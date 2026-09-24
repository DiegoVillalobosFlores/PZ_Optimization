package pzopt;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import zombie.ai.states.FakeDeadZombieState;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.CharacterModelCamera;
import zombie.core.skinnedmodel.ModelCameraRenderData;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.math.PZMath;
import zombie.core.textures.TextureDraw;
import zombie.debug.DebugOptions;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.iso.fboRenderChunk.FBORenderCell;
import zombie.iso.sprite.IsoSprite;

/**
 * The characters draw on the other cores ({@code charDrawPrep}, 2026-09-22).
 *
 * <p>The game-thread half of drawing a character with a 3D model ({@code FBORenderCell.renderMovingObjects} ->
 * {@code IsoGameCharacter.render} -> {@code IsoSprite.renderActiveModel}) is mostly the draw data: the model's lights for
 * this player ({@code ModelInstance.updateLights}), then {@code TextureDraw.drawModel} allocates a
 * {@code ModelSlotRenderData}, fills it from the model instance and its sub-models ({@code initModel}: one render-data
 * object and a matrix palette per body / hair / clothing model) and hands the second half ({@code init}: depth, effect
 * lights, the palette into shader parameters, attachment transforms) to the game's own eight-thread executor, one task
 * and one future per character. On the Louisville horde preset that is up to 510 characters a frame and 13 % of the
 * game thread. Between the end of the update phase and this pass nothing moves a bone, so the whole thing is built
 * ahead, in three steps hooked into {@code FBORenderCell}: {@link #walk} at the top of {@code renderInternal} hands the
 * walk of the cell's objects (the same set, the same order as the stock loop; every read is of state the update phase
 * left still) to one pool task, which keeps the on-screen ones for the sequential pass and the zombies that pass
 * the model tests that do not depend on this frame's cutaway and lighting passes; {@link #start}, right before the
 * chunk bakes (after the cutaway checks, the lighting refresh and the blacked-out passes: everything the draw data
 * reads), takes the walk's result, applies the square visibility test on the game thread, runs
 * {@code checkUpdateModelTextures} (the texture creator is game-thread work) and the lazy per-square lighting refresh
 * the model lights will read (this square, and the one above on stairs: {@code JNILighting.update} with its
 * dirty-tracking side effects, game-thread work; after it the reads are plain) and hands
 * the batch to the pass's own pool ({@code charDrawThreads} daemon threads; the game's slot-init executor has eight and
 * the batch has to finish inside the bakes) chunk by chunk, each chunk's task starting while the game thread tests the
 * next: {@code updateLights} (reads only, once the game thread refreshed the squares; the override's interpolation uses
 * thread-local scratch), {@code initModel}, {@code init} and the camera record, all on the same thread per zombie,
 * while the game thread bakes chunk textures, composites, draws the players, the corpse shadows, the world items and
 * the puddles.
 * {@link #join} at {@code renderMovingObjects} waits for the tail of it (nothing, normally), then the stock loop runs
 * over the on-screen objects: every check, the alpha rules, the fire sprites, the shadows and the enqueue order are
 * unchanged; only the {@code TextureDraw.drawModel} override finds the data ready ({@link #take}, so no future for the
 * render thread) and the {@code ModelInstance.updateLights} override sees the frame stamp and skips its second run.
 *
 * <p>What stays on the stock path: players, animals, survivors, vehicles, a fake-dead zombie (drawn from the corpse
 * atlas), a culled zombie without an active model (drawn as an atlas sprite), a zombie in a vehicle or on furniture, one
 * holding a hand model (muzzle flash and weapon transforms live in shared effect state), and everything while a debug
 * mode reads the draw data on the game thread. A zombie prepared and then not drawn (the chain's decision changed
 * between the two passes; it should not) is released the way a drawn one is: a no-op drawer goes into the sprite
 * stream so the render thread runs its {@code postRender} in order.
 *
 * <p>Thread safety, per the audit of {@code initModel} / {@code init}: the pools are synchronized, the depth helper's
 * results, the matrix pools and the shader's light scratch are thread-local (the game's own executor already runs
 * {@code init} on eight threads), every read is of state the update phase left still, the one static scratch
 * ({@code ModelInstance}'s two colours) became thread-local in the override, and a zombie is handled by exactly one
 * task, so its animation player's lazy skinning-data init and bone-index cache see one writer. What must not leave
 * the game thread: the first read of a square's light info in a pass ({@code IsoGridSquare.ILighting.lightInfo()}
 * is the lazy {@code JNILighting.update} — JNI reads plus the square's field writes and pzopt's dirty-tracking
 * hooks, which the game thread's own lazy reads during the bakes would race); {@link #start} performs that read for
 * the zombie's square and the one above it, after which the worker's {@code updateLights} only reads. A task that
 * throws turns the pass off for the session and that frame draws everything through the stock path.
 */
