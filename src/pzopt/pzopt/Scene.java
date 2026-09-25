package pzopt;

import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.iso.weather.ClimateManager;

/**
 * Scene conditions for a harness run (the run.sh presets): time of day, weather and the player's
 * torch. Read from the flag file:
 * <ul>
 *   <li>{@code time_of_day=H} — game hour (0-24, fractional ok) forced when the world is up, so the
 *       lighting rebake a jump to night causes is over before the route;</li>
 *   <li>{@code weather=storm|clear} — {@code storm} stops the save's own weather period and
 *       weather generation, then pins the climate overrides a stock STAGE_STORM sets (rain 1.0,
 *       full cloud, wind, dark ambient) every frame, plus a lightning strike next to the player every
 *       {@code thunder_secs} seconds (default 6) so the flash + forced grid-stack recalc is part of
 *       every run at the same instants; {@code clear} stops weather and pins the fair-weather values;</li>
 *   <li>{@code fog=heavy|off|0..1} — pins the fog intensity ({@code heavy} = 1.0, the value a stock
 *       BLIZZARD stage without its own roll uses; a storm stage rolls at most 0.5) every frame. On its own it
 *       stops the save's weather period too (every WeatherPeriod stage re-pins fog to 0) but leaves the other
 *       climate values to the save; combined with {@code weather=storm} the stock storm fog tint is pinned as
 *       well, so {@code storm} + {@code fog=heavy} is the heaviest STAGE_STORM the game can roll. Rendered by
 *       {@code ImprovedFog} (options {@code fogQuality} 0/1) or the legacy fog circle + particles (2); the
 *       sandbox {@code MaxFogIntensity} cap is logged, not overridden;</li>
 *   <li>{@code see_all=true} — the native lighting marks every square seen and visible (the spectator view a
 *       dead player had in B41; {@code LightingJNI} override): the never-seen black behind buildings is gone, so a
 *       dense downtown (Louisville preset) is fully drawn. More visible tiles and characters than a normal view;
 *       compare only runs with the same value;</li>
 *   <li>{@code population=N|max} — sandbox zombie population multipliers (ZombieConfig.PopulationMultiplier,
 *       PopulationStartMultiplier and PopulationPeakMultiplier, 0-4, {@code max} = 4) pushed to the native
 *       population manager at world-ready ({@code onConfigReloaded}). Cells the bench save never visited get
 *       their zombies generated with the new multiplier when their chunks first load, so with a far
 *       {@code start=} teleport (Louisville preset) the whole route is populated at that density; already
 *       visited cells keep their saved population;</li>
 *   <li>{@code car_spawn=1..5} — sandbox CarSpawnRate (1 none, 2 very low, 3 low, 4 normal, 5 high) forced at
 *       world-ready, before the {@code start=} teleport: chunks the bench save never visited spawn their parked
 *       cars with it when they first load ({@code IsoChunk.AddVehicles}); the summary counts {@code vehicles_loaded}
 *       (vehicle-spawn rig, 2026-09-23);</li>
 *   <li>{@code zombies=off} — no zombies at all: the population multipliers go to 0 (as {@code population=0})
 *       and every zombie loaded with the save or streamed in later is removed from the world on each tick
 *       (the game's own removeFromWorld + removeFromSquare pair), so a manual walk on a copy of a real save
 *       is not interrupted by grabs;</li>
 *   <li>{@code torch=on|off} — {@code on} puts a lit Base.HandTorch in the player's primary hand
 *       (a cone light that follows the facing, so with {@code turn} it sweeps the lighting grid);
 *       {@code off} makes sure no light item is equipped;</li>
 *   <li>{@code visible=true} — the bench player is not made invisible (the harness default, so zombies
 *       ignore the player, is what {@code LightingJNI.playerSet} receives as ghost mode). Not needed for the
 *       torch: the beam draws with the invisible player too (seen live on every night-torch run of
 *       2026-09-20); it only never shows in the {@code --shot-at} captures, which hold the player still for
 *       2 s first;</li>
 *   <li>{@code helicopter=true} — the stock helicopter meta event ({@code IsoWorld.helicopter.setTarget(player)}) at the
 *       route start, moved next to the player at once (stock spawns it 1000 tiles out and it takes ~60 s to arrive),
 *       so it hovers / searches over the route for its 60 s and adds its 500-radius world sound at the stock random
 *       cadence (~every 10 s) from wherever it is, a moving big source. A state line every 5 s;
 *       {@code helicopter=} in pzopt-bench.out with the {@code devWorldSoundTiming} big-sound bucket, which on the
 *       bench route (no vehicles) is exactly the helicopter's calls;</li>
 *   <li>{@code sound=R} — a stock world sound of radius R (volume R, no source) every {@code sound_every}
 *       frames (default 1: what {@code zombie.iso.Alarm.update} does for the ~49 s a house alarm rings, at 600)
 *       from the player's square, or from the square of the first call with {@code sound_fixed=true} (a
 *       ringing alarm sits still; the memo in the FishSchoolManager override only helps a still source).
 *       {@code addSound} is timed on the game thread; {@code sound_parts=true} also times its two public
 *       sub-steps with an extra call each (the fish-scaring walk, the native population scan). A summary line
 *       every 5 s in the console and {@code sound_stats=} in pzopt-bench.out. Rig of 2026-09-22, runs
 *       {@code sound-*}.</li>
 *   <li>{@code house_alarm=D}, {@code car_alarm=D}, {@code sound_probe=true} — the stock house and car alarms placed
 *       next to the route at its start, and the once-a-second census of the sound engine: {@link SoundProbe}.</li>
 * </ul>
 * Unset keys leave the save as it is. Nothing here is written back: runs use the bench save copy.
 */
