package pzopt;

import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.GameTime;
import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;
import zombie.iso.weather.ClimateManager;

/**
 * Cloud shadows (Config {@code cloudShadows}, with {@code sunShadows}; docs/findings-sky-2026-09-26.md).
 *
 * <p>A cloud blocks the direct sun (or moon) exactly as a wall does, so a cloud shadow is the static sun shadow's darkening
 * applied where the cloud is: the chunk AO kernel keeps, per chunk texture, the direct light's share of the light the
 * texture is baked with ({@code q = s lit / (1 - s out + s lit)}, the G channel of its kept term, 0 indoors and in shade),
 * and the chunk composite multiplies each pixel by {@code 1 - q (1 - T)}, T the cloud's transmittance there. Nothing is
 * re-baked when the clouds move: the composite pays two taps into a 256 x 256 field (cached) where the cloud cover reaches
 * a pixel, and a third into the kept term where a cloud actually shades it.
 *
 * <p>The field: a tiled Perlin-Worley noise (R, base shapes, histogram-equalised so a cloud cover c covers a share c of the
 * ground) and an inverted Worley fBm (G, detail that erodes the edges), generated once off-thread. Density follows the
 * coverage remap of Schneider's Nubis clouds: {@code d = (base - (1 - c)) / (c edge)}, eroded by the detail layer drifting
 * at its own speed (the clouds change shape as they go), transmittance {@code T = 1 - opacity (1 - e^(-3d)) / (1 - e^-3)}.
 * The field drifts with the wind at the clouds' height and is projected along the key light from {@code cloudHeight}
 * squares up, so a cloud's shadow lands where the sun ray through it meets the ground, walls and roofs (the per-pixel world
 * position comes from the composite's depth: the mapping of {@link Ssr.View}).
 *
 * <p>Characters and vehicles read the same field on the CPU ({@link #transmittanceAt}): their shade and their capsule
 * shadows follow the clouds. The stock screen-space cloud overlay stays unless {@code cloudReplaceStock}.
 */
public final class CloudShadow {
   private CloudShadow() {
   }

   static final int FIELD_UNIT = 13, TERM_UNIT = 14;
   private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
   private static volatile boolean failed;
   private static volatile boolean patched;
   private static long patchedPrograms;

   /** Is the composite able to draw them (patched at launch, no failure)? */
   static boolean supported() {
      return patched && !failed && !MAC;
   }

   /** Do the chunk textures need the direct-sun share (bare marks included)? Game thread. */
   static boolean wanted() {
      return Config.SUN_SHADOWS && Config.CLOUD_SHADOWS && Overrides.enabled() && !MAC && !failed;
   }

   /** Cloud shadows shade the scene this frame (the strength of the key light above zero). */
   static boolean enabled() {
      return wanted() && supported();
   }

   // ------------------------------------------------------------------------------------------------ the field

   static final int N = Math.max(64, Math.min(1024, Integer.highestOneBit(Math.max(64, Config.CLOUD_FIELD_SIZE))));
   /** squares per texel of the base layer at cloudScalePct 100: 640 squares of sky per tile, fair-weather cumulus ~100-200 across */
   static final float SQUARES_PER_TEXEL = 2.5F;
   /** the detail layer's frequency over the base's */
   static final float DETAIL_SCALE = 4F; // an integer: the base uv wraps by whole periods
   private static volatile byte[] field; // N * N * 2: R base, G detail
   /** the base layer's maximum per MAX_CELL x MAX_CELL texels, dilated by a cell (a bilinear tap reaches its neighbour texel) */
   private static volatile float[] maxGrid;
   static final int MAX_CELL = 8;

   static float[] maxGrid(byte[] f, int n) {
      int m = n / MAX_CELL;
      float[] raw = new float[m * m];
      for (int y = 0; y < n; y++) {
         for (int x = 0; x < n; x++) {
            int c = (y / MAX_CELL) * m + x / MAX_CELL;
            raw[c] = Math.max(raw[c], (f[(y * n + x) * 2] & 0xFF) / 255F);
         }
      }
      float[] out = new float[m * m];
      for (int y = 0; y < m; y++) {
         for (int x = 0; x < m; x++) {
            float v = 0F;
            for (int dy = -1; dy <= 1; dy++) {
               for (int dx = -1; dx <= 1; dx++) {
                  v = Math.max(v, raw[Math.floorMod(y + dy, m) * m + Math.floorMod(x + dx, m)]);
               }
            }
            out[y * m + x] = v;
         }
      }
      return out;
   }

   /** Could a cloud shade any of the world rectangle [x0, x1] x [y0, y1] at levels z0..z1 (the coarse max map)? */
   static boolean cloudReaches(float x0, float y0, float x1, float y1, float z0, float z1) {
      float[] g = maxGrid;
      if (g == null) {
         return true;
      }
      double period = N * SQUARES_PER_TEXEL * Config.CLOUD_SCALE_PCT / 100.0;
      double ax0 = Math.min(parA * z0, parA * z1), ax1 = Math.max(parA * z0, parA * z1);
      double ay0 = Math.min(parB * z0, parB * z1), ay1 = Math.max(parB * z0, parB * z1);
      double sx = N / period / MAX_CELL, sy = sx;
      int cx0 = (int)Math.floor((x0 + ax0 + parX + driftX) * sx), cx1 = (int)Math.floor((x1 + ax1 + parX + driftX) * sx);
      int cy0 = (int)Math.floor((y0 + ay0 + parY + driftY) * sy), cy1 = (int)Math.floor((y1 + ay1 + parY + driftY) * sy);
      int m = N / MAX_CELL;
      if (cx1 - cx0 >= m || cy1 - cy0 >= m) {
         return true;
      }
      float threshold = 1F - cover;
      for (int cy = cy0; cy <= cy1; cy++) {
         for (int cx = cx0; cx <= cx1; cx++) {
            if (g[Math.floorMod(cy, m) * m + Math.floorMod(cx, m)] > threshold) {
               return true;
            }
         }
      }
      return false;
   }
   private static volatile boolean generating;

   private static void generateAsync() {
      if (field != null || generating) {
         return;
      }
      generating = true;
      Thread t = new Thread(() -> {
         long t0 = System.nanoTime();
         try {
            byte[] fl = generate(N, 0x5EEDC10DL);
            maxGrid = maxGrid(fl, N);
            field = fl;
            Log.info(String.format(java.util.Locale.ROOT, "cloud shadows: field %dx%d generated in %.1f ms", N, N, (System.nanoTime() - t0) / 1e6));
         } catch (Throwable e) {
            failed = true;
            Log.warn("cloud shadows: field generation failed, off: " + e);
         }
      }, "pzopt-cloudfield");
      t.setDaemon(true);
      t.start();
   }

