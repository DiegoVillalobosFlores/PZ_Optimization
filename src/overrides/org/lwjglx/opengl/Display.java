package org.lwjglx.opengl;

import imgui.ImDrawData;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.extension.implot.ImPlot;
import imgui.extension.implot.ImPlotContext;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import java.awt.Canvas;
import java.nio.IntBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.glfw.GLFWWindowFocusCallback;
import org.lwjgl.glfw.GLFWWindowIconifyCallback;
import org.lwjgl.glfw.GLFWWindowPosCallback;
import org.lwjgl.glfw.GLFWWindowRefreshCallback;
import org.lwjgl.glfw.GLFWWindowSizeCallback;
import org.lwjgl.glfw.GLFWImage.Buffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLDebugMessageCallback;
import org.lwjgl.system.MemoryStack;
import org.lwjglx.LWJGLException;
import org.lwjglx.LWJGLUtil;
import org.lwjglx.input.Keyboard;
import org.lwjglx.input.Mouse;
import zombie.GameWindow;
import zombie.characters.IsoPlayer;
import zombie.core.Clipboard;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.math.PZMath;
import zombie.core.opengl.RenderThread;
import zombie.debug.DebugLog;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.ui.UIManager;

public class Display {
   private static String windowTitle = "Game";
   private static boolean displayCreated;
   private static boolean displayFocused;
   private static boolean displayVisible = true;
   private static boolean displayDirty;
   private static boolean displayResizable = true;
   private static boolean vsyncEnabled = true;
   private static DisplayMode gameWindowMode = new DisplayMode(640, 480);
   private static DisplayMode desktopDisplayMode = new DisplayMode(640, 480);
   private static int displayX;
   private static int displayY;
   private static boolean displayResized;
   private static int displayWidth;
   private static int displayHeight;
   private static int displayFramebufferWidth;
   private static int displayFramebufferHeight;
   private static Buffer displayIcons;
   private static long monitor;
   private static boolean isBorderlessWindow;
   private static boolean latestResized;
   private static int latestWidth;
   private static int latestHeight;
   public static GLCapabilities capabilities;
   public static ImGuiImplGlfw imGuiGlfw;
   public static ImGuiImplGl3 imGuiGl3;
   private static ImPlotContext imPlotContext;
   private static final double[] mouseCursorPosX = new double[1];
   private static final double[] mouseCursorPosY = new double[1];
   private static int mouseCursorState = -1;
   static int frameCount;

   public static void init() {
      if (LWJGLUtil.getPlatform() == 1) {
         if (("1".equals(System.getProperty("zomboid.wayland")) || pzopt.Hdr.wantsWayland()) && GLFW.glfwPlatformSupported(393219)) { // pzopt: HDR output needs the Wayland platform
            GLFW.glfwInitHint(327683, 393219);
         } else {
            GLFW.glfwInitHint(327683, 393220);
         }
      }

      if (!GLFW.glfwInit()) {
         throw new IllegalStateException("Unable to initialize GLFW");
      }

      if (GLFW.glfwGetPlatform() != 393219) {
         Keyboard.create();
      }

      monitor = GLFW.glfwGetPrimaryMonitor();
      GLFWVidMode vidmode = GLFW.glfwGetVideoMode(monitor);
      int monitorWidth = vidmode.width();
      int monitorHeight = vidmode.height();
      int monitorBitPerPixel = vidmode.redBits() + vidmode.greenBits() + vidmode.blueBits();
      int monitorRefreshRate = vidmode.refreshRate();
      desktopDisplayMode = new DisplayMode(monitorWidth, monitorHeight, monitorBitPerPixel, monitorRefreshRate);
   }

   public static void create(PixelFormat pixel_format) throws LWJGLException {
      GLFW.glfwWindowHint(135178, pixel_format.getAccumulationBitsPerPixel());
      GLFW.glfwWindowHint(135172, pixel_format.getAlphaBits());
      GLFW.glfwWindowHint(135179, pixel_format.getAuxBuffers());
      GLFW.glfwWindowHint(135173, pixel_format.getDepthBits());
      GLFW.glfwWindowHint(135181, pixel_format.getSamples());
      GLFW.glfwWindowHint(135174, pixel_format.getStencilBits());
      create();
      if (GLFW.glfwGetPlatform() == 393219) {
         Keyboard.create();
      }
   }

