package pzopt;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import zombie.characters.animals.pathfind.AnimalPathfind;
import zombie.core.ThreadGroups;
import zombie.iso.IsoChunk;

/**
 * Bounded worker pool for the pooled part of a chunk's recalc pass
 * (IsoChunk.recalcPooled: everything after the RecalcProperties loop).
 *
 * The streamer thread keeps the queue, the disk read and loop 1, then calls
 * {@link #submit}. Workers run the pass; an {@link OrderedPublisher} hands
 * finished chunks to the game thread (IsoChunk.loadGridSquare) in submission
 * order. A chunk whose pass threw is not published: it is queued for the
 * streamer thread, which re-runs the full stock pass on it ({@link #runRetries})
 * and publishes the result, so the world never sees a half-recalculated chunk.
 */
public final class RecalcPool {
   /** Bookkeeping carried from submit to publish. */
   public static final class Task {
      final IsoChunk chunk;
      final Stats.Timing timing;
      OrderedPublisher.Entry<Task> entry;

      Task(IsoChunk chunk, Stats.Timing timing) {
         this.chunk = chunk;
         this.timing = timing;
      }
   }

   private static final int WIDTH = Config.effectiveWorkers();
   /** Width while a world loads (the initial chunk map is hundreds of chunks with nothing else to do): loadWorkers. */
   private static final int LOAD_WIDTH = WIDTH > 1 ? Math.max(WIDTH, Config.LOAD_WORKERS) : 1;
   private static volatile ThreadPoolExecutor executor;
   private static final AtomicInteger threadIndex = new AtomicInteger();
   private static final ConcurrentLinkedQueue<Task> retries = new ConcurrentLinkedQueue<>();
   private static final OrderedPublisher<Task> publisher = new OrderedPublisher<>(RecalcPool::publish, RecalcPool::failed);

   private RecalcPool() {
   }

   /** True when the pass should be handed to workers rather than run inline. */
   public static boolean active() {
      return WIDTH > 1;
   }

   public static int width() {
      return WIDTH;
   }

   private static ExecutorService executor() {
      ThreadPoolExecutor e = executor;
      if (e == null) {
         synchronized (RecalcPool.class) {
            e = executor;
            if (e == null) {
               AnimalPathfind.getInstance(); // lazy singleton reached from RecalcProperties; initialise it here, once
               int width = loading() ? LOAD_WIDTH : WIDTH;
               e = new ThreadPoolExecutor(width, width, 10L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
                  Thread t = new Thread(ThreadGroups.Workers, () -> {
                     CorePlacement.background(); // macOS: utility QoS (E cores first)
                     r.run();
                  }, Guard.WORKER_PREFIX + threadIndex.getAndIncrement());
                  t.setDaemon(true);
                  t.setPriority(Thread.NORM_PRIORITY);
                  return t;
               });
               e.allowCoreThreadTimeOut(true); // the extra load-time threads go away 10 s after the load
               executor = e;
               Log.info("recalc pool started with " + width + " workers (" + WIDTH + " in play, " + LOAD_WIDTH + " while loading)");
            }
         }
      }
      // grow for a world load, shrink back for play; a fixed queue, so idle threads simply time out
      int want = loading() ? LOAD_WIDTH : WIDTH;
      if (e.getCorePoolSize() != want) {
         synchronized (RecalcPool.class) {
            if (e.getCorePoolSize() != want) {
               if (want > e.getMaximumPoolSize()) {
                  e.setMaximumPoolSize(want);
                  e.setCorePoolSize(want);
               } else {
                  e.setCorePoolSize(want);
                  e.setMaximumPoolSize(want);
               }
               Log.info("recalc pool width " + want + (want == LOAD_WIDTH && want != WIDTH ? " (world loading)" : ""));
            }
         }
      }
      return e;
   }

   /** True while GameLoadingState's loader thread exists (set in enter(), cleared in exit()). */
   private static boolean loading() {
      return LOAD_WIDTH != WIDTH && zombie.gameStates.GameLoadingState.loader != null;
   }

   /** Streamer thread: loop 1 is done; run the rest on a worker and publish in order. */
   public static void submit(IsoChunk chunk, Stats.Timing timing) {
      Task task = new Task(chunk, timing);
      task.entry = publisher.submit(task);
      executor().execute(() -> run(task));
   }

   private static void run(Task task) {
      Throwable failure = null;
      try {
         task.timing.recalcStartNs = System.nanoTime();
         task.timing.thread = Thread.currentThread().getName();
         task.chunk.recalcPooled();
         task.timing.recalcEndNs = System.nanoTime();
      } catch (Throwable t) {
         failure = t;
      }
      publisher.complete(task.entry, failure);
   }

   private static void publish(OrderedPublisher.Entry<Task> e) {
      Task task = e.item;
      Parity.capture(task.chunk);
      IsoChunk.loadGridSquare.add(task.chunk);
      task.timing.publishNs = System.nanoTime();
      Stats.done(task.chunk, task.timing);
   }

   private static void failed(OrderedPublisher.Entry<Task> e) {
      Task task = e.item;
      Log.error("recalc of chunk " + task.chunk.wx + "," + task.chunk.wy + " failed on " + Thread.currentThread().getName()
            + ": " + e.failure + "; will retry the full pass on the streamer thread");
      retries.add(task);
      StreamerWake.signal();
   }

   /** Streamer thread, each loop: re-run the stock pass for failed chunks and publish them. */
   public static void runRetries() {
      Task task;
      while ((task = retries.poll()) != null) {
         try {
            task.timing.recalcStartNs = System.nanoTime();
            task.timing.thread = Thread.currentThread().getName() + "(retry)";
            task.chunk.loadInWorldStreamerThread();
            task.timing.recalcEndNs = System.nanoTime();
         } catch (Throwable ex) {
            // Throwable, not Exception: a StackOverflowError here (stock isWallTo recursing on a mis-mapped
            // square) once escaped past publish()/resolve() and left the publisher blocked on this entry,
            // so no later chunk was ever handed to the main thread. Log and publish what we have, as stock would.
            zombie.core.logger.ExceptionLogger.logException(ex);
            Log.error("retry of chunk " + task.chunk.wx + "," + task.chunk.wy + " failed too; publishing as stock would");
         }
         publish(task.entry);
         publisher.resolve(task.entry);
      }
   }

   public static int inFlight() {
      return publisher.inFlight() + retries.size();
   }

   /** Streamer thread on stop: wait for every submitted chunk to be published. */
   public static void drain() {
      long deadline = System.currentTimeMillis() + 30_000L;
      while (inFlight() > 0 && System.currentTimeMillis() < deadline) {
         runRetries();
         try {
            Thread.sleep(2L);
         } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
         }
      }
      if (inFlight() > 0) {
         Log.error("recalc pool drain timed out with " + inFlight() + " chunks in flight");
      }
   }

   /** Test hook: simulate a worker throwing for the given chunk coordinates (dev builds only). */
   public static volatile String failChunk = System.getProperty("pzopt.failChunk", HarnessFlags.get("fail_chunk", ""));
}
