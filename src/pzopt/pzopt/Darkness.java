package pzopt;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GLCapabilities;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.rendering.RenderTarget;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureFBO;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.LightingJNI;
import zombie.iso.fboRenderChunk.FBORenderChunk;

/**
 * Candidate B of docs/plan-graphics-enhancements.md (2026-09-26): the looks players ask for most, at no frame cost.
 *
 * <ol>
 *   <li><b>Darkness floor</b> ({@code darknessFloorPct}): no square the player has seen is lit below this luminance
 *       (basements excepted unless {@code darknessFloorBasements}). Applied to the native's per-square light where
 *       LightingJNI reads it ({@code JNILighting.pzoptDarkApply}): the chunk bakes, the per-pixel lighting lattice
 *       and the character models all take the floored values, so a frame costs nothing and no extra re-bake happens
 *       (the change test compares floored values). A soft maximum, {@code (Y^4 + F^4)^(1/4)}, lifted in a moonlight
 *       tint: lights above the floor stay as they are. Unseen squares stay black (what the player may know is unchanged).</li>
 *   <li><b>Remembered places</b> ({@code memoryTint}): what the player cannot see right now (the vision cone's shadow
 *       polygons: behind walls, behind the player) is drawn desaturated, dimmed and cooler instead of darkened, with the
 *       cone's soft edge; rooms of a building that went out of sight keep a dim remembered light
 *       ({@code memoryLightPct}) and their furniture (the fade to black of seen squares is held at half), instead of
 *       going black. The tint replaces the stock vision pass's colour: the same full-screen quad reads the world colour
 *       at its own pixel after a texture barrier and discards where nothing is shadowed, so it is no dearer than
 *       stock's black blend.</li>
 *   <li><b>Colour grading</b> ({@link Grade}).</li>
 * </ol>
 * All three are off by default and apply at once from the Enhancements tab.
 */
public final class Darkness {
   private Darkness() {
   }

   /** The square-level settings; immutable, swapped whole so the lighting workers always read one consistent set. */
   public static final class Settings {
      public final float floor; // luminance floor of seen squares (0 = none)
      public final float memoryLight; // luminance floor of seen squares the native is fading out (0 = none)
      public final boolean basements;
      public final boolean holdObjects; // seen squares' fade held at 0.5: furniture of remembered rooms stays drawn
      public final float[] tint; // luminance-normalised colour of the lift
      public final int generation; // a new one per settings change: the squares' derived values and the colour caches of an older one are stale

      Settings(float floor, float memoryLight, boolean basements, float[] tint) {
         this.generation = GENERATION.incrementAndGet() & 0x0FFFFFFF;
         this.floor = floor;
         this.memoryLight = memoryLight;
         this.basements = basements;
         this.holdObjects = floor > 0F || memoryLight > 0F;
         this.tint = tint;
      }

      /** The luminance floor of one square, 0 = leave the native's light. {@code rawDark}: its fade multiplier before us. */
      public float floorFor(byte vis, int z, float rawDark) {
         if ((vis & 1) == 0 || z < 0 && !this.basements) {
            return 0F;
         }
         float f = this.floor;
         if (rawDark < 0.999F && this.memoryLight > f) {
            f = this.memoryLight;
         }
         return f;
      }

      /** The fade multiplier seen squares keep at least (object alpha = 2 x it for objects of other rooms). */
      public float minDark(byte vis, int z) {
         return this.holdObjects && (vis & 1) != 0 && (z >= 0 || this.basements) ? 0.5F : 0F;
      }
   }

   private static final java.util.concurrent.atomic.AtomicInteger GENERATION = new java.util.concurrent.atomic.AtomicInteger();
   /** Non-null while a square-level feature is on (read by every JNILighting update, possibly on a lighting worker). */
   public static volatile Settings squares = build();
   private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
   public static long applied, floored, repeated, cacheMisses; // racy counters (lighting workers), for the stats line only
   /** devDarkStats: time the per-square apply (the lighting workers add to it). */
   public static final boolean TIMING = Config.DEV_DARK_STATS;
   public static final java.util.concurrent.atomic.LongAdder applyNs = new java.util.concurrent.atomic.LongAdder();

