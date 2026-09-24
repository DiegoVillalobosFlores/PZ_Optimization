package pzopt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.joml.Vector3f;
import zombie.GameWindow;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle;

/**
 * Cheap rejection for {@code IsoZombie.isVehicleBetween} ({@code vehicleCull}, 2026-09-22).
 *
 * <p>Stock asks every loaded vehicle for the exact intersection of the zombie-to-player segment with its box: the segment
 * is transformed into the vehicle's local space (two matrix multiplies, three pooled vectors) per vehicle, per zombie
 * that could see the player, per frame. Downtown Louisville has hundreds of parked cars and hundreds of zombies with a
 * line of sight, and the walk was 6 % of the game thread. A vehicle whose bounding circle (half the horizontal diagonal
 * of its script extents plus the centre-of-mass offset, with a margin) does not reach the segment cannot intersect it,
 * and that is two subtractions and a few multiplies on the vehicle's world position.
 *
 * <p>Since the player-LOS pass (2026-09-22) the per-zombie walk does not even visit every loaded vehicle: every zombie
 * asking in one frame asks about the same player position, and a vehicle can only cross a zombie-to-player segment
 * when its circle reaches the disc around the player whose radius is the zombie's distance. {@link #near} keeps, per
 * frame and player position, the vehicles whose circle reaches the disc of the zombie view distance (plus any caller
 * standing further out), so the exact test runs over the handful of cars around the player instead of the hundreds
 * parked across downtown.
 */
public final class VehicleCull {
   private VehicleCull() {
   }

   /**
    * Extra radius: the exact test uses the physics transform's origin while getX/getY follow it a tick behind (0.46
    * tiles a tick at 100 km/h), so the circle is generous enough that a moving car can never be rejected wrongly.
    */
   private static final float MARGIN = 1.0F;

   /** False when the vehicle's bounding circle misses the segment (x1,y1)-(x2,y2) in world tiles. */
   public static boolean mayIntersect(BaseVehicle vehicle, float x1, float y1, float x2, float y2) {
      float radius = radius(vehicle);
      if (radius < 0.0F) {
         return true;
      }
      return distanceSquaredToSegment(vehicle.getX(), vehicle.getY(), x1, y1, x2, y2) <= radius * radius;
   }

   /** The bounding-circle radius of a vehicle in tiles, -1 when it has no script (then nothing is rejected). */
   static float radius(BaseVehicle vehicle) {
      VehicleScript script = vehicle.getScript();
      if (script == null) {
         return -1.0F;
      }
      Vector3f extents = script.getExtents();
      Vector3f com = script.getCenterOfMassOffset();
      float half = 0.5F * (float)Math.sqrt(extents.x * extents.x + extents.z * extents.z);
      return half + (float)Math.sqrt(com.x * com.x + com.z * com.z) + MARGIN;
   }

   // The per-frame candidate list: the vehicles whose circle reaches the disc of radius radius around (x, y), built in
   // frame frame. One for the game thread (IsoZombie.spottedNew runs there); a mod that updates zombies on other
   // threads (PZMulticore, 2026-09-24: a shared list cleared under a reader threw ConcurrentModificationException and
   // handed out null vehicles) gets one per thread.
   private static final class Near {
      final ArrayList<BaseVehicle> list = new ArrayList<>();
      int frame = Integer.MIN_VALUE;
      float x;
      float y;
      float radius = -1.0F;
   }

   private static final Near GAME = new Near();
   private static final ThreadLocal<Near> OTHER = ThreadLocal.withInitial(Near::new);
   private static final java.util.concurrent.atomic.AtomicInteger nearBuilds = new java.util.concurrent.atomic.AtomicInteger();

   /**
    * The vehicles of {@code all} that can cross a segment from a point within {@code reach} tiles of (tx,ty) to
    * (tx,ty): a superset of what {@link #mayIntersect} accepts for any such segment. Reused across the frame while the
    * target and the frame stay the same and the reach fits; rebuilt otherwise (once per player per frame in practice).
    */
   public static List<BaseVehicle> near(Collection<BaseVehicle> all, float tx, float ty, float reach, int frame) {
      Near near = Thread.currentThread() == GameWindow.gameThread ? GAME : OTHER.get();
      if (frame != near.frame || tx != near.x || ty != near.y || reach > near.radius) {
         near.list.clear();
         for (BaseVehicle vehicle : all) {
            float radius = radius(vehicle);
            if (radius < 0.0F) {
               near.list.add(vehicle);
               continue;
            }
            float dx = vehicle.getX() - tx;
            float dy = vehicle.getY() - ty;
            float limit = radius + reach;
            if (dx * dx + dy * dy <= limit * limit) {
               near.list.add(vehicle);
            }
         }
         near.frame = frame;
         near.x = tx;
         near.y = ty;
         near.radius = reach;
         nearBuilds.incrementAndGet();
      }
      return near.list;
   }

   /** Candidate lists built so far (for the log line). */
   public static int nearBuilds() {
      return nearBuilds.get();
   }

   /** Squared distance from (px,py) to the segment (x1,y1)-(x2,y2). */
   static float distanceSquaredToSegment(float px, float py, float x1, float y1, float x2, float y2) {
      float dx = x2 - x1;
      float dy = y2 - y1;
      float len2 = dx * dx + dy * dy;
      float t = 0.0F;
      if (len2 > 1.0E-6F) {
         t = ((px - x1) * dx + (py - y1) * dy) / len2;
         t = t < 0.0F ? 0.0F : (t > 1.0F ? 1.0F : t);
      }
      float cx = x1 + t * dx - px;
      float cy = y1 + t * dy - py;
      return cx * cx + cy * cy;
   }
}
