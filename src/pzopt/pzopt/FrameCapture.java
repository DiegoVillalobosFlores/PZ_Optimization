package pzopt;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Dev rig {@code devCapture=start,seconds,fps,scalePct} (2026-09-25): a frame sequence of the presented picture without a
 * screen recorder (machines where none can be installed). Right before the swap, from {@code start} seconds after the
 * world is up for {@code seconds}, at most {@code fps} frames a second: the back buffer is blitted into a
 * {@code scalePct} % framebuffer and read back (synchronous, a dev rig: never for timing), a writer thread appends the
 * RGBA rows (bottom-up) to {@code ~/Zomboid/pzopt-capture/frames.rgba}; {@code index.txt} holds the size and one epoch ms
 * per frame. {@code harness/ppl/capture.py} turns it into PNGs and temporal metrics. A fifth field {@code gray} writes
 * one luma byte per pixel to {@code frames.gray} instead (a quarter of the bytes: minute-long walks on the laptops).
 */
public final class FrameCapture {
   private FrameCapture() {
   }

   private static float start = -1F, seconds, fps, scale;
   private static long worldUpNs, lastNs;
   private static int fbo, tex, w, h, frames;
   private static boolean done, failed, gray;
   private static final ArrayBlockingQueue<Object[]> QUEUE = new ArrayBlockingQueue<>(64);
   private static Thread writer;

   /** Render thread, Display.swapBuffers, before the swap. */
   public static void beforeSwap() {
      if (done || failed || Config.DEV_CAPTURE.isEmpty()) {
         return;
      }
      try {
         capture();
      } catch (Throwable t) {
         failed = true;
         Log.warn("frame capture: " + t);
      }
   }

   private static void capture() throws Exception {
      if (start < 0F) {
         String[] p = Config.DEV_CAPTURE.split(",");
         start = Float.parseFloat(p[0].trim());
         seconds = p.length > 1 ? Float.parseFloat(p[1].trim()) : 10F;
         fps = p.length > 2 ? Float.parseFloat(p[2].trim()) : 30F;
         scale = (p.length > 3 ? Float.parseFloat(p[3].trim()) : 50F) / 100F;
         gray = p.length > 4 && "gray".equalsIgnoreCase(p[4].trim());
      }
      if (zombie.iso.IsoWorld.instance == null || zombie.iso.IsoWorld.instance.currentCell == null) {
         return;
      }
      long now = System.nanoTime();
      if (worldUpNs == 0L) {
         worldUpNs = now;
      }
      double t = (now - worldUpNs) / 1e9;
      if (t < start) {
         return;
      }
      if (t > start + seconds) {
         done = true;
         QUEUE.put(new Object[0]);
         Log.info("frame capture: " + frames + " frames " + w + "x" + h + " to " + dir());
         return;
      }
      if (now - lastNs < (long)(1e9 / fps) - 2_000_000L) {
         return;
      }
      lastNs = now;
      int sw = org.lwjglx.opengl.Display.getWidth(), sh = org.lwjglx.opengl.Display.getHeight();
      if (fbo == 0) {
         w = Math.max(16, Math.round(sw * scale));
         h = Math.max(16, Math.round(sh * scale));
         tex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         int prev = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
         fbo = GL30.glGenFramebuffers();
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
         dir().mkdirs();
         java.nio.file.Files.writeString(new File(dir(), "index.txt").toPath(), "w=" + w + " h=" + h + (gray ? " fmt=gray" : "") + "\n");
         writer = new Thread(FrameCapture::write, "pzopt-capture");
         writer.setDaemon(true);
         writer.start();
      }
      int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
      GL11.glReadBuffer(GL11.GL_BACK);
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
      boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL30.glBlitFramebuffer(0, 0, sw, sh, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
      ByteBuffer b = BufferUtils.createByteBuffer(w * h * 4);
      GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, b);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
      if (scissor) {
         GL11.glEnable(GL11.GL_SCISSOR_TEST);
      }
      frames++;
      if (!QUEUE.offer(new Object[] {b, System.currentTimeMillis()})) {
         frames--; // the writer fell behind: the frame is dropped (its stamp too)
      }
   }

   static File dir() {
      return new File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt-capture");
   }

   private static void write() {
      try (FileOutputStream out = new FileOutputStream(new File(dir(), gray ? "frames.gray" : "frames.rgba")); java.io.FileWriter idx = new java.io.FileWriter(new File(dir(), "index.txt"), true)) {
         byte[] row = null;
         while (true) {
            Object[] e = QUEUE.take();
            if (e.length == 0) {
               break;
            }
            ByteBuffer b = (ByteBuffer)e[0];
            if (gray) {
               int n = b.capacity() / 4;
               if (row == null || row.length != n) {
                  row = new byte[n];
               }
               for (int i = 0; i < n; i++) { // BT.601 luma, as region-flicker.py computes it
                  int r = b.get(i * 4) & 255, g = b.get(i * 4 + 1) & 255, bl = b.get(i * 4 + 2) & 255;
                  row[i] = (byte)((r * 77 + g * 150 + bl * 29) >> 8);
               }
               out.write(row);
               idx.write(e[1] + "\n");
               continue;
            }
            if (row == null || row.length != b.capacity()) {
               row = new byte[b.capacity()];
            }
            b.get(0, row);
            out.write(row);
            idx.write(e[1] + "\n");
         }
      } catch (Throwable t) {
         Log.warn("frame capture writer: " + t);
      }
   }
}
