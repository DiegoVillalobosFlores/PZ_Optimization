package pzopt;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjglx.BufferUtils;
import zombie.core.Core;
import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.Shader;
import zombie.core.opengl.ShaderProgram;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoPuddles;
import zombie.iso.PuddlesShader;

/**
 * Render-thread side of the puddle cache (Config key {@code puddleVbo}, needs {@code puddleCache}).
 *
 * <p>With {@code puddleCache} alone the game thread still copies every on-screen batch into IsoPuddles'
 * RenderData each frame and patches lights, jiggle and depth per vertex (~3.6k squares, 12 % of a
 * thunderstorm frame on the laptop), and the render thread streams the whole block through the 64 KB
 * ring buffer in ~7 map/draw cycles. Here every batch owns a GL buffer that is uploaded when the batch
 * is (re)built, when one of its squares' lights changed (LightingJNI tells {@link PuddleCache}) or when
 * the camera crossed a chunk edge (the depth of every vertex shifts by one constant); the camera's
 * sub-pixel jiggle, the only input that changes every frame, is a translation folded into the
 * ModelViewProjection uniform instead of being added to every vertex. Same vertex bytes, same shader
 * and uniforms as {@code ModelManager.RenderPuddles} / {@code IsoPuddles.renderSome}, same blend and
 * depth state, one draw per chunk level.
 *
 * <p>Threading: a {@link Frame} is filled on the game thread (one per level with puddles) and handed to
 * the render thread through {@code SpriteRenderer.drawGeneric}; its items carry an immutable snapshot
 * of the batch data when an upload is due, so the game thread may keep patching the batch. The GL
 * buffer id lives in the batch and is only touched by the render thread.
 */
public final class PuddleVbo {
   /** Floats per square (4 vertices x 8), the IsoPuddles.RenderData layout. */
   static final int FLOATS = PuddleCache.FLOATS;
   private static final int STRIDE = 32;
   private static final int MAX_SQUARES = 64; // one chunk level

   /** One batch to draw this frame; {@code upload} is non-null when its buffer must be refilled first. */
   static final class Item {
      PuddleCache.Batch batch;
      float[] upload;
      int count;
   }

   /** The generic draw command for one player and level. */
   static final class Frame extends TextureDraw.GenericDrawer {
      int playerIndex;
      int z;
      float jx;
      float jy;
      final ArrayList<Item> items = new ArrayList<>();

      @Override
      public void render() {
         Gl.draw(this);
      }

      @Override
      public void postRender() {
         for (int i = 0; i < this.items.size(); i++) {
            Item it = this.items.get(i);
            if (it.upload != null) {
               uploadPool.offer(it.upload);
               it.upload = null;
            }
            it.batch = null;
            itemPool.offer(it);
         }
         this.items.clear();
         framePool.offer(this);
      }
   }

   private static final ConcurrentLinkedQueue<Frame> framePool = new ConcurrentLinkedQueue<>();
   private static final ConcurrentLinkedQueue<Item> itemPool = new ConcurrentLinkedQueue<>();
   private static final ConcurrentLinkedQueue<float[]> uploadPool = new ConcurrentLinkedQueue<>();
   private static long uploads;
   private static long uploadFloats;
   private static long draws;
   private static long frames;

   public static boolean enabled() {
      return PuddleCache.enabled() && Config.PUDDLE_VBO;
   }

   static Frame begin(int playerIndex, int z, float jx, float jy) {
      Frame f = framePool.poll();
      if (f == null) {
         f = new Frame();
      }
      f.playerIndex = playerIndex;
      f.z = z;
      f.jx = jx;
      f.jy = jy;
      return f;
   }

   /** Adds a batch; {@code snapshot} true copies its data for an upload before the draw. */
   static void add(Frame f, PuddleCache.Batch b, boolean snapshot) {
      Item it = itemPool.poll();
      if (it == null) {
         it = new Item();
      }
      it.batch = b;
      // a chunk level holds at most MAX_SQUARES squares, which is all the index buffer and the upload arrays cover;
      // a longer batch (duplicate entries in the level's square list, 2026-09-24) draws its first MAX_SQUARES, as
      // Gl.draw always did, instead of throwing out of the world pass
      it.count = Math.min(b.count, MAX_SQUARES);
      if (snapshot) {
         float[] u = uploadPool.poll();
         if (u == null || u.length < MAX_SQUARES * FLOATS) {
            u = new float[MAX_SQUARES * FLOATS];
         }
         System.arraycopy(b.data, 0, u, 0, it.count * FLOATS);
         it.upload = u;
      } else {
         it.upload = null;
      }
      f.items.add(it);
   }

   /** Submits the frame; returns it when it went to the render thread (empty frames are dropped). */
   static Frame submit(Frame f) {
      if (f.items.isEmpty()) {
         framePool.offer(f);
         return null;
      }
      frames++;
      SpriteRenderer.instance.drawGeneric(f);
      return f;
   }

