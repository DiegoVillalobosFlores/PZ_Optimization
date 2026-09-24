package pzopt;

import java.util.IdentityHashMap;
import java.util.List;
import zombie.characters.IsoZombie;
import zombie.core.PerformanceSettings;
import zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator;
import zombie.core.skinnedmodel.advancedanimation.AnimCondition;
import zombie.core.skinnedmodel.advancedanimation.AnimLayer;
import zombie.core.skinnedmodel.advancedanimation.AnimNode;
import zombie.core.skinnedmodel.advancedanimation.AnimState;
import zombie.core.skinnedmodel.advancedanimation.AnimTransition;
import zombie.core.skinnedmodel.advancedanimation.AnimationSet;
import zombie.core.skinnedmodel.advancedanimation.LiveAnimNode;
import zombie.core.skinnedmodel.animation.AnimationTrack;

/**
 * The zombies' animator and model update on the frame workers ({@code animatorParallel}, 2026-09-22 night).
 *
 * <p>After {@link ActionEval} has evaluated every queued zombie's transitions, stock finishes each zombie in turn on the
 * game thread: the state change ({@code ActionContext.update}), the animator ({@code AdvancedAnimator.update}: node
 * conditions, blend weights, transitions, the anim events of the node timelines), the move deltas and the model update
 * (the track tick, which fires the track events, then the bone math that {@link AnimBatch} already moved). That was 9-12
 * % of the game thread on the Louisville horde. The animator and the tick only touch the character's own animation
 * objects, read-only anim sets, thread-safe pools and its variables; what they reach outside the character goes through
 * the anim events. So the finish is split in three:
 * <ol>
 * <li>game thread, queue order: the state change, and the eligibility test below; an eligible zombie is armed;</li>
 * <li>frame workers: each armed zombie's animator, move deltas and model update, with its anim events captured
 *     ({@link AnimCapture}) instead of dispatched and the tracks it removes held unreleased;</li>
 * <li>game thread, queue order: an armed zombie gets its captured events dispatched (stock's handlers: sounds,
 *     footsteps, variables, the state's anim-event hook, the context's event set) around the context's event clear, as
 *     stock orders them; a zombie that was not armed runs its animator and model update here, as stock.</li>
 * </ol>
 * The bone batch collects during step 2 as it did during stock's loop and starts after step 3.
 *
 * <p>Exactness: a zombie whose animator step fired an event (a footstep, a variable set, a sound) stops on the worker
 * right after the animator and does the rest on the game thread once its events are dispatched, so everything after
 * the animator sees the handlers' effects like stock. Events of the track tick are dispatched after the tick instead of
 * inside it (the tick of the other tracks of the same character no longer sees a handler's effect in the same frame).
 *
 * <p>Not armed: players, animals, anything {@link AnimBatch} would not batch (a ragdoll, a recorder, a parent player),
 * a character with no square, and a zombie whose current anim states (or its live nodes) name a variable callback with
 * side effects in a condition, a scalar, a speed scale or a track-time variable ({@link ActionEval#impureCallback}):
 * those callbacks clear the target, drop the thump target, rescan corpses or run a pathfind line test, and must run on
 * the game thread in stock's order. The callbacks themselves carry a guard ({@link #impureGuard}): reached on a
 * worker anyway, they throw before any side effect and the zombie finishes on the game thread; the count is in the log
 * ({@code impure=}) and has to stay 0.
 */
public final class AnimParallel {
   private AnimParallel() {
   }

   private static final boolean ENABLED = Config.ANIMATOR_PARALLEL && Config.ACTION_EVAL_PARALLEL && Config.effectiveWorkers() > 1;
   private static volatile boolean failed;

   private static IsoZombie[] work = new IsoZombie[1024];
   private static int workCount;
   private static Thread gameThread;
   private static boolean gameNoInterpolate; // the game thread running a worker task (it joins the batch)
   private static AnimCapture gameCapture;

   public static long frames, armed, serialEvents, serialOther, notArmed, statesUnsafe, impureTouches, failures;

   public static boolean enabled() {
      return ENABLED && !failed && Overrides.enabled();
   }

   /** Thrown by an impure callback reached on a worker, before its side effect. */
   public static final class ImpureTouch extends RuntimeException {
      ImpureTouch() {
         super("impure anim-variable callback reached on a frame worker", null, false, false);
      }
   }

   private static final ImpureTouch IMPURE = new ImpureTouch();

