package pzopt;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.TextureDraw;

/**
 * Ambient occlusion on the static world (Config keys {@code ambientOcclusion}, {@code aoScalePct}, {@code aoRadiusPct},
 * {@code aoStrengthPct}, {@code aoThicknessPct}).
 *
 * <p>Right after the chunk textures are composited the scene depth holds exactly the static world (floors, walls,
 * furniture, baked trees) and nothing else: characters, vehicles, items and the translucent layers are drawn later.
 * The FBO renderer's depth is linear in world space ({@code IsoDepthHelper}: {@code C - (x + y + 2z) * SQUARE_DEPTH / 2})
 * and the projection is orthographic, so every depth texel gives an exact view-space position: screen pixels over
 * {@code 32 * sqrt(2) * tileScale / zoom} per square across the screen, {@link #UNITS_PER_DEPTH} squares per unit of
 * depth along the view. The pass is ground-truth-style horizon AO in that space with visibility bitmasks (a slice's
 * hemisphere as 32 cosine-weighted sectors, each sample covering the sectors between its front and its assumed back,
 * {@code aoThicknessPct} behind), so thin things (fence posts, poles) occlude as little as they cover, and a wall far
 * in front of the floor behind it (a depth jump) adds no halo.
 *
 * <ol>
 *   <li>AO at {@code aoScalePct} % of the viewport per axis, two slice directions and four steps per side per texel,
 *       rotated over a 4x4 pixel tile (a Bayer order: the 4x4 tile holds 32 directions);</li>
 *   <li>a 4x4 depth-aware box over the same tile, which is exactly one period of the rotation pattern;</li>
 *   <li>a multiply onto the scene with depth-aware bilinear weights; pixels whose AO rounds to none are discarded
 *       before the blend, so open ground costs one small texture read.</li>
 * </ol>
 * Everything drawn after the chunk composite lands on top of the darkened scene unaffected.
 */
public final class AmbientOcclusion {
   /** Squares along the view per unit of depth: SQUARE_DEPTH / 2 per (x + y + 2z) over cos(30) cos(45) per (x + y + 2z). */
   static final float UNITS_PER_DEPTH = (float)(0.6123724356957945 / (0.0028867084 * 0.5));
   /** Screen pixels per square across the screen at tileScale 1, zoom 1 (the iso x axis is 32 px right, 16 down). */
   static final float PX_PER_UNIT = (float)(32.0 * Math.sqrt(2.0));

   private static volatile boolean failed;
   private static long frames;
   private static long skipped;

   private AmbientOcclusion() {
   }

   public static boolean enabled() {
      return Overrides.enabled() && Config.AO && "screen".equals(Config.AO_MODE) && !failed;
   }

   public static int scalePct() {
      return Math.max(25, Math.min(100, Config.AO_SCALE_PCT));
   }

   private static long changes; // game thread: chunk textures (re)drawn since the start

   /** Game thread: a chunk texture is about to be baked or drawn into; the static world changes this frame. */
   public static void changed() {
      changes++;
   }

   /**
    * Game thread, right after FBORenderChunkManager.endFrame(): queue the pass on the scene the chunk textures were drawn
    * into, with what decides whether the static scene is the one the AO buffer already holds: the camera offset and
    * zoom, the chunk textures composited and the bake count.
    */
   public static void queue(int playerIndex) {
      if (!enabled()) {
         return;
      }
      Frame f = FRAMES[nextFrame++ & 3];
      f.offX = zombie.iso.IsoCamera.getOffX();
      f.offY = zombie.iso.IsoCamera.getOffY();
      f.zoom = Core.getInstance().getZoom(playerIndex);
      zombie.iso.PlayerCamera cam = zombie.iso.IsoCamera.cameras[playerIndex];
      f.jiggleX = cam.fixJigglyModelsX;
      f.jiggleY = cam.fixJigglyModelsY;
      long sum = changes * 1000003L;
      java.util.ArrayList<zombie.iso.fboRenderChunk.FBORenderChunk> list = zombie.iso.fboRenderChunk.FBORenderChunkManager.instance.toRenderThisFrame;
      for (int i = 0; i < list.size(); i++) {
         sum = sum * 31L + list.get(i).index;
      }
      f.scene = sum;
      SpriteRenderer.instance.drawGeneric(f);
   }

   public static String stats() {
      return "ao: frames=" + frames + " computed=" + computed + (skipped > 0 ? " skipped=" + skipped : "") + (failed ? " FAILED" : "");
   }

