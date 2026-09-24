package pzopt;

import java.util.Locale;

/**
 * pzopt.DrivePath + DrivePilot in a simulated car: CarController's steering law (the per-frame step toward minus the
 * input, the decay for small inputs, the speed-dependent clamp), a kinematic bicycle with a yaw lag and a grip limit,
 * cruise control / brake / coast. The Rosewood route (KY-60 east, right onto North Main St, south to Jacks Lane) at
 * 240, 60 and 30 fps, with the true steering gain off by x0.6 and x1.7 (the pilot learns it), a parked car on the
 * line (passed inside the street), and a road block (stopped before it).
 */
public class DrivePilotTest {
   static final String ROSEWOOD = "8002,11204.5/8106,11204.5/8106,11966";

   static final class Result {
      float maxXte, maxXteStraight, cornerKmh = Float.MAX_VALUE, endGap, minGap = Float.MAX_VALUE, seconds, topKmh;
      boolean done, offBand;
   }

   /** Street band half width at an arc length of the Rosewood path: KY-60 is 15 wide, North Main St 8. */
   static float[] band(DrivePath p, int i) {
      return p.s[i] < 104f ? new float[]{-7.5f, 7.5f} : new float[]{-4f, 4f};
   }

   static Result drive(String spec, float fps, float gainTrue, float[][] cars, boolean log) {
      DrivePath path = DrivePath.build(DrivePath.parseWaypoints(spec), 10f);
      float wheelbase = 2.6f, halfW = 0.9f, halfL = 2.1f;
      DrivePilot pilot = new DrivePilot(path, 120f, 5f, 6f, 5f, 0.6f, 40f, 0f, 0.15f, 0.5f, true, halfW, halfL, wheelbase);
      DrivePilot.Senses senses = new DrivePilot.Senses() {
         public float[] band(DrivePath p, int i) {
            return DrivePilotTest.band(p, i);
         }

         public void obstacles(DrivePath p, float s0, float s1, int iHint, DrivePilot.Obstacles out) {
            for (float[] c : cars) if (c[0] >= s0 && c[0] <= s1) out.add(c[0], c[1], c[2], c[3], c[4] > 0.5f);
         }

         public boolean onStreet(float x, float y) {
            return true;
         }
      };
      DrivePilot.Input in = new DrivePilot.Input();
      DrivePilot.Output out = new DrivePilot.Output();
      float dt = 1f / fps;
      float x = 8002f, y = 11204f, psi = 0f, v = 0f, steer = 0f, kAct = 0f;
      Result r = new Result();
      float t = 0f;
      for (; t < 120f && !pilot.done(); t += dt) {
         float kmh = v * 3.6f;
         float clamp = (0.9f - 0.3f) * (1f - Math.min(1f, kmh / 120f)) + 0.3f;
         in.x = x; in.y = y; in.fx = (float)Math.cos(psi); in.fy = (float)Math.sin(psi);
         in.v = v; in.kmh = kmh; in.steer = steer; in.maxKmh = 120f; in.steerClamp = clamp;
         in.multiplier = 48f * dt; in.dt = dt;
         pilot.step(in, out, senses);
         float a;
         if (out.brake) a = -8f;
         else if (kmh < out.regulatorKmh) a = 5f * (1f - kmh / 125f);
         else a = -0.8f;
         v = Math.max(0f, Math.min(122f / 3.6f, v + a * dt));
         float fpsMod = 60f * dt;
         float g = 0.06f * fpsMod * Math.max(0.1f, 1f - kmh / 120f);
         steer = DrivePilot.predict(steer, out.steering, fpsMod, g, clamp);
         float kWant = -gainTrue / wheelbase * steer;
         if (v > 0.5f) kWant = Math.max(-9f / (v * v), Math.min(9f / (v * v), kWant)); // grip
         kAct += (kWant - kAct) * Math.min(1f, dt / 0.15f);
         psi += kAct * v * dt;
         x += Math.cos(psi) * v * dt;
         y += Math.sin(psi) * v * dt;
         int i = path.nearest(x, y, 0, path.n - 1);
         float lat = path.lateral(i, x, y), s = path.along(i, x, y);
         float xte = Math.abs(lat - pilot.offsetNow);
         if (t > 2f) r.maxXte = Math.max(r.maxXte, xte);
         boolean straight = Math.abs(path.k[i]) < 1e-5f && (s < 80f || s > 150f) && t > 3f && pilot.obstaclesAhead == 0 && Math.abs(pilot.offsetNow) < 1e-3f;
         if (straight) r.maxXteStraight = Math.max(r.maxXteStraight, xte);
         if (Math.abs(path.k[i]) > 1e-5f) r.cornerKmh = Math.min(r.cornerKmh, kmh);
         float[] b = band(path, i);
         if (lat - halfW < b[0] - 0.05f || lat + halfW > b[1] + 0.05f) r.offBand = true;
         for (float[] c : cars) {
            // gap between the car's box and the obstacle's box, both along the path (the pilot's frame)
            float ds = Math.abs(s - c[0]) - halfL - c[2], dl = Math.abs(lat - c[1]) - halfW - c[3];
            r.minGap = Math.min(r.minGap, Math.max(ds, dl));
         }
         r.topKmh = Math.max(r.topKmh, kmh);
         if (log && ((int)(t * 4f) != (int)((t - dt) * 4f))) {
            System.out.println(String.format(Locale.ROOT, "  t=%5.2f s=%6.1f lat=%+5.2f off=%+5.2f kmh=%5.1f tgt=%5.1f steer=%+.3f in=%+.2f brake=%s K=%.2f",
                  t, s, lat, pilot.offsetNow, kmh, pilot.targetTps * pilot.kmhPerTps(), steer, out.steering, out.brake, pilot.gain()));
         }
      }
      r.done = pilot.done();
      r.seconds = t;
      r.endGap = path.length - pilot.progress();
      return r;
   }

