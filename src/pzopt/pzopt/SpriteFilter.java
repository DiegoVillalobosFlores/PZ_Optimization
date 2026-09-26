package pzopt;

import java.util.HashMap;
import org.lwjgl.opengl.GL11;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;

/**
 * Candidate A of docs/plan-graphics-enhancements.md (2026-09-26): sprite filtering that ends the blur.
 *
 * <p>The static world reaches the screen through the chunk composite: every chunk-level texture (baked at one texel per
 * world pixel) is drawn at 1 / zoom. Stock samples it with nearest magnification (linear at zoom 0.75 only, the soft
 * zoom level players call "blurry") and trilinear minification (zoomed out: a blend of the sharp base level and a
 * box-filtered half-size level, the soft, "forced AA" look while driving). {@code spriteFilter=sharp} replaces the one
 * texture fetch of the composite:
 * <ul>
 *   <li>magnified (zoom &lt; 1): texel-aware anti-aliased point sampling (d7samurai's "antialiased point sampling",
 *       Cole Cecil's sharp bilinear): each texel is a hard square, only the one screen pixel a texel edge crosses is
 *       blended by its coverage, so edges stay crisp at any zoom and do not crawl while the camera moves by fractions of
 *       a pixel ({@code spriteFilterSharpnessPct} narrows the blended band);</li>
 *   <li>minified (zoom &gt; 1): shader supersampling of the mip chain (Ben Golus, "Sharper mipmapping using shader based
 *       supersampling"): four rotated-grid samples one mip level sharper than the footprint, which keeps high-contrast
 *       tile art (fences, window frames, road lines) sharp without the shimmer of plain nearest
 *       ({@code spriteFilterMin}: rgss4, rgss2, bias, trilinear);</li>
 *   <li>1:1 (zoom 1): the stock program, unchanged.</li>
 * </ul>
 * {@code spriteFilter=nearest} is plain point sampling at every magnified zoom (0.75 included), the crisp look without
 * the anti-aliased edge.
 *
 * <p>Cost. The composite is the largest world pass, so nothing is added where it is not needed: the zoom decides once a
 * frame which program the composite runs (the game's own StartShader of the chunk program is re-pointed, no extra GL
 * call; {@link #remap}), each variant is compiled with only its own path (no branches, no extra registers), zoom 1 and
 * "stock" run the stock program itself, and the linear magnification filter the texel-aware path needs rides the
 * glTexParameteri pair the game already issues on every chunk-texture bind ({@link #magFilter}). With per-pixel
 * lighting (which owns the composite programs) the fetch is compiled into its programs at launch and picks its path
 * from the texel footprint.
 */
public final class SpriteFilter {
   private SpriteFilter() {
   }

   static final int NONE = 0, MAGNIFY = 1, MINIFY = 2, DYNAMIC = 3, INTEGER = 4;

   // ---------------------------------------------------------------------------------------------- render thread state

   /** Render thread: between the chunk program's start and the next program start (the chunk quad's texture bind). */
   public static boolean inComposite;
   /** Render thread: the magnification filter chunk textures get in the composite (0 = the game's own). */
   private static int compositeMag;
   /** Render thread: the game's chunk composite program and the variant it is re-pointed to this frame (0 = none). */
   private static int stockProgram, remapProgram;
   private static final HashMap<String, zombie.viewCone.ChunkRenderShader> variants = new HashMap<>();
   private static final HashMap<String, Boolean> failedVariants = new HashMap<>();
   private static String compilingDefines = ""; // read by patchShader while a variant compiles
   private static String stockFrag, stockVert; // the chunk composite's sources as the game compiles them (after SSR's patch)
   // the per-frame sprite path: tileWithDepth / opaqueWithDepth (the tiles drawn per frame, outside the chunk textures)
   private static final String[] TILE_NAMES = {"tileWithDepth", "opaqueWithDepth"};
   private static final String[] tileFrag = new String[2], tileVert = new String[2];
   private static final int[] tileStock = new int[2], tileVariant = new int[2];
   private static final boolean[] tileFailed = new boolean[2];
   private static boolean tileOn;
   private static int worldFbo;
   private static int compilingTile = -1; // which tile program patchShader is compiling a variant of
   private static volatile boolean dynamicCompiled; // pixel light's composite programs carry the fetch (launch setting)
   private static boolean warned;

   // ------------------------------------------------------------------------------------------------ game thread state

   private static int frames;
   private static boolean alternateOn = true;
   private static final Frame[] ring = {new Frame(), new Frame(), new Frame(), new Frame()};
   private static int ringAt;
   private static long lastStatsNs;
   private static int statsFrames, statsMag, statsMin, statsOne;

   private static boolean supported() {
      return Overrides.enabled() && !System.getProperty("os.name", "").contains("OS X") && !Core.getInstance().getUseOpenGL21();
   }

   // this frame's settings (game thread): the configured ones, or the dev cycle's entry
   private static String fMin = "rgssa2";
   private static boolean fSkip;
   private static int fLodBiasPct; // the tap level: floor(log2(footprint) - bias)
   private static boolean fSharpMips;
   private static boolean fLinear;
   private static boolean fSmooth;
   private static int fSharpPct;
   private static String cycleName;
   private static String[] cycle, shotModes;
   private static int shotModeAt = -1;
   private static boolean fromShotModes;

