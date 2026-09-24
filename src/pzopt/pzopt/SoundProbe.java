package pzopt;

import fmod.fmod.EmitterType;
import fmod.fmod.FMODSoundEmitter;
import fmod.javafmod;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;

/**
 * Harness rig of the sound engine (2026-09-24): two stock alarms placed next to the route and a once-a-second census of
 * what the game hands FMOD, for {@code harness/soundprof.py} and {@code harness/audio.py}.
 * <ul>
 *   <li>{@code house_alarm=D} ({@code true} = 12) — at the route start, a stock {@code zombie.iso.Alarm} (what
 *       {@code AmbientStreamManager.doAlarm} adds for an alarmed building with power) on the ground-floor building square
 *       nearest to D tiles east of the player: the {@code event:/Meta/HouseAlarm} FMOD event for ~49 s plus a
 *       600-radius world sound every frame;</li>
 *   <li>{@code car_alarm=D} ({@code true} = 8) — at the route start, a {@code car_alarm_script} (default
 *       {@code Base.ModernCar}, whose script has {@code alarmLoop}) on the road nearest to D tiles west of the player,
 *       battery full, {@code setAlarmed(true)} + {@code triggerAlarm()}: the stock looping car alarm through the car's
 *       {@code VehicleSounds} emitter and its 150-radius world sound;</li>
 *   <li>{@code gunshots=R} — from the route start, R shots a second (fractional ok) of {@code gunshot_weapon} (default
 *       {@code Base.Pistol}) by the player, the two calls of stock's {@code ISReloadWeaponAction.attackHook}:
 *       {@code playRangedWeaponShootSound(weapon.getSwingSound())} on the player's emitter and the weapon's world sound
 *       (radius x sandbox FirearmNoiseMultiplier, halved indoors, stress humans), made directly because the bench player
 *       is in ghost mode ({@code addWorldSoundUnlessInvisible} would drop it); {@code gunshots=} counts in the census;</li>
 *   <li>{@code sound_probe=true} (default on with either alarm) — every second, settle included (t &lt; 0), one line of
 *       {@code pzopt-sound.out}: mean frame time, zombies loaded, the FMOD emitters {@code SoundManager} holds (by
 *       {@code EmitterType}: vocals / footsteps / extra / other), how many hold a sound, the sound instances playing /
 *       stopping and the most common sound names, the zombie vocal slots in use, live world sounds, both alarms' state
 *       (the car's through its VehicleSounds emitter), every FMOD global parameter's value (Inside, RainIntensity, the
 *       wall distances, zones: what the world ambiance event mixes by), how many event instances FMOD holds virtual (out
 *       of the real-voice budget: {@code FMOD_Studio_EventInstance_IsVirtual} through FFM, {@code virtual_top} = which) and
 *       the WorldAmbiance event's own state ({@code ambiance=real|virtual/<playback>}: the rain / wind bed), FMOD's core
 *       channel counts ({@code fmod_channels=playing/real}, through {@link AudioLimiter}'s system handle).
 *       {@code sound_probe_hz} (default 1) samples faster for timing a gap.
 *       At the route end the CPU of every native thread over the route ({@code /proc/self/task/}/schedstat: FMOD's
 *       mixer / Studio update / stream threads are not Java threads, {@code pzopt-threads.out} never sees them), and
 *       {@code sound_probe=} in pzopt-bench.out.</li>
 * </ul>
 * The census runs on the game thread (reflection over ~3 emitters per zombie, ~1 ms with 2,500 zombies) and shows in the
 * stacks as {@code SoundProbe.sample}; soundprof.py leaves it out.
 */
public final class SoundProbe {
   private static boolean enabled;
   private static int houseDist, carDist;
   private static float gunshotsPerSec;
   private static String gunWeaponType = "Base.Pistol";
   private static zombie.inventory.types.HandWeapon gun;
   private static long nextShotNs;
   private static int shotsFired;
   private static String carScript = "Base.ModernCar";
   private static long startNs, lastSampleNs;
   private static int framesSinceSample;
   private static final List<String> timeline = new ArrayList<>();
   private static Map<String, long[]> threadsAtStart;
   private static long wallAtStartNs;
   private static BaseVehicle car;
   private static int houseX = Integer.MIN_VALUE, houseY;
   private static long sampleNsTotal, sampleNsMax;
   private static int samples;
   private static int maxActive, maxInstances, maxWorldSounds;
   private static long sumInstances, sumActive;
   private static int routeSamples;
   private static Field emittersField, toStartField, instancesField, stoppedField, soundNameField, slotsField, slotCharField, alarmInstField, globalsField,
         eventInstanceField, ambianceInstField;
   // FMOD_Studio_EventInstance_IsVirtual through FFM (libfmodstudio.so exports the C API; the Java side only holds the
   // instance handles): a virtual instance is one FMOD stopped mixing because the real-voice budget is spent
   private static java.lang.invoke.MethodHandle isVirtualFn;
   private static boolean isVirtualTried;
   private static java.lang.foreign.MemorySegment virtOut;
   private static long sampleEveryNs = 1_000_000_000L;

   private SoundProbe() {
   }

