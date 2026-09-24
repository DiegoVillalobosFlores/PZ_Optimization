package pzopt;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.opengl.EXTFramebufferBlit;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;

/**
 * macOS (Apple silicon) present bridge: the only way a GL game gets ProMotion / Adaptive-Sync timing.
 *
 * Measured on the M1 Pro's ProMotion panel (tools/mac/mtlprobe.m, glbridge.m, 2026-09-24): an NSOpenGL flushBuffer or
 * a plain Metal present is shown on the 120 Hz grid, so a 90 fps game alternates 8.3 / 16.7 ms frames (judder); a
 * Metal present with {@code presentDrawable:afterMinimumDuration:} from a fullscreen or screen-sized borderless window
 * is shown at any multiple of the panel's 4.17 ms update granularity: 90 fps asked -> a steady 80 Hz (12.5 ms, sd
 * 0.33 ms), unchanged with 4 ms of random per-frame CPU jitter. Windowed it stays on the 120 Hz grid.
 *
 * So instead of glfwSwapBuffers, every frame the GL back buffer is blitted into one of three IOSurface-backed
 * rectangle textures (glFlush is the GL -> Metal hand-off on one device, Apple's "Mixing Metal and OpenGL" sample),
 * copied into a CAMetalLayer drawable on a layer over the GLFW content view, and presented with the minimum duration
 * of the frame cap snapped to what the panel can show ({@link #snapFps}). The drawable's presentedTime is the on-glass
 * time, fed back to pzopt.Pacing's log. Any failure removes the layer and falls back to glfwSwapBuffers for good.
 *
 * Everything goes through the Objective-C runtime and the system frameworks with java.lang.foreign; objc_msgSend is
 * called with the exact prototype of each message (arm64 has no _stret / _fpret variants).
 */
public final class MacPresent {
   private static final boolean ARM_MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
      && System.getProperty("os.arch", "").contains("aarch64");
   private static final String SETTING = Config.MAC_PRESENT.toLowerCase(Locale.ROOT);

   private static boolean failed;
   private static boolean ready;
   private static final int SURFACES = 3;

   private static Linker linker;
   private static MemorySegment msgSendAddr;
   private static MethodHandle getClass;
   private static MethodHandle selRegister;
   private static MethodHandle poolPush;
   private static MethodHandle poolPop;
   private static MethodHandle ioSurfaceCreate;
   private static MethodHandle cglTexImageIOSurface;
   private static MethodHandle cglGetCurrentContext;
   private static MethodHandle mtlCreateDevice;
   private static MethodHandle caMediaTime;
   private static final Map<String, MemorySegment> sels = new HashMap<>();
   private static final Map<FunctionDescriptor, MethodHandle> sends = new HashMap<>();

   private static final AddressLayout P = ValueLayout.ADDRESS;
   private static final ValueLayout.OfLong J = ValueLayout.JAVA_LONG;
   private static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE;
   private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
   private static final StructLayout CGSIZE = MemoryLayout.structLayout(D.withName("w"), D.withName("h"));
   private static final StructLayout CGRECT = MemoryLayout.structLayout(D, D, D, D);
   private static final StructLayout MTL3 = MemoryLayout.structLayout(J, J, J);

   private static MemorySegment nsWindow;
   private static MemorySegment view;
   private static MemorySegment layer;
   private static MemorySegment device;
   private static MemorySegment queue;
   private static int width;
   private static int height;
   /** Drawable size: the window's size in the panel's pixels (bounds x backingScaleFactor). */
   private static int drawW;
   private static int drawH;
   private static MemorySegment scaler;
   private static double viewW;
   private static double viewH;
   private static boolean nativeFullscreenPending;
   private static volatile boolean nativeFullscreen;
   private static final MemorySegment[] surfaces = new MemorySegment[SURFACES];
   private static final MemorySegment[] mtlTextures = new MemorySegment[SURFACES];
   private static final int[] glTextures = new int[SURFACES];
   private static final int[] fbos = new int[SURFACES];
   private static final MemorySegment[] commandBuffers = new MemorySegment[SURFACES];
   private static long frame;
   private static final int KEEP = 8;
   private static final MemorySegment[] drawables = new MemorySegment[KEEP];
   private static final long[] drawableFrames = new long[KEEP];
   private static long mediaToNanoNs;