   /**
    * devSpriteFilterCycle: every devSpriteFilterAlternate frames the next entry of the list ("stock", "nearest", or a
    * minification mode with "+skip" for the empty-area probe, e.g. stock,rgss4,rgss4+skip,rgss2,bias); the composite's
    * GPU section is named after the entry, so every variant is timed on the same route in one run.
    */
   private static void pickFrame() {
      fMin = Config.SPRITE_FILTER_MIN;
      fSkip = Config.SPRITE_FILTER_SKIP_EMPTY;
      fLodBiasPct = Config.SPRITE_FILTER_LOD_BIAS_PCT;
      fSharpMips = Config.SPRITE_FILTER_SHARP_MIPS;
      fLinear = Config.SPRITE_FILTER_LINEAR_LIGHT;
      fSmooth = "smooth".equals(Config.SPRITE_FILTER_KERNEL);
      fSharpPct = Config.SPRITE_FILTER_SHARPNESS_PCT;
      cycleName = null;
      fromShotModes = false;
      String e = null;
      if (!Config.DEV_SPRITE_FILTER_SHOT_MODES.isEmpty()) {
         // shot rig: one mode after the other through the camera hold (four slots clear of its two screenshots), the switches logged with their time
         // for harness/spritefilter/modes.py to pick a settled captured frame per mode
         if (shotModes == null) {
            shotModes = Config.DEV_SPRITE_FILTER_SHOT_MODES.split(",");
         }
         // slots clear of the harness's two screenshots (2 s and 4 s into the hold, each stalls ~0.9 s): four modes
         int i = 0;
         float held = Harness.shotHeldS;
         if (held >= 0F) {
            i = held < 0.9F ? 0 : held < 2.0F ? 1 : held < 4.0F ? 2 : held < 5.95F ? 3 : 4;
            if (held >= 1.8F && held < 2.9F || held >= 3.9F && held < 5.0F) {
               i = shotModeAt; // inside a screenshot stall: keep the mode
            }
         }
         i = Math.min(i, shotModes.length);
         if (i < shotModes.length) {
            e = shotModes[i].trim();
            fromShotModes = true;
            if (i != shotModeAt) {
               shotModeAt = i;
               Log.info("sprite filter: shot mode " + e + " from epoch_ms=" + System.currentTimeMillis());
            }
         } else if (shotModeAt != i) {
            shotModeAt = i;
            Log.info("sprite filter: shot modes done at epoch_ms=" + System.currentTimeMillis());
         }
      } else if (Config.DEV_SPRITE_FILTER_ALTERNATE > 0 && !Config.DEV_SPRITE_FILTER_CYCLE.isEmpty()) {
         if (cycle == null) {
            cycle = Config.DEV_SPRITE_FILTER_CYCLE.split(",");
         }
         e = cycle[(frames / Config.DEV_SPRITE_FILTER_ALTERNATE) % cycle.length].trim();
      }
      if (e != null) {
         Entry x = entries.get(e);
         if (x == null) {
            entries.put(e, x = Entry.parse(e));
         }
         cycleName = x.name;
         fMin = x.min;
         fSkip = x.skip;
         fLodBiasPct = x.lodBiasPct;
         fSharpMips = x.sharpMips;
         fLinear = x.linear;
         fSmooth = x.smooth;
         fSharpPct = x.sharpPct;
      }
   }

   private static final HashMap<String, Entry> entries = new HashMap<>(); // game thread

   /** A dev cycle / shot-mode entry: mode [@lod bias %] [+w<sharpness %>] [+smooth] [+skip] [+box] [+lin]. */
   private static final class Entry {
      String name, min;
      boolean skip, sharpMips, linear, smooth;
      int lodBiasPct, sharpPct;

      static Entry parse(String e) {
         Entry x = new Entry();
         x.name = e.replace('+', '_').replace("@", "_b");
         x.min = Config.SPRITE_FILTER_MIN;
         x.skip = Config.SPRITE_FILTER_SKIP_EMPTY;
         x.lodBiasPct = Config.SPRITE_FILTER_LOD_BIAS_PCT;
         x.sharpMips = Config.SPRITE_FILTER_SHARP_MIPS;
         x.linear = Config.SPRITE_FILTER_LINEAR_LIGHT;
         x.smooth = "smooth".equals(Config.SPRITE_FILTER_KERNEL);
         x.sharpPct = Config.SPRITE_FILTER_SHARPNESS_PCT;
         int w = e.indexOf("+w"); // +w200: the magnification ramp's sharpness in %
         if (w >= 0) {
            int end = w + 2;
            while (end < e.length() && Character.isDigit(e.charAt(end))) end++;
            x.sharpPct = Integer.parseInt(e.substring(w + 2, end));
            e = e.substring(0, w) + e.substring(end);
         }
         if (e.contains("+smooth")) { // the smoothstep ramp
            x.smooth = true;
            e = e.replace("+smooth", "");
         }
         if (e.endsWith("+lin")) { // the taps averaged in linear light
            x.linear = true;
            e = e.substring(0, e.length() - 4);
         }
         if (e.endsWith("+box")) { // the box level 1 (no sharp-mip pass)
            x.sharpMips = false;
            e = e.substring(0, e.length() - 4);
         }
         x.skip = e.endsWith("+skip");
         x.min = x.skip ? e.substring(0, e.length() - 5) : e;
         int at = x.min.indexOf('@'); // rgss4@50: the level bias in % of a level
         if (at >= 0) {
            x.lodBiasPct = Integer.parseInt(x.min.substring(at + 1));
            x.min = x.min.substring(0, at);
         }
         return x;
      }
   }

