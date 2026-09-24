package zombie.pathfind;

import astar.ISearchNode;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.procedure.TObjectProcedure;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.joml.Vector2f;
import zombie.GameWindow;
import zombie.Lua.LuaManager;
import zombie.ai.KnownBlockedEdges;
import zombie.ai.astar.Mover;
import zombie.audio.FMODAmbientWalls;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.Core;
import zombie.core.logger.ExceptionLogger;
import zombie.core.math.PZMath;
import zombie.core.profiling.AbstractPerformanceProfileProbe;
import zombie.debug.DebugOptions;
import zombie.debug.LineDrawer;
import zombie.gameStates.DebugChunkState;
import zombie.input.GameKeyboard;
import zombie.input.Mouse;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMetaGrid;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.Vector2;
import zombie.iso.SpriteDetails.IsoObjectType;
import zombie.iso.fboRenderChunk.FBORenderCutaways;
import zombie.network.GameClient;
import zombie.pathfind.PathFindBehavior2.PointOnPath;
import zombie.pathfind.highLevel.HLAStar;
import zombie.pathfind.highLevel.HLGlobals;
import zombie.pathfind.highLevel.HLLevelTransition;
import zombie.pathfind.nativeCode.PathfindNative;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.Clipper;

public final class PolygonalMap2 {
   // pzopt: marker so the game log shows the loose class was loaded (quiet: logged once the logger is up)
   static {
      pzopt.Overrides.onClassLoadedQuiet("zombie.pathfind.PolygonalMap2");
   }
   public static final PolygonalMap2 instance = new PolygonalMap2();
   public static final float RADIUS = 0.3F;
   public static final boolean CLOSE_TO_WALLS = true;
   public static final boolean PATHS_UNDER_VEHICLES = true;
   public static final boolean COLLIDE_CLIPPER = false;
   public static final boolean COLLIDE_BEVEL = false;
   public static final int CXN_FLAG_CAN_PATH = 1;
   public static final int CXN_FLAG_THUMP = 2;
   public static final int NODE_FLAG_CRAWL = 1;
   public static final int NODE_FLAG_CRAWL_INTERIOR = 2;
   public static final int NODE_FLAG_IN_CHUNK_DATA = 4;
   public static final int NODE_FLAG_PERIMETER = 8;
   public static final int NODE_FLAG_STAIR = 16;
   public static final int NODE_FLAG_KEEP = 65536;
   public static final int LCC_ZERO = 0;
   public static final int LCC_IGNORE_DOORS = 1;
   public static final int LCC_CLOSE_TO_WALLS = 2;
   public static final int LCC_CHECK_COST = 4;
   public static final int LCC_RENDER = 8;
   public static final int LCC_ALLOW_ON_EDGE = 16;
   public static final float RADIUS_DIAGONAL = (float)Math.sqrt(0.18F);
   public static final Vector2 temp = new Vector2();
   public static final int SQUARES_PER_CHUNK = 8;
   public static final int LEVELS_PER_CHUNK = 64;
   public static final int GROUND_LEVEL = 32;
   private static final int SQUARES_PER_CELL = 256;
   public static final int CHUNKS_PER_CELL = 32;
   public static final int BIT_SOLID = 1;
   public static final int BIT_COLLIDE_W = 2;
   public static final int BIT_COLLIDE_N = 4;
   public static final int BIT_STAIR_TW = 8;
   public static final int BIT_STAIR_MW = 16;
   public static final int BIT_STAIR_BW = 32;
   public static final int BIT_STAIR_TN = 64;
   public static final int BIT_STAIR_MN = 128;
   public static final int BIT_STAIR_BN = 256;
   public static final int BIT_SOLID_FLOOR = 512;
   static final int BIT_SOLID_TRANS = 1024;
   public static final int BIT_WINDOW_W = 2048;
   public static final int BIT_WINDOW_N = 4096;
   public static final int BIT_CAN_PATH_W = 8192;
   public static final int BIT_CAN_PATH_N = 16384;
   public static final int BIT_THUMP_W = 32768;
   public static final int BIT_THUMP_N = 65536;
   public static final int BIT_THUMPABLE = 131072;
   public static final int BIT_DOOR_E = 262144;
   public static final int BIT_DOOR_S = 524288;
   public static final int BIT_WINDOW_W_UNBLOCKED = 1048576;
   public static final int BIT_WINDOW_N_UNBLOCKED = 2097152;
   public static final int BIT_DOOR_W_UNBLOCKED = 4194304;
   public static final int BIT_DOOR_N_UNBLOCKED = 8388608;
   public static final int BIT_HOPPABLE_N = 16777216;
   public static final int BIT_HOPPABLE_W = 33554432;
   public static final int ALL_STAIR_BITS = 504;
   private static final int ALL_SOLID_BITS = 1025;
   private static final IsoDirections[] DIRECTIONS = IsoDirections.values();
   public final Object renderLock = new Object();
   public final ClosestPointOnEdge closestPointOnEdge = new ClosestPointOnEdge();
   public final TIntObjectHashMap<Node> squareToNode = new TIntObjectHashMap();
   public final ByteBuffer xyBufferThread = ByteBuffer.allocateDirect(8192);
   private final ConcurrentLinkedQueue<IChunkTask> chunkTaskQueue = new ConcurrentLinkedQueue<>();
   private final ConcurrentLinkedQueue<SquareUpdateTask> squareTaskQueue = new ConcurrentLinkedQueue<>();
   private final ConcurrentLinkedQueue<IVehicleTask> vehicleTaskQueue = new ConcurrentLinkedQueue<>();
   public final ArrayList<Vehicle> vehicles = new ArrayList<>();
   public final HashMap<BaseVehicle, Vehicle> vehicleMap = new HashMap<>();
   private final Sync sync = new Sync();
   public final RequestQueue requests = new RequestQueue();
   private final ConcurrentLinkedQueue<PathRequestTask> requestTaskQueue = new ConcurrentLinkedQueue<>();
   public final CollideWithObstaclesPoly collideWithObstaclesPoly = new CollideWithObstaclesPoly();
   private final ArrayList<VehicleCluster> clusters = new ArrayList<>();
   private final ArrayList<Square> tempSquares = new ArrayList<>();
   private final ArrayList<VisibilityGraph> graphs = new ArrayList<>();
   private final AdjustStartEndNodeData adjustStartData = new AdjustStartEndNodeData();
   private final AdjustStartEndNodeData adjustGoalData = new AdjustStartEndNodeData();
   private final LineClearCollide lcc = new LineClearCollide();
   private final VGAStar astar = new VGAStar();
   private final TestRequest testRequest = new TestRequest();
   private final PointOnPath pointOnPath = new PointOnPath();
   private final HashMap<BaseVehicle, VehicleState> vehicleState = new HashMap<>();
   private final TObjectProcedure<Node> releaseNodeProc = new TObjectProcedure<Node>() {
      public boolean execute(Node node) {
         node.release();
         return true;
      }
   };
   private final Path shortestPath = new Path();
   private final ConcurrentLinkedQueue<PathFindRequest> requestToMain = new ConcurrentLinkedQueue<>();
   private final HashMap<Mover, PathFindRequest> requestMap = new HashMap<>();
   private final LineClearCollideMain lccMain = new LineClearCollideMain();
   private final float[] tempFloats = new float[8];
   private final CollideWithObstacles collideWithObstacles = new CollideWithObstacles();
   public Clipper clipperThread;
   public boolean rebuild;
   private int testZ;
   private int minX;
   private int minY;
   private int width;
   private int height;
   private Cell[][] cells;
   private PolygonalMap2.PMThread thread;

   private void createVehicleCluster(VehicleRect rect, ArrayList<VehicleRect> rects, ArrayList<VehicleCluster> clusters) {
      for (int i = 0; i < rects.size(); i++) {
         VehicleRect rect2 = rects.get(i);
         if (rect != rect2 && rect.z == rect2.z && (rect.cluster == null || rect.cluster != rect2.cluster) && rect.isAdjacent(rect2)) {
            if (rect.cluster != null) {
               if (rect2.cluster == null) {
                  rect2.cluster = rect.cluster;
                  rect2.cluster.rects.add(rect2);
               } else {
                  clusters.remove(rect2.cluster);
                  rect.cluster.merge(rect2.cluster);
               }
            } else if (rect2.cluster != null) {
               if (rect.cluster == null) {
                  rect.cluster = rect2.cluster;
                  rect.cluster.rects.add(rect);
               } else {
                  clusters.remove(rect.cluster);
                  rect2.cluster.merge(rect.cluster);
               }
            } else {
               VehicleCluster cluster = VehicleCluster.alloc().init();
               cluster.z = rect.z;
               rect.cluster = cluster;
               rect2.cluster = cluster;
               cluster.rects.add(rect);
               cluster.rects.add(rect2);
               clusters.add(cluster);
            }
         }
      }

      if (rect.cluster == null) {
         VehicleCluster cluster = VehicleCluster.alloc().init();
         cluster.z = rect.z;
         rect.cluster = cluster;
         cluster.rects.add(rect);
         clusters.add(cluster);
      }
   }

