package pzopt;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.textures.ColorInfo;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureFBO;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.LightingJNI;

/**
 * The HDR light map (Hdr, tune {@code light}): where the light is, independent of what the surfaces look like.
 *
 * The SDR frame is albedo x light, both squeezed into 0..1, so an expansion keyed on pixel brightness lifts pale paint
 * as much as a lamp-lit floor (first night sweep, 2026-09-24: the cream rug tiles and the player's legs went HDR, the
 * torch beam did not). A worker reads JNILighting's cached corner colours on the player's floor, without JNI calls or
 * dirty-bit side effects. The small RGBA8 texture stores rgb = the square's light, a = its absolute excess over the local ambient
 * around that square, within its own room. The reference is independent of the player, camera bounds and visibility.
 * The composite maps each pixel back to its iso square through an affine transform (world texture UV -> world
 * px -> iso x/y on the player's floor) and scales the pixel by a gain that grows with that excess: lit surfaces get
 * brighter in proportion, their colours intact, dark paint stays dark.
 */
public final class HdrLight {
   private HdrLight() {
   }

   static final int MAX = 256;
   private static final VarHandle LIGHT_INFO;
   /** JNILighting.cacheVertLight: the 8 corner colours the renderer draws with (0-3 floor corners, 4-7 top), 0xAABBGGRR. */
   private static final VarHandle VERT_LIGHT;
   /** JNILighting.vis (bit 2 = the player sees the square now), read without JNILighting.update()'s JNI call */
   private static final VarHandle VIS;

   static {
      VarHandle h = null, v = null, s = null;
      try {
         MethodHandles.Lookup l = MethodHandles.privateLookupIn(LightingJNI.JNILighting.class, MethodHandles.lookup());
         h = l.findVarHandle(LightingJNI.JNILighting.class, "lightInfo", ColorInfo.class);
         v = l.findVarHandle(LightingJNI.JNILighting.class, "cacheVertLight", int[].class);
         s = l.findVarHandle(LightingJNI.JNILighting.class, "vis", byte.class);
      } catch (Throwable t) {
         Log.warn("hdr light: JNILighting light caches not reachable, light map off: " + t);
      }
      LIGHT_INFO = h;
      VERT_LIGHT = v;
      VIS = s;
   }

   /** One frame's map: built on the worker, queued for upload by the game thread, uploaded on the render thread. */
   static final class Frame extends TextureDraw.GenericDrawer {
      static final int FREE = 0, BUILDING = 1, BUILT = 2, UPLOADING = 3;
      final ByteBuffer data = BufferUtils.createByteBuffer(MAX * MAX * 4);
      final int[] hist = new int[256], histSeen = new int[256], histCould = new int[256];
      /** per texel: the analytic intensity of the lights reaching the square and its colour (see build) */
      final float[] an = new float[MAX * MAX], ar = new float[MAX * MAX], ag = new float[MAX * MAX], ab = new float[MAX * MAX], tmp = new float[MAX * MAX];
      /** Per texel: sun exposure (outdoors x the square's light), blurred; red in the aux map. */
      final float[] sun = new float[MAX * MAX];
      /** Per texel: the ambient reference from that square's own room, never the player's room. */
      final float[] localAmbient = new float[MAX * MAX];
      /** RG16F aux map: sun exposure and local mean linear luminance for night amplification. */
      final FloatBuffer aux = BufferUtils.createFloatBuffer(MAX * MAX * 2);
      final java.util.concurrent.atomic.AtomicInteger state = new java.util.concurrent.atomic.AtomicInteger(FREE);
      // the region read: squares x0 .. x0 + w * step, y0 .. y0 + h * step on floor z
      int x0, y0, w, h, step, z;
      /** window px -> light map UV: u = m[0]*x + m[1]*y + m[2], v = m[3]*x + m[4]*y + m[5] */
      final float[] m = new float[6];
      float ambient;
      int counted, maxExcess, lit;
      long buildNs;

