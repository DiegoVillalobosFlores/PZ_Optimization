package zombie.vispoly;

import gnu.trove.list.array.TFloatArrayList;
import gnu.trove.map.hash.TIntObjectHashMap;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjglx.BufferUtils;
import org.lwjglx.opengl.Display;
import zombie.GameProfiler;
import zombie.GameProfiler.ProfileArea;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.SpriteRenderer;
import zombie.core.Styles.FloatList;
import zombie.core.Styles.FloatList.ExpandStyle;
import zombie.core.math.PZMath;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.Shader;
import zombie.core.opengl.ShaderProgram;
import zombie.core.opengl.VBORenderer;
import zombie.core.rendering.RenderTarget;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureFBO;
import zombie.core.textures.TextureDraw.GenericDrawer;
import zombie.debug.DebugOptions;
import zombie.debug.LineDrawer;
import zombie.input.GameKeyboard;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.LightingJNI;
import zombie.iso.PlayerCamera;
import zombie.iso.Vector2;
import zombie.iso.Vector3;
import zombie.iso.LosUtil.TestResults;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.SpriteDetails.IsoObjectType;
import zombie.iso.fboRenderChunk.FBORenderLevels;
import zombie.iso.objects.IsoTree;
import zombie.popman.ObjectPool;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.Clipper;
import zombie.vehicles.ClipperPolygon;

public final class VisibilityPolygon2 {
   private static VisibilityPolygon2 instance;
   boolean useCircle;
   private final VisibilityPolygon2.Drawer[][] drawers = new VisibilityPolygon2.Drawer[4][3];
   private final ObjectPool<VisibilityPolygon2.VisibilityWall> visibilityWallPool = new ObjectPool(
      VisibilityPolygon2.VisibilityWall::new, "VisibilityPolygon2.visibilityWallPool", 2048
   );
   int dirtyObstacleCounter;

   public static VisibilityPolygon2 getInstance() {
      if (instance == null) {
         instance = new VisibilityPolygon2();
      }

      return instance;
   }

   private VisibilityPolygon2() {
      for (int i = 0; i < 4; i++) {
         for (int j = 0; j < 3; j++) {
            this.drawers[i][j] = new VisibilityPolygon2.Drawer();
         }
      }
   }

   public void renderMain(int playerIndex) {
      if (DebugOptions.instance.fboRenderChunk.renderVisionPolygon.getValue()) {
         int stateIndex = SpriteRenderer.instance.getMainStateIndex();
         VisibilityPolygon2.Drawer drawer = this.drawers[playerIndex][stateIndex];
         drawer.calculateVisibilityPolygon(playerIndex);
         SpriteRenderer.instance.drawGeneric(drawer);
      }
   }

   public void addChunkToWorld(IsoChunk chunk) {
      this.dirtyObstacleCounter++;
   }

   public static final class ChunkData {
      final IsoChunk chunk;
      final TIntObjectHashMap<VisibilityPolygon2.ChunkLevelData> levelData = new TIntObjectHashMap();

      public ChunkData(IsoChunk chunk) {
         this.chunk = chunk;
      }

      public VisibilityPolygon2.ChunkLevelData getDataForLevel(int level) {
         if (level >= -32 && level <= 31) {
            int index = level + 32;
            VisibilityPolygon2.ChunkLevelData levelData = (VisibilityPolygon2.ChunkLevelData)this.levelData.get(index);
            if (levelData == null) {
               levelData = new VisibilityPolygon2.ChunkLevelData(this, level);
               this.levelData.put(index, levelData);
            }

            return levelData;
         } else {
            return null;
         }
      }

      public void removeFromWorld() {
         for (VisibilityPolygon2.ChunkLevelData levelData : this.levelData.valueCollection()) {
            levelData.removeFromWorld();
         }
      }
   }

   public static final class ChunkLevelData {
      private final VisibilityPolygon2.ChunkData chunkData;
      private final int level;
      private int adjacentChunkLoadedCounter = -1;
      private final List<VisibilityPolygon2.VisibilityWall> allWalls = new ArrayList<>();
      private final List<IsoGridSquare> solidSquares = new ArrayList<>();

      ChunkLevelData(VisibilityPolygon2.ChunkData chunkData, int level) {
         this.chunkData = chunkData;
         this.level = level;
      }

      public void invalidate() {
         this.adjacentChunkLoadedCounter = -1;
         VisibilityPolygon2.getInstance().dirtyObstacleCounter++;
      }

      public void recreate() {
         int level = this.level;
         IsoChunk chunk = this.chunkData.chunk;
         ObjectPool<VisibilityPolygon2.VisibilityWall> exteriorWallsPool = VisibilityPolygon2.getInstance().visibilityWallPool;
         exteriorWallsPool.releaseAll(this.allWalls);
         this.allWalls.clear();
         this.solidSquares.clear();
         if (level >= chunk.minLevel && level <= chunk.maxLevel) {
            IsoGridSquare[] squares = chunk.squares[chunk.squaresIndexOfLevel(level)];
            int chunksPerWidth = 8;

            for (int y = 0; y < 8; y++) {
               VisibilityPolygon2.VisibilityWall wall = null;

               for (int x = 0; x < 8; x++) {
                  IsoGridSquare square = squares[x + y * 8];
                  if (this.hasNorthWall(square)) {
                     if (wall == null) {
                        wall = (VisibilityPolygon2.VisibilityWall)exteriorWallsPool.alloc();
                        wall.chunkLevelData = this;
                        wall.x1 = square.x;
                        wall.y1 = square.y;
                     }
                  } else if (wall != null) {
                     wall.x2 = chunk.wx * 8 + x;
                     wall.y2 = chunk.wy * 8 + y;
                     this.allWalls.add(wall);
                     wall = null;
                  }

                  if (square != null) {
                     if (square.has(IsoObjectType.tree)) {
                        IsoTree tree = square.getTree();
                        if (tree != null && tree.getProperties() != null && tree.getProperties().has(IsoFlagType.blocksight)) {
                           this.solidSquares.add(square);
                        }
                     } else if (square.isSolid()) {
                        this.solidSquares.add(square);
                     }
                  }
               }

               if (wall != null) {
                  wall.x2 = chunk.wx * 8 + 8;
                  wall.y2 = chunk.wy * 8 + y;
                  this.allWalls.add(wall);
               }
            }

            for (int x = 0; x < 8; x++) {
               VisibilityPolygon2.VisibilityWall wall = null;

               for (int y = 0; y < 8; y++) {
                  IsoGridSquare square = squares[x + y * 8];
                  if (this.hasWestWall(square)) {
                     if (wall == null) {
                        wall = (VisibilityPolygon2.VisibilityWall)exteriorWallsPool.alloc();
                        wall.chunkLevelData = this;
                        wall.x1 = square.x;
                        wall.y1 = square.y;
                     }
                  } else if (wall != null) {
                     wall.x2 = chunk.wx * 8 + x;
                     wall.y2 = chunk.wy * 8 + y;
                     this.allWalls.add(wall);
                     wall = null;
                  }
               }

               if (wall != null) {
                  wall.x2 = chunk.wx * 8 + x;
                  wall.y2 = chunk.wy * 8 + 8;
                  this.allWalls.add(wall);
               }
            }
         }
      }

      boolean hasNorthWall(IsoGridSquare square) {
         if (square == null) {
            return false;
         } else if (square.isSolid()) {
            return false;
         } else {
            return square.has(IsoObjectType.tree) ? false : square.testVisionAdjacent(0, -1, 0, false, false) == TestResults.Blocked;
         }
      }

      boolean hasWestWall(IsoGridSquare square) {
         if (square == null) {
            return false;
         } else if (square.isSolid()) {
            return false;
         } else {
            return square.has(IsoObjectType.tree) ? false : square.testVisionAdjacent(-1, 0, 0, false, false) == TestResults.Blocked;
         }
      }

      void removeFromWorld() {
         this.adjacentChunkLoadedCounter = -1;
         VisibilityPolygon2.instance.visibilityWallPool.releaseAll(this.allWalls);
         this.allWalls.clear();
         this.solidSquares.clear();
      }
   }

   private static final class Drawer extends GenericDrawer {
      int playerIndex;
      float px;
      float py;
      int pz;
      float visionCone;
      float lookAngleRadians;
      final Vector2 lookVector = new Vector2();
      float px1;
      float py1;
      float px2;
      float py2;
      float dirX1;
      float dirY1;
      float dirX2;
      float dirY2;
      float circleRadius = 40.0F;
      static final ArrayList<Vector3f> circlePoints = new ArrayList<>();
      final VisibilityPolygon2.Partition[] partitions = new VisibilityPolygon2.Partition[8];
      final TFloatArrayList shadows = new TFloatArrayList();
      boolean insideTree;
      int dirtyObstacleCounter = -1;
      boolean clipperPolygonsDirty = true;
      final ArrayList<ClipperPolygon> clipperPolygons = new ArrayList<>();
      static Texture blurTex;
      static Texture blurDepthTex;
      static TextureFBO blurFBO;
      static Shader blurShader;
      static Shader polygonShader;
      static Shader blitShader;
      static int downscale = 2;
      static float shadowBlurRamp = 0.015F;
      float zoom = 1.0F;
      private final VisibilityPolygon2.Drawer.PolygonData polygonData = new VisibilityPolygon2.Drawer.PolygonData();
      private final FloatList shadowVerts = new FloatList(ExpandStyle.Normal, 10000);
      private static final Vector2 edge = new Vector2();
      private static final Vector3[] angles = new Vector3[]{new Vector3(), new Vector3(), new Vector3(), new Vector3()};

      Drawer() {
         for (int i = 0; i < 8; i++) {
            this.partitions[i] = new VisibilityPolygon2.Partition();
            this.partitions[i].minAngle = i * 45;
            this.partitions[i].maxAngle = (i + 1) * 45;
         }

         this.initCirclePoints(this.circleRadius, 32);
      }

      void initCirclePoints(float radius, int segments) {
         this.circleRadius = radius;
         circlePoints.clear();
         float x = 0.0F;
         float y = 0.0F;

         for (int i = 0; i < segments; i++) {
            double angle = Math.toRadians(i * 360.0 / segments);
            double cx = 0.0 + radius * Math.cos(angle);
            double cy = 0.0 + radius * Math.sin(angle);
            circlePoints.add(new Vector3f((float)cx, (float)cy, Vector2.getDirection((float)cx, (float)cy) + (float) Math.PI));
         }
      }

      boolean isDirty(IsoPlayer player) {
         if (this.clipperPolygonsDirty) {
            return true;
         }

         if (this.dirtyObstacleCounter != VisibilityPolygon2.instance.dirtyObstacleCounter) {
            return true;
         }

         if (this.zoom != IsoCamera.frameState.zoom) {
            return true;
         }

         if (this.px == player.getX() && this.py == player.getY() && this.pz == PZMath.fastfloor(player.getZ())) {
            float visionCone = LightingJNI.calculateVisionCone(player);
            if (visionCone != this.visionCone) {
               return true;
            }

            float lookAngleRadians = player.getLookAngleRadians();
            BaseVehicle vehicle = player.getVehicle();
            if (vehicle != null
               && !player.isAiming()
               && !player.isLookingWhileInVehicle()
               && vehicle.isDriver(player)
               && vehicle.getCurrentSpeedKmHour() < -1.0F) {
               lookAngleRadians += (float) Math.PI;
            }

            return lookAngleRadians != this.lookAngleRadians;
         } else {
            return true;
         }
      }

      void calculateVisibilityPolygon(int playerIndex) {
         if (DebugOptions.instance.useNewVisibility.getValue()) {
            this.calculateVisibilityPolygonNew(playerIndex);
         } else {
            this.calculateVisibilityPolygonOld(playerIndex);
         }
      }

