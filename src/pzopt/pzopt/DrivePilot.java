package pzopt;

import java.util.Locale;

/**
 * The harness driver for a {@link DrivePath} (flag path=, 2026-09-24). Pure control law; the game side is
 * {@link DriveSenses} (road edges, obstacles) and Harness (reads the vehicle, applies the {@link Output}).
 *
 * Steering: pure pursuit toward a point {@code look} tiles ahead on the path (plus the avoidance offset), which gives
 * the curvature to drive; curvature per unit of the game's steering value {@code K} is learned while driving (start
 * 1 / wheelbase). The game moves its steering value toward minus the input only by {@code 0.06 x fpsMod x delta} per
 * frame (delta = 1 - speed / max speed, at least 0.1) and lets it decay by {@code 0.04 x fpsMod} for inputs within
 * +-0.1 (CarController.update), so every frame the input is the candidate whose predicted next steering value lands
 * nearest the wanted one: analogue control through the joypad's field ({@code clientControls.steering}), and a duty
 * cycle where only a big input can hold a small angle at speed.
 *
 * Speed: the path's speed plan (corner speed from the lateral acceleration, braking distance to every corner and to
 * the end), a stop before a hard obstacle nothing can pass, the cruise speed; the game's cruise control holds it and
 * the brake pedal is pressed when the car is above it by more than a small margin.
 *
 * Obstacles ahead (other vehicles, solid squares on the street; zombies as soft ones: passed if there is room, never
 * braked for) shift the driven line sideways inside the street's paved band, ramped in and out at {@code slope}
 * tiles per tile.
 */
final class DrivePilot {
   /** What the pilot needs from the world; the game implementation is {@link DriveSenses}. */
   interface Senses {
      /** Street edges across the path at sample i: {left, right} lateral offsets (+ = right), or null when unknown. */
      float[] band(DrivePath path, int i);

      /** Obstacles between arc lengths s0 and s1, appended as {s, lateral, halfAlong, halfAcross, hard 1 / soft 0}. */
      void obstacles(DrivePath path, float s0, float s1, int iHint, Obstacles out);

      /** True when (x,y) is on a street square (for the off-road count); unknown = true. */
      boolean onStreet(float x, float y);
   }

   static final class Obstacles {
      float[] v = new float[5 * 64];
      int n;

      void add(float s, float lat, float halfAlong, float halfAcross, boolean hard) {
         if (5 * (n + 1) > v.length) v = java.util.Arrays.copyOf(v, v.length * 2);
         int o = 5 * n++;
         v[o] = s; v[o + 1] = lat; v[o + 2] = halfAlong; v[o + 3] = halfAcross; v[o + 4] = hard ? 1f : 0f;
      }
   }

   /** One frame of the vehicle, read by the caller before the game's controller runs. */
   static final class Input {
      float x, y;          // world tiles
      float fx, fy;        // unit forward vector (world x, y)
      float v;             // ground speed, tiles/s
      float kmh;           // the speedometer (km/h, what the cruise control compares)
      float steer;         // CarController.getVehicleSteering()
      float maxKmh;        // BaseVehicle.getMaxSpeed()
      float steerClamp;    // VehicleScript.getSteeringClamp(kmh)
      float multiplier;    // GameTime.getMultiplier()
      float dt;            // seconds since the last frame
      boolean chunkAhead;  // BaseVehicle.isInvalidChunkAhead(): the game brakes by itself
   }

   /** What to feed the controller this frame. */
   static final class Output {
      float steering;      // clientControls.steering, -1 (left) .. +1 (right)
      boolean brake;       // clientControls.brake
      float regulatorKmh;  // cruise-control speed
   }

   final DrivePath path;
   // parameters (flags)
   final float cruiseKmh, latAccel, decel, lookMin, lookTime, lookMax, lane, slope, margin;
   final boolean stopAtEnd;
   final float halfW, halfL, wheelbase;

