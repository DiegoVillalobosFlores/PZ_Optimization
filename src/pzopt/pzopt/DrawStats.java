package pzopt;

/**
 * Instrumented runs: shader starts the render thread executes inside chunk-level bakes (between FBORenderChunkStart and
 * FBORenderChunkEnd) and outside them, logged every 10 s. A tile sprite of a bake starts the tile-depth shader with its
 * own depth uniforms, so each start is a separate draw call (sizing the draw-call batching of bakes, 2026-09-24).
 */
public final class DrawStats {
   public static final boolean ON = Config.INSTRUMENT;
   private static boolean inBake;
   private static long bakes, startsInBake, startsOutside, lastLogNs;

   private DrawStats() {
   }

   public static void bakeStart() {
      inBake = true;
      bakes++;
   }

   public static void bakeEnd() {
      inBake = false;
   }

   private static final java.util.HashMap<Integer, long[]> byProgram = new java.util.HashMap<>();

   /** Also counts the starts per program (the top ones are logged with their shader name). */
   public static void shaderStart(int program) {
      long[] c = byProgram.get(program);
      if (c == null) {
         byProgram.put(program, c = new long[2]);
      }
      c[inBake ? 0 : 1]++;
      shaderStart();
   }

   public static void shaderStart() {
      if (inBake) {
         startsInBake++;
      } else {
         startsOutside++;
      }
      long now = System.nanoTime();
      if (now - lastLogNs > 10_000_000_000L) {
         if (lastLogNs != 0L) {
            Log.info("draw stats: bakes " + bakes + ", shader starts in bakes " + startsInBake + " (" + (bakes == 0 ? 0 : startsInBake / bakes) + " a bake), outside "
               + startsOutside + (UniformCache.ON ? ", uniforms skipped " + UniformCache.skipped + " sent " + UniformCache.sent + ", sampler setups skipped "
               + UniformCache.samplerSkips : ""));
            UniformCache.skipped = UniformCache.sent = UniformCache.samplerSkips = 0L;
            java.util.ArrayList<java.util.Map.Entry<Integer, long[]>> e = new java.util.ArrayList<>(byProgram.entrySet());
            e.sort((x, y) -> Long.compare(y.getValue()[0] + y.getValue()[1], x.getValue()[0] + x.getValue()[1]));
            StringBuilder b = new StringBuilder("draw stats by program (in bakes / outside):");
            for (int i = 0; i < Math.min(10, e.size()); i++) {
               zombie.core.opengl.Shader sh = zombie.core.opengl.Shader.ShaderMap.get(e.get(i).getKey());
               b.append(' ').append(sh == null ? "id" + e.get(i).getKey() : sh.getName()).append('=').append(e.get(i).getValue()[0]).append('/').append(e.get(i).getValue()[1]);
            }
            Log.info(b.toString());
         }
         lastLogNs = now;
         bakes = startsInBake = startsOutside = 0L;
         byProgram.clear();
      }
   }
}
