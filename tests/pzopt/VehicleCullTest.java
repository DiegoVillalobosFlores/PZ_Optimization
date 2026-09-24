package pzopt;

/** pzopt.VehicleCull.distanceSquaredToSegment: the nearest-point arithmetic behind the vehicle prefilter. */
public class VehicleCullTest {
   public static void main(String[] args) {
      check("on the segment", 0.0F, VehicleCull.distanceSquaredToSegment(5, 5, 0, 0, 10, 10));
      check("beside the middle", 2.0F, VehicleCull.distanceSquaredToSegment(6, 4, 0, 0, 10, 10));
      check("past the end clamps to the end point", 8.0F, VehicleCull.distanceSquaredToSegment(12, 12, 0, 0, 10, 10));
      check("before the start clamps to the start point", 25.0F, VehicleCull.distanceSquaredToSegment(-3, -4, 0, 0, 10, 10));
      check("zero-length segment is a point distance", 25.0F, VehicleCull.distanceSquaredToSegment(3, 4, 0, 0, 0, 0));
      nearList();
      nearPerThread();
      System.out.println("VehicleCullTest ok");
   }

   /** near(): the per-frame candidate list is rebuilt on a new frame, target or larger reach, and reused otherwise. */
   private static void nearList() {
      java.util.List<zombie.vehicles.BaseVehicle> none = java.util.Collections.emptyList();
      int builds = VehicleCull.nearBuilds();
      VehicleCull.near(none, 10, 10, 20, 1);
      VehicleCull.near(none, 10, 10, 20, 1);
      VehicleCull.near(none, 10, 10, 15, 1);
      Check.check(VehicleCull.nearBuilds() == builds + 1, "same frame, target and a reach that fits: one build");
      VehicleCull.near(none, 10, 10, 25, 1);
      Check.check(VehicleCull.nearBuilds() == builds + 2, "a larger reach rebuilds");
      VehicleCull.near(none, 11, 10, 25, 1);
      Check.check(VehicleCull.nearBuilds() == builds + 3, "a moved target rebuilds");
      VehicleCull.near(none, 11, 10, 25, 2);
      Check.check(VehicleCull.nearBuilds() == builds + 4, "a new frame rebuilds");
   }

   /** near() off the game thread: every thread keeps its own list, so one thread's rebuild never clears another's. */
   private static void nearPerThread() {
      java.util.List<zombie.vehicles.BaseVehicle> none = java.util.Collections.emptyList();
      java.util.List<zombie.vehicles.BaseVehicle> mine = VehicleCull.near(none, 1, 1, 5, 100);
      java.util.concurrent.atomic.AtomicReference<Object> theirs = new java.util.concurrent.atomic.AtomicReference<>();
      Thread other = new Thread(() -> theirs.set(VehicleCull.near(none, 2, 2, 5, 100)));
      other.start();
      try {
         other.join();
      } catch (InterruptedException e) {
         throw new AssertionError(e);
      }
      Check.check(theirs.get() != null && theirs.get() != mine, "another thread gets its own list");
      Check.check(VehicleCull.near(none, 1, 1, 5, 100) == mine, "this thread's list is kept");
   }

   private static void check(String what, float expected, float got) {
      if (Math.abs(expected - got) > 1.0E-4F) {
         throw new AssertionError(what + ": expected " + expected + ", got " + got);
      }
   }
}
