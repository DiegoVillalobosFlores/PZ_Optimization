package pzopt;

/**
 * The crown shade of a tree drawn per frame (FBORenderTrees: a tree near the player swaying in the wind, fading or
 * translucent never bakes, so the chunk kernel's crown proxy, pzopt.ChunkAo, never reaches it; it drew evenly lit while
 * its baked neighbours had shaded trunks and lower crowns, and a tree lost its shade as the player walked up to it).
 *
 * <p>The same model as the kernel's: the crown an ellipsoid on the tree's card, centred at 0.6 of the sprite's height,
 * 0.4 of it tall and {@code rh} squares wide; a point of the card takes exp(-sigma x its path out of the crown) towards
 * the sun (sunShadowTrees, times the sun's strength) and straight up (aoTreeCanopyPct, at most half the sky, times the
 * vegetation AO strength). The card's quad is drawn in strips with a colour per vertex. The per-frame card does not know
 * which side faces the sun, so the lateral part of the sun path is averaged over both sides: the shade runs from the top
 * down, like the baked tree's minus its sunward rim.
 */
public final class TreeShade {
   private TreeShade() {
   }

   /** Strips per quad (the shade is smooth; vertices carry it). */
   public static final int STRIPS = 6;

   /** Render thread: shade trees drawn per frame now? */
   public static boolean active() {
      return Overrides.enabled() && (sunOn() || skyOn());
   }

   private static boolean sunOn() {
      return Config.SUN_SHADOWS && Config.SUN_SHADOW_TREES && SunShadow.dir[3] > 0F && Config.SUN_SHADOW_CANOPY_PCT > 0;
   }

   private static boolean skyOn() {
      return Config.AO && Config.AO_TREE_CANOPY_PCT > 0;
   }

   /**
    * The shade factor at a point of a tree's card: {@code u} squares along the card from its centre, {@code h} squares above
    * the foot; {@code height} the sprite's height in squares, {@code rh} the crown's half width.
    */
   public static float shade(float u, float h, float height, float rh) {
      float zc = 0.6F * height, rv = 0.4F * height;
      float f = 1F;
      if (sunOn()) {
         float[] w = SunShadow.world;
         float lh = (float)Math.sqrt(w[0] * w[0] + w[1] * w[1]);
         // the sun's horizontal part split along the card (lateral, either side) and across it (depth); the card faces the
         // camera, the SE, so across = the sun's component along (1, 1) / sqrt 2 (x east, y south)
         float across = (w[0] + w[1]) * 0.70710678F;
         float along = (float)Math.sqrt(Math.max(0F, lh * lh - across * across));
         float c = 0.5F * (chord(u, 0F, h - zc, along, across, w[2], rh, rv) + chord(u, 0F, h - zc, -along, across, w[2], rh, rv));
         f *= 1F - SunShadow.dir[3] * (1F - (float)Math.exp(-Config.SUN_SHADOW_CANOPY_PCT / 100F * c));
      }
      if (skyOn()) {
         float c = chord(u, 0F, h - zc, 0F, 0F, 1F, rh, rv);
         float veg = Math.max(0, Config.AO_STRENGTH_VEGETATION_PCT) / 100F;
         f *= 1F - 0.5F * veg * (1F - (float)Math.exp(-Config.AO_TREE_CANOPY_PCT / 100F * c));
      }
      return f;
   }

   /** The length of the ray from (x, y, z) (relative to the crown's centre) along (dx, dy, dz) inside the ellipsoid (rh, rh, rv). */
   private static float chord(float x, float y, float z, float dx, float dy, float dz, float rh, float rv) {
      float ox = x / rh, oy = y / rh, oz = z / rv, rx = dx / rh, ry = dy / rh, rz = dz / rv;
      float a = rx * rx + ry * ry + rz * rz;
      if (a < 1e-8F) {
         return 0F;
      }
      float b = ox * rx + oy * ry + oz * rz, cc = ox * ox + oy * oy + oz * oz - 1F;
      float disc = b * b - a * cc;
      if (disc <= 0F) {
         return 0F;
      }
      float s = (float)Math.sqrt(disc);
      float t1 = (-b + s) / a, t0 = Math.max(0F, (-b - s) / a);
      return Math.max(0F, t1 - t0) * (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
   }

   /** The crown's half width for a sprite unitsX wide (FBORenderTrees: 14 JUMBOXXL, 10 JUMBOXL, 6 JUMBO, 2 plain). */
   public static float crownRadius(int unitsX) {
      return unitsX >= 14 ? 3.3F : unitsX >= 10 ? 2.4F : unitsX >= 6 ? 1.5F : 0.6F;
   }
}
