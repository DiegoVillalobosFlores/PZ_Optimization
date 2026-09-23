package zombie.core.textures;

import java.util.ArrayList;
import zombie.GameTime;
import zombie.IndieGL;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.SceneShaderStore;
import zombie.core.SpriteRenderer;
import zombie.core.utils.ImageUtils;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoUtils;
import zombie.iso.PlayerCamera;
import zombie.iso.sprite.IsoCursor;
import zombie.iso.sprite.IsoReticle;
import zombie.network.GameServer;
import zombie.network.ServerGUI;

public final class MultiTextureFBO2 {
   private final float[] zoomLevelsDefault = new float[]{2.5F, 2.25F, 2.0F, 1.75F, 1.5F, 1.25F, 1.0F, 0.75F, 0.5F, 0.25F};
   private float[] zoomLevels;
   public TextureFBO current;
   public volatile TextureFBO fboRendered;
   private final float[] zoom = new float[4];
   private final float[] targetZoom = new float[4];
   private final float[] startZoom = new float[4];
   private float zoomedInLevel;
   private float zoomedOutLevel;
   public final boolean[] autoZoom = new boolean[4];
   public boolean zoomEnabled = true;

   public MultiTextureFBO2() {
      for (int n = 0; n < 4; n++) {
         this.zoom[n] = this.targetZoom[n] = this.startZoom[n] = 1.0F;
      }
   }

   public int getWidth(int playerIndex) {
      return (int)(IsoCamera.getScreenWidth(playerIndex) * this.getDisplayZoom(playerIndex) * (Core.tileScale / 2.0F));
   }

   public int getHeight(int playerIndex) {
      return (int)(IsoCamera.getScreenHeight(playerIndex) * this.getDisplayZoom(playerIndex) * (Core.tileScale / 2.0F));
   }

   public void setZoom(int playerIndex, float value) {
      this.zoom[playerIndex] = value;
   }

   public void setZoomAndTargetZoom(int playerIndex, float value) {
      this.zoom[playerIndex] = value;
      this.targetZoom[playerIndex] = value;
   }

   public float getZoom(int playerIndex) {
      return this.zoom[playerIndex];
   }

   public float getTargetZoom(int playerIndex) {
      return this.targetZoom[playerIndex];
   }

   public float getDisplayZoom(int playerIndex) {
      return Core.width > Core.initialWidth ? this.zoom[playerIndex] * (Core.initialWidth / Core.width) : this.zoom[playerIndex];
   }

   public void setTargetZoom(int playerIndex, float target) {
      if (this.targetZoom[playerIndex] != target) {
         this.targetZoom[playerIndex] = target;
         this.startZoom[playerIndex] = this.zoom[playerIndex];
      }
   }

   public ArrayList<Integer> getDefaultZoomLevels() {
      ArrayList<Integer> percents = new ArrayList<>();
      float[] levels = this.zoomLevelsDefault;

      for (int i = 0; i < levels.length; i++) {
         percents.add(Math.round(levels[i] * 100.0F));
      }

      return percents;
   }

   public void setZoomLevels(Double... zooms) {
      this.zoomLevels = new float[zooms.length];

      for (int i = 0; i < zooms.length; i++) {
         this.zoomLevels[i] = zooms[i].floatValue();
      }
   }

   public void setZoomLevelsFromOption(String levels) {
      this.zoomLevels = this.zoomLevelsDefault;
      if (levels != null && !levels.isEmpty()) {
         String[] ss = levels.split(";");
         if (ss.length != 0) {
            ArrayList<Integer> percents = new ArrayList<>();

            for (String s : ss) {
               if (!s.isEmpty()) {
                  try {
                     int percent = Integer.parseInt(s);

                     for (float knownLevel : this.zoomLevels) {
                        if (Math.round(knownLevel * 100.0F) == percent) {
                           if (!percents.contains(percent)) {
                              percents.add(percent);
                           }
                           break;
                        }
                     }
                  } catch (NumberFormatException var13) {
                  }
               }
            }

            if (!percents.contains(100)) {
               percents.add(100);
            }

            percents.sort((o1, o2) -> o2 - o1);
            this.zoomLevels = new float[percents.size()];

            for (int i = 0; i < percents.size(); i++) {
               int playerIndex = IsoPlayer.getPlayerIndex();
               this.zoomLevels[i] = percents.get(i).intValue() / 100.0F;
            }
         }
      }
   }