   /** The mode in force this frame (the dev alternation turns it to stock every other period). */
   private static String mode() {
      String m = Config.SPRITE_FILTER;
      if (cycleName != null) {
         return "stock".equals(fMin) || "nearest".equals(fMin) ? fMin : "sharp";
      }
      if (Config.DEV_SPRITE_FILTER_ALTERNATE > 0 && !alternateOn) {
         return "stock";
      }
      if (Config.DEV_SPRITE_FILTER_SHOT_AB && Harness.shotHeldS < 3f) {
         return "stock"; // shot rig A/B: shot-game.png stock, shot2-game.png with the filter (the camera held still)
      }
      return m;
   }

   /** GPU section name for the composite (devSpriteFilterAlternate splits it into .on / .off). */
   public static String section(String name) {
      if (Config.DEV_SPRITE_FILTER_ALTERNATE <= 0) {
         return name;
      }
      if (cycleName != null) {
         return name + "." + cycleName;
      }
      return alternateOn ? name + ".on" : name + ".off";
   }

   /**
    * Game thread, ahead of the chunk composite: this frame's regime from the zoom and the render scale, queued to the
    * render thread in stream order.
    */
   public static void beforeComposite(int playerIndex) {
      if (Config.DEV_SPRITE_FILTER_ALTERNATE > 0 && ++frames % Config.DEV_SPRITE_FILTER_ALTERNATE == 0) {
         alternateOn = !alternateOn;
      }
      pickFrame();
      String m = mode();
      boolean sharp = "sharp".equals(m);
      boolean nearest = "nearest".equals(m);
      if (!supported() || !(sharp || nearest)) {
         if (!warned && (sharp || nearest) && Overrides.enabled()) {
            warned = true;
            Log.info("sprite filter: " + m + " needs GLSL 1.50 (not on this OpenGL context): stock filtering");
         }
         queue(NONE, 0, "");
         return;
      }
      float texelsPerPixel = Core.getInstance().getZoom(playerIndex) / RenderScale.scale();
      int regime = texelsPerPixel < 0.999F ? MAGNIFY : texelsPerPixel > 1.001F ? MINIFY : NONE;
      if (regime == MAGNIFY && !Config.SPRITE_FILTER_INTEGER_AA) {
         // 2x, 4x (zoom 0.5, 0.25): every texel is already an even block of whole pixels, point sampling is exact
         float k = 1.0F / texelsPerPixel;
         if (Math.abs(k - Math.round(k)) < 0.002F * k) {
            regime = INTEGER;
         }
      }
      statsFrames++;
      if (regime == MAGNIFY || regime == INTEGER) statsMag++;
      else if (regime == MINIFY) statsMin++;
      else statsOne++;
      long now = System.nanoTime();
      if (Config.DEV_SPRITE_FILTER_STATS && now - lastStatsNs > 10_000_000_000L) {
         lastStatsNs = now;
         Log.info(String.format(java.util.Locale.ROOT, "sprite filter: mode=%s min=%s sharp=%d%% skip=%b frames=%d magnify=%d minify=%d one=%d variants=%d sharp-mip passes=%d",
               m, fMin, Config.SPRITE_FILTER_SHARPNESS_PCT, fSkip, statsFrames, statsMag, statsMin, statsOne, variants.size(), SpriteMips.passes));
         statsFrames = statsMag = statsMin = statsOne = 0;
      }
      if (nearest) {
         // point sampling at every magnified zoom (stock: linear at 0.75); minified stays stock trilinear
         queue(NONE, regime == MAGNIFY ? GL11.GL_NEAREST : 0, "");
         return;
      }
      if (regime == NONE || regime == INTEGER) {
         queue(NONE, 0, ""); // 1:1 and whole multiples: the stock program (nearest) is already exact
         return;
      }
      queue(regime, GL11.GL_LINEAR, defines(regime));
   }

   /**
    * Game thread (a bake): the highest mip level the composite can read with the configured filter at the widest zoom
    * over the render scale, or {@code stock} when the stock sampling decides. rgss4 and rgss2 read the level
    * floor(log2(footprint) - spriteFilterLodBiasPct / 100) (level 1 at the widest stock zoom, 2.5), bias a trilinear
    * lookup half a level sharp; the empty-area probe reads one level coarser (so 2 levels instead of stock's 3).
    */
   public static int mipLevelsNeeded(int stock) {
      if (!"sharp".equals(Config.SPRITE_FILTER) || !Config.SPRITE_FILTER_MIP_TRIM || !Overrides.enabled() || !supported()) {
         return stock;
      }
      double l2 = Math.log(Math.max(1.0F, Core.getInstance().getMaxZoom()) / RenderScale.scale()) / Math.log(2.0);
      int n;
      switch (minCode(Config.SPRITE_FILTER_MIN)) {
         case 4: case 2: case 5: case 6: case 7: n = (int)Math.max(0, Math.floor(l2 - Config.SPRITE_FILTER_LOD_BIAS_PCT / 100.0 + 1e-3)); break;
         case 1: n = (int)Math.ceil(l2 - 0.5 - 1e-3); break;
         default: return stock;
      }
      int mc = minCode(Config.SPRITE_FILTER_MIN);
      if (Config.SPRITE_FILTER_SKIP_EMPTY && (mc == 2 || mc == 4 || mc == 6 || mc == 7)) {
         n += 1; // the empty-area probe reads a level above the taps
      }
      return Math.max(0, n);
   }

   // ---------------------------------------------------------------------------------- sharp level-1 mipmaps (bakes)

   private static zombie.core.textures.Texture bakeTex;
   private static zombie.core.textures.Texture[] sharpPending = new zombie.core.textures.Texture[16];
   private static int sharpPendingN;

