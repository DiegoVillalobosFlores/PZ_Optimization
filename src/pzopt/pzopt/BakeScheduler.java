package pzopt;

import java.util.Arrays;
import java.util.IdentityHashMap;

/**
 * bakeScheduler (2026-09-24, the Rosewood 120 km/h drive): one budget for every chunk-level bake of a frame.
 *
 * <p>The drive baked nothing in 63 % of its frames and 10-120 levels in others (GPU 3-16 ms, render thread 5-12 ms in
 * one frame): each dirt reason had its own rule (creations counted by bakeBudget, lighting and redraw spread by their
 * budgets, cutaway / object / tree changes and first bakes of pooled chunk levels never held), so the kinds that arrive
 * together (a chunk row crossed every ~270 ms at 120 km/h, a building entering or leaving the collapse set while driving
 * past it) stacked. Here FBORenderCell offers every dirty on-screen level once a frame, before the chunk loop, with its
 * class and its chunk's distance to the camera character; {@link #plan} grants:
 * <ol>
 * <li>{@link #MUST}: an existing texture whose objects, items, trees or obscuring changed: always (a stale one would show
 *     the object neither baked nor drawn per frame);</li>
 * <li>up to {@code bakeArrivalQuota} never-textured levels (a hole on screen until baked), lowest level first, then
 *     nearest, outside the budget;</li>
 * <li>levels past their class's longest wait ({@code bakeMaxWait*}), by class then oldest first, up to
 *     {@code bakeFrameBudgetHard};</li>
 * <li>the rest by class (cutaway, never-textured, strong lighting, redraw, lighting drift), nearest first within a
 *     class, until {@code bakeFrameBudget} bakes are granted this frame.</li>
 * </ol>
 * With {@code bakeSmooth} the last step grants only the least number a frame that still meets every waiting level's longest
 * wait (demand bound, earliest deadline first), never more than the budget.
 * A level not granted keeps its previous texture on screen (FBORenderCell's deferral path); a never-textured one is not
 * drawn, which at the edge of the loaded grid looks like the unloaded ground beyond it. Waits are frames since the level
 * was first offered dirty.
 */
public final class BakeScheduler {
   public static final boolean ON = Config.BAKE_SCHEDULER && Overrides.enabled();
   public static final int MUST = 0, CUTAWAY = 1, ARRIVAL = 2, STRONG = 3, REDRAW = 4, LIGHT = 5, CLASSES = 6;
   /**
    * seamDirections: the redraw a neighbour loading on a side no seam of the level reads (north, west, the diagonals) sets
    * this pzopt dirty bit instead of DIRTY_REDRAW; stock never looks at it, the scheduler bakes it with lighting drift.
    */
   public static final long DIRTY_SEAM_LOW = 1L << 20;
   private static final String[] NAMES = {"must", "cutaway", "arrival", "strong", "redraw", "light"};
   private static final BakeScheduler[] players = new BakeScheduler[4];

   private Object[] chunk = new Object[256]; // IsoChunk, compared by identity
   private int[] level = new int[256];
   private int[] cls = new int[256];
   private int[] dist = new int[256];
   private int[] wait = new int[256];
   private long[] key = new long[256];
   private int n;
   private int frame;
   private final IdentityHashMap<Object, int[]> dirtySince = new IdentityHashMap<>(); // [li] = first frame offered dirty, 0 = none; [64] = last frame seen
   private final IdentityHashMap<Object, long[]> granted = new IdentityHashMap<>(); // [0] bit li = granted this frame; [1 + li] = its class
   private final IdentityHashMap<Object, int[]> arrivalGrant = new IdentityHashMap<>(); // [li] = frame of the last arrival grant (repeat stat)
   private final long[] used = new long[CLASSES], unused = new long[CLASSES];
   private long arrivalRepeats;
   // counters for the periodic log line
   private final long[] offered = new long[CLASSES], grantedN = new long[CLASSES], overdue = new long[CLASSES], waitSum = new long[CLASSES];
   private final int[] waitMax = new int[CLASSES];
   private long frames, deferredFrames, maxGranted, grantsMade, grantsUsed;