public final class Scene {
   private static float timeOfDay = -1f;
   private static String weather = "";
   private static float fog = -1f;
   private static String torch = "";
   private static int fires; // fire=N: N fires lit near the player at the route start (HDR scenes)
   private static boolean headlights;
   private static float puddles = -1F; // puddles=V: wet ground and puddle size pinned to V (0..1) through the puddles' admin overrides (HDR glint scenes) // headlights=on: the player's vehicle keeps its headlights on (HDR night drives)
   private static String lights = ""; // lights=on: grid power on and every light switch within 50 tiles on (HDR scenes)
   private static boolean visible;
   private static float thunderSecs = 6f;
   private static float population = -1f;
   private static int carSpawn = -1;
   private static boolean zombiesOff;
   private static int zombiesRemoved;
   private static volatile boolean seeAll;
   private static long lastThunderNs;
   private static int thunderCount;
   private static InventoryItem torchItem;
   private static long lastTorchLogNs;
   private static java.lang.reflect.Field jniActiveTorches;
   // sound=R: a world sound of radius R (volume R) at the player's square every sound_every frames (default 1,
   // the stock house-alarm pattern: Alarm.update() adds a 600-radius sound each frame for ~49 s), timed per call;
   // sound_parts=true also times the two public sub-steps on their own (extra calls, only for the breakdown)
   private static int soundRadius;
   private static int soundEvery = 1;
   private static boolean soundParts;
   private static boolean soundFixed;
   private static boolean helicopter;
   private static long lastHeliLogNs; // sound_fixed=true: every call from the square of the first one (a house alarm), not the moving player
   private static int soundX = Integer.MIN_VALUE, soundY;
   private static long soundFrames, soundCalls, soundTotalNs, soundMaxNs, soundFishNs, soundPopNs, soundFishMaxNs, soundPopMaxNs, lastSoundLogNs;

   private Scene() {
   }