   public static Scratch scratch() {
      return SCRATCH.get();
   }

   /**
    * Per thread: a direct-mapped cache of floored corner colours (the night's light takes few distinct values: the
    * ambient plateaus, the steps of a lamp's falloff), so a square that did change mostly costs nine lookups.
    */
   public static final class Scratch {
      private static final int BITS = 12;
      private final int[] keys = new int[1 << BITS], vals = new int[1 << BITS];
      private final float[] rgb = new float[3];
      private int generation = -1;

      public int floorAbgr(int abgr, float f, Settings s) {
         if (s.generation != this.generation) {
            java.util.Arrays.fill(this.keys, 0); // 0 never is a key (the selector byte is 1 or 2)
            this.generation = s.generation;
         }
         int key = abgr & 0xFFFFFF | (f == s.floor ? 1 : 2) << 24; // the alpha byte (always 0xFF) carries which floor
         int h = key * 0x9E3779B1 >>> 32 - BITS;
         if (this.keys[h] == key) {
            return this.vals[h];
         }
         int v = GradeMath.floorAbgr(abgr | 0xFF000000, f, s.tint, this.rgb);
         this.keys[h] = key;
         this.vals[h] = v;
         cacheMisses++;
         return v;
      }
   }

   private static Settings build() {
      if (!Overrides.enabled()) {
         return null;
      }
      float floor = Math.max(0, Math.min(50, Config.DARKNESS_FLOOR_PCT)) / 100F;
      float mem = Config.MEMORY_TINT ? Math.max(0, Math.min(50, Config.MEMORY_LIGHT_PCT)) / 100F : 0F;
      if (floor <= 0F && mem <= 0F) {
         return null;
      }
      return new Settings(floor, mem, Config.DARKNESS_FLOOR_BASEMENTS, parseTint(Config.DARKNESS_FLOOR_TINT));
   }

   static float[] parseTint(String s) {
      float[] t = {0.92F, 0.98F, 1.12F};
      try {
         String[] p = s.split(",");
         if (p.length == 3) {
            t = new float[] {Float.parseFloat(p[0].trim()), Float.parseFloat(p[1].trim()), Float.parseFloat(p[2].trim())};
         }
      } catch (NumberFormatException ignored) {
      }
      float y = GradeMath.LR * t[0] + GradeMath.LG * t[1] + GradeMath.LB * t[2];
      return new float[] {t[0] / y, t[1] / y, t[2] / y};
   }

   // ------------------------------------------------------------------------------------------------ live apply

   private static volatile boolean pending;

   /** Enhancements: a darkness / memory key changed (game thread). Applied at the next world frame. */
   static void reconfigure() {
      pending = true;
   }

   /**
    * Game thread, at the start of the world pass (no lighting batch runs): every loaded square re-derives its light
    * from the native's values it kept (or keeps them now, or gives them back when the features went off), and every
    * chunk texture bakes again.
    */
   private static void applyPending() {
      pending = false;
      Settings s = build();
      squares = s;
      IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
      int count = 0, chunks = 0;
      if (cell != null) {
         for (int p = 0; p < 4; p++) {
            IsoChunkMap cm = cell.getChunkMap(p);
            if (cm == null || cm.ignore || cm.getChunks() == null) {
               continue;
            }
            for (IsoChunk c : cm.getChunks()) {
               if (c == null) {
                  continue;
               }
               for (int z = c.minLevel; z <= c.maxLevel; z++) {
                  for (int y = 0; y < 8; y++) {
                     for (int x = 0; x < 8; x++) {
                        IsoGridSquare sq = c.getGridSquare(x, y, z);
                        if (sq == null || sq.lighting == null || !(sq.lighting[p] instanceof LightingJNI.JNILighting jl)) {
                           continue;
                        }
                        if (s != null) {
                           jl.pzoptDarkApply(s, false);
                        } else {
                           jl.pzoptDarkRestore();
                        }
                        if (PixelLight.ACTIVE) {
                           PixelLight.lightChanged(sq);
                        }
                        count++;
                     }
                  }
               }
               c.getRenderLevels(p).invalidateAll(FBORenderChunk.DIRTY_OBJECT_MODIFY);
               chunks++;
            }
         }
      }
      Log.info("darkness: settings applied (floor " + Config.DARKNESS_FLOOR_PCT + " %" + (Config.DARKNESS_FLOOR_BASEMENTS ? " incl. basements" : "")
         + ", remembered places " + (Config.MEMORY_TINT ? "on, tint " + Config.MEMORY_TINT_PCT + " %, light " + Config.MEMORY_LIGHT_PCT + " %" : "off") + "): "
         + count + " squares, " + chunks + " chunks bake again");
   }

