package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import zombie.ZomboidFileSystem;

/**
 * What the game thread is doing while it is the bottleneck: its stack, sampled {@code gameThreadProfileHz}
 * times a second from a daemon thread and folded into named buckets, so the overlay's "game thread
 * bound" verdict comes with the phases behind it and a harness run keeps a per-second breakdown
 * ({@code Zomboid/pzopt-gamethread.out}, summed over the route window by harness/analyze.py). The
 * same numbers JFR + harness/gametree.py give after a run, live, on every platform, without JFR.
 *
 * Each sample is one {@link Thread#getStackTrace()} of the game thread, which the JVM serves with a
 * thread-local handshake: the game thread stops at its next safepoint poll for the stack walk (tens of
 * microseconds), nothing else pauses. (Until 2026-09-22 it was {@link ThreadMXBean#getThreadInfo(long, int)},
 * which Java 25 serves with a global ThreadDump safepoint: at 100 Hz on the 4-core Dell that stopped every
 * Java thread for 10 % of the wall time, single stops up to 175 ms; {@code profileHandshake=false} restores it.) Safepoint bias applies (a
 * sample lands on the next poll, not exactly where the timer fired), fine for shares of phases.
 * Every sample is counted four ways, prefixes of the bucket keys:
 * <ul>
 * <li>{@code p:<phase>} the phase, from the frame's top-level split: {@code update} (GameWindow.logic),
 *     {@code render} (GameWindow.renderInternal, the game-thread half of rendering: bakes, the
 *     translucent pass, the UI), {@code lighting} (LightingThread.update on the game thread),
 *     {@code frame other}, or {@code outside frame} (limiter, menus, loading);</li>
 * <li>{@code s:<phase>/<sub>} the sub-phase: the innermost frame in {@link #LABELS} ("chunk bakes",
 *     "zombies", "vispoly", ...), else the game frame just inside the innermost {@link #CONTAINERS}
 *     entry ({@code Class.method}), so new code names itself;</li>
 * <li>{@code l:<phase>/<sub>/<method>} the hot method: the innermost game frame (zombie / se.krka /
 *     pzopt / fmod), JDK callees folded into it, like gametree.py;</li>
 * <li>{@code w:<phase>/<sub>/<method>} the same path when the thread was not RUNNABLE: what the
 *     game thread waits on (the render thread in the frame hand-off, a lock, a sleep). A high
 *     game-thread load with a waits share is not "bound" but "waiting".</li>
 * </ul>
 * The keys nest, so the overlay draws them as a tree ({@link #tree}: phases, their sub-phases, the
 * hottest methods as a hint, biggest first at every level, each sub-phase in a stable colour of its
 * own, waits in red) and analyze.py prints the same tree over a route.
 * Seconds are published as immutable snapshots into a small ring; the overlay merges the last
 * {@link #WINDOW_SECONDS}. Samples are only taken while someone reads them (the overlay is shown or
 * the frame log is on), like the utilization sampler, and only when an element shows the result
 * (overlayTree, overlayFlame, overlayVerdict=detailed) or the frame log is on; {@code gameThreadProfileHz} sets the rate (25 by default, 125 samples over the 5 s window: at 100 Hz the captures took 1.3-1.6 % of the Mac's wall time, 2026-09-23; a
 * sample is ~80 us on the sampler thread, of which the game thread's own stall is a fraction, so
 * well under 1 % of it).
 */
public final class GameThreadProfile {
   /**
    * Sampling runs when something shows or logs it: the tree, the flame graph, the detailed verdict, or the frame log
    * (harness runs). Read live, like the rate: the Profiler tab changes both while the game runs.
    */
   static boolean enabled() {
      return !"off".equalsIgnoreCase(Config.OVERLAY_TREE.trim()) || !"off".equalsIgnoreCase(Config.OVERLAY_FLAME.trim())
            || "detailed".equalsIgnoreCase(Config.OVERLAY_VERDICT.trim()) || Config.OVERLAY_LOG || Harness.REQUESTED;
   }

   /** Samples per second, {@code gameThreadProfileHz} clamped to 10..1000. */
   static int hz() {
      return Math.max(10, Math.min(1000, Config.GAME_THREAD_PROFILE_HZ));
   }
   static final int WINDOW_SECONDS = 5;
   private static final int MAX_DEPTH = 128;
   private static final int RING = 16;
   private static final String OUT_FILE = "pzopt-gamethread.out";

   /** Top-level phases, matched walking from the root: the first hit names the phase. */
   private static final String[][] PHASES = {
      {"zombie.GameWindow", "logic", "update"},
      {"zombie.GameWindow", "renderInternal", "render"},
      {"zombie.iso.LightingThread", "update", "lighting"},
   };

