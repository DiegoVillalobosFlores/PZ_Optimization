package pzopt;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.WGLNVDXInterop;
import org.lwjgl.system.MemoryStack;

/**
 * Windows HDR output for Hdr (written 2026-09-24 without a Windows machine to run it: the maintainer's Windows boot is
 * the test; every step logs and any failure returns to SwapBuffers / SDR for good).
 *
 * GLFW's WGL code never picks a float pixel format, so like macOS the game keeps its 8-bit back buffer and Hdr carries
 * the world gain in its alpha ({@link Hdr#alphaGain}). Presentation goes through DXGI instead of SwapBuffers:
 * - a D3D11 device and a flip-model swap chain on the game's HWND, R16G16B16A16_FLOAT, colour space
 *   RGB_FULL_G10_NONE_P709 (scRGB: linear, sRGB primaries, 1.0 = 80 cd/m²);
 * - one D3D11 R16G16B16A16_FLOAT texture shared with GL through WGL_NV_DX_interop2 (NVIDIA, AMD, Intel drivers);
 * - per frame: lock it, run HdrMac's encode (gamma 2.2 decode x alpha gain, soft knee into the peak, scaled to scRGB
 *   by SDR white / 80) into it, unlock, CopyResource into the swap chain's current buffer, Present.
 * The peak comes from IDXGIOutput6::GetDesc1 (MaxLuminance); SDR white from Config hdrUiNits (0 = 200 cd/m², the
 * Windows default of the "SDR content brightness" slider; reading the slider needs DisplayConfigGetDeviceInfo, not done).
 *
 * COM through java.lang.foreign: vtable slots as documented in the Windows SDK headers (d3d11.h, dxgi1_6.h).
 */
public final class HdrWin {
   private HdrWin() {
   }

   static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
   private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
   private static final java.lang.foreign.AddressLayout P = ValueLayout.ADDRESS;
   private static final int DXGI_FORMAT_R16G16B16A16_FLOAT = 10;
   private static final int DXGI_COLOR_SPACE_RGB_FULL_G10_NONE_P709 = 1;
   private static final int SWAPCHAIN_ALLOW_TEARING = 2048, PRESENT_ALLOW_TEARING = 0x200;

   // vtable slots
   private static final int QI = 0, RELEASE = 2;
   private static final int DEV_CREATE_TEXTURE2D = 5, DEV_GET_IMMEDIATE_CONTEXT = 40;
   private static final int CTX_COPY_RESOURCE = 47;
   private static final int FACTORY2_CREATE_SWAPCHAIN_FOR_HWND = 15;
   private static final int SC_PRESENT = 8, SC_GET_BUFFER = 9, SC_RESIZE_BUFFERS = 13, SC_GET_CONTAINING_OUTPUT = 15, SC3_SET_COLOR_SPACE1 = 38;
   private static final int OUT6_GET_DESC1 = 27;

   private static boolean failed, ready, tearing;
   private static Linker linker;
   private static final Map<FunctionDescriptor, MethodHandle> CALLS = new HashMap<>();
   private static MemorySegment device, context, swapChain, sharedTex;
   private static long dxDevice, dxObject;
   private static int glTex, glFbo, width, height;
   private static int backTex, backFbo, backW, backH, encodeProgram, uSrc, uSize, uP0, uP1;
   public static volatile double peakNits = 1000, sdrWhiteNits = 200;

   static boolean wanted() {
      return WINDOWS && Hdr.REQUESTED && !failed;
   }

   /** Render thread, Display.swapBuffers(): true when the frame was presented through the scRGB swap chain. */
   public static boolean present(long glfwWindow) {
      if (!wanted() || !Hdr.active) {
         return false;
      }
      try {
         int[] fw = new int[1], fh = new int[1];
         GLFW.glfwGetFramebufferSize(glfwWindow, fw, fh);
         if (fw[0] <= 0 || fh[0] <= 0) {
            return false;
         }
         if (!ready) {
            init(glfwWindow, fw[0], fh[0]);
         } else if (fw[0] != width || fh[0] != height) {
            resize(fw[0], fh[0]);
         }
         presentFrame();
         return true;
      } catch (Throwable t) {
         fail(t);
         return false;
      }
   }

