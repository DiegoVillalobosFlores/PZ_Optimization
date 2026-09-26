package zombie.iso;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import zombie.GameWindow;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.math.PZMath;
import zombie.debug.DebugOptions;
import zombie.input.GameKeyboard;
import zombie.input.JoypadManager;
import zombie.input.Mouse;
import zombie.iso.sprite.IsoSprite;
import zombie.network.GameServer;
import zombie.ui.SpeedControls;
import zombie.ui.UIManager;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.BaseVehicle.Vector3fObjectPool;

public final class PlayerCamera {
   static {
      pzopt.Overrides.onClassLoadedQuiet("zombie.iso.PlayerCamera"); // pzopt: marker so the game log shows the loose class was loaded
   }

   public final int playerIndex;
   public float offX;
   public float offY;
   private float tOffX;
   private float tOffY;
   public float lastOffX;
   public float lastOffY;
   public float rightClickTargetX;
   public float rightClickTargetY;
   public float rightClickX;
   public float rightClickY;
   private float rightClickXf;
   private float rightClickYf;
   public float deferedX;
   public float deferedY;
   public float zoom;
   public int offscreenWidth;
   public int offscreenHeight;
   public final Matrix4f projection = new Matrix4f();
   public final Matrix4f modelview = new Matrix4f();
   private static final Vector2 offVec = new Vector2();
   private static float panSpeed = 1.0F;
   private long panTime = -1L;
   private final Vector3f lastVehicleForwardDirection = new Vector3f();
   public int width;
   public int height;
   public float fixJigglyModelsX;
   public float fixJigglyModelsY;
   public float fixJigglyModelsSquareX;
   public float fixJigglyModelsSquareY;
   private static final int[] s_viewport = new int[]{0, 0, 0, 0};
   private static final Vector3f s_tempVector3f_1 = new Vector3f();

   public PlayerCamera(int playerIndex) {
      this.playerIndex = playerIndex;
   }

   public void center() {
      float offX = this.offX;
      float offY = this.offY;
      IsoGameCharacter isoGameCharacter = IsoCamera.getCameraCharacter();
      if (isoGameCharacter != null) {
         float chrZ = IsoCamera.frameState.calculateCameraZ(isoGameCharacter);
         offX = IsoUtils.XToScreen(isoGameCharacter.getX() + this.deferedX, isoGameCharacter.getY() + this.deferedY, chrZ, 0);
         offY = IsoUtils.YToScreen(isoGameCharacter.getX() + this.deferedX, isoGameCharacter.getY() + this.deferedY, chrZ, 0);
         offX -= IsoCamera.getOffscreenWidth(this.playerIndex) / 2.0F;
         offY -= IsoCamera.getOffscreenHeight(this.playerIndex) / 2.0F;
         offY -= isoGameCharacter.getOffsetY() * 1.5F;
         offX += IsoCamera.playerOffsetX;
         offY += IsoCamera.playerOffsetY;
      }

      this.offX = this.tOffX = offX;
      this.offY = this.tOffY = offY;
   }

