package zombie.iso;

import fmod.fmod.FMODSoundEmitter;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import se.krka.kahlua.vm.KahluaTable;
import zombie.CollisionManager;
import zombie.FliesSound;
import zombie.GameProfiler;
import zombie.GameTime;
import zombie.GameWindow;
import zombie.IndieGL;
import zombie.MapCollisionData;
import zombie.MovingObjectUpdateScheduler;
import zombie.PersistentOutfits;
import zombie.ReanimatedPlayers;
import zombie.SandboxOptions;
import zombie.SharedDescriptors;
import zombie.SoundManager;
import zombie.SystemDisabler;
import zombie.UsedFromLua;
import zombie.VirtualZombieManager;
import zombie.WorldSoundManager;
import zombie.ZomboidFileSystem;
import zombie.ZomboidGlobals;
import zombie.GameProfiler.ProfileArea;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.Lua.LuaManager.GlobalObject;
import zombie.ai.ZombieGroupManager;
import zombie.ai.states.FakeDeadZombieState;
import zombie.audio.BaseSoundEmitter;
import zombie.audio.DummySoundEmitter;
import zombie.audio.ObjectAmbientEmitters;
import zombie.audio.parameters.ParameterInside;
import zombie.basements.Basements;
import zombie.buildingRooms.BuildingRoomsEditor;
import zombie.characters.AnimalVocalsManager;
import zombie.characters.HaloTextHelper;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoSurvivor;
import zombie.characters.IsoZombie;
import zombie.characters.SurvivorDesc;
import zombie.characters.ZombieVocalsManager;
import zombie.characters.animals.AnimalDefinitions;
import zombie.characters.animals.AnimalPopulationManager;
import zombie.characters.animals.AnimalTracksDefinitions;
import zombie.characters.animals.AnimalZones;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.professions.CharacterProfessionDefinition;
import zombie.characters.traits.CharacterTraitDefinition;
import zombie.core.Core;
import zombie.core.ImportantAreaManager;
import zombie.core.PZForkJoinPool;
import zombie.core.PerformanceSettings;
import zombie.core.SceneShaderStore;
import zombie.core.SpriteRenderer;
import zombie.core.TilePropertyAliasMap;
import zombie.core.Translator;
import zombie.core.logger.ExceptionLogger;
import zombie.core.math.PZMath;
import zombie.core.physics.WorldSimulation;
import zombie.core.profiling.AbstractPerformanceProfileProbe;
import zombie.core.profiling.PerformanceProfileProbe;
import zombie.core.properties.FasciaEdge;
import zombie.core.properties.IsoPropertyType;
import zombie.core.properties.PropertyContainer;
import zombie.core.random.Rand;
import zombie.core.skinnedmodel.DeadBodyAtlas;
import zombie.core.skinnedmodel.animation.debug.AnimationPlayerRecorder;
import zombie.core.skinnedmodel.model.WorldItemAtlas;
import zombie.core.stash.StashSystem;
import zombie.core.textures.Texture;
import zombie.core.utils.OnceEvery;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.debug.LineDrawer;
import zombie.debug.LogSeverity;
import zombie.entity.GameEntityManager;
import zombie.entity.components.spriteconfig.SpriteConfigManager;
import zombie.entity.util.TimSort;
import zombie.erosion.ErosionGlobals;
import zombie.gameStates.GameLoadingState;
import zombie.gizmo.Gizmos;
import zombie.globalObjects.GlobalObjectLookup;
import zombie.input.Mouse;
import zombie.inventory.ItemConfigurator;
import zombie.inventory.ItemPickerJava;
import zombie.inventory.types.MapItem;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.SpriteDetails.IsoObjectType;
import zombie.iso.areas.DesignationZone;
import zombie.iso.areas.IsoBuilding;
import zombie.iso.areas.SafeHouse;
import zombie.iso.areas.isoregion.IsoRegions;
import zombie.iso.fboRenderChunk.FBORenderAreaHighlights;
import zombie.iso.objects.IsoFireManager;
import zombie.iso.objects.ObjectRenderEffects;
import zombie.iso.objects.RainManager;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteGrid;
import zombie.iso.sprite.IsoSpriteManager;
import zombie.iso.sprite.SkyBox;
import zombie.iso.sprite.SpriteGridParseData;
import zombie.iso.sprite.SpriteGridParseData.Level;
import zombie.iso.weather.ClimateManager;
import zombie.iso.weather.WorldFlares;
import zombie.iso.weather.fog.ImprovedFog;
import zombie.iso.weather.fx.IsoWeatherFX;
import zombie.iso.weather.fx.WeatherFxMask;
import zombie.iso.worldgen.WorldGenChunk;
import zombie.iso.worldgen.WorldGenParams;
import zombie.iso.worldgen.WorldGenParams.Result;
import zombie.iso.worldgen.attachments.AttachmentsHandler;
import zombie.iso.worldgen.blending.Blending;
import zombie.iso.worldgen.maps.BiomeMap;
import zombie.iso.worldgen.rules.Rules;
import zombie.iso.worldgen.zombie.ZombieVoronoi;
import zombie.iso.worldgen.zones.ZoneGenerator;
import zombie.iso.zones.Zone;
import zombie.network.BodyDamageSync;
import zombie.network.ClientServerMap;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.NetChecksum;
import zombie.network.PassengerMap;
import zombie.network.ServerMap;
import zombie.network.ServerOptions;
import zombie.network.id.ObjectIDManager;
import zombie.pathfind.PolygonalMap2;
import zombie.pathfind.extra.BorderFinderRenderer;
import zombie.pathfind.nativeCode.PathfindNative;
import zombie.pathfind.nativeCode.PathfindNativeRenderer;
import zombie.popman.ZombiePopulationManager;
import zombie.popman.animal.HutchManager;
import zombie.radio.ZomboidRadio;
import zombie.randomizedWorld.RandomizedWorldBase;
import zombie.randomizedWorld.randomizedBuilding.RBBar;
import zombie.randomizedWorld.randomizedBuilding.RBBarn;
import zombie.randomizedWorld.randomizedBuilding.RBBasic;
import zombie.randomizedWorld.randomizedBuilding.RBBurnt;
import zombie.randomizedWorld.randomizedBuilding.RBBurntCorpse;
import zombie.randomizedWorld.randomizedBuilding.RBBurntFireman;
import zombie.randomizedWorld.randomizedBuilding.RBCafe;
import zombie.randomizedWorld.randomizedBuilding.RBClinic;
import zombie.randomizedWorld.randomizedBuilding.RBDorm;
import zombie.randomizedWorld.randomizedBuilding.RBGunstoreSiege;
import zombie.randomizedWorld.randomizedBuilding.RBHairSalon;
import zombie.randomizedWorld.randomizedBuilding.RBHeatBreakAfternoon;
import zombie.randomizedWorld.randomizedBuilding.RBJackieJaye;
import zombie.randomizedWorld.randomizedBuilding.RBJoanHartford;
import zombie.randomizedWorld.randomizedBuilding.RBKateAndBaldspot;
import zombie.randomizedWorld.randomizedBuilding.RBLooted;
import zombie.randomizedWorld.randomizedBuilding.RBMayorWestPoint;
import zombie.randomizedWorld.randomizedBuilding.RBNolans;
import zombie.randomizedWorld.randomizedBuilding.RBOffice;
import zombie.randomizedWorld.randomizedBuilding.RBOther;
import zombie.randomizedWorld.randomizedBuilding.RBPileOCrepe;
import zombie.randomizedWorld.randomizedBuilding.RBPizzaWhirled;
import zombie.randomizedWorld.randomizedBuilding.RBPoliceSiege;
import zombie.randomizedWorld.randomizedBuilding.RBSWATStation;
import zombie.randomizedWorld.randomizedBuilding.RBSafehouse;
import zombie.randomizedWorld.randomizedBuilding.RBSchool;
import zombie.randomizedWorld.randomizedBuilding.RBShopLooted;
import zombie.randomizedWorld.randomizedBuilding.RBSpiffo;
import zombie.randomizedWorld.randomizedBuilding.RBStripclub;
import zombie.randomizedWorld.randomizedBuilding.RBTrashed;
import zombie.randomizedWorld.randomizedBuilding.RBTwiggy;
import zombie.randomizedWorld.randomizedBuilding.RBWoodcraft;
import zombie.randomizedWorld.randomizedBuilding.RandomizedBuildingBase;
import zombie.randomizedWorld.randomizedVehicleStory.RVSAmbulanceCrash;
import zombie.randomizedWorld.randomizedVehicleStory.RVSAnimalOnRoad;
import zombie.randomizedWorld.randomizedVehicleStory.RVSAnimalTrailerOnRoad;
import zombie.randomizedWorld.randomizedVehicleStory.RVSBanditRoad;
import zombie.randomizedWorld.randomizedVehicleStory.RVSBurntCar;
import zombie.randomizedWorld.randomizedVehicleStory.RVSCarCrash;
import zombie.randomizedWorld.randomizedVehicleStory.RVSCarCrashCorpse;
import zombie.randomizedWorld.randomizedVehicleStory.RVSCarCrashDeer;
import zombie.randomizedWorld.randomizedVehicleStory.RVSChangingTire;
import zombie.randomizedWorld.randomizedVehicleStory.RVSConstructionSite;
import zombie.randomizedWorld.randomizedVehicleStory.RVSCrashHorde;
import zombie.randomizedWorld.randomizedVehicleStory.RVSDeadEnd;
import zombie.randomizedWorld.randomizedVehicleStory.RVSFlippedCrash;
import zombie.randomizedWorld.randomizedVehicleStory.RVSHerdOnRoad;
import zombie.randomizedWorld.randomizedVehicleStory.RVSPlonkies;
import zombie.randomizedWorld.randomizedVehicleStory.RVSPoliceBlockade;
import zombie.randomizedWorld.randomizedVehicleStory.RVSPoliceBlockadeShooting;
import zombie.randomizedWorld.randomizedVehicleStory.RVSRegionalProfessionVehicle;
import zombie.randomizedWorld.randomizedVehicleStory.RVSRichJerk;
import zombie.randomizedWorld.randomizedVehicleStory.RVSRoadKill;
import zombie.randomizedWorld.randomizedVehicleStory.RVSRoadKillSmall;
import zombie.randomizedWorld.randomizedVehicleStory.RVSTrailerCrash;
import zombie.randomizedWorld.randomizedVehicleStory.RVSUtilityVehicle;
import zombie.randomizedWorld.randomizedVehicleStory.RandomizedVehicleStoryBase;
import zombie.randomizedWorld.randomizedZoneStory.RZJackieJaye;
import zombie.randomizedWorld.randomizedZoneStory.RZSAttachedAnimal;
import zombie.randomizedWorld.randomizedZoneStory.RZSBBQParty;
import zombie.randomizedWorld.randomizedZoneStory.RZSBaseball;
import zombie.randomizedWorld.randomizedZoneStory.RZSBeachParty;
import zombie.randomizedWorld.randomizedZoneStory.RZSBurntWreck;
import zombie.randomizedWorld.randomizedZoneStory.RZSBuryingCamp;
import zombie.randomizedWorld.randomizedZoneStory.RZSCampsite;
import zombie.randomizedWorld.randomizedZoneStory.RZSCharcoalBurner;
import zombie.randomizedWorld.randomizedZoneStory.RZSDean;
import zombie.randomizedWorld.randomizedZoneStory.RZSDuke;
import zombie.randomizedWorld.randomizedZoneStory.RZSEscapedAnimal;
import zombie.randomizedWorld.randomizedZoneStory.RZSEscapedHerd;
import zombie.randomizedWorld.randomizedZoneStory.RZSFishingTrip;
import zombie.randomizedWorld.randomizedZoneStory.RZSForestCamp;
import zombie.randomizedWorld.randomizedZoneStory.RZSForestCampEaten;
import zombie.randomizedWorld.randomizedZoneStory.RZSFrankHemingway;
import zombie.randomizedWorld.randomizedZoneStory.RZSHermitCamp;
import zombie.randomizedWorld.randomizedZoneStory.RZSHillbillyHoedown;
import zombie.randomizedWorld.randomizedZoneStory.RZSHogWild;
import zombie.randomizedWorld.randomizedZoneStory.RZSHunterCamp;
import zombie.randomizedWorld.randomizedZoneStory.RZSKirstyKormick;
import zombie.randomizedWorld.randomizedZoneStory.RZSMurderScene;
import zombie.randomizedWorld.randomizedZoneStory.RZSMusicFest;
import zombie.randomizedWorld.randomizedZoneStory.RZSMusicFestStage;
import zombie.randomizedWorld.randomizedZoneStory.RZSNastyMattress;
import zombie.randomizedWorld.randomizedZoneStory.RZSOccultActivity;
import zombie.randomizedWorld.randomizedZoneStory.RZSOldFirepit;
import zombie.randomizedWorld.randomizedZoneStory.RZSOldShelter;
import zombie.randomizedWorld.randomizedZoneStory.RZSOrphanedFawn;
import zombie.randomizedWorld.randomizedZoneStory.RZSRangerSmith;
import zombie.randomizedWorld.randomizedZoneStory.RZSRockerParty;
import zombie.randomizedWorld.randomizedZoneStory.RZSSadCamp;
import zombie.randomizedWorld.randomizedZoneStory.RZSSexyTime;
import zombie.randomizedWorld.randomizedZoneStory.RZSSirTwiggy;
import zombie.randomizedWorld.randomizedZoneStory.RZSSurvivalistCamp;
import zombie.randomizedWorld.randomizedZoneStory.RZSTragicPicnic;
import zombie.randomizedWorld.randomizedZoneStory.RZSTrapperCamp;
import zombie.randomizedWorld.randomizedZoneStory.RZSVanCamp;
import zombie.randomizedWorld.randomizedZoneStory.RZSWasteDump;
import zombie.randomizedWorld.randomizedZoneStory.RZSWaterPump;
import zombie.randomizedWorld.randomizedZoneStory.RandomizedZoneStoryBase;
import zombie.savefile.ClientPlayerDB;
import zombie.savefile.PlayerDB;
import zombie.savefile.PlayerDBHelper;
import zombie.savefile.ServerPlayerDB;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.CharacterTrait;
import zombie.spriteModel.SpriteModelManager;
import zombie.statistics.StatisticsManager;
import zombie.text.templating.TemplateText;
import zombie.tileDepth.TileDepthTextureManager;
import zombie.tileDepth.TileGeometryManager;
import zombie.ui.TutorialManager;
import zombie.util.AddCoopPlayer;
import zombie.util.SharedStrings;
import zombie.util.StringUtils;
import zombie.util.list.PZArrayList;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleIDMap;
import zombie.vehicles.VehicleManager;
import zombie.vehicles.VehiclesDB2;
import zombie.viewCone.ViewConeTextureFBO;
import zombie.world.WorldDictionary;
import zombie.world.WorldDictionaryException;
import zombie.world.moddata.GlobalModData;
import zombie.worldMap.network.HiddenAuthors;
import zombie.worldMap.network.WorldMapClient;

@UsedFromLua
public final class IsoWorld {
   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy. IsoWorld initialises before the
   // game's logger exists (the class is touched at JVM start), so the marker is deferred: the quiet variant queues it and
   // the next onClassLoaded call prints it. The loud variant here killed every launch of 2026-09-22 05:36-06:00 (the
   // logger's NPE inside the static initialiser became "Could not initialize class zombie.iso.IsoWorld" everywhere).
   static {
      pzopt.Overrides.onClassLoadedQuiet("zombie.iso.IsoWorld");
   }

   private static final int TILESETS_PER_FILE_1 = 1024;
   private static final int TILES_PER_TILESET_1 = 1024;
   private static final int TILES_PER_FILE_1 = 1048576;
   private static final int TILESETS_PER_FILE_OTHER = 512;
   private static final int TILES_PER_TILESET_OTHER = 512;
   private static final int TILES_PER_FILE_OTHER = 262144;
   private static final int NUM_TDEF_FILES_GT_1 = 8188;
   public static final int MIN_TDEF_FILE_NUMBER_FOR_MODS = 100;
   public static final int MAX_TDEF_FILE_NUMBER_FOR_MODS = 8189;
   private static final int TDEF_VERSION1 = 1;
   private static final int TDEF_VERSION_LATEST = 1;
   private static final byte[] TDEF_FILE_MAGIC = new byte[]{116, 100, 101, 102};
   public static final long LUA_CHECKSUM_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(60L);
   private String weather = "sunny";
   public final IsoMetaGrid metaGrid = new IsoMetaGrid();
   private final ArrayList<RandomizedBuildingBase> randomizedBuildingList = new ArrayList<>();
   private final ArrayList<RandomizedZoneStoryBase> randomizedZoneList = new ArrayList<>();
   private final ArrayList<RandomizedVehicleStoryBase> randomizedVehicleStoryList = new ArrayList<>();
   private final RandomizedBuildingBase rbBasic = new RBBasic();
   private final RandomizedWorldBase randomizedWorldBase = new RandomizedWorldBase();
   private final HashMap<String, ArrayList<UUID>> spawnedZombieZone = new HashMap<>();
   private final HashMap<String, ArrayList<String>> allTiles = new HashMap<>();
   private final ArrayList<String> tileImages = new ArrayList<>();
   /** pzopt: tileDefPreload. Set while pzopt.TileDefPreload builds the tile sprites into a private manager at boot: sprites
    *  are created without their texture (the texture table is not thread-safe during boot; the loader binds them), door
    *  lookups use that manager, and the loading-screen frame pump is skipped. */
   IsoSpriteManager pzoptTileMgr;
   private float flashIsoCursorA = 1.0F;
   private boolean flashIsoCursorInc;
   public SkyBox sky;
   private int timeSinceLastSurvivorInHorde = 4000;
   private int frameNo;
   public final Helicopter helicopter = new Helicopter();
   private boolean hydroPowerOn;
   public final ArrayList<IsoGameCharacter> characters = new ArrayList<>();
   private final ArrayDeque<BaseSoundEmitter> freeEmitters = new ArrayDeque<>();
   private final ArrayList<BaseSoundEmitter> currentEmitters = new ArrayList<>();
   private final HashMap<BaseSoundEmitter, IsoObject> emitterOwners = new HashMap<>();
   public int x = 50;
   public int y = 50;
   public IsoCell currentCell;
   public static IsoWorld instance = new IsoWorld();
   public int totalSurvivorsDead;
   public int totalSurvivorNights;
   public int survivorSurvivalRecord;
   public HashMap<Integer, SurvivorDesc> survivorDescriptors = new HashMap<>();
   public ArrayList<AddCoopPlayer> addCoopPlayers = new ArrayList<>();
   private static final IsoWorld.CompScoreToPlayer compScoreToPlayer = new IsoWorld.CompScoreToPlayer();
   public static String mapPath = "media/";
   public static boolean mapUseJar = true;
   private final boolean loaded = false;
   public static final HashMap<String, ArrayList<String>> PropertyValueMap = new HashMap<>();
   private static final int JUMBO_TRUNK_VARIANT_COUNT = 114;
   private static final int FAKE_JUMBO_TREE_TILESET_INDEX = 12;
   private static int worldX;
   private static int worldY;
   private SurvivorDesc luaDesc;
   private final List<CharacterTrait> luatraits = new ArrayList<>();
   private int luaPosX = -1;
   private int luaPosY = -1;
   private int luaPosZ = -1;
   private String spawnRegionName = "";
   public static final int WorldVersion = 249;
   public static final int WorldVersion_PreviouslyMoved = 196;
   public static final int WorldVersion_DesignationZone = 197;
   public static final int WorldVersion_PlayerExtraInfoFlags = 198;
   public static final int WorldVersion_ObjectID = 199;
   public static final int WorldVersion_CraftUpdateFoundations = 200;
   public static final int WorldVersion_AlarmDecay = 201;
   public static final int WorldVersion_FishingCheat = 202;
   public static final int WorldVersion_CharacterVoiceType = 203;
   public static final int WorldVersion_AnimalHutch = 204;
   public static final int WorldVersion_AlarmClock = 205;
   public static final int WorldVersion_VariableHeight = 206;
   public static final int WorldVersion_EnableWorldgen = 207;
   public static final int WorldVersion_CharacterVoiceOptions = 208;
   public static final int WorldVersion_ChunksWorldGeneratedBoolean = 209;
   public static final int WorldVersion_ChunksWorldModifiedBoolean = 210;
   public static final int WorldVersion_CharacterDiscomfort = 211;
   public static final int WorldVersion_HutchAndVehicleAnimalFormat = 212;
   public static final int WorldVersion_IsoCompostHealthValues = 213;
   public static final int WorldVersion_ChunksAttachmentsState = 214;
   public static final int WorldVersion_ZoneIDisUUID = 215;
   public static final int WorldVersion_SafeHouseHitPoints = 216;
   public static final int WorldVersion_FastMoveCheat = 217;
   public static final int WorldVersion_SquareSeen = 218;
   public static final int WorldVersion_TrapExplosionDuration = 219;
   public static final int WorldVersion_InventoryItemUsesInteger = 220;
   public static final int WorldVersion_ChunksAttachmentsPartial = 221;
   public static final int WorldVersion_PrintMediaRottingCorpsesBodyDamage = 222;
   public static final int WorldVersion_SafeHouseCreatedTimeAndLocation = 223;
   public static final int WorldVersion_Stats_Idleness = 224;
   public static final int WorldVersion_AnimalRottingTexture = 225;
   public static final int WorldVersion_LearnedRecipes = 226;
   public static final int WorldVersion_BodyDamageSavePoulticeValues = 227;
   public static final int WorldVersion_PlayerSaveCraftingHistory = 228;
   public static final int WorldVersion_VehicleAlarm = 229;
   public static final int WorldVersion_RecipesAndAmmoCheats = 230;
   public static final int WorldVersion_SavePlayerCheats = 231;
   public static final int WorldVersion_ItemWorldRotationFloats = 232;
   public static final int WorldVersion_MetaEntityOutsideAware = 233;
   public static final int WorldVersion_VisitedFileVersion = 234;
   public static final int WorldVersion_VariableCraftInputCounts = 235;
   public static final int WorldVersion_AnimalPetTime = 236;
   public static final int WorldVersion_RootLocale = 237;
   public static final int WorldVersion_CraftLogicParallelCrafting = 238;
   public static final int WorldVersion_PlayerAutoDrink = 239;
   public static final int WorldVersion_42_13 = 240;
   public static final int WorldVersion_PlayerInsulation = 241;
   public static final int WorldVersion_SaveFireTimer = 242;
   public static final int WorldVersion_BodyDamageStatusesSync = 243;
   public static final int WorldVersion_RemoveDifficulty = 244;
   public static final int WorldVersion_AnimalWild = 245;
   public static final int WorldVersion_DeadBodyAnimalGenetics = 246;
   public static final int WorldVersion_AnimalOnlineId = 247;
   public static final int WorldVersion_BuildMaterials = 248;
   public static final int WorldVersion_ThermalDuration = 249;
   public static int savedWorldVersion = -1;
   private boolean drawWorld = true;
   private final PZArrayList<IsoZombie> zombieWithModel = new PZArrayList(IsoZombie.class, 128);
   private final PZArrayList<IsoZombie> zombieWithoutModel = new PZArrayList(IsoZombie.class, 128);
   private final TimSort timSort = new TimSort();
   private final ArrayList<IsoAnimal> animalWithModel = new ArrayList<>();
   private final ArrayList<IsoAnimal> animalWithoutModel = new ArrayList<>();
   private final Vector2 coneTempo1 = new Vector2();
   private final Vector2 coneTempo2 = new Vector2();
   private final Vector2 coneTempo3 = new Vector2();
   public static boolean noZombies;
   public static int totalWorldVersion = -1;
   public static int saveoffsetx;
   public static int saveoffsety;
   public boolean doChunkMapUpdate = true;
   private long emitterUpdateMs;
   public boolean emitterUpdate;
   private int updateSafehousePlayers = 200;
   public static CompletableFuture<Void> animationThread;
   private Rules rules;
   private WorldGenChunk wgChunk;
   private Blending blending;
   private AttachmentsHandler attachmentsHandler;
   private ZoneGenerator zoneGenerator;
   private BiomeMap biomeMap;
   private List<ZombieVoronoi> zombieVoronois;