   private void createVehicleClusters() {
      this.clusters.clear();
      ArrayList<VehicleRect> rects = new ArrayList<>();

      for (int i = 0; i < this.vehicles.size(); i++) {
         Vehicle vehicle = this.vehicles.get(i);
         VehicleRect rect = VehicleRect.alloc();
         vehicle.polyPlusRadius.getAABB(rect);
         rect.vehicle = vehicle;
         rects.add(rect);
      }

      if (!rects.isEmpty()) {
         for (int i = 0; i < rects.size(); i++) {
            VehicleRect rect = rects.get(i);
            this.createVehicleCluster(rect, rects, this.clusters);
         }
      }
   }

   public Node getNodeForSquare(Square square) {
      Node node = (Node)this.squareToNode.get(square.id);
      if (node == null) {
         node = Node.alloc().init(square);
         this.squareToNode.put(square.id, node);
      }

      return node;
   }

   public Node getExistingNodeForSquare(Square square) {
      return (Node)this.squareToNode.get(square.id);
   }

   private VisibilityGraph getVisGraphAt(float x, float y, int z) {
      return this.getVisGraphAt(x, y, z, 0);
   }

   public VisibilityGraph getVisGraphAt(float x, float y, int z, int expand) {
      Chunk chunk = this.getChunkFromSquarePos(PZMath.fastfloor(x), PZMath.fastfloor(y));
      if (chunk == null) {
         return null;
      }

      for (int i = 0; i < chunk.visibilityGraphs.size(); i++) {
         VisibilityGraph graph = (VisibilityGraph)chunk.visibilityGraphs.get(i);
         if (graph.contains(x, y, z, expand)) {
            return graph;
         }
      }

      return null;
   }

   public VisibilityGraph getVisGraphForSquare(Square square) {
      Chunk chunk = this.getChunkFromSquarePos(square.x, square.y);
      if (chunk == null) {
         return null;
      }

      for (int i = 0; i < chunk.visibilityGraphs.size(); i++) {
         VisibilityGraph graph = (VisibilityGraph)chunk.visibilityGraphs.get(i);
         if (graph.contains(square)) {
            return graph;
         }
      }

      return null;
   }

   public void getVisibilityGraphsOverlappingChunk(Chunk chunk, int level, ArrayList<VisibilityGraph> graphs) {
      for (int i = 0; i < chunk.visibilityGraphs.size(); i++) {
         VisibilityGraph graph = (VisibilityGraph)chunk.visibilityGraphs.get(i);
         if (graph.cluster.z == level && !graphs.contains(graph)) {
            graphs.add(graph);
         }
      }
   }

   public void getVisibilityGraphsAdjacentToChunk(Chunk chunk, int level, ArrayList<VisibilityGraph> graphs) {
      for (int i = 0; i < DIRECTIONS.length; i++) {
         IsoDirections dir = DIRECTIONS[i];
         Chunk chunk2 = this.getChunkFromChunkPos(chunk.wx + dir.dx(), chunk.wy + dir.dy());
         if (chunk2 != null) {
            for (int j = 0; j < chunk2.visibilityGraphs.size(); j++) {
               VisibilityGraph graph = (VisibilityGraph)chunk2.visibilityGraphs.get(j);
               if (graph.cluster.z == level && !graphs.contains(graph)) {
                  VehicleRect bounds = graph.cluster.bounds();
                  int expand = 1;
                  if (bounds.intersects(chunk.getMinX() - 1, chunk.getMinY() - 1, chunk.getMaxX() + 2, chunk.getMaxY() + 2, 1)) {
                     graphs.add(graph);
                  }

                  bounds.release();
               }
            }
         }
      }
   }

   public Connection connectTwoNodes(Node node1, Node node2, int flags) {
      Connection cxn = Connection.alloc().init(node1, node2, flags);
      node1.visible.add(cxn);
      node2.visible.add(cxn);
      return cxn;
   }

   public Connection connectTwoNodes(Node node1, Node node2) {
      return this.connectTwoNodes(node1, node2, 0);
   }

   public void breakConnection(Connection cxn) {
      cxn.node1.visible.remove(cxn);
      cxn.node2.visible.remove(cxn);
      cxn.release();
   }

   public void breakConnection(Node node1, Node node2) {
      for (int i = 0; i < node1.visible.size(); i++) {
         Connection cxn = (Connection)node1.visible.get(i);
         if (cxn.otherNode(node1) == node2) {
            this.breakConnection(cxn);
            break;
         }
      }
   }

   private void addStairNodes() {
      ArrayList<Square> stairSquares = this.tempSquares;
      stairSquares.clear();

      for (int i = 0; i < this.graphs.size(); i++) {
         VisibilityGraph graph = this.graphs.get(i);
         graph.getStairSquares(stairSquares);
      }

      for (int i = 0; i < stairSquares.size(); i++) {
         Square square = stairSquares.get(i);
         Square squareTop = null;
         Square squareT = null;
         Square squareM = null;
         Square squareB = null;
         Square squareBottom = null;
         if (square.has(8)) {
            squareTop = this.getSquare(square.x - 1, square.y, square.z + 1);
            squareT = square;
            squareM = this.getSquare(square.x + 1, square.y, square.z);
            squareB = this.getSquare(square.x + 2, square.y, square.z);
            squareBottom = this.getSquare(square.x + 3, square.y, square.z);
         }

         if (square.has(64)) {
            squareTop = this.getSquare(square.x, square.y - 1, square.z + 1);
            squareT = square;
            squareM = this.getSquare(square.x, square.y + 1, square.z);
            squareB = this.getSquare(square.x, square.y + 2, square.z);
            squareBottom = this.getSquare(square.x, square.y + 3, square.z);
         }

         if (squareTop != null && squareT != null && squareM != null && squareB != null && squareBottom != null) {
            VisibilityGraph graph = this.getVisGraphForSquare(squareTop);
            Node searchNodeTop;
            if (graph == null) {
               searchNodeTop = this.getNodeForSquare(squareTop);
            } else {
               searchNodeTop = Node.alloc().init(squareTop);

               for (Obstacle obstacle : graph.obstacles) {
                  if (obstacle.isNodeInsideOf(searchNodeTop)) {
                     searchNodeTop.ignore = true;
                  }
               }

               searchNodeTop.addGraph(graph);
               graph.addNode(searchNodeTop);
               this.squareToNode.put(squareTop.id, searchNodeTop);
            }

            searchNodeTop.flags |= 16;
            graph = this.getVisGraphForSquare(squareBottom);
            Node searchNodeBottom;
            if (graph == null) {
               searchNodeBottom = this.getNodeForSquare(squareBottom);
            } else {
               searchNodeBottom = Node.alloc().init(squareBottom);

               for (Obstacle obstacle : graph.obstacles) {
                  if (obstacle.isNodeInsideOf(searchNodeBottom)) {
                     searchNodeBottom.ignore = true;
                  }
               }

               searchNodeBottom.addGraph(graph);
               graph.addNode(searchNodeBottom);
               this.squareToNode.put(squareBottom.id, searchNodeBottom);
            }

            searchNodeBottom.flags |= 16;
         }
      }
   }

   private void addCanPathNodes() {
      ArrayList<Square> squares = this.tempSquares;
      squares.clear();

      for (int i = 0; i < this.graphs.size(); i++) {
         VisibilityGraph graph = this.graphs.get(i);
         graph.getCanPathSquares(squares);
      }

      for (int i = 0; i < squares.size(); i++) {
         Square square1 = squares.get(i);
         if (!square1.isNonThumpableSolid() && !square1.has(504) && square1.has(512)) {
            if (square1.isCanPathW()) {
               int x2 = square1.x - 1;
               int y2 = square1.y;
               Square square2 = this.getSquare(x2, y2, square1.z);
               if (square2 != null && !square2.isNonThumpableSolid() && !square2.has(504) && square2.has(512)) {
                  Node node1 = this.getOrCreateCanPathNode(square1);
                  Node node2 = this.getOrCreateCanPathNode(square2);
                  int flags = 1;
                  if (square1.has(163840) || square2.has(131072)) {
                     flags |= 2;
                  }

                  this.connectTwoNodes(node1, node2, flags);
               }
            }

            if (square1.isCanPathN()) {
               int x2 = square1.x;
               int y2 = square1.y - 1;
               Square square2 = this.getSquare(x2, y2, square1.z);
               if (square2 != null && !square2.isNonThumpableSolid() && !square2.has(504) && square2.has(512)) {
                  Node node1 = this.getOrCreateCanPathNode(square1);
                  Node node2 = this.getOrCreateCanPathNode(square2);
                  int flags = 1;
                  if (square1.has(196608) || square2.has(131072)) {
                     flags |= 2;
                  }

                  this.connectTwoNodes(node1, node2, flags);
               }
            }
         }
      }
   }

   private Node getOrCreateCanPathNode(Square square) {
      VisibilityGraph graph = this.getVisGraphForSquare(square);
      Node node = this.getNodeForSquare(square);
      if (graph != null && !graph.nodes.contains(node)) {
         for (Obstacle obstacle : graph.obstacles) {
            if (obstacle.isNodeInsideOf(node)) {
               node.ignore = true;
               break;
            }
         }

         graph.addNode(node);
      }

      return node;
   }