   /** Read the flags once (called at world-ready) and apply them to the player and the climate. */
   static void apply(IsoPlayer p) {
      timeOfDay = Float.parseFloat(HarnessFlags.get("time_of_day", "-1"));
      weather = HarnessFlags.get("weather", "").trim().toLowerCase(java.util.Locale.ROOT);
      fog = parseFog(HarnessFlags.get("fog", ""));
      fogTintDark = "dark".equalsIgnoreCase(HarnessFlags.get("fog_tint", "").trim());
      torch = HarnessFlags.get("torch", "").trim().toLowerCase(java.util.Locale.ROOT);
      thunderSecs = Float.parseFloat(HarnessFlags.get("thunder_secs", "6"));
      visible = Boolean.parseBoolean(HarnessFlags.get("visible", "false"));
      population = parsePopulation(HarnessFlags.get("population", ""));
      carSpawn = Integer.parseInt(HarnessFlags.get("car_spawn", "-1").trim());
      zombiesOff = "off".equalsIgnoreCase(HarnessFlags.get("zombies", "").trim());
      if (zombiesOff && population < 0f) {
         population = 0f;
      }
      seeAll = Boolean.parseBoolean(HarnessFlags.get("see_all", "false"));
      soundRadius = Integer.parseInt(HarnessFlags.get("sound", "0").trim());
      soundEvery = Math.max(1, Integer.parseInt(HarnessFlags.get("sound_every", "1").trim()));
      soundParts = Boolean.parseBoolean(HarnessFlags.get("sound_parts", "false"));
      soundFixed = Boolean.parseBoolean(HarnessFlags.get("sound_fixed", "false"));
      helicopter = Boolean.parseBoolean(HarnessFlags.get("helicopter", "false"));
      fires = Integer.parseInt(HarnessFlags.get("fire", "0").trim());
      lights = HarnessFlags.get("lights", "").trim().toLowerCase(java.util.Locale.ROOT);
      headlights = "on".equalsIgnoreCase(HarnessFlags.get("headlights", "").trim());
      puddles = Float.parseFloat(HarnessFlags.get("puddles", "-1").trim());
      Showcase.apply(); // showcase=horde
      Showcase.worldReady(p); // showcase=horde: god mode, unseen, power off, the area cleared until the scene starts
      Explore.worldReady(p); // explore=restaurant: the walk through the nearest restaurant
      SoundProbe.apply(); // house_alarm / car_alarm / sound_probe
      if (soundRadius > 0) {
         Log.info("harness: sound=" + soundRadius + " every " + soundEvery + " frame(s) from the player's square, hearing="
               + zombie.SandboxOptions.instance.lore.hearing.getValue() + " (1=pinpoint x3, 2=normal x1, 3=poor x0.45)" + (soundParts ? ", parts timed" : "") + (soundFixed ? ", fixed square" : ""));
      }
      if (!requested()) {
         return;
      }
      if (seeAll) {
         Log.info("harness: see_all: every square is marked seen and visible by the native lighting (LightingJNI override)");
      }
      if (population >= 0f) {
         // before any chunk of the route loads (Harness teleports to start= right after this): the native
         // popman reads these at chunk-add time for cells without saved population data
         zombie.SandboxOptions.ZombieConfig cfg = zombie.SandboxOptions.instance.zombieConfig;
         cfg.populationMultiplier.setValue(population);
         cfg.populationStartMultiplier.setValue(population);
         cfg.populationPeakMultiplier.setValue(population);
         zombie.popman.ZombiePopulationManager.instance.onConfigReloaded();
         Log.info("harness: zombie population multipliers forced to " + population + " (start/peak too); zombies loaded now: " + zombiesLoaded());
      }
      if (carSpawn > 0) {
         zombie.SandboxOptions.instance.carSpawnRate.setValue(carSpawn); // read per chunk in IsoChunk.AddVehicles
         Log.info("harness: car spawn rate forced to " + carSpawn + " (1 none .. 5 high)");
      }
      if (zombiesOff) {
         removeZombies();
         Log.info("harness: zombies=off: " + zombiesRemoved + " zombies removed at world-ready; any that stream in later are removed too");
      }
      if (visible) {
         p.setInvisible(false, true); // ghost mode off for the native lighting (opt-in; zombies then react to the player)
         Log.info("harness: player visible (ghost mode off; god mode " + p.isGodMod() + ")");
      }
      if (timeOfDay >= 0f) {
         GameTime gt = GameTime.getInstance();
         float h = timeOfDay % 24f;
         gt.setLastTimeOfDay(h);
         gt.setTimeOfDay(h);
         // the cached day info (dawn/dusk) is per date, which does not change; night strength is recomputed
         // from the hour on every climate tick. (forceDayInfoUpdate() here NPEs: currentDay is still null before
         // the first climate tick, preset-night-torch-1.)
         Log.info("harness: time of day forced to " + h + " h");
      }
      if (!weather.isEmpty()) {
         ClimateManager cm = ClimateManager.getInstance();
         if ("storm".equals(weather) || "clear".equals(weather)) {
            cm.stopWeatherAndThunder();          // the save's own weather period would fight the overrides
            cm.setEnabledWeatherGeneration(false); // ... and no new one may start mid-route
            assertWeather(cm);
            Log.info("harness: weather forced to " + weather + ("storm".equals(weather) ? ", lightning every " + thunderSecs + " s" : ""));
         } else {
            Log.warn("harness: unknown weather '" + weather + "' (storm|clear); leaving the save's weather");
            weather = "";
         }
      }
      if (fog >= 0f) {
         ClimateManager cm = ClimateManager.getInstance();
         if (weather.isEmpty()) {
            // a running weather period re-pins FOG_INTENSITY to 0 on every stage but STORM/BLIZZARD, so the
            // fog flag alone still has to stop it; the other climate values stay the save's own
            cm.stopWeatherAndThunder();
            cm.setEnabledWeatherGeneration(false);
         }
         assertFog(cm);
         int maxFog = zombie.SandboxOptions.instance.maxFogIntensity.getValue();
         int fogCycle = zombie.SandboxOptions.instance.fogCycle.getValue();
         Log.info("harness: fog forced to " + fog + (fog > 0f && "storm".equals(weather) ? " with the storm tint" : "")
               + "; fogQuality=" + zombie.core.PerformanceSettings.fogQuality + " (0/1 ImprovedFog, 2 legacy circle)"
               + ", sandbox MaxFogIntensity=" + maxFog + " FogCycle=" + fogCycle);
         if (maxFog != 1) {
            Log.warn("harness: sandbox MaxFogIntensity=" + maxFog + " caps the rendered fog (1 = uncapped, 2 = 0.75, 3 = 0.5, 4 = none)");
         }
         if (fogCycle > 1) {
            Log.warn("harness: sandbox FogCycle=" + fogCycle + " drives its own fog override; the pinned value may not hold");
         }
      }
      if ("on".equals(torch) || "off".equals(torch)) {
         // strip every light the character already carries: the bench save's pistol has an always-on weapon light
         // (setActivated(false) does nothing for it) and getActiveLightItem prefers the secondary hand, so torch=off
         // would still leave a player light
         java.util.ArrayList<InventoryItem> lights = p.getActiveLightItems(new java.util.ArrayList<>());
         for (InventoryItem lit : lights) {
            lit.setActivated(false);
            if (lit.isEmittingLight()) {
               if (p.getPrimaryHandItem() == lit) p.setPrimaryHandItem(null);
               if (p.getSecondaryHandItem() == lit) p.setSecondaryHandItem(null);
               p.removeAttachedItem(lit);
               Log.info("harness: unequipped always-on light " + lit.getFullType());
            } else {
               Log.info("harness: deactivated light " + lit.getFullType());
            }
         }
      }
      if ("on".equals(torch)) {
         try {
            torchItem = p.getInventory().AddItem("Base.HandTorch");
            if (torchItem == null) {
               Log.warn("harness: could not create Base.HandTorch");
            } else {
               p.setPrimaryHandItem(torchItem);
               torchItem.setActivated(true);
               Log.info("harness: torch on (Base.HandTorch, strength " + torchItem.getLightStrength() + ", distance " + torchItem.getLightDistance()
                     + ", cone " + torchItem.isTorchCone() + ", emitting " + torchItem.isEmittingLight() + "); player torch strength "
                     + p.getTorchStrength() + " distance " + p.getLightDistance() + " cone " + p.isTorchCone());
            }
         } catch (Exception e) {
            Log.warn("harness: torch on failed: " + e);
         }
      } else if ("off".equals(torch)) {
         Log.info("harness: torch off; player torch strength " + p.getTorchStrength() + " (active light item " + (p.getActiveLightItem() == null ? "none" : p.getActiveLightItem().getFullType()) + ")");
      } else if (!torch.isEmpty()) {
         Log.warn("harness: unknown torch '" + torch + "' (on|off)");
         torch = "";
      }
   }

