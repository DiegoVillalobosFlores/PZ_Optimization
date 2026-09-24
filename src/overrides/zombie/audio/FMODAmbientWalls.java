package zombie.audio;

import fmod.fmod.FMODSoundEmitter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import zombie.AmbientStreamManager;
import zombie.GameSounds;
import zombie.audio.FMODAmbientWallLevelData.FMODAmbientWall;
import zombie.audio.parameters.ParameterInside;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.math.PZMath;
import zombie.debug.DebugOptions;
import zombie.debug.LineDrawer;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkLevel;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.Vector2;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoWindow;
import zombie.popman.ObjectPool;
import zombie.scripting.objects.SoundKey;
import zombie.util.StringUtils;
import zombie.util.list.PZArrayUtil;

public final class FMODAmbientWalls {
   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
   static {
      pzopt.Overrides.onClassLoaded("zombie.audio.FMODAmbientWalls");
   }

   public static final boolean ENABLE = true;
   private static FMODAmbientWalls instance;
   private final Vector2 tempVector2 = new Vector2();
   private final ObjectPool<FMODAmbientWalls.ObjectWithDistance> objectPool = new ObjectPool(
      FMODAmbientWalls.ObjectWithDistance::new, "FMODAmbientWalls.objectPool"
   );
   private final ArrayList<FMODAmbientWalls.ObjectWithDistance> objects = new ArrayList<>();
   private final FMODAmbientWalls.Slot[] slots;
   private final ObjectPool<ObjectAmbientEmitters.DoorLogic> doorLogicPool = new ObjectPool(
      ObjectAmbientEmitters.DoorLogic::new, "FMODAmbientWalls.doorLogicPool"
   );
   private final ObjectPool<ObjectAmbientEmitters.WindowLogic> windowLogicPool = new ObjectPool(
      ObjectAmbientEmitters.WindowLogic::new, "FMODAmbientWalls.windowLogicPool"
   );
   private final ObjectPool<FMODAmbientWalls.OpenWallLogic> openWallLogicPool = new ObjectPool(
      FMODAmbientWalls.OpenWallLogic::new, "FMODAmbientWalls.openWallLogicPool"
   );
   private final HashMap<String, Integer> instanceCounts = new HashMap<>();
   private final Comparator<FMODAmbientWalls.ObjectWithDistance> comp = (a, b) -> Float.compare(a.distSq, b.distSq);

   public static FMODAmbientWalls getInstance() {
      if (instance == null) {
         instance = new FMODAmbientWalls();
      }

      return instance;
   }

   private FMODAmbientWalls() {
      int numSlots = 16;
      this.slots = (FMODAmbientWalls.Slot[])PZArrayUtil.newInstance(FMODAmbientWalls.Slot.class, 16, FMODAmbientWalls.Slot::new);
   }

   public void update() {
      if (!pzopt.SoundTick.due()) { // pzopt: soundTickHz. FMOD Studio applies what this sets on its own 20 ms update; above
         return; // pzopt: soundTickHz  the tick rate the extra per-frame passes were work nobody could hear
      } // pzopt: soundTickHz
      this.addObjectsFromChunks();
      ParameterInside.calculateFloodFill();
      this.updateEmitters();
   }

   void addObjectsFromChunks() {
      for (int i = 0; i < IsoPlayer.numPlayers; i++) {
         IsoChunkMap chunkMap = IsoWorld.instance.currentCell.chunkMap[i];
         if (!chunkMap.ignore) {
            IsoPlayer player = IsoPlayer.players[i];
            if (player != null) {
               int midX = IsoChunkMap.chunkGridWidth / 2;
               int midY = IsoChunkMap.chunkGridWidth / 2;

               for (int dy = -1; dy <= 1; dy++) {
                  for (int dx = -1; dx <= 1; dx++) {
                     this.addObjectsFromChunkLevel(i, midX + dx, midY + dy);
                  }
               }
            }
         }
      }
   }