   public IsoMetaGrid getMetaGrid() {
      return this.metaGrid;
   }

   public Zone registerZone(String name, String type, int x, int y, int z, int width, int height) {
      return this.metaGrid.registerZone(name, type, x, y, z, width, height);
   }

   @Deprecated
   public Zone registerZoneNoOverlap(String name, String type, int x, int y, int z, int width, int height) {
      return this.registerZone(name, type, x, y, z, width, height);
   }

   public void removeZonesForLotDirectory(String lotDir) {
      this.metaGrid.removeZonesForLotDirectory(lotDir);
   }

   public BaseSoundEmitter getFreeEmitter() {
      BaseSoundEmitter e;
      if (this.freeEmitters.isEmpty()) {
         e = (BaseSoundEmitter)(Core.soundDisabled ? new DummySoundEmitter() : new FMODSoundEmitter());
      } else {
         e = this.freeEmitters.pop();
      }

      e.setPlayRemoteEvents(false);
      this.currentEmitters.add(e);
      return e;
   }

   public BaseSoundEmitter getFreeEmitter(float x, float y, float z) {
      BaseSoundEmitter e = this.getFreeEmitter();
      e.setPos(x, y, z);
      return e;
   }

   public void takeOwnershipOfEmitter(BaseSoundEmitter emitter) {
      this.currentEmitters.remove(emitter);
   }

   public void setEmitterOwner(BaseSoundEmitter emitter, IsoObject object) {
      if (emitter != null && object != null) {
         if (!this.emitterOwners.containsKey(emitter)) {
            this.emitterOwners.put(emitter, object);
         }
      }
   }

   public void returnOwnershipOfEmitter(BaseSoundEmitter emitter) {
      if (emitter != null) {
         if (!this.currentEmitters.contains(emitter) && !this.freeEmitters.contains(emitter)) {
            if (emitter.isEmpty()) {
               if (emitter instanceof FMODSoundEmitter fmodSoundEmitter) {
                  fmodSoundEmitter.clearParameters();
               }

               this.freeEmitters.add(emitter);
            } else {
               this.currentEmitters.add(emitter);
            }
         }
      }
   }

   public Zone registerVehiclesZone(String name, String type, int x, int y, int z, int width, int height, KahluaTable properties) {
      return this.metaGrid.registerVehiclesZone(name, type, x, y, z, width, height, properties);
   }

   public Zone registerMannequinZone(String name, String type, int x, int y, int z, int width, int height, KahluaTable properties) {
      return this.metaGrid.registerMannequinZone(name, type, x, y, z, width, height, properties);
   }

   public void registerRoomTone(String name, String type, int x, int y, int z, int width, int height, KahluaTable properties) {
      this.metaGrid.registerRoomTone(name, type, x, y, z, width, height, properties);
   }

   public void registerSpawnOrigin(int x, int y, int width, int height, KahluaTable properties) {
      ZombiePopulationManager.instance.registerSpawnOrigin(x, y, width, height, properties);
   }

   public void registerWaterFlow(float x, float y, float flow, float speed) {
      IsoWaterFlow.addFlow(x, y, flow, speed);
   }

   public void registerWaterZone(float x1, float y1, float x2, float y2, float shore, float waterGround) {
      IsoWaterFlow.addZone(x1, y1, x2, y2, shore, waterGround);
   }

   public void checkVehiclesZones() {
      this.metaGrid.checkVehiclesZones();
   }

   public void setGameMode(String mode) {
      Core.getInstance().setGameMode(mode);
      Core.lastStand = "LastStand".equals(mode);
      Core.getInstance().setChallenge(false);
      Core.challengeId = null;
   }

   public String getGameMode() {
      return Core.gameMode;
   }

   public void setPreset(String mode) {
      Core.preset = mode;
   }

   public String getPreset() {
      return Core.preset;
   }

   public void setWorld(String world) {
      Core.gameSaveWorld = world.trim();
   }

   public void setMap(String world) {
      Core.gameMap = world;
   }

   public String getMap() {
      return Core.gameMap;
   }

   public void renderTerrain() {
   }

   public int getFrameNo() {
      return this.frameNo;
   }

   public IsoSurvivor CreateRandomSurvivor(SurvivorDesc desc, IsoGridSquare sq, IsoPlayer player) {
      return null;
   }

   public void CreateSwarm(int num, int x1, int y1, int x2, int y2) {
   }

   public void ForceKillAllZombies() {
      GameTime.getInstance().RemoveZombiesIndiscriminate(1000);
   }

   public static int readInt(RandomAccessFile in) throws EOFException, IOException {
      int ch1 = in.read();
      int ch2 = in.read();
      int ch3 = in.read();
      int ch4 = in.read();
      if ((ch1 | ch2 | ch3 | ch4) < 0) {
         throw new EOFException();
      } else {
         return (ch1 << 0) + (ch2 << 8) + (ch3 << 16) + (ch4 << 24);
      }
   }

   public static String readString(RandomAccessFile in) throws EOFException, IOException {
      return in.readLine();
   }

   public static int readInt(InputStream in) throws EOFException, IOException {
      int ch1 = in.read();
      int ch2 = in.read();
      int ch3 = in.read();
      int ch4 = in.read();
      if ((ch1 | ch2 | ch3 | ch4) < 0) {
         throw new EOFException();
      } else {
         return (ch1 << 0) + (ch2 << 8) + (ch3 << 16) + (ch4 << 24);
      }
   }

   public static String readString(InputStream in, StringBuilder input) throws IOException {
      input.setLength(0);
      int c = -1;
      boolean eol = false;

      while (!eol) {
         switch (c = in.read()) {
            case -1:
            case 10:
               eol = true;
               break;
            case 13:
               throw new IllegalStateException("\r\n unsupported");
            default:
               input.append((char)c);
         }
      }

      return c == -1 && input.isEmpty() ? null : input.toString();
   }

   private int getSpriteID(int fileNumber, int tilesetNumber, int tileIndex) {
      return fileNumber == 1 ? (tilesetNumber - 1) * 1024 + tileIndex : 1048576 + (fileNumber - 2) * 262144 + (tilesetNumber - 1) * 512 + tileIndex;
   }

