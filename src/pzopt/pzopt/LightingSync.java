package pzopt;

import java.util.concurrent.locks.LockSupport;

/**
 * The lighting thread's rate limiter ({@code lightingSyncPark}, 2026-09-24, the E-core pass). Stock LightingThread paces
 * its native update with LWJGL's Display.sync(lightingFps), which sleeps in 1 ms steps and then yield-spins through the
 * last millisecond or so of every period; at 15 Hz that is a core busy ~2 % of the time for nothing, and the spin keeps it
 * out of its deepest idle state. Here the thread parks straight to its next update (the lighting cadence has no use for
 * sub-millisecond precision). The first call also tells {@link CorePlacement} that this is background work (macOS QoS).
 */
public final class LightingSync {
   public static final boolean ON = Config.LIGHTING_SYNC_PARK && Overrides.enabled();
   private static long next;
   private static boolean marked;

   private LightingSync() {
   }

   public static void sync(int fps) {
      if (!marked) {
         marked = true;
         CorePlacement.background();
      }
      if (fps <= 0) {
         return;
      }
      long now = System.nanoTime();
      long left = next - now;
      if (left > 0L) {
         LockSupport.parkNanos(left);
         now = System.nanoTime();
      }
      next = Math.max(next + 1_000_000_000L / fps, now);
   }
}