   private static int crowd = -1;
   private static long crowdAtNs;

   /**
    * crowd=N (2026-09-25, the sun shadow rig): N zombies spawned once, 3 s after the first tick, on dry outdoor squares
    * 4-14 squares around the player (who is a ghost in bench runs: they stand and wander, a steady crowd on screen).
    */
   private static void crowdTick(IsoPlayer p, long nowNs) {
      if (crowd < 0) {
         crowd = Integer.parseInt(HarnessFlags.get("crowd", "0").trim());
         crowdAtNs = nowNs + 3_000_000_000L;
      }
      if (crowd <= 0 || nowNs < crowdAtNs) {
         return;
      }
      int want = crowd;
      crowd = 0;
      // the crowd must not end the run: cs-shade1 / cs-shade2 (2026-09-25) died to it on foot (setGodMod and the cheat flag
      // leave the player mortal outside debug mode); the zombies are made useless below
      p.getCheats().set(zombie.characters.CheatType.GOD_MODE, true);
      p.setInvisible(true, true);
      zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
      java.util.ArrayList<zombie.iso.IsoGridSquare> ground = new java.util.ArrayList<>();
      int px = (int)Math.floor(p.getX()), py = (int)Math.floor(p.getY());
      for (int y = py - 14; y <= py + 14; y++) {
         for (int x = px - 14; x <= px + 14; x++) {
            int d2 = (x - px) * (x - px) + (y - py) * (y - py);
            zombie.iso.IsoGridSquare sq = cell.getGridSquare(x, y, 0);
            if (d2 >= 16 && d2 <= 196 && sq != null && sq.isOutside() && sq.isFree(false) && !sq.isWaterSquare()) {
               ground.add(sq);
            }
         }
      }
      java.util.Collections.shuffle(ground, new java.util.Random(42));
      int spawned = 0;
      for (int i = 0; i < ground.size() && spawned < want; i++) {
         zombie.iso.IsoGridSquare sq = ground.get(i);
         java.util.ArrayList<zombie.characters.IsoZombie> list = zombie.Lua.LuaManager.GlobalObject.addZombiesInOutfit(sq.x, sq.y, 0, 1, null, 50);
         if (list != null) {
            for (zombie.characters.IsoZombie z : list) {
               z.setUseless(true); // never targets anyone (IsoZombie spotted / RespondToSound): stands and idles, still animated
            }
            spawned += list.size();
         }
      }
      Log.info("harness: crowd: " + spawned + " zombies spawned around " + px + "," + py + " (" + ground.size() + " outdoor squares)");
   }

   /** Per-frame upkeep while the run is live: keep the overrides pinned and fire the scheduled lightning. */
   static void tick(IsoPlayer p, long nowNs) {
      if (headlights && p.getVehicle() != null && p.getVehicle().hasHeadlights() && !p.getVehicle().getHeadlightsOn()) {
         p.getVehicle().setHeadlightsOn(true);
         Log.info("harness: headlights on (" + p.getVehicle().getScriptName() + "), battery " + p.getVehicle().getBatteryCharge()
               + ", can emit light " + p.getVehicle().getHeadlightCanEmmitLight());
      }
      keepWornItems(p); // the bench player keeps their glasses (screen blur otherwise; see pinWornItems)
      Showcase.tick(p, nowNs); // showcase=horde: aim, fire, keep the lights on
      Explore.tick(p, nowNs); // explore=restaurant: walk, look around
      ThumpRig.tick(p, nowNs); // thump=N: zombies thumping a door off-screen (the thump-burst repro)
      crowdTick(p, nowNs); // crowd=N: a crowd around the player (the capsule shadow rig)
      if (zombiesOff) {
         removeZombies();
      }
      if (soundRadius > 0) {
         fireSound(p, nowNs);
      }
      SoundProbe.tick(p, nowNs);
      if (helicopter && nowNs - lastHeliLogNs >= 5_000_000_000L) {
         lastHeliLogNs = nowNs;
         Log.info("harness: helicopter " + helicopterState());
      }
      if (!torch.isEmpty() && nowNs - lastTorchLogNs >= 5_000_000_000L) {
         lastTorchLogNs = nowNs;
         Log.info("harness: torch check: player strength " + p.getTorchStrength() + " dist " + p.getLightDistance() + " cone " + p.isTorchCone()
               + " primary=" + (p.getPrimaryHandItem() == null ? "none" : p.getPrimaryHandItem().getFullType())
               + " activated=" + (torchItem != null && torchItem.isActivated()) + " inInventory=" + (torchItem != null && p.getInventory().contains(torchItem))
               + " jniTorches=" + jniTorchCount() + " invisible=" + p.isInvisible() + " night=" + GameTime.getInstance().getNight()
               + " | square lighting (player, +3, +6 tiles ahead): " + squareLight(p, 0) + " " + squareLight(p, 3) + " " + squareLight(p, 6));
      }
      if (weather.isEmpty() && fog < 0f) {
         return;
      }
      ClimateManager cm = ClimateManager.getInstance();
      if (!weather.isEmpty()) {
         assertWeather(cm);
      }
      if (fog >= 0f) {
         assertFog(cm);
      }
      if ("storm".equals(weather) && thunderSecs > 0f) {
         if (lastThunderNs == 0L) {
            lastThunderNs = nowNs; // first strike one interval after the route starts, not on frame one
         } else if (nowNs - lastThunderNs >= (long)(thunderSecs * 1e9)) {
            lastThunderNs = nowNs;
            thunderCount++;
            // a strike 60 tiles from the player: inside the 7500-tile lightning range at near-full strength,
            // so ThunderStorm applies the flash (dayLightStrength ramp + dirtyRecalcGridStackTime = 1 for ~100 frames)
            cm.getThunderStorm().triggerThunderEvent((int)p.getX() + 60, (int)p.getY() - 60, true, true, true);
         }
      }
   }

