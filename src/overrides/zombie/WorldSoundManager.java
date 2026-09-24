package zombie;

import java.util.ArrayList;
import java.util.List;
import zombie.Lua.LuaEventManager;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.core.Core;
import zombie.core.math.PZMath;
import zombie.debug.DebugOptions;
import zombie.debug.LineDrawer;
import zombie.iso.FishSchoolManager;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoClothingDryer;
import zombie.iso.objects.IsoClothingWasher;
import zombie.iso.objects.IsoCombinationWasherDryer;
import zombie.iso.objects.IsoGenerator;
import zombie.iso.objects.IsoJukebox;
import zombie.iso.objects.IsoRadio;
import zombie.iso.objects.IsoStove;
import zombie.iso.objects.IsoTelevision;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.ServerGUI;
import zombie.popman.MPDebugInfo;
import zombie.popman.ObjectPool;
import zombie.popman.ZombiePopulationManager;
import zombie.vehicles.VehiclePartOwner;

@UsedFromLua
public final class WorldSoundManager {
   public static final WorldSoundManager instance = new WorldSoundManager();
   private static final float MUFFLE_SOUND_DIFFERENT_ROOMS = 1.2F;
   private static final float MUFFLE_SOUND_INSIDE_OUTSIDE = 1.4F;
   public final List<WorldSoundManager.WorldSound> soundList = new ArrayList<>();
   private final ObjectPool<WorldSoundManager.WorldSound> freeSounds = new ObjectPool(WorldSoundManager.WorldSound::new, "WorldSoundManager.freeSounds");
   private static final WorldSoundManager.ResultBiggestSound resultBiggestSound = new WorldSoundManager.ResultBiggestSound();

   public void init(IsoCell cell) {
   }

   public void initFrame() {
   }

   public void KillCell() {
      for (WorldSoundManager.WorldSound sound : this.soundList) {
         sound.source = null;
      }

      this.freeSounds.releaseAll(this.soundList);
      this.soundList.clear();
   }

   public WorldSoundManager.WorldSound getNew() {
      return (WorldSoundManager.WorldSound)this.freeSounds.alloc();
   }

   public WorldSoundManager.WorldSound release(WorldSoundManager.WorldSound worldSound) {
      this.freeSounds.release(worldSound);
      return null;
   }

   public WorldSoundManager.WorldSound addSound(Object source, int x, int y, int z, int radius, int volume) {
      return this.addSound(source, x, y, z, radius, volume, false, 0.0F, 1.0F);
   }

   public WorldSoundManager.WorldSound addSound(Object source, int x, int y, int z, int radius, int volume, boolean stressHumans) {
      return this.addSound(source, x, y, z, radius, volume, stressHumans, 0.0F, 1.0F);
   }

   public WorldSoundManager.WorldSound addSound(
      Object source, int x, int y, int z, int radius, int volume, boolean stressHumans, float zombieIgnoreDist, float stressMod
   ) {
      return this.addSound(source, x, y, z, radius, volume, stressHumans, zombieIgnoreDist, stressMod, false, true, false, false, false);
   }

   public WorldSoundManager.WorldSound addSoundRepeating(
      Object source, int x, int y, int z, int radius, int volume, boolean stressHumans, float zombieIgnoreDist, float stressMod
   ) {
      return this.addSound(source, x, y, z, radius, volume, stressHumans, zombieIgnoreDist, stressMod, false, true, false, true, false);
   }

   public WorldSoundManager.WorldSound addSound(
      Object source,
      int x,
      int y,
      int z,
      int radius,
      int volume,
      boolean stressHumans,
      float zombieIgnoreDist,
      float stressMod,
      boolean sourceIsZombie,
      boolean doSend,
      boolean remote
   ) {
      return this.addSound(source, x, y, z, radius, volume, stressHumans, zombieIgnoreDist, stressMod, sourceIsZombie, doSend, remote, false, false);
   }

   public WorldSoundManager.WorldSound addSound(
      Object source,
      int x,
      int y,
      int z,
      int radius,
      int volume,
      boolean stressHumans,
      float zombieIgnoreDist,
      float stressMod,
      boolean sourceIsZombie,
      boolean doSend,
      boolean remote,
      boolean repeating,
      boolean stressAnimals
   ) {
      short flags = 4;
      if (stressAnimals) {
         flags = (short)(flags | 1);
      }

      if (stressHumans) {
         flags = (short)(flags | 2);
      }

      return this.addSound(source, x, y, z, radius, volume, zombieIgnoreDist, stressMod, sourceIsZombie, doSend, remote, repeating, flags);
   }

