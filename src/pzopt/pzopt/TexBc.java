package pzopt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * BC3 (DXT5) block encoder for textureCompression, so the driver never compresses: Mesa turns a GL_COMPRESSED_RGBA
 * upload into a CPU compression inside glTexImage2D on the render thread (the flip's menu at 30 fps for 15 s after
 * boot). Blocks are encoded here, on whatever thread holds the pixels, and handed to glCompressedTexImage2D.
 *
 * Two colour fits: FAST, the inset bounding box of van Waveren's "Real-Time DXT Compression" (2006); HQ, stb_dxt's
 * principal axis by power iteration, least-squares endpoint refinement and optimal single-colour tables. Alpha: FAST
 * the 8-level mode on the exact min/max; HQ adds least-squares endpoints and the 6-level mode (exact 0 and 255), lowest
 * error wins (a wider exhaustive endpoint search and a hill-climb gained under 0.1 dB and were dropped). Colour blocks are always written in the
 * 4-colour order (c0 > c1, or c0 == c1 with every index 0), which BC3 decoders read the same way on every GPU.
 *
 * No game classes: tools/TexCompProbe.java compiles against this file alone.
 */
public final class TexBc {
   public static final int GL_COMPRESSED_RGBA_S3TC_DXT5_EXT = 0x83F3;

   /** Optimal endpoints for a solid 8-bit value: [value*2] = high, [value*2+1] = low (stb_dxt's omatch tables). */
   static final int[] OMATCH5 = optTable(5), OMATCH6 = optTable(6);

   /**
    * Rounding of the alpha interpolants the encoder assumes: GPUs round them (texelFetch readback on NVIDIA scores the
    * rounded palette higher than the floored one); a tie-break only, both decode within one level.
    */
   static final int ALPHA_ROUND7 = 3, ALPHA_ROUND5 = 2;

   /**
    * The GPU's own decode (TexBcPalette.measure): alpha palette per endpoint pair ([(a0 << 8 | a1) * 8 + k]) and colour
    * entries 2 and 3 per channel pair ([c0 * n + c1] = e2 << 8 | e3); null = the textbook formula.
    */
   static volatile byte[] ALPHA_LUT;
   static volatile short[] R_LUT, G_LUT, B_LUT;

   public static void setDecoder(byte[] alpha, short[] r, short[] g, short[] b) {
      R_LUT = r;
      G_LUT = g;
      B_LUT = b;
      ALPHA_LUT = alpha;
   }

   public static boolean measuredDecoder() {
      return ALPHA_LUT != null;
   }

   /** Fingerprint of the decoder model (0 = the formula): cached blocks fitted to one GPU's palette are not reused on another. */
   public static int decoderId() {
      byte[] a = ALPHA_LUT;
      if (a == null) {
         return 0;
      }
      return java.util.Arrays.hashCode(a) * 31 + java.util.Arrays.hashCode(R_LUT) * 17 + java.util.Arrays.hashCode(G_LUT) * 7 + java.util.Arrays.hashCode(B_LUT);
   }

   /**
    * Alpha error above which HQ also tries every pair of the block's own values as endpoints, in both modes (the
    * driver's trick of mapping near-0 / near-255 texels onto the 6-level mode's exact 0 and 255 is one of them).
    */
   public static int ALPHA_SEARCH_ERR = Integer.getInteger("pzopt.texbc.alphaSearchErr", 64);
   /** The wide search also tries 8-level pairs (off: +4 ns/px on the CPU for 0.02 dB; the 6-level pairs carry the gain). */
   static final boolean SEARCH_8 = Boolean.getBoolean("pzopt.texbc.search8");

   private TexBc() {
   }

   public static int bc3Size(int w, int h) {
      return ((w + 3) >> 2) * ((h + 3) >> 2) * 16;
   }