   /** Game thread, a chunk texture starts baking (dirty, rendered): remember it for its level-1 rewrite. */
   public static void bakeBegin(zombie.core.textures.Texture tex) {
      // only while zoomed out to 2x and more: level 1 is read from there (a texture kept from an earlier zoom keeps
      // the box level until its next bake)
      // the configured mode (the shot rig's stock half must not keep the load-time bakes box-filtered); a dev cycle's
      // stock phase does skip it (timing)
      boolean sharpNow = cycleName != null && !fromShotModes ? "sharp".equals(mode()) : "sharp".equals(Config.SPRITE_FILTER);
      bakeTex = tex != null && fSharpMips && sharpNow
            && Core.getInstance().getZoom(zombie.iso.IsoCamera.frameState.playerIndex) / RenderScale.scale() >= 1.95F
            && Config.BAKE_MIP_LEVELS > 0 && supported() && zombie.debug.DebugOptions.instance.fboRenderChunk.mipMaps.getValue() ? tex : null;
   }

   /** Game thread, after endRenderChunkLevel: the texture is finished once the manager has let go of it. */
   public static void bakeEnd() {
      zombie.core.textures.Texture t = bakeTex;
      if (t == null || zombie.iso.fboRenderChunk.FBORenderChunkManager.instance.renderChunk != null) {
         return;
      }
      bakeTex = null;
      for (int i = 0; i < sharpPendingN; i++) {
         if (sharpPending[i] == t) {
            return;
         }
      }
      if (sharpPendingN == sharpPending.length) {
         sharpPending = java.util.Arrays.copyOf(sharpPending, sharpPendingN * 2);
      }
      sharpPending[sharpPendingN++] = t;
   }

   /** The #defines of a variant: its regime and the live settings it is compiled with. */
   private static String defines(int regime) {
      return definesFor(regime, minCode(fMin), fSkip, fLodBiasPct, fSmooth, fSharpPct, fLinear, Config.SPRITE_FILTER_INTEGER_AA);
   }

   // the last few define strings by their packed inputs (built once, not every frame)
   private static final long[] defKeys = {-1L, -1L, -1L, -1L};
   private static final String[] defStrings = new String[4];
   private static int defNext;

   private static synchronized String definesFor(int regime, int min, boolean skip, int lodBiasPct, boolean smooth, int sharpPct, boolean linear, boolean integerAa) {
      int pct = Math.max(25, Math.min(800, sharpPct));
      long key = regime | (long)min << 3 | (skip ? 1L : 0L) << 7 | (long)Math.max(0, Math.min(100, lodBiasPct)) << 8 | (smooth ? 1L : 0L) << 15
            | (long)pct << 16 | (linear ? 1L : 0L) << 26 | (integerAa ? 1L : 0L) << 27;
      for (int i = 0; i < 4; i++) {
         if (defKeys[i] == key) {
            return defStrings[i];
         }
      }
      StringBuilder d = new StringBuilder();
      d.append("#define PZSF_REGIME ").append(regime).append('\n');
      d.append("#define PZSF_MIN ").append(min).append('\n');
      d.append("#define PZSF_SKIP ").append(skip ? 1 : 0).append('\n');
      d.append(String.format(java.util.Locale.ROOT, "#define PZSF_LOD_BIAS %.3f\n", lodBiasPct / 100.0));
      d.append("#define PZSF_KERNEL ").append(smooth ? 1 : 0).append('\n');
      d.append("#define PZSF_LINEAR ").append(linear ? 1 : 0).append('\n');
      d.append("#define PZSF_INTEGER_AA ").append(integerAa ? 1 : 0).append('\n');
      d.append(String.format(java.util.Locale.ROOT, "#define PZSF_WIDTH %.6f\n", 100.0 / pct));
      String r = d.toString();
      int slot = defNext++ & 3;
      defKeys[slot] = key;
      defStrings[slot] = r;
      return r;
   }

   /** The configured settings' variants (not a dev cycle's). */
   private static String configDefines(int regime) {
      return definesFor(regime, minCode(Config.SPRITE_FILTER_MIN), Config.SPRITE_FILTER_SKIP_EMPTY, Config.SPRITE_FILTER_LOD_BIAS_PCT,
            "smooth".equals(Config.SPRITE_FILTER_KERNEL), Config.SPRITE_FILTER_SHARPNESS_PCT, Config.SPRITE_FILTER_LINEAR_LIGHT, Config.SPRITE_FILTER_INTEGER_AA);
   }

   private static String precompiled = "";
   private static String precompiledMag, precompiledMin;
   private static boolean precompiledSprites;