   /** First line of every variable callback with side effects (IsoZombie override). */
   public static void impureGuard(String name) {
      if (currentCapture() != null) {
         impureTouches++;
         if (impureTouches <= 10) {
            Log.warn("animatorParallel: impure callback " + name + " reached on a worker; that zombie finishes on the game thread");
         }
         throw IMPURE;
      }
   }

   /**
    * Right before the side effect of a guarded callback (ActionEval.GUARDED_CALLBACKS): off the game thread, in the
    * transition evaluation or the animator task, throw before it; the caller's fallback runs it on the game thread.
    */
   public static void conditionalGuard(String name) {
      if (currentCapture() != null) {
         impureGuard(name); // the animator task: counted as impure=, finishes on the game thread
      } else if (ActionEval.currentSnapshot() != null && Thread.currentThread() != gameThread) {
         throw IMPURE; // the transition evaluation: counted by ActionEval as guardedFallbacks=
      }
   }

   /** The capture of the character this thread is updating in the batch, or null. */
   public static AnimCapture currentCapture() {
      Thread t = Thread.currentThread();
      if (t instanceof FrameBatch.Worker w) {
         return w.capture;
      }
      return t == gameThread ? gameCapture : null;
   }

   /** AnimationTrack's keyframe sampling: stock's static switch, or false on a thread doing a model-less update. */
   public static boolean interpolateAnims() {
      Thread t = Thread.currentThread();
      if (t instanceof FrameBatch.Worker w) {
         // a worker: the switch as it was when the batch started (animatorPipeline: the game thread may be inside
         // stock's flip for a zombie it updates itself), off during this thread's model-less update
         return w.capture != null ? baseInterpolate && !w.noInterpolate : PerformanceSettings.interpolateAnims;
      }
      if (!PerformanceSettings.interpolateAnims) {
         return false;
      }
      return !(t == gameThread && gameNoInterpolate);
   }

   /** True when this thread is capturing: the caller must not release the track now (AnimationMultiTrack override). */
   public static boolean deferRelease(AnimationTrack track) {
      AnimCapture c = currentCapture();
      if (c == null) {
         return false;
      }
      c.deferRelease(track);
      return true;
   }

   /** IsoGameCharacter.updateAnimPlayer on a batch thread: the per-thread form of stock's interpolateAnims flip. Returns true when it applied. */
   public static boolean setNoInterpolate(boolean on) {
      Thread t = Thread.currentThread();
      if (t instanceof FrameBatch.Worker w) {
         w.noInterpolate = on;
         return true;
      }
      if (t == gameThread && gameCapture != null) {
         gameNoInterpolate = on;
         return true;
      }
      return false;
   }

   static void enter(AnimCapture c) {
      Thread t = Thread.currentThread();
      if (t instanceof FrameBatch.Worker w) {
         w.capture = c;
      } else {
         gameCapture = c;
      }
   }

   static void exit() {
      Thread t = Thread.currentThread();
      if (t instanceof FrameBatch.Worker w) {
         w.capture = null;
         w.noInterpolate = false;
      } else {
         gameCapture = null;
         gameNoInterpolate = false;
      }
   }

   /** Called by the worker task of one zombie (IsoGameCharacter.pzoptRestWorker) around its update. */
   public static void begin(AnimCapture c) {
      enter(c);
   }

   public static void end() {
      exit();
   }

   /**
    * Game thread, ActionEval's apply phase: finish the queued zombies (their evaluation is done) with the animator and
    * model update of the eligible ones on the workers.
    */
   /** True when ActionEval should hand its evaluations to the pipelined apply (animatorPipeline). */
   public static boolean pipelined() {
      return PIPELINE && enabled();
   }