   private static void presentFrame() throws Throwable {
      try (MemoryStack st = MemoryStack.stackPush()) {
         PointerBuffer objs = st.pointers(dxObject);
         if (!WGLNVDXInterop.wglDXLockObjectsNV(dxDevice, objs)) {
            throw new IllegalStateException("wglDXLockObjectsNV failed");
         }
         encode();
         if (!WGLNVDXInterop.wglDXUnlockObjectsNV(dxDevice, objs)) {
            throw new IllegalStateException("wglDXUnlockObjectsNV failed");
         }
      }
      try (Arena a = Arena.ofConfined()) {
         MemorySegment buf = a.allocate(P);
         check(call(swapChain, SC_GET_BUFFER, FunctionDescriptor.of(I, P, I, P, P), 0, guid(a, "6f15aaf2-d208-4e89-9ab4-489535d34f9c"), buf), "GetBuffer");
         MemorySegment back = buf.get(P, 0);
         callV(context, CTX_COPY_RESOURCE, FunctionDescriptor.ofVoid(P, P, P), back, sharedTex);
         release(back);
         boolean vsync = zombie.core.Core.getInstance().getOptionVSync();
         int flags = !vsync && tearing ? PRESENT_ALLOW_TEARING : 0;
         int hr = (int)call(swapChain, SC_PRESENT, FunctionDescriptor.of(I, P, I, I), vsync ? 1 : 0, flags);
         if (hr < 0 && hr != 0x087A0001) { // DXGI_STATUS_OCCLUDED is fine
            throw new IllegalStateException("Present hr=0x" + Integer.toHexString(hr));
         }
      }
   }

