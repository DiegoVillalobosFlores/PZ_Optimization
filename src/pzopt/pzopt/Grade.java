package pzopt;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;
import zombie.GameTime;
import zombie.iso.weather.ClimateManager;

/**
 * Colour grading of the world by time of day and weather (Config.COLOR_GRADING, candidate B of
 * docs/plan-graphics-enhancements.md, 2026-09-26). One 33³ 3D LUT, one trilinear fetch per pixel at the end of
 * the stock screen composite ({@code screen.frag}, before the HDR expansion), plus a triangular dither of one 8-bit step.
 * The LUT is RGB10_A2 (33³ x 4 bytes).
 *
 * <p>The LUT is the blend of per-condition looks (night: the rods' Purkinje shift; dawn; golden hour; overcast; rain;
 * storm; fog; snow), weighted from the climate manager every frame on the game thread (cheap: a dozen getters and
 * arithmetic) and smoothed over ~2 s; when the blended look moves far enough the table is baked again on a worker
 * (~36k lattice points, a few ms off-thread, at most a few times a second) and uploaded by the render thread (430 KB, a
 * few times a minute in practice: game time moves slowly). The GPU cost is the fetch; the rest is nothing a frame.
 * Custom looks: {@code ~/Zomboid/pzopt/luts/<condition>.cube} (base, night, dawn, dusk, overcast, rain, storm, fog, snow)
 * are applied after the procedural grade with their condition's weight (base always), so modders can ship a LUT.
 */
public final class Grade {
   private Grade() {
   }

   /** Patched composite program + LUT exist (render thread). */
   static final int LUT_UNIT = 13;

   static final String[] CONDITIONS = {"night", "dawn", "dusk", "overcast", "rain", "storm", "fog", "snow"};
   private static final boolean PLATFORM = !HdrMac.MAC; // the macOS context is GL 2.1 (GLSL 1.20): no 3D-texture composite patch

   /** The game thread's blended look (0 = neutral when grading is off). */
   private static volatile boolean patched;
   private static volatile String state = "off";

   public static boolean enabled() {
      return Overrides.enabled() && Config.COLOR_GRADING && PLATFORM;
   }

   public static String state() {
      return state;
   }

   // ------------------------------------------------------------------------------------------------ looks

   static GradeMath.Look[] defaultLooks() {
      GradeMath.Look night = GradeMath.neutral();
      night.purkinje = 0.55F; // 0.75 / 0.10 (run dk-night1), then 0.6 / 0.05 (dk-fin-night) still greyed the lamp-lit rooms' dark wood
      night.purkinjeHi = 0.04F;
      night.mired = -12F;
      night.saturation = 0.88F;
      night.contrast = 1.04F;
      night.shadowGain = new float[] {0.92F, 0.98F, 1.12F};

      GradeMath.Look dawn = GradeMath.neutral();
      dawn.mired = 22F;
      dawn.tint = 0.025F;
      dawn.saturation = 1.04F;
      dawn.shadowGain = new float[] {0.95F, 0.97F, 1.10F};
      dawn.highlightGain = new float[] {1.06F, 0.98F, 0.96F};

      GradeMath.Look dusk = GradeMath.neutral();
      dusk.mired = 38F;
      dusk.saturation = 1.10F;
      dusk.contrast = 1.05F;
      dusk.shadowGain = new float[] {0.93F, 0.98F, 1.10F};
      dusk.highlightGain = new float[] {1.08F, 0.99F, 0.90F};

      GradeMath.Look overcast = GradeMath.neutral();
      overcast.mired = -8F;
      overcast.saturation = 0.90F;
      overcast.contrast = 0.97F;

      GradeMath.Look rain = GradeMath.neutral();
      rain.mired = -10F;
      rain.saturation = 0.85F;
      rain.contrast = 0.96F;
      rain.exposure = -0.05F;

      GradeMath.Look storm = GradeMath.neutral();
      storm.mired = -14F;
      storm.tint = -0.02F;
      storm.saturation = 0.78F;
      storm.contrast = 1.08F;
      storm.exposure = -0.08F;

      GradeMath.Look fog = GradeMath.neutral();
      fog.mired = -5F;
      fog.saturation = 0.86F;
      fog.contrast = 0.88F;
      fog.exposure = 0.04F;

      GradeMath.Look snow = GradeMath.neutral();
      snow.mired = -14F;
      snow.saturation = 0.9F;
      snow.exposure = 0.06F;
      return new GradeMath.Look[] {night, dawn, dusk, overcast, rain, storm, fog, snow};
   }