   public Node getPointOutsideObjects(Square square, float targetX, float targetY) {
      Square w = instance.getSquare(square.x - 1, square.y, square.z);
      Square nw = instance.getSquare(square.x - 1, square.y - 1, square.z);
      Square n = instance.getSquare(square.x, square.y - 1, square.z);
      Square ne = instance.getSquare(square.x + 1, square.y - 1, square.z);
      Square e = instance.getSquare(square.x + 1, square.y, square.z);
      Square se = instance.getSquare(square.x + 1, square.y + 1, square.z);
      Square s = instance.getSquare(square.x, square.y + 1, square.z);
      Square sw = instance.getSquare(square.x - 1, square.y + 1, square.z);
      float x1 = square.x;
      float y1 = square.y;
      float x2 = square.x + 1.0F;
      float y2 = square.y + 1.0F;
      if (square.isCollideW()) {
         x1 += 0.35000002F;
      }

      if (square.isCollideN()) {
         y1 += 0.35000002F;
      }

      if (e != null && (e.has(2) || e.has(504) || e.isReallySolid())) {
         x2 -= 0.35000002F;
      }

      if (s != null && (s.has(4) || s.has(504) || s.isReallySolid())) {
         y2 -= 0.35000002F;
      }

      float tx = PZMath.clamp(targetX, x1, x2);
      float ty = PZMath.clamp(targetY, y1, y2);
      if (tx <= square.x + 0.3F && ty <= square.y + 0.3F) {
         boolean solid = nw != null && (nw.has(504) || nw.isReallySolid());
         solid |= n != null && n.has(2);
         solid |= w != null && w.has(4);
         if (solid) {
            float x3 = square.x + 0.3F + 0.05F;
            float y3 = square.y + 0.3F + 0.05F;
            if (x3 - tx <= y3 - ty) {
               tx = x3;
            } else {
               ty = y3;
            }
         }
      }

      if (tx >= square.x + 1.0F - 0.3F && ty <= square.y + 0.3F) {
         boolean solid = ne != null && (ne.has(2) || ne.has(504) || ne.isReallySolid());
         solid |= e != null && e.has(4);
         if (solid) {
            float x3 = square.x + 1.0F - 0.3F - 0.05F;
            float y3 = square.y + 0.3F + 0.05F;
            if (tx - x3 <= y3 - ty) {
               tx = x3;
            } else {
               ty = y3;
            }
         }
      }

      if (tx <= square.x + 0.3F && ty >= square.y + 1 - 0.3F) {
         boolean solid = sw != null && (sw.has(4) || sw.has(504) || sw.isReallySolid());
         solid |= s != null && s.has(2);
         if (solid) {
            float x3 = square.x + 0.3F + 0.05F;
            float y3 = square.y + 1.0F - 0.3F - 0.05F;
            if (x3 - tx <= ty - y3) {
               tx = x3;
            } else {
               ty = y3;
            }
         }
      }

      if (tx >= square.x + 1 - 0.3F && ty >= square.y + 1.0F - 0.3F) {
         boolean solid = se != null && (se.has(2) || se.has(4) || se.has(504) || se.isReallySolid());
         if (solid) {
            float x3 = square.x + 1.0F - 0.3F - 0.05F;
            float y3 = square.y + 1.0F - 0.3F - 0.05F;
            if (tx - x3 <= ty - y3) {
               tx = x3;
            } else {
               ty = y3;
            }
         }
      }

      return Node.alloc().init(tx, ty, square.z);
   }

   private void createVisibilityGraph(VehicleCluster cluster) {
      VisibilityGraph vg = VisibilityGraph.alloc().init(cluster);
      vg.addPerimeterEdges();
      vg.initOverlappedChunks();
      this.graphs.add(vg);
   }

   private void createVisibilityGraphs() {
      this.createVehicleClusters();
      this.squareToNode.clear();

      for (int i = 0; i < this.clusters.size(); i++) {
         VehicleCluster cluster = this.clusters.get(i);
         this.createVisibilityGraph(cluster);
      }

      this.addStairNodes();
      this.addCanPathNodes();
   }

   private boolean findPath(PathFindRequest request, boolean render) {
      float requestStartZ = request.startZ + 32.0F;
      float requestTargetZ = request.targetZ + 32.0F;
      int flags = 16;
      if (!(request.mover instanceof IsoZombie)) {
         flags |= 4;
      }

      if (PZMath.fastfloor(requestStartZ) == PZMath.fastfloor(requestTargetZ)
         && !this.lcc.isNotClear(this, request.startX, request.startY, request.targetX, request.targetY, PZMath.fastfloor(requestStartZ), flags)) {
         request.path.addNode(request.startX, request.startY, request.startZ);
         request.path.addNode(request.targetX, request.targetY, request.targetZ);
         if (render) {
            for (VisibilityGraph vg : this.graphs) {
               vg.render();
            }
         }

         return true;
      } else {
         this.astar.init(this.graphs, this.squareToNode);
         this.astar.knownBlockedEdges.clear();

         for (int i = 0; i < request.knownBlockedEdges.size(); i++) {
            KnownBlockedEdges kbe = (KnownBlockedEdges)request.knownBlockedEdges.get(i);
            Square square1 = this.getSquare(kbe.x, kbe.y, kbe.z);
            if (square1 != null) {
               this.astar.knownBlockedEdges.put(square1.id, kbe);
            }
         }

         VisibilityGraph removeStart = null;
         VisibilityGraph removeGoal = null;
         SearchNode startNode = null;
         SearchNode goalNode = null;
         boolean adjustStart = false;
         boolean adjustGoal = false;

         try {
            Square square = this.getSquare(PZMath.fastfloor(request.startX), PZMath.fastfloor(request.startY), PZMath.fastfloor(requestStartZ));
            if (square == null || square.isReallySolid()) {
               return false;
            }

            if (square.has(504)) {
               startNode = this.astar.getSearchNode(square);
            } else {
               VisibilityGraph vg = this.astar.getVisGraphForSquare(square);
               if (vg != null) {
                  if (!vg.created) {
                     vg.create();
                  }

                  Node vgNode = null;
                  int adjusted = vg.getPointOutsideObstacles(request.startX, request.startY, requestStartZ, this.adjustStartData);
                  if (adjusted == -1) {
                     return false;
                  }

                  if (adjusted == 1) {
                     adjustStart = true;
                     vgNode = this.adjustStartData.node;
                     if (this.adjustStartData.isNodeNew) {
                        removeStart = vg;
                     }
                  }

                  if (vgNode == null) {
                     vgNode = Node.alloc().init(request.startX, request.startY, PZMath.fastfloor(requestStartZ));
                     vg.addNode(vgNode);
                     removeStart = vg;
                  }

                  startNode = this.astar.getSearchNode(vgNode);
               }
            }

            if (startNode == null) {
               startNode = this.astar.getSearchNode(square);
            }

            if (this.getChunkFromSquarePos(PZMath.fastfloor(request.targetX), PZMath.fastfloor(request.targetY)) == null) {
               goalNode = this.astar.getSearchNode(PZMath.fastfloor(request.targetX), PZMath.fastfloor(request.targetY));
            } else {
               square = this.getSquare(PZMath.fastfloor(request.targetX), PZMath.fastfloor(request.targetY), PZMath.fastfloor(requestTargetZ));
               if (square == null || square.isReallySolid()) {
                  return false;
               }

               if ((
                     PZMath.fastfloor(request.startX) != PZMath.fastfloor(request.targetX)
                        || PZMath.fastfloor(request.startY) != PZMath.fastfloor(request.targetY)
                        || PZMath.fastfloor(request.startZ) != PZMath.fastfloor(request.targetZ)
                  )
                  && this.isBlockedInAllDirections(PZMath.fastfloor(request.targetX), PZMath.fastfloor(request.targetY), PZMath.fastfloor(requestTargetZ))) {
                  return false;
               }

               if (square.has(504)) {
                  goalNode = this.astar.getSearchNode(square);
               } else {
                  VisibilityGraph vg = this.astar.getVisGraphForSquare(square);
                  if (vg != null) {
                     if (!vg.created) {
                        vg.create();
                     }

                     Node vgNode = null;
                     int adjusted = vg.getPointOutsideObstacles(request.targetX, request.targetY, requestTargetZ, this.adjustGoalData);
                     if (adjusted == -1) {
                        return false;
                     }

                     if (adjusted == 1) {
                        adjustGoal = true;
                        vgNode = this.adjustGoalData.node;
                        if (this.adjustGoalData.isNodeNew) {
                           removeGoal = vg;
                        }
                     }

                     if (vgNode == null) {
                        vgNode = Node.alloc().init(request.targetX, request.targetY, PZMath.fastfloor(requestTargetZ));
                        vg.addNode(vgNode);
                        removeGoal = vg;
                     }

                     goalNode = this.astar.getSearchNode(vgNode);
                  } else {
                     for (int i = 0; i < this.graphs.size(); i++) {
                        vg = this.graphs.get(i);
                        if (vg.contains(square, 1)) {
                           Node vgNode = this.getPointOutsideObjects(square, request.targetX, request.targetY);
                           vg.addNode(vgNode);
                           if (vgNode.x != request.targetX || vgNode.y != request.targetY) {
                              adjustGoal = true;
                              this.adjustGoalData.isNodeNew = false;
                           }

                           removeGoal = vg;
                           goalNode = this.astar.getSearchNode(vgNode);
                           break;
                        }
                     }
                  }
               }

               if (goalNode == null) {
                  goalNode = this.astar.getSearchNode(square);
               }
            }

            ArrayList<ISearchNode> path = this.astar.shortestPath(request, startNode, goalNode);
            if (path != null) {
               if (path.size() != 1) {
                  this.cleanPath(path, request, adjustStart, adjustGoal, goalNode);
                  if (DebugOptions.instance.pathfindSmoothPlayerPath.getValue() && request.mover instanceof IsoPlayer isoPlayer && !isoPlayer.isNpc()) {
                     this.smoothPath(request.path);
                  }

                  this.fixPathZ(request.path);
                  return true;
               }

               SearchNode node = startNode;
               request.path.addNode(node);
               node = goalNode;
               if (!adjustGoal && node.square != null && node.square.x + 0.5F != request.targetX && goalNode.square.y + 0.5F != request.targetY) {
                  request.path.addNode(request.targetX, request.targetY, requestTargetZ, 0);
               } else {
                  request.path.addNode(node);
               }

               this.fixPathZ(request.path);
               return true;
            }
         } finally {
            if (render) {
               for (VisibilityGraph vg : this.graphs) {
                  vg.render();
               }
            }

            if (removeStart != null) {
               removeStart.removeNode(startNode.vgNode);
               startNode.vgNode.release();
            }

            if (removeGoal != null) {
               removeGoal.removeNode(goalNode.vgNode);
               goalNode.vgNode.release();
            }

            for (int i = 0; i < this.astar.searchNodes.size(); i++) {
               ((SearchNode)this.astar.searchNodes.get(i)).release();
            }

            if (adjustStart && this.adjustStartData.isNodeNew) {
               for (int i = 0; i < this.adjustStartData.node.edges.size(); i++) {
                  Edge edge = (Edge)this.adjustStartData.node.edges.get(i);
                  edge.obstacle.unsplit(this.adjustStartData.node, edge.edgeRing);
               }

               this.adjustStartData.graph.edges.remove(this.adjustStartData.newEdge);
            }

            if (adjustGoal && this.adjustGoalData.isNodeNew) {
               for (int i = 0; i < this.adjustGoalData.node.edges.size(); i++) {
                  Edge edge = (Edge)this.adjustGoalData.node.edges.get(i);
                  edge.obstacle.unsplit(this.adjustGoalData.node, edge.edgeRing);
               }

               this.adjustGoalData.graph.edges.remove(this.adjustGoalData.newEdge);
            }
         }

         return false;
      }
   }

