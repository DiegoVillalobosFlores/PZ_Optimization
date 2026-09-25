package pzopt;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import zombie.iso.IsoChunk;
import zombie.iso.fboRenderChunk.FBORenderLevels;

/**
 * seamSpread (2026-09-24, the Rosewood 120 km/h drive): stock re-bakes every level of every on-screen chunk whose
 * neighbour just loaded (FBORenderCell.checkSeamChunks, DIRTY_REDRAW) so the SeamFix2 floor / wall strips along the
 * chunk border pick up the new neighbour's squares. While driving ~60 chunks load a second and each one dirties up to
 * eight baked neighbours at once; the re-bake budget holds them for rebakeMaxFrames (3) and then bakes them together,
 * which made the 10-40-bake frames of the drive (GPU 3-16 ms in one frame). Here a baked neighbour's seam re-bake joins
 * a queue instead and is released oldest first, {@code seamRebakeBudget} chunks a frame, none in a frame after a heavy
 * one, and every chunk within {@code seamMaxFrames}. A chunk whose texture is not made yet needs no queue: its first
 * bake sees the loaded neighbour. What shows meanwhile is the texture as baked before the neighbour arrived: the seam
 * strip along that border is missing for a few frames, at the edge of the loaded area.
 */
public final class SeamSpread {
   public static final boolean ON = Config.SEAM_SPREAD && Overrides.enabled();
   private static final LinkedHashMap<IsoChunk, Integer>[] queued = newQueues();
   public static long deferred, released, forced, dropped;

   @SuppressWarnings("unchecked")
   private static LinkedHashMap<IsoChunk, Integer>[] newQueues() {
      LinkedHashMap<IsoChunk, Integer>[] q = new LinkedHashMap[4];
      for (int i = 0; i < q.length; i++) {
         q[i] = new LinkedHashMap<>();
      }
      return q;
   }

   private SeamSpread() {
   }

   /** Game thread: a neighbour of {@code c} loaded. True = queued (the caller skips its invalidateAll). */
   public static boolean defer(IsoChunk c, int playerIndex, FBORenderLevels levels, float zoom, int frameNo) {
      if (!hasBakedLevel(c, levels, zoom)) {
         return false; // nothing baked yet: the redraw flag is free, the first bake sees the neighbour
      }
      queued[playerIndex].putIfAbsent(c, frameNo);
      deferred++;
      return true;
   }

   /** Game thread, once a frame after the seam checks: release the oldest queued chunks within the frame's allowance. */
   public static void release(int playerIndex, int frameNo, int bakesLastFrame) {
      LinkedHashMap<IsoChunk, Integer> q = queued[playerIndex];
      if (q.isEmpty()) {
         return;
      }
      int budget = bakesLastFrame >= Config.SEAM_HEAVY_BAKES ? 0 : Config.SEAM_REBAKE_BUDGET;
      Iterator<Map.Entry<IsoChunk, Integer>> it = q.entrySet().iterator();
      while (it.hasNext()) {
         Map.Entry<IsoChunk, Integer> e = it.next();
         boolean overdue = frameNo - e.getValue() >= Config.SEAM_MAX_FRAMES;
         if (budget <= 0 && !overdue) {
            break; // FIFO: every later entry is younger
         }
         it.remove();
         IsoChunk c = e.getKey();
         FBORenderLevels levels = c.getRenderLevels(playerIndex);
         if (levels == null) {
            dropped++;
            continue;
         }
         levels.invalidateAll(1024L);
         if (overdue && budget <= 0) {
            forced++;
         } else {
            budget--;
            released++;
         }
      }
   }

   /** A chunk left the grid or went off screen: its textures are freed or re-made anyway. */
   public static void forget(IsoChunk c) {
      if (!ON) {
         return;
      }
      for (LinkedHashMap<IsoChunk, Integer> q : queued) {
         q.remove(c);
      }
   }

   private static boolean hasBakedLevel(IsoChunk c, FBORenderLevels levels, float zoom) {
      for (int z = c.minLevel; z <= c.maxLevel; z++) {
         if (z == levels.getMinLevel(z) && levels.getFBOForLevel(z, zoom) != null && !levels.isDirty(z, 512L, zoom)) {
            return true;
         }
      }
      return false;
   }

   public static String summary() {
      int n = 0;
      for (LinkedHashMap<IsoChunk, Integer> q : queued) {
         n += q.size();
      }
      return " | seam spread: deferred=" + deferred + " released=" + released + " forced=" + forced + " dropped=" + dropped + " queued=" + n;
   }
}
