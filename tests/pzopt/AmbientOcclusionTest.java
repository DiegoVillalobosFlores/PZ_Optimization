package pzopt;

/**
 * The view-space scale the AO kernels rest on: the FBO depth is C - (x + y + 2z) * SQUARE_DEPTH / 2 and the 2:1
 * projection is an orthographic camera 30 degrees above the ground at 45 degrees, so a square along x is
 * cos 30 cos 45 squares along the view and a level (96 px tall at tileScale 1) is 2 x that.
 */
public class AmbientOcclusionTest {
   public static void main(String[] args) {
      double half = zombie.iso.IsoDepthHelper.SQUARE_DEPTH * 0.5;
      double c30c45 = Math.cos(Math.toRadians(30)) * Math.cos(Math.toRadians(45));
      Check.check(Math.abs(AmbientOcclusion.UNITS_PER_DEPTH * half - c30c45) < 1e-5, "one square along x is cos30 cos45 squares along the view");
      double level = 96.0 / (AmbientOcclusion.PX_PER_UNIT * Math.cos(Math.toRadians(30))); // a level's height in squares
      Check.check(Math.abs(level - 2.449) < 1e-3, "a level is 2.449 squares tall");
      Check.check(Math.abs(AmbientOcclusion.UNITS_PER_DEPTH * 2 * half - Math.sin(Math.toRadians(30)) * level) < 1e-3,
         "one level up (2 in x + y + 2z) is sin30 x its height along the view");
      Check.check(Math.abs(AmbientOcclusion.PX_PER_UNIT * Math.sin(Math.toRadians(30)) * Math.cos(Math.toRadians(45)) - 16.0) < 1e-4,
         "a square along x drops 16 px on screen at tileScale 1");
      System.out.println("AmbientOcclusionTest ok");
   }
}
