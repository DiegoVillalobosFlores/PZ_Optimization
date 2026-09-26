package pzopt;

import java.nio.FloatBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.ShaderHelper;
import zombie.core.textures.Texture;

/**
 * spriteFilterSharpMips (2026-09-26, candidate A): the sharp minification reads mip level 1 of a chunk texture from 2x
 * zoomed out (the driving zooms 2 - 2.5). glGenerateMipmap builds that level with a 2x2 box, which is soft; this pass
 * rewrites level 1 right after the bake's stock mip generation with a Lanczos-2 2:1 downsample of level 0 (separable,
 * 8 texels per axis folded into 4 bilinear taps: 16 taps per output texel), clamped to the premultiplied range. Level 2
 * and up stay the stock box chain from level 0 (only the empty-area probe reads level 2, for coverage).
 *
 * <p>Render thread, once a frame ahead of the chunk composite (the frame's bakes are done by then), for every texture
 * baked this frame: its own framebuffer on level 1, a sampler without mipmapping on unit 0 (reads level 0 only, so no
 * feedback loop with the attached level); no GL query: the game thread restarts the world frame after the batch (as the
 * game does after every bake), and the rest of the state is restored as ChunkAo restores it.
 */
final class SpriteMips {
   private SpriteMips() {
   }

   // Lanczos-2 at output texel offsets 0.25, 0.75, 1.25, 1.75 (input offsets 0.5 .. 3.5), normalised to 0.5 a side:
   // 0.4343 0.1165 -0.0419 -0.0089. Same-sign pairs as one bilinear tap: 0.5508 at 0.7115, -0.0508 at 2.675.
   static final String FRAG = String.join("\n",
      "#version 330",
      "uniform sampler2D src;",
      "uniform vec2 texel;", // level 0 texel size
      "in vec2 uv;",
      "out vec4 o;",
      "const vec2 OFF = vec2(0.7115, 2.675);",
      "const vec2 W = vec2(0.5508, -0.0508);",
      "void main() {",
      "   vec4 c = vec4(0.0);",
      "   for (int i = 0; i < 4; i++) {",
      "      float ox = (i < 2 ? OFF.x : OFF.y) * (i % 2 == 0 ? -1.0 : 1.0);",
      "      float wx = i < 2 ? W.x : W.y;",
      "      for (int j = 0; j < 4; j++) {",
      "         float oy = (j < 2 ? OFF.x : OFF.y) * (j % 2 == 0 ? -1.0 : 1.0);",
      "         float wy = j < 2 ? W.x : W.y;",
      "         vec4 s = textureLod(src, uv + vec2(ox, oy) * texel, 0.0);",
      "#ifdef LINEAR_LIGHT",
      "         s.rgb *= s.rgb;", // gamma 2 approximation: weight the light, not the encoded value
      "#endif",
      "         c += wx * wy * s;",
      "      }",
      "   }",
      "   float a = clamp(c.a, 0.0, 1.0);",
      "#ifdef LINEAR_LIGHT",
      "   c.rgb = sqrt(max(c.rgb, vec3(0.0)));",
      "#endif",
      "   o = vec4(clamp(c.rgb, vec3(0.0), vec3(a)), a);",
      "}");
   static final String VERT = String.join("\n",
      "#version 330",
      "layout(location = 0) in vec2 pos;",
      "out vec2 uv;",
      "void main() {",
      "   uv = pos * 0.5 + 0.5;",
      "   gl_Position = vec4(pos, 0.0, 1.0);",
      "}");

   private static int program, uTexel, fbo, sampler, quadVbo;
   private static boolean failed;
   static long passes;

