package pzopt;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoLightSource;
import zombie.iso.IsoRoomLight;
import zombie.iso.IsoWorld;
import zombie.iso.LightingJNI;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;

/**
 * Per-pixel lighting of the static world ({@code pixelLight}, 2026-09-25).
 *
 * <p>Stock bakes the light into the chunk textures: floors get the four corner colours of their square (libLighting's
 * {@code cacheVertLight} 0-3, each about the brightest of the four squares around the corner, which is what makes the light
 * blocky), walls the bottom and top corners, objects the flat {@code lightInfo}; every change re-bakes the chunk level
 * (torches, headlights, lightning, dusk, the vision fade). Here the chunk textures bake unlit (the chunk's squares hand out
 * white light while it bakes) and the chunk composite shader lights every pixel:
 * <ul>
 *   <li>three small texture arrays, one texel per square, toroidal in x, y (the chunk grid) and z (16 levels): the square's
 *       own light without its handheld torch (the native's sample at the centre, what stock draws objects with) and a
 *       "simple square" flag; the connectivity to its eight neighbours (shared corner colours: the native breaks them at
 *       walls), whether the torch reaches it for the native, whether it has a vertical gradient; the wall gradient
 *       (top corners' mean minus the bottom's). A light change re-packs a chunk level: 768 bytes;</li>
 *   <li>per pixel: the world position from the depth (linear in x + y + 2z under the iso projection), the light between
 *       the square centres (one bilinear fetch when all four are simple, else masked by the connectivity), the handheld
 *       torch from its fitted cone (x the native's visibility), vehicle lights' and point lights' shapes on top of the
 *       native's values, the surface's facing to each light from the depth's normal, the torch shadow mask of the
 *       previous frame (reprojected; {@code pplShadows}).</li>
 * </ul>
 * Split screen, the Mac (GL 2.1) and any GL failure fall back to the stock baked light (every texture re-baked).
 *
 * <p>Dev rig {@code devPplDumpAt=s1,s2}: the cached lighting of every loaded square near the player, every light
 * source and the camera to {@code ~/Zomboid/pzopt-ppl/<tag>-squares.txt}, plus the scene depth / colour right after the
 * chunk composite; {@code harness/ppl/} reads them.
 */
public final class PixelLight {
   private PixelLight() {
   }

   /** The chunk textures bake unlit and this pass lights them. Read by the game thread and the lighting-read workers. */
   public static volatile boolean ACTIVE = Config.PIXEL_LIGHT && Overrides.enabled() && !System.getProperty("os.name", "").contains("OS X");

   static final int LEVELS = 16; // levels the lattice holds (level & 15)
   /** IsoDepthHelper: depth per unit of x + y (SQUARE_DEPTH / 2; a level adds 2 units) */
   static final double DEPTH_PER_XY = 0.0028867084 / 2.0;

   private static volatile boolean failed;
   private static boolean rebakeAll;

   private static void fail(String why) {
      if (!failed) {
         failed = true;
         ACTIVE = false;
         rebakeAll = true;
         Log.warn("pixel light: " + why + "; stock baked light for the rest of the session");
      }
   }

   // ------------------------------------------------------------------------------------------------ bake (game thread)

   private static final ArrayList<LightingJNI.JNILighting> whitened = new ArrayList<>();
   private static IsoChunk bakeChunk;
   public static long bakes, whitenedSquares;

   /** A chunk texture starts baking: every square of the chunk hands out white light until its texture is done. */
   public static void bakeBegin(IsoChunk c, int playerIndex) {
      if (bakeChunk == c) {
         return;
      }
      bakeEnd();
      if (bakeChunk != null) {
         unwhitenAll(); // a new texture while the last one still caches: cannot happen, but never leave squares white
      }
      bakeChunk = c;
      bakes++;
      for (int z = c.minLevel; z <= c.maxLevel; z++) {
         IsoGridSquare[] squares = c.squares[z - c.minLevel];
         for (int i = 0; i < squares.length; i++) {
            IsoGridSquare sq = squares[i];
            if (sq != null && sq.lighting[playerIndex] instanceof LightingJNI.JNILighting jl) {
               jl.pzoptWhiten();
               whitened.add(jl);
            }
         }
         int li = z + 32;
         if (li >= 0 && li < 64) {
            c.pzoptPplDirty[li] = 1; // the bake refreshes squares' light lazily
         }
      }
      whitenedSquares += whitened.size();
   }

   /** After a chunk level's bake ended (and once before the composite): the real light back once the texture is done. */
   public static void bakeEnd() {
      if (bakeChunk == null || FBORenderChunkManager.instance.isCaching()) {
         return;
      }
      unwhitenAll();
   }

   private static void unwhitenAll() {
      for (int i = 0; i < whitened.size(); i++) {
         whitened.get(i).pzoptUnwhiten();
      }
      whitened.clear();
      bakeChunk = null;
   }

   /** A square's light was re-read from the native (game thread or a lighting-read worker; one level per task). */
   public static void lightChanged(IsoGridSquare square) {
      IsoChunk c = square.chunk;
      int li = square.z + 32;
      if (c != null && li >= 0 && li < 64) {
         c.pzoptPplDirty[li] = 1;
      }
   }

   // ------------------------------------------------------------------------------------------------ lattice (game thread)

   private static int n; // squares per side of the lattice (power of two, >= the chunk grid)
   private static IsoChunk[] slotChunk;
   private static int[] slotLevel; // per slot and level & 15: the level uploaded there
   private static final ArrayList<Frame> RING = new ArrayList<>();
   private static long frames, blocksUploaded, framesFull, packNs, packSimple, packHidden, packSlow;
   private static int traceSq, traceSeen; // dev (devPplTrace): this frame's packed squares, the seen ones
   private static long traceLum; // and the sum of their packed light
   private static int logCountdown;

   /** composite: the chunk composite shader lights each fragment (no extra pass); pass: a full-screen pass after it */
   static boolean compositeMode() {
      return !"pass".equals(Config.PPL_MODE) && chunkShaderPatched;
   }

   private static Frame pendingPass;
   private static int shadowLight = -1; // this frame's light that gets the shadow mask (composite mode), -1: none
   static volatile int costMask; // dev (devPplCostAt): parts of the shader switched off, for attributing its cost

   /**
    * Game thread, after the bakes and right before FBORenderChunkManager.endFrame() (the chunk composite): the lattice
    * blocks to upload and this frame's camera, queued ahead of the composite (composite mode) or kept for the pass.
    */
   public static void beforeComposite(int playerIndex, ArrayList<IsoChunk> onScreen) {
      if (worldUpNs == 0L && IsoWorld.instance != null && IsoWorld.instance.currentCell != null) {
         worldUpNs = System.nanoTime();
      }
      if (!Config.DEV_PPL_TOGGLE_AT.isEmpty()) {
         scheduledToggles();
      }
      if (!Config.DEV_PPL_COST_AT.isEmpty() && worldUpNs != 0L) {
         double t = (System.nanoTime() - worldUpNs) / 1e9;
         for (String part : Config.DEV_PPL_COST_AT.split(",")) {
            String[] kv = part.split(":");
            if (t >= Double.parseDouble(kv[0].trim())) {
               costMask = Integer.parseInt(kv[1].trim());
            }
         }
      }
      if (!Config.DEV_PPL_ALTERNATE.isEmpty() && worldUpNs != 0L) {
         String[] a = Config.DEV_PPL_ALTERNATE.split(",");
         double t = (System.nanoTime() - worldUpNs) / 1e9 - Double.parseDouble(a[0].trim());
         if (t >= 0.0) {
            costMask = Integer.parseInt(a[((long)(t / Double.parseDouble(a[1].trim())) & 1L) == 0L ? 2 : 3].trim());
         }
      }
      if (rebakeAll) {
         rebakeAll = false;
         rebakeEverything();
      }
      pendingPass = null;
      if (Config.DEV_PPL_TIMING) {
         SpriteRenderer.instance.drawGeneric(COMPOSITE_START);
      }
      if (!ACTIVE) {
         if (chunkShaderPatched && Gl.onSent) {
            SpriteRenderer.instance.drawGeneric(OFF); // the composite shader back to the stock light
         }
         return;
      }
      if (IsoPlayer.numPlayers > 1) {
         fail("split screen");
         return;
      }
      int grid = Math.max(IsoChunkMap.chunkGridWidth, 1) * 8;
      int want = 64;
      while (want < grid) {
         want <<= 1;
      }
      if (want != n) {
         n = want;
         int slots = (n / 8) * (n / 8);
         slotChunk = new IsoChunk[slots];
         slotLevel = new int[slots * LEVELS];
         java.util.Arrays.fill(slotLevel, Integer.MIN_VALUE);
         Log.info("pixel light: " + n + "x" + n + " squares x " + LEVELS + " levels, 3 x " + n * n * LEVELS * 4 / 1048576 + " MB, chunk grid " + IsoChunkMap.chunkGridWidth
            + ", mode " + (compositeMode() ? "composite" : "pass"));
      }
      if (chunkShaderPatched && (zombie.core.SceneShaderStore.chunkRenderShader == null || !zombie.core.SceneShaderStore.chunkRenderShader.isCompiled())) {
         chunkShaderPatched = false; // the game dropped the chunk composite shader (it would draw the unlit texture): light in a pass instead
         Log.warn("pixel light: the chunk composite shader is not in use, pass mode");
      }
      Frame f = freeFrame();
      f.n = n;
      f.blocks = 0;
      f.composite = compositeMode();
      f.ox = (int)Math.floor(IsoCamera.frameState.camCharacterX);
      f.oy = (int)Math.floor(IsoCamera.frameState.camCharacterY);
      ambR = Math.min(1.0F, ambR + 0.0005F);
      ambG = Math.min(1.0F, ambG + 0.0005F);
      ambB = Math.min(1.0F, ambB + 0.0005F);
      int s = n / 8;
      long packT0 = System.nanoTime();
      for (int i = 0; i < onScreen.size(); i++) {
         IsoChunk c = onScreen.get(i);
         int slot = Math.floorMod(c.wx, s) + Math.floorMod(c.wy, s) * s;
         if (slotChunk[slot] != c) {
            slotChunk[slot] = c;
            java.util.Arrays.fill(slotLevel, slot * LEVELS, slot * LEVELS + LEVELS, Integer.MIN_VALUE);
         }
         int zTop = Math.min(c.maxLevel + 1, c.minLevel + LEVELS - 1); // one level above the top: tall sprites (tree crowns) reach into it
         for (int z = c.minLevel; z <= zTop; z++) {
            int li = z + 32;
            int idx = slot * LEVELS + (z & (LEVELS - 1));
            boolean dirty = li >= 0 && li < 64 && c.pzoptPplDirty[li] != 0;
            if (slotLevel[idx] == z && !dirty) {
               continue;
            }
            if (!f.room()) {
               framesFull++;
               break;
            }
            if (li >= 0 && li < 64) {
               c.pzoptPplDirty[li] = 0;
            }
            slotLevel[idx] = z;
            pack(f, c, z, playerIndex);
         }
      }
      blocksUploaded += f.blocks;
      ambientCommit();
      packNs += System.nanoTime() - packT0;
      // the camera: window px -> (x - y, x + y - 6z) and depth -> x + y + 2z, relative to the origin square
      Core core = Core.getInstance();
      int ox = (int)Math.floor(IsoCamera.frameState.camCharacterX), oy = (int)Math.floor(IsoCamera.frameState.camCharacterY);
      zombie.iso.PlayerCamera cam = IsoCamera.cameras[playerIndex];
      f.zoom = core.getZoom(playerIndex);
      f.ts = Core.tileScale;
      f.offX = IsoCamera.getOffX();
      f.offY = IsoCamera.getOffY();
      f.screenW = IsoCamera.getScreenWidth(playerIndex);
      f.screenH = IsoCamera.getScreenHeight(playerIndex);
      f.ox = ox;
      f.oy = oy;
      f.jx = cam.fixJigglyModelsSquareX;
      f.jy = cam.fixJigglyModelsSquareY;
      f.d0 = IsoDepthHelper.getSquareDepthData(ox, oy, ox, oy, 0.0F).depthStart;
      f.lights = Config.PPL_ANALYTIC ? gatherLights(f, playerIndex) : 0;
      // the chunk textures composited this frame, keyed by their depth texture (the StartShader draw carries it): the render
      // thread picks the light-free variant for those no dynamic light reaches
      ArrayList<zombie.iso.fboRenderChunk.FBORenderChunk> list = FBORenderChunkManager.instance.toRenderThisFrame;
      f.chunks = 0;
      for (int i = 0; i < list.size() && f.chunks < f.chunkKeys.length; i++) {
         zombie.iso.fboRenderChunk.FBORenderChunk rc = list.get(i);
         if (rc.depth == null || rc.chunk == null) {
            continue;
         }
         int k = f.chunks++;
         f.chunkKeys[k] = rc.depth;
         f.chunkRect[k * 4] = rc.chunk.wx * 8 - f.ox;
         f.chunkRect[k * 4 + 1] = rc.chunk.wy * 8 - f.oy;
         f.chunkRect[k * 4 + 2] = rc.getMinLevel();
         f.chunkRect[k * 4 + 3] = rc.getTopLevel();
         f.chunkFlags[k] = chunkFlags(rc.chunk, rc.getMinLevel(), rc.getTopLevel());
      }
      f.wet = Config.PPL_WET_SPECULAR ? zombie.iso.IsoPuddles.getInstance().getWetGroundFinalValue() : 0.0F;
      f.shadowLight = -1;
      for (int i = 0; i < f.lights && Config.PPL_SHADOWS; i++) {
         if (f.lc[i * 4 + 3] == 1.0F) { // the first handheld torch: the player's
            f.shadowLight = i;
            break;
         }
      }
      shadowLight = f.composite ? f.shadowLight : -1;
      frames++;
      if (--logCountdown <= 0) {
         logCountdown = 3600;
         Log.info("pixel light: " + stats());
      }
      if (Config.DEV_PPL_TRACE) {
         Log.info(String.format(java.util.Locale.ROOT, "ppl trace: ms=%d cam=%.3f,%.3f o=%d,%d blocks=%d sq=%d lum=%.1f seen=%d full=%d onScreen=%d chunks=%d off=%.1f,%.1f zoom=%.3f d0=%.6f j=%.3f,%.3f comp=%b",
               System.currentTimeMillis(), IsoCamera.frameState.camCharacterX, IsoCamera.frameState.camCharacterY, f.ox, f.oy, f.blocks, traceSq,
               traceSq > 0 ? traceLum / (3.0 * traceSq) : -1.0, traceSeen, framesFull, onScreen.size(), f.chunks, f.offX, f.offY, f.zoom, f.d0, f.jx, f.jy, f.composite));
         traceSq = traceSeen = 0;
         traceLum = 0L;
      }
      if (f.composite) {
         SpriteRenderer.instance.drawGeneric(f);
      } else {
         pendingPass = f;
      }
   }