   /**
    * Frames whose direct callee names the sub-phase when no {@link #LABELS} entry matches deeper in
    * the stack, outermost first; the innermost one present wins. Prefix-matched on the class name
    * (so inner classes count) with an optional exact method.
    */
   private static final String[][] CONTAINERS = {
      {"zombie.GameWindow", "logic"},
      {"zombie.GameWindow", "renderInternal"},
      {"zombie.gameStates.IngameState", "updateInternal"},
      {"zombie.gameStates.IngameState", "renderInternal"},
      {"zombie.gameStates.IngameState", "renderFrameInternal"},
      {"zombie.gameStates.IngameState", "UpdateStuff"},
      {"zombie.iso.IsoWorld", "updateWorld"},
      {"zombie.iso.IsoWorld", "renderInternal"},
      {"zombie.iso.IsoCell", "updateInternal"},
      {"zombie.MovingObjectUpdateSchedulerUpdateBucket", "update"},
      {"zombie.MovingObjectUpdateSchedulerUpdateBucket", "postupdate"},
      {"zombie.iso.fboRenderChunk.FBORenderCell", "renderTilesInternal"},
      {"zombie.iso.fboRenderChunk.FBORenderCell", "performRenderTiles"},
   };

   /** Named sub-phases: class prefix, method (null = any), label. The innermost match in the stack wins. */
   private static final String[][] LABELS = {
      // update side
      {"zombie.characters.IsoPlayer", "update", "player"},
      {"zombie.characters.IsoPlayer", "postupdate", "player"},
      {"zombie.characters.IsoZombie", "update", "zombies"},
      {"zombie.characters.IsoZombie", "postupdate", "zombies"},
      {"zombie.characters.animals.IsoAnimal", null, "animals"},
      {"zombie.vehicles.BaseVehicle", "update", "vehicles"},
      {"zombie.vehicles.BaseVehicle", "postupdate", "vehicles"},
      {"zombie.vehicles.VehicleManager", null, "vehicles"},
      {"zombie.characters.ecs.ECSEntity", null, "ecs"},
      {"zombie.iso.IsoChunkMap", "update", "chunk map"},
      {"zombie.iso.IsoWorld", "updateThread", "chunk stream"},
      {"zombie.iso.WorldStreamer", null, "chunk stream"},
      {"zombie.iso.weather.fx.IsoWeatherFX", "update", "weather update"},
      {"zombie.iso.weather.ClimateManager", null, "weather update"},
      {"zombie.ui.UIManager", "update", "ui update"},
      {"zombie.Lua.LuaEventManager", "triggerEvent", "lua events"},
      {"zombie.SoundManager", null, "audio"},
      {"zombie.AmbientStreamManager", null, "audio"},
      {"zombie.audio", null, "audio"},
      {"zombie.WorldSoundManager", null, "world sounds"},
      {"zombie.ZombieGroupManager", null, "zombie population"},
      {"zombie.popman", null, "zombie population"},
      {"zombie.iso.IsoCell", "ProcessObjects", "objects update"},
      {"zombie.iso.IsoCell", "updateWeatherFx", "weather update"},
      // render side (game-thread half)
      {"zombie.iso.fboRenderChunk.FBORenderCell", "renderOneChunk", "chunk bakes"},
      {"zombie.iso.fboRenderChunk.FBORenderCell", "renderOneLevel", "chunk bakes"},
      {"zombie.iso.fboRenderChunk.FBORenderCell", "pzoptBakeTrees", "tree bake"},
      {"pzopt.TreeBake", null, "tree bake"},
      {"zombie.iso.fboRenderChunk.FBORenderCell", "renderTranslucentObjects", "translucent"},
      {"zombie.iso.fboRenderChunk.FBORenderCell", "renderOneChunk_Translucent", "translucent"},
      {"zombie.iso.fboRenderChunk.FBORenderCell", "renderTranslucentFloorObjects", "translucent floor"},
      {"zombie.iso.fboRenderChunk.FBORenderCell", "renderOneChunk_TranslucentFloor", "translucent floor"},
      {"zombie.vispoly", null, "vispoly"},
      {"zombie.iso.fboRenderChunk.FBORenderCell", "renderItemsInWorld", "world items"},
      {"zombie.iso.fboRenderChunk.FBORenderCell", "renderMovingObjects", "characters draw"},
      {"zombie.iso.fboRenderChunk.FBORenderCell", "renderMovingObject", "characters draw"},
      {"zombie.iso.fboRenderChunk.FBORenderCell", "renderPlayer", "characters draw"},
      {"pzopt.CharDraw", null, "characters draw"}, // the pre-pass: walk hand-off, the visibility test, the join wait
      {"zombie.iso.fboRenderChunk.FBORenderChunkManager", "endFrame", "chunk composite"},
      {"zombie.iso.fboRenderChunk.FBORenderChunk", "renderInWorldMainThread", "chunk composite"},
      {"zombie.iso.fboRenderChunk.FBORenderCell", "prepareChunksForUpdating", "prepare chunks"},
      {"zombie.iso.fboRenderChunk.FBORenderCell", "runChecks", "chunk checks"},
      {"zombie.iso.fboRenderChunk.FBORenderCell", "updateChunkLighting", "chunk lighting"},
      {"zombie.iso.fboRenderChunk.FBORenderCutaways", null, "cutaways"},
      {"zombie.iso.IsoWorld", "renderWeatherFX", "weather fx"},
      {"zombie.iso.weather.fx", null, "weather fx"},
      {"pzopt.RainTiles", null, "weather fx"},
      {"zombie.iso.weather.fog", null, "fog"},
      {"pzopt.FogPass", null, "fog"},
      {"zombie.iso.fboRenderChunk.FBORenderCell", "renderPuddles", "puddles"},
      {"zombie.iso.IsoPuddles", null, "puddles"},
      {"pzopt.PuddleCache", null, "puddles"},
      {"zombie.iso.fboRenderChunk.FBORenderCell", "renderWater", "water"},
      {"zombie.iso.fboRenderChunk.FBORenderCell", "renderWaterFlow", "water"},
      {"zombie.iso.fboRenderChunk.FBORenderCell", "renderWaterShore", "water"},
      {"zombie.iso.IsoWorld", "renderTerrain", "terrain"},
      {"zombie.ui.UIManager", "render", "ui draw"},
      {"zombie.gameStates.IngameState", "renderFrameUI", "ui draw"},
      {"zombie.gameStates.IngameState", "renderFrameTextInternal", "ui draw"},
      {"zombie.iso.LightingJNI", null, "lighting jni"},
      {"zombie.core.opengl.RenderThread", "Ready", "frame hand-off"}, // Core.EndFrameUI -> RenderThread.Ready -> pushFrameDown waits for a free render state
      {"zombie.core.SpriteRenderer", "pushFrameDown", "frame hand-off"},
      {"zombie.core.SpriteRenderer", "postRender", "frame hand-off"},
      {"zombie.core.SpriteRenderer", "initFromIsoCamera", "frame hand-off"},
      {"zombie.core.opengl.RenderThread", "invokeOnRenderContext", "render-thread call"}, // a blocking GL round trip from the game thread
      {"zombie.core.opengl.RenderContextQueueItem", null, "render-thread call"},
      {"pzopt.Overlay", null, "overlay"},
      {"pzopt.GpuSections", null, "overlay"},
      {"zombie.core.profiling", null, "game profiler"},
      {"zombie.ZomboidFileSystem", null, "file system"},
      {"zombie.MainThread", "flushInvokeQueue", "main-thread queue"},
      {"zombie.savefile", null, "saving"},
      {"zombie.iso.IsoChunk", "Save", "saving"},
   };