   /**
    * Render thread, after every swap: compile the variants the configured settings need as soon as they change (at boot
    * in the menu, or when the player presses Apply), so the first zoom in or out never waits for a compile (a program
    * costs ~0.8 s here: a hitch in the world, one slow menu frame here).
    */
   public static void afterSwap() {
      if (!"sharp".equals(Config.SPRITE_FILTER) || stockFrag == null && !dynamicCompiled || failedAll) {
         return;
      }
      String mag = configDefines(MAGNIFY), min = configDefines(MINIFY); // cached strings: no garbage per frame
      boolean sprites = Config.SPRITE_FILTER_SPRITES;
      if (mag == precompiledMag && min == precompiledMin && sprites == precompiledSprites) {
         return;
      }
      precompiledMag = mag;
      precompiledMin = min;
      precompiledSprites = sprites;
      String key = mag + min + sprites;
      if (key.equals(precompiled)) {
         return;
      }
      try {
         if (!supported() || zombie.core.SceneShaderStore.chunkRenderShader == null || !zombie.core.SceneShaderStore.chunkRenderShader.isCompiled()) {
            return;
         }
         precompiled = key;
         long t0 = System.nanoTime();
         if (!PixelLight.chunkShaderPatched) {
            variant(mag);
            variant(min);
            // dev rigs: every entry of the cycle / shot modes too (a first compile mid-run is a ~0.8 s gap)
            String list = !Config.DEV_SPRITE_FILTER_SHOT_MODES.isEmpty() ? Config.DEV_SPRITE_FILTER_SHOT_MODES : Config.DEV_SPRITE_FILTER_CYCLE;
            for (String e : list.isEmpty() ? new String[0] : list.split(",")) {
               Entry x = Entry.parse(e.trim());
               if (!"stock".equals(x.min) && !"nearest".equals(x.min)) {
                  for (int regime : new int[] {MAGNIFY, MINIFY}) {
                     variant(definesFor(regime, minCode(x.min), x.skip, x.lodBiasPct, x.smooth, x.sharpPct, x.linear, Config.SPRITE_FILTER_INTEGER_AA));
                  }
               }
            }
         }
         if (Config.SPRITE_FILTER_SPRITES) {
            tileVariants();
         }
         Log.info(String.format(java.util.Locale.ROOT, "sprite filter: variants ready in %.0f ms", (System.nanoTime() - t0) / 1e6));
      } catch (Throwable t) {
         failedAll = true;
         Log.warn("sprite filter: precompile failed (" + t + ")");
      }
   }

   private static boolean failedAll;

   private static int minCode(String m) {
      switch (m == null ? "" : m) {
         case "floor": return 5;
         case "rgssa": return 6;
         case "rgssa2": return 7;
         case "rgss2": return 2;
         case "bias": return 1;
         case "trilinear": return 0;
         case "rgss4": default: return 4;
      }
   }

   private static void queue(int regime, int mag, String defines) {
      Frame f = ring[ringAt++ & 3];
      if (f.mips.length < sharpPendingN) {
         f.mips = new zombie.core.textures.Texture[sharpPendingN * 2];
      }
      for (int i = 0; i < sharpPendingN; i++) {
         f.mips[i] = sharpPending[i];
         sharpPending[i] = null;
      }
      f.nMips = sharpPendingN;
      sharpPendingN = 0;
      f.sprites = Config.SPRITE_FILTER_SPRITES && "sharp".equals(mode()) && supported();
      f.regime = regime;
      f.mag = mag;
      f.defines = defines;
      SpriteRenderer.instance.drawGeneric(f);
      if (f.nMips > 0) {
         // the level-1 passes leave their own framebuffer and viewport: restart the world frame the way the game does
         // after every bake (FBORenderChunkManager.endCaching), no GL query needed
         SpriteRenderer.instance.glDoEndFrame();
         SpriteRenderer.instance.glDoStartFrame(Core.getInstance().getScreenWidth(), Core.getInstance().getScreenHeight(),
               Core.getInstance().getCurrentPlayerZoom(), zombie.iso.IsoCamera.frameState.playerIndex);
      }
   }

   /** Render thread, in stream order before the composite: pick (and compile once) this frame's program. */
   private static final class Frame extends TextureDraw.GenericDrawer {
      int regime;
      int mag;
      String defines;
      boolean sprites;
      zombie.core.textures.Texture[] mips = new zombie.core.textures.Texture[16];
      int nMips;

      @Override
      public void render() {
         SpriteMips.runBatch(this.mips, this.nMips);
         this.nMips = 0;
         tileOn = this.sprites && tileVariants();
         if (tileOn) {
            zombie.core.textures.TextureFBO fbo = Core.getInstance().getOffscreenBuffer(0);
            worldFbo = fbo == null ? -1 : fbo.getBufferId();
         }
         zombie.viewCone.ChunkRenderShader stock = zombie.core.SceneShaderStore.chunkRenderShader;
         stockProgram = stock != null && stock.isCompiled() ? stock.getID() : 0;
         remapProgram = 0;
         if (dynamicCompiled && PixelLight.chunkShaderPatched) {
            // pixel light owns the composite programs; the fetch in them decides per texture (launch setting)
            compositeMag = "sharp".equals(Config.SPRITE_FILTER) ? GL11.GL_LINEAR : this.mag;
            return;
         }
         compositeMag = this.mag;
         if (this.regime == NONE || stockProgram == 0 || PixelLight.chunkShaderPatched || stockFrag == null) {
            if (this.regime != NONE) {
               compositeMag = 0; // no variant can run: keep the stock filters with the stock program
            }
            return;
         }
         zombie.viewCone.ChunkRenderShader v = variant(this.defines);
         if (v == null) {
            compositeMag = 0;
            return;
         }
         remapProgram = v.getID();
      }
   }

   private static zombie.viewCone.ChunkRenderShader variant(String defines) {
      zombie.viewCone.ChunkRenderShader v = variants.get(defines);
      if (v != null || failedVariants.containsKey(defines)) {
         return v;
      }
      compilingDefines = defines;
      try {
         v = new zombie.viewCone.ChunkRenderShader("pzopt_sfChunk");
         if (v.getProgram() != null && v.isCompiled()) {
            variants.put(defines, v);
            Log.info("sprite filter: composite variant " + v.getID() + " (" + defines.replace("#define ", "").replace('\n', ' ').trim() + ")");
            return v;
         }
         Log.warn("sprite filter: composite variant did not compile (" + defines.replace('\n', ' ') + "): stock filtering");
      } catch (Throwable t) {
         Log.warn("sprite filter: composite variant failed (" + t + "): stock filtering");
      } finally {
         compilingDefines = "";
      }
      failedVariants.put(defines, Boolean.TRUE);
      return null;
   }

