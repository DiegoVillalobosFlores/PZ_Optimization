package pzopt;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Locale;
import zombie.GameTime;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.physics.WorldSimulation;
import zombie.iso.IsoCamera;
import zombie.iso.IsoUtils;
import zombie.iso.PlayerCamera;
import zombie.vehicles.BaseVehicle;

/**
 * Measurement rig for the driving smoothness pass (`devDriveJitter`, 2026-09-26): one row per game frame, taken just
 * before the frame is rendered (all updates done, the camera fixed), with what the frame will show of player 0's
 * vehicle: the frame's start time, the simulation step it advanced, the physics steps it ran, the vehicle's physics and
 * drawn positions, the camera offsets the world is drawn at and the vehicle's position on screen. harness/drivejitter.py
 * turns it into the smoothness numbers (screen-space residuals of the world scroll and of the car against a smooth fit
 * of the same frames).
 *
 * <p>Columns (pzopt-drivejitter.out, cache dir): t_us (frame start, since the first row), sim_dt_us (the step the game
 * simulated this frame, fpsMultiplier / 60 s), steps (Bullet steps run this frame), kmh, phys_x phys_y (jniTransform +
 * the world offset), car_x car_y (the vehicle's position the frame draws), ply_x ply_y (the camera character),
 * cam_x cam_y (PlayerCamera.getOffX/Y: the offsets the world is drawn at, offscreen pixels), camf_x camf_y (the same
 * before the whole-pixel truncation), look_x look_y (the driving look-ahead, rightClickX/Y), stale_x stale_y (offX/Y
 * minus the centre the camera would take from the character's position now: non-zero = the camera was placed before the
 * character moved this frame), scr_x scr_y (the vehicle on the offscreen buffer, pixels), zoom, alpha (the render
 * interpolation fraction when a smoothing key is on, else -1), mono_us (the frame start on CLOCK_MONOTONIC, the clock of
 * present.txt: harness/drivejitter.py --present maps each frame to the vblank it was shown on).
 */
public final class DriveJitter {
   private DriveJitter() {
   }

   static final boolean ON = Config.DEV_DRIVE_JITTER;
   private static final StringBuilder buf = new StringBuilder(1 << 16);
   private static Writer out;
   private static boolean failed;
   private static long t0;
   private static int lastBulletFrame = Integer.MIN_VALUE;
   /** Set by the smoothing code each frame (render interpolation fraction), -1 when none ran. */
   public static float alpha = -1f;

   // which game frame each swap shows (pzopt-driveswap.out: "<frame start mono us> <swap return mono us>"): the game
   // thread files its frame's start under the push count, the render thread takes them in acquire order
   private static final long[] pushedStart = new long[64];
   private static volatile long pushes;
   private static long acquires;
   private static long shownStart;
   private static long currentStart;
   private static final StringBuilder swaps = new StringBuilder(1 << 14);
   private static Writer swapOut;

   /** Game thread, RenderThread.Ready after pushFrameDown. */
   public static void pushed() {
      if (!ON) {
         return;
      }
      long n = pushes + 1;
      pushedStart[(int)(n & 63)] = currentStart;
      pushes = n;
   }

   /** Render thread, a game frame's state was acquired. */
   public static void acquired() {
      if (!ON) {
         return;
      }
      long n = acquires + 1;
      if (n <= pushes) {
         acquires = n;
         shownStart = pushedStart[(int)(n & 63)];
      }
   }

   /** Render thread, Display.update after the swap returned. */
   public static void swapped() {
      if (!ON || shownStart == 0L || out == null) {
         return;
      }
      synchronized (swaps) {
         swaps.append(shownStart / 1000L).append(' ').append(System.nanoTime() / 1000L).append('\n');
      }
   }

