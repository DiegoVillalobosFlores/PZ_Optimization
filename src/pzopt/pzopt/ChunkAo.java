package pzopt;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import zombie.core.Core;
import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.IsoTree;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.FBORenderLevels;

/**
 * Ambient occlusion baked into the chunk textures (Config {@code ambientOcclusion}, {@code aoMode=chunk}): nothing on
 * a frame that bakes nothing, nothing while the camera moves (the textures are only composited).
 *
 * <p>The static world lives in the chunk-level textures, drawn when they bake and composited every frame. A texture's
 * depth gives the AO of its pixels in the orthographic view space {@link AmbientOcclusion} uses (texel pixels over
 * {@code 32 sqrt(2) tileScale textureScale} per square, {@link AmbientOcclusion#UNITS_PER_DEPTH} squares per unit of
 * depth); the result is kept per texture (R8 at {@code aoScalePct} % per axis) and multiplied into its colour.
 * Occluders across the chunk's edge (a wall on the neighbour's boundary squares over this chunk's floor) come from the
 * eight neighbour textures of the same level pair and zoom, each at its composite offset and depth offset, the nearest
 * winning as in the composite.
 *
 * <ul>
 *   <li>Every bake ends (its framebuffer still bound, before the stock mipmap build) with one multiply of the AO the
 *       texture has. The AO depends on the depth alone, so the frequent lighting-only re-bakes cost that multiply and
 *       nothing else; a texture whose AO is 1 everywhere (an occlusion query on its first multiply) skips it.</li>
 *   <li>A new texture computes its AO inside its first bake (aoArrivalInBake: whatever the frame's slack, the bake
 *       scheduler bounds those bakes); a bake that changes objects, cutaways or trees does too, up to
 *       {@code aoBakeBudget} a frame; the rest wait for {@code aoComputeBudget} deferred computes a frame (before the
 *       composite), each applied onto the texture as a ratio new / old (blend DST_COLOR, SRC_COLOR = 2 src dst, so it
 *       darkens and lightens) with the mip levels the current zoom samples.</li>
 *   <li>A chunk with nothing but floor on the texture's levels, and nothing standing on its neighbours' borders facing
 *       it, is skipped (its AO is 1).</li>
 *   <li>When a texture gets its first AO, the neighbours computed without it are refreshed, when something but floor
 *       stands on its border squares facing them (a chunk that baked before its neighbour still gets its walls).</li>
 * </ul>
 * A compute is two passes: AO at {@code aoScalePct} % of the texture (ground-truth-style horizon AO with 32-sector
 * visibility bitmasks, reading the nine depth textures directly) and a 4x4 depth-aware box. On NVIDIA every pass and
 * framebuffer switch costs microseconds whatever its size, so the design counts passes, not texels
 * (docs/findings-ambient-occlusion-2026-09-24.md).
 */
public final class ChunkAo {
   /**
    * Dirty flags that change what a texture's depth holds: all but blood (1), lighting (32) and redraw (1024). Redraw is
    * mostly a level coming back on screen, the water shader toggling, a light switch or a stove (the storm run set it
    * 6,000 times in 25 s); its two geometric causes are small: the seam tiles after a neighbour chunk loads (the
    * neighbour refresh covers those) and the upper-level occlusion set.
    */
   private static final long GEOMETRY_FLAGS = 0x7FFFL & ~(32L | 1L | 1024L);
   private static final int SETTLE_FRAMES = 8;
   /** Chunk-texture depth per unit of (x + y + 2z) within the chunk: IsoDepthHelper.calculateDepth's 0.023093667 / 16. */
   private static final float SQUARE_DEPTH_HALF = 0.023093667F / 16.0F;
   private static final double COMPUTE_NS_ESTIMATE = 60_000.0; // a compute's GPU time with its pass overheads (desktop, 50 %)
   private static final double COMPUTE_NS_ESTIMATE_SUN = 90_000.0; // the same with the sun march (cs-walk-*: kernel ~58 us against ~35)

   private static volatile boolean failed;
   private static long computed;
   private static long multiplied;
   private static long refreshes;
   private static long deferredPeak;
   private static long skippedEmpty;
   private static long computedInBake;
   private static long mipRebakes;
   private static long refreshesSkipped;
   private static long bareSkipped;
   private static long heavyFrames;
   private static long sunRequeued;
   private static long pendingPeakAll; // deferredPeak since launch (stats() restarts that one)
   private static long arrivalsOverBudget; // first AOs computed in their bake past aoBakeBudget / the slack (aoArrivalInBake)
   // latency of a texture's first AO from its first bake: count, sum, max, and buckets 0 (in the bake) / <=10 / <=50 /
   // <=100 / <=250 / <=500 / <=1000 / >1000 ms; texture-frames composited without it, frames with at least one
   private static long firstAos;
   private static double firstAoMsSum;
   private static double firstAoMsMax;
   private static final long[] FIRST_AO_MS = new long[8];
   private static final int[] FIRST_AO_EDGES = {0, 10, 50, 100, 250, 500, 1000};
   private static long shownWithoutAo;
   private static long framesShowingWithoutAo;
   private static int lastMipLevels;
   private static int budgetLeft = 4; // game thread: computes left this frame (bakes first, then flush)
   private static volatile long frames; // game thread: frames rendered (flush runs once a frame)

   private ChunkAo() {
   }

   /**
    * An AO key changed on the Enhancements tab (game thread, after Config's live reload). A texture's colour has its AO
    * multiplied in, so every loaded chunk texture bakes again (flagged as an object change: the bake budgets spread it over
    * the next frames), computing its AO with the new settings, or none when AO is now off. The per-texture state is
    * dropped here; the render thread drops the kept AO textures at its first job of the new generation and skips the
    * jobs queued under the old one (a draw queued from the options screen's Apply can miss the frame's list).
    */
   static void reconfigure() {
      failed = false;
      INFOS.clear();
      PENDING.clear();
      VISIBLE.clear();
      generation++;
      zombie.iso.IsoCell cell = IsoWorld.instance.currentCell;
      int chunks = 0;
      if (cell != null) {
         for (int p = 0; p < 4; p++) {
            zombie.iso.IsoChunkMap cm = cell.getChunkMap(p);
            if (cm == null || cm.ignore || cm.getChunks() == null) {
               continue;
            }
            for (IsoChunk c : cm.getChunks()) {
               if (c != null) {
                  c.getRenderLevels(p).invalidateAll(FBORenderChunk.DIRTY_OBJECT_MODIFY);
                  chunks++;
               }
            }
         }
      }
      Log.info("chunk ao: settings applied (" + (enabled() ? "on" : "off") + "), " + chunks + " chunks bake again");
   }

   private static volatile int generation; // bumped by reconfigure (game thread); jobs carry the one they were queued in
   private static int appliedGeneration; // render thread

   public static boolean enabled() {
      return Overrides.enabled() && (Config.AO || Config.SUN_SHADOWS) && "chunk".equals(Config.AO_MODE) && !failed; // sun shadows share the kernel and the kept term
   }

   public static String stats() {
      String s = "chunk ao: computed=" + computed + " (in bakes " + computedInBake + ") multiplied=" + multiplied + " skipped (no occlusion)=" + skippedEmpty + " neighbour refreshes=" + refreshes + " (skipped, bare border " + refreshesSkipped + ") bare textures=" + bareSkipped + " slow frames without computes=" + heavyFrames + " zoom-out re-bakes=" + mipRebakes + " pending=" + PENDING.size()
         + " pending peak=" + deferredPeak + " first AO: " + firstAos + " (in bakes " + FIRST_AO_MS[0] + ", over budget " + arrivalsOverBudget + ", >100 ms "
         + (FIRST_AO_MS[4] + FIRST_AO_MS[5] + FIRST_AO_MS[6] + FIRST_AO_MS[7]) + String.format(java.util.Locale.ROOT, ", max %.0f ms)", firstAoMsMax)
         + " shown without AO=" + shownWithoutAo + (SunShadow.enabled() ? " sun requeued=" + sunRequeued + " roof columns=" + roofColumnsFound + " | " + SunShadow.stats() : "") + (failed ? " FAILED" : "");
      if (Config.DEV_AO_TIMING) {
         StringBuilder sb = new StringBuilder(s).append(" | geometry bakes by flag:");
         for (int b = 0; b < 15; b++) {
            if (FLAG_TALLY[b] > 0) {
               sb.append(' ').append(FLAG_NAMES[b]).append('=').append(FLAG_TALLY[b]);
            }
         }
         s = sb.toString();
      }
      deferredPeak = PENDING.size();
      return s;
   }

   /** Does this bake change the texture's depth (or create it)? Called before FBORenderChunkManager.endRenderChunkLevel. */
   public static boolean geometryDirty(FBORenderLevels levels, int level, float zoom) {
      boolean dirty = levels.isDirty(level, GEOMETRY_FLAGS, zoom);
      if (dirty && Config.DEV_AO_TIMING) {
         for (int b = 0; b < 15; b++) {
            if ((GEOMETRY_FLAGS & (1L << b)) != 0 && levels.isDirty(level, 1L << b, zoom)) {
               FLAG_TALLY[b]++;
            }
         }
      }
      return dirty;
   }

   private static final long[] FLAG_TALLY = new long[15]; // dev: which dirty flags made bakes recompute
   private static final String[] FLAG_NAMES = {"blood", "corpse", "itemAdd", "itemRemove", "itemModify", "lighting", "objectAdd", "objectRemove",
      "objectModify", "create", "redraw", "cutaways", "trees", "obscuring", "redoCutaways"};

   // ------------------------------------------------------------------------------------------------ game thread

   /** What the game thread knows of a texture (by FBORenderChunk.index). */
   private static final class Info {
      long key;
      boolean has; // a compute was issued: the texture's bakes multiply its AO in
      int mask; // the neighbour slots (3x3, centre excluded) that were in the context of that compute
      boolean pending;
      boolean bare; // skipped as bare: its colour holds no AO (no multiply; its next compute starts from none)
      boolean sunStale; // queued only because the sun moved: computes on sunComputeBudget, a trickle
      int mipLevels; // mip levels 1.. that carry the AO (a bake's stock mipmap build after the in-bake multiply: all)
      long readyFrame; // a neighbour refresh waits a few frames: one refresh for a whole streaming wave
      long bornNs; // the first bake of this owner (latency counters)
      boolean landed; // its first AO was issued (or it is bare): shown without AO until then
      IsoChunk chunk;
      FBORenderChunk rc;
      int minLevel;
      float zoom;
      int playerIndex;
   }

   private static final HashMap<Integer, Info> INFOS = new HashMap<>();
   private static final ArrayList<Info> PENDING = new ArrayList<>();
   private static final java.util.HashSet<Integer> VISIBLE = new java.util.HashSet<>();

   private static long keyOf(IsoChunk c, int minLevel, FBORenderChunk rc) {
      return (((long)c.wx * 73856093L) ^ ((long)c.wy * 19349663L) ^ ((long)minLevel * 83492791L)) * 31L + rc.w * 7919L + rc.h;
   }