   // ------------------------------------------------------------------------------------------------ per frame

   private static int lastFrame = -1;
   private static long frames;
   private static long lastStatsNs;
   private static boolean alternateOn = true;
   private static final FrameState[] STATES = {new FrameState(), new FrameState(), new FrameState(), new FrameState()};
   private static int stateIndex;

   /** The render-side switches of one frame, set in stream order ahead of its vision pass and composite. */
   private static final class FrameState extends TextureDraw.GenericDrawer {
      boolean grade, memory;

      @Override
      public void render() {
         Grade.renderOn = this.grade;
         memoryOn = this.memory;
      }
   }

   /** Is this frame's render-side part on? (devDarkAlternate flips it every N frames for a drift-free cost A/B.) */
   private static boolean frameOn() {
      return alternateOn;
   }

   /** Game thread, IsoWorld.render (once per frame; split screen calls it per player, the first call counts). */
   public static void frame() {
      if (!Overrides.enabled()) {
         return;
      }
      int frameNo = IsoWorld.instance == null ? 0 : IsoWorld.instance.getFrameNo();
      if (frameNo == lastFrame) {
         return;
      }
      lastFrame = frameNo;
      frames++;
      if (pending) {
         applyPending();
      }
      if (Config.DEV_DARK_ALTERNATE > 0 && frames % Config.DEV_DARK_ALTERNATE == 0) {
         alternateOn = !alternateOn;
      }
      Grade.frame();
      FrameState st = STATES[stateIndex++ & 3];
      st.grade = Grade.enabled() && frameOn();
      st.memory = memoryEnabled() && frameOn();
      SpriteRenderer.instance.drawGeneric(st);
      long now = System.nanoTime();
      if ((Config.DEV_DARK_STATS || Config.DEV_DARK_ALTERNATE > 0) && now - lastStatsNs > 10_000_000_000L) {
         lastStatsNs = now;
         Log.info(stats());
      }
   }

   /** GPU section names: with devDarkAlternate the frames with and without the render-side part are timed apart. */
   public static String section(String name) {
      if (Config.DEV_DARK_ALTERNATE <= 0) {
         return name;
      }
      return alternateOn ? name + ".on" : name + ".off";
   }

   public static String stats() {
      Settings s = squares;
      return "darkness: floor=" + (s == null ? 0 : Math.round(s.floor * 100)) + "% memoryLight=" + (s == null ? 0 : Math.round(s.memoryLight * 100))
         + "% apply us total=" + applyNs.sum() / 1000 + " per frame=" + String.format(java.util.Locale.ROOT, "%.2f", applyNs.sum() / 1000.0 / Math.max(1L, frames)) + " squares derived=" + applied + " fade pre-passes=" + prePasses + " floored=" + floored + " repeated=" + repeated + " colour misses=" + cacheMisses + " | memory passes=" + memoryPasses + " (" + memoryState + ") | " + Grade.stats();
   }

   // ------------------------------------------------------------------------------------------------ memory tint pass

   static volatile boolean memoryOn; // render thread, this frame
   private static int[] programs = new int[3];
   private static int[][] uniforms = new int[3][];
   private static boolean memoryBroken;
   private static String memoryState = "off";
   private static long memoryPasses;
   private static int barrierKind; // 0 unknown, 1 GL 4.5 / ARB, 2 NV, -1 none

