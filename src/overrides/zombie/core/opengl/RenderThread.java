package zombie.core.opengl;

import java.io.IOException;
import java.util.ArrayList;
import org.lwjgl.opengl.GL11;
import org.lwjglx.LWJGLException;
import org.lwjglx.input.Controllers;
import org.lwjglx.opengl.Display;
import org.lwjglx.opengl.OpenGLException;
import org.lwjglx.opengl.Util;
import zombie.GameWindow;
import zombie.Lua.LuaManager;
import zombie.core.Clipboard;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.ThreadGroups;
import zombie.core.logger.ExceptionLogger;
import zombie.core.profiling.AbstractPerformanceProfileProbe;
import zombie.core.profiling.PerformanceProfileFrameProbe;
import zombie.core.profiling.PerformanceProfileProbe;
import zombie.core.sprite.SpriteRenderState;
import zombie.core.textures.TextureID;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.input.GameKeyboard;
import zombie.input.Mouse;
import zombie.iso.IsoPuddles;
import zombie.network.GameServer;
import zombie.ui.FPSGraph;
import zombie.util.Lambda;
import zombie.util.lambda.Invokers.Params0.Boolean.CallbackStackItem;
import zombie.util.lambda.Invokers.Params1.ICallback;
import zombie.util.list.PZArrayUtil;

public class RenderThread {
   public static Thread renderThread;
   private static Thread contextThread;
   private static boolean isDisplayCreated;
   private static int contextLockReentrantDepth;
   public static final Object m_contextLock = "RenderThread borrowContext Lock";
   private static final ArrayList<RenderContextQueueItem> invokeOnRenderQueue = new ArrayList<>();
   private static final ArrayList<RenderContextQueueItem> invokeOnRenderQueue_Invoking = new ArrayList<>();

   static {
      pzopt.Overrides.onClassLoadedQuiet("zombie.core.opengl.RenderThread"); // pzopt: initializes before the game log exists
   }
   private static boolean isInitialized;
   private static final Object m_initLock = "RenderThread Initialization Lock";
   private static volatile boolean isCloseRequested;
   private static volatile int displayWidth;
   private static volatile int displayHeight;
   private static volatile boolean renderingEnabled = true;
   private static volatile boolean waitForRenderState;
   private static volatile boolean hasContext;
   private static boolean cursorVisible = true;
   private static long renderTime;
   private static long startWaitTime;
   private static long waitTime;

   public static void init() throws IOException, LWJGLException {
      synchronized (m_initLock) {
         if (!isInitialized) {
            renderThread = Thread.currentThread();
            displayWidth = Display.getWidth();
            displayHeight = Display.getHeight();
            isInitialized = true;
            if (!GameServer.server) {
               GameWindow.InitDisplay();
               Controllers.create();
               Clipboard.initMainThread();
            }
         }
      }
   }

   public static void initServerGUI() {
      synchronized (m_initLock) {
         if (isInitialized) {
            return;
         }

         renderThread = new Thread(ThreadGroups.Main, RenderThread::renderLoop, "RenderThread Main Loop");
         renderThread.setName("Render Thread");
         renderThread.setUncaughtExceptionHandler(RenderThread::uncaughtException);
         displayWidth = Display.getWidth();
         displayHeight = Display.getHeight();
         isInitialized = true;
      }

      renderThread.start();
   }

   public static long getRenderTime() {
      return renderTime;
   }

   public static void renderLoop() {
      if (!isInitialized) {
         throw new IllegalStateException("RenderThread is not initialized.");
      }

      acquireContextReentrant();
      boolean isAlive = true;

      while (isAlive) {
         long startTime = System.nanoTime();
         if (startWaitTime == 0L) {
            startWaitTime = startTime;
         }

         synchronized (m_contextLock) {
            if (!hasContext) {
               acquireContextReentrant();
            }

            displayWidth = Display.getWidth();
            displayHeight = Display.getHeight();
            if (renderingEnabled) {
               AbstractPerformanceProfileProbe var4 = RenderThread.s_performance.renderStep.profile();

               try {
                  renderStep();
               } catch (Throwable var11) {
                  if (var4 != null) {
                     try {
                        var4.close();
                     } catch (Throwable var9) {
                        var11.addSuppressed(var9);
                     }
                  }

                  throw var11;
               }

               if (var4 != null) {
                  var4.close();
               }
            } else if (isDisplayCreated && hasContext) {
               Display.processMessages();
            }

            flushInvokeQueue();
            if (!renderingEnabled) {
               isCloseRequested = false;
            } else {
               pzopt.VirtualPad.poll(); // pzopt: harness virtual pad (--flag pad=<script>); GameWindow.GameInput.poll() otherwise
               Mouse.poll();
               GameKeyboard.poll();
               pzopt.InputLag.afterPoll(); // pzopt: harness input-lag probe, the polling states the game swaps in next
               pzopt.InputLatch.serve(false); // pzopt: inputLatch, a request that came in while this thread rendered
               isCloseRequested = isCloseRequested || Display.isCloseRequested();
            }

            if (!GameServer.server) {
               Clipboard.updateMainThread();
            }

            DebugOptions.testThreadCrash(0);
            isAlive = !GameWindow.gameThreadExited;
         }

         renderTime = System.nanoTime() - startTime;
         Thread.yield();
      }

      releaseContextReentrant();
      synchronized (m_initLock) {
         renderThread = null;
         isInitialized = false;
      }

      shutdown();
      pzopt.Hdr.beforeExit(); // pzopt: native Wayland, terminate GLFW here (NVIDIA EGL segfaulted in the VM thread's exit)
      System.exit(0);
   }