   /**
    * Encodes the block rows [by0, by1) of an RGBA8 image of w x h (rows of w*4 bytes from srcOff) into BC3 blocks at
    * dstOff (row-major, 16 bytes a block). Absolute reads and writes only: several threads may encode disjoint block
    * rows of one image at once.
    */
   public static void encodeBc3(ByteBuffer src, int srcOff, int w, int h, ByteBuffer dst, int dstOff, int by0, int by1, boolean hq) {
      int bw = (w + 3) >> 2;
      boolean le = src.order() == ByteOrder.LITTLE_ENDIAN;
      int[] r = new int[16], g = new int[16], b = new int[16], a = new int[16], p = new int[16];
      long[] out = new long[2];
      // uniform blocks (a quarter to a third of a sprite page: transparent space, flat fills) repeat one value; the
      // last one's block is kept
      int uniV = 0;
      long uniA = 0L, uniC = 0L;
      boolean uniSet = false;
      for (int by = by0; by < by1; by++) {
         for (int bx = 0; bx < bw; bx++) {
            boolean uniform = true;
            for (int y = 0; y < 4; y++) {
               int sy = Math.min(by * 4 + y, h - 1);
               int row = srcOff + sy * w * 4;
               for (int x = 0; x < 4; x++) {
                  int sx = Math.min(bx * 4 + x, w - 1);
                  int v = src.getInt(row + sx * 4);
                  p[y * 4 + x] = v;
                  uniform &= v == p[0];
               }
            }
            int o = dstOff + (by * bw + bx) * 16;
            if (uniform && uniSet && p[0] == uniV) {
               putBlock(dst, o, uniA, uniC);
               continue;
            }
            for (int i = 0; i < 16; i++) {
               int v = p[i];
               if (le) {
                  r[i] = v & 0xFF; g[i] = (v >>> 8) & 0xFF; b[i] = (v >>> 16) & 0xFF; a[i] = v >>> 24;
               } else {
                  r[i] = v >>> 24; g[i] = (v >>> 16) & 0xFF; b[i] = (v >>> 8) & 0xFF; a[i] = v & 0xFF;
               }
            }
            encodeBlock(r, g, b, a, hq, out);
            if (uniform) {
               uniSet = true;
               uniV = p[0];
               uniA = out[0];
               uniC = out[1];
            }
            putBlock(dst, o, out[0], out[1]);
         }
      }
   }

   private static void putBlock(ByteBuffer dst, int o, long alpha, long color) {
      if (dst.order() == ByteOrder.LITTLE_ENDIAN) {
         dst.putLong(o, alpha);
         dst.putLong(o + 8, color);
      } else {
         dst.putLong(o, Long.reverseBytes(alpha));
         dst.putLong(o + 8, Long.reverseBytes(color));
      }
   }

   /** One block: out[0] = the alpha half, out[1] = the colour half, both as little-endian 64-bit words. */
   static void encodeBlock(int[] r, int[] g, int[] b, int[] a, boolean hq, long[] out) {
      out[0] = hq ? alphaHq(a) : alphaBlock(a);
      if (!hq) {
         out[1] = colorFast(r, g, b);
         return;
      }
      // adaptive: the bounding-box fit first; the principal-axis fit only where that one misses by more than
      // HQ_THRESHOLD squared error a pixel (most sprite blocks are flat, two-tone or on a line)
      long fast = colorFast(r, g, b);
      int fe = colorErr(r, g, b, fast);
      if (fe <= HQ_THRESHOLD * 16) {
         out[1] = fast;
         return;
      }
      long q = colorHq(r, g, b);
      out[1] = colorErr(r, g, b, q) <= fe ? q : fast;
   }

   /**
    * Squared RGB error per pixel above which a block gets the HQ colour fit (0 = every block). 16 keeps the HQ quality
    * within 0.03 dB on the game's own pages at 70 % of its cost (the bounding-box fit is exact on flat and two-tone
    * blocks, most of a sprite page).
    */
   public static int HQ_THRESHOLD = Integer.getInteger("pzopt.texbc.hqThreshold", 16);

   /** Squared RGB error of a colour block word against the pixels. */
   static int colorErr(int[] r, int[] g, int[] b, long blk) {
      int c0 = (int) (blk & 0xFFFF), c1 = (int) ((blk >>> 16) & 0xFFFF);
      int bits = (int) (blk >>> 32);
      int[] pr = new int[4], pg = new int[4], pb = new int[4];
      palette(c0, c1, pr, pg, pb);
      int err = 0;
      for (int i = 0; i < 16; i++) {
         int k = (bits >>> (2 * i)) & 3;
         err += sq(r[i] - pr[k], g[i] - pg[k], b[i] - pb[k]);
      }
      return err;
   }