   public static void create() throws LWJGLException {
      if (Display.Window.handle != 0L) {
         GLFW.glfwDestroyWindow(Display.Window.handle);
      }

      GLFWVidMode vidmode = GLFW.glfwGetVideoMode(monitor);
      int monitorWidth = vidmode.width();
      int monitorHeight = vidmode.height();
      int monitorBitPerPixel = vidmode.redBits() + vidmode.greenBits() + vidmode.blueBits();
      int monitorRefreshRate = vidmode.refreshRate();
      desktopDisplayMode = new DisplayMode(monitorWidth, monitorHeight, monitorBitPerPixel, monitorRefreshRate);
      GLFW.glfwDefaultWindowHints();
      GLFW.glfwWindowHint(139265, 196609);
      Display.Callbacks.errorCallback = GLFWErrorCallback.createPrint(System.err);
      GLFW.glfwSetErrorCallback(Display.Callbacks.errorCallback);
      if (Core.debug) {
         GLFW.glfwWindowHint(139271, 1);
      }

      GLFW.glfwWindowHint(131076, 0);
      GLFW.glfwWindowHint(131075, displayResizable ? 1 : 0);
      if (LWJGLUtil.getPlatform() == 2) {
         GLFW.glfwWindowHint(143361, 0);
      }

      boolean bDebug = Core.debug && "true".equalsIgnoreCase(System.getProperty("org.lwjgl.util.Debug"));
      GLFW.glfwWindowHint(139271, bDebug ? 1 : 0);
      pzopt.Hdr.windowHints(); // pzopt: HDR output, an FP16 default framebuffer
      // pzopt: create the window at the size and mode the game asks for a moment later (Core.width x Core.height,
      // fullscreen per the option) instead of the shim's 640x480 placeholder that Core.setDisplayModeInternal
      // resizes. Under NVIDIA PRIME render offload on XWayland (Dell GTX 960M, 2026-09-22) the GL drawable kept the
      // size the context was first made current with: the fullscreen 1920x1080 window showed the frame's
      // bottom-left 640x480, the rest black (the stock shim too). Created at the final size, nothing has to
      // follow a resize; Core's own switch then finds the window already right and does nothing.
      long pzoptMonitor = 0L;
      Core pzoptCore = Core.getInstance();
      if (pzoptCore != null && (Core.width > 0 && Core.height > 0 || pzoptCore.isFullScreen())) {
         boolean pzoptFullscreen = pzoptCore.isFullScreen();
         int w = Core.width > 0 ? Core.width : monitorWidth;
         int h = Core.height > 0 ? Core.height : monitorHeight;
         if (!pzoptFullscreen && pzoptCore.getOptionBorderlessWindow()) {
            w = monitorWidth;
            h = monitorHeight;
            // pzopt: on Windows borderless is created undecorated, i.e. already in the state Core's switch would put
            // it in. A decorated desktop-sized window there is either clamped to the screen's max track size
            // (1920x1058 under Wine) or, when it fits the max track size (multi-monitor desktops), left at the
            // caption's offset (client at 8,31) by the decoration removal, which keeps the client rect, while the
            // switch then finds the size unchanged and never moves it to 0,0: the menu showed off centre and clicks
            // landed above the cursor. X11 window managers resize and place the window themselves and nobody reported
            // it there; with the window mapped undecorated at the screen size the Workshop uploader's native confirm
            // dialog was not on top (desktop, KWin, 2026-09-23), so Linux keeps the stock decorated creation.
            if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WIN32) {
               GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, 0); // pzopt: see above
               isBorderlessWindow = true; // pzopt: calcWindowPos places it at the monitor origin, Core's check sees it done
            }
            if (pzoptBorderlessFullscreenApplies(w, h)) { // pzopt: borderless = a fullscreen window at the desktop mode (VRR), see pzoptBorderlessFs
               isBorderlessWindow = true; // pzopt: Core's switch finds the borderless state already in place
               pzoptBorderlessFs = true; // pzopt
               GLFW.glfwWindowHint(GLFW.GLFW_AUTO_ICONIFY, 0); // pzopt: stays on screen when it loses focus, like a borderless window
               GLFW.glfwWindowHint(GLFW.GLFW_REFRESH_RATE, monitorRefreshRate); // pzopt: the desktop's rate: no video mode switch
               pzoptMonitor = monitor; // pzopt
            }
         }
         if (pzoptFullscreen) {
            GLFW.glfwWindowHint(GLFW.GLFW_REFRESH_RATE, monitorRefreshRate); // the desktop's rate: no video mode switch
            pzoptMonitor = monitor;
            gameWindowMode = new DisplayMode(w, h, monitorBitPerPixel, monitorRefreshRate);
         } else {
            gameWindowMode = new DisplayMode(w, h);
         }
      }
      Display.Window.handle = GLFW.glfwCreateWindow(gameWindowMode.getWidth(), gameWindowMode.getHeight(), windowTitle, pzoptMonitor, 0L);
      if (Display.Window.handle != 0L) {
         // pzopt: keep gameWindowMode equal to the size the window really got. Fullscreen: GLFW may have picked
         // another video mode. Windowed: Windows clamps a new decorated window to the screen's max track size, so a
         // 1920x1080 window on a 1920x1080 screen is created 1920x1061; with gameWindowMode already 1920x1080,
         // Core's switch right after found "no change" and never issued the (unclamped) resize the stock 640x480
         // placeholder always got, and every launch stayed at 1920x1061. Recording the clamped size lets it resize.
         int[] pw = new int[1];
         int[] ph = new int[1];
         GLFW.glfwGetWindowSize(Display.Window.handle, pw, ph);
         if (pw[0] > 0 && ph[0] > 0 && (pw[0] != gameWindowMode.getWidth() || ph[0] != gameWindowMode.getHeight())) {
            gameWindowMode = pzoptMonitor != 0L ? new DisplayMode(pw[0], ph[0], monitorBitPerPixel, monitorRefreshRate) : new DisplayMode(pw[0], ph[0]);
         }
      }
      if (Display.Window.handle == 0L) {
         throw new IllegalStateException("Failed to create Display window");
      }

      if (GLFW.glfwGetPlatform() != 393218 && GLFW.glfwGetPlatform() != 393219) {
         GLFW.glfwSetWindowIcon(Display.Window.handle, displayIcons);
      }

      Display.Callbacks.noise = bDebug;
      Display.Callbacks.initCallbacks();
      calcWindowPos(isBorderlessWindow() || isFullscreen());
      GLFW.glfwSetWindowPos(Display.Window.handle, displayX, displayY);
      GLFW.glfwShowWindow(Display.Window.handle);
      GLFW.glfwMakeContextCurrent(Display.Window.handle);
      capabilities = GL.createCapabilities();
      pzopt.Hdr.windowCreated(Display.Window.handle); // pzopt: HDR output, tag the surface with an HDR image description
      GLFW.glfwSwapInterval(0);
      GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
      GL11.glClear(16640);
      GLFW.glfwSwapBuffers(Display.Window.handle);
      setVSyncEnabled(vsyncEnabled);
      if (bDebug && capabilities.OpenGL43) {
         int[] ids = new int[]{131185};
         GL43.glDebugMessageControl(33350, 33361, 4352, ids, false);
      }

      int[] _width = new int[1];
      int[] _height = new int[1];
      GLFW.glfwGetWindowSize(Display.Window.handle, _width, _height);
      displayWidth = latestWidth = _width[0];
      displayHeight = latestHeight = _height[0];
      displayCreated = true;
      pzopt.Vrr.start(); // pzopt: variable refresh state (Linux DRM VRR_ENABLED poller)
      if (Core.isImGui()) {
         imGuiGl3 = new ImGuiImplGl3();
         imGuiGlfw = new ImGuiImplGlfw();
         ImGui.createContext();
         imPlotContext = ImPlot.createContext();
         ImGuiIO io = ImGui.getIO();
         if (Core.isUseViewports()) {
            io.addConfigFlags(1024);
         }

         io.addConfigFlags(64);
         io.addConfigFlags(32768);
         io.addConfigFlags(16384);
         String glslVersion = null;
         if (GLFW.glfwGetPlatform() == 393218) {
            glslVersion = "#version 120";
         }

         imGuiGl3.init(glslVersion);
         imGuiGlfw.init(Display.Window.handle, true);
      }
   }

   public static boolean isCreated() {
      return displayCreated;
   }

   public static boolean isActive() {
      return displayFocused;
   }

   public static boolean isVisible() {
      return displayVisible;
   }

   public static void setLocation(int new_x, int new_y) {
      System.out.println("TODO: Implement Display.setLocation(int, int)");
   }

   public static boolean isVSyncEnabledPzopt() { // pzopt: vblankLock applies to a vsync'd swap only
      return vsyncEnabled; // pzopt
   } // pzopt

   public static void setVSyncEnabled(boolean sync) {
      vsyncEnabled = sync;
      if (sync) {
         GLFW.glfwSwapInterval(pzopt.LowLatency.vsyncInterval()); // pzopt: vsyncAdaptive, -1 (late frames tear instead of waiting a refresh) when the driver has swap_control_tear
         pzopt.VsyncLock.intervalReset(); // pzopt: vsyncLock, re-apply its interval before the next swap
      } else {
         GLFW.glfwSwapInterval(0);
      }
   }

   public static long getWindow() {
      return Display.Window.handle;
   }

   public static void update() {
      update(true);
   }

   public static void update(boolean processMessages) {
      try {
         pzopt.VsyncLock.beforeSwap(); // pzopt: vsyncLock, the swap interval the cap asks for
         pzopt.LowLatency.beforeSwap(); // pzopt: reflexSleep, how long the swap blocks (vsync)
         swapBuffers();
         pzopt.InputLag.swapped(); // pzopt: harness input-lag probe (--flag inputlag=1), the frame's swap returned
         pzopt.DriveJitter.swapped(); // pzopt: devDriveJitter, the swap of the frame acquired last
         pzopt.LowLatency.afterSwap(); // pzopt: gpuMaxFrames, fence the frame and wait for the GPU queue to drain
         displayDirty = false;
      } catch (LWJGLException e) {
         throw new RuntimeException(e);
      }

      if (processMessages) {
         processMessages();
      }
   }

   private static void updateMouseCursor() {
      int cursorState = RenderThread.isCursorVisible() ? 212993 : 212994;
      boolean lockCursorToWindow = Core.getInstance().getOptionLockCursorToWindow();
      if (lockCursorToWindow) {
         cursorState = 212995;
      }

      if (mouseCursorState != cursorState) {
         boolean bWasDisabled = mouseCursorState == 212995;
         if (bWasDisabled) {
            GLFW.glfwGetCursorPos(getWindow(), mouseCursorPosX, mouseCursorPosY);
         }

         mouseCursorState = cursorState;
         GLFW.glfwSetInputMode(getWindow(), 208897, cursorState);
         if (bWasDisabled) {
            GLFW.glfwSetCursorPos(getWindow(), mouseCursorPosX[0], mouseCursorPosY[0]);
         }
      }

      if (lockCursorToWindow) {
         GLFW.glfwGetCursorPos(getWindow(), mouseCursorPosX, mouseCursorPosY);
         int posX = (int)mouseCursorPosX[0];
         int posY = (int)mouseCursorPosY[0];
         mouseCursorPosX[0] = PZMath.clamp((int)mouseCursorPosX[0], 0, latestWidth);   // pzopt: screen coordinates, not framebuffer
         mouseCursorPosY[0] = PZMath.clamp((int)mouseCursorPosY[0], 0, latestHeight);
         if (posX != (int)mouseCursorPosX[0] || posY != (int)mouseCursorPosY[0]) {
            GLFW.glfwSetCursorPos(getWindow(), mouseCursorPosX[0], mouseCursorPosY[0]);
         }
      }
   }

   public static void processMessages() {
      GLFW.glfwPollEvents();
      Keyboard.poll();
      Mouse.poll();
      updateMouseCursor();
      pzopt.InputLag.afterEvents(); // pzopt: harness input-lag probe, what glfwPollEvents delivered
      if (latestResized) {
         latestResized = false;
         displayResized = true;
         displayWidth = latestWidth;
         displayHeight = latestHeight;
         if (gameWindowMode.getFrequency() > 0) {
            gameWindowMode = new DisplayMode(displayWidth, displayHeight, gameWindowMode.getBitsPerPixel(), gameWindowMode.getFrequency());
         } else {
            gameWindowMode = new DisplayMode(displayWidth, displayHeight);
         }
      } else {
         displayResized = false;
      }
   }

   public static void swapBuffers() throws LWJGLException {
      pzopt.FrameCapture.beforeSwap(); // pzopt: devCapture, the frame sequence rig
      pzopt.Hdr.beforeSwap(); // pzopt: HDR output, encode the frame for the HDR surface
      if (pzopt.HdrMac.present(Display.Window.handle)) { // pzopt: HDR output on macOS, presented through an EDR Metal layer
         return;
      }
      if (pzopt.HdrWin.present(Display.Window.handle)) { // pzopt: HDR output on Windows, presented through a DXGI scRGB swap chain
         return;
      }
      if (pzoptHudSwap()) {
         return;
      }
      if (pzopt.MacPresent.present(Display.Window.handle)) { // pzopt: macOS Metal present bridge (ProMotion / Adaptive-Sync timing)
         return; // pzopt
      }
      GLFW.glfwSwapBuffers(Display.Window.handle);
   }

   // pzopt: MangoHud on native Wayland. GLFW resolves eglSwapBuffers with dlsym on its private libEGL handle,
   // which no LD_PRELOAD interposition reaches (on X11 the Steam overlay's dlsym hook chains to MangoHud;
   // on Wayland there is no such chain, so the HUD never appeared: wl-gl60-1, 2026-09-19). When MangoHud's
   // OpenGL library is already loaded in this process, the platform is Wayland and the overrides are
   // enabled, the swap is handed to the eglSwapBuffers that library exports (it draws the HUD and then
   // calls the real one) with GLFW's EGL display and surface. Anything unexpected falls back to
   // glfwSwapBuffers for good, with one log line.
   private static java.lang.invoke.MethodHandle pzoptHudSwapHandle;
   private static long pzoptHudDisplay;
   private static long pzoptHudSurface;
   private static long pzoptHudWindow;
   private static boolean pzoptHudChecked;

   private static boolean pzoptHudSwap() {
      if (!pzoptHudChecked) {
         pzoptHudChecked = true;
         pzoptHudInit();
      }
      if (pzoptHudSwapHandle == null) {
         return false;
      }
      try {
         long window = Display.Window.handle;
         if (window != pzoptHudWindow) {
            pzoptHudWindow = window;
            pzoptHudDisplay = org.lwjgl.glfw.GLFWNativeEGL.glfwGetEGLDisplay();
            pzoptHudSurface = org.lwjgl.glfw.GLFWNativeEGL.glfwGetEGLSurface(window);
         }
         if (pzoptHudDisplay == 0L || pzoptHudSurface == 0L) {
            throw new IllegalStateException("no EGL display/surface for the window");
         }
         int ok = (int)pzoptHudSwapHandle.invokeExact(java.lang.foreign.MemorySegment.ofAddress(pzoptHudDisplay), java.lang.foreign.MemorySegment.ofAddress(pzoptHudSurface));
         if (ok == 0) {
            throw new IllegalStateException("eglSwapBuffers returned EGL_FALSE");
         }
         return true;
      } catch (Throwable t) {
         pzoptHudSwapHandle = null;
         pzopt.Log.warn("MangoHud swap failed, back to glfwSwapBuffers: " + t);
         return false;
      }
   }

   private static void pzoptHudInit() {
      try {
         if (!pzopt.Overrides.enabled() || GLFW.glfwGetPlatform() != 393219 || !"1".equals(System.getenv("MANGOHUD"))) {
            return;
         }
         boolean loaded = false;
         for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/self/maps"))) {
            if (line.contains("libMangoHud_opengl.so")) {
               loaded = true;
               break;
            }
         }
         if (!loaded) {
            return; // not preloaded: nothing to hand the swap to (and no reason to load it now)
         }
         java.lang.foreign.SymbolLookup lookup = java.lang.foreign.SymbolLookup.libraryLookup("libMangoHud_opengl.so", java.lang.foreign.Arena.global());
         java.lang.foreign.MemorySegment fn = lookup.find("eglSwapBuffers").orElse(null);
         if (fn == null) {
            pzopt.Log.warn("MangoHud library has no eglSwapBuffers export; HUD stays off on Wayland");
            return;
         }
         pzoptHudSwapHandle = java.lang.foreign.Linker.nativeLinker().downcallHandle(fn,
            java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.ADDRESS));
         pzopt.Log.info("MangoHud on Wayland: presenting through the preloaded library's eglSwapBuffers");
      } catch (Throwable t) {
         pzoptHudSwapHandle = null;
         pzopt.Log.warn("MangoHud on Wayland not set up: " + t);
      }
   }

   public static void destroy() {
      if (Core.isImGui()) {
         if (imGuiGl3 != null) {
            imGuiGl3.dispose();
            imGuiGlfw.dispose();
         }

         ImPlot.destroyContext(imPlotContext);
         ImGui.destroyContext();
      }

      Display.Callbacks.releaseCallbacks();
      GLFW.glfwDestroyWindow(Display.Window.handle);
      displayCreated = false;
   }

   public static void setDisplayModeAndFullscreen(DisplayMode mode) throws LWJGLException {
      setDisplayModeAndFullscreenInternal(mode, mode.isFullscreenCapable());
   }

   public static void setFullscreen(boolean fullscreen) {
      setDisplayModeAndFullscreenInternal(gameWindowMode, fullscreen);
   }

   public static boolean isFullscreen() {
      return !isCreated() ? Core.getInstance().isFullScreen() : GLFW.glfwGetWindowMonitor(Display.Window.handle) != 0L && !pzoptBorderlessFs; // pzopt: a borderless monitor window is not the fullscreen option
   }

   /**
    * pzopt: the window is a borderless one that GLFW holds as a monitor window at the desktop's own video mode. Only a
    * window the compositor knows as fullscreen gets variable refresh: KWin's "automatic" VRR policy, Mutter and gamescope
    * key on the fullscreen state, which a desktop-sized undecorated window does not have (desktop, KWin 6.7, 2026-09-24:
    * VRR_ENABLED stayed 0 through a borderless run and was 1 for the whole fullScreen=true one). A GLFW monitor window at
    * the current mode sets _NET_WM_STATE_FULLSCREEN (X11) / xdg_toplevel.set_fullscreen (Wayland) without a mode switch.
    * Auto-iconify is off, so it stays on screen when focus moves away like a borderless window does. To Core it stays the
    * borderless option: isFullscreen() is false, so options.ini keeps fullScreen=false.
    */
   private static boolean pzoptBorderlessFs;

   private static boolean pzoptBorderlessFullscreenApplies(int w, int h) {
      if (!pzopt.Overrides.enabled() || desktopDisplayMode == null || w != desktopDisplayMode.getWidth() || h != desktopDisplayMode.getHeight()) {
         return false;
      }
      String mode = pzopt.Config.BORDERLESS_FULLSCREEN;
      int platform = GLFW.glfwGetPlatform();
      boolean linux = platform == GLFW.GLFW_PLATFORM_X11 || platform == GLFW.GLFW_PLATFORM_WAYLAND;
      return "true".equalsIgnoreCase(mode) || "auto".equalsIgnoreCase(mode) && linux;
   }

   /** pzopt: the window is a borderless fullscreen (monitor) window; for the overlay / Vrr status. */
   public static boolean pzoptIsBorderlessFullscreen() {
      return pzoptBorderlessFs;
   }

   public static void setBorderlessWindow(boolean borderless) {
      isBorderlessWindow = borderless;
      if (borderless && isCreated() && pzopt.MacPresent.nativeFullscreenForBorderless()) { // pzopt: macOS: a native fullscreen Space (Adaptive-Sync / ProMotion timing), see MacPresent
         pzopt.MacPresent.requestNativeFullscreen(); // pzopt: done on the next frame, after Core's mode switch
         return; // pzopt: keeps its decoration, hidden in fullscreen
      } // pzopt
      if (isCreated()) {
         GLFW.glfwSetWindowAttrib(getWindow(), 131077, borderless ? 0 : 1);
      }
   }

   public static boolean isBorderlessWindow() {
      return isBorderlessWindow;
   }

   public static void setDisplayMode(DisplayMode dm) throws LWJGLException {
      if (dm == null) {
         throw new NullPointerException();
      }

      setDisplayModeAndFullscreenInternal(dm, dm.isFullscreenCapable() && isFullscreen());
   }

   private static int getTargetFrequency(DisplayMode mode) {
      return desktopDisplayMode.getHeight() == mode.getHeight() && desktopDisplayMode.getWidth() == mode.getWidth() ? desktopDisplayMode.getFrequency() : -1;
   }

   private static void setDisplayModeAndFullscreenInternal(DisplayMode mode, boolean fullscreen) {
      boolean wasFullscreen = isFullscreen();
      DisplayMode oldMode = gameWindowMode;
      gameWindowMode = mode;
      Core.setFullScreen(fullscreen);
      boolean borderlessFs = !fullscreen && isBorderlessWindow() && pzoptBorderlessFullscreenApplies(mode.getWidth(), mode.getHeight()); // pzopt: see pzoptBorderlessFs
      boolean wantMonitor = fullscreen || borderlessFs; // pzopt
      boolean hasMonitor = isCreated() && GLFW.glfwGetWindowMonitor(Display.Window.handle) != 0L; // pzopt
      if (isCreated() && (wasFullscreen != fullscreen || hasMonitor != wantMonitor || !gameWindowMode.equals(oldMode) || !fullscreen && !borderlessFs && pzoptBorderlessMisplaced())) { // pzopt: also attach / detach a borderless fullscreen window, and re-place a borderless window the decoration removal left off the monitor origin
         GLFW.glfwHideWindow(Display.Window.handle);
         calcWindowPos(fullscreen || isBorderlessWindow());
         pzoptBorderlessFs = borderlessFs; // pzopt
         GLFW.glfwSetWindowAttrib(Display.Window.handle, GLFW.GLFW_AUTO_ICONIFY, borderlessFs ? 0 : 1); // pzopt: a borderless window stays up without focus
         GLFW.glfwSetWindowMonitor(
            Display.Window.handle,
            wantMonitor ? monitor : 0L, // pzopt: borderless fullscreen is a monitor window too
            displayX,
            displayY,
            gameWindowMode.getWidth(),
            gameWindowMode.getHeight(),
            fullscreen ? getTargetFrequency(mode) : borderlessFs ? desktopDisplayMode.getFrequency() : -1 // pzopt: borderless keeps the desktop mode
         );
         if (GLFW.glfwGetPlatform() != 393218 && GLFW.glfwGetPlatform() != 393219) {
            GLFW.glfwSetWindowIcon(Display.Window.handle, displayIcons);
         }

         GLFW.glfwShowWindow(Display.Window.handle);
         GLFW.glfwFocusWindow(Display.Window.handle);
         pzoptAwaitWindowSize(); // pzopt: the window manager applies the new size after this call returns
         GLFW.glfwMakeContextCurrent(0L);
         GLFW.glfwMakeContextCurrent(Display.Window.handle);
         GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
         GLFW.glfwSwapInterval(0);
         GL11.glClear(16640);
         GLFW.glfwSwapBuffers(Display.Window.handle);
         setVSyncEnabled(vsyncEnabled);
      }
   }

   /**
    * pzopt: a borderless window whose client area is not where calcWindowPos puts it (the monitor origin for a
    * desktop-sized one). Switching windowed -> borderless at the same size only removes the decoration, which keeps
    * the client rect, so the window stayed at the old caption offset with its bottom rows off screen.
    */
   private static boolean pzoptBorderlessMisplaced() {
      if (!isBorderlessWindow() || GLFW.glfwGetWindowMonitor(Display.Window.handle) != 0L || GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND) { // Wayland has no window position
         return false;
      }
      int[] x = new int[1];
      int[] y = new int[1];
      GLFW.glfwGetWindowPos(Display.Window.handle, x, y);
      int wantX = Math.max(0, (desktopDisplayMode.getWidth() - gameWindowMode.getWidth()) / 2);
      int wantY = Math.max(0, (desktopDisplayMode.getHeight() - gameWindowMode.getHeight()) / 2);
      return x[0] != wantX || y[0] != wantY;
   }

   /**
    * pzopt: after glfwSetWindowMonitor the window manager resizes the window asynchronously; the stock shim
    * re-bound the context at once, so a driver that latches the drawable geometry on MakeCurrent (NVIDIA PRIME
    * render offload on XWayland) kept presenting the old size. Wait, briefly, until the server reports the
    * requested size (a fullscreen window may legitimately settle on another video mode: stop when the size
    * stops changing), then record it so getWidth()/getHeight() are right before the ConfigureNotify is polled.
    */
   private static void pzoptAwaitWindowSize() {
      int wantW = gameWindowMode.getWidth();
      int wantH = gameWindowMode.getHeight();
      int[] w = new int[1];
      int[] h = new int[1];
      int lastW = -1;
      int lastH = -1;
      int stable = 0;
      long deadline = System.nanoTime() + 1_000_000_000L;
      while (System.nanoTime() < deadline) {
         GLFW.glfwGetFramebufferSize(Display.Window.handle, w, h);
         if (w[0] == wantW && h[0] == wantH) {
            break;
         }
         if (w[0] == lastW && h[0] == lastH) {
            if (++stable >= 20) { // 200 ms without a change: the window manager is done
               break;
            }
         } else {
            stable = 0;
            lastW = w[0];
            lastH = h[0];
         }
         try {
            Thread.sleep(10L);
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
         }
      }
      if (w[0] > 0 && h[0] > 0) {
         displayFramebufferWidth = w[0];
         displayFramebufferHeight = h[0];
         GLFW.glfwGetWindowSize(Display.Window.handle, w, h);
         if (w[0] > 0 && h[0] > 0) {
            latestWidth = w[0];
            latestHeight = h[0];
         }
      }
   }

   private static void calcWindowPos(boolean fullscreen) {
      MemoryStack stack = MemoryStack.stackPush();

      try {
         IntBuffer fbw = stack.callocInt(1);
         IntBuffer fbh = stack.callocInt(1);
         GLFW.glfwGetFramebufferSize(Display.Window.handle, fbw, fbh);
         displayFramebufferWidth = fbw.get(0);
         displayFramebufferHeight = fbh.get(0);
         IntBuffer fblb = stack.callocInt(1);
         IntBuffer fbtb = stack.callocInt(1);
         GLFW.glfwGetWindowFrameSize(Display.Window.handle, fblb, fbtb, null, null);
         int leftFrameSize = fblb.get(0);
         int topFrameSize = fbtb.get(0);
         displayWidth = gameWindowMode.getWidth();
         displayHeight = gameWindowMode.getHeight();
         if (fullscreen) {
            leftFrameSize = 0;
            topFrameSize = 0;
         }

         displayX = leftFrameSize + (desktopDisplayMode.getWidth() - gameWindowMode.getWidth()) / 2;
         displayY = topFrameSize + (desktopDisplayMode.getHeight() - gameWindowMode.getHeight()) / 2;
         if (gameWindowMode.getWidth() > desktopDisplayMode.getWidth()) {
            displayX = leftFrameSize;
         }

         if (gameWindowMode.getHeight() > desktopDisplayMode.getHeight()) {
            displayY = topFrameSize;
         }

         if (Core.isUseGameViewport()) {
            displayY = topFrameSize;
            displayX = 0;
            displayWidth -= leftFrameSize * 2;
            displayHeight -= topFrameSize;
         }
      } catch (Throwable var9) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var8) {
               var9.addSuppressed(var8);
            }
         }

         throw var9;
      }

      if (stack != null) {
         stack.close();
      }
   }

   public static DisplayMode getDisplayMode() {
      return gameWindowMode;
   }

   public static DisplayMode[] getAvailableDisplayModes() throws LWJGLException {
      org.lwjgl.glfw.GLFWVidMode.Buffer modes = GLFW.glfwGetVideoModes(GLFW.glfwGetPrimaryMonitor());
      DisplayMode[] displayModes = new DisplayMode[modes.capacity()];

      for (int i = 0; i < displayModes.length; i++) {
         modes.position(i);
         int w = modes.width();
         int h = modes.height();
         int b = modes.redBits() + modes.greenBits() + modes.blueBits();
         int r = modes.refreshRate();
         displayModes[i] = new DisplayMode(w, h, b, r);
      }

      return displayModes;
   }

   public static DisplayMode getDesktopDisplayMode() {
      return desktopDisplayMode;
   }

   public static boolean wasResized() {
      return displayResized;
   }

   public static int getX() {
      return displayX;
   }

   public static int getY() {
      return displayY;
   }

   // pzopt: the game treats getWidth()/getHeight() as the size in pixels of what it draws into. GLFW reports
   // the window size in screen coordinates; on a scaled Wayland desktop (e.g. KDE at 125 %) those are smaller
   // than the framebuffer (GLFW_SCALE_FRAMEBUFFER is on by default in GLFW 3.4), so the stock code drew a
   // 4096x1728 viewport into a 5120x2160 buffer. Report the framebuffer size; on X11 the two are identical.
   public static int getWidth() {
      return displayFramebufferWidth > 0 ? displayFramebufferWidth : latestWidth; // pzopt: framebuffer size (HiDPI scale)
   }

   public static int getHeight() {
      return displayFramebufferHeight > 0 ? displayFramebufferHeight : latestHeight; // pzopt: framebuffer size (HiDPI scale)
   }

   /** pzopt: framebuffer pixels per screen coordinate along X (1.0 when GLFW does not scale). */
   public static double getFramebufferScaleX() {
      return pzoptScalesFramebuffer() && displayFramebufferWidth > 0 && latestWidth > 0 ? (double)displayFramebufferWidth / latestWidth : 1.0;
   }

   /** pzopt: framebuffer pixels per screen coordinate along Y (1.0 when GLFW does not scale). */
   public static double getFramebufferScaleY() {
      return pzoptScalesFramebuffer() && displayFramebufferHeight > 0 && latestHeight > 0 ? (double)displayFramebufferHeight / latestHeight : 1.0;
   }

   /**
    * pzopt: only Cocoa and Wayland give the framebuffer another size than the window; on Win32 and X11 both are the
    * client area, so a ratio other than 1 there is two sizes recorded at different moments, never a real scale.
    */
   private static boolean pzoptScalesFramebuffer() {
      int platform = GLFW.glfwGetPlatform();
      return platform == GLFW.GLFW_PLATFORM_COCOA || platform == GLFW.GLFW_PLATFORM_WAYLAND;
   }

   public static int getFramebufferWidth() {
      return displayFramebufferWidth;
   }

   public static int getFramebufferHeight() {
      return displayFramebufferHeight;
   }

   public static void setTitle(String title) {
      windowTitle = title;
      if (isCreated()) {
         GLFW.glfwSetWindowTitle(Display.Window.handle, windowTitle);
      }
   }

   public static boolean isCloseRequested() {
      return GLFW.glfwWindowShouldClose(Display.Window.handle);
   }

   public static boolean isDirty() {
      return displayDirty;
   }

   public static void setInitialBackground(float red, float green, float blue) {
      System.out.println("TODO: Implement Display.setInitialBackground(float, float, float)");
   }

   public static void setIcon(Buffer icons) {
      displayIcons = icons;
   }

   public static void setResizable(boolean resizable) {
      displayResizable = resizable;
   }

   public static boolean isResizable() {
      return displayResizable;
   }

   public static void setParent(Canvas parent) throws LWJGLException {
   }

   public static void releaseContext() throws LWJGLException {
      GLFW.glfwMakeContextCurrent(0L);
   }

   public static boolean isCurrent() throws LWJGLException {
      return GLFW.glfwGetCurrentContext() == Display.Window.handle;
   }

   public static void makeCurrent() throws LWJGLException {
      GLFW.glfwMakeContextCurrent(Display.Window.handle);
      GL.setCapabilities(capabilities);
   }

   public static String getAdapter() {
      return "GeNotSupportedAdapter";
   }

   public static String getVersion() {
      return "1.0 NOT SUPPORTED";
   }

   public static void sync(int fps) {
      if (pzopt.LightingSync.ON) { // pzopt: the lighting thread (the only caller) parks to its next update instead of yield-spinning the last ms
         pzopt.LightingSync.sync(fps); // pzopt
         return; // pzopt
      } // pzopt
      Sync.sync(fps);
   }

   public static void imGuiNewFrame() {
      if (Core.isImGui()) {
         imGuiGlfw.newFrame();
         ImGui.newFrame();
         frameCount++;
      }
   }

   public static boolean inImGuiFrame() {
      return frameCount > 0;
   }

   public static void drawImGuiDrawData(ImDrawData imDrawData) {
      if (Core.isImGui()) {
         imGuiGl3.renderDrawData(imDrawData);
         ImGui.freeDrawData(imDrawData);
      }
   }

   public static ImDrawData imguiEndFrame() {
      pzopt.ResumeShot.drawOverWorld(); // pzopt: resumeShot, the last view stays over the world until it is lit, then fades out
      pzopt.Overlay.draw(); // pzopt: performance overlay; Core.EndFrameUI calls this after the UI composite and right before it hands the frame to the render thread
      if (!Core.isImGui()) {
         return null;
      }

      if (frameCount == 0) {
         return null;
      }

      frameCount--;
      ImGui.endFrame();
      ImGui.render();
      if (Core.isUseGameViewport()) {
         SpriteRenderer.instance.glBuffer(12, 0);
      }

      ImDrawData drawData = ImGui.getDrawData();
      if (Core.isUseViewports()) {
         RenderThread.invokeOnRenderContext(() -> {
            long backupWindowPtr = GLFW.glfwGetCurrentContext();
            ImGui.updatePlatformWindows();
            ImGui.renderPlatformWindowsDefault();
            GLFW.glfwMakeContextCurrent(backupWindowPtr);
         });
      }

      return drawData;
   }

   private static final class Callbacks {
      static boolean noise;
      static GLFWErrorCallback errorCallback;
      static GLDebugMessageCallback debugMessageCallback;
      static GLFWKeyCallback keyCallback;
      static GLFWCharCallback charCallback;
      static GLFWCursorPosCallback cursorPosCallback;
      static GLFWMouseButtonCallback mouseButtonCallback;
      static GLFWScrollCallback scrollCallback;
      static GLFWWindowFocusCallback windowFocusCallback;
      static GLFWWindowIconifyCallback windowIconifyCallback;
      static GLFWWindowSizeCallback windowSizeCallback;
      static GLFWWindowPosCallback windowPosCallback;
      static GLFWWindowRefreshCallback windowRefreshCallback;
      static GLFWFramebufferSizeCallback framebufferSizeCallback;

      static void initCallbacks() {
         // pzopt: cursor positions arrive in screen coordinates; the game works in framebuffer pixels (see getWidth)
         cursorPosCallback = GLFWCursorPosCallback.create(
            (windowHnd, xpos, ypos) -> Mouse.addMoveEvent(xpos * Display.getFramebufferScaleX(), ypos * Display.getFramebufferScaleY()) // pzopt: cursor in framebuffer pixels (HiDPI scale)
         );
         GLFW.glfwSetCursorPosCallback(Display.getWindow(), cursorPosCallback);
         mouseButtonCallback = GLFWMouseButtonCallback.create((windowHnd, button, action, mods) -> Mouse.addButtonEvent(button, action == 1));
         GLFW.glfwSetMouseButtonCallback(Display.getWindow(), mouseButtonCallback);
         windowFocusCallback = GLFWWindowFocusCallback.create(
            (windowHnd, focused) -> {
               if (noise) {
                  DebugLog.log("glfwSetWindowFocusCallback focused=" + focused);
               }

               Display.displayFocused = focused;
               if (focused) {
                  Clipboard.rememberCurrentValue();
               }

               if (!focused
                  && Core.getInstance().getOptionFocusloss()
                  && GameWindow.isIngameState()
                  && !Core.exiting
                  && !GameClient.client
                  && !GameServer.server
                  && IsoPlayer.hasInstance()
                  && !IsoPlayer.allPlayersDead()
                  && UIManager.getSpeedControls().isReallyVisible()) {
                  UIManager.getSpeedControls().SetCurrentGameSpeed(0);
               }
            }
         );
         GLFW.glfwSetWindowFocusCallback(Display.getWindow(), windowFocusCallback);
         windowIconifyCallback = GLFWWindowIconifyCallback.create((windowHnd, iconified) -> {
            if (noise) {
               DebugLog.log("glfwSetWindowIconifyCallback iconifed=" + iconified);
            }

            Display.displayVisible = !iconified;
         });
         GLFW.glfwSetWindowIconifyCallback(Display.getWindow(), windowIconifyCallback);
         windowSizeCallback = GLFWWindowSizeCallback.create((window, width, height) -> {
            if (noise) {
               DebugLog.log("glfwSetWindowSizeCallback width,height=" + width + "," + height);
            }

            if (width + height != 0) {
               Display.latestResized = true;
               Display.latestWidth = width;
               Display.latestHeight = height;
            }
         });
         GLFW.glfwSetWindowSizeCallback(Display.getWindow(), windowSizeCallback);
         scrollCallback = GLFWScrollCallback.create((windowHnd, xpos, ypos) -> Mouse.setDWheel(xpos, ypos));
         GLFW.glfwSetScrollCallback(Display.getWindow(), scrollCallback);
         windowPosCallback = GLFWWindowPosCallback.create((windowHnd, xpos, ypos) -> {
            if (noise) {
               DebugLog.log("glfwSetWindowPosCallback x,y=" + xpos + "," + ypos);
            }

            Display.displayX = xpos;
            Display.displayY = ypos;
         });
         GLFW.glfwSetWindowPosCallback(Display.getWindow(), windowPosCallback);
         windowRefreshCallback = GLFWWindowRefreshCallback.create(windowHnd -> Display.displayDirty = true);
         GLFW.glfwSetWindowRefreshCallback(Display.getWindow(), windowRefreshCallback);
         framebufferSizeCallback = GLFWFramebufferSizeCallback.create((windowHnd, width, height) -> {
            if (noise) {
               DebugLog.log("glfwSetFramebufferSizeCallback width,height=" + width + "," + height);
            }

            Display.displayFramebufferWidth = width;
            Display.displayFramebufferHeight = height;
         });
         GLFW.glfwSetFramebufferSizeCallback(Display.getWindow(), framebufferSizeCallback);
         keyCallback = GLFWKeyCallback.create((windowHnd, key, scancode, action, mods) -> Keyboard.addKeyEvent(key, action));
         GLFW.glfwSetKeyCallback(Display.getWindow(), keyCallback);
         charCallback = GLFWCharCallback.create((windowHnd, codepoint) -> Keyboard.addCharEvent((char)codepoint));
         GLFW.glfwSetCharCallback(Display.getWindow(), charCallback);
      }

      static void releaseCallbacks() {
         errorCallback.free();
         if (debugMessageCallback != null) {
            debugMessageCallback.free();
         }

         keyCallback.free();
         charCallback.free();
         cursorPosCallback.free();
         mouseButtonCallback.free();
         scrollCallback.free();
         windowFocusCallback.free();
         windowIconifyCallback.free();
         windowSizeCallback.free();
         windowPosCallback.free();
         windowRefreshCallback.free();
         framebufferSizeCallback.free();
      }
   }

   private static final class Window {
      static long handle;
   }
}
