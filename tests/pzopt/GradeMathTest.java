package pzopt;

import java.io.File;
import java.nio.file.Files;

/** Colour grading and darkness-floor math (pzopt.GradeMath, pzopt.Grade weights); no GL. */
public final class GradeMathTest {
   public static void main(String[] args) throws Exception {
      float[] o = new float[3];

      // white balance: mired 0 / tint 0 is the identity matrix
      float[] wb = GradeMath.whiteBalance(0F, 0F);
      for (int i = 0; i < 9; i++) {
         Check.check(Math.abs(wb[i] - (i % 4 == 0 ? 1F : 0F)) < 1e-3F, "white balance identity at mired 0 (" + i + ": " + wb[i] + ")");
      }
      // cooler filter (negative mired) makes white bluer, luminance kept
      float[] cool = GradeMath.whiteBalance(-30F, 0F);
      float wr = cool[0] + cool[1] + cool[2], wbl = cool[6] + cool[7] + cool[8];
      Check.check(wbl > wr, "negative mired = bluer white (r " + wr + " b " + wbl + ")");

      // the neutral look bakes the identity; the shader's shaper lookup returns the input within half an 8-bit step
      float[] id = GradeMath.bake(GradeMath.neutral(), null, null);
      float maxErr = 0F;
      for (int r = 0; r <= 255; r += 5) {
         for (int g = 0; g <= 255; g += 17) {
            for (int b = 0; b <= 255; b += 51) {
               GradeMath.lookup(id, r / 255F, g / 255F, b / 255F, o);
               maxErr = Math.max(maxErr, Math.max(Math.abs(o[0] - r / 255F), Math.max(Math.abs(o[1] - g / 255F), Math.abs(o[2] - b / 255F))));
            }
         }
      }
      Check.check(maxErr < 0.5F / 255F, "identity LUT round trip (max error " + maxErr * 255F + " steps)");

      // every default look keeps black black (the fog of war is never lifted) and stays in range
      GradeMath.Look[] looks = Grade.defaultLooks();
      for (int i = 0; i < looks.length; i++) {
         float[] t = GradeMath.bake(looks[i], null, null);
         Check.check(t[0] < 1e-4F && t[1] < 1e-4F && t[2] < 1e-4F, Grade.CONDITIONS[i] + ": black stays black");
         for (float v : t) {
            Check.check(v >= 0F && v <= 1F, Grade.CONDITIONS[i] + ": output in 0..1");
         }
      }

      // night: a dark saturated red loses most of its saturation and turns bluer; a bright lamp colour keeps its hue
      GradeMath.Look night = looks[0];
      float[] tn = GradeMath.bake(night, null, null);
      GradeMath.lookup(tn, 0.20F, 0.05F, 0.05F, o);
      Check.check(o[2] / o[0] > 0.05F / 0.20F * 2F, "night: dark red shifts towards the rods' blue grey (" + o[0] + "," + o[1] + "," + o[2] + ")");
      GradeMath.lookup(tn, 1.0F, 0.6F, 0.2F, o);
      Check.check(o[0] > o[2] * 2F, "night: a bright lamp keeps its warm hue (" + o[0] + "," + o[1] + "," + o[2] + ")");

      // blend: zero weights = neutral; weights compose
      GradeMath.Look none = GradeMath.blend(looks, new float[looks.length], 1F);
      Check.check(new GradeMath.Prepared(none).identity, "zero weights blend to the neutral look");
      float[] w = new float[looks.length];
      w[4] = 1F;
      w[3] = 1F;
      GradeMath.Look both = GradeMath.blend(looks, w, 1F);
      Check.check(Math.abs(both.saturation - looks[3].saturation * looks[4].saturation) < 1e-4F, "saturations multiply");

      // weights: midnight = night, noon clear = nothing, the hour before dusk = golden hour
      float[] ww = new float[Grade.CONDITIONS.length];
      Grade.computeWeights(0F, 6F, 20F, 1F, 0F, 0F, 0F, 0F, 0F, ww);
      Check.check(ww[0] == 1F && ww[1] == 0F && ww[2] == 0F, "midnight: night only");
      Grade.computeWeights(12F, 6F, 20F, 0F, 0F, 0F, 0F, 0F, 0F, ww);
      for (float v : ww) {
         Check.check(v == 0F, "clear noon: neutral");
      }
      Grade.computeWeights(19.4F, 6F, 20F, 0F, 0F, 0F, 0F, 0F, 0F, ww);
      Check.check(ww[2] > 0.9F, "golden hour before dusk (" + ww[2] + ")");

      // darkness floor: black lifts to the floor's luminance in its tint, bright light untouched, monotonic
      float[] tint = Darkness.parseTint("0.92,0.98,1.12");
      float[] c = {0F, 0F, 0F};
      GradeMath.floorLight(c, 0.1F, tint);
      float y = GradeMath.LR * c[0] + GradeMath.LG * c[1] + GradeMath.LB * c[2];
      Check.check(Math.abs(y - 0.1F) < 1e-4F && c[2] > c[0], "floor: black -> the floor, cool (" + y + ")");
      c = new float[] {0.5F, 0.4F, 0.3F};
      GradeMath.floorLight(c, 0.1F, tint);
      Check.check(Math.abs(c[0] - 0.5F) < 1e-3F, "floor: a lit square keeps its light (" + c[0] + ")");
      float prev = -1F;
      for (int i = 0; i <= 100; i++) {
         float[] g = {i / 100F * 0.3F, i / 100F * 0.3F, i / 100F * 0.3F};
         GradeMath.floorLight(g, 0.1F, tint);
         float yy = GradeMath.LR * g[0] + GradeMath.LG * g[1] + GradeMath.LB * g[2];
         Check.check(yy >= prev - 1e-6F, "floor: monotonic");
         prev = yy;
      }
      int abgr = GradeMath.floorAbgr(0xFF000000, 0.1F, tint, new float[3]);
      Check.check((abgr >>> 24) == 0xFF && (abgr & 0xFF) > 20 && (abgr >> 16 & 0xFF) > (abgr & 0xFF), "floor on packed ABGR: alpha kept, lifted, blue > red");
      Check.check(GradeMath.floorAbgr(0xFFC8C8C8, 0.1F, tint, new float[3]) == 0xFFC8C8C8, "floor on a bright packed light: unchanged");

      // settings: unseen squares and basements are never floored; remembered squares take the memory light
      Darkness.Settings s = new Darkness.Settings(0.05F, 0.10F, false, tint);
      Check.check(s.floorFor((byte)0, 0, 0F) == 0F, "unseen: no floor");
      Check.check(s.floorFor((byte)1, -1, 1F) == 0F, "basement: no floor");
      Check.check(s.floorFor((byte)7, 0, 1F) == 0.05F, "seen, lit: the floor");
      Check.check(s.floorFor((byte)1, 0, 0F) == 0.10F, "seen, fading out: the memory light");
      Check.check(s.minDark((byte)1, 0) == 0.5F && s.minDark((byte)0, 0) == 0F, "fade held for seen squares only");

      // .cube: an identity cube reads back as the identity; overlay weights blend
      File f = File.createTempFile("pzopt-grade", ".cube");
      StringBuilder sb = new StringBuilder("TITLE \"t\"\nLUT_3D_SIZE 2\n");
      for (int b = 0; b < 2; b++) {
         for (int g = 0; g < 2; g++) {
            for (int r = 0; r < 2; r++) {
               sb.append(1 - r).append(' ').append(1 - g).append(' ').append(1 - b).append('\n'); // an inverting cube
            }
         }
      }
      Files.writeString(f.toPath(), sb.toString());
      GradeMath.Cube inv = GradeMath.Cube.read(f);
      f.delete();
      float[] half = GradeMath.bake(GradeMath.neutral(), new GradeMath.Cube[] {inv}, new float[] {0.5F});
      GradeMath.lookup(half, 0.9F, 0.1F, 0.4F, o);
      Check.check(Math.abs(o[0] - 0.5F) < 0.01F && Math.abs(o[1] - 0.5F) < 0.01F, "half-weight inverting cube -> mid grey (" + o[0] + "," + o[1] + ")");
      // fused table (stock screen tail + neutral grade): matches the stock tail within one 8-bit step everywhere
      int[] fused = GradeMath.bakeFusedPacked(GradeMath.neutral(), null, null);
      float fusedErr = 0F;
      float[] ref = new float[3];
      java.util.Random rnd = new java.util.Random(3);
      for (int i = 0; i < 20000; i++) {
         float r = rnd.nextInt(256) / 255F, g = rnd.nextInt(256) / 255F, b = rnd.nextInt(256) / 255F;
         ref[0] = r;
         ref[1] = g;
         ref[2] = b;
         GradeMath.stockTail(ref);
         GradeMath.lookupFused(fused, r, g, b, o);
         for (int ch = 0; ch < 3; ch++) {
            fusedErr = Math.max(fusedErr, Math.abs(Math.max(0F, Math.min(1F, o[ch])) - Math.max(0F, Math.min(1F, ref[ch]))));
         }
      }
      Check.check(fusedErr < 0.5F / 255F, "fused neutral table = the stock tail (max error " + fusedErr * 255F + " steps)");
      System.out.println("GradeMathTest ok (fused stock tail max error " + fusedErr * 255F + " steps) (identity LUT max error " + maxErr * 255F + " steps)");
   }
}