   private static GradeMath.Look[] looks = defaultLooks();
   private static final float[] weights = new float[CONDITIONS.length];
   private static final float[] smoothed = new float[CONDITIONS.length];
   private static long lastNs;
   private static boolean stormFlag;
   private static float stormSmooth;

   /** Test / rig: the weights as last computed. */
   static float[] weights() {
      return smoothed.clone();
   }

   /**
    * The condition weights from the climate: night from the stock night strength, dawn / golden hour as bells around the
    * season's dawn and dusk (the golden hour before dusk), weather from its intensities.
    */
   static void computeWeights(float hour, float dawnHour, float duskHour, float night, float cloud, float rain, float snow, float fog,
         float storm, float[] out) {
      out[0] = clamp01(night);
      float day = 1F - out[0];
      out[1] = bell(hour, dawnHour + 0.4F, 1.4F) * Math.max(0.35F, day);
      out[2] = bell(hour, duskHour - 0.6F, 1.6F) * Math.max(0.35F, day);
      float wet = clamp01(Math.max(rain, snow));
      out[3] = clamp01(cloud) * (1F - 0.6F * wet);
      out[4] = clamp01(rain) * (1F - clamp01(storm));
      out[5] = clamp01(storm);
      out[6] = clamp01(fog);
      out[7] = clamp01(snow);
      // the sun-coloured looks fade under a covered sky
      float cover = clamp01(Math.max(cloud, Math.max(wet, fog)));
      out[1] *= 1F - 0.7F * cover;
      out[2] *= 1F - 0.7F * cover;
   }

   private static float bell(float h, float centre, float halfWidth) {
      float d = Math.abs(h - centre);
      d = Math.min(d, 24F - d);
      return GradeMath.smoothstep(halfWidth, 0F, d);
   }

   private static float clamp01(float v) {
      return v < 0F ? 0F : v > 1F ? 1F : v;
   }

   // ------------------------------------------------------------------------------------------------ bake (worker)

