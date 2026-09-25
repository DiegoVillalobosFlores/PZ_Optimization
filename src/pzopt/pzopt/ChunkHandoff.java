package pzopt;

/**
 * chunkHandoffSlack (2026-09-24, the Rosewood drive): a streamed chunk's hand-off to the world (IsoChunk.doLoadGridsquare:
 * loot, erosion, neighbour recalc, pathfinding) is 1-5 ms on the game thread, a tenth of the game thread in the drive's
 * late frames. The queue is short while driving (~60 chunks a second, one every few frames), so after a frame whose game
 * step used more than chunkHandoffSlackPct of the cap interval the hand-off waits, at most chunkHandoffMaxWait frames and
 * never while chunkHandoffSlackQueue or more chunks are queued, and lands in a frame with room for it.
 * Instrumented runs log the hand-off times (census below).
 */
public final class ChunkHandoff {
   private static int waited;
   private static long n, sumNs, maxNs, over2, over4, deferredFrames;
   private static long lastLogNs;

   private ChunkHandoff() {
   }

   /** Game thread, IsoChunkMap.updateInternal with a non-empty queue: true = hand nothing off this frame. */
   public static boolean defer(int queued) {
      if (!Config.CHUNK_HANDOFF_SLACK || !Overrides.enabled()) {
         return false;
      }
      long interval = Pacing.capIntervalNs();
      if (interval <= 0L || queued >= Config.CHUNK_HANDOFF_SLACK_QUEUE || waited >= Config.CHUNK_HANDOFF_MAX_WAIT
            || FrameCap.lastStepNs <= interval * Config.CHUNK_HANDOFF_SLACK_PCT / 100) {
         waited = 0;
         return false;
      }
      waited++;
      deferredFrames++;
      return true;
   }

   public static void done(long ns) {
      if (!Config.INSTRUMENT) {
         return;
      }
      n++;
      sumNs += ns;
      maxNs = Math.max(maxNs, ns);
      if (ns > 2_000_000L) over2++;
      if (ns > 4_000_000L) over4++;
      long now = System.nanoTime();
      if (now - lastLogNs > 10_000_000_000L) {
         if (lastLogNs != 0L) {
            Log.info("chunk hand-off: " + n + " chunks, mean " + (n == 0 ? 0 : sumNs / n / 1000) + " us, max " + maxNs / 1000 + " us, > 2 ms " + over2 + ", > 4 ms " + over4
               + ", frames deferred " + deferredFrames);
         }
         lastLogNs = now;
         n = sumNs = maxNs = over2 = over4 = deferredFrames = 0L;
      }
   }
}