   private boolean findPathHighLevelThenLowLevel(PathFindRequest request, boolean bRender) {
      this.astar.init(this.graphs, this.squareToNode);
      this.astar.mover.set(request);
      ArrayList<HLLevelTransition> levelTransitionList = HLGlobals.levelTransitionList;

      try {
         AbstractPerformanceProfileProbe startX = HLAStar.PerfFindPath.profile();

         try {
            HLGlobals.astar
               .findPath(
                  this.astar.mover,
                  request.startX,
                  request.startY,
                  PZMath.fastfloor(request.startZ + 32.0F),
                  request.targetX,
                  request.targetY,
                  PZMath.fastfloor(request.targetZ + 32.0F),
                  levelTransitionList,
                  HLGlobals.chunkLevelList,
                  HLGlobals.bottomOfLevelTransition,
                  bRender
               );
         } catch (Throwable var21) {
            if (startX != null) {
               try {
                  startX.close();
               } catch (Throwable var20) {
                  var21.addSuppressed(var20);
               }
            }

            throw var21;
         }

         if (startX != null) {
            startX.close();
         }
      } finally {
         for (SearchNode node : this.astar.searchNodes) {
            node.release();
         }

         this.astar.searchNodes.clear();
      }

      if (HLGlobals.chunkLevelList.isEmpty()) {
         return false;
      }

      if (this.astar.mover.allowedChunkLevels != null) {
         this.astar.mover.allowedChunkLevels.clear();
         this.astar.mover.allowedChunkLevels.addAll(HLGlobals.chunkLevelList);
      }

      if (this.astar.mover.allowedLevelTransitions != null) {
         this.astar.mover.allowedLevelTransitions.clear();
         this.astar.mover.allowedLevelTransitions.addAll(levelTransitionList);
      }

      if (levelTransitionList.isEmpty()) {
         request.minLevel = PZMath.fastfloor(request.startZ + 32.0F);
         request.maxLevel = PZMath.fastfloor(request.targetZ + 32.0F);
         return this.findPath(request, bRender);
      }

      HLGlobals.path.clear();
      float startX = request.startX;
      float startY = request.startY;
      float startZ = request.startZ;
      float targetX = request.targetX;
      float targetY = request.targetY;
      float targetZ = request.targetZ;
      int currentZ = PZMath.fastfloor(startZ + 32.0F);
      boolean bStartOnStair = HLGlobals.astar.getLevelTransitionAt(PZMath.fastfloor(startX), PZMath.fastfloor(startY), PZMath.fastfloor(startZ + 32.0F))
         != null;
      boolean bTargetOnStair = HLGlobals.astar.getLevelTransitionAt(PZMath.fastfloor(targetX), PZMath.fastfloor(targetY), PZMath.fastfloor(targetZ + 32.0F))
         != null;

      for (int i = 0; i < levelTransitionList.size(); i++) {
         HLLevelTransition levelTransition = levelTransitionList.get(i);
         boolean bLastTransition = i == levelTransitionList.size() - 1;
         if (bTargetOnStair && bLastTransition) {
            break;
         }

         boolean bGoingDown = (Boolean)HLGlobals.bottomOfLevelTransition.get(i);
         request.startX = startX;
         request.startY = startY;
         request.startZ = startZ;
         request.targetX = (bGoingDown ? levelTransition.getBottomFloorX() : levelTransition.getTopFloorX()) + 0.5F;
         request.targetY = (bGoingDown ? levelTransition.getBottomFloorY() : levelTransition.getTopFloorY()) + 0.5F;
         request.targetZ = (bGoingDown ? levelTransition.getBottomFloorZ() : levelTransition.getTopFloorZ()) - 32;
         request.minLevel = levelTransition.getBottomFloorZ();
         request.maxLevel = levelTransition.getTopFloorZ();
         if (!this.findPath(request, bRender)) {
            return false;
         }

         this.addPathSegmentNodes(request.path, HLGlobals.path);
         request.path.nodes.clear();
         startX = request.targetX;
         startY = request.targetY;
         startZ = request.targetZ;
      }

      request.startX = startX;
      request.startY = startY;
      request.startZ = startZ;
      request.targetX = targetX;
      request.targetY = targetY;
      request.targetZ = targetZ;
      request.minLevel = PZMath.fastfloor(PZMath.min(startZ, targetZ)) + 32;
      request.maxLevel = PZMath.fastfloor(PZMath.max(startZ, targetZ)) + 32;
      if (this.findPath(request, bRender)) {
         this.addPathSegmentNodes(request.path, HLGlobals.path);
         request.path.nodes.clear();
         request.path.nodes.addAll(HLGlobals.path.nodes);
         HLGlobals.path.nodes.clear();
         return true;
      } else {
         return false;
      }
   }

   private void addPathSegmentNodes(Path src, Path dest) {
      for (int j = 0; j < src.nodes.size(); j++) {
         PathNode node = src.getNode(j);
         if (dest.nodes.size() < 2 || !node.isApproximatelyEqual(dest.getLastNode())) {
            dest.nodes.add(node);
         }
      }
   }