   private static void uncaughtException(Thread thread, Throwable e) {
      try {
         GameWindow.uncaughtException(thread, e);
      } finally {
         Runnable forceClose = () -> {
            long maxTimeMs = 120000L;
            long timeMs = 0L;
            long timeNow = System.currentTimeMillis();
            long timePrev = timeNow;
            if (!GameWindow.gameThreadExited) {
               try {
                  Thread.sleep(1000L);
               } catch (InterruptedException var11) {
               }

               DebugType.General.error("  Waiting for GameThread to exit...");

               try {
                  Thread.sleep(2000L);
               } catch (InterruptedException var10) {
               }

               while (!GameWindow.gameThreadExited) {
                  Thread.yield();
                  timeNow = System.currentTimeMillis();
                  long timeDiff = timeNow - timePrev;
                  timeMs += timeDiff;
                  if (timeMs >= 120000L) {
                     DebugType.General.error("  GameThread failed to exit within time limit.");
                     break;
                  }

                  timePrev = timeNow;
               }
            }

            DebugType.General.error("  Shutting down...");
            System.exit(1);
         };
         Thread forceCloseThread = new Thread(forceClose, "ForceCloseThread");
         forceCloseThread.start();
         DebugType.General.error("Shutting down sequence starts.");
         isCloseRequested = true;
         DebugType.General.error("  Notifying render state queue...");
         notifyRenderStateQueue();
         DebugType.General.error("  Notifying InvokeOnRenderQueue...");
         synchronized (invokeOnRenderQueue) {
            invokeOnRenderQueue_Invoking.addAll(invokeOnRenderQueue);
            invokeOnRenderQueue.clear();
         }

         PZArrayUtil.forEach(invokeOnRenderQueue_Invoking, RenderContextQueueItem::notifyWaitingListeners);
      }
   }

   private static boolean renderStep() {
      boolean result = false;

      try {
         result = lockStepRenderStep();
      } catch (OpenGLException glEx) {
         logGLException(glEx);
      } catch (Exception ex) {
         DebugType.General.error("Thrown an " + ex.getClass().getTypeName() + ": " + ex.getMessage());
         ExceptionLogger.logException(ex);
      }

      return result;
   }

   public static long getWaitTime() {
      return waitTime;
   }

