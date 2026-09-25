package zombie.randomizedWorld.randomizedBuilding;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import zombie.SandboxOptions;
import zombie.UsedFromLua;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.properties.IsoPropertyType;
import zombie.core.random.Rand;
import zombie.core.stash.StashSystem;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.ItemPickerJava;
import zombie.inventory.types.DrainableComboItem;
import zombie.inventory.types.Moveable;
import zombie.iso.BuildingDef;
import zombie.iso.IsoCell;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.RoomDef;
import zombie.iso.SpawnPoints;
import zombie.iso.areas.IsoBuilding;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoWindow;
import zombie.network.GameClient;
import zombie.network.GameServer;

@UsedFromLua
public final class RBTrashed extends RandomizedBuildingBase {
   private static final IsoDirections[] DIRECTIONS = IsoDirections.values();

   public RBTrashed() {
      this.name = "Trashed Building";
      this.setChance(5);
      this.setAlwaysDo(true);
   }

   public void randomizeBuilding(BuildingDef def) {
      pzopt.KidsRoom.begin(); // pzopt: kidsRoomMemo, trashHouse adds or removes none of the kids-room tiles, so a room's answer holds for the pass
      try { // pzopt
         this.trashHouse(def);
      } finally { // pzopt
         pzopt.KidsRoom.end(); // pzopt
      } // pzopt
   }

   public boolean isValid(BuildingDef def, boolean force) {
      this.debugLine = "";
      if (GameClient.client) {
         return false;
      }

      if (StashSystem.isStashBuilding(def)) {
         this.debugLine = "Stash buildings are invalid";
         return false;
      }

      if (SpawnPoints.instance.isSpawnBuilding(def)) {
         this.debugLine = "Spawn houses are invalid";
         return false;
      }

      if (def.isAllExplored() && !force) {
         return false;
      }

      if (!force) {
         IsoGridSquare sq = IsoCell.getInstance().getGridSquare(def.x, def.y, 0);
         int chance = this.getChance(sq);
         if (Rand.Next(100) > chance) {
            return false;
         }

         for (int i = 0; i < GameServer.Players.size(); i++) {
            IsoPlayer player = (IsoPlayer)GameServer.Players.get(i);
            if (player.getSquare() != null && player.getSquare().getBuilding() != null && player.getSquare().getBuilding().def == def) {
               return false;
            }
         }
      }

      if (SandboxOptions.instance.getCurrentLootedChance() < 1 && !force) {
         return false;
      } else {
         int max = SandboxOptions.instance.maximumLootedBuildingRooms.getValue();
         if (def.getRooms().size() > max) {
            this.debugLine = "Building is too large, maximum " + max + " rooms";
            return false;
         } else {
            return true;
         }
      }
   }

   public IsoGridSquare getFloorSquare(ArrayList<IsoGridSquare> squares, IsoGridSquare square, RoomDef room, IsoBuilding building) {
      if (!Rand.NextBool(3)) {
         return square.getRandomAdjacentFreeSameRoom();
      } else {
         return !Rand.NextBool(5)
            ? Objects.requireNonNull(Objects.requireNonNull(building).getRandomRoom()).getRoomDef().getExtraFreeSquare()
            : room.getExtraFreeSquare();
      }
   }

