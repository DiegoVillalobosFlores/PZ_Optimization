package pzopt;

/** The display-grid frame clock: wobbly frame starts step whole periods, hitches keep their time, no drift. */
public class FrameClockTest {
   public static void main(String[] args) {
      double p = 1.0 / 120.0;
      java.util.Random r = new java.util.Random(7);
      FrameClock.reset();
      double wall = 0.0, sim = 0.0, worst = 0.0;
      for (int i = 0; i < 2000; i++) {
         double dt = p + (r.nextDouble() - 0.5) * 0.001; // +-0.5 ms around the 120 Hz grid
         if (i == 1000) {
            dt = 3 * p + 0.0004; // a hitch of two missed refreshes
         }
         double used = FrameClock.step(dt);
         wall += dt;
         sim += used;
         if (i > 64 && i != 1000) {
            worst = Math.max(worst, Math.abs(used - p));
         }
         Check.check(Math.abs(sim - wall) < 2.5 * p, "the simulated time stays with the wall clock at " + i + ": " + (sim - wall));
      }
      Check.check(worst < 0.02 * p, "wobbly frames step one period (+ the slow feedback): worst " + worst * 1000 + " ms");
      FrameClock.reset();
      for (int i = 0; i < 100; i++) {
         FrameClock.step(p);
      }
      double used = FrameClock.step(0.050);
      Check.check(Math.abs(used - 0.050) < 1e-9, "a 50 ms stall is not on the grid: measured time " + used);
      FrameClock.reset();
      for (int i = 0; i < 200; i++) {
         FrameClock.step(p);
      }
      double s = 0.0, w = 0.0;
      for (int i = 0; i < 300; i++) { // the rate drops to 60 fps
         s += FrameClock.step(2 * p);
         w += 2 * p;
      }
      Check.check(Math.abs(s - w) < 2.5 * p, "a new frame rate never drifts: " + (s - w));
      Check.check(Math.abs(VehicleSmooth.lerpAngle(3.0F, -3.0F, 0.5F) - (float)Math.PI) < 0.01F
            || Math.abs(VehicleSmooth.lerpAngle(3.0F, -3.0F, 0.5F) + (float)Math.PI) < 0.01F, "wheel angle across the wrap takes the short way");
      Check.check(Math.abs(VehicleSmooth.lerpAngle(1.0F, 2.0F, 0.25F) - 1.25F) < 1e-5F, "plain angle lerp");
      System.out.println("FrameClockTest ok");
   }
}
