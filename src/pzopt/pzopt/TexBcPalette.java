package pzopt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

/**
 * The running GPU's own BC3 decode, measured once: GPUs do not decode the interpolated BC3 entries with the textbook
 * weights (the flip's Radeon 890M decodes alpha 0..255 in the 6-level mode as 52 104 151 203, an RTX 4090 as 48 96 159
 * 207, the formula says 51 102 153 204), so an encoder that fits against the formula picks slightly wrong indices and
 * modes. One BC3 texture holds a block for every alpha endpoint pair (indices 0..7) and for every colour channel pair
 * (indices 0..3); it is drawn through texelFetch into an RGBA8 framebuffer and read back, giving TexBc the palette the
 * texture unit really produces (TexBc.setDecoder). GL 3.3 (no compute), render thread, ~10 ms once.
 *
 * Layout: 1024 x 1152 texels = 256 x 288 blocks. Rows 0..255: alpha block (x, y) has a0 = x, a1 = y. Rows 256..271:
 * green pairs (64 x 64 over 256 x 16 blocks: g0 = i / 64, g1 = i % 64) with r0 = 31 > r1 = 0 so the block is in the
 * 4-colour order; rows 272..275: blue pairs (32 x 32), same red; rows 276..279: red pairs r0 > r1 (and r0 == r1 with
 * g0 = 63 > g1 = 0); rows 280..287 unused.
 */
public final class TexBcPalette {
   static final int BW = 256, BH = 288, W = BW * 4, H = BH * 4;

   /** Why the last measure() kept the formula (the caller logs it). */
   public static String error;

   private TexBcPalette() {
   }