   private void addObjectsFromChunkLevel(int playerIndex, int chunkX, int chunkY) {
      IsoChunkMap chunkMap = IsoWorld.instance.getCell().getChunkMap(playerIndex);
      IsoChunk chunk = chunkMap.getChunk(chunkX, chunkY);
      if (chunk != null) {
         int playerZ = PZMath.fastfloor(IsoPlayer.players[playerIndex].getZ());
         IsoChunkLevel chunkLevel = chunk.getLevelData(playerZ);
         if (chunkLevel != null) {
            FMODAmbientWallLevelData levelData = chunkLevel.fmodAmbientWallLevelData;
            if (levelData == null) {
               levelData = chunkLevel.fmodAmbientWallLevelData = FMODAmbientWallLevelData.alloc().init(chunkLevel);
            }

            levelData.checkDirty();

            for (int i = 0; i < levelData.walls.size(); i++) {
               FMODAmbientWall wall = (FMODAmbientWall)levelData.walls.get(i);
               this.addObjects(wall);
            }
         }
      }
   }

   private void addObjects(FMODAmbientWall wall) {
      int z = wall.owner.chunkLevel.getLevel();
      if (wall.isHorizontal()) {
         for (int x = wall.x1; x < wall.x2; x++) {
            IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(x, wall.y1, z);
            if (square != null) {
               FMODAmbientWalls.ObjectWithDistance owd = (FMODAmbientWalls.ObjectWithDistance)this.objectPool.alloc();
               owd.x = square.x;
               owd.y = square.y;
               owd.z = square.z;
               owd.north = true;
               owd.setObjectAndLogic(square);
               this.objects.add(owd);
            }
         }
      } else {
         for (int y = wall.y1; y < wall.y2; y++) {
            IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(wall.x1, y, z);
            if (square != null) {
               FMODAmbientWalls.ObjectWithDistance owd = (FMODAmbientWalls.ObjectWithDistance)this.objectPool.alloc();
               owd.x = square.x;
               owd.y = square.y;
               owd.z = square.z;
               owd.north = false;
               owd.setObjectAndLogic(square);
               this.objects.add(owd);
            }
         }
      }
   }

   private void updateEmitters() {
      for (int i = 0; i < this.slots.length; i++) {
         this.slots[i].playing = false;
      }

      if (this.objects.isEmpty()) {
         this.stopNotPlaying();
      } else {
         for (int i = 0; i < this.objects.size(); i++) {
            FMODAmbientWalls.ObjectWithDistance owd = this.objects.get(i);
            owd.getEmitterPosition(this.tempVector2);
            owd.distSq = FMODParameterUtils.getClosestListenerDistanceSquared(this.tempVector2.x, this.tempVector2.y, owd.z);
            if (owd.distSq > 64.0F || !owd.shouldPlay()) {
               this.objects.remove(i--);
               this.objectPool.release(owd);
            }
         }

         this.objects.sort(this.comp);
         this.instanceCounts.clear();

         for (int i = 0; i < this.objects.size(); i++) {
            FMODAmbientWalls.ObjectWithDistance owd = this.objects.get(i);
            if (owd.logic != null) {
               String soundName = owd.logic.getSoundName();
               GameSound gameSound = GameSounds.getSound(soundName);
               if (gameSound != null && !gameSound.clips.isEmpty()) {
                  GameSoundClip clip0 = (GameSoundClip)gameSound.clips.getFirst();
                  if (!StringUtils.isNullOrWhitespace(clip0.event)) {
                     soundName = clip0.event;
                  }
               }

               int instanceCount = this.instanceCounts.getOrDefault(soundName, 0);
               if (instanceCount >= 3) {
                  this.objects.remove(i--);
                  this.objectPool.release(owd);
               } else {
                  this.instanceCounts.put(soundName, instanceCount + 1);
               }
            }
         }

         int count = Math.min(this.objects.size(), this.slots.length);

         for (int i = 0; i < count; i++) {
            FMODAmbientWalls.ObjectWithDistance owd = this.objects.get(i);
            int j = this.getExistingSlot(owd);
            if (j != -1) {
               FMODAmbientWalls.Slot slot = this.slots[j];
               this.objects.remove(i--);
               count--;
               if (slot.owd != null) {
                  this.objectPool.release(slot.owd);
               }

               slot.playSound(owd);
            }
         }

         for (int i = 0; i < count; i++) {
            FMODAmbientWalls.ObjectWithDistance owd = this.objects.get(i);
            int j = this.getExistingSlot(owd);
            if (j == -1) {
               j = this.getFreeSlot();
               FMODAmbientWalls.Slot slot = this.slots[j];
               if (slot.owd != null) {
                  if (slot.emitter != null && !slot.emitter.isPlaying(owd.logic.getSoundName())) {
                     slot.stopPlaying();
                  }

                  this.objectPool.release(slot.owd);
                  slot.owd = null;
               }

               this.objects.remove(i--);
               count--;
               slot.playSound(owd);
            }
         }

         this.stopNotPlaying();
         this.objectPool.releaseAll(this.objects);
         this.objects.clear();
      }
   }

