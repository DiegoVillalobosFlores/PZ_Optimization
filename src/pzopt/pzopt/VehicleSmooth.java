package pzopt;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.HashMap;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.physics.Bullet;
import zombie.core.physics.WorldSimulation;
import zombie.iso.IsoCamera;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.iso.PlayerCamera;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;

/**
 * Smooth vehicle motion on screen (`vehicleSmooth`, 2026-09-26, docs/findings-car-jitter-2026-09-26.md).
 *
 * <p>Stock steps Bullet at a fixed 100 Hz ({@code WorldSimulation.updatePhysic}: whole 10 ms steps, the remainder
 * carried to the next frame) and draws each vehicle, its passengers and the camera that follows the driver at the
 * state after the last whole step. At any frame rate that is not a multiple of 100 the car advances 0, 1 or 2 steps
 * a frame in an uneven pattern (240 fps: 0,1,0,0,1,...; 120 fps: a still frame every sixth), and since the camera sits
 * on the driver the whole world judders with it: the "micro rubber band" of driving.
 *
 * <p>This keeps the simulation exactly as stock and changes only what a frame draws: WorldSimulation reads every
 * vehicle's physics state before its last step of the frame ({@link #beforeLastStep}) and after it
 * ({@link #afterSteps}); for the render ({@link #beforeRender} .. {@link #afterRender}, around
 * {@code GameWindow.renderInternal}) each moving vehicle is put at the state between the two that matches the
 * time the frame stands for, its seated characters at their seats on that transform and the camera re-centred on the
 * driver there; afterwards every value goes back. Modes: {@code interp} draws the state {@code alpha} of the way from
 * the previous step to the last one (alpha = the carried remainder / 10 ms; one step, 10 ms, behind the simulation,
 * like a game engine's interpolated rigid body), {@code extrap} carries the last step forward by the remainder (no
 * added delay, overshoots for one step when a car stops dead against something).
 */
public final class VehicleSmooth {
   private VehicleSmooth() {
   }

   static final int OFF = 0, INTERP = 1, EXTRAP = 2;
   static final int MODE = mode(Config.VEHICLE_SMOOTH);

   private static int mode(String s) {
      switch (s) {
         case "interp":
            return INTERP;
         case "extrap":
            return EXTRAP;
         default:
            return OFF;
      }
   }

   /** driveCameraLate: centre the camera on the driver after every update of the frame, not inside the player's. */
   static final boolean CAMERA_LATE = Config.DRIVE_CAMERA_LATE;

   public static boolean enabled() {
      return (MODE != OFF || CAMERA_LATE) && Overrides.enabled() && !GameServer.server && !GameClient.client;
   }

   /** One vehicle's physics state as Bullet reports it (world-offset space, like jniTransform). */
   private static final class Snap {
      final Vector3f pos = new Vector3f();
      final Quaternionf rot = new Quaternionf();
      final float[] wheel = new float[16]; // steering, rotation, skid, suspension x 4
      int wheels;
      int stamp;
   }

   private static final class Shown {
      BaseVehicle v;
      final zombie.core.physics.Transform jni = new zombie.core.physics.Transform();
      final float[] wheel = new float[16];
      float x, nx, y, ny, z, lz;
      final ArrayList<IsoGameCharacter> chr = new ArrayList<>(4);
      final ArrayList<float[]> chrPos = new ArrayList<>(4);
   }

   private static final HashMap<Integer, Snap> prev = new HashMap<>();
   private static final HashMap<Integer, Snap> curr = new HashMap<>();
   private static final float[] ff = new float[8192];
   private static int stamp; // bumped once per frame that stepped
   private static float alpha;
   private static final ArrayList<Shown> shown = new ArrayList<>();
   private static final ArrayList<Shown> pool = new ArrayList<>();
   private static final zombie.core.physics.Transform tmp = new zombie.core.physics.Transform();
   private static final Quaternionf qa = new Quaternionf(), qb = new Quaternionf();
   private static final Vector3f seat = new Vector3f();
   private static float camOffX, camOffY, camTOffX, camTOffY;
   private static boolean camSaved;
   private static MethodHandle updateTransform, getTOffX, setTOffX, getTOffY, setTOffY;
   private static boolean broken;

   /** WorldSimulation.updatePhysic, right before the frame's last Bullet step. */
   public static void beforeLastStep() {
      if (MODE == OFF || !enabled()) {
         return;
      }
      read(prev);
   }

   /** WorldSimulation.updatePhysic, after the frame's steps: {@code localTime} is the carried remainder (s). */
   public static void afterSteps(int steps, float localTime, float step) {
      if (!enabled()) {
         return;
      }
      if (steps > 0 && MODE != OFF) {
         stamp++;
         read(curr);
      }
      alpha = Math.max(0f, Math.min(1f, localTime / step));
      DriveJitter.alpha = alpha;
   }

   static final int STEP_HZ = Math.max(30, Math.min(1000, Config.PHYSICS_STEP_HZ));
   static final boolean FRAME_STEPS = "frame".equals(Config.PHYSICS_STEP_MODE);