   public WorldSoundManager.WorldSound addSound(
      Object source,
      int x,
      int y,
      int z,
      int radius,
      int volume,
      float zombieIgnoreDist,
      float stressMod,
      boolean sourceIsZombie,
      boolean doSend,
      boolean remote,
      boolean repeating,
      short flags
   ) {
      if (radius <= 0) {
         return null;
      }

      WorldSoundManager.WorldSound s;
      boolean timing = pzopt.Config.DEV_WORLD_SOUND_TIMING; // pzopt
      long t0 = timing ? System.nanoTime() : 0L; // pzopt
      synchronized (this.soundList) {
         long t1 = timing ? System.nanoTime() : 0L; // pzopt
         long pzoptAsked = 0L; // pzopt
         long pzoptHit = 0L; // pzopt
         s = this.getNew().init(source, x, y, z, radius, volume, zombieIgnoreDist, stressMod, flags);
         long t2 = timing ? System.nanoTime() : 0L; // pzopt
         s.repeating = repeating;
         if (source == null) {
            s.sourceIsZombie = sourceIsZombie;
         }

         if (!GameServer.server) {
            int hearing = SandboxOptions.instance.lore.hearing.getValue();
            if (hearing == 4) {
               hearing = 1;
            }

            if (hearing == 5) {
               hearing = 2;
            }

            float animalHearingMultiplier = !s.stresshumans && !s.stressAnimals ? 1.0F : 3.0F;
            float zombieHearingMultiplier = this.getHearingMultiplier(hearing);
            float radiusMultiplier = PZMath.max(animalHearingMultiplier, zombieHearingMultiplier);
            int radiusMax = (int)PZMath.ceil(radius * radiusMultiplier);
            int chunkMinX = (x - radiusMax) / 8;
            int chunkMinY = (y - radiusMax) / 8;
            int chunkMaxX = (int)Math.ceil(((float)x + radiusMax) / 8.0F);
            int chunkMaxY = (int)Math.ceil(((float)y + radiusMax) / 8.0F);
            if (pzopt.Config.WORLD_SOUND_FAST && pzopt.Overrides.enabled()) { // pzopt: walk only the loaded chunk grid, not (2r/8)^2 world chunks
               int gridMinX = Integer.MAX_VALUE; // pzopt: union of every active player's chunk map, getChunk is null outside it
               int gridMinY = Integer.MAX_VALUE; // pzopt
               int gridMaxX = Integer.MIN_VALUE; // pzopt
               int gridMaxY = Integer.MIN_VALUE; // pzopt
               for (int n = 0; n < IsoPlayer.numPlayers; n++) { // pzopt
                  zombie.iso.IsoChunkMap map = IsoWorld.instance.currentCell.chunkMap[n]; // pzopt
                  if (map == null || map.ignore) { // pzopt
                     continue; // pzopt
                  }
                  gridMinX = Math.min(gridMinX, map.getWorldXMin()); // pzopt
                  gridMinY = Math.min(gridMinY, map.getWorldYMin()); // pzopt
                  gridMaxX = Math.max(gridMaxX, map.getWorldXMin() + zombie.iso.IsoChunkMap.chunkGridWidth); // pzopt
                  gridMaxY = Math.max(gridMaxY, map.getWorldYMin() + zombie.iso.IsoChunkMap.chunkGridWidth); // pzopt
               }
               chunkMinX = Math.max(chunkMinX, gridMinX); // pzopt
               chunkMinY = Math.max(chunkMinY, gridMinY); // pzopt
               chunkMaxX = Math.min(chunkMaxX, gridMaxX); // pzopt
               chunkMaxY = Math.min(chunkMaxY, gridMaxY); // pzopt
            }

            for (int xx = chunkMinX; xx < chunkMaxX; xx++) {
               for (int yy = chunkMinY; yy < chunkMaxY; yy++) {
                  IsoChunk c = IsoWorld.instance.currentCell.getChunk(xx, yy);
                  if (c != null) {
                     c.soundList.add(s);
                     pzoptHit++; // pzopt
                  }
               }
            }
            pzoptAsked = (long)Math.max(0, chunkMaxX - chunkMinX) * Math.max(0, chunkMaxY - chunkMinY); // pzopt
         }

         long t3 = timing ? System.nanoTime() : 0L; // pzopt
         this.soundList.add(s);
         if (s.life != 16) { // pzopt: worldSoundCleanupFast, a sound with another life breaks the creation-order = death-order rule
            pzoptUniformLife = false; // pzopt: for good; the chunk sweep goes back to the full pass
         } // pzopt
         if (s.life <= 0) { // pzopt: worldSoundCleanupFast (a copied sound arriving dead still gets swept)
            this.pzoptDeadPending = true; // pzopt: worldSoundCleanupFast
         } // pzopt: worldSoundCleanupFast
         ZombiePopulationManager.instance.addWorldSound(s, doSend);
         if (timing) { // pzopt
            long t4 = System.nanoTime(); // pzopt
            int b = radius >= 100 ? 1 : 0; // pzopt: big sounds (alarm 600, helicopter 500, vehicles 100-150) vs the rest
            pzoptCalls[b]++; // pzopt
            pzoptLockNs[b] += t1 - t0; // pzopt
            pzoptInitNs[b] += t2 - t1; // pzopt
            pzoptChunksNs[b] += t3 - t2; // pzopt
            pzoptPopNs[b] += t4 - t3; // pzopt
            pzoptChunksAsked[b] += pzoptAsked; // pzopt
            pzoptChunksHit[b] += pzoptHit; // pzopt
            long total = t4 - t0; // pzopt
            if (total > pzoptMaxNs[b]) pzoptMaxNs[b] = total; // pzopt
            if (b == 1) { // pzopt: one line per big sound so a frame spike can be matched to it
               pzopt.Log.info("world sound r=" + radius + " v=" + volume + " at " + x + "," + y + " src=" + (source == null ? "null" : source.getClass().getSimpleName()) // pzopt
                     + " took " + String.format(java.util.Locale.ROOT, "%.3f", total / 1e6) + " ms (init " + String.format(java.util.Locale.ROOT, "%.3f", (t2 - t1) / 1e6) // pzopt
                     + ", chunks " + String.format(java.util.Locale.ROOT, "%.3f", (t3 - t2) / 1e6) + " asked " + pzoptAsked + " hit " + pzoptHit + ") epoch_ms=" + System.currentTimeMillis()); // pzopt
            }
         }
      }

      if (doSend) {
         if (GameClient.client) {
            GameClient.instance.sendWorldSound(s);
         } else if (GameServer.server) {
            GameServer.sendWorldSound(s, null);
         }
      }

      if (Core.debug && GameClient.client) {
         MPDebugInfo.AddDebugSound(s);
      }

      return s;
   }

