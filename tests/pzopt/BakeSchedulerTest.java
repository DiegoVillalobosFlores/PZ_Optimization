package pzopt;

import static pzopt.Check.check;

/** pzopt.BakeScheduler: must-bakes always, then overdue levels oldest first up to the hard cap, then class and distance. */
public class BakeSchedulerTest {
   public static void main(String[] args) {
      BakeScheduler s = new BakeScheduler();
      Object[] c = new Object[20];
      for (int i = 0; i < c.length; i++) {
         c[i] = new Object();
      }

      // frame 1: 3 must, 2 cutaway (dist 5, 1), 3 arrivals, budget 4 -> the 3 must plus the nearest cutaway
      s.begin(1);
      s.offer(c[0], 0, BakeScheduler.MUST, 9);
      s.offer(c[1], 0, BakeScheduler.MUST, 3);
      s.offer(c[2], 0, BakeScheduler.MUST, 1);
      s.offer(c[3], 0, BakeScheduler.CUTAWAY, 5);
      s.offer(c[4], 0, BakeScheduler.CUTAWAY, 1);
      s.offer(c[5], 0, BakeScheduler.ARRIVAL, 11);
      s.offer(c[6], 1, BakeScheduler.ARRIVAL, 11);
      s.offer(c[7], 0, BakeScheduler.LIGHT, 2);
      s.plan(4);
      check(s.granted(c[0], 0) && s.granted(c[1], 0) && s.granted(c[2], 0), "must levels are always granted");
      check(s.granted(c[4], 0) && !s.granted(c[3], 0), "the nearest cutaway takes the last credit");
      check(s.granted(c[5], 0) && s.granted(c[6], 1), "arrivals take their own quota, outside the budget");
      check(!s.granted(c[7], 0), "nothing else past the budget");
      check(!s.granted(c[6], 0), "grants are per level");

      // must levels alone past the budget: all granted, nothing else
      s.begin(2);
      for (int i = 0; i < 6; i++) {
         s.offer(c[10 + i], 0, BakeScheduler.MUST, i);
      }
      s.offer(c[3], 0, BakeScheduler.CUTAWAY, 5);
      s.plan(4);
      for (int i = 0; i < 6; i++) {
         check(s.granted(c[10 + i], 0), "must " + i);
      }
      check(!s.granted(c[3], 0), "a waiting cutaway gets no credit after the must levels used it up");

      // the held cutaway (first offered at frame 1) is overdue at frame 1 + bakeMaxWaitCutaway: it jumps the arrivals
      int f = 1 + Config.BAKE_MAX_WAIT_CUTAWAY;
      s.begin(f);
      s.offer(c[5], 0, BakeScheduler.ARRIVAL, 1);
      s.offer(c[3], 0, BakeScheduler.CUTAWAY, 5);
      s.plan(1);
      check(s.granted(c[3], 0), "overdue first");
      check(s.granted(c[5], 0), "an arrival still gets its quota");
      s.baked(c[3], 0);

      // after its bake the wait restarts: offered again, it is a fresh cutaway
      s.begin(f + 1);
      s.offer(c[3], 0, BakeScheduler.CUTAWAY, 5);
      s.offer(c[8], 0, BakeScheduler.CUTAWAY, 2);
      s.plan(1);
      check(s.granted(c[8], 0) && !s.granted(c[3], 0), "a baked level's wait restarts");

      // overdue levels are capped by bakeFrameBudgetHard; the oldest go first
      BakeScheduler t = new BakeScheduler();
      Object[] d = new Object[40];
      for (int i = 0; i < d.length; i++) {
         d[i] = new Object();
         t.begin(100 + i);
         t.offer(d[i], 0, BakeScheduler.REDRAW, 3); // first offered at frame 100 + i
         t.plan(0 + 1);
      }
      int now = 100 + 39 + Config.BAKE_MAX_WAIT_REDRAW + 1;
      t.begin(now);
      for (int i = 0; i < d.length; i++) {
         t.offer(d[i], 0, BakeScheduler.REDRAW, 3);
      }
      t.plan(1);
      int n = 0;
      boolean[] got = new boolean[d.length];
      for (int i = 0; i < d.length; i++) {
         got[i] = t.granted(d[i], 0);
         if (got[i]) {
            n++;
         }
      }
      check(n == Math.max(1, Config.BAKE_FRAME_BUDGET_HARD), "overdue grants stop at the hard cap: " + n);
      check(got[1] && !got[39], "the oldest overdue level goes first");
      check(!t.granted(d[1], 0), "a grant is used once");

      // the arrival quota takes ground levels first, then the nearest
      BakeScheduler u = new BakeScheduler();
      Object[] e = new Object[Config.BAKE_ARRIVAL_QUOTA + 3];
      for (int i = 0; i < e.length; i++) {
         e[i] = new Object();
      }
      u.begin(5000);
      u.offer(e[0], 2, BakeScheduler.ARRIVAL, 1); // an upper level, near
      for (int i = 1; i < e.length; i++) {
         u.offer(e[i], 0, BakeScheduler.ARRIVAL, 20 - i); // ground levels, farther
      }
      u.plan(1);
      int ground = 0;
      for (int i = 1; i < e.length; i++) {
         if (u.granted(e[i], 0)) {
            ground++;
         }
      }
      check(ground == Config.BAKE_ARRIVAL_QUOTA && !u.granted(e[0], 2), "ground levels before an upper one: " + ground);

      // chunkReused forgets the wait of a pooled chunk
      s.begin(1000);
      s.offer(c[9], 0, BakeScheduler.LIGHT, 1);
      s.plan(0 + 1);
      BakeScheduler.chunkReused(c[9]);
      System.out.println("BakeSchedulerTest: ok");
   }
}
