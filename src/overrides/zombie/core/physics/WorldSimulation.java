package zombie.core.physics;

import java.util.ArrayList;
import java.util.HashMap;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.core.profiling.AbstractPerformanceProfileProbe;
import zombie.core.profiling.PerformanceProfileProbe;
import zombie.core.textures.TextureDraw.GenericDrawer;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoMetaGrid;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.PacketTypes.PacketType;
import zombie.network.packets.INetworkPacket;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleManager;
import zombie.vehicles.BaseVehicle.Authorization;

public final class WorldSimulation {
   static {
      pzopt.Overrides.onClassLoadedQuiet("zombie.core.physics.WorldSimulation"); // pzopt: marker so the game log shows the loose class was loaded
   }

   public static WorldSimulation instance = new WorldSimulation();
   public HashMap<Integer, IsoMovingObject> physicsObjectMap = new HashMap<>();
   public boolean created;
   public float offsetX;
   public float offsetY;
   public long time;
   private final ArrayList<BaseVehicle> collideVehicles = new ArrayList<>(4);
   private final Vector3f tempVector3f = new Vector3f();
   private final Vector3f tempVector3f2 = new Vector3f();
   private final Transform tempTransform = new Transform();
   private final Quaternionf javaxQuat4f = new Quaternionf();
   private final float[] ff = new float[8192];
   private final float[] wheelSteer = new float[4];
   private final float[] wheelRotation = new float[4];
   private final float[] wheelSkidInfo = new float[4];
   private final float[] wheelSuspensionLength = new float[4];
   private float localTime;
   public float periodSec;
   private int bulletFrameNo = -1;

   public void create() {
      if (!this.created) {
         IsoMetaGrid metaGrid = IsoWorld.instance.metaGrid;
         this.offsetX = metaGrid.getMinX() * 256;
         this.offsetY = metaGrid.getMinY() * 256;
         this.time = GameTime.getServerTimeMills();
         IsoChunkMap cm = IsoWorld.instance.currentCell.chunkMap[0];
         Bullet.initWorld(
            metaGrid.getMinX(),
            metaGrid.getMinY(),
            metaGrid.getMaxX(),
            metaGrid.getMaxY(),
            (int)this.offsetX,
            (int)this.offsetY,
            cm.getWorldXMin(),
            cm.getWorldYMin(),
            IsoChunkMap.chunkGridWidth
         );

         for (int i = 0; i < 4; i++) {
            this.wheelSteer[i] = 0.0F;
            this.wheelRotation[i] = 0.0F;
            this.wheelSkidInfo[i] = 0.0F;
            this.wheelSuspensionLength[i] = 0.0F;
         }

         this.created = true;
      }
   }

   public void destroy() {
      Bullet.destroyWorld();
   }

   private void updatePhysic() {
      if (pzopt.VehicleSmooth.stepsChanged()) { // pzopt: physicsStepHz / physicsStepMode, the step the physics runs with
         this.updatePhysicPzopt(); // pzopt: physicsStepHz / physicsStepMode
         return; // pzopt: physicsStepHz / physicsStepMode
      } // pzopt: physicsStepHz / physicsStepMode
      float fixedTimeStep = 0.01F;
      int numSimulationSubSteps = 0;
      this.localTime = this.localTime + GameTime.instance.getPhysicsSecondsSinceLastUpdate();
      if (this.localTime >= 0.01F) {
         numSimulationSubSteps = (int)(this.localTime / 0.01F);
         this.localTime -= numSimulationSubSteps * 0.01F;

         for (int i = 0; i < numSimulationSubSteps; i++) {
            this.updateVehiclePhysics();
            if (i == numSimulationSubSteps - 1) { // pzopt: vehicleSmooth, the state before the frame's last step
               pzopt.VehicleSmooth.beforeLastStep(); // pzopt: vehicleSmooth
            } // pzopt: vehicleSmooth
            Bullet.stepSimulation(0.01F, 0, 0.0F);
         }

         this.periodSec = numSimulationSubSteps * 0.01F;
         this.bulletFrameNo++;
      }

      pzopt.VehicleSmooth.afterSteps(numSimulationSubSteps, this.localTime, 0.01F); // pzopt: vehicleSmooth, the state after it + the remainder
      if (Math.abs(this.time - GameTime.getServerTimeMills()) > 100L) {
         this.time = GameTime.getServerTimeMills();
      } else {
         this.time += (long)(10.0F * numSimulationSubSteps);
      }
   }