   public static void apply(IsoZombie[] queue, int count, boolean evalPending) {
      gameThread = Thread.currentThread();
      frames++;
      if (workCount != 0) {
         java.util.Arrays.fill(work, 0, workCount, null);
      }
      workCount = 0;
      if (work.length < count) {
         work = new IsoZombie[Math.max(count, work.length * 2)];
      }

      // 1. the state changes, in order, and who may go to the workers
      int gen = ActionEval.generation();
      for (int i = 0; i < count; i++) {
         IsoZombie z = queue[i];
         if (evalPending) {
            zombie.characters.action.ActionContext ctx = z.getActionContext();
            if (ctx.pzoptEvalDone != gen) {
               waitEval(ctx, gen);
            }
         }
         ActionEval.useMultiplier(z); // the zombie's simulation-level multiplier for its state change and its time step
         z.pzoptRestContext();
         if (eligible(z)) {
            z.pzoptCapture().arm(z.getAnimationTimeDelta());
            work[workCount++] = z;
            armed++;
         } else {
            notArmed++;
         }
      }

      // 2. the animators and models on the workers
      baseInterpolate = PerformanceSettings.interpolateAnims;
      boolean pipelined = PIPELINE && workCount > 0;
      if (pipelined) {
         // animatorPipeline: the workers alone run the batch (in queue order, the cursor hands out ascending indices)
         // while this thread finishes each zombie in step 3 as soon as its task is done, instead of taking tasks itself
         batchStart = System.nanoTime();
         firstStart.set(Long.MAX_VALUE);
         FrameBatch.runAsync(workCount, j -> {
            if (Thread.currentThread() != gameThread) {
               long now = System.nanoTime();
               long cur;
               while (now < (cur = firstStart.get()) && !firstStart.compareAndSet(cur, now)) {
               }
               workerTasks.incrementAndGet();
            }
            long t0 = System.nanoTime();
            work[j].pzoptRestWorker();
            taskNanos.addAndGet(System.nanoTime() - t0);
         }, AnimParallel::pipelineDone);
      } else if (workCount > 0) {
         Throwable t = FrameBatch.run(workCount, j -> work[j].pzoptRestWorker());
         if (t != null && !failed) {
            failed = true; // stock path from the next frame on; this frame's zombies finish below with what they have
            Log.warn("animatorParallel: a worker task failed, off for the session: " + t);
         }
      }

      // 3. the events and the rest, in order
      for (int i = 0; i < count; i++) {
         IsoZombie z = queue[i];
         ActionEval.useMultiplier(z); // the event handlers (ThumpState counts strikes over it) and a serial finish run under it
         AnimCapture c = z.pzoptCaptureIfAny();
         if (c != null && c.armed) {
            if (pipelined && !c.done) {
               waitDone(c);
            }
            if (c.serial) {
               if (c.impure) {
                  serialOther++;
               } else if (c.failure != null) {
                  failures++;
                  serialOther++;
               } else {
                  serialEvents++;
               }
            }
            z.pzoptRestFinish(c);
         } else {
            z.pzoptRestAnimatorAndAfter();
         }
      }
      if (pipelined) {
         long fs = firstStart.get();
         if (fs != Long.MAX_VALUE) {
            firstStartNanos += fs - batchStart;
         }
         spanNanos += System.nanoTime() - batchStart;
         FrameBatch.join(); // every task has finished (each was waited for above); returns the batch's bookkeeping
      }
      java.util.Arrays.fill(work, 0, workCount, null);
      workCount = 0;
   }

   private static final boolean PIPELINE = Config.ANIMATOR_PIPELINE;
   private static volatile boolean baseInterpolate = true; // stock's interpolateAnims at the batch start (the game thread may flip the static meanwhile)
   public static long pipelineWaits, pipelineWaitNanos, firstStartNanos, spanNanos;
   private static long batchStart;
   private static final java.util.concurrent.atomic.AtomicLong firstStart = new java.util.concurrent.atomic.AtomicLong();
   public static final java.util.concurrent.atomic.AtomicLong taskNanos = new java.util.concurrent.atomic.AtomicLong();
   public static final java.util.concurrent.atomic.AtomicLong workerTasks = new java.util.concurrent.atomic.AtomicLong();

   public static long evalWaits, evalWaitNanos;

   private static void waitEval(zombie.characters.action.ActionContext ctx, int gen) {
      long t0 = System.nanoTime();
      evalWaits++;
      int spins = 0;
      while (ctx.pzoptEvalDone != gen) {
         if (FrameBatch.helpOne()) {
            continue;
         }
         if (++spins < 500) {
            Thread.onSpinWait();
         } else {
            java.util.concurrent.locks.LockSupport.parkNanos(5_000L);
         }
      }
      evalWaitNanos += System.nanoTime() - t0;
   }

   private static void waitDone(AnimCapture c) {
      long t0 = System.nanoTime();
      pipelineWaits++;
      int spins = 0;
      while (!c.done) {
         if (FrameBatch.helpOne()) {
            continue; // an unstarted zombie (possibly this one) done here instead of idling
         }
         if (++spins < 500) {
            Thread.onSpinWait();
         } else {
            java.util.concurrent.locks.LockSupport.parkNanos(5_000L);
         }
      }
      pipelineWaitNanos += System.nanoTime() - t0;
   }

