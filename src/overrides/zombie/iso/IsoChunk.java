package zombie.iso;

import gnu.trove.list.array.TLongArrayList;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import pzopt.Guard;
import pzopt.RecalcPool;
import java.util.Stack;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.CRC32;
import zombie.ChunkMapFilenames;
import zombie.FliesSound;
import zombie.GameProfiler;
import zombie.GameTime;
import zombie.GameWindow;
import zombie.LoadGridsquarePerformanceWorkaround;
import zombie.LootRespawn;
import zombie.MapCollisionData;
import zombie.ReanimatedPlayers;
import zombie.SandboxOptions;
import zombie.SystemDisabler;
import zombie.UsedFromLua;
import zombie.VirtualZombieManager;
import zombie.WorldSoundManager;
import zombie.ZombieSpawnRecorder;
import zombie.ZomboidFileSystem;
import zombie.GameProfiler.ProfileArea;
import zombie.Lua.LuaEventManager;
import zombie.Lua.MapObjects;
import zombie.WorldSoundManager.WorldSound;
import zombie.audio.ObjectAmbientEmitters.PerObjectLogic;
import zombie.basements.Basements;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoSurvivor;
import zombie.characters.IsoZombie;
import zombie.characters.RagdollBuilder;
import zombie.characters.IsoGameCharacter.Location;
import zombie.characters.animals.AnimalPopulationManager;
import zombie.characters.animals.IsoAnimal;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.logger.ExceptionLogger;
import zombie.core.logger.LoggerManager;
import zombie.core.math.PZMath;
import zombie.core.network.ByteBufferWriter;
import zombie.core.physics.Bullet;
import zombie.core.physics.RagdollController;
import zombie.core.physics.WorldSimulation;
import zombie.core.properties.IsoPropertyType;
import zombie.core.properties.PropertyContainer;
import zombie.core.raknet.UdpConnection;
import zombie.core.random.Rand;
import zombie.core.stash.StashSystem;
import zombie.core.utils.BoundedQueue;
import zombie.debug.DebugLog;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.erosion.ErosionMain;
import zombie.erosion.ErosionData.Chunk;
import zombie.globalObjects.SGlobalObjects;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.ItemPickerJava;
import zombie.inventory.ItemSpawner;
import zombie.inventory.ItemPickerJava.ItemPickerContainer;
import zombie.inventory.types.InventoryContainer;
import zombie.iso.CorpseCount.ChunkData;
import zombie.iso.IsoGridSquare.GetSquare;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.SpriteDetails.IsoObjectType;
import zombie.iso.areas.IsoBuilding;
import zombie.iso.areas.IsoRoom;
import zombie.iso.enums.ChunkGenerationStatus;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;
import zombie.iso.fboRenderChunk.FBORenderLevels;
import zombie.iso.fboRenderChunk.FBORenderOcclusion;
import zombie.iso.fboRenderChunk.FBORenderCutaways.ChunkLevelData;
import zombie.iso.fboRenderChunk.FBORenderCutaways.ChunkLevelsData;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoGenerator;
import zombie.iso.objects.IsoLightSwitch;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoTree;
import zombie.iso.objects.RainManager;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteInstance;
import zombie.iso.sprite.IsoSpriteManager;
import zombie.iso.worldgen.ChunksCache;
import zombie.iso.worldgen.WorldGenChunk;
import zombie.iso.worldgen.WorldGenUtils;
import zombie.iso.worldgen.blending.BlendDirection;
import zombie.iso.worldgen.utils.ChunkCoord;
import zombie.iso.worldgen.utils.SquareCoord;
import zombie.iso.zones.VehicleZone;
import zombie.iso.zones.Zone;
import zombie.network.ChunkChecksum;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.network.PacketTypes.PacketType;
import zombie.network.packets.INetworkPacket;
import zombie.pathfind.PolygonalMap2;
import zombie.pathfind.nativeCode.PathfindNative;
import zombie.popman.ZombiePopulationManager;
import zombie.popman.animal.AnimalInstanceManager;
import zombie.randomizedWorld.randomizedBuilding.RandomizedBuildingBase;
import zombie.randomizedWorld.randomizedRanch.RandomizedRanchBase;
import zombie.randomizedWorld.randomizedVehicleStory.RandomizedVehicleStoryBase;
import zombie.randomizedWorld.randomizedVehicleStory.VehicleStorySpawnData;
import zombie.randomizedWorld.randomizedZoneStory.RandomizedZoneStoryBase;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.VehicleScript;
import zombie.util.CappedConcurrentQueue;
import zombie.util.StringUtils;
import zombie.util.list.PZArrayUtil;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleType;
import zombie.vehicles.VehiclesDB2;
import zombie.vehicles.VirtualVehicleManager;
import zombie.vehicles.BaseVehicle.MinMaxPosition;
import zombie.vehicles.VehicleType.VehicleTypeDefinition;
import zombie.vispoly.VisibilityPolygon2;

@UsedFromLua
public final class IsoChunk {
   // pzopt: exact occluder mask per level (index z + 32) and a bit per level saying the mask is stored; replaces the
   // per-frame map lookup FBORenderCell.calculateOccludingSquares used for clean levels (cutawayFast)
   public final long[] pzoptOccluderMask = new long[64];
   public long pzoptOccluderMaskSet;
   public pzopt.PuddleCache.Slot pzoptPuddles; // pzopt: packed puddle vertices per player and level (Config puddleCache)
   // pzopt: frame number of the last cacheLightInfo per square, rows indexed playerIndex * 64 + level + 32
   // (Config.LIGHT_INFO_ONCE_PER_FRAME, used by FBORenderCell)
   public int[][] pzoptLightInfoFrame;
   // pzopt: per level (index z + 32), the frame a square of the level accumulated a strong light change and the frame the
   // level's texture was last baked (pzopt.LightDirt: a torch sweep re-bakes now, sky drift and flashes stay held)
   public final int[] pzoptLightStrongFrame = new int[64];
   public final int[] pzoptLightBakeFrame = new int[64];
   // pzopt: per 5x5 neighbour slot, a fingerprint of this chunk's baked trees whose sprite overlaps that neighbour's
   // chunk-level texture, so a tree removed or turned per-frame here re-bakes the neighbour that holds a copy of it
   // (Config.TREE_BAKE_PASS, pzopt.TreeBake, issue #5)
   public int[] pzoptTreeExportFp;
   // pzopt: which squares of levels 0 and 1 take fog (exterior and not in a room), packed per diagonal, refreshed
   // every Config.fogMaskFrames frames (pzopt.FogPass.ChunkFog; the fog row walk reads it instead of the squares)
   public pzopt.FogPass.ChunkFog pzoptFog;
   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
   static {
      pzopt.Overrides.onClassLoaded("zombie.iso.IsoChunk");
   }