   private static boolean lockStepRenderStep() {
      pzopt.LowLatency.beforeAcquire(); // pzopt: vblankLock, wait for vblank - W, then let the game start its frame
      SpriteRenderState renderState = SpriteRenderer.instance.acquireStateForRendering(RenderThread::waitForRenderStateCallback);
      if (renderState != null) {
         pzopt.InputLag.acquired(); // pzopt: harness input-lag probe, the render thread took a game frame
         pzopt.LowLatency.frameBegin(); // pzopt: reflexSleep queue measurement, frames-in-flight count, input-latch busy
         pzopt.Pacing.onAcquire(); // pzopt: the frame's step start (VRR pacing)
         waitTime = System.nanoTime() - startWaitTime;
         startWaitTime = 0L;
         cursorVisible = renderState.cursorVisible;
         AbstractPerformanceProfileProbe var10 = RenderThread.s_performance.spriteRendererPostRender.profile();

         try {
            pzopt.Overlay.gpuBegin(); // pzopt: GL timer query around the sprite replay (GPU busy time of the frame)
            pzopt.GpuPstate.gpuBegin(); // pzopt: gpuPstate, the frame's GPU time (GL timestamps) for the AMD clock governor
            pzopt.CursorLatch.beforeReplay(renderState); // pzopt: cursorLatch, the drawn cursor at the newest pointer position
            SpriteRenderer.instance.postRender();
            pzopt.Overlay.gpuEnd(); // pzopt: end of the frame's GPU work; the swap is not timed
            pzopt.GpuPstate.gpuEnd(); // pzopt: gpuPstate
            pzopt.InputLag.frameQueued(); // pzopt: harness input-lag probe, GPU-completion timestamp query of the frame
            zombie.core.VBO.GLVertexBufferObject.pzoptFrameEnd(); // pzopt: frame fence for the persistent sprite buffers
         } catch (Throwable var8) {
            if (var10 != null) {
               try {
                  var10.close();
               } catch (Throwable var5) {
                  var8.addSuppressed(var5);
               }
            }

            throw var8;
         }

         if (var10 != null) {
            var10.close();
         }

         var10 = RenderThread.s_performance.displayUpdate.profile();

         try {
            pzopt.Pacing.beforeSwap(); // pzopt: optional present pacing (presentPacing) + swap-call stamp
            Display.update(true);
            pzopt.Pacing.afterSwap(); // pzopt: swap-return stamp, pzopt-pacing.out row
            pzopt.GlNames.refill(); // pzopt: glNoSync, top up the GL name pools while the frame is handed over
            checkControllers();
         } catch (Throwable var7) {
            if (var10 != null) {
               try {
                  var10.close();
               } catch (Throwable var4) {
                  var7.addSuppressed(var4);
               }
            }

            throw var7;
         }

         if (var10 != null) {
            var10.close();
         }

         pzopt.Overlay.onSwap(); // pzopt: presented-frame time sample (same instant MangoHud logs)
         if (FPSGraph.instance != null) {
            FPSGraph.instance.addRender(System.currentTimeMillis());
         }

         return true;
      } else {
         notifyRenderStateQueue();
         if (!waitForRenderState || LuaManager.thread != null && LuaManager.thread.step) {
            AbstractPerformanceProfileProbe var1 = RenderThread.s_performance.displayUpdate.profile();

            try {
               Display.processMessages();
            } catch (Throwable var9) {
               if (var1 != null) {
                  try {
                     var1.close();
                  } catch (Throwable var6) {
                     var9.addSuppressed(var6);
                  }
               }

               throw var9;
            }

            if (var1 != null) {
               var1.close();
            }
         }

         return true;
      }
   }

   private static void checkControllers() {
   }

   private static boolean waitForRenderStateCallback() {
      flushInvokeQueue();
      pzopt.InputLatch.serve(true); // pzopt: inputLatch, the game thread asked for a fresh event pump + input poll
      return shouldContinueWaiting();
   }

   private static boolean shouldContinueWaiting() {
      return !isCloseRequested && !GameWindow.gameThreadExited && (waitForRenderState || SpriteRenderer.instance.isWaitingForRenderState());
   }

   public static boolean isWaitForRenderState() {
      return waitForRenderState;
   }

   public static void setWaitForRenderState(boolean wait) {
      waitForRenderState = wait;
   }

   private static void flushInvokeQueue() {
      synchronized (invokeOnRenderQueue) {
         if (!invokeOnRenderQueue.isEmpty()) {
            PZArrayUtil.addAll(invokeOnRenderQueue_Invoking, invokeOnRenderQueue);
            invokeOnRenderQueue.clear();
         }
      }

      try {
         if (!invokeOnRenderQueue_Invoking.isEmpty()) {
            long start = System.nanoTime();

            while (!invokeOnRenderQueue_Invoking.isEmpty()) {
               RenderContextQueueItem item = invokeOnRenderQueue_Invoking.remove(0);
               long startJob = System.nanoTime();
               item.invoke();
               long endJob = System.nanoTime();
               if (endJob - startJob > 1.0E7) {
                  boolean var7 = true;
               }

               if (endJob - start > 1.0E7) {
                  break;
               }
            }

            for (int i = invokeOnRenderQueue_Invoking.size() - 1; i >= 0; i--) {
               RenderContextQueueItem item = invokeOnRenderQueue_Invoking.get(i);
               if (item.isWaiting()) {
                  while (i >= 0) {
                     RenderContextQueueItem item1 = invokeOnRenderQueue_Invoking.remove(0);
                     item1.invoke();
                     i--;
                  }
                  break;
               }
            }
         }

         if (TextureID.deleteTextureIDS.position() > 0) {
            TextureID.deleteTextureIDS.flip();
            GL11.glDeleteTextures(TextureID.deleteTextureIDS);
            TextureID.deleteTextureIDS.clear();
         }
      } catch (OpenGLException glEx) {
         logGLException(glEx);
      } catch (Exception ex) {
         DebugType.General.printException(ex, LogSeverity.Error, "Thrown an %s: %s ", new Object[]{ex.getClass().getTypeName(), ex.getMessage()});
      }
   }

