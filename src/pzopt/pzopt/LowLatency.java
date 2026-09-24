package pzopt;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;

/**
 * Reflex-style latency control for the GL renderer (2026-09-24). NVIDIA Reflex itself is a D3D11 / D3D12 / Vulkan SDK;
 * there is none for OpenGL, so this does what it does with GL primitives:
 *
 * <ul>
 * <li>{@code gpuMaxFrames=N} (0 = off): after each swap the render thread fences the GPU stream and waits until at most
 * N frames are queued behind the GPU (the driver's "low latency mode" / {@code __GL_MaxFramesAllowed}); it serves
 * input-latch requests while it waits. Without it a GPU- or vsync-bound game runs ahead into the driver's queue.</li>
 * <li>{@code reflexSleep} (the Reflex just-in-time sleep): every frame the render thread measures how long the frame
 * queued before it could take it (the game thread's wait in the hand-off plus the time the frame sat in the ready
 * slot); a feedback controller turns that into a sleep the game thread takes before it reads its input, driving the
 * queue toward {@code reflexQueueUs}. Whatever the bottleneck (GPU, render thread, vsync flip), the frame is then
 * started as late as it can be and still arrives when the pipeline has room: the input it carries is that much
 * fresher, the throughput is the same.</li>
 * </ul>
 * Frame identity: push n (RenderThread.Ready) is acquire n on the render thread (one-slot hand-off, every pushed state
 * is rendered once).
 */
public final class LowLatency {
   public static final int GPU_MAX_FRAMES = Overrides.enabled() ? Config.GPU_MAX_FRAMES : 0;
   public static final boolean REFLEX = Config.REFLEX_SLEEP && Overrides.enabled();
   private static final long TARGET_NS = Config.REFLEX_QUEUE_US * 1000L;
   private static final long MAX_SLEEP_NS = 30_000_000L;
   private static final int RING = 16;

   private static final long[] fences = new long[RING];
   private static final long[] pushEndNs = new long[RING];
   private static final long[] pushWaitNs = new long[RING];
   private static long acquires; // render thread
   private static long pushes; // game thread
   private static volatile long sleepNs;
   private static volatile long drainNs;
   private static long lastSampleNs, capNs = -1L;

   private static long capIntervalNs() {
      if (capNs < 0L) {
         int fps = Config.REFLEX_CAP_FPS;
         if (fps < 0) {
            // auto: only with vsync, from the monitor's refresh
            int hz = org.lwjglx.opengl.Display.getDesktopDisplayMode().getFrequency();
            fps = org.lwjglx.opengl.Display.isVSyncEnabledPzopt() && hz > 0 ? (int)Math.floor(hz - hz * (double)hz / 3600.0) : 0;
            Log.info("reflex: auto cap " + (fps > 0 ? fps + " fps (" + hz + " Hz)" : "off"));
         }
         capNs = fps > 0 ? 1_000_000_000L / fps : 0L;
      }
      return capNs;
   }
   private static long drains;
   private static long waitNsSum, waits;
   private static long frameQueueNs = -1L, swapStartNs, minSwapNs = Long.MAX_VALUE / 2, slackN;
   private static final long[] slacks = new long[64];
   private static long lastFrameDoneNs;
   private static int gpuState; // 0 = queries not set up, 1 = on, -1 = none
   private static int[] queries;
   private static final long[] queryIssueNs = new long[RING];
   private static final long[] pendingSlackNs = new long[RING];
   private static long gpuOffsetNs, gpuCalibratedNs;
   private static double gpuQueueSum;
   private static double intervalNs = 16_000_000.0;
   private static double queueSum;
   private static long queueN, sleptSum, frames, lastLogNs;

   private LowLatency() {
   }

   // --- vblankLock: frame start locked to the display's vblank (GLX_NV_delay_before_swap) -----------------------

   public static final boolean VBLANK_LOCK = Config.VBLANK_LOCK && Overrides.enabled();
   private static int lockState; // 0 = undecided, 1 = on, -1 = unavailable
   private static long glxDisplay, glxDrawable;
   private static volatile long goSeq, goNs;
   private static long goConsumed;
   private static double lockWindowNs = 3_000_000.0; // W: go -> swap issued, p95 + margin
   private static final long[] lockSpans = new long[64];
   private static long lockSpanN, lockSwaps, lockLate;

