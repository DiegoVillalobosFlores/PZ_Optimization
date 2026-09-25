package zombie.core.VBO;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.ARBMapBufferRange;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjglx.opengl.OpenGLException;
import zombie.core.skinnedmodel.model.VertexBufferObject;

public class GLVertexBufferObject {
   public static IGLBufferObject funcs;
   private long size;
   private final int type;
   private final int usage;
   private transient int id;
   private transient boolean mapped;
   private transient boolean cleared;
   private transient ByteBuffer buffer;
   private int vertexAttribArray = -1;
   // pzopt: persistent mapping (GL_ARB_buffer_storage) for the fixed-size sprite ring buffers
   private boolean pzoptPersistent;
   // pzopt: K storage slots per buffer object (Config.PERSISTENT_VBO_SLOTS); each map() rotates to the next slot and
   // re-binds it, so the reuse distance of a 128-buffer ring becomes 128*K batches. slot 0 keeps the original id.
   private int[] pzoptSlotIds;
   private ByteBuffer[] pzoptSlotBuffers;
   private long[] pzoptSlotFences;
   private long[] pzoptSlotUnmapFrame;
   private int pzoptSlot;
   private int pzoptUnmappedSlot;
   private static final java.util.ArrayList<GLVertexBufferObject> pzoptUnmappedSinceFence = new java.util.ArrayList<>();
   private static boolean pzoptLogged;
   private static long pzoptWaits, pzoptWaitNs, pzoptStalls;
   // pzopt: frame fences — a buffer unmapped in frame F is rewritten only after the GPU finished frame F
   // (the per-batch fence alone left chunk floors black: bs-vbo-1, 2026-09-20)
   private static final int PZOPT_FRAME_RING = 8;
   private static final long[] pzoptFrameFences = new long[PZOPT_FRAME_RING];
   private static long pzoptFrame = 1; // frame counter; index = frame % ring
   private static long pzoptFrameDone; // newest frame whose fence has been seen signalled
   private static long pzoptMaps, pzoptMapsThisFrame, pzoptMaxMapsPerFrame, pzoptFrameWaits, pzoptFrameWaitNs, pzoptSameFrameReuse;
   private static long pzoptLastLogNs;
   // MAP_WRITE | MAP_PERSISTENT (| MAP_COHERENT); without the coherent bit the map adds MAP_FLUSH_EXPLICIT and unmap()
   // flushes the written range (glFlushMappedBufferRange), the spec'd way to publish client writes to the GPU
   private static final int PZOPT_STORAGE_FLAGS = 0x0002 | 0x0040 | (pzopt.Config.PERSISTENT_VBO_COHERENT ? 0x0080 : 0);
   private static final int PZOPT_MAP_FLAGS = 0x0002 | 0x0040 | (pzopt.Config.PERSISTENT_VBO_COHERENT ? 0x0080 : 0x0010);

   static {
      pzopt.Overrides.onClassLoaded("zombie.core.VBO.GLVertexBufferObject");
   }

   private static boolean pzoptUsePersistent() {
      return pzopt.Config.PERSISTENT_VBO && pzopt.Overrides.enabled() && GL.getCapabilities().GL_ARB_buffer_storage && GL.getCapabilities().OpenGL32;
   }

   /**
    * Fence the draws issued from the buffers unmapped since the last fence (a vertex/index pair is unmapped together
    * and drawn together); called when the next buffer is mapped, i.e. after those draws were issued.
    */
   private static void pzoptFencePrevious() {
      java.util.ArrayList<GLVertexBufferObject> pending = pzoptUnmappedSinceFence;
      for (int i = 0; i < pending.size(); i++) {
         GLVertexBufferObject prev = pending.get(i);
         int slot = prev.pzoptUnmappedSlot;
         if (prev.pzoptSlotFences[slot] != 0L) {
            org.lwjgl.opengl.GL32.glDeleteSync(prev.pzoptSlotFences[slot]);
         }
         prev.pzoptSlotFences[slot] = org.lwjgl.opengl.GL32.glFenceSync(0x9117, 0); // SYNC_GPU_COMMANDS_COMPLETE
      }
      pending.clear();
   }

