package pzopt;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.input.GameKeyboard;
import zombie.iso.IsoChunkMap;
import zombie.vehicles.BaseVehicle;

/**
 * Hands-off benchmark driver, run on the game thread once per frame from
 * Stats.frameTick. Reads Zomboid/Lua/pzopt-harness.txt (written by
 * harness/run.sh, also read by the Lua mod that auto-continues the save).
 *
 * Keys it uses:
 *   mode     bench|parity|drive  drive the legacy route or validate and run a vehicle route; anything else: inactive
 *   route    legs      e.g. "E:600,S:600,W:600,N:600" — direction and length in tiles (drive: total distance; first leg = spawn heading)
 *   vehicle  script    drive mode: vehicle spawned on the nearest road when the player is on foot (default the race car; "none" = fixture required)
 *   kmh      km/h      drive mode: cruise-control speed (default 60); the route follows the road (roadFollow)
 *   path     x,y/x,y/… drive mode: drive this centreline instead (DrivePath, corners rounded to corner_radius, 10): the
 *                      car starts on the first point facing the second (the player is teleported there when it is far
 *                      or not loaded), DrivePilot steers and plans the speed (corners, obstacles, a stop at the last
 *                      point), the route is complete when it stops there; route= is ignored. 20 Hz telemetry in
 *                      pzopt-drive.out, drive_* lines in pzopt-bench.out; harness/drive_check.py judges the drive.
 *                      Tuning flags: see startPilot(). harness/drive-path.py builds paths from the map's streets.xml
 *   speed    tiles/s   default 18 (about car speed on a road)
 *   start    X,Y       bench/parity: teleport the player to this world square at world-ready, before the settle
 *                      time (the route then begins there). Any distance: IsoChunkMap.ProcessChunkPos unloads the
 *                      grid and reloads it around the new square, like the debug teleport. Give the streamer a
 *                      longer settle (the Louisville preset uses 20 s) so the load burst is over before the route
 *   population N|max   sandbox zombie population multipliers forced at world-ready (see Scene)
 *   turn     deg/s     bench/parity: spin the player's facing at this rate along the route so the view cone, lighting
 *                      cone and cutaways keep changing (default 0 = keep the save's facing)
 *   settle   seconds   wait after the world is up before moving (default 15; harness/run.sh passes 5: the
 *                      load burst and the forced-zoom bake are over ~2 s after the world is up)
 *   zoom     max|level  force the camera zoom before the route (auto-zoom off); drive mode defaults to max, other modes keep the save's zoom
 *   zoom_cycle secs    bench/drive: every this many seconds on the route move the target zoom one level, like one mouse-wheel
 *                      notch (the ease in MultiTextureFBO2.update runs), in to the closest level then back out; each step is a
 *                      Stats mark "zoom-<level>" (harness/zoomsteps.py aligns the frame times to them)
 *   zoom_jump  true    with zoom_cycle: set the zoom to the level at once (no ease), the worst case for the chunk-texture bakes
 *   zoom_span  N       with zoom_cycle: levels per step (default 1; 9 = the whole 0.25..2.5 range, a fast wheel spin)
 *   max_seconds        drive mode: give up (route_status=timeout) after this long on the route (default 90)
 *   jitter   tiles     bench/parity, with hold: every frame of the hold the player's X alternates between the end
 *                      square's east edge minus and plus this much (e.g. 0.05), i.e. the square under the player
 *                      flips every frame, like zombies shoving a player standing on a roof edge (carport flicker rig)
 *   shot_burst N       bench/parity with hold: 1 s into the hold write N in-game screenshots on N consecutive frames,
 *                      Screenshots/pzopt-burst-NN.png (with jitter: alternating positions, frame-exact flicker checks)
 *   hold     seconds   bench/parity: after the last leg wait this long before quitting (no teleports: the player may walk); the turn
 *                      keeps spinning the facing (a still camera with changing cutaways, for flicker recordings)
 *   shot_at  seconds   bench/parity: this far into the route hold the camera (no teleport, no turn) for 6 s and,
 *                      2 s into the hold, write Zomboid/Screenshots/pzopt-shot.png (Core.TakeFullScreenshot) and
 *                      touch Zomboid/pzopt-shot.now so run.sh can take a desktop capture too (artifact checks)
 *   find     curtains  dev: at route start list the curtains within 100 tiles (type, open state, attached window,
 *                      room) so a screenshot run can be started inside such a room with start=X,Y (issue #4)
 *   upstairs Z         dev: at route start move the player to the nearest loaded indoor square at level Z (issue #12)
 *   upstairs_at S      dev: with upstairs, make that move S seconds into the route instead (a Stats mark "upstairs")
 *   close_curtains true dev: at route start close every open curtain in that range through IsoCurtain.ToggleDoor
 *                      (map curtains always load open; the bench save is a copy, nothing persists)
 *   route_start_epoch  unix seconds: do not start the route before this instant (puts the route on the
 *                      schedule the external MangoHud log was configured for); absent = the route starts
 *                      settle seconds after the world is up, whenever that is
 *   mangohud_end_epoch unix seconds at which the MangoHud log closes; the game stays up until then
 *                      (a few seconds past the route end on schedule), because MangoHud only writes
 *                      its CSV if the log ends while the game runs
 *   mangohud_secs      length of the external log; when mangohud_end_epoch is absent the log is assumed to
 *                      start 3 s before the route (run.sh starts it via mangohudctl) and the linger is derived
 *
 * Whatever the flags, the instant the route will start is published as soon as the world is up in
 * Zomboid/pzopt-schedule.out (world_ready_epoch_ms, route_start_epoch_ms, log_end_epoch_ms): run.sh
 * waits for that file and times the external log and the fps-metrics reset off it. When run.sh has
 * stopped the log itself it drops Zomboid/pzopt-logdone, which ends the linger early.
 *
 * bench and parity retain the teleport control route. drive uses the vehicle's
 * normal CarController input path and completes from observed vehicle
 * displacement, so it never teleports the player or removes them from the
 * vehicle.
 *
 * Multiplayer client (GameClient.client, harness/mp/run.sh, 2026-09-21): vehicles belong to the server, so
 * drive mode leaves any vehicle the character was saved in, applies start= (MP_TELEPORT: waits for the
 * square to load), asks the server for the vehicle with the admin command "/addvehicle <script> x,y,0" on
 * the nearest road and waits for it to stream in (MP_VEHICLE, the request is repeated at 8 s because the
 * first one lands before the server has the chunk), seats the player, and turns the physics body only
 * once the client holds the vehicle's authority (MP_ALIGN; a rotation set earlier is overwritten by the
 * next VehicleUpdate). mpCleanup asks the server to remove leftover vehicles in the route corridor at the
 * start and the end of a run (the harness car included), so runs do not pile cars up at the route end.
 */
public final class Harness {
   /** True when the flag file asks for a bench run; decided once at class init so the frame hook stays a boolean test. */
   public static final boolean REQUESTED = "bench".equals(HarnessFlags.get("mode")) || "parity".equals(HarnessFlags.get("mode")) || "drive".equals(HarnessFlags.get("mode"))
         || "play".equals(HarnessFlags.get("mode"));
   /** play: a copy of a real save with the scene flags applied (weather, hour, torch) and the player left alone: no god mode, no ghost, no route, no quit. */
   private static final boolean PLAYING = "play".equals(HarnessFlags.get("mode"));
   private static final int IDLE = 0, WAIT_WORLD = 1, SETTLE = 2, RUN = 3, LINGER = 5, DONE = 4, PLAY = 6, MP_VEHICLE = 7, MP_ALIGN = 8, MP_TELEPORT = 9, PATH_START = 10;
   /** drive mode with flag path=: the route's centreline, its driver and what it sees (DrivePilot, 2026-09-24). */
   private static DrivePath drivePath;
   private static DrivePilot pilot;
   private static DriveSenses senses;
   private static String pathError;
   private static final DrivePilot.Input pilotIn = new DrivePilot.Input();
   private static final DrivePilot.Output pilotOut = new DrivePilot.Output();
   private static final org.joml.Vector3f pilotFwd = new org.joml.Vector3f();
   private static final StringBuilder driveRows = new StringBuilder(DrivePilot.HEADER + "\n");
   private static long lastDriveRowNs;
   /** Multiplayer client (drive mode): the road square the server was asked to put the vehicle on, and when. */
   private static zombie.iso.IsoGridSquare mpRoad;
   private static zombie.iso.IsoDirections mpDir;
   private static long mpAskedNs;
   private static long mpLogSlot = -1;
   private static long mpSeatedNs;
   private static int mpStartX, mpStartY;
   private static boolean mpRepeated;
   private static long lingerUntilEpochMs;
   /** Instant the route starts (unix ms), fixed at world-ready: max(route_start_epoch, world ready + settle). */
   private static long plannedStartEpochMs;
   /** Derived end of the external log when mangohud_end_epoch is absent (0 = none). */
   private static long derivedLogEndEpochMs;
   private static int state = IDLE;
   private static boolean started;
   private static final String DEFAULT_ROUTE = "E:400,S:500,W:400,N:500";
   /** drive mode: vehicle script spawned under the player when the save has them on foot ("none" = require a fixture). */
   private static final String DEFAULT_VEHICLE = "Base.RaceCar12"; // fastest script: CarRacecar template, maxSpeed 120, engineForce 7000
   private static boolean vehicleSpawned;
   private static long lastTelemetryNs;
   private static boolean reseated;
   private static long quitRequestedEpochMs;
   private static long stateSinceNs;
   private static long lastFrameNs;
   private static float speed = 18f;
   /** bench: degrees per second the player facing rotates while on the route (flag turn, 0 = off). */
   private static float turnDegPerSec = 0f;
   private static float turnAngle = 0f;
   /** bench/drive: seconds between one-level camera zoom steps on the route (flag zoom_cycle, 0 = off). */
   private static float zoomCycleSecs = 0f;
   private static boolean zoomJump; // flag zoom_jump: the zoom is set to the level at once instead of the wheel's ease
   private static int zoomSpan = 1; // flag zoom_span: levels per step
   private static int zoomDir = 1; // +1 = zooming in (smaller value), -1 = zooming out
   private static long lastZoomStepNs;
   private static int zoomSteps;
   private static final int ZOOM_TRACE_FRAMES = 90;
   private static int zoomTraceLeft; // frames still to trace after the last step
   private static long zoomTraceBakes, zoomTraceDeferred, zoomTraceFrameNs;
   private static final StringBuilder zoomTrace = new StringBuilder();
   private static long zoomTraceReturned, zoomTraceRebakes, zoomTraceCreations, zoomTraceUrgent, zoomTracePlaceholders, zoomTraceFlood;
   private static final int[] zoomTraceUrgentFlags = new int[16];
   private static final long[] zoomTraceCf = new long[4];
   private static final int[] zoomTraceCfFlags = new int[16];
   /** bench: seconds into the route at which the camera is held for a screenshot (flag shot_at, 0 = off). */
   private static float shotAt = 0f;
   /** bench: seconds to stay on the route's end square, still spinning, before the run ends (flag hold, 0 = off). */
   private static float holdSecs = 0f;
   /** bench: hold-time X oscillation across the end square's east edge, in tiles (flag jitter, 0 = off). */
   private static float jitterTiles = 0f;
   private static float jitterEdgeX;
   private static boolean jitterSide;
   private static long holdStartNs = 0L;
   private static int shotPhase; // 0 = pending, 1 = holding, 2 = done
   /** bench/parity with hold: this many consecutive-frame screenshots 1 s into the hold (flag shot_burst, 0 = off). */
   private static int shotBurst;
   private static boolean jitterY; // flag jitter_y: jitter the Y instead of the X
   private static int burstTaken;
   private static long shotHoldNs;
   private static boolean shotRequested, shot2Requested;
   private static float settle = 15f;
   private static final List<float[]> legs = new ArrayList<>(); // {dx, dy, length}
   private static int leg = 0;
   private static float legDone = 0f;
   private static float x, y;
   private static int routeZ; // the level the bench route walks on (upstairs=Z moves it)
   private static float upstairsAt; // upstairs_at: seconds into the route of the level change (0 = at route start)
   private static float startX, startY;
   private static int chunksAtStart;
   private static long runStartNs;
   private static long runStartEpochMs, runEndEpochMs; // wall clock, to align external (MangoHud) logs
   private static boolean driving;
   private static BaseVehicle vehicle;
   private static float vehicleStartX, vehicleStartY;
   private static float drivenDistance;
   private static boolean rejected;
   private static String routeStatus = "complete";
   private static float maxSeconds = 90f;
   /** drive mode: cruise-control (regulator) speed in km/h the vehicle is driven at (flag kmh, default 60). */
   private static float cruiseKmh = 60f; // road speed; curves are followed by roadFollow(), so flat out is not needed
   /** Per-thread CPU time at route start (thread id -> ns), for pzopt-threads.out. */
   private static Map<Long, Long> cpuAtStart;
   private static long processCpuAtStart;

