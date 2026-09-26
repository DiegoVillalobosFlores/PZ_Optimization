package pzopt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Colour grading as numbers (no GL): a look (exposure, white balance as a mired shift, tint, contrast, saturation,
 * split toning, the scotopic / Purkinje shift of night vision), the blend of the per-condition looks by weights, and
 * the bake of the result into a 3D LUT over a square-root shaper (more lattice points in the darks, where night lives).
 * {@link Grade} evaluates the weights from the climate, bakes on a worker and hands the table to the render thread.
 *
 * <p>The input is the finished SDR world pixel (gamma-encoded, what the stock screen shader outputs); every operation
 * runs on linear light (sRGB transfer decoded) and the result is encoded again. Pure black stays black under every look
 * (no additive lift): the fog of war is never brightened by the grade.
 */
public final class GradeMath {
   private GradeMath() {
   }

   /** Lattice points per axis (odd, like the usual .cube sizes). */
   public static final int N = 33;

   /** Rec.709 luminance. */
   static final float LR = 0.2126F, LG = 0.7152F, LB = 0.0722F;
   /** A rod (scotopic) response over linear Rec.709 RGB: the rods peak at ~507 nm, so green and blue carry it, red barely. */
   static final float SR = 0.06F, SG = 0.56F, SB = 0.38F;

   /** One look. Neutral = every field at its identity value (see {@link #neutral}). */
   public static final class Look {
      public float exposure; // stops
      public float mired; // colour filter as a shift of the light's temperature in mired (negative = cooler / bluer)
      public float tint; // green (-) / magenta (+)
      public float contrast = 1F; // log contrast around mid grey
      public float saturation = 1F;
      public float[] shadowGain = {1F, 1F, 1F}; // multiplicative colour in the shadows (luminance-normalised at use)
      public float[] highlightGain = {1F, 1F, 1F};
      public float purkinje; // 0..1: dark pixels shift to the rods' desaturated blue-green response
      public float purkinjeHi = 0.12F; // linear luminance at which the scotopic shift has faded out

      public Look copy() {
         Look l = new Look();
         l.exposure = exposure;
         l.mired = mired;
         l.tint = tint;
         l.contrast = contrast;
         l.saturation = saturation;
         l.shadowGain = shadowGain.clone();
         l.highlightGain = highlightGain.clone();
         l.purkinje = purkinje;
         l.purkinjeHi = purkinjeHi;
         return l;
      }

      @Override
      public String toString() {
         return String.format(java.util.Locale.ROOT, "ev=%.2f mired=%.1f tint=%.3f contrast=%.3f sat=%.3f shadow=%.2f,%.2f,%.2f high=%.2f,%.2f,%.2f purkinje=%.2f",
            exposure, mired, tint, contrast, saturation, shadowGain[0], shadowGain[1], shadowGain[2], highlightGain[0], highlightGain[1], highlightGain[2], purkinje);
      }
   }

   public static Look neutral() {
      return new Look();
   }

   /**
    * The weighted combination of looks: additive fields sum their weighted deltas, multiplicative ones multiply their
    * weighted powers (so two conditions that each desaturate by 10 % give 19 %, never below zero). Weights are 0..1.
    */
   public static Look blend(Look[] looks, float[] weights, float strength) {
      Look r = neutral();
      for (int i = 0; i < looks.length; i++) {
         float w = Math.max(0F, Math.min(1F, weights[i])) * strength;
         if (w <= 0F || looks[i] == null) {
            continue;
         }
         Look l = looks[i];
         r.exposure += w * l.exposure;
         r.mired += w * l.mired;
         r.tint += w * l.tint;
         r.contrast *= (float)Math.pow(Math.max(0.05F, l.contrast), w);
         r.saturation *= (float)Math.pow(Math.max(0.0F, l.saturation) + 1e-6F, w);
         for (int c = 0; c < 3; c++) {
            r.shadowGain[c] *= (float)Math.pow(Math.max(0.05F, l.shadowGain[c]), w);
            r.highlightGain[c] *= (float)Math.pow(Math.max(0.05F, l.highlightGain[c]), w);
         }
         r.purkinje = 1F - (1F - r.purkinje) * (1F - w * l.purkinje);
         r.purkinjeHi = Math.max(r.purkinjeHi, l.purkinje > 0F ? l.purkinjeHi : 0F);
      }
      return r;
   }

