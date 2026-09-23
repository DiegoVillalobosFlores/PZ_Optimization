package pzopt;

/** The upscaler's pure parts: the Halton jitter sequence and the quality presets' render scale. */
public class UpscalerTest {
   public static void main(String[] args) {
      // Halton base 2 / 3: the classic first values, all inside [0, 1)
      Check.check(Math.abs(Dlss.halton(1, 2) - 0.5F) < 1e-6F, "halton(1,2) = " + Dlss.halton(1, 2));
      Check.check(Math.abs(Dlss.halton(2, 2) - 0.25F) < 1e-6F, "halton(2,2) = " + Dlss.halton(2, 2));
      Check.check(Math.abs(Dlss.halton(3, 2) - 0.75F) < 1e-6F, "halton(3,2) = " + Dlss.halton(3, 2));
      Check.check(Math.abs(Dlss.halton(1, 3) - 1.0F / 3.0F) < 1e-6F, "halton(1,3) = " + Dlss.halton(1, 3));
      Check.check(Math.abs(Dlss.halton(2, 3) - 2.0F / 3.0F) < 1e-6F, "halton(2,3) = " + Dlss.halton(2, 3));
      for (int i = 1; i < 200; i++) {
         float h2 = Dlss.halton(i, 2), h3 = Dlss.halton(i, 3);
         Check.check(h2 >= 0.0F && h2 < 1.0F && h3 >= 0.0F && h3 < 1.0F, "halton in [0,1) at " + i);
      }
      // the 32 first jitters spread over the pixel: every quadrant of the pixel gets samples
      int[] quadrant = new int[4];
      for (int i = 1; i <= 32; i++) {
         float x = Dlss.halton(i, 2) - 0.5F, y = Dlss.halton(i, 3) - 0.5F;
         quadrant[(x < 0 ? 0 : 1) + (y < 0 ? 0 : 2)]++;
      }
      for (int q = 0; q < 4; q++) {
         Check.check(quadrant[q] >= 4, "quadrant " + q + " has " + quadrant[q] + " of 32 jitters");
      }

      // the presets, read through Config (system properties win over everything; set before Config loads)
      System.setProperty("pzopt.upscaler", "fsr1");
      System.setProperty("pzopt.upscalerQuality", "performance");
      Check.check(Config.UPSCALER.equals("fsr1"), "upscaler key: " + Config.UPSCALER);
      Check.check(Config.UPSCALER_QUALITY.equals("performance"), "quality key: " + Config.UPSCALER_QUALITY);
      Check.check(Config.FSR_SHARPNESS_PCT == 80, "default sharpness 80: " + Config.FSR_SHARPNESS_PCT);
      Check.check(Config.UPSCALER_SCALE_PCT == 0, "no explicit scale: " + Config.UPSCALER_SCALE_PCT);
      // the DLSS defaults of 2026-09-23 (docs/plan-upscalers.md): preset E, 67 % output (the render size at quality),
      // the RCAS finish, the world drawn straight into the DLSS image
      Check.check(Config.DLSS_PRESET.equals("e"), "dlss preset default: " + Config.DLSS_PRESET);
      Check.check(Config.DLSS_OUTPUT_PCT == 67 && "rcas".equals(Config.DLSS_OUTPUT_FILTER) && Config.DLSS_DIRECT_COLOR,
         "dlss output defaults: " + Config.DLSS_OUTPUT_PCT + " " + Config.DLSS_OUTPUT_FILTER + " " + Config.DLSS_DIRECT_COLOR);
      Check.check(Config.DLSS_JITTER_SIGN == 1.0F && Config.DLSS_MV_SIGN == 1.0F, "sign defaults");
      System.out.println("UpscalerTest OK");
   }
}