   /**
    * Render thread, TextureDraw's StartShader: the program to start instead of {@code program} (the chunk composite's
    * variant for this frame's zoom, else {@code program}). Every program start also ends the composite's filter window.
    */
   public static int remap(int program) {
      inComposite = false;
      if (program == 0) {
         return 0;
      }
      if (program == stockProgram) {
         int r = remapProgram;
         return r != 0 ? r : program;
      }
      if (tileOn && zombie.core.textures.TextureFBO.lastID == worldFbo && zombie.iso.fboRenderChunk.FBORenderChunkManager.instance.renderThreadCurrent == null) {
         // a tile drawn per frame into the world (not a bake into a chunk texture, not a UI scene)
         if (program == tileStock[0]) {
            return tileVariant[0];
         }
         if (program == tileStock[1]) {
            return tileVariant[1];
         }
      }
      return program;
   }

   /** Render thread: the per-frame tile programs' variants exist (compiled once; false when they cannot run). */
   private static boolean tileVariants() {
      boolean any = false;
      for (int i = 0; i < 2; i++) {
         if (tileVariant[i] != 0) {
            any = true;
            continue;
         }
         if (tileFailed[i]) {
            continue;
         }
         zombie.tileDepth.TileDepthShader stock = i == 0 ? zombie.core.SceneShaderStore.tileDepthShader : zombie.core.SceneShaderStore.opaqueDepthShader;
         if (stock == null || !stock.isCompiled() || tileFrag[i] == null || tileVert[i] == null) {
            continue; // not compiled yet: try again next frame
         }
         tileFailed[i] = true; // until it is proven good
         compilingTile = i;
         compilingDefines = "#define PZSF_REGIME 3\n#define PZSF_MIN " + minCode(Config.SPRITE_FILTER_MIN) + "\n#define PZSF_SKIP 0\n"
               + String.format(java.util.Locale.ROOT, "#define PZSF_LOD_BIAS %.3f\n", Config.SPRITE_FILTER_LOD_BIAS_PCT / 100.0)
               + "#define PZSF_INTEGER_AA " + (Config.SPRITE_FILTER_INTEGER_AA ? 1 : 0) + "\n#define PZSF_LINEAR " + (Config.SPRITE_FILTER_LINEAR_LIGHT ? 1 : 0) + "\n#define PZSF_KERNEL "
               + ("smooth".equals(Config.SPRITE_FILTER_KERNEL) ? 1 : 0) + "\n"
               + String.format(java.util.Locale.ROOT, "#define PZSF_WIDTH %.6f\n", 100.0 / Math.max(25, Math.min(800, Config.SPRITE_FILTER_SHARPNESS_PCT)));
         try {
            zombie.tileDepth.TileDepthShader v = new zombie.tileDepth.TileDepthShader(i == 0 ? "pzopt_sfTile" : "pzopt_sfOpaque");
            if (v.getProgram() == null || !v.isCompiled()) {
               Log.warn("sprite filter: the per-frame " + TILE_NAMES[i] + " variant did not compile: stock filtering for those tiles");
               continue;
            }
            // the game sets this program's uniforms through the stock program's locations (ShaderUniformSetter,
            // IndieGL.shaderSetValue): the variant must have every stock uniform at the same location
            String bad = sameUniforms(stock.getID(), v.getID());
            if (bad != null) {
               Log.warn("sprite filter: the per-frame " + TILE_NAMES[i] + " variant's uniform " + bad + " moved: stock filtering for those tiles");
               continue;
            }
            tileStock[i] = stock.getID();
            tileVariant[i] = v.getID();
            tileFailed[i] = false;
            any = true;
            Log.info("sprite filter: per-frame " + TILE_NAMES[i] + " variant " + v.getID() + " (stock " + stock.getID() + ")");
         } catch (Throwable t) {
            Log.warn("sprite filter: per-frame " + TILE_NAMES[i] + " variant failed (" + t + ")");
         } finally {
            compilingTile = -1;
            compilingDefines = "";
         }
      }
      return any;
   }

   /** Null when every active uniform of {@code stock} has the same location in {@code variant}, else its name. */
   private static String sameUniforms(int stock, int variant) {
      int n = org.lwjgl.opengl.GL20.glGetProgrami(stock, org.lwjgl.opengl.GL20.GL_ACTIVE_UNIFORMS);
      try (org.lwjgl.system.MemoryStack st = org.lwjgl.system.MemoryStack.stackPush()) {
         java.nio.IntBuffer size = st.mallocInt(1), type = st.mallocInt(1);
         for (int i = 0; i < n; i++) {
            String name = org.lwjgl.opengl.GL20.glGetActiveUniform(stock, i, size, type);
            int a = org.lwjgl.opengl.GL20.glGetUniformLocation(stock, name);
            int b = org.lwjgl.opengl.GL20.glGetUniformLocation(variant, name);
            if (a != b) {
               return name + " (" + a + " -> " + b + ")";
            }
         }
      }
      return null;
   }

   /** Render thread, the end of ChunkRenderShader.startRenderThread (its DEPTH texture is bound): the chunk quad's bind follows. */
   public static void afterChunkStart() {
      inComposite = compositeMag != 0;
   }

   /** Render thread, TextureID.assignFilteringFlags: the magnification filter a chunk texture gets in the composite. */
   public static int magFilter(int stockMag) {
      return inComposite ? compositeMag : stockMag;
   }