   private Harness() {
   }

   static boolean active() {
      return state != IDLE && state != DONE && state != LINGER;
   }

   /** True when the MangoHud overlay library is mapped into this process (Linux only). */
   static boolean mangoHudLoaded() {
      try {
         return java.nio.file.Files.lines(java.nio.file.Path.of("/proc/self/maps")).anyMatch(l -> l.contains("MangoHud") || l.contains("mangohud"));
      } catch (Exception e) {
         return false;
      }
   }

   /**
    * Leave the world. Core.quit() only sets Core.exiting for IngameState to act on; the Lua mod then
    * ends the process at the main menu (started=1 in the flag file). If the process is still in the
    * world 10 s later, quitToDesktop() (GameWindow.closeRequested), and 10 s after that System.exit:
    * a benchmark run must never sit in the world waiting for a click.
    */
   /** Publishes the instants run.sh needs to time the external log; written whole, then renamed into place. */
   private static void writeSchedule(long worldReadyMs) {
      File dir = new File(ZomboidFileSystem.instance.getCacheDir());
      File tmp = new File(dir, "pzopt-schedule.out.tmp");
      File f = new File(dir, "pzopt-schedule.out");
      try (java.io.FileWriter w = new java.io.FileWriter(tmp)) {
         w.write("world_ready_epoch_ms=" + worldReadyMs + "\n");
         w.write("route_start_epoch_ms=" + plannedStartEpochMs + "\n");
         w.write("log_end_epoch_ms=" + derivedLogEndEpochMs + "\n");
         w.write("settle=" + settle + "\n");
      } catch (java.io.IOException e) {
         Log.warn("harness: could not write " + tmp + ": " + e);
         return;
      }
      if (!tmp.renameTo(f)) Log.warn("harness: could not rename " + tmp + " to " + f);
   }

   private static void requestQuit() {
      quitRequestedEpochMs = System.currentTimeMillis();
      Log.info("harness: quit requested");
      Overlay.flushLog();
      Pacing.flushLog();
      GameThreadProfile.flushLog();
      Core.getInstance().quit();
      // frames stop once the world is gone, so the escalation cannot rely on onFrame:
      // a timer thread closes the window after 15 s and ends the process after 30 s
      Thread t = new Thread(() -> {
         try {
            Thread.sleep(15_000L);
            Log.warn("harness: process still alive 15 s after quit; requesting window close");
            Core.getInstance().quitToDesktop();
            Thread.sleep(15_000L);
            Log.warn("harness: process still alive 30 s after quit; exiting");
            Stats.flush();
            System.exit(0);
         } catch (InterruptedException ignored) {
         }
      }, "pzopt-quit");
      t.setDaemon(true);
      t.start();
   }

   /** Quit now, or after the external log has closed if the runner asked for that and the logger is actually present. */
   private static void quitWhenLogsAreDone() {
      long end = Long.parseLong(HarnessFlags.get("mangohud_end_epoch", "0")) * 1000L;
      if (end == 0) end = derivedLogEndEpochMs;
      long wait = end - System.currentTimeMillis();
      if (wait > 0 && wait < 600_000L && mangoHudLoaded()) {
         Log.info("harness: lingering " + wait / 1000 + "s for the MangoHud log to close");
         lingerUntilEpochMs = end;
         state = LINGER;
         return;
      }
      state = DONE;
      requestQuit(); // to the main menu (saves); the Lua mod then quits the process
   }