   /** The field (N x N, 2 bytes a texel): tiled Perlin-Worley base (equalised), inverted Worley fBm detail. */
   static byte[] generate(int n, long seed) {
      float[] base = new float[n * n];
      float[] detail = new float[n * n];
      for (int y = 0; y < n; y++) {
         for (int x = 0; x < n; x++) {
            float u = (x + 0.5F) / n, v = (y + 0.5F) / n;
            float perlin = 0F, amp = 1F, norm = 0F;
            for (int o = 0; o < 5; o++) {
               int p = 3 << o;
               perlin += amp * perlin(u * p, v * p, p, seed + o * 101L);
               norm += amp;
               amp *= 0.5F;
            }
            perlin = perlin / norm * 0.5F + 0.5F; // ~[0, 1]
            float w = 0.625F * (1F - worley(u * 4, v * 4, 4, seed + 7L)) + 0.25F * (1F - worley(u * 8, v * 8, 8, seed + 11L))
               + 0.125F * (1F - worley(u * 16, v * 16, 16, seed + 13L));
            // Perlin-Worley: the Perlin fBm remapped from (Worley - 1, 1): billowy cells with Perlin's connectedness
            base[y * n + x] = (perlin - (w - 1F)) / (2F - w);
            detail[y * n + x] = 0.625F * (1F - worley(u * 6, v * 6, 6, seed + 17L)) + 0.25F * (1F - worley(u * 12, v * 12, 12, seed + 19L))
               + 0.125F * (1F - worley(u * 24, v * 24, 24, seed + 23L));
         }
      }
      byte[] out = new byte[n * n * 2];
      equalise(base, out, 0);
      equalise(detail, out, 1);
      return out;
   }

   /** Rank order into 0..255: a threshold at 1 - c leaves exactly a share c above it. */
   private static void equalise(float[] v, byte[] out, int channel) {
      int n = v.length;
      long[] keyed = new long[n];
      for (int i = 0; i < n; i++) {
         keyed[i] = ((long)Float.floatToIntBits(v[i] + 16F) << 32) | i; // positive floats sort as their bits
      }
      java.util.Arrays.sort(keyed);
      for (int r = 0; r < n; r++) {
         int i = (int)keyed[r];
         out[i * 2 + channel] = (byte)Math.min(255, (int)((long)r * 256L / n));
      }
   }

   private static int hash(int x, int y, long seed) {
      long h = seed * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + y * 0x165667B19E3779F9L;
      h ^= h >>> 29;
      h *= 0xBF58476D1CE4E5B9L;
      h ^= h >>> 32;
      return (int)h;
   }

   /** Tiled gradient noise (period p cells), ~[-1, 1]. */
   static float perlin(float x, float y, int p, long seed) {
      int x0 = (int)Math.floor(x), y0 = (int)Math.floor(y);
      float fx = x - x0, fy = y - y0;
      float n00 = grad(Math.floorMod(x0, p), Math.floorMod(y0, p), seed, fx, fy);
      float n10 = grad(Math.floorMod(x0 + 1, p), Math.floorMod(y0, p), seed, fx - 1F, fy);
      float n01 = grad(Math.floorMod(x0, p), Math.floorMod(y0 + 1, p), seed, fx, fy - 1F);
      float n11 = grad(Math.floorMod(x0 + 1, p), Math.floorMod(y0 + 1, p), seed, fx - 1F, fy - 1F);
      float ux = fx * fx * fx * (fx * (fx * 6F - 15F) + 10F), uy = fy * fy * fy * (fy * (fy * 6F - 15F) + 10F);
      float a = n00 + ux * (n10 - n00), b = n01 + ux * (n11 - n01);
      return (a + uy * (b - a)) * 1.414F;
   }

   private static float grad(int ix, int iy, long seed, float dx, float dy) {
      double ang = (hash(ix, iy, seed) & 0xFFFF) / 65536.0 * Math.PI * 2.0;
      return (float)(Math.cos(ang) * dx + Math.sin(ang) * dy);
   }

