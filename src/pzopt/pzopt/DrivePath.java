package pzopt;

import java.util.ArrayList;

/**
 * A harness drive route as a dense centreline (flag path=, 2026-09-24): waypoints in world tiles joined by straight
 * segments, every corner rounded with a circular arc, sampled every {@link #STEP} tiles with arc length, unit heading
 * and signed curvature. The world's y axis points south, so a positive curvature (and a positive lateral offset) is a
 * turn to the right / a point right of the direction of travel. Pure geometry, no game classes: {@code DrivePathTest}.
 */
final class DrivePath {
   static final float STEP = 0.5f;
   final float[] x, y, hx, hy, k, s;
   final int n;
   final float length;
   /** The speed (tiles/s) each sample allows from the curvature from here to the end, braking at {@code decel}: fill with {@link #planSpeeds}. */
   float[] vmax;

   private DrivePath(float[] x, float[] y, float[] hx, float[] hy, float[] k, float[] s) {
      this.x = x;
      this.y = y;
      this.hx = hx;
      this.hy = hy;
      this.k = k;
      this.s = s;
      this.n = x.length;
      this.length = s[n - 1];
   }

   /** "x,y/x,y/..." (';' also separates points): the waypoints, at least two distinct ones. */
   static float[][] parseWaypoints(String spec) {
      ArrayList<float[]> pts = new ArrayList<>();
      for (String part : spec.trim().split("[/;]")) {
         if (part.isBlank()) continue;
         String[] xy = part.trim().split(",");
         if (xy.length != 2) throw new IllegalArgumentException("path point '" + part + "' is not x,y");
         float px = Float.parseFloat(xy[0].trim()), py = Float.parseFloat(xy[1].trim());
         if (!pts.isEmpty()) {
            float[] last = pts.get(pts.size() - 1);
            if (Math.hypot(px - last[0], py - last[1]) < 1e-3) continue;
         }
         pts.add(new float[]{px, py});
      }
      if (pts.size() < 2) throw new IllegalArgumentException("path needs two distinct points: " + spec);
      return pts.toArray(new float[0][]);
   }

   /**
    * The dense path through the waypoints. Each corner becomes an arc of radius {@code cornerRadius} tangent to both
    * segments; where the segments are too short for that radius (the tangent length may use at most 45 % of either),
    * the radius shrinks.
    */
   static DrivePath build(float[][] wp, float cornerRadius) {
      int m = wp.length;
      // corner i (1..m-2): arc start A, end B, centre C, radius R, sweep sign sg, start angle a0, sweep phi
      float[] ax = new float[m], ay = new float[m], bx = new float[m], by = new float[m];
      float[] cx = new float[m], cy = new float[m], rr = new float[m], a0 = new float[m], phi = new float[m];
      int[] sg = new int[m];
      ax[0] = bx[0] = wp[0][0];
      ay[0] = by[0] = wp[0][1];
      ax[m - 1] = bx[m - 1] = wp[m - 1][0];
      ay[m - 1] = by[m - 1] = wp[m - 1][1];
      for (int i = 1; i < m - 1; i++) {
         float inx = wp[i][0] - wp[i - 1][0], iny = wp[i][1] - wp[i - 1][1];
         float outx = wp[i + 1][0] - wp[i][0], outy = wp[i + 1][1] - wp[i][1];
         float lin = (float)Math.hypot(inx, iny), lout = (float)Math.hypot(outx, outy);
         inx /= lin; iny /= lin; outx /= lout; outy /= lout;
         float cross = inx * outy - iny * outx, dot = inx * outx + iny * outy;
         float theta = (float)Math.atan2(Math.abs(cross), dot); // 0 = straight on
         if (theta < 1e-3f) {
            ax[i] = bx[i] = wp[i][0];
            ay[i] = by[i] = wp[i][1];
            continue;
         }
         float tanHalf = (float)Math.tan(theta / 2);
         float t = Math.min(cornerRadius * tanHalf, 0.45f * Math.min(lin, lout));
         float r = t / tanHalf;
         int sign = cross > 0 ? 1 : -1; // +1 = right turn (y points south)
         ax[i] = wp[i][0] - inx * t;
         ay[i] = wp[i][1] - iny * t;
         bx[i] = wp[i][0] + outx * t;
         by[i] = wp[i][1] + outy * t;
         float nx = -iny, ny = inx; // right-hand normal of the incoming direction
         cx[i] = ax[i] + nx * r * sign;
         cy[i] = ay[i] + ny * r * sign;
         rr[i] = r;
         sg[i] = sign;
         a0[i] = (float)Math.atan2(ay[i] - cy[i], ax[i] - cx[i]);
         phi[i] = theta;
      }
      ArrayList<float[]> out = new ArrayList<>(); // {x, y, hx, hy, k}
      float carry = 0f; // distance already walked past the last emitted sample
      for (int i = 0; i < m - 1; i++) {
         // straight from the end of corner i to the start of corner i+1
         float px = bx[i], py = by[i], qx = ax[i + 1], qy = ay[i + 1];
         float len = (float)Math.hypot(qx - px, qy - py);
         float dx = wp[i + 1][0] - wp[i][0], dy = wp[i + 1][1] - wp[i][1];
         float dl = (float)Math.hypot(dx, dy);
         dx /= dl; dy /= dl;
         float d = out.isEmpty() ? 0f : STEP - carry;
         for (; d <= len + 1e-4f; d += STEP) out.add(new float[]{px + dx * d, py + dy * d, dx, dy, 0f});
         carry = len - (d - STEP);
         if (i + 1 < m - 1 && rr[i + 1] > 0f) {
            int c = i + 1;
            float arc = rr[c] * phi[c];
            float dd = STEP - carry;
            for (; dd <= arc + 1e-4f; dd += STEP) {
               float a = a0[c] + sg[c] * dd / rr[c];
               float ca = (float)Math.cos(a), sa = (float)Math.sin(a);
               out.add(new float[]{cx[c] + rr[c] * ca, cy[c] + rr[c] * sa, -sg[c] * sa, sg[c] * ca, sg[c] / rr[c]});
            }
            carry = arc - (dd - STEP);
         }
      }
      float[] last = out.get(out.size() - 1);
      if (Math.hypot(last[0] - wp[m - 1][0], last[1] - wp[m - 1][1]) > 1e-3) {
         out.add(new float[]{wp[m - 1][0], wp[m - 1][1], last[2], last[3], 0f});
      }
      int n = out.size();
      float[] x = new float[n], y = new float[n], hx = new float[n], hy = new float[n], k = new float[n], s = new float[n];
      for (int i = 0; i < n; i++) {
         float[] p = out.get(i);
         x[i] = p[0]; y[i] = p[1]; hx[i] = p[2]; hy[i] = p[3]; k[i] = p[4];
         if (i > 0) s[i] = s[i - 1] + (float)Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
      }
      return new DrivePath(x, y, hx, hy, k, s);
   }