   private static void pipelineDone(Throwable t) {
      if (t != null && !failed) {
         failed = true;
         Log.warn("animatorParallel: a worker task failed, off for the session: " + t);
      }
   }

   private static int ragdollOffThread;

   /** Any thread: true on a frame worker (the game thread is known once the pipeline ran), where Bullet must not be called. */
   public static boolean offGameThread() {
      Thread g = gameThread;
      return g != null && Thread.currentThread() != g;
   }

   /**
    * Any thread, from the AnimationPlayer override where a ragdoll controller is made or stepped (both call into the
    * game's Bullet library): counts and logs (first 5, with the stack) a call that is not on the game thread. Evidence
    * rig for the Bullet calculateSimulationIslands crash while shooting a burning horde (showcase runs, 2026-09-24).
    */
   public static void noteRagdoll(String where) {
      Thread t = Thread.currentThread();
      if (gameThread == null || t == gameThread) {
         return;
      }
      int n;
      synchronized (AnimParallel.class) {
         n = ++ragdollOffThread;
      }
      if (n <= 5) {
         Log.warn("animatorParallel: ragdoll " + where + " on " + t.getName() + " (not the game thread), #" + n);
         StackTraceElement[] st = new Throwable().getStackTrace();
         StringBuilder sb = new StringBuilder();
         for (int i = 1; i < Math.min(st.length, 14); i++) sb.append("\n    at ").append(st[i]);
         Log.warn("animatorParallel: ragdoll stack" + sb);
      }
   }

   private static boolean eligible(IsoZombie z) {
      zombie.core.skinnedmodel.animation.AnimationPlayer p = z.getAnimationPlayer();
      if (p == null || !p.pzoptBatchable() || z.isAnimationRecorderActive() || z.getCurrentSquare() == null || !z.pzoptDeltasSeeded()) {
         return false;
      }
      if (z.isBeingGrappled() || z.isGrappling() || z.getReanimatedPlayer() != null) {
         return false;
      }
      AdvancedAnimator a = z.getAdvancedAnimator();
      if (a == null) {
         return false;
      }
      if (!statesSafe(a)) {
         statesUnsafe++;
         return false;
      }
      return true;
   }

   // Game thread only.
   private static final IdentityHashMap<AnimationSet, IdentityHashMap<String, Boolean>> stateSafe = new IdentityHashMap<>();
   private static final IdentityHashMap<AnimNode, Boolean> nodeSafe = new IdentityHashMap<>();
   private static final IdentityHashMap<AnimationSet, Boolean> setSafe = new IdentityHashMap<>();

   private static boolean statesSafe(AdvancedAnimator a) {
      AnimationSet set = a.animSet;
      if (set == null) {
         return true; // the animator returns at once
      }
      if (!ActionEval.callbackKeysReady()) {
         return false;
      }
      Boolean whole = setSafe.get(set);
      if (whole == null) {
         // every node of every state of the set: when all are safe, no state or live node of this set needs a look again
         boolean ok = true;
         for (AnimState state : set.states.values()) {
            for (int i = 0; i < state.nodes.size(); i++) {
               ok &= nodeSafe(state.nodes.get(i)); // no short cut: the log names every unsafe variable once
            }
         }
         whole = ok;
         setSafe.put(set, whole);
         Log.info("animatorParallel: anim set " + set.name + (ok ? " is safe for the workers as a whole" : " has side-effecting states; checked per state"));
      }
      if (whole) {
         return true;
      }
      if (!layerSafe(set, a.getRootLayer())) {
         return false;
      }
      int n = a.getSubLayerCount();
      for (int i = 0; i < n; i++) {
         if (!layerSafe(set, a.getSubLayerAt(i))) {
            return false;
         }
      }
      return true;
   }

   private static boolean layerSafe(AnimationSet set, AnimLayer layer) {
      if (layer == null) {
         return true;
      }
      String name = layer.hasState() ? layer.getCurrentStateName() : "Idle"; // no state: the animator transitions the root to Idle
      if (name == null) {
         return false;
      }
      IdentityHashMap<String, Boolean> states = stateSafe.computeIfAbsent(set, k -> new IdentityHashMap<>());
      Boolean safe = states.get(name);
      if (safe == null) {
         AnimState state = set.GetState(name);
         boolean ok = true;
         if (state != null) {
            for (int i = 0; i < state.nodes.size() && ok; i++) {
               ok = nodeSafe(state.nodes.get(i));
            }
         }
         safe = ok;
         states.put(name, safe);
      }
      if (!safe) {
         return false;
      }
      List<LiveAnimNode> live = layer.getLiveAnimNodes();
      for (int i = 0; i < live.size(); i++) {
         AnimNode source = live.get(i).getSourceNode();
         if (source != null && !nodeSafe(source)) {
            return false;
         }
      }
      return true;
   }

