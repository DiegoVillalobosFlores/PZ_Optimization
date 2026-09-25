package pzopt;

import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureFBO;

/**
 * Resolves the low-resolution world image (see {@link RenderScale}) to the screen size before the stock screen
 * shader draws it (docs/plan-upscalers.md). Queued from {@code MultiTextureFBO2.render()} on the game thread as a
 * {@link TextureDraw.GenericDrawer}, run on the render thread just before the composite quad of the same frame.
 *
 * <ul>
 * <li>{@code bicubic}: nothing to do here; the composite quad samples the low-res region and the stock screen
 * shader's bicubic filter is the upscaler.</li>
 * <li>{@code fsr1}: AMD FidelityFX Super Resolution 1.0 (MIT): EASU (edge-adaptive spatial upsampling, 12 taps)
 * into a screen-sized texture, then RCAS (robust contrast-adaptive sharpening) into the output texture.</li>
 * <li>{@code dlss} / {@code xess}: the frame is handed to {@link Dlss} (a Vulkan device sharing the images).</li>
 * </ul>
 * The output is exposed through {@link #output()}, a {@link Texture} the stock quad can draw.
 */
public final class Upscaler {
   private Upscaler() {
   }

   private static final UpscaleTexture OUTPUT = new UpscaleTexture();
   private static final Resolver[] RESOLVERS = {new Resolver(), new Resolver(), new Resolver(), new Resolver()}; // one per frame in flight, each carrying its frame's object motion
   private static int nextResolver;

   // render-thread GL objects
   private static int texA; // EASU output (fsr1)
   private static int texR, fboR, texRW, texRH; // RCAS alone at a smaller size (dlssOutputFilter=rcas)
   private static int texB; // RCAS output = the composite input (fsr1)
   private static int fboA;
   private static int fboB;
   private static int texW;
   private static int texH;
   private static int quadVbo;
   private static int easuProgram;
   private static int rcasProgram;
   private static int[] easuUniforms;
   private static int[] rcasUniforms;
   private static boolean failed;
   private static long resolves;
   private static final FloatBuffer QUAD = BufferUtils.createFloatBuffer(8);
   private static final int[] SAVED_VIEWPORT = new int[4];
   private static final int[] CHECK_VIEWPORT = new int[4];
   private static int stateMismatches;

   /**
    * The framebuffer bound and the viewport at the resolve, for the passes to put back (upscaleNoGlGet). Each glGet is a
    * round trip to NVIDIA's driver thread that waits until it has worked off the whole world pass (a quarter of the render
    * thread's time in on-time frames, run td-prof3), and the resolve asked five times a frame. The resolve runs in the
    * screen composite (MultiTextureFBO2.render), where the default framebuffer is bound through TextureFBO (lastID) and the
    * viewport is the screen: devDlssStateLog showed exactly that on every frame. devGlStateCheck still asks the driver and
    * logs every disagreement.
    */
   static int savedState(int[] viewport) {
      if (!Config.UPSCALE_NO_GLGET) {
         GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
         return GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
      }
      int fbo = zombie.core.textures.TextureFBO.lastID;
      viewport[0] = 0;
      viewport[1] = 0;
      viewport[2] = zombie.core.Core.getInstance().getScreenWidth();
      viewport[3] = zombie.core.Core.getInstance().getScreenHeight();
      if (Config.DEV_GL_STATE_CHECK) {
         GL11.glGetIntegerv(GL11.GL_VIEWPORT, CHECK_VIEWPORT);
         int real = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
         if (real != fbo || !java.util.Arrays.equals(CHECK_VIEWPORT, viewport)) {
            if (stateMismatches++ < 10) {
               Log.warn("upscaleNoGlGet: recorded fbo " + fbo + " viewport " + java.util.Arrays.toString(viewport) + ", driver fbo " + real + " viewport "
                  + java.util.Arrays.toString(CHECK_VIEWPORT));
            }
            System.arraycopy(CHECK_VIEWPORT, 0, viewport, 0, 4);
            return real;
         }
      }
      return fbo;
   }