   public static BakeScheduler get(int playerIndex) {
      BakeScheduler s = players[playerIndex];
      if (s == null) {
         s = players[playerIndex] = new BakeScheduler();
      }
      return s;
   }

   /** Game thread, before the offers of a frame. */
   public void begin(int frameNo) {
      this.frame = frameNo;
      this.n = 0;
      for (long[] g : this.granted.values()) {
         long bits = g[0];
         while (bits != 0L) {
            int li = Long.numberOfTrailingZeros(bits);
            bits &= bits - 1;
            this.unused[(int)g[1 + li]]++;
         }
      }
      this.granted.clear();
      if ((frameNo & 255) == 0) {
         // chunks no longer offered (baked clean, gone off screen, pooled) for a few seconds leave the table
         this.dirtySince.values().removeIf(a -> frameNo - a[64] > 2000);
      }
   }

   public void offer(Object c, int lvl, int klass, int distance) {
      int li = lvl + 32;
      if (li < 0 || li >= 64) {
         return;
      }
      int[] since = this.dirtySince.computeIfAbsent(c, k -> new int[65]);
      if (since[li] == 0) {
         since[li] = this.frame;
      }
      since[64] = this.frame;
      if (this.n == this.chunk.length) {
         int m = this.n * 2;
         this.chunk = Arrays.copyOf(this.chunk, m);
         this.level = Arrays.copyOf(this.level, m);
         this.cls = Arrays.copyOf(this.cls, m);
         this.dist = Arrays.copyOf(this.dist, m);
         this.wait = Arrays.copyOf(this.wait, m);
         this.key = Arrays.copyOf(this.key, m);
      }
      int i = this.n++;
      this.chunk[i] = c;
      this.level[i] = lvl;
      this.cls[i] = klass;
      this.dist[i] = distance;
      this.wait[i] = this.frame - since[li];
      this.offered[klass]++;
   }

   private static int maxWait(int klass) {
      return switch (klass) {
         case CUTAWAY -> Config.BAKE_MAX_WAIT_CUTAWAY;
         case STRONG -> Config.BAKE_MAX_WAIT_STRONG;
         case ARRIVAL -> Config.BAKE_MAX_WAIT_ARRIVAL;
         case REDRAW -> Config.BAKE_MAX_WAIT_REDRAW;
         case LIGHT -> Config.BAKE_MAX_WAIT_LIGHT;
         default -> 0;
      };
   }

   private int budgetNow = -1;
   private int calmFrames;
   private long budgetSum;

   /**
    * bakeBudgetAdaptive: this frame's budget from the last frame's cost (AIMD): the game step without the limiter wait
    * (FrameCap.lastStepNs) or the render thread's acquire-to-swap time (Pacing.lastSubmitNs) above bakeBudgetHighPct (90) % of
    * the cap interval halves it (never under bakeFrameBudgetMin); both under bakeBudgetLowPct (60) % for 4 frames in a row add
    * one (never over bakeFrameBudget).
    * Uncapped or without the timings it is bakeFrameBudget.
    */
   public int budget(long intervalNs) {
      int b = adaptiveBudget(intervalNs);
      if (Config.BAKE_DEADLINE_PCT > 0 && intervalNs > 0L) {
         // bakeDeadlinePct: this step already used that share of its interval before the render phase (a heavy update:
         // chunk arrivals, lighting, Lua): the normal tier waits; must, arrival quota and overdue levels still go
         long elapsed = System.nanoTime() - Pacing.stepStartNs();
         if (elapsed > intervalNs * Config.BAKE_DEADLINE_PCT / 100) {
            this.deadlineFrames++;
            return 0;
         }
      }
      return b;
   }

   private long deadlineFrames;