   // state
   private int idx;
   private float sNow;
   private float gainK;               // curvature per unit of -steer
   private final float gainK0;
   private float kmhPerTps = 3.6f;    // speedometer km/h per tile/s, learned
   private float lastHeading = Float.NaN;
   private float kFilt, steerFilt;
   private float lastOffset;
   private final Obstacles obstacles = new Obstacles();
   private boolean done;

   // telemetry
   float xte, xteBase, headingErrDeg, targetTps, offsetNow, lookTiles, curvCmd, stopAt = Float.NaN;
   int obstaclesAhead;
   float sumXte2, maxXte, drivenS;
   int frames, offStreetFrames, chunkAheadFrames, brakeFrames, impacts, avoidFrames, stopFrames;
   float minTpsAfterCruise = Float.MAX_VALUE;
   boolean reachedCruise;
   private float vHist0 = -1f, vHistT;
   String lastImpact = "";

   DrivePilot(DrivePath path, float cruiseKmh, float latAccel, float decel, float lookMin, float lookTime, float lookMax,
              float lane, float slope, float margin, boolean stopAtEnd, float halfW, float halfL, float wheelbase) {
      this.path = path;
      this.cruiseKmh = cruiseKmh;
      this.latAccel = latAccel;
      this.decel = decel;
      this.lookMin = lookMin;
      this.lookTime = lookTime;
      this.lookMax = lookMax;
      this.lane = lane;
      this.slope = slope;
      this.margin = margin;
      this.stopAtEnd = stopAtEnd;
      this.halfW = halfW;
      this.halfL = halfL;
      this.wheelbase = wheelbase;
      this.gainK0 = 1f / Math.max(1f, wheelbase);
      this.gainK = gainK0;
      path.planSpeeds(latAccel, decel, stopAtEnd);
   }

   boolean done() {
      return done;
   }

   float progress() {
      return sNow;
   }

   float gain() {
      return gainK / gainK0;
   }

   float kmhPerTps() {
      return kmhPerTps;
   }

