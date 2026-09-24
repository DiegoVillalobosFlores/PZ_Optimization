package pzopt;

import zombie.network.GameClient;
import zombie.network.GameServer;

/**
 * Dynamic zombie detail (`zombieLodDynamic`, 2026-09-24): how many zombies are drawn as 3D models and how many of those
 * blend their animations follow the frame cap instead of the fixed stock 510 / 20.
 *
 * <p>{@code IsoWorld.sceneCullZombies} sorts the on-screen zombies nearest first and gives the first 510 a model (the
 * rest are culled to the flat atlas sprite) and the first {@code PerformanceSettings.numberZombiesBlended} (20) animation
 * blending. A horde makes both limits expensive on the game thread (bones, skinning, the character draw) and the GPU.
 * Here one quality level between 0 and 1 maps linearly onto both: {@code zombieLodMin3d .. 510} models and
 * {@code zombieLodMinBlend .. numberZombiesBlended} blended.
 *
 * <p>The level follows the game-thread frame step ({@code FrameCap.lastStepNs}: the work of a frame without the
 * limiter's wait, so the headroom is visible while the game holds its cap). Every frame's step is kept; every
 * {@link #DECIDE_NS} the window's median and 90th percentile are compared with the cap's frame budget: a median above
 * {@link #OVER} of it (most frames miss the cap) lowers the level, more the further over, and blocks climbing for
 * {@link #CLIMB_HOLD_NS}; a 90th percentile below {@link #HEADROOM} (even the slow frames fit) raises it one small
 * step; anything else holds. A lone hitch moves nothing, and the level does not swing the same zombies between model
 * and sprite every few seconds (the first version, an 8-frame average, went 0 -> 1 -> 0 within 5 s on Louisville). The target is the
 * cap in force ({@code FrameCap.lockNow()}: the player's, the VRR cap or {@code frameCapFps}); uncapped it is
 * {@code zombieLodUncappedFps}, and 0 there means stock detail while uncapped.
 *
 * <p>Game thread only (called from sceneCullZombies). Stock values as ceilings: another mod lowering the 510 or the
 * blend count (ZBBetterFPS) still wins where it is lower.
 */
public final class ZombieLod {
   /** stock number of zombies drawn as 3D models */
   public static final int STOCK_3D = 510;
   static final long DECIDE_NS = 250_000_000L;
   /** median step above this share of the budget: most frames miss the cap, lower the detail */
   static final float OVER = 0.97F;
   /** 90th-percentile step below this share: even the slow frames fit, raise the detail */
   static final float HEADROOM = 0.85F;
   static final float UP_STEP = 0.03F;
   static final float DOWN_STEP = 0.08F;
   static final long LOG_NS = 5_000_000_000L;
   /** after a cut, no climbing for this long */
   static final long CLIMB_HOLD_NS = 2_000_000_000L;
   private static final long[] window = new long[256];
   private static int windowCount;
   private static long climbAfterNs;
   private static double lastP50Ns;

   private static float level = 1.0F;
   private static long lastDecideNs;
   private static long lastLogNs;
   private static int lastTargetFps;

   private ZombieLod() {
   }

   public static boolean active() {
      return Config.ZOMBIE_LOD_DYNAMIC && Overrides.enabled() && !GameServer.server && !GameClient.client;
   }

   /** Once per frame, before the cull loop. */
   public static void update() {
      long step = FrameCap.lastStepNs;
      if (step > 0L && windowCount < window.length) {
         window[windowCount++] = step;
      }
      long now = System.nanoTime();
      if (now - lastDecideNs < DECIDE_NS) {
         return;
      }
      lastDecideNs = now;
      int fps = targetFps();
      lastTargetFps = fps;
      double p50 = 0.0;
      double p90 = 0.0;
      if (windowCount > 0) {
         java.util.Arrays.sort(window, 0, windowCount);
         p50 = window[(windowCount - 1) / 2];
         p90 = window[Math.min(windowCount - 1, (int)(windowCount * 0.9))];
      }
      windowCount = 0;
      lastP50Ns = p50;
      float before = level;
      level = next(level, p50, p90, fps, now >= climbAfterNs);
      if (level < before) {
         climbAfterNs = now + CLIMB_HOLD_NS;
      }
      if (now - lastLogNs >= LOG_NS) {
         lastLogNs = now;
         Log.info(describe());
      }
   }

   /** The cap the detail aims for, 0 = none (stock detail). */
   static int targetFps() {
      if (FrameCap.uncappedNow()) {
         return Math.max(0, Config.ZOMBIE_LOD_UNCAPPED_FPS);
      }
      return FrameCap.lockNow();
   }

   /**
    * The controller step: the next level from the current one, the window's median and 90th-percentile frame step,
    * the target fps and whether climbing is allowed (not within {@link #CLIMB_HOLD_NS} of a cut).
    */
   static float next(float level, double p50Ns, double p90Ns, int targetFps, boolean mayClimb) {
      if (targetFps <= 0) {
         return Math.min(1.0F, level + UP_STEP); // no target: back to stock detail
      }
      if (p50Ns <= 0.0) {
         return level;
      }
      double budget = 1.0e9 / targetFps;
      double ratio = p50Ns / budget;
      if (ratio > OVER) {
         // the further over the budget, the bigger the cut (at most three steps at once)
         float cut = DOWN_STEP * (float)Math.min(3.0, 1.0 + (ratio - OVER) / 0.25);
         return Math.max(0.0F, level - cut);
      }
      if (mayClimb && p90Ns / budget < HEADROOM) {
         return Math.min(1.0F, level + UP_STEP);
      }
      return level;
   }

   /** Zombies drawn as 3D models at the current level (at most the stock 510). */
   public static int max3d() {
      return scale(level, Config.ZOMBIE_LOD_MIN_3D, STOCK_3D);
   }

   /** Zombies that blend animations at the current level (at most {@code stockBlended}). */
   public static int blended(int stockBlended) {
      return scale(level, Config.ZOMBIE_LOD_MIN_BLEND, stockBlended);
   }

   static int scale(float level, int min, int max) {
      int lo = Math.max(0, Math.min(min, max));
      return lo + Math.round(level * (max - lo));
   }

   static float level() {
      return level;
   }

   public static String describe() {
      return String.format(java.util.Locale.ROOT, "zombie lod: level %.2f, 3d %d, blended %d, median step %.2f ms, target %s",
         level, max3d(), blended(zombie.core.PerformanceSettings.numberZombiesBlended), lastP50Ns / 1.0e6,
         lastTargetFps > 0 ? lastTargetFps + " fps (" + String.format(java.util.Locale.ROOT, "%.2f", 1000.0 / lastTargetFps) + " ms)" : "none (stock detail)");
   }
}