   // pzopt: Config.DEV_WORLD_SOUND_TIMING section totals of addSound (the harness sound= rig prints them)
   private static final long[] pzoptCalls = new long[2], pzoptLockNs = new long[2], pzoptInitNs = new long[2], pzoptChunksNs = new long[2], pzoptPopNs = new long[2], pzoptChunksAsked = new long[2], pzoptChunksHit = new long[2], pzoptMaxNs = new long[2]; // pzopt: [0] radius < 100, [1] radius >= 100

   public static String pzoptTiming() { // pzopt
      StringBuilder sb = new StringBuilder(); // pzopt
      for (int b = 0; b < 2; b++) { // pzopt
         long n = pzoptCalls[b]; // pzopt
         sb.append(b == 0 ? "radius<100: " : " | radius>=100: "); // pzopt
         if (n == 0) { // pzopt
            sb.append("no calls"); // pzopt
            continue; // pzopt
         }
         sb.append(String.format(java.util.Locale.ROOT, "%d calls, lock %.3f, init(lua+fish) %.3f, chunk loop %.3f (asked %d, hit %d per call), list+popman %.3f ms mean, max %.3f ms, total %.2f s", // pzopt
               n, pzoptLockNs[b] / 1e6 / n, pzoptInitNs[b] / 1e6 / n, pzoptChunksNs[b] / 1e6 / n, pzoptChunksAsked[b] / n, pzoptChunksHit[b] / n, pzoptPopNs[b] / 1e6 / n, // pzopt
               pzoptMaxNs[b] / 1e6, (pzoptLockNs[b] + pzoptInitNs[b] + pzoptChunksNs[b] + pzoptPopNs[b]) / 1e9)); // pzopt
      }
      return sb.toString(); // pzopt
   }

