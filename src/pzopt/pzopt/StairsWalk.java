package pzopt;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import zombie.characters.IsoPlayer;
import zombie.iso.BuildingDef;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.RoomDef;
import zombie.iso.SpriteDetails.IsoObjectType;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;

/**
 * Harness scene {@code explore=stairs} (2026-09-25, the flip report: with every new lighting key on, the walls flicker
 * while the player climbs to the top floor of a building and goes back down to the basement): the player walks the
 * building's staircases on foot, through the movement keys (Showcase's key set), never teleported. The planner is a
 * breadth-first search over the loaded squares of every level of the building, where a staircase is one edge from the
 * square in front of its bottom step to the square past its top step (both ways); the path then walks the three stair
 * squares in line, which is how the game's own movement climbs and descends.
 *
 * <p>With {@code director=jev} the next action comes from harness/explore-director.py {@code --scene stairs} (go_up,
 * go_down, look_around, hold, done) through the same state / command files as explore=restaurant; without it an
 * autopilot plays the same order: look around on every level, up to the top floor, then down to the lowest level.
 * Every level change is logged with its epoch ms ({@code harness: stairs: level}), so a devCapture sequence can be cut
 * at the transitions.
 */
final class StairsWalk {
   private StairsWalk() {
   }

   static final String[] ACTIONS = {"go_up", "go_down", "look_around", "hold", "done"};

   private static boolean director, finished, reachedTop, backDown;
   private static int lowest, top, level, goalLevel = Integer.MIN_VALUE, levelChanges, commands, commandSeq = -1, replans, stuckMarks, doorsOpened;
   private static String command = "hold";
   private static long startNs, lastStateNs, lastCmdCheckNs, lastPlanNs, lastProgressNs, levelSinceNs, arrivedNs;
   private static java.io.File stateFile, cmdFile;
   private static float progressX, progressY;
   private static final Set<Integer> lookedOn = new HashSet<>(), noStairsUp = new HashSet<>(), noStairsDown = new HashSet<>();
   private static final java.util.Map<Integer, Float> turnedOn = new java.util.HashMap<>();
   private static final Set<Long> blocked = new HashSet<>();

   private static int[] pathX = new int[0], pathY = new int[0], pathZ = new int[0];
   private static int pathIdx, pathLen;

   static boolean finished() {
      return finished;
   }