      @Override
      public void render() {
         try {
            upload(this);
         } catch (Throwable t) {
            Log.warn("hdr light: upload failed: " + t);
            ready = false;
         } finally {
            state.set(FREE);
         }
      }
   }

   private static final Frame[] RING = {new Frame(), new Frame(), new Frame(), new Frame()};
   static volatile boolean ready;
   /** The last built map's mean local reference (diagnostic only), squares read and max excess. */
   static volatile float lastAmbient;
   static volatile int lastCounted, lastSeen, lastMaxExcess;
   static volatile float lastNightMin, lastNightMax;
   /** devHdrTraceMs: squares in the line of sight whatever the facing (JNILighting vis bit 4), the medians of all / seen / those */
   static volatile int lastCould, lastMedAll, lastMedSeen, lastMedCould;
   static final float[] mapping = new float[6];
   /** Uploaded coverage in normalized texture coordinates; render thread only. */
   static float mapWidthUV, mapHeightUV;
   static int mapZ;
   private static int tex, auxTex;
   static final int AUX_UNIT = 2;
   private static final byte[] ZERO = new byte[MAX * MAX * 4];
   public static long buildNs, builds;
   private static int logged;
   private static final HdrExposure.LocalAmbient LOCAL_AMBIENT = new HdrExposure.LocalAmbient();
   private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "pzopt-hdr-light");
      t.setDaemon(true);
      return t;
   });

   /**
    * Game thread, MultiTextureFBO2.render(). Queues the upload of the newest map the worker finished (its mapping computed
    * here, from this frame's camera: the squares' light is in world space, only the projection must be this frame's), then
    * hands the worker the region of this frame to read. The reads are the renderer's cached corner colours (plain int
    * reads; a square being re-lit meanwhile gives last frame's value, which is what the eye sees anyway).
    */
   public static void queue(float flipY) {
      if (VERT_LIGHT == null) {
         return;
      }
      IsoPlayer player = IsoPlayer.players[0];
      IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
      if (player == null || cell == null) {
         return;
      }
      long t0 = System.nanoTime();
      int z = (int)Math.floor(player.getZ());
      float zoom = Core.getInstance().getZoom(0);
      int sw = IsoCamera.getScreenWidth(0), sh = IsoCamera.getScreenHeight(0);
      int ts = Core.tileScale;
      float offX = IsoCamera.getOffX(0), offY = IsoCamera.getOffY(0);
      int winH = Core.height, left = IsoCamera.getScreenLeft(0), top = IsoCamera.getScreenTop(0);

      // 1. the newest finished map -> render thread, projected with this frame's camera
      Frame done = null;
      for (Frame f : RING) {
         if (f.state.get() == Frame.BUILT && f.z == z && (done == null || f.buildNs > done.buildNs)) {
            done = f;
         }
      }
      for (Frame f : RING) {
         if (f != done && f.state.get() == Frame.BUILT) {
            f.state.set(Frame.FREE); // superseded
         }
      }
      if (done != null) {
         float[] u = new float[3], v = new float[3];
         float[][] pts = {{0F, 0F}, {1F, 0F}, {0F, 1F}};
         for (int i = 0; i < 3; i++) {
            float sx = pts[i][0] - left, sy = (flipY > 0.5F ? winH - pts[i][1] : pts[i][1]) - top;
            float px = sx * zoom + offX, py = sy * zoom + offY;
            float ix = (px + 2F * py) / (64F * ts) + 3F * z;
            float iy = (px - 2F * py) / (-64F * ts) + 3F * z;
            u[i] = (ix - done.x0) / (done.w * done.step);
            v[i] = (iy - done.y0) / (done.h * done.step);
         }
         done.m[0] = u[1] - u[0];
         done.m[1] = u[2] - u[0];
         done.m[2] = u[0];
         done.m[3] = v[1] - v[0];
         done.m[4] = v[2] - v[0];
         done.m[5] = v[0];
         if (logged++ % 1200 == 0) {
            Log.info(String.format("hdr light: map %dx%d (step %d) at %d,%d z=%d, %d squares read, mean local reference %.3f, max excess %d, %d texels > 10%%, zoom %.2f,"
                  + " worker build %.3f ms, game thread %.3f ms avg", done.w, done.h, done.step, done.x0, done.y0, z, done.counted, done.ambient, done.maxExcess,
                  done.lit, zoom, done.buildNs / 1e6, builds > 0 ? buildNs / 1e6 / builds : 0.0));
         }
         done.state.set(Frame.UPLOADING);
         SpriteRenderer.instance.drawGeneric(done);
      }

      // 2. this frame's region -> a free frame on the worker
      Frame f = null;
      for (Frame c : RING) {
         if (c.state.compareAndSet(Frame.FREE, Frame.BUILDING)) {
            f = c;
            break;
         }
      }
      if (f != null) {
         float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
         for (int c = 0; c < 4; c++) {
            float px = ((c & 1) == 0 ? 0 : sw) * zoom + offX;
            float py = ((c & 2) == 0 ? 0 : sh) * zoom + offY;
            float ix = (px + 2F * py) / (64F * ts) + 3F * z;
            float iy = (px - 2F * py) / (-64F * ts) + 3F * z;
            minX = Math.min(minX, ix);
            maxX = Math.max(maxX, ix);
            minY = Math.min(minY, iy);
            maxY = Math.max(maxY, iy);
         }
         // one map texel per `step` squares: at wide zooms a square is a few dozen pixels, every other one is plenty for
         // the light's gradient and quarters the reads; a margin of a few squares covers a frame of camera motion
         int step = zoom >= 2F ? 2 : 1;
         int x0 = (int)Math.floor(minX) - 3 * step, y0 = (int)Math.floor(minY) - 3 * step;
         int w = Math.min(MAX, ((int)Math.ceil(maxX) + 6 * step - x0) / step), h = Math.min(MAX, ((int)Math.ceil(maxY) + 6 * step - y0) / step);
         if (w <= 0 || h <= 0) {
            f.state.set(Frame.FREE);
         } else {
            f.x0 = x0;
            f.y0 = y0;
            f.w = w;
            f.h = h;
            f.step = step;
            f.z = z;
            Frame job = f;
            WORKER.execute(() -> build(job, cell));
         }
      }
      buildNs += System.nanoTime() - t0;
      builds++;
   }

   /** Worker: read the renderer's cached corner light of each sampled square, chunk by chunk (one lookup per 8x8). */
   private static void build(Frame f, IsoCell cell) {
      long t0 = System.nanoTime();
      try {
         int x0 = f.x0, y0 = f.y0, w = f.w, h = f.h, step = f.step, z = f.z;
         float nightCap = Hdr.nightCap();
         float daylightFloor = 1F - nightCap;
         Hdr.Tune tune = Hdr.tune;
         LOCAL_AMBIENT.prepare(cell, x0, y0, w, h, step, z, tune.gamma);
         f.aux.clear();
         // Missing squares must not enable night gain. Keep unused sun exposure at zero.
         for (int i = 0; i < w * h; i++) {
            f.aux.put(i * 2, 0F).put(i * 2 + 1, 1F);
         }
         float nightMin = 1F, nightMax = 0F;
         ByteBuffer d = f.data;
         d.clear(); // the previous upload left the limit at that map's size
         int[] hist = f.hist;
         java.util.Arrays.fill(hist, 0);
         int[] histSeen = f.histSeen;
         java.util.Arrays.fill(histSeen, 0);
         int seen = 0;
         int[] histCould = f.histCould;
         java.util.Arrays.fill(histCould, 0);
         int could = 0;
         d.put(0, ZERO, 0, w * h * 4);
         java.util.Arrays.fill(f.an, 0, w * h, 0F);
         java.util.Arrays.fill(f.sun, 0, w * h, 0F);
         java.util.Arrays.fill(f.localAmbient, 0, w * h, 1F);
         float ambientSum = 0F;
         int counted = 0;
         int xEnd = x0 + w * step, yEnd = y0 + h * step;
         for (int cy = Math.floorDiv(y0, 8); cy <= Math.floorDiv(yEnd - 1, 8); cy++) {
            for (int cx = Math.floorDiv(x0, 8); cx <= Math.floorDiv(xEnd - 1, 8); cx++) {
               zombie.iso.IsoChunk chunk = cell.getChunk(cx, cy);
               if (chunk == null) {
                  continue;
               }
               int sy0 = Math.max(cy * 8, y0), sy1 = Math.min(cy * 8 + 8, yEnd);
               int sx0 = Math.max(cx * 8, x0), sx1 = Math.min(cx * 8 + 8, xEnd);
               for (int y = sy0; y < sy1; y++) {
                  if ((y - y0) % step != 0) {
                     continue;
                  }
                  for (int x = sx0; x < sx1; x++) {
                     if ((x - x0) % step != 0) {
                        continue;
                     }
                     IsoGridSquare sq = chunk.getGridSquare(x - cx * 8, y - cy * 8, z);
                     if (sq == null || !(sq.lighting[0] instanceof LightingJNI.JNILighting jl)) {
                        continue;
                     }
                     // the floor corners as drawn (lightInfo is the square's base light: the torch / headlight cones are only in these)
                     int[] vl = (int[])VERT_LIGHT.get(jl);
                     int r = 0, g = 0, b = 0;
                     for (int k = 0; k < 4; k++) {
                        int c = vl[k];
                        r += c & 0xFF;
                        g += c >> 8 & 0xFF;
                        b += c >> 16 & 0xFF;
                     }
                     r >>= 2;
                     g >>= 2;
                     b >>= 2;
                     int mc = Math.max(r, Math.max(g, b));
                     hist[mc]++; // same measure as the excess below (max channel)
                     counted++;
                     byte vis = VIS != null ? (byte)VIS.get(jl) : 0;
                     if ((vis & 2) != 0) {
                        histSeen[mc]++;
                        seen++;
                     }
                     if ((vis & 4) != 0) {
                        histCould[mc]++;
                        could++;
                     }
                     int t = ((y - y0) / step) * w + (x - x0) / step;
                     int o = t * 4;
                     f.localAmbient[t] = LOCAL_AMBIENT.at(x, y, daylightFloor);
                     ambientSum += f.localAmbient[t];
                     float luminance = LOCAL_AMBIENT.meanLuminance;
                     f.aux.put(t * 2 + 1, luminance);
                     float night = HdrExposure.night(luminance, tune.nightLo, tune.nightHi, nightCap);
                     nightMin = Math.min(nightMin, night);
                     nightMax = Math.max(nightMax, night);
                     d.put(o, (byte)r).put(o + 1, (byte)g).put(o + 2, (byte)b);
                     // the lights the native lighting found reaching this square (occlusion done there): an unclamped
                     // intensity that peaks at each source, (1 - d / radius)^2 per light, summed in colour. The vertex
                     // light saturates at 1.0 over most of a pool; this keeps the hot core and the light's colour.
                     float ir = 0F, ig = 0F, ib = 0F;
                     int n = jl.resultLightCount();
                     for (int k = 0; k < n; k++) {
                        IsoGridSquare.ResultLight rl = jl.getResultLight(k);
                        if (rl == null || rl.radius <= 0) {
                           continue;
                        }
                        float dx = x - rl.x, dy = y - rl.y, dz = (z - rl.z) * 3F;
                        float fall = 1F - (float)Math.sqrt(dx * dx + dy * dy + dz * dz) / rl.radius;
                        if (fall <= 0F) {
                           continue;
                        }
                        fall *= fall;
                        ir += rl.r * fall;
                        ig += rl.g * fall;
                        ib += rl.b * fall;
                     }
                     f.an[t] = ir * 0.2126F + ig * 0.7152F + ib * 0.0722F;
                     // sunlight reaches squares open to the sky; how much is the square's light (0 in the dark, ~1 by day)
                     f.sun[t] = sq.isOutside() ? Math.max(r, Math.max(g, b)) / 255F : 0F;
                     f.ar[t] = ir;
                     f.ag[t] = ig;
                     f.ab[t] = ib;
                  }
               }
            }
         }
         // This mean is diagnostic only. Applying it to all texels would let one room's switch
         // change another room's HDR gain. Each texel below uses its own local reference.
         float amb = counted > 0 ? ambientSum / counted : 1F;
         float hot = Math.max(0F, Math.min(1F, Hdr.tune.lightHot));
         // the per-square light lists are all-or-nothing at a cone's edge or a wall: a separable 5-tap blur (~2 squares)
         // keeps the analytic field from cutting a hard edge into the smooth vertex light (hdrcmp-ours, 09:25)
         blur5(f.an, f.tmp, w, h);
         blur5(f.sun, f.tmp, w, h);
         for (int i = 0, n = w * h; i < n; i++) {
            f.aux.put(i * 2, clamp255(f.sun[i]) / 255F);
         }
         f.aux.position(0).limit(w * h * 2);
         int maxExcess = 0, lit = 0;
         for (int i = 0, n = w * h; i < n; i++) {
            int r = d.get(i * 4) & 0xFF, g = d.get(i * 4 + 1) & 0xFF, b = d.get(i * 4 + 2) & 0xFF;
            // the light's strength is its largest channel: by luminance a fire's orange counts at a fraction of a white
            // torch (red weighs 0.21) and the fire-lit ground got almost no gain (hdrcmp-ours, 09:25)
            float l = Math.max(r, Math.max(g, b)) / 255F;
            // Keep absolute excess: normalization by 1 - ambient would restore full gain from tiny drops.
            float ex = Math.max(0F, l - f.localAmbient[i]);
            // alpha: how lit (vertex excess) x how close to a source (analytic, 0..1, weighted by lightHot)
            float an = Math.min(1F, f.an[i]);
            int e = clamp255(ex * (1F - hot + hot * an));
            maxExcess = Math.max(maxExcess, e);
            lit += e > 25 ? 1 : 0;
            d.put(i * 4 + 3, (byte)e);
            // rgb: the light's chroma (max channel 1) where lights reach, else the vertex light's
            float cr = f.an[i] > 0F ? f.ar[i] : r / 255F, cg = f.an[i] > 0F ? f.ag[i] : g / 255F, cb = f.an[i] > 0F ? f.ab[i] : b / 255F;
            float cm = Math.max(Math.max(cr, cg), Math.max(cb, 1e-4F));
            d.put(i * 4, (byte)clamp255(cr / cm)).put(i * 4 + 1, (byte)clamp255(cg / cm)).put(i * 4 + 2, (byte)clamp255(cb / cm));
         }
         d.position(0).limit(w * h * 4);
         f.ambient = amb;
         f.counted = counted;
         f.maxExcess = maxExcess;
         lastAmbient = amb;
         lastCounted = counted;
         lastSeen = seen;
         lastCould = could;
         if (Config.DEV_HDR_TRACE_MS > 0) {
            lastMedAll = median(hist, counted);
            lastMedSeen = median(histSeen, seen);
            lastMedCould = median(histCould, could);
         }
         lastMaxExcess = maxExcess;
         lastNightMin = counted > 0 ? nightMin : 0F;
         lastNightMax = nightMax;
         f.lit = lit;
         f.buildNs = System.nanoTime() - t0;
         f.state.set(Frame.BUILT);
      } catch (Throwable t) {
         Log.warn("hdr light: build failed: " + t);
         f.state.set(Frame.FREE);
      } finally {
         LOCAL_AMBIENT.clear();
      }
   }

   private static int median(int[] h, int n) {
      for (int i = 0, acc = 0; i < 256; i++) {
         acc += h[i];
         if (acc > n / 2) {
            return i;
         }
      }
      return 0;
   }

   /** In-place separable [1 4 6 4 1]/16 blur of a w x h field (edges clamped). */
   static void blur5(float[] a, float[] t, int w, int h) {
      for (int y = 0; y < h; y++) {
         int row = y * w;
         for (int x = 0; x < w; x++) {
            int x0 = Math.max(0, x - 2), x1 = Math.max(0, x - 1), x3 = Math.min(w - 1, x + 1), x4 = Math.min(w - 1, x + 2);
            t[row + x] = (a[row + x0] + 4F * a[row + x1] + 6F * a[row + x] + 4F * a[row + x3] + a[row + x4]) * 0.0625F;
         }
      }
      for (int y = 0; y < h; y++) {
         int y0 = Math.max(0, y - 2) * w, y1 = Math.max(0, y - 1) * w, y3 = Math.min(h - 1, y + 1) * w, y4 = Math.min(h - 1, y + 2) * w, yy = y * w;
         for (int x = 0; x < w; x++) {
            a[yy + x] = (t[y0 + x] + 4F * t[y1 + x] + 6F * t[yy + x] + 4F * t[y3 + x] + t[y4 + x]) * 0.0625F;
         }
      }
   }

   private static int clamp255(float v) {
      int i = (int)(v * 255F + 0.5F);
      return i < 0 ? 0 : Math.min(i, 255);
   }

   /** Render thread: bind the last uploaded light map before sampling it, preserving the active texture unit. */
   static boolean bind() {
      if (tex == 0 || !ready || !HdrExposure.matchesFloor(mapZ)) {
         return false;
      }
      // Chunk AO also uses this unit; a floor change can leave no new map to upload and restore its binding.
      int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + Hdr.LIGHT_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
      GL13.glActiveTexture(prevActive);
      Texture.lastTextureID = -1;
      return true;
   }

   /** Render thread: rebind the aux (sun and local luminance) map before any HDR pass samples it. */
   static boolean bindAux() {
      if (auxTex == 0 || !ready || !HdrExposure.matchesFloor(mapZ)) {
         return false;
      }
      int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + AUX_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, auxTex);
      GL13.glActiveTexture(prevActive);
      Texture.lastTextureID = -1;
      return true;
   }

   /** Render thread: upload the map to its texture on unit 5 and publish the mapping for the composite. */
   private static void upload(Frame f) {
      int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + Hdr.LIGHT_UNIT);
      if (tex == 0) {
         tex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, MAX, MAX, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer(MAX * MAX * 4));
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 0x812F);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 0x812F);
      }
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
      GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
      GL11.glPixelStorei(0x0CF2, f.w); // GL_UNPACK_ROW_LENGTH
      GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, f.w, f.h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, f.data);
      if (auxTex == 0) {
         auxTex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, auxTex);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG16F, MAX, MAX, 0, GL30.GL_RG, GL11.GL_FLOAT, BufferUtils.createFloatBuffer(MAX * MAX * 2));
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 0x812F);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 0x812F);
      }
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, auxTex);
      GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, f.w, f.h, GL30.GL_RG, GL11.GL_FLOAT, f.aux);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
      GL11.glPixelStorei(0x0CF2, 0);
      GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
      GL13.glActiveTexture(prevActive);
      Texture.lastTextureID = -1;
      // the map uses the top-left f.w x f.h texels of the MAX x MAX texture
      float sx = (float)f.w / MAX, sy = (float)f.h / MAX;
      mapWidthUV = sx;
      mapHeightUV = sy;
      mapZ = f.z;
      mapping[0] = f.m[0] * sx;
      mapping[1] = f.m[1] * sx;
      mapping[2] = f.m[2] * sx;
      mapping[3] = f.m[3] * sy;
      mapping[4] = f.m[4] * sy;
      mapping[5] = f.m[5] * sy;
      if (!ready) {
         Log.info(String.format("hdr light: first upload %dx%d, mapping u = %.4f x + %.4f y + %.4f, v = %.4f x + %.4f y + %.4f",
               f.w, f.h, mapping[0], mapping[1], mapping[2], mapping[3], mapping[4], mapping[5]));
      }
      ready = true;
   }
}
