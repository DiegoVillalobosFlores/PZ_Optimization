package pzopt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import zombie.characters.IsoPlayer;
import zombie.iso.BuildingDef;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.RoomDef;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;

/**
 * Harness scene {@code explore=restaurant} (2026-09-25, the flip HDR report: every light bloomed inside a building by
 * day, but only while the player faced north): the player walks from the bench save's spot to the nearest restaurant
 * and through its rooms, on foot, never teleported. Movement is the game's own input: the movement keys toward the next
 * square of a grid path (the loaded squares, walls / windows / furniture blocking, doors opened on the way, re-planned
 * every 2 s and around a square the player got stuck at), reported held by the zombie.input.GameKeyboard override
 * (Showcase's key set).
 *
 * <p>With {@code director=jev} the next action comes from outside the game like the horde scene's: every 0.3 s the
 * scene's facts go to {@code Zomboid/pzopt-explore-state.json}, harness/explore-director.py asks TypeSafe's Jev and writes
 * {@code <seq> <action>} to {@code Zomboid/pzopt-explore-cmd.txt} (go_to_restaurant, next_room, look_around, face_north,
 * hold, done). Without it an autopilot plays the same order. look_around turns the player once round in place (the HDR
 * trace, devHdrTraceMs, logs every facing); in the first {@code explore_dump_rooms} rooms it asks Hdr for a frame dump at
 * each of N / E / S / W. Flags: {@code explore_match} (room-name substrings, comma-separated), {@code explore_radius}.
 */
public final class Explore {
   private Explore() {
   }

   private static final String[] DEFAULT_MATCH = {"restaurant", "spiffo", "pizza", "burger", "diner", "cafe", "jayschicken", "chinese", "bakery"};
   private static final String[] ACTIONS = {"go_to_restaurant", "next_room", "look_around", "face_north", "hold", "done"};
   private static final String[] DIRS = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"}; // k * 45 deg, 0 = east, +y = south

   private static boolean on, director, started, finished;
   private static String command = "hold";
   private static int commandSeq = -1, commands, dumpRooms, doorsOpened, replans, stuckMarks;
   private static long startNs, lastStateNs, lastCmdCheckNs, lastPlanNs, lastProgressNs;
   private static java.io.File stateFile, cmdFile;

   private static BuildingDef building;
   private static RoomDef targetRoom, goalRoom;
   private static final ArrayList<RoomDef> rooms = new ArrayList<>();
   private static final Set<RoomDef> visited = new HashSet<>();
   private static final Set<RoomDef> unreachable = new HashSet<>();
   private static final Map<RoomDef, Float> turnedIn = new HashMap<>(), northSecsIn = new HashMap<>();
   private static final Set<Long> blocked = new HashSet<>();

   private static int[] pathX = new int[0], pathY = new int[0];
   private static int pathIdx, pathLen;
   private static boolean pathReachesGoal;
   private static float progressX, progressY;
   private static String lastGoalKey = "";

   public static boolean active() {
      return on;
   }

   /** The harness ends the route when the director (or the autopilot) says done. */
   public static boolean done() {
      return finished;
   }

