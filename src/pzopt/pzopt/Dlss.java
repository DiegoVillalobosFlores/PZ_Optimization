package pzopt;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectFD;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreFD;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureFBO;
import zombie.iso.PlayerCamera;

/**
 * NVIDIA DLSS Super Resolution for the upscaler (docs/plan-upscalers.md, milestone 3), through
 * natives/libpzopt_ngx64.so (src/native/pzopt_ngx.cpp): a Vulkan device on the GL context's GPU runs NGX; its four
 * images (colour, depth, motion vectors at the render size; the output at the screen size) and two semaphores are
 * imported into GL with GL_EXT_memory_object_fd / GL_EXT_semaphore_fd. Per frame, on the render thread after the
 * world pass: the low-res colour is blitted into the colour image, the scene depth resampled into the depth image,
 * the camera's motion written into the motion-vector image, GL signals, the shim evaluates, GL waits, and the
 * output image is the composite texture ({@link Upscaler#output()}). The world pass is drawn with a Halton
 * sub-pixel jitter through the viewport ({@link RenderScale#setJitter}).
 *
 * <p>Any failure turns the pass off for the session (the frame then goes through the bicubic path).
 */
final class Dlss {
   private Dlss() {
   }

   private static final int GL_TRUE = 1;

   private static boolean tried;
   private static boolean ready;
   private static MethodHandle init, error, optimal, create, imageFd, imageBytes, semaphoreFd, evaluate, evaluateSet, destroy, gpuUs, times;
   // devDlssGaps: GL timestamps at the hand-over and after the wait, matched with the evaluation's Vulkan start / end
   private static final int GAP_RING = 16;
   private static int[] gapSignalQ, gapResumeQ;
   private static final long[] gapSeq = new long[GAP_RING];
   private static long gapInNs, gapOutNs, gapCount;
   private static MemorySegment gapStart, gapEnd;
   private static long statFrames, prepNs, evalNs, waitNs, afterNs; // render-thread time per phase since the last stats line
   private static long lastWaitEndNs;
   private static int inW, inH, outW, outH;
   // one image set, or two with dlssPipeline (GL composites the previous frame's output while this frame's
   // evaluation runs); the scalar names below are the set being written this frame (select / store)
   private static int sets = 1;
   private static final int[][] setTex = new int[2][4];
   private static final int[][] setMem = new int[2][4];
   private static final int[] setColorFbo = new int[2], setMvFbo = new int[2], setInputsFbo = new int[2];
   private static final int[] setSemGl = new int[2], setSemDlss = new int[2], setMvDepthStencil = new int[2];
   private static int current; // the set this frame writes and evaluates
   private static int pending = -1; // the set evaluated last frame whose DLSS-done semaphore GL has not waited on yet
   private static int shown = -1; // the set whose output was last waited on (what the composite shows)
   private static int[] tex = setTex[0]; // colour, depth, mv, output (GL names of the imported images)
   private static int[] mem = setMem[0];
   private static int colorFbo, mvFbo;
   private static int inputsFbo; // depth (attachment 0) + motion vectors (attachment 1): one pass writes both
   private static int semGlDone, semDlssDone;
   private static int quadVbo;
   private static final int[] SAVED_VIEWPORT = new int[4];
   private static final int[] LAYOUTS = new int[4];
   private static final int[] NO_BUFFERS = new int[0];
   private static final int[] OUT_LAYOUT = {EXTSemaphore.GL_LAYOUT_GENERAL_EXT};
   private static final int[] OUT_TEX = new int[1];
   private static long frames;
   private static long lastFrameNs;
   private static float lastOffX, lastOffY, lastZoom;
   private static boolean haveLast;
   private static int haltonIndex;
   private static float dlssSharpness;
   private static final Arena ARENA = Arena.global();

   private static int rectProgram;
   private static int[] rectUniforms;
   private static int inputsProgram;
   private static int[] inputsUniforms;
   private static int sceneDepthFbo = -1, sceneDepthTex; // the world framebuffer the depth attachment was last queried for
   private static int directFbo; // dlssDirectColor: the world framebuffer whose colour attachment is a DLSS colour image now
   private static int directTex; // ... which image
   private static boolean directThisFrame; // the frame being resolved was drawn straight into its colour image
   private static int mvDepthStencilTex; // the world depth-stencil texture attached to mvFbo for the stencil-masked object rects

   private static void select(int k) {
      tex = setTex[k];
      mem = setMem[k];
      colorFbo = setColorFbo[k];
      mvFbo = setMvFbo[k];
      inputsFbo = setInputsFbo[k];
      semGlDone = setSemGl[k];
      semDlssDone = setSemDlss[k];
      mvDepthStencilTex = setMvDepthStencil[k];
   }

