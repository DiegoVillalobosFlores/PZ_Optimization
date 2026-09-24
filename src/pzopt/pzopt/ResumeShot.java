package pzopt;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Properties;
import javax.imageio.ImageIO;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.RenderThread;
import zombie.core.textures.ImageData;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureID;
import zombie.core.utils.DirectBufferAllocator;
import zombie.core.utils.ImageUtils;
import zombie.core.utils.WrappedBuffer;
import zombie.iso.IsoCamera;
import zombie.iso.IsoUtils;

/**
 * resumeShot (2026-09-22, the maintainer's design: "the illusion of an instant load"): when the game exits, the view
 * around the player (at the player's zoom, no UI; what it keeps is resumeShotDetail: the whole frame by default, since
 * 2026-09-24, or ground-level floors only, the first design) is kept next to the save with the chunk grid's screen geometry; Continue shows it at full brightness in the square of
 * chunks around the player that pzopt.CenterFirstLoad loads first (whole chunks popping in, in random bursts, the way the
 * live world builds itself on world entry, the rest black), and the live world (objects and all) then builds over it from the
 * centre outwards.
 *
 * Capture (SavefileThumbnail.create, exit saves only: Core.exiting or GameWindow.exit): beginCapture's flags make
 * FBORenderCell draw what resumeShotDetail keeps (floors: level 0 floors only); every chunk level is invalidated so they
 * re-bake that way, the world is rendered once at the player's zoom and composited, a render-thread drawer reads the back
 * buffer, then endCapture clears the flags and the chunks are invalidated again. A daemon thread scales the image to at most MAX_WIDTH wide and writes FILE plus GEOMETRY (the screen
 * position of the player's chunk corner and the screen vectors of one chunk step in world x and y, normalised to the
 * screen size).
 * Show: GameLoadingState.enter starts the decode on a daemon thread; the (noLoadingScreen) black loading frame draws the
 * square (draw, one texture upload on the main thread), and on world entry the square stays over the world
 * and fades as the chunk map lights up (drawOverWorld), then the texture is freed.
 */
public final class ResumeShot {
   static final String FILE = "pzopt-resume.jpg";
   static final String GEOMETRY = "pzopt-resume.properties";
   /** how long the last Continue of this save took from the loading frame to world entry (ms), paces the fill-in */
   static final String LOAD_TIME = "pzopt-resume-load.txt";
   static final int MAX_WIDTH = 1920;
   /** chunks around the player's chunk shown (the square pzopt.CenterFirstLoad loads first) */
   static final int SQUARE_RADIUS = CenterFirstLoad.RADIUS;
   /*
    * Read by FBORenderCell on the game thread while the capture frame is recorded (all false otherwise), set from
    * resumeShotDetail: floors = ground-level floors only; buildings = every level's floors, walls, doors, furniture and
    * items, no trees or translucent tiles; world = everything static, trees and translucent tiles included; full = the
    * frame as seen, characters, vehicles and corpses too. Everything but "full" leaves out what moves.
    */
   /** the capture frame is being recorded (every level bakes in that one frame) */
   public static volatile boolean capturing;
   /** floors: ground-level floors only (no upper floors, walls, objects) */
   public static volatile boolean floorOnly;
   /** floors, buildings: no trees */
   public static volatile boolean noTrees;
   /** floors, buildings: no translucent tiles */
   public static volatile boolean noTranslucent;
   /** every level but full: no players, characters, vehicles, corpses or their shadows */
   public static volatile boolean noMoving;

   /** resumeShotDetail as 0 (floors) .. 3 (full) */
   static int detail() {
      switch (Config.RESUME_SHOT_DETAIL) {
         case "buildings":
            return 1;
         case "world":
            return 2;
         case "full":
            return 3;
         default:
            return 0;
      }
   }

   /** From SavefileThumbnail right before the capture frame. */
   public static void beginCapture() {
      int d = detail();
      floorOnly = d == 0;
      noTrees = d <= 1;
      noTranslucent = d <= 1;
      noMoving = d <= 2;
      capturing = true;
   }