   /**
    * Called by the RenderThread override once per frame after SpriteRenderer.postRender(): every draw of the
    * frame has been issued, so one fence here covers all reads of the buffers unmapped during the frame.
    */
   public static void pzoptFrameEnd() {
      if (!pzopt.Config.PERSISTENT_VBO || !pzopt.Config.PERSISTENT_VBO_FRAME_FENCE && !pzopt.Config.PERSISTENT_VBO_FRAME_SYNC) { // pzopt: frameSync needs the frame fences too
         return;
      }
      int i = (int)(pzoptFrame % PZOPT_FRAME_RING);
      if (pzoptFrameFences[i] != 0L) {
         org.lwjgl.opengl.GL32.glDeleteSync(pzoptFrameFences[i]);
      }
      pzoptFrameFences[i] = org.lwjgl.opengl.GL32.glFenceSync(0x9117, 0);
      if (pzoptMapsThisFrame > pzoptMaxMapsPerFrame) {
         pzoptMaxMapsPerFrame = pzoptMapsThisFrame;
      }
      pzoptMapsThisFrame = 0;
      pzoptFrame++;
      long now = System.nanoTime();
      if (pzopt.Config.INSTRUMENT && now - pzoptLastLogNs > 5_000_000_000L) {
         if (pzoptLastLogNs != 0L) {
            pzopt.Log.info("persistent VBO: maps=" + pzoptMaps + " max/frame=" + pzoptMaxMapsPerFrame + " batch waits=" + pzoptWaits + " (" + pzoptWaitNs / 1_000_000 + " ms) stalls=" + pzoptStalls
                  + " frame waits=" + pzoptFrameWaits + " (" + pzoptFrameWaitNs / 1_000_000 + " ms) same-frame reuse=" + pzoptSameFrameReuse);
         }
         pzoptLastLogNs = now;
         pzoptMaps = pzoptMaxMapsPerFrame = pzoptWaits = pzoptWaitNs = pzoptStalls = pzoptFrameWaits = pzoptFrameWaitNs = pzoptSameFrameReuse = 0L;
      }
   }

   /** Wait until the GPU has finished the frame this buffer was last used in (no-op when that frame is already known done). */
   private void pzoptWaitFrame(long f) {
      if (f == 0L) {
         return;
      }
      if (f >= pzoptFrame) {
         pzoptSameFrameReuse++; // the ring wrapped inside the current frame: only the per-batch fence protects it
         return;
      }
      f = Math.min(f + pzopt.Config.PERSISTENT_VBO_FRAME_LAG, pzoptFrame - 1); // diagnostic lag: also wait for the following frames
      if (f <= pzoptFrameDone) {
         return;
      }
      if (pzoptFrame - f > PZOPT_FRAME_RING - 1) {
         return; // fence already recycled: that frame is many frames old
      }
      long sync = pzoptFrameFences[(int)(f % PZOPT_FRAME_RING)];
      if (sync == 0L) {
         return;
      }
      long t0 = System.nanoTime();
      int r = org.lwjgl.opengl.GL32.glClientWaitSync(sync, 0x0001, 1_000_000_000L);
      long dt = System.nanoTime() - t0;
      if (r == 0x911B || r == 0x911D) {
         pzopt.Log.warn("persistent VBO: frame fence wait returned 0x" + Integer.toHexString(r));
         return;
      }
      pzoptFrameDone = f; // fences signal in order: every older frame is done too
      if (dt > 20_000L) {
         pzoptFrameWaits++;
         pzoptFrameWaitNs += dt;
      }
   }

