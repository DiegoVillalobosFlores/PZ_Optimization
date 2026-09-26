package zombie.iso.fboRenderChunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.joml.Vector2f;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaClosure;
import zombie.GameProfiler;
import zombie.GameTime;
import zombie.GameWindow;
import zombie.IndieGL;
import zombie.SandboxOptions;
import zombie.GameProfiler.ProfileArea;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.audio.FMODAmbientWalls;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.IsoGameCharacter.Location;
import zombie.core.BoxedStaticValues;
import zombie.core.Color;
import zombie.core.Core;
import zombie.core.PZForkJoinPool;
import zombie.core.PerformanceSettings;
import zombie.core.SpriteRenderer;
import zombie.core.logger.ExceptionLogger;
import zombie.core.math.PZMath;
import zombie.core.opengl.RenderThread;
import zombie.core.opengl.Shader;
import zombie.core.profiling.AbstractPerformanceProfileProbe;
import zombie.core.profiling.PerformanceProfileProbe;
import zombie.core.properties.IsoPropertyType;
import zombie.core.skinnedmodel.model.ItemModelRenderer;
import zombie.core.textures.ColorInfo;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.debug.DebugOptions;
import zombie.debug.LineDrawer;
import zombie.entity.util.TimSort;
import zombie.gameStates.DebugChunkState;
import zombie.input.GameKeyboard;
import zombie.input.JoypadManager;
import zombie.iso.BuildingDef;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkLevel;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoDirections;
import zombie.iso.IsoDepthHelper; // pzopt: tree pass
import zombie.iso.IsoFloorBloodSplat;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMarkers;
import zombie.iso.IsoMetaCell;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.IsoPuddles;
import zombie.iso.IsoPuddlesGeometry;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWater;
import zombie.iso.IsoWaterGeometry;
import zombie.iso.IsoWorld;
import zombie.iso.LightingJNI;
import zombie.iso.RoomDef;
import zombie.iso.WorldMarkers;
import zombie.iso.IsoCell.PerPlayerRender;
import zombie.iso.IsoCell.s_performance;
import zombie.iso.IsoCell.s_performance.renderTiles;
import zombie.iso.LightingJNI.VisibleRoom;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.SpriteDetails.IsoObjectType;
import zombie.iso.areas.IsoBuilding;
import zombie.iso.areas.IsoRoom;
import zombie.iso.fboRenderChunk.FBORenderCutaways.ChunkLevelData;
import zombie.iso.objects.IsoBarbecue;
import zombie.iso.objects.IsoBarricade;
import zombie.iso.objects.IsoCarBatteryCharger;
import zombie.iso.objects.IsoCurtain;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoFire;
import zombie.iso.objects.IsoMannequin;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoTree;
import zombie.iso.objects.IsoWindow;
import zombie.iso.objects.IsoWindowFrame;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.iso.objects.interfaces.BarricadeAble;
import zombie.iso.sprite.CorpseFlies;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteInstance; // pzopt: tree pass
import zombie.iso.sprite.IsoSpriteGrid;
import zombie.iso.sprite.IsoSpriteManager;
import zombie.iso.sprite.shapers.FloorShaper;
import zombie.iso.sprite.shapers.FloorShaperAttachedSprites;
import zombie.iso.sprite.shapers.FloorShaperDeDiamond;
import zombie.iso.sprite.shapers.FloorShaperDiamond;
import zombie.iso.sprite.shapers.WallShaperN;
import zombie.iso.sprite.shapers.WallShaperW;
import zombie.iso.weather.fog.ImprovedFog;
import zombie.iso.weather.fx.WeatherFxMask;
import zombie.network.GameClient;
import zombie.popman.ObjectPool;
import zombie.tileDepth.TileSeamModifier;
import zombie.tileDepth.TileSeamManager.Tiles;
import zombie.ui.UIManager;
import zombie.util.Type;
import zombie.util.list.PZArrayList;
import zombie.util.list.PZArrayUtil;
import zombie.vehicles.BaseVehicle;
import zombie.vispoly.VisibilityPolygon2;

public final class FBORenderCell {
   public static final FBORenderCell instance = new FBORenderCell();
   private static final float BLACK_OUT_DIST = 10.0F;
   public static final boolean OUTLINE_DOUBLEDOOR_FRAMES = true;
   public static IsoObject lowestCutawayObjectW;
   public static IsoObject lowestCutawayObjectN;
   public IsoCell cell;
   public final ArrayList<IsoGridSquare> waterSquares = new ArrayList<>();
   public final ArrayList<IsoGridSquare> waterAttachSquares = new ArrayList<>();
   public final ArrayList<IsoGridSquare> fishSplashSquares = new ArrayList<>();
   public final ArrayList<IsoMannequin> mannequinList = new ArrayList<>();
   public boolean renderAnimatedAttachments;
   public boolean renderTranslucentOnly;
   public boolean renderWindowFrameOutline;
   public boolean renderDebugChunkState;
   private final FBORenderCell.PerPlayerData[] perPlayerData = new FBORenderCell.PerPlayerData[4];
   private long currentTimeMillis;
   private boolean windEffects;
   private boolean waterShader;
   private int puddlesQuality = -1;
   private float puddlesValue;
   private float wetGroundValue;
   private long puddlesRedrawTimeMs;
   private float snowFracTarget;
   private final TimSort timSort = new TimSort();
   private final int maxChunksPerFrame = 5;
   private final ColorInfo defColorInfo = new ColorInfo(1.0F, 1.0F, 1.0F, 1.0F);
   private final ArrayList<ArrayList<IsoFloorBloodSplat>> splatByType = new ArrayList<>();
   private final PZArrayList<IsoWorldInventoryObject> tempWorldInventoryObjects = new PZArrayList(IsoWorldInventoryObject.class, 16);
   private final PZArrayList<IsoGridSquare> tempSquares = new PZArrayList(IsoGridSquare.class, 64);
   private final ArrayList<Location> tempLocations = new ArrayList<>();
   private final ObjectPool<Location> locationPool = new ObjectPool(Location::new, "FBORenderCell.locationPool");
   private long delayedLoadingTimerMs;
   private boolean invalidateDelayedLoadingLevels;
   public static final PerformanceProfileProbe calculateRenderInfo = new PerformanceProfileProbe("FBORenderCell.calculateRenderInfo");
   public static final PerformanceProfileProbe cutaways = new PerformanceProfileProbe("FBORenderCell.cutaways");
   public static final PerformanceProfileProbe fog = new PerformanceProfileProbe("FBORenderCell.fog");
   public static final PerformanceProfileProbe puddles = new PerformanceProfileProbe("FBORenderCell.puddles");
   public static final PerformanceProfileProbe renderOneChunk = new PerformanceProfileProbe("FBORenderCell.renderOneChunk");
   public static final PerformanceProfileProbe renderOneChunkLevel = new PerformanceProfileProbe("FBORenderCell.renderOneChunkLevel");
   public static final PerformanceProfileProbe renderOneChunkLevel2 = new PerformanceProfileProbe("FBORenderCell.renderOneChunkLevel2");
   public static final PerformanceProfileProbe translucentFloor = new PerformanceProfileProbe("FBORenderCell.translucentFloor");
   public static final PerformanceProfileProbe translucentNonFloor = new PerformanceProfileProbe("FBORenderCell.translucentNonFloor");
   public static final PerformanceProfileProbe updateLighting = new PerformanceProfileProbe("FBORenderCell.updateLighting");
   public static final PerformanceProfileProbe water = new PerformanceProfileProbe("FBORenderCell.water");
   public static final PerformanceProfileProbe tilesProbe = new PerformanceProfileProbe("renderTiles");
   public static final PerformanceProfileProbe itemsProbe = new PerformanceProfileProbe("renderItemsInWorld");
   public static final PerformanceProfileProbe movingObjectsProbe = new PerformanceProfileProbe("renderMovingObjects");
   public static final PerformanceProfileProbe shadowsProbe = new PerformanceProfileProbe("renderShadows");
   public static final PerformanceProfileProbe visibilityProbe = new PerformanceProfileProbe("VisibilityPolygon2");
   public static final PerformanceProfileProbe translucentFloorObjectsProbe = new PerformanceProfileProbe("renderTranslucentFloorObjects");
   public static final PerformanceProfileProbe translucentObjectsProbe = new PerformanceProfileProbe("renderTranslucentObjects");
   public static final boolean FIX_CORPSE_CLIPPING = true;
   public static final boolean FIX_ITEM_CLIPPING = true;
   public static final boolean FIX_JUMBO_CLIPPING = true;
   private final PZArrayList<IsoChunk> sortedChunks = new PZArrayList(IsoChunk.class, 121);
   private static final float ALPHA_ADJACENT_TO_CUTAWAY_WALL = 0.25F;
   private static final float ALPHA_OCCLUDING_CEILING = 0.25F;
   private static final float ALPHA_OCCLUDING_EAVE = 0.05F;
   private static final float ALPHA_OCCLUDING_OTHER = 0.66F;
   private static final float ALPHA_OCCLUDING_STAIR = 0.5F;
   private static final float ALPHA_OCCLUDING_TABLETOP = 0.66F;
   public static float blackedOutRoomFadeBlackness;
   public static long blackedOutRoomFadeDurationMs = 800L;
   private final Object[] L_callBeforeWorldRender = new Object[4];

   static {
      pzopt.Overrides.onClassLoaded("zombie.iso.fboRenderChunk.FBORenderCell");
   }

   private FBORenderCell() {
      for (int playerIndex = 0; playerIndex < 4; playerIndex++) {
         this.perPlayerData[playerIndex] = new FBORenderCell.PerPlayerData(playerIndex);
      }
   }

   private static int pzoptDevRedrawFrames = -1;

   public void renderInternal() {
      if (pzopt.Config.INSTRUMENT) pzoptTlFrame();
      if (pzopt.Config.DEV_REDRAW_FRAME > 0) {
         // dev: force a full redraw of every on-screen chunk level N frames after the first render
         if (pzoptDevRedrawFrames < 0) pzoptDevRedrawFrames = 0;
         if (++pzoptDevRedrawFrames == pzopt.Config.DEV_REDRAW_FRAME) {
            FBORenderCell.PerPlayerData pd = this.perPlayerData[IsoCamera.frameState.playerIndex];
            for (int i = 0; i < pd.onScreenChunks.size(); i++) {
               pd.onScreenChunks.get(i).invalidateRenderChunkLevels(1024L);
            }
            pzopt.Log.info("dev: redraw of " + pd.onScreenChunks.size() + " on-screen chunks forced");
         }
      }
      int playerIndex = IsoCamera.frameState.playerIndex;
      if (pzopt.CharDraw.enabled()) {
         pzopt.CharDraw.walk(IsoWorld.instance.getCell().getObjectList(), this); // pzopt: charDrawPrep, the object walk on a worker from here
      }

      int playerZ = PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);
      if (!PerformanceSettings.newRoofHiding) {
         if (this.cell.hideFloors[playerIndex] && this.cell.unhideFloorsCounter[playerIndex] > 0) {
            this.cell.unhideFloorsCounter[playerIndex]--;
         }

         if (this.cell.unhideFloorsCounter[playerIndex] <= 0) {
            this.cell.hideFloors[playerIndex] = false;
            this.cell.unhideFloorsCounter[playerIndex] = 60;
         }
      }

      int x1 = 0;
      int y1 = 0;
      int x2 = 0 + IsoCamera.getOffscreenWidth(playerIndex);
      int y2 = 0 + IsoCamera.getOffscreenHeight(playerIndex);
      float topLeftX = IsoUtils.XToIso(0.0F, 0.0F, 0.0F);
      float topRightY = IsoUtils.YToIso(x2, 0.0F, 0.0F);
      float bottomRightX = IsoUtils.XToIso(x2, y2, 6.0F);
      float bottomLeftY = IsoUtils.YToIso(0.0F, y2, 6.0F);
      this.cell.minY = (int)topRightY;
      this.cell.maxY = (int)bottomLeftY;
      this.cell.minX = (int)topLeftX;
      this.cell.maxX = (int)bottomRightX;
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      perPlayerData1.occludedGridX1 = this.cell.minX;
      perPlayerData1.occludedGridY1 = this.cell.minY;
      perPlayerData1.occludedGridX2 = this.cell.maxX;
      perPlayerData1.occludedGridY2 = this.cell.maxY;
      this.cell.minX -= 2;
      this.cell.minY -= 2;
      this.cell.minX = this.cell.minX - this.cell.minX % 8;
      this.cell.minY = this.cell.minY - this.cell.minY % 8;
      this.cell.maxX = this.cell.maxX + (8 - this.cell.maxX % 8);
      this.cell.maxY = this.cell.maxY + (8 - this.cell.maxY % 8);
      this.cell.maxZ = IsoCell.maxHeight;
      IsoGameCharacter isoGameCharacter = IsoCamera.getCameraCharacter();
      if (isoGameCharacter == null) {
         this.cell.maxZ = 1;
      }

      if (IsoPlayer.getInstance().getZ() < 0.0F) {
         this.cell.maxZ = (int)Math.ceil(IsoPlayer.getInstance().getZ()) + 1;
      }

      if (this.cell.minX != this.cell.lastMinX || this.cell.minY != this.cell.lastMinY) {
         this.cell.lightUpdateCount = 10;
      }

      if (!PerformanceSettings.newRoofHiding) {
         IsoGridSquare currentSq = isoGameCharacter == null ? null : isoGameCharacter.getCurrentSquare();
         if (currentSq != null) {
            IsoGridSquare sq = this.cell.getGridSquare(Math.round(isoGameCharacter.getX()), Math.round(isoGameCharacter.getY()), playerZ);
            if (sq != null && this.cell.IsBehindStuff(sq)) {
               this.cell.hideFloors[playerIndex] = true;
            }

            if (!this.cell.hideFloors[playerIndex] && currentSq.getProperties().has(IsoFlagType.hidewalls)
               || !currentSq.getProperties().has(IsoFlagType.exterior)) {
               this.cell.hideFloors[playerIndex] = true;
            }
         }

         if (this.cell.hideFloors[playerIndex]) {
            this.cell.maxZ = playerZ + 1;
         }
      }

      this.cell.drawStencilMask();
      long lastPlayerWindowPeekingRoomId = this.cell.playerWindowPeekingRoomId[playerIndex];

      for (int i = 0; i < IsoPlayer.numPlayers; i++) {
         this.cell.playerWindowPeekingRoomId[i] = -1L;
         IsoPlayer player2 = IsoPlayer.players[i];
         if (player2 != null) {
            IsoBuilding currentBuilding = player2.getCurrentBuilding();
            if (currentBuilding == null) {
               IsoDirections playerDir = IsoDirections.fromAngle(player2.getForwardDirection());
               currentBuilding = this.cell.GetPeekedInBuilding(player2.getCurrentSquare(), playerDir);
               if (currentBuilding != null) {
                  this.cell.playerWindowPeekingRoomId[i] = this.cell.playerPeekedRoomId;
               }
            }
         }
      }

      if (lastPlayerWindowPeekingRoomId != this.cell.playerWindowPeekingRoomId[playerIndex]) {
         IsoPlayer.players[playerIndex].dirtyRecalcGridStack = true;
      }

      if (isoGameCharacter != null
         && isoGameCharacter.getCurrentSquare() != null
         && isoGameCharacter.getCurrentSquare().getProperties().has(IsoFlagType.hidewalls)) {
         this.cell.maxZ = playerZ + 1;
      }

      this.callBeforeWorldRender(playerIndex);
      this.cell.rendering = true;

      try {
         int maxHeight = playerZ < 0 ? playerZ : IsoCell.getInstance().chunkMap[playerIndex].maxHeight;
         int min = this.cell.chunkMap[playerIndex].minHeight;
         min = Math.max(min, playerZ);
         this.RenderTiles(min, maxHeight);
      } catch (Exception ex) {
         this.cell.rendering = false;
         ExceptionLogger.logException(ex);
      }

      this.cell.rendering = false;
      if (IsoGridSquare.getRecalcLightTime() < 0.0F) {
         IsoGridSquare.setRecalcLightTime(60.0F);
      }

      if (IsoGridSquare.getLightcache() <= 0) {
         IsoGridSquare.setLightcache(90);
      }

      ProfileArea var24 = GameProfiler.getInstance().profile("renderLast");

      try {
         for (IsoMovingObject obj : this.cell.getObjectList()) {
            obj.renderlast();
         }

         for (int i = 0; i < this.cell.getStaticUpdaterObjectList().size(); i++) {
            IsoObject obj = (IsoObject)this.cell.getStaticUpdaterObjectList().get(i);
            obj.renderlast();
         }
      } catch (Throwable var21) {
         if (var24 != null) {
            try {
               var24.close();
            } catch (Throwable var19) {
               var21.addSuppressed(var19);
            }
         }

         throw var21;
      }

      if (var24 != null) {
         var24.close();
      }

      IsoTree.checkChopTreeIndicators(playerIndex);
      IsoTree.renderChopTreeIndicators();
      this.cell.lastMinX = this.cell.minX;
      this.cell.lastMinY = this.cell.minY;
      this.cell.DoBuilding(playerIndex, true);
   }

   public void RenderTiles(int minHeight, int maxHeight) {
      this.cell.minHeight = minHeight;
      AbstractPerformanceProfileProbe var3 = s_performance.isoCellRenderTiles.profile();

      try {
         this.renderTilesInternal(maxHeight);
      } catch (Throwable var7) {
         if (var3 != null) {
            try {
               var3.close();
            } catch (Throwable var6) {
               var7.addSuppressed(var6);
            }
         }

         throw var7;
      }

      if (var3 != null) {
         var3.close();
      }
   }

   private void renderTilesInternal(int maxHeight) {
      pzopt.GpuSections.frame(IsoWorld.instance.getFrameNo()); // pzopt: GPU sections per-frame tick
      this.pzoptUpdateTreeMode(); // pzopt: treeBakeMaxChunksPerSec, once per frame
      FBORenderChunkManager.instance.recycle();
      if (DebugOptions.instance.terrain.renderTiles.enable.getValue()) {
         if (IsoCell.floorRenderShader == null) {
            RenderThread.invokeOnRenderContext(this.cell::initTileShaders);
         }

         FBORenderLevels.clearCachedSquares = !pzoptKeepPerFrameLists(); // pzopt: see pzoptKeepPerFrameLists
         int playerIndex = IsoCamera.frameState.playerIndex;
         IsoPlayer player = IsoPlayer.players[playerIndex];
         FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
         player.dirtyRecalcGridStackTime = player.dirtyRecalcGridStackTime - GameTime.getInstance().getMultiplier() / 4.0F;
         PerPlayerRender perPlayerRender = this.cell.getPerPlayerRenderAt(playerIndex);
         perPlayerRender.setSize(this.cell.maxX - this.cell.minX + 1, this.cell.maxY - this.cell.minY + 1);
         this.currentTimeMillis = System.currentTimeMillis();
         if (this.cell.minX != perPlayerRender.minX
            || this.cell.minY != perPlayerRender.minY
            || this.cell.maxX != perPlayerRender.maxX
            || this.cell.maxY != perPlayerRender.maxY) {
            perPlayerRender.minX = this.cell.minX;
            perPlayerRender.minY = this.cell.minY;
            perPlayerRender.maxX = this.cell.maxX;
            perPlayerRender.maxY = this.cell.maxY;
         }

         int currentZ = PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);
         ProfileArea bForceCutawayUpdate = GameProfiler.getInstance().profile("updateWeatherMask");

         try {
            this.updateWeatherMask(playerIndex, currentZ);
         } catch (Throwable var28) {
            if (bForceCutawayUpdate != null) {
               try {
                  bForceCutawayUpdate.close();
               } catch (Throwable var21) {
                  var28.addSuppressed(var21);
               }
            }

            throw var28;
         }

         if (bForceCutawayUpdate != null) {
            bForceCutawayUpdate.close();
         }

         boolean bForceCutawayUpdatex = false;
         if (perPlayerData1.lastZ != currentZ) {
            if (currentZ < 0 != perPlayerData1.lastZ < 0) {
               player.dirtyRecalcGridStack = true;
               this.invalidateAll(playerIndex);
            } else if (player.getBuilding() != null) {
               player.getBuilding().getDef().invalidateOverlappedChunkLevelsAbove(playerIndex, PZMath.min(currentZ, perPlayerData1.lastZ), 2048L);
            } else if (player.isClimbing()) {
               bForceCutawayUpdatex = true;
            }

            perPlayerData1.lastZ = currentZ;
            this.checkSeenRooms(player, currentZ);
         }

         int puddlesValue1 = (int)Math.ceil(IsoPuddles.getInstance().getPuddlesSizeFinalValue() * 500.0F);
         int wetGround1 = (int)Math.ceil(IsoPuddles.getInstance().getWetGroundFinalValue() * 500.0F);
         if (PerformanceSettings.puddlesQuality == 2
            && (this.puddlesValue != puddlesValue1 || this.wetGroundValue != wetGround1)
            && this.puddlesRedrawTimeMs + 1000L < this.currentTimeMillis) {
            this.puddlesValue = puddlesValue1;
            this.wetGroundValue = wetGround1;
            this.puddlesRedrawTimeMs = this.currentTimeMillis;
            this.invalidateAll(playerIndex);
         }

         if (SandboxOptions.instance.enableSnowOnGround.getValue() && this.snowFracTarget != this.cell.getSnowTarget()) {
            this.snowFracTarget = this.cell.getSnowTarget();
            this.invalidateAll(playerIndex);
         }

         CompletableFuture<Boolean> checkFuture = null;
         if (DebugOptions.instance.threadGridStacks.getValue()) {
            checkFuture = CompletableFuture.supplyAsync(() -> this.recalculateGridStacks(player, playerIndex), PZForkJoinPool.commonPool());
         }

         ProfileArea size = GameProfiler.getInstance().profile("runChecks");
         boolean var31; // pzopt: decompiler fix (declarations dropped)
         boolean var32;

         try {
            var31 = bForceCutawayUpdatex | this.runChecks(playerIndex);
         } catch (Throwable var27) {
            if (size != null) {
               try {
                  size.close();
               } catch (Throwable var20) {
                  var27.addSuppressed(var20);
               }
            }

            throw var27;
         }

         if (size != null) {
            size.close();
         }

         AbstractPerformanceProfileProbe var39 = renderTiles.recalculateAnyGridStacks.profile();

         try {
            if (checkFuture != null) {
               var32 = var31 | checkFuture.join();
            } else {
               var32 = var31 | this.recalculateGridStacks(player, playerIndex);
            }
         } catch (Throwable var26) {
            if (var39 != null) {
               try {
                  var39.close();
               } catch (Throwable var19) {
                  var26.addSuppressed(var19);
               }
            }

            throw var26;
         }

         if (var39 != null) {
            var39.close();
         }

         for (int z = 0; z < 8; z++) {
            var32 |= this.checkDebugKeys(playerIndex, z);
         }

         boolean var33 = var32 | this.checkDebugKeys(playerIndex, currentZ);
         if (var33) {
            FBORenderCutaways.getInstance().squareChanged(null);
         }

         AbstractPerformanceProfileProbe var41 = cutaways.profile();

         try {
            boolean var34 = var33 | FBORenderCutaways.getInstance().checkPlayerRoom(playerIndex);
            boolean var35 = var34 | this.cell.SetCutawayRoomsForPlayer();
            boolean var36 = var35 | FBORenderCutaways.getInstance().checkExteriorWalls(perPlayerData1.onScreenChunks);
            boolean var37 = var36 | FBORenderCutaways.getInstance().checkSlopedSurfaces(perPlayerData1.onScreenChunks);
            if (var37) {
               FBORenderCutaways.getInstance().squareChanged(null);
            }

            boolean var38 = var37 | FBORenderCutaways.getInstance().checkOccludedRooms(playerIndex, perPlayerData1.onScreenChunks);
            // pzopt: bakeScheduler. The frame's grants are made before the bake preparation, which then prepares only the
            // granted levels: it walked every dirty level every frame (64 squares x 2 levels of cutaway tests and light-info
            // calls each), a held one included (17 % of the game thread in late frames, run td-prof3). Occlusion counts are
            // last frame's here.
            this.pzoptSchedPlanned = pzopt.BakeScheduler.ON && !this.pzoptZoomFlood && !pzopt.ResumeShot.capturing; // pzopt
            if (this.pzoptSchedPlanned) { // pzopt
               this.pzoptSchedulePlan(playerIndex, this.currentTimeMillis); // pzopt
            } // pzopt
            this.prepareChunksForUpdating(playerIndex);
            if (var38) {
               FBORenderCutaways.getInstance().doCutawayVisitSquares(playerIndex, this.pzoptCutawayVisitChunks(perPlayerData1));
            }
         } catch (Throwable var29) {
            if (var41 != null) {
               try {
                  var41.close();
               } catch (Throwable var18) {
                  var29.addSuppressed(var18);
               }
            }

            throw var29;
         }

         if (var41 != null) {
            var41.close();
         }

         perPlayerData1.occlusionChanged = false;
         if (FBORenderOcclusion.getInstance().enabled && this.pzoptHasDirtyChunkTexturesForOcclusion(playerIndex)) {
            perPlayerData1.occlusionChanged = true;
            int sizex = (perPlayerData1.occludedGridX2 - perPlayerData1.occludedGridX1 + 1)
               * (perPlayerData1.occludedGridY2 - perPlayerData1.occludedGridY1 + 1);
            if (perPlayerData1.occludedGrid == null || perPlayerData1.occludedGrid.length < sizex) {
               perPlayerData1.occludedGrid = new int[sizex];
            }

            Arrays.fill(perPlayerData1.occludedGrid, -32);
            this.calculateOccludingSquares(playerIndex);
            FBORenderOcclusion.getInstance().occludedGrid = perPlayerData1.occludedGrid;
            FBORenderOcclusion.getInstance().occludedGridX1 = perPlayerData1.occludedGridX1;
            FBORenderOcclusion.getInstance().occludedGridY1 = perPlayerData1.occludedGridY1;
            FBORenderOcclusion.getInstance().occludedGridX2 = perPlayerData1.occludedGridX2;
            FBORenderOcclusion.getInstance().occludedGridY2 = perPlayerData1.occludedGridY2;
            this.pzoptCountsPrecomputed = pzopt.Config.OCCLUSION_COUNT_PARALLEL && pzopt.Overrides.enabled() && this.pzoptPrecountRenderedSquares(playerIndex); // pzopt: occlusionCountParallel
         } else {
            this.pzoptCountsPrecomputed = false; // pzopt: occlusionCountParallel
         }

         AbstractPerformanceProfileProbe var43 = updateLighting.profile();

         try {
            this.updateChunkLighting(playerIndex);
         } catch (Throwable var25) {
            if (var43 != null) {
               try {
                  var43.close();
               } catch (Throwable var17) {
                  var25.addSuppressed(var17);
               }
            }

            throw var25;
         }

         if (var43 != null) {
            var43.close();
         }

         this.checkBlackedOutBuildings(playerIndex);
         this.checkBlackedOutRooms(playerIndex);
         if (pzopt.CharDraw.enabled()) {
            // pzopt: charDrawPrep. Everything the zombies' draw data reads is final now (cutaways, this frame's lighting,
            // the blacked-out passes): the visibility test and the texture-creator check run here and the draw data
            // builds on the slot-init executor while the chunk bakes and the rest of performRenderTiles run, joined in
            // renderMovingObjects.
            pzopt.CharDraw.start(IsoWorld.instance.getCell().getObjectList(), this);
         }

         FBORenderLevels.clearCachedSquares = false;
         AbstractPerformanceProfileProbe var44 = renderTiles.performRenderTiles.profile();

         try {
            pzopt.GpuSections.begin("tiles"); // pzopt: GPU section
            this.performRenderTiles(perPlayerRender, playerIndex, this.currentTimeMillis);
            pzopt.GpuSections.end("tiles"); // pzopt: GPU section
         } catch (Throwable var24) {
            if (var44 != null) {
               try {
                  var44.close();
               } catch (Throwable var16) {
                  var24.addSuppressed(var16);
               }
            }

            throw var24;
         }

         if (var44 != null) {
            var44.close();
         }

         FBORenderLevels.clearCachedSquares = !pzoptKeepPerFrameLists(); // pzopt: see pzoptKeepPerFrameLists
         this.cell.playerCutawaysDirty[playerIndex] = false;
         IsoCell.ShadowSquares.clear();
         IsoCell.MinusFloorCharacters.clear();
         IsoCell.ShadedFloor.clear();
         IsoCell.SolidFloor.clear();
         IsoCell.VegetationCorpses.clear();
         AbstractPerformanceProfileProbe var45 = renderTiles.renderDebugPhysics.profile();

         try {
            this.cell.renderDebugPhysics(playerIndex);
         } catch (Throwable var23) {
            if (var45 != null) {
               try {
                  var45.close();
               } catch (Throwable var15) {
                  var23.addSuppressed(var15);
               }
            }

            throw var23;
         }

         if (var45 != null) {
            var45.close();
         }

         AbstractPerformanceProfileProbe var46 = renderTiles.renderDebugLighting.profile();

         try {
            this.cell.renderDebugLighting(perPlayerRender, maxHeight);
         } catch (Throwable var22) {
            if (var46 != null) {
               try {
                  var46.close();
               } catch (Throwable var14) {
                  var22.addSuppressed(var14);
               }
            }

            throw var22;
         }

         if (var46 != null) {
            var46.close();
         }

         FMODAmbientWalls.getInstance().render();
      }
   }

   private boolean recalculateGridStacks(IsoPlayer player, int playerIndex) {
      boolean bForceCutawayUpdate = false;
      // pzopt: the buildings-in-front / hidden-levels scan depends on the camera square and facing; with
      // GRID_STACK_INTERVAL it is skipped while both are unchanged, for at most that many frames
      int pzoptInterval = pzopt.Overrides.enabled() ? pzopt.Config.GRID_STACK_INTERVAL : 0;
      boolean pzoptSkip = false;
      if (pzoptInterval > 0 && !player.dirtyRecalcGridStack) {
         IsoGridSquare sq = IsoCamera.frameState.camCharacterSquare;
         zombie.iso.IsoDirections dir = player.getDir();
         int frame = IsoWorld.instance.getFrameNo();
         pzoptSkip = sq != null && sq == this.pzoptGridStackSquare && dir == this.pzoptGridStackDir && frame - this.pzoptGridStackFrame < pzoptInterval;
         if (!pzoptSkip) {
            this.pzoptGridStackSquare = sq;
            this.pzoptGridStackDir = dir;
            this.pzoptGridStackFrame = frame;
         }
      }
      if (!pzoptSkip) {
         FBORenderCutaways.getInstance().CalculatePointsOfInterest();
         bForceCutawayUpdate |= FBORenderCutaways.getInstance().CalculateBuildingsToCollapse();
         bForceCutawayUpdate |= FBORenderCutaways.getInstance().checkHiddenBuildingLevels();
      }
      bForceCutawayUpdate |= player.dirtyRecalcGridStack;
      this.recalculateAnyGridStacks(playerIndex);
      return bForceCutawayUpdate;
   }

   private void updateWeatherMask(int playerIndex, int currentZ) {
      if (WeatherFxMask.checkVisibleSquares(playerIndex, currentZ)) {
         WeatherFxMask.forceMaskUpdate(playerIndex);
         WeatherFxMask.initMask();
      }
   }

   private boolean runChecks(int playerIndex) {
      GameProfiler profiler = GameProfiler.getInstance();
      this.checkWaterQualityOption(playerIndex);
      this.checkWindEffectsOption(playerIndex);
      ProfileArea var4 = profiler.profile("Newly");

      boolean result;
      try {
         result = this.checkNewlyOnScreenChunks(playerIndex);
      } catch (Throwable var12) {
         if (var4 != null) {
            try {
               var4.close();
            } catch (Throwable var9) {
               var12.addSuppressed(var9);
            }
         }

         throw var12;
      }

      if (var4 != null) {
         var4.close();
      }

      var4 = profiler.profile("Obscuring");

      try {
         this.checkObjectsObscuringPlayer(playerIndex);
         this.checkFadingInObjectsObscuringPlayer(playerIndex);
      } catch (Throwable var11) {
         if (var4 != null) {
            try {
               var4.close();
            } catch (Throwable var8) {
               var11.addSuppressed(var8);
            }
         }

         throw var11;
      }

      if (var4 != null) {
         var4.close();
      }

      var4 = profiler.profile("Chunks");

      try {
         this.checkChunksWithTrees(playerIndex);
         this.checkSeamChunks(playerIndex);
      } catch (Throwable var10) {
         if (var4 != null) {
            try {
               var4.close();
            } catch (Throwable var7) {
               var10.addSuppressed(var7);
            }
         }

         throw var10;
      }

      if (var4 != null) {
         var4.close();
      }

      this.checkMannequinRenderDirection(playerIndex);
      this.checkPuddlesQualityOption(playerIndex);
      return result;
   }

   private void invalidateAll(int playerIndex) {
      IsoChunkMap chunkMap = this.cell.chunkMap[playerIndex];

      for (int xx = 0; xx < IsoChunkMap.chunkGridWidth; xx++) {
         for (int yy = 0; yy < IsoChunkMap.chunkGridWidth; yy++) {
            IsoChunk c = chunkMap.getChunk(xx, yy);
            if (c != null && !c.lightingNeverDone[playerIndex]) {
               FBORenderLevels renderLevels = c.getRenderLevels(playerIndex);
               renderLevels.invalidateAll(2048L);
            }
         }
      }
   }

   private void checkObjectsObscuringPlayer(int playerIndex) {
      this.calculatePlayerRenderBounds(playerIndex);
      this.calculateObjectsObscuringPlayer(playerIndex, this.tempLocations);
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      if (this.tempLocations.equals(perPlayerData1.squaresObscuringPlayer)) {
         this.locationPool.releaseAll(this.tempLocations);
         this.tempLocations.clear();
      } else {
         IsoChunkMap chunkMap = this.cell.getChunkMap(playerIndex);

         for (int i = 0; i < perPlayerData1.squaresObscuringPlayer.size(); i++) {
            Location location = perPlayerData1.squaresObscuringPlayer.get(i);
            if (!this.listContainsLocation(this.tempLocations, location)) {
               IsoGridSquare square = chunkMap.getGridSquare(location.x, location.y, location.z);
               if (square != null) {
                  square.invalidateRenderChunkLevel(8192L);
                  this.invalidateChunkLevelForRenderSquare(square);
               }
            }
         }

         this.locationPool.releaseAll(perPlayerData1.squaresObscuringPlayer);
         perPlayerData1.squaresObscuringPlayer.clear();
         PZArrayUtil.addAll(perPlayerData1.squaresObscuringPlayer, this.tempLocations);

         for (int i = 0; i < perPlayerData1.squaresObscuringPlayer.size(); i++) {
            Location location = perPlayerData1.squaresObscuringPlayer.get(i);
            IsoGridSquare square = chunkMap.getGridSquare(location.x, location.y, location.z);
            if (square != null) {
               square.invalidateRenderChunkLevel(8192L);
               this.invalidateChunkLevelForRenderSquare(square);
            }
         }

         this.tempLocations.clear();
      }
   }

   private void calculatePlayerRenderBounds(int playerIndex) {
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      float playerX = IsoCamera.frameState.camCharacterX;
      float playerY = IsoCamera.frameState.camCharacterY;
      float playerZ = IsoCamera.frameState.camCharacterZ;
      perPlayerData1.playerBoundsX = IsoUtils.XToScreen(playerX, playerY, playerZ, 0);
      perPlayerData1.playerBoundsY = IsoUtils.YToScreen(playerX, playerY, playerZ, 0);
      perPlayerData1.playerBoundsX = perPlayerData1.playerBoundsX - 32 * Core.tileScale;
      perPlayerData1.playerBoundsY = perPlayerData1.playerBoundsY - 112 * Core.tileScale;
      perPlayerData1.playerBoundsW = 64 * Core.tileScale;
      perPlayerData1.playerBoundsH = 128 * Core.tileScale;
   }

   private boolean isPotentiallyObscuringObject(IsoObject object) {
      if (object == null) {
         return false;
      }

      if (object instanceof IsoWorldInventoryObject) {
         return false;
      }

      IsoSprite sprite = object.getSprite();
      if (sprite == null) {
         return false;
      }

      IsoGameCharacter chr = IsoCamera.frameState.camCharacter;
      if (chr != null && chr.isSittingOnFurniture() && chr.isSitOnFurnitureObject(object)) {
         return false;
      }

      if (chr != null && chr.isOnBed() && object == chr.getBed()) {
         return false;
      }

      if (sprite.getProperties().has(IsoFlagType.water)) {
         return false;
      }

      if (!sprite.getProperties().has(IsoFlagType.attachedSurface) || !object.square.has(IsoFlagType.solid) && !object.square.has(IsoFlagType.solidtrans)) {
         if (sprite.getProperties().has(IsoFlagType.attachedE)
            || sprite.getProperties().has(IsoFlagType.attachedS)
            || sprite.getProperties().has(IsoFlagType.attachedCeiling)) {
            return true;
         } else if (object.getContainerCount() > 0) {
            return true;
         } else if (object.isStairsNorth()) {
            return IsoCamera.frameState.camCharacterSquare != null && IsoCamera.frameState.camCharacterSquare.HasStairs()
               ? false
               : object.getX() > PZMath.fastfloor(IsoCamera.frameState.camCharacterX);
         } else if (!object.isStairsWest()) {
            return sprite.solid || sprite.solidTrans;
         } else {
            return IsoCamera.frameState.camCharacterSquare != null && IsoCamera.frameState.camCharacterSquare.HasStairs()
               ? false
               : object.getY() > PZMath.fastfloor(IsoCamera.frameState.camCharacterY);
         }
      } else {
         return true;
      }
   }

   private void calculateObjectsObscuringPlayer(int playerIndex, ArrayList<Location> locations) {
      this.locationPool.releaseAll(locations);
      locations.clear();
      IsoPlayer player = IsoPlayer.players[playerIndex];
      if (player != null && player.getCurrentSquare() != null) {
         IsoChunkMap chunkMap = this.cell.getChunkMap(playerIndex);
         int sqx = player.getCurrentSquare().getX();
         int sqy = player.getCurrentSquare().getY();
         int sqz = player.getCurrentSquare().getZ();
         int sqLeftX = sqx - 1;
         int sqLeftY = sqy;
         int sqRightX = sqx;
         int sqRightY = sqy - 1;
         this.testSquareObscuringPlayer(playerIndex, sqx, sqy, sqz, locations);

         for (int i = 1; i <= 3; i++) {
            this.testSquareObscuringPlayer(playerIndex, sqx + i, sqy + i, sqz, locations);
            this.testSquareObscuringPlayer(playerIndex, sqLeftX + i, sqLeftY + i, sqz, locations);
            this.testSquareObscuringPlayer(playerIndex, sqRightX + i, sqRightY + i, sqz, locations);
            this.testSquareObscuringPlayer(playerIndex, sqx - 1 + i, sqy + 1 + i, sqz, locations);
            this.testSquareObscuringPlayer(playerIndex, sqx + 1 + i, sqy - 1 + i, sqz, locations);
         }

         for (int i = 0; i < locations.size(); i++) {
            Location location = locations.get(i);
            IsoGridSquare square = chunkMap.getGridSquare(location.x, location.y, location.z);
            if (square != null) {
               for (int j = 0; j < square.getObjects().size(); j++) {
                  IsoObject object = (IsoObject)square.getObjects().get(j);
                  if (this.isPotentiallyObscuringObject(object)) {
                     this.addObscuringStairObjects(locations, square, object);
                     IsoSprite sprite = object.getSprite();
                     if (sprite.getSpriteGrid() != null) {
                        IsoSpriteGrid spriteGrid = sprite.getSpriteGrid();
                        int spriteGridPosX = spriteGrid.getSpriteGridPosX(sprite);
                        int spriteGridPosY = spriteGrid.getSpriteGridPosY(sprite);
                        int spriteGridPosZ = spriteGrid.getSpriteGridPosZ(sprite);

                        for (int spriteGridZ = 0; spriteGridZ < spriteGrid.getLevels(); spriteGridZ++) {
                           for (int spriteGridY = 0; spriteGridY < spriteGrid.getHeight(); spriteGridY++) {
                              for (int spriteGridX = 0; spriteGridX < spriteGrid.getWidth(); spriteGridX++) {
                                 if (spriteGrid.getSprite(spriteGridX, spriteGridY) != null) {
                                    int squareX = square.x - spriteGridPosX + spriteGridX;
                                    int squareY = square.y - spriteGridPosY + spriteGridY;
                                    int squareZ = square.z - spriteGridPosZ + spriteGridZ;
                                    if (chunkMap.getGridSquare(squareX, squareY, squareZ) != null
                                       && !this.listContainsLocation(locations, squareX, squareY, squareZ)) {
                                       Location location1 = (Location)this.locationPool.alloc();
                                       location1.set(squareX, squareY, squareZ);
                                       locations.add(location1);
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

         FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];

         for (int i = 0; i < perPlayerData1.squaresObscuringPlayer.size(); i++) {
            Location location = perPlayerData1.squaresObscuringPlayer.get(i);
            if (!this.listContainsLocation(locations, location) && !this.listContainsLocation(perPlayerData1.fadingInSquares, location)) {
               IsoGridSquare square = chunkMap.getGridSquare(location.x, location.y, location.z);
               if (this.squareHasFadingInObjects(playerIndex, square)) {
                  Location location1 = (Location)this.locationPool.alloc();
                  location1.set(square.x, square.y, square.z);
                  perPlayerData1.fadingInSquares.add(location1);
               }
            }
         }
      }
   }

   private void addObscuringStairObjects(ArrayList<Location> locations, IsoGridSquare square, IsoObject object) {
      if (object.isStairsNorth()) {
         int dy1 = 0;
         int dy2 = 0;
         if (object.getType() == IsoObjectType.stairsTN) {
            dy2 = 2;
         }

         if (object.getType() == IsoObjectType.stairsMN) {
            dy1 = -1;
            dy2 = 1;
         }

         if (object.getType() == IsoObjectType.stairsBN) {
            dy1 = -2;
            dy2 = 0;
         }

         if (dy1 < dy2) {
            for (int dy = dy1; dy <= dy2; dy++) {
               IsoGridSquare square1 = IsoWorld.instance.currentCell.getGridSquare(square.x, square.y + dy, square.z);
               if (square1 != null && !this.listContainsLocation(locations, square.x, square.y + dy, square.z)) {
                  Location location1 = (Location)this.locationPool.alloc();
                  location1.set(square.x, square.y + dy, square.z);
                  locations.add(location1);
               }
            }
         }
      }

      if (object.isStairsWest()) {
         int dx1 = 0;
         int dx2 = 0;
         if (object.getType() == IsoObjectType.stairsTW) {
            dx2 = 2;
         }

         if (object.getType() == IsoObjectType.stairsMW) {
            dx1 = -1;
            dx2 = 1;
         }

         if (object.getType() == IsoObjectType.stairsBW) {
            dx1 = -2;
            dx2 = 0;
         }

         if (dx1 < dx2) {
            for (int dx = dx1; dx <= dx2; dx++) {
               IsoGridSquare square1 = IsoWorld.instance.currentCell.getGridSquare(square.x + dx, square.y, square.z);
               if (square1 != null && !this.listContainsLocation(locations, square.x + dx, square.y, square.z)) {
                  Location location1 = (Location)this.locationPool.alloc();
                  location1.set(square.x + dx, square.y, square.z);
                  locations.add(location1);
               }
            }
         }
      }
   }

   private void checkFadingInObjectsObscuringPlayer(int playerIndex) {
      IsoChunkMap chunkMap = this.cell.getChunkMap(playerIndex);
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];

      for (int i = 0; i < perPlayerData1.fadingInSquares.size(); i++) {
         Location location = perPlayerData1.fadingInSquares.get(i);
         IsoGridSquare square = chunkMap.getGridSquare(location.x, location.y, location.z);
         if (square != null && !this.squareHasFadingInObjects(playerIndex, square)) {
            square.invalidateRenderChunkLevel(8192L);
            this.invalidateChunkLevelForRenderSquare(square);
            perPlayerData1.fadingInSquares.remove(i--);
            this.locationPool.release(location);
         }
      }
   }

   private boolean squareHasFadingInObjects(int playerIndex, IsoGridSquare square) {
      if (square == null) {
         return false;
      }

      for (int i = 0; i < square.getObjects().size(); i++) {
         IsoObject object = (IsoObject)square.getObjects().get(i);
         if (this.isPotentiallyObscuringObject(object) && object.getAlpha(playerIndex) < 1.0F) {
            return true;
         }
      }

      return false;
   }

   private void invalidateChunkLevelForRenderSquare(IsoGridSquare square) {
      if (!square.getWorldObjects().isEmpty()) {
         int chunksPerWidth = 8;
         if (PZMath.coordmodulo(square.x, 8) == 0 && PZMath.coordmodulo(square.y, 8) == 7) {
            IsoGridSquare renderSquare = square.getAdjacentSquare(IsoDirections.S);
            if (renderSquare != null) {
               renderSquare.invalidateRenderChunkLevel(8192L);
            }
         }

         if (PZMath.coordmodulo(square.x, 8) == 7 && PZMath.coordmodulo(square.y, 8) == 0) {
            IsoGridSquare renderSquare = square.getAdjacentSquare(IsoDirections.E);
            if (renderSquare != null) {
               renderSquare.invalidateRenderChunkLevel(8192L);
            }
         }
      }
   }

   private boolean listContainsLocation(ArrayList<Location> locations, Location location) {
      return this.listContainsLocation(locations, location.x, location.y, location.z);
   }

   private boolean listContainsLocation(ArrayList<Location> locations, int x, int y, int z) {
      for (int i = 0; i < locations.size(); i++) {
         if (locations.get(i).equals(x, y, z)) {
            return true;
         }
      }

      return false;
   }

   private void testSquareObscuringPlayer(int playerIndex, int x, int y, int z, ArrayList<Location> locations) {
      IsoChunkMap chunkMap = this.cell.getChunkMap(playerIndex);
      IsoGridSquare square = chunkMap.getGridSquare(x, y, z);
      if (this.isSquareObscuringPlayer(playerIndex, square)) {
         if (this.listContainsLocation(locations, square.x, square.y, square.z)) {
            return;
         }

         Location location = (Location)this.locationPool.alloc();
         location.set(square.x, square.y, square.z);
         locations.add(location);
      }
   }

   private boolean isSquareObscuringPlayer(int playerIndex, IsoGridSquare square) {
      if (square == null) {
         return false;
      }

      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];

      for (int i = 0; i < square.getObjects().size(); i++) {
         IsoObject object = (IsoObject)square.getObjects().get(i);
         if (this.isPotentiallyObscuringObject(object)) {
            Texture texture = object.sprite.getTextureForCurrentFrame(object.getForwardIsoDirection(), object);
            if (texture != null
               && perPlayerData1.isObjectObscuringPlayer(square, texture, object.offsetX, object.offsetY + object.getRenderYOffset() * Core.tileScale)) {
               return true;
            }
         }
      }

      return false;
   }

   private void checkChunksWithTrees(int playerIndex) {
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];

      for (int i = 0; i < perPlayerData1.onScreenChunks.size(); i++) {
         IsoChunk c = perPlayerData1.onScreenChunks.get(i);
         if (0 >= c.minLevel && 0 <= c.maxLevel) {
            FBORenderLevels renderLevels = c.getRenderLevels(playerIndex);
            if (renderLevels.isOnScreen(0)) {
               boolean bInStencilRect = renderLevels.calculateInStencilRect(0, this.cell);
               if (!bInStencilRect && renderLevels.inStencilRect) {
                  renderLevels.inStencilRect = false;
                  renderLevels.invalidateLevel(0, 4096L);
               } else {
                  renderLevels.inStencilRect = bInStencilRect;
                  if (this.checkTreeTranslucency(playerIndex, renderLevels)) {
                     renderLevels.invalidateLevel(0, 4096L);
                  }
               }
            }
         }
      }
   }

   private void checkSeamChunks(int playerIndex) {
      IsoChunkMap chunkMap = this.cell.chunkMap[playerIndex];
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];

      for (int i = 0; i < perPlayerData1.onScreenChunks.size(); i++) {
         IsoChunk c = perPlayerData1.onScreenChunks.get(i);
         FBORenderLevels renderLevels = c.getRenderLevels(playerIndex);
         if (renderLevels.adjacentChunkLoadedCounter != c.adjacentChunkLoadedCounter) {
            renderLevels.adjacentChunkLoadedCounter = c.adjacentChunkLoadedCounter;
            int pzoptDirs = c.pzoptSeamDirs; // pzopt: seamDirections
            c.pzoptSeamDirs = 0; // pzopt
            if (pzopt.SeamSpread.ON && pzopt.SeamSpread.defer(c, playerIndex, renderLevels, Core.getInstance().getZoom(playerIndex), IsoWorld.instance.getFrameNo())) { // pzopt: seamSpread, queued
               continue; // pzopt
            } // pzopt
            if (pzopt.BakeScheduler.ON && pzopt.Config.SEAM_DIRECTIONS && pzoptDirs == 2) { // pzopt: seamDirections, no seam of this chunk reads the new neighbour
               renderLevels.invalidateAll(pzopt.BakeScheduler.DIRTY_SEAM_LOW); // pzopt: re-baked at the scheduler's lowest priority
               continue; // pzopt
            } // pzopt
            renderLevels.invalidateAll(1024L);
         }
      }
      if (pzopt.SeamSpread.ON) { // pzopt: seamSpread, release the oldest queued seam re-bakes within this frame's allowance
         pzopt.SeamSpread.release(playerIndex, IsoWorld.instance.getFrameNo(), this.pzoptBakesThisFrame); // pzopt: pzoptBakesThisFrame still holds last frame's count here
      } // pzopt
   }

   private boolean checkTreeTranslucency(int playerIndex, FBORenderLevels renderLevels) {
      if (Core.getInstance().getOptionDoWindSpriteEffects()) {
         return false;
      }

      float zoom = Core.getInstance().getZoom(playerIndex);
      if (renderLevels.isDirty(0, zoom)) {
         return false;
      }

      ArrayList<IsoGridSquare> squares = renderLevels.treeSquares;
      boolean bChanged = false;
      boolean pzoptAiming = IsoPlayer.getPlayer(IsoCamera.frameState.playerIndex).isAnyAimKeyDown(); // pzopt: hoisted out of the tree loop

      for (int i = 0; i < squares.size(); i++) {
         IsoGridSquare square = squares.get(i);
         if (square.chunk != null) {
            IsoTree tree = square.getTree();
            if (tree != null && pzopt.Config.TREES_IN_CHUNK_TEXTURE && pzopt.Overrides.enabled()) {
               if (!pzoptTreeTextureReady(tree)) {
                  if (this.pzoptTreesAwaitingTexture.add(tree)) {
                     pzoptTreesWaited++;
                  }
               } else if (!this.pzoptTreesAwaitingTexture.isEmpty() && this.pzoptTreesAwaitingTexture.remove(tree)) { // pzopt: the set is empty almost always; skip the hash per tree
                  pzoptTreesArrived++;
                  bChanged = true;
                  square.invalidateRenderChunkLevel(4096L);
                  IsoGridSquare renderSquare = tree.getRenderSquare();
                  if (renderSquare != null && renderSquare != square) {
                     renderSquare.invalidateRenderChunkLevel(4096L);
                  }
                  this.pzoptInvalidateTreeCopies(tree, playerIndex); // pzopt: issue #5
               }
            }
            if (tree != null && !this.isTreeRenderedEveryFrame(tree)) {
               boolean bChanged2 = false;
               if (tree.fadeAlpha < 1.0F != tree.wasFaded) {
                  tree.wasFaded = tree.fadeAlpha < 1.0F;
                  bChanged = true;
                  bChanged2 = true;
               }

               if (this.pzoptIsTranslucentTree(tree, pzoptAiming) != tree.renderFlag) { // pzopt: aim key read once per chunk, not per tree
                  tree.renderFlag = !tree.renderFlag;
                  bChanged = true;
                  bChanged2 = true;
               }

               if (bChanged2) {
                  IsoGridSquare renderSquare = tree.getRenderSquare();
                  if (renderSquare != null && tree.getSquare() != renderSquare) {
                     renderSquare.invalidateRenderChunkLevel(4096L);
                  }
                  this.pzoptInvalidateTreeCopies(tree, playerIndex); // pzopt: issue #5, the neighbours holding a copy
               }
            }
         }
      }

      return bChanged;
   }

   private void checkWaterQualityOption(int playerIndex) {
      if (this.waterShader != IsoWater.getInstance().getShaderEnable()) {
         this.waterShader = IsoWater.getInstance().getShaderEnable();
         FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];

         for (int i = 0; i < perPlayerData1.onScreenChunks.size(); i++) {
            IsoChunk c = perPlayerData1.onScreenChunks.get(i);
            if (0 >= c.minLevel && 0 <= c.maxLevel) {
               FBORenderLevels renderLevels = c.getRenderLevels(playerIndex);
               if (renderLevels.calculateOnScreen(0)) {
                  renderLevels.invalidateLevel(0, 1024L);
               }
            }
         }
      }
   }

   private void checkWindEffectsOption(int playerIndex) {
      if (this.windEffects != Core.getInstance().getOptionDoWindSpriteEffects()) {
         this.windEffects = Core.getInstance().getOptionDoWindSpriteEffects();
         FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];

         for (int i = 0; i < perPlayerData1.onScreenChunks.size(); i++) {
            IsoChunk c = perPlayerData1.onScreenChunks.get(i);
            if (0 >= c.minLevel && 0 <= c.maxLevel) {
               FBORenderLevels renderLevels = c.getRenderLevels(playerIndex);
               if (renderLevels.calculateOnScreen(0)) {
                  renderLevels.invalidateLevel(0, 4096L);
               }
            }
         }
      }
   }

   private void checkPuddlesQualityOption(int playerIndex) {
      if (this.puddlesQuality == 2 != (PerformanceSettings.puddlesQuality == 2)) {
         this.puddlesQuality = PerformanceSettings.puddlesQuality;
         IsoChunkMap chunkMap = IsoWorld.instance.currentCell.chunkMap[playerIndex];

         for (int cy = 0; cy < IsoChunkMap.chunkGridWidth; cy++) {
            for (int cx = 0; cx < IsoChunkMap.chunkGridWidth; cx++) {
               IsoChunk chunk = chunkMap.getChunk(cx, cy);
               if (chunk != null) {
                  for (int z = chunk.minLevel; z <= chunk.maxLevel; z++) {
                     IsoGridSquare[] squares = chunk.squares[chunk.squaresIndexOfLevel(z)];

                     for (int i = 0; i < squares.length; i++) {
                        IsoGridSquare square = squares[i];
                        if (square != null) {
                           IsoPuddlesGeometry pg = square.getPuddles();
                           if (pg != null) {
                              pg.init(square);
                           }
                        }
                     }
                  }
               }
            }
         }

         this.invalidateAll(playerIndex);
      }
   }

   // pzopt: renderChunkPrewarm (2026-09-23, the Dell hitch pass). A chunk level's first bake creates its render chunk:
   // two textures and an FBO, whose glGenTextures / glCheckFramebufferStatus stall the NVIDIA driver on the Dell for
   // tens of ms; the pool (sizeChunkStore, keyed by texture height) only fills as the view first fills, so the first
   // minute of play pays it while walking and turning. This fills the pool for the current zoom from the loading screen.
   private static long pzoptPoolLogNs;

   public static void pzoptPrewarmRenderChunks() { // pzopt
      int n = pzopt.Config.RENDER_CHUNK_PREWARM;
      if (n < 0) {
         int peak = pzoptReadPoolPeak(); // pzopt: auto = the most render chunks in use at once last session, + 5 %
         n = peak > 0 ? Math.max(32, Math.min(400, peak + peak / 20)) : 96;
      }
      if (n <= 0 || !pzopt.Overrides.enabled()) {
         return;
      }
      long t0 = System.nanoTime();
      float zoom = Core.getInstance().getZoom(0);
      FBORenderChunkManager m = FBORenderChunkManager.instance;
      int one = n * 2 / 3; // most chunks have one level (flat ground); the rest pair two
      int made = 0;
      for (int levels = 1; levels <= 2; levels++) {
         int count = levels == 1 ? one : n - one;
         int w = FBORenderLevels.calculateTextureWidthForLevels(0, levels - 1, zoom);
         int h = FBORenderLevels.calculateTextureHeightForLevels(0, levels - 1, zoom);
         ArrayList<FBORenderChunk> st = m.sizeChunkStore.computeIfAbsent(h, k -> new ArrayList<>());
         for (int i = 0; i < count; i++) {
            FBORenderChunk rc = new FBORenderChunk();
            rc.w = w;
            rc.h = h;
            rc.index = m.rcIndex++;
            rc.preInit();
            rc.init();
            st.add(rc);
            made++;
         }
      }
      pzopt.Log.info("renderChunkPrewarm: " + made + " render chunks for zoom " + zoom + " in " + (System.nanoTime() - t0) / 1_000_000L + " ms");
   }

   static void pzoptLogRenderChunkPool() { // pzopt: every 10 s, how many render chunks exist and how many wait in the pool
      long now = System.nanoTime();
      if (now - pzoptPoolLogNs < 10_000_000_000L || pzopt.Config.RENDER_CHUNK_PREWARM >= 0 && !pzopt.Config.INSTRUMENT) {
         return;
      }
      pzoptPoolLogNs = now;
      FBORenderChunkManager m = FBORenderChunkManager.instance;
      StringBuilder sb = new StringBuilder("renderChunks: created=").append(m.rcIndex).append(" toppedUp=").append(pzoptTopUpMade).append(" pooled"); // pzopt: renderChunkTopUp count
      int pooled = 0;
      for (java.util.Map.Entry<Integer, ArrayList<FBORenderChunk>> e : m.sizeChunkStore.entrySet()) {
         sb.append(' ').append(e.getKey()).append(':').append(e.getValue().size());
         pooled += e.getValue().size();
      }
      int inUse = m.rcIndex - pooled - m.toRecycle.size();
      if (inUse > pzoptPoolPeak) {
         pzoptPoolPeak = inUse;
         pzoptWritePoolPeak(inUse);
      }
      if (pzopt.Config.INSTRUMENT) {
         pzopt.Log.info(sb.append(" inUse=").append(inUse).append(" peak=").append(pzoptPoolPeak).toString());
      }
   }

   private static int pzoptPoolPeak; // pzopt

   private static java.io.File pzoptPoolFile() { // pzopt
      return new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir() + java.io.File.separator + "pzopt", "renderchunk-pool.txt");
   }

   private static int pzoptReadPoolPeak() { // pzopt
      try {
         return Integer.parseInt(java.nio.file.Files.readString(pzoptPoolFile().toPath()).trim());
      } catch (Exception e) {
         return 0;
      }
   }

   private static void pzoptWritePoolPeak(int peak) { // pzopt: a session's peak, so the next load prewarms what this machine / resolution / zoom needs
      try {
         java.io.File f = pzoptPoolFile();
         f.getParentFile().mkdirs();
         java.nio.file.Files.writeString(f.toPath(), Integer.toString(peak));
      } catch (Exception e) {
         // pzopt: best effort
      }
   }

   // pzopt: renderChunkTopUp (2026-09-24, the Rosewood drive). The prewarm fills the pool once at load; a 120 km/h town drive
   // still made ~190 render chunks mid-drive (peak in use 481 vs 400 prewarmed), several in one frame, each two textures, an
   // FBO and a framebuffer check on the render thread (6 % of its samples in late frames). While the free pool for this zoom's
   // texture size holds fewer than renderChunkTopUp, up to renderChunkTopUpPerFrame new ones are made each frame, ahead of need;
   // their GL objects are made on the render thread (a TextureFBO built on the game thread blocks it until the render thread ran it).
   private static void pzoptTopUpRenderChunks(float zoom) { // pzopt
      int low = pzopt.Config.RENDER_CHUNK_TOP_UP;
      if (low <= 0 || !pzopt.Overrides.enabled()) {
         return;
      }
      FBORenderChunkManager m = FBORenderChunkManager.instance;
      for (int levels = 1; levels <= 2; levels++) {
         int h = FBORenderLevels.calculateTextureHeightForLevels(0, levels - 1, zoom);
         ArrayList<FBORenderChunk> st = m.sizeChunkStore.computeIfAbsent(h, k -> new ArrayList<>());
         if (st.size() >= low) {
            continue;
         }
         int w = FBORenderLevels.calculateTextureWidthForLevels(0, levels - 1, zoom);
         for (int i = 0; i < pzopt.Config.RENDER_CHUNK_TOP_UP_PER_FRAME && st.size() < low; i++) {
            FBORenderChunk rc = new FBORenderChunk();
            rc.w = w;
            rc.h = h;
            rc.index = m.rcIndex++;
            rc.preInit();
            SpriteRenderer.instance.drawGeneric(new pzopt.GlTask(rc::init)); // on the render thread, in stream order before any bake that takes it (init on the game thread waited for the render thread: TextureFBO)
            st.add(0, rc); // the pool hands out from the end
            pzoptTopUpMade++;
         }
         return; // one size a frame
      }
   }

   static long pzoptTopUpMade; // pzopt

   private boolean checkNewlyOnScreenChunks(int playerIndex) {
      pzoptLogRenderChunkPool(); // pzopt
      pzoptTopUpRenderChunks(Core.getInstance().getZoom(playerIndex)); // pzopt: renderChunkTopUp
      boolean bForceCutawaysUpdate = false;
      float cameraZoom = Core.getInstance().getZoom(playerIndex);
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      perPlayerData1.onScreenChunks.clear();
      perPlayerData1.chunksWithAnimatedAttachments.clear();
      perPlayerData1.chunksWithFlies.clear();
      IsoChunkMap chunkMap = this.cell.chunkMap[playerIndex];

      for (int xx = 0; xx < IsoChunkMap.chunkGridWidth; xx++) {
         for (int yy = 0; yy < IsoChunkMap.chunkGridWidth; yy++) {
            IsoChunk c = chunkMap.getChunk(xx, yy);
            if (c != null && !c.lightingNeverDone[playerIndex]) {
               FBORenderLevels renderLevels = c.getRenderLevels(playerIndex);
               if (!c.IsOnScreen(true)) {
                  for (int z = c.minLevel; z <= c.maxLevel; z++) {
                     renderLevels.setOnScreen(z, false);
                     if (pzoptZoomRetain) { // pzopt: zoomRetain, the textures stay while the chunk is inside the widest zoom's screen
                        pzopt.ZoomRetain.releaseOffScreen(renderLevels, c, z, playerIndex);
                     } else
                     renderLevels.freeFBOsForLevel(z);
                  }
               } else {
                  perPlayerData1.onScreenChunks.add(c);
                  if (renderLevels.prevMinZ != c.minLevel || renderLevels.prevMaxZ != c.maxLevel) {
                     for (int z = c.minLevel; z <= c.maxLevel; z++) {
                        renderLevels.invalidateLevel(z, 64L);
                     }
                  }

                  for (int z = c.minLevel; z <= c.maxLevel; z++) {
                     if (z == renderLevels.getMinLevel(z)) {
                        boolean bWasOnScreen = renderLevels.isOnScreen(z);
                        boolean bOnScreen = renderLevels.calculateOnScreen(z);
                        if (bOnScreen && !renderLevels.getCachedSquares_Flies(z).isEmpty()) {
                           perPlayerData1.addChunkWith_Flies(c);
                        }

                        if (bWasOnScreen != bOnScreen) {
                           if (bOnScreen) {
                              renderLevels.setOnScreen(z, true);
                              if (pzoptZoomRetain && renderLevels.prevMinZ != Integer.MAX_VALUE) {
                                 // pzopt: zoomRetain. Seen before and back during a zoom flood (a zoom-out brought it back): its
                                 // kept texture re-bakes, or its missing one is made, under the zoom plan. Back by the camera
                                 // moving (no flood): allowed at once, so the kept texture is redrawn this frame as stock's fresh
                                 // one was, never held by the re-bake budget (a stale roof for 1-3 frames read as flicker on the
                                 // Louisville walk, parity watch 2026-09-22 05:18)
                                 c.pzoptZoomReturned[playerIndex] |= 1L << (z + 32);
                                 if (!this.pzoptZoomFlood) {
                                    c.pzoptZoomAllowed[playerIndex] |= 1L << (z + 32);
                                 }
                                 pzopt.ZoomRetain.returned++;
                              }
                              renderLevels.invalidateLevel(z, 1024L);
                              if (renderLevels.isDirty(z, 16384L, cameraZoom)) {
                                 bForceCutawaysUpdate = true;
                              }
                           } else {
                              renderLevels.setOnScreen(z, false);
                              if (pzoptZoomRetain) { // pzopt: zoomRetain
                                 pzopt.ZoomRetain.releaseOffScreen(renderLevels, c, z, playerIndex);
                              } else
                              renderLevels.freeFBOsForLevel(z);
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      FBORenderChunkManager.instance.recycle();
      return bForceCutawaysUpdate;
   }

   private void performRenderTiles(PerPlayerRender perPlayerRender, int playerIndex, long currentTimeMillis) {
      Shader floorRenderShader = null;
      Shader wallRenderShader = null;
      this.renderAnimatedAttachments = false;
      this.renderTranslucentOnly = false;
      this.renderWindowFrameOutline = false;
      FBORenderChunkManager.instance.startFrame();
      if (pzopt.BakeLog.ON) pzopt.BakeLog.frame(); // pzopt: per-frame bake census (instrumented runs)
      this.pzoptBakesThisFrame = 0;
      this.pzoptRebakesThisFrame = 0; // pzopt: re-bake budget
      this.pzoptZoomRebakesThisFrame = 0; // pzopt: zoomRetain re-bake budget
      // pzopt: zoomRetain flood mode: the zoom changed this frame, or zoom work deferred last frame is still draining; while
      // it lasts, first-sight levels are made under the zoom budget too (a spin from 0.25 to 2.5 while driving brings
      // 200-300 chunk levels that were streamed in off screen; outside a flood a new chunk row bakes at once, as stock)
      float pzoptZoomNow = Core.getInstance().getZoom(playerIndex);
      boolean pzoptZoomChanged = this.pzoptLastZoom[playerIndex] > 0.0F && pzoptZoomNow != this.pzoptLastZoom[playerIndex];
      this.pzoptZoomChangedNow = pzoptZoomChanged;
      this.pzoptZoomFlood = pzoptZoomRetain && this.pzoptLastZoom[playerIndex] > 0.0F && (pzoptZoomChanged || this.pzoptZoomDeferredThisFrame > 0);
      this.pzoptLastZoom[playerIndex] = pzoptZoomNow;
      this.pzoptZoomDeferredThisFrame = 0;
      if (pzoptZoomRetain) {
         this.pzoptZoomPlan(playerIndex);
         if (pzoptZoomChanged) {
            this.pzoptZoomAllowLeft = 0; // the frame the zoom changes: nothing new starts, the next frame's plan sorts what appeared
         }
      }
      this.pzoptStrongThisFrame = 0; // pzopt: strong re-bake budget
      this.pzoptSchedNow = this.pzoptSchedPlanned && !this.pzoptZoomFlood; // pzopt: bakeScheduler, planned before the bake preparation
      this.pzoptCreatesThisFrame = 0; // pzopt: never-baked levels started this frame (the bakeBudget counts these alone)
      this.pzoptCreatesDeferredLastFrame = this.pzoptCreatesDeferredThisFrame;
      this.pzoptCreatesDeferredThisFrame = 0;
      this.pzoptDeferredTextures.clear();
      IsoPuddles.getInstance().clearThreadData();
      IsoWater.getInstance().clearThreadData();
      this.invalidateDelayedLoadingLevels = false;
      if (this.delayedLoadingTimerMs != 0L && this.delayedLoadingTimerMs <= currentTimeMillis) {
         this.delayedLoadingTimerMs = 0L;
         this.invalidateDelayedLoadingLevels = true;
      }

      FBORenderTrees.startFrame();
      SpriteRenderer.instance.beginProfile(tilesProbe);
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      perPlayerData1.chunksWithTranslucentFloor.clear();
      perPlayerData1.chunksWithTranslucentNonFloor.clear();

      pzopt.GpuSections.begin("chunks"); // pzopt: GPU section (every on-screen chunk: bakes and per-chunk passes)
      for (int i = 0; i < perPlayerData1.onScreenChunks.size(); i++) {
         IsoChunk c = perPlayerData1.onScreenChunks.get(i);
         AbstractPerformanceProfileProbe var10 = renderOneChunk.profile();

         try {
            this.renderOneChunk(c, perPlayerRender, playerIndex, currentTimeMillis, floorRenderShader, wallRenderShader); // pzopt: (a per-chunk GPU section here cost 600 queries a frame; bake + composite cover it)
         } catch (Throwable var28) {
            if (var10 != null) {
               try {
                  var10.close();
               } catch (Throwable var20) {
                  var28.addSuppressed(var20);
               }
            }

            throw var28;
         }

         if (var10 != null) {
            var10.close();
         }
      }

      pzopt.GpuSections.end("chunks"); // pzopt: GPU section
      if (pzoptZoomRetain) {
         this.pzoptZoomSettle(playerIndex); // pzopt: zoomRetain, a credit nothing consumed is not carried to the next plan
      }
      SpriteRenderer.instance.endProfile(tilesProbe);
      FBORenderCorpses.getInstance().update();
      FBORenderItems.getInstance().update();
      this.pzoptFlushTreeAppends(playerIndex, Core.getInstance().getZoom(playerIndex)); // pzopt: treeAppend, before the textures are composited
      pzopt.PixelLight.bakeEnd(); pzopt.SpriteFilter.bakeEnd(); // pzopt: pixelLight; sprite filter, the finished texture gets its sharp level 1, no square stays white past the bakes
      pzopt.ChunkAo.flush(playerIndex); // pzopt: ambient occlusion, this frame's budget of AO computes, before the textures are composited
      pzopt.Ssr.beforeComposite(playerIndex, this.perPlayerData[playerIndex].onScreenChunks); // pzopt: reflections, the water square map and the scatter's frame, ahead of the chunk composite
      pzopt.PixelLight.beforeComposite(playerIndex, this.perPlayerData[playerIndex].onScreenChunks); // pzopt: pixelLight, the lattice uploads and the camera, ahead of the chunk composite that lights each pixel
      pzopt.SpriteFilter.beforeComposite(playerIndex); // pzopt: sprite filter, this frame's composite program for the zoom
      pzopt.GpuSections.begin(pzopt.SpriteFilter.section("composite")); /* pzopt: GPU section: chunk textures into the combined FBO and onto the screen */
      if (pzopt.Config.COMPOSITE_SHADER_RUN && pzopt.Overrides.enabled() && !DebugOptions.instance.fboRenderChunk.combinedFbo.getValue()
            && DebugOptions.instance.fboRenderChunk.renderChunkTextures.getValue()) { // pzopt: compositeShaderRun
         this.pzoptCompositeChunks(); // pzopt
      } else { // pzopt
         FBORenderChunkManager.instance.endFrame();
      } // pzopt
      pzopt.GpuSections.end(pzopt.SpriteFilter.section("composite")); // pzopt: sprite filter, devSpriteFilterAlternate splits the section
      pzopt.Ssr.afterComposite(); // pzopt: reflections, dev timing of the composite with its scatter
      pzopt.AmbientOcclusion.queue(playerIndex); // pzopt: ambient occlusion on the static world, before anything else is drawn over it
      pzopt.PixelLight.afterComposite(playerIndex); // pzopt: pixelLight, the per-pixel light pass (pass mode) and the dev dumps, before anything else is drawn over the static world
      pzopt.CapsuleShadow.queue(playerIndex); // pzopt: sunShadows, the characters' sun shadows onto the static world (they add themselves below)
      FBORenderShadows.getInstance().clear();
      boolean pzoptFloorOnly = pzopt.ResumeShot.noMoving; // pzopt: resumeShot's exit capture (below "full"): no players, shadows, corpses
      if (!pzoptFloorOnly) {
      pzopt.GpuSections.begin("players"); // pzopt: GPU section (players, corpse / mannequin shadows)
      this.renderPlayers(playerIndex);
      this.renderCorpseShadows(playerIndex);
      this.renderMannequinShadows(playerIndex);
      pzopt.GpuSections.end("players"); // pzopt: GPU section
      }
      if (!DebugOptions.instance.fboRenderChunk.corpsesInChunkTexture.getValue() && !pzoptFloorOnly) {
         this.renderCorpsesInWorld(playerIndex);
      }

      if (!DebugOptions.instance.fboRenderChunk.itemsInChunkTexture.getValue() && !pzoptFloorOnly) {
         SpriteRenderer.instance.beginProfile(itemsProbe);
         pzopt.GpuSections.begin("items"); /* pzopt: GPU section */ this.renderItemsInWorld(playerIndex); pzopt.GpuSections.end("items");
         SpriteRenderer.instance.endProfile(itemsProbe);
      }

      if (PerformanceSettings.puddlesQuality < 2) {
         AbstractPerformanceProfileProbe var29 = puddles.profile();

         try {
            pzopt.Ssr.devDump(playerIndex, "puddles", false); // pzopt: reflections, dev frame dump
            pzopt.GpuSections.begin("puddles"); /* pzopt: GPU section */ this.renderPuddles(playerIndex); pzopt.GpuSections.end("puddles");
            pzopt.Ssr.devDump(playerIndex, "puddles", true); // pzopt: reflections, dev frame dump
         } catch (Throwable var27) {
            if (var29 != null) {
               try {
                  var29.close();
               } catch (Throwable var19) {
                  var27.addSuppressed(var19);
               }
            }

            throw var27;
         }

         if (var29 != null) {
            var29.close();
         }
      }

      this.renderOpaqueObjectsEvent(playerIndex);
      SpriteRenderer.instance.beginProfile(movingObjectsProbe);
      if (!pzopt.ResumeShot.noMoving) { // pzopt: resumeShot's exit capture (below "full"): no vehicles or characters
      pzopt.GpuSections.begin("moving"); /* pzopt: GPU section */ this.renderMovingObjects(); pzopt.GpuSections.end("moving");
      }
      SpriteRenderer.instance.endProfile(movingObjectsProbe);
      AbstractPerformanceProfileProbe var30 = water.profile();

      try {
         pzopt.Ssr.devDump(playerIndex, "water", false); // pzopt: reflections, dev frame dump
         pzopt.Ssr.beforeWater(playerIndex); // pzopt: reflections, this frame's camera for the water shader
         pzopt.GpuSections.begin("water"); /* pzopt: GPU section */ this.renderWater(playerIndex); pzopt.GpuSections.end("water");
         pzopt.Ssr.afterWater(); // pzopt: reflections
         pzopt.Ssr.devDump(playerIndex, "water", true); // pzopt: reflections, dev frame dump
         if (pzopt.HdrGlint.queueGlintOnly()) { // pzopt: HDR output, water + puddle glints after everything that can stand on them
            this.pzoptWaterOnly = true; // pzopt: HDR output, only the water shader draws in the glint-only pass
            this.renderWater(playerIndex); // pzopt: HDR output
            this.pzoptWaterOnly = false; // pzopt: HDR output
            this.renderPuddles(playerIndex); // pzopt: HDR output
            pzopt.HdrGlint.queueOff(); // pzopt: HDR output
         }
      } catch (Throwable var26) {
         if (var30 != null) {
            try {
               var30.close();
            } catch (Throwable var18) {
               var26.addSuppressed(var18);
            }
         }

         throw var26;
      }

      if (var30 != null) {
         var30.close();
      }

      pzopt.GpuSections.begin("attach"); // pzopt: GPU section
      this.renderAnimatedAttachments(playerIndex);
      this.renderFlies(playerIndex);
      FBORenderObjectHighlight.getInstance().render(playerIndex);
      pzopt.GpuSections.end("attach"); // pzopt: GPU section
      IsoChunkMap chunkMap = IsoWorld.instance.currentCell.getChunkMap(playerIndex);

      pzopt.GpuSections.begin("zloop"); // pzopt: GPU section (per-level translucent / water / splashes / shadows)
      for (int z = chunkMap.minHeight; z <= chunkMap.maxHeight; z++) {
         SpriteRenderer.instance.beginProfile(translucentFloorObjectsProbe);
         AbstractPerformanceProfileProbe var34 = translucentFloor.profile();

         try {
            if (!pzopt.ResumeShot.noTranslucent) { // pzopt: resumeShot capture (floors, buildings): no translucent objects (per-frame trees)
            pzopt.GpuSections.begin("translucentFloor"); /* pzopt: GPU section */ this.renderTranslucentFloorObjects(playerIndex, z, floorRenderShader, wallRenderShader, currentTimeMillis); pzopt.GpuSections.end("translucentFloor");
            }
         } catch (Throwable var25) {
            if (var34 != null) {
               try {
                  var34.close();
               } catch (Throwable var17) {
                  var25.addSuppressed(var17);
               }
            }

            throw var25;
         }

         if (var34 != null) {
            var34.close();
         }

         SpriteRenderer.instance.endProfile(translucentFloorObjectsProbe);
         var34 = puddles.profile();

         try {
            pzopt.GpuSections.begin("puddles"); /* pzopt: GPU section */ this.renderPuddlesTranslucentFloorsOnly(playerIndex, z); pzopt.GpuSections.end("puddles");
         } catch (Throwable var24) {
            if (var34 != null) {
               try {
                  var34.close();
               } catch (Throwable var16) {
                  var24.addSuppressed(var16);
               }
            }

            throw var24;
         }

         if (var34 != null) {
            var34.close();
         }

         if (z == 0) {
            this.renderWaterShore(playerIndex);
         }

         pzopt.GpuSections.begin("splashes"); /* pzopt: GPU section */ this.renderRainSplashes(playerIndex, z); pzopt.GpuSections.end("splashes");
         SpriteRenderer.instance.beginProfile(shadowsProbe);
         pzopt.GpuSections.begin("shadows"); // pzopt: GPU section
         FBORenderShadows.getInstance().renderMain(z);
         pzopt.GpuSections.end("shadows"); // pzopt: GPU section
         SpriteRenderer.instance.endProfile(shadowsProbe);
         if (z == PZMath.fastfloor(IsoCamera.frameState.camCharacterZ)) {
            SpriteRenderer.instance.beginProfile(visibilityProbe);
            ProfileArea var36 = GameProfiler.getInstance().profile("Visibility");

            try {
               pzopt.GpuSections.begin(pzopt.Darkness.section("vispoly")); // pzopt: GPU section (the vision cone: polygon + blur + screen pass; devDarkAlternate splits it by the remembered-places state)
               VisibilityPolygon2.getInstance().renderMain(playerIndex);
               pzopt.GpuSections.end(pzopt.Darkness.section("vispoly")); // pzopt: GPU section
            } catch (Throwable var23) {
               if (var36 != null) {
                  try {
                     var36.close();
                  } catch (Throwable var15) {
                     var23.addSuppressed(var15);
                  }
               }

               throw var23;
            }

            if (var36 != null) {
               var36.close();
            }

            SpriteRenderer.instance.endProfile(visibilityProbe);
         }

         WorldMarkers.instance.renderGridSquareMarkers(z);
         this.renderTranslucentOnly = true;
         IsoMarkers.instance.renderIsoMarkers(perPlayerRender, z, playerIndex);
         this.renderTranslucentOnly = false;
         SpriteRenderer.instance.beginProfile(translucentObjectsProbe);
         var34 = translucentNonFloor.profile();

         try {
            if (!pzopt.ResumeShot.noTranslucent) { // pzopt: resumeShot capture (floors, buildings): no translucent objects (per-frame trees)
            pzopt.GpuSections.begin("translucent"); /* pzopt: GPU section */ this.renderTranslucentObjects(playerIndex, z, floorRenderShader, wallRenderShader, currentTimeMillis); pzopt.GpuSections.end("translucent");
            }
         } catch (Throwable var22) {
            if (var34 != null) {
               try {
                  var34.close();
               } catch (Throwable var14) {
                  var22.addSuppressed(var14);
               }
            }

            throw var22;
         }

         if (var34 != null) {
            var34.close();
         }

         SpriteRenderer.instance.endProfile(translucentObjectsProbe);
      }

      FBORenderShadows.getInstance().endRender();
      pzopt.GpuSections.end("zloop"); // pzopt: GPU section
      if (DebugOptions.instance.weather.showUsablePuddles.getValue()) {
         this.renderPuddleDebug(playerIndex);
      }

      AbstractPerformanceProfileProbe var33 = fog.profile();

      try {
         this.renderFog(playerIndex);
      } catch (Throwable var21) {
         if (var33 != null) {
            try {
               var33.close();
            } catch (Throwable var13) {
               var21.addSuppressed(var13);
            }
         }

         throw var21;
      }

      if (var33 != null) {
         var33.close();
      }

      FBORenderObjectOutline.getInstance().render(playerIndex);
   }

   private void renderOneChunk(
      IsoChunk c, PerPlayerRender perPlayerRender, int playerIndex, long currentTimeMillis, Shader floorRenderShader, Shader wallRenderShader
   ) {
      if (c != null && c.IsOnScreen(true)) {
         if (!c.lightingNeverDone[playerIndex]) {
            FBORenderLevels renderLevels = c.getRenderLevels(playerIndex);
            renderLevels.prevMinZ = c.minLevel;
            renderLevels.prevMaxZ = c.maxLevel;

            for (int zza = c.minLevel; zza <= c.maxLevel; zza++) {
               AbstractPerformanceProfileProbe var10 = renderOneChunkLevel.profile();

               try {
                  this.renderOneLevel(c, zza, perPlayerRender, playerIndex, currentTimeMillis, floorRenderShader, wallRenderShader);
               } catch (Throwable var14) {
                  if (var10 != null) {
                     try {
                        var10.close();
                     } catch (Throwable var13) {
                        var14.addSuppressed(var13);
                     }
                  }

                  throw var14;
               }

               if (var10 != null) {
                  var10.close();
               }

               if (DebugOptions.instance.fboRenderChunk.renderWallLines.getValue() && zza == PZMath.fastfloor(IsoCamera.frameState.camCharacterZ)) {
                  c.getCutawayData().debugRender(zza);
               }
            }

            IndieGL.glDepthMask(false);
            IndieGL.glDepthFunc(519);
         }
      }
   }

   private void renderOneLevel(
      IsoChunk c, int level, PerPlayerRender perPlayerRender, int playerIndex, long currentTimeMillis, Shader floorRenderShader, Shader wallRenderShader
   ) {
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      FBORenderLevels renderLevels = c.getRenderLevels(playerIndex);
      if (!renderLevels.isOnScreen(level)) {
         if (pzoptZoomRetain) { // pzopt: zoomRetain
            pzopt.ZoomRetain.releaseOffScreen(renderLevels, c, level, playerIndex);
         } else
         renderLevels.freeFBOsForLevel(level);
      } else {
         float zoom = Core.getInstance().getZoom(playerIndex);
         if (this.invalidateDelayedLoadingLevels && renderLevels.isDelayedLoading(level) && level == renderLevels.getMinLevel(level)) {
            renderLevels.invalidateLevel(level, 1024L);
         }

         if (FBORenderOcclusion.getInstance().enabled) {
            if (level == renderLevels.getMinLevel(level) && perPlayerData1.occlusionChanged && this.pzoptCountsPrecomputed && pzopt.Config.DEV_OCCLUSION_COUNT_CHECK) { // pzopt: rig, the worker count against the stock one
               pzoptCountChecks++; // pzopt
               if (renderLevels.getRenderedSquaresCount(level) != this.calculateRenderedSquaresCount(playerIndex, c, level)) pzoptCountMismatches++; // pzopt
            } // pzopt
            if (level == renderLevels.getMinLevel(level) && perPlayerData1.occlusionChanged && !this.pzoptCountsPrecomputed) { // pzopt: occlusionCountParallel counted it already this frame
               renderLevels.setRenderedSquaresCount(level, this.calculateRenderedSquaresCount(playerIndex, c, level));
            }

            if (renderLevels.getRenderedSquaresCount(level) == 0) {
               if (pzoptZoomRetain) {
                  c.pzoptZoomReturned[playerIndex] &= ~(1L << (renderLevels.getMinLevel(level) + 32)); // pzopt: zoomRetain, nothing pending for a culled level
                  c.pzoptZoomAllowed[playerIndex] &= ~(1L << (renderLevels.getMinLevel(level) + 32));
               }
               if (level == renderLevels.getMaxLevel(level) && !(pzopt.Config.OCCLUSION_RETAIN && pzopt.Overrides.enabled())) { // pzopt: occlusionRetain keeps the texture and its dirt
                  renderLevels.clearDirty(level, zoom);
                  if (renderLevels.getFBOForLevel(level, zoom) != null) {
                     renderLevels.freeFBOsForLevel(level);
                     FBORenderChunkManager.instance.recycle();
                  }
               }

               return;
            }
         }

         // pzopt: bake budget. A new chunk row at max zoom means dozens of chunk-level textures baked in one frame
         // (26 % of slow-frame samples, attr-jfr-all). Past the budget a dirty level is deferred: if it was baked
         // before, its previous texture is drawn as if it were clean; if never (DIRTY_CREATE still set) it is simply
         // not drawn this frame. Deferred levels are retried next frame in the same chunk order.
         int pzoptBudget = pzopt.Overrides.enabled() && !pzopt.ResumeShot.capturing ? pzopt.Config.BAKE_BUDGET : 0; // pzopt: the resume-shot capture frame bakes everything at once
         int pzoptRebakeMs = pzopt.Overrides.enabled() ? pzopt.Config.LIGHTING_REBAKE_MS : 0;
         // pzopt: zoomRetain. A level never made at all (its slot was never dirtied: a chunk streamed in while zoomed in and
         // not seen since) has no dirt to enter this block with and baked at once; during a zoom flood it takes the plan too.
         boolean pzoptZoomFresh = pzoptZoomRetain && this.pzoptZoomFlood && !renderLevels.isDirty(level, zoom)
               && level == renderLevels.getMinLevel(level) && renderLevels.getFBOForLevel(level, zoom) == null;
         if ((pzoptBudget > 0 || pzoptRebakeMs > 0 || pzoptZoomRetain) && (renderLevels.isDirty(level, zoom) || pzoptZoomFresh)) {
            FBORenderChunk pzoptRc = renderLevels.getFBOForLevel(level, zoom);
            boolean pzoptDefer;
            if (level == renderLevels.getMinLevel(level) && this.pzoptSchedNow) { // pzopt: bakeScheduler decides (pzoptSchedulePlan)
               pzoptDefer = !pzopt.BakeScheduler.get(playerIndex).granted(c, level); // pzopt
               if (pzoptDefer) { // pzopt
                  pzoptDeferredTotal++; // pzopt
                  if (pzoptRc != null) { // pzopt
                     this.pzoptDeferredTextures.add(pzoptRc); // pzopt: the level's upper half follows
                  } // pzopt
               } else { // pzopt
                  this.pzoptBakesThisFrame++; // pzopt
               } // pzopt
            } else if (level == renderLevels.getMinLevel(level)) { // pzopt: (was the only branch) the per-kind budgets
               // lighting-only re-bake (flag 32 alone: daylight drifted by 1/255 on some square) of a texture baked
               // less than LIGHTING_REBAKE_MS ago: hold it, the previous texture stays on screen
               boolean pzoptOnly32 = pzoptRc != null && !renderLevels.isDirty(level, ~32L, zoom);
               // pzopt: a strong change (a torch beam sweeping in, a light switch; pzopt.LightDirt) is neither sky drift
               // nor a flash: it re-bakes now, as stock does, unless the frame marked enough levels to be a global event.
               // At most LIGHTING_STRONG_BUDGET such re-bakes a frame (pzoptStrongNow): turning sweeps the out-of-sight
               // fade (darkMulti) across every exterior square, which marked ~every on-screen level strong each frame;
               // cheap at 400 fps in Rosewood, 100+ tall-chunk bakes a frame in downtown Louisville (10.8 fps, GPU 97 %,
               // never-baked levels starved black, 2026-09-22). Past the budget the level takes the holds below.
               // pzopt: creation first. A never-baked level (DIRTY_CREATE) is black on screen until it bakes, a held
               // re-bake only shows slightly stale light; so the never-baked budget counts creations alone (re-bakes used
               // to fill it, and in downtown with re-bakes flowing every frame the new chunks at the end of the order
               // stayed black), and while a creation was deferred last frame the optional re-bakes (strong, lighting
               // hold, spread) wait so the whole frame goes to the black ones (2026-09-22, Louisville horde preset).
               boolean pzoptCreate = renderLevels.isDirty(level, 512L, zoom);
               // pzopt: zoomRetain. A level back on screen after a zoom-out with no texture at this scale yet (never seen at
               // this scale, or freed outside the retention rectangle) is made ZOOM_REBAKE_BUDGET per frame, its other-scale
               // texture or nothing in its place meanwhile; the stock path made every one of them in the frame it returned
               // (300-400 for a 0.25 -> 2.5 spin). A first-sight level (a new chunk) still bakes at once.
               // pzopt: zoomRetain (pzopt.ZoomRetain). A level the zoom brought back on screen (bit in c.pzoptZoomReturned), or
               // a level first seen while a zoom flood drains, bakes when this frame's plan allows it (pzoptZoomPlan: the
               // pending levels nearest the camera first, an adaptive count per frame) and is otherwise held with its kept
               // texture, its other-scale one or nothing on screen; only object / item / obscuring changes bake at once.
               boolean pzoptZoomAllowedNow = false;
               if (pzoptZoomRetain) {
                  long pzoptBit = 1L << (level + 32);
                  boolean pzoptPending = (c.pzoptZoomReturned[playerIndex] & pzoptBit) != 0L;
                  boolean pzoptAllowed = pzoptPending && (c.pzoptZoomAllowed[playerIndex] & pzoptBit) != 0L;
                  boolean pzoptHoldable = pzoptRc == null || pzoptCreate || !renderLevels.isDirty(level, ~(32L | 1024L | 2048L | 4096L | 16384L), zoom);
                  if (!pzoptPending && this.pzoptZoomFlood && (pzoptRc == null || pzoptCreate)) {
                     // first sight during a flood: a spare credit of the plan, else it joins the pending set for the next plan
                     pzoptPending = true;
                     if (this.pzoptZoomAllowLeft > 0) {
                        this.pzoptZoomAllowLeft--;
                        pzoptAllowed = true;
                     } else {
                        c.pzoptZoomReturned[playerIndex] |= pzoptBit;
                     }
                  } else if (!pzoptPending && this.pzoptZoomChangedNow && pzoptRc != null && pzoptHoldable) {
                     // the frame the zoom changes also dirties levels that stayed on screen (the tree-fade stencil rectangle
                     // is screen pixels, a zoom-out sweeps it over ten times the world; redraws): they take the plan too
                     pzoptPending = true;
                     c.pzoptZoomReturned[playerIndex] |= pzoptBit;
                  }
                  if (pzoptPending) {
                     if (pzoptAllowed || !pzoptHoldable) {
                        c.pzoptZoomReturned[playerIndex] &= ~pzoptBit;
                        c.pzoptZoomAllowed[playerIndex] &= ~pzoptBit;
                        if (pzoptAllowed) {
                           pzoptZoomAllowedNow = true;
                           this.pzoptZoomRebakesThisFrame++;
                           if (pzoptRc == null || pzoptCreate) pzopt.ZoomRetain.creations++; else pzopt.ZoomRetain.rebakes++;
                        } else {
                           pzopt.ZoomRetain.urgent++;
                           for (int b = 0; b < 16; b++) {
                              if (renderLevels.isDirty(level, 1L << b, zoom)) pzopt.ZoomRetain.urgentFlags[b]++;
                           }
                        }
                     } else {
                        pzoptDeferredTotal++;
                        this.pzoptZoomDeferredThisFrame++;
                        if (pzoptRc != null) {
                           this.pzoptDeferredTextures.add(pzoptRc);
                        }
                        this.pzoptDeferZoomDraw(c, level, zoom, renderLevels, perPlayerData1, pzoptRc != null && !pzoptCreate ? pzoptRc : null);
                        return;
                     }
                  }
               }
               boolean pzoptStarving = pzoptBudget > 0 && this.pzoptCreatesDeferredLastFrame > 0;
               boolean pzoptStrong = pzoptOnly32 && !pzoptStarving && this.pzoptStrongNow(c, level);
               boolean pzoptLightingOnly = pzoptRebakeMs > 0 && pzoptOnly32 && !pzoptStrong;
               if (pzoptLightingOnly && !pzoptStarving) {
                  Long last = this.pzoptLastBakeMs.get(pzoptRc);
                  pzoptLightingOnly = last != null && currentTimeMillis - last < pzoptRebakeMs;
               }
               // only a never-baked level (DIRTY_CREATE) is deferred: a re-bake of a visible texture (obscuring set,
               // trees, cutaways, lighting) must land the same frame or the stale texture shows (window flicker)
               pzoptDefer = pzoptLightingOnly || (pzoptBudget > 0 && this.pzoptCreatesThisFrame >= pzoptBudget && pzoptCreate);
               if (pzoptZoomAllowedNow) {
                  pzoptDefer = false; // pzopt: zoomRetain, planned this frame
               }
               if (pzoptCreate && !pzoptZoomAllowedNow) {
                  if (pzoptDefer) {
                     this.pzoptCreatesDeferredThisFrame++;
                     pzoptCreatesStarved++;
                  } else {
                     this.pzoptCreatesThisFrame++;
                  }
               }
               if (pzoptLightingOnly) {
                  pzoptLightingRebakesHeld++;
               }
               // pzopt: re-bake budget. A texture already on screen whose only dirty reasons are lighting drift (32)
               // or a redraw (1024: neighbour loaded, came on screen, light switch) keeps its previous image for up to
               // REBAKE_MAX_FRAMES frames once REBAKE_BUDGET such re-bakes have started this frame. Object, tree,
               // obscuring and (since 2026-09-20 evening) cutaway changes are never held: their per-frame draws re-test
               // the live state (isTableTopObjectSquareCutaway, the window-frame flags), so a stale texture would show
               // the object neither baked nor per frame.
               int pzoptRebakeBudget = pzopt.Overrides.enabled() ? pzopt.Config.REBAKE_BUDGET : 0;
               if (!pzoptDefer && !pzoptZoomAllowedNow && pzoptRebakeBudget > 0 && pzoptRc != null && !renderLevels.isDirty(level, 512L, zoom)
                     && !renderLevels.isDirty(level, ~(32L | 1024L), zoom)) {
                  int pzoptNow = IsoWorld.instance.getFrameNo();
                  // pzopt: lighting-only dirt (32 alone: daylight drift, a lightning flash ramp) may stay stale for
                  // LIGHTING_REBAKE_MAX_FRAMES with LIGHTING_REBAKE_BUDGET starts per frame, so a flash that dirties every
                  // on-screen texture lands over ~25 frames instead of one; a redraw (1024) keeps the short cap.
                  boolean pzoptLightingDirt = !renderLevels.isDirty(level, ~32L, zoom);
                  int pzoptMaxFrames = pzoptLightingDirt ? pzopt.Config.LIGHTING_REBAKE_MAX_FRAMES : pzopt.Config.REBAKE_MAX_FRAMES;
                  int pzoptFrameBudget = pzoptLightingDirt ? pzopt.Config.LIGHTING_REBAKE_BUDGET : pzoptRebakeBudget;
                  if (pzoptStarving) {
                     pzoptFrameBudget = 0; // pzopt: creation first, the spread waits (its max-frames cap still applies)
                  }
                  if (pzoptStrong) {
                     pzoptFrameBudget = Integer.MAX_VALUE; // pzopt: strong lighting change within the strong budget, never held (pzopt.LightDirt)
                  }
                  if (this.pzoptRebakesThisFrame >= pzoptFrameBudget) {
                     Integer since = this.pzoptRebakeHeldSince.get(pzoptRc);
                     if (since == null) {
                        if (this.pzoptRebakeHeldSince.size() > 4096) {
                           this.pzoptRebakeHeldSince.clear();
                        }
                        this.pzoptRebakeHeldSince.put(pzoptRc, pzoptNow);
                        pzoptDefer = true;
                     } else if (pzoptNow - since < pzoptMaxFrames) {
                        pzoptDefer = true;
                     }
                  }
                  if (!pzoptDefer) {
                     this.pzoptRebakesThisFrame++;
                     this.pzoptRebakeHeldSince.remove(pzoptRc);
                     pzoptRebakesTotal++;
                  } else {
                     pzoptRebakesHeld++;
                  }
               }
               if (pzoptDefer) {
                  pzoptDeferredTotal++;
                  pzoptDeferredCumulative++;
                  if (pzoptRc != null) {
                     this.pzoptDeferredTextures.add(pzoptRc);
                  }
               } else {
                  this.pzoptBakesThisFrame++;
               }
            } else {
               pzoptDefer = pzoptRc == null || this.pzoptDeferredTextures.contains(pzoptRc);
            }
            if (pzoptDefer) {
               FBORenderChunk pzoptDraw = pzoptRc != null && !renderLevels.isDirty(level, 512L, zoom) ? pzoptRc : null;
               if (pzoptDraw == null && pzoptZoomRetain && pzopt.Config.ZOOM_PLACEHOLDER) {
                  // pzopt: zoomPlaceholder. The texture at this zoom's scale is still to be baked (the 0.75 crossing flips
                  // every on-screen level to the other scale); the level's texture at the other scale, when complete,
                  // is drawn in its place (FBORenderChunk.render scales by its own highRes flag) instead of nothing.
                  pzoptDraw = this.pzoptOtherScaleTexture(renderLevels, level, zoom);
               }
               if (pzoptDraw != null) {
                  // stale but complete texture: same as the clean path below
                  FBORenderChunkManager.instance.renderChunk = pzoptDraw;
                  FBORenderChunkManager.instance.endRenderChunkLevel(c, level, zoom, false); pzopt.PixelLight.bakeEnd(); pzopt.SpriteFilter.bakeEnd(); // pzopt: pixelLight; sprite filter, the finished texture gets its sharp level 1
                  if (!renderLevels.getCachedSquares_AnimatedAttachments(level).isEmpty()) {
                     perPlayerData1.addChunkWith_AnimatedAttachments(c);
                  }
                  if (!renderLevels.getCachedSquares_TranslucentFloor(level).isEmpty()) {
                     perPlayerData1.addChunkWith_TranslucentFloor(c);
                  }
                  if (renderLevels.getCachedSquares_Items(level).size()
                        + renderLevels.getCachedSquares_TranslucentNonFloor(level).size()
                        + renderLevels.getCachedSquares_CutawayWindowFrames(level).size()
                     > 0) {
                     perPlayerData1.addChunkWith_TranslucentNonFloor(c);
                  }
               }
               return;
            }
         }

         int frameNo = IsoWorld.instance.getFrameNo();
         boolean canRender = true;
         boolean pzoptWasDirty = renderLevels.isDirty(level, zoom); // pzopt: per-frame bake census
         if (pzopt.BakeLog.ON && pzoptWasDirty) pzopt.BakeLog.bake(c, renderLevels, level, zoom); // pzopt: per-frame bake census
         boolean isDirty = FBORenderChunkManager.instance.beginRenderChunkLevel(c, level, zoom, canRender, true);
         if (pzopt.BakeLog.ON && isDirty && !pzoptWasDirty) pzopt.BakeLog.hidden(c, level); // pzopt: a texture made here without prior dirt (bypasses every budget)
         if (isDirty && pzopt.PixelLight.ACTIVE) pzopt.PixelLight.bakeBegin(c, playerIndex); // pzopt: pixelLight, the chunk's squares hand out white light while its texture bakes
         if (isDirty && canRender) pzopt.GpuSections.begin("bake"); // pzopt: GPU section
         if (isDirty && canRender) pzopt.AmbientOcclusion.changed(); // pzopt: ambient occlusion, a chunk texture changes: recompute
         if (isDirty && canRender && pzopt.BakeMips.ON && FBORenderChunkManager.instance.renderChunk != null) { // pzopt: bakeMipLevels
            pzopt.BakeMips.onBake(FBORenderChunkManager.instance.renderChunk, FBORenderChunkManager.instance.renderChunk.getTexture()); // pzopt
         } // pzopt
         if (isDirty && canRender && FBORenderChunkManager.instance.renderChunk != null) pzopt.SpriteFilter.bakeBegin(FBORenderChunkManager.instance.renderChunk.getTexture()); // pzopt: sprite filter, the texture's sharp level 1 after the bake
         if (DebugOptions.instance.delayObjectRender.getValue()) {
            canRender = frameNo == c.loadedFrame || frameNo >= c.renderFrame;
         }

         if (isDirty && canRender) {
            if (level == renderLevels.getMinLevel(level)) {
               pzopt.LightDirt.baked(c, level, frameNo); // pzopt: the accumulated light changes of this level are on screen
               if (pzopt.BakeScheduler.ON) pzopt.BakeScheduler.get(playerIndex).baked(c, level); // pzopt: bakeScheduler, its wait restarts
            }
            if (level == renderLevels.getMinLevel(level) && pzoptRebakeMs > 0) {
               this.pzoptLastBakeMs.put(FBORenderChunkManager.instance.renderChunk, currentTimeMillis);
               if (this.pzoptLastBakeMs.size() > 4096) {
                  this.pzoptLastBakeMs.clear(); // bounded; textures are recycled, a lost stamp only means one early re-bake
               }
            }
            if (pzopt.Config.INSTRUMENT && level == renderLevels.getMinLevel(level)) {
               pzoptBakesTotal++;
               pzoptBakesCumulative++;
               if (this.pzoptZoomChangedNow) {
                  // pzopt: zoomRetain dev tally, what bakes in the frame the zoom changes (the plan starts nothing there)
                  pzopt.ZoomRetain.changeFrameBakes++;
                  if (renderLevels.isDirty(level, 512L, zoom)) pzopt.ZoomRetain.changeFrameCreates++;
                  if (renderLevels.prevMinZ == Integer.MAX_VALUE) pzopt.ZoomRetain.changeFrameFirstSight++;
                  if (!c.IsOnScreen(true)) pzopt.ZoomRetain.changeFrameOffScreen++;
                  for (int b = 0; b < 16; b++) {
                     if (renderLevels.isDirty(level, 1L << b, zoom)) pzopt.ZoomRetain.changeFrameFlags[b]++;
                  }
               }
               for (int b = 0; b < 16; b++) {
                  if (renderLevels.isDirty(level, 1L << b, zoom)) pzoptBakeFlags[b]++;
               }
            }
            boolean[][] flattenGrassEtc = perPlayerRender.flattenGrassEtc;
            IsoCell.ShadowSquares.clear();
            IsoCell.SolidFloor.clear();
            IsoCell.ShadedFloor.clear();
            IsoCell.VegetationCorpses.clear();
            IsoCell.MinusFloorCharacters.clear();
            GameProfiler profiler = GameProfiler.getInstance();
            AbstractPerformanceProfileProbe var17 = calculateRenderInfo.profile();

            try {
               if (level == renderLevels.getMinLevel(level)) {
                  renderLevels.clearCachedSquares(level);
                  pzopt.PuddleCache.invalidate(c, level); // pzopt: the puddle square list is rebuilt below
               } else if (pzoptKeepPerFrameLists()) { // pzopt: an upper level rebuilt without its group's lower level
                  pzoptDropLevelSquares(renderLevels, level); // pzopt: drops its own entries first (see pzoptDropLevelSquares)
                  pzopt.PuddleCache.invalidate(c, level); // pzopt
               }

               if (level == 0) {
                  renderLevels.treeSquares.clear();
               }

               ChunkLevelData levelData = c.getCutawayDataForLevel(level);
               IsoGridSquare[] squares = c.squares[c.squaresIndexOfLevel(level)];

               for (int i = 0; i < squares.length; i++) {
                  IsoGridSquare square = squares[i];
                  if (levelData.shouldRenderSquare(playerIndex, square) && !FBORenderOcclusion.getInstance().isOccluded(square.x, square.y, square.z)) {
                     if (!square.getObjects().isEmpty()) {
                        square.flattenGrassEtc = false;
                        ProfileArea squarex = profiler.profile("Calculate");

                        try {
                           this.calculateObjectRenderInfo(square);
                        } catch (Throwable var47) {
                           if (squarex != null) {
                              try {
                                 squarex.close();
                              } catch (Throwable var44) {
                                 var47.addSuppressed(var44);
                              }
                           }

                           throw var47;
                        }

                        if (squarex != null) {
                           squarex.close();
                        }

                        int flags = 0;
                        boolean bHasTranslucentFloor = false;
                        boolean bHasTranslucentNonFloor = false;
                        boolean bHasAttachedSpritesOnWater = false;
                        boolean bHasAnimatedAttachments = false;
                        boolean bHasItems = false;
                        IsoObject[] objects = (IsoObject[])square.getObjects().getElements();
                        int numObjects = square.getObjects().size();

                        for (int j = 0; j < numObjects; j++) {
                           IsoObject object = objects[j];
                           ObjectRenderInfo renderInfo = object.getRenderInfo(playerIndex);
                           switch (renderInfo.layer) {
                              case Floor:
                                 flags |= 1;
                                 break;
                              case Vegetation:
                                 flags |= 2;
                                 break;
                              case MinusFloor:
                                 flags |= 4;
                                 break;
                              case MinusFloorSE:
                                 flags |= 4;
                                 break;
                              case WorldInventoryObject:
                                 flags |= 4;
                                 break;
                              case Translucent:
                              case TranslucentSE:
                                 bHasTranslucentNonFloor = true;
                                 break;
                              case TranslucentFloor:
                                 bHasTranslucentFloor = true;
                           }

                           if (renderInfo.layer == ObjectRenderLayer.None
                              && object.sprite != null
                              && object.sprite.getProperties().has(IsoFlagType.water)
                              && object.getAttachedAnimSprite() != null
                              && !object.getAttachedAnimSprite().isEmpty()) {
                              bHasAttachedSpritesOnWater = true;
                           }

                           bHasAnimatedAttachments |= object.hasAnimatedAttachments();
                           if (!DebugOptions.instance.fboRenderChunk.itemsInChunkTexture.getValue()) {
                              bHasItems |= object instanceof IsoWorldInventoryObject;
                           }
                        }

                        if (bHasAnimatedAttachments) {
                           renderLevels.getCachedSquares_AnimatedAttachments(level).add(square);
                        }

                        if (!square.getStaticMovingObjects().isEmpty()) {
                           renderLevels.getCachedSquares_Corpses(level).add(square);
                        }

                        if (square.hasFlies()) {
                           renderLevels.getCachedSquares_Flies(level).add(square);
                        }

                        if (bHasItems) {
                           renderLevels.getCachedSquares_Items(level).add(square);
                        }

                        ProfileArea var85 = profiler.profile("Puddles");

                        try {
                           if (square.getPuddles() != null && square.getPuddles().shouldRender()) {
                              renderLevels.getCachedSquares_Puddles(level).add(square);
                           }
                        } catch (Throwable var54) {
                           if (var85 != null) {
                              try {
                                 var85.close();
                              } catch (Throwable var43) {
                                 var54.addSuppressed(var43);
                              }
                           }

                           throw var54;
                        }

                        if (var85 != null) {
                           var85.close();
                        }

                        if (bHasTranslucentFloor) {
                           renderLevels.getCachedSquares_TranslucentFloor(level).add(square);
                        }

                        if (bHasTranslucentNonFloor) {
                           renderLevels.getCachedSquares_TranslucentNonFloor(level).add(square);
                        }

                        if (!square.getStaticMovingObjects().isEmpty()) {
                           int var74 = flags | 2;
                           flags = var74 | 16;
                           if (square.HasStairs()) {
                              flags |= 4;
                           }
                        }

                        if (!square.getWorldObjects().isEmpty()) {
                           flags |= 2;
                        }

                        for (int m = 0; m < square.getMovingObjects().size(); m++) {
                           IsoMovingObject mov = (IsoMovingObject)square.getMovingObjects().get(m);
                           boolean bOnFloor = mov.isOnFloor();
                           if (bOnFloor && mov instanceof IsoZombie zombie) {
                              bOnFloor = zombie.isProne();
                              if (!BaseVehicle.renderToTexture) {
                                 bOnFloor = false;
                              }
                           }

                           int var75;
                           if (bOnFloor) {
                              var75 = flags | 2;
                           } else {
                              var75 = flags | 4;
                           }

                           flags = var75 | 16;
                        }

                        if (square.hasFlies()) {
                           flags |= 4;
                        }

                        if ((flags & 1) != 0) {
                           IsoCell.SolidFloor.add(square);
                        }

                        if ((flags & 8) != 0) {
                           IsoCell.ShadedFloor.add(square);
                        }

                        if ((flags & 2) != 0) {
                           IsoCell.VegetationCorpses.add(square);
                        }

                        if ((flags & 4) != 0) {
                           IsoCell.MinusFloorCharacters.add(square);
                        }

                        if ((flags & 16) != 0) {
                           IsoCell.ShadowSquares.add(square);
                        }

                        if (level == 0 && square.has(IsoObjectType.tree)) {
                           renderLevels.treeSquares.add(square);
                        }

                        if (square.getWater() != null && square.getWater().hasWater()) {
                           renderLevels.getCachedSquares_Water(level).add(square);
                        }

                        if (square.getWater() != null && square.getWater().isbShore() && IsoWater.getInstance().getShaderEnable()) {
                           renderLevels.getCachedSquares_WaterShore(level).add(square);
                        }

                        if (bHasAttachedSpritesOnWater) {
                           renderLevels.getCachedSquares_WaterAttach(level).add(square);
                        }
                     }
                  } else {
                     this.setNotRendered(square);
                  }
               }
            } catch (Throwable var55) {
               if (var17 != null) {
                  try {
                     var17.close();
                  } catch (Throwable var42) {
                     var55.addSuppressed(var42);
                  }
               }

               throw var55;
            }

            if (var17 != null) {
               var17.close();
            }

            var17 = renderOneChunkLevel2.profile();

            label658: {
               try {
                  if (level == renderLevels.getMinLevel(level)) {
                     renderLevels.clearDelayedLoading(level);
                  }

                  boolean renderFloor = true;
                  boolean renderObjects = true;
                  if (DebugOptions.instance.delayObjectRender.getValue()) {
                     renderFloor = frameNo == c.loadedFrame || frameNo > c.renderFrame;
                     renderObjects = frameNo >= c.renderFrame;
                  }

                  if (pzopt.ResumeShot.floorOnly && level > 0) {
                     // pzopt: resumeShot's exit capture draws ground-level floors only. The upper levels still run (and draw
                     // nothing): a chunk's levels share one texture, finished and queued for the screen at its top level.
                     renderFloor = false;
                  }
                  if (renderFloor) {
                     ProfileArea var59 = profiler.profile("Floor");

                     try {
                        for (int i = 0; i < IsoCell.SolidFloor.size(); i++) {
                           IsoGridSquare square = (IsoGridSquare)IsoCell.SolidFloor.get(i);
                           this.renderFloor(square);
                        }
                     } catch (Throwable var49) {
                        if (var59 != null) {
                           try {
                              var59.close();
                           } catch (Throwable var41) {
                              var49.addSuppressed(var41);
                           }
                        }

                        throw var49;
                     }

                     if (var59 != null) {
                        var59.close();
                     }

                     var59 = profiler.profile("Snow");

                     try {
                        IndieGL.disableDepthTest();
                        FBORenderSnow.getInstance().RenderSnow(c, level);
                        IndieGL.enableDepthTest();
                     } catch (Throwable var46) {
                        if (var59 != null) {
                           try {
                              var59.close();
                           } catch (Throwable var40) {
                              var46.addSuppressed(var40);
                           }
                        }

                        throw var46;
                     }

                     if (var59 != null) {
                        var59.close();
                     }

                     var59 = profiler.profile("Blood");

                     try {
                        if (IsoCamera.frameState.camCharacterZ >= 0.0F || level <= PZMath.fastfloor(IsoCamera.frameState.camCharacterZ)) {
                           int chunksPerWidth = 8;
                           this.renderOneLevel_Blood(c, level, c.wx * 8, c.wy * 8, (c.wx + 1) * 8, (c.wy + 1) * 8);
                           this.renderOneLevel_Blood(c, -1, -1, level);
                           this.renderOneLevel_Blood(c, 0, -1, level);
                           this.renderOneLevel_Blood(c, 1, -1, level);
                           this.renderOneLevel_Blood(c, -1, 0, level);
                           this.renderOneLevel_Blood(c, 1, 0, level);
                           this.renderOneLevel_Blood(c, -1, 1, level);
                           this.renderOneLevel_Blood(c, 0, 1, level);
                           this.renderOneLevel_Blood(c, 1, 1, level);
                        }
                     } catch (Throwable var48) {
                        if (var59 != null) {
                           try {
                              var59.close();
                           } catch (Throwable var39) {
                              var48.addSuppressed(var39);
                           }
                        }

                        throw var48;
                     }

                     if (var59 != null) {
                        var59.close();
                     }
                  }

                  if (!renderObjects) {
                     FBORenderChunkManager.instance.endRenderChunkLevel(c, level, zoom, false); pzopt.PixelLight.bakeEnd(); pzopt.SpriteFilter.bakeEnd(); // pzopt: pixelLight; sprite filter, the finished texture gets its sharp level 1
                     break label658;
                  }

                  if (DebugOptions.instance.terrain.renderTiles.vegetationCorpses.getValue()) {
                     ProfileArea var62 = profiler.profile("Vegetation Corpses");

                     try {
                        if (DebugOptions.instance.fboRenderChunk.corpsesInChunkTexture.getValue()) {
                           IsoGridSquare squareNW = c.getGridSquare(0, 0, level);
                           IsoGridSquare squareN = squareNW == null ? null : squareNW.getAdjacentSquare(IsoDirections.N);
                           if (squareNW != null && squareN != null) {
                              squareN.cacheLightInfo();
                              this.renderCorpses(squareN, squareNW, true);
                           }

                           IsoGridSquare squareW = squareNW == null ? null : squareNW.getAdjacentSquare(IsoDirections.W);
                           if (squareNW != null && squareW != null) {
                              squareW.cacheLightInfo();
                              this.renderCorpses(squareW, squareNW, true);
                           }
                        }

                        for (int i = 0; i < IsoCell.VegetationCorpses.size(); i++) {
                           IsoGridSquare square = (IsoGridSquare)IsoCell.VegetationCorpses.get(i);
                           this.renderVegetation(square);
                           if (DebugOptions.instance.fboRenderChunk.corpsesInChunkTexture.getValue()) {
                              this.renderCorpses(square, square, true);
                           }
                        }
                     } catch (Throwable var50) {
                        if (var62 != null) {
                           try {
                              var62.close();
                           } catch (Throwable var38) {
                              var50.addSuppressed(var38);
                           }
                        }

                        throw var50;
                     }

                     if (var62 != null) {
                        var62.close();
                     }
                  }

                  if (DebugOptions.instance.terrain.renderTiles.minusFloorCharacters.getValue() && !pzopt.ResumeShot.floorOnly) { // pzopt: resumeShot's floors capture: no walls, objects, items (characters are the moving-objects pass)
                     ProfileArea var63 = profiler.profile("Minus Floor Chars");

                     try {
                        if (DebugOptions.instance.fboRenderChunk.itemsInChunkTexture.getValue()) {
                           IsoGridSquare squareNW = c.getGridSquare(0, 0, level);
                           IsoGridSquare squareN = squareNW == null ? null : squareNW.getAdjacentSquare(IsoDirections.N);
                           if (squareNW != null && squareN != null) {
                              squareN.cacheLightInfo();
                              this.renderWorldInventoryObjects(squareN, squareNW, true);
                           }

                           IsoGridSquare squareW = squareNW == null ? null : squareNW.getAdjacentSquare(IsoDirections.W);
                           if (squareNW != null && squareW != null) {
                              squareW.cacheLightInfo();
                              this.renderWorldInventoryObjects(squareW, squareNW, true);
                           }
                        }

                        FBORenderTrees.current = FBORenderTrees.alloc();
                        FBORenderTrees.current.init();
                        IsoGridSquare squareNW = c.getGridSquare(0, 0, level);
                        IsoGridSquare squareN = squareNW == null ? null : squareNW.getAdjacentSquare(IsoDirections.N);
                        if (squareNW != null && squareN != null) {
                           squareN.cacheLightInfo();
                           this.renderMinusFloor(c, squareN);
                        }

                        IsoGridSquare squareW = squareNW == null ? null : squareNW.getAdjacentSquare(IsoDirections.W);
                        if (squareNW != null && squareW != null) {
                           squareW.cacheLightInfo();
                           this.renderMinusFloor(c, squareW);
                        }

                        for (int i = 0; i < IsoCell.MinusFloorCharacters.size(); i++) {
                           squareN = (IsoGridSquare)IsoCell.MinusFloorCharacters.get(i);
                           if (squareN.getLightInfo(playerIndex) != null) {
                              this.renderMinusFloor(c, squareN);
                              if (DebugOptions.instance.fboRenderChunk.itemsInChunkTexture.getValue()) {
                                 this.renderWorldInventoryObjects(squareN, squareN, true);
                              }

                              this.renderMinusFloorSE(squareN);
                           }
                        }

                        if (FBORenderTrees.current.trees.isEmpty()) {
                           FBORenderTrees.s_pool.release(FBORenderTrees.current);
                        } else {
                           SpriteRenderer.instance.drawGeneric(FBORenderTrees.current);
                        }
                     } catch (Throwable var51) {
                        if (var63 != null) {
                           try {
                              var63.close();
                           } catch (Throwable var37) {
                              var51.addSuppressed(var37);
                           }
                        }

                        throw var51;
                     }

                     if (var63 != null) {
                        var63.close();
                     }
                  }

                  if (PerformanceSettings.puddlesQuality == 2) {
                     ProfileArea var64 = profiler.profile("Low Puddles");

                     try {
                        this.renderPuddlesToChunkTexture(playerIndex, level, c);
                     } catch (Throwable var45) {
                        if (var64 != null) {
                           try {
                              var64.close();
                           } catch (Throwable var36) {
                              var45.addSuppressed(var36);
                           }
                        }

                        throw var45;
                     }

                     if (var64 != null) {
                        var64.close();
                     }
                  }

                  ProfileArea var65 = profiler.profile("Add Chunk");

                  try {
                     if (!renderLevels.getCachedSquares_AnimatedAttachments(level).isEmpty()) {
                        perPlayerData1.addChunkWith_AnimatedAttachments(c);
                     }

                     if (!renderLevels.getCachedSquares_TranslucentFloor(level).isEmpty()) {
                        perPlayerData1.addChunkWith_TranslucentFloor(c);
                     }

                     if (renderLevels.getCachedSquares_Items(level).size()
                           + renderLevels.getCachedSquares_TranslucentNonFloor(level).size()
                           + renderLevels.getCachedSquares_CutawayWindowFrames(level).size()
                        > 0) {
                        perPlayerData1.addChunkWith_TranslucentNonFloor(c);
                     }
                  } catch (Throwable var52) {
                     if (var65 != null) {
                        try {
                           var65.close();
                        } catch (Throwable var35) {
                           var52.addSuppressed(var35);
                        }
                     }

                     throw var52;
                  }

                  if (var65 != null) {
                     var65.close();
                  }
               } catch (Throwable var53) {
                  if (var17 != null) {
                     try {
                        var17.close();
                     } catch (Throwable var34) {
                        var53.addSuppressed(var34);
                     }
                  }

                  throw var53;
               }

               if (var17 != null) {
                  var17.close();
               }

               // pzopt: issue #5. Trees go in last, after every level of the texture, so the depth test settles them
               // against walls and roofs of all its levels; neighbours' trees that reach into this texture come too.
               if (pzoptTreePassActive() && FBORenderChunkManager.instance.renderChunk != null
                     && FBORenderChunkManager.instance.renderChunk.isTopLevel(level)) {
                  pzopt.GpuSections.begin("bake.trees"); // pzopt: GPU sub-section
                  this.pzoptBakeTrees(c, playerIndex, zoom);
                  pzopt.GpuSections.end("bake.trees"); // pzopt: GPU sub-section
               }
               if (pzopt.ChunkAo.enabled() && FBORenderChunkManager.instance.renderChunk != null && FBORenderChunkManager.instance.renderChunk.isTopLevel(level)) { // pzopt: ambient occlusion baked into the texture
                  pzopt.ChunkAo.bakeEnd(FBORenderChunkManager.instance.renderChunk, c, playerIndex, zoom, pzopt.ChunkAo.geometryDirty(renderLevels, level, zoom)); // pzopt
               } // pzopt
               pzopt.GpuSections.begin("bake.end"); // pzopt: GPU sub-section (unbind, mipmaps of the top level)
               FBORenderChunkManager.instance.endRenderChunkLevel(c, level, zoom, true); pzopt.PixelLight.bakeEnd(); pzopt.SpriteFilter.bakeEnd(); // pzopt: pixelLight; sprite filter, the finished texture gets its sharp level 1
               pzopt.GpuSections.end("bake.end"); // pzopt: GPU sub-section
               pzopt.GpuSections.end("bake"); // pzopt: GPU section
               return;
            }

            if (var17 != null) {
               var17.close();
            }
         } else {
            FBORenderChunkManager.instance.endRenderChunkLevel(c, level, zoom, false); pzopt.PixelLight.bakeEnd(); pzopt.SpriteFilter.bakeEnd(); // pzopt: pixelLight; sprite filter, the finished texture gets its sharp level 1
            if (!renderLevels.getCachedSquares_AnimatedAttachments(level).isEmpty()) {
               perPlayerData1.addChunkWith_AnimatedAttachments(c);
            }

            if (!renderLevels.getCachedSquares_TranslucentFloor(level).isEmpty()) {
               perPlayerData1.addChunkWith_TranslucentFloor(c);
            }

            if (renderLevels.getCachedSquares_Items(level).size()
                  + renderLevels.getCachedSquares_TranslucentNonFloor(level).size()
                  + renderLevels.getCachedSquares_CutawayWindowFrames(level).size()
               > 0) {
               perPlayerData1.addChunkWith_TranslucentNonFloor(c);
            }
         }
      }
   }

   private void calculateObjectRenderInfo(IsoGridSquare square) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      this.calculateObjectRenderInfo(playerIndex, square, square.getObjects());

      for (int i = 0; i < square.getStaticMovingObjects().size(); i++) {
         IsoMovingObject object = (IsoMovingObject)square.getStaticMovingObjects().get(i);
         if (object instanceof IsoDeadBody body) {
            ObjectRenderInfo renderInfo = object.getRenderInfo(playerIndex);
            renderInfo.layer = ObjectRenderLayer.Corpse;
            renderInfo.renderAlpha = 1.0F;
            renderInfo.cutaway = false;
            renderInfo.cutawayOutline = false;
         }
      }

      for (int i = 0; i < square.getWorldObjects().size(); i++) {
         IsoWorldInventoryObject object = (IsoWorldInventoryObject)square.getWorldObjects().get(i);
         ObjectRenderInfo renderInfo = object.getRenderInfo(playerIndex);
         renderInfo.layer = ObjectRenderLayer.WorldInventoryObject;
         renderInfo.renderAlpha = 1.0F;
         renderInfo.cutaway = false;
         renderInfo.cutawayOutline = false;
      }
   }

   private void calculateObjectRenderInfo(int playerIndex, IsoGridSquare square, PZArrayList<IsoObject> objectList) {
      IsoObject[] objects = (IsoObject[])objectList.getElements();
      int numObjects = objectList.size();

      for (int i = 0; i < numObjects; i++) {
         IsoObject object = objects[i];
         this.calculateObjectRenderInfo(playerIndex, square, object);
      }
   }

   private void calculateObjectRenderInfo(int playerIndex, IsoGridSquare square, IsoObject object) {
      ObjectRenderInfo renderInfo = object.getRenderInfo(playerIndex);
      renderInfo.layer = this.calculateObjectRenderLayer(object);
      renderInfo.targetAlpha = this.calculateObjectTargetAlpha(object);
      renderInfo.renderAlpha = 0.0F;
      renderInfo.cutawayOutline = false;
      renderInfo.cutaway = false;
      if (renderInfo.targetAlpha < 1.0F) {
         if (object instanceof IsoMannequin) {
            boolean var5 = true;
         } else if (renderInfo.layer == ObjectRenderLayer.MinusFloor) {
            renderInfo.layer = ObjectRenderLayer.Translucent;
         } else if (renderInfo.layer == ObjectRenderLayer.MinusFloorSE) {
            renderInfo.layer = ObjectRenderLayer.TranslucentSE;
         }
      }

      if ((renderInfo.layer == ObjectRenderLayer.Translucent || renderInfo.layer == ObjectRenderLayer.TranslucentSE)
         && square.getLightLevel(playerIndex) == 0.0F) {
         object.setAlpha(playerIndex, renderInfo.targetAlpha);
      }

      renderInfo.renderWidth = renderInfo.renderHeight = 0.0F;
   }

   private void setNotRendered(IsoGridSquare square) {
      if (square != null) {
         int playerIndex = IsoCamera.frameState.playerIndex;
         IsoObject[] objects = (IsoObject[])square.getObjects().getElements();
         int numObjects = square.getObjects().size();

         for (int i = 0; i < numObjects; i++) {
            IsoObject object = objects[i];
            ObjectRenderInfo renderInfo = object.getRenderInfo(playerIndex);
            renderInfo.layer = ObjectRenderLayer.None;
            renderInfo.targetAlpha = 0.0F;
         }
      }
   }

   private ObjectRenderLayer calculateObjectRenderLayer(IsoObject object) {
      if (!object.getDoRender()) {
         return ObjectRenderLayer.None;
      } else if (object instanceof IsoWorldInventoryObject) {
         return ObjectRenderLayer.WorldInventoryObject;
      } else if (this.isObjectRenderLayer_TranslucentFloor(object)) {
         return ObjectRenderLayer.TranslucentFloor;
      } else if (this.isObjectRenderLayer_Floor(object)) {
         return ObjectRenderLayer.Floor;
      } else if (this.isObjectRenderLayer_Vegetation(object)) {
         return ObjectRenderLayer.Vegetation;
      }

      ObjectRenderLayer pzoptCurtainLayer = this.pzoptCurtainLayer(object); // pzopt: issue #4
      if (pzoptCurtainLayer != null) {
         return pzoptCurtainLayer;
      }

      if (this.isObjectRenderLayer_MinusFloor(object)) {
         return ObjectRenderLayer.MinusFloor;
      } else if (this.isObjectRenderLayer_MinusFloorSE(object)) {
         return pzoptPerFrameTranslucentTile(object.getSprite()) ? ObjectRenderLayer.TranslucentSE : ObjectRenderLayer.MinusFloorSE;
      } else {
         return this.isObjectRenderLayer_Translucent(object) ? ObjectRenderLayer.Translucent : ObjectRenderLayer.None;
      }
   }

   /**
    * pzopt: a curtain hanging in front of the window (or door) on its own square draws in the same pass as that
    * window: baked when the window bakes, per frame when the window is per frame (issue #4). The window comes
    * before the curtain in the square's object list (IsoCurtain.getObjectAttachedTo searches backwards), so its
    * layer for this pass is already decided, including the fading / obscuring-player cases that send a baked window
    * per frame for a while. Stock draws both per frame in object order with depth writes off and never compares
    * their depths; a curtain in one pass and its window in the other depth-tests a per-frame sprite against the
    * composited chunk texture, where the two sample the 8-bit wall depth texture at slightly different positions
    * and the glass z-fights through the closed curtain. Returns null when the stock rules apply (both bake keys
    * off, curtainS / curtainE behind the next square's wall, sheet-door curtains, nothing attached).
    */
   private ObjectRenderLayer pzoptCurtainLayer(IsoObject object) {
      if (!(object instanceof IsoCurtain curtain) || !pzopt.Overrides.enabled()) {
         return null;
      }
      if (!pzopt.Config.WINDOWS_IN_CHUNK_TEXTURE && !pzopt.Config.TRANSLUCENT_TILES_IN_CHUNK_TEXTURE) {
         return null;
      }
      IsoObjectType type = curtain.getType();
      if (type != IsoObjectType.curtainN && type != IsoObjectType.curtainW || curtain.getSpriteModel() != null) {
         return null;
      }
      IsoObject attached = curtain.getObjectAttachedTo();
      if (attached == null || attached.square != object.square) {
         return null;
      }
      ObjectRenderLayer layer = attached.getRenderInfo(IsoCamera.frameState.playerIndex).layer;
      return layer == ObjectRenderLayer.MinusFloor || layer == ObjectRenderLayer.Translucent ? layer : null;
   }

   private boolean isObjectRenderLayer_Floor(IsoObject object) {
      IsoGridSquare square = object.square;
      if (square == null) {
         return false;
      }

      boolean bDoIt = true;
      if (object.sprite != null && !object.sprite.solidfloor && object.sprite.renderLayer != 1) {
         bDoIt = false;
      }

      if (object instanceof IsoFire || object instanceof IsoCarBatteryCharger) {
         bDoIt = false;
      }

      IsoWaterGeometry water = square.z == 0 ? square.getWater() : null;
      if (IsoWater.getInstance().getShaderEnable()
         && water != null
         && water.isValid()
         && object.sprite != null
         && object.sprite.properties.has(IsoFlagType.water)) {
         bDoIt = water.isbShore();
      }

      if (bDoIt && IsoWater.getInstance().getShaderEnable() && water != null && water.isValid() && !water.isbShore()) {
         IsoObject waterObj = square.getWaterObject();
         bDoIt = waterObj != null && waterObj.getObjectIndex() < object.getObjectIndex();
      }

      if (bDoIt && object.sprite != null && object.sprite.getProperties().getSlopedSurfaceDirection() != null) {
         return false;
      }

      int playerIndex = IsoCamera.frameState.playerIndex;
      return FBORenderCutaways.getInstance().shouldHideElevatedFloor(playerIndex, object) ? false : bDoIt;
   }

   private boolean isObjectRenderLayer_Vegetation(IsoObject object) {
      IsoGridSquare square = object.square;
      boolean bGrassEtc = object.sprite != null && (object.sprite.isBush || object.sprite.canBeRemoved || object.sprite.attachedFloor);
      return bGrassEtc && square.flattenGrassEtc;
   }

   private boolean isObjectRenderLayer_MinusFloor(IsoObject object) {
      IsoSprite sprite = object.getSprite();
      if (pzoptPerFrameTranslucentTile(sprite)) {
         return false;
      }

      if (object.isAnimating()) {
         return false;
      }

      if (Core.getInstance().getOptionDoWindSpriteEffects()) {
         if (object instanceof IsoTree) {
            return false;
         }

         if (object.getWindRenderEffects() != null) {
            return false;
         }
      } else {
         if (this.isTranslucentTree(object) || this.isTreeRenderedEveryFrame(object)) {
            return false;
         }

         if (object instanceof IsoTree && object.getObjectRenderEffects() != null) {
            return false;
         }
      }

      if (object.getObjectRenderEffectsToApply() != null) {
         return false;
      } else if (object instanceof IsoTree isoTree && isoTree.fadeAlpha < 1.0F) {
         return false;
      } else {
         IsoMannequin mannequin = (IsoMannequin)Type.tryCastTo(object, IsoMannequin.class);
         if (mannequin != null && mannequin.shouldRenderEachFrame()) {
            return false;
         }

         IsoGridSquare square = object.square;
         boolean bDoIt = true;
         IsoObjectType t = IsoObjectType.MAX;
         if (sprite != null) {
            t = sprite.getTileType();
         }

         if (sprite != null && (sprite.solidfloor || sprite.renderLayer == 1) && sprite.getProperties().getSlopedSurfaceDirection() == null) {
            bDoIt = false;
         }

         if (object instanceof IsoFire) {
            bDoIt = false;
         }

         int maxZ = 1000;
         if (square.z >= 1000 && (sprite == null || !sprite.alwaysDraw)) {
            bDoIt = false;
         }

         boolean bGrassEtc = sprite != null && (sprite.isBush || sprite.canBeRemoved || sprite.attachedFloor);
         if (bGrassEtc && square.flattenGrassEtc) {
            return false;
         }

         if (sprite != null
            && (t == IsoObjectType.WestRoofB || t == IsoObjectType.WestRoofM || t == IsoObjectType.WestRoofT)
            && square.z == 999
            && square.z == PZMath.fastfloor(IsoCamera.getCameraCharacterZ())) {
            bDoIt = false;
         }

         if (sprite != null && !sprite.solidfloor && IsoPlayer.getInstance().isClimbing()) {
            bDoIt = true;
         }

         if (square.isSpriteOnSouthOrEastWall(object)) {
            bDoIt = false;
         }

         // pzopt: with windowsInChunkTexture, windows and glass doors bake like walls; they are still drawn per
         // frame while fading / obscuring the player (the clause below), and the obscuring set invalidates their
         // chunk level (flag 8192) when it changes
         boolean pzoptBake = pzopt.Config.WINDOWS_IN_CHUNK_TEXTURE && pzopt.Overrides.enabled();
         boolean bTranslucent = !pzoptBake && object instanceof IsoWindow;
         IsoDoor door = (IsoDoor)Type.tryCastTo(object, IsoDoor.class);
         bTranslucent |= !pzoptBake && door != null && door.getProperties() != null && door.getProperties().has("doorTrans");
         int playerIndex = IsoCamera.frameState.playerIndex;
         FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
         bTranslucent |= sprite != null
            && (sprite.solid || sprite.solidTrans)
            && (object.getAlpha(playerIndex) < 1.0F && perPlayerData1.isFadingInSquare(square) || perPlayerData1.isSquareObscuringPlayer(square));
         if (bTranslucent) {
            bDoIt = false;
         }

         return bDoIt;
      }
   }

   private boolean isObjectRenderLayer_MinusFloorSE(IsoObject object) {
      IsoGridSquare square = object.square;
      boolean bDoIt = true;
      IsoObjectType t = IsoObjectType.MAX;
      if (object.sprite != null) {
         t = object.sprite.getTileType();
      }

      if (object.sprite != null && (object.sprite.solidfloor || object.sprite.renderLayer == 1)) {
         bDoIt = false;
      }

      if (object instanceof IsoFire) {
         bDoIt = false;
      }

      int maxZ = 1000;
      if (square.z >= 1000 && (object.sprite == null || !object.sprite.alwaysDraw)) {
         bDoIt = false;
      }

      boolean bGrassEtc = object.sprite != null && (object.sprite.isBush || object.sprite.canBeRemoved || object.sprite.attachedFloor);
      if (bGrassEtc) {
         return false;
      }

      if (object.sprite != null
         && (t == IsoObjectType.WestRoofB || t == IsoObjectType.WestRoofM || t == IsoObjectType.WestRoofT)
         && square.z == 999
         && square.z == PZMath.fastfloor(IsoCamera.getCameraCharacterZ())) {
         bDoIt = false;
      }

      if (object.sprite != null && !object.sprite.solidfloor && IsoPlayer.getInstance().isClimbing()) {
         bDoIt = true;
      }

      if (!square.isSpriteOnSouthOrEastWall(object)) {
         bDoIt = false;
      }

      boolean bTranslucent = object instanceof IsoWindow;
      IsoDoor door = (IsoDoor)Type.tryCastTo(object, IsoDoor.class);
      bTranslucent |= door != null && door.getProperties() != null && door.getProperties().has("doorTrans");
      if (bTranslucent) {
         bDoIt = false;
      }

      return bDoIt;
   }

   private boolean isObjectRenderLayer_TranslucentFloor(IsoObject object) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      if (FBORenderCutaways.getInstance().shouldHideElevatedFloor(playerIndex, object)) {
         return false;
      }

      boolean bTranslucent = object.getSprite() != null && object.getSprite().getProperties().has(IsoFlagType.transparentFloor);
      if (object.getSprite() != null
         && object.getSprite().solidfloor
         && IsoWater.getInstance().getShaderEnable()
         && DebugOptions.instance.terrain.renderTiles.isoGridSquare.shoreFade.getValue()) {
         IsoWaterGeometry water = object.square.z == 0 ? object.square.getWater() : null;
         boolean isShore = water != null && water.isbShore();
         if (isShore) {
            return true;
         }
      }

      return bTranslucent;
   }

   /** pzopt: tiles whose definition says Translucent (depthFlags bit 2) are drawn per frame unless translucentTilesInChunkTexture bakes them. */
   private static boolean pzoptPerFrameTranslucentTile(IsoSprite sprite) {
      return sprite != null && (sprite.depthFlags & 2) != 0 && !(pzopt.Config.TRANSLUCENT_TILES_IN_CHUNK_TEXTURE && pzopt.Overrides.enabled());
   }

   private boolean isObjectRenderLayer_Translucent(IsoObject object) {
      IsoSprite sprite = object.getSprite();
      if (pzoptPerFrameTranslucentTile(sprite)) {
         return true;
      }

      if (object instanceof IsoFire) {
         return true;
      }

      if (object.isAnimating()) {
         return true;
      }

      if (Core.getInstance().getOptionDoWindSpriteEffects()) {
         if (object instanceof IsoTree) {
            return true;
         }

         if (object.getWindRenderEffects() != null) {
            return true;
         }
      } else {
         if (this.isTranslucentTree(object) || this.isTreeRenderedEveryFrame(object)) {
            return true;
         }

         if (object instanceof IsoTree && object.getObjectRenderEffects() != null) {
            return true;
         }
      }

      if (object.getObjectRenderEffectsToApply() != null) {
         return true;
      } else if (object instanceof IsoTree isoTree && isoTree.fadeAlpha < 1.0F) {
         return true;
      } else {
         boolean bTranslucent = object instanceof IsoWindow;
         IsoDoor door = (IsoDoor)Type.tryCastTo(object, IsoDoor.class);
         bTranslucent |= door != null && door.getProperties() != null && door.getProperties().has("doorTrans");
         int playerIndex = IsoCamera.frameState.playerIndex;
         FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
         return bTranslucent
            | (
               sprite != null
                  && (sprite.solid || sprite.solidTrans)
                  && (
                     object.getAlpha(playerIndex) < 1.0F && perPlayerData1.isFadingInSquare(object.square)
                        || perPlayerData1.isSquareObscuringPlayer(object.square)
                  )
            );
      }
   }

   public boolean isTreeRenderedEveryFrame(IsoObject object) {
      // pzopt: with treesInChunkTexture a tree is only drawn per frame while it is translucent (player under it),
      // fading, or wind-animated; the rest bake into the chunk texture (checkTreeTranslucency handles the transitions)
      if (pzopt.Config.TREES_IN_CHUNK_TEXTURE && pzopt.Overrides.enabled() && !this.pzoptTreesPerFrameNow) {
         // a tree whose texture is still loading (the JUMBO packs arrive after the first bake at game load) draws
         // per frame until it is ready; checkTreeTranslucency then re-dirties its level so it bakes in
         return object instanceof IsoTree && !pzoptTreeTextureReady(object);
      }
      return object instanceof IsoTree;
   }

   // ---- pzopt: tree pass (Config.TREE_BAKE_PASS, pzopt.TreeBake, issue #5) ----------------------------------------

   /** Baked trees are drawn by {@link #pzoptBakeTrees} instead of the MinusFloor loop. */
   private boolean pzoptTreePassActive() {
      return pzopt.Config.TREES_IN_CHUNK_TEXTURE && pzopt.Config.TREE_BAKE_PASS && pzopt.Overrides.enabled()
         && !this.pzoptTreesPerFrameNow && !Core.getInstance().getOptionDoWindSpriteEffects();
   }

   /**
    * pzopt: treeBakeMaxChunksPerSec. While chunks stream in faster than this, new chunk textures are baked without
    * trees and the trees draw per frame (a texture that lives a second or two is not worth baking its trees into);
    * decided once per frame so a bake and the per-frame path never disagree within a frame. Textures already baked
    * keep their trees until they re-bake for their own reasons: a tree's layer is stored in its ObjectRenderInfo at
    * bake time, and the tree-copy fingerprints re-bake neighbours whose copies went stale, so a mode flip costs no
    * re-bake burst. Counter: pzoptTreesPerFrameFrames.
    */
   private boolean pzoptTreesPerFrameNow;
   private int pzoptTreeModeFrame = -1;
   static long pzoptTreesPerFrameFrames;

   private void pzoptUpdateTreeMode() {
      int frame = IsoWorld.instance.getFrameNo();
      if (frame == this.pzoptTreeModeFrame) {
         return;
      }
      this.pzoptTreeModeFrame = frame;
      int max = pzopt.Config.TREE_BAKE_MAX_CHUNKS_PER_SEC;
      this.pzoptTreesPerFrameNow = max > 0 && pzopt.Overrides.enabled() && pzopt.ChunkRate.perSecond() > max;
      if (this.pzoptTreesPerFrameNow) {
         pzoptTreesPerFrameFrames++;
      }
   }

   /** Would this tree bake right now (the tree cases of isObjectRenderLayer_MinusFloor and IsoTree.render)? */
   private boolean pzoptTreeBakes(IsoTree tree, boolean aiming) {
      if (tree.square == null || tree.square.z >= 1000 || tree.isHighlighted() || tree.isAnimating()) {
         return false;
      }
      if (tree.getObjectRenderEffects() != null || tree.getObjectRenderEffectsToApply() != null || tree.fadeAlpha < 1.0F) {
         return false;
      }
      return !this.isTreeRenderedEveryFrame(tree) && !this.pzoptIsTranslucentTree(tree, aiming);
   }

   private final pzopt.TreeBake.Rect pzoptTreeRect = new pzopt.TreeBake.Rect();
   private final pzopt.TreeBake.Rect pzoptTreeOwnTexture = new pzopt.TreeBake.Rect();
   private final pzopt.TreeBake.Rect pzoptTreeOtherTexture = new pzopt.TreeBake.Rect();

   /** The tree's full sprite rectangle in the logical space of chunk (wx, wy)'s texture with offsets goX / yoff. */
   private boolean pzoptTreeRect(IsoTree tree, int wx, int wy, float goX, float yoff, pzopt.TreeBake.Rect out) {
      IsoSprite sprite = tree.getSprite();
      if (sprite == null) {
         return false;
      }
      Texture texture = sprite.getTextureForCurrentFrame(tree.getForwardIsoDirection(), tree);
      if (texture == null) {
         return false;
      }
      int tileScale = Core.tileScale;
      float scale = pzopt.TreeBake.spriteScale(tileScale, texture.getWidthOrig(), texture.getHeightOrig());
      IsoGridSquare square = tree.square;
      pzopt.TreeBake.spriteRect(square.x, square.y, square.z, wx, wy, pzopt.TreeBake.offsetX(sprite.name, tileScale),
         pzopt.TreeBake.offsetY(sprite.name, tileScale), texture.getWidthOrig() * scale, texture.getHeightOrig() * scale, goX, yoff, tileScale, out);
      return true;
   }

   /** Texture-space y offset of the chunk-level texture for levels minLevel..topLevel (FBORenderChunkManager's yoff). */
   private static float pzoptTextureYOffset(int minLevel, int topLevel) {
      return (topLevel - minLevel + 1) * FBORenderChunk.PIXELS_PER_LEVEL + minLevel * FBORenderChunk.PIXELS_PER_LEVEL
         + FBORenderLevels.extraHeightForJumboTrees(minLevel, topLevel);
   }

   /**
    * The logical area of chunk {@code n}'s texture for the level group starting at {@code minLevel}, in the space of
    * the texture of chunk {@code c} (offsets goX / yoff), sized as FBORenderLevels allocates it. False when n has no
    * such level group.
    */
   private static boolean pzoptChunkTextureRect(IsoChunk n, IsoChunk c, int minLevel, float zoom, float goX, float yoff, pzopt.TreeBake.Rect out) {
      if (minLevel < 0 || minLevel < n.minLevel || minLevel > n.maxLevel) {
         return false;
      }
      int topLevel = Math.min(minLevel + 1, n.maxLevel);
      int scale = FBORenderLevels.getTextureScale(zoom);
      float w = (float)FBORenderLevels.calculateTextureWidthForLevels(minLevel, topLevel, zoom) / scale;
      float h = (float)FBORenderLevels.calculateTextureHeightForLevels(minLevel, topLevel, zoom) / scale;
      pzopt.TreeBake.neighbourTextureRect(n.wx - c.wx, n.wy - c.wy, goX, yoff, pzoptTextureYOffset(minLevel, topLevel), w, h, Core.tileScale, out);
      return true;
   }

   /** Re-bakes chunk n's texture for the level group at minLevel if it exists; a texture not yet baked pulls the trees itself. */
   private static void pzoptInvalidateTreeHolder(IsoChunk n, int minLevel, int playerIndex, float zoom) {
      FBORenderLevels renderLevels = n.getRenderLevels(playerIndex);
      if (renderLevels.getFBOForLevel(minLevel, zoom) != null) {
         renderLevels.invalidateLevel(minLevel, 4096L);
         pzopt.TreeBake.neighboursInvalidated++;
      }
   }

   /** Re-bakes every neighbour texture that holds a copy of the tree (its baked / per-frame state changed). */
   private void pzoptInvalidateTreeCopies(IsoTree tree, int playerIndex) {
      if (!pzoptTreePassActive() || tree.square == null || tree.square.chunk == null) {
         return;
      }
      IsoChunk c = tree.square.chunk;
      int minLevel = FBORenderLevels.calculateMinLevel(tree.square.z);
      float zoom = Core.getInstance().getZoom(playerIndex);
      pzopt.TreeBake.Rect sprite = this.pzoptTreeRect;
      pzopt.TreeBake.Rect own = this.pzoptTreeOwnTexture;
      pzopt.TreeBake.Rect other = this.pzoptTreeOtherTexture;
      // any frame works as long as every rectangle uses the same one: c's corner, no texture offsets
      float goX = 0.0F;
      float yoff = 0.0F;
      if (!pzoptChunkTextureRect(c, c, minLevel, zoom, goX, yoff, own) || !this.pzoptTreeRect(tree, c.wx, c.wy, goX, yoff, sprite)) {
         return;
      }
      for (int dwy = -2; dwy <= 2; dwy++) {
         for (int dwx = -2; dwx <= 2; dwx++) {
            if (dwx == 0 && dwy == 0) {
               continue;
            }
            IsoChunk n = this.cell.getChunk(c.wx + dwx, c.wy + dwy);
            if (n != null && pzoptChunkTextureRect(n, c, minLevel, zoom, goX, yoff, other) && pzopt.TreeBake.needsCopy(sprite, other, own)) {
               pzoptInvalidateTreeHolder(n, minLevel, playerIndex, zoom);
            }
         }
      }
   }

   /**
    * The tree pass of one chunk-level texture, run after its top level is baked: every baked tree of this chunk, and
    * every baked tree of the chunks up to two away whose sprite reaches into this texture beyond its own texture, is
    * drawn as a depth-tilted quad by pzopt.TreeBake in this texture's space and relative to this chunk's depth. Also
    * refreshes this chunk's export fingerprints: when the set of its trees needing a copy in a neighbour's texture
    * changed since its last bake (a tree chopped, grown, gone per-frame or back), that neighbour is re-baked so it
    * does not keep a stale copy.
    */
   /** pzopt: resumeShot's floor capture: this frame's bake counters, for its log line. */
   public String pzoptFrameBakeCounters() {
      return "bakes=" + this.pzoptBakesThisFrame + " creations=" + this.pzoptCreatesThisFrame + " creationsDeferred=" + this.pzoptCreatesDeferredThisFrame
         + " zoomDeferred=" + this.pzoptZoomDeferredThisFrame + " rebakes=" + this.pzoptRebakesThisFrame;
   }

   /** pzopt: resumeShot's floor capture turns occlusion culling off for its frame (FBORenderOcclusion.enabled is package-private). */
   public static boolean pzoptSetOcclusion(boolean on) {
      FBORenderOcclusion occlusion = FBORenderOcclusion.getInstance();
      boolean was = occlusion.enabled;
      occlusion.enabled = on;
      return was;
   }

   private void pzoptBakeTrees(IsoChunk c, int playerIndex, float zoom) {
      if (pzopt.ResumeShot.noTrees) {
         return; // pzopt: resumeShot's exit capture (floors, buildings): no trees
      }
      FBORenderChunk rc = FBORenderChunkManager.instance.renderChunk;
      int minLevel = rc.getMinLevel();
      int topLevel = rc.getTopLevel();
      if (minLevel < 0) {
         return;
      }
      pzopt.TreeBake.passes++;
      float scale = rc.highRes ? 2.0F : 1.0F;
      float goX = FBORenderChunkManager.instance.getXOffset();
      float yoff = FBORenderChunkManager.instance.getYOffset();
      boolean aiming = IsoPlayer.getPlayer(playerIndex).isAnyAimKeyDown();
      int camX = PZMath.fastfloor(IsoCamera.frameState.camCharacterX);
      int camY = PZMath.fastfloor(IsoCamera.frameState.camCharacterY);
      int tileScale = Core.tileScale;
      pzopt.TreeBake.Rect sprite = this.pzoptTreeRect;
      pzopt.TreeBake.Rect own = this.pzoptTreeOwnTexture;   // this texture
      pzopt.TreeBake.Rect other = this.pzoptTreeOtherTexture; // a neighbour's texture, in this texture's space
      pzopt.TreeBake.ownTextureRect(goX, rc.w / scale, rc.h / scale, own);
      pzopt.TreeBake.Drawer drawer = null;
      int[] exportFp = c.pzoptTreeExportFp;
      if (exportFp == null) {
         exportFp = c.pzoptTreeExportFp = new int[25];
      }
      int[] newFp = new int[25];
      IsoChunk[] slotChunk = new IsoChunk[25];
      for (int slot = 0; slot < 25; slot++) {
         slotChunk[slot] = slot == 12 ? c : this.cell.getChunk(c.wx + slot % 5 - 2, c.wy + slot / 5 - 2);
      }
      for (int slot = 0; slot < 25; slot++) {
         boolean ownChunk = slot == 12;
         IsoChunk n = slotChunk[slot];
         if (n == null || (!ownChunk && n.lightingNeverDone[playerIndex])) {
            continue;
         }
         // a neighbour's tree is copied only where it reaches beyond its own texture (most trees never do)
         if (!ownChunk && !pzoptChunkTextureRect(n, c, minLevel, zoom, goX, yoff, other)) {
            continue;
         }
         int z0 = Math.max(minLevel, n.minLevel);
         int z1 = Math.min(topLevel, n.maxLevel);
         for (int z = z0; z <= z1; z++) {
            IsoGridSquare[] squares = n.squares[n.squaresIndexOfLevel(z)];
            ChunkLevelData levelData = n.getCutawayDataForLevel(z);
            for (int i = 0; i < squares.length; i++) {
               IsoGridSquare square = squares[i];
               if (square == null || !square.has(IsoObjectType.tree)) {
                  continue;
               }
               IsoTree tree = square.getTree();
               if (tree == null || !this.pzoptTreeBakes(tree, aiming)) {
                  continue;
               }
               if (ownChunk && tree.getRenderInfo(playerIndex).layer != ObjectRenderLayer.MinusFloor) {
                  continue; // this bake's calculateObjectRenderInfo put it in a per-frame layer
               }
               if (!levelData.shouldRenderSquare(playerIndex, square) || FBORenderOcclusion.getInstance().isOccluded(square.x, square.y, square.z)
                     || FBORenderCutaways.getInstance().isForceRenderSquare(playerIndex, square)) {
                  continue;
               }
               if (!this.pzoptTreeRect(tree, c.wx, c.wy, goX, yoff, sprite)) {
                  continue;
               }
               if (ownChunk) {
                  // export fingerprints: which of this chunk's trees need a copy in each neighbour's texture
                  for (int e = 0; e < 25; e++) {
                     if (e != 12 && slotChunk[e] != null && pzoptChunkTextureRect(slotChunk[e], c, minLevel, zoom, goX, yoff, other)
                           && pzopt.TreeBake.needsCopy(sprite, other, own)) {
                        newFp[e] += System.identityHashCode(tree) * 31 + 1;
                     }
                  }
                  tree.renderFlag = false; // baked: what renderMinusFloor_NotDoorOrWall records for checkTreeTranslucency
                  if (!pzopt.TreeBake.overlaps(sprite, own)) {
                     continue;
                  }
               } else if (!pzopt.TreeBake.needsCopy(sprite, own, other)) {
                  continue;
               }
               if (!ownChunk) {
                  square.cacheLightInfo(); // as the stock bake does for the N / W neighbour squares it draws
               }
               if (square.getLightInfo(playerIndex) == null) {
                  continue;
               }
               // depth relative to this texture's chunk: the square's south corner, as the sprite path writes it
               float base = IsoDepthHelper.getSquareDepthData(camX, camY, square.x + 0.99F, square.y + 0.99F, z).depthStart;
               base -= IsoDepthHelper.getChunkDepthData(PZMath.fastfloor(camX / 8.0F), PZMath.fastfloor(camY / 8.0F), c.wx, c.wy, z).depthStart;
               if (base < 0.0F) {
                  continue; // a nearer chunk's tree: below this texture's depth range, its own and nearer textures hold it
               }
               ColorInfo light = this.sanitizeLightInfo(playerIndex, square);
               boolean unlit = tree.getSprite().getProperties().has(IsoFlagType.unlit) || pzopt.PixelLight.ACTIVE; // pzopt: pixelLight, trees bake unlit like the rest of the texture
               float cr = unlit ? 1.0F : light.r;
               float cg = unlit ? 1.0F : light.g;
               float cb = unlit ? 1.0F : light.b;
               if (drawer == null) {
                  drawer = pzopt.TreeBake.alloc();
               }
               IsoDirections dir = tree.getForwardIsoDirection();
               // sprite.x0 / y0 is the origin the sprite path would use; each texture adds its own trim offsets
               this.pzoptAddTreeTexture(drawer, tree.getSprite().getTextureForCurrentFrame(dir, tree), sprite.x0, sprite.y0, sprite.ground, base, cr, cg, cb, tileScale);
               if (tree.attachedAnimSprite != null) {
                  for (int k = 0; k < tree.attachedAnimSprite.size(); k++) {
                     IsoSpriteInstance inst = tree.attachedAnimSprite.get(k);
                     this.pzoptAddTreeTexture(drawer, inst.parentSprite.getTextureForCurrentFrame(dir, tree), sprite.x0, sprite.y0, sprite.ground, base, cr, cg, cb, tileScale);
                  }
               }
               pzopt.TreeBake.treesDrawn++;
               if (!ownChunk) {
                  pzopt.TreeBake.copiesDrawn++;
               }
            }
         }
      }
      for (int slot = 0; slot < 25; slot++) {
         if (newFp[slot] != exportFp[slot]) {
            int before = exportFp[slot];
            exportFp[slot] = newFp[slot];
            IsoChunk e = slotChunk[slot];
            if (e != null && e != c && minLevel >= e.minLevel && minLevel <= e.maxLevel) {
               // pzopt: treeAppend. A first export (no copy of this chunk's trees in that texture yet, the usual case:
               // a newly loaded chunk next to baked ones while driving) only adds quads on top of a finished texture,
               // so they are drawn into it after the bakes of this frame instead of re-baking the whole texture.
               if (before == 0 && pzopt.Overrides.enabled() && pzopt.Config.TREE_APPEND
                     && this.pzoptQueueTreeAppend(c, e, minLevel, topLevel, playerIndex, zoom, aiming, camX, camY, tileScale)) {
                  continue;
               }
               pzoptInvalidateTreeHolder(e, minLevel, playerIndex, zoom);
            }
         }
      }
      if (drawer != null) {
         SpriteRenderer.instance.drawGeneric(drawer);
      }
   }

   // pzopt: treeAppend. The neighbour textures that get this frame's newly exported trees drawn on top, flushed
   // before FBORenderChunkManager.endFrame composites them (each entry: the texture, its chunk, the quads).
   private final java.util.ArrayList<Object[]> pzoptTreeAppends = new java.util.ArrayList<>();

   /**
    * pzopt: treeAppend. Lists the trees of chunk {@code c} that reach into neighbour {@code e}'s finished texture
    * as quads in that texture's space, to be drawn into it later this frame. False when the texture must be
    * re-baked instead (none, dirty, off screen): the caller invalidates it as before.
    */
   private boolean pzoptQueueTreeAppend(IsoChunk c, IsoChunk e, int minLevel, int topLevel, int playerIndex, float zoom,
                                        boolean aiming, int camX, int camY, int tileScale) {
      FBORenderLevels levelsE = e.getRenderLevels(playerIndex);
      FBORenderChunk rcE = levelsE.getFBOForLevel(minLevel, zoom);
      if (rcE == null) {
         return true; // no texture: its first bake pulls the trees itself (the invalidation would do nothing either)
      }
      if (levelsE.isDirty(minLevel, zoom) || !levelsE.isOnScreen(minLevel) || rcE.tex == null || rcE.getMinLevel() != minLevel) {
         return false;
      }
      float scaleE = rcE.highRes ? 2.0F : 1.0F;
      float goX = rcE.w / 2.0F; // FBORenderChunkManager.beginRenderChunkLevel's xoff / yoff for this texture
      float yoff = (rcE.getTopLevel() - rcE.getMinLevel() + 1) * FBORenderChunk.PIXELS_PER_LEVEL
         + rcE.getMinLevel() * FBORenderChunk.PIXELS_PER_LEVEL + FBORenderLevels.extraHeightForJumboTrees(rcE.getMinLevel(), rcE.getTopLevel());
      pzopt.TreeBake.Rect sprite = this.pzoptTreeAppendSprite;
      pzopt.TreeBake.Rect ownE = this.pzoptTreeAppendOwn;
      pzopt.TreeBake.Rect otherC = this.pzoptTreeAppendOther;
      pzopt.TreeBake.ownTextureRect(goX, rcE.w / scaleE, rcE.h / scaleE, ownE);
      if (!pzoptChunkTextureRect(c, e, minLevel, zoom, goX, yoff, otherC)) {
         return true;
      }
      pzopt.TreeBake.Drawer drawer = null;
      int z0 = Math.max(minLevel, c.minLevel);
      int z1 = Math.min(rcE.getTopLevel(), c.maxLevel); // the levels that texture holds, as its own bake walks them
      for (int z = z0; z <= z1; z++) {
         IsoGridSquare[] squares = c.squares[c.squaresIndexOfLevel(z)];
         ChunkLevelData levelData = c.getCutawayDataForLevel(z);
         for (int i = 0; i < squares.length; i++) {
            IsoGridSquare square = squares[i];
            if (square == null || !square.has(IsoObjectType.tree)) {
               continue;
            }
            IsoTree tree = square.getTree();
            if (tree == null || !this.pzoptTreeBakes(tree, aiming) || tree.getRenderInfo(playerIndex).layer != ObjectRenderLayer.MinusFloor) {
               continue;
            }
            if (!levelData.shouldRenderSquare(playerIndex, square) || FBORenderOcclusion.getInstance().isOccluded(square.x, square.y, square.z)
                  || FBORenderCutaways.getInstance().isForceRenderSquare(playerIndex, square)) {
               continue;
            }
            // the same tree in the neighbour texture's space; drawn only where it reaches beyond its own texture
            if (!this.pzoptTreeRect(tree, e.wx, e.wy, goX, yoff, sprite) || !pzopt.TreeBake.needsCopy(sprite, ownE, otherC)) {
               continue;
            }
            if (square.getLightInfo(playerIndex) == null) {
               continue;
            }
            float base = IsoDepthHelper.getSquareDepthData(camX, camY, square.x + 0.99F, square.y + 0.99F, z).depthStart;
            base -= IsoDepthHelper.getChunkDepthData(PZMath.fastfloor(camX / 8.0F), PZMath.fastfloor(camY / 8.0F), e.wx, e.wy, z).depthStart;
            if (base < 0.0F) {
               continue;
            }
            ColorInfo light = this.sanitizeLightInfo(playerIndex, square);
            boolean unlit = tree.getSprite().getProperties().has(IsoFlagType.unlit) || pzopt.PixelLight.ACTIVE; // pzopt: pixelLight, trees bake unlit like the rest of the texture
            float cr = unlit ? 1.0F : light.r;
            float cg = unlit ? 1.0F : light.g;
            float cb = unlit ? 1.0F : light.b;
            if (drawer == null) {
               drawer = pzopt.TreeBake.alloc();
            }
            IsoDirections dir = tree.getForwardIsoDirection();
            this.pzoptAddTreeTexture(drawer, tree.getSprite().getTextureForCurrentFrame(dir, tree), sprite.x0, sprite.y0, sprite.ground, base, cr, cg, cb, tileScale);
            if (tree.attachedAnimSprite != null) {
               for (int k = 0; k < tree.attachedAnimSprite.size(); k++) {
                  IsoSpriteInstance inst = tree.attachedAnimSprite.get(k);
                  this.pzoptAddTreeTexture(drawer, inst.parentSprite.getTextureForCurrentFrame(dir, tree), sprite.x0, sprite.y0, sprite.ground, base, cr, cg, cb, tileScale);
               }
            }
         }
      }
      if (drawer == null) {
         return true; // nothing reaches that texture after all
      }
      drawer.expectTexture(rcE.tex);
      this.pzoptTreeAppends.add(new Object[] {rcE, e, drawer});
      pzopt.TreeBake.appendsQueued++;
      return true;
   }

   /**
    * pzopt: treeAppend. Draws the queued tree quads into their neighbour textures, each inside the same
    * begin / end sequence a bake uses (FlipY frame, FBORenderChunkStart without a clear, FBORenderChunkEnd)
    * so the render thread binds the texture with its depth and regenerates its mipmaps. A texture that is not
    * among this frame's composited ones is re-baked the old way instead.
    */
   private void pzoptFlushTreeAppends(int playerIndex, float zoom) {
      if (this.pzoptTreeAppends.isEmpty()) {
         return;
      }
      pzopt.AmbientOcclusion.changed(); // pzopt: ambient occlusion, trees drawn into finished textures
      for (int i = 0; i < this.pzoptTreeAppends.size(); i++) {
         Object[] entry = this.pzoptTreeAppends.get(i);
         FBORenderChunk rcE = (FBORenderChunk)entry[0];
         IsoChunk e = (IsoChunk)entry[1];
         pzopt.TreeBake.Drawer drawer = (pzopt.TreeBake.Drawer)entry[2];
         FBORenderLevels levelsE = e.getRenderLevels(playerIndex);
         if (!FBORenderChunkManager.instance.toRenderThisFrame.contains(rcE) || levelsE.getFBOForLevel(rcE.getMinLevel(), zoom) != rcE
               || levelsE.isDirty(rcE.getMinLevel(), zoom)) {
            drawer.postRender(); // back to the pool
            pzoptInvalidateTreeHolder(e, rcE.getMinLevel(), playerIndex, zoom);
            pzopt.TreeBake.appendsFellBack++;
            continue;
         }
         SpriteRenderer.instance.glDoEndFrame();
         SpriteRenderer.instance.glDoStartFrameFlipY(rcE.w, rcE.h, rcE.highRes ? 1.0F : 0.0F, playerIndex);
         rcE.beginMainThread(false);
         SpriteRenderer.instance.drawGeneric(drawer);
         rcE.endMainThread();
         SpriteRenderer.instance.glDoEndFrame();
         SpriteRenderer.instance.glDoStartFrame(Core.getInstance().getScreenWidth(), Core.getInstance().getScreenHeight(), Core.getInstance().getCurrentPlayerZoom(), playerIndex);
         pzopt.TreeBake.appendsDrawn++;
      }
      this.pzoptTreeAppends.clear();
   }

   private final pzopt.TreeBake.Rect pzoptTreeAppendSprite = new pzopt.TreeBake.Rect();
   private final pzopt.TreeBake.Rect pzoptTreeAppendOwn = new pzopt.TreeBake.Rect();
   private final pzopt.TreeBake.Rect pzoptTreeAppendOther = new pzopt.TreeBake.Rect();

   /** One texture of a tree (main sprite or foliage overlay) placed like IsoSprite.performRenderFrame would. */
   private void pzoptAddTreeTexture(pzopt.TreeBake.Drawer drawer, Texture texture, float sx, float sy, float ground, float base,
                                    float r, float g, float b, int tileScale) {
      if (texture == null || !texture.isReady() || texture.getTextureId() == null) {
         return;
      }
      float scale = pzopt.TreeBake.spriteScale(tileScale, texture.getWidthOrig(), texture.getHeightOrig());
      float x0 = sx + texture.getOffsetX() * scale;
      float y0 = sy + texture.getOffsetY() * scale;
      float x1 = x0 + texture.getWidth() * scale;
      float y1 = y0 + texture.getHeight() * scale;
      drawer.add(texture, x0, y0, x1, y1, pzopt.TreeBake.depthAtRow(base, ground, y0, tileScale),
         pzopt.TreeBake.depthAtRow(base, ground, y1, tileScale), r, g, b, 1.0F);
   }

   /** JUMBO trees do not come out of the chunk-texture tree batch (missing at game load, 2026-09-19); they stay per frame. */
   private static boolean pzoptIsJumboTree(IsoObject object) {
      IsoSprite sprite = object.getSprite();
      if (sprite == null) {
         return false;
      }
      zombie.core.textures.Texture texture = sprite.getTextureForCurrentFrame(object.getDir(), object);
      String name = texture == null ? sprite.name : texture.getName();
      return name != null && name.contains("JUMBO");
   }

   private static boolean pzoptTreeTextureReady(IsoObject object) {
      IsoSprite sprite = object.getSprite();
      if (sprite == null) {
         return true;
      }
      zombie.core.textures.Texture texture = sprite.getTextureForCurrentFrame(object.getDir(), object);
      return texture != null && texture.isReady();
   }

   private final java.util.HashSet<IsoTree> pzoptTreesAwaitingTexture = new java.util.HashSet<>();
   private static long pzoptTreesWaited, pzoptTreesArrived;

   public boolean isTranslucentTree(IsoObject object) {
      if (!(object instanceof IsoTree)) {
         return false;
      } else {
         int playerIndex = IsoCamera.frameState.playerIndex;
         boolean isAiming = IsoPlayer.getPlayer(playerIndex).isAnyAimKeyDown();
         return this.pzoptIsTranslucentTree(object, isAiming);
      }
   }

   /** pzopt: isTranslucentTree with the aim-key state supplied by the caller (checkTreeTranslucency reads it once per chunk). */
   private boolean pzoptIsTranslucentTree(IsoObject object, boolean isAiming) {
      {
         IsoGridSquare square = object.square;
         square.IsOnScreen();
         if (isAiming
            || square.x >= PZMath.fastfloor(IsoCamera.frameState.camCharacterX)
               && square.y >= PZMath.fastfloor(IsoCamera.frameState.camCharacterY)
               && IsoCamera.frameState.camCharacterSquare != null) {
            float sx = square.cachedScreenX - IsoCamera.frameState.offX;
            float sy = square.cachedScreenY - IsoCamera.frameState.offY;
            IsoCell cell = IsoWorld.instance.currentCell;
            return cell.isInStencil(sx, sy);
         } else {
            return false;
         }
      }
   }

   private float calculateObjectTargetAlpha(IsoObject object) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      ObjectRenderLayer renderLayer = object.getRenderInfo(playerIndex).layer;
      IsoObjectType t = IsoObjectType.MAX;
      if (object.sprite != null) {
         t = object.sprite.getTileType();
      }

      if (renderLayer != ObjectRenderLayer.MinusFloor
         && renderLayer != ObjectRenderLayer.MinusFloorSE
         && renderLayer != ObjectRenderLayer.Translucent
         && renderLayer != ObjectRenderLayer.TranslucentSE) {
         return 1.0F;
      }

      boolean isOpenDoor = object instanceof IsoDoor door && door.isOpen() || object instanceof IsoThumpable isoThumpable && isoThumpable.open;
      if (isOpenDoor && object.getProperties() != null && !object.getProperties().has(IsoPropertyType.GARAGE_DOOR)) {
         return 0.6F;
      }

      boolean isWestDoorOrWall = t == IsoObjectType.doorFrW || t == IsoObjectType.doorW || object.sprite != null && object.sprite.cutW;
      boolean isNorthDoorOrWall = t == IsoObjectType.doorFrN || t == IsoObjectType.doorN || object.sprite != null && object.sprite.cutN;
      return !isWestDoorOrWall && !isNorthDoorOrWall
         ? this.calculateObjectTargetAlpha_NotDoorOrWall(object)
         : this.calculateObjectTargetAlpha_DoorOrWall(object);
   }

   private float calculateObjectTargetAlpha_DoorOrWall(IsoObject object) {
      if (object.sprite == null) {
         return 1.0F;
      }

      if (object.sprite.cutW && object.sprite.cutN) {
         return 1.0F;
      }

      IsoObjectType t = object.sprite.getTileType();
      int playerIndex = IsoCamera.frameState.playerIndex;
      IsoGridSquare square = object.getSquare();
      IsoGridSquare squareS = square.getAdjacentSquare(IsoDirections.S);
      IsoGridSquare squareE = square.getAdjacentSquare(IsoDirections.E);
      if (object.isFascia() && this.shouldHideFascia(playerIndex, object)) {
         object.setAlphaAndTarget(playerIndex, 0.0F);
         return 0.0F;
      }

      int cutawaySelf = square.getPlayerCutawayFlag(playerIndex, this.currentTimeMillis);
      if (squareS == null) {
         int cutawayS = 0;
      } else {
         squareS.getPlayerCutawayFlag(playerIndex, this.currentTimeMillis);
      }

      if (squareE == null) {
         int cutawayE = 0;
      } else {
         squareE.getPlayerCutawayFlag(playerIndex, this.currentTimeMillis);
      }

      if (t == IsoObjectType.doorFrW || t == IsoObjectType.doorW || object.sprite.cutW) {
         IsoObjectType doorFrType = IsoObjectType.doorFrW;
         IsoObjectType doorType = IsoObjectType.doorW;
         boolean isDoor = t == doorFrType || t == doorType;
         boolean isWindow = object instanceof IsoWindow;
         boolean isCutaway = (cutawaySelf & 2) != 0;
         if ((isDoor || isWindow) && isCutaway) {
            if (isDoor && !this.hasSeenDoorW(playerIndex, square)) {
               return 0.0F;
            }

            if (isWindow && !this.hasSeenWindowW(playerIndex, square)) {
               return 0.0F;
            }

            return 0.4F;
         }
      } else if (t == IsoObjectType.doorFrN || t == IsoObjectType.doorN || object.sprite.cutN) {
         IsoObjectType doorFrType = IsoObjectType.doorFrN;
         IsoObjectType doorType = IsoObjectType.doorN;
         boolean isDoor = t == doorFrType || t == doorType;
         boolean isWindow = object instanceof IsoWindow;
         boolean isCutaway = (cutawaySelf & 1) != 0;
         if ((isDoor || isWindow) && isCutaway) {
            if (isDoor && !this.hasSeenDoorN(playerIndex, square)) {
               return 0.0F;
            }

            if (isWindow && !this.hasSeenWindowN(playerIndex, square)) {
               return 0.0F;
            }

            return 0.4F;
         }
      }

      return 1.0F;
   }

   private boolean hasSeenDoorW(int playerIndex, IsoGridSquare square) {
      boolean bCouldSee = true;
      boolean bHasSeenDoorW = false;
      IsoObject[] objectArray = (IsoObject[])square.getObjects().getElements();
      int numObjects = square.getObjects().size();

      for (int i = 0; i < numObjects; i++) {
         IsoObject obj = objectArray[i];
         IsoSprite sprite = obj.sprite;
         IsoObjectType type = sprite == null ? IsoObjectType.MAX : sprite.getTileType();
         if (type == IsoObjectType.doorFrW || type == IsoObjectType.doorW) {
            IsoGridSquare toWest = square.getAdjacentSquare(IsoDirections.W);
            bHasSeenDoorW |= true;
         }
      }

      return bHasSeenDoorW;
   }

   private boolean hasSeenDoorN(int playerIndex, IsoGridSquare square) {
      boolean bCouldSee = true;
      boolean bHasSeenDoorN = false;
      IsoObject[] objectArray = (IsoObject[])square.getObjects().getElements();
      int numObjects = square.getObjects().size();

      for (int i = 0; i < numObjects; i++) {
         IsoObject obj = objectArray[i];
         IsoSprite sprite = obj.sprite;
         IsoObjectType type = sprite == null ? IsoObjectType.MAX : sprite.getTileType();
         if (type == IsoObjectType.doorFrN || type == IsoObjectType.doorN) {
            IsoGridSquare toNorth = square.getAdjacentSquare(IsoDirections.N);
            bHasSeenDoorN |= true;
         }
      }

      return bHasSeenDoorN;
   }

   private boolean hasSeenWindowW(int playerIndex, IsoGridSquare square) {
      boolean bCouldSee = true;
      boolean bHasSeenWindowW = false;
      IsoObject[] objectArray = (IsoObject[])square.getObjects().getElements();
      int numObjects = square.getObjects().size();

      for (int i = 0; i < numObjects; i++) {
         IsoObject obj = objectArray[i];
         if (square.isWindowOrWindowFrame(obj, false)) {
            IsoGridSquare toWest = square.getAdjacentSquare(IsoDirections.W);
            bHasSeenWindowW |= true;
         }
      }

      return bHasSeenWindowW;
   }

   private boolean hasSeenWindowN(int playerIndex, IsoGridSquare square) {
      boolean bCouldSee = true;
      boolean bHasSeenWindowN = false;
      IsoObject[] objectArray = (IsoObject[])square.getObjects().getElements();
      int numObjects = square.getObjects().size();

      for (int i = 0; i < numObjects; i++) {
         IsoObject obj = objectArray[i];
         if (square.isWindowOrWindowFrame(obj, true)) {
            IsoGridSquare toNorth = square.getAdjacentSquare(IsoDirections.N);
            bHasSeenWindowN |= true;
         }
      }

      return bHasSeenWindowN;
   }

   private float calculateObjectTargetAlpha_NotDoorOrWall(IsoObject object) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      IsoGridSquare square = object.getSquare();
      IsoObjectType t = IsoObjectType.MAX;
      if (object.sprite != null) {
         t = object.sprite.getTileType();
      }

      if (object instanceof IsoCurtain curtain) {
         IsoObject attachedTo = curtain.getObjectAttachedTo();
         if (attachedTo != null && square.getTargetDarkMulti(playerIndex) <= attachedTo.getSquare().getTargetDarkMulti(playerIndex)) {
            return this.calculateObjectTargetAlpha_NotDoorOrWall(attachedTo);
         }
      }

      if (object instanceof IsoBarricade barricade) {
         BarricadeAble attachedTo = barricade.getBarricadedObject();
         if (attachedTo instanceof IsoObject isoObject && square.getTargetDarkMulti(playerIndex) <= attachedTo.getSquare().getTargetDarkMulti(playerIndex)) {
            return this.calculateObjectTargetAlpha_NotDoorOrWall(isoObject);
         }
      }

      int cutawaySelf = square.getPlayerCutawayFlag(playerIndex, this.currentTimeMillis);
      boolean bCutawayNorth = (cutawaySelf & 1) != 0;
      boolean bCutawayWest = (cutawaySelf & 2) != 0;
      if (object instanceof IsoWindowFrame windowFrame) {
         return this.calculateWindowTargetAlpha(playerIndex, object, windowFrame.getOppositeSquare(), windowFrame.getNorth());
      } else if (object instanceof IsoWindow window) {
         return this.calculateWindowTargetAlpha(playerIndex, object, window.getOppositeSquare(), window.getNorth());
      } else {
         boolean bIsRoof = t == IsoObjectType.WestRoofB || t == IsoObjectType.WestRoofM || t == IsoObjectType.WestRoofT;
         boolean bIsValidOverhang = bIsRoof && PZMath.fastfloor(IsoCamera.frameState.camCharacterZ) == square.getZ() && square.getBuilding() == null;
         if (bIsValidOverhang && FBORenderCutaways.getInstance().CanBuildingSquareOccludePlayer(square, playerIndex)) {
            return 0.05F;
         }

         if (object.isFascia() && this.shouldHideFascia(playerIndex, object)) {
            object.setAlphaAndTarget(playerIndex, 0.0F);
            return 0.0F;
         }

         if (IsoCamera.frameState.camCharacterSquare == null || IsoCamera.frameState.camCharacterSquare.getRoom() != square.getRoom()) {
            boolean bCutaway = false;
            if (square.has(IsoFlagType.cutN) && square.has(IsoFlagType.cutW)) {
               bCutaway = bCutawayNorth || bCutawayWest;
            } else if (square.has(IsoFlagType.cutW)) {
               bCutaway = bCutawayWest;
            } else if (square.has(IsoFlagType.cutN)) {
               bCutaway = bCutawayNorth;
            }

            if (bCutaway) {
               return square.isCanSee(playerIndex) ? 0.25F : 0.0F;
            }
         }

         if (object.isTableTopObject() && this.perPlayerData[playerIndex].isSquareObscuringPlayer(square)) {
            return 0.66F;
         } else if (!this.isPotentiallyObscuringObject(object) || !this.perPlayerData[playerIndex].isSquareObscuringPlayer(square)) {
            return 1.0F;
         } else if (object.sprite != null && object.sprite.getProperties().has(IsoFlagType.attachedCeiling)) {
            return 0.25F;
         } else {
            return object.isStairsObject() ? 0.5F : 0.66F;
         }
      }
   }

   public float calculateWindowTargetAlpha(int playerIndex, IsoObject object, IsoGridSquare oppositeSq, boolean bNorth) {
      IsoGridSquare square = object.getSquare();
      int cutawaySelf = square.getPlayerCutawayFlag(playerIndex, this.currentTimeMillis);
      boolean bCutawayNorth = (cutawaySelf & 1) != 0;
      boolean bCutawayWest = (cutawaySelf & 2) != 0;
      float targetAlpha = 1.0F;
      if (object.getTargetAlpha(playerIndex) < 1.0E-4F && oppositeSq != null && oppositeSq != square && oppositeSq.lighting[playerIndex].bSeen()) {
         targetAlpha = oppositeSq.lighting[playerIndex].darkMulti() * 2.0F;
      }

      if (targetAlpha > 0.75F && (bCutawayNorth && bNorth || bCutawayWest && !bNorth)) {
         float maxOpacity = 0.75F;
         float minOpacity = 0.1F;
         IsoPlayer player = IsoPlayer.players[playerIndex];
         if (player != null) {
            float maxFadeDistanceSquared = 25.0F;
            float distanceSquared = PZMath.min(IsoUtils.DistanceToSquared(player.getX(), player.getY(), square.x + 0.5F, square.y + 0.5F), 25.0F);
            float fadeAmount = PZMath.lerp(0.1F, 0.75F, 1.0F - distanceSquared / 25.0F);
            targetAlpha = Math.max(fadeAmount, 0.1F);
         } else {
            targetAlpha = 0.1F;
         }
      }

      return targetAlpha;
   }

   public void renderFloor(IsoGridSquare square) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      IsoObject[] objects = (IsoObject[])square.getObjects().getElements();
      int numObjects = square.getObjects().size();

      for (int i = 0; i < numObjects; i++) {
         IsoObject object = objects[i];
         ObjectRenderInfo renderInfo = object.getRenderInfo(playerIndex);
         if (renderInfo.layer == ObjectRenderLayer.Floor) {
            this.renderFloor(object);
         }
      }
   }

   public void renderFloor(IsoObject object) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      ObjectRenderInfo renderInfo = object.getRenderInfo(playerIndex);
      IsoGridSquare square = object.square;
      IndieGL.glAlphaFunc(516, 0.0F);
      object.setTargetAlpha(playerIndex, renderInfo.targetAlpha);
      object.setAlpha(playerIndex, renderInfo.targetAlpha);
      if (DebugOptions.instance.terrain.renderTiles.renderGridSquares.getValue()) {
         if (object.sprite != null) {
            IndieGL.glDepthMask(true);
            if (object.sprite.getProperties().getSlopedSurfaceDirection() != null && square.getPlayerCutawayFlag(playerIndex, this.currentTimeMillis) != 0) {
               IsoSprite sprite = IsoSpriteManager.instance.getSprite("ramps_01_23");
               this.defColorInfo.set(square.getLightInfo(playerIndex));
               if (DebugOptions.instance.fboRenderChunk.nolighting.getValue()) {
                  this.defColorInfo.set(1.0F, 1.0F, 1.0F, this.defColorInfo.a);
               }

               sprite.render(object, square.x, square.y, square.z, object.getDir(), object.offsetX, object.offsetY, this.defColorInfo, true);
            } else {
               FloorShaper attachedFloorShaper = FloorShaperAttachedSprites.instance;
               FloorShaper floorShaper;
               if (!object.getProperties().has(IsoFlagType.diamondFloor) && !object.getProperties().has(IsoFlagType.water)) {
                  floorShaper = FloorShaperDeDiamond.instance;
               } else {
                  floorShaper = FloorShaperDiamond.instance;
               }

               IsoWaterGeometry water = square.z == 0 ? square.getWater() : null;
               boolean isShore = water != null && water.isbShore() && IsoWater.getInstance().getShaderEnable();
               float depth0 = water == null ? 0.0F : water.depth[0];
               float depth1 = water == null ? 0.0F : water.depth[3];
               float depth2 = water == null ? 0.0F : water.depth[2];
               float depth3 = water == null ? 0.0F : water.depth[1];
               int col0 = square.getVertLight(0, playerIndex);
               int col1 = square.getVertLight(1, playerIndex);
               int col2 = square.getVertLight(2, playerIndex);
               int col3 = square.getVertLight(3, playerIndex);
               if (this.isBlackedOutBuildingSquare(square)) {
                  float fade = instance.getBlackedOutRoomFadeRatio(square);
                  col0 = Color.lerpABGR(col0, -16777216, fade);
                  col1 = Color.lerpABGR(col1, -16777216, fade);
                  col2 = Color.lerpABGR(col2, -16777216, fade);
                  col3 = Color.lerpABGR(col3, -16777216, fade);
               }

               if (DebugOptions.instance.terrain.renderTiles.isoGridSquare.floor.lightingDebug.getValue()) {
                  col0 = -65536;
                  col1 = -65536;
                  col2 = -16776961;
                  col3 = -16776961;
               }

               attachedFloorShaper.setShore(isShore);
               attachedFloorShaper.setWaterDepth(depth0, depth1, depth2, depth3);
               attachedFloorShaper.setVertColors(col0, col1, col2, col3);
               floorShaper.setShore(isShore);
               floorShaper.setWaterDepth(depth0, depth1, depth2, depth3);
               floorShaper.setVertColors(col0, col1, col2, col3);
               TileSeamModifier.instance.setShore(isShore);
               TileSeamModifier.instance.setWaterDepth(depth0, depth1, depth2, depth3);
               TileSeamModifier.instance.setVertColors(col0, col1, col2, col3);
               IsoGridSquare.setBlendFunc();
               Shader floorShader = null;
               IndieGL.StartShader(floorShader, playerIndex);
               this.defColorInfo.set(1.0F, 1.0F, 1.0F, 1.0F);
               object.renderFloorTile(square.x, square.y, square.z, this.defColorInfo, true, false, floorShader, floorShaper, attachedFloorShaper);
               IndieGL.EndShader();
            }
         }
      }
   }

   private void renderFishSplashes(int playerIndex, ArrayList<IsoGridSquare> squares) {
      IndieGL.glBlendFunc(770, 771);

      for (int i = 0; i < squares.size(); i++) {
         IsoGridSquare square = squares.get(i);
         ColorInfo lightInfo = square.getLightInfo(playerIndex);
         square.renderFishSplash(playerIndex, lightInfo);
      }
   }

   private void renderVegetation(IsoGridSquare square) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      IsoObject[] objects = (IsoObject[])square.getObjects().getElements();
      int numObjects = square.getObjects().size();

      for (int i = 0; i < numObjects; i++) {
         IsoObject object = objects[i];
         ObjectRenderInfo renderInfo = object.getRenderInfo(playerIndex);
         if (renderInfo.layer == ObjectRenderLayer.Vegetation) {
            this.renderVegetation(object);
         }
      }
   }

   private void renderVegetation(IsoObject object) {
      this.renderMinusFloor(object);
   }

   private void renderCorpsesInWorld(int playerIndex) {
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];

      for (int i = 0; i < perPlayerData1.onScreenChunks.size(); i++) {
         IsoChunk chunk = perPlayerData1.onScreenChunks.get(i);
         FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);

         for (int z = chunk.minLevel; z <= chunk.maxLevel; z++) {
            if (renderLevels.isOnScreen(z) && z == renderLevels.getMinLevel(z)) {
               List<IsoGridSquare> squares = renderLevels.getCachedSquares_Corpses(z);

               for (int j = 0; j < squares.size(); j++) {
                  IsoGridSquare square = squares.get(j);
                  this.renderCorpses(square, square, false);
               }
            }
         }
      }
   }

   private void renderCorpses(IsoGridSquare square, IsoGridSquare renderSquare, boolean bInChunkTexture) {
      if (this.shouldRenderSquare(renderSquare)) {
         int playerIndex = IsoCamera.frameState.playerIndex;
         ColorInfo lightInfo = square.getLightInfo(playerIndex);
         FBORenderLevels renderLevels = renderSquare.chunk.getRenderLevels(playerIndex);

         for (int i = 0; i < square.getStaticMovingObjects().size(); i++) {
            IsoMovingObject mov = (IsoMovingObject)square.getStaticMovingObjects().get(i);
            if ((mov.sprite != null || mov instanceof IsoDeadBody) && mov instanceof IsoDeadBody isoDeadBody) {
               if (bInChunkTexture) {
                  if (renderSquare == mov.getRenderSquare()) {
                     FBORenderChunk renderChunk = renderLevels.getFBOForLevel(square.z, Core.getInstance().getZoom(playerIndex));
                     FBORenderCorpses.getInstance().render(renderChunk.index, isoDeadBody);
                  }
               } else {
                  mov.render(mov.getX(), mov.getY(), mov.getZ(), lightInfo, true, false, null);
               }
            }
         }

         int size = square.getMovingObjects().size();

         for (int i = 0; i < size; i++) {
            IsoMovingObject mov = (IsoMovingObject)square.getMovingObjects().get(i);
            if (mov != null && mov.sprite != null) {
               boolean bOnFloor = mov.isOnFloor();
               if (bOnFloor && mov instanceof IsoZombie zombie) {
                  bOnFloor = zombie.isProne();
                  if (!BaseVehicle.renderToTexture) {
                     bOnFloor = false;
                  }
               }

               if (bOnFloor) {
                  mov.render(mov.getX(), mov.getY(), mov.getZ(), lightInfo, true, false, null);
               }
            }
         }
      }
   }

   private void renderItemsInWorld(int playerIndex) {
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];

      for (int i = 0; i < perPlayerData1.onScreenChunks.size(); i++) {
         IsoChunk chunk = perPlayerData1.onScreenChunks.get(i);
         FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);

         for (int z = chunk.minLevel; z <= chunk.maxLevel; z++) {
            if (renderLevels.isOnScreen(z) && z == renderLevels.getMinLevel(z)) {
               List<IsoGridSquare> squares = renderLevels.getCachedSquares_Items(z);

               for (int j = 0; j < squares.size(); j++) {
                  IsoGridSquare square = squares.get(j);
                  if (square.chunk == chunk) {
                     this.renderWorldInventoryObjects(square, square, false);
                  } else {
                     IsoGridSquare renderSquare = chunk.getGridSquare(0, 0, square.z);
                     this.renderWorldInventoryObjects(square, renderSquare, false);
                  }
               }
            }
         }
      }
   }

   private void renderMinusFloor(IsoChunk chunk, IsoGridSquare square) {
      this.renderMinusFloor(chunk, square, square.getObjects());
   }

   private void renderMinusFloor(IsoChunk chunk, IsoGridSquare square, PZArrayList<IsoObject> objectList) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      boolean bForceRender = FBORenderCutaways.getInstance().isForceRenderSquare(playerIndex, square);
      IsoObject[] objects = (IsoObject[])objectList.getElements();
      int numObjects = objectList.size();

      boolean pzoptTreePass = pzoptTreePassActive(); // pzopt: baked trees are drawn by pzoptBakeTrees at the end of the texture
      for (int i = 0; i < numObjects; i++) {
         IsoObject object = objects[i];
         ObjectRenderInfo renderInfo = object.getRenderInfo(playerIndex);
         if (renderInfo.layer == ObjectRenderLayer.MinusFloor) {
            if (pzoptTreePass && object instanceof IsoTree) {
               continue; // pzopt: issue #5
            }
            IsoGridSquare renderSquare = object.getRenderSquare();
            if (renderSquare != null && chunk == renderSquare.chunk) {
               if (bForceRender) {
                  IsoSpriteGrid spriteGrid = object.getSpriteGrid();
                  if (spriteGrid == null || spriteGrid.getLevels() == 1) {
                     continue;
                  }
               }

               this.renderMinusFloor(object);
            }
         }
      }
   }

   private void renderMinusFloor(IsoObject object) {
      if (pzopt.ResumeShot.floorOnly) {
         return; // pzopt: resumeShot's exit capture: floors only (no walls, doors, objects, trees)
      }
      if (pzopt.ResumeShot.noTrees && object instanceof IsoTree) {
         return; // pzopt: resumeShot's exit capture (buildings): no trees
      }
      if (object instanceof IsoTree && pzopt.Config.TREES_IN_CHUNK_TEXTURE && pzopt.Overrides.enabled() && pzopt.Config.TREE_BAKE_DIRECT) {
         // pzopt: bake the tree through the plain sprite path (IsoTree.render without a FBORenderTrees batch)
         FBORenderTrees batch = FBORenderTrees.current;
         FBORenderTrees.current = null;
         try {
            this.pzoptRenderMinusFloorInner(object);
         } finally {
            FBORenderTrees.current = batch;
         }
         return;
      }
      this.pzoptRenderMinusFloorInner(object);
   }

   private void pzoptRenderMinusFloorInner(IsoObject object) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      ObjectRenderInfo renderInfo = object.getRenderInfo(playerIndex);
      IsoObjectType t = IsoObjectType.MAX;
      if (object.sprite != null) {
         t = object.sprite.getTileType();
      }

      boolean isWestDoorOrWall = t == IsoObjectType.doorFrW || t == IsoObjectType.doorW || object.sprite != null && object.sprite.cutW;
      boolean isNorthDoorOrWall = t == IsoObjectType.doorFrN || t == IsoObjectType.doorN || object.sprite != null && object.sprite.cutN;
      IndieGL.glAlphaFunc(516, 0.0F);
      object.setAlphaAndTarget(playerIndex, renderInfo.targetAlpha);
      IsoGridSquare.setBlendFunc();
      if (object.sprite != null && (isWestDoorOrWall || isNorthDoorOrWall)) {
         if (DebugOptions.instance.terrain.renderTiles.isoGridSquare.doorsAndWalls.getValue()) {
            this.renderMinusFloor_DoorOrWall(object);
         }
      } else if (DebugOptions.instance.terrain.renderTiles.isoGridSquare.objects.getValue()) {
         this.renderMinusFloor_NotDoorOrWall(object);
      }
   }

   private void renderMinusFloor_DoorOrWall(IsoObject object) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      ObjectRenderInfo renderInfo = object.getRenderInfo(playerIndex);
      IsoGridSquare square = object.square;
      IsoGridSquare squareN = square.getAdjacentSquare(IsoDirections.N);
      IsoGridSquare squareS = square.getAdjacentSquare(IsoDirections.S);
      IsoGridSquare squareW = square.getAdjacentSquare(IsoDirections.W);
      IsoGridSquare squareE = square.getAdjacentSquare(IsoDirections.E);
      int cutawaySelf = square.getPlayerCutawayFlag(playerIndex, this.currentTimeMillis);
      int cutawayN = squareN == null ? 0 : squareN.getPlayerCutawayFlag(playerIndex, this.currentTimeMillis);
      int cutawayS = squareS == null ? 0 : squareS.getPlayerCutawayFlag(playerIndex, this.currentTimeMillis);
      int cutawayW = squareW == null ? 0 : squareW.getPlayerCutawayFlag(playerIndex, this.currentTimeMillis);
      int cutawayE = squareE == null ? 0 : squareE.getPlayerCutawayFlag(playerIndex, this.currentTimeMillis);
      IsoObjectType t = IsoObjectType.MAX;
      if (object.sprite != null) {
         t = object.sprite.getTileType();
      }

      IndieGL.glAlphaFunc(516, 0.0F);
      object.setAlphaAndTarget(playerIndex, renderInfo.targetAlpha);
      this.defColorInfo.set(1.0F, 1.0F, 1.0F, 1.0F);
      int stenciled = 0;
      Shader wallRenderShader = null;
      boolean bHasSeenDoorN = false;
      boolean bHasSeenDoorW = false;
      boolean bHasSeenWindowN = false;
      boolean bHasSeenWindowW = false;
      boolean bCouldSee = square.lighting[playerIndex].bCouldSee();
      lowestCutawayObjectN = null;
      lowestCutawayObjectW = null;
      IsoObject[] objects = (IsoObject[])square.getObjects().getElements();
      int numObjects = square.getObjects().size();

      for (int i = 0; i < numObjects; i++) {
         IsoObject obj = objects[i];
         IsoObjectType t2 = obj.sprite == null ? IsoObjectType.MAX : obj.sprite.getTileType();
         if (lowestCutawayObjectN == null && square.isWindowOrWindowFrame(obj, true) && (cutawaySelf & 1) != 0) {
            IsoGridSquare toNorth = square.getAdjacentSquare(IsoDirections.N);
            bHasSeenWindowN = bCouldSee || toNorth != null && toNorth.isCouldSee(playerIndex);
            lowestCutawayObjectN = obj;
         }

         if (lowestCutawayObjectW == null && square.isWindowOrWindowFrame(obj, false) && (cutawaySelf & 2) != 0) {
            IsoGridSquare toWest = square.getAdjacentSquare(IsoDirections.W);
            bHasSeenWindowW = bCouldSee || toWest != null && toWest.isCouldSee(playerIndex);
            lowestCutawayObjectW = obj;
         }

         if (lowestCutawayObjectN == null
            && obj.sprite != null
            && (t2 == IsoObjectType.doorFrN || t2 == IsoObjectType.doorN || obj.sprite.getProperties().has(IsoFlagType.DoorWallN))
            && (cutawaySelf & 1) != 0) {
            IsoGridSquare toNorth = square.getAdjacentSquare(IsoDirections.N);
            bHasSeenDoorN = bCouldSee || toNorth != null && toNorth.isCouldSee(playerIndex);
            lowestCutawayObjectN = obj;
         }

         if (lowestCutawayObjectW == null
            && obj.sprite != null
            && (t2 == IsoObjectType.doorFrW || t2 == IsoObjectType.doorW || obj.sprite.getProperties().has(IsoFlagType.DoorWallW))
            && (cutawaySelf & 2) != 0) {
            IsoGridSquare toWest = square.getAdjacentSquare(IsoDirections.W);
            bHasSeenDoorW = bCouldSee || toWest != null && toWest.isCouldSee(playerIndex);
            lowestCutawayObjectW = obj;
         }
      }

      IsoGridSquare.circleStencil = true;
      boolean bNeverCutaway = object.getProperties() != null && object.getProperties().has(IsoFlagType.NeverCutaway);
      if (bNeverCutaway) {
         IsoGridSquare.circleStencil = false;
      }

      IndieGL.glDepthMask(true);
      if (object.isWallSE()) {
         square.DoWallLightingW(object, 0, cutawaySelf, cutawayN, cutawayS, cutawayW, cutawayE, bHasSeenDoorW, bHasSeenWindowW, wallRenderShader);
      } else if (object.sprite.cutW && object.sprite.cutN) {
         square.DoWallLightingNW(
            object, 0, cutawaySelf, cutawayN, cutawayS, cutawayW, cutawayE, bHasSeenDoorN, bHasSeenDoorW, bHasSeenWindowN, bHasSeenWindowW, wallRenderShader
         );
      } else if (t == IsoObjectType.doorFrW || t == IsoObjectType.doorW || object.sprite.cutW) {
         square.DoWallLightingW(object, 0, cutawaySelf, cutawayN, cutawayS, cutawayW, cutawayE, bHasSeenDoorW, bHasSeenWindowW, wallRenderShader);
      } else if (t == IsoObjectType.doorFrN || t == IsoObjectType.doorN || object.sprite.cutN) {
         square.DoWallLightingN(object, 0, cutawaySelf, cutawayN, cutawayS, cutawayW, cutawayE, bHasSeenDoorN, bHasSeenWindowN, wallRenderShader);
      }

      if (renderInfo.cutawayOutline) {
         FBORenderLevels renderLevels = square.getChunk().getRenderLevels(playerIndex);
         if (!renderLevels.getCachedSquares_CutawayWindowFrames(square.getZ()).contains(square)) {
            renderLevels.getCachedSquares_CutawayWindowFrames(square.getZ()).add(square);
         }
      }
   }

   void renderMinusFloor_NotDoorOrWall(IsoObject object) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      ObjectRenderInfo renderInfo = object.getRenderInfo(playerIndex);
      IsoGridSquare square = object.square;
      IndieGL.glAlphaFunc(516, 0.0F);
      if (this.renderTranslucentOnly) {
         object.setTargetAlpha(playerIndex, renderInfo.targetAlpha);
         if (object.getType() == IsoObjectType.WestRoofT) {
            object.setAlphaAndTarget(playerIndex, renderInfo.targetAlpha);
         }
      } else {
         object.setAlphaAndTarget(playerIndex, renderInfo.targetAlpha);
      }

      ColorInfo lightInfo = this.sanitizeLightInfo(playerIndex, square);
      boolean bForceRender = FBORenderCutaways.getInstance().isForceRenderSquare(playerIndex, square);
      if (bForceRender) {
         IsoGridSquare below = this.cell.getGridSquare(square.x, square.y, square.z - 1);
         if (below != null) {
            lightInfo = this.sanitizeLightInfo(playerIndex, below);
         }
      }

      if (object instanceof IsoTree isoTree) {
         isoTree.renderFlag = this.isTranslucentTree(object);
      }

      IndieGL.glDepthMask(true);
      if (this.isRoofTileWithPossibleSeamSameLevel(square, object.sprite, IsoDirections.E)) {
         IsoGridSquare square2 = square.getAdjacentSquare(IsoDirections.E);
         this.renderJoinedRoofTile(playerIndex, object, square2, IsoDirections.E);
      }

      if (this.isRoofTileWithPossibleSeamSameLevel(square, object.sprite, IsoDirections.S)) {
         IsoGridSquare square2 = square.getAdjacentSquare(IsoDirections.S);
         this.renderJoinedRoofTile(playerIndex, object, square2, IsoDirections.S);
      }

      if (this.isRoofTileWithPossibleSeamBelow(square, object.sprite, IsoDirections.E)) {
         IsoGridSquare square2 = this.cell.getGridSquare(square.x + 1, square.y, square.z - 1);
         this.renderJoinedRoofTile(playerIndex, object, square2, IsoDirections.E);
      }

      if (this.isRoofTileWithPossibleSeamBelow(square, object.sprite, IsoDirections.S)) {
         IsoGridSquare square2 = this.cell.getGridSquare(square.x, square.y + 1, square.z - 1);
         this.renderJoinedRoofTile(playerIndex, object, square2, IsoDirections.S);
      }

      if (object instanceof IsoWindow window) {
         IsoGridSquare squareN = square.getAdjacentSquare(IsoDirections.N);
         IsoGridSquare squareS = square.getAdjacentSquare(IsoDirections.S);
         IsoGridSquare squareW = square.getAdjacentSquare(IsoDirections.W);
         IsoGridSquare squareE = square.getAdjacentSquare(IsoDirections.E);
         int cutawaySelf = square.getPlayerCutawayFlag(playerIndex, this.currentTimeMillis);
         int cutawayN = squareN == null ? 0 : squareN.getPlayerCutawayFlag(playerIndex, this.currentTimeMillis);
         int cutawayS = squareS == null ? 0 : squareS.getPlayerCutawayFlag(playerIndex, this.currentTimeMillis);
         int cutawayW = squareW == null ? 0 : squareW.getPlayerCutawayFlag(playerIndex, this.currentTimeMillis);
         int cutawayE = squareE == null ? 0 : squareE.getPlayerCutawayFlag(playerIndex, this.currentTimeMillis);
         int stenciled = 0;
         Shader wallRenderShader = null;
         boolean bHasSeenDoorN = false;
         boolean bHasSeenDoorW = false;
         boolean bHasSeenWindowN = false;
         boolean bHasSeenWindowW = false;
         if (window.getNorth() && object == square.getWall(true)) {
            IsoGridSquare.circleStencil = false;
            square.DoWallLightingN(object, 0, cutawaySelf, cutawayN, cutawayS, cutawayW, cutawayE, false, false, wallRenderShader);
            return;
         }

         if (!window.getNorth() && object == square.getWall(false)) {
            IsoGridSquare.circleStencil = false;
            square.DoWallLightingW(object, 0, cutawaySelf, cutawayN, cutawayS, cutawayW, cutawayE, false, false, wallRenderShader);
            return;
         }
      }

      if (!this.renderTranslucentOnly && object instanceof IsoCurtain curtain && pzoptCurtainDepthNudge(curtain) > 0.0F) { // pzopt: issue #4
         pzoptRenderCurtainNudged(curtain, square, lightInfo, pzoptCurtainDepthNudge(curtain));
         return;
      }

      object.render(square.x, square.y, square.z, lightInfo, true, false, null);
   }

   /**
    * pzopt: how far (tiles) a baked curtain draws nearer the camera than its tile geometry says, 0 for none. Stock
    * never depth-tests a curtain against its window: both are per-frame translucent objects drawn in object order
    * with depth writes off. Baked (windowsInChunkTexture, pzoptCurtainLayer) the two write depth into the chunk
    * texture under GL_LEQUAL and the depth textures decide: the north window glass and the north curtain share the
    * wall depth (or, for the tiles with geometry boxes, the glass leads by 0.017 tile), and the glass came out on
    * top of the closed curtain (issue #4). Only curtainN / curtainW hang on the camera side of their wall (same
    * square as the window); curtainS / curtainE are behind the wall of the next square and are meant to be seen
    * through the glass. Sheet-door curtains draw as 3D models at the door's CurtainOffset and are left alone. The
    * per-frame pass keeps stock's draw order (pzoptCurtainLayer puts the curtain in the same pass as its window).
    */
   private static float pzoptCurtainDepthNudge(IsoCurtain curtain) {
      if (pzopt.Config.CURTAIN_DEPTH_NUDGE <= 0.0F || !pzopt.Overrides.enabled()) {
         return 0.0F;
      }
      if (!pzopt.Config.WINDOWS_IN_CHUNK_TEXTURE && !pzopt.Config.TRANSLUCENT_TILES_IN_CHUNK_TEXTURE) {
         return 0.0F;
      }
      IsoObjectType type = curtain.getType();
      if (type != IsoObjectType.curtainN && type != IsoObjectType.curtainW) {
         return 0.0F;
      }
      return curtain.getSpriteModel() != null ? 0.0F : pzopt.Config.CURTAIN_DEPTH_NUDGE;
   }

   /**
    * pzopt: draws the curtain with its world position moved {@code nudge} tiles into the room (south for curtainN, east
    * for curtainW: smaller depth, see IsoDepthHelper.calculateDepth) and its sprite offset moved back by the same
    * screen distance, so the pixels land where they always did and only the depth the tile-depth shader writes
    * changes. Bake only: within one chunk texture both sprites sample the same depth texture at the same texels, so
    * the nudge is an exact margin; against the composited texture a per-frame sprite is not aligned that well.
    */
   private static void pzoptRenderCurtainNudged(IsoCurtain curtain, IsoGridSquare square, ColorInfo lightInfo, float nudge) {
      float dx = curtain.getType() == IsoObjectType.curtainW ? nudge : 0.0F;
      float dy = curtain.getType() == IsoObjectType.curtainN ? nudge : 0.0F;
      float offsetX = curtain.offsetX;
      float offsetY = curtain.offsetY;
      curtain.offsetX += IsoUtils.XToScreen(dx, dy, 0.0F, 0);
      curtain.offsetY += IsoUtils.YToScreen(dx, dy, 0.0F, 0);
      try {
         curtain.render(square.x + dx, square.y + dy, square.z, lightInfo, true, false, null);
      } finally {
         curtain.offsetX = offsetX;
         curtain.offsetY = offsetY;
      }
   }

   private boolean isRoofTileset(IsoSprite sprite) {
      return sprite == null ? false : sprite.getRoofProperties() != null;
   }

   private boolean isRoofTileWithPossibleSeamSameLevel(IsoGridSquare square, IsoSprite sprite, IsoDirections dir) {
      if (sprite == null) {
         return false;
      } else if (dir == IsoDirections.E && PZMath.coordmodulo(square.x, 8) != 7) {
         return false;
      } else if (dir == IsoDirections.S && PZMath.coordmodulo(square.y, 8) != 7) {
         return false;
      } else {
         return !this.isRoofTileset(sprite) ? false : sprite.getRoofProperties().hasPossibleSeamSameLevel(dir);
      }
   }

   private boolean isRoofTileWithPossibleSeamBelow(IsoGridSquare square, IsoSprite sprite, IsoDirections dir) {
      if (sprite == null) {
         return false;
      } else {
         return !this.isRoofTileset(sprite) ? false : sprite.getRoofProperties().hasPossibleSeamLevelBelow(dir);
      }
   }

   private boolean areRoofTilesJoinedSameLevel(IsoSprite sprite1, IsoSprite sprite2, IsoDirections dir) {
      if (!this.isRoofTileset(sprite1)) {
         return false;
      } else if (!this.isRoofTileset(sprite2)) {
         return false;
      } else if (dir == IsoDirections.E) {
         return sprite1.getRoofProperties().isJoinedSameLevelEast(sprite2.getRoofProperties());
      } else {
         return dir == IsoDirections.S ? sprite1.getRoofProperties().isJoinedSameLevelSouth(sprite2.getRoofProperties()) : false;
      }
   }

   private boolean areRoofTilesJoinedLevelBelow(IsoSprite sprite1, IsoSprite sprite2, IsoDirections dir) {
      if (!this.isRoofTileset(sprite1)) {
         return false;
      } else if (!this.isRoofTileset(sprite2)) {
         return false;
      } else if (dir == IsoDirections.E) {
         return sprite1.getRoofProperties().isJoinedLevelBelowEast(sprite2.getRoofProperties());
      } else {
         return dir == IsoDirections.S ? sprite1.getRoofProperties().isJoinedLevelBelowSouth(sprite2.getRoofProperties()) : false;
      }
   }

   private void renderJoinedRoofTile(int playerIndex, IsoObject object, IsoGridSquare square2, IsoDirections dir) {
      if (square2 != null) {
         IsoGridSquare square = object.getSquare();

         for (int i = 0; i < square2.getObjects().size(); i++) {
            IsoObject object2 = (IsoObject)square2.getObjects().get(i);
            if (square.z == square2.z
               ? this.areRoofTilesJoinedSameLevel(object.sprite, object2.sprite, dir)
               : this.areRoofTilesJoinedLevelBelow(object.sprite, object2.sprite, dir)) {
               ObjectRenderInfo renderInfo2 = object2.getRenderInfo(playerIndex);
               if (renderInfo2.targetAlpha == 0.0F || square.chunk != square2.chunk) {
                  float renderWidth = renderInfo2.renderWidth;
                  float renderHeight = renderInfo2.renderHeight;
                  this.calculateObjectRenderInfo(playerIndex, object2.square, object2);
                  renderInfo2.renderWidth = renderWidth;
                  renderInfo2.renderHeight = renderHeight;
                  if (object2.getRenderInfo(playerIndex).targetAlpha == 0.0F) {
                     continue;
                  }
               }

               if (!(object2.getRenderInfo(playerIndex).targetAlpha < 1.0F)) {
                  object2.renderSquareOverride = square;
                  object2.renderDepthAdjust = -1.0E-5F;
                  object2.sx = 0.0F;
                  if (this.renderTranslucentOnly) {
                     object2.setTargetAlpha(playerIndex, object2.getRenderInfo(playerIndex).targetAlpha);
                  } else {
                     object2.setAlphaAndTarget(playerIndex, object2.getRenderInfo(playerIndex).targetAlpha);
                  }

                  object2.render(square2.x, square2.y, square2.z, this.sanitizeLightInfo(playerIndex, square2), true, false, null);
                  object2.sx = 0.0F;
                  object2.renderSquareOverride = null;
                  object2.renderDepthAdjust = 0.0F;
                  break;
               }
            }
         }
      }
   }

   private void renderWorldInventoryObjects(IsoGridSquare square, IsoGridSquare renderSquare, boolean bChunkTexture) {
      if (this.shouldRenderSquare(renderSquare)) {
         this.tempWorldInventoryObjects.clear();
         PZArrayUtil.addAll(this.tempWorldInventoryObjects, square.getWorldObjects());
         this.timSort.doSort(this.tempWorldInventoryObjects.getElements(), (java.util.Comparator<IsoWorldInventoryObject>) (o1, o2) -> { // pzopt: decompiler fix (explicit lambda types)
            float d1 = o1.xoff * o1.xoff + o1.yoff * o1.yoff;
            float d2 = o2.xoff * o2.xoff + o2.yoff * o2.yoff;
            if (d1 == d2) {
               return 0;
            } else {
               return d1 > d2 ? 1 : -1;
            }
         }, 0, this.tempWorldInventoryObjects.size());
         int playerIndex = IsoCamera.frameState.playerIndex;

         for (int i = 0; i < this.tempWorldInventoryObjects.size(); i++) {
            IsoWorldInventoryObject object = (IsoWorldInventoryObject)this.tempWorldInventoryObjects.get(i);
            ObjectRenderInfo renderInfo = object.getRenderInfo(playerIndex);
            if (renderInfo.layer == ObjectRenderLayer.WorldInventoryObject && (!bChunkTexture || renderSquare == object.getRenderSquare())) {
               this.renderWorldInventoryObject(object, bChunkTexture);
            }
         }
      }
   }

   private void renderWorldInventoryObject(IsoWorldInventoryObject worldObj, boolean bChunkTexture) {
      IsoGridSquare square = worldObj.getSquare();
      int playerIndex = IsoCamera.frameState.playerIndex;
      if (bChunkTexture) {
         IsoGridSquare renderSquare = worldObj.getRenderSquare();
         if (!(worldObj.zoff < 0.01F)
            && (this.isTableTopObjectFadedOut(playerIndex, square) || this.isTableTopObjectSquareCutaway(playerIndex, square, worldObj.zoff))) {
            FBORenderLevels renderLevels = renderSquare.chunk.getRenderLevels(playerIndex);
            List<IsoGridSquare> squares = renderLevels.getCachedSquares_Items(renderSquare.z);
            if (!squares.contains(square)) {
               squares.add(square);
            }

            return;
         }

         if (!worldObj.getItem().getScriptItem().isWorldRender()) {
            return;
         }

         if (Core.getInstance().isOption3DGroundItem() && ItemModelRenderer.itemHasModel(worldObj.getItem())) {
            FBORenderLevels renderLevels = renderSquare.chunk.getRenderLevels(playerIndex);
            FBORenderChunk renderChunk = renderLevels.getFBOForLevel(renderSquare.z, Core.getInstance().getZoom(playerIndex));
            FBORenderItems.getInstance().render(renderChunk.index, worldObj);
            return;
         }
      }

      if (this.renderTranslucentOnly) {
         if (worldObj.zoff < 0.01F) {
            return;
         }

         if (!this.isTableTopObjectFadedOut(playerIndex, square) && !this.isTableTopObjectSquareCutaway(playerIndex, square, worldObj.zoff)) {
            return;
         }
      }

      ColorInfo lightInfo = square.getLightInfo(playerIndex);
      worldObj.render(square.x, square.y, square.z, lightInfo, true, false, null);
   }

   private boolean isTableTopObjectSquareCutaway(int playerIndex, IsoGridSquare square, float zoff) {
      IsoGridSquare squareS = square.getAdjacentSquare(IsoDirections.S);
      IsoGridSquare squareE = square.getAdjacentSquare(IsoDirections.E);
      boolean cutawaySelf = square.getPlayerCutawayFlag(playerIndex, this.currentTimeMillis) != 0;
      boolean cutawayS = squareS != null && squareS.getPlayerCutawayFlag(playerIndex, this.currentTimeMillis) != 0;
      boolean cutawayE = squareE != null && squareE.getPlayerCutawayFlag(playerIndex, this.currentTimeMillis) != 0;
      if (IsoCamera.frameState.camCharacterSquare == null || IsoCamera.frameState.camCharacterSquare.getRoom() != square.getRoom()) {
         boolean bCutaway = cutawaySelf;
         if (square.has(IsoFlagType.cutN) && square.has(IsoFlagType.cutW)) {
            bCutaway |= cutawayS | cutawayE;
         } else if (square.has(IsoFlagType.cutW)) {
            bCutaway |= cutawayS;
         } else if (square.has(IsoFlagType.cutN)) {
            bCutaway |= cutawayE;
         }

         if (bCutaway) {
            return true;
         }
      }

      return false;
   }

   private boolean isTableTopObjectFadedOut(int playerIndex, IsoGridSquare square) {
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      return this.listContainsLocation(perPlayerData1.squaresObscuringPlayer, square.x, square.y, square.z)
         || this.listContainsLocation(perPlayerData1.fadingInSquares, square.x, square.y, square.z);
   }

   private void renderMinusFloorSE(IsoGridSquare square) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      IsoObject[] objects = (IsoObject[])square.getObjects().getElements();
      int numObjects = square.getObjects().size();

      for (int i = 0; i < numObjects; i++) {
         IsoObject object = objects[i];
         ObjectRenderInfo renderInfo = object.getRenderInfo(playerIndex);
         if (renderInfo.layer == ObjectRenderLayer.MinusFloorSE) {
            this.renderMinusFloorSE(object);
         }
      }
   }

   private void renderMinusFloorSE(IsoObject object) {
      this.renderMinusFloor(object);
   }

   private void renderTranslucentFloor(IsoGridSquare square) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      IsoObject[] objects = (IsoObject[])square.getObjects().getElements();
      int numObjects = square.getObjects().size();

      for (int i = 0; i < numObjects; i++) {
         IsoObject object = objects[i];
         ObjectRenderInfo renderInfo = object.getRenderInfo(playerIndex);
         if (renderInfo.layer == ObjectRenderLayer.TranslucentFloor) {
            this.renderTranslucent(object);
         }
      }
   }

   private void renderTranslucent(IsoGridSquare square) {
      if (pzopt.Config.INSTRUMENT) pzoptTl[7]++;
      this.renderTranslucent(square, ObjectRenderLayer.Translucent);
   }

   private void renderTranslucentSE(IsoGridSquare square) {
      this.renderTranslucent(square, ObjectRenderLayer.TranslucentSE);
   }

   private void renderTranslucent(IsoGridSquare square, ObjectRenderLayer renderLayer) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      IsoObject[] objects = (IsoObject[])square.getObjects().getElements();
      int numObjects = square.getObjects().size();

      for (int i = 0; i < numObjects; i++) {
         IsoObject object = objects[i];
         ObjectRenderInfo renderInfo = object.getRenderInfo(playerIndex);
         if (renderInfo.layer == renderLayer) {
            if (!(object instanceof IsoTree) && !FBORenderTrees.current.trees.isEmpty()) {
               SpriteRenderer.instance.drawGeneric(FBORenderTrees.current);
               FBORenderTrees.current = FBORenderTrees.alloc();
               FBORenderTrees.current.init();
            }

            this.renderTranslucent(object);
         }
      }
   }

   /**
    * pzopt: stock FBORenderLevels.NLevels.invalidate() also empties the level's per-frame square lists (items on
    * tables, obscuring furniture, cutaway window frames, corpses, animated attachments, flies, puddles, translucent
    * floor) whenever a level is invalidated outside performRenderTiles, because the bake that follows in the same
    * frame rebuilds them. With a held re-bake (Config.LIGHTING_REBAKE_MS, REBAKE_BUDGET) the previous texture stays
    * on screen but those lists were already cleared, so for the held frames nothing per-frame was drawn: objects on
    * tables, doors, windows and corpses blinked out for 1-3 frames (2026-09-20, runs flick-*). The lists only ever
    * change at a bake (clearCachedSquares(level) at its start), so keeping them across invalidations keeps them
    * matched to whatever texture is on screen. Stock's own flow is unchanged: every bake rebuilds them anyway.
    */
   private static boolean pzoptKeepPerFrameLists() {
      return pzopt.Overrides.enabled() && (pzopt.Config.LIGHTING_REBAKE_MS > 0 || pzopt.Config.REBAKE_BUDGET > 0 || pzopt.Config.BAKE_BUDGET > 0);
   }

   /**
    * pzopt: the per-frame square lists belong to a group of two levels (FBORenderLevels.NLevels) and stock clears
    * them only when the group's lower level is rebuilt. With the lists kept across invalidations, an upper level
    * rebuilt without its lower level (a held or budgeted lower level, a zoom plan step) appended its squares a
    * second time: 84 puddle squares on one 64-square level overflowed PuddleVbo and the thrown exception skipped
    * the rest of the world pass (water, splashes, translucent objects, fog) on those frames (run uiz-storm240,
    * 2026-09-24, the Workshop "weather layer / fog flashing" reports). Drop the level's own entries first, so its
    * rebuild replaces them and the other level's stay.
    */
   private static void pzoptDropLevelSquares(FBORenderLevels renderLevels, int level) {
      pzoptDupSquaresDropped += pzoptDropLevel(renderLevels.getCachedSquares_AnimatedAttachments(level), level)
         + pzoptDropLevel(renderLevels.getCachedSquares_Corpses(level), level)
         + pzoptDropLevel(renderLevels.getCachedSquares_CutawayWindowFrames(level), level)
         + pzoptDropLevel(renderLevels.getCachedSquares_Flies(level), level)
         + pzoptDropLevel(renderLevels.getCachedSquares_Items(level), level)
         + pzoptDropLevel(renderLevels.getCachedSquares_Puddles(level), level)
         + pzoptDropLevel(renderLevels.getCachedSquares_TranslucentFloor(level), level)
         + pzoptDropLevel(renderLevels.getCachedSquares_TranslucentNonFloor(level), level)
         + pzoptDropLevel(renderLevels.getCachedSquares_Water(level), level)
         + pzoptDropLevel(renderLevels.getCachedSquares_WaterShore(level), level)
         + pzoptDropLevel(renderLevels.getCachedSquares_WaterAttach(level), level);
   }

   private static int pzoptDropLevel(List<IsoGridSquare> squares, int level) {
      int n = squares.size();
      int kept = 0;
      for (int i = 0; i < n; i++) {
         IsoGridSquare square = squares.get(i);
         if (square.getZ() != level) {
            squares.set(kept++, square);
         }
      }
      for (int i = n - 1; i >= kept; i--) {
         squares.remove(i);
      }
      return n - kept;
   }

   private static long pzoptDupSquaresDropped; // pzopt: entries pzoptDropLevelSquares removed (bake counters)

   // pzopt: bake budget — chunk-level textures (re)baked per frame; the rest keep their previous texture for a frame
   private int pzoptBakesThisFrame;
   private int pzoptRebakesThisFrame; // pzopt: re-bake budget (Config.REBAKE_BUDGET)
   private int pzoptStrongThisFrame; // pzopt: strong re-bakes granted this frame (Config.LIGHTING_STRONG_BUDGET)
   private int pzoptCreatesThisFrame; // pzopt: never-baked levels baked this frame (Config.BAKE_BUDGET counts only these)
   private int pzoptCreatesDeferredThisFrame; // pzopt: never-baked levels left black this frame
   private int pzoptCreatesDeferredLastFrame; // pzopt: ... and last frame: > 0 holds the optional re-bakes (creation first)
   private static long pzoptCreatesStarved; // pzopt: counter for the log line

   /**
    * pzopt: whether this level's strong lighting change (pzopt.LightDirt) re-bakes now. Granted while the frame's strong
    * budget lasts; past it the level is held like weak drift (it re-bakes within lightingRebakeMs or the spread anyway).
    */
   private int pzoptStrongBudgetNow = -1; // pzopt: lightingStrongFrameMs, the adaptive strong budget of this frame
   private int pzoptStrongBudgetFrame = -1;

   /**
    * pzopt: the frame's strong re-bake budget. With lightingStrongFrameMs > 0 it follows the last game-thread frame step
    * (pzopt.FrameCap.lastStepNs, the limiter's wait excluded): over the threshold it halves (down to 1), under 3/4 of it
    * it grows by one, up to lightingStrongBudget. The out-of-sight fade (darkMulti) moves per unit of game time, so a slow
    * frame moves every exterior square further, marks more levels strong, bakes more textures (GPU work) and slows the
    * next frame: at ~30 fps in downtown Louisville the loop held ~30 % of the runs of 2026-09-22 at twice the GPU time
    * per frame. Held strong levels still re-bake within lightingRebakeMs or the spread.
    */
   private int pzoptStrongBudget() {
      int budget = pzopt.Config.LIGHTING_STRONG_BUDGET;
      float thresholdMs = pzopt.Config.LIGHTING_STRONG_FRAME_MS;
      if (budget <= 0 || thresholdMs <= 0.0F) {
         return budget;
      }
      int frame = IsoWorld.instance.getFrameNo();
      if (frame != this.pzoptStrongBudgetFrame) {
         this.pzoptStrongBudgetFrame = frame;
         if (this.pzoptStrongBudgetNow < 0) {
            this.pzoptStrongBudgetNow = budget;
         }
         long lastStepNs = pzopt.FrameCap.lastStepNs;
         if (lastStepNs > 0L) {
            float lastMs = lastStepNs / 1e6F;
            if (lastMs > thresholdMs) {
               this.pzoptStrongBudgetNow = Math.max(1, this.pzoptStrongBudgetNow / 2);
               pzoptStrongBudgetCuts++;
            } else if (lastMs < thresholdMs * 0.75F) {
               this.pzoptStrongBudgetNow = Math.min(budget, this.pzoptStrongBudgetNow + 1);
            }
         }
      }
      return this.pzoptStrongBudgetNow;
   }

   private static long pzoptStrongBudgetCuts; // pzopt: frames that halved the strong budget (log)

   // pzopt: compositeShaderRun (2026-09-25, the Rosewood drive). FBORenderChunkManager.endFrame's non-combined path, with one
   // difference: FBORenderChunk.renderInWorldMainThread ends the chunk shader after every chunk-level texture, so the render
   // thread switched chunk shader -> default -> chunk shader ~350 times a frame (DrawStats: program 0 was the most started
   // program) with nothing drawn in between; here the shader ends once after the last chunk. Each chunk still starts the
   // chunk shader with its own depth texture and chunk depth, draws its quad and sets the same state, in the same order.
   private void pzoptCompositeChunks() {
      FBORenderChunkManager m = FBORenderChunkManager.instance;
      if (!m.toRenderThisFrame.isEmpty()) {
         int playerIndex = IsoCamera.frameState.playerIndex;
         int offscreenWidth = Core.getInstance().getOffscreenWidth(playerIndex);
         int offscreenHeight = Core.getInstance().getOffscreenHeight(playerIndex);
         SpriteRenderer.instance.glDoEndFrame();
         SpriteRenderer.instance.glDoStartFrameNoZoom(offscreenWidth, offscreenHeight, Core.getInstance().getCurrentPlayerZoom(), playerIndex);
         boolean started = false;
         for (int i = 0; i < m.toRenderThisFrame.size(); i++) {
            FBORenderChunk rc = m.toRenderThisFrame.get(i);
            if (rc.getRenderLevels().getPlayerIndex() == playerIndex) {
               pzoptCompositeOne(rc, playerIndex);
               started |= zombie.core.SceneShaderStore.chunkRenderShader != null;
            }
         }
         if (started) {
            IndieGL.EndShader();
         }
         SpriteRenderer.instance.glDoEndFrame();
         SpriteRenderer.instance.glDoStartFrame(offscreenWidth, offscreenHeight, Core.getInstance().getCurrentPlayerZoom(), playerIndex);
      }
      m.submitCachesForFrame();
      SpriteRenderer.instance.releaseFBORenderChunkLock();
   }

   /** FBORenderChunk.renderInWorldMainThread without its closing EndShader (non-combined path; see pzoptCompositeChunks). */
   private static void pzoptCompositeOne(FBORenderChunk rc, int playerIndex) {
      if (zombie.core.SceneShaderStore.chunkRenderShader != null) {
         IndieGL.StartShader(zombie.core.SceneShaderStore.chunkRenderShader.getID());
         int numSprites = SpriteRenderer.instance.states.getPopulatingActiveState().numSprites;
         TextureDraw texd = SpriteRenderer.instance.states.getPopulatingActiveState().sprite[numSprites - 1];
         texd.tex1 = rc.depth;
         IsoDepthHelper.Results result = IsoDepthHelper.getChunkDepthData(PZMath.fastfloor(IsoCamera.frameState.camCharacterX / 8.0F),
            PZMath.fastfloor(IsoCamera.frameState.camCharacterY / 8.0F), rc.chunk.wx, rc.chunk.wy, rc.getMinLevel());
         texd.chunkDepth = result.depthStart;
         zombie.iso.PlayerCamera camera = IsoCamera.cameras[playerIndex];
         float dx = camera.fixJigglyModelsSquareX;
         float dy = camera.fixJigglyModelsSquareY;
         float depthStart = (result.indexX + result.indexY - dx - dy) / 8.0F / 40.0F;
         depthStart *= 0.46187335F;
         texd.chunkDepth = depthStart - FBORenderLevels.calculateMinLevel(rc.getMinLevel()) * 0.0028867084F;
      }
      IndieGL.glBlendFuncSeparate(1, 771, 773, 1);
      float x = IsoUtils.XToScreen(rc.chunk.wx * 8, rc.chunk.wy * 8, rc.getMinLevel(), 0);
      float y = IsoUtils.YToScreen(rc.chunk.wx * 8, rc.chunk.wy * 8, rc.getMinLevel(), 0);
      float w = rc.w;
      float h = rc.h;
      if (rc.highRes) {
         w /= 2.0F;
         h /= 2.0F;
      }
      y -= FBORenderChunk.PIXELS_PER_LEVEL * (rc.getTopLevel() - rc.getMinLevel() + 1);
      y -= FBORenderLevels.extraHeightForJumboTrees(rc.getMinLevel(), rc.getTopLevel());
      x -= IsoCamera.getOffX();
      y -= IsoCamera.getOffY();
      x /= IsoCamera.frameState.zoom;
      y /= IsoCamera.frameState.zoom;
      w /= IsoCamera.frameState.zoom;
      h /= IsoCamera.frameState.zoom;
      x -= w / 2.0F;
      x += IsoCamera.cameras[playerIndex].fixJigglyModelsX;
      y += IsoCamera.cameras[playerIndex].fixJigglyModelsY;
      if (rc.tex.getTextureId() != null) {
         boolean bMipMaps = DebugOptions.instance.fboRenderChunk.mipMaps.getValue() && !rc.highRes;
         rc.tex.getTextureId().setMinFilter(bMipMaps ? 9987 : 9728);
         rc.tex.getTextureId().setMagFilter(IsoCamera.frameState.zoom == 0.75F ? 9729 : 9728);
      }
      IndieGL.glDepthFunc(515);
      IndieGL.glDepthMask(true);
      IndieGL.enableDepthTest();
      SpriteRenderer.instance.render(rc.getTexture(), x, y, w, h, 1.0F, 1.0F, 1.0F, 1.0F, null);
      IndieGL.enableDepthTest();
      IndieGL.glDepthMask(true);
      rc.renderX = x;
      rc.renderY = y;
      rc.renderW = w;
      rc.renderH = h;
   }

   private boolean pzoptSchedNow; // pzopt: bakeScheduler decides this frame (off during a zoom flood and the resume-shot capture)
   private boolean pzoptSchedPlanned; // pzopt: bakeScheduler planned this frame (renderTilesInternal, before prepareChunksForUpdating)

   // pzopt: bakeScheduler (pzopt.BakeScheduler). Every dirty on-screen level is offered once a frame with its class and
   // its chunk's distance to the camera character; the grants are read back in renderOneLevel. A lighting-only level
   // (flag 32 alone, not strong) baked less than lightingRebakeMs ago is not offered: it keeps its texture, as before.
   private void pzoptSchedulePlan(int playerIndex, long currentTimeMillis) { // pzopt
      pzopt.BakeScheduler s = pzopt.BakeScheduler.get(playerIndex);
      int frameNo = IsoWorld.instance.getFrameNo();
      s.begin(frameNo);
      s.cameraLevel(PZMath.fastfloor(IsoCamera.frameState.camCharacterZ)); // pzopt: bakeLevelChangeFrames, a floor change bakes at once
      float zoom = Core.getInstance().getZoom(playerIndex);
      int pcx = PZMath.fastfloor(IsoCamera.frameState.camCharacterX / 8.0F);
      int pcy = PZMath.fastfloor(IsoCamera.frameState.camCharacterY / 8.0F);
      FBORenderCell.PerPlayerData perPlayerData1s = this.perPlayerData[playerIndex];
      ArrayList<IsoChunk> chunks = perPlayerData1s.onScreenChunks;
      for (int i = 0; i < chunks.size(); i++) {
         IsoChunk c = chunks.get(i);
         FBORenderLevels rl = c.getRenderLevels(playerIndex);
         for (int z = c.minLevel; z <= c.maxLevel; z++) {
            if (z != rl.getMinLevel(z) || !rl.isOnScreen(z) || !rl.isDirty(z, zoom)) {
               continue;
            }
            FBORenderChunk rc = rl.getFBOForLevel(z, zoom);
            // A level last found fully occluded (renderOneLevel returns before the bake decision) is not offered while the
            // occlusion stays as it was. The plan runs before this frame's occlusion pass, so it only reads the stored count
            // (writing one from last frame's grid left levels at 0 for good: nothing baked, run td-combo5); a level without
            // a texture is always offered (at worst an unused grant).
            if (FBORenderOcclusion.getInstance().enabled && rc != null && !perPlayerData1s.occlusionChanged && rl.getRenderedSquaresCount(z) == 0) {
               continue;
            }
            boolean noTexture = rc == null || rl.isDirty(z, 512L, zoom);
            int klass;
            if (!noTexture && rl.isDirty(z, 1L | 2L | 4L | 8L | 16L | 64L | 128L | 256L | 4096L | 8192L, zoom)) {
               klass = pzopt.BakeScheduler.MUST;
            } else if (noTexture) {
               klass = pzopt.BakeScheduler.ARRIVAL;
            } else if (rl.isDirty(z, 2048L | 16384L, zoom)) {
               klass = pzopt.BakeScheduler.CUTAWAY;
            } else if (rl.isDirty(z, ~(32L | pzopt.BakeScheduler.DIRTY_SEAM_LOW), zoom)) {
               klass = pzopt.BakeScheduler.REDRAW;
            } else if (rl.isDirty(z, pzopt.BakeScheduler.DIRTY_SEAM_LOW, zoom)) {
               klass = pzopt.BakeScheduler.LIGHT; // seamDirections: a neighbour this level's seams do not read loaded
            } else if (pzopt.LightDirt.rebakeNow(c, z, frameNo)) {
               klass = pzopt.BakeScheduler.STRONG;
            } else {
               Long last = this.pzoptLastBakeMs.get(rc);
               if (last != null && currentTimeMillis - last < pzopt.Config.LIGHTING_REBAKE_MS) {
                  continue; // held like before: the drift shows at the next re-bake
               }
               klass = pzopt.BakeScheduler.LIGHT;
            }
            s.offer(c, z, klass, Math.max(Math.abs(c.wx - pcx), Math.abs(c.wy - pcy)));
         }
      }
      s.plan(s.budget(pzopt.Pacing.capIntervalNs()));
   }

   private boolean pzoptStrongNow(IsoChunk c, int level) {
      if (!pzopt.LightDirt.rebakeNow(c, level, IsoWorld.instance.getFrameNo())) {
         return false;
      }
      int budget = this.pzoptStrongBudget();
      if (budget > 0 && this.pzoptStrongThisFrame >= budget) {
         pzoptStrongHeld++;
         return false;
      }
      this.pzoptStrongThisFrame++;
      pzoptStrongRebakes++;
      return true;
   }
   private final java.util.IdentityHashMap<FBORenderChunk, Integer> pzoptRebakeHeldSince = new java.util.IdentityHashMap<>();
   // pzopt: zoomRetain (2026-09-22, pzopt.ZoomRetain): kept textures back on screen, re-baked under their own budget
   private static final boolean pzoptZoomRetain = pzopt.Overrides.enabled() && pzopt.Config.ZOOM_RETAIN;
   private int pzoptZoomRebakesThisFrame;
   private final float[] pzoptLastZoom = new float[4];
   private boolean pzoptZoomFlood;
   private boolean pzoptZoomChangedNow; // the zoom changed this frame (dev tally of what still bakes in that frame)
   private int pzoptZoomDeferredThisFrame;
   private int pzoptZoomAllowLeft; // this frame's plan credits not given to a pending level (first-sight flood levels take them)
   private int pzoptZoomBudgetNow = pzopt.Config.ZOOM_REBAKE_BUDGET; // adaptive: halves after a long frame, grows back after short ones
   private final ArrayList<IsoChunk> pzoptZoomPending = new ArrayList<>();

   /**
    * The frame's zoom plan: the pending levels (c.pzoptZoomReturned) of the loaded chunks sorted by their chunk's distance
    * to the camera character, the first pzoptZoomBudgetNow of them allowed (c.pzoptZoomAllowed). The count follows the last
    * game-thread frame step (FrameCap.lastStepNs, the limiter's wait excluded): over ZOOM_FRAME_MS it halves (a fresh chunk texture costs the render thread ~1 ms of GL allocation
    * on top of the bake, and that wait shows up here), under 3/4 of it grows by two, within [4, zoomRebakeBudget].
    */
   private void pzoptZoomPlan(int playerIndex) {
      long lastStepNs = pzopt.FrameCap.lastStepNs; // the previous frame step's own length (no limiter wait, so a 60 fps cap is not read as a long frame)
      if (lastStepNs > 0L) {
         float lastMs = lastStepNs / 1e6F;
         if (lastMs > pzopt.Config.ZOOM_FRAME_MS) {
            this.pzoptZoomBudgetNow = Math.max(4, this.pzoptZoomBudgetNow / 2);
         } else if (lastMs < pzopt.Config.ZOOM_FRAME_MS * 0.75F) {
            this.pzoptZoomBudgetNow = Math.min(pzopt.Config.ZOOM_REBAKE_BUDGET, this.pzoptZoomBudgetNow + 2);
         }
      }
      this.pzoptZoomAllowLeft = this.pzoptZoomBudgetNow;
      IsoChunkMap chunkMap = this.cell.chunkMap[playerIndex];
      ArrayList<IsoChunk> pending = this.pzoptZoomPending;
      pending.clear();
      for (int xx = 0; xx < IsoChunkMap.chunkGridWidth; xx++) {
         for (int yy = 0; yy < IsoChunkMap.chunkGridWidth; yy++) {
            IsoChunk c = chunkMap.getChunk(xx, yy);
            if (c != null && c.pzoptZoomReturned[playerIndex] != 0L) {
               // the on-screen scan (checkNewlyOnScreenChunks, before this plan, reading last frame's flood flag) marks a
               // camera-motion return allowed at once: outside a flood the mark stands and is not charged to the budget
               // (the plan used to clear it here, so those returns competed nearest-first within the budget and a pan over
               // seen ground after a slow frame drew stale textures for a frame or two); in a flood frame it is sorted in
               c.pzoptZoomAllowed[playerIndex] = this.pzoptZoomFlood ? 0L : c.pzoptZoomAllowed[playerIndex] & c.pzoptZoomReturned[playerIndex];
               pending.add(c);
            }
         }
      }
      if (pending.isEmpty()) {
         return;
      }
      if (this.pzoptZoomFlood) {
         pzopt.ZoomRetain.floodFrames++;
      }
      IsoGameCharacter ch = IsoCamera.getCameraCharacter();
      final float px = ch != null ? ch.getX() : IsoCamera.frameState.camCharacterX;
      final float py = ch != null ? ch.getY() : IsoCamera.frameState.camCharacterY;
      pending.sort((a, b) -> Float.compare(pzoptChunkDist2(a, px, py), pzoptChunkDist2(b, px, py)));
      int left = this.pzoptZoomBudgetNow;
      for (int i = 0; i < pending.size() && left > 0; i++) {
         IsoChunk c = pending.get(i);
         long allowed = c.pzoptZoomAllowed[playerIndex]; // the scan's uncharged marks, if any
         long bits = c.pzoptZoomReturned[playerIndex] & ~allowed;
         while (bits != 0L && left > 0) {
            long low = bits & -bits;
            allowed |= low;
            bits ^= low;
            left--;
         }
         c.pzoptZoomAllowed[playerIndex] = allowed;
      }
      this.pzoptZoomAllowLeft = left;
   }

   private static float pzoptChunkDist2(IsoChunk c, float px, float py) {
      float dx = c.wx * 8 + 4 - px;
      float dy = c.wy * 8 + 4 - py;
      return dx * dx + dy * dy;
   }

   /**
    * After the chunk loop: a level the plan (or the scan) allowed this frame either took its credit in renderOneLevel (both
    * bits cleared there) or never reached the gate: its chunk was skipped (lighting not done yet, not in the on-screen
    * list), the level index is not visited (the chunk's minLevel moved), or the level is clean with a texture and has no
    * dirt to enter the gate with. Such a level is not pending: its bits go, so a bit no path clears can take at most one
    * credit. The 3441a1c build had no such guard and two paths that left bits behind: those levels, nearest the camera,
    * took every credit of every plan, the flood never ended, and every chunk level streamed in from then on waited for a
    * credit that never came (textures stopped appearing past a fixed radius; the Workshop report of 2026-09-22).
    */
   private void pzoptZoomSettle(int playerIndex) {
      ArrayList<IsoChunk> pending = this.pzoptZoomPending;
      for (int i = 0; i < pending.size(); i++) {
         IsoChunk c = pending.get(i);
         long stale = c.pzoptZoomAllowed[playerIndex] & c.pzoptZoomReturned[playerIndex];
         if (stale != 0L) {
            c.pzoptZoomReturned[playerIndex] &= ~stale;
            pzopt.ZoomRetain.dropped += Long.bitCount(stale);
         }
         c.pzoptZoomAllowed[playerIndex] = 0L;
      }
   }

   /**
    * A returned level whose texture is deferred this frame: draws the given stale texture, else the other-scale one, else
    * nothing (the per-player lists are kept as for a stale draw).
    */
   private void pzoptDeferZoomDraw(IsoChunk c, int level, float zoom, FBORenderLevels renderLevels, FBORenderCell.PerPlayerData perPlayerData1, FBORenderChunk stale) {
      FBORenderChunk draw = stale;
      if (draw == null && pzopt.Config.ZOOM_PLACEHOLDER) {
         draw = this.pzoptOtherScaleTexture(renderLevels, level, zoom);
      }
      if (draw != null) {
         FBORenderChunkManager.instance.renderChunk = draw;
         FBORenderChunkManager.instance.endRenderChunkLevel(c, level, zoom, false); pzopt.PixelLight.bakeEnd(); pzopt.SpriteFilter.bakeEnd(); // pzopt: pixelLight; sprite filter, the finished texture gets its sharp level 1
         if (!renderLevels.getCachedSquares_AnimatedAttachments(level).isEmpty()) {
            perPlayerData1.addChunkWith_AnimatedAttachments(c);
         }
         if (!renderLevels.getCachedSquares_TranslucentFloor(level).isEmpty()) {
            perPlayerData1.addChunkWith_TranslucentFloor(c);
         }
         if (renderLevels.getCachedSquares_Items(level).size() + renderLevels.getCachedSquares_TranslucentNonFloor(level).size()
               + renderLevels.getCachedSquares_CutawayWindowFrames(level).size() > 0) {
            perPlayerData1.addChunkWith_TranslucentNonFloor(c);
         }
      }
   }

   /** The level's complete texture at the other scale (null when there is none): the placeholder while this scale bakes. */
   private FBORenderChunk pzoptOtherScaleTexture(FBORenderLevels renderLevels, int level, float cameraZoom) {
      if (FBORenderLevels.getTextureScale(0.5F) == FBORenderLevels.getTextureScale(1.0F)) {
         return null; // high-res textures off: one scale only
      }
      float otherZoom = FBORenderLevels.getTextureScale(cameraZoom) > 1 ? 1.0F : 0.5F;
      FBORenderChunk other = renderLevels.getFBOForLevel(level, otherZoom);
      if (other == null || other.tex == null || other.getMinLevel() != renderLevels.getMinLevel(level) || renderLevels.isDirty(level, 512L, otherZoom)) {
         return null;
      }
      pzopt.ZoomRetain.placeholders++;
      return other;
   }
   private static long pzoptRebakesTotal;
   private static long pzoptRebakesHeld;
   private final java.util.HashSet<FBORenderChunk> pzoptDeferredTextures = new java.util.HashSet<>();
   private static long pzoptDeferredTotal;
   private static long pzoptLightingRebakesHeld;
   private static long pzoptStrongRebakes; // pzopt: lighting-only re-bakes that skipped the holds (pzopt.LightDirt)
   private static long pzoptStrongHeld; // pzopt: strong levels past the frame's strong budget, held like weak drift
   // pzopt: cutaway savings (Config.CUTAWAY_FAST / CUTAWAY_RADIUS / GRID_STACK_INTERVAL)
   private final java.util.ArrayList<IsoChunk> pzoptNearChunks = new java.util.ArrayList<>();
   private IsoGridSquare pzoptGridStackSquare;
   private zombie.iso.IsoDirections pzoptGridStackDir;
   private int pzoptGridStackFrame = -1000;
   private final java.util.IdentityHashMap<FBORenderChunk, Long> pzoptLastBakeMs = new java.util.IdentityHashMap<>();
   private static long pzoptBakesTotal;

   /** pzopt: the bake / re-bake counters since boot, one string for the harness summary at route end (the periodic log line may never print in a short run). */
   public static String pzoptBakeCounters() {
      return "bakes=" + pzoptBakesCumulative + " deferred=" + pzoptDeferredTotal + " lightingRebakesHeld=" + pzoptLightingRebakesHeld + " strongNow=" + pzoptStrongRebakes
            + " strongPastBudget=" + pzoptStrongHeld + " strongMarks=" + pzopt.LightDirt.strongMarks + " globalLightEvents=" + pzopt.LightDirt.globalEvents
            + " flushed=" + pzoptLightingFlushed + " budgetedRebakes=" + pzoptRebakesTotal + " rebakesHeld=" + pzoptRebakesHeld + " creationsDeferred=" + pzoptCreatesStarved
            + " strongBudgetCuts=" + pzoptStrongBudgetCuts + " dupSquaresDropped=" + pzoptDupSquaresDropped;
   }
   public static long pzoptBakesCumulative; // pzopt: never reset; the harness zoom trace reads the per-frame delta
   public static long pzoptDeferredCumulative; // pzopt: never reset; deferred (budgeted / held) levels
   private static final long[] pzoptBakeFlags = new long[16];
   private static final String[] PZOPT_FLAG_NAMES = {"blood", "corpse", "itemAdd", "itemRemove", "itemModify", "lighting", "objectAdd", "objectRemove", "objectModify", "create", "redraw", "cutaways", "trees", "obscuring", "redoCutaways", "b15"};
   // pzopt: lighting budget — chunks whose square light info is refreshed per frame; continues next frame
   private final java.util.LinkedHashMap<IsoChunk, Long> pzoptLightingPendingLevels = new java.util.LinkedHashMap<>();

   // pzopt: dev counters — what the per-frame translucent pass draws (instrument=true), logged every 1800 frames
   private static final long[] pzoptTl = new long[8];
   private static int pzoptTlFrames;
   private static final String[] PZOPT_TL_NAMES = {"window", "door", "tree", "depthFlagTranslucent", "animating", "fading/obscuring", "other", "squares"};
   private static final java.util.HashMap<String, Integer> pzoptTlSets = new java.util.HashMap<>();

   private static void pzoptCountTranslucent(IsoObject object) {
      IsoSprite sprite = object.getSprite();
      int k;
      if (object instanceof IsoWindow) k = 0;
      else if (object instanceof IsoDoor) k = 1;
      else if (object instanceof IsoTree) k = 2;
      else if (sprite != null && (sprite.depthFlags & 2) != 0) {
         k = 3;
         String n = sprite.getName();
         if (n != null) {
            int u = n.lastIndexOf('_');
            pzoptTlSets.merge(u > 0 ? n.substring(0, u) : n, 1, Integer::sum);
         }
      }
      else if (object.isAnimating()) k = 4;
      else if (sprite != null && (sprite.solid || sprite.solidTrans)) k = 5;
      else k = 6;
      pzoptTl[k]++;
   }

   private static void pzoptTlFrame() {
      if (++pzoptTlFrames < 1800) return;
      StringBuilder sb = new StringBuilder("translucent pass per frame:");
      for (int i = 0; i < pzoptTl.length; i++) {
         sb.append(' ').append(PZOPT_TL_NAMES[i]).append('=').append(pzoptTl[i] / pzoptTlFrames);
         pzoptTl[i] = 0;
      }
      if (!pzoptTlSets.isEmpty()) {
         final int frames = pzoptTlFrames;
         sb.append(" | trees waited for texture=").append(pzoptTreesWaited).append(" arrived=").append(pzoptTreesArrived).append(" | bakes in period=").append(pzoptBakesTotal).append(" deferred so far=").append(pzoptDeferredTotal).append(" lighting rebakes held=").append(pzoptLightingRebakesHeld).append(" strong now=").append(pzoptStrongRebakes).append(" strong past budget=").append(pzoptStrongHeld).append(" creations deferred=").append(pzoptCreatesStarved).append(" strong marks=").append(pzopt.LightDirt.strongMarks).append(" global light events=").append(pzopt.LightDirt.globalEvents).append(" flushed=").append(pzoptLightingFlushed).append(" budgeted rebakes=").append(pzoptRebakesTotal).append(" held=").append(pzoptRebakesHeld)
            .append(" | zoom kept=").append(pzopt.ZoomRetain.kept).append(" returned=").append(pzopt.ZoomRetain.returned).append(" rebakes=").append(pzopt.ZoomRetain.rebakes).append(" creations=").append(pzopt.ZoomRetain.creations).append(" urgent=").append(pzopt.ZoomRetain.urgent).append(" placeholders=").append(pzopt.ZoomRetain.placeholders).append(" dropped=").append(pzopt.ZoomRetain.dropped).append(" flags:"); // pzopt: zoomRetain counters
      for (int b = 0; b < 16; b++) {
         if (pzoptBakeFlags[b] > 0) sb.append(' ').append(PZOPT_FLAG_NAMES[b]).append('=').append(pzoptBakeFlags[b]);
         pzoptBakeFlags[b] = 0;
      }
      pzoptBakesTotal = 0;
      sb.append(" | cutaway visits=").append(zombie.iso.fboRenderChunk.FBORenderCutaways.pzoptCutawayVisits) // pzopt: 400 fps pass counters
         .append(" chunks invalidated=").append(zombie.iso.fboRenderChunk.FBORenderCutaways.pzoptCutawayChunksInvalidated)
         .append(" squares changed=").append(zombie.iso.fboRenderChunk.FBORenderCutaways.pzoptCutawayChangedSquares)
         .append(" walls visited=").append(zombie.iso.fboRenderChunk.FBORenderCutaways.pzoptWallsVisited)
         .append(" skipped=").append(zombie.iso.fboRenderChunk.FBORenderCutaways.pzoptWallsSkipped)
         .append(" roof flips held=").append(zombie.iso.fboRenderChunk.FBORenderCutaways.pzoptRoofFlipsHeld) // pzopt: roofHideDebounceFrames
         .append(" | occlusion rebuilds skipped=").append(pzoptOcclusionRebuildsSkipped)
         .append(" light info skipped=").append(pzoptLightInfoSkipped).append(" levels gated=").append(pzoptLightInfoLevelsGated);
      sb.append(pzopt.GpuSections.summary()); // pzopt: GPU sections (Config.GPU_SECTIONS)
      if (pzopt.SeamSpread.ON) sb.append(pzopt.SeamSpread.summary()); // pzopt: seamSpread counters
      if (pzopt.BakeScheduler.ON) sb.append(pzopt.BakeScheduler.get(0).summary()); // pzopt: bakeScheduler counters
      if (pzopt.GlNames.ON) sb.append(pzopt.GlNames.summary()); // pzopt: glNoSync counters
      if (zombie.iso.LightingJNI.pzoptNewDeferred > 0) { sb.append(" | new-chunk lighting passes deferred: ").append(zombie.iso.LightingJNI.pzoptNewDeferred); zombie.iso.LightingJNI.pzoptNewDeferred = 0; } // pzopt: lightingNewChunkBudget
      if (pzoptParallelCountFrames > 0) { sb.append(" | occlusion counts on workers: ").append(pzoptParallelCounts).append(" levels in ").append(pzoptParallelCountFrames).append(" frames, dev checks ").append(pzoptCountChecks).append(" mismatches ").append(pzoptCountMismatches); pzoptParallelCounts = pzoptParallelCountFrames = 0; } // pzopt: occlusionCountParallel
      if (pzopt.PuddleCache.enabled()) { sb.append(" | ").append(pzopt.PuddleCache.stats()); } // pzopt
      if (pzopt.RainSplashes.enabled()) { sb.append(" | ").append(pzopt.RainSplashes.stats()); } // pzopt
      if (pzopt.RainTiles.enabled()) { sb.append(" | ").append(pzopt.RainTiles.stats()); } // pzopt
      if (pzopt.FogPass.enabled()) { sb.append(" | ").append(pzopt.FogPass.stats()); } // pzopt: one-pass fog
      if (pzopt.AmbientOcclusion.enabled()) { sb.append(" | ").append(pzopt.AmbientOcclusion.stats()); } // pzopt: ambient occlusion
      if (pzopt.ChunkAo.enabled()) { sb.append(" | ").append(pzopt.ChunkAo.stats()); } // pzopt: ambient occlusion baked into the chunk textures
      if (pzopt.Config.TREES_IN_CHUNK_TEXTURE && pzopt.Config.TREE_BAKE_PASS) { sb.append(" | ").append(pzopt.TreeBake.stats()); } // pzopt: issue #5
      if (pzopt.Config.TREE_BAKE_MAX_CHUNKS_PER_SEC > 0) { sb.append(" | trees per-frame frames: ").append(pzoptTreesPerFrameFrames).append(" chunks/s now ").append(String.format(java.util.Locale.ROOT, "%.0f", pzopt.ChunkRate.perSecond())); } // pzopt: treeBakeMaxChunksPerSec
      sb.append(" | ").append(pzopt.AnimBatch.describe()); // pzopt: the zombies' bone-math batch
      sb.append(" | ").append(pzopt.ActionEval.describe()); // pzopt: the zombies' transition-evaluation batch
      sb.append(" | ").append(pzopt.AnimParallel.describe()); // pzopt: animatorParallel
      sb.append(" | ").append(pzopt.CharDraw.describe()); // pzopt: charDrawPrep, the characters draw pre-pass
      sb.append(" | ").append(pzopt.LightingBatch.describe()); // pzopt: the lighting reads on the workers
      sb.append(" | ").append(pzopt.SeparateMask.describe()); // pzopt: separateFast, the cached grid answers
      sb.append(" | top tilesets:");
         pzoptTlSets.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(8)
               .forEach(e -> sb.append(' ').append(e.getKey()).append('=').append(e.getValue() / frames));
         pzoptTlSets.clear();
      }
      pzoptTlFrames = 0;
      pzopt.Log.info(sb.toString());
   }

   public void renderTranslucent(IsoObject object) {
      if (pzopt.Config.INSTRUMENT) pzoptCountTranslucent(object);
      IndieGL.glDefaultBlendFunc();
      IsoSprite sprite = object.getSprite();
      if (sprite != null && sprite.getProperties().has(IsoFlagType.transparentFloor)) {
         this.renderFloor(object);
      } else if (object instanceof IsoDoor || object instanceof IsoThumpable isoThumpable && isoThumpable.isDoor()) {
         object.sx = 0.0F;
         this.renderMinusFloor_DoorOrWall(object);
      } else if (object.getType() == IsoObjectType.doorFrW || object.getType() == IsoObjectType.doorFrN) {
         this.renderMinusFloor_DoorOrWall(object);
      } else if (sprite != null && sprite.solidfloor && object.square.getWater() != null && object.square.getWater().isbShore()) {
         this.renderFloor(object);
      } else {
         if (sprite == null || !sprite.cutN && !sprite.cutW) {
            object.getRenderInfo(IsoCamera.frameState.playerIndex).targetAlpha = this.calculateObjectTargetAlpha_NotDoorOrWall(object);
         } else {
            object.getRenderInfo(IsoCamera.frameState.playerIndex).targetAlpha = this.calculateObjectTargetAlpha_DoorOrWall(object);
         }

         IsoObjectType t = IsoObjectType.MAX;
         if (object.sprite != null) {
            t = object.sprite.getTileType();
         }

         boolean isWestDoorOrWall = t == IsoObjectType.doorFrW || t == IsoObjectType.doorW || object.sprite != null && object.sprite.cutW;
         boolean isNorthDoorOrWall = t == IsoObjectType.doorFrN || t == IsoObjectType.doorN || object.sprite != null && object.sprite.cutN;
         if (object.sprite != null && (isWestDoorOrWall || isNorthDoorOrWall)) {
            if (DebugOptions.instance.terrain.renderTiles.isoGridSquare.doorsAndWalls.getValue()) {
               this.renderMinusFloor_DoorOrWall(object);
            }
         } else if (DebugOptions.instance.terrain.renderTiles.isoGridSquare.objects.getValue()) {
            this.renderMinusFloor_NotDoorOrWall(object);
         }

         if (!(object instanceof IsoBarbecue) || !FBORenderObjectHighlight.getInstance().isRendering()) {
            if (object.hasAnimatedAttachments()) {
               this.renderAnimatedAttachments(object);
            }
         }
      }
   }

   private void renderCutawayOutline(IsoGridSquare square, boolean bAttachedSE) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      IsoObject[] objects = (IsoObject[])square.getObjects().getElements();
      int numObjects = square.getObjects().size();

      for (int i = 0; i < numObjects; i++) {
         IsoObject object = objects[bAttachedSE ? numObjects - 1 - i : i];
         ObjectRenderInfo renderInfo = object.getRenderInfo(playerIndex);
         if (renderInfo.cutawayOutline) {
            this.renderTranslucent(object);
         }
      }
   }

   private void renderAnimatedAttachments(int playerIndex) {
      this.renderTranslucentOnly = true;
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];

      for (int i = 0; i < perPlayerData1.chunksWithAnimatedAttachments.size(); i++) {
         IsoChunk chunk = perPlayerData1.chunksWithAnimatedAttachments.get(i);
         this.renderOneChunk_AnimatedAttachments(playerIndex, chunk);
      }
   }

   private void renderOneChunk_AnimatedAttachments(int playerIndex, IsoChunk chunk) {
      IndieGL.enableDepthTest();
      IndieGL.glDepthFunc(515);
      IndieGL.glDepthMask(false);

      for (int zza = chunk.minLevel; zza <= chunk.maxLevel; zza++) {
         this.renderOneLevel_AnimatedAttachments(playerIndex, chunk, zza);
      }

      IndieGL.glDepthMask(false);
      IndieGL.glDepthFunc(519);
   }

   private void renderOneLevel_AnimatedAttachments(int playerIndex, IsoChunk chunk, int level) {
      FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
      if (renderLevels.isOnScreen(level)) {
         ChunkLevelData levelData = chunk.getCutawayDataForLevel(level);
         List<IsoGridSquare> squares = renderLevels.getCachedSquares_AnimatedAttachments(level);

         for (int i = 0; i < squares.size(); i++) {
            IsoGridSquare square = squares.get(i);
            if (square.z == level && levelData.shouldRenderSquare(playerIndex, square) && square.IsOnScreen()) {
               this.renderAnimatedAttachments(square);
            }
         }
      }
   }

   private void renderAnimatedAttachments(IsoGridSquare square) {
      this.renderAnimatedAttachments = true;
      int playerIndex = IsoCamera.frameState.playerIndex;
      IsoObject[] objects = (IsoObject[])square.getObjects().getElements();
      int numObjects = square.getObjects().size();

      for (int i = 0; i < numObjects; i++) {
         IsoObject object = objects[i];
         ObjectRenderLayer renderLayer = object.getRenderInfo(playerIndex).layer;
         if (renderLayer != ObjectRenderLayer.None
            && renderLayer != ObjectRenderLayer.Translucent
            && renderLayer != ObjectRenderLayer.TranslucentSE
            && object.hasAnimatedAttachments()) {
            this.renderAnimatedAttachments(object);
         }
      }

      this.renderAnimatedAttachments = false;
   }

   public void renderAnimatedAttachments(IsoObject object) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      ColorInfo lightInfo = object.square.getLightInfo(playerIndex);
      if (DebugOptions.instance.fboRenderChunk.nolighting.getValue()) {
         this.defColorInfo.set(1.0F, 1.0F, 1.0F, lightInfo.a);
         lightInfo = this.defColorInfo;
      }

      IndieGL.glDefaultBlendFunc();
      object.renderAnimatedAttachments(object.getX(), object.getY(), object.getZ(), lightInfo);
   }

   private void renderFlies(int playerIndex) {
      this.renderTranslucentOnly = true;
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];

      for (int i = 0; i < perPlayerData1.chunksWithFlies.size(); i++) {
         IsoChunk chunk = perPlayerData1.chunksWithFlies.get(i);
         this.renderOneChunk_Flies(playerIndex, chunk);
      }
   }

   private void renderOneChunk_Flies(int playerIndex, IsoChunk chunk) {
      IndieGL.enableDepthTest();
      IndieGL.glDepthFunc(515);
      IndieGL.glDepthMask(false);
      IndieGL.enableBlend();
      IndieGL.glBlendFunc(770, 771);

      for (int zza = chunk.minLevel; zza <= chunk.maxLevel; zza++) {
         this.renderOneLevel_Flies(playerIndex, chunk, zza);
      }

      IndieGL.glDepthMask(false);
      IndieGL.glDepthFunc(519);
   }

   private void renderOneLevel_Flies(int playerIndex, IsoChunk chunk, int level) {
      FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
      if (renderLevels.isOnScreen(level)) {
         ChunkLevelData levelData = chunk.getCutawayDataForLevel(level);
         List<IsoGridSquare> squares = renderLevels.getCachedSquares_Flies(level);

         for (int i = 0; i < squares.size(); i++) {
            IsoGridSquare square = squares.get(i);
            if (square.z == level && levelData.shouldRenderSquare(playerIndex, square) && square.IsOnScreen() && square.hasFlies()) {
               CorpseFlies.render(square.x, square.y, square.z);
            }
         }
      }
   }

   private void updateChunkLighting(int playerIndex) {
      if (!DebugOptions.instance.fboRenderChunk.nolighting.getValue()) {
         if (DebugOptions.instance.fboRenderChunk.updateSquareLightInfo.getValue()) {
            FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
            int pzoptLightBudget = pzopt.Overrides.enabled() ? pzopt.Config.LIGHTING_BUDGET : 0;
            if (pzoptLightBudget > 0) {
               this.pzoptUpdateChunkLightingBudgeted(playerIndex, perPlayerData1, pzoptLightBudget);
               return;
            }
            if (perPlayerData1.lightingUpdateCounter != LightingJNI.getUpdateCounter(playerIndex)) {
               perPlayerData1.lightingUpdateCounter = LightingJNI.getUpdateCounter(playerIndex);
               int chunkLevelCount = 0;
               this.sortedChunks.clear();
               PZArrayUtil.addAll(this.sortedChunks, perPlayerData1.onScreenChunks);
               this.timSort.doSort(this.sortedChunks.getElements(), (java.util.Comparator) Comparator.comparingInt((IsoChunk a) -> a.lightingUpdateCounter), 0, this.sortedChunks.size()); // pzopt: decompiler fix

               for (int i = 0; i < this.sortedChunks.size(); i++) {
                  IsoChunk chunk = (IsoChunk)this.sortedChunks.get(i);
                  boolean updated = false;

                  for (int z = chunk.minLevel; z <= chunk.maxLevel; z++) {
                     if (this.updateChunkLevelLighting(playerIndex, chunk, z)) {
                        updated = true;
                        chunk.lightingUpdateCounter = perPlayerData1.lightingUpdateCounter;
                     }
                  }

                  if (DebugOptions.instance.lightingSplitUpdate.getValue() && updated) {
                     if (++chunkLevelCount >= 5) {
                        return;
                     }
                  }
               }
            }
         }
      }
   }

   /**
    * pzopt: lighting budget. Stock refreshes the light info of every on-screen chunk level the JNI marks dirty in the
    * frame the lighting counter changes (12 % of slow-frame samples at max zoom). Here at most {@code budget} chunks
    * refresh per frame. The dirty bits are still read for every chunk in the frame the counter changes (the JNI
    * rewrites them on its next pass, so an unread bit is lost and the chunk stays unlit: black tiles and tree
    * silhouettes behind the car at 120 km/h); chunks past the budget are queued and refreshed on the following
    * frames, oldest first, before any newly dirty chunk.
    */
   private void pzoptUpdateChunkLightingBudgeted(int playerIndex, FBORenderCell.PerPlayerData perPlayerData1, int budget) {
      java.util.LinkedHashMap<IsoChunk, Long> pending = this.pzoptLightingPendingLevels;
      if (perPlayerData1.lightingUpdateCounter != LightingJNI.getUpdateCounter(playerIndex)) {
         perPlayerData1.lightingUpdateCounter = LightingJNI.getUpdateCounter(playerIndex);
         this.sortedChunks.clear();
         PZArrayUtil.addAll(this.sortedChunks, perPlayerData1.onScreenChunks);
         this.timSort.doSort(this.sortedChunks.getElements(), (java.util.Comparator) Comparator.comparingInt((IsoChunk a) -> a.lightingUpdateCounter), 0, this.sortedChunks.size()); // pzopt: decompiler fix

         for (int i = 0; i < this.sortedChunks.size(); i++) {
            IsoChunk chunk = (IsoChunk)this.sortedChunks.get(i);
            long mask = 0L;

            for (int z = chunk.minLevel; z <= chunk.maxLevel; z++) {
               if (this.pzoptIsChunkLevelLightingDirty(playerIndex, chunk, z)) {
                  mask |= 1L << (z + 32);
               }
            }

            if (mask != 0L) {
               chunk.lightingUpdateCounter = perPlayerData1.lightingUpdateCounter;
               Long prev = pending.get(chunk);
               pending.put(chunk, prev == null ? mask : prev | mask);
            }
         }
      }

      if (pending.isEmpty()) {
         return;
      }

      int done = 0;
      java.util.Iterator<java.util.Map.Entry<IsoChunk, Long>> it = pending.entrySet().iterator();
      while (it.hasNext()) {
         java.util.Map.Entry<IsoChunk, Long> e = it.next();
         IsoChunk chunk = e.getKey();
         long mask = e.getValue();
         boolean refreshed = false;
         if (perPlayerData1.onScreenChunks.contains(chunk)) {
            for (int z = chunk.minLevel; z <= chunk.maxLevel; z++) {
               if ((mask & (1L << (z + 32))) != 0L) {
                  refreshed |= this.pzoptCacheChunkLevelLightInfo(playerIndex, chunk, z);
               }
            }
         }
         it.remove(); // off-screen or unloaded: dropped, the JNI marks it dirty again when it is lit in view
         if (refreshed && ++done >= budget) {
            return;
         }
      }
   }

   private static long pzoptLightingFlushed; // pzopt: chunks refreshed by the pre-pass flush

   /**
    * pzopt: called by LightingJNI.update just before a lighting pass lands. The per-square dirty bits the pending
    * chunks of the budget still have to read are rewritten by that pass, so the queue is drained here, in the same
    * frame: the budget spreads the work over the frames between two passes and the remainder lands now instead of
    * being lost (the engine answers a non-dirty square with the previous pass, so a late read is no cure either).
    */
   public static void pzoptFlushPendingLighting(int playerIndex) {
      FBORenderCell cell = instance;
      if (cell == null || playerIndex < 0 || playerIndex >= cell.perPlayerData.length || !pzopt.Config.LIGHTING_FLUSH) {
         return;
      }
      java.util.LinkedHashMap<IsoChunk, Long> pending = cell.pzoptLightingPendingLevels;
      if (pending.isEmpty()) {
         return;
      }
      FBORenderCell.PerPlayerData perPlayerData1 = cell.perPlayerData[playerIndex];
      int savedPlayer = IsoCamera.frameState.playerIndex;
      IsoCamera.frameState.playerIndex = playerIndex; // cacheLightInfo reads the player from the frame state
      try {
         if (pzopt.LightingBatch.ENABLED && pzopt.Overrides.enabled()) {
            cell.pzoptFlushPendingLightingParallel(playerIndex, pending, perPlayerData1); // pzopt: lightingReadParallel
            return;
         }
         for (java.util.Map.Entry<IsoChunk, Long> e : pending.entrySet()) {
            IsoChunk chunk = e.getKey();
            long mask = e.getValue();
            if (perPlayerData1.onScreenChunks.contains(chunk)) {
               for (int z = chunk.minLevel; z <= chunk.maxLevel; z++) {
                  if ((mask & (1L << (z + 32))) != 0L) {
                     cell.pzoptCacheChunkLevelLightInfo(playerIndex, chunk, z);
                  }
               }
               pzoptLightingFlushed++;
            }
         }
      } finally {
         IsoCamera.frameState.playerIndex = savedPlayer;
         pending.clear();
      }
   }

   private IsoChunk[] pzoptLightTaskChunks = new IsoChunk[256];
   private int[] pzoptLightTaskLevels = new int[256];

   /**
    * pzopt: lightingReadParallel (pzopt.LightingBatch). The same drain as the loop above, but the chunk levels are
    * collected first, the structures a level's read creates lazily are created here on the game thread (the per-player
    * render levels, the cutaway level data, the once-per-frame stamp row), then every level reads its squares on the
    * frame workers and this thread, and the room / meta hooks the reads deferred run afterwards in level order. With
    * devLightingReadCheck one square in sixteen is asked again here and compared with what the worker stored.
    */
   private void pzoptFlushPendingLightingParallel(int playerIndex, java.util.LinkedHashMap<IsoChunk, Long> pending, FBORenderCell.PerPlayerData perPlayerData1) {
      int n = 0;
      for (java.util.Map.Entry<IsoChunk, Long> e : pending.entrySet()) {
         IsoChunk chunk = e.getKey();
         long mask = e.getValue();
         if (!perPlayerData1.onScreenChunks.contains(chunk)) {
            continue;
         }
         boolean any = false;
         for (int z = chunk.minLevel; z <= chunk.maxLevel; z++) {
            if ((mask & (1L << (z + 32))) != 0L) {
               if (n == this.pzoptLightTaskChunks.length) {
                  this.pzoptLightTaskChunks = Arrays.copyOf(this.pzoptLightTaskChunks, n * 2);
                  this.pzoptLightTaskLevels = Arrays.copyOf(this.pzoptLightTaskLevels, n * 2);
               }
               this.pzoptLightTaskChunks[n] = chunk;
               this.pzoptLightTaskLevels[n] = z;
               n++;
               any = true;
               chunk.getRenderLevels(playerIndex);
               chunk.getCutawayDataForLevel(z);
               pzoptTouchLightInfoRow(chunk, playerIndex, z);
            }
         }
         if (any) {
            pzoptLightingFlushed++;
         }
      }
      if (n == 0) {
         return;
      }
      final IsoChunk[] chunks = this.pzoptLightTaskChunks;
      final int[] levels = this.pzoptLightTaskLevels;
      final int player = playerIndex;
      final int count = n;
      if (n < 2) {
         this.pzoptCacheChunkLevelLightInfo(player, chunks[0], levels[0]);
      } else {
         final pzopt.LightingBatch.Effects[] effects = new pzopt.LightingBatch.Effects[n];
         for (int i = 0; i < n; i++) {
            effects[i] = pzopt.LightingBatch.effectsFor(i, count, player);
         }
         Throwable t = pzopt.FrameBatch.run(n, i -> {
            IsoChunk chunk = chunks[i];
            int z = levels[i];
            pzopt.LightingBatch.withEffects(effects[i], () -> this.pzoptCacheChunkLevelLightInfo(player, chunk, z));
         });
         pzopt.LightingBatch.applyAll(n);
         if (t != null) {
            pzopt.Log.warn("lightingReadParallel: a lighting read failed on a worker: " + t);
         }
         if (pzopt.Config.DEV_LIGHTING_READ_CHECK) {
            for (int i = 0; i < n; i++) {
               IsoChunk chunk = chunks[i];
               IsoGridSquare[] squares = chunk.squares[chunk.squaresIndexOfLevel(levels[i])];
               for (int k = (i & 15); k < squares.length; k += 16) {
                  IsoGridSquare square = squares[k];
                  if (square != null && square.lighting[player] instanceof LightingJNI.JNILighting) {
                     pzopt.LightingBatch.checks++;
                     if (!((LightingJNI.JNILighting)square.lighting[player]).pzoptRecheck()) {
                        pzopt.LightingBatch.mismatches++;
                     }
                  }
               }
            }
         }
      }
      Arrays.fill(chunks, 0, n, null);
   }

   /** pzopt: lightingReadParallel, the once-per-frame stamp row of (player, level) created on the game thread before the batch. */
   private static void pzoptTouchLightInfoRow(IsoChunk chunk, int playerIndex, int level) {
      if (!pzopt.Config.LIGHT_INFO_ONCE_PER_FRAME || level < -32 || level >= 32) {
         return;
      }
      int[][] stamps = chunk.pzoptLightInfoFrame;
      if (stamps == null) {
         stamps = new int[256][];
         chunk.pzoptLightInfoFrame = stamps;
      }
      int row = playerIndex * 64 + level + 32;
      if (stamps[row] == null) {
         int[] r = new int[64];
         Arrays.fill(r, Integer.MIN_VALUE);
         stamps[row] = r;
      }
   }

   private boolean pzoptIsChunkLevelLightingDirty(int playerIndex, IsoChunk chunk, int level) {
      if (level < chunk.minLevel || level > chunk.maxLevel) {
         return false;
      }
      FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
      return renderLevels.isOnScreen(level) && LightingJNI.getChunkDirty(playerIndex, chunk.wx, chunk.wy, level + 32);
   }

   /**
    * pzopt: cacheLightInfo is a JNI call per square, and the same square is asked twice in one frame when its chunk
    * level is both texture-dirty (prepareChunkForUpdating) and lighting-dirty (updateChunkLighting). The lighting
    * results only change in LightingThread.update, which runs before the render, so the second call within a
    * frame is skipped (Config.LIGHT_INFO_ONCE_PER_FRAME). Stamps live on the chunk per player and level.
    */
   private static void pzoptCacheLightInfo(int playerIndex, IsoChunk chunk, int level, int index, IsoGridSquare square) {
      if (pzopt.Config.LIGHT_INFO_ONCE_PER_FRAME && pzopt.Overrides.enabled() && level >= -32 && level < 32) {
         int[][] stamps = chunk.pzoptLightInfoFrame;
         if (stamps == null) {
            stamps = new int[256][];
            chunk.pzoptLightInfoFrame = stamps;
         }
         int row = playerIndex * 64 + level + 32;
         int[] r = stamps[row];
         if (r == null) {
            r = new int[64];
            Arrays.fill(r, Integer.MIN_VALUE);
            stamps[row] = r;
         }
         int frame = IsoWorld.instance.getFrameNo();
         if (r[index] == frame) {
            pzoptLightInfoSkipped++;
            return;
         }
         r[index] = frame;
      }
      square.cacheLightInfo();
   }

   public static long pzoptLightInfoSkipped; // pzopt: counter for the log
   public static long pzoptLightInfoLevelsGated;

   private boolean pzoptCacheChunkLevelLightInfo(int playerIndex, IsoChunk chunk, int level) {
      if (level < chunk.minLevel || level > chunk.maxLevel) {
         return false;
      }
      ChunkLevelData levelData = chunk.getCutawayDataForLevel(level);
      IsoGridSquare[] squares = chunk.squares[chunk.squaresIndexOfLevel(level)];

      for (int i = 0; i < squares.length; i++) {
         IsoGridSquare square = squares[i];
         if (square != null && levelData.shouldRenderSquare(playerIndex, square)) {
            pzoptCacheLightInfo(playerIndex, chunk, level, i, square); // pzopt: once per frame
         }
      }

      return true;
   }

   private boolean updateChunkLevelLighting(int playerIndex, IsoChunk chunk, int level) {
      if (level >= chunk.minLevel && level <= chunk.maxLevel) {
         FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
         if (!renderLevels.isOnScreen(level)) {
            return false;
         }

         if (!LightingJNI.getChunkDirty(playerIndex, chunk.wx, chunk.wy, level + 32)) {
            return false;
         }

         ChunkLevelData levelData = chunk.getCutawayDataForLevel(level);
         IsoGridSquare[] squares = chunk.squares[chunk.squaresIndexOfLevel(level)];

         for (int i = 0; i < squares.length; i++) {
            IsoGridSquare square = squares[i];
            if (square != null && levelData.shouldRenderSquare(playerIndex, square)) {
               pzoptCacheLightInfo(playerIndex, chunk, level, i, square); // pzopt: once per frame
            }
         }

         return true;
      } else {
         return false;
      }
   }

   private void renderOneLevel_Blood(IsoChunk chunk, int zza, int minX, int minY, int maxX, int maxY) {
      if (DebugOptions.instance.terrain.renderTiles.bloodDecals.getValue()) {
         int optionBloodDecals = Core.getInstance().getOptionBloodDecals();
         if (optionBloodDecals != 0) {
            float worldAge = (float)GameTime.getInstance().getWorldAgeHours();
            int playerIndex = IsoCamera.frameState.playerIndex;
            ChunkLevelData cutawayLevel = chunk.getCutawayDataForLevel(zza);
            if (this.splatByType.isEmpty()) {
               for (int i = 0; i < IsoFloorBloodSplat.FLOOR_BLOOD_TYPES.length; i++) {
                  this.splatByType.add(new ArrayList<>());
               }
            }

            for (int n = 0; n < IsoFloorBloodSplat.FLOOR_BLOOD_TYPES.length; n++) {
               this.splatByType.get(n).clear();
            }

            int cx = chunk.wx * 8;
            int cy = chunk.wy * 8;

            for (int n = 0; n < chunk.floorBloodSplatsFade.size(); n++) {
               IsoFloorBloodSplat b = chunk.floorBloodSplatsFade.get(n);
               if ((b.index < 1 || b.index > 10 || IsoChunk.renderByIndex[optionBloodDecals - 1][b.index - 1] != 0)
                  && !(cx + b.x < minX)
                  && !(cx + b.x > maxX)
                  && !(cy + b.y < minY)
                  && !(cy + b.y > maxY)
                  && PZMath.fastfloor(b.z) == zza
                  && b.type >= 0
                  && b.type < IsoFloorBloodSplat.FLOOR_BLOOD_TYPES.length) {
                  b.chunk = chunk;
                  this.splatByType.get(b.type).add(b);
               }
            }

            for (int i = 0; i < chunk.floorBloodSplats.size(); i++) {
               IsoFloorBloodSplat b = (IsoFloorBloodSplat)chunk.floorBloodSplats.get(i);
               if ((b.index < 1 || b.index > 10 || IsoChunk.renderByIndex[optionBloodDecals - 1][b.index - 1] != 0)
                  && !(cx + b.x < minX)
                  && !(cx + b.x > maxX)
                  && !(cy + b.y < minY)
                  && !(cy + b.y > maxY)
                  && PZMath.fastfloor(b.z) == zza
                  && b.type >= 0
                  && b.type < IsoFloorBloodSplat.FLOOR_BLOOD_TYPES.length) {
                  b.chunk = chunk;
                  this.splatByType.get(b.type).add(b);
               }
            }

            for (int n = 0; n < this.splatByType.size(); n++) {
               ArrayList<IsoFloorBloodSplat> splats = this.splatByType.get(n);
               if (!splats.isEmpty()) {
                  String type = IsoFloorBloodSplat.FLOOR_BLOOD_TYPES[n];
                  IsoSprite use;
                  if (!IsoFloorBloodSplat.spriteMap.containsKey(type)) {
                     IsoSprite sp = IsoSprite.CreateSprite(IsoSpriteManager.instance);
                     sp.LoadSingleTexture(type);
                     IsoFloorBloodSplat.spriteMap.put(type, sp);
                     use = sp;
                  } else {
                     use = (IsoSprite)IsoFloorBloodSplat.spriteMap.get(type);
                  }

                  for (int i = 0; i < splats.size(); i++) {
                     IsoFloorBloodSplat b = splats.get(i);
                     ColorInfo inf = this.defColorInfo;
                     inf.r = 1.0F;
                     inf.g = 1.0F;
                     inf.b = 1.0F;
                     inf.a = 0.27F;
                     float aa = (b.x + b.y / b.x) * (b.type + 1);
                     float bb = aa * b.x / b.y * (b.type + 1) / (aa + b.y);
                     float cc = bb * aa * bb * b.x / (b.y + 2.0F);
                     aa *= 42367.543F;
                     bb *= 6367.123F;
                     cc *= 23367.133F;
                     aa %= 1000.0F;
                     bb %= 1000.0F;
                     cc %= 1000.0F;
                     aa /= 1000.0F;
                     bb /= 1000.0F;
                     cc /= 1000.0F;
                     if (aa > 0.25F) {
                        aa = 0.25F;
                     }

                     inf.r -= aa * 2.0F;
                     inf.g -= aa * 2.0F;
                     inf.b -= aa * 2.0F;
                     inf.r += bb / 3.0F;
                     inf.g -= cc / 3.0F;
                     inf.b -= cc / 3.0F;
                     float deltaAge = worldAge - b.worldAge;
                     if (deltaAge >= 0.0F && deltaAge < 72.0F) {
                        float f = 1.0F - deltaAge / 72.0F;
                        inf.r *= 0.2F + f * 0.8F;
                        inf.g *= 0.2F + f * 0.8F;
                        inf.b *= 0.2F + f * 0.8F;
                        inf.a *= 0.25F + f * 0.75F;
                     } else {
                        inf.r *= 0.2F;
                        inf.g *= 0.2F;
                        inf.b *= 0.2F;
                        inf.a *= 0.25F;
                     }

                     if (b.fade > 0) {
                        inf.a = inf.a * (b.fade / (PerformanceSettings.getLockFPS() * 5.0F));
                        if (--b.fade == 0) {
                           b.chunk.floorBloodSplatsFade.remove(b);
                        }
                     }

                     IsoGridSquare square = b.chunk.getGridSquare(PZMath.fastfloor(b.x), PZMath.fastfloor(b.y), PZMath.fastfloor(b.z));
                     if (cutawayLevel.shouldRenderSquare(playerIndex, square)) {
                        if (this.isBlackedOutBuildingSquare(square)) {
                           inf.set(0.0F, 0.0F, 0.0F, inf.a);
                        }

                        if (square != null) {
                           int l0 = square.getVertLight(0, playerIndex);
                           int l1 = square.getVertLight(1, playerIndex);
                           int l2 = square.getVertLight(2, playerIndex);
                           int l3 = square.getVertLight(3, playerIndex);
                           float r0 = Color.getRedChannelFromABGR(l0);
                           float g0 = Color.getGreenChannelFromABGR(l0);
                           float b0 = Color.getBlueChannelFromABGR(l0);
                           float r1 = Color.getRedChannelFromABGR(l1);
                           float g1 = Color.getGreenChannelFromABGR(l1);
                           float b1 = Color.getBlueChannelFromABGR(l1);
                           float r2 = Color.getRedChannelFromABGR(l2);
                           float g2 = Color.getGreenChannelFromABGR(l2);
                           float b2 = Color.getBlueChannelFromABGR(l2);
                           float r3 = Color.getRedChannelFromABGR(l3);
                           float g3 = Color.getGreenChannelFromABGR(l3);
                           float b3 = Color.getBlueChannelFromABGR(l3);
                           inf.r *= (r0 + r1 + r2 + r3) / 4.0F;
                           inf.g *= (g0 + g1 + g2 + g3) / 4.0F;
                           inf.b *= (b0 + b1 + b2 + b3) / 4.0F;
                        }

                        use.renderBloodSplat(b.chunk.wx * 8 + b.x, b.chunk.wy * 8 + b.y, b.z, inf);
                     }
                  }
               }
            }
         }
      }
   }

   private void renderOneLevel_Blood(IsoChunk chunk, int dwx, int dwy, int zza) {
      IsoChunk chunk2 = IsoWorld.instance.currentCell.getChunk(chunk.wx + dwx, chunk.wy + dwy);
      if (chunk2 != null) {
         int chunksPerWidth = 8;
         int minX = chunk.wx * 8 - 1;
         int minY = chunk.wy * 8 - 1;
         int maxX = (chunk.wx + 1) * 8 + 1;
         int maxY = (chunk.wy + 1) * 8 + 1;
         this.renderOneLevel_Blood(chunk2, zza, minX, minY, maxX, maxY);
      }
   }

   private void recalculateAnyGridStacks(int playerIndex) {
      IsoPlayer player = IsoPlayer.players[playerIndex];
      if (player.dirtyRecalcGridStack) {
         player.dirtyRecalcGridStack = false;
         WeatherFxMask.setDiamondIterDone(playerIndex);
      }
   }

   /**
    * pzopt: the occluded-squares grid (and, through occlusionChanged, every on-screen level's rendered-squares count)
    * is rebuilt whenever any on-screen chunk level is dirty. A level dirty for lighting only (flag 32: daylight or a
    * light source drifted) has the same squares, vision matrix and cutaway flags, so its occluders are unchanged;
    * with Config.OCCLUSION_SKIP_LIGHTING_ONLY such frames keep the previous grid, exactly like a frame with no dirty
    * level does in stock. Any other dirty reason still rebuilds.
    */
   private boolean pzoptHasDirtyChunkTexturesForOcclusion(int playerIndex) {
      if (!pzopt.Config.OCCLUSION_SKIP_LIGHTING_ONLY || !pzopt.Overrides.enabled()) {
         return this.hasAnyDirtyChunkTextures(playerIndex);
      }
      float zoom = Core.getInstance().getZoom(playerIndex);
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      boolean lightingOnly = false;
      for (int i = 0; i < perPlayerData1.onScreenChunks.size(); i++) {
         IsoChunk c = perPlayerData1.onScreenChunks.get(i);
         FBORenderLevels renderLevels = c.getRenderLevels(playerIndex);
         for (int z = c.minLevel; z <= c.maxLevel; z++) {
            if (renderLevels.isOnScreen(z) && renderLevels.isDirty(z, zoom)) {
               // pzopt: bakeScheduler, a held level re-bakes in a later frame; the occluders only change with the levels
               // granted now (the rebuild ran every frame while any re-bake waited: 7 % of late game steps, run td-prof4)
               if (this.pzoptSchedPlanned && pzopt.Config.OCCLUSION_GRANTED_ONLY && !pzopt.BakeScheduler.get(playerIndex).peek(c, renderLevels.getMinLevel(z))) {
                  continue;
               }
               if (renderLevels.isDirty(z, ~32L, zoom)) {
                  return true;
               }
               lightingOnly = true;
            }
         }
      }
      if (lightingOnly) {
         pzoptOcclusionRebuildsSkipped++;
      }
      return false;
   }

   public static long pzoptOcclusionRebuildsSkipped; // pzopt: counter for the log

   private boolean hasAnyDirtyChunkTextures(int playerIndex) {
      float zoom = Core.getInstance().getZoom(playerIndex);
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];

      for (int i = 0; i < perPlayerData1.onScreenChunks.size(); i++) {
         IsoChunk c = perPlayerData1.onScreenChunks.get(i);
         FBORenderLevels renderLevels = c.getRenderLevels(playerIndex);

         for (int z = c.minLevel; z <= c.maxLevel; z++) {
            if (renderLevels.isOnScreen(z) && renderLevels.isDirty(z, zoom)) {
               return true;
            }
         }
      }

      return false;
   }

   /** pzopt: the on-screen chunks within CUTAWAY_RADIUS chunks of the camera character (all of them when the radius is 0). */
   private java.util.ArrayList<IsoChunk> pzoptCutawayVisitChunks(FBORenderCell.PerPlayerData perPlayerData1) {
      int r = pzopt.Overrides.enabled() ? pzopt.Config.CUTAWAY_RADIUS : 0;
      if (r <= 0) {
         return perPlayerData1.onScreenChunks;
      }
      int cx = PZMath.fastfloor(IsoCamera.frameState.camCharacterX / 8.0F);
      int cy = PZMath.fastfloor(IsoCamera.frameState.camCharacterY / 8.0F);
      this.pzoptNearChunks.clear();
      for (int i = 0; i < perPlayerData1.onScreenChunks.size(); i++) {
         IsoChunk c = perPlayerData1.onScreenChunks.get(i);
         if (Math.abs(c.wx - cx) <= r && Math.abs(c.wy - cy) <= r) {
            this.pzoptNearChunks.add(c);
         }
      }
      return this.pzoptNearChunks;
   }

   private void calculateOccludingSquares(int playerIndex) {
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      // pzopt: a clean chunk level (no dirty flag) cannot have changed its occluding squares (every input — cutaway
      // flags, vision matrix, squares — dirties the level when it changes), so its stored mask is replayed into the
      // grid instead of re-testing all 64 squares; only dirty or never-computed levels run the full test
      boolean pzoptFast = pzopt.Config.CUTAWAY_FAST && pzopt.Overrides.enabled();
      float pzoptZoom = pzoptFast ? Core.getInstance().getZoom(playerIndex) : 0.0F;
      int pzoptWidth = perPlayerData1.occludedGridX2 - perPlayerData1.occludedGridX1 + 1;

      for (int i = 0; i < perPlayerData1.onScreenChunks.size(); i++) {
         IsoChunk c = perPlayerData1.onScreenChunks.get(i);
         FBORenderLevels renderLevels = c.getRenderLevels(playerIndex);

         for (int z = c.minLevel; z <= c.maxLevel; z++) {
            if (renderLevels.isOnScreen(z)) {
               int pzoptSlot = z + 32; // pzopt: levels are -32..31
               if (pzoptFast && (c.pzoptOccluderMaskSet & (1L << pzoptSlot)) != 0L && !renderLevels.isDirty(z, pzoptZoom)) {
                  // the stock mask (levelData.occludingSquares) is built with an int shift: bits 32-63 wrap and bit 31
                  // sign-extends, so it is only good for change detection; the exact mask is kept on the chunk (black
                  // rows of tiles along house walls, 2026-09-19; was a map keyed by ChunkLevelData until 2026-09-20)
                  long mask = c.pzoptOccluderMask[pzoptSlot];
                  while (mask != 0L) {
                     int b = Long.numberOfTrailingZeros(mask);
                     mask &= mask - 1L;
                     int zeroX = c.wx * 8 + (b & 7) - z * 3 - perPlayerData1.occludedGridX1;
                     int zeroY = c.wy * 8 + (b >> 3) - z * 3 - perPlayerData1.occludedGridY1;
                     if (zeroX >= 0 && zeroY >= 0 && zeroX < pzoptWidth && zeroY <= perPlayerData1.occludedGridY2 - perPlayerData1.occludedGridY1) {
                        int idx = zeroX + zeroY * pzoptWidth;
                        perPlayerData1.occludedGrid[idx] = PZMath.max(perPlayerData1.occludedGrid[idx], z);
                     }
                  }
                  continue;
               }
               ChunkLevelData levelData = c.getCutawayData().getDataForLevel(z);
               boolean bChanged = levelData.calculateOccludingSquares(
                  playerIndex,
                  perPlayerData1.occludedGridX1,
                  perPlayerData1.occludedGridY1,
                  perPlayerData1.occludedGridX2,
                  perPlayerData1.occludedGridY2,
                  perPlayerData1.occludedGrid
               );
               if (pzoptFast) {
                  c.pzoptOccluderMask[pzoptSlot] = pzoptExactOccluderMask(playerIndex, c, z, levelData);
                  c.pzoptOccluderMaskSet |= 1L << pzoptSlot;
               }
               if (bChanged) {
                  FBORenderOcclusion.getInstance().invalidateOverlappedChunkLevels(playerIndex, c, z);
               }
            }
         }
      }
   }

   /**
    * pzopt: the squares of a chunk level that occlude (same test as ChunkLevelData.calculateOccludingSquares) as an
    * exact 64-bit mask, without the on-screen clip of the stock loop so it stays valid while the camera scrolls.
    */
   private static long pzoptExactOccluderMask(int playerIndex, IsoChunk chunk, int level, ChunkLevelData levelData) {
      IsoGridSquare[] squares = chunk.getSquaresForLevel(level);
      long mask = 0L;
      for (int i = 0; i < 64; i++) {
         IsoGridSquare square = squares[i];
         if (square == null || !levelData.shouldRenderSquare(playerIndex, square)) {
            continue;
         }
         if (!square.getVisionMatrix(0, 0, -1) && chunk.getGridSquare(i % 8, i / 8, square.z - 1) != null) {
            continue;
         }
         mask |= 1L << i;
      }
      return mask;
   }

   // pzopt: occlusionCountParallel. The rendered-squares count of every on-screen level (renderOneLevel recomputed it level
   // by level whenever the occlusion grid changed: every few frames while driving, all levels at once) is counted right
   // after the grid is built, chunks spread over the FrameBatch workers. Reads only: the cutaway square flags and the
   // grid (the stock isOccluded writes FBORenderOcclusion.testValue, so the same test runs here on locals); each task
   // writes its own chunk's counts.
   private boolean pzoptCountsPrecomputed;
   private final java.util.ArrayList<IsoChunk> pzoptCountChunks = new java.util.ArrayList<>();
   public static long pzoptParallelCounts, pzoptParallelCountFrames, pzoptCountChecks, pzoptCountMismatches;

   private boolean pzoptPrecountRenderedSquares(int playerIndex) {
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      java.util.ArrayList<IsoChunk> list = this.pzoptCountChunks;
      list.clear();
      list.addAll(perPlayerData1.onScreenChunks);
      final int[] grid = perPlayerData1.occludedGrid;
      final int gx1 = perPlayerData1.occludedGridX1, gy1 = perPlayerData1.occludedGridY1;
      final int gw = perPlayerData1.occludedGridX2 - gx1 + 1, gh = perPlayerData1.occludedGridY2 - gy1 + 1;
      final boolean cheap = DebugOptions.instance.cheapOcclusionCount.getValue();
      final int per = 8;
      int tasks = (list.size() + per - 1) / per;
      final java.util.concurrent.atomic.AtomicLong counted = new java.util.concurrent.atomic.AtomicLong();
      Throwable t = pzopt.FrameBatch.run(tasks, index -> {
         int end = Math.min(list.size(), (index + 1) * per);
         for (int i = index * per; i < end; i++) {
            IsoChunk c = list.get(i);
            FBORenderLevels renderLevels = c.getRenderLevels(playerIndex);
            for (int z = c.minLevel; z <= c.maxLevel; z++) {
               if (z == renderLevels.getMinLevel(z) && renderLevels.isOnScreen(z)) {
                  renderLevels.setRenderedSquaresCount(z, pzoptCountRendered(playerIndex, c, z, renderLevels, grid, gx1, gy1, gw, gh, cheap));
                  counted.incrementAndGet();
               }
            }
         }
      });
      if (t != null) {
         pzopt.Log.warn("occlusionCountParallel: " + t + "; counting on the game thread");
         return false;
      }
      pzoptParallelCounts += counted.get();
      pzoptParallelCountFrames++;
      return true;
   }

   /** calculateRenderedSquaresCount with FBORenderOcclusion.isOccluded on locals (worker threads). */
   private static int pzoptCountRendered(int playerIndex, IsoChunk chunk, int level, FBORenderLevels renderLevels, int[] grid, int gx1, int gy1,
                                         int gw, int gh, boolean cheap) {
      int minLevel = renderLevels.getMinLevel(level);
      int maxLevel = renderLevels.getMaxLevel(level);
      int renderedSquaresCount = 0;
      for (int z = minLevel; z <= maxLevel; z++) {
         ChunkLevelData chunkLevelData = chunk.getCutawayDataForLevel(z);
         IsoGridSquare[] squares = chunk.getSquaresForLevel(z);
         for (int i = 0; i < squares.length; i++) {
            IsoGridSquare square = squares[i];
            if (chunkLevelData.shouldRenderSquare(playerIndex, square) && !pzoptOccluded(square.x, square.y, z, grid, gx1, gy1, gw, gh)) {
               if (cheap) {
                  return 1;
               }
               renderedSquaresCount++;
            }
         }
      }
      return renderedSquaresCount;
   }

   /** FBORenderOcclusion.isOccluded (the same ten grid cells behind the square, occluded when every one is higher). */
   private static boolean pzoptOccluded(int x, int y, int z, int[] grid, int gx1, int gy1, int gw, int gh) {
      return pzoptOccAux(x, y, z, grid, gx1, gy1, gw, gh) && pzoptOccAux(x - 1, y - 1, z, grid, gx1, gy1, gw, gh)
         && pzoptOccAux(x - 2, y - 2, z, grid, gx1, gy1, gw, gh) && pzoptOccAux(x - 3, y - 3, z, grid, gx1, gy1, gw, gh)
         && pzoptOccAux(x - 1, y, z, grid, gx1, gy1, gw, gh) && pzoptOccAux(x, y - 1, z, grid, gx1, gy1, gw, gh)
         && pzoptOccAux(x - 2, y - 1, z, grid, gx1, gy1, gw, gh) && pzoptOccAux(x - 1, y - 2, z, grid, gx1, gy1, gw, gh)
         && pzoptOccAux(x - 3, y - 2, z, grid, gx1, gy1, gw, gh) && pzoptOccAux(x - 2, y - 3, z, grid, gx1, gy1, gw, gh);
   }

   private static boolean pzoptOccAux(int x, int y, int z, int[] grid, int gx1, int gy1, int gw, int gh) {
      int zeroX = x - z * 3 - gx1;
      int zeroY = y - z * 3 - gy1;
      if (zeroX < 0 || zeroY < 0 || zeroX >= gw || zeroY >= gh) {
         return false;
      }
      return grid[zeroX + zeroY * gw] > z;
   }

   private int calculateRenderedSquaresCount(int playerIndex, IsoChunk chunk, int level) {
      int minLevel = chunk.getRenderLevels(playerIndex).getMinLevel(level);
      int maxLevel = chunk.getRenderLevels(playerIndex).getMaxLevel(level);
      int renderedSquaresCount = 0;

      for (int z = minLevel; z <= maxLevel; z++) {
         ChunkLevelData chunkLevelData = chunk.getCutawayDataForLevel(z);
         IsoGridSquare[] squares = chunk.getSquaresForLevel(z);

         for (int i = 0; i < squares.length; i++) {
            IsoGridSquare square = squares[i];
            if (chunkLevelData.shouldRenderSquare(playerIndex, square) && !FBORenderOcclusion.getInstance().isOccluded(square.x, square.y, z)) {
               if (DebugOptions.instance.cheapOcclusionCount.getValue()) {
                  return 1;
               }

               renderedSquaresCount++;
            }
         }
      }

      return renderedSquaresCount;
   }

   private void prepareChunksForUpdating(int playerIndex) {
      float zoom = Core.getInstance().getZoom(playerIndex);
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];

      for (int i = 0; i < perPlayerData1.onScreenChunks.size(); i++) {
         IsoChunk c = perPlayerData1.onScreenChunks.get(i);
         FBORenderLevels renderLevels = c.getRenderLevels(playerIndex);

         for (int z = c.minLevel; z <= c.maxLevel; z++) {
            if (z == renderLevels.getMinLevel(z) && renderLevels.isOnScreen(z) && renderLevels.isDirty(z, zoom)) {
               if (pzoptZoomRetain && pzopt.ZoomRetain.waiting(c, playerIndex, z)) {
                  continue; // pzopt: zoomRetain, prepared in the frame the plan bakes it (a zoom-out dirties ~200 levels at once)
               }
               if (this.pzoptSchedPlanned && !pzopt.BakeScheduler.get(playerIndex).peek(c, z) && renderLevels.getFBOForLevel(z, zoom) != null
                     && !renderLevels.isDirty(z, 512L, zoom)) {
                  // pzopt: bakeScheduler, a held level keeps its texture and the square flags of its last preparation (they
                  // match what is on screen); a never-textured level is always prepared: the occlusion count reads these
                  // flags, and one never prepared counted 0 squares, was skipped as occluded and never baked (holes, td-combo6r)
                  continue; // pzopt
               }
               this.prepareChunkForUpdating(playerIndex, c, z);
            }
         }
      }
   }

   private void prepareChunkForUpdating(int playerIndex, IsoChunk chunk, int z) {
      if (chunk != null) {
         FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
         if (renderLevels.isOnScreen(z)) {
            int minLevel = renderLevels.getMinLevel(z);
            int maxLevel = renderLevels.getMaxLevel(z);

            for (int z2 = minLevel; z2 <= maxLevel; z2++) {
               ChunkLevelData levelData = chunk.getCutawayDataForLevel(z2);
               // pzopt: Config.LIGHT_INFO_CHUNK_GATE. cacheLightInfo per square asks the lighting engine (JNI) whether the
               // square is dirty; the engine also answers per chunk level, which stock's own lighting refresh uses as its
               // gate. One chunk-level question replaces 64 square questions when nothing in the level changed.
               boolean pzoptRefresh = !(pzopt.Config.LIGHT_INFO_CHUNK_GATE && pzopt.Overrides.enabled())
                  || LightingJNI.getChunkDirty(playerIndex, chunk.wx, chunk.wy, z2 + 32);
               if (!pzoptRefresh) {
                  pzoptLightInfoLevelsGated++;
               }

               for (int y = 0; y < 8; y++) {
                  for (int x = 0; x < 8; x++) {
                     IsoGridSquare sq = chunk.getGridSquare(x, y, z2);
                     levelData.squareFlags[playerIndex][x + y * 8] = 0;
                     if (sq != null) {
                        // pzopt: once per frame, dirty levels only; a square that never had its light info cached (fresh
                        // chunk whose lighting pass ran before this bake) must be filled regardless of the gate, or the
                        // null test below leaves the whole square out of the texture (black chunk squares, 2026-09-20)
                        if (pzoptRefresh || sq.getLightInfo(playerIndex) == null) pzoptCacheLightInfo(playerIndex, chunk, z2, x + y * 8, sq);
                        if (sq.getLightInfo(playerIndex) != null && this.shouldRenderSquare(sq)) {
                           levelData.squareFlags[playerIndex][x + y * 8] = (byte)(levelData.squareFlags[playerIndex][x + y * 8] | 1);
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private boolean shouldRenderSquare(IsoGridSquare square) {
      if (square == null) {
         return false;
      }

      int playerIndex = IsoCamera.frameState.playerIndex;
      return square.getLightInfo(playerIndex) != null && square.lighting[playerIndex] != null
         ? FBORenderCutaways.getInstance().shouldRenderBuildingSquare(playerIndex, square)
         : false;
   }

   private void renderTranslucentFloorObjects(int playerIndex, int z, Shader floorRenderShader, Shader wallRenderShader, long currentTimeMillis) {
      if (DebugOptions.instance.fboRenderChunk.renderTranslucentFloor.getValue()) {
         this.renderTranslucentOnly = true;
         FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];

         for (int i = 0; i < perPlayerData1.chunksWithTranslucentFloor.size(); i++) {
            IsoChunk chunk = perPlayerData1.chunksWithTranslucentFloor.get(i);
            this.renderOneChunk_TranslucentFloor(chunk, playerIndex, z, floorRenderShader, wallRenderShader, currentTimeMillis);
         }
      }
   }

   private void renderOneChunk_TranslucentFloor(IsoChunk c, int playerIndex, int zza, Shader floorRenderShader, Shader wallRenderShader, long currentTimeMillis) {
      if (c != null) {
         if (!c.lightingNeverDone[playerIndex]) {
            IndieGL.enableDepthTest();
            IndieGL.glDepthFunc(515);
            IndieGL.glDepthMask(false);
            this.renderOneLevel_TranslucentFloor(playerIndex, c, zza);
            IndieGL.glDepthMask(false);
            IndieGL.glDepthFunc(519);
         }
      }
   }

   private void renderOneLevel_TranslucentFloor(int playerIndex, IsoChunk c, int level) {
      FBORenderLevels renderLevels = c.getRenderLevels(playerIndex);
      if (renderLevels.isOnScreen(level)) {
         List<IsoGridSquare> squares = renderLevels.getCachedSquares_TranslucentFloor(level);

         for (int i = 0; i < squares.size(); i++) {
            IsoGridSquare square = squares.get(i);
            if (square.z == level && square.IsOnScreen()) {
               this.renderTranslucentFloor(square);
            }
         }
      }
   }

   private void renderTranslucentObjects(int playerIndex, int z, Shader floorRenderShader, Shader wallRenderShader, long currentTimeMillis) {
      if (DebugOptions.instance.fboRenderChunk.renderTranslucentNonFloor.getValue()) {
         this.renderTranslucentOnly = true;
         FBORenderTrees.current = FBORenderTrees.alloc();
         FBORenderTrees.current.init();
         FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];

         for (int i = 0; i < perPlayerData1.chunksWithTranslucentNonFloor.size(); i++) {
            IsoChunk chunk = perPlayerData1.chunksWithTranslucentNonFloor.get(i);
            this.renderOneChunk_Translucent(chunk, playerIndex, z, floorRenderShader, wallRenderShader, currentTimeMillis);
         }

         SpriteRenderer.instance.drawGeneric(FBORenderTrees.current);
      }
   }

   private void renderOneChunk_Translucent(IsoChunk c, int playerIndex, int zza, Shader floorRenderShader, Shader wallRenderShader, long currentTimeMillis) {
      if (c != null && c.IsOnScreen(true)) {
         if (!c.lightingNeverDone[playerIndex]) {
            IndieGL.enableDepthTest();
            IndieGL.glDepthFunc(515);
            IndieGL.glDepthMask(false);
            this.renderOneLevel_Translucent(playerIndex, c, zza);
            IndieGL.glDepthMask(false);
            IndieGL.glDepthFunc(519);
         }
      }
   }

   private void renderOneLevel_Translucent(int playerIndex, IsoChunk c, int level) {
      FBORenderLevels renderLevels = c.getRenderLevels(playerIndex);
      if (renderLevels.isOnScreen(level)) {
         ChunkLevelData levelData = c.getCutawayDataForLevel(level);
         List<IsoGridSquare> squaresObjects = renderLevels.getCachedSquares_TranslucentNonFloor(level);
         List<IsoGridSquare> squaresCutawayOutlines = renderLevels.getCachedSquares_CutawayWindowFrames(level);
         List<IsoGridSquare> squaresItems = renderLevels.getCachedSquares_Items(level);
         if (squaresObjects.size() + squaresItems.size() + squaresCutawayOutlines.size() != 0) {
            PZArrayList<IsoGridSquare> sorted = this.tempSquares;
            sorted.clear();
            ProfileArea i = GameProfiler.getInstance().profile("Sort");

            try {
               PZArrayUtil.addAll(sorted, squaresItems);

               for (int ix = 0; ix < squaresCutawayOutlines.size(); ix++) {
                  IsoGridSquare square = squaresCutawayOutlines.get(ix);
                  if (!sorted.contains(square)) {
                     sorted.add(square);
                  }
               }

               for (int ix = 0; ix < squaresObjects.size(); ix++) {
                  IsoGridSquare square = squaresObjects.get(ix);
                  if (!sorted.contains(square)) {
                     sorted.add(square);
                  }
               }
            } catch (Throwable var14) {
               if (i != null) {
                  try {
                     i.close();
                  } catch (Throwable var13) {
                     var14.addSuppressed(var13);
                  }
               }

               throw var14;
            }

            if (i != null) {
               i.close();
            }

            this.timSort.doSort(sorted.getElements(), (java.util.Comparator<IsoGridSquare>) (o1, o2) -> { // pzopt: decompiler fix
               int worldRight = IsoWorld.instance.getMetaGrid().getMaxX() * 256;
               int i1 = o1.x + o1.y * worldRight;
               int i2 = o2.x + o2.y * worldRight;
               return i1 - i2;
            }, 0, sorted.size());

            for (int ix = 0; ix < sorted.size(); ix++) {
               IsoGridSquare square = (IsoGridSquare)sorted.get(ix);
               if (square.z == level && levelData.shouldRenderSquare(playerIndex, square) && square.IsOnScreen()) {
                  this.renderTranslucent(square);
                  if (DebugOptions.instance.fboRenderChunk.itemsInChunkTexture.getValue() && !square.getWorldObjects().isEmpty()) {
                     if (square.chunk == c) {
                        this.renderWorldInventoryObjects(square, square, false);
                     } else {
                        IsoGridSquare renderSquare = c.getGridSquare(0, 0, square.z);
                        this.renderWorldInventoryObjects(square, renderSquare, false);
                     }
                  }

                  this.renderTranslucentSE(square);
                  this.renderWindowFrameOutline = true;
                  this.renderCutawayOutline(square, false);
                  this.renderWindowFrameOutline = false;
               }
            }
         }
      }
   }

   private void renderCorpseShadows(int playerIndex) {
      if (DebugOptions.instance.terrain.renderTiles.shadows.getValue()) {
         if (Core.getInstance().getOptionCorpseShadows()) {
            FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];

            for (int i = 0; i < perPlayerData1.onScreenChunks.size(); i++) {
               IsoChunk chunk = perPlayerData1.onScreenChunks.get(i);
               FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);

               for (int z = chunk.minLevel; z <= chunk.maxLevel; z++) {
                  if (renderLevels.isOnScreen(z) && z == renderLevels.getMinLevel(z)) {
                     List<IsoGridSquare> squares = renderLevels.getCachedSquares_Corpses(z);

                     for (int j = 0; j < squares.size(); j++) {
                        IsoGridSquare square = squares.get(j);

                        for (int k = 0; k < square.getStaticMovingObjects().size(); k++) {
                           if (square.getStaticMovingObjects().get(k) instanceof IsoDeadBody deadBody && !square.HasStairs()) {
                              deadBody.renderShadow();
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private void checkMannequinRenderDirection(int playerIndex) {
      for (int i = 0; i < this.mannequinList.size(); i++) {
         IsoMannequin mannequin = this.mannequinList.get(i);
         if (mannequin.getObjectIndex() == -1) {
            this.mannequinList.remove(i--);
         } else {
            mannequin.checkRenderDirection(playerIndex);
         }
      }
   }

   private void renderMannequinShadows(int playerIndex) {
      for (int i = 0; i < this.mannequinList.size(); i++) {
         IsoMannequin mannequin = this.mannequinList.get(i);
         if (mannequin.getObjectIndex() == -1) {
            this.mannequinList.remove(i--);
         } else if (this.shouldRenderSquare(mannequin.getSquare())) {
            mannequin.renderShadow(mannequin.getX() + 0.5F, mannequin.getY() + 0.5F, mannequin.getZ());
            if (mannequin.shouldRenderEachFrame()) {
               ColorInfo lightInfo = mannequin.getSquare().getLightInfo(playerIndex);
               mannequin.render(mannequin.getX(), mannequin.getY(), mannequin.getZ(), lightInfo, true, false, null);
            }
         }
      }
   }

   private void renderOpaqueObjectsEvent(int playerIndex) {
      int buildX;
      int buildY;
      int buildZ;
      if (JoypadManager.instance.getFromPlayer(playerIndex) == null) {
         if (UIManager.getPickedTile() == null) {
            return;
         }

         buildX = PZMath.fastfloor(UIManager.getPickedTile().x);
         buildY = PZMath.fastfloor(UIManager.getPickedTile().y);
         buildZ = PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);
      } else {
         buildX = PZMath.fastfloor(IsoCamera.frameState.camCharacterX);
         buildY = PZMath.fastfloor(IsoCamera.frameState.camCharacterY);
         buildZ = PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);
      }

      if (IsoWorld.instance.isValidSquare(buildX, buildY, buildZ)) {
         IsoGridSquare square = this.cell.getGridSquare(buildX, buildY, buildZ);
         LuaEventManager.triggerEvent("RenderOpaqueObjectsInWorld", playerIndex, buildX, buildY, buildZ, square);
      }
   }

   private void renderPlayers(int playerIndex) {
      if (!GameClient.client) {
         for (int i = 0; i < IsoPlayer.numPlayers; i++) {
            IsoPlayer player = IsoPlayer.players[i];
            if (player != null) {
               this.renderPlayer(playerIndex, player);
            }
         }
      } else {
         for (IsoPlayer player : GameClient.IDToPlayerMap.values()) {
            this.renderPlayer(playerIndex, player);
         }
      }
   }

   private void renderPlayer(int playerIndex, IsoPlayer player) {
      if (this.cell.getObjectList().contains(player)) {
         if (player.getCurrentSquare() != null) {
            if (player.isOnScreen()) {
               if (player.getCurrentSquare().getLightInfo(playerIndex) != null) {
                  if (FBORenderCutaways.getInstance().shouldRenderBuildingSquare(playerIndex, player.getCurrentSquare())) {
                     if (DebugOptions.instance.terrain.renderTiles.shadows.getValue()) {
                        pzopt.CapsuleShadow.add(player); // pzopt: sunShadows, the player's body in this frame's capsule shadow pass
                        pzopt.Ssr.addMoving(player); // pzopt: reflections, the player near water in the frame's moving scatter
                        player.renderShadow(player.getX(), player.getY(), player.getZ());
                     }

                     player.render(
                        player.getX(), player.getY(), player.getZ(), player.getCurrentSquare().getLightInfo(IsoPlayer.getPlayerIndex()), true, false, null
                     );
                     this.debugChunkStateRenderPlayer(player);
                  }
               }
            }
         }
      }
   }

   private void renderMovingObjects() {
      this.renderTranslucentOnly = true;

      java.util.ArrayList<IsoMovingObject> pzoptOnScreen = pzopt.CharDraw.enabled() ? pzopt.CharDraw.join(IsoWorld.instance.getCell().getObjectList(), this) : null;
      if (pzoptOnScreen != null) {
         // pzopt: charDrawPrep. The walk of the cell's objects (CharDraw.walk / start) kept the on-screen ones in the stock
         // order and handed the zombies about to be drawn to the executor; join waited for the tail of that, and the loop
         // below is the stock per-object chain over the on-screen list, finding the data ready in TextureDraw.drawModel.
         int pzoptPlayerIndex = IsoCamera.frameState.playerIndex;

         for (int i = 0; i < pzoptOnScreen.size(); i++) {
            this.pzoptRenderOnScreenObject(pzoptOnScreen.get(i), pzoptPlayerIndex); // the list already passed the three leading tests
         }

         pzopt.CharDraw.finish();
      } else {
         for (IsoMovingObject isoMovingObject : IsoWorld.instance.getCell().getObjectList()) {
            this.renderMovingObject(isoMovingObject);
         }
      }

      this.renderTranslucentOnly = false;
      SpriteRenderer.instance.renderQueued();
   }

   /**
    * pzopt: charDrawPrep. IsoGameCharacter.renderShadow returns without drawing for a scene-culled character that has
    * no active model and is not a fake-dead zombie (every earlier test in it returns too); most of a horde on screen is
    * such zombies, drawn as atlas sprites, so their call is skipped.
    */
   private static boolean pzoptShadowIsNoOp(IsoGameCharacter chr) {
      return pzopt.CharDraw.enabled()
         && chr.isSceneCulled()
         && !chr.hasActiveModel()
         && !(chr instanceof IsoZombie && chr.getCurrentState() == zombie.ai.states.FakeDeadZombieState.instance());
   }

   /** pzopt: charDrawPrep, the moving-object visibility test for pzopt.CharDraw's pre-pass. */
   public boolean pzoptShouldRenderSquare(IsoGridSquare square) {
      return this.shouldRenderSquare(square);
   }

   private void renderMovingObject(IsoMovingObject isoMovingObject) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      if (isoMovingObject.getClass() != IsoPlayer.class) {
         if (isoMovingObject.getCurrentSquare() != null) {
            if (isoMovingObject.isOnScreen()) {
               this.pzoptRenderOnScreenObject(isoMovingObject, playerIndex);
            }
         }
      }
   }

   /**
    * pzopt: charDrawPrep. The rest of renderMovingObject for an object known to be on screen with a square and not a
    * player (the pre-pass's on-screen list, built from those three tests in this frame; nothing changes them during the
    * render). Stock's body from the light-info test on.
    */
   private void pzoptRenderOnScreenObject(IsoMovingObject isoMovingObject, int playerIndex) {
      IsoGridSquare square = isoMovingObject.getCurrentSquare();
      pzopt.Ssr.addMoving(isoMovingObject); // pzopt: reflections, a moving object near water joins the frame's moving scatter
      // pzopt: charDrawPrep. A zombie whose draw data the pre-pass built passed these two square tests on the game
      // thread this frame already (CharDraw.start, nothing changed them since), so they are not asked again.
      boolean pzoptPrepared = isoMovingObject.getClass() == IsoZombie.class
         && pzopt.CharDraw.isPrepared(((IsoZombie)isoMovingObject).legsSprite == null ? null : ((IsoZombie)isoMovingObject).legsSprite.modelSlot);
      if (pzoptPrepared || square.getLightInfo(playerIndex) != null) {
         if (pzoptPrepared || this.shouldRenderSquare(square)) {
            if (DebugOptions.instance.terrain.renderTiles.shadows.getValue()) {
               IsoGameCharacter chr = (IsoGameCharacter)Type.tryCastTo(isoMovingObject, IsoGameCharacter.class);
               if (chr != null && chr.getCurrentSquare() != null && chr.getCurrentSquare().HasStairs() && chr.isRagdoll()) {
                  boolean vehicle = true;
               } else if (chr != null && !pzoptShadowIsNoOp(chr)) { // pzopt: charDrawPrep, the culled atlas zombies' call returns before drawing
                  pzopt.CapsuleShadow.add(chr); // pzopt: sunShadows, this character's body in the frame's capsule shadow pass
                  chr.renderShadow(isoMovingObject.getX(), isoMovingObject.getY(), isoMovingObject.getZ());
               } else if (chr != null) { // pzopt: sunShadows, an atlas zombie (no model, no stock shadow): one upright capsule
                  pzopt.CapsuleShadow.addAtlas(chr); // pzopt
               }

               if (isoMovingObject instanceof BaseVehicle vehicle) {
                  pzopt.CapsuleShadow.addVehicle(vehicle); // pzopt: sunShadows, the vehicle's body in the frame's capsule shadow pass
                  vehicle.renderShadow();
               }
            }

            // pzopt: zombieAtlasFast. A culled zombie drawn as an atlas sprite, or a zombie whose model draw data the
            // pre-pass built, takes the flat copy of its render chain (IsoZombie.pzoptRenderFlat); false = neither
            // case, the stock virtual chain below runs.
            if (isoMovingObject.getClass() == IsoZombie.class
               && ((IsoZombie)isoMovingObject).pzoptRenderFlat(isoMovingObject.getX(), isoMovingObject.getY(), isoMovingObject.getZ(), square.getLightInfo(playerIndex))) {
               return;
            }

            isoMovingObject.render(
               isoMovingObject.getX(), isoMovingObject.getY(), isoMovingObject.getZ(), square.getLightInfo(playerIndex), true, false, null
            );
         }
      }
   }

   private boolean pzoptWaterOnly; // pzopt: HDR glint-only pass: renderWater draws the water shader only

   private void renderWater(int playerIndex) {
      if (DebugOptions.instance.weather.waterPuddles.getValue()
         && DebugOptions.instance.terrain.renderTiles.water.getValue()
         && DebugOptions.instance.terrain.renderTiles.waterBody.getValue()) {
         if (!(IsoCamera.frameState.camCharacterZ < 0.0F)) {
            FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
            IsoChunkMap chunkMap = this.cell.chunkMap[playerIndex];
            int maxZ = chunkMap.maxHeight;

            for (int z = 0; z <= maxZ; z++) {
               this.waterSquares.clear();
               this.waterAttachSquares.clear();
               this.fishSplashSquares.clear();

               for (int i = 0; i < perPlayerData1.onScreenChunks.size(); i++) {
                  IsoChunk chunk = perPlayerData1.onScreenChunks.get(i);
                  if (z >= chunk.minLevel && z <= chunk.maxLevel) {
                     FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
                     if (renderLevels.isOnScreen(z)) {
                        List<IsoGridSquare> squares = renderLevels.getCachedSquares_Water(z);
                        int j = 0;

                        for (int n = squares.size(); j < n; j++) {
                           IsoGridSquare square = squares.get(j);
                           if (square.IsOnScreen() && square.getZ() == z) {
                              IsoObject floor = square.getFloor();
                              if (floor == null || floor.getRenderInfo(playerIndex).layer != ObjectRenderLayer.TranslucentFloor) {
                                 if (IsoWater.getInstance().getShaderEnable() && square.getWater() != null && square.getWater().isValid()) {
                                    this.waterSquares.add(square);
                                 }

                                 if (square.shouldRenderFishSplash(playerIndex)) {
                                    this.fishSplashSquares.add(square);
                                 }
                              }
                           }
                        }

                        squares = renderLevels.getCachedSquares_WaterAttach(z);
                        j = 0;

                        for (int n = squares.size(); j < n; j++) {
                           IsoGridSquare square = squares.get(j);
                           if (square.IsOnScreen() && IsoWater.getInstance().getShaderEnable() && square.getWater() != null && square.getWater().isValid()) {
                              this.waterAttachSquares.add(square);
                           }
                        }
                     }
                  }
               }

               if (!this.waterSquares.isEmpty()) {
                  IsoWater.getInstance().render(this.waterSquares, z);
                  if (DebugOptions.instance.fboRenderChunk.renderWaterFlow.getValue() && !this.pzoptWaterOnly) { // pzopt: HDR glint-only pass
                     this.renderWaterFlow(this.waterSquares);
                  }
               }

               for (int i = 0; i < (this.pzoptWaterOnly ? 0 : this.waterAttachSquares.size()); i++) { // pzopt: HDR glint-only pass draws no sprites
                  IsoGridSquare square = this.waterAttachSquares.get(i);
                  IsoObject[] objects = (IsoObject[])square.getObjects().getElements();
                  int numObjects = square.getObjects().size();

                  for (int j = 0; j < numObjects; j++) {
                     IsoObject object = objects[j];
                     if (object != null
                        && object.getRenderInfo(playerIndex).layer == ObjectRenderLayer.None
                        && object.getAttachedAnimSprite() != null
                        && !object.getAttachedAnimSprite().isEmpty()) {
                        this.renderTranslucentOnly = true;
                        object.renderAttachedAndOverlaySprites(
                           object.getForwardIsoDirection(), square.x, square.y, square.z, square.getLightInfo(playerIndex), true, false, null, null
                        );
                        this.renderTranslucentOnly = false;
                     }
                  }
               }

               if (!this.fishSplashSquares.isEmpty() && !this.pzoptWaterOnly) { // pzopt: HDR glint-only pass draws no sprites
                  this.renderFishSplashes(playerIndex, this.fishSplashSquares);
               }
            }
         }
      }
   }

   private void renderWaterFlow(List<IsoGridSquare> waterSquares) {
      for (int i = 0; i < waterSquares.size(); i++) {
         IsoGridSquare square = waterSquares.get(i);
         IsoWaterGeometry water = square.getWater();
         float flow = (float) Math.PI - (water.getFlow() - (float) (Math.PI / 4));
         float speed = water.getSpeed();
         LineDrawer.DrawIsoLine(
            square.x + 0.5F,
            square.y + 0.5F,
            square.z,
            square.x + 0.5F + (float)Math.cos(flow) * speed,
            square.y + 0.5F + (float)Math.sin(flow) * speed,
            square.z,
            1.0F,
            1.0F,
            1.0F,
            1.0F,
            1
         );
      }
   }

   private void renderWaterShore(int playerIndex) {
      if (DebugOptions.instance.weather.waterPuddles.getValue()
         && DebugOptions.instance.terrain.renderTiles.water.getValue()
         && DebugOptions.instance.terrain.renderTiles.waterShore.getValue()) {
         if (IsoWater.getInstance().getShaderEnable()) {
            if (!(IsoCamera.frameState.camCharacterZ < 0.0F)) {
               FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
               IsoChunkMap chunkMap = this.cell.chunkMap[playerIndex];
               int maxZ = chunkMap.maxHeight;

               for (int z = 0; z <= maxZ; z++) {
                  this.waterSquares.clear();
                  this.waterAttachSquares.clear();

                  for (int i = 0; i < perPlayerData1.onScreenChunks.size(); i++) {
                     IsoChunk chunk = perPlayerData1.onScreenChunks.get(i);
                     if (z >= chunk.minLevel && z <= chunk.maxLevel) {
                        FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
                        if (renderLevels.isOnScreen(z)) {
                           List<IsoGridSquare> squares = renderLevels.getCachedSquares_WaterShore(z);
                           int j = 0;

                           for (int n = squares.size(); j < n; j++) {
                              IsoGridSquare square = squares.get(j);
                              if (square.getZ() == z && square.IsOnScreen() && square.getWater() != null && square.getWater().isbShore()) {
                                 this.waterSquares.add(square);
                                 this.waterAttachSquares.add(square);
                              }
                           }
                        }
                     }
                  }

                  if (!this.waterSquares.isEmpty()) {
                     IsoWater.getInstance().renderShore(this.waterSquares, z);
                  }

                  for (int i = 0; i < this.waterAttachSquares.size(); i++) {
                     IsoGridSquare square = this.waterAttachSquares.get(i);
                     IsoObject[] objects = (IsoObject[])square.getObjects().getElements();
                     int numObjects = square.getObjects().size();

                     for (int j = 0; j < numObjects; j++) {
                        IsoObject object = objects[j];
                        if (object != null
                           && object.getRenderInfo(playerIndex).layer == ObjectRenderLayer.None
                           && object.getAttachedAnimSprite() != null
                           && !object.getAttachedAnimSprite().isEmpty()) {
                           this.renderTranslucentOnly = true;
                           object.renderAttachedAndOverlaySprites(
                              object.getForwardIsoDirection(), square.x, square.y, square.z, square.getLightInfo(playerIndex), true, false, null, null
                           );
                           this.renderTranslucentOnly = false;
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private void renderPuddles(int playerIndex) {
      if (IsoPuddles.getInstance().shouldRenderPuddles()) {
         if (!FBORenderSnow.getInstance().isSnowAnywhere()) {
            IsoChunkMap chunkMap = this.cell.chunkMap[playerIndex];
            int maxZ = chunkMap.maxHeight;
            if (Core.getInstance().getPerfPuddles() > 0) {
               maxZ = 0;
            }

            FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
            if (pzopt.PuddleCache.enabled()) { // pzopt: cached packed vertices per chunk level
               pzopt.PuddleCache.render(playerIndex, perPlayerData1.onScreenChunks, maxZ);
               return;
            }

            for (int z = 0; z <= maxZ; z++) {
               this.waterSquares.clear();

               for (int i = 0; i < perPlayerData1.onScreenChunks.size(); i++) {
                  IsoChunk chunk = perPlayerData1.onScreenChunks.get(i);
                  if (z >= chunk.minLevel && z <= chunk.maxLevel) {
                     FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
                     if (renderLevels.isOnScreen(z)) {
                        List<IsoGridSquare> squares = renderLevels.getCachedSquares_Puddles(z);
                        if (!squares.isEmpty()) {
                           ChunkLevelData levelData = chunk.getCutawayDataForLevel(z);

                           for (int j = 0; j < squares.size(); j++) {
                              IsoGridSquare square = squares.get(j);
                              if (square.getZ() == z && levelData.shouldRenderSquare(playerIndex, square) && square.IsOnScreen()) {
                                 IsoObject floor = square.getFloor();
                                 if (floor != null
                                    && (PerformanceSettings.puddlesQuality >= 2 || floor.getRenderInfo(playerIndex).layer != ObjectRenderLayer.TranslucentFloor)
                                    )
                                  {
                                    IsoPuddlesGeometry puddlesGeometry = square.getPuddles();
                                    if (puddlesGeometry != null && puddlesGeometry.shouldRender()) {
                                       this.waterSquares.add(square);
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }
               }

               IsoPuddles.getInstance().render(this.waterSquares, z);
            }
         }
      }
   }

   private void renderPuddleDebug(int playerIndex) {
      if (IsoPuddles.getInstance().shouldRenderPuddles()) {
         Texture tex = Texture.getSharedTexture("media/textures/Item_Waterdrop_Grey.png");
         if (tex != null) {
            IndieGL.disableDepthTest();
            IndieGL.StartShader(0);
            int maxZ = 0;
            FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];

            for (int z = 0; z <= 0; z++) {
               for (int i = 0; i < perPlayerData1.onScreenChunks.size(); i++) {
                  IsoChunk chunk = perPlayerData1.onScreenChunks.get(i);

                  for (int x = 0; x < 8; x++) {
                     for (int y = 0; y < 8; y++) {
                        IsoGridSquare square = chunk.getGridSquare(x, y, z);
                        if (square != null) {
                           float level = square.getPuddlesInGround();
                           if (!(level <= 0.09F)) {
                              int sqx = square.getX();
                              int sqy = square.getY();
                              float sx = IsoUtils.XToScreen(sqx, sqy, z, 0) - IsoCamera.frameState.offX;
                              float sy = IsoUtils.YToScreen(sqx, sqy, z, 0) - IsoCamera.frameState.offY;
                              sx -= tex.getWidth() / 2.0F * Core.tileScale;
                              sy -= tex.getHeight() / 2.0F * Core.tileScale;
                              float opacity = PZMath.clamp(0.1F + level, 0.2F, 1.0F);
                              SpriteRenderer.instance
                                 .render(
                                    tex, sx, sy, tex.getWidth() * Core.tileScale, tex.getHeight() * Core.tileScale, opacity, opacity, opacity, opacity, null
                                 );
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private void renderPuddlesTranslucentFloorsOnly(int playerIndex, int z) {
      if (IsoPuddles.getInstance().shouldRenderPuddles()) {
         if (Core.getInstance().getPerfPuddles() <= 0 || z == 0) {
            FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
            this.waterSquares.clear();

            for (int i = 0; i < perPlayerData1.onScreenChunks.size(); i++) {
               IsoChunk chunk = perPlayerData1.onScreenChunks.get(i);
               if (z >= chunk.minLevel && z <= chunk.maxLevel) {
                  FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
                  if (renderLevels.isOnScreen(z) && !renderLevels.getCachedSquares_Puddles(z).isEmpty()) {
                     ChunkLevelData levelData = chunk.getCutawayDataForLevel(z);
                     List<IsoGridSquare> squares = renderLevels.getCachedSquares_TranslucentFloor(z);

                     for (int j = 0; j < squares.size(); j++) {
                        IsoGridSquare square = squares.get(j);
                        if (levelData.shouldRenderSquare(playerIndex, square) && square.IsOnScreen()) {
                           IsoObject floor = square.getFloor();
                           if (floor != null && floor.getRenderInfo(playerIndex).layer == ObjectRenderLayer.TranslucentFloor) {
                              IsoPuddlesGeometry puddlesGeometry = square.getPuddles();
                              if (puddlesGeometry != null && puddlesGeometry.shouldRender()) {
                                 this.waterSquares.add(square);
                              }
                           }
                        }
                     }
                  }
               }
            }

            IsoPuddles.getInstance().render(this.waterSquares, z);
         }
      }
   }

   private void renderPuddlesToChunkTexture(int playerIndex, int z, IsoChunk chunk) {
      if (IsoPuddles.getInstance().shouldRenderPuddles()) {
         if (!FBORenderSnow.getInstance().isSnowAnywhere()) {
            if (z >= chunk.minLevel && z <= chunk.maxLevel) {
               if (chunk.getRenderLevels(playerIndex).isOnScreen(z)) {
                  this.waterSquares.clear();
                  ChunkLevelData levelData = chunk.getCutawayDataForLevel(z);
                  IsoGridSquare[] squares = chunk.squares[chunk.squaresIndexOfLevel(z)];

                  for (int j = 0; j < squares.length; j++) {
                     IsoGridSquare square = squares[j];
                     if (levelData.shouldRenderSquare(playerIndex, square)) {
                        IsoObject floor = square.getFloor();
                        if (floor != null && floor.getRenderInfo(playerIndex).layer != ObjectRenderLayer.TranslucentFloor) {
                           IsoPuddlesGeometry puddlesGeometry = square.getPuddles();
                           if (puddlesGeometry != null && puddlesGeometry.shouldRender()) {
                              this.waterSquares.add(square);
                           }
                        }
                     }
                  }

                  IsoPuddles.getInstance().renderToChunkTexture(this.waterSquares, z);
               }
            }
         }
      }
   }

   private void renderRainSplashes(int playerIndex, int z) {
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      IsoChunkMap chunkMap = this.cell.chunkMap[playerIndex];
      this.waterSquares.clear();

      for (int i = 0; i < perPlayerData1.onScreenChunks.size(); i++) {
         IsoChunk chunk = perPlayerData1.onScreenChunks.get(i);
         if (z >= chunk.minLevel && z <= chunk.maxLevel && chunk.getRenderLevels(playerIndex).isOnScreen(z)) {
            IsoChunkLevel levelData = chunk.getLevelData(z);
            if (pzopt.RainSplashes.enabled()) { // pzopt: same update without one Rand.NextBool per idle square per frame
               pzopt.RainSplashes.update(levelData);
               pzopt.RainSplashes.render(levelData, playerIndex);
               continue;
            }
            levelData.updateRainSplashes();
            levelData.renderRainSplashes(playerIndex);
         }
      }
   }

   private void renderFog(int playerIndex) {
      if (!(IsoCamera.frameState.camCharacterZ < 0.0F)) {
         if (PerformanceSettings.fogQuality != 2) {
            if (pzopt.FogPass.enabled()) { // pzopt: the FBO renderer draws the rectangles at endFrame anyway; skip the square walk that only fed the row iterator
               pzopt.GpuSections.begin("fog"); // pzopt: GPU section
               ImprovedFog.getDrawer().startFrame();
               boolean first = true;
               for (int z = 0; z <= 1; z++) {
                  if (ImprovedFog.startRender(playerIndex, z)) {
                     if (first) {
                        first = false;
                        ImprovedFog.startFrame(ImprovedFog.getDrawer());
                     }
                     ImprovedFog.endRender();
                  }
               }
               ImprovedFog.getDrawer().endFrame();
               pzopt.GpuSections.end("fog");
               return;
            }
            FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
            pzopt.GpuSections.begin("fog"); // pzopt: GPU section
            ImprovedFog.getDrawer().startFrame();
            boolean bFirst = true;

            for (int z = 0; z <= 1; z++) {
               if (ImprovedFog.startRender(playerIndex, z)) {
                  if (bFirst) {
                     bFirst = false;
                     ImprovedFog.startFrame(ImprovedFog.getDrawer());
                  }

                  for (int i = 0; i < perPlayerData1.onScreenChunks.size(); i++) {
                     IsoChunk chunk = perPlayerData1.onScreenChunks.get(i);
                     if (z >= chunk.minLevel && z <= chunk.maxLevel) {
                        FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
                        if (renderLevels.isOnScreen(z)) {
                           ChunkLevelData levelData = chunk.getCutawayDataForLevel(z);
                           IsoGridSquare[] squares = chunk.squares[chunk.squaresIndexOfLevel(z)];

                           for (int j = 0; j < squares.length; j++) {
                              IsoGridSquare square = squares[j];
                              if (levelData.shouldRenderSquare(playerIndex, square)) {
                                 IsoObject[] objects = (IsoObject[])square.getObjects().getElements();
                                 int numObjects = square.getObjects().size();

                                 for (int k = 0; k < numObjects; k++) {
                                    IsoObject object = objects[k];
                                    ObjectRenderInfo renderInfo = object.getRenderInfo(playerIndex);
                                    if (renderInfo.layer == ObjectRenderLayer.MinusFloor) {
                                       ProfileArea var17 = GameProfiler.getInstance().profile("ImprovedFog");

                                       try {
                                          ImprovedFog.renderRowsBehind(square);
                                       } catch (Throwable var21) {
                                          if (var17 != null) {
                                             try {
                                                var17.close();
                                             } catch (Throwable var20) {
                                                var21.addSuppressed(var20);
                                             }
                                          }

                                          throw var21;
                                       }

                                       if (var17 != null) {
                                          var17.close();
                                       }
                                       break;
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }

                  ImprovedFog.endRender();
               }
            }

            ImprovedFog.getDrawer().endFrame();
            pzopt.GpuSections.end("fog"); // pzopt: GPU section
         }
      }
   }

   public void handleDelayedLoading(IsoObject object) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      object.getChunk().getRenderLevels(playerIndex).handleDelayedLoading(object);
      if (this.delayedLoadingTimerMs == 0L) {
         this.delayedLoadingTimerMs = System.currentTimeMillis() + 250L;
      }
   }

   private ColorInfo sanitizeLightInfo(int playerIndex, IsoGridSquare square) {
      ColorInfo lightInfo = square.getLightInfo(playerIndex);
      if (lightInfo == null) {
         lightInfo = this.defColorInfo;
      }

      if (DebugOptions.instance.fboRenderChunk.nolighting.getValue()) {
         this.defColorInfo.set(1.0F, 1.0F, 1.0F, lightInfo.a);
         lightInfo = this.defColorInfo;
      }

      return lightInfo;
   }

   private void debugChunkStateRenderPlayer(IsoPlayer player) {
      if (GameWindow.states.current == DebugChunkState.instance) {
         DebugChunkState.instance.drawObjectAtCursor();
         if (DebugChunkState.instance.getBoolean("ObjectAtCursor")) {
            if ("player".equals(DebugChunkState.instance.fromLua1("getObjectAtCursor", "id"))) {
               float gridXf = DebugChunkState.instance.gridXf;
               float gridYf = DebugChunkState.instance.gridYf;
               int mZ = DebugChunkState.instance.z;
               IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(gridXf, gridYf, mZ);
               if (square != null) {
                  float x = player.getX();
                  float y = player.getY();
                  float z = player.getZ();
                  IsoGridSquare psquare = player.getCurrentSquare();
                  float apparentZ = square.getApparentZ(gridXf % 1.0F, gridYf % 1.0F);
                  player.setX(gridXf);
                  player.setY(gridYf);
                  player.setZ(apparentZ);
                  player.setCurrent(square);
                  this.renderDebugChunkState = true;
                  player.render(gridXf, gridYf, apparentZ, new ColorInfo(), true, false, null);
                  this.renderDebugChunkState = false;
                  player.setX(x);
                  player.setY(y);
                  player.setZ(z);
                  player.setCurrent(psquare);
               }
            }
         }
      }
   }

   private boolean checkDebugKeys(int playerIndex, int currentZ) {
      boolean bForceCutawayUpdate = false;
      if (Core.debug && GameKeyboard.isKeyPressed(28)) {
         IsoChunkMap chunkMap = this.cell.getChunkMap(playerIndex);

         for (int y = 0; y < IsoChunkMap.chunkGridWidth; y++) {
            for (int x = 0; x < IsoChunkMap.chunkGridWidth; x++) {
               IsoChunk chunk = chunkMap.getChunk(x, y);
               if (chunk != null && currentZ >= chunk.minLevel && currentZ <= chunk.maxLevel && chunk.IsOnScreen(true)) {
                  FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
                  if (renderLevels.isOnScreen(currentZ)) {
                     renderLevels.invalidateLevel(currentZ, 64L);
                     this.prepareChunkForUpdating(playerIndex, chunk, currentZ);
                     IsoGridSquare[] squares = chunk.squares[chunk.squaresIndexOfLevel(currentZ)];

                     for (int i = 0; i < squares.length; i++) {
                        if (squares[i] != null) {
                           squares[i].setPlayerCutawayFlag(playerIndex, 0, 0L);
                        }
                     }

                     chunk.getCutawayData().invalidateOccludedSquaresMaskForSeenRooms(playerIndex, currentZ);
                  }
               }
            }
         }

         bForceCutawayUpdate = true;
      }

      return bForceCutawayUpdate;
   }

   public void renderSeamFix1_Floor(IsoObject object, float x, float y, float z, ColorInfo stCol, Consumer<TextureDraw> texdModifier) {
      if (PerformanceSettings.fboRenderChunk && DebugOptions.instance.fboRenderChunk.seamFix1.getValue()) {
         IsoGridSquare square = object.getSquare();
         IsoSprite sprite = object.getSprite();
         if (PZMath.coordmodulo(square.y, 8) == 7) {
            IsoGridSquare s = square.getAdjacentSquare(IsoDirections.S);
            if (s != null && s.getFloor() != null) {
               sprite.render(
                  object,
                  x,
                  y,
                  z,
                  object.getForwardIsoDirection(),
                  object.offsetX + 5.0F,
                  object.offsetY + object.getRenderYOffset() * Core.tileScale - 5.0F,
                  stCol,
                  !object.isBlink(),
                  texdModifier
               );
            }
         }

         if (PZMath.coordmodulo(square.x, 8) == 7) {
            IsoGridSquare e = square.getAdjacentSquare(IsoDirections.E);
            if (e != null && e.getFloor() != null) {
               sprite.render(
                  object,
                  x,
                  y,
                  z,
                  object.getForwardIsoDirection(),
                  object.offsetX - 5.0F,
                  object.offsetY + object.getRenderYOffset() * Core.tileScale - 5.0F,
                  stCol,
                  !object.isBlink(),
                  texdModifier
               );
            }
         }
      }
   }

   public void renderSeamFix2_Floor(IsoObject object, float x, float y, float z, ColorInfo stCol, Consumer<TextureDraw> texdModifier) {
      if (!this.renderTranslucentOnly) {
         if (PerformanceSettings.fboRenderChunk && DebugOptions.instance.fboRenderChunk.seamFix2.getValue()) {
            IsoGridSquare square = object.getSquare();
            IsoSprite sprite = object.getSprite();
            IsoGridSquare squareS = square.getAdjacentSquare(IsoDirections.S);
            boolean bShoreS = squareS != null && squareS.getWater() != null && squareS.getWater().isbShore() && IsoWater.getInstance().getShaderEnable();
            if (PZMath.coordmodulo(square.y, 8) == 7 || bShoreS) {
               IsoGridSquare s = square.getAdjacentSquare(IsoDirections.S);
               if (s != null && s.getFloor() != null && (bShoreS || !s.has(IsoFlagType.water) || PerformanceSettings.waterQuality == 2)) {
                  IsoSprite.seamFix2 = Tiles.FloorSouth;
                  if (sprite.getProperties().has(IsoFlagType.FloorHeightOneThird)) {
                     IsoSprite.seamFix2 = Tiles.FloorSouthOneThird;
                  }

                  if (sprite.getProperties().has(IsoFlagType.FloorHeightTwoThirds)) {
                     IsoSprite.seamFix2 = Tiles.FloorSouthTwoThirds;
                  }

                  object.sx = 0.0F;
                  if (bShoreS) {
                     object.renderDepthAdjust = -0.001F;
                  }

                  sprite.render(
                     object,
                     x,
                     y,
                     z,
                     object.getForwardIsoDirection(),
                     object.offsetX + 6.0F,
                     object.offsetY + object.getRenderYOffset() * Core.tileScale - 3.0F,
                     stCol,
                     !object.isBlink(),
                     texdModifier
                  );
                  object.sx = 0.0F;
                  object.renderDepthAdjust = 0.0F;
                  IsoSprite.seamFix2 = null;
               }
            }

            if (PZMath.coordmodulo(square.x, 8) == 7) {
               IsoGridSquare e = square.getAdjacentSquare(IsoDirections.E);
               if (e != null && e.getFloor() != null && (!e.has(IsoFlagType.water) || PerformanceSettings.waterQuality == 2)) {
                  IsoSprite.seamFix2 = Tiles.FloorEast;
                  if (sprite.getProperties().has(IsoFlagType.FloorHeightOneThird)) {
                     IsoSprite.seamFix2 = Tiles.FloorEastOneThird;
                  }

                  if (sprite.getProperties().has(IsoFlagType.FloorHeightTwoThirds)) {
                     IsoSprite.seamFix2 = Tiles.FloorEastTwoThirds;
                  }

                  object.sx = 0.0F;
                  sprite.render(
                     object,
                     x,
                     y,
                     z,
                     object.getForwardIsoDirection(),
                     object.offsetX - 6.0F,
                     object.offsetY + object.getRenderYOffset() * Core.tileScale - 3.0F,
                     stCol,
                     !object.isBlink(),
                     texdModifier
                  );
                  object.sx = 0.0F;
                  IsoSprite.seamFix2 = null;
               }
            }

            IsoGridSquare squareN = square.getAdjacentSquare(IsoDirections.N);
            boolean bShoreN = squareN != null && squareN.getWater() != null && squareN.getWater().isbShore() && IsoWater.getInstance().getShaderEnable();
            if (bShoreN) {
               IsoSprite.seamFix2 = Tiles.FloorSouth;
               object.sx = 0.0F;
               object.renderSquareOverride2 = squareN;
               object.renderDepthAdjust = -0.001F;
               sprite.render(
                  object,
                  x,
                  y - 1.0F,
                  z,
                  object.getForwardIsoDirection(),
                  object.offsetX,
                  object.offsetY + object.getRenderYOffset() * Core.tileScale,
                  stCol,
                  !object.isBlink(),
                  texdModifier
               );
               object.sx = 0.0F;
               object.renderSquareOverride2 = null;
               object.renderDepthAdjust = 0.0F;
               IsoSprite.seamFix2 = null;
            }

            IsoGridSquare squareW = square.getAdjacentSquare(IsoDirections.W);
            boolean bShoreW = squareW != null && squareW.getWater() != null && squareW.getWater().isbShore() && IsoWater.getInstance().getShaderEnable();
            if (bShoreW) {
               IsoSprite.seamFix2 = Tiles.FloorEast;
               object.sx = 0.0F;
               object.renderSquareOverride2 = squareW;
               object.renderDepthAdjust = -0.001F;
               sprite.render(
                  object,
                  x - 1.0F,
                  y,
                  z,
                  object.getForwardIsoDirection(),
                  object.offsetX - 2.0F,
                  object.offsetY - 1.0F + object.getRenderYOffset() * Core.tileScale,
                  stCol,
                  !object.isBlink(),
                  texdModifier
               );
               object.sx = 0.0F;
               object.renderSquareOverride2 = null;
               object.renderDepthAdjust = 0.0F;
               IsoSprite.seamFix2 = null;
            }

            IsoGridSquare squareE = square.getAdjacentSquare(IsoDirections.E);
            boolean bShoreE = squareE != null && squareE.getWater() != null && squareE.getWater().isbShore() && IsoWater.getInstance().getShaderEnable();
            if (bShoreE) {
               IsoSprite.seamFix2 = Tiles.FloorEast;
               object.sx = 0.0F;
               sprite.render(
                  object,
                  x,
                  y,
                  z,
                  object.getForwardIsoDirection(),
                  object.offsetX - 6.0F,
                  object.offsetY + object.getRenderYOffset() * Core.tileScale - 3.0F,
                  stCol,
                  !object.isBlink(),
                  texdModifier
               );
               object.sx = 0.0F;
               IsoSprite.seamFix2 = null;
            }
         }
      }
   }

   public void renderSeamFix1_Wall(IsoObject object, float x, float y, float z, ColorInfo stCol, Consumer<TextureDraw> texdModifier) {
      if (PerformanceSettings.fboRenderChunk && DebugOptions.instance.fboRenderChunk.seamFix1.getValue()) {
         IsoGridSquare square = object.getSquare();
         IsoSprite sprite = object.getSprite();
         if (sprite.getProperties().has(IsoFlagType.WallW) && PZMath.coordmodulo(square.y, 8) == 7) {
            IsoGridSquare s = square.getAdjacentSquare(IsoDirections.S);
            if (s != null && ((s.getWallType() & 4) != 0 || s.getWindowFrame(false) != null || s.has(IsoFlagType.DoorWallW))) {
               sprite.renderWallSliceW(
                  object,
                  x,
                  y,
                  z,
                  object.getForwardIsoDirection(),
                  object.offsetX,
                  object.offsetY + object.getRenderYOffset() * Core.tileScale,
                  stCol,
                  !object.isBlink(),
                  texdModifier
               );
            }
         }

         if (sprite.getProperties().has(IsoFlagType.WallN) && PZMath.coordmodulo(square.x, 8) == 7) {
            IsoGridSquare e = square.getAdjacentSquare(IsoDirections.E);
            if (e != null && ((e.getWallType() & 1) != 0 || e.getWindowFrame(true) != null || e.has(IsoFlagType.DoorWallN))) {
               sprite.renderWallSliceN(
                  object,
                  x,
                  y,
                  z,
                  object.getForwardIsoDirection(),
                  object.offsetX,
                  object.offsetY + object.getRenderYOffset() * Core.tileScale,
                  stCol,
                  !object.isBlink(),
                  texdModifier
               );
            }
         }
      }
   }

   public void renderSeamFix2_Wall(IsoObject object, float x, float y, float z, ColorInfo stCol, Consumer<TextureDraw> texdModifier) {
      if (PerformanceSettings.fboRenderChunk && DebugOptions.instance.fboRenderChunk.seamFix2.getValue()) {
         IsoGridSquare square = object.getSquare();
         IsoSprite sprite = object.getSprite();
         if (!sprite.getProperties().has(IsoFlagType.HoppableN) && !sprite.getProperties().has(IsoFlagType.HoppableW)) {
            if (sprite.tileSheetIndex < 80 || sprite.tileSheetIndex > 82 || sprite.tilesetName == null || !sprite.tilesetName.equals("carpentry_02")) {
               if (sprite.tileSheetIndex < 48 || sprite.tileSheetIndex > 55 || sprite.tilesetName == null || !sprite.tilesetName.equals("walls_logs")) {
                  if (sprite.tilesetName == null || !sprite.tilesetName.equals("walls_logs")) {
                     if (sprite.getProperties().has(IsoFlagType.WallNW) && texdModifier == WallShaperW.instance && PZMath.coordmodulo(square.y, 8) == 7) {
                        IsoGridSquare s = square.getAdjacentSquare(IsoDirections.S);
                        if (s != null && ((s.getWallType() & 4) != 0 || s.getWindowFrame(false) != null || s.has(IsoFlagType.DoorWallW))) {
                           IsoSprite.seamFix2 = Tiles.WallSouth;
                           object.sx = 0.0F;
                           sprite.render(
                              object,
                              x,
                              y,
                              z,
                              IsoDirections.NW,
                              object.offsetX + 6.0F,
                              object.offsetY + object.getRenderYOffset() * Core.tileScale - 3.0F,
                              stCol,
                              !object.isBlink(),
                              texdModifier
                           );
                           object.sx = 0.0F;
                           IsoSprite.seamFix2 = null;
                        }
                     }

                     if (sprite.getProperties().has(IsoFlagType.WallNW) && texdModifier == WallShaperN.instance && PZMath.coordmodulo(square.x, 8) == 7) {
                        IsoGridSquare e = square.getAdjacentSquare(IsoDirections.E);
                        if (e != null && ((e.getWallType() & 1) != 0 || e.getWindowFrame(true) != null || e.has(IsoFlagType.DoorWallN))) {
                           IsoSprite.seamFix2 = Tiles.WallEast;
                           object.sx = 0.0F;
                           sprite.render(
                              object,
                              x,
                              y,
                              z,
                              IsoDirections.NW,
                              object.offsetX - 6.0F,
                              object.offsetY + object.getRenderYOffset() * Core.tileScale - 3.0F,
                              stCol,
                              !object.isBlink(),
                              texdModifier
                           );
                           object.sx = 0.0F;
                           IsoSprite.seamFix2 = null;
                        }
                     }

                     if ((sprite.getProperties().has(IsoFlagType.WallW) || sprite.getProperties().has(IsoFlagType.WindowW))
                        && PZMath.coordmodulo(square.y, 8) == 7) {
                        IsoGridSquare s = square.getAdjacentSquare(IsoDirections.S);
                        if (s != null && ((s.getWallType() & 4) != 0 || s.getWindowFrame(false) != null || s.has(IsoFlagType.DoorWallW))) {
                           IsoSprite.seamFix2 = Tiles.WallSouth;
                           object.sx = 0.0F;
                           sprite.render(
                              object,
                              x,
                              y,
                              z,
                              IsoDirections.W,
                              object.offsetX + 6.0F,
                              object.offsetY + object.getRenderYOffset() * Core.tileScale - 3.0F,
                              stCol,
                              !object.isBlink(),
                              texdModifier
                           );
                           object.sx = 0.0F;
                           IsoSprite.seamFix2 = null;
                        }
                     }

                     if ((sprite.getProperties().has(IsoFlagType.WallN) || sprite.getProperties().has(IsoFlagType.WindowN))
                        && PZMath.coordmodulo(square.x, 8) == 7) {
                        IsoGridSquare e = square.getAdjacentSquare(IsoDirections.E);
                        if (e != null && ((e.getWallType() & 1) != 0 || e.getWindowFrame(true) != null || e.has(IsoFlagType.DoorWallN))) {
                           IsoSprite.seamFix2 = Tiles.WallEast;
                           object.sx = 0.0F;
                           sprite.render(
                              object,
                              x,
                              y,
                              z,
                              IsoDirections.N,
                              object.offsetX - 6.0F,
                              object.offsetY + object.getRenderYOffset() * Core.tileScale - 3.0F,
                              stCol,
                              !object.isBlink(),
                              texdModifier
                           );
                           object.sx = 0.0F;
                           IsoSprite.seamFix2 = null;
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private void checkSeenRooms(IsoPlayer player, int level) {
      if (!GameClient.client) {
         IsoBuilding building = player.getBuilding();
         if (building != null) {
            for (IsoRoom room : building.rooms) {
               if (!room.def.explored && PZMath.abs(room.def.level - level) <= 1) {
                  room.def.explored = true;
                  IsoWorld.instance.getCell().roomSpotted(room);
               }
            }
         }
      }
   }

   private boolean shouldHideFascia(int playerIndex, IsoObject object) {
      IsoGridSquare square = object.getFasciaAttachedSquare();
      return square == null ? false : !FBORenderCutaways.getInstance().shouldRenderBuildingSquare(playerIndex, square);
   }

   private boolean checkBlackedOutBuildings(int playerIndex) {
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      float playerX = IsoCamera.frameState.camCharacterX;
      float playerY = IsoCamera.frameState.camCharacterY;
      Vector2f closestPoint = BaseVehicle.allocVector2f();
      ArrayList<BuildingDef> collapsedBuildings = FBORenderCutaways.getInstance().getCollapsedBuildings();
      boolean bChanged = false;

      for (int i = 0; i < collapsedBuildings.size(); i++) {
         BuildingDef buildingDef = collapsedBuildings.get(i);
         float distSq = buildingDef.getClosestPoint(playerX, playerY, closestPoint);
         int index = perPlayerData1.blackedOutBuildings.indexOf(buildingDef);
         if (index == -1) {
            if (distSq > 100.0F) {
               perPlayerData1.blackedOutBuildings.add(buildingDef);
               buildingDef.setInvalidateCacheForAllChunks(playerIndex, 32L);
               bChanged = true;
            }
         } else if (distSq <= 100.0F) {
            perPlayerData1.blackedOutBuildings.remove(index);
            buildingDef.setInvalidateCacheForAllChunks(playerIndex, 32L);
            bChanged = true;
         }
      }

      BaseVehicle.releaseVector2f(closestPoint);

      for (int i = 0; i < perPlayerData1.blackedOutBuildings.size(); i++) {
         BuildingDef buildingDef = perPlayerData1.blackedOutBuildings.get(i);
         int index = collapsedBuildings.indexOf(buildingDef);
         if (index == -1) {
            perPlayerData1.blackedOutBuildings.remove(i--);
            buildingDef.setInvalidateCacheForAllChunks(playerIndex, 32L);
            bChanged = true;
         }
      }

      return bChanged;
   }

   private void checkBlackedOutRooms(int playerIndex) {
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      ArrayList<VisibleRoom> visibleRooms = LightingJNI.getVisibleRooms(playerIndex);
      if (visibleRooms != null) {
         visibleRooms.forEach(visibleRoom -> {
            if (!perPlayerData1.visibleRooms.contains(visibleRoom)) {
               IsoMetaCell metaCellx = IsoWorld.instance.getMetaGrid().getCellData(visibleRoom.cellX, visibleRoom.cellY);
               RoomDef roomDefx = metaCellx == null ? null : (RoomDef)metaCellx.roomByMetaId.get(visibleRoom.metaId);
               if (roomDefx != null) {
                  roomDefx.setInvalidateCacheForAllChunks(playerIndex, 32L);
               }

               if (this.shouldDarkenIndividualRooms()) {
                  for (int ix = perPlayerData1.fadingRooms.size() - 1; ix >= 0; ix--) {
                     FBORenderCell.FadingRoom fadingRoomx = perPlayerData1.fadingRooms.get(ix);
                     if (fadingRoomx.equals(visibleRoom.cellX, visibleRoom.cellY, visibleRoom.metaId)) {
                        fadingRoomx.release();
                        perPlayerData1.fadingRooms.remove(ix);
                     }
                  }
               }
            }
         });
         perPlayerData1.visibleRooms.forEach(visibleRoom -> {
            if (!visibleRooms.contains(visibleRoom)) {
               if (this.shouldDarkenIndividualRooms()) {
                  FBORenderCell.FadingRoom fadingRoomx = FBORenderCell.FadingRoom.alloc().set(visibleRoom.cellX, visibleRoom.cellY, visibleRoom.metaId);
                  fadingRoomx.startTimeMs = System.currentTimeMillis();
                  fadingRoomx.blackness = 0.0F;
                  perPlayerData1.fadingRooms.add(fadingRoomx);
               }

               IsoMetaCell metaCellx = IsoWorld.instance.getMetaGrid().getCellData(visibleRoom.cellX, visibleRoom.cellY);
               RoomDef roomDefx = metaCellx == null ? null : (RoomDef)metaCellx.roomByMetaId.get(visibleRoom.metaId);
               if (roomDefx != null) {
                  roomDefx.setInvalidateCacheForAllChunks(playerIndex, 32L);
               }
            }
         });
         VisibleRoom.releaseAll(perPlayerData1.visibleRooms);
         perPlayerData1.visibleRooms.clear();

         for (int i = 0; i < visibleRooms.size(); i++) {
            VisibleRoom visibleRoom1 = visibleRooms.get(i);
            VisibleRoom visibleRoom2 = VisibleRoom.alloc().set(visibleRoom1);
            perPlayerData1.visibleRooms.add(visibleRoom2);
         }

         if (this.shouldDarkenIndividualRooms()) {
            for (int i = perPlayerData1.fadingRooms.size() - 1; i >= 0; i--) {
               FBORenderCell.FadingRoom fadingRoom = perPlayerData1.fadingRooms.get(i);
               if (fadingRoom.startTimeMs + blackedOutRoomFadeDurationMs <= System.currentTimeMillis()) {
                  fadingRoom.release();
                  perPlayerData1.fadingRooms.remove(i);
               } else {
                  float ratio = (float)(System.currentTimeMillis() - fadingRoom.startTimeMs) / (float)blackedOutRoomFadeDurationMs;
                  float fade = (int)PZMath.ceil(ratio * 100.0F) / 10 * 0.1F;
                  fade *= blackedOutRoomFadeBlackness;
                  if (fade != fadingRoom.blackness) {
                     fadingRoom.blackness = fade;
                     IsoMetaCell metaCell = IsoWorld.instance.getMetaGrid().getCellData(fadingRoom.cellX, fadingRoom.cellY);
                     RoomDef roomDef = metaCell == null ? null : (RoomDef)metaCell.roomByMetaId.get(fadingRoom.metaId);
                     if (roomDef != null) {
                        roomDef.setInvalidateCacheForAllChunks(playerIndex, 32L);
                     }
                  }
               }
            }
         }
      }
   }

   public boolean shouldDarkenIndividualRooms() {
      return blackedOutRoomFadeBlackness > 0.0F;
   }

   public boolean isBlackedOutBuildingSquare(IsoGridSquare square) {
      if (!PerformanceSettings.fboRenderChunk) {
         return false;
      }

      if (!FBORenderCutaways.getInstance().isAnyBuildingCollapsed()) {
         return false;
      }

      if (square == null) {
         return false;
      }

      BuildingDef buildingDef = square.getBuilding() == null ? null : square.getBuilding().getDef();
      if (buildingDef == null) {
         return false;
      }

      int playerIndex = IsoCamera.frameState.playerIndex;
      if (this.shouldDarkenIndividualRooms()) {
         IsoRoom room = square.getRoom();
         if (room == null) {
            return false;
         }

         int cellX = buildingDef.getCellX();
         int cellY = buildingDef.getCellY();
         long metaID = room.getRoomDef().metaId;
         if (!LightingJNI.isRoomVisible(playerIndex, cellX, cellY, metaID)) {
            return true;
         }
      }

      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      return perPlayerData1.blackedOutBuildings.contains(buildingDef);
   }

   public float getBlackedOutRoomFadeRatio(IsoGridSquare square) {
      if (!this.shouldDarkenIndividualRooms()) {
         return 1.0F;
      }

      if (square == null) {
         return blackedOutRoomFadeBlackness;
      }

      BuildingDef buildingDef = square.getBuilding() == null ? null : square.getBuilding().getDef();
      if (buildingDef == null) {
         return blackedOutRoomFadeBlackness;
      }

      IsoRoom room = square.getRoom();
      if (room == null) {
         return blackedOutRoomFadeBlackness;
      }

      int cellX = buildingDef.getCellX();
      int cellY = buildingDef.getCellY();
      long metaID = room.getRoomDef().metaId;
      int playerIndex = IsoCamera.frameState.playerIndex;
      FBORenderCell.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];

      for (int i = perPlayerData1.fadingRooms.size() - 1; i >= 0; i--) {
         FBORenderCell.FadingRoom fadingRoom = perPlayerData1.fadingRooms.get(i);
         if (fadingRoom.equals(cellX, cellY, metaID)) {
            return fadingRoom.blackness;
         }
      }

      return blackedOutRoomFadeBlackness;
   }

   private void callBeforeWorldRender(int playerIndex) {
      KahluaTable buildingObject = IsoCell.getInstance().getDrag(playerIndex);
      if (buildingObject != null) {
         Object functionObj = buildingObject.rawget("beforeWorldRender");
         if (functionObj instanceof JavaFunction || functionObj instanceof LuaClosure) {
            int buildX = PZMath.fastfloor(IsoCamera.frameState.camCharacterX);
            int buildY = PZMath.fastfloor(IsoCamera.frameState.camCharacterY);
            int buildZ = PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);
            if (JoypadManager.instance.getFromPlayer(playerIndex) == null) {
               buildX = PZMath.fastfloor(UIManager.getPickedTile().x);
               buildY = PZMath.fastfloor(UIManager.getPickedTile().y);
            } else {
               Object xJoypadObj = buildingObject.rawget("xJoypad");
               if (xJoypadObj == null) {
                  buildingObject.rawset("xJoypad", BoxedStaticValues.toDouble(buildX));
                  buildingObject.rawset("yJoypad", BoxedStaticValues.toDouble(buildY));
               }

               buildingObject.rawset("zJoypad", BoxedStaticValues.toDouble(buildZ));
               buildX = ((KahluaTableImpl)buildingObject).rawgetInt("xJoypad");
               buildY = ((KahluaTableImpl)buildingObject).rawgetInt("yJoypad");
            }

            Object[] args = this.L_callBeforeWorldRender;
            args[0] = buildingObject;
            args[1] = buildX;
            args[2] = buildY;
            args[3] = buildZ;
            LuaManager.caller.pcallvoid(LuaManager.thread, functionObj, args);
         }
      }
   }

   public void Reset() {
      for (int i = 0; i < 4; i++) {
         this.perPlayerData[i].reset();
      }
   }

   static final class FadingRoom {
      public int cellX;
      public int cellY;
      public long metaId;
      public long startTimeMs;
      public float blackness;
      private static final ObjectPool<FBORenderCell.FadingRoom> pool = new ObjectPool(FBORenderCell.FadingRoom::new, "FadingRoom.pool");

      FBORenderCell.FadingRoom set(int cellX, int cellY, long metaID) {
         this.cellX = cellX;
         this.cellY = cellY;
         this.metaId = metaID;
         return this;
      }

      public FBORenderCell.FadingRoom set(VisibleRoom other) {
         return this.set(other.cellX, other.cellY, other.metaId);
      }

      @Override
      public boolean equals(Object rhs) {
         return rhs instanceof VisibleRoom other ? this.equals(other.cellX, other.cellY, other.metaId) : false;
      }

      boolean equals(int cellX, int cellY, long metaID) {
         return this.cellX == cellX && this.cellY == cellY && this.metaId == metaID;
      }

      public static FBORenderCell.FadingRoom alloc() {
         return (FBORenderCell.FadingRoom)pool.alloc();
      }

      public void release() {
         pool.release(this);
      }

      public static void releaseAll(List<FBORenderCell.FadingRoom> objs) {
         pool.releaseAll(objs);
      }
   }

   private static final class PerPlayerData {
      private final int playerIndex;
      private int lastZ = Integer.MAX_VALUE;
      private final ArrayList<IsoChunk> onScreenChunks = new ArrayList<>();
      private final ArrayList<IsoChunk> chunksWithAnimatedAttachments = new ArrayList<>();
      private final ArrayList<IsoChunk> chunksWithFlies = new ArrayList<>();
      private final ArrayList<IsoChunk> chunksWithTranslucentFloor = new ArrayList<>();
      private final ArrayList<IsoChunk> chunksWithTranslucentNonFloor = new ArrayList<>();
      private float playerBoundsX;
      private float playerBoundsY;
      private float playerBoundsW;
      private float playerBoundsH;
      private final ArrayList<Location> squaresObscuringPlayer = new ArrayList<>();
      private final ArrayList<Location> fadingInSquares = new ArrayList<>();
      private int lightingUpdateCounter;
      private int occludedGridX1;
      private int occludedGridY1;
      private int occludedGridX2;
      private int occludedGridY2;
      private int[] occludedGrid;
      private boolean occlusionChanged;
      private final ArrayList<BuildingDef> blackedOutBuildings = new ArrayList<>();
      final ArrayList<VisibleRoom> visibleRooms = new ArrayList<>();
      final ArrayList<FBORenderCell.FadingRoom> fadingRooms = new ArrayList<>();

      private PerPlayerData(int playerIndex) {
         this.playerIndex = playerIndex;
      }

      private void addChunkWith_AnimatedAttachments(IsoChunk chunk) {
         if (!this.chunksWithAnimatedAttachments.contains(chunk)) {
            this.chunksWithAnimatedAttachments.add(chunk);
         }
      }

      private void addChunkWith_Flies(IsoChunk chunk) {
         if (!this.chunksWithFlies.contains(chunk)) {
            this.chunksWithFlies.add(chunk);
         }
      }

      private void addChunkWith_TranslucentFloor(IsoChunk chunk) {
         if (!this.chunksWithTranslucentFloor.contains(chunk)) {
            this.chunksWithTranslucentFloor.add(chunk);
         }
      }

      private void addChunkWith_TranslucentNonFloor(IsoChunk chunk) {
         if (!this.chunksWithTranslucentNonFloor.contains(chunk)) {
            this.chunksWithTranslucentNonFloor.add(chunk);
         }
      }

      private boolean isSquareObscuringPlayer(IsoGridSquare square) {
         for (int i = 0; i < this.squaresObscuringPlayer.size(); i++) {
            Location location = this.squaresObscuringPlayer.get(i);
            if (location.equals(square.x, square.y, square.z)) {
               return true;
            }
         }

         return false;
      }

      private boolean isFadingInSquare(IsoGridSquare square) {
         for (int i = 0; i < this.fadingInSquares.size(); i++) {
            Location location = this.fadingInSquares.get(i);
            if (location.equals(square.x, square.y, square.z)) {
               return true;
            }
         }

         return false;
      }

      private boolean isObjectObscuringPlayer(IsoGridSquare square, Texture texture, float offsetX, float offsetY) {
         square.cachedScreenX = IsoUtils.XToScreen(square.x, square.y, square.z, 0);
         square.cachedScreenY = IsoUtils.YToScreen(square.x, square.y, square.z, 0);
         float textureX = square.cachedScreenX - offsetX + texture.getOffsetX();
         float textureY = square.cachedScreenY - offsetY + texture.getOffsetY();
         return textureX < this.playerBoundsX + this.playerBoundsW
            && textureX + texture.getWidth() > this.playerBoundsX
            && textureY < this.playerBoundsY + this.playerBoundsH
            && textureY + texture.getHeight() > this.playerBoundsY;
      }

      private void reset() {
         this.blackedOutBuildings.clear();
         VisibleRoom.releaseAll(this.visibleRooms);
         this.visibleRooms.clear();
         this.chunksWithAnimatedAttachments.clear();
         this.chunksWithFlies.clear();
         this.chunksWithTranslucentFloor.clear();
         this.chunksWithTranslucentNonFloor.clear();
         this.fadingInSquares.clear();
         this.lastZ = Integer.MAX_VALUE;
         this.lightingUpdateCounter = 0;
         this.onScreenChunks.clear();
      }
   }
}