   // ---- transfer ----

   static float toLinear(float v) {
      v = Math.max(0F, v);
      return v <= 0.04045F ? v / 12.92F : (float)Math.pow((v + 0.055F) / 1.055F, 2.4);
   }

   static float toSrgb(float v) {
      v = Math.max(0F, v);
      return v <= 0.0031308F ? v * 12.92F : 1.055F * (float)Math.pow(v, 1.0 / 2.4) - 0.055F;
   }

   // the bakes evaluate the transfer ~800k times: tables (4096 intervals, linear interpolation; error < 2e-7 on
   // toLinear, < 3e-5 on toSrgb, whose table is indexed by sqrt(v) for its steep foot)
   private static final int TN = 4096;
   private static final float[] LIN = new float[TN + 2], SRGB = new float[TN + 2];

   static {
      for (int i = 0; i <= TN + 1; i++) {
         float u = Math.min(1F, i / (float)TN);
         LIN[i] = toLinear(u);
         SRGB[i] = toSrgb(u * u);
      }
   }

   static float toLinearFast(float v) {
      if (v <= 0F) {
         return 0F;
      }
      if (v >= 1F) {
         return toLinear(v);
      }
      float f = v * TN;
      int i = (int)f;
      return LIN[i] + (LIN[i + 1] - LIN[i]) * (f - i);
   }

   static float toSrgbFast(float v) {
      if (v <= 0F) {
         return 0F;
      }
      if (v >= 1F) {
         return toSrgb(v);
      }
      float f = (float)Math.sqrt(v) * TN;
      int i = (int)f;
      return SRGB[i] + (SRGB[i + 1] - SRGB[i]) * (f - i);
   }

   // ---- white balance: a colour filter of a Planckian light relative to D65, applied in LMS (von Kries) ----

   /** CIE xy of a blackbody at kelvin (Kim et al. 2002 cubic spline, 1667..25000 K). */
   static double[] planckXy(double k) {
      k = Math.max(1667.0, Math.min(25000.0, k));
      double x;
      if (k <= 4000.0) {
         x = -0.2661239e9 / (k * k * k) - 0.2343589e6 / (k * k) + 0.8776956e3 / k + 0.179910;
      } else {
         x = -3.0258469e9 / (k * k * k) + 2.1070379e6 / (k * k) + 0.2226347e3 / k + 0.240390;
      }
      double y;
      if (k <= 2222.0) {
         y = -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683;
      } else if (k <= 4000.0) {
         y = -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
      } else {
         y = 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483;
      }
      return new double[] {x, y};
   }

   /** Linear Rec.709 RGB -> XYZ and back, CAT02 LMS. */
   private static final double[][] RGB2XYZ = {{0.4124564, 0.3575761, 0.1804375}, {0.2126729, 0.7151522, 0.0721750}, {0.0193339, 0.1191920, 0.9503041}};
   private static final double[][] XYZ2RGB = {{3.2404542, -1.5371385, -0.4985314}, {-0.9692660, 1.8760108, 0.0415560}, {0.0556434, -0.2040259, 1.0572252}};
   private static final double[][] CAT02 = {{0.7328, 0.4296, -0.1624}, {-0.7036, 1.6975, 0.0061}, {0.0030, 0.0136, 0.9834}};
   private static final double[][] CAT02_INV = {{1.096124, -0.278869, 0.182745}, {0.454369, 0.473533, 0.072098}, {-0.009628, -0.005698, 1.015326}};

   private static double[] mul(double[][] m, double[] v) {
      return new double[] {m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2], m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
         m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
   }

   private static double[][] mul(double[][] a, double[][] b) {
      double[][] r = new double[3][3];
      for (int i = 0; i < 3; i++) {
         for (int j = 0; j < 3; j++) {
            r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
         }
      }
      return r;
   }