   /** First call decides whether a harness run is requested; afterwards drives the state machine. */
   public static void onFrame(long nowNs) {
      if (state == DONE) {
         return;
      }
      if (!started) {
         started = true;
         if (!REQUESTED) {
            state = DONE;
            return;
         }
         speed = Float.parseFloat(HarnessFlags.get("speed", "18"));
         turnDegPerSec = Float.parseFloat(HarnessFlags.get("turn", "0"));
         zoomCycleSecs = Float.parseFloat(HarnessFlags.get("zoom_cycle", "0"));
         zoomJump = "true".equals(HarnessFlags.get("zoom_jump", "false"));
         zoomSpan = Math.max(1, Integer.parseInt(HarnessFlags.get("zoom_span", "1")));
         shotAt = Float.parseFloat(HarnessFlags.get("shot_at", "0"));
         holdSecs = Float.parseFloat(HarnessFlags.get("hold", "0"));
         jitterTiles = Float.parseFloat(HarnessFlags.get("jitter", "0"));
         shotBurst = Integer.parseInt(HarnessFlags.get("shot_burst", "0"));
         jitterY = "true".equals(HarnessFlags.get("jitter_y", "false"));
         settle = Float.parseFloat(HarnessFlags.get("settle", "15"));
         maxSeconds = Float.parseFloat(HarnessFlags.get("max_seconds", "90"));
         cruiseKmh = Float.parseFloat(HarnessFlags.get("kmh", "60"));
          parseRoute(HarnessFlags.get("route", DEFAULT_ROUTE));
          driving = "drive".equals(HarnessFlags.get("mode"));
         String pathFlag = HarnessFlags.get("path", "").trim();
         if (driving && !pathFlag.isEmpty()) {
            try {
               drivePath = DrivePath.build(DrivePath.parseWaypoints(pathFlag), Float.parseFloat(HarnessFlags.get("corner_radius", "10")));
               Log.info(String.format(java.util.Locale.ROOT, "harness: drive path %s: %.0f tiles, %d samples, starts %.1f,%.1f heading %.0f deg",
                     pathFlag, drivePath.length, drivePath.n, drivePath.x[0], drivePath.y[0], Math.toDegrees(Math.atan2(drivePath.hy[0], drivePath.hx[0]))));
            } catch (RuntimeException e) {
               pathError = "bad path flag '" + pathFlag + "': " + e.getMessage(); // rejected once the world is up (the summary needs it)
            }
         }
         Log.info("harness: " + HarnessFlags.get("mode") + " mode, route=" + (drivePath != null ? "path" : HarnessFlags.get("route", DEFAULT_ROUTE)) + (driving ? " cruise=" + cruiseKmh + " km/h" : " speed=" + speed + " tiles/s") + " settle=" + settle + "s");
         state = WAIT_WORLD;
         stateSinceNs = nowNs;
      }
      float dt = lastFrameNs == 0L ? 0f : (nowNs - lastFrameNs) / 1e9f;
      lastFrameNs = nowNs;
      IsoPlayer p = IsoPlayer.getInstance();
      if (p != null && (state == SETTLE || state == RUN || state == PLAY)) {
         Scene.tick(p, nowNs); // keeps the forced weather pinned and fires the scheduled lightning
      }
      switch (state) {
         case WAIT_WORLD -> {
            if (p != null && p.getCurrentSquare() != null) {
                HarnessFlags.markStarted();
                if (PLAYING) {
                   // play mode: the scene is forced, the player keeps their save's state and the game stays open
                   try {
                      Scene.apply(p);
                   } catch (Exception e) {
                      Log.warn("harness: scene setup failed: " + e);
                   }
                   if ("1".equals(HarnessFlags.get("inputlag"))) {
                      // the input-lag rig stands the player on the bench save for ~2 min of scripted input
                      p.setGodMod(true, true);
                      p.setInvisible(true, true);
                   }
                   Log.info("harness: play mode, world ready at " + p.getXi() + "," + p.getYi() + "," + (int)p.getZ() + "; scene applied, no route, quit when you like");
                   state = PLAY;
                   stateSinceNs = nowNs;
                   return;
                }
                p.setGodMod(true, true);
                p.setInvisible(true, true);
                // god mode keeps the health, not the hat: a horde bump can still knock the glasses off and blur the
                // screen for the rest of the run (Louisville, 2026-09-22); worn items get a zero chance to fall
                Log.info("harness: " + Scene.pinWornItems(p) + " worn items pinned; " + Scene.visionState(p));
                // scene presets (time of day, weather, torch): forced now, at the start of the settle time,
                // so the lighting rebake a jump to night or a storm causes is over before the route
                try {
                   Scene.apply(p);
                } catch (Exception e) {
                   // a throw here would repeat every frame (the state never advances) and the run never exits
                   reject("scene setup failed: " + e);
                   return;
                }
                if (pathError != null) {
                   reject(pathError);
                   return;
                }
                String startFlag = HarnessFlags.get("start", "").trim();
                if (!startFlag.isEmpty() && (!driving || zombie.network.GameClient.client)) {
                   // far start (flag start=X,Y): after the scene (population multipliers) so the chunks the jump
                   // loads are generated with them; the settle time covers the reload burst
                   String[] xy = startFlag.split(",");
                   int sx = Integer.parseInt(xy[0].trim());
                   int sy = Integer.parseInt(xy[1].trim());
                   Log.info("harness: teleporting from " + p.getXi() + "," + p.getYi() + " to start " + sx + "," + sy);
                   p.teleportTo(sx, sy, 0);
                }
                if (driving) {
                   vehicle = p.getVehicle();
                   if (vehicle != null && zombie.network.GameClient.client) {
                      // a multiplayer character persists on the server: after a previous run they are still seated
                      // at the route end. Leave that vehicle and start over from the start square with a fresh one
                      Log.info("harness: multiplayer: leaving the vehicle the character was saved in (" + vehicle.getScriptName() + " at " + (int)vehicle.getX() + "," + (int)vehicle.getY() + ")");
                      vehicle.exit(p);
                      vehicle = null;
                   }
                   if (vehicle != null && drivePath != null && !zombie.network.GameClient.client) {
                      // a path run always starts from the path's first point in a fresh car
                      Log.info("harness: path run: leaving the vehicle the character was saved in (" + vehicle.getScriptName() + ")");
                      vehicle.exit(p);
                      vehicle = null;
                   }
                   if (vehicle == null && !"none".equals(HarnessFlags.get("vehicle", DEFAULT_VEHICLE))) {
                      if (drivePath != null && !zombie.network.GameClient.client) {
                         // the car goes on the path's first point: bring the player there and wait for the square
                         // (PATH_START) unless it is loaded and close already
                         int sx = (int)Math.floor(drivePath.x[0]), sy = (int)Math.floor(drivePath.y[0]);
                         if (square(sx, sy, 0) == null || Math.abs(p.getX() - sx) > 40f || Math.abs(p.getY() - sy) > 40f) {
                            Log.info("harness: path run: teleporting from " + p.getXi() + "," + p.getYi() + " to the path start " + sx + "," + sy);
                            p.teleportTo(sx + 0.5f, sy + 0.5f, 0);
                            state = PATH_START;
                            stateSinceNs = nowNs;
                            return;
                         }
                      }
                      if (zombie.network.GameClient.client) {
                         // multiplayer client: vehicles are the server's. First let the start teleport land
                         // (MP_TELEPORT), then ask the server (admin command) and wait for the vehicle to stream
                         // in (MP_VEHICLE), seat, turn it once the client owns it (MP_ALIGN), then continue as below
                         if (!startFlag.isEmpty()) {
                            String[] xy = startFlag.split(",");
                            mpStartX = Integer.parseInt(xy[0].trim());
                            mpStartY = Integer.parseInt(xy[1].trim());
                         } else {
                            mpStartX = p.getXi();
                            mpStartY = p.getYi();
                         }
                         state = MP_TELEPORT;
                         stateSinceNs = nowNs;
                         return;
                      }
                      // no fixture with the player behind the wheel: put a fresh vehicle on the player's
                      // square and seat them, the same steps as the debug menu's "spawn vehicle"
                      vehicle = spawnAndEnter(p, HarnessFlags.get("vehicle", DEFAULT_VEHICLE));
                   }
                } else {
                   p.ensureNotInVehicle();
                   startX = x = p.getX();
                   startY = y = p.getY();
                }
                if (!worldReady(p, nowNs)) {
                   return;
                }
            }
         }
         case PATH_START -> {
            int sx = (int)Math.floor(drivePath.x[0]), sy = (int)Math.floor(drivePath.y[0]);
            if (p != null && p.getCurrentSquare() != null && square(sx, sy, 0) != null && Math.abs(p.getXi() - sx) <= 2 && Math.abs(p.getYi() - sy) <= 2) {
               Log.info("harness: path run: the start square " + sx + "," + sy + " is loaded after " + (nowNs - stateSinceNs) / 1_000_000 + " ms");
               vehicle = spawnAndEnter(p, HarnessFlags.get("vehicle", DEFAULT_VEHICLE));
               worldReady(p, nowNs);
            } else if (nowNs - stateSinceNs > 60_000_000_000L) {
               reject("the path start " + sx + "," + sy + " did not load within 60 s of the teleport");
            }
         }
         case MP_TELEPORT -> {
            int dx = p.getXi() - mpStartX, dy = p.getYi() - mpStartY;
            boolean there = dx * dx + dy * dy <= 9 && p.getCurrentSquare() != null; // the square is null until the chunk arrives
            if ((there || nowNs - stateSinceNs > 8_000_000_000L) && p.getCurrentSquare() != null) {
               Log.info("harness: multiplayer: player at " + p.getXi() + "," + p.getYi() + (there ? " (start reached)" : " (start NOT reached within 8 s, continuing from here)") + " " + (nowNs - stateSinceNs) / 1_000_000 + " ms after the teleport");
               mpRequestVehicle(p, HarnessFlags.get("vehicle", DEFAULT_VEHICLE));
               state = MP_VEHICLE;
               stateSinceNs = nowNs;
            } else if ((nowNs - stateSinceNs) / 1_000_000_000L >= 2 && (nowNs - stateSinceNs) % 2_000_000_000L < 20_000_000L) {
               p.teleportTo(mpStartX, mpStartY, 0); // re-issue every ~2 s; the server may have snapped the first one back
            }
         }
         case MP_VEHICLE -> {
            BaseVehicle v = mpFindVehicle(p);
            long waited = (nowNs - mpAskedNs) / 1_000_000L;
            if (v == null && waited / 2000 != mpLogSlot) {
               // every 2 s: what the client sees, and one repeat of the request at 8 s (the server answers
               // "Invalid location" while the chunk is still loading around the freshly placed player)
               mpLogSlot = waited / 2000;
               StringBuilder sb = new StringBuilder();
               int n = 0;
               if (p.getCell() != null) {
                  for (BaseVehicle o : p.getCell().getVehicles()) {
                     n++;
                     if (o != null && mpRoad != null && n <= 6) {
                        sb.append(' ').append(o.getScriptName()).append('#').append(o.getId()).append('@').append((int)o.getX()).append(',').append((int)o.getY()).append(o.getDriver() != null ? "(driven)" : "");
                     }
                  }
               }
               Log.info("harness: multiplayer: waiting for the vehicle, " + waited / 1000 + " s, " + n + " vehicles in the cell:" + sb);
               if (mpLogSlot == 4 && !mpRepeated) {
                  mpRepeated = true;
                  zombie.network.GameClient.SendCommandToServer("/addvehicle " + HarnessFlags.get("vehicle", DEFAULT_VEHICLE) + " " + (mpRoad.x + 1) + "," + mpRoad.y + ",0");
                  Log.info("harness: multiplayer: repeated the /addvehicle request");
               }
            }
            if (v != null) {
               vehicle = mpSeat(p, v, HarnessFlags.get("vehicle", DEFAULT_VEHICLE));
               if (vehicle == null) {
                  reject("could not seat the player in the server's vehicle");
                  return;
               }
               mpSeatedNs = nowNs;
               state = MP_ALIGN;
               stateSinceNs = nowNs;
            } else if (nowNs - mpAskedNs > 40_000_000_000L) {
               reject("the server did not deliver a " + HarnessFlags.get("vehicle", DEFAULT_VEHICLE) + " within 40 s of /addvehicle");
               return;
            }
         }
         case MP_ALIGN -> {
            // the server owns a vehicle until its driver's client is granted authority (Local); a rotation set
            // before that is overwritten by the next VehicleUpdate. Once local (or after 3 s), teleport the
            // physics body to the road heading (setWorldTransform) and carry on
            boolean local = vehicle.isNetPlayerAuthorization(BaseVehicle.Authorization.Local) || vehicle.isNetPlayerAuthorization(BaseVehicle.Authorization.LocalCollide);
            if (local || nowNs - mpSeatedNs > 3_000_000_000L) {
               try {
                  float angle = (float)(mpDir.toAngle() + Math.PI);
                  while (angle > Math.PI * 2) angle -= (float)(Math.PI * 2);
                  vehicle.savedRot.setAngleAxis(angle, 0f, 1f, 0f);
                  zombie.core.physics.Transform t = new zombie.core.physics.Transform();
                  vehicle.getWorldTransform(t);
                  t.setRotation(vehicle.savedRot);
                  vehicle.setWorldTransform(t);
                  Log.info("harness: multiplayer: vehicle authority " + (local ? "local" : "still remote after 3 s") + " " + (nowNs - mpSeatedNs) / 1_000_000 + " ms after seating; physics body turned to face " + mpDir);
               } catch (Exception e) {
                  Log.warn("harness: multiplayer: could not turn the vehicle: " + e);
               }
               if (!worldReady(p, nowNs)) {
                  return;
               }
            }
         }
         case SETTLE -> {
             long notBefore = Long.parseLong(HarnessFlags.get("route_start_epoch", "0")) * 1000L;
             if (System.currentTimeMillis() >= plannedStartEpochMs) {
                if (notBefore > 0) {
                   long late = System.currentTimeMillis() - notBefore;
                   Log.info("harness: route start " + (late > 1500 ? "LATE by " + late / 1000 + "s (world load took longer than the lead; the external log window is short)" : "on schedule"));
                }
                // the zoom was forced when the world came up (see WORLD_READY); re-assert it in case auto-zoom or a
                // vehicle entry retargeted it during the settle time
                String zoomFlag = HarnessFlags.get("zoom", driving ? "max" : "");
                if (!zoomFlag.isEmpty() && !forceZoom(p, zoomFlag)) {
                   reject("could not force zoom " + zoomFlag);
                   return;
                }
                if (driving && !vehicle.isEngineRunning()) {
                   // the fixture may have been saved with the engine off; the
                   // route needs it running, and the ignition key check is what
                   // the auto-shutdown would otherwise trip on
                   vehicle.setKeysInIgnition(true);
                   vehicle.engineDoRunning();
                   Log.info("harness: started the vehicle engine (running=" + vehicle.isEngineRunning() + ")");
                }
                if (driving) {
                   vehicleStartX = vehicle.getX();
                   vehicleStartY = vehicle.getY();
                   lastTelemetryNs = 0L;
                }
                if (pilot != null) {
                   // a car standing on the start pins ours (the bench save's own car, saved with the player in it at
                   // 8002,11204, streams in after the spawn): fail now with the reason instead of a 90 s stall
                   float reach = vehicle.getScript().getExtents().z() * 0.5f;
                   for (BaseVehicle o : p.getCell().getVehicles()) {
                      if (o == vehicle || o.getScript() == null) continue;
                      float d = (float)Math.hypot(o.getX() - vehicle.getX(), o.getY() - vehicle.getY());
                      if (d < 0.8f * (reach + o.getScript().getExtents().z() * 0.5f)) {
                         reject(String.format(java.util.Locale.ROOT, "vehicle %s at %.1f,%.1f overlaps the path start (%.1f tiles away): move the path's first point",
                               o.getScriptName(), o.getX(), o.getY(), d));
                         return;
                      }
                   }
                }
                Log.info("harness: route start" + (Scene.requested() ? " (zombies loaded: " + Scene.zombiesLoaded() + ")" : ""));
               boolean closeCurtains = "true".equals(HarnessFlags.get("close_curtains", "false"));
               if (closeCurtains || "curtains".equals(HarnessFlags.get("find", ""))) {
                  findCurtains(p, closeCurtains); // dev: curtain screenshot rig (issue #4)
               }
               if ("water".equals(HarnessFlags.get("find", ""))) {
                  findWater(p); // dev: HDR water glint scenes
               }
               if ("shore".equals(HarnessFlags.get("find", ""))) {
                  goToShore(p); // dev: HDR water glint scenes, the player on dry land with the water in view
               }
               int upstairs = Integer.parseInt(HarnessFlags.get("upstairs", "0").trim());
               upstairsAt = Float.parseFloat(HarnessFlags.get("upstairs_at", "0").trim());
               if (upstairs > 0 && upstairsAt <= 0f) {
                  goUpstairs(p, upstairs); // dev: upper-floor rig (issue #12, FSR black squares upstairs)
               }
               Scene.routeStart(nowNs);
               Showcase.routeStart(p); // showcase=horde: the HDR horde video scene
               chunksAtStart = Stats.chunkCount();
               runStartNs = nowNs;
               runStartEpochMs = System.currentTimeMillis();
               sampleThreadCpu();
               Stats.mark("route-start");
               state = RUN;
            }
         }
          case RUN -> {
            if (p == null) {
               Log.warn("harness: player vanished mid-route (world reloaded?); aborting run");
               state = DONE;
               return;
            }
             if (driving) {
                if (vehicle == null || vehicle.getController() == null || p.getVehicle() != vehicle || !vehicle.isDriver(p)) {
                   String why = "vehicle=" + vehicle + " controller=" + (vehicle == null ? null : vehicle.getController()) + " playerVehicle=" + p.getVehicle()
                         + " isDriver=" + (vehicle != null && vehicle.isDriver(p)) + " seat0=" + (vehicle == null ? null : vehicle.getCharacter(0)) + " player=" + p + " sameInstance=" + (p == IsoPlayer.players[0]);
                   if (vehicle != null && vehicle.getController() != null && !reseated) {
                      reseated = true;
                      boolean ok = vehicle.enter(0, p);
                      Log.warn("harness: driver check failed (" + why + "); re-seated=" + ok);
                      if (ok) return;
                   }
                   reject("driver or vehicle changed during route: " + why);
                   return;
                }
                // CarController.updateControls() overwrites clientControls from the keyboard every
                // frame, so input injection does not stick. The game's cruise control does: with the
                // regulator on and no pedal pressed the controller opens the throttle until the
                // regulator speed is reached (CarController.update), exactly like the player pressing
                // the cruise-control key. Re-asserted every frame in case something switched it off.
                if (pilot == null && (!vehicle.isRegulator() || vehicle.getRegulatorSpeed() != cruiseKmh)) {
                   vehicle.setRegulator(true);
                   vehicle.setRegulatorSpeed(cruiseKmh);
                }
                if (!vehicle.isEngineRunning()) {
                   vehicle.setKeysInIgnition(true);
                   vehicle.engineDoRunning();
                }
                 zoomCycle(p, nowNs);
                 float steer;
                 if (pilot != null) {
                    pilotStep(dt, nowNs);
                    steer = pilotOut.steering;
                 } else {
                    steer = roadFollow(dt);
                 }
                 if (nowNs - lastTelemetryNs >= 1_000_000_000L) {
                    lastTelemetryNs = nowNs;
                    Log.info(String.format(java.util.Locale.ROOT, "harness: drive t=%.0fs pos=%.1f,%.1f dist=%.1f lateral=%.2f steer=%+.2f speed=%.1fkm/h engine=%s regulator=%s/%.0f throttle=%.2f gear=%s paused=%s zoom=%.2f",
                          (nowNs - runStartNs) / 1e9, vehicle.getX(), vehicle.getY(), drivenDistance, pilot != null ? pilot.xte : lateralError(), steer, vehicle.getCurrentSpeedKmHour(), vehicle.isEngineRunning(),
                          vehicle.isRegulator(), vehicle.getRegulatorSpeed(), vehicle.throttle, vehicle.transmissionNumber, zombie.GameTime.isGamePaused(), Core.getInstance().getZoom(p.getIndex()))
                          + (pilot == null ? "" : String.format(java.util.Locale.ROOT, " s=%.0f/%.0f target=%.0fkm/h hdg_err=%+.1f offset=%+.2f obstacles=%d%s brake=%s chunk_ahead=%s gain=%.2f impacts=%d",
                          pilot.progress(), drivePath.length, pilot.targetTps * pilot.kmhPerTps(), pilot.headingErrDeg, pilot.offsetNow, pilot.obstaclesAhead,
                          Float.isNaN(pilot.stopAt) ? "" : String.format(java.util.Locale.ROOT, " stop_at=%.0f", pilot.stopAt), pilotOut.brake, pilotIn.chunkAhead, pilot.gain(), pilot.impacts)));
                 }
                 // path length: the road turns, so the distance is accumulated per frame
                 float ddx = vehicle.getX() - x;
                 float ddy = vehicle.getY() - y;
                 x = vehicle.getX();
                 y = vehicle.getY();
                 drivenDistance += (float)Math.sqrt(ddx * ddx + ddy * ddy);
                if (pilot != null ? pilot.done() : drivenDistance >= routeDistance()) {
                   finish(p, 0);
                } else if ((nowNs - runStartNs) / 1e9f >= maxSeconds) {
                   routeStatus = "timeout";
                   Log.warn("harness: vehicle covered " + drivenDistance + " of " + routeDistance() + " tiles in " + maxSeconds + "s (speed now " + vehicle.getCurrentSpeedKmHour() + " km/h, engine running=" + vehicle.isEngineRunning() + ")");
                   finish(p, 0);
                }
                return;
             }
            if (upstairsAt > 0f && (nowNs - runStartNs) / 1e9 >= upstairsAt) {
               upstairsAt = 0f; // once: the level change mid-route (upstairs_at, issue #12 transition)
               Stats.mark("upstairs");
               goUpstairs(p, Integer.parseInt(HarnessFlags.get("upstairs", "1").trim()));
            }
            if (shotAt > 0f && shotPhase < 2 && !holdForScreenshot(nowNs)) {
               return; // camera held for the screenshot: no teleport, no turn this frame
            }
             float step = speed * Math.min(dt, 0.1f);
            while (step > 0f && leg < legs.size()) {
               float[] l = legs.get(leg);
               float remain = l[2] - legDone;
               float take = Math.min(step, remain);
               x += l[0] * take;
               y += l[1] * take;
               legDone += take;
               step -= take;
               if (legDone >= l[2]) {
                  leg++;
                  legDone = 0f;
               }
            }
            // whole-tile teleports through the game's own API (what the debug
            // teleport tools use); it also takes the player out of a vehicle,
            // which plain setX/setY does not survive
            boolean holding = leg >= legs.size() && holdSecs > 0f;
            if (!holding && !Showcase.active() && ((int)x != p.getXi() || (int)y != p.getYi())) { // showcase=horde moves the player itself
               // no teleports during the hold: the player may walk away from the end square (manual tests)
               p.teleportTo((int)x, (int)y, routeZ);
            }
            if (turnDegPerSec != 0f) {
               // spin the facing so the vision cone, lighting cone and buildings-in-front scans keep changing
               turnAngle = (turnAngle + turnDegPerSec * Math.min(dt, 0.1f)) % 360f;
               p.setDirectionAngle(turnAngle);
            }
            zoomCycle(p, nowNs);
            if (leg >= legs.size() && holdSecs > 0f) {
               // end-of-route hold: no more teleports, the facing keeps turning (flag hold)
               if (holdStartNs == 0L) {
                  holdStartNs = nowNs;
                  Log.info("harness: route legs done at " + p.getXi() + "," + p.getYi() + "; holding " + holdSecs + " s" + (turnDegPerSec != 0f ? " with turn=" + turnDegPerSec : ""));
               }
               if ((nowNs - holdStartNs) / 1e9f < holdSecs) {
                  if (shotBurst > 0 && burstTaken < shotBurst && (nowNs - holdStartNs) / 1e9f >= 1f) {
                     // flag shot_burst: N in-game screenshots on N consecutive frames of the hold (frame-exact flicker checks)
                     try {
                        Core.getInstance().TakeFullScreenshot(String.format("pzopt-burst-%02d.png", burstTaken));
                     } catch (Exception e) {
                        Log.warn("harness: burst screenshot failed: " + e);
                     }
                     burstTaken++;
                     if (burstTaken == shotBurst) {
                        Log.info("harness: " + shotBurst + " burst screenshots written (Screenshots/pzopt-burst-NN.png)");
                     }
                  }
                  if (jitterTiles > 0f) {
                     // flag jitter: the square under the player flips every frame across the east edge of the end square
                     if (jitterEdgeX == 0f) {
                        jitterEdgeX = jitterY ? p.getYi() + 1f : p.getXi() + 1f;
                        Log.info("harness: jitter " + jitterTiles + " tiles across " + (jitterY ? "y=" : "x=") + jitterEdgeX + " every frame");
                     }
                     jitterSide = !jitterSide;
                     if (jitterY) { // flag jitter_y: the same across the south edge (north/south jumps)
                        p.setY(jitterEdgeX + (jitterSide ? jitterTiles : -jitterTiles));
                     } else
                     p.setX(jitterEdgeX + (jitterSide ? jitterTiles : -jitterTiles));
                  }
                  return;
               }
            }
            if (leg >= legs.size()) {
               float secs = (nowNs - runStartNs) / 1e9f;
               int chunks = Stats.chunkCount() - chunksAtStart;
               Stats.mark("route-end");
               runEndEpochMs = System.currentTimeMillis();
               Log.info("harness: route done in " + secs + "s, " + chunks + " chunks loaded (" + chunks / secs + "/s); quitting");
               Log.info("harness: player at route end: " + Scene.visionState(p)); // blur > 0 = the screen was soft (pinWornItems)
               writeThreadCpu(secs);
               writeSummary(secs, chunks);
               Stats.flush();
               quitWhenLogsAreDone();
            }
         }
         case PLAY -> {
            // nothing: Scene.tick above keeps the weather pinned and fires the lightning
         }
         case LINGER -> {
            // run.sh drops pzopt-logdone once it has closed the external log itself (control socket)
            if (System.currentTimeMillis() >= lingerUntilEpochMs || new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-logdone").isFile()) {
               state = DONE;
               requestQuit();
            }
         }
         default -> {
         }
      }
   }