   int getExistingSlot(FMODAmbientWalls.ObjectWithDistance owd) {
      for (int i = 0; i < this.slots.length; i++) {
         FMODAmbientWalls.ObjectWithDistance owd1 = this.slots[i].owd;
         if (owd1 != null && owd.x == owd1.x && owd.y == owd1.y && owd.z == owd1.z && owd.north == owd1.north) {
            return i;
         }
      }

      return -1;
   }

   int getFreeSlot() {
      for (int i = 0; i < this.slots.length; i++) {
         if (!this.slots[i].playing) {
            return i;
         }
      }

      return -1;
   }

   void stopNotPlaying() {
      for (int i = 0; i < this.slots.length; i++) {
         FMODAmbientWalls.Slot slot = this.slots[i];
         if (!slot.playing) {
            slot.stopPlaying();
            slot.owd = null;
         }
      }
   }

   public void squareChanged(IsoGridSquare square) {
      if (square != null && square.getChunk() != null) {
         IsoChunkLevel chunkLevel = square.getChunk().getLevelData(square.getZ());
         if (chunkLevel != null && chunkLevel.fmodAmbientWallLevelData != null) {
            chunkLevel.fmodAmbientWallLevelData.dirty = true;
         }
      }
   }

   public void render() {
      if (DebugOptions.instance.ambientWallEmittersRender.getValue()) {
         for (int playerIndex = 0; playerIndex < IsoPlayer.numPlayers; playerIndex++) {
            IsoChunkMap chunkMap = IsoWorld.instance.currentCell.chunkMap[playerIndex];
            if (!chunkMap.ignore) {
               IsoPlayer player = IsoPlayer.players[playerIndex];
               if (player != null) {
                  int playerZ = PZMath.fastfloor(player.getZ());
                  int midX = IsoChunkMap.chunkGridWidth / 2;
                  int midY = IsoChunkMap.chunkGridWidth / 2;

                  for (int dy = -1; dy <= 1; dy++) {
                     for (int dx = -1; dx <= 1; dx++) {
                        IsoChunk chunk = chunkMap.getChunk(midX + dx, midY + dy);
                        if (chunk != null) {
                           IsoChunkLevel chunkLevel = chunk.getLevelData(playerZ);
                           if (chunkLevel != null) {
                              FMODAmbientWallLevelData levelData = chunkLevel.fmodAmbientWallLevelData;
                              if (levelData != null) {
                                 for (int i = 0; i < levelData.walls.size(); i++) {
                                    FMODAmbientWall wall = (FMODAmbientWall)levelData.walls.get(i);
                                    LineDrawer.addLine(wall.x1, wall.y1, playerZ, wall.x2, wall.y2, playerZ, 1.0F, 0.0F, 0.0F, 1.0F);
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }

         for (FMODAmbientWalls.Slot slot : this.slots) {
            if (slot.owd != null) {
               FMODAmbientWalls.ObjectWithDistance owd = slot.owd;
               int w = owd.north ? 1 : 0;
               int h = 1 - w;
               LineDrawer.addLine(owd.x, owd.y, owd.z, owd.x + w, owd.y + h, owd.z, 0.0F, 1.0F, 0.0F, 1.0F);
            }
         }
      }
   }

   static final class ObjectWithDistance {
      int x;
      int y;
      int z;
      boolean north;
      IsoObject object;
      ObjectAmbientEmitters.PerObjectLogic logic;
      float distSq;

      Vector2 getEmitterPosition(Vector2 pos) {
         if (this.object instanceof IsoDoor door) {
            return door.getFacingPosition(pos);
         } else if (this.object instanceof IsoWindow window) {
            return window.getFacingPosition(pos);
         } else {
            return this.north ? pos.set(this.x + 0.5F, this.y) : pos.set(this.x, this.y + 0.5F);
         }
      }

      void setObjectAndLogic(IsoGridSquare square) {
         this.release();
         this.object = square.getDoor(this.north);
         if (this.object != null) {
            this.logic = ((ObjectAmbientEmitters.DoorLogic)FMODAmbientWalls.getInstance().doorLogicPool.alloc()).init(this.object);
         } else {
            this.object = square.getWindow(this.north);
            if (this.object != null) {
               this.logic = ((ObjectAmbientEmitters.WindowLogic)FMODAmbientWalls.getInstance().windowLogicPool.alloc()).init(this.object);
            } else {
               this.logic = ((FMODAmbientWalls.OpenWallLogic)FMODAmbientWalls.getInstance().openWallLogicPool.alloc()).init(square, this.north);
            }
         }
      }

      boolean shouldPlay() {
         return this.object != null && this.object.getObjectIndex() == -1 ? false : this.logic.shouldPlaySound();
      }

      void release() {
         if (this.logic instanceof ObjectAmbientEmitters.DoorLogic logic1) {
            FMODAmbientWalls.getInstance().doorLogicPool.release(logic1);
         } else if (this.logic instanceof ObjectAmbientEmitters.WindowLogic logic1) {
            FMODAmbientWalls.getInstance().windowLogicPool.release(logic1);
         } else if (this.logic instanceof FMODAmbientWalls.OpenWallLogic logic1) {
            FMODAmbientWalls.getInstance().openWallLogicPool.release(logic1);
         }

         this.object = null;
         this.logic = null;
      }
   }

   public static final class OpenWallLogic extends ObjectAmbientEmitters.PerObjectLogic {
      IsoGridSquare square;
      boolean north;

      public FMODAmbientWalls.OpenWallLogic init(IsoGridSquare square, boolean north) {
         super.init(null);
         this.square = square;
         this.north = north;
         return this;
      }

      @Override
      public boolean shouldPlaySound() {
         return AmbientStreamManager.instance.isParameterInsideTrue() && this.isReachableSquare();
      }

      @Override
      public String getSoundName() {
         return SoundKey.OPEN_WALL_AMBIENCE.toString();
      }

      @Override
      public void startPlaying(BaseSoundEmitter emitter, long instance) {
      }

      @Override
      public void checkParameters(BaseSoundEmitter emitter, long instance) {
         this.setParameterValue(0, emitter, instance, "DoorWindowOpen", 1.0F);
      }

      boolean isReachableSquare() {
         return this.square != null && ParameterInside.isAdjacentToReachableSquare(this.square, this.north);
      }
   }

   static final class Slot {
      FMODAmbientWalls.ObjectWithDistance owd;
      BaseSoundEmitter emitter;
      long instance;
      boolean playing;

      void playSound(FMODAmbientWalls.ObjectWithDistance owd) {
         this.owd = owd;
         ObjectAmbientEmitters.PerObjectLogic logic = owd.logic;
         if (this.emitter == null) {
            this.emitter = (BaseSoundEmitter)(Core.soundDisabled ? new DummySoundEmitter() : new FMODSoundEmitter());
         }

         Vector2 tempVector2 = owd.getEmitterPosition(FMODAmbientWalls.getInstance().tempVector2);
         this.emitter.setPos(tempVector2.getX(), tempVector2.getY(), owd.z);
         String soundName = logic.getSoundName();
         if (!this.emitter.isPlaying(soundName)) {
            this.emitter.stopAll();
            if (this.emitter instanceof FMODSoundEmitter fmodSoundEmitter) {
               fmodSoundEmitter.clearParameters();
            }

            this.instance = this.emitter.playSoundImpl(soundName, (IsoObject)null);
            logic.startPlaying(this.emitter, this.instance);
         }

         logic.checkParameters(this.emitter, this.instance);
         this.playing = true;
         this.emitter.tick();
      }

      void stopPlaying() {
         if (this.emitter != null && this.instance != 0L) {
            ObjectAmbientEmitters.PerObjectLogic logic = this.owd.logic;
            logic.stopPlaying(this.emitter, this.instance);
            if (this.emitter.hasSustainPoints(this.instance)) {
               this.emitter.triggerCue(this.instance);
               this.instance = 0L;
            } else {
               this.emitter.stopAll();
               this.instance = 0L;
            }
         }
      }
   }
}
