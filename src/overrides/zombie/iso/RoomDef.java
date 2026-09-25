package zombie.iso;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import org.joml.Vector2f;
import zombie.UsedFromLua;
import zombie.Lua.LuaManager.GlobalObject;
import zombie.core.math.PZMath;
import zombie.inventory.ItemPickerJava;
import zombie.inventory.ItemPickerJava.ItemPickerRoom;
import zombie.iso.areas.IsoRoom;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.randomizedWorld.randomizedBuilding.RandomizedBuildingBase;
import zombie.util.list.PZArrayUtil;
import zombie.vehicles.BaseVehicle;

@UsedFromLua
public final class RoomDef {
   private static final ArrayList<IsoGridSquare> squareChoices = new ArrayList<>();
   public boolean explored;
   public boolean doneSpawn;
   public int indoorZombies;
   public int spawnCount = -1;
   public boolean lightsActive;
   public String name;
   public int level;
   public BuildingDef building;
   public long id;
   public final ArrayList<RoomDef.RoomRect> rects = new ArrayList<>(1);
   public final ArrayList<MetaObject> objects = new ArrayList<>(0);
   public int x = 100000;
   public int y = 100000;
   public int x2 = -10000;
   public int y2 = -10000;
   public int area;
   private final HashMap<String, Integer> proceduralSpawnedContainer = new HashMap<>();
   private boolean roofFixed;
   public long metaId;
   public boolean userDefined;

   public RoomDef(long id, String name) {
      this.id = id;
      this.name = name;
   }

   public RoomDef() {
      this(0L, "");
   }

   public long getID() {
      return this.id;
   }

   public String getIDString() {
      return String.valueOf(this.getID());
   }

   public boolean isExplored() {
      return this.explored;
   }

   public boolean isInside(int x, int y, int z) {
      int wx = this.building.x;
      int wy = this.building.y;

      for (int n = 0; n < this.rects.size(); n++) {
         int rx = this.rects.get(n).x;
         int ry = this.rects.get(n).y;
         int rx2 = this.rects.get(n).getX2();
         int ry2 = this.rects.get(n).getY2();
         if (x >= rx && y >= ry && x < rx2 && y < ry2 && z == this.level) {
            return true;
         }
      }

      return false;
   }

   public boolean contains(int x, int y) {
      for (int i = 0; i < this.rects.size(); i++) {
         RoomDef.RoomRect rect = this.rects.get(i);
         if (rect.contains(x, y)) {
            return true;
         }
      }

      return false;
   }

   public boolean intersects(int x, int y, int w, int h) {
      for (int n = 0; n < this.rects.size(); n++) {
         RoomDef.RoomRect rect = this.rects.get(n);
         if (x + w > rect.getX() && x < rect.getX2() && y + h > rect.getY() && y < rect.getY2()) {
            return true;
         }
      }

      return false;
   }

   public boolean isAdjacent(RoomDef other) {
      for (int j = 0; j < other.rects.size(); j++) {
         RoomDef.RoomRect rect = other.rects.get(j);
         if (this.intersects(rect.getX() - 1, rect.getY() - 1, rect.getW() + 2, rect.getH() + 2)) {
            return true;
         }
      }

      return false;
   }

   public boolean isAdjacent(int x, int y, int w, int h) {
      return this.intersects(x - 1, y - 1, w + 2, h + 2);
   }

   public boolean overlaps(RoomDef other) {
      for (int i = 0; i < other.getRects().size(); i++) {
         RoomDef.RoomRect rectOther = other.getRects().get(i);
         if (this.getAreaOverlapping(rectOther.getX(), rectOther.getY(), rectOther.getW(), rectOther.getH()) > 0.0F) {
            return true;
         }
      }

      return false;
   }

   public float getAreaOverlapping(IsoChunk chunk) {
      int chunksPerWidth = 8;
      return this.getAreaOverlapping(chunk.wx * 8, chunk.wy * 8, 8, 8);
   }

   public float getAreaOverlapping(int x, int y, int w, int h) {
      int roomArea = 0;
      int overlapArea = 0;

      for (int n = 0; n < this.rects.size(); n++) {
         RoomDef.RoomRect rect = this.rects.get(n);
         roomArea += rect.w * rect.h;
         int x1 = Math.max(x, rect.x);
         int y1 = Math.max(y, rect.y);
         int x2 = Math.min(x + w, rect.x + rect.w);
         int y2 = Math.min(y + h, rect.y + rect.h);
         if (x2 >= x1 && y2 >= y1) {
            overlapArea += (x2 - x1) * (y2 - y1);
         }
      }

      return overlapArea <= 0 ? 0.0F : (float)overlapArea / roomArea;
   }

