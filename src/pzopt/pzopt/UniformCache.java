package pzopt;

/**
 * uniformCache (2026-09-24, the Rosewood drive): every tile sprite drawn with the tile-depth shader starts the shader again
 * with its own depth uniforms, ~220 times a chunk bake and ~1,000 times a frame outside bakes (DrawStats), each start
 * re-sending the two sampler uniforms (always 0 and 1) and all four value uniforms. With NVIDIA's threaded driver every
 * GL call is work on the driver thread, which the render thread then waits for. While shader starts of the same program
 * follow each other with no other render command between (TextureDraw.run resets the cache on any other command), a
 * uniform holding the value it is being set to is skipped and the tile-depth shader's sampler setup is not repeated.
 * Render thread only.
 */
public final class UniformCache {
   public static final boolean ON = Config.UNIFORM_CACHE && Overrides.enabled();
   private static final int SLOTS = 256;
   private static final int[] bits = new int[SLOTS];
   private static final int[] gen = new int[SLOTS];
   private static int generation = 1;
   private static int program = Integer.MIN_VALUE;
   public static long skipped, sent, samplerSkips;

   private UniformCache() {
   }

   /** Any render command other than a shader start: nothing is known about the uniforms any more. */
   public static void reset() {
      if (program != Integer.MIN_VALUE) {
         program = Integer.MIN_VALUE;
         generation++;
      }
   }

   /** A shader start of program {@code p}: true when the previous command was a start of the same program. */
   public static boolean startProgram(int p) {
      if (p == program) {
         return true;
      }
      program = p;
      generation++;
      return false;
   }

   private static boolean same(int location, int value) {
      if (location < 0) {
         return true; // not an active uniform: glUniform would ignore it
      }
      if (location >= SLOTS) {
         sent++;
         return false;
      }
      if (gen[location] == generation && bits[location] == value) {
         skipped++;
         return true;
      }
      gen[location] = generation;
      bits[location] = value;
      sent++;
      return false;
   }

   public static boolean same1f(int location, float v) {
      return same(location, Float.floatToRawIntBits(v));
   }

   public static boolean same1i(int location, int v) {
      return same(location, v);
   }

   /** A uniform of another shape was sent to {@code location}: its cached value is no longer valid. */
   public static void forget(int location) {
      if (location >= 0 && location < SLOTS) {
         gen[location] = 0;
      }
   }
}