   void step(Input in, Output out, Senses senses) {
      float dt = Math.max(1e-4f, Math.min(0.1f, in.dt));
      frames++;
      // --- where on the path
      int window = 8 + (int)(in.v * dt * 4f / DrivePath.STEP);
      idx = frames == 1 ? path.nearest(in.x, in.y, 0, path.n - 1) : path.nearest(in.x, in.y, idx - 4, idx + window + 8);
      sNow = path.along(idx, in.x, in.y);
      drivenS = Math.max(drivenS, sNow);
      xteBase = path.lateral(idx, in.x, in.y);
      float heading = (float)Math.atan2(in.fy, in.fx);
      float pathHeading = (float)Math.atan2(path.hy[idx], path.hx[idx]);
      headingErrDeg = (float)Math.toDegrees(wrap(heading - pathHeading));

      // --- learn the speedometer scale and the steering gain
      if (in.v > 3f && in.kmh > 5f) kmhPerTps += (in.kmh / in.v - kmhPerTps) * Math.min(1f, dt * 2f);
      if (!Float.isNaN(lastHeading) && in.v > 4f) {
         float kMeas = wrap(heading - lastHeading) / dt / in.v;
         float a = Math.min(1f, dt / 0.25f);
         kFilt += (kMeas - kFilt) * a;
         steerFilt += (in.steer - steerFilt) * a;
         if (Math.abs(steerFilt) > 0.02f && Math.abs(in.steer - steerFilt) < 0.2f * Math.abs(steerFilt)) {
            float est = -kFilt / steerFilt;
            if (est > 0f) gainK += (Math.max(0.3f * gainK0, Math.min(3f * gainK0, est)) - gainK) * Math.min(1f, dt / 1.5f);
         }
      }
      lastHeading = heading;

      // --- obstacles and the lateral offset profile
      float vCruise = cruiseKmh / kmhPerTps;
      float horizon = Math.max(30f, Math.min(160f, in.v * in.v / (2f * decel) + in.v * 1.5f + 20f));
      obstacles.n = 0;
      senses.obstacles(path, sNow - halfL - 1f, sNow + horizon, idx, obstacles);
      stopAt = Float.NaN;
      obstaclesAhead = 0;
      // requirements: {sLo, sHi, offset}; at most 32 considered, nearest first as reported
      int nreq = 0;
      float[] req = reqBuf;
      for (int o = 0; o < obstacles.n && nreq < 32; o++) {
         int b = 5 * o;
         float os = obstacles.v[b], olat = obstacles.v[b + 1], ohl = obstacles.v[b + 2], ohw = obstacles.v[b + 3];
         boolean hard = obstacles.v[b + 4] > 0.5f;
         float lo = olat - ohw - halfW - margin, hi = olat + ohw + halfW + margin;
         float sLo = os - ohl - halfL - 1.5f, sHi = os + ohl + halfL + 1f;
         if (sHi < sNow) continue;
         float want = lane;
         if (want <= lo || want >= hi) continue; // the planned line already clears it
         obstaclesAhead++;
         float[] band = senses.band(path, path.indexAt(os));
         float bandLo = band == null ? lane : band[0] + halfW + 0.3f, bandHi = band == null ? lane : band[1] - halfW - 0.3f;
         boolean leftOk = lo >= bandLo, rightOk = hi <= bandHi;
         float pick;
         if (leftOk && rightOk) pick = Math.abs(lo - lastOffset) <= Math.abs(hi - lastOffset) ? lo : hi;
         else if (leftOk) pick = lo;
         else if (rightOk) pick = hi;
         else {
            if (hard) {
               float st = sLo - 1f;
               if (Float.isNaN(stopAt) || st < stopAt) stopAt = st;
            }
            continue;
         }
         req[3 * nreq] = sLo;
         req[3 * nreq + 1] = sHi;
         req[3 * nreq + 2] = pick;
         nreq++;
      }

      // --- steering: pure pursuit on the (offset) path
      // a sideways move of A tiles as a half cosine over L tiles peaks at A pi^2 v^2 / (2 L^2) of lateral
      // acceleration; keep that under half the corner budget for a typical 2.5-tile move: the ramp slope A / L
      slopeNow = Math.min(slope, (float)Math.sqrt(2f * 2.5f * 0.5f * latAccel) / ((float)Math.PI * Math.max(1f, in.v)));
      float look = Math.max(lookMin, Math.min(lookMax, lookMin + lookTime * in.v));
      lookTiles = look;
      float sT = Math.min(path.length, sNow + look);
      int iT = path.indexAt(sT);
      float offT = offsetAt(sT, req, nreq), offHere = offsetAt(sNow, req, nreq);
      float[] bandT = senses.band(path, iT);
      if (bandT != null) offT = Math.max(bandT[0] + halfW + 0.3f, Math.min(bandT[1] - halfW - 0.3f, offT));
      offsetNow = offHere;
      lastOffset = offHere;
      if (nreq > 0) avoidFrames++;
      float tx = path.x[iT] - path.hy[iT] * offT, ty = path.y[iT] + path.hx[iT] * offT;
      if (sNow + look > path.length) { // past the end: extend the last heading so the target keeps leading
         float extra = sNow + look - path.length;
         tx += path.hx[path.n - 1] * extra;
         ty += path.hy[path.n - 1] * extra;
      }
      float dx = tx - in.x, dy = ty - in.y;
      float dist = (float)Math.sqrt(dx * dx + dy * dy);
      float sinA = dist < 1e-3f ? 0f : (in.fx * dy - in.fy * dx) / dist; // + = target right of the heading
      float cosA = dist < 1e-3f ? 1f : (in.fx * dx + in.fy * dy) / dist;
      float alpha = (float)Math.atan2(sinA, cosA);
      float kCmd = 2f * (float)Math.sin(alpha) / Math.max(dist, 1f);
      curvCmd = kCmd;
      xte = xteBase - offHere;
      float want = -kCmd / gainK;
      want = Math.max(-in.steerClamp, Math.min(in.steerClamp, want));
      out.steering = pickSteering(in.steer, want, in.multiplier, in.kmh, in.maxKmh, in.steerClamp);

      // --- speed
      float lat = Math.max(0f, in.v * 0.25f); // reaction: plan from where the car is a quarter second from now
      float vPlan = Math.min(vCruise, path.vmaxAt(Math.min(path.length, sNow + lat)));
      if (!Float.isNaN(stopAt)) {
         float d = stopAt - sNow - in.v * 0.3f;
         vPlan = Math.min(vPlan, (float)Math.sqrt(2f * decel * Math.max(0f, d)));
         stopFrames++;
      }
      if (stopAtEnd && sNow >= path.length - 1.5f) vPlan = 0f;
      targetTps = vPlan;
      boolean atCruise = vPlan >= vCruise - 1e-3f;
      out.regulatorKmh = atCruise ? cruiseKmh : Math.max(0f, vPlan * kmhPerTps);
      out.brake = in.v > vPlan + Math.max(0.6f, 0.04f * vPlan) || (vPlan < 0.3f && in.v > 0.05f);
      if (out.brake) brakeFrames++;

      // --- run statistics
      float ax = Math.abs(xte);
      sumXte2 += ax * ax;
      maxXte = Math.max(maxXte, ax);
      if (!senses.onStreet(in.x, in.y)) offStreetFrames++;
      if (in.chunkAhead) chunkAheadFrames++;
      if (!reachedCruise && in.kmh >= 0.95f * Math.min(cruiseKmh, in.maxKmh)) reachedCruise = true;
      if (reachedCruise && vPlan >= vCruise - 1e-3f) minTpsAfterCruise = Math.min(minTpsAfterCruise, in.v);
      // an impact: the speed fell by more than 3 tiles/s within 0.3 s while nothing asked for braking
      vHistT += dt;
      if (vHist0 < 0f || vHistT >= 0.3f) {
         if (vHist0 >= 0f && vHist0 - in.v > 3f && !out.brake && !in.chunkAhead && brakeFreeSince(frames)) {
            impacts++;
            lastImpact = String.format(Locale.ROOT, "%.1f,%.1f at s=%.0f (%.1f -> %.1f tiles/s)", in.x, in.y, sNow, vHist0, in.v);
         }
         vHist0 = in.v;
         vHistT = 0f;
      }
      if (out.brake) lastBrakeFrame = frames;
      if (sNow >= path.length - (stopAtEnd ? 3f : 0.5f) && (!stopAtEnd || in.v < 0.5f)) done = true;
   }

