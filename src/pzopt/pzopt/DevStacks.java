package pzopt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Dev rig ({@code devInvalidateStacks}): counts the distinct call stacks behind chunk-level invalidations with the given
 * dirty bits (IsoChunk.invalidateRenderChunkLevel) and logs the most frequent every 10 s. Found the per-frame object
 * add / remove re-bakes next to the car on the Rosewood drive (2026-09-24).
 */
public final class DevStacks {
   private static final HashMap<String, int[]> counts = new HashMap<>();
   private static long lastLogNs;

   private DevStacks() {
   }

   public static void record(String what) {
      StackTraceElement[] st = new Throwable().getStackTrace();
      StringBuilder b = new StringBuilder(what);
      for (int i = 2, n = 0; i < st.length && n < 9; i++, n++) {
         String c = st[i].getClassName();
         b.append(" < ").append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(st[i].getMethodName()).append(':').append(st[i].getLineNumber());
      }
      counts.computeIfAbsent(b.toString(), k -> new int[1])[0]++;
      long now = System.nanoTime();
      if (now - lastLogNs > 10_000_000_000L) {
         lastLogNs = now;
         ArrayList<Map.Entry<String, int[]>> e = new ArrayList<>(counts.entrySet());
         e.sort((x, y) -> y.getValue()[0] - x.getValue()[0]);
         for (int i = 0; i < Math.min(12, e.size()); i++) {
            Log.info("invalidate stacks: " + e.get(i).getValue()[0] + " x " + e.get(i).getKey());
         }
         counts.clear();
      }
   }
}