   private void cleanPath(ArrayList<ISearchNode> path, PathFindRequest request, boolean adjustStart, boolean adjustGoal, SearchNode goalNode) {
      boolean bNPC = request.mover instanceof IsoPlayer isoPlayer && isoPlayer.isNpc();
      Square squarePrev = null;
      int dxOld = -123;
      int dyOld = -123;

      for (int i = 0; i < path.size(); i++) {
         SearchNode node = (SearchNode)path.get(i);
         float x = node.getX();
         float y = node.getY();
         float z = node.getZ();
         int flags = node.vgNode == null ? 0 : node.vgNode.flags;
         Square squareCur = node.square;
         boolean extendPrev = false;
         if (squareCur != null && squarePrev != null && squareCur.z == squarePrev.z) {
            int dx = squareCur.x - squarePrev.x;
            int dy = squareCur.y - squarePrev.y;
            if (dx == dxOld && dy == dyOld) {
               if (request.path.nodes.size() > 1) {
                  extendPrev = !request.path.getLastNode().hasFlag(65536);
               }

               if (dx == 0 && dy == -1 && squarePrev.has(16384)) {
                  extendPrev = false;
               } else if (dx == 0 && dy == 1 && squareCur.has(16384)) {
                  extendPrev = false;
               } else if (dx == -1 && dy == 0 && squarePrev.has(8192)) {
                  extendPrev = false;
               } else if (dx == 1 && dy == 0 && squareCur.has(8192)) {
                  extendPrev = false;
               }
            } else {
               dxOld = dx;
               dyOld = dy;
            }
         } else {
            dxOld = dyOld = -123; // pzopt: decompiler fix, the jar's chained store (one bipush + dup), Vineflower split it in two
         }

         squarePrev = squareCur;
         if (bNPC) {
            extendPrev = false;
         }

         if (extendPrev) {
            PathNode pathNode = request.path.getLastNode();
            pathNode.x = squareCur.x + 0.5F;
            pathNode.y = squareCur.y + 0.5F;
         } else {
            if (request.path.nodes.size() > 1) {
               PathNode last = request.path.getLastNode();
               if (last.isApproximatelyEqual(x, y, z)) {
                  last.x = x;
                  last.y = y;
                  last.z = z;
                  continue;
               }
            }

            if (i > 0 && node.square != null) {
               SearchNode nodePrev = (SearchNode)path.get(i - 1);
               if (nodePrev.square != null) {
                  int dx = node.square.x - nodePrev.square.x;
                  int dy = node.square.y - nodePrev.square.y;
                  if (dx == 0 && dy == -1 && nodePrev.square.has(16384)) {
                     flags |= 65536;
                  } else if (dx == 0 && dy == 1 && node.square.has(16384)) {
                     flags |= 65536;
                  } else if (dx == -1 && dy == 0 && nodePrev.square.has(8192)) {
                     flags |= 65536;
                  } else if (dx == 1 && dy == 0 && node.square.has(8192)) {
                     flags |= 65536;
                  }
               }
            }

            request.path.addNode(x, y, z, flags);
         }
      }

      if (request.mover instanceof IsoPlayer && !bNPC) {
         if (request.path.isEmpty()) {
            PathNode node0 = null;
         } else {
            request.path.getNode(0);
         }

         if (!adjustGoal
            && goalNode.square != null
            && IsoUtils.DistanceToSquared(goalNode.square.x + 0.5F, goalNode.square.y + 0.5F, request.targetX, request.targetY) > 0.010000001F) {
            request.path.addNode(request.targetX, request.targetY, request.targetZ + 32.0F, 0);
         }
      }

      PathNode prev = null;

      for (int i = 0; i < request.path.nodes.size(); i++) {
         PathNode node = (PathNode)request.path.nodes.get(i);
         PathNode next = i < request.path.nodes.size() - 1 ? (PathNode)request.path.nodes.get(i + 1) : null;
         if (node.hasFlag(1)) {
            boolean crawl = prev != null && prev.hasFlag(2) || next != null && next.hasFlag(2);
            if (!crawl) {
               node.flags &= -4;
            }
         }

         prev = node;
      }
   }

   private void smoothPath(Path path) {
      int index = 0;

      while (index < path.nodes.size() - 2) {
         PathNode node1 = (PathNode)path.nodes.get(index);
         PathNode node2 = (PathNode)path.nodes.get(index + 1);
         PathNode node3 = (PathNode)path.nodes.get(index + 2);
         if (PZMath.fastfloor(node1.z) == PZMath.fastfloor(node2.z) && PZMath.fastfloor(node1.z) == PZMath.fastfloor(node3.z)) {
            if (!this.lcc.isNotClear(this, node1.x, node1.y, node3.x, node3.y, PZMath.fastfloor(node1.z), 20)) {
               path.nodes.remove(index + 1);
               path.nodePool.push(node2);
            } else {
               index++;
            }
         } else {
            index++;
         }
      }
   }

   private void fixPathZ(Path path) {
      for (int i = 0; i < path.nodes.size(); i++) {
         ((PathNode)path.nodes.get(i)).z -= 32.0F;
      }
   }

   public float getApparentZ(IsoGridSquare square) {
      if (square.has(IsoObjectType.stairsTW) || square.has(IsoObjectType.stairsTN)) {
         return square.z + 0.75F;
      } else if (square.has(IsoObjectType.stairsMW) || square.has(IsoObjectType.stairsMN)) {
         return square.z + 0.5F;
      } else {
         return !square.has(IsoObjectType.stairsBW) && !square.has(IsoObjectType.stairsBN) ? square.z : square.z + 0.25F;
      }
   }

