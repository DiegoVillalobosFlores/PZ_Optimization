package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLCapabilities;
import zombie.ZomboidFileSystem;

/**
 * Frame pacing for variable refresh rate displays.
 *
 * On a VRR display every frame is shown the moment it is ready, so the present cadence is the display cadence.
 * The frame limiter (GameWindow.mainThreadStep) paces the start of the game-thread step, i.e. the game time each
 * frame shows; the frame then reaches the screen after its own update + hand-off + render + GPU time, which varies
 * frame to frame. Motion on screen is smooth when the gap between two flips equals the game-time step between the
 * two frames. On a fixed-refresh display that variation hides behind the vsync grid; a VRR display shows it as is
 * (desktop, 100 fps cap, VRR on: steps 10.00 ms apart with 0.26 ms jitter, flips 3.1 ms off their step on average,
 * 54 % of frames off by more than 2 ms; harness/pacing.py "ON-SCREEN JUDDER").
 *
 * {@link #beforeSwap()} holds the swap until {@code step start + L}, L being a high percentile
 * ({@code presentPacingPct}) of how long recent frames took from step start to "ready":
 * <ul>
 * <li>{@code cpu}: ready = the render thread reached the swap. The GPU may still be drawing, so the flip then waits
 * for it: the flip lands 0.5-8 ms after the swap.</li>
 * <li>{@code gpu}: ready = the GPU finished the frame, read back a few frames later from a GL_TIMESTAMP query mapped
 * onto System.nanoTime; the render thread never blocks on the GPU. An on-time frame is complete when its swap is
 * issued, so it flips a fixed ~0.45 ms later.</li>
 * <li>{@code gpufinish}: the render thread waits for the frame's fence, then holds. Exact, but the render thread no
 * longer overlaps the GPU: at 100 fps it fell to 84 fps and the queue fed back into L (34 ms step-to-screen).</li>
 * </ul>
 * Frames later than L go as soon as ready. The hold never makes the render thread spend more than one cap interval
 * on a frame (it would fall behind the game thread and frames would queue), and there is no hold when uncapped.
 *
 * The same hooks log one row per presented frame to Zomboid/pzopt-pacing.out ({@code pacingLog}, on in instrumented
 * runs; harness/pacing.py): epoch_ms, step start, render-thread acquire, swap call, swap return, the hold and the
 * frame's GPU completion (System.nanoTime; rows are written {@link #LAG_ROWS} frames late so the GPU time is known).
 *
 * Threads: {@link #stepStart} and {@link #onPush} on the game thread, the rest on the render thread. Every pushed
 * frame is rendered (pushFrameDown waits for the ready slot), so a single-producer single-consumer ring of step
 * starts pairs each rendered frame with the game time it was built from.
 */
public final class Pacing {
   public static final boolean LOG = Config.PACING_LOG && Overrides.enabled();
   static final int OFF = 0, CPU = 1, GPU = 2, GPU_FINISH = 3, AUTO = 4;
   public static final int MODE = !Overrides.enabled() ? OFF : switch (Config.PRESENT_PACING.toLowerCase(Locale.ROOT)) {
      case "cpu" -> CPU;
      case "gpu", "true", "on" -> GPU;
      case "gpufinish" -> GPU_FINISH;
      case "auto" -> AUTO;
      default -> OFF;
   };
   private static final String[] NAMES = {"off", "cpu", "gpu", "gpufinish", "auto"};
   private static final boolean ACTIVE = LOG || MODE != OFF;

   private static final int RING = 64;
   private static final long[] ring = new long[RING];
   private static volatile int head; // written by the game thread
   private static volatile int tail; // written by the render thread

   private static volatile long stepStartNs;
   /** Game thread: the limiter's frame interval in ns, 0 when uncapped. */
   private static volatile long capIntervalNs;

   // --- render thread -------------------------------------------------------------------------
   /** The configured mode after the GL checks (AUTO stays AUTO); {@link #effectiveMode()} resolves AUTO per frame. */
   private static int mode = MODE;
   /** What AUTO means on this GL: gpu with timestamp queries, else cpu. */
   private static int autoMode = GPU;
   private static long frameSimNs;
   private static long acquireNs;
   private static long swapCallNs;
   private static long waitNs;
   private static long frameNo;