   /**
    * Game thread, at the end of a texture's bake (its top level, trees drawn), before FBORenderChunkManager.endRenderChunkLevel
    * closes it: multiply the texture's AO in while its framebuffer is bound, and queue a compute when it has none or the
    * bake changed its depth.
    */
   public static void bakeEnd(FBORenderChunk rc, IsoChunk c, int playerIndex, float zoom, boolean geometry) {
      if (!enabled() || rc == null || rc.tex == null || rc.depth == null || !zombie.iso.fboRenderChunk.FBORenderChunkManager.instance.isCaching()) {
         return;
      }
      int minLevel = rc.getMinLevel();
      long key = keyOf(c, minLevel, rc);
      Info info = INFOS.get(rc.index);
      if (info == null) {
         if (INFOS.size() > 8192) {
            INFOS.clear();
            PENDING.clear();
         }
         info = new Info();
         INFOS.put(rc.index, info);
      }
      if (info.key != key) {
         info.key = key;
         info.has = false;
         info.mask = 0;
         info.bare = false;
         info.bornNs = System.nanoTime();
         info.landed = false;
      }
      info.chunk = c;
      info.rc = rc;
      info.minLevel = minLevel;
      info.zoom = zoom;
      info.playerIndex = playerIndex;
      if ((geometry || !info.has) && bare(c, minLevel, rc.getTopLevel(), playerIndex, zoom)) {
         // nothing but floor here and nothing standing on the neighbours' borders facing us: the AO is 1 everywhere.
         // No compute and no multiply; a neighbour that later brings occluders to our border refreshes us (from no AO).
         info.has = true;
         info.bare = true;
         info.mask = presentMask(c, minLevel, playerIndex, zoom);
         info.mipLevels = 3;
         if (info.pending) {
            info.pending = false;
            PENDING.remove(info);
         }
         info.landed = true;
         bareSkipped++;
         return;
      }
      // a texture's first AO goes into its first bake whatever the slack: queued, a new chunk showed its ground and grass
      // without the AO shading for up to a second at 120 km/h, then darkened (the bake scheduler bounds these bakes)
      boolean arrival = !info.has && Config.AO_ARRIVAL_IN_BAKE;
      if ((geometry || !info.has) && (budgetLeft > 0 || arrival) && rc.fbo != null) {
         // within this frame's budget, or a first AO: compute now, inside the bake (no ratio, no mipmap passes)
         if (budgetLeft > 0) {
            budgetLeft--;
         } else {
            arrivalsOverBudget++;
         }
         Job job = obtain();
         job.kind = Job.COMPUTE_IN_BAKE;
         job.fresh = true; // the bake just drew the colour
         info.bare = false;
         job.index = rc.index;
         job.key = key;
         job.w = rc.w;
         job.h = rc.h;
         job.fbo = 0;
         job.colorTex = rc.tex.getID();
         job.mipmaps = false;
         info.mask = context(job, rc, c, playerIndex, zoom, true);
         info.has = true;
         landed(info, true);
         info.mipLevels = 3;
         info.sunStale = false;
         if (info.pending) {
            info.pending = false;
            PENDING.remove(info);
         }
         computed++;
         computedInBake++;
         SpriteRenderer.instance.drawGeneric(job);
         return;
      }
      if (info.has && !info.bare) {
         info.mipLevels = 3; // multiplied before the bake's mipmap build
         Job job = obtain();
         job.kind = Job.MULTIPLY;
         job.index = rc.index;
         job.key = key;
         job.w = rc.w;
         job.h = rc.h;
         SpriteRenderer.instance.drawGeneric(job);
      }
      if (geometry || !info.has) {
         info.sunStale = false; // it needs a compute of its own now, at the normal budget
      }
      if ((geometry || !info.has) && !info.pending) {
         info.pending = true;
         info.readyFrame = 0L; // (a settle for new textures did not reduce the neighbour refreshes: 2,239 vs 2,272)
         PENDING.add(info);
         deferredPeak = Math.max(deferredPeak, PENDING.size());
         pendingPeakAll = Math.max(pendingPeakAll, PENDING.size());
      }
   }

   /**
    * Game thread, once per frame after the bakes and before the composite: issue up to aoComputeBudget queued computes,
    * oldest first. A texture that is dirty again waits for its bake; one recycled to another chunk is dropped.
    */
   public static void flush(int playerIndex) {
      frames++;
      if (SunShadow.update() && enabled()) {
         // the sun moved a step (or its strength changed): every kept term with shadows is stale; the textures on screen
         // compute first, a few a frame (each applied as new / old); bare textures have no caster in reach at any hour
         for (Info info : INFOS.values()) {
            if (info.has && !info.bare && !info.pending && info.rc != null) {
               info.pending = true;
               info.sunStale = true;
               info.readyFrame = 0L;
               PENDING.add(info);
               sunRequeued++;
            }
         }
         deferredPeak = Math.max(deferredPeak, PENDING.size());
      }
      // a frame after one that missed the cap gets no in-bake computes and one deferred compute every 8 frames (6 a frame
      // added ~1.4 ms at p99 on the capped 120 km/h drive, which is game-thread bound and over the cap most frames; one
      // every slow frame, aoSlowFrameComputes=1, barely shortened the queue and one of two runs read p99 16.1 ms)
      // under a cap the number follows the slack the last frame left (~60 us of GPU a compute)
      int cap = FrameCap.uncappedNow() ? 0 : FrameCap.lockNow();
      int fit = Integer.MAX_VALUE;
      if (Config.AO_SKIP_SLOW_FRAMES && cap > 0 && FrameCap.lastStepNs > 0L) {
         double slackNs = 1.0e9 / cap - FrameCap.lastStepNs;
         fit = slackNs <= 0.0 ? 0 : (int)(slackNs / (SunShadow.enabled() ? COMPUTE_NS_ESTIMATE_SUN : COMPUTE_NS_ESTIMATE));
      }
      boolean heavy = fit == 0;
      int slow = Config.AO_SLOW_FRAME_COMPUTES > 0 ? Config.AO_SLOW_FRAME_COMPUTES : frames % 8 == 0 ? 1 : 0; // default: one every 8 slow frames
      int budget = heavy ? slow : Math.min(fit, Math.max(1, Config.AO_COMPUTE_BUDGET)); // deferred computes this frame
      budgetLeft = heavy ? 0 : Math.min(fit, Math.max(1, Config.AO_BAKE_BUDGET)); // the next frame's bakes
      if (heavy) {
         heavyFrames++;
      }
      if (!enabled()) {
         return;
      }
      int needed = mipLevelsNeeded(playerIndex);
      if (needed > lastMipLevels) {
         // zoomed out past what the deferred computes gave the mipmaps: those textures re-bake (lighting only), and the
         // in-bake multiply before the stock mipmap build puts the AO in every level
         for (Info info : INFOS.values()) {
            if (info.has && info.playerIndex == playerIndex && info.mipLevels < needed && info.rc != null && info.rc.chunk == info.chunk) {
               info.chunk.getRenderLevels(playerIndex).invalidateLevel(info.minLevel, 32L);
               info.mipLevels = 3;
               mipRebakes++;
            }
         }
      }
      lastMipLevels = needed;
      if (PENDING.isEmpty()) {
         return;
      }
      VISIBLE.clear();
      java.util.ArrayList<FBORenderChunk> shown = zombie.iso.fboRenderChunk.FBORenderChunkManager.instance.toRenderThisFrame;
      int withoutAo = 0;
      for (int i = 0; i < shown.size(); i++) {
         VISIBLE.add(shown.get(i).index);
         Info si = INFOS.get(shown.get(i).index);
         if (si != null && !si.landed) {
            withoutAo++; // on screen without its first AO (every one waiting is in PENDING)
         }
      }
      shownWithoutAo += withoutAo;
      if (withoutAo > 0) {
         framesShowingWithoutAo++;
      }
      int sunBudget = heavy ? 0 : Math.max(0, Config.SUN_COMPUTE_BUDGET); // sun-step recomputes: a trickle (a step would otherwise burst every texture's compute into a few frames)
      for (int pass = 0; pass < 2 && budget > 0; pass++) { // the textures composited this frame first
         for (int i = 0; i < PENDING.size() && budget > 0; i++) { // (budget 0: nothing this frame)
            Info info = PENDING.get(i);
            if (info.playerIndex != playerIndex || frames < info.readyFrame || info.rc == null || (pass == 0) != VISIBLE.contains(info.rc.index)) {
               continue;
            }
            FBORenderChunk rc = info.rc;
            FBORenderLevels levels = info.chunk.getRenderLevels(playerIndex);
            boolean owned = rc != null && rc.chunk == info.chunk && rc.isInit && rc.getMinLevel() == info.minLevel
               && levels.getFBOForLevel(info.minLevel, info.zoom) == rc && keyOf(info.chunk, info.minLevel, rc) == info.key;
            if (!owned) {
               info.pending = false;
               PENDING.remove(i--);
               continue;
            }
            if (levels.isDirty(info.minLevel, info.zoom)) {
               continue; // it bakes again first; its bakeEnd keeps it queued
            }
            if (info.sunStale && sunBudget <= 0) {
               continue;
            }
            int fbo = FogPass.fboId(rc.fbo);
            if (fbo <= 0) {
               continue;
            }
            Job job = obtain();
            job.kind = Job.COMPUTE;
            job.fresh = info.bare; // a bare texture's colour holds no AO: the ratio starts from none
            info.bare = false;
            job.index = rc.index;
            job.key = info.key;
            job.w = rc.w;
            job.h = rc.h;
            job.fbo = fbo;
            job.colorTex = rc.tex.getID();
            job.mipmaps = zombie.debug.DebugOptions.instance.fboRenderChunk.mipMaps.getValue() && !rc.highRes;
            job.mipLevels = job.mipmaps ? needed : 0;
            info.mipLevels = job.mipLevels;
            info.mask = context(job, rc, info.chunk, playerIndex, info.zoom, true);
            info.has = true;
            landed(info, false);
            info.pending = false;
            if (info.sunStale) {
               info.sunStale = false;
               sunBudget--;
            }
            PENDING.remove(i--);
            budget--;
            computed++;
            SpriteRenderer.instance.drawGeneric(job);
         }
      }
   }

   /** Latency counters: the texture's first AO was issued now (inside its bake, or deferred). */
   private static void landed(Info info, boolean inBake) {
      if (info.landed) {
         return;
      }
      info.landed = true;
      double ms = inBake ? 0.0 : (System.nanoTime() - info.bornNs) / 1.0e6;
      int b = 0;
      if (!inBake) {
         b = FIRST_AO_EDGES.length;
         for (int i = 1; i < FIRST_AO_EDGES.length; i++) {
            if (ms <= FIRST_AO_EDGES[i]) {
               b = i;
               break;
            }
         }
      }
      FIRST_AO_MS[b]++;
      firstAos++;
      firstAoMsSum += ms;
      firstAoMsMax = Math.max(firstAoMsMax, ms);
   }

   /** The first-AO latency counters since launch, for the harness summary (ao_latency=). */
   public static String latency() {
      return "first_aos=" + firstAos + " in_bake=" + FIRST_AO_MS[0] + " le10ms=" + FIRST_AO_MS[1] + " le50ms=" + FIRST_AO_MS[2] + " le100ms=" + FIRST_AO_MS[3]
         + " le250ms=" + FIRST_AO_MS[4] + " le500ms=" + FIRST_AO_MS[5] + " le1000ms=" + FIRST_AO_MS[6] + " gt1000ms=" + FIRST_AO_MS[7]
         + String.format(java.util.Locale.ROOT, " mean_ms=%.1f max_ms=%.1f", firstAos > 0 ? firstAoMsSum / firstAos : 0.0, firstAoMsMax)
         + " shown_without_ao=" + shownWithoutAo + " frames_showing_without_ao=" + framesShowingWithoutAo + " frames=" + frames
         + " arrivals_over_budget=" + arrivalsOverBudget + " heavy_frames=" + heavyFrames + " pending_peak=" + pendingPeakAll;
   }