   /** Read the flags (Scene.apply, world-ready). */
   static void apply() {
      houseDist = parseDist(HarnessFlags.get("house_alarm", ""), 12);
      carDist = parseDist(HarnessFlags.get("car_alarm", ""), 8);
      carScript = HarnessFlags.get("car_alarm_script", "Base.ModernCar").trim();
      gunshotsPerSec = Math.max(0f, Float.parseFloat(HarnessFlags.get("gunshots", "0").trim()));
      gunWeaponType = HarnessFlags.get("gunshot_weapon", "Base.Pistol").trim();
      paramLog = Boolean.parseBoolean(HarnessFlags.get("sound_param_log", "false"));
      sampleEveryNs = (long)(1e9 / Math.max(0.2f, Float.parseFloat(HarnessFlags.get("sound_probe_hz", "1").trim())));
      enabled = Boolean.parseBoolean(HarnessFlags.get("sound_probe", Boolean.toString(houseDist > 0 || carDist > 0 || gunshotsPerSec > 0f)));
      if (requested()) {
         Log.info("harness: sound probe " + (enabled ? "on" : "off") + (houseDist > 0 ? ", house alarm ~" + houseDist + " tiles east at the route start" : "")
               + (carDist > 0 ? ", car alarm (" + carScript + ") ~" + carDist + " tiles west at the route start" : "")
               + (gunshotsPerSec > 0f ? ", " + gunshotsPerSec + " shots/s of " + gunWeaponType + " from the route start" : ""));
      }
   }

   static boolean requested() {
      return enabled || houseDist > 0 || carDist > 0 || gunshotsPerSec > 0f;
   }

   /** gunshots=R: one stock shot (shoot sound + the weapon's world sound) whenever the next one is due. */
   private static void fireGun(IsoPlayer p, long nowNs) {
      if (gunshotsPerSec <= 0f || startNs == 0L || nowNs < nextShotNs) {
         return;
      }
      long step = (long)(1e9 / gunshotsPerSec);
      nextShotNs = nextShotNs == 0L ? nowNs + step : Math.max(nextShotNs + step, nowNs - step); // no burst after a long frame
      try {
         if (gun == null) {
            Object item = zombie.inventory.InventoryItemFactory.CreateItem(gunWeaponType);
            if (!(item instanceof zombie.inventory.types.HandWeapon w) || !w.isRanged()) {
               Log.warn("harness: gunshot_weapon " + gunWeaponType + " is not a ranged weapon; gunshots off");
               gunshotsPerSec = 0f;
               return;
            }
            gun = w;
            Log.info("harness: gunshots: " + gunWeaponType + " sound " + w.getSwingSound() + " radius " + w.getSoundRadius() + " volume " + w.getSoundVolume()
                  + " x FirearmNoiseMultiplier " + zombie.SandboxOptions.instance.firearmNoiseMultiplier.getValue());
         }
         p.playRangedWeaponShootSound(gun.getSwingSound());
         float radius = (float)(gun.getSoundRadius() * zombie.SandboxOptions.instance.firearmNoiseMultiplier.getValue());
         if (!p.isOutside()) {
            radius *= 0.5f;
         }
         zombie.WorldSoundManager.instance.addSound(p, zombie.core.math.PZMath.fastfloor(p.getX()), zombie.core.math.PZMath.fastfloor(p.getY()),
               zombie.core.math.PZMath.fastfloor(p.getZ()), (int) radius, gun.getSoundVolume(), true);
         shotsFired++;
      } catch (Exception e) {
         Log.warn("harness: gunshot failed: " + e);
         gunshotsPerSec = 0f;
      }
   }

   private static int parseDist(String raw, int dflt) {
      String v = raw.trim().toLowerCase(Locale.ROOT);
      if (v.isEmpty() || "false".equals(v) || "off".equals(v) || "0".equals(v)) return 0;
      if ("true".equals(v) || "on".equals(v)) return dflt;
      try {
         return Math.max(1, Integer.parseInt(v));
      } catch (NumberFormatException e) {
         Log.warn("harness: bad alarm distance '" + raw + "'; using " + dflt);
         return dflt;
      }
   }

   /** Route start: place the alarms, snapshot the native threads. */
   static void routeStart(IsoPlayer p, long nowNs) {
      startNs = nowNs;
      if (!requested()) {
         return;
      }
      threadsAtStart = readThreads();
      wallAtStartNs = System.nanoTime();
      if (p == null || p.getCurrentSquare() == null) {
         return;
      }
      nextShotNs = 0L;
      shotsFired = 0;
      pulseStreams = pulseStreams();
      if (houseDist > 0) {
         startHouseAlarm(p);
      }
      if (carDist > 0) {
         startCarAlarm(p);
      }
   }

   private static void startHouseAlarm(IsoPlayer p) {
      try {
         IsoGridSquare sq = nearestBuildingSquare((int) p.getX() + houseDist, (int) p.getY(), 40);
         if (sq == null) {
            Log.warn("harness: house alarm: no building square within 40 tiles of " + ((int) p.getX() + houseDist) + "," + (int) p.getY());
            return;
         }
         houseX = sq.getX();
         houseY = sq.getY();
         zombie.AmbientStreamManager asm = (zombie.AmbientStreamManager) zombie.AmbientStreamManager.instance;
         asm.alarmList.add(new zombie.iso.Alarm(houseX, houseY)); // what doAlarm adds for an alarmed building with power
         Log.info("harness: house alarm started at " + houseX + "," + houseY + " (player " + (int) p.getX() + "," + (int) p.getY() + ", building "
               + (sq.getBuilding() == null ? "?" : sq.getBuilding().getID()) + "); stock ~49 s, 600-radius world sound every frame");
      } catch (Exception e) {
         Log.warn("harness: house alarm failed: " + e);
      }
   }

