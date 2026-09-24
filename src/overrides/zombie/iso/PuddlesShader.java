package zombie.iso;

import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.Shader;
import zombie.core.opengl.ShaderProgram;
import zombie.iso.sprite.SkyBox;

public final class PuddlesShader extends Shader {
   private int waterGroundTex;
   private int puddlesHm;
   private int waterTextureReflectionA;
   private int waterTextureReflectionB;
   private int waterTime;
   private int waterOffset;
   private int waterViewport;
   private int waterReflectionParam;
   private int puddlesParams;

   public PuddlesShader(String name) {
      super(name);
      pzopt.Overrides.onClassLoadedQuiet("zombie.iso.PuddlesShader"); // pzopt: marker so the game log shows the loose class was loaded
   }

   protected void onCompileSuccess(ShaderProgram shaderProgram) {
      int shaderID = shaderProgram.getShaderID();
      this.waterGroundTex = GL20.glGetUniformLocation(shaderID, "WaterGroundTex");
      this.waterTextureReflectionA = GL20.glGetUniformLocation(shaderID, "WaterTextureReflectionA");
      this.waterTextureReflectionB = GL20.glGetUniformLocation(shaderID, "WaterTextureReflectionB");
      this.puddlesHm = GL20.glGetUniformLocation(shaderID, "PuddlesHM");
      this.waterTime = GL20.glGetUniformLocation(shaderID, "WTime");
      this.waterOffset = GL20.glGetUniformLocation(shaderID, "WOffset");
      this.waterViewport = GL20.glGetUniformLocation(shaderID, "WViewport");
      this.waterReflectionParam = GL20.glGetUniformLocation(shaderID, "WReflectionParam");
      this.puddlesParams = GL20.glGetUniformLocation(shaderID, "PuddlesParams");
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

      if (this.puddlesHm != -1) {
         GL20.glUniform1i(this.puddlesHm, 3);
      }

      this.End();
   }

   public void updatePuddlesParams(int userId, int z) {
      IsoPuddles w = IsoPuddles.getInstance();
      SkyBox sb = SkyBox.getInstance();
      PlayerCamera camera = SpriteRenderer.instance.getRenderingPlayerCamera(userId);
      GL13.glActiveTexture(33985);
      sb.getTextureCurrent().bind();
      GL11.glTexParameteri(3553, 10240, 9729);
      GL11.glTexParameteri(3553, 10241, 9729);
      GL11.glTexEnvi(8960, 8704, 7681);
      GL13.glActiveTexture(33986);
      sb.getTexturePrev().bind();
      GL11.glTexParameteri(3553, 10240, 9729);
      GL11.glTexParameteri(3553, 10241, 9729);
      GL11.glTexEnvi(8960, 8704, 7681);
      GL13.glActiveTexture(33987);
      w.getHMTexture().bind();
      GL11.glTexParameteri(3553, 10240, 9729);
      GL11.glTexParameteri(3553, 10241, 9729);
      GL11.glTexEnvi(8960, 8704, 7681);
      IsoPuddles.getInstance().updateHMTextureBuffer();
      GL20.glUniform1f(this.waterTime, w.getShaderTime());
      Vector4f offset = w.getShaderOffset();
      GL20.glUniform4f(this.waterOffset, offset.x - 90000.0F, offset.y - 640000.0F, offset.z, offset.w);
      GL20.glUniform4f(
         this.waterViewport,
         IsoCamera.getOffscreenLeft(userId),
         IsoCamera.getOffscreenTop(userId),
         pzopt.RenderScale.viewPx(camera.offscreenWidth / camera.zoom), // pzopt: upscaler, the viewport the fragments are in
         pzopt.RenderScale.viewPx(camera.offscreenHeight / camera.zoom)
      );
      GL20.glUniform1f(this.waterReflectionParam, sb.getTextureShift());
      GL20.glUniformMatrix4fv(this.puddlesParams, true, w.getPuddlesParams(z));
      pzopt.HdrGlint.surfaceUniforms(); // pzopt: HDR output, sun / sky / lamp glints on the puddles (bound program)
   }
}