   private static boolean nodeSafe(AnimNode node) {
      Boolean cached = nodeSafe.get(node);
      if (cached != null) {
         return cached;
      }
      boolean ok = conditionsSafe(node.conditions) & nameSafe(node.scalar) & nameSafe(node.scalar2) & nameSafe(node.speedScale)
            & nameSafe(node.speedScaleVariable) & nameSafe(node.trackTimeToVariable);
      for (int i = 0; i < node.transitions.size(); i++) {
         AnimTransition t = node.transitions.get(i);
         ok &= t == null || conditionsSafe(t.conditions);
      }
      nodeSafe.put(node, ok);
      return ok;
   }

   private static boolean conditionsSafe(AnimCondition[] conditions) {
      if (conditions == null) {
         return true;
      }
      boolean ok = true;
      for (AnimCondition c : conditions) {
         if (c != null) {
            ok &= nameSafe(c.name);
         }
      }
      return ok;
   }

   private static boolean nameSafe(String name) {
      if (name == null || name.isEmpty()) {
         return true;
      }
      char c0 = name.charAt(0);
      if (c0 == '-' || c0 == '.' || Character.isDigit(c0)) {
         return true; // a number (speed scale "1.00")
      }
      if (!ActionEval.impureCallback(name) || GUARDED.contains(name.toLowerCase(java.util.Locale.ENGLISH))) {
         return true;
      }
      if (unsafeNames.add(name.toLowerCase(java.util.Locale.ENGLISH))) {
         Log.info("animatorParallel: anim variable " + name + " is a side-effecting callback; the states that read it stay on the game thread");
      }
      return false;
   }

   /**
    * Callbacks whose side effect is conditional and guarded right before it (IsoZombie override): bHasTarget and
    * shouldSprint only clear a target that has become a reanimated corpse, otherwise they are pure reads. On a worker the
    * rare side-effect case throws before it (impure= in the log), so the states reading them may go to the workers.
    */
   private static final java.util.Set<String> GUARDED = java.util.Set.of("bhastarget", "shouldsprint", "bpassengerexposed", "battack", "bthump", "blunge", "battackvehicle", "beatbodytarget", // ActionEval.GUARDED_CALLBACKS
      // BaseGrappleable.RegisterGrappleVariables (audit 2026-09-23): getters of the character's own grapple fields and a
      // bearing from two positions; a grappled or grappling zombie is never armed anyway
      "bdograpple", "bdocontinuegrapple", "bisgrappling", "grappleresult", "sharedgrappletype", "sharedgrappleanimnode",
      "sharedgrappletime", "sharedgrapplefraction", "grappleoffsetforward", "grappleoffsetbehaviour", "bearingtograppledtarget",
      "bbeinggrappled", "grappledby", "grappledbytype", "grapplegrabanim", "grappleanim", "anygrappleanim",
      // FallDamage.registerVariableCallbacks (audit 2026-09-23): reads of its own severity fields
      "blandlight", "blandlightmask", "bhardfall", "bhardfall2", "fallimpactseverity");

   private static final java.util.HashSet<String> unsafeNames = new java.util.HashSet<>();

   /** One line for the periodic FBORenderCell log. */
   public static String describe() {
      return "animator parallel: frames=" + frames + " armed=" + armed + " notArmed=" + notArmed + " statesUnsafe=" + statesUnsafe
            + " evalWaits=" + evalWaits + " evalWait ms=" + (evalWaitNanos / 1_000_000L) + " pipelineWaits=" + pipelineWaits + " pipelineWait ms=" + (pipelineWaitNanos / 1_000_000L) + " firstWorkerStart ms=" + (firstStartNanos / 1_000_000L) + " span ms=" + (spanNanos / 1_000_000L) + " workerTasks=" + workerTasks.get() + " task ms=" + (taskNanos.get() / 1_000_000L) + " serialEvents=" + serialEvents + " serialOther=" + serialOther + " impure=" + impureTouches + " failures=" + failures
            + (failed ? " FAILED" : "");
   }
}