   public static boolean memoryEnabled() {
      return Overrides.enabled() && Config.MEMORY_TINT && !memoryBroken && !HdrMac.MAC;
   }

   private static final String VERT = String.join("\n",
      "#version 330",
      "layout (location = 0) in vec3 position;",
      "layout (location = 1) in vec2 texcoord;",
      "out vec2 uv;",
      "void main() {",
      "  gl_Position = vec4(2.0 * (position.xy - vec2(0.5, 0.5)), 1.0, 1.0);",
      "  uv = texcoord;",
      "}",
      "");

   /** The ring of the soft edge: the mean of the 5x5 sums on eight points at radius pzMemR around a vision texel. */
   private static final String RING = String.join("\n",
      "  // a wide ramp that starts at the shadow's edge and rises inwards (the depth test clips everything outside the",
      "  // shadow polygons, so the ramp must lie inside them)",
      "  ivec2 lim = ivec2(texSize) - 1;",
      "  float ring = texelFetch(reduced, clamp(pixel, ivec2(0), lim), 0).r;",
      "  for (int k = 0; k < 8; k++) {",
      "    vec2 d = vec2(cos(float(k) * 0.785398), sin(float(k) * 0.785398)) * pzMemR;",
      "    ring += texelFetch(reduced, clamp(pixel + ivec2(round(d)), ivec2(0), lim), 0).r;",
      "  }",
      "  float soft = smoothstep(0.5, 1.0, ring / (9.0 * BLUR_SAMPLES));");

   /**
    * The vision pass: mode 0 = the stock 25-tap alpha (no visBlurReduce sums), 1 = the soft ring per pixel from the sums,
    * 2 = the soft, time-faded alpha of the pre-pass (one bilinear fetch per pixel). Then the remembered look.
    */
   private static String frag(int mode) {
      return String.join("\n",
         "#version 330",
         "#define BLUR_RANGE 2",
         "in vec2 uv;",
         "uniform vec2 screenSize;",
         "uniform vec2 displayOrigin;",
         "uniform vec2 displaySize;",
         "uniform vec2 texSize;",
         "uniform vec2 TextureSize;",
         "uniform sampler2D tex;",
         "uniform sampler2D depth;",
         "uniform sampler2D reduced; // mode 1: the 5x5 sums; mode 2: the faded soft alpha",
         "uniform sampler2D world;",
         "uniform vec4 pzMem; // x strength, y desaturation, z dim, w edge softness (0 = stock edge, 1 = the soft one)",
         "uniform vec3 pzMemTint;",
         "uniform float pzMemR; // ring radius in vision texels",
         "const float BLUR_SAMPLES = float((BLUR_RANGE * 2 + 1) * (BLUR_RANGE * 2 + 1));",
         "out vec4 colour;",
         "void main() {",
         "  vec2 screenUV = (gl_FragCoord.xy - displayOrigin.xy) / displaySize;",
         "  ivec2 pixel = ivec2(gl_FragCoord.xy / screenSize * texSize);",
         mode == 2
            ? "  float a = textureLod(reduced, gl_FragCoord.xy / screenSize, 0.0).r * pzMem.x;"
            : String.join("\n",
               "  float sum = 0.0;",
               mode == 1
                  ? "  sum = texelFetch(reduced, clamp(pixel, ivec2(0), ivec2(texSize) - 1), 0).r;"
                  : String.join("\n",
                     "  vec2 maxUV = texSize / TextureSize;",
                     "  vec2 pixelStep = vec2(1.0) / TextureSize;",
                     "  for (int y = -BLUR_RANGE; y <= BLUR_RANGE; y++) {",
                     "    for (int x = -BLUR_RANGE; x <= BLUR_RANGE; x++) {",
                     "      sum += textureLod(tex, min(vec2(pixel + ivec2(x, y)) * pixelStep, maxUV), 0.0).a;",
                     "    }",
                     "  }"),
               "  float hard = clamp((sum - BLUR_SAMPLES * 0.5) * 2.0 / BLUR_SAMPLES, 0.0, 1.0); // the stock pass's alpha",
               mode == 1 ? RING : "  float soft = hard;",
               "  float a = mix(hard, soft, pzMem.w) * pzMem.x;"),
         "  if (a <= 0.002) discard; // nothing shadowed: nothing written (stock blends a zero)",
         "  gl_FragDepth = textureLod(depth, screenUV, 0.0).r;",
         "  vec3 c = texelFetch(world, ivec2(gl_FragCoord.xy), 0).rgb;",
         "  float y = dot(c, vec3(0.2126, 0.7152, 0.0722));",
         "  vec3 m = mix(c, vec3(y), pzMem.y) * pzMem.z * pzMemTint;",
         "  colour = vec4(m, a);",
         "}",
         "");
   }