   public void LoadTileDefinitions(IsoSpriteManager sprMan, String filename, int fileNumber) {
      DebugType.DetailedInfo.trace("tiledef: loading " + filename);
      boolean bPatch = filename.endsWith(".patch.tiles");
      int maxTilesetsPerFile = fileNumber == 1 ? 1024 : 512;
      int maxTilesPerTileset = fileNumber == 1 ? 1024 : 512;

      try (
         FileInputStream fis = new FileInputStream(filename);
         BufferedInputStream in = new BufferedInputStream(fis);
      ) {
         byte[] magic = new byte[TDEF_FILE_MAGIC.length];
         int nBytes = in.read(magic);
         if (nBytes != TDEF_FILE_MAGIC.length || !Arrays.equals(magic, TDEF_FILE_MAGIC)) {
            throw new IOException("not magic");
         }

         int version = readInt(in);
         if (version < 1 || version > 1) {
            throw new IOException("%s: invalid version %d, must be from %d to %d".formatted(filename, version, 1, 1));
         }

         int numTilesheets = readInt(in);
         if (numTilesheets < 0 || numTilesheets > maxTilesetsPerFile) {
            throw new IOException("%s: invalid number of tilesets %d, must be from 0 to %d".formatted(filename, numTilesheets, maxTilesetsPerFile));
         }

         SharedStrings sharedStrings = new SharedStrings();
         StringBuilder stringBuilder = new StringBuilder();
         boolean saveMovableStatsToFile = false;
         boolean dumpCustomNameToStdout = false;
         boolean dumpUntranslatedCustomNameToStdout = Core.debug && Translator.getLanguage() == Translator.getDefaultLanguage();
         ArrayList<IsoSprite> buffer = new ArrayList<>();
         Map<String, ArrayList<IsoSprite>> groupMap = new HashMap<>();
         Map<String, ArrayList<IsoSprite>> faceMap = new HashMap<>();
         String[] faceNames = new String[]{"N", "E", "S", "W"};

         for (int i = 0; i < faceNames.length; i++) {
            faceMap.put(faceNames[i], new ArrayList<>());
         }

         SpriteGridParseData spriteGridParseData = new SpriteGridParseData();
         Map<String, ArrayList<String>> uniqueMovables = new HashMap<>();
         int movableSpriteCount = 0;
         int movableOneSpriteCount = 0;
         int movablesSingleCount = 0;
         int movablesMultiCount = 0;
         HashSet<String> customNames = new HashSet<>();

         for (int n = 0; n < numTilesheets; n++) {
            String str = readString(in, stringBuilder);
            String name = str.trim();
            String imageName = readString(in, stringBuilder);
            int wTiles = readInt(in);
            int hTiles = readInt(in);
            int tilesetNumber = readInt(in);
            if (tilesetNumber < 1 || tilesetNumber > maxTilesetsPerFile) {
               throw new IOException("%s: invalid tileset number %d, must be from 1 to %d in %s".formatted(filename, tilesetNumber, maxTilesetsPerFile, name));
            }

            int nTiles = readInt(in);
            if (nTiles < 0 || nTiles > maxTilesPerTileset) {
               throw new IOException("%s: invalid number of tiles %d, must be from 0 to %d in %s".formatted(filename, nTiles, maxTilesPerTileset, name));
            }

            for (int m = 0; m < nTiles; m++) {
               String tileName = name + "_" + m;
               IsoSprite spr;
               if (bPatch) {
                  spr = (IsoSprite)sprMan.namedMap.get(tileName);
                  if (spr == null) {
                     int nProps = readInt(in);

                     for (int l = 0; l < nProps; l++) {
                        readString(in, stringBuilder);
                        readString(in, stringBuilder);
                     }
                     continue;
                  }
               } else {
                  spr = this.pzoptTileMgr != null ? pzopt.TileDefPreload.addSprite(sprMan, tileName, this.getSpriteID(fileNumber, tilesetNumber, m)) : sprMan.AddSprite(tileName, this.getSpriteID(fileNumber, tilesetNumber, m)); // pzopt: tileDefPreload, texture bound at load
               }

               if (Core.debug) {
                  if (this.allTiles.containsKey(name)) {
                     if (!bPatch) {
                        this.allTiles.get(name).add(tileName);
                     }
                  } else {
                     ArrayList<String> newMap = new ArrayList<>();
                     newMap.add(tileName);
                     this.allTiles.put(name, newMap);
                  }
               }

               buffer.add(spr);
               if (!bPatch) {
                  spr.setName(tileName);
                  spr.tilesetName = name;
                  spr.tileSheetIndex = m;
               }

               if (spr.name.contains("damaged") || spr.name.contains("trash_")) {
                  spr.attachedFloor = true;
                  spr.getProperties().set("attachedFloor", "true");
               }

               if (spr.name.startsWith("f_bushes") && m <= 31) {
                  spr.isBush = true;
                  spr.attachedFloor = true;
               }

               int nProps = readInt(in);

               for (int l = 0; l < nProps; l++) {
                  str = readString(in, stringBuilder);
                  String prop = str.trim();
                  str = readString(in, stringBuilder);
                  String val = str.trim();
                  IsoObjectType type = IsoObjectType.FromString(prop);
                  if (type != IsoObjectType.MAX) {
                     if (spr.getTileType() != IsoObjectType.doorW && spr.getTileType() != IsoObjectType.doorN || type != IsoObjectType.wall) {
                        spr.setTileType(type);
                     }

                     if (type == IsoObjectType.doorW) {
                        spr.getProperties().set(IsoFlagType.doorW);
                     } else if (type == IsoObjectType.doorN) {
                        spr.getProperties().set(IsoFlagType.doorN);
                     }
                  } else {
                     prop = sharedStrings.get(prop);
                     if (prop.equals("firerequirement")) {
                        spr.firerequirement = Integer.parseInt(val);
                     } else if (prop.equals("fireRequirement")) {
                        spr.firerequirement = Integer.parseInt(val);
                     } else if (prop.equals("BurntTile")) {
                        spr.burntTile = val;
                     } else if (prop.equals("ForceAmbient")) {
                        spr.forceAmbient = true;
                        spr.getProperties().set(prop, val);
                     } else if (prop.equals("solidfloor")) {
                        spr.solidfloor = true;
                        spr.getProperties().set(prop, val);
                     } else if (prop.equals("canBeRemoved")) {
                        spr.canBeRemoved = true;
                        spr.getProperties().set(prop, val);
                     } else if (prop.equals("attachedFloor")) {
                        spr.attachedFloor = true;
                        spr.getProperties().set(prop, val);
                     } else if (prop.equals("cutW")) {
                        spr.cutW = true;
                        spr.getProperties().set(prop, val);
                     } else if (prop.equals("cutN")) {
                        spr.cutN = true;
                        spr.getProperties().set(prop, val);
                     } else if (prop.equals("solid")) {
                        spr.solid = true;
                        spr.getProperties().set(prop, val);
                     } else if (prop.equals("solidtrans")) {
                        spr.solidTrans = true;
                        spr.getProperties().set(prop, val);
                     } else if (prop.equals("invisible")) {
                        spr.invisible = true;
                        spr.getProperties().set(prop, val);
                     } else if (prop.equals("alwaysDraw")) {
                        spr.alwaysDraw = true;
                        spr.getProperties().set(prop, val);
                     } else if (prop.equals("FasciaEdge")) {
                        spr.setFasciaEdge((FasciaEdge)StringUtils.tryParseEnum(FasciaEdge.class, val, null));
                        spr.getProperties().set(prop, val);
                     } else if (prop.equals("forceRender")) {
                        spr.forceRender = true;
                        spr.getProperties().set(prop, val);
                     } else if ("FloorHeight".equals(prop)) {
                        if ("OneThird".equals(val)) {
                           spr.getProperties().set(IsoFlagType.FloorHeightOneThird);
                        } else if ("TwoThirds".equals(val)) {
                           spr.getProperties().set(IsoFlagType.FloorHeightTwoThirds);
                        }
                     } else if (prop.equals("MoveWithWind")) {
                        spr.moveWithWind = true;
                        spr.getProperties().set(prop, val);
                     } else if (prop.equals("WindType")) {
                        spr.windType = Integer.parseInt(val);
                        spr.getProperties().set(prop, val);
                     } else if (prop.equals("RenderLayer")) {
                        spr.getProperties().set(prop, val);
                        if ("Default".equals(val)) {
                           spr.renderLayer = 0;
                        } else if ("Floor".equals(val)) {
                           spr.renderLayer = 1;
                        }
                     } else if (prop.equals("TreatAsWallOrder")) {
                        spr.treatAsWallOrder = true;
                        spr.getProperties().set(prop, val);
                     } else {
                        spr.getProperties().set(prop, val);
                        if ("WindowN".equals(prop) || "WindowW".equals(prop)) {
                           spr.getProperties().set(prop, val, false);
                        }
                     }
                  }

                  if (type == IsoObjectType.tree) {
                     if (spr.name.equals("e_riverbirch_1_1")) {
                        val = "1";
                     }

                     spr.getProperties().set("tree", val);
                     spr.getProperties().set(IsoFlagType.blocksight);
                     int size = Integer.parseInt(val);
                     if (name.startsWith("vegetation_trees")) {
                        size = 4;
                     }

                     if (size < 1) {
                        size = 1;
                     }

                     if (size > 4) {
                        size = 4;
                     }

                     if (size == 1 || size == 2) {
                        spr.getProperties().unset(IsoFlagType.blocksight);
                     }

                     spr.getProperties().set("MaterialType", "Wood_Solid");
                  }

                  if (prop.equals("interior") && val.equals("false")) {
                     spr.getProperties().set(IsoFlagType.exterior);
                  }

                  if (prop.equals("HoppableN")) {
                     spr.getProperties().set(IsoFlagType.collideN);
                     spr.getProperties().set(IsoFlagType.canPathN);
                     spr.getProperties().set(IsoFlagType.transparentN);
                  }

                  if (prop.equals("HoppableW")) {
                     spr.getProperties().set(IsoFlagType.collideW);
                     spr.getProperties().set(IsoFlagType.canPathW);
                     spr.getProperties().set(IsoFlagType.transparentW);
                  }

                  if (prop.equals("WallN")) {
                     spr.getProperties().set(IsoFlagType.collideN);
                     spr.getProperties().set(IsoFlagType.cutN);
                     spr.setTileType(IsoObjectType.wall);
                     spr.cutN = true;
                     spr.getProperties().set("WallN", "", false);
                  }

                  if (prop.equals("CantClimb")) {
                     spr.getProperties().set(IsoFlagType.CantClimb);
                  } else if (IsoPropertyType.CONTAINER.isProperty(prop)) {
                     spr.getProperties().set(prop, val, false);
                  } else if (prop.equals("WallNTrans")) {
                     spr.getProperties().set(IsoFlagType.collideN);
                     spr.getProperties().set(IsoFlagType.cutN);
                     spr.getProperties().set(IsoFlagType.transparentN);
                     spr.setTileType(IsoObjectType.wall);
                     spr.cutN = true;
                     spr.getProperties().set("WallNTrans", "", false);
                  } else if (prop.equals("WallW")) {
                     spr.getProperties().set(IsoFlagType.collideW);
                     spr.getProperties().set(IsoFlagType.cutW);
                     spr.setTileType(IsoObjectType.wall);
                     spr.cutW = true;
                     spr.getProperties().set("WallW", "", false);
                  } else if (prop.equals("windowN")) {
                     spr.getProperties().set("WindowN", "WindowN");
                     spr.getProperties().set(IsoFlagType.transparentN);
                     spr.getProperties().set("WindowN", "WindowN", false);
                  } else if (prop.equals("windowW")) {
                     spr.getProperties().set("WindowW", "WindowW");
                     spr.getProperties().set(IsoFlagType.transparentW);
                     spr.getProperties().set("WindowW", "WindowW", false);
                  } else if (prop.equals("cutW")) {
                     spr.getProperties().set(IsoFlagType.cutW);
                     spr.cutW = true;
                  } else if (prop.equals("cutN")) {
                     spr.getProperties().set(IsoFlagType.cutN);
                     spr.cutN = true;
                  } else if (prop.equals("WallWTrans")) {
                     spr.getProperties().set(IsoFlagType.collideW);
                     spr.getProperties().set(IsoFlagType.transparentW);
                     spr.getProperties().set(IsoFlagType.cutW);
                     spr.setTileType(IsoObjectType.wall);
                     spr.cutW = true;
                     spr.getProperties().set("WallWTrans", "", false);
                  } else if (prop.equals("DoorWallN")) {
                     spr.getProperties().set(IsoFlagType.cutN);
                     spr.cutN = true;
                     spr.getProperties().set("DoorWallN", "", false);
                  } else if (prop.equals("DoorWallNTrans")) {
                     spr.getProperties().set(IsoFlagType.cutN);
                     spr.getProperties().set(IsoFlagType.transparentN);
                     spr.cutN = true;
                     spr.getProperties().set("DoorWallNTrans", "", false);
                  } else if (prop.equals("DoorWallW")) {
                     spr.getProperties().set(IsoFlagType.cutW);
                     spr.cutW = true;
                     spr.getProperties().set("DoorWallW", "", false);
                  } else if (prop.equals("DoorWallWTrans")) {
                     spr.getProperties().set(IsoFlagType.cutW);
                     spr.getProperties().set(IsoFlagType.transparentW);
                     spr.cutW = true;
                     spr.getProperties().set("DoorWallWTrans", "", false);
                  } else if (prop.equals("WallNW")) {
                     spr.getProperties().set(IsoFlagType.collideN);
                     spr.getProperties().set(IsoFlagType.cutN);
                     spr.getProperties().set(IsoFlagType.collideW);
                     spr.getProperties().set(IsoFlagType.cutW);
                     spr.setTileType(IsoObjectType.wall);
                     spr.cutW = true;
                     spr.cutN = true;
                     spr.getProperties().set("WallNW", "", false);
                  } else if (prop.equals("WallNWTrans")) {
                     spr.getProperties().set(IsoFlagType.collideN);
                     spr.getProperties().set(IsoFlagType.cutN);
                     spr.getProperties().set(IsoFlagType.collideW);
                     spr.getProperties().set(IsoFlagType.transparentN);
                     spr.getProperties().set(IsoFlagType.transparentW);
                     spr.getProperties().set(IsoFlagType.cutW);
                     spr.setTileType(IsoObjectType.wall);
                     spr.cutW = true;
                     spr.cutN = true;
                     spr.getProperties().set("WallNWTrans", "", false);
                  } else if (prop.equals("WallSE")) {
                     spr.getProperties().set(IsoFlagType.cutW);
                     spr.getProperties().set(IsoFlagType.WallSE);
                     spr.getProperties().set("WallSE", "WallSE");
                     spr.cutW = true;
                  } else if (prop.equals("WindowW")) {
                     spr.getProperties().set(IsoFlagType.canPathW);
                     spr.getProperties().set(IsoFlagType.collideW);
                     spr.getProperties().set(IsoFlagType.cutW);
                     spr.getProperties().set(IsoFlagType.transparentW);
                     spr.setTileType(IsoObjectType.windowFW);
                     if (spr.getProperties().has(IsoFlagType.HoppableW)) {
                        DebugType.Moveable.error("ERROR: WindowW sprite shouldn't have HoppableW (%s)", new Object[]{spr.getName()});
                        spr.getProperties().unset(IsoFlagType.HoppableW);
                     }

                     spr.cutW = true;
                  } else if (prop.equals("WindowN")) {
                     spr.getProperties().set(IsoFlagType.canPathN);
                     spr.getProperties().set(IsoFlagType.collideN);
                     spr.getProperties().set(IsoFlagType.cutN);
                     spr.getProperties().set(IsoFlagType.transparentN);
                     spr.setTileType(IsoObjectType.windowFN);
                     if (spr.getProperties().has(IsoFlagType.HoppableN)) {
                        DebugType.Moveable.error("ERROR: WindowN sprite shouldn't have HoppableN (%s)", new Object[]{spr.getName()});
                        spr.getProperties().unset(IsoFlagType.HoppableN);
                     }

                     spr.cutN = true;
                  } else if (prop.equals("UnbreakableWindowW")) {
                     spr.getProperties().set(IsoFlagType.canPathW);
                     spr.getProperties().set(IsoFlagType.collideW);
                     spr.getProperties().set(IsoFlagType.cutW);
                     spr.getProperties().set(IsoFlagType.transparentW);
                     spr.getProperties().set(IsoFlagType.collideW);
                     spr.setTileType(IsoObjectType.wall);
                     spr.cutW = true;
                  } else if (prop.equals("UnbreakableWindowN")) {
                     spr.getProperties().set(IsoFlagType.canPathN);
                     spr.getProperties().set(IsoFlagType.collideN);
                     spr.getProperties().set(IsoFlagType.cutN);
                     spr.getProperties().set(IsoFlagType.transparentN);
                     spr.getProperties().set(IsoFlagType.collideN);
                     spr.setTileType(IsoObjectType.wall);
                     spr.cutN = true;
                  } else if (prop.equals("UnbreakableWindowNW")) {
                     spr.getProperties().set(IsoFlagType.cutN);
                     spr.getProperties().set(IsoFlagType.transparentN);
                     spr.getProperties().set(IsoFlagType.collideN);
                     spr.getProperties().set(IsoFlagType.cutN);
                     spr.getProperties().set(IsoFlagType.collideW);
                     spr.getProperties().set(IsoFlagType.cutW);
                     spr.setTileType(IsoObjectType.wall);
                     spr.cutW = true;
                     spr.cutN = true;
                  } else if ("NoWallLighting".equals(prop)) {
                     spr.getProperties().set(IsoFlagType.NoWallLighting);
                  } else if ("ForceAmbient".equals(prop)) {
                     spr.getProperties().set(IsoFlagType.ForceAmbient);
                  }

                  if (prop.equals("name")) {
                     spr.setParentObjectName(val);
                  }
               }

               if (spr.getProperties().has(IsoPropertyType.RED_LIGHT)
                  || spr.getProperties().has(IsoPropertyType.GREEN_LIGHT)
                  || spr.getProperties().has(IsoPropertyType.BLUE_LIGHT)) {
                  if (!spr.getProperties().has(IsoPropertyType.RED_LIGHT)) {
                     spr.getProperties().set(IsoPropertyType.RED_LIGHT, "0");
                  }

                  if (!spr.getProperties().has(IsoPropertyType.GREEN_LIGHT)) {
                     spr.getProperties().set(IsoPropertyType.GREEN_LIGHT, "0");
                  }

                  if (!spr.getProperties().has(IsoPropertyType.BLUE_LIGHT)) {
                     spr.getProperties().set(IsoPropertyType.BLUE_LIGHT, "0");
                  }
               }

               spr.getProperties().CreateKeySet();
               if (Core.debug && spr.getProperties().has(IsoPropertyType.SMASHED_TILE_OFFSET) && !spr.getProperties().has(IsoPropertyType.GLASS_REMOVED_OFFSET)
                  )
                {
                  DebugType.Sprite.error("Window sprite has SmashedTileOffset but no GlassRemovedOffset (" + spr.getName() + ")");
               }
            }

            this.setOpenDoorProperties(name, buffer);
            groupMap.clear();

            for (IsoSprite spr : buffer) {
               if (spr.getProperties().has("StopCar") && spr.getTileType() != IsoObjectType.tree) {
                  spr.setTileType(IsoObjectType.isMoveAbleObject);
               }

               if (spr.getProperties().has("IsMoveAble")) {
                  if (spr.getProperties().has(IsoPropertyType.CUSTOM_NAME) && !spr.getProperties().get(IsoPropertyType.CUSTOM_NAME).equals("")) {
                     movableSpriteCount++;
                     if (spr.getProperties().has(IsoPropertyType.GROUP_NAME)) {
                        String group = spr.getProperties().get(IsoPropertyType.GROUP_NAME) + " " + spr.getProperties().get(IsoPropertyType.CUSTOM_NAME);
                        if (!groupMap.containsKey(group)) {
                           groupMap.put(group, new ArrayList<>());
                        }

                        groupMap.get(group).add(spr);
                        customNames.add(group);
                     } else {
                        if (!uniqueMovables.containsKey(name)) {
                           uniqueMovables.put(name, new ArrayList<>());
                        }

                        if (!uniqueMovables.get(name).contains(spr.getProperties().get(IsoPropertyType.CUSTOM_NAME))) {
                           uniqueMovables.get(name).add(spr.getProperties().get(IsoPropertyType.CUSTOM_NAME));
                        }

                        movableOneSpriteCount++;
                        customNames.add(spr.getProperties().get(IsoPropertyType.CUSTOM_NAME));
                     }
                  } else {
                     DebugType.Moveable.println("[IMPORTANT] MOVABLES: Object has no custom name defined: sheet = " + name);
                  }
               } else if (spr.getProperties().has("SpriteGridPos")) {
                  if (StringUtils.isNullOrWhitespace(spr.getProperties().get(IsoPropertyType.CUSTOM_NAME))) {
                     DebugType.Moveable.println("[IMPORTANT] MOVABLES: Object has no custom name defined: sheet = " + name);
                  } else if (spr.getProperties().has(IsoPropertyType.GROUP_NAME)) {
                     String group = spr.getProperties().get(IsoPropertyType.GROUP_NAME) + " " + spr.getProperties().get(IsoPropertyType.CUSTOM_NAME);
                     if (!groupMap.containsKey(group)) {
                        groupMap.put(group, new ArrayList<>());
                     }

                     groupMap.get(group).add(spr);
                  }
               }
            }

            for (Entry<String, ArrayList<IsoSprite>> entry : groupMap.entrySet()) {
               String fullgroup = entry.getKey();
               if (!uniqueMovables.containsKey(name)) {
                  uniqueMovables.put(name, new ArrayList<>());
               }

               if (!uniqueMovables.get(name).contains(fullgroup)) {
                  uniqueMovables.get(name).add(fullgroup);
               }

               ArrayList<IsoSprite> members = entry.getValue();
               if (members.size() == 1) {
                  DebugType.Moveable.debugln("MOVABLES: Object has only one face defined for group: (" + fullgroup + ") sheet = " + name);
               }

               if (members.size() == 3) {
                  DebugType.Moveable
                     .debugln("MOVABLES: Object only has 3 sprites, _might_ have a error in settings, group: (" + fullgroup + ") sheet = " + name);
               }

               for (String faceName : faceNames) {
                  faceMap.get(faceName).clear();
               }

               boolean isSpriteGrid = members.get(0).getProperties().has("SpriteGridPos")
                  && !members.get(0).getProperties().get("SpriteGridPos").equals("None");
               boolean isValid = true;

               for (IsoSprite current : members) {
                  boolean testIsSpriteGrid = current.getProperties().has("SpriteGridPos") && !current.getProperties().get("SpriteGridPos").equals("None");
                  if (isSpriteGrid != testIsSpriteGrid) {
                     isValid = false;
                     DebugType.Moveable.debugln("MOVABLES: Difference in SpriteGrid settings for members of group: (" + fullgroup + ") sheet = " + name);
                     break;
                  }

                  if (!current.getProperties().has(IsoPropertyType.FACING)) {
                     isValid = false;
                  } else {
                     switch (current.getProperties().get(IsoPropertyType.FACING)) {
                        case "N":
                           faceMap.get("N").add(current);
                           break;
                        case "E":
                           faceMap.get("E").add(current);
                           break;
                        case "S":
                           faceMap.get("S").add(current);
                           break;
                        case "W":
                           faceMap.get("W").add(current);
                           break;
                        default:
                           DebugType.Moveable
                              .debugln(
                                 "MOVABLES: Invalid face ("
                                    + current.getProperties().get(IsoPropertyType.FACING)
                                    + ") for group: ("
                                    + fullgroup
                                    + ") sheet = "
                                    + name
                              );
                           isValid = false;
                     }
                  }

                  if (!isValid) {
                     DebugType.Moveable.debugln("MOVABLES: Not all members have a valid face defined for group: (" + fullgroup + ") sheet = " + name);
                     break;
                  }
               }

               if (isValid) {
                  if (isSpriteGrid) {
                     int sprCount = 0;
                     IsoSpriteGrid[] grids = new IsoSpriteGrid[faceNames.length];

                     for (int i = 0; i < faceNames.length; i++) {
                        ArrayList<IsoSprite> direction = faceMap.get(faceNames[i]);
                        if (!direction.isEmpty()) {
                           if (sprCount == 0) {
                              sprCount = direction.size();
                           }

                           if (sprCount != direction.size()) {
                              DebugType.Moveable
                                 .debugln("MOVABLES: Sprite count mismatch for multi sprite movable, group: (" + fullgroup + ") sheet = " + name);
                              isValid = false;
                              break;
                           }

                           spriteGridParseData.clear();

                           for (IsoSprite mem : direction) {
                              String pos = mem.getProperties().get("SpriteGridPos");
                              String[] parts = pos.split(",");
                              if (parts.length < 2 || parts.length > 3) {
                                 DebugType.Moveable
                                    .debugln("MOVABLES: SpriteGrid position error for multi sprite movable, group: (" + fullgroup + ") sheet = " + name);
                                 isValid = false;
                                 break;
                              }

                              int x = Integer.parseInt(parts[0]);
                              int y = Integer.parseInt(parts[1]);
                              int spriteGridZ = 0;
                              if (parts.length == 3) {
                                 spriteGridZ = Integer.parseInt(parts[2]);
                              }

                              if (mem.getProperties().has("SpriteGridLevel")) {
                                 spriteGridZ = Integer.parseInt(mem.getProperties().get("SpriteGridLevel"));
                                 if (spriteGridZ < 0) {
                                    DebugType.Moveable
                                       .debugln("MOVABLES: invalid SpriteGirdLevel for multi sprite movable, group: (" + fullgroup + ") sheet = " + name);
                                    isValid = false;
                                    break;
                                 }
                              }

                              Level levelData = spriteGridParseData.getOrCreateLevel(spriteGridZ);
                              if (levelData.xyToSprite.containsKey(pos)) {
                                 DebugType.Moveable
                                    .debugln(
                                       "MOVABLES: double SpriteGrid position ("
                                          + pos
                                          + ") for multi sprite movable, group: ("
                                          + fullgroup
                                          + ") sheet = "
                                          + name
                                    );
                                 isValid = false;
                                 break;
                              }

                              levelData.xyToSprite.put(pos, mem);
                              levelData.width = PZMath.max(levelData.width, x + 1);
                              levelData.height = PZMath.max(levelData.height, y + 1);
                              spriteGridParseData.width = PZMath.max(spriteGridParseData.width, levelData.width);
                              spriteGridParseData.height = PZMath.max(spriteGridParseData.height, levelData.height);
                           }

                           if (!isValid) {
                              break;
                           }

                           if (!spriteGridParseData.isValid()) {
                              DebugType.Moveable
                                 .debugln("MOVABLES: SpriteGrid dimensions error for multi sprite movable, group: (" + fullgroup + ") sheet = " + name);
                              isValid = false;
                              break;
                           }

                           grids[i] = new IsoSpriteGrid(spriteGridParseData.width, spriteGridParseData.height, spriteGridParseData.levels.size());

                           for (Level levelData : spriteGridParseData.levels) {
                              for (Entry<String, IsoSprite> entry1 : levelData.xyToSprite.entrySet()) {
                                 String pos = entry1.getKey();
                                 IsoSprite sprite = entry1.getValue();
                                 String[] parts = pos.split(",");
                                 int x = Integer.parseInt(parts[0]);
                                 int y = Integer.parseInt(parts[1]);
                                 grids[i].setSprite(x, y, levelData.z, sprite);
                              }
                           }

                           if (!grids[i].validate()) {
                              DebugType.Moveable
                                 .debugln("MOVABLES: SpriteGrid didn't validate for multi sprite movable, group: (" + fullgroup + ") sheet = " + name);
                              isValid = false;
                              break;
                           }
                        }
                     }

                     if (isValid && sprCount != 0) {
                        movablesMultiCount++;

                        for (int i = 0; i < faceNames.length; i++) {
                           IsoSpriteGrid grid = grids[i];
                           if (grid != null) {
                              for (IsoSprite member : grid.getSprites()) {
                                 if (member != null) {
                                    member.setSpriteGrid(grid);

                                    for (int j = 0; j < faceNames.length; j++) {
                                       if (j != i && grids[j] != null) {
                                          IsoSprite anchorSprite = grids[j].getAnchorSprite();
                                          member.getProperties()
                                             .set(faceNames[j] + "offset", Integer.toString(anchorSprite.tileSheetIndex - member.tileSheetIndex));
                                       }
                                    }
                                 }
                              }
                           }
                        }
                     } else {
                        DebugType.Moveable.debugln("MOVABLES: Error in multi sprite movable, group: (" + fullgroup + ") sheet = " + name);
                     }
                  } else if (members.size() > 4) {
                     DebugType.Moveable.debugln("MOVABLES: Object has too many faces defined for group: (" + fullgroup + ") sheet = " + name);
                  } else {
                     for (String faceName : faceNames) {
                        if (faceMap.get(faceName).size() > 1) {
                           DebugType.Moveable.debugln("MOVABLES: " + faceName + " face defined more than once for group: (" + fullgroup + ") sheet = " + name);
                           isValid = false;
                        }
                     }

                     if (isValid) {
                        movablesSingleCount++;

                        for (IsoSprite current : members) {
                           for (String faceName : faceNames) {
                              ArrayList<IsoSprite> direction = faceMap.get(faceName);
                              if (!direction.isEmpty() && direction.get(0) != current) {
                                 current.getProperties().set(faceName + "offset", Integer.toString(buffer.indexOf(direction.get(0)) - buffer.indexOf(current)));
                              }
                           }
                        }
                     }
                  }
               }
            }

            buffer.clear();
         }

         if (dumpUntranslatedCustomNameToStdout) {
            ArrayList<String> customNamesSorted = new ArrayList<>(customNames);
            Collections.sort(customNamesSorted);
            StringBuilder sb = new StringBuilder();

            for (String customName : customNamesSorted) {
               if (Translator.getMoveableDisplayNameOrNull(customName) == null) {
                  sb.append(customName.replaceAll(" ", "_").replaceAll("-", "_").replaceAll("'", "").replaceAll("\\.", "") + " = \"" + customName + "\",\n");
               }
            }

            String str = sb.toString();
            if (!str.isEmpty() && Core.debug) {
               DebugType.Translation.debugln("Missing translations in Moveables_EN.txt:\n" + str);
            }
         }
      } catch (Exception ex) {
         ExceptionLogger.logException(ex);
      }
   }