   public void update() {
      this.center();
      float ddx = (this.tOffX - this.offX) / 15.0F;
      float ddy = (this.tOffY - this.offY) / 15.0F;
      this.offX += ddx;
      this.offY += ddy;
      if (this.lastOffX == 0.0F && this.lastOffY == 0.0F) {
         this.lastOffX = this.offX;
         this.lastOffY = this.offY;
      }

      long now = System.currentTimeMillis();
      panSpeed = 110.0F;
      float mult = (float)this.panTime < 0.0F ? 1.0F : (float)(now - this.panTime) / 1000.0F * panSpeed;
      mult = pzopt.DriveCamera.panMult(this.playerIndex, mult, panSpeed); // pzopt: driveLookSmooth, the frame time in ns, not whole ms
      mult = 1.0F / mult;
      this.panTime = now;
      IsoPlayer player = IsoPlayer.players[this.playerIndex];
      boolean isJoypad = GameWindow.activatedJoyPad != null && player != null && player.getJoypadBind() != -1;
      BaseVehicle vehicle = player == null ? null : player.getVehicle();
      if (vehicle != null && vehicle.getCurrentSpeedKmHour() <= 1.0F) {
         vehicle.getForwardVector(this.lastVehicleForwardDirection);
      }

      if (Core.getInstance().getOptionPanCameraWhileDriving() && vehicle != null && vehicle.getCurrentSpeedKmHour() > 1.0F) {
         float zoom = Core.getInstance().getZoom(this.playerIndex);
         float s = vehicle.getCurrentSpeedKmHour() * BaseVehicle.getFakeSpeedModifier() / 10.0F;
         s *= zoom;
         Vector3f forward = vehicle.getForwardVector((Vector3f)((Vector3fObjectPool)BaseVehicle.TL_vector3f_pool.get()).alloc());
         float angleDiff = this.lastVehicleForwardDirection.angle(forward) * (180.0F / (float)Math.PI);
         if (angleDiff > 1.0F) {
            float f = angleDiff / 180.0F / PerformanceSettings.getLockFPS();
            f = PZMath.max(f, 0.1F);
            this.lastVehicleForwardDirection.lerp(forward, f, forward);
            this.lastVehicleForwardDirection.set(forward);
         }

         this.rightClickTargetX = pzopt.DriveCamera.px(IsoUtils.XToScreen(forward.x * s, forward.z * s, player.getZ(), 0)); // pzopt: driveLookSmooth, whole pixels only in stock
         this.rightClickTargetY = pzopt.DriveCamera.px(IsoUtils.YToScreen(forward.x * s, forward.z * s, player.getZ(), 0)); // pzopt: driveLookSmooth
         ((Vector3fObjectPool)BaseVehicle.TL_vector3f_pool.get()).release(forward);
         int screenX = 0;
         int screenY = 0;
         int screenW = IsoCamera.getOffscreenWidth(this.playerIndex);
         int screenH = IsoCamera.getOffscreenHeight(this.playerIndex);
         float centerX = 0.0F + screenW / 2.0F;
         float centerY = 0.0F + screenH / 2.0F;
         float border = 150.0F * zoom;
         this.rightClickTargetX = pzopt.DriveCamera.px(PZMath.clamp(centerX + this.rightClickTargetX, border, screenW - border)) - centerX; // pzopt: driveLookSmooth
         this.rightClickTargetY = pzopt.DriveCamera.px(PZMath.clamp(centerY + this.rightClickTargetY, border, screenH - border)) - centerY; // pzopt: driveLookSmooth
         if (Math.abs(s) < 5.0F) {
            float f = 1.0F - Math.abs(s) / 5.0F;
            this.returnToCenter(1.0F / (16.0F * mult / f));
         } else {
            mult /= 0.5F * zoom;
            float playerX = IsoUtils.XToScreenExact(player.getX(), player.getY(), player.getZ(), 0);
            float playerY = IsoUtils.YToScreenExact(player.getX(), player.getY(), player.getZ(), 0);
            if (playerX < border / 2.0F || playerX > screenW - border / 2.0F || playerY < border / 2.0F || playerY > screenH - border / 2.0F) {
               mult /= 4.0F;
            }

            this.rightClickXf = PZMath.step(this.rightClickXf, this.rightClickTargetX, 1.875F * PZMath.sign(this.rightClickTargetX - this.rightClickXf) / mult);
            this.rightClickYf = PZMath.step(this.rightClickYf, this.rightClickTargetY, 1.875F * PZMath.sign(this.rightClickTargetY - this.rightClickYf) / mult);
            this.rightClickX = pzopt.DriveCamera.px(this.rightClickXf); // pzopt: driveLookSmooth
            this.rightClickY = pzopt.DriveCamera.px(this.rightClickYf); // pzopt: driveLookSmooth
         }
      } else if (isJoypad && player != null) {
         if ((player.isAiming() || player.isLookingWhileInVehicle())
            && JoypadManager.instance.isRBPressed(player.getJoypadBind())
            && !player.isJoypadIgnoreAimUntilCentered()) {
            this.rightClickTargetX = JoypadManager.instance.getAimingAxisX(player.getJoypadBind()) * 1500.0F;
            this.rightClickTargetY = JoypadManager.instance.getAimingAxisY(player.getJoypadBind()) * 1500.0F;
            mult /= 0.5F * Core.getInstance().getZoom(this.playerIndex);
            this.rightClickXf = PZMath.step(this.rightClickXf, this.rightClickTargetX, (this.rightClickTargetX - this.rightClickXf) / (80.0F * mult));
            this.rightClickYf = PZMath.step(this.rightClickYf, this.rightClickTargetY, (this.rightClickTargetY - this.rightClickYf) / (80.0F * mult));
            this.rightClickX = (int)this.rightClickXf;
            this.rightClickY = (int)this.rightClickYf;
            player.dirtyRecalcGridStackTime = 2.0F;
         } else {
            this.returnToCenter(1.0F / (16.0F * mult));
         }
      } else if (this.playerIndex == 0 && player != null && !player.isBlockMovement() && GameKeyboard.isKeyDown("PanCamera")) {
         int screenWidth = IsoCamera.getScreenWidth(this.playerIndex);
         int screenHeight = IsoCamera.getScreenHeight(this.playerIndex);
         int x1 = IsoCamera.getScreenLeft(this.playerIndex);
         int y1 = IsoCamera.getScreenTop(this.playerIndex);
         float difX = Mouse.getXA() - (x1 + screenWidth / 2.0F);
         float difY = Mouse.getYA() - (y1 + screenHeight / 2.0F);
         float screenRatio;
         if (screenWidth > screenHeight) {
            screenRatio = (float)screenHeight / screenWidth;
            difX *= screenRatio;
         } else {
            screenRatio = (float)screenWidth / screenHeight;
            difY *= screenRatio;
         }

         screenRatio *= screenWidth / 1366.0F;
         offVec.set(difX, difY);
         offVec.setLength(Math.min(offVec.getLength(), Math.min(screenWidth, screenHeight) / 2.0F));
         difX = offVec.x / screenRatio;
         difY = offVec.y / screenRatio;
         this.rightClickTargetX = difX * 2.0F;
         this.rightClickTargetY = difY * 2.0F;
         mult /= 0.5F * Core.getInstance().getZoom(this.playerIndex);
         this.rightClickXf = PZMath.step(this.rightClickXf, this.rightClickTargetX, (this.rightClickTargetX - this.rightClickXf) / (80.0F * mult));
         this.rightClickYf = PZMath.step(this.rightClickYf, this.rightClickTargetY, (this.rightClickTargetY - this.rightClickYf) / (80.0F * mult));
         this.rightClickX = (int)this.rightClickXf;
         this.rightClickY = (int)this.rightClickYf;
         player.dirtyRecalcGridStackTime = 2.0F;
         IsoSprite.globalOffsetX = -1.0F;
      } else if (this.playerIndex == 0 && Core.getInstance().getOptionPanCameraWhileAiming() && SpeedControls.instance.getCurrentGameSpeed() > 0) {
         boolean isclient = !GameServer.server;
         boolean isaiming = !UIManager.isMouseOverInventory() && player != null && player.isAiming();
         boolean alive = !isJoypad && player != null && !player.isDead();
         if (isclient && isaiming && alive) {
            int screenWidth = IsoCamera.getScreenWidth(this.playerIndex);
            int screenHeight = IsoCamera.getScreenHeight(this.playerIndex);
            int x1 = IsoCamera.getScreenLeft(this.playerIndex);
            int y1 = IsoCamera.getScreenTop(this.playerIndex);
            float difX = Mouse.getXA() - (x1 + screenWidth / 2.0F);
            float difY = Mouse.getYA() - (y1 + screenHeight / 2.0F);
            float screenRatio;
            if (screenWidth > screenHeight) {
               screenRatio = (float)screenHeight / screenWidth;
               difX *= screenRatio;
            } else {
               screenRatio = (float)screenWidth / screenHeight;
               difY *= screenRatio;
            }

            screenRatio *= screenWidth / 1366.0F;
            float screenDim = Math.min(screenWidth, screenHeight) / 6.0F;
            float minPan = Math.min(screenWidth, screenHeight) / 2.0F - screenDim;
            offVec.set(difX, difY);
            if (offVec.getLength() < minPan) {
               difY = 0.0F;
               difX = 0.0F;
            } else {
               offVec.setLength(Math.min(offVec.getLength(), Math.min(screenWidth, screenHeight) / 2.0F) - minPan);
               difX = offVec.x / screenRatio;
               difY = offVec.y / screenRatio;
            }

            this.rightClickTargetX = difX * 7.0F;
            this.rightClickTargetY = difY * 7.0F;
            mult /= 0.5F * Core.getInstance().getZoom(this.playerIndex);
            this.rightClickXf = PZMath.step(this.rightClickXf, this.rightClickTargetX, (this.rightClickTargetX - this.rightClickXf) / (80.0F * mult));
            this.rightClickYf = PZMath.step(this.rightClickYf, this.rightClickTargetY, (this.rightClickTargetY - this.rightClickYf) / (80.0F * mult));
            this.rightClickX = (int)this.rightClickXf;
            this.rightClickY = (int)this.rightClickYf;
            player.dirtyRecalcGridStackTime = 2.0F;
         } else {
            this.returnToCenter(1.0F / (16.0F * mult));
         }

         IsoSprite.globalOffsetX = -1.0F;
      } else {
         this.returnToCenter(1.0F / (16.0F * mult));
      }

      this.zoom = Core.getInstance().getZoom(this.playerIndex);
   }

