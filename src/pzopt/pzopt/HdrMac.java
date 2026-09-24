package pzopt;

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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.EXTFramebufferBlit;
import org.lwjgl.opengl.EXTFramebufferObject;

/**
 * macOS EDR output for Hdr (Apple silicon). GLFW on macOS gives the game an OpenGL 2.1 context with an 8-bit back
 * buffer and no way to ask for a float one, so HDR there takes the route of the vrr session's MacPresent bridge (GL
 * renders, Metal presents, IOSurfaces in between), with an EDR swapchain:
 *
 * - a CAMetalLayer over the GLFW content view, RGBA16Float, {@code wantsExtendedDynamicRangeContent}, colour space
 *   extended linear sRGB: 1.0 is the SDR (UI) white of the screen's current brightness, values above it are shown up to
 *   {@code NSScreen.maximumExtendedDynamicRangeColorComponentValue} (the MacBook Pro XDR panel: up to 16);
 * - three RGBA16F ('RGhA') IOSurfaces bound as GL rectangle textures and Metal textures;
 * - per frame (instead of glfwSwapBuffers): the back buffer is copied to a texture, one GL pass writes it into the
 *   IOSurface upright for Metal, decoded from gamma 2.2 and multiplied by the world gain Hdr's alpha pass left in the
 *   back buffer's alpha ({@link Hdr#alphaGain}), clamped to the current headroom with a soft knee; glFlush hands it to
 *   Metal, which copies (or MPS-scales) it into the next drawable and presents.
 *
 * Any failure removes the layer and returns to glfwSwapBuffers (SDR) for good. objc_msgSend is called with the exact
 * prototype of each message (arm64 has no _stret / _fpret variants).
 */
public final class HdrMac {
   private HdrMac() {
   }

   static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
   private static final ValueLayout.OfLong J = ValueLayout.JAVA_LONG;
   private static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE;
   private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
   private static final java.lang.foreign.AddressLayout P = ValueLayout.ADDRESS;
   private static final StructLayout CGSIZE = MemoryLayout.structLayout(D.withName("w"), D.withName("h"));
   private static final StructLayout CGRECT = MemoryLayout.structLayout(D.withName("x"), D.withName("y"), D.withName("w"), D.withName("h"));
   private static final StructLayout MTL3 = MemoryLayout.structLayout(J, J, J);
   private static final int SURFACES = 3;
   private static final int GL_TEXTURE_RECTANGLE = 0x84F5, GL_RGBA16F = 0x881A, GL_HALF_FLOAT = 0x140B;
   private static final long MTL_RGBA16F = 115L;

   private static boolean failed, ready;
   public static volatile double headroom = 1.0, potentialHeadroom = 1.0;
   private static Linker linker;
   private static MemorySegment msgSendAddr;
   private static MethodHandle getClass, selRegister, poolPush, poolPop, ioSurfaceCreate, cglTexImageIOSurface, cglGetCurrentContext,
         mtlCreateDevice, cgColorSpaceCreateWithName;
   private static final Map<String, MemorySegment> SELS = new HashMap<>();
   private static final Map<FunctionDescriptor, MethodHandle> SENDS = new HashMap<>();
   private static MemorySegment nsWindow, view, device, queue, layer, scaler, screen;
   private static final MemorySegment[] surfaces = new MemorySegment[SURFACES], mtlTextures = new MemorySegment[SURFACES],
         commandBuffers = new MemorySegment[SURFACES];
   private static final int[] glTextures = new int[SURFACES], fbos = new int[SURFACES];
   private static int width, height, drawW, drawH;
   private static long frame;
   private static int backTex, backFbo, backW, backH, encodeProgram;
   private static int uSrc, uSize, uP0, uP1;
   private static long headroomCheckedMs;

   /** Hdr: the EDR path applies (macOS, HDR asked for). */
   static boolean wanted() {
      return MAC && Hdr.REQUESTED && !failed;
   }

