package pzopt;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjglx.opengl.Display;
import zombie.GameWindow;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.RenderThread;
import zombie.input.ControllerStateCache;
import zombie.input.GameKeyboard;
import zombie.input.MouseStateCache;

/**
 * Input latency (2026-09-24, harness/inputlag.py): the input a game frame reads, as fresh as the frame can have it.
 *
 * <p>Stock: the render thread pumps OS events (glfwPollEvents) and polls the keyboard, mouse and pad caches once, right
 * after its buffer swap; a polled state is frozen until the game thread swaps it in at the start of its next
 * {@code logic()}. Everything that happens between that swap and the game's next frame start (the frame limiter's idle
 * time: ~3 ms of a 4.2 ms frame at 240 fps, ~14 ms at 60 fps) waits one more frame.
 *
 * <ul>
 * <li>{@code keyboardFresh}: GameKeyboard reads the poll it swaps in (stock read the previous one, then swapped: the
 * keyboard ran a frame behind the mouse and the pad).</li>
 * <li>{@code inputLatch}: the game thread, just before its input swaps, asks the render thread for a fresh pump + poll
 * ({@link #latch}); an idle render thread (waiting for the next game frame) is woken on the sprite-state monitor and
 * serves it in its wait callback ({@link #serve}), a busy one serves it after its swap. The game thread waits at most
 * {@code inputLatchWaitUs}. Re-polling keeps a key or button that went down after the first poll down until the game has
 * seen it and adds the mouse wheel up (KeyboardState / MouseState overrides).</li>
 * <li>{@code frameStartGate}: before the input swap, the game thread waits for the render thread to take the previous
 * frame (the wait stock does in pushFrameDown, after the frame is built on stale input), so a render- or GPU-bound
 * frame samples its input when the pipeline has room for it.</li>
 * </ul>
 */
public final class InputLatch {
   public static final boolean KEYBOARD_FRESH = Config.KEYBOARD_FRESH && Overrides.enabled();
   public static final boolean LATCH = Config.INPUT_LATCH && Overrides.enabled();
   public static final boolean GATE = Config.FRAME_START_GATE && Overrides.enabled();
   private static final long WAIT_NS = Config.INPUT_LATCH_WAIT_US * 1000L;
   /** Right-mouse hold before aiming (zombie.input.Mouse.isRightDelay); stock 0.15 s tells a context-menu click from an aim. */
   public static final float AIM_HOLD_S = Overrides.enabled() ? Math.max(0, Config.AIM_HOLD_MS) / 1000.0F : 0.15F;

   /** Render thread between frames (waiting for the next one, or on the GPU fence): it can serve a latch at once. */
   public static volatile boolean renderIdle;
   private static final AtomicLong requested = new AtomicLong();
   private static final AtomicLong served = new AtomicLong();
   private static MouseStateCache mouseCache;
   private static ControllerStateCache padCache;
   private static boolean broken;
   private static long frames, timeouts, waitNsSum, gateNsSum, lastLogNs, latched;

   private InputLatch() {
   }

   /** Game thread, GameWindow.logic just before the Mouse / GameKeyboard / GameInput swaps. */
   public static void beforeInputSwap() {
      LowLatency.beforeInputSample();
      if (!LATCH && !GATE) {
         return;
      }
      long t0 = System.nanoTime();
      if (GATE) {
         gate();
      }
      long t1 = System.nanoTime();
      boolean ok = true;
      if (LATCH && !broken && RenderThread.renderThread != null && renderIdle) { // a busy render thread (drawing, blocked in a vsync swap) serves it after its swap anyway
         latched++;
         long seq = requested.incrementAndGet();
         Object m = SpriteRenderer.instance.states;
         synchronized (m) {
            m.notifyAll(); // wakes the render thread's wait for a ready state; its callback serves the request
         }
         long deadline = t1 + WAIT_NS;
         while (served.get() < seq) {
            if (System.nanoTime() > deadline) {
               ok = false;
               break;
            }
            Thread.onSpinWait();
         }
      }
      long t2 = System.nanoTime();
      frames++;
      if (!ok) {
         timeouts++;
      }
      gateNsSum += t1 - t0;
      waitNsSum += t2 - t1;
      if (t2 - lastLogNs > 10_000_000_000L) {
         if (lastLogNs != 0L) {
            Log.info(String.format("input latch: %d frames (%d latched), gate %.3f ms/frame, latch wait %.3f ms/frame, %d not served in %d us",
                  frames, latched, gateNsSum / 1e6 / frames, waitNsSum / 1e6 / frames, timeouts, WAIT_NS / 1000));
         }
         lastLogNs = t2;
         frames = timeouts = waitNsSum = gateNsSum = latched = 0;
      }
   }

   private static void gate() {
      var states = SpriteRenderer.instance.states;
      long deadline = System.nanoTime() + 50_000_000L;
      synchronized (states) {
         while (states.getReady() != null && RenderThread.isRunning() && System.nanoTime() < deadline) {
            try {
               states.wait(0, 200_000);
            } catch (InterruptedException e) {
               return;
            }
         }
      }
   }

   /**
    * Render thread: in its wait for a game frame (pumps events itself) and after its own post-swap polls (the events
    * were just pumped). Serves a pending latch request: fresh OS events, then every input cache polled again.
    */
   public static void serve(boolean pumpEvents) {
      if (!pumpEvents) {
         renderIdle = true; // the render loop finished a frame and its polls; next it waits for the game
      }
      if (!LATCH || broken) {
         return;
      }
      long r = requested.get();
      if (served.get() >= r) {
         return;
      }
      try {
         if (pumpEvents) {
            Display.processMessages();
         }
         GameWindow.GameInput.poll(); // drains the key / mouse event queues into the polling states (their own gate is per state)
         if (mouseCache == null) {
            Field f = zombie.input.Mouse.class.getDeclaredField("s_mouseStateCache");
            f.setAccessible(true);
            mouseCache = (MouseStateCache)f.get(null);
            f = GameWindow.GameInput.getClass().getDeclaredField("controllerStateCache");
            f.setAccessible(true);
            padCache = (ControllerStateCache)f.get(GameWindow.GameInput);
         }
         padCache.pzoptRepoll();
         mouseCache.pzoptRepoll();
         GameKeyboard.pzoptRepoll();
      } catch (ReflectiveOperationException | RuntimeException e) {
         Log.warn("input latch: off after " + e);
         broken = true;
      }
      served.set(r);
      InputLag.afterPoll();
   }
}