   public void render() {
      if (Core.debug) {
         boolean bPathToMouse = DebugOptions.instance.pathfindPathToMouseEnable.getValue()
            && !this.testRequest.done
            && IsoPlayer.getInstance().getPath2() == null;
         if (DebugOptions.instance.pathfindRenderClusters.getValue()) {
            synchronized (this.renderLock) {
               for (VehicleCluster cluster : this.clusters) {
                  for (VehicleRect rect : cluster.rects) {
                     LineDrawer.addRect(rect.x, rect.y, rect.z - 32, rect.w, rect.h, 0.0F, 0.0F, 1.0F);
                  }

                  VehicleRect rect = cluster.bounds();
                  rect.release();
               }

               if (!bPathToMouse) {
                  for (VisibilityGraph vg : this.graphs) {
                     vg.render();
                  }
               }
            }
         }

         synchronized (this.renderLock) {
            float x = Mouse.getX();
            float y = Mouse.getY();
            int z = IsoPlayer.getInstance().getZi();
            float gridX = IsoUtils.XToIso(x, y, z);
            float gridY = IsoUtils.YToIso(x, y, z);
            Square square = this.getSquare(PZMath.fastfloor(gridX), PZMath.fastfloor(gridY), z);
            if (square != null) {
               VisibilityGraph graph = this.getVisGraphForSquare(square);
               if (graph != null) {
                  for (Obstacle obstacle : graph.obstacles) {
                     if (obstacle.bounds.containsPoint(gridX, gridY) && obstacle.isPointInside(gridX, gridY)) {
                        obstacle.getClosestPointOnEdge(gridX, gridY, this.closestPointOnEdge);
                        float closestX = this.closestPointOnEdge.point.x;
                        float closestY = this.closestPointOnEdge.point.y;
                        LineDrawer.addLine(closestX - 0.05F, closestY - 0.05F, z, closestX + 0.05F, closestY + 0.05F, z, 1.0F, 1.0F, 0.0F, null, false);
                        break;
                     }
                  }
               }
            }
         }

         if (DebugOptions.instance.pathfindRenderLineClearCollide.getValue()) {
            float x = Mouse.getX();
            float y = Mouse.getY();
            int z = IsoPlayer.getInstance().getZi();
            float gridX = IsoUtils.XToIso(x, y, z);
            float gridY = IsoUtils.YToIso(x, y, z);
            LineDrawer.addLine(IsoPlayer.getInstance().getX(), IsoPlayer.getInstance().getY(), z, gridX, gridY, z, 1, 1, 1, null);
            int flags = 9;
            flags |= 2;
            if (this.lccMain.isNotClear(this, IsoPlayer.getInstance().getX(), IsoPlayer.getInstance().getY(), gridX, gridY, z, null, flags)) {
               Vector2f v = this.resolveCollision(IsoPlayer.getInstance(), gridX, gridY, L_render.vector2f);
               LineDrawer.addLine(v.x - 0.05F, v.y - 0.05F, z, v.x + 0.05F, v.y + 0.05F, z, 1.0F, 1.0F, 0.0F, null, false);
            }
         }

         if (GameKeyboard.isKeyDown(209) && !GameKeyboard.wasKeyDown(209)) {
            this.testZ = Math.max(this.testZ - 1, -32);
         }

         if (GameKeyboard.isKeyDown(201) && !GameKeyboard.wasKeyDown(201)) {
            this.testZ = Math.min(this.testZ + 1, 31);
         }

         if (bPathToMouse) {
            float x = Mouse.getX();
            float y = Mouse.getY();
            int z = this.testZ;
            float targetX = IsoUtils.XToIso(x, y, z);
            float targetY = IsoUtils.YToIso(x, y, z);
            float targetZ = z;
            int targetXi = PZMath.fastfloor(targetX);
            int targetYi = PZMath.fastfloor(targetY);
            int targetZi = PZMath.fastfloor(targetZ);

            for (int dy = -1; dy <= 2; dy++) {
               LineDrawer.addLine(targetXi - 1, targetYi + dy, targetZi, targetXi + 2, targetYi + dy, targetZi, 0.3F, 0.3F, 0.3F, null, false);
            }

            for (int dx = -1; dx <= 2; dx++) {
               LineDrawer.addLine(targetXi + dx, targetYi - 1, targetZi, targetXi + dx, targetYi + 2, targetZi, 0.3F, 0.3F, 0.3F, null, false);
            }

            for (int dy = -1; dy <= 1; dy++) {
               for (int dx = -1; dx <= 1; dx++) {
                  float r = 0.3F;
                  float g = 0.0F;
                  float b = 0.0F;
                  IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(targetXi + dx, targetYi + dy, targetZi);
                  if (sq == null || sq.isSolid() || sq.isSolidTrans() || sq.HasStairs()) {
                     LineDrawer.addLine(targetXi + dx, targetYi + dy, targetZi, targetXi + dx + 1, targetYi + dy + 1, targetZi, 0.3F, 0.0F, 0.0F, null, false);
                  }
               }
            }

            float rgb = 0.5F;
            if (z < PZMath.fastfloor(IsoPlayer.getInstance().getZ())) {
               LineDrawer.addLine(
                  targetXi + 0.5F,
                  targetYi + 0.5F,
                  targetZi,
                  targetXi + 0.5F,
                  targetYi + 0.5F,
                  PZMath.fastfloor(IsoPlayer.getInstance().getZ()),
                  0.5F,
                  0.5F,
                  0.5F,
                  null,
                  true
               );
            } else if (z > PZMath.fastfloor(IsoPlayer.getInstance().getZ())) {
               LineDrawer.addLine(
                  targetXi + 0.5F,
                  targetYi + 0.5F,
                  targetZi,
                  targetXi + 0.5F,
                  targetYi + 0.5F,
                  PZMath.fastfloor(IsoPlayer.getInstance().getZ()),
                  0.5F,
                  0.5F,
                  0.5F,
                  null,
                  true
               );
            }

            PathFindRequest request = PathFindRequest.alloc()
               .init(
                  this.testRequest,
                  IsoPlayer.getInstance(),
                  IsoPlayer.getInstance().getX(),
                  IsoPlayer.getInstance().getY(),
                  IsoPlayer.getInstance().getZ(),
                  targetX,
                  targetY,
                  targetZ
               );
            if (DebugOptions.instance.pathfindPathToMouseAllowCrawl.getValue()) {
               request.canCrawl = true;
               if (DebugOptions.instance.pathfindPathToMouseIgnoreCrawlCost.getValue()) {
                  request.ignoreCrawlCost = true;
               }
            }

            if (DebugOptions.instance.pathfindPathToMouseAllowThump.getValue()) {
               request.canThump = true;
            }

            this.testRequest.done = false;
            synchronized (this.renderLock) {
               boolean render = DebugOptions.instance.pathfindRenderClusters.getValue();
               if (this.findPathHighLevelThenLowLevel(request, render) && !request.path.isEmpty()) {
                  for (int i = 0; i < request.path.nodes.size() - 1; i++) {
                     PathNode node1 = (PathNode)request.path.nodes.get(i);
                     PathNode node2 = (PathNode)request.path.nodes.get(i + 1);
                     IsoGridSquare square1 = IsoWorld.instance.currentCell.getGridSquare(node1.x, node1.y, node1.z);
                     IsoGridSquare square2 = IsoWorld.instance.currentCell.getGridSquare(node2.x, node2.y, node2.z);
                     float z1 = square1 == null ? node1.z : this.getApparentZ(square1);
                     float z2 = square2 == null ? node2.z : this.getApparentZ(square2);
                     float r = 1.0F;
                     float g = 1.0F;
                     float b = 0.0F;
                     if (z1 != PZMath.fastfloor(z1) || z2 != PZMath.fastfloor(z2)) {
                        g = 0.0F;
                     }

                     LineDrawer.addLine(node1.x, node1.y, z1, node2.x, node2.y, z2, 1.0F, g, 0.0F, null, true);
                     LineDrawer.addRect(node1.x - 0.05F, node1.y - 0.05F, z1, 0.1F, 0.1F, 1.0F, g, 0.0F);
                  }

                  PathFindBehavior2.closestPointOnPath(
                     IsoPlayer.getInstance().getX(),
                     IsoPlayer.getInstance().getY(),
                     IsoPlayer.getInstance().getZ(),
                     IsoPlayer.getInstance(),
                     request.path,
                     this.pointOnPath
                  );
                  PathNode node1 = (PathNode)request.path.nodes.get(this.pointOnPath.pathIndex);
                  PathNode node2 = (PathNode)request.path.nodes.get(this.pointOnPath.pathIndex + 1);
                  IsoGridSquare square1 = IsoWorld.instance.currentCell.getGridSquare(node1.x, node1.y, node1.z);
                  IsoGridSquare square2 = IsoWorld.instance.currentCell.getGridSquare(node2.x, node2.y, node2.z);
                  float z1 = square1 == null ? node1.z : this.getApparentZ(square1);
                  float z2 = square2 == null ? node2.z : this.getApparentZ(square2);
                  float closestZ = z1 + (z2 - z1) * this.pointOnPath.dist;
                  LineDrawer.addLine(
                     this.pointOnPath.x - 0.05F,
                     this.pointOnPath.y - 0.05F,
                     closestZ,
                     this.pointOnPath.x + 0.05F,
                     this.pointOnPath.y + 0.05F,
                     closestZ,
                     0.0F,
                     1.0F,
                     0.0F,
                     null,
                     true
                  );
                  LineDrawer.addLine(
                     this.pointOnPath.x - 0.05F,
                     this.pointOnPath.y + 0.05F,
                     closestZ,
                     this.pointOnPath.x + 0.05F,
                     this.pointOnPath.y - 0.05F,
                     closestZ,
                     0.0F,
                     1.0F,
                     0.0F,
                     null,
                     true
                  );
                  if (GameKeyboard.isKeyDown(207) && !GameKeyboard.wasKeyDown(207)) {
                     Object obj = LuaManager.env.rawget("ISPathFindAction_pathToLocationF");
                     if (obj != null) {
                        LuaManager.caller.pcall(LuaManager.thread, obj, new Object[]{targetX, targetY, targetZ});
                     }
                  }
               }

               request.release();
            }
         } else {
            for (int i = 0; i < this.testRequest.path.nodes.size() - 1; i++) {
               PathNode node1 = (PathNode)this.testRequest.path.nodes.get(i);
               PathNode node2 = (PathNode)this.testRequest.path.nodes.get(i + 1);
               float r = 1.0F;
               float g = 1.0F;
               float b = 1.0F;
               if (node1.z != PZMath.fastfloor(node1.z) || node2.z != PZMath.fastfloor(node2.z)) {
                  g = 0.0F;
               }

               LineDrawer.addLine(node1.x, node1.y, node1.z, node2.x, node2.y, node2.z, 1.0F, g, 1.0F, null, true);
            }

            this.testRequest.done = false;
         }

         if (DebugOptions.instance.pathfindRenderConnections.getValue()) {
            float screenX = Mouse.getX();
            float screenY = Mouse.getY();
            int z = this.testZ;
            float worldX = IsoUtils.XToIso(screenX, screenY, z);
            float worldY = IsoUtils.YToIso(screenX, screenY, z);
            VisibilityGraph vg = this.getVisGraphAt(worldX, worldY, z, 1);
            if (vg != null) {
               Node closest = vg.getClosestNodeTo(worldX, worldY);
               if (closest != null) {
                  for (Connection cxn : closest.visible) {
                     Node visible = cxn.otherNode(closest);
                     LineDrawer.addLine(closest.x, closest.y, z, visible.x, visible.y, z, 1.0F, 0.0F, 0.0F, null, true);
                  }
               }
            }
         }

         if (GameWindow.states.current == DebugChunkState.instance && bPathToMouse) {
            this.updateMain();
         }
      }
   }

   public void squareChanged(IsoGridSquare square) {
      if (PathfindNative.useNativeCode) {
         square.invalidateVispolyChunkLevel();
         FBORenderCutaways.getInstance().squareChanged(square);
         FMODAmbientWalls.getInstance().squareChanged(square);
         PathfindNative.instance.squareChanged(square);
      } else {
         for (int i = 0; i < DIRECTIONS.length; i++) {
            IsoDirections dir = DIRECTIONS[i];
            IsoGridSquare square2 = square.getAdjacentSquare(dir);
            if (square2 != null) {
               SquareUpdateTask task = SquareUpdateTask.alloc().init(this, square2);
               this.squareTaskQueue.add(task);
            }
         }

         SquareUpdateTask task = SquareUpdateTask.alloc().init(this, square);
         this.squareTaskQueue.add(task);
         this.thread.wake();
         square.invalidateVispolyChunkLevel();
         FBORenderCutaways.getInstance().squareChanged(square);
         FMODAmbientWalls.getInstance().squareChanged(square);
      }
   }

   public void addChunkToWorld(IsoChunk chunk) {
      ChunkUpdateTask task = ChunkUpdateTask.alloc().init(this, chunk);
      this.chunkTaskQueue.add(task);
      this.thread.wake();
   }

   public void removeChunkFromWorld(IsoChunk chunk) {
      if (this.thread != null) {
         ChunkRemoveTask task = ChunkRemoveTask.alloc().init(this, chunk);
         this.chunkTaskQueue.add(task);
         this.thread.wake();
      }
   }

   public void addVehicleToWorld(BaseVehicle vehicle) {
      VehicleAddTask task = VehicleAddTask.alloc();
      task.init(this, vehicle);
      this.vehicleTaskQueue.add(task);
      VehicleState state = VehicleState.alloc().init(vehicle);
      this.vehicleState.put(vehicle, state);
      this.thread.wake();
   }

   public void updateVehicle(BaseVehicle vehicle) {
      VehicleUpdateTask task = VehicleUpdateTask.alloc();
      task.init(this, vehicle);
      this.vehicleTaskQueue.add(task);
      this.thread.wake();
   }

   public void removeVehicleFromWorld(BaseVehicle vehicle) {
      if (this.thread != null) {
         VehicleRemoveTask task = VehicleRemoveTask.alloc();
         task.init(this, vehicle);
         this.vehicleTaskQueue.add(task);
         VehicleState state = this.vehicleState.remove(vehicle);
         if (state != null) {
            state.vehicle = null;
            state.release();
         }

         this.thread.wake();
      }
   }

   private Cell getCellFromSquarePos(int x, int y) {
      x -= this.minX * 256;
      y -= this.minY * 256;
      if (x >= 0 && y >= 0) {
         int cellX = x / 256;
         int cellY = y / 256;
         return cellX < this.width && cellY < this.height ? this.cells[cellX][cellY] : null;
      } else {
         return null;
      }
   }