   public WorldSoundManager.WorldSound addSoundRepeating(
      Object source, int x, int y, int z, int radius, int volume, boolean stressHumans, boolean stressAnimals
   ) {
      return this.addSound(source, x, y, z, radius, volume, stressHumans, 0.0F, 1.0F, false, true, false, true, stressAnimals);
   }

   public WorldSoundManager.WorldSound addSoundRepeating(Object source, int x, int y, int z, int radius, int volume, boolean stressHumans) {
      return this.addSoundRepeating(source, x, y, z, radius, volume, stressHumans, 0.0F, 1.0F);
   }

   public WorldSoundManager.WorldSound addSoundRepeating(Object source, int x, int y, int z, int radius, int volume, short flags) {
      boolean sourceIsZombie = false;
      boolean doSend = true;
      boolean remote = false;
      boolean repeating = true;
      return this.addSound(source, x, y, z, radius, volume, 0.0F, 1.0F, false, true, false, true, flags);
   }

   public WorldSoundManager.WorldSound getSoundZomb(IsoZombie zom) {
      if (zom.soundSourceTarget == null) {
         return null;
      }

      if (zom.getCurrentSquare() == null) {
         return null;
      }

      IsoChunk chunk = zom.getCurrentSquare().chunk;
      List<WorldSoundManager.WorldSound> soundList;
      if (chunk != null && !GameServer.server) {
         soundList = chunk.soundList;
      } else {
         soundList = this.soundList;
      }

      synchronized (this.soundList) { // pzopt: addSound holds this lock; zombies updated on other threads (PZMulticore) add while others read
      for (int n = 0; n < soundList.size(); n++) {
         WorldSoundManager.WorldSound sound = soundList.get(n);
         if (zom.soundSourceTarget == sound.source && sound.stressZombies) {
            return sound;
         }
      }
      } // pzopt

      return null;
   }

   public WorldSoundManager.WorldSound getSoundAnimal(IsoAnimal animal) {
      if (animal.getCurrentSquare() == null) {
         return null;
      }

      IsoChunk chunk = animal.getCurrentSquare().chunk;
      List<WorldSoundManager.WorldSound> soundList;
      if (chunk != null && !GameServer.server) {
         soundList = chunk.soundList;
      } else {
         soundList = this.soundList;
      }

      WorldSoundManager.WorldSound loudest = null;
      float loudestVolume = 0.0F;

      synchronized (this.soundList) { // pzopt: as getSoundZomb
      for (int n = 0; n < soundList.size(); n++) {
         WorldSoundManager.WorldSound sound = soundList.get(n);
         if (sound.stresshumans || sound.stressAnimals) {
            float distSq = IsoUtils.DistanceToSquared(animal.getX(), animal.getY(), animal.getZ() * 3.0F, sound.x, sound.y, sound.z * 3.0F);
            float radiusBonus = animal.isWild() ? 3.0F : 1.0F;
            float radius = sound.radius * radiusBonus;
            if (!(distSq > radius * radius)) {
               float delta = 1.0F - distSq / (radius * radius);
               float volume = sound.volume * delta;
               if (volume > loudestVolume) {
                  loudestVolume = volume;
                  loudest = sound;
               }
            }
         }
      }
      } // pzopt

      return loudest;
   }