   private ByteBuffer pzoptMapPersistent() {
      if (!pzopt.Config.PERSISTENT_VBO_FRAME_SYNC) { // pzopt: frameSync fences whole frames instead of every batch
         pzoptFencePrevious();
      }
      pzoptMaps++;
      pzoptMapsThisFrame++;
      if (this.buffer == null) {
         // first map: allocate the slots (the caller has bound this.id; slot 0 keeps it) and leave slot 0 bound
         int k = Math.max(1, pzopt.Config.PERSISTENT_VBO_SLOTS);
         this.pzoptSlotIds = new int[k];
         this.pzoptSlotBuffers = new ByteBuffer[k];
         this.pzoptSlotFences = new long[k];
         this.pzoptSlotUnmapFrame = new long[k];
         for (int i = 0; i < k; i++) {
            int id = i == 0 ? this.id : funcs.glGenBuffers();
            funcs.glBindBuffer(this.type, id);
            org.lwjgl.opengl.GL44.glBufferStorage(this.type, this.size, PZOPT_STORAGE_FLAGS);
            ByteBuffer b = GL30.glMapBufferRange(this.type, 0L, this.size, PZOPT_MAP_FLAGS, null);
            if (b == null) {
               throw new OpenGLException("Failed to persistently map a buffer " + this.size + " bytes long");
            }
            this.pzoptSlotIds[i] = id;
            this.pzoptSlotBuffers[i] = b;
         }
         this.pzoptSlot = 0;
         this.id = this.pzoptSlotIds[0];
         funcs.glBindBuffer(this.type, this.id);
         this.buffer = this.pzoptSlotBuffers[0];
         this.pzoptPersistent = true;
         this.cleared = true; // immutable storage: never glBufferData again
         if (!pzoptLogged) {
            pzoptLogged = true;
            pzopt.Log.info("persistent VBO mapping active (GL_ARB_buffer_storage), " + k + " slot(s) per buffer");
         }
      } else {
         if (this.pzoptSlotIds.length > 1) {
            this.pzoptSlot = (this.pzoptSlot + 1) % this.pzoptSlotIds.length;
            this.id = this.pzoptSlotIds[this.pzoptSlot];
            funcs.glBindBuffer(this.type, this.id); // the caller bound the previous slot; the draws must see this one
            this.buffer = this.pzoptSlotBuffers[this.pzoptSlot];
         }
         if (pzopt.Config.PERSISTENT_VBO_FRAME_FENCE && !pzopt.Config.PERSISTENT_VBO_FRAME_SYNC) {
            this.pzoptWaitFrame(this.pzoptSlotUnmapFrame[this.pzoptSlot]);
         }
         if (pzopt.Config.PERSISTENT_VBO_FRAME_SYNC) {
            // pzopt: persistentVboFrameSync (2026-09-24). A fence per 64 KB batch meant a glFenceSync + glClientWaitSync per
            // map, and with NVIDIA's threaded driver every wait is a round trip to the driver thread even when the fence has
            // long signalled (37 % of the render thread in late frames on the Rosewood drive). Here a slot last drawn in an
            // earlier frame waits for that frame's fence only when it is not yet known done (one wait covers every older
            // frame); a slot drawn earlier in this very frame (the ring wrapped inside one frame) waits for a fence set now,
            // behind the draws already issued from it.
            // A slot drawn persistentVboTrustFrames or more frames ago needs no call at all: the driver blocks the swap
            // while 2-3 frames are queued behind the GPU (the swap-chain limit), so that frame is long finished.
            long f = this.pzoptSlotUnmapFrame[this.pzoptSlot];
            if (f != 0L && f > pzoptFrameDone && pzoptFrame - f < pzopt.Config.PERSISTENT_VBO_TRUST_FRAMES) {
               if (f >= pzoptFrame) {
                  pzoptSameFrameReuse++;
                  long t0 = System.nanoTime();
                  long sync = org.lwjgl.opengl.GL32.glFenceSync(0x9117, 0);
                  org.lwjgl.opengl.GL32.glClientWaitSync(sync, 0x0001, 1_000_000_000L);
                  org.lwjgl.opengl.GL32.glDeleteSync(sync);
                  pzoptFrameWaits++;
                  pzoptFrameWaitNs += System.nanoTime() - t0;
               } else {
                  this.pzoptWaitFrame(f);
               }
            }
         }
      }
      long fence = this.pzoptSlotFences[this.pzoptSlot];
      if (fence != 0L) {
         // the GPU may still be reading the batch written into this slot 128*K batches ago
         long t0 = System.nanoTime();
         int r = org.lwjgl.opengl.GL32.glClientWaitSync(fence, 0x0001, 1_000_000_000L); // SYNC_FLUSH_COMMANDS_BIT, 1 s
         org.lwjgl.opengl.GL32.glDeleteSync(fence);
         this.pzoptSlotFences[this.pzoptSlot] = 0L;
         // 0x911A ALREADY_SIGNALED, 0x911C CONDITION_SATISFIED (had to wait: the GPU was 128 batches behind), 0x911B TIMEOUT_EXPIRED, 0x911D WAIT_FAILED
         if (r == 0x911B || r == 0x911D) {
            pzopt.Log.warn("persistent VBO: fence wait returned 0x" + Integer.toHexString(r));
         }
         if (r == 0x911C) {
            pzoptStalls++;
         }
         long dt = System.nanoTime() - t0;
         if (dt > 20_000L) {
            pzoptWaits++;
            pzoptWaitNs += dt;
         }
      }
      if (pzopt.Config.PERSISTENT_VBO_DELAY_US > 0) {
         java.util.concurrent.locks.LockSupport.parkNanos(pzopt.Config.PERSISTENT_VBO_DELAY_US * 1000L); // pzopt: diagnostic, CPU-only
      }
      if (pzopt.Config.PERSISTENT_VBO_FINISH) {
         GL11.glFinish(); // pzopt: diagnostic — rules a GPU read-after-overwrite race in or out
      }
      this.buffer.order(ByteOrder.nativeOrder()).clear().limit((int)this.size);
      this.mapped = true;
      return this.buffer;
   }