   public void destroy() {
      if (this.current != null) {
         this.current.destroy();
         this.current = null;
         this.fboRendered = null;

         for (int n = 0; n < 4; n++) {
            this.zoom[n] = this.targetZoom[n] = 1.0F;
         }
      }
   }

   public void create(int xres, int yres) throws Exception {
      if (this.zoomLevels == null) {
         this.zoomLevels = this.zoomLevelsDefault;
      }

      this.zoomedInLevel = this.zoomLevels[this.zoomLevels.length - 1];
      this.zoomedOutLevel = this.zoomLevels[0];
      int x = ImageUtils.getNextPowerOfTwoHW(xres);
      int y = ImageUtils.getNextPowerOfTwoHW(yres);
      this.current = this.createTexture(x, y, false);
   }

   public void update() {
      int playerIndex = IsoPlayer.getPlayerIndex();
      if (!this.zoomEnabled) {
         this.zoom[playerIndex] = this.targetZoom[playerIndex] = 1.0F;
      }

      IsoGameCharacter isoGameCharacter = IsoCamera.getCameraCharacter();
      if (this.autoZoom[playerIndex] && isoGameCharacter != null && this.zoomEnabled) {
         float dist = IsoUtils.DistanceTo(IsoCamera.getRightClickOffX(), IsoCamera.getRightClickOffY(), 0.0F, 0.0F);
         float delta = dist / 300.0F;
         if (delta > 1.0F) {
            delta = 1.0F;
         }

         float zoom = this.shouldAutoZoomIn() ? this.zoomedInLevel : this.zoomedOutLevel;
         zoom += delta;
         if (zoom > this.zoomLevels[0]) {
            zoom = this.zoomLevels[0];
         }

         if (isoGameCharacter.getVehicle() != null) {
            zoom = this.getMaxZoom();
         }

         this.setTargetZoom(playerIndex, zoom);
      }

      if (!this.autoZoom[playerIndex] && PZOPT_EASE_MS > 0) {
         // pzopt: zoomEase. A manual zoom change (wheel, harness, Lua) moves along a cubic Bézier over PZOPT_EASE_MS of wall
         // time instead of the fixed 0.03 per frame and the snap below; a new target while moving restarts from the current zoom.
         this.pzoptEase(playerIndex);
         this.setCameraToCentre();
         return;
      }

      float step = 0.004F * GameTime.instance.getMultiplier() / GameTime.instance.getTrueMultiplier() * (Core.tileScale == 2 ? 1.5F : 1.5F);
      if (!this.autoZoom[playerIndex]) {
         step *= 5.0F;
      } else if (this.targetZoom[playerIndex] > this.zoom[playerIndex]) {
         step *= 1.0F;
      }

      if (this.targetZoom[playerIndex] > this.zoom[playerIndex]) {
         this.zoom[playerIndex] = this.zoom[playerIndex] + step;
         IsoPlayer.players[playerIndex].dirtyRecalcGridStackTime = 2.0F;
         if (this.zoom[playerIndex] > this.targetZoom[playerIndex] || Math.abs(this.zoom[playerIndex] - this.targetZoom[playerIndex]) < 0.001F) {
            this.zoom[playerIndex] = this.targetZoom[playerIndex];
         }
      }

      if (this.targetZoom[playerIndex] < this.zoom[playerIndex]) {
         this.zoom[playerIndex] = this.zoom[playerIndex] - step;
         IsoPlayer.players[playerIndex].dirtyRecalcGridStackTime = 2.0F;
         if (this.zoom[playerIndex] < this.targetZoom[playerIndex] || Math.abs(this.zoom[playerIndex] - this.targetZoom[playerIndex]) < 0.001F) {
            this.zoom[playerIndex] = this.targetZoom[playerIndex];
         }
      }

      this.setCameraToCentre();
   }

   // pzopt: zoomEase state per player (Config.ZOOM_EASE_MS / ZOOM_EASE, pzopt.ZoomEase)
   private static final int PZOPT_EASE_MS = pzopt.Overrides.enabled() ? pzopt.Config.ZOOM_EASE_MS : 0;
   private static final pzopt.ZoomEase PZOPT_EASE = pzopt.ZoomEase.parse(pzopt.Config.ZOOM_EASE);
   private final float[] pzoptEaseFrom = new float[4];
   private final float[] pzoptEaseTarget = new float[]{Float.NaN, Float.NaN, Float.NaN, Float.NaN};
   private final long[] pzoptEaseStartNs = new long[4];

