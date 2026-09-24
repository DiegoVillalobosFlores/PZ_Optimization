package pzopt;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * The per-frame worker pool of the zombie passes (2026-09-22, docs/plan-zombie-multithread.md §4.1).
 *
 * <p>One batch at a time: the game thread hands over {@code count} indexed tasks and a {@link Runner}, the daemon
 * workers ({@code pzopt-frame-N}, {@code frameThreads} of them, clamped to cores - 1) and the game thread itself pull
 * indices from a shared cursor until none are left, and {@link #run} returns when every task has finished. The wake is
 * a generation counter under one monitor (~50 µs), the join spins briefly then parks in 20 µs slices; a batch of a
 * few milliseconds hides both. {@code AnimBatch} (the bone math) and {@code ActionEval} (the action-context transitions)
 * are the two users; the game thread never blocks inside a batch on anything but the join.
 *
 * <p>A task that throws marks the batch failed; the runner decides what that means (both users switch themselves off
 * for the rest of the session and take the stock path).
 */
public final class FrameBatch {
   private FrameBatch() {
   }

   /** One indexed task. */
   public interface Runner {
      void run(int index) throws Throwable;
   }

   /**
    * A frame worker. The per-thread fields replace game statics a worker must not flip (animatorParallel): the
    * {@code PerformanceSettings.interpolateAnims} switch of the model-less update and the anim-event capture of the
    * character being updated on this thread.
    */
   public static final class Worker extends Thread {
      public boolean noInterpolate; // animatorParallel: this thread's keyframe sampling behaves as interpolateAnims=false
      public AnimCapture capture; // animatorParallel: the character whose anim events this thread is capturing, or null

      Worker(Runnable r, String name) {
         super(r, name);
      }
   }

   public static final int THREADS = Math.max(1, Math.min(Config.FRAME_THREADS, Config.CPUS - 1));

   /**
    * One batch: its runner, size and counters in one object, published to the workers through {@link #current}. A worker
    * that wakes late works on the batch object it read, whose cursor is exhausted, and never touches a newer batch's
    * counters with an older batch's runner (the first version kept the fields loose and a late worker once read a null
    * runner against the next batch's cursor: an NPE and a task counted as finished without running, 2026-09-22).
    */
   private static final class Batch {
      final Runner runner;
      final int count;
      final AtomicInteger cursor = new AtomicInteger();
      final AtomicInteger finished = new AtomicInteger();
      volatile Throwable failure;

      Batch(Runner runner, int count) {
         this.runner = runner;
         this.count = count;
      }

      void work() {
         int i;
         while ((i = this.cursor.getAndIncrement()) < this.count) {
            try {
               this.runner.run(i);
            } catch (Throwable t) {
               if (this.failure == null) {
                  this.failure = t;
               }
            } finally {
               this.finished.incrementAndGet();
            }
         }
      }
   }

   private static final Object gate = new Object();
   private static volatile int generation; // written under gate; volatile so a spinning worker sees a new batch without the monitor
   private static volatile Batch current; // the batch the workers should join
   private static Thread[] workers;
   private static boolean running; // game thread only

   public static long batches, tasks, waitNanos, workNanos;
   public static long asyncBatches, asyncJoinNanos, asyncHelped; // animBatchAsync: batches started without a join, game-thread time at their joins, tasks the game thread took at the join

   /** Called on the game thread when an asynchronous batch has been joined, with its first failure or null. */
   public interface Completion {
      void done(Throwable failure);
   }

   private static Batch pending; // game thread only: the asynchronous batch not joined yet
   private static Completion pendingDone;

   /** True while an asynchronous batch may still be running (game thread only). */
   public static boolean hasPending() {
      return pending != null;
   }

   /**
    * Game thread: start tasks 0..count-1 on the workers only and return at once (animBatchAsync). The batch is joined by
    * {@link #join()}, which every later {@link #run} / {@link #runAsync} does first, so at most one batch is ever in
    * flight and the one-batch-at-a-time rule of the workers holds. {@code done} runs on the game thread at the join.
    */
   public static void runAsync(int n, Runner r, Completion done) {
      join();
      if (n <= 0) {
         done.done(null);
         return;
      }
      if (running) {
         throw new IllegalStateException("FrameBatch.runAsync inside a batch");
      }
      if (workers == null) {
         start();
      }
      batches++;
      tasks += n;
      asyncBatches++;
      Batch batch = new Batch(r, n);
      pending = batch;
      pendingDone = done;
      current = batch;
      synchronized (gate) {
         generation++;
         gate.notifyAll();
      }
   }

   public static long helped; // tasks of an asynchronous batch the game thread ran while waiting for one (helpOne)

   /**
    * Game thread, while waiting for a task of the asynchronous batch: claim the next task no worker has started and run
    * it here. False when every task has been claimed (the caller spins for the one it waits for).
    */
   public static boolean helpOne() {
      Batch batch = pending;
      if (batch == null) {
         return false;
      }
      int i = batch.cursor.getAndIncrement();
      if (i >= batch.count) {
         return false;
      }
      helped++;
      try {
         batch.runner.run(i);
      } catch (Throwable t) {
         if (batch.failure == null) {
            batch.failure = t;
         }
      } finally {
         batch.finished.incrementAndGet();
      }
      return true;
   }

   /**
    * Game thread: finish the asynchronous batch, if one is in flight: take whatever tasks no worker has started yet, wait
    * for the rest, then run its completion. Cheap when nothing is pending.
    */
   public static void join() {
      Batch batch = pending;
      if (batch == null) {
         return;
      }
      pending = null;
      Completion done = pendingDone;
      pendingDone = null;
      long t0 = System.nanoTime();
      int before = batch.cursor.get();
      batch.work();
      if (before < batch.count) {
         asyncHelped += batch.count - Math.max(before, 0);
      }
      int spins = 0;
      while (batch.finished.get() < batch.count) {
         if (++spins < 200) {
            Thread.onSpinWait();
         } else {
            LockSupport.parkNanos(20_000L);
         }
      }
      asyncJoinNanos += System.nanoTime() - t0;
      done.done(batch.failure);
   }

   /**
    * Game thread: run tasks 0..count-1 on the workers and this thread, wait for all of them, and return the first
    * exception a task threw (null when every task finished).
    */
   public static Throwable run(int n, Runner r) {
      if (n <= 0) {
         return null;
      }
      if (running) {
         throw new IllegalStateException("FrameBatch.run is not reentrant");
      }
      join(); // animBatchAsync: one batch in flight at a time
      if (workers == null) {
         start();
      }
      running = true;
      long t0 = System.nanoTime();
      batches++;
      tasks += n;
      Batch batch = new Batch(r, n);
      current = batch;
      synchronized (gate) {
         generation++;
         gate.notifyAll();
      }
      batch.work();
      long t1 = System.nanoTime();
      int spins = 0;
      while (batch.finished.get() < n) {
         if (++spins < 200) {
            Thread.onSpinWait();
         } else {
            LockSupport.parkNanos(20_000L);
         }
      }
      long t2 = System.nanoTime();
      workNanos += t1 - t0;
      waitNanos += t2 - t1;
      running = false;
      return batch.failure;
   }

   private static void start() {
      workers = new Thread[THREADS];
      for (int k = 0; k < THREADS; k++) {
         Thread t = new Worker(FrameBatch::workerLoop, "pzopt-frame-" + k);
         t.setDaemon(true);
         t.setPriority(Thread.NORM_PRIORITY);
         workers[k] = t;
         t.start();
      }
      Log.info("frameThreads: " + THREADS + " worker threads for the per-frame zombie batches");
   }

   private static final long SPIN_NANOS = Config.FRAME_SPIN_US * 1000L;

   private static void workerLoop() {
      CorePlacement.background(); // macOS: utility QoS (E cores first)
      int seen = 0;
      while (true) {
         // frameSpinUs: the frame's batches come back to back (separation, transitions, animators, bones, lighting);
         // a worker that just finished one spins a little for the next instead of parking, so it starts within
         // microseconds instead of queueing on the monitor behind the others
         if (SPIN_NANOS > 0) {
            long until = System.nanoTime() + SPIN_NANOS;
            while (generation == seen && System.nanoTime() < until) {
               Thread.onSpinWait();
            }
         }
         if (generation != seen) {
            seen = generation;
            Batch batch = current;
            if (batch != null) {
               batch.work();
               PoolStats.flush(); // poolStatsBatched: this worker's pool counts of the batch
            }
            continue;
         }
         synchronized (gate) {
            while (generation == seen) {
               try {
                  gate.wait();
               } catch (InterruptedException e) {
                  return;
               }
            }
            seen = generation;
         }
         Batch batch = current;
         if (batch != null) {
            batch.work();
            PoolStats.flush(); // poolStatsBatched
         }
      }
   }

   /** One line for the periodic FBORenderCell log. */
   public static String describe() {
      return "frame batches=" + batches + " tasks=" + tasks + " work ms=" + (workNanos / 1_000_000L) + " wait ms=" + (waitNanos / 1_000_000L)
            + " async=" + asyncBatches + " helped=" + helped + " async join ms=" + (asyncJoinNanos / 1_000_000L) + " async helped=" + asyncHelped;
   }
}