   private final float[] reqBuf = new float[96];
   private float slopeNow = 0.15f;
   private int lastBrakeFrame = -1000;

   private boolean brakeFreeSince(int frame) {
      return frame - lastBrakeFrame > 30;
   }

   /** The lateral offset the line has at arc length sa: the lane, pulled toward each avoidance requirement (ramps of {@code slope}). */
   private float offsetAt(float sa, float[] req, int nreq) {
      float best = lane;
      for (int r = 0; r < nreq; r++) {
         float sLo = req[3 * r], sHi = req[3 * r + 1], val = req[3 * r + 2];
         float d = sa < sLo ? sLo - sa : sa > sHi ? sa - sHi : 0f;
         float mag = Math.max(0f, Math.abs(val - lane) - slopeNow * d);
         if (mag > Math.abs(best - lane)) best = lane + Math.signum(val - lane) * mag;
      }
      return best;
   }

   /**
    * The input that brings the game's steering value nearest {@code want} next frame (CarController.update: for
    * |input| > 0.1 steer -= (input + steer) x 0.06 x fpsMod x delta, else a decay of 0.04 x fpsMod toward 0; then clamped).
    */
   static float pickSteering(float steer, float want, float multiplier, float kmh, float maxKmh, float clamp) {
      float fpsMod = multiplier / 0.8f;
      float delta = Math.max(0.1f, 1f - kmh / Math.max(1f, maxKmh));
      float g = 0.06f * fpsMod * delta;
      float exact = g > 1e-6f ? (steer - want) / g - steer : 0f;
      float best = 0f, bestErr = Math.abs(predict(steer, 0f, fpsMod, g, clamp) - want);
      for (float c : CANDIDATES) {
         float e = Math.abs(predict(steer, c, fpsMod, g, clamp) - want);
         if (e < bestErr - 1e-6f) { best = c; bestErr = e; }
      }
      float cx = Math.max(-1f, Math.min(1f, exact));
      if (Math.abs(cx) > 0.1f) {
         float e = Math.abs(predict(steer, cx, fpsMod, g, clamp) - want);
         if (e < bestErr - 1e-6f) { best = cx; }
      }
      return best;
   }