   private static double[] xyToXyz(double[] xy) {
      return new double[] {xy[0] / xy[1], 1.0, (1.0 - xy[0] - xy[1]) / xy[1]};
   }

   /**
    * The linear-RGB matrix of a colour filter: the picture as if lit by a light {@code mired} away from D65 (negative =
    * hotter = bluer), luminance of white kept, plus a green / magenta tint. Identity for (0, 0).
    */
   public static float[] whiteBalance(float mired, float tint) {
      double d65 = 1e6 / 6504.0;
      double k = 1e6 / Math.max(40.0, d65 + mired);
      double[] src = mul(CAT02, xyToXyz(new double[] {0.31271, 0.32902}));
      double[] dst = mul(CAT02, xyToXyz(planckXy(k)));
      // Planckian locus vs D65 (daylight locus) differ a little at 6504 K: normalise so mired 0 is identity
      double[] ref = mul(CAT02, xyToXyz(planckXy(6504.0)));
      double[][] gain = {{dst[0] / ref[0], 0, 0}, {0, dst[1] / ref[1], 0}, {0, 0, dst[2] / ref[2]}};
      double[][] m = mul(XYZ2RGB, mul(CAT02_INV, mul(gain, mul(CAT02, RGB2XYZ))));
      // tint: green gain against red / blue
      double t = tint;
      double[] tg = {1.0 + t * 0.5, 1.0 - t, 1.0 + t * 0.5};
      float[] out = new float[9];
      // white's luminance kept at 1
      double wr = m[0][0] + m[0][1] + m[0][2], wg = m[1][0] + m[1][1] + m[1][2], wb = m[2][0] + m[2][1] + m[2][2];
      double y = LR * wr * tg[0] + LG * wg * tg[1] + LB * wb * tg[2];
      for (int i = 0; i < 3; i++) {
         for (int j = 0; j < 3; j++) {
            out[i * 3 + j] = (float)(m[i][j] * tg[i] / y);
         }
      }
      return out;
   }

   /** Everything a bake needs, derived once per look. */
   static final class Prepared {
      final float gainEv;
      final float[] wb;
      final float contrast, saturation, purkinje, purkinjeHi;
      final float[] shadow = new float[3], high = new float[3];
      final boolean identity;

      Prepared(Look l) {
         gainEv = (float)Math.pow(2.0, l.exposure);
         wb = whiteBalance(l.mired, l.tint);
         contrast = l.contrast;
         saturation = l.saturation;
         purkinje = l.purkinje;
         purkinjeHi = l.purkinjeHi;
         normalise(l.shadowGain, shadow);
         normalise(l.highlightGain, high);
         identity = l.exposure == 0F && l.mired == 0F && l.tint == 0F && l.contrast == 1F && l.saturation == 1F && l.purkinje == 0F
            && isOne(l.shadowGain) && isOne(l.highlightGain);
      }

      private static boolean isOne(float[] g) {
         return g[0] == 1F && g[1] == 1F && g[2] == 1F;
      }

      private static void normalise(float[] g, float[] out) {
         float y = LR * g[0] + LG * g[1] + LB * g[2];
         y = y > 1e-6F ? y : 1F;
         out[0] = g[0] / y;
         out[1] = g[1] / y;
         out[2] = g[2] / y;
      }
   }

   private static final float MID = 0.18F;

   /** The look applied to one gamma-encoded colour; {@code out} gets the gamma-encoded result. */
   static void apply(Prepared p, float r0, float g0, float b0, float[] out) {
      applyLinear(p, toLinearFast(r0), toLinearFast(g0), toLinearFast(b0), out);
   }