   // --- alpha: 8-level mode, a0 = max > a1 = min ---------------------------------------------------------------

   /** Squared error of an alpha block word against the pixels (decoder's palette). */
   static int alphaErr(int[] a, long blk) {
      int a0 = (int) (blk & 0xFF), a1 = (int) ((blk >>> 8) & 0xFF);
      int p0 = alphaValue(a0, a1, 0), p1 = alphaValue(a0, a1, 1), p2 = alphaValue(a0, a1, 2), p3 = alphaValue(a0, a1, 3);
      int p4 = alphaValue(a0, a1, 4), p5 = alphaValue(a0, a1, 5), p6 = alphaValue(a0, a1, 6), p7 = alphaValue(a0, a1, 7);
      int err = 0;
      for (int i = 0; i < 16; i++) {
         int k = (int) ((blk >>> (16 + 3 * i)) & 7);
         int v = k == 0 ? p0 : k == 1 ? p1 : k == 2 ? p2 : k == 3 ? p3 : k == 4 ? p4 : k == 5 ? p5 : k == 6 ? p6 : p7;
         int d = a[i] - v;
         err += d * d;
      }
      return err;
   }

   static int alphaValue(int a0, int a1, int k) {
      byte[] lut = ALPHA_LUT;
      if (lut != null) {
         return lut[((a0 << 8) | a1) * 8 + k] & 0xFF;
      }
      if (k == 0) {
         return a0;
      }
      if (k == 1) {
         return a1;
      }
      if (a0 > a1) {
         return ((8 - k) * a0 + (k - 1) * a1 + ALPHA_ROUND7) / 7;
      }
      if (k == 6) {
         return 0;
      }
      if (k == 7) {
         return 255;
      }
      return ((6 - k) * a0 + (k - 1) * a1 + ALPHA_ROUND5) / 5;
   }

   /** Nearest-index block for fixed endpoints (either mode); the 8-level mode by ramp position, +-1 checked. */
   static long alphaFit(int[] a, int a0, int a1) {
      if (a0 <= a1 || !PROJ) {
         return alphaFitSearch(a, a0, a1);
      }
      int range = a0 - a1;
      long bits = 0L;
      for (int i = 0; i < 16; i++) {
         int v = a[i];
         int s = v >= a0 ? 0 : v <= a1 ? 7 : ((a0 - v) * 14 + range) / (2 * range); // ramp slot 0..7 from a0
         int best = s, bd = Math.abs(v - rampValue(a0, a1, s));
         if (s > 0) {
            int d = Math.abs(v - rampValue(a0, a1, s - 1));
            if (d < bd) {
               bd = d;
               best = s - 1;
            }
         }
         if (s < 7) {
            int d = Math.abs(v - rampValue(a0, a1, s + 1));
            if (d < bd) {
               best = s + 1;
            }
         }
         int idx = best == 0 ? 0 : best == 7 ? 1 : best + 1;
         bits |= (long) idx << (3 * i);
      }
      return a0 | (long) a1 << 8 | bits << 16;
   }

   /** Value of ramp slot s (0 = a0 .. 7 = a1) in the 8-level mode. */
   static int rampValue(int a0, int a1, int s) {
      return alphaValue(a0, a1, s == 0 ? 0 : s == 7 ? 1 : s + 1);
   }

   static long alphaFitSearch(int[] a, int a0, int a1) {
      long bits = 0L;
      int[] pal = new int[8];
      for (int k = 0; k < 8; k++) {
         pal[k] = alphaValue(a0, a1, k);
      }
      for (int i = 0; i < 16; i++) {
         int best = 0, bd = Integer.MAX_VALUE;
         for (int k = 0; k < 8; k++) {
            int d = Math.abs(a[i] - pal[k]);
            if (d < bd) {
               bd = d;
               best = k;
            }
         }
         bits |= (long) best << (3 * i);
      }
      return a0 | (long) a1 << 8 | bits << 16;
   }