   /** sound=R: one stock addSound call from the player's square, timed; a summary line every 5 s. */
   private static void fireSound(IsoPlayer p, long nowNs) {
      if (soundFrames++ % soundEvery != 0) {
         return;
      }
      int x = (int) p.getX(), y = (int) p.getY();
      if (soundFixed) {
         if (soundX == Integer.MIN_VALUE) {
            soundX = x;
            soundY = y;
         }
         x = soundX;
         y = soundY;
      }
      long t0 = System.nanoTime();
      zombie.WorldSoundManager.WorldSound s = zombie.WorldSoundManager.instance.addSound(null, x, y, 0, soundRadius, soundRadius);
      long dt = System.nanoTime() - t0;
      soundCalls++;
      soundTotalNs += dt;
      if (dt > soundMaxNs) soundMaxNs = dt;
      if (soundParts && s != null) {
         long t1 = System.nanoTime();
         zombie.iso.FishSchoolManager.getInstance().addSoundNoise(x, y, soundRadius / 6); // what WorldSound.init does (single player)
         long fish = System.nanoTime() - t1;
         long t2 = System.nanoTime();
         zombie.popman.ZombiePopulationManager.instance.addWorldSound(s, false); // the native popman scan
         long pop = System.nanoTime() - t2;
         soundFishNs += fish;
         soundPopNs += pop;
         if (fish > soundFishMaxNs) soundFishMaxNs = fish;
         if (pop > soundPopMaxNs) soundPopMaxNs = pop;
      }
      if (nowNs - lastSoundLogNs >= 5_000_000_000L) {
         lastSoundLogNs = nowNs;
         Log.info("harness: sound=" + soundRadius + ": " + soundStats());
      }
   }

   private static String soundStats() {
      if (soundCalls == 0) return "no calls";
      String out = String.format(java.util.Locale.ROOT, "%d calls, addSound mean %.3f ms max %.3f ms", soundCalls, soundTotalNs / 1e6 / soundCalls, soundMaxNs / 1e6);
      if (soundParts) {
         out += String.format(java.util.Locale.ROOT, "; parts: FishSchoolManager.addSoundNoise mean %.3f max %.3f ms, ZombiePopulationManager.addWorldSound mean %.3f max %.3f ms",
               soundFishNs / 1e6 / soundCalls, soundFishMaxNs / 1e6, soundPopNs / 1e6 / soundCalls, soundPopMaxNs / 1e6);
      }
      out += "; world sounds live=" + zombie.WorldSoundManager.instance.soundList.size();
      if (Config.DEV_WORLD_SOUND_TIMING) {
         out += "; sections: " + zombie.WorldSoundManager.pzoptTiming();
      }
      return out;
   }

   /** The JNI lighting of the square n tiles along the player's facing: canSee/darkMulti/target/rgb. */
   private static String squareLight(IsoPlayer p, int ahead) {
      try {
         float fx = p.getForwardDirectionX(), fy = p.getForwardDirectionY();
         zombie.iso.IsoGridSquare sq = p.getCell().getGridSquare((int)(p.getX() + fx * ahead), (int)(p.getY() + fy * ahead), (int)p.getZ());
         if (sq == null) return "null";
         zombie.iso.IsoGridSquare.ILighting l = sq.lighting[p.getIndex()];
         if (l == null) return "nolighting";
         return String.format(java.util.Locale.ROOT, "[see=%b dark=%.2f target=%.2f rgb=%.2f,%.2f,%.2f]", l.bCanSee(), l.darkMulti(), l.targetDarkMulti(),
               l.lightInfo().r, l.lightInfo().g, l.lightInfo().b);
      } catch (Exception e) {
         return "err:" + e;
      }
   }

   /** Number of torches LightingJNI handed to the native lighting on its last pass (private static list, read reflectively). */
   private static int jniTorchCount() {
      try {
         if (jniActiveTorches == null) {
            jniActiveTorches = zombie.iso.LightingJNI.class.getDeclaredField("activeTorches");
            jniActiveTorches.setAccessible(true);
         }
         return ((java.util.List<?>)jniActiveTorches.get(null)).size();
      } catch (Exception e) {
         return -1;
      }
   }