   /** From SavefileThumbnail after the capture frame. */
   public static void endCapture() {
      capturing = false;
      floorOnly = false;
      noTrees = false;
      noTranslucent = false;
      noMoving = false;
   }
   /** set by GameWindow.exit before its save (the window-close path; Core.exiting covers the in-game quit) */
   public static volatile boolean exitSave;

   private static volatile byte[] rgba;
   private static volatile int width;
   private static volatile int height;
   private static volatile float[] geom; // ox, oy, vxx, vxy, vyx, vyy (screen-normalised)
   private static Texture texture;
   private static long revealStartMs; // the loading frame's start (GameLoadingState.enter)
   private static boolean loadTimePending; // this load's loading-frame-to-world-entry time not written yet
   private static int releaseIn = -1;
   private static volatile boolean hasShot;

   private ResumeShot() {
   }

   public static boolean enabled() {
      return Config.RESUME_SHOT && NoLoadingScreen.active();
   }

   /**
    * SavefileThumbnail.create: this save is the one the game exits with, so the floor capture runs. Both exit paths
    * (IngameState's quit, which clears Core.exiting before it saves, and GameWindow.exit) stop player saves right before
    * their last save; autosaves do not.
    */
   public static boolean exiting() {
      if (!enabled()) {
         return false;
      }
      if (Core.exiting || exitSave) {
         return true;
      }
      return zombie.savefile.PlayerDB.isAllow() && !zombie.savefile.PlayerDB.getInstance().canSavePlayers;
   }

   /**
    * From SavefileThumbnail.create (game thread) right after the floor-only world was composited: queue the read-back and
    * record the chunk grid's screen geometry for the player's chunk.
    */
   public static void queueCapture(int playerIndex) {
      IsoPlayer p = IsoPlayer.players[playerIndex];
      if (p == null) {
         return;
      }
      float zoom = Core.getInstance().getZoom(playerIndex);
      float sw = Core.getInstance().getScreenWidth();
      float sh = Core.getInstance().getScreenHeight();
      int cx = (int)Math.floor(p.getX() / 8.0F);
      int cy = (int)Math.floor(p.getY() / 8.0F);
      float offX = IsoCamera.cameras[playerIndex].getOffX();
      float offY = IsoCamera.cameras[playerIndex].getOffY();
      float ox = (IsoUtils.XToScreen(cx * 8, cy * 8, 0.0F, 0) - offX) / zoom;
      float oy = (IsoUtils.YToScreen(cx * 8, cy * 8, 0.0F, 0) - offY) / zoom;
      float ax = (IsoUtils.XToScreen(cx * 8 + 8, cy * 8, 0.0F, 0) - offX) / zoom;
      float ay = (IsoUtils.YToScreen(cx * 8 + 8, cy * 8, 0.0F, 0) - offY) / zoom;
      float bx = (IsoUtils.XToScreen(cx * 8, cy * 8 + 8, 0.0F, 0) - offX) / zoom;
      float by = (IsoUtils.YToScreen(cx * 8, cy * 8 + 8, 0.0F, 0) - offY) / zoom;
      float[] g = {ox / sw, oy / sh, (ax - ox) / sw, (ay - oy) / sh, (bx - ox) / sw, (by - oy) / sh};
      File out = ZomboidFileSystem.instance.getFileInCurrentSave(FILE);
      File meta = ZomboidFileSystem.instance.getFileInCurrentSave(GEOMETRY);
      SpriteRenderer.instance.drawGeneric(new Capture(out, meta, g));
   }

   private static final class Capture extends TextureDraw.GenericDrawer {
      private final File out;
      private final File meta;
      private final float[] g;

      Capture(File out, File meta, float[] g) {
         this.out = out;
         this.meta = meta;
         this.g = g;
      }