   /** GameWindow.frameStep, after the frame's update and before its render. */
   public static void beforeRender(long frameStartNs) {
      if (!ON || failed) {
         return;
      }
      currentStart = frameStartNs;
      IsoPlayer p = IsoPlayer.players[0];
      BaseVehicle v = p == null ? null : p.getVehicle();
      WorldSimulation ws = WorldSimulation.instance;
      int bf = ws.getBulletFrameNo();
      int steps = bf != lastBulletFrame && lastBulletFrame != Integer.MIN_VALUE ? Math.round(ws.periodSec / 0.01f) : 0;
      lastBulletFrame = bf;
      if (v == null) {
         return;
      }
      if (out == null && !open()) {
         return;
      }
      if (t0 == 0L) {
         t0 = frameStartNs;
      }
      PlayerCamera cam = IsoCamera.cameras[0];
      float carZ = v.jniTransform.origin.y / 2.44949f;
      float camX = cam.getOffX();
      float camY = cam.getOffY();
      float scrX = IsoUtils.XToScreen(v.getX(), v.getY(), carZ, 0) - camX;
      float scrY = IsoUtils.YToScreen(v.getX(), v.getY(), carZ, 0) - camY;
      float chrZ = IsoCamera.frameState.calculateCameraZ(p);
      float cx = IsoUtils.XToScreen(p.getX() + cam.deferedX, p.getY() + cam.deferedY, chrZ, 0) - IsoCamera.getOffscreenWidth(0) / 2.0f + IsoCamera.playerOffsetX;
      float cy = IsoUtils.YToScreen(p.getX() + cam.deferedX, p.getY() + cam.deferedY, chrZ, 0) - IsoCamera.getOffscreenHeight(0) / 2.0f
            - p.getOffsetY() * 1.5f + IsoCamera.playerOffsetY;
      StringBuilder b = new StringBuilder(256);
      b.append((frameStartNs - t0) / 1000L).append(' ')
            .append(Math.round(GameTime.instance.fpsMultiplier / 60f * 1e6f)).append(' ')
            .append(steps).append(' ');
      f(b, v.getCurrentSpeedKmHour());
      f(b, v.jniTransform.origin.x + ws.offsetX);
      f(b, v.jniTransform.origin.z + ws.offsetY);
      f(b, v.getX());
      f(b, v.getY());
      f(b, p.getX());
      f(b, p.getY());
      f(b, camX);
      f(b, camY);
      f(b, cam.offX + cam.rightClickX);
      f(b, cam.offY + cam.rightClickY);
      f(b, cam.rightClickX);
      f(b, cam.rightClickY);
      f(b, cam.offX - cx);
      f(b, cam.offY - cy);
      f(b, scrX);
      f(b, scrY);
      f(b, Core.getInstance().getZoom(0));
      f(b, alpha);
      b.append(frameStartNs / 1000L).append('\n');
      synchronized (buf) {
         buf.append(b);
      }
   }

   private static void f(StringBuilder b, float x) {
      b.append(String.format(Locale.ROOT, "%.4f", x)).append(' ');
   }

   private static boolean open() {
      try {
         out = new FileWriter(new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-drivejitter.out"));
         out.write("t_us sim_dt_us steps kmh phys_x phys_y car_x car_y ply_x ply_y cam_x cam_y camf_x camf_y look_x look_y stale_x stale_y scr_x scr_y zoom alpha mono_us\n");
         out.flush();
      } catch (IOException | RuntimeException e) {
         Log.warn("drive jitter: off after " + e);
         failed = true;
         return false;
      }
      try {
         swapOut = new FileWriter(new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-driveswap.out"));
      } catch (IOException e) {
         Log.warn("drive jitter: no swap log: " + e);
      }
      Thread t = new Thread(DriveJitter::flushLoop, "pzopt-drivejitter");
      t.setDaemon(true);
      t.start();
      Log.info("drive jitter: logging pzopt-drivejitter.out");
      return true;
   }

   private static void flushLoop() {
      while (true) {
         try {
            Thread.sleep(250L);
            String s;
            synchronized (buf) {
               s = buf.toString();
               buf.setLength(0);
            }
            if (!s.isEmpty()) {
               out.write(s);
               out.flush();
            }
            synchronized (swaps) {
               s = swaps.toString();
               swaps.setLength(0);
            }
            if (!s.isEmpty() && swapOut != null) {
               swapOut.write(s);
               swapOut.flush();
            }
         } catch (InterruptedException | IOException e) {
            return;
         }
      }
   }
}