   /** One second of samples, immutable once published: the buckets and the folded stacks. */
   static final class Second {
      final long epochMs;
      final int samples;
      final Map<String, int[]> counts;
      /** Folded game-frame stacks ({@link #fold}: {@code Class.method;Class.method;...} root to leaf) and their sample counts. */
      final Map<String, int[]> stacks;

      Second(long epochMs, int samples, Map<String, int[]> counts, Map<String, int[]> stacks) {
         this.epochMs = epochMs;
         this.samples = samples;
         this.counts = counts;
         this.stacks = stacks;
      }
   }

   /** A node of the flame graph: a game frame, its sample count (inclusive) and its callees. */
   static final class Node {
      final String name;
      int count;
      final HashMap<String, Node> kids = new HashMap<>();

      Node(String name) {
         this.name = name;
      }

      Node kid(String n) {
         Node k = kids.get(n);
         if (k == null) {
            k = new Node(n);
            kids.put(n, k);
         }
         return k;
      }

      /** Callees, biggest first. */
      List<Node> sortedKids() {
         ArrayList<Node> out = new ArrayList<>(kids.values());
         out.sort((a, b) -> Integer.compare(b.count, a.count));
         return out;
      }
   }

   private static final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
   private static final Second[] ring = new Second[RING];
   private static volatile int ringHead; // seconds published so far
   private static Thread sampler;
   private static volatile long gameThreadId = -1L;
   private static volatile Thread gameThread; // pzopt: sampled by handshake (profileHandshake)
   private static BufferedWriter log;
   private static boolean logFailed;
   private static BufferedWriter stackLog;
   private static final HashMap<String, Integer> frameIds = new HashMap<>();
   private static final String STACKS_FILE = "pzopt-stacks.out";

   private GameThreadProfile() {
   }

