package pzopt;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.Texture;

/**
 * The ImprovedFog pass without its cost (Config keys {@code fogPass}, {@code fogScalePct}).
 *
 * <p>Stock draws heavy fog as one screen-wide rectangle per tile row per level (two levels): each rectangle is
 * 96 texture pixels tall while the rows are 16 apart, so every screen pixel is shaded by up to twelve of them
 * (seven noise fetches each, a {@code gl_FragDepth} write that disables early depth rejection, one blend each),
 * and every rectangle is its own draw call with three uniform updates. On the 5120x2160 desktop that was
 * ~1.9 ms of GPU per frame (512 → 256 fps uncapped on the 120 km/h route). The game thread walks every
 * on-screen square and its objects per level just to feed the row iterator in painter's order, which the
 * FBO renderer never needs (the rectangles are drawn at the end of the frame anyway).
 *
 * <p>This pass keeps the stock rectangles, noise and alpha maths, but:
 * <ul>
 *   <li>the render thread draws all rectangles of the frame in one draw call: the per-rectangle uniforms
 *       (rectangle position and size, row noise offset, layer alpha) travel as vertex attributes;</li>
 *   <li>they are drawn into a fog buffer of {@code fogScalePct} % of the viewport per axis, depth-tested
 *       against a copy of the scene depth ({@code glBlitFramebuffer}, nearest), and the buffer is composited
 *       onto the scene once with one bilinear full-screen quad (premultiplied alpha, order-independent since
 *       every rectangle of a pixel has the same colour); at 50 % that is a quarter of the fragment and blend
 *       work, at 25 % a sixteenth;</li>
 *   <li>the rectangle depth comes from the vertex position instead of {@code gl_FragDepth}, so fragments
 *       behind walls are rejected before they are shaded;</li>
 *   <li>the game thread runs the row iterator once per level without the square walk
 *       ({@code ImprovedFog.endRender} does it), with the chunk looked up once per eight squares.</li>
 * </ul>
 * If the depth copy is refused by the driver the stock drawer takes over for the rest of the session.
 */
public final class FogPass {
   private static final int RECT_BYTES = 60; // ImprovedFogDrawer.RECTANGLE_BYTES
   private static final int STRIDE = 36; // vec3 position, vec4 rect (px, py, sideFrac, rowOffset), vec2 (depth, layerAlpha)

   /** Set once the driver refused the depth copy; the stock drawer runs from then on. */
   private static volatile boolean fallback;
   private static long framesDrawn;
   private static long rectanglesDrawn;

   private FogPass() {
   }

   public static boolean enabled() {
      return Overrides.enabled() && Config.FOG_PASS && !fallback;
   }

   public static int scalePct() {
      return Math.max(25, Math.min(100, Config.FOG_SCALE_PCT)); // below 25 % the depth reduction would read more than 4x4 pixels per texel
   }

   private static long walkNs;
   private static long segmentNs;
   private static long walks;
   private static long segments;
   private static long squares;

   /** Game thread: one layer's row walk took {@code ns}, of which {@code segNs} in {@code segs} segment ends over {@code sq} squares. */
   public static void walked(long ns, long segNs, int segs, int sq) {
      walkNs += ns;
      segmentNs += segNs;
      segments += segs;
      squares += sq;
      walks++;
   }

   public static String stats() {
      String s = "fog pass: frames=" + framesDrawn + " rectangles=" + rectanglesDrawn + (fallback ? " FALLBACK (stock drawer)" : "");
      if (walks > 0) {
         s += String.format(" | walk us/layer=%.0f (segments %.0f us in %.1f, squares %.0f)", walkNs / 1000.0 / walks, segmentNs / 1000.0 / walks,
            (double)segments / walks, (double)squares / walks);
         walkNs = segmentNs = walks = segments = squares = 0L;
      }
      return s;
   }

   // ------------------------------------------------------------------------------------------------ scene depth

   private static final java.util.ArrayList<int[]> sceneDepthTextures = new java.util.ArrayList<>(); // {fbo id, texture id}

