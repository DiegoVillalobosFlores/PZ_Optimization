package pzopt;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.Locale;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/**
 * The GPU clock on AMD APUs and GPUs under Linux ({@code gpuPstate}, 2026-09-24, the E-core pass).
 *
 * <p>At a frame cap the amdgpu firmware races the GPU: on the flip's Radeon 890M the frame's ~3.7 ms of GPU work ran at
 * 2.7-2.9 GHz (the top of the voltage curve), then the GPU idled ~55 % of every frame, and the GFX rail drew ~20 W of the
 * socket's 29. The kernel lets any client of the render node pin a "stable pstate" on its own context
 * ({@code AMDGPU_CTX_OP_SET_STABLE_PSTATE}, the ioctl RGP captures use; no root): standard (a fixed ~1 GHz on Strix
 * Point), min_sclk (~640 MHz), min_mclk (the lowest memory clock too), peak. The kernel drops it back to automatic when
 * the context is closed, so a crash or an exit can never leave the GPU pinned.
 *
 * <p>Modes: off | auto | standard | min_sclk | min_mclk | peak. {@code auto} holds the lowest level of the ladder
 * min_sclk -> standard -> automatic whose GPU time per frame fits the frame cap: the frame's GPU time comes from two
 * GL_TIMESTAMP queries around the render thread's sprite replay (read a few frames late, no stall); a level whose p90
 * passes {@code gpuPstateFitPct} of the frame interval steps up one rung, and the governor steps back down when the time
 * measured last at the lower rung (else this one's, scaled by half the clock ratio: the work is mostly memory-bound)
 * fits again, with a back-off that doubles each time a lower rung did not hold. min_mclk (the lowest memory clock too)
 * is never picked by auto: it halved the frame rate on the flip. Uncapped frames and the loading screen run automatic.
 * The level is device-wide (the compositor draws at it too) while the game runs.
 */
public final class GpuPstate {
   public static final String MODE = Config.GPU_PSTATE.toLowerCase(Locale.ROOT);
   private static final int NONE = 0, STANDARD = 1, MIN_SCLK = 2, MIN_MCLK = 3, PEAK = 4;
   private static final long IOCTL_AMDGPU_CTX = 0xC0106442L; // DRM_IOWR(DRM_COMMAND_BASE + DRM_AMDGPU_CTX, union drm_amdgpu_ctx)

   private static MethodHandle ioctl;
   private static int fd = -1, ctxId;
   private static boolean broken, started;
   private static int level = -1; // the level set now
   private static final Arena ARENA = Arena.ofAuto();
   private static MemorySegment buf;

   // GPU time per frame from two GL_TIMESTAMP queries per frame (render thread only)
   private static final int QUERIES = 8;
   private static int[] ids;
   private static final long[] issued = new long[QUERIES];
   private static int next, active = -1;
   private static final float[] gpuMs = new float[256];
   private static int gpuCount;

   // governor state (render thread)
   private static long windowStart, holdUntil, backoffNs = 5_000_000_000L;
   private static final float[] learnedMs = new float[5]; // p90 GPU ms last measured at each level
   private static int budgetFps = 60;
   private static boolean uncapped, inWorld;
   public static long levelMs[] = new long[5];
   private static long lastTick;
   public static int changes;

   private GpuPstate() {
   }

   public static boolean enabled() {
      return !MODE.equals("off") && Overrides.enabled() && !broken;
   }

   /** Game thread, per step (FrameCap.stepDone): the cap in force and whether a world is on screen. */
   public static void onStep(boolean world, boolean isUncapped, int capFps) {
      inWorld = world;
      uncapped = isUncapped;
      budgetFps = Math.max(1, capFps);
   }

   // ------------------------------------------------------------------ render thread hooks (RenderThread.lockStepRenderStep)

   /** Before the sprite replay: the frame's first timestamp; starts the pstate context on the first call. */
   public static void gpuBegin() {
      if (!enabled()) {
         return;
      }
      try {
         if (!started) {
            start();
            if (broken) {
               return;
            }
         }
         collect();
         int q = next;
         if (issued[q] != 0L) {
            active = -1;
            return; // every pair still in flight
         }
         GL33.glQueryCounter(ids[2 * q], GL33.GL_TIMESTAMP);
         active = q;
      } catch (Throwable t) {
         fail("timer queries: " + t);
      }
   }