   /**
    * From the overlay once the game thread is known, and again when the Profiler tab changes (a no-op once running);
    * the sampler runs until the JVM exits, idle while nothing wants its samples.
    */
   public static synchronized void start(long threadId) {
      if (!enabled() || sampler != null) {
         return;
      }
      gameThreadId = threadId;
      if (Thread.currentThread().threadId() == threadId) {
         gameThread = Thread.currentThread();
      }
      Thread t = new Thread(GameThreadProfile::loop, "pzopt-overlay-stacks");
      t.setDaemon(true);
      t.setPriority(Thread.MIN_PRIORITY);
      t.start();
      sampler = t;
      Log.info("game-thread profile: sampling the game thread's stack at " + hz() + " Hz while the overlay or its log is on");
   }

   public static boolean running() {
      return sampler != null;
   }

   private static void loop() {
      long next = System.nanoTime();
      long secondEndNs = next + 1_000_000_000L;
      HashMap<String, int[]> counts = new HashMap<>();
      HashMap<String, int[]> stacks = new HashMap<>();
      int samples = 0;
      long sampleNs = 0L, sampleMaxNs = 0L, stallSince = System.nanoTime(); // time inside the stack capture: an upper bound on the game thread's pause
      int stallSamples = 0;
      while (true) {
         long periodNs = 1_000_000_000L / hz(); // gameThreadProfileHz, changed in the tab while running
         next += periodNs;
         long wait = next - System.nanoTime();
         if (wait > 0) {
            try {
               Thread.sleep(wait / 1_000_000L, (int)(wait % 1_000_000L));
            } catch (InterruptedException e) {
               return;
            }
         } else if (wait < -periodNs * 8) {
            next = System.nanoTime(); // fell far behind (a pause, a debugger): do not burst to catch up
         }
         // devProfileLogOff: a harness run samples only while the overlay is shown (the baseline of the overlay's own cost)
         boolean wanted = enabled() && (Overlay.isVisible() || Overlay.logging() && !Config.DEV_PROFILE_LOG_OFF);
         if (wanted) {
            long t0 = System.nanoTime();
            try {
               Thread g = gameThread;
               if (g != null && Config.PROFILE_HANDSHAKE) {
                  Thread.State state = g.getState();
                  StackTraceElement[] st = g.getStackTrace();
                  if (st.length > MAX_DEPTH) {
                     st = java.util.Arrays.copyOf(st, MAX_DEPTH);
                  }
                  if (st.length > 0) {
                     classify(st, state, counts);
                     add(stacks, fold(st));
                     samples++;
                     if (Config.LUA_PROFILE) { // pzopt: which Lua functions, when the sample is inside Kahlua (LuaProfile)
                        for (StackTraceElement e : st) {
                           if (e.getClassName().startsWith("se.krka.kahlua.vm.KahluaThread")) {
                              LuaProfile.sample();
                              break;
                           }
                        }
                     }
                  }
               } else {
                  ThreadInfo ti = threads.getThreadInfo(gameThreadId, MAX_DEPTH);
                  if (ti != null) {
                     StackTraceElement[] st = ti.getStackTrace();
                     classify(st, ti.getThreadState(), counts);
                     add(stacks, fold(st));
                     samples++;
                  }
               }
            } catch (Throwable t) {
               Log.warn("game-thread profile: sampling stopped: " + t);
               return;
            }
            long dt = System.nanoTime() - t0;
            sampleNs += dt;
            sampleMaxNs = Math.max(sampleMaxNs, dt);
            stallSamples++;
         }
         if (Config.LUA_PROFILE) {
            LuaProfile.tick(System.currentTimeMillis());
         }
         long now = System.nanoTime();
         if (now - stallSince >= 10_000_000_000L) {
            if (stallSamples > 0) {
               Log.info(String.format(java.util.Locale.ROOT, "game-thread profile: %d samples in %.0f s, capture mean %.0f us, max %.0f us, %.2f %% of wall",
                     stallSamples, (now - stallSince) / 1e9, sampleNs / 1e3 / stallSamples, sampleMaxNs / 1e3, 100.0 * sampleNs / (now - stallSince)));
            }
            stallSince = now;
            sampleNs = 0L;
            sampleMaxNs = 0L;
            stallSamples = 0;
         }
         if (now >= secondEndNs) {
            secondEndNs = now + 1_000_000_000L;
            if (samples > 0) {
               Second s = new Second(System.currentTimeMillis(), samples, counts, stacks);
               int h = ringHead;
               ring[h % RING] = s;
               ringHead = h + 1;
               if (Overlay.logging()) {
                  writeSecond(s);
               }
               if (Overlay.isVisible()) {
                  publishView(h + 1);
               }
               counts = new HashMap<>();
               stacks = new HashMap<>();
               samples = 0;
            }
         }
      }
   }

   // ------------------------------------------------------------------ classification

   private static boolean isGame(String cls) {
      return cls.startsWith("zombie.") || cls.startsWith("se.krka.") || cls.startsWith("pzopt.") || cls.startsWith("fmod.");
   }