   public WorldSoundManager.ResultBiggestSound getBiggestSoundZomb(int x, int y, int z, boolean ignoreBySameType, IsoZombie zom) {
      float largestSound = -1000000.0F;
      WorldSoundManager.WorldSound largest = null;
      IsoChunk chunk = null;
      if (zom != null) {
         if (zom.getCurrentSquare() == null) {
            return pzoptResultBiggestSound().init(null, 0.0F); // pzopt: per thread off the game thread
         }

         chunk = zom.getCurrentSquare().chunk;
      }

      List<WorldSoundManager.WorldSound> soundList;
      if (chunk != null && !GameServer.server) {
         soundList = chunk.soundList;
      } else {
         soundList = this.soundList;
      }

      boolean pzoptHoist = pzopt.Config.HEARING_HOIST && pzopt.Overrides.enabled(); // pzopt: hearingHoist
      float pzoptHearing = pzoptHoist ? this.getHearingMultiplier(zom) : 0.0F; // pzopt: hearingHoist (pure: the zombie's hearing, worn items, weather)
      IsoGridSquare pzoptSq2 = null; // pzopt: hearingHoist, the zombie's own square, looked up at the first sound that needs it
      boolean pzoptSq2Done = false; // pzopt: hearingHoist
      synchronized (this.soundList) { // pzopt: as getSoundZomb
      for (int n = 0; n < soundList.size(); n++) {
         WorldSoundManager.WorldSound sound = soundList.get(n);
         if (sound != null && sound.stressZombies && sound.radius != 0) {
            float dist = IsoUtils.DistanceToSquared(x, y, z * 3, sound.x, sound.y, sound.z * 3);
            float radius = sound.radius * (pzoptHoist ? pzoptHearing : this.getHearingMultiplier(zom)); // pzopt: hearingHoist
            if (!(dist > radius * radius)
               && (!(dist < sound.zombieIgnoreDist * sound.zombieIgnoreDist) || z != sound.z)
               && (!ignoreBySameType || !sound.sourceIsZombie)) {
               IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(sound.x, sound.y, sound.z);
               if (!pzoptHoist || !pzoptSq2Done) { // pzopt: hearingHoist
                  pzoptSq2 = IsoWorld.instance.currentCell.getGridSquare(x, y, z); // pzopt: hearingHoist
                  pzoptSq2Done = true; // pzopt: hearingHoist
               } // pzopt: hearingHoist
               IsoGridSquare sq2 = pzoptSq2; // pzopt: hearingHoist
               float delta = dist / (radius * radius);
               if (sq != null && sq2 != null && sq.getRoom() != sq2.getRoom()) {
                  delta *= 1.2F;
                  if (sq2.getRoom() == null || sq.getRoom() == null) {
                     delta *= 1.4F;
                  }
               }

               delta = 1.0F - delta;
               if (!(delta <= 0.0F)) {
                  if (delta > 1.0F) {
                     delta = 1.0F;
                  }

                  float tot = sound.volume * delta;
                  if (tot > largestSound) {
                     largestSound = tot;
                     largest = sound;
                  }
               }
            }
         }
      }
      } // pzopt

      return pzoptResultBiggestSound().init(largest, largestSound); // pzopt: per thread off the game thread
   }

   // pzopt: the shared result object for the game thread (stock), one per thread for any other caller: a mod that
   // updates zombies on several threads (PZMulticore) would otherwise overwrite a result another thread still reads
   private static final ThreadLocal<WorldSoundManager.ResultBiggestSound> pzoptResultOther = ThreadLocal.withInitial(WorldSoundManager.ResultBiggestSound::new);

   private static WorldSoundManager.ResultBiggestSound pzoptResultBiggestSound() { // pzopt
      return Thread.currentThread() == zombie.GameWindow.gameThread ? resultBiggestSound : pzoptResultOther.get();
   }

   public float getSoundAttract(WorldSoundManager.WorldSound sound, IsoZombie zom) {
      if (sound == null) {
         return 0.0F;
      }

      if (sound.radius == 0) {
         return 0.0F;
      }

      if (sound.sourceIsZombie) {
         return 0.0F;
      }

      float distSq = IsoUtils.DistanceToSquared(zom.getX(), zom.getY(), zom.getZ() * 3.0F, sound.x, sound.y, sound.z * 3);
      float radius = sound.radius * this.getHearingMultiplier(zom);
      if (distSq > radius * radius) {
         return 0.0F;
      }

      if (distSq < sound.zombieIgnoreDist * sound.zombieIgnoreDist && zom.getZ() == sound.z) {
         return 0.0F;
      }

      IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(sound.x, sound.y, sound.z);
      IsoGridSquare sq2 = IsoWorld.instance.currentCell.getGridSquare(zom.getX(), zom.getY(), zom.getZ());
      float delta = distSq / (radius * radius);
      if (sq != null && sq2 != null && sq.getRoom() != sq2.getRoom()) {
         delta *= 1.2F;
         if (sq2.getRoom() == null || sq.getRoom() == null) {
            delta *= 1.4F;
         }
      }

      delta = PZMath.clamp_01(1.0F - delta);
      return sound.volume * delta;
   }