   /**
    * shot_at: from that instant on the route the camera is held for 6 s; 2 s into the hold the game writes
    * Screenshots/pzopt-shot.png and Zomboid/pzopt-shot.now appears (run.sh takes a desktop capture on it).
    * Returns false while the route must not advance.
    */
   private static boolean holdForScreenshot(long nowNs) {
      float t = (nowNs - runStartNs) / 1e9f;
      if (shotPhase == 0) {
         if (t < shotAt) {
            return true;
         }
         shotPhase = 1;
         shotHoldNs = nowNs;
         Log.info("harness: holding the camera at t=" + (int)t + "s for the screenshot (x=" + (int)x + ",y=" + (int)y + ", facing " + (int)turnAngle + ")");
      }
      float held = (nowNs - shotHoldNs) / 1e9f;
      if (!shotRequested && held >= 2f) {
         shotRequested = true;
         try {
            Core.getInstance().TakeFullScreenshot("pzopt-shot.png");
            Hdr.requestDump("shot"); // HDR output: the same held frame as float dumps (and every [sweep] set of hdrTune)
            new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-shot.now").createNewFile();
            Log.info("harness: screenshot requested at epoch_ms=" + System.currentTimeMillis());
         } catch (Exception e) {
            Log.warn("harness: screenshot failed: " + e);
         }
      }
      if (shotRequested && !shot2Requested && held >= 4f) {
         shot2Requested = true; // second capture 2 s later: a baked artifact stays put, a per-frame one changes
         try {
            Core.getInstance().TakeFullScreenshot("pzopt-shot2.png");
            new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-shot2.now").createNewFile();
         } catch (Exception e) {
            Log.warn("harness: second screenshot failed: " + e);
         }
      }
      if (held >= 6f) {
         shotPhase = 2;
         Log.info("harness: route resumes after the screenshot hold");
         return true;
      }
      return false;
   }

   /** Total tiles of the parsed route (the corridor swept in multiplayer). */
   private static float routeLength() {
      float n = 0f;
      for (float[] leg : legs) n += leg[2];
      return n;
   }

   private static void parseRoute(String route) {
      legs.clear();
      for (String part : route.split(",")) {
         String[] kv = part.trim().split(":");
         float len = Float.parseFloat(kv[1]);
         switch (kv[0].trim().toUpperCase()) {
            case "E" -> legs.add(new float[]{1, 0, len});
            case "W" -> legs.add(new float[]{-1, 0, len});
            case "N" -> legs.add(new float[]{0, -1, len});
            case "S" -> legs.add(new float[]{0, 1, len});
            default -> Log.warn("harness: unknown route direction " + kv[0]);
         }
      }
   }

   private static void writeSummary(float secs, int chunks) {
      File f = new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-bench.out");
      try (FileWriter w = new FileWriter(f)) {
          Core core = Core.getInstance();
          int playerIndex = IsoPlayer.getInstance() == null ? 0 : IsoPlayer.getInstance().getIndex();
          float zoom = core.getZoom(playerIndex);
          w.write("mode=" + HarnessFlags.get("mode") + "\nroute=" + HarnessFlags.get("route", DEFAULT_ROUTE) + "\nspeed=" + speed + "\nstart=" + (int)startX + "," + (int)startY
                + "\nroute_status=" + (rejected ? "rejected" : routeStatus)
                + "\nroute_reason=" + HarnessFlags.get("reject_reason", "")
                 + "\nvehicle=" + (vehicle == null ? "none" : vehicle.getScriptName()) + "\nvehicle_spawned=" + vehicleSpawned
                + "\nmovement_source=" + (driving ? "vehicle-controller" : "teleport-control")
                + "\nvehicle_distance=" + drivenDistance + "\ncruise_kmh=" + (driving ? cruiseKmh : 0f)
                + (drivePath != null ? "\ndrive_path=" + HarnessFlags.get("path", "") : "")
                + (pilot != null ? "\n" + pilot.describe(secs) : "")
                + "\nzoom=" + zoom + "\nmax_zoom=" + core.getMaxZoom() + "\nauto_zoom_option=" + autoZoomWasOn
                + "\noffscreen_width=" + core.getOffscreenWidth(playerIndex) + "\noffscreen_height=" + core.getOffscreenHeight(playerIndex)
                + "\npan_camera=" + HarnessFlags.get("pan_camera", Boolean.toString(GameKeyboard.isKeyDown("PanCamera")))
                + "\nchunk_map_width=" + IsoChunkMap.chunkGridWidth
                + "\nresolution=" + core.getScreenWidth() + "x" + core.getScreenHeight()
                + "\nrenderer_backend=OpenGL\nrenderer_opengl33=" + (!core.getUseOpenGL21())
                + "\ndashboard=" + HarnessFlags.get("dashboard", "enabled")
                + "\n" + Scene.summary()
                + "\nroute_start_epoch_ms=" + runStartEpochMs + "\nroute_end_epoch_ms=" + runEndEpochMs
                + "\nroute_seconds=" + secs + "\nchunks_loaded=" + chunks + "\nchunks_per_second=" + (secs > 0f ? chunks / secs : 0f)
                + "\nsettings=" + Config.describe()
                + "\nzombie_batches=" + AnimBatch.describe() + " | " + ActionEval.describe() + " | " + AnimParallel.describe() + " | " + LightingBatch.describe() + " | " + FrameBatch.describe()
                + "\nbake_counters=" + zombie.iso.fboRenderChunk.FBORenderCell.pzoptBakeCounters() + "\n"); // pzopt: the per-frame batch and bake counters at route end
      } catch (IOException e) {
         Log.warn("harness: could not write summary: " + e);
      }
      writeNativeMemory(f.getParentFile()); // pzopt: -XX:NativeMemoryTracking runs: the JVM's native memory split at route end
   }

   /** With -XX:NativeMemoryTracking=summary, the VM.native_memory summary at route end in pzopt-nmt.out (the exit-time
    *  -XX:+PrintNMTStatistics never prints: the game does not take the JVM's normal exit path). */
   private static void writeNativeMemory(File dir) {
      try {
         Object out = java.lang.management.ManagementFactory.getPlatformMBeanServer().invoke(
               new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"), "vmNativeMemory",
               new Object[] {new String[] {"summary", "scale=MB"}}, new String[] {String[].class.getName()});
         String text = String.valueOf(out);
         if (text.contains("not enabled")) {
            return;
         }
         try (FileWriter w = new FileWriter(new File(dir, "pzopt-nmt.out"))) {
            w.write(text);
         }
      } catch (Throwable t) {
         Log.warn("harness: native memory summary: " + t);
      }
   }

   /**
    * Spawn a repaired vehicle facing the route's first leg on the player's square and seat the
    * player as its driver. Mirrors LuaManager.GlobalObject.addVehicleDebug plus the enter path the
    * "Enter vehicle" action takes (BaseVehicle.enter -> setPassenger + setVehicle). Returns null if
    * the vehicle could not be placed or entered.
    */