   // pzopt: physicsStepHz (fixed steps at another rate) / physicsStepMode=frame (the frame's time in equal steps of at most
   // pzopt: one fixed step, nothing carried over); same per-step work as stock (impulses, surrounding chunks, one Bullet step)
   private void updatePhysicPzopt() { // pzopt: physicsStepHz / physicsStepMode
      float step = pzopt.VehicleSmooth.stepSeconds(); // pzopt
      int n = 0; // pzopt
      this.localTime = this.localTime + GameTime.instance.getPhysicsSecondsSinceLastUpdate(); // pzopt
      if (pzopt.VehicleSmooth.frameSteps()) { // pzopt
         if (this.localTime > 1.0E-5F) { // pzopt
            n = Math.max(1, (int)Math.ceil(this.localTime / step - 1.0E-4F)); // pzopt
            step = this.localTime / n; // pzopt
            this.localTime = 0.0F; // pzopt
         } // pzopt
      } else if (this.localTime >= step) { // pzopt
         n = (int)(this.localTime / step); // pzopt
         this.localTime -= n * step; // pzopt
      } // pzopt
      if (n > 0) { // pzopt
         for (int i = 0; i < n; i++) { // pzopt
            this.updateVehiclePhysics(); // pzopt
            if (i == n - 1) { // pzopt
               pzopt.VehicleSmooth.beforeLastStep(); // pzopt
            } // pzopt
            Bullet.stepSimulation(step, 0, 0.0F); // pzopt
         } // pzopt
         this.periodSec = n * step; // pzopt
         this.bulletFrameNo++; // pzopt
      } // pzopt
      pzopt.VehicleSmooth.afterSteps(n, this.localTime, step); // pzopt
      if (Math.abs(this.time - GameTime.getServerTimeMills()) > 100L) { // pzopt
         this.time = GameTime.getServerTimeMills(); // pzopt
      } else { // pzopt
         this.time += (long)(1000.0F * n * step); // pzopt
      } // pzopt
   } // pzopt

   private void updateVehiclePhysics() {
      for (BaseVehicle vehicle : IsoWorld.instance.currentCell.getVehicles()) {
         vehicle.applyAccumulatedImpulsesFromHitObjectsToPhysics();
         vehicle.applyAllImpulsesFromProneCharacters();
         vehicle.checkSurroundingChunks();
      }
   }