   public float getSoundAttractAnimal(WorldSoundManager.WorldSound sound, IsoAnimal animal) {
      if (sound == null) {
         return 0.0F;
      }

      if (sound.radius == 0) {
         return 0.0F;
      }

      float distSq = IsoUtils.DistanceToSquared(animal.getX(), animal.getY(), animal.getZ() * 3.0F, sound.x, sound.y, sound.z * 3);
      float radiusBonus = animal.isWild() ? 3.0F : 1.0F;
      float radius = sound.radius * radiusBonus;
      if (distSq > radius * radius) {
         return 0.0F;
      }

      if (distSq < sound.zombieIgnoreDist * sound.zombieIgnoreDist && animal.getZ() == sound.z) {
         return 0.0F;
      }

      IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(sound.x, sound.y, sound.z);
      IsoGridSquare sq2 = IsoWorld.instance.currentCell.getGridSquare(animal.getX(), animal.getY(), animal.getZ());
      float delta = distSq / (radius * radius);
      if (sq != null && sq2 != null && sq.getRoom() != sq2.getRoom()) {
         delta *= 1.2F;
         if (sq2.getRoom() == null || sq.getRoom() == null) {
            delta *= 1.4F;
         }
      }

      delta = PZMath.clamp_01(1.0F - delta);
      return sound.volume * delta;
   }

   public float getStressFromSounds(int x, int y, int z) {
      float ret = 0.0F;

      synchronized (this.soundList) { // pzopt: as getSoundZomb
      for (int i = 0; i < this.soundList.size(); i++) {
         WorldSoundManager.WorldSound sound = this.soundList.get(i);
         if (sound.stresshumans && sound.radius != 0) {
            float dist = IsoUtils.DistanceManhatten(x, y, sound.x, sound.y);
            float delta = dist / sound.radius;
            delta = 1.0F - delta;
            if (!(delta <= 0.0F)) {
               if (delta > 1.0F) {
                  delta = 1.0F;
               }

               float tot = delta * sound.stressMod;
               ret += tot;
            }
         }
      }
      } // pzopt

      return ret;
   }

   // pzopt: worldSoundCleanupFast. A sound in a chunk list can only be dead (life 0) after the decrement below took it
   // there; after a frame where none reached 0 the chunk sweep has nothing to remove.
   private boolean pzoptDeadPending = true;
   public static long pzoptSkipped, pzoptSwept, pzoptSkipDeadFound; // pzopt: worldSoundCleanupFast counters (SoundProbe summary)
   public static long pzoptPrefixMiss; // pzopt: devWorldSoundCleanupCheck, dead entries found after a chunk list's live prefix (must stay 0)
   public static boolean pzoptUniformLife = true; // pzopt: every sound added so far was born with life 16 (IsoChunk.updateSounds may trim a prefix)