   /** The framebuffer bound now, as savedState records it (detachDirectColor). */
   static int boundFramebuffer() {
      if (!Config.UPSCALE_NO_GLGET || Config.DEV_GL_STATE_CHECK) {
         return GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
      }
      return zombie.core.textures.TextureFBO.lastID;
   }

   /** The mode of this session ("off" when the pass is inactive). */
   public static String mode() {
      return RenderScale.mode();
   }

   /** The composite draws the upscaled texture (fsr1 / dlss / xess) rather than the offscreen buffer's region. */
   public static boolean drawsOutput() {
      String m = mode();
      return "fsr1".equals(m) || "dlss".equals(m) || "xess".equals(m);
   }

   /** The texture the composite quad draws when {@link #drawsOutput()}; empty until the first resolve. */
   public static UpscaleTexture output() {
      return OUTPUT;
   }

   /**
    * The source rectangle the composite quad samples from {@link #output()} for a player's screen rectangle: the
    * rectangle itself when the output is screen-sized, scaled to the output when it is smaller (dlssOutputPct with
    * the bicubic filter: the stock screen shader's bicubic does the last step).
    */
   public static void outputSourceRect(int sx, int sy, int sw, int sh, int[] out) {
      int ow = OUTPUT.getWidthHW(), oh = OUTPUT.getHeightHW();
      if (ow == Core.width && oh == Core.height || ow <= 0 || oh <= 0) {
         out[0] = sx; out[1] = sy; out[2] = sw; out[3] = sh;
         return;
      }
      float fx = (float)ow / Core.width, fy = (float)oh / Core.height;
      out[0] = Math.round(sx * fx); out[1] = Math.round(sy * fy); out[2] = Math.round(sw * fx); out[3] = Math.round(sh * fy);
   }

   /**
    * The texture the aiming cursor reads the world under it from (IsoCursor), and the factor from screen pixels to
    * that texture's pixels: the resolved output when there is one (fsr1 / dlss: the image on screen, and with
    * dlssDirectColor the only one), else the offscreen texture whose scaled rectangle holds the world.
    */
   public static Texture cursorBackground(Texture offscreen) {
      return RenderScale.active() && drawsOutput() && OUTPUT.hasTexture() ? OUTPUT : offscreen;
   }

   public static float cursorBackgroundScale(Texture background) {
      if (background == OUTPUT) {
         return (float)OUTPUT.getWidthHW() / Core.width;
      }
      return RenderScale.scale();
   }

   /** The size the screen shader's TextureSize must report for the composite texture, or null for the stock one. */
   public static int[] compositeTextureSize() {
      if (!RenderScale.active() || !drawsOutput() || !OUTPUT.hasTexture()) {
         return null;
      }
      return new int[]{OUTPUT.getWidthHW(), OUTPUT.getHeightHW()};
   }

   /** Game thread, from MultiTextureFBO2.render(): queue the render-thread resolve of this frame. */
   public static void queueResolve() {
      if (RenderScale.active() && drawsOutput()) {
         Resolver r = RESOLVERS[nextResolver++ & 3];
         r.frame = ObjectMotion.endFrame();
         SpriteRenderer.instance.drawGeneric(r);
      }
   }

   /** Game thread, from MultiTextureFBO2.render() after the composite quads: dlssFlushAfterComposite submits them at once. */
   public static void queueCompositeFlush() {
      if (Config.DLSS_FLUSH_AFTER_COMPOSITE && RenderScale.active() && "dlss".equals(mode())) {
         SpriteRenderer.instance.drawGeneric(FLUSH);
      }
   }