   // ------------------------------------------------------------------------------------------------------ shaders

   /** ShaderUnit hook (outermost): capture the stock composite's sources, build the variants, patch pixel light's. */
   public static String patchShader(String fileName, String code) {
      if (fileName == null || code == null) {
         return code;
      }
      String fn = fileName.replace('\\', '/');
      for (int i = 0; i < 2; i++) {
         String name = i == 0 ? "pzopt_sfTile" : "pzopt_sfOpaque";
         if (fn.endsWith("/" + name + ".vert")) {
            return tileVert[i] != null ? tileVert[i] : code;
         }
         if (fn.endsWith("/" + name + ".frag")) {
            String p = tileFrag[i] == null || compilingTile != i ? null : patchFetch(tileFrag[i], compilingDefines);
            return p != null ? p : code;
         }
         if (fn.endsWith("/" + TILE_NAMES[i] + ".vert")) {
            tileVert[i] = code;
            return code;
         }
         if (fn.endsWith("/" + TILE_NAMES[i] + ".frag")) {
            tileFrag[i] = code;
            return code;
         }
      }
      if (fn.endsWith("/pzopt_sfChunk.vert")) {
         return stockVert != null ? stockVert : code;
      }
      if (fn.endsWith("/pzopt_sfChunk.frag")) {
         if (stockFrag == null) {
            return code;
         }
         String p = patchFetch(stockFrag, compilingDefines);
         return p != null ? p : code;
      }
      if (fn.endsWith("/chunkShader.vert")) {
         stockVert = code;
         return code;
      }
      if (fn.endsWith("/chunkShader.frag") || fn.endsWith("/pzopt_chunkBase.frag")) {
         boolean stock = code.contains("texture2D(DIFFUSE, texCoord.st, 0.0)");
         if (fn.endsWith("/chunkShader.frag") && stock) {
            stockFrag = code; // the variants above replace it per frame; the program itself stays stock
            return code;
         }
         // pixel light's programs (its chunkShader.frag and every pzopt_chunkBase variant) carry the self-selecting
         // fetch when the sprite filter is on at launch
         if (!stock && "sharp".equals(Config.SPRITE_FILTER) && supported()) {
            String p = patchFetch(code, configDefines(DYNAMIC));
            if (p != null) {
               dynamicCompiled = true;
               Log.info("sprite filter: " + fileName + " (pixel light) samples its texture with the sprite filter");
               return p;
            }
         }
      }
      return code;
   }

   /** The composite source with its one DIFFUSE fetch replaced by pzsfFetch, or null when the fetch is not found. */
   static String patchFetch(String code, String defines) {
      String[] fetches = {"texture2D(DIFFUSE, texCoord.st, 0.0)", "texture(DIFFUSE, texCoord.st)"};
      String found = null;
      for (String f : fetches) {
         int at = code.indexOf(f);
         if (at >= 0 && code.indexOf(f, at + 1) < 0) {
            found = f;
            break;
         }
      }
      if (found == null) {
         Log.warn("sprite filter: the composite's texture fetch was not found; stock filtering");
         return null;
      }
      String c = code.replace(found, "pzsfFetch(DIFFUSE, texCoord.st)");
      if (c.startsWith("#version 120")) {
         c = "#version 150 compatibility" + c.substring("#version 120".length());
      }
      // after the #version / #extension lines (an #extension must come before any declaration)
      int insert = 0;
      int pos = 0;
      while (pos < c.length()) {
         int nl = c.indexOf('\n', pos);
         int end = nl < 0 ? c.length() : nl;
         String line = c.substring(pos, end).trim();
         if (line.startsWith("#version") || line.startsWith("#extension")) {
            insert = nl < 0 ? c.length() : nl + 1;
         }
         pos = end + 1;
      }
      return c.substring(0, insert) + defines + FETCH_GLSL + "\n" + c.substring(insert);
   }