      @Override
      public void render() {
         int w = Core.getInstance().getScreenWidth();
         int h = Core.getInstance().getScreenHeight();
         if (w <= 0 || h <= 0) {
            return;
         }
         int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
         ByteBuffer buf = MemoryUtil.memAlloc(w * h * 3);
         try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
            GL11.glReadBuffer(GL11.GL_BACK);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, w, h, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buf);
            byte[] px = new byte[w * h * 3];
            buf.get(px);
            Thread t = new Thread(() -> write(px, w, h, this.out, this.meta, this.g), "pzopt-resume-shot");
            t.setDaemon(true);
            t.start();
         } catch (Throwable e) {
            Log.warn("resume shot: " + e);
         } finally {
            MemoryUtil.memFree(buf);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
         }
      }
   }

   /** Area-average scale to at most MAX_WIDTH wide, flip (GL rows are bottom-up), write the JPEG and the geometry. */
   static void write(byte[] px, int w, int h, File out, File meta, float[] g) {
      try {
         int tw = Math.min(MAX_WIDTH, w);
         int th = Math.max(1, (int)((long)h * tw / w));
         BufferedImage img = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
         for (int ty = 0; ty < th; ty++) {
            int y0 = ty * h / th;
            int y1 = Math.max(y0 + 1, (ty + 1) * h / th);
            for (int tx = 0; tx < tw; tx++) {
               int x0 = tx * w / tw;
               int x1 = Math.max(x0 + 1, (tx + 1) * w / tw);
               long r = 0, gg = 0, b = 0;
               int n = 0;
               for (int y = y0; y < y1; y++) {
                  int row = (h - 1 - y) * w; // GL origin is bottom-left
                  for (int x = x0; x < x1; x++) {
                     int i = (row + x) * 3;
                     r += px[i] & 0xFF;
                     gg += px[i + 1] & 0xFF;
                     b += px[i + 2] & 0xFF;
                     n++;
                  }
               }
               img.setRGB(tx, ty, (int)(r / n) << 16 | (int)(gg / n) << 8 | (int)(b / n));
            }
         }
         File tmp = new File(out.getPath() + ".tmp");
         ImageIO.write(img, "jpg", tmp);
         Properties p = new Properties();
         String[] keys = {"ox", "oy", "vxx", "vxy", "vyx", "vyy"};
         for (int i = 0; i < keys.length; i++) {
            p.setProperty(keys[i], Float.toString(g[i]));
         }
         try (OutputStream os = new FileOutputStream(meta)) {
            p.store(os, "pzopt resume shot: screen position of the player's chunk corner and one chunk step in x / y");
         }
         if (!tmp.renameTo(out)) {
            out.delete();
            tmp.renameTo(out);
         }
      } catch (Throwable e) {
         Log.warn("resume shot: could not write " + out + ": " + e);
      }
   }

   /** From GameLoadingState.enter: decode the save's shot and geometry on a daemon thread (nothing if there is none). */
   public static void startLoad() {
      rgba = null;
      geom = null;
      releaseIn = -1;
      hasShot = false;
      revealStartMs = System.currentTimeMillis();
      loadTimePending = true;
      expandMs = DEFAULT_EXPAND_MS;
      lastLoadMs = 0L;
      if (!enabled()) {
         return;
      }
      File f = ZomboidFileSystem.instance.getFileInCurrentSave(FILE);
      File m = ZomboidFileSystem.instance.getFileInCurrentSave(GEOMETRY);
      if (!f.isFile() || !m.isFile()) {
         return;
      }
      hasShot = true;
      File lt = ZomboidFileSystem.instance.getFileInCurrentSave(LOAD_TIME);
      Thread t = new Thread(() -> {
         try {
            Properties p = new Properties();
            try (InputStream in = new FileInputStream(m)) {
               p.load(in);
            }
            String[] keys = {"ox", "oy", "vxx", "vxy", "vyx", "vyy"};
            if (lt.isFile()) {
               try {
                  long ms = Long.parseLong(Files.readString(lt.toPath()).trim());
                  lastLoadMs = ms;
                  expandMs = Math.max(MIN_EXPAND_MS, Math.min(MAX_EXPAND_MS, ms * FILL_SHARE));
               } catch (NumberFormatException e) {
                  // a damaged file: keep the default pace
               }
            }
            float[] g = new float[keys.length];
            for (int i = 0; i < keys.length; i++) {
               g[i] = Float.parseFloat(p.getProperty(keys[i]));
            }
            BufferedImage img = ImageIO.read(f);
            if (img == null) {
               return;
            }
            int w = img.getWidth();
            int h = img.getHeight();
            byte[] out = new byte[w * h * 4];
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
               img.getRGB(0, y, w, 1, row, 0, w);
               for (int x = 0; x < w; x++) {
                  int q = row[x];
                  int i = (y * w + x) * 4;
                  out[i] = (byte)(q >> 16);
                  out[i + 1] = (byte)(q >> 8);
                  out[i + 2] = (byte)q;
                  out[i + 3] = (byte)0xFF;
               }
            }
            width = w;
            height = h;
            geom = g;
            rgba = out;
         } catch (Throwable e) {
            hasShot = false;
            Log.warn("resume shot: could not read " + f + ": " + e);
         }
      }, "pzopt-resume-shot-load");
      t.setDaemon(true);
      t.start();
   }

   /** GameLoadingState.render: this save has a cached shot, so the loading frame shows it (else the stock loading screen). */
   public static boolean hasShot() {
      return hasShot;
   }

   /** From the loading frame (main thread): the square with the tile effect; false if the shot is not decoded yet. */
   public static boolean draw() {
      if (texture == null) {
         byte[] px = rgba;
         if (px == null) {
            return false;
         }
         rgba = null;
         texture = upload(px, width, height);
      }
      drawTiles(System.currentTimeMillis() - revealStartMs, 1.0F);
      return true;
   }

   /**
    * The square of chunks around the player's chunk, chunk by chunk, each cut out of the shot along its diamond (UVs =
    * the corners' screen positions) and drawn as captured once shown (the rest of the screen stays black).
    */
   private static void drawTiles(long t, float alpha) {
      float[] g = geom;
      if (g == null || texture == null) {
         return;
      }
      float sw = Core.getInstance().getScreenWidth();
      float sh = Core.getInstance().getScreenHeight();
      float u0 = texture.getXStart();
      float v0 = texture.getYStart();
      float du = texture.getXEnd() - u0;
      float dv = texture.getYEnd() - v0;
      float cxx = g[2]; // one chunk in world x, screen-normalised
      float cxy = g[3];
      float cyx = g[4]; // one chunk in world y
      float cyy = g[5];
      float[] bursts = burstTimes(t);
      for (int i = -SQUARE_RADIUS; i <= SQUARE_RADIUS; i++) {
         for (int j = -SQUARE_RADIUS; j <= SQUARE_RADIUS; j++) {
            if (!shown(i, j, t, bursts)) {
               continue;
            }
            float x0 = g[0] + i * cxx + j * cyx;
            float y0 = g[1] + i * cxy + j * cyy;
            float x1 = x0 + cxx;
            float y1 = y0 + cxy;
            float x2 = x1 + cyx;
            float y2 = y1 + cyy;
            float x3 = x0 + cyx;
            float y3 = y0 + cyy;
            SpriteRenderer.instance.renderPoly(texture, x0 * sw, y0 * sh, x1 * sw, y1 * sh, x2 * sw, y2 * sh, x3 * sw, y3 * sh,
                  1.0F, 1.0F, 1.0F, alpha,
                  u0 + x0 * du, v0 + y0 * dv, u0 + x1 * du, v0 + y1 * dv, u0 + x2 * du, v0 + y2 * dv, u0 + x3 * du, v0 + y3 * dv);
         }
      }
   }

   /**
    * the loading effect, looping, modelled on how the live world builds itself on world entry (the resumeShot=false
    * recording of run worldload-rec2, 2026-09-24, harness/revealmap.py): whole chunks pop in (no fade, no tile-level
    * order), in bursts of several chunks at once at uneven intervals, in no spatial order, so neighbours leave black
    * holes that close later. Here every chunk draws one of BURSTS bursts at random per loop, the bursts are spread over
    * expandMs by random gaps and the last one lands at expandMs; after HOLD_MS the chunks pop out the same way with a
    * fresh draw, and after GAP_MS the next loop starts. (The live world does the same in ~270 ms: 50 chunks in 9 bursts.)
    * expandMs is FILL_SHARE of this save's last loading-frame-to-world-entry time (LOAD_TIME, written by onWorldEntered),
    * so the square is complete just before the world appears; DEFAULT_EXPAND_MS before the first measured Continue.
    * The stored time is the average of the last value and this load's, and a load that beats the pace finishes the fill
    * within FINISH_MS of world entry (drawOverWorld).
    */
   static final float DEFAULT_EXPAND_MS = 3000.0F;
   static final float MIN_EXPAND_MS = 500.0F;
   static final float MAX_EXPAND_MS = 20000.0F;
   static final float FILL_SHARE = 0.85F;
   static final float HOLD_MS = 2500.0F;
   static final float GAP_MS = 500.0F;
   private static volatile float expandMs = DEFAULT_EXPAND_MS;
   /** this save's stored load time as read (0 = none); the new one is averaged with it so one slow load does not set the pace */
   private static volatile long lastLoadMs;
   /** chunks still black at world entry pop in within this */
   static final float FINISH_MS = 300.0F;
   /** bursts per fill (the live world: 9 for the ~50 chunks on screen) */
   static final int BURSTS = 9;
   /** the gaps between bursts vary from GAP_MIN to GAP_MIN + 1 (relative; the live world's ran 17-67 ms) */
   static final float GAP_MIN = 0.35F;

   /**
    * the cycle-relative times (ms) of this loop's bursts at time t: BURSTS pop-in times, then BURSTS pop-out times, each
    * run ending exactly at the end of its fill
    */
   static float[] burstTimes(long t) {
      float e = expandMs;
      float cycle = e + HOLD_MS + e + GAP_MS;
      long loop = t / (long)cycle;
      float[] b = new float[2 * BURSTS];
      for (int pass = 0; pass < 2; pass++) {
         long draw = loop * 2L + pass;
         float sum = 0.0F;
         for (int k = 0; k < BURSTS; k++) {
            sum += GAP_MIN + rand(k, 1000, draw);
            b[pass * BURSTS + k] = sum;
         }
         float start = pass == 0 ? 0.0F : e + HOLD_MS;
         for (int k = 0; k < BURSTS - 1; k++) {
            b[pass * BURSTS + k] = start + e * b[pass * BURSTS + k] / sum;
         }
         b[pass * BURSTS + BURSTS - 1] = start + e; // exactly the end of the fill (drawOverWorld draws at ceil(e))
      }
      return b;
   }

   /** whether chunk i, j (relative to the player's chunk) is shown at time t, given burstTimes(t) */
   static boolean shown(int i, int j, long t, float[] bursts) {
      float e = expandMs;
      float cycle = e + HOLD_MS + e + GAP_MS;
      long loop = t / (long)cycle;
      float p = t % cycle;
      float in = bursts[Math.min(BURSTS - 1, (int)(rand(i, j, loop * 2L) * BURSTS))];
      float out = bursts[BURSTS + Math.min(BURSTS - 1, (int)(rand(i, j, loop * 2L + 1L) * BURSTS))];
      return p >= in && p < out;
   }

   /** a stable uniform [0, 1) per tile and draw (SplitMix64 finaliser) */
   static float rand(int i, int j, long draw) {
      long z = ((long)i * 0x9E3779B97F4A7C15L) ^ ((long)j * 0xC2B2AE3D27D4EB4FL) ^ (draw * 0x165667B19E3779F9L);
      z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
      z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
      z ^= z >>> 31;
      return (z >>> 40) / (float)(1L << 24);
   }

   /** the cells stay over the world until FADE_FROM of the chunk map is lit and are gone at FADE_TO */
   private static final float FADE_FROM = 0.2F;
   private static final float FADE_TO = 0.8F;
   private static final long MAX_OVER_WORLD_MS = 3000L;
   private static boolean overWorld;
   private static long enteredMs;
   private static volatile float coverage;

   /** From NoLoadingScreen on world entry: the cells stay over the world (drawOverWorld) while it lights up. */
   public static void onWorldEntered() {
      rgba = null;
      coverage = 0.0F;
      if (texture != null) {
         overWorld = true;
         enteredMs = System.currentTimeMillis();
      }
      if (enabled() && loadTimePending) {
         long measured = System.currentTimeMillis() - revealStartMs;
         long ms = lastLoadMs > 0L ? (lastLoadMs + measured) / 2L : measured;
         File lt = ZomboidFileSystem.instance.getFileInCurrentSave(LOAD_TIME);
         Thread t = new Thread(() -> {
            try {
               Files.writeString(lt.toPath(), Long.toString(ms));
            } catch (Throwable e) {
               Log.warn("resume shot: could not write " + lt + ": " + e);
            }
         }, "pzopt-resume-load-time");
         t.setDaemon(true);
         t.start();
         Log.info("resume shot: loading frame to world entry " + measured + " ms (fill-in paced at " + (int)expandMs + " ms, next pace from " + ms + " ms)");
         loadTimePending = false;
      }
   }

   /** From NoLoadingScreen every world frame: the share of the chunk map that is loaded and lit (0..1). */
   public static void setWorldCoverage(float lit) {
      coverage = lit;
   }

   /**
    * From Display.imguiEndFrame (the last game-thread draw of a frame): the cells over the world, their opacity falling as
    * the chunk map lights up (FADE_FROM .. FADE_TO), so the ground dissolves into the live world as that builds from the
    * centre outwards; freed once gone (or after MAX_OVER_WORLD_MS).
    */
   public static void drawOverWorld() {
      if (!overWorld || texture == null) {
         return;
      }
      float a = 1.0F - (coverage - FADE_FROM) / (FADE_TO - FADE_FROM);
      if (a > 1.0F) {
         a = 1.0F;
      }
      if (System.currentTimeMillis() - enteredMs > MAX_OVER_WORLD_MS) {
         a = 0.0F;
      }
      if (a <= 0.0F) {
         overWorld = false;
         releaseIn = 8; // queued frames still draw it
         return;
      }
      // chunks the load outran pop in within FINISH_MS of world entry, then the square stays whole until it fades
      float te = enteredMs - revealStartMs;
      float t = System.currentTimeMillis() - revealStartMs;
      float e = expandMs;
      if (t > te && te < e) {
         t = te + (t - te) * (e - te) / FINISH_MS;
      }
      drawTiles((long)Math.ceil(Math.min(t, e)), a); // expandMs has a fraction: truncating hid the last burst
   }

   /** Per game frame from NoLoadingScreen. */
   public static void onFrame() {
      if (releaseIn > 0 && --releaseIn == 0) {
         final Texture t = texture;
         texture = null;
         RenderThread.queueInvokeOnRenderContext(t::destroy);
      }
   }

   private static Texture upload(byte[] rgba, int w, int h) {
      boolean compress = TextureID.useCompressionOption;
      TextureID.useCompressionOption = false;
      try {
         int wHw = ImageUtils.getNextPowerOfTwoHW(w);
         int hHw = ImageUtils.getNextPowerOfTwoHW(h);
         WrappedBuffer wb = DirectBufferAllocator.allocate(wHw * hHw * 4);
         ByteBuffer buf = wb.getBuffer();
         buf.clear();
         for (int y = 0; y < h; y++) {
            buf.position(y * wHw * 4);
            buf.put(rgba, y * w * 4, w * 4);
         }
         buf.position(0);
         buf.limit(wHw * hHw * 4);
         return new Texture(new TextureID(new ImageData(w, h, wb)), "pzopt-resume-shot");
      } finally {
         TextureID.useCompressionOption = compress;
      }
   }
}