   private int adaptiveBudget(long intervalNs) {
      int max = Config.BAKE_FRAME_BUDGET;
      if (!Config.BAKE_BUDGET_ADAPTIVE || intervalNs <= 0L) {
         return max;
      }
      int min = Math.min(max, Config.BAKE_FRAME_BUDGET_MIN);
      if (this.budgetNow < 0) {
         this.budgetNow = max;
      }
      long cost = Math.max(FrameCap.lastStepNs, Pacing.lastSubmitNs);
      if (cost > intervalNs * Config.BAKE_BUDGET_HIGH_PCT / 100) {
         this.budgetNow = Math.max(min, this.budgetNow / 2);
         this.calmFrames = 0;
      } else if (cost < intervalNs * Config.BAKE_BUDGET_LOW_PCT / 100) {
         if (++this.calmFrames >= 4) {
            this.calmFrames = 0;
            this.budgetNow = Math.min(max, this.budgetNow + 1);
         }
      } else {
         this.calmFrames = 0;
      }
      this.budgetSum += this.budgetNow;
      return this.budgetNow;
   }

   private final int[] slackHist = new int[1024];
   private long smoothSum;

   /** Grants this frame's bakes (see the class comment); {@code budget} is this frame's bakeFrameBudget. */
   public void plan(int budget) {
      int given = 0;
      int arrivals = 0;
      boolean smooth = Config.BAKE_SMOOTH;
      int[] h = this.slackHist;
      if (smooth) {
         Arrays.fill(h, 0);
      }
      // sort keys: tier (0 must, 1 arrival quota, 2 overdue, 3 normal), then within overdue the longest wait, else class then
      // distance (bakeSmooth: the frames left before the level's longest wait first, then class, then distance)
      for (int i = 0; i < this.n; i++) {
         int k = this.cls[i];
         long tier;
         long sub;
         if (k == MUST) {
            tier = 0;
            sub = this.dist[i];
         } else if (k == ARRIVAL && Config.BAKE_ARRIVAL_QUOTA > 0) {
            tier = 1; // the arrival quota: ground level first (no upper floor floating over an unbaked ground), then the nearest
            sub = (long)(this.level[i] + 32) << 12 | Math.min(this.dist[i], 0xFFF);
         } else if (this.wait[i] >= maxWait(k)) {
            tier = 2;
            sub = (long)k << 12 | 0xFFFL - Math.min(this.wait[i], 0xFFF); // class first (an overdue arrival before an overdue redraw), then the longest wait
         } else if (smooth) {
            tier = 3;
            int slack = Math.min(maxWait(k) - this.wait[i], h.length - 1); // >= 1: not overdue
            h[slack]++;
            sub = (long)slack << 15 | (long)k << 12 | Math.min(this.dist[i], 0xFFF);
         } else {
            tier = 3;
            sub = (long)k << 12 | Math.min(this.dist[i], 0xFFF);
         }
         this.key[i] = tier << 48 | sub << 16 | i;
      }
      Arrays.sort(this.key, 0, this.n);
      int hard = Math.max(budget, Config.BAKE_FRAME_BUDGET_HARD);
      if (smooth) {
         // demand bound: the levels due within d frames need ceil(count / d) bakes a frame from now on; the largest over d
         // is the least rate that meets every deadline, so a backlog drains evenly instead of in budget-sized bursts
         int need = Config.BAKE_SMOOTH_MIN;
         int due = 0;
         for (int d = 1; d < h.length; d++) {
            due += h[d];
            if (due > 0) {
               need = Math.max(need, (due + d - 1) / d);
            }
         }
         budget = Math.min(budget, need);
         this.smoothSum += budget;
      }
      for (int j = 0; j < this.n; j++) {
         int i = (int)(this.key[j] & 0xFFFF);
         long tier = this.key[j] >>> 48;
         boolean grant = tier == 0 || tier == 1 && arrivals < Config.BAKE_ARRIVAL_QUOTA || tier == 2 && given < hard || tier == 3 && given < budget;
         if (!grant) {
            continue;
         }
         if (tier == 1) {
            arrivals++; // outside the budget: a never-textured level is a hole on screen
         } else {
            given++;
         }
         int k = this.cls[i];
         this.grantedN[k]++;
         if (tier == 2) {
            this.overdue[k]++;
         }
         this.waitSum[k] += this.wait[i];
         this.waitMax[k] = Math.max(this.waitMax[k], this.wait[i]);
         long[] g = this.granted.computeIfAbsent(this.chunk[i], c -> new long[65]);
         g[0] |= 1L << (this.level[i] + 32);
         g[1 + this.level[i] + 32] = k;
         if (k == ARRIVAL) {
            int[] last = this.arrivalGrant.computeIfAbsent(this.chunk[i], c -> new int[64]);
            int li = this.level[i] + 32;
            if (last[li] != 0 && this.frame - last[li] < 2400) {
               this.arrivalRepeats++;
            }
            last[li] = this.frame;
         }
      }
      this.frames++;
      this.grantsMade += given + arrivals;
      if (given + arrivals < this.n) {
         this.deferredFrames++;
      }
      this.maxGranted = Math.max(this.maxGranted, given + arrivals);
   }