   /** Number of map() calls that waited more than 20 us on the GPU, and their total time (dev counters). */
   public static long pzoptFenceWaits() {
      return pzoptWaits;
   }

   public static long pzoptFenceWaitNs() {
      return pzoptWaitNs;
   }

   /** map() calls whose fence had not signalled yet (the GPU was still reading the batch from 128 batches ago). */
   public static long pzoptFenceStalls() {
      return pzoptStalls;
   }

   public static void init() {
      if (GL.getCapabilities().OpenGL15) {
         System.out.println("OpenGL 1.5 buffer objects supported");
         funcs = new GLBufferObject15();
      } else {
         if (!GL.getCapabilities().GL_ARB_vertex_buffer_object) {
            throw new RuntimeException("Neither OpenGL 1.5 nor GL_ARB_vertex_buffer_object supported");
         }

         System.out.println("GL_ARB_vertex_buffer_object supported");
         funcs = new GLBufferObjectARB();
      }

      VertexBufferObject.funcs = funcs;
   }

   public GLVertexBufferObject(long size, int type, int usage) {
      this.size = size;
      this.type = type;
      this.usage = usage;
   }

   public GLVertexBufferObject(int type, int usage) {
      this.size = 0L;
      this.type = type;
      this.usage = usage;
   }

   public void create() {
      this.id = funcs.glGenBuffers();
   }

   public void clear() {
      if (this.pzoptPersistent) {
         return;
      }
      if (!this.cleared) {
         funcs.glBufferData(this.type, this.size, this.usage);
         this.cleared = true;
      }
   }

   protected void doDestroy() {
      if (this.id != 0) {
         this.unmap();
         if (this.pzoptPersistent) {
            for (int i = 0; i < this.pzoptSlotIds.length; i++) {
               funcs.glBindBuffer(this.type, this.pzoptSlotIds[i]);
               funcs.glUnmapBuffer(this.type);
               if (this.pzoptSlotFences[i] != 0L) {
                  org.lwjgl.opengl.GL32.glDeleteSync(this.pzoptSlotFences[i]);
                  this.pzoptSlotFences[i] = 0L;
               }
               if (i > 0) {
                  funcs.glDeleteBuffers(this.pzoptSlotIds[i]);
               }
            }
            this.id = this.pzoptSlotIds[0]; // deleted below with the stock path
            this.pzoptPersistent = false;
            this.buffer = null;
            pzoptUnmappedSinceFence.remove(this);
         }
         funcs.glDeleteBuffers(this.id);
         this.id = 0;
      }
   }