   /**
    * The pre-pass (mode 2), one fragment per vision texel: the soft ring alpha, eased in over memoryFadeMs from the
    * last frame's value re-projected by the camera's move (what just went out of sight turns grey over a quarter
    * second, like a memory settling; what comes into sight is shown at once: falls are never delayed).
    */
   private static final String PRE_FRAG = String.join("\n",
      "#version 330",
      "#define BLUR_RANGE 2",
      "in vec2 uv;",
      "uniform vec2 texSize; // the vision texture (the sums)",
      "uniform vec2 outSize; // this pass's target, 1/memoryFadeScale of it",
      "uniform sampler2D reduced;",
      "uniform sampler2D prev;",
      "uniform float pzMemR;",
      "uniform vec4 fade; // x blend factor this frame, y history valid, zw where this texel was last frame (target texels)",
      "const float BLUR_SAMPLES = float((BLUR_RANGE * 2 + 1) * (BLUR_RANGE * 2 + 1));",
      "out vec4 o;",
      "void main() {",
      "  ivec2 pixel = ivec2(gl_FragCoord.xy * texSize / outSize);",
      RING,
      "  float a = soft;",
      "  vec2 q = gl_FragCoord.xy + fade.zw;",
      "  if (fade.y > 0.5 && all(greaterThanEqual(q, vec2(0.0))) && all(lessThan(q, outSize))) {",
      "    float h = textureLod(prev, q / outSize, 0.0).r;",
      "    a = soft <= h ? soft : mix(h, soft, fade.x);",
      "  }",
      "  o = vec4(a, 0.0, 0.0, 1.0);",
      "}",
      "");

   // pre-pass state (render thread)
   private static int preProgram;
   private static int[] preU;
   private static final int[] histTex = new int[2];
   private static int histFbo, histW, histH, histCur;
   private static boolean histValid;
   private static float lastOffX, lastOffY, lastZoom;
   private static long lastPreNs;
   static long prePasses;