   // display timing (NSScreen, macOS 12+)
   private static volatile double minInterval;
   private static volatile double maxInterval;
   private static volatile double granularity;

   private MacPresent() {
   }

   /** The bridge is wanted on this machine (Apple silicon, overrides on, macPresent not off). */
   public static boolean wanted() {
      return ARM_MAC && Overrides.enabled() && !"off".equals(SETTING) && !"false".equals(SETTING);
   }

   /** Presenting through Metal right now. */
   public static boolean active() {
      return ready && !failed;
   }

   /** ProMotion / Adaptive-Sync display (NSScreen refresh interval range), read at first use. */
   public static boolean variableDisplay() {
      return maxInterval > minInterval + 1e-4;
   }

   public static double maxHz() {
      return minInterval > 0.0 ? 1.0 / minInterval : 0.0;
   }

   /** The highest rate at or below fps the panel shows exactly: frame intervals are multiples of the update granularity
    * (fullscreen / borderless with the bridge: 4.17 ms on ProMotion -> 120, 80, 60, 48, 40 ...) or of the refresh
    * period otherwise (120, 60, 40, 30 ...). */
   public static int snapFps(int fps, boolean adaptive) {
      double min = minInterval;
      if (min <= 0.0 || fps <= 0) {
         return fps;
      }
      double step = adaptive && granularity > 0.0 ? granularity : min;
      double want = Math.max(1.0 / fps, min);
      long n = (long)Math.ceil(want / step - 1e-3);
      n = Math.max(n, (long)Math.ceil(min / step - 1e-3));
      return (int)Math.floor(1.0 / (n * step) + 1e-3);
   }

   /** Render thread, instead of glfwSwapBuffers. True when the frame was presented through Metal. */
   public static boolean present(long glfwWindow) {
      if (failed || !wanted()) {
         return false;
      }
      try (Arena a = Arena.ofConfined()) {
         if (!ready) {
            init(glfwWindow, a);
         }
         int[] fw = new int[1];
         int[] fh = new int[1];
         GLFW.glfwGetFramebufferSize(glfwWindow, fw, fh);
         if (fw[0] <= 0 || fh[0] <= 0) {
            return true; // minimised: nothing to show
         }
         if (nativeFullscreenPending) {
            nativeFullscreenPending = false;
            if (!inNativeFullscreen()) {
               sendL(nsWindow, "setCollectionBehavior:", 128L); // NSWindowCollectionBehaviorFullScreenPrimary
               sendV(nsWindow, "toggleFullScreen:", MemorySegment.NULL);
               Log.info("mac present: entering native fullscreen (the Adaptive-Sync / ProMotion path)");
            }
         }
         nativeFullscreen = inNativeFullscreen();
         if (fw[0] != width || fh[0] != height || viewSizeChanged(a)) {
            resize(fw[0], fh[0], a);
         }
         MemorySegment pool = (MemorySegment)poolPush.invokeExact();
         try {
            presentFrame(a);
         } finally {
            poolPop.invokeExact(pool);
         }
         return true;
      } catch (Throwable t) {
         fail(t);
         return false;
      }
   }