   private static final int WINDOW = 120;
   private static final long[] lagWin = new long[WINDOW];
   private static final long[] sorted = new long[WINDOW];
   private static int lagCount;
   private static int lagPos;
   private static long targetLagNs;
   private static int framesSinceTarget;

   // GPU completion: one GL_TIMESTAMP query per frame, read back when available
   private static final int QUERIES = 8;
   private static int[] queryIds;
   private static final long[] queryFrame = new long[QUERIES];
   private static final long[] querySim = new long[QUERIES];
   private static boolean timestamps;
   private static boolean glChecked;
   private static long gpuToCpuNs;
   private static long lastCalibrationNs;

   // log rows wait LAG_ROWS frames for their GPU completion time
   static final int LAG_ROWS = 10;
   private static final long[][] rows = new long[16][9];
   private static BufferedWriter log;
   private static boolean logFailed;
   private static long logStartNs;
   private static long logStartEpochMs;
   private static long lastFlushNs;
   private static final StringBuilder line = new StringBuilder(128);

   private Pacing() {
   }

   /** Game thread, at the start of a frame step (the time the frame's game state is built for). */
   public static void stepStart(long nowNs, long intervalNs) {
      stepStartNs = nowNs;
      capIntervalNs = intervalNs;
   }

   /** Game thread, right before SpriteRenderer.pushFrameDown: the frame being pushed shows this step's time. */
   public static void onPush() {
      if (!ACTIVE) {
         return;
      }
      int h = head;
      if (h - tail >= RING) {
         return; // render thread not consuming (no render state yet): drop
      }
      ring[h & (RING - 1)] = stepStartNs;
      head = h + 1;
   }

   /** Render thread, after acquireStateForRendering returned a frame. */
   public static void onAcquire() {
      if (!ACTIVE) {
         return;
      }
      acquireNs = System.nanoTime();
      int t = tail;
      if (t != head) {
         frameSimNs = ring[t & (RING - 1)];
         tail = t + 1;
      } else {
         frameSimNs = stepStartNs;
      }
   }

   /** Render thread, right before Display.update (the swap): optionally hold the present, then stamp the call. */
   public static void beforeSwap() {
      if (!ACTIVE) {
         return;
      }
      if (!glChecked) {
         initGl();
      }
      long now = System.nanoTime();
      waitNs = 0L;
      long frame = frameNo++;
      long interval = capIntervalNs;
      int m = effectiveMode();
      if (m != lastMode) {
         lagCount = 0;
         targetLagNs = 0L;
         lastMode = m;
      }
      if (timestamps) {
         collectQueries(now);
         issueQuery(frame);
      }
      if (m == GPU_FINISH && interval > 0L) {
         long sync = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
         GL32.glClientWaitSync(sync, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 100_000_000L);
         GL32.glDeleteSync(sync);
         now = System.nanoTime();
      } else if (m != OFF && interval > 0L) {
         GL11.glFlush(); // the frame's commands reach the GPU now, not at the swap after the hold
      }
      if (m != OFF && frameSimNs != 0L && interval > 0L) {
         if (m == CPU || m == GPU_FINISH) {
            addLag(now - frameSimNs);
         }
         if (lagCount > 0) {
            if (++framesSinceTarget >= 15 || targetLagNs == 0L) {
               framesSinceTarget = 0;
               System.arraycopy(lagWin, 0, sorted, 0, lagCount);
               Arrays.sort(sorted, 0, lagCount);
               int idx = Math.min(lagCount - 1, lagCount * Config.PRESENT_PACING_PCT / 100);
               targetLagNs = sorted[idx] + Config.PRESENT_PACING_MARGIN_US * 1000L;
            }
            long target = frameSimNs + targetLagNs;
            // the render thread must be ready for the next frame within one interval of taking this one
            long guard = acquireNs + interval - 300_000L;
            if (target > guard) {
               target = guard;
            }
            if (target > now) {
               waitUntil(target);
               long after = System.nanoTime();
               waitNs = after - now;
               now = after;
            }
         }
      } else if (interval == 0L) {
         lagCount = 0;
         targetLagNs = 0L;
      }
      swapCallNs = now;
   }

   private static int lastMode = -1;