   public static void logGLException(OpenGLException glEx) {
      logGLException(glEx, true);
   }

   public static void logGLException(OpenGLException glEx, boolean stackTrace) {
      DebugType.General.error("OpenGLException thrown: " + glEx.getMessage());

      for (int extraErrorCode = GL11.glGetError(); extraErrorCode != 0; extraErrorCode = GL11.glGetError()) {
         String errorString = Util.translateGLErrorString(extraErrorCode);
         DebugType.General.error("  Also detected error: " + errorString + " ( code:" + extraErrorCode + ")");
      }

      if (stackTrace) {
         DebugType.General.printException(glEx, LogSeverity.Error, "OpenGLException details.", new Object[0]);
      }
   }

   public static void Ready() {
      long pzoptReadyNs = System.nanoTime(); // pzopt: harness input-lag probe, the hand-off with its ready-slot wait
      pzopt.Pacing.onPush(); // pzopt: pair the pushed frame with its step start (VRR pacing)
      SpriteRenderer.instance.pushFrameDown();
      pzopt.InputLag.pushed(pzoptReadyNs); // pzopt: harness input-lag probe
      pzopt.LowLatency.pushed(pzoptReadyNs); // pzopt: reflexSleep, the frame's hand-off and its wait
      if (!isInitialized) {
         invokeOnRenderContext(RenderThread::renderStep);
      }
   }

   private static void acquireContextReentrant() {
      synchronized (m_contextLock) {
         acquireContextReentrantInternal();
      }
   }

   private static void releaseContextReentrant() {
      synchronized (m_contextLock) {
         releaseContextReentrantInternal();
      }
   }

   private static void acquireContextReentrantInternal() {
      Thread currentThread = Thread.currentThread();
      if (contextThread != null && contextThread != currentThread) {
         throw new RuntimeException("Context thread mismatch: " + contextThread + ", " + currentThread);
      }

      contextLockReentrantDepth++;
      if (contextLockReentrantDepth <= 1) {
         contextThread = currentThread;
         isDisplayCreated = Display.isCreated();
         if (isDisplayCreated) {
            try {
               hasContext = true;
               Display.makeCurrent();
               Display.setVSyncEnabled(Core.getInstance().getOptionVSync());
            } catch (LWJGLException e) {
               DebugType.General.printException(e, LogSeverity.Error, "Exception thrown trying to gain GL context.", new Object[0]);
            }
         }
      }
   }

   private static void releaseContextReentrantInternal() {
      Thread currentThread = Thread.currentThread();
      if (contextThread != currentThread) {
         throw new RuntimeException("Context thread mismatch: " + contextThread + ", " + currentThread);
      }

      if (contextLockReentrantDepth == 0) {
         throw new RuntimeException("Context thread release overflow: 0: " + contextThread + ", " + currentThread);
      }

      contextLockReentrantDepth--;
      if (contextLockReentrantDepth <= 0) {
         if (isDisplayCreated && hasContext) {
            try {
               hasContext = false;
               Display.releaseContext();
            } catch (LWJGLException e) {
               DebugType.General.printException(e, LogSeverity.Error, "Exception thrown trying to release GL context.", new Object[0]);
            }
         }

         contextThread = null;
      }
   }

   public static void invokeOnRenderContext(Runnable toInvoke) throws RenderContextQueueException {
      RenderContextQueueItem queueItem = RenderContextQueueItem.alloc(toInvoke);
      queueItem.setWaiting();
      queueInvokeOnRenderContext(queueItem);

      try {
         queueItem.waitUntilFinished(() -> {
            notifyRenderStateQueue();
            return !isCloseRequested && !GameWindow.gameThreadExited;
         });
      } catch (InterruptedException ex) {
         DebugType.General.error("Thread Interrupted while waiting for queued item to finish:" + queueItem);
         notifyRenderStateQueue();
      }

      Throwable t = queueItem.getThrown();
      if (t != null) {
         throw new RenderContextQueueException(t);
      }
   }

   public static boolean invokeQueryOnRenderContext(zombie.util.lambda.Invokers.Params0.Boolean.ICallback callback) {
      if (contextThread == Thread.currentThread()) {
         return callback.accept();
      }

      if (!isInitialized) {
         for (int i = 0; i < 1048576 && !isInitialized && !Thread.interrupted(); i++) {
            Thread.yield();
         }

         if (!isInitialized) {
            return false;
         }
      }

      CallbackStackItem invoker = Lambda.invokerBoolean(callback);
      invokeOnRenderContext(invoker);
      boolean result = invoker.getAsBoolean();
      invoker.release();
      return result;
   }

