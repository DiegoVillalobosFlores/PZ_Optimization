package zombie.iso.weather;

import org.lwjgl.opengl.GL20;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.opengl.RenderSettings;
import zombie.core.opengl.Shader;
import zombie.core.opengl.ShaderProgram;
import zombie.core.opengl.RenderSettings.PlayerRenderSettings;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.PlayerCamera;
import zombie.iso.SearchMode;
import zombie.iso.SearchMode.PlayerSearchMode;

public class WeatherShader extends Shader {
   public int timeOfDay;
   private int pixelOffset;
   private int pixelSize;
   private int bloom;
   private int timer;
   private int blurStrength;
   private int textureSize;
   private int zoom;
   private int light;
   private int lightIntensity;
   private int nightValue;
   private int exterior;
   private int nightVisionGoggles;
   private int desaturationVal;
   private int fogMod;
   private int searchModeId;
   private int screenInfo;
   private int paramInfo;
   private int varInfo;
   private int drunkFactor;
   private int blurFactor;
   private int timerVal;
   private int timerWrap;
   private float timerWrapVal = -1.0F;
   private boolean alt;
   public static final int texdVarsSize = 25;
   private static final float[][] floatArrs = new float[5][];

   public WeatherShader(String name) {
      super(name);
      pzopt.Overrides.onClassLoadedQuiet("zombie.iso.weather.WeatherShader"); // pzopt: marker so the game log shows the loose class was loaded
   }

   public void startMainThread(TextureDraw texd, int playerIndex) {
      if (playerIndex >= 0 && playerIndex < 4) {
         PlayerRenderSettings plrSettings = RenderSettings.getInstance().getPlayerSettings(playerIndex);
         int offScreenWidth = Core.getInstance().getOffscreenWidth(playerIndex);
         int offScreenHeight = Core.getInstance().getOffscreenHeight(playerIndex);
         if (texd.vars == null) {
            texd.vars = getFreeFloatArray();
            if (texd.vars == null) {
               texd.vars = new float[25];
            }
         }

         texd.vars[0] = plrSettings.getBlendColor().r;
         texd.vars[1] = plrSettings.getBlendColor().g;
         texd.vars[2] = plrSettings.getBlendColor().b;
         texd.vars[3] = plrSettings.getBlendIntensity();
         texd.vars[4] = plrSettings.getDesaturation();
         texd.vars[5] = plrSettings.isApplyNightVisionGoggles() ? 1.0F : 0.0F;
         PlayerSearchMode searchMode = SearchMode.getInstance().getSearchModeForPlayer(playerIndex);
         texd.vars[6] = searchMode.getShaderBlur();
         texd.vars[7] = searchMode.getShaderRadius();
         texd.vars[8] = IsoCamera.getOffscreenLeft(playerIndex);
         texd.vars[9] = IsoCamera.getOffscreenTop(playerIndex);
         PlayerCamera camera = IsoCamera.cameras[playerIndex];
         texd.vars[10] = IsoCamera.getOffscreenWidth(playerIndex);
         texd.vars[11] = IsoCamera.getOffscreenHeight(playerIndex);
         texd.vars[12] = camera.rightClickX;
         texd.vars[13] = camera.rightClickY;
         texd.vars[14] = Core.getInstance().getZoom(playerIndex);
         texd.vars[15] = Core.tileScale == 2 ? 64.0F : 32.0F;
         texd.vars[16] = searchMode.getShaderGradientWidth() * texd.vars[15] / 2.0F;
         texd.vars[17] = searchMode.getShaderDesat();
         texd.vars[18] = searchMode.isShaderEnabled() ? 1.0F : 0.0F;
         texd.vars[19] = searchMode.getShaderDarkness();
         texd.vars[22] = plrSettings.getDrunkFactor();
         texd.vars[23] = plrSettings.getBlurFactor();
         texd.flipped = plrSettings.isExterior();
         texd.f1 = plrSettings.getDarkness();
         texd.col0 = offScreenWidth;
         texd.col1 = offScreenHeight;
         texd.col2 = Core.getInstance().getOffscreenTrueWidth();
         texd.col3 = Core.getInstance().getOffscreenTrueHeight();
         int[] pzoptSize = pzopt.Upscaler.compositeTextureSize(); // pzopt: upscaler, the composite quad draws the resolved screen-size texture: TextureSize is its bicubic texel size
         if (pzoptSize != null) {
            texd.col2 = pzoptSize[0];
            texd.col3 = pzoptSize[1];
         }
         texd.singleCol = Core.getInstance().getZoom(playerIndex) > 2.0F
            || Core.getInstance().getZoom(playerIndex) < 2.0 && Core.getInstance().getZoom(playerIndex) >= 1.75F;
      }
   }

   public void startThumbnail(TextureDraw texd, int playerIndex, int thumbWidth, int thumbHeight, float zoom) {
      this.startMainThread(texd, playerIndex);
      int offScreenWidth = Core.getInstance().getOffscreenWidth(playerIndex);
      int offScreenHeight = Core.getInstance().getOffscreenHeight(playerIndex);
      texd.vars[12] = (offScreenWidth - thumbWidth * zoom) / 2.0F;
      texd.vars[13] = -(offScreenHeight - thumbHeight * zoom) / 2.0F;
      texd.vars[14] = zoom;
      texd.singleCol = zoom > 2.0F || zoom < 2.0 && zoom >= 1.75F;
   }