   /**
    * The player stands (or sits) where the route starts: validate the driving fixture, publish the schedule,
    * force the zoom and enter SETTLE. False = rejected. Called from WAIT_WORLD, or from MP_VEHICLE once a
    * multiplayer server has delivered the vehicle.
    */
   private static boolean worldReady(IsoPlayer p, long nowNs) {
      if (driving) {
         if (vehicle == null || !vehicle.isDriver(p)) {
            reject("player is not driving a vehicle");
            return false;
         }
         vehicleStartX = vehicle.getX();
         vehicleStartY = vehicle.getY();
         startX = x = vehicleStartX;
         startY = y = vehicleStartY;
         Log.info("harness: driving fixture valid, vehicle=" + vehicle.getScriptName() + " at " + (int)vehicleStartX + "," + (int)vehicleStartY);
         if (drivePath != null) {
            startPilot();
         }
         if (zombie.network.GameClient.client) {
            mpCleanup(p, false); // earlier runs' cars and the world's wrecks on the loaded part of the road
         }
      }
      Log.info("harness: world ready, player at " + (int)x + "," + (int)y + "," + (int)p.getZ() + "; settling " + settle + "s");
      Log.info("harness: MangoHud is " + (mangoHudLoaded() ? "loaded" : "NOT loaded") + " in this process");
      Log.info(ModelShaders.summary()); // how many model loads blocked on the render thread during boot + load
      long scheduled = Long.parseLong(HarnessFlags.get("route_start_epoch", "0")) * 1000L;
      long nowMs = System.currentTimeMillis();
      long earliest = nowMs + (long)(settle * 1000);
      plannedStartEpochMs = Math.max(scheduled, earliest);
      if (scheduled > 0) {
         // slack between the earliest possible route start (now + settle) and the fixed schedule; negative = the
         // route starts LATE and the harness --lead needs to grow
         Log.info("harness: route scheduled in " + (scheduled - nowMs) / 1000 + "s; lead margin after the settle time " + (scheduled - earliest) / 1000 + "s");
      } else {
         Log.info("harness: route starts in " + settle + "s (no fixed schedule)");
      }
      long logSecs = Long.parseLong(HarnessFlags.get("mangohud_secs", "0"));
      if (logSecs > 0 && HarnessFlags.get("mangohud_end_epoch", "").isEmpty()) {
         derivedLogEndEpochMs = plannedStartEpochMs - 3000L + logSecs * 1000L + 1000L;
      }
      writeSchedule(nowMs);
      // zoom flag: "max" (drive mode default) or a level such as 2.5 / 1.0; unset = whatever the save had.
      // Forced here, at the start of the settle time, so the burst of chunk-texture bakes a zoom change
      // causes (hundreds of chunk levels come on screen at once: a 280 ms frame) is over before the route.
      String zoomFlag = HarnessFlags.get("zoom", driving ? "max" : "");
      if (!zoomFlag.isEmpty() && !forceZoom(p, zoomFlag)) {
         reject("could not force zoom " + zoomFlag);
         return false;
      }
      state = SETTLE;
      stateSinceNs = nowNs;
      return true;
   }

   /**
    * The path driver for this vehicle. Flags (all optional): lat_accel (tiles/s^2 of cornering, 5), decel (tiles/s^2
    * the speed plan brakes with, 6), look_min / look_time / look_max (pure-pursuit look-ahead: tiles + seconds x speed,
    * capped; 5 / 0.6 / 40), lane (tiles right of the centreline, 0), avoid_slope (tiles sideways per tile along, 0.15),
    * avoid_margin (tiles of clearance, 0.5), stop_at_end (true), avoid_zombies (true).
    */
   private static void startPilot() {
      zombie.scripting.objects.VehicleScript sc = vehicle.getScript();
      org.joml.Vector3f ext = sc.getExtents();
      float zMin = Float.MAX_VALUE, zMax = -Float.MAX_VALUE;
      for (int i = 0; i < sc.getWheelCount(); i++) {
         float wz = sc.getWheel(i).getOffset().z();
         zMin = Math.min(zMin, wz);
         zMax = Math.max(zMax, wz);
      }
      float wheelbase = zMax > zMin ? zMax - zMin : ext.z() * 0.6f;
      pilot = new DrivePilot(drivePath, cruiseKmh,
            Float.parseFloat(HarnessFlags.get("lat_accel", "5")), Float.parseFloat(HarnessFlags.get("decel", "6")),
            Float.parseFloat(HarnessFlags.get("look_min", "5")), Float.parseFloat(HarnessFlags.get("look_time", "0.6")), Float.parseFloat(HarnessFlags.get("look_max", "40")),
            Float.parseFloat(HarnessFlags.get("lane", "0")), Float.parseFloat(HarnessFlags.get("avoid_slope", "0.15")), Float.parseFloat(HarnessFlags.get("avoid_margin", "0.5")),
            !"false".equals(HarnessFlags.get("stop_at_end", "true")), ext.x() * 0.5f, ext.z() * 0.5f, wheelbase);
      senses = new DriveSenses(drivePath, vehicle, !"false".equals(HarnessFlags.get("avoid_zombies", "true")));
      Log.info(String.format(java.util.Locale.ROOT, "harness: drive pilot: %s %.2f x %.2f tiles, wheelbase %.2f, max %.0f km/h, cruise %.0f km/h, path %.0f tiles",
            vehicle.getScriptName(), ext.x(), ext.z(), wheelbase, vehicle.getMaxSpeed(), cruiseKmh, drivePath.length));
   }

   /** One frame of the path driver: read the vehicle, step the pilot, feed the controller (called before IsoPlayer.update). */
   private static void pilotStep(float dt, long nowNs) {
      zombie.core.physics.CarController c = vehicle.getController();
      DrivePilot.Input in = pilotIn;
      in.x = vehicle.getX();
      in.y = vehicle.getY();
      vehicle.getForwardVector(pilotFwd);
      float fl = (float)Math.hypot(pilotFwd.x, pilotFwd.z);
      in.fx = fl > 1e-4f ? pilotFwd.x / fl : 1f;
      in.fy = fl > 1e-4f ? pilotFwd.z / fl : 0f;
      in.v = vehicle.getSpeed2D();
      in.kmh = vehicle.getCurrentSpeedKmHour();
      in.steer = c.getVehicleSteering();
      in.maxKmh = vehicle.getMaxSpeed();
      in.steerClamp = vehicle.getScript().getSteeringClamp(in.kmh);
      in.multiplier = zombie.GameTime.getInstance().getMultiplier();
      in.dt = dt;
      in.chunkAhead = vehicle.isInvalidChunkAhead();
      pilot.step(in, pilotOut, senses);
      // CarController.update (IsoPlayer.update -> updatePhysics, later this frame) reads these; the keyboard pass after
      // it (updateControls) overwrites them, so they are set again every frame. The brake switches the cruise control off
      // (updateRegulator), so the regulator is set every frame too.
      c.clientControls.steering = pilotOut.steering;
      c.clientControls.brake = pilotOut.brake;
      vehicle.setRegulator(pilotOut.regulatorKmh > 0.5f);
      vehicle.setRegulatorSpeed(pilotOut.regulatorKmh);
      if (nowNs - lastDriveRowNs >= 50_000_000L) {
         lastDriveRowNs = nowNs;
         driveRows.append(pilot.row((nowNs - runStartNs) / 1e9f, in, pilotOut)).append('\n');
      }
   }

   /** pzopt-drive.out: the path driver's 20 Hz telemetry (DrivePilot.HEADER columns), for harness/drive_check.py. */
   private static void writeDriveLog() {
      File f = new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-drive.out");
      try (FileWriter w = new FileWriter(f)) {
         w.write(driveRows.toString());
      } catch (IOException e) {
         Log.warn("harness: could not write " + f + ": " + e);
      }
   }

   /** Multiplayer client: teleport to the nearest road and ask the server (admin) for the vehicle on that square. */
   private static void mpRequestVehicle(IsoPlayer p, String script) {
      zombie.iso.IsoGridSquare sq = p.getCurrentSquare();
      zombie.iso.IsoGridSquare road = findRoad(sq, 40);
      if (road == null) {
         Log.warn("harness: no road within 40 tiles of " + sq.x + "," + sq.y + "; asking for the vehicle where the player stands");
         road = sq;
      }
      int[] heading = bestHeading(road, HarnessFlags.get("heading", "auto"));
      if (heading == null) {
         heading = new int[]{1, 0, 0, 0};
      }
      headingX = heading[0];
      headingY = heading[1];
      mpDir = headingX > 0 ? zombie.iso.IsoDirections.E : headingX < 0 ? zombie.iso.IsoDirections.W : headingY > 0 ? zombie.iso.IsoDirections.S : zombie.iso.IsoDirections.N;
      mpRoad = road;
      mpAskedNs = System.nanoTime();
      p.teleportTo(road.x, road.y, 0);
      // "/addvehicle <script> x,y,z": the server places it at x-1,y-0.1 and repairs it (AddVehicleCommand)
      zombie.network.GameClient.SendCommandToServer("/addvehicle " + script + " " + (road.x + 1) + "," + road.y + ",0");
      Log.info("harness: multiplayer: asked the server for " + script + " on the road at " + road.x + "," + road.y + " (player " + sq.x + "," + sq.y + "), heading " + mpDir);
   }

   /** The vehicle the server delivered: the newest driverless one within 4 tiles of the requested square. */
   private static BaseVehicle mpFindVehicle(IsoPlayer p) {
      if (mpRoad == null || p.getCell() == null) {
         return null;
      }
      String want = HarnessFlags.get("vehicle", DEFAULT_VEHICLE);
      BaseVehicle best = null;
      for (BaseVehicle v : p.getCell().getVehicles()) {
         if (v == null || v.getDriver() != null || v.getSquare() == null || !want.equals(v.getScriptName())) continue;
         float dx = v.getX() - mpRoad.x, dy = v.getY() - mpRoad.y;
         if (dx * dx + dy * dy <= 16f && (best == null || v.getId() > best.getId())) {
            best = v;
         }
      }
      return best;
   }

   /** Point the server's vehicle along the road and seat the player (BaseVehicle.enter sends VehicleEnter itself). */
   private static BaseVehicle mpSeat(IsoPlayer p, BaseVehicle v, String script) {
      try {
         p.getInventory().AddItem(v.createVehicleKey());
         if (!v.enter(0, p)) {
            Log.warn("harness: could not seat the player in the server's " + v.getScriptName());
            return null;
         }
         vehicleSpawned = true;
         String headlights = HarnessFlags.get("headlights", "auto");
         float hour = Float.parseFloat(HarnessFlags.get("time_of_day", "-1"));
         boolean lightsOn = "on".equals(headlights) || ("auto".equals(headlights) && hour >= 0f && (hour < 6f || hour >= 20f));
         v.setHeadlightsOn(lightsOn);
         Log.info("harness: multiplayer: the server delivered " + v.getScriptName() + " (id " + v.getId() + ") " + ((System.nanoTime() - mpAskedNs) / 1_000_000) + " ms after the request; player seated as driver");
         return v;
      } catch (Exception e) {
         Log.warn("harness: multiplayer vehicle setup failed: " + e);
         return null;
      }
   }