   /** Render thread, lockStepRenderStep before it waits for the next game frame: wait for vblank - W, then release the game. */
   public static void beforeAcquire() {
      if (!VBLANK_LOCK || lockState < 0 || !org.lwjglx.opengl.Display.isVSyncEnabledPzopt()) {
         return;
      }
      if (lockState == 0) {
         try {
            glxDisplay = org.lwjgl.glfw.GLFWNativeX11.glfwGetX11Display();
            glxDrawable = org.lwjgl.glfw.GLFWNativeGLX.glfwGetGLXWindow(org.lwjglx.opengl.Display.getWindow());
            boolean ext = false;
            if (glxDisplay != 0L) {
               // GLFW's glfwExtensionSupported only sees the context's extension list; ask GLX itself
               ext = org.lwjgl.opengl.GL.createCapabilitiesGLX(glxDisplay).GLX_NV_delay_before_swap;
            }
            lockState = glxDisplay != 0L && glxDrawable != 0L && ext ? 1 : -1;
            Log.info("vblank lock: display " + (glxDisplay != 0L) + ", GLX drawable " + (glxDrawable != 0L) + ", GLX_NV_delay_before_swap " + ext);
         } catch (Throwable t) {
            lockState = -1;
            Log.info("vblank lock: " + t);
         }
         Log.info("vblank lock: " + (lockState > 0 ? "on (GLX_NV_delay_before_swap)" : "unavailable (needs GLX + GLX_NV_delay_before_swap)"));
         if (lockState < 0) {
            return;
         }
      }
      try {
         org.lwjgl.opengl.GLXNVDelayBeforeSwap.glXDelayBeforeSwapNV(glxDisplay, glxDrawable, (float)(lockWindowNs / 1e9));
      } catch (Throwable t) {
         lockState = -1;
         Log.warn("vblank lock: off after " + t);
         return;
      }
      goNs = System.nanoTime();
      goSeq++;
      InputLatch.renderIdle = true;
      Object m = zombie.core.SpriteRenderer.instance.states;
      synchronized (m) {
         m.notifyAll(); // the game thread may wait on the state monitor in its gate
      }
   }

   /** Render thread, right before the swap of a frame started by a go: how long go -> swap took, which sets W. */
   private static void lockSwap() {
      if (!VBLANK_LOCK || lockState <= 0) {
         return;
      }
      long span = System.nanoTime() - goNs;
      if (span <= 0L || span > 100_000_000L) {
         return;
      }
      lockSpans[(int)(lockSpanN++ % lockSpans.length)] = span;
      lockSwaps++;
      if (span > lockWindowNs) {
         lockLate++;
      }
      if (lockSpanN >= lockSpans.length && lockSpanN % 16 == 0) {
         long[] w = lockSpans.clone();
         java.util.Arrays.sort(w);
         long p95 = w[w.length * 95 / 100];
         double target = Math.max(1_000_000.0, Math.min(intervalNs - 500_000.0, p95 + Config.VBLANK_LOCK_MARGIN_US * 1000.0));
         lockWindowNs = lockWindowNs * 0.7 + target * 0.3;
      }
      if (lockSwaps % 1650 == 0) {
         Log.info(String.format("vblank lock: W %.2f ms, %d of %d frames past W", lockWindowNs / 1e6, lockLate, lockSwaps));
      }
   }

   private static void waitForGo() {
      long deadline = System.nanoTime() + (long)Math.min(40_000_000.0, intervalNs * 2.0);
      while (goSeq == goConsumed && System.nanoTime() < deadline && lockState > 0) {
         java.util.concurrent.locks.LockSupport.parkNanos(50_000L);
      }
      goConsumed = goSeq;
   }

   /** Game thread, InputLatch.beforeInputSwap first: the Reflex sleep (or the vblank lock's go) before input is read. */
   public static void beforeInputSample() {
      GpuBoost.frame(zombie.characters.IsoPlayer.players[0] != null);
      if (VBLANK_LOCK && lockState > 0 && org.lwjglx.opengl.Display.isVSyncEnabledPzopt() && zombie.characters.IsoPlayer.players[0] != null) {
         waitForGo();
         return;
      }
      if (!REFLEX) {
         return;
      }
      long now = System.nanoTime();
      long until = now + sleepNs + drainNs;
      drainNs = 0L;
      // reflexCapFps: never start frames faster than the cap (Reflex's auto cap with vsync: refresh - refresh^2/3600, so
      // the game can never outrun the display and re-fill the queue the sleep drained)
      long minInterval = capIntervalNs();
      if (minInterval > 0L && lastSampleNs != 0L) {
         until = Math.max(until, lastSampleNs + minInterval);
      }
      while (true) {
         long left = until - System.nanoTime();
         if (left <= 0L) {
            break;
         }
         if (left > 1_200_000L) {
            java.util.concurrent.locks.LockSupport.parkNanos(left - 1_000_000L);
         } else {
            Thread.onSpinWait();
         }
      }
      long t = System.nanoTime();
      lastSampleNs = t;
      sleptSum += t - now;
      frames++;
      if (t - lastLogNs > 10_000_000_000L) {
         if (lastLogNs != 0L && frames > 0) {
            Log.info(String.format("reflex: %d frames, slept %.3f ms/frame, slack %.3f ms/frame of which driver queue %.3f (p10 target %.2f), %d drains",
                  frames, sleptSum / 1e6 / frames, queueN > 0 ? queueSum / 1e6 / queueN : 0.0, queueN > 0 ? gpuQueueSum / 1e6 / queueN : 0.0, TARGET_NS / 1e6, drains));
         }
         lastLogNs = t;
         frames = sleptSum = 0;
         queueSum = 0.0;
         gpuQueueSum = 0.0;
         queueN = 0;
      }
   }

