package pzopt;

import java.util.ArrayList;
import org.joml.Vector3f;
import zombie.characters.IsoZombie;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.vehicles.BaseVehicle;

/**
 * What {@link DrivePilot} sees of the world (harness drive runs with flag path=, 2026-09-24): the street's paved band
 * across the path (street floor tiles, {@link Harness#isStreet}), and what stands on it ahead: other vehicles (hard,
 * oriented boxes projected onto the path), solid / solid-trans / tree squares inside the band (hard), zombies (soft).
 * Bands and solid squares are static, so they are cached per path sample once every square of the scan is loaded.
 */
final class DriveSenses implements DrivePilot.Senses {
   private static final float SCAN = 12f; // tiles each side of the path scanned for the street's edges
   private static final float[] NONE = new float[0];
   private final BaseVehicle self;
   private final int z;
   private final boolean zombies;
   private final float[][] bands;
   private final float[][] solids;
   private final Vector3f tmp = new Vector3f();

   DriveSenses(DrivePath path, BaseVehicle self, boolean zombies) {
      this.self = self;
      this.z = (int)self.getZ();
      this.zombies = zombies;
      this.bands = new float[path.n][];
      this.solids = new float[path.n][];
   }

   private IsoGridSquare square(float x, float y) {
      return IsoWorld.instance.getCell().getGridSquare((int)Math.floor(x), (int)Math.floor(y), z);
   }

   @Override
   public float[] band(DrivePath path, int i) {
      float[] b = bands[i];
      if (b != null) return b;
      // street tiles every half tile across the path; the band is the run of street that holds the path point (or
      // the nearest street within 2 tiles of it, for arcs that cut a corner's kerb)
      int steps = (int)(SCAN / 0.5f);
      boolean[] street = new boolean[2 * steps + 1];
      for (int j = -steps; j <= steps; j++) {
         float w = j * 0.5f;
         IsoGridSquare sq = square(path.x[i] - path.hy[i] * w, path.y[i] + path.hx[i] * w);
         if (sq == null) return null; // not loaded yet: unknown, try again later
         street[j + steps] = Harness.isStreet(sq);
      }
      int c = -1;
      for (int d = 0; d <= 4 && c < 0; d++) {
         if (street[steps + d]) c = steps + d;
         else if (street[steps - d]) c = steps - d;
      }
      if (c < 0) {
         b = new float[]{-1f, 1f}; // no street here (a gap in the tiles, a bridge deck): keep to the line
      } else {
         int lo = c, hi = c;
         while (lo > 0 && street[lo - 1]) lo--;
         while (hi < street.length - 1 && street[hi + 1]) hi++;
         b = new float[]{(lo - steps) * 0.5f - 0.25f, (hi - steps) * 0.5f + 0.25f};
      }
      bands[i] = b;
      return b;
   }

   /** Lateral offsets of solid squares inside the band at sample i (cached; null while unknown). */
   private float[] solids(DrivePath path, int i) {
      float[] s = solids[i];
      if (s != null) return s;
      float[] b = band(path, i);
      if (b == null) return null;
      ArrayList<Float> hits = null;
      int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;
      for (float w = b[0]; w <= b[1] + 1e-3f; w += 0.5f) {
         float px = path.x[i] - path.hy[i] * w, py = path.y[i] + path.hx[i] * w;
         int sx = (int)Math.floor(px), sy = (int)Math.floor(py);
         if (sx == lastX && sy == lastY) continue;
         lastX = sx;
         lastY = sy;
         IsoGridSquare sq = IsoWorld.instance.getCell().getGridSquare(sx, sy, z);
         if (sq == null) return null;
         if (sq.isSolid() || sq.isSolidTrans() || sq.HasTree()) {
            if (hits == null) hits = new ArrayList<>();
            hits.add(path.lateral(i, sx + 0.5f, sy + 0.5f));
         }
      }
      if (hits == null) s = NONE;
      else {
         s = new float[hits.size()];
         for (int k = 0; k < s.length; k++) s[k] = hits.get(k);
      }
      solids[i] = s;
      return s;
   }

