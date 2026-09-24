package pzopt;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Thread placement on hybrid CPUs ({@code corePlacement}, 2026-09-24, the E-core pass).
 *
 * <p>A Ryzen AI 9 HX 370 (the flip) has four Zen 5 cores that boost to 5.1 GHz and eight Zen 5c cores capped at 3.3 GHz.
 * One busy thread costs 9.2 W of core power on a Zen 5 core and 2.2 W on a Zen 5c core (SMU rails, pinned busy loop), and
 * the Linux scheduler put the game thread on the Zen 5 cores more than half of the time, so a 120 fps capped game drew
 * 37 W from the socket. Here the threads are sorted into classes by name and each class gets a CPU mask:
 * <ul>
 *   <li>critical: the game thread, the render thread, and Mesa's GL threads that carry the render thread's commands
 *   ({@code :gl}, {@code :gdrv}, {@code :cs});</li>
 *   <li>pause: the stop-the-world GC workers and the VM thread, only busy while every other thread is stopped, so a
 *   pause ends sooner on every core;</li>
 *   <li>background: everything else (lighting, streaming, pools, JIT, concurrent GC, audio, the shader compiler).</li>
 * </ul>
 * Background threads always run on the efficient cores in a world. The critical class follows the mode: {@code auto}
 * keeps it on the efficient cores while the game step fits the frame cap and moves it to the fast cores when it falls
 * short ({@link #onStep}, a governor thread at 4 Hz with hysteresis and a measured fast/slow speed ratio); uncapped
 * frames and loading screens use the fast cores. The loading screen gives every class every core (load time).
 *
 * <p>Linux: {@code sched_setaffinity} per thread id, re-applied to new threads by a scan of /proc/self/task (a thread
 * inherits its creator's mask). Classes of CPUs come from amd-pstate's highest perf, Intel's cpu_atom / cpu_core PMUs,
 * or the top clock; a CPU whose cores are all alike turns the placement off. macOS has no affinity: threads we own ask
 * for a quality-of-service class instead ({@link #background()}), which is what steers Apple's scheduler between its P
 * and E clusters.
 */
public final class CorePlacement {
   public static final String MODE = Config.CORE_PLACEMENT.toLowerCase(Locale.ROOT);
   private static final boolean LINUX = new File("/proc/self/task").isDirectory();
   private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

   private static final int CLASS_CRITICAL = 0, CLASS_PAUSE = 1, CLASS_BACKGROUND = 2;

   /** CPU masks (bit per logical CPU, up to 1024). */
   private static long[] fastMask, slowMask, allMask, backgroundMask, criticalFastMask;
   private static String fastList = "", slowList = "";
   private static boolean hybrid;

   private static MethodHandle schedSetaffinity, gettid, qosSelf;
   private static volatile boolean started, broken;

   /** Registered thread ids of the game thread and the render thread (Linux), 0 until known. */
   private static volatile int gameTid, renderTid;
   private static long gameJavaId = -1, renderJavaId = -1;

   /** Where the critical class runs now: true = fast cores. Written by the governor thread only. */
   private static volatile boolean criticalFast;
   /**
    * Frames are missing the cap (or there is none): the background class may use every core, because the frame's worker
    * batches (zombies, character draw, lighting reads) sit on the critical path then. Written by the governor thread only.
    */
   private static volatile boolean wide;
   public static long wideMs;
   /** Loading screen or menu before the first world: every class on every core. */
   private static volatile boolean loading = true;
   private static volatile boolean uncapped;
   private static volatile long budgetNs = 1_000_000_000L / 60;

   // step ring: the game thread writes, the governor reads (a torn read costs one sample)
   private static final long[] steps = new long[512];
   private static final long[] cpus = new long[512]; // the game thread's CPU per frame
   private static long lastCpu; // game thread only
   private static final ThreadMXBean MX = ManagementFactory.getThreadMXBean();
   private static volatile int stepCount;

   public static long promotions, demotions, fastMs, slowMs;
   private static float ratio = 1.6F; // fast / slow speed: the CPPC highest-perf (or clock) ratio of the two classes until both are measured
   private static double emaSlow, emaFast; // the game thread's CPU p90 per frame in steady windows on each class (ns)
   private static int nSlow, nFast;

   private CorePlacement() {
   }

   /** Anything to do on this machine and in this mode. */
   public static boolean active() {
      return started && !broken;
   }

   /** Is the frame limiter allowed to sleep (no spinning core while the placement is managing the cores). */
   public static boolean limiterSleeps() {
      return active() && hybrid;
   }

   // --- game-thread hooks ----------------------------------------------------------------------------------------------

   /**
    * Game thread, after every limiter step (FrameCap.stepDone): the step's length, the in-game state and the cap in force.
    * Starts the placement on the first call.
    */
   public static void onStep(long stepNs, boolean inWorld, boolean isUncapped, int capFps) {
      if (!started) {
         start();
      }
      if (broken) {
         return;
      }
      // the game thread's own CPU per frame (step + limiter wait): a step that is long because it waited for the render
      // thread, the GPU or a worker batch is not a reason for faster cores
      long cpu = MX.getCurrentThreadCpuTime();
      long frameCpu = lastCpu > 0L ? cpu - lastCpu : stepNs;
      lastCpu = cpu;
      int n = stepCount;
      steps[n & (steps.length - 1)] = stepNs;
      cpus[n & (cpus.length - 1)] = frameCpu;
      stepCount = n + 1;
      loading = !inWorld;
      uncapped = isUncapped;
      budgetNs = 1_000_000_000L / Math.max(1, capFps);
   }

   /**
    * The calling thread is background work (pools, lighting, streaming): on macOS it asks for the utility QoS class, which
    * Apple's scheduler runs on the E cores first. Linux sorts threads by name instead, so this is a no-op there.
    */
   public static void background() {
      if (MAC && qos(0x11)) { // QOS_CLASS_UTILITY
         return;
      }
   }

   /** The calling thread is the game or the render thread: user-interactive QoS on macOS. */
   public static void interactive() {
      if (MAC) {
         qos(0x21); // QOS_CLASS_USER_INTERACTIVE
      }
   }

   private static boolean qos(int cls) {
      if (!MAC || MODE.equals("off") || !Overrides.enabled()) {
         return false;
      }
      try {
         if (qosSelf == null) {
            Linker l = Linker.nativeLinker();
            qosSelf = l.downcallHandle(l.defaultLookup().find("pthread_set_qos_class_self_np").orElseThrow(),
                  FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
         }
         return (int) qosSelf.invokeExact(cls, 0) == 0;
      } catch (Throwable t) {
         return false;
      }
   }

   // --- start ----------------------------------------------------------------------------------------------------------

   private static synchronized void start() {
      if (started) {
         return;
      }
      started = true;
      if (MODE.equals("off") || !Overrides.enabled()) {
         broken = true;
         return;
      }
      if (MAC) {
         interactive(); // the game thread
         try {
            zombie.core.opengl.RenderThread.invokeOnRenderContext(CorePlacement::interactive);
         } catch (Throwable t) {
            Log.warn("corePlacement: render thread QoS not set (" + t + ")");
         }
         broken = true; // nothing else to run on macOS: the pools call background() themselves
         Log.info("corePlacement: macOS QoS classes (game / render threads user-interactive, pools utility)");
         return;
      }
      if (!LINUX) {
         broken = true;
         return;
      }
      try {
         detect();
      } catch (Throwable t) {
         Log.warn("corePlacement: no CPU topology (" + t + "); off");
         broken = true;
         return;
      }
      if (!hybrid) {
         Log.info("corePlacement: every core is alike; off");
         broken = true;
         return;
      }
      try {
         Linker l = Linker.nativeLinker();
         schedSetaffinity = l.downcallHandle(l.defaultLookup().find("sched_setaffinity").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
         gettid = l.downcallHandle(l.defaultLookup().find("gettid").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
         gameTid = (int) gettid.invokeExact();
      } catch (Throwable t) {
         Log.warn("corePlacement: no native sched_setaffinity / gettid (" + t + "); off");
         broken = true;
         return;
      }
      gameJavaId = Thread.currentThread().threadId();
      try {
         zombie.core.opengl.RenderThread.invokeOnRenderContext(CorePlacement::registerRender);
      } catch (Throwable t) {
         Log.warn("corePlacement: render thread not registered (" + t + ")");
      }
      criticalFast = !MODE.equals("efficient") && !MODE.equals("auto");
      Log.info("corePlacement=" + MODE + ": fast cores " + fastList + ", efficient cores " + slowList + "; game tid " + gameTid
            + ", render tid " + renderTid);
      Thread th = new Thread(CorePlacement::loop, "pzopt-cores");
      th.setDaemon(true);
      th.start();
   }

   private static void registerRender() {
      try {
         renderTid = (int) gettid.invokeExact();
         renderJavaId = Thread.currentThread().threadId();
      } catch (Throwable t) {
         Log.warn("corePlacement: render gettid failed (" + t + ")");
      }
   }

   /** Fast / slow classes of logical CPUs (Linux sysfs). */
   private static void detect() throws Exception {
      int n = 0;
      File cpuDir = new File("/sys/devices/system/cpu");
      File[] cpus = cpuDir.listFiles((d, name) -> name.matches("cpu\\d+"));
      int max = 0;
      for (File c : cpus) {
         max = Math.max(max, Integer.parseInt(c.getName().substring(3)));
      }
      long[] rank = new long[max + 1];
      Arrays.fill(rank, -1);
      int[] atom = readList("/sys/devices/cpu_atom/cpus");
      for (File c : cpus) {
         int id = Integer.parseInt(c.getName().substring(3));
         long v = readLong(c, "cpufreq/amd_pstate_highest_perf");
         if (v < 0) {
            v = readLong(c, "acpi_cppc/highest_perf");
         }
         if (v < 0) {
            v = readLong(c, "cpu_capacity");
         }
         if (v < 0) {
            v = readLong(c, "cpufreq/cpuinfo_max_freq");
         }
         rank[id] = v;
         n++;
      }
      if (atom.length > 0) { // Intel hybrid: the PMU says which are the E cores
         for (int i = 0; i <= max; i++) {
            if (rank[i] >= 0) {
               rank[i] = 2;
            }
         }
         for (int a : atom) {
            if (a <= max) {
               rank[a] = 1;
            }
         }
      }
      long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
      for (long v : rank) {
         if (v >= 0) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
         }
      }
      int words = (max >> 6) + 1;
      fastMask = new long[words];
      slowMask = new long[words];
      allMask = new long[words];
      if (n < 2 || hi <= lo || hi < lo * 1.15) { // one class (a few percent of boost-ranking spread is not a hybrid CPU)
         hybrid = false;
         return;
      }
      long cut = (hi + lo) / 2;
      ratio = (float) Math.max(1.2, Math.min(2.0, (double) hi / Math.max(1L, lo)));
      List<Integer> f = new ArrayList<>(), s = new ArrayList<>();
      for (int i = 0; i <= max; i++) {
         if (rank[i] < 0) {
            continue;
         }
         allMask[i >> 6] |= 1L << (i & 63);
         if (rank[i] > cut) {
            fastMask[i >> 6] |= 1L << (i & 63);
            f.add(i);
         } else {
            slowMask[i >> 6] |= 1L << (i & 63);
            s.add(i);
         }
      }
      fastList = compact(f);
      slowList = compact(s);
      hybrid = !f.isEmpty() && !s.isEmpty();
      backgroundMask = slowMask;
      criticalFastMask = fastMask;
      if (!Config.CORE_CRITICAL_CPUS.isBlank()) {
         criticalFastMask = parseMask(Config.CORE_CRITICAL_CPUS, words, rank, max);
         fastList = fastList + " (critical on " + Config.CORE_CRITICAL_CPUS + ")";
      }
      if (!Config.CORE_BACKGROUND_CPUS.isBlank()) {
         long[] m = parseMask(Config.CORE_BACKGROUND_CPUS, words, rank, max);
         backgroundMask = m;
         slowList = slowList + " (background on " + Config.CORE_BACKGROUND_CPUS + ")";
      }
   }

   private static long[] parseMask(String spec, int words, long[] rank, int max) {
      long[] m = new long[words];
      for (String part : spec.split(",")) {
         part = part.trim();
         if (part.isEmpty()) {
            continue;
         }
         int dash = part.indexOf('-');
         int a = Integer.parseInt(dash < 0 ? part : part.substring(0, dash));
         int b = dash < 0 ? a : Integer.parseInt(part.substring(dash + 1));
         for (int i = a; i <= b && i <= max; i++) {
            if (rank[i] >= 0) {
               m[i >> 6] |= 1L << (i & 63);
            }
         }
      }
      return m;
   }

   private static long readLong(File dir, String rel) {
      try {
         return Long.parseLong(Files.readString(new File(dir, rel).toPath()).trim());
      } catch (Exception e) {
         return -1;
      }
   }

   private static int[] readList(String path) {
      try {
         String spec = Files.readString(new File(path).toPath()).trim();
         List<Integer> out = new ArrayList<>();
         for (String part : spec.split(",")) {
            if (part.isEmpty()) {
               continue;
            }
            int dash = part.indexOf('-');
            int a = Integer.parseInt(dash < 0 ? part : part.substring(0, dash));
            int b = dash < 0 ? a : Integer.parseInt(part.substring(dash + 1));
            for (int i = a; i <= b; i++) {
               out.add(i);
            }
         }
         return out.stream().mapToInt(Integer::intValue).toArray();
      } catch (Exception e) {
         return new int[0];
      }
   }

   private static String compact(List<Integer> cs) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < cs.size(); ) {
         int j = i;
         while (j + 1 < cs.size() && cs.get(j + 1) == cs.get(j) + 1) {
            j++;
         }
         if (sb.length() > 0) {
            sb.append(',');
         }
         sb.append(cs.get(i));
         if (j > i) {
            sb.append('-').append(cs.get(j));
         }
         i = j + 1;
      }
      return sb.toString();
   }

   // --- the placement thread -------------------------------------------------------------------------------------------

   private static final Map<Integer, Integer> classOf = new HashMap<>(); // tid -> class, placement thread only
   private static final Map<Integer, long[]> applied = new HashMap<>(); // tid -> mask last applied

   private static void loop() {
      ThreadMXBean mx = ManagementFactory.getThreadMXBean();
      long lastWall = System.nanoTime();
      long lastGameCpu = -1, lastRenderCpu = -1;
      int lastSteps = stepCount;
      long movedAt = System.nanoTime();
      long hold = Config.CORE_HOLD_MS * 1_000_000L;
      long lastDemote = 0;
      int tick = 0;
      int behindWindows = 0;
      long lastBehind = 0;
      boolean wasLoading = true;
      boolean wasFast = criticalFast;
      while (true) {
         try {
            Thread.sleep(250);
            long now = System.nanoTime();
            long wall = now - lastWall;
            lastWall = now;
            long gameCpu = gameJavaId >= 0 ? mx.getThreadCpuTime(gameJavaId) : -1;
            long renderCpu = renderJavaId >= 0 ? mx.getThreadCpuTime(renderJavaId) : -1;
            int sc = stepCount;
            int frames = sc - lastSteps;
            lastSteps = sc;
            double gameWork = lastGameCpu >= 0 && gameCpu >= 0 && frames > 0 ? (double) (gameCpu - lastGameCpu) / frames : 0.0;
            double renderWork = lastRenderCpu >= 0 && renderCpu >= 0 && frames > 0 ? (double) (renderCpu - lastRenderCpu) / frames : 0.0;
            lastGameCpu = gameCpu;
            lastRenderCpu = renderCpu;
            long p90 = p90(steps, sc, Math.min(frames, steps.length));
            long cpu90 = p90(cpus, sc, Math.min(frames, cpus.length));
            double budget = budgetNs;
            // steady windows (every frame the cap asks for, and not the second right after a move) teach the real speed
            // ratio: Zen 5c has half the L3 of Zen 5 besides the lower clock, so the game thread took ~2x, not the 1.57 of
            // the boost rankings (the flip, b3)
            if (frames > 0 && frames >= wall / budget * 0.97 && now - movedAt > 1_000_000_000L && !loading && !uncapped && cpu90 > 0) {
               if (criticalFast) {
                  emaFast = nFast++ == 0 ? cpu90 : emaFast * 0.8 + cpu90 * 0.2;
               } else {
                  emaSlow = nSlow++ == 0 ? cpu90 : emaSlow * 0.8 + cpu90 * 0.2;
               }
               if (nFast >= 3 && nSlow >= 3) {
                  ratio = (float) Math.max(1.3, Math.min(2.5, emaSlow / emaFast));
               }
            }
            if (criticalFast) {
               fastMs += wall / 1_000_000L;
            } else {
               slowMs += wall / 1_000_000L;
            }

            // wide: two windows in a row below 95 % of the cap's frames, cleared after 3 s at the cap
            boolean behindNow = !loading && (uncapped || frames > 0 && frames < wall / budget * 0.95);
            behindWindows = behindNow ? behindWindows + 1 : 0;
            if (behindNow) {
               lastBehind = now;
            }
            boolean wantWide = uncapped || behindWindows >= 2 || wide && now - lastBehind < 3_000_000_000L;
            if (wide) {
               wideMs += wall / 1_000_000L;
            }
            boolean want = criticalFast;
            if (MODE.equals("performance")) {
               want = true;
            } else if (MODE.equals("efficient")) {
               want = false;
            } else if (loading || uncapped) {
               want = true;
            } else if (frames > 0) {
               double work = cpu90;
               if (!criticalFast) {
                  // the game thread's own CPU per frame (p90 of the window) or the render thread's is past the share
                  boolean gameShort = cpu90 > budget * Config.CORE_PROMOTE_PCT / 100.0;
                  boolean renderShort = renderWork > budget * Config.CORE_PROMOTE_PCT / 100.0;
                  if (gameShort || renderShort) {
                     want = true;
                     if (now - lastDemote < 10_000_000_000L) {
                        hold = Math.min(hold * 2, 60_000_000_000L); // it went back and forth: stay longer next time
                     }
                  }
               } else if (now - movedAt > hold) {
                  double w = Math.max(work, renderWork);
                  if (w * ratio < budget * Config.CORE_DEMOTE_PCT / 100.0) {
                     want = false;
                     lastDemote = now;
                  }
               }
            }
            if (want != criticalFast) {
               criticalFast = want;
               movedAt = now;
               if (want) {
                  promotions++;
               } else {
                  demotions++;
               }
            }
            boolean wideChanged = wantWide != wide;
            wide = wantWide;
            if (wideChanged && !loading) {
               Log.info("corePlacement: background threads -> " + (wantWide ? "every core (frames below the cap)" : "efficient cores (the cap holds)"));
            }
            if (want != wasFast || loading != wasLoading || wideChanged || (tick & 1) == 0) {
               scan(want != wasFast || loading != wasLoading || wideChanged);
               if (want != wasFast && !loading && !uncapped) {
                  Log.info(String.format(Locale.ROOT, "corePlacement: game + render threads -> %s cores (step p90 %.2f ms, game cpu p90 %.2f / mean %.2f ms, render %.2f ms of %.2f ms, %d of %.0f frames; speed ratio %.2f)",
                        want ? "fast" : "efficient", p90 / 1e6, cpu90 / 1e6, gameWork / 1e6, renderWork / 1e6, budget / 1e6, frames, wall / budget, ratio));
               }
               wasFast = want;
               wasLoading = loading;
            }
            tick++;
         } catch (InterruptedException e) {
            return;
         } catch (Throwable t) {
            Log.warn("corePlacement: " + t + "; off");
            broken = true;
            return;
         }
      }
   }

   private static long p90(long[] ring, int end, int n) {
      if (n <= 0) {
         return 0;
      }
      long[] v = new long[n];
      for (int i = 0; i < n; i++) {
         v[i] = ring[(end - 1 - i) & (ring.length - 1)];
      }
      Arrays.sort(v);
      return v[Math.min(n - 1, (int) (n * 0.9))];
   }

   /** Classify new threads, and re-apply the masks of every known thread when {@code all}. */
   private static void scan(boolean all) throws Throwable {
      File[] tasks = new File("/proc/self/task").listFiles();
      if (tasks == null) {
         return;
      }
      Map<Integer, Integer> seen = new HashMap<>();
      for (File t : tasks) {
         int tid;
         try {
            tid = Integer.parseInt(t.getName());
         } catch (NumberFormatException e) {
            continue;
         }
         Integer cls = classOf.get(tid);
         if (cls == null) {
            String comm;
            try {
               comm = Files.readString(new File(t, "comm").toPath()).trim();
            } catch (Exception e) {
               continue;
            }
            cls = classify(tid, comm);
            classOf.put(tid, cls);
         }
         seen.put(tid, cls);
         long[] mask = maskFor(cls);
         long[] prev = applied.get(tid);
         if (all || prev == null || !Arrays.equals(prev, mask)) {
            if (setAffinity(tid, mask)) {
               applied.put(tid, mask);
            } else {
               applied.put(tid, mask); // a thread that exited between the listing and the call; do not retry every pass
            }
         }
      }
      classOf.keySet().retainAll(seen.keySet());
      applied.keySet().retainAll(seen.keySet());
   }

   private static int classify(int tid, String comm) {
      if (tid == gameTid || tid == renderTid) {
         return CLASS_CRITICAL;
      }
      // Mesa's threads that carry the render thread's GL commands: <process>:gl0 (glthread), :gdrv0, :cs0
      int colon = comm.lastIndexOf(':');
      if (colon >= 0) {
         String tail = comm.substring(colon + 1);
         if (tail.startsWith("gl") || tail.startsWith("gdrv") || tail.startsWith("cs")) {
            return CLASS_CRITICAL;
         }
      }
      if (comm.startsWith("GC Thread") || comm.equals("VM Thread")) {
         return CLASS_PAUSE;
      }
      return CLASS_BACKGROUND;
   }

   private static long[] maskFor(int cls) {
      if (loading) {
         return allMask;
      }
      return switch (cls) {
         case CLASS_CRITICAL -> criticalFast ? criticalFastMask : slowMask;
         case CLASS_PAUSE -> allMask;
         default -> wide ? allMask : backgroundMask;
      };
   }

   private static boolean setAffinity(int tid, long[] mask) throws Throwable {
      try (Arena a = Arena.ofConfined()) {
         MemorySegment set = a.allocate(Math.max(128, mask.length * 8L));
         for (int i = 0; i < mask.length; i++) {
            set.setAtIndex(ValueLayout.JAVA_LONG, i, mask[i]);
         }
         return (int) schedSetaffinity.invokeExact(tid, set.byteSize(), set) == 0;
      }
   }

   /** One line for the periodic FBORenderCell log and the harness summary. */
   public static String describe() {
      if (!active()) {
         return "cores=" + MODE + " (inactive)";
      }
      return "cores=" + MODE + " critical=" + (criticalFast ? "fast" : "efficient") + " fast_ms=" + fastMs + " slow_ms=" + slowMs
            + " promotions=" + promotions + " demotions=" + demotions + " wide_ms=" + wideMs + String.format(Locale.ROOT, " ratio=%.2f", ratio);
   }
}