      void calculateVisibilityPolygonNew(int playerIndex) {
         this.playerIndex = playerIndex;
         IsoPlayer player = IsoPlayer.players[playerIndex];
         if (this.isDirty(player)) {
            this.insideTree = false;
            this.dirtyObstacleCounter = VisibilityPolygon2.instance.dirtyObstacleCounter;
            this.shadowVerts.clear();
            IsoChunkMap chunkMap = IsoWorld.instance.currentCell.chunkMap[playerIndex];
            int chunkMapMinX = chunkMap.getWorldXMinTiles();
            int chunkMapMinY = chunkMap.getWorldYMinTiles();
            int chunkMapMaxX = chunkMap.getWorldXMaxTiles();
            int chunkMapMaxY = chunkMap.getWorldYMaxTiles();
            this.px = IsoCamera.frameState.camCharacterX;
            this.py = IsoCamera.frameState.camCharacterY;
            this.pz = PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);
            this.zoom = IsoCamera.frameState.zoom;
            this.visionCone = LightingJNI.calculateVisionCone(player);
            this.lookAngleRadians = player.getLookAngleRadians();
            player.getLookVector(this.lookVector);
            this.polygonData.update(playerIndex);
            BaseVehicle vehicle = player.getVehicle();
            if (vehicle != null
               && !player.isAiming()
               && !player.isLookingWhileInVehicle()
               && vehicle.isDriver(player)
               && vehicle.getCurrentSpeedKmHour() < -1.0F) {
               this.lookAngleRadians += (float) Math.PI;
               this.lookVector.rotate((float) Math.PI);
            }

            IsoGridSquare playerSquare = IsoPlayer.players[playerIndex].getCurrentSquare();
            if (playerSquare != null) {
               IsoTree tree = playerSquare.getTree();
               if (tree != null && tree.getProperties() != null && tree.getProperties().has(IsoFlagType.blocksight)) {
                  this.insideTree = true;
               }

               if (this.insideTree) {
                  this.addFullScreenShadow();
                  this.clipperPolygonsDirty = true;
               } else {
                  this.addViewShadow();
                  IsoChunk[] chunks = chunkMap.getChunks();

                  for (int c = 0; c < chunks.length; c++) {
                     IsoChunk chunk = chunks[c];
                     if (chunk != null && chunk.loaded && !chunk.lightingNeverDone[playerIndex]) {
                        FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
                        if (renderLevels.isOnScreen(this.pz)) {
                           VisibilityPolygon2.ChunkLevelData chunkLevelData = chunk.getVispolyDataForLevel(this.pz);
                           if (chunkLevelData != null && chunk.IsOnScreen(false)) {
                              if (chunkLevelData.adjacentChunkLoadedCounter != chunk.adjacentChunkLoadedCounter) {
                                 chunkLevelData.adjacentChunkLoadedCounter = chunk.adjacentChunkLoadedCounter;
                                 chunkLevelData.recreate();
                              }

                              for (int i = 0; i < chunkLevelData.allWalls.size(); i++) {
                                 VisibilityPolygon2.VisibilityWall wall = chunkLevelData.allWalls.get(i);
                                 if ((
                                       wall.isHorizontal()
                                          ? wall.y1 != chunkMapMinY && wall.y1 != chunkMapMaxY
                                          : wall.x1 != chunkMapMinX && wall.x1 != chunkMapMaxX
                                    )
                                    && this.isInViewCone(wall.x1, wall.y1, wall.x2, wall.y2)) {
                                    this.addWallShadow(wall);
                                 }
                              }

                              for (int i = 0; i < chunkLevelData.solidSquares.size(); i++) {
                                 IsoGridSquare square = chunkLevelData.solidSquares.get(i);
                                 if (this.isInViewCone(square)) {
                                    this.addSquareShadow(square);
                                 }
                              }
                           }
                        }
                     }
                  }

                  this.clipperPolygonsDirty = true;
               }
            }
         }
      }