   /**
    * HQ alpha: the exact min/max 8-level block, then least-squares endpoints for its ramp positions (twice), and the
    * 6-level mode (exact 0 and 255 in the palette) around the other values; the lowest error wins.
    */
   static long alphaHq(int[] a) {
      long bin = alphaBinary(a);
      if (bin != -1L) {
         return bin;
      }
      long best = alphaBlock(a);
      int mn = (int) ((best >>> 8) & 0xFF), mx = (int) (best & 0xFF);
      if (mn == mx) {
         return best;
      }
      int bestErr = alphaErr(a, best);
      long cur = best;
      for (int pass = 0; pass < 2 && bestErr > 0; pass++) {
         // ramp position s/7 from a0 per pixel: value = ((7-s)*a0 + s*a1)/7
         long saa = 0, sab = 0, sbb = 0, sac = 0, sbc = 0;
         for (int i = 0; i < 16; i++) {
            int k = (int) ((cur >>> (16 + 3 * i)) & 7);
            int s = k == 0 ? 0 : k == 1 ? 7 : k - 1;
            int w0 = 7 - s, w1 = s;
            saa += w0 * w0; sab += w0 * w1; sbb += w1 * w1;
            sac += w0 * a[i] * 7L; sbc += w1 * a[i] * 7L;
         }
         double det = (double) saa * sbb - (double) sab * sab;
         if (Math.abs(det) < 1e-9) {
            break;
         }
         int n0 = clamp8((int) Math.round((sac * sbb - sbc * sab) / det));
         int n1 = clamp8((int) Math.round((sbc * saa - sac * sab) / det));
         if (n0 <= n1) {
            break;
         }
         cur = alphaFit(a, n0, n1);
         int e = alphaErr(a, cur);
         if (e >= bestErr) {
            break;
         }
         best = cur;
         bestErr = e;
      }
      // 6-level mode: a0 <= a1 spans the values strictly inside (0, 255); 0 and 255 come exact
      int imn = 255, imx = 0;
      for (int i = 0; i < 16; i++) {
         if (a[i] > 0 && a[i] < 255) {
            imn = Math.min(imn, a[i]);
            imx = Math.max(imx, a[i]);
         }
      }
      if (imn <= imx) {
         long six = alphaFit(a, imn, imx);
         int e = alphaErr(a, six);
         if (e < bestErr) {
            best = six;
            bestErr = e;
         }
      } else if (bestErr > 0) {
         long six = alphaFit(a, 0, 255); // only 0 and 255 in the block, but the 8-level ramp missed one
         if (alphaErr(a, six) < bestErr) {
            best = six;
         }
      }
      if (bestErr > ALPHA_SEARCH_ERR) {
         // every pair of the block's own values as endpoints, scored on the block's histogram of distinct values with
         // an early exit; only the winner gets its indices
         int[] v = new int[16], cnt = new int[16];
         int nv = 0;
         for (int i = 0; i < 16; i++) {
            int j = 0;
            while (j < nv && v[j] != a[i]) {
               j++;
            }
            if (j == nv) {
               v[nv++] = a[i];
            }
            cnt[j]++;
         }
         int[] pal = new int[8];
         int bestLo = -1, bestHi = -1;
         for (int i = 0; i < nv; i++) {
            for (int j = 0; j < nv; j++) {
               int lo = v[i], hi = v[j];
               if (lo >= hi) {
                  continue;
               }
               for (int mode = SEARCH_8 ? 0 : 1; mode < 2; mode++) {
                  int a0 = mode == 0 ? hi : lo, a1 = mode == 0 ? lo : hi; // 8-level a0 > a1, 6-level a0 < a1
                  for (int k = 0; k < 8; k++) {
                     pal[k] = alphaValue(a0, a1, k);
                  }
                  int e = 0;
                  for (int q = 0; q < nv && e < bestErr; q++) {
                     int bd = Integer.MAX_VALUE;
                     for (int k = 0; k < 8; k++) {
                        int d = v[q] - pal[k];
                        d *= d;
                        if (d < bd) {
                           bd = d;
                        }
                     }
                     e += bd * cnt[q];
                  }
                  if (e < bestErr) {
                     bestErr = e;
                     bestLo = a0;
                     bestHi = a1;
                  }
               }
            }
         }
         if (bestLo >= 0) {
            best = alphaFitSearch(a, bestLo, bestHi);
         }
      }
      return best;
   }

   static int clamp8(int v) {
      return v < 0 ? 0 : Math.min(v, 255);
   }