   /**
    * Fills the job's context sources (the texture itself first) and returns the neighbour slots present. With refresh,
    * a neighbour whose own AO was computed without this texture in its context is queued to compute again.
    */
   private static int context(Job job, FBORenderChunk rc, IsoChunk c, int playerIndex, float zoom, boolean refresh) {
      int s = FBORenderLevels.getTextureScale(zoom);
      int minLevel = rc.getMinLevel();
      job.ppu = AmbientOcclusion.PX_PER_UNIT * Core.tileScale * s;
      job.n = 0;
      job.isoHalfW = rc.w * 0.5F;
      job.isoInvSA = 1.0F / (s * 32.0F * Core.tileScale);
      job.isoS = s;
      job.isoTop = FBORenderChunk.PIXELS_PER_LEVEL * (rc.getTopLevel() - minLevel + 1) + FBORenderLevels.extraHeightForJumboTrees(minLevel, rc.getTopLevel());
      job.vegetation = Config.AO && vegetationMask(job.veg, c, minLevel, rc.getTopLevel());
      job.sun = SunShadow.enabled() && SunShadow.dir[3] > 0F;
      if (job.sun) {
         System.arraycopy(SunShadow.dir, 0, job.sunDir, 0, 4);
         System.arraycopy(SunShadow.perp, 0, job.sunPerp, 0, 4);
         float wz = SunShadow.world[2], wh = (float)Math.sqrt(SunShadow.world[0] * SunShadow.world[0] + SunShadow.world[1] * SunShadow.world[1]);
         job.sunTanElev = wz / Math.max(1e-3F, wh);
         exteriorMask(job.ext, c, minLevel);
      }
      boolean sunReach = SunShadow.enabled(); // a shadow reaches a whole chunk: any caster here matters to every neighbour
      int casters = -1; // lazily: does anything but floor stand in this chunk (sun shadows)
      job.addSource(rc.depth.getID(), 0.0F, 0.0F, rc.w, rc.h, 0.0F);
      float ox = originX(c, minLevel, rc.w, s);
      float oy = originY(c, minLevel, rc.getTopLevel());
      float depthC = IsoDepthHelper.getChunkDepthData(c.wx, c.wy, c.wx, c.wy, minLevel).depthStart;
      zombie.iso.IsoCell cell = IsoWorld.instance.currentCell;
      int mask = 0;
      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dy == 0) {
               continue;
            }
            IsoChunk nc = cell == null ? null : cell.getChunk(c.wx + dx, c.wy + dy);
            if (nc == null) {
               continue;
            }
            FBORenderLevels levels = nc.getRenderLevels(playerIndex);
            FBORenderChunk nrc = levels.getFBOForLevel(minLevel, zoom);
            if (nrc == null || nrc == rc || !nrc.isInit || nrc.depth == null || nrc.getMinLevel() != minLevel || nrc.depth.getID() <= 0
                  || levels.isDirty(minLevel, 512L, zoom)) { // not baked yet: its texture holds a previous owner's picture
               continue;
            }
            float nox = originX(nc, minLevel, nrc.w, s);
            float noy = originY(nc, minLevel, nrc.getTopLevel());
            float depthN = IsoDepthHelper.getChunkDepthData(c.wx, c.wy, nc.wx, nc.wy, minLevel).depthStart;
            job.addSource(nrc.depth.getID(), (nox - ox) * s, (noy - oy) * s, nrc.w, nrc.h, depthN - depthC);
            mask |= slot(dx, dy);
            Info ni = INFOS.get(nrc.index);
            boolean reach = false;
            if (refresh && ni != null && ni.has && !ni.pending && ni.key == keyOf(nc, minLevel, nrc) && (ni.mask & slot(-dx, -dy)) == 0) {
               if (sunReach && casters < 0) {
                  casters = occluders(c, minLevel, rc.getTopLevel()) ? 1 : 0;
               }
               reach = sunReach ? casters == 1 : borderOccluders(c, minLevel, rc.getTopLevel(), dx, dy);
            }
            if (refresh && ni != null && ni.has && !ni.pending && ni.key == keyOf(nc, minLevel, nrc) && (ni.mask & slot(-dx, -dy)) == 0
                  && !reach) {
               ni.mask |= slot(-dx, -dy); // nothing stands on our border facing it: its AO is already right
               refreshesSkipped++;
            }
            if (refresh && ni != null && ni.has && !ni.pending && ni.key == keyOf(nc, minLevel, nrc) && (ni.mask & slot(-dx, -dy)) == 0
                  && reach) {
               ni.pending = true;
               ni.readyFrame = frames + SETTLE_FRAMES; // more neighbours of the same wave may still arrive: one refresh for all
               PENDING.add(ni);
               refreshes++;
            }
         }
      }
      return mask;
   }

   /**
    * The mip levels the composite samples now: the chunk textures are drawn at 1 / zoom of their size into an offscreen
    * buffer at the render scale, so a texel covers zoom / renderScale pixels; trilinear filtering reads floor(log2) + 1.
    */
   private static int mipLevelsNeeded(int playerIndex) {
      float minification = Core.getInstance().getZoom(playerIndex) / Math.max(0.1F, RenderScale.scale());
      if (minification <= 1.01F) {
         return 0;
      }
      return Math.min(3, (int)Math.floor(Math.log(minification) / Math.log(2.0)) + 1);
   }

   /**
    * Does anything but floor stand on the chunk's border squares facing the neighbour at (dx, dy) (a column, a row, or the
    * corner square), on the texture's levels? Only those can occlude the neighbour's side within the AO radius (< 1 square).
    */
   private static boolean borderOccluders(IsoChunk c, int minLevel, int topLevel, int dx, int dy) {
      for (int z = minLevel; z <= topLevel; z++) {
         for (int i = 0; i < 8; i++) {
            int x = dx < 0 ? 0 : dx > 0 ? 7 : i;
            int y = dy < 0 ? 0 : dy > 0 ? 7 : i;
            zombie.iso.IsoGridSquare sq = c.getGridSquare(x, y, z);
            if (sq != null) {
               zombie.util.list.PZArrayList<zombie.iso.IsoObject> objects = sq.getObjects();
               for (int k = 0; k < objects.size(); k++) {
                  zombie.iso.IsoObject o = objects.get(k);
                  if (o != null && (!o.isFloor() || o.hasAttachedAnimSprites())) { // grass and bushes often hang off the floor object
                     return true;
                  }
               }
            }
            if (dx != 0 && dy != 0) {
               break; // the corner square alone
            }
         }
      }
      return false;
   }

   /**
    * No object but floor on the texture's levels of this chunk, and none on the present neighbours' borders facing it
    * (with sun shadows: none anywhere in the present neighbours, a shadow reaches across a whole chunk).
    */
   private static boolean bare(IsoChunk c, int minLevel, int topLevel, int playerIndex, float zoom) {
      if (occluders(c, minLevel, topLevel)) {
         return false;
      }
      boolean sun = SunShadow.enabled();
      zombie.iso.IsoCell cell = IsoWorld.instance.currentCell;
      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            IsoChunk nc = (dx == 0 && dy == 0) || cell == null ? null : cell.getChunk(c.wx + dx, c.wy + dy);
            if (nc != null && nc.getRenderLevels(playerIndex).getFBOForLevel(minLevel, zoom) != null
                  && (sun ? occluders(nc, minLevel, topLevel) : borderOccluders(nc, minLevel, topLevel, -dx, -dy))) {
               return false;
            }
         }
      }
      return true;
   }

   /** Does anything but floor stand in this chunk on these levels (grass and bushes hanging off floor objects count)? */
   private static boolean occluders(IsoChunk c, int minLevel, int topLevel) {
      for (int z = minLevel; z <= topLevel; z++) {
         for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
               zombie.iso.IsoGridSquare sq = c.getGridSquare(x, y, z);
               if (sq == null) {
                  continue;
               }
               zombie.util.list.PZArrayList<zombie.iso.IsoObject> objects = sq.getObjects();
               for (int k = 0; k < objects.size(); k++) {
                  zombie.iso.IsoObject o = objects.get(k);
                  if (o != null && (!o.isFloor() || o.hasAttachedAnimSprites())) { // grass and bushes often hang off the floor object
                     return true;
                  }
               }
            }
         }
      }
      return false;
   }

   /**
    * sunShadows: which squares around the chunk are outdoors (IsoFlagType.exterior), as bits (row-major, 16 x 16 from
    * VEG_MARGIN squares before the chunk's corner), plane 0 = the texture's lower level, 1 = the upper. Rooms have a
    * roof the texture does not show: the sun never reaches them.
    */
   private static void exteriorMask(int[] ext, IsoChunk c, int minLevel) {
      java.util.Arrays.fill(ext, 0);
      roofColumns(ext, c, minLevel);
      zombie.iso.IsoCell cell = IsoWorld.instance.currentCell;
      if (cell == null) {
         return;
      }
      int x0 = c.wx * 8 - VEG_MARGIN;
      int y0 = c.wy * 8 - VEG_MARGIN;
      for (int plane = 0; plane < 2; plane++) {
         for (int y = 0; y < VEG_SIDE; y++) {
            for (int x = 0; x < VEG_SIDE; x++) {
               IsoGridSquare sq = cell.getGridSquare(x0 + x, y0 + y, minLevel + plane);
               // a square that is not loaded (or not there: open air on the upper level) counts as outdoors
               if (sq == null || sq.isOutside()) {
                  int bit = y * VEG_SIDE + x;
                  ext[plane * 8 + (bit >> 5)] |= 1 << (bit & 31);
               }
            }
         }
      }
   }

   /** The neighbour slots whose texture of this level pair and zoom exists and is baked. */
   private static int presentMask(IsoChunk c, int minLevel, int playerIndex, float zoom) {
      zombie.iso.IsoCell cell = IsoWorld.instance.currentCell;
      int mask = 0;
      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            IsoChunk nc = (dx == 0 && dy == 0) || cell == null ? null : cell.getChunk(c.wx + dx, c.wy + dy);
            if (nc == null) {
               continue;
            }
            FBORenderLevels levels = nc.getRenderLevels(playerIndex);
            FBORenderChunk nrc = levels.getFBOForLevel(minLevel, zoom);
            if (nrc != null && nrc.isInit && !levels.isDirty(minLevel, 512L, zoom)) {
               mask |= slot(dx, dy);
            }
         }
      }
      return mask;
   }

   /** The vegetation mask covers the chunk and this many squares around it (neighbours' tree crowns reach in). */
   private static final int VEG_MARGIN = 4;
   private static final int VEG_SIDE = 8 + 2 * VEG_MARGIN; // 16: 256 bits, 8 ints a plane
   private static final int TREE_REACH = 3; // a baked tree's crown spans up to 7 squares along its screen row

   /**
    * aoStrengthVegetationPct: which squares around the chunk hold vegetation, as bits (row-major, 16 x 16 from VEG_MARGIN
    * squares before the chunk's corner): planes 0-2 for the texture's first three levels (bushes, grass, flowers: sprites
    * flagged isBush / canBeRemoved / vegitation), plane 3 for tree crowns at any height (the tree's square and the squares
    * its crown covers along its screen row, x + k, y - k). The kernel reconstructs each "object" pixel's square from its
    * depth and texture position and looks it up. Nothing to build (false) when vegetation and objects share a strength.
    */
   private static boolean vegetationMask(int[] veg, IsoChunk c, int minLevel, int topLevel) {
      java.util.Arrays.fill(veg, 0);
      if (Config.AO_STRENGTH_VEGETATION_PCT == Config.AO_STRENGTH_OBJECT_PCT) {
         return false;
      }
      zombie.iso.IsoCell cell = IsoWorld.instance.currentCell;
      if (cell == null) {
         return false;
      }
      int x0 = c.wx * 8 - VEG_MARGIN;
      int y0 = c.wy * 8 - VEG_MARGIN;
      boolean any = false;
      for (int z = minLevel; z <= topLevel; z++) {
         int plane = Math.min(2, z - minLevel);
         for (int y = y0 - TREE_REACH; y < y0 + VEG_SIDE + TREE_REACH; y++) {
            for (int x = x0 - TREE_REACH; x < x0 + VEG_SIDE + TREE_REACH; x++) {
               IsoGridSquare sq = cell.getGridSquare(x, y, z);
               if (sq == null) {
                  continue;
               }
               IsoObject[] objects = (IsoObject[])sq.getObjects().getElements();
               int count = sq.getObjects().size();
               for (int i = 0; i < count; i++) {
                  IsoObject o = objects[i];
                  if (o instanceof IsoTree) {
                     for (int k = -TREE_REACH; k <= TREE_REACH; k++) {
                        any |= markVegetation(veg, 3, x + k - x0, y - k - y0);
                        any |= markVegetation(veg, 3, x + k + 1 - x0, y - k - y0);
                     }
                  } else if (isVegetation(o.getSprite())) {
                     any |= markVegetation(veg, plane, x - x0, y - y0);
                  }
               }
            }
         }
      }
      return any;
   }

   /**
    * Plane 2 of the exterior mask (ints 16..23): the columns (x, y) with a roof tile on the texture's levels or the one
    * above. Roof sprites' depth is a staircase whose steps snap to the floor / wall planes: the sun term shaded every
    * tile's riser and cast each step onto the next (an egg-crate pattern, cs-bt-on*); pixels over a roof column take none.
    */
   private static void roofColumns(int[] ext, IsoChunk c, int minLevel) {
      zombie.iso.IsoCell cell = IsoWorld.instance.currentCell;
      if (cell == null) {
         return;
      }
      int x0 = c.wx * 8 - VEG_MARGIN;
      int y0 = c.wy * 8 - VEG_MARGIN;
      for (int y = 0; y < VEG_SIDE; y++) {
         for (int x = 0; x < VEG_SIDE; x++) {
            for (int z = minLevel; z <= minLevel + 2; z++) {
               IsoGridSquare sq = cell.getGridSquare(x0 + x, y0 + y, z);
               if (sq != null && roof(sq)) {
                  int bit = y * VEG_SIDE + x;
                  ext[16 + (bit >> 5)] |= 1 << (bit & 31);
                  roofColumnsFound++;
                  break;
               }
            }
         }
      }
   }

   private static long roofColumnsFound;

   /** Does a roof tile (a sprite with a RoofGroup, or from the roofs_ sheets) stand on this square? */
   private static boolean roof(IsoGridSquare sq) {
      zombie.util.list.PZArrayList<IsoObject> objects = sq.getObjects();
      for (int k = 0; k < objects.size(); k++) {
         IsoObject o = objects.get(k);
         IsoSprite sp = o == null ? null : o.getSprite();
         if (sp != null && (sp.getProperties() != null && sp.getProperties().get("RoofGroup") != null || sp.getName() != null && sp.getName().startsWith("roofs_"))) {
            return true;
         }
      }
      return false;
   }

   private static boolean isVegetation(IsoSprite sprite) {
      return sprite != null && !sprite.solidfloor
         && (sprite.isBush || sprite.canBeRemoved || sprite.getProperties().has(IsoFlagType.vegitation));
   }

   private static boolean markVegetation(int[] veg, int plane, int lx, int ly) {
      if (lx < 0 || ly < 0 || lx >= VEG_SIDE || ly >= VEG_SIDE) {
         return false;
      }
      int bit = ly * VEG_SIDE + lx;
      veg[plane * 8 + (bit >> 5)] |= 1 << (bit & 31);
      return true;
   }

   private static int slot(int dx, int dy) {
      return 1 << ((dy + 1) * 3 + dx + 1);
   }

   /** The texture's left edge in world pixels (FBORenderChunk.renderInWorldMainThread without the camera). */
   private static float originX(IsoChunk c, int minLevel, int textureW, int s) {
      return IsoUtils.XToScreen(c.wx * 8, c.wy * 8, minLevel, 0) - textureW / (float)s / 2.0F;
   }

   /** The texture's top edge in world pixels. */
   private static float originY(IsoChunk c, int minLevel, int topLevel) {
      return IsoUtils.YToScreen(c.wx * 8, c.wy * 8, minLevel, 0) - FBORenderChunk.PIXELS_PER_LEVEL * (topLevel - minLevel + 1)
         - FBORenderLevels.extraHeightForJumboTrees(minLevel, topLevel);
   }

   // ------------------------------------------------------------------------------------------------ jobs

   private static final ArrayDeque<Job> POOL = new ArrayDeque<>();

   private static Job obtain() {
      Job j;
      synchronized (POOL) {
         j = POOL.poll();
      }
      if (j == null) {
         j = new Job();
      }
      j.generation = generation;
      return j;
   }

   /** A multiply inside a bake, or a compute with its context sources (index 0 = the texture itself). */
   static final class Job extends TextureDraw.GenericDrawer {
      static final int MULTIPLY = 0;
      static final int COMPUTE = 1;
      static final int COMPUTE_IN_BAKE = 2; // inside the bake: the colour was just drawn, so compute and multiply, no ratio
      int kind;
      int generation; // ChunkAo.generation when it was queued: a job of older settings is skipped
      boolean fresh;
      int index;
      long key;
      int w;
      int h;
      int fbo; // compute: the texture's framebuffer
      int colorTex;
      boolean mipmaps;
      int mipLevels; // deferred compute: levels 1..mipLevels get the ratio too
      float ppu;
      int n;
      final int[] srcTex = new int[9];
      final float[] srcX = new float[9]; // the source's texel offset from this texture (rows top-down)
      final float[] srcY = new float[9];
      final int[] srcW = new int[9];
      final int[] srcH = new int[9];
      final float[] srcDepth = new float[9]; // added to the source's depth to put it in this texture's depth
      final int[] veg = new int[32]; // vegetation squares, 4 planes of 16 x 16 bits (vegetationMask)
      boolean vegetation; // the mask has a square set
      boolean sun; // sun shadows: sunDir / sunPerp / ext are set
      final float[] sunDir = new float[4]; // SunShadow.dir: view-space direction to the sun, w = strength
      final float[] sunPerp = new float[4]; // SunShadow.perp: across it, w = tan of the penumbra angle
      final int[] ext = new int[24]; // exterior squares, 2 planes of 16 x 16 bits, then the roof columns (exteriorMask)
      float sunTanElev; // tan of the sun's elevation (the march length)
      float isoHalfW; // texels from the texture's left edge to the chunk corner's screen x
      float isoInvSA; // units of (x - y) per texel
      float isoS; // texels per world pixel
      float isoTop; // world pixels from the texture's top edge to the chunk corner at the lowest level

      void addSource(int tex, float x, float y, int sw, int sh, float depth) {
         this.srcTex[this.n] = tex;
         this.srcX[this.n] = x;
         this.srcY[this.n] = y;
         this.srcW[this.n] = sw;
         this.srcH[this.n] = sh;
         this.srcDepth[this.n] = depth;
         this.n++;
      }

      @Override
      public void render() {
         try {
            if (this.generation != ChunkAo.generation) {
               return; // queued before the AO settings changed: its texture bakes again under the new ones
            }
            if (appliedGeneration != ChunkAo.generation) {
               appliedGeneration = ChunkAo.generation;
               GL.clear(); // the kept AO of the old settings
            }
            if (this.kind == MULTIPLY) {
               GL.multiply(this);
            } else { // COMPUTE, COMPUTE_IN_BAKE
               GL.compute(this);
            }
         } catch (Throwable t) {
            fail("render: " + t);
         }
      }

      @Override
      public void postRender() {
         synchronized (POOL) {
            if (POOL.size() < 256) {
               POOL.push(this);
            }
         }
      }
   }

   private static void fail(String why) {
      if (!failed) {
         failed = true;
         Log.warn("chunk ao: " + why + "; off for the rest of the session");
      }
   }

   // ------------------------------------------------------------------------------------------------ render thread

   private static final Gl GL = new Gl();

   /** The AO kept for one FBORenderChunk (by index): R8 at the AO scale, and the texture it was computed for. */
   private static final class Entry {
      int tex;
      int fbo;
      int aw;
      int ah;
      long key;
      boolean valid;
      boolean checked; // the first multiply since the compute ran under an occlusion query
      int query;
      boolean queryPending;
      boolean empty; // that multiply changed no pixel: the texture has no occlusion, its bakes skip the multiply
   }

   private static final class Gl {
      private final HashMap<Integer, Entry> entries = new HashMap<>();
      private int aoProgram;
      private int aoOnlyProgram; // AO_PASS: no sun code (its registers)
      private final int[] uAoOnly = new int[15];
      private int blurProgram;
      private int mulProgram;
      private int ratioProgram;
      private int copyProgram;
      private int mipFbo;
      private int quadVbo;
      // shared scratch, grown to the largest texture: the context depth (R32F), the raw AO (RG32F: AO, depth), the new AO (R8)
      private final FloatBuffer rects = BufferUtils.createFloatBuffer(36);
      private final FloatBuffer offs = BufferUtils.createFloatBuffer(9);
      private int rawTex;
      private int rawFbo;
      private int newTex;
      private int newFbo;
      private int rawW;
      private int rawH;
      private final int[] viewport = new int[4];
      private final int[] uAo = new int[15];
      private final int[] uBlur = new int[3];
      private final int[] uMul = new int[2];
      private final int[] uRatio = new int[4];
      private final int[] uCopy = new int[2];
      private boolean logged;

      private static float scale() {
         return Math.max(25, Math.min(100, Config.AO_SCALE_PCT)) / 100.0F; // AO texels per texture texel
      }

      private Entry entry(int index, int aw, int ah) {
         Entry e = this.entries.get(index);
         if (e == null) {
            if (this.entries.size() > 8192) {
               this.clear();
            }
            e = new Entry();
            this.entries.put(index, e);
         }
         if (e.aw != aw || e.ah != ah) {
            if (e.tex != 0) {
               GL30.glDeleteFramebuffers(e.fbo);
               GL11.glDeleteTextures(e.tex);
            }
            e.tex = texture(aw, ah, GL30.GL_R8, GL30.GL_RED, GL11.GL_UNSIGNED_BYTE, true);
            e.fbo = fbo(e.tex);
            e.aw = aw;
            e.ah = ah;
            e.valid = false;
         }
         return e;
      }

      private void begin() {
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glDisable(GL11.GL_STENCIL_TEST);
         GL11.glDisable(GL11.GL_ALPHA_TEST);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDisable(GL11.GL_BLEND);
         GL11.glDepthMask(false);
         GL11.glColorMask(true, true, true, true);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.quadVbo);
         GL20.glEnableVertexAttribArray(0);
         for (int i = 1; i < 5; i++) {
            GL20.glDisableVertexAttribArray(i);
         }
         GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
      }

      /** Inside a bake (the texture's framebuffer bound): its colour times the AO it has, if it has one for this texture. */
      void multiply(Job job) {
         if (failed || (this.aoProgram == 0 && !this.init())) {
            return;
         }
         float sc = scale();
         int aw = Math.max(1, (int)Math.ceil(job.w * sc));
         int ah = Math.max(1, (int)Math.ceil(job.h * sc));
         Entry e = this.entries.get(job.index);
         if (e == null || !e.valid || e.key != job.key || e.aw != aw || e.ah != ah) {
            return; // a compute is queued
         }
         boolean dev = Config.DEV_AO_VIEW > 0;
         if (e.queryPending && GL15.glGetQueryObjecti(e.query, GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
            e.empty = GL15.glGetQueryObjecti(e.query, GL15.GL_QUERY_RESULT) == 0;
            e.queryPending = false;
         }
         if (e.empty && !dev) {
            skippedEmpty++;
            return;
         }
         GL11.glGetIntegerv(GL11.GL_VIEWPORT, this.viewport);
         this.stamp(0);
         this.begin();
         this.drawMultiply(job, e, sc, aw, ah);
         this.stamp(1);
         this.collect(false);
         restore(this.viewport);
      }

      /** The texture's colour (its framebuffer bound) times its AO; the first time after a compute under an occlusion query. */
      private void drawMultiply(Job job, Entry e, float sc, int aw, int ah) {
         boolean dev = Config.DEV_AO_VIEW > 0;
         GL11.glViewport(0, 0, job.w, job.h);
         GL20.glUseProgram(this.mulProgram);
         GL20.glUniform1i(this.uMul[0], 0);
         GL20.glUniform4f(this.uMul[1], sc / aw, sc / ah, 1.0F, Config.DEV_AO_VIEW);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, e.tex);
         GL11.glEnable(GL11.GL_BLEND);
         if (Config.DEV_AO_VIEW > 0) {
            GL11.glBlendFunc(GL11.GL_DST_ALPHA, GL11.GL_ZERO); // the AO term alone, premultiplied by the texture's alpha
         } else {
            GL11.glBlendFunc(GL11.GL_ZERO, GL11.GL_SRC_COLOR);
         }
         GL11.glColorMask(true, true, true, false);
         boolean check = !e.checked && !dev;
         if (check) {
            if (e.query == 0) {
               e.query = GL15.glGenQueries();
            }
            GL15.glBeginQuery(GL33.GL_ANY_SAMPLES_PASSED, e.query);
         }
         GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
         if (check) {
            GL15.glEndQuery(GL33.GL_ANY_SAMPLES_PASSED);
            e.checked = true;
            e.queryPending = true;
         }
         GL11.glDisable(GL11.GL_BLEND);
         GL11.glColorMask(true, true, true, true);
         multiplied++;
      }

      /** The kernel's uniforms for one variant (the sources stay bound on units 0..8; rects / offs filled and flipped). */
      private void kernelUniforms(int[] u, Job job, float geoX) {
         GL20.glUniform4fv(u[0], this.rects);
         GL20.glUniform1fv(u[1], this.offs);
         GL20.glUniform1i(u[4], job.n);
         float radius = Math.max(0.05F, Config.AO_RADIUS_PCT / 100.0F);
         GL20.glUniform4f(u[2], geoX, job.ppu, Config.AO_CHUNK_FLIP ? -1.0F : 1.0F, 0.0F);
         GL20.glUniform4f(u[3], radius * job.ppu, Math.max(0.01F, Config.AO_THICKNESS_PCT / 100.0F), AmbientOcclusion.UNITS_PER_DEPTH, radius);
         if (job.vegetation) {
            GL30.glUniform1uiv(u[6], job.veg);
         }
         GL20.glUniform4f(u[7], job.isoHalfW, job.isoInvSA, job.isoS, job.isoTop); // (the vegetation and the exterior lookups)
         GL20.glUniform4f(u[9], Config.AO ? 1.0F : 0.0F, Config.DEV_SUN_VIEW, 0.0F, 0.0F);
         if (job.sun) {
            GL20.glUniform4f(u[10], job.sunDir[0], job.sunDir[1], job.sunDir[2], job.sunDir[3]);
            GL20.glUniform4f(u[11], job.sunPerp[0], job.sunPerp[1], job.sunPerp[2], job.sunPerp[3]);
            // the march reaches as far as a caster of the texture's two levels (4.9 squares tall) throws a shadow at this sun
            // height, at most sunShadowLengthPct; the steps shrink with it (a high sun: short shadows, fewer samples as dense)
            float maxLen = Math.max(0.5F, Config.SUN_SHADOW_LENGTH_PCT / 100.0F);
            float len = Math.max(1.0F, Math.min(maxLen, 2.0F * 2.4494897F / Math.max(0.05F, job.sunTanElev)));
            int steps = Math.max(8, Math.min(32, Math.round(Math.max(1, Config.SUN_SHADOW_STEPS) * len / maxLen)));
            GL20.glUniform4f(u[12], len * job.ppu, Math.max(0.05F, Config.SUN_SHADOW_THICKNESS_PCT / 100.0F), steps, 1.0F);
            GL30.glUniform1uiv(u[13], job.ext);
         } else {
            GL20.glUniform4f(u[10], 0.0F, 0.0F, 0.0F, 0.0F);
         }
         GL20.glUniform4f(u[8], 16.0F * Core.tileScale, 96.0F * Core.tileScale, SQUARE_DEPTH_HALF, job.vegetation ? 1.0F : 0.0F);
         // the strengths go into the kept AO (so the multiply / ratio passes run at 1); read per compute: the Enhancements tab changes them live
         GL20.glUniform4f(u[5], strength(Config.AO_STRENGTH_FLOOR_PCT), strength(Config.AO_STRENGTH_WALL_PCT), strength(Config.AO_STRENGTH_OBJECT_PCT),
            strength(Config.AO_STRENGTH_VEGETATION_PCT));
      }

      private static float strength(int pct) {
         return Math.max(0.0F, pct / 100.0F);
      }

      /** Outside the bakes: the texture's AO from its context, applied onto its colour as new / old, then kept. */
      void compute(Job job) {
         boolean inBake = job.kind == Job.COMPUTE_IN_BAKE;
         if (failed || job.srcTex[0] <= 0 || job.fbo <= 0 && !inBake) {
            return;
         }
         if (this.aoProgram == 0 && !this.init()) {
            fail("shaders did not compile");
            return;
         }
         int previousFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
         if (inBake) {
            job.fbo = previousFbo; // the texture's own framebuffer, bound by the bake
         }
         GL11.glGetIntegerv(GL11.GL_VIEWPORT, this.viewport);
         float sc = scale();
         int aw = Math.max(1, (int)Math.ceil(job.w * sc));
         int ah = Math.max(1, (int)Math.ceil(job.h * sc));
         float ppuAo = job.ppu * sc; // AO texels per square
         Entry e = this.entry(job.index, aw, ah);
         boolean hadOld = e.valid && e.key == job.key && !job.fresh;
         this.stamp(0);
         this.begin();
         if (!this.ensureScratch(aw, ah)) {
            fail("scratch buffers incomplete");
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
            restore(this.viewport);
            return;
         }
         // 1. (no context pass) 2. AO at the reduced size: the kernel reads the texture's own depth and its neighbours'
         // directly, each at its offset (a rect test per source per tap, one fetch where it covers the tap)
         this.stamp(4);
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.rawFbo);
         GL11.glViewport(0, 0, aw, ah);
         int[] uK = job.sun ? this.uAo : this.uAoOnly; // no sun now (off, night): the variant without the sun code
         GL20.glUseProgram(job.sun ? this.aoProgram : this.aoOnlyProgram);
         this.rects.clear();
         this.offs.clear();
         for (int i = 0; i < 9; i++) {
            boolean has = i < job.n;
            this.rects.put(has ? job.srcX[i] : 0.0F).put(has ? job.srcY[i] : 0.0F).put(has ? job.srcW[i] : 0.0F).put(has ? job.srcH[i] : 0.0F);
            this.offs.put(has ? job.srcDepth[i] : 0.0F);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, has ? job.srcTex[i] : 0);
         }
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         this.rects.flip();
         this.offs.flip();
         this.kernelUniforms(uK, job, 1.0F / sc);
         GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
         for (int i = 8; i >= 1; i--) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         }
         GL13.glActiveTexture(GL13.GL_TEXTURE0);

         this.stamp(5);
         // 3. 4x4 depth-aware box into the new AO (in a bake straight into the texture's kept AO)
         boolean direct = inBake || !hadOld; // nothing to keep for a ratio: straight into the texture's AO
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, direct ? e.fbo : this.newFbo);
         GL20.glUseProgram(this.blurProgram);
         GL20.glUniform1i(this.uBlur[0], 0);
         GL20.glUniform4f(this.uBlur[1], AmbientOcclusion.UNITS_PER_DEPTH, 1.0F / ppuAo, aw - 1, ah - 1);
         GL20.glUniform1f(this.uBlur[2], Config.DEV_SUN_VIEW > 0 ? 1.0F : 0.0F);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.rawTex);
         GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
         if (Config.DEV_AO_DUMP_FRAME > 0 && computed == Config.DEV_AO_DUMP_FRAME) {
            this.dump(job, aw, ah, sc);
            this.dumpTerm(direct ? e.fbo : this.newFbo, aw, ah);
         }
         this.stamp(1);
         if (inBake) {
            // 4'. the bake just drew the colour: multiply the new AO in, the stock mipmap build follows at the bake's end
            e.key = job.key;
            e.valid = true;
            e.checked = false;
            e.queryPending = false;
            e.empty = false;
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
            this.stamp(2);
            this.drawMultiply(job, e, sc, aw, ah);
            this.stamp(3);
            this.collect(true);
            restore(this.viewport);
            return;
         }

         // 4. onto the texture: colour * new / old (old = 1 for a texture that had none)
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, job.fbo);
         GL11.glViewport(0, 0, job.w, job.h);
         GL20.glUseProgram(this.ratioProgram);
         GL20.glUniform1i(this.uRatio[0], 0);
         GL20.glUniform1i(this.uRatio[1], 1);
         GL20.glUniform4f(this.uRatio[2], sc / aw, sc / ah, 1.0F, hadOld ? 1.0F : 0.0F);
         GL20.glUniform4f(this.uRatio[3], direct ? 1.0F : (float)aw / this.rawW, direct ? 1.0F : (float)ah / this.rawH, Config.DEV_AO_VIEW, 0.0F);
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, e.tex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, direct ? e.tex : this.newTex);
         GL11.glEnable(GL11.GL_BLEND);
         if (Config.DEV_AO_VIEW > 0) {
            GL11.glBlendFunc(GL11.GL_DST_ALPHA, GL11.GL_ZERO);
         } else {
            GL11.glBlendFunc(GL11.GL_DST_COLOR, GL11.GL_SRC_COLOR); // 2 src dst: src = ratio / 2 darkens and lightens
         }
         GL11.glColorMask(true, true, true, false);
         GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
         this.stamp(2);
         if (job.mipmaps && job.mipLevels > 0 && job.colorTex > 0 && !Config.DEV_AO_NO_MIPS) {
            // the bake's mipmaps get the same ratio level by level (glGenerateMipmap costs ~65 us on a 1024 texture)
            if (this.mipFbo == 0) {
               this.mipFbo = GL30.glGenFramebuffers();
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.mipFbo);
            GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
            // levels 1..3 only: every level is a framebuffer re-attachment (~3 us each on NVIDIA), and at the widest zoom with
            // the upscaler at 50 % the composite samples between levels 2 and 3; smaller levels show no AO detail anyway
            int levels = Math.min(job.mipLevels + 1, 32 - Integer.numberOfLeadingZeros(Math.max(job.w, job.h)));
            for (int k = 1; k < levels; k++) {
               GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, job.colorTex, k);
               GL11.glViewport(0, 0, Math.max(1, job.w >> k), Math.max(1, job.h >> k));
               GL20.glUniform4f(this.uRatio[2], sc / aw * (1 << k), sc / ah * (1 << k), 1.0F, hadOld ? 1.0F : 0.0F);
               GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
            }
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
         }
         GL11.glDisable(GL11.GL_BLEND);
         GL11.glColorMask(true, true, true, true);

         // 5. keep the new AO (a first compute wrote it in place already)
         if (!direct) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, e.fbo);
            GL11.glViewport(0, 0, aw, ah);
            GL20.glUseProgram(this.copyProgram);
            GL20.glUniform1i(this.uCopy[0], 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.newTex);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
         }
         e.key = job.key;
         e.valid = true;
         e.checked = false;
         e.queryPending = false;
         e.empty = false;
         this.stamp(3);
         this.collect(true);
         if (!this.logged) {
            this.logged = true;
            Log.info(String.format("chunk ao: first texture %dx%d, AO %dx%d, %.1f AO texels per square, %d depth sources",
               job.w, job.h, aw, ah, ppuAo, job.n));
         }
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
         restore(this.viewport);
      }

      // dev (devAoTiming): GPU time per job from timestamp queries, 64 jobs in flight
      private int[] queries;
      private int slot;
      private final boolean[] pending = new boolean[64];
      private final boolean[] slotCompute = new boolean[64];
      private long computeNs;
      private long applyNs;
      private long mipNs;
      private long ctxNs;
      private long kernelNs;
      private long computeJobs;
      private long mulNs;
      private long mulJobs;
      private long allNs; // every job's GPU time since the last line
      private long lastFrames = -1;

      private void stamp(int k) {
         if (!Config.DEV_AO_TIMING) {
            return;
         }
         if (this.queries == null) {
            this.queries = new int[64 * 6];
            GL15.glGenQueries(this.queries);
         }
         GL33.glQueryCounter(this.queries[this.slot * 6 + k], GL33.GL_TIMESTAMP);
      }

      private void collect(boolean compute) {
         if (!Config.DEV_AO_TIMING) {
            return;
         }
         if (!compute) {
            GL33.glQueryCounter(this.queries[this.slot * 6 + 2], GL33.GL_TIMESTAMP);
            GL33.glQueryCounter(this.queries[this.slot * 6 + 3], GL33.GL_TIMESTAMP);
         }
         this.pending[this.slot] = true;
         this.slotCompute[this.slot] = compute;
         this.slot = (this.slot + 1) & 63;
         if (this.pending[this.slot]) {
            int b = this.slot * 6;
            if (GL15.glGetQueryObjecti(this.queries[b + 3], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
               long t0 = GL33.glGetQueryObjecti64(this.queries[b], GL15.GL_QUERY_RESULT);
               long t1 = GL33.glGetQueryObjecti64(this.queries[b + 1], GL15.GL_QUERY_RESULT);
               long t2 = GL33.glGetQueryObjecti64(this.queries[b + 2], GL15.GL_QUERY_RESULT);
               long t3 = GL33.glGetQueryObjecti64(this.queries[b + 3], GL15.GL_QUERY_RESULT);
               this.allNs += t3 - t0;
               if (this.slotCompute[this.slot]) {
                  this.computeNs += t1 - t0;
                  long t4 = GL33.glGetQueryObjecti64(this.queries[b + 4], GL15.GL_QUERY_RESULT);
                  long t5 = GL33.glGetQueryObjecti64(this.queries[b + 5], GL15.GL_QUERY_RESULT);
                  this.ctxNs += t4 - t0;
                  this.kernelNs += t5 - t4;
                  this.applyNs += t2 - t1;
                  this.mipNs += t3 - t2;
                  this.computeJobs++;
               } else {
                  this.mulNs += t1 - t0;
                  this.mulJobs++;
               }
               if ((this.computeJobs + this.mulJobs) % 200 == 0) {
                  double cj = Math.max(1L, this.computeJobs);
                  long f = frames;
                  double perFrame = this.lastFrames < 0 || f <= this.lastFrames ? -1.0 : this.allNs / 1e3 / (f - this.lastFrames);
                  this.lastFrames = f;
                  this.allNs = 0L;
                  Log.info(String.format("chunk ao gpu us/frame=%.2f | us/job: compute=%.1f (context %.1f, kernel %.1f) + apply %.1f + mips/copy %.1f (%d jobs) multiply=%.1f (%d jobs) | %s",
                     perFrame, this.computeNs / 1e3 / cj, this.ctxNs / 1e3 / cj, this.kernelNs / 1e3 / cj, this.applyNs / 1e3 / cj, this.mipNs / 1e3 / cj,
                     this.computeJobs, this.mulJobs == 0 ? 0.0 : this.mulNs / 1e3 / this.mulJobs, this.mulJobs, stats()));
                  this.computeNs = this.applyNs = this.mipNs = this.mulNs = this.ctxNs = this.kernelNs = 0L;
                  this.computeJobs = this.mulJobs = 0L;
               }
            }
            this.pending[this.slot] = false;
         }
      }

      /** Dev (devAoDumpFrame = the Nth computed texture): its raw AO (AO, depth) to ~/Zomboid/pzopt-chunkao-*. */
      private void dump(Job job, int aw, int ah, float sc) {
         try {
            java.io.File dir = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir());
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.rawFbo);
            FloatBuffer raw = BufferUtils.createFloatBuffer(aw * ah * 2);
            GL11.glReadPixels(0, 0, aw, ah, GL30.GL_RG, GL11.GL_FLOAT, raw);
            write(new java.io.File(dir, "pzopt-chunkao-raw.bin"), raw);
            StringBuilder sb = new StringBuilder();
            sb.append("w=").append(job.w).append("\nh=").append(job.h).append("\naw=").append(aw).append("\nah=").append(ah).append("\nsc=").append(sc)
               .append("\nppu=").append(job.ppu).append('\n');
            for (int i = 0; i < job.n; i++) {
               sb.append("src").append(i).append('=').append(job.srcX[i]).append(',').append(job.srcY[i]).append(',').append(job.srcW[i]).append(',')
                  .append(job.srcH[i]).append(',').append(job.srcDepth[i]).append('\n');
            }
            java.nio.file.Files.writeString(new java.io.File(dir, "pzopt-chunkao-dump.txt").toPath(), sb.toString());
            Log.info("chunk ao: dumped texture " + job.index + " (" + job.n + " sources) to " + dir);
         } catch (Throwable t) {
            Log.warn("chunk ao: dump failed: " + t);
         }
      }

      /** Dev (devAoDumpFrame): the blurred term (R8 as floats) next to the raw dump. */
      private void dumpTerm(int outFbo, int aw, int ah) {
         try {
            java.io.File dir = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir());
            int prev = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, outFbo);
            FloatBuffer o = BufferUtils.createFloatBuffer(aw * ah);
            GL11.glReadPixels(0, 0, aw, ah, GL11.GL_RED, GL11.GL_FLOAT, o);
            write(new java.io.File(dir, "pzopt-chunkao-term.bin"), o);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prev);
         } catch (Throwable t) {
            Log.warn("chunk ao: term dump failed: " + t);
         }
      }

      private static void write(java.io.File f, FloatBuffer b) throws java.io.IOException {
         ByteBuffer bb = ByteBuffer.allocate(b.capacity() * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
         bb.asFloatBuffer().put(b);
         java.nio.file.Files.write(f.toPath(), bb.array());
      }

      private static void restore(int[] viewport) {
         GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
         GL11.glColorMask(true, true, true, true);
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         for (int i = 0; i < 5; i++) {
            GL20.glEnableVertexAttribArray(i);
         }
         // Raw binds bypass ShaderHelper; invalidate its cached ID before restoring the default shader.
         ShaderHelper.forgetCurrentlyBound();
         ShaderHelper.glUseProgramObjectARB(0);
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glDepthMask(true);
         GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
         GLStateRenderThread.restore();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      }

      private void clear() {
         for (Entry e : this.entries.values()) {
            if (e.tex != 0) {
               GL30.glDeleteFramebuffers(e.fbo);
               GL11.glDeleteTextures(e.tex);
            }
         }
         this.entries.clear();
      }

      private boolean ensureScratch(int aw, int ah) {
         boolean ok = true;
         if (this.rawTex == 0 || this.rawW < aw || this.rawH < ah) {
            if (this.rawTex != 0) {
               GL30.glDeleteFramebuffers(this.rawFbo);
               GL11.glDeleteTextures(this.rawTex);
               GL30.glDeleteFramebuffers(this.newFbo);
               GL11.glDeleteTextures(this.newTex);
            }
            this.rawW = Math.max(aw, this.rawW);
            this.rawH = Math.max(ah, this.rawH);
            this.rawTex = texture(this.rawW, this.rawH, GL30.GL_RGBA32F, GL11.GL_RGBA, GL11.GL_FLOAT, false); // AO, depth, sun (-1: none), -
            this.rawFbo = fbo(this.rawTex);
            ok &= GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
            this.newTex = texture(this.rawW, this.rawH, GL30.GL_R8, GL30.GL_RED, GL11.GL_UNSIGNED_BYTE, true);
            this.newFbo = fbo(this.newTex);
            ok &= GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
         }
         return ok;
      }

      private static int texture(int w, int h, int internal, int format, int type, boolean linear) {
         int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
         int tex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, (ByteBuffer)null);
         int filter = linear ? GL11.GL_LINEAR : GL11.GL_NEAREST;
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 33071);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 33071);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
         return tex;
      }

      private static int fbo(int tex) {
         int fbo = GL30.glGenFramebuffers();
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
         GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
         return fbo;
      }

      /** A kernel variant's uniform locations, in uAo's order (and its sources on units 0..8). */
      private static void locate(int program, int[] u) {
         String[] names = {"rect", "off", "geo", "params", "nSrc", "strength", "veg", "iso0", "iso1", "mode", "sunDir", "sunPerp", "sunPar", "ext"};
         for (int i = 0; i < names.length; i++) {
            u[i] = GL20.glGetUniformLocation(program, names[i]);
         }
         GL20.glUseProgram(program);
         for (int i = 0; i < 9; i++) {
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Src" + i), i);
         }
         GL20.glUseProgram(0);
      }

      private static String variant(String src, String define) {
         return src.replaceFirst("#version 140", "#version 140\n#define " + define);
      }

      private boolean init() {
         this.aoProgram = AmbientOcclusion.link(AmbientOcclusion.QUAD_VERT, AO_FRAG);
         this.aoOnlyProgram = AmbientOcclusion.link(AmbientOcclusion.QUAD_VERT, variant(AO_FRAG, "AO_PASS"));
         this.blurProgram = AmbientOcclusion.link(AmbientOcclusion.QUAD_VERT, BLUR_FRAG);
         this.mulProgram = AmbientOcclusion.link(AmbientOcclusion.QUAD_VERT, MUL_FRAG);
         this.ratioProgram = AmbientOcclusion.link(AmbientOcclusion.QUAD_VERT, RATIO_FRAG);
         this.copyProgram = AmbientOcclusion.link(AmbientOcclusion.QUAD_VERT, COPY_FRAG);
         if (this.aoProgram == 0 || this.aoOnlyProgram == 0 || this.blurProgram == 0 || this.mulProgram == 0 || this.ratioProgram == 0
               || this.copyProgram == 0) {
            failed = true;
            return false;
         }
         locate(this.aoOnlyProgram, this.uAoOnly);
         this.uAo[0] = GL20.glGetUniformLocation(this.aoProgram, "rect");
         this.uAo[1] = GL20.glGetUniformLocation(this.aoProgram, "off");
         this.uAo[2] = GL20.glGetUniformLocation(this.aoProgram, "geo");
         this.uAo[3] = GL20.glGetUniformLocation(this.aoProgram, "params");
         this.uAo[4] = GL20.glGetUniformLocation(this.aoProgram, "nSrc");
         this.uAo[5] = GL20.glGetUniformLocation(this.aoProgram, "strength");
         this.uAo[6] = GL20.glGetUniformLocation(this.aoProgram, "veg");
         this.uAo[7] = GL20.glGetUniformLocation(this.aoProgram, "iso0");
         this.uAo[8] = GL20.glGetUniformLocation(this.aoProgram, "iso1");
         this.uAo[9] = GL20.glGetUniformLocation(this.aoProgram, "mode");
         this.uAo[10] = GL20.glGetUniformLocation(this.aoProgram, "sunDir");
         this.uAo[11] = GL20.glGetUniformLocation(this.aoProgram, "sunPerp");
         this.uAo[12] = GL20.glGetUniformLocation(this.aoProgram, "sunPar");
         this.uAo[13] = GL20.glGetUniformLocation(this.aoProgram, "ext");
         GL20.glUseProgram(this.aoProgram);
         for (int i = 0; i < 9; i++) {
            GL20.glUniform1i(GL20.glGetUniformLocation(this.aoProgram, "Src" + i), i); // texture unit i = source i, fixed
         }
         // multiply() can return immediately after init(), without running restore().
         ShaderHelper.forgetCurrentlyBound();
         ShaderHelper.glUseProgramObjectARB(0);
         this.uBlur[0] = GL20.glGetUniformLocation(this.blurProgram, "Ao");
         this.uBlur[1] = GL20.glGetUniformLocation(this.blurProgram, "params");
         this.uBlur[2] = GL20.glGetUniformLocation(this.blurProgram, "sunOnly");
         this.uMul[0] = GL20.glGetUniformLocation(this.mulProgram, "Ao");
         this.uMul[1] = GL20.glGetUniformLocation(this.mulProgram, "m");
         this.uRatio[0] = GL20.glGetUniformLocation(this.ratioProgram, "NewAo");
         this.uRatio[1] = GL20.glGetUniformLocation(this.ratioProgram, "OldAo");
         this.uRatio[2] = GL20.glGetUniformLocation(this.ratioProgram, "m");
         this.uRatio[3] = GL20.glGetUniformLocation(this.ratioProgram, "n");
         this.uCopy[0] = GL20.glGetUniformLocation(this.copyProgram, "Src");
         FloatBuffer quad = BufferUtils.createFloatBuffer(8);
         quad.put(new float[] {-1.0F, -1.0F, 1.0F, -1.0F, 1.0F, 1.0F, -1.0F, 1.0F}).flip();
         this.quadVbo = GL15.glGenBuffers();
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.quadVbo);
         GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quad, GL15.GL_STATIC_DRAW);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         return true;
      }
   }

   // ------------------------------------------------------------------------------------------------ shaders

   /**
    * AmbientOcclusion's kernel in texture space, reading the depth of the texture (source 0) and of its neighbours
    * (sources 1..8, each at its texel offset and depth offset; the nearest wins, as in the composite) directly: positions
    * in full-size texels of this texture, rows top-down (geo.z = -1). The centre is the surface the composite shows (a
    * neighbour's overlapping edge may lie in front of ours); a texel a little behind both neighbours on an axis (a tile
    * edge's row written behind) is filled; occluders under 0.04 squares above the plane are ignored.
    */
   private static final String AO_FRAG = String.join("\n",
      "#version 140",
      "uniform sampler2D Src0;",
      "uniform sampler2D Src1;",
      "uniform sampler2D Src2;",
      "uniform sampler2D Src3;",
      "uniform sampler2D Src4;",
      "uniform sampler2D Src5;",
      "uniform sampler2D Src6;",
      "uniform sampler2D Src7;",
      "uniform sampler2D Src8;",
      "uniform vec4 rect[9];", // source i covers texels [x, x + w) x [y, y + h) of this texture
      "uniform float off[9];", // added to source i's depth
      "uniform int nSrc;",
      "uniform vec4 geo;", // texture texels per AO texel, texture texels per square, row sign
      "uniform vec4 params;", // radius in texture texels, thickness in squares, squares per unit depth, radius in squares
      "uniform vec4 strength;", // darkening strength on floors, walls, objects, vegetation (aoStrength*Pct / 100)
      "uniform uint veg[32];", // vegetation squares: planes 0-2 = the texture's levels, 3 = tree crowns; 16 x 16 bits from 4 squares before the chunk
      "uniform vec4 iso0;", // texels to the chunk corner's screen x, units of (x - y) per texel, texels per world pixel, world px from the top edge to the corner
      "uniform vec4 iso1;", // world px per unit of (x + y), per level, depth per unit of (x + y + 2z), 1 = test vegetation
      "uniform vec4 mode;", // 1 = ambient occlusion, dev sun view (1 = the sun term alone, 2 = the exterior mask, 3 = the facing term)
      "uniform vec4 sunDir;", // sun shadows: view-space direction to the sun, w = strength (0 = no sun term)
      "uniform vec4 sunPerp;", // across the sun and the view direction, w = tan of the sun's angular radius
      "uniform vec4 sunPar;", // march length in texture texels per unit of screen travel, thickness in squares, steps, 1 = exterior test
      "uniform uint ext[24];", // exterior squares: planes 0-1 = the texture's levels, plane 2 = roof columns; 16 x 16 bits from 4 squares before the chunk
      "out vec4 result;",
      "const float HALF_PI = 1.5707963;",
      "const float BAYER[16] = float[16](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);",
      "float depthAt(vec2 p) {",
      "   float d = 1.0;",
      "   vec2 q;",
      "   float s;",
      "   { q = p - rect[0].xy; if (q.x >= 0.0 && q.y >= 0.0 && q.x < rect[0].z && q.y < rect[0].w) { s = texelFetch(Src0, ivec2(q), 0).r; if (s < 0.99999) d = min(d, s + off[0]); } }",
      "   if (nSrc > 1) { q = p - rect[1].xy; if (q.x >= 0.0 && q.y >= 0.0 && q.x < rect[1].z && q.y < rect[1].w) { s = texelFetch(Src1, ivec2(q), 0).r; if (s < 0.99999) d = min(d, s + off[1]); } }",
      "   if (nSrc > 2) { q = p - rect[2].xy; if (q.x >= 0.0 && q.y >= 0.0 && q.x < rect[2].z && q.y < rect[2].w) { s = texelFetch(Src2, ivec2(q), 0).r; if (s < 0.99999) d = min(d, s + off[2]); } }",
      "   if (nSrc > 3) { q = p - rect[3].xy; if (q.x >= 0.0 && q.y >= 0.0 && q.x < rect[3].z && q.y < rect[3].w) { s = texelFetch(Src3, ivec2(q), 0).r; if (s < 0.99999) d = min(d, s + off[3]); } }",
      "   if (nSrc > 4) { q = p - rect[4].xy; if (q.x >= 0.0 && q.y >= 0.0 && q.x < rect[4].z && q.y < rect[4].w) { s = texelFetch(Src4, ivec2(q), 0).r; if (s < 0.99999) d = min(d, s + off[4]); } }",
      "   if (nSrc > 5) { q = p - rect[5].xy; if (q.x >= 0.0 && q.y >= 0.0 && q.x < rect[5].z && q.y < rect[5].w) { s = texelFetch(Src5, ivec2(q), 0).r; if (s < 0.99999) d = min(d, s + off[5]); } }",
      "   if (nSrc > 6) { q = p - rect[6].xy; if (q.x >= 0.0 && q.y >= 0.0 && q.x < rect[6].z && q.y < rect[6].w) { s = texelFetch(Src6, ivec2(q), 0).r; if (s < 0.99999) d = min(d, s + off[6]); } }",
      "   if (nSrc > 7) { q = p - rect[7].xy; if (q.x >= 0.0 && q.y >= 0.0 && q.x < rect[7].z && q.y < rect[7].w) { s = texelFetch(Src7, ivec2(q), 0).r; if (s < 0.99999) d = min(d, s + off[7]); } }",
      "   if (nSrc > 8) { q = p - rect[8].xy; if (q.x >= 0.0 && q.y >= 0.0 && q.x < rect[8].z && q.y < rect[8].w) { s = texelFetch(Src8, ivec2(q), 0).r; if (s < 0.99999) d = min(d, s + off[8]); } }",
      "   return d;",
      "}",
      "float tapDepth(vec2 p) {", // a tap: this texture's own depth where it has a pixel (an overlapping neighbour edge in front of it only matters at the centre), else the neighbours
      "   if (p.x >= 0.0 && p.y >= 0.0 && p.x < rect[0].z && p.y < rect[0].w) {",
      "      float s0 = texelFetch(Src0, ivec2(p), 0).r;",
      "      if (s0 < 0.99999) return s0;",
      "   }",
      "   return nSrc > 1 ? depthAt(p) : 1.0;",
      "}",
      // the square under a texel from its own depth (calculateDepth: k (20 - (x + y) - 2 z) within the chunk) and its screen
      // position (x - y from the column, (x + y) 16 - z 96 world px from the row), looked up in the vegetation mask
      "bool vegetationAt(vec2 c, float ys) {",
      "   float u = 20.0 - texelFetch(Src0, ivec2(c), 0).r / iso1.z;",
      "   float p = (c.x - iso0.x) * iso0.y;",
      "   float q = (ys < 0.0 ? c.y : rect[0].w - c.y) / iso0.z - iso0.w;",
      "   float zl = (u * iso1.x - q) / (2.0 * iso1.x + iso1.y);",
      "   float sum = u - 2.0 * zl;",
      "   ivec2 sq = ivec2(floor(vec2(sum + p, sum - p) * 0.5)) + 4;",
      "   if (sq.x < 0 || sq.y < 0 || sq.x >= 16 || sq.y >= 16) return false;",
      "   int bit = sq.y * 16 + sq.x;",
      "   int lvl = clamp(int(floor(zl + 0.05)), 0, 2);",
      "   uint m = veg[lvl * 8 + (bit >> 5)] | veg[24 + (bit >> 5)];",
      "   return ((m >> uint(bit & 31)) & 1u) != 0u;",
      "}",
      // the square (x, y relative to the masks' origin) and level under a view-space point given as texel c + depth
      "vec3 squareAt(vec2 c, float depth, float ys) {",
      "   float u = 20.0 - depth / iso1.z;",
      "   float p = (c.x - iso0.x) * iso0.y;",
      "   float q = (ys < 0.0 ? c.y : rect[0].w - c.y) / iso0.z - iso0.w;",
      "   float zl = (u * iso1.x - q) / (2.0 * iso1.x + iso1.y);",
      "   float sum = u - 2.0 * zl;",
      "   return vec3(floor(vec2(sum + p, sum - p) * 0.5) + 4.0, zl);",
      "}",
      // is the surface outdoors: its square, a quarter square off the surface along the normal (a wall belongs to the side it faces)
      "bool exteriorAt(vec2 c, float d, vec3 N, float ppu, float kz, float ys) {",
      "   vec3 s = squareAt(c + vec2(N.x, ys * N.y) * 0.25 * ppu, d + N.z * 0.25 / kz, ys);",
      "   ivec2 sq = ivec2(s.xy);",
      "   if (sq.x < 0 || sq.y < 0 || sq.x >= 16 || sq.y >= 16) return true;",
      "   int bit = sq.y * 16 + sq.x;",
      "   int lvl = clamp(int(floor(s.z + 0.05)), 0, 1);",
      "   vec3 s0 = squareAt(c, d, ys);", // the roof test on the pixel's own column and the 8 around it (the stepped roof depth reconstructs loosely)
      "   for (int ry = -1; ry <= 1; ry++) {",
      "      for (int rx = -1; rx <= 1; rx++) {",
      "         ivec2 q0 = ivec2(s0.xy) + ivec2(rx, ry);",
      "         if (q0.x < 0 || q0.y < 0 || q0.x >= 16 || q0.y >= 16) continue;",
      "         int b0 = q0.y * 16 + q0.x;",
      "         if (((ext[16 + (b0 >> 5)] >> uint(b0 & 31)) & 1u) != 0u && s0.z > 0.5) return false;",
      "      }",
      "   }",
      "   return ((ext[lvl * 8 + (bit >> 5)] >> uint(bit & 31)) & 1u) != 0u;",
      "}",
      "uint popc(uint v) {",
      "   v = v - ((v >> 1u) & 0x55555555u);",
      "   v = (v & 0x33333333u) + ((v >> 2u) & 0x33333333u);",
      "   return (((v + (v >> 4u)) & 0x0F0F0F0Fu) * 0x01010101u) >> 24u;",
      "}",
      "uint sectors(float u0, float u1) {",
      "   uint b0 = uint(clamp(floor(u0 + 0.35), 0.0, 32.0));",
      "   uint b1 = uint(clamp(ceil(u1 - 0.35), 0.0, 32.0));",
      "   if (b1 <= b0) return 0u;",
      "   uint hi = b1 >= 32u ? 0xFFFFFFFFu : ((1u << b1) - 1u);",
      "   return hi & ~((1u << b0) - 1u);",
      "}",
      "float sectorOf(vec2 v, float cn, float sn) {",
      "   v = normalize(v);",
      "   float s = v.y * cn - v.x * sn;",
      "   if (v.x * cn + v.y * sn < 0.0) s = v.y >= 0.0 ? 1.0 : -1.0;",
      "   return (s + 1.0) * 16.0;",
      "}",
      // sine of the angle from the sun (in the slice) to v, mapped onto the 32 sectors of the sun's disk; behind: off the disk
      "float sunSector(vec2 v, vec2 ls, float sinA) {",
      "   v = normalize(v);",
      "   float s = ls.x * v.y - ls.y * v.x;",
      "   if (dot(ls, v) < 0.0) s = s >= 0.0 ? 1.0 : -1.0;",
      "   return (clamp(s / sinA, -1.0, 1.0) + 1.0) * 16.0;",
      "}",
      // the share of the sun's disk this surface sees: one slice through the sun and the view direction (the sun lies in it),
      // leaning across the disk by this texel's Bayer rank (the 4x4 box averages the 16), casters as depth intervals
      // [front, front + thickness] whose angles cover sectors of the disk (visibility bitmask), attached shadow from the normal
      // (facing only on the floor / wall planes: sprites' painted depth gives noisy normals; an object ignores casters closer
      // than a third of a square: a bush or a crown does not shadow itself, its neighbours do; roofs are out, see exteriorMask)
      "float sunVisibility(vec2 c, float d, vec3 N, bool plane, float bayer, float jitter, float ppu, float kz, float ys) {",
      "   float tanA = sunPerp.w;",
      "   float lat = ((bayer + 0.5) / 8.0 - 1.0) * tanA;",
      "   vec3 L = normalize(sunDir.xyz + sunPerp.xyz * lat);",
      "   float facing = plane ? smoothstep(0.0, 0.25, dot(N, L)) : 1.0;",
      "   float near2 = plane ? 0.0 : 0.11;",
      "   if (mode.y > 2.5) return facing;",
      "   if (facing <= 0.0) return 0.0;",
      "   float lxy = length(L.xy);",
      "   if (lxy < 1e-3) return facing;",
      "   vec2 D = L.xy / lxy;",
      "   vec2 tdir = vec2(D.x, ys * D.y);",
      "   vec2 ls = vec2(lxy, -L.z);", // the sun in the slice: (along D, towards the camera)
      "   float sinA = tanA / sqrt(1.0 + tanA * tanA);",
      "   float lenPx = sunPar.x * lxy;",
      "   float steps = sunPar.z;",
      "   uint mask = 0u;",
      "   for (int j = 0; j < 32; j++) {",
      "      if (float(j) >= steps) break;",
      "      float f = (float(j) + jitter) / steps;",
      "      float rpx = max((float(j) + 1.0) * geo.x * 0.75, pow(f, 1.6) * lenPx);",
      "      vec2 sp = c + tdir * rpx;",
      "      float sd = depthAt(sp);",
      "      if (sd >= 0.99999) continue;",
      "      vec2 o = (floor(sp) + 0.5 - c) / ppu;",
      "      vec3 dF = vec3(o.x, ys * o.y, (sd - d) * kz);",
      "      if (dot(dF, N) < 0.06 || dot(dF, dF) < near2) continue;", // on or under the surface's own plane (tile edge rows, the neighbour overlap)
      "      vec2 fv = vec2(dot(dF.xy, D), -dF.z);",
      "      float uF = sunSector(fv, ls, sinA);",
      "      float uB = sunSector(fv - vec2(0.0, sunPar.y), ls, sinA);",
      "      mask |= sectors(min(uF, uB), max(uF, uB));",
      "      if (mask == 0xFFFFFFFFu) break;",
      "   }",
      "   return facing * (1.0 - float(popc(mask)) / 32.0);",
      "}",
      "void main() {",
      "   ivec2 t = ivec2(gl_FragCoord.xy);",
      "   vec2 c = floor((vec2(t) + 0.5) * geo.x) + 0.5;", // the texture texel under this AO texel's centre
      "   if (c.x >= rect[0].z || c.y >= rect[0].w || texelFetch(Src0, ivec2(c), 0).r >= 0.99999) {",
      "      result = vec4(1.0, 1.0, -1.0, 1.0);",
      "      return;",
      "   }",
      "   float kz = params.z;",
      "   float ppu = geo.y;",
      "   float ys = geo.z;",
      "   float d = depthAt(c);",
      "   float dl = depthAt(c - vec2(2.0, 0.0));",
      "   float dr = depthAt(c + vec2(2.0, 0.0));",
      "   float dd = depthAt(c - vec2(0.0, 2.0));",
      "   float du = depthAt(c + vec2(0.0, 2.0));",
      "   if (d > du && d > dd && (d - 0.5 * (du + dd)) * kz < 0.05) d = 0.5 * (du + dd);",
      "   if (d > dl && d > dr && (d - 0.5 * (dl + dr)) * kz < 0.05) d = 0.5 * (dl + dr);",
      "#ifdef AO_PASS",
      "   bool sunHere = false;", // the variant compiled without the sun code (computes with no sun: its registers)
      "#else",
      "   bool sunHere = sunDir.w > 0.0;",
      "#endif",
      "   if (mode.x < 0.5 && !sunHere) { result = vec4(1.0, d, -1.0, 1.0); return; }",
      "   float zx = 0.5 * (abs(dr - d) < abs(d - dl) ? dr - d : d - dl);",
      "   float zy = 0.5 * (abs(du - d) < abs(d - dd) ? du - d : d - dd);",
      "   vec3 N = normalize(vec3(zx * kz * ppu, ys * zy * kz * ppu, -1.0));",
      "   const vec3 NG = vec3(0.0, 0.8660254, -0.5);",
      "   const vec3 NE = vec3(0.7071068, -0.3535534, -0.6123724);",
      "   const vec3 NS = vec3(-0.7071068, -0.3535534, -0.6123724);",
      "   float g = dot(N, NG), e = dot(N, NE), so = dot(N, NS);",
      "   bool plane = g > 0.94 || e > 0.94 || so > 0.94;",
      "   if (g > 0.94) N = NG; else if (e > 0.94) N = NE; else if (so > 0.94) N = NS;",
      "   float bayer = BAYER[(t.x & 3) + 4 * (t.y & 3)];",
      "   float jitter = fract(bayer * 0.618034 + 0.5 * float((t.x ^ t.y) & 1));",
      "   float sk = g > 0.94 ? strength.x : (e > 0.94 || so > 0.94 ? strength.y : (iso1.w > 0.5 && vegetationAt(c, ys) ? strength.w : strength.z));", // floors, walls, vegetation, the rest
      "   const vec3 V = vec3(0.0, 0.0, -1.0);",
      "   float radiusPx = params.x;",
      "   float thickness = params.y;",
      "   float radius = params.w;",
      "   float vis = 0.0;",
      "   float wsum = 0.0;",
      "   for (int i = 0; i < (mode.x > 0.5 ? 2 : 0); i++) {",
      "      float phi = (float(i) + bayer / 16.0) * HALF_PI;",
      "      vec2 dir = vec2(cos(phi), sin(phi));", // in texels
      "      vec3 D = normalize(vec3(dir.x, ys * dir.y, 0.0));", // in view space
      "      vec3 axis = vec3(-D.y, D.x, 0.0);",
      "      vec3 pn = N - axis * dot(N, axis);",
      "      float pnl = length(pn);",
      "      if (pnl < 1e-4) continue;",
      "      float cn = dot(pn, V) / pnl;",
      "      float sn = dot(pn, D) / pnl;",
      "      uint mask = 0u;",
      "      for (int side = 0; side < 2; side++) {",
      "         float sgn = side == 0 ? 1.0 : -1.0;",
      "         for (int j = 0; j < 4; j++) {",
      "            float f = (float(j) + jitter) / 4.0;",
      "            float rpx = max((float(j) + 1.0) * geo.x, f * f * radiusPx);",
      "            vec2 sp = c + dir * sgn * rpx;",
      "            float sd = tapDepth(sp);",
      "            if (sd >= 0.99999) continue;",
      "            vec2 o = (floor(sp) + 0.5 - c) / ppu;",
      "            vec3 dF = vec3(o.x, ys * o.y, (sd - d) * kz);",
      "            if (dot(dF, dF) > radius * radius) continue;",
      "            if (dot(dF, N) < 0.04) continue;",
      "            vec2 fv = vec2(-dF.z, dot(dF.xy, D.xy));",
      "            float uF = sectorOf(fv, cn, sn);",
      "            float uB = sectorOf(fv - vec2(thickness, 0.0), cn, sn);",
      "            mask |= sectors(min(uF, uB), max(uF, uB));",
      "         }",
      "      }",
      "      vis += (1.0 - float(popc(mask)) / 32.0) * pnl;",
      "      wsum += pnl;",
      "   }",
      "   float ao = clamp(1.0 - (1.0 - (wsum > 0.0 ? vis / wsum : 1.0)) * sk, 0.0, 1.0);",
      "   float sun = -1.0;", // -1: no sun term at this texel
      "#ifndef AO_PASS",
      "   if (sunHere) {",
      "      sun = 1.0;",
      "      bool outdoors = sunPar.w < 0.5 || exteriorAt(c, d, N, ppu, kz, ys);",
      "      if (mode.y > 1.5 && mode.y < 2.5) sun = outdoors ? 1.0 : 0.3;",
      "      else if (outdoors) sun = 1.0 - sunDir.w * (1.0 - sunVisibility(c, d, N, plane, bayer, jitter, ppu, kz, ys));",
      "   }",
      "#endif",
      "   result = vec4(ao, d, sun, 1.0);",
      "}");

   /**
    * 4x4 depth-aware box over one period of the rotation pattern, into the texture's R8 term: the AO and the sun averaged
    * apart (the sun over a 5 x 5 tent), then multiplied.
    */
   private static final String BLUR_FRAG = String.join("\n",
      "#version 140",
      "uniform sampler2D Ao;",
      "uniform vec4 params;", // squares per unit depth, squares per AO texel, last texel x, y
      "uniform float sunOnly;", // dev (devSunView): the sun term alone
      "out vec4 result;",
      "void main() {",
      "   ivec2 t = ivec2(gl_FragCoord.xy);",
      "   vec4 c = texelFetch(Ao, t, 0);",
      "   if (c.g >= 0.99999) {",
      "      result = vec4(1.0);",
      "      return;",
      "   }",
      "   float sum = 0.0, wsum = 0.0, ssum = 0.0, swsum = 0.0;",
      // AO: the 4 x 4 box over one Bayer period; sun: a 5 x 5 tent (the 16 lateral leans, a little smoother at the edges)
      "   for (int y = -2; y <= 2; y++) {",
      "      for (int x = -2; x <= 2; x++) {",
      "         vec4 a = texelFetch(Ao, clamp(t + ivec2(x, y), ivec2(0), ivec2(params.zw)), 0);",
      "         if (a.g >= 0.99999) continue;",
      "         float tol = 0.08 + 3.0 * params.y * float(max(abs(x), abs(y)));",
      "         float dz = (a.g - c.g) * params.x / tol;",
      "         float w = exp(-dz * dz);",
      "         if (x >= -1 && y >= -1) { sum += a.r * w; wsum += w; }",
      "         if (a.b >= 0.0) { float tw = w * (2.5 - abs(float(x))) * (2.5 - abs(float(y))); ssum += a.b * tw; swsum += tw; }",
      "      }",
      "   }",
      "   float ao = wsum > 0.0 ? sum / wsum : c.r;",
      "   float sun = swsum > 1e-4 ? ssum / swsum : (c.b >= 0.0 ? c.b : 1.0);",
      "   result = vec4(sunOnly > 0.5 ? sun : ao * sun);",
      "}");

   /** The texture's colour times its AO (bilinear from the R8 AO); no occlusion: no blend. */
   private static final String MUL_FRAG = String.join("\n",
      "#version 140",
      "uniform sampler2D Ao;",
      "uniform vec4 m;", // AO uv per texture texel x, y; strength; dev view
      "out vec4 fragColor;",
      "void main() {",
      "   float ao = texture(Ao, gl_FragCoord.xy * m.xy).r;",
      "   ao = clamp(1.0 - (1.0 - ao) * m.z, 0.0, 1.0);",
      "   if (m.w < 0.5 && ao > 0.996) discard;",
      "   fragColor = vec4(vec3(ao), 1.0);",
      "}");

   /**
    * Onto the texture: new / old AO (each with the strength applied; old = 1 when the texture had none) halved for the
    * 2 src dst blend, from the scratch AO (a corner of a larger texture: n.xy scales its uv) and the kept one.
    */
   private static final String RATIO_FRAG = String.join("\n",
      "#version 140",
      "uniform sampler2D NewAo;",
      "uniform sampler2D OldAo;",
      "uniform vec4 m;", // AO uv per texture texel x, y; strength; 1 = there is an old AO
      "uniform vec4 n;", // the scratch's used share x, y; dev view
      "out vec4 fragColor;",
      "void main() {",
      "   vec2 uv = gl_FragCoord.xy * m.xy;",
      "   float a = clamp(1.0 - (1.0 - texture(NewAo, uv * n.xy).r) * m.z, 0.0, 1.0);",
      "   if (n.z > 0.5) { fragColor = vec4(vec3(a), 1.0); return; }",
      "   float b = m.w > 0.5 ? clamp(1.0 - (1.0 - texture(OldAo, uv).r) * m.z, 0.0, 1.0) : 1.0;",
      "   float r = a / max(b, 0.02);",
      "   if (abs(r - 1.0) < 0.004) discard;",
      "   fragColor = vec4(vec3(clamp(r, 0.0, 2.0) * 0.5), 1.0);",
      "}");

   /** The scratch AO (texel for texel) into the texture's kept AO. */
   private static final String COPY_FRAG = String.join("\n",
      "#version 140",
      "uniform sampler2D Src;",
      "out vec4 fragColor;",
      "void main() {",
      "   fragColor = vec4(texelFetch(Src, ivec2(gl_FragCoord.xy), 0).r);",
      "}");
}