   private void returnToCenter(float mult) {
      this.rightClickTargetX = 0.0F;
      this.rightClickTargetY = 0.0F;
      if (this.rightClickTargetX != this.rightClickX || this.rightClickTargetY != this.rightClickY) {
         this.rightClickXf = PZMath.step(this.rightClickXf, this.rightClickTargetX, (this.rightClickTargetX - this.rightClickXf) * mult);
         this.rightClickYf = PZMath.step(this.rightClickYf, this.rightClickTargetY, (this.rightClickTargetY - this.rightClickYf) * mult);
         this.rightClickX = (int)this.rightClickXf;
         this.rightClickY = (int)this.rightClickYf;
         if (Math.abs(this.rightClickTargetX - this.rightClickXf) < 0.001F) {
            this.rightClickX = (int)this.rightClickTargetX;
            this.rightClickXf = this.rightClickX;
         }

         if (Math.abs(this.rightClickTargetY - this.rightClickYf) < 0.001F) {
            this.rightClickY = (int)this.rightClickTargetY;
            this.rightClickYf = this.rightClickY;
         }

         IsoPlayer player = IsoPlayer.players[this.playerIndex];
         player.dirtyRecalcGridStackTime = 2.0F;
      }
   }

   public float getOffX() {
      return pzopt.DriveCamera.snap(this.offX + this.rightClickX, this.playerIndex); // pzopt: cameraScreenPixels, whole screen pixels instead of whole offscreen pixels
   }