   private static long computed;
   private static int nextFrame;
   private static final Frame[] FRAMES = {new Frame(), new Frame(), new Frame(), new Frame()};

   /** One frame's snapshot, rendered in stream order on the render thread. */
   static final class Frame extends TextureDraw.GenericDrawer {
      float offX;
      float offY;
      float zoom;
      float jiggleX;
      float jiggleY;
      long scene;

      boolean sameAs(Frame o) {
         return o != null && o.offX == this.offX && o.offY == this.offY && o.zoom == this.zoom && o.jiggleX == this.jiggleX && o.jiggleY == this.jiggleY
            && o.scene == this.scene;
      }

      void copyFrom(Frame o) {
         this.offX = o.offX;
         this.offY = o.offY;
         this.zoom = o.zoom;
         this.jiggleX = o.jiggleX;
         this.jiggleY = o.jiggleY;
         this.scene = o.scene;
      }

      @Override
      public void render() {
         try {
            GL.render(this);
         } catch (Throwable t) {
            fail("render: " + t);
         }
      }
   }

   private static final Gl GL = new Gl();

   private static void fail(String why) {
      if (!failed) {
         failed = true;
         Log.warn("ambient occlusion: " + why + "; off for the rest of the session");
      }
   }

   // ------------------------------------------------------------------------------------------------ render thread

   private static final class Gl {
      private int aoProgram;
      private int blurProgram;
      private int applyProgram;
      private int quadVbo;
      private int aoFbo;
      private int aoTex;
      private int blurFbo;
      private int blurTex;
      private int w;
      private int h;
      private final int[] viewport = new int[4];
      private final float[] viewportF = new float[4];
      private final Matrix4f mvp = new Matrix4f();
      private final int[] uAo = new int[4];
      private final int[] uBlur = new int[3];
      private final int[] uApply = new int[5];
      private boolean logged;

      private final Frame last = new Frame(); // what the AO buffer holds
      private boolean lastValid;

      void render(Frame frame) {
         if (failed) {
            return;
         }
         GL11.glGetIntegerv(GL11.GL_VIEWPORT, this.viewport);
         GL11.glGetFloatv(GL11.GL_VIEWPORT, this.viewportF);
         int vx = this.viewport[0];
         int vy = this.viewport[1];
         int vw = this.viewport[2];
         int vh = this.viewport[3];
         int sceneFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
         int depthTex = sceneDepthTexture(sceneFbo);
         Core core = Core.getInstance();
         if (vw <= 0 || vh <= 0 || depthTex == 0 || core.modelViewMatrixStack.isEmpty()) {
            skipped++;
            return;
         }
         this.mvp.set(core.projectionMatrixStack.peek()).mul(core.modelViewMatrixStack.peek());
         float ppu = PX_PER_UNIT * Core.tileScale * Math.abs(this.mvp.m00()) * vw * 0.5F; // viewport pixels per square
         if (!(ppu > 0.0F)) {
            skipped++;
            return;
         }
         if (this.aoProgram == 0 && !this.init()) {
            fail("shaders did not compile");
            return;
         }
         int pct = scalePct();
         int aw = Math.max(1, (vw * pct + 99) / 100);
         int ah = Math.max(1, (vh * pct + 99) / 100);
         if (!this.ensureTargets(aw, ah)) {
            fail("AO buffers incomplete");
            return;
         }
         boolean reuse = this.lastValid && frame.sameAs(this.last) && Config.AO_REUSE && Config.DEV_AO_DUMP_FRAME <= 0; // the static scene has not changed
         float scale = (float)aw / vw; // AO texels per viewport pixel
         float radius = Math.max(0.05F, Config.AO_RADIUS_PCT / 100.0F);
         float thickness = Math.max(0.01F, Config.AO_THICKNESS_PCT / 100.0F);
         float texelWorld = 1.0F / (scale * ppu); // squares per AO texel
         if (!this.logged) {
            this.logged = true;
            Log.info(String.format("ambient occlusion: viewport %dx%d at %d,%d, AO buffer %dx%d, %.1f px per square, radius %.2f squares = %.1f px, scene depth texture %d",
               vw, vh, vx, vy, aw, ah, ppu, radius, radius * ppu, depthTex));
         }

         if (Config.DEV_AO_DUMP_FRAME > 0 && frames == Config.DEV_AO_DUMP_FRAME) {
            dump(sceneFbo, vx, vy, vw, vh, ppu);
         }
         GpuSections.markNow("ao", false);
         this.stamp(0);
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glDisable(GL11.GL_STENCIL_TEST);
         GL11.glDisable(GL11.GL_ALPHA_TEST); // the sprite renderer leaves glAlphaFunc(GREATER, 0) on; GLStateRenderThread.restore() puts it back
         GL11.glDisable(GL11.GL_BLEND);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDepthMask(false);
         GL11.glColorMask(true, true, true, true);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.quadVbo);
         GL20.glEnableVertexAttribArray(0);
         for (int i = 1; i < 5; i++) {
            GL20.glDisableVertexAttribArray(i);
         }
         GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);