   /** Render thread (the sprite filter's frame drawer, ahead of the composite): rewrite level 1 of these textures. */
   static void runBatch(Texture[] list, int n) {
      if (n == 0 || failed) {
         return;
      }
      try {
         if (program == 0 && !init()) {
            return;
         }
         GpuSections.markNow("sharpmip", false);
         begin();
         for (int i = 0; i < n; i++) {
            Texture t = list[i];
            list[i] = null;
            int id = t == null ? 0 : t.getID();
            int w = t == null ? 0 : t.getWidthHW(), h = t == null ? 0 : t.getHeightHW();
            if (id > 0 && w >= 4 && h >= 4) {
               pass(id, w, h);
            }
         }
         end();
         GpuSections.markNow("sharpmip", true);
      } catch (Throwable e) {
         failed = true;
         Log.warn("sprite filter: sharp mipmaps failed (" + e + "), box mipmaps from now on");
      }
   }

   private static boolean init() {
      String frag = Config.SPRITE_FILTER_LINEAR_LIGHT ? FRAG.replace("#version 330", "#version 330\n#define LINEAR_LIGHT") : FRAG; // launch setting
      int vs = compile(GL20.GL_VERTEX_SHADER, VERT), fs = compile(GL20.GL_FRAGMENT_SHADER, frag);
      if (vs == 0 || fs == 0) {
         failed = true;
         return false;
      }
      int p = GL20.glCreateProgram();
      GL20.glAttachShader(p, vs);
      GL20.glAttachShader(p, fs);
      GL20.glBindAttribLocation(p, 0, "pos");
      GL20.glLinkProgram(p);
      GL20.glDeleteShader(vs);
      GL20.glDeleteShader(fs);
      if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
         Log.warn("sprite filter: sharp mipmap program does not link: " + GL20.glGetProgramInfoLog(p));
         failed = true;
         return false;
      }
      program = p;
      uTexel = GL20.glGetUniformLocation(p, "texel");
      GL20.glUseProgram(p);
      GL20.glUniform1i(GL20.glGetUniformLocation(p, "src"), 0);
      fbo = GL30.glGenFramebuffers();
      sampler = GL33.glGenSamplers();
      GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
      GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
      GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
      GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
      quadVbo = GL15.glGenBuffers();
      FloatBuffer q = MemoryUtil.memAllocFloat(8);
      q.put(new float[] {-1, -1, 1, -1, 1, 1, -1, 1}).flip();
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
      GL15.glBufferData(GL15.GL_ARRAY_BUFFER, q, GL15.GL_STATIC_DRAW);
      MemoryUtil.memFree(q);
      Log.info("sprite filter: sharp mipmaps (Lanczos-2 level 1) ready");
      return true;
   }

   private static int compile(int type, String src) {
      int s = GL20.glCreateShader(type);
      GL20.glShaderSource(s, src);
      GL20.glCompileShader(s);
      if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
         Log.warn("sprite filter: sharp mipmap shader does not compile: " + GL20.glGetShaderInfoLog(s));
         GL20.glDeleteShader(s);
         return 0;
      }
      return s;
   }

   private static void begin() {
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glDisable(GL11.GL_STENCIL_TEST);
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDisable(GL11.GL_BLEND);
      GL11.glDepthMask(false);
      GL11.glColorMask(true, true, true, true);
      GL20.glUseProgram(program);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL33.glBindSampler(0, sampler);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
      GL20.glEnableVertexAttribArray(0);
      for (int i = 1; i < 5; i++) {
         GL20.glDisableVertexAttribArray(i);
      }
      GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
   }

   private static void pass(int id, int w, int h) {
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, id, 1);
      GL11.glViewport(0, 0, Math.max(1, w >> 1), Math.max(1, h >> 1));
      GL20.glUniform2f(uTexel, 1.0F / w, 1.0F / h);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
      GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
      passes++;
   }

   private static void end() {
      GL33.glBindSampler(0, 0);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, zombie.core.textures.TextureFBO.lastID);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
      for (int i = 0; i < 5; i++) {
         GL20.glEnableVertexAttribArray(i);
      }
      ShaderHelper.forgetCurrentlyBound();
      ShaderHelper.glUseProgramObjectARB(0);
      GL11.glEnable(GL11.GL_DEPTH_TEST);
      GL11.glDepthMask(true);
      GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
      GLStateRenderThread.restore();
      SpriteRenderer.ringBuffer.restoreVbos = true;
      SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      Texture.lastTextureID = 0;
   }
}
