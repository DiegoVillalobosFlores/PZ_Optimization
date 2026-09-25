package pzopt;

import static pzopt.Check.check;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * TexBc (texCompress's BC3 encoder): blocks decode close to the source, solid colours and binary alpha exactly, the
 * colour half is always in the 4-colour order (c0 > c1, or c0 == c1 with every index 0), both byte orders read the
 * same pixels, and parallel block-row slices write the same bytes as one pass.
 */
public class TexBcTest {
   public static void main(String[] args) {
      Random rnd = new Random(11);
      for (boolean hq : new boolean[] {false, true}) {
         // solid colours: exact within the 565 grid's best (optimal tables in HQ)
         for (int t = 0; t < 200; t++) {
            int r = rnd.nextInt(256), g = rnd.nextInt(256), b = rnd.nextInt(256), a = rnd.nextInt(256);
            byte[] px = new byte[16 * 4];
            for (int i = 0; i < 16; i++) {
               px[i * 4] = (byte) r; px[i * 4 + 1] = (byte) g; px[i * 4 + 2] = (byte) b; px[i * 4 + 3] = (byte) a;
            }
            byte[] out = roundTrip(px, 4, 4, hq, ByteOrder.LITTLE_ENDIAN);
            check((out[3] & 0xFF) == a, "solid alpha exact");
            int tol = hq ? 4 : 8;
            check(Math.abs((out[0] & 0xFF) - r) <= tol && Math.abs((out[1] & 0xFF) - g) <= tol / 2 + 2 && Math.abs((out[2] & 0xFF) - b) <= tol,
                  "solid colour close (hq=" + hq + ")");
         }
         // sprite-like content: binary alpha must stay exact
         byte[] img = new byte[64 * 64 * 4];
         for (int i = 0; i < 64 * 64; i++) {
            boolean in = ((i % 64) - 32) * ((i % 64) - 32) + ((i / 64) - 32) * ((i / 64) - 32) < 400;
            img[i * 4] = (byte) (i * 7);
            img[i * 4 + 1] = (byte) (i / 64 * 4);
            img[i * 4 + 2] = (byte) (255 - i % 64 * 4);
            img[i * 4 + 3] = (byte) (in ? 255 : 0);
         }
         byte[] dec = roundTrip(img, 64, 64, hq, ByteOrder.LITTLE_ENDIAN);
         for (int i = 0; i < 64 * 64; i++) {
            check(dec[i * 4 + 3] == img[i * 4 + 3], "binary alpha exact");
         }
         check(dec.length == img.length, "size");
         // byte order of the source buffer does not change the result
         byte[] be = roundTrip(img, 64, 64, hq, ByteOrder.BIG_ENDIAN);
         check(java.util.Arrays.equals(dec, be), "byte order independent");
         // odd sizes (mip tails): 1x1, 2x2, 3x5
         for (int[] wh : new int[][] {{1, 1}, {2, 2}, {3, 5}}) {
            byte[] s = new byte[wh[0] * wh[1] * 4];
            rnd.nextBytes(s);
            byte[] d = roundTrip(s, wh[0], wh[1], hq, ByteOrder.LITTLE_ENDIAN);
            check(d.length == s.length, "odd size decodes");
         }
      }
      // gradients: HQ error well under the fast fit's, both bounded
      byte[] grad = new byte[32 * 32 * 4];
      for (int i = 0; i < 32 * 32; i++) {
         int x = i % 32, y = i / 32;
         grad[i * 4] = (byte) (x * 8); grad[i * 4 + 1] = (byte) (y * 8); grad[i * 4 + 2] = (byte) ((x + y) * 4); grad[i * 4 + 3] = (byte) (x * 8);
      }
      double eFast = mse(grad, roundTrip(grad, 32, 32, false, ByteOrder.LITTLE_ENDIAN));
      double eHq = mse(grad, roundTrip(grad, 32, 32, true, ByteOrder.LITTLE_ENDIAN));
      check(eHq <= eFast, "hq no worse than fast on gradients (" + eHq + " vs " + eFast + ")");
      check(eHq < 40.0, "hq gradient error bounded (a 2-axis ramp is off a colour line) (" + eHq + ")");
      // 4-colour order and slices
      byte[] noise = new byte[128 * 128 * 4];
      rnd.nextBytes(noise);
      ByteBuffer src = ByteBuffer.allocateDirect(noise.length).put(noise).flip();
      int size = TexBc.bc3Size(128, 128);
      ByteBuffer one = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
      ByteBuffer parts = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
      TexBc.encodeBc3(src, 0, 128, 128, one, 0, 0, 32, true);
      for (int by = 0; by < 32; by += 5) {
         TexBc.encodeBc3(src, 0, 128, 128, parts, 0, by, Math.min(32, by + 5), true);
      }
      check(one.equals(parts), "slices = one pass");
      for (int blk = 0; blk < size / 16; blk++) {
         int c0 = one.getShort(blk * 16 + 8) & 0xFFFF, c1 = one.getShort(blk * 16 + 10) & 0xFFFF;
         int bits = one.getInt(blk * 16 + 12);
         check(c0 > c1 || (c0 == c1 && bits == 0), "4-colour order");
      }
      // the block Mesa's DXT5 won on the flip (0 0 0 26 | 6 147 249 255 | 255 x 8): its 6-level encoding with endpoints
      // 26 / 147 (6 -> 0, 249 -> 255) scores 72 under the formula; the wider search must find at least as good
      int[] mesa = {0, 0, 0, 26, 6, 147, 249, 255, 255, 255, 255, 255, 255, 255, 255, 255};
      int e = TexBc.alphaErr(mesa, TexBc.alphaHq(mesa));
      check(e <= 72, "wide alpha block error " + e + " <= Mesa's 72");
      // a decoder table filled from the formula must encode exactly like the formula
      byte[] alut = new byte[65536 * 8];
      for (int a0 = 0; a0 < 256; a0++) {
         for (int a1 = 0; a1 < 256; a1++) {
            for (int k = 0; k < 8; k++) {
               alut[((a0 << 8) | a1) * 8 + k] = (byte) TexBc.alphaValue(a0, a1, k);
            }
         }
      }
      short[] rl = new short[1024], gl = new short[4096], bl = new short[1024];
      for (int i0 = 0; i0 < 64; i0++) {
         for (int i1 = 0; i1 < 64; i1++) {
            gl[i0 * 64 + i1] = (short) TexBc.mid(null, 64, i0, i1, TexBc.ex6(i0), TexBc.ex6(i1));
            if (i0 < 32 && i1 < 32) {
               rl[i0 * 32 + i1] = (short) TexBc.mid(null, 32, i0, i1, TexBc.ex5(i0), TexBc.ex5(i1));
               bl[i0 * 32 + i1] = rl[i0 * 32 + i1];
            }
         }
      }
      ByteBuffer formula = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
      TexBc.encodeBc3(src, 0, 128, 128, formula, 0, 0, 32, true);
      TexBc.setDecoder(alut, rl, gl, bl);
      ByteBuffer tabled = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
      TexBc.encodeBc3(src, 0, 128, 128, tabled, 0, 0, 32, true);
      TexBc.setDecoder(null, null, null, null);
      // the tabled path searches indices exactly instead of by projection: equal error, not always equal bytes
      byte[] df = TexBc.decodeBc3(formula, 0, 128, 128), dt = TexBc.decodeBc3(tabled, 0, 128, 128);
      byte[] srcBytes = new byte[noise.length];
      src.get(0, srcBytes);
      double ef = mse(srcBytes, df), et = mse(srcBytes, dt);
      check(Math.abs(ef - et) < 0.5, "formula table encodes like the formula (" + ef + " vs " + et + ")");
      System.out.println("TexBcTest ok (gradient mse fast " + String.format("%.2f", eFast) + ", hq " + String.format("%.2f", eHq) + ")");
   }

   static byte[] roundTrip(byte[] px, int w, int h, boolean hq, ByteOrder order) {
      ByteBuffer src = ByteBuffer.allocateDirect(px.length).order(order);
      src.put(px).flip();
      ByteBuffer dst = ByteBuffer.allocateDirect(TexBc.bc3Size(w, h)).order(ByteOrder.LITTLE_ENDIAN);
      TexBc.encodeBc3(src, 0, w, h, dst, 0, 0, (h + 3) >> 2, hq);
      return TexBc.decodeBc3(dst, 0, w, h);
   }

   static double mse(byte[] a, byte[] b) {
      double s = 0;
      for (int i = 0; i < a.length; i++) {
         int d = (a[i] & 0xFF) - (b[i] & 0xFF);
         s += d * d;
      }
      return s / a.length;
   }
}