   /** Reset the per-run counters (route start), so the strike cadence is counted from the route. */
   static void routeStart(long nowNs) {
      lastThunderNs = nowNs;
      thunderCount = 0;
      if (helicopter) {
         startHelicopter();
      }
      if (fires > 0) {
         startFires();
      }
      if (puddles >= 0F) {
         zombie.iso.IsoPuddles ip = zombie.iso.IsoPuddles.getInstance();
         for (int id : new int[] {1, 3}) { // wet ground, puddle size
            ip.getPuddlesFloat(id).setEnableAdmin(true);
            ip.getPuddlesFloat(id).setAdminValue(puddles);
         }
         Log.info("harness: puddles pinned to " + puddles + " (wet ground + puddle size, admin override)");
      }
      if ("on".equals(lights)) {
         lightsOn();
      }
      SoundProbe.routeStart(IsoPlayer.getInstance(), nowNs);
   }

   /** fire=N: N fires in a row 4 tiles south-east of the player, 2 tiles apart (IsoFireManager, may spread). */
   private static void startFires() {
      try {
         IsoPlayer p = IsoPlayer.getInstance();
         zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
         int px = (int)p.getX(), py = (int)p.getY(), pz = (int)p.getZ(), lit = 0;
         for (int i = 0; i < fires; i++) {
            zombie.iso.IsoGridSquare sq = cell.getGridSquare(px + 4 + 2 * i, py + 4 - i, pz);
            if (sq != null) {
               zombie.iso.objects.IsoFireManager.StartFire(cell, sq, true, 100);
               lit++;
            }
         }
         Log.info("harness: fire=" + fires + ": " + lit + " fires started near " + px + "," + py + "," + pz);
      } catch (Exception e) {
         Log.warn("harness: fire start failed: " + e);
      }
   }

   /** lights=on: grid power on, then every light switch within 50 tiles (levels 0-3) switched on, electricity check ignored. */
   private static void lightsOn() {
      IsoPlayer p = IsoPlayer.getInstance();
      lightsOnAround((int)p.getX(), (int)p.getY());
   }

   static boolean lightsFlag() {
      return "on".equals(lights);
   }

   /** The same around a spot (explore=restaurant: the restaurant, loaded once the walk gets near it). */
   static void lightsOnAround(int px, int py) {
      try {
         zombie.iso.IsoWorld.instance.setHydroPowerOn(true);
         zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
         int on = 0;
         for (int z = 0; z < 4; z++) {
            for (int y = py - 50; y <= py + 50; y++) {
               for (int x = px - 50; x <= px + 50; x++) {
                  zombie.iso.IsoGridSquare sq = cell.getGridSquare(x, y, z);
                  if (sq == null) {
                     continue;
                  }
                  for (int i = 0; i < sq.getObjects().size(); i++) {
                     if (sq.getObjects().get(i) instanceof zombie.iso.objects.IsoLightSwitch ls && !ls.isActivated()) {
                        ls.setActive(true, false, true);
                        on++;
                     }
                  }
               }
            }
         }
         Log.info("harness: lights=on: hydro power on, " + on + " light switches switched on within 50 tiles of " + px + "," + py);
      } catch (Exception e) {
         Log.warn("harness: lights on failed: " + e);
      }
   }

   /** helicopter=true: the stock event, started at the route start and placed 40 tiles from the player. */
   private static void startHelicopter() {
      try {
         IsoPlayer p = IsoPlayer.getInstance();
         zombie.iso.Helicopter h = zombie.iso.IsoWorld.instance.helicopter;
         h.setTarget(p); // stock: 1000 tiles out, State.Arriving, "chopper: activated" in the log
         h.x = p.getX() + 40f; // pzopt rig: within the loaded grid at once; Arriving reaches the target in a few ticks
         h.y = p.getY() - 40f;
         Log.info("harness: helicopter started at " + (int) h.x + "," + (int) h.y + " (player " + (int) p.getX() + "," + (int) p.getY()
               + "); stock 500-radius world sound about every 10 s from its position");
      } catch (Exception e) {
         Log.warn("harness: helicopter start failed: " + e);
      }
   }

   private static String helicopterState() {
      zombie.iso.Helicopter h = zombie.iso.IsoWorld.instance.helicopter;
      IsoPlayer p = IsoPlayer.getInstance();
      String out = "active=" + h.isActive() + " at " + (int) h.x + "," + (int) h.y;
      if (p != null) {
         out += " dist=" + (int) Math.sqrt((h.x - p.getX()) * (h.x - p.getX()) + (h.y - p.getY()) * (h.y - p.getY()));
      }
      if (Config.DEV_WORLD_SOUND_TIMING) {
         out += "; " + zombie.WorldSoundManager.pzoptTiming();
      }
      return out;
   }