   /** Render thread, Display.swapBuffers(): true when the frame was presented through the EDR layer. */
   public static boolean present(long glfwWindow) {
      if (!wanted() || !Hdr.active) {
         return false;
      }
      try (Arena a = Arena.ofConfined()) {
         if (!ready) {
            init(glfwWindow);
         }
         int[] fw = new int[1], fh = new int[1];
         GLFW.glfwGetFramebufferSize(glfwWindow, fw, fh);
         if (fw[0] <= 0 || fh[0] <= 0) {
            return true;
         }
         if (fw[0] != width || fh[0] != height) {
            resize(fw[0], fh[0], a);
         }
         long now = System.currentTimeMillis();
         if (now - headroomCheckedMs > 500) {
            headroomCheckedMs = now;
            readHeadroom();
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
      frame++;
      MemorySegment old = commandBuffers[k];
      if (old != null) {
         if (sendJ(old, "status") < 4L) {
            sendV(old, "waitUntilCompleted"); // Metal's copy of this surface three frames ago must be done
         }
         sendV(old, "release");
         commandBuffers[k] = null;
      }
      encode(k);
      GL11.glFlush(); // the GL -> Metal hand-off on one device
      MemorySegment drawable = sendP(layer, "nextDrawable");
      if (drawable.address() == 0L) {
         return;
      }
      MemorySegment cb = sendP(queue, "commandBuffer");
      MemorySegment dst = sendP(drawable, "texture");
      if (drawW != width || drawH != height) {
         handle(FunctionDescriptor.ofVoid(P, P, P, P, P)).invokeExact(scaler, sel("encodeToCommandBuffer:sourceTexture:destinationTexture:"),
               cb, mtlTextures[k], dst);
      } else {
         MemorySegment enc = sendP(cb, "blitCommandEncoder");
         MemorySegment origin = a.allocate(MTL3);
         MemorySegment size = a.allocate(MTL3);
         size.set(J, 0, width);
         size.set(J, 8, height);
         size.set(J, 16, 1L);
         MethodHandle copy = handle(FunctionDescriptor.ofVoid(P, P, P, J, J, MTL3, MTL3, P, J, J, MTL3));
         copy.invokeExact(enc, sel("copyFromTexture:sourceSlice:sourceLevel:sourceOrigin:sourceSize:toTexture:destinationSlice:destinationLevel:destinationOrigin:"),
               mtlTextures[k], 0L, 0L, origin, size, dst, 0L, 0L, origin);
         sendV(enc, "endEncoding");
      }
      sendV(cb, "presentDrawable:", drawable);
      sendV(cb, "commit");
      sendP(cb, "retain");
      commandBuffers[k] = cb;
   }

   /** GL: back buffer -> copy -> IOSurface k, upright for Metal, gamma 2.2 decoded, times the alpha-carried gain. */
   private static void encode(int k) {
      int prevRead = GL11.glGetInteger(EXTFramebufferBlit.GL_READ_FRAMEBUFFER_BINDING_EXT);
      int prevDraw = GL11.glGetInteger(EXTFramebufferBlit.GL_DRAW_FRAMEBUFFER_BINDING_EXT);
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
            EXTFramebufferObject.glDeleteFramebuffersEXT(backFbo);
         }
         backTex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, backTex);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer)null);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         backFbo = EXTFramebufferObject.glGenFramebuffersEXT();
         EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, backFbo);
         EXTFramebufferObject.glFramebufferTexture2DEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT, GL11.GL_TEXTURE_2D, backTex, 0);
         backW = width;
         backH = height;
      }
      if (encodeProgram == 0) {
         encodeProgram = Shaders.program("hdr mac encode", Hdr.QUAD120_VERT, EDR_FRAG);
         if (encodeProgram == 0) {
            throw new IllegalStateException("EDR encode shader failed");
         }
         uSrc = GL20.glGetUniformLocation(encodeProgram, "src");
         uSize = GL20.glGetUniformLocation(encodeProgram, "size");
         uP0 = GL20.glGetUniformLocation(encodeProgram, "p0");
         uP1 = GL20.glGetUniformLocation(encodeProgram, "p1");
      }
      EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferBlit.GL_READ_FRAMEBUFFER_EXT, 0);
      EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferBlit.GL_DRAW_FRAMEBUFFER_EXT, backFbo);
      EXTFramebufferBlit.glBlitFramebufferEXT(0, 0, width, height, 0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
      EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferBlit.GL_DRAW_FRAMEBUFFER_EXT, fbos[k]);
      GL11.glViewport(0, 0, width, height);
      GL20.glUseProgram(encodeProgram);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, backTex);
      GL20.glUniform1i(uSrc, 0);
      GL20.glUniform2f(uSize, width, height);
      double hr = Math.max(1.0, Math.min(headroom, Hdr.GAIN_MAX));
      GL20.glUniform4f(uP0, (float)(Math.log(Hdr.GAIN_MAX) / Math.log(2)), (float)hr, Hdr.tune.knee, Hdr.tune.gamma);
      GL20.glUniform4f(uP1, 1F, 0F, 0F, 0F); // EDR: 1.0 = SDR white
      // upside down: the IOSurface's row 0 is Metal's top row, the back buffer's row 0 is GL's bottom row
      GL11.glBegin(GL11.GL_QUADS);
      GL11.glTexCoord2f(0F, 1F);
      GL11.glVertex2f(-1F, -1F);
      GL11.glTexCoord2f(1F, 1F);
      GL11.glVertex2f(1F, -1F);
      GL11.glTexCoord2f(1F, 0F);
      GL11.glVertex2f(1F, 1F);
      GL11.glTexCoord2f(0F, 0F);
      GL11.glVertex2f(-1F, 1F);
      GL11.glEnd();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      zombie.core.textures.Texture.lastTextureID = -1;
      GL13.glActiveTexture(prevActive);
      GL20.glUseProgram(prevProgram);
      EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferBlit.GL_READ_FRAMEBUFFER_EXT, prevRead);
      EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferBlit.GL_DRAW_FRAMEBUFFER_EXT, prevDraw);
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

   /**
    * Extended linear sRGB from the 8-bit back buffer: gamma 2.2 decode times the gain decoded from alpha, in units of
    * the SDR white, soft knee into the peak, then scaled to the swap chain's unit (macOS EDR: 1.0 = SDR white;
    * Windows scRGB: 1.0 = 80 cd/m², so SDR white / 80). Shared by HdrMac and HdrWin.
    */
   static final String EDR_FRAG = String.join("\n",
         "#version 120",
         "uniform sampler2D src;",
         "uniform vec2 size;",
         "uniform vec4 p0; // x log2 of the largest gain, y peak (x SDR white), z knee (fraction of the peak), w gamma",
         "uniform vec4 p1; // x output scale (swap-chain units per SDR white)",
         "varying vec2 uv;",
         "void main() {",
         "  vec4 v = texture2D(src, uv);",
         "  float gain = exp2((1.0 - v.a) * p0.x);",
         "  vec3 n = pow(max(v.rgb, vec3(0.0)), vec3(p0.w)) * gain;",
         "  float peak = p0.y;",
         "  float knee = max(1.0, p0.z * peak);",
         "  float m = max(max(n.r, n.g), n.b);",
         "  if (m > knee && peak > knee) {",
         "    float x = (m - knee) / (peak - knee);",
         "    n *= (knee + (peak - knee) * (x / (1.0 + x))) / m;",
         "  }",
         "  gl_FragColor = vec4(n * p1.x, 1.0);",
         "}");

   // --- setup --------------------------------------------------------------------------------------------------------

   private static void init(long glfwWindow) throws Throwable {
      linker = Linker.nativeLinker();
      Arena g = Arena.global();
      SymbolLookup objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", g);
      SymbolLookup metal = SymbolLookup.libraryLookup("/System/Library/Frameworks/Metal.framework/Metal", g);
      SymbolLookup ios = SymbolLookup.libraryLookup("/System/Library/Frameworks/IOSurface.framework/IOSurface", g);
      SymbolLookup ogl = SymbolLookup.libraryLookup("/System/Library/Frameworks/OpenGL.framework/OpenGL", g);
      SymbolLookup cg = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", g);
      SymbolLookup.libraryLookup("/System/Library/Frameworks/QuartzCore.framework/QuartzCore", g);
      SymbolLookup.libraryLookup("/System/Library/Frameworks/MetalPerformanceShaders.framework/MetalPerformanceShaders", g);
      msgSendAddr = objc.find("objc_msgSend").orElseThrow();
      getClass = linker.downcallHandle(objc.find("objc_getClass").orElseThrow(), FunctionDescriptor.of(P, P));
      selRegister = linker.downcallHandle(objc.find("sel_registerName").orElseThrow(), FunctionDescriptor.of(P, P));
      poolPush = linker.downcallHandle(objc.find("objc_autoreleasePoolPush").orElseThrow(), FunctionDescriptor.of(P));
      poolPop = linker.downcallHandle(objc.find("objc_autoreleasePoolPop").orElseThrow(), FunctionDescriptor.ofVoid(P));
      ioSurfaceCreate = linker.downcallHandle(ios.find("IOSurfaceCreate").orElseThrow(), FunctionDescriptor.of(P, P));
      cglTexImageIOSurface = linker.downcallHandle(ogl.find("CGLTexImageIOSurface2D").orElseThrow(), FunctionDescriptor.of(I, P, I, I, I, I, I, I, P, I));
      cglGetCurrentContext = linker.downcallHandle(ogl.find("CGLGetCurrentContext").orElseThrow(), FunctionDescriptor.of(P));
      mtlCreateDevice = linker.downcallHandle(metal.find("MTLCreateSystemDefaultDevice").orElseThrow(), FunctionDescriptor.of(P));
      cgColorSpaceCreateWithName = linker.downcallHandle(cg.find("CGColorSpaceCreateWithName").orElseThrow(), FunctionDescriptor.of(P, P));
      MemorySegment extendedLinearSrgb = cg.find("kCGColorSpaceExtendedLinearSRGB").orElseThrow().reinterpret(8).get(P, 0);

      nsWindow = MemorySegment.ofAddress(GLFWNativeCocoa.glfwGetCocoaWindow(glfwWindow));
      view = sendP(nsWindow, "contentView");
      device = (MemorySegment)mtlCreateDevice.invokeExact();
      if (device.address() == 0L) {
         throw new IllegalStateException("no Metal device");
      }
      queue = sendP(device, "newCommandQueue");
      scaler = (MemorySegment)handle(FunctionDescriptor.of(P, P, P, P)).invokeExact(sendP(cls("MPSImageBilinearScale"), "alloc"), sel("initWithDevice:"), device);
      layer = sendP(cls("CAMetalLayer"), "layer");
      sendP(layer, "retain");
      sendV(layer, "setDevice:", device);
      sendL(layer, "setPixelFormat:", MTL_RGBA16F);
      sendZ(layer, "setWantsExtendedDynamicRangeContent:", true);
      MemorySegment cs = (MemorySegment)cgColorSpaceCreateWithName.invokeExact(extendedLinearSrgb);
      sendV(layer, "setColorspace:", cs);
      sendZ(layer, "setFramebufferOnly:", false);
      sendZ(layer, "setOpaque:", true);
      sendZ(view, "setWantsLayer:", true);
      MemorySegment host = sendP(view, "layer");
      sendV(host, "addSublayer:", layer);
      readHeadroom();
      ready = true;
      Log.info(String.format(Locale.ROOT, "hdr mac: EDR layer on (RGBA16Float, extended linear sRGB), headroom now %.2f, potential %.2f",
            headroom, potentialHeadroom));
   }

   private static void readHeadroom() {
      try {
         screen = sendP(nsWindow, "screen");
         if (screen.address() == 0L) {
            screen = sendP(cls("NSScreen"), "mainScreen");
         }
         headroom = sendD(screen, "maximumExtendedDynamicRangeColorComponentValue");
         potentialHeadroom = sendD(screen, "maximumPotentialExtendedDynamicRangeColorComponentValue");
         // the encoded peak is where the panel is now (EDR headroom rises once EDR content is on screen), in HDR's units
         HdrWayland.encRef = 1.0;
         HdrWayland.encMax = Math.max(1.0, headroom);
      } catch (Throwable t) {
         Log.warn("hdr mac: EDR headroom unavailable: " + t);
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
            sendV(surfaces[i], "release");
         }
      }
      width = w;
      height = h;
      MemorySegment dict = sendP(cls("NSMutableDictionary"), "dictionary");
      putNumber(dict, "kIOSurfaceWidth", w);
      putNumber(dict, "kIOSurfaceHeight", h);
      putNumber(dict, "kIOSurfaceBytesPerElement", 8);
      putNumber(dict, "kIOSurfacePixelFormat", 0x52476841); // 'RGhA': RGBA half float
      MemorySegment cgl = (MemorySegment)cglGetCurrentContext.invokeExact();
      int prevTex = GL11.glGetInteger(0x84F6); // GL_TEXTURE_BINDING_RECTANGLE
      int prevFbo = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
      for (int i = 0; i < SURFACES; i++) {
         surfaces[i] = (MemorySegment)ioSurfaceCreate.invokeExact(dict);
         if (surfaces[i].address() == 0L) {
            throw new IllegalStateException("IOSurfaceCreate failed for " + w + "x" + h);
         }
         glTextures[i] = GL11.glGenTextures();
         GL11.glBindTexture(GL_TEXTURE_RECTANGLE, glTextures[i]);
         int err = (int)cglTexImageIOSurface.invokeExact(cgl, GL_TEXTURE_RECTANGLE, GL_RGBA16F, w, h, GL11.GL_RGBA, GL_HALF_FLOAT, surfaces[i], 0);
         if (err != 0) {
            throw new IllegalStateException("CGLTexImageIOSurface2D (RGBA16F) error " + err);
         }
         fbos[i] = EXTFramebufferObject.glGenFramebuffersEXT();
         EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, fbos[i]);
         EXTFramebufferObject.glFramebufferTexture2DEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT, GL_TEXTURE_RECTANGLE, glTextures[i], 0);
         int status = EXTFramebufferObject.glCheckFramebufferStatusEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT);
         if (status != EXTFramebufferObject.GL_FRAMEBUFFER_COMPLETE_EXT) {
            throw new IllegalStateException("IOSurface FBO incomplete 0x" + Integer.toHexString(status));
         }
         MethodHandle h4 = handle(FunctionDescriptor.of(P, P, P, J, J, J, ValueLayout.JAVA_BOOLEAN));
         MemorySegment desc = (MemorySegment)h4.invokeExact(cls("MTLTextureDescriptor"), sel("texture2DDescriptorWithPixelFormat:width:height:mipmapped:"),
               MTL_RGBA16F, (long)w, (long)h, false);
         sendL(desc, "setUsage:", 1L); // MTLTextureUsageShaderRead
         sendL(desc, "setStorageMode:", 0L); // MTLStorageModeShared
         MethodHandle nt = handle(FunctionDescriptor.of(P, P, P, P, P, J));
         mtlTextures[i] = (MemorySegment)nt.invokeExact(device, sel("newTextureWithDescriptor:iosurface:plane:"), desc, surfaces[i], 0L);
         if (mtlTextures[i].address() == 0L) {
            throw new IllegalStateException("newTextureWithDescriptor:iosurface: failed");
         }
      }
      GL11.glBindTexture(GL_TEXTURE_RECTANGLE, prevTex);
      EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, prevFbo);
      MemorySegment bounds = (MemorySegment)handle(FunctionDescriptor.of(CGRECT, P, P)).invokeExact((SegmentAllocator)a, view, sel("bounds"));
      handle(FunctionDescriptor.ofVoid(P, P, CGRECT)).invokeExact(layer, sel("setFrame:"), bounds);
      double scale = sendD(nsWindow, "backingScaleFactor");
      drawW = Math.max(w, (int)Math.round(bounds.get(D, 16) * scale));
      drawH = Math.max(h, (int)Math.round(bounds.get(D, 24) * scale));
      MemorySegment sz = a.allocate(CGSIZE);
      sz.set(D, 0, drawW);
      sz.set(D, 8, drawH);
      handle(FunctionDescriptor.ofVoid(P, P, CGSIZE)).invokeExact(layer, sel("setDrawableSize:"), sz);
      handle(FunctionDescriptor.ofVoid(P, P, D)).invokeExact(layer, sel("setContentsScale:"), scale);
      Log.info("hdr mac: game " + w + "x" + h + " -> EDR drawable " + drawW + "x" + drawH + (drawW != w || drawH != h ? " (MPS bilinear upscale)" : ""));
   }

   private static void putNumber(MemorySegment dict, String keySymbol, int value) throws Throwable {
      SymbolLookup ios = SymbolLookup.libraryLookup("/System/Library/Frameworks/IOSurface.framework/IOSurface", Arena.global());
      MemorySegment key = ios.find(keySymbol).orElseThrow().reinterpret(8).get(P, 0);
      MemorySegment num = (MemorySegment)handle(FunctionDescriptor.of(P, P, P, I)).invokeExact(cls("NSNumber"), sel("numberWithInt:"), value);
      handle(FunctionDescriptor.ofVoid(P, P, P, P)).invokeExact(dict, sel("setObject:forKey:"), num, key);
   }

   private static void fail(Throwable t) {
      failed = true;
      Hdr.active = false;
      Log.warn("hdr mac: EDR output off, back to glfwSwapBuffers (SDR): " + t);
      try {
         if (layer != null) {
            sendV(layer, "removeFromSuperlayer");
         }
      } catch (Throwable ignored) {
      }
   }

   // --- Objective-C runtime helpers ----------------------------------------------------------------------------------

   private static MemorySegment cls(String name) throws Throwable {
      try (Arena a = Arena.ofConfined()) {
         return (MemorySegment)getClass.invokeExact(a.allocateFrom(name));
      }
   }

   private static MemorySegment sel(String name) throws Throwable {
      MemorySegment s = SELS.get(name);
      if (s == null) {
         try (Arena a = Arena.ofConfined()) {
            s = (MemorySegment)selRegister.invokeExact(a.allocateFrom(name));
         }
         SELS.put(name, s);
      }
      return s;
   }

   private static MethodHandle handle(FunctionDescriptor fd) {
      return SENDS.computeIfAbsent(fd, d -> linker.downcallHandle(msgSendAddr, d));
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