   public void forEachChunk(BiConsumer<RoomDef, IsoChunk> consumer) {
      int chunksPerWidth = 8;
      HashSet<IsoChunk> chunks = new HashSet<>();

      for (int i = 0; i < this.rects.size(); i++) {
         RoomDef.RoomRect rect = this.rects.get(i);
         int minX = rect.x / 8;
         int minY = rect.y / 8;
         int maxX = (rect.x + rect.w) / 8;
         int maxY = (rect.y + rect.h) / 8;
         if (PZMath.coordmodulo(rect.x + rect.w, 8) == 0) {
            maxX--;
         }

         if (PZMath.coordmodulo(rect.y + rect.h, 8) == 0) {
            maxY--;
         }

         for (int wy = minY; wy <= maxY; wy++) {
            for (int wx = minX; wx <= maxX; wx++) {
               IsoChunk chunk = GameServer.server ? ServerMap.instance.getChunk(wx, wy) : IsoWorld.instance.currentCell.getChunk(wx, wy);
               if (chunk != null) {
                  chunks.add(chunk);
               }
            }
         }
      }

      chunks.forEach(isoChunk -> consumer.accept(this, isoChunk));
      chunks.clear();
   }

   public void setInvalidateCacheForAllChunks(int playerIndex, long dirtyFlags) {
      this.forEachChunk((roomDef, chunk) -> chunk.getRenderLevels(playerIndex).invalidateAll(dirtyFlags));
   }

   public IsoRoom getIsoRoom() {
      return IsoWorld.instance.metaGrid.getRoomByID(this.id);
   }

   public ArrayList<MetaObject> getObjects() {
      return this.objects;
   }

   public ArrayList<MetaObject> getMetaObjects() {
      return this.objects;
   }

   public void refreshSquares() {
      this.getIsoRoom().refreshSquares();
   }

   public BuildingDef getBuilding() {
      return this.building;
   }

   public void setBuilding(BuildingDef def) {
      this.building = def;
   }

   public String getName() {
      return this.name;
   }

   public void setName(String newName) {
      if (this.name != null) {
         this.name = newName;
      }
   }

   public ArrayList<RoomDef.RoomRect> getRects() {
      return this.rects;
   }

   public int getY() {
      return this.y;
   }

   public int getX() {
      return this.x;
   }

   public int getX2() {
      return this.x2;
   }

   public int getY2() {
      return this.y2;
   }

   public int getW() {
      return this.x2 - this.x;
   }

   public int getH() {
      return this.y2 - this.y;
   }

   public int getZ() {
      return this.level;
   }

   public void CalculateBounds() {
      this.x = 10000000;
      this.y = 10000000;
      this.x2 = -1000000;
      this.y2 = -1000000;
      this.area = 0;

      for (int nn = 0; nn < this.rects.size(); nn++) {
         RoomDef.RoomRect rect = this.rects.get(nn);
         if (rect.x < this.x) {
            this.x = rect.x;
         }

         if (rect.y < this.y) {
            this.y = rect.y;
         }

         if (rect.x + rect.w > this.x2) {
            this.x2 = rect.x + rect.w;
         }

         if (rect.y + rect.h > this.y2) {
            this.y2 = rect.y + rect.h;
         }

         this.area = this.area + rect.w * rect.h;
      }
   }

   public long calculateMetaID(int cellX, int cellY) {
      int minX = Integer.MAX_VALUE;
      int minY = Integer.MAX_VALUE;

      for (int i = 0; i < this.rects.size(); i++) {
         RoomDef.RoomRect rect = this.rects.get(i);
         if (rect.x <= minX && rect.y < minY) {
            minX = rect.x;
            minY = rect.y;
         }
      }

      minX -= cellX * 256;
      minY -= cellY * 256;
      return (long)this.level << 32 | (long)minY << 16 | minX;
   }

   public void offset(int dx, int dy) {
      this.x += dx;
      this.y += dy;
      this.x2 += dx;
      this.y2 += dy;

      for (int j = 0; j < this.rects.size(); j++) {
         RoomDef.RoomRect roomRect = this.rects.get(j);
         roomRect.x += dx;
         roomRect.y += dy;
      }
   }

   public int getArea() {
      return this.area;
   }

   public void setExplored(boolean explored) {
      this.explored = explored;
   }

   public IsoGridSquare getFreeSquare() {
      return this.getRandomSquare(square -> square.isFree(false));
   }

   public IsoGridSquare getExtraFreeSquare() {
      return this.getRandomSquare(square -> square.isFree(false) && square.getObjects().size() < 2 && !square.HasStairs() && square.hasFloor());
   }