   public void startRenderThread(TextureDraw texd) {
      float timeOfDay = texd.f1;
      boolean bExterior = texd.flipped;
      int offScreenWidth = texd.col0;
      int offScreenHeight = texd.col1;
      int textureWidth = texd.col2;
      int textureHeight = texd.col3;
      float zoom = texd.singleCol ? 1.0F : 0.0F;
      GL20.glUniform1f(this.getWidth(), offScreenWidth);
      GL20.glUniform1f(this.getHeight(), offScreenHeight);
      GL20.glUniform1f(this.nightValue, timeOfDay);
      if (texd.vars != null) {
         GL20.glUniform3f(this.light, texd.vars[0], texd.vars[1], texd.vars[2]);
         GL20.glUniform1f(this.lightIntensity, texd.vars[3]);
         GL20.glUniform1f(this.desaturationVal, texd.vars[4]);
         GL20.glUniform1f(this.nightVisionGoggles, texd.vars[5]);
      }

      GL20.glUniform1f(this.exterior, bExterior ? 1.0F : 0.0F);
      GL20.glUniform1f(this.timer, this.timerVal / 2);
      GL20.glUniform1f(this.timerWrap, this.timerWrapVal);
      if (PerformanceSettings.getLockFPS() >= 60) {
         if (this.alt) {
            this.timerVal++;
         }

         this.alt = !this.alt;
      } else {
         this.timerVal += 2;
      }

      this.timerWrapVal = 1.0F - 2.0F * (this.timerVal / 2.1474836E9F);
      GL20.glUniform2f(this.textureSize, textureWidth, textureHeight);
      GL20.glUniform1f(this.zoom, zoom);
      GL20.glUniform4f(this.searchModeId, texd.vars[6], texd.vars[7], texd.vars[8], texd.vars[9]);
      GL20.glUniform4f(this.screenInfo, texd.vars[10], texd.vars[11], texd.vars[12], texd.vars[13]);
      GL20.glUniform4f(this.paramInfo, texd.vars[14], texd.vars[15], texd.vars[16], texd.vars[17]);
      GL20.glUniform4f(this.varInfo, texd.vars[18], texd.vars[19], texd.vars[20], texd.vars[21]);
      GL20.glUniform1f(this.drunkFactor, texd.vars[22]);
      GL20.glUniform1f(this.blurFactor, texd.vars[23]);
      pzopt.Hdr.worldUniforms(); // pzopt: HDR output, the world expansion's uniforms on the bound composite program
      pzopt.Grade.worldUniforms(this.getID()); // pzopt: colour grading, the LUT and its switch on the bound composite program
   }

   public void onCompileSuccess(ShaderProgram sender) {
      int shaderID = this.getID();
      this.timeOfDay = GL20.glGetUniformLocation(shaderID, "TimeOfDay");
      this.bloom = GL20.glGetUniformLocation(shaderID, "BloomVal");
      this.pixelOffset = GL20.glGetUniformLocation(shaderID, "PixelOffset");
      this.pixelSize = GL20.glGetUniformLocation(shaderID, "PixelSize");
      this.blurStrength = GL20.glGetUniformLocation(shaderID, "BlurStrength");
      this.setWidth(GL20.glGetUniformLocation(shaderID, "bgl_RenderedTextureWidth"));
      this.setHeight(GL20.glGetUniformLocation(shaderID, "bgl_RenderedTextureHeight"));
      this.timer = GL20.glGetUniformLocation(shaderID, "timer");
      this.textureSize = GL20.glGetUniformLocation(shaderID, "TextureSize");
      this.zoom = GL20.glGetUniformLocation(shaderID, "Zoom");
      this.light = GL20.glGetUniformLocation(shaderID, "Light");
      this.lightIntensity = GL20.glGetUniformLocation(shaderID, "LightIntensity");
      this.nightValue = GL20.glGetUniformLocation(shaderID, "NightValue");
      this.exterior = GL20.glGetUniformLocation(shaderID, "Exterior");
      this.nightVisionGoggles = GL20.glGetUniformLocation(shaderID, "NightVisionGoggles");
      this.desaturationVal = GL20.glGetUniformLocation(shaderID, "DesaturationVal");
      this.fogMod = GL20.glGetUniformLocation(shaderID, "FogMod");
      this.searchModeId = GL20.glGetUniformLocation(shaderID, "SearchMode");
      this.screenInfo = GL20.glGetUniformLocation(shaderID, "ScreenInfo");
      this.paramInfo = GL20.glGetUniformLocation(shaderID, "ParamInfo");
      this.varInfo = GL20.glGetUniformLocation(shaderID, "VarInfo");
      this.drunkFactor = GL20.glGetUniformLocation(shaderID, "DrunkFactor");
      this.blurFactor = GL20.glGetUniformLocation(shaderID, "BlurFactor");
      this.timerWrap = GL20.glGetUniformLocation(shaderID, "timerWrap");
   }

   public void postRender(TextureDraw texd) {
      if (texd.vars != null) {
         returnFloatArray(texd.vars);
         texd.vars = null;
      }
   }

   private static float[] getFreeFloatArray() {
      for (int i = 0; i < floatArrs.length; i++) {
         if (floatArrs[i] != null) {
            float[] arr = floatArrs[i];
            floatArrs[i] = null;
            return arr;
         }
      }

      return new float[25];
   }

   private static void returnFloatArray(float[] arr) {
      for (int i = 0; i < floatArrs.length; i++) {
         if (floatArrs[i] == null) {
            floatArrs[i] = arr;
            break;
         }
      }
   }
}