   public ByteBuffer map(int size) {
      if (!this.mapped) {
         if (this.size != size) {
            this.size = size;
            this.clear();
         }

         if (this.buffer != null && this.buffer.capacity() < size) {
            this.buffer = null;
         }

         ByteBuffer old = this.buffer;
         if (GL.getCapabilities().OpenGL30) {
            int flags = 38;
            this.buffer = GL30.glMapBufferRange(this.type, 0L, size, 38, this.buffer);
         } else if (GL.getCapabilities().GL_ARB_map_buffer_range) {
            int flags = 38;
            this.buffer = ARBMapBufferRange.glMapBufferRange(this.type, 0L, size, 38, this.buffer);
         } else {
            this.buffer = funcs.glMapBuffer(this.type, funcs.GL_WRITE_ONLY(), size, this.buffer);
         }

         if (this.buffer == null) {
            throw new OpenGLException("Failed to map buffer " + this);
         }

         if (this.buffer != old && old != null) {
         }

         this.buffer.order(ByteOrder.nativeOrder()).clear().limit(size);
         this.mapped = true;
         this.cleared = false;
      }

      return this.buffer;
   }

   public ByteBuffer map() {
      if (!this.mapped) {
         assert this.size > 0L;
         if (this.pzoptPersistent || (this.buffer == null && pzoptUsePersistent())) {
            return this.pzoptMapPersistent();
         }
         this.clear();
         ByteBuffer old = this.buffer;
         if (GL.getCapabilities().OpenGL30) {
            int flags = 38;
            this.buffer = GL30.glMapBufferRange(this.type, 0L, this.size, 38, this.buffer);
         } else if (GL.getCapabilities().GL_ARB_map_buffer_range) {
            int flags = 38;
            this.buffer = ARBMapBufferRange.glMapBufferRange(this.type, 0L, this.size, 38, this.buffer);
         } else {
            this.buffer = funcs.glMapBuffer(this.type, funcs.GL_WRITE_ONLY(), this.size, this.buffer);
         }

         if (this.buffer == null) {
            throw new OpenGLException("Failed to map a buffer " + this.size + " bytes long");
         }

         if (this.buffer != old && old != null) {
         }

         this.buffer.order(ByteOrder.nativeOrder()).clear().limit((int)this.size);
         this.mapped = true;
         this.cleared = false;
      }

      return this.buffer;
   }

   public void orphan() {
      funcs.glMapBuffer(this.type, this.usage, this.size, null);
   }

   public boolean unmap() {
      if (this.mapped) {
         this.mapped = false;
         if (this.pzoptPersistent) {
            if (!pzopt.Config.PERSISTENT_VBO_COHERENT) {
               funcs.glBindBuffer(this.type, this.id);
               GL30.glFlushMappedBufferRange(this.type, 0L, this.size); // publish the batch (MAP_FLUSH_EXPLICIT)
            }
            if (!pzopt.Config.PERSISTENT_VBO_FRAME_SYNC) { // pzopt: frameSync, the frame fence covers them
               pzoptUnmappedSinceFence.add(this); // the draws from this buffer follow; fenced at the next map()
            } // pzopt
            this.pzoptUnmappedSlot = this.pzoptSlot;
            this.pzoptSlotUnmapFrame[this.pzoptSlot] = pzoptFrame; // and covered by this frame's fence (pzoptFrameEnd)
            return true;
         }
         return funcs.glUnmapBuffer(this.type);
      } else {
         return true;
      }
   }

   public boolean isMapped() {
      return this.mapped;
   }

   public void bufferData(ByteBuffer data) {
      funcs.glBufferData(this.type, data, this.usage);
   }

   @Override
   public String toString() {
      return "GLVertexBufferObject[" + this.id + ", " + this.size + "]";
   }

   public void bind() {
      funcs.glBindBuffer(this.type, this.id);
   }

   public void bindNone() {
      funcs.glBindBuffer(this.type, 0);
   }

   public int getID() {
      return this.id;
   }

   public void enableVertexAttribArray(int index) {
      if (this.vertexAttribArray != index) {
         this.disableVertexAttribArray();
         if (index >= 0) {
            GL20.glEnableVertexAttribArray(index);
         }

         this.vertexAttribArray = index >= 0 ? index : -1;
      }
   }

   public void disableVertexAttribArray() {
      if (this.vertexAttribArray != -1) {
         GL20.glDisableVertexAttribArray(this.vertexAttribArray);
         this.vertexAttribArray = -1;
      }
   }
}
