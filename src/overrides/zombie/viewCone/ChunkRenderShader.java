package zombie.viewCone;

import org.lwjgl.opengl.GL13;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.Shader;
import zombie.core.opengl.ShaderProgram;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

public class ChunkRenderShader extends Shader {
   public ChunkRenderShader(String name) {
      super(name);
      pzopt.Overrides.onClassLoadedQuiet("zombie.viewCone.ChunkRenderShader"); // pzopt: marker so the game log shows the loose class was loaded
   }

   public void startMainThread(TextureDraw texd, int playerIndex) {
   }

   public void startRenderThread(TextureDraw texd) {
      this.getProgram().setValue("DEPTH", texd.tex1, 1);
      GL13.glActiveTexture(33984);
      SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      Texture.lastTextureID = 0;
      SpriteRenderer.ringBuffer.shaderChangedTexture1();
      this.getProgram().setValue("chunkDepth", texd.chunkDepth);
      pzopt.PixelLight.chunkDraw(this, texd); // pzopt: pixelLight, a chunk texture no light reaches switches to the light-free variant; the first draw of a frame on a program sets its light uniforms (viewport as drawn)
   }

   public void onCompileSuccess(ShaderProgram sender) {
      this.Start();
      sender.setSamplerUnit("DIFFUSE", 0);
      sender.setSamplerUnit("DEPTH", 1);
      this.End();
   }
}