   public float getOffY() {
      return pzopt.DriveCamera.snap(this.offY + this.rightClickY, this.playerIndex); // pzopt: cameraScreenPixels
   }

   public float getTOffX() {
      float x = this.tOffX - this.offX;
      return pzopt.DriveCamera.snap(this.offX + this.rightClickX - x, this.playerIndex); // pzopt: cameraScreenPixels
   }

   public float getTOffY() {
      float y = this.tOffY - this.offY;
      return pzopt.DriveCamera.snap(this.offY + this.rightClickY - y, this.playerIndex); // pzopt: cameraScreenPixels
   }

   public float getLastOffX() {
      return pzopt.DriveCamera.snap(this.lastOffX + this.rightClickX, this.playerIndex); // pzopt: cameraScreenPixels
   }

   public float getLastOffY() {
      return pzopt.DriveCamera.snap(this.lastOffY + this.rightClickY, this.playerIndex); // pzopt: cameraScreenPixels
   }

   public float XToIso(float screenX, float screenY, float floor) {
      screenX = pzopt.DriveCamera.isoScreen(screenX); // pzopt: driveLookSmooth / cameraScreenPixels, fractional camera offsets reach the model camera whole
      screenY = pzopt.DriveCamera.isoScreen(screenY); // pzopt: driveLookSmooth / cameraScreenPixels
      float px = screenX + this.getOffX();
      float py = screenY + this.getOffY();
      float tx = (px + 2.0F * py) / (64.0F * Core.tileScale);
      return tx + 3.0F * floor;
   }