   /** Render thread: the frame number the next afterSwap logs (for on-glass feedback matched later). */
   public static long currentFrame() {
      return frameNo - 1;
   }

   /** The limiter's current frame interval (0 = uncapped). */
   public static long capIntervalNs() {
      return capIntervalNs;
   }

   /** Render thread: frame f reached the screen at shownNs (System.nanoTime base); fills the log row while it is pending. */
   public static void onGlass(long f, long shownNs) {
      long[] row = rows[(int)(f & 15)];
      if (row[7] == f && f > frameNo - 1 - LAG_ROWS) {
         row[8] = shownNs;
      }
   }

   /** The pacing mode for this frame: AUTO follows pzopt.Vrr. */
   static int effectiveMode() {
      return mode == AUTO ? (Vrr.active() && !MacPresent.active() ? autoMode : OFF) : mode;
   }

   public static String describeMode() {
      return mode == AUTO ? "auto (" + NAMES[Vrr.active() ? autoMode : OFF] + ")" : NAMES[mode];
   }

   private static void addLag(long lag) {
      if (lag <= 0L || lag > 250_000_000L) {
         return;
      }
      lagWin[lagPos] = lag;
      lagPos = (lagPos + 1) % WINDOW;
      lagCount = Math.min(WINDOW, lagCount + 1);
   }

   private static void initGl() {
      glChecked = true;
      try {
         GLCapabilities caps = GL.getCapabilities();
         timestamps = caps.OpenGL33 || caps.GL_ARB_timer_query;
         if (mode == GPU_FINISH && !(caps.OpenGL32 || caps.GL_ARB_sync)) {
            mode = CPU;
         }
         if (timestamps) {
            queryIds = new int[QUERIES];
            GL15.glGenQueries(queryIds);
            Arrays.fill(queryFrame, -1L);
            calibrate(System.nanoTime());
         } else {
            autoMode = CPU;
            if (mode == GPU) {
               mode = CPU;
            }
         }
         if (MODE != OFF) {
            Log.info("pacing: presentPacing=" + Config.PRESENT_PACING + " -> " + NAMES[mode] + (mode == AUTO ? " (" + NAMES[autoMode] + " while VRR is active)" : "")
               + " (GL timestamps " + (timestamps ? "yes" : "no") + ", p" + Config.PRESENT_PACING_PCT + " + " + Config.PRESENT_PACING_MARGIN_US + " us)");
         }
      } catch (Throwable t) {
         timestamps = false;
         autoMode = CPU;
         if (mode == GPU || mode == GPU_FINISH) {
            mode = CPU;
         }
         Log.warn("pacing: GL queries unavailable: " + t);
      }
   }

   /** GPU clock -> System.nanoTime offset; GL_TIMESTAMP read synchronously returns the GPU's current time. */
   private static void calibrate(long now) {
      long before = System.nanoTime();
      long gpu = GL32.glGetInteger64(GL33.GL_TIMESTAMP);
      long after = System.nanoTime();
      gpuToCpuNs = (before + after) / 2L - gpu;
      lastCalibrationNs = now;
   }

   private static void issueQuery(long frame) {
      int slot = (int)(frame % QUERIES);
      if (queryFrame[slot] != -1L) {
         return; // still pending from QUERIES frames ago (GPU far behind): skip this frame
      }
      GL33.glQueryCounter(queryIds[slot], GL33.GL_TIMESTAMP);
      queryFrame[slot] = frame;
      querySim[slot] = frameSimNs;
   }