   private static boolean matches(StackTraceElement e, String cls, String method) {
      return e.getClassName().startsWith(cls) && (method == null || method.equals(e.getMethodName()));
   }

   /**
    * The game frames of a stack, root to leaf, {@code ;}-separated {@link #shortName}s: JDK / LWJGL
    * frames folded into their callers, and everything outside {@code GameWindow.frameStep} dropped
    * when it is on the stack (the frame is the flame graph's root; the limiter, menus and loading
    * keep their own roots).
    */
   static String fold(StackTraceElement[] st) {
      int start = st.length - 1;
      for (int i = st.length - 1; i >= 0; i--) {
         if (st[i].getClassName().equals("zombie.GameWindow") && st[i].getMethodName().equals("frameStep")) {
            start = i;
            break;
         }
      }
      StringBuilder sb = new StringBuilder(24 * Math.min(start + 1, 40));
      for (int i = start; i >= 0; i--) {
         if (isGame(st[i].getClassName())) {
            if (sb.length() > 0) {
               sb.append(';');
            }
            sb.append(shortName(st[i]));
         }
      }
      return sb.length() == 0 ? "(no game frame)" : sb.toString();
   }

   /** {@code Class.method} with the package dropped (inner classes keep their {@code $} suffix; {@code pzopt.} frames keep it). */
   static String shortName(StackTraceElement e) {
      String c = e.getClassName();
      String simple = c.substring(c.lastIndexOf('.') + 1) + '.' + e.getMethodName();
      return c.startsWith("pzopt.") ? "pzopt." + simple : simple; // our own frames keep the package so they stand out
   }

   private static void add(Map<String, int[]> counts, String key) {
      int[] c = counts.get(key);
      if (c == null) {
         counts.put(key, new int[] {1});
      } else {
         c[0]++;
      }
   }

   /** Folds one stack (leaf first, as {@link ThreadInfo#getStackTrace()} returns it) into the four bucket families. */
   static void classify(StackTraceElement[] st, Thread.State state, Map<String, int[]> counts) {
      String phase = null;
      String label = null;
      String containerChild = null;
      String hot = null;
      boolean inFrame = false;
      // root -> leaf: the outermost phase marker wins, the innermost label / container wins
      for (int i = st.length - 1; i >= 0; i--) {
         StackTraceElement e = st[i];
         String cls = e.getClassName();
         if (!inFrame) {
            if (cls.equals("zombie.GameWindow") && e.getMethodName().equals("frameStep")) {
               inFrame = true;
            }
            continue;
         }
         if (phase == null) {
            for (String[] p : PHASES) {
               if (matches(e, p[0], p[1])) {
                  phase = p[2];
                  break;
               }
            }
         }
         for (String[] l : LABELS) {
            if (matches(e, l[0], l[1])) {
               label = l[2];
               break;
            }
         }
         for (String[] c : CONTAINERS) {
            if (matches(e, c[0], c[1])) {
               // the game frame just inside this container names the sub-phase when no label is deeper
               containerChild = null;
               for (int j = i - 1; j >= 0; j--) {
                  if (isGame(st[j].getClassName())) {
                     containerChild = shortName(st[j]);
                     break;
                  }
               }
               label = null; // a label above this container is coarser than the container's child
               break;
            }
         }
      }
      for (StackTraceElement e : st) {
         if (isGame(e.getClassName())) {
            hot = shortName(e);
            break;
         }
      }
      if (!inFrame) {
         phase = "outside frame";
      } else if (phase == null) {
         phase = "frame other";
      }
      String sub = label != null ? label : containerChild != null ? containerChild : phase;
      String h = hot != null ? hot : "?";
      add(counts, "p:" + phase);
      add(counts, "s:" + phase + '/' + sub);
      add(counts, "l:" + phase + '/' + sub + '/' + h);
      if (state != Thread.State.RUNNABLE) {
         add(counts, "w:" + phase + '/' + sub + '/' + h);
      }
   }

   // ------------------------------------------------------------------ readers

   /** The last {@link #WINDOW_SECONDS} published seconds merged: total samples and the counts. */
   static Map<String, int[]> window(int[] samplesOut) {
      HashMap<String, int[]> merged = new HashMap<>();
      int total = 0;
      int h = ringHead;
      for (int i = 1; i <= Math.min(WINDOW_SECONDS, Math.min(h, RING)); i++) {
         Second s = ring[(h - i) % RING];
         if (s == null) {
            continue;
         }
         total += s.samples;
         for (Map.Entry<String, int[]> e : s.counts.entrySet()) {
            int[] c = merged.get(e.getKey());
            if (c == null) {
               merged.put(e.getKey(), new int[] {e.getValue()[0]});
            } else {
               c[0] += e.getValue()[0];
            }
         }
      }
      samplesOut[0] = total;
      return merged;
   }