   public void update() {
      boolean pzoptSweep = this.pzoptDeadPending || !pzopt.Config.WORLD_SOUND_CLEANUP_FAST || !pzopt.Overrides.enabled(); // pzopt: worldSoundCleanupFast
      this.pzoptDeadPending = false; // pzopt: worldSoundCleanupFast
      if (!pzoptSweep) { // pzopt: worldSoundCleanupFast
         pzoptSkipped++; // pzopt
         if (pzopt.Config.DEV_WORLD_SOUND_CLEANUP_CHECK && !GameServer.server) { // pzopt: the rig proving the skip safe
            for (int n = 0; n < IsoPlayer.numPlayers; n++) { // pzopt
               IsoChunkMap map = IsoWorld.instance.currentCell.chunkMap[n]; // pzopt
               for (int y = 0; !map.ignore && y < IsoChunkMap.chunkGridWidth; y++) { // pzopt
                  for (int x = 0; x < IsoChunkMap.chunkGridWidth; x++) { // pzopt
                     IsoChunk c = map.getChunk(x, y); // pzopt
                     if (c != null) { // pzopt
                        for (int i = 0; i < c.soundList.size(); i++) { // pzopt
                           WorldSoundManager.WorldSound w = c.soundList.get(i); // pzopt
                           if (w == null || w.life <= 0) { // pzopt
                              pzoptSkipDeadFound++; // pzopt
                           } // pzopt
                        } // pzopt
                     } // pzopt
                  } // pzopt
               } // pzopt
            } // pzopt
         } // pzopt
      } else { // pzopt
         pzoptSwept++; // pzopt
      } // pzopt
      if (!GameServer.server && pzoptSweep) { // pzopt: worldSoundCleanupFast
         for (int n = 0; n < IsoPlayer.numPlayers; n++) {
            IsoChunkMap chunkMap = IsoWorld.instance.currentCell.chunkMap[n];
            if (!chunkMap.ignore) {
               for (int y = 0; y < IsoChunkMap.chunkGridWidth; y++) {
                  for (int x = 0; x < IsoChunkMap.chunkGridWidth; x++) {
                     IsoChunk chunk = chunkMap.getChunk(x, y);
                     if (chunk != null) {
                        chunk.updateSounds();
                     }
                  }
               }
            }
         }
      }

      int s = this.soundList.size();

      for (int n = 0; n < s; n++) {
         WorldSoundManager.WorldSound sound = this.soundList.get(n);
         if (sound != null && sound.life > 0) {
            sound.life--;
            if (sound.life <= 0) { // pzopt: worldSoundCleanupFast
               this.pzoptDeadPending = true; // pzopt: worldSoundCleanupFast (its chunk lists are swept next frame, before its release)
            } // pzopt: worldSoundCleanupFast
         } else {
            this.soundList.remove(n);
            this.release(sound);
            n--;
            s--;
         }
      }
   }