   /** {@link #apply} on linear inputs (the bake decodes each lattice axis once). */
   static void applyLinear(Prepared p, float rl, float gl, float bl, float[] out) {
      float r = rl * p.gainEv, g = gl * p.gainEv, b = bl * p.gainEv;
      float[] m = p.wb;
      float wr = m[0] * r + m[1] * g + m[2] * b;
      float wg = m[3] * r + m[4] * g + m[5] * b;
      float wbb = m[6] * r + m[7] * g + m[8] * b;
      r = Math.max(0F, wr);
      g = Math.max(0F, wg);
      b = Math.max(0F, wbb);
      // scotopic shift (Purkinje): below the mesopic range the rods take over, colour fades to a blue-green grey of the
      // rods' own response; lamps and fires above it keep their colour
      if (p.purkinje > 0F) {
         float y = LR * r + LG * g + LB * b;
         float t = 1F - smoothstep(0F, p.purkinjeHi, y);
         float w = p.purkinje * t;
         if (w > 0F) {
            float s = SR * r + SG * g + SB * b;
            // rod grey tinted towards the night-blue the eye reports, luminance of the scotopic response kept
            float sr = s * 0.78F, sg = s * 0.96F, sb = s * 1.34F;
            r += (sr - r) * w;
            g += (sg - g) * w;
            b += (sb - b) * w;
         }
      }
      // split toning, multiplicative (black stays black)
      float y = LR * r + LG * g + LB * b;
      float hw = smoothstep(0.02F, 0.5F, y);
      r *= p.shadow[0] + (p.high[0] - p.shadow[0]) * hw;
      g *= p.shadow[1] + (p.high[1] - p.shadow[1]) * hw;
      b *= p.shadow[2] + (p.high[2] - p.shadow[2]) * hw;
      // contrast on log luminance around mid grey, hue kept (the ratio scales all channels)
      if (p.contrast != 1F) {
         y = LR * r + LG * g + LB * b;
         if (y > 1e-7F) {
            float yc = MID * (float)Math.pow(y / MID, p.contrast);
            float k = yc / y;
            r *= k;
            g *= k;
            b *= k;
         }
      }
      if (p.saturation != 1F) {
         y = LR * r + LG * g + LB * b;
         r = y + (r - y) * p.saturation;
         g = y + (g - y) * p.saturation;
         b = y + (b - y) * p.saturation;
      }
      out[0] = Math.min(1F, toSrgbFast(r));
      out[1] = Math.min(1F, toSrgbFast(g));
      out[2] = Math.min(1F, toSrgbFast(b));
   }

   static float smoothstep(float e0, float e1, float x) {
      float t = Math.max(0F, Math.min(1F, (x - e0) / (e1 - e0)));
      return t * t * (3F - 2F * t);
   }

   /** Lattice coordinate i (0..N-1) -> the gamma-encoded input it stands for (square-root shaper). */
   public static float latticeInput(int i) {
      float u = i / (float)(N - 1);
      return u * u;
   }

   /**
    * Bakes the look (then the optional .cube overlays, each blended by its weight) into an RGB float table, x fastest
    * (glTexImage3D order).
    */
   public static float[] bake(Look look, Cube[] cubes, float[] cubeWeights) {
      Prepared p = new Prepared(look);
      float[] t = new float[N * N * N * 3];
      float[] lin = new float[N];
      for (int i = 0; i < N; i++) {
         lin[i] = toLinear(latticeInput(i));
      }
      float[] o = new float[3];
      float[] c = new float[3];
      int k = 0;
      for (int bz = 0; bz < N; bz++) {
         float bi = latticeInput(bz);
         for (int gy = 0; gy < N; gy++) {
            float gi = latticeInput(gy);
            for (int rx = 0; rx < N; rx++) {
               float ri = latticeInput(rx);
               if (p.identity) {
                  o[0] = ri;
                  o[1] = gi;
                  o[2] = bi;
               } else {
                  applyLinear(p, lin[rx], lin[gy], lin[bz], o);
               }
               if (cubes != null) {
                  for (int q = 0; q < cubes.length; q++) {
                     float w = cubeWeights[q];
                     if (cubes[q] == null || w <= 0F) {
                        continue;
                     }
                     cubes[q].sample(o[0], o[1], o[2], c);
                     o[0] += (c[0] - o[0]) * w;
                     o[1] += (c[1] - o[1]) * w;
                     o[2] += (c[2] - o[2]) * w;
                  }
               }
               t[k++] = o[0];
               t[k++] = o[1];
               t[k++] = o[2];
            }
         }
      }
      return t;
   }