   @Override
   public void obstacles(DrivePath path, float s0, float s1, int iHint, DrivePilot.Obstacles out) {
      int i0 = path.indexAt(Math.max(0f, s0)), i1 = path.indexAt(s1);
      // static: solid squares on the street, one sample per tile
      for (int i = i0; i <= i1; i += 2) {
         float[] s = solids(path, i);
         if (s == null) continue;
         for (float lat : s) out.add(path.s[i], lat, 0.5f, 0.5f, true);
      }
      // the path window's bounding box, grown by the widest band, to reject far objects cheaply
      float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
      for (int i = i0; i <= i1; i += 8) {
         minX = Math.min(minX, path.x[i]); maxX = Math.max(maxX, path.x[i]);
         minY = Math.min(minY, path.y[i]); maxY = Math.max(maxY, path.y[i]);
      }
      minX = Math.min(minX, path.x[i1]) - SCAN; maxX = Math.max(maxX, path.x[i1]) + SCAN;
      minY = Math.min(minY, path.y[i1]) - SCAN; maxY = Math.max(maxY, path.y[i1]) + SCAN;
      for (BaseVehicle o : IsoWorld.instance.getCell().getVehicles()) {
         if (o == self || o == null || o.getScript() == null) continue;
         float ox = o.getX(), oy = o.getY();
         if (ox < minX || ox > maxX || oy < minY || oy > maxY || Math.abs(o.getZ() - z) > 0.5f) continue;
         int i = project(path, ox, oy, i0, i1);
         float lat = path.lateral(i, ox, oy);
         if (Math.abs(lat) > SCAN) continue;
         Vector3f ext = o.getScript().getExtents();
         o.getForwardVector(tmp);
         float fl = (float)Math.hypot(tmp.x, tmp.z);
         float fx = fl > 1e-4f ? tmp.x / fl : 1f, fy = fl > 1e-4f ? tmp.z / fl : 0f;
         float tx = path.hx[i], ty = path.hy[i], nx = -ty, ny = tx;
         float hl = ext.z * 0.5f, hw = ext.x * 0.5f;
         float along = Math.abs(hl * (fx * tx + fy * ty)) + Math.abs(hw * (-fy * tx + fx * ty));
         float across = Math.abs(hl * (fx * nx + fy * ny)) + Math.abs(hw * (-fy * nx + fx * ny));
         float os = path.alongRaw(i, ox, oy); // unclamped: a car behind the path's start (the save's own) is not ahead of us
         if (os + along < s0 || os - along > s1) continue;
         out.add(os, lat, along, across, true);
      }
      if (!zombies) return;
      ArrayList<IsoZombie> list = IsoWorld.instance.getCell().getZombieList();
      for (int k = 0, n = list.size(); k < n; k++) {
         IsoZombie zo = list.get(k);
         if (zo == null || zo.isDead()) continue;
         float zx = zo.getX(), zy = zo.getY();
         if (zx < minX || zx > maxX || zy < minY || zy > maxY || Math.abs(zo.getZ() - z) > 0.5f) continue;
         int i = project(path, zx, zy, i0, i1);
         float lat = path.lateral(i, zx, zy);
         if (Math.abs(lat) > SCAN) continue;
         float zs = path.alongRaw(i, zx, zy);
         if (zs < s0 || zs > s1) continue;
         out.add(zs, lat, 0.35f, 0.35f, false);
      }
   }

   /** Nearest sample in [i0, i1]: every 8th sample, then the neighbourhood of the best. */
   private static int project(DrivePath path, float px, float py, int i0, int i1) {
      int best = path.nearest(px, py, i0, i0);
      float bd = Float.MAX_VALUE;
      for (int i = i0; i <= i1; i += 8) {
         float dx = px - path.x[i], dy = py - path.y[i], d = dx * dx + dy * dy;
         if (d < bd) { bd = d; best = i; }
      }
      return path.nearest(px, py, best - 8, best + 8);
   }

   @Override
   public boolean onStreet(float x, float y) {
      IsoGridSquare sq = square(x, y);
      return sq == null || Harness.isStreet(sq);
   }
}