   public IsoGridSquare getFreeUnoccupiedSquare() {
      return this.getRandomSquare(square -> square.isFree(true));
   }

   public IsoGridSquare getRandomSquare(Predicate<IsoGridSquare> predicate) {
      squareChoices.clear();

      for (int nn = 0; nn < this.rects.size(); nn++) {
         RoomDef.RoomRect rect = this.rects.get(nn);

         for (int x = rect.getX(); x < rect.getX2(); x++) {
            for (int y = rect.getY(); y < rect.getY2(); y++) {
               IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(x, y, this.getZ());
               if (sq != null && predicate != null && predicate.test(sq) || predicate == null) {
                  squareChoices.add(sq);
               }
            }
         }
      }

      return (IsoGridSquare)PZArrayUtil.pickRandom(squareChoices);
   }

   public boolean isEmptyOutside() {
      return "emptyoutside".equalsIgnoreCase(this.name);
   }

   public HashMap<String, Integer> getProceduralSpawnedContainer() {
      return this.proceduralSpawnedContainer;
   }

   public RoomDef.RoomRect getRoomRect(int x, int y, int z) {
      ArrayList<RoomDef.RoomRect> roomRects = this.rects;

      for (int i = 0; i < roomRects.size(); i++) {
         RoomDef.RoomRect rect = roomRects.get(i);
         if (rect.x <= x && rect.y <= y && rect.getX2() >= x && rect.getY2() >= y) {
            return rect;
         }
      }

      return null;
   }

   public boolean isRoofFixed() {
      return this.roofFixed;
   }

   public void setRoofFixed(boolean b) {
      this.roofFixed = b;
   }

   public float getClosestPoint(float x, float y, Vector2f closestXY) {
      float closestDist = Float.MAX_VALUE;
      Vector2f closestXY2 = BaseVehicle.allocVector2f();
      float insideDistSq = 0.5F;

      for (int i = 0; i < this.rects.size(); i++) {
         RoomDef.RoomRect rect = this.rects.get(i);
         float dist = rect.getClosestPoint(x, y, closestXY2);
         if (dist < closestDist) {
            closestDist = dist;
            closestXY.set(closestXY2);
         }

         if (dist <= 0.5F) {
            break;
         }
      }

      BaseVehicle.releaseVector2f(closestXY2);
      return closestDist;
   }

   public void Dispose() {
      this.building = null;
      this.rects.clear();
      this.objects.clear();
      this.proceduralSpawnedContainer.clear();
   }

   public boolean isKidsRoom() {
      if (pzopt.KidsRoom.active()) { // pzopt: kidsRoomMemo, a trashed-house pass asks once per wall square (quadratic in the room)
         Boolean memo = pzopt.KidsRoom.get(this); // pzopt
         if (memo != null) { // pzopt
            if (pzopt.Config.DEV_KIDS_ROOM_CHECK) pzopt.KidsRoom.check(memo, this.pzoptIsKidsRoom()); // pzopt: rig, the memo against a fresh scan
            return memo; // pzopt
         } // pzopt
         boolean result = this.pzoptIsKidsRoom(); // pzopt
         pzopt.KidsRoom.put(this, result); // pzopt
         return result; // pzopt
      } // pzopt
      return this.pzoptIsKidsRoom(); // pzopt
   }

   /** pzopt: the stock isKidsRoom body (kidsRoomMemo), the tile names from a static set instead of a list built per call. */
   private boolean pzoptIsKidsRoom() { // pzopt
      if (Objects.equals(this.name, "kidsbedroom")) {
         return true;
      }

      if (this.getBuilding().getRooms().size() > RandomizedBuildingBase.maximumRoomCount) {
         return false;
      }

      java.util.Set<String> tiles = pzopt.KidsRoom.TILES; // pzopt: kidsRoomMemo, the same 19 names, built once

      for (int x = this.x; x < this.x2; x++) {
         for (int y = this.y; y < this.y2; y++) {
            IsoGridSquare sq = GlobalObject.getCell().getGridSquare(x, y, this.level);
            if (sq != null) {
               for (int i = 0; i < sq.getObjects().size(); i++) {
                  IsoObject obj = (IsoObject)sq.getObjects().get(i);
                  if (obj != null && obj.getSprite() != null && obj.getSprite().name != null && tiles.contains(obj.getSprite().name)) {
                     return true;
                  }
               }
            }
         }
      }

      return false;
   }