   private static final float[] CANDIDATES = {-1f, -0.6f, -0.3f, -0.11f, 0.11f, 0.3f, 0.6f, 1f};

   /** CarController.update's steering step for one frame. */
   static float predict(float steer, float input, float fpsMod, float g, float clamp) {
      float next;
      if (Math.abs(input) > 0.1f) next = steer - (input + steer) * g;
      else if (Math.abs(steer) <= 0.04f) next = 0f;
      else if (steer > 0f) next = Math.max(0f, steer - 0.04f * fpsMod);
      else next = Math.min(0f, steer + 0.04f * fpsMod);
      return Math.max(-clamp, Math.min(clamp, next));
   }

   static float wrap(float a) {
      while (a > Math.PI) a -= (float)(2 * Math.PI);
      while (a < -Math.PI) a += (float)(2 * Math.PI);
      return a;
   }

   /** One telemetry row (pzopt-drive.out). */
   String row(float t, Input in, Output out) {
      return String.format(Locale.ROOT, "%.3f\t%.2f\t%.2f\t%.1f\t%.2f\t%.1f\t%.2f\t%.1f\t%.1f\t%.2f\t%.3f\t%d\t%.2f\t%d\t%s\t%d",
            t, in.x, in.y, sNow, xte, headingErrDeg, in.v, in.kmh, targetTps * kmhPerTps, out.steering, in.steer,
            out.brake ? 1 : 0, offsetNow, obstaclesAhead, Float.isNaN(stopAt) ? "-" : String.format(Locale.ROOT, "%.0f", stopAt), in.chunkAhead ? 1 : 0);
   }

   static final String HEADER = "t\tx\ty\ts\txte\thdg_err\ttps\tkmh\ttarget_kmh\tinput\tsteer\tbrake\toffset\tobstacles\tstop_at\tchunk_ahead";

   /** Summary lines for pzopt-bench.out. */
   String describe(float seconds) {
      return String.format(Locale.ROOT,
            "drive_pilot=path\ndrive_path_length=%.1f\ndrive_progress=%.1f\ndrive_done=%s\ndrive_xte_rms=%.3f\ndrive_xte_max=%.2f"
                  + "\ndrive_off_street_s=%.2f\ndrive_chunk_ahead_s=%.2f\ndrive_brake_s=%.2f\ndrive_avoid_s=%.2f\ndrive_stop_for_obstacle_s=%.2f"
                  + "\ndrive_impacts=%d\ndrive_last_impact=%s\ndrive_reached_cruise=%s\ndrive_min_kmh_at_cruise=%.1f\ndrive_steer_gain=%.2f\ndrive_kmh_per_tile_s=%.3f",
            path.length, drivenS, done, frames == 0 ? 0f : (float)Math.sqrt(sumXte2 / frames), maxXte,
            share(offStreetFrames, seconds), share(chunkAheadFrames, seconds), share(brakeFrames, seconds), share(avoidFrames, seconds), share(stopFrames, seconds),
            impacts, lastImpact, reachedCruise, minTpsAfterCruise == Float.MAX_VALUE ? 0f : minTpsAfterCruise * kmhPerTps, gain(), kmhPerTps);
   }

   private float share(int count, float seconds) {
      return frames == 0 ? 0f : count * seconds / frames;
   }
}