   static void worldReady(IsoPlayer p) {
      director = "jev".equalsIgnoreCase(HarnessFlags.get("director", "").trim());
      java.io.File z = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir());
      stateFile = new java.io.File(z, "pzopt-explore-state.json");
      cmdFile = new java.io.File(z, "pzopt-explore-cmd.txt");
      cmdFile.delete();
      stateFile.delete(); // a previous run's state would be answered before this run writes its own
      p.getCheats().set(zombie.characters.CheatType.GOD_MODE, true);
      p.setInvisible(true, true);
      level = levelOf(p);
      lowest = top = level;
      IsoGridSquare sq = p.getCurrentSquare();
      BuildingDef b = sq != null && sq.getBuilding() != null ? sq.getBuilding().getDef() : null;
      if (b == null && sq != null && sq.getRoom() != null) b = sq.getRoom().getRoomDef().getBuilding();
      if (b != null) {
         // a basement is a building of its own (map_basements): every building whose footprint overlaps this one counts
         int bx = b.getX(), by = b.getY(), bx2 = b.getX2(), by2 = b.getY2();
         for (BuildingDef o : zombie.iso.IsoWorld.instance.getMetaGrid().getBuildings()) {
            if (o != b && (o.getX2() < bx || o.getX() > bx2 || o.getY2() < by || o.getY() > by2)) continue;
            for (RoomDef r : o.getRooms()) {
               lowest = Math.min(lowest, r.level);
               top = Math.max(top, r.level);
            }
         }
      }
      String t = HarnessFlags.get("stairs_top", "").trim(); // override when the building's rooms do not tell
      if (!t.isEmpty()) top = Integer.parseInt(t);
      Log.info(String.format(Locale.ROOT, "harness: explore=stairs at %.1f,%.1f,%d, building levels %d..%d%s", p.getX(), p.getY(), level, lowest, top,
            director ? ", director jev" : ", autopilot"));
   }

   static void routeStart(IsoPlayer p) {
      startNs = System.nanoTime();
      levelSinceNs = startNs;
      lastProgressNs = startNs;
      command = "hold";
      event(p, "start");
   }

   static void tick(IsoPlayer p, long nowNs) {
      if (finished) return;
      float dt = Math.min(0.1F, zombie.GameTime.getInstance().getRealworldSecondsSinceLastUpdate());
      IsoGridSquare cur = p.getCurrentSquare();
      boolean onStairs = cur != null && cur.HasStairs();
      int lv = levelOf(p);
      if (!onStairs && lv != level) {
         level = lv;
         levelChanges++;
         levelSinceNs = nowNs;
         top = Math.max(top, level);
         lowest = Math.min(lowest, level);
         if (level >= top) reachedTop = true;
         if (reachedTop && level <= lowest) backDown = true;
         event(p, "level " + level);
      }
      if (director) {
         if (nowNs - lastCmdCheckNs >= 100_000_000L) {
            lastCmdCheckNs = nowNs;
            readCommand();
         }
      } else {
         autopilot(onStairs);
      }
      Showcase.releaseKeys();
      switch (command) {
         case "go_up", "go_down" -> {
            int dz = "go_up".equals(command) ? 1 : -1;
            if (goalLevel == Integer.MIN_VALUE) goalLevel = level + dz;
            if (!onStairs && level == goalLevel) {
               if (pathLen > 0 || arrivedNs == 0L) arrivedNs = nowNs;
               pathLen = 0; // arrived: stand until the next command (a fresh go_up / go_down decision goes one more level)
            } else {
               walk(p, cur, onStairs, nowNs);
            }
         }
         case "look_around" -> {
            goalLevel = Integer.MIN_VALUE;
            float turned = turnedOn.getOrDefault(level, 0F);
            if (turned < 360F) {
               float step = 90F * dt;
               p.setDirectionAngle(p.getDirectionAngle() + step);
               turnedOn.put(level, turned + step);
               if (turned + step >= 360F) {
                  lookedOn.add(level);
                  event(p, "looked around on " + level);
               }
            }
         }
         case "done" -> {
            finished = true;
            event(p, String.format(Locale.ROOT, "done: %d level changes, top reached %b, back down %b, %d re-plans, %d stuck marks, %d doors, %d commands",
                  levelChanges, reachedTop, backDown, replans, stuckMarks, doorsOpened, commands));
         }
         default -> goalLevel = Integer.MIN_VALUE; // hold
      }
      if (director && nowNs - lastStateNs >= 300_000_000L) {
         lastStateNs = nowNs;
         writeState(p, onStairs, nowNs);
      }
   }

   private static int levelOf(IsoPlayer p) {
      return (int)Math.floor(p.getZ() + 0.05F);
   }

   private static void event(IsoPlayer p, String what) {
      Log.info(String.format(Locale.ROOT, "harness: stairs: %s at +%.1f s epoch=%d pos=%.1f,%.1f,%.2f", what, (System.nanoTime() - startNs) / 1e9,
            System.currentTimeMillis(), p.getX(), p.getY(), p.getZ()));
   }

   /** Without a director: look around on every level, up to the top floor, then down to the lowest level, done. */
   private static void autopilot(boolean onStairs) {
      String next;
      if (onStairs) {
         next = command;
      } else if (!lookedOn.contains(level)) {
         next = "look_around";
      } else if (!reachedTop && level < top && !noStairsUp.contains(level)) {
         next = "go_up";
      } else if (level > lowest && !noStairsDown.contains(level)) {
         next = "go_down";
      } else {
         next = "done";
      }
      if (!next.equals(command)) {
         Log.info("harness: stairs autopilot: " + command + " -> " + next);
         setCommand(next);
      }
   }

   private static void setCommand(String c) {
      if (!c.equals(command)) {
         goalLevel = Integer.MIN_VALUE;
         arrivedNs = 0L;
         pathLen = 0;
         commands++;
      }
      command = c;
   }

   // ---- walking: a path over the loaded squares of every level, staircases as edges ----

   private static void walk(IsoPlayer p, IsoGridSquare cur, boolean onStairs, long nowNs) {
      if (cur == null) return;
      boolean stuck = pathLen > 0 && nowNs - lastProgressNs > 1_500_000_000L;
      if (stuck && !onStairs) {
         int bx = pathX[Math.min(pathIdx, pathLen - 1)], by = pathY[Math.min(pathIdx, pathLen - 1)];
         blocked.add(pack(bx, by, pathZ[Math.min(pathIdx, pathLen - 1)]));
         stuckMarks++;
         Log.info("harness: stairs: stuck at " + cur.x + "," + cur.y + "," + cur.z + ", square " + bx + "," + by + " marked blocked");
      }
      // never re-plan on the stairs: the square under the player is a stair square with no plain neighbours
      if (!onStairs && (pathLen == 0 || stuck || pathIdx >= pathLen || nowNs - lastPlanNs > 2_000_000_000L)) {
         lastPlanNs = nowNs;
         lastProgressNs = nowNs;
         progressX = p.getX();
         progressY = p.getY();
         plan(cur, goalLevel);
         replans++;
         if (pathLen == 0) {
            (goalLevel > level ? noStairsUp : noStairsDown).add(level);
            Log.info("harness: stairs: no staircase " + (goalLevel > level ? "up" : "down") + " reachable from " + cur.x + "," + cur.y + "," + cur.z);
            return;
         }
      }
      if (Math.hypot(p.getX() - progressX, p.getY() - progressY) > 0.4) {
         progressX = p.getX();
         progressY = p.getY();
         lastProgressNs = nowNs;
      }
      while (pathIdx < pathLen && Math.hypot(pathX[pathIdx] + 0.5F - p.getX(), pathY[pathIdx] + 0.5F - p.getY()) < 0.35F) {
         pathIdx++;
      }
      if (pathIdx >= pathLen) return;
      if (!onStairs) {
         IsoGridSquare next = cur.getCell().getGridSquare(pathX[pathIdx], pathY[pathIdx], pathZ[pathIdx]);
         if (next != null && next.z == cur.z) openDoorBetween(p, cur, next);
      }
      Showcase.moveKeys(pathX[pathIdx] + 0.5F - p.getX(), pathY[pathIdx] + 0.5F - p.getY());
   }

   private static void openDoorBetween(IsoPlayer p, IsoGridSquare a, IsoGridSquare b) {
      if (a == b) return;
      IsoObject o = a.getDoorTo(b);
      if (o instanceof IsoDoor d && !d.IsOpen() && !d.isBarricaded()) {
         d.setLocked(false);
         d.setLockedByKey(false);
         d.ToggleDoor(p);
         doorsOpened++;
         Log.info("harness: stairs: opened a door at " + d.getSquare().x + "," + d.getSquare().y + "," + d.getSquare().z);
      } else if (o instanceof IsoThumpable t && t.isDoor() && !t.IsOpen()) {
         t.ToggleDoor(p);
         doorsOpened++;
      }
   }

   private static long pack(int x, int y, int z) {
      return ((long)x << 40) ^ ((long)(y & 0xFFFFF) << 20) ^ (z & 0xFFFFF);
   }

   private static final int R = 60, W = 2 * R + 1;
   // staircase edges: {dx, dy} of the climb (north: -y, west: -x)
   private static final int[][] CLIMB = {{0, -1}, {-1, 0}};

   /** Breadth-first from the player's square to the nearest square on {@code goalZ}; staircases join the levels. */
   private static void plan(IsoGridSquare start, int goalZ) {
      IsoCell cell = start.getCell();
      int ox = start.x - R, oy = start.y - R, oz = Math.min(lowest, goalZ) - 1, nz = Math.max(top, goalZ) + 2 - oz;
      int n = W * W * nz;
      int[] prev = new int[n];
      byte[] via = new byte[n]; // 0 plain step; 1..4 staircase: up N, up W, down N, down W
      java.util.Arrays.fill(prev, -2);
      ArrayDeque<Integer> q = new ArrayDeque<>();
      int s = idx(start.x - ox, start.y - oy, start.z - oz);
      prev[s] = -1;
      q.add(s);
      int found = -1;
      int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
      while (!q.isEmpty()) {
         int i = q.poll();
         int x = ox + i % W, y = oy + i / W % W, z = oz + i / (W * W);
         IsoGridSquare a = cell.getGridSquare(x, y, z);
         if (a == null) continue;
         if (z == goalZ) {
            found = i;
            break;
         }
         for (int[] d : nb) {
            IsoGridSquare b = cell.getGridSquare(x + d[0], y + d[1], z);
            if (!passable(a, b)) continue;
            visit(prev, via, q, i, b.x - ox, b.y - oy, z - oz, 0);
         }
         for (int c = 0; c < 2; c++) {
            int dx = CLIMB[c][0], dy = CLIMB[c][1];
            IsoObjectType bottom = c == 0 ? IsoObjectType.stairsBN : IsoObjectType.stairsBW, mid = c == 0 ? IsoObjectType.stairsMN : IsoObjectType.stairsMW,
                  topT = c == 0 ? IsoObjectType.stairsTN : IsoObjectType.stairsTW;
            // up: the three stair squares ahead on this level, the exit past the top one level higher
            if (has(cell, x + dx, y + dy, z, bottom) && has(cell, x + 2 * dx, y + 2 * dy, z, mid) && has(cell, x + 3 * dx, y + 3 * dy, z, topT)) {
               IsoGridSquare e = cell.getGridSquare(x + 4 * dx, y + 4 * dy, z + 1);
               if (e != null && e.isFree(false) && !blocked.contains(pack(e.x, e.y, e.z)) && inside(e.x - ox, e.y - oy, e.z - oz, nz)) {
                  visit(prev, via, q, i, e.x - ox, e.y - oy, e.z - oz, 1 + c);
               }
            }
            // down: this square is the exit past the top step of a staircase one level lower
            if (has(cell, x - dx, y - dy, z - 1, topT) && has(cell, x - 2 * dx, y - 2 * dy, z - 1, mid) && has(cell, x - 3 * dx, y - 3 * dy, z - 1, bottom)) {
               IsoGridSquare e = cell.getGridSquare(x - 4 * dx, y - 4 * dy, z - 1);
               if (e != null && e.isFree(false) && !e.HasStairs() && !blocked.contains(pack(e.x, e.y, e.z)) && inside(e.x - ox, e.y - oy, e.z - oz, nz)) {
                  visit(prev, via, q, i, e.x - ox, e.y - oy, e.z - oz, 3 + c);
               }
            }
         }
      }
      pathIdx = 0;
      pathLen = 0;
      if (found < 0) return;
      // back from the goal: each staircase edge expands into its three stair squares
      int[] bx = new int[4096], by = new int[4096], bz = new int[4096];
      int k = 0;
      for (int i = found; i >= 0 && k < 4090; i = prev[i]) {
         int x = ox + i % W, y = oy + i / W % W, z = oz + i / (W * W);
         bx[k] = x;
         by[k] = y;
         bz[k++] = z;
         int v = via[i];
         if (v != 0) {
            int c = (v - 1) % 2, dx = CLIMB[c][0], dy = CLIMB[c][1];
            if (v <= 2) { // up: exit at +4 steps one level higher; the stair squares at +3, +2, +1 on the lower level
               for (int m = 3; m >= 1; m--) {
                  bx[k] = x - (4 - m) * dx;
                  by[k] = y - (4 - m) * dy;
                  bz[k++] = z - 1;
               }
            } else { // down: exit at -4 steps one level lower; stair squares at -1, -2, -3 from the upper square
               for (int m = 3; m >= 1; m--) {
                  bx[k] = x + (4 - m) * dx;
                  by[k] = y + (4 - m) * dy;
                  bz[k++] = z;
               }
            }
         }
      }
      if (pathX.length < k) {
         pathX = new int[k];
         pathY = new int[k];
         pathZ = new int[k];
      }
      for (int j = 0; j < k; j++) {
         pathX[j] = bx[k - 1 - j];
         pathY[j] = by[k - 1 - j];
         pathZ[j] = bz[k - 1 - j];
      }
      pathLen = k;
      pathIdx = Math.min(1, k); // the first entry is the player's own square
      if (k <= 1) pathLen = 0;
   }

   private static int idx(int x, int y, int z) {
      return (z * W + y) * W + x;
   }

   private static boolean inside(int x, int y, int z, int nz) {
      return x >= 0 && y >= 0 && x < W && y < W && z >= 0 && z < nz;
   }

   private static void visit(int[] prev, byte[] via, ArrayDeque<Integer> q, int from, int x, int y, int z, int v) {
      if (x < 0 || y < 0 || x >= W || y >= W || z < 0 || z * W * W >= prev.length) return;
      int j = idx(x, y, z);
      if (prev[j] != -2) return;
      prev[j] = from;
      via[j] = (byte)v;
      q.add(j);
   }

   private static boolean has(IsoCell cell, int x, int y, int z, IsoObjectType t) {
      IsoGridSquare s = cell.getGridSquare(x, y, z);
      return s != null && s.has(t);
   }

   /** A plain step on one level: never onto or off a stair square sideways (the staircase edges cover the stairs). */
   private static boolean passable(IsoGridSquare a, IsoGridSquare b) {
      if (b == null || b.z != a.z || blocked.contains(pack(b.x, b.y, b.z))) return false;
      if (b.HasStairs() || a.HasStairs() || !b.isFree(false)) return false;
      if (a.isWallTo(b) || a.isWindowBlockedTo(b) || a.isStairBlockedTo(b)) return false;
      if (a.isDoorBlockedTo(b)) {
         IsoObject o = a.getDoorTo(b);
         return o instanceof IsoDoor d && !d.isBarricaded() || o instanceof IsoThumpable t && t.isDoor();
      }
      return true;
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
         if (!c.equals(command)) Log.info("harness: stairs director: " + command + " -> " + c + " (#" + seq + ")");
         // the same go_up / go_down again, decided on a state written after the arrival (a decision takes ~0.6 s): one more level
         if (c.equals(command) && goalLevel != Integer.MIN_VALUE && level == goalLevel && arrivedNs != 0L && System.nanoTime() - arrivedNs > 1_200_000_000L) {
            Log.info("harness: stairs director: " + c + " again (#" + seq + "), one more level");
            goalLevel = Integer.MIN_VALUE;
            arrivedNs = 0L;
         }
         setCommand(c);
      } catch (Exception e) {
         // a half-written file: the next check reads it
      }
   }

   private static void writeState(IsoPlayer p, boolean onStairs, long nowNs) {
      String json = String.format(Locale.ROOT,
            "{\"t\":%d,\"scene\":\"stairs\",\"seconds_since_start\":%.1f,\"current_action\":\"%s\",\"player\":{\"level\":%d,\"on_stairs\":%b,\"moving\":%b,"
                  + "\"seconds_on_this_level\":%.1f,\"arrived_at_goal_level\":%b},\"building\":{\"lowest_level\":%d,\"top_level\":%d},"
                  + "\"progress\":{\"top_floor_reached\":%b,\"back_at_lowest_after_top\":%b,\"level_changes\":%d,\"looked_around_on_this_level\":%b,"
                  + "\"no_stairs_up_from_this_level\":%b,\"no_stairs_down_from_this_level\":%b}}",
            System.currentTimeMillis(), (nowNs - startNs) / 1e9, command, level, onStairs, p.isPlayerMoving(), (nowNs - levelSinceNs) / 1e9,
            goalLevel != Integer.MIN_VALUE && level == goalLevel && !onStairs, lowest, top, reachedTop, backDown, levelChanges, lookedOn.contains(level),
            noStairsUp.contains(level), noStairsDown.contains(level));
      try {
         java.io.File tmp = new java.io.File(stateFile.getPath() + ".tmp");
         java.nio.file.Files.writeString(tmp.toPath(), json);
         java.nio.file.Files.move(tmp.toPath(), stateFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (Exception e) {
         Log.warn("harness: stairs: state write failed: " + e);
      }
   }
}