   public boolean isShop() {
      String roomName = this.getName();
      if (roomName == null) {
         return false;
      }

      if (ItemPickerJava.rooms.containsKey(roomName)) {
         ItemPickerRoom roomDist = (ItemPickerRoom)ItemPickerJava.rooms.get(roomName);
         if (roomDist != null && roomDist.isShop) {
            return true;
         }
      }

      return false;
   }

   public boolean isUserDefined() {
      return this.userDefined;
   }

   public void copyFrom(RoomDef other) {
      this.explored = other.explored;
      this.doneSpawn = other.doneSpawn;
      this.indoorZombies = other.indoorZombies;
      this.spawnCount = other.spawnCount;
      this.lightsActive = other.lightsActive;
      this.name = other.name;
      this.level = other.level;
      this.proceduralSpawnedContainer.clear();
      this.proceduralSpawnedContainer.putAll(other.proceduralSpawnedContainer);
      this.roofFixed = other.roofFixed;

      for (RoomDef.RoomRect rect2 : other.getRects()) {
         RoomDef.RoomRect rect1 = new RoomDef.RoomRect(rect2.getX(), rect2.getY(), rect2.getW(), rect2.getH());
         this.getRects().add(rect1);
      }

      this.CalculateBounds();
   }

   @UsedFromLua
   public static final class RoomRect {
      public int x;
      public int y;
      public int w;
      public int h;

      public RoomRect() {
      }

      public RoomRect(int x, int y, int w, int h) {
         this.x = x;
         this.y = y;
         this.w = w;
         this.h = h;
      }

      public RoomDef.RoomRect set(int x, int y, int w, int h) {
         this.x = x;
         this.y = y;
         this.w = w;
         this.h = h;
         return this;
      }

      public int getX() {
         return this.x;
      }

      public int getY() {
         return this.y;
      }

      public int getX2() {
         return this.x + this.w;
      }

      public int getY2() {
         return this.y + this.h;
      }

      public int getW() {
         return this.w;
      }

      public int getH() {
         return this.h;
      }

      public boolean contains(float x, float y) {
         return x >= this.getX() && y >= this.getY() && x < this.getX2() && y < this.getY2();
      }

      public float getClosestPoint(float x, float y, Vector2f closestXY) {
         if (this.w == 1 && this.h == 1) {
            closestXY.set(this.x + 0.5F, this.y + 0.5F);
            return IsoUtils.DistanceToSquared(x, y, closestXY.x, closestXY.y);
         }

         if (this.contains(x, y)) {
            closestXY.set(PZMath.fastfloor(x) + 0.5F, PZMath.fastfloor(y) + 0.5F);
            return IsoUtils.DistanceToSquared(x, y, closestXY.x, closestXY.y);
         }

         float closestDist = Float.MAX_VALUE;
         if (this.w > 1) {
            closestDist = this.getClosestPointOnEdge(this.x + 0.5F, this.y + 0.5F, this.x + this.w - 0.5F, this.y + 0.5F, x, y, closestDist, closestXY);
            closestDist = this.getClosestPointOnEdge(
               this.x + this.w - 0.5F, this.y + this.h - 0.5F, this.x + 0.5F, this.y + this.h - 0.5F, x, y, closestDist, closestXY
            );
         }

         if (this.h > 1) {
            closestDist = this.getClosestPointOnEdge(
               this.x + this.w - 0.5F, this.y + 0.5F, this.x + this.w - 0.5F, this.y + this.h - 0.5F, x, y, closestDist, closestXY
            );
            closestDist = this.getClosestPointOnEdge(this.x + 0.5F, this.y + this.h - 0.5F, this.x + 0.5F, this.y + 0.5F, x, y, closestDist, closestXY);
         }

         return closestDist;
      }

      private float getClosestPointOnEdge(float x1, float y1, float x2, float y2, float x3, float y3, float closestDist, Vector2f out) {
         double u = ((x3 - x1) * (x2 - x1) + (y3 - y1) * (y2 - y1)) / (Math.pow(x2 - x1, 2.0) + Math.pow(y2 - y1, 2.0));
         double xu = x1 + u * (x2 - x1);
         double yu = y1 + u * (y2 - y1);
         if (u <= 0.0) {
            xu = x1;
            yu = y1;
         } else if (u >= 1.0) {
            xu = x2;
            yu = y2;
         }

         double distSq = (x3 - xu) * (x3 - xu) + (y3 - yu) * (y3 - yu);
         if (distSq < closestDist) {
            if (x1 == x2) {
               yu = (int)yu + 0.5F;
            } else {
               xu = (int)xu + 0.5F;
            }

            out.set(xu, yu);
            return (float)distSq;
         } else {
            return closestDist;
         }
      }
   }
}