   private static IsoGridSquare nearestBuildingSquare(int cx, int cy, int radius) {
      zombie.iso.IsoCell cell = IsoWorld.instance.getCell();
      for (int r = 0; r <= radius; r++) {
         for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
               if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
               IsoGridSquare sq = cell.getGridSquare(cx + dx, cy + dy, 0);
               if (sq != null && sq.getBuilding() != null) return sq;
            }
         }
      }
      return null;
   }

   private static void startCarAlarm(IsoPlayer p) {
      try {
         IsoGridSquare from = IsoWorld.instance.getCell().getGridSquare((int) p.getX() - carDist, (int) p.getY(), 0);
         if (from == null) from = p.getCurrentSquare();
         IsoGridSquare road = Harness.findRoad(from, 30);
         if (road == null) road = from;
         car = zombie.Lua.LuaManager.GlobalObject.addVehicleDebug(carScript, zombie.iso.IsoDirections.N, 0, road);
         if (car == null) {
            Log.warn("harness: car alarm: could not place " + carScript + " at " + road.getX() + "," + road.getY());
            return;
         }
         car.repair();
         VehiclePart battery = car.getPartById("Battery");
         if (battery != null && battery.getInventoryItem() != null) {
            battery.getInventoryItem().setCurrentUsesFloat(1f);
         }
         car.setAlarmed(true); // also clears previouslyEntered
         car.triggerAlarm();
         Log.info("harness: car alarm " + carScript + " at " + road.getX() + "," + road.getY() + " (player " + (int) p.getX() + "," + (int) p.getY()
               + "): hasAlarm=" + car.hasAlarm() + " battery=" + car.getBatteryCharge() + " active=" + car.isAlarmActive() + " sound=" + car.getChosenAlarmSound());
      } catch (Exception e) {
         Log.warn("harness: car alarm failed: " + e);
      }
   }

   // sound_param_log=true: every change of the listener's ambience parameters, frame by frame (a flip shorter than the
   // census period would be missed), with the player's square and whether FMOD's own copy agrees
   private static boolean paramLog;
   private static final java.util.HashMap<String, Float> paramLast = new java.util.HashMap<>();
   private static final List<String> paramChanges = new ArrayList<>();
   private static final String[] PARAM_NAMES = {"Inside", "ClosestWallDistance", "ClosestExteriorWallDistance", "RoomSize", "RoomTypeEx",
         "RainIntensity", "WindIntensity", "Storm", "WeatherEvent", "FogIntensity", "CharacterElevation", "CameraZoom"};

   private static void logParams(IsoPlayer p, long nowNs) {
      try {
         if (globalsField == null) {
            return;
         }
         for (Object o : (List<?>) globalsField.get(fmod.fmod.FMODManager.instance)) {
            zombie.audio.FMODParameter g = (zombie.audio.FMODParameter) o;
            String n = g.getName();
            boolean wanted = false;
            for (String w : PARAM_NAMES) {
               if (w.equals(n)) {
                  wanted = true;
                  break;
               }
            }
            if (!wanted) continue;
            float v = g.getCurrentValue();
            logChange(p, nowNs, n, v);
         }
         logAmbianceOcclusion(p, nowNs);
      } catch (Exception ignored) {
      }
   }

   private static void logChange(IsoPlayer p, long nowNs, String n, float v) {
      Float old = paramLast.put(n, v);
      if (old != null && Float.compare(old, v) != 0 && paramChanges.size() < 20000) {
         IsoGridSquare sq = p.getCurrentSquare();
         paramChanges.add(String.format(Locale.ROOT, "# param t=%.3f %s %.2f->%.2f player=%.2f,%.2f,%.0f room=%s",
               startNs == 0L ? -1.0 : (nowNs - startNs) / 1e9, n, old, v, p.getX(), p.getY(), p.getZ(),
               sq == null ? "?" : Boolean.toString(sq.isInARoom())));
      }
   }

   // The WorldAmbiance emitter (the rain / wind bed) stands on the listener's x, y at z 0; its Occlusion parameter is 1
   // whenever that square is not isCouldSee for the player (ParameterOcclusion). Logged as the pseudo parameters
   // ~ambOcclusion (the emitter's last value, -1 when the event has no Occlusion parameter) and ~couldSee.
   private static java.lang.reflect.Field ambEmitterField, occlusionField, localInstancesField;

   private static void logAmbianceOcclusion(IsoPlayer p, long nowNs) throws ReflectiveOperationException {
      if (ambEmitterField == null) {
         ambEmitterField = zombie.AmbientStreamManager.class.getDeclaredField("worldAmbienceEmitter");
         ambEmitterField.setAccessible(true);
         occlusionField = fmod.fmod.FMODSoundEmitter.class.getDeclaredField("occlusion");
         occlusionField.setAccessible(true);
         localInstancesField = zombie.audio.FMODLocalParameter.class.getDeclaredField("instances");
         localInstancesField.setAccessible(true);
      }
      Object em = ambEmitterField.get(zombie.AmbientStreamManager.instance);
      if (em instanceof fmod.fmod.FMODSoundEmitter) {
         zombie.audio.FMODParameter occ = (zombie.audio.FMODParameter) occlusionField.get(em);
         int users = ((gnu.trove.list.array.TLongArrayList) localInstancesField.get(occ)).size();
         logChange(p, nowNs, "~ambOcclusion", users == 0 ? -1f : occ.getCurrentValue());
      }
      IsoGridSquare sq = IsoWorld.instance.getCell().getGridSquare((double) p.getX(), (double) p.getY(), 0.0);
      logChange(p, nowNs, "~couldSee", sq == null ? -1f : sq.isCouldSee(0) ? 1f : 0f);
      // the WorldAmbiance event's own core channels (its rain / wind layers): FMOD virtualises single channels by
      // audibility once the real-voice budget is full, while the event instance itself stays real
      long amb = ambianceInstField == null ? 0L : ambianceInstField.getLong(zombie.AmbientStreamManager.instance);
      int[] ch = ambChannels(amb);
      logChange(p, nowNs, "~ambChannels", ch[0]);
      logChange(p, nowNs, "~ambVirtualCh", ch[1]);
      String vs = String.join(",", ambVirtualSet);
      if (!vs.equals(ambVirtualLast)) {
         if (ambVirtualLast != null && paramChanges.size() < 20000) {
            paramChanges.add(String.format(Locale.ROOT, "# ambvirtual t=%.3f %s", startNs == 0L ? -1.0 : (nowNs - startNs) / 1e9, vs.isEmpty() ? "-" : vs));
         }
         ambVirtualLast = vs;
      }
      int[] playing = AudioLimiter.channelsPlaying();
      logChange(p, nowNs, "~realFull", playing == null ? -1f : playing[1] >= 60 ? 1f : 0f);
   }

   private static boolean chTried;
   private static java.lang.invoke.MethodHandle evGroup, numChannels, getChannel, numGroups, getGroup, chVirtual;
   private static java.lang.foreign.MemorySegment ptrOut, intOut, nameBuf;
   private static java.lang.invoke.MethodHandle chSound, soundName, chAudibility;
   private static final HashMap<Long, String> soundNames = new HashMap<>();
   // the ambiance event's channels of the last walk: sound name, V(irtual) / R(eal), audibility in dB
   private static final List<String> ambChannelList = new ArrayList<>();
   private static final java.util.TreeSet<String> ambVirtualSet = new java.util.TreeSet<>();
   private static String ambVirtualLast;

   /** {channels, virtual channels} under the event instance's channel group; {-1, -1} when unknown. */
   private static int[] ambChannels(long instance) {
      if (!chTried) {
         chTried = true;
         try {
            var linker = java.lang.foreign.Linker.nativeLinker();
            var arena = java.lang.foreign.Arena.global();
            String dir = System.getProperty("user.dir") + "/natives/";
            var studio = java.lang.foreign.SymbolLookup.libraryLookup(java.nio.file.Path.of(dir + "libfmodstudio.so"), arena);
            var core = java.lang.foreign.SymbolLookup.libraryLookup(java.nio.file.Path.of(dir + "libfmod.so"), arena);
            var J = java.lang.foreign.ValueLayout.JAVA_LONG;
            var I = java.lang.foreign.ValueLayout.JAVA_INT;
            var A = java.lang.foreign.ValueLayout.ADDRESS;
            evGroup = linker.downcallHandle(studio.find("FMOD_Studio_EventInstance_GetChannelGroup").orElseThrow(), java.lang.foreign.FunctionDescriptor.of(I, J, A));
            numChannels = linker.downcallHandle(core.find("FMOD_ChannelGroup_GetNumChannels").orElseThrow(), java.lang.foreign.FunctionDescriptor.of(I, J, A));
            getChannel = linker.downcallHandle(core.find("FMOD_ChannelGroup_GetChannel").orElseThrow(), java.lang.foreign.FunctionDescriptor.of(I, J, I, A));
            numGroups = linker.downcallHandle(core.find("FMOD_ChannelGroup_GetNumGroups").orElseThrow(), java.lang.foreign.FunctionDescriptor.of(I, J, A));
            getGroup = linker.downcallHandle(core.find("FMOD_ChannelGroup_GetGroup").orElseThrow(), java.lang.foreign.FunctionDescriptor.of(I, J, I, A));
            chVirtual = linker.downcallHandle(core.find("FMOD_Channel_IsVirtual").orElseThrow(), java.lang.foreign.FunctionDescriptor.of(I, J, A));
            chSound = linker.downcallHandle(core.find("FMOD_Channel_GetCurrentSound").orElseThrow(), java.lang.foreign.FunctionDescriptor.of(I, J, A));
            soundName = linker.downcallHandle(core.find("FMOD_Sound_GetName").orElseThrow(), java.lang.foreign.FunctionDescriptor.of(I, J, A, I));
            chAudibility = linker.downcallHandle(core.find("FMOD_Channel_GetAudibility").orElseThrow(), java.lang.foreign.FunctionDescriptor.of(I, J, A));
            nameBuf = arena.allocate(128);
            ptrOut = arena.allocate(J);
            intOut = arena.allocate(I);
         } catch (Throwable e) {
            Log.warn("harness: sound probe: no channel walk (" + e + ")");
            evGroup = null;
         }
      }
      int[] out = {-1, -1};
      if (evGroup == null || instance == 0L) return out;
      try {
         if ((int) evGroup.invokeExact(instance, ptrOut) != 0) return out;
         out[0] = 0;
         out[1] = 0;
         ambChannelList.clear();
         ambVirtualSet.clear();
         walkGroup(ptrOut.get(java.lang.foreign.ValueLayout.JAVA_LONG, 0), out, 0);
      } catch (Throwable e) {
         out[0] = out[1] = -1;
      }
      return out;
   }

   private static void walkGroup(long group, int[] out, int depth) throws Throwable {
      if (group == 0L || depth > 8) return;
      if ((int) numChannels.invokeExact(group, intOut) != 0) return;
      int n = intOut.get(java.lang.foreign.ValueLayout.JAVA_INT, 0);
      for (int i = 0; i < n; i++) {
         if ((int) getChannel.invokeExact(group, i, ptrOut) != 0) continue;
         long c = ptrOut.get(java.lang.foreign.ValueLayout.JAVA_LONG, 0);
         out[0]++;
         boolean virt = (int) chVirtual.invokeExact(c, intOut) == 0 && intOut.get(java.lang.foreign.ValueLayout.JAVA_INT, 0) != 0;
         if (virt) out[1]++;
         String name = "?";
         if ((int) chSound.invokeExact(c, ptrOut) == 0) {
            long snd = ptrOut.get(java.lang.foreign.ValueLayout.JAVA_LONG, 0);
            name = soundNames.get(snd);
            if (name == null) {
               name = (int) soundName.invokeExact(snd, nameBuf, 128) == 0 ? nameBuf.getString(0) : "?";
               soundNames.put(snd, name);
            }
         }
         float aud = (int) chAudibility.invokeExact(c, ptrOut) == 0 ? ptrOut.get(java.lang.foreign.ValueLayout.JAVA_FLOAT, 0) : -1f;
         ambChannelList.add(String.format(Locale.ROOT, "%s:%s:%.0f", name, virt ? "V" : "R", aud > 0f ? 20.0 * Math.log10(aud) : -999.0));
         if (virt) ambVirtualSet.add(name);
      }
      if ((int) numGroups.invokeExact(group, intOut) != 0) return;
      int g = intOut.get(java.lang.foreign.ValueLayout.JAVA_INT, 0);
      for (int i = 0; i < g; i++) {
         if ((int) getGroup.invokeExact(group, i, ptrOut) != 0) continue;
         walkGroup(ptrOut.get(java.lang.foreign.ValueLayout.JAVA_LONG, 0), out, depth + 1);
      }
   }

   /** Every frame from Scene.tick (settle and route): one census a second. */
   static void tick(IsoPlayer p, long nowNs) {
      fireGun(p, nowNs);
      if (paramLog && p != null) {
         logParams(p, nowNs);
      }
      if (!enabled) {
         return;
      }
      framesSinceSample++;
      if (lastSampleNs == 0L) {
         lastSampleNs = nowNs;
         framesSinceSample = 0;
         return;
      }
      if (nowNs - lastSampleNs < sampleEveryNs) {
         return;
      }
      float frameMs = (nowNs - lastSampleNs) / 1e6f / Math.max(1, framesSinceSample);
      lastSampleNs = nowNs;
      framesSinceSample = 0;
      long t0 = System.nanoTime();
      String line = sample(p, nowNs, frameMs);
      long dt = System.nanoTime() - t0;
      samples++;
      sampleNsTotal += dt;
      if (dt > sampleNsMax) sampleNsMax = dt;
      if (line != null) {
         timeline.add(line + String.format(Locale.ROOT, " probe_ms=%.2f", dt / 1e6));
         if (paramLog && !ambChannelList.isEmpty()) {
            timeline.add(String.format(Locale.ROOT, "# ambch t=%.2f %s", startNs == 0L ? -1.0 : (nowNs - startNs) / 1e9, String.join(" ", ambChannelList)));
         }
      }
   }

   @SuppressWarnings("unchecked")
   private static String sample(IsoPlayer p, long nowNs, float frameMs) {
      try {
         if (emittersField == null) {
            emittersField = zombie.SoundManager.class.getDeclaredField("emitters");
            emittersField.setAccessible(true);
            toStartField = FMODSoundEmitter.class.getDeclaredField("toStart");
            toStartField.setAccessible(true);
            instancesField = FMODSoundEmitter.class.getDeclaredField("instances");
            instancesField.setAccessible(true);
            stoppedField = FMODSoundEmitter.class.getDeclaredField("stopped");
            stoppedField.setAccessible(true);
            soundNameField = Class.forName("fmod.fmod.FMODSoundEmitter$Sound").getDeclaredField("name");
            soundNameField.setAccessible(true);
            slotsField = zombie.characters.ZombieVocalsManager.class.getDeclaredField("slots");
            slotsField.setAccessible(true);
            slotCharField = Class.forName("zombie.characters.ZombieVocalsManager$Slot").getDeclaredField("character");
            slotCharField.setAccessible(true);
            alarmInstField = zombie.iso.Alarm.class.getDeclaredField("inst");
            alarmInstField.setAccessible(true);
            eventInstanceField = Class.forName("fmod.fmod.FMODSoundEmitter$EventSound").getDeclaredField("eventInstance");
            eventInstanceField.setAccessible(true);
            ambianceInstField = zombie.AmbientStreamManager.class.getDeclaredField("worldAmbianceInstance");
            ambianceInstField.setAccessible(true);
         }
         // emitters by type: [0] voice [1] footstep [2] extra [3] other (ui, vehicles, ambient, objects)
         int[] emitters = new int[4], active = new int[4], inst = new int[4];
         int virtualCount = 0;
         HashMap<String, Integer> virtualNames = new HashMap<>();
         java.util.TreeMap<String, Integer> otherNames = new java.util.TreeMap<>();
         int stopping = 0, pending = 0;
         HashMap<String, Integer> names = new HashMap<>();
         java.util.Set<?> set = (java.util.Set<?>) emittersField.get(zombie.SoundManager.instance);
         for (Object o : set.toArray()) {
            if (!(o instanceof FMODSoundEmitter e)) {
               emitters[3]++;
               continue;
            }
            int k = e.emitterType == EmitterType.Voice ? 0 : e.emitterType == EmitterType.Footstep ? 1 : e.emitterType == EmitterType.Extra ? 2 : 3;
            emitters[k]++;
            List<Object> ins = (List<Object>) instancesField.get(e);
            int n = ins.size();
            int s = ((List<?>) stoppedField.get(e)).size();
            pending += ((List<?>) toStartField.get(e)).size();
            stopping += s;
            if (n > 0 || s > 0) active[k]++;
            inst[k] += n;
            for (int i = 0; i < n; i++) {
               Object snd = ins.get(i);
               String name = snd == null ? null : (String) soundNameField.get(snd);
               if (name != null) names.merge(name, 1, Integer::sum);
               boolean virt = snd != null && eventInstanceField.getDeclaringClass().isInstance(snd) && isVirtual(eventInstanceField.getLong(snd)) == 1;
               if (virt) {
                  virtualCount++;
                  if (name != null) virtualNames.merge(name, 1, Integer::sum);
               }
               if (k == 3 && name != null) {
                  otherNames.merge(virt ? name + "(v)" : name, 1, Integer::sum); // every non-character sound, virtual ones marked
               }
            }
         }
         int slotsUsed = 0;
         for (Object slot : (Object[]) slotsField.get(zombie.characters.ZombieVocalsManager.instance)) {
            if (slotCharField.get(slot) != null) slotsUsed++;
         }
         zombie.AmbientStreamManager asm = (zombie.AmbientStreamManager) zombie.AmbientStreamManager.instance;
         long ai = alarmInstField.getLong(null);
         String house = houseDist <= 0 ? "-" : asm.alarmList.size() + "/" + (ai == 0L ? "noinst" : playbackState(javafmod.FMOD_Studio_GetPlaybackState(ai)));
         // the alarm plays through VehicleSounds' own emitter (AlarmSound), not BaseVehicle.getEmitter()
         zombie.vehicleSound.VehicleSounds vs = car == null ? null : car.getVehicleSounds();
         String carState = carDist <= 0 ? "-" : car == null ? "none" : (car.isAlarmSoundOn() ? "on" : car.isAlarmActive() ? "active" : "off") + "/" + car.getChosenAlarmSound()
               + "/" + (vs != null && car.getChosenAlarmSound() != null && vs.getVehicleSoundEmitter().isPlaying(car.getChosenAlarmSound()) ? "playing" : "silent");
         if (globalsField == null) {
            globalsField = fmod.fmod.FMODManager.class.getDeclaredField("globalParameterList");
            globalsField.setAccessible(true);
         }
         StringBuilder globals = new StringBuilder();
         for (Object o : (List<?>) globalsField.get(fmod.fmod.FMODManager.instance)) {
            zombie.audio.FMODParameter g = (zombie.audio.FMODParameter) o;
            float v = g.getCurrentValue();
            if (Float.isNaN(v)) continue;
            globals.append(globals.length() == 0 ? "" : ",").append(g.getName()).append(':').append(String.format(Locale.ROOT, "%.2f", v));
         }
         int totalActive = active[0] + active[1] + active[2] + active[3];
         int totalInst = inst[0] + inst[1] + inst[2] + inst[3];
         int worldSounds = zombie.WorldSoundManager.instance.soundList.size();
         float t = (nowNs - startNs) / 1e9f;
         if (startNs != 0L) {
            routeSamples++;
            sumInstances += totalInst;
            sumActive += totalActive;
            if (totalActive > maxActive) maxActive = totalActive;
            if (totalInst > maxInstances) maxInstances = totalInst;
            if (worldSounds > maxWorldSounds) maxWorldSounds = worldSounds;
         }
         int[] ch = AudioLimiter.channelsPlaying(); // FMOD core: playing / real (the rest are virtual: out of the voice budget)
         String channels = ch == null ? "-" : ch[0] + "/" + ch[1];
         float[] mt = AudioLimiter.meter(); // the limiter's input / output peak (linear) of FMOD's last metered block
         String limiter = mt == null ? "-" : String.format(Locale.ROOT, "%.3f/%.3f", mt[0], mt[1]);
         long amb = ambianceInstField.getLong(zombie.AmbientStreamManager.instance);
         String ambiance = amb == 0L ? "none" : (isVirtual(amb) == 1 ? "virtual" : isVirtual(amb) == 0 ? "real" : "?") + "/" + playbackState(javafmod.FMOD_Studio_GetPlaybackState(amb));
         StringBuilder virt = new StringBuilder();
         virtualNames.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(6)
               .forEach(en -> virt.append(virt.length() == 0 ? "" : ",").append(en.getKey()).append(':').append(en.getValue()));
         StringBuilder top = new StringBuilder();
         names.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(8)
               .forEach(en -> top.append(top.length() == 0 ? "" : ",").append(en.getKey()).append(':').append(en.getValue()));
         double dist = houseX == Integer.MIN_VALUE ? -1 : Math.hypot(houseX - p.getX(), houseY - p.getY());
         return String.format(Locale.ROOT,
               "t=%.1f phase=%s frame_ms=%.2f zombies=%d emitters=%d active=%d instances=%d pending=%d stopping=%d voice=%d/%d/%d footstep=%d/%d/%d extra=%d/%d/%d other=%d/%d/%d vocal_slots=%d world_sounds=%d gunshots=%d virtual=%d fmod_channels=%s limiter_peak=%s ambiance=%s virtual_top=%s house_alarm=%s house_dist=%.0f car_alarm=%s top=%s other_names=%s globals=%s",
               startNs == 0L ? -1f : t, startNs == 0L ? "settle" : "route", frameMs, Scene.zombiesLoaded(), set.size(), totalActive, totalInst, pending, stopping,
               emitters[0], active[0], inst[0], emitters[1], active[1], inst[1], emitters[2], active[2], inst[2], emitters[3], active[3], inst[3],
               slotsUsed, worldSounds, shotsFired, isVirtualFn == null ? -1 : virtualCount, channels, limiter, ambiance, virt.length() == 0 ? "-" : virt.toString(), house, dist, carState, top.length() == 0 ? "-" : top.toString(), otherNames.isEmpty() ? "-" : otherNames.toString().replace(" ", "").replace("{", "").replace("}", "").replace("=", ":"),
               globals.length() == 0 ? "-" : globals.toString());
      } catch (Exception e) {
         return "error=" + e;
      }
   }

   /** 1 virtual, 0 real, -1 unknown (no binding, or FMOD refused the handle). */
   private static int isVirtual(long instance) {
      if (!isVirtualTried) {
         isVirtualTried = true;
         try {
            String path = System.getProperty("user.dir") + "/natives/libfmodstudio.so";
            java.lang.foreign.SymbolLookup lookup = java.lang.foreign.SymbolLookup.libraryLookup(java.nio.file.Path.of(path), java.lang.foreign.Arena.global());
            isVirtualFn = java.lang.foreign.Linker.nativeLinker().downcallHandle(lookup.find("FMOD_Studio_EventInstance_IsVirtual").orElseThrow(),
                  java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_LONG, java.lang.foreign.ValueLayout.ADDRESS));
            virtOut = java.lang.foreign.Arena.global().allocate(java.lang.foreign.ValueLayout.JAVA_INT);
            Log.info("harness: sound probe: FMOD_Studio_EventInstance_IsVirtual bound from " + path);
         } catch (Throwable e) {
            Log.warn("harness: sound probe: no FMOD_Studio_EventInstance_IsVirtual (" + e + "); virtual voices not counted");
            isVirtualFn = null;
         }
      }
      if (isVirtualFn == null || instance == 0L) return -1;
      try {
         virtOut.set(java.lang.foreign.ValueLayout.JAVA_INT, 0, 0);
         int r = (int) isVirtualFn.invokeExact(instance, virtOut);
         return r != 0 ? -1 : virtOut.get(java.lang.foreign.ValueLayout.JAVA_INT, 0) != 0 ? 1 : 0;
      } catch (Throwable e) {
         return -1;
      }
   }

   private static String playbackState(int s) {
      return switch (s) {
         case 0 -> "playing";
         case 1 -> "sustaining";
         case 2 -> "stopped";
         case 3 -> "starting";
         case 4 -> "stopping";
         default -> "state" + s;
      };
   }

   private static String pulseStreams = "";
   private static final List<String> ambientStops = new ArrayList<>();

   /** devAmbientSlotLog: one line per ambient-object slot the ObjectAmbientEmitters override stops (route time prepended). */
   public static synchronized void ambientStop(String what) {
      if (ambientStops.size() < 5000) {
         ambientStops.add(String.format(Locale.ROOT, "# ambient stop t=%.2f %s", startNs == 0L ? -1.0 : (System.nanoTime() - startNs) / 1e9, what));
      }
   }

   /**
    * PipeWire / PulseAudio's view of this process's playback streams at the route start (Linux; `pactl list sink-inputs`):
    * sample spec, channel map, volume. The audio judge found the recorded game stream louder than FMOD's master output
    * (2026-09-24): a channel downmix or a stream volume sits between them.
    */
   private static String pulseStreams() {
      try {
         Process pr = new ProcessBuilder("pactl", "list", "sink-inputs").redirectErrorStream(true).start();
         String all = new String(pr.getInputStream().readAllBytes());
         pr.waitFor();
         String pid = Long.toString(ProcessHandle.current().pid());
         StringBuilder out = new StringBuilder();
         for (String block : all.split("\n(?=Sink Input #)")) {
            if (!block.contains("application.process.id = \"" + pid + "\"")) continue;
            for (String l : block.split("\n")) {
               String t = l.trim();
               if (t.startsWith("Sink Input") || t.startsWith("Sink:") || t.startsWith("Sample Specification") || t.startsWith("Channel Map")
                     || t.startsWith("Volume:") || t.startsWith("application.name") || t.startsWith("media.name") || t.startsWith("node.name")) {
                  out.append("# pulse ").append(t).append('\n');
               }
            }
         }
         return out.length() == 0 ? "# pulse: no sink input of this process\n" : out.toString();
      } catch (Exception e) {
         return "# pulse: " + e + "\n";
      }
   }

   /** tid -> {cpu ns, name} for every native thread of the process (Linux; empty elsewhere). */
   private static Map<String, long[]> readThreads() {
      Map<String, long[]> out = new HashMap<>();
      threadNames.clear();
      File[] tasks = new File("/proc/self/task").listFiles();
      if (tasks == null) return out;
      for (File t : tasks) {
         try {
            String comm = Files.readString(Path.of(t.getPath(), "comm")).trim();
            String[] ss = Files.readString(Path.of(t.getPath(), "schedstat")).trim().split(" ");
            out.put(t.getName(), new long[]{Long.parseLong(ss[0])});
            threadNames.put(t.getName(), comm);
         } catch (Exception ignored) {
         }
      }
      return out;
   }

   private static final Map<String, String> threadNames = new HashMap<>();

   /** Lines for pzopt-bench.out; also writes pzopt-sound.out (timeline + native thread CPU over the route). */
   static String summary() {
      if (!requested()) {
         return "";
      }
      StringBuilder threads = new StringBuilder();
      String audioCpu = "n/a";
      if (threadsAtStart != null) {
         Map<String, String> startNames = new HashMap<>(threadNames);
         Map<String, long[]> end = readThreads();
         double wall = (System.nanoTime() - wallAtStartNs) / 1e9;
         List<Object[]> rows = new ArrayList<>();
         long audioNs = 0, totalNs = 0;
         for (Map.Entry<String, long[]> en : end.entrySet()) {
            long[] a = threadsAtStart.get(en.getKey());
            long d = en.getValue()[0] - (a == null ? 0L : a[0]);
            if (d <= 0) continue;
            String name = threadNames.getOrDefault(en.getKey(), startNames.getOrDefault(en.getKey(), "?"));
            rows.add(new Object[]{name, d, en.getKey()});
            totalNs += d;
            if (isAudioThread(name)) audioNs += d;
         }
         rows.sort((x, y) -> Long.compare((Long) y[1], (Long) x[1]));
         threads.append(String.format(Locale.ROOT, "# native threads over the route: wall %.1f s, process %.0f ms (%.2f cores), audio threads %.0f ms (%.3f cores)\n",
               wall, totalNs / 1e6, totalNs / 1e9 / wall, audioNs / 1e6, audioNs / 1e9 / wall));
         threads.append("thread\ttid\tcpu_ms\tshare_of_wall\taudio\n");
         for (Object[] r : rows) {
            long d = (Long) r[1];
            boolean audio = isAudioThread((String) r[0]);
            if (!audio && d < 5_000_000L) continue;
            threads.append(r[0]).append('\t').append(r[2]).append('\t').append(d / 1_000_000).append('\t')
                  .append(String.format(Locale.ROOT, "%.3f", d / 1e9 / wall)).append('\t').append(audio ? "yes" : "").append('\n');
         }
         audioCpu = String.format(Locale.ROOT, "%.3f", audioNs / 1e9 / wall);
      }
      File f = new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-sound.out");
      try (FileWriter w = new FileWriter(f)) {
         w.write("# pzopt sound probe: one census a second (settle t=-1, then route seconds); house_alarm=" + houseDist + " car_alarm=" + carDist
               + (houseX != Integer.MIN_VALUE ? " house_at=" + houseX + "," + houseY : "") + (car != null ? " car_at=" + (int) car.getX() + "," + (int) car.getY() + " car_sound=" + car.getChosenAlarmSound() : "") + "\n");
         w.write("# FMOD mix format: " + AudioLimiter.format() + "; limiter " + AudioLimiter.state() + "\n");
         w.write(pulseStreams);
         for (String l : paramChanges) {
            w.write(l);
            w.write('\n');
         }
         synchronized (SoundProbe.class) {
            for (String l : ambientStops) {
               w.write(l);
               w.write('\n');
            }
         }
         for (String l : timeline) {
            w.write(l);
            w.write('\n');
         }
         w.write(threads.toString());
      } catch (IOException e) {
         Log.warn("harness: could not write pzopt-sound.out: " + e);
      }
      return String.format(Locale.ROOT, "\nsound_probe=gunshots %d, route samples %d, active emitters mean %.0f max %d, sound instances mean %.0f max %d, world sounds max %d, audio threads %s cores, probe mean %.2f ms max %.2f ms, limiter %s, world-sound sweeps %d run / %d skipped (dead found on skipped frames %d, after a chunk's prefix %d, uniform life %b)",
            shotsFired, routeSamples, routeSamples == 0 ? 0.0 : sumActive / (double) routeSamples, maxActive, routeSamples == 0 ? 0.0 : sumInstances / (double) routeSamples, maxInstances,
            maxWorldSounds, audioCpu, samples == 0 ? 0.0 : sampleNsTotal / 1e6 / samples, sampleNsMax / 1e6, AudioLimiter.state(), zombie.WorldSoundManager.pzoptSwept, zombie.WorldSoundManager.pzoptSkipped,
            zombie.WorldSoundManager.pzoptSkipDeadFound, zombie.WorldSoundManager.pzoptPrefixMiss, zombie.WorldSoundManager.pzoptUniformLife);
   }

   /**
    * FMOD's own threads and the PulseAudio / PipeWire client threads. FMOD never names its threads on Linux: they inherit
    * the name of the thread that ran FMOD_System_Init (MainThread in stock, BootAsync's pzopt-fmod-init here), so in a stock
    * run they are found by soundprof.py next to the JVM-attached one async-profiler sees.
    */
   static boolean isAudioThread(String comm) {
      String c = comm.toLowerCase(Locale.ROOT);
      return c.startsWith("fmod") || c.startsWith("pzopt-fmod-init") || c.contains("pulse") || c.contains("pipewire") || c.startsWith("pw-") || c.contains("alsa") || c.contains("audio");
   }
}