   private static final IsoDirections[] DIRECTIONS = IsoDirections.values();
   private final Set<IsoGridSquare> delayedPhysicsShapeSet = new HashSet<>();
   public static boolean doServerRequests = true;
   public int wx;
   public int wy;
   public IsoGridSquare[][] squares;
   public ChunkData corpseCount;
   public zombie.FliesSound.ChunkData corpseData;
   private final FBORenderLevels[] renderLevels = new FBORenderLevels[4];
   private ArrayList<Location> generatorsTouchingThisChunk;
   private IsoChunkLevel[] levels = new IsoChunkLevel[1];
   public int maxLevel;
   public int minLevel;
   public final ArrayList<WorldSound> soundList = new ArrayList<>();
   private int treeCount;
   private int numberOfWaterTiles;
   public int lightingUpdateCounter;
   private Zone scavengeZone;
   private final TLongArrayList spawnedRooms = new TLongArrayList();
   public IsoChunk next;
   public final zombie.pathfind.CollideWithObstaclesPoly.ChunkData collision = new zombie.pathfind.CollideWithObstaclesPoly.ChunkData();
   public int adjacentChunkLoadedCounter;
   public int pzoptSeamDirs; // pzopt: seamDirections, 1 = a south / east neighbour loaded since the last seam check, 2 = another side
   public VehicleStorySpawnData vehicleStorySpawnData;
   public Object loadVehiclesObject;
   public final zombie.audio.ObjectAmbientEmitters.ChunkData objectEmitterData = new zombie.audio.ObjectAmbientEmitters.ChunkData();
   public final ChunkLevelsData cutawayData = new ChunkLevelsData(this);
   public final zombie.vispoly.VisibilityPolygon2.ChunkData vispolyData = new zombie.vispoly.VisibilityPolygon2.ChunkData(this);
   // pzopt: zoomRetain. Bit (minLevel + 32) per player: the level pair came back on screen (a zoom-out) and its
   // texture, kept or still to be made, is handled under the zoom budget (FBORenderCell.renderOneLevel)
   public final long[] pzoptZoomReturned = new long[4];
   public final long[] pzoptZoomAllowed = new long[4]; // pzopt: zoomRetain, the bits of pzoptZoomReturned this frame's plan bakes
   private boolean blendingDoneFull;
   private boolean blendingDonePartial;
   private boolean[] blendingModified = new boolean[4];
   private final byte[] blendingDepth = new byte[]{
      BlendDirection.NORTH.defaultDepth, BlendDirection.SOUTH.defaultDepth, BlendDirection.WEST.defaultDepth, BlendDirection.EAST.defaultDepth
   };
   private boolean attachmentsDoneFull = true;
   private boolean[] attachmentsState = new boolean[]{true, true, true, true, true};
   private List<SquareCoord> attachmentsPartial;
   private static final boolean[] comparatorBool4 = new boolean[]{true, true, true, true};
   private static final boolean[] comparatorBool5 = new boolean[]{true, true, true, true, true};
   private EnumSet<ChunkGenerationStatus> chunkGenerationStatus = EnumSet.noneOf(ChunkGenerationStatus.class);
   public static boolean doWorldgen = true;
   public static boolean doForaging = true;
   public static boolean doAttachments = true;
   public long loadedFrame;
   public long renderFrame;
   private static int frameDelay;
   private static final int maxFrameDelay = 5;
   public boolean requiresHotSave;
   public boolean preventHotSave;
   private boolean ignorePathfind;
   public IsoChunk.JobType jobType = IsoChunk.JobType.None;
   public LotHeader lotheader;
   public final BoundedQueue<IsoFloorBloodSplat> floorBloodSplats = new BoundedQueue(1000);
   public final ArrayList<IsoFloorBloodSplat> floorBloodSplatsFade = new ArrayList<>();
   private static final int MAX_BLOOD_SPLATS = 1000;
   private int nextSplatIndex;
   public static final byte[][] renderByIndex = new byte[][]{
      {1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
      {1, 0, 0, 0, 0, 1, 0, 0, 0, 0},
      {1, 0, 0, 1, 0, 0, 1, 0, 0, 0},
      {1, 0, 0, 1, 0, 1, 0, 0, 1, 0},
      {1, 0, 1, 0, 1, 0, 1, 0, 1, 0},
      {1, 1, 0, 1, 1, 0, 1, 1, 0, 0},
      {1, 1, 0, 1, 1, 0, 1, 1, 0, 1},
      {1, 1, 1, 1, 0, 1, 1, 1, 1, 0},
      {1, 1, 1, 1, 1, 1, 1, 1, 1, 0},
      {1, 1, 1, 1, 1, 1, 1, 1, 1, 1}
   };
   public final ArrayList<IsoChunkMap> refs = new ArrayList<>();
   public boolean loaded;
   private boolean blam;
   private boolean addZombies;
   public ArrayList<IsoGridSquare> proceduralZombieSquares = new ArrayList<>();
   private boolean fixed2x;
   public final boolean[] lightCheck = new boolean[4];
   public final boolean[] lightingNeverDone = new boolean[4];
   public final ArrayList<IsoRoomLight> roomLights = new ArrayList<>();
   public final ArrayList<BaseVehicle> vehicles = new ArrayList<>();
   public int lootRespawnHour = -1;
   public static final short LB_PATHFIND = 2;
   public short loadedBits;
   private static final short INVALID_LOAD_ID = -1;
   private static short nextLoadID;
   private short loadId = -1;
   public int objectsSyncCount;
   private static int addVehiclesForTestVtype;
   private static int addVehiclesForTestVskin;
   private static int addVehiclesForTestVrot;
   private static final ArrayList<BaseVehicle> BaseVehicleCheckedVehicles = new ArrayList<>();
   private int minLevelPhysics = 1000;
   private int maxLevelPhysics = 1000;
   private static final int MAX_SHAPES = 4;
   private final int[] shapes = new int[4];
   private static final byte[] bshapes = new byte[4];
   private static final IsoChunk.ChunkGetter chunkGetter = new IsoChunk.ChunkGetter();
   static final ArrayList<IsoGridSquare> newSquareList = new ArrayList<>();
   private boolean loadedPhysics;
   public ArrayList<IsoGameCharacter> ragdollControllersForAddToWorld;
   public static final CappedConcurrentQueue<IsoChunk> loadGridSquare = new CappedConcurrentQueue(Integer.MAX_VALUE);
   public static final int BLOCK_SIZE = 65536;
   private static ByteBuffer sliceBuffer = ByteBuffer.allocate(65536);
   private static ByteBuffer sliceBufferLoad = ByteBuffer.allocate(65536);
   public static final Object WriteLock = new Object();
   private static final ArrayList<RoomDef> tempRoomDefs = new ArrayList<>();
   private static final ArrayList<BuildingDef> tempBuildingDefs = new ArrayList<>();
   private static final ArrayList<IsoBuilding> tempBuildings = new ArrayList<>();
   private static final ArrayList<IsoChunk.ChunkLock> Locks = new ArrayList<>();
   private static final Stack<IsoChunk.ChunkLock> FreeLocks = new Stack<>();
   private static final IsoChunk.SanityCheck sanityCheck = new IsoChunk.SanityCheck();
   private static final CRC32 crcLoad = new CRC32();
   private static final CRC32 crcSave = new CRC32();
   private Chunk erosion;
   private static final HashMap<String, String> Fix2xMap = new HashMap<>();
   public int randomId;
   public long revision;

   public void flagForHotSave() {
      if (!this.preventHotSave) {
         this.requiresHotSave = true;
      }
   }

   public void updateSounds() {
      synchronized (WorldSoundManager.instance.soundList) {
         if (pzopt.Config.WORLD_SOUND_CLEANUP_FAST && pzopt.Overrides.enabled() && WorldSoundManager.pzoptUniformLife) { // pzopt: worldSoundCleanupFast.
            // Every sound so far was born with life 16 and all lose one per update together, and this list is in creation
            // order, so its dead entries are a prefix: trim it and stop at the first live one.
            int pzoptK = 0; // pzopt
            int pzoptN = this.soundList.size(); // pzopt
            while (pzoptK < pzoptN) { // pzopt
               WorldSound sound = this.soundList.get(pzoptK); // pzopt
               if (sound != null && sound.life > 0) { // pzopt
                  break; // pzopt
               } // pzopt
               pzoptK++; // pzopt
            } // pzopt
            if (pzopt.Config.DEV_WORLD_SOUND_CLEANUP_CHECK) { // pzopt: the rig: nothing dead may follow the prefix
               for (int r = pzoptK; r < pzoptN; r++) { // pzopt
                  WorldSound sound = this.soundList.get(r); // pzopt
                  if (sound == null || sound.life <= 0) { // pzopt
                     WorldSoundManager.pzoptPrefixMiss++; // pzopt
                  } // pzopt
               } // pzopt
            } // pzopt
            if (pzoptK > 0) { // pzopt
               this.soundList.subList(0, pzoptK).clear(); // pzopt
            } // pzopt
            return; // pzopt
         } // pzopt
         if (pzopt.Config.WORLD_SOUND_CLEANUP_FAST && pzopt.Overrides.enabled()) { // pzopt: worldSoundCleanupFast, one pass that
            int pzoptW = 0; // pzopt: keeps the live sounds in order (stock removed each dead one with a shift)
            int pzoptN = this.soundList.size(); // pzopt
            for (int r = 0; r < pzoptN; r++) { // pzopt
               WorldSound sound = this.soundList.get(r); // pzopt
               if (sound != null && sound.life > 0) { // pzopt
                  if (pzoptW != r) { // pzopt
                     this.soundList.set(pzoptW, sound); // pzopt
                  } // pzopt
                  pzoptW++; // pzopt
               } // pzopt
            } // pzopt
            if (pzoptW < pzoptN) { // pzopt
               this.soundList.subList(pzoptW, pzoptN).clear(); // pzopt
            } // pzopt
            return; // pzopt
         } // pzopt
         int s = this.soundList.size();

         for (int n = 0; n < s; n++) {
            WorldSound sound = this.soundList.get(n);
            if (sound == null || sound.life <= 0) {
               this.soundList.remove(n);
               n--;
               s--;
            }
         }
      }
   }

   public boolean IsOnScreen(boolean halfTileBorder) {
      int chunksPerWidth = 8;
      float x1 = IsoUtils.XToScreen(this.wx * 8, this.wy * 8, this.minLevel, 0);
      float y1 = IsoUtils.YToScreen(this.wx * 8, this.wy * 8, this.minLevel, 0);
      float x2 = IsoUtils.XToScreen(this.wx * 8 + 8, this.wy * 8, this.minLevel, 0);
      float y2 = IsoUtils.YToScreen(this.wx * 8 + 8, this.wy * 8, this.minLevel, 0);
      float x3 = IsoUtils.XToScreen(this.wx * 8 + 8, this.wy * 8 + 8, this.minLevel, 0);
      float y3 = IsoUtils.YToScreen(this.wx * 8 + 8, this.wy * 8 + 8, this.minLevel, 0);
      float x4 = IsoUtils.XToScreen(this.wx * 8, this.wy * 8 + 8, this.minLevel, 0);
      float y4 = IsoUtils.YToScreen(this.wx * 8, this.wy * 8 + 8, this.minLevel, 0);
      float minX = PZMath.min(x1, x2, x3, x4);
      float maxX = PZMath.max(x1, x2, x3, x4);
      float minY = PZMath.min(y1, y2, y3, y4);
      float maxY = PZMath.max(y1, y2, y3, y4);
      x1 = IsoUtils.XToScreen(this.wx * 8, this.wy * 8, this.maxLevel + 1, 0);
      y1 = IsoUtils.YToScreen(this.wx * 8, this.wy * 8, this.maxLevel + 1, 0);
      x2 = IsoUtils.XToScreen(this.wx * 8 + 8, this.wy * 8, this.maxLevel + 1, 0);
      y2 = IsoUtils.YToScreen(this.wx * 8 + 8, this.wy * 8, this.maxLevel + 1, 0);
      x3 = IsoUtils.XToScreen(this.wx * 8 + 8, this.wy * 8 + 8, this.maxLevel + 1, 0);
      y3 = IsoUtils.YToScreen(this.wx * 8 + 8, this.wy * 8 + 8, this.maxLevel + 1, 0);
      x4 = IsoUtils.XToScreen(this.wx * 8, this.wy * 8 + 8, this.maxLevel + 1, 0);
      y4 = IsoUtils.YToScreen(this.wx * 8, this.wy * 8 + 8, this.maxLevel + 1, 0);
      minX = PZMath.min(minX, x1, x2, x3, x4);
      maxX = PZMath.max(maxX, x1, x2, x3, x4);
      minY = PZMath.min(minY, y1, y2, y3, y4);
      maxY = PZMath.max(maxY, y1, y2, y3, y4);
      minY -= FBORenderLevels.extraHeightForJumboTrees(this.minLevel, this.maxLevel);
      if (this.minLevel == 0) {
         x1 = IsoUtils.YToScreen(this.wx * 8, this.wy * 8, this.minLevel, 0);
         x1 += FBORenderChunk.FLOOR_HEIGHT;
         x1 -= FBORenderChunk.JUMBO_XXL_HEIGHT;
         minY = PZMath.min(minY, x1);
      }

      int playerIndex = IsoCamera.frameState.playerIndex;
      y1 = IsoCamera.frameState.offX;
      x2 = IsoCamera.frameState.offY;
      minX -= y1;
      minY -= x2;
      maxX -= y1;
      maxY -= x2;
      y2 = maxX - minX;
      x3 = maxY - minY;
      int border = 0;
      if (maxX <= 0.0F) {
         return false;
      } else if (maxY <= 0.0F) {
         return false;
      } else {
         return minX >= IsoCamera.frameState.offscreenWidth + 0 ? false : !(minY >= IsoCamera.frameState.offscreenHeight + 0);
      }
   }

   public IsoChunk(IsoCell cell) {
      this.levels[0] = IsoChunkLevel.alloc().init(this, this.minLevel);
      this.squares = new IsoGridSquare[1][];
      this.squares[0] = this.levels[0].squares;
      this.checkLightingLater_AllPlayers_OneLevel(this.levels[0].getLevel());

      for (int playerIndex = 0; playerIndex < 4; playerIndex++) {
         this.lightCheck[playerIndex] = true;
         this.lightingNeverDone[playerIndex] = true;
      }
   }

   public IsoChunk(WorldReuserThread dummy) {
   }

   public void checkLightingLater_AllPlayers_AllLevels() {
      Arrays.fill(this.lightCheck, true);

      for (int z = this.getMinLevel(); z <= this.getMaxLevel(); z++) {
         IsoChunkLevel chunkLevel = this.getLevelData(z);
         Arrays.fill(chunkLevel.lightCheck, true);
      }
   }

   public void checkLightingLater_AllPlayers_OneLevel(int level) {
      IsoChunkLevel chunkLevel = this.getLevelData(level);
      if (chunkLevel != null) {
         Arrays.fill(this.lightCheck, true);
         Arrays.fill(chunkLevel.lightCheck, true);
      }
   }

   public void checkLightingLater_OnePlayer_AllLevels(int playerIndex) {
      this.lightCheck[playerIndex] = true;

      for (int z = this.getMinLevel(); z <= this.getMaxLevel(); z++) {
         IsoChunkLevel chunkLevel = this.getLevelData(z);
         chunkLevel.lightCheck[playerIndex] = true;
      }
   }

   public void checkLightingLater_OnePlayer_OneLevel(int playerIndex, int level) {
      IsoChunkLevel chunkLevel = this.getLevelData(level);
      if (chunkLevel != null) {
         chunkLevel.lightCheck[playerIndex] = true;
      }
   }

   public void addBloodSplat(float x, float y, float z, int type) {
      if (!(x < this.wx * 8) && !(x >= (this.wx + 1) * 8)) {
         if (!(y < this.wy * 8) && !(y >= (this.wy + 1) * 8)) {
            IsoGridSquare sq = this.getGridSquare(PZMath.fastfloor(x - this.wx * 8), PZMath.fastfloor(y - this.wy * 8), PZMath.fastfloor(z));
            if (sq != null && sq.isSolidFloor()) {
               IsoFloorBloodSplat b = new IsoFloorBloodSplat(x - this.wx * 8, y - this.wy * 8, z, type, (float)GameTime.getInstance().getWorldAgeHours());
               if (type < 8) {
                  b.index = ++this.nextSplatIndex;
                  if (this.nextSplatIndex >= 10) {
                     this.nextSplatIndex = 0;
                  }
               }

               if (this.floorBloodSplats.isFull()) {
                  IsoFloorBloodSplat b2 = (IsoFloorBloodSplat)this.floorBloodSplats.removeFirst();
                  b2.fade = PerformanceSettings.getLockFPS() * 5;
                  this.floorBloodSplatsFade.add(b2);
               }

               this.floorBloodSplats.add(b);
               if (PerformanceSettings.fboRenderChunk && Thread.currentThread() == GameWindow.gameThread) {
                  this.invalidateRenderChunkLevel(sq.z, 1L);
               }

               if (GameServer.server) {
                  INetworkPacket.sendToRelative(PacketType.AddBlood, x, y, new Object[]{x, y, z, type, sq});
               }
            }
         }
      }
   }

   public void AddCorpses(int wx, int wy) {
      if (!IsoWorld.getZombiesDisabled() && !"Tutorial".equals(Core.gameMode)) {
         IsoMetaChunk ch = IsoWorld.instance.getMetaChunk(wx, wy);
         if (ch != null) {
            float nz = ch.getZombieIntensity();
            nz *= 0.1F;
            int n = 0;
            if (nz < 1.0F) {
               if (Rand.Next(100) < nz * 100.0F) {
                  n = 1;
               }
            } else {
               n = Rand.Next(0, PZMath.fastfloor(nz));
            }

            if (n > 0) {
               int timeout = 0;

               IsoGridSquare sq;
               do {
                  int x = Rand.Next(10);
                  int y = Rand.Next(10);
                  sq = this.getGridSquare(x, y, 0);
                  timeout++;
               } while (timeout < 100 && (sq == null || !RandomizedBuildingBase.is2x2AreaClear(sq)));

               if (timeout == 100) {
                  return;
               }

               if (sq != null) {
                  int amount = 14;
                  if (Rand.Next(10) == 0) {
                     amount = 50;
                  }

                  if (Rand.Next(40) == 0) {
                     amount = 100;
                  }

                  for (int m = 0; m < amount; m++) {
                     float rx = Rand.Next(3000) / 1000.0F;
                     float ry = Rand.Next(3000) / 1000.0F;
                     rx -= 1.5F;
                     ry -= 1.5F;
                     this.addBloodSplat(sq.getX() + rx, sq.getY() + ry, sq.getZ(), Rand.Next(20));
                  }

                  boolean isSkeleton = Rand.Next(15 - SandboxOptions.instance.timeSinceApo.getValue()) == 0;
                  IsoDeadBody body = sq.addCorpse(isSkeleton);
                  if (body != null) {
                     if (isSkeleton) {
                        body.getHumanVisual().setSkinTextureIndex(2);
                     }

                     body.setFakeDead(false);
                     if (!isSkeleton && Rand.Next(3) == 0) {
                        VirtualZombieManager.instance.createEatingZombies(body, Rand.Next(1, 4));
                     } else if (isSkeleton && Rand.Next(6) == 0) {
                        VirtualZombieManager.instance.createEatingZombies(body, Rand.Next(1, 4));
                     } else if (!isSkeleton && Rand.Next(10) == 0) {
                        body.setFakeDead(true);
                        if (Rand.Next(5) == 0) {
                           body.setCrawling(true);
                        }
                     }

                     int ratChance = 400;
                     if (Objects.equals(sq.getSquareZombiesType(), "StreetPoor") || Objects.equals(sq.getZoneType(), "TrailerPark")) {
                        ratChance /= 2;
                     }

                     if (Objects.equals(sq.getSquareZombiesType(), "Rich") || Objects.equals(sq.getLootZone(), "Rich")) {
                        ratChance *= 2;
                     }

                     if (sq.getZ() < 0) {
                        ratChance /= 2;
                     }

                     if (sq.canSpawnVermin() && Rand.Next(ratChance) < SandboxOptions.instance.getCurrentRatIndex()) {
                        int max = SandboxOptions.instance.getCurrentRatIndex() / 10;
                        if (Objects.equals(sq.getSquareZombiesType(), "StreetPoor") || Objects.equals(sq.getZoneType(), "TrailerPark")) {
                           max *= 2;
                        }

                        if (max < 1) {
                           max = 1;
                        }

                        if (max > 7) {
                           max = 7;
                        }

                        int nbrOfRats = Rand.Next(1, max);
                        String breed = "grey";
                        if (sq != null
                           && sq.getBuilding() != null
                           && (
                              sq.getBuilding().hasRoom("laboratory")
                                 || sq.getBuilding().hasRoom("classroom")
                                 || sq.getBuilding().hasRoom("secondaryclassroom")
                                 || Objects.equals(sq.getZombiesType(), "University")
                           )
                           && !Rand.NextBool(3)) {
                           breed = "white";
                        }

                        IsoAnimal animal;
                        if (Rand.NextBool(2)) {
                           animal = new IsoAnimal(IsoWorld.instance.getCell(), sq.getX(), sq.getY(), sq.getZ(), "rat", breed);
                        } else {
                           animal = new IsoAnimal(IsoWorld.instance.getCell(), sq.getX(), sq.getY(), sq.getZ(), "ratfemale", breed);
                        }

                        animal.addToWorld();
                        animal.randomizeAge();
                        if (nbrOfRats > 1) {
                           for (int i = 1; i < nbrOfRats; i++) {
                              IsoGridSquare square = sq.getAdjacentSquare(IsoDirections.getRandom());
                              if (square != null && square.isFree(true) && square.isSolidFloor()) {
                                 if (Rand.NextBool(2)) {
                                    animal = new IsoAnimal(IsoWorld.instance.getCell(), square.getX(), square.getY(), square.getZ(), "rat", breed);
                                 } else {
                                    animal = new IsoAnimal(IsoWorld.instance.getCell(), square.getX(), square.getY(), square.getZ(), "ratfemale", breed);
                                 }

                                 animal.addToWorld();
                                 animal.randomizeAge();
                                 if (Rand.NextBool(3)) {
                                    animal.setStateEventDelayTimer(0.0F);
                                 } else if (square.canReachTo(sq)) {
                                    animal.fleeTo(sq);
                                 }
                              }
                           }
                        }

                        int nbrOfPoops = Rand.Next(0, max);

                        for (int i = 0; i < nbrOfPoops; i++) {
                           IsoGridSquare square = sq.getAdjacentSquare(IsoDirections.getRandom());
                           if (square != null && square.isFree(true) && square.isSolidFloor()) {
                              this.addItemOnGround(square, "Base.Dung_Rat");
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   public void AddBlood(int wx, int wy) {
      IsoMetaChunk ch = IsoWorld.instance.getMetaChunk(wx, wy);
      if (ch != null) {
         float nz = ch.getZombieIntensity();
         nz *= 0.1F;
         if (Rand.Next(40) == 0) {
            nz += 10.0F;
         }

         int n = 0;
         if (nz < 1.0F) {
            if (Rand.Next(100) < nz * 100.0F) {
               n = 1;
            }
         } else {
            n = Rand.Next(0, PZMath.fastfloor(nz));
         }

         if (n > 0) {
            VirtualZombieManager.instance.AddBloodToMap(n, this);
         }
      }
   }

   private void checkVehiclePos(BaseVehicle vehicle, IsoChunk chunk) {
      this.fixVehiclePos(vehicle, chunk);
      IsoDirections dir = vehicle.getDir();
      switch (dir) {
         case E:
         case W:
            if (vehicle.getX() - chunk.wx * 8 < vehicle.getScript().getExtents().x) {
               IsoGridSquare sq2 = IsoWorld.instance
                  .currentCell
                  .getGridSquare(vehicle.getX() - vehicle.getScript().getExtents().x, vehicle.getY(), vehicle.getZ());
               if (sq2 == null) {
                  return;
               }

               this.fixVehiclePos(vehicle, sq2.chunk);
            }

            if (vehicle.getX() - chunk.wx * 8 > 8.0F - vehicle.getScript().getExtents().x) {
               IsoGridSquare sq2 = IsoWorld.instance
                  .currentCell
                  .getGridSquare(vehicle.getX() + vehicle.getScript().getExtents().x, vehicle.getY(), vehicle.getZ());
               if (sq2 == null) {
                  return;
               }

               this.fixVehiclePos(vehicle, sq2.chunk);
            }
            break;
         case N:
         case S:
            if (vehicle.getY() - chunk.wy * 8 < vehicle.getScript().getExtents().z) {
               IsoGridSquare sq2 = IsoWorld.instance
                  .currentCell
                  .getGridSquare(vehicle.getX(), vehicle.getY() - vehicle.getScript().getExtents().z, vehicle.getZ());
               if (sq2 == null) {
                  return;
               }

               this.fixVehiclePos(vehicle, sq2.chunk);
            }

            if (vehicle.getY() - chunk.wy * 8 > 8.0F - vehicle.getScript().getExtents().z) {
               IsoGridSquare sq2 = IsoWorld.instance
                  .currentCell
                  .getGridSquare(vehicle.getX(), vehicle.getY() + vehicle.getScript().getExtents().z, vehicle.getZ());
               if (sq2 == null) {
                  return;
               }

               this.fixVehiclePos(vehicle, sq2.chunk);
            }
      }
   }

   private boolean fixVehiclePos(BaseVehicle vehicle, IsoChunk chunk) {
      MinMaxPosition vpos = vehicle.getMinMaxPosition();
      boolean vch = false;
      IsoDirections dir = vehicle.getDir();

      for (int i = 0; i < chunk.vehicles.size(); i++) {
         MinMaxPosition v2pos = chunk.vehicles.get(i).getMinMaxPosition();
         switch (dir) {
            case E:
            case W:
               float dx = v2pos.minX - vpos.maxX;
               if (dx > 0.0F && vpos.minY < v2pos.maxY && vpos.maxY > v2pos.minY) {
                  vehicle.setX(vehicle.getX() - dx);
                  vpos.minX -= dx;
                  vpos.maxX -= dx;
                  vch = true;
               } else {
                  dx = vpos.minX - v2pos.maxX;
                  if (dx > 0.0F && vpos.minY < v2pos.maxY && vpos.maxY > v2pos.minY) {
                     vehicle.setX(vehicle.getX() + dx);
                     vpos.minX += dx;
                     vpos.maxX += dx;
                     vch = true;
                  }
               }
               break;
            case N:
            case S:
               float d = v2pos.minY - vpos.maxY;
               if (d > 0.0F && vpos.minX < v2pos.maxX && vpos.maxX > v2pos.minX) {
                  vehicle.setY(vehicle.getY() - d);
                  vpos.minY -= d;
                  vpos.maxY -= d;
                  vch = true;
               } else {
                  d = vpos.minY - v2pos.maxY;
                  if (d > 0.0F && vpos.minX < v2pos.maxX && vpos.maxX > v2pos.minX) {
                     vehicle.setY(vehicle.getY() + d);
                     vpos.minY += d;
                     vpos.maxY += d;
                     vch = true;
                  }
               }
         }
      }

      return vch;
   }

   private boolean isGoodVehiclePos(BaseVehicle vehicle, IsoChunk chunk) {
      int chunkMinX = (PZMath.fastfloor(vehicle.getX()) - 4) / 8 - 1;
      int chunkMinY = (PZMath.fastfloor(vehicle.getY()) - 4) / 8 - 1;
      int chunkMaxX = (int)Math.ceil((vehicle.getX() + 4.0F) / 8.0F) + 1;
      int chunkMaxY = (int)Math.ceil((vehicle.getY() + 4.0F) / 8.0F) + 1;

      for (int cy = chunkMinY; cy < chunkMaxY; cy++) {
         for (int cx = chunkMinX; cx < chunkMaxX; cx++) {
            IsoChunk chunk2 = GameServer.server ? ServerMap.instance.getChunk(cx, cy) : IsoWorld.instance.currentCell.getChunkForGridSquare(cx * 8, cy * 8, 0);
            if (chunk2 != null) {
               for (int i = 0; i < chunk2.vehicles.size(); i++) {
                  BaseVehicle vehicle2 = chunk2.vehicles.get(i);
                  if (PZMath.fastfloor(vehicle2.getZ()) == PZMath.fastfloor(vehicle.getZ()) && vehicle.testCollisionWithVehicle(vehicle2)) {
                     return false;
                  }
               }
            }
         }
      }

      return true;
   }

   private void AddVehicles_ForTest(Zone zone) {
      int stallWid = 6;
      int stallLen = 5;
      int yOffset = zone.y - this.wy * 8 + 3;

      while (yOffset < 0) {
         yOffset += 6;
      }

      int xOffset = zone.x - this.wx * 8 + 2;

      while (xOffset < 0) {
         xOffset += 5;
      }

      for (int y = yOffset; y < 8 && this.wy * 8 + y < zone.y + zone.h; y += 6) {
         for (int x = xOffset; x < 8 && this.wx * 8 + x < zone.x + zone.w; x += 5) {
            IsoGridSquare sq = this.getGridSquare(x, y, 0);
            if (sq != null) {
               BaseVehicle v = new BaseVehicle(IsoWorld.instance.currentCell);
               v.setZone("Test");
               switch (addVehiclesForTestVtype) {
                  case 0:
                     v.setScriptName("Base.CarNormal");
                     break;
                  case 1:
                     v.setScriptName("Base.SmallCar");
                     break;
                  case 2:
                     v.setScriptName("Base.SmallCar02");
                     break;
                  case 3:
                     v.setScriptName("Base.CarTaxi");
                     break;
                  case 4:
                     v.setScriptName("Base.CarTaxi2");
                     break;
                  case 5:
                     v.setScriptName("Base.PickUpTruck");
                     break;
                  case 6:
                     v.setScriptName("Base.PickUpVan");
                     break;
                  case 7:
                     v.setScriptName("Base.CarStationWagon");
                     break;
                  case 8:
                     v.setScriptName("Base.CarStationWagon2");
                     break;
                  case 9:
                     v.setScriptName("Base.VanSeats");
                     break;
                  case 10:
                     v.setScriptName("Base.Van");
                     break;
                  case 11:
                     v.setScriptName("Base.StepVan");
                     break;
                  case 12:
                     v.setScriptName("Base.PickUpTruck");
                     break;
                  case 13:
                     v.setScriptName("Base.PickUpVan");
                     break;
                  case 14:
                     v.setScriptName("Base.CarStationWagon");
                     break;
                  case 15:
                     v.setScriptName("Base.CarStationWagon2");
                     break;
                  case 16:
                     v.setScriptName("Base.VanSeats");
                     break;
                  case 17:
                     v.setScriptName("Base.Van");
                     break;
                  case 18:
                     v.setScriptName("Base.StepVan");
                     break;
                  case 19:
                     v.setScriptName("Base.SUV");
                     break;
                  case 20:
                     v.setScriptName("Base.OffRoad");
                     break;
                  case 21:
                     v.setScriptName("Base.ModernCar");
                     break;
                  case 22:
                     v.setScriptName("Base.ModernCar02");
                     break;
                  case 23:
                     v.setScriptName("Base.CarLuxury");
                     break;
                  case 24:
                     v.setScriptName("Base.SportsCar");
                     break;
                  case 25:
                     v.setScriptName("Base.PickUpVanLightsPolice");
                     break;
                  case 26:
                     v.setScriptName("Base.CarLightsPolice");
                     break;
                  case 27:
                     v.setScriptName("Base.PickUpVanLightsFire");
                     break;
                  case 28:
                     v.setScriptName("Base.PickUpTruckLightsFire");
                     break;
                  case 29:
                     v.setScriptName("Base.PickUpVanLightsFossoil");
                     break;
                  case 30:
                     v.setScriptName("Base.PickUpTruckLightsFossoil");
                     break;
                  case 31:
                     v.setScriptName("Base.CarLightsRanger");
                     break;
                  case 32:
                     v.setScriptName("Base.StepVanMail");
                     break;
                  case 33:
                     v.setScriptName("Base.VanSpiffo");
                     break;
                  case 34:
                     v.setScriptName("Base.VanAmbulance");
                     break;
                  case 35:
                     v.setScriptName("Base.VanRadio");
                     break;
                  case 36:
                     v.setScriptName("Base.PickupBurnt");
                     break;
                  case 37:
                     v.setScriptName("Base.CarNormalBurnt");
                     break;
                  case 38:
                     v.setScriptName("Base.TaxiBurnt");
                     break;
                  case 39:
                     v.setScriptName("Base.ModernCarBurnt");
                     break;
                  case 40:
                     v.setScriptName("Base.ModernCar02Burnt");
                     break;
                  case 41:
                     v.setScriptName("Base.SportsCarBurnt");
                     break;
                  case 42:
                     v.setScriptName("Base.SmallCarBurnt");
                     break;
                  case 43:
                     v.setScriptName("Base.SmallCar02Burnt");
                     break;
                  case 44:
                     v.setScriptName("Base.VanSeatsBurnt");
                     break;
                  case 45:
                     v.setScriptName("Base.VanBurnt");
                     break;
                  case 46:
                     v.setScriptName("Base.SUVBurnt");
                     break;
                  case 47:
                     v.setScriptName("Base.OffRoadBurnt");
                     break;
                  case 48:
                     v.setScriptName("Base.PickUpVanLightsBurnt");
                     break;
                  case 49:
                     v.setScriptName("Base.AmbulanceBurnt");
                     break;
                  case 50:
                     v.setScriptName("Base.VanRadioBurnt");
                     break;
                  case 51:
                     v.setScriptName("Base.PickupSpecialBurnt");
                     break;
                  case 52:
                     v.setScriptName("Base.NormalCarBurntPolice");
                     break;
                  case 53:
                     v.setScriptName("Base.LuxuryCarBurnt");
                     break;
                  case 54:
                     v.setScriptName("Base.PickUpVanBurnt");
                     break;
                  case 55:
                     v.setScriptName("Base.PickUpTruckMccoy");
                     break;
                  case 56:
                     v.setScriptName("Base.PickUpTruckLightsRanger");
                     break;
                  case 57:
                     v.setScriptName("Base.PickUpVanLightsRanger");
               }

               v.setDir(IsoDirections.W);
               double angle = (v.getDir().toAngle() + (float) Math.PI) % (Math.PI * 2);
               v.savedRot.setAngleAxis(angle, 0.0, 1.0, 0.0);
               if (addVehiclesForTestVrot == 1) {
                  v.savedRot.setAngleAxis(Math.PI / 2, 0.0, 0.0, 1.0);
               }

               if (addVehiclesForTestVrot == 2) {
                  v.savedRot.setAngleAxis(Math.PI, 0.0, 0.0, 1.0);
               }

               v.jniTransform.setRotation(v.savedRot);
               v.setX(sq.x);
               v.setY(sq.y + 3.0F - 3.0F);
               v.setZ(sq.z);
               v.jniTransform.origin.set(v.getX() - WorldSimulation.instance.offsetX, v.getZ(), v.getY() - WorldSimulation.instance.offsetY);
               v.setScript();
               this.checkVehiclePos(v, this);
               this.vehicles.add(v);
               v.setSkinIndex(addVehiclesForTestVskin);
               addVehiclesForTestVrot++;
               if (addVehiclesForTestVrot >= 2) {
                  addVehiclesForTestVrot = 0;
                  addVehiclesForTestVskin++;
                  if (addVehiclesForTestVskin >= v.getSkinCount()) {
                     addVehiclesForTestVtype = (addVehiclesForTestVtype + 1) % 56;
                     addVehiclesForTestVskin = 0;
                  }
               }
            }
         }
      }
   }

   private void AddVehicles_OnZone(VehicleZone zone, String zoneName) {
      IsoDirections dir = IsoDirections.N;
      int stallWid = 3;
      int stallLen = 4;
      if ((zone.w == stallLen || zone.w == stallLen + 1 || zone.w == stallLen + 2) && (zone.h <= stallWid || zone.h >= stallLen + 2)) {
         dir = IsoDirections.W;
      }

      int var23 = 5;
      if (zone.dir != null) {
         dir = zone.dir;
      }

      if (dir != IsoDirections.N && dir != IsoDirections.S) {
         var23 = 3;
         stallWid = 5;
      }

      int chunksPerWidth = 8;
      float yOffset = zone.y - this.wy * 8 + var23 / 2.0F;

      while (yOffset < 0.0F) {
         yOffset += var23;
      }

      float xOffset = zone.x - this.wx * 8 + stallWid / 2.0F;

      while (xOffset < 0.0F) {
         xOffset += stallWid;
      }

      // pzopt: Vineflower rendered the row loop as a while ending in an unconditional break (only the first
      // pzopt: row of stalls per chunk ever spawned); the bytecode loops every row, y += stallLen
      rows:
      for (float y = yOffset; y < 8.0F && this.wy * 8 + y < zone.y + zone.h; y += var23) { // pzopt: restored row loop
         for (float x = xOffset; x < 8.0F && this.wx * 8 + x < zone.x + zone.w; x += stallWid) {
            IsoGridSquare sq = this.getGridSquare(PZMath.fastfloor(x), PZMath.fastfloor(y), 0);
            if (sq != null) {
               VehicleType type = VehicleType.getRandomVehicleType(zoneName);
               if (type == null) {
                  System.out.println("Can't find car: " + zoneName);
                  continue rows; // pzopt: stock `continue block16` (next row)
               }

               int chance = type.spawnRate;

               chance = switch (SandboxOptions.instance.carSpawnRate.getValue()) {
                  case 2 -> (int)Math.ceil(chance / 10.0F);
                  case 3 -> (int)Math.ceil(chance / 1.5F);
                  case 5 -> chance * 2; // pzopt: Vineflower dropped the operand (was `2`, a flat 2 % on High)
                  default -> chance;
               };
               if (SystemDisabler.doVehiclesEverywhere || DebugOptions.instance.vehicleSpawnEverywhere.getValue() || type.forceSpawn) {
                  chance = 100;
               }

               if (Rand.Next(100) <= chance) {
                  BaseVehicle v = new BaseVehicle(IsoWorld.instance.currentCell);
                  v.setZone(zoneName);
                  v.setVehicleType(type.name);
                  if (type.isSpecialCar) {
                     v.setDoColor(false);
                  }

                  if (!this.RandomizeModel(v, zone, zoneName, type)) {
                     System.out.println("Problem with Vehicle spawning: " + zoneName + " " + type);
                     return;
                  }

                  int alarmChance = 15;
                  switch (SandboxOptions.instance.carAlarm.getValue()) {
                     case 1:
                        alarmChance = -1;
                        break;
                     case 2:
                        alarmChance = 3;
                        break;
                     case 3:
                        alarmChance = 8;
                     case 4:
                     default:
                        break;
                     case 5:
                        alarmChance = 25;
                        break;
                     case 6:
                        alarmChance = 50;
                  }

                  boolean wrecked = v.getScriptName().toLowerCase().contains("burnt") || v.getScriptName().toLowerCase().contains("smashed");
                  if (Rand.Next(100) < alarmChance && !wrecked) {
                     v.setAlarmed(true);
                  }

                  if (zone.isFaceDirection()) {
                     v.setDir(dir);
                  } else if (dir != IsoDirections.N && dir != IsoDirections.S) {
                     v.setDir(Rand.Next(2) == 0 ? IsoDirections.W : IsoDirections.E);
                  } else {
                     v.setDir(Rand.Next(2) == 0 ? IsoDirections.N : IsoDirections.S);
                  }

                  float angle = v.getDir().toAngle() + (float) Math.PI;

                  while (angle > Math.PI * 2) {
                     angle = (float)(angle - (Math.PI * 2));
                  }

                  if (type.randomAngle) {
                     angle = Rand.Next(0.0F, (float) (Math.PI * 2));
                  }

                  v.savedRot.setAngleAxis(angle, 0.0F, 1.0F, 0.0F);
                  v.jniTransform.setRotation(v.savedRot);
                  float vehicleLength = v.getScript().getExtents().z;
                  float distFromFront = 0.5F;
                  float vx = sq.x + 0.5F;
                  float vy = sq.y + 0.5F;
                  if (dir == IsoDirections.N) {
                     vx = sq.x + stallWid / 2.0F - (int)(stallWid / 2.0F);
                     vy = zone.y + vehicleLength / 2.0F + 0.5F;
                     if (vy >= sq.y + 1 && PZMath.fastfloor(y) < 7 && this.getGridSquare(PZMath.fastfloor(x), PZMath.fastfloor(y) + 1, 0) != null) {
                        sq = this.getGridSquare(PZMath.fastfloor(x), PZMath.fastfloor(y) + 1, 0);
                     }
                  } else if (dir == IsoDirections.S) {
                     vx = sq.x + stallWid / 2.0F - (int)(stallWid / 2.0F);
                     vy = zone.y + zone.h - vehicleLength / 2.0F - 0.5F;
                     if (vy < sq.y && PZMath.fastfloor(y) > 0 && this.getGridSquare(PZMath.fastfloor(x), PZMath.fastfloor(y) - 1, 0) != null) {
                        sq = this.getGridSquare(PZMath.fastfloor(x), PZMath.fastfloor(y) - 1, 0);
                     }
                  } else if (dir == IsoDirections.W) {
                     vx = zone.x + vehicleLength / 2.0F + 0.5F;
                     vy = sq.y + var23 / 2.0F - (int)(var23 / 2.0F);
                     if (vx >= sq.x + 1 && PZMath.fastfloor(x) < 7 && this.getGridSquare(PZMath.fastfloor(x) + 1, PZMath.fastfloor(y), 0) != null) {
                        sq = this.getGridSquare(PZMath.fastfloor(x) + 1, PZMath.fastfloor(y), 0);
                     }
                  } else if (dir == IsoDirections.E) {
                     vx = zone.x + zone.w - vehicleLength / 2.0F - 0.5F;
                     vy = sq.y + var23 / 2.0F - (int)(var23 / 2.0F);
                     if (vx < sq.x && PZMath.fastfloor(x) > 0 && this.getGridSquare(PZMath.fastfloor(x) - 1, PZMath.fastfloor(y), 0) != null) {
                        sq = this.getGridSquare(PZMath.fastfloor(x) - 1, PZMath.fastfloor(y), 0);
                     }
                  }

                  if (vx < sq.x + 0.005F) {
                     vx = sq.x + 0.005F;
                  }

                  if (vx > sq.x + 1 - 0.005F) {
                     vx = sq.x + 1 - 0.005F;
                  }

                  if (vy < sq.y + 0.005F) {
                     vy = sq.y + 0.005F;
                  }

                  if (vy > sq.y + 1 - 0.005F) {
                     vy = sq.y + 1 - 0.005F;
                  }

                  v.setX(vx);
                  v.setY(vy);
                  v.setZ(sq.z);
                  v.jniTransform.origin.set(v.getX() - WorldSimulation.instance.offsetX, v.getZ(), v.getY() - WorldSimulation.instance.offsetY);
                  float rustChance = 100.0F - Math.min(type.baseVehicleQuality * 120.0F, 100.0F);
                  v.rust = Rand.Next(100) < rustChance ? 1.0F : 0.0F;
                  if (doSpawnedVehiclesInInvalidPosition(v) || GameClient.client) {
                     this.vehicles.add(v);
                  }

                  if (type.chanceOfOverCar > 0 && Rand.Next(100) <= type.chanceOfOverCar) {
                     this.spawnVehicleRandomAngle(sq, zone, zoneName);
                  }
               }
            }
         }
      }
   }

   private void AddVehicles_OnZonePolyline(VehicleZone zone, String zoneName) {
      int stallLen = 5;
      Vector2 vector2 = new Vector2();

      for (int i = 0; i < zone.points.size() - 2; i += 2) {
         int x1 = zone.points.getQuick(i);
         int y1 = zone.points.getQuick(i + 1);
         int x2 = zone.points.getQuick((i + 2) % zone.points.size());
         int y2 = zone.points.getQuick((i + 3) % zone.points.size());
         vector2.set(x2 - x1, y2 - y1);

         for (float d = 2.5F; d < vector2.getLength(); d += 5.0F) {
            float vx = x1 + vector2.x / vector2.getLength() * d;
            float vy = y1 + vector2.y / vector2.getLength() * d;
            if (vx >= this.wx * 8 && vy >= this.wy * 8 && vx < (this.wx + 1) * 8 && vy < (this.wy + 1) * 8) {
               VehicleType type = VehicleType.getRandomVehicleType(zoneName);
               if (type == null) {
                  System.out.println("Can't find car: " + zoneName);
                  return;
               }

               BaseVehicle v = new BaseVehicle(IsoWorld.instance.currentCell);
               v.setZone(zoneName);
               v.setVehicleType(type.name);
               if (type.isSpecialCar) {
                  v.setDoColor(false);
               }

               if (!this.RandomizeModel(v, zone, zoneName, type)) {
                  System.out.println("Problem with Vehicle spawning: " + zoneName + " " + type);
                  return;
               }

               int alarmChance = 15;
               switch (SandboxOptions.instance.carAlarm.getValue()) {
                  case 1:
                     alarmChance = -1;
                     break;
                  case 2:
                     alarmChance = 3;
                     break;
                  case 3:
                     alarmChance = 8;
                  case 4:
                  default:
                     break;
                  case 5:
                     alarmChance = 25;
                     break;
                  case 6:
                     alarmChance = 50;
               }

               if (Rand.Next(100) < alarmChance) {
                  v.setAlarmed(true);
               }

               float oldx = vector2.x;
               float oldy = vector2.y;
               vector2.normalize();
               v.setDir(IsoDirections.fromAngle(vector2));
               float angle = vector2.getDirectionNeg() + 0.0F;

               while (angle > Math.PI * 2) {
                  angle = (float)(angle - (Math.PI * 2));
               }

               vector2.x = oldx;
               vector2.y = oldy;
               if (type.randomAngle) {
                  angle = Rand.Next(0.0F, (float) (Math.PI * 2));
               }

               v.savedRot.setAngleAxis(angle, 0.0F, 1.0F, 0.0F);
               v.jniTransform.setRotation(v.savedRot);
               IsoGridSquare sq = this.getGridSquare(PZMath.fastfloor(vx) - this.wx * 8, PZMath.fastfloor(vy) - this.wy * 8, 0);
               if (vx < sq.x + 0.005F) {
                  vx = sq.x + 0.005F;
               }

               if (vx > sq.x + 1 - 0.005F) {
                  vx = sq.x + 1 - 0.005F;
               }

               if (vy < sq.y + 0.005F) {
                  vy = sq.y + 0.005F;
               }

               if (vy > sq.y + 1 - 0.005F) {
                  vy = sq.y + 1 - 0.005F;
               }

               v.setX(vx);
               v.setY(vy);
               v.setZ(sq.z);
               v.jniTransform.origin.set(v.getX() - WorldSimulation.instance.offsetX, v.getZ(), v.getY() - WorldSimulation.instance.offsetY);
               float rustChance = 100.0F - Math.min(type.baseVehicleQuality * 120.0F, 100.0F);
               v.rust = Rand.Next(100) < rustChance ? 1.0F : 0.0F;
               if (doSpawnedVehiclesInInvalidPosition(v) || GameClient.client) {
                  this.vehicles.add(v);
               }
            }
         }
      }
   }

   public static void removeFromCheckedVehicles(BaseVehicle v) {
      BaseVehicleCheckedVehicles.remove(v);
   }

   public static void addFromCheckedVehicles(BaseVehicle v) {
      if (!BaseVehicleCheckedVehicles.contains(v)) {
         BaseVehicleCheckedVehicles.add(v);
      }
   }

   public static void Reset() {
      BaseVehicleCheckedVehicles.clear();
   }

   public static boolean doSpawnedVehiclesInInvalidPosition(BaseVehicle v) {
      int x = PZMath.fastfloor(v.getX());
      int y = PZMath.fastfloor(v.getY());
      int z = PZMath.fastfloor(v.getZ());
      IsoGridSquare sq = null;
      if (GameServer.server) {
         sq = ServerMap.instance.getGridSquare(x, y, z);
      } else if (!GameClient.client) {
         sq = IsoWorld.instance.currentCell.getGridSquare(x, y, z);
      }

      if (sq == null) {
         return false;
      }

      VehicleZone vehicleZone = IsoWorld.instance.metaGrid.getVehicleZoneAt(x, y, z);
      if (vehicleZone == null && !sq.isOutside()) {
         return false;
      }

      for (int i = 0; i < BaseVehicleCheckedVehicles.size(); i++) {
         if (BaseVehicleCheckedVehicles.get(i).testCollisionWithVehicle(v)) {
            return false;
         }
      }

      addFromCheckedVehicles(v);
      return true;
   }

   private void spawnVehicleRandomAngle(IsoGridSquare sq, Zone zone, String zoneName) {
      boolean north = true;
      int stallWid = 3;
      int stallLen = 4;
      if ((zone.w == stallLen || zone.w == stallLen + 1 || zone.w == stallLen + 2) && (zone.h <= stallWid || zone.h >= stallLen + 2)) {
         north = false;
      }

      int var10 = 5;
      if (!north) {
         var10 = 3;
         stallWid = 5;
      }

      VehicleType type = VehicleType.getRandomVehicleType(zoneName);
      if (type == null) {
         System.out.println("Can't find car: " + zoneName);
      } else {
         BaseVehicle v = new BaseVehicle(IsoWorld.instance.currentCell);
         v.setZone(zoneName);
         if (this.RandomizeModel(v, zone, zoneName, type)) {
            if (north) {
               v.setDir(Rand.Next(2) == 0 ? IsoDirections.N : IsoDirections.S);
            } else {
               v.setDir(Rand.Next(2) == 0 ? IsoDirections.W : IsoDirections.E);
            }

            float angle = Rand.Next(0.0F, (float) (Math.PI * 2));
            v.savedRot.setAngleAxis(angle, 0.0F, 1.0F, 0.0F);
            v.jniTransform.setRotation(v.savedRot);
            if (north) {
               v.setX(sq.x + stallWid / 2.0F - (int)(stallWid / 2.0F));
               v.setY(sq.y);
            } else {
               v.setX(sq.x);
               v.setY(sq.y + var10 / 2.0F - (int)(var10 / 2.0F));
            }

            v.setZ(sq.z);
            v.jniTransform.origin.set(v.getX() - WorldSimulation.instance.offsetX, v.getZ(), v.getY() - WorldSimulation.instance.offsetY);
            if (doSpawnedVehiclesInInvalidPosition(v) || GameClient.client) {
               this.vehicles.add(v);
            }
         }
      }
   }

   public boolean RandomizeModel(BaseVehicle v, Zone zone, String name, VehicleType type) {
      if (type.vehiclesDefinition.isEmpty()) {
         System.out.println("no vehicle definition found for " + name);
         return false;
      }

      float rand = Rand.Next(0.0F, 100.0F);
      float currentIndex = 0.0F;
      VehicleTypeDefinition vehicleDefinition = null;

      for (int i = 0; i < type.vehiclesDefinition.size(); i++) {
         vehicleDefinition = (VehicleTypeDefinition)type.vehiclesDefinition.get(i);
         currentIndex += vehicleDefinition.spawnChance;
         if (rand < currentIndex) {
            break;
         }
      }

      String scriptName = vehicleDefinition.vehicleType;
      VehicleScript script = ScriptManager.instance.getVehicle(scriptName);
      if (script == null) {
         DebugLog.log("no such vehicle script \"" + scriptName + "\" in IsoChunk.RandomizeModel");
         return false;
      }

      int index = vehicleDefinition.index;
      v.setScriptName(scriptName);
      v.setScript();

      try {
         if (index > -1) {
            v.setSkinIndex(index);
         } else {
            v.setSkinIndex(Rand.Next(v.getSkinCount()));
         }

         return true;
      } catch (Exception e) {
         DebugType.General.printException(e, LogSeverity.Error, "Problem with %s", new Object[]{v.getScriptName()});
         return false;
      }
   }

   private void AddVehicles_TrafficJam_W(Zone zone, String zoneName) {
      int stallWid = 3;
      int stallLen = 6;
      int yOffset = zone.y - this.wy * 8 + 1;

      while (yOffset < 0) {
         yOffset += 3;
      }

      int xOffset = zone.x - this.wx * 8 + 3;

      while (xOffset < 0) {
         xOffset += 6;
      }

      for (int y = yOffset; y < 8 && this.wy * 8 + y < zone.y + zone.h; y += 3 + Rand.Next(1)) {
         for (int x = xOffset; x < 8 && this.wx * 8 + x < zone.x + zone.w; x += 6 + Rand.Next(1)) {
            IsoGridSquare sq = this.getGridSquare(x, y, 0);
            if (sq != null) {
               VehicleType type = VehicleType.getRandomVehicleType(zoneName);
               if (type == null) {
                  System.out.println("Can't find car: " + zoneName);
                  break;
               }

               int chance = 80;
               if (SystemDisabler.doVehiclesEverywhere || DebugOptions.instance.vehicleSpawnEverywhere.getValue()) {
                  chance = 100;
               }

               if (Rand.Next(100) <= chance) {
                  BaseVehicle v = new BaseVehicle(IsoWorld.instance.currentCell);
                  v.setZone("TrafficJam");
                  v.setVehicleType(type.name);
                  if (!this.RandomizeModel(v, zone, zoneName, type)) {
                     return;
                  }

                  v.setScript();
                  v.setX(sq.x + Rand.Next(0.0F, 1.0F));
                  v.setY(sq.y + Rand.Next(0.0F, 1.0F));
                  v.setZ(sq.z);
                  v.jniTransform.origin.set(v.getX() - WorldSimulation.instance.offsetX, v.getZ(), v.getY() - WorldSimulation.instance.offsetY);
                  if (this.isGoodVehiclePos(v, this)) {
                     v.setSkinIndex(Rand.Next(v.getSkinCount() - 1));
                     v.setDir(IsoDirections.W);
                     float dist = Math.abs(zone.x + zone.w - sq.x);
                     dist /= 20.0F;
                     dist = Math.min(2.0F, dist);
                     float angle = v.getDir().toAngle() + (float) Math.PI - 0.25F + Rand.Next(0.0F, dist);

                     while (angle > Math.PI * 2) {
                        angle = (float)(angle - (Math.PI * 2));
                     }

                     v.savedRot.setAngleAxis(angle, 0.0F, 1.0F, 0.0F);
                     v.jniTransform.setRotation(v.savedRot);
                     if (doSpawnedVehiclesInInvalidPosition(v) || GameClient.client) {
                        this.vehicles.add(v);
                     }
                  }
               }
            }
         }
      }
   }

   private void AddVehicles_TrafficJam_E(Zone zone, String zoneName) {
      int stallWid = 3;
      int stallLen = 6;
      int yOffset = zone.y - this.wy * 8 + 1;

      while (yOffset < 0) {
         yOffset += 3;
      }

      int xOffset = zone.x - this.wx * 8 + 3;

      while (xOffset < 0) {
         xOffset += 6;
      }

      for (int y = yOffset; y < 8 && this.wy * 8 + y < zone.y + zone.h; y += 3 + Rand.Next(1)) {
         for (int x = xOffset; x < 8 && this.wx * 8 + x < zone.x + zone.w; x += 6 + Rand.Next(1)) {
            IsoGridSquare sq = this.getGridSquare(x, y, 0);
            if (sq != null) {
               VehicleType type = VehicleType.getRandomVehicleType(zoneName);
               if (type == null) {
                  System.out.println("Can't find car: " + zoneName);
                  break;
               }

               int chance = 80;
               if (SystemDisabler.doVehiclesEverywhere || DebugOptions.instance.vehicleSpawnEverywhere.getValue()) {
                  chance = 100;
               }

               if (Rand.Next(100) <= chance) {
                  BaseVehicle v = new BaseVehicle(IsoWorld.instance.currentCell);
                  v.setZone("TrafficJam");
                  v.setVehicleType(type.name);
                  if (!this.RandomizeModel(v, zone, zoneName, type)) {
                     return;
                  }

                  v.setScript();
                  v.setX(sq.x + Rand.Next(0.0F, 1.0F));
                  v.setY(sq.y + Rand.Next(0.0F, 1.0F));
                  v.setZ(sq.z);
                  v.jniTransform.origin.set(v.getX() - WorldSimulation.instance.offsetX, v.getZ(), v.getY() - WorldSimulation.instance.offsetY);
                  if (this.isGoodVehiclePos(v, this)) {
                     v.setSkinIndex(Rand.Next(v.getSkinCount() - 1));
                     v.setDir(IsoDirections.E);
                     float dist = Math.abs(zone.x + zone.w - sq.x - zone.w);
                     dist /= 20.0F;
                     dist = Math.min(2.0F, dist);
                     float angle = v.getDir().toAngle() + (float) Math.PI - 0.25F + Rand.Next(0.0F, dist);

                     while (angle > Math.PI * 2) {
                        angle = (float)(angle - (Math.PI * 2));
                     }

                     v.savedRot.setAngleAxis(angle, 0.0F, 1.0F, 0.0F);
                     v.jniTransform.setRotation(v.savedRot);
                     if (doSpawnedVehiclesInInvalidPosition(v) || GameClient.client) {
                        this.vehicles.add(v);
                     }
                  }
               }
            }
         }
      }
   }

   private void AddVehicles_TrafficJam_S(Zone zone, String zoneName) {
      int stallWid = 3;
      int stallLen = 6;
      int yOffset = zone.y - this.wy * 8 + 3;

      while (yOffset < 0) {
         yOffset += 6;
      }

      int xOffset = zone.x - this.wx * 8 + 1;

      while (xOffset < 0) {
         xOffset += 3;
      }

      for (int y = yOffset; y < 8 && this.wy * 8 + y < zone.y + zone.h; y += 6 + Rand.Next(-1, 1)) {
         for (int x = xOffset; x < 8 && this.wx * 8 + x < zone.x + zone.w; x += 3 + Rand.Next(1)) {
            IsoGridSquare sq = this.getGridSquare(x, y, 0);
            if (sq != null) {
               VehicleType type = VehicleType.getRandomVehicleType(zoneName);
               if (type == null) {
                  System.out.println("Can't find car: " + zoneName);
                  break;
               }

               int chance = 80;
               if (SystemDisabler.doVehiclesEverywhere || DebugOptions.instance.vehicleSpawnEverywhere.getValue()) {
                  chance = 100;
               }

               if (Rand.Next(100) <= chance) {
                  BaseVehicle v = new BaseVehicle(IsoWorld.instance.currentCell);
                  v.setZone("TrafficJam");
                  v.setVehicleType(type.name);
                  if (!this.RandomizeModel(v, zone, zoneName, type)) {
                     return;
                  }

                  v.setScript();
                  v.setX(sq.x + Rand.Next(0.0F, 1.0F));
                  v.setY(sq.y + Rand.Next(0.0F, 1.0F));
                  v.setZ(sq.z);
                  v.jniTransform.origin.set(v.getX() - WorldSimulation.instance.offsetX, v.getZ(), v.getY() - WorldSimulation.instance.offsetY);
                  if (this.isGoodVehiclePos(v, this)) {
                     v.setSkinIndex(Rand.Next(v.getSkinCount() - 1));
                     v.setDir(IsoDirections.S);
                     float dist = Math.abs(zone.y + zone.h - sq.y - zone.h);
                     dist /= 20.0F;
                     dist = Math.min(2.0F, dist);
                     float angle = v.getDir().toAngle() + (float) Math.PI - 0.25F + Rand.Next(0.0F, dist);

                     while (angle > Math.PI * 2) {
                        angle = (float)(angle - (Math.PI * 2));
                     }

                     v.savedRot.setAngleAxis(angle, 0.0F, 1.0F, 0.0F);
                     v.jniTransform.setRotation(v.savedRot);
                     if (doSpawnedVehiclesInInvalidPosition(v) || GameClient.client) {
                        this.vehicles.add(v);
                     }
                  }
               }
            }
         }
      }
   }

   private void AddVehicles_TrafficJam_N(Zone zone, String zoneName) {
      int stallWid = 3;
      int stallLen = 6;
      int yOffset = zone.y - this.wy * 8 + 3;

      while (yOffset < 0) {
         yOffset += 6;
      }

      int xOffset = zone.x - this.wx * 8 + 1;

      while (xOffset < 0) {
         xOffset += 3;
      }

      for (int y = yOffset; y < 8 && this.wy * 8 + y < zone.y + zone.h; y += 6 + Rand.Next(-1, 1)) {
         for (int x = xOffset; x < 8 && this.wx * 8 + x < zone.x + zone.w; x += 3 + Rand.Next(1)) {
            IsoGridSquare sq = this.getGridSquare(x, y, 0);
            if (sq != null) {
               VehicleType type = VehicleType.getRandomVehicleType(zoneName);
               if (type == null) {
                  System.out.println("Can't find car: " + zoneName);
                  break;
               }

               int chance = 80;
               if (SystemDisabler.doVehiclesEverywhere || DebugOptions.instance.vehicleSpawnEverywhere.getValue()) {
                  chance = 100;
               }

               if (Rand.Next(100) <= chance) {
                  BaseVehicle v = new BaseVehicle(IsoWorld.instance.currentCell);
                  v.setZone("TrafficJam");
                  v.setVehicleType(type.name);
                  if (!this.RandomizeModel(v, zone, zoneName, type)) {
                     return;
                  }

                  v.setScript();
                  v.setX(sq.x + Rand.Next(0.0F, 1.0F));
                  v.setY(sq.y + Rand.Next(0.0F, 1.0F));
                  v.setZ(sq.z);
                  v.jniTransform.origin.set(v.getX() - WorldSimulation.instance.offsetX, v.getZ(), v.getY() - WorldSimulation.instance.offsetY);
                  if (this.isGoodVehiclePos(v, this)) {
                     v.setSkinIndex(Rand.Next(v.getSkinCount() - 1));
                     v.setDir(IsoDirections.N);
                     float dist = Math.abs(zone.y + zone.h - sq.y);
                     dist /= 20.0F;
                     dist = Math.min(2.0F, dist);
                     float angle = v.getDir().toAngle() + (float) Math.PI - 0.25F + Rand.Next(0.0F, dist);

                     while (angle > Math.PI * 2) {
                        angle = (float)(angle - (Math.PI * 2));
                     }

                     v.savedRot.setAngleAxis(angle, 0.0F, 1.0F, 0.0F);
                     v.jniTransform.setRotation(v.savedRot);
                     if (doSpawnedVehiclesInInvalidPosition(v) || GameClient.client) {
                        this.vehicles.add(v);
                     }
                  }
               }
            }
         }
      }
   }

   private void AddVehicles_TrafficJam_Polyline(Zone zone, String zoneName) {
      int stallWid = 3;
      int stallLen = 6;
      float perpOffset = 2.0F;
      Vector2 vector2 = new Vector2();
      Vector2 perp = new Vector2();
      float distanceToSegmentStart = 0.0F;
      float zoneLength = zone.getPolylineLength();

      for (int i = 0; i < zone.points.size() - 2; i += 2) {
         int x1 = zone.points.getQuick(i);
         int y1 = zone.points.getQuick(i + 1);
         int x2 = zone.points.getQuick(i + 2);
         int y2 = zone.points.getQuick(i + 3);
         vector2.set(x2 - x1, y2 - y1);
         float len = vector2.getLength();
         perp.set(vector2);
         perp.tangent();
         perp.normalize();
         float distanceToSpawnPoint = distanceToSegmentStart;
         distanceToSegmentStart += len;

         for (float d = 3.0F; d <= len - 3.0F; d += 6 + Rand.Next(-1, 1)) {
            float offset = PZMath.clamp(d + Rand.Next(-1.0F, 1.0F), 3.0F, len - 3.0F);
            float localPerpOffset = Rand.Next(-1.0F, 1.0F);
            float spawnX = x1 + vector2.x / len * offset + perp.x * localPerpOffset;
            float spawnY = y1 + vector2.y / len * offset + perp.y * localPerpOffset;
            this.TryAddVehicle_TrafficJam(zone, zoneName, spawnX, spawnY, vector2, distanceToSpawnPoint + offset, zoneLength);

            for (float dp = 2.0F; dp + 1.5F <= zone.polylineWidth / 2.0F; dp += 2.0F) {
               localPerpOffset = dp + Rand.Next(-1.0F, 1.0F);
               if (localPerpOffset + 1.5F <= zone.polylineWidth / 2.0F) {
                  offset = PZMath.clamp(d + Rand.Next(-2.0F, 2.0F), 3.0F, len - 3.0F);
                  spawnX = x1 + vector2.x / len * offset + perp.x * localPerpOffset;
                  spawnY = y1 + vector2.y / len * offset + perp.y * localPerpOffset;
                  this.TryAddVehicle_TrafficJam(zone, zoneName, spawnX, spawnY, vector2, distanceToSpawnPoint + offset, zoneLength);
               }

               localPerpOffset = dp + Rand.Next(-1.0F, 1.0F);
               if (localPerpOffset + 1.5F <= zone.polylineWidth / 2.0F) {
                  offset = PZMath.clamp(d + Rand.Next(-2.0F, 2.0F), 3.0F, len - 3.0F);
                  spawnX = x1 + vector2.x / len * offset - perp.x * localPerpOffset;
                  spawnY = y1 + vector2.y / len * offset - perp.y * localPerpOffset;
                  this.TryAddVehicle_TrafficJam(zone, zoneName, spawnX, spawnY, vector2, distanceToSpawnPoint + offset, zoneLength);
               }
            }
         }
      }
   }

   private void TryAddVehicle_TrafficJam(Zone zone, String zoneName, float spawnX, float spawnY, Vector2 vector2, float distanceToSpawnPoint, float zoneLength) {
      int chunksPerWidth = 8;
      if (!(spawnX < this.wx * 8) && !(spawnX >= (this.wx + 1) * 8) && !(spawnY < this.wy * 8) && !(spawnY >= (this.wy + 1) * 8)) {
         IsoGridSquare sq = this.getGridSquare(PZMath.fastfloor(spawnX) - this.wx * 8, PZMath.fastfloor(spawnY) - this.wy * 8, 0);
         if (sq != null) {
            VehicleType type = VehicleType.getRandomVehicleType(zoneName + "W");
            if (type == null) {
               System.out.println("Can't find car: " + zoneName);
            } else {
               int chance = 80;
               if (SystemDisabler.doVehiclesEverywhere || DebugOptions.instance.vehicleSpawnEverywhere.getValue()) {
                  chance = 100;
               }

               if (Rand.Next(100) <= chance) {
                  BaseVehicle v = new BaseVehicle(IsoWorld.instance.currentCell);
                  v.setZone("TrafficJam");
                  v.setVehicleType(type.name);
                  if (this.RandomizeModel(v, zone, zoneName, type)) {
                     v.setScript();
                     v.setX(spawnX);
                     v.setY(spawnY);
                     v.setZ(sq.z);
                     float oldx = vector2.x;
                     float oldy = vector2.y;
                     vector2.normalize();
                     v.setDir(IsoDirections.fromAngle(vector2));
                     float angle = vector2.getDirectionNeg();
                     vector2.set(oldx, oldy);
                     float r = 90.0F * (distanceToSpawnPoint / zoneLength);
                     angle += Rand.Next(-r, r) * (float) (Math.PI / 180.0);

                     while (angle > Math.PI * 2) {
                        angle = (float)(angle - (Math.PI * 2));
                     }

                     v.savedRot.setAngleAxis(angle, 0.0F, 1.0F, 0.0F);
                     v.jniTransform.setRotation(v.savedRot);
                     v.jniTransform.origin.set(v.getX() - WorldSimulation.instance.offsetX, v.getZ(), v.getY() - WorldSimulation.instance.offsetY);
                     if (this.isGoodVehiclePos(v, this)) {
                        v.setSkinIndex(Rand.Next(v.getSkinCount() - 1));
                        if (doSpawnedVehiclesInInvalidPosition(v)) {
                           this.vehicles.add(v);
                        }
                     }
                  }
               }
            }
         }
      }
   }

   public void AddVehicles() {
      if (SandboxOptions.instance.carSpawnRate.getValue() != 1) {
         if (VehicleType.vehicles.isEmpty()) {
            VehicleType.init();
         }

         if (!GameClient.client) {
            if (SandboxOptions.instance.enableVehicles.getValue()) {
               if (!GameServer.server) {
                  WorldSimulation.instance.create();
               }

               IsoMetaCell metaCell = IsoWorld.instance.getMetaGrid().getCellData(this.wx / 32, this.wy / 32);
               ArrayList<VehicleZone> vehicleZones = metaCell == null ? null : metaCell.vehicleZones;

               for (int i = 0; vehicleZones != null && i < vehicleZones.size(); i++) {
                  VehicleZone zone = vehicleZones.get(i);
                  if (zone.x + zone.w >= this.wx * 8 && zone.y + zone.h >= this.wy * 8 && zone.x < (this.wx + 1) * 8 && zone.y < (this.wy + 1) * 8) {
                     String name = zone.name;
                     if (name.isEmpty()) {
                        name = zone.type;
                     }

                     if (SandboxOptions.instance.trafficJam.getValue()) {
                        if (zone.isPolyline()) {
                           if ("TrafficJam".equalsIgnoreCase(name)) {
                              this.AddVehicles_TrafficJam_Polyline(zone, name);
                              continue;
                           }

                           if ("RTrafficJam".equalsIgnoreCase(name) && Rand.Next(100) < 10) {
                              this.AddVehicles_TrafficJam_Polyline(zone, name.replaceFirst("rtraffic", "traffic"));
                              continue;
                           }
                        }

                        if ("TrafficJamW".equalsIgnoreCase(name)) {
                           this.AddVehicles_TrafficJam_W(zone, name);
                        }

                        if ("TrafficJamE".equalsIgnoreCase(name)) {
                           this.AddVehicles_TrafficJam_E(zone, name);
                        }

                        if ("TrafficJamS".equalsIgnoreCase(name)) {
                           this.AddVehicles_TrafficJam_S(zone, name);
                        }

                        if ("TrafficJamN".equalsIgnoreCase(name)) {
                           this.AddVehicles_TrafficJam_N(zone, name);
                        }

                        if ("RTrafficJamW".equalsIgnoreCase(name) && Rand.Next(100) < 10) {
                           this.AddVehicles_TrafficJam_W(zone, name.replaceFirst("rtraffic", "traffic"));
                        }

                        if ("RTrafficJamE".equalsIgnoreCase(name) && Rand.Next(100) < 10) {
                           this.AddVehicles_TrafficJam_E(zone, name.replaceFirst("rtraffic", "traffic"));
                        }

                        if ("RTrafficJamS".equalsIgnoreCase(name) && Rand.Next(100) < 10) {
                           this.AddVehicles_TrafficJam_S(zone, name.replaceFirst("rtraffic", "traffic"));
                        }

                        if ("RTrafficJamN".equalsIgnoreCase(name) && Rand.Next(100) < 10) {
                           this.AddVehicles_TrafficJam_N(zone, name.replaceFirst("rtraffic", "traffic"));
                        }
                     }

                     if (!StringUtils.containsIgnoreCase(name, "TrafficJam")) {
                        if ("TestVehicles".equals(name)) {
                           this.AddVehicles_ForTest(zone);
                        } else if (VehicleType.hasTypeForZone(name)) {
                           if (zone.isPolyline()) {
                              this.AddVehicles_OnZonePolyline(zone, name);
                           } else {
                              this.AddVehicles_OnZone(zone, name);
                           }
                        }
                     }
                  }
               }

               IsoMetaChunk metaChunk = IsoWorld.instance.getMetaChunk(this.wx, this.wy);
               if (metaChunk != null) {
                  for (int i = 0; i < metaChunk.getZonesSize(); i++) {
                     Zone zone = metaChunk.getZone(i);
                     this.addRandomCarCrash(zone, false);
                  }
               }
            }
         }
      }
   }

   public void addSurvivorInHorde(boolean forced) {
      if (forced || !IsoWorld.getZombiesDisabled()) {
         IsoMetaChunk metaChunk = IsoWorld.instance.getMetaChunk(this.wx, this.wy);
         if (metaChunk != null) {
            for (int i = 0; i < metaChunk.getZonesSize(); i++) {
               Zone zone = metaChunk.getZone(i);
               if (this.canAddSurvivorInHorde(zone, forced)) {
                  int baseChance = 4;
                  float worldAgeDays = (float)GameTime.getInstance().getWorldAgeHours() / 24.0F;
                  worldAgeDays += (SandboxOptions.instance.timeSinceApo.getValue() - 1) * 30;
                  baseChance = (int)(baseChance + worldAgeDays * 0.03F);
                  baseChance = Math.min(baseChance, 15);
                  if (forced || Rand.Next(0.0F, 500.0F) < 0.4F * baseChance) {
                     this.addSurvivorInHorde(zone);
                     if (forced) {
                        break;
                     }
                  }
               }
            }
         }
      }
   }

   private boolean canAddSurvivorInHorde(Zone zone, boolean force) {
      if (!force && IsoWorld.instance.getTimeSinceLastSurvivorInHorde() > 0) {
         return false;
      } else if (!force && IsoWorld.getZombiesDisabled()) {
         return false;
      } else if (!force && zone.hourLastSeen != 0) {
         return false;
      } else {
         return !force && zone.haveConstruction ? false : "Nav".equals(zone.getType());
      }
   }

   private void addSurvivorInHorde(Zone zone) {
      zone.hourLastSeen++;
      IsoWorld.instance.setTimeSinceLastSurvivorInHorde(5000);
      int minX = Math.max(zone.x, this.wx * 8);
      int minY = Math.max(zone.y, this.wy * 8);
      int maxX = Math.min(zone.x + zone.w, (this.wx + 1) * 8);
      int maxY = Math.min(zone.y + zone.h, (this.wy + 1) * 8);
      float centerX = minX + (maxX - minX) / 2.0F;
      float centerY = minY + (maxY - minY) / 2.0F;
      VirtualZombieManager.instance.choices.clear();

      for (int i = -3; i < 3; i++) {
         for (int j = -3; j < 3; j++) {
            IsoGridSquare sq = this.getGridSquare((int)(centerX + i) - this.wx * 8, (int)(centerY + j) - this.wy * 8, 0);
            if (sq != null && sq.getBuilding() == null && !sq.isVehicleIntersecting() && sq.isGoodSquare()) {
               VirtualZombieManager.instance.choices.add(sq);
            }
         }
      }

      if (!VirtualZombieManager.instance.choices.isEmpty()) {
         int zombiesNbr = Rand.Next(15, 20);

         for (int i = 0; i < zombiesNbr; i++) {
            IsoZombie zombie = VirtualZombieManager.instance.createRealZombieAlways(IsoDirections.getRandom(), false);
            if (zombie != null) {
               zombie.dressInRandomOutfit();
               ZombieSpawnRecorder.instance.record(zombie, "addSurvivorInHorde");
            }
         }

         VirtualZombieManager.instance.choices.clear();
         IsoGridSquare sq = this.getGridSquare((int)centerX - this.wx * 8, (int)centerY - this.wy * 8, 0);
         if (sq != null && sq.getBuilding() == null && !sq.isVehicleIntersecting() && sq.isGoodSquare()) {
            VirtualZombieManager.instance.choices.add(sq);
            IsoZombie zombie = VirtualZombieManager.instance.createRealZombieAlways(IsoDirections.getRandom(), false);
            if (zombie != null) {
               ZombieSpawnRecorder.instance.record(zombie, "addSurvivorInHorde");
               zombie.setAsSurvivor();
            }
         }
      }
   }

   public boolean canAddRandomCarCrash(Zone zone, boolean force) {
      if (!force && zone.hourLastSeen != 0) {
         return false;
      } else if (!force && zone.haveConstruction) {
         return false;
      } else if (!"Nav".equals(zone.getType())) {
         return false;
      } else {
         int minX = Math.max(zone.x, this.wx * 8);
         int minY = Math.max(zone.y, this.wy * 8);
         int maxX = Math.min(zone.x + zone.w, (this.wx + 1) * 8);
         int maxY = Math.min(zone.y + zone.h, (this.wy + 1) * 8);
         if (zone.w > 30 && zone.h < 13) {
            return maxX - minX >= 10 && maxY - minY >= 5;
         } else {
            return zone.h > 30 && zone.w < 13 ? maxX - minX >= 5 && maxY - minY >= 10 : false;
         }
      }
   }

   public void addRandomCarCrash(Zone zone, boolean addToWorld) {
      if (zone != null) {
         if (this.vehicles.isEmpty()) {
            if ("Nav".equals(zone.getType())) {
               RandomizedVehicleStoryBase.doRandomStory(zone, this, false);
            }
         }
      }
   }

   public static boolean FileExists(int wx, int wy) {
      File inFile = ChunkMapFilenames.instance.getFilename(wx, wy);
      if (inFile == null) {
         inFile = ZomboidFileSystem.instance.getFileInCurrentSave(wx + File.separator + wy + ".bin");
      }

      return inFile.exists();
   }

   public void checkPhysicsLater(int level) {
      IsoChunkLevel chunkLevel = this.getLevelData(level);
      if (chunkLevel != null) {
         chunkLevel.physicsCheck = true;
      }
   }

   public void updatePhysicsForLevel(int z) {
      Bullet.beginUpdateChunk(this, z);

      for (int y = 0; y < 8; y++) {
         for (int x = 0; x < 8; x++) {
            this.calcPhysics(x, y, z, this.shapes);
            int nShapes = 0;

            for (int i = 0; i < 4; i++) {
               if (this.shapes[i] != -1) {
                  bshapes[nShapes++] = (byte)(this.shapes[i] + 1);
               }
            }

            Bullet.updateChunk(x, y, nShapes, bshapes);
         }
      }

      Bullet.endUpdateChunk();
   }

   private int addPhysicsShape(IsoGridSquare sq, int[] shapes, int count, int shape) {
      if (PZArrayUtil.contains(shapes, count, shape)) {
         return count;
      } else if (count == shapes.length) {
         DebugType.General.println("Error: Too many physics objects on gridsquare: %d,%d,%d", new Object[]{sq.x, sq.y, sq.z});
         return count;
      } else {
         shapes[count] = shape;
         return count + 1;
      }
   }

   private int addPhysicsShape(IsoGridSquare sq, int[] shapes, int count, IsoChunk.PhysicsShapes shape) {
      return this.addPhysicsShape(sq, shapes, count, shape.ordinal());
   }

   private void calcPhysics(int x, int y, int z, int[] shapes) {
      for (int i = 0; i < 4; i++) {
         shapes[i] = -1;
      }

      IsoGridSquare sq = this.getGridSquare(x, y, z);
      if (sq != null) {
         int shapeCount = 0;
         if (z == 0) {
            boolean isColumn = false;

            for (int i = 0; i < sq.getObjects().size(); i++) {
               IsoObject o = (IsoObject)sq.getObjects().get(i);
               if (!o.hasProperty("PhysicsShape")
                  && o.sprite != null
                  && o.sprite.name != null
                  && (
                     o.sprite.name.contains("lighting_outdoor_")
                        || o.sprite.name.equals("recreational_sports_01_21")
                        || o.sprite.name.equals("recreational_sports_01_19")
                        || o.sprite.name.equals("recreational_sports_01_32")
                  )
                  && (!o.getProperties().has("MoveType") || !"WallObject".equals(o.getProperties().get("MoveType")))) {
                  isColumn = true;
                  break;
               }
            }

            if (isColumn) {
               shapeCount = this.addPhysicsShape(sq, shapes, shapeCount, IsoChunk.PhysicsShapes.Tree);
            }
         }

         boolean solidThumpable = false;
         if (!sq.getSpecialObjects().isEmpty()) {
            int size = sq.getSpecialObjects().size();

            for (int i = 0; i < size; i++) {
               IsoObject obj = (IsoObject)sq.getSpecialObjects().get(i);
               if (obj instanceof IsoThumpable isoThumpable && isoThumpable.isBlockAllTheSquare()) {
                  solidThumpable = true;
                  break;
               }
            }
         }

         PropertyContainer props = sq.getProperties();
         if (sq.HasStairs()) {
            if (sq.has(IsoObjectType.stairsMN)) {
               shapeCount = this.addPhysicsShape(sq, shapes, shapeCount, IsoChunk.PhysicsShapes.StairsMiddleNorth);
            }

            if (sq.has(IsoObjectType.stairsMW)) {
               shapeCount = this.addPhysicsShape(sq, shapes, shapeCount, IsoChunk.PhysicsShapes.StairsMiddleWest);
            }
         }

         if (sq.has(IsoObjectType.isMoveAbleObject)) {
            shapeCount = this.addPhysicsShape(sq, shapes, shapeCount, IsoChunk.PhysicsShapes.Tree);
         }

         if (sq.has(IsoObjectType.tree)) {
            String tree = sq.getProperties().get("tree");
            String windType = sq.getProperties().get("WindType");
            if (tree == null) {
               shapeCount = this.addPhysicsShape(sq, shapes, shapeCount, IsoChunk.PhysicsShapes.Tree);
            }

            if (tree != null && !tree.equals("1") && (windType == null || !windType.equals("2") || !tree.equals("2") && !tree.equals("1"))) {
               shapeCount = this.addPhysicsShape(sq, shapes, shapeCount, IsoChunk.PhysicsShapes.Tree);
            }
         } else if (props.has(IsoFlagType.solid) || props.has(IsoFlagType.solidtrans) || props.has(IsoFlagType.blocksight) || sq.HasStairs() || solidThumpable) {
            if (sq.HasStairs()) {
               shapeCount = this.addPhysicsShape(sq, shapes, shapeCount, IsoChunk.PhysicsShapes.SolidStairs);
            } else {
               shapeCount = this.addPhysicsShape(sq, shapes, shapeCount, IsoChunk.PhysicsShapes.Solid);
            }
         }

         if (sq.getProperties().has(IsoFlagType.solidfloor)) {
            shapeCount = this.addPhysicsShape(sq, shapes, shapeCount, IsoChunk.PhysicsShapes.Floor);
         }

         if (!sq.getProperties().has("CarSlowFactor")) {
            if (sq.getProperties().has(IsoFlagType.DoorWallW) && sq.getProperties().has(IsoFlagType.doorW) && !props.has(IsoFlagType.open)) {
               if (this.checkForActiveRagdoll(sq)) {
                  this.delayedPhysicsShapeSet.add(sq);
               } else {
                  this.delayedPhysicsShapeSet.remove(sq);
                  shapeCount = this.addPhysicsShape(sq, shapes, shapeCount, IsoChunk.PhysicsShapes.WallW);
               }
            }

            if (props.has(IsoFlagType.collideW) || props.has(IsoFlagType.windowW)) {
               shapeCount = this.addPhysicsShape(sq, shapes, shapeCount, IsoChunk.PhysicsShapes.WallW);
            }

            if (sq.getProperties().has(IsoFlagType.DoorWallN) && sq.getProperties().has(IsoFlagType.doorN) && !props.has(IsoFlagType.open)) {
               if (this.checkForActiveRagdoll(sq)) {
                  this.delayedPhysicsShapeSet.add(sq);
               } else {
                  this.delayedPhysicsShapeSet.remove(sq);
                  shapeCount = this.addPhysicsShape(sq, shapes, shapeCount, IsoChunk.PhysicsShapes.WallN);
               }
            }

            if (props.has(IsoFlagType.collideN) || props.has(IsoFlagType.windowN)) {
               shapeCount = this.addPhysicsShape(sq, shapes, shapeCount, IsoChunk.PhysicsShapes.WallN);
            }

            if (sq.has("PhysicsShape")) {
               String shape = sq.getProperties().get("PhysicsShape");
               if ("Solid".equals(shape)) {
                  shapeCount = this.addPhysicsShape(sq, shapes, shapeCount, IsoChunk.PhysicsShapes.Solid);
               } else if ("WallN".equals(shape)) {
                  shapeCount = this.addPhysicsShape(sq, shapes, shapeCount, IsoChunk.PhysicsShapes.WallN);
               } else if ("WallW".equals(shape)) {
                  shapeCount = this.addPhysicsShape(sq, shapes, shapeCount, IsoChunk.PhysicsShapes.WallW);
               } else if ("WallS".equals(shape)) {
                  shapeCount = this.addPhysicsShape(sq, shapes, shapeCount, IsoChunk.PhysicsShapes.WallS);
               } else if ("WallE".equals(shape)) {
                  shapeCount = this.addPhysicsShape(sq, shapes, shapeCount, IsoChunk.PhysicsShapes.WallE);
               } else if ("Tree".equals(shape)) {
                  shapeCount = this.addPhysicsShape(sq, shapes, shapeCount, IsoChunk.PhysicsShapes.Tree);
               } else if ("Floor".equals(shape)) {
                  shapeCount = this.addPhysicsShape(sq, shapes, shapeCount, IsoChunk.PhysicsShapes.Floor);
               }
            }

            if (sq.has("PhysicsMesh")) {
               String shape = sq.getProperties().get("PhysicsMesh");
               if (!shape.contains(".")) {
                  shape = "Base." + shape;
               }

               Integer index = (Integer)Bullet.physicsShapeNameToIndex.getOrDefault(shape, null);
               if (index != null) {
                  shapeCount = this.addPhysicsShape(sq, shapes, shapeCount, IsoChunk.PhysicsShapes.FIRST_MESH.ordinal() + index);
               }
            }
         }
      }
   }

   public void setBlendingDoneFull(boolean flag) {
      this.blendingDoneFull = flag;
   }

   public boolean isBlendingDoneFull() {
      return this.blendingDoneFull;
   }

   public void setBlendingDonePartial(boolean flag) {
      this.blendingDonePartial = flag;
   }

   public boolean isBlendingDonePartial() {
      return this.blendingDonePartial;
   }

   public void setBlendingModified(int i) {
      this.blendingModified[i] = true;
   }

   public boolean isBlendingDone(int i) {
      return this.blendingModified[i];
   }

   public void setModifDepth(BlendDirection dir, byte depth) {
      this.blendingDepth[dir.index] = depth;
   }

   public void setModifDepth(BlendDirection dir, int depth) {
      this.setModifDepth(dir, (byte)depth);
   }

   public byte getModifDepth(BlendDirection dir) {
      return this.blendingDepth[dir.index];
   }

   public void setAttachmentsDoneFull(boolean attachmentsDoneFull) {
      this.attachmentsDoneFull = attachmentsDoneFull;
   }

   public boolean isAttachmentsDoneFull() {
      return this.attachmentsDoneFull;
   }

   public void setAttachmentsState(int i, boolean value) {
      this.attachmentsState[i] = value;
   }

   public boolean isAttachmentsDone(int i) {
      return this.attachmentsState[i];
   }

   public boolean[] getAttachmentsState() {
      return this.attachmentsState;
   }

   public void setAttachmentsPartial(SquareCoord coord) {
      if (this.attachmentsPartial == null) {
         this.attachmentsPartial = new ArrayList<>();
      }

      this.attachmentsPartial.add(coord);
   }

   public SquareCoord getAttachmentsPartial(int i) {
      return this.attachmentsPartial != null && !this.attachmentsPartial.isEmpty() ? this.attachmentsPartial.get(i) : null;
   }

   public boolean hasAttachmentsPartial(SquareCoord coord) {
      return this.attachmentsPartial != null && !this.attachmentsPartial.isEmpty() ? this.attachmentsPartial.contains(coord) : false;
   }

   public Integer attachmentsPartialSize() {
      return this.attachmentsPartial == null ? null : this.attachmentsPartial.size();
   }

   public EnumSet<ChunkGenerationStatus> isModded() {
      return this.chunkGenerationStatus;
   }

   public void isModded(EnumSet<ChunkGenerationStatus> chunkGenerationStatus) {
      this.chunkGenerationStatus = chunkGenerationStatus;
   }

   public void isModded(ChunkGenerationStatus chunkGenerationStatus) {
      this.chunkGenerationStatus = EnumSet.of(chunkGenerationStatus);
   }

   public void addModded(ChunkGenerationStatus chunkGenerationStatus) {
      this.chunkGenerationStatus.add(chunkGenerationStatus);
   }

   public void rmModded(ChunkGenerationStatus chunkGenerationStatus) {
      this.chunkGenerationStatus.remove(chunkGenerationStatus);
   }

   private static IsoChunk getFromPool() {
      IsoChunk chunk = (IsoChunk)IsoChunkMap.chunkStore.poll();
      if (chunk == null) {
         chunk = new IsoChunk((IsoCell)null);
      }

      chunk.assignLoadID();
      return chunk;
   }

   private boolean LoadBrandNew(int wx, int wy) {
      this.wx = wx;
      this.wy = wy;
      int wx_t = WorldGenUtils.INSTANCE.getCornerOfGeneration(wx);
      int wy_t = WorldGenUtils.INSTANCE.getCornerOfGeneration(wy);
      ChunksCache chunks = new ChunksCache();

      for (int i = wx_t; i < wx_t + 2; i++) {
         for (int j = wy_t; j < wy_t + 2; j++) {
            IsoChunk c = i == wx && j == wy ? this : getFromPool();
            CellLoader.LoadCellBinaryChunk(IsoWorld.instance.currentCell, i, j, c);
            c.wx = i;
            c.wy = j;
            chunks.put(new ChunkCoord(i, j), c);
         }
      }

      if (doWorldgen) {
         IsoWorld.instance.getWgChunk().generateChunks(chunks);
      }

      Basements.getInstance().onNewChunkLoaded(this);
      if (!GameClient.client && Core.addZombieOnCellLoad) {
         this.addZombies = true;
      }

      for (int i = wx_t; i < wx_t + 2; i++) {
         for (int j = wy_t; j < wy_t + 2; j++) {
            if (i != wx || j != wy) {
               IsoChunk c = (IsoChunk)chunks.get(new ChunkCoord(i, j));
               c.resetForStore();
               IsoChunkMap.chunkStore.add(c);
            }
         }
      }

      return true;
   }

   public boolean hasEmptySquaresOnLevelZero() {
      for (int y = 0; y < 8; y++) {
         for (int x = 0; x < 8; x++) {
            IsoGridSquare square = this.getGridSquare(x, y, 0);
            if ((square == null || square.getObjects().isEmpty()) && !this.hasNonEmptySquareBelow(x, y, 0)) {
               return true;
            }
         }
      }

      return false;
   }

   private boolean hasNonEmptySquareBelow(int x, int y, int z) {
      z--;

      while (z >= this.getMinLevel()) {
         IsoGridSquare square = this.getGridSquare(x, y, z);
         if (square != null && !square.getObjects().isEmpty()) {
            return true;
         }

         z--;
      }

      return false;
   }

   public boolean LoadChunk(int wx, int wy, ByteBuffer fromServer) {
      this.loaded = this.LoadOrCreate(wx, wy, fromServer);
      if (!this.loaded) {
         ChunkChecksum.setChecksum(wx, wy, 0L);
         this.Blam(wx, wy);
         this.loaded = this.LoadBrandNew(wx, wy);
      }

      return this.loaded;
   }

   private boolean LoadOrCreate(int wx, int wy, ByteBuffer fromServer) {
      this.wx = wx;
      this.wy = wy;
      boolean loaded;
      if (fromServer != null && !this.blam) {
         loaded = this.LoadFromBuffer(wx, wy, fromServer);
      } else {
         File inFile = ChunkMapFilenames.instance.getFilename(wx, wy);
         if (inFile.exists() && !this.blam) {
            try {
               this.LoadFromDisk();
               loaded = true;
            } catch (Exception e) {
               ExceptionLogger.logException(e, "Error loading chunk " + wx + "," + wy);
               if (GameServer.server) {
                  LoggerManager.getLogger("map").write("Error loading chunk " + wx + "," + wy);
                  LoggerManager.getLogger("map").write(e);
               }

               this.BackupBlam(wx, wy, e);
               loaded = false;
            }
         } else {
            loaded = this.LoadBrandNew(wx, wy);
         }
      }

      if (doForaging && loaded) {
         IsoWorld.instance.getZoneGenerator().genForaging(wx, wy);
      }

      return loaded;
   }

   public boolean LoadFromBuffer(int wx, int wy, ByteBuffer bb) {
      this.wx = wx;
      this.wy = wy;
      if (!this.blam) {
         try {
            this.LoadFromDiskOrBuffer(bb);
            return true;
         } catch (Exception e) {
            ExceptionLogger.logException(e);
            if (GameServer.server) {
               LoggerManager.getLogger("map").write("Error loading chunk " + wx + "," + wy);
               LoggerManager.getLogger("map").write(e);
            }

            this.BackupBlam(wx, wy, e);
            return false;
         }
      } else {
         return this.LoadBrandNew(wx, wy);
      }
   }

   private void assignRoom(IsoGridSquare sq) {
      if (sq != null && sq.getRoom() == null) {
         RoomDef roomDef = IsoWorld.instance.metaGrid.getRoomAt(sq.x, sq.y, sq.z);
         sq.setRoomID(roomDef == null ? -1L : roomDef.id);
      }
   }

   private void ensureNotNull3x3(int lx, int ly, int z) {
      this.ensureNotNull(lx, ly, z, 0, 0);

      for (int i = 0; i < DIRECTIONS.length; i++) {
         IsoDirections dir = DIRECTIONS[i];
         this.ensureNotNull(lx, ly, z, dir.dx(), dir.dy());
      }
   }

   private void ensureNotNull(int lx, int ly, int z, int dx, int dy) {
      IsoCell cell = IsoWorld.instance.currentCell;
      if (lx + dx >= 0 && lx + dx < 8 && ly + dy >= 0 && ly + dy < 8) {
         IsoGridSquare sq = this.getGridSquare(lx + dx, ly + dy, z);
         if (sq == null) {
            sq = IsoGridSquare.getNew(cell, null, this.wx * 8 + lx + dx, this.wy * 8 + ly + dy, z);
            this.setSquare(lx + dx, ly + dy, z, sq);
            this.assignRoom(sq);
         }
      }
   }

   public void loadInWorldStreamerThread() {
      this.recalcLoop1(); // pzopt: split for the recalc pool (override-edits IsoChunk 2)
      this.recalcPooled(); // pzopt
   }

   // pzopt: the stock method split in two. Loop 1 (RecalcProperties for every
   // square, creating missing squares) stays on the streamer thread: it is
   // what constructs squares and first computes polyline-zone outlines on
   // shared scratch. Everything after it only reads that state and touches
   // nothing outside this chunk, so it can run on a pzopt.RecalcPool worker.
   public void recalcLoop1() {
      for (int z = this.minLevel; z <= this.maxLevel; z++) {
         for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
               IsoGridSquare sq = this.getGridSquare(x, y, z);
               if (sq == null && z == 0) {
                  sq = IsoGridSquare.getNew(IsoWorld.instance.currentCell, null, this.wx * 8 + x, this.wy * 8 + y, z);
                  this.setSquare(x, y, z, sq);
               }

               if (sq != null) {
                  if (!sq.getObjects().isEmpty()) {
                     this.ensureNotNull3x3(x, y, z);

                     for (int zz = z - 1; zz > this.minLevel; zz--) {
                        this.ensureNotNull3x3(x, y, zz);
                     }
                  }

                  sq.RecalcProperties();
               }
            }
         }
      }
   }

   public void recalcPooled() {
      if (Guard.DEV && RecalcPool.failChunk != null && RecalcPool.failChunk.equals(this.wx + "," + this.wy)) {
         RecalcPool.failChunk = null; // one-shot: the streamer-thread retry of this chunk must succeed
         throw new IllegalStateException("pzopt: injected recalc failure for chunk " + this.wx + "," + this.wy);
      }
      // pzopt: a getter per pass instead of binding the static, so two passes can run at once
      IsoChunk.ChunkGetter getter = new IsoChunk.ChunkGetter();
      getter.chunk = this;

      for (int y = 0; y < 8; y++) {
         for (int x = 0; x < 8; x++) {
            for (int z = this.maxLevel; z > 0; z--) {
               IsoGridSquare sq = this.getGridSquare(x, y, z);
               if (sq != null && sq.hasRainBlockingTile()) {
                  z--;

                  for (; z >= this.getMinLevel(); z--) {
                     sq = this.getGridSquare(x, y, z);
                     if (sq != null && !sq.haveRoof) {
                        sq.haveRoof = true;
                        sq.getProperties().unset(IsoFlagType.exterior);
                     }
                  }
                  break;
               }
            }
         }
      }

      for (int z = this.minLevel; z <= this.maxLevel; z++) {
         for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
               IsoGridSquare sq = this.getGridSquare(x, y, z);
               if (sq != null) {
                  sq.RecalcAllWithNeighbours(true, getter);
               }
            }
         }
      }

      for (int z = this.minLevel; z <= this.maxLevel; z++) {
         for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
               IsoGridSquare sq = this.getGridSquare(x, y, z);
               if (sq != null) {
                  sq.propertiesDirty = true;
               }
            }
         }
      }
   }

   private void RecalcAllWithNeighbour(IsoGridSquare sq, IsoDirections dir, int dz) {
      int dx = dir.dx();
      int dy = dir.dy();
      int x = sq.getX() + dx;
      int y = sq.getY() + dy;
      int z = sq.getZ() + dz;
      IsoGridSquare sq2 = dz == 0 ? sq.getAdjacentSquare(dir) : IsoWorld.instance.currentCell.getGridSquare(x, y, z);
      if (sq2 != null) {
         sq.ReCalculateCollide(sq2);
         sq2.ReCalculateCollide(sq);
         sq.ReCalculatePathFind(sq2);
         sq2.ReCalculatePathFind(sq);
         sq.ReCalculateVisionBlocked(sq2);
         sq2.ReCalculateVisionBlocked(sq);
      }

      if (dz == 0) {
         switch (dir) {
            case E:
               if (sq2 == null) {
                  sq.e = null;
               } else {
                  sq.e = sq.testPathFindAdjacent(null, 1, 0, 0) ? null : sq2;
                  sq2.w = sq2.testPathFindAdjacent(null, -1, 0, 0) ? null : sq;
               }
               break;
            case W:
               if (sq2 == null) {
                  sq.w = null;
               } else {
                  sq.w = sq.testPathFindAdjacent(null, -1, 0, 0) ? null : sq2;
                  sq2.e = sq2.testPathFindAdjacent(null, 1, 0, 0) ? null : sq;
               }
               break;
            case N:
               if (sq2 == null) {
                  sq.n = null;
               } else {
                  sq.n = sq.testPathFindAdjacent(null, 0, -1, 0) ? null : sq2;
                  sq2.s = sq2.testPathFindAdjacent(null, 0, 1, 0) ? null : sq;
               }
               break;
            case S:
               if (sq2 == null) {
                  sq.s = null;
               } else {
                  sq.s = sq.testPathFindAdjacent(null, 0, 1, 0) ? null : sq2;
                  sq2.n = sq2.testPathFindAdjacent(null, 0, -1, 0) ? null : sq;
               }
               break;
            case NW:
               if (sq2 == null) {
                  sq.nw = null;
               } else {
                  sq.nw = sq.testPathFindAdjacent(null, -1, -1, 0) ? null : sq2;
                  sq2.se = sq2.testPathFindAdjacent(null, 1, 1, 0) ? null : sq;
               }
               break;
            case NE:
               if (sq2 == null) {
                  sq.ne = null;
               } else {
                  sq.ne = sq.testPathFindAdjacent(null, 1, -1, 0) ? null : sq2;
                  sq2.sw = sq2.testPathFindAdjacent(null, -1, 1, 0) ? null : sq;
               }
               break;
            case SE:
               if (sq2 == null) {
                  sq.se = null;
               } else {
                  sq.se = sq.testPathFindAdjacent(null, 1, 1, 0) ? null : sq2;
                  sq2.nw = sq2.testPathFindAdjacent(null, -1, -1, 0) ? null : sq;
               }
               break;
            case SW:
               if (sq2 == null) {
                  sq.sw = null;
               } else {
                  sq.sw = sq.testPathFindAdjacent(null, -1, 1, 0) ? null : sq2;
                  sq2.ne = sq2.testPathFindAdjacent(null, 1, -1, 0) ? null : sq;
               }
         }
      }
   }

   private void EnsureSurroundNotNullX(int x, int y, int z) {
      for (int x1 = x - 1; x1 <= x + 1; x1++) {
         if (x1 >= 0 && x1 < 8) {
            this.EnsureSurroundNotNull(x1, y, z);
         }
      }
   }

   private void EnsureSurroundNotNullY(int x, int y, int z) {
      for (int y1 = y - 1; y1 <= y + 1; y1++) {
         if (y1 >= 0 && y1 < 8) {
            this.EnsureSurroundNotNull(x, y1, z);
         }
      }
   }

   private void EnsureSurroundNotNull(int x, int y, int z) {
      IsoCell cell = IsoWorld.instance.currentCell;
      IsoGridSquare sq = this.getGridSquare(x, y, z);
      if (sq == null) {
         sq = IsoGridSquare.getNew(cell, null, this.wx * 8 + x, this.wy * 8 + y, z);
         cell.ConnectNewSquare(sq, false);
         this.assignRoom(sq);
         newSquareList.add(sq);
      }
   }

   private static int getMinLevelOf(int minLevel, IsoChunk chunk) {
      return chunk == null ? minLevel : PZMath.min(minLevel, chunk.minLevel);
   }

   private static int getMaxLevelOf(int maxLevel, IsoChunk chunk) {
      return chunk == null ? maxLevel : PZMath.max(maxLevel, chunk.maxLevel);
   }

   public void loadInMainThread() {
      pzopt.ChunkRate.loaded(); // pzopt: chunk hand-off rate (treeBakeMaxChunksPerSec)
      IsoCell cell = IsoWorld.instance.currentCell;
      IsoChunk chunkW = cell.getChunk(this.wx - 1, this.wy);
      IsoChunk chunkN = cell.getChunk(this.wx, this.wy - 1);
      IsoChunk chunkE = cell.getChunk(this.wx + 1, this.wy);
      IsoChunk chunkS = cell.getChunk(this.wx, this.wy + 1);
      IsoChunk chunkNW = cell.getChunk(this.wx - 1, this.wy - 1);
      IsoChunk chunkNE = cell.getChunk(this.wx + 1, this.wy - 1);
      IsoChunk chunkSE = cell.getChunk(this.wx + 1, this.wy + 1);
      IsoChunk chunkSW = cell.getChunk(this.wx - 1, this.wy + 1);
      int left = 0;
      int top = 0;
      int right = 7;
      int bottom = 7;
      int minLevel2 = getMinLevelOf(this.minLevel, chunkW);
      minLevel2 = getMinLevelOf(minLevel2, chunkN);
      minLevel2 = getMinLevelOf(minLevel2, chunkE);
      minLevel2 = getMinLevelOf(minLevel2, chunkS);
      minLevel2 = getMinLevelOf(minLevel2, chunkNW);
      minLevel2 = getMinLevelOf(minLevel2, chunkNE);
      minLevel2 = getMinLevelOf(minLevel2, chunkSE);
      minLevel2 = getMinLevelOf(minLevel2, chunkSW);
      int maxLevel2 = getMaxLevelOf(this.maxLevel, chunkW);
      maxLevel2 = getMaxLevelOf(maxLevel2, chunkN);
      maxLevel2 = getMaxLevelOf(maxLevel2, chunkE);
      maxLevel2 = getMaxLevelOf(maxLevel2, chunkS);
      maxLevel2 = getMaxLevelOf(maxLevel2, chunkNW);
      maxLevel2 = getMaxLevelOf(maxLevel2, chunkNE);
      maxLevel2 = getMaxLevelOf(maxLevel2, chunkSE);
      maxLevel2 = getMaxLevelOf(maxLevel2, chunkSW);
      newSquareList.clear();

      for (int z = minLevel2; z <= maxLevel2; z++) {
         for (int x = 0; x < 8; x++) {
            if (chunkN != null) {
               IsoGridSquare sq = chunkN.getGridSquare(x, 7, z);
               if (sq != null && !sq.getObjects().isEmpty()) {
                  this.EnsureSurroundNotNullX(x, 0, z);
               }
            }

            if (chunkS != null) {
               IsoGridSquare sq = chunkS.getGridSquare(x, 0, z);
               if (sq != null && !sq.getObjects().isEmpty()) {
                  this.EnsureSurroundNotNullX(x, 7, z);
               }
            }
         }

         for (int y = 0; y < 8; y++) {
            if (chunkW != null) {
               IsoGridSquare sq = chunkW.getGridSquare(7, y, z);
               if (sq != null && !sq.getObjects().isEmpty()) {
                  this.EnsureSurroundNotNullY(0, y, z);
               }
            }

            if (chunkE != null) {
               IsoGridSquare sq = chunkE.getGridSquare(0, y, z);
               if (sq != null && !sq.getObjects().isEmpty()) {
                  this.EnsureSurroundNotNullY(7, y, z);
               }
            }
         }

         if (chunkNW != null) {
            IsoGridSquare sq = chunkNW.getGridSquare(7, 7, z);
            if (sq != null && !sq.getObjects().isEmpty()) {
               this.EnsureSurroundNotNull(0, 0, z);
            }
         }

         if (chunkNE != null) {
            IsoGridSquare sq = chunkNE.getGridSquare(0, 7, z);
            if (sq != null && !sq.getObjects().isEmpty()) {
               this.EnsureSurroundNotNull(7, 0, z);
            }
         }

         if (chunkSE != null) {
            IsoGridSquare sq = chunkSE.getGridSquare(0, 0, z);
            if (sq != null && !sq.getObjects().isEmpty()) {
               this.EnsureSurroundNotNull(7, 7, z);
            }
         }

         if (chunkSW != null) {
            IsoGridSquare sq = chunkSW.getGridSquare(7, 0, z);
            if (sq != null && !sq.getObjects().isEmpty()) {
               this.EnsureSurroundNotNull(0, 7, z);
            }
         }
      }

      for (int z = minLevel2; z <= maxLevel2; z++) {
         for (int x = 0; x < 8; x++) {
            if (chunkN != null) {
               IsoGridSquare sq = this.getGridSquare(x, 0, z);
               if (sq != null && !sq.getObjects().isEmpty()) {
                  chunkN.EnsureSurroundNotNullX(x, 7, z);
               }
            }

            if (chunkS != null) {
               IsoGridSquare sq = this.getGridSquare(x, 7, z);
               if (sq != null && !sq.getObjects().isEmpty()) {
                  chunkS.EnsureSurroundNotNullX(x, 0, z);
               }
            }
         }

         for (int y = 0; y < 8; y++) {
            if (chunkW != null) {
               IsoGridSquare sq = this.getGridSquare(0, y, z);
               if (sq != null && !sq.getObjects().isEmpty()) {
                  chunkW.EnsureSurroundNotNullY(7, y, z);
               }
            }

            if (chunkE != null) {
               IsoGridSquare sq = this.getGridSquare(7, y, z);
               if (sq != null && !sq.getObjects().isEmpty()) {
                  chunkE.EnsureSurroundNotNullY(0, y, z);
               }
            }
         }

         if (chunkNW != null) {
            IsoGridSquare sq = this.getGridSquare(0, 0, z);
            if (sq != null && !sq.getObjects().isEmpty()) {
               chunkNW.EnsureSurroundNotNull(7, 7, z);
            }
         }

         if (chunkNE != null) {
            IsoGridSquare sq = this.getGridSquare(7, 0, z);
            if (sq != null && !sq.getObjects().isEmpty()) {
               chunkNE.EnsureSurroundNotNull(0, 7, z);
            }
         }

         if (chunkSE != null) {
            IsoGridSquare sq = this.getGridSquare(7, 7, z);
            if (sq != null && !sq.getObjects().isEmpty()) {
               chunkSE.EnsureSurroundNotNull(0, 0, z);
            }
         }

         if (chunkSW != null) {
            IsoGridSquare sq = this.getGridSquare(0, 7, z);
            if (sq != null && !sq.getObjects().isEmpty()) {
               chunkSW.EnsureSurroundNotNull(7, 0, z);
            }
         }
      }

      for (int i = 0; i < newSquareList.size(); i++) {
         IsoGridSquare sq = newSquareList.get(i);
         sq.RecalcAllWithNeighbours(true);
      }

      newSquareList.clear();
      GameProfiler profiler = GameProfiler.getInstance();
      ProfileArea var67 = profiler.profile("Recalc Nav");

      try {
         for (int z = this.minLevel; z <= this.maxLevel; z++) {
            for (int y = 0; y < 8; y++) {
               for (int x = 0; x < 8; x++) {
                  IsoGridSquare sq = this.getGridSquare(x, y, z);
                  if (sq != null) {
                     if (x == 0 || x == 7 || y == 0 || y == 7) {
                        sq.doGridNav(IsoGridSquare.cellGetSquare);

                        for (int dz = -1; dz <= 1; dz++) {
                           if (x == 0) {
                              this.RecalcAllWithNeighbour(sq, IsoDirections.W, dz);
                              this.RecalcAllWithNeighbour(sq, IsoDirections.NW, dz);
                              this.RecalcAllWithNeighbour(sq, IsoDirections.SW, dz);
                           } else if (x == 7) {
                              this.RecalcAllWithNeighbour(sq, IsoDirections.E, dz);
                              this.RecalcAllWithNeighbour(sq, IsoDirections.NE, dz);
                              this.RecalcAllWithNeighbour(sq, IsoDirections.SE, dz);
                           }

                           if (y == 0) {
                              this.RecalcAllWithNeighbour(sq, IsoDirections.N, dz);
                              if (x != 0) {
                                 this.RecalcAllWithNeighbour(sq, IsoDirections.NW, dz);
                              }

                              if (x != 7) {
                                 this.RecalcAllWithNeighbour(sq, IsoDirections.NE, dz);
                              }
                           } else if (y == 7) {
                              this.RecalcAllWithNeighbour(sq, IsoDirections.S, dz);
                              if (x != 0) {
                                 this.RecalcAllWithNeighbour(sq, IsoDirections.SW, dz);
                              }

                              if (x != 7) {
                                 this.RecalcAllWithNeighbour(sq, IsoDirections.SE, dz);
                              }
                           }
                        }

                        IsoGridSquare n = sq.getAdjacentSquare(IsoDirections.N);
                        IsoGridSquare s = sq.getAdjacentSquare(IsoDirections.S);
                        IsoGridSquare w = sq.getAdjacentSquare(IsoDirections.W);
                        IsoGridSquare e = sq.getAdjacentSquare(IsoDirections.E);
                        if (n != null && w != null && (x == 0 || y == 0)) {
                           this.RecalcAllWithNeighbour(n, IsoDirections.W, 0);
                        }

                        if (n != null && e != null && (x == 7 || y == 0)) {
                           this.RecalcAllWithNeighbour(n, IsoDirections.E, 0);
                        }

                        if (s != null && w != null && (x == 0 || y == 7)) {
                           this.RecalcAllWithNeighbour(s, IsoDirections.W, 0);
                        }

                        if (s != null && e != null && (x == 7 || y == 7)) {
                           this.RecalcAllWithNeighbour(s, IsoDirections.E, 0);
                        }
                     }

                     IsoRoom room = sq.getRoom();
                     if (room != null) {
                        room.addSquare(sq);
                     }
                  }
               }
            }

            IsoGridSquare nw = this.getGridSquare(0, 0, z);
            if (nw != null) {
               nw.RecalcAllWithNeighbours(true);
            }

            IsoGridSquare ne = this.getGridSquare(7, 0, z);
            if (ne != null) {
               ne.RecalcAllWithNeighbours(true);
            }

            IsoGridSquare sw = this.getGridSquare(0, 7, z);
            if (sw != null) {
               sw.RecalcAllWithNeighbours(true);
            }

            IsoGridSquare se = this.getGridSquare(7, 7, z);
            if (se != null) {
               se.RecalcAllWithNeighbours(true);
            }
         }
      } catch (Throwable var29) {
         if (var67 != null) {
            try {
               var67.close();
            } catch (Throwable var27) {
               var29.addSuppressed(var27);
            }
         }

         throw var29;
      }

      if (var67 != null) {
         var67.close();
      }

      this.fixObjectAmbientEmittersOnAdjacentChunks(chunkE, chunkS);
      if (chunkW != null) {
         chunkW.checkLightingLater_AllPlayers_AllLevels();
      }

      if (chunkN != null) {
         chunkN.checkLightingLater_AllPlayers_AllLevels();
      }

      if (chunkE != null) {
         chunkE.checkLightingLater_AllPlayers_AllLevels();
      }

      if (chunkS != null) {
         chunkS.checkLightingLater_AllPlayers_AllLevels();
      }

      if (chunkNW != null) {
         chunkNW.checkLightingLater_AllPlayers_AllLevels();
      }

      if (chunkNE != null) {
         chunkNE.checkLightingLater_AllPlayers_AllLevels();
      }

      if (chunkSE != null) {
         chunkSE.checkLightingLater_AllPlayers_AllLevels();
      }

      if (chunkSW != null) {
         chunkSW.checkLightingLater_AllPlayers_AllLevels();
      }

      for (int pn = 0; pn < IsoPlayer.numPlayers; pn++) {
         LosUtil.cachecleared[pn] = true;
      }

      IsoLightSwitch.chunkLoaded(this);
      var67 = profiler.profile("Recreate Level Cutaway");

      try {
         for (int z = this.minLevel; z <= this.maxLevel; z++) {
            this.getCutawayData().recreateLevel(z);
         }
      } catch (Throwable var28) {
         if (var67 != null) {
            try {
               var67.close();
            } catch (Throwable var26) {
               var28.addSuppressed(var26);
            }
         }

         throw var28;
      }

      if (var67 != null) {
         var67.close();
      }
   }

   private void fixObjectAmbientEmittersOnAdjacentChunks(IsoChunk chunkE, IsoChunk chunkS) {
      if (!GameServer.server) {
         if (chunkE != null || chunkS != null) {
            int left = 0;
            int top = 0;

            for (int z = 0; z < 64; z++) {
               if (chunkE != null) {
                  for (int y = 0; y < 8; y++) {
                     IsoGridSquare square = chunkE.getGridSquare(0, y, z);
                     this.fixObjectAmbientEmittersOnSquare(square, false);
                  }
               }

               if (chunkS != null) {
                  for (int x = 0; x < 8; x++) {
                     IsoGridSquare square = chunkS.getGridSquare(x, 0, z);
                     this.fixObjectAmbientEmittersOnSquare(square, true);
                  }
               }
            }
         }
      }
   }

   private void fixObjectAmbientEmittersOnSquare(IsoGridSquare square, boolean north) {
   }

   @Deprecated
   public void recalcNeighboursNow() {
      IsoCell cell = IsoWorld.instance.currentCell;

      for (int x = 0; x < 8; x++) {
         for (int y = 0; y < 8; y++) {
            for (int z = this.minLevel; z <= this.maxLevel; z++) {
               IsoGridSquare sq = this.getGridSquare(x, y, z);
               if (sq != null) {
                  if (z > 0 && !sq.getObjects().isEmpty()) {
                     sq.EnsureSurroundNotNull();

                     for (int zz = z - 1; zz > this.minLevel; zz--) {
                        IsoGridSquare sq2 = this.getGridSquare(x, y, zz);
                        if (sq2 == null) {
                           sq2 = IsoGridSquare.getNew(cell, null, this.wx * 8 + x, this.wy * 8 + y, zz);
                           cell.ConnectNewSquare(sq2, false);
                           this.assignRoom(sq2);
                        }
                     }
                  }

                  sq.RecalcProperties();
               }
            }
         }
      }

      for (int z = this.minLevel; z <= this.maxLevel; z++) {
         for (int x = -1; x < 9; x++) {
            IsoGridSquare sq = cell.getGridSquare(this.wx * 8 + x, this.wy * 8 - 1, z);
            if (sq != null && !sq.getObjects().isEmpty()) {
               sq.EnsureSurroundNotNull();
            }

            sq = cell.getGridSquare(this.wx * 8 + x, this.wy * 8 + 8, z);
            if (sq != null && !sq.getObjects().isEmpty()) {
               sq.EnsureSurroundNotNull();
            }
         }

         for (int y = 0; y < 8; y++) {
            IsoGridSquare sq = cell.getGridSquare(this.wx * 8 - 1, this.wy * 8 + y, z);
            if (sq != null && !sq.getObjects().isEmpty()) {
               sq.EnsureSurroundNotNull();
            }

            sq = cell.getGridSquare(this.wx * 8 + 8, this.wy * 8 + y, z);
            if (sq != null && !sq.getObjects().isEmpty()) {
               sq.EnsureSurroundNotNull();
            }
         }
      }

      for (int x = 0; x < 8; x++) {
         for (int y = 0; y < 8; y++) {
            for (int z = this.minLevel; z <= this.maxLevel; z++) {
               IsoGridSquare sq = this.getGridSquare(x, y, z);
               if (sq != null) {
                  sq.RecalcAllWithNeighbours(true);
                  IsoRoom r = sq.getRoom();
                  if (r != null) {
                     r.addSquare(sq);
                  }
               }
            }
         }
      }

      for (int x = 0; x < 8; x++) {
         for (int y = 0; y < 8; y++) {
            for (int z = this.minLevel; z <= this.maxLevel; z++) {
               IsoGridSquare sq = this.getGridSquare(x, y, z);
               if (sq != null) {
                  sq.propertiesDirty = true;
               }
            }
         }
      }

      IsoLightSwitch.chunkLoaded(this);
   }

   public void updateBuildings() {
   }

   public static void updatePlayerInBullet() {
      ArrayList<IsoPlayer> players = GameServer.getPlayers();
      Bullet.updatePlayerList(players);
   }

   public void update() {
      pzopt.Stats.frameTick(IsoCamera.frameState.frameCount); // pzopt: frame-time sampling, one compare per call
      if (doAttachments && !this.blendingDoneFull && !Arrays.equals(this.blendingModified, comparatorBool4)) {
         IsoWorld.instance.getBlending().applyBlending(this);
      }

      if (doAttachments && !this.attachmentsDoneFull) {
         IsoWorld.instance.getAttachmentsHandler().applyAttachments(this);
      }

      if (!GameServer.server && (this.minLevelPhysics != this.minLevel || this.maxLevelPhysics != this.maxLevel)) {
         this.minLevelPhysics = this.minLevel;
         this.maxLevelPhysics = this.maxLevel;
         Bullet.setChunkMinMaxLevel(this.wx, this.wy, this.minLevel, this.maxLevel);
      }

      if (!this.loadedPhysics) {
         this.loadedPhysics = true;

         for (int i = 0; i < this.vehicles.size(); i++) {
            this.vehicles.get(i).chunk = this;
         }
      }

      if (this.ragdollControllersForAddToWorld != null) {
         for (int index = 0; index < this.ragdollControllersForAddToWorld.size(); index++) {
            this.ragdollControllersForAddToWorld.get(index).addToWorld();
         }

         this.ragdollControllersForAddToWorld.clear();
         this.ragdollControllersForAddToWorld = null;
      }

      this.updateVehicleStory();
   }

   public void updateVehicleStory() {
      if (this.loaded && this.vehicleStorySpawnData != null) {
         IsoMetaChunk metaChunk = IsoWorld.instance.getMetaChunk(this.wx, this.wy);
         if (metaChunk != null) {
            VehicleStorySpawnData spawnData = this.vehicleStorySpawnData;

            for (int i = 0; i < metaChunk.getZonesSize(); i++) {
               Zone zone = metaChunk.getZone(i);
               if (spawnData.isValid(zone, this)) {
                  spawnData.story.randomizeVehicleStory(zone, this);
                  zone.hourLastSeen++;
                  break;
               }
            }
         }
      }
   }

   public int squaresIndexOfLevel(int worldSquareZ) {
      return worldSquareZ - this.minLevel;
   }

   public IsoGridSquare[] getSquaresForLevel(int worldSquareZ) {
      return this.squares[this.squaresIndexOfLevel(worldSquareZ)];
   }

   public void doPathfind() {
      this.ignorePathfind = false;
   }

   public void ignorePathfind() {
      this.ignorePathfind = true;
   }

   public void setSquare(int x, int y, int z, IsoGridSquare square) {
      assert square == null || square.x - this.wx * 8 == x && square.y - this.wy * 8 == y && square.z == z;
      boolean bNewLevels = !this.isValidLevel(z);
      this.setMinMaxLevel(PZMath.min(this.getMinLevel(), z), PZMath.max(this.getMaxLevel(), z));
      int zz = this.squaresIndexOfLevel(z);
      this.squares[zz][x + y * 8] = square;
      if (square != null) {
         square.chunk = this;
         square.associatedBuilding = IsoWorld.instance.getMetaGrid().getAssociatedBuildingAt(square.x, square.y);
      }

      if (this.jobType != IsoChunk.JobType.SoftReset) {
         Guard.assertGameThread("pathfinding registration (level change)"); // pzopt: dev-build check, see design Decision 6
         if (!this.ignorePathfind && bNewLevels && Thread.currentThread() == GameWindow.gameThread || Thread.currentThread() == GameServer.mainThread) {
            if (PathfindNative.useNativeCode) {
               PathfindNative.instance.addChunkToWorld(this);
            } else {
               PolygonalMap2.instance.addChunkToWorld(this);
            }
         }
      }
   }

   public int getMinLevel() {
      return this.minLevel;
   }

   public int getMaxLevel() {
      return this.maxLevel;
   }

   public boolean isValidLevel(int level) {
      return level >= this.getMinLevel() && level <= this.getMaxLevel();
   }

   public void setMinMaxLevel(int minLevel, int maxLevel) {
      if (minLevel != this.minLevel || maxLevel != this.maxLevel) {
         for (int z = this.minLevel; z <= this.maxLevel; z++) {
            if (z < minLevel || z > maxLevel) {
               IsoChunkLevel chunkLevel = this.levels[z - this.minLevel];
               if (chunkLevel != null) {
                  this.unlinkSquares(chunkLevel);
                  chunkLevel.clear();
                  chunkLevel.release();
                  this.levels[z - this.minLevel] = null;
               }
            }
         }

         IsoChunkLevel[] newLevels = new IsoChunkLevel[maxLevel - minLevel + 1];

         for (int z = minLevel; z <= maxLevel; z++) {
            if (this.isValidLevel(z)) {
               newLevels[z - minLevel] = this.levels[z - this.minLevel];
            } else {
               newLevels[z - minLevel] = IsoChunkLevel.alloc().init(this, z);
            }
         }

         this.minLevel = minLevel;
         this.maxLevel = maxLevel;
         this.levels = newLevels;
         this.squares = new IsoGridSquare[maxLevel - minLevel + 1][];

         for (int z = minLevel; z <= maxLevel; z++) {
            this.squares[z - minLevel] = this.levels[z - minLevel].squares;
         }
      }
   }

   public IsoChunkLevel getLevelData(int level) {
      return this.isValidLevel(level) ? this.levels[level - this.minLevel] : null;
   }

   public IsoGridSquare getGridSquare(int chunkSquareX, int chunkSquareY, int worldSquareZ) {
      if (chunkSquareX >= 0 && chunkSquareX < 8 && chunkSquareY >= 0 && chunkSquareY < 8 && worldSquareZ <= this.maxLevel && worldSquareZ >= this.minLevel) {
         int zz = this.squaresIndexOfLevel(worldSquareZ);
         return zz < this.squares.length && zz >= 0 ? this.squares[zz][chunkSquareY * 8 + chunkSquareX] : null;
      } else {
         return null;
      }
   }

   public IsoRoom getRoom(long roomID) {
      return IsoWorld.instance.getMetaGrid().getRoomByID(roomID);
   }

   public void removeFromWorld() {
      loadGridSquare.remove(this);
      this.preventHotSave = true;
      if (GameClient.client && GameClient.instance.connected) {
         try {
            GameClient.instance.sendAddedRemovedItems(true);
         } catch (Exception ex) {
            ExceptionLogger.logException(ex);
         }
      }

      try {
         MapCollisionData.instance.removeChunkFromWorld(this);
         AnimalPopulationManager.getInstance().removeChunkFromWorld(this);
         ZombiePopulationManager.instance.removeChunkFromWorld(this);
         if (!GameClient.client) {
            int popmanCellX = (int)Math.floor(this.wx / 32.0);
            int popmanCellY = (int)Math.floor(this.wy / 32.0);
            ZombiePopulationManager.instance.requestSaveCell(popmanCellX, popmanCellY);
         }

         if (PathfindNative.useNativeCode) {
            PathfindNative.instance.removeChunkFromWorld(this);
         } else {
            PolygonalMap2.instance.removeChunkFromWorld(this);
         }

         this.collision.clear();
      } catch (Exception ex) {
         ExceptionLogger.logException(ex);
      }

      int to = 64;

      for (int n = this.minLevel; n <= this.maxLevel; n++) {
         for (int m = 0; m < 64; m++) {
            IsoGridSquare sq = this.squares[this.squaresIndexOfLevel(n)][m];
            if (sq != null) {
               RainManager.RemoveAllOn(sq);
               sq.clearWater();
               sq.clearPuddles();
               if (sq.getRoom() != null) {
                  sq.getRoom().removeSquare(sq);
               }

               if (sq.zone != null) {
                  sq.zone.removeSquare(sq);
               }

               ArrayList<IsoMovingObject> mov = sq.getMovingObjects();

               for (int a = 0; a < mov.size(); a++) {
                  IsoMovingObject obj = mov.get(a);
                  if (obj instanceof IsoSurvivor) {
                     IsoWorld.instance.currentCell.getSurvivorList().remove(obj);
                     obj.Despawn();
                  }

                  if (obj instanceof IsoAnimal isoAnimal && GameClient.client) {
                     AnimalInstanceManager.getInstance().remove(isoAnimal);
                  }

                  obj.removeFromWorld();
                  obj.current = obj.last = null;
                  if (!GameServer.server && !GameClient.client && obj instanceof BaseVehicle vehicle && vehicle.shouldUpdateInMeta()) {
                     this.vehicles.remove(vehicle);
                     VehiclesDB2.instance.updateVehicle(vehicle);
                     VirtualVehicleManager.getInstance().addToMeta(vehicle);
                  }

                  if (!mov.contains(obj)) {
                     a--;
                  }
               }

               mov.clear();

               for (int i = 0; i < sq.getObjects().size(); i++) {
                  IsoObject obj = (IsoObject)sq.getObjects().get(i);
                  obj.removeFromWorldToMeta();
               }

               for (int i = 0; i < sq.getStaticMovingObjects().size(); i++) {
                  IsoMovingObject obj = (IsoMovingObject)sq.getStaticMovingObjects().get(i);
                  obj.removeFromWorld();
               }

               this.disconnectFromAdjacentChunks(sq);
               sq.softClear();
               sq.chunk = null;
            }
         }
      }

      for (int i = 0; i < this.vehicles.size(); i++) {
         BaseVehicle vehicle = this.vehicles.get(i);
         if (IsoWorld.instance.currentCell.getVehicles().contains(vehicle) || IsoWorld.instance.currentCell.addVehicles.contains(vehicle)) {
            DebugLog.log("IsoChunk.removeFromWorld: vehicle wasn't removed from world id=" + vehicle.vehicleId);
            vehicle.removeFromWorld();
         }
      }

      if (this.corpseCount != null) {
         this.corpseCount.removeFromWorld();
      }

      if (!GameServer.server) {
         FBORenderOcclusion.getInstance().removeChunkFromWorld(this);
         FBORenderChunkManager.instance.freeChunk(this);
         java.util.Arrays.fill(this.pzoptZoomReturned, 0L); // pzopt: zoomRetain, nothing pending for a reused chunk
         java.util.Arrays.fill(this.pzoptZoomAllowed, 0L);
         this.cutawayData.removeFromWorld();
         this.getVispolyData().removeFromWorld();
         if (this.corpseData != null) {
            this.corpseData.removeFromWorld();
         }
      }

      this.preventHotSave = false;
   }

   private void disconnectFromAdjacentChunks(IsoGridSquare sq) {
      int lx = PZMath.coordmodulo(sq.x, 8);
      int ly = PZMath.coordmodulo(sq.y, 8);
      if (lx == 0 || lx == 7 || ly == 0 || ly == 7) {
         IsoDirections d1 = IsoDirections.N;
         IsoDirections d2 = IsoDirections.S;
         if (sq.getAdjacentSquare(d1) != null && sq.getAdjacentSquare(d1).chunk != sq.chunk) {
            sq.getAdjacentSquare(d1).setAdjacentSquare(d2, null);
            sq.getAdjacentSquare(d1).s = null;
         }

         d1 = IsoDirections.NW;
         d2 = IsoDirections.SE;
         if (sq.getAdjacentSquare(d1) != null && sq.getAdjacentSquare(d1).chunk != sq.chunk) {
            sq.getAdjacentSquare(d1).setAdjacentSquare(d2, null);
            sq.getAdjacentSquare(d1).se = null;
         }

         d1 = IsoDirections.W;
         d2 = IsoDirections.E;
         if (sq.getAdjacentSquare(d1) != null && sq.getAdjacentSquare(d1).chunk != sq.chunk) {
            sq.getAdjacentSquare(d1).setAdjacentSquare(d2, null);
            sq.getAdjacentSquare(d1).e = null;
         }

         d1 = IsoDirections.SW;
         d2 = IsoDirections.NE;
         if (sq.getAdjacentSquare(d1) != null && sq.getAdjacentSquare(d1).chunk != sq.chunk) {
            sq.getAdjacentSquare(d1).setAdjacentSquare(d2, null);
            sq.getAdjacentSquare(d1).ne = null;
         }

         d1 = IsoDirections.S;
         d2 = IsoDirections.N;
         if (sq.getAdjacentSquare(d1) != null && sq.getAdjacentSquare(d1).chunk != sq.chunk) {
            sq.getAdjacentSquare(d1).setAdjacentSquare(d2, null);
            sq.getAdjacentSquare(d1).n = null;
         }

         d1 = IsoDirections.SE;
         d2 = IsoDirections.NW;
         if (sq.getAdjacentSquare(d1) != null && sq.getAdjacentSquare(d1).chunk != sq.chunk) {
            sq.getAdjacentSquare(d1).setAdjacentSquare(d2, null);
            sq.getAdjacentSquare(d1).nw = null;
         }

         d1 = IsoDirections.E;
         d2 = IsoDirections.W;
         if (sq.getAdjacentSquare(d1) != null && sq.getAdjacentSquare(d1).chunk != sq.chunk) {
            sq.getAdjacentSquare(d1).setAdjacentSquare(d2, null);
            sq.getAdjacentSquare(d1).w = null;
         }

         d1 = IsoDirections.NE;
         d2 = IsoDirections.SW;
         if (sq.getAdjacentSquare(d1) != null && sq.getAdjacentSquare(d1).chunk != sq.chunk) {
            sq.getAdjacentSquare(d1).setAdjacentSquare(d2, null);
            sq.getAdjacentSquare(d1).sw = null;
         }
      }
   }

   public void doReuseGridsquares() {
      ObjectCache<IsoObject>.ObjectCacheList cacheListObject = CellLoader.isoObjectCache.popList();
      ObjectCache<IsoTree>.ObjectCacheList cacheListTree = CellLoader.isoTreeCache.popList();
      int to = 64;

      for (int n = 0; n < this.squares.length; n++) {
         for (int m = 0; m < 64; m++) {
            IsoGridSquare sq = this.squares[n][m];
            if (sq != null) {
               LuaEventManager.triggerEvent("ReuseGridsquare", sq);

               for (int a = 0; a < sq.getObjects().size(); a++) {
                  IsoObject o = (IsoObject)sq.getObjects().get(a);
                  if (o instanceof IsoTree tree) {
                     o.reset();
                     cacheListTree.add(tree);
                  } else if (o instanceof IsoObject && o.getObjectName().equals("IsoObject")) {
                     o.reset();
                     cacheListObject.add(o);
                  } else {
                     o.reuseGridSquare();
                  }
               }

               sq.discard();
               this.squares[n][m] = null;
            }
         }
      }

      CellLoader.isoObjectCache.push(cacheListObject);
      CellLoader.isoTreeCache.push(cacheListTree);
      this.resetForStore();
      assert !IsoChunkMap.chunkStore.contains(this);
      IsoChunkMap.chunkStore.add(this);
   }

   private static int bufferSize(int size) {
      return (size + 65536 - 1) / 65536 * 65536;
   }

   private static ByteBuffer ensureCapacity(ByteBuffer bb, int capacity) {
      if (bb == null || bb.capacity() < capacity) {
         bb = ByteBuffer.allocate(bufferSize(capacity));
      }

      return bb;
   }

   private static ByteBuffer ensureCapacity(ByteBuffer bb) {
      if (bb == null) {
         return ByteBuffer.allocate(65536);
      } else if (bb.capacity() - bb.position() < 65536) {
         ByteBuffer newBB = ensureCapacity(null, bb.position() + 65536);
         return newBB.put(bb.array(), 0, bb.position());
      } else {
         return bb;
      }
   }

   private boolean[] readFlags(ByteBuffer bb, int nFlags) {
      boolean[] ret = new boolean[nFlags];
      byte val = bb.get();
      int pow = 1;

      for (byte i = 0; i < nFlags; i++) {
         ret[i] = (val & pow) == pow;
         pow *= 2;
      }

      return ret;
   }

   private void writeFlags(ByteBuffer bb, boolean[] flags) {
      int b = 0;

      for (byte i = 0; i < flags.length; i++) {
         b += (flags[i] ? 1 : 0) << i;
      }

      bb.put((byte)b);
   }

   public void LoadFromDisk() throws IOException {
      this.LoadFromDiskOrBuffer(null);
   }

   private void LoadFromDiskOrBuffer(ByteBuffer bb) throws IOException {
      sanityCheck.beginLoad(this);

      try {
         this.LoadFromDiskOrBufferInternal(bb);
      } finally {
         sanityCheck.endLoad(this);
      }

      if (this.getGridSquare(0, 0, 0) == null && this.getGridSquare(7, 7, 0) == null) {
         if (bb != null) {
            bb.rewind();
         }

         this.LoadFromDiskOrBufferInternal(bb);
         throw new RuntimeException("black chunk " + this.wx + "," + this.wy);
      }
   }

   private void LoadFromDiskOrBufferInternal(ByteBuffer bb) throws IOException {
      try {
         ByteBuffer sliceBufferLoad;
         if (bb == null) {
            IsoChunk.sliceBufferLoad = SafeRead(this.wx, this.wy, IsoChunk.sliceBufferLoad);
            sliceBufferLoad = IsoChunk.sliceBufferLoad;
         } else {
            sliceBufferLoad = bb;
         }

         int wX = this.wx * 8;
         int wY = this.wy * 8;
         wX /= 256;
         wY /= 256;
         String filenameheader = ChunkMapFilenames.instance.getHeader(wX, wY);
         if (IsoLot.InfoHeaders.containsKey(filenameheader)) {
            this.lotheader = (LotHeader)IsoLot.InfoHeaders.get(filenameheader);
         }

         IsoCell.wx = this.wx;
         IsoCell.wy = this.wy;
         boolean isDebugSave = sliceBufferLoad.get() != 0;
         int worldVersion = sliceBufferLoad.getInt();
         if (isDebugSave) {
            DebugLog.log("WorldVersion = " + worldVersion + ", debug = " + isDebugSave);
         }

         if (worldVersion > 249) {
            throw new RuntimeException("unknown world version " + worldVersion + " while reading chunk " + this.wx + "," + this.wy);
         }

         this.fixed2x = true;
         int len = sliceBufferLoad.getInt();
         sanityCheck.checkLength(len, sliceBufferLoad.limit());
         long crc = sliceBufferLoad.getLong();
         crcLoad.reset();
         crcLoad.update(sliceBufferLoad.array(), 17, sliceBufferLoad.limit() - 1 - 4 - 4 - 8);
         sanityCheck.checkCRC(crc, crcLoad.getValue());
         if (worldVersion >= 209) {
            this.blendingDoneFull = sliceBufferLoad.get() != 0;
         }

         if (worldVersion >= 210) {
            this.blendingModified = this.readFlags(sliceBufferLoad, this.blendingModified.length);
            this.blendingDonePartial = sliceBufferLoad.get() != 0;
            if (!Arrays.equals(this.blendingModified, comparatorBool4) && this.blendingDonePartial) {
               for (int i = 0; i < 4; i++) {
                  this.blendingDepth[i] = sliceBufferLoad.get();
               }
            }
         }

         if (worldVersion >= 214) {
            this.attachmentsDoneFull = sliceBufferLoad.get() != 0;
            this.attachmentsState = this.readFlags(sliceBufferLoad, this.attachmentsState.length);
         }

         if (worldVersion >= 221) {
            short count = sliceBufferLoad.getShort();
            if (count == 0) {
               this.attachmentsPartial = null;
            } else {
               this.attachmentsPartial = new ArrayList<>();

               for (short i = 0; i < count; i++) {
                  this.attachmentsPartial.add(SquareCoord.load(sliceBufferLoad));
               }
            }
         }

         int bloodSplatLifespanDays = SandboxOptions.getInstance().bloodSplatLifespanDays.getValue();
         float worldAgeHours = (float)GameTime.getInstance().getWorldAgeHours();
         int minLevel;
         int maxLevel;
         if (206 <= worldVersion) {
            maxLevel = sliceBufferLoad.getInt();
            minLevel = sliceBufferLoad.getInt();
         } else {
            maxLevel = 7;
            minLevel = 0;
         }

         this.setMinMaxLevel(minLevel, maxLevel);
         int c = sliceBufferLoad.getInt();

         for (int n = 0; n < c; n++) {
            IsoFloorBloodSplat s = new IsoFloorBloodSplat();
            s.load(sliceBufferLoad, worldVersion);
            s.worldAge = GameTime.minHours(s.worldAge, worldAgeHours);
            if (bloodSplatLifespanDays <= 0 || !(worldAgeHours - s.worldAge >= bloodSplatLifespanDays * 24)) {
               if (s.type < 8) {
                  this.nextSplatIndex = s.index % 10;
               }

               this.floorBloodSplats.add(s);
            }
         }

         IsoMetaGrid metaGrid = IsoWorld.instance.getMetaGrid();

         for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
               long flags;
               if (worldVersion >= 206) {
                  flags = sliceBufferLoad.getLong();
               } else {
                  flags = sliceBufferLoad.get();
                  flags <<= 32;
               }

               for (int zz = minLevel; zz <= maxLevel; zz++) {
                  IsoGridSquare gs = null;
                  int n = 0;
                  if ((flags & 1L << zz + 32) != 0L) {
                     n = 1;
                  }

                  if (n == 1) {
                     if (gs == null) {
                        if (IsoGridSquare.loadGridSquareCache != null) {
                           gs = IsoGridSquare.getNew(
                              IsoGridSquare.loadGridSquareCache, IsoWorld.instance.currentCell, null, x + this.wx * 8, y + this.wy * 8, zz
                           );
                        } else {
                           gs = IsoGridSquare.getNew(IsoWorld.instance.currentCell, null, x + this.wx * 8, y + this.wy * 8, zz);
                        }
                     }

                     gs.chunk = this;
                     if (this.lotheader != null) {
                        RoomDef roomDef = metaGrid.getRoomAt(gs.x, gs.y, gs.z);
                        long roomID = roomDef != null ? roomDef.id : -1L;
                        gs.setRoomID(roomID);
                        roomDef = metaGrid.getEmptyOutsideAt(gs.x, gs.y, gs.z);
                        if (roomDef != null) {
                           IsoRoom room = this.getRoom(roomDef.id);
                           gs.roofHideBuilding = room == null ? null : room.building;
                        }
                     }

                     gs.ResetIsoWorldRegion();
                     this.setSquare(x, y, zz, gs);
                  }

                  if (n == 1 && gs != null) {
                     gs.load(sliceBufferLoad, worldVersion, isDebugSave);
                     gs.FixStackableObjects();
                     if (this.jobType == IsoChunk.JobType.SoftReset) {
                        if (!gs.getStaticMovingObjects().isEmpty()) {
                           gs.getStaticMovingObjects().clear();
                        }

                        for (int m = 0; m < gs.getObjects().size(); m++) {
                           IsoObject o = (IsoObject)gs.getObjects().get(m);
                           o.softReset();
                           if (o.getObjectIndex() == -1) {
                              m--;
                           }
                        }

                        gs.setOverlayDone(false);
                     }
                  }
               }
            }
         }

         this.getErosionData().load(sliceBufferLoad, worldVersion);
         this.getErosionData().set(this);
         short count = sliceBufferLoad.getShort();
         if (count > 0 && this.generatorsTouchingThisChunk == null) {
            this.generatorsTouchingThisChunk = new ArrayList<>();
         }

         if (this.generatorsTouchingThisChunk != null) {
            this.generatorsTouchingThisChunk.clear();
         }

         for (int i = 0; i < count; i++) {
            int x = sliceBufferLoad.getInt();
            int y = sliceBufferLoad.getInt();
            int z = sliceBufferLoad.get();
            Location pos = new Location(x, y, z);
            this.generatorsTouchingThisChunk.add(pos);
         }

         this.vehicles.clear();
         if (!GameClient.client) {
            short numVehicles = sliceBufferLoad.getShort();

            for (int i = 0; i < numVehicles; i++) {
               byte x = sliceBufferLoad.get();
               byte y = sliceBufferLoad.get();
               byte z = sliceBufferLoad.get();
               IsoObject obj = IsoObject.factoryFromFileInput(IsoWorld.instance.currentCell, sliceBufferLoad);
               if (obj != null && obj instanceof BaseVehicle baseVehicle) {
                  IsoGridSquare sq = this.getGridSquare(x, y, z);
                  obj.square = sq;
                  ((IsoMovingObject)obj).current = sq;

                  try {
                     obj.load(sliceBufferLoad, worldVersion, isDebugSave);
                     this.vehicles.add(baseVehicle);
                     addFromCheckedVehicles(baseVehicle);
                     if (this.jobType == IsoChunk.JobType.SoftReset) {
                        obj.softReset();
                     }
                  } catch (Exception ex) {
                     throw new RuntimeException(ex);
                  }
               }
            }

            this.lootRespawnHour = sliceBufferLoad.getInt();
            if (worldVersion >= 206) {
               int rooms = sliceBufferLoad.getShort();

               for (int i = 0; i < rooms; i++) {
                  long roomID = sliceBufferLoad.getLong();
                  this.addSpawnedRoom(roomID);
               }
            } else {
               int rooms = sliceBufferLoad.get();

               for (int i = 0; i < rooms; i++) {
                  int roomID = sliceBufferLoad.getInt();
                  this.addSpawnedRoom(RoomID.makeID(this.wx / 8, this.wy / 8, roomID));
               }
            }
         }
      } finally {
         this.fixed2x = true;
      }
   }

   public void doLoadGridsquare() {
      this.preventHotSave = true;
      if (this.jobType == IsoChunk.JobType.SoftReset) {
         this.spawnedRooms.clear();
      }

      if (!GameServer.server) {
         this.loadInMainThread();
      }

      int cellX = PZMath.fastfloor(this.wx / 32.0F);
      int cellY = PZMath.fastfloor(this.wy / 32.0F);
      IsoMetaCell metaCell = IsoWorld.instance.getMetaGrid().getCellData(cellX, cellY);
      if (metaCell != null && !GameClient.client) {
         metaCell.checkAnimalZonesGenerated(this.wx, this.wy);
      }

      if (this.addZombies && !VehiclesDB2.instance.isChunkSeen(this.wx, this.wy)) {
         try {
            this.AddVehicles();
         } catch (Throwable t) {
            ExceptionLogger.logException(t);
         }
      }

      if (!GameClient.client) {
         this.AddZombieZoneStory();
         this.AddRanchAnimals();
      }

      this.CheckGrassRegrowth();
      VehiclesDB2.instance.setChunkSeen(this.wx, this.wy);
      if (this.addZombies) {
         if (IsoWorld.instance.getTimeSinceLastSurvivorInHorde() > 0) {
            IsoWorld.instance.setTimeSinceLastSurvivorInHorde(IsoWorld.instance.getTimeSinceLastSurvivorInHorde() - 1);
         }

         this.addSurvivorInHorde(false);
         WorldGenChunk wgChunk = IsoWorld.instance.getWgChunk();

         for (int i = 0; i < this.proceduralZombieSquares.size(); i++) {
            IsoGridSquare square = this.proceduralZombieSquares.get(i);
            wgChunk.addZombieToSquare(square);
         }
      }

      this.proceduralZombieSquares.clear();
      this.update();
      this.addRagdollControllers();
      CorpseCount.instance.chunkLoaded(this);
      if (!GameServer.server) {
         FliesSound.instance.chunkLoaded(this);
         NearestWalls.chunkLoaded(this);
      }

      if (this.addZombies) {
         int rand = 5 + SandboxOptions.instance.timeSinceApo.getValue();
         rand = Math.min(20, rand);
         if (Rand.Next(rand) == 0) {
            this.AddCorpses(this.wx, this.wy);
         }

         if (Rand.Next(rand * 2) == 0) {
            this.AddBlood(this.wx, this.wy);
         }
      }

      LoadGridsquarePerformanceWorkaround.init(this.wx, this.wy);
      int chunksPerWidth = 8;
      if (!GameClient.client) {
         for (int i = 0; i < this.vehicles.size(); i++) {
            BaseVehicle v = this.vehicles.get(i);
            if (!v.addedToWorld && VehiclesDB2.instance.isVehicleLoaded(v)) {
               v.removeFromSquare();
               this.vehicles.remove(i);
               i--;
            } else {
               if (!v.addedToWorld) {
                  v.addToWorld();
               }

               if (v.sqlId == -1) {
                  assert false;
                  if (v.square == null) {
                     float d = 5.0E-4F;
                     int minX = this.wx * 8;
                     int minY = this.wy * 8;
                     int maxX = minX + 8;
                     int maxY = minY + 8;
                     float x = PZMath.clamp(v.getX(), minX + 5.0E-4F, maxX - 5.0E-4F);
                     float y = PZMath.clamp(v.getY(), minY + 5.0E-4F, maxY - 5.0E-4F);
                     v.square = this.getGridSquare(PZMath.fastfloor(x) - this.wx * 8, PZMath.fastfloor(y) - this.wy * 8, 0);
                  }

                  VehiclesDB2.instance.addVehicle(v);
               }
            }
         }
      }

      this.treeCount = 0;
      this.scavengeZone = null;
      this.numberOfWaterTiles = 0;

      for (int zz = this.minLevel; zz <= this.maxLevel; zz++) {
         for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
               IsoGridSquare square = this.getGridSquare(x, y, zz);
               if (square != null && !square.getObjects().isEmpty()) {
                  for (int i = 0; i < square.getObjects().size(); i++) {
                     IsoObject obj = (IsoObject)square.getObjects().get(i);
                     obj.addToWorld();
                     if (obj.getSprite() != null && obj.getSprite().getProperties().has(IsoPropertyType.FUEL_AMOUNT)) {
                        obj.getPipedFuelAmount();
                     }

                     if (zz == 0 && obj.getSprite() != null && obj.getSprite().getProperties().has(IsoFlagType.water)) {
                        this.numberOfWaterTiles++;
                     }
                  }

                  if (square.HasTree()) {
                     this.treeCount++;
                  }

                  if (this.jobType != IsoChunk.JobType.SoftReset) {
                     ErosionMain.LoadGridsquare(square);
                  }

                  if (this.addZombies) {
                     MapObjects.newGridSquare(square);
                  }

                  MapObjects.loadGridSquare(square);
                  if (this.isNewChunk()) {
                     this.addRatsAfterLoading(square);
                  }

                  try {
                     LuaEventManager.triggerEvent("LoadGridsquare", square);
                  } catch (Throwable t) {
                     ExceptionLogger.logException(t);
                  }
               }

               if (square != null && !square.getStaticMovingObjects().isEmpty()) {
                  for (int i = 0; i < square.getStaticMovingObjects().size(); i++) {
                     IsoMovingObject obj = (IsoMovingObject)square.getStaticMovingObjects().get(i);
                     obj.addToWorld();
                  }
               }
            }
         }
      }

      if (this.jobType != IsoChunk.JobType.SoftReset) {
         ErosionMain.ChunkLoaded(this);
      }

      if (this.jobType != IsoChunk.JobType.SoftReset) {
         SGlobalObjects.chunkLoaded(this.wx, this.wy);
      }

      ReanimatedPlayers.instance.addReanimatedPlayersToChunk(this);
      Guard.assertGameThread("pathfinding registration (chunk load)"); // pzopt: dev-build check, see design Decision 6
      if (this.jobType != IsoChunk.JobType.SoftReset) {
         MapCollisionData.instance.addChunkToWorld(this);
         AnimalPopulationManager.getInstance().addChunkToWorld(this);
         ZombiePopulationManager.instance.addChunkToWorld(this);
         if (PathfindNative.useNativeCode) {
            PathfindNative.instance.addChunkToWorld(this);
         } else {
            PolygonalMap2.instance.addChunkToWorld(this);
         }

         IsoGenerator.chunkLoaded(this);
         LootRespawn.chunkLoaded(this);
      }

      if (!GameServer.server) {
         ArrayList<IsoRoomLight> roomLightsWorld = IsoWorld.instance.currentCell.roomLights;

         for (int i = 0; i < this.roomLights.size(); i++) {
            IsoRoomLight roomLight = this.roomLights.get(i);
            if (!roomLightsWorld.contains(roomLight)) {
               roomLightsWorld.add(roomLight);
            }
         }
      }

      this.roomLights.clear();
      if (this.jobType != IsoChunk.JobType.SoftReset) {
         tempBuildingDefs.clear();
         IsoWorld.instance.metaGrid.getBuildingsIntersecting(this.wx * 8 - 1, this.wy * 8 - 1, 10, 10, tempBuildingDefs);
         tempBuildings.clear();

         for (int i = 0; i < tempBuildingDefs.size(); i++) {
            BuildingDef buildingDef = tempBuildingDefs.get(i);
            ArrayList<RoomDef> rooms = buildingDef.getRooms();
            if (buildingDef.getRooms().isEmpty()) {
               rooms = buildingDef.getEmptyOutside();
            }

            if (!rooms.isEmpty()) {
               RoomDef roomDef = rooms.get(0);
               if (roomDef.getIsoRoom() == null) {
                  boolean var43 = true;
               } else {
                  IsoBuilding building = roomDef.getIsoRoom().getBuilding();
                  tempBuildings.add(building);
               }
            }
         }

         this.randomizeBuildingsEtc(tempBuildings);
         if (!GameServer.server) {
            VisibilityPolygon2.getInstance().addChunkToWorld(this);
         }
      }

      for (int zz = this.minLevel; zz <= this.maxLevel; zz++) {
         for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
               this.loadGridSquareIfNeeded(this.getGridSquare(x, y, zz));
            }
         }
      }

      this.checkAdjacentChunks();

      try {
         if (GameServer.server && this.jobType != IsoChunk.JobType.SoftReset) {
            for (int i = 0; i < GameServer.udpEngine.connections.size(); i++) {
               UdpConnection connection = (UdpConnection)GameServer.udpEngine.connections.get(i);
               if (!connection.chunkObjectStateRequests.isEmpty()) {
                  for (int j = 0; j < connection.chunkObjectStateRequests.size(); j += 2) {
                     short wx1 = connection.chunkObjectStateRequests.get(j);
                     short wy1 = connection.chunkObjectStateRequests.get(j + 1);
                     if (wx1 == this.wx && wy1 == this.wy) {
                        connection.chunkObjectStateRequests.remove(j, 2);
                        j -= 2;
                        ByteBufferWriter b = connection.startPacket();
                        PacketType.ChunkObjectStateResponse.doPacket(b);
                        b.putShort(this.wx);
                        b.putShort(this.wy);

                        try {
                           if (this.saveObjectState(b.bb)) {
                              PacketType.ChunkObjectStateResponse.send(connection);
                           } else {
                              connection.cancelPacket();
                           }
                        } catch (Throwable t) {
                           DebugType.General.printException(t, LogSeverity.Error);
                           connection.cancelPacket();
                        }
                     }
                  }
               }
            }
         }

         if (GameClient.client) {
            GameClient.connection.addChunkObjectState((short)this.wx);
            GameClient.connection.addChunkObjectState((short)this.wy);
         }
      } catch (Throwable t) {
         ExceptionLogger.logException(t);
      }

      this.loadedFrame = IsoWorld.instance.getFrameNo();
      this.renderFrame = this.loadedFrame + frameDelay;
      frameDelay = (frameDelay + 1) % 5;
      this.preventHotSave = false;
      LuaEventManager.triggerEvent("LoadChunk", this);
   }

   private void loadGridSquareIfNeeded(IsoGridSquare square) {
      if (square != null && !square.getObjects().isEmpty()) {
         try {
            LoadGridsquarePerformanceWorkaround.LoadGridsquare(square);
         } catch (Throwable t) {
            ExceptionLogger.logException(t);
         }
      }
   }

   private void addRatsAfterLoading(IsoGridSquare square) {
      Zone zone = square.getZone();
      boolean canHaveVermin = this.addZombies && square.hasTrash() && SandboxOptions.instance.getCurrentRatIndex() > 0 && square.canSpawnVermin();
      boolean allowRaccoons = true;
      int ratChance = 400;
      if (Objects.equals(square.getSquareZombiesType(), "StreetPoor") || Objects.equals(square.getZoneType(), "TrailerPark")) {
         ratChance /= 2;
      }

      if (Objects.equals(square.getSquareZombiesType(), "Rich") || Objects.equals(square.getLootZone(), "Rich")) {
         ratChance *= 2;
      }

      if (square.getZ() < 0) {
         ratChance /= 2;
      }

      if (canHaveVermin && Rand.Next(ratChance) < SandboxOptions.instance.getCurrentRatIndex()) {
         boolean mice = !square.isOutside() && Rand.NextBool(3);
         int max = SandboxOptions.instance.getCurrentRatIndex() / 10;
         if (Objects.equals(square.getSquareZombiesType(), "StreetPoor") || Objects.equals(square.getZoneType(), "TrailerPark")) {
            max *= 2;
         }

         if (max < 1) {
            max = 1;
         }

         if (max > 7) {
            max = 7;
         }

         int nbrOfRats = Rand.Next(1, max);
         String type = "rat";
         String breed = "grey";
         if (mice) {
            type = "mouse";
            breed = "deer";
         }

         if (square.getBuilding() != null
            && (
               square.getBuilding().hasRoom("laboratory")
                  || square.getBuilding().hasRoom("classroom")
                  || square.getBuilding().hasRoom("secondaryclassroom")
                  || Objects.equals(square.getZombiesType(), "University")
            )
            && !Rand.NextBool(3)) {
            breed = "white";
         }

         if (square.isFree(true)) {
            String type2 = type;
            if (type.equals("rat") && Rand.NextBool(2)) {
               type2 = "ratfemale";
            }

            if (type.equals("mouse") && Rand.NextBool(2)) {
               type2 = "mousefemale";
            }

            IsoAnimal animal = new IsoAnimal(IsoWorld.instance.getCell(), square.getX(), square.getY(), square.getZ(), type2, breed);
            animal.addToWorld();
            animal.randomizeAge();
            IsoGridSquare sq2 = square.getAdjacentSquare(IsoDirections.getRandom());
            if (Rand.NextBool(3)) {
               animal.setStateEventDelayTimer(0.0F);
            } else if (sq2 != null && sq2.isFree(true) && sq2.isSolidFloor() && square.canReachTo(sq2)) {
               animal.fleeTo(sq2);
            }
         }

         ArrayList<IsoGridSquare> usedSquares = new ArrayList<>();

         for (int i = 0; i < nbrOfRats; i++) {
            IsoGridSquare sq = square.getAdjacentSquare(IsoDirections.getRandom());
            if (sq != null && sq.isFree(true) && sq.isSolidFloor() && !usedSquares.contains(sq)) {
               IsoAnimal animal = new IsoAnimal(IsoWorld.instance.getCell(), sq.getX(), sq.getY(), sq.getZ(), type, breed);
               animal.addToWorld();
               animal.randomizeAge();
               IsoGridSquare sq2 = square.getAdjacentSquare(IsoDirections.getRandom());
               if (Rand.NextBool(3)) {
                  animal.setStateEventDelayTimer(0.0F);
               } else if (sq2 != null && sq2.isFree(true) && sq2.isSolidFloor() && !usedSquares.contains(sq2) && sq.canReachTo(sq2)) {
                  animal.fleeTo(sq2);
               } else {
                  usedSquares.add(sq);
               }
            }
         }

         int nbrOfPoops = Rand.Next(0, max);

         for (int i = 0; i < nbrOfPoops; i++) {
            IsoGridSquare sq = square.getAdjacentSquare(IsoDirections.getRandom());
            if (sq != null && sq.isFree(true) && sq.isSolidFloor()) {
               if (mice) {
                  this.addItemOnGround(sq, "Base.Dung_Mouse");
               } else {
                  this.addItemOnGround(sq, "Base.Dung_Rat");
               }
            }
         }

         IsoObject trashCan = square.getTrashReceptacle();
         if (trashCan != null) {
            nbrOfPoops = Rand.Next(0, max);

            for (int i = 0; i < nbrOfPoops; i++) {
               InventoryItem poop = InventoryItemFactory.CreateItem("Base.Dung_Rat");
               if (mice) {
                  poop = InventoryItemFactory.CreateItem("Base.Dung_Mouse");
               }

               trashCan.getContainer().addItem(poop);
            }
         }
      } else if (canHaveVermin && square.isOutside() && Rand.Next(600) < SandboxOptions.instance.getCurrentRatIndex() && !square.isVehicleIntersecting()) {
         String type = "raccoonboar";
         if (Rand.NextBool(2)) {
            type = "raccoonsow";
         }

         String breed = "grey";
         if (square.isFree(true)) {
            IsoAnimal animal = new IsoAnimal(IsoWorld.instance.getCell(), square.getX(), square.getY(), square.getZ(), type, "grey");
            animal.addToWorld();
            animal.randomizeAge();
            IsoGridSquare sq2 = square.getAdjacentSquare(IsoDirections.getRandom());
            if (Rand.NextBool(3)) {
               animal.setStateEventDelayTimer(0.0F);
            } else if (sq2 != null && sq2.isFree(true) && sq2.isSolidFloor() && square.canReachTo(sq2)) {
               animal.fleeTo(sq2);
            }
         }
      }
   }

   private void CheckGrassRegrowth() {
      IsoMetaChunk metaChunk = IsoWorld.instance.getMetaChunk(this.wx, this.wy);
      if (metaChunk != null) {
         for (int i = 0; i < metaChunk.getZonesSize(); i++) {
            Zone zone = metaChunk.getZone(i);
            if ("GrassRegrowth".equals(zone.getType()) && zone.getLastActionTimestamp() > 0) {
               int time = Long.valueOf(GameTime.instance.getCalender().getTimeInMillis() / 1000L).intValue() - zone.getLastActionTimestamp();
               time = time / 60 / 60;
               if (time >= SandboxOptions.instance.animalGrassRegrowTime.getValue()) {
                  IsoGridSquare sq = IsoWorld.instance.getCell().getGridSquare(zone.x, zone.y, zone.z);
                  IsoGridSquare sq2 = IsoWorld.instance.getCell().getGridSquare(zone.x + zone.getWidth(), zone.y + zone.getHeight(), zone.z);
                  if (sq != null && sq2 != null) {
                     zone.setLastActionTimestamp(0);

                     for (int x = zone.x; x < zone.x + zone.getWidth(); x++) {
                        for (int y = zone.y; y < zone.y + zone.getHeight(); y++) {
                           sq = IsoWorld.instance.getCell().getGridSquare(x, y, zone.z);
                           if (sq != null && sq.getFloor() != null && sq.getFloor().getAttachedAnimSprite() != null) {
                              for (int j = 0; j < sq.getFloor().getAttachedAnimSprite().size(); j++) {
                                 IsoSprite sprite = ((IsoSpriteInstance)sq.getFloor().getAttachedAnimSprite().get(j)).parentSprite;
                                 if ("blends_natural_01_87".equals(sprite.getName())) {
                                    sq.getFloor().RemoveAttachedAnim(j);
                                    break;
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
   }

   private void randomizeBuildingsEtc(ArrayList<IsoBuilding> buildings) {
      int chunksPerWidth = 8;
      tempRoomDefs.clear();
      IsoWorld.instance.metaGrid.getRoomsIntersecting(this.wx * 8 - 1, this.wy * 8 - 1, 9, 9, tempRoomDefs);

      for (int i = 0; i < tempRoomDefs.size(); i++) {
         IsoRoom room = tempRoomDefs.get(i).getIsoRoom();
         if (room != null) {
            IsoBuilding building = room.getBuilding();
            if (!buildings.contains(building)) {
               buildings.add(building);
            }
         }
      }

      for (int i = 0; i < buildings.size(); i++) {
         IsoBuilding building = buildings.get(i);
         if (!GameClient.client && building != null && building.def != null && building.def.isFullyStreamedIn()) {
            StashSystem.doBuildingStash(building.def);
            if (building.def != null && StashSystem.isStashBuilding(building.def)) {
               StashSystem.visitedBuilding(building.def);
            }
         }

         RandomizedBuildingBase.ChunkLoaded(building);
      }

      if (!GameClient.client && !buildings.isEmpty()) {
         for (int i = 0; i < buildings.size(); i++) {
            IsoBuilding building = buildings.get(i);

            for (int j = 0; j < building.rooms.size(); j++) {
               IsoRoom room = (IsoRoom)building.rooms.get(j);
               if (room.def.doneSpawn
                  && !this.isSpawnedRoom(room.def.id)
                  && VirtualZombieManager.instance.shouldSpawnZombiesOnLevel(room.def.level)
                  && room.def.intersects(this.wx * 8, this.wy * 8, 8, 8)) {
                  this.addSpawnedRoom(room.def.id);
                  VirtualZombieManager.instance.addIndoorZombiesToChunk(this, room);
               }
            }
         }
      }
   }

   private void checkAdjacentChunks() {
      IsoCell cell = IsoWorld.instance.currentCell;

      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            if (dx != 0 || dy != 0) {
               IsoChunk adjacent = cell.getChunk(this.wx + dx, this.wy + dy);
               if (adjacent != null) {
                  adjacent.adjacentChunkLoadedCounter++;
                  // pzopt: seamDirections, the side this chunk is on as seen from the neighbour: SeamFix2 reads only the
                  // squares south (0,+1) and east (+1,0) of a chunk, so only those neighbours' seams wait for this one
                  adjacent.pzoptSeamDirs |= (dx == -1 && dy == 0 || dx == 0 && dy == -1) ? 1 : 2; // pzopt
               }
            }
         }
      }
   }

   private void AddZombieZoneStory() {
      IsoMetaChunk metaChunk = IsoWorld.instance.getMetaChunk(this.wx, this.wy);
      if (metaChunk != null) {
         for (int i = 0; i < metaChunk.getZonesSize(); i++) {
            Zone zone = metaChunk.getZone(i);
            RandomizedZoneStoryBase.isValidForStory(zone, false);
         }
      }
   }

   private void AddRanchAnimals() {
      IsoMetaChunk metaChunk = IsoWorld.instance.getMetaChunk(this.wx, this.wy);
      if (metaChunk != null) {
         for (int i = 0; i < metaChunk.getZonesSize(); i++) {
            Zone zone = metaChunk.getZone(i);
            RandomizedRanchBase.checkRanchStory(zone, false);
         }
      }
   }

   public void setCache() {
      IsoWorld.instance.currentCell.setCacheChunk(this);
   }

   private static IsoChunk.ChunkLock acquireLock(int wx, int wy) {
      synchronized (Locks) {
         for (int i = 0; i < Locks.size(); i++) {
            if (Locks.get(i).wx == wx && Locks.get(i).wy == wy) {
               return Locks.get(i).ref();
            }
         }

         IsoChunk.ChunkLock lock = FreeLocks.isEmpty() ? new IsoChunk.ChunkLock(wx, wy) : FreeLocks.pop().set(wx, wy);
         Locks.add(lock);
         return lock.ref();
      }
   }

   private static void releaseLock(IsoChunk.ChunkLock lock) {
      synchronized (Locks) {
         if (lock.deref() == 0) {
            Locks.remove(lock);
            FreeLocks.push(lock);
         }
      }
   }

   public void setCacheIncludingNull() {
   }

   public void Save(boolean bPreventChunkReuse) throws IOException {
      this.requiresHotSave = false;
      if (!Core.getInstance().isNoSave() && !GameClient.client) {
         synchronized (WriteLock) {
            sanityCheck.beginSave(this);

            try {
               File testDir = ChunkMapFilenames.instance.getDir(Core.gameSaveWorld);
               if (!testDir.exists()) {
                  testDir.mkdir();
               }

               sliceBuffer = this.Save(sliceBuffer, crcSave, false);
               if (!GameClient.client && !GameServer.server) {
                  SafeWrite(this.wx, this.wy, sliceBuffer);
               } else {
                  long crc = ChunkChecksum.getChecksumIfExists(this.wx, this.wy);
                  crcSave.reset();
                  crcSave.update(sliceBuffer.array(), 0, sliceBuffer.position());
                  if (crc != crcSave.getValue()) {
                     ChunkChecksum.setChecksum(this.wx, this.wy, crcSave.getValue());
                     SafeWrite(this.wx, this.wy, sliceBuffer);
                  }
               }

               if (!bPreventChunkReuse && !GameServer.server) {
                  if (this.jobType != IsoChunk.JobType.Convert) {
                     WorldReuserThread.instance.addReuseChunk(this);
                  } else {
                     this.doReuseGridsquares();
                  }
               }
            } finally {
               sanityCheck.endSave(this);
            }
         }
      } else {
         if (!bPreventChunkReuse && !GameServer.server && this.jobType != IsoChunk.JobType.Convert) {
            WorldReuserThread.instance.addReuseChunk(this);
         }
      }
   }

   public static void SafeWrite(int wx, int wy, ByteBuffer bb) throws IOException {
      if (!Core.getInstance().isNoSave()) {
         IsoChunk.ChunkLock lock = acquireLock(wx, wy);
         lock.lockForWriting();

         try {
            File outFile = ChunkMapFilenames.instance.getFilename(wx, wy);
            sanityCheck.beginSaveFile(outFile.getAbsolutePath());
            if (!Files.isDirectory(Path.of(outFile.getParent()))) {
               try {
                  Files.createDirectories(Path.of(outFile.getParent()));
               } catch (IOException e) {
                  DebugType.General.printException(e, "", LogSeverity.Error);
               }
            }

            try (FileOutputStream output = new FileOutputStream(outFile)) {
               output.getChannel().truncate(0L);
               output.write(bb.array(), 0, bb.position());
            } finally {
               sanityCheck.endSaveFile();
            }
         } finally {
            lock.unlockForWriting();
            releaseLock(lock);
         }
      }
   }

   public static ByteBuffer SafeRead(int wx, int wy, ByteBuffer bb) throws IOException {
      IsoChunk.ChunkLock lock = acquireLock(wx, wy);
      lock.lockForReading();

      try {
         File inFile = ChunkMapFilenames.instance.getFilename(wx, wy);
         if (inFile == null) {
            inFile = ZomboidFileSystem.instance.getFileInCurrentSave(wx + File.separator + wy + ".bin");
         }

         sanityCheck.beginLoadFile(inFile.getAbsolutePath());

         try (FileInputStream inStream = new FileInputStream(inFile)) {
            bb = ensureCapacity(bb, (int)inFile.length());
            bb.clear();
            int len = inStream.read(bb.array());
            bb.limit(PZMath.max(len, 0));
         } finally {
            sanityCheck.endLoadFile(inFile.getAbsolutePath());
         }
      } finally {
         lock.unlockForReading();
         releaseLock(lock);
      }

      return bb;
   }

   public void SaveLoadedChunk(zombie.network.ClientChunkRequest.Chunk ccrc, CRC32 crc32) throws IOException {
      ccrc.bb = this.Save(ccrc.bb, crc32, false);
   }

   public static boolean IsDebugSave() {
      return !Core.debug ? false : false;
   }

   public ByteBuffer Save(ByteBuffer bb, CRC32 crc, boolean bHotSave) throws IOException {
      bb.rewind();
      bb = ensureCapacity(bb);
      bb.clear();
      bb.put((byte)(IsDebugSave() ? 1 : 0));
      bb.putInt(249);
      bb.putInt(0);
      bb.putLong(0L);
      bb.put((byte)(this.blendingDoneFull ? 1 : 0));
      this.writeFlags(bb, this.blendingModified);
      bb.put((byte)(this.blendingDonePartial ? 1 : 0));
      if (!Arrays.equals(this.blendingModified, comparatorBool4) && this.blendingDonePartial) {
         for (int i = 0; i < 4; i++) {
            bb.put(this.blendingDepth[i]);
         }
      }

      bb.put((byte)(this.attachmentsDoneFull ? 1 : 0));
      this.writeFlags(bb, this.attachmentsState);
      if (this.attachmentsPartial == null) {
         bb.putShort((short)0);
      } else {
         bb.putShort((short)this.attachmentsPartial.size());

         for (SquareCoord coord : this.attachmentsPartial) {
            coord.save(bb);
         }
      }

      int count = Math.min(1000, this.floorBloodSplats.size());
      int start = this.floorBloodSplats.size() - count;
      int positionMinMaxLevel = bb.position();
      bb.putInt(this.maxLevel);
      bb.putInt(this.minLevel);
      bb.putInt(count);

      for (int n = start; n < this.floorBloodSplats.size(); n++) {
         IsoFloorBloodSplat s = (IsoFloorBloodSplat)this.floorBloodSplats.get(n);
         s.save(bb);
      }

      int position = bb.position();
      int minLevel1 = Integer.MAX_VALUE;
      int maxLevel1 = Integer.MIN_VALUE;

      for (int x = 0; x < 8; x++) {
         for (int y = 0; y < 8; y++) {
            int flagPos = bb.position();
            bb.putLong(0L);
            long flags = 0L;

            for (int z = this.minLevel; z <= this.maxLevel; z++) {
               IsoGridSquare gs = this.getGridSquare(x, y, z);
               bb = ensureCapacity(bb);
               if (gs != null && gs.shouldSave()) {
                  flags |= 1L << z + 32;
                  minLevel1 = PZMath.min(minLevel1, z);
                  maxLevel1 = PZMath.max(maxLevel1, z);
                  int pos = bb.position();

                  while (true) {
                     try {
                        gs.save(bb, null, IsDebugSave());
                        break;
                     } catch (BufferOverflowException ex) {
                        DebugLog.log("IsoChunk.Save: BufferOverflowException, growing ByteBuffer");
                        bb = ensureCapacity(bb);
                        bb.position(pos);
                     }
                  }
               }
            }

            int oldPos = bb.position();
            bb.position(flagPos);
            bb.putLong(flags);
            bb.position(oldPos);
         }
      }

      if (minLevel1 <= maxLevel1) {
         int position1 = bb.position();
         bb.position(positionMinMaxLevel);
         bb.putInt(maxLevel1);
         bb.putInt(minLevel1);
         bb.position(position1);
      }

      bb = ensureCapacity(bb);
      this.getErosionData().save(bb);
      if (this.generatorsTouchingThisChunk == null) {
         bb.putShort((short)0);
      } else {
         bb.putShort((short)this.generatorsTouchingThisChunk.size());

         for (int i = 0; i < this.generatorsTouchingThisChunk.size(); i++) {
            Location pos = this.generatorsTouchingThisChunk.get(i);
            bb.putInt(pos.x);
            bb.putInt(pos.y);
            bb.put((byte)pos.z);
         }
      }

      bb.putShort((short)0);
      if (!bHotSave && (!GameServer.server || GameServer.softReset) && !GameClient.client && !GameWindow.loadedAsClient) {
         VehiclesDB2.instance.unloadChunk(this);
      }

      if (GameClient.client) {
         int respawnEveryHours = SandboxOptions.instance.hoursForLootRespawn.getValue();
         if (respawnEveryHours > 0 && !(GameTime.getInstance().getWorldAgeHours() < respawnEveryHours)) {
            this.lootRespawnHour = 7 + (int)(GameTime.getInstance().getWorldAgeHours() / respawnEveryHours) * respawnEveryHours;
         } else {
            this.lootRespawnHour = -1;
         }
      }

      bb.putInt(this.lootRespawnHour);
      assert this.spawnedRooms.size() <= 32767;
      bb.putShort((short)PZMath.min(this.spawnedRooms.size(), 32767));

      for (int i = 0; i < this.spawnedRooms.size(); i++) {
         bb.putLong(this.spawnedRooms.get(i));
      }

      int len = bb.position();
      crc.reset();
      crc.update(bb.array(), 17, len - 1 - 4 - 4 - 8);
      bb.position(5);
      bb.putInt(len);
      bb.putLong(crc.getValue());
      bb.position(len);
      return bb;
   }

   public boolean saveObjectState(ByteBuffer bb) throws IOException {
      int chunksPerWidth = 8;
      boolean empty = true;

      for (int z = 0; z < this.maxLevel; z++) {
         for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
               IsoGridSquare square = this.getGridSquare(x, y, z);
               if (square != null) {
                  int numObjects = square.getObjects().size();
                  IsoObject[] objects = (IsoObject[])square.getObjects().getElements();

                  for (int i = 0; i < numObjects; i++) {
                     IsoObject obj = objects[i];
                     int pos1 = bb.position();
                     bb.position(pos1 + 2 + 2 + 4 + 2);
                     int pos2 = bb.position();
                     obj.saveState(bb);
                     int pos3 = bb.position();
                     if (pos3 > pos2) {
                        bb.position(pos1);
                        bb.putShort((short)(x + y * 8 + z * 8 * 8));
                        bb.putShort((short)i);
                        bb.putInt(obj.getObjectName().hashCode());
                        bb.putShort((short)(pos3 - pos2));
                        bb.position(pos3);
                        empty = false;
                     } else {
                        bb.position(pos1);
                     }
                  }
               }
            }
         }
      }

      if (empty) {
         return false;
      }

      bb.putShort((short)-1);
      return true;
   }

   public void loadObjectState(ByteBuffer bb) throws IOException {
      int chunksPerWidth = 8;

      for (short xyz = bb.getShort(); xyz != -1; xyz = bb.getShort()) {
         int x = xyz % 8;
         int z = xyz / 64;
         int y = (xyz - z * 8 * 8) / 8;
         short index = bb.getShort();
         int hashCode = bb.getInt();
         short dataLen = bb.getShort();
         int pos1 = bb.position();
         IsoGridSquare square = this.getGridSquare(x, y, z);
         if (square != null && index >= 0 && index < square.getObjects().size()) {
            IsoObject obj = (IsoObject)square.getObjects().get(index);
            if (hashCode == obj.getObjectName().hashCode()) {
               obj.loadState(bb);
               assert bb.position() == pos1 + dataLen;
            } else {
               bb.position(pos1 + dataLen);
            }
         } else {
            bb.position(pos1 + dataLen);
         }
      }
   }

   public void Blam(int wx, int wy) {
      for (int z = 0; z < this.maxLevel; z++) {
         for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
               this.setSquare(x, y, z, null);
            }
         }
      }

      this.blam = true;
   }

   private void BackupBlam(int wx, int wy, Exception ex) {
      File blamDir = ZomboidFileSystem.instance.getFileInCurrentSave("blam");
      blamDir.mkdirs();

      try {
         if (!Files.isDirectory(Path.of(blamDir + File.separator + wx))) {
            try {
               Files.createDirectories(Path.of(blamDir + File.separator + wx));
            } catch (IOException e) {
               DebugType.General.printException(e, "", LogSeverity.Error);
            }
         }

         File errorFile = new File(blamDir + File.separator + wx + File.separator + wy + "_error.txt");
         FileOutputStream fileStream = new FileOutputStream(errorFile);
         PrintStream printStream = new PrintStream(fileStream);
         ex.printStackTrace(printStream);
         printStream.close();
      } catch (Exception ex2) {
         DebugType.General.printException(ex2, LogSeverity.Error);
      }

      File sourceFile = ZomboidFileSystem.instance.getFileInCurrentSave("map" + File.separator + wx, wy + ".bin");
      if (sourceFile.exists()) {
         File destFile = new File(blamDir.getPath() + File.separator + wx + File.separator + wy + ".bin");

         try {
            copyFile(sourceFile, destFile);
         } catch (Exception ex2) {
            DebugType.General.printException(ex2, LogSeverity.Error);
         }
      }
   }

   private static void copyFile(File sourceFile, File destFile) throws IOException {
      if (!destFile.exists()) {
         destFile.createNewFile();
      }

      FileChannel source = null;
      FileChannel destination = null;

      try {
         source = new FileInputStream(sourceFile).getChannel();
         destination = new FileOutputStream(destFile).getChannel();
         destination.transferFrom(source, 0L, source.size());
      } finally {
         if (source != null) {
            source.close();
         }

         if (destination != null) {
            destination.close();
         }
      }
   }

   public Chunk getErosionData() {
      if (this.erosion == null) {
         this.erosion = new Chunk();
      }

      return this.erosion;
   }

   private static int newtiledefinitions(int tilesetNumber, int tileID) {
      int fileNumber = 1;
      return 110000 + tilesetNumber * 1000 + tileID;
   }

   public static int Fix2x(IsoGridSquare square, int spriteID) {
      if (square == null || square.chunk == null) {
         return spriteID;
      }

      if (square.chunk.fixed2x) {
         return spriteID;
      }

      HashMap<String, IsoSprite> namedMap = IsoSpriteManager.instance.namedMap;
      if (spriteID >= newtiledefinitions(140, 48) && spriteID <= newtiledefinitions(140, 51)) {
         return -1;
      }

      if (spriteID >= newtiledefinitions(8, 14) && spriteID <= newtiledefinitions(8, 71) && spriteID % 8 >= 6) {
         return -1;
      }

      if (spriteID == newtiledefinitions(92, 2)) {
         return spriteID + 20;
      }

      if (spriteID == newtiledefinitions(92, 20)) {
         return spriteID + 1;
      }

      if (spriteID == newtiledefinitions(92, 21)) {
         return spriteID - 1;
      }

      if (spriteID >= newtiledefinitions(92, 26) && spriteID <= newtiledefinitions(92, 29)) {
         return spriteID + 6;
      }

      if (spriteID == newtiledefinitions(11, 16)) {
         return newtiledefinitions(11, 45);
      }

      if (spriteID == newtiledefinitions(11, 17)) {
         return newtiledefinitions(11, 43);
      }

      if (spriteID == newtiledefinitions(11, 18)) {
         return newtiledefinitions(11, 41);
      }

      if (spriteID == newtiledefinitions(11, 19)) {
         return newtiledefinitions(11, 47);
      }

      if (spriteID == newtiledefinitions(11, 24)) {
         return newtiledefinitions(11, 26);
      }

      if (spriteID == newtiledefinitions(11, 25)) {
         return newtiledefinitions(11, 27);
      }

      if (spriteID == newtiledefinitions(27, 42)) {
         return spriteID + 1;
      }

      if (spriteID == newtiledefinitions(27, 43)) {
         return spriteID - 1;
      }

      if (spriteID == newtiledefinitions(27, 44)) {
         return spriteID + 3;
      }

      if (spriteID == newtiledefinitions(27, 47)) {
         return spriteID - 2;
      }

      if (spriteID == newtiledefinitions(27, 45)) {
         return spriteID + 1;
      }

      if (spriteID == newtiledefinitions(27, 46)) {
         return spriteID - 2;
      }

      if (spriteID == newtiledefinitions(34, 4)) {
         return spriteID + 1;
      }

      if (spriteID == newtiledefinitions(34, 5)) {
         return spriteID - 1;
      }

      if (spriteID >= newtiledefinitions(14, 0) && spriteID <= newtiledefinitions(14, 7)) {
         return -1;
      }

      if (spriteID >= newtiledefinitions(14, 8) && spriteID <= newtiledefinitions(14, 12)) {
         return spriteID + 72;
      }

      if (spriteID == newtiledefinitions(14, 13)) {
         return spriteID + 71;
      }

      if (spriteID >= newtiledefinitions(14, 16) && spriteID <= newtiledefinitions(14, 17)) {
         return spriteID + 72;
      }

      if (spriteID == newtiledefinitions(14, 18)) {
         return spriteID + 73;
      }

      if (spriteID == newtiledefinitions(14, 19)) {
         return spriteID + 66;
      }

      if (spriteID == newtiledefinitions(14, 20)) {
         return -1;
      }

      if (spriteID == newtiledefinitions(14, 21)) {
         return newtiledefinitions(14, 89);
      }

      if (spriteID == newtiledefinitions(21, 0)) {
         return newtiledefinitions(125, 16);
      }

      if (spriteID == newtiledefinitions(21, 1)) {
         return newtiledefinitions(125, 32);
      }

      if (spriteID == newtiledefinitions(21, 2)) {
         return newtiledefinitions(125, 48);
      }

      if (spriteID == newtiledefinitions(26, 0)) {
         return newtiledefinitions(26, 6);
      }

      if (spriteID == newtiledefinitions(26, 6)) {
         return newtiledefinitions(26, 0);
      }

      if (spriteID == newtiledefinitions(26, 1)) {
         return newtiledefinitions(26, 7);
      }

      if (spriteID == newtiledefinitions(26, 7)) {
         return newtiledefinitions(26, 1);
      }

      if (spriteID == newtiledefinitions(26, 8)) {
         return newtiledefinitions(26, 14);
      }

      if (spriteID == newtiledefinitions(26, 14)) {
         return newtiledefinitions(26, 8);
      }

      if (spriteID == newtiledefinitions(26, 9)) {
         return newtiledefinitions(26, 15);
      }

      if (spriteID == newtiledefinitions(26, 15)) {
         return newtiledefinitions(26, 9);
      }

      if (spriteID == newtiledefinitions(26, 16)) {
         return newtiledefinitions(26, 22);
      }

      if (spriteID == newtiledefinitions(26, 22)) {
         return newtiledefinitions(26, 16);
      }

      if (spriteID == newtiledefinitions(26, 17)) {
         return newtiledefinitions(26, 23);
      }

      if (spriteID == newtiledefinitions(26, 23)) {
         return newtiledefinitions(26, 17);
      }

      if (spriteID >= newtiledefinitions(148, 0) && spriteID <= newtiledefinitions(148, 16)) {
         int id = spriteID - newtiledefinitions(148, 0);
         return newtiledefinitions(160, id);
      }

      if ((spriteID < newtiledefinitions(42, 44) || spriteID > newtiledefinitions(42, 47))
         && (spriteID < newtiledefinitions(42, 52) || spriteID > newtiledefinitions(42, 55))) {
         if (spriteID == newtiledefinitions(43, 24)) {
            return spriteID + 4;
         } else if (spriteID == newtiledefinitions(43, 26)) {
            return spriteID + 2;
         } else if (spriteID == newtiledefinitions(43, 33)) {
            return spriteID - 4;
         } else if (spriteID == newtiledefinitions(44, 0)) {
            return newtiledefinitions(44, 1);
         } else if (spriteID == newtiledefinitions(44, 1)) {
            return newtiledefinitions(44, 0);
         } else if (spriteID == newtiledefinitions(44, 2)) {
            return newtiledefinitions(44, 7);
         } else if (spriteID == newtiledefinitions(44, 3)) {
            return newtiledefinitions(44, 6);
         } else if (spriteID == newtiledefinitions(44, 4)) {
            return newtiledefinitions(44, 5);
         } else if (spriteID == newtiledefinitions(44, 5)) {
            return newtiledefinitions(44, 4);
         } else if (spriteID == newtiledefinitions(44, 6)) {
            return newtiledefinitions(44, 3);
         } else if (spriteID == newtiledefinitions(44, 7)) {
            return newtiledefinitions(44, 2);
         } else if (spriteID == newtiledefinitions(44, 16)) {
            return newtiledefinitions(44, 45);
         } else if (spriteID == newtiledefinitions(44, 17)) {
            return newtiledefinitions(44, 44);
         } else if (spriteID == newtiledefinitions(44, 18)) {
            return newtiledefinitions(44, 46);
         } else if (spriteID >= newtiledefinitions(44, 19) && spriteID <= newtiledefinitions(44, 22)) {
            return spriteID + 33;
         } else if (spriteID == newtiledefinitions(44, 23)) {
            return newtiledefinitions(44, 47);
         } else if (spriteID == newtiledefinitions(46, 8)) {
            return newtiledefinitions(46, 5);
         } else if (spriteID == newtiledefinitions(46, 14)) {
            return newtiledefinitions(46, 10);
         } else if (spriteID == newtiledefinitions(46, 15)) {
            return newtiledefinitions(46, 11);
         } else if (spriteID == newtiledefinitions(46, 22)) {
            return newtiledefinitions(46, 14);
         } else if (spriteID == newtiledefinitions(46, 23)) {
            return newtiledefinitions(46, 15);
         } else if (spriteID == newtiledefinitions(46, 54)) {
            return newtiledefinitions(46, 55);
         } else if (spriteID == newtiledefinitions(46, 55)) {
            return newtiledefinitions(46, 54);
         } else if (spriteID == newtiledefinitions(106, 32)) {
            return newtiledefinitions(106, 34);
         } else if (spriteID == newtiledefinitions(106, 34)) {
            return newtiledefinitions(106, 32);
         } else if (spriteID == newtiledefinitions(47, 0) || spriteID == newtiledefinitions(47, 4)) {
            return spriteID + 1;
         } else if (spriteID == newtiledefinitions(47, 1) || spriteID == newtiledefinitions(47, 5)) {
            return spriteID - 1;
         } else if (spriteID >= newtiledefinitions(47, 8) && spriteID <= newtiledefinitions(47, 13)) {
            return spriteID + 8;
         } else if (spriteID >= newtiledefinitions(47, 22) && spriteID <= newtiledefinitions(47, 23)) {
            return spriteID - 12;
         } else if (spriteID >= newtiledefinitions(47, 44) && spriteID <= newtiledefinitions(47, 47)) {
            return spriteID + 4;
         } else if (spriteID >= newtiledefinitions(47, 48) && spriteID <= newtiledefinitions(47, 51)) {
            return spriteID - 4;
         } else if (spriteID == newtiledefinitions(48, 56)) {
            return newtiledefinitions(48, 58);
         } else if (spriteID == newtiledefinitions(48, 58)) {
            return newtiledefinitions(48, 56);
         } else if (spriteID == newtiledefinitions(52, 57)) {
            return newtiledefinitions(52, 58);
         } else if (spriteID == newtiledefinitions(52, 58)) {
            return newtiledefinitions(52, 59);
         } else if (spriteID == newtiledefinitions(52, 45)) {
            return newtiledefinitions(52, 44);
         } else if (spriteID == newtiledefinitions(52, 46)) {
            return newtiledefinitions(52, 45);
         } else if (spriteID == newtiledefinitions(54, 13)) {
            return newtiledefinitions(54, 18);
         } else if (spriteID == newtiledefinitions(54, 15)) {
            return newtiledefinitions(54, 19);
         } else if (spriteID == newtiledefinitions(54, 21)) {
            return newtiledefinitions(54, 16);
         } else if (spriteID == newtiledefinitions(54, 22)) {
            return newtiledefinitions(54, 13);
         } else if (spriteID == newtiledefinitions(54, 23)) {
            return newtiledefinitions(54, 17);
         } else if (spriteID >= newtiledefinitions(67, 0) && spriteID <= newtiledefinitions(67, 16)) {
            int id = 64 + Rand.Next(16);
            return namedMap.get("f_bushes_1_" + id).id;
         } else if (spriteID == newtiledefinitions(68, 6)) {
            return -1;
         } else if (spriteID >= newtiledefinitions(68, 16) && spriteID <= newtiledefinitions(68, 17)) {
            return namedMap.get("d_plants_1_53").id;
         } else if (spriteID >= newtiledefinitions(68, 18) && spriteID <= newtiledefinitions(68, 23)) {
            int id = Rand.Next(4) * 16 + Rand.Next(8);
            return namedMap.get("d_plants_1_" + id).id;
         } else {
            return spriteID >= newtiledefinitions(79, 24) && spriteID <= newtiledefinitions(79, 41)
               ? newtiledefinitions(81, spriteID - newtiledefinitions(79, 24))
               : spriteID;
         }
      } else {
         return -1;
      }
   }

   public static String Fix2x(String tileName) {
      if (Fix2xMap.isEmpty()) {
         HashMap<String, String> m = Fix2xMap;

         for (int i = 48; i <= 51; i++) {
            m.put("blends_streetoverlays_01_" + i, "");
         }

         m.put("fencing_01_14", "");
         m.put("fencing_01_15", "");
         m.put("fencing_01_22", "");
         m.put("fencing_01_23", "");
         m.put("fencing_01_30", "");
         m.put("fencing_01_31", "");
         m.put("fencing_01_38", "");
         m.put("fencing_01_39", "");
         m.put("fencing_01_46", "");
         m.put("fencing_01_47", "");
         m.put("fencing_01_62", "");
         m.put("fencing_01_63", "");
         m.put("fencing_01_70", "");
         m.put("fencing_01_71", "");
         m.put("fixtures_bathroom_02_2", "fixtures_bathroom_02_22");
         m.put("fixtures_bathroom_02_20", "fixtures_bathroom_02_21");
         m.put("fixtures_bathroom_02_21", "fixtures_bathroom_02_20");

         for (int i = 26; i <= 29; i++) {
            m.put("fixtures_bathroom_02_" + i, "fixtures_bathroom_02_" + (i + 6));
         }

         m.put("fixtures_counters_01_16", "fixtures_counters_01_45");
         m.put("fixtures_counters_01_17", "fixtures_counters_01_43");
         m.put("fixtures_counters_01_18", "fixtures_counters_01_41");
         m.put("fixtures_counters_01_19", "fixtures_counters_01_47");
         m.put("fixtures_counters_01_24", "fixtures_counters_01_26");
         m.put("fixtures_counters_01_25", "fixtures_counters_01_27");

         for (int i = 0; i <= 7; i++) {
            m.put("fixtures_railings_01_" + i, "");
         }

         for (int i = 8; i <= 12; i++) {
            m.put("fixtures_railings_01_" + i, "fixtures_railings_01_" + (i + 72));
         }

         m.put("fixtures_railings_01_13", "fixtures_railings_01_84");

         for (int i = 16; i <= 17; i++) {
            m.put("fixtures_railings_01_" + i, "fixtures_railings_01_" + (i + 72));
         }

         m.put("fixtures_railings_01_18", "fixtures_railings_01_91");
         m.put("fixtures_railings_01_19", "fixtures_railings_01_85");
         m.put("fixtures_railings_01_20", "");
         m.put("fixtures_railings_01_21", "fixtures_railings_01_89");
         m.put("floors_exterior_natural_01_0", "blends_natural_01_16");
         m.put("floors_exterior_natural_01_1", "blends_natural_01_32");
         m.put("floors_exterior_natural_01_2", "blends_natural_01_48");
         m.put("floors_rugs_01_0", "floors_rugs_01_6");
         m.put("floors_rugs_01_6", "floors_rugs_01_0");
         m.put("floors_rugs_01_1", "floors_rugs_01_7");
         m.put("floors_rugs_01_7", "floors_rugs_01_1");
         m.put("floors_rugs_01_8", "floors_rugs_01_14");
         m.put("floors_rugs_01_14", "floors_rugs_01_8");
         m.put("floors_rugs_01_9", "floors_rugs_01_15");
         m.put("floors_rugs_01_15", "floors_rugs_01_9");
         m.put("floors_rugs_01_16", "floors_rugs_01_22");
         m.put("floors_rugs_01_22", "floors_rugs_01_16");
         m.put("floors_rugs_01_17", "floors_rugs_01_23");
         m.put("floors_rugs_01_23", "floors_rugs_01_17");
         m.put("furniture_bedding_01_42", "furniture_bedding_01_43");
         m.put("furniture_bedding_01_43", "furniture_bedding_01_42");
         m.put("furniture_bedding_01_44", "furniture_bedding_01_47");
         m.put("furniture_bedding_01_47", "furniture_bedding_01_45");
         m.put("furniture_bedding_01_45", "furniture_bedding_01_46");
         m.put("furniture_bedding_01_46", "furniture_bedding_01_44");
         m.put("furniture_tables_low_01_4", "furniture_tables_low_01_5");
         m.put("furniture_tables_low_01_5", "furniture_tables_low_01_4");

         for (int i = 0; i <= 5; i++) {
            m.put("location_business_machinery_" + i, "location_business_machinery_01_" + i);
            m.put("location_business_machinery_" + (i + 8), "location_business_machinery_01_" + (i + 8));
            m.put("location_ business_machinery_" + i, "location_business_machinery_01_" + i);
            m.put("location_ business_machinery_" + (i + 8), "location_business_machinery_01_" + (i + 8));
         }

         for (int i = 44; i <= 47; i++) {
            m.put("location_hospitality_sunstarmotel_01_" + i, "");
         }

         for (int i = 52; i <= 55; i++) {
            m.put("location_hospitality_sunstarmotel_01_" + i, "");
         }

         m.put("location_hospitality_sunstarmotel_02_24", "location_hospitality_sunstarmotel_02_28");
         m.put("location_hospitality_sunstarmotel_02_26", "location_hospitality_sunstarmotel_02_28");
         m.put("location_hospitality_sunstarmotel_02_33", "location_hospitality_sunstarmotel_02_29");
         m.put("location_restaurant_bar_01_0", "location_restaurant_bar_01_1");
         m.put("location_restaurant_bar_01_1", "location_restaurant_bar_01_0");
         m.put("location_restaurant_bar_01_2", "location_restaurant_bar_01_7");
         m.put("location_restaurant_bar_01_3", "location_restaurant_bar_01_6");
         m.put("location_restaurant_bar_01_4", "location_restaurant_bar_01_5");
         m.put("location_restaurant_bar_01_5", "location_restaurant_bar_01_4");
         m.put("location_restaurant_bar_01_6", "location_restaurant_bar_01_3");
         m.put("location_restaurant_bar_01_7", "location_restaurant_bar_01_2");
         m.put("location_restaurant_bar_01_16", "location_restaurant_bar_01_45");
         m.put("location_restaurant_bar_01_17", "location_restaurant_bar_01_44");
         m.put("location_restaurant_bar_01_18", "location_restaurant_bar_01_46");

         for (int i = 19; i <= 22; i++) {
            m.put("location_restaurant_bar_01_" + i, "location_restaurant_bar_01_" + (i + 33));
         }

         m.put("location_restaurant_bar_01_23", "location_restaurant_bar_01_47");
         m.put("location_restaurant_pie_01_8", "location_restaurant_pie_01_5");
         m.put("location_restaurant_pie_01_14", "location_restaurant_pie_01_10");
         m.put("location_restaurant_pie_01_15", "location_restaurant_pie_01_11");
         m.put("location_restaurant_pie_01_22", "location_restaurant_pie_01_14");
         m.put("location_restaurant_pie_01_23", "location_restaurant_pie_01_15");
         m.put("location_restaurant_pie_01_54", "location_restaurant_pie_01_55");
         m.put("location_restaurant_pie_01_55", "location_restaurant_pie_01_54");
         m.put("location_pizzawhirled_01_32", "location_pizzawhirled_01_34");
         m.put("location_pizzawhirled_01_34", "location_pizzawhirled_01_32");
         m.put("location_restaurant_seahorse_01_0", "location_restaurant_seahorse_01_1");
         m.put("location_restaurant_seahorse_01_1", "location_restaurant_seahorse_01_0");
         m.put("location_restaurant_seahorse_01_4", "location_restaurant_seahorse_01_5");
         m.put("location_restaurant_seahorse_01_5", "location_restaurant_seahorse_01_4");

         for (int i = 8; i <= 13; i++) {
            m.put("location_restaurant_seahorse_01_" + i, "location_restaurant_seahorse_01_" + (i + 8));
         }

         for (int i = 22; i <= 23; i++) {
            m.put("location_restaurant_seahorse_01_" + i, "location_restaurant_seahorse_01_" + (i - 12));
         }

         for (int i = 44; i <= 47; i++) {
            m.put("location_restaurant_seahorse_01_" + i, "location_restaurant_seahorse_01_" + (i + 4));
         }

         for (int i = 48; i <= 51; i++) {
            m.put("location_restaurant_seahorse_01_" + i, "location_restaurant_seahorse_01_" + (i - 4));
         }

         m.put("location_restaurant_spiffos_01_56", "location_restaurant_spiffos_01_58");
         m.put("location_restaurant_spiffos_01_58", "location_restaurant_spiffos_01_56");
         m.put("location_shop_fossoil_01_45", "location_shop_fossoil_01_44");
         m.put("location_shop_fossoil_01_46", "location_shop_fossoil_01_45");
         m.put("location_shop_fossoil_01_57", "location_shop_fossoil_01_58");
         m.put("location_shop_fossoil_01_58", "location_shop_fossoil_01_59");
         m.put("location_shop_greenes_01_13", "location_shop_greenes_01_18");
         m.put("location_shop_greenes_01_15", "location_shop_greenes_01_19");
         m.put("location_shop_greenes_01_21", "location_shop_greenes_01_16");
         m.put("location_shop_greenes_01_22", "location_shop_greenes_01_13");
         m.put("location_shop_greenes_01_23", "location_shop_greenes_01_17");
         m.put("location_shop_greenes_01_67", "location_shop_greenes_01_70");
         m.put("location_shop_greenes_01_68", "location_shop_greenes_01_67");
         m.put("location_shop_greenes_01_70", "location_shop_greenes_01_71");
         m.put("location_shop_greenes_01_75", "location_shop_greenes_01_78");
         m.put("location_shop_greenes_01_76", "location_shop_greenes_01_75");
         m.put("location_shop_greenes_01_78", "location_shop_greenes_01_79");

         for (int i = 0; i <= 16; i++) {
            m.put("vegetation_foliage_01_" + i, "randBush");
         }

         m.put("vegetation_groundcover_01_0", "blends_grassoverlays_01_16");
         m.put("vegetation_groundcover_01_1", "blends_grassoverlays_01_8");
         m.put("vegetation_groundcover_01_2", "blends_grassoverlays_01_0");
         m.put("vegetation_groundcover_01_3", "blends_grassoverlays_01_64");
         m.put("vegetation_groundcover_01_4", "blends_grassoverlays_01_56");
         m.put("vegetation_groundcover_01_5", "blends_grassoverlays_01_48");
         m.put("vegetation_groundcover_01_6", "");
         m.put("vegetation_groundcover_01_44", "blends_grassoverlays_01_40");
         m.put("vegetation_groundcover_01_45", "blends_grassoverlays_01_32");
         m.put("vegetation_groundcover_01_46", "blends_grassoverlays_01_24");
         m.put("vegetation_groundcover_01_16", "d_plants_1_53");
         m.put("vegetation_groundcover_01_17", "d_plants_1_53");

         for (int i = 18; i <= 23; i++) {
            m.put("vegetation_groundcover_01_" + i, "randPlant");
         }

         for (int i = 20; i <= 23; i++) {
            m.put("walls_exterior_house_01_" + i, "walls_exterior_house_01_" + (i + 12));
            m.put("walls_exterior_house_01_" + (i + 8), "walls_exterior_house_01_" + (i + 8 + 12));
         }

         for (int i = 24; i <= 41; i++) {
            m.put("walls_exterior_roofs_01_" + i, "walls_exterior_roofs_03_" + i);
         }
      }

      String s = Fix2xMap.get(tileName);
      if (s == null) {
         return tileName;
      } else if ("randBush".equals(s)) {
         int id = 64 + Rand.Next(16);
         return "f_bushes_1_" + id;
      } else if ("randPlant".equals(s)) {
         int id = Rand.Next(4) * 16 + Rand.Next(8);
         return "d_plants_1_" + id;
      } else {
         return s;
      }
   }

   public void addGeneratorPos(int x, int y, int z) {
      if (this.generatorsTouchingThisChunk == null) {
         this.generatorsTouchingThisChunk = new ArrayList<>();
      }

      for (int i = 0; i < this.generatorsTouchingThisChunk.size(); i++) {
         Location pos = this.generatorsTouchingThisChunk.get(i);
         if (pos.x == x && pos.y == y && pos.z == z) {
            return;
         }
      }

      Location pos = new Location(x, y, z);
      this.generatorsTouchingThisChunk.add(pos);
   }

   public void removeGeneratorPos(int x, int y, int z) {
      if (this.generatorsTouchingThisChunk != null) {
         for (int i = 0; i < this.generatorsTouchingThisChunk.size(); i++) {
            Location pos = this.generatorsTouchingThisChunk.get(i);
            if (pos.x == x && pos.y == y && pos.z == z) {
               this.generatorsTouchingThisChunk.remove(i);
               i--;
            }
         }
      }
   }

   public boolean isGeneratorPoweringSquare(int x, int y, int z) {
      if (this.generatorsTouchingThisChunk == null) {
         return false;
      }

      for (int i = 0; i < this.generatorsTouchingThisChunk.size(); i++) {
         Location pos = this.generatorsTouchingThisChunk.get(i);
         if (IsoGenerator.isPoweringSquare(pos.x, pos.y, pos.z, x, y, z)) {
            return true;
         }
      }

      return false;
   }

   public void checkForMissingGenerators() {
      if (this.generatorsTouchingThisChunk != null) {
         for (int i = 0; i < this.generatorsTouchingThisChunk.size(); i++) {
            Location pos = this.generatorsTouchingThisChunk.get(i);
            IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(pos.x, pos.y, pos.z);
            if (square != null) {
               IsoGenerator generator = square.getGenerator();
               if (generator == null || !generator.isActivated()) {
                  this.generatorsTouchingThisChunk.remove(i);
                  i--;
               }
            }
         }
      }
   }

   public void addObjectPoweredByGenerator(IsoObject object) {
      if (object != null && object.getSquare() != null) {
         IsoChunkLevel chunkLevel = this.getLevelData(object.getSquare().getZ());
         if (chunkLevel != null) {
            chunkLevel.addObjectPoweredByGenerator(object);
         }
      }
   }

   public void removeObjectPoweredByGenerator(IsoObject object) {
      if (object != null && object.getSquare() != null) {
         IsoChunkLevel chunkLevel = this.getLevelData(object.getSquare().getZ());
         if (chunkLevel != null) {
            chunkLevel.removeObjectPoweredByGenerator(object);
         }
      }
   }

   public boolean isNewChunk() {
      return this.addZombies;
   }

   public void addSpawnedRoom(long roomID) {
      if (!this.spawnedRooms.contains(roomID)) {
         this.spawnedRooms.add(roomID);
      }
   }

   public boolean isSpawnedRoom(long roomID) {
      return this.spawnedRooms.contains(roomID);
   }

   public Zone getScavengeZone() {
      if (this.scavengeZone != null) {
         return this.scavengeZone;
      }

      IsoMetaChunk metaChunk = IsoWorld.instance.getMetaGrid().getChunkData(this.wx, this.wy);
      if (metaChunk != null && metaChunk.getZonesSize() > 0) {
         for (int i = 0; i < metaChunk.getZonesSize(); i++) {
            Zone zone = metaChunk.getZone(i);
            if ("DeepForest".equals(zone.type) || "Forest".equals(zone.type)) {
               this.scavengeZone = zone;
               return zone;
            }

            if ("Nav".equals(zone.type) || "Town".equals(zone.type)) {
               return null;
            }
         }
      }

      int numTrees = 5;
      if (this.treeCount < 5) {
         return null;
      }

      int adjacentWithTrees = 0;

      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            if (dx != 0 || dy != 0) {
               IsoChunk adjacent = GameServer.server
                  ? ServerMap.instance.getChunk(this.wx + dx, this.wy + dy)
                  : IsoWorld.instance.currentCell.getChunk(this.wx + dx, this.wy + dy);
               if (adjacent != null && adjacent.treeCount >= 5) {
                  if (++adjacentWithTrees == 8) {
                     int chunksPerWidth = 8;
                     this.scavengeZone = new Zone("", "Forest", this.wx * 8, this.wy * 8, 0, 8, 8);
                     return this.scavengeZone;
                  }
               }
            }
         }
      }

      return null;
   }

   private void unlinkSquares(IsoChunkLevel chunkLevel) {
      for (int m = 0; m < chunkLevel.squares.length; m++) {
         IsoGridSquare sq = chunkLevel.squares[m];
         if (sq != null) {
            if (sq.getRoom() != null) {
               sq.getRoom().removeSquare(sq);
            }

            if (sq.zone != null) {
               sq.zone.removeSquare(sq);
            }

            this.disconnectFromAdjacentChunks(sq);
            sq.softClear();
            chunkLevel.squares[m] = null;
         }
      }
   }

   /**
    * pzopt: the per-frame square lists of every chunk level (items, obscuring objects, cutaway window frames,
    * corpses, flies, puddles, water). Stock empties them in FBORenderLevels.invalidate(); with
    * FBORenderCell.pzoptKeepPerFrameLists they survive invalidations so a held re-bake keeps drawing them, and a
    * chunk object going back to the pool must drop them here instead.
    */
   private void pzoptClearPerFrameLists() {
      for (int playerIndex = 0; playerIndex < 4; playerIndex++) {
         FBORenderLevels renderLevels = this.renderLevels[playerIndex];
         if (renderLevels == null) {
            continue;
         }
         for (int z = this.minLevel; z <= this.maxLevel; z++) {
            renderLevels.clearCachedSquares(z);
         }
      }
   }

   public void resetForStore() {
      loadGridSquare.remove(this);
      this.pzoptOccluderMaskSet = 0L; // pzopt: a reused chunk object starts without stored occluder masks
      this.pzoptTreeExportFp = null; // pzopt: tree export fingerprints belong to the previous chunk
      pzopt.LightDirt.chunkReused(this); // pzopt: strong-light and bake frame stamps belong to the previous chunk
      this.pzoptSeamDirs = 0; // pzopt: seamDirections
      if (pzopt.BakeScheduler.ON) pzopt.BakeScheduler.chunkReused(this); // pzopt: bakeScheduler waits belong to the previous chunk
      this.pzoptFog = null; // pzopt: fog masks belong to the previous chunk
      this.pzoptClearPerFrameLists(); // pzopt: FBORenderCell keeps them across invalidations; a reused chunk starts empty
      pzopt.PuddleCache.chunkReused(this); // pzopt: the cached puddle batches (and their GL buffers) are rebuilt for the new position
      this.randomId = 0;
      this.revision = 0L;
      this.nextSplatIndex = 0;
      this.floorBloodSplats.clear();
      this.floorBloodSplatsFade.clear();
      this.jobType = IsoChunk.JobType.None;

      for (int z = this.minLevel; z <= this.maxLevel; z++) {
         this.unlinkSquares(this.levels[z - this.minLevel]);
         this.levels[z - this.minLevel].clear();
         this.levels[z - this.minLevel].release();
         this.levels[z - this.minLevel] = null;
      }

      this.maxLevel = 0;
      this.minLevel = 0;
      this.minLevelPhysics = this.maxLevelPhysics = 1000;
      this.levels[0] = IsoChunkLevel.alloc().init(this, this.minLevel);
      this.fixed2x = false;
      this.vehicles.clear();
      this.roomLights.clear();
      this.blam = false;
      this.lotheader = null;
      this.loaded = false;
      this.addZombies = false;
      this.proceduralZombieSquares.clear();
      this.loadedPhysics = false;
      this.wx = 0;
      this.wy = 0;
      this.erosion = null;
      this.lootRespawnHour = -1;
      if (this.generatorsTouchingThisChunk != null) {
         this.generatorsTouchingThisChunk.clear();
      }

      this.soundList.clear();
      this.treeCount = 0;
      this.scavengeZone = null;
      this.numberOfWaterTiles = 0;
      this.spawnedRooms.resetQuick();
      this.adjacentChunkLoadedCounter = 0;
      this.loadedBits = 0;
      this.loadId = -1;
      this.squares = new IsoGridSquare[1][];
      this.squares[0] = this.levels[0].squares;

      for (int playerIndex = 0; playerIndex < 4; playerIndex++) {
         this.lightCheck[playerIndex] = true;
         this.lightingNeverDone[playerIndex] = true;
      }

      this.refs.clear();
      this.vehicleStorySpawnData = null;
      this.loadVehiclesObject = null;
      this.objectEmitterData.reset();
      this.blendingDoneFull = false;
      this.blendingDonePartial = false;
      Arrays.fill(this.blendingModified, false);
      this.blendingDepth[0] = BlendDirection.NORTH.defaultDepth;
      this.blendingDepth[1] = BlendDirection.SOUTH.defaultDepth;
      this.blendingDepth[2] = BlendDirection.WEST.defaultDepth;
      this.blendingDepth[3] = BlendDirection.EAST.defaultDepth;
      this.attachmentsDoneFull = true;
      Arrays.fill(this.attachmentsState, true);
      this.attachmentsPartial = null;
      this.chunkGenerationStatus = EnumSet.noneOf(ChunkGenerationStatus.class);
      this.ignorePathfind = false;
   }

   public int getNumberOfWaterTiles() {
      return this.numberOfWaterTiles;
   }

   public void setRandomVehicleStoryToSpawnLater(VehicleStorySpawnData spawnData) {
      this.vehicleStorySpawnData = spawnData;
   }

   public boolean hasObjectAmbientEmitter(IsoObject object) {
      return this.objectEmitterData.hasObject(object);
   }

   public void addObjectAmbientEmitter(IsoObject object, PerObjectLogic logic) {
      this.objectEmitterData.addObject(object, logic);
   }

   public void removeObjectAmbientEmitter(IsoObject object) {
      this.objectEmitterData.removeObject(object);
   }

   private void addItemOnGround(IsoGridSquare square, String type) {
      if (!SandboxOptions.instance.removeStoryLoot.getValue() || ItemPickerJava.getLootModifier(type) != 0.0F) {
         if (square != null && !StringUtils.isNullOrWhitespace(type)) {
            InventoryItem item = ItemSpawner.spawnItem(type, square, Rand.Next(0.2F, 0.8F), Rand.Next(0.2F, 0.8F), 0.0F);
            if (item instanceof InventoryContainer inventoryContainer && ItemPickerJava.containers.containsKey(item.getType())) {
               ItemPickerJava.rollContainerItem(inventoryContainer, null, (ItemPickerContainer)ItemPickerJava.getItemPickerContainers().get(item.getType()));
               LuaEventManager.triggerEvent("OnFillContainer", "Container", item.getType(), inventoryContainer.getItemContainer());
            }
         }
      }
   }

   public void assignLoadID() {
      if (this.loadId != -1) {
         throw new IllegalStateException("IsoChunk was already assigned a valid loadID");
      }

      this.loadId = nextLoadID++;
      if (nextLoadID == 32767) {
         nextLoadID = 0;
      }
   }

   public short getLoadID() {
      if (this.loadId == -1) {
         throw new IllegalStateException("IsoChunk.loadID is invalid");
      } else {
         return this.loadId;
      }
   }

   public boolean containsPoint(float x, float y) {
      int chunksPerWidth = 8;
      return Float.compare(x, this.wx * 8) >= 0
         && Float.compare(x, (this.wx + 1) * 8) < 0
         && Float.compare(y, this.wy * 8) >= 0
         && Float.compare(y, (this.wy + 1) * 8) < 0;
   }

   public FBORenderLevels getRenderLevels(int playerIndex) {
      if (this.renderLevels[playerIndex] == null) {
         this.renderLevels[playerIndex] = new FBORenderLevels(playerIndex, this);
      }

      return this.renderLevels[playerIndex];
   }

   public void invalidateRenderChunkLevel(int level, long dirtyFlags) {
      if (pzopt.Config.DEV_INVALIDATE_STACKS && (dirtyFlags & 192L) != 0L) pzopt.DevStacks.record("flags=" + dirtyFlags); // pzopt: dev rig
      if (PerformanceSettings.fboRenderChunk) {
         if (!GameServer.server) {
            for (int playerIndex = 0; playerIndex < 4; playerIndex++) {
               this.getRenderLevels(playerIndex).invalidateLevel(level, dirtyFlags);
            }
         }
      }
   }

   public void invalidateRenderChunkLevels(long dirtyFlags) {
      if (PerformanceSettings.fboRenderChunk) {
         if (!GameServer.server) {
            for (int playerIndex = 0; playerIndex < 4; playerIndex++) {
               this.getRenderLevels(playerIndex).invalidateAll(dirtyFlags);
            }
         }
      }
   }

   public ChunkLevelsData getCutawayData() {
      return this.cutawayData;
   }

   public ChunkLevelData getCutawayDataForLevel(int z) {
      return this.getCutawayData().getDataForLevel(z);
   }

   public void invalidateVispolyChunkLevel(int level) {
      if (!GameServer.server) {
         this.getVispolyDataForLevel(level).invalidate();
      }
   }

   public zombie.vispoly.VisibilityPolygon2.ChunkData getVispolyData() {
      return this.vispolyData;
   }

   public zombie.vispoly.VisibilityPolygon2.ChunkLevelData getVispolyDataForLevel(int z) {
      return this.getVispolyData().getDataForLevel(z);
   }

   public boolean hasWaterSquare() {
      for (int y = 0; y < 8; y++) {
         for (int x = 0; x < 8; x++) {
            IsoGridSquare square = this.getGridSquare(x, y, 0);
            if (square == null || square.isWaterSquare()) {
               return true;
            }
         }
      }

      return false;
   }

   private void addRagdollControllers() {
      if (!RagdollBuilder.instance.isInitialized()) {
         RagdollBuilder.instance.Initialize();
      }
   }

   private boolean checkForActiveRagdoll(IsoGridSquare isoGridSquare) {
      return RagdollController.checkForActiveRagdoll(isoGridSquare);
   }

   public void checkPhysicsLaterForActiveRagdoll(IsoChunkLevel isoChunkLevel) {
      if (!this.delayedPhysicsShapeSet.isEmpty()) {
         for (IsoGridSquare isoGridSquare : this.delayedPhysicsShapeSet) {
            if (isoChunkLevel.containsIsoGridSquare(isoGridSquare)) {
               isoChunkLevel.physicsCheck = true;
               break;
            }
         }
      }
   }

   public boolean hasFence() {
      for (int y = 0; y < 8; y++) {
         for (int x = 0; x < 8; x++) {
            IsoGridSquare square = this.getGridSquare(x, y, 0);
            if (square != null && square.hasFence()) {
               return true;
            }
         }
      }

      return false;
   }

   private static class ChunkGetter implements GetSquare {
      private IsoChunk chunk;

      public IsoGridSquare getGridSquare(int x, int y, int z) {
         x -= this.chunk.wx * 8;
         y -= this.chunk.wy * 8;
         return x >= 0 && x < 8 && y >= 0 && y < 8 && z >= -32 && z <= 31 ? this.chunk.getGridSquare(x, y, z) : null;
      }
   }

   private static class ChunkLock {
      public int wx;
      public int wy;
      public int count;
      public ReentrantReadWriteLock rw = new ReentrantReadWriteLock(true);

      public ChunkLock(int wx, int wy) {
         this.wx = wx;
         this.wy = wy;
      }

      public IsoChunk.ChunkLock set(int wx, int wy) {
         assert this.count == 0;
         this.wx = wx;
         this.wy = wy;
         return this;
      }

      public IsoChunk.ChunkLock ref() {
         this.count++;
         return this;
      }

      public int deref() {
         assert this.count > 0;
         return --this.count;
      }

      public void lockForReading() {
         this.rw.readLock().lock();
      }

      public void unlockForReading() {
         this.rw.readLock().unlock();
      }

      public void lockForWriting() {
         this.rw.writeLock().lock();
      }

      public void unlockForWriting() {
         this.rw.writeLock().unlock();
      }
   }

   public enum JobType {
      None,
      Convert,
      SoftReset;
   }

   private enum PhysicsShapes {
      Solid,
      WallN,
      WallW,
      WallS,
      WallE,
      Tree,
      Floor,
      StairsMiddleNorth,
      StairsMiddleWest,
      SolidStairs,
      FIRST_MESH;
   }

   private static class SanityCheck {
      public IsoChunk saveChunk;
      public String saveThread;
      public IsoChunk loadChunk;
      public String loadThread;
      public final ArrayList<String> loadFile = new ArrayList<>();
      public String saveFile;

      public synchronized void beginSave(IsoChunk chunk) {
         if (this.saveChunk != null) {
            this.log("trying to save while already saving, wx,wy=" + chunk.wx + "," + chunk.wy);
         }

         if (this.loadChunk == chunk) {
            this.log("trying to save the same IsoChunk being loaded");
         }

         this.saveChunk = chunk;
         this.saveThread = Thread.currentThread().getName();
      }

      public synchronized void endSave(IsoChunk chunk) {
         this.saveChunk = null;
         this.saveThread = null;
      }

      public synchronized void beginLoad(IsoChunk chunk) {
         if (this.loadChunk != null) {
            this.log("trying to load while already loading, wx,wy=" + chunk.wx + "," + chunk.wy);
         }

         if (this.saveChunk == chunk) {
            this.log("trying to load the same IsoChunk being saved");
         }

         this.loadChunk = chunk;
         this.loadThread = Thread.currentThread().getName();
      }

      public synchronized void endLoad(IsoChunk chunk) {
         this.loadChunk = null;
         this.loadThread = null;
      }

      public synchronized void checkCRC(long saveCRC, long loadCRC) {
         if (saveCRC != loadCRC) {
            this.log("CRC mismatch save=" + saveCRC + " load=" + loadCRC);
         }
      }

      public synchronized void checkLength(long saveLen, long loadLen) {
         if (saveLen != loadLen) {
            this.log("LENGTH mismatch save=" + saveLen + " load=" + loadLen);
         }
      }

      public synchronized void beginLoadFile(String file) {
         if (file.equals(this.saveFile)) {
            this.log("attempted to load file being saved " + file);
         }

         this.loadFile.add(file);
      }

      public synchronized void endLoadFile(String file) {
         this.loadFile.remove(file);
      }

      public synchronized void beginSaveFile(String file) {
         if (this.loadFile.contains(file)) {
            this.log("attempted to save file being loaded " + file);
         }

         this.saveFile = file;
      }

      public synchronized void endSaveFile() {
         this.saveFile = null;
      }

      public synchronized void log(String message) {
         StringBuilder sb = new StringBuilder();
         sb.append("SANITY CHECK FAIL! thread=\"" + Thread.currentThread().getName() + "\"\n");
         if (message != null) {
            sb.append(message + "\n");
         }

         if (this.saveChunk != null && this.saveChunk == this.loadChunk) {
            sb.append("exact same IsoChunk being saved + loaded\n");
         }

         if (this.saveChunk != null) {
            sb.append("save wx,wy=" + this.saveChunk.wx + "," + this.saveChunk.wy + " thread=\"" + this.saveThread + "\"\n");
         } else {
            sb.append("save chunk=null\n");
         }

         if (this.loadChunk != null) {
            sb.append("load wx,wy=" + this.loadChunk.wx + "," + this.loadChunk.wy + " thread=\"" + this.loadThread + "\"\n");
         } else {
            sb.append("load chunk=null\n");
         }

         String str = sb.toString();
         throw new RuntimeException(str);
      }
   }
}