   /**
    * Replaces the offscreen buffer's DEPTH24_STENCIL8 renderbuffer with a DEPTH24_STENCIL8 texture on the same
    * attachments (Config {@code fogPass}; MultiTextureFBO2.createTexture calls it right after the FBO is made).
    * Rendering into it is the same; the fog pass can then read the scene depth where it is instead of copying
    * the whole depth buffer every frame (44 MB at 5120x2160), and at 100 % the fog buffer shares it outright.
    * On any failure the renderbuffer is put back. Textures of FBOs that no longer exist are deleted here.
    */
   public static void sceneDepthAsTexture(zombie.core.textures.TextureFBO fbo, Texture tex) {
      if (!Overrides.enabled() || !(Config.FOG_PASS || Config.AO && "screen".equals(Config.AO_MODE)) || Config.FOG_DEPTH_COPY || fbo == null) { // ambient occlusion reads it in place too
         return;
      }
      zombie.core.opengl.RenderThread.invokeOnRenderContext(() -> {
         int id = fboId(fbo);
         if (id <= 0) {
            return;
         }
         int previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
         int previousTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, id);
         int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
         int rb = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
         int stencilType = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_STENCIL_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
         int w = tex.getWidthHW();
         int h = tex.getHeightHW();
         if (type != GL30.GL_RENDERBUFFER || w <= 0 || h <= 0) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
            return;
         }
         for (int i = sceneDepthTextures.size() - 1; i >= 0; i--) {
            int[] e = sceneDepthTextures.get(i);
            if (!GL30.glIsFramebuffer(e[0]) || e[0] == id) {
               GL11.glDeleteTextures(e[1]);
               sceneDepthTextures.remove(i);
            }
         }
         while (GL11.glGetError() != 0) {
         }
         int depthTex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH24_STENCIL8, w, h, 0, GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, (ByteBuffer)null);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12_CLAMP_TO_EDGE);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12_CLAMP_TO_EDGE);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTex);
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTex, 0);
         int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
         int err = GL11.glGetError();
         if (status != GL30.GL_FRAMEBUFFER_COMPLETE || err != 0) {
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, rb);
            if (stencilType == GL30.GL_RENDERBUFFER) {
               GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, rb);
            }
            GL11.glDeleteTextures(depthTex);
            Log.warn("fog pass: offscreen buffer " + w + "x" + h + " keeps its depth renderbuffer (status 0x" + Integer.toHexString(status)
               + ", GL error 0x" + Integer.toHexString(err) + ")");
         } else {
            GL30.glDeleteRenderbuffers(rb); // TextureFBO.destroy deletes the name again later, which GL ignores
            sceneDepthTextures.add(new int[] {id, depthTex});
            Log.info("fog pass: offscreen buffer " + w + "x" + h + " (fbo " + id + ") depth+stencil is texture " + depthTex);
         }
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
      });
   }

   private static java.lang.reflect.Field fboIdField;

   /** TextureFBO keeps its framebuffer name private; 0 when it cannot be read. */
   static int fboId(zombie.core.textures.TextureFBO fbo) {
      try {
         if (fboIdField == null) {
            java.lang.reflect.Field f = zombie.core.textures.TextureFBO.class.getDeclaredField("id");
            f.setAccessible(true);
            fboIdField = f;
         }
         return fboIdField.getInt(fbo);
      } catch (ReflectiveOperationException | RuntimeException e) {
         Log.warn("fog pass: TextureFBO id not readable (" + e + "); the scene depth stays a renderbuffer");
         return 0;
      }
   }

   // ------------------------------------------------------------------------------------------------ game thread

   /**
    * Per chunk: which squares of levels 0 and 1 take fog (no square, or exterior and not in a room), packed per
    * diagonal lx + ly (bit lx of {@code diag[level][lx + ly]}), so the fog row walk reads up to eight squares of a
    * row from one short. Refreshed lazily every {@code fogMaskFrames} frames, the first refresh staggered per
    * chunk so the refreshes spread over the frames. Lives on {@code IsoChunk.pzoptFog}.
    */
   public static final class ChunkFog {
      private final short[][] diag = new short[2][15];
      private final int[] frame = {Integer.MIN_VALUE, Integer.MIN_VALUE};

      /** The level's diagonal masks, recomputed when older than {@code refresh} frames. */
      public short[] diagonals(zombie.iso.IsoChunk chunk, int level, int now, int refresh) {
         int l = level & 1;
         if (now - this.frame[l] >= refresh || this.frame[l] == Integer.MIN_VALUE) {
            this.refresh(chunk, level, now, refresh);
         }
         return this.diag[l];
      }

      private void refresh(zombie.iso.IsoChunk chunk, int level, int now, int refresh) {
         int l = level & 1;
         short[] d = this.diag[l];
         zombie.iso.IsoGridSquare[] squares = null;
         if (level >= chunk.minLevel && level <= chunk.maxLevel) {
            int index = chunk.squaresIndexOfLevel(level);
            if (index >= 0 && index < chunk.squares.length) {
               squares = chunk.squares[index];
            }
         }
         java.util.Arrays.fill(d, (short)0);
         for (int ly = 0; ly < 8; ly++) {
            for (int lx = 0; lx < 8; lx++) {
               zombie.iso.IsoGridSquare square = squares == null ? null : squares[ly * 8 + lx];
               if (square == null || square.isExteriorCache && !square.isInARoom()) {
                  d[lx + ly] |= (short)(1 << lx);
               }
            }
         }
         // first refresh: pretend it happened up to refresh-1 frames ago so the chunks do not all refresh on the same frame
         int stagger = this.frame[l] == Integer.MIN_VALUE ? Math.floorMod(chunk.wx * 31 + chunk.wy * 17 + level * 7, Math.max(1, refresh)) : 0;
         this.frame[l] = now - stagger;
      }
   }

   // ------------------------------------------------------------------------------------------------ render thread

   /** Per-drawer render-thread state (one per player and sprite-renderer state, like the drawers themselves). */
   public static final class Gl {
      private int vbo;
      private final int[] vbos = new int[3];
      private int vboIndex;
      private ByteBuffer staging;
      // the fog buffer: colour + depth texture at fogScalePct % of the viewport
      private int fogFbo;
      private int fogColorTex;
      private int fogMaxDepthTex; // R32F: the farthest scene depth of each fog texel's block (the nearest is the depth attachment)
      private int fogFarFbo; // colour only: the rectangles that pass against the block's farthest depth
      private int fogFarTex;
      private int fogDepthTex;
      private int fogW;
      private int fogH;
      // below 100 %: a full-size copy of the scene depth the downsample and the composite read
      private int sceneFbo;
      private int sceneDepthTex;
      private int sceneW;
      private int sceneH;
      private int depthFormat;
      private boolean depthStencil;
      private int fogProgram;
      private int downProgram;
      private int compositeProgram;
      private int quadVbo;
      private final int[] uni = new int[16];
      private final FloatBuffer mat = BufferUtils.createFloatBuffer(16);
      private final int[] viewport = new int[4];
      private final float[] viewportF = new float[4]; // the exact viewport, fractional offset included (the upscaler's sub-pixel jitter)
      private final Matrix4f mvp = new Matrix4f();
      private int checks;
      private int sampler;
      private int mipTextureId;
      private boolean mipsOk;
      private final float[] clearColor = new float[4];

      /**
       * Draw the frame's fog rectangles. {@code rects} is the drawer's rectangle buffer, flipped (limit = bytes),
       * {@code u} the drawer's uniform floats in the order screenInfo, textureInfo, worldOffset, scalingInfo,
       * colorInfo, paramInfo, cameraInfo (4 each). Returns false when the stock drawer must run instead.
       */
      public boolean render(ByteBuffer rects, float[] u, Texture noise) {
         if (fallback || noise == null) {
            return false;
         }
         int numRects = rects.limit() / RECT_BYTES;
         if (numRects == 0) {
            return true;
         }
         if (this.fogProgram == 0 && !this.initPrograms()) {
            fail("fog shaders did not compile");
            return false;
         }
         GL11.glGetIntegerv(GL11.GL_VIEWPORT, this.viewport);
         GL11.glGetFloatv(GL11.GL_VIEWPORT, this.viewportF);
         int vx = this.viewport[0];
         int vy = this.viewport[1];
         int vw = this.viewport[2];
         int vh = this.viewport[3];
         if (vw <= 0 || vh <= 0) {
            return true;
         }
         int currentFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
         int pct = scalePct();
         boolean scaled = pct < 100;
         int fw = Math.max(1, vw * pct / 100);
         int fh = Math.max(1, vh * pct / 100);
         int sceneTex = sceneDepthTextureOf(currentFbo); // the scene depth in place (sceneDepthAsTexture), else 0 = copy it
         boolean inPlace = sceneTex != 0;
         if (!this.ensureBuffers(fw, fh, scaled && !inPlace ? vw : 0, scaled && !inPlace ? vh : 0, currentFbo, inPlace && !scaled ? sceneTex : 0)) {
            fail("fog buffers incomplete");
            return false;
         }
         boolean check = this.checks < 8;
         if (check) {
            while (GL11.glGetError() != 0) {
            }
         }

         GpuSections.markNow("fog.blit", false);
         if (!inPlace) {
            // 1. the scene depth: at 100 % straight into the fog buffer; below, a same-size copy the next pass reduces
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, currentFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, scaled ? this.sceneFbo : this.fogFbo);
            GL30.glBlitFramebuffer(vx, vy, vx + vw, vy + vh, 0, 0, vw, vh, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            if (check) {
               this.checks++;
               int err = GL11.glGetError();
               if (err != 0) {
                  GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo);
                  fail("depth blit refused (GL error 0x" + Integer.toHexString(err) + ")");
                  return false;
               }
            }
         }
         int sceneDepth = inPlace ? sceneTex : this.sceneDepthTex; // what the reduction and the composite read below 100 %
         int sceneDepthX = inPlace ? vx : 0; // its texel origin of the viewport
         int sceneDepthY = inPlace ? vy : 0;
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fogFbo);
         GL11.glViewport(0, 0, fw, fh);
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glDisable(GL11.GL_STENCIL_TEST);
         GL11.glDisable(GL11.GL_BLEND);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.quadVbo);
         GL20.glEnableVertexAttribArray(0);
         GL20.glDisableVertexAttribArray(1);
         GL20.glDisableVertexAttribArray(2);
         GL20.glDisableVertexAttribArray(3);
         GL20.glDisableVertexAttribArray(4);
         GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
         if (scaled) {
            // 1b. the nearest depth of every block of scene pixels becomes the fog texel's depth, so a block that holds
            // a thin near object (a power line, a fence post) keeps that object's depth and the fog behind it stays out
            GL20.glUseProgram(this.downProgram);
            GL20.glUniform1i(this.uni[11], 0);
            GL20.glUniform4f(this.uni[12], (float)vw / fw, (float)vh / fh, sceneDepthX + vw - 1, sceneDepthY + vh - 1);
            GL20.glUniform2f(this.uniBlockOrigin, sceneDepthX, sceneDepthY);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneDepth);
            GL20.glDrawBuffers(this.drawBuffersMax); // the farthest depth goes to colour attachment 1, the nearest to the depth attachment
            GL11.glColorMask(true, true, true, true);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_ALWAYS);
            GL11.glDepthMask(true);
            GL11.glDrawArrays(GL11.GL_QUADS, 0, 4);
            GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
         }
         GpuSections.markNow("fog.blit", true);

         GpuSections.markNow("fog.rects", false);
         // 2. the rectangles, one draw call, depth-tested against the copy
         GL11.glColorMask(true, true, true, true);
         GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, this.clearColor);
         GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
         GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
         GL11.glClearColor(this.clearColor[0], this.clearColor[1], this.clearColor[2], this.clearColor[3]);

         this.upload(rects, numRects);
         GL20.glUseProgram(this.fogProgram);
         this.setMvp();
         GL20.glUniform4f(this.uni[0], u[0], u[1], u[2], u[3]); // screenInfo
         GL20.glUniform4f(this.uni[1], u[4], u[5], u[6], u[7]); // textureInfo
         GL20.glUniform4f(this.uni[2], u[8], u[9], u[10], u[11]); // worldOffset
         GL20.glUniform4f(this.uni[3], u[12], u[13], u[14], u[15]); // scalingInfo
         GL20.glUniform4f(this.uni[4], u[16], u[17], u[18], u[19]); // colorInfo
         GL20.glUniform4f(this.uni[5], u[20], u[21], u[22], u[23]); // paramInfo
         GL20.glUniform4f(this.uni[6], u[24], u[25], u[26], u[27]); // cameraInfo
         GL20.glUniform4f(this.uni[7], (float)fw / vw, (float)fh / vh, vx, vy); // fogScale: buffer px -> viewport px
         GL20.glUniform1i(this.uni[8], 0);
         GL20.glUniform1i(this.uniMaxDepth, 1);
         GL20.glUniform1i(this.uniFarMode, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.fogMaxDepthTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glEnable(GL11.GL_TEXTURE_2D);
         noise.getTextureId().bind();
         this.bindNoiseSampler(noise.getTextureId().getID());

         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glDepthFunc(GL11.GL_LESS);
         GL11.glDepthMask(false);
         GL11.glEnable(GL11.GL_BLEND);
         GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
         GL20.glEnableVertexAttribArray(1);
         GL20.glEnableVertexAttribArray(2);
         GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, STRIDE, 0L);
         GL20.glVertexAttribPointer(1, 4, GL11.GL_FLOAT, false, STRIDE, 12L);
         GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, STRIDE, 28L);
         if (!Config.DEV_FOG_NO_DRAW) {
            GL11.glDrawArrays(GL11.GL_QUADS, 0, numRects * 4);
         }
         if (scaled) {
            // 2b. the same rectangles against the block's farthest depth (shader-side test, the buffer has no depth):
            // the composite interpolates each pixel between the two by its own depth, so a pixel farther than its
            // block's nearest (a power line over the ground behind it) gets the rows stock would draw over it
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fogFarFbo);
            GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL11.glClearColor(this.clearColor[0], this.clearColor[1], this.clearColor[2], this.clearColor[3]);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL20.glUniform1i(this.uniFarMode, 1);
            if (!Config.DEV_FOG_NO_DRAW) {
               GL11.glDrawArrays(GL11.GL_QUADS, 0, numRects * 4);
            }
         }
         GL33.glBindSampler(0, 0);
         GpuSections.markNow("fog.rects", true);

         GpuSections.markNow("fog.composite", false);
         // 3. back to the scene: the fog buffer over it once, premultiplied; below 100 % each screen pixel takes the
         // four nearest fog texels weighted by bilinear distance and by how close their depth is to its own scene
         // depth, so a wire keeps the fog of the texel that was decided at its depth and the ground next to it its own
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo);
         if (this.viewportF[0] != vx || this.viewportF[1] != vy) {
            org.lwjgl.opengl.GL41.glViewportIndexedf(0, this.viewportF[0], this.viewportF[1], this.viewportF[2], this.viewportF[3]); // keep the upscaler's jitter
         } else {
            GL11.glViewport(vx, vy, vw, vh);
         }
         GL20.glUseProgram(this.compositeProgram);
         GL20.glUniform1i(this.uni[9], 0);
         GL20.glUniform1i(this.uni[13], 1);
         GL20.glUniform1i(this.uni[14], 2);
         GL20.glUniform4f(this.uni[15], (float)fw / vw, (float)fh / vh, vx, vy); // compInfo
         GL20.glUniform4f(this.uniCompSize, fw, fh, scaled ? 1.0F : 0.0F, inPlace ? 1.0F : 0.0F); // compSize (w: the scene depth is the scene's own texture, viewport-positioned)
         GL20.glUniform1i(this.uniDevView, Config.DEV_FOG_DEPTH_VIEW);
         GL20.glUniform1i(this.uniCompMax, 3);
         GL20.glUniform1i(this.uniCompFar, 4);
         GL13.glActiveTexture(GL13.GL_TEXTURE4);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.fogFarTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE3);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.fogMaxDepthTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE2);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, scaled ? sceneDepth : this.fogDepthTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.fogDepthTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.fogColorTex);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.quadVbo);
         GL20.glDisableVertexAttribArray(1);
         GL20.glDisableVertexAttribArray(2);
         GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
         GL11.glDrawArrays(GL11.GL_QUADS, 0, 4);
         GpuSections.markNow("fog.composite", true);

         // 4. leave the state the way VBORenderer / the sprite ring buffer expect it
         GL13.glActiveTexture(GL13.GL_TEXTURE4);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE3);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE2);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
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
         framesDrawn++;
         rectanglesDrawn += numRects;
         return true;
      }

      /**
       * The noise texture is loaded without mipmaps (min filter linear), and the stock path samples it at full
       * resolution, where neighbouring fragments hit the same texels. In a scaled fog buffer the fetches are
       * one or more texels apart, so every one of the seven fetches per fragment misses the texture cache and
       * the draw becomes memory-bound (1.2 ms at 25 % on a Radeon 890M, independent of the buffer size) and the
       * noise aliases. So the texture gets mipmaps once (glGenerateMipmap on the game's texture object; the
       * levels survive the game's per-bind filter juggling) and the fog draw samples it through a sampler object
       * with trilinear filtering, which never touches the texture's own parameters.
       */
      private void bindNoiseSampler(int textureId) {
         if (textureId <= 0) {
            return;
         }
         if (this.sampler == 0) {
            this.sampler = GL33.glGenSamplers();
            GL33.glSamplerParameteri(this.sampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL33.glSamplerParameteri(this.sampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL33.glSamplerParameteri(this.sampler, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL33.glSamplerParameteri(this.sampler, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
         }
         if (textureId != this.mipTextureId) {
            while (GL11.glGetError() != 0) {
            }
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D); // the noise texture is bound on unit 0
            int err = GL11.glGetError();
            this.mipTextureId = textureId;
            this.mipsOk = err == 0;
            int format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            Log.info("fog pass: noise texture " + textureId + " (internal format 0x" + Integer.toHexString(format) + ") mipmaps "
               + (this.mipsOk ? "generated" : "refused (GL error 0x" + Integer.toHexString(err) + "), sampling level 0"));
         }
         if (this.mipsOk) {
            GL33.glBindSampler(0, this.sampler);
         }
      }

      private void upload(ByteBuffer rects, int numRects) {
         int bytes = numRects * 4 * STRIDE;
         if (this.staging == null || this.staging.capacity() < bytes) {
            this.staging = BufferUtils.createByteBuffer(Math.max(bytes, 64 * 1024));
         }
         ByteBuffer v = this.staging;
         v.clear();
         rects.rewind();
         for (int i = 0; i < numRects; i++) {
            float sx = rects.getFloat();
            float sy = rects.getFloat();
            float ex = rects.getFloat();
            float ey = rects.getFloat();
            rects.getFloat(); // u0, v0, u1, v1: the stock fog shader never reads its texture coordinates
            rects.getFloat();
            rects.getFloat();
            rects.getFloat();
            float depthTop = rects.getFloat();
            rects.getFloat();
            float depthBottom = rects.getFloat();
            rects.getFloat();
            float offset = rects.getFloat();
            float layerAlpha = rects.getFloat();
            rects.getInt(); // zLayer: the stock shader ignores scalingInfo.z
            float sideFrac = 32.0F * Core.tileScale / Math.max(1.0F, ex - sx); // the stock side fade width in rectangle units
            vertex(v, sx, sy, 0.0F, 0.0F, sideFrac, offset, depthTop, layerAlpha);
            vertex(v, ex, sy, 1.0F, 0.0F, sideFrac, offset, depthTop, layerAlpha);
            vertex(v, ex, ey, 1.0F, 1.0F, sideFrac, offset, depthBottom, layerAlpha);
            vertex(v, sx, ey, 0.0F, 1.0F, sideFrac, offset, depthBottom, layerAlpha);
         }
         v.flip();
         if (this.vbos[0] == 0) {
            GL15.glGenBuffers(this.vbos);
         }
         this.vbo = this.vbos[this.vboIndex = (this.vboIndex + 1) % this.vbos.length]; // a small ring so an upload never waits for the previous frame's draw
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
         GL15.glBufferData(GL15.GL_ARRAY_BUFFER, v, GL15.GL_STREAM_DRAW);
      }

      private static void vertex(ByteBuffer v, float x, float y, float px, float py, float side, float offset, float depth, float layerAlpha) {
         v.putFloat(x).putFloat(y).putFloat(0.0F).putFloat(px).putFloat(py).putFloat(side).putFloat(offset).putFloat(depth).putFloat(layerAlpha);
      }

      private void setMvp() {
         Core core = Core.getInstance();
         if (core.modelViewMatrixStack.isEmpty()) {
            this.mvp.identity();
         } else {
            this.mvp.set(core.projectionMatrixStack.peek()).mul(core.modelViewMatrixStack.peek());
         }
         this.mvp.get(this.mat);
         GL20.glUniformMatrix4fv(this.uni[10], false, this.mat);
      }

      /** The scene framebuffer's depth attachment when it is a 2D texture (see sceneDepthAsTexture), else 0. */
      private static int sceneDepthTextureOf(int sceneFbo) {
         if (sceneFbo == 0) {
            return 0;
         }
         int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
         if (type != GL11.GL_TEXTURE) {
            return 0;
         }
         return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
      }

      /** The internal format of the scene framebuffer's depth attachment: a depth blit needs the same one on both sides. */
      private static int sceneDepthFormat(int sceneFbo) {
         if (sceneFbo == 0) {
            return GL30.GL_DEPTH24_STENCIL8; // the window's PixelFormat(32, 0, 24, 8)
         }
         int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
         int name = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
         if (type == GL30.GL_RENDERBUFFER) {
            int previous = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, name);
            int format = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_INTERNAL_FORMAT);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, previous);
            return format;
         }
         if (type == GL11.GL_TEXTURE) {
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
            int format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
            return format;
         }
         return GL30.GL_DEPTH24_STENCIL8;
      }

      private static int colorTexture(int w, int h, int internalFormat, int format, int type) {
         int tex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, type, (ByteBuffer)null);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12_CLAMP_TO_EDGE);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12_CLAMP_TO_EDGE);
         return tex;
      }

      /** A depth texture in the scene's depth format (so the blit is format-compatible), nearest, clamped. */
      private int depthTexture(int w, int h) {
         int tex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
         if (this.depthStencil) {
            boolean f32 = this.depthFormat == GL30.GL_DEPTH32F_STENCIL8;
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, this.depthFormat, w, h, 0, GL30.GL_DEPTH_STENCIL,
               f32 ? GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV : GL30.GL_UNSIGNED_INT_24_8, (ByteBuffer)null);
         } else {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, this.depthFormat, w, h, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer)null);
         }
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12_CLAMP_TO_EDGE);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12_CLAMP_TO_EDGE);
         return tex;
      }

      private int fogDepthShared; // the scene's own depth texture attached to the fog buffer (100 % in place), else 0

      private boolean ensureBuffers(int fw, int fh, int sw, int sh, int sceneFboNow, int sharedDepth) {
         boolean fogOk = this.fogFbo != 0 && this.fogW == fw && this.fogH == fh && this.fogDepthShared == sharedDepth;
         boolean sceneOk = sw == 0 ? this.sceneFbo == 0 : this.sceneFbo != 0 && this.sceneW == sw && this.sceneH == sh;
         if (fogOk && sceneOk) {
            return true;
         }
         int previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
         int previousTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
         if (this.fogFbo == 0 && this.sceneFbo == 0) {
            this.depthFormat = sceneDepthFormat(sceneFboNow);
            this.depthStencil = this.depthFormat == GL30.GL_DEPTH24_STENCIL8 || this.depthFormat == GL30.GL_DEPTH32F_STENCIL8;
         }
         int attachment = this.depthStencil ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : GL30.GL_DEPTH_ATTACHMENT;
         boolean ok = true;
         if (!fogOk) {
            if (this.fogFbo != 0) {
               GL30.glDeleteFramebuffers(this.fogFbo);
               GL30.glDeleteFramebuffers(this.fogFarFbo);
               GL11.glDeleteTextures(this.fogColorTex);
               GL11.glDeleteTextures(this.fogMaxDepthTex);
               GL11.glDeleteTextures(this.fogFarTex);
               if (this.fogDepthShared == 0) {
                  GL11.glDeleteTextures(this.fogDepthTex);
               }
            }
            this.fogColorTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.fogColorTex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, fw, fh, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12_CLAMP_TO_EDGE);
            this.fogMaxDepthTex = colorTexture(fw, fh, GL30.GL_R32F, GL11.GL_RED, GL11.GL_FLOAT);
            this.fogFarTex = colorTexture(fw, fh, GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
            this.fogDepthTex = sharedDepth != 0 ? sharedDepth : this.depthTexture(fw, fh);
            this.fogDepthShared = sharedDepth;
            this.fogFbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fogFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, this.fogColorTex, 0);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, this.fogMaxDepthTex, 0);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, this.fogDepthTex, 0);
            GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            this.fogFarFbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fogFarFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, this.fogFarTex, 0);
            int status2 = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status == GL30.GL_FRAMEBUFFER_COMPLETE) {
               status = status2;
            }
            this.fogW = fw;
            this.fogH = fh;
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
               Log.warn("fog pass: fog buffer " + fw + "x" + fh + " incomplete (0x" + Integer.toHexString(status) + ")");
               ok = false;
            }
         }
         if (!sceneOk) {
            if (this.sceneFbo != 0) {
               GL30.glDeleteFramebuffers(this.sceneFbo);
               GL11.glDeleteTextures(this.sceneDepthTex);
               this.sceneFbo = 0;
            }
            if (sw > 0) {
               this.sceneDepthTex = this.depthTexture(sw, sh);
               this.sceneFbo = GL30.glGenFramebuffers();
               GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.sceneFbo);
               GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, this.sceneDepthTex, 0);
               GL11.glDrawBuffer(GL11.GL_NONE);
               GL11.glReadBuffer(GL11.GL_NONE);
               int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
               if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                  Log.warn("fog pass: scene depth copy " + sw + "x" + sh + " incomplete (0x" + Integer.toHexString(status) + ")");
                  ok = false;
               }
            }
            this.sceneW = sw;
            this.sceneH = sh;
         }
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTex);
         this.checks = 0;
         if (ok) {
            Log.info("fog pass: fog buffer " + fw + "x" + fh + " (" + scalePct() + " % of the viewport), depth format 0x" + Integer.toHexString(this.depthFormat)
               + (sw > 0 ? ", scene depth copy " + sw + "x" + sh : sharedDepth != 0 ? ", the scene's depth texture shared" : ", scene depth read in place"));
         }
         return ok;
      }

      private boolean initPrograms() {
         this.fogProgram = link(FOG_VERT, Config.DEV_FOG_FLAT ? FOG_FRAG_FLAT : FOG_FRAG, new String[] {"aPosition", "aRect", "aExtra"}, null);
         this.downProgram = link(QUAD_VERT, DOWN_FRAG, new String[] {"aPosition"}, new String[] {null, "maxDepth"});
         this.compositeProgram = link(QUAD_VERT, COMPOSITE_FRAG, new String[] {"aPosition"}, null);
         if (this.fogProgram == 0 || this.downProgram == 0 || this.compositeProgram == 0) {
            return false;
         }
         this.uni[0] = GL20.glGetUniformLocation(this.fogProgram, "screenInfo");
         this.uni[1] = GL20.glGetUniformLocation(this.fogProgram, "textureInfo");
         this.uni[2] = GL20.glGetUniformLocation(this.fogProgram, "worldOffset");
         this.uni[3] = GL20.glGetUniformLocation(this.fogProgram, "scalingInfo");
         this.uni[4] = GL20.glGetUniformLocation(this.fogProgram, "colorInfo");
         this.uni[5] = GL20.glGetUniformLocation(this.fogProgram, "paramInfo");
         this.uni[6] = GL20.glGetUniformLocation(this.fogProgram, "cameraInfo");
         this.uni[7] = GL20.glGetUniformLocation(this.fogProgram, "fogScale");
         this.uni[8] = GL20.glGetUniformLocation(this.fogProgram, "NoiseTexture");
         this.uni[9] = GL20.glGetUniformLocation(this.compositeProgram, "FogTexture");
         this.uni[10] = GL20.glGetUniformLocation(this.fogProgram, "ModelViewProjection");
         this.uni[11] = GL20.glGetUniformLocation(this.downProgram, "SceneDepth");
         this.uni[12] = GL20.glGetUniformLocation(this.downProgram, "blockInfo");
         this.uni[13] = GL20.glGetUniformLocation(this.compositeProgram, "FogDepth");
         this.uni[14] = GL20.glGetUniformLocation(this.compositeProgram, "SceneDepth");
         this.uni[15] = GL20.glGetUniformLocation(this.compositeProgram, "compInfo");
         this.uniCompSize = GL20.glGetUniformLocation(this.compositeProgram, "compSize");
         this.uniBlockOrigin = GL20.glGetUniformLocation(this.downProgram, "blockOrigin");
         this.uniDevView = GL20.glGetUniformLocation(this.compositeProgram, "devView");
         this.uniMaxDepth = GL20.glGetUniformLocation(this.fogProgram, "MaxDepth");
         this.uniFarMode = GL20.glGetUniformLocation(this.fogProgram, "farMode");
         this.uniCompMax = GL20.glGetUniformLocation(this.compositeProgram, "MaxDepth");
         this.uniCompFar = GL20.glGetUniformLocation(this.compositeProgram, "FogFar");
         this.drawBuffersMax = BufferUtils.createIntBuffer(2);
         this.drawBuffersMax.put(GL11.GL_NONE).put(GL30.GL_COLOR_ATTACHMENT1).flip();
         FloatBuffer quad = BufferUtils.createFloatBuffer(8);
         quad.put(new float[] {-1.0F, -1.0F, 1.0F, -1.0F, 1.0F, 1.0F, -1.0F, 1.0F}).flip();
         this.quadVbo = GL15.glGenBuffers();
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.quadVbo);
         GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quad, GL15.GL_STATIC_DRAW);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         return true;
      }

      private int uniCompSize;
      private int uniBlockOrigin;
      private int uniDevView;
      private int uniMaxDepth;
      private int uniFarMode;
      private int uniCompMax;
      private int uniCompFar;
      private java.nio.IntBuffer drawBuffersMax;
   }

   private static final int GL12_CLAMP_TO_EDGE = 33071;

   private static void fail(String why) {
      if (!fallback) {
         fallback = true;
         Log.warn("fog pass: " + why + "; the stock fog drawer runs from now on");
      }
   }

   private static int link(String vert, String frag, String[] attribs, String[] fragData) {
      int vs = compile(GL20.GL_VERTEX_SHADER, vert);
      int fs = compile(GL20.GL_FRAGMENT_SHADER, frag);
      if (vs == 0 || fs == 0) {
         return 0;
      }
      int program = GL20.glCreateProgram();
      GL20.glAttachShader(program, vs);
      GL20.glAttachShader(program, fs);
      for (int i = 0; i < attribs.length; i++) {
         GL20.glBindAttribLocation(program, i, attribs[i]);
      }
      if (fragData != null) {
         for (int i = 0; i < fragData.length; i++) {
            if (fragData[i] != null) {
               GL30.glBindFragDataLocation(program, i, fragData[i]);
            }
         }
      }
      GL20.glLinkProgram(program);
      GL20.glDeleteShader(vs);
      GL20.glDeleteShader(fs);
      if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
         Log.warn("fog pass: link failed: " + GL20.glGetProgramInfoLog(program, 4096));
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
         Log.warn("fog pass: shader compile failed: " + GL20.glGetShaderInfoLog(shader, 4096));
         GL20.glDeleteShader(shader);
         return 0;
      }
      return shader;
   }

   // ------------------------------------------------------------------------------------------------ shaders

   /** The rectangle depth comes from the vertex, so early depth rejection works (stock wrote gl_FragDepth). */
   private static final String FOG_VERT = String.join("\n",
      "#version 140",
      "in vec3 aPosition;",
      "in vec4 aRect;",
      "in vec2 aExtra;",
      "uniform mat4 ModelViewProjection;",
      "out vec4 vRect;",
      "out float vLayerAlpha;",
      "out float vDepth;",
      "void main() {",
      "   gl_Position = ModelViewProjection * vec4(aPosition, 1.0);",
      "   vDepth = clamp(aExtra.x, 0.0, 1.0);",
      "   gl_Position.z = (vDepth * 2.0 - 1.0) * gl_Position.w;",
      "   vRect = aRect;",
      "   vLayerAlpha = aExtra.y;",
      "}");

   /**
    * media/shaders/fog.frag with the per-rectangle uniforms read from the interpolated vertex attributes
    * (position in the rectangle, side fade width, row noise offset, layer alpha), gl_FragCoord mapped from the
    * fog buffer to viewport pixels, and no gl_FragDepth write. The noise and alpha maths are the stock ones.
    */
   private static final String FOG_FRAG = String.join("\n",
      "#version 140",
      "uniform sampler2D NoiseTexture;",
      "uniform vec4 screenInfo;",
      "uniform vec4 textureInfo;",
      "uniform vec4 scalingInfo;",
      "uniform vec4 colorInfo;",
      "uniform vec4 worldOffset;",
      "uniform vec4 paramInfo;",
      "uniform vec4 cameraInfo;",
      "uniform vec4 fogScale;",
      "uniform sampler2D MaxDepth;",
      "uniform int farMode;", // 1: no depth attachment, the test is against the block's farthest depth in MaxDepth
      "in vec4 vRect;",
      "in float vLayerAlpha;",
      "in float vDepth;",
      "out vec4 fragColor;",
      "float fogNoise(in vec2 fcoord) {",
      "   float scaling = 0.0008;",
      "   vec2 uv = vec2(fcoord.x*screenInfo.x*scaling, fcoord.y*screenInfo.y*scaling) + worldOffset.xy*scaling;",
      "   uv *= 0.25;",
      "   float scalingMod = 0.00035;",
      "   vec2 uv2 = vec2(uv.x+(scalingInfo.x * scalingMod), uv.y+(scalingInfo.y * scalingMod));",
      "   float value = 0.0;",
      "   float amplitude = 0.5;",
      "   float ampTot = amplitude;",
      "   vec4 tex1 = texture(NoiseTexture, uv2);",
      "   value += amplitude * tex1.r;",
      "   amplitude *= 0.5;",
      "   ampTot += amplitude;",
      "   scalingMod = 0.00070;",
      "   uv2 = vec2(uv.x+(scalingInfo.x * scalingMod), uv.y+(scalingInfo.y * scalingMod));",
      "   tex1 = texture(NoiseTexture, uv2);",
      "   value += amplitude * tex1.g;",
      "   amplitude *= 0.5;",
      "   ampTot += amplitude;",
      "   scalingMod = 0.00040;",
      "   uv2 = vec2(uv.x+(scalingInfo.x * scalingMod), uv.y+(scalingInfo.y * scalingMod));",
      "   uv2 *= 3.0;",
      "   tex1 = texture(NoiseTexture, uv2);",
      "   value += amplitude * tex1.b;",
      "   value = 0.5 + (0.50*(value/ampTot));",
      "   return clamp(value,0.0,1.0);",
      "}",
      "float alphaBorders(in vec2 p, in vec2 fcoord) {",
      "   float borderAlpha = 1.0;",
      "   float scaling = 0.00035;",
      "   vec2 uv = vec2(fcoord.x*screenInfo.x*scaling, fcoord.y*screenInfo.y*scaling) + worldOffset.xy*scaling;",
      "   uv.x += vRect.w;",
      "   uv.y += vRect.w;",
      "   vec4 tex1 = texture(NoiseTexture, uv);",
      "   float n = tex1.a;",
      "   tex1 = texture(NoiseTexture, uv+0.5);",
      "   float n2 = tex1.a;",
      "   float height = paramInfo.x-((paramInfo.x*0.5)*n);",
      "   borderAlpha = min(p.y/height, 1.0);",
      "   height = paramInfo.y-((paramInfo.y*0.5)*n2);",
      "   borderAlpha = min( max((1.0-p.y)/height, 0.0) , borderAlpha);",
      "   float sidesWidth = vRect.z;",
      "   borderAlpha = min(max((1.0-p.x)/sidesWidth, 0.0), borderAlpha);",
      "   borderAlpha = min(p.x/sidesWidth, borderAlpha);",
      "   scaling = 0.00085;",
      "   uv = vec2(fcoord.x*screenInfo.x*scaling, fcoord.y*screenInfo.y*scaling) + worldOffset.xy*scaling;",
      "   uv.x += vRect.w;",
      "   uv.y += vRect.w;",
      "   tex1 = texture(NoiseTexture, uv);",
      "   n = tex1.a;",
      "   borderAlpha += (1.0-borderAlpha) * (n*borderAlpha);",
      "   borderAlpha *= borderAlpha;",
      "   return clamp(borderAlpha,0.0,1.0);",
      "}",
      "float alphaCircle(in float alpha, in float rad, in vec2 coord, in float zoom) {",
      "   vec2 center = vec2(0.5, 0.5);",
      "   center.x -= (worldOffset.z)/screenInfo.x;",
      "   center.y += (worldOffset.w)/screenInfo.y;",
      "   float dist = distance(coord.xy, center);",
      "   float baseAlpha = smoothstep(0.01,0.99,dist*rad*zoom);",
      "   return alpha + ((1.0-alpha)*baseAlpha);",
      "}",
      "void main() {",
      "   if (farMode == 1 && vDepth >= texelFetch(MaxDepth, ivec2(gl_FragCoord.xy), 0).r) {",
      "      discard;",
      "   }",
      "   float zoom = screenInfo.z;",
      "   vec2 fc = gl_FragCoord.xy / fogScale.xy + fogScale.zw;",
      "   vec2 coord = (vec2(fc.x-cameraInfo.x, fc.y-cameraInfo.y) * zoom) / screenInfo.xy;",
      "   float alpha = alphaCircle(paramInfo.z, paramInfo.w, coord, zoom);",
      "   vec2 fcoord = vec2(fc.x/(screenInfo.x/zoom), 1.0 - fc.y/(screenInfo.y/zoom));",
      "   float layerAlpha = vLayerAlpha;",
      "   float borderAlpha = alphaBorders(vRect.xy, fcoord);",
      "   if(textureInfo.x>=1.0) {",
      "      float alp = textureInfo.z * alpha * borderAlpha * layerAlpha;",
      "      fragColor = vec4( 1.0, 1.0, 0.0, clamp(alp, 0.0, 1.0) );",
      "      return;",
      "   }",
      "   float n = fogNoise(fcoord);",
      "   float alp = max(0.0,(n-0.50)/0.50);",
      "   alp *= textureInfo.z * alpha * borderAlpha * layerAlpha;",
      "   alp = clamp(alp, 0.0, 1.0);",
      "   fragColor = vec4(n*colorInfo.r, n*colorInfo.g, n*colorInfo.b, alp);",
      "}");

   /** Measurement only (devFogFlat): the rectangle coverage without the noise. */
   private static final String FOG_FRAG_FLAT = String.join("\n",
      "#version 140",
      "uniform vec4 textureInfo;",
      "uniform vec4 colorInfo;",
      "uniform sampler2D MaxDepth;",
      "uniform int farMode;",
      "in vec4 vRect;",
      "in float vLayerAlpha;",
      "in float vDepth;",
      "out vec4 fragColor;",
      "void main() {",
      "   if (farMode == 1 && vDepth >= texelFetch(MaxDepth, ivec2(gl_FragCoord.xy), 0).r) {",
      "      discard;",
      "   }",
      "   float alp = clamp(textureInfo.z * 0.3 * vLayerAlpha, 0.0, 1.0);",
      "   fragColor = vec4(colorInfo.rgb, alp);",
      "}");

   private static final String QUAD_VERT = String.join("\n",
      "#version 140",
      "in vec2 aPosition;",
      "void main() {",
      "   gl_Position = vec4(aPosition, 0.0, 1.0);",
      "}");

   /** The nearest (smallest) scene depth of the block of screen pixels a fog texel stands for. */
   private static final String DOWN_FRAG = String.join("\n",
      "#version 140",
      "uniform sampler2D SceneDepth;",
      "uniform vec4 blockInfo;", // pixels per fog texel in x and y, the scene depth's last viewport texel x and y
      "uniform vec2 blockOrigin;", // the viewport origin in the scene depth texture
      "out float maxDepth;",
      "void main() {",
      "   ivec2 t = ivec2(gl_FragCoord.xy);",
      "   int x0 = int(blockOrigin.x) + int(floor(float(t.x) * blockInfo.x));",
      "   int y0 = int(blockOrigin.y) + int(floor(float(t.y) * blockInfo.y));",
      "   int x1 = max(x0 + 1, int(blockOrigin.x) + int(floor(float(t.x + 1) * blockInfo.x)));",
      "   int y1 = max(y0 + 1, int(blockOrigin.y) + int(floor(float(t.y + 1) * blockInfo.y)));",
      "   x1 = min(x1, x0 + 4);",
      "   y1 = min(y1, y0 + 4);",
      "   float d = 1.0;",
      "   float m = 0.0;",
      "   for (int y = y0; y < y1; y++) {",
      "      for (int x = x0; x < x1; x++) {",
      "         float s = texelFetch(SceneDepth, ivec2(min(x, int(blockInfo.z)), min(y, int(blockInfo.w))), 0).r;",
      "         d = min(d, s);",
      "         m = max(m, s);",
      "      }",
      "   }",
      "   gl_FragDepth = d;",
      "   maxDepth = m;",
      "}");

   /**
    * The fog buffer over the scene. Below 100 % every screen pixel blends the four nearest fog texels with bilinear
    * weights (floored so a neighbour can still win) divided by the distance between the texel's depth (the block's
    * nearest depth) and the pixel's own scene depth: the texel decided at this pixel's surface dominates.
    */
   private static final String COMPOSITE_FRAG = String.join("\n",
      "#version 140",
      "uniform sampler2D FogTexture;", // the rows in front of the block's nearest depth
      "uniform sampler2D FogDepth;", // that nearest depth
      "uniform sampler2D SceneDepth;",
      "uniform sampler2D MaxDepth;", // the block's farthest depth
      "uniform sampler2D FogFar;", // the rows in front of it
      "uniform vec4 compInfo;", // fog texels per screen pixel in x and y, viewport origin x and y
      "uniform vec4 compSize;", // fog buffer width and height, depth-aware flag, scene depth positioned at the viewport flag
      "uniform int devView;",
      "out vec4 fragColor;",
      "void main() {",
      "   vec2 p = gl_FragCoord.xy - compInfo.zw;",
      "   vec2 sp = compSize.w > 0.5 ? gl_FragCoord.xy : p;",
      "   if (devView > 0) {",
      "      float d = devView == 1 ? texelFetch(SceneDepth, ivec2(sp), 0).r : devView == 2 ? texelFetch(FogDepth, ivec2(p * compInfo.xy), 0).r : texelFetch(FogTexture, ivec2(p * compInfo.xy), 0).a;",
      "      float e = d * 255.0;",
      "      fragColor = vec4(floor(e) / 255.0, fract(e), 0.0, 1.0);",
      "      return;",
      "   }",
      "   if (compSize.z < 0.5) {",
      "      fragColor = texelFetch(FogTexture, ivec2(p * compInfo.xy), 0);",
      "      return;",
      "   }",
      "   vec2 f = p * compInfo.xy - 0.5;",
      "   vec2 i0 = floor(f);",
      "   vec2 fr = f - i0;",
      "   ivec2 maxi = ivec2(compSize.xy) - 1;",
      "   ivec2 a = clamp(ivec2(i0), ivec2(0), maxi);",
      "   ivec2 b = clamp(ivec2(i0) + ivec2(1, 0), ivec2(0), maxi);",
      "   ivec2 c = clamp(ivec2(i0) + ivec2(0, 1), ivec2(0), maxi);",
      "   ivec2 d = clamp(ivec2(i0) + ivec2(1, 1), ivec2(0), maxi);",
      "   vec4 w = vec4((1.0 - fr.x) * (1.0 - fr.y), fr.x * (1.0 - fr.y), (1.0 - fr.x) * fr.y, fr.x * fr.y);",
      "   vec4 dn = vec4(texelFetch(FogDepth, a, 0).r, texelFetch(FogDepth, b, 0).r, texelFetch(FogDepth, c, 0).r, texelFetch(FogDepth, d, 0).r);",
      "   vec4 df = vec4(texelFetch(MaxDepth, a, 0).r, texelFetch(MaxDepth, b, 0).r, texelFetch(MaxDepth, c, 0).r, texelFetch(MaxDepth, d, 0).r);",
      "   float dp = texelFetch(SceneDepth, ivec2(sp), 0).r;",
      // each texel's fog at this pixel's own depth: between the rows in front of the block's nearest depth and the
      // rows in front of its farthest, by where the pixel's depth lies in that range (the rows accumulate with depth)
      "   vec4 t = clamp((vec4(dp) - dn) / max(df - dn, vec4(0.00001)), 0.0, 1.0);",
      "   vec4 fa = mix(texelFetch(FogTexture, a, 0), texelFetch(FogFar, a, 0), t.x);",
      "   vec4 fb = mix(texelFetch(FogTexture, b, 0), texelFetch(FogFar, b, 0), t.y);",
      "   vec4 fc2 = mix(texelFetch(FogTexture, c, 0), texelFetch(FogFar, c, 0), t.z);",
      "   vec4 fd = mix(texelFetch(FogTexture, d, 0), texelFetch(FogFar, d, 0), t.w);",
      // texels whose depth range does not reach this pixel's depth were decided for other surfaces: they fade out
      "   vec4 outside = max(dn - vec4(dp), vec4(0.0)) + max(vec4(dp) - df, vec4(0.0));",
      "   w = max(w, vec4(0.05)) * (exp(-outside * (1.0 / 0.0005)) + vec4(0.001));",
      "   vec4 col = w.x * fa + w.y * fb + w.z * fc2 + w.w * fd;",
      "   fragColor = col / (w.x + w.y + w.z + w.w);",
      "}");
}
