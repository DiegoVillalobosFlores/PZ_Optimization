package pzopt;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import zombie.characters.IsoZombie;
import zombie.core.physics.RagdollController;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.iso.IsoWorld;

/**
 * Harness-only ragdoll monitor (2026-09-24, the "ragdolls slide, spin, sink and fly" player report): with
 * {@code showcase=horde} every zombie whose ragdoll controller is live is followed frame by frame on the game thread, and
 * each ragdoll episode (controller initialised → released, or the zombie gone: turned into a corpse) becomes one line of
 * {@code pzopt-ragdoll.out}. The numbers are the ones a player sees go wrong:
 * <ul>
 * <li>{@code pelvis_speed}: the fastest pelvis movement over 50 ms windows (tiles/s): a body flying off;</li>
 * <li>{@code late_travel}: pelvis travel after the first {@link #SETTLE_S} s: a body sliding along the ground;</li>
 * <li>{@code late_yaw}: body-axis (pelvis → head) rotation after {@link #SETTLE_S} s, and {@code yaw_rate} the fastest over
 *     100 ms windows (degrees, degrees/s): a body spinning;</li>
 * <li>{@code net_late}: the straight-line pelvis displacement from {@link #SETTLE_S} s to the end (tiles).</li>
 * <li>{@code z_min} / {@code z_max}: the pelvis height over the floor the ragdoll started on (levels): under the floor or
 *     up in the air;</li>
 * <li>{@code sep}: the widest horizontal gap between the pelvis and the character's position (tiles): the body leaving
 *     its character;</li>
 * <li>{@code char_travel}: how far the character itself moved during the episode (tiles).</li>
 * </ul>
 * Travel and turn are summed over 50 ms samples, not per frame: summed per frame the bones' sub-millimetre jitter grew
 * with the frame rate (hs-opt-a/b at ~290 fps showed 2-4x stock's travel at its 60 fps cap with the same net movement).
 * A {@code state} line every 2 s carries the live count and the running totals (the last one is the run's summary). The
 * thresholds that call an episode abnormal live in harness/ragdoll-judge.py, calibrated on stock runs.
 */
public final class RagdollWatch {
   private RagdollWatch() {
   }

   static final float SETTLE_S = 1.5F;
   private static final zombie.iso.Vector3 HEAD = new zombie.iso.Vector3();

   private static final class Ep {
      int id;
      long t0Ns, lastNs, lastSpeedNs;
      boolean deadAtStart, dead, calculated;
      float cx0, cy0, cz0, floor;
      float px, py, pz, spx, spy, syaw, yaw0, lateX = Float.NaN, lateY;
      float pelvisSpeed, travel, lateTravel, lateYaw, yawRate, netLate, zMin = Float.MAX_VALUE, zMax = -Float.MAX_VALUE, sep;
      int frames;
      String outfit;
   }

   private static final IdentityHashMap<IsoZombie, Ep> live = new IdentityHashMap<>();
   private static final ArrayList<IsoZombie> seen = new ArrayList<>();
   private static java.io.PrintWriter out;
   private static long startNs, lastStateNs;
   private static int episodes, deaths, nextId;
   private static int flagFly, flagSlide, flagDrift, flagSpin, flagUnder, flagAbove;

   /** Scene start (game thread). */
   static void start() {
      startNs = System.nanoTime();
      try {
         java.io.File f = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt-ragdoll.out");
         out = new java.io.PrintWriter(new java.io.FileWriter(f, false));
         out.println("# pzopt.RagdollWatch: one ep line per ragdoll episode, a state line every 2 s; settings=" + (Overrides.enabled() ? "optimized" : "stock-path")
               + " animatorParallel=" + AnimParallel.enabled());
         out.flush();
      } catch (java.io.IOException e) {
         Log.warn("harness: ragdoll watch: cannot write pzopt-ragdoll.out: " + e);
      }
   }

   /** Every frame while the scene runs (game thread). */
   static void tick(long nowNs) {
      if (out == null) {
         return;
      }
      seen.clear();
      ArrayList<IsoZombie> list = IsoWorld.instance.getCell().getZombieList();
      for (int i = 0; i < list.size(); i++) {
         IsoZombie z = list.get(i);
         AnimationPlayer ap = z == null ? null : z.getAnimationPlayer();
         RagdollController rc = ap == null ? null : ap.getRagdollController();
         if (rc == null || !rc.isInitialized()) {
            continue;
         }
         seen.add(z);
         Ep e = live.get(z);
         if (e == null) {
            e = new Ep();
            e.id = ++nextId;
            e.t0Ns = e.lastSpeedNs = nowNs;
            e.deadAtStart = z.isDead();
            e.cx0 = z.getX();
            e.cy0 = z.getY();
            e.cz0 = z.getZ();
            e.floor = (float)Math.floor(e.cz0 + 0.01F);
            e.outfit = z.getOutfitName();
            live.put(z, e);
         }
         sample(z, rc, e, nowNs);
      }
      for (Iterator<Map.Entry<IsoZombie, Ep>> it = live.entrySet().iterator(); it.hasNext(); ) {
         Map.Entry<IsoZombie, Ep> me = it.next();
         if (!containsIdentity(seen, me.getKey())) {
            end(me.getKey(), me.getValue(), nowNs);
            it.remove();
         }
      }
      if (nowNs - lastStateNs >= 2_000_000_000L) {
         lastStateNs = nowNs;
         out.printf(java.util.Locale.ROOT, "state t=%.1f live=%d episodes=%d deaths=%d active_sims=%d off_thread=%d fly=%d slide=%d drift=%d spin=%d under=%d above=%d zombies=%d%n",
               (nowNs - startNs) / 1e9, live.size(), episodes, deaths, RagdollController.getNumberOfActiveSimulations(), AnimParallel.ragdollOffThreadCount(),
               flagFly, flagSlide, flagDrift, flagSpin, flagUnder, flagAbove, list.size());
         out.flush();
      }
   }