   private static final AtomicReference<int[][]> ready = new AtomicReference<>(); // {grade-only 33³, fused 65³}, packed RGB10_A2
   private static final AtomicBoolean baking = new AtomicBoolean();
   private static GradeMath.Look bakedLook; // game thread: the look of the last requested bake
   private static float bakedStrength = -1F;
   private static long bakeRequests, bakesDone, uploads;
   private static long bakeNsTotal;
   private static long lastBakeNs;
   private static GradeMath.Cube[] cubes; // base + CONDITIONS
   private static long cubesCheckedNs;
   private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "pzopt-grade");
      t.setDaemon(true);
      t.setPriority(Thread.MIN_PRIORITY);
      return t;
   });

   private static void loadCubes() {
      File dir = new File(zombie.ZomboidFileSystem.instance.getCacheDir() + File.separator + "pzopt" + File.separator + "luts");
      GradeMath.Cube[] c = new GradeMath.Cube[CONDITIONS.length + 1];
      int n = 0;
      if (dir.isDirectory()) {
         for (int i = 0; i <= CONDITIONS.length; i++) {
            File f = new File(dir, (i == 0 ? "base" : CONDITIONS[i - 1]) + ".cube");
            if (f.isFile()) {
               try {
                  c[i] = GradeMath.Cube.read(f);
                  n++;
               } catch (Exception e) {
                  Log.warn("color grading: " + f + ": " + e.getMessage());
               }
            }
         }
      }
      if (n > 0) {
         Log.info("color grading: " + n + " custom .cube LUT(s) from " + dir);
      }
      cubes = n > 0 ? c : null;
   }

   private static float distance(GradeMath.Look a, GradeMath.Look b) {
      float d = Math.abs(a.exposure - b.exposure);
      d = Math.max(d, Math.abs(a.mired - b.mired) / 150F);
      d = Math.max(d, Math.abs(a.tint - b.tint));
      d = Math.max(d, Math.abs(a.contrast - b.contrast));
      d = Math.max(d, Math.abs(a.saturation - b.saturation));
      d = Math.max(d, Math.abs(a.purkinje - b.purkinje));
      for (int c = 0; c < 3; c++) {
         d = Math.max(d, Math.abs(a.shadowGain[c] - b.shadowGain[c]));
         d = Math.max(d, Math.abs(a.highlightGain[c] - b.highlightGain[c]));
      }
      return d;
   }

   private static final GradeMath.Look NEUTRAL = GradeMath.neutral();
   /** The blended look is the identity (game thread writes, render thread reads). */
   static volatile boolean neutralNow;

   /** A rebake below this look distance would change an 8-bit output by well under one step. */
   private static final float REBAKE_DISTANCE = 0.004F;

   /** Game thread, once per frame (Darkness.frame): weights, smoothing, bake requests. */
   static void frame() {
      if (!enabled() || !patched) {
         if (bakedLook != null) {
            bakedLook = null; // off: the next enable bakes afresh
         }
         lastNs = 0L;
         return;
      }
      long now = System.nanoTime();
      if (lastNs != 0L && now - lastNs < 100_000_000L) {
         return; // 10 Hz: the climate and the hour move slowly (the blend showed in the game-thread samples every frame)
      }
      float dt = lastNs == 0L ? 1F : Math.min(1F, (now - lastNs) / 1e9F);
      lastNs = now;
      if (cubesCheckedNs == 0L) { // at the first enabled frame and after every Apply: the .cube files are read once
         cubesCheckedNs = now;
         loadCubes();
      }
      ClimateManager cm = ClimateManager.getInstance();
      GameTime gt = GameTime.getInstance();
      if (cm == null || gt == null) {
         return;
      }
      float dawnH = 6F, duskH = 20F;
      try {
         if (cm.getSeason() != null) {
            dawnH = cm.getSeason().getDawn();
            duskH = cm.getSeason().getDusk();
         }
      } catch (Throwable ignored) {
      }
      stormFlag = cm.getIsThunderStorming();
      stormSmooth += ((stormFlag ? 1F : 0F) - stormSmooth) * Math.min(1F, dt / 4F);
      computeWeights(gt.getTimeOfDay(), dawnH, duskH, cm.getNightStrength(), cm.getCloudIntensity(), cm.getRainIntensity(),
         cm.getSnowIntensity(), cm.getFogIntensity(), stormSmooth, weights);
      float k = bakedLook == null ? 1F : Math.min(1F, dt / 2F); // ~2 s to follow a change; a fresh start jumps to the target
      for (int i = 0; i < weights.length; i++) {
         smoothed[i] += (weights[i] - smoothed[i]) * k;
      }
      Tune.poll();
      float strength = Config.COLOR_GRADING_PCT / 100F;
      GradeMath.Look target = GradeMath.blend(looks, smoothed, strength);
      if (Config.COLOR_GRADING_NIGHT_PCT != 100) {
         target.purkinje *= Config.COLOR_GRADING_NIGHT_PCT / 100F;
      }
      // clear daylight without custom LUTs is the identity: the composite then skips the grade (and its dither) entirely
      neutralNow = cubes == null && distance(target, NEUTRAL) < 0.001F;
      boolean due = bakedLook == null || strength != bakedStrength || distance(target, bakedLook) > REBAKE_DISTANCE || Tune.changed
         || Config.DEV_GRADE_REBAKE_MS > 0 && now - lastBakeNs > Config.DEV_GRADE_REBAKE_MS * 1_000_000L;
      if (due && Pbo.tried && baking.compareAndSet(false, true)) {
         Tune.changed = false;
         bakedLook = target;
         bakedStrength = strength;
         lastBakeNs = now;
         bakeRequests++;
         final GradeMath.Look look = target.copy();
         final GradeMath.Cube[] cs = cubes;
         final float[] cw = cubeWeights(strength);
         WORKER.execute(() -> {
            try {
               long t0 = System.nanoTime();
               int[] t = GradeMath.pack(GradeMath.bake(look, cs, cw));
               int[] f = Config.COLOR_GRADING_FUSED ? GradeMath.bakeFusedPacked(look, cs, cw) : null;
               bakeNsTotal += System.nanoTime() - t0;
               bakesDone++;
               if (!Pbo.fill(t, f)) { // no free mapped slot (or none at all): the render thread copies the arrays
                  ready.set(new int[][] {t, f});
               }
               if (Config.DEV_GRADE_TRACE) {
                  Log.info("color grading: baked " + look + " in " + (System.nanoTime() - t0) / 1000 + " us; weights " + java.util.Arrays.toString(smoothed));
               }
            } catch (Throwable e) {
               Log.warn("color grading: bake failed: " + e);
            } finally {
               baking.set(false);
            }
         });
      }
   }

   private static float[] cubeWeights(float strength) {
      float[] w = new float[CONDITIONS.length + 1];
      w[0] = strength;
      for (int i = 0; i < CONDITIONS.length; i++) {
         w[i + 1] = smoothed[i] * strength;
      }
      return w;
   }

   /** Live apply (Enhancements): force a bake with the new strength / on-off. */
   static void reconfigure() {
      bakedLook = null;
      bakedStrength = -1F;
      cubes = null;
      cubesCheckedNs = 0L;
      Log.info("color grading: settings applied (" + (Config.COLOR_GRADING ? "on, " + Config.COLOR_GRADING_PCT + " %, night " + Config.COLOR_GRADING_NIGHT_PCT + " %" : "off") + ")");
   }

   public static String stats() {
      return "color grading: " + state + " bakes=" + bakesDone + "/" + bakeRequests + " avg bake us=" + (bakesDone == 0 ? 0 : bakeNsTotal / bakesDone / 1000)
         + " uploads=" + uploads + " (mapped " + mappedUploads + ") upload us avg=" + (uploads == 0 ? 0 : uploadNsTotal / uploads / 1000) + " max=" + uploadNsMax / 1000 + " weights=" + fmt(smoothed);
   }

   private static String fmt(float[] w) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < w.length; i++) {
         if (w[i] >= 0.01F) {
            sb.append(sb.length() == 0 ? "" : ",").append(CONDITIONS[i]).append(':').append(String.format(java.util.Locale.ROOT, "%.2f", w[i]));
         }
      }
      return sb.length() == 0 ? "-" : sb.toString();
   }

   // ------------------------------------------------------------------------------------------------ render thread

   private static int lutTex;
   private static int program, uP, uD, uF;
   /** The grade applies this frame (render thread; set in stream order by Darkness's frame state). */
   static volatile boolean renderOn;

   /**
    * Render thread, WeatherShader.startRenderThread (the composite program {@code prog} is bound): uploads a fresh
    * table, binds the LUT, sets pzGradeP. Nothing but one uniform when grading is off.
    */
   public static void worldUniforms(int prog) {
      if (!patched || prog == 0) {
         return;
      }
      if (prog != program) {
         program = prog;
         uP = GL20.glGetUniformLocation(prog, "pzGradeP");
         uD = GL20.glGetUniformLocation(prog, "pzGradeD");
         uF = GL20.glGetUniformLocation(prog, "pzGradeF");
      }
      if (uP < 0) {
         return;
      }
      boolean on = renderOn && enabled();
      if (on) {
         Pbo.frame();
         int[][] t = ready.getAndSet(null);
         if (t != null && !uploadLuts(t)) {
            on = false;
         }
      }
      if (on && lutTex != 0) {
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + LUT_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, lutTex);
         boolean fused = fusedTex != 0 && Config.COLOR_GRADING_FUSED;
         if (fused) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + FUSED_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, fusedTex);
         }
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         float n = GradeMath.N, nf = GradeMath.NF;
         GL20.glUniform4f(uP, 1F, Config.COLOR_GRADING_DITHER ? 1F / 255F : 0F, (n - 1F) / n, 0.5F / n);
         GL20.glUniform4f(uF, fused ? 1F : 0F, (nf - 1F) / nf, 0.5F / nf, 0F);
         if (uD >= 0) {
            GL20.glUniform4f(uD, Config.DEV_GRADE_ABLATE, 0F, 0F, 0F);
         }
         state = fused ? "on (fused)" : "on";
      } else {
         GL20.glUniform4f(uP, 0F, 0F, 1F, 0F);
      }
   }

   private static java.nio.IntBuffer upload;
   private static int fusedTex;
   static final int FUSED_UNIT = 14;
   static long uploadNsMax, uploadNsTotal;

   private static int texture3d(int unit, int tex, int n, java.nio.IntBuffer data) {
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
      if (tex == 0) {
         tex = GL11.glGenTextures();
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
         GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGB10_A2, n, n, n, 0, GL11.GL_RGBA, GL12.GL_UNSIGNED_INT_2_10_10_10_REV, data);
      } else {
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex);
         GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, 0, n, n, n, GL11.GL_RGBA, GL12.GL_UNSIGNED_INT_2_10_10_10_REV, data);
      }
      return tex;
   }

   /** Render thread: the worker's packed tables into the two 3D textures (1.1 MB + 144 KB of ints, no conversion). */
   private static boolean uploadLuts(int[][] t) {
      try {
         long t0 = System.nanoTime();
         if (upload == null) {
            upload = MemoryUtil.memAllocInt(GradeMath.NF * GradeMath.NF * GradeMath.NF);
         }
         upload.clear();
         upload.put(t[0]).flip();
         lutTex = texture3d(LUT_UNIT, lutTex, GradeMath.N, upload);
         if (t[1] != null) {
            upload.clear();
            upload.put(t[1]).flip();
            fusedTex = texture3d(FUSED_UNIT, fusedTex, GradeMath.NF, upload);
         }
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         long dt = System.nanoTime() - t0;
         uploadNsTotal += dt;
         uploadNsMax = Math.max(uploadNsMax, dt);
         uploads++;
         return true;
      } catch (Throwable e) {
         Log.warn("color grading: LUT upload failed: " + e);
         state = "failed: " + e;
         patched = false;
         return false;
      }
   }

   /**
    * Uploads without the render thread copying 1.25 MB (the direct path: 118 us average, 467 us at worst per bake, run
    * dk-upl1): a persistently mapped pixel-unpack buffer with two slots (ARB_buffer_storage). The worker writes the packed
    * tables into a free slot; the render thread only issues the two glTexSubImage3D from it (the copy runs on the GPU)
    * and fences the slot, which is free again once the fence has passed (checked at most once a frame, only while a slot
    * is in flight, and only three frames after the upload so the check never waits).
    */
   static final class Pbo {
      private static final int FREE = 0, WRITING = 1, FILLED = 2, IN_FLIGHT = 3;
      private static final int N3 = GradeMath.N * GradeMath.N * GradeMath.N, NF3 = GradeMath.NF * GradeMath.NF * GradeMath.NF;
      private static final int SLOT_INTS = N3 + NF3;
      private static final java.util.concurrent.atomic.AtomicIntegerArray STATE = new java.util.concurrent.atomic.AtomicIntegerArray(2);
      private static final java.nio.IntBuffer[] VIEW = new java.nio.IntBuffer[2];
      private static final long[] FENCE = new long[2];
      private static final long[] FRAME = new long[2];
      private static final boolean[] HAS_FUSED = new boolean[2];
      private static volatile boolean ready;
      /** The render thread has set the buffer up (or found it unavailable): the first bake waits for it. */
      static volatile boolean tried;
      private static int buffer;
      private static long frames;

      /** Worker: the tables into a free slot; false when there is none (the caller falls back). */
      static boolean fill(int[] t, int[] f) {
         if (!ready) {
            return false;
         }
         for (int i = 0; i < 2; i++) {
            if (STATE.compareAndSet(i, FREE, WRITING)) {
               java.nio.IntBuffer v = VIEW[i].duplicate();
               v.clear();
               v.put(t);
               if (f != null) {
                  v.position(N3);
                  v.put(f);
               }
               HAS_FUSED[i] = f != null;
               STATE.set(i, FILLED);
               return true;
            }
         }
         return false;
      }

      /** Render thread, once a frame while grading is on: set up, retire passed fences, upload a filled slot. */
      static void frame() {
         frames++;
         if (!tried) {
            try {
               GLCapabilities caps = GL.getCapabilities();
               if (caps.OpenGL44 || caps.GL_ARB_buffer_storage) {
                  long bytes = 2L * SLOT_INTS * 4L;
                  buffer = org.lwjgl.opengl.GL15.glGenBuffers();
                  org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, buffer);
                  int flags = org.lwjgl.opengl.GL30.GL_MAP_WRITE_BIT | org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT | org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT;
                  org.lwjgl.opengl.GL44.glBufferStorage(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, bytes, flags);
                  java.nio.ByteBuffer map = org.lwjgl.opengl.GL30.glMapBufferRange(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, 0L, bytes, flags);
                  org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                  if (map != null) {
                     java.nio.IntBuffer all = map.order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
                     for (int i = 0; i < 2; i++) {
                        all.position(i * SLOT_INTS).limit((i + 1) * SLOT_INTS);
                        VIEW[i] = all.slice();
                        all.clear();
                     }
                     ready = true;
                     Log.info("color grading: LUT uploads through a persistently mapped buffer (2 x " + SLOT_INTS * 4 / 1024 + " KB)");
                  }
               }
            } catch (Throwable e) {
               Log.warn("color grading: mapped upload buffer unavailable (" + e + "); the render thread copies the tables");
               org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            } finally {
               tried = true; // after the views are published: the game thread's first bake request waits for this
            }
         }
         if (!ready) {
            return;
         }
         for (int i = 0; i < 2; i++) {
            if (STATE.get(i) == IN_FLIGHT && frames - FRAME[i] >= 3) {
               int r = org.lwjgl.opengl.GL32.glClientWaitSync(FENCE[i], 0, 0L);
               if (r == org.lwjgl.opengl.GL32.GL_ALREADY_SIGNALED || r == org.lwjgl.opengl.GL32.GL_CONDITION_SATISFIED) {
                  org.lwjgl.opengl.GL32.glDeleteSync(FENCE[i]);
                  STATE.set(i, FREE);
               }
            }
         }
         for (int i = 0; i < 2; i++) {
            if (STATE.get(i) == FILLED) {
               long t0 = System.nanoTime();
               org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, buffer);
               long base = (long)i * SLOT_INTS * 4L;
               lutTex = texture3dFrom(LUT_UNIT, lutTex, GradeMath.N, base);
               if (HAS_FUSED[i]) {
                  fusedTex = texture3dFrom(FUSED_UNIT, fusedTex, GradeMath.NF, base + N3 * 4L);
               }
               org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, 0); // never leave it bound: the game's own uploads would read from it
               GL13.glActiveTexture(GL13.GL_TEXTURE0);
               FENCE[i] = org.lwjgl.opengl.GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
               FRAME[i] = frames;
               STATE.set(i, IN_FLIGHT);
               long dt = System.nanoTime() - t0;
               uploadNsTotal += dt;
               uploadNsMax = Math.max(uploadNsMax, dt);
               uploads++;
               mappedUploads++;
            }
         }
      }
   }

   static long mappedUploads;

   /** texture3d with the data from the bound pixel-unpack buffer at {@code offset} bytes. */
   private static int texture3dFrom(int unit, int tex, int n, long offset) {
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
      if (tex == 0) {
         tex = GL11.glGenTextures();
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
         GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGB10_A2, n, n, n, 0, GL11.GL_RGBA, GL12.GL_UNSIGNED_INT_2_10_10_10_REV, offset);
      } else {
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex);
         GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, 0, n, n, n, GL11.GL_RGBA, GL12.GL_UNSIGNED_INT_2_10_10_10_REV, offset);
      }
      return tex;
   }

   // ------------------------------------------------------------------------------------------------ composite patch

   private static final String GLSL = String.join("\n",
      "",
      "// pzopt: colour grading (pzopt.Grade): the time-of-day / weather LUT and a triangular dither, before the HDR expansion",
      "layout(binding = " + LUT_UNIT + ") uniform sampler3D pzGradeLut;",
      "uniform vec4 pzGradeP; // x on, y dither amplitude, z LUT scale (n-1)/n, w LUT offset 0.5/n",
      "float pzGradeIgn(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }",
      "uniform vec4 pzGradeD; // dev ablation (devGradeAblate): x 0 full, 1 no shaper, 2 no LUT fetch, 3 the wrapper only",
      "vec3 pzGrade(vec3 c) {",
      "  if (pzGradeD.x > 2.5) return c;",
      "  vec3 u = (pzGradeD.x > 0.5 ? clamp(c, 0.0, 1.0) : sqrt(clamp(c, 0.0, 1.0))) * pzGradeP.z + pzGradeP.w;",
      "  vec3 g = pzGradeD.x > 1.5 ? u * 1.0001 : texture(pzGradeLut, u).rgb;",
      "  if (pzGradeP.y > 0.0) { // dither (colorGradingDither): its hash alone was ~12 us / frame at 5120x2160 (runs dk-abl*)",
      "    g += (pzGradeIgn(gl_FragCoord.xy) + pzGradeIgn(gl_FragCoord.xy + vec2(47.0, 17.0)) - 1.0) * pzGradeP.y; // triangular, -1..1",
      "  }",
      "  return g;",
      "}",
      "layout(binding = " + FUSED_UNIT + ") uniform sampler3D pzGradeFused;",
      "uniform vec4 pzGradeF; // x fused table on, y scale (n-1)/n, z offset 0.5/n",
      "void main() {",
      "  if (pzGradeP.x < 0.5) {",
      "    pzGradeStockMain();",
      "    return;",
      "  }",
      "  if (pzGradeF.x > 0.5 && NightVisionGoggles < 0.5 && VarInfo.x == 0.0 && DrunkFactor <= 0.0 && BlurFactor <= 0.0) {",
      "    // the plain world path: the stock screenWorld after its first desaturation (clamp, desaturate 0.1, contrast 1.2)",
      "    // and the grade are one table; the stock film grain (at most 0.0015) and its 3D noise are not computed",
      "    vec3 p = desaturate(textureBicubic(DIFFUSE, vUV.st).xyz, DesaturationVal);",
      "    gl_FragColor = vec4(texture(pzGradeFused, clamp(p, 0.0, 1.0) * pzGradeF.y + pzGradeF.z).rgb * " + (GradeMath.FUSED_HI - GradeMath.FUSED_LO) + " + " + GradeMath.FUSED_LO + ", 1.0);",
      "    return;",
      "  }",
      "  pzGradeStockMain();",
      "  if (NightVisionGoggles < 0.5) {",
      "    gl_FragColor.rgb = pzGrade(gl_FragColor.rgb);",
      "  }",
      "}",
      "");

   /** ShaderUnit hook (before Hdr's): the world composite gets the grade appended; any other shader passes through. */
   public static String patchShader(String fileName, String code) {
      if (!Overrides.enabled() || !PLATFORM || code == null || fileName == null) {
         return code;
      }
      String f = fileName.replace('\\', '/');
      if (!f.endsWith("/screen.frag") || !code.contains("void main()") || !code.startsWith("#version")) {
         return code;
      }
      try {
         GLCapabilities caps = GL.getCapabilities();
         boolean ext = caps.OpenGL42 || caps.GL_ARB_shading_language_420pack;
         if (!ext) {
            state = "unsupported (no GL 4.2 / ARB_shading_language_420pack)";
            Log.info("color grading: " + state);
            return code;
         }
         int eol = code.indexOf('\n');
         String head = code.substring(0, eol + 1);
         String rest = code.substring(eol + 1);
         String patched = head + "#extension GL_ARB_shading_language_420pack : enable\n" // a #version 330 shader needs it for layout(binding) even on a GL 4.6 driver
            + rest.replace("void main()", "void pzGradeStockMain()") + GLSL;
         int test = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
         GL20.glShaderSource(test, patched);
         GL20.glCompileShader(test);
         boolean ok = GL20.glGetShaderi(test, GL20.GL_COMPILE_STATUS) != 0;
         String log = ok ? "" : GL20.glGetShaderInfoLog(test, 4096);
         GL20.glDeleteShader(test);
         if (!ok) {
            state = "compile failed";
            Log.warn("color grading: patched screen.frag did not compile, stock composite: " + log);
            return code;
         }
         Grade.patched = true;
         state = Config.COLOR_GRADING ? "ready" : "patched (off)";
         Log.info("color grading: world composite patched (" + (Config.COLOR_GRADING ? "on" : "off, live") + ")");
         return patched;
      } catch (Throwable e) {
         state = "failed: " + e;
         Log.warn("color grading: patch failed: " + e);
         return code;
      }
   }

   // ------------------------------------------------------------------------------------------------ dev tune file

   /**
    * Dev: {@code -Dpzopt.gradeTune=<file>} re-read once a second; lines {@code <condition>.<field>=<value>} (fields:
    * exposure, mired, tint, contrast, saturation, purkinje, purkinjeHi, shadow=r,g,b, highlight=r,g,b) override the
    * built-in looks, so one run can tune them.
    */
   static final class Tune {
      static boolean changed;
      private static long checkedNs, mtime;

      static void poll() {
         String path = Config.GRADE_TUNE;
         if (path.isEmpty()) {
            return;
         }
         long now = System.nanoTime();
         if (now - checkedNs < 1_000_000_000L) {
            return;
         }
         checkedNs = now;
         File f = new File(path);
         if (!f.isFile() || f.lastModified() == mtime) {
            return;
         }
         mtime = f.lastModified();
         GradeMath.Look[] l = defaultLooks();
         try {
            for (String line : java.nio.file.Files.readAllLines(f.toPath())) {
               line = line.trim();
               int eq = line.indexOf('=');
               int dot = line.indexOf('.');
               if (line.startsWith("#") || eq < 0 || dot < 0 || dot > eq) {
                  continue;
               }
               String cond = line.substring(0, dot), field = line.substring(dot + 1, eq).trim(), v = line.substring(eq + 1).trim();
               int i = java.util.Arrays.asList(CONDITIONS).indexOf(cond);
               if (i < 0) {
                  continue;
               }
               GradeMath.Look look = l[i];
               switch (field) {
                  case "exposure" -> look.exposure = Float.parseFloat(v);
                  case "mired" -> look.mired = Float.parseFloat(v);
                  case "tint" -> look.tint = Float.parseFloat(v);
                  case "contrast" -> look.contrast = Float.parseFloat(v);
                  case "saturation" -> look.saturation = Float.parseFloat(v);
                  case "purkinje" -> look.purkinje = Float.parseFloat(v);
                  case "purkinjeHi" -> look.purkinjeHi = Float.parseFloat(v);
                  case "shadow", "highlight" -> {
                     String[] p = v.split(",");
                     float[] g = {Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2])};
                     if (field.equals("shadow")) {
                        look.shadowGain = g;
                     } else {
                        look.highlightGain = g;
                     }
                  }
                  default -> {
                  }
               }
            }
            looks = l;
            changed = true;
            Log.info("color grading: tune file " + f + " read");
         } catch (Exception e) {
            Log.warn("color grading: tune file " + f + ": " + e);
         }
      }
   }
}