   /** Whether the level holds a grant this frame, without using it (the bake preparation asks first). */
   public boolean peek(Object c, int lvl) {
      long[] g = this.granted.get(c);
      return g != null && (g[0] & 1L << (lvl + 32)) != 0L;
   }

   /** renderOneLevel's decision for a level offered this frame; a true answer is counted as a used grant. */
   public boolean granted(Object c, int lvl) {
      long[] g = this.granted.get(c);
      long bit = 1L << (lvl + 32);
      if (g != null && (g[0] & bit) != 0L) {
         g[0] &= ~bit; // once: a second ask this frame (none expected) is not a second bake
         this.grantsUsed++;
         this.used[(int)g[1 + lvl + 32]]++;
         return true;
      }
      return false;
   }

   /** The level's texture was baked: its wait starts again with its next dirt. */
   public void baked(Object c, int lvl) {
      int[] since = this.dirtySince.get(c);
      int li = lvl + 32;
      if (since != null && li >= 0 && li < 64) {
         since[li] = 0;
      }
   }

   /** A chunk object is being reused for another position. */
   public static void chunkReused(Object c) {
      for (BakeScheduler s : players) {
         if (s != null) {
            s.dirtySince.remove(c);
            s.arrivalGrant.remove(c); // the repeat stat is per chunk position
         }
      }
   }

   public String summary() {
      StringBuilder sb = new StringBuilder(" | bake scheduler: arrival repeats=").append(this.arrivalRepeats).append(" unused by class=");
      for (int k = 0; k < CLASSES; k++) {
         sb.append(k == 0 ? "" : "/").append(this.unused[k]);
      }
      sb.append(" frames=").append(this.frames).append(" held=").append(this.deferredFrames)
         .append(" max/frame=").append(this.maxGranted).append(" grants used=").append(this.grantsUsed).append('/').append(this.grantsMade)
         .append(" budget avg=").append(this.frames == 0 ? 0 : String.format(java.util.Locale.ROOT, "%.1f", this.budgetSum / (double)this.frames))
         .append(" deadline holds=").append(this.deadlineFrames)
         .append(" smooth avg=").append(this.frames == 0 ? 0 : String.format(java.util.Locale.ROOT, "%.2f", this.smoothSum / (double)this.frames));
      for (int k = 0; k < CLASSES; k++) {
         if (this.offered[k] == 0) {
            continue;
         }
         sb.append(' ').append(NAMES[k]).append('=').append(this.grantedN[k]).append('/').append(this.offered[k]).append(" (overdue ")
            .append(this.overdue[k]).append(", wait avg ").append(this.grantedN[k] == 0 ? 0 : this.waitSum[k] / this.grantedN[k]).append(" max ")
            .append(this.waitMax[k]).append(')');
      }
      Arrays.fill(this.offered, 0L);
      Arrays.fill(this.used, 0L);
      Arrays.fill(this.unused, 0L);
      this.arrivalRepeats = 0L;
      Arrays.fill(this.grantedN, 0L);
      Arrays.fill(this.overdue, 0L);
      Arrays.fill(this.waitSum, 0L);
      Arrays.fill(this.waitMax, 0);
      this.frames = this.deferredFrames = this.maxGranted = this.grantsMade = this.grantsUsed = this.budgetSum = this.smoothSum = this.deadlineFrames = 0L;
      return sb.toString();
   }
}