   private static boolean containsIdentity(ArrayList<IsoZombie> l, IsoZombie z) {
      for (int i = 0; i < l.size(); i++) {
         if (l.get(i) == z) return true;
      }
      return false;
   }

   private static void sample(IsoZombie z, RagdollController rc, Ep e, long nowNs) {
      e.dead |= z.isDead();
      if (!rc.getRagdollStateData().isCalculated) {
         e.lastNs = nowNs;
         return; // the bone positions are not computed yet (first frames)
      }
      float px = rc.getPelvisPositionX(), py = rc.getPelvisPositionY(), pz = rc.getPelvisPositionZ();
      zombie.iso.Vector3 head = rc.getHeadPosition(HEAD);
      float yaw = (float)Math.toDegrees(Math.atan2(head.y - py, head.x - px));
      float age = (nowNs - e.t0Ns) / 1e9F;
      e.frames++;
      if (!e.calculated) {
         e.calculated = true;
         e.spx = px;
         e.spy = py;
         e.yaw0 = e.syaw = yaw;
         e.lastSpeedNs = nowNs;
      }
      if (nowNs - e.lastSpeedNs >= 50_000_000L) { // fixed-rate samples: travel, speed and turn do not grow with the fps
         float step = (float)Math.hypot(px - e.spx, py - e.spy);
         float dyaw = Math.abs(((yaw - e.syaw) % 360F + 540F) % 360F - 180F);
         e.pelvisSpeed = Math.max(e.pelvisSpeed, step / ((nowNs - e.lastSpeedNs) / 1e9F));
         e.yawRate = Math.max(e.yawRate, dyaw / ((nowNs - e.lastSpeedNs) / 1e9F));
         e.travel += step;
         if (age > SETTLE_S) {
            e.lateTravel += step;
            e.lateYaw += dyaw;
            if (Float.isNaN(e.lateX)) {
               e.lateX = e.spx;
               e.lateY = e.spy;
            }
            e.netLate = (float)Math.hypot(px - e.lateX, py - e.lateY);
         }
         e.spx = px;
         e.spy = py;
         e.syaw = yaw;
         e.lastSpeedNs = nowNs;
      }
      e.zMin = Math.min(e.zMin, pz - e.floor);
      e.zMax = Math.max(e.zMax, pz - e.floor);
      e.sep = Math.max(e.sep, (float)Math.hypot(px - z.getX(), py - z.getY()));
      e.px = px;
      e.py = py;
      e.pz = pz;
      e.lastNs = nowNs;
   }

   private static void end(IsoZombie z, Ep e, long nowNs) {
      episodes++;
      boolean dead = e.dead || z.isDead();
      if (dead) deaths++;
      float charTravel = (float)Math.hypot(z.getX() - e.cx0, z.getY() - e.cy0);
      // the same limits as harness/ragdoll-judge.py's defaults, for the live counters only
      if (e.pelvisSpeed > 12F) flagFly++;
      if (e.lateTravel > 3F) flagSlide++;
      if (e.netLate > 2F) flagDrift++;
      if (e.lateYaw > 540F) flagSpin++;
      if (e.calculated && e.zMin < -0.25F) flagUnder++;
      if (e.calculated && e.zMax > 1.2F) flagAbove++;
      out.printf(java.util.Locale.ROOT,
            "ep id=%d t=%.2f dur=%.2f frames=%d dead=%b dead_at_start=%b calculated=%b pelvis_speed=%.2f travel=%.2f late_travel=%.2f net_late=%.2f late_yaw=%.0f yaw_rate=%.0f z_min=%.2f z_max=%.2f sep=%.2f char_travel=%.2f floor=%.0f at=%.1f,%.1f outfit=%s%n",
            e.id, (e.t0Ns - startNs) / 1e9, (nowNs - e.t0Ns) / 1e9, e.frames, dead, e.deadAtStart, e.calculated, e.pelvisSpeed, e.travel, e.lateTravel, e.netLate, e.lateYaw, e.yawRate,
            e.calculated ? e.zMin : 0F, e.calculated ? e.zMax : 0F, e.sep, charTravel, e.floor, e.cx0, e.cy0, e.outfit);
   }
}