   /** Entries whose key starts with {@code prefix}, largest first, as {key without the prefix, count}. */
   static List<Map.Entry<String, int[]>> under(Map<String, int[]> counts, String prefix) {
      ArrayList<Map.Entry<String, int[]>> out = new ArrayList<>();
      for (Map.Entry<String, int[]> e : counts.entrySet()) {
         if (e.getKey().startsWith(prefix)) {
            out.add(Map.entry(e.getKey().substring(prefix.length()), e.getValue()));
         }
      }
      out.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
      return out;
   }

   /** One row of the overlay tree: a phase (depth 0) or a sub-phase (depth 1), share of the window's samples. */
   static final class Row {
      final int depth;
      final String name;
      final float pct;
      /** Share of this row's samples that were waits (not RUNNABLE), 0..pct. */
      final float waitPct;
      /** The biggest hot methods under this row, e.g. {@code "FBORenderCell.renderOneLevel 3 %"}; may be empty. */
      final String hint;
      final float[] color;

      Row(int depth, String name, float pct, float waitPct, String hint, float[] color) {
         this.depth = depth;
         this.name = name;
         this.pct = pct;
         this.waitPct = waitPct;
         this.hint = hint;
         this.color = color;
      }
   }

   /** Phase colours; sub-phases get a stable colour from their name ({@link #nameColor}). */
   private static final float[] C_RENDER = {0.55f, 0.75f, 1f};
   private static final float[] C_UPDATE = {0.55f, 1f, 0.6f};
   private static final float[] C_LIGHTING = {1f, 0.85f, 0.4f};
   private static final float[] C_OTHER = {0.75f, 0.75f, 0.75f};
   static final float[] C_WAIT = {1f, 0.5f, 0.5f};
   static final float[] C_PZOPT = {1f, 0.55f, 1f};
   static final float[] C_OTHER_PUBLIC = C_OTHER;
   /** Twelve distinct hues for the sub-phase names, evenly spread, light enough to read on the dark panel. */
   private static final float[][] PALETTE = new float[12][];
   static {
      for (int i = 0; i < PALETTE.length; i++) {
         PALETTE[i] = hsl(i * 360f / PALETTE.length, 0.85f, 0.7f);
      }
   }

   static float[] phaseColor(String phase) {
      switch (phase) {
         case "render": return C_RENDER;
         case "update": return C_UPDATE;
         case "lighting": return C_LIGHTING;
         default: return C_OTHER;
      }
   }

   /** A stable colour per sub-phase name, so "chunk bakes" looks the same every frame and every run. */
   static float[] nameColor(String name) {
      int h = 0;
      for (int i = 0; i < name.length(); i++) {
         h = h * 31 + name.charAt(i);
      }
      h ^= h >>> 15;
      h *= 0x2c1b3c6d;
      h ^= h >>> 12;
      return PALETTE[Math.floorMod(h, PALETTE.length)];
   }

   private static float[] hsl(float h, float s, float l) {
      float c = (1f - Math.abs(2f * l - 1f)) * s;
      float hp = (h % 360f) / 60f;
      float x = c * (1f - Math.abs(hp % 2f - 1f));
      float r, g, b;
      if (hp < 1) { r = c; g = x; b = 0; }
      else if (hp < 2) { r = x; g = c; b = 0; }
      else if (hp < 3) { r = 0; g = c; b = x; }
      else if (hp < 4) { r = 0; g = x; b = c; }
      else if (hp < 5) { r = x; g = 0; b = c; }
      else { r = c; g = 0; b = x; }
      float m = l - c / 2f;
      return new float[] {r + m, g + m, b + m};
   }

   private static String pct(int count, int total) {
      return String.format(java.util.Locale.ROOT, "%.0f %%", 100f * count / total);
   }

   private static String shortHot(String hot) {
      int dot = hot.indexOf('.');
      return dot > 0 && hot.length() > 34 ? hot.substring(dot + 1) : hot;
   }