   private static void assertWeather(ClimateManager cm) {
      // the values WeatherPeriod.update pins during STAGE_STORM with a strength-0.95 front (see the decompiled
      // zombie.iso.weather.WeatherPeriod, case 3): full rain and cloud, strong wind, dim desaturated light
      boolean storm = "storm".equals(weather);
      set(cm, ClimateManager.FLOAT_PRECIPITATION_INTENSITY, storm ? 1.0f : 0.0f);
      set(cm, ClimateManager.FLOAT_CLOUD_INTENSITY, storm ? 1.0f : 0.0f);
      set(cm, ClimateManager.FLOAT_WIND_INTENSITY, storm ? 0.9f : 0.1f);
      set(cm, ClimateManager.FLOAT_WIND_ANGLE_INTENSITY, storm ? 0.7f : 0.0f);
      if (fog < 0f) {
         set(cm, ClimateManager.FLOAT_FOG_INTENSITY, 0.0f); // no fog flag: a storm/clear run has no fog (the 2026-09-20 baselines)
      }
      set(cm, ClimateManager.FLOAT_DESATURATION, storm ? 0.3f : 0.0f);
      set(cm, ClimateManager.FLOAT_GLOBAL_LIGHT_INTENSITY, storm ? 0.4f : 1.0f);
      set(cm, ClimateManager.FLOAT_AMBIENT, storm ? 0.45f : 1.0f);
      set(cm, ClimateManager.FLOAT_DAYLIGHT_STRENGTH, storm ? 0.45f : 1.0f);
      cm.getClimateBool(ClimateManager.BOOL_IS_SNOW).setOverride(false);
   }

   // fog_tint=dark: a near-black fog (the storm tint 0.5/0.45/0.4 glowed beige over a pitch-black night, cine-jev1)
   private static boolean fogTintDark;
   private static final zombie.iso.weather.ClimateColorInfo DARK_FOG = new zombie.iso.weather.ClimateColorInfo(0.06f, 0.06f, 0.07f, 1.0f, 0.06f, 0.06f, 0.07f, 1.0f);

   private static void assertFog(ClimateManager cm) {
      set(cm, ClimateManager.FLOAT_FOG_INTENSITY, fog);
      if (fog > 0f && "storm".equals(weather)) {
         // what WeatherPeriod.update pins for a STAGE_STORM whose stage rolled fog (case 3, fogStrength > 0)
         cm.getClimateColor(ClimateManager.COLOR_NEW_FOG).setOverride(fogTintDark ? DARK_FOG : cm.getFogTintStorm(), 1.0f);
      } else if (fog > 0f && fogTintDark) {
         cm.getClimateColor(ClimateManager.COLOR_NEW_FOG).setOverride(DARK_FOG, 1.0f);
      }
   }

   /** {@code heavy} = 1, {@code off} = 0, a number = that intensity (clamped to 0..1); unset or unknown = -1. */
   private static float parseFog(String raw) {
      String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
      if (v.isEmpty()) return -1f;
      if ("heavy".equals(v) || "on".equals(v)) return 1f;
      if ("off".equals(v) || "none".equals(v)) return 0f;
      try {
         return Math.max(0f, Math.min(1f, Float.parseFloat(v)));
      } catch (NumberFormatException e) {
         Log.warn("harness: unknown fog '" + raw + "' (heavy|off|0..1); leaving the save's fog");
         return -1f;
      }
   }

   private static void set(ClimateManager cm, int id, float value) {
      cm.getClimateFloat(id).setOverride(value, 1.0f);
   }

   static boolean requested() {
      return timeOfDay >= 0f || !weather.isEmpty() || fog >= 0f || !torch.isEmpty() || visible || population >= 0f || carSpawn > 0 || seeAll || zombiesOff || soundRadius > 0 || helicopter || fires > 0 || !lights.isEmpty() || headlights || puddles >= 0F || SoundProbe.requested();
   }

   /** Flag see_all=true: read by the LightingJNI override on every player update (false until apply() ran). */
   public static boolean seeAll() {
      return seeAll;
   }

   /**
    * Nothing the bench player wears may fall off during a run. God mode only cancels the health loss of a zombie
    * hit or a fall: {@code BodyDamage.AddRandomDamageFromZombie} and {@code handleLandingImpact} still roll
    * {@code helmetFall}, which drops a hat or glasses with {@code Clothing.chanceToFall} and fires
    * {@code OnClothingUpdated}. A short-sighted character who loses their glasses then gets
    * {@code blurFactorTarget = 1}: {@code screen.frag} blurs the whole world outside a small circle around the
    * player for the rest of the run (Louisville preset, 2026-09-22: the horde bumps the ghost player, the
    * optimized side of the video looked soft, the stock side had kept its glasses by chance). A zero chance
    * makes {@code helmetFallFromWornItems} skip the item, so the character keeps every worn item and the
    * screen stays as sharp as the save left it. Called after god / ghost mode; returns the items pinned.
    */
   static int pinWornItems(IsoPlayer p) {
      zombie.characters.WornItems.WornItems worn = p.getWornItems();
      pinnedWorn.clear();
      wornRestored = 0;
      if (worn == null) {
         return 0;
      }
      int pinned = 0;
      for (int i = 0; i < worn.size(); i++) {
         InventoryItem item = worn.getItemByIndex(i);
         if (item instanceof zombie.inventory.types.Clothing clothing) {
            if (clothing.getChanceToFall() > 0) {
               clothing.setChanceToFall(0);
               pinned++;
            }
            pinnedWorn.put(clothing.getBodyLocation(), clothing);
         }
      }
      return pinned;
   }

   private static final java.util.LinkedHashMap<zombie.scripting.objects.ItemBodyLocation, zombie.inventory.types.Clothing> pinnedWorn = new java.util.LinkedHashMap<>();
   private static int wornRestored;

