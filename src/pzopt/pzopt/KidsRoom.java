package pzopt;

import java.util.IdentityHashMap;
import java.util.Set;

/**
 * kidsRoomMemo (2026-09-25, the Rosewood drive's worst frames): a building that rolls the "trashed house" story on chunk
 * load (RBTrashed.trashHouse) asks RoomDef.isKidsRoom for every square of the building, twice through
 * isValidGraffSquare, and each answer scans every square of the room and every object on it against 19 tile names (a new
 * list per call): quadratic in the room. 20 % of the wall time of the drive's 40 worst game steps (10-104 ms, run
 * td-s-profvm). trashHouse destroys doors, smashes windows, moves container items to the floor and adds graffiti
 * overlays: none of those is a kids-room tile, so a room's answer cannot change within the pass, and it is kept per
 * RoomDef from RBTrashed.randomizeBuilding's begin to its end. Game thread only (chunk randomization runs there); other
 * threads and calls outside the pass scan as stock. devKidsRoomCheck rescans every memo hit and counts disagreements.
 */
public final class KidsRoom {
   public static final boolean ON = Config.KIDS_ROOM_MEMO && Overrides.enabled();
   public static final Set<String> TILES = Set.of("furniture_bedding_01_36", "furniture_bedding_01_38", "furniture_seating_indoor_02_12",
      "furniture_seating_indoor_02_13", "furniture_seating_indoor_02_14", "furniture_seating_indoor_02_15", "walls_decoration_01_50",
      "walls_decoration_01_51", "location_community_school_01_62", "location_community_school_01_63", "floors_rugs_01_63",
      "floors_rugs_01_64", "floors_rugs_01_65", "floors_rugs_01_66", "floors_rugs_01_67", "floors_rugs_01_68", "floors_rugs_01_69",
      "floors_rugs_01_70", "floors_rugs_01_71");
   private static final IdentityHashMap<Object, Boolean> memo = new IdentityHashMap<>();
   private static Thread owner;
   private static int depth;
   public static long passes, hits, misses, checks, mismatches, passNanos, worstPassNanos;
   private static long passStart;

   private KidsRoom() {
   }

   /** Opens the pass (the memo only with kidsRoomMemo on; the pass is timed either way, the A/B's measure). */
   public static void begin() {
      if (depth++ == 0) {
         owner = Thread.currentThread();
         passes++;
         passStart = System.nanoTime();
      }
   }

   public static void end() {
      if (depth == 0) {
         return;
      }
      if (--depth == 0) {
         long t = System.nanoTime() - passStart;
         passNanos += t;
         worstPassNanos = Math.max(worstPassNanos, t);
         memo.clear();
         owner = null;
         if (Overrides.enabled()) {
            Log.info(summary());
         }
      }
   }

   public static boolean active() {
      return ON && depth > 0 && Thread.currentThread() == owner;
   }

   public static Boolean get(Object room) {
      Boolean b = memo.get(room);
      if (b != null) {
         hits++;
      } else {
         misses++;
      }
      return b;
   }

   public static void put(Object room, boolean result) {
      memo.put(room, result);
   }

   public static void check(boolean memoized, boolean fresh) {
      checks++;
      if (memoized != fresh) {
         mismatches++;
      }
   }

   public static String summary() {
      return "kidsRoomMemo: " + (ON ? "on" : "off") + ", " + passes + " trashed-house passes, " + String.format(java.util.Locale.ROOT, "%.1f ms total, worst %.1f ms", passNanos / 1e6, worstPassNanos / 1e6) + ", " + hits + " answers from the memo, " + misses + " scans, dev checks "
         + checks + " mismatches " + mismatches;
   }
}