   /**
    * Blocks whose alpha is only 0 and 255 (two thirds to nine tenths of a sprite page's blocks): the exact 8-level block
    * (a0 = 255 -> index 0, a1 = 0 -> index 1) without a division; -1 for any other block.
    */
   static long alphaBinary(int[] a) {
      long bits = 0L;
      boolean zero = false, full = false;
      for (int i = 0; i < 16; i++) {
         int v = a[i];
         if (v == 0) {
            zero = true;
            bits |= 1L << (3 * i);
         } else if (v == 255) {
            full = true;
         } else {
            return -1L;
         }
      }
      if (!zero) {
         return 255L | 255L << 8;
      }
      if (!full) {
         return 0L;
      }
      return 255L | bits << 16;
   }

   static long alphaBlock(int[] a) {
      int mn = 255, mx = 0;
      for (int i = 0; i < 16; i++) {
         mn = Math.min(mn, a[i]);
         mx = Math.max(mx, a[i]);
      }
      if (mn == mx) {
         return mx | (long) mx << 8; // a0 == a1: every index 0 decodes to a0
      }
      long bits = 0L;
      int range = mx - mn;
      for (int i = 0; i < 16; i++) {
         // position on the 0..7 ramp from max (0/7) to min (7/7), rounded; ramp slot -> BC3 index
         int t = ((mx - a[i]) * 14 + range) / (2 * range);
         int idx = t == 0 ? 0 : t == 7 ? 1 : t + 1;
         bits |= (long) idx << (3 * i);
      }
      return mx | (long) mn << 8 | bits << 16;
   }

   // --- colour --------------------------------------------------------------------------------------------------

   static int to565(int r, int g, int b) {
      return (mul8(r, 31) << 11) | (mul8(g, 63) << 5) | mul8(b, 31);
   }

   /** a*b/255 rounded (stb_dxt's mul8bit). */
   static int mul8(int a, int b) {
      int t = a * b + 128;
      return (t + (t >> 8)) >> 8;
   }

   static int ex5(int v) {
      return (v << 3) | (v >> 2);
   }

   static int ex6(int v) {
      return (v << 2) | (v >> 4);
   }

   /** Indices for the palette of c0/c1 (4-colour order), nearest in RGB. */
   /** Index selection by projection on the endpoint axis (stb_dxt): one dot product a pixel instead of 4 distances. */
   static final boolean PROJ = !Boolean.getBoolean("pzopt.texbc.exactIndices");

   static int indices(int[] r, int[] g, int[] b, int c0, int c1) {
      if (PROJ && R_LUT == null) {
         return indicesProj(r, g, b, c0, c1);
      }
      return indicesExact(r, g, b, c0, c1);
   }

   static int indicesProj(int[] r, int[] g, int[] b, int c0, int c1) {
      int r0 = ex5(c0 >> 11), g0 = ex6((c0 >> 5) & 63), b0 = ex5(c0 & 31);
      int r1 = ex5(c1 >> 11), g1 = ex6((c1 >> 5) & 63), b1 = ex5(c1 & 31);
      int dr = r0 - r1, dg = g0 - g1, db = b0 - b1;
      int s0 = r0 * dr + g0 * dg + b0 * db, s1 = r1 * dr + g1 * dg + b1 * db;
      int s2 = ((2 * r0 + r1) / 3) * dr + ((2 * g0 + g1) / 3) * dg + ((2 * b0 + b1) / 3) * db;
      int s3 = ((r0 + 2 * r1) / 3) * dr + ((g0 + 2 * g1) / 3) * dg + ((b0 + 2 * b1) / 3) * db;
      // along c1 -> c0: index 1, 3, 2, 0; thresholds at the midpoints (doubled)
      int t13 = s1 + s3, t32 = s3 + s2, t20 = s2 + s0;
      int bits = 0;
      for (int i = 0; i < 16; i++) {
         int d = 2 * (r[i] * dr + g[i] * dg + b[i] * db);
         int k = d < t13 ? 1 : d < t32 ? 3 : d < t20 ? 2 : 0;
         bits |= k << (2 * i);
      }
      return bits;
   }