   public void trashHouse(BuildingDef def) {
      IsoCell cell = IsoWorld.instance.currentCell;
      int trashFactor = 40 + SandboxOptions.instance.getCurrentLootedChance();
      if (trashFactor > 90) {
         trashFactor = 90;
      }

      int baseTrashFactor = trashFactor;
      boolean graff = Rand.NextBool(2);
      boolean removedBar = false;
      ArrayList<InventoryItem> removedItems = new ArrayList<>();
      ArrayList<RBTrashed.AddItemOnGround> addOnGroundItems = new ArrayList<>();

      for (int x = def.x - 1; x < def.x2 + 1; x++) {
         for (int y = def.y - 1; y < def.y2 + 1; y++) {
            for (int z = -32; z < 31; z++) {
               trashFactor = baseTrashFactor;
               boolean canTrash = false;
               if (z < 0) {
                  int depth = z * -1 + 1;
                  trashFactor /= depth;
               }

               IsoGridSquare sq = cell.getGridSquare(x, y, z);
               if (graff && this.isValidGraffSquare(sq, true, false) && Rand.Next(500) <= trashFactor) {
                  this.graffSquare(sq, true);
               }

               if (graff && this.isValidGraffSquare(sq, false, false) && Rand.Next(500) <= trashFactor) {
                  this.graffSquare(sq, false);
               }

               if (sq != null && z == 0 && sq.getRoom() == null) {
                  for (int o = 0; o < sq.getObjects().size(); o++) {
                     IsoObject obj = (IsoObject)sq.getObjects().get(o);
                     if (obj instanceof IsoDoor door
                        && !door.getProperties().has(IsoPropertyType.DOUBLE_DOOR)
                        && !door.getProperties().has(IsoPropertyType.GARAGE_DOOR)
                        && !door.isBarricaded()
                        && !door.IsOpen()) {
                        if (z == 0 && door.isLocked()) {
                           door.destroy();
                        } else if (Rand.Next(200) <= trashFactor) {
                           door.destroy();
                        } else if (Rand.Next(10) <= trashFactor) {
                           door.ToggleDoorSilent();
                           if (door.isLocked()) {
                              door.setLocked(false);
                           }
                        } else {
                           door.setLocked(false);
                        }
                     } else if (obj instanceof IsoDoor door && door.getProperties().has(IsoPropertyType.GARAGE_DOOR) && door.isLocked() && !door.IsOpen()) {
                        door.destroy();
                     }

                     if (sq.getZ() == 0 && obj instanceof IsoWindow window && window.isLocked() && !window.IsOpen()) {
                        window.smashWindow(true, false);
                        window.addBrokenGlass(Rand.NextBool(2));
                     } else if (obj instanceof IsoWindow window && Rand.Next(100) <= trashFactor && !window.IsOpen()) {
                        window.smashWindow(true, false);
                        window.addBrokenGlass(Rand.NextBool(2));
                     }
                  }
               } else if (sq != null && sq.getRoom() != null && !sq.getRoom().getRoomDef().isKidsRoom()) {
                  IsoBuilding building = sq.getBuilding();
                  RoomDef room = sq.getRoom().getRoomDef();
                  boolean kidsRoom = room != null && room.isKidsRoom();
                  canTrash = !kidsRoom && RandomizedBuildingBase.is1x1AreaClear(sq) && sq.hasFloor() && !sq.isOutside();
                  ArrayList<IsoGridSquare> squares = new ArrayList<>();

                  for (int i = 0; i < DIRECTIONS.length; i++) {
                     IsoGridSquare testSq = sq.getAdjacentSquare(DIRECTIONS[i]);
                     if (testSq != null && testSq.isExtraFreeSquare() && testSq.getRoom() != null && testSq.getRoom() == sq.getRoom()) {
                        squares.add(testSq);
                     }
                  }

                  if (graff && this.isValidGraffSquare(sq, true, false) && Rand.Next(500) <= trashFactor) {
                     this.graffSquare(sq, true);
                  }

                  if (graff && this.isValidGraffSquare(sq, false, false) && Rand.Next(500) <= trashFactor) {
                     this.graffSquare(sq, false);
                  }

                  for (int o = 0; o < sq.getObjects().size(); o++) {
                     IsoObject obj = (IsoObject)sq.getObjects().get(o);
                     if (obj instanceof IsoDoor door
                        && !door.getProperties().has(IsoPropertyType.DOUBLE_DOOR)
                        && !door.getProperties().has(IsoPropertyType.GARAGE_DOOR)
                        && !door.isBarricaded()
                        && !door.IsOpen()) {
                        if (z == 0 && door.isLocked()) {
                           door.destroy();
                        } else if (Rand.Next(200) <= trashFactor) {
                           door.destroy();
                        } else if (Rand.Next(10) <= trashFactor) {
                           door.ToggleDoorSilent();
                           if (door.isLocked()) {
                              door.setLocked(false);
                           }
                        } else {
                           door.setLocked(false);
                        }
                     } else if (obj instanceof IsoDoor door && door.getProperties().has(IsoPropertyType.GARAGE_DOOR) && door.isLocked() && !door.IsOpen()) {
                        door.destroy();
                     }

                     if (obj instanceof IsoWindow window && Rand.Next(100) <= trashFactor && !window.IsOpen()) {
                        window.smashWindow(true, false);
                        window.addBrokenGlass(Rand.NextBool(2));
                     } else if (sq.getZ() == 0 && obj instanceof IsoWindow window && window.isLocked() && !window.IsOpen()) {
                        window.setIsLocked(false);
                     }

                     if (obj.getContainer() != null
                        && obj.getContainer().getItems() != null
                        && !obj.getSprite().getProperties().has(IsoPropertyType.IS_TRASH_CAN)) {
                        removedItems.clear();
                        addOnGroundItems.clear();

                        for (int k = 0; k < obj.getContainer().getItems().size(); k++) {
                           InventoryItem item = (InventoryItem)obj.getContainer().getItems().get(k);
                           if (Rand.Next(200) < trashFactor && !Objects.equals(item.getType(), "VHS_Home")) {
                              if (item.getReplaceOnUseFullType() != null && obj.getSquare().getRoom() != null) {
                                 IsoGridSquare square = obj.getSquare().getRandomAdjacentFreeSameRoom();
                                 if (square == null || Rand.NextBool(3)) {
                                    square = obj.getSquare().getRoom().getRoomDef().getExtraFreeSquare();
                                 }

                                 if (square == null || Rand.NextBool(5)) {
                                    square = Objects.requireNonNull(Objects.requireNonNull(obj.getSquare().getBuilding()).getRandomRoom())
                                       .getRoomDef()
                                       .getExtraFreeSquare();
                                 }

                                 if (square != null && !square.isOutside() && square.getRoom() != null && square.hasRoomDef()) {
                                    this.addItemOnGround(square, item.getReplaceOnUseFullType());
                                 }
                              } else if (item instanceof DrainableComboItem drainableComboItem
                                 && drainableComboItem.getReplaceOnDepleteFullType() != null
                                 && obj.getSquare().getRoom() != null) {
                                 IsoGridSquare square = this.getFloorSquare(squares, sq, room, building);
                                 if (square != null && !square.isOutside() && square.getRoom() != null && square.hasRoomDef()) {
                                    this.addItemOnGround(square, drainableComboItem.getReplaceOnDepleteFullType());
                                 }
                              }

                              removedItems.add(item);
                           } else if (Rand.Next(100) < trashFactor && !(item instanceof Moveable)) {
                              IsoGridSquare square = this.getFloorSquare(squares, sq, room, building);
                              if (square != null && !square.isOutside() && square.getRoom() != null && square.hasRoomDef()) {
                                 ItemPickerJava.trashItemLooted(item);
                                 removedItems.add(item);
                                 addOnGroundItems.add(new RBTrashed.AddItemOnGround(square, item));
                              }
                           }
                        }

                        if (!removedItems.isEmpty()) {
                           if (GameServer.server) {
                              GameServer.sendRemoveItemsFromContainer(obj.getContainer(), removedItems);
                           }

                           for (InventoryItem item : removedItems) {
                              obj.getContainer().DoRemoveItem(item);
                           }
                        }

                        if (!addOnGroundItems.isEmpty()) {
                           for (RBTrashed.AddItemOnGround aiog : addOnGroundItems) {
                              this.addItemOnGround(aiog.square, aiog.item, false);
                           }
                        }

                        ItemPickerJava.updateOverlaySprite(obj);
                        obj.getContainer().setExplored(true);
                     }

                     if (obj.getContainerByIndex(1) != null && obj.getContainerByIndex(1).getItems() != null) {
                        removedItems.clear();
                        List<InventoryItem> items = obj.getContainerByIndex(1).getItems();

                        for (int k = 0; k < items.size(); k++) {
                           if (Rand.Next(100) < 80) {
                              removedItems.add(items.get(k));
                           }
                        }

                        if (!removedItems.isEmpty()) {
                           if (GameServer.server) {
                              GameServer.sendRemoveItemsFromContainer(obj.getContainerByIndex(1), removedItems);
                           }

                           for (InventoryItem item : removedItems) {
                              obj.getContainerByIndex(1).DoRemoveItem(item);
                           }
                        }

                        ItemPickerJava.updateOverlaySprite(obj);
                        obj.getContainerByIndex(1).setExplored(true);
                     }

                     if (!removedBar
                        && z == 0
                        && obj.getSprite() != null
                        && obj.getSprite().getName() != null
                        && (
                           Objects.equals(obj.getSprite().getName(), "location_shop_mall_01_18")
                              || Objects.equals(obj.getSprite().getName(), "location_shop_mall_01_19")
                        )) {
                        sq.RemoveTileObject(obj);
                        sq.RecalcProperties();
                        sq.RecalcAllWithNeighbours(true);
                        if (sq.getWindow() != null) {
                           sq.getWindow().smashWindow(true, false);
                        }

                        removedBar = true;
                     }
                  }
               }

               if (sq != null) {
                  if (canTrash) {
                     if (Rand.Next(500) <= trashFactor) {
                        this.trashSquare(sq);
                     }
                  } else if (z == 0 && sq.isOutside() && RandomizedBuildingBase.is1x1AreaClear(sq) && Rand.Next(2000) <= trashFactor) {
                     this.trashSquare(sq);
                  }

                  if (z == 0 && sq.isOutside() && RandomizedBuildingBase.is2x2AreaClear(sq) && Rand.Next(10000) <= trashFactor) {
                     sq.addCorpse();
                  }
               }
            }
         }
      }

      for (int i = 0; i < def.rooms.size(); i++) {
         RoomDef room = (RoomDef)def.rooms.get(i);
         IsoGridSquare square = room.getExtraFreeSquare();
         int chance = Math.min(baseTrashFactor, room.getIsoRoom().getSquares().size());
         chance = Math.max(chance, baseTrashFactor);
         if (room != null && square != null && Rand.Next(1000) <= chance && RandomizedBuildingBase.is2x2AreaClear(square)) {
            square.addCorpse();
         }
      }

      RoomDef room = def.getRandomRoom(4, true);
      IsoGridSquare freeSQ = getRandomSquareForCorpse(room);
      if (room != null && freeSQ != null && def.getRoomsNumber() > 2 && def.getArea() >= 100 && Rand.NextBool(100)) {
         String zombieType = "Bandit";
         if (!graff && Rand.NextBool(3)) {
            zombieType = "PrivateMilitia";
         } else if (!graff && Rand.NextBool(3)) {
            zombieType = switch (Rand.Next(5)) {
               case 1 -> "Survivalist02";
               case 2 -> "Survivalist03";
               case 3 -> "Survivalist04";
               case 4 -> "Survivalist05";
               default -> "Survivalist";
            };
         }

         boolean corpse = Rand.NextBool(2);
         ArrayList<IsoZombie> zombies = this.addZombiesOnSquare(1, zombieType, null, freeSQ);
         if (zombies != null && zombies.get(0) != null) {
            String keyType = "Base.Key1";
            InventoryItem houseKey = InventoryItemFactory.CreateItem("Base.Key1");
            if (houseKey != null) {
               houseKey.setKeyId(def.getKeyId());
               zombies.get(0).addItemToSpawnAtDeath(houseKey);
            }

            if (corpse) {
               freeSQ.createCorpse(zombies.get(0));
            }
         }

         for (int i = 0; i < def.rooms.size(); i++) {
            room = (RoomDef)def.rooms.get(i);
            IsoGridSquare square = room.getExtraFreeSquare();
            if (square != null && Rand.NextBool(100) && RandomizedBuildingBase.is2x2AreaClear(square)) {
               if (Rand.NextBool(10)) {
                  corpse = Rand.NextBool(2);
               }

               zombies = this.addZombiesOnSquare(1, zombieType, null, square);
               if (corpse) {
                  square.createCorpse(zombies.get(0));
               }
            }
         }
      }

      def.setAllExplored(true);
      def.alarmed = false;
   }

   private record AddItemOnGround(IsoGridSquare square, InventoryItem item) {
   }
}