         // 1. AO at the reduced size (skipped, like 2., while the static scene is the one the buffer holds)
         if (!reuse) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.aoFbo);
            GL11.glViewport(0, 0, aw, ah);
            GL20.glUseProgram(this.aoProgram);
            GL20.glUniform1i(this.uAo[0], 0);
            GL20.glUniform4f(this.uAo[1], vx, vy, 1.0F / scale, ppu);
            GL20.glUniform4f(this.uAo[2], radius * ppu, thickness, UNITS_PER_DEPTH, radius);
            GL20.glUniform4f(this.uAo[3], vx + vw - 1, vy + vh - 1, Config.DEV_AO_VARIANT, 0.0F);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);

            this.stamp(1);
            // 2. 4x4 depth-aware box (one period of the direction pattern)
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.blurFbo);
            GL20.glUseProgram(this.blurProgram);
            GL20.glUniform1i(this.uBlur[0], 0);
            GL20.glUniform4f(this.uBlur[1], UNITS_PER_DEPTH, texelWorld, aw - 1, ah - 1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.aoTex);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
            this.last.copyFrom(frame);
            this.lastValid = true;
            computed++;
         } else {
            this.stamp(1);
         }

         this.stamp(2);
         // 3. multiply onto the scene
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, sceneFbo);
         if (this.viewportF[0] != vx || this.viewportF[1] != vy) {
            org.lwjgl.opengl.GL41.glViewportIndexedf(0, this.viewportF[0], this.viewportF[1], this.viewportF[2], this.viewportF[3]); // keep the upscaler's jitter
         } else {
            GL11.glViewport(vx, vy, vw, vh);
         }
         GL20.glUseProgram(this.applyProgram);
         GL20.glUniform1i(this.uApply[0], 0);
         GL20.glUniform1i(this.uApply[1], 1);
         GL20.glUniform4f(this.uApply[2], vx, vy, scale, texelWorld);
         GL20.glUniform4f(this.uApply[3], aw - 1, ah - 1, UNITS_PER_DEPTH, Math.max(0.0F, Config.AO_STRENGTH_PCT / 100.0F));
         GL20.glUniform4f(this.uApply[4], Config.DEV_AO_VIEW, ppu, 0.0F, 0.0F);
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.blurTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
         if (Config.DEV_AO_VIEW == 0) {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_ZERO, GL11.GL_SRC_COLOR);
            GL11.glColorMask(true, true, true, false);
         }
         GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
         this.stamp(3);
         this.collect();
         GpuSections.markNow("ao", true);

         // 4. leave the state the way VBORenderer / the sprite ring buffer expect it (as FogPass does)
         GL11.glColorMask(true, true, true, true);
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         for (int i = 0; i < 5; i++) {
            GL20.glEnableVertexAttribArray(i);
         }
         GL20.glUseProgram(0);
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glDepthMask(true);
         GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
         GLStateRenderThread.restore();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
         frames++;
      }

      /**
       * Dev (devAoDumpFrame): the scene depth (float32) and colour (RGBA8) of the viewport as the pass sees them, into
       * ~/Zomboid/pzopt-ao-depth.bin / pzopt-ao-color.bin with a pzopt-ao-dump.txt header, for offline work on the kernel.
       */
      private static void dump(int sceneFbo, int vx, int vy, int vw, int vh, float ppu) {
         try {
            int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sceneFbo);
            java.nio.FloatBuffer depth = BufferUtils.createFloatBuffer(vw * vh);
            GL11.glReadPixels(vx, vy, vw, vh, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
            ByteBuffer color = BufferUtils.createByteBuffer(vw * vh * 4);
            GL11.glReadPixels(vx, vy, vw, vh, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, color);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            java.io.File dir = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir());
            ByteBuffer db = ByteBuffer.allocate(vw * vh * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            db.asFloatBuffer().put(depth);
            java.nio.file.Files.write(new java.io.File(dir, "pzopt-ao-depth.bin").toPath(), db.array());
            byte[] cb = new byte[vw * vh * 4];
            color.get(cb);
            java.nio.file.Files.write(new java.io.File(dir, "pzopt-ao-color.bin").toPath(), cb);
            java.nio.file.Files.writeString(new java.io.File(dir, "pzopt-ao-dump.txt").toPath(),
               "w=" + vw + "\nh=" + vh + "\nppu=" + ppu + "\nunitsPerDepth=" + UNITS_PER_DEPTH + "\ntileScale=" + Core.tileScale + "\n");
            Log.info("ambient occlusion: dumped the " + vw + "x" + vh + " scene depth and colour to " + dir);
         } catch (Throwable t) {
            Log.warn("ambient occlusion: dump failed: " + t);
         }
      }

      // dev (devAoTiming): GPU time per stage from timestamp queries, 8 frames in flight, read when available
      private int[] queries;
      private int slot;
      private final long[] stageNs = new long[3];
      private long timedFrames;

      private void stamp(int stage) {
         if (!Config.DEV_AO_TIMING) {
            return;
         }
         if (this.queries == null) {
            this.queries = new int[8 * 4];
            GL15.glGenQueries(this.queries);
            java.util.Arrays.fill(this.pending, false);
         }
         GL33.glQueryCounter(this.queries[this.slot * 4 + stage], GL33.GL_TIMESTAMP);
      }

      private final boolean[] pending = new boolean[8];

      private void collect() {
         if (!Config.DEV_AO_TIMING) {
            return;
         }
         this.pending[this.slot] = true;
         this.slot = (this.slot + 1) % 8;
         if (this.pending[this.slot]) { // the oldest frame: 8 frames old, long done
            int base = this.slot * 4;
            if (GL15.glGetQueryObjecti(this.queries[base + 3], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
               long t0 = GL33.glGetQueryObjecti64(this.queries[base], GL15.GL_QUERY_RESULT);
               long t1 = GL33.glGetQueryObjecti64(this.queries[base + 1], GL15.GL_QUERY_RESULT);
               long t2 = GL33.glGetQueryObjecti64(this.queries[base + 2], GL15.GL_QUERY_RESULT);
               long t3 = GL33.glGetQueryObjecti64(this.queries[base + 3], GL15.GL_QUERY_RESULT);
               this.stageNs[0] += t1 - t0;
               this.stageNs[1] += t2 - t1;
               this.stageNs[2] += t3 - t2;
               if (++this.timedFrames == 1000) {
                  Log.info(String.format("ambient occlusion gpu us/frame: ao=%.1f blur=%.1f apply=%.1f total=%.1f (%d frames)", this.stageNs[0] / 1e3 / this.timedFrames,
                     this.stageNs[1] / 1e3 / this.timedFrames, this.stageNs[2] / 1e3 / this.timedFrames, (this.stageNs[0] + this.stageNs[1] + this.stageNs[2]) / 1e3 / this.timedFrames, this.timedFrames));
                  java.util.Arrays.fill(this.stageNs, 0L);
                  this.timedFrames = 0;
               }
            }
            this.pending[this.slot] = false;
         }
      }

      /** The scene framebuffer's depth attachment when it is a 2D texture (FogPass.sceneDepthAsTexture), else 0. */
      private static int sceneDepthTexture(int fbo) {
         if (fbo == 0) {
            return 0;
         }
         int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
         if (type != GL11.GL_TEXTURE) {
            return 0;
         }
         return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
      }

      private boolean ensureTargets(int aw, int ah) {
         if (this.aoFbo != 0 && this.w == aw && this.h == ah) {
            return true;
         }
         int previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
         int previousTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
         if (this.aoFbo != 0) {
            GL30.glDeleteFramebuffers(this.aoFbo);
            GL30.glDeleteFramebuffers(this.blurFbo);
            GL11.glDeleteTextures(this.aoTex);
            GL11.glDeleteTextures(this.blurTex);
         }
         this.lastValid = false;
         this.aoTex = texture(aw, ah);
         this.blurTex = texture(aw, ah);
         this.aoFbo = fbo(this.aoTex);
         int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
         this.blurFbo = fbo(this.blurTex);
         int status2 = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTex);
         this.w = aw;
         this.h = ah;
         if (status != GL30.GL_FRAMEBUFFER_COMPLETE || status2 != GL30.GL_FRAMEBUFFER_COMPLETE) {
            Log.warn("ambient occlusion: buffer " + aw + "x" + ah + " incomplete (0x" + Integer.toHexString(status) + ", 0x" + Integer.toHexString(status2) + ")");
            return false;
         }
         Log.info("ambient occlusion: AO buffer " + aw + "x" + ah + " (" + scalePct() + " % of the viewport)");
         return true;
      }

      /** RG32F: the AO term and the scene depth it was computed at (the blur and the upsample weigh by it). */
      private static int texture(int w, int h) {
         int tex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG32F, w, h, 0, GL30.GL_RG, GL11.GL_FLOAT, (ByteBuffer)null);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, CLAMP_TO_EDGE);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, CLAMP_TO_EDGE);
         return tex;
      }

      private static int fbo(int tex) {
         int fbo = GL30.glGenFramebuffers();
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
         GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
         return fbo;
      }

      private boolean init() {
         this.aoProgram = link(QUAD_VERT, AO_FRAG);
         this.blurProgram = link(QUAD_VERT, BLUR_FRAG);
         this.applyProgram = link(QUAD_VERT, APPLY_FRAG);
         if (this.aoProgram == 0 || this.blurProgram == 0 || this.applyProgram == 0) {
            return false;
         }
         this.uAo[0] = GL20.glGetUniformLocation(this.aoProgram, "SceneDepth");
         this.uAo[1] = GL20.glGetUniformLocation(this.aoProgram, "view");
         this.uAo[2] = GL20.glGetUniformLocation(this.aoProgram, "params");
         this.uAo[3] = GL20.glGetUniformLocation(this.aoProgram, "bounds");
         this.uBlur[0] = GL20.glGetUniformLocation(this.blurProgram, "Ao");
         this.uBlur[1] = GL20.glGetUniformLocation(this.blurProgram, "params");
         this.uApply[0] = GL20.glGetUniformLocation(this.applyProgram, "SceneDepth");
         this.uApply[1] = GL20.glGetUniformLocation(this.applyProgram, "Ao");
         this.uApply[2] = GL20.glGetUniformLocation(this.applyProgram, "view");
         this.uApply[3] = GL20.glGetUniformLocation(this.applyProgram, "params");
         this.uApply[4] = GL20.glGetUniformLocation(this.applyProgram, "dev");
         FloatBuffer quad = BufferUtils.createFloatBuffer(8);
         quad.put(new float[] {-1.0F, -1.0F, 1.0F, -1.0F, 1.0F, 1.0F, -1.0F, 1.0F}).flip();
         this.quadVbo = GL15.glGenBuffers();
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.quadVbo);
         GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quad, GL15.GL_STATIC_DRAW);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         return true;
      }
   }

   private static final int CLAMP_TO_EDGE = 33071;

   static int link(String vert, String frag) {
      int vs = compile(GL20.GL_VERTEX_SHADER, vert);
      int fs = compile(GL20.GL_FRAGMENT_SHADER, frag);
      if (vs == 0 || fs == 0) {
         return 0;
      }
      int program = GL20.glCreateProgram();
      GL20.glAttachShader(program, vs);
      GL20.glAttachShader(program, fs);
      GL20.glBindAttribLocation(program, 0, "aPosition");
      GL20.glLinkProgram(program);
      GL20.glDeleteShader(vs);
      GL20.glDeleteShader(fs);
      if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
         Log.warn("ambient occlusion: link failed: " + GL20.glGetProgramInfoLog(program, 4096));
         GL20.glDeleteProgram(program);
         return 0;
      }
      return program;
   }

   private static int compile(int type, String source) {
      int shader = GL20.glCreateShader(type);
      GL20.glShaderSource(shader, source);
      GL20.glCompileShader(shader);
      if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
         Log.warn("ambient occlusion: shader compile failed: " + GL20.glGetShaderInfoLog(shader, 4096));
         GL20.glDeleteShader(shader);
         return 0;
      }
      return shader;
   }

   // ------------------------------------------------------------------------------------------------ shaders

   static final String QUAD_VERT = String.join("\n",
      "#version 140",
      "in vec2 aPosition;",
      "void main() {",
      "   gl_Position = vec4(aPosition, 0.0, 1.0);",
      "}");

   /**
    * View space: x, y in squares from the viewport pixel (y up), z in squares away from the viewer from the depth; the
    * viewer looks along +z, so V (towards the viewer) is (0, 0, -1). Per texel: the full-size pixel under its centre,
    * its normal from the smaller of the one-pixel depth differences per axis, two slices, four steps per side, and per
    * slice a 32-sector visibility bitmask over the hemisphere around the normal projected into the slice. Sector k covers
    * sin(angle from the normal) in [k / 16 - 1, (k + 1) / 16 - 1]: equal cosine-weighted solid angle per sector.
    */
   private static final String AO_FRAG = String.join("\n",
      "#version 140",
      "uniform sampler2D SceneDepth;",
      "uniform vec4 view;", // viewport origin x, y in the depth texture; viewport pixels per AO texel; viewport pixels per square
      "uniform vec4 params;", // radius in pixels, thickness in squares, squares per unit depth, radius in squares
      "uniform vec4 bounds;", // last viewport pixel x, y in the depth texture; z: dev variant (1 = one depth read, 2 = none)
      "out vec4 result;",
      "const float HALF_PI = 1.5707963;",
      "const float BAYER[16] = float[16](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);",
      "float depthAt(vec2 p) {",
      "   ivec2 q = clamp(ivec2(floor(p)) + ivec2(view.xy), ivec2(view.xy), ivec2(bounds.xy));",
      "   return texelFetch(SceneDepth, q, 0).r;",
      "}",
      "uint popc(uint v) {",
      "   v = v - ((v >> 1u) & 0x55555555u);",
      "   v = (v & 0x33333333u) + ((v >> 2u) & 0x33333333u);",
      "   return (((v + (v >> 4u)) & 0x0F0F0F0Fu) * 0x01010101u) >> 24u;",
      "}",
      "uint sectors(float u0, float u1) {", // the sectors between u0 < u1 (sin of the angle from the normal, mapped to 0..32)
      "   uint b0 = uint(clamp(floor(u0 + 0.35), 0.0, 32.0));", // a grazing sample on the same plane marks nothing
      "   uint b1 = uint(clamp(ceil(u1 - 0.35), 0.0, 32.0));",
      "   if (b1 <= b0) return 0u;",
      "   uint hi = b1 >= 32u ? 0xFFFFFFFFu : ((1u << b1) - 1u);",
      "   return hi & ~((1u << b0) - 1u);",
      "}",
      // a slice vector (towards the viewer, along +D) as sin(angle - n) mapped to 0..32, clamped past the hemisphere's rim
      "float sectorOf(vec2 v, float cn, float sn) {",
      "   v = normalize(v);",
      "   float s = v.y * cn - v.x * sn;",
      "   if (v.x * cn + v.y * sn < 0.0) s = v.y >= 0.0 ? 1.0 : -1.0;", // behind the hemisphere: the rim on the vector's side of V
      "   return (s + 1.0) * 16.0;",
      "}",
      "void main() {",
      "   ivec2 t = ivec2(gl_FragCoord.xy);",
      "   vec2 c = floor((vec2(t) + 0.5) * view.z) + 0.5;", // the full-size pixel under the texel centre (viewport-relative)
      "   if (bounds.z > 1.5) { result = vec4(1.0, 0.5, 0.0, 1.0); return; }",
      "   float d = depthAt(c);",
      "   if (bounds.z > 0.5) { result = vec4(1.0, d, 0.0, 1.0); return; }",
      "   if (d >= 0.99999) {",
      "      result = vec4(1.0, d, 0.0, 1.0);",
      "      return;",
      "   }",
      "   float kz = params.z;",
      "   float ppu = view.w;",
      "   vec3 P = vec3(c / ppu, d * kz);",
      // two-pixel differences: the chunk textures are drawn at the zoom's scale, so one-pixel depth steps alternate (2:1)
      "   float dl = depthAt(c - vec2(2.0, 0.0));",
      "   float dr = depthAt(c + vec2(2.0, 0.0));",
      "   float dd = depthAt(c - vec2(0.0, 2.0));",
      "   float du = depthAt(c + vec2(0.0, 2.0));",
      "   float zx = 0.5 * (abs(dr - d) < abs(d - dl) ? dr - d : d - dl);",
      "   float zy = 0.5 * (abs(du - d) < abs(d - dd) ? du - d : d - dd);",
      "   vec3 N = normalize(vec3(zx * kz * ppu, zy * kz * ppu, -1.0));",
      // tiles are floors and walls: a normal close to one of the three planes is that plane (no quantisation noise)
      "   const vec3 NG = vec3(0.0, 0.8660254, -0.5);", // the ground: world up, seen from 30 degrees
      "   const vec3 NE = vec3(0.7071068, -0.3535534, -0.6123724);", // a wall facing +x (east, lower right)
      "   const vec3 NS = vec3(-0.7071068, -0.3535534, -0.6123724);", // a wall facing +y (south, lower left)
      "   float g = dot(N, NG), e = dot(N, NE), so = dot(N, NS);",
      "   if (g > 0.94) N = NG; else if (e > 0.94) N = NE; else if (so > 0.94) N = NS;",
      "   const vec3 V = vec3(0.0, 0.0, -1.0);",
      "   float bayer = BAYER[(t.x & 3) + 4 * (t.y & 3)];",
      "   float jitter = fract(bayer * 0.618034 + 0.5 * float((t.x ^ t.y) & 1));",
      "   float radiusPx = params.x;",
      "   float thickness = params.y;",
      "   float radius = params.w;",
      "   float vis = 0.0;",
      "   float wsum = 0.0;",
      "   for (int i = 0; i < 2; i++) {",
      "      float phi = (float(i) + bayer / 16.0) * HALF_PI;", // two slices 90 degrees apart, the tile rotates them through 90
      "      vec2 dir = vec2(cos(phi), sin(phi));",
      "      vec3 D = vec3(dir, 0.0);",
      "      vec3 axis = vec3(-dir.y, dir.x, 0.0);",
      "      vec3 pn = N - axis * dot(N, axis);",
      "      float pnl = length(pn);",
      "      if (pnl < 1e-4) continue;",
      "      float cn = dot(pn, V) / pnl;", // cos and sin of the projected normal's angle from V towards +D
      "      float sn = dot(pn, D) / pnl;",
      "      uint mask = 0u;",
      "      for (int side = 0; side < 2; side++) {",
      "         float s = side == 0 ? 1.0 : -1.0;",
      "         for (int j = 0; j < 4; j++) {",
      "            float f = (float(j) + jitter) / 4.0;",
      "            float rpx = max(float(j) + 1.0, f * f * radiusPx);",
      "            vec2 sp = c + dir * s * rpx;",
      "            float sd = depthAt(sp);",
      "            if (sd >= 0.99999) continue;",
      "            vec3 dF = vec3((floor(sp) + 0.5) / ppu, sd * kz) - P;",
      "            if (dot(dF, dF) > radius * radius) continue;", // beyond the radius (a depth jump): not an occluder
      "            if (dot(dF, N) < 0.04) continue;", // micro-steps (a tile edge's row written a little behind): no occluder under 0.04 squares above the plane
      "            vec2 fv = vec2(-dF.z, dot(dF.xy, dir));", // (towards the viewer, along +D); the back is thickness farther
      "            float uF = sectorOf(fv, cn, sn);",
      "            float uB = sectorOf(fv - vec2(thickness, 0.0), cn, sn);",
      "            mask |= sectors(min(uF, uB), max(uF, uB));",
      "         }",
      "      }",
      "      vis += (1.0 - float(popc(mask)) / 32.0) * pnl;",
      "      wsum += pnl;",
      "   }",
      "   result = vec4(wsum > 0.0 ? vis / wsum : 1.0, d, 0.0, 1.0);",
      "}");

   /** 4x4 box over one period of the rotation pattern, weighted by how close each texel's depth is to the centre's. */
   private static final String BLUR_FRAG = String.join("\n",
      "#version 140",
      "uniform sampler2D Ao;",
      "uniform vec4 params;", // squares per unit depth, squares per AO texel, last texel x, y
      "out vec4 result;",
      "void main() {",
      "   ivec2 t = ivec2(gl_FragCoord.xy);",
      "   vec2 c = texelFetch(Ao, t, 0).rg;",
      "   if (c.g >= 0.99999) {",
      "      result = vec4(1.0, c.g, 0.0, 1.0);",
      "      return;",
      "   }",
      "   float sum = 0.0;",
      "   float wsum = 0.0;",
      "   for (int y = -1; y <= 2; y++) {",
      "      for (int x = -1; x <= 2; x++) {",
      "         vec2 a = texelFetch(Ao, clamp(t + ivec2(x, y), ivec2(0), ivec2(params.zw)), 0).rg;",
      "         float tol = 0.08 + 3.0 * params.y * float(max(abs(x), abs(y)));", // a surface may slope 3 squares of depth per square
      "         float dz = (a.g - c.g) * params.x / tol;",
      "         float w = exp(-dz * dz);",
      "         sum += a.r * w;",
      "         wsum += w;",
      "      }",
      "   }",
      "   result = vec4(sum / wsum, c.g, 0.0, 1.0);",
      "}");

   /**
    * The scene times the AO: the four nearest AO texels, bilinear weights times how close each one's depth is to this
    * pixel's own; nothing close (a thin object between texels) leaves the pixel alone. dev.x 1 shows the AO term,
    * 2 the normals reconstructed at full size.
    */
   private static final String APPLY_FRAG = String.join("\n",
      "#version 140",
      "uniform sampler2D SceneDepth;",
      "uniform sampler2D Ao;",
      "uniform vec4 view;", // viewport origin x, y; AO texels per pixel; squares per AO texel
      "uniform vec4 params;", // last AO texel x, y; squares per unit depth; strength
      "uniform vec4 dev;", // view mode, viewport pixels per square
      "out vec4 fragColor;",
      "void main() {",
      "   ivec2 sp = ivec2(gl_FragCoord.xy);",
      "   if (dev.x > 1.5) {",
      "      float d = texelFetch(SceneDepth, sp, 0).r;",
      "      float kz = params.z * dev.y;",
      "      float dr = texelFetch(SceneDepth, sp + ivec2(2, 0), 0).r;",
      "      float dl = texelFetch(SceneDepth, sp - ivec2(2, 0), 0).r;",
      "      float du = texelFetch(SceneDepth, sp + ivec2(0, 2), 0).r;",
      "      float dd = texelFetch(SceneDepth, sp - ivec2(0, 2), 0).r;",
      "      float zx = 0.5 * (abs(dr - d) < abs(d - dl) ? dr - d : d - dl);",
      "      float zy = 0.5 * (abs(du - d) < abs(d - dd) ? du - d : d - dd);",
      "      fragColor = vec4(normalize(vec3(zx * kz, zy * kz, -1.0)) * 0.5 + 0.5, 1.0);",
      "      return;",
      "   }",
      "   vec2 f = (gl_FragCoord.xy - view.xy) * view.z - 0.5;",
      "   vec2 i0 = floor(f);",
      "   vec2 fr = f - i0;",
      "   ivec2 maxi = ivec2(params.xy);",
      "   vec2 ta = texelFetch(Ao, clamp(ivec2(i0), ivec2(0), maxi), 0).rg;",
      "   vec2 tb = texelFetch(Ao, clamp(ivec2(i0) + ivec2(1, 0), ivec2(0), maxi), 0).rg;",
      "   vec2 tc = texelFetch(Ao, clamp(ivec2(i0) + ivec2(0, 1), ivec2(0), maxi), 0).rg;",
      "   vec2 te = texelFetch(Ao, clamp(ivec2(i0) + ivec2(1, 1), ivec2(0), maxi), 0).rg;",
      "   vec4 ao4 = vec4(ta.r, tb.r, tc.r, te.r);",
      "   if (dev.x < 0.5 && min(min(ao4.x, ao4.y), min(ao4.z, ao4.w)) > 0.996) discard;", // open ground: no depth read, no blend
      "   float d = texelFetch(SceneDepth, sp, 0).r;",
      "   if (d >= 0.99999) {",
      "      if (dev.x > 0.5) { fragColor = vec4(1.0); return; }",
      "      discard;",
      "   }",
      "   vec4 w = vec4((1.0 - fr.x) * (1.0 - fr.y), fr.x * (1.0 - fr.y), (1.0 - fr.x) * fr.y, fr.x * fr.y);",
      "   float tol = 0.08 + 2.0 * view.w;",
      "   vec4 dz = (vec4(ta.g, tb.g, tc.g, te.g) - vec4(d)) * (params.z / tol);",
      "   w *= exp(-dz * dz);",
      "   float ws = w.x + w.y + w.z + w.w;",
      "   float ao = ws > 1e-3 ? dot(w, ao4) / ws : 1.0;",
      "   ao = clamp(1.0 - (1.0 - ao) * params.w, 0.0, 1.0);",
      "   if (dev.x < 0.5 && ao > 0.996) discard;",
      "   fragColor = vec4(vec3(ao), 1.0);",
      "}");
}