   /**
    * Every frame: a pinned item that left its body location (the first pinned Louisville run still ended with
    * {@code eyes=none}: the zero chance stops {@code helmetFall}, other paths such as a broken item's
    * {@code Clothing.setCondition} unwear and drop it) is worn again and the vision effects are recomputed at once,
    * so the blur target never flips. The dropped copy on the floor of the bench save is left alone.
    */
   static void keepWornItems(IsoPlayer p) {
      if (pinnedWorn.isEmpty()) {
         return;
      }
      boolean changed = false;
      for (java.util.Map.Entry<zombie.scripting.objects.ItemBodyLocation, zombie.inventory.types.Clothing> e : pinnedWorn.entrySet()) {
         zombie.inventory.types.Clothing item = e.getValue();
         if (p.getWornItem(e.getKey()) == item) {
            continue;
         }
         if (!p.getInventory().contains(item)) {
            p.getInventory().AddItem(item);
         }
         p.setWornItem(e.getKey(), item);
         wornRestored++;
         changed = true;
      }
      if (changed) {
         p.resetModelNextFrame();
         p.updateVisionEffects();
      }
   }

   /** Times keepWornItems had to put a pinned item back (a hit that would have blurred the screen). */
   static int wornRestored() {
      return wornRestored;
   }

   /** One line for the console: eyewear, the short-sighted trait and the screen blur the vision effects apply. */
   static String visionState(IsoPlayer p) {
      InventoryItem eyes = p.getWornItem(zombie.scripting.objects.ItemBodyLocation.EYES);
      return "eyes=" + (eyes == null ? "none" : eyes.getFullType() + (eyes.isVisualAid() ? " (visual aid)" : ""))
         + " shortSighted=" + p.hasTrait(zombie.scripting.objects.CharacterTrait.SHORT_SIGHTED)
         + " blur=" + String.format(java.util.Locale.ROOT, "%.2f", p.getBlurFactor())
         + " wornRestored=" + wornRestored;
   }

   /** "" = leave the save's sandbox values (-1); "max" = 4 (the sandbox ceiling); numbers are clamped to 0-4. */
   static float parsePopulation(String v) {
      v = v.trim().toLowerCase(java.util.Locale.ROOT);
      if (v.isEmpty()) return -1f;
      if ("max".equals(v) || "insane".equals(v)) return 4f;
      return Math.max(0f, Math.min(4f, Float.parseFloat(v)));
   }

   /** Real (non-virtual) zombies in the loaded cell right now. */
   /** zombies=off: every zombie in the cell's list leaves the world (the pair IsoZombie.update uses for a zombie with no square). */
   private static void removeZombies() {
      try {
         java.util.ArrayList<zombie.characters.IsoZombie> list = new java.util.ArrayList<>(zombie.iso.IsoWorld.instance.getCell().getZombieList());
         for (zombie.characters.IsoZombie z : list) {
            if (ThumpRig.isRigZombie(z)) {
               continue;
            }
            z.removeFromWorld();
            z.removeFromSquare();
            zombiesRemoved++;
         }
      } catch (Exception e) {
         Log.warn("harness: zombies=off: removal failed: " + e);
      }
   }

   static int zombiesLoaded() {
      try {
         return zombie.iso.IsoWorld.instance.getCell().getZombieList().size();
      } catch (Exception e) {
         return -1;
      }
   }

   /** Lines for pzopt-bench.out. */
   static String summary() {
      float night = -1f;
      float precip = -1f;
      float fogNow = -1f;
      float fogFx = -1f;
      try {
         ClimateManager cm = ClimateManager.getInstance();
         night = cm.getNightStrength();
         precip = cm.getPrecipitationIntensity();
         fogNow = cm.getFogIntensity();
         zombie.iso.weather.fx.IsoWeatherFX fx = zombie.iso.IsoWorld.instance.getCell().getWeatherFX();
         if (fx != null) fogFx = fx.getFogIntensity(); // the stepped value the renderer reached (ramps 0.005/tick)
      } catch (Exception ignored) {
      }
      return "time_of_day=" + (timeOfDay >= 0f ? Float.toString(timeOfDay) : "save")
            + "\ngame_hour=" + GameTime.getInstance().getTimeOfDay()
            + "\nweather=" + (weather.isEmpty() ? "save" : weather)
            + "\nfog=" + (fog >= 0f ? Float.toString(fog) : "save")
            + "\ntorch=" + (torch.isEmpty() ? "save" : torch) + "\nvisible=" + visible
            + "\nnight_strength=" + night + "\nprecipitation=" + precip + "\nfog_intensity=" + fogNow + "\nfog_fx=" + fogFx
            + "\nfog_quality=" + zombie.core.PerformanceSettings.fogQuality + "\nlightning_strikes=" + thunderCount
            + "\npopulation=" + (population >= 0f ? Float.toString(population) : "save") + "\nzombies_loaded=" + zombiesLoaded() + "\nzombies_removed=" + zombiesRemoved
            + "\nsee_all=" + seeAll
            + (carSpawn > 0 ? "\ncar_spawn=" + carSpawn + "\nvehicles_loaded=" + zombie.iso.IsoWorld.instance.currentCell.getVehicles().size() : "")
            + (soundRadius > 0 ? "\nsound_radius=" + soundRadius + "\nsound_every=" + soundEvery + "\nsound_stats=" + soundStats() : "")
            + (helicopter ? "\nhelicopter=" + helicopterState() : "") + ThumpRig.summary() + SoundProbe.summary();
   }
}