   /** Palette entries 2 (<< 8) and 3 of one channel pair: the measured decode, or the formula. */
   static int mid(short[] lut, int n, int i0, int i1, int e0, int e1) {
      if (lut != null) {
         return lut[i0 * n + i1] & 0xFFFF;
      }
      return ((2 * e0 + e1) / 3) << 8 | ((e0 + 2 * e1) / 3);
   }

   static int indicesExact(int[] r, int[] g, int[] b, int c0, int c1) {
      int r0 = ex5(c0 >> 11), g0 = ex6((c0 >> 5) & 63), b0 = ex5(c0 & 31);
      int r1 = ex5(c1 >> 11), g1 = ex6((c1 >> 5) & 63), b1 = ex5(c1 & 31);
      int mr = mid(R_LUT, 32, c0 >> 11, c1 >> 11, r0, r1), mg = mid(G_LUT, 64, (c0 >> 5) & 63, (c1 >> 5) & 63, g0, g1), mb = mid(B_LUT, 32, c0 & 31, c1 & 31, b0, b1);
      int r2 = mr >> 8, g2 = mg >> 8, b2 = mb >> 8;
      int r3 = mr & 0xFF, g3 = mg & 0xFF, b3 = mb & 0xFF;
      int bits = 0;
      for (int i = 0; i < 16; i++) {
         int e0 = sq(r[i] - r0, g[i] - g0, b[i] - b0), e1 = sq(r[i] - r1, g[i] - g1, b[i] - b1);
         int e2 = sq(r[i] - r2, g[i] - g2, b[i] - b2), e3 = sq(r[i] - r3, g[i] - g3, b[i] - b3);
         int k = 0, be = e0;
         if (e1 < be) {
            be = e1;
            k = 1;
         }
         if (e2 < be) {
            be = e2;
            k = 2;
         }
         if (e3 < be) {
            k = 3;
         }
         bits |= k << (2 * i);
      }
      return bits;
   }

   static int sq(int dr, int dg, int db) {
      return dr * dr + dg * dg + db * db;
   }

   static void palette(int c0, int c1, int[] pr, int[] pg, int[] pb) {
      pr[0] = ex5(c0 >> 11); pg[0] = ex6((c0 >> 5) & 63); pb[0] = ex5(c0 & 31);
      pr[1] = ex5(c1 >> 11); pg[1] = ex6((c1 >> 5) & 63); pb[1] = ex5(c1 & 31);
      int mr = mid(R_LUT, 32, c0 >> 11, c1 >> 11, pr[0], pr[1]), mg = mid(G_LUT, 64, (c0 >> 5) & 63, (c1 >> 5) & 63, pg[0], pg[1]);
      int mb = mid(B_LUT, 32, c0 & 31, c1 & 31, pb[0], pb[1]);
      pr[2] = mr >> 8; pg[2] = mg >> 8; pb[2] = mb >> 8;
      pr[3] = mr & 0xFF; pg[3] = mg & 0xFF; pb[3] = mb & 0xFF;
   }

   /** Packs c0/c1/indices, forcing the 4-colour order: swap to c0 > c1 (index k -> k^1), all-0 indices if equal. */
   static long pack(int c0, int c1, int bits) {
      if (c0 < c1) {
         int t = c0; c0 = c1; c1 = t;
         bits ^= 0x55555555;
      } else if (c0 == c1) {
         bits = 0;
      }
      return (c0 & 0xFFFFL) | (long) (c1 & 0xFFFF) << 16 | (bits & 0xFFFFFFFFL) << 32;
   }

   /** van Waveren: bounding box inset by 1/16 of its extent, max -> c0, min -> c1. */
   static long colorFast(int[] r, int[] g, int[] b) {
      int r0 = 255, g0 = 255, b0 = 255, r1 = 0, g1 = 0, b1 = 0;
      for (int i = 0; i < 16; i++) {
         r0 = Math.min(r0, r[i]); g0 = Math.min(g0, g[i]); b0 = Math.min(b0, b[i]);
         r1 = Math.max(r1, r[i]); g1 = Math.max(g1, g[i]); b1 = Math.max(b1, b[i]);
      }
      int ir = (r1 - r0) >> 4, ig = (g1 - g0) >> 4, ib = (b1 - b0) >> 4;
      int c0 = to565(r1 - ir, g1 - ig, b1 - ib);
      int c1 = to565(r0 + ir, g0 + ig, b0 + ib);
      return pack(c0, c1, c0 == c1 ? 0 : indices(r, g, b, c0, c1));
   }

