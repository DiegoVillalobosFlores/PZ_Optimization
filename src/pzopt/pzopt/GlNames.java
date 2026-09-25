package pzopt;

import java.util.HashSet;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * glNoSync (2026-09-25, the Rosewood drive): GL object names without a driver round trip in the frame.
 *
 * <p>A GL call that returns a value (glGenTextures, glGenFramebuffers, glCheckFramebufferStatus, any glGet) makes the
 * render thread wait until NVIDIA's threaded driver has worked through everything queued before it. On the drive the
 * render thread's late frames spent ~25 % of their time in glGenTextures (corpse clothing textures from
 * TextureCombiner, new corpse / item atlas pages, render-chunk textures) and a few % in the framebuffer status check.
 * Names are only reserved numbers, so they are taken from a pool here, refilled with one batched glGen call right after
 * the swap ({@link #refill}), when the render thread has handed the frame over and waits for the next one anyway.
 * Framebuffer names belong to one GL context: the pool serves only the thread that filled it (the render thread),
 * any other caller asks the driver as before. A framebuffer configuration that came out complete once (same attachment
 * sizes, depth texture or renderbuffer, stencil) is not checked again: completeness depends only on those.
 */
public final class GlNames {
   public static final boolean ON = Config.GL_NO_SYNC && Overrides.enabled();
   private static final int[] textures = new int[Math.max(8, Config.GL_NAME_POOL)];
   private static final int[] framebuffers = new int[Math.max(4, Config.GL_NAME_POOL / 4)];
   private static int texN, fboN;
   private static volatile Thread owner;
   private static final HashSet<Long> completeShapes = new HashSet<>();
   public static long texServed, texFallback, fboServed, fboFallback, refills, statusSkipped, statusChecked;

   private GlNames() {
   }

   /** Render thread, right after the swap: tops the pools up to full when they are under half. */
   public static void refill() {
      if (!ON) {
         return;
      }
      Thread t = Thread.currentThread();
      if (owner == null) {
         owner = t;
      } else if (owner != t) {
         return;
      }
      if (texN < textures.length / 2) {
         int[] fresh = new int[textures.length - texN];
         GL11.glGenTextures(fresh);
         System.arraycopy(fresh, 0, textures, texN, fresh.length);
         texN = textures.length;
         refills++;
      }
      if (fboN < framebuffers.length / 2) {
         int[] fresh = new int[framebuffers.length - fboN];
         GL30.glGenFramebuffers(fresh);
         System.arraycopy(fresh, 0, framebuffers, fboN, fresh.length);
         fboN = framebuffers.length;
         refills++;
      }
   }

   /** A texture name for TextureID.generateHwId. */
   public static int texture() {
      if (ON && texN > 0 && Thread.currentThread() == owner) {
         texServed++;
         return textures[--texN];
      }
      texFallback++;
      return GL11.glGenTextures();
   }

   /** A framebuffer name for TextureFBO (core GL 3.0 framebuffers only; {@code core} false = the EXT / ARB path). */
   public static int framebuffer(boolean core) {
      if (ON && core && fboN > 0 && Thread.currentThread() == owner) {
         fboServed++;
         return framebuffers[--fboN];
      }
      fboFallback++;
      return -1;
   }

   private static long shape(int w, int h, int depthW, int depthH, boolean depthTexture, boolean stencil) {
      return (long)(w & 0x7FFF) << 49 | (long)(h & 0x7FFF) << 34 | (long)(depthW & 0x7FFF) << 19 | (long)(depthH & 0x7FFF) << 4
         | (depthTexture ? 2 : 0) | (stencil ? 1 : 0);
   }

   /** Whether TextureFBO may skip glCheckFramebufferStatus: this attachment shape already came out complete. */
   public static boolean knownComplete(int w, int h, int depthW, int depthH, boolean depthTexture, boolean stencil) {
      if (!ON || Thread.currentThread() != owner || !completeShapes.contains(shape(w, h, depthW, depthH, depthTexture, stencil))) {
         statusChecked++;
         return false;
      }
      statusSkipped++;
      return true;
   }

   public static void complete(int w, int h, int depthW, int depthH, boolean depthTexture, boolean stencil) {
      if (ON && Thread.currentThread() == owner) {
         completeShapes.add(shape(w, h, depthW, depthH, depthTexture, stencil));
      }
   }

   public static String summary() {
      return " | glNames: textures " + texServed + " pooled / " + texFallback + " asked, framebuffers " + fboServed + " / " + fboFallback
         + ", refills " + refills + ", status checks skipped " + statusSkipped + " / done " + statusChecked;
   }
}