   /** True when the physics runs with other steps than stock's fixed 100 Hz (physicsStepHz / physicsStepMode). */
   public static boolean stepsChanged() {
      return (STEP_HZ != 100 || FRAME_STEPS) && Overrides.enabled() && !GameServer.server && !GameClient.client;
   }

   /** The fixed step (physicsStepHz), and with physicsStepMode=frame the largest step a frame is split into. */
   public static float stepSeconds() {
      return 1f / STEP_HZ;
   }

   public static boolean frameSteps() {
      return FRAME_STEPS;
   }

   private static void read(HashMap<Integer, Snap> into) {
      int total = Bullet.getVehicleCount();
      int offset = 0;
      int n;
      int s = into == curr ? stamp : stamp + 1; // prev is read before the stamp of its step is taken
      while (offset < total && (n = Bullet.getVehiclePhysics(offset, ff)) > 0) {
         offset += n;
         int fn = 0;
         for (int i = 0; i < n; i++) {
            int id = (int)ff[fn++];
            Snap sn = into.get(id);
            if (sn == null) {
               sn = new Snap();
               into.put(id, sn);
            }
            sn.pos.set(ff[fn], ff[fn + 1], ff[fn + 2]);
            sn.rot.set(ff[fn + 3], ff[fn + 4], ff[fn + 5], ff[fn + 6]);
            fn += 7 + 3 + 2; // position, rotation, velocity, speed, collide
            int wc = (int)ff[fn++];
            sn.wheels = Math.min(wc, 4);
            for (int w = 0; w < wc; w++) {
               if (w < 4) {
                  System.arraycopy(ff, fn, sn.wheel, w * 4, 4);
               }
               fn += 4;
            }
            sn.stamp = s;
         }
      }
   }

   private static boolean handles() {
      if (updateTransform != null) {
         return true;
      }
      if (broken) {
         return false;
      }
      try {
         var m = BaseVehicle.class.getDeclaredMethod("updateTransform");
         m.setAccessible(true);
         updateTransform = MethodHandles.lookup().unreflect(m);
         var f = PlayerCamera.class.getDeclaredField("tOffX");
         f.setAccessible(true);
         getTOffX = MethodHandles.lookup().unreflectGetter(f);
         setTOffX = MethodHandles.lookup().unreflectSetter(f);
         f = PlayerCamera.class.getDeclaredField("tOffY");
         f.setAccessible(true);
         getTOffY = MethodHandles.lookup().unreflectGetter(f);
         setTOffY = MethodHandles.lookup().unreflectSetter(f);
         return true;
      } catch (ReflectiveOperationException | RuntimeException e) {
         Log.warn("vehicleSmooth: off after " + e);
         broken = true;
         updateTransform = null;
         return false;
      }
   }

   /** GameWindow.frameStep, before renderInternal: put the moving vehicles where this frame should show them. */
   public static void beforeRender() {
      afterRender(); // a render that threw left the shown values in place
      if (!enabled() || IsoWorld.instance == null || IsoWorld.instance.currentCell == null || !handles()) {
         return;
      }
      float t = MODE == EXTRAP ? 1f + alpha : alpha;
      WorldSimulation ws = WorldSimulation.instance;
      boolean moved = false;
      try {
         for (BaseVehicle v : MODE == OFF ? java.util.Collections.<BaseVehicle>emptySet() : IsoWorld.instance.currentCell.getVehicles()) {
            Snap a = prev.get((int)v.vehicleId);
            Snap b = curr.get((int)v.vehicleId);
            if (a == null || b == null || a.stamp != stamp || b.stamp != stamp) {
               continue; // not stepped together in the last stepping frame (new, removed, or asleep)
            }
            zombie.core.physics.Transform j = v.jniTransform;
            if (Math.abs(j.origin.x - b.pos.x) > 1e-3f || Math.abs(j.origin.z - b.pos.z) > 1e-3f || Math.abs(j.origin.y - b.pos.y) > 1e-3f) {
               continue; // moved by something other than the last step (teleport, chunk-edge clamp)
            }
            float dx = b.pos.x - a.pos.x, dz = b.pos.z - a.pos.z;
            if (dx * dx + dz * dz > 1f || (dx == 0f && dz == 0f && a.rot.equals(b.rot))) {
               continue; // a jump of over a tile in 10 ms is a reset, not motion; still cars stay as they are
            }
            Shown s = pool.isEmpty() ? new Shown() : pool.remove(pool.size() - 1);
            s.v = v;
            s.jni.set(j);
            s.x = v.getX();
            s.nx = v.getNextX();
            s.y = v.getY();
            s.ny = v.getNextY();
            s.z = v.getZ();
            s.lz = v.getLastZ();
            int wheels = Math.min(b.wheels, v.wheelInfo == null ? 0 : v.wheelInfo.length);
            for (int w = 0; w < wheels; w++) {
               BaseVehicle.WheelInfo wi = v.wheelInfo[w];
               s.wheel[w * 4] = wi.steering;
               s.wheel[w * 4 + 1] = wi.rotation;
               s.wheel[w * 4 + 2] = wi.skidInfo;
               s.wheel[w * 4 + 3] = wi.suspensionLength;
            }
            shown.add(s);
            // the shown transform
            j.origin.set(a.pos).lerp(b.pos, t);
            a.rot.slerp(b.rot, t, qa);
            j.setRotation(qa.normalize());
            for (int w = 0; w < wheels; w++) {
               BaseVehicle.WheelInfo wi = v.wheelInfo[w];
               wi.steering = lerp(a.wheel[w * 4], b.wheel[w * 4], t);
               wi.rotation = lerpAngle(a.wheel[w * 4 + 1], b.wheel[w * 4 + 1], t);
               wi.suspensionLength = lerp(a.wheel[w * 4 + 3], b.wheel[w * 4 + 3], t);
            }
            float nx = s.x + (j.origin.x - s.jni.origin.x);
            float ny = s.y + (j.origin.z - s.jni.origin.z);
            v.setX(nx);
            v.setY(ny);
            updateTransform.invokeExact(v);
            s.chr.clear();
            s.chrPos.clear();
            int seats = v.getMaxPassengers();
            for (int i = 0; i < seats; i++) {
               IsoGameCharacter c = v.getCharacter(i);
               if (c == null) {
                  continue;
               }
               s.chr.add(c);
               s.chrPos.add(new float[]{c.getX(), c.getNextX(), c.getY(), c.getNextY(), c.getZ(), c.getLastZ()});
               Vector3f p = v.getPassengerWorldPos(i, seat);
               if (p != null) {
                  c.setX(p.x);
                  c.setY(p.y);
                  c.setZ(p.z);
               }
            }
            moved = true;
         }
         if (moved || CAMERA_LATE) {
            recentre();
         }
      } catch (Throwable e) {
         Log.warn("vehicleSmooth: off after " + e);
         broken = true;
         updateTransform = null;
         afterRender();
      }
   }

