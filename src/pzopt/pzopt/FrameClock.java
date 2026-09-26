package pzopt;

import zombie.GameTime;

/**
 * The simulation step of a frame on the display's grid (`frameClockSmooth`, 2026-09-26,
 * docs/findings-car-jitter-2026-09-26.md).
 *
 * <p>Stock ({@code FPSTracking.frameStep}) gives every frame the measured length of the previous one as its simulation
 * step ({@code GameTime.fpsMultiplier = 60 x dt}). With vsync or a frame cap the frames reach the screen on a fixed
 * grid, so a frame that started 0.5 ms late and the next one that started 0.5 ms early are still shown one refresh
 * apart, but the world moved 1 ms of simulation more and then less between them: at 120 km/h and zoom 1 that is a
 * pixel of judder for every such wobble of the frame starts. The fix of "The Elusive Frame Timing" (Croteam, GDC 2019):
 * step by what the display shows. Here the grid period is a slow average of the frame times near the median of the last 64; a frame within 20 %
 * of a whole number of periods steps exactly that many periods, any other frame (a real hitch, an uncapped frame rate)
 * keeps its measured time; the difference to the wall clock is carried and fed back at most 1 % of a period a frame, so
 * the game time never drifts.
 */
public final class FrameClock {
   private FrameClock() {
   }

   static final boolean ON = Config.FRAME_CLOCK_SMOOTH;
   private static final double[] ring = new double[64];
   private static final double[] sorted = new double[64];
   private static int count, next;
   private static double drift; // seconds measured but not yet simulated (negative: simulated ahead)
   private static double period;

   /** GameWindow.frameStep, right after FPSTracking.frameStep set GameTime.fpsMultiplier from the measured frame time. */
   public static void afterFpsTracking() {
      if (!ON || !Overrides.enabled()) {
         return;
      }
      GameTime gt = GameTime.instance;
      float mult = gt.fpsMultiplier;
      if (mult >= 5.0F || mult <= 0.0F) { // the stock clamp (a stall of 83 ms or more): leave it, forget the carried time
         drift = 0.0;
         return;
      }
      gt.fpsMultiplier = (float)Math.min(5.0, step(mult / 60.0) * 60.0);
   }

   /** The simulation step for a frame measured at {@code dt} seconds (keeps the period estimate and the carried time). */
   static double step(double dt) {
      ring[next] = dt;
      next = (next + 1) & 63;
      if (count < 64) {
         count++;
      }
      if (count < 16) {
         return dt;
      }
      System.arraycopy(ring, 0, sorted, 0, count);
      java.util.Arrays.sort(sorted, 0, count);
      double med = sorted[count / 2];
      if (med <= 0.0) {
         return dt;
      }
      // the period: the median seeds it and catches a new frame rate; otherwise a slow average of the frames near one
      // period, so it does not wobble with the noise the median of 64 frames still has
      if (period <= 0.0 || Math.abs(period - med) > 0.1 * med) {
         period = med;
      } else if (Math.abs(dt - period) <= 0.2 * period) {
         period += (dt - period) * 0.01;
      }
      double p = period;
      long k = Math.round(dt / p);
      double used = dt;
      if (k >= 1 && Math.abs(dt - k * p) <= 0.2 * p) {
         used = k * p;
      }
      drift += dt - used;
      if (Math.abs(drift) > 2.0 * p) {
         used += drift; // the period estimate is off (the rate just changed): catch up at once
         drift = 0.0;
      } else {
         double c = Math.max(-0.01 * p, Math.min(0.01 * p, drift * 0.05));
         used += c;
         drift -= c;
      }
      return used;
   }

   /** Test hook: forget the history. */
   static void reset() {
      count = next = 0;
      drift = 0.0;
      period = 0.0;
   }
}