   private void pzoptEase(int playerIndex) {
      float target = this.targetZoom[playerIndex];
      if (target != this.pzoptEaseTarget[playerIndex]) {
         // a new target (doZoomScroll, setTargetZoom, setZoomAndTargetZoom): the motion starts here, from wherever the zoom is
         this.pzoptEaseTarget[playerIndex] = target;
         this.pzoptEaseFrom[playerIndex] = this.zoom[playerIndex];
         this.pzoptEaseStartNs[playerIndex] = System.nanoTime();
      }
      if (this.zoom[playerIndex] == target) {
         return;
      }
      float elapsedMs = (System.nanoTime() - this.pzoptEaseStartNs[playerIndex]) / 1e6F;
      this.zoom[playerIndex] = PZOPT_EASE.zoomAt(this.pzoptEaseFrom[playerIndex], target, elapsedMs, PZOPT_EASE_MS);
      if (IsoPlayer.players[playerIndex] != null) {
         IsoPlayer.players[playerIndex].dirtyRecalcGridStackTime = 2.0F;
      }
   }

   private boolean shouldAutoZoomIn() {
      IsoGameCharacter isoGameCharacter = IsoCamera.getCameraCharacter();
      if (isoGameCharacter == null) {
         return false;
      }

      IsoGridSquare square = isoGameCharacter.getCurrentSquare();
      if (square != null && !square.isOutside()) {
         return true;
      }

      if (isoGameCharacter instanceof IsoPlayer player) {
         if (player.isRunning() || player.isSprinting()) {
            return false;
         } else {
            return player.closestZombie < 6.0F && player.isTargetedByZombie() ? true : player.lastTargeted < PerformanceSettings.getLockFPS() * 4;
         }
      } else {
         return false;
      }
   }

   private void setCameraToCentre() {
      PlayerCamera camera = IsoCamera.cameras[IsoPlayer.getPlayerIndex()];
      camera.center();
   }

   private TextureFBO createTexture(int x, int y, boolean test) {
      if (test) {
         Texture tex = new Texture(x, y, 16);
         TextureFBO newOne = new TextureFBO(tex);
         newOne.destroy();
         return null;
      } else {
         Texture tex = new Texture(x, y, 19);
         TextureFBO fbo = new TextureFBO(tex);
         pzopt.FogPass.sceneDepthAsTexture(fbo, tex); // pzopt: the scene depth as a sampleable D24S8 texture (fog pass reads it in place)
         return fbo;
      }
   }

   private final int[] pzoptSrc = new int[4]; // pzopt: upscaler, the composite's source rectangle in the resolved texture