   /**
    * The tree over the window, biggest first at every level: phases with at least 1 % of the samples,
    * under each up to {@code maxSubs} sub-phases with at least 1 %, each with its wait share and its
    * {@code maxHot} hottest methods as a hint. Empty before the first second or with the profile off.
    */
   static List<Row> tree(int maxSubs, int maxHot) {
      ArrayList<Row> rows = new ArrayList<>();
      if (!enabled() || sampler == null) {
         return rows;
      }
      int[] n = new int[1];
      Map<String, int[]> w = window(n);
      int total = n[0];
      if (total == 0) {
         return rows;
      }
      for (Map.Entry<String, int[]> p : under(w, "p:")) {
         if (100 * p.getValue()[0] < total) {
            break;
         }
         String phase = p.getKey();
         int phaseWaits = 0;
         for (Map.Entry<String, int[]> x : under(w, "w:" + phase + '/')) {
            phaseWaits += x.getValue()[0];
         }
         rows.add(new Row(0, phase, 100f * p.getValue()[0] / total, 100f * phaseWaits / total, "", phaseColor(phase)));
         int shown = 0;
         for (Map.Entry<String, int[]> s : under(w, "s:" + phase + '/')) {
            if (shown >= maxSubs || 100 * s.getValue()[0] < total) {
               break;
            }
            String sub = s.getKey();
            int waits = 0;
            for (Map.Entry<String, int[]> x : under(w, "w:" + phase + '/' + sub + '/')) {
               waits += x.getValue()[0];
            }
            StringBuilder hint = new StringBuilder();
            int hots = 0;
            for (Map.Entry<String, int[]> l : under(w, "l:" + phase + '/' + sub + '/')) {
               if (hots >= maxHot || 100 * l.getValue()[0] < total) {
                  break;
               }
               hint.append(hots == 0 ? "" : "  ").append(shortHot(l.getKey())).append(' ').append(pct(l.getValue()[0], total));
               hots++;
            }
            rows.add(new Row(1, sub, 100f * s.getValue()[0] / total, 100f * waits / total, hint.toString(), nameColor(sub)));
            shown++;
         }
      }
      return rows;
   }

   /** The overlay's header line for the tree, e.g. {@code "game thread (502 stacks / 5 s)   waiting 24 %"}; empty without data. */
   static String header() {
      if (!enabled() || sampler == null) {
         return "";
      }
      int[] n = new int[1];
      Map<String, int[]> w = window(n);
      if (n[0] == 0) {
         return "";
      }
      int waits = 0;
      for (Map.Entry<String, int[]> e : under(w, "w:")) {
         waits += e.getValue()[0];
      }
      return "game thread (" + n[0] + " stacks / " + WINDOW_SECONDS + " s), most time first   waiting " + pct(waits, n[0]);
   }

