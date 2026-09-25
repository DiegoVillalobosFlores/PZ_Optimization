import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;
import pzopt.TexBc;
import pzopt.TexBcGpu;

/**
 * textureCompression cost probe: real .pack pages (PNG-decoded, box-filtered mip chains) uploaded the ways the game
 * could, timed on one "render" thread with a real GL context, without launching the game.
 *
 *   javac --release 25 -cp projectzomboid.jar -d /tmp/tcp src/pzopt/pzopt/TexBc.java src/pzopt/pzopt/TexBcGpu.java tools/TexCompProbe.java
 *   <game>/jre64/bin/java -cp <game>/projectzomboid.jar:/tmp/tcp TexCompProbe <pages> <threads> <methods> <pack>...
 *
 * methods (comma list): rgba (plain RGBA8 upload = textureCompression off), driver (GL_COMPRESSED_RGBA: the driver
 * compresses), cpu-fast / cpu-hq (TexBc on <threads> workers, then glCompressedTexImage2D), gpu-fast / gpu-hq
 * (TexBcGpu compute). Per method: render-thread ms (GL calls; "+finish" adds glFinish = GPU work), worker ms, internal
 * format, PSNR of level 0 vs the source (RGB over alpha > 0, alpha over all).
 */
public final class TexCompProbe {
   record Page(String name, int w, int h, ByteBuffer[] lv, int[] ws, int[] hs) {
   }

   public static void main(String[] args) throws Exception {
      int maxPages = Integer.parseInt(args[0]);
      int threads = Integer.parseInt(args[1]);
      String[] methods = args[2].split(",");
      List<Page> pages = new ArrayList<>();
      long t0 = System.nanoTime();
      for (int i = 3; i < args.length && pages.size() < maxPages; i++) {
         readPack(new File(args[i]), pages, maxPages);
      }
      long px = 0;
      for (Page p : pages) {
         for (int i = 0; i < p.lv.length; i++) {
            px += (long) p.ws[i] * p.hs[i];
         }
      }
      System.out.printf("pages %d, %.1f MPixel with mips, decoded in %d ms%n", pages.size(), px / 1e6, (System.nanoTime() - t0) / 1_000_000);

      GLFW.glfwInit();
      GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
      long win = GLFW.glfwCreateWindow(64, 64, "TexCompProbe", 0L, 0L);
      GLFW.glfwMakeContextCurrent(win);
      GL.createCapabilities();
      System.out.println("GL " + GL11C.glGetString(GL11C.GL_VERSION) + " / " + GL11C.glGetString(GL11C.GL_RENDERER));
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      if (Boolean.getBoolean("probe.lut")) {
         long tl = System.nanoTime();
         boolean ok = pzopt.TexBcPalette.measure();
         if (ok && TexBcGpu.init()) {
            TexBcGpu.setLut();
         }
         System.out.printf("measured decoder: %s (%.1f ms)%s%n", ok, (System.nanoTime() - tl) / 1e6, ok ? "" : " " + pzopt.TexBcPalette.error);
      }
      if (System.getProperty("probe.palette") != null) {
         palette(System.getProperty("probe.palette"));
         return;
      }
      if (Boolean.getBoolean("probe.alphadiff")) {
         alphaDiff(pages);
         return;
      }
      if (Boolean.getBoolean("probe.mipcheck")) {
         mipCheck(args);
         return;
      }
      warm(pages.get(0), methods, pool);
      for (String m : methods) {
         run(m, pages, pool, threads);
      }
      pool.shutdown();
      GLFW.glfwDestroyWindow(win);
      GLFW.glfwTerminate();
   }

   static void warm(Page p, String[] methods, ExecutorService pool) throws Exception {
      for (String m : methods) {
         int tex = upload(m, p, pool, 4, new long[2]);
         GL11C.glFinish();
         GL11C.glDeleteTextures(tex);
      }
   }