   public Cell getCellFromChunkPos(int wx, int wy) {
      return this.getCellFromSquarePos(wx * 8, wy * 8);
   }

   public Chunk allocChunkIfNeeded(int wx, int wy) {
      Cell cell = this.getCellFromChunkPos(wx, wy);
      return cell == null ? null : cell.allocChunkIfNeeded(wx, wy);
   }

   public Chunk getChunkFromChunkPos(int wx, int wy) {
      Cell cell = this.getCellFromChunkPos(wx, wy);
      return cell == null ? null : cell.getChunkFromChunkPos(wx, wy);
   }

   public Chunk getChunkFromSquarePos(int x, int y) {
      Cell cell = this.getCellFromSquarePos(x, y);
      return cell == null ? null : cell.getChunkFromChunkPos(PZMath.coorddivision(x, 8), PZMath.coorddivision(y, 8));
   }

   public Square getSquare(int x, int y, int z) {
      Chunk chunk = this.getChunkFromSquarePos(x, y);
      return chunk == null ? null : chunk.getSquare(x, y, z);
   }

   public Square getSquareRawZ(int x, int y, int z) {
      Chunk chunk = this.getChunkFromSquarePos(x, y);
      return chunk == null ? null : chunk.getSquare(x, y, z);
   }

   public boolean isBlockedInAllDirections(int x, int y, int z) {
      Square square = this.getSquare(x, y, z);
      if (square == null) {
         return false;
      }

      Square sqN = this.getSquare(x, y - 1, z);
      Square sqS = this.getSquare(x, y + 1, z);
      Square sqW = this.getSquare(x - 1, y, z);
      Square sqE = this.getSquare(x + 1, y, z);
      boolean blockedN = sqN != null && this.astar.canNotMoveBetween(square, sqN, false);
      boolean blockedS = sqS != null && this.astar.canNotMoveBetween(square, sqS, false);
      boolean blockedW = sqW != null && this.astar.canNotMoveBetween(square, sqW, false);
      boolean blockedE = sqE != null && this.astar.canNotMoveBetween(square, sqE, false);
      return blockedN && blockedS && blockedW && blockedE;
   }

   public boolean canMoveBetween(PMMover mover, int x1, int y1, int z1, int x2, int y2, int z2) {
      Square square1 = this.getSquare(x1, y1, z1);
      Square square2 = this.getSquare(x2, y2, z2);
      if (square1 != null && square2 != null) {
         PMMover mover1 = this.astar.mover;

         try {
            this.astar.mover = mover;
            return this.astar.canMoveBetween(square1, square2, false);
         } finally {
            this.astar.mover = mover1;
         }
      } else {
         return false;
      }
   }

   public boolean canNotMoveBetween(PMMover mover, int x1, int y1, int z1, int x2, int y2, int z2) {
      return !this.canMoveBetween(mover, x1, y1, z1, x2, y2, z2);
   }

   public void init(IsoMetaGrid metaGrid) {
      this.minX = metaGrid.getMinX();
      this.minY = metaGrid.getMinY();
      this.width = metaGrid.getWidth();
      this.height = metaGrid.getHeight();
      this.cells = new Cell[this.width][this.height];

      for (int y = 0; y < this.height; y++) {
         for (int x = 0; x < this.width; x++) {
            this.cells[x][y] = Cell.alloc().init(this, this.minX + x, this.minY + y);
         }
      }

      this.thread = new PolygonalMap2.PMThread();
      this.thread.setName("PolyPathThread");
      this.thread.setDaemon(true);
      this.thread.start();
   }

   public void stop() {
      this.thread.stop = true;
      this.thread.wake();

      while (this.thread.isAlive()) {
         try {
            Thread.sleep(5L);
         } catch (InterruptedException var3) {
         }
      }

      for (int y = 0; y < this.height; y++) {
         for (int x = 0; x < this.width; x++) {
            if (this.cells[x][y] != null) {
               this.cells[x][y].release();
            }
         }
      }

      for (IChunkTask task = this.chunkTaskQueue.poll(); task != null; task = this.chunkTaskQueue.poll()) {
         task.release();
      }

      for (SquareUpdateTask task = this.squareTaskQueue.poll(); task != null; task = this.squareTaskQueue.poll()) {
         task.release();
      }

      for (IVehicleTask task = this.vehicleTaskQueue.poll(); task != null; task = this.vehicleTaskQueue.poll()) {
         task.release();
      }

      for (PathRequestTask task = this.requestTaskQueue.poll(); task != null; task = this.requestTaskQueue.poll()) {
         task.release();
      }

      while (!this.requests.isEmpty()) {
         this.requests.removeLast().release();
      }

      while (!this.requestToMain.isEmpty()) {
         this.requestToMain.remove().release();
      }

      for (int i = 0; i < this.vehicles.size(); i++) {
         Vehicle vehicle = this.vehicles.get(i);
         vehicle.release();
      }

      for (VehicleState state : this.vehicleState.values()) {
         state.release();
      }

      this.requestMap.clear();
      this.vehicles.clear();
      this.vehicleState.clear();
      this.vehicleMap.clear();
      this.cells = null;
      this.thread = null;
      this.rebuild = true;
   }

   public void updateMain() {
      for (BaseVehicle vehicle : IsoWorld.instance.currentCell.getVehicles()) {
         VehicleState state = this.vehicleState.get(vehicle);
         if (state != null && state.check()) {
            this.updateVehicle(vehicle);
         }
      }

      for (PathFindRequest request = this.requestToMain.poll(); request != null; request = this.requestToMain.poll()) {
         if (this.requestMap.get(request.mover) == request) {
            this.requestMap.remove(request.mover);
         }

         if (!request.cancel) {
            if (request.path.isEmpty()) {
               request.finder.Failed(request.mover);
            } else {
               request.finder.Succeeded(request.path, request.mover);
            }
         }

         request.release();
      }
   }

   public void updateThread() {
      for (IChunkTask task = this.chunkTaskQueue.poll(); task != null; task = this.chunkTaskQueue.poll()) {
         task.execute();
         task.release();
         this.rebuild = true;
      }

      for (SquareUpdateTask task = this.squareTaskQueue.poll(); task != null; task = this.squareTaskQueue.poll()) {
         task.execute();
         task.release();
      }

      for (IVehicleTask task = this.vehicleTaskQueue.poll(); task != null; task = this.vehicleTaskQueue.poll()) {
         task.execute();
         task.release();
         this.rebuild = true;
      }

      for (PathRequestTask task = this.requestTaskQueue.poll(); task != null; task = this.requestTaskQueue.poll()) {
         task.execute();
         task.release();
      }

      if (this.rebuild) {
         for (int i = 0; i < this.graphs.size(); i++) {
            VisibilityGraph vg = this.graphs.get(i);
            vg.release();
         }

         this.graphs.clear();
         this.squareToNode.forEachValue(this.releaseNodeProc);
         this.createVisibilityGraphs();
         this.rebuild = false;
         ChunkDataZ.epochCount++;
         HLAStar.modificationCount++;
      }

      int requestsPerUpdate = 2;

      while (!this.requests.isEmpty()) {
         PathFindRequest request = this.requests.removeFirst();
         if (request.cancel) {
            this.requestToMain.add(request);
         } else {
            try {
               this.findPathHighLevelThenLowLevel(request, false);
            } catch (Exception ex) {
               ExceptionLogger.logException(ex);
            }

            if (!request.targetXyz.isEmpty()) {
               this.shortestPath.copyFrom(request.path);
               float targetX = request.targetX;
               float targetY = request.targetY;
               float targetZ = request.targetZ;
               float minLength = this.shortestPath.isEmpty() ? Float.MAX_VALUE : this.shortestPath.length();

               for (int i = 0; i < request.targetXyz.size(); i += 3) {
                  request.targetX = request.targetXyz.get(i);
                  request.targetY = request.targetXyz.get(i + 1);
                  request.targetZ = request.targetXyz.get(i + 2);
                  request.path.clear();
                  this.findPathHighLevelThenLowLevel(request, false);
                  if (!request.path.isEmpty()) {
                     float length = request.path.length();
                     if (length < minLength) {
                        minLength = length;
                        this.shortestPath.copyFrom(request.path);
                        targetX = request.targetX;
                        targetY = request.targetY;
                        targetZ = request.targetZ;
                     }
                  }
               }

               request.path.copyFrom(this.shortestPath);
               request.targetX = targetX;
               request.targetY = targetY;
               request.targetZ = targetZ;
            }

            this.requestToMain.add(request);
            if (--requestsPerUpdate == 0) {
               break;
            }
         }
      }
   }

   public PathFindRequest addRequest(IPathfinder pathfinder, Mover mover, float startX, float startY, float startZ, float targetX, float targetY, float targetZ) {
      this.cancelRequest(mover);
      PathFindRequest request = PathFindRequest.alloc().init(pathfinder, mover, startX, startY, startZ, targetX, targetY, targetZ);
      this.requestMap.put(mover, request);
      PathRequestTask task = PathRequestTask.alloc().init(this, request);
      this.requestTaskQueue.add(task);
      this.thread.wake();
      return request;
   }