   /**
    * The fetch. {@code PZSF_REGIME} 1 = magnified, 2 = minified, 3 = picked per texture from the texel footprint.
    * Chunk textures are premultiplied (composited with ONE, ONE_MINUS_SRC_ALPHA), so blending texels is exact.
    */
   static final String FETCH_GLSL = String.join("\n",
      "// pzopt.SpriteFilter (candidate A): texel-aware magnification, supersampled minification",
      "vec4 pzsfMagnify(sampler2D t, vec2 p, vec2 sz, vec2 w) {",
      // p: texel coordinates; w: the screen pixel's width in texels (x the sharpness). Inside a texel the coordinate
      // snaps to its centre; across a texel edge it ramps over one pixel, so the linear filter blends the two texels
      // by their coverage of that pixel (exact box filter of a hard-edged texel grid)
      "   vec2 c = floor(p + 0.5);",
      "#if PZSF_KERNEL == 1",
      // smoothstep ramp across the edge (a softer shoulder than the box, same width)
      "   vec2 f = clamp((p - c) / w + 0.5, 0.0, 1.0);",
      "   p = c + f * f * (3.0 - 2.0 * f) - 0.5;",
      "#else",
      "   p = c + clamp((p - c) / w, -0.5, 0.5);",
      "#endif",
      "   return textureLod(t, p / sz, 0.0);",
      "}",
      "vec4 pzsfMinify(sampler2D t, vec2 uv, vec2 dx, vec2 dy) {",
      // the camera is orthographic and the texture axis-aligned: every pixel of the draw has the same footprint, so a
      // trilinear blend of two levels would only be a fixed blur (and twice the fetch cost). Each tap reads one level,
      // bilinear: level 0 up to 2.83 texels a pixel
      "#if PZSF_MIN >= 2",
      "   vec2 sz = vec2(textureSize(t, 0));",
      "   float l2 = log2(max(abs(dx.x) * sz.x, abs(dy.y) * sz.y));",
      // level 0 below two texels a pixel, level 1 from 2x (the 2:1 box level is the right prefilter there) and so on;
      // PZSF_LOD_BIAS 0.5 keeps level 0 up to 2.83 (sharper, but the taps then read 4x the texels: +bandwidth)
      "   float lod = max(0.0, floor(l2 - PZSF_LOD_BIAS + 0.01));",
      "#endif",
      "#if PZSF_SKIP && (PZSF_MIN == 2 || PZSF_MIN == 4 || PZSF_MIN == 6 || PZSF_MIN == 7)",
      // one coarse probe a level above the taps: its bilinear footprint (+-2 texels of the taps' level) covers every tap
      // (+-1.75 at most), and a zero alpha there means an empty texture area (the transparent air above the ground and
      // between walls is most of a chunk texture)
      "   if (textureLod(t, uv, lod + 1.0).a == 0.0) return vec4(0.0);",
      "#endif",
      "#if PZSF_MIN == 5",
      // one bilinear tap on that level: the pixel covers 1 - 2 of its texels, inside the bilinear tent's reach, so it is
      // filtered enough; half the fetches of stock's trilinear, and no blend with the coarser level (sharper)
      "   return textureLod(t, uv, lod);",
      "#elif PZSF_MIN == 6",
      // rotated grid spread by how far the footprint exceeds the tap level's texel (none at 1:1, full at 2:1)
      "   float k = clamp(exp2(l2 - lod) - 1.0, 0.0, 1.0);",
      "   vec2 a = k * (0.125 * dx + 0.375 * dy), b = k * (0.375 * dx - 0.125 * dy);",
      "   return 0.25 * (textureLod(t, uv + a, lod) + textureLod(t, uv - a, lod) + textureLod(t, uv + b, lod) + textureLod(t, uv - b, lod));",
      "#elif PZSF_MIN == 7",
      // two taps along the diagonal, spread the same way (half the fetches of the grid)
      "   float k = clamp(exp2(l2 - lod) - 1.0, 0.0, 1.0);",
      "   vec2 a = k * 0.25 * (dx + dy);",
      "   return 0.5 * (textureLod(t, uv + a, lod) + textureLod(t, uv - a, lod));",
      "#elif PZSF_MIN == 4",
      // rotated-grid supersampling: four taps at (+-1/8, +-3/8) of the pixel, one level sharper than the footprint (Golus)
      "   vec2 a = 0.125 * dx + 0.375 * dy, b = 0.375 * dx - 0.125 * dy;",
      "#if PZSF_LINEAR",
      // light-correct average (gamma 2 approximation): thin bright lines keep their brightness when shrunk
      "   vec4 s0 = textureLod(t, uv + a, lod), s1 = textureLod(t, uv - a, lod), s2 = textureLod(t, uv + b, lod), s3 = textureLod(t, uv - b, lod);",
      "   vec3 l = 0.25 * (s0.rgb * s0.rgb + s1.rgb * s1.rgb + s2.rgb * s2.rgb + s3.rgb * s3.rgb);",
      "   return vec4(sqrt(l), 0.25 * (s0.a + s1.a + s2.a + s3.a));",
      "#else",
      "   return 0.25 * (textureLod(t, uv + a, lod) + textureLod(t, uv - a, lod) + textureLod(t, uv + b, lod) + textureLod(t, uv - b, lod));",
      "#endif",
      "#elif PZSF_MIN == 2",
      "   vec2 a = 0.25 * (dx + dy);",
      "   return 0.5 * (textureLod(t, uv + a, lod) + textureLod(t, uv - a, lod));",
      "#elif PZSF_MIN == 1",
      "   return texture(t, uv, -0.5);",
      "#else",
      "   return texture(t, uv);",
      "#endif",
      "}",
      "vec4 pzsfFetch(sampler2D t, vec2 uv) {",
      "#if PZSF_REGIME == 1",
      "   vec2 sz = vec2(textureSize(t, 0));",
      "   vec2 p = uv * sz;",
      "   return pzsfMagnify(t, p, sz, max(fwidth(p), vec2(1e-5)) * PZSF_WIDTH);",
      "#elif PZSF_REGIME == 2",
      "   return pzsfMinify(t, uv, dFdx(uv), dFdy(uv));",
      "#else",
      // pixel light's programs: the footprint is the same for every pixel of a chunk texture, so the branch is uniform
      "   vec2 sz = vec2(textureSize(t, 0));",
      "   vec2 p = uv * sz;",
      "   vec2 dx = dFdx(uv), dy = dFdy(uv);",
      "   vec2 d = fwidth(p);",
      "#if !PZSF_INTEGER_AA",
      // whole multiples (2x, 4x): point sampling is exact, like the composite's
      "   float k = 1.0 / max(d.x, 1e-5);",
      "   if (d.x < 0.999 && abs(k - floor(k + 0.5)) < 0.002 * k) return textureLod(t, (floor(p) + 0.5) / sz, 0.0);",
      "#endif",
      "   if (d.x < 0.999) return pzsfMagnify(t, p, sz, max(d, vec2(1e-5)) * PZSF_WIDTH);",
      "   if (d.x > 1.001) return pzsfMinify(t, uv, dx, dy);",
      "   return textureLod(t, (floor(p) + 0.5) / sz, 0.0);",
      "#endif",
      "}");
}