   private static final TextureDraw.GenericDrawer FLUSH = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         GL11.glFlush();
      }
   };

   public static long resolves() {
      return resolves;
   }

   private static final class Resolver extends TextureDraw.GenericDrawer {
      ObjectMotion.Frame frame;

      @Override
      public void render() {
         try {
            resolve(this.frame);
         } catch (Throwable t) {
            RenderScale.disable("resolve failed: " + t);
            Log.error("upscaler resolve: " + t);
         }
      }
   }

   // --- render thread ---------------------------------------------------------------------------------------------

   static void resolve(ObjectMotion.Frame objects) {
      if (!RenderScale.active() || failed) {
         return;
      }
      String m = mode();
      if ("xess".equals(m)) {
         RenderScale.fallback("fsr1", "xess: Intel XeSS is Windows-only and its backend is not built yet");
         m = mode();
      }
      if ("dlss".equals(m)) {
         Dlss.resolve(objects);
         if (!"dlss".equals(mode())) {
            m = mode(); // fell back this frame: resolve it as fsr1 below
         } else {
            if (Dlss.outputBelowScreen() && "fsr1".equals(Config.DLSS_OUTPUT_FILTER)) {
               fsr(Dlss.outputTexture(), Dlss.outputRect()); // dlssOutputPct: DLSS wrote a smaller image, EASU + RCAS take it the rest of the way
            } else if (Dlss.outputBelowScreen() && "rcas".equals(Config.DLSS_OUTPUT_FILTER)) {
               int[] r = Dlss.outputRect();
               rcasAtSize(Dlss.outputTexture(), r[2], r[3]); // RCAS at the DLSS output size, the composite's bicubic does the rest
            } // else the composite quad's bicubic samples the smaller output directly (outputSourceRect)
            return;
         }
      }
      Dlss.detachDirectColor(); // a DLSS that fell back this session must not leave the world drawing into its image
      if (!"fsr1".equals(m)) {
         return;
      }
      TextureFBO world = Core.getInstance().getOffscreenBuffer();
      if (world == null || world.getTexture() == null) {
         return;
      }
      fsr(((Texture)world.getTexture()).getID(), null);
   }

   /** EASU + RCAS from a source texture's rectangle (null = each player's low-res world rectangle) to the screen-size output. */
   private static void fsr(int sourceTex, int[] sourceRect) {
      int screenW = Core.width;
      int screenH = Core.height;
      if (!ensureTargets(screenW, screenH) || !ensurePrograms()) {
         return;
      }
      GpuSections.markNow(sourceRect == null ? "upscale" : "upscale.fsr", false);
      // save what the sprite renderer cares about
      GL11.glGetIntegerv(GL11.GL_VIEWPORT, SAVED_VIEWPORT);
      int previousFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
      GL11.glDisable(GL11.GL_BLEND);
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glDisable(GL11.GL_STENCIL_TEST);
      GL11.glDisable(GL11.GL_CULL_FACE);
      GL11.glDepthMask(false);
      GL11.glColorMask(true, true, true, true);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
      for (int i = 1; i < 5; i++) {
         GL20.glDisableVertexAttribArray(i);
      }
      GL20.glEnableVertexAttribArray(0);
      GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);

      float sharpnessStops = 2.0F * (1.0F - Config.FSR_SHARPNESS_PCT / 100.0F); // 100 % = 0 stops = sharpest
      int players = Math.max(1, IsoPlayer.numPlayers);
      for (int p = 0; p < players; p++) {
         if (IsoPlayer.players[p] == null && players > 1) {
            continue;
         }
         int[] in = sourceRect != null ? sourceRect : RenderScale.scaledRect(p);
         int ox = RenderScale.fullLeft(p), oy = RenderScale.fullTop(p), ow = RenderScale.fullWidth(p), oh = RenderScale.fullHeight(p);

         // 1. EASU: world (low-res region) -> texA (full rect)
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboA);
         GL11.glViewport(ox, oy, ow, oh);
         GL20.glUseProgram(easuProgram);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, sourceTex);
         GL20.glUniform1i(easuUniforms[0], 0);
         GL20.glUniform4f(easuUniforms[1], (float)in[2] / ow, (float)in[3] / oh, 0.5F * in[2] / ow - 0.5F, 0.5F * in[3] / oh - 0.5F); // con0
         GL20.glUniform4i(easuUniforms[2], in[0], in[1], in[2], in[3]); // input rect (texels)
         GL20.glUniform2i(easuUniforms[3], ox, oy); // output origin (pixels)
         GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

         // 2. RCAS: texA -> texB
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboB);
         GL11.glViewport(ox, oy, ow, oh);
         GL20.glUseProgram(rcasProgram);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, texA);
         GL20.glUniform1i(rcasUniforms[0], 0);
         GL20.glUniform1f(rcasUniforms[1], (float)Math.pow(2.0, -sharpnessStops)); // con0.x
         GL20.glUniform4i(rcasUniforms[2], ox, oy, ow, oh); // rect (texels of texA = pixels)
         GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
      }
      OUTPUT.set(texB, texW, texH);
      resolves++;

      // 3. leave the state the way the sprite ring buffer expects it (as FogPass does)
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
      GL11.glViewport(SAVED_VIEWPORT[0], SAVED_VIEWPORT[1], SAVED_VIEWPORT[2], SAVED_VIEWPORT[3]);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      Texture.lastTextureID = -1;
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
      for (int i = 0; i < 5; i++) {
         GL20.glEnableVertexAttribArray(i);
      }
      GL20.glUseProgram(0);
      GL11.glDepthMask(true);
      GL11.glEnable(GL11.GL_BLEND);
      GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
      GLStateRenderThread.restore();
      SpriteRenderer.ringBuffer.restoreVbos = true;
      SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      GpuSections.markNow(sourceRect == null ? "upscale" : "upscale.fsr", true);
   }

   /**
    * dlssOutputFilter=rcas: FSR 1.0's contrast-adaptive sharpen alone, run at the DLSS output's own size (below the
    * screen with dlssOutputPct) into a texture of that size, which the composite's bicubic then stretches: the
    * sharpness of the EASU + RCAS finish without EASU's full-screen pass.
    */
   private static void rcasAtSize(int sourceTex, int w, int h) {
      if (!ensureTargets(Core.width, Core.height) || !ensurePrograms()) {
         return;
      }
      if (texR == 0 || texRW != w || texRH != h) {
         if (texR != 0) {
            GL30.glDeleteFramebuffers(fboR);
            GL11.glDeleteTextures(texR);
         }
         texR = colorTexture(w, h);
         fboR = framebufferOf(texR);
         texRW = w;
         texRH = h;
         if (fboR == 0) {
            fail("rcas target incomplete");
            return;
         }
      }
      GpuSections.markNow("upscale.rcas", false);
      int previousFbo = savedState(SAVED_VIEWPORT);
      GL11.glDisable(GL11.GL_BLEND);
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glDisable(GL11.GL_STENCIL_TEST);
      GL11.glDisable(GL11.GL_CULL_FACE);
      GL11.glDepthMask(false);
      GL11.glColorMask(true, true, true, true);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
      for (int i = 1; i < 5; i++) {
         GL20.glDisableVertexAttribArray(i);
      }
      GL20.glEnableVertexAttribArray(0);
      GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboR);
      GL11.glViewport(0, 0, w, h);
      GL20.glUseProgram(rcasProgram);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, sourceTex);
      GL20.glUniform1i(rcasUniforms[0], 0);
      GL20.glUniform1f(rcasUniforms[1], (float)Math.pow(2.0, -2.0F * (1.0F - Config.FSR_SHARPNESS_PCT / 100.0F)));
      GL20.glUniform4i(rcasUniforms[2], 0, 0, w, h);
      GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
      OUTPUT.set(texR, w, h);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
      GL11.glViewport(SAVED_VIEWPORT[0], SAVED_VIEWPORT[1], SAVED_VIEWPORT[2], SAVED_VIEWPORT[3]);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      Texture.lastTextureID = -1;
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
      for (int i = 0; i < 5; i++) {
         GL20.glEnableVertexAttribArray(i);
      }
      GL20.glUseProgram(0);
      GL11.glDepthMask(true);
      GL11.glEnable(GL11.GL_BLEND);
      GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
      GLStateRenderThread.restore();
      SpriteRenderer.ringBuffer.restoreVbos = true;
      SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      GpuSections.markNow("upscale.rcas", true);
   }

   private static boolean ensureTargets(int w, int h) {
      if (texA != 0 && texW == w && texH == h) {
         return true;
      }
      if (texA != 0) {
         GL30.glDeleteFramebuffers(fboA);
         GL30.glDeleteFramebuffers(fboB);
         GL11.glDeleteTextures(texA);
         GL11.glDeleteTextures(texB);
         texA = texB = fboA = fboB = 0;
      }
      texA = colorTexture(w, h);
      texB = colorTexture(w, h);
      fboA = framebufferOf(texA);
      fboB = framebufferOf(texB);
      texW = w;
      texH = h;
      if (fboA == 0 || fboB == 0) {
         fail("upscale targets incomplete");
         return false;
      }
      if (quadVbo == 0) {
         quadVbo = GL15.glGenBuffers();
         QUAD.clear();
         QUAD.put(-1.0F).put(-1.0F).put(1.0F).put(-1.0F).put(-1.0F).put(1.0F).put(1.0F).put(1.0F);
         QUAD.flip();
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
         GL15.glBufferData(GL15.GL_ARRAY_BUFFER, QUAD, GL15.GL_STATIC_DRAW);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
      }
      Log.info("upscaler: " + RenderScale.describe() + " targets " + w + "x" + h);
      return true;
   }

   static int colorTexture(int w, int h) {
      int tex = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer)null);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      return tex;
   }

   static int framebufferOf(int tex) {
      int previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
      int fbo = GL30.glGenFramebuffers();
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
      int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
      if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
         GL30.glDeleteFramebuffers(fbo);
         return 0;
      }
      return fbo;
   }

   private static boolean ensurePrograms() {
      if (easuProgram != 0 && rcasProgram != 0) {
         return true;
      }
      easuProgram = Shaders.program("fsr1 easu", QUAD_VERT, EASU_FRAG);
      rcasProgram = Shaders.program("fsr1 rcas", QUAD_VERT, RCAS_FRAG);
      if (easuProgram == 0 || rcasProgram == 0) {
         fail("fsr1 shaders refused");
         return false;
      }
      easuUniforms = new int[]{GL20.glGetUniformLocation(easuProgram, "Source"), GL20.glGetUniformLocation(easuProgram, "con0"),
         GL20.glGetUniformLocation(easuProgram, "inRect"), GL20.glGetUniformLocation(easuProgram, "outOrigin")};
      rcasUniforms = new int[]{GL20.glGetUniformLocation(rcasProgram, "Source"), GL20.glGetUniformLocation(rcasProgram, "sharpness"),
         GL20.glGetUniformLocation(rcasProgram, "rect")};
      return true;
   }

   private static void fail(String why) {
      failed = true;
      RenderScale.disable(why);
   }

   // --- shaders ---------------------------------------------------------------------------------------------------

   static final String QUAD_VERT = String.join("\n",
      "#version 330",
      "layout(location = 0) in vec2 aPos;",
      "void main() { gl_Position = vec4(aPos, 0.0, 1.0); }");

   /**
    * FidelityFX FSR 1.0 EASU (ffx_fsr1.h, MIT, AMD), the 12-tap version with texel fetches. The output pixel maps
    * to an input position through con0; the taps f g j k are the 2x2 block around it, b c e h i l n o the ring.
    * Direction and length of the local gradient select an elliptical Lanczos-like kernel, clamped to the block's
    * min / max. Input positions are clamped to the low-res rectangle so nothing outside it bleeds in.
    */
   static final String EASU_FRAG = String.join("\n",
      "#version 330",
      "uniform sampler2D Source;",
      "uniform vec4 con0;",
      "uniform ivec4 inRect;",
      "uniform ivec2 outOrigin;",
      "out vec4 fragColor;",
      "vec3 tap(ivec2 p) {",
      "   p = clamp(p, ivec2(0), inRect.zw - 1);",
      "   return texelFetch(Source, p + inRect.xy, 0).rgb;",
      "}",
      "void easuSet(inout vec2 dir, inout float len, vec2 pp, bool biS, bool biT, bool biU, bool biV, float lA, float lB, float lC, float lD, float lE) {",
      "   float w = 0.0;",
      "   if (biS) w = (1.0 - pp.x) * (1.0 - pp.y);",
      "   if (biT) w = pp.x * (1.0 - pp.y);",
      "   if (biU) w = (1.0 - pp.x) * pp.y;",
      "   if (biV) w = pp.x * pp.y;",
      "   float dc = lD - lC;",
      "   float cb = lC - lB;",
      "   float lenX = max(abs(dc), abs(cb));",
      "   lenX = 1.0 / max(lenX, 1.0e-5);",
      "   float dirX = lD - lB;",
      "   dir.x += dirX * w;",
      "   lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);",
      "   lenX *= lenX;",
      "   len += lenX * w;",
      "   float ec = lE - lC;",
      "   float ca = lC - lA;",
      "   float lenY = max(abs(ec), abs(ca));",
      "   lenY = 1.0 / max(lenY, 1.0e-5);",
      "   float dirY = lE - lA;",
      "   dir.y += dirY * w;",
      "   lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);",
      "   lenY *= lenY;",
      "   len += lenY * w;",
      "}",
      "void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {",
      "   vec2 v = vec2(off.x * dir.x + off.y * dir.y, off.x * (-dir.y) + off.y * dir.x);",
      "   v *= len;",
      "   float d2 = min(v.x * v.x + v.y * v.y, clp);",
      "   float wB = 0.4 * d2 - 1.0;",
      "   float wA = lob * d2 - 1.0;",
      "   wB *= wB;",
      "   wA *= wA;",
      "   wB = 1.5625 * wB - 0.5625;",
      "   float w = wB * wA;",
      "   aC += c * w;",
      "   aW += w;",
      "}",
      "float luma(vec3 c) { return c.b * 0.5 + (c.r * 0.5 + c.g); }",
      "void main() {",
      "   ivec2 ip = ivec2(gl_FragCoord.xy) - outOrigin;",
      "   vec2 pp = vec2(ip) * con0.xy + con0.zw;",
      "   vec2 fp = floor(pp);",
      "   pp -= fp;",
      "   ivec2 f0 = ivec2(fp);",
      "   vec3 b = tap(f0 + ivec2(0, -1));",
      "   vec3 c = tap(f0 + ivec2(1, -1));",
      "   vec3 e = tap(f0 + ivec2(-1, 0));",
      "   vec3 f = tap(f0);",
      "   vec3 g = tap(f0 + ivec2(1, 0));",
      "   vec3 h = tap(f0 + ivec2(2, 0));",
      "   vec3 i = tap(f0 + ivec2(-1, 1));",
      "   vec3 j = tap(f0 + ivec2(0, 1));",
      "   vec3 k = tap(f0 + ivec2(1, 1));",
      "   vec3 l = tap(f0 + ivec2(2, 1));",
      "   vec3 n = tap(f0 + ivec2(0, 2));",
      "   vec3 o = tap(f0 + ivec2(1, 2));",
      "   float bL = luma(b), cL = luma(c), eL = luma(e), fL = luma(f), gL = luma(g), hL = luma(h);",
      "   float iL = luma(i), jL = luma(j), kL = luma(k), lL = luma(l), nL = luma(n), oL = luma(o);",
      "   vec2 dir = vec2(0.0);",
      "   float len = 0.0;",
      "   easuSet(dir, len, pp, true, false, false, false, bL, eL, fL, gL, jL);",
      "   easuSet(dir, len, pp, false, true, false, false, cL, fL, gL, hL, kL);",
      "   easuSet(dir, len, pp, false, false, true, false, fL, iL, jL, kL, nL);",
      "   easuSet(dir, len, pp, false, false, false, true, gL, jL, kL, lL, oL);",
      "   vec2 dir2 = dir * dir;",
      "   float dirR = dir2.x + dir2.y;",
      "   bool zro = dirR < (1.0 / 32768.0);",
      "   dirR = inversesqrt(max(dirR, 1.0e-12));",
      "   dirR = zro ? 1.0 : dirR;",
      "   dir.x = zro ? 1.0 : dir.x;",
      "   dir *= vec2(dirR);",
      "   len = len * 0.5;",
      "   len *= len;",
      "   float stretch = (dir.x * dir.x + dir.y * dir.y) / max(abs(dir.x), abs(dir.y));",
      "   vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);",
      "   float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;",
      "   float clp = 1.0 / lob;",
      "   vec3 min4 = min(min(f, g), min(j, k));",
      "   vec3 max4 = max(max(f, g), max(j, k));",
      "   vec3 aC = vec3(0.0);",
      "   float aW = 0.0;",
      "   easuTap(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, b);",
      "   easuTap(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, c);",
      "   easuTap(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, i);",
      "   easuTap(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, j);",
      "   easuTap(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, f);",
      "   easuTap(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, e);",
      "   easuTap(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, k);",
      "   easuTap(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, l);",
      "   easuTap(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, h);",
      "   easuTap(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, g);",
      "   easuTap(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, o);",
      "   easuTap(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, n);",
      "   vec3 pix = min(max4, max(min4, aC * (1.0 / aW)));",
      "   fragColor = vec4(pix, 1.0);",
      "}");

   /**
    * FidelityFX FSR 1.0 RCAS (ffx_fsr1.h, MIT, AMD): a 5-tap contrast-adaptive sharpen whose lobe is limited by
    * the local min / max so it never rings; sharpness = exp2(-stops). Noise is left as stock FSR does (no denoise).
    */
   static final String RCAS_FRAG = String.join("\n",
      "#version 330",
      "uniform sampler2D Source;",
      "uniform float sharpness;",
      "uniform ivec4 rect;",
      "out vec4 fragColor;",
      "vec3 tap(ivec2 p) {",
      "   p = clamp(p, rect.xy, rect.xy + rect.zw - 1);",
      "   return texelFetch(Source, p, 0).rgb;",
      "}",
      "void main() {",
      "   ivec2 sp = ivec2(gl_FragCoord.xy);",
      "   vec3 b = tap(sp + ivec2(0, -1));",
      "   vec3 d = tap(sp + ivec2(-1, 0));",
      "   vec3 e = tap(sp);",
      "   vec3 f = tap(sp + ivec2(1, 0));",
      "   vec3 h = tap(sp + ivec2(0, 1));",
      "   vec3 mn4 = min(min(b, d), min(f, h));",
      "   vec3 mx4 = max(max(b, d), max(f, h));",
      "   vec2 peakC = vec2(1.0, -4.0);",
      "   vec3 hitMin = mn4 / (4.0 * mx4 + 1.0e-6);",
      "   vec3 hitMax = (peakC.x - mx4) / (4.0 * mn4 + peakC.y);",
      "   vec3 lobeRGB = max(-hitMin, hitMax);",
      "   float lobe = max(-0.1875, min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0)) * sharpness;",
      "   float rcpL = 1.0 / (4.0 * lobe + 1.0);",
      "   vec3 pix = ((b + d + f + h) * lobe + e) * rcpL;",
      "   fragColor = vec4(clamp(pix, 0.0, 1.0), 1.0);",
      "}");
}