   /** Render thread: the faded alpha into histTex[histCur]; false when it could not run (mode 1 then). */
   private static boolean prePass(Texture reducedTex, int vw, int vh, int playerIndex, float originX, float originY, float screenW, float screenH) {
      int q = Math.max(1, Config.MEMORY_FADE_SCALE);
      int w = Math.max(1, (vw + q - 1) / q), h = Math.max(1, (vh + q - 1) / q);
      if (preProgram == 0) {
         preProgram = AmbientOcclusion.link(VERT, PRE_FRAG);
         if (preProgram == 0) {
            Log.warn("remembered places: fade pre-pass did not link; no fade");
            preProgram = -1;
            return false;
         }
         String[] names = {"texSize", "reduced", "prev", "pzMemR", "fade", "outSize"};
         preU = new int[names.length];
         for (int i = 0; i < names.length; i++) {
            preU[i] = GL20.glGetUniformLocation(preProgram, names[i]);
         }
      }
      if (preProgram < 0) {
         return false;
      }
      if (histFbo == 0 || w != histW || h != histH) {
         if (histFbo == 0) {
            histFbo = org.lwjgl.opengl.GL30.glGenFramebuffers();
         }
         for (int i = 0; i < 2; i++) {
            if (histTex[i] == 0) {
               histTex[i] = GL11.glGenTextures();
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + 5);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, histTex[i]);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL30.GL_R16F, w, h, 0, GL11.GL_RED, GL11.GL_FLOAT, (java.nio.FloatBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
         }
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         histW = w;
         histH = h;
         histValid = false;
      }
      zombie.iso.PlayerCamera cam = SpriteRenderer.instance.getRenderingPlayerCamera(playerIndex);
      float offX = cam == null ? 0F : cam.getOffX(), offY = cam == null ? 0F : cam.getOffY(), zoom = cam == null ? 1F : cam.zoom;
      int osW = cam == null || cam.offscreenWidth <= 0 ? Core.getInstance().getOffscreenWidth(playerIndex) : cam.offscreenWidth;
      int osH = cam == null || cam.offscreenHeight <= 0 ? Core.getInstance().getOffscreenHeight(playerIndex) : cam.offscreenHeight;
      long now = System.nanoTime();
      float dt = lastPreNs == 0L ? 1F : (now - lastPreNs) / 1e9F;
      lastPreNs = now;
      boolean valid = histValid && zoom == lastZoom && dt < 0.5F;
      // a point on screen at offscreen px p now was at p + (off - lastOff) last frame (y down); GL rows run up
      float sx = (offX - lastOffX) * w / Math.max(1F, osW), sy = -(offY - lastOffY) * h / Math.max(1F, osH);
      lastOffX = offX;
      lastOffY = offY;
      lastZoom = zoom;
      float k = 1F - (float)Math.exp(-dt * 1000F / Math.max(1, Config.MEMORY_FADE_MS));
      int prev = histCur;
      histCur ^= 1;
      int prevFbo = TextureFBO.lastID;
      org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, histFbo);
      org.lwjgl.opengl.GL30.glFramebufferTexture2D(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, histTex[histCur], 0);
      GL11.glDisable(GL11.GL_BLEND);
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glViewport(0, 0, w, h);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + 3);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, reducedTex.getID());
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + 5);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, histTex[prev]);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL20.glUseProgram(preProgram);
      GL20.glUniform2f(preU[0], vw, vh);
      GL20.glUniform2f(preU[5], w, h);
      GL20.glUniform1i(preU[1], 3);
      GL20.glUniform1i(preU[2], 5);
      GL20.glUniform1f(preU[3], MEMORY_RING);
      GL20.glUniform4f(preU[4], k, valid ? 1F : 0F, sx, sy);
      RenderTarget.DrawFullScreenQuad();
      org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, prevFbo);
      GL11.glViewport((int)originX, (int)originY, (int)screenW, (int)screenH);
      GL11.glEnable(GL11.GL_BLEND);
      GL11.glEnable(GL11.GL_DEPTH_TEST);
      histValid = true;
      prePasses++;
      return true;
   }

   /**
    * Render thread, VisibilityPolygon2's screen pass in place of the stock one (after VisBlur.reduce). Draws the
    * remembered look where the vision cone's shadow is and returns true, or false for the stock pass (off this frame,
    * no texture barrier, a failure). Leaves blending / depth state as it found it (the caller restores the rest).
    */
   public static boolean memoryPass(Texture blurTex, Texture blurDepthTex, boolean useReduced, float screenW, float screenH, float originX,
         float originY, float displayW, float displayH, int playerIndex) {
      if (!memoryOn || !memoryEnabled() || blurTex == null || blurDepthTex == null) {
         return false;
      }
      try {
         if (barrierKind == 0) {
            GLCapabilities caps = GL.getCapabilities();
            barrierKind = caps.OpenGL45 || caps.GL_ARB_texture_barrier ? 1 : caps.GL_NV_texture_barrier ? 2 : -1;
            if (barrierKind < 0) {
               memoryBroken = true;
               memoryState = "unsupported (no texture barrier)";
               Log.warn("remembered places: " + memoryState + "; the stock vision pass stays");
               return false;
            }
         }
         TextureFBO world = Core.getInstance().getOffscreenBuffer();
         if (world == null || !(world.getTexture() instanceof Texture wt)) {
            return false;
         }
         Texture reducedTex = useReduced ? VisBlur.sums() : null;
         int v = reducedTex == null ? 0 : Config.MEMORY_FADE_MS > 0 && prePass(reducedTex, blurTex.getWidth(), blurTex.getHeight(), playerIndex, originX, originY, screenW, screenH) ? 2 : 1;
         if (programs[v] == 0) {
            programs[v] = AmbientOcclusion.link(VERT, frag(v));
            if (programs[v] == 0) {
               memoryBroken = true;
               memoryState = "shader failed";
               Log.warn("remembered places: shader did not link; the stock vision pass stays");
               return false;
            }
            String[] names = {"screenSize", "displayOrigin", "displaySize", "texSize", "TextureSize", "tex", "depth", "reduced", "world", "pzMem", "pzMemTint", "pzMemR"};
            int[] u = new int[names.length];
            for (int i = 0; i < names.length; i++) {
               u[i] = GL20.glGetUniformLocation(programs[v], names[i]);
            }
            uniforms[v] = u;
            Log.info("remembered places: vision pass ready (" + (v == 2 ? "faded pre-pass, " + Config.MEMORY_FADE_MS + " ms" : v == 1 ? "reduced sums" : "25-tap") + ")");
         }
         int[] u = uniforms[v];
         if (barrierKind == 1) {
            GL45.glTextureBarrier();
         } else {
            org.lwjgl.opengl.NVTextureBarrier.glTextureBarrierNV();
         }
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + 1);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, blurTex.getID());
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + 2);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, blurDepthTex.getID());
         if (v == 2) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + 3);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, histTex[histCur]);
         } else if (reducedTex != null) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + 3);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, reducedTex.getID());
         }
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + 4);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, wt.getID());
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL20.glUseProgram(programs[v]);
         GL20.glUniform2f(u[0], screenW, screenH);
         GL20.glUniform2f(u[1], originX, originY);
         GL20.glUniform2f(u[2], displayW, displayH);
         GL20.glUniform2f(u[3], blurTex.getWidth(), blurTex.getHeight());
         GL20.glUniform2f(u[4], blurTex.getWidthHW(), blurTex.getHeightHW());
         GL20.glUniform1i(u[5], 1);
         GL20.glUniform1i(u[6], 2);
         GL20.glUniform1i(u[7], 3);
         GL20.glUniform1i(u[8], 4);
         float strength = Math.max(0, Math.min(100, Config.MEMORY_TINT_PCT)) / 100F;
         GL20.glUniform4f(u[9], strength, MEMORY_DESATURATION, MEMORY_DIM, MEMORY_SOFT);
         GL20.glUniform3f(u[10], MEMORY_TINT[0], MEMORY_TINT[1], MEMORY_TINT[2]);
         GL20.glUniform1f(u[11], MEMORY_RING);
         RenderTarget.DrawFullScreenQuad();
         GL20.glUseProgram(0);
         zombie.core.ShaderHelper.forgetCurrentlyBound();
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
         memoryPasses++;
         memoryState = "on";
         return true;
      } catch (Throwable t) {
         memoryBroken = true;
         memoryState = "failed: " + t;
         Log.warn("remembered places: vision pass failed, stock from now on: " + t);
         GL20.glUseProgram(0);
         zombie.core.ShaderHelper.forgetCurrentlyBound();
         return false;
      }
   }

   /** The remembered look (tunable by -D for dev rigs). */
   static final float MEMORY_DESATURATION = Config.DEV_MEMORY_DESAT_PCT / 100F;
   static final float MEMORY_DIM = Config.DEV_MEMORY_DIM_PCT / 100F;
   static final float MEMORY_SOFT = Config.DEV_MEMORY_SOFT_PCT / 100F;
   static final float MEMORY_RING = Config.DEV_MEMORY_RING_TEXELS;
   static final float[] MEMORY_TINT = parseTint(System.getProperty("pzopt.devMemoryTint", "0.9,0.97,1.15"));
}
