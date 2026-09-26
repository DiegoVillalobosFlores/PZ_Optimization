package pzopt;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.textures.Texture;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoTree;

/**
 * Harness scene {@code explore=trees} (2026-09-25, the maintainer's report: with every new lighting setting on together the
 * picture is unstable and trees are lit wrong, their lower part without shadow): the player walks from tree to tree on foot
 * (the movement keys along a grid path, never teleported), circles each one and stands watching it, so the trees are seen
 * from a moving and from a still camera.
 *
 * <p>With {@code director=jev} the next action comes from harness/trees/tree-director.py (TypeSafe's Jev) through
 * {@code Zomboid/pzopt-trees-state.json} / {@code pzopt-trees-cmd.txt}: next_tree, circle_tree, watch, done (hold while
 * nothing else applies). Without it an autopilot plays the same order. Flags: {@code trees_radius} (tiles, 30),
 * {@code trees_max} (trees to visit, 4), {@code trees_watch} (seconds, 3), {@code trees_max_seconds} (the scene's time cap, 120).
 * A tree counts as reached within 2 tiles; one the grid path cannot get nearer to, or that leaves the player standing still
 * for 6 s, is given up (the first version circled a tree 10 tiles away into the Rosewood church wall until the route cap).
 *
 * <p>Every frame the on-screen trees' sprite rectangles go to {@code Zomboid/pzopt-trees.out} (epoch ms, tree id, window px
 * x0 y0 x1 y1, the ground row, window px per level of height), so harness/trees/tree-judge.py can measure the trees in a
 * devCapture sequence or a recording: near-black crowns, frame-to-frame jumps while the camera stands still, the shading
 * of each height band.
 */
public final class TreeWalk {
   private TreeWalk() {
   }

   private static final String[] ACTIONS = {"next_tree", "circle_tree", "watch", "hold", "done"};

   private static boolean on, director, started, finished;
   private static String command = "hold";
   private static int commandSeq = -1, commands, maxTrees;
   private static float radius, watchSecs;
   private static long startNs, lastStateNs, lastCmdCheckNs, lastPlanNs, lastProgressNs, lastLogNs;
   private static java.io.File stateFile, cmdFile;
   private static BufferedWriter boxes;

   private static final ArrayList<IsoTree> trees = new ArrayList<>();
   private static final Set<IsoTree> visited = new HashSet<>();
   private static final Set<IsoTree> unreachable = new HashSet<>();
   private static IsoTree current;
   private static float watched, maxSeconds;
   private static int circlePoint; // explore=trees: the next of the 8 waypoints round the current tree (8 = the lap is done)
   private static long pointSinceNs, stillSinceNs;
   private static float stillX, stillY;

   private static int[] pathX = new int[0], pathY = new int[0];
   private static int pathIdx, pathLen, goalX, goalY;
   private static float progressX, progressY;
   private static final Set<Long> blocked = new HashSet<>();

   public static boolean active() {
      return on;
   }

   public static boolean done() {
      return finished;
   }