   /** Lattice points per axis of the fused LUT (the stock screen shader's tail + the grade; 65: max 1.1 8-bit steps off). */
   public static final int NF = 65;
   /**
    * The fused table stores FUSED_LO..FUSED_HI (the stock contrast leaves 0..1: -0.08..1.12); a table clamped to 0..1 put a
    * kink between lattice points that trilinear filtering smeared over 1.1 8-bit steps. 10 bits over 1.375: 0.34 steps.
    */
   public static final float FUSED_LO = -0.125F, FUSED_HI = 1.25F;

   /**
    * The stock screen shader's world path after its first desaturation (screen.frag {@code screenWorld}: clamp to 0..1,
    * {@code contrast(desaturate(c, 0.1), 1.2)}), in place. The film grain it adds after (at most 0.0015) is left out.
    */
   static void stockTail(float[] c) {
      float r = Math.max(0F, Math.min(1F, c[0])), g = Math.max(0F, Math.min(1F, c[1])), b = Math.max(0F, Math.min(1F, c[2]));
      float y = LR * r + LG * g + LB * b;
      r += (y - r) * 0.1F;
      g += (y - g) * 0.1F;
      b += (y - b) * 0.1F;
      c[0] = (r - 0.4F) * 1.2F + 0.4F;
      c[1] = (g - 0.4F) * 1.2F + 0.4F;
      c[2] = (b - 0.4F) * 1.2F + 0.4F;
   }

   /**
    * The fused table: for a world pixel already desaturated by the stock DesaturationVal, the stock tail then the look
    * then the .cube overlays, on a plain (unshaped) NF³ lattice, packed RGB10_A2 (GL_UNSIGNED_INT_2_10_10_10_REV, r
    * lowest). A plain lattice represents the affine stock tail exactly.
    */
   public static int[] bakeFusedPacked(Look look, Cube[] cubes, float[] cubeWeights) {
      Prepared p = new Prepared(look);
      int n = NF;
      int[] t = new int[n * n * n];
      float[] c = new float[3], o = new float[3], q = new float[3];
      int k = 0;
      for (int bz = 0; bz < n; bz++) {
         for (int gy = 0; gy < n; gy++) {
            for (int rx = 0; rx < n; rx++) {
               c[0] = rx / (float)(n - 1);
               c[1] = gy / (float)(n - 1);
               c[2] = bz / (float)(n - 1);
               stockTail(c);
               if (p.identity && cubes == null) {
                  o[0] = c[0]; // the stock values as they are (below 0 / above 1 too: the HDR expansion reads them)
                  o[1] = c[1];
                  o[2] = c[2];
               } else {
                  apply(p, Math.max(0F, Math.min(1F, c[0])), Math.max(0F, Math.min(1F, c[1])), Math.max(0F, Math.min(1F, c[2])), o);
                  overlay(cubes, cubeWeights, o, q);
               }
               for (int ch = 0; ch < 3; ch++) {
                  q[ch] = (o[ch] - FUSED_LO) / (FUSED_HI - FUSED_LO);
               }
               t[k++] = pack1010102(q);
            }
         }
      }
      return t;
   }

   private static void overlay(Cube[] cubes, float[] cubeWeights, float[] o, float[] c) {
      if (cubes == null) {
         return;
      }
      for (int q = 0; q < cubes.length; q++) {
         float w = cubeWeights[q];
         if (cubes[q] == null || w <= 0F) {
            continue;
         }
         cubes[q].sample(o[0], o[1], o[2], c);
         o[0] += (c[0] - o[0]) * w;
         o[1] += (c[1] - o[1]) * w;
         o[2] += (c[2] - o[2]) * w;
      }
   }

   static int pack1010102(float[] o) {
      int r = Math.round(Math.max(0F, Math.min(1F, o[0])) * 1023F);
      int g = Math.round(Math.max(0F, Math.min(1F, o[1])) * 1023F);
      int b = Math.round(Math.max(0F, Math.min(1F, o[2])) * 1023F);
      return 3 << 30 | b << 20 | g << 10 | r;
   }