   /** Tiled Worley F1 (period p cells, one feature point a cell), distance in cells clamped to [0, 1]. */
   static float worley(float x, float y, int p, long seed) {
      int x0 = (int)Math.floor(x), y0 = (int)Math.floor(y);
      float best = 4F;
      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            int cx = x0 + dx, cy = y0 + dy;
            int h = hash(Math.floorMod(cx, p), Math.floorMod(cy, p), seed);
            float px = cx + (h & 0xFFFF) / 65536F, py = cy + (h >>> 16 & 0xFFFF) / 65536F;
            float ddx = px - x, ddy = py - y;
            best = Math.min(best, ddx * ddx + ddy * ddy);
         }
      }
      return Math.min(1F, (float)Math.sqrt(best));
   }

   // ------------------------------------------------------------------------------------------------ per frame (game thread)

   /** This frame's cloud state: base uv of world (0, 0) and its detail uv, coverage, parallax, strength (0 = no clouds). */
   private static double driftX, driftY, detailX, detailY;
   private static double lastWorldHours = Double.NaN;
   static volatile float cover, strength, windKph;
   static volatile double parX, parY, parA, parB; // cloud uv offset from the key light's parallax, per level height
   public static volatile boolean replaceStock;
   private static long frames;
   private static long toggleT0;
   private static boolean devOff;

   /** Game thread, once a frame after SunShadow.update (ChunkAo.flush): the drift and this frame's cover. */
   static void update() {
      if (!wanted()) {
         strength = 0F;
         replaceStock = false;
         return;
      }
      generateAsync();
      frames++;
      ClimateManager cm = ClimateManager.getInstance();
      float c = Config.DEV_CLOUD_COVER >= 0F ? Config.DEV_CLOUD_COVER : cm != null ? cm.getCloudIntensity() : 0F;
      c = Math.max(0F, Math.min(1F, c));
      // the drift: the wind at the clouds' height (the climate's wind blows from getWindAngleDegrees' compass point, 0 =
      // from the south-east), in real seconds of the game's clock (pauses and fast-forward with it)
      GameTime gt = GameTime.getInstance();
      double hours = gt != null ? gt.getWorldAgeHours() : 0.0;
      double dtReal = 0.0;
      if (!Double.isNaN(lastWorldHours) && gt != null) {
         double gameSecondsPerReal = 1440.0 / Math.max(1.0, gt.getMinutesPerDay());
         dtReal = Math.max(0.0, Math.min(10.0, (hours - lastWorldHours) * 3600.0 / gameSecondsPerReal));
      }
      lastWorldHours = hours;
      float kph = cm != null ? cm.getWindspeedKph() : 10F;
      windKph = kph;
      double toward = Math.toRadians((cm != null ? cm.getWindAngleDegrees() : 90F) - 45.0); // compass bearing it blows to
      double speed = Math.max(3.0, kph) / 3.6 * 1.6 * Config.CLOUD_SPEED_PCT / 100.0; // squares (~m) a second, aloft ~1.6 x the surface
      double vx = Math.sin(toward) * speed, vy = -Math.cos(toward) * speed; // world: x east, y south
      double period = N * SQUARES_PER_TEXEL * Config.CLOUD_SCALE_PCT / 100.0;
      driftX = (driftX + vx * dtReal) % period;
      driftY = (driftY + vy * dtReal) % period;
      // the detail layer drifts slower and a little across the wind: the edges change as the clouds go
      double dPeriod = period / DETAIL_SCALE;
      detailX = (detailX + (0.55 * vx - 0.25 * vy) * dtReal) % dPeriod;
      detailY = (detailY + (0.55 * vy + 0.25 * vx) * dtReal) % dPeriod;

      // the key light (unstepped: the shadows slide with the sun instead of jumping at the static shadows' steps)
      double[] L = SunShadow.lightBody == 0 ? Sky.sun : Sky.moon;
      if ("arc".equals(Config.SKY_PATH)) {
         L = new double[] {SunShadow.world[0], SunShadow.world[1], SunShadow.world[2]};
      }
      double lz = Math.max(0.12, L[2]);
      parX = L[0] / lz * Config.CLOUD_HEIGHT;
      parY = L[1] / lz * Config.CLOUD_HEIGHT;
      parA = -L[0] / lz * 2.4494897; // per level of height the landing point moves back towards the light
      parB = -L[1] / lz * 2.4494897;
      cover = c;
      float s = SunShadow.liveStrength;
      if (Config.DEV_CLOUD_ALTERNATE > 0) {
         long now = System.currentTimeMillis();
         if (toggleT0 == 0L) {
            toggleT0 = now;
            Log.info("cloud shadows: alternating every " + Config.DEV_CLOUD_ALTERNATE + " ms from epoch_ms " + now + " (on first)");
         }
         boolean off = (now - toggleT0) / Config.DEV_CLOUD_ALTERNATE % 2L == 1L;
         if (off != devOff) {
            devOff = off;
            Log.info("cloud shadows: dev alternate " + (off ? "off" : "on") + " at epoch_ms " + now);
         }
         if (off) {
            s = 0F;
         }
      }
      // no clouds, full overcast (no gaps: the key light's own strength already fell to 0) or no key light: nothing to draw
      strength = c > 0.01F && s > 0.004F && field != null ? 1F : 0F;
      replaceStock = Config.CLOUD_REPLACE_STOCK && strength > 0F;
      if (frames % 1200 == 1) {
         zombie.characters.IsoPlayer pl = zombie.characters.IsoPlayer.getInstance();
         if (pl != null && Config.DEV_CLOUD_TIMING) {
            Log.info(String.format(java.util.Locale.ROOT, "cloud shadows: transmittance at the player %.3f (enabled %s supported %s field %s)",
               transmittanceAt(pl.getX(), pl.getY(), pl.getZ()), enabled(), supported(), field != null));
         }
         Log.info(String.format(java.util.Locale.ROOT, "cloud shadows: cover %.2f wind %.0f kph to %.0f deg, drift %.0f,%.0f, key %s strength %.3f, parallax %.0f,%.0f%s",
            c, kph, (Math.toDegrees(toward) + 360.0) % 360.0, driftX, driftY, SunShadow.lightBody == 0 ? "sun" : "moon", s, parX, parY,
            supported() ? "" : " (composite not patched)"));
      }
   }

   /** The cloud's density parameters as the shader has them: 1 - cover, 1 / (cover x edge), erosion. */
   private static final float EDGE = 0.8F, EROSION = 0.35F;

   /**
    * Any thread: how much of the key light's direct part reaches (wx, wy) at level z through the clouds (1 = none held back).
    * The same field, drift and density as the composite (bilinear, wrapped).
    */
   public static float transmittanceAt(float wx, float wy, float z) {
      byte[] f = field;
      if (f == null || strength <= 0F || !enabled()) {
         return 1F;
      }
      double period = N * SQUARES_PER_TEXEL * Config.CLOUD_SCALE_PCT / 100.0;
      double X = wx + parA * z + parX + driftX, Y = wy + parB * z + parY + driftY;
      float base = sample(f, X / period, Y / period, 0);
      float c = cover;
      float d = clamp01((base - (1F - c)) / Math.max(0.02F, c * EDGE));
      if (d <= 0F) {
         return 1F;
      }
      double dp = period / DETAIL_SCALE;
      float det = sample(f, (wx + parA * z + parX + detailX) / dp, (wy + parB * z + parY + detailY) / dp, 1);
      float e = det * EROSION;
      d = clamp01((d - e) / (1F - e));
      return 1F - Config.CLOUD_OPACITY_PCT / 100F * (1F - (float)Math.exp(-3.0 * d)) / 0.95021293F;
   }

   private static float sample(byte[] f, double u, double v, int ch) {
      double x = (u - Math.floor(u)) * N - 0.5, y = (v - Math.floor(v)) * N - 0.5;
      int x0 = (int)Math.floor(x), y0 = (int)Math.floor(y);
      float fx = (float)(x - x0), fy = (float)(y - y0);
      int xa = Math.floorMod(x0, N), xb = Math.floorMod(x0 + 1, N), ya = Math.floorMod(y0, N), yb = Math.floorMod(y0 + 1, N);
      float a = (f[(ya * N + xa) * 2 + ch] & 0xFF), b = (f[(ya * N + xb) * 2 + ch] & 0xFF);
      float c = (f[(yb * N + xa) * 2 + ch] & 0xFF), d = (f[(yb * N + xb) * 2 + ch] & 0xFF);
      float top = a + (b - a) * fx, bot = c + (d - c) * fx;
      return (top + (bot - top) * fy) / 255F;
   }

   private static float clamp01(float v) {
      return v < 0F ? 0F : v > 1F ? 1F : v;
   }

   // ------------------------------------------------------------------------------------------------ composite (render thread)

   /** A frame's cloud state for the render thread (the game thread queues frames ahead of it). */
   private static final class Frame extends TextureDraw.GenericDrawer {
      final Ssr.View view = new Ssr.View();
      float strength, cover, opacity;
      boolean cull; // clouded holds the depth textures a cloud may reach; the rest skip
      int[] clouded = new int[256];
      int nClouded;
      boolean devOn; // devCloudTiming: this frame's alternation state (on = the clouds drawn)
      double ox, oy, driftX, driftY, detailX, detailY, parX, parY, parA, parB, period;

      boolean hasClouded(int depthTex) {
         for (int i = 0; i < this.nClouded; i++) {
            if (this.clouded[i] == depthTex) {
               return true;
            }
         }
         return false;
      }

      @Override
      public void render() {
         renderFrame = this;
         serial++;
         if (Config.DEV_CLOUD_TIMING) {
            Timing.begin(this.devOn);
         }
      }
   }

   private static final Frame[] FRAMES = {new Frame(), new Frame(), new Frame(), new Frame()};
   private static int frameIndex;
   private static volatile Frame renderFrame;
   private static int serial;

   /** Game thread, FBORenderCell right before the chunk composite: the cloud state of this frame for the render thread. */
   public static void beforeComposite(int playerIndex) {
      if (!patched) {
         if (Config.DEV_CLOUD_TIMING) {
            SpriteRenderer.instance.drawGeneric(BEGIN_UNPATCHED); // the stock composite's time, logged as "off"
         }
         return;
      }
      update();
      Frame f = FRAMES[frameIndex++ & 3];
      f.strength = supported() ? strength : 0F;
      f.devOn = !devOff;
      if (f.strength > 0F) {
         f.view.capture(playerIndex);
         f.cover = cover;
         f.opacity = Config.CLOUD_OPACITY_PCT / 100F;
         f.driftX = driftX;
         f.driftY = driftY;
         f.detailX = detailX;
         f.detailY = detailY;
         f.parX = parX;
         f.parY = parY;
         f.parA = parA;
         f.parB = parB;
         f.period = N * SQUARES_PER_TEXEL * Config.CLOUD_SCALE_PCT / 100.0;
         f.cull = false;
         f.nClouded = 0;
         if (Config.CLOUD_CULL) {
            java.util.ArrayList<zombie.iso.fboRenderChunk.FBORenderChunk> shown = zombie.iso.fboRenderChunk.FBORenderChunkManager.instance.toRenderThisFrame;
            f.cull = true;
            for (int i = 0; i < shown.size() && f.cull; i++) {
               zombie.iso.fboRenderChunk.FBORenderChunk rc = shown.get(i);
               if (rc == null || rc.chunk == null || rc.depth == null) {
                  continue;
               }
               int lv = rc.getMinLevel();
               float x0 = rc.chunk.wx * 8F, y0 = rc.chunk.wy * 8F;
               culled++;
               if (cloudReaches(x0 - 1F, y0 - 1F, x0 + 9F, y0 + 9F, lv, lv + 2F)) {
                  culled--;
                  if (f.nClouded == f.clouded.length) {
                     f.cull = false; // more than the list holds: no culling this frame
                  } else {
                     f.clouded[f.nClouded++] = rc.depth.getID();
                  }
               }
            }
         }
      }
      SpriteRenderer.instance.drawGeneric(f);
   }

   private static final TextureDraw.GenericDrawer AFTER = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         Timing.end();
      }
   };

   private static final TextureDraw.GenericDrawer BEGIN_UNPATCHED = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         Timing.begin(false);
      }
   };

   /** Game thread, right after the chunk composite (devCloudTiming). */
   public static void afterComposite() {
      if (Config.DEV_CLOUD_TIMING) {
         SpriteRenderer.instance.drawGeneric(AFTER);
      }
   }

   /** devCloudTiming: GL timestamps around the chunk composite, split by devCloudAlternate's state, logged every 5 s. */
   private static final class Timing {
      private static final int SLOTS = 16;
      private static int[] q;
      private static final boolean[] busy = new boolean[SLOTS], on = new boolean[SLOTS];
      private static int slot = -1, next;
      private static final long[] ns = new long[2], n = new long[2];
      private static final java.util.ArrayList<Long>[] all = new java.util.ArrayList[] {new java.util.ArrayList<Long>(), new java.util.ArrayList<Long>()};
      private static long lastLog;

      static void begin(boolean state) {
         if (q == null) {
            q = new int[SLOTS * 2];
            for (int i = 0; i < q.length; i++) {
               q[i] = org.lwjgl.opengl.GL15.glGenQueries();
            }
         }
         collect();
         int s = next;
         if (busy[s]) {
            slot = -1;
            return;
         }
         next = (next + 1) % SLOTS;
         org.lwjgl.opengl.GL33.glQueryCounter(q[s * 2], org.lwjgl.opengl.GL33.GL_TIMESTAMP);
         on[s] = state;
         slot = s;
      }

      static void end() {
         if (slot < 0 || q == null) {
            return;
         }
         org.lwjgl.opengl.GL33.glQueryCounter(q[slot * 2 + 1], org.lwjgl.opengl.GL33.GL_TIMESTAMP);
         busy[slot] = true;
         slot = -1;
      }

      private static void collect() {
         for (int s = 0; s < SLOTS; s++) {
            if (!busy[s] || org.lwjgl.opengl.GL15.glGetQueryObjecti(q[s * 2 + 1], org.lwjgl.opengl.GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
               continue;
            }
            long t = org.lwjgl.opengl.GL33.glGetQueryObjecti64(q[s * 2 + 1], org.lwjgl.opengl.GL15.GL_QUERY_RESULT)
               - org.lwjgl.opengl.GL33.glGetQueryObjecti64(q[s * 2], org.lwjgl.opengl.GL15.GL_QUERY_RESULT);
            int k = on[s] ? 1 : 0;
            ns[k] += t;
            n[k]++;
            all[k].add(t);
            busy[s] = false;
         }
         long now = System.currentTimeMillis();
         if (now - lastLog > 5000L && (n[0] + n[1]) > 0) {
            lastLog = now;
            Log.info(String.format(java.util.Locale.ROOT, "cloud timing: composite on %.1f us (median %.1f, %d frames) off %.1f us (median %.1f, %d frames)",
               n[1] > 0 ? ns[1] / 1000.0 / n[1] : 0.0, median(all[1]), n[1], n[0] > 0 ? ns[0] / 1000.0 / n[0] : 0.0, median(all[0]), n[0]));
            ns[0] = ns[1] = n[0] = n[1] = 0L;
            all[0].clear();
            all[1].clear();
         }
      }

      private static double median(java.util.ArrayList<Long> v) {
         if (v.isEmpty()) {
            return 0.0;
         }
         java.util.Collections.sort(v);
         return v.get(v.size() / 2) / 1000.0;
      }
   }

   private static int fieldTex;
   private static final float[] VP = new float[4];
   private static final int[] VPI = new int[4];
   private static final float[] MAP = new float[6];
   private static final java.util.HashMap<Integer, int[]> LOCATIONS = new java.util.HashMap<>();
   private static final java.util.HashMap<Integer, Integer> APPLIED = new java.util.HashMap<>();
   /** per program: the last per-draw term value set (-3: unknown) */
   private static final java.util.HashMap<Integer, float[]> LAST_TERM = new java.util.HashMap<>();
   static final String[] UNIFORMS = {"pzCloudU", "pzCloudV", "pzCloudP", "pzCloudD", "pzCloudE", "pzCloudField", "pzCloudTerm"};
   private static long draws, drawsWithTerm, drawsBare, culled;
   private static final java.util.HashMap<Integer, long[]> LAST_HANDLE = new java.util.HashMap<>();
   private static long dummyHandle;

   /** Render thread: a kept term's handle leaves residency; programs that hold it get the field's (always resident) instead. */
   static void handleReleased(long h) {
      for (java.util.Map.Entry<Integer, long[]> en : LAST_HANDLE.entrySet()) {
         if (en.getValue()[0] == h) {
            int[] l = LOCATIONS.get(en.getKey());
            if (l != null && l[6] >= 0 && dummy()) {
               org.lwjgl.opengl.ARBBindlessTexture.glProgramUniformHandleui64ARB(en.getKey(), l[6], dummyHandle);
            }
            en.getValue()[0] = dummyHandle;
         }
      }
   }

   private static boolean dummy() {
      if (dummyHandle == 0L && fieldTex != 0) {
         dummyHandle = org.lwjgl.opengl.ARBBindlessTexture.glGetTextureHandleARB(fieldTex);
         if (dummyHandle != 0L) {
            org.lwjgl.opengl.ARBBindlessTexture.glMakeTextureHandleResidentARB(dummyHandle);
         }
      }
      return dummyHandle != 0L;
   }

   /** the composite reads the kept terms through bindless handles (decided when the shader is patched) */
   private static volatile boolean bindless;
   private static int boundSerial = -1;

   private static boolean ensureField() {
      if (fieldTex != 0) {
         return true;
      }
      byte[] f = field;
      if (f == null) {
         return false;
      }
      ByteBuffer b = BufferUtils.createByteBuffer(f.length);
      b.put(f).flip();
      fieldTex = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, fieldTex);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
      GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG8, N, N, 0, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, b);
      GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
      GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      return true;
   }

   /** ChunkRenderShader.startRenderThread, every chunk texture drawn by the composite (after pixelLight and the scatter). */
   public static void chunkDraw(TextureDraw texd) {
      if (!patched || failed) {
         return;
      }
      Frame f = renderFrame;
      try {
         int prog = Ssr.boundProgram();
         int[] l = LOCATIONS.get(prog);
         if (l == null) {
            l = new int[UNIFORMS.length];
            for (int i = 0; i < l.length; i++) {
               l[i] = GL20.glGetUniformLocation(prog, UNIFORMS[i]);
            }
            LOCATIONS.put(prog, l);
         }
         if (l[2] < 0) {
            return; // not a patched program
         }
         boolean on = f != null && f.strength > 0F && Config.DEV_CLOUD_VIEW != 2 ? ensureField() : f != null && Config.DEV_CLOUD_VIEW == 2;
         Integer applied = APPLIED.get(prog);
         float[] last = LAST_TERM.get(prog);
         if (last == null) {
            LAST_TERM.put(prog, last = new float[] {-3F, -3F});
         }
         if (applied == null || applied != serial) {
            APPLIED.put(prog, serial);
            last[0] = -3F;
            last[1] = -3F;
            if (!on) {
               GL20.glUniform4f(l[2], 0F, 0F, 0F, 0F);
               return;
            }
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, VPI);
            VP[0] = VPI[0];
            VP[1] = VPI[1];
            VP[2] = VPI[2];
            VP[3] = VPI[3];
            f.view.mapping(VP, MAP);
            uniforms(f, l);
            if (bindless && l[6] >= 0 && !LAST_HANDLE.containsKey(prog) && dummy()) {
               org.lwjgl.opengl.ARBBindlessTexture.glUniformHandleui64ARB(l[6], dummyHandle);
               LAST_HANDLE.put(prog, new long[] {dummyHandle});
            }
            if (Config.DEV_CLOUD_TIMING && serial % 1200 == 7) {
               Log.info(String.format(java.util.Locale.ROOT, "cloud shadows: render prog %d field tex %d vp %.0f,%.0f,%.0f,%.0f map %.4f,%.1f,%.4f,%.1f,%.1f,%.1f U %s V %s P %s D %s gl error %d",
                  prog, fieldTex, VP[0], VP[1], VP[2], VP[3], MAP[0], MAP[1], MAP[2], MAP[3], MAP[4], MAP[5], java.util.Arrays.toString(DBG_U), java.util.Arrays.toString(DBG_V),
                  java.util.Arrays.toString(DBG_P), java.util.Arrays.toString(DBG_D), GL11.glGetError()));
            }
         } else if (!on) {
            return;
         }
         // per draw: this texture's direct-sun share (its kept term on TERM_UNIT, or a constant)
         int depthTex = texd.tex1 != null ? texd.tex1.getID() : -1; // the draw's DEPTH: the chunk texture's own
         boolean reach = depthTex > 0 && (!f.cull || f.hasClouded(depthTex));
         draws++;
         float want;
         float tw = 0F, th = 0F;
         if (texd.tex1 != null && texd.tex1.getWidthHW() > 0 && texd.tex1.getHeightHW() > 0) {
            tw = 1F / texd.tex1.getWidthHW();
            th = 1F / texd.tex1.getHeightHW();
         }
         if (bindless) {
            long h = reach ? ChunkAo.cloudHandle(depthTex) : -2L;
            if (h > 0L) {
               drawsWithTerm++;
               long[] lh = LAST_HANDLE.get(prog);
               if (lh == null) {
                  LAST_HANDLE.put(prog, lh = new long[1]);
               }
               if (lh[0] != h) {
                  lh[0] = h;
                  org.lwjgl.opengl.ARBBindlessTexture.glUniformHandleui64ARB(l[6], h);
               }
               want = -1F;
            } else if (h == -1L) {
               drawsBare++;
               want = SunShadow.dir[3];
            } else {
               want = 0F;
            }
            if (want != last[0] || tw != last[1]) {
               last[0] = want;
               last[1] = tw;
               GL20.glUniform4f(l[4], want, Config.DEV_CLOUD_VIEW, tw, th);
            }
            return;
         }
         int term = reach ? ChunkAo.cloudTerm(depthTex) : -2;
         if (term > 0) {
            drawsWithTerm++;
            if ((Config.DEV_CLOUD_SKIP & 4) == 0 || boundSerial != serial) { // dev bit 4: one bind a frame (wrong picture, the cost of per-draw binds)
               boundSerial = serial;
               GL13.glActiveTexture(GL13.GL_TEXTURE0 + TERM_UNIT);
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, term);
               GL13.glActiveTexture(GL13.GL_TEXTURE0);
            }
            want = -1F;
         } else if (term == -1) {
            drawsBare++;
            want = SunShadow.dir[3]; // open ground in the sun: its whole direct share
         } else {
            want = 0F;
         }
         if (want != last[0] || tw != last[1]) {
            last[0] = want;
            last[1] = tw;
            GL20.glUniform4f(l[4], want, Config.DEV_CLOUD_VIEW, tw, th);
         }
      } catch (Throwable t) {
         failed = true;
         Log.warn("cloud shadows: composite uniforms failed, off: " + t);
      }
   }

   /**
    * The frame's uniforms on this program: the cloud uv as a linear function of (window x, window y, depth). In the
    * composite's frame u = x - y = kA px + cA, v = x + y - 6z = kB py + cB, w = x + y + 2z = kC depth + cC (z in levels,
    * relative to the origin square ox, oy); the landing point X = x + parA z + parX, Y = y + parB z + parY.
    */
   private static void uniforms(Frame f, int[] l) {
      uniforms(f, l, FIELD_UNIT);
   }

   private static void uniforms(Frame f, int[] l, int fieldUnit) {
      double kA = MAP[0], cA = MAP[1], kB = MAP[2], cB = MAP[3], kC = MAP[4], cC = MAP[5];
      double a = f.parA, b = f.parB;
      // X = u / 2 + v (1 - a) / 8 + w (3 + a) / 8, Y = -u / 2 + v (1 - b) / 8 + w (3 + b) / 8
      double xPx = kA / 2.0, xPy = kB * (1.0 - a) / 8.0, xD = kC * (3.0 + a) / 8.0, xC = cA / 2.0 + cB * (1.0 - a) / 8.0 + cC * (3.0 + a) / 8.0;
      double yPx = -kA / 2.0, yPy = kB * (1.0 - b) / 8.0, yD = kC * (3.0 + b) / 8.0, yC = -cA / 2.0 + cB * (1.0 - b) / 8.0 + cC * (3.0 + b) / 8.0;
      double P = f.period;
      double offX = floorModD(f.view.ox + f.parX + f.driftX, P), offY = floorModD(f.view.oy + f.parY + f.driftY, P);
      GL20.glUniform4f(l[0], (float)(xPx / P), (float)(xPy / P), (float)(xD / P), (float)((xC + offX) / P));
      GL20.glUniform4f(l[1], (float)(yPx / P), (float)(yPy / P), (float)(yD / P), (float)((yC + offY) / P));
      float c = Math.max(0.0F, Math.min(1F, f.cover));
      GL20.glUniform4f(l[2], f.strength, 1F - c, 1F / Math.max(0.02F, c * EDGE), EROSION);
      DBG_U[0] = (float)(xPx / P); DBG_U[1] = (float)(xPy / P); DBG_U[2] = (float)(xD / P); DBG_U[3] = (float)((xC + offX) / P);
      DBG_V[0] = (float)(yPx / P); DBG_V[1] = (float)(yPy / P); DBG_V[2] = (float)(yD / P); DBG_V[3] = (float)((yC + offY) / P);
      DBG_P[0] = f.strength; DBG_P[1] = 1F - c; DBG_P[2] = 1F / Math.max(0.02F, c * EDGE); DBG_P[3] = EROSION;
      DBG_D[0] = l[0]; DBG_D[1] = l[2]; DBG_D[2] = l[4]; DBG_D[3] = l[5];
      // detail uv = base uv x DETAIL_SCALE + its own drift (relative to the base's)
      double dP = P / DETAIL_SCALE;
      double dOffX = floorModD((f.detailX - f.driftX) / dP, 1.0), dOffY = floorModD((f.detailY - f.driftY) / dP, 1.0);
      GL20.glUniform4f(l[3], DETAIL_SCALE, (float)dOffX, (float)dOffY, f.opacity / 0.95021293F);
      GL20.glUniform1i(l[5], fieldUnit);
      if (l[6] >= 0 && !(bindless && fieldUnit == FIELD_UNIT)) {
         GL20.glUniform1i(l[6], TERM_UNIT);
      }
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + fieldUnit);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, fieldTex);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
   }

   /** The water's field unit: past every unit the water, glint, reflection and composite passes bind (0-15). */
   static final int WATER_FIELD_UNIT = 16;
   private static final java.util.HashMap<Integer, int[]> WATER_LOCATIONS = new java.util.HashMap<>();
   private static final java.util.HashMap<Integer, Integer> WATER_APPLIED = new java.util.HashMap<>();
   private static final String[] WATER_UNIFORMS = {"pzCloudU", "pzCloudV", "pzCloudP", "pzCloudD", "pzCloudE", "pzCloudFieldW", "-"};

   /** WaterShader.updateWaterParams (program bound): the frame's cloud uniforms on the water program, once a frame. */
   public static void waterUniforms() {
      if (!waterPatched || failed) {
         return;
      }
      Frame f = renderFrame;
      try {
         int prog = Ssr.boundProgram();
         int[] l = WATER_LOCATIONS.get(prog);
         if (l == null) {
            l = new int[WATER_UNIFORMS.length];
            for (int i = 0; i < l.length; i++) {
               l[i] = GL20.glGetUniformLocation(prog, WATER_UNIFORMS[i]);
            }
            WATER_LOCATIONS.put(prog, l);
         }
         if (l[2] < 0) {
            return;
         }
         Integer applied = WATER_APPLIED.get(prog);
         if (applied != null && applied == serial) {
            return;
         }
         WATER_APPLIED.put(prog, serial);
         if (f == null || f.strength <= 0F || !ensureField()) {
            GL20.glUniform4f(l[2], 0F, 0F, 0F, 0F);
            return;
         }
         GL11.glGetIntegerv(GL11.GL_VIEWPORT, VPI);
         VP[0] = VPI[0];
         VP[1] = VPI[1];
         VP[2] = VPI[2];
         VP[3] = VPI[3];
         f.view.mapping(VP, MAP);
         uniforms(f, l, WATER_FIELD_UNIT);
         GL20.glUniform4f(l[4], SunShadow.dir[3], 0F, 0F, 0F); // open water in the sun: its whole direct share
      } catch (Throwable t) {
         failed = true;
         Log.warn("cloud shadows: water uniforms failed, off: " + t);
      }
   }

   private static volatile boolean waterPatched;

   private static final float[] DBG_U = new float[4], DBG_V = new float[4], DBG_P = new float[4], DBG_D = new float[4];

   private static double floorModD(double v, double p) {
      double r = v % p;
      return r < 0.0 ? r + p : r;
   }

   static String stats() {
      String s = String.format(java.util.Locale.ROOT, "clouds: %s cover %.2f wind %.0f kph strength %.2f, draws %d (term %d, bare %d), textures culled %d, programs patched %d%s%s",
         enabled() ? "on" : "off", cover, windKph, strength, draws, drawsWithTerm, drawsBare, culled, patchedPrograms,
         bindless ? ", bindless handles " + ChunkAo.residentHandles : "", failed ? " FAILED" : "");
      draws = drawsWithTerm = drawsBare = culled = 0L;
      return s;
   }

   // ------------------------------------------------------------------------------------------------ shader patch

   /**
    * ShaderUnit hook, after pixelLight's patch and before the reflections' (which renames our main in turn): the chunk
    * composite (stock chunkShader.frag, GLSL 1.20, or pixelLight's programs, GLSL 4.20) gets the cloud shadow after its own
    * main. Only when sun shadows and cloud shadows are on at launch (else the game keeps its own programs untouched).
    */
   public static String patchShader(String fileName, String code) {
      if (fileName == null || code == null || MAC || !Overrides.enabled() || !Config.CLOUD_SHADOWS || !Config.SUN_SHADOWS) {
         return code;
      }
      String f = fileName.replace('\\', '/');
      if (f.endsWith("/water.frag") || f.endsWith("/water_hq.frag")) {
         return patchWater(fileName, code);
      }
      if (!(f.endsWith("/chunkShader.frag") || f.endsWith("/pzopt_chunkBase.frag") || f.endsWith("/pzopt_chunkStock.frag"))) {
         return code;
      }
      int nl = code.indexOf('\n');
      String first = nl < 0 ? "" : code.substring(0, nl).trim();
      boolean stock = first.equals("#version 120");
      boolean glsl4 = first.startsWith("#version 4");
      String out = code.contains("out vec4 fragColor;") ? "fragColor" : "gl_FragColor";
      if (!(stock || glsl4) || code.indexOf("void main()") < 0 || !code.contains("gl_FragDepth") || !code.contains("texCoord") || !code.contains(out)) {
         Log.warn("cloud shadows: " + fileName + " is not a composite shader we know, no cloud shadows");
         return code;
      }
      String tex = stock ? "texture2D" : "texture";
      boolean bl = Config.CLOUD_BINDLESS && org.lwjgl.opengl.GL.getCapabilities().GL_ARB_bindless_texture;
      // bindless: GLSL 4.00+ (the stock 1.20 composite as 4.20 compatibility: varying, texture2D and gl_FragColor stay)
      String head = bl ? (stock ? "#version 420 compatibility" : first) + "\n#extension GL_ARB_bindless_texture : require\n#define PZC_BINDLESS" : first;
      String c = head + "\n#define PZC_OUT " + out + "\n#define PZC_TEX " + tex + (Config.DEV_CLOUD_VIEW > 0 ? "\n#define PZC_VIEW " + Config.DEV_CLOUD_VIEW : "")
         + (Config.CLOUD_TERM_MIPS ? (stock && !bl ? "\n#define PZC_TERM(uv) texture2D(pzCloudTerm, uv, 1.0)" : "\n#define PZC_TERM(uv) textureLod(pzCloudTerm, uv, float(PZC_TERM_LOD))")
            : (stock && !bl ? "\n#define PZC_TERM(uv) texture2D(pzCloudTerm, uv, -8.0)" : "\n#define PZC_TERM(uv) textureLod(pzCloudTerm, uv, 0.0)"))
         + "\n#define PZC_TERM_LOD " + Math.max(0, Math.min(2, Config.CLOUD_TERM_LOD))
         + ((Config.DEV_CLOUD_SKIP & 1) != 0 ? "\n#define PZC_SKIP_TERM" : "") + ((Config.DEV_CLOUD_SKIP & 2) != 0 ? "\n#define PZC_SKIP_DETAIL" : "")
         + code.substring(nl).replace("void main()", "void pzCloudInner()") + "\n" + COMPOSITE_GLSL + "\n";
      int test = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
      GL20.glShaderSource(test, c);
      GL20.glCompileShader(test);
      boolean ok = GL20.glGetShaderi(test, GL20.GL_COMPILE_STATUS) != 0;
      String log = ok ? "" : GL20.glGetShaderInfoLog(test, 4096);
      GL20.glDeleteShader(test);
      if (!ok) {
         Log.warn("cloud shadows: " + fileName + " patch does not compile, stays as it was: " + log);
         return code;
      }
      patched = true;
      bindless = bl;
      patchedPrograms++;
      Log.info("cloud shadows: patched into " + fileName + (bl ? " (kept terms bindless)" : ""));
      return c;
   }

   /** The water shaders (GLSL 1.20, after HDR's glint patch; the reflections' patch follows): the water darkened under a cloud. */
   private static String patchWater(String fileName, String code) {
      String anchor = "gl_FragDepth = vDepth;";
      int at = code.indexOf(anchor);
      int mi = code.indexOf("void mainImage(");
      if (at < 0 || mi < 0 || at < mi || !code.startsWith("#version 120") || !code.contains("varying float vDepth;")) {
         Log.warn("cloud shadows: " + fileName + " has changed, no cloud shadows on the water");
         return code;
      }
      String c = code.substring(0, mi) + WATER_GLSL + "\n" + code.substring(mi, at + anchor.length())
         + "\n    fragColor.rgb *= pzCloudWater();" + code.substring(at + anchor.length());
      int test = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
      GL20.glShaderSource(test, c);
      GL20.glCompileShader(test);
      boolean ok = GL20.glGetShaderi(test, GL20.GL_COMPILE_STATUS) != 0;
      String log = ok ? "" : GL20.glGetShaderInfoLog(test, 4096);
      GL20.glDeleteShader(test);
      if (!ok) {
         Log.warn("cloud shadows: " + fileName + " water patch does not compile, stays as it was: " + log);
         return code;
      }
      waterPatched = true;
      Log.info("cloud shadows: patched into " + fileName);
      return c;
   }

   static final String WATER_GLSL = String.join("\n",
      "uniform sampler2D pzCloudFieldW;",
      "uniform vec4 pzCloudU;",
      "uniform vec4 pzCloudV;",
      "uniform vec4 pzCloudP;",
      "uniform vec4 pzCloudD;",
      "uniform vec4 pzCloudE;",
      "float pzCloudWater() {",
      "   if (pzCloudP.x <= 0.0) return 1.0;",
      "   vec4 p = vec4(gl_FragCoord.xy, vDepth, 1.0);",
      "   vec2 uv = vec2(dot(pzCloudU, p), dot(pzCloudV, p));",
      "   float d = clamp((texture2D(pzCloudFieldW, uv).r - pzCloudP.y) * pzCloudP.z, 0.0, 1.0);",
      "   if (d <= 0.0) return 1.0;",
      "   float e = texture2D(pzCloudFieldW, uv * pzCloudD.x + pzCloudD.yz).g * pzCloudP.w;",
      "   d = clamp((d - e) / (1.0 - e), 0.0, 1.0);",
      "   return 1.0 - pzCloudE.x * pzCloudD.w * (1.0 - exp(-3.0 * d));",
      "}");

   /**
    * The composite's own main, then the cloud: its uv from the window position and the depth it wrote (linear), the
    * coverage remap, the detail erosion only where the base reaches, the kept term only where a cloud shades.
    */
   static final String COMPOSITE_GLSL = String.join("\n",
      "uniform sampler2D pzCloudField;", // FIELD_UNIT: R base shapes (equalised), G detail
      "#ifdef PZC_BINDLESS",
      "layout(bindless_sampler) uniform sampler2D pzCloudTerm;", // the chunk texture's kept term by its handle, G the direct-sun share
      "#else",
      "uniform sampler2D pzCloudTerm;", // TERM_UNIT: the chunk texture's kept term, G the direct-sun share
      "#endif",
      "uniform vec4 pzCloudU;", // cloud u = dot(U, (window x, window y, depth, 1))
      "uniform vec4 pzCloudV;",
      "uniform vec4 pzCloudP;", // x on, y 1 - cover, z 1 / (cover edge), w erosion
      "uniform vec4 pzCloudD;", // x detail scale, yz detail offset, w opacity / (1 - e^-3)
      "uniform vec4 pzCloudE;", // x the texture's direct-sun share (-1: read the kept term), y dev view, zw one texel of the chunk texture's uv
      "void main() {",
      "   pzCloudInner();",
      "#ifdef PZC_VIEW",
      "   if (pzCloudE.y > 1.5) { float q0 = pzCloudE.x >= 0.0 ? pzCloudE.x : PZC_TERM(texCoord.st).g; PZC_OUT.rgb = vec3(q0) * PZC_OUT.a; return; }",
      "#endif",
      "#ifdef PZC_VIEW",
      "   if (pzCloudE.y > 0.5 && pzCloudE.y < 1.5 && (pzCloudP.x <= 0.0 || pzCloudE.x == 0.0)) { PZC_OUT.rgb = vec3(pzCloudE.x == 0.0 ? 0.85 : 1.0) * PZC_OUT.a; return; }",
      "#endif",
      "   if (pzCloudP.x <= 0.0 || pzCloudE.x == 0.0 || PZC_OUT.a < 0.004) return;", // nothing drawn here (a chunk quad is mostly transparent padding)
      "   float dz = gl_FragDepth;",
      // a pixel with colour but no depth (tile edges along the chunk seams write none): the nearest drawn neighbour's depth
      // (its own reconstructs far off the ground and read a cloud from elsewhere: light dots along every seam)
      "   if (dz - chunkDepth >= 0.9999) {",
      "      float best = 1.0;",
      "      for (int i = 1; i <= 3; i++) {",
      "         vec2 o = pzCloudE.zw * float(i);",
      "         best = min(best, min(min(PZC_TEX(DEPTH, texCoord.st + vec2(o.x, 0.0)).r, PZC_TEX(DEPTH, texCoord.st - vec2(o.x, 0.0)).r),",
      "                              min(PZC_TEX(DEPTH, texCoord.st + vec2(0.0, o.y)).r, PZC_TEX(DEPTH, texCoord.st - vec2(0.0, o.y)).r)));",
      "         if (best < 0.9999) break;",
      "      }",
      "      if (best >= 0.9999) return;",
      "      dz = chunkDepth + best;",
      "   }",
      "   vec4 p = vec4(gl_FragCoord.xy, dz, 1.0);",
      "   vec2 uv = vec2(dot(pzCloudU, p), dot(pzCloudV, p));",
      "   float d = clamp((PZC_TEX(pzCloudField, uv).r - pzCloudP.y) * pzCloudP.z, 0.0, 1.0);",
      "#ifdef PZC_VIEW",
      "   if (d <= 0.0 && pzCloudE.y > 0.5 && pzCloudE.y < 1.5) { PZC_OUT.rgb = vec3(1.0) * PZC_OUT.a; return; }",
      "#endif",
      "   if (d <= 0.0) return;",
      "#ifndef PZC_SKIP_DETAIL",
      "   float e = PZC_TEX(pzCloudField, uv * pzCloudD.x + pzCloudD.yz).g * pzCloudP.w;",
      "   d = clamp((d - e) / (1.0 - e), 0.0, 1.0);",
      "#endif",
      "   float shade = pzCloudD.w * (1.0 - exp(-3.0 * d));",
      "#ifdef PZC_VIEW",
      "   if (shade < 0.002 && pzCloudE.y > 0.5 && pzCloudE.y < 1.5) { PZC_OUT.rgb = vec3(1.0) * PZC_OUT.a; return; }",
      "#endif",
      "   if (shade < 0.002) return;",
      "#ifdef PZC_SKIP_TERM",
      "   float q = 0.4;",
      "#else",
      "   float q = pzCloudE.x >= 0.0 ? pzCloudE.x : PZC_TERM(texCoord.st).g;",
      "#endif",
      "#ifdef PZC_VIEW",
      "   PZC_OUT.rgb = vec3(1.0 - q * shade) * PZC_OUT.a; return;",
      "#endif",
      "   PZC_OUT.rgb *= 1.0 - q * shade;",
      "}");
}