   /** stb_dxt: single-colour tables, principal axis endpoints, two least-squares refinements. */
   static long colorHq(int[] r, int[] g, int[] b) {
      boolean solid = true;
      for (int i = 1; i < 16 && solid; i++) {
         solid = r[i] == r[0] && g[i] == g[0] && b[i] == b[0];
      }
      if (solid) {
         int c0 = (OMATCH5[r[0] * 2] << 11) | (OMATCH6[g[0] * 2] << 5) | OMATCH5[b[0] * 2];
         int c1 = (OMATCH5[r[0] * 2 + 1] << 11) | (OMATCH6[g[0] * 2 + 1] << 5) | OMATCH5[b[0] * 2 + 1];
         return pack(c0, c1, c0 == c1 ? 0 : 0xAAAAAAAA);
      }
      // mean and covariance
      int mr = 0, mg = 0, mb = 0;
      int nr = 255, ng = 255, nb = 255, xr = 0, xg = 0, xb = 0;
      for (int i = 0; i < 16; i++) {
         mr += r[i]; mg += g[i]; mb += b[i];
         nr = Math.min(nr, r[i]); ng = Math.min(ng, g[i]); nb = Math.min(nb, b[i]);
         xr = Math.max(xr, r[i]); xg = Math.max(xg, g[i]); xb = Math.max(xb, b[i]);
      }
      mr = (mr + 8) >> 4; mg = (mg + 8) >> 4; mb = (mb + 8) >> 4;
      float c00 = 0, c01 = 0, c02 = 0, c11 = 0, c12 = 0, c22 = 0;
      for (int i = 0; i < 16; i++) {
         int dr = r[i] - mr, dg = g[i] - mg, db = b[i] - mb;
         c00 += dr * dr; c01 += dr * dg; c02 += dr * db;
         c11 += dg * dg; c12 += dg * db; c22 += db * db;
      }
      float vr = xr - nr, vg = xg - ng, vb = xb - nb;
      for (int it = 0; it < 4; it++) {
         float tr = vr * c00 + vg * c01 + vb * c02;
         float tg = vr * c01 + vg * c11 + vb * c12;
         float tb = vr * c02 + vg * c12 + vb * c22;
         vr = tr; vg = tg; vb = tb;
         float m = Math.max(Math.abs(vr), Math.max(Math.abs(vg), Math.abs(vb)));
         if (m > 0f) {
            vr /= m; vg /= m; vb /= m;
         }
      }
      if (Math.abs(vr) + Math.abs(vg) + Math.abs(vb) < 1e-6f) {
         vr = 0.299f; vg = 0.587f; vb = 0.114f;
      }
      int mini = 0, maxi = 0;
      float mind = Float.MAX_VALUE, maxd = -Float.MAX_VALUE;
      for (int i = 0; i < 16; i++) {
         float d = r[i] * vr + g[i] * vg + b[i] * vb;
         if (d < mind) {
            mind = d;
            mini = i;
         }
         if (d > maxd) {
            maxd = d;
            maxi = i;
         }
      }
      int c0 = to565(r[maxi], g[maxi], b[maxi]);
      int c1 = to565(r[mini], g[mini], b[mini]);
      int bits = indices(r, g, b, c0, c1);
      for (int pass = 0; pass < 2; pass++) {
         long e = refine(r, g, b, bits);
         if (e < 0) {
            break;
         }
         int n0 = (int) (e >>> 16), n1 = (int) (e & 0xFFFF);
         if (n0 == c0 && n1 == c1) {
            break;
         }
         c0 = n0;
         c1 = n1;
         bits = indices(r, g, b, c0, c1);
      }
      return pack(c0, c1, bits);
   }