   /** A float RGB table packed RGB10_A2 (the grade-only table's upload). */
   public static int[] pack(float[] t) {
      int[] out = new int[t.length / 3];
      float[] o = new float[3];
      for (int i = 0; i < out.length; i++) {
         o[0] = t[i * 3];
         o[1] = t[i * 3 + 1];
         o[2] = t[i * 3 + 2];
         out[i] = pack1010102(o);
      }
      return out;
   }

   /** Trilinear lookup of a packed fused table at a stock-desaturated pixel (tests). */
   public static void lookupFused(int[] packed, float r, float g, float b, float[] out) {
      int n = NF;
      float[] t = new float[packed.length * 3];
      float span = FUSED_HI - FUSED_LO;
      for (int i = 0; i < packed.length; i++) {
         t[i * 3] = (packed[i] & 1023) / 1023F * span + FUSED_LO;
         t[i * 3 + 1] = (packed[i] >> 10 & 1023) / 1023F * span + FUSED_LO;
         t[i * 3 + 2] = (packed[i] >> 20 & 1023) / 1023F * span + FUSED_LO;
      }
      trilinear(t, n, Math.max(0F, Math.min(1F, r)) * (n - 1), Math.max(0F, Math.min(1F, g)) * (n - 1), Math.max(0F, Math.min(1F, b)) * (n - 1), out);
   }

   /** Trilinear lookup of a baked table (the shader's fetch, for tests and offline checks). */
   public static void lookup(float[] table, float r, float g, float b, float[] out) {
      float fr = (float)Math.sqrt(Math.max(0F, Math.min(1F, r))) * (N - 1);
      float fg = (float)Math.sqrt(Math.max(0F, Math.min(1F, g))) * (N - 1);
      float fb = (float)Math.sqrt(Math.max(0F, Math.min(1F, b))) * (N - 1);
      trilinear(table, N, fr, fg, fb, out);
   }

   static void trilinear(float[] t, int n, float fr, float fg, float fb, float[] out) {
      int r0 = Math.min(n - 2, (int)fr), g0 = Math.min(n - 2, (int)fg), b0 = Math.min(n - 2, (int)fb);
      float dr = fr - r0, dg = fg - g0, db = fb - b0;
      for (int ch = 0; ch < 3; ch++) {
         float c000 = t[((b0 * n + g0) * n + r0) * 3 + ch], c100 = t[((b0 * n + g0) * n + r0 + 1) * 3 + ch];
         float c010 = t[((b0 * n + g0 + 1) * n + r0) * 3 + ch], c110 = t[((b0 * n + g0 + 1) * n + r0 + 1) * 3 + ch];
         float c001 = t[(((b0 + 1) * n + g0) * n + r0) * 3 + ch], c101 = t[(((b0 + 1) * n + g0) * n + r0 + 1) * 3 + ch];
         float c011 = t[(((b0 + 1) * n + g0 + 1) * n + r0) * 3 + ch], c111 = t[(((b0 + 1) * n + g0 + 1) * n + r0 + 1) * 3 + ch];
         float c00 = c000 + (c100 - c000) * dr, c10 = c010 + (c110 - c010) * dr, c01 = c001 + (c101 - c001) * dr, c11 = c011 + (c111 - c011) * dr;
         float c0 = c00 + (c10 - c00) * dg, c1 = c01 + (c11 - c01) * dg;
         out[ch] = c0 + (c1 - c0) * db;
      }
   }

   /** An Adobe / Resolve .cube 3D LUT (sRGB-encoded domain 0..1 unless DOMAIN_MIN / DOMAIN_MAX say otherwise). */
   public static final class Cube {
      final int n;
      final float[] t; // r fastest
      final float[] lo = {0F, 0F, 0F}, hi = {1F, 1F, 1F};
      final String name;

      Cube(String name, int n, float[] t) {
         this.name = name;
         this.n = n;
         this.t = t;
      }

