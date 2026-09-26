package pzopt;

/**
 * Frame cap locked to the display's refresh (`vsyncLock`, 2026-09-26, docs/findings-car-jitter-2026-09-26.md).
 *
 * <p>Stock caps the frame rate with a CPU accumulator of exactly 1 / cap seconds and, with vsync on, swaps with interval
 * 1. The accumulator's clock and the panel's refresh never match exactly, so their phase drifts: at a 60 fps cap on a
 * 120 Hz panel a frame is shown for 3 refreshes and the next for 1 every time the phase crosses a vblank (2.5 % of
 * frames on the flip, 25 ms / 8 ms pairs: a visible hitch while driving). With the key on, when vsync is on, variable
 * refresh is off and the refresh is a whole multiple n of the cap (within 2 %), the swap interval becomes n and the
 * accumulator runs 3 % faster than the cap, so the swap's wait on the vblank, not the CPU clock, sets the pace: every
 * frame stays on screen exactly n refreshes (with vsyncAdaptive the interval is -n: a late frame still tears instead of
 * waiting). Any other combination is stock.
 */
public final class VsyncLock {
   private VsyncLock() {
   }

   static final boolean ON = Config.VSYNC_LOCK;
   private static volatile int wanted = 1; // swap interval the game thread asks for (1 = stock)
   private static int applied = -99;
   private static boolean locked;
   private static int loggedFps = -1, loggedN = -1;

   /** GameWindow.mainThreadStep: the limiter period for this step (stock {@code desiredDt} unless locked). */
   public static long desiredDt(long stockDt, int capFps) {
      if (!ON || !Overrides.enabled()) {
         return stockDt;
      }
      int n = 1;
      boolean lock = false;
      double hz = Vrr.refreshHz();
      if (capFps > 0 && hz > 0.0 && org.lwjglx.opengl.Display.isVSyncEnabledPzopt() && !Vrr.active()) {
         double r = hz / capFps;
         long k = Math.round(r);
         if (k >= 1 && k <= 4 && Math.abs(r - k) <= 0.02 * k) {
            n = (int)k;
            lock = true;
         }
      }
      wanted = n;
      locked = lock;
      if (capFps != loggedFps || n != loggedN) {
         loggedFps = capFps;
         loggedN = n;
         Log.info("vsyncLock: cap " + capFps + " fps, refresh " + Math.round(hz) + " Hz, " + (lock ? "swap interval " + n + ", vsync paces the frames" : "not a divisor of the refresh: stock limiter"));
      }
      return lock ? stockDt * 97L / 100L : stockDt;
   }

   /** Display.update (render thread), before the swap: apply the interval the game thread asked for. */
   public static void beforeSwap() {
      if (!ON) {
         return;
      }
      int w = wanted;
      if (!org.lwjglx.opengl.Display.isVSyncEnabledPzopt()) {
         applied = -99;
         return;
      }
      if (w != applied) {
         // vsyncAdaptive's swap_control_tear: a negative interval is the same pacing, a late frame tears instead of waiting
         org.lwjgl.glfw.GLFW.glfwSwapInterval(adaptive() ? -w : w);
         applied = w;
      }
   }

   private static int tear; // 0 = not asked yet, 1 = swap_control_tear, -1 = none

   private static boolean adaptive() {
      if (!Config.VSYNC_ADAPTIVE) {
         return false;
      }
      if (tear == 0) {
         tear = org.lwjgl.glfw.GLFW.glfwExtensionSupported("GLX_EXT_swap_control_tear")
               || org.lwjgl.glfw.GLFW.glfwExtensionSupported("WGL_EXT_swap_control_tear") ? 1 : -1;
      }
      return tear > 0;
   }

   /** Display.setVSyncEnabled reset the interval to the stock one: apply ours again before the next swap. */
   public static void intervalReset() {
      applied = -99;
   }

   public static boolean locked() {
      return locked;
   }
}