   public void cancelRequest(Mover mover) {
      PathFindRequest request = this.requestMap.remove(mover);
      if (request != null) {
         request.cancel = true;
      }
   }

   public ArrayList<Point> getPointInLine(float fromX, float fromY, float toX, float toY, int z) {
      PointPool pointPool = new PointPool();
      ArrayList<Point> result = new ArrayList<>();
      this.supercover(fromX, fromY, toX, toY, z, pointPool, result);
      return result;
   }

   public void supercover(float x0, float y0, float x1, float y1, int z, PointPool pointPool, ArrayList<Point> pts) {
      double dx = Math.abs(x1 - x0);
      double dy = Math.abs(y1 - y0);
      int x = PZMath.fastfloor(x0);
      int y = PZMath.fastfloor(y0);
      int n = 1;
      int xInc;
      double error;
      if (dx == 0.0) {
         xInc = 0;
         error = Double.POSITIVE_INFINITY;
      } else if (x1 > x0) {
         xInc = 1;
         n += PZMath.fastfloor(x1) - x;
         error = (PZMath.fastfloor(x0) + 1 - x0) * dy;
      } else {
         xInc = -1;
         n += x - PZMath.fastfloor(x1);
         error = (x0 - PZMath.fastfloor(x0)) * dy;
      }

      int yInc;
      if (dy == 0.0) {
         yInc = 0;
         error -= Double.POSITIVE_INFINITY;
      } else if (y1 > y0) {
         yInc = 1;
         n += PZMath.fastfloor(y1) - y;
         error -= (PZMath.fastfloor(y0) + 1 - y0) * dx;
      } else {
         yInc = -1;
         n += y - PZMath.fastfloor(y1);
         error -= (y0 - PZMath.fastfloor(y0)) * dx;
      }

      for (; n > 0; n--) {
         Point pt = pointPool.alloc().init(x, y);
         if (pts.contains(pt)) {
            pointPool.release(pt);
         } else {
            pts.add(pt);
         }

         if (error > 0.0) {
            y += yInc;
            error -= dx;
         } else {
            x += xInc;
            error += dy;
         }
      }
   }

   public boolean lineClearCollide(float fromX, float fromY, float toX, float toY, int z) {
      return this.lineClearCollide(fromX, fromY, toX, toY, z, null);
   }

   public boolean lineClearCollide(float fromX, float fromY, float toX, float toY, int z, IsoMovingObject ignoreVehicle) {
      return this.lineClearCollide(fromX, fromY, toX, toY, z, ignoreVehicle, true, true);
   }

   public boolean lineClearCollide(
      float fromX, float fromY, float toX, float toY, int z, IsoMovingObject ignoreVehicle, boolean ignoreDoors, boolean closeToWalls
   ) {
      int flags = 0;
      if (ignoreDoors) {
         flags |= 1;
      }

      if (closeToWalls) {
         flags |= 2;
      }

      if (Core.debug && DebugOptions.instance.pathfindRenderLineClearCollide.getValue()) {
         flags |= 8;
      }

      return this.lineClearCollide(fromX, fromY, toX, toY, z, ignoreVehicle, flags);
   }

   public boolean lineClearCollide(float fromX, float fromY, float toX, float toY, int z, IsoMovingObject ignoreVehicle, int flags) {
      BaseVehicle vehicle = null;
      if (ignoreVehicle instanceof IsoGameCharacter isoGameCharacter) {
         vehicle = isoGameCharacter.getVehicle();
      } else if (ignoreVehicle instanceof BaseVehicle baseVehicle) {
         vehicle = baseVehicle;
      }

      synchronized (this.lccMain) { // pzopt: lccMain's point list / pool are shared; PZMulticore runs zombie updates on several threads
         return this.lccMain.isNotClear(this, fromX, fromY, toX, toY, z, vehicle, flags);
      } // pzopt
   }

   public Vector2 getCollidepoint(float fromX, float fromY, float toX, float toY, int z, IsoMovingObject ignoreVehicle, int flags) {
      BaseVehicle vehicle = null;
      if (ignoreVehicle instanceof IsoGameCharacter isoGameCharacter) {
         vehicle = isoGameCharacter.getVehicle();
      } else if (ignoreVehicle instanceof BaseVehicle baseVehicle) {
         vehicle = baseVehicle;
      }

      synchronized (this.lccMain) { // pzopt: as lineClearCollide
         return this.lccMain.getCollidepoint(this, fromX, fromY, toX, toY, z, vehicle, flags);
      } // pzopt
   }

   public boolean canStandAt(float x, float y, int z, IsoMovingObject ignoreVehicle, boolean ignoreDoors, boolean closeToWalls) {
      BaseVehicle vehicle = null;
      if (ignoreVehicle instanceof IsoGameCharacter isoGameCharacter) {
         vehicle = isoGameCharacter.getVehicle();
      } else if (ignoreVehicle instanceof BaseVehicle baseVehicle) {
         vehicle = baseVehicle;
      }

      int flags = 0;
      if (ignoreDoors) {
         flags |= 1;
      }

      if (closeToWalls) {
         flags |= 2;
      }

      if (Core.debug && DebugOptions.instance.pathfindRenderLineClearCollide.getValue()) {
         flags |= 8;
      }

      return this.canStandAt(x, y, z, vehicle, flags);
   }

   public boolean canStandAt(float x, float y, int z, BaseVehicle ignoreVehicle, int flags) {
      synchronized (this.lccMain) { // pzopt: as lineClearCollide
         return this.lccMain.canStandAtOld(this, x, y, z, ignoreVehicle, flags);
      } // pzopt
   }

   public boolean intersectLineWithVehicle(float x1, float y1, float x2, float y2, BaseVehicle vehicle, Vector2 out) {
      if (vehicle != null && vehicle.getScript() != null) {
         float[] ff = this.tempFloats;
         ff[0] = vehicle.getPoly().x1;
         ff[1] = vehicle.getPoly().y1;
         ff[2] = vehicle.getPoly().x2;
         ff[3] = vehicle.getPoly().y2;
         ff[4] = vehicle.getPoly().x3;
         ff[5] = vehicle.getPoly().y3;
         ff[6] = vehicle.getPoly().x4;
         ff[7] = vehicle.getPoly().y4;
         float nearest = Float.MAX_VALUE;

         for (int i = 0; i < 8; i += 2) {
            float x3 = ff[i % 8];
            float y3 = ff[(i + 1) % 8];
            float x4 = ff[(i + 2) % 8];
            float y4 = ff[(i + 3) % 8];
            double denom = (y4 - y3) * (x2 - x1) - (x4 - x3) * (y2 - y1);
            if (denom == 0.0) {
               return false;
            }

            double ua = ((x4 - x3) * (y1 - y3) - (y4 - y3) * (x1 - x3)) / denom;
            double ub = ((x2 - x1) * (y1 - y3) - (y2 - y1) * (x1 - x3)) / denom;
            if (ua >= 0.0 && ua <= 1.0 && ub >= 0.0 && ub <= 1.0) {
               float intersectX = (float)(x1 + ua * (x2 - x1));
               float intersectY = (float)(y1 + ua * (y2 - y1));
               float dist = IsoUtils.DistanceTo(x1, y1, intersectX, intersectY);
               if (dist < nearest) {
                  out.set(intersectX, intersectY);
                  nearest = dist;
               }
            }
         }

         return nearest < Float.MAX_VALUE;
      } else {
         return false;
      }
   }

   public Vector2f resolveCollision(IsoGameCharacter chr, float nx, float ny, Vector2f finalPos) {
      return GameClient.client && chr.isSkipResolveCollision() ? finalPos.set(nx, ny) : this.collideWithObstacles.resolveCollision(chr, nx, ny, finalPos);
   }

   public boolean resolveCollision(IsoGameCharacter chr, float radius, Vector2f finalPos) {
      return GameClient.client && chr.isSkipResolveCollision() ? false : this.collideWithObstacles.resolveCollision(chr, radius, finalPos);
   }

   private final class PMThread extends Thread {
      private final Object notifier = new Object();
      private boolean stop;

      @Override
      public void run() {
         while (!this.stop) {
            try {
               this.runInner();
            } catch (Exception ex) {
               ExceptionLogger.logException(ex);
            }
         }
      }

      private void runInner() {
         PolygonalMap2.this.sync.startFrame();
         synchronized (PolygonalMap2.this.renderLock) {
            PolygonalMap2.instance.updateThread();
         }

         PolygonalMap2.this.sync.endFrame();

         while (this.shouldWait()) {
            synchronized (this.notifier) {
               try {
                  this.notifier.wait();
               } catch (InterruptedException var4) {
               }
            }
         }
      }

      private boolean shouldWait() {
         if (this.stop) {
            return false;
         } else if (!PolygonalMap2.instance.chunkTaskQueue.isEmpty()) {
            return false;
         } else if (!PolygonalMap2.instance.squareTaskQueue.isEmpty()) {
            return false;
         } else if (!PolygonalMap2.instance.vehicleTaskQueue.isEmpty()) {
            return false;
         } else {
            return !PolygonalMap2.instance.requestTaskQueue.isEmpty() ? false : PolygonalMap2.instance.requests.isEmpty();
         }
      }

      private void wake() {
         synchronized (this.notifier) {
            this.notifier.notify();
         }
      }
   }
}