   private static void store(int k) {
      setColorFbo[k] = colorFbo;
      setMvFbo[k] = mvFbo;
      setInputsFbo[k] = inputsFbo;
      setSemGl[k] = semGlDone;
      setSemDlss[k] = semDlssDone;
      setMvDepthStencil[k] = mvDepthStencilTex;
   }
   private static long objectRects;

   /** The frame's resolve (render thread); objects = the frame's per-object motion entries (may be null). */
   static void resolve(ObjectMotion.Frame objects) {
      if (!RenderScale.active()) {
         return;
      }
      if (IsoPlayer.numPlayers > 1) {
         RenderScale.fallback("fsr1", "dlss: split screen is not supported (one feature per screen)");
         return;
      }
      if (!ready && !setUp()) {
         return;
      }
      try {
         frame(objects);
      } catch (Throwable t) {
         RenderScale.disable("dlss frame failed: " + t);
         Log.error("dlss: " + t);
      }
   }

   private static boolean setUp() {
      if (tried) {
         return false;
      }
      tried = true;
      try {
         boolean shareOk = WINDOWS ? GL.getCapabilities().GL_EXT_memory_object_win32 && GL.getCapabilities().GL_EXT_semaphore_win32
            : GL.getCapabilities().GL_EXT_memory_object_fd && GL.getCapabilities().GL_EXT_semaphore_fd;
         if (!shareOk) {
            RenderScale.fallback("fsr1", "dlss: the GL driver has no GL_EXT_memory_object / GL_EXT_semaphore " + (WINDOWS ? "win32" : "fd") + " (Vulkan image sharing)");
            return false;
         }
         if (!GL.getCapabilities().GL_ARB_viewport_array) {
            RenderScale.fallback("fsr1", "dlss: no float viewports (GL_ARB_viewport_array) for the sub-pixel jitter");
            return false;
         }
         File lib = new File("natives", WINDOWS ? "pzopt_ngx64.dll" : "libpzopt_ngx64.so").getAbsoluteFile();
         if (!lib.isFile()) {
            RenderScale.fallback("fsr1", "dlss: " + lib + " not found");
            return false;
         }
         SymbolLookup lookup = SymbolLookup.libraryLookup(lib.getPath(), ARENA);
         Linker linker = Linker.nativeLinker();
         init = linker.downcallHandle(lookup.find("pzngx_init").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
         error = linker.downcallHandle(lookup.find("pzngx_error").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS));
         optimal = linker.downcallHandle(lookup.find("pzngx_optimal").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
         create = linker.downcallHandle(lookup.find("pzngx_create").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
         imageFd = linker.downcallHandle(lookup.find("pzngx_image_fd").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
         imageBytes = linker.downcallHandle(lookup.find("pzngx_image_bytes").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
         semaphoreFd = linker.downcallHandle(lookup.find("pzngx_semaphore_fd").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
         evaluate = linker.downcallHandle(lookup.find("pzngx_evaluate").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));
         evaluateSet = lookup.find("pzngx_evaluate_set").map(a -> linker.downcallHandle(a,
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT))).orElse(null);
         destroy = linker.downcallHandle(lookup.find("pzngx_destroy").orElseThrow(), FunctionDescriptor.ofVoid());
         times = lookup.find("pzngx_times").map(a -> linker.downcallHandle(a, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS))).orElse(null);
         gpuUs = lookup.find("pzngx_gpu_us").map(a -> linker.downcallHandle(a, FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE))).orElse(null);

         // the GPU the GL context runs on, so the Vulkan device is the same one
         ByteBuffer uuid = BufferUtils.createByteBuffer(16);
         EXTMemoryObject.glGetUnsignedBytei_vEXT(EXTMemoryObject.GL_DEVICE_UUID_EXT, 0, uuid);
         MemorySegment uuidSeg = ARENA.allocate(16);
         for (int i = 0; i < 16; i++) {
            uuidSeg.set(ValueLayout.JAVA_BYTE, i, uuid.get(i));
         }
         File dataDir = new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt/ngx");
         dataDir.mkdirs();
         File dlssDir = new File("natives").getAbsoluteFile();
         int rc = (int)init.invokeExact(cString(dataDir.getAbsolutePath()), cString(dlssDir.getAbsolutePath()), uuidSeg, -1);
         if (rc != 0) {
            RenderScale.fallback("fsr1", "dlss: " + lastError());
            return false;
         }
         if (!createFeature()) {
            return false;
         }
         ready = true;
         Log.info("dlss: ready, " + inW + "x" + inH + " -> " + outW + "x" + outH + " (" + Config.UPSCALER_QUALITY + ", preset " + Config.DLSS_PRESET + ", DLSS sharpness hint " + dlssSharpness + ")");
         return true;
      } catch (Throwable t) {
         RenderScale.fallback("fsr1", "dlss: " + t);
         return false;
      }
   }

   private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

   private static void importSemaphore(int semaphore, long handle) {
      if (WINDOWS) {
         org.lwjgl.opengl.EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(semaphore, org.lwjgl.opengl.EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handle);
      } else {
         EXTSemaphoreFD.glImportSemaphoreFdEXT(semaphore, EXTMemoryObjectFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, (int)handle);
      }
   }

   private static MemorySegment cString(String s) {
      byte[] b = s.getBytes(StandardCharsets.UTF_8);
      MemorySegment seg = ARENA.allocate(b.length + 1);
      MemorySegment.copy(MemorySegment.ofArray(b), 0, seg, 0, b.length);
      seg.set(ValueLayout.JAVA_BYTE, b.length, (byte)0);
      return seg;
   }

   private static String lastError() {
      try {
         MemorySegment p = (MemorySegment)error.invokeExact();
         return p.reinterpret(4096).getString(0);
      } catch (Throwable t) {
         return t.toString();
      }
   }

   /** The NVSDK_NGX_DLSS_Hint_Render_Preset value of the dlssPreset key (0 = default). */
   private static int presetValue() {
      String p = Config.DLSS_PRESET;
      if (p.length() == 1 && p.charAt(0) >= 'a' && p.charAt(0) <= 'o') {
         return p.charAt(0) - 'a' + 1;
      }
      return 0;
   }

   private static int qualityIndex() {
      switch (Config.UPSCALER_QUALITY) {
         case "balanced": return 1;
         case "performance": return 2;
         case "ultra": case "ultra-performance": case "ultraperformance": return 3;
         case "native": case "dlaa": case "100": return 4;
         default: return 0;
      }
   }

   private static boolean createFeature() throws Throwable {
      int[] r = RenderScale.scaledRect(0);
      inW = r[2];
      inH = r[3];
      outW = outputSize(RenderScale.fullWidth(0));
      outH = outputSize(RenderScale.fullHeight(0));
      MemorySegment ow = ARENA.allocate(4), oh = ARENA.allocate(4), sh = ARENA.allocate(4);
      int rc = (int)optimal.invokeExact(qualityIndex(), outW, outH, ow, oh, sh);
      if (rc == 0) {
         dlssSharpness = sh.get(ValueLayout.JAVA_FLOAT, 0);
         if (Config.DEV_UPSCALER_LOG) {
            Log.info("dlss: optimal render size " + ow.get(ValueLayout.JAVA_INT, 0) + "x" + oh.get(ValueLayout.JAVA_INT, 0) + ", ours " + inW + "x" + inH);
         }
      }
      sets = Config.DLSS_PIPELINE && evaluateSet != null ? 2 : 1;
      int flags = (Config.DLSS_DEPTH_INVERTED ? 1 : 0) | (Config.DLSS_SHARPEN ? 2 : 0) | (Config.DLSS_AUTO_EXPOSURE ? 0 : 4) | (presetValue() << 8) | (sets == 2 ? 1 << 16 : 0);
      rc = (int)create.invokeExact(inW, inH, outW, outH, qualityIndex(), flags);
      if (rc != 0) {
         RenderScale.fallback("fsr1", "dlss: " + lastError());
         return false;
      }
      // import the images
      int[] formats = {GL11.GL_RGBA8, GL30.GL_R32F, GL30.GL_RG16F, GL11.GL_RGBA8};
      for (int k = 0; k < sets; k++) {
         select(k);
         for (int i = 0; i < 4; i++) {
            long fd = (long)imageFd.invokeExact(4 * k + i);
            long bytes = (long)imageBytes.invokeExact(4 * k + i);
            if (fd < 0 || bytes <= 0) {
               RenderScale.fallback("fsr1", "dlss: image " + i + " has no exported memory");
               return false;
            }
            mem[i] = EXTMemoryObject.glCreateMemoryObjectsEXT();
            EXTMemoryObject.glMemoryObjectParameteriEXT(mem[i], EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT, GL_TRUE);
            if (WINDOWS) {
               org.lwjgl.opengl.EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(mem[i], bytes, org.lwjgl.opengl.EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, fd);
            } else {
               EXTMemoryObjectFD.glImportMemoryFdEXT(mem[i], bytes, EXTMemoryObjectFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, (int)fd);
            }
            tex[i] = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex[i]);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, EXTMemoryObject.GL_TEXTURE_TILING_EXT, EXTMemoryObject.GL_OPTIMAL_TILING_EXT);
            int w = i == 3 ? outW : inW, h = i == 3 ? outH : inH;
            EXTMemoryObject.glTexStorageMem2DEXT(GL11.GL_TEXTURE_2D, 1, formats[i], w, h, mem[i], 0L);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
            int err = GL11.glGetError();
            if (err != 0) {
               RenderScale.fallback("fsr1", "dlss: importing image " + i + " failed (GL error 0x" + Integer.toHexString(err) + ")");
               return false;
            }
         }
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         colorFbo = Upscaler.framebufferOf(tex[0]);
         mvFbo = Upscaler.framebufferOf(tex[2]);
         inputsFbo = framebufferOf2(tex[1], tex[2]);
         if (colorFbo == 0 || mvFbo == 0 || inputsFbo == 0) {
            RenderScale.fallback("fsr1", "dlss: a framebuffer over an imported image is incomplete");
            return false;
         }
         // the semaphores
         semGlDone = EXTSemaphore.glGenSemaphoresEXT();
         importSemaphore(semGlDone, (long)semaphoreFd.invokeExact(2 * k));
         semDlssDone = EXTSemaphore.glGenSemaphoresEXT();
         importSemaphore(semDlssDone, (long)semaphoreFd.invokeExact(2 * k + 1));
         int err = GL11.glGetError();
         if (err != 0) {
            RenderScale.fallback("fsr1", "dlss: importing the semaphores failed (GL error 0x" + Integer.toHexString(err) + ")");
            return false;
         }
         mvDepthStencilTex = 0;
         store(k);
      }
      current = 0;
      pending = -1;
      shown = -1;
      select(0);
      LAYOUTS[0] = EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT;
      LAYOUTS[1] = EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT;
      LAYOUTS[2] = EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT;
      LAYOUTS[3] = EXTSemaphore.GL_LAYOUT_GENERAL_EXT;
      // the passes that fill depth and motion vectors
      rectProgram = Shaders.program("dlss object motion", Upscaler.QUAD_VERT, RECT_FRAG);
      inputsProgram = Shaders.program("dlss depth + motion", Upscaler.QUAD_VERT, INPUTS_FRAG);
      if (rectProgram == 0 || inputsProgram == 0) {
         RenderScale.fallback("fsr1", "dlss: the depth / motion shaders were refused");
         return false;
      }
      rectUniforms = new int[]{GL20.glGetUniformLocation(rectProgram, "mv")};
      inputsUniforms = new int[]{GL20.glGetUniformLocation(inputsProgram, "SceneDepth"), GL20.glGetUniformLocation(inputsProgram, "origin"),
         GL20.glGetUniformLocation(inputsProgram, "constantDepth"), GL20.glGetUniformLocation(inputsProgram, "cur"), GL20.glGetUniformLocation(inputsProgram, "prev"),
         GL20.glGetUniformLocation(inputsProgram, "params")};
      mvDepthStencilTex = 0;
      quadVbo = GL15.glGenBuffers();
      java.nio.FloatBuffer q = BufferUtils.createFloatBuffer(8);
      q.put(-1.0F).put(-1.0F).put(1.0F).put(-1.0F).put(-1.0F).put(1.0F).put(1.0F).put(1.0F).flip();
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
      GL15.glBufferData(GL15.GL_ARRAY_BUFFER, q, GL15.GL_STATIC_DRAW);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
      haveLast = false;
      RenderScale.setJitter(0.0F, 0.0F);
      return true;
   }

   private static void frame(ObjectMotion.Frame objects) throws Throwable {
      TextureFBO world = Core.getInstance().getOffscreenBuffer();
      if (world == null || world.getTexture() == null) {
         return;
      }
      int[] r = RenderScale.scaledRect(0);
      if (r[2] != inW || r[3] != inH || outputSize(RenderScale.fullWidth(0)) != outW || outputSize(RenderScale.fullHeight(0)) != outH) {
         // resolution change: rebuild everything
         destroy.invokeExact();
         releaseGl();
         if (!createFeature()) {
            return;
         }
      }
      select(current);
      long tStart = System.nanoTime();
      if (lastWaitEndNs != 0) {
         afterNs += tStart - lastWaitEndNs; // the render thread from the last wait to this resolve (composite, UI, swap, next world pass)
      }
      int worldFbo = world.getBufferId();
      PlayerCamera camera = SpriteRenderer.instance.getRenderingPlayerCamera(0);
      float s = RenderScale.scale();
      GpuSections.markNow("upscale", false);
      GpuSections.markNow("dlss.inputs", false);
      GL11.glGetIntegerv(GL11.GL_VIEWPORT, SAVED_VIEWPORT);
      int previousFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
      GL11.glDisable(GL11.GL_BLEND);
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glDisable(GL11.GL_STENCIL_TEST);
      GL11.glDisable(GL11.GL_CULL_FACE);
      GL11.glDepthMask(false);
      GL11.glColorMask(true, true, true, true);

      // 1. colour: the low-res region straight into the shared colour image (drawn there already with dlssDirectColor)
      boolean direct = detachDirectColor();
      if (!direct) {
         GpuSections.markNow("dlss.color", false);
         GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, worldFbo);
         GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, colorFbo);
         GL30.glBlitFramebuffer(r[0], r[1], r[0] + inW, r[1] + inH, 0, 0, inW, inH, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
         GpuSections.markNow("dlss.color", true);
      }

      // 2. depth and camera motion vectors: one full-rect pass into both images
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
      for (int i = 1; i < 5; i++) {
         GL20.glDisableVertexAttribArray(i);
      }
      GL20.glEnableVertexAttribArray(0);
      GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      int sceneDepth = sceneDepthTexture(worldFbo);
      float offX = camera.offX, offY = camera.offY, zoom = camera.zoom <= 0.0F ? 1.0F : camera.zoom;
      boolean reset = !haveLast;
      if (!haveLast) {
         lastOffX = offX;
         lastOffY = offY;
         lastZoom = zoom;
         haveLast = true;
      }
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, inputsFbo);
      GL11.glViewport(0, 0, inW, inH);
      GL20.glUseProgram(inputsProgram);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneDepth);
      GL20.glUniform1i(inputsUniforms[0], 0);
      GL20.glUniform2i(inputsUniforms[1], r[0], r[1]);
      GL20.glUniform1f(inputsUniforms[2], sceneDepth == 0 ? 0.5F : -1.0F);
      GL20.glUniform3f(inputsUniforms[3], offX, offY, zoom);
      GL20.glUniform3f(inputsUniforms[4], lastOffX, lastOffY, lastZoom);
      GL20.glUniform4f(inputsUniforms[5], s, inH, Config.DLSS_MV_SIGN, 0.0F);
      GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mvFbo); // the object rects below write the motion image alone
      lastOffX = offX;
      lastOffY = offY;
      lastZoom = zoom;

      // 2b. the objects' own motion where their stencil id sits (characters, vehicles), over the camera motion
      if (objects != null && objects.count > 0 && sceneDepth != 0 && Config.UPSCALER_OBJECT_MV) {
         if (mvDepthStencilTex != sceneDepth) {
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, sceneDepth, 0);
            mvDepthStencilTex = sceneDepth;
         }
         GL11.glEnable(GL11.GL_STENCIL_TEST);
         GL11.glStencilMask(0);
         GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
         GL20.glUseProgram(rectProgram);
         for (int i = 0; i < objects.count; i++) {
            float rx = objects.rect[i * 4] * s, ry = objects.rect[i * 4 + 1] * s, rw = objects.rect[i * 4 + 2] * s, rh = objects.rect[i * 4 + 3] * s;
            // screen rect (y down) -> memory rows (y up)
            int x0 = Math.max(0, (int)Math.floor(rx)), x1 = Math.min(inW, (int)Math.ceil(rx + rw));
            int y0 = Math.max(0, (int)Math.floor(inH - ry - rh)), y1 = Math.min(inH, (int)Math.ceil(inH - ry));
            if (x1 <= x0 || y1 <= y0) {
               continue;
            }
            GL11.glViewport(x0, y0, x1 - x0, y1 - y0);
            GL11.glStencilFunc(GL11.GL_EQUAL, i + 1, 0x7F);
            GL20.glUniform2f(rectUniforms[0], objects.motion[i * 2] * s * Config.DLSS_MV_SIGN, -objects.motion[i * 2 + 1] * s * Config.DLSS_MV_SIGN);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
            objectRects++;
         }
         GL11.glDisable(GL11.GL_STENCIL_TEST);
         GL11.glStencilMask(0xFF);
      }

      // 3. hand over to Vulkan and back
      GpuSections.markNow("dlss.inputs", true);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      boolean gaps = Config.DEV_DLSS_GAPS && times != null;
      int gapSlot = (int)((frames + 1) % GAP_RING);
      if (gaps) {
         if (gapSignalQ == null) {
            gapSignalQ = new int[GAP_RING];
            gapResumeQ = new int[GAP_RING];
            GL15.glGenQueries(gapSignalQ);
            GL15.glGenQueries(gapResumeQ);
            gapStart = ARENA.allocate(8);
            gapEnd = ARENA.allocate(8);
         }
         readGaps(gapSlot); // the evaluation GAP_RING frames back used this slot
         org.lwjgl.opengl.GL33.glQueryCounter(gapSignalQ[gapSlot], org.lwjgl.opengl.GL33.GL_TIMESTAMP);
      }
      EXTSemaphore.glSignalSemaphoreEXT(semGlDone, NO_BUFFERS, tex, LAYOUTS);
      GL11.glFlush();
      long now = System.nanoTime();
      prepNs += now - tStart;
      float frameMs = lastFrameNs == 0 ? 16.7F : (now - lastFrameNs) / 1.0e6F;
      lastFrameNs = now;
      float jx = RenderScale.frameJitterX() * Config.DLSS_JITTER_SIGN;
      float jy = RenderScale.frameJitterY() * Config.DLSS_JITTER_SIGN;
      int rc = sets == 2 ? (int)evaluateSet.invokeExact(jx, jy, 1.0F, 1.0F, reset ? 1 : 0, dlssSharpness, frameMs, current)
         : (int)evaluate.invokeExact(jx, jy, 1.0F, 1.0F, reset ? 1 : 0, dlssSharpness, frameMs);
      if (rc != 0) {
         throw new IllegalStateException(lastError());
      }
      long tEval = System.nanoTime();
      evalNs += tEval - now;
      store(current);
      if (sets == 1 || pending < 0 && shown < 0) {
         // this frame's own evaluation (one set, or the first pipelined frame)
         waitDone(semDlssDone, tex);
         shown = current;
         pending = -1;
      } else {
         // pipelined: show the previous frame's evaluation, finished while this frame's world pass drew; this frame's
         // is waited on next frame (GL writes this set's inputs again only after that wait, two frames from now)
         if (pending >= 0) {
            waitDone(setSemDlss[pending], setTex[pending]);
            shown = pending;
         }
         pending = current;
      }
      current = (current + 1) % sets;
      if (gaps) {
         org.lwjgl.opengl.GL33.glQueryCounter(gapResumeQ[gapSlot], org.lwjgl.opengl.GL33.GL_TIMESTAMP);
         gapSeq[gapSlot] = frames + 1; // this evaluation's number (frames is counted below)
      }
      lastWaitEndNs = System.nanoTime();
      waitNs += lastWaitEndNs - tEval;
      if (++statFrames >= 600) {
         stats();
      }
      Upscaler.output().set(setTex[shown][3], outW, outH);
      frames++;

      // 4. the next frame's jitter (Halton 2,3 over the phase count NVIDIA recommends: 8 x ratio^2)
      int phases = Math.max(8, Math.round(8.0F * ((float)outH / inH) * ((float)outH / inH)));
      haltonIndex = (haltonIndex + 1) % phases;
      float hx = halton(haltonIndex + 1, 2) - 0.5F;
      float hy = halton(haltonIndex + 1, 3) - 0.5F;
      RenderScale.setJitter(Config.DLSS_JITTER ? hx : 0.0F, Config.DLSS_JITTER ? hy : 0.0F);
      if (Config.DEV_UPSCALER_LOG && (frames <= 3 || frames % 600 == 0)) {
         Log.info("dlss: frame " + frames + " jitter " + jx + "," + jy + " camera " + offX + "," + offY + " zoom " + zoom + " depthTex " + sceneDepth + " " + frameMs + " ms");
      }

      // 5. leave the state the way the sprite ring buffer expects it
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
      GL11.glViewport(SAVED_VIEWPORT[0], SAVED_VIEWPORT[1], SAVED_VIEWPORT[2], SAVED_VIEWPORT[3]);
      Texture.lastTextureID = -1;
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
      for (int i = 0; i < 5; i++) {
         GL20.glEnableVertexAttribArray(i);
      }
      GL20.glUseProgram(0);
      GL11.glDepthMask(true);
      GL11.glEnable(GL11.GL_BLEND);
      GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
      GLStateRenderThread.restore();
      SpriteRenderer.ringBuffer.restoreVbos = true;
      SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      GpuSections.markNow("upscale", true);
   }

   /**
    * GL waits for DLSS. GL resumes 0.15-0.2 ms after the evaluation's last Vulkan timestamp (devDlssGaps, 2026-09-23);
    * flushing right after the wait (dlssFlushAfterWait) or after the composite, or naming only the output image in
    * the wait, changed neither that gap nor the frame rate: the GL work behind the wait is not what is late. The
    * likeliest reader is the GPU serving other channels (the compositor) once GL blocks, work off mode does too.
    */
   private static void waitDone(int semaphore, int[] images) {
      if (Config.DLSS_WAIT_OUTPUT_ONLY) {
         OUT_TEX[0] = images[3];
         EXTSemaphore.glWaitSemaphoreEXT(semaphore, NO_BUFFERS, OUT_TEX, OUT_LAYOUT);
      } else {
         EXTSemaphore.glWaitSemaphoreEXT(semaphore, NO_BUFFERS, images, LAYOUTS);
      }
      if (Config.DLSS_FLUSH_AFTER_WAIT) {
         GL11.glFlush();
      }
   }

   /** devDlssGaps: the hand-over gaps of the evaluation that last used this query slot, when its results are in. */
   private static void readGaps(int slot) {
      long seq = gapSeq[slot];
      if (seq == 0 || GL15.glGetQueryObjecti(gapResumeQ[slot], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
         return;
      }
      gapSeq[slot] = 0;
      long glSignal = org.lwjgl.opengl.GL33.glGetQueryObjecti64(gapSignalQ[slot], GL15.GL_QUERY_RESULT);
      long glResume = org.lwjgl.opengl.GL33.glGetQueryObjecti64(gapResumeQ[slot], GL15.GL_QUERY_RESULT);
      try {
         if ((int)times.invokeExact(seq, gapStart, gapEnd) != 0) {
            return;
         }
      } catch (Throwable t) {
         return;
      }
      long vkStart = gapStart.get(ValueLayout.JAVA_LONG, 0), vkEnd = gapEnd.get(ValueLayout.JAVA_LONG, 0);
      gapInNs += vkStart - glSignal;
      gapOutNs += glResume - vkEnd;
      gapCount++;
   }

   /** One console line per ~600 frames: DLSS's own GPU time (Vulkan timestamps) and the render thread's time per phase. */
   private static void stats() {
      double gpu = -1.0;
      try {
         if (gpuUs != null) {
            gpu = (double)gpuUs.invokeExact();
         }
      } catch (Throwable t) {
         gpuUs = null;
      }
      long n = Math.max(1L, statFrames);
      String gapText = gapCount == 0 ? "" : String.format(java.util.Locale.ROOT, " gap_in_us=%.0f gap_out_us=%.0f (n=%d)",
         gapInNs / 1000.0 / gapCount, gapOutNs / 1000.0 / gapCount, gapCount);
      Log.info(String.format(java.util.Locale.ROOT, "dlss: stats frames=%d gpu_dlss_us=%.0f cpu_prep_us=%.0f cpu_eval_us=%.0f cpu_wait_us=%.0f cpu_between_us=%.0f preset=%s %dx%d->%dx%d",
         statFrames, gpu, prepNs / 1000.0 / n, evalNs / 1000.0 / n, waitNs / 1000.0 / n, afterNs / 1000.0 / n, Config.DLSS_PRESET, inW, inH, outW, outH) + gapText);
      statFrames = prepNs = evalNs = waitNs = afterNs = 0L;
      gapInNs = gapOutNs = gapCount = 0L;
   }

   /** The DLSS output size for a screen size: dlssOutputPct of it (never below the render size), else the screen size. */
   private static int outputSize(int screenPixels) {
      int pct = Config.DLSS_OUTPUT_PCT;
      if (pct <= 0 || pct >= 100) {
         return screenPixels;
      }
      int render = Math.round(screenPixels * RenderScale.scale());
      int out = Math.round(screenPixels * pct / 100.0F);
      return out <= render * 1.02F ? render : out; // within 2 % of the render size (67 at quality): exactly that size, no 1.005x resample
   }

   /** The DLSS output is smaller than the screen (dlssOutputPct): Upscaler runs EASU + RCAS on it. */
   static boolean outputBelowScreen() {
      return ready && shown >= 0 && (outW < RenderScale.fullWidth(0) || outH < RenderScale.fullHeight(0));
   }

   static int outputTexture() {
      return setTex[shown][3];
   }

   static int[] outputRect() {
      return new int[]{0, 0, outW, outH};
   }

   static long objectRects() {
      return objectRects;
   }

   /**
    * dlssDirectColor, render thread, at every scaled start of the world frame (the world framebuffer bound): its
    * colour attachment becomes this frame's DLSS colour image, which has exactly the scaled viewport's size (player
    * 0 only), so the resolve needs no copy. The stock texture is attached back before the resolve
    * ({@link #detachDirectColor}); until then nothing samples the world colour (the cursor reads the resolved output,
    * Upscaler.cursorBackground).
    */
   static void attachDirectColor(int worldFbo) {
      if (!Config.DLSS_DIRECT_COLOR || !ready || !"dlss".equals(RenderScale.mode()) || RenderScale.worldPassPlayer() != 0 || IsoPlayer.numPlayers > 1) {
         return;
      }
      int image = setTex[current][0];
      if (image == 0 || directFbo == worldFbo && directTex == image) {
         return; // already (the world frame restarts after every chunk bake)
      }
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, image, 0);
      directFbo = worldFbo;
      directTex = image;
   }

   /** Puts the world framebuffer's own colour texture back; true when this frame was drawn into the DLSS image. */
   static boolean detachDirectColor() {
      if (directFbo == 0) {
         return false;
      }
      TextureFBO world = Core.getInstance().getOffscreenBuffer();
      boolean drewHere = world != null && world.getBufferId() == directFbo && directTex == setTex[current][0];
      int previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, directFbo);
      if (world != null && world.getBufferId() == directFbo) {
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, ((Texture)world.getTexture()).getID(), 0);
      }
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
      directFbo = 0;
      directTex = 0;
      return drewHere;
   }

   /** The world framebuffer's depth attachment when it is a texture (FogPass.sceneDepthAsTexture), else 0. */
   private static int sceneDepthTexture(int worldFbo) {
      if (worldFbo == sceneDepthFbo) {
         return sceneDepthTex; // the world framebuffer keeps its attachments; a new one (resize) is queried again
      }
      sceneDepthFbo = worldFbo;
      sceneDepthTex = queryDepthAttachment(worldFbo);
      return sceneDepthTex;
   }

   private static int queryDepthAttachment(int worldFbo) {
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, worldFbo);
      int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
      if (type != GL11.GL_TEXTURE) {
         return 0;
      }
      return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
   }

   private static void releaseGl() {
      detachDirectColor();
      for (int k = 0; k < 2; k++) {
         select(k);
         for (int i = 0; i < 4; i++) {
            if (tex[i] != 0) {
               GL11.glDeleteTextures(tex[i]);
               tex[i] = 0;
            }
            if (mem[i] != 0) {
               EXTMemoryObject.glDeleteMemoryObjectsEXT(mem[i]);
               mem[i] = 0;
            }
         }
         mvDepthStencilTex = 0;
         if (colorFbo != 0) GL30.glDeleteFramebuffers(colorFbo);
         if (mvFbo != 0) GL30.glDeleteFramebuffers(mvFbo);
         if (inputsFbo != 0) GL30.glDeleteFramebuffers(inputsFbo);
         colorFbo = mvFbo = inputsFbo = 0;
         if (semGlDone != 0) EXTSemaphore.glDeleteSemaphoresEXT(semGlDone);
         if (semDlssDone != 0) EXTSemaphore.glDeleteSemaphoresEXT(semDlssDone);
         semGlDone = semDlssDone = 0;
         store(k);
      }
      select(0);
      sceneDepthFbo = -1;
      current = 0;
      pending = -1;
      shown = -1;
      Upscaler.output().set(0, 0, 0);
   }

   /** A framebuffer with two colour attachments drawn together (glDrawBuffers), or 0 when incomplete. */
   private static int framebufferOf2(int tex0, int tex1) {
      int previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
      int fbo = GL30.glGenFramebuffers();
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex0, 0);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, tex1, 0);
      GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
      int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
      if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
         GL30.glDeleteFramebuffers(fbo);
         return 0;
      }
      return fbo;
   }

   static float halton(int index, int base) {
      float f = 1.0F, r = 0.0F;
      while (index > 0) {
         f /= base;
         r += f * (index % base);
         index /= base;
      }
      return r;
   }

   static long frames() {
      return frames;
   }

   /**
    * The inputs pass: attachment 0 the scene depth of the low-res region resampled 1:1 into the R32F depth image (or a
    * constant when none); attachment 1 the camera motion per pixel, in low-res pixels from the current position to the
    * previous one (DLSS's convention, MVScale 1): a fragment at memory row / column (x, y) is the screen point
    * (x / s, (inH - y) / s), which is the world pixel offX + sx * zoom; the previous frame's camera puts that world
    * pixel at another position.
    */
   static final String INPUTS_FRAG = String.join("\n",
      "#version 330",
      "uniform sampler2D SceneDepth;",
      "uniform ivec2 origin;",
      "uniform float constantDepth;",
      "uniform vec3 cur;",
      "uniform vec3 prev;",
      "uniform vec4 params;",
      "layout(location = 0) out float fragDepth;",
      "layout(location = 1) out vec2 fragMv;",
      "void main() {",
      "   fragDepth = constantDepth >= 0.0 ? constantDepth : texelFetch(SceneDepth, ivec2(gl_FragCoord.xy) + origin, 0).r;",
      "   float s = params.x;",
      "   float inH = params.y;",
      "   vec2 p = gl_FragCoord.xy;",
      "   vec2 screen = vec2(p.x / s, (inH - p.y) / s);",
      "   vec2 world = cur.xy + screen * cur.z;",
      "   vec2 prevScreen = (world - prev.xy) / prev.z;",
      "   vec2 prevP = vec2(prevScreen.x * s, inH - prevScreen.y * s);",
      "   fragMv = (prevP - p) * params.z;",
      "}");

   /** One object's motion over its stencil-masked rectangle. */
   static final String RECT_FRAG = String.join("\n",
      "#version 330",
      "uniform vec2 mv;",
      "out vec2 fragMv;",
      "void main() { fragMv = mv; }");
}