   /** The two biggest sub-phases for the verdict line, e.g. {@code "chunk bakes 21 %, zombies 9 %"}; empty without data. */
   static String verdictDetail() {
      if (!enabled() || sampler == null) {
         return "";
      }
      int[] n = new int[1];
      Map<String, int[]> w = window(n);
      if (n[0] == 0) {
         return "";
      }
      List<Map.Entry<String, int[]>> subs = under(w, "s:");
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < Math.min(2, subs.size()); i++) {
         Map.Entry<String, int[]> e = subs.get(i);
         if (100 * e.getValue()[0] < 5 * n[0]) {
            break;
         }
         String name = e.getKey().substring(e.getKey().indexOf('/') + 1);
         sb.append(i == 0 ? "" : ", ").append(name).append(' ').append(pct(e.getValue()[0], n[0]));
      }
      return sb.toString();
   }

   /**
    * The flame graph of the window: a trie of the folded stacks of the last {@link #WINDOW_SECONDS}
    * seconds under a synthetic root whose count is the window's samples. Null with the profile off
    * or before the first second.
    */
   // the folded keys repeat from second to second: split each once instead of substring-ing it on every overlay refresh
   // (the flame rebuild was ~80 MB of garbage on the game thread in a 40 s Dell drive, 2026-09-22)
   private static final HashMap<String, String[]> splitCache = new HashMap<>();

   private static String[] split(String key) {
      String[] f = splitCache.get(key);
      if (f == null) {
         if (splitCache.size() > 20000) {
            splitCache.clear();
         }
         f = key.split(";");
         splitCache.put(key, f);
      }
      return f;
   }

   static Node flame() {
      if (!enabled() || sampler == null) {
         return null;
      }
      Node root = new Node("game thread");
      int h = ringHead;
      for (int i = 1; i <= Math.min(WINDOW_SECONDS, Math.min(h, RING)); i++) {
         Second s = ring[(h - i) % RING];
         if (s == null) {
            continue;
         }
         root.count += s.samples;
         for (Map.Entry<String, int[]> e : s.stacks.entrySet()) {
            int c = e.getValue()[0];
            Node n = root;
            for (String frame : split(e.getKey())) {
               n = n.kid(frame);
               n.count += c;
            }
         }
      }
      return root.count > 0 ? root : null;
   }

   // ------------------------------------------------------------------ the overlay's view

   /**
    * What the overlay shows of the profile, built on the sampler thread once per published second (the window only
    * changes then): merging the five seconds and sorting the buckets for every tree row took ~0.3 % of the game
    * thread at the overlay's 4 Hz refresh. The overlay reads the latest one.
    */
   static final class View {
      final String header;
      final List<Row> rows;
      final String detail;
      final Node flame;
      final List<Overlay.FlameBox> flameBoxes;

      View(String header, List<Row> rows, String detail, Node flame, List<Overlay.FlameBox> flameBoxes) {
         this.header = header;
         this.rows = rows;
         this.detail = detail;
         this.flame = flame;
         this.flameBoxes = flameBoxes;
      }
   }

   private static volatile View view;

   /** The latest view, or null before the first second sampled while the overlay was shown. */
   static View view() {
      return view;
   }

   private static void publishView(int second) {
      try {
         int subs = Overlay.treeSubsConfigured();
         Node root = Overlay.flameConfigured() ? flame() : null;
         view = new View(subs < 0 ? "" : header(), subs < 0 ? List.of() : tree(subs, Overlay.treeHotConfigured()), verdictDetail(),
               root, root == null ? List.of() : Overlay.flameBoxes(root));
      } catch (Throwable t) {
         Log.warn("game-thread profile: overlay view failed: " + t);
      }
   }

   // ------------------------------------------------------------------ log

   /**
    * One row per second: {@code epoch_ms<TAB>samples<TAB>key=count<TAB>...}, keys as in the class
    * comment. Written by the sampler thread while the overlay log is on; the harness copies it with
    * the other {@code pzopt-*.out} files.
    */
   private static void writeSecond(Second s) {
      if (logFailed) {
         return;
      }
      try {
         if (log == null) {
            String dir = ZomboidFileSystem.instance.getCacheDir();
            if (dir == null) {
               return;
            }
            log = new BufferedWriter(new FileWriter(new File(dir, OUT_FILE), false), 1 << 16);
            log.write("# pzopt game-thread stack profile, " + hz() + " Hz; per second: epoch_ms, samples, then key=count (p:phase, s:phase/sub-phase, l:phase/sub/hot method, w:phase/sub/method while not runnable)\n");
         }
         StringBuilder b = new StringBuilder(512);
         b.append(s.epochMs).append('\t').append(s.samples);
         ArrayList<Map.Entry<String, int[]>> entries = new ArrayList<>(s.counts.entrySet());
         entries.sort((a, c) -> Integer.compare(c.getValue()[0], a.getValue()[0]));
         for (Map.Entry<String, int[]> e : entries) {
            b.append('\t').append(e.getKey()).append('=').append(e.getValue()[0]);
         }
         b.append('\n');
         log.write(b.toString());
         log.flush();
         writeStacks(s);
      } catch (IOException | RuntimeException e) {
         logFailed = true;
         Log.warn("game-thread profile: log stopped: " + e);
      }
   }

   /**
    * The folded stacks of one second for harness/flamegraph.py: {@code f <id> <Class.method>} defines a
    * frame id the first time it appears, {@code t <epoch_ms> <samples>} opens a second, then one
    * {@code <id>;<id>;... <count>} line per distinct stack (root to leaf). Ids keep the file small
    * (a 25 s route is a few hundred KB instead of several MB of repeated method names).
    */
   private static void writeStacks(Second s) throws IOException {
      if (stackLog == null) {
         String dir = ZomboidFileSystem.instance.getCacheDir();
         stackLog = new BufferedWriter(new FileWriter(new File(dir, STACKS_FILE), false), 1 << 16);
         stackLog.write("# pzopt game-thread folded stacks, " + hz() + " Hz: 'f <id> <Class.method>' defines a frame id, 't <epoch_ms> <samples>' opens a second, '<id>;<id>;... <count>' is one stack root->leaf\n");
      }
      StringBuilder defs = new StringBuilder(); // new frame ids, written before the second that first uses them
      StringBuilder b = new StringBuilder(1 << 12);
      b.append('t').append(' ').append(s.epochMs).append(' ').append(s.samples).append('\n');
      for (Map.Entry<String, int[]> e : s.stacks.entrySet()) {
         String key = e.getKey();
         int from = 0;
         boolean first = true;
         while (from <= key.length()) {
            int semi = key.indexOf(';', from);
            String frame = semi < 0 ? key.substring(from) : key.substring(from, semi);
            Integer id = frameIds.get(frame);
            if (id == null) {
               id = frameIds.size();
               frameIds.put(frame, id);
               defs.append("f ").append(id).append(' ').append(frame).append('\n');
            }
            b.append(first ? "" : ";").append(id.intValue());
            first = false;
            if (semi < 0) {
               break;
            }
            from = semi + 1;
         }
         b.append(' ').append(e.getValue()[0]).append('\n');
      }
      stackLog.write(defs.toString());
      stackLog.write(b.toString());
      stackLog.flush();
   }

   /** Flush the per-second logs (called at quit from the harness with the overlay's; safe any time). */
   public static void flushLog() {
      for (BufferedWriter w : new BufferedWriter[] {log, stackLog}) {
         if (w != null) {
            try {
               w.flush();
            } catch (IOException ignored) {
            }
         }
      }
   }
}