   public float YToIso(float screenX, float screenY, float floor) {
      screenX = pzopt.DriveCamera.isoScreen(screenX); // pzopt: driveLookSmooth / cameraScreenPixels
      screenY = pzopt.DriveCamera.isoScreen(screenY); // pzopt: driveLookSmooth / cameraScreenPixels
      float px = screenX + this.getOffX();
      float py = screenY + this.getOffY();
      float ty = (px - 2.0F * py) / (-64.0F * Core.tileScale);
      return ty + 3.0F * floor;
   }

   public float YToScreenExact(float objectX, float objectY, float objectZ, int screenZ) {
      float wy = IsoUtils.YToScreen(objectX, objectY, objectZ, screenZ);
      return wy - this.getOffY();
   }

   public float XToScreenExact(float objectX, float objectY, float objectZ, int screenZ) {
      float wx = IsoUtils.XToScreen(objectX, objectY, objectZ, screenZ);
      return wx - this.getOffX();
   }

   public void copyFrom(PlayerCamera other) {
      this.offX = other.offX;
      this.offY = other.offY;
      this.tOffX = other.tOffX;
      this.tOffY = other.tOffY;
      this.lastOffX = other.lastOffX;
      this.lastOffY = other.lastOffY;
      this.rightClickTargetX = other.rightClickTargetX;
      this.rightClickTargetY = other.rightClickTargetY;
      this.rightClickX = other.rightClickX;
      this.rightClickY = other.rightClickY;
      this.deferedX = other.deferedX;
      this.deferedY = other.deferedY;
      this.zoom = other.zoom;
      this.offscreenWidth = other.offscreenWidth;
      this.offscreenHeight = other.offscreenHeight;
      this.width = other.width;
      this.height = other.height;
      this.fixJigglyModelsX = other.fixJigglyModelsX;
      this.fixJigglyModelsY = other.fixJigglyModelsY;
      this.fixJigglyModelsSquareX = other.fixJigglyModelsSquareX;
      this.fixJigglyModelsSquareY = other.fixJigglyModelsSquareY;
      this.projection.set(other.projection);
      this.modelview.set(other.modelview);
   }

   public void initFromIsoCamera(int playerIndex) {
      this.copyFrom(IsoCamera.cameras[playerIndex]);
      this.zoom = Core.getInstance().getZoom(playerIndex);
      this.offscreenWidth = IsoCamera.getOffscreenWidth(playerIndex);
      this.offscreenHeight = IsoCamera.getOffscreenHeight(playerIndex);
      this.width = IsoCamera.getScreenWidth(playerIndex);
      this.height = IsoCamera.getScreenHeight(playerIndex);
   }