   /** Render-thread state: the shared index buffer, the staging buffer and the draw. */
   static final class Gl {
      private static int ebo;
      private static FloatBuffer staging;
      private static final Matrix4f mvp = new Matrix4f();
      private static void ensureBuffers() {
         if (ebo == 0) {
            ebo = GL15.glGenBuffers();
            ShortBuffer idx = BufferUtils.createShortBuffer(MAX_SQUARES * 6);
            for (int s = 0; s < MAX_SQUARES; s++) {
               int v = s * 4;
               idx.put((short)v).put((short)(v + 1)).put((short)(v + 2)).put((short)v).put((short)(v + 2)).put((short)(v + 3));
            }
            idx.flip();
            GL15.glBindBuffer(34963, ebo);
            GL15.glBufferData(34963, idx, 35044); // GL_STATIC_DRAW
            staging = BufferUtils.createFloatBuffer(MAX_SQUARES * FLOATS);
         }
      }

      static void draw(Frame f) {
         IsoPuddles puddles = IsoPuddles.getInstance();
         if (puddles.effect == null) {
            return;
         }
         ensureBuffers();
         // the state setup of ModelManager.RenderPuddles
         GL11.glPushClientAttrib(-1);
         GL11.glPushAttrib(1048575);
         Matrix4f projection = Core.getInstance().projectionMatrixStack.alloc();
         puddles.puddlesProjection(projection);
         Core.getInstance().projectionMatrixStack.push(projection);
         Matrix4f modelView = Core.getInstance().modelViewMatrixStack.alloc();
         modelView.identity();
         Core.getInstance().modelViewMatrixStack.push(modelView);
         int shaderID = puddles.effect.getID();
         ShaderHelper.glUseProgramObjectARB(shaderID);
         Shader shader = puddles.effect;
         if (shader instanceof PuddlesShader puddlesShader) {
            puddlesShader.updatePuddlesParams(f.playerIndex, f.z);
         }
         ShaderProgram program = shader.getProgram();
         VertexBufferObject.setModelViewProjection(program); // sets ModelViewProjection = projection x modelView
         // the jiggle stock adds to every vertex, as a translation of the same matrix
         mvp.set(projection).mul(modelView).translate(f.jx, f.jy, 0.0F);
         program.setValue("ModelViewProjection", mvp);

         // the state of IsoPuddles.renderSome
         GL15.glBindBuffer(34963, ebo);
         GL20.glEnableVertexAttribArray(0);
         GL20.glEnableVertexAttribArray(1);
         GL20.glEnableVertexAttribArray(2);
         GL20.glEnableVertexAttribArray(3);
         GL20.glEnableVertexAttribArray(4);
         GL20.glEnableVertexAttribArray(5);
         GL20.glEnableVertexAttribArray(6);
         GL11.glDepthMask(false);
         GL11.glBlendFunc(770, 771);
         GL11.glEnable(2929);
         GL11.glDepthFunc(515);
         if (Config.PUDDLE_EARLY_Z) {
            // the depth attribute is relative to the camera's chunk and goes below 0 for nearer chunks; stock's
            // gl_FragDepth write clamps it per fragment, the vertex path would clip those quads: depth clamping
            // gives the fixed pipeline the same "interpolate, then clamp to the range" (GL_DEPTH_CLAMP, GL 3.2)
            GL11.glEnable(0x864F);
         }

         for (int i = 0; i < f.items.size(); i++) {
            Item it = f.items.get(i);
            PuddleCache.Batch b = it.batch;
            int n = Math.min(it.count, MAX_SQUARES);
            if (n <= 0) {
               continue;
            }
            if (b.vbo == 0) {
               b.vbo = GL15.glGenBuffers();
            }
            GL15.glBindBuffer(34962, b.vbo);
            if (it.upload != null) {
               staging.clear();
               staging.put(it.upload, 0, n * FLOATS);
               staging.flip();
               GL15.glBufferData(34962, staging, 35048); // GL_DYNAMIC_DRAW
               uploads++;
               uploadFloats += n * FLOATS;
            }
            GL20.glVertexAttribPointer(2, 1, 5126, true, STRIDE, 0L);
            GL20.glVertexAttribPointer(3, 1, 5126, true, STRIDE, 4L);
            GL20.glVertexAttribPointer(4, 1, 5126, true, STRIDE, 8L);
            GL20.glVertexAttribPointer(5, 1, 5126, true, STRIDE, 12L);
            GL20.glVertexAttribPointer(0, 2, 5126, false, STRIDE, 16L);
            GL20.glVertexAttribPointer(1, 4, 5121, true, STRIDE, 24L);
            GL20.glVertexAttribPointer(6, 1, 5126, true, STRIDE, 28L);
            GL12.glDrawRangeElements(4, 0, n * 4, n * 6, 5123, 0L);
            draws++;
         }

         GL20.glDisableVertexAttribArray(4);
         GL20.glDisableVertexAttribArray(5);
         GL20.glDisableVertexAttribArray(6);
         GL11.glDisable(0x864F);
         GL15.glBindBuffer(34962, 0);
         GL15.glBindBuffer(34963, 0);
         SpriteRenderer.ringBuffer.restoreVbos = true;
         Core.getInstance().projectionMatrixStack.pop();
         Core.getInstance().modelViewMatrixStack.pop();
         ShaderHelper.glUseProgramObjectARB(0);
         GL11.glPopAttrib();
         GL11.glPopClientAttrib();
         Texture.lastTextureID = -1;
         GLStateRenderThread.restore();
      }

   }

   public static String stats() {
      return "puddle vbo: frames=" + frames + " draws=" + draws + " uploads=" + uploads + " (" + uploadFloats * 4L / 1024L + " KB)";
   }
}