   private static BaseVehicle spawnAndEnter(IsoPlayer p, String script) {
      try {
         zombie.iso.IsoGridSquare sq = p.getCurrentSquare();
         zombie.iso.IsoGridSquare road;
         zombie.iso.IsoDirections dir;
         float angle;
         if (drivePath != null) {
            // path run: on the path's first point, facing along its first segment (IsoDirections angles: N = 0, W = pi/2,
            // S = pi, E = 3 pi / 2, i.e. atan2(-hx, -hy); the body's rotation is that plus pi)
            road = square((int)Math.floor(drivePath.x[0]), (int)Math.floor(drivePath.y[0]), 0);
            if (road == null) {
               Log.warn("harness: the path start " + drivePath.x[0] + "," + drivePath.y[0] + " is not loaded");
               return null;
            }
            float hx = drivePath.hx[0], hy = drivePath.hy[0];
            headingX = Math.abs(hx) >= Math.abs(hy) ? (int)Math.signum(hx) : 0;
            headingY = Math.abs(hx) >= Math.abs(hy) ? 0 : (int)Math.signum(hy);
            float a = (float)Math.atan2(-hx, -hy);
            if (a < 0f) a += (float)(Math.PI * 2);
            dir = zombie.iso.IsoDirections.values()[Math.round(a / (float)(Math.PI / 4)) % 8];
            angle = (float)(a + Math.PI);
            Log.info(String.format(java.util.Locale.ROOT, "harness: path start at %d,%d, heading %.0f deg (%s)", road.x, road.y, Math.toDegrees(Math.atan2(hy, hx)), dir));
         } else {
            // a vehicle driven straight from a field ends in the first tree line (drive-rec-1): put it on
            // the nearest road, centred, pointing along the longest straight run of street tiles
            road = findRoad(sq, 40);
            if (road == null) {
               Log.warn("harness: no road within 40 tiles of " + sq.x + "," + sq.y + "; spawning where the player stands");
               road = sq;
            }
            int[] heading = bestHeading(road, HarnessFlags.get("heading", "auto"));
            if (heading == null) {
               Log.warn("harness: no straight road run from " + road.x + "," + road.y);
               heading = new int[]{1, 0, 0, 0};
            }
            headingX = heading[0];
            headingY = heading[1];
            dir = headingX > 0 ? zombie.iso.IsoDirections.E : headingX < 0 ? zombie.iso.IsoDirections.W : headingY > 0 ? zombie.iso.IsoDirections.S : zombie.iso.IsoDirections.N;
            Log.info("harness: road at " + road.x + "," + road.y + " (player " + sq.x + "," + sq.y + "), heading " + dir + ", straight run " + heading[2] + " tiles" + (heading[3] == 1 ? " to the edge of the loaded map (continues)" : ""));
            angle = (float)(dir.toAngle() + Math.PI);
         }
         // the route length is a path length now (roadFollow keeps the car on the road through curves), so a
         // requested distance longer than the first straight run is fine
         BaseVehicle v = zombie.Lua.LuaManager.GlobalObject.addVehicleDebug(script, dir, 0, road);
         if (v == null || v.getSquare() == null) {
            Log.warn("harness: could not place " + script + " at " + road.x + "," + road.y);
            return null;
         }
         // addVehicleDebug adds a random +-0.2 rad (11 deg) to the heading; a straight route needs it exact
         while (angle > Math.PI * 2) angle -= (float)(Math.PI * 2);
         v.savedRot.setAngleAxis(angle, 0f, 1f, 0f);
         v.jniTransform.setRotation(v.savedRot);
         v.repair(); // every part at 100 % condition
         // engine quality 100 (script default 80 for the race car), stock loudness and force
         v.setEngineFeature(100, v.getEngineLoudness(), (int)v.getScript().getEngineForce());
         p.getInventory().AddItem(v.createVehicleKey());
         if (!v.enter(0, p)) {
            Log.warn("harness: could not seat the player in " + script);
            return null;
         }
         vehicleSpawned = true;
         // headlights=on|off|auto (default auto: on when the scene forces a night hour, off otherwise so the daytime
         // drive baselines keep their light state); a moving headlight beam is the drive-route case of the held
         // lighting re-bakes (pzopt.LightDirt, 2026-09-21)
         String headlights = HarnessFlags.get("headlights", "auto");
         float hour = Float.parseFloat(HarnessFlags.get("time_of_day", "-1"));
         boolean lightsOn = "on".equals(headlights) || ("auto".equals(headlights) && hour >= 0f && (hour < 6f || hour >= 20f));
         v.setHeadlightsOn(lightsOn);
         // lightbar=0..3: the emergency lightbar's lights mode (ambulance, police; 0 = off, 1-3 the game's patterns);
         // its rotating red/blue world lights change every frame
         int lightbar = Integer.parseInt(HarnessFlags.get("lightbar", "0"));
         if (lightbar > 0 && v.hasLightbar()) {
            v.setLightbarLightsMode(lightbar);
         }
         Log.info("harness: spawned " + script + " facing " + dir + " (engine quality " + v.getEngineQuality() + ", condition 100, headlights " + (lightsOn ? "on" : "off") + (lightbar > 0 && v.hasLightbar() ? ", lightbar mode " + lightbar : "") + ") and seated the player as driver");
         return v;
      } catch (Exception e) {
         Log.warn("harness: vehicle spawn failed: " + e);
         return null;
      }
   }

   private static int headingX = 1, headingY = 0;
   private static float prevLateral;
   private static int steerFrame;
   private static java.lang.reflect.Field keyDown;
   private static int keyLeft = -1, keyRight = -1;

   /** Signed distance (tiles) of the vehicle from the line it started on: + = to the right of the heading. */
   private static float lateralError() {
      // heading E (+x): right is +y; W: right is -y; S (+y): right is -x; N (-y): right is +x
      float ox = vehicle.getX() - vehicleStartX, oy = vehicle.getY() - vehicleStartY;
      return headingX != 0 ? headingX * oy : -headingY * ox;
   }

   /**
    * Lane keeping through the keyboard: CarController.updateControls() reads the Left/Right bindings
    * from GameKeyboard's polled state, which is refreshed at the start of the frame and consumed by
    * IsoPlayer.update() after this hook ran (IsoCell.updateInternal updates the chunk map first), so a
    * key set here is seen this frame and cleared by the next poll. Proportional control by
    * duty-cycling the (binary) key over frames. Returns the steering applied (-1 left, +1 right, 0).
    */
   // road following: heading from the vehicle's own motion, road centre sampled ahead of it
   private static float hdgX = 1f, hdgY = 0f;
   private static final float LINE_PREVIEW_S = 0.6f; // wide-street lane keeping: seconds of lateral drift the target leads by
   private static float lastRoadOffset;
   private static float roadOffsetFiltered, roadOffsetRate;
   private static int noRoadFrames;