   /** After the sprite replay, before the swap: the frame's last timestamp, then the governor. */
   public static void gpuEnd() {
      if (!enabled() || active < 0) {
         return;
      }
      try {
         GL33.glQueryCounter(ids[2 * active + 1], GL33.GL_TIMESTAMP);
         issued[active] = 1L;
         next = (active + 1) % QUERIES;
         active = -1;
         govern();
      } catch (Throwable t) {
         fail("timer queries: " + t);
      }
   }

   private static void collect() {
      for (int q = 0; q < QUERIES; q++) {
         if (issued[q] == 0L || GL15.glGetQueryObjecti(ids[2 * q + 1], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
            continue;
         }
         long t0 = GL33.glGetQueryObjecti64(ids[2 * q], GL15.GL_QUERY_RESULT);
         long t1 = GL33.glGetQueryObjecti64(ids[2 * q + 1], GL15.GL_QUERY_RESULT);
         issued[q] = 0L;
         gpuMs[gpuCount++ & (gpuMs.length - 1)] = Math.max(0L, t1 - t0) / 1e6f;
      }
   }

   // ------------------------------------------------------------------ the governor

   private static int fixedLevel() {
      return switch (MODE) {
         case "standard" -> STANDARD;
         case "min_sclk" -> MIN_SCLK;
         case "min_mclk" -> MIN_MCLK;
         case "peak" -> PEAK;
         default -> -1;
      };
   }

   /** Clock order for auto: NONE (automatic, up to the top clock) is the fastest, then standard, then min_mclk. */
   private static float clockGhz(int l) {
      return switch (l) {
         case STANDARD -> 1.0F;
         case MIN_SCLK -> 0.64F;
         case MIN_MCLK -> 0.68F;
         case PEAK -> 2.17F;
         default -> 2.8F;
      };
   }

   private static void govern() throws Throwable {
      long now = System.nanoTime();
      if (lastTick != 0L && level >= 0) {
         levelMs[level] += (now - lastTick) / 1_000_000L;
      }
      lastTick = now;
      int fixed = fixedLevel();
      if (fixed >= 0) {
         set(fixed, "gpuPstate=" + MODE);
         return;
      }
      // auto
      if (!inWorld || uncapped) {
         set(NONE, !inWorld ? "no world" : "uncapped");
         return;
      }
      if (now - windowStart < 500_000_000L) {
         return;
      }
      int n = Math.min(gpuCount, 60);
      windowStart = now;
      if (n < 20) {
         return;
      }
      float[] v = new float[n];
      for (int i = 0; i < n; i++) {
         v[i] = gpuMs[(gpuCount - 1 - i) & (gpuMs.length - 1)];
      }
      Arrays.sort(v);
      float p90 = v[(int) (n * 0.9F)];
      float budget = 1000.0F / budgetFps;
      float fit = budget * Config.GPU_PSTATE_FIT_PCT / 100.0F;
      if (level >= 0) {
         learnedMs[level] = p90;
      }
      // the ladder, slowest first: min_sclk (~640 MHz), standard (~1 GHz), automatic. The frame's GPU work on the flip is
      // mostly memory-bound (at a fixed 1 GHz it took 4.2 ms against 3.4 at automatic clocks, not 2.7x), so the low
      // levels hold the cap far more often than the clock ratio suggests: the step-down estimate is the time last
      // measured at the lower level, else this level's time scaled by half the clock ratio.
      int rung = rung(level);
      if (rung > 0 && p90 > fit) {
         int up = LADDER[rung - 1];
         set(up, String.format(Locale.ROOT, "GPU p90 %.2f ms of %.2f at %s", p90, budget, name(level)));
         holdUntil = now + backoffNs;
         backoffNs = Math.min(backoffNs * 2, 120_000_000_000L);
      } else if (rung >= 0 && rung + 1 < LADDER.length && now > holdUntil) {
         int down = LADDER[rung + 1];
         float ratio = clockGhz(level) / clockGhz(down);
         float est = learnedMs[down] > 0 ? Math.max(learnedMs[down], p90) : p90 * (1.0F + (ratio - 1.0F) * 0.5F);
         if (est <= fit * 0.9F) {
            set(down, String.format(Locale.ROOT, "GPU p90 %.2f ms at %s, ~%.2f expected at %s, of %.2f", p90, name(level), est, name(down), budget));
            holdUntil = now + 2_000_000_000L;
         } else {
            holdUntil = now + 2_000_000_000L;
            learnedMs[down] = 0; // re-estimate from this level next time
         }
         if (p90 < fit * 0.7F) {
            backoffNs = 5_000_000_000L;
         }
      }
   }

   /** gpuPstate=auto's levels, fastest first. */
   private static final int[] LADDER = {NONE, STANDARD, MIN_SCLK};

   private static int rung(int l) {
      for (int i = 0; i < LADDER.length; i++) {
         if (LADDER[i] == l) {
            return i;
         }
      }
      return -1;
   }

   private static void set(int l, String why) throws Throwable {
      if (l == level) {
         return;
      }
      buf.set(ValueLayout.JAVA_INT, 0, 6); // AMDGPU_CTX_OP_SET_STABLE_PSTATE
      buf.set(ValueLayout.JAVA_INT, 4, l);
      buf.set(ValueLayout.JAVA_INT, 8, ctxId);
      buf.set(ValueLayout.JAVA_INT, 12, 0);
      int r = (int) ioctl.invokeExact(fd, IOCTL_AMDGPU_CTX, buf);
      if (r != 0) {
         fail("SET_STABLE_PSTATE " + l + " returned " + r + " (another client holds the stable pstate?)");
         return;
      }
      level = l;
      changes++;
      Log.info("gpuPstate: " + name(l) + " (" + why + ")");
   }

   private static String name(int l) {
      return switch (l) {
         case STANDARD -> "standard (~1 GHz)";
         case MIN_SCLK -> "min_sclk";
         case MIN_MCLK -> "min_mclk";
         case PEAK -> "peak";
         default -> "automatic";
      };
   }

   private static void start() {
      started = true;
      File dri = new File("/dev/dri");
      String node = null;
      String[] names = dri.list();
      if (names != null) {
         Arrays.sort(names);
         for (String n : names) {
            if (n.startsWith("renderD") && isAmdgpu(n)) {
               node = "/dev/dri/" + n;
               break;
            }
         }
      }
      if (node == null) {
         broken = true;
         Log.info("gpuPstate: no amdgpu render node; off");
         return;
      }
      try {
         Linker l = Linker.nativeLinker();
         MethodHandle open = l.downcallHandle(l.defaultLookup().find("open").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT), Linker.Option.firstVariadicArg(2));
         ioctl = l.downcallHandle(l.defaultLookup().find("ioctl").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
               Linker.Option.firstVariadicArg(2));
         fd = (int) open.invokeExact(ARENA.allocateFrom(node), 2 | 0x80000); // O_RDWR | O_CLOEXEC
         if (fd < 0) {
            fail("open " + node + " failed");
            return;
         }
         buf = ARENA.allocate(16, 8);
         buf.set(ValueLayout.JAVA_INT, 0, 1); // AMDGPU_CTX_OP_ALLOC_CTX
         int r = (int) ioctl.invokeExact(fd, IOCTL_AMDGPU_CTX, buf);
         if (r != 0) {
            fail("AMDGPU_CTX alloc returned " + r);
            return;
         }
         ctxId = buf.get(ValueLayout.JAVA_INT, 0);
         ids = new int[2 * QUERIES];
         GL15.glGenQueries(ids);
         Log.info("gpuPstate=" + MODE + ": " + node + " context " + ctxId);
      } catch (Throwable t) {
         fail(String.valueOf(t));
      }
   }

   private static boolean isAmdgpu(String renderNode) {
      try {
         String link = new File("/sys/class/drm/" + renderNode + "/device/driver").getCanonicalPath();
         return link.endsWith("/amdgpu");
      } catch (Exception e) {
         return false;
      }
   }

   private static void fail(String why) {
      if (!broken) {
         broken = true;
         Log.warn("gpuPstate: " + why + "; off");
      }
   }

   /** One line for the harness summary. */
   public static String describe() {
      if (!started) {
         return "gpu_pstate=" + MODE + " (not started)";
      }
      return "gpu_pstate=" + MODE + (broken ? " (off)" : "") + " level=" + name(Math.max(level, 0)) + " changes=" + changes + " ms[auto,standard,min_sclk,min_mclk,peak]="
            + Arrays.toString(levelMs) + String.format(Locale.ROOT, " p90ms@auto=%.2f p90ms@standard=%.2f", learnedMs[NONE], learnedMs[STANDARD]);
   }
}