   /** Stress: every page uploaded back to back (no glFinish), then every texture checked; returns the worst PSNR. */
   static void runNoFinish(String m, List<Page> pages, ExecutorService pool, int threads) throws Exception {
      int[] tex = new int[pages.size()];
      long t0 = System.nanoTime();
      for (int i = 0; i < pages.size(); i++) {
         tex[i] = uploadNoFinish(m, pages.get(i));
      }
      long calls = System.nanoTime() - t0;
      GL11C.glFinish();
      double worst = 99;
      int mismatched = 0;
      for (int i = 0; i < pages.size(); i++) {
         Page p = pages.get(i);
         ByteBuffer got = sampled(tex[i], p.w, p.h);
         // the same page again, finished on its own: the encoder is deterministic, so any difference is a race
         int again = uploadNoFinish(m, p);
         GL11C.glFinish();
         ByteBuffer ref = sampled(again, p.w, p.h);
         if (!got.equals(ref)) {
            mismatched++;
         }
         MemoryUtil.memFree(ref);
         GL11C.glDeleteTextures(again);
         double se = 0;
         long n = 0;
         for (int k = 0; k < p.w * p.h; k++) {
            if ((p.lv[0].get(k * 4 + 3) & 0xFF) > 0) {
               for (int c = 0; c < 3; c++) {
                  int d = (p.lv[0].get(k * 4 + c) & 0xFF) - (got.get(k * 4 + c) & 0xFF);
                  se += d * d;
               }
               n += 3;
            }
         }
         worst = Math.min(worst, psnr(se, n));
         MemoryUtil.memFree(got);
         GL11C.glDeleteTextures(tex[i]);
      }
      System.out.printf("%-9s no-finish: %d pages, calls %.1f ms, worst page PSNR rgb %.2f dB, %d pages differ from a finished upload%n", m, pages.size(), calls / 1e6, worst, mismatched);
   }