   /** Game thread, RenderThread.Ready after pushFrameDown; startNs = before the hand-off (its wait included). */
   public static void pushed(long startNs) {
      if (!REFLEX) {
         return;
      }
      long now = System.nanoTime();
      int slot = (int)(++pushes % RING);
      pushWaitNs[slot] = now - startNs;
      pushEndNs[slot] = now;
   }

   /** Render thread, right after a state was acquired: how long this frame queued, fed to the sleep controller. */
   public static void frameBegin() {
      InputLatch.renderIdle = false;
      if (!REFLEX && GPU_MAX_FRAMES <= 0) {
         return;
      }
      acquires++;
      if (!REFLEX) {
         return;
      }
      int slot = (int)(acquires % RING);
      long end = pushEndNs[slot];
      if (end == 0L) {
         return;
      }
      long q = System.nanoTime() - end + pushWaitNs[slot];
      pushEndNs[slot] = 0L;
      frameQueueNs = q >= 0L && q < 200_000_000L ? q : -1L;
      gpuStartQuery(slot);
   }

   /**
    * The driver queue: a GL_TIMESTAMP query issued before the frame's commands executes when the GPU reaches them; the
    * gap between issuing and reaching is the time the frame waited behind earlier frames (a vsync-blocked swap, a busy
    * GPU) without the CPU seeing it, since the swap returns while the driver still has room.
    */
   private static void gpuStartQuery(int slot) {
      if (gpuState < 0) {
         return;
      }
      if (gpuState == 0) {
         if (!GL.getCapabilities().OpenGL33 && !GL.getCapabilities().GL_ARB_timer_query) {
            gpuState = -1;
            return;
         }
         queries = new int[RING];
         GL15.glGenQueries(queries);
         gpuState = 1;
      }
      long now = System.nanoTime();
      if (now - gpuCalibratedNs > 1_000_000_000L) {
         long a = System.nanoTime();
         long g = GL32.glGetInteger64(GL33.GL_TIMESTAMP);
         long b = System.nanoTime();
         gpuOffsetNs = a + (b - a) / 2 - g;
         gpuCalibratedNs = now;
      }
      if (queryIssueNs[slot] != 0L) {
         resolveGpu(slot, true);
      }
      GL33.glQueryCounter(queries[slot], GL33.GL_TIMESTAMP);
      queryIssueNs[slot] = System.nanoTime();
      pendingSlackNs[slot] = -1L;
   }