   /** Measures and installs the decoder model; false (the formula stays) on any failure. */
   public static boolean measure() {
      ByteBuffer blocks = MemoryUtil.memAlloc(BW * BH * 16).order(ByteOrder.LITTLE_ENDIAN);
      int tex = 0, rt = 0, fbo = 0, prog = 0, vao = 0;
      try {
         for (int by = 0; by < BH; by++) {
            for (int bx = 0; bx < BW; bx++) {
               int o = (by * BW + bx) * 16;
               long alpha = 0L, color = 0L;
               if (by < 256) {
                  long bits = 0;
                  for (int i = 0; i < 16; i++) {
                     bits |= (long) (i & 7) << (3 * i);
                  }
                  alpha = bx | (long) by << 8 | bits << 16;
               } else {
                  int c0, c1;
                  int i = (by - 256) * BW + bx;
                  if (by < 272) { // green pairs
                     c0 = (31 << 11) | ((i / 64) << 5);
                     c1 = (i % 64) << 5;
                  } else if (by < 276) { // blue pairs
                     int j = i - 16 * BW;
                     c0 = (31 << 11) | (j / 32);
                     c1 = j % 32;
                  } else if (by < 280) { // red pairs r0 >= r1
                     int j = i - 20 * BW;
                     int r0 = j / 32, r1 = j % 32;
                     c0 = (r0 << 11) | (63 << 5);
                     c1 = r1 << 11;
                  } else {
                     c0 = c1 = 0;
                  }
                  long idx = 0;
                  for (int k = 0; k < 16; k++) {
                     idx |= (long) (k & 3) << (2 * k);
                  }
                  color = (c0 & 0xFFFFL) | (long) (c1 & 0xFFFF) << 16 | idx << 32;
               }
               blocks.putLong(o, alpha);
               blocks.putLong(o + 8, color);
            }
         }
         // everything this touches is restored: it runs inside TextureID.generateHwId, in the middle of a frame
         int prevDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
         int prevRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
         int prevProg = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
         int prevUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
         GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
         int prevTex = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
         int prevVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
         int prevPack = GL11C.glGetInteger(GL11C.GL_PACK_ALIGNMENT);
         int[] vp = new int[4];
         GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, vp);
         tex = GL11C.glGenTextures();
         GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);
         GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
         GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
         GL13C.glCompressedTexImage2D(GL11C.GL_TEXTURE_2D, 0, TexBc.GL_COMPRESSED_RGBA_S3TC_DXT5_EXT, W, H, 0, blocks);
         rt = GL11C.glGenTextures();
         GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, rt);
         GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, W, H, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
         fbo = GL30C.glGenFramebuffers();
         GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
         GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, rt, 0);
         prog = program();
         vao = GL30C.glGenVertexArrays();
         boolean blend = GL11C.glIsEnabled(GL11C.GL_BLEND), depth = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST), scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
         GL11C.glDisable(GL11C.GL_BLEND);
         GL11C.glDisable(GL11C.GL_DEPTH_TEST);
         GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
         GL11C.glViewport(0, 0, W, H);
         GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);
         GL20C.glUseProgram(prog);
         GL30C.glBindVertexArray(vao);
         GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
         GL30C.glBindVertexArray(prevVao);
         ByteBuffer px = MemoryUtil.memAlloc(W * H * 4);
         GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1);
         GL11C.glReadPixels(0, 0, W, H, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, px);
         if (blend) {
            GL11C.glEnable(GL11C.GL_BLEND);
         }
         if (depth) {
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);
         }
         if (scissor) {
            GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
         }
         GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, prevPack);
         GL11C.glViewport(vp[0], vp[1], vp[2], vp[3]);
         GL20C.glUseProgram(prevProg);
         GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTex);
         GL13C.glActiveTexture(prevUnit);
         GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, prevDraw);
         GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, prevRead);
         boolean ok = install(px);
         MemoryUtil.memFree(px);
         return ok;
      } catch (Throwable t) {
         error = "measurement failed (" + t + ")";
         return false;
      } finally {
         MemoryUtil.memFree(blocks);
         if (fbo != 0) {
            GL30C.glDeleteFramebuffers(fbo);
         }
         if (rt != 0) {
            GL11C.glDeleteTextures(rt);
         }
         if (tex != 0) {
            GL11C.glDeleteTextures(tex);
         }
         if (prog != 0) {
            GL20C.glDeleteProgram(prog);
         }
         if (vao != 0) {
            GL30C.glDeleteVertexArrays(vao);
         }
      }
   }

   /** Texel (x, y) of the readback: RGBA bytes. */
   private static int at(ByteBuffer px, int x, int y, int c) {
      return px.get((y * W + x) * 4 + c) & 0xFF;
   }

   /** Builds the tables from the readback; sanity-checks the endpoints (indices 0 and 1 must decode to them). */
   static boolean install(ByteBuffer px) {
      byte[] alpha = new byte[65536 * 8];
      for (int a1 = 0; a1 < 256; a1++) {
         for (int a0 = 0; a0 < 256; a0++) {
            for (int k = 0; k < 8; k++) {
               // pixel k of the block (row 0: k 0..3, row 1: k 4..7)
               int v = at(px, a0 * 4 + (k & 3), a1 * 4 + (k >> 2), 3);
               alpha[((a0 << 8) | a1) * 8 + k] = (byte) v;
            }
            if ((alpha[((a0 << 8) | a1) * 8] & 0xFF) != a0 || (alpha[((a0 << 8) | a1) * 8 + 1] & 0xFF) != a1) {
               error = "the readback does not decode its endpoints (" + a0 + "," + a1 + ")";
               return false;
            }
         }
      }
      // colour: palette entries 2 and 3 of each channel pair (the endpoints are the standard bit expansion)
      short[] g = new short[64 * 64], b = new short[32 * 32], r = new short[32 * 32];
      for (int i = 0; i < 64 * 64; i++) {
         int bx = i % BW, by = 256 + i / BW;
         g[i] = (short) (at(px, bx * 4 + 2, by * 4, 1) << 8 | at(px, bx * 4 + 3, by * 4, 1));
      }
      for (int j = 0; j < 32 * 32; j++) {
         int i = j + 16 * BW;
         int bx = i % BW, by = 256 + i / BW;
         b[j] = (short) (at(px, bx * 4 + 2, by * 4, 2) << 8 | at(px, bx * 4 + 3, by * 4, 2));
      }
      for (int j = 0; j < 32 * 32; j++) {
         int i = j + 20 * BW;
         int bx = i % BW, by = 256 + i / BW;
         int r0 = j / 32, r1 = j % 32;
         if (r0 >= r1) {
            r[j] = (short) (at(px, bx * 4 + 2, by * 4, 0) << 8 | at(px, bx * 4 + 3, by * 4, 0));
         } else {
            // never emitted (c0 > c1 puts the larger red in c0): the formula
            int e0 = TexBc.ex5(r0), e1 = TexBc.ex5(r1);
            r[j] = (short) (((2 * e0 + e1) / 3) << 8 | ((e0 + 2 * e1) / 3));
         }
      }
      TexBc.setDecoder(alpha, r, g, b);
      return true;
   }

   private static int program() {
      int vs = shader(GL20C.GL_VERTEX_SHADER, "#version 330\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0.0,1.0);}");
      int fs = shader(GL20C.GL_FRAGMENT_SHADER, "#version 330\nuniform sampler2D t;out vec4 o;void main(){o=texelFetch(t,ivec2(gl_FragCoord.xy),0);}");
      int p = GL20C.glCreateProgram();
      GL20C.glAttachShader(p, vs);
      GL20C.glAttachShader(p, fs);
      GL20C.glLinkProgram(p);
      GL20C.glDeleteShader(vs);
      GL20C.glDeleteShader(fs);
      if (GL20C.glGetProgrami(p, GL20C.GL_LINK_STATUS) == 0) {
         throw new IllegalStateException("link: " + GL20C.glGetProgramInfoLog(p));
      }
      return p;
   }

   private static int shader(int type, String src) {
      int s = GL20C.glCreateShader(type);
      GL20C.glShaderSource(s, src);
      GL20C.glCompileShader(s);
      if (GL20C.glGetShaderi(s, GL20C.GL_COMPILE_STATUS) == 0) {
         throw new IllegalStateException(GL20C.glGetShaderInfoLog(s));
      }
      return s;
   }
}