   private static void presentFrame(Arena a) throws Throwable {
      int k = (int)(frame % SURFACES);
      long f = frame++;
      MemorySegment old = commandBuffers[k];
      if (old != null) {
         // the copy that read this surface three frames ago must be done before GL overwrites it
         if (sendJ(old, "status") < 4L) {
            sendV(old, "waitUntilCompleted");
         }
         sendV(old, "release");
         commandBuffers[k] = null;
      }
      // GL: back buffer -> IOSurface texture k
      int readFbo = GL11.glGetInteger(EXTFramebufferBlit.GL_READ_FRAMEBUFFER_BINDING_EXT);
      int drawFbo = GL11.glGetInteger(EXTFramebufferBlit.GL_DRAW_FRAMEBUFFER_BINDING_EXT);
      boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
      if (scissor) {
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
      }
      EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferBlit.GL_READ_FRAMEBUFFER_EXT, 0);
      EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferBlit.GL_DRAW_FRAMEBUFFER_EXT, fbos[k]);
      // GL row 0 is the bottom, the IOSurface's row 0 is Metal's top: blit upside down
      EXTFramebufferBlit.glBlitFramebufferEXT(0, 0, width, height, 0, height, width, 0, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
      EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferBlit.GL_READ_FRAMEBUFFER_EXT, readFbo);
      EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferBlit.GL_DRAW_FRAMEBUFFER_EXT, drawFbo);
      if (scissor) {
         GL11.glEnable(GL11.GL_SCISSOR_TEST);
      }
      if (Config.MAC_PRESENT_FINISH) {
         GL11.glFinish();
      } else {
         GL11.glFlush();
      }
      if (Config.DEV_MAC_PRESENT_CHECK && f > 0L && f % 1000L == 0L && f <= 5000L) {
         orientationCheck(k, f);
      }
      // Metal: copy into the next drawable and present it no sooner than the cap interval after the previous one
      long w0 = System.nanoTime();
      MemorySegment drawable = sendP(layer, "nextDrawable");
      steer(System.nanoTime() - w0);
      if (drawable.address() == 0L) {
         return;
      }
      MemorySegment cb = sendP(queue, "commandBuffer");
      MemorySegment dst = sendP(drawable, "texture");
      if (drawW != width || drawH != height) {
         // the game renders below the panel's pixels (GLFW asks for no Retina framebuffer on macOS): upscale into a
         // native-size drawable. A scale-1 layer is resampled by the window server instead, and that composited path
         // loses the exact minimum-duration timing (60 Hz came out 12.5 / 20.8 ms alternating, glbridge lowres runs)
         handle(FunctionDescriptor.ofVoid(P, P, P, P, P)).invokeExact(self(scaler), sel("encodeToCommandBuffer:sourceTexture:destinationTexture:"),
            cb, mtlTextures[k], dst);
      } else {
         MemorySegment enc = sendP(cb, "blitCommandEncoder");
         MemorySegment origin = a.allocate(MTL3);
         MemorySegment size = a.allocate(MTL3);
         size.set(J, 0, width);
         size.set(J, 8, height);
         size.set(J, 16, 1L);
         copyTexture(enc, mtlTextures[k], origin, size, dst);
         sendV(enc, "endEncoding");
      }
      double interval = presentInterval();
      if (interval > 0.0) {
         MethodHandle h = handle(FunctionDescriptor.ofVoid(P, P, P, D));
         h.invokeExact(self(cb), sel("presentDrawable:afterMinimumDuration:"), drawable, interval);
      } else {
         sendV(cb, "presentDrawable:", drawable);
      }
      sendV(cb, "commit");
      sendP(cb, "retain");
      commandBuffers[k] = cb;
      // on-glass feedback: presentedTime is known once the drawable has been shown
      int slot = (int)(f % KEEP);
      MemorySegment prev = drawables[slot];
      if (prev != null) {
         double shown = sendD(prev, "presentedTime");
         if (shown > 0.0) {
            Pacing.onGlass(drawableFrames[slot], (long)(shown * 1e9) + mediaToNanoNs);
         }
         sendV(prev, "release");
      }
      sendP(drawable, "retain");
      drawables[slot] = drawable;
      drawableFrames[slot] = Pacing.currentFrame();
   }

   // --- orientation rig (devMacPresentCheck) -------------------------------------------------------
   //
   // The window cannot be screen-captured from the harness (ssh has no Screen Recording permission), so the rig checks
   // the one place the bridge can turn the picture over: IOSurface row r (Metal's row 0 is the top) must hold the GL
   // back buffer's row height-1-r (GL's row 0 is the bottom). MPS upscale and the drawable copy keep Metal's rows.

   private static MethodHandle surfLock;
   private static MethodHandle surfUnlock;
   private static MethodHandle surfBase;
   private static MethodHandle surfRowBytes;

   private static void orientationCheck(int k, long f) throws Throwable {
      if (surfLock == null) {
         SymbolLookup ios = SymbolLookup.libraryLookup("/System/Library/Frameworks/IOSurface.framework/IOSurface", Arena.global());
         surfLock = linker.downcallHandle(ios.find("IOSurfaceLock").orElseThrow(), FunctionDescriptor.of(I, P, I, P));
         surfUnlock = linker.downcallHandle(ios.find("IOSurfaceUnlock").orElseThrow(), FunctionDescriptor.of(I, P, I, P));
         surfBase = linker.downcallHandle(ios.find("IOSurfaceGetBaseAddress").orElseThrow(), FunctionDescriptor.of(P, P));
         surfRowBytes = linker.downcallHandle(ios.find("IOSurfaceGetBytesPerRow").orElseThrow(), FunctionDescriptor.of(J, P));
      }
      GL11.glFinish();
      int readFbo = GL11.glGetInteger(EXTFramebufferBlit.GL_READ_FRAMEBUFFER_BINDING_EXT);
      EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferBlit.GL_READ_FRAMEBUFFER_EXT, 0);
      ByteBuffer gl = BufferUtils.createByteBuffer(width * height * 4);
      GL11.glReadPixels(0, 0, width, height, 0x80E1, 0x8367, gl); // GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV: the surface's bytes
      EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferBlit.GL_READ_FRAMEBUFFER_EXT, readFbo);
      MemorySegment surface = surfaces[k];
      if ((int)surfLock.invokeExact(surface, 1, MemorySegment.NULL) != 0) { // kIOSurfaceLockReadOnly
         Log.warn("mac present check: IOSurfaceLock failed");
         return;
      }
      int rows = 32;
      int upright = 0;
      int flipped = 0;
      int distinct = 0;
      try {
         long rowBytes = (long)surfRowBytes.invokeExact(surface);
         MemorySegment base = ((MemorySegment)surfBase.invokeExact(surface)).reinterpret(rowBytes * height);
         long prev = 0L;
         for (int i = 0; i < rows; i++) {
            int r = (int)((i + 0.5) * height / rows);
            long hs = 17L;
            for (int x = 0; x < width; x++) {
               hs = hs * 31L + (base.get(ValueLayout.JAVA_INT_UNALIGNED, r * rowBytes + x * 4L) & 0xFFFFFF);
            }
            long top = glRowHash(gl, height - 1 - r);
            upright += hs == top ? 1 : 0;
            flipped += hs == glRowHash(gl, r) ? 1 : 0;
            distinct += i > 0 && top != prev ? 1 : 0;
            prev = top;
         }
      } finally {
         int ignored = (int)surfUnlock.invokeExact(surface, 1, MemorySegment.NULL);
      }
      String verdict = distinct < 2 ? "inconclusive (uniform frame)" : upright == rows ? "upright" : flipped == rows ? "UPSIDE DOWN" : "MISMATCH";
      Log.info(String.format(Locale.ROOT, "mac present check: frame %d %s (%d/%d rows match the GL picture upright, %d flipped, %d distinct)",
         f, verdict, upright, rows, flipped, distinct + 1));
   }

   private static long glRowHash(ByteBuffer gl, int row) {
      long h = 17L;
      int off = row * width * 4;
      for (int x = 0; x < width; x++) {
         h = h * 31L + (gl.getInt(off + x * 4) & 0xFFFFFF);
      }
      return h;
   }

   // --- phase control ----------------------------------------------------------------------------
   //
   // A minimum-duration present shows a frame one panel step after its predecessor, so frames that are ready early
   // wait in the drawable queue: nextDrawable blocks the render thread, the full ready slot blocks the game thread,
   // and the game settles at the worst phase, building each frame right after a slot frees (53 ms from step start to
   // glass at 80 Hz, mac7-bridge-fs-80). The limiter can shift the game's cadence instead: each frame the render thread
   // compares how long nextDrawable blocked with a ~1 ms target and asks the game thread to start its next step later
   // (blocked longer) or earlier (queue ran dry, the frame risks missing its slot) by part of the difference.

   private static final long TARGET_BLOCK_NS = 1_000_000L;
   private static volatile long stepShiftNs;

   private static void steer(long blockedNs) {
      if (!Config.MAC_PRESENT_PHASE) {
         return;
      }
      long interval = Pacing.capIntervalNs();
      if (interval <= 0L) {
         return;
      }
      long err = blockedNs - TARGET_BLOCK_NS;
      long step = Math.max(-interval / 8L, Math.min(interval / 4L, err * 3L / 10L));
      stepShiftNs += step;
   }

   /** Game thread, when the limiter fires a step: how much later (> 0) or earlier (< 0) to start it; resets the request. */
   public static long takeStepShiftNs(long intervalNs) {
      long s = stepShiftNs;
      if (s == 0L || !active()) {
         return 0L;
      }
      stepShiftNs = 0L;
      return Math.max(-intervalNs / 4L, Math.min(intervalNs / 2L, s));
   }

   /** Minimum duration for the present: the game's cap interval less 0.5 ms (it rounds up to the next panel step). */
   private static double presentInterval() {
      long ns = Pacing.capIntervalNs();
      if (ns <= 0L) {
         return minInterval > 0.0 ? minInterval - 0.0005 : 0.0;
      }
      return Math.max(0.0, ns / 1e9 - 0.0005);
   }

   private static void copyTexture(MemorySegment enc, MemorySegment src, MemorySegment origin, MemorySegment size, MemorySegment dst) throws Throwable {
      MethodHandle h = handle(FunctionDescriptor.ofVoid(P, P, P, J, J, MTL3, MTL3, P, J, J, MTL3));
      h.invokeExact(self(enc), sel("copyFromTexture:sourceSlice:sourceLevel:sourceOrigin:sourceSize:toTexture:destinationSlice:destinationLevel:destinationOrigin:"),
         src, 0L, 0L, origin, size, dst, 0L, 0L, origin);
   }

   // --- setup ------------------------------------------------------------------------------------

   private static void init(long glfwWindow, Arena a) throws Throwable {
      linker = Linker.nativeLinker();
      Arena g = Arena.global();
      SymbolLookup objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", g);
      SymbolLookup metal = SymbolLookup.libraryLookup("/System/Library/Frameworks/Metal.framework/Metal", g);
      SymbolLookup qc = SymbolLookup.libraryLookup("/System/Library/Frameworks/QuartzCore.framework/QuartzCore", g);
      SymbolLookup ios = SymbolLookup.libraryLookup("/System/Library/Frameworks/IOSurface.framework/IOSurface", g);
      SymbolLookup ogl = SymbolLookup.libraryLookup("/System/Library/Frameworks/OpenGL.framework/OpenGL", g);
      msgSendAddr = objc.find("objc_msgSend").orElseThrow();
      getClass = linker.downcallHandle(objc.find("objc_getClass").orElseThrow(), FunctionDescriptor.of(P, P));
      selRegister = linker.downcallHandle(objc.find("sel_registerName").orElseThrow(), FunctionDescriptor.of(P, P));
      poolPush = linker.downcallHandle(objc.find("objc_autoreleasePoolPush").orElseThrow(), FunctionDescriptor.of(P));
      poolPop = linker.downcallHandle(objc.find("objc_autoreleasePoolPop").orElseThrow(), FunctionDescriptor.ofVoid(P));
      ioSurfaceCreate = linker.downcallHandle(ios.find("IOSurfaceCreate").orElseThrow(), FunctionDescriptor.of(P, P));
      cglTexImageIOSurface = linker.downcallHandle(ogl.find("CGLTexImageIOSurface2D").orElseThrow(),
         FunctionDescriptor.of(I, P, I, I, I, I, I, I, P, I));
      cglGetCurrentContext = linker.downcallHandle(ogl.find("CGLGetCurrentContext").orElseThrow(), FunctionDescriptor.of(P));
      mtlCreateDevice = linker.downcallHandle(metal.find("MTLCreateSystemDefaultDevice").orElseThrow(), FunctionDescriptor.of(P));
      caMediaTime = linker.downcallHandle(qc.find("CACurrentMediaTime").orElseThrow(), FunctionDescriptor.of(D));
      readScreen();
      long before = System.nanoTime();
      double media = (double)caMediaTime.invokeExact();
      long after = System.nanoTime();
      mediaToNanoNs = (before + after) / 2L - (long)(media * 1e9);

      nsWindow = MemorySegment.ofAddress(GLFWNativeCocoa.glfwGetCocoaWindow(glfwWindow));
      view = sendP(nsWindow, "contentView");
      device = (MemorySegment)mtlCreateDevice.invokeExact();
      if (device.address() == 0L) {
         throw new IllegalStateException("no Metal device");
      }
      queue = sendP(device, "newCommandQueue");
      SymbolLookup.libraryLookup("/System/Library/Frameworks/MetalPerformanceShaders.framework/MetalPerformanceShaders", g);
      scaler = (MemorySegment)handle(FunctionDescriptor.of(P, P, P, P)).invokeExact(sendP(cls("MPSImageBilinearScale"), "alloc"),
         sel("initWithDevice:"), device);
      layer = sendP(cls("CAMetalLayer"), "layer");
      sendP(layer, "retain");
      sendV(layer, "setDevice:", device);
      sendL(layer, "setPixelFormat:", 80L); // MTLPixelFormatBGRA8Unorm
      sendZ(layer, "setFramebufferOnly:", false);
      sendZ(layer, "setOpaque:", true);
      // 3 drawables let a queue build up behind the minimum-duration presents whenever the game runs at the panel's
      // rate: 42-44 ms from swap to glass at 80 Hz (mac6-bridge-fs); with 2 a full queue blocks nextDrawable instead
      sendL(layer, "setMaximumDrawableCount:", Math.max(2, Math.min(3, Config.MAC_PRESENT_DRAWABLES)));
      sendZ(view, "setWantsLayer:", true);
      MemorySegment host = sendP(view, "layer");
      sendV(host, "addSublayer:", layer);
      ready = true;
      Log.info(String.format(Locale.ROOT, "mac present: Metal bridge on (%s), display %.0f Hz max, %.1f ms granularity, %s",
         Config.MAC_PRESENT, maxHz(), granularity * 1000.0, variableDisplay() ? "variable refresh (ProMotion / Adaptive-Sync)" : "fixed refresh"));
   }

   private static boolean viewSizeChanged(Arena a) throws Throwable {
      MemorySegment bounds = (MemorySegment)handle(FunctionDescriptor.of(CGRECT, P, P)).invokeExact((SegmentAllocator)a, self(view), sel("bounds"));
      return bounds.get(D, 16) != viewW || bounds.get(D, 24) != viewH;
   }

   static boolean inNativeFullscreen() {
      try {
         return nsWindow != null && (sendJ(nsWindow, "styleMask") & (1L << 14)) != 0L; // NSWindowStyleMaskFullScreen
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * macOS borderless with the bridge: the window goes into a native fullscreen Space on the next frame instead of
    * staying a screen-sized window. Only the native fullscreen path held the exact minimum-duration cadence in every
    * probe run (a screen-sized borderless window alternated 12.5 / 20.8 ms at 60 Hz in some); Apple documents
    * Adaptive-Sync as a fullscreen feature. The title bar is hidden there, so the window keeps its decoration.
    */
   /** The window is in a native fullscreen Space (read each bridged frame): the panel's fine steps apply (FrameCap). */
   public static boolean nativeFullscreenNow() {
      return nativeFullscreen && active();
   }

   public static boolean nativeFullscreenForBorderless() {
      return wanted() && Config.MAC_NATIVE_FULLSCREEN;
   }

   public static void requestNativeFullscreen() {
      nativeFullscreenPending = true;
   }

   /** NSScreen.mainScreen refresh interval range and update granularity. */
   public static void readScreen() {
      try {
         if (linker == null) {
            return;
         }
         MemorySegment screen = sendP(cls("NSScreen"), "mainScreen");
         if (screen.address() == 0L) {
            return;
         }
         minInterval = sendD(screen, "minimumRefreshInterval");
         maxInterval = sendD(screen, "maximumRefreshInterval");
         granularity = sendD(screen, "displayUpdateGranularity");
      } catch (Throwable t) {
         Log.warn("mac present: NSScreen timing unavailable: " + t);
      }
   }

   private static void resize(int w, int h, Arena a) throws Throwable {
      for (int i = 0; i < SURFACES; i++) {
         if (commandBuffers[i] != null) {
            sendV(commandBuffers[i], "waitUntilCompleted");
            sendV(commandBuffers[i], "release");
            commandBuffers[i] = null;
         }
         if (fbos[i] != 0) {
            EXTFramebufferObject.glDeleteFramebuffersEXT(fbos[i]);
            GL11.glDeleteTextures(glTextures[i]);
            sendV(mtlTextures[i], "release");
            sendV(surfaces[i], "release"); // IOSurfaceRef is toll-free bridged to an NSObject
         }
      }
      width = w;
      height = h;
      MemorySegment dict = sendP(cls("NSMutableDictionary"), "dictionary");
      putNumber(dict, "kIOSurfaceWidth", w);
      putNumber(dict, "kIOSurfaceHeight", h);
      putNumber(dict, "kIOSurfaceBytesPerElement", 4);
      putNumber(dict, "kIOSurfacePixelFormat", 0x42475241); // 'BGRA'
      MemorySegment cgl = (MemorySegment)cglGetCurrentContext.invokeExact();
      int prevTex = GL11.glGetInteger(0x84F6); // GL_TEXTURE_BINDING_RECTANGLE_ARB
      int prevFbo = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
      for (int i = 0; i < SURFACES; i++) {
         surfaces[i] = (MemorySegment)ioSurfaceCreate.invokeExact(dict);
         if (surfaces[i].address() == 0L) {
            throw new IllegalStateException("IOSurfaceCreate failed for " + w + "x" + h);
         }
         glTextures[i] = GL11.glGenTextures();
         GL11.glBindTexture(0x84F5, glTextures[i]); // GL_TEXTURE_RECTANGLE_ARB
         int err = (int)cglTexImageIOSurface.invokeExact(cgl, 0x84F5, GL11.GL_RGBA, w, h, 0x80E1, 0x8367, surfaces[i], 0); // GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV
         if (err != 0) {
            throw new IllegalStateException("CGLTexImageIOSurface2D error " + err);
         }
         fbos[i] = EXTFramebufferObject.glGenFramebuffersEXT();
         EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, fbos[i]);
         EXTFramebufferObject.glFramebufferTexture2DEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT, 0x84F5, glTextures[i], 0);
         int status = EXTFramebufferObject.glCheckFramebufferStatusEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT);
         if (status != EXTFramebufferObject.GL_FRAMEBUFFER_COMPLETE_EXT) {
            throw new IllegalStateException("IOSurface FBO incomplete 0x" + Integer.toHexString(status));
         }
         MemorySegment desc = texDescriptor(w, h);
         MethodHandle nt = handle(FunctionDescriptor.of(P, P, P, P, P, J));
         mtlTextures[i] = (MemorySegment)nt.invokeExact(self(device), sel("newTextureWithDescriptor:iosurface:plane:"), desc, surfaces[i], 0L);
         if (mtlTextures[i].address() == 0L) {
            throw new IllegalStateException("newTextureWithDescriptor:iosurface: failed");
         }
      }
      GL11.glBindTexture(0x84F5, prevTex);
      EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, prevFbo);
      MemorySegment bounds = (MemorySegment)handle(FunctionDescriptor.of(CGRECT, P, P)).invokeExact((SegmentAllocator)a, self(view), sel("bounds"));
      handle(FunctionDescriptor.ofVoid(P, P, CGRECT)).invokeExact(self(layer), sel("setFrame:"), bounds);
      double scale = sendD(nsWindow, "backingScaleFactor");
      viewW = bounds.get(D, 16);
      viewH = bounds.get(D, 24);
      drawW = Math.max(w, (int)Math.round(viewW * scale));
      drawH = Math.max(h, (int)Math.round(viewH * scale));
      MemorySegment sz = a.allocate(CGSIZE);
      sz.set(D, 0, drawW);
      sz.set(D, 8, drawH);
      handle(FunctionDescriptor.ofVoid(P, P, CGSIZE)).invokeExact(self(layer), sel("setDrawableSize:"), sz);
      handle(FunctionDescriptor.ofVoid(P, P, D)).invokeExact(self(layer), sel("setContentsScale:"), scale);
      readScreen();
      Log.info("mac present: game " + w + "x" + h + " -> drawable " + drawW + "x" + drawH + (drawW != w || drawH != h ? " (MPS bilinear upscale)" : "")
         + ", backing scale " + scale + (inNativeFullscreen() ? ", native fullscreen" : ""));
   }

   private static MemorySegment texDescriptor(int w, int h) throws Throwable {
      MethodHandle h4 = handle(FunctionDescriptor.of(P, P, P, J, J, J, ValueLayout.JAVA_BOOLEAN));
      MemorySegment d = (MemorySegment)h4.invokeExact(self(cls("MTLTextureDescriptor")),
         sel("texture2DDescriptorWithPixelFormat:width:height:mipmapped:"), 80L, (long)w, (long)h, false);
      sendL(d, "setUsage:", 1L); // MTLTextureUsageShaderRead
      sendL(d, "setStorageMode:", 0L); // MTLStorageModeShared
      return d;
   }

   private static void putNumber(MemorySegment dict, String keySymbol, int value) throws Throwable {
      SymbolLookup ios = SymbolLookup.libraryLookup("/System/Library/Frameworks/IOSurface.framework/IOSurface", Arena.global());
      MemorySegment key = ios.find(keySymbol).orElseThrow().reinterpret(8).get(P, 0);
      MemorySegment num = (MemorySegment)handle(FunctionDescriptor.of(P, P, P, I)).invokeExact(self(cls("NSNumber")), sel("numberWithInt:"), value);
      handle(FunctionDescriptor.ofVoid(P, P, P, P)).invokeExact(self(dict), sel("setObject:forKey:"), num, key);
   }

   private static void fail(Throwable t) {
      failed = true;
      Log.warn("mac present: Metal bridge off, back to glfwSwapBuffers: " + t);
      try {
         if (layer != null) {
            sendV(layer, "removeFromSuperlayer");
         }
      } catch (Throwable ignored) {
      }
   }

   // --- Objective-C runtime helpers --------------------------------------------------------------

   private static MemorySegment self(MemorySegment o) {
      return o;
   }

   private static MemorySegment cls(String name) throws Throwable {
      try (Arena a = Arena.ofConfined()) {
         return (MemorySegment)getClass.invokeExact(a.allocateFrom(name));
      }
   }

   private static MemorySegment sel(String name) throws Throwable {
      MemorySegment s = sels.get(name);
      if (s == null) {
         try (Arena a = Arena.ofConfined()) {
            s = (MemorySegment)selRegister.invokeExact(a.allocateFrom(name));
         }
         sels.put(name, s);
      }
      return s;
   }

   private static MethodHandle handle(FunctionDescriptor fd) {
      return sends.computeIfAbsent(fd, d -> linker.downcallHandle(msgSendAddr, d));
   }

   private static MemorySegment sendP(MemorySegment obj, String selector) throws Throwable {
      return (MemorySegment)handle(FunctionDescriptor.of(P, P, P)).invokeExact(obj, sel(selector));
   }

   private static long sendJ(MemorySegment obj, String selector) throws Throwable {
      return (long)handle(FunctionDescriptor.of(J, P, P)).invokeExact(obj, sel(selector));
   }

   private static double sendD(MemorySegment obj, String selector) throws Throwable {
      return (double)handle(FunctionDescriptor.of(D, P, P)).invokeExact(obj, sel(selector));
   }

   private static void sendV(MemorySegment obj, String selector) throws Throwable {
      handle(FunctionDescriptor.ofVoid(P, P)).invokeExact(obj, sel(selector));
   }

   private static void sendV(MemorySegment obj, String selector, MemorySegment arg) throws Throwable {
      handle(FunctionDescriptor.ofVoid(P, P, P)).invokeExact(obj, sel(selector), arg);
   }

   private static void sendL(MemorySegment obj, String selector, long arg) throws Throwable {
      handle(FunctionDescriptor.ofVoid(P, P, J)).invokeExact(obj, sel(selector), arg);
   }

   private static void sendZ(MemorySegment obj, String selector, boolean arg) throws Throwable {
      handle(FunctionDescriptor.ofVoid(P, P, ValueLayout.JAVA_BOOLEAN)).invokeExact(obj, sel(selector), arg);
   }
}