   private static void resolveGpu(int slot, boolean wait) {
      if (pendingSlackNs[slot] < 0L) {
         if (wait) {
            queryIssueNs[slot] = 0L; // the frame never finished (no frameDone): drop it
         }
         return;
      }
      if (!wait && GL15.glGetQueryObjecti(queries[slot], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
         return;
      }
      long reached = GL33.glGetQueryObjecti64(queries[slot], GL15.GL_QUERY_RESULT) + gpuOffsetNs;
      long gpuQueue = Math.max(0L, reached - queryIssueNs[slot] - 100_000L);
      if (gpuQueue > 100_000_000L) {
         gpuQueue = 0L; // a calibration jump, not a queue
      }
      gpuQueueSum += gpuQueue;
      addSlack(pendingSlackNs[slot] + gpuQueue);
      queryIssueNs[slot] = 0L;
      pendingSlackNs[slot] = -1L;
   }

   /** Render thread, Display.update just before the buffer swap. */
   public static void beforeSwap() {
      lockSwap();
      swapStartNs = System.nanoTime();
   }

   /**
    * Render thread, after the swap and the frames-in-flight wait: the frame's slack (hand-off queue + the time the swap
    * and the fence blocked beyond their non-blocking minimum). Every 16 frames the sleep moves so that the 10th
    * percentile of the last 64 slacks sits at the target: nine frames in ten keep a margin, so a frame rarely misses
    * its flip (a lost refresh with vsync, a GPU bubble without), while the typical frame arrives just in time.
    */
   private static void frameDone(long swapNs, long fenceNs) {
      if (!REFLEX || frameQueueNs < 0L) {
         return;
      }
      minSwapNs = Math.min((long)(minSwapNs * 1.001) + 1L, swapNs); // slowly forgets, so a changed driver path is relearnt
      // the fence wait (gpuMaxFrames) is not slack: it lasts until the flip whatever the game does; its effect shows as
      // the next frame's queue. The swap block is: without the fence, a vsync swap blocks until a buffer frees up
      long slack = frameQueueNs + Math.max(0L, swapNs - minSwapNs);
      long now = System.nanoTime();
      if (lastFrameDoneNs != 0L) {
         long iv = now - lastFrameDoneNs;
         if (iv > 0L && iv < 200_000_000L) {
            intervalNs = intervalNs * 0.95 + iv * 0.05;
         }
      }
      lastFrameDoneNs = now;
      int slot = (int)(acquires % RING);
      if (gpuState > 0 && queryIssueNs[slot] != 0L) {
         pendingSlackNs[slot] = slack; // completed by resolveGpu with the driver queue a few frames later
         for (int i = 0; i < RING; i++) {
            if (i != slot && queryIssueNs[i] != 0L) {
               resolveGpu(i, false);
            }
         }
      } else {
         addSlack(slack);
      }
   }

   private static void addSlack(long slack) {
      slacks[(int)(slackN++ % slacks.length)] = slack;
      queueSum += slack;
      queueN++;
      if (slackN >= slacks.length && slackN % 16 == 0) {
         long[] w = slacks.clone();
         java.util.Arrays.sort(w);
         long p10 = w[w.length / 10];
         long s = sleepNs + (p10 - TARGET_NS) / 2;
         // throughput guard: a sleep near a whole frame interval would cost frames, never allow it
         long cap = Math.min(MAX_SLEEP_NS, (long)(intervalNs * 0.8));
         sleepNs = Math.max(0L, Math.min(cap, s));
         // a stuffed pipeline (vsync: one extra frame queued, the swap blocking a whole refresh) keeps its extra frame
         // whatever the per-frame sleep below one interval is: drain it once by sleeping one whole interval
         if (p10 > intervalNs * 0.9 && sleepNs >= cap * 0.95 && drainNs == 0L && org.lwjglx.opengl.Display.isVSyncEnabledPzopt()) {
            drainNs = (long)intervalNs;
            drains++;
         }
      }
   }

   /** The swap interval for vsync on: 1, or -1 (adaptive) with vsyncAdaptive when the driver supports it. */
   public static int vsyncInterval() {
      if (!Config.VSYNC_ADAPTIVE || !Overrides.enabled()) {
         return 1;
      }
      boolean tear = org.lwjgl.glfw.GLFW.glfwExtensionSupported("GLX_EXT_swap_control_tear")
            || org.lwjgl.glfw.GLFW.glfwExtensionSupported("WGL_EXT_swap_control_tear");
      Log.info("vsync: adaptive " + (tear ? "on (swap interval -1)" : "unavailable (no swap_control_tear), interval 1"));
      return tear ? -1 : 1;
   }

   /** Render thread, Display.update right after the swap: the frames-in-flight limit. */
   public static void afterSwap() {
      long swapNs = System.nanoTime() - swapStartNs;
      if (GPU_MAX_FRAMES <= 0) {
         frameDone(swapNs, 0L);
         return;
      }
      int slot = (int)(acquires % RING);
      if (fences[slot] != 0L) {
         GL32.glDeleteSync(fences[slot]);
      }
      fences[slot] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
      int waitSlot = (int)((acquires - (GPU_MAX_FRAMES - 1) + RING * 4L) % RING);
      long f = fences[waitSlot];
      if (f == 0L) {
         frameDone(swapNs, 0L);
         return;
      }
      long t0 = System.nanoTime();
      // wait in slices and serve input-latch requests in between: this thread is the one that pumps OS events
      int r = GL32.glClientWaitSync(f, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 200_000L);
      while (r == GL32.GL_TIMEOUT_EXPIRED && System.nanoTime() - t0 < 50_000_000L) {
         InputLatch.renderIdle = true; // waiting on the GPU: latch requests are served here
         InputLatch.serve(true);
         r = GL32.glClientWaitSync(f, 0, 200_000L);
      }
      InputLatch.renderIdle = false;
      long fenceNs = System.nanoTime() - t0;
      waitNsSum += fenceNs;
      frameDone(swapNs, Math.max(0L, fenceNs - 100_000L));
      waits++;
      if (r == GL32.GL_WAIT_FAILED) {
         Log.warn("frames in flight: fence wait failed");
      }
      if (waits == 2400) {
         Log.info(String.format("frames in flight: max %d, fence wait %.3f ms/frame", GPU_MAX_FRAMES, waitNsSum / 1e6 / waits));
         waits = 0;
         waitNsSum = 0L;
      }
   }
}