   public void render() {
      if (this.current != null) {
         int max = 0;

         for (int playerIndex = 3; playerIndex >= 0; playerIndex--) {
            if (IsoPlayer.players[playerIndex] != null) {
               max = playerIndex > 1 ? 3 : playerIndex;
               break;
            }
         }

         max = Math.max(max, IsoPlayer.numPlayers - 1);
         pzopt.Upscaler.queueResolve(); // pzopt: upscaler, the low-res world image is resolved to the screen size on the render thread before the quads below
         pzopt.GpuSections.begin("screen"); // pzopt: GPU section (the full-size screen-shader composite)

         for (int playerIndex = 0; playerIndex <= max; playerIndex++) {
            if (SceneShaderStore.weatherShader != null && DebugOptions.instance.fboRenderChunk.useWeatherShader.getValue()) {
               IndieGL.StartShader(SceneShaderStore.weatherShader, playerIndex);
            }

            int sx = IsoCamera.getScreenLeft(playerIndex);
            int sy = IsoCamera.getScreenTop(playerIndex);
            int sw = IsoCamera.getScreenWidth(playerIndex);
            int sh = IsoCamera.getScreenHeight(playerIndex);
            if (IsoPlayer.players[playerIndex] != null || GameServer.server && ServerGUI.isCreated()) {
               if (pzopt.RenderScale.active()) {
                  // pzopt: upscaler. fsr1 / dlss: the resolved screen-size texture; bicubic: the stock screen shader's
                  // bicubic filter samples the low-res region of the offscreen buffer straight into the screen rect
                  if (pzopt.Upscaler.drawsOutput() && pzopt.Upscaler.output().hasTexture()) {
                     pzopt.Upscaler.outputSourceRect(sx, sy, sw, sh, this.pzoptSrc); // pzopt: upscaler, a smaller DLSS output (dlssOutputPct) is sampled over its own size
                     pzopt.Upscaler.output().rendershader2(sx, sy, sw, sh, this.pzoptSrc[0], this.pzoptSrc[1], this.pzoptSrc[2], this.pzoptSrc[3], 1.0F, 1.0F, 1.0F, 1.0F); // pzopt: upscaler
                  } else {
                     int[] r = pzopt.RenderScale.scaledRect(playerIndex);
                     ((Texture)this.current.getTexture()).rendershader2(sx, sy, sw, sh, r[0], r[1], r[2], r[3], 1.0F, 1.0F, 1.0F, 1.0F);
                  }
               } else {
                  ((Texture)this.current.getTexture()).rendershader2(sx, sy, sw, sh, sx, sy, sw, sh, 1.0F, 1.0F, 1.0F, 1.0F);
               }
            } else {
               SpriteRenderer.instance.renderi(null, sx, sy, sw, sh, 0.0F, 0.0F, 0.0F, 1.0F, null);
            }
         }

         if (SceneShaderStore.weatherShader != null) {
            IndieGL.EndShader();
         }

         pzopt.GpuSections.end("screen"); // pzopt: GPU section
         pzopt.Upscaler.queueCompositeFlush(); // pzopt: upscaler, dlssFlushAfterComposite
         IsoPlayer.forEachPlayer(MultiTextureFBO2::renderCursor);
      }
   }

   private static void renderCursor(IsoPlayer player) {
      IsoReticle isoReticle = IsoReticle.getInstance(player.getIndex());
      if (isoReticle != null) {
         switch (isoReticle.getTargetReticleMode()) {
            case RETICLE:
               isoReticle.render();
               break;
            case CURSOR:
               IsoCursor.getInstance().render(player.getIndex());
         }
      }
   }

   public TextureFBO getCurrent(int nPlayer) {
      return this.current;
   }

   public Texture getTexture(int nPlayer) {
      return (Texture)this.current.getTexture();
   }

   public void doZoomScroll(int playerIndex, int del) {
      this.targetZoom[playerIndex] = this.getNextZoom(playerIndex, del);
   }

   public float getNextZoom(int playerIndex, int del) {
      if (this.zoomEnabled && this.zoomLevels != null) {
         if (del > 0) {
            for (int i = this.zoomLevels.length - 1; i > 0; i--) {
               if (this.targetZoom[playerIndex] == this.zoomLevels[i]) {
                  return this.zoomLevels[i - 1];
               }
            }
         } else if (del < 0) {
            for (int i = 0; i < this.zoomLevels.length - 1; i++) {
               if (this.targetZoom[playerIndex] == this.zoomLevels[i]) {
                  return this.zoomLevels[i + 1];
               }
            }
         }

         return this.targetZoom[playerIndex];
      } else {
         return 1.0F;
      }
   }

   public float getMinZoom() {
      return this.zoomEnabled && this.zoomLevels != null && this.zoomLevels.length != 0 ? this.zoomLevels[this.zoomLevels.length - 1] : 1.0F;
   }

   public float getMaxZoom() {
      return this.zoomEnabled && this.zoomLevels != null && this.zoomLevels.length != 0 ? this.zoomLevels[0] : 1.0F;
   }

   // pzopt: the widest selectable zoom below `limit` (zoomRetain: the largest zoom at which the high-res chunk
   // textures are used is the widest level under 0.75); 0 when no level is below it
   public float pzoptWidestZoomBelow(float limit) {
      float best = 0.0F;
      if (this.zoomEnabled && this.zoomLevels != null) {
         for (float level : this.zoomLevels) {
            if (level < limit && level > best) {
               best = level;
            }
         }
      }
      return best;
   }

   public boolean test() {
      try {
         this.createTexture(16, 16, true);
         return true;
      } catch (Exception ex) {
         DebugType.General.printException(ex, LogSeverity.Error, "Failed to create Test FBO", new Object[0]);
         Core.safeMode = true;
         return false;
      }
   }
}