   public static <T1> void invokeOnRenderContext(T1 val1, ICallback<T1> invoker) {
      Lambda.capture(val1, invoker, (stack, lVal1, lInvoker) -> invokeOnRenderContext(stack.invoker(lVal1, lInvoker)));
   }

   public static <T1, T2> void invokeOnRenderContext(T1 val1, T2 val2, zombie.util.lambda.Invokers.Params2.ICallback<T1, T2> invoker) {
      Lambda.capture(val1, val2, invoker, (stack, lVal1, lVal2, lInvoker) -> invokeOnRenderContext(stack.invoker(lVal1, lVal2, lInvoker)));
   }

   public static <T1, T2, T3> void invokeOnRenderContext(T1 val1, T2 val2, T3 val3, zombie.util.lambda.Invokers.Params3.ICallback<T1, T2, T3> invoker) {
      Lambda.capture(val1, val2, val3, invoker, (stack, lVal1, lVal2, lVal3, lInvoker) -> invokeOnRenderContext(stack.invoker(lVal1, lVal2, lVal3, lInvoker)));
   }

   public static <T1, T2, T3, T4> void invokeOnRenderContext(
      T1 val1, T2 val2, T3 val3, T4 val4, zombie.util.lambda.Invokers.Params4.ICallback<T1, T2, T3, T4> invoker
   ) {
      Lambda.capture(
         val1,
         val2,
         val3,
         val4,
         invoker,
         (stack, lVal1, lVal2, lVal3, lVal4, lInvoker) -> invokeOnRenderContext(stack.invoker(lVal1, lVal2, lVal3, lVal4, lInvoker))
      );
   }

   protected static void notifyRenderStateQueue() {
      if (SpriteRenderer.instance != null) {
         SpriteRenderer.instance.notifyRenderStateQueue();
      }
   }

   public static void queueInvokeOnRenderContext(Runnable runnable) {
      queueInvokeOnRenderContext(RenderContextQueueItem.alloc(runnable));
   }

   public static void queueInvokeOnRenderContext(RenderContextQueueItem queueItem) {
      if (!isInitialized) {
         synchronized (m_initLock) {
            if (!isInitialized) {
               try {
                  acquireContextReentrant();
                  queueItem.invoke();
               } finally {
                  releaseContextReentrant();
               }

               return;
            }
         }
      }

      if (contextThread == Thread.currentThread()) {
         queueItem.invoke();
      } else {
         synchronized (invokeOnRenderQueue) {
            invokeOnRenderQueue.add(queueItem);
         }
      }
   }

   public static void shutdown() {
      GameWindow.GameInput.quit();
      IsoPuddles.getInstance().freeHMTextureBuffer();
      if (isInitialized) {
         queueInvokeOnRenderContext(Display::destroy);
      } else {
         Display.destroy();
      }
   }

   public static boolean isCloseRequested() {
      if (isCloseRequested) {
         DebugType.ExitDebug.debugln("RenderThread.isCloseRequested 1");
         return isCloseRequested;
      }

      if (!isInitialized) {
         synchronized (m_initLock) {
            if (!isInitialized) {
               isCloseRequested = Display.isCloseRequested();
               if (isCloseRequested) {
                  DebugType.ExitDebug.debugln("RenderThread.isCloseRequested 2");
               }
            }
         }
      }

      return isCloseRequested;
   }

   public static int getDisplayWidth() {
      return !isInitialized ? Display.getWidth() : displayWidth;
   }

   public static int getDisplayHeight() {
      return !isInitialized ? Display.getHeight() : displayHeight;
   }

   public static boolean isRunning() {
      return isInitialized;
   }

   public static void startRendering() {
      renderingEnabled = true;
   }

   public static void onGameThreadExited() {
      DebugType.General.println("GameThread exited.");
      if (renderThread != null) {
         renderThread.interrupt();
      }
   }

   public static boolean isCursorVisible() {
      return cursorVisible;
   }

   private static class s_performance {
      static final PerformanceProfileFrameProbe renderStep = new PerformanceProfileFrameProbe("RenderThread.renderStep");
      static final PerformanceProfileProbe displayUpdate = new PerformanceProfileProbe("Display.update(true)");
      static final PerformanceProfileProbe spriteRendererPostRender = new PerformanceProfileProbe("SpriteRenderer.postRender");
   }
}
