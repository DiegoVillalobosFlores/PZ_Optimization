package pzopt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;

/**
 * GPU time per named section of the frame, measured with {@code GL_TIMESTAMP} queries that ride
 * the sprite command stream: {@link #begin(String)} / {@link #end(String)} are called on the game
 * thread while a frame is recorded, each queues a generic draw command whose render step (on the
 * render thread, in stream order) writes a timestamp query. Results are collected on the render
 * thread a few frames later and summed per section; {@link #summary()} (called from the periodic
 * FBORenderCell log line) prints the average GPU time per frame of each section since the last
 * summary. Timestamps nest freely, unlike GL_TIME_ELAPSED (the Overlay's whole-frame query).
 * Enabled by {@code Config.GPU_SECTIONS} (measurement only; a few dozen queries per frame).
 */
public final class GpuSections {
   private static final int RING = 4096;
   private static int[] ids;
   private static int next;
   private static final ArrayList<Pair> pending = new ArrayList<>();
   private static final HashMap<String, long[]> totals = new HashMap<>(); // name -> {ns, count}
   private static final ArrayList<Marker> pool = new ArrayList<>();
   private static long frames;
   private static int lastFrame = -1;

   private static final class Pair {
      final String name;
      final int beginId;
      final int endId;
      final long beginCpuNs;
      final long endCpuNs;

      Pair(String name, int beginId, int endId, long beginCpuNs, long endCpuNs) {
         this.name = name;
         this.beginId = beginId;
         this.endId = endId;
         this.beginCpuNs = beginCpuNs;
         this.endCpuNs = endCpuNs;
      }
   }

   /**
    * Per-pair log (Zomboid/pzopt-gpusections.out, instrumented runs with gpuSections): one line per finished section,
    * "begin_cpu_ns name gpu_ns", begin_cpu_ns = System.nanoTime() when the render thread issued the begin timestamp,
    * so each pair joins the pzopt-pacing.out frame whose acquire..swap-call window holds it (harness/frame-causes.py).
    */
   private static final boolean LOG = Config.INSTRUMENT;
   private static java.io.BufferedWriter log;
   private static boolean logFailed;
   private static final HashMap<String, Long> openCpu = new HashMap<>();

   /** One queued timestamp; render() runs on the render thread. */
   private static final class Marker extends TextureDraw.GenericDrawer {
      String name;
      boolean isEnd;
      int id;

      @Override
      public void render() {
         if (ids == null) {
            ids = new int[RING];
            GL15.glGenQueries(ids);
         }
         if (id < 0) {
            id = ids[next];
            next = (next + 1) % RING;
         }
         GL33.glQueryCounter(id, GL33.GL_TIMESTAMP);
         if (isEnd) {
            long cpu;
            synchronized (open) {
               Long c = openCpu.remove(name);
               cpu = c == null ? 0L : c;
            }
            synchronized (pending) {
               pending.add(new Pair(name, openBegin(name), id, cpu, System.nanoTime()));
            }
            collect();
         } else {
            synchronized (open) {
               open.put(name, id);
               openCpu.put(name, System.nanoTime());
            }
         }
      }

      @Override
      public void postRender() {
         synchronized (pool) {
            pool.add(this);
         }
      }
   }

   private static final HashMap<String, Integer> open = new HashMap<>();

   private static int openBegin(String name) {
      synchronized (open) {
         Integer id = open.remove(name);
         return id == null ? -1 : id;
      }
   }

   public static boolean enabled() {
      return Config.GPU_SECTIONS && Overrides.enabled();
   }

   public static void begin(String name) {
      if (enabled()) {
         queue(name, false);
      }
   }

   public static void end(String name) {
      if (enabled()) {
         queue(name, true);
      }
   }

   /** Render thread, inside a drawer's render(): a section begin/end issued right now in the GL stream. */
   public static void markNow(String name, boolean isEnd) {
      if (!enabled()) {
         return;
      }
      Marker m;
      synchronized (pool) {
         m = pool.isEmpty() ? new Marker() : pool.remove(pool.size() - 1);
      }
      m.name = name;
      m.isEnd = isEnd;
      m.id = -1;
      m.render();
      m.postRender();
   }

   /** Call once per frame on the game thread (any section site does it) so per-frame averages are right. */
   public static void frame(int frameNo) {
      if (frameNo != lastFrame) {
         lastFrame = frameNo;
         frames++;
      }
   }

   private static void queue(String name, boolean isEnd) {
      Marker m;
      synchronized (pool) {
         m = pool.isEmpty() ? new Marker() : pool.remove(pool.size() - 1);
      }
      m.name = name;
      m.isEnd = isEnd;
      m.id = -1;
      SpriteRenderer.instance.drawGeneric(m);
   }

   /** Render thread: fold every finished pair into the totals. */
   private static void collect() {
      synchronized (pending) {
         for (int i = pending.size() - 1; i >= 0; i--) {
            Pair p = pending.get(i);
            if (p.beginId < 0) {
               pending.remove(i);
               continue;
            }
            if (GL15.glGetQueryObjecti(p.endId, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
               continue;
            }
            long t0 = GL33.glGetQueryObjecti64(p.beginId, GL15.GL_QUERY_RESULT);
            long t1 = GL33.glGetQueryObjecti64(p.endId, GL15.GL_QUERY_RESULT);
            pending.remove(i);
            synchronized (totals) {
               long[] t = totals.computeIfAbsent(p.name, k -> new long[2]);
               t[0] += Math.max(0L, t1 - t0);
               t[1]++;
            }
            if (LOG) {
               logPair(p, Math.max(0L, t1 - t0));
            }
         }
      }
   }

   private static void logPair(Pair p, long gpuNs) {
      if (logFailed) {
         return;
      }
      try {
         if (log == null) {
            String dir = zombie.ZomboidFileSystem.instance.getCacheDir();
            if (dir == null) {
               return;
            }
            log = new java.io.BufferedWriter(new java.io.FileWriter(new java.io.File(dir, "pzopt-gpusections.out"), false), 1 << 16);
            log.write("# begin_cpu_ns name gpu_ns cpu_ns (begin = System.nanoTime of the begin timestamp's issue on the render thread; cpu_ns = render-thread time from begin to end issue)\n");
         }
         log.write(p.beginCpuNs + " " + p.name + " " + gpuNs + " " + (p.endCpuNs - p.beginCpuNs) + "\n");
      } catch (java.io.IOException | RuntimeException e) {
         logFailed = true;
      }
   }

   /** Render thread, once a second or at exit: push the per-pair log to disk. */
   public static void flushLog() {
      java.io.BufferedWriter w = log;
      if (w != null) {
         try {
            w.flush();
         } catch (java.io.IOException ignored) {
         }
      }
   }

   /** Average GPU microseconds per frame and pairs per frame for every section since the last call; resets. */
   public static String summary() {
      if (!enabled()) {
         return "";
      }
      StringBuilder sb = new StringBuilder(" | gpu us/frame:");
      synchronized (totals) {
         long f = Math.max(1L, frames);
         ArrayList<Map.Entry<String, long[]>> entries = new ArrayList<>(totals.entrySet());
         entries.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
         for (Map.Entry<String, long[]> e : entries) {
            long[] t = e.getValue();
            sb.append(' ').append(e.getKey()).append('=').append(t[0] / 1000L / f).append('(').append(String.format("%.1f", (double) t[1] / f)).append("/f)");
         }
         totals.clear();
         frames = 0;
      }
      return sb.toString();
   }
}