   /** Least-squares endpoints for the given indices (stb_dxt RefineBlock); -1 when the system is singular. */
   static long refine(int[] r, int[] g, int[] b, int bits) {
      // weights of c0 per index: 1, 0, 2/3, 1/3 (in thirds: 3, 0, 2, 1)
      int aa = 0, ab = 0, bb = 0;
      int ar = 0, ag = 0, ab_ = 0, br = 0, bg = 0, bbl = 0;
      for (int i = 0; i < 16; i++) {
         int k = (bits >>> (2 * i)) & 3;
         int w0 = k == 0 ? 3 : k == 1 ? 0 : k == 2 ? 2 : 1;
         int w1 = 3 - w0;
         aa += w0 * w0; ab += w0 * w1; bb += w1 * w1;
         ar += w0 * r[i]; ag += w0 * g[i]; ab_ += w0 * b[i];
         br += w1 * r[i]; bg += w1 * g[i]; bbl += w1 * b[i];
      }
      float det = (float) aa * bb - (float) ab * ab;
      if (Math.abs(det) < 1e-6f) {
         return -1L;
      }
      // solve [aa ab; ab bb] [e0; e1] = 3 * [a.c; b.c]
      float f = 3f / det;
      float r0 = (ar * bb - br * ab) * f, g0 = (ag * bb - bg * ab) * f, b0 = (ab_ * bb - bbl * ab) * f;
      float r1 = (br * aa - ar * ab) * f, g1 = (bg * aa - ag * ab) * f, b1 = (bbl * aa - ab_ * ab) * f;
      int c0 = (q(r0, 31) << 11) | (q(g0, 63) << 5) | q(b0, 31);
      int c1 = (q(r1, 31) << 11) | (q(g1, 63) << 5) | q(b1, 31);
      return (long) c0 << 16 | c1;
   }

   static int q(float v, int max) {
      int x = Math.round(v * max / 255f);
      return x < 0 ? 0 : Math.min(x, max);
   }

   static int[] optTable(int bits) {
      int size = 1 << bits;
      int[] t = new int[512];
      for (int i = 0; i < 256; i++) {
         int bestErr = Integer.MAX_VALUE;
         for (int mn = 0; mn < size; mn++) {
            for (int mx = 0; mx < size; mx++) {
               int emx = bits == 5 ? ex5(mx) : ex6(mx);
               int emn = bits == 5 ? ex5(mn) : ex6(mn);
               int err = Math.abs((2 * emx + emn) / 3 - i) * 100 + Math.abs(emx - emn) * 3;
               if (err < bestErr) {
                  bestErr = err;
                  t[i * 2] = mx;
                  t[i * 2 + 1] = mn;
               }
            }
         }
      }
      return t;
   }

   // --- decode (tests and the probe's quality numbers) ----------------------------------------------------------

   /** Decodes one BC3 image back to RGBA8 (row-major, w*h*4 bytes). */
   public static byte[] decodeBc3(ByteBuffer src, int off, int w, int h) {
      byte[] out = new byte[w * h * 4];
      int bw = (w + 3) >> 2, bh = (h + 3) >> 2;
      int[] pr = new int[4], pg = new int[4], pb = new int[4], pa = new int[8];
      for (int by = 0; by < bh; by++) {
         for (int bx = 0; bx < bw; bx++) {
            int o = off + (by * bw + bx) * 16;
            long al = 0, co = 0;
            for (int k = 7; k >= 0; k--) {
               al = al << 8 | (src.get(o + k) & 0xFF);
               co = co << 8 | (src.get(o + 8 + k) & 0xFF);
            }
            int a0 = (int) (al & 0xFF), a1 = (int) ((al >>> 8) & 0xFF);
            for (int k = 0; k < 8; k++) {
               pa[k] = alphaValue(a0, a1, k);
            }
            int c0 = (int) (co & 0xFFFF), c1 = (int) ((co >>> 16) & 0xFFFF);
            palette(c0, c1, pr, pg, pb);
            for (int i = 0; i < 16; i++) {
               int x = bx * 4 + (i & 3), y = by * 4 + (i >> 2);
               if (x >= w || y >= h) {
                  continue;
               }
               int ci = (int) ((co >>> (32 + 2 * i)) & 3);
               int ai = (int) ((al >>> (16 + 3 * i)) & 7);
               int p = (y * w + x) * 4;
               out[p] = (byte) pr[ci];
               out[p + 1] = (byte) pg[ci];
               out[p + 2] = (byte) pb[ci];
               out[p + 3] = (byte) pa[ai];
            }
         }
      }
      return out;
   }
}