   /** World-ready (game thread): the trees around the player. */
   static void worldReady(IsoPlayer p) {
      on = "trees".equalsIgnoreCase(HarnessFlags.get("explore", "").trim());
      if (!on) {
         return;
      }
      director = "jev".equalsIgnoreCase(HarnessFlags.get("director", "").trim());
      radius = Float.parseFloat(HarnessFlags.get("trees_radius", "30").trim());
      maxTrees = Integer.parseInt(HarnessFlags.get("trees_max", "4").trim());
      watchSecs = Float.parseFloat(HarnessFlags.get("trees_watch", "3").trim());
      maxSeconds = Float.parseFloat(HarnessFlags.get("trees_max_seconds", "120").trim());
      java.io.File z = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir());
      stateFile = new java.io.File(z, "pzopt-trees-state.json");
      cmdFile = new java.io.File(z, "pzopt-trees-cmd.txt");
      cmdFile.delete();
      try {
         boxes = new BufferedWriter(new FileWriter(new java.io.File(z, "pzopt-trees.out")));
         boxes.write("# epoch_ms tree_id x0 y0 x1 y1 ground_y px_per_level (window px) | epoch_ms P player_x player_y moving action screen_w screen_h zoom off_x off_y\n");
      } catch (java.io.IOException e) {
         Log.warn("harness: explore=trees: " + e);
      }
      p.getCheats().set(zombie.characters.CheatType.GOD_MODE, true);
      p.setInvisible(true, true);
   }

   private static void findTrees(IsoPlayer p) {
      IsoCell cell = IsoWorld.instance.currentCell;
      int r = (int)radius, px = (int)p.getX(), py = (int)p.getY();
      ArrayList<Object[]> found = new ArrayList<>();
      for (int y = py - r; y <= py + r; y++) {
         for (int x = px - r; x <= px + r; x++) {
            IsoGridSquare sq = cell.getGridSquare(x, y, 0);
            if (sq == null) continue;
            IsoTree t = sq.getTree();
            if (t == null) continue;
            float d = (float)Math.hypot(x + 0.5F - p.getX(), y + 0.5F - p.getY());
            if (d <= radius) found.add(new Object[] {d, t});
         }
      }
      found.sort((a, b) -> Float.compare((Float)a[0], (Float)b[0]));
      for (Object[] o : found) trees.add((IsoTree)o[1]);
   }

   static void routeStart(IsoPlayer p) {
      if (!on || started) return;
      started = true;
      startNs = System.nanoTime();
      lastProgressNs = startNs;
      if (p.getVehicle() != null) {
         p.getVehicle().exit(p);
      }
      command = director ? "hold" : "next_tree";
      // the trees are chosen here, after the settle (at world-ready only part of the map is loaded: the list, and so the
      // walk, differed run to run), and only the ones the grid path reaches, nearest first: every A/B run walks the same
      findTrees(p);
      IsoGridSquare cur = p.getCurrentSquare();
      ArrayList<IsoTree> reachable = new ArrayList<>();
      for (IsoTree t : trees) {
         if (reachable.size() >= maxTrees) break;
         if (cur == null || t.square == null || !t.square.isOutside()) continue;
         plan(cur, t.square.x, t.square.y, 2.5F);
         if (pathReaches) reachable.add(t);
      }
      pathLen = 0;
      trees.clear();
      trees.addAll(reachable);
      StringBuilder sb = new StringBuilder();
      for (IsoTree t : trees) sb.append(' ').append(t.square.x).append(',').append(t.square.y);
      Log.info(String.format(Locale.ROOT, "harness: explore=trees: %d reachable trees within %.0f tiles:%s%s", trees.size(), radius, sb,
            director ? ", director jev" : ", autopilot"));
   }

   /** Per frame while the run is live (game thread). */
   static void tick(IsoPlayer p, long nowNs) {
      if (!on) return;
      logBoxes(p, nowNs);
      if (!started || finished) return;
      float dt = Math.min(0.1F, zombie.GameTime.getInstance().getRealworldSecondsSinceLastUpdate());
      if (director) {
         if (nowNs - lastCmdCheckNs >= 100_000_000L) {
            lastCmdCheckNs = nowNs;
            readCommand();
         }
      } else {
         autopilot();
      }
      Showcase.releaseKeys();
      if ((nowNs - startNs) / 1e9 > maxSeconds) {
         command = "done"; // the scene's time cap (trees_max_seconds): a walk that cannot finish still ends
         Log.info("harness: explore=trees: time cap reached");
      }
      // watchdog: a walking command that has not moved the player for 6 s gives the current tree up (a wall the grid did not see)
      boolean walking = "next_tree".equals(command) || "circle_tree".equals(command);
      if (!walking || Math.hypot(p.getX() - stillX, p.getY() - stillY) > 0.3) {
         stillX = p.getX();
         stillY = p.getY();
         stillSinceNs = nowNs;
      } else if (current != null && nowNs - stillSinceNs > 6_000_000_000L) {
         Log.info(String.format(Locale.ROOT, "harness: explore=trees: stuck 6 s at %.1f,%.1f (%s), tree at %d,%d given up", p.getX(), p.getY(), command,
               current.square.x, current.square.y));
         giveUp();
         stillSinceNs = nowNs;
      }
      switch (command) {
         case "next_tree" -> {
            if (current == null || finishedCurrent()) {
               current = nextTree(p);
               circlePoint = 0;
               pointSinceNs = 0L; // walkCircle starts the lap from the player's side
               watched = 0F;
               if (current != null) {
                  Log.info(String.format(Locale.ROOT, "harness: explore=trees: next tree %s at %d,%d (+%.1f s)", spriteName(current),
                        current.square.x, current.square.y, (nowNs - startNs) / 1e9));
               }
            }
            if (current != null && !visited.contains(current)) {
               int r = walkTo(p, nowNs, current.square.x, current.square.y, 2.5F, true);
               if (r > 0) {
                  visited.add(current);
               } else if (r < 0) {
                  Log.info("harness: explore=trees: no way to the tree at " + current.square.x + "," + current.square.y + ", next one");
                  giveUp();
               }
            }
         }
         case "circle_tree" -> {
            if (current != null) walkCircle(p, nowNs);
         }
         case "watch" -> {
            if (current != null && !p.isPlayerMoving()) watched += dt;
         }
         case "done" -> {
            finished = true;
            Log.info(String.format(Locale.ROOT, "harness: explore=trees: done at +%.1f s, %d trees visited, %d commands",
                  (nowNs - startNs) / 1e9, visited.size(), commands));
            flushBoxes();
         }
         default -> { // hold
         }
      }
      if (director && nowNs - lastStateNs >= 300_000_000L) {
         lastStateNs = nowNs;
         writeState(p, nowNs);
      }
   }

   private static boolean circled() {
      return circlePoint >= 8;
   }

   private static boolean finishedCurrent() {
      return unreachable.contains(current) || visited.contains(current) && circled() && watched >= watchSecs;
   }

   /** The current tree cannot be walked to or round: it counts as finished (unreachable), the walk goes on. */
   private static void giveUp() {
      unreachable.add(current);
      visited.remove(current);
      circlePoint = 8;
      watched = watchSecs;
      pathLen = 0;
   }

   private static IsoTree nextTree(IsoPlayer p) {
      if (visited.size() + unreachable.size() >= maxTrees + 3 || visited.size() >= maxTrees) return null;
      IsoTree best = null;
      float bestD = Float.MAX_VALUE;
      for (IsoTree t : trees) {
         if (visited.contains(t) || unreachable.contains(t) || t.square == null || t.getObjectIndex() < 0 || !t.square.isOutside()) continue;
         float d = (float)Math.hypot(t.square.x + 0.5F - p.getX(), t.square.y + 0.5F - p.getY());
         if (d < 3F) continue; // not the one the player stands at
         if (d < bestD) {
            bestD = d;
            best = t;
         }
      }
      return best;
   }

   /**
    * One lap round the current tree: 8 waypoints 2.5 tiles out, clockwise from the player's side, each walked on the grid
    * path (walls and fences walked round); a waypoint not reached within 3 s is skipped (a wall, a building).
    */
   private static void walkCircle(IsoPlayer p, long nowNs) {
      if (circled()) return;
      int cx = current.square.x, cy = current.square.y;
      double a0 = Math.atan2(p.getY() - cy - 0.5, p.getX() - cx - 0.5);
      double a = circlePoint == 0 && pointSinceNs == 0L ? a0 : circleBase + circlePoint * Math.PI / 4.0;
      if (circlePoint == 0 && pointSinceNs == 0L) {
         circleBase = a0 + Math.PI / 4.0;
      }
      int gx = (int)Math.floor(cx + 0.5 + 2.5 * Math.cos(circleBase + circlePoint * Math.PI / 4.0));
      int gy = (int)Math.floor(cy + 0.5 + 2.5 * Math.sin(circleBase + circlePoint * Math.PI / 4.0));
      if (pointSinceNs == 0L) {
         pointSinceNs = nowNs;
      }
      int r = walkTo(p, nowNs, gx, gy, 1.2F, false);
      if (r != 0 || nowNs - pointSinceNs > 3_000_000_000L) {
         circlePoint++;
         pointSinceNs = nowNs;
         pathLen = 0;
      }
   }

   private static double circleBase;

   // ---- walking: a grid path over the loaded ground-level squares ----

   /**
    * 1 once the player stands within {@code tol} tiles of the goal square, 0 while walking, -1 when no free square that
    * close can be reached (a tree behind a fence or in a yard). {@code run}: runs outdoors.
    */
   private static int walkTo(IsoPlayer p, long nowNs, int gx, int gy, float tol, boolean run) {
      IsoGridSquare cur = p.getCurrentSquare();
      if (cur == null) return 0;
      if (Math.hypot(gx + 0.5F - p.getX(), gy + 0.5F - p.getY()) < tol) {
         pathLen = 0;
         return 1;
      }
      boolean stuck = pathLen > 0 && nowNs - lastProgressNs > 1_500_000_000L;
      if (stuck) {
         blocked.add(key(pathX[Math.min(pathIdx, pathLen - 1)], pathY[Math.min(pathIdx, pathLen - 1)]));
      }
      if (gx != goalX || gy != goalY || stuck || pathIdx >= pathLen || nowNs - lastPlanNs > 2_000_000_000L) {
         goalX = gx;
         goalY = gy;
         lastPlanNs = nowNs;
         lastProgressNs = nowNs;
         progressX = p.getX();
         progressY = p.getY();
         plan(cur, gx, gy, tol);
         if (pathLen == 0 || !pathReaches) return -1; // nowhere nearer (it used to count as reached: the walk then circled a tree 10 tiles away into a church wall)
      }
      if (Math.hypot(p.getX() - progressX, p.getY() - progressY) > 0.4) {
         progressX = p.getX();
         progressY = p.getY();
         lastProgressNs = nowNs;
      }
      while (pathIdx < pathLen && Math.hypot(pathX[pathIdx] + 0.5F - p.getX(), pathY[pathIdx] + 0.5F - p.getY()) < 0.35F) {
         pathIdx++;
      }
      if (pathIdx >= pathLen) return 0;
      Showcase.moveKeys(pathX[pathIdx] + 0.5F - p.getX(), pathY[pathIdx] + 0.5F - p.getY());
      if (run && cur.isOutside()) Showcase.holdKey("Run");
      return 0;
   }

   private static long key(int x, int y) {
      return ((long)x << 32) ^ (y & 0xFFFFFFFFL);
   }

   private static boolean pathReaches;

   /** Breadth-first over the loaded squares around the player to the first square within {@code tol} of the goal, else to the reachable square nearest it. */
   private static void plan(IsoGridSquare start, int gx, int gy, float tol) {
      IsoCell cell = start.getCell();
      int r = 60, w = 2 * r + 1, ox = start.x - r, oy = start.y - r, z = start.z;
      int[] prev = new int[w * w];
      java.util.Arrays.fill(prev, -2);
      ArrayDeque<Integer> q = new ArrayDeque<>();
      int s = (start.y - oy) * w + (start.x - ox);
      prev[s] = -1;
      q.add(s);
      int found = -1, nearest = s;
      float nearestD = (float)Math.hypot(start.x - gx, start.y - gy);
      int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
      while (!q.isEmpty()) {
         int i = q.poll();
         int x = ox + i % w, y = oy + i / w;
         IsoGridSquare a = cell.getGridSquare(x, y, z);
         if (a == null) continue;
         if (Math.hypot(x - gx, y - gy) < tol) {
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
            if (b == null || blocked.contains(key(nx, ny)) || !b.isFree(false) || a.isWallTo(b) || a.isWindowBlockedTo(b) || a.isDoorBlockedTo(b)) continue;
            prev[j] = i;
            q.add(j);
         }
      }
      pathReaches = found >= 0;
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
      pathLen = n <= 1 ? 0 : n;
      pathIdx = Math.min(1, n);
   }

   // ---- the trees' screen rectangles, per frame ----

   private static String spriteName(IsoTree t) {
      return t.getSprite() != null && t.getSprite().name != null ? t.getSprite().name : "?";
   }

   private static void logBoxes(IsoPlayer p, long nowNs) {
      if (boxes == null || IsoCamera.frameState == null) return;
      try {
         long ms = System.currentTimeMillis();
         float zoom = Core.getInstance().getZoom(0);
         float offX = IsoCamera.getOffX(), offY = IsoCamera.getOffY();
         int ts = Core.tileScale;
         int sw = IsoCamera.getScreenWidth(0), sh = IsoCamera.getScreenHeight(0);
         for (IsoTree t : trees) {
            if (t.square == null || t.getSprite() == null) continue;
            Texture tex = t.getSprite().getTextureForCurrentFrame(t.getForwardIsoDirection(), t);
            if (tex == null) continue;
            float scale = TreeBake.spriteScale(ts, tex.getWidthOrig(), tex.getHeightOrig());
            int x = t.square.x, y = t.square.y, z = t.square.z;
            float sx = (x - y) * (32 * ts), sy = (x + y) * (16 * ts) - z * (96 * ts);
            float x0 = (sx - TreeBake.offsetX(spriteName(t), ts) - offX) / zoom, y0 = (sy - TreeBake.offsetY(spriteName(t), ts) - offY) / zoom;
            float x1 = x0 + tex.getWidthOrig() * scale / zoom, y1 = y0 + tex.getHeightOrig() * scale / zoom;
            if (x1 < 0 || y1 < 0 || x0 > sw || y0 > sh) continue;
            boxes.write(String.format(Locale.ROOT, "%d %d %.1f %.1f %.1f %.1f %.1f %.2f\n", ms, System.identityHashCode(t) & 0xFFFFFF,
                  x0, y0, x1, y1, (sy + 32 * ts - offY) / zoom, 96 * ts / zoom));
         }
         boxes.write(String.format(Locale.ROOT, "%d P %.2f %.2f %b %s %d %d %.4f %.1f %.1f\n", ms, p.getX(), p.getY(), p.isPlayerMoving(), command, sw, sh, zoom, offX, offY));
         if (nowNs - lastLogNs > 1_000_000_000L) {
            lastLogNs = nowNs;
            boxes.flush();
         }
      } catch (java.io.IOException e) {
         boxes = null;
      }
   }

   private static void flushBoxes() {
      try {
         if (boxes != null) boxes.flush();
      } catch (java.io.IOException e) {
         // the run ends anyway
      }
   }

   // ---- the director (Jev) ----

   /** Without a director: to the next tree, circle it, watch it, next. */
   private static void autopilot() {
      String next;
      if (current == null || !visited.contains(current)) {
         next = visited.size() >= maxTrees || current == null && !visited.isEmpty() && nextTreeless() ? "done" : "next_tree";
      } else if (!circled()) {
         next = "circle_tree";
      } else if (watched < watchSecs) {
         next = "watch";
      } else {
         next = visited.size() >= maxTrees ? "done" : "next_tree";
      }
      if (!next.equals(command)) {
         Log.info("harness: explore=trees autopilot: " + command + " -> " + next);
         command = next;
      }
   }

   private static boolean nextTreeless() {
      return trees.size() <= visited.size();
   }

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
            Log.info("harness: explore=trees director: " + command + " -> " + c + " (#" + seq + ")");
         }
         command = c;
      } catch (Exception e) {
         // a half-written file: the next check reads it
      }
   }

   private static void writeState(IsoPlayer p, long nowNs) {
      boolean at = current != null && (visited.contains(current) || unreachable.contains(current));
      float dist = current == null ? -1F : (float)Math.hypot(current.square.x + 0.5F - p.getX(), current.square.y + 0.5F - p.getY());
      int left = 0;
      for (IsoTree t : trees) left += !visited.contains(t) && !unreachable.contains(t) && t.square != null && t.square.isOutside() ? 1 : 0;
      left = Math.min(left, Math.max(0, maxTrees - visited.size()));
      String json = String.format(Locale.ROOT,
            "{\"t\":%d,\"seconds_since_start\":%.1f,\"current_action\":\"%s\",\"player\":{\"moving\":%b},"
                  + "\"trees\":{\"to_visit_total\":%d,\"visited\":%d,\"left_to_visit\":%d},"
                  + "\"current_tree\":{\"chosen\":%b,\"reached\":%b,\"distance_tiles\":%.1f,\"circled_once\":%b,\"degrees_circled\":%.0f,"
                  + "\"seconds_watched\":%.1f,\"seconds_to_watch\":%.1f}}",
            System.currentTimeMillis(), (nowNs - startNs) / 1e9, command, p.isPlayerMoving(), Math.min(maxTrees, trees.size()), visited.size(), left,
            current != null, at, dist, circled(), Math.min(8, circlePoint) * 45.0, watched, watchSecs);
      try {
         java.io.File tmp = new java.io.File(stateFile.getPath() + ".tmp");
         java.nio.file.Files.writeString(tmp.toPath(), json);
         java.nio.file.Files.move(tmp.toPath(), stateFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (Exception e) {
         Log.warn("harness: explore=trees: state write failed: " + e);
      }
   }
}