      public void sample(float r, float g, float b, float[] out) {
         float fr = (Math.max(lo[0], Math.min(hi[0], r)) - lo[0]) / (hi[0] - lo[0]) * (n - 1);
         float fg = (Math.max(lo[1], Math.min(hi[1], g)) - lo[1]) / (hi[1] - lo[1]) * (n - 1);
         float fb = (Math.max(lo[2], Math.min(hi[2], b)) - lo[2]) / (hi[2] - lo[2]) * (n - 1);
         trilinear(t, n, fr, fg, fb, out);
      }

      public static Cube read(File f) throws IOException {
         int n = 0;
         float[] t = null;
         int k = 0;
         float[] lo = {0F, 0F, 0F}, hi = {1F, 1F, 1F};
         try (BufferedReader in = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = in.readLine()) != null) {
               line = line.trim();
               if (line.isEmpty() || line.startsWith("#") || line.startsWith("TITLE")) {
                  continue;
               }
               String[] p = line.split("\\s+");
               if (p[0].equals("LUT_3D_SIZE")) {
                  n = Integer.parseInt(p[1]);
                  if (n < 2 || n > 256) {
                     throw new IOException("LUT_3D_SIZE " + n);
                  }
                  t = new float[n * n * n * 3];
               } else if (p[0].equals("LUT_1D_SIZE")) {
                  throw new IOException("1D LUTs are not supported");
               } else if (p[0].equals("DOMAIN_MIN")) {
                  for (int i = 0; i < 3; i++) {
                     lo[i] = Float.parseFloat(p[i + 1]);
                  }
               } else if (p[0].equals("DOMAIN_MAX")) {
                  for (int i = 0; i < 3; i++) {
                     hi[i] = Float.parseFloat(p[i + 1]);
                  }
               } else if (t != null && p.length >= 3 && (Character.isDigit(p[0].charAt(0)) || p[0].charAt(0) == '-' || p[0].charAt(0) == '.')) {
                  if (k + 3 > t.length) {
                     throw new IOException("more entries than LUT_3D_SIZE^3");
                  }
                  t[k++] = Float.parseFloat(p[0]);
                  t[k++] = Float.parseFloat(p[1]);
                  t[k++] = Float.parseFloat(p[2]);
               }
            }
         }
         if (t == null || k != t.length) {
            throw new IOException("incomplete 3D LUT (" + k / 3 + " of " + (t == null ? 0 : t.length / 3) + " entries)");
         }
         Cube c = new Cube(f.getName(), n, t);
         System.arraycopy(lo, 0, c.lo, 0, 3);
         System.arraycopy(hi, 0, c.hi, 0, 3);
         return c;
      }
   }

   // ---- the darkness floor on one square's light (pzopt.Darkness) ----

   /**
    * Soft minimum of a light colour's luminance: Y' = (Y^4 + F^4)^(1/4), the missing luminance added in the floor's
    * tint (normalised to luminance 1). Lights well above the floor are left as they are (+0.3 % at Y = 2F).
    */
   public static void floorLight(float[] rgb, float floorY, float[] tint) {
      float y = LR * rgb[0] + LG * rgb[1] + LB * rgb[2];
      float y2 = y * y, f2 = floorY * floorY;
      float yf = (float)Math.sqrt(Math.sqrt(y2 * y2 + f2 * f2));
      float d = yf - y;
      if (d > 0F) {
         rgb[0] = Math.min(1F, rgb[0] + d * tint[0]);
         rgb[1] = Math.min(1F, rgb[1] + d * tint[1]);
         rgb[2] = Math.min(1F, rgb[2] + d * tint[2]);
      }
   }

   /** {@link #floorLight} on a packed ABGR vertex colour (the native's corner lights). */
   public static int floorAbgr(int abgr, float floorY, float[] tint, float[] scratch) {
      scratch[0] = (abgr & 0xFF) / 255F;
      scratch[1] = (abgr >> 8 & 0xFF) / 255F;
      scratch[2] = (abgr >> 16 & 0xFF) / 255F;
      floorLight(scratch, floorY, tint);
      int r = Math.round(scratch[0] * 255F), g = Math.round(scratch[1] * 255F), b = Math.round(scratch[2] * 255F);
      return abgr & 0xFF000000 | b << 16 | g << 8 | r;
   }
}