   public void update() {
      AbstractPerformanceProfileProbe var1 = WorldSimulation.s_performance.worldSimulationUpdate.profile();

      try {
         this.updateInternal();
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

   private void updateInternal() {
      if (this.created) {
         this.updatePhysic();
         if (GameClient.client) {
            try {
               VehicleManager.instance.clientUpdate();
            } catch (Exception ex) {
               DebugType.Vehicle.printException(ex, "VehicleManager.clientUpdate was failed", LogSeverity.Error);
            }
         }

         this.collideVehicles.clear();
         int totalVehicles = Bullet.getVehicleCount();
         int offset = 0;

         while (offset < totalVehicles) {
            int numVehicles = Bullet.getVehiclePhysics(offset, this.ff);
            if (numVehicles <= 0) {
               break;
            }

            offset += numVehicles;
            int fn = 0;

            for (int i = 0; i < numVehicles; i++) {
               int id = (int)this.ff[fn++];
               float x = this.ff[fn++];
               float y = this.ff[fn++];
               float z = this.ff[fn++];
               this.tempTransform.origin.set(x, y, z);
               float qx = this.ff[fn++];
               float qy = this.ff[fn++];
               float qz = this.ff[fn++];
               float qw = this.ff[fn++];
               this.javaxQuat4f.set(qx, qy, qz, qw);
               this.tempTransform.setRotation(this.javaxQuat4f);
               float vx = this.ff[fn++];
               float vy = this.ff[fn++];
               float vz = this.ff[fn++];
               this.tempVector3f.set(vx, vy, vz);
               float speed = this.ff[fn++];
               float isCollide = this.ff[fn++];
               isCollide = pzopt.VehicleSmooth.collide(id, isCollide); // pzopt: vehicleSmooth, the collide flag its earlier reads took
               int wheelCount = (int)this.ff[fn++];

               for (int n = 0; n < wheelCount; n++) {
                  this.wheelSteer[n] = this.ff[fn++];
                  this.wheelRotation[n] = this.ff[fn++];
                  this.wheelSkidInfo[n] = this.ff[fn++];
                  this.wheelSuspensionLength[n] = this.ff[fn++];
               }

               int hash = (int)(x * 100.0F + y * 100.0F + z * 100.0F + qx * 100.0F + qy * 100.0F + qz * 100.0F + qw * 100.0F);
               BaseVehicle vehicle = VehicleManager.instance.getVehicleByID((short)id);
               if (vehicle != null
                  && (
                     !GameClient.client
                        || vehicle == null
                        || !(vehicle.timeSinceLastAuth <= 0.0F)
                        || !vehicle.isNetPlayerAuthorization(Authorization.Remote) && !vehicle.isNetPlayerAuthorization(Authorization.RemoteCollide)
                  )) {
                  if (vehicle.vehicleId == id && isCollide > 0.5F) {
                     this.collideVehicles.add(vehicle);
                     vehicle.authSimulationHash = hash;
                  }

                  if (GameClient.client && vehicle.isNetPlayerAuthorization(Authorization.LocalCollide)) {
                     if (vehicle.authSimulationHash != hash) {
                        vehicle.authSimulationTime = System.currentTimeMillis();
                        vehicle.authSimulationHash = hash;
                     }

                     if (vehicle.isAttachingTrailer() || vehicle.getVehicleTowedBy() != null && vehicle.getVehicleTowedBy().isAttachingTrailer()) {
                        vehicle.authSimulationTime = System.currentTimeMillis();
                     }

                     if (System.currentTimeMillis() - vehicle.authSimulationTime > 1000L) {
                        INetworkPacket.send(PacketType.VehicleCollide, new Object[]{vehicle, vehicle.getDriver(), false});
                        vehicle.authSimulationTime = 0L;
                     }
                  }

                  if (!vehicle.isNetPlayerAuthorization(Authorization.Remote) || !vehicle.isNetPlayerAuthorization(Authorization.RemoteCollide)) {
                     if (GameClient.client && vehicle.isNetPlayerAuthorization(Authorization.Server)) {
                        vehicle.setSpeedKmHour(0.0F);
                     } else {
                        vehicle.setSpeedKmHour(speed);
                     }
                  }

                  if (!GameClient.client
                     || !(vehicle.timeSinceLastAuth <= 0.0F)
                     || vehicle.constraintChangedTime != -1L
                     || !vehicle.isNetPlayerAuthorization(Authorization.Server)
                        && !vehicle.isNetPlayerAuthorization(Authorization.Remote)
                        && !vehicle.isNetPlayerAuthorization(Authorization.RemoteCollide)) {
                     if (this.compareTransform(this.tempTransform, vehicle.getPoly().t)) {
                        vehicle.polyDirty = true;
                     }

                     vehicle.jniTransform.set(this.tempTransform);
                     vehicle.jniLinearVelocity.set(this.tempVector3f);
                     vehicle.jniIsCollide = isCollide > 0.5F;

                     for (int m = 0; m < wheelCount; m++) {
                        vehicle.wheelInfo[m].steering = this.wheelSteer[m];
                        vehicle.wheelInfo[m].rotation = this.wheelRotation[m];
                        vehicle.wheelInfo[m].skidInfo = this.wheelSkidInfo[m];
                        vehicle.wheelInfo[m].suspensionLength = this.wheelSuspensionLength[m];
                     }
                  }
               }
            }
         }

         pzopt.VehicleSmooth.collideDone(); // pzopt: vehicleSmooth, drop collide flags of vehicles the read did not report

         if (GameClient.client) {
            IsoPlayer driver = IsoPlayer.players[IsoPlayer.getPlayerIndex()];
            if (driver != null) {
               BaseVehicle vehicleDriver = driver.getVehicle();
               if (vehicleDriver != null && vehicleDriver.isNetPlayerId(driver.getOnlineID()) && this.collideVehicles.contains(vehicleDriver)) {
                  for (BaseVehicle vehicle : this.collideVehicles) {
                     if (vehicle.DistTo(vehicleDriver) < 16.0F && vehicle.isNetPlayerAuthorization(Authorization.Server)) {
                        INetworkPacket.send(PacketType.VehicleCollide, new Object[]{vehicle, driver, true});
                        vehicle.authorizationClientCollide(driver);
                     }
                  }
               }
            }
         }

         int numObjects = Bullet.getObjectPhysics(this.ff);
         int fn = 0;

         for (int i = 0; i < numObjects; i++) {
            int id = (int)this.ff[fn++];
            float x = this.ff[fn++];
            float y = this.ff[fn++];
            float z = this.ff[fn++];
            x += this.offsetX;
            z += this.offsetY;
            IsoMovingObject obj = this.physicsObjectMap.get(id);
            if (obj != null) {
               obj.removeFromSquare();
               obj.setX(x + 0.18F);
               obj.setY(z);
               obj.setZ(Math.max(0.0F, y / 3.0F / 0.8164967F));
               obj.setCurrentSquareFromPosition();
            }
         }
      }
   }

   private boolean compareTransform(Transform t1, Transform t2) {
      if (!(Math.abs(t1.origin.x - t2.origin.x) > 0.01F) && !(Math.abs(t1.origin.z - t2.origin.z) > 0.01F) && (int)t1.origin.y == (int)t2.origin.y) {
         int forwardAxis = 2;
         t1.basis.getColumn(2, this.tempVector3f2);
         float t1x = this.tempVector3f2.x;
         float t1z = this.tempVector3f2.z;
         t2.basis.getColumn(2, this.tempVector3f2);
         float t2x = this.tempVector3f2.x;
         float t2z = this.tempVector3f2.z;
         return Math.abs(t1x - t2x) > 0.001F || Math.abs(t1z - t2z) > 0.001F;
      } else {
         return true;
      }
   }

   public void activateChunkMap(int playerIndex) {
      this.create();
      IsoChunkMap chunkMap = IsoWorld.instance.currentCell.chunkMap[playerIndex];
      if (!GameServer.server) {
         Bullet.activateChunkMap(playerIndex, chunkMap.getWorldXMin(), chunkMap.getWorldYMin(), IsoChunkMap.chunkGridWidth);
      }
   }

   public void deactivateChunkMap(int playerIndex) {
      if (this.created) {
         Bullet.deactivateChunkMap(playerIndex);
      }
   }

   public void scrollGroundLeft(int playerIndex) {
      if (this.created) {
         Bullet.scrollChunkMapLeft(playerIndex);
      }
   }

   public void scrollGroundRight(int playerIndex) {
      if (this.created) {
         Bullet.scrollChunkMapRight(playerIndex);
      }
   }

   public void scrollGroundUp(int playerIndex) {
      if (this.created) {
         Bullet.scrollChunkMapUp(playerIndex);
      }
   }

   public void scrollGroundDown(int playerIndex) {
      if (this.created) {
         Bullet.scrollChunkMapDown(playerIndex);
      }
   }

   public static GenericDrawer getDrawer(int playerIndex) {
      PhysicsDebugRenderer drawer = PhysicsDebugRenderer.alloc();
      drawer.init(IsoPlayer.players[playerIndex]);
      IsoPlayer.players[playerIndex].physicsDebugRenderer = drawer;
      return drawer;
   }

   public int getBulletFrameNo() {
      return this.bulletFrameNo;
   }

   private static class s_performance {
      static final PerformanceProfileProbe worldSimulationUpdate = new PerformanceProfileProbe("WorldSimulation.update");
   }
}