   public void calculateModelViewProjection(float ox, float oy, float oz) {
      PlayerCamera cam = this;
      this.offscreenWidth = IsoCamera.getOffscreenWidth(this.playerIndex);
      this.offscreenHeight = IsoCamera.getOffscreenHeight(this.playerIndex);
      this.zoom = Core.getInstance().getZoom(this.playerIndex);
      float rcx = cam.rightClickX;
      float rcy = cam.rightClickY;
      float tox = cam.getTOffX();
      float toy = cam.getTOffY();
      float defx = cam.deferedX;
      float defy = cam.deferedY;
      IsoGameCharacter isoGameCharacter = IsoCamera.getCameraCharacter();
      float x = isoGameCharacter.getX();
      float y = isoGameCharacter.getY();
      float z = IsoCamera.frameState.calculateCameraZ(isoGameCharacter);
      x -= cam.XToIso(-tox - rcx, -toy - rcy, 0.0F);
      y -= cam.YToIso(-tox - rcx, -toy - rcy, 0.0F);
      x += defx;
      y += defy;
      double screenWidth = cam.offscreenWidth / 1920.0F;
      double screenHeight = cam.offscreenHeight / 1920.0F;
      this.projection
         .setOrtho(-((float)screenWidth) / 2.0F, (float)screenWidth / 2.0F, -((float)screenHeight) / 2.0F, (float)screenHeight / 2.0F, -10.0F, 10.0F);
      this.modelview.scaling(Core.scale);
      this.modelview.scale(Core.tileScale / 2.0F);
      this.modelview.rotate((float) (Math.PI / 6), 1.0F, 0.0F, 0.0F);
      this.modelview.rotate((float) (Math.PI * 3.0 / 4.0), 0.0F, 1.0F, 0.0F);
      double difX = ox - x;
      double difY = oy - y;
      this.modelview.translate(-((float)difX), (oz - z) * 2.44949F, -((float)difY));
      this.modelview.rotate((float) Math.PI, 0.0F, 1.0F, 0.0F);
      this.modelview.translate(0.0F, -0.71999997F, 0.0F);
   }

   public void calculateFixForJigglyModels(float ox, float oy, float oz) {
      if (!DebugOptions.instance.fboRenderChunk.fixJigglyModels.getValue()) {
         this.fixJigglyModelsX = 0.0F;
         this.fixJigglyModelsY = 0.0F;
         this.fixJigglyModelsSquareX = 0.0F;
         this.fixJigglyModelsSquareY = 0.0F;
      } else {
         int wx = PZMath.fastfloor(ox / 8.0F);
         int wy = PZMath.fastfloor(oy / 8.0F);
         float chrZ = IsoCamera.frameState.calculateCameraZ(IsoCamera.getCameraCharacter());
         float x1 = IsoUtils.XToScreen(wx * 8, wy * 8, chrZ, 0);
         float y1 = IsoUtils.YToScreen(wx * 8, wy * 8, chrZ, 0);
         x1 -= this.getOffX();
         y1 -= this.getOffY();
         x1 /= this.zoom;
         y1 /= this.zoom;
         Vector3f v1 = this.worldToUI(wx * 8, wy * 8, chrZ, s_tempVector3f_1);
         float x2 = v1.x;
         float y2 = IsoCamera.getScreenHeight(this.playerIndex) - v1.y;
         this.fixJigglyModelsX = x2 - x1;
         this.fixJigglyModelsY = y2 - y1;
         float jx = this.fixJigglyModelsX * this.zoom;
         float jy = this.fixJigglyModelsY * this.zoom;
         this.fixJigglyModelsSquareX = (jx + 2.0F * jy) / (64.0F * Core.tileScale);
         this.fixJigglyModelsSquareY = (jx - 2.0F * jy) / (-64.0F * Core.tileScale);
      }
   }

   private Vector3f worldToUI(float worldX, float worldY, float worldZ, Vector3f result) {
      PlayerCamera camera = this;
      Matrix4f matrix4f = BaseVehicle.allocMatrix4f();
      matrix4f.set(camera.projection);
      matrix4f.mul(camera.modelview);
      s_viewport[2] = IsoCamera.getScreenWidth(this.playerIndex);
      s_viewport[3] = IsoCamera.getScreenHeight(this.playerIndex);
      IsoGameCharacter isoGameCharacter = IsoCamera.getCameraCharacter();
      matrix4f.project(
         worldX - isoGameCharacter.getX(), (worldZ - isoGameCharacter.getZ()) * 3.0F * 0.8164967F, worldY - isoGameCharacter.getY(), s_viewport, result
      );
      BaseVehicle.releaseMatrix4f(matrix4f);
      return result;
   }
}