   static void report(String name, Result r) {
      System.out.println(String.format(Locale.ROOT, "%-34s done=%s %5.1f s top %5.1f km/h corner %5.1f km/h xte max %.2f straight %.2f end gap %.1f min obstacle gap %s off band %s",
            name, r.done, r.seconds, r.topKmh, r.cornerKmh, r.maxXte, r.maxXteStraight, r.endGap, r.minGap == Float.MAX_VALUE ? "-" : String.format(Locale.ROOT, "%.2f", r.minGap), r.offBand));
   }

   public static void main(String[] args) {
      boolean log = args.length > 0 && args[0].equals("-v");
      // geometry: corners are tangent arcs, arc length and curvature consistent
      DrivePath p = DrivePath.build(DrivePath.parseWaypoints(ROSEWOOD), 10f);
      Check.check(Math.abs(p.length - (104f + 761.5f - 20f + 10f * (float)Math.PI / 2f)) < 1f, "Rosewood path length " + p.length);
      int corner = 0;
      for (int i = 0; i < p.n; i++) if (Math.abs(p.k[i] - 0.1f) < 1e-4f) corner++;
      Check.check(corner > 25 && corner < 35, "right-hand corner samples at k=+0.1: " + corner);
      Check.check(Math.abs(p.x[p.n - 1] - 8106f) < 1e-3f && Math.abs(p.y[p.n - 1] - 11966f) < 1e-3f, "ends on the last waypoint");
      for (int i = 1; i < p.n; i++) {
         float ds = p.s[i] - p.s[i - 1];
         Check.check(ds > 0f && ds <= DrivePath.STEP + 1e-3f, "sample spacing at " + i + ": " + ds);
      }
      // a point 2 tiles south of the KY-60 line, 50 tiles in: lateral +2 (right of eastbound), along 50
      int i50 = p.nearest(8052f, 11206.5f, 0, p.n - 1);
      Check.check(Math.abs(p.lateral(i50, 8052f, 11206.5f) - 2f) < 1e-3f && Math.abs(p.along(i50, 8052f, 11206.5f) - 50f) < 0.3f, "lateral / along");
      // steering step: the chosen input moves the steering value toward the wanted one, never away (a value of
      // 0.1 or less cannot be held exactly: the smallest input that is not a decay drifts it by 0.0016 a frame here)
      for (float st = -0.8f; st <= 0.8f; st += 0.1f) {
         for (float want = -0.8f; want <= 0.8f; want += 0.1f) {
            float in = DrivePilot.pickSteering(st, want, 0.2f, 60f, 120f, 0.9f);
            float next = DrivePilot.predict(st, in, 0.25f, 0.06f * 0.25f * 0.5f, 0.9f);
            Check.check(Math.abs(next - want) <= Math.abs(st - want) + 0.002f, "steering step from " + st + " toward " + want);
         }
      }

      float[][] none = {};
      for (float fps : new float[]{240f, 60f, 30f}) {
         for (float gain : new float[]{1f, 0.6f, 1.7f}) {
            Result r = drive(ROSEWOOD, fps, gain, none, log && fps == 240f && gain == 1f);
            report(String.format(Locale.ROOT, "rosewood %3.0f fps gain x%.1f", fps, gain), r);
            Check.check(r.done, "route done");
            Check.check(r.maxXteStraight < 0.6f, "straight lane keeping " + r.maxXteStraight);
            Check.check(r.maxXte < 2.2f, "whole route cross-track " + r.maxXte);
            Check.check(!r.offBand, "stays on the street");
            Check.check(r.topKmh > 110f, "reaches the cruise speed");
            Check.check(r.endGap < 3.5f, "stops at the end");
         }
      }
      // a parked car on the line 300 tiles into North Main St: passed inside the street, clear of it
      float[][] parked = {{400f, 0f, 2.1f, 0.9f, 1f}};
      Result rp = drive(ROSEWOOD, 240f, 1f, parked, false);
      report("parked car on the line", rp);
      Check.check(rp.done && rp.minGap > 0.2f && !rp.offBand, "passes the parked car");
      // the street blocked edge to edge: stops before it and does not finish
      float[][] block = {{400f, 0f, 1f, 4f, 1f}};
      Result rb = drive(ROSEWOOD, 240f, 1f, block, false);
      report("road block", rb);
      Check.check(!rb.done && rb.minGap > 0.3f, "stops before the block");
      // a zombie on the line: passed if possible (soft)
      float[][] zombie = {{300f, 0.3f, 0.35f, 0.35f, 0f}};
      Result rz = drive(ROSEWOOD, 240f, 1f, zombie, false);
      report("zombie on the line", rz);
      Check.check(rz.done && rz.minGap > 0.2f, "passes the zombie");
      System.out.println("DrivePilotTest ok");
   }
}