   /** GL: back buffer -> copy -> the interop texture (scRGB), via HdrMac's shared encode shader. */
   private static void encode() {
      int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
      int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
      int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
      int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
      int[] vp = new int[4];
      GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
      boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
            stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
      GL11.glDisable(GL11.GL_BLEND);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDisable(GL11.GL_STENCIL_TEST);
      if (backTex == 0 || backW != width || backH != height) {
         if (backTex != 0) {
            GL11.glDeleteTextures(backTex);
            GL30.glDeleteFramebuffers(backFbo);
         }
         backTex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, backTex);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer)null);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         backFbo = GL30.glGenFramebuffers();
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, backFbo);
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, backTex, 0);
         backW = width;
         backH = height;
      }
      if (encodeProgram == 0) {
         encodeProgram = Shaders.program("hdr win encode", Hdr.QUAD120_VERT, HdrMac.EDR_FRAG);
         if (encodeProgram == 0) {
            throw new IllegalStateException("scRGB encode shader failed");
         }
         uSrc = GL20.glGetUniformLocation(encodeProgram, "src");
         uSize = GL20.glGetUniformLocation(encodeProgram, "size");
         uP0 = GL20.glGetUniformLocation(encodeProgram, "p0");
         uP1 = GL20.glGetUniformLocation(encodeProgram, "p1");
      }
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, backFbo);
      GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, glFbo);
      GL11.glViewport(0, 0, width, height);
      GL20.glUseProgram(encodeProgram);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, backTex);
      GL20.glUniform1i(uSrc, 0);
      GL20.glUniform2f(uSize, width, height);
      float sdr = (float)sdrWhiteNits;
      GL20.glUniform4f(uP0, (float)(Math.log(Hdr.GAIN_MAX) / Math.log(2)), (float)Math.max(1.0, peakNits / sdr), Hdr.tune.knee, Hdr.tune.gamma);
      GL20.glUniform4f(uP1, sdr / 80F, 0F, 0F, 0F); // scRGB: 1.0 = 80 cd/m²
      // D3D texture rows run top-down, GL's bottom-up: written upside down (Config hdrWinFlip turns it over if a driver maps it the other way)
      boolean flip = Config.HDR_WIN_FLIP;
      GL11.glBegin(GL11.GL_QUADS);
      GL11.glTexCoord2f(0F, flip ? 1F : 0F);
      GL11.glVertex2f(-1F, -1F);
      GL11.glTexCoord2f(1F, flip ? 1F : 0F);
      GL11.glVertex2f(1F, -1F);
      GL11.glTexCoord2f(1F, flip ? 0F : 1F);
      GL11.glVertex2f(1F, 1F);
      GL11.glTexCoord2f(0F, flip ? 0F : 1F);
      GL11.glVertex2f(-1F, 1F);
      GL11.glEnd();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      zombie.core.textures.Texture.lastTextureID = -1;
      GL13.glActiveTexture(prevActive);
      GL20.glUseProgram(prevProgram);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
      GL11.glViewport(vp[0], vp[1], vp[2], vp[3]);
      if (blend) {
         GL11.glEnable(GL11.GL_BLEND);
      }
      if (scissor) {
         GL11.glEnable(GL11.GL_SCISSOR_TEST);
      }
      if (depth) {
         GL11.glEnable(GL11.GL_DEPTH_TEST);
      }
      if (stencil) {
         GL11.glEnable(GL11.GL_STENCIL_TEST);
      }
   }

   // --- setup --------------------------------------------------------------------------------------------------------

   private static void init(long glfwWindow, int w, int h) throws Throwable {
      if (!GL.createCapabilitiesWGL().WGL_NV_DX_interop2) {
         throw new IllegalStateException("the GL driver has no WGL_NV_DX_interop2");
      }
      linker = Linker.nativeLinker();
      Arena g = Arena.global();
      SymbolLookup d3d11 = SymbolLookup.libraryLookup("d3d11.dll", g);
      SymbolLookup dxgi = SymbolLookup.libraryLookup("dxgi.dll", g);
      long hwnd = GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
      try (Arena a = Arena.ofConfined()) {
         // D3D11CreateDevice(adapter NULL, HARDWARE, NULL, BGRA_SUPPORT, NULL, 0, SDK_VERSION, &device, &level, &context)
         MethodHandle create = linker.downcallHandle(d3d11.find("D3D11CreateDevice").orElseThrow(),
               FunctionDescriptor.of(I, P, I, P, I, P, I, I, P, P, P));
         MemorySegment dev = a.allocate(P), ctx = a.allocate(P), level = a.allocate(I);
         check((int)create.invokeExact(MemorySegment.NULL, 1, MemorySegment.NULL, 0x20, MemorySegment.NULL, 0, 7, dev, level, ctx), "D3D11CreateDevice");
         device = dev.get(P, 0);
         context = ctx.get(P, 0);
         // CreateDXGIFactory2(0, IID_IDXGIFactory2, &factory)
         MethodHandle cf = linker.downcallHandle(dxgi.find("CreateDXGIFactory2").orElseThrow(), FunctionDescriptor.of(I, I, P, P));
         MemorySegment fac = a.allocate(P);
         check((int)cf.invokeExact(0, guid(a, "50c83a1c-e072-4c48-87b0-3630fa36a6d0"), fac), "CreateDXGIFactory2");
         MemorySegment factory = fac.get(P, 0);
         tearing = true;
         MemorySegment desc = swapChainDesc(a, w, h);
         MemorySegment sc1 = a.allocate(P);
         int hr = (int)call(factory, FACTORY2_CREATE_SWAPCHAIN_FOR_HWND, FunctionDescriptor.of(I, P, P, ValueLayout.JAVA_LONG, P, P, P, P),
               device, hwnd, desc, MemorySegment.NULL, MemorySegment.NULL, sc1);
         if (hr < 0) {
            tearing = false; // older Windows: no tearing flag
            desc = swapChainDesc(a, w, h);
            hr = (int)call(factory, FACTORY2_CREATE_SWAPCHAIN_FOR_HWND, FunctionDescriptor.of(I, P, P, ValueLayout.JAVA_LONG, P, P, P, P),
                  device, hwnd, desc, MemorySegment.NULL, MemorySegment.NULL, sc1);
         }
         check(hr, "CreateSwapChainForHwnd (a flip-model swap chain on the GL window)");
         MemorySegment sc = sc1.get(P, 0);
         MemorySegment sc3 = a.allocate(P);
         check(call(sc, QI, FunctionDescriptor.of(I, P, P, P), guid(a, "94d99bdb-f1f8-4ab0-b236-7da0170edab1"), sc3), "IDXGISwapChain3");
         release(sc);
         swapChain = sc3.get(P, 0);
         check(call(swapChain, SC3_SET_COLOR_SPACE1, FunctionDescriptor.of(I, P, I), DXGI_COLOR_SPACE_RGB_FULL_G10_NONE_P709), "SetColorSpace1(scRGB)");
         release(factory);
         readOutput(a);
      }
      dxDevice = WGLNVDXInterop.wglDXOpenDeviceNV(device.address());
      if (dxDevice == 0L) {
         throw new IllegalStateException("wglDXOpenDeviceNV failed");
      }
      makeShared(w, h);
      sdrWhiteNits = Config.HDR_UI_NITS > 0 ? Config.HDR_UI_NITS : 200;
      HdrWayland.encRef = sdrWhiteNits;
      HdrWayland.encMax = peakNits;
      ready = true;
      Log.info(String.format(Locale.ROOT, "hdr win: scRGB flip-model swap chain on the game window (%dx%d, tearing %s), SDR white %.0f, peak %.0f cd/m²",
            w, h, tearing, sdrWhiteNits, peakNits));
   }

   private static MemorySegment swapChainDesc(Arena a, int w, int h) {
      // DXGI_SWAP_CHAIN_DESC1: Width, Height, Format, Stereo, SampleDesc{Count, Quality}, BufferUsage, BufferCount, Scaling, SwapEffect, AlphaMode, Flags
      MemorySegment d = a.allocate(48, 4);
      int[] v = {w, h, DXGI_FORMAT_R16G16B16A16_FLOAT, 0, 1, 0, 0x20, 2, 0, 4, 0, tearing ? SWAPCHAIN_ALLOW_TEARING : 0};
      for (int i = 0; i < v.length; i++) {
         d.set(I, i * 4L, v[i]);
      }
      return d;
   }

   /** IDXGIOutput6::GetDesc1 MaxLuminance of the output the swap chain is on. */
   private static void readOutput(Arena a) {
      try {
         MemorySegment out = a.allocate(P);
         check(call(swapChain, SC_GET_CONTAINING_OUTPUT, FunctionDescriptor.of(I, P, P), out), "GetContainingOutput");
         MemorySegment o = out.get(P, 0);
         MemorySegment o6 = a.allocate(P);
         check(call(o, QI, FunctionDescriptor.of(I, P, P, P), guid(a, "068346e8-aaec-4b84-add7-137f513f77a1"), o6), "IDXGIOutput6");
         release(o);
         MemorySegment desc = a.allocate(152, 8);
         check(call(o6.get(P, 0), OUT6_GET_DESC1, FunctionDescriptor.of(I, P, P), desc), "GetDesc1");
         float max = desc.get(ValueLayout.JAVA_FLOAT, 140);
         int colorSpace = desc.get(I, 100);
         release(o6.get(P, 0));
         if (max > 80F) {
            peakNits = max;
         }
         Log.info(String.format(Locale.ROOT, "hdr win: output colour space %d (12 = HDR10 on), max luminance %.0f cd/m²", colorSpace, max));
      } catch (Throwable t) {
         Log.warn("hdr win: output luminance unknown, assuming 1000: " + t);
      }
   }

   /** The D3D11 FP16 texture GL writes into (registered with the interop), and its GL framebuffer. */
   private static void makeShared(int w, int h) throws Throwable {
      if (dxObject != 0L) {
         WGLNVDXInterop.wglDXUnregisterObjectNV(dxDevice, dxObject);
         dxObject = 0L;
         GL30.glDeleteFramebuffers(glFbo);
         GL11.glDeleteTextures(glTex);
         release(sharedTex);
      }
      try (Arena a = Arena.ofConfined()) {
         // D3D11_TEXTURE2D_DESC: Width, Height, MipLevels, ArraySize, Format, SampleDesc{1,0}, Usage DEFAULT, Bind RT|SRV, CPU 0, Misc 0
         MemorySegment d = a.allocate(44, 4);
         int[] v = {w, h, 1, 1, DXGI_FORMAT_R16G16B16A16_FLOAT, 1, 0, 0, 0x20 | 0x8, 0, 0};
         for (int i = 0; i < v.length; i++) {
            d.set(I, i * 4L, v[i]);
         }
         MemorySegment tex = a.allocate(P);
         check(call(device, DEV_CREATE_TEXTURE2D, FunctionDescriptor.of(I, P, P, P, P), d, MemorySegment.NULL, tex), "CreateTexture2D");
         sharedTex = tex.get(P, 0);
      }
      glTex = GL11.glGenTextures();
      dxObject = WGLNVDXInterop.wglDXRegisterObjectNV(dxDevice, sharedTex.address(), glTex, GL11.GL_TEXTURE_2D, WGLNVDXInterop.WGL_ACCESS_WRITE_DISCARD_NV);
      if (dxObject == 0L) {
         throw new IllegalStateException("wglDXRegisterObjectNV failed");
      }
      int prev = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
      glFbo = GL30.glGenFramebuffers();
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, glFbo);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, glTex, 0);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
      width = w;
      height = h;
   }

   private static void resize(int w, int h) throws Throwable {
      GL11.glFinish();
      makeShared(w, h); // releases the old shared texture first
      check(call(swapChain, SC_RESIZE_BUFFERS, FunctionDescriptor.of(I, P, I, I, I, I, I), 0, w, h, DXGI_FORMAT_R16G16B16A16_FLOAT,
            tearing ? SWAPCHAIN_ALLOW_TEARING : 0), "ResizeBuffers");
      Log.info("hdr win: swap chain resized to " + w + "x" + h);
   }

   private static void fail(Throwable t) {
      failed = true;
      Hdr.active = false;
      Log.warn("hdr win: HDR output off, back to SwapBuffers (SDR): " + t);
   }

   // --- COM helpers ---------------------------------------------------------------------------------------------------

   private static MethodHandle slot(MemorySegment obj, int index, FunctionDescriptor fd) {
      MemorySegment vtbl = obj.reinterpret(8).get(P, 0).reinterpret(8L * (index + 1));
      MemorySegment fn = vtbl.getAtIndex(P, index);
      return linker.downcallHandle(fn, fd);
   }

   private static int call(MemorySegment obj, int index, FunctionDescriptor fd, Object... args) throws Throwable {
      Object[] all = new Object[args.length + 1];
      all[0] = obj;
      System.arraycopy(args, 0, all, 1, args.length);
      return (int)slot(obj, index, fd).invokeWithArguments(all);
   }

   private static void callV(MemorySegment obj, int index, FunctionDescriptor fd, Object... args) throws Throwable {
      Object[] all = new Object[args.length + 1];
      all[0] = obj;
      System.arraycopy(args, 0, all, 1, args.length);
      slot(obj, index, fd).invokeWithArguments(all);
   }

   private static void release(MemorySegment obj) throws Throwable {
      if (obj != null && obj.address() != 0L) {
         slot(obj, RELEASE, FunctionDescriptor.of(I, P)).invokeWithArguments(obj);
      }
   }

   private static void check(int hr, String what) {
      if (hr < 0) {
         throw new IllegalStateException(what + " failed, hr=0x" + Integer.toHexString(hr));
      }
   }

   private static MemorySegment guid(Arena a, String s) {
      String h = s.replace("-", "");
      MemorySegment g = a.allocate(16, 4);
      g.set(I, 0, (int)Long.parseLong(h.substring(0, 8), 16));
      g.set(ValueLayout.JAVA_SHORT, 4, (short)Integer.parseInt(h.substring(8, 12), 16));
      g.set(ValueLayout.JAVA_SHORT, 6, (short)Integer.parseInt(h.substring(12, 16), 16));
      for (int i = 0; i < 8; i++) {
         g.set(ValueLayout.JAVA_BYTE, 8 + i, (byte)Integer.parseInt(h.substring(16 + 2 * i, 18 + 2 * i), 16));
      }
      return g;
   }
}
