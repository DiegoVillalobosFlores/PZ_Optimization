package pzopt;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * pzopt.SpriteFilter: the fetch patch finds the one DIFFUSE fetch of every program it patches (the game's chunkShader,
 * tileWithDepth, opaqueWithDepth and pixel light's form), every define combination compiles (glslangValidator when it is
 * on the PATH), and the magnification ramp is the exact box coverage of a hard texel edge.
 */
public class SpriteFilterTest {
   public static void main(String[] args) throws Exception {
      File jar = new File(zombie.core.Core.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      Path shaders = jar.toPath().getParent().resolve("media/shaders");
      String[] programs = {"chunkShader.frag", "tileWithDepth.frag", "opaqueWithDepth.frag"};
      boolean glslang = onPath("glslangValidator");
      Path tmp = Files.createTempDirectory("pzsf");
      int compiled = 0;
      for (String name : programs) {
         String src = Files.readString(shaders.resolve(name));
         for (int regime = 1; regime <= 3; regime++) {
            for (int min : new int[] {0, 1, 2, 4, 5, 6, 7}) {
               for (int skip = 0; skip < 2; skip++) {
                  for (int kernel = 0; kernel < 2; kernel++) {
                     String d = "#define PZSF_REGIME " + regime + "\n#define PZSF_MIN " + min + "\n#define PZSF_SKIP " + skip
                           + "\n#define PZSF_LOD_BIAS 0.500\n#define PZSF_KERNEL " + kernel + "\n#define PZSF_INTEGER_AA " + skip + "\n#define PZSF_LINEAR " + kernel + "\n#define PZSF_WIDTH 0.500000\n";
                     String p = SpriteFilter.patchFetch(src, d);
                     Check.check(p != null && p.contains("pzsfFetch(DIFFUSE, texCoord.st)"), name + ": the fetch is replaced");
                     Check.check(!p.contains("texture2D(DIFFUSE, texCoord.st, 0.0)"), name + ": no stock fetch left");
                     Check.check(p.startsWith("#version 150 compatibility") || p.startsWith("#version 330"), name + ": a GLSL version with textureSize");
                     if (glslang) {
                        Path f = tmp.resolve("v.frag");
                        Files.writeString(f, p);
                        Process pr = new ProcessBuilder("glslangValidator", "-S", "frag", f.toString()).redirectErrorStream(true).start();
                        String out = new String(pr.getInputStream().readAllBytes());
                        Check.check(pr.waitFor() == 0, name + " regime " + regime + " min " + min + " skip " + skip + " kernel " + kernel + " compiles:\n" + out);
                        compiled++;
                     }
                  }
               }
            }
         }
      }
      if (glslang) { // the sharp level-1 mipmap program
         for (String[] u : new String[][] {{"frag", SpriteMips.FRAG}, {"frag", SpriteMips.FRAG.replace("#version 330", "#version 330\n#define LINEAR_LIGHT")}, {"vert", SpriteMips.VERT}}) {
            Path f = tmp.resolve("mip." + u[0]);
            Files.writeString(f, u[1]);
            Process pr = new ProcessBuilder("glslangValidator", f.toString()).redirectErrorStream(true).start();
            String out = new String(pr.getInputStream().readAllBytes());
            Check.check(pr.waitFor() == 0, "sharp mipmap " + u[0] + " compiles:\n" + out);
         }
      }
      // the Lanczos-2 weights folded into bilinear pairs sum to one per axis and reproduce the texel weights
      double[] lw = new double[4];
      double sum = 0;
      for (int i = 0; i < 4; i++) {
         double x = (i + 0.5) / 2.0; // output texels
         lw[i] = sinc(x) * sinc(x / 2.0);
         sum += 2 * lw[i];
      }
      for (int i = 0; i < 4; i++) {
         lw[i] /= sum;
      }
      Check.check(Math.abs((lw[0] + lw[1]) - 0.5508) < 5e-4 && Math.abs((lw[2] + lw[3]) + 0.0508) < 5e-4, "pair weights 0.5508 / -0.0508");
      Check.check(Math.abs((0.5 * lw[0] + 1.5 * lw[1]) / (lw[0] + lw[1]) - 0.7115) < 5e-4, "pair offset 0.7115");
      Check.check(Math.abs((2.5 * lw[2] + 3.5 * lw[3]) / (lw[2] + lw[3]) - 2.675) < 5e-3, "pair offset 2.675");
      // pixel light's composite form
      String ppl = "#version 420\nuniform sampler2D DIFFUSE;\nin vec2 texCoord;\nout vec4 o;\nvoid main() {\n   vec4 c = texture(DIFFUSE, texCoord.st);\n   o = c;\n}\n";
      String p = SpriteFilter.patchFetch(ppl, "#define PZSF_REGIME 3\n#define PZSF_MIN 4\n#define PZSF_SKIP 0\n#define PZSF_LOD_BIAS 0.0\n#define PZSF_KERNEL 0\n#define PZSF_INTEGER_AA 0\n#define PZSF_LINEAR 0\n#define PZSF_WIDTH 1.0\n");
      Check.check(p != null && p.indexOf("#define PZSF_REGIME") > p.indexOf("#version 420"), "pixel light's form: patched after its #version");
      Check.check(SpriteFilter.patchFetch("#version 120\nvoid main() {}\n", "") == null, "no fetch: left alone");

      // the magnification ramp: a pixel w texels wide centred at p over a hard edge at c between texel values 0 and 1;
      // the linear filter at the remapped coordinate must return the share of the pixel on the right texel
      for (double w : new double[] {0.25, 0.5, 0.75, 0.9}) {
         for (int i = 0; i <= 400; i++) {
            double c = 10.0;
            double p0 = c - 1.0 + i * 0.005;
            double pr = c + Math.max(-0.5, Math.min(0.5, (p0 - c) / w)); // pzsfMagnify, box kernel
            double filtered = Math.max(0.0, Math.min(1.0, pr - (c - 0.5))); // linear filter between the texel centres c -/+ 0.5
            double coverage = Math.max(0.0, Math.min(1.0, (p0 + w / 2 - c) / w));
            Check.check(Math.abs(filtered - coverage) < 1e-9, "box coverage at w=" + w + " p=" + p0);
         }
      }
      System.out.println("SpriteFilterTest ok" + (glslang ? " (" + compiled + " variants compiled by glslangValidator)" : " (glslangValidator not on PATH: sources only)"));
   }

   private static double sinc(double x) {
      return x == 0 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
   }

   private static boolean onPath(String exe) {
      for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
         if (new File(dir, exe).canExecute()) {
            return true;
         }
      }
      return false;
   }
}