      void calculateVisibilityPolygonOld(int playerIndex) {
         this.playerIndex = playerIndex;
         IsoPlayer player = IsoPlayer.players[playerIndex];
         if (Core.debug && GameKeyboard.isKeyDown(28) || this.isDirty(player)) {
            this.clipperPolygonsDirty = true;
            this.dirtyObstacleCounter = VisibilityPolygon2.instance.dirtyObstacleCounter;
            this.visionCone = LightingJNI.calculateVisionCone(player);
            this.lookAngleRadians = player.getLookAngleRadians();
            player.getLookVector(this.lookVector);
            BaseVehicle vehicle = player.getVehicle();
            if (vehicle != null
               && !player.isAiming()
               && !player.isLookingWhileInVehicle()
               && vehicle.isDriver(player)
               && vehicle.getCurrentSpeedKmHour() < -1.0F) {
               this.lookAngleRadians += (float) Math.PI;
               this.lookVector.rotate((float) Math.PI);
            }

            this.shadows.clear();
            this.insideTree = false;
            HashSet<VisibilityPolygon2.Segment> segmentHashSet = VisibilityPolygon2.L_calculateVisibilityPolygon.segmentHashSet;
            segmentHashSet.clear();

            for (int i = 0; i < this.partitions.length; i++) {
               segmentHashSet.addAll(this.partitions[i].segments);
               this.partitions[i].segments.clear();
            }

            VisibilityPolygon2.L_calculateVisibilityPolygon.segmentList.clear();
            VisibilityPolygon2.L_calculateVisibilityPolygon.segmentList.addAll(segmentHashSet);
            VisibilityPolygon2.L_calculateVisibilityPolygon.m_segmentPool.releaseAll(VisibilityPolygon2.L_calculateVisibilityPolygon.segmentList);
            IsoChunkMap chunkMap = IsoWorld.instance.currentCell.chunkMap[playerIndex];
            int chunkMapMinX = chunkMap.getWorldXMinTiles();
            int chunkMapMinY = chunkMap.getWorldYMinTiles();
            int chunkMapMaxX = chunkMap.getWorldXMaxTiles();
            int chunkMapMaxY = chunkMap.getWorldYMaxTiles();
            this.px = IsoCamera.frameState.camCharacterX;
            this.py = IsoCamera.frameState.camCharacterY;
            this.pz = PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);
            float cone = -this.visionCone;
            float lookAngleRadians = this.lookAngleRadians;
            float direction1 = lookAngleRadians + (float)Math.acos(cone);
            float direction2 = lookAngleRadians - (float)Math.acos(cone);
            this.dirX1 = (float)Math.cos(direction2);
            this.dirY1 = (float)Math.sin(direction2);
            this.dirX2 = (float)Math.cos(direction1);
            this.dirY2 = (float)Math.sin(direction1);
            this.px1 = this.px + this.dirX1 * 1500.0F;
            this.py1 = this.py + this.dirY1 * 1500.0F;
            this.px2 = this.px + this.dirX2 * 1500.0F;
            this.py2 = this.py + this.dirY2 * 1500.0F;
            IsoGridSquare playerSquare = IsoPlayer.players[playerIndex].getCurrentSquare();
            if (playerSquare != null) {
               IsoTree tree = playerSquare.getTree();
               if (tree != null && tree.getProperties() != null && tree.getProperties().has(IsoFlagType.blocksight)) {
                  this.insideTree = true;
               } else {
                  GameProfiler profiler = GameProfiler.getInstance();
                  ArrayList<VisibilityPolygon2.VisibilityWall> sortedWalls = VisibilityPolygon2.L_calculateVisibilityPolygon.sortedWalls;
                  ArrayList<IsoGridSquare> solidSquares = VisibilityPolygon2.L_calculateVisibilityPolygon.solidSquares;
                  ProfileArea var19 = profiler.profile("Collect");

                  try {
                     sortedWalls.clear();
                     solidSquares.clear();

                     for (int y = 0; y < IsoChunkMap.chunkGridWidth; y++) {
                        for (int x = 0; x < IsoChunkMap.chunkGridWidth; x++) {
                           IsoChunk chunk = chunkMap.getChunk(x, y);
                           if (chunk != null && !chunk.lightingNeverDone[playerIndex] && chunk.loaded && chunk.IsOnScreen(true)) {
                              FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
                              if (renderLevels.isOnScreen(this.pz)) {
                                 VisibilityPolygon2.ChunkLevelData chunkLevelData = chunk.getVispolyDataForLevel(this.pz);
                                 if (chunkLevelData != null) {
                                    if (Core.debug && GameKeyboard.isKeyDown(28)) {
                                       chunkLevelData.invalidate();
                                    }

                                    if (chunkLevelData.adjacentChunkLoadedCounter != chunk.adjacentChunkLoadedCounter) {
                                       chunkLevelData.adjacentChunkLoadedCounter = chunk.adjacentChunkLoadedCounter;
                                       chunkLevelData.recreate();
                                    }

                                    for (int i = 0; i < chunkLevelData.allWalls.size(); i++) {
                                       VisibilityPolygon2.VisibilityWall wall = chunkLevelData.allWalls.get(i);
                                       if ((
                                             wall.isHorizontal()
                                                ? wall.y1 != chunkMapMinY && wall.y1 != chunkMapMaxY
                                                : wall.x1 != chunkMapMinX && wall.x1 != chunkMapMaxX
                                          )
                                          && this.isInViewCone(wall.x1, wall.y1, wall.x2, wall.y2)) {
                                          sortedWalls.add(wall);
                                       }
                                    }

                                    for (int i = 0; i < chunkLevelData.solidSquares.size(); i++) {
                                       IsoGridSquare square = chunkLevelData.solidSquares.get(i);
                                       if (this.isInViewCone(square)) {
                                          solidSquares.add(square);
                                       }
                                    }
                                 }
                              }
                           }
                        }
                     }
                  } catch (Throwable var32) {
                     if (var19 != null) {
                        try {
                           var19.close();
                        } catch (Throwable var29) {
                           var32.addSuppressed(var29);
                        }
                     }

                     throw var32;
                  }

                  if (var19 != null) {
                     var19.close();
                  }

                  var19 = profiler.profile("Walls");

                  try {
                     sortedWalls.sort((o1, o2) -> {
                        float d1 = IsoUtils.DistanceToSquared(o1.x1 + (o1.x2 - o1.x1) * 0.5F, o1.y1 + (o1.y2 - o1.y1) * 0.5F, this.px, this.py);
                        float d2 = IsoUtils.DistanceToSquared(o2.x1 + (o2.x2 - o2.x1) * 0.5F, o2.y1 + (o2.y2 - o2.y1) * 0.5F, this.px, this.py);
                        return Float.compare(d1, d2);
                     });

                     for (int i = 0; i < sortedWalls.size(); i++) {
                        VisibilityPolygon2.VisibilityWall wall = sortedWalls.get(i);
                        float d = 0.0F;
                        if (wall.y1 == wall.y2) {
                           this.addPolygonForLineSegment(this.px, this.py, wall.x1 - 0.0F, wall.y1, wall.x2 + 0.0F, wall.y2);
                        } else {
                           this.addPolygonForLineSegment(this.px, this.py, wall.x1, wall.y1 - 0.0F, wall.x2, wall.y2 + 0.0F);
                        }
                     }
                  } catch (Throwable var31) {
                     if (var19 != null) {
                        try {
                           var19.close();
                        } catch (Throwable var28) {
                           var31.addSuppressed(var28);
                        }
                     }

                     throw var31;
                  }

                  if (var19 != null) {
                     var19.close();
                  }

                  var19 = profiler.profile("Squares");

                  try {
                     solidSquares.sort((o1, o2) -> {
                        float d1 = IsoUtils.DistanceToSquared(o1.x + 0.5F, o1.y + 0.5F, this.px, this.py);
                        float d2 = IsoUtils.DistanceToSquared(o2.x + 0.5F, o2.y + 0.5F, this.px, this.py);
                        return Float.compare(d1, d2);
                     });

                     for (int i = 0; i < solidSquares.size(); i++) {
                        IsoGridSquare square = solidSquares.get(i);
                        if (Vector2.dot(square.x + 0.5F - this.px, square.y - this.py, 0.0F, -1.0F) < 0.0F) {
                           this.addPolygonForLineSegment(this.px, this.py, square.x, square.y, square.x + 1, square.y);
                        }

                        if (Vector2.dot(square.x + 1 - this.px, square.y + 0.5F - this.py, 1.0F, 0.0F) < 0.0F) {
                           this.addPolygonForLineSegment(this.px, this.py, square.x + 1, square.y, square.x + 1, square.y + 1);
                        }

                        if (Vector2.dot(square.x + 0.5F - this.px, square.y + 1 - this.py, 0.0F, 1.0F) < 0.0F) {
                           this.addPolygonForLineSegment(this.px, this.py, square.x + 1, square.y + 1, square.x, square.y + 1);
                        }

                        if (Vector2.dot(square.x - this.px, square.y + 0.5F - this.py, -1.0F, 0.0F) < 0.0F) {
                           this.addPolygonForLineSegment(this.px, this.py, square.x, square.y + 1, square.x, square.y);
                        }
                     }
                  } catch (Throwable var30) {
                     if (var19 != null) {
                        try {
                           var19.close();
                        } catch (Throwable var27) {
                           var30.addSuppressed(var27);
                        }
                     }

                     throw var30;
                  }

                  if (var19 != null) {
                     var19.close();
                  }
               }
            }
         }
      }

      boolean isCollinear(float ax, float ay, float bx, float by, float cx, float cy) {
         float f = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
         return f >= -0.05F && f < 0.05F;
      }

      float getDotWithLookVector(float x, float y) {
         Vector2 v = VisibilityPolygon2.L_calculateVisibilityPolygon.m_tempVector2_1.set(x - this.px, y - this.py);
         v.normalize();
         return v.dot(this.lookVector);
      }

      boolean lineSegmentsIntersects(float sx, float sy, float dirX, float dirY, float aX, float aY, float bX, float bY) {
         float lineSegmentLength = 1500.0F;
         float doaX = sx - aX;
         float doaY = sy - aY;
         float dbaX = bX - aX;
         float dbaY = bY - aY;
         float invDbaDir = 1.0F / (dbaY * dirX - dbaX * dirY);
         float t = (dbaX * doaY - dbaY * doaX) * invDbaDir;
         if (t >= 0.0F && t <= 1500.0F) {
            float t2 = (doaY * dirX - doaX * dirY) * invDbaDir;
            if (t2 >= 0.0F && t2 <= 1.0F) {
               return true;
            }
         }

         return false;
      }

      boolean isInViewCone(float x1, float y1, float x2, float y2) {
         if (!VisibilityPolygon2.instance.useCircle
            || !(IsoUtils.DistanceToSquared(x1, y1, this.px, this.py) >= this.circleRadius * this.circleRadius)
               && !(IsoUtils.DistanceToSquared(x2, y2, this.px, this.py) >= this.circleRadius * this.circleRadius)) {
            float dot1 = this.getDotWithLookVector(x1, y1);
            float dot2 = this.getDotWithLookVector(x2, y2);
            float dot3 = this.getDotWithLookVector(x1 + (x2 - x1) * 0.5F, y1 + (y2 - y1) * 0.5F);
            return Float.compare(dot1, -this.visionCone) < 0 && Float.compare(dot2, -this.visionCone) < 0 && Float.compare(dot3, -this.visionCone) < 0
               ? this.lineSegmentsIntersects(this.px, this.py, this.dirX1, this.dirY1, x1, y1, x2, y2)
                  || this.lineSegmentsIntersects(this.px, this.py, this.dirX2, this.dirY2, x1, y1, x2, y2)
               : true;
         } else {
            return false;
         }
      }

      boolean isInViewCone(IsoGridSquare square) {
         return this.isInViewCone(square.x, square.y, square.x + 1, square.y)
            || this.isInViewCone(square.x + 1, square.y, square.x + 1, square.y + 1)
            || this.isInViewCone(square.x + 1, square.y + 1, square.x, square.y + 1)
            || this.isInViewCone(square.x, square.y + 1, square.x, square.y);
      }

      void addPolygonForLineSegment(float px, float py, float x1, float y1, float x2, float y2) {
         this.addPolygonForLineSegment(px, py, x1, y1, x2, y2, false);
      }

      void addPolygonForLineSegment(float px, float py, float x1, float y1, float x2, float y2, boolean bCollinearHack) {
         if (Core.debug) {
         }

         boolean bDrawLines = false;
         float angle1 = Vector2.getDirection(x1 - px, y1 - py) * (180.0F / (float)Math.PI);
         float angle2 = Vector2.getDirection(x2 - px, y2 - py) * (180.0F / (float)Math.PI);
         if (!bCollinearHack && this.isCollinear(px, py, x1, y1, x2, y2) && Math.abs(angle1 - angle2) < 10.0F) {
            if (bDrawLines) {
               LineDrawer.addLine(x1, y1, this.pz, x2, y2, this.pz, 1.0F, 1.0F, 0.0F, 1.0F);
            }

            Vector2 perp = VisibilityPolygon2.L_calculateVisibilityPolygon.m_tempVector2_1.set(x2 - x1, y2 - y1);
            perp.rotate((float) (Math.PI / 2));
            perp.normalize();
            float d = 0.025F;
            if (IsoUtils.DistanceToSquared(px, py, x1, y1) < IsoUtils.DistanceToSquared(px, py, x2, y2)) {
               this.addPolygonForLineSegment(px, py, x1 - perp.x * 0.025F, y1 - perp.y * 0.025F, x1 + perp.x * 0.025F, y1 + perp.y * 0.025F, true);
            } else {
               this.addPolygonForLineSegment(px, py, x2 - perp.x * 0.025F, y2 - perp.y * 0.025F, x2 + perp.x * 0.025F, y2 + perp.y * 0.025F, true);
            }
         } else {
            VisibilityPolygon2.Segment segment = (VisibilityPolygon2.Segment)VisibilityPolygon2.L_calculateVisibilityPolygon.m_segmentPool.alloc();
            segment.a
               .set(
                  x1,
                  y1,
                  x1 - px,
                  y1 - py,
                  (Vector2.getDirection(x1 - px, y1 - py) + (float) Math.PI) * (180.0F / (float)Math.PI),
                  IsoUtils.DistanceToSquared(x1, y1, px, py)
               );
            segment.b
               .set(
                  x2,
                  y2,
                  x2 - px,
                  y2 - py,
                  (Vector2.getDirection(x2 - px, y2 - py) + (float) Math.PI) * (180.0F / (float)Math.PI),
                  IsoUtils.DistanceToSquared(x2, y2, px, py)
               );
            int partitionMin = PZMath.fastfloor(segment.minAngle() / 45.0F);
            int partitionMax = PZMath.fastfloor(segment.maxAngle() / 45.0F);
            if (segment.maxAngle() - segment.minAngle() > 180.0F) {
               partitionMin = PZMath.fastfloor(segment.maxAngle() / 45.0F);
               partitionMax = PZMath.fastfloor((segment.minAngle() + 360.0F) / 45.0F);
            }

            boolean added = true;

            for (int i = partitionMin; i <= partitionMax; i++) {
               int modI = i % 8;
               if (!this.partitions[modI].addSegment(segment)) {
                  i--;

                  while (i >= partitionMin) {
                     modI = i % 8;
                     this.partitions[modI].segments.remove(segment);
                     i--;
                  }

                  added = false;
                  break;
               }
            }

            if (!added) {
               if (bDrawLines) {
                  LineDrawer.addLine(segment.a.x, segment.a.y, this.pz, segment.b.x, segment.b.y, this.pz, 1.0F, 0.0F, 0.0F, 1.0F);
               }

               VisibilityPolygon2.L_calculateVisibilityPolygon.m_segmentPool.release(segment);
            } else {
               if (bDrawLines) {
                  LineDrawer.addLine(segment.a.x, segment.a.y, this.pz, segment.b.x, segment.b.y, this.pz, 1.0F, 1.0F, 1.0F, 1.0F);
               }

               float radius = 1500.0F;
               float x3 = x1 + segment.a.dirX * 1500.0F;
               float y3 = y1 + segment.a.dirY * 1500.0F;
               float x4 = x2 + segment.b.dirX * 1500.0F;
               float y4 = y2 + segment.b.dirY * 1500.0F;
               IsoChunkMap chunkMap = IsoWorld.instance.currentCell.getChunkMap(this.playerIndex);
               int xMin = chunkMap.getWorldXMinTiles();
               int yMin = chunkMap.getWorldYMinTiles();
               int xMax = chunkMap.getWorldXMaxTiles();
               int yMax = chunkMap.getWorldYMaxTiles();
               Vector2 intersection = VisibilityPolygon2.L_calculateVisibilityPolygon.m_tempVector2_1;
               if (VisibilityPolygon2.instance.useCircle) {
                  intersectLineSegmentWithCircle(x3, y3, px, py, this.circleRadius, intersection);
                  x3 = intersection.x;
                  y3 = intersection.y;
                  intersectLineSegmentWithCircle(x4, y4, px, py, this.circleRadius, intersection);
                  x4 = intersection.x;
                  y4 = intersection.y;
               } else {
                  if (intersectLineSegmentWithAABB(x1, y1, x3, y3, xMin, yMin, xMax, yMax, intersection)) {
                     x3 = intersection.x;
                     y3 = intersection.y;
                  }

                  if (intersectLineSegmentWithAABB(x2, y2, x4, y4, xMin, yMin, xMax, yMax, intersection)) {
                     x4 = intersection.x;
                     y4 = intersection.y;
                  }
               }

               float sum = 0.0F;
               sum += (x2 - x1) * (y2 + y1);
               sum += (x4 - x2) * (y4 + y2);
               sum += (x3 - x4) * (y3 + y4);
               sum += (x1 - x3) * (y1 + y3);
               boolean clockwise = sum > 0.0F;
               if (clockwise) {
                  this.shadows.add(x2);
                  this.shadows.add(y2);
                  this.shadows.add(x1);
                  this.shadows.add(y1);
                  this.shadows.add(x3);
                  this.shadows.add(y3);
                  this.shadows.add(x4);
                  this.shadows.add(y4);
               } else {
                  this.shadows.add(x1);
                  this.shadows.add(y1);
                  this.shadows.add(x2);
                  this.shadows.add(y2);
                  this.shadows.add(x4);
                  this.shadows.add(y4);
                  this.shadows.add(x3);
                  this.shadows.add(y3);
               }

               if (bDrawLines) {
                  LineDrawer.addLine(px, py, this.pz, x3, y3, this.pz, 1.0F, 1.0F, 1.0F, 0.5F);
               }

               if (bDrawLines) {
                  LineDrawer.addLine(px, py, this.pz, x4, y4, this.pz, 1.0F, 1.0F, 1.0F, 0.5F);
               }
            }
         }
      }

      static boolean intersectLineSegments(float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4, Vector2 intersection) {
         float d = (y4 - y3) * (x2 - x1) - (x4 - x3) * (y2 - y1);
         if (d == 0.0F) {
            return false;
         }

         float yd = y1 - y3;
         float xd = x1 - x3;
         float ua = ((x4 - x3) * yd - (y4 - y3) * xd) / d;
         if (!(ua < 0.0F) && !(ua > 1.0F)) {
            float ub = ((x2 - x1) * yd - (y2 - y1) * xd) / d;
            if (!(ub < 0.0F) && !(ub > 1.0F)) {
               if (intersection != null) {
                  intersection.set(x1 + (x2 - x1) * ua, y1 + (y2 - y1) * ua);
               }

               return true;
            } else {
               return false;
            }
         } else {
            return false;
         }
      }

      static boolean intersectLineSegmentWithAABB(float x1, float y1, float x2, float y2, float rx1, float ry1, float rx2, float ry2, Vector2 intersection) {
         if (intersectLineSegments(x1, y1, x2, y2, rx1, ry1, rx2, ry1, intersection)) {
            return true;
         } else if (intersectLineSegments(x1, y1, x2, y2, rx2, ry1, rx2, ry2, intersection)) {
            return true;
         } else {
            return intersectLineSegments(x1, y1, x2, y2, rx2, ry2, rx1, ry2, intersection)
               ? true
               : intersectLineSegments(x1, y1, x2, y2, rx1, ry2, rx1, ry1, intersection);
         }
      }

      static int intersectLineSegmentWithAABBEdge(float x1, float y1, float x2, float y2, float rx1, float ry1, float rx2, float ry2, Vector2 intersection) {
         if (intersectLineSegments(x1, y1, x2, y2, rx1, ry1, rx2, ry1, intersection)) {
            return 1;
         } else if (intersectLineSegments(x1, y1, x2, y2, rx2, ry1, rx2, ry2, intersection)) {
            return 2;
         } else if (intersectLineSegments(x1, y1, x2, y2, rx2, ry2, rx1, ry2, intersection)) {
            return 3;
         } else {
            return intersectLineSegments(x1, y1, x2, y2, rx1, ry2, rx1, ry1, intersection) ? 4 : 0;
         }
      }

      static void intersectLineSegmentWithCircle(float x2, float y2, float cx, float cy, float radius, Vector2 intersection) {
         intersection.set(x2 - cx, y2 - cy);
         intersection.setLength(radius);
         intersection.x += cx;
         intersection.y += cy;
      }

      private void initBlur() {
         int w = Core.width / downscale;
         int h = Core.height / downscale;
         if (blurTex == null || blurTex.getWidth() != w || blurTex.getHeight() != h) {
            if (blurTex != null) {
               blurTex.destroy();
            }

            if (blurDepthTex != null) {
               blurDepthTex.destroy();
            }

            int format = 6403;
            int internalFormat = Display.capabilities.OpenGL30 ? 33321 : 6403;
            blurTex = new Texture(w, h, 16, 6403, internalFormat);
            blurTex.setNameOnly("visBlur");
            blurDepthTex = new Texture(w, h, 512);
            blurDepthTex.setNameOnly("visBlurDepth");
            if (blurFBO == null) {
               blurFBO = new TextureFBO(blurTex, blurDepthTex, false);
            } else {
               blurFBO.startDrawing(false, false);
               blurFBO.attach(blurTex, 36064);
               blurFBO.attach(blurDepthTex, 36096);
               blurFBO.endDrawing();
            }
         }

         if (blurShader == null) {
            blurShader = new Shader("visibilityBlur");
         }

         if (polygonShader == null) {
            polygonShader = new Shader("visPolygon");
         }

         if (blitShader == null) {
            blitShader = new Shader("blitSimple");
         }

         this.polygonData.init();
      }

      private void addFullScreenShadow() {
         IsoChunkMap chunkMap = IsoWorld.instance.currentCell.getChunkMap(this.playerIndex);
         float xMin = chunkMap.getWorldXMinTiles();
         float yMin = chunkMap.getWorldYMinTiles();
         float xMax = chunkMap.getWorldXMaxTiles();
         float yMax = chunkMap.getWorldYMaxTiles();
         this.shadowVerts.add(xMin);
         this.shadowVerts.add(yMin);
         this.shadowVerts.add(0.0F);
         this.shadowVerts.add(xMax);
         this.shadowVerts.add(yMin);
         this.shadowVerts.add(0.0F);
         this.shadowVerts.add(xMax);
         this.shadowVerts.add(yMax);
         this.shadowVerts.add(0.0F);
         this.shadowVerts.add(xMin);
         this.shadowVerts.add(yMin);
         this.shadowVerts.add(0.0F);
         this.shadowVerts.add(xMax);
         this.shadowVerts.add(yMax);
         this.shadowVerts.add(0.0F);
         this.shadowVerts.add(xMin);
         this.shadowVerts.add(yMax);
         this.shadowVerts.add(0.0F);
      }

      private float getLen(float x1, float y1, float x2, float y2) {
         float x = x2 - x1;
         float y = y2 - y1;
         return PZMath.sqrt(x * x + y * y);
      }

      private void addViewShadow() {
         IsoChunkMap chunkMap = IsoWorld.instance.currentCell.getChunkMap(this.playerIndex);
         int xMin = chunkMap.getWorldXMinTiles();
         int yMin = chunkMap.getWorldYMinTiles();
         int xMax = chunkMap.getWorldXMaxTiles();
         int yMax = chunkMap.getWorldYMaxTiles();
         float offset = (float)Math.acos(-this.visionCone);
         float dirL = this.lookAngleRadians - offset;
         float dirR = this.lookAngleRadians + offset;
         float angleXL = (float)Math.cos(dirL);
         float angleYL = (float)Math.sin(dirL);
         float angleXR = (float)Math.cos(dirR);
         float angleYR = (float)Math.sin(dirR);
         float pxL = this.px + angleXL * 1500.0F;
         float pyL = this.py + angleYL * 1500.0F;
         float pxR = this.px + angleXR * 1500.0F;
         float pyR = this.py + angleYR * 1500.0F;
         intersectLineSegmentWithAABBEdge(this.px, this.py, pxR, pyR, xMin, yMin, xMax, yMax, edge);
         pxR = edge.x;
         pyR = edge.y;
         intersectLineSegmentWithAABBEdge(this.px, this.py, pxL, pyL, xMin, yMin, xMax, yMax, edge);
         pxL = edge.x;
         pyL = edge.y;
         float angleL = Vector2.getDirection(pxL - this.px, pyL - this.py);
         float angleR = Vector2.getDirection(pxR - this.px, pyR - this.py);
         float angleTL = Vector2.getDirection(xMin - this.px, yMin - this.py);
         float angleTR = Vector2.getDirection(xMax - this.px, yMin - this.py);
         float angleBR = Vector2.getDirection(xMax - this.px, yMax - this.py);
         float angleBL = Vector2.getDirection(xMin - this.px, yMax - this.py);
         angles[0].set(xMin, yMin, angleTL);
         angles[1].set(xMax, yMin, angleTR);
         angles[2].set(xMax, yMax, angleBR);
         angles[3].set(xMin, yMax, angleBL);
         this.shadowVerts.add(this.px);
         this.shadowVerts.add(this.py);
         this.shadowVerts.add(0.0F);
         this.shadowVerts.add(pxR);
         this.shadowVerts.add(pyR);
         this.shadowVerts.add(this.getLen(this.px, this.py, pxR, pyR) * shadowBlurRamp);
         if (angleL > angleR) {
            for (int i = 0; i < 4; i++) {
               if (angles[i].z > angleR && angles[i].z < angleL) {
                  float len = this.getLen(this.px, this.py, angles[i].x, angles[i].y) * shadowBlurRamp;
                  this.shadowVerts.add(angles[i].x);
                  this.shadowVerts.add(angles[i].y);
                  this.shadowVerts.add(len);
                  this.shadowVerts.add(this.px);
                  this.shadowVerts.add(this.py);
                  this.shadowVerts.add(0.0F);
                  this.shadowVerts.add(angles[i].x);
                  this.shadowVerts.add(angles[i].y);
                  this.shadowVerts.add(len);
               }
            }
         } else {
            for (int i = 0; i < 4; i++) {
               if (angles[i].z > angleR) {
                  float len = this.getLen(this.px, this.py, angles[i].x, angles[i].y) * shadowBlurRamp;
                  this.shadowVerts.add(angles[i].x);
                  this.shadowVerts.add(angles[i].y);
                  this.shadowVerts.add(len);
                  this.shadowVerts.add(this.px);
                  this.shadowVerts.add(this.py);
                  this.shadowVerts.add(0.0F);
                  this.shadowVerts.add(angles[i].x);
                  this.shadowVerts.add(angles[i].y);
                  this.shadowVerts.add(len);
               }
            }

            for (int i = 0; i < 4; i++) {
               if (angles[i].z < angleL) {
                  float len = this.getLen(this.px, this.py, angles[i].x, angles[i].y) * shadowBlurRamp;
                  this.shadowVerts.add(angles[i].x);
                  this.shadowVerts.add(angles[i].y);
                  this.shadowVerts.add(len);
                  this.shadowVerts.add(this.px);
                  this.shadowVerts.add(this.py);
                  this.shadowVerts.add(0.0F);
                  this.shadowVerts.add(angles[i].x);
                  this.shadowVerts.add(angles[i].y);
                  this.shadowVerts.add(len);
               }
            }
         }

         this.shadowVerts.add(pxL);
         this.shadowVerts.add(pyL);
         this.shadowVerts.add(this.getLen(this.px, this.py, pxL, pyL) * shadowBlurRamp);
      }

      private void addLineShadow(float x1, float y1, float x2, float y2) {
         float x1d = x1 - this.px;
         float y1d = y1 - this.py;
         float x2d = x2 - this.px;
         float y2d = y2 - this.py;
         float sqr1 = x1d * x1d + y1d * y1d;
         float mag1 = PZMath.sqrt(PZMath.max(0.01F, sqr1));
         float sqr2 = x2d * x2d + y2d * y2d;
         float mag2 = PZMath.sqrt(PZMath.max(0.01F, sqr2));
         float x1n = x1d / mag1;
         float y1n = y1d / mag1;
         float x2n = x2d / mag2;
         float y2n = y2d / mag2;
         float offsetDist = 70.0F;
         float len = 70.0F * shadowBlurRamp;
         this.shadowVerts.add(x1);
         this.shadowVerts.add(y1);
         this.shadowVerts.add(0.0F);
         this.shadowVerts.add(x2);
         this.shadowVerts.add(y2);
         this.shadowVerts.add(0.0F);
         this.shadowVerts.add(x1 + x1n * 70.0F);
         this.shadowVerts.add(y1 + y1n * 70.0F);
         this.shadowVerts.add(len);
         this.shadowVerts.add(x2);
         this.shadowVerts.add(y2);
         this.shadowVerts.add(0.0F);
         this.shadowVerts.add(x1 + x1n * 70.0F);
         this.shadowVerts.add(y1 + y1n * 70.0F);
         this.shadowVerts.add(len);
         this.shadowVerts.add(x2 + x2n * 70.0F);
         this.shadowVerts.add(y2 + y2n * 70.0F);
         this.shadowVerts.add(len);
      }

      private void addWallShadow(VisibilityPolygon2.VisibilityWall wall) {
         this.addLineShadow(wall.x1, wall.y1, wall.x2, wall.y2);
      }

      private void addSquareShadow(IsoGridSquare square) {
         if (square.x < this.px) {
            this.addLineShadow(square.x + 1, square.y, square.x + 1, square.y + 1);
         } else {
            this.addLineShadow(square.x, square.y, square.x, square.y + 1);
         }

         if (square.y < this.py) {
            this.addLineShadow(square.x, square.y + 1, square.x + 1, square.y + 1);
         } else {
            this.addLineShadow(square.x, square.y, square.x + 1, square.y);
         }
      }

      public void render() {
         if (DebugOptions.instance.useNewVisibility.getValue()) {
            ProfileArea var1 = GameProfiler.getInstance().profile("renderNew");

            try {
               this.renderNew();
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
         } else {
            this.renderOld();
         }
      }

      public void renderNew() {
         this.initBlur();
         this.clipperPolygonsDirty = false;
         this.polygonData.camera = SpriteRenderer.instance.getRenderingPlayerCamera(this.playerIndex);
         GL11.glDepthFunc(515);
         GL11.glDepthMask(true);
         GL11.glEnable(3042);
         GL11.glEnable(2929);
         GL11.glBlendFunc(770, 771);
         GL11.glDisable(2884);
         GL11.glDisable(3008);
         this.renderPolygons();
         GL11.glDepthMask(false);
         if (!DebugOptions.instance.displayVisibilityPolygon.getValue()) {
            this.renderToScreen();
         }

         GL11.glEnable(3008);
         GL11.glDepthFunc(519);
         GLStateRenderThread.restore();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         int screenLeft = IsoCamera.getScreenLeft(this.playerIndex);
         int screenTop = IsoCamera.getScreenTop(this.playerIndex);
         int screenWidth = IsoCamera.getScreenWidth(this.playerIndex);
         int screenHeight = IsoCamera.getScreenHeight(this.playerIndex);
         GL11.glViewport(screenLeft, screenTop, screenWidth, screenHeight);
         pzopt.RenderScale.afterModelDraw(); // pzopt: upscaler, the integer viewport restore dropped the sub-pixel jitter for the rest of the world pass
      }

      public void renderOld() {
         Clipper clipper = VisibilityPolygon2.L_render.m_clipper;
         ByteBuffer clipperBuf = VisibilityPolygon2.L_render.clipperBuf;
         GameProfiler profiler = GameProfiler.getInstance();
         if (!this.clipperPolygons.isEmpty() && !this.clipperPolygonsDirty) {
            this.releaseClipperPolygons(VisibilityPolygon2.L_render.clipperPolygons1);

            for (int i = 0; i < this.clipperPolygons.size(); i++) {
               ClipperPolygon clipperPolygon1 = this.clipperPolygons.get(i);
               ClipperPolygon copy = clipperPolygon1.makeCopy(VisibilityPolygon2.L_render.clipperPolygonPool, VisibilityPolygon2.L_render.floatArrayListPool);
               VisibilityPolygon2.L_render.clipperPolygons1.add(copy);
            }
         } else {
            ProfileArea camera = profiler.profile("Update Dirty");

            try {
               this.releaseClipperPolygons(this.clipperPolygons);
               IsoChunkMap chunkMap = IsoWorld.instance.currentCell.getChunkMap(this.playerIndex);
               int xMin = chunkMap.getWorldXMinTiles();
               int yMin = chunkMap.getWorldYMinTiles();
               int xMax = chunkMap.getWorldXMaxTiles();
               int yMax = chunkMap.getWorldYMaxTiles();
               VisibilityPolygon2.L_addShadowToClipper.angle1cm = Vector2.getDirection(xMin - this.px, yMin - this.py) + (float) Math.PI;
               VisibilityPolygon2.L_addShadowToClipper.angle2cm = Vector2.getDirection(xMin - this.px, yMax - this.py) + (float) Math.PI;
               VisibilityPolygon2.L_addShadowToClipper.angle3cm = Vector2.getDirection(xMax - this.px, yMax - this.py) + (float) Math.PI;
               VisibilityPolygon2.L_addShadowToClipper.angle4cm = Vector2.getDirection(xMax - this.px, yMin - this.py) + (float) Math.PI;
               clipper.clear();
               if (this.insideTree) {
                  this.addShadowToClipper(clipper, clipperBuf, xMin, yMin, xMax, yMin, xMax, yMax, xMin, yMax);
               } else {
                  this.addViewConeToClipper(clipper, clipperBuf);

                  for (int i = 0; i < this.shadows.size(); i += 8) {
                     int n = i;
                     float x1 = this.shadows.getQuick(n++);
                     float y1 = this.shadows.getQuick(n++);
                     float x2 = this.shadows.getQuick(n++);
                     float y2 = this.shadows.getQuick(n++);
                     float x3 = this.shadows.getQuick(n++);
                     float y3 = this.shadows.getQuick(n++);
                     float x4 = this.shadows.getQuick(n++);
                     float y4 = this.shadows.getQuick(n);
                     this.addShadowToClipper(clipper, clipperBuf, x1, y1, x2, y2, x3, y3, x4, y4);
                  }
               }

               int numPolys = clipper.generatePolygons();
               this.releaseClipperPolygons(VisibilityPolygon2.L_render.clipperPolygons1);
               this.getClipperPolygons(clipper, numPolys, VisibilityPolygon2.L_render.clipperPolygons1);

               for (int i = 0; i < VisibilityPolygon2.L_render.clipperPolygons1.size(); i++) {
                  ClipperPolygon clipperPolygon1 = VisibilityPolygon2.L_render.clipperPolygons1.get(i);
                  ClipperPolygon copy = clipperPolygon1.makeCopy(VisibilityPolygon2.L_render.clipperPolygonPool, VisibilityPolygon2.L_render.floatArrayListPool);
                  this.clipperPolygons.add(copy);
               }

               this.clipperPolygonsDirty = false;
            } catch (Throwable var23) {
               if (camera != null) {
                  try {
                     camera.close();
                  } catch (Throwable var21) {
                     var23.addSuppressed(var21);
                  }
               }

               throw var23;
            }

            if (camera != null) {
               camera.close();
            }
         }

         PlayerCamera camera = SpriteRenderer.instance.getRenderingPlayerCamera(this.playerIndex);
         VBORenderer vbor = VBORenderer.getInstance();
         vbor.startRun(vbor.formatPositionColorUvDepth);
         boolean triangulate = true;
         vbor.setMode(4);
         vbor.setDepthTest(true);
         GL11.glDepthFunc(515);
         GL11.glDepthMask(false);
         GL11.glEnable(3042);
         GL11.glBlendFunc(770, 771);
         if (DebugOptions.instance.displayVisibilityPolygon.getValue()) {
            GL11.glPolygonMode(1032, 6913);
         }

         vbor.setTextureID(Texture.getWhite().getTextureId());
         int numSteps = 5;
         ArrayList<ClipperPolygon> polygons = VisibilityPolygon2.L_render.clipperPolygons3;
         float alphaPerStep = 0.0125F;
         VisibilityPolygon2.L_render.triangleIndex = 0;

         for (int step = 0; step < 5; step++) {
            boolean bBlurEdges = true;
            ProfileArea var45 = profiler.profile("Blur");

            try {
               if (step == 4) {
                  for (int i = 0; i < VisibilityPolygon2.L_render.clipperPolygons1.size(); i++) {
                     ClipperPolygon polygon1 = VisibilityPolygon2.L_render.clipperPolygons1.get(i);
                     clipper.clear();
                     this.addClipperPolygon(clipper, clipperBuf, polygon1, false);
                     int numPolys3 = clipper.generatePolygons();
                     this.triangulatePolygons(numPolys3, clipper, clipperBuf, camera, 0.0125F * (step + 1));
                  }
               } else {
                  polygons.clear();

                  for (int i = 0; i < VisibilityPolygon2.L_render.clipperPolygons1.size(); i++) {
                     ClipperPolygon polygon1 = VisibilityPolygon2.L_render.clipperPolygons1.get(i);
                     clipper.clear();
                     this.addClipperPolygon(clipper, clipperBuf, polygon1, false);
                     int numPolys2 = clipper.generatePolygons(-0.06999999999999999, 3);
                     this.releaseClipperPolygons(VisibilityPolygon2.L_render.clipperPolygons2);
                     this.getClipperPolygons(clipper, numPolys2, VisibilityPolygon2.L_render.clipperPolygons2);
                     clipper.clear();
                     this.addClipperPolygon(clipper, clipperBuf, polygon1, false);
                     this.addClipperPolygons(clipper, clipperBuf, VisibilityPolygon2.L_render.clipperPolygons2, true);
                     int numPolys3 = clipper.generatePolygons();
                     this.triangulatePolygons(numPolys3, clipper, clipperBuf, camera, 0.0125F * (step + 1));
                     polygons.addAll(VisibilityPolygon2.L_render.clipperPolygons2);
                     VisibilityPolygon2.L_render.clipperPolygons2.clear();
                  }

                  this.releaseClipperPolygons(VisibilityPolygon2.L_render.clipperPolygons1);
                  VisibilityPolygon2.L_render.clipperPolygons1.addAll(polygons);
               }
            } catch (Throwable var22) {
               if (var45 != null) {
                  try {
                     var45.close();
                  } catch (Throwable var20) {
                     var22.addSuppressed(var20);
                  }
               }

               throw var22;
            }

            if (var45 != null) {
               var45.close();
            }
         }

         vbor.endRun();
         vbor.flush();
         GL11.glDepthFunc(519);
         GL11.glPolygonMode(1032, 6914);
         GLStateRenderThread.restore();
      }

      private void updatePolygons(int start, int end) {
         this.polygonData.reset();
         float[] vertices = this.shadowVerts.array();

         for (int i = start; i < end; i += 3) {
            float x = vertices[i];
            float y = vertices[i + 1];
            float b = vertices[i + 2];
            this.polygonData.addVertex(x, y, b);
         }

         this.polygonData.data.flip();
      }

      private void renderPolygons() {
         GL11.glPushClientAttrib(2);
         if (DebugOptions.instance.displayVisibilityPolygon.getValue()) {
            GL11.glPolygonMode(1032, 6913);
         }

         polygonShader.Start();
         if (!DebugOptions.instance.displayVisibilityPolygon.getValue()) {
            GL11.glBlendFunc(1, 0);
            GL11.glViewport(0, 0, blurFBO.getWidth(), blurFBO.getHeight());
            blurFBO.startDrawing(true, true);
         }

         VertexBufferObject.setModelViewProjection(polygonShader.getProgram());
         int maxSlots = 9216;

         for (int i = 0; i < this.shadowVerts.size(); i += 9216) {
            int count = PZMath.min(9216, this.shadowVerts.size() - i);
            this.updatePolygons(i, i + count);
            this.polygonData.draw();
         }

         if (!DebugOptions.instance.displayVisibilityPolygon.getValue()) {
            blurFBO.endDrawing();
            GL11.glViewport(0, 0, Core.width, Core.height);
            GL11.glBlendFunc(770, 771);
         }

         polygonShader.End();
         if (DebugOptions.instance.displayVisibilityPolygon.getValue()) {
            GL11.glPolygonMode(1032, 6914);
         }

         GL11.glPopClientAttrib();
      }

      private void renderToScreen() {
         if (DebugOptions.instance.previewTiles.getValue()) {
            GL11.glDepthFunc(519);
            blitShader.Start();
            blitShader.getProgram().setValue("Tex", blurTex, 0);
            RenderTarget.DrawFullScreenQuad();
            blitShader.End();
         } else {
            GameProfiler profiler = GameProfiler.getInstance();
            ProfileArea var2 = profiler.profile("BlurShader");

            try {
               TextureFBO osBuffer = Core.getInstance().getOffscreenBuffer(this.playerIndex);
               int osWidth = osBuffer == null ? Core.width : osBuffer.getWidth();
               int osHeight = osBuffer == null ? Core.height : osBuffer.getHeight();
               int divW = IsoPlayer.numPlayers > 1 ? 2 : 1;
               int divH = IsoPlayer.numPlayers > 2 ? 2 : 1;
               int offscreenWidth = osWidth / divW;
               int offscreenHeight = osHeight / divH;
               int screenLeft = IsoCamera.getScreenLeft(this.playerIndex);
               int screenTop = IsoCamera.getScreenTop(this.playerIndex);
               int screenWidth = IsoCamera.getScreenWidth(this.playerIndex);
               int screenHeight = IsoCamera.getScreenHeight(this.playerIndex);
               Shader pzoptScreen = pzopt.VisBlur.reduce(blurTex); // pzopt: visBlurReduce, the 25-tap sum once per vision texel (null = the stock pass)
               Shader pzoptBlur = pzoptScreen != null ? pzoptScreen : blurShader; // pzopt: visBlurReduce
               GL11.glViewport(screenLeft, screenTop, screenWidth, screenHeight);
               pzoptBlur.Start(); // pzopt: visBlurReduce
               ShaderProgram blurProgram = pzoptBlur.getProgram(); // pzopt: visBlurReduce
               if (pzoptScreen != null) { // pzopt: visBlurReduce
                  blurProgram.setValue("reduced", pzopt.VisBlur.sums(), 2); // pzopt
               } // pzopt
               VisibilityPolygon2.L_render.vector2.set(screenWidth, screenHeight);
               blurProgram.setValue("screenSize", VisibilityPolygon2.L_render.vector2);
               VisibilityPolygon2.L_render.vector2.set(screenLeft, screenTop);
               blurProgram.setValue("displayOrigin", VisibilityPolygon2.L_render.vector2);
               VisibilityPolygon2.L_render.vector2.set(pzopt.RenderScale.visBlurPx(offscreenWidth), pzopt.RenderScale.visBlurPx(offscreenHeight)); // pzopt: upscaler, displaySize maps the scaled gl_FragCoord to the depth / vision texture like screenSize and displayOrigin (else the shadow's depth comes from a shrunken copy: a second view cone)
               blurProgram.setValue("displaySize", VisibilityPolygon2.L_render.vector2);
               VisibilityPolygon2.L_render.vector2.set(blurTex.getWidth(), blurTex.getHeight());
               blurProgram.setValue("texSize", VisibilityPolygon2.L_render.vector2);
               VisibilityPolygon2.L_render.vector2.set(blurTex.getWidthHW(), blurTex.getHeightHW());
               blurProgram.setValue("TextureSize", VisibilityPolygon2.L_render.vector2);
               blurProgram.setValue("tex", blurTex, 0);
               blurProgram.setValue("depth", blurDepthTex, 1);
               blurProgram.setValue("zoom", this.zoom);
               blurProgram.setValue("opacityScale", PerformanceSettings.viewConeOpacity);
               ProfileArea var15 = profiler.profile("Render Quad");

               try {
                  RenderTarget.DrawFullScreenQuad();
               } catch (Throwable var20) {
                  if (var15 != null) {
                     try {
                        var15.close();
                     } catch (Throwable var19) {
                        var20.addSuppressed(var19);
                     }
                  }

                  throw var20;
               }

               if (var15 != null) {
                  var15.close();
               }

               pzoptBlur.End(); // pzopt: visBlurReduce
            } catch (Throwable var21) {
               if (var2 != null) {
                  try {
                     var2.close();
                  } catch (Throwable var18) {
                     var21.addSuppressed(var18);
                  }
               }

               throw var21;
            }

            if (var2 != null) {
               var2.close();
            }
         }
      }

      void addViewConeToClipper(Clipper clipper, ByteBuffer clipperBuf) {
         IsoChunkMap chunkMap = IsoWorld.instance.currentCell.getChunkMap(this.playerIndex);
         int xMin = chunkMap.getWorldXMinTiles();
         int yMin = chunkMap.getWorldYMinTiles();
         int xMax = chunkMap.getWorldXMaxTiles();
         int yMax = chunkMap.getWorldYMaxTiles();
         float cone = -this.visionCone;
         float lookAngleRadians = this.lookAngleRadians;
         float direction1 = lookAngleRadians + (float)Math.acos(cone);
         float direction2 = lookAngleRadians - (float)Math.acos(cone);
         float px1 = this.px + (float)Math.cos(direction2) * 1500.0F;
         float py1 = this.py + (float)Math.sin(direction2) * 1500.0F;
         float px2 = this.px + (float)Math.cos(direction1) * 1500.0F;
         float py2 = this.py + (float)Math.sin(direction1) * 1500.0F;
         Vector2 vector2 = VisibilityPolygon2.L_render.vector2;
         if (VisibilityPolygon2.instance.useCircle) {
            intersectLineSegmentWithCircle(px1, py1, this.px, this.py, this.circleRadius, vector2);
            px1 = vector2.x;
            py1 = vector2.y;
            intersectLineSegmentWithCircle(px2, py2, this.px, this.py, this.circleRadius, vector2);
            px2 = vector2.x;
            py2 = vector2.y;
         } else {
            intersectLineSegmentWithAABB(this.px, this.py, px1, py1, xMin, yMin, xMax, yMax, vector2);
            px1 = vector2.x;
            py1 = vector2.y;
            intersectLineSegmentWithAABB(this.px, this.py, px2, py2, xMin, yMin, xMax, yMax, vector2);
            px2 = vector2.x;
            py2 = vector2.y;
         }

         float angle1 = Vector2.getDirection(px1 - this.px, py1 - this.py) + (float) Math.PI;
         float angle2 = Vector2.getDirection(px2 - this.px, py2 - this.py) + (float) Math.PI;
         Vector3f v1 = VisibilityPolygon2.L_render.v1.set(px1, py1, angle1);
         Vector3f v2 = VisibilityPolygon2.L_render.v2.set(px2, py2, angle2);
         if (VisibilityPolygon2.instance.useCircle) {
            for (int i = 0; i < circlePoints.size(); i++) {
               Vector3f v3 = circlePoints.get(i);
               v3.x = v3.x + this.px;
               v3.y = v3.y + this.py;
            }

            ArrayList<Vector3f> sorted = VisibilityPolygon2.L_render.vs;
            sorted.clear();
            sorted.add(v1);
            sorted.add(v2);
            if (angle1 > angle2) {
               for (int i = 0; i < circlePoints.size(); i++) {
                  Vector3f v3 = circlePoints.get(i);
                  if (Float.compare(v3.z, angle1) < 0 && Float.compare(v3.z, angle2) > 0) {
                     sorted.add(v3);
                  }
               }
            } else {
               for (int i = 0; i < circlePoints.size(); i++) {
                  Vector3f v3 = circlePoints.get(i);
                  if (Float.compare(v3.z, angle1) < 0 || Float.compare(v3.z, angle2) > 0) {
                     sorted.add(v3);
                  }
               }
            }

            sorted.sort((o1, o2) -> Float.compare(o1.z, o2.z));
            sorted.add(sorted.indexOf(v2), VisibilityPolygon2.L_addShadowToClipper.v1.set(this.px, this.py, 0.0F));
            clipperBuf.clear();

            for (int i = 0; i < sorted.size(); i++) {
               clipperBuf.putFloat(sorted.get(i).x);
               clipperBuf.putFloat(sorted.get(i).y);
            }

            clipper.addPath(sorted.size(), clipperBuf, false);

            for (int i = 0; i < circlePoints.size(); i++) {
               Vector3f v3 = circlePoints.get(i);
               v3.x = v3.x - this.px;
               v3.y = v3.y - this.py;
            }
         } else {
            Vector3f v3 = VisibilityPolygon2.L_render.v3.set(xMin, yMin, VisibilityPolygon2.L_addShadowToClipper.angle1cm);
            Vector3f v4 = VisibilityPolygon2.L_render.v4.set(xMin, yMax, VisibilityPolygon2.L_addShadowToClipper.angle2cm);
            Vector3f v5 = VisibilityPolygon2.L_render.v5.set(xMax, yMax, VisibilityPolygon2.L_addShadowToClipper.angle3cm);
            Vector3f v6 = VisibilityPolygon2.L_render.v6.set(xMax, yMin, VisibilityPolygon2.L_addShadowToClipper.angle4cm);
            ArrayList<Vector3f> sorted = VisibilityPolygon2.L_render.vs;
            sorted.clear();
            sorted.add(v1);
            sorted.add(v2);
            if (angle1 > angle2) {
               if (Float.compare(v3.z, angle1) < 0 && Float.compare(v3.z, angle2) > 0) {
                  sorted.add(v3);
               }

               if (Float.compare(v4.z, angle1) < 0 && Float.compare(v4.z, angle2) > 0) {
                  sorted.add(v4);
               }

               if (Float.compare(v5.z, angle1) < 0 && Float.compare(v5.z, angle2) > 0) {
                  sorted.add(v5);
               }

               if (Float.compare(v6.z, angle1) < 0 && Float.compare(v6.z, angle2) > 0) {
                  sorted.add(v6);
               }
            } else {
               if (Float.compare(v3.z, angle1) < 0 || Float.compare(v3.z, angle2) > 0) {
                  sorted.add(v3);
               }

               if (Float.compare(v4.z, angle1) < 0 || Float.compare(v4.z, angle2) > 0) {
                  sorted.add(v4);
               }

               if (Float.compare(v5.z, angle1) < 0 || Float.compare(v5.z, angle2) > 0) {
                  sorted.add(v5);
               }

               if (Float.compare(v6.z, angle1) < 0 || Float.compare(v6.z, angle2) > 0) {
                  sorted.add(v6);
               }
            }

            sorted.sort((o1, o2) -> Float.compare(o1.z, o2.z));
            sorted.add(sorted.indexOf(v2), VisibilityPolygon2.L_addShadowToClipper.v1.set(this.px, this.py, 0.0F));
            clipperBuf.clear();

            for (int i = 0; i < sorted.size(); i++) {
               clipperBuf.putFloat(sorted.get(i).x);
               clipperBuf.putFloat(sorted.get(i).y);
            }

            clipper.addPath(sorted.size(), clipperBuf, false);
         }
      }

      void addShadowToClipper(Clipper clipper, ByteBuffer clipperBuf, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4) {
         IsoChunkMap chunkMap = IsoWorld.instance.currentCell.getChunkMap(this.playerIndex);
         int xMin = chunkMap.getWorldXMinTiles();
         int yMin = chunkMap.getWorldYMinTiles();
         int xMax = chunkMap.getWorldXMaxTiles();
         int yMax = chunkMap.getWorldYMaxTiles();
         float angle2 = Vector2.getDirection(x3 - this.px, y3 - this.py) + (float) Math.PI;
         float angle1 = Vector2.getDirection(x4 - this.px, y4 - this.py) + (float) Math.PI;
         Vector3f v1 = VisibilityPolygon2.L_render.v1.set(x3, y3, angle2);
         Vector3f v2 = VisibilityPolygon2.L_render.v2.set(x4, y4, angle1);
         if (VisibilityPolygon2.instance.useCircle) {
            float angleMin = PZMath.min(angle1, angle2);
            float angleMax = PZMath.max(angle1, angle2);

            for (int i = 0; i < circlePoints.size(); i++) {
               Vector3f v3 = circlePoints.get(i);
               v3.x = v3.x + this.px;
               v3.y = v3.y + this.py;
            }

            ArrayList<Vector3f> sorted = VisibilityPolygon2.L_render.vs;
            sorted.clear();
            sorted.add(v1);
            sorted.add(v2);
            clipperBuf.clear();
            if (angleMin < (float) (Math.PI / 2) && angleMax >= (float) (Math.PI * 3.0 / 2.0)) {
               for (int i = 0; i < circlePoints.size(); i++) {
                  Vector3f v3 = circlePoints.get(i);
                  if (Float.compare(v3.z, angleMax) > 0 || Float.compare(v3.z, angleMin) < 0) {
                     sorted.add(v3);
                  }
               }

               sorted.sort((o1, o2) -> Float.compare(o1.z, o2.z));

               for (int i = 0; i < sorted.size(); i++) {
                  Vector3f v = sorted.get(i);
                  if (v.z >= (float) (Math.PI * 3.0 / 2.0)) {
                     clipperBuf.putFloat(v.x);
                     clipperBuf.putFloat(v.y);
                  }
               }

               for (int i = 0; i < sorted.size(); i++) {
                  Vector3f v = sorted.get(i);
                  if (v.z < (float) (Math.PI / 2)) {
                     clipperBuf.putFloat(v.x);
                     clipperBuf.putFloat(v.y);
                  }
               }

               clipperBuf.putFloat(x1);
               clipperBuf.putFloat(y1);
               clipperBuf.putFloat(x2);
               clipperBuf.putFloat(y2);
            } else {
               for (int i = 0; i < circlePoints.size(); i++) {
                  Vector3f v3 = circlePoints.get(i);
                  if (Float.compare(v3.z, angleMax) < 0 && Float.compare(v3.z, angleMin) > 0) {
                     sorted.add(v3);
                  }
               }

               sorted.sort((o1, o2) -> Float.compare(o1.z, o2.z));

               for (int i = 0; i < sorted.size(); i++) {
                  Vector3f v = sorted.get(i);
                  clipperBuf.putFloat(v.x);
                  clipperBuf.putFloat(v.y);
               }

               clipperBuf.putFloat(x1);
               clipperBuf.putFloat(y1);
               clipperBuf.putFloat(x2);
               clipperBuf.putFloat(y2);
            }

            clipper.addPath(sorted.size() + 2, clipperBuf, false);

            for (int i = 0; i < circlePoints.size(); i++) {
               Vector3f v3 = circlePoints.get(i);
               v3.x = v3.x - this.px;
               v3.y = v3.y - this.py;
            }
         } else {
            Vector3f v3 = VisibilityPolygon2.L_render.v3.set(xMin, yMin, VisibilityPolygon2.L_addShadowToClipper.angle1cm);
            Vector3f v4 = VisibilityPolygon2.L_render.v4.set(xMin, yMax, VisibilityPolygon2.L_addShadowToClipper.angle2cm);
            Vector3f v5 = VisibilityPolygon2.L_render.v5.set(xMax, yMax, VisibilityPolygon2.L_addShadowToClipper.angle3cm);
            Vector3f v6 = VisibilityPolygon2.L_render.v6.set(xMax, yMin, VisibilityPolygon2.L_addShadowToClipper.angle4cm);
            ArrayList<Vector3f> sorted = VisibilityPolygon2.L_render.vs;
            sorted.clear();
            sorted.add(v1);
            sorted.add(v2);
            if (angle1 > angle2) {
               if (Float.compare(v3.z, angle1) < 0 && Float.compare(v3.z, angle2) > 0) {
                  sorted.add(v3);
               }

               if (Float.compare(v4.z, angle1) < 0 && Float.compare(v4.z, angle2) > 0) {
                  sorted.add(v4);
               }

               if (Float.compare(v5.z, angle1) < 0 && Float.compare(v5.z, angle2) > 0) {
                  sorted.add(v5);
               }

               if (Float.compare(v6.z, angle1) < 0 && Float.compare(v6.z, angle2) > 0) {
                  sorted.add(v6);
               }

               sorted.sort((o1, o2) -> Float.compare(o1.z, o2.z));
               sorted.add(VisibilityPolygon2.L_addShadowToClipper.v1.set(x1, y1, 0.0F));
               sorted.add(VisibilityPolygon2.L_addShadowToClipper.v2.set(x2, y2, 0.0F));
               clipperBuf.clear();

               for (int i = 0; i < sorted.size(); i++) {
                  clipperBuf.putFloat(sorted.get(i).x);
                  clipperBuf.putFloat(sorted.get(i).y);
               }

               clipper.addPath(sorted.size(), clipperBuf, false);
            } else {
               if (Float.compare(v3.z, angle1) < 0 || Float.compare(v3.z, angle2) > 0) {
                  sorted.add(v3);
               }

               if (Float.compare(v4.z, angle1) < 0 || Float.compare(v4.z, angle2) > 0) {
                  sorted.add(v4);
               }

               if (Float.compare(v5.z, angle1) < 0 || Float.compare(v5.z, angle2) > 0) {
                  sorted.add(v5);
               }

               if (Float.compare(v6.z, angle1) < 0 || Float.compare(v6.z, angle2) > 0) {
                  sorted.add(v6);
               }

               sorted.sort((o1, o2) -> Float.compare(o1.z, o2.z));
               int index = sorted.indexOf(v1);
               sorted.add(index, VisibilityPolygon2.L_addShadowToClipper.v1.set(x1, y1, 0.0F));
               sorted.add(index + 1, VisibilityPolygon2.L_addShadowToClipper.v2.set(x2, y2, 0.0F));
               clipperBuf.clear();

               for (int i = 0; i < sorted.size(); i++) {
                  clipperBuf.putFloat(sorted.get(i).x);
                  clipperBuf.putFloat(sorted.get(i).y);
               }

               clipper.addPath(sorted.size(), clipperBuf, false);
            }
         }
      }

      void triangulatePolygons(int numPolys3, Clipper clipper, ByteBuffer clipperBuf, PlayerCamera camera, float alpha) {
         for (int j = 0; j < numPolys3; j++) {
            clipperBuf.clear();

            int numPoints;
            try {
               numPoints = clipper.triangulate(j, clipperBuf);
            } catch (BufferOverflowException ex) {
               clipperBuf = VisibilityPolygon2.L_render.clipperBuf = ByteBuffer.allocateDirect(clipperBuf.capacity() + 1024);
               j--;
               continue;
            }

            for (int k = 0; k < numPoints; k += 3) {
               float x1 = clipperBuf.getFloat();
               float y1 = clipperBuf.getFloat();
               float x2 = clipperBuf.getFloat();
               float y2 = clipperBuf.getFloat();
               float x3 = clipperBuf.getFloat();
               float y3 = clipperBuf.getFloat();
               if (VisibilityPolygon2.instance.useCircle) {
                  Vector2 split1 = VisibilityPolygon2.L_render.vector2;
                  Vector2 split2 = VisibilityPolygon2.L_render.vector2_2;
                  Vector2 split3 = VisibilityPolygon2.L_render.vector2_3;
                  float dist1 = this.closestPointOnLineSegment(x1, y1, x2, y2, this.px, this.py, split1);
                  float dist2 = this.closestPointOnLineSegment(x2, y2, x3, y3, this.px, this.py, split2);
                  float dist3 = this.closestPointOnLineSegment(x3, y3, x1, y1, this.px, this.py, split3);
                  if (dist1 < 0.001F) {
                     dist1 = Float.MAX_VALUE;
                  }

                  if (dist2 < 0.001F) {
                     dist2 = Float.MAX_VALUE;
                  }

                  if (dist3 < 0.001F) {
                     dist3 = Float.MAX_VALUE;
                  }

                  if (dist1 < dist2 && dist1 < dist3) {
                     this.renderOneTriangle(camera, x1, y1, split1.x, split1.y, x3, y3, alpha);
                     this.renderOneTriangle(camera, split1.x, split1.y, x2, y2, x3, y3, alpha);
                     continue;
                  }

                  if (dist2 < dist1 && dist2 < dist3) {
                     this.renderOneTriangle(camera, x2, y2, split2.x, split2.y, x1, y1, alpha);
                     this.renderOneTriangle(camera, split2.x, split2.y, x3, y3, x1, y1, alpha);
                     continue;
                  }

                  if (dist3 < dist1 && dist3 < dist2) {
                     this.renderOneTriangle(camera, x3, y3, split3.x, split3.y, x2, y2, alpha);
                     this.renderOneTriangle(camera, split3.x, split3.y, x1, y1, x2, y2, alpha);
                     continue;
                  }
               }

               this.renderOneTriangle(camera, x1, y1, x2, y2, x3, y3, alpha);
            }
         }
      }

      float closestPointOnLineSegment(float x1, float y1, float x2, float y2, float x3, float y3, Vector2 closest) {
         double u = ((x3 - x1) * (x2 - x1) + (y3 - y1) * (y2 - y1)) / (Math.pow(x2 - x1, 2.0) + Math.pow(y2 - y1, 2.0));
         if (Double.compare(u, 0.001F) <= 0) {
            closest.set(x1, y1);
            return IsoUtils.DistanceToSquared(x3, y3, x1, y1);
         } else if (Double.compare(u, 0.9989999999525025) >= 0) {
            closest.set(x2, y2);
            return IsoUtils.DistanceToSquared(x3, y3, x2, y2);
         } else {
            double xu = x1 + u * (x2 - x1);
            double yu = y1 + u * (y2 - y1);
            closest.set((float)xu, (float)yu);
            return IsoUtils.DistanceToSquared(x3, y3, (float)xu, (float)yu);
         }
      }

      void renderOneTriangle(PlayerCamera camera, float x1, float y1, float x2, float y2, float x3, float y3, float alpha) {
         VBORenderer vbor = VBORenderer.getInstance();
         float sx1 = camera.XToScreenExact(x1, y1, this.pz, 0);
         float sy1 = camera.YToScreenExact(x1, y1, this.pz, 0);
         float sx2 = camera.XToScreenExact(x2, y2, this.pz, 0);
         float sy2 = camera.YToScreenExact(x2, y2, this.pz, 0);
         float sx3 = camera.XToScreenExact(x3, y3, this.pz, 0);
         float sy3 = camera.YToScreenExact(x3, y3, this.pz, 0);
         float u = 0.0F;
         float v = 0.0F;
         float depthAdjust = -1.0E-4F;
         float depth1 = IsoDepthHelper.getSquareDepthData(PZMath.fastfloor(this.px), PZMath.fastfloor(this.py), x1, y1, this.pz).depthStart + -1.0E-4F;
         float depth2 = IsoDepthHelper.getSquareDepthData(PZMath.fastfloor(this.px), PZMath.fastfloor(this.py), x2, y2, this.pz).depthStart + -1.0E-4F;
         float depth3 = IsoDepthHelper.getSquareDepthData(PZMath.fastfloor(this.px), PZMath.fastfloor(this.py), x3, y3, this.pz).depthStart + -1.0E-4F;
         float r = 0.0F;
         float g = 0.0F;
         float b = 0.0F;
         if (DebugOptions.instance.displayVisibilityPolygon.getValue()) {
            int c = VisibilityPolygon2.L_render.triangleIndex++ % Model.debugDrawColours.length;
            r = Model.debugDrawColours[c].r;
            g = Model.debugDrawColours[c].g;
            b = Model.debugDrawColours[c].b;
            alpha = 1.0F;
         }

         if (VisibilityPolygon2.instance.useCircle) {
            float alpha1 = 1.0F - PZMath.clamp(IsoUtils.DistanceToSquared(x1, y1, this.px, this.py) / (this.circleRadius * this.circleRadius), 0.0F, 1.0F);
            float alpha2 = 1.0F - PZMath.clamp(IsoUtils.DistanceToSquared(x2, y2, this.px, this.py) / (this.circleRadius * this.circleRadius), 0.0F, 1.0F);
            float alpha3 = 1.0F - PZMath.clamp(IsoUtils.DistanceToSquared(x3, y3, this.px, this.py) / (this.circleRadius * this.circleRadius), 0.0F, 1.0F);
            vbor.addTriangleDepth(
               sx1,
               sy1,
               0.0F,
               0.0F,
               0.0F,
               depth1,
               alpha1,
               sx2,
               sy2,
               0.0F,
               0.5F,
               0.5F,
               depth2,
               alpha2,
               sx3,
               sy3,
               0.0F,
               1.0F,
               1.0F,
               depth3,
               alpha3,
               r,
               g,
               b,
               alpha
            );
         } else {
            vbor.addTriangleDepth(sx1, sy1, 0.0F, 0.0F, 0.0F, depth1, sx2, sy2, 0.0F, 0.5F, 0.5F, depth2, sx3, sy3, 0.0F, 1.0F, 1.0F, depth3, r, g, b, alpha);
         }
      }

      void addClipperPolygon(Clipper clipper, ByteBuffer clipperBuf, ClipperPolygon clipperPolygon, boolean bClip) {
         this.addClipperPolygon(clipper, clipperBuf, clipperPolygon.outer, bClip);

         for (int i = 0; i < clipperPolygon.holes.size(); i++) {
            this.addClipperPolygon(clipper, clipperBuf, (TFloatArrayList)clipperPolygon.holes.get(i), bClip);
         }
      }

      void addClipperPolygon(Clipper clipper, ByteBuffer clipperBuf, TFloatArrayList xy, boolean bClip) {
         clipperBuf.clear();
         if (bClip) {
         }

         for (int i = 0; i < xy.size(); i++) {
            clipperBuf.putFloat(xy.get(i));
         }

         clipper.addPath(xy.size() / 2, clipperBuf, bClip);
      }

      void addClipperPolygons(Clipper clipper, ByteBuffer clipperBuf, ArrayList<ClipperPolygon> polygons, boolean bClip) {
         for (int i = 0; i < polygons.size(); i++) {
            this.addClipperPolygon(clipper, clipperBuf, polygons.get(i), bClip);
         }
      }

      void getClipperPolygons(Clipper clipper, int numPolys, ArrayList<ClipperPolygon> polygons) {
         ByteBuffer clipperBuf = VisibilityPolygon2.L_render.clipperBuf;

         for (int i = 0; i < numPolys; i++) {
            clipperBuf.clear();
            clipper.getPolygon(i, clipperBuf);
            int numPoints = clipperBuf.getShort();
            if (numPoints >= 3) {
               ClipperPolygon clipperPolygon = (ClipperPolygon)VisibilityPolygon2.L_render.clipperPolygonPool.alloc();

               for (int j = 0; j < numPoints; j++) {
                  float x1 = clipperBuf.getFloat();
                  float y1 = clipperBuf.getFloat();
                  clipperPolygon.outer.add(x1);
                  clipperPolygon.outer.add(y1);
               }

               int holeCount = clipperBuf.getShort();

               for (int j = 0; j < holeCount; j++) {
                  int var14 = clipperBuf.getShort();
                  if (var14 >= 3) {
                     TFloatArrayList hole = (TFloatArrayList)VisibilityPolygon2.L_render.floatArrayListPool.alloc();
                     hole.clear();

                     for (int k = 0; k < var14; k++) {
                        float x1 = clipperBuf.getFloat();
                        float y1 = clipperBuf.getFloat();
                        hole.add(x1);
                        hole.add(y1);
                     }

                     clipperPolygon.holes.add(hole);
                  }
               }

               polygons.add(clipperPolygon);
            }
         }
      }

      void releaseClipperPolygons(ArrayList<ClipperPolygon> polygons) {
         for (int i = 0; i < polygons.size(); i++) {
            ClipperPolygon polygon = polygons.get(i);
            polygon.outer.clear();
            VisibilityPolygon2.L_render.floatArrayListPool.releaseAll(polygon.holes);
            polygon.holes.clear();
         }

         VisibilityPolygon2.L_render.clipperPolygonPool.releaseAll(polygons);
         polygons.clear();
      }

      private static class PolygonData {
         public static final int MAX_VERTS = 3072;
         public static final int NUM_ELEMENTS = 5;
         private final FloatBuffer data = BufferUtils.createFloatBuffer(15360);
         private int bufferId = -1;
         private PlayerCamera camera;
         private int sx;
         private int sy;
         private float z;

         public void init() {
            if (this.bufferId == -1) {
               this.bufferId = GL20.glGenBuffers();
               GL20.glBindBuffer(34962, this.bufferId);
               GL20.glBufferData(34962, this.data, 35048);
               GL20.glBindBuffer(34962, 0);
            }
         }

         public void update(int playerIndex) {
            this.sx = PZMath.fastfloor(IsoCamera.frameState.camCharacterX);
            this.sy = PZMath.fastfloor(IsoCamera.frameState.camCharacterY);
            this.z = PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);
         }

         public void draw() {
            GL20.glBindBuffer(34962, this.bufferId);
            GL20.glBufferSubData(34962, 0L, this.data);
            GL20.glEnableVertexAttribArray(0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glEnableVertexAttribArray(2);
            GL20.glVertexAttribPointer(0, 3, 5126, false, 20, 0L);
            GL20.glVertexAttribPointer(1, 1, 5126, false, 20, 12L);
            GL20.glVertexAttribPointer(2, 1, 5126, false, 20, 16L);
            GL11.glDrawArrays(4, 0, this.data.limit() / 5);
            GL20.glDisableVertexAttribArray(0);
            GL20.glDisableVertexAttribArray(1);
            GL20.glDisableVertexAttribArray(2);
            GL20.glBindBuffer(34962, 0);
         }

         public void addVertex(float x, float y, float b) {
            float depthAdjust = -1.0E-4F;
            float cx = this.camera.XToScreenExact(x, y, this.z, 0);
            float cy = this.camera.YToScreenExact(x, y, this.z, 0);
            float d = IsoDepthHelper.getSquareDepthData(this.sx, this.sy, x, y, this.z).depthStart;
            this.addTransformed(cx, cy, 0.0F, b, d + -1.0E-4F);
         }

         public void addTransformed(float x, float y, float z, float b, float d) {
            this.data.put(x);
            this.data.put(y);
            this.data.put(z);
            this.data.put(b);
            this.data.put(d);
         }

         public void reset() {
            this.data.rewind();
            this.data.limit(this.data.capacity());
         }
      }
   }

   private static final class EndPoint {
      float x;
      float y;
      float dirX;
      float dirY;
      float angle;
      float dist;

      VisibilityPolygon2.EndPoint set(float x, float y, float dirX, float dirY, float angle, float dist) {
         this.x = x;
         this.y = y;
         Vector2 v = VisibilityPolygon2.L_calculateVisibilityPolygon.m_tempVector2_1.set(dirX, dirY);
         v.normalize();
         this.dirX = v.x;
         this.dirY = v.y;
         this.angle = angle;
         this.dist = dist;
         return this;
      }
   }

   static final class L_addShadowToClipper {
      static float angle1cm;
      static float angle2cm;
      static float angle3cm;
      static float angle4cm;
      static final Vector3f v1 = new Vector3f();
      static final Vector3f v2 = new Vector3f();
   }

   static final class L_calculateVisibilityPolygon {
      static final ObjectPool<VisibilityPolygon2.Segment> m_segmentPool = new ObjectPool(
         VisibilityPolygon2.Segment::new, "L_calculateVisibilityPolygon.m_segmentPool"
      );
      static final Vector2 m_tempVector2_1 = new Vector2();
      static final HashSet<VisibilityPolygon2.Segment> segmentHashSet = new HashSet<>();
      static final ArrayList<VisibilityPolygon2.Segment> segmentList = new ArrayList<>();
      static final ArrayList<VisibilityPolygon2.VisibilityWall> sortedWalls = new ArrayList<>();
      static final ArrayList<IsoGridSquare> solidSquares = new ArrayList<>();
   }

   static final class L_render {
      static final Vector3f v1 = new Vector3f();
      static final Vector3f v2 = new Vector3f();
      static final Vector3f v3 = new Vector3f();
      static final Vector3f v4 = new Vector3f();
      static final Vector3f v5 = new Vector3f();
      static final Vector3f v6 = new Vector3f();
      static final ArrayList<Vector3f> vs = new ArrayList<>();
      static final Vector2 vector2 = new Vector2();
      static final Vector2 vector2_2 = new Vector2();
      static final Vector2 vector2_3 = new Vector2();
      static final Clipper m_clipper = new Clipper();
      static ByteBuffer clipperBuf = ByteBuffer.allocateDirect(10240);
      static final ArrayList<ClipperPolygon> clipperPolygons1 = new ArrayList<>();
      static final ArrayList<ClipperPolygon> clipperPolygons2 = new ArrayList<>();
      static final ArrayList<ClipperPolygon> clipperPolygons3 = new ArrayList<>();
      static final ObjectPool<ClipperPolygon> clipperPolygonPool = new ObjectPool(ClipperPolygon::new, "L_render.clipperPolygonPool");
      static final ObjectPool<TFloatArrayList> floatArrayListPool = new ObjectPool(TFloatArrayList::new, "L_render.floatArrayListPool");
      static int triangleIndex;
   }

   private static final class Partition {
      float minAngle;
      float maxAngle;
      final ArrayList<VisibilityPolygon2.Segment> segments = new ArrayList<>();

      boolean addSegment(VisibilityPolygon2.Segment segment) {
         int index = this.segments.size();

         for (int i = 0; i < this.segments.size(); i++) {
            if (Float.compare(segment.minDist(), this.segments.get(i).minDist()) < 0) {
               index = i;
               break;
            }
         }

         for (int i = 0; i < index; i++) {
            if (segment.isInShadowOf(this.segments.get(i))) {
               return false;
            }
         }

         this.segments.add(index, segment);
         return true;
      }
   }

   private static final class Segment {
      final VisibilityPolygon2.EndPoint a = new VisibilityPolygon2.EndPoint();
      final VisibilityPolygon2.EndPoint b = new VisibilityPolygon2.EndPoint();

      float minAngle() {
         return PZMath.min(this.a.angle, this.b.angle);
      }

      float maxAngle() {
         return PZMath.max(this.a.angle, this.b.angle);
      }

      float minDist() {
         return PZMath.min(this.a.dist, this.b.dist);
      }

      float maxDist() {
         return PZMath.max(this.a.dist, this.b.dist);
      }

      boolean isInShadowOf(VisibilityPolygon2.Segment other) {
         float minAngle1 = this.minAngle();
         float maxAngle1 = this.maxAngle();
         if (maxAngle1 - minAngle1 > 180.0F) {
            minAngle1 += 360.0F;
            float temp = minAngle1;
            minAngle1 = maxAngle1;
            maxAngle1 = temp;
         }

         float minAngle2 = other.minAngle();
         float maxAngle2 = other.maxAngle();
         if (maxAngle2 - minAngle2 > 180.0F) {
            minAngle2 += 360.0F;
            float temp = minAngle2;
            minAngle2 = maxAngle2;
            maxAngle2 = temp;
         }

         if (maxAngle1 > 360.0F && maxAngle2 < 180.0F) {
            if (minAngle2 < 180.0F) {
               minAngle2 += 360.0F;
            }

            maxAngle2 += 360.0F;
         }

         if (maxAngle2 > 360.0F && maxAngle1 < 180.0F) {
            if (minAngle1 < 180.0F) {
               minAngle1 += 360.0F;
            }

            maxAngle1 += 360.0F;
         }

         return Float.compare(minAngle1, minAngle2) >= 0 && Float.compare(maxAngle1, maxAngle2) <= 0 && Float.compare(this.minDist(), other.maxDist()) >= 0;
      }
   }

   public static final class VisibilityWall {
      VisibilityPolygon2.ChunkLevelData chunkLevelData;
      public int x1;
      public int y1;
      public int x2;
      public int y2;

      boolean isHorizontal() {
         return this.y1 == this.y2;
      }
   }
}