   /**
    * Speed limits per sample: the corner speed sqrt(latAccel / |k|), and from every later sample the speed from which
    * braking at {@code decel} still makes it (one backward pass); {@code stopAtEnd} = zero at the last sample.
    */
   void planSpeeds(float latAccel, float decel, boolean stopAtEnd) {
      float[] v = new float[n];
      for (int i = 0; i < n; i++) v[i] = Math.abs(k[i]) < 1e-5f ? Float.MAX_VALUE : (float)Math.sqrt(latAccel / Math.abs(k[i]));
      if (stopAtEnd) v[n - 1] = 0f;
      for (int i = n - 2; i >= 0; i--) {
         float ds = s[i + 1] - s[i];
         v[i] = Math.min(v[i], (float)Math.sqrt(v[i + 1] * v[i + 1] + 2f * decel * ds));
      }
      vmax = v;
   }

   /** Index of the sample nearest (px,py), searched in [from, to] (clamped). */
   int nearest(float px, float py, int from, int to) {
      from = Math.max(0, from);
      to = Math.min(n - 1, to);
      int best = from;
      float bd = Float.MAX_VALUE;
      for (int i = from; i <= to; i++) {
         float dx = px - x[i], dy = py - y[i], d = dx * dx + dy * dy;
         if (d < bd) { bd = d; best = i; }
      }
      return best;
   }

   /** Arc length of the point's projection onto the path near sample i. */
   float along(int i, float px, float py) {
      return Math.max(0f, Math.min(length, alongRaw(i, px, py)));
   }

   /** The same, not clamped to the path: negative before the start, beyond {@link #length} past the end. */
   float alongRaw(int i, float px, float py) {
      return s[i] + (px - x[i]) * hx[i] + (py - y[i]) * hy[i];
   }

   /** Signed distance of the point from the path near sample i: + = right of the direction of travel. */
   float lateral(int i, float px, float py) {
      return (px - x[i]) * -hy[i] + (py - y[i]) * hx[i];
   }

   /** Sample index at arc length sa (clamped). */
   int indexAt(float sa) {
      if (sa <= 0f) return 0;
      if (sa >= length) return n - 1;
      int i = Math.min(n - 1, (int)(sa / STEP)); // samples are STEP apart up to the corner rounding
      while (i > 0 && s[i] > sa) i--;
      while (i < n - 1 && s[i + 1] <= sa) i++;
      return i;
   }

   /** The speed limit at arc length sa, interpolated. */
   float vmaxAt(float sa) {
      int i = indexAt(sa);
      if (i >= n - 1) return vmax[n - 1];
      float t = (sa - s[i]) / Math.max(1e-4f, s[i + 1] - s[i]);
      return vmax[i] + (vmax[i + 1] - vmax[i]) * Math.max(0f, Math.min(1f, t));
   }
}