   /** The camera follows the driver: centre it on the shown driver (PlayerCamera.center), keep the sim's values. */
   private static void recentre() throws Throwable {
      IsoPlayer p = IsoPlayer.players[0];
      if (p == null || p.getVehicle() == null || IsoPlayer.numPlayers > 1) {
         return;
      }
      PlayerCamera cam = IsoCamera.cameras[0];
      camOffX = cam.offX;
      camOffY = cam.offY;
      camTOffX = (float)getTOffX.invokeExact(cam);
      camTOffY = (float)getTOffY.invokeExact(cam);
      camSaved = true;
      zombie.characters.IsoGameCharacter prevChr = IsoCamera.getCameraCharacter();
      IsoCamera.setCameraCharacter(p);
      cam.center();
      IsoCamera.setCameraCharacter(prevChr);
   }

   /** GameWindow.frameStep, after renderInternal: every value back to the simulation's. */
   public static void afterRender() {
      if (shown.isEmpty() && !camSaved) {
         return;
      }
      for (int k = shown.size() - 1; k >= 0; k--) {
         Shown s = shown.get(k);
         BaseVehicle v = s.v;
         v.jniTransform.set(s.jni);
         v.setX(s.x);
         v.setNextX(s.nx);
         v.setY(s.y);
         v.setNextY(s.ny);
         v.setZ(s.z);
         v.setLastZ(s.lz);
         int wheels = v.wheelInfo == null ? 0 : Math.min(4, v.wheelInfo.length);
         for (int w = 0; w < wheels; w++) {
            BaseVehicle.WheelInfo wi = v.wheelInfo[w];
            wi.steering = s.wheel[w * 4];
            wi.rotation = s.wheel[w * 4 + 1];
            wi.skidInfo = s.wheel[w * 4 + 2];
            wi.suspensionLength = s.wheel[w * 4 + 3];
         }
         for (int i = 0; i < s.chr.size(); i++) {
            IsoGameCharacter c = s.chr.get(i);
            float[] q = s.chrPos.get(i);
            c.setX(q[0]);
            c.setNextX(q[1]);
            c.setY(q[2]);
            c.setNextY(q[3]);
            c.setZ(q[4]);
            c.setLastZ(q[5]);
         }
         if (updateTransform != null) {
            try {
               updateTransform.invokeExact(v);
            } catch (Throwable e) {
               Log.warn("vehicleSmooth: restore " + e);
            }
         }
         s.v = null;
         s.chr.clear();
         s.chrPos.clear();
         pool.add(s);
      }
      shown.clear();
      if (camSaved) {
         PlayerCamera cam = IsoCamera.cameras[0];
         cam.offX = camOffX;
         cam.offY = camOffY;
         try {
            setTOffX.invokeExact(cam, camTOffX);
            setTOffY.invokeExact(cam, camTOffY);
         } catch (Throwable e) {
            Log.warn("vehicleSmooth: camera restore " + e);
         }
         camSaved = false;
      }
   }

   private static float lerp(float a, float b, float t) {
      return a + (b - a) * t;
   }

   static float lerpAngle(float a, float b, float t) {
      float d = b - a;
      float tau = (float)(Math.PI * 2);
      d -= tau * (float)Math.floor((d + Math.PI) / tau);
      return a + d * t;
   }
}