   /** World-ready (game thread): out of harm's way, find the restaurant. */
   static void worldReady(IsoPlayer p) {
      on = "restaurant".equalsIgnoreCase(HarnessFlags.get("explore", "").trim());
      if (!on) {
         return;
      }
      director = "jev".equalsIgnoreCase(HarnessFlags.get("director", "").trim());
      dumpRooms = Integer.parseInt(HarnessFlags.get("explore_dump_rooms", "2").trim());
      java.io.File z = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir());
      stateFile = new java.io.File(z, "pzopt-explore-state.json");
      cmdFile = new java.io.File(z, "pzopt-explore-cmd.txt");
      cmdFile.delete();
      p.getCheats().set(zombie.characters.CheatType.GOD_MODE, true);
      p.setInvisible(true, true);
      String m = HarnessFlags.get("explore_match", "").trim();
      String[] match = m.isEmpty() ? DEFAULT_MATCH : m.toLowerCase(Locale.ROOT).split(",");
      float radius = Float.parseFloat(HarnessFlags.get("explore_radius", "600").trim());
      ArrayList<Object[]> found = new ArrayList<>();
      for (BuildingDef b : IsoWorld.instance.getMetaGrid().getBuildings()) {
         for (RoomDef r : b.getRooms()) {
            if (r.level != 0 || r.name == null) continue;
            String n = r.name.toLowerCase(Locale.ROOT);
            boolean hit = false;
            for (String s : match) hit |= !s.isBlank() && n.contains(s.trim());
            if (!hit) continue;
            float d = (float)Math.hypot((r.getX() + r.getX2()) / 2F - p.getX(), (r.getY() + r.getY2()) / 2F - p.getY());
            if (d <= radius) found.add(new Object[] {d, r});
         }
      }
      found.sort((a, b) -> Float.compare((Float)a[0], (Float)b[0]));
      for (int i = 0; i < Math.min(8, found.size()); i++) {
         RoomDef r = (RoomDef)found.get(i)[1];
         Log.info(String.format(Locale.ROOT, "harness: explore: candidate %s at %d,%d-%d,%d, %.0f tiles", r.name, r.getX(), r.getY(), r.getX2(), r.getY2(), (Float)found.get(i)[0]));
      }
      if (found.isEmpty()) {
         Log.warn("harness: explore: no room matching " + String.join(",", match) + " within " + radius + " tiles; nothing to explore");
         finished = true;
         return;
      }
      targetRoom = (RoomDef)found.get(0)[1];
      building = targetRoom.getBuilding();
      for (RoomDef r : building.getRooms()) {
         if (r.level == 0 && r.getArea() >= 4) rooms.add(r);
      }
      StringBuilder sb = new StringBuilder();
      for (RoomDef r : rooms) sb.append(' ').append(r.name).append('@').append(r.getX()).append(',').append(r.getY());
      Log.info("harness: explore=restaurant: " + targetRoom.name + " at " + targetRoom.getX() + "," + targetRoom.getY() + ", ground-floor rooms:" + sb
            + (director ? ", director jev" : ", autopilot"));
   }

   /** Route start (game thread): out of the car, the scene begins. */
   static void routeStart(IsoPlayer p) {
      if (!on || started) return;
      started = true;
      startNs = System.nanoTime();
      lastProgressNs = startNs;
      if (p.getVehicle() != null) {
         Log.info("harness: explore: leaving the " + p.getVehicle().getScriptName());
         p.getVehicle().exit(p);
      }
      command = director ? "hold" : "go_to_restaurant";
   }

   /** Per frame while the run is live (game thread). */
   static void tick(IsoPlayer p, long nowNs) {
      if (!on || !started || finished) return;
      float dt = Math.min(0.1F, zombie.GameTime.getInstance().getRealworldSecondsSinceLastUpdate());
      RoomDef here = stickyRoom(roomOf(p.getCurrentSquare()), nowNs);
      if (here != null && rooms.contains(here) && visited.add(here)) {
         Log.info(String.format(Locale.ROOT, "harness: explore: entered %s at +%.1f s", here.name, (nowNs - startNs) / 1e9));
      }
      if (director) {
         if (nowNs - lastCmdCheckNs >= 100_000_000L) {
            lastCmdCheckNs = nowNs;
            readCommand();
         }
      } else {
         autopilot(here);
      }
      Showcase.releaseKeys();
      switch (command) {
         case "go_to_restaurant" -> walkTo(p, nowNs, targetRoom, true);
         case "next_room" -> {
            RoomDef next = nextRoom(p);
            if (next != null) walkTo(p, nowNs, next, false);
         }
         case "look_around" -> {
            if (here != null) {
               float t = turnedIn.getOrDefault(here, 0F);
               if (t < 360F) {
                  float step = 60F * dt;
                  p.setDirectionAngle(p.getDirectionAngle() + step);
                  turnedIn.put(here, t + step);
                  dumpAtQuarters(p, here, t, t + step);
               }
            }
         }
         case "face_north" -> {
            float d = ((-90F - p.getDirectionAngle()) % 360F + 540F) % 360F - 180F; // north = -90 (+y is south)
            float step = 180F * dt;
            p.setDirectionAngle(p.getDirectionAngle() + Math.max(-step, Math.min(step, d)));
            if (here != null && Math.abs(d) < 10F) northSecsIn.merge(here, dt, Float::sum);
         }
         case "done" -> {
            finished = true;
            Log.info(String.format(Locale.ROOT, "harness: explore: done at +%.1f s, %d of %d rooms visited, %d unreachable, %d doors opened, %d re-plans, %d stuck marks, %d commands",
                  (nowNs - startNs) / 1e9, visited.size(), rooms.size(), unreachable.size(), doorsOpened, replans, stuckMarks, commands));
         }
         default -> { // hold
         }
      }
      if (director && nowNs - lastStateNs >= 300_000_000L) {
         lastStateNs = nowNs;
         writeState(p, here, nowNs);
      }
   }

   private static RoomDef shownRoom, candidateRoom;
   private static long candidateSinceNs;

   /**
    * The room the scene counts the player in: a new room only after 0.4 s in it. A player standing on the square where
    * two rooms meet flipped between them every frame (hdrnorth-walk: bakery / grocerystorage), and the director with it.
    */
   private static RoomDef stickyRoom(RoomDef now, long nowNs) {
      if (now == shownRoom) {
         candidateRoom = null;
      } else if (now != candidateRoom) {
         candidateRoom = now;
         candidateSinceNs = nowNs;
      } else if (nowNs - candidateSinceNs >= 400_000_000L) {
         shownRoom = now;
         candidateRoom = null;
      }
      return shownRoom;
   }

   private static RoomDef roomOf(IsoGridSquare sq) {
      return sq != null && sq.getRoom() != null ? sq.getRoom().getRoomDef() : null;
   }

   /** The nearest ground-floor room of the restaurant not visited yet and not found unreachable. */
   private static RoomDef nextRoom(IsoPlayer p) {
      RoomDef best = null;
      float bestD = Float.MAX_VALUE;
      for (RoomDef r : rooms) {
         if (visited.contains(r) || unreachable.contains(r)) continue;
         float d = (float)Math.hypot((r.getX() + r.getX2()) / 2F - p.getX(), (r.getY() + r.getY2()) / 2F - p.getY());
         if (d < bestD) {
            bestD = d;
            best = r;
         }
      }
      return best;
   }

   /** Without a director: to the restaurant, then in each room look around, face north a few seconds, next room. */
   private static void autopilot(RoomDef here) {
      boolean inside = here != null && rooms.contains(here);
      boolean left = false;
      for (RoomDef r : rooms) left |= !visited.contains(r) && !unreachable.contains(r);
      String next;
      if (!inside) {
         next = visited.isEmpty() ? "go_to_restaurant" : left ? "next_room" : "done";
      } else if (turnedIn.getOrDefault(here, 0F) < 360F) {
         next = "look_around";
      } else if (northSecsIn.getOrDefault(here, 0F) < 4F) {
         next = "face_north";
      } else {
         next = left ? "next_room" : "done";
      }
      if (!next.equals(command)) {
         Log.info("harness: explore autopilot: " + command + " -> " + next);
         command = next;
      }
   }

   // ---- walking: a grid path over the loaded squares, followed with the movement keys ----

   private static void walkTo(IsoPlayer p, long nowNs, RoomDef goal, boolean run) {
      IsoGridSquare cur = p.getCurrentSquare();
      if (cur == null || goal == null) return;
      if (roomOf(cur) == goal) {
         goalRoom = goal;
         pathLen = 0;
         return;
      }
      String key = goal.name + "@" + goal.getX() + "," + goal.getY();
      boolean stuck = pathLen > 0 && nowNs - lastProgressNs > 1_500_000_000L;
      if (stuck) {
         // no progress for 1.5 s (a car, a fence the grid did not see): that square is out, plan around it
         int bx = pathX[Math.min(pathIdx, pathLen - 1)], by = pathY[Math.min(pathIdx, pathLen - 1)];
         blocked.add(pack(bx, by));
         stuckMarks++;
         Log.info("harness: explore: stuck at " + cur.x + "," + cur.y + ", square " + bx + "," + by + " marked blocked");
      }
      if (!key.equals(lastGoalKey) || stuck || pathIdx >= pathLen || nowNs - lastPlanNs > 2_000_000_000L) {
         goalRoom = goal;
         lastGoalKey = key;
         lastPlanNs = nowNs;
         lastProgressNs = nowNs;
         progressX = p.getX();
         progressY = p.getY();
         plan(cur, goal);
         replans++;
         if (pathLen == 0) {
            if (!pathReachesGoal && goal != targetRoom) {
               unreachable.add(goal);
               Log.info("harness: explore: " + goal.name + " unreachable from " + cur.x + "," + cur.y);
            }
            return;
         }
      }
      if (Math.hypot(p.getX() - progressX, p.getY() - progressY) > 0.4) {
         progressX = p.getX();
         progressY = p.getY();
         lastProgressNs = nowNs;
      }
      // advance past the squares already reached
      while (pathIdx < pathLen && Math.hypot(pathX[pathIdx] + 0.5F - p.getX(), pathY[pathIdx] + 0.5F - p.getY()) < 0.35F) {
         pathIdx++;
      }
      if (pathIdx >= pathLen) return;
      IsoGridSquare next = cur.getCell().getGridSquare(pathX[pathIdx], pathY[pathIdx], cur.z);
      openDoorBetween(p, cur, next);
      Showcase.moveKeys(pathX[pathIdx] + 0.5F - p.getX(), pathY[pathIdx] + 0.5F - p.getY());
      if (run && cur.isOutside()) Showcase.holdKey("Run"); // outside it runs to save time; inside it walks
   }

   private static void openDoorBetween(IsoPlayer p, IsoGridSquare a, IsoGridSquare b) {
      if (a == null || b == null || a == b) return;
      IsoObject o = a.getDoorTo(b);
      if (o instanceof IsoDoor d && !d.IsOpen() && !d.isBarricaded()) {
         d.setLocked(false);
         d.setLockedByKey(false);
         d.ToggleDoor(p);
         doorsOpened++;
         Log.info("harness: explore: opened a door at " + d.getSquare().x + "," + d.getSquare().y);
      } else if (o instanceof IsoThumpable t && t.isDoor() && !t.IsOpen()) {
         t.ToggleDoor(p);
         doorsOpened++;
      }
   }

   private static long pack(int x, int y) {
      return ((long)x << 32) ^ (y & 0xFFFFFFFFL);
   }

   /** Breadth-first over the loaded ground-floor squares around the player; the goal room, else the square nearest it. */
   private static void plan(IsoGridSquare start, RoomDef goal) {
      IsoCell cell = start.getCell();
      int r = 80, w = 2 * r + 1, ox = start.x - r, oy = start.y - r, z = start.z;
      int[] prev = new int[w * w];
      java.util.Arrays.fill(prev, -2);
      ArrayDeque<Integer> q = new ArrayDeque<>();
      int s = (start.y - oy) * w + (start.x - ox);
      prev[s] = -1;
      q.add(s);
      float gx = (goal.getX() + goal.getX2()) / 2F, gy = (goal.getY() + goal.getY2()) / 2F;
      int found = -1, nearest = s;
      float nearestD = (float)Math.hypot(start.x - gx, start.y - gy);
      int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
      while (!q.isEmpty()) {
         int i = q.poll();
         int x = ox + i % w, y = oy + i / w;
         IsoGridSquare a = cell.getGridSquare(x, y, z);
         if (a == null) continue;
         if (roomOf(a) == goal) {
            found = i;
            break;
         }
         float d = (float)Math.hypot(x - gx, y - gy);
         if (d < nearestD) {
            nearestD = d;
            nearest = i;
         }
         for (int[] n : nb) {
            int nx = x + n[0], ny = y + n[1];
            if (nx < ox || ny < oy || nx >= ox + w || ny >= oy + w) continue;
            int j = (ny - oy) * w + (nx - ox);
            if (prev[j] != -2) continue;
            IsoGridSquare b = cell.getGridSquare(nx, ny, z);
            if (!passable(a, b)) continue;
            prev[j] = i;
            q.add(j);
         }
      }
      pathReachesGoal = found >= 0;
      int end = found >= 0 ? found : nearest;
      int n = 0;
      for (int i = end; i >= 0; i = prev[i]) n++;
      if (pathX.length < n) {
         pathX = new int[n];
         pathY = new int[n];
      }
      int k = n;
      for (int i = end; i >= 0; i = prev[i]) {
         k--;
         pathX[k] = ox + i % w;
         pathY[k] = oy + i / w;
      }
      // the first entry is the player's own square
      pathLen = n;
      pathIdx = Math.min(1, n);
      if (n <= 1) pathLen = 0;
   }

   private static boolean passable(IsoGridSquare a, IsoGridSquare b) {
      if (b == null || b.z != a.z || blocked.contains(pack(b.x, b.y))) return false;
      if (!b.isFree(false)) return false;
      if (a.isWallTo(b) || a.isWindowBlockedTo(b) || a.isStairBlockedTo(b)) return false;
      if (a.isDoorBlockedTo(b)) {
         IsoObject o = a.getDoorTo(b);
         return o instanceof IsoDoor d && !d.isBarricaded() || o instanceof IsoThumpable t && t.isDoor();
      }
      return true;
   }

   // ---- HDR dumps at the cardinal facings of the first rooms ----

   private static final ArrayList<RoomDef> dumpedRooms = new ArrayList<>();

   /** look_around turned this room's total from {@code before} to {@code after} degrees: a dump at every quarter turn. */
   private static void dumpAtQuarters(IsoPlayer p, RoomDef here, float before, float after) {
      if (!dumpedRooms.contains(here)) {
         if (dumpedRooms.size() >= dumpRooms) return;
         dumpedRooms.add(here);
      }
      for (int m = 0; m < 4; m++) {
         float th = 1F + 90F * m;
         if (before < th && after >= th) {
            String tag = "r" + dumpedRooms.indexOf(here) + "-" + facing(p);
            Hdr.requestDump(tag);
            Log.info("harness: explore: HDR dump " + tag + " (" + here.name + ")");
         }
      }
   }

   private static String facing(IsoPlayer p) {
      return DIRS[Math.floorMod(Math.round(p.getDirectionAngle() / 45F), 8)];
   }

   // ---- the director (Jev) ----

   private static void readCommand() {
      try {
         if (cmdFile == null || !cmdFile.isFile()) return;
         String[] parts = java.nio.file.Files.readString(cmdFile.toPath()).trim().split("\\s+");
         if (parts.length < 2) return;
         int seq = Integer.parseInt(parts[0]);
         if (seq == commandSeq) return;
         commandSeq = seq;
         String c = parts[1];
         if (!java.util.Arrays.asList(ACTIONS).contains(c)) return;
         if (!c.equals(command)) {
            commands++;
            Log.info("harness: explore director: " + command + " -> " + c + " (#" + seq + ")");
         }
         command = c;
      } catch (Exception e) {
         // a half-written file: the next check reads it
      }
   }

   private static void writeState(IsoPlayer p, RoomDef here, long nowNs) {
      boolean inside = here != null && rooms.contains(here);
      int left = 0;
      for (RoomDef r : rooms) left += !visited.contains(r) && !unreachable.contains(r) ? 1 : 0;
      float toRestaurant = (float)Math.hypot((targetRoom.getX() + targetRoom.getX2()) / 2F - p.getX(), (targetRoom.getY() + targetRoom.getY2()) / 2F - p.getY());
      String json = String.format(Locale.ROOT,
            "{\"t\":%d,\"seconds_since_start\":%.1f,\"current_action\":\"%s\",\"player\":{\"inside_restaurant\":%b,\"current_room\":\"%s\","
                  + "\"distance_to_restaurant_tiles\":%.1f,\"moving\":%b,\"facing\":\"%s\"},\"restaurant\":{\"visited_any_room\":%b,\"rooms_total\":%d,\"rooms_visited\":%d,"
                  + "\"rooms_left_to_visit\":%d},\"this_room\":{\"looked_around\":%b,\"degrees_turned\":%.0f,\"seconds_facing_north\":%.1f}}",
            System.currentTimeMillis(), (nowNs - startNs) / 1e9, command, inside, here == null ? (p.getCurrentSquare() != null && p.getCurrentSquare().isOutside() ? "outside" : "none") : here.name,
            toRestaurant, p.isPlayerMoving(), facing(p), !visited.isEmpty(), rooms.size(), visited.size(), left,
            inside && turnedIn.getOrDefault(here, 0F) >= 360F, inside ? turnedIn.getOrDefault(here, 0F) : 0F, inside ? northSecsIn.getOrDefault(here, 0F) : 0F);
      try {
         java.io.File tmp = new java.io.File(stateFile.getPath() + ".tmp");
         java.nio.file.Files.writeString(tmp.toPath(), json);
         java.nio.file.Files.move(tmp.toPath(), stateFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (Exception e) {
         Log.warn("harness: explore: state write failed: " + e);
      }
   }
}
