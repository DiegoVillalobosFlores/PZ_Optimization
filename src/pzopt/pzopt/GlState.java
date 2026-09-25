package pzopt;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import org.lwjgl.opengl.GL11;

/**
 * weatherNoGlGet (2026-09-23, the Dell hitch pass): WeatherParticleDrawer.render asked the driver for the current
 * shader program (glGetInteger(GL_CURRENT_PROGRAM)) every call, to restore it afterwards. A glGet makes the NVIDIA
 * driver wait for its command queue: on the Dell (PRIME offload) that was 3.3 s of the render thread's wall time in
 * a 40 s walk. ShaderHelper already tracks the program it binds (currentlyBound, -1 when unknown); this reads it.
 * devGlStateCheck still queries the driver and counts disagreements (log line every 256 calls).
 */
public final class GlState {
   private static final VarHandle BOUND;
   public static long checks, mismatches, recorded, fallbacks;

   static {
      VarHandle h = null;
      try {
         h = MethodHandles.privateLookupIn(zombie.core.ShaderHelper.class, MethodHandles.lookup())
               .findStaticVarHandle(zombie.core.ShaderHelper.class, "currentlyBound", int.class);
      } catch (Throwable t) {
         Log.warn("glState: ShaderHelper.currentlyBound not readable (" + t + "); querying the driver");
      }
      BOUND = h;
   }

   private GlState() {
   }

   /** GL_CURRENT_PROGRAM, from ShaderHelper's record when it has one. Render thread. */
   public static int currentProgram() {
      return currentProgram(Config.WEATHER_NO_GLGET);
   }

   /** glNoSync callers (DeadBodyAtlas.toBodyAtlas): the same record, under their own key. */
   public static int currentProgramNoSync() {
      return currentProgram(Config.GL_NO_SYNC);
   }

   private static int currentProgram(boolean useRecord) {
      if (BOUND == null || !useRecord) {
         return GL11.glGetInteger(35725);
      }
      int v = (int)BOUND.get();
      if (((recorded + fallbacks + 1) & 255) == 0) {
         Log.info("glState: " + recorded + " recorded, " + fallbacks + " driver fallbacks, " + checks + " dev checks, " + mismatches + " mismatches");
      }
      if (v <= 0) {
         fallbacks++;
         return GL11.glGetInteger(35725);
      }
      recorded++;
      if (Config.DEV_GL_STATE_CHECK) {
         int g = GL11.glGetInteger(35725);
         checks++;
         if (g != v) {
            mismatches++;
         }
         return g;
      }
      return v;
   }
}