   // dev (devPplTiming): GPU time of the chunk composite (and the pass, pass mode) per mode, from timestamp queries
   // bracketing it, logged every 600 frames of one mode with the mode's name: an A/B inside one run (devPplToggleAt)
   // that the machine's clock drift does not blur
   private static final TextureDraw.GenericDrawer COMPOSITE_START = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         CompositeTimer.stamp(0);
      }
   };
   private static final TextureDraw.GenericDrawer COMPOSITE_END = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         CompositeTimer.stamp(1);
         CompositeTimer.collect(ACTIVE);
      }
   };

   static final class CompositeTimer {
      private static int[] q;
      private static int slot;
      private static final boolean[] pending = new boolean[8], mode = new boolean[8];
      private static final int[] slotMask = new int[8];
      private static long sumOn, sumOff, nOn, nOff;
      private static final long[] altSum = new long[2], altN = new long[2];
      private static int altLast = -1, altSince;

      static void stamp(int i) {
         if (q == null) {
            q = new int[16];
            GL15.glGenQueries(q);
         }
         GL33.glQueryCounter(q[slot * 2 + i], GL33.GL_TIMESTAMP);
      }

      static void collect(boolean on) {
         pending[slot] = true;
         mode[slot] = on;
         slotMask[slot] = costMask;
         slot = (slot + 1) % 8;
         if (pending[slot] && GL15.glGetQueryObjecti(q[slot * 2 + 1], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
            long ns = GL33.glGetQueryObjecti64(q[slot * 2 + 1], GL15.GL_QUERY_RESULT) - GL33.glGetQueryObjecti64(q[slot * 2], GL15.GL_QUERY_RESULT);
            if (!Config.DEV_PPL_ALTERNATE.isEmpty()) {
               alternate(slotMask[slot], ns);
            } else if (mode[slot]) {
               sumOn += ns;
               nOn++;
            } else {
               sumOff += ns;
               nOff++;
            }
            if (nOn + nOff >= 600) {
               Log.info(String.format("pixel light composite gpu: %s %.1f us (%d frames) epoch_ms=%d", (nOn >= nOff ? "on" : "off") + (costMask != 0 ? "/m" + costMask : ""),
                  (nOn >= nOff ? sumOn / (double)nOn : sumOff / (double)nOff) / 1e3, Math.max(nOn, nOff), System.currentTimeMillis()));
               sumOn = sumOff = nOn = nOff = 0;
            }
         }
         pending[slot] = false;
      }

      /** devPplAlternate: the two masks summed apart, the first 8 frames after every flip left out, a line every 3000 frames. */
      private static void alternate(int mask, long ns) {
         String[] a = Config.DEV_PPL_ALTERNATE.split(",");
         int k = mask == Integer.parseInt(a[2].trim()) ? 0 : mask == Integer.parseInt(a[3].trim()) ? 1 : -1;
         if (k != altLast) {
            altLast = k;
            altSince = 0;
         }
         if (k < 0 || ++altSince <= 8) {
            return;
         }
         altSum[k] += ns;
         altN[k]++;
         if (altN[0] + altN[1] >= 3000) {
            Log.info(String.format("pixel light composite gpu alt: m%s %.1f us (%d) m%s %.1f us (%d) epoch_ms=%d", a[2].trim(), altSum[0] / 1e3 / Math.max(1L, altN[0]), altN[0], a[3].trim(),
               altSum[1] / 1e3 / Math.max(1L, altN[1]), altN[1], System.currentTimeMillis()));
            altSum[0] = altSum[1] = altN[0] = altN[1] = 0L;
         }
      }
   }

   /** dev (devPplTiming): GPU time of the shadow mask pass, logged every 600 frames. */
   static final class ShadowTimer {
      private static int[] q;
      private static int slot;
      private static final boolean[] pending = new boolean[8];
      private static long sum, count;

      static void stamp(int i) {
         if (q == null) {
            q = new int[16];
            GL15.glGenQueries(q);
         }
         GL33.glQueryCounter(q[slot * 2 + i], GL33.GL_TIMESTAMP);
      }

      static void collect() {
         pending[slot] = true;
         slot = (slot + 1) % 8;
         if (pending[slot] && GL15.glGetQueryObjecti(q[slot * 2 + 1], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
            sum += GL33.glGetQueryObjecti64(q[slot * 2 + 1], GL15.GL_QUERY_RESULT) - GL33.glGetQueryObjecti64(q[slot * 2], GL15.GL_QUERY_RESULT);
            if (++count == 600) {
               Log.info(String.format("pixel light shadow mask gpu: %.1f us (600 frames) epoch_ms=%d", sum / 6e5, System.currentTimeMillis()));
               sum = count = 0;
            }
         }
         pending[slot] = false;
      }
   }

   /** Game thread, right after FBORenderChunkManager.endFrame(): the pass (pass mode) and the dev dumps of the static world. */
   public static void afterComposite(int playerIndex) {
      if (!Config.DEV_PPL_DUMP_AT.isEmpty()) {
         scheduledDumps(playerIndex);
      }
      if (pendingPass != null) {
         SpriteRenderer.instance.drawGeneric(pendingPass);
         pendingPass = null;
      }
      if (Config.DEV_PPL_TIMING) {
         SpriteRenderer.instance.drawGeneric(COMPOSITE_END);
      }
      if (ACTIVE && shadowLight >= 0) {
         SpriteRenderer.instance.drawGeneric(SHADOW_PASS); // the next frame's composite reads it (reprojected)
      }
      if (pendingLitDump != null) {
         Dump d = new Dump();
         d.tag = pendingLitDump + "-lit";
         pendingLitDump = null;
         SpriteRenderer.instance.drawGeneric(d);
      }
   }

   /** Render thread, after the composite: the shadow mask of this frame's torch from the scene depth. */
   private static final TextureDraw.GenericDrawer SHADOW_PASS = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         try {
            GL.shadowPass();
         } catch (Throwable t) {
            Log.warn("pixel light: shadow pass failed, shadows off: " + t);
            GL.shadowFailed = true;
         }
      }
   };

   /** Render thread: the composite shader back to the stock light (the mode was switched off). */
   private static final TextureDraw.GenericDrawer OFF = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         Gl.wantOn = false;
         Gl.serial++;
      }
   };

   static volatile zombie.viewCone.ChunkRenderShader baseShader; // the light-free variant (render thread)
   static volatile zombie.viewCone.ChunkRenderShader stockShader; // dev (cost bit 256): the unpatched stock program
   private static String stockFrag;
   public static long baseDraws, fullDraws, drawLights, culled, mergedPoints;

   /**
    * Render thread, ChunkRenderShader.startRenderThread (that shader's program is bound, its DEPTH and chunkDepth set): the
    * program for this chunk texture is the variant compiled for the kinds of light its list holds (none: the light-free
    * base, 32 registers; the full program 48, 10 waves instead of 16 on the flip's RDNA 3.5), switched through the game's
    * program cache; the first draw of a frame on each program sets its light uniforms, every draw its light list.
    */
   public static void chunkDraw(zombie.core.opengl.Shader shader, TextureDraw texd) {
      try {
         zombie.viewCone.ChunkRenderShader stock = stockShader;
         if (shader == stock) {
            return;
         }
         if ((costMask & 256) != 0 && stock != null) {
            zombie.core.ShaderHelper.glUseProgramObjectARB(stock.getID()); // dev: the stock program on the same (unlit) bakes, cost only
            stock.startRenderThread(texd);
            return;
         }
         int bits = ACTIVE && Gl.wantOn && !failed ? GL.lightBits(texd.tex1) : -1;
         zombie.core.opengl.Shader want = bits == -1 ? null : GL.variantFor(bits);
         if (want != null && want != shader) {
            zombie.core.ShaderHelper.glUseProgramObjectARB(want.getID()); // through the game's cache: its per-draw ModelViewProjection goes to the bound program
            ((zombie.viewCone.ChunkRenderShader)want).startRenderThread(texd); // DEPTH and chunkDepth on that program, then back here
            return;
         }
         if (shader == baseShader) {
            baseDraws++;
         } else {
            fullDraws++;
            drawLights += bits == -1 ? GL.lights : Integer.bitCount(bits);
         }
         int program = shader.getProgram().getShaderID();
         Integer applied = GL.appliedSerials.get(program);
         if (applied == null || applied != Gl.serial) {
            GL.appliedSerials.put(program, Gl.serial);
            GL.setChunkUniforms(program);
            if (Config.DEV_PPL_TRACE) {
               Integer k = GL.chunkIndex.get(texd.tex1);
               Log.info(String.format(java.util.Locale.ROOT, "ppl rtrace: ms=%d serial=%d o=%d,%d d0=%.6f chunk=%s chunkDepth=%.6f", System.currentTimeMillis(), Gl.serial,
                     GL.ox, GL.oy, GL.d0, k == null ? "?" : GL.chunkRect[k * 4] + "," + GL.chunkRect[k * 4 + 1] + "," + GL.chunkRect[k * 4 + 2], texd.chunkDepth));
            }
         }
         if (shader != baseShader && bits != -1) {
            GL.selectLights(program, bits);
         }
         GL.selectLevels(program, texd.tex1);
      } catch (Throwable t) {
         fail("chunk uniforms: " + t);
      }
   }

   static final int V_NO_POINT = 1, V_NO_TORCH = 2, V_NO_WET = 4, V_NO_MASK = 8, V_BASE = 16, V_COPY = 32;
   private static final java.util.HashMap<Integer, Integer> programKeys = new java.util.HashMap<>(); // variant programs (dev tint)
   private static String variantDefines = "#define PPL_BASE\n"; // read by patchShader while a variant compiles

   /** Render thread: a variant program of the chunk composite (pzopt_chunkBase's placeholder, the source from here), or null. */
   static zombie.viewCone.ChunkRenderShader compileVariant(int key) {
      StringBuilder d = new StringBuilder();
      if ((key & V_BASE) != 0) {
         d.append("#define PPL_BASE\n");
         if ((key & V_COPY) != 0) d.append("#define PPL_COPY\n"); // dev: a second, identical program
      } else {
         if ((key & V_NO_POINT) != 0) d.append("#define PPL_NO_POINT\n");
         if ((key & V_NO_TORCH) != 0) d.append("#define PPL_NO_TORCH\n");
         if ((key & V_NO_WET) != 0) d.append("#define PPL_NO_WET\n");
         if ((key & V_NO_MASK) != 0) d.append("#define PPL_NO_MASK\n");
      }
      variantDefines = d.toString();
      try {
         zombie.viewCone.ChunkRenderShader v = new zombie.viewCone.ChunkRenderShader("pzopt_chunkBase");
         if (v.getProgram() != null && v.isCompiled()) {
            programKeys.put(v.getProgram().getShaderID(), key);
            Log.info("pixel light: chunk program variant " + key + " = " + v.getID() + " (" + variantDefines.replace("#define ", "").replace('\n', ' ').trim() + ")");
            return v;
         }
         Log.warn("pixel light: chunk program variant " + key + " did not compile; the full program instead");
      } catch (Throwable t) {
         Log.warn("pixel light: chunk program variant " + key + " failed (" + t + "); the full program instead");
      }
      return null;
   }

   /**
    * What no light can change in a chunk texture: 1 its squares' light is saturated (daylight: nothing adds to it; over the
    * eight chunks around it too, the light between square centres reaches half a square across the border), 2 none of its
    * squares shows the torch (fog of war, out of reach; its own squares: a half-square soft edge at a chunk border that is
    * also the visibility boundary becomes hard). Over its levels; not packed yet: nothing.
    */
   private static int chunkFlags(IsoChunk c, int z0, int z1) {
      IsoCell cell = IsoWorld.instance.currentCell;
      int own = 3;
      for (int z = z0; z <= z1 && own != 0; z++) {
         int li = z + 32;
         own &= li >= 0 && li < 64 && z >= c.minLevel && z <= c.maxLevel ? c.pzoptPplFlags[li] : 3;
      }
      int and = own & 1; // saturation: the neighbours too
      for (int dy = -1; dy <= 1 && and != 0; dy++) {
         for (int dx = -1; dx <= 1 && and != 0; dx++) {
            IsoChunk n = dx == 0 && dy == 0 ? c : cell.getChunk(c.wx + dx, c.wy + dy);
            if (n == null) {
               continue; // nothing drawn there
            }
            for (int z = z0; z <= z1 && and != 0; z++) {
               int li = z + 32;
               and &= li >= 0 && li < 64 && z >= n.minLevel && z <= n.maxLevel ? n.pzoptPplFlags[li] : 3;
            }
         }
      }
      return and | own & 2;
   }

   static final int MAX_LIGHTS = 16;
   private static final float[] candD = new float[4096];
   private static final IsoLightSource[] candL = new IsoLightSource[4096];

   /**
    * The dynamic light sources near the view for the shader's per-pixel shaping, relative to the origin square: torches and
    * headlights (as sent to the native: position, direction, cone, reach) first, then the nearest active point lights
    * (lamps, fires). a = (x, y, z, reach), b = (dir x, dir y, cone cos or -2 for a point light, strength).
    */
   private static int gatherLights(Frame f, int playerIndex) {
      float cx = IsoCamera.frameState.camCharacterX, cy = IsoCamera.frameState.camCharacterY;
      float view = (f.screenW + 2.0F * f.screenH) * f.zoom / (64.0F * f.ts) + 4.0F; // squares from the centre to a screen corner, generously
      int count = 0;
      ArrayList<IsoGameCharacter.TorchInfo> torches = LightingJNI.pzoptTorches();
      for (int i = 0; i < torches.size() && count < MAX_LIGHTS; i++) {
         IsoGameCharacter.TorchInfo t = torches.get(i);
         if (t.id == 0 || Math.abs(t.x - cx) > view + t.dist || Math.abs(t.y - cy) > view + t.dist) {
            continue;
         }
         float len = (float)Math.sqrt(t.angleX * t.angleX + t.angleY * t.angleY);
         if (len < 1e-4F) {
            continue;
         }
         boolean merged = false; // the game sends every active light item of a player at the same spot (torch + another): the native takes the brightest
         for (int j = 0; j < count && !merged; j++) {
            int q = j * 4;
            if (Math.abs(f.la[q] - (t.x - f.ox)) < 0.01F && Math.abs(f.la[q + 1] - (t.y - f.oy)) < 0.01F && Math.abs(f.lb[q] - t.angleX / len) < 0.01F
               && Math.abs(f.lb[q + 1] - t.angleY / len) < 0.01F && f.lc[q + 3] == (t.id >= 4096 || t.focusing > 0 ? 2.0F : 1.0F)) {
               merged = true;
               if (t.strength * Math.max(1.0F, t.dist) > f.lb[q + 3] * f.la[q + 3]) { // the stronger one (reach x strength)
                  f.la[q + 3] = Math.max(1.0F, t.dist);
                  f.lb[q + 2] = t.cone ? t.dot : -2.0F;
                  f.lb[q + 3] = t.strength;
               }
            }
         }
         if (merged) {
            continue;
         }
         int k = count * 4;
         f.la[k] = t.x - f.ox;
         f.la[k + 1] = t.y - f.oy;
         f.la[k + 2] = t.z;
         f.la[k + 3] = Math.max(1.0F, t.dist);
         f.lb[k] = t.angleX / len;
         f.lb[k + 1] = t.angleY / len;
         f.lb[k + 2] = t.cone ? t.dot : -2.0F;
         f.lb[k + 3] = t.strength;
         f.lc[k] = t.r;
         f.lc[k + 1] = t.g;
         f.lc[k + 2] = t.b;
         f.lc[k + 3] = t.id >= 4096 || t.focusing > 0 ? 2.0F : 1.0F; // 1: a handheld torch (replaces the native's); 2: a vehicle light (sharpens the native's)
         count++;
      }
      IsoCell cell = IsoWorld.instance.currentCell;
      java.util.Stack<IsoLightSource> list = cell.getLamppostPositions();
      int nc = 0;
      for (int i = 0; Config.PPL_POINT_LIGHTS && i < list.size() && nc < candL.length; i++) {
         IsoLightSource l = list.get(i);
         if (!l.active || l.radius <= 0) {
            continue;
         }
         float dx = l.x + 0.5F - cx, dy = l.y + 0.5F - cy;
         float r = Math.min(l.radius, 20);
         if (Math.abs(dx) > view + r || Math.abs(dy) > view + r) {
            continue;
         }
         candD[nc] = dx * dx + dy * dy;
         candL[nc++] = l;
      }
      while (count < MAX_LIGHTS && nc > 0) { // nearest first (a handful: selection, not a sort)
         int best = 0;
         for (int i = 1; i < nc; i++) {
            if (candD[i] < candD[best]) {
               best = i;
            }
         }
         IsoLightSource l = candL[best];
         candD[best] = candD[--nc];
         candL[best] = candL[nc];
         float lr = Math.min(1.0F, Math.max(0.0F, l.r * 2.0F)), lg = Math.min(1.0F, Math.max(0.0F, l.g * 2.0F)), lb = Math.min(1.0F, Math.max(0.0F, l.b * 2.0F));
         if (mergePoint(f, count, l.x + 0.5F - f.ox, l.y + 0.5F - f.oy, l.z, Math.min(l.radius, 20), lr, lg, lb)) {
            continue; // a fire's burning squares, a lamp pair: one light (the shader's loop runs once per light per pixel)
         }
         int k = count * 4;
         f.la[k] = l.x + 0.5F - f.ox;
         f.la[k + 1] = l.y + 0.5F - f.oy;
         f.la[k + 2] = l.z;
         f.la[k + 3] = Math.min(l.radius, 20);
         f.lb[k] = 0.0F;
         f.lb[k + 1] = 0.0F;
         f.lb[k + 2] = -2.0F;
         f.lb[k + 3] = 1.0F;
         f.lc[k] = Math.min(1.0F, Math.max(0.0F, l.r * 2.0F)); // as LightingJNI hands it to the native
         f.lc[k + 1] = Math.min(1.0F, Math.max(0.0F, l.g * 2.0F));
         f.lc[k + 2] = Math.min(1.0F, Math.max(0.0F, l.b * 2.0F));
         f.lc[k + 3] = 0.0F; // a point light: the brightest of it and the rest, per channel
         count++;
      }
      java.util.Arrays.fill(candL, 0, candL.length, null);
      return count;
   }

   /**
    * A point light within 2 squares of one already in the table, on its level, of about its colour, joins it: the reach
    * grows to cover both (max(r, r' + distance)), which is never brighter than the pair; where it is a little dimmer (at
    * the joined light's centre) the native's field, which the shader takes the maximum with, carries it.
    */
   private static boolean mergePoint(Frame f, int count, float x, float y, float z, float r, float cr, float cg, float cb) {
      for (int j = 0; j < count; j++) {
         int q = j * 4;
         if (f.lc[q + 3] != 0.0F || f.la[q + 2] != z) {
            continue;
         }
         float dx = f.la[q] - x, dy = f.la[q + 1] - y, dist = (float)Math.sqrt(dx * dx + dy * dy);
         if (dist <= 2.0F && Math.abs(f.lc[q] - cr) < 0.08F && Math.abs(f.lc[q + 1] - cg) < 0.08F && Math.abs(f.lc[q + 2] - cb) < 0.08F) {
            f.la[q + 3] = Math.max(f.la[q + 3], r + dist);
            f.lc[q] = Math.max(f.lc[q], cr);
            f.lc[q + 1] = Math.max(f.lc[q + 1], cg);
            f.lc[q + 2] = Math.max(f.lc[q + 2], cb);
            mergedPoints++;
            return true;
         }
      }
      return false;
   }

   public static String stats() {
      return "ppl: frames=" + frames + " blocks=" + blocksUploaded + " fullFrames=" + framesFull + " bakes=" + bakes + " whitened=" + whitenedSquares
         + " chunk draws light-free=" + baseDraws + " with lights=" + fullDraws + String.format(" (%.1f lights each)", fullDraws > 0 ? (double)drawLights / fullDraws : 0.0) + " lights culled (saturated / hidden / cone)=" + culled + " point lights merged=" + mergedPoints
         + " squares simple=" + packSimple + " hidden=" + packHidden + " slow=" + packSlow
         + String.format(" pack=%.1fus/frame", frames > 0 ? packNs / 1e3 / frames : 0.0)
         + (failed ? " FAILED" : "") + (Gl.passNs > 0 ? String.format(" gpu pass=%.1fus upload=%.1fus", Gl.passNs / 1e3, Gl.uploadNs / 1e3) : "");
   }

   private static Frame freeFrame() {
      for (int i = 0; i < RING.size(); i++) {
         Frame f = RING.get(i);
         if (f.free) {
            f.free = false;
            return f;
         }
      }
      Frame f = new Frame();
      RING.add(f);
      f.free = false;
      if (RING.size() > 6) {
         Log.warn("pixel light: " + RING.size() + " frames in flight");
      }
      return f;
   }

   /**
    * One chunk level: two 16x16 RGBA8 corner blocks (bottom, top; corner c of square (x, y) at texel (2x + (c == 1 || c == 2),
    * 2y + (c >= 2))) and an 8x8 info block (rgb: the square's own light, the native's sample at its centre; a: which of the
    * eight neighbours it is connected to, i.e. shares its corner colours with: the native breaks them at walls between a lit
    * room and the dark outside).
    */
   private static void pack(Frame f, IsoChunk c, int z, int playerIndex) {
      ByteBuffer b = f.buf();
      int base = f.blocks * BLOCK_BYTES;
      IsoCell cell = IsoWorld.instance.currentCell;
      packChunk = c;
      boolean allSat = true, allHidden = true;
      for (int y = 0; y < 8; y++) {
         for (int x = 0; x < 8; x++) {
            IsoGridSquare sq = c.getGridSquare(x, y, z);
            boolean above = false;
            if (sq == null && z > c.maxLevel) {
               sq = c.getGridSquare(x, y, c.maxLevel); // above the top: the top corners of the level below, for both layers
               above = true;
            }
            int v0 = 0, v1 = 0, v2 = 0, v3 = 0, t0 = 0, t1 = 0, t2 = 0, t3 = 0;
            int info = 0, conn = 0, tvis = 255;
            if (sq != null && sq.lighting[playerIndex] instanceof LightingJNI.JNILighting jl) {
               if (above) {
                  v0 = t0 = jl.pzoptVert(4);
                  v1 = t1 = jl.pzoptVert(5);
                  v2 = t2 = jl.pzoptVert(6);
                  v3 = t3 = jl.pzoptVert(7);
                  info = meanAbgr(v0, v1, v2, v3);
                  conn = 255;
               } else {
                  v0 = jl.pzoptVert(0);
                  v1 = jl.pzoptVert(1);
                  v2 = jl.pzoptVert(2);
                  v3 = jl.pzoptVert(3);
                  t0 = jl.pzoptVert(4);
                  t1 = jl.pzoptVert(5);
                  t2 = jl.pzoptVert(6);
                  t3 = jl.pzoptVert(7);
                  zombie.core.textures.ColorInfo li = jl.pzoptInfo();
                  // the native adds the torches (the brightest of them) to the square's light: base = light - torch; a
                  // saturated square's base is the ambient (unknown under the clamp)
                  float tr = 0.0F, tg = 0.0F, tb = 0.0F;
                  for (int k = 0; Config.PPL_ANALYTIC && k < jl.resultLightCount(); k++) {
                     zombie.iso.IsoGridSquare.ResultLight rl = jl.getResultLight(k);
                     if ((rl.flags & 2) != 0 && rl.id < 4096) { // handheld torches (vehicle lights, 4096 + vehicle * 10 + light, stay in the base: they move too fast for the replacement)
                        tr = Math.max(tr, rl.r);
                        tg = Math.max(tg, rl.g);
                        tb = Math.max(tb, rl.b);
                     }
                  }
                  float tmax = Math.max(tr, Math.max(tg, tb)), imax = Math.max(li.r, Math.max(li.g, li.b));
                  if (tmax > 0.02F && imax < 0.9F * tmax || tmax <= 0.02F && (jl.pzoptVis() & 2) == 0 && torchNear(sq.x + 0.5F, sq.y + 0.5F, z)) {
                     // the native lists the torch here but did not add it (a wall hides it, or the square is dark for the
                     // player): nothing to take out, and the torch stays off here. No entry at all: the model decides where
                     // the player can see the square (one the torch has just turned to is not lit by the native yet; the
                     // torch points where the player looks), never where the player cannot (fog of war)
                     tr = tg = tb = 0.0F;
                     tvis = 0;
                  }
                  // a clamped channel hides the light under the torch; all three take the ambient estimate then (one channel
                  // alone turned the base blue or teal where a warm torch clips red and green first: a ring at the torch's
                  // reach under a bright storm ambient)
                  boolean outside = sq.isOutside();
                  boolean clipped = tmax >= 0.99F || tmax > 0.0F && Math.max(li.r, Math.max(li.g, li.b)) >= 0.999F;
                  int est = outside ? ambOut : ambIn;
                  float er = est >= 0 ? (est & 0xFF) / 255.0F : ambR, eg = est >= 0 ? (est >> 8 & 0xFF) / 255.0F : ambG, eb = est >= 0 ? (est >> 16 & 0xFF) / 255.0F : ambB;
                  float br = clipped ? Math.min(li.r, er) : Math.max(0.0F, li.r - tr);
                  float bg = clipped ? Math.min(li.g, eg) : Math.max(0.0F, li.g - tg);
                  float bb = clipped ? Math.min(li.b, eb) : Math.max(0.0F, li.b - tb);
                  info = Math.min(255, (int)(br * 255.0F + 0.5F)) | Math.min(255, (int)(bg * 255.0F + 0.5F)) << 8 | Math.min(255, (int)(bb * 255.0F + 0.5F)) << 16;
                  if ((jl.pzoptVis() & 1) != 0 && tr + tg + tb == 0.0F && li.r + li.g + li.b > 0.0F) {
                     ambientSample(outside ? 0 : 1, (int)(li.r * 255.0F + 0.5F) | (int)(li.g * 255.0F + 0.5F) << 8 | (int)(li.b * 255.0F + 0.5F) << 16);
                  }
                  if ((jl.pzoptVis() & 1) != 0 && tr + tg + tb == 0.0F && li.r + li.g + li.b > 0.0F && li.r < ambR + 0.5F) {
                     ambR = Math.min(ambR, li.r); // a seen square without torch light: the ambient is at most its light
                     ambG = Math.min(ambG, li.g);
                     ambB = Math.min(ambB, li.b);
                  }
                  int wx = sq.x, wy = sq.y;
                  // E, S, W, N, SE, SW, NW, NE: shared corners equal
                  if (same(v1, corner(cell, wx + 1, wy, z, 0, playerIndex)) && same(v2, corner(cell, wx + 1, wy, z, 3, playerIndex))) conn |= 1;
                  if (same(v3, corner(cell, wx, wy + 1, z, 0, playerIndex)) && same(v2, corner(cell, wx, wy + 1, z, 1, playerIndex))) conn |= 2;
                  if (same(v0, corner(cell, wx - 1, wy, z, 1, playerIndex)) && same(v3, corner(cell, wx - 1, wy, z, 2, playerIndex))) conn |= 4;
                  if (same(v0, corner(cell, wx, wy - 1, z, 3, playerIndex)) && same(v1, corner(cell, wx, wy - 1, z, 2, playerIndex))) conn |= 8;
                  if ((conn & 3) == 3 && same(v2, corner(cell, wx + 1, wy + 1, z, 0, playerIndex))) conn |= 16;
                  if ((conn & 6) == 6 && same(v3, corner(cell, wx - 1, wy + 1, z, 1, playerIndex))) conn |= 32;
                  if ((conn & 12) == 12 && same(v0, corner(cell, wx - 1, wy - 1, z, 2, playerIndex))) conn |= 64;
                  if ((conn & 9) == 9 && same(v1, corner(cell, wx + 1, wy - 1, z, 3, playerIndex))) conn |= 128;
               }
            }
            // the corners' vertical gradient (a ceiling brighter or darker than the floor): walls need the corner layers only then
            int grad = v0 != t0 || v1 != t1 || v2 != t2 || v3 != t3 ? 255 : 0;
            // a: 255 a simple square with the torch visible, 0 a simple square with the torch hidden (fog of war: at night most of
            // the screen), 128 not simple. Only all-255 or all-0 survive the bilinear exactly (the extremes of a mean), so the
            // shader's one fetch knows both the light and the torch visibility there. (A vertical gradient does not count: walls
            // fetch their own texel.)
            // (not simple: 192 torch visible, 64 hidden, so the edge path reads the visibility from the same four fetches)
            int simple = conn != 255 ? (tvis == 255 ? 192 : 64) : tvis == 255 ? 255 : 0;
            if (sq != null && !above) { // (no square: no pixels; above the top: the level below's)
               allSat &= (info & 0xFF) >= 252 && (info >> 8 & 0xFF) >= 252 && (info >> 16 & 0xFF) >= 252;
               allHidden &= tvis == 0 || !torchNear(sq.x + 0.5F, sq.y + 0.5F, z); // no torch there: none to hide
            }
            if (conn != 255) packSlow++; else if (simple == 0) packHidden++; else packSimple++;
            if (Config.DEV_PPL_TRACE && sq != null && !above) {
               traceSq++;
               traceLum += (info & 0xFF) + (info >> 8 & 0xFF) + (info >> 16 & 0xFF);
               if (sq.lighting[playerIndex] instanceof LightingJNI.JNILighting tj && (tj.pzoptVis() & 1) != 0) traceSeen++;
            }
            int cell8 = (y * 8 + x) * 4;
            b.putInt(base + cell8, info & 0xFFFFFF | simple << 24); // base light; a: a simple square (the shader's one-fetch path)
            int outdoor = sq != null && sq.isOutside() ? 255 : 0;
            b.putInt(base + 256 + cell8, conn | tvis << 8 | grad << 16 | outdoor << 24); // connectivity bits, torch visibility, vertical gradient, outdoors (wet in rain)
            b.putInt(base + 512 + cell8, grad == 0 ? 0x808080 : wallDelta(v0, v1, v2, v3, t0, t1, t2, t3)); // top corners' mean - bottom corners' mean, 0.5 = none
         }
      }
      if (z + 32 >= 0 && z + 32 < 64) {
         c.pzoptPplFlags[z + 32] = (byte)((allSat ? 1 : 0) | (allHidden ? 2 : 0));
      }
      f.bx[f.blocks] = Math.floorMod(c.wx * 8, f.n);
      f.by[f.blocks] = Math.floorMod(c.wy * 8, f.n);
      f.bl[f.blocks] = z & (LEVELS - 1);
      f.blocks++;
   }

   // the ambient under saturated torch light: the smallest light of a seen square without torch light, kept across frames
   // (reset upwards slowly so dusk and dawn follow); the fallback until the estimates below have samples
   private static float ambR = 1.0F, ambG = 1.0F, ambB = 1.0F;
   // the ambient outdoors / indoors: the most common light among the seen squares without torch light packed in a frame
   // (outdoors without a lamp every square has exactly the ambient), kept until a frame has enough samples; -1 unknown
   private static int ambOut = -1, ambIn = -1;
   private static final int[] ambKeys = new int[2 * 64], ambCounts = new int[2 * 64], ambTotal = new int[2];

   private static void ambientSample(int cls, int rgb) {
      int base = cls * 64, h = (rgb * 0x9E3779B1) >>> 26;
      for (int k = 0; k < 64; k++) {
         int i = base + (h + k & 63);
         if (ambCounts[i] == 0) {
            ambKeys[i] = rgb;
            ambCounts[i] = 1;
            ambTotal[cls]++;
            return;
         }
         if (ambKeys[i] == rgb) {
            ambCounts[i]++;
            ambTotal[cls]++;
            return;
         }
      }
   }

   /** After a frame's packs: the modes become the estimates (enough samples only), the tables are cleared. */
   private static void ambientCommit() {
      for (int cls = 0; cls < 2; cls++) {
         if (ambTotal[cls] >= 24) {
            int best = -1, bestN = 0;
            for (int i = cls * 64; i < cls * 64 + 64; i++) {
               if (ambCounts[i] > bestN) {
                  bestN = ambCounts[i];
                  best = ambKeys[i];
               }
            }
            if (bestN * 4 >= ambTotal[cls]) { // a clear mode (a quarter of the samples or more)
               if (cls == 0) ambOut = best; else ambIn = best;
            }
         }
         ambTotal[cls] = 0;
      }
      java.util.Arrays.fill(ambCounts, 0);
   }
   private static IsoChunk packChunk;

   /** A handheld torch could reach this point (the frame's torch list, reach + a square). */
   private static boolean torchNear(float x, float y, int z) {
      ArrayList<IsoGameCharacter.TorchInfo> torches = LightingJNI.pzoptTorches();
      for (int i = 0; i < torches.size(); i++) {
         IsoGameCharacter.TorchInfo t = torches.get(i);
         if (t.id == 0 || t.id >= 4096 || Math.abs(t.z - z) > 1.5F) {
            continue;
         }
         float dx = x - t.x, dy = y - t.y, r = Math.max(1.0F, t.dist) + 1.0F;
         if (dx * dx + dy * dy < r * r) {
            return true;
         }
      }
      return false;
   }

   /** No square there (a lit corner always has alpha 255; -1 would be a white corner). */
   private static final int NO_SQUARE = 0x00FFFFFF;

   /** A neighbour square's corner colour as lit (NO_SQUARE: none), for the connectivity bits. */
   private static int corner(IsoCell cell, int x, int y, int z, int i, int playerIndex) {
      IsoChunk c = packChunk;
      int lx = x - c.wx * 8, ly = y - c.wy * 8;
      IsoGridSquare sq = lx >= 0 && lx < 8 && ly >= 0 && ly < 8 ? c.getGridSquare(lx, ly, z) : cell.getGridSquare(x, y, z); // inside the chunk: no cell lookup
      return sq != null && sq.lighting[playerIndex] instanceof LightingJNI.JNILighting jl ? jl.pzoptVert(i) : NO_SQUARE;
   }

   /** Per channel 128 + (top mean - bottom mean) / 2 of the corner colours: a wall's light from its foot to its top. */
   private static int wallDelta(int v0, int v1, int v2, int v3, int t0, int t1, int t2, int t3) {
      int out = 0;
      for (int sh = 0; sh < 24; sh += 8) {
         int bot = (v0 >> sh & 0xFF) + (v1 >> sh & 0xFF) + (v2 >> sh & 0xFF) + (v3 >> sh & 0xFF);
         int top = (t0 >> sh & 0xFF) + (t1 >> sh & 0xFF) + (t2 >> sh & 0xFF) + (t3 >> sh & 0xFF);
         out |= Math.max(0, Math.min(255, 128 + (top - bot) / 8)) << sh;
      }
      return out;
   }

   private static boolean same(int a, int b) {
      if (b == NO_SQUARE) {
         return false;
      }
      return Math.abs((a & 0xFF) - (b & 0xFF)) <= 3 && Math.abs((a >> 8 & 0xFF) - (b >> 8 & 0xFF)) <= 3 && Math.abs((a >> 16 & 0xFF) - (b >> 16 & 0xFF)) <= 3;
   }

   private static int meanAbgr(int a, int b, int c, int d) {
      int r = ((a & 0xFF) + (b & 0xFF) + (c & 0xFF) + (d & 0xFF)) / 4;
      int g = ((a >> 8 & 0xFF) + (b >> 8 & 0xFF) + (c >> 8 & 0xFF) + (d >> 8 & 0xFF)) / 4;
      int bl = ((a >> 16 & 0xFF) + (b >> 16 & 0xFF) + (c >> 16 & 0xFF) + (d >> 16 & 0xFF)) / 4;
      return r | g << 8 | bl << 16;
   }

   private static int toggleNext;
   private static float[] toggleAt;

   /** devPplToggleAt=s1,s2: the mode flips at those seconds after the world is up (every texture re-baked), for same-scene A/Bs. */
   private static void scheduledToggles() {
      if (toggleAt == null) {
         String[] parts = Config.DEV_PPL_TOGGLE_AT.split(",");
         toggleAt = new float[parts.length];
         for (int i = 0; i < parts.length; i++) {
            toggleAt[i] = Float.parseFloat(parts[i].trim());
         }
      }
      if (toggleNext >= toggleAt.length || worldUpNs == 0L || failed) {
         return;
      }
      if ((System.nanoTime() - worldUpNs) / 1e9 >= toggleAt[toggleNext]) {
         toggleNext++;
         unwhitenAll();
         ACTIVE = !ACTIVE;
         slotChunk = null;
         n = 0; // every lattice block again
         rebakeAll = true;
         Log.info("pixel light: dev toggle, now " + (ACTIVE ? "per-pixel" : "stock baked light") + " epoch_ms=" + System.currentTimeMillis());
      }
   }

   private static void rebakeEverything() {
      IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
      if (cell == null) {
         return;
      }
      int count = 0;
      for (int p = 0; p < 4; p++) {
         IsoChunkMap cm = cell.chunkMap[p];
         if (cm == null) {
            continue;
         }
         for (int cy = 0; cy < IsoChunkMap.chunkGridWidth; cy++) {
            for (int cx = 0; cx < IsoChunkMap.chunkGridWidth; cx++) {
               IsoChunk c = cm.getChunk(cx, cy);
               if (c != null) {
                  c.getRenderLevels(p).invalidateAll(32L);
                  count++;
               }
            }
         }
      }
      Log.info("pixel light: " + count + " chunks re-baked with the stock light");
   }

   static final int BLOCK_BYTES = 768; // per chunk level, 8x8 RGBA8 each: base light + simple flag, connectivity + torch visibility + gradient flag, wall gradient
   static final int MAX_BLOCKS = 1024;

   /** One frame: the lattice blocks to upload and the camera, rendered in stream order before the composite (or after it: the pass). */
   static final class Frame extends TextureDraw.GenericDrawer {
      volatile boolean free = true;
      final ByteBuffer data = BufferUtils.createByteBuffer(64 * BLOCK_BYTES).order(ByteOrder.LITTLE_ENDIAN);
      ByteBuffer big; // grown on demand up to MAX_BLOCKS
      int[] bx = new int[MAX_BLOCKS], by = new int[MAX_BLOCKS], bl = new int[MAX_BLOCKS];
      int blocks, n;
      boolean composite;
      float zoom, offX, offY, d0, jx, jy;
      int ts, screenW, screenH, ox, oy;
      int lights, shadowLight, chunks;
      float wet;
      final zombie.core.textures.Texture[] chunkKeys = new zombie.core.textures.Texture[1024];
      final float[] chunkRect = new float[1024 * 4];
      final int[] chunkFlags = new int[1024];
      final float[] la = new float[MAX_LIGHTS * 4], lb = new float[MAX_LIGHTS * 4], lc = new float[MAX_LIGHTS * 4];

      ByteBuffer buf() {
         return this.big != null ? this.big : this.data;
      }

      boolean room() {
         if (this.blocks < 64) {
            return true;
         }
         if (this.blocks >= MAX_BLOCKS) {
            return false;
         }
         if (this.big == null) {
            this.big = BufferUtils.createByteBuffer(MAX_BLOCKS * BLOCK_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            this.big.put(0, this.data, 0, this.blocks * BLOCK_BYTES);
         }
         return true;
      }

      @Override
      public void render() {
         try {
            GL.render(this);
         } catch (Throwable t) {
            fail("render: " + t);
         }
      }

      /**
       * The state that holds this frame is recycled (game thread, GenericSpriteRenderState.clear): only now can the frame be
       * reused. Not after render(): the render thread renders its last state again while the game thread is late (a chunk
       * crossing, the chunk map shift), and a frame already refilled with the next frame's camera put the lattice mapping one
       * chunk away from the replayed composite's depth for that frame (a whole screen lit from the level above: black, or
       * hidden rooms lit; the flip report, 2026-09-25).
       */
      @Override
      public void postRender() {
         java.util.Arrays.fill(this.chunkKeys, 0, Math.min(this.chunks, this.chunkKeys.length), null); // the depth textures, no longer referenced
         this.free = true;
      }
   }

   // ------------------------------------------------------------------------------------------------ render thread

   private static final Gl GL = new Gl();
   static final int LATTICE_UNIT = 12; // the texture unit the lattice stays bound to for the chunk composite
   static final int INFO_UNIT = 11; // and the per-square light
   static final int VIS_UNIT = 10; // and the per-square torch visibility
   static final int MASK_UNIT = 9; // and the previous frame's torch shadow mask (not 4: FogPass and HdrGlint bind it, the fog between composites)

   static final class Gl {
      private int program, quadVbo, lattice, latticeN, info, vis;
      private final int[] u = new int[11];
      private final int[] viewport = new int[4];
      private final float[] viewportF = new float[4];
      private boolean logged;
      static long passNs, uploadNs;
      // the camera of the newest frame (render thread), what the composite's uniforms are made from
      static int serial;
      final java.util.HashMap<Integer, Integer> appliedSerials = new java.util.HashMap<>();
      static boolean wantOn, onSent;
      private float zoom, offX, offY, d0, jx, jy;
      private int ts, screenW, screenH, ox, oy, n, lights, shadowLight = -1;
      private float wet;
      private final java.util.IdentityHashMap<zombie.core.textures.Texture, Integer> chunkIndex = new java.util.IdentityHashMap<>();
      private final float[] chunkRect = new float[1024 * 4];
      private final int[] chunkFlags = new int[1024];
      private boolean baseTried;
      private int precompiled;
      // dry: torch and lamps, torch, lamps; then the same in the rain
      private static final int[] PRECOMPILE = {V_NO_WET | V_NO_MASK, V_NO_WET | V_NO_MASK | V_NO_POINT, V_NO_WET | V_NO_MASK | V_NO_TORCH, V_NO_MASK,
         V_NO_MASK | V_NO_POINT, V_NO_MASK | V_NO_TORCH};

      /**
       * The dynamic lights that reach the chunk texture drawn with this depth texture, one bit each (unknown: all): the
       * shader loops over these only (a tiled light list, one tile per chunk texture), none = the light-free variant.
       */
      private final java.util.HashMap<Integer, zombie.viewCone.ChunkRenderShader> variants = new java.util.HashMap<>();
      private boolean switchToggle;

      /** The program for a chunk texture with these lights: a variant without the kinds it does not need, null = the full one. */
      zombie.core.opengl.Shader variantFor(int bits) {
         if (!Config.PPL_VARIANTS || Config.DEV_PPL_VIEW != 0 || baseShader == null) {
            return null;
         }
         if ((costMask & 8192) != 0) { // dev: two identical light-free programs, alternating draw by draw (what a program switch costs)
            if (!this.variants.containsKey(V_BASE | V_COPY)) {
               this.variants.put(V_BASE | V_COPY, compileVariant(V_BASE | V_COPY));
            }
            zombie.viewCone.ChunkRenderShader copy = this.variants.get(V_BASE | V_COPY);
            return (this.switchToggle ^= true) || copy == null ? baseShader : copy;
         }
         if ((costMask & 512) != 0) {
            return baseShader; // dev: the light-free variant everywhere
         }
         boolean point = false, torch = false, vehicle = false, mask = false;
         for (int b = bits & ((1 << this.lights) - 1); b != 0; b &= b - 1) {
            int i = Integer.numberOfTrailingZeros(b);
            float kind = this.lc[i * 4 + 3];
            point |= kind == 0.0F;
            torch |= kind == 1.0F;
            vehicle |= kind == 2.0F;
            mask |= i == this.shadowLight;
         }
         boolean wet = this.wet * Config.PPL_SPEC_PCT / 100.0F > 0.004F;
         if (!point && !torch && !(vehicle && wet)) {
            return baseShader; // a vehicle light only lights the wet glints
         }
         int key = (point ? 0 : V_NO_POINT) | (torch ? 0 : V_NO_TORCH) | (wet ? 0 : V_NO_WET) | (mask && this.maskValid && Config.PPL_SHADOWS ? 0 : V_NO_MASK);
         if (key == 0) {
            return null;
         }
         if (!this.variants.containsKey(key)) {
            this.variants.put(key, compileVariant(key)); // once per kind of scene (a few ms, on first use)
         }
         return this.variants.get(key);
      }

      int lightBits(zombie.core.textures.Texture depth) {
         int all = (1 << this.lights) - 1;
         Integer k = depth == null ? null : this.chunkIndex.get(depth);
         if (k == null || Config.DEV_PPL_VIEW != 0) {
            return all;
         }
         int bits = 0;
         float x0 = this.chunkRect[k * 4], y0 = this.chunkRect[k * 4 + 1], z0 = this.chunkRect[k * 4 + 2], z1 = this.chunkRect[k * 4 + 3];
         // saturated light: a lamp or torch adds nothing under the clamp (unless wet: the glints go on top); every square
         // hiding the torch: it is multiplied by a visibility of 0
         boolean sat = (this.chunkFlags[k] & 1) != 0 && this.wet * Config.PPL_SPEC_PCT / 100.0F <= 0.004F, hidden = (this.chunkFlags[k] & 2) != 0;
         for (int i = 0; i < this.lights; i++) {
            float lx = this.la[i * 4], ly = this.la[i * 4 + 1], lz = this.la[i * 4 + 2], reach = this.la[i * 4 + 3];
            float kind = this.lc[i * 4 + 3];
            if (sat && kind < 1.5F || hidden && kind == 1.0F || kind >= 1.0F && !coneReaches(i, x0, y0)) {
               culled++;
               continue;
            }
            if (lz < z0 - 1.5F || lz > z1 + 1.5F) {
               continue;
            }
            float dx = Math.max(0.0F, Math.max(x0 - lx, lx - (x0 + 8.0F))), dy = Math.max(0.0F, Math.max(y0 - ly, ly - (y0 + 8.0F)));
            if (dx * dx + dy * dy < (reach + 1.5F) * (reach + 1.5F)) {
               bits |= 1 << i;
            }
         }
         if (bits == 0 && this.shadowLight >= 0 && Config.PPL_SHADOWS) {
            bits = 1 << this.shadowLight; // the mask's torch: the full program (it reads the mask) even out of its reach
         }
         return bits;
      }
      /**
       * Whether torch / headlight i can light any point of the chunk at (x0, y0) (its 8x8 squares plus 3 for tall sprites
       * drawn into it): the shader's cone (pplTorch: the ramp starts at the cone's cos + 0.025, a vehicle's at - 0.28) and
       * the spill at a holder's feet; grid samples plus both cone edges against the rectangle.
       */
      private boolean coneReaches(int i, float x0, float y0) {
         float ax = this.la[i * 4], ay = this.la[i * 4 + 1], reach = this.la[i * 4 + 3] + 1.0F;
         float dx = this.lb[i * 4], dy = this.lb[i * 4 + 1], cone = this.lb[i * 4 + 2];
         if (cone < -1.5F) {
            return true; // no cone: the reach test decided
         }
         boolean car = this.lc[i * 4 + 3] > 1.5F;
         float c0 = (car ? cone - 0.28F : cone + 0.025F) - 0.05F; // a little wider than the shader's ramp
         float m = 3.0F, rx0 = x0 - m, ry0 = y0 - m, rx1 = x0 + 8.0F + m, ry1 = y0 + 8.0F + m;
         float cx = Math.max(rx0, Math.min(ax, rx1)), cy = Math.max(ry0, Math.min(ay, ry1));
         if ((cx - ax) * (cx - ax) + (cy - ay) * (cy - ay) < 1.0F) {
            return true; // the holder is in or next to it (the spill, the cone's apex)
         }
         for (int gy = 0; gy <= 6; gy++) {
            for (int gx = 0; gx <= 6; gx++) {
               float px = rx0 + (rx1 - rx0) * gx / 6.0F - ax, py = ry0 + (ry1 - ry0) * gy / 6.0F - ay;
               float d = (float)Math.sqrt(px * px + py * py);
               if (d < reach && (px * dx + py * dy) > c0 * d) {
                  return true;
               }
            }
         }
         if (c0 <= -1.0F) {
            return true;
         }
         float s = (float)Math.sqrt(Math.max(0.0F, 1.0F - c0 * c0));
         for (int e = -1; e <= 1; e += 2) { // the cone's edges: dir rotated by +-acos(c0)
            float ex = dx * c0 - e * dy * s, ey = dy * c0 + e * dx * s;
            if (segmentHitsRect(ax, ay, ax + ex * reach, ay + ey * reach, rx0, ry0, rx1, ry1)) {
               return true;
            }
         }
         return false;
      }

      private static boolean segmentHitsRect(float x0, float y0, float x1, float y1, float rx0, float ry0, float rx1, float ry1) {
         float t0 = 0.0F, t1 = 1.0F, ddx = x1 - x0, ddy = y1 - y0;
         float[] p = {-ddx, ddx, -ddy, ddy}, q = {x0 - rx0, rx1 - x0, y0 - ry0, ry1 - y0};
         for (int k = 0; k < 4; k++) {
            if (p[k] == 0.0F) {
               if (q[k] < 0.0F) {
                  return false;
               }
            } else {
               float t = q[k] / p[k];
               if (p[k] < 0.0F) {
                  t0 = Math.max(t0, t);
               } else {
                  t1 = Math.min(t1, t);
               }
               if (t0 > t1) {
                  return false;
               }
            }
         }
         return true;
      }

      private final float[] la = new float[MAX_LIGHTS * 4], lb = new float[MAX_LIGHTS * 4], lc = new float[MAX_LIGHTS * 4];
      private final java.util.HashMap<Integer, int[]> selState = new java.util.HashMap<>(); // program -> {pplSel location, value sent}

      /** Per chunk draw on the full program: its light list (a uniform, sent only when it changes). */
      void selectLights(int program, int bits) {
         int[] st = this.selState.get(program);
         if (st == null) {
            st = new int[] {GL20.glGetUniformLocation(program, "pplSel"), -1};
            this.selState.put(program, st);
         }
         if (st[0] >= 0 && st[1] != bits) {
            GL20.glUniform1i(st[0], bits);
            st[1] = bits;
         }
      }

      private final java.util.HashMap<Integer, int[]> levelState = new java.util.HashMap<>(); // program -> {pplLv location, min, top sent}

      /** Per chunk draw on every program: the levels the chunk texture holds (a uniform, sent only when it changes). */
      void selectLevels(int program, zombie.core.textures.Texture depth) {
         int[] st = this.levelState.get(program);
         if (st == null) {
            st = new int[] {GL20.glGetUniformLocation(program, "pplLv"), Integer.MIN_VALUE, Integer.MIN_VALUE};
            this.levelState.put(program, st);
         }
         Integer k = depth == null ? null : this.chunkIndex.get(depth);
         int lo = k == null ? -64 : (int)this.chunkRect[k * 4 + 2], hi = k == null ? 64 : (int)this.chunkRect[k * 4 + 3];
         if (st[0] >= 0 && (st[1] != lo || st[2] != hi)) {
            GL20.glUniform2i(st[0], lo, hi);
            st[1] = lo;
            st[2] = hi;
         }
      }
      private final java.util.HashMap<Integer, int[]> chunkUniforms = new java.util.HashMap<>();
      private int diag;

      void render(Frame f) {
         if (failed) {
            return;
         }
         if ((costMask & 128) != 0 && this.latticeN == f.n) { // dev: bit 128, none of the frame's GL work (stale light, cost probe)
            serial++;
            wantOn = true;
            return;
         }
         if (this.latticeN != f.n) {
            this.allocLattice(f.n);
         }
         this.stamp(0);
         // 1. the frame's blocks: three 8x8 texel uploads per chunk level
         GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
         GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
         GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
         ByteBuffer b = f.buf();
         int[] targets = {this.info, this.vis, this.lattice};
         for (int t = 0; t < 3 && (costMask & 32) == 0; t++) { // dev: devPplCostAt bit 32 skips the uploads (stale light, cost probe)
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, targets[t]);
            for (int i = 0; i < f.blocks; i++) {
               GL12.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, f.bx[i], f.by[i], f.bl[i], 8, 8, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, b.slice(i * BLOCK_BYTES + t * 256, 256));
            }
         }
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
         this.stamp(1);
         // 2. the camera
         this.zoom = f.zoom;
         this.offX = f.offX;
         this.offY = f.offY;
         this.d0 = f.d0;
         this.jx = f.jx;
         this.jy = f.jy;
         this.ts = f.ts;
         this.screenW = f.screenW;
         this.screenH = f.screenH;
         this.ox = f.ox;
         this.oy = f.oy;
         this.n = f.n;
         this.lights = f.lights;
         this.wet = f.wet;
         this.chunkIndex.clear();
         for (int i = 0; i < f.chunks; i++) {
            this.chunkIndex.put(f.chunkKeys[i], i); // (the keys stay until postRender: a replayed state renders this frame again)
         }
         System.arraycopy(f.chunkRect, 0, this.chunkRect, 0, f.chunks * 4);
         System.arraycopy(f.chunkFlags, 0, this.chunkFlags, 0, f.chunks);
         if (Config.PPL_VARIANTS && f.composite && !this.baseTried) {
            this.baseTried = true;
            if (!Config.DEV_PPL_ALTERNATE.isEmpty() && stockFrag != null) {
               zombie.viewCone.ChunkRenderShader st = new zombie.viewCone.ChunkRenderShader("pzopt_chunkStock");
               if (st.getProgram() != null && st.isCompiled()) {
                  stockShader = st;
                  Log.info("pixel light: dev stock chunk program " + st.getID());
               }
            }
            try {
               baseShader = compileVariant(V_BASE);
               this.variants.put(V_BASE, baseShader);
            } catch (Throwable t) {
               Log.warn("pixel light: the light-free chunk program failed (" + t + "); one program for every chunk texture");
            }
         } else if (baseShader != null && Config.DEV_PPL_VIEW == 0 && this.precompiled < PRECOMPILE.length) {
            int key = PRECOMPILE[this.precompiled++]; // the common variants, one a frame after the world is up (no hitch at first use)
            if (!this.variants.containsKey(key)) {
               this.variants.put(key, compileVariant(key));
            }
         }
         this.shadowLight = f.shadowLight;
         System.arraycopy(f.la, 0, this.la, 0, f.lights * 4);
         System.arraycopy(f.lb, 0, this.lb, 0, f.lights * 4);
         System.arraycopy(f.lc, 0, this.lc, 0, f.lights * 4);
         serial++;
         wantOn = true;
         if (f.composite) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + VIS_UNIT);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.vis);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + INFO_UNIT);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.info);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + LATTICE_UNIT);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.lattice);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            this.stamp(2);
            this.collect();
            return; // the composite's draws light themselves (setChunkUniforms)
         }
         this.pass();
      }

      /** window px -> (x - y, x + y - 6z) and depth -> x + y + 2z relative to the origin square: mapA = (kA, cA, kB, cB), mapC.xy = (kC, cC) */
      private void mapping(float[] out) {
         GL11.glGetFloatv(GL11.GL_VIEWPORT, this.viewportF);
         double vx = this.viewportF[0], vy = this.viewportF[1], vwF = this.viewportF[2], vhF = this.viewportF[3];
         double sxPerPx = this.screenW / vwF, syPerPx = this.screenH / vhF;
         double a32 = 32.0 * this.ts, a16 = 16.0 * this.ts;
         out[0] = (float)(sxPerPx * this.zoom / a32);
         out[1] = (float)(((-vx * sxPerPx) * this.zoom + this.offX) / a32 - (this.ox - this.oy));
         out[2] = (float)(-syPerPx * this.zoom / a16);
         out[3] = (float)((((vy + vhF) * syPerPx) * this.zoom + this.offY) / a16 - (this.ox + this.oy));
         out[4] = (float)(-1.0 / DEPTH_PER_XY);
         out[5] = (float)(this.d0 / DEPTH_PER_XY);
      }

      private final float[] map = new float[6];

      /** The chunk composite program is bound: its light uniforms for this frame (or off). */
      void setChunkUniforms(int program) {
         int[] loc = this.chunkUniforms.get(program);
         if (loc == null) {
            loc = new int[] {GL20.glGetUniformLocation(program, "pplOn"), GL20.glGetUniformLocation(program, "pplClear"), GL20.glGetUniformLocation(program, "pplMapA"),
               GL20.glGetUniformLocation(program, "pplMapC"), GL20.glGetUniformLocation(program, "pplOrg"), GL20.glGetUniformLocation(program, "pplLa"),
               GL20.glGetUniformLocation(program, "pplLb"), GL20.glGetUniformLocation(program, "pplLn"), GL20.glGetUniformLocation(program, "pplOpt"),
               GL20.glGetUniformLocation(program, "pplLc"), GL20.glGetUniformLocation(program, "pplOpt2"), GL20.glGetUniformLocation(program, "pplSmP"),
               GL20.glGetUniformLocation(program, "pplSmV"), GL20.glGetUniformLocation(program, "pplSmO"), GL20.glGetUniformLocation(program, "pplWet"),
               GL20.glGetUniformLocation(program, "pplShadowMask")};
            this.chunkUniforms.put(program, loc);
            if (loc[15] >= 0) {
               // the game's ShaderProgram renumbers every sampler2D to units 0, 1, 2... after the link (layout(binding) lost;
               // the sampler2DArray ones keep theirs): the mask's unit again (the program is bound here)
               GL20.glUniform1i(loc[15], MASK_UNIT);
            }
         }

         if (loc[0] < 0) {
            return; // not our shader
         }
         if (Config.DEV_PPL_TINT) {
            int key = programKeys.getOrDefault(program, 0);
            boolean torch = (key & V_NO_TORCH) == 0, point = (key & V_NO_POINT) == 0;
            float[] t = (key & V_BASE) != 0 ? new float[] {0.55F, 1.0F, 0.55F} : torch && point ? new float[] {1.0F, 1.0F, 0.45F}
               : torch ? new float[] {1.0F, 0.5F, 0.5F} : new float[] {0.55F, 0.6F, 1.0F};
            GL20.glUniform3f(GL20.glGetUniformLocation(program, "pplTint"), t[0], t[1], t[2]);
         }
         GL20.glUniform1f(loc[1], Config.PPL_DISCARD_CLEAR ? 1.0F : 0.0F);
         if (!wantOn || failed) {
            GL20.glUniform1f(loc[0], 0.0F);
            onSent = false;
            return;
         }
         this.mapping(this.map);
         GL20.glUniform1f(loc[0], (costMask & 64) != 0 ? 0.0F : 1.0F); // dev: bit 64, the shader's light off in per-pixel mode (cost probe)
         GL20.glUniform4f(loc[2], this.map[0], this.map[1], this.map[2], this.map[3]);
         GL20.glUniform4f(loc[3], this.map[4], this.map[5], this.n, Config.DEV_PPL_VIEW);
         GL20.glUniform4i(loc[4], Math.floorMod(this.ox, this.n), Math.floorMod(this.oy, this.n), this.n - 1, LEVELS - 1);
         this.lightUniforms(loc[5], loc[6], loc[7], loc[9]);
         GL20.glUniform4f(loc[14], this.wet * Config.PPL_SPEC_PCT / 100.0F, 48.0F, 0.0F, 0.0F);
         boolean mask = this.maskValid && !shadowFailed && this.shadowLight >= 0 && Config.PPL_SHADOWS;
         GL20.glUniform4f(loc[10], Config.PPL_SMOOTH ? 1.0F : 0.0F, mask ? this.shadowLight : -1.0F, this.hasTorch() ? 1.0F : 0.0F, costMask);
         if (mask) {
            GL20.glUniform4f(loc[11], this.prevMap[0], this.prevMap[1], this.prevMap[2], this.prevMap[3]);
            GL20.glUniform4f(loc[12], this.prevVp[0], this.prevVp[1], this.prevVp[2], this.prevVp[3]);
            // the previous frame's mapping is relative to its own origin square: x - y and x + y shift by the difference
            GL20.glUniform4f(loc[13], (this.ox - this.oy) - (this.prevOx - this.prevOy), (this.ox + this.oy) - (this.prevOx + this.prevOy), 0.0F, 0.0F);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + MASK_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.maskTex[1]);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
         }
         GL20.glUniform4f(loc[8], Config.PPL_NORMALS ? 1.0F : 0.0F, Config.PPL_WRAP_PCT / 100.0F, Config.PPL_SHADOWS ? 1.0F : 0.0F, Config.PPL_SHADOW_SQUARES);
         onSent = true;
         if (!this.logged) {
            this.logged = true;
            Log.info(String.format("pixel light: composite mode, viewport %.0fx%.0f at %.1f,%.1f, lattice %d on unit %d", this.viewportF[2], this.viewportF[3],
               this.viewportF[0], this.viewportF[1], this.lattice, LATTICE_UNIT));
         }
      }

      private final FloatBuffer lbuf = BufferUtils.createFloatBuffer(MAX_LIGHTS * 4);

      private boolean hasTorch() {
         for (int i = 0; i < this.lights; i++) {
            if (this.lc[i * 4 + 3] == 1.0F) {
               return true;
            }
         }
         return false;
      }

      private void lightUniforms(int la, int lb, int ln, int lc) {
         if (ln < 0) {
            return;
         }
         GL20.glUniform1i(ln, this.lights);
         if (this.lights > 0) {
            this.lbuf.clear();
            this.lbuf.put(this.la, 0, this.lights * 4).flip();
            GL20.glUniform4fv(la, this.lbuf);
            this.lbuf.clear();
            this.lbuf.put(this.lb, 0, this.lights * 4).flip();
            GL20.glUniform4fv(lb, this.lbuf);
            this.lbuf.clear();
            this.lbuf.put(this.lc, 0, this.lights * 4).flip();
            GL20.glUniform4fv(lc, this.lbuf);
         }
      }

      // the torch shadow mask: half the viewport per axis, marched in the scene depth right after the composite, blurred;
      // the next frame's composite reads it through the previous frame's mapping
      static boolean shadowFailed;
      private int shadowProgram, blurProgram, maskW, maskH;
      private final int[] maskTex = new int[2], maskFbo = new int[2];
      private final int[] su = new int[8], bu = new int[3];
      boolean maskValid;
      private final float[] prevMap = new float[6], prevVp = new float[4];
      private int prevOx, prevOy;

      void shadowPass() {
         if (shadowFailed || failed || this.shadowLight < 0 || this.shadowLight >= this.lights) {
            this.maskValid = false;
            return;
         }
         GL11.glGetIntegerv(GL11.GL_VIEWPORT, this.viewport);
         GL11.glGetFloatv(GL11.GL_VIEWPORT, this.viewportF);
         int vx = this.viewport[0], vy = this.viewport[1], vw = this.viewport[2], vh = this.viewport[3];
         int sceneFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
         int depthTex = sceneDepthTexture(sceneFbo);
         if (vw <= 0 || vh <= 0 || depthTex == 0) {
            this.maskValid = false;
            return;
         }
         if (this.shadowProgram == 0) {
            this.shadowProgram = AmbientOcclusion.link(AmbientOcclusion.QUAD_VERT, SHADOW_FRAG);
            this.blurProgram = AmbientOcclusion.link(AmbientOcclusion.QUAD_VERT, MASK_BLUR_FRAG);
            if (this.shadowProgram == 0 || this.blurProgram == 0) {
               shadowFailed = true;
               Log.warn("pixel light: the shadow mask shaders did not compile, shadows off");
               return;
            }
            String[] names = {"SceneDepth", "pplMapA", "pplMapC", "pplSv", "pplSa", "pplSb", "pplOpt"};
            for (int i = 0; i < names.length; i++) {
               this.su[i] = GL20.glGetUniformLocation(this.shadowProgram, names[i]);
            }
            this.bu[0] = GL20.glGetUniformLocation(this.blurProgram, "Mask");
            this.bu[1] = GL20.glGetUniformLocation(this.blurProgram, "texel");
            if (this.quadVbo == 0) {
               this.init(); // the quad (and the pass program, unused here)
            }
         }
         int mw = (vw + 1) / 2, mh = (vh + 1) / 2;
         if (mw != this.maskW || mh != this.maskH) {
            for (int i = 0; i < 2; i++) {
               if (this.maskTex[i] != 0) {
                  GL11.glDeleteTextures(this.maskTex[i]);
                  GL30.glDeleteFramebuffers(this.maskFbo[i]);
               }
               this.maskTex[i] = GL11.glGenTextures();
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.maskTex[i]);
               GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG16F, mw, mh, 0, GL30.GL_RG, GL11.GL_FLOAT, (ByteBuffer)null);
               GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
               GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
               GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
               GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
               this.maskFbo[i] = GL30.glGenFramebuffers();
               GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.maskFbo[i]);
               GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, this.maskTex[i], 0);
               GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            this.maskW = mw;
            this.maskH = mh;
         }
         if (Config.DEV_PPL_TIMING) {
            ShadowTimer.stamp(0);
         }
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glDisable(GL11.GL_STENCIL_TEST);
         GL11.glDisable(GL11.GL_ALPHA_TEST);
         GL11.glDisable(GL11.GL_BLEND);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDepthMask(false);
         GL11.glColorMask(true, true, true, true);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.quadVbo);
         GL20.glEnableVertexAttribArray(0);
         for (int i = 1; i < 5; i++) {
            GL20.glDisableVertexAttribArray(i);
         }
         GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
         // 1. march
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.maskFbo[0]);
         GL11.glViewport(0, 0, mw, mh);
         GL20.glUseProgram(this.shadowProgram);
         GL20.glUniform1i(this.su[0], 0);
         GL20.glUniform4f(this.su[1], this.map[0], this.map[1], this.map[2], this.map[3]);
         GL20.glUniform4f(this.su[2], this.map[4], this.map[5], this.n, 0.0F);
         GL20.glUniform4f(this.su[3], this.viewportF[0], this.viewportF[1], 2.0F, 0.0F);
         int k = this.shadowLight * 4;
         GL20.glUniform4f(this.su[4], this.la[k], this.la[k + 1], this.la[k + 2], this.la[k + 3]);
         GL20.glUniform4f(this.su[5], this.lb[k], this.lb[k + 1], this.lb[k + 2], this.lb[k + 3]);
         GL20.glUniform4f(this.su[6], 1.0F, 0.0F, 1.0F, Config.PPL_SHADOW_SQUARES);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
         GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
         // 2. depth-aware 3x3 blur
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.maskFbo[1]);
         GL20.glUseProgram(this.blurProgram);
         GL20.glUniform1i(this.bu[0], 0);
         GL20.glUniform2f(this.bu[1], 1.0F / mw, 1.0F / mh);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.maskTex[0]);
         GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
         if (Config.DEV_PPL_TIMING) {
            ShadowTimer.stamp(1);
            ShadowTimer.collect();
         }
         System.arraycopy(this.map, 0, this.prevMap, 0, 6);
         System.arraycopy(this.viewportF, 0, this.prevVp, 0, 4);
         this.prevOx = this.ox;
         this.prevOy = this.oy;
         this.maskValid = true;
         // 3. back to the scene
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, sceneFbo);
         if (this.viewportF[0] != vx || this.viewportF[1] != vy) {
            org.lwjgl.opengl.GL41.glViewportIndexedf(0, this.viewportF[0], this.viewportF[1], this.viewportF[2], this.viewportF[3]);
         } else {
            GL11.glViewport(vx, vy, vw, vh);
         }
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         for (int i = 0; i < 5; i++) {
            GL20.glEnableVertexAttribArray(i);
         }
         GL20.glUseProgram(0);
         zombie.core.ShaderHelper.forgetCurrentlyBound(); // the game caches the bound program: the next StartShader must bind again
         zombie.core.DefaultShader.isActive = false;
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glDepthMask(true);
         GL11.glEnable(GL11.GL_BLEND);
         GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
         GLStateRenderThread.restore();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      }

      /** Pass mode: a full-screen quad over the scene right after the composite. */
      private void pass() {
         if ((costMask & 1024) != 0) {
            return; // dev: bit 1024, no pass (pass mode's cost A/B)
         }
         GL11.glGetIntegerv(GL11.GL_VIEWPORT, this.viewport);
         int vw = this.viewport[2], vh = this.viewport[3];
         int sceneFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
         int depthTex = sceneDepthTexture(sceneFbo);
         if (vw <= 0 || vh <= 0 || depthTex == 0) {
            fail("the scene depth is not a texture (fbo " + sceneFbo + ")");
            return;
         }
         if (this.program == 0 && !this.init()) {
            fail("the pass shader did not compile");
            return;
         }
         this.mapping(this.map);
         if (!this.logged) {
            this.logged = true;
            Log.info(String.format("pixel light: pass mode on %dx%d at %.1f,%.1f, scene depth texture %d, lattice %d", vw, vh, this.viewportF[0], this.viewportF[1], depthTex,
               this.lattice));
         }
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glDisable(GL11.GL_STENCIL_TEST);
         GL11.glDisable(GL11.GL_ALPHA_TEST);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDepthMask(false);
         int view = Config.DEV_PPL_VIEW;
         if (view == 0) {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_ZERO, GL11.GL_SRC_COLOR);
         } else {
            GL11.glDisable(GL11.GL_BLEND);
         }
         GL11.glColorMask(true, true, true, false);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.quadVbo);
         GL20.glEnableVertexAttribArray(0);
         for (int i = 1; i < 5; i++) {
            GL20.glDisableVertexAttribArray(i);
         }
         GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
         GL20.glUseProgram(this.program);
         GL20.glUniform1i(this.u[0], 0);
         GL20.glUniform4f(this.u[2], this.map[0], this.map[1], this.map[2], this.map[3]);
         GL20.glUniform4f(this.u[3], this.map[4], this.map[5], this.n, view);
         GL20.glUniform4i(this.u[4], Math.floorMod(this.ox, this.n), Math.floorMod(this.oy, this.n), this.n - 1, LEVELS - 1);
         this.lightUniforms(this.u[5], this.u[6], this.u[7], this.u[9]);
         GL20.glUniform4f(this.u[10], Config.PPL_SMOOTH ? 1.0F : 0.0F, -1.0F, this.hasTorch() ? 1.0F : 0.0F, 0.0F);
         GL20.glUniform4f(this.u[8], Config.PPL_NORMALS ? 1.0F : 0.0F, Config.PPL_WRAP_PCT / 100.0F, Config.PPL_SHADOWS ? 1.0F : 0.0F, Config.PPL_SHADOW_SQUARES);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + VIS_UNIT);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.vis);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + INFO_UNIT);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.info);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + LATTICE_UNIT);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.lattice);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
         GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
         this.stamp(2);
         this.collect();
         // the state VBORenderer / the sprite ring buffer expect (as AmbientOcclusion / FogPass leave it)
         GL11.glColorMask(true, true, true, true);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + LATTICE_UNIT);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + INFO_UNIT);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + VIS_UNIT);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         for (int i = 0; i < 5; i++) {
            GL20.glEnableVertexAttribArray(i);
         }
         GL20.glUseProgram(0);
         zombie.core.ShaderHelper.forgetCurrentlyBound(); // the game caches the bound program: the next StartShader must bind again
         zombie.core.DefaultShader.isActive = false;
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glDepthMask(true);
         GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
         GLStateRenderThread.restore();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      }

      private void allocLattice(int n) {
         if (this.lattice != 0) {
            GL11.glDeleteTextures(this.lattice);
         }
         this.lattice = GL11.glGenTextures();
         this.latticeN = n;
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.lattice);
         ByteBuffer zero = BufferUtils.createByteBuffer(n * n * LEVELS * 4); // the wall gradient, one texel per square
         GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA8, n, n, LEVELS, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, zero);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_MAX_LEVEL, 0);
         // one texel per square, toroidal like the lattice: GL_REPEAT lets the hardware interpolate across the wrap
         if (this.info != 0) {
            GL11.glDeleteTextures(this.info);
         }
         this.info = GL11.glGenTextures();
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.info);
         ByteBuffer zero2 = zero;
         GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA8, n, n, LEVELS, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, zero2);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_MAX_LEVEL, 0);
         if (this.vis != 0) {
            GL11.glDeleteTextures(this.vis);
         }
         this.vis = GL11.glGenTextures();
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.vis);
         GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA8, n, n, LEVELS, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, zero2);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_MAX_LEVEL, 0);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
      }

      private boolean init() {
         this.program = AmbientOcclusion.link(AmbientOcclusion.QUAD_VERT, PASS_FRAG);
         if (this.program == 0) {
            return false;
         }
         this.u[0] = GL20.glGetUniformLocation(this.program, "SceneDepth");
         this.u[1] = -1; // (the samplers have fixed bindings)
         this.u[2] = GL20.glGetUniformLocation(this.program, "pplMapA");
         this.u[3] = GL20.glGetUniformLocation(this.program, "pplMapC");
         this.u[4] = GL20.glGetUniformLocation(this.program, "pplOrg");
         this.u[5] = GL20.glGetUniformLocation(this.program, "pplLa");
         this.u[6] = GL20.glGetUniformLocation(this.program, "pplLb");
         this.u[7] = GL20.glGetUniformLocation(this.program, "pplLn");
         this.u[8] = GL20.glGetUniformLocation(this.program, "pplOpt");
         this.u[9] = GL20.glGetUniformLocation(this.program, "pplLc");
         this.u[10] = GL20.glGetUniformLocation(this.program, "pplOpt2");
         FloatBuffer quad = BufferUtils.createFloatBuffer(8);
         quad.put(new float[] {-1.0F, -1.0F, 1.0F, -1.0F, 1.0F, 1.0F, -1.0F, 1.0F}).flip();
         this.quadVbo = GL15.glGenBuffers();
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.quadVbo);
         GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quad, GL15.GL_STATIC_DRAW);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         return true;
      }

      private static int sceneDepthTexture(int fbo) {
         if (fbo == 0) {
            return 0;
         }
         int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
         if (type != GL11.GL_TEXTURE) {
            return 0;
         }
         return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
      }

      // dev (devPplTiming): GPU time of the upload and the pass, 8 frames in flight
      private int[] queries;
      private int slot;
      private final boolean[] pending = new boolean[8];
      private long sumUpload, sumPass, timed;

      private void stamp(int stage) {
         if (!Config.DEV_PPL_TIMING) {
            return;
         }
         if (this.queries == null) {
            this.queries = new int[8 * 3];
            GL15.glGenQueries(this.queries);
         }
         GL33.glQueryCounter(this.queries[this.slot * 3 + stage], GL33.GL_TIMESTAMP);
      }

      private void collect() {
         if (!Config.DEV_PPL_TIMING) {
            return;
         }
         this.pending[this.slot] = true;
         this.slot = (this.slot + 1) % 8;
         if (this.pending[this.slot]) {
            int base = this.slot * 3;
            if (GL15.glGetQueryObjecti(this.queries[base + 2], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
               long t0 = GL33.glGetQueryObjecti64(this.queries[base], GL15.GL_QUERY_RESULT);
               long t1 = GL33.glGetQueryObjecti64(this.queries[base + 1], GL15.GL_QUERY_RESULT);
               long t2 = GL33.glGetQueryObjecti64(this.queries[base + 2], GL15.GL_QUERY_RESULT);
               this.sumUpload += t1 - t0;
               this.sumPass += t2 - t1;
               if (++this.timed == 1000) {
                  uploadNs = this.sumUpload / this.timed;
                  passNs = this.sumPass / this.timed;
                  Log.info(String.format("pixel light gpu us/frame: upload=%.1f pass=%.1f (1000 frames)", uploadNs / 1e3, passNs / 1e3));
                  this.sumUpload = this.sumPass = this.timed = 0;
               }
            }
            this.pending[this.slot] = false;
         }
      }
   }

   // ------------------------------------------------------------------------------------------------ shaders

   static volatile boolean chunkShaderPatched;

   /** ShaderUnit hook: the chunk composite's fragment shader gets the per-pixel light (test-compiled; stock on failure). */
   public static String patchShader(String fileName, String code) {
      if (fileName == null || code == null) {
         return code;
      }
      String fn = fileName.replace('\\', '/');
      if (fn.endsWith("/pzopt_chunkBase.vert")) {
         return CHUNK_BASE_VERT; // the shipped files are placeholders: the sources live here
      }
      if (fn.endsWith("/pzopt_chunkBase.frag")) {
         return "#version 420\n" + variantDefines + TINT + CHUNK_FRAG_BODY;
      }
      if (fn.endsWith("/pzopt_chunkStock.vert")) {
         return CHUNK_BASE_VERT;
      }
      if (fn.endsWith("/pzopt_chunkStock.frag")) {
         return stockFrag != null ? stockFrag : code;
      }
      if (!fn.endsWith("/chunkShader.frag")) {
         return code;
      }
      stockFrag = code; // dev: devPplAlternate's stock program
      if ("pass".equals(Config.PPL_MODE)) {
         return code; // pass mode: the composite stays the stock program (the patched one costs its registers even unlit)
      }
      if (!(Config.PIXEL_LIGHT || !Config.DEV_PPL_TOGGLE_AT.isEmpty()) || !Overrides.enabled() || System.getProperty("os.name", "").contains("OS X")) {
         return code;
      }
      int test = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
      GL20.glShaderSource(test, CHUNK_FRAG);
      GL20.glCompileShader(test);
      boolean ok = GL20.glGetShaderi(test, GL20.GL_COMPILE_STATUS) != 0;
      String log = ok ? "" : GL20.glGetShaderInfoLog(test, 4096);
      GL20.glDeleteShader(test);
      if (!ok) {
         Log.warn("pixel light: the chunk composite shader does not compile, " + fileName + " stays stock (pass mode): " + log);
         return code;
      }
      chunkShaderPatched = true;
      Log.info("pixel light: " + fileName + " lights each composited pixel");
      return CHUNK_FRAG;
   }

   /** The light at a world position (x, y relative to the origin square; z the level): the owner square's corners from the lattice. */
   private static final String LIGHT_GLSL = String.join("\n",
      "layout(binding = 12) uniform sampler2DArray pplWall;", // LATTICE_UNIT: one texel per square, a wall's light from foot to top (0.5 + delta / 2); explicit units: the game validates the program with every sampler on unit 0, and two sampler types on one unit fail on Mesa
      "uniform vec4 pplMapA;", // x - y = pplMapA.x * px + pplMapA.y, x + y - 6z = pplMapA.z * py + pplMapA.w (relative to the origin square)
      "uniform vec4 pplMapC;", // x + y + 2z = pplMapC.x * depth + pplMapC.y; z: lattice squares per side; w: dev view
      "uniform ivec4 pplOrg;", // origin square in the lattice (x, y), squares - 1, levels - 1
      "const float PPL_LEVEL = 2.4494897;", // squares per level of height
      "uniform vec4 pplLa[16];", // dynamic lights: x, y (relative to the origin square), z, reach
      "uniform vec4 pplLb[16];", // direction x, y, cone cos (-2: a point light), strength
      "uniform int pplLn;",
      "uniform int pplSel = -1;", // the lights that reach the chunk texture being drawn (bits; set per draw in the composite: tiled light lists)
      "uniform ivec2 pplLv = ivec2(-64, 64);", // the levels the chunk texture being drawn holds (min, top; set per draw in the composite)
      "uniform vec4 pplOpt;", // x: normals on, y: wrap, z: shadows on, w: shadow march length
      "uniform vec4 pplOpt2;", // x: smoothstep between centres, y: the light the shadow mask belongs to (-1: none)
      "layout(binding = 9) uniform sampler2D pplShadowMask;", // MASK_UNIT: the previous frame's torch shadow mask
      "uniform vec4 pplSmP;", // the previous frame's window mapping (kA, cA, kB, cB)
      "uniform vec4 pplSmV;", // the previous frame's viewport
      "uniform vec4 pplSmO;", // x - y and x + y of this frame's origin minus the previous frame's
      "float pplMask(vec3 P) {",
      "   float A = P.x - P.y + pplSmO.x, B = P.x + P.y - 6.0 * P.z + pplSmO.y;",
      "   vec2 f = vec2((A - pplSmP.y) / pplSmP.x, (B - pplSmP.w) / pplSmP.z);",
      "   vec2 uv = (f - pplSmV.xy) / pplSmV.zw;",
      "   if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) return 1.0;",
      "   return texture(pplShadowMask, uv).r;",
      "}",
      // the surface normal from the position's screen derivatives, in squares (a level is 2.449 squares tall), facing the
      // viewer; the sprites' depth textures make it a real normal on furniture too (call in uniform control flow)
      "vec3 pplNormal(vec3 P) {",
      "   vec3 m = P * vec3(1.0, 1.0, PPL_LEVEL);",
      "   vec3 nn = cross(dFdx(m), dFdy(m));",
      "   float l = length(nn);",
      "   nn = l > 1e-10 ? nn / l : vec3(0.0, 0.0, 1.0);",
      "   nn = dot(nn, vec3(3.0, 3.0, PPL_LEVEL)) < 0.0 ? -nn : nn;",
      // tiles are floors and walls: within ~20 degrees of one of the three planes it is that plane (the edge rows written a
      // little low tilt the raw normal into lines along every tile edge); a pixel at a level's height is floor
      "   if (abs(P.z - floor(P.z + 0.5)) < 0.008 || nn.z > 0.94) return vec3(0.0, 0.0, 1.0);",
      "   if (nn.x > 0.94) return vec3(1.0, 0.0, 0.0);",
      "   if (nn.y > 0.94) return vec3(0.0, 1.0, 0.0);",
      "   return nn;",
      "}",
      "uniform vec4 pplLc[16];", // colour, kind (1: a torch, added to the base; 0: a point light, the brightest per channel)
      // a torch's intensity (PixelLight.torchModel, fitted to the native's per-square torch entries)
      // (fitted on flip dumps, 2026-09-25: a handheld torch 1.76 x strength, falloff^1.15, cosine ramp from the cone's + 0.025
      // to 0.95, mean error 0.04; a vehicle light (focused) 1.57 x strength, falloff^1.57, ramp from the cone's - 0.28 to 1.0,
      // mean error 0.055)
      "float pplTorch(vec2 p, vec4 a, vec4 b, float kind) {",
      "   vec2 v = p - a.xy;",
      "   float d = length(v);",
      "   bool car = kind > 1.5;",
      "   float x = clamp(1.0 - d / a.w, 0.0, 1.0);",
      "   float fall = car ? x * sqrt(x) * (0.93 + 0.07 * x) : x * (0.85 + 0.15 * x);", // ~x^1.57, ~x^1.15 without pow
      "   float ang = 1.0;",
      "   if (b.z > -1.5 && d > 1e-3) {",
      "      float c0 = car ? b.z - 0.28 : b.z + 0.025, c1 = car ? 1.0 : 0.95;",
      "      ang = clamp((dot(v, b.xy) / d - c0) / max(c1 - c0, 0.05), 0.0, 1.0);",
      "      if (!car) ang = mix(ang, 1.0, clamp(1.0 - 1.5 * d, 0.0, 1.0));", // a little spill at the holder's feet
      "   }",
      "   return min(1.0, (car ? 1.57 : 1.76) * b.w * fall * ang);",
      "}",
      "vec3 pplPos(vec2 f, float d) {",
      "   float A = pplMapA.x * f.x + pplMapA.y;",
      "   float B = pplMapA.z * f.y + pplMapA.w;",
      "   float C = pplMapC.x * d + pplMapC.y;",
      "   float Z = (C - B) * 0.125;",
      "   float S = C - 2.0 * Z;",
      "   return vec3((S + A) * 0.5, (S - A) * 0.5, Z);",
      "}",
      // dz: the z step to the neighbour pixels (floors are flat in z, walls and objects are not)
      "layout(binding = 11) uniform sampler2DArray pplInfo;", // INFO_UNIT: one texel per square, rgb its light without torches, a the torches reach it (a wall does not hide it)
      "layout(binding = 10) uniform sampler2DArray pplConn;", // CONN_UNIT: one texel per square, r its connected neighbours (bits E S W N SE SW NW NE)
      "vec4 pplInfoAt(ivec2 s, int lvl) { return texelFetch(pplInfo, ivec3(s & pplOrg.z, lvl), 0); }",
      // the facing of a surface to a light, relative to a floor's (floors unchanged; wrap softens the sprites' own shading)
      "float pplFacing(vec3 P, vec3 n, vec3 lp) {",
      "   vec3 ld = normalize(lp - P * vec3(1.0, 1.0, PPL_LEVEL));",
      "   float wr = pplOpt.y;",
      "   return clamp(max(dot(n, ld) + wr, 0.0) / max(ld.z + wr, 0.15), 0.0, 1.25);",
      "}",
      "uniform vec4 pplWet;", // x: wet ground x specular strength, y: shininess
      "vec3 pplSpec = vec3(0.0);", // out of pplLight: the wet glints of the lights (added, not multiplied by the surface colour)
      "const vec3 PPL_VIEW = vec3(0.6428, 0.6428, 0.5162);", // towards the camera, in squares (the axis the screen does not see: (3, 3, 1) levels)
      "vec3 pplLight(vec3 P, float dz, vec3 n) {", // n: the surface normal in squares (z up), towards the viewer
      "   int cost = int(pplOpt2.w + 0.5);", // dev: parts switched off (devPplCostAt)
      "   if ((cost & 8) != 0) return vec3(fract(P.x * 0.001) + 0.999);",
      // the square that owns the surface: a hair towards the viewer (a north wall's owner is on its +y side, a west wall's
      // on its +x side, a floor's above it)
      // tile edge rows are written up to 0.005 levels low; at most one row of a wall's top goes up. Along the chunk edges the
      // edge rows sit deeper and took the level below the texture's (no squares there: a black lattice, a dotted dark line
      // along every chunk edge); a wall's top row that goes up in a chunk with no squares above took a black lattice as
      // well (dots along the wall tops): the level stays within the ones the chunk texture holds (top = the chunk's
      // highest level with squares)
      "   float lz = clamp(floor(P.z + 0.006), float(pplLv.x), float(pplLv.y));",
      "   vec2 sq = floor(P.xy + 0.004);",
      "   vec2 fxy = clamp(P.xy - sq, 0.0, 1.0);",
      "   ivec2 s = (ivec2(sq) + pplOrg.xy) & pplOrg.z;",
      // lifted a level by the tolerance: a floor's edge row a hair low or the top row of a wall of the level below; the
      // brighter of the two squares (an unseen upper floor put dark dots along the wall tops)
      "   if (P.z < lz && lz > float(pplLv.x)) {",
      "      vec3 a = pplInfoAt(s, int(lz) & pplOrg.w).rgb, b = pplInfoAt(s, int(lz - 1.0) & pplOrg.w).rgb;",
      "      if (max(b.r, max(b.g, b.b)) > max(a.r, max(a.g, a.b))) lz -= 1.0;",
      "   }",
      "   float fz = clamp(P.z - lz, 0.0, 1.0);",
      "   int lvl = int(lz) & pplOrg.w;",
      "   int view = int(pplMapC.w + 0.5);",
      // 1. the base light is the native's sample at each square's centre (lightInfo without the torches: what stock draws
      // objects with; the corners are the max over the four squares around them, which is what makes stock's light
      // blocky), interpolated between the centres, only across neighbours sharing their corner colours (the native
      // breaks them at walls); the torches' visibility the same way
      "   vec2 q = fxy - 0.5;",
      "   ivec2 dir = ivec2(q.x < 0.0 ? -1 : 1, q.y < 0.0 ? -1 : 1);",
      "   vec2 w = abs(q);",
      "   if (pplOpt2.x > 0.5) w = w * w * (3.0 - 2.0 * w);", // smoothstep between the centres: no Mach bands where the slope changes
      "   float w00 = (1.0 - w.x) * (1.0 - w.y), w10 = w.x * (1.0 - w.y), w01 = (1.0 - w.x) * w.y, w11 = w.x * w.y;",
      // one fetch: the hardware's bilinear between the four centres; its alpha is 1 exactly when all four are simple squares
      // (every neighbour connected, the torches not hidden, no vertical gradient)
      "   vec2 tx = P.xy + vec2(pplOrg.xy) - 0.5;", // in texels, centres at integers
      "   vec2 ti = floor(tx), tf = tx - ti;",
      "   if (pplOpt2.x > 0.5) tf = tf * tf * (3.0 - 2.0 * tf);",
      "   vec4 B = textureLod(pplInfo, vec3((ti + 0.5 + tf) / pplMapC.z, float(lvl)), 0.0);", // the same weights through the hardware's bilinear
      "   float V = B.a < 0.5 ? 0.0 : 1.0;", // a simple neighbourhood: all four torch-visible (1) or all four hidden (0)
      "   bool grad = false;",
      "   int conn = 255;",
      "   if ((B.a < 0.999 && B.a > 0.001 || view == 6) && (cost & 2) == 0) {",
      "      vec3 cc = texelFetch(pplConn, ivec3(s, lvl), 0).rgb;",
      "      conn = int(cc.r * 255.0 + 0.5);",
      "      grad = cc.b > 0.5;",
      "      int bx = dir.x > 0 ? 1 : 4, by = dir.y > 0 ? 2 : 8;",
      "      int bxy = dir.x > 0 ? (dir.y > 0 ? 16 : 128) : (dir.y > 0 ? 32 : 64);",
      "      bool cx = (conn & bx) != 0, cy = (conn & by) != 0, cxy = (conn & bxy) != 0;",
      // the neighbours' squares, the own one where not connected (a diagonal falls back to the connected side); four
      // independent fetches accumulated at once (chaining the values kept four texels alive: registers)
      "      ivec2 s10 = cx ? s + ivec2(dir.x, 0) : s, s01 = cy ? s + ivec2(0, dir.y) : s;",
      "      ivec2 s11 = cxy ? s + dir : (cx ? s10 : s01);",
      "      vec4 t00 = pplInfoAt(s, lvl), t10 = pplInfoAt(s10, lvl), t01 = pplInfoAt(s01, lvl), t11 = pplInfoAt(s11, lvl);",
      "      B = w00 * t00 + w10 * t10 + w01 * t01 + w11 * t11;",
      // the torch's visibility between the centres, from the same texels' alphas (>= 0.5: visible)
      "      V = dot(vec4(w00, w10, w01, w11), step(vec4(0.5), vec4(t00.a, t10.a, t01.a, t11.a)));",
      "   }",
      "   vec3 L = B.rgb;",
      "   vec2 c00 = sq + 0.5, c11 = c00 + vec2(dir);",
      // 2. the dynamic lights at this pixel: torches (their brightest, added, where they reach), point lights (the
      // native's max(light, (1 - d / r) colour) per channel, visible where the centre samples show them)
      "   vec3 torch = vec3(0.0);",
      "   pplSpec = vec3(0.0);",
      "#ifndef PPL_BASE", // the base variant (pzopt_chunkBase: no light in reach of the chunk) has no light loop: half the registers
      "#ifdef PPL_NO_WET",
      "   bool wet = false;",
      "#else",
      "   bool wet = pplWet.x > 0.004 && pplLn > 0;",
      "#endif",
      "   if (wet) wet = texelFetch(pplConn, ivec3(s, lvl), 0).a > 0.5;", // outdoors: the rain wets it
      "   if (view != 6 && (cost & 1) == 0) {",
      "      int bits = pplSel & ((1 << pplLn) - 1);",
      "      while (bits != 0) {", // only this chunk's lights: uniform per draw, so the loop is coherent
      "         int i = findLSB(bits);",
      "         bits &= bits - 1;",
      "         vec4 a = pplLa[i], b = pplLb[i], c = pplLc[i];",
      "         if (abs(a.z - lz) > 1.5) continue;",
      "         float dd = length(P.xy - a.xy);",
      "         if (dd > a.w + 1.0) continue;",
      "         vec3 lpos = vec3(a.xy, (a.z + (c.w > 0.5 ? 0.55 : 0.6)) * PPL_LEVEL);",
      "         float f = pplOpt.x > 0.5 ? pplFacing(P, n, lpos) : 1.0;",
      "         float glint = 0.0;",
      "         if (wet) {",
      "            vec3 ld = normalize(lpos - P * vec3(1.0, 1.0, PPL_LEVEL));",
      "            glint = pow(max(dot(n, normalize(ld + PPL_VIEW)), 0.0), pplWet.y) * step(0.0, dot(n, ld));",
      "         }",
      // a vehicle light: the native's field between the centres already carries it (a moving car's native footprint lags
      // a pass behind, so it is not replaced); its model only lights the wet glints. (A residual sharpening from the model,
      // T(p) - bilinear T(centres), cost four evaluations and doubled the shader's registers for a barely visible edge.)
      // the variants (pzopt_chunkBase with PPL_NO_*: the kinds of light a chunk texture's list does not hold) leave cases
      // out; fewer live values, more waves in flight
      "         if (c.w > 1.5) {",
      "            if (wet) pplSpec += c.rgb * pplTorch(P.xy, a, b, c.w) * glint;",
      "         } else if (c.w > 0.5) {",
      "#ifndef PPL_NO_TORCH",
      "            float tv = pplTorch(P.xy, a, b, c.w) * f;",
      "#ifndef PPL_NO_MASK",
      "            if (tv > 0.01 && float(i) == pplOpt2.y) tv *= pplMask(P);",
      "#endif",
      "            torch = max(torch, c.rgb * tv);",
      "            pplSpec += c.rgb * tv * V * glint;",
      "#endif",
      "         } else {",
      "#ifndef PPL_NO_POINT",
      "            float lp = clamp(1.0 - dd / a.w, 0.0, 1.0);",
      // visible where the native's light (interpolated) reaches the prediction: the concave falloff puts the interpolation a
      // little under it, hence the soft threshold
      "            float pred = max(c.r, max(c.g, c.b)) * lp;",
      "            float pv = pred < 0.03 ? 1.0 : smoothstep(0.55, 0.9, max(L.r, max(L.g, L.b)) / pred);",
      "            L = max(L, c.rgb * lp * pv * f);",
      "            pplSpec += c.rgb * lp * pv * glint;",
      "#endif",
      "         }",
      "      }",
      "   }",
      "   L = clamp(L + torch * V, 0.0, 1.0);",
      "   pplSpec *= pplWet.x;",
      "#endif",
      // 3. walls keep the native's vertical gradient (ceiling vs floor corners) on top of it
      "   if (fz > 0.001) {", // walls and objects: the square's light from its foot to its top (0.5: none)
      "      L = clamp(L + fz * (texelFetch(pplWall, ivec3(s, lvl), 0).rgb * 2.0 - 1.0), 0.0, 1.0);",
      "   }",
      "#if !defined(PPL_BASE) && defined(PPL_DEV)",
      "   if (view == 5) L = torch * V;",
      "   if (view == 4) L = n * 0.5 + 0.5;",
      "#if !defined(PPL_BASE) && defined(PPL_DEV)",
      "   if (view == 3) { float c = mod(sq.x + sq.y + lz, 2.0); L = vec3(0.35 + 0.5 * c, 0.35 + 0.3 * fxy.x, 0.35 + 0.3 * fxy.y); }",
      "   if (view == 8) L = vec3(float(conn & 15) / 15.0, float(conn >> 4) / 15.0, V);", // dev: connectivity, torch visibility
      "   if (view == 9) L = vec3(pplOpt2.y >= 0.0 ? pplMask(P) : 0.5);", // dev: the torch shadow mask as read (reprojected)
      "#endif",
      "   if (view == 10) L = B.a < 0.999 && B.a > 0.001 ? vec3(1.0, 0.2, 0.2) : vec3(0.2, 1.0, 0.2);", // dev: red where the edge path runs
      "   if (view == 11) L = B.rgb;", // dev: the base light between the centres
      "   if (view == 12) L = vec3(V, B.a, float(wet));", // dev: the torch visibility, the simple flag, wet
      "#endif",
      "   return L;",
      "}");

   /** The torch shadow mask: r visibility towards the torch (marched in the scene depth), g the pixel's depth (for the blur). */
   private static final String SHADOW_FRAG = String.join("\n",
      "#version 420",
      "uniform sampler2D SceneDepth;",
      LIGHT_GLSL,
      "float pplDepthAt(vec2 f) { return texelFetch(SceneDepth, ivec2(f), 0).r; }",
      "uniform vec4 pplSv;", // viewport origin x, y (window px), window px per mask texel
      "uniform vec4 pplSa, pplSb;", // the torch
      "out vec4 fragColor;",
      "void main() {",
      "   vec2 f = pplSv.xy + (floor(gl_FragCoord.xy) + 0.5) * pplSv.z;",
      "   float d = texelFetch(SceneDepth, ivec2(f), 0).r;",
      "   float vis = 1.0;",
      "   if (d < 1.0) {",
      "      vec3 P = pplPos(f, d);",
      "      if (pplTorch(P.xy, pplSa, pplSb, 1.0) > 0.01) {",
      "         vec3 L = vec3(pplSa.xy, pplSa.z + 0.55);",
      "         vec3 dd = L - P;",
      "         float len = length(dd.xy);",
      "         float span = min(len - 0.35, pplOpt.w);",
      "         if (span > 0.05) {",
      "            vec3 st = dd / len;",
      "            for (int k = 0; k < 16; k++) {",
      "               vec3 Q = P + st * ((float(k) + 0.5) / 16.0 * span + 0.06);",
      "               vec2 fq = vec2((Q.x - Q.y - pplMapA.y) / pplMapA.x, (Q.x + Q.y - 6.0 * Q.z - pplMapA.w) / pplMapA.z);",
      "               float dq = (Q.x + Q.y + 2.0 * Q.z - pplMapC.y) / pplMapC.x;",
      "               float gap = dq - pplDepthAt(fq);", // > 0: a surface in front of the ray point
      "               if (gap > 0.00016 && gap < 0.0035) { vis = 0.0; break; }",
      "            }",
      "         }",
      "      }",
      "   }",
      "   fragColor = vec4(vis, d, 0.0, 1.0);",
      "}");

   /** 3x3 blur of the mask, only across texels at a depth close to the centre's. */
   private static final String MASK_BLUR_FRAG = String.join("\n",
      "#version 330",
      "uniform sampler2D Mask;",
      "uniform vec2 texel;",
      "out vec4 fragColor;",
      "void main() {",
      "   ivec2 c = ivec2(gl_FragCoord.xy);",
      "   vec2 m0 = texelFetch(Mask, c, 0).rg;",
      "   float sum = 0.0, wsum = 0.0;",
      "   for (int y = -1; y <= 1; y++) {",
      "      for (int x = -1; x <= 1; x++) {",
      "         vec2 m = texelFetch(Mask, c + ivec2(x, y), 0).rg;",
      "         float w = abs(m.y - m0.y) < 0.0015 ? (x == 0 && y == 0 ? 2.0 : 1.0) : 0.0;",
      "         sum += m.x * w;",
      "         wsum += w;",
      "      }",
      "   }",
      "   fragColor = vec4(sum / max(wsum, 1e-4), m0.y, 0.0, 1.0);",
      "}");

   private static final String PASS_FRAG = String.join("\n",
      "#version 420",
      "uniform sampler2D SceneDepth;",
      LIGHT_GLSL,
      "float pplDepthAt(vec2 f) { return texelFetch(SceneDepth, ivec2(f), 0).r; }",
      "out vec4 fragColor;",
      "void main() {",
      "   float d = texelFetch(SceneDepth, ivec2(gl_FragCoord.xy), 0).r;",
      "   vec3 P = pplPos(gl_FragCoord.xy, d);",
      "   float dz = max(abs(dFdx(P.z)), abs(dFdy(P.z)));",
      "   vec3 n = pplLn > 0 && pplOpt.x > 0.5 ? pplNormal(P) : vec3(0.0, 0.0, 1.0);",
      "   if (d >= 1.0) discard;",
      "   vec3 L = pplLight(P, dz, n);",
      "   if (int(pplMapC.w + 0.5) == 2) L = vec3(1.0);",
      "   fragColor = vec4(L, 1.0);",
      "}");

   /** The game's chunkShader.frag (DIFFUSE x vertex colour, depth = chunkDepth + the texture's depth) with the light multiplied in. */
   private static final String CHUNK_FRAG_BODY = String.join("\n",
      "uniform sampler2D DIFFUSE;",
      "uniform sampler2D DEPTH;",
      "uniform int useTexture = 1;",
      "uniform float chunkDepth = 0.0;",
      "uniform float pplOn = 0.0;",
      "uniform float pplClear = 0.0;",
      "#ifdef PPL_TINT",
      "uniform vec3 pplTint = vec3(1.0);", // dev (devPplTint): which program drew the chunk texture
      "#endif",
      "in vec4 col;",
      "in vec2 texCoord;",
      "out vec4 fragColor;",
      LIGHT_GLSL,
      "void main() {",
      "   vec4 c = vec4(1.0, 1.0, 1.0, 1.0);",
      "   if (useTexture == 1) c = texture(DIFFUSE, texCoord.st);",
      "   float dt = texture(DEPTH, texCoord.st).r;",
      // an empty texel (no colour, cleared depth) blends nothing and writes the far plane: skipped (no blend, no depth write)
      "   if (pplClear > 0.5 && c.a <= 0.0 && dt >= 1.0) discard;",
      "   float d = chunkDepth + dt;",
      "   gl_FragDepth = d;",
      "   if (pplOn > 0.5 && (int(pplOpt2.w + 0.5) & 16) == 0) {",
      "      vec3 P = pplPos(gl_FragCoord.xy, d);",
      "      float dz = max(abs(dFdx(P.z)), abs(dFdy(P.z)));",
      "#ifdef PPL_BASE",
      "      vec3 n = vec3(0.0, 0.0, 1.0);",
      "#else",
      "      vec3 n = pplLn > 0 && pplOpt.x > 0.5 && (int(pplOpt2.w + 0.5) & 4) == 0 ? pplNormal(P) : vec3(0.0, 0.0, 1.0);", // uniform condition: the derivatives stay valid
      "#endif",
      "#ifdef PPL_BASE",
      "      if (c.a > 0.0) c.rgb *= pplLight(P, dz, n);", // premultiplied
      "#elif defined(PPL_DEV)",
      "      int view = int(pplMapC.w + 0.5);",
      "      if (c.a > 0.0 && view != 2) {",
      "         vec3 L = pplLight(P, dz, n);",
      "         c.rgb = view == 1 || view == 3 ? L * c.a : c.rgb * L + pplSpec * c.a;", // premultiplied; the glints on top
      "      }",
      "#else",
      "      if (c.a > 0.0) c.rgb = c.rgb * pplLight(P, dz, n) + pplSpec * c.a;", // premultiplied; the glints on top
      "#endif",
      "   }",
      "   fragColor = c * col;",
      "#ifdef PPL_TINT",
      "   fragColor.rgb *= pplTint;",
      "#endif",
      "}");

   /** The game's chunkShader.frag (DIFFUSE x vertex colour, depth = chunkDepth + the texture's depth) with the light multiplied in. */
   private static final String TINT = Config.DEV_PPL_TINT ? "#define PPL_TINT\n" : "";
   private static final String CHUNK_FRAG = "#version 420\n" + (Config.DEV_PPL_VIEW != 0 ? "#define PPL_DEV\n" : "") + TINT + CHUNK_FRAG_BODY; // dev views compiled in only when asked: they keep values alive to the end (registers)
   /** The same without the dynamic lights (chunk textures no light reaches): 32 registers, full occupancy on the 890M (64 with). */
   private static final String CHUNK_BASE_FRAG = "#version 420\n#define PPL_BASE\n" + CHUNK_FRAG_BODY;
   /** A plain pass-through for the base variant's program (position through ModelViewProjection, texture coordinate, colour). */
   private static final String CHUNK_BASE_VERT = String.join("\n",
      "#version 330",
      "layout (location = 0) in vec2 vPos;",
      "layout (location = 1) in vec2 vUV;",
      "layout (location = 2) in vec4 vCol;",
      "uniform mat4 ModelViewProjection;",
      "out vec4 col;",
      "out vec2 texCoord;",
      "void main() {",
      "   gl_Position = ModelViewProjection * vec4(vPos, 0.0, 1.0);",
      "   texCoord = vUV;",
      "   col = vCol;",
      "}");

   // ------------------------------------------------------------------------------------------------ dev dump

   private static String pendingLitDump; // the scene again after this frame's pass: <tag>-lit
   private static long worldUpNs;
   private static int dumpAtNext;
   private static float[] dumpAt;

   private static void scheduledDumps(int playerIndex) {
      if (dumpAt == null) {
         String[] parts = Config.DEV_PPL_DUMP_AT.split(",");
         dumpAt = new float[parts.length];
         for (int i = 0; i < parts.length; i++) {
            dumpAt[i] = Float.parseFloat(parts[i].trim());
         }
      }
      if (dumpAtNext >= dumpAt.length || IsoWorld.instance == null || IsoWorld.instance.currentCell == null) {
         return;
      }
      long now = System.nanoTime();
      if (worldUpNs == 0L) {
         worldUpNs = now;
      }
      if ((now - worldUpNs) / 1e9 >= dumpAt[dumpAtNext]) {
         String tag = "t" + (int)dumpAt[dumpAtNext];
         dumpAtNext++;
         try {
            dumpSquares(tag, playerIndex);
            Dump d = new Dump();
            d.tag = tag;
            SpriteRenderer.instance.drawGeneric(d);
            pendingLitDump = tag;
         } catch (Throwable t) {
            Log.warn("pixel light: dump " + tag + " failed: " + t);
         }
      }
   }

   static File dir() {
      File d = new File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt-ppl");
      d.mkdirs();
      return d;
   }

   private static void dumpSquares(String tag, int playerIndex) throws java.io.IOException {
      IsoCell cell = IsoWorld.instance.currentCell;
      IsoPlayer player = IsoPlayer.players[playerIndex];
      StringBuilder sb = new StringBuilder(1 << 20);
      float zoom = Core.getInstance().getZoom(playerIndex);
      int camX = (int)Math.floor(IsoCamera.frameState.camCharacterX), camY = (int)Math.floor(IsoCamera.frameState.camCharacterY);
      sb.append("# camera\n");
      sb.append("zoom ").append(zoom).append('\n');
      sb.append("tileScale ").append(Core.tileScale).append('\n');
      sb.append("offX ").append(IsoCamera.getOffX()).append('\n');
      sb.append("offY ").append(IsoCamera.getOffY()).append('\n');
      sb.append("screen ").append(IsoCamera.getScreenLeft(playerIndex)).append(' ').append(IsoCamera.getScreenTop(playerIndex)).append(' ')
         .append(IsoCamera.getScreenWidth(playerIndex)).append(' ').append(IsoCamera.getScreenHeight(playerIndex)).append('\n');
      sb.append("core ").append(Core.width).append(' ').append(Core.height).append('\n');
      sb.append("camChar ").append(IsoCamera.frameState.camCharacterX).append(' ').append(IsoCamera.frameState.camCharacterY).append(' ')
         .append(IsoCamera.frameState.camCharacterZ).append('\n');
      if (player != null) {
         sb.append("player ").append(player.getX()).append(' ').append(player.getY()).append(' ').append(player.getZ()).append('\n');
      }
      sb.append("pixelLight ").append(ACTIVE ? 1 : 0).append('\n');
      sb.append("hour ").append(zombie.GameTime.getInstance().getTimeOfDay()).append('\n');
      sb.append("night ").append(zombie.GameTime.getInstance().getNight()).append('\n');
      sb.append("daylight ").append(zombie.iso.weather.ClimateManager.getInstance().getDayLightStrength()).append('\n');
      for (int i = 0; i < 4; i++) {
         float x = camX + (i == 1 ? 5 : 0), y = camY + (i == 2 ? 5 : 0), z = i == 3 ? 1 : 0;
         sb.append("depthAt ").append(x).append(' ').append(y).append(' ').append(z).append(' ')
            .append(IsoDepthHelper.getSquareDepthData(camX, camY, x, y, z).depthStart).append('\n');
      }
      sb.append("# lights: id x y z radius r g b active building\n");
      for (IsoLightSource l : cell.getLamppostPositions()) {
         sb.append("light ").append(l.id).append(' ').append(l.x).append(' ').append(l.y).append(' ').append(l.z).append(' ').append(l.radius).append(' ')
            .append(l.r).append(' ').append(l.g).append(' ').append(l.b).append(' ').append(l.active).append(' ')
            .append(l.localToBuilding == null ? -1 : l.localToBuilding.id).append('\n');
      }
      sb.append("# rooms: id x y z w h active\n");
      for (IsoRoomLight l : cell.roomLights) {
         sb.append("room ").append(l.id).append(' ').append(l.x).append(' ').append(l.y).append(' ').append(l.z).append(' ').append(l.width).append(' ')
            .append(l.height).append(' ').append(l.active).append('\n');
      }
      sb.append("# torches: id x y z r g b angleX angleY dist strength cone dot focusing\n");
      ArrayList<IsoGameCharacter.TorchInfo> torches = LightingJNI.pzoptTorches();
      for (IsoGameCharacter.TorchInfo t : torches) {
         sb.append("torch ").append(t.id).append(' ').append(t.x).append(' ').append(t.y).append(' ').append(t.z).append(' ').append(t.r).append(' ')
            .append(t.g).append(' ').append(t.b).append(' ').append(t.angleX).append(' ').append(t.angleY).append(' ').append(t.dist).append(' ')
            .append(t.strength).append(' ').append(t.cone).append(' ').append(t.dot).append(' ').append(t.focusing).append('\n');
      }
      sb.append("# squares: x y z | vis lr lg lb dark targetDark lightLevel tick v0..v7 n [id,x,y,z,radius,r,g,b,flags]... | objects\n");
      int px = player != null ? (int)Math.floor(player.getX()) : camX, py = player != null ? (int)Math.floor(player.getY()) : camY;
      int r = 48;
      int count = 0;
      for (int cy = Math.floorDiv(py - r, 8); cy <= Math.floorDiv(py + r, 8); cy++) {
         for (int cx = Math.floorDiv(px - r, 8); cx <= Math.floorDiv(px + r, 8); cx++) {
            IsoChunk chunk = cell.getChunk(cx, cy);
            if (chunk == null) {
               continue;
            }
            for (int z = chunk.minLevel; z <= chunk.maxLevel; z++) {
               for (int y = 0; y < 8; y++) {
                  for (int x = 0; x < 8; x++) {
                     IsoGridSquare sq = chunk.getGridSquare(x, y, z);
                     if (sq == null || !(sq.lighting[playerIndex] instanceof LightingJNI.JNILighting jl)) {
                        continue;
                     }
                     sb.append("sq ").append(sq.x).append(' ').append(sq.y).append(' ').append(sq.z).append(" | ");
                     jl.pzoptDump(sb);
                     sb.append(" |");
                     for (int o = 0; o < sq.getObjects().size(); o++) {
                        zombie.iso.IsoObject obj = sq.getObjects().get(o);
                        sb.append(' ').append(obj.sprite != null && obj.sprite.name != null ? obj.sprite.name : obj.getClass().getSimpleName());
                     }
                     sb.append('\n');
                     count++;
                  }
               }
            }
         }
      }
      Files.writeString(new File(dir(), tag + "-squares.txt").toPath(), sb.toString());
      Log.info("pixel light: dump " + tag + ": " + count + " squares, " + cell.getLamppostPositions().size() + " lights, " + cell.roomLights.size() + " room lights, "
         + torches.size() + " torches");
   }

   /** Render thread, in stream order right after the chunk composite (and this frame's pass): the scene depth + colour. */
   static final class Dump extends TextureDraw.GenericDrawer {
      String tag;

      @Override
      public void render() {
         try {
            int[] vp = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
            int vx = vp[0], vy = vp[1], vw = vp[2], vh = vp[3];
            int sceneFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sceneFbo);
            FloatBuffer depth = BufferUtils.createFloatBuffer(vw * vh);
            GL11.glReadPixels(vx, vy, vw, vh, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
            ByteBuffer color = BufferUtils.createByteBuffer(vw * vh * 4);
            GL11.glReadPixels(vx, vy, vw, vh, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, color);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            ByteBuffer db = ByteBuffer.allocate(vw * vh * 4).order(ByteOrder.LITTLE_ENDIAN);
            db.asFloatBuffer().put(depth);
            Files.write(new File(dir(), this.tag + "-depth.bin").toPath(), db.array());
            byte[] cb = new byte[vw * vh * 4];
            color.get(cb);
            Files.write(new File(dir(), this.tag + "-color.bin").toPath(), cb);
            Core core = Core.getInstance();
            StringBuilder sb = new StringBuilder();
            sb.append("w=").append(vw).append("\nh=").append(vh).append("\nvx=").append(vx).append("\nvy=").append(vy).append("\nfbo=").append(sceneFbo).append('\n');
            if (!core.projectionMatrixStack.isEmpty() && !core.modelViewMatrixStack.isEmpty()) {
               org.joml.Matrix4f p = core.projectionMatrixStack.peek(), m = core.modelViewMatrixStack.peek();
               sb.append("proj=").append(p.m00()).append(',').append(p.m11()).append(',').append(p.m22()).append(',').append(p.m30()).append(',').append(p.m31()).append(',').append(p.m32()).append('\n');
               sb.append("mv=").append(m.m00()).append(',').append(m.m11()).append(',').append(m.m30()).append(',').append(m.m31()).append('\n');
            }
            if (GL.info != 0 && GL.latticeN > 0) {
               // the per-square arrays as the shader reads them: texel (x mod n, y mod n), layer z mod 16
               int n = GL.latticeN;
               for (int a = 0; a < 2; a++) {
                  ByteBuffer ib = BufferUtils.createByteBuffer(n * n * LEVELS * 4);
                  GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, a == 0 ? GL.info : GL.vis);
                  GL11.glGetTexImage(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, ib);
                  byte[] ia = new byte[ib.capacity()];
                  ib.get(ia);
                  Files.write(new File(dir(), this.tag + (a == 0 ? "-info.bin" : "-conn.bin")).toPath(), ia);
               }
               GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
               sb.append("latticeN=").append(n).append("\nlevels=").append(LEVELS).append('\n');
            }
            if (GL.maskValid && GL.maskW > 0) {
               // the torch shadow mask (after the lit composite: this frame's), march result then blurred, RG float each
               ByteBuffer mb = ByteBuffer.allocateDirect(GL.maskW * GL.maskH * 8 * 2).order(ByteOrder.LITTLE_ENDIAN);
               for (int i = 0; i < 2; i++) {
                  GL11.glBindTexture(GL11.GL_TEXTURE_2D, GL.maskTex[i]);
                  mb.position(i * GL.maskW * GL.maskH * 8);
                  GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG, GL11.GL_FLOAT, mb);
               }
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
               byte[] m = new byte[mb.capacity()];
               mb.position(0);
               mb.get(m);
               Files.write(new File(dir(), this.tag + "-mask.bin").toPath(), m);
               sb.append("maskW=").append(GL.maskW).append("\nmaskH=").append(GL.maskH).append('\n');
            }
            Files.writeString(new File(dir(), this.tag + "-view.txt").toPath(), sb.toString());
            Log.info("pixel light: dump " + this.tag + ": scene " + vw + "x" + vh + " at " + vx + "," + vy);
         } catch (Throwable t) {
            Log.warn("pixel light: scene dump failed: " + t);
         }
      }
   }
}