   static int uploadNoFinish(String m, Page p) {
      int tex = GL11C.glGenTextures();
      GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);
      GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR_MIPMAP_LINEAR);
      TexBcGpu.upload(p.lv, p.ws, p.hs, 0, m.equals("gpu-hq"));
      return tex;
   }

   static void run(String m, List<Page> pages, ExecutorService pool, int threads) throws Exception {
      if (Boolean.getBoolean("probe.nofinish") && m.startsWith("gpu-")) {
         runNoFinish(m, pages, pool, threads);
         return;
      }
      long calls = 0, work = 0;
      long[] acc = new long[2];
      double sumSeRgb = 0, sumSeA = 0;
      long nRgb = 0, nA = 0;
      int fmt = 0;
      long t0 = System.nanoTime();
      for (Page p : pages) {
         acc[0] = acc[1] = 0;
         int tex = upload(m, p, pool, threads, acc);
         calls += acc[0];
         work += acc[1];
         // quality on level 0 (untimed)
         GL11C.glFinish();
         fmt = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
         ByteBuffer got = sampled(tex, p.w, p.h);
         ByteBuffer src = p.lv[0];
         for (int i = 0; i < p.w * p.h; i++) {
            int a = src.get(i * 4 + 3) & 0xFF;
            int da = a - (got.get(i * 4 + 3) & 0xFF);
            sumSeA += da * da;
            nA++;
            if (a > 0) {
               for (int c = 0; c < 3; c++) {
                  int d = (src.get(i * 4 + c) & 0xFF) - (got.get(i * 4 + c) & 0xFF);
                  sumSeRgb += d * d;
               }
               nRgb += 3;
            }
         }
         MemoryUtil.memFree(got);
         GL11C.glDeleteTextures(tex);
      }
      long total = System.nanoTime() - t0;
      System.out.printf("%-9s render thread %7.1f ms  worker wall %7.1f ms  | fmt 0x%04X  PSNR rgb %5.2f dB  alpha %5.2f dB  (loop %d ms)%n",
            m, calls / 1e6, work / 1e6, fmt, psnr(sumSeRgb, nRgb), psnr(sumSeA, nA), total / 1_000_000);
   }

   /**
    * GPU mips (TexBcGpu.mips) against ImageData's own chain, byte for byte: raw level 0 of each page into a storage
    * buffer, the GPU builds the levels, the readback is premultiplied like ImageData.performPreMultipliedAlpha and
    * compared with getMipMapData(i).
    */
   static void mipCheck(String[] args) throws Exception {
      if (!TexBcGpu.init()) {
         throw new IllegalStateException(TexBcGpu.error);
      }
      byte[] all = java.nio.file.Files.readAllBytes(new File(args[3]).toPath());
      ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
      if (all[0] == 'P' && all[1] == 'Z') {
         bb.position(8);
      }
      int count = bb.getInt(), checked = 0, levelsBad = 0;
      long bytes = 0;
      for (int i = 0; i < count && checked < Integer.parseInt(args[0]); i++) {
         str(bb);
         int entries = bb.getInt();
         bb.getInt();
         for (int e = 0; e < entries; e++) {
            str(bb);
            bb.position(bb.position() + 32);
         }
         int len = bb.getInt(), start = bb.position();
         bb.position(start + len);
         for (boolean preserve : new boolean[] {false, true}) {
            zombie.core.textures.ImageData raw = new zombie.core.textures.ImageData(new ByteArrayInputStream(all, start, len), false);
            zombie.core.textures.ImageData cpu = new zombie.core.textures.ImageData(new ByteArrayInputStream(all, start, len), false);
            raw.preserveTransparentColor = preserve;
            cpu.preserveTransparentColor = preserve;
            int n = zombie.core.textures.ImageData.calculateNumMips(raw.getWidthHW(), raw.getHeightHW());
            int[] w = new int[n], h = new int[n];
            w[0] = raw.getWidthHW();
            h[0] = raw.getHeightHW();
            long total = 0;
            for (int l = 0; l < n; l++) {
               if (l > 0) {
                  w[l] = zombie.core.textures.ImageData.getNextMipDimension(w[l - 1]);
                  h[l] = zombie.core.textures.ImageData.getNextMipDimension(h[l - 1]);
               }
               total += (long) w[l] * h[l] * 4;
            }
            int buf = GL15C.glGenBuffers();
            GL15C.glBindBuffer(org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER, buf);
            GL15C.glBufferData(org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER, total, GL15C.GL_STATIC_DRAW);
            ByteBuffer l0 = raw.getData().getBuffer().duplicate();
            l0.position(0).limit(w[0] * h[0] * 4);
            GL15C.glBufferSubData(org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER, 0, l0);
            TexBcGpu.mips(buf, 0, w, h, preserve);
            ByteBuffer back = MemoryUtil.memAlloc((int) total);
            GL15C.glBindBuffer(org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER, buf);
            GL15C.glGetBufferSubData(org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER, 0, back);
            GL15C.glDeleteBuffers(buf);
            long off = 0;
            for (int l = 0; l < n; l++) {
               ByteBuffer ref = cpu.getMipMapData(l).getBuffer();
               int sz = w[l] * h[l] * 4;
               boolean same = true;
               for (int k = 0; k < sz && same; k += 4) {
                  int a = back.get((int) off + k + 3) & 0xFF;
                  for (int c = 0; c < 3; c++) {
                     int v = (back.get((int) off + k + c) & 0xFF) * a / 255;
                     same &= (byte) v == ref.get(k + c);
                  }
                  same &= (byte) a == ref.get(k + 3);
               }
               if (!same) {
                  levelsBad++;
               }
               off += sz;
               bytes += sz;
            }
            MemoryUtil.memFree(back);
            raw.dispose();
            cpu.dispose();
         }
         checked++;
      }
      System.out.printf("mipcheck: %d pages x 2 (preserveTransparentColor off/on), %.1f MB of levels, %d levels differ%n", checked, bytes / 1e6, levelsBad);
   }

   /**
    * Where the driver's DXT5 alpha beats ours: per 4x4 block, alpha squared error of both (as the GPU samples them) on
    * level 0; prints the totals by block kind and the blocks we lose most on (source alphas, both encodings).
    */
   static void alphaDiff(List<Page> pages) throws Exception {
      long oursTotal = 0, drvTotal = 0, blocksWorse = 0, blocksBetter = 0, blocks = 0;
      java.util.TreeMap<Long, String> worst = new java.util.TreeMap<>();
      java.util.Map<String, long[]> byKind = new java.util.TreeMap<>();
      for (Page p : pages) {
         int w = p.w, h = p.h;
         int td = upload("driver", p, null, 1, new long[2]);
         ByteBuffer drvBlk = MemoryUtil.memAlloc(TexBc.bc3Size(w, h));
         GL13C.glGetCompressedTexImage(GL11C.GL_TEXTURE_2D, 0, drvBlk);
         ByteBuffer drv = sampled(td, w, h);
         GL11C.glDeleteTextures(td);
         ByteBuffer ours = MemoryUtil.memAlloc(TexBc.bc3Size(w, h)).order(ByteOrder.LITTLE_ENDIAN);
         TexBc.encodeBc3(p.lv[0], 0, w, h, ours, 0, 0, (h + 3) >> 2, true);
         int to = GL11C.glGenTextures();
         GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, to);
         GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
         GL13C.glCompressedTexImage2D(GL11C.GL_TEXTURE_2D, 0, TexBc.GL_COMPRESSED_RGBA_S3TC_DXT5_EXT, w, h, 0, ours);
         ByteBuffer our = sampled(to, w, h);
         GL11C.glDeleteTextures(to);
         int bw = (w + 3) / 4;
         for (int by = 0; by < (h + 3) / 4; by++) {
            for (int bx = 0; bx < bw; bx++) {
               long eo = 0, ed = 0;
               int mn = 255, mx = 0;
               boolean has0 = false, has255 = false;
               StringBuilder src = new StringBuilder();
               for (int i = 0; i < 16; i++) {
                  int x = Math.min(bx * 4 + i % 4, w - 1), y = Math.min(by * 4 + i / 4, h - 1);
                  int k = (y * w + x) * 4 + 3;
                  int a = p.lv[0].get(k) & 0xFF;
                  int d1 = a - (our.get(k) & 0xFF), d2 = a - (drv.get(k) & 0xFF);
                  eo += d1 * d1;
                  ed += d2 * d2;
                  mn = Math.min(mn, a);
                  mx = Math.max(mx, a);
                  has0 |= a == 0;
                  has255 |= a == 255;
                  src.append(a).append(i % 4 == 3 ? " | " : " ");
               }
               blocks++;
               oursTotal += eo;
               drvTotal += ed;
               String kind = mn == mx ? "flat" : (has0 || has255) && (mx - mn) > 128 ? "wide+extreme" : (mx - mn) > 128 ? "wide" : "narrow";
               long[] kv = byKind.computeIfAbsent(kind, q -> new long[3]);
               kv[0]++;
               kv[1] += eo;
               kv[2] += ed;
               if (eo > ed) {
                  blocksWorse++;
                  if (worst.size() < 12 || eo - ed > worst.firstKey()) {
                     int o = (by * bw + bx) * 16;
                     worst.put((eo - ed) * 1000000 + blocks % 1000000, "ours " + eo + " drv " + ed + " | src " + src + " ours a0/a1 " + (ours.get(o) & 0xFF) + "/" + (ours.get(o + 1) & 0xFF)
                           + " drv a0/a1 " + (drvBlk.get(o) & 0xFF) + "/" + (drvBlk.get(o + 1) & 0xFF));
                     if (worst.size() > 12) {
                        worst.pollFirstEntry();
                     }
                  }
               } else if (eo < ed) {
                  blocksBetter++;
               }
            }
         }
         MemoryUtil.memFree(drvBlk);
         MemoryUtil.memFree(drv);
         MemoryUtil.memFree(ours);
         MemoryUtil.memFree(our);
      }
      System.out.printf("alphadiff: %d blocks, alpha SSE ours %d vs driver %d; blocks worse %d, better %d%n", blocks, oursTotal, drvTotal, blocksWorse, blocksBetter);
      for (java.util.Map.Entry<String, long[]> e : byKind.entrySet()) {
         System.out.printf("  %-13s %8d blocks  SSE ours %10d  driver %10d%n", e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]);
      }
      for (String v : worst.descendingMap().values()) {
         System.out.println("  " + v);
      }
   }

   /** The GPU's BC3 alpha palette for endpoints "a0,a1": one 4x4 block, pixel i uses index i % 8, sampled back. */
   static void palette(String spec) {
      String[] ab = spec.split(",");
      int a0 = Integer.parseInt(ab[0]), a1 = Integer.parseInt(ab[1]);
      ByteBuffer blk = MemoryUtil.memAlloc(16).order(ByteOrder.LITTLE_ENDIAN);
      long bits = 0;
      for (int i = 0; i < 16; i++) {
         bits |= (long) (i % 8) << (3 * i);
      }
      blk.putLong(0, a0 | (long) a1 << 8 | bits << 16);
      blk.putLong(8, 0L);
      int t = GL11C.glGenTextures();
      GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, t);
      GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
      GL13C.glCompressedTexImage2D(GL11C.GL_TEXTURE_2D, 0, TexBc.GL_COMPRESSED_RGBA_S3TC_DXT5_EXT, 4, 4, 0, blk);
      ByteBuffer got = sampled(t, 4, 4);
      StringBuilder sb = new StringBuilder("palette " + a0 + "," + a1 + " (" + (a0 > a1 ? "8-level" : "6-level") + "): GPU");
      for (int k = 0; k < 8; k++) {
         sb.append(' ').append(got.get(k * 4 + 3) & 0xFF);
      }
      sb.append("   encoder model");
      for (int k = 0; k < 8; k++) {
         sb.append(' ').append(pzoptAlpha(a0, a1, k));
      }
      System.out.println(sb);
   }

   static int pzoptAlpha(int a0, int a1, int k) {
      try {
         java.lang.reflect.Method m = TexBc.class.getDeclaredMethod("alphaValue", int.class, int.class, int.class);
         m.setAccessible(true);
         return (int) m.invoke(null, a0, a1, k);
      } catch (ReflectiveOperationException e) {
         throw new IllegalStateException(e);
      }
   }

   static int fetchProg, fetchVao;

   /** Level 0 as the GPU samples it: texelFetch into an RGBA8 framebuffer, read back (the hardware decoder). */
   static ByteBuffer sampled(int tex, int w, int h) {
      if (fetchProg == 0) {
         int vs = sh(org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER, "#version 330\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0.0,1.0);}");
         int fs = sh(org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER, "#version 330\nuniform sampler2D t;out vec4 o;void main(){o=texelFetch(t,ivec2(gl_FragCoord.xy),0);}");
         fetchProg = org.lwjgl.opengl.GL20C.glCreateProgram();
         org.lwjgl.opengl.GL20C.glAttachShader(fetchProg, vs);
         org.lwjgl.opengl.GL20C.glAttachShader(fetchProg, fs);
         org.lwjgl.opengl.GL20C.glLinkProgram(fetchProg);
         fetchVao = org.lwjgl.opengl.GL30C.glGenVertexArrays();
      }
      int rt = GL11C.glGenTextures();
      GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, rt);
      GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, w, h, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
      int fbo = org.lwjgl.opengl.GL30C.glGenFramebuffers();
      org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER, fbo);
      org.lwjgl.opengl.GL30C.glFramebufferTexture2D(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER, org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, rt, 0);
      GL11C.glViewport(0, 0, w, h);
      GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);
      org.lwjgl.opengl.GL20C.glUseProgram(fetchProg);
      org.lwjgl.opengl.GL30C.glBindVertexArray(fetchVao);
      GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
      ByteBuffer got = MemoryUtil.memAlloc(w * h * 4);
      GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1);
      GL11C.glReadPixels(0, 0, w, h, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, got);
      org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER, 0);
      org.lwjgl.opengl.GL30C.glDeleteFramebuffers(fbo);
      GL11C.glDeleteTextures(rt);
      GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);
      return got;
   }

   static int sh(int type, String src) {
      int s = org.lwjgl.opengl.GL20C.glCreateShader(type);
      org.lwjgl.opengl.GL20C.glShaderSource(s, src);
      org.lwjgl.opengl.GL20C.glCompileShader(s);
      if (org.lwjgl.opengl.GL20C.glGetShaderi(s, org.lwjgl.opengl.GL20C.GL_COMPILE_STATUS) == 0) {
         throw new IllegalStateException(org.lwjgl.opengl.GL20C.glGetShaderInfoLog(s));
      }
      return s;
   }

   static double psnr(double se, long n) {
      if (n == 0 || se == 0) {
         return 99.0;
      }
      return 10 * Math.log10(255.0 * 255.0 / (se / n));
   }

   /** Returns the texture; acc[0] += render-thread ns (GL calls + glFinish), acc[1] += worker wall ns before it. */
   static int upload(String m, Page p, ExecutorService pool, int threads, long[] acc) throws Exception {
      ByteBuffer[] enc = null;
      if (m.startsWith("cpu-")) {
         boolean hq = m.equals("cpu-hq");
         long w0 = System.nanoTime();
         enc = new ByteBuffer[p.lv.length];
         List<Future<?>> fs = new ArrayList<>();
         for (int i = 0; i < p.lv.length; i++) {
            int w = p.ws[i], h = p.hs[i];
            ByteBuffer dst = MemoryUtil.memAlloc(TexBc.bc3Size(w, h));
            enc[i] = dst;
            int bh = (h + 3) >> 2;
            int step = Math.max(1, bh / (threads * 4));
            ByteBuffer src = p.lv[i];
            for (int by = 0; by < bh; by += step) {
               int a = by, b = Math.min(bh, by + step);
               fs.add(pool.submit(() -> TexBc.encodeBc3(src, 0, w, h, dst, 0, a, b, hq)));
            }
         }
         for (Future<?> f : fs) {
            f.get();
         }
         acc[1] += System.nanoTime() - w0;
      }
      long t0 = System.nanoTime();
      int tex = GL11C.glGenTextures();
      GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);
      GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR_MIPMAP_LINEAR);
      GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
      GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
      switch (m) {
         case "rgba", "driver" -> {
            int ifmt = m.equals("rgba") ? GL11C.GL_RGBA8 : GL13C.GL_COMPRESSED_RGBA;
            for (int i = 0; i < p.lv.length; i++) {
               GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, i, ifmt, p.ws[i], p.hs[i], 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, p.lv[i]);
            }
         }
         case "cpu-fast", "cpu-hq" -> {
            for (int i = 0; i < p.lv.length; i++) {
               GL13C.glCompressedTexImage2D(GL11C.GL_TEXTURE_2D, i, TexBc.GL_COMPRESSED_RGBA_S3TC_DXT5_EXT, p.ws[i], p.hs[i], 0, enc[i]);
            }
         }
         case "gpu-fast", "gpu-hq" -> {
            if (!TexBcGpu.init()) {
               throw new IllegalStateException("gpu encoder: " + TexBcGpu.error);
            }
            TexBcGpu.upload(p.lv, p.ws, p.hs, 0, m.equals("gpu-hq"));
         }
         default -> throw new IllegalArgumentException(m);
      }
      long calls = System.nanoTime() - t0;
      GL11C.glFinish();
      acc[0] += System.nanoTime() - t0;
      if (enc != null) {
         for (ByteBuffer b : enc) {
            MemoryUtil.memFree(b);
         }
      }
      if (Boolean.getBoolean("probe.calls")) {
         System.out.printf("  %s %s %dx%d calls %.2f ms%n", m, p.name, p.w, p.h, calls / 1e6);
      }
      int err = GL11C.glGetError();
      if (err != 0) {
         throw new IllegalStateException(m + ": GL error 0x" + Integer.toHexString(err));
      }
      return tex;
   }

   // --- .pack reading (TexturePackDevice format: optional "PZPK" + version, pages of name / entries / PNG) --------

   static void readPack(File f, List<Page> pages, int max) throws IOException {
      byte[] all;
      try (InputStream in = new FileInputStream(f)) {
         all = in.readAllBytes();
      }
      ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
      int version = 0;
      if (all[0] == 'P' && all[1] == 'Z' && all[2] == 'P' && all[3] == 'K') {
         bb.position(4);
         version = bb.getInt();
      }
      int count = bb.getInt();
      for (int i = 0; i < count && pages.size() < max; i++) {
         String name = str(bb);
         int entries = bb.getInt();
         bb.getInt(); // mask
         for (int e = 0; e < entries; e++) {
            str(bb);
            bb.position(bb.position() + 32);
         }
         int start = bb.position();
         int len;
         if (version >= 1) {
            len = bb.getInt();
            start = bb.position();
            bb.position(start + len);
         } else {
            int p = start;
            while (!(all[p] == (byte) 0xEF && all[p + 1] == (byte) 0xBE && all[p + 2] == (byte) 0xAD && all[p + 3] == (byte) 0xDE)) {
               p++;
            }
            len = p - start;
            bb.position(p + 4);
         }
         if (Boolean.getBoolean("probe.game")) {
            pages.add(gamePage(f.getName() + ":" + name, new ByteArrayInputStream(all, start, len)));
            continue;
         }
         BufferedImage img = ImageIO.read(new ByteArrayInputStream(all, start, len));
         if (img == null) {
            continue;
         }
         pages.add(page(f.getName() + ":" + name, img));
      }
   }

   static String str(ByteBuffer bb) {
      int n = bb.getInt();
      byte[] s = new byte[n];
      bb.get(s);
      return new String(s);
   }

   /** The game's own decode: ImageData (power-of-two padded), initMipMaps, getMipMapData (premultiplied), as uploaded. */
   static Page gamePage(String name, InputStream in) {
      try {
         zombie.core.textures.ImageData d = new zombie.core.textures.ImageData(in, false);
         d.initMipMaps();
         int n = d.getMipMapCount();
         ByteBuffer[] lv = new ByteBuffer[n];
         int[] ws = new int[n], hs = new int[n];
         for (int i = 0; i < n; i++) {
            zombie.core.textures.MipMapLevel l = d.getMipMapData(i);
            ws[i] = l.width;
            hs[i] = l.height;
            ByteBuffer c = MemoryUtil.memAlloc(l.width * l.height * 4);
            ByteBuffer s = l.getBuffer().duplicate();
            s.position(0).limit(l.width * l.height * 4);
            c.put(s).flip();
            lv[i] = c;
         }
         return new Page(name, ws[0], hs[0], lv, ws, hs);
      } catch (Exception e) {
         throw new IllegalStateException(e);
      }
   }

   static Page page(String name, BufferedImage img) {
      int w = img.getWidth(), h = img.getHeight();
      int levels = 1 + (31 - Integer.numberOfLeadingZeros(Math.max(w, h)));
      ByteBuffer[] lv = new ByteBuffer[levels];
      int[] ws = new int[levels], hs = new int[levels];
      int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
      ByteBuffer l0 = MemoryUtil.memAlloc(w * h * 4);
      for (int i = 0; i < w * h; i++) {
         int v = argb[i];
         l0.put(i * 4, (byte) (v >> 16)).put(i * 4 + 1, (byte) (v >> 8)).put(i * 4 + 2, (byte) v).put(i * 4 + 3, (byte) (v >>> 24));
      }
      lv[0] = l0;
      ws[0] = w;
      hs[0] = h;
      for (int l = 1; l < levels; l++) {
         int pw = ws[l - 1], ph = hs[l - 1];
         int nw = Math.max(1, pw >> 1), nh = Math.max(1, ph >> 1);
         ByteBuffer n = MemoryUtil.memAlloc(nw * nh * 4);
         ByteBuffer s = lv[l - 1];
         for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
               for (int c = 0; c < 4; c++) {
                  int x0 = Math.min(2 * x, pw - 1), x1 = Math.min(2 * x + 1, pw - 1);
                  int y0 = Math.min(2 * y, ph - 1), y1 = Math.min(2 * y + 1, ph - 1);
                  int sum = (s.get((y0 * pw + x0) * 4 + c) & 0xFF) + (s.get((y0 * pw + x1) * 4 + c) & 0xFF)
                        + (s.get((y1 * pw + x0) * 4 + c) & 0xFF) + (s.get((y1 * pw + x1) * 4 + c) & 0xFF);
                  n.put((y * nw + x) * 4 + c, (byte) ((sum + 2) >> 2));
               }
            }
         }
         lv[l] = n;
         ws[l] = nw;
         hs[l] = nh;
      }
      return new Page(name, w, h, lv, ws, hs);
   }
}