public final class CharDraw {
   private CharDraw() {
   }

   private static final boolean ENABLED = Config.CHAR_DRAW_PREP && Config.effectiveWorkers() > 1;
   /** Build threads: the key, clamped to cores - 2 (the game and render threads keep theirs); the walk uses one of them too. */
   static final int THREADS = Math.max(1, Math.min(Config.CHAR_DRAW_THREADS, Config.CPUS - 2));
   private static ExecutorService pool; // game thread creates it on first use

   private static final ArrayList<IsoMovingObject> onScreen = new ArrayList<>(2048);
   private static final ArrayList<IsoZombie> candidates = new ArrayList<>(1024); // the walk's model zombies, before the visibility test
   private static final ArrayList<IsoZombie> batch = new ArrayList<>(1024);
   private static ModelSlotRenderData[] prepared = new ModelSlotRenderData[1024];
   private static ModelCameraRenderData[] preparedCamera = new ModelCameraRenderData[1024]; // the camera record renderActiveModel enqueues before the model, built with the draw data
   private static final AtomicInteger pending = new AtomicInteger();
   private static final AtomicInteger walkPending = new AtomicInteger();
   private static volatile boolean failed;
   private static boolean walking; // game thread: walk() ran this pass, start() has not
   private static boolean started; // game thread: start() ran this pass, join() has not
   private static boolean active; // game thread: between join() and finish(), take() may serve
   private static int stamp = 1; // the frame + player the lights were updated for (ModelInstance.pzoptLightsStamp)
   private static int frameNo;

   public static long frames, walked, onScreenTotal, batched, taken, leftovers, walkNanos, walkWaitNanos, startNanos, joinNanos, maxBatch, joinWaits, walkWaits; // counters for the log

   public static boolean enabled() {
      return ENABLED && !failed && Overrides.enabled();
   }

   /**
    * Game thread, the top of the cell render: hand the walk of the cell's objects to the executor. Nothing waits here.
    */
   public static void walk(Set<IsoMovingObject> objects, FBORenderCell cell) {
      if (started || walkPending.get() > 0) {
         return; // a pass is under way (a stale walk from a pass that never reached start is simply redone below)
      }
      if (active) {
         finish(); // the previous pass never reached finish (an exception in the loop): release its leftovers first
      }
      frames++;
      int playerIndex = IsoCamera.frameState.playerIndex;
      frameNo++;
      stamp = (frameNo << 2) | (playerIndex & 3);
      final boolean stockOnly = Core.debug || cell.renderDebugChunkState || !DebugOptions.instance.isoSprite.renderModels.getValue();
      final boolean alphaForced = IsoCamera.getCameraCharacter() != IsoPlayer.getInstance(); // IsoZombie.render sets the alpha to 1 then
      walking = true;
      walkPending.set(1);
      pool().execute(() -> runWalk(objects, stockOnly, alphaForced));
   }