   private static void collectQueries(long now) {
      if (now - lastCalibrationNs > 1_000_000_000L) {
         calibrate(now);
      }
      for (int i = 0; i < QUERIES; i++) {
         long f = queryFrame[i];
         if (f == -1L || GL15.glGetQueryObjecti(queryIds[i], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
            continue;
         }
         long done = GL33.glGetQueryObjecti64(queryIds[i], GL15.GL_QUERY_RESULT) + gpuToCpuNs;
         queryFrame[i] = -1L;
         if (effectiveMode() == GPU && querySim[i] != 0L) {
            addLag(done - querySim[i]);
         }
         long[] row = rows[(int)(f & 15)];
         if (row[7] == f) {
            row[6] = done;
         }
      }
   }

   /** Render thread, after Display.update returned. */
   public static void afterSwap() {
      if (!LOG) {
         return;
      }
      long ret = System.nanoTime();
      long f = frameNo - 1;
      long[] row = rows[(int)(f & 15)];
      row[0] = ret;
      row[1] = frameSimNs;
      row[2] = acquireNs;
      row[3] = swapCallNs;
      row[4] = ret;
      row[5] = waitNs;
      row[6] = 0L;
      row[7] = f;
      row[8] = 0L;
      if (f >= LAG_ROWS) {
         write(rows[(int)((f - LAG_ROWS) & 15)]);
      }
   }

   private static void write(long[] row) {
      if (logFailed) {
         return;
      }
      try {
         if (log == null) {
            String dir = ZomboidFileSystem.instance.getCacheDir();
            if (dir == null) {
               return;
            }
            File file = new File(dir, "pzopt-pacing.out");
            log = new BufferedWriter(new FileWriter(file, false), 1 << 16);
            logStartNs = System.nanoTime();
            logStartEpochMs = System.currentTimeMillis();
            log.write("# epoch_ms sim_ns acquire_ns swap_call_ns swap_ret_ns wait_ns gpu_done_ns shown_ns (System.nanoTime; shown = on-glass time where the platform reports it (macOS Metal bridge); presentPacing="
               + Config.PRESENT_PACING + " mode=" + NAMES[mode] + ")\n");
            Log.info("pacing: logging frames to " + file);
         }
         StringBuilder b = line;
         b.setLength(0);
         b.append(logStartEpochMs + (row[4] - logStartNs) / 1_000_000L);
         for (int i = 1; i <= 6; i++) {
            b.append(' ').append(row[i]);
         }
         b.append(' ').append(row[8]);
         b.append('\n');
         log.write(b.toString());
         if (row[4] - lastFlushNs > 1_000_000_000L) {
            log.flush();
            lastFlushNs = row[4];
         }
      } catch (IOException | RuntimeException e) {
         logFailed = true;
         Log.warn("pacing: log stopped: " + e);
      }
   }

   public static void flushLog() {
      BufferedWriter w = log;
      if (w != null) {
         try {
            w.flush();
         } catch (IOException ignored) {
         }
      }
   }

   /**
    * limiterSleep: parks until limiterSpinUs before the limiter's next step and returns; the stock limiter loop spins the
    * rest, so a late wake costs nothing but the spin it replaces.
    */
   public static void limiterWait(long stepNs) {
      if (!slackSet) {
         slackSet = true;
         timerSlack1ns();
      }
      long left = stepNs - System.nanoTime() - Config.LIMITER_SPIN_US * 1000L;
      if (left > 20_000L) {
         LockSupport.parkNanos(left);
      }
   }

   private static boolean slackSet; // game thread only

   /**
    * Linux: the calling thread's timer slack to 1 ns (default 50 us), so a park ends when asked; prctl(PR_SET_TIMERSLACK)
    * only touches the calling thread.
    */
   static void timerSlack1ns() {
      if (!new java.io.File("/proc/self/task").isDirectory()) {
         return;
      }
      try {
         java.lang.foreign.Linker l = java.lang.foreign.Linker.nativeLinker();
         java.lang.invoke.MethodHandle prctl = l.downcallHandle(l.defaultLookup().find("prctl").orElseThrow(),
               java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_INT,
                     java.lang.foreign.ValueLayout.JAVA_LONG), java.lang.foreign.Linker.Option.firstVariadicArg(1));
         int r = (int) prctl.invokeExact(29, 1L); // PR_SET_TIMERSLACK
         if (r != 0) {
            Log.warn("limiterSleep: prctl(PR_SET_TIMERSLACK) = " + r);
         }
      } catch (Throwable t) {
         Log.warn("limiterSleep: no timer slack change (" + t + ")");
      }
   }

   /** Sleeps to ~1 ms before the deadline (park is coarse), then spins the rest. */
   public static void waitUntil(long deadlineNs) {
      while (true) {
         long left = deadlineNs - System.nanoTime();
         if (left <= 0L) {
            return;
         }
         if (left > 1_200_000L) {
            LockSupport.parkNanos(left - 1_000_000L);
         } else {
            Thread.onSpinWait();
         }
      }
   }
}