   /**
    * Steer toward the centre of the street some tiles ahead. The heading is the smoothed direction of motion
    * (falls back to the spawn heading while stationary); across that heading the street tiles at the look-ahead
    * point are scanned and their middle taken as the target. Returns the steering applied (-1 left, +1 right, 0).
    */
   private static float roadFollow(float dt) {
      try {
         if (keyDown == null) {
            keyDown = zombie.input.GameKeyboard.class.getDeclaredField("down");
            keyDown.setAccessible(true);
            keyLeft = Core.getInstance().getKeyBinding("Left").keyValue();
            keyRight = Core.getInstance().getKeyBinding("Right").keyValue();
            hdgX = headingX;
            hdgY = headingY;
         }
         boolean[] down = (boolean[])keyDown.get(null);
         if (down == null || keyLeft < 0 || keyRight < 0) return 0f;
         float vx = vehicle.getX() - x, vy = vehicle.getY() - y; // this frame's motion (x,y still hold last frame's position)
         float vlen = (float)Math.sqrt(vx * vx + vy * vy);
         if (vlen > 0.02f && vehicle.getCurrentSpeedKmHour() > 3f) {
            float k = 0.25f; // smoothing
            hdgX = hdgX * (1 - k) + (vx / vlen) * k;
            hdgY = hdgY * (1 - k) + (vy / vlen) * k;
            float hl = (float)Math.sqrt(hdgX * hdgX + hdgY * hdgY);
            if (hl > 1e-4f) { hdgX /= hl; hdgY /= hl; }
         }
         float kmh = Math.max(0f, vehicle.getCurrentSpeedKmHour());
         float look = Math.max(4f, Math.min(14f, kmh / 6f)); // tiles ahead: ~10 at 60 km/h
         float rx = -hdgY, ry = hdgX; // right-hand perpendicular (heading E -> right is +y)
         Float offset = roadCentreOffset(look, rx, ry);
         if (offset == null) offset = roadCentreOffset(look * 0.5f, rx, ry);
         if (offset == null) offset = roadCentreOffset(look * 1.6f, rx, ry);
         if (offset == null) {
            // nothing straight ahead: the road turns. Fan of bearings at the look-ahead radius, most street tiles wins
            int bestScore = 0; float bestA = 0f;
            for (int a = -90; a <= 90; a += 15) {
               if (a == 0) continue;
               double r = Math.toRadians(a);
               float dx = (float)(hdgX * Math.cos(r) - hdgY * Math.sin(r)), dy = (float)(hdgX * Math.sin(r) + hdgY * Math.cos(r));
               float cx = vehicle.getX() + dx * look, cy = vehicle.getY() + dy * look;
               int score = 0;
               for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) if (isStreet(square(Math.round(cx) + i, Math.round(cy) + j, (int)vehicle.getZ()))) score++;
               if (score > bestScore || (score == bestScore && score > 0 && Math.abs(a) < Math.abs(bestA))) { bestScore = score; bestA = a; }
            }
            if (bestScore > 0) offset = (float)(look * Math.sin(Math.toRadians(bestA))) * (bestA > 0 ? 1f : 1f);
         }
         if (offset == null) {
            noRoadFrames++;
            if (noRoadFrames == 120) Log.warn("harness: no street found ahead for 120 frames at " + vehicle.getX() + "," + vehicle.getY());
            return 0f;
         }
         noRoadFrames = 0;
         // The road scan quantises the target to half tiles, so a raw per-frame derivative is a step of
         // +-0.5 / dt (3.75 tiles at 240 fps) on every scan change: it slammed the wheel and the car
         // oscillated across the road until it hit a yard (2026-09-19 evening, three timeouts in a row).
         // Low-pass the offset over ~0.15 s and damp with the smoothed rate in tiles per second.
         float a = Math.min(1f, Math.max(dt, 1e-3f) / 0.15f);
         float prev = roadOffsetFiltered;
         roadOffsetFiltered += (offset - roadOffsetFiltered) * a;
         float rate = dt > 1e-3f ? (roadOffsetFiltered - prev) / dt : 0f;
         roadOffsetRate += (rate - roadOffsetRate) * a;
         float u = roadOffsetFiltered + 0.35f * roadOffsetRate;
         lastRoadOffset = offset;
         steerFrame++;
         float mag = Math.abs(u);
         if (mag < 0.5f) return 0f;
         int duty = mag > 3f ? 1 : mag > 1.5f ? 2 : 4; // press every frame / every 2nd / every 4th
         if (steerFrame % duty != 0) return 0f;
         if (u > 0) { down[keyRight] = true; return 1f; }
         down[keyLeft] = true;
         return -1f;
      } catch (Exception e) {
         Log.warn("harness: road following unavailable: " + e);
         keyLeft = keyRight = -2;
         return 0f;
      }
   }

   /** Signed offset (tiles, + = to the right of the heading) of the middle of the street `look` tiles ahead, or null if no street tile is there. */
   private static Float roadCentreOffset(float look, float rx, float ry) {
      float px = vehicle.getX() + hdgX * look, py = vehicle.getY() + hdgY * look;
      int z = (int)vehicle.getZ();
      int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
      for (int w = -7; w <= 7; w++) {
         int sx = Math.round(px + rx * w), sy = Math.round(py + ry * w);
         if (isStreet(square(sx, sy, z))) { min = Math.min(min, w); max = Math.max(max, w); }
      }
      if (min == Integer.MAX_VALUE) return null;
      if (max - min >= 13) {
         // street all across the scan (a wide junction): steer back to the route line instead of holding course. Holding
         // kept the heading error of the last curve: on the Dell (~30 fps) the car drifted 8 tiles off the line through the
         // wide stretch after the start, overcorrected at 100 km/h and ended in a yard (2026-09-22, three drive timeouts).
         // Aim at where the car will be LINE_PREVIEW_S from now (the lateral velocity from its heading), not where it is:
         // on position alone a start yaw of a few degrees went uncorrected until the car was tiles off the line, and past
         // the +-3 clamp the target stopped moving, which removed the damping; the car swung +10 / -8 tiles across the
         // road at 100 km/h and hit the north side at x~8120 (2026-09-23, half the upscaler drives of the evening).
         float kmh = Math.max(0f, vehicle.getCurrentSpeedKmHour());
         float lateralVelocity = kmh / 3.6f * (headingX != 0 ? headingX * hdgY : -headingY * hdgX); // tiles/s, + = drifting right
         return Math.max(-3f, Math.min(3f, -(lateralError() + LINE_PREVIEW_S * lateralVelocity)));
      }
      return (min + max) / 2f;
   }

   @SuppressWarnings("unused")
   private static float laneKeep(float dt) {
      try {
         if (keyDown == null) {
            keyDown = zombie.input.GameKeyboard.class.getDeclaredField("down");
            keyDown.setAccessible(true);
            keyLeft = Core.getInstance().getKeyBinding("Left").keyValue();
            keyRight = Core.getInstance().getKeyBinding("Right").keyValue();
         }
         boolean[] down = (boolean[])keyDown.get(null);
         if (down == null || keyLeft < 0 || keyRight < 0) return 0f;
         float lat = lateralError();
         float vel = dt > 0f ? (lat - prevLateral) / dt : 0f; // tiles/s across the lane
         prevLateral = lat;
         float u = lat + 0.7f * vel; // predicted offset ~0.7 s ahead
         steerFrame++;
         float mag = Math.abs(u);
         if (mag < 0.15f) return 0f;
         int duty = mag > 1.5f ? 1 : mag > 0.6f ? 2 : 4; // press every frame / every 2nd / every 4th
         if (steerFrame % duty != 0) return 0f;
         if (u > 0) { down[keyLeft] = true; return -1f; }
         down[keyRight] = true;
         return 1f;
      } catch (Exception e) {
         Log.warn("harness: lane keeping unavailable: " + e);
         keyLeft = keyRight = -2;
         return 0f;
      }
   }

   static boolean isStreet(zombie.iso.IsoGridSquare sq) {
      if (sq == null) return false;
      zombie.iso.IsoObject floor = sq.getFloor();
      if (floor == null || floor.getSprite() == null || floor.getSprite().getName() == null) return false;
      String n = floor.getSprite().getName();
      return n.contains("blends_street") || n.contains("floors_exterior_street");
   }

   private static zombie.iso.IsoGridSquare square(int x, int y, int z) {
      return zombie.iso.IsoWorld.instance.getCell().getGridSquare(x, y, z);
   }

   /** Nearest street square (Chebyshev rings), moved to the middle of the road's width across its axis. */
   static zombie.iso.IsoGridSquare findRoad(zombie.iso.IsoGridSquare from, int radius) {
      for (int r = 0; r <= radius; r++) {
         for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
               if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
               zombie.iso.IsoGridSquare sq = square(from.x + dx, from.y + dy, from.z);
               if (isStreet(sq)) return centreOfRoad(sq);
            }
         }
      }
      return null;
   }

   /** Width of the street run through sq along (dx,dy) in both directions, capped. */
   private static int run(zombie.iso.IsoGridSquare sq, int dx, int dy, int cap) {
      int n = 0;
      for (int i = 1; i <= cap; i++) {
         if (!isStreet(square(sq.x + dx * i, sq.y + dy * i, sq.z))) break;
         n++;
      }
      return n;
   }

   private static zombie.iso.IsoGridSquare centreOfRoad(zombie.iso.IsoGridSquare sq) {
      // the road axis is the direction with the longer street run; centre across the other one
      int alongX = run(sq, 1, 0, 30) + run(sq, -1, 0, 30);
      int alongY = run(sq, 0, 1, 30) + run(sq, 0, -1, 30);
      int px = alongX >= alongY ? 0 : 1, py = alongX >= alongY ? 1 : 0;
      int plus = run(sq, px, py, 12), minus = run(sq, -px, -py, 12);
      int shift = (plus - minus) / 2;
      zombie.iso.IsoGridSquare c = square(sq.x + px * shift, sq.y + py * shift, sq.z);
      return c != null ? c : sq;
   }

   /**
    * {dx, dy, straightTiles}: the axis direction with the longest run of street tiles ahead of sq
    * (a lateral wobble of 2 tiles is tolerated), or the requested one (N/S/E/W) with its run.
    */
   static int[] bestHeading(zombie.iso.IsoGridSquare sq, String want) {
      int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
      String[] names = {"E", "W", "S", "N"};
      int[] best = null;
      for (int d = 0; d < 4; d++) {
         if (!"auto".equalsIgnoreCase(want) && !names[d].equalsIgnoreCase(want)) continue;
         int dx = dirs[d][0], dy = dirs[d][1], px = dy, py = dx; // perpendicular for the tolerance
         int n = 0;
         boolean edge = false; // the scan reached unloaded chunks: the road may well continue
         for (int i = 1; i <= 1500; i++) {
            boolean ok = false;
            zombie.iso.IsoGridSquare centre = square(sq.x + dx * i, sq.y + dy * i, sq.z);
            if (centre == null) { edge = true; break; }
            for (int w = -2; w <= 2 && !ok; w++) ok = isStreet(square(sq.x + dx * i + px * w, sq.y + dy * i + py * w, sq.z));
            if (!ok) break;
            n++;
         }
         if (best == null || n > best[2]) best = new int[]{dx, dy, n, edge ? 1 : 0};
      }
      return best;
   }

   private static float routeDistance() {
      if (drivePath != null) return drivePath.length;
      float distance = 0f;
      for (float[] leg : legs) distance += leg[2];
      return distance;
   }

   /**
    * Dev flag upstairs=Z (issue #12: black squares with FSR once the player is on an upper floor): at route start
    * move the player to the loaded square nearest to it at level Z that has a floor and room to stand, so the
    * route / hold / shot that follows is seen from that floor (the cutaways, the upper-level chunk textures and
    * the view cone of an upper floor). Logs where it went, or that no such square was loaded.
    */
   private static void goUpstairs(IsoPlayer p, int z) {
      zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
      int px = p.getXi(), py = p.getYi();
      for (int r = 0; r <= 60; r++) {
         for (int y = py - r; y <= py + r; y++) {
            for (int x = px - r; x <= px + r; x++) {
               if (Math.max(Math.abs(x - px), Math.abs(y - py)) != r) continue; // the ring at distance r
               zombie.iso.IsoGridSquare sq = cell.getGridSquare(x, y, z);
               if (sq == null || sq.getFloor() == null || !sq.isFree(false) || sq.getRoom() == null) continue;
               p.teleportTo(x + 0.5F, y + 0.5F, z);
               Harness.x = x + 0.5F; // the route continues from here, on this level (its teleports would put the player back)
               Harness.y = y + 0.5F;
               routeZ = z;
               Log.info("harness: upstairs: moved the player to " + x + "," + y + "," + z + " (room " + sq.getRoom().getName() + ", " + r + " tiles from " + px + "," + py + ")");
               return;
            }
         }
      }
      Log.warn("harness: upstairs: no loaded square with a floor at level " + z + " within 60 tiles of " + px + "," + py);
   }

   /**
    * Dev flags for the curtain screenshot rig (issue #4, windows through closed curtains). find=curtains lists the
    * curtains on loaded squares within 100 tiles of the player (type, open state, what they are attached to, the
    * room), so a screenshot run can be started inside such a room with start=X,Y. close_curtains=true closes every
    * open curtain in that range through the game's own ToggleDoor (map curtains always load open; the bench save is
    * a copy, so nothing persists), so the bake shows the reporter's state.
    */
   /** find=shore: teleport to the nearest dry square with at least 20 water squares within 5 tiles. */
   private static void goToShore(IsoPlayer p) {
      zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
      int px = (int)p.getX(), py = (int)p.getY();
      for (int r = 0; r <= 110; r++) {
         for (int y = py - r; y <= py + r; y++) {
            for (int x = px - r; x <= px + r; x++) {
               if (Math.max(Math.abs(x - px), Math.abs(y - py)) != r) {
                  continue;
               }
               zombie.iso.IsoGridSquare sq = cell.getGridSquare(x, y, 0);
               if (sq == null || sq.isWaterSquare() || sq.getFloor() == null || !sq.isFree(false)) {
                  continue;
               }
               int water = 0;
               for (int dy = -5; dy <= 5; dy++) {
                  for (int dx = -5; dx <= 5; dx++) {
                     zombie.iso.IsoGridSquare w = cell.getGridSquare(x + dx, y + dy, 0);
                     water += w != null && w.isWaterSquare() ? 1 : 0;
                  }
               }
               if (water >= 20) {
                  p.setX(x + 0.5F);
                  p.setY(y + 0.5F);
                  p.setLastX(x + 0.5F);
                  p.setLastY(y + 0.5F);
                  p.setCurrent(sq);
                  Log.info("harness: find=shore: moved to " + x + "," + y + " (" + water + " water squares within 5 tiles, " + r + " tiles from " + px + "," + py + ")");
                  return;
               }
            }
         }
      }
      Log.warn("harness: find=shore: no shore within 110 tiles of " + px + "," + py);
   }

   /** find=water: the water squares in the loaded grid around the player, nearest first (HDR glint scenes). */
   private static void findWater(IsoPlayer p) {
      zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
      int px = (int)p.getX(), py = (int)p.getY(), n = 0;
      java.util.ArrayList<int[]> found = new java.util.ArrayList<>();
      for (int y = py - 120; y <= py + 120; y++) {
         for (int x = px - 120; x <= px + 120; x++) {
            zombie.iso.IsoGridSquare sq = cell.getGridSquare(x, y, 0);
            if (sq != null && sq.isWaterSquare()) {
               n++;
               found.add(new int[] {x, y, (x - px) * (x - px) + (y - py) * (y - py)});
            }
         }
      }
      found.sort((a, b) -> Integer.compare(a[2], b[2]));
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < Math.min(8, found.size()); i++) {
         sb.append(' ').append(found.get(i)[0]).append(',').append(found.get(i)[1]);
      }
      Log.info("harness: find=water: " + n + " water squares within 120 tiles of " + px + "," + py + "; nearest:" + sb);
   }

   private static void findCurtains(IsoPlayer p, boolean close) {
      zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
      int px = p.getXi(), py = p.getYi();
      int found = 0, closed = 0, squares = 0, windows = 0;
      for (int z = 0; z <= 2; z++) {
         for (int y = py - 100; y <= py + 100; y++) {
            for (int x = px - 100; x <= px + 100; x++) {
               zombie.iso.IsoGridSquare sq = cell.getGridSquare(x, y, z);
               if (sq == null) continue;
               squares++;
               for (int i = 0; i < sq.getObjects().size(); i++) {
                  if (sq.getObjects().get(i) instanceof zombie.iso.objects.IsoWindow) windows++;
                  if (!(sq.getObjects().get(i) instanceof zombie.iso.objects.IsoCurtain c)) continue;
                  if (close && c.open) {
                     c.ToggleDoor(null);
                     closed++;
                  }
                  zombie.iso.IsoObject attached = c.getObjectAttachedTo();
                  Log.info("harness: curtain " + c.getType() + (c.open ? " open" : " closed") + " at " + x + "," + y + "," + z + " sprite=" + (c.getSprite() == null ? "?" : c.getSprite().getName())
                        + " attached=" + (attached == null ? "none" : attached.getClass().getSimpleName() + (attached.getSprite() == null ? "" : "/" + attached.getSprite().getName()))
                        + " room=" + (sq.getRoom() == null ? "outside" : sq.getRoom().getName()) + " dist=" + Math.abs(x - px) + "," + Math.abs(y - py));
                  found++;
               }
            }
         }
      }
      Log.info("harness: " + found + " curtains within 100 tiles of " + px + "," + py + (close ? ", " + closed + " closed now" : "") + " (" + squares + " loaded squares, " + windows + " windows)");
   }

   /**
    * flag zoom_cycle: every zoomCycleSecs on the route the target zoom moves one level the way one mouse-wheel
    * notch does (MultiTextureFBO2.getNextZoom; the ease in its update() then slides the zoom there), zooming in
    * until the closest level, then back out, and so on. The step is a Stats mark so the frame times can be aligned
    * to it. zoom_jump=true sets the zoom to the level at once (the ease skipped: every chunk texture flips scale
    * in one frame, the worst case for the bakes).
    */
   private static void zoomCycle(IsoPlayer p, long nowNs) {
      if (zoomCycleSecs <= 0f) return;
      Core core = Core.getInstance();
      if (core.offscreenBuffer == null) return;
      if (zoomTraceLeft > 0) {
         // per-frame trace after a step: "bakes/deferred@ms" for ZOOM_TRACE_FRAMES frames (the frame that ended now)
         long bakes = zombie.iso.fboRenderChunk.FBORenderCell.pzoptBakesCumulative;
         long deferred = zombie.iso.fboRenderChunk.FBORenderCell.pzoptDeferredCumulative;
         zoomTrace.append(' ').append(bakes - zoomTraceBakes);
         if (deferred != zoomTraceDeferred) zoomTrace.append('/').append(deferred - zoomTraceDeferred);
         zoomTrace.append('@').append(String.format(java.util.Locale.ROOT, "%.1f", (nowNs - zoomTraceFrameNs) / 1e6));
         zoomTraceBakes = bakes;
         zoomTraceDeferred = deferred;
         zoomTraceFrameNs = nowNs;
         if (--zoomTraceLeft == 0) {
            Log.info("harness: zoom step " + zoomSteps + " trace (bakes[/deferred]@ms per frame):" + zoomTrace);
            StringBuilder z = new StringBuilder("harness: zoom step " + zoomSteps + " retain: returned=" + (ZoomRetain.returned - zoomTraceReturned)
                  + " rebakes=" + (ZoomRetain.rebakes - zoomTraceRebakes) + " creations=" + (ZoomRetain.creations - zoomTraceCreations)
                  + " urgent=" + (ZoomRetain.urgent - zoomTraceUrgent) + " flood frames=" + (ZoomRetain.floodFrames - zoomTraceFlood) + " placeholders=" + (ZoomRetain.placeholders - zoomTracePlaceholders) + " urgent flags:");
            for (int b = 0; b < 16; b++) {
               if (ZoomRetain.urgentFlags[b] != zoomTraceUrgentFlags[b]) z.append(' ').append(1L << b).append('=').append(ZoomRetain.urgentFlags[b] - zoomTraceUrgentFlags[b]);
            }
            Log.info(z.toString());
            z = new StringBuilder("harness: zoom step " + zoomSteps + " change-frame bakes=" + (ZoomRetain.changeFrameBakes - zoomTraceCf[0]) + " creates=" + (ZoomRetain.changeFrameCreates - zoomTraceCf[1])
                  + " first-sight=" + (ZoomRetain.changeFrameFirstSight - zoomTraceCf[2]) + " chunk-off-screen=" + (ZoomRetain.changeFrameOffScreen - zoomTraceCf[3]) + " flags:");
            for (int b = 0; b < 16; b++) {
               if (ZoomRetain.changeFrameFlags[b] != zoomTraceCfFlags[b]) z.append(' ').append(1L << b).append('=').append(ZoomRetain.changeFrameFlags[b] - zoomTraceCfFlags[b]);
            }
            Log.info(z.toString());
            zoomTrace.setLength(0);
         }
      }
      if (lastZoomStepNs == 0L) {
         lastZoomStepNs = nowNs; // the first step comes zoomCycleSecs after the route start
         return;
      }
      if ((nowNs - lastZoomStepNs) / 1e9f < zoomCycleSecs) return;
      lastZoomStepNs = nowNs;
      int idx = p.getIndex();
      float next = zoomSpanTarget(core, idx, zoomDir);
      if (next == core.offscreenBuffer.getTargetZoom(idx)) {
         zoomDir = -zoomDir; // at the end of the level list: turn around
         next = zoomSpanTarget(core, idx, zoomDir);
      }
      if (zoomJump) {
         core.offscreenBuffer.setZoomAndTargetZoom(idx, next);
      } else {
         core.offscreenBuffer.setTargetZoom(idx, next);
      }
      zoomSteps++;
      Stats.mark("zoom-" + next);
      zoomTraceLeft = ZOOM_TRACE_FRAMES;
      zoomTraceReturned = ZoomRetain.returned;
      zoomTraceRebakes = ZoomRetain.rebakes;
      zoomTraceCreations = ZoomRetain.creations;
      zoomTraceUrgent = ZoomRetain.urgent;
      zoomTracePlaceholders = ZoomRetain.placeholders;
      zoomTraceFlood = ZoomRetain.floodFrames;
      zoomTraceCf[0] = ZoomRetain.changeFrameBakes; zoomTraceCf[1] = ZoomRetain.changeFrameCreates; zoomTraceCf[2] = ZoomRetain.changeFrameFirstSight; zoomTraceCf[3] = ZoomRetain.changeFrameOffScreen;
      System.arraycopy(ZoomRetain.changeFrameFlags, 0, zoomTraceCfFlags, 0, 16);
      System.arraycopy(ZoomRetain.urgentFlags, 0, zoomTraceUrgentFlags, 0, 16);
      zoomTraceBakes = zombie.iso.fboRenderChunk.FBORenderCell.pzoptBakesCumulative;
      zoomTraceDeferred = zombie.iso.fboRenderChunk.FBORenderCell.pzoptDeferredCumulative;
      zoomTraceFrameNs = nowNs;
      zoomTrace.setLength(0);
      Log.info(String.format(java.util.Locale.ROOT, "harness: zoom step %d -> %.2f (%s, was %.2f, %s)", zoomSteps, next, zoomDir > 0 ? "out" : "in",
            core.getZoom(idx), zoomJump ? "jump" : "wheel"));
   }

   /** The level zoomSpan wheel notches away from the current target (the list end when fewer remain). */
   private static float zoomSpanTarget(Core core, int idx, int dir) {
      float from = core.offscreenBuffer.getTargetZoom(idx);
      float next = from;
      for (int i = 0; i < zoomSpan; i++) {
         core.offscreenBuffer.setTargetZoom(idx, next); // getNextZoom walks from the target
         float n = core.offscreenBuffer.getNextZoom(idx, dir);
         if (n == next) break;
         next = n;
      }
      core.offscreenBuffer.setTargetZoom(idx, from);
      return next;
   }

   /**
    * Pin the camera at the configured maximum zoom for the route. The game's
    * auto-zoom (MultiTextureFBO2.update) retargets the zoom every frame while the
    * camera character is in a vehicle, so it is switched off for the run — the
    * value it would pick is the maximum anyway, but it would keep the zoom
    * sliding for the first seconds of the route and re-arm on every re-entry.
    */
   private static boolean forceZoom(IsoPlayer p, String level) {
      Core core = Core.getInstance();
      if (core.offscreenBuffer == null) return false;
      float target = level.equals("max") ? core.offscreenBuffer.getMaxZoom() : Float.parseFloat(level);
      autoZoomWasOn = core.getAutoZoom(p.getIndex());
      core.setAutoZoom(p.getIndex(), false); // auto-zoom retargets the zoom every frame (and forces max in a vehicle)
      core.offscreenBuffer.setZoomAndTargetZoom(p.getIndex(), target);
      Log.info("harness: zoom forced to " + core.getZoom(p.getIndex()) + " (requested " + level + ", max " + core.getMaxZoom() + ", auto-zoom was " + autoZoomWasOn + ")");
      return Math.abs(core.getZoom(p.getIndex()) - target) < 0.01f;
   }
   private static boolean autoZoomWasOn;

   private static void finish(IsoPlayer p, int chunks) {
      float secs = (System.nanoTime() - runStartNs) / 1e9f;
      chunks = Stats.chunkCount() - chunksAtStart;
      Stats.mark("route-end");
      runEndEpochMs = System.currentTimeMillis();
      Log.info("harness: route " + routeStatus + " in " + secs + "s, vehicle distance=" + drivenDistance + "; quitting");
      writeThreadCpu(secs);
      if (pilot != null) {
         writeDriveLog(); // before the summary: run.sh treats pzopt-bench.out as the end of the route
      }
      writeSummary(secs, chunks);
      Stats.flush();
      if (driving && vehicle != null && vehicle.getController() != null) {
         vehicle.setRegulator(false);
         vehicle.setRegulatorSpeed(0f);
         vehicle.getController().clientControls.forceBrake = System.currentTimeMillis(); // updateControls brakes for 1 s
      }
      if (driving && zombie.network.GameClient.client) {
         mpCleanup(p, true);
      }
      quitWhenLogsAreDone();
   }

   /**
    * Multiplayer client: the server keeps every vehicle a run leaves behind, so the next run finds the previous
    * car parked at the route end (and the world's own wrecks on the road). Ask the server (admin, the same
    * "vehicle remove" client command ISVehicleMechanics uses) to delete every vehicle standing in the route
    * corridor that this client currently has loaded; at the end of a run the harness's own car too, after
    * leaving it. Only what is loaded client-side can be named, so the sweep runs at the start and the end.
    */
   private static void mpCleanup(IsoPlayer p, boolean end) {
      if (p == null || p.getCell() == null) {
         return;
      }
      try {
         float ax = headingX != 0 ? 1f : 0f, ay = headingY != 0 ? 1f : 0f; // along / across the heading
         float len = Math.max(50f, routeLength() + 60f);
         int removed = 0;
         BaseVehicle own = vehicle;
         if (end && own != null && own.getDriver() == p) {
            own.exit(p);
         }
         for (BaseVehicle v : new ArrayList<>(p.getCell().getVehicles())) {
            if (v == null) continue;
            boolean mine = v == own;
            float along = (v.getX() - startX) * ax * Math.signum(headingX + headingY) + (v.getY() - startY) * ay * Math.signum(headingX + headingY);
            float across = (v.getX() - startX) * ay + (v.getY() - startY) * ax;
            boolean onRoute = along >= -20f && along <= len && Math.abs(across) <= 4f;
            if (!(mine && end) && !onRoute) continue;
            if (mine && !end) continue;
            if (!mine && v.getDriver() != null) continue;
            se.krka.kahlua.vm.KahluaTable args = zombie.Lua.LuaManager.platform.newTable();
            args.rawset("vehicle", (double)v.getId());
            zombie.Lua.LuaManager.GlobalObject.sendClientCommand(p, "vehicle", "remove", args);
            removed++;
         }
         Log.info("harness: multiplayer: asked the server to remove " + removed + " vehicle(s) in the route corridor" + (end ? " (the harness car included)" : "") + " at route " + (end ? "end" : "start"));
      } catch (Exception e) {
         Log.warn("harness: multiplayer cleanup failed: " + e);
      }
   }

   /**
    * Snapshot every thread's CPU time. With the route wall time this says which
    * threads are saturated (game thread at ~100 % = main-loop bound) and how much
    * of the machine the process used — the utilization side of the objective.
    */
   private static void sampleThreadCpu() {
      ThreadMXBean mx = ManagementFactory.getThreadMXBean();
      cpuAtStart = new HashMap<>();
      try {
         if (!mx.isThreadCpuTimeEnabled()) mx.setThreadCpuTimeEnabled(true);
         for (long id : mx.getAllThreadIds()) {
            long t = mx.getThreadCpuTime(id);
            if (t >= 0) cpuAtStart.put(id, t);
         }
         processCpuAtStart = processCpuNs();
      } catch (Exception e) {
         Log.warn("harness: thread cpu sampling unavailable: " + e);
         cpuAtStart = null;
      }
   }

   private static long processCpuNs() {
      java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
      if (os instanceof com.sun.management.OperatingSystemMXBean sun) return sun.getProcessCpuTime();
      return -1L;
   }

   /** pzopt-threads.out: "name\tcpu_ms\tshare" per thread over the route (share = cpu / wall), plus the process total. */
   private static void writeThreadCpu(float secs) {
      if (cpuAtStart == null || secs <= 0f) return;
      ThreadMXBean mx = ManagementFactory.getThreadMXBean();
      List<String[]> rows = new ArrayList<>();
      long sum = 0;
      for (long id : mx.getAllThreadIds()) {
         long t = mx.getThreadCpuTime(id);
         if (t < 0) continue;
         long d = t - cpuAtStart.getOrDefault(id, 0L);
         if (d <= 0) continue;
         ThreadInfo info = mx.getThreadInfo(id);
         String name = info == null ? ("thread-" + id) : info.getThreadName();
         rows.add(new String[]{name, Long.toString(d)});
         sum += d;
      }
      rows.sort((a, b) -> Long.compare(Long.parseLong(b[1]), Long.parseLong(a[1])));
      File f = new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-threads.out");
      try (FileWriter w = new FileWriter(f)) {
         w.write("# route_seconds=" + secs + " cores=" + Runtime.getRuntime().availableProcessors() + "\n");
         long proc = processCpuNs();
         if (proc >= 0 && processCpuAtStart >= 0) {
            w.write("# process_cpu_ms=" + (proc - processCpuAtStart) / 1_000_000 + " process_share=" + ((proc - processCpuAtStart) / 1e9 / secs) + "\n");
         }
         w.write("# live_threads_cpu_ms=" + sum / 1_000_000 + "\n");
         w.write("thread\tcpu_ms\tshare_of_wall\n");
         for (String[] r : rows) {
            long ns = Long.parseLong(r[1]);
            w.write(r[0] + "\t" + ns / 1_000_000 + "\t" + String.format(java.util.Locale.ROOT, "%.3f", ns / 1e9 / secs) + "\n");
         }
      } catch (IOException e) {
         Log.warn("harness: could not write thread cpu summary: " + e);
      }
   }

   private static void reject(String reason) {
      rejected = true;
      HarnessFlags.setRejectReason(reason);
      runEndEpochMs = System.currentTimeMillis();
      writeSummary(0f, 0);
      Log.warn("harness: rejected driving run: " + reason);
      Stats.flush();
      state = DONE;
      requestQuit();
   }
}