   private void GenerateTilePropertyLookupTables() {
      TilePropertyAliasMap.instance.Generate(PropertyValueMap);
      PropertyValueMap.clear();
   }

   public void LoadTileDefinitionsPropertyStrings(IsoSpriteManager sprMan, String filename, int fileNumber) {
      DebugType.DetailedInfo.trace("tiledef: loading " + filename);
      if (!GameServer.server && this.pzoptTileMgr == null) { // pzopt: tileDefPreload runs on a boot thread, no loading screen to pump
         Thread.yield();
         Core.getInstance().DoFrameReady();
      }

      int maxTilesetsPerFile = fileNumber == 1 ? 1024 : 512;
      int maxTilesPerTileset = fileNumber == 1 ? 1024 : 512;

      try (
         FileInputStream fis = new FileInputStream(filename);
         BufferedInputStream in = new BufferedInputStream(fis);
      ) {
         byte[] magic = new byte[TDEF_FILE_MAGIC.length];
         int nBytes = in.read(magic);
         if (nBytes != TDEF_FILE_MAGIC.length || !Arrays.equals(magic, TDEF_FILE_MAGIC)) {
            throw new IOException("not magic");
         }

         int version = readInt(in);
         if (version < 1 || version > 1) {
            throw new IOException("%s: invalid version %d, must be from %d to %d".formatted(filename, version, 1, 1));
         }

         int numTilesheets = readInt(in);
         if (numTilesheets < 0 || numTilesheets > maxTilesetsPerFile) {
            throw new IOException("%s: invalid number of tilesets %d, must be from 0 to %d".formatted(filename, numTilesheets, maxTilesetsPerFile));
         }

         SharedStrings sharedStrings = new SharedStrings();
         StringBuilder stringBuilder = new StringBuilder();

         for (int n = 0; n < numTilesheets; n++) {
            String str = readString(in, stringBuilder);
            String name = str.trim();
            String imageName = readString(in, stringBuilder);
            this.tileImages.add(imageName);
            int wTiles = readInt(in);
            int hTiles = readInt(in);
            int tilesetNumber = readInt(in);
            if (tilesetNumber < 1 || tilesetNumber > maxTilesetsPerFile) {
               throw new IOException("%s: invalid tileset number %d, must be from 1 to %d in %s".formatted(filename, tilesetNumber, maxTilesetsPerFile, name));
            }

            int nTiles = readInt(in);
            if (nTiles < 0 || nTiles > maxTilesPerTileset) {
               throw new IOException("%s: invalid number of tiles %d, must be from 0 to %d in %s".formatted(filename, nTiles, maxTilesPerTileset, name));
            }

            for (int m = 0; m < nTiles; m++) {
               int nProps = readInt(in);

               for (int l = 0; l < nProps; l++) {
                  str = readString(in, stringBuilder);
                  String prop = str.trim();
                  str = readString(in, stringBuilder);
                  String val = str.trim();
                  prop = sharedStrings.get(prop);
                  ArrayList<String> values;
                  if (PropertyValueMap.containsKey(prop)) {
                     values = PropertyValueMap.get(prop);
                  } else {
                     values = new ArrayList<>();
                     PropertyValueMap.put(prop, values);
                  }

                  if (!values.contains(val)) {
                     values.add(val);
                  }
               }
            }
         }
      } catch (Exception ex) {
         Logger.getLogger(GameWindow.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
      }
   }

   private void SetCustomPropertyValues() {
      PropertyValueMap.get("WindowN").add("WindowN");
      PropertyValueMap.get("WindowW").add("WindowW");
      PropertyValueMap.get("DoorWallN").add("DoorWallN");
      PropertyValueMap.get("DoorWallW").add("DoorWallW");
      PropertyValueMap.get("WallSE").add("WallSE");
      ArrayList<String> offsets = new ArrayList<>();

      for (int i = -96; i <= 96; i++) {
         String str = Integer.toString(i);
         offsets.add(str);
      }

      PropertyValueMap.put("Noffset", offsets);
      PropertyValueMap.put("Soffset", offsets);
      PropertyValueMap.put("Woffset", offsets);
      PropertyValueMap.put("Eoffset", offsets);
      PropertyValueMap.get("tree").add("5");
      PropertyValueMap.get("tree").add("6");
      PropertyValueMap.get("tree").add("7");
      PropertyValueMap.get("tree").add("8");
      PropertyValueMap.get(IsoPropertyType.RED_LIGHT.getName()).add("0");
      PropertyValueMap.get(IsoPropertyType.GREEN_LIGHT.getName()).add("0");
      PropertyValueMap.get(IsoPropertyType.BLUE_LIGHT.getName()).add("0");

      for (int i = 0; i <= 96; i++) {
         String value = String.valueOf(i);
         ArrayList<String> values = PropertyValueMap.get("ItemHeight");
         if (!values.contains(value)) {
            values.add(value);
         }

         values = PropertyValueMap.get("Surface");
         if (!values.contains(value)) {
            values.add(value);
         }
      }
   }

   private void setOpenDoorProperties(String tilesheetName, ArrayList<IsoSprite> sprites) {
      for (int i = 0; i < sprites.size(); i++) {
         IsoSprite spr = sprites.get(i);
         if ((spr.getTileType() == IsoObjectType.doorN || spr.getTileType() == IsoObjectType.doorW) && !spr.getProperties().has(IsoFlagType.open)) {
            String doubleDoor = spr.getProperties().get(IsoPropertyType.DOUBLE_DOOR);
            if (doubleDoor != null) {
               int index = PZMath.tryParseInt(doubleDoor, -1);
               if (index >= 5) {
                  spr.getProperties().set(IsoFlagType.open);
               }
            } else {
               String garageDoor = spr.getProperties().get(IsoPropertyType.GARAGE_DOOR);
               if (garageDoor != null) {
                  int index = PZMath.tryParseInt(garageDoor, -1);
                  if (index >= 4) {
                     spr.getProperties().set(IsoFlagType.open);
                  }
               } else {
                  IsoSprite openSprite = (IsoSprite)(this.pzoptTileMgr != null ? this.pzoptTileMgr : IsoSpriteManager.instance).namedMap.get(tilesheetName + "_" + (spr.tileSheetIndex + 2)); // pzopt: tileDefPreload builds into its own manager
                  if (openSprite != null) {
                     openSprite.setTileType(spr.getTileType());
                     openSprite.getProperties().set(spr.getTileType() == IsoObjectType.doorN ? IsoFlagType.doorN : IsoFlagType.doorW);
                     openSprite.getProperties().set(IsoFlagType.open);
                  }
               }
            }
         }
      }
   }

   private void saveMovableStats(Map<String, ArrayList<String>> names, int num, int onesprites, int singles, int multies, int totalsprites) throws FileNotFoundException, IOException {
      File path = new File(ZomboidFileSystem.instance.getCacheDir());
      if (path.exists() && path.isDirectory()) {
         File f = new File(ZomboidFileSystem.instance.getCacheDir() + File.separator + "movables_stats_" + num + ".txt");

         try (FileWriter w = new FileWriter(f, false)) {
            w.write("### Movable objects ###" + System.lineSeparator());
            w.write("Single Face: " + onesprites + System.lineSeparator());
            w.write("Multi Face: " + singles + System.lineSeparator());
            w.write("Multi Face & Multi Sprite: " + multies + System.lineSeparator());
            w.write("Total objects : " + (onesprites + singles + multies) + System.lineSeparator());
            w.write(" " + System.lineSeparator());
            w.write("Total sprites : " + totalsprites + System.lineSeparator());
            w.write(" " + System.lineSeparator());

            for (Entry<String, ArrayList<String>> entry : names.entrySet()) {
               w.write(entry.getKey() + System.lineSeparator());

               for (String name : entry.getValue()) {
                  w.write("\t" + name + System.lineSeparator());
               }
            }
         } catch (Exception ex) {
            ExceptionLogger.logException(ex);
         }
      }
   }

   private void addJumboTreeTileset(IsoSpriteManager sprMan, int fileNumber, String type, String name, int tilesetNumber, int rows, int columns, int windType) {
      for (int row = 0; row < rows; row++) {
         for (int col = 0; col < columns; col++) {
            String tilesetName = "e_" + name + type + "_1";
            int tileNum = row * columns + col;
            IsoSprite spr = sprMan.AddSprite(tilesetName + "_" + tileNum, this.getSpriteID(fileNumber, tilesetNumber, tileNum));
            assert GameServer.server || !spr.hasNoTextures();
            spr.setName(tilesetName + "_" + tileNum);
            spr.setTileType(IsoObjectType.tree);
            if (type.contains("JUMBOXXL")) {
               spr.getProperties().set("tree", "8");
            } else if (type.contains("JUMBOXL")) {
               spr.getProperties().set("tree", "7");
            } else if (type.contains("JUMBO") && col == 1) {
               spr.getProperties().set("tree", "6");
            } else if (type.contains("JUMBO") && col == 0) {
               spr.getProperties().set("tree", "5");
            } else {
               spr.getProperties().set("tree", "5");
            }

            spr.getProperties().unset(IsoFlagType.solid);
            spr.getProperties().set(IsoFlagType.blocksight);
            spr.getProperties().set("MaterialType", "Wood_Solid");
            spr.getProperties().CreateKeySet();
            spr.moveWithWind = true;
            spr.windType = windType;
         }
      }
   }

   private void addJumboTrunkTileset(IsoSpriteManager sprMan, int fileNumber, String type, int tilesetNumber) {
      for (int i = 0; i <= 114; i++) {
         String tilesetName = "e_trunks_" + type + "_" + i;
         IsoSprite spr = sprMan.AddSprite(tilesetName, fileNumber * 512 * 512 + tilesetNumber * 512 + i);
         assert GameServer.server || !spr.hasNoTextures();
         spr.setName(tilesetName);
         spr.setTileType(IsoObjectType.tree);
         if (type.contains("JUMBOXXL")) {
            spr.getProperties().set("tree", "8");
         } else if (type.contains("JUMBOXL")) {
            spr.getProperties().set("tree", "7");
         } else {
            spr.getProperties().set("tree", "5");
         }

         spr.getProperties().unset(IsoFlagType.solid);
         spr.getProperties().set(IsoFlagType.blocksight);
         spr.getProperties().set("MaterialType", "Wood_Solid");
         spr.getProperties().CreateKeySet();
      }
   }

   /**
    * pzopt: tileDefPreload. The tile definition load of init() into the given private manager, from a boot thread (see
    * pzopt.TileDefPreload): the same files, order and property passes, mod tile definitions resolved as
    * ZomboidFileSystem.loadModTileDefs does. The tile image list is copied into the given list.
    */
   public void pzoptPreloadTileDefs(IsoSpriteManager pm, java.util.List<String> modTileFiles, java.util.List<Integer> modFileNumbers, java.util.List<String> tileImagesOut) {
      this.pzoptTileMgr = pm;
      try {
         this.tileImages.clear();
         ZomboidFileSystem zfs = ZomboidFileSystem.instance;
         this.LoadTileDefinitionsPropertyStrings(pm, zfs.getMediaPath("newtiledefinitions.tiles"), 1);
         this.LoadTileDefinitionsPropertyStrings(pm, zfs.getMediaPath("tiledefinitions_erosion.tiles"), 2);
         this.LoadTileDefinitionsPropertyStrings(pm, zfs.getMediaPath("tiledefinitions_overlays.tiles"), 4);
         this.LoadTileDefinitionsPropertyStrings(pm, zfs.getMediaPath("tiledefinitions_b42chunkcaching.tiles"), 5);
         this.LoadTileDefinitionsPropertyStrings(pm, zfs.getMediaPath("tiledefinitions_noiseworks.patch.tiles"), -1);
         this.LoadTileDefinitionsPropertyStrings(pm, zfs.getMediaPath("jumbo_trees_big.tiles"), 6);
         this.LoadTileDefinitionsPropertyStrings(pm, zfs.getMediaPath("jumbo_trees.tiles"), 8);
         for (int i = 0; i < modTileFiles.size(); i++) {
            this.LoadTileDefinitionsPropertyStrings(pm, modTileFiles.get(i), modFileNumbers.get(i));
         }
         this.SetCustomPropertyValues();
         this.GenerateTilePropertyLookupTables();
         this.LoadTileDefinitions(pm, zfs.getMediaPath("newtiledefinitions.tiles"), 1);
         this.LoadTileDefinitions(pm, zfs.getMediaPath("tiledefinitions_erosion.tiles"), 2);
         this.LoadTileDefinitions(pm, zfs.getMediaPath("tiledefinitions_overlays.tiles"), 4);
         this.LoadTileDefinitions(pm, zfs.getMediaPath("tiledefinitions_b42chunkcaching.tiles"), 5);
         this.LoadTileDefinitions(pm, zfs.getMediaPath("tiledefinitions_noiseworks.patch.tiles"), -1);
         this.LoadTileDefinitions(pm, zfs.getMediaPath("jumbo_trees_big.tiles"), 6);
         this.LoadTileDefinitions(pm, zfs.getMediaPath("jumbo_trees.tiles"), 8);
         this.registerFakeJumboTree(pm, 7);
         for (int i = 0; i < modTileFiles.size(); i++) {
            this.LoadTileDefinitions(pm, modTileFiles.get(i), modFileNumbers.get(i));
         }
         tileImagesOut.addAll(this.tileImages);
      } finally {
         this.pzoptTileMgr = null;
      }
   }

   private void registerFakeJumboTree(IsoSpriteManager sprMan, int fileNumber) {
      int tileNum = 0;
      IsoSprite spr = this.pzoptTileMgr != null ? pzopt.TileDefPreload.addSprite(sprMan, "jumbo_tree_01_0", fileNumber * 512 * 512 + 6144 + 0) : sprMan.AddSprite("jumbo_tree_01_0", fileNumber * 512 * 512 + 6144 + 0); // pzopt: tileDefPreload
      spr.setName("jumbo_tree_01_0");
      spr.setTileType(IsoObjectType.tree);
      spr.getProperties().set("tree", "4");
      spr.getProperties().unset(IsoFlagType.solid);
      spr.getProperties().set(IsoFlagType.blocksight);
   }

   private void loadedTileDefinitions() {
      CellLoader.glassRemovedWindowSpriteMap.clear();
      CellLoader.smashedWindowSpriteMap.clear();

      for (IsoSprite sprite : IsoSpriteManager.instance.namedMap.values()) {
         PropertyContainer props = sprite.getProperties();
         if (props.has(IsoFlagType.windowW) || props.has(IsoFlagType.windowN)) {
            String val = props.get(IsoPropertyType.GLASS_REMOVED_OFFSET);
            if (val != null) {
               int offset = PZMath.tryParseInt(val, 0);
               if (offset != 0) {
                  IsoSprite sprite2 = IsoSprite.getSprite(IsoSpriteManager.instance, sprite, offset);
                  if (sprite2 != null) {
                     CellLoader.glassRemovedWindowSpriteMap.put(sprite2, sprite);
                  }
               }
            }

            val = props.get(IsoPropertyType.SMASHED_TILE_OFFSET);
            if (val != null) {
               int offset = PZMath.tryParseInt(val, 0);
               if (offset != 0) {
                  IsoSprite sprite2 = IsoSprite.getSprite(IsoSpriteManager.instance, sprite, offset);
                  if (sprite2 != null) {
                     CellLoader.smashedWindowSpriteMap.put(sprite2, sprite);
                  }
               }
            }
         }

         if (sprite.name != null && sprite.name.startsWith("fixtures_railings_01")) {
            sprite.getProperties().set(IsoFlagType.NeverCutaway);
         }

         IsoSprite sprite1 = (IsoSprite)IsoSpriteManager.instance.namedMap.get(sprite.tilesetName + "_on_" + sprite.tileSheetIndex);
         if (sprite1 != null && !sprite1.hasNoTextures()) {
            sprite.getProperties().set(IsoFlagType.HasLightOnSprite);
         } else {
            sprite.getProperties().unset(IsoFlagType.HasLightOnSprite);
         }
      }

      SpriteModelManager.getInstance().loadedTileDefinitions();
      TileDepthTextureManager.getInstance().loadedTileDefinitions();
      TileGeometryManager.getInstance().loadedTileDefinitions();
      this.getAttachmentsHandler().loadAttachments();
   }

   public boolean LoadPlayerForInfo() throws FileNotFoundException, IOException {
      if (GameClient.client) {
         return ClientPlayerDB.getInstance().loadNetworkPlayerInfo(1);
      }

      File inFile = ZomboidFileSystem.instance.getFileInCurrentSave("map_p.bin");
      if (!inFile.exists()) {
         PlayerDB.getInstance().importPlayersFromVehiclesDB();
         return PlayerDB.getInstance().loadLocalPlayerInfo(1);
      }

      FileInputStream inStream = new FileInputStream(inFile);
      BufferedInputStream input = new BufferedInputStream(inStream);
      synchronized (SliceY.SliceBufferLock) {
         SliceY.SliceBuffer.clear();
         int numBytes = input.read(SliceY.SliceBuffer.array());
         SliceY.SliceBuffer.limit(numBytes);
         input.close();
         byte b1 = SliceY.SliceBuffer.get();
         byte b2 = SliceY.SliceBuffer.get();
         byte b3 = SliceY.SliceBuffer.get();
         byte b4 = SliceY.SliceBuffer.get();
         if (b1 == 80 && b2 == 76 && b3 == 89 && b4 == 82) {
            int serverPlayerID = SliceY.SliceBuffer.getInt();
         } else {
            SliceY.SliceBuffer.rewind();
         }

         String serverPlayerID = GameWindow.ReadString(SliceY.SliceBuffer);
         if (GameClient.client && !IsoPlayer.isServerPlayerIDValid(serverPlayerID)) {
            GameLoadingState.gameLoadingString = Translator.getText("IGUI_MP_ServerPlayerIDMismatch", new Object[0]);
            GameLoadingState.playerWrongIP = true;
            return false;
         } else {
            worldX = SliceY.SliceBuffer.getInt();
            worldY = SliceY.SliceBuffer.getInt();
            IsoChunkMap.worldXa = SliceY.SliceBuffer.getInt();
            IsoChunkMap.worldYa = SliceY.SliceBuffer.getInt();
            IsoChunkMap.worldZa = SliceY.SliceBuffer.getInt();
            IsoChunkMap.worldXa = IsoChunkMap.worldXa + 256 * saveoffsetx;
            IsoChunkMap.worldYa = IsoChunkMap.worldYa + 256 * saveoffsety;
            IsoChunkMap.SWorldX[0] = worldX;
            IsoChunkMap.SWorldY[0] = worldY;
            IsoChunkMap.SWorldX[0] = IsoChunkMap.SWorldX[0] + 32 * saveoffsetx;
            IsoChunkMap.SWorldY[0] = IsoChunkMap.SWorldY[0] + 32 * saveoffsety;
            return true;
         }
      }
   }

   public void init() throws FileNotFoundException, IOException, WorldDictionaryException {
      if (!Core.tutorial) {
         this.randomizedBuildingList.add(new RBSafehouse());
         this.randomizedBuildingList.add(new RBBurnt());
         this.randomizedBuildingList.add(new RBOther());
         this.randomizedBuildingList.add(new RBLooted());
         this.randomizedBuildingList.add(new RBBurntFireman());
         this.randomizedBuildingList.add(new RBBurntCorpse());
         this.randomizedBuildingList.add(new RBShopLooted());
         this.randomizedBuildingList.add(new RBKateAndBaldspot());
         this.randomizedBuildingList.add(new RBStripclub());
         this.randomizedBuildingList.add(new RBSchool());
         this.randomizedBuildingList.add(new RBSpiffo());
         this.randomizedBuildingList.add(new RBPizzaWhirled());
         this.randomizedBuildingList.add(new RBPileOCrepe());
         this.randomizedBuildingList.add(new RBCafe());
         this.randomizedBuildingList.add(new RBBar());
         this.randomizedBuildingList.add(new RBOffice());
         this.randomizedBuildingList.add(new RBHairSalon());
         this.randomizedBuildingList.add(new RBClinic());
         this.randomizedBuildingList.add(new RBGunstoreSiege());
         this.randomizedBuildingList.add(new RBPoliceSiege());
         this.randomizedBuildingList.add(new RBSWATStation());
         this.randomizedBuildingList.add(new RBHeatBreakAfternoon());
         this.randomizedBuildingList.add(new RBTrashed());
         this.randomizedBuildingList.add(new RBBarn());
         this.randomizedBuildingList.add(new RBDorm());
         this.randomizedBuildingList.add(new RBNolans());
         this.randomizedBuildingList.add(new RBJackieJaye());
         this.randomizedBuildingList.add(new RBJoanHartford());
         this.randomizedBuildingList.add(new RBMayorWestPoint());
         this.randomizedBuildingList.add(new RBTwiggy());
         this.randomizedBuildingList.add(new RBWoodcraft());
         this.randomizedVehicleStoryList.add(new RVSUtilityVehicle());
         this.randomizedVehicleStoryList.add(new RVSConstructionSite());
         this.randomizedVehicleStoryList.add(new RVSBurntCar());
         this.randomizedVehicleStoryList.add(new RVSPoliceBlockadeShooting());
         this.randomizedVehicleStoryList.add(new RVSPoliceBlockade());
         this.randomizedVehicleStoryList.add(new RVSCarCrash());
         this.randomizedVehicleStoryList.add(new RVSAmbulanceCrash());
         this.randomizedVehicleStoryList.add(new RVSCarCrashCorpse());
         this.randomizedVehicleStoryList.add(new RVSChangingTire());
         this.randomizedVehicleStoryList.add(new RVSFlippedCrash());
         this.randomizedVehicleStoryList.add(new RVSBanditRoad());
         this.randomizedVehicleStoryList.add(new RVSTrailerCrash());
         this.randomizedVehicleStoryList.add(new RVSCrashHorde());
         this.randomizedVehicleStoryList.add(new RVSCarCrashDeer());
         this.randomizedVehicleStoryList.add(new RVSDeadEnd());
         this.randomizedVehicleStoryList.add(new RVSRegionalProfessionVehicle());
         this.randomizedVehicleStoryList.add(new RVSRoadKill());
         this.randomizedVehicleStoryList.add(new RVSRoadKillSmall());
         this.randomizedVehicleStoryList.add(new RVSAnimalOnRoad());
         this.randomizedVehicleStoryList.add(new RVSHerdOnRoad());
         this.randomizedVehicleStoryList.add(new RVSAnimalTrailerOnRoad());
         this.randomizedVehicleStoryList.add(new RVSRichJerk());
         this.randomizedVehicleStoryList.add(new RVSPlonkies());
         this.randomizedZoneList.add(new RZSAttachedAnimal());
         this.randomizedZoneList.add(new RZSBBQParty());
         this.randomizedZoneList.add(new RZSBaseball());
         this.randomizedZoneList.add(new RZSBeachParty());
         this.randomizedZoneList.add(new RZSBurntWreck());
         this.randomizedZoneList.add(new RZSBuryingCamp());
         this.randomizedZoneList.add(new RZSCampsite());
         this.randomizedZoneList.add(new RZSCharcoalBurner());
         this.randomizedZoneList.add(new RZSDean());
         this.randomizedZoneList.add(new RZSDuke());
         this.randomizedZoneList.add(new RZSEscapedAnimal());
         this.randomizedZoneList.add(new RZSEscapedHerd());
         this.randomizedZoneList.add(new RZSFishingTrip());
         this.randomizedZoneList.add(new RZSForestCamp());
         this.randomizedZoneList.add(new RZSForestCampEaten());
         this.randomizedZoneList.add(new RZSFrankHemingway());
         this.randomizedZoneList.add(new RZSHermitCamp());
         this.randomizedZoneList.add(new RZSHillbillyHoedown());
         this.randomizedZoneList.add(new RZSHogWild());
         this.randomizedZoneList.add(new RZSHunterCamp());
         this.randomizedZoneList.add(new RZJackieJaye());
         this.randomizedZoneList.add(new RZSKirstyKormick());
         this.randomizedZoneList.add(new RZSMurderScene());
         this.randomizedZoneList.add(new RZSMusicFest());
         this.randomizedZoneList.add(new RZSMusicFestStage());
         this.randomizedZoneList.add(new RZSNastyMattress());
         this.randomizedZoneList.add(new RZSOccultActivity());
         this.randomizedZoneList.add(new RZSOldFirepit());
         this.randomizedZoneList.add(new RZSOldShelter());
         this.randomizedZoneList.add(new RZSOrphanedFawn());
         this.randomizedZoneList.add(new RZSRangerSmith());
         this.randomizedZoneList.add(new RZSRockerParty());
         this.randomizedZoneList.add(new RZSSadCamp());
         this.randomizedZoneList.add(new RZSSexyTime());
         this.randomizedZoneList.add(new RZSSirTwiggy());
         this.randomizedZoneList.add(new RZSSurvivalistCamp());
         this.randomizedZoneList.add(new RZSTragicPicnic());
         this.randomizedZoneList.add(new RZSTrapperCamp());
         this.randomizedZoneList.add(new RZSVanCamp());
         this.randomizedZoneList.add(new RZSWasteDump());
         this.randomizedZoneList.add(new RZSWaterPump());
      }

      RBBasic.getUniqueRDSSpawned().clear();
      if (!GameClient.client && !GameServer.server) {
         BodyDamageSync.instance = null;
      } else {
         BodyDamageSync.instance = new BodyDamageSync();
      }

      if (GameServer.server) {
         Core.gameSaveWorld = GameServer.serverName;
         String saveFolder = ZomboidFileSystem.instance.getCurrentSaveDir();
         File file = new File(saveFolder);
         if (!file.exists()) {
            GameServer.resetId = Rand.Next(10000000);
            ServerOptions.instance.putSaveOption("ResetID", String.valueOf(GameServer.resetId));
         }

         GlobalObject.createWorld(Core.gameSaveWorld);
      }

      savedWorldVersion = this.readWorldVersion();
      if (!GameServer.server) {
         File inFile = ZomboidFileSystem.instance.getFileInCurrentSave("map_ver.bin");

         try (
            FileInputStream fis = new FileInputStream(inFile);
            DataInputStream input = new DataInputStream(fis);
         ) {
            int worldVersion = input.readInt();
            String mapDir = GameWindow.ReadString(input);
            if (!GameClient.client) {
               Core.gameMap = mapDir;
            }
         } catch (FileNotFoundException var38) {
         }
      }

      if (!GameClient.client) {
         File inFile = ZomboidFileSystem.instance.getFileInCurrentSave("id_manager_data.bin");

         try (
            FileInputStream fis = new FileInputStream(inFile);
            DataInputStream input = new DataInputStream(fis);
         ) {
            int worldVersion = input.readInt();
            ObjectIDManager.getInstance().load(input, worldVersion);
         } catch (FileNotFoundException var30) {
         }
      }

      if (!GameClient.client) {
         File inFile = ZomboidFileSystem.instance.getFileInCurrentSave("important_area_data.bin");

         try (
            FileInputStream fis = new FileInputStream(inFile);
            BufferedInputStream input = new BufferedInputStream(fis);
         ) {
            synchronized (SliceY.SliceBufferLock) {
               SliceY.SliceBuffer.clear();
               int numBytes = input.read(SliceY.SliceBuffer.array());
               SliceY.SliceBuffer.limit(numBytes);
               input.close();
               int worldVersion = SliceY.SliceBuffer.getInt();
               ImportantAreaManager.getInstance().load(SliceY.SliceBuffer, worldVersion);
            }
         } catch (FileNotFoundException var25) {
         }
      }

      Result wgLoadResult = WorldGenParams.INSTANCE.load();
      if (wgLoadResult == Result.CLIENT || wgLoadResult == Result.NOT_PRESENT && GameServer.server) {
         String newSeed = ServerOptions.instance.seed.getValue();
         if (GameServer.server && newSeed.isEmpty()) {
            newSeed = GameServer.seed;
            ServerOptions.instance.changeOption("Seed", newSeed);
         }

         WorldGenParams.INSTANCE.setSeedString(newSeed);
         WorldGenParams.INSTANCE.setMinXCell(-250);
         WorldGenParams.INSTANCE.setMaxXCell(250);
         WorldGenParams.INSTANCE.setMinYCell(-250);
         WorldGenParams.INSTANCE.setMaxYCell(250);
      }

      if (!GameServer.server || !GameServer.softReset) {
         this.metaGrid.CreateStep1();
      }

      LuaEventManager.triggerEvent("OnPreDistributionMerge");
      LuaEventManager.triggerEvent("OnDistributionMerge");
      LuaEventManager.triggerEvent("OnPostDistributionMerge");
      DebugType.General.println("VehiclesDB2.init() start");
      VehiclesDB2.instance.init();
      DebugType.General.println("VehiclesDB2.init() end");
      DebugType.General.println("triggerEvent OnInitWorld");
      LuaEventManager.triggerEvent("OnInitWorld");
      if (!GameClient.client) {
         DebugType.General.println("SandboxOptions.load() start");
         SandboxOptions.instance.load();
         DebugType.General.println("SandboxOptions.load() end");
      }

      DebugType.General.println("ItemPickerJava.Parse() start");
      ItemPickerJava.Parse();
      DebugType.General.println("ItemPickerJava.Parse() end");
      this.hydroPowerOn = SandboxOptions.getInstance().doesPowerGridExist();
      ZomboidGlobals.toLua();
      ItemPickerJava.InitSandboxLootSettings();
      this.survivorDescriptors.clear();
      pzopt.SpriteWindow.open(); // pzopt: the sprite maps are disposed and refilled until the missing-tile sprite below
      IsoSpriteManager.instance.Dispose();
      if (GameClient.client && ServerOptions.instance.doLuaChecksum.getValue()) {
         DebugType.General.println("client: DoLuaChecksum start");

         try {
            NetChecksum.comparer.beginCompare();
            GameLoadingState.gameLoadingString = Translator.getText("IGUI_MP_Checksum", new Object[0]);
            long start = System.currentTimeMillis();
            long prevSecond = start;

            while (!GameClient.checksumValid) {
               if (GameWindow.serverDisconnected) {
                  pzopt.SpriteWindow.close(); // pzopt: leaving the sprite window early
                  return;
               }

               if (System.currentTimeMillis() > start + LUA_CHECKSUM_TIMEOUT_MS) {
                  DebugType.General.println("checksum: timed out waiting for the server to respond");
                  GameClient.connection.forceDisconnect("world-timeout-response");
                  GameWindow.serverDisconnected = true;
                  GameWindow.kickReason = Translator.getText("UI_GameLoad_TimedOut", new Object[0]);
                  pzopt.SpriteWindow.close(); // pzopt: leaving the sprite window early
                  return;
               }

               if (System.currentTimeMillis() > prevSecond + 1000L) {
                  DebugType.Moveable.println("checksum: waited one second");
                  prevSecond += 1000L;
               }

               NetChecksum.comparer.update();
               if (GameClient.checksumValid) {
                  break;
               }

               Thread.sleep(100L);
            }
         } catch (Exception e) {
            ExceptionLogger.logException(e);
         }

         DebugType.General.println("client: DoLuaChecksum end");
      }

      GameLoadingState.gameLoadingString = Translator.getText("IGUI_MP_LoadTileDef", new Object[0]);
      IsoSpriteManager spriteManager = IsoSpriteManager.instance;
      this.tileImages.clear();
      DebugType.General.println("LoadTileDefinitions start");
      ZomboidFileSystem zfs = ZomboidFileSystem.instance;
      if (!pzopt.TileDefPreload.install(spriteManager, this.tileImages)) { // pzopt: tileDefPreload, the sprites built at boot (stock below otherwise)
      this.LoadTileDefinitionsPropertyStrings(spriteManager, zfs.getMediaPath("newtiledefinitions.tiles"), 1);
      this.LoadTileDefinitionsPropertyStrings(spriteManager, zfs.getMediaPath("tiledefinitions_erosion.tiles"), 2);
      this.LoadTileDefinitionsPropertyStrings(spriteManager, zfs.getMediaPath("tiledefinitions_overlays.tiles"), 4);
      this.LoadTileDefinitionsPropertyStrings(spriteManager, zfs.getMediaPath("tiledefinitions_b42chunkcaching.tiles"), 5);
      this.LoadTileDefinitionsPropertyStrings(spriteManager, zfs.getMediaPath("tiledefinitions_noiseworks.patch.tiles"), -1);
      this.LoadTileDefinitionsPropertyStrings(spriteManager, zfs.getMediaPath("jumbo_trees_big.tiles"), 6);
      this.LoadTileDefinitionsPropertyStrings(spriteManager, zfs.getMediaPath("jumbo_trees.tiles"), 8);
      ZomboidFileSystem.instance.loadModTileDefPropertyStrings();
      this.SetCustomPropertyValues();
      this.GenerateTilePropertyLookupTables();
      this.LoadTileDefinitions(spriteManager, zfs.getMediaPath("newtiledefinitions.tiles"), 1);
      this.LoadTileDefinitions(spriteManager, zfs.getMediaPath("tiledefinitions_erosion.tiles"), 2);
      this.LoadTileDefinitions(spriteManager, zfs.getMediaPath("tiledefinitions_overlays.tiles"), 4);
      this.LoadTileDefinitions(spriteManager, zfs.getMediaPath("tiledefinitions_b42chunkcaching.tiles"), 5);
      this.LoadTileDefinitions(spriteManager, zfs.getMediaPath("tiledefinitions_noiseworks.patch.tiles"), -1);
      this.LoadTileDefinitions(spriteManager, zfs.getMediaPath("jumbo_trees_big.tiles"), 6);
      this.LoadTileDefinitions(spriteManager, zfs.getMediaPath("jumbo_trees.tiles"), 8);
      this.registerFakeJumboTree(spriteManager, 7);
      ZomboidFileSystem.instance.loadModTileDefs();
      } // pzopt: end of the stock tile definition load
      GameLoadingState.gameLoadingString = "";
      DebugType.General.println("LoadTileDefinitions end");
      spriteManager.AddSprite("media/ui/missing-tile.png");
      pzopt.SpriteWindow.close(); // pzopt: the refill is complete; depth-map loads that finished meanwhile finish now
      ScriptManager.instance.PostTileDefinitions();
      DebugType.General.println("triggerEvent OnLoadedTileDefinitions");
      LuaEventManager.triggerEvent("OnLoadedTileDefinitions", spriteManager);
      this.loadedTileDefinitions();
      AnimalDefinitions.getAnimalDefs();
      if (GameServer.server && GameServer.softReset) {
         IsoRegions.init();
         BentFences.init();
         WorldConverter.instance.softreset();
      }

      try {
         WeatherFxMask.init();
      } catch (Exception e) {
         ExceptionLogger.logException(e);
      }

      TemplateText.Initialize();
      IsoRegions.init();
      BentFences.init();
      ObjectRenderEffects.init();
      WorldConverter.instance.convert(Core.gameSaveWorld, spriteManager);
      if (!GameLoadingState.worldVersionError) {
         SandboxOptions.instance.handleOldZombiesFile2();
         DebugType.General.println("GameTime.init() and load() start");
         GameTime.getInstance().init();
         GameTime.getInstance().load();
         DebugType.General.println("GameTime.init() and load() end");
         ImprovedFog.init();
         DebugType.General.println("ZomboidRadio.Init() start");
         ZomboidRadio.getInstance().Init(savedWorldVersion);
         DebugType.General.println("ZomboidRadio.Init() end");
         DebugType.General.println("GlobalModData.init() start");
         GlobalModData.instance.init();
         DebugType.General.println("GlobalModData.init() end");
         DebugType.General.println("InstanceTracker.load() start");
         InstanceTracker.load();
         DebugType.General.println("InstanceTracker.load() end");
         if (GameServer.server && Core.getInstance().getPoisonousBerry() == null) {
            Core.getInstance().initPoisonousBerry();
         }

         if (GameServer.server && Core.getInstance().getPoisonousMushroom() == null) {
            Core.getInstance().initPoisonousMushroom();
         }

         DebugType.General.println("ErosionGlobals.Boot() start");
         ErosionGlobals.Boot(spriteManager);
         DebugType.General.println("ErosionGlobals.Boot() end");
         DebugType.General.println("WorldDictionary.init() start");
         WorldDictionary.init();
         ScriptManager.instance.PostWorldDictionaryInit();
         DebugType.General.println("WorldDictionary.init() end");
         FishSchoolManager.getInstance().init();
         WorldMarkers.instance.init();
         DebugType.General.println("GameEntityManager.Init() start");
         GameEntityManager.Init(savedWorldVersion);
         DebugType.General.println("GameEntityManager.Init() end");
         if (GameServer.server) {
            SharedDescriptors.initSharedDescriptors();
         }

         DebugType.General.println("PersistentOutfits.init() start");
         PersistentOutfits.instance.init();
         DebugType.General.println("PersistentOutfits.init() end");
         VehicleIDMap.instance.Reset();
         VehicleManager.instance = new VehicleManager();
         GameLoadingState.gameLoadingString = Translator.getText("IGUI_MP_InitMap", new Object[0]);
         this.metaGrid.CreateStep2();
         pzopt.LoadTrace.step("CreateStep2 end"); // pzopt: load-trace step markers (only with the trace installed)
         ClimateManager.getInstance().init(this.metaGrid);
         SafeHouse.init();
         VirtualZombieManager.instance.init();
         if (!GameClient.client) {
            StashSystem.init();
         }

         Basements.getInstance().beforeOnLoadMapZones();
         pzopt.LoadTrace.step("OnLoadMapZones start");
         LuaEventManager.triggerEvent("OnLoadMapZones");
         pzopt.LoadTrace.step("OnLoadMapZones end");
         if (!GameClient.client) {
            Basements.getInstance().beforeLoadMetaGrid();
            pzopt.LoadTrace.step("Basements.beforeLoadMetaGrid end");
            BuildingRoomsEditor.getInstance().load();
            pzopt.LoadTrace.step("BuildingRoomsEditor.load end");
            this.metaGrid.load();
            pzopt.LoadTrace.step("metaGrid.load end");
            Basements.getInstance().afterLoadMetaGrid();
            pzopt.LoadTrace.step("Basements.afterLoadMetaGrid end");
            this.metaGrid.load("map_zone.bin", this.metaGrid::loadZone);
            pzopt.LoadTrace.step("map_zone.bin end");
            this.metaGrid.loadCells("metagrid", "metacell_(-?[0-9]+)_(-?[0-9]+)\\.bin", IsoMetaCell::load);
            pzopt.LoadTrace.step("metacells end");
            this.metaGrid.load("map_animals.bin", this.metaGrid::loadAnimalZones);
            this.metaGrid.processZones();
            pzopt.LoadTrace.step("processZones end");
         } else {
            Basements.getInstance().beforeLoadMetaGrid();
         }

         DebugType.General.println("triggerEvent OnLoadedMapZones");
         LuaEventManager.triggerEvent("OnLoadedMapZones");
         if (GameServer.server) {
            ServerMap.instance.init(this.metaGrid);
         }

         DebugType.General.println("ItemConfigurator.Preprocess() start");
         ItemConfigurator.Preprocess();
         DebugType.General.println("ItemConfigurator.Preprocess() end");
         boolean isPlayerAlive = false;
         if (GameClient.client) {
            if (ClientPlayerDB.getInstance().clientLoadNetworkPlayer() && ClientPlayerDB.getInstance().isAliveMainNetworkPlayer()) {
               isPlayerAlive = true;
            }
         } else {
            isPlayerAlive = PlayerDBHelper.isPlayerAlive(ZomboidFileSystem.instance.getCurrentSaveDir(), 1);
         }

         if (GameServer.server) {
            ServerPlayerDB.setAllow(true);
         }

         if (!GameClient.client && !GameServer.server) {
            PlayerDB.setAllow(true);
         }

         boolean bLoadCharacter;
         if (isPlayerAlive) {
            bLoadCharacter = true;
            if (!this.LoadPlayerForInfo()) {
               return;
            }

            worldX = IsoChunkMap.SWorldX[IsoPlayer.getPlayerIndex()];
            worldY = IsoChunkMap.SWorldY[IsoPlayer.getPlayerIndex()];
         } else {
            bLoadCharacter = false;
            if (GameClient.client && !ServerOptions.instance.spawnPoint.getValue().isEmpty()) {
               String[] spawnPoint = ServerOptions.instance.spawnPoint.getValue().split(",");
               if (spawnPoint.length == 3) {
                  try {
                     IsoChunkMap.mpWorldXa = Integer.parseInt(spawnPoint[0].trim());
                     IsoChunkMap.mpWorldYa = Integer.parseInt(spawnPoint[1].trim());
                     IsoChunkMap.mpWorldZa = Integer.parseInt(spawnPoint[2].trim());
                  } catch (NumberFormatException ex) {
                     DebugType.Moveable.println("ERROR: SpawnPoint must be x,y,z, got \"" + ServerOptions.instance.spawnPoint.getValue() + "\"");
                     IsoChunkMap.mpWorldXa = 0;
                     IsoChunkMap.mpWorldYa = 0;
                     IsoChunkMap.mpWorldZa = 0;
                  }
               } else {
                  DebugType.Moveable.println("ERROR: SpawnPoint must be x,y,z, got \"" + ServerOptions.instance.spawnPoint.getValue() + "\"");
               }
            }

            if (!GameClient.client || IsoChunkMap.mpWorldXa == 0 && IsoChunkMap.mpWorldYa == 0) {
               IsoChunkMap.worldXa = this.getLuaPosX();
               IsoChunkMap.worldYa = this.getLuaPosY();
               IsoChunkMap.worldZa = this.getLuaPosZ();
               if (GameClient.client && ServerOptions.instance.safehouseAllowRespawn.getValue()) {
                  for (int i = 0; i < SafeHouse.getSafehouseList().size(); i++) {
                     SafeHouse safe = (SafeHouse)SafeHouse.getSafehouseList().get(i);
                     if (safe.getPlayers().contains(GameClient.username) && safe.isRespawnInSafehouse(GameClient.username)) {
                        IsoChunkMap.worldXa = safe.getX() + safe.getH() / 2;
                        IsoChunkMap.worldYa = safe.getY() + safe.getW() / 2;
                        IsoChunkMap.worldZa = 0;
                     }
                  }
               }

               worldX = PZMath.fastfloor(IsoChunkMap.worldXa / 8.0F);
               worldY = PZMath.fastfloor(IsoChunkMap.worldYa / 8.0F);
            } else {
               IsoChunkMap.worldXa = IsoChunkMap.mpWorldXa;
               IsoChunkMap.worldYa = IsoChunkMap.mpWorldYa;
               IsoChunkMap.worldZa = IsoChunkMap.mpWorldZa;
               worldX = PZMath.fastfloor(IsoChunkMap.worldXa / 8.0F);
               worldY = PZMath.fastfloor(IsoChunkMap.worldYa / 8.0F);
            }
         }

         KahluaTable selectedDebugScenario = (KahluaTable)LuaManager.env.rawget("selectedDebugScenario");
         if (selectedDebugScenario != null) {
            KahluaTable loc = (KahluaTable)selectedDebugScenario.rawget("startLoc");
            int x = ((Double)loc.rawget("x")).intValue();
            int y = ((Double)loc.rawget("y")).intValue();
            int z = ((Double)loc.rawget("z")).intValue();
            IsoChunkMap.worldXa = x;
            IsoChunkMap.worldYa = y;
            IsoChunkMap.worldZa = z;
            worldX = PZMath.fastfloor(IsoChunkMap.worldXa / 8.0F);
            worldY = PZMath.fastfloor(IsoChunkMap.worldYa / 8.0F);
         }

         DebugType.General.println("MapCollisionData.init() start");
         MapCollisionData.instance.init(instance.getMetaGrid());
         DebugType.General.println("MapCollisionData.init() end");
         DebugType.General.println("AnimalPopulationManager.init() start");
         AnimalPopulationManager.getInstance().init(this.getMetaGrid());
         DebugType.General.println("AnimalPopulationManager.init() end");
         DebugType.General.println("ZombiePopulationManager.init() start");
         ZombiePopulationManager.instance.init(instance.getMetaGrid());
         DebugType.General.println("ZombiePopulationManager.init() end");
         DebugType.General.println("Pathfind init() start");
         PathfindNative.useNativeCode = DebugOptions.instance.pathfindUseNativeCode.getValue();
         if (PathfindNative.useNativeCode) {
            PathfindNative.instance.init(instance.getMetaGrid());
         } else {
            PolygonalMap2.instance.init(instance.getMetaGrid());
         }

         DebugType.General.println("Pathfind init() end");
         GlobalObjectLookup.init(instance.getMetaGrid());
         if (!GameServer.server) {
            SpawnPoints.instance.initSinglePlayer(this.metaGrid);
         }

         DebugType.General.println("WorldStreamer.create() start");
         WorldStreamer.instance.create();
         DebugType.General.println("WorldStreamer.create() end");
         DebugType.General.println("CellLoader.LoadCellBinaryChunk start");
         pzopt.CenterFirstLoad.setCenter(worldX, worldY); // pzopt: centerFirstLoad, the initial chunks nearest-first
         this.currentCell = CellLoader.LoadCellBinaryChunk(spriteManager, worldX, worldY);
         DebugType.General.println("CellLoader.LoadCellBinaryChunk start");
         ClimateManager.getInstance().postCellLoadSetSnow();
         GameLoadingState.gameLoadingString = Translator.getText("IGUI_MP_LoadWorld", new Object[0]);
         MapCollisionData.instance.start();
         if (!GameServer.server) {
            DebugType.General.println("MapItem.LoadWorldMap() start");
            MapItem.LoadWorldMap();
            DebugType.General.println("MapItem.LoadWorldMap() start");
         }

         if (GameClient.client) {
            WorldMapClient.instance.worldMapLoaded();
         }

         if (GameServer.server) {
            HiddenAuthors.read();
         }

         DebugType.General.println("WorldStreamer.isBusy() loop start");

         long pzoptPollMs = pzopt.Config.LOADER_CPU_FIXES && pzopt.Overrides.enabled() ? 2L : 100L; // pzopt: the loader notices the last initial chunk within 2 ms instead of up to 100
         while (WorldStreamer.instance.isBusy() && !pzopt.CenterFirstLoad.nearLoaded()) { // pzopt: centerFirstLoad, enter once the chunks around the player are in
            try {
               Thread.sleep(pzoptPollMs);
            } catch (InterruptedException var17) {
            }
         }

         DebugType.General.println("WorldStreamer.isBusy() loop end");
         ArrayList<IsoChunk> loaded = new ArrayList<>();
         IsoChunk.loadGridSquare.copyTo(loaded);

         for (IsoChunk chunk : loaded) {
            this.currentCell.chunkMap[0].setChunkDirect(chunk, false);
         }

         this.currentCell.chunkMap[0].calculateZExtentsForChunkMap();
         IsoChunk.doServerRequests = true;
         if (bLoadCharacter && SystemDisabler.doPlayerCreation) {
            this.currentCell.LoadPlayer(savedWorldVersion);
            if (GameClient.client) {
               IsoPlayer.getInstance().setUsername(GameClient.username);
            }

            ZomboidRadio.getInstance().getRecordedMedia().handleLegacyListenedLines(IsoPlayer.getInstance());
         } else if (GameClient.client) {
            ZomboidRadio.getInstance().getRecordedMedia().handleLegacyListenedLines(null);
            LuaManager.thread.debugOwnerThread = GameWindow.gameThread;
            LuaManager.debugthread.debugOwnerThread = GameWindow.gameThread;
            GameClient.sendCreatePlayer((byte)0);
            DebugType.General.println("Waiting for the server to send player data");
            long currentTime = System.currentTimeMillis();
            boolean playerLoaded = false;

            while (true) {
               try {
                  if (IsoPlayer.players[0] != null) {
                     playerLoaded = true;
                  }

                  if (System.currentTimeMillis() - currentTime > 30000L || playerLoaded) {
                     break;
                  }

                  Thread.sleep(100L);
               } catch (InterruptedException var33) {
               }
            }

            LuaManager.thread.debugOwnerThread = GameLoadingState.loader;
            LuaManager.debugthread.debugOwnerThread = GameLoadingState.loader;
            if (!playerLoaded) {
               throw new RuntimeException("Timed out waiting for the server to send player data");
            }

            DebugType.General.println("Player data received from the server");
            IsoPlayer playerObj = IsoPlayer.players[0];
            IsoChunkMap.worldXa = playerObj.getXi();
            IsoChunkMap.worldYa = playerObj.getYi();
            IsoChunkMap.worldZa = playerObj.getZi();
            IsoGridSquare sq = this.currentCell.getGridSquare(IsoChunkMap.worldXa, IsoChunkMap.worldYa, IsoChunkMap.worldZa);
            if (sq != null && sq.getRoom() != null) {
               if (SandboxOptions.instance.lore.playerSpawnZombieRemoval.getValue() < 4) {
                  sq.getRoom().def.setExplored(true);
               } else if (SandboxOptions.instance.lore.playerSpawnZombieRemoval.getValue() < 3) {
                  sq.getRoom().building.setAllExplored(true, sq.getRoom());
                  this.setBasementAllExplored(sq.getRoom().building);
               }

               if (!GameServer.server && !GameClient.client) {
                  ZombiePopulationManager.instance.playerSpawnedAt(sq.getX(), sq.getY(), sq.getZ());
               }
            }

            if (!GameClient.client) {
               Core.getInstance().initPoisonousBerry();
               Core.getInstance().initPoisonousMushroom();
            }

            LuaEventManager.triggerEvent("OnNewGame", playerObj, sq);
         } else {
            ZomboidRadio.getInstance().getRecordedMedia().handleLegacyListenedLines(null);
            if (IsoPlayer.numPlayers == 0) {
               IsoPlayer.numPlayers = 1;
            }

            int xa = IsoChunkMap.worldXa;
            int ya = IsoChunkMap.worldYa;
            int za = IsoChunkMap.worldZa;
            if (GameClient.client && !ServerOptions.instance.spawnPoint.getValue().isEmpty()) {
               String[] spawnPoint = ServerOptions.instance.spawnPoint.getValue().split(",");
               if (spawnPoint.length != 3) {
                  DebugType.Moveable.println("ERROR: SpawnPoint must be x,y,z, got \"" + ServerOptions.instance.spawnPoint.getValue() + "\"");
               } else {
                  try {
                     int yAb = Integer.parseInt(spawnPoint[1].trim());
                     int xAb = Integer.parseInt(spawnPoint[0].trim());
                     int zAb = Integer.parseInt(spawnPoint[2].trim());
                     if (GameClient.client && ServerOptions.instance.safehouseAllowRespawn.getValue()) {
                        for (int i = 0; i < SafeHouse.getSafehouseList().size(); i++) {
                           SafeHouse safe = (SafeHouse)SafeHouse.getSafehouseList().get(i);
                           if (safe.getPlayers().contains(GameClient.username) && safe.isRespawnInSafehouse(GameClient.username)) {
                              xAb = safe.getX() + safe.getH() / 2;
                              yAb = safe.getY() + safe.getW() / 2;
                              zAb = 0;
                           }
                        }
                     }

                     if (this.currentCell.getGridSquare(xAb, yAb, zAb) != null) {
                        xa = xAb;
                        ya = yAb;
                        za = zAb;
                     }
                  } catch (NumberFormatException ex) {
                     DebugType.Moveable.println("ERROR: SpawnPoint must be x,y,z, got \"" + ServerOptions.instance.spawnPoint.getValue() + "\"");
                  }
               }
            }

            IsoGridSquare sq = this.currentCell.getGridSquare(xa, ya, za);
            if (SystemDisabler.doPlayerCreation && !GameServer.server) {
               if (sq != null && sq.isFree(false) && sq.getRoom() != null) {
                  IsoGridSquare last = sq;
                  sq = sq.getRoom().getFreeTile();
                  if (sq == null) {
                     sq = last;
                  }
               }

               IsoPlayer player = null;
               if (this.getLuaPlayerDesc() != null) {
                  if (GameClient.client && ServerOptions.instance.safehouseAllowRespawn.getValue()) {
                     sq = this.currentCell.getGridSquare(IsoChunkMap.worldXa, IsoChunkMap.worldYa, IsoChunkMap.worldZa);
                     if (sq != null && sq.isFree(false) && sq.getRoom() != null) {
                        IsoGridSquare last = sq;
                        sq = sq.getRoom().getFreeTile();
                        if (sq == null) {
                           sq = last;
                        }
                     }
                  }

                  if (sq == null) {
                     throw new RuntimeException("can't create player at x,y,z=" + xa + "," + ya + "," + za + " because the square is null");
                  }

                  WorldSimulation.instance.create();
                  player = new IsoPlayer(instance.currentCell, this.getLuaPlayerDesc(), sq.getX(), sq.getY(), sq.getZ());
                  if (GameClient.client) {
                     player.setUsername(GameClient.username);
                  }

                  player.setDir(IsoDirections.SE);
                  player.sqlId = 1;
                  IsoPlayer.players[0] = player;
                  IsoPlayer.setInstance(player);
                  IsoCamera.setCameraCharacter(player);
               }

               IsoPlayer playerObj = IsoPlayer.getInstance();
               playerObj.applyTraits(this.getLuaTraits());
               CharacterProfessionDefinition characterProfessionDefinition = CharacterProfessionDefinition.getCharacterProfessionDefinition(
                  playerObj.getDescriptor().getCharacterProfession()
               );
               if (characterProfessionDefinition.hasGrantedRecipes()) {
                  playerObj.getKnownRecipes().addAll(characterProfessionDefinition.getGrantedRecipes());
               }

               for (CharacterTrait characterTrait : this.getLuaTraits()) {
                  CharacterTraitDefinition characterTraitDefinition = CharacterTraitDefinition.getCharacterTraitDefinition(characterTrait);
                  if (characterTraitDefinition.hasGrantedRecipes()) {
                     playerObj.getKnownRecipes().addAll(characterTraitDefinition.getGrantedRecipes());
                  }
               }

               if (sq != null && sq.getRoom() != null) {
                  sq.getRoom().def.setExplored(true);
                  sq.getRoom().building.setAllExplored(true);
                  this.setBasementAllExplored(sq.getRoom().getBuilding());
                  if (!GameServer.server && !GameClient.client) {
                     ZombiePopulationManager.instance.playerSpawnedAt(sq.getX(), sq.getY(), sq.getZ());
                  }
               }

               if (!GameClient.client) {
                  Core.getInstance().initPoisonousBerry();
                  Core.getInstance().initPoisonousMushroom();
               }

               LuaEventManager.triggerEvent("OnNewGame", player, sq);
            }
         }

         if (PlayerDB.isAllow()) {
            PlayerDB.getInstance().canSavePlayers = true;
         }

         TutorialManager.instance.activeControlZombies = false;
         ReanimatedPlayers.instance.loadReanimatedPlayers();
         if (IsoPlayer.getInstance() != null) {
            if (GameClient.client) {
               int pzoptX = PZMath.fastfloor(IsoPlayer.getInstance().getX()); // pzopt: Vineflower reused the boolean slots here (regen fix)
               int pzoptY = PZMath.fastfloor(IsoPlayer.getInstance().getY());
               int z = PZMath.fastfloor(IsoPlayer.getInstance().getZ());

               while (z > 0) {
                  IsoGridSquare sq = this.currentCell.getGridSquare(pzoptX, pzoptY, PZMath.fastfloor(z));
                  if (sq != null && sq.TreatAsSolidFloor()) {
                     break;
                  }

                  IsoPlayer.getInstance().setZ(--z);
               }
            }

            ScriptManager.instance.checkAutoLearn(IsoPlayer.getInstance());
            ScriptManager.instance.checkMetaRecipes(IsoPlayer.getInstance());
            ScriptManager.instance.VerifyAllCraftRecipesAreLearnable();
            IsoPlayer.getInstance().setCurrentSquareFromPosition();
         }

         this.PopulateCellWithSurvivors();
         if (IsoPlayer.players[0] != null && !this.currentCell.getObjectList().contains(IsoPlayer.players[0])) {
            this.currentCell.getObjectList().add(IsoPlayer.players[0]);
         }

         LightingThread.instance.create();
         DebugType.General.println("MetaTracker.load() start");
         MetaTracker.load();
         DebugType.General.println("MetaTracker.load() end");
         StatisticsManager.getInstance().load();
         GameLoadingState.gameLoadingString = "";
         AnimationPlayerRecorder.initMessaging();
         WorldDictionary.onWorldLoaded();
         this.currentCell.initWeatherFx();
         if (ScriptManager.instance.hasLoadErrors(!Core.debug) || SpriteConfigManager.HasLoadErrors()) {
            DebugType.Moveable
               .println("script error = " + ScriptManager.instance.hasLoadErrors(!Core.debug) + ", sprite error = " + SpriteConfigManager.HasLoadErrors());
            throw new WorldDictionaryException("World loading could not proceed, there are script load errors. (Actual error may be printed earlier in log)");
         }
      }
   }

   private void setBasementAllExplored(IsoBuilding spawnBuilding) {
      BuildingDef spawnBuildingDef = spawnBuilding.getDef();
      ArrayList<BuildingDef> buildingDefs = new ArrayList<>();
      instance.metaGrid
         .getBuildingsIntersecting(spawnBuildingDef.getX(), spawnBuildingDef.getY(), spawnBuildingDef.getW(), spawnBuildingDef.getH(), buildingDefs);

      for (int i = 0; i < buildingDefs.size(); i++) {
         BuildingDef candidateDef = buildingDefs.get(i);
         if (candidateDef.getMinLevel() < 0) {
            candidateDef.setAllExplored(true);
         }
      }
   }

   int readWorldVersion() {
      if (GameServer.server) {
         File file = ZomboidFileSystem.instance.getFileInCurrentSave("map_t.bin");

         try (
            FileInputStream fis = new FileInputStream(file);
            DataInputStream dis = new DataInputStream(fis);
         ) {
            byte b1 = dis.readByte();
            byte b2 = dis.readByte();
            byte b3 = dis.readByte();
            byte b4 = dis.readByte();
            if (b1 == 71 && b2 == 77 && b3 == 84 && b4 == 77) {
               return dis.readInt();
            }
         } catch (FileNotFoundException var19) {
         } catch (IOException ex) {
            ExceptionLogger.logException(ex);
         }

         return -1;
      } else {
         File file = ZomboidFileSystem.instance.getFileInCurrentSave("map_ver.bin");

         try (
            FileInputStream fis = new FileInputStream(file);
            DataInputStream dis = new DataInputStream(fis);
         ) {
            return dis.readInt();
         } catch (FileNotFoundException var15) {
         } catch (IOException ex) {
            ExceptionLogger.logException(ex);
         }

         return -1;
      }
   }

   public List<CharacterTrait> getLuaTraits() {
      return this.luatraits;
   }

   public void addLuaTrait(CharacterTrait trait) {
      this.getLuaTraits().add(trait);
   }

   public SurvivorDesc getLuaPlayerDesc() {
      return this.luaDesc;
   }

   public void setLuaPlayerDesc(SurvivorDesc desc) {
      this.luaDesc = desc;
   }

   public void KillCell() {
      this.helicopter.deactivate();
      CollisionManager.instance.contactMap.clear();
      ObjectIDManager.getInstance().clear();
      FliesSound.instance.reset();
      CorpseCount.instance.reset();
      IsoObjectPicker.Instance.Init();
      IsoChunkMap.SharedChunks.clear();
      SoundManager.instance.StopMusic();
      WorldSoundManager.instance.KillCell();
      ZombieGroupManager.instance.Reset();
      this.currentCell.Dispose();
      IsoSpriteManager.instance.Dispose();
      this.currentCell = null;
      IsoLot.Dispose();
      IsoGameCharacter.getSurvivorMap().clear();
      IsoPlayer.getInstance().setCurrent(null);
      IsoPlayer.getInstance().setLast(null);
      IsoPlayer.getInstance().square = null;
      RainManager.reset();
      IsoFireManager.Reset();
      ObjectAmbientEmitters.Reset();
      AnimalVocalsManager.Reset();
      ZombieVocalsManager.Reset();
      IsoWaterFlow.Reset();
      BuildingRoomsEditor.Reset();
      this.metaGrid.Dispose();
      this.biomeMap.Dispose();
      IsoBuilding.idCount = 0;
      instance = new IsoWorld();
   }

   public void setDrawWorld(boolean b) {
      this.drawWorld = b;
   }

   public void sceneCullZombies() {
      this.zombieWithModel.clear();
      this.zombieWithoutModel.clear();

      for (int n = 0; n < this.currentCell.getZombieList().size(); n++) {
         IsoZombie z = (IsoZombie)this.currentCell.getZombieList().get(n);
         boolean withModel = false;

         for (int pn = 0; pn < IsoPlayer.numPlayers; pn++) {
            IsoPlayer player = IsoPlayer.players[pn];
            if (player != null && z.current != null) {
               float screenX = z.getScreenProperX(pn);
               float screenY = z.getScreenProperY(pn);
               if (!(screenX < -100.0F)
                  && !(screenY < -100.0F)
                  && !(screenX > Core.getInstance().getOffscreenWidth(pn) + 100)
                  && !(screenY > Core.getInstance().getOffscreenHeight(pn) + 100)
                  && (z.getAlpha(pn) != 0.0F && z.legsSprite.def.alpha != 0.0F || z.current.isCouldSee(pn) || z.couldSeeHeadSquare(player))) {
                  withModel = true;
                  break;
               }
            }
         }

         if (withModel && z.isCurrentState(FakeDeadZombieState.instance())) {
            withModel = false;
         }

         if (withModel) {
            this.zombieWithModel.add(z);
         } else {
            this.zombieWithoutModel.add(z);
         }
      }

      if (pzopt.Overrides.enabled() && pzopt.Config.ZOMBIE_CULL_SORT_FAST) {
         this.pzoptSortZombiesByScore(); // pzopt: one score per zombie and a primitive sort, the same order as the stable sort below
      } else {
         this.timSort.doSort(this.zombieWithModel.getElements(), compScoreToPlayer, 0, this.zombieWithModel.size());
      }

      int c = 0;
      int count = 0;
      int tcount = 0;
      int tcountMax = 510;
      // pzopt: zombieLodDynamic, the model / blend counts follow the frame cap (pzopt.ZombieLod). Stock never read
      // tcountMax; both 510 literals stay so a mod that rewrites them (ZBBetterFPS' cull cap) still caps below ours.
      int pzoptBlended = PerformanceSettings.numberZombiesBlended; // pzopt
      if (pzopt.ZombieLod.active()) { // pzopt
         pzopt.ZombieLod.update(); // pzopt
         tcountMax = Math.min(tcountMax, pzopt.ZombieLod.max3d()); // pzopt
         pzoptBlended = pzopt.ZombieLod.blended(pzoptBlended); // pzopt
      } // pzopt
      PerformanceSettings.animationSkip = 0;

      for (int n = 0; n < this.zombieWithModel.size(); n++) {
         IsoZombie z = (IsoZombie)this.zombieWithModel.get(n);
         if (tcount < 510 && tcount < tcountMax) { // pzopt: zombieLodDynamic's cap (510 without it)
            if (!z.ghost) {
               count++;
               tcount++;
               z.setSceneCulled(false);
               if (z.legsSprite != null && z.legsSprite.modelSlot != null) {
                  if (count > PerformanceSettings.zombieAnimationSpeedFalloffCount) {
                     c++;
                     count = 0;
                  }

                  if (tcount < PerformanceSettings.zombieBonusFullspeedFalloff) {
                     z.legsSprite.modelSlot.model.setInstanceSkip(count / PerformanceSettings.zombieBonusFullspeedFalloff);
                     count = 0;
                  } else {
                     z.legsSprite.modelSlot.model.setInstanceSkip(c + PerformanceSettings.animationSkip);
                  }

                  if (z.legsSprite.modelSlot.model.animPlayer != null) {
                     if (tcount < pzoptBlended) { // pzopt: numberZombiesBlended, or zombieLodDynamic's lower count
                        z.legsSprite.modelSlot.model.animPlayer.doBlending = !z.isAlphaAndTargetZero(0)
                           || !z.isAlphaAndTargetZero(1)
                           || !z.isAlphaAndTargetZero(2)
                           || !z.isAlphaAndTargetZero(3);
                     } else {
                        z.legsSprite.modelSlot.model.animPlayer.doBlending = false;
                     }
                  }
               }
            }
         } else {
            z.setSceneCulled(true);
            if (z.hasAnimationPlayer()) {
               z.getAnimationPlayer().doBlending = false;
            }
         }
      }

      for (int n = 0; n < this.zombieWithoutModel.size(); n++) {
         IsoZombie z = (IsoZombie)this.zombieWithoutModel.get(n);
         if (z.hasActiveModel()) {
            z.setSceneCulled(true);
         }

         if (z.hasAnimationPlayer()) {
            z.getAnimationPlayer().doBlending = false;
         }
      }
   }

   public void sceneCullAnimals() {
      this.animalWithModel.clear();
      this.animalWithoutModel.clear();

      for (IsoMovingObject mo : this.currentCell.getObjectList()) {
         if (mo instanceof IsoAnimal animal) {
            boolean withModel = false;

            for (int pn = 0; pn < IsoPlayer.numPlayers; pn++) {
               IsoPlayer player = IsoPlayer.players[pn];
               if (player != null && animal.current != null) {
                  float screenX = (int)(IsoUtils.XToScreen(animal.getX(), animal.getY(), animal.getZ(), 0) - IsoCamera.cameras[pn].getOffX());
                  float screenY = (int)(IsoUtils.YToScreen(animal.getX(), animal.getY(), animal.getZ(), 0) - IsoCamera.cameras[pn].getOffY());
                  if (!(screenX < -100.0F)
                     && !(screenY < -100.0F)
                     && !(screenX > Core.getInstance().getOffscreenWidth(pn) + 100)
                     && !(screenY > Core.getInstance().getOffscreenHeight(pn) + 100)
                     && (animal.getAlpha(pn) != 0.0F && animal.legsSprite.def.alpha != 0.0F || animal.current.isCouldSee(pn))) {
                     withModel = true;
                     break;
                  }
               }
            }

            if (withModel && animal.isCurrentState(FakeDeadZombieState.instance())) {
               withModel = false;
            }

            if (withModel) {
               this.animalWithModel.add(animal);
            } else {
               this.animalWithoutModel.add(animal);
            }
         }
      }

      for (IsoAnimal animal : this.animalWithModel) {
         animal.setSceneCulled(false);
         if (animal.hasAnimationPlayer()) {
            animal.getAnimationPlayer().doBlending = true;
         }
      }

      for (IsoAnimal animal : this.animalWithoutModel) {
         if (animal.hasActiveModel()) {
            animal.setSceneCulled(true);
         }

         if (animal.hasAnimationPlayer()) {
            animal.getAnimationPlayer().doBlending = false;
         }
      }
   }

   public void render() {
      AbstractPerformanceProfileProbe var1 = IsoWorld.s_performance.isoWorldRender.profile();

      try {
         pzopt.Darkness.frame(); // pzopt: darkness floor / remembered places / colour grading: live settings, grade weights, this frame's render switches
         pzopt.GpuSections.begin("world"); // pzopt: GPU section (the whole world pass, the part the render scale shrinks)
         this.renderInternal();
         pzopt.GpuSections.end("world"); // pzopt: GPU section
      } catch (Throwable var5) {
         if (var1 != null) {
            try {
               var1.close();
            } catch (Throwable var4) {
               var5.addSuppressed(var4);
            }
         }

         throw var5;
      }

      if (var1 != null) {
         var1.close();
      }
   }

   private void renderInternal() {
      if (this.drawWorld) {
         IsoGameCharacter isoGameCharacter = IsoCamera.getCameraCharacter();
         if (isoGameCharacter != null) {
            SpriteRenderer.instance.doCoreIntParam(0, isoGameCharacter.getX());
            SpriteRenderer.instance.doCoreIntParam(1, isoGameCharacter.getY());
            SpriteRenderer.instance.doCoreIntParam(2, IsoCamera.frameState.camCharacterZ);
            GameProfiler profiler = GameProfiler.getInstance();

            try {
               ProfileArea t = profiler.profile("Cull");

               try {
                  this.sceneCullZombies();
                  this.sceneCullAnimals();
               } catch (Throwable var16) {
                  if (t != null) {
                     try {
                        t.close();
                     } catch (Throwable var10) {
                        var16.addSuppressed(var10);
                     }
                  }

                  throw var16;
               }

               if (t != null) {
                  t.close();
               }
            } catch (Throwable t) {
               ExceptionLogger.logException(t);
            }

            try {
               WeatherFxMask.initMask();
               pzopt.GpuSections.begin("atlas"); // pzopt: GPU section
               DeadBodyAtlas.instance.render();
               WorldItemAtlas.instance.render();
               pzopt.GpuSections.end("atlas"); // pzopt: GPU section
               pzopt.GpuSections.begin("cell"); // pzopt: GPU section
               this.currentCell.render();
               pzopt.GpuSections.end("cell"); // pzopt: GPU section
               Gizmos.getInstance().render(IsoCamera.frameState.playerIndex);
               this.DrawIsoCursorHelper();
               DeadBodyAtlas.instance.renderDebug();
               ProfileArea var18 = profiler.profile("renderPathfinding");

               try {
                  this.renderPathfinding();
               } catch (Throwable var14) {
                  if (var18 != null) {
                     try {
                        var18.close();
                     } catch (Throwable var9) {
                        var14.addSuppressed(var9);
                     }
                  }

                  throw var14;
               }

               if (var18 != null) {
                  var18.close();
               }

               WorldSoundManager.instance.render();
               WorldFlares.debugRender();
               WorldMarkers.instance.debugRender();
               ObjectAmbientEmitters.getInstance().render();
               var18 = profiler.profile("renderVocals");

               try {
                  this.renderVocals();
               } catch (Throwable var13) {
                  if (var18 != null) {
                     try {
                        var18.close();
                     } catch (Throwable var8) {
                        var13.addSuppressed(var8);
                     }
                  }

                  throw var13;
               }

               if (var18 != null) {
                  var18.close();
               }

               var18 = profiler.profile("renderWeatherFX");

               try {
                  this.renderWeatherFX();
               } catch (Throwable var12) {
                  if (var18 != null) {
                     try {
                        var18.close();
                     } catch (Throwable var7) {
                        var12.addSuppressed(var7);
                     }
                  }

                  throw var12;
               }

               if (var18 != null) {
                  var18.close();
               }

               if (PerformanceSettings.fboRenderChunk) {
                  FBORenderAreaHighlights.getInstance().render();
                  BuildingRoomsEditor.getInstance().renderMain();
               }

               ParameterInside.renderDebug();
               LineDrawer.render();
               if (GameClient.client) {
                  ClientServerMap.render(IsoCamera.frameState.playerIndex);
                  PassengerMap.render(IsoCamera.frameState.playerIndex);
               }

               var18 = profiler.profile("Skybox");

               try {
                  SkyBox.getInstance().render();
               } catch (Throwable var11) {
                  if (var18 != null) {
                     try {
                        var18.close();
                     } catch (Throwable var6) {
                        var11.addSuppressed(var6);
                     }
                  }

                  throw var11;
               }

               if (var18 != null) {
                  var18.close();
               }
            } catch (Throwable t) {
               ExceptionLogger.logException(t);
            }
         }
      }
   }

   private void renderPathfinding() {
      if (PathfindNative.useNativeCode) {
         PathfindNative.instance.render();
         PathfindNativeRenderer.instance.render();
      } else {
         PolygonalMap2.instance.render();
      }

      BorderFinderRenderer.instance.render();
   }

   private void renderVocals() {
      AnimalVocalsManager.instance.render();
      ZombieVocalsManager.instance.render();
   }

   private void renderWeatherFX() {
      this.getCell().getWeatherFX().getDrawer(IsoWeatherFX.cloudId).startFrame();
      this.getCell().getWeatherFX().getDrawer(IsoWeatherFX.fogId).startFrame();
      this.getCell().getWeatherFX().getDrawer(IsoWeatherFX.snowId).startFrame();
      this.getCell().getWeatherFX().getDrawer(IsoWeatherFX.rainId).startFrame();
      WeatherFxMask.renderFxMask(IsoCamera.frameState.playerIndex);
   }

   public void DrawPlayerCone() {
      int playerIndex = IsoCamera.frameState.playerIndex;
      SpriteRenderer.instance
         .pushIsoView(IsoPlayer.getInstance().getX(), IsoPlayer.getInstance().getY(), IsoPlayer.getInstance().getZ(), (float)Math.toRadians(180.0), false);
      IsoPlayer player = IsoPlayer.getInstance();
      float cone = 1.0F + LightingJNI.calculateVisionCone(player);
      Vector2 lookVector = player.getLookVector(this.coneTempo1);
      BaseVehicle vehicle = player.getVehicle();
      if (vehicle != null && !player.isAiming() && !player.isLookingWhileInVehicle() && vehicle.isDriver(player) && vehicle.getCurrentSpeedKmHour() < -1.0F) {
         lookVector.rotate((float) Math.PI);
      }

      if (cone < 0.0F) {
         cone = Math.abs(cone) + 1.0F;
      }

      cone = (float)(cone * (Math.PI / 2));
      this.coneTempo2.x = lookVector.x;
      this.coneTempo2.y = lookVector.y;
      this.coneTempo3.x = lookVector.x;
      this.coneTempo3.y = lookVector.y;
      this.coneTempo2.rotate(-cone);
      this.coneTempo3.rotate(cone);
      float offscreen1x = this.coneTempo2.x * 1000.0F;
      float offscreen1y = this.coneTempo2.y * 1000.0F;
      float offscreen2x = offscreen1x + -lookVector.x * 1000.0F;
      float offscreen2y = offscreen1y + -lookVector.y * 1000.0F;
      float offscreen3x = -lookVector.x * 1000.0F;
      float offscreen3y = -lookVector.y * 1000.0F;
      IndieGL.disableDepthTest();
      IndieGL.disableScissorTest();
      SpriteRenderer.instance.glBuffer(8, 0);
      if (ViewConeTextureFBO.instance.getTexture() != null) {
         SpriteRenderer.instance.glViewport(0, 0, ViewConeTextureFBO.instance.getTexture().getWidth(), ViewConeTextureFBO.instance.getTexture().getHeight());
      }

      IndieGL.StartShader(0);
      SpriteRenderer.instance.renderPoly(0.0F, 0.0F, offscreen1x, offscreen1y, offscreen2x, offscreen2y, offscreen3x, offscreen3y, 0.0F, 0.0F, 0.0F, 0.5F);
      IndieGL.EndShader();
      offscreen1x = this.coneTempo3.x * 1000.0F;
      offscreen1y = this.coneTempo3.y * 1000.0F;
      offscreen2x = offscreen1x + -lookVector.x * 1000.0F;
      offscreen2y = offscreen1y + -lookVector.y * 1000.0F;
      offscreen3x = -lookVector.x * 1000.0F;
      offscreen3y = -lookVector.y * 1000.0F;
      SpriteRenderer.instance.renderPoly(offscreen3x, offscreen3y, offscreen2x, offscreen2y, offscreen1x, offscreen1y, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.5F);
      SpriteRenderer.instance.glBuffer(9, 0);
      SpriteRenderer.instance
         .glViewport(
            IsoCamera.getScreenLeft(playerIndex),
            IsoCamera.getScreenTop(playerIndex),
            IsoCamera.getScreenWidth(playerIndex),
            IsoCamera.getScreenHeight(playerIndex)
         );
      SpriteRenderer.instance.popIsoView();
      IndieGL.enableScissorTest();
   }

   public void DrawPlayerCone2() {
      IndieGL.glDepthMask(false);
      IndieGL.glBlendFunc(770, 771);
      if (SceneShaderStore.blurShader != null) {
         SceneShaderStore.blurShader.setTexture(ViewConeTextureFBO.instance.getTexture());
      }

      if (SceneShaderStore.blurShader != null) {
         IndieGL.StartShader(SceneShaderStore.blurShader, IsoPlayer.getPlayerIndex());
      }

      SpriteRenderer.instance
         .render(
            ViewConeTextureFBO.instance.getTexture(),
            0.0F,
            Core.getInstance().getOffscreenHeight(IsoPlayer.getPlayerIndex()),
            Core.getInstance().getOffscreenWidth(IsoPlayer.getPlayerIndex()),
            -Core.getInstance().getOffscreenHeight(IsoPlayer.getPlayerIndex()),
            1.0F,
            1.0F,
            1.0F,
            1.0F,
            null
         );
      if (SceneShaderStore.blurShader != null) {
         IndieGL.EndShader();
      }

      IndieGL.glDepthMask(true);
   }

   private void DrawIsoCursorHelper() {
      if (Core.getInstance().getOffscreenBuffer() == null) {
         IsoPlayer player = IsoPlayer.getInstance();
         if (player != null && !player.isDead() && player.isAiming() && player.playerIndex == 0 && player.getJoypadBind() == -1) {
            if (!GameTime.isGamePaused()) {
               float alpha = 0.05F;
               switch (Core.getInstance().getIsoCursorVisibility()) {
                  case 0:
                     return;
                  case 1:
                     alpha = 0.05F;
                     break;
                  case 2:
                     alpha = 0.1F;
                     break;
                  case 3:
                     alpha = 0.15F;
                     break;
                  case 4:
                     alpha = 0.3F;
                     break;
                  case 5:
                     alpha = 0.5F;
                     break;
                  case 6:
                     alpha = 0.75F;
               }

               if (Core.getInstance().isFlashIsoCursor()) {
                  if (this.flashIsoCursorInc) {
                     this.flashIsoCursorA += 0.1F;
                     if (this.flashIsoCursorA >= 1.0F) {
                        this.flashIsoCursorInc = false;
                     }
                  } else {
                     this.flashIsoCursorA -= 0.1F;
                     if (this.flashIsoCursorA <= 0.0F) {
                        this.flashIsoCursorInc = true;
                     }
                  }

                  alpha = this.flashIsoCursorA;
               }

               Texture t = Texture.getSharedTexture("media/ui/isocursor.png");
               int width = (int)(t.getWidth() * Core.tileScale / 2.0F);
               int height = (int)(t.getHeight() * Core.tileScale / 2.0F);
               SpriteRenderer.instance.setDoAdditive(true);
               SpriteRenderer.instance.renderi(t, Mouse.getX() - width / 2, Mouse.getY() - height / 2, width, height, alpha, alpha, alpha, alpha, null);
               SpriteRenderer.instance.setDoAdditive(false);
            }
         }
      }
   }

   private void updateWorld() {
      this.currentCell.update();
      IsoRegions.update();
      HaloTextHelper.update();
      CollisionManager.instance.ResolveContacts();
      if (DebugOptions.instance.threadAnimation.getValue()) {
         animationThread = CompletableFuture.runAsync(MovingObjectUpdateScheduler.instance::postupdate);
      } else {
         ProfileArea var1 = GameProfiler.getInstance().profile("Animation");

         try {
            MovingObjectUpdateScheduler.instance.postupdate();
         } catch (Throwable var5) {
            if (var1 != null) {
               try {
                  var1.close();
               } catch (Throwable var4) {
                  var5.addSuppressed(var4);
               }
            }

            throw var5;
         }

         if (var1 != null) {
            var1.close();
         }
      }
   }

   public void FinishAnimation() {
      pzopt.AnimBatch.join(); // pzopt: animBatchAsync, the zombies' bone batch started at the end of the postupdate loop
      if (animationThread != null) {
         ProfileArea var1 = GameProfiler.getInstance().profile("Wait Animation");

         try {
            animationThread.join();
         } catch (Throwable var5) {
            if (var1 != null) {
               try {
                  var1.close();
               } catch (Throwable var4) {
                  var5.addSuppressed(var4);
               }
            }

            throw var5;
         }

         if (var1 != null) {
            var1.close();
         }

         animationThread = null;
      }
   }

   public void update() {
      AbstractPerformanceProfileProbe var1 = IsoWorld.s_performance.isoWorldUpdate.profile();

      try {
         this.updateInternal();
      } catch (Throwable var7) {
         if (var1 != null) {
            try {
               var1.close();
            } catch (Throwable var5) {
               var7.addSuppressed(var5);
            }
         }

         throw var7;
      }

      if (var1 != null) {
         var1.close();
      }

      ProfileArea var8 = GameProfiler.getInstance().profile("Update DZ");

      try {
         DesignationZone.update();
      } catch (Throwable var6) {
         if (var8 != null) {
            try {
               var8.close();
            } catch (Throwable var4) {
               var6.addSuppressed(var4);
            }
         }

         throw var6;
      }

      if (var8 != null) {
         var8.close();
      }
   }

   private void updateInternal() {
      this.frameNo++;
      if (GameServer.server) {
         try {
            VehicleManager.instance.serverUpdate();
         } catch (Exception ex) {
            DebugType.General.printException(ex, LogSeverity.Error);
         }
      }

      WorldSimulation.instance.update();
      HutchManager.getInstance().updateAll();
      ImprovedFog.update();
      this.helicopter.update();
      long currentMS = System.currentTimeMillis();
      if (currentMS - this.emitterUpdateMs >= 30L) {
         this.emitterUpdateMs = currentMS;
         this.emitterUpdate = true;
      } else {
         this.emitterUpdate = false;
      }

      for (int n = 0; n < this.currentEmitters.size(); n++) {
         BaseSoundEmitter e = this.currentEmitters.get(n);
         if (this.emitterUpdate || e.hasSoundsToStart()) {
            e.tick();
         }

         if (e.isEmpty()) {
            if (e instanceof FMODSoundEmitter fmodSoundEmitter) {
               fmodSoundEmitter.clearParameters();
            }

            this.currentEmitters.remove(n);
            this.freeEmitters.push(e);
            IsoObject owner = this.emitterOwners.remove(e);
            if (owner != null && owner.emitter == e) {
               owner.emitter = null;
            }

            n--;
         }
      }

      if (!GameClient.client && !GameServer.server) {
         IsoMetaCell currentChunk = this.metaGrid.getCurrentCellData();
         if (currentChunk != null) {
            currentChunk.checkTriggers();
         }
      }

      WorldSoundManager.instance.initFrame();
      ZombieGroupManager.instance.preupdate();
      OnceEvery.update();
      CollisionManager.instance.initUpdate();
      CompletableFuture<Void> thread = null;
      if (DebugOptions.instance.threadWorld.getValue()) {
         thread = CompletableFuture.runAsync(this::updateThread, PZForkJoinPool.commonPool());
      }

      GameProfiler profiler = GameProfiler.getInstance();
      ProfileArea var17 = profiler.profile("Update Climate");

      try {
         ClimateManager.getInstance().update();
      } catch (Throwable var12) {
         if (var17 != null) {
            try {
               var17.close();
            } catch (Throwable var9) {
               var12.addSuppressed(var9);
            }
         }

         throw var12;
      }

      if (var17 != null) {
         var17.close();
      }

      this.updateWorld();
      if (thread != null) {
         var17 = profiler.profile("Wait Thread");

         try {
            thread.join();
         } catch (Throwable var11) {
            if (var17 != null) {
               try {
                  var17.close();
               } catch (Throwable var8) {
                  var11.addSuppressed(var8);
               }
            }

            throw var11;
         }

         if (var17 != null) {
            var17.close();
         }
      } else {
         this.updateThread();
      }

      AnimationPlayerRecorder.onPostUpdateWorld();
   }

   private void updateThread() {
      GameProfiler profiler = GameProfiler.getInstance();
      ProfileArea i = profiler.profile("Update Buildings");

      try {
         this.updateBuildings();
      } catch (Throwable var14) {
         if (i != null) {
            try {
               i.close();
            } catch (Throwable var9) {
               var14.addSuppressed(var9);
            }
         }

         throw var14;
      }

      if (i != null) {
         i.close();
      }

      i = profiler.profile("Update Static");

      try {
         ObjectRenderEffects.updateStatic();
      } catch (Throwable var13) {
         if (i != null) {
            try {
               i.close();
            } catch (Throwable var8) {
               var13.addSuppressed(var8);
            }
         }

         throw var13;
      }

      if (i != null) {
         i.close();
      }

      for (int ix = 0; ix < this.addCoopPlayers.size(); ix++) {
         AddCoopPlayer acp = this.addCoopPlayers.get(ix);
         acp.update();
         if (acp.isFinished()) {
            this.addCoopPlayers.remove(ix--);
         }
      }

      if (!GameServer.server) {
         IsoPlayer.UpdateRemovedEmitters();
      }

      i = profiler.profile("Update DBs");

      try {
         this.updateDBs();
      } catch (Throwable var12) {
         if (i != null) {
            try {
               i.close();
            } catch (Throwable var7) {
               var12.addSuppressed(var7);
            }
         }

         throw var12;
      }

      if (i != null) {
         i.close();
      }

      if (this.updateSafehousePlayers > 0 && (GameServer.server || GameClient.client)) {
         this.updateSafehousePlayers--;
         if (this.updateSafehousePlayers == 0) {
            this.updateSafehousePlayers = 200;
            SafeHouse.updateSafehousePlayersConnected();
         }
      }

      i = profiler.profile("Update VA");

      try {
         AnimalZones.updateVirtualAnimals();
      } catch (Throwable var11) {
         if (i != null) {
            try {
               i.close();
            } catch (Throwable var6) {
               var11.addSuppressed(var6);
            }
         }

         throw var11;
      }

      if (i != null) {
         i.close();
      }

      i = profiler.profile("Load Animal Defs");

      try {
         AnimalTracksDefinitions.loadTracksDefinitions();
      } catch (Throwable var10) {
         if (i != null) {
            try {
               i.close();
            } catch (Throwable var5) {
               var10.addSuppressed(var5);
            }
         }

         throw var10;
      }

      if (i != null) {
         i.close();
      }
   }

   private void updateBuildings() {
      for (int n = 0; n < this.currentCell.getBuildingList().size(); n++) {
         ((IsoBuilding)this.currentCell.getBuildingList().get(n)).update();
      }
   }

   private void updateDBs() {
      try {
         if (PlayerDB.isAvailable()) {
            PlayerDB.getInstance().updateMain();
         }

         VehiclesDB2.instance.updateMain();
      } catch (Exception ex) {
         ExceptionLogger.logException(ex);
      }
   }

   public IsoCell getCell() {
      return this.currentCell;
   }

   private void PopulateCellWithSurvivors() {
   }

   public int getWorldSquareY() {
      return this.currentCell.chunkMap[IsoPlayer.getPlayerIndex()].worldY * 8;
   }

   public int getWorldSquareX() {
      return this.currentCell.chunkMap[IsoPlayer.getPlayerIndex()].worldX * 8;
   }

   public IsoMetaChunk getMetaChunk(int wx, int wy) {
      return this.metaGrid.getChunkData(wx, wy);
   }

   public IsoMetaChunk getMetaChunkFromTile(int wx, int wy) {
      return this.metaGrid.getChunkDataFromTile(wx, wy);
   }

   public float getGlobalTemperature() {
      return ClimateManager.getInstance().getTemperature();
   }

   public String getWeather() {
      return this.weather;
   }

   public void setWeather(String weather) {
      this.weather = weather;
   }

   public int getLuaSpawnCellX() {
      return PZMath.coordmodulo(this.luaPosX, 256);
   }

   @Deprecated
   public void setLuaSpawnCellX(int luaSpawnCellX) {
   }

   public int getLuaSpawnCellY() {
      return PZMath.coordmodulo(this.luaPosY, 256);
   }

   @Deprecated
   public void setLuaSpawnCellY(int luaSpawnCellY) {
   }

   public int getLuaPosX() {
      return this.luaPosX;
   }

   public void setLuaPosX(int luaPosX) {
      this.luaPosX = luaPosX;
   }

   public int getLuaPosY() {
      return this.luaPosY;
   }

   public void setLuaPosY(int luaPosY) {
      this.luaPosY = luaPosY;
   }

   public int getLuaPosZ() {
      return this.luaPosZ;
   }

   public void setLuaPosZ(int luaPosZ) {
      this.luaPosZ = luaPosZ;
   }

   public void setSpawnRegion(String spawnRegionName) {
      if (spawnRegionName != null) {
         this.spawnRegionName = spawnRegionName;
      }
   }

   public String getSpawnRegion() {
      return this.spawnRegionName;
   }

   public String getWorld() {
      return Core.gameSaveWorld;
   }

   public void transmitWeather() {
      if (GameServer.server) {
         GameServer.sendWeather();
      }
   }

   public boolean isValidSquare(int x, int y, int z) {
      return z >= -32 && z <= 31 ? this.metaGrid.isValidSquare(x, y) : false;
   }

   public ArrayList<RandomizedZoneStoryBase> getRandomizedZoneList() {
      return this.randomizedZoneList;
   }

   public RandomizedZoneStoryBase getRandomizedZoneStoryByName(String name) {
      for (int i = 0; i < this.randomizedZoneList.size(); i++) {
         RandomizedZoneStoryBase rzs = this.randomizedZoneList.get(i);
         if (rzs.getName().equalsIgnoreCase(name)) {
            return rzs;
         }
      }

      return null;
   }

   public ArrayList<RandomizedBuildingBase> getRandomizedBuildingList() {
      return this.randomizedBuildingList;
   }

   public ArrayList<RandomizedVehicleStoryBase> getRandomizedVehicleStoryList() {
      return this.randomizedVehicleStoryList;
   }

   public RandomizedVehicleStoryBase getRandomizedVehicleStoryByName(String name) {
      for (int i = 0; i < this.randomizedVehicleStoryList.size(); i++) {
         RandomizedVehicleStoryBase rvs = this.randomizedVehicleStoryList.get(i);
         if (rvs.getName().equalsIgnoreCase(name)) {
            return rvs;
         }
      }

      return null;
   }

   public RandomizedBuildingBase getRBBasic() {
      return this.rbBasic;
   }

   public RandomizedWorldBase getRandomizedWorldBase() {
      return this.randomizedWorldBase;
   }

   public static boolean getZombiesDisabled() {
      return noZombies || !SystemDisabler.doZombieCreation || SandboxOptions.instance.zombies.getValue() == 6;
   }

   public static boolean getZombiesEnabled() {
      return !getZombiesDisabled();
   }

   public ClimateManager getClimateManager() {
      return ClimateManager.getInstance();
   }

   public IsoPuddles getPuddlesManager() {
      return IsoPuddles.getInstance();
   }

   public static int getWorldVersion() {
      return 249;
   }

   public HashMap<String, ArrayList<UUID>> getSpawnedZombieZone() {
      return this.spawnedZombieZone;
   }

   public int getTimeSinceLastSurvivorInHorde() {
      return this.timeSinceLastSurvivorInHorde;
   }

   public void setTimeSinceLastSurvivorInHorde(int timeSinceLastSurvivorInHorde) {
      this.timeSinceLastSurvivorInHorde = timeSinceLastSurvivorInHorde;
   }

   public float getWorldAgeDays() {
      float worldAgeDays = (float)GameTime.getInstance().getWorldAgeHours() / 24.0F;
      return worldAgeDays + (SandboxOptions.instance.timeSinceApo.getValue() - 1) * 30;
   }

   public HashMap<String, ArrayList<String>> getAllTiles() {
      return this.allTiles;
   }

   public ArrayList<String> getAllTilesName() {
      ArrayList<String> result = new ArrayList<>(this.allTiles.keySet());
      Collections.sort(result);
      return result;
   }

   public ArrayList<String> getAllTiles(String filename) {
      return this.allTiles.get(filename);
   }

   public boolean isHydroPowerOn() {
      return this.hydroPowerOn;
   }

   public void setHydroPowerOn(boolean on) {
      this.hydroPowerOn = on;
   }

   public ArrayList<String> getTileImageNames() {
      return this.tileImages;
   }

   public static void parseDistributions() {
      ItemPickerJava.Parse();
      ItemPickerJava.InitSandboxLootSettings();
   }

   public void setRules(Rules rules) {
      this.rules = rules;
   }

   public Rules getRules() {
      return this.rules;
   }

   public void setWgChunk(WorldGenChunk wgChunk) {
      this.wgChunk = wgChunk;
   }

   public WorldGenChunk getWgChunk() {
      return this.wgChunk;
   }

   public void setBlending(Blending blending) {
      this.blending = blending;
   }

   public Blending getBlending() {
      return this.blending;
   }

   public void setAttachmentsHandler(AttachmentsHandler attachmentsHandler) {
      this.attachmentsHandler = attachmentsHandler;
   }

   public AttachmentsHandler getAttachmentsHandler() {
      return this.attachmentsHandler;
   }

   public void setZoneGenerator(ZoneGenerator zoneGenerator) {
      this.zoneGenerator = zoneGenerator;
   }

   public ZoneGenerator getZoneGenerator() {
      return this.zoneGenerator;
   }

   public void setBiomeMap(BiomeMap biomeMap) {
      this.biomeMap = biomeMap;
   }

   public BiomeMap getBiomeMap() {
      return this.biomeMap;
   }

   public void setZombieVoronois(List<ZombieVoronoi> zombieVoronois) {
      this.zombieVoronois = zombieVoronois;
   }

   public List<ZombieVoronoi> getZombieVoronois() {
      return this.zombieVoronois;
   }

   private static class CompDistToPlayer implements Comparator<IsoZombie> {
      public float px;
      public float py;

      public int compare(IsoZombie a, IsoZombie b) {
         float aScore = IsoUtils.DistanceManhatten(PZMath.fastfloor(a.getX()), PZMath.fastfloor(a.getY()), this.px, this.py);
         float bScore = IsoUtils.DistanceManhatten(PZMath.fastfloor(b.getX()), PZMath.fastfloor(b.getY()), this.px, this.py);
         if (aScore < bScore) {
            return -1;
         } else {
            return aScore > bScore ? 1 : 0;
         }
      }
   }

   private long[] pzoptCullKeys = new long[1024];
   private IsoZombie[] pzoptCullTemp = new IsoZombie[1024];

   /**
    * pzopt: zombieCullSortFast. Stock sorts the zombies with a model by their relevance to the players with a comparator that
    * recomputes both scores on every comparison (n log n x 2 score walks, 1.4 % of the game thread in a Louisville horde).
    * Each score is computed once here, packed with the zombie's index into a long (score descending, index ascending on
    * ties) and the keys sorted as primitives, which yields exactly the order of stock's stable sort under the same comparator.
    */
   private void pzoptSortZombiesByScore() {
      int n = this.zombieWithModel.size();
      if (n < 2) {
         return;
      }

      if (this.pzoptCullKeys.length < n) {
         this.pzoptCullKeys = new long[Math.max(n, this.pzoptCullKeys.length * 2)];
         this.pzoptCullTemp = new IsoZombie[this.pzoptCullKeys.length];
      }

      IsoZombie[] elements = this.zombieWithModel.getElements();
      long[] keys = this.pzoptCullKeys;

      for (int i = 0; i < n; i++) {
         keys[i] = pzopt.SortKeys.descending(compScoreToPlayer.getScore(elements[i]), i);
      }

      Arrays.sort(keys, 0, n);
      IsoZombie[] temp = this.pzoptCullTemp;

      for (int i = 0; i < n; i++) {
         temp[i] = elements[pzopt.SortKeys.index(keys[i])];
      }

      System.arraycopy(temp, 0, elements, 0, n);
      Arrays.fill(temp, 0, n, null);
   }

   private static class CompScoreToPlayer implements Comparator<IsoZombie> {
      public int compare(IsoZombie a, IsoZombie b) {
         float aScore = this.getScore(a);
         float bScore = this.getScore(b);
         if (aScore < bScore) {
            return 1;
         } else {
            return aScore > bScore ? -1 : 0;
         }
      }

      public float getScore(IsoZombie zombie) {
         float maxScore = Float.MIN_VALUE;

         for (int pn = 0; pn < 4; pn++) {
            IsoPlayer player = IsoPlayer.players[pn];
            if (player != null && player.current != null) {
               float score = player.getZombieRelevenceScore(zombie);
               maxScore = Math.max(maxScore, score);
            }
         }

         return maxScore;
      }
   }

   public class Frame {
      public ArrayList<Integer> xPos = new ArrayList<>();
      public ArrayList<Integer> yPos = new ArrayList<>();
      public ArrayList<Integer> type = new ArrayList<>();

      public Frame() {
         Iterator<IsoMovingObject> it = IsoWorld.instance.currentCell.getObjectList().iterator();

         while (it != null && it.hasNext()) {
            IsoMovingObject o = it.next();
            int type;
            if (o instanceof IsoPlayer) {
               type = 0;
            } else if (o instanceof IsoSurvivor) {
               type = 1;
            } else {
               if (!(o instanceof IsoZombie isoZombie) || isoZombie.ghost) {
                  continue;
               }

               type = 2;
            }

            this.xPos.add(PZMath.fastfloor(o.getX()));
            this.yPos.add(PZMath.fastfloor(o.getY()));
            this.type.add(type);
         }
      }
   }

   public static class MetaCell {
      public int x;
      public int y;
      public int zombieCount;
      public IsoDirections zombieMigrateDirection;
      public int[][] from = new int[3][3];
   }

   private static class s_performance {
      static final PerformanceProfileProbe isoWorldUpdate = new PerformanceProfileProbe("IsoWorld.update");
      static final PerformanceProfileProbe isoWorldRender = new PerformanceProfileProbe("IsoWorld.render");
   }
}