   /** The pass's own daemon threads ({@code pzopt-chardraw-N}): the game's slot-init executor has eight, and the batch must finish inside the bakes. */
   private static ExecutorService pool() {
      ExecutorService p = pool;
      if (p == null) {
         final AtomicInteger n = new AtomicInteger();
         p = java.util.concurrent.Executors.newFixedThreadPool(THREADS, r -> {
            Thread t = new Thread(() -> {
               CorePlacement.background(); // macOS: utility QoS (E cores first)
               r.run();
            }, "pzopt-chardraw-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
         });
         pool = p;
         Log.info("charDrawPrep: " + THREADS + " threads for the characters draw pre-pass");
      }
      return p;
   }

   /** Executor thread: the on-screen objects in the set's order, and the zombies whose model tests pass so far. */
   private static void runWalk(Set<IsoMovingObject> objects, boolean stockOnly, boolean alphaForced) {
      long t0 = System.nanoTime();
      try {
         onScreen.clear();
         candidates.clear();
         for (IsoMovingObject object : objects) {
            walked++;
            if (object.getClass() == IsoPlayer.class || object.getCurrentSquare() == null || !object.isOnScreen()) {
               continue;
            }
            onScreen.add(object);
            if (!stockOnly && object.getClass() == IsoZombie.class && wantsModelDraw((IsoZombie)object, alphaForced)) {
               candidates.add((IsoZombie)object);
            }
         }
      } catch (Throwable t) {
         failed = true;
         Log.warn("charDrawPrep: the object walk failed, stock path from now on: " + t);
      } finally {
         walkNanos += System.nanoTime() - t0;
         walkPending.decrementAndGet();
      }
   }

   /**
    * Game thread, right before the chunk bakes (the cutaway, lighting and blacked-out passes are done): take the walk's
    * result, apply this frame's square visibility test, run the texture-creator check, and hand the batch to the
    * executor. Runs the walk inline when walk() was not called.
    */
   public static void start(Set<IsoMovingObject> objects, FBORenderCell cell) {
      if (started) {
         return;
      }
      if (!walking) {
         walk(objects, cell);
      }
      walking = false;
      long t0 = System.nanoTime();
      if (walkPending.get() > 0) {
         walkWaits++;
         int spins = 0;
         while (walkPending.get() > 0) {
            if (++spins < 200) {
               Thread.onSpinWait();
            } else {
               LockSupport.parkNanos(20_000L);
            }
         }
         walkWaitNanos += System.nanoTime() - t0;
      }
      started = true;
      batch.clear();
      if (failed) {
         onScreen.clear();
         candidates.clear();
         return;
      }
      int playerIndex = IsoCamera.frameState.playerIndex;
      // The table is grown for every candidate first (the tasks below index it), then the candidates are tested and
      // handed over in chunks, each chunk's task starting while the game thread tests the next one.
      int maxId = -1;
      for (int i = 0; i < candidates.size(); i++) {
         int id = candidates.get(i).legsSprite.modelSlot.id;
         if (id > maxId) {
            maxId = id;
         }
      }
      if (maxId >= prepared.length) {
         int size = Math.max(maxId + 1, prepared.length * 2);
         prepared = java.util.Arrays.copyOf(prepared, size);
         preparedCamera = java.util.Arrays.copyOf(preparedCamera, size);
      }
      int chunk = Math.max(16, (candidates.size() + THREADS - 1) / THREADS);
      ExecutorService exec = pool();
      int chunkStart = 0;
      pending.set(0);
      for (int i = 0; i < candidates.size(); i++) {
         IsoZombie zombie = candidates.get(i);
         IsoGridSquare square = zombie.getCurrentSquare();
         if (square != null && square.getLightInfo(playerIndex) != null && cell.pzoptShouldRenderSquare(square)) { // else renderMovingObject skips it
            zombie.checkUpdateModelTextures(); // creates the texture creator on the game thread, as the stock chain would before the draw
            // The model lights (updateLights, on the worker) read this square's lighting and, above a fraction of a level,
            // the square above's: the lazy per-square refresh behind those reads (JNILighting.update, with its
            // dirty-tracking side effects) runs here, on the game thread, so the worker's reads are plain.
            square.lighting[playerIndex].lightInfo();
            float z = zombie.getZ();
            if (z - PZMath.fastfloor(z) > 0.2F) {
               IsoGridSquare above = IsoWorld.instance.currentCell.getGridSquare(PZMath.fastfloor(zombie.getX()), PZMath.fastfloor(zombie.getY()), PZMath.fastfloor(z) + 1);
               if (above != null) {
                  above.lighting[playerIndex].lightInfo();
               }
            }

            batch.add(zombie);
         }
         if (batch.size() - chunkStart >= chunk || i == candidates.size() - 1) {
            if (batch.size() > chunkStart) {
               final int from = chunkStart;
               final int to = batch.size();
               pending.incrementAndGet();
               exec.execute(() -> buildRange(from, to));
               chunkStart = to;
            }
         }
      }
      candidates.clear();
      onScreenTotal += onScreen.size();
      int n = batch.size();
      batched += n;
      if (n > maxBatch) {
         maxBatch = n;
      }
      startNanos += System.nanoTime() - t0;
   }

   /**
    * The model tests of the stock chain that do not depend on this frame's cutaway / lighting passes (those two are
    * applied by start on the game thread): true when the chain is about to draw this zombie's model.
    */
   private static boolean wantsModelDraw(IsoZombie zombie, boolean alphaForced) {
      IsoSprite legs = zombie.legsSprite;
      if (legs == null || !legs.hasActiveModel()) {
         return false; // the atlas sprite instead of the model: most of a horde
      }
      ModelManager.ModelSlot slot = legs.modelSlot;
      if (slot == null || slot.model == null || slot.model.object == null || slot.character != zombie) {
         return false; // renderActiveModel returns
      }
      if (zombie.getCurrentState() == FakeDeadZombieState.instance()) {
         return false; // IsoZombie.render: the corpse atlas
      }
      if (!zombie.getDoRender() || (!alphaForced && zombie.isAlphaAndTargetZero())) {
         return false; // IsoGameCharacter.render
      }
      if (zombie.isSeatedInVehicle() || zombie.isSpriteInvisible() || (!alphaForced && zombie.isAlphaZero())) {
         return false;
      }
      if (zombie.sprite == null || !zombie.isbUseParts()) {
         return false;
      }
      return zombie.primaryHandModel == null && !zombie.isSittingOnFurniture(); // muzzle flash / weapon transform / seating: shared state, stock path
   }

   /** Pool thread: the draw data of the batched zombies from .. to - 1 (a chunk the game thread just handed over). */
   private static void buildRange(int from, int to) {
      try {
         ArrayList<IsoZombie> list = batch;
         for (int i = from; i < to; i++) {
            if (failed) {
               return;
            }
            build(list.get(i));
         }
      } catch (Throwable t) {
         failed = true;
         Log.warn("charDrawPrep: a draw-data task failed, stock path from now on: " + t);
      } finally {
         pending.decrementAndGet();
      }
   }

   /**
    * The draw data of one batched zombie: initModel, init (the lights were updated on the game thread in start), and
    * the camera record IsoSprite.renderActiveModel enqueues before the model (position, rendered angle; the seated and
    * furniture cases are not batched).
    */
   private static void build(IsoZombie zombie) {
      ModelManager.ModelSlot slot = zombie.legsSprite.modelSlot;
      ModelInstance model = slot.model;
      model.updateLights(); // pure reads of lighting the game thread refreshed in start (the override's interpolation uses thread-local scratch)
      model.pzoptLightsStamp = stamp;
      ModelSlotRenderData data = ModelSlotRenderData.alloc();
      boolean ok = false;
      try {
         data.initModel(slot);
         synchronized (data) {
            data.init(slot);
         }
         ok = true;
      } finally {
         if (!ok) {
            discard(data);
         }
      }
      ModelCameraRenderData camera = ModelCameraRenderData.s_pool.alloc();
      camera.init(CharacterModelCamera.instance, slot);
      preparedCamera[slot.id] = camera;
      prepared[slot.id] = data;
   }

   /**
    * Game thread, the start of renderMovingObjects: wait for the tasks (started before the chunk bakes, so normally
    * done) and return the on-screen objects for the stock per-object loop, or null when the pass failed and the stock
    * loop over the whole set must run; {@link #finish} follows the loop. Runs the whole pre-pass here when start() was
    * not called.
    */
   public static ArrayList<IsoMovingObject> join(Set<IsoMovingObject> objects, FBORenderCell cell) {
      if (!started) {
         start(objects, cell); // (runs the walk inline too when needed)
      }
      started = false;
      if (pending.get() > 0) {
         joinWaits++;
         long t0 = System.nanoTime();
         int spins = 0;
         while (pending.get() > 0) {
            if (++spins < 200) {
               Thread.onSpinWait();
            } else {
               LockSupport.parkNanos(20_000L);
            }
         }
         joinNanos += System.nanoTime() - t0;
      }
      if (failed) {
         discardAll();
         batch.clear();
         onScreen.clear();
         active = false;
         return null; // this frame draws through the stock loop; enabled() is false from now on
      }
      active = true;
      return onScreen;
   }

   /** The stock loop: true when the pre-pass built this slot's draw data this frame (and it has not been taken yet). */
   public static boolean isPrepared(ModelManager.ModelSlot slot) {
      if (!active || slot == null) {
         return false;
      }
      int id = slot.id;
      ModelSlotRenderData[] table = prepared;
      return id >= 0 && id < table.length && table[id] != null;
   }

   /** TextureDraw.drawModel: the prepared data for this slot, once, or null for the stock path. */
   public static ModelSlotRenderData take(ModelManager.ModelSlot slot) {
      if (!active) {
         return null;
      }
      int id = slot.id;
      ModelSlotRenderData[] table = prepared;
      if (id < 0 || id >= table.length) {
         return null;
      }
      ModelSlotRenderData data = table[id];
      if (data == null) {
         return null;
      }
      table[id] = null;
      taken++;
      return data;
   }

   /** IsoZombie.pzoptRenderFlat: the camera record built with this slot's draw data, once, or null (the caller builds its own). */
   public static ModelCameraRenderData takeCamera(ModelManager.ModelSlot slot) {
      if (!active) {
         return null;
      }
      int id = slot.id;
      ModelCameraRenderData[] table = preparedCamera;
      if (id < 0 || id >= table.length) {
         return null;
      }
      ModelCameraRenderData camera = table[id];
      if (camera != null) {
         table[id] = null;
      }
      return camera;
   }

   /** ModelInstance.updateLights: true when a task already ran it for this frame and player. */
   public static boolean lightsDone(ModelInstance model) {
      return active && model.pzoptLightsStamp == stamp;
   }

   /** Game thread, the end of renderMovingObjects: release whatever was prepared and not drawn, through the render thread. */
   public static void finish() {
      if (!active) {
         return;
      }
      active = false;
      for (int i = 0; i < batch.size(); i++) {
         ModelManager.ModelSlot slot = batch.get(i).legsSprite.modelSlot;
         ModelSlotRenderData data = slot == null || slot.id >= prepared.length ? null : prepared[slot.id];
         if (data != null) {
            prepared[slot.id] = null;
            leftovers++;
            slot.renderRefCount++; // what the stock enqueue counts up; the drawer's postRender counts it down
            SpriteRenderer.instance.drawGeneric(new Leftover(data));
         }
         ModelCameraRenderData camera = slot == null || slot.id >= preparedCamera.length ? null : preparedCamera[slot.id];
         if (camera != null) {
            preparedCamera[slot.id] = null;
            ModelCameraRenderData.s_pool.release(camera); // never enqueued: straight back to its pool
         }
      }
      batch.clear();
      onScreen.clear();
   }

   /** A prepared draw that was never drawn: nothing on screen, the stock postRender in the render thread's order. */
   private static final class Leftover extends TextureDraw.GenericDrawer {
      private final ModelSlotRenderData data;

      Leftover(ModelSlotRenderData data) {
         this.data = data;
      }

      @Override
      public void render() {
      }

      @Override
      public void postRender() {
         this.data.postRender();
      }
   }

   /** A task failed: give back what the batched data holds, as far as the public fields allow, and forget it. */
   private static void discardAll() {
      for (int i = 0; i < batch.size(); i++) {
         ModelManager.ModelSlot slot = batch.get(i).legsSprite.modelSlot;
         if (slot != null && slot.id < prepared.length && prepared[slot.id] != null) {
            ModelSlotRenderData data = prepared[slot.id];
            prepared[slot.id] = null;
            discard(data);
         }
         if (slot != null && slot.id < preparedCamera.length && preparedCamera[slot.id] != null) {
            ModelCameraRenderData.s_pool.release(preparedCamera[slot.id]);
            preparedCamera[slot.id] = null;
         }
      }
   }

   private static void discard(ModelSlotRenderData data) {
      try {
         ModelInstanceRenderData.release(data.modelData);
         data.modelData.clear();
         if (data.textureCreator != null) {
            data.textureCreator.renderRefCount--;
            data.textureCreator = null;
         }
      } catch (Throwable t) {
         Log.warn("charDrawPrep: discarding draw data failed: " + t);
      }
   }

   /** One line for the periodic FBORenderCell log. */
   public static String describe() {
      long f = Math.max(1L, frames);
      return "char draw: frames=" + frames + " on-screen/frame=" + (onScreenTotal / f) + " of " + (walked / f) + " batched/frame=" + (batched / f)
         + " max=" + maxBatch + " taken=" + taken + " leftovers=" + leftovers + " walk ms=" + (walkNanos / 1_000_000L) + " (worker) walk waits="
         + walkWaits + "/" + (walkWaitNanos / 1_000_000L) + " ms start ms=" + (startNanos / 1_000_000L) + " join waits=" + joinWaits + " join ms="
         + (joinNanos / 1_000_000L) + (failed ? " FAILED" : "");
   }
}