   public void render() {
      if (Core.debug && DebugOptions.instance.worldSoundRender.getValue()) {
         if (!GameClient.client) {
            if (!GameServer.server || ServerGUI.isCreated()) {
               int hearing = SandboxOptions.instance.lore.hearing.getValue();
               if (hearing == 4) {
                  hearing = 2;
               }

               if (hearing == 5) {
                  hearing = 2;
               }

               float radiusMultiplier = this.getHearingMultiplier(hearing);

               for (int i = 0; i < this.soundList.size(); i++) {
                  WorldSoundManager.WorldSound sound = this.soundList.get(i);
                  float radius = sound.radius * radiusMultiplier;
                  int segments = 32;
                  LineDrawer.DrawIsoCircle(sound.x, sound.y, sound.z, radius, 32, 1.0F, 1.0F, 1.0F, 1.0F);
               }

               if (!GameServer.server) {
                  IsoChunkMap chunkMap = IsoWorld.instance.currentCell.getChunkMap(0);
                  if (chunkMap != null && !chunkMap.ignore) {
                     for (int y = 0; y < IsoChunkMap.chunkGridWidth; y++) {
                        for (int x = 0; x < IsoChunkMap.chunkGridWidth; x++) {
                           IsoChunk chunk = chunkMap.getChunk(x, y);
                           if (chunk != null) {
                              for (int i = 0; i < chunk.soundList.size(); i++) {
                                 WorldSoundManager.WorldSound sound = chunk.soundList.get(i);
                                 float radius = sound.radius * radiusMultiplier;
                                 int segments = 32;
                                 LineDrawer.DrawIsoCircle(sound.x, sound.y, sound.z, radius, 32, 0.0F, 1.0F, 1.0F, 1.0F);
                                 int chunksPerWidth = 8;
                                 float left = chunk.wx * 8 + 0.1F;
                                 float top = chunk.wy * 8 + 0.1F;
                                 float right = (chunk.wx + 1) * 8 - 0.1F;
                                 float bottom = (chunk.wy + 1) * 8 - 0.1F;
                                 LineDrawer.DrawIsoRect(left, top, right - left, bottom - top, sound.z, 0.0F, 1.0F, 1.0F);
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   public float getHearingMultiplier(IsoZombie zombie) {
      return zombie == null
         ? this.getHearingMultiplier(2)
         : this.getHearingMultiplier(zombie.hearing) * zombie.getWornItemsHearingMultiplier() * zombie.getWeatherHearingMultiplier();
   }

   public float getHearingMultiplier(int hearing) {
      if (hearing == 1) {
         return 3.0F;
      } else {
         return hearing == 3 ? 0.45F : 1.0F;
      }
   }

   public static final class ResultBiggestSound {
      public WorldSoundManager.WorldSound sound;
      public float attract;

      public WorldSoundManager.ResultBiggestSound init(WorldSoundManager.WorldSound sound, float attract) {
         this.sound = sound;
         this.attract = attract;
         return this;
      }
   }

   @UsedFromLua
   public static final class WorldSound {
      public Object source;
      public int life = 1;
      public int radius;
      public boolean stresshumans;
      public boolean stressZombies;
      public boolean stressAnimals;
      public int volume;
      public int x;
      public int y;
      public int z;
      public float zombieIgnoreDist;
      public boolean sourceIsZombie;
      public boolean sourceIsPlayer;
      public boolean sourceIsPlayerBase;
      public float stressMod = 1.0F;
      public boolean repeating;

      private boolean isSourceIsPlayerBase(Object source) {
         return source instanceof IsoGenerator
            || source instanceof IsoJukebox
            || source instanceof IsoTelevision
            || source instanceof IsoRadio
            || source instanceof IsoStove
            || source instanceof IsoClothingWasher
            || source instanceof IsoClothingDryer
            || source instanceof IsoCombinationWasherDryer;
      }

      public WorldSoundManager.WorldSound init(Object source, int x, int y, int z, int radius, int volume) {
         return this.init(source, x, y, z, radius, volume, false, 0.0F, 1.0F);
      }

      public WorldSoundManager.WorldSound init(Object source, int x, int y, int z, int radius, int volume, boolean stresshumans) {
         return this.init(source, x, y, z, radius, volume, stresshumans, 0.0F, 1.0F);
      }

      public WorldSoundManager.WorldSound init(
         Object source, int x, int y, int z, int radius, int volume, boolean stresshumans, float zombieIgnoreDist, float stressMod
      ) {
         short flags = 4;
         if (stresshumans) {
            flags = (short)(flags | 2);
         }

         return this.init(source, x, y, z, radius, volume, zombieIgnoreDist, stressMod, flags);
      }

      public WorldSoundManager.WorldSound init(Object source, int x, int y, int z, int radius, int volume, float zombieIgnoreDist, float stressMod, short flags) {
         this.source = source;
         this.life = 16;
         this.x = x;
         this.y = y;
         this.z = z;
         this.radius = radius;
         this.volume = volume;
         this.stresshumans = (flags & 2) != 0;
         this.stressAnimals = (flags & 1) != 0;
         this.stressZombies = (flags & 4) != 0;
         this.zombieIgnoreDist = zombieIgnoreDist;
         this.stressMod = stressMod;
         this.sourceIsPlayer = source instanceof IsoPlayer;
         this.sourceIsPlayerBase = this.isSourceIsPlayerBase(source);
         this.sourceIsZombie = source instanceof IsoZombie;
         this.repeating = false;
         LuaEventManager.triggerEvent("OnWorldSound", x, y, z, radius, volume, source);
         if (!GameClient.client) {
            FishSchoolManager.getInstance().addSoundNoise(x, y, radius / 6);
         }

         return this;
      }

      public WorldSoundManager.WorldSound init(
         boolean sourceIsZombie, int x, int y, int z, int radius, int volume, boolean stressHumans, float zombieIgnoreDist, float stressMod
      ) {
         WorldSoundManager.WorldSound sound = this.init(null, x, y, z, radius, volume, stressHumans, zombieIgnoreDist, stressMod);
         sound.sourceIsZombie = sourceIsZombie;
         return sound;
      }

      public WorldSoundManager.WorldSound init(WorldSoundManager.WorldSound other) {
         this.source = other.source;
         this.life = other.life;
         this.radius = other.radius;
         this.stresshumans = other.stresshumans;
         this.stressZombies = other.stressZombies;
         this.stressAnimals = other.stressAnimals;
         this.volume = other.volume;
         this.x = other.x;
         this.y = other.y;
         this.z = other.z;
         this.zombieIgnoreDist = other.zombieIgnoreDist;
         this.sourceIsZombie = other.sourceIsZombie;
         this.sourceIsPlayer = other.sourceIsPlayer;
         this.sourceIsPlayerBase = other.sourceIsPlayerBase;
         this.stressMod = other.stressMod;
         this.repeating = other.repeating;
         return this;
      }

      public boolean sourceIsVehicle() {
         return this.source instanceof VehiclePartOwner;
      }
   }
}
