package zombie.iso;

import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.Shader;
import zombie.core.opengl.ShaderProgram;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.iso.sprite.SkyBox;

public final class WaterShader extends Shader {
   private int waterGroundTex;
   private int waterTextureReflectionA;
   private int waterTextureReflectionB;
   private int waterTime;
   private int waterOffset;
   private int waterViewport;
   private int waterReflectionParam;
   private int waterParamWind;
   private int waterParamWindSpeed;
   private int waterParamRainIntensity;

   public WaterShader(String name) {
      super(name);
      pzopt.Overrides.onClassLoadedQuiet("zombie.iso.WaterShader"); // pzopt: marker so the game log shows the loose class was loaded
   }

   protected void onCompileSuccess(ShaderProgram shaderProgram) {
      int shaderID = shaderProgram.getShaderID();
      this.waterGroundTex = GL20.glGetUniformLocation(shaderID, "WaterGroundTex");
      this.waterTextureReflectionA = GL20.glGetUniformLocation(shaderID, "WaterTextureReflectionA");
      this.waterTextureReflectionB = GL20.glGetUniformLocation(shaderID, "WaterTextureReflectionB");
      this.waterTime = GL20.glGetUniformLocation(shaderID, "WTime");
      this.waterOffset = GL20.glGetUniformLocation(shaderID, "WOffset");
      this.waterViewport = GL20.glGetUniformLocation(shaderID, "WViewport");
      this.waterReflectionParam = GL20.glGetUniformLocation(shaderID, "WReflectionParam");
      this.waterParamWind = GL20.glGetUniformLocation(shaderID, "WParamWind");
      this.waterParamWindSpeed = GL20.glGetUniformLocation(shaderID, "WParamWindSpeed");
      this.waterParamRainIntensity = GL20.glGetUniformLocation(shaderID, "WParamRainIntensity");
      this.Start();
      if (this.waterGroundTex != -1) {
         GL20.glUniform1i(this.waterGroundTex, 0);
      }

      if (this.waterTextureReflectionA != -1) {
         GL20.glUniform1i(this.waterTextureReflectionA, 1);
      }

      if (this.waterTextureReflectionB != -1) {
         GL20.glUniform1i(this.waterTextureReflectionB, 2);
      }

      this.End();
   }

   public void startMainThread(TextureDraw texd, int playerIndex) {
      IsoWater w = IsoWater.getInstance();
      SkyBox sb = SkyBox.getInstance();
      texd.u0 = w.getWaterWindX();
      texd.u1 = w.getWaterWindY();
      texd.u2 = w.getWaterWindSpeed();
      texd.u3 = IsoPuddles.getInstance().getRainIntensity();
      texd.v0 = w.getShaderTime();
      texd.v1 = sb.getTextureShift();
   }

   public void updateWaterParams(TextureDraw texd, int playerIndex) {
      IsoWater w = IsoWater.getInstance();
      SkyBox sb = SkyBox.getInstance();
      PlayerCamera camera = SpriteRenderer.instance.getRenderingPlayerCamera(playerIndex);
      GL13.glActiveTexture(33984);
      GL11.glEnable(3553);
      w.getTextureBottom().bind();
      GL11.glTexEnvi(8960, 8704, 7681);
      GL13.glActiveTexture(33985);
      GL11.glEnable(3553);
      sb.getTextureCurrent().bind();
      Texture.lastTextureID = -1;
      GL11.glTexParameteri(3553, 10240, 9729);
      GL11.glTexParameteri(3553, 10241, 9729);
      GL11.glTexEnvi(8960, 8704, 7681);
      GL13.glActiveTexture(33986);
      GL11.glEnable(3553);
      sb.getTexturePrev().bind();
      Texture.lastTextureID = -1;
      GL11.glTexParameteri(3553, 10240, 9729);
      GL11.glTexParameteri(3553, 10241, 9729);
      GL11.glTexEnvi(8960, 8704, 7681);
      GL20.glUniform1f(this.waterTime, texd.v0);
      Vector4f offset = w.getShaderOffset();
      GL20.glUniform4f(this.waterOffset, offset.x - 90000.0F, offset.y - 640000.0F, offset.z, offset.w);
      GL20.glUniform4f(
         this.waterViewport,
         IsoCamera.getOffscreenLeft(playerIndex),
         IsoCamera.getOffscreenTop(playerIndex),
         pzopt.RenderScale.viewPx(camera.offscreenWidth / camera.zoom), // pzopt: upscaler, the viewport the fragments are in
         pzopt.RenderScale.viewPx(camera.offscreenHeight / camera.zoom)
      );
      GL20.glUniform1f(this.waterReflectionParam, texd.v1);
      GL20.glUniform2f(this.waterParamWind, texd.u0, texd.u1);
      GL20.glUniform1f(this.waterParamWindSpeed, texd.u2);
      GL20.glUniform1f(this.waterParamRainIntensity, texd.u3);
      pzopt.HdrGlint.surfaceUniforms(); // pzopt: HDR output, sun / sky / lamp glints on the water (bound program)
      pzopt.Ssr.surfaceUniforms(); // pzopt: reflections, the world colour + depth and the camera mapping (bound program)
   }
}
