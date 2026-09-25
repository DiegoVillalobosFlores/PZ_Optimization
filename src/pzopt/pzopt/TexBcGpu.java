package pzopt;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

/**
 * BC3 (DXT5) encoding on the GPU for textureCompression: the RGBA8 mip chain goes into a shader storage buffer, a
 * compute shader encodes one 4x4 block per invocation (TexBc's fits, FAST or HQ, ported line for line), and each level is created with glCompressedTexImage2D reading the
 * blocks from that buffer bound as the pixel-unpack buffer, so the blocks never come back to the CPU. The render
 * thread pays the RGBA copy into the buffer (the same bytes an uncompressed upload copies) plus a few GL calls.
 *
 * GL 4.3 (compute, SSBOs); init() says whether it is usable. Render thread only. No game classes (the probe links it).
 */
public final class TexBcGpu {
   private static final String SRC = String.join("\n",
      "#version 430",
      "layout(local_size_x = 64) in;",
      "layout(std430, binding = 0) readonly buffer Src { uint px[]; };",
      "layout(std430, binding = 1) writeonly buffer Dst { uvec4 blk[]; };",
      "layout(std430, binding = 2) readonly buffer Tab { int om5[512]; int om6[512]; };",
      "layout(std430, binding = 3) readonly buffer Lut { uint alut[131072]; uint clut[6144]; };",
      "uniform int uLut;",
      "uniform int uAlphaSearchErr;",
      "uniform int uSearch8;",
      "uniform uint uSrcBase;",
      "uniform uint uDstBase;",
      "uniform ivec2 uSize;",
      "uniform int uHq;",
      "uniform int uPremul;",
      "int R[16]; int G[16]; int B[16]; int A[16];",
      "int ex5(int v) { return (v << 3) | (v >> 2); }",
      "int ex6(int v) { return (v << 2) | (v >> 4); }",
      "int mul8(int a, int b) { int t = a * b + 128; return (t + (t >> 8)) >> 8; }",
      "int to565(int r, int g, int b) { return (mul8(r, 31) << 11) | (mul8(g, 63) << 5) | mul8(b, 31); }",
      "ivec3 ex565(int c) { return ivec3(ex5(c >> 11), ex6((c >> 5) & 63), ex5(c & 31)); }",
      "void mids(int c0, int c1, ivec3 p0, ivec3 p1, out ivec3 p2, out ivec3 p3) {",
      "  if (uLut != 0) {",
      "    uint mr = clut[(c0 >> 11) * 32 + (c1 >> 11)], mg = clut[1024 + ((c0 >> 5) & 63) * 64 + ((c1 >> 5) & 63)], mb = clut[5120 + (c0 & 31) * 32 + (c1 & 31)];",
      "    p2 = ivec3(int(mr >> 8), int(mg >> 8), int(mb >> 8)); p3 = ivec3(int(mr & 255u), int(mg & 255u), int(mb & 255u));",
      "  } else { p2 = (2 * p0 + p1) / 3; p3 = (p0 + 2 * p1) / 3; }",
      "}",
      "uint indices(int c0, int c1) {",
      "  ivec3 p0 = ex565(c0), p1 = ex565(c1), p2, p3;",
      "  mids(c0, c1, p0, p1, p2, p3);",
      "  uint bits = 0u;",
      "  for (int i = 0; i < 16; i++) {",
      "    ivec3 c = ivec3(R[i], G[i], B[i]);",
      "    ivec3 d0 = c - p0, d1 = c - p1, d2 = c - p2, d3 = c - p3;",
      "    int e0 = d0.x*d0.x + d0.y*d0.y + d0.z*d0.z, e1 = d1.x*d1.x + d1.y*d1.y + d1.z*d1.z;",
      "    int e2 = d2.x*d2.x + d2.y*d2.y + d2.z*d2.z, e3 = d3.x*d3.x + d3.y*d3.y + d3.z*d3.z;",
      "    uint k = 0u; int be = e0;",
      "    if (e1 < be) { be = e1; k = 1u; }",
      "    if (e2 < be) { be = e2; k = 2u; }",
      "    if (e3 < be) { be = e3; k = 3u; }",
      "    bits |= k << (2 * i);",
      "  }",
      "  return bits;",
      "}",
      "int q(float v, int mx) { return clamp(int(round(v * float(mx) / 255.0)), 0, mx); }",
      "bool refine(uint bits, out int c0, out int c1) {",
      "  int aa = 0, ab = 0, bb = 0; ivec3 ac = ivec3(0), bc = ivec3(0);",
      "  for (int i = 0; i < 16; i++) {",
      "    uint k = (bits >> (2 * i)) & 3u;",
      "    int w0 = k == 0u ? 3 : k == 1u ? 0 : k == 2u ? 2 : 1; int w1 = 3 - w0;",
      "    aa += w0 * w0; ab += w0 * w1; bb += w1 * w1;",
      "    ivec3 c = ivec3(R[i], G[i], B[i]); ac += w0 * c; bc += w1 * c;",
      "  }",
      "  float det = float(aa) * float(bb) - float(ab) * float(ab);",
      "  if (abs(det) < 1e-6) { c0 = 0; c1 = 0; return false; }",
      "  float f = 3.0 / det;",
      "  vec3 e0 = (vec3(ac) * float(bb) - vec3(bc) * float(ab)) * f;",
      "  vec3 e1 = (vec3(bc) * float(aa) - vec3(ac) * float(ab)) * f;",
      "  c0 = (q(e0.x, 31) << 11) | (q(e0.y, 63) << 5) | q(e0.z, 31);",
      "  c1 = (q(e1.x, 31) << 11) | (q(e1.y, 63) << 5) | q(e1.z, 31);",
      "  return true;",
      "}",
      "uvec2 pack(int c0, int c1, uint bits) {",
      "  if (c0 < c1) { int t = c0; c0 = c1; c1 = t; bits ^= 0x55555555u; }",
      "  else if (c0 == c1) { bits = 0u; }",
      "  return uvec2(uint(c0) | (uint(c1) << 16), bits);",
      "}",
      "uvec2 colorFast() {",
      "  ivec3 mn = ivec3(255), mx = ivec3(0);",
      "  for (int i = 0; i < 16; i++) { ivec3 c = ivec3(R[i], G[i], B[i]); mn = min(mn, c); mx = max(mx, c); }",
      "  ivec3 ins = (mx - mn) >> 4;",
      "  int c0 = to565(mx.x - ins.x, mx.y - ins.y, mx.z - ins.z);",
      "  int c1 = to565(mn.x + ins.x, mn.y + ins.y, mn.z + ins.z);",
      "  return pack(c0, c1, c0 == c1 ? 0u : indices(c0, c1));",
      "}",
      "uvec2 colorHq() {",
      "  bool solid = true;",
      "  for (int i = 1; i < 16; i++) solid = solid && R[i] == R[0] && G[i] == G[0] && B[i] == B[0];",
      "  if (solid) {",
      "    int c0 = (om5[R[0] * 2] << 11) | (om6[G[0] * 2] << 5) | om5[B[0] * 2];",
      "    int c1 = (om5[R[0] * 2 + 1] << 11) | (om6[G[0] * 2 + 1] << 5) | om5[B[0] * 2 + 1];",
      "    return pack(c0, c1, c0 == c1 ? 0u : 0xAAAAAAAAu);",
      "  }",
      "  ivec3 sum = ivec3(0), mn = ivec3(255), mx = ivec3(0);",
      "  for (int i = 0; i < 16; i++) { ivec3 c = ivec3(R[i], G[i], B[i]); sum += c; mn = min(mn, c); mx = max(mx, c); }",
      "  ivec3 m = (sum + 8) >> 4;",
      "  float c00 = 0.0, c01 = 0.0, c02 = 0.0, c11 = 0.0, c12 = 0.0, c22 = 0.0;",
      "  for (int i = 0; i < 16; i++) {",
      "    vec3 d = vec3(ivec3(R[i], G[i], B[i]) - m);",
      "    c00 += d.x * d.x; c01 += d.x * d.y; c02 += d.x * d.z; c11 += d.y * d.y; c12 += d.y * d.z; c22 += d.z * d.z;",
      "  }",
      "  vec3 v = vec3(mx - mn);",
      "  for (int it = 0; it < 4; it++) {",
      "    v = vec3(v.x * c00 + v.y * c01 + v.z * c02, v.x * c01 + v.y * c11 + v.z * c12, v.x * c02 + v.y * c12 + v.z * c22);",
      "    float s = max(abs(v.x), max(abs(v.y), abs(v.z)));",
      "    if (s > 0.0) v /= s;",
      "  }",
      "  if (abs(v.x) + abs(v.y) + abs(v.z) < 1e-6) v = vec3(0.299, 0.587, 0.114);",
      "  int mini = 0, maxi = 0; float mind = 3.4e38, maxd = -3.4e38;",
      "  for (int i = 0; i < 16; i++) {",
      "    float d = dot(vec3(R[i], G[i], B[i]), v);",
      "    if (d < mind) { mind = d; mini = i; }",
      "    if (d > maxd) { maxd = d; maxi = i; }",
      "  }",
      "  int c0 = to565(R[maxi], G[maxi], B[maxi]);",
      "  int c1 = to565(R[mini], G[mini], B[mini]);",
      "  uint bits = indices(c0, c1);",
      "  for (int pass = 0; pass < 2; pass++) {",
      "    int n0, n1;",
      "    if (!refine(bits, n0, n1)) break;",
      "    if (n0 == c0 && n1 == c1) break;",
      "    c0 = n0; c1 = n1; bits = indices(c0, c1);",
      "  }",
      "  return pack(c0, c1, bits);",
      "}",
      "uvec2 alphaBlock() {",
      "  int mn = 255, mx = 0;",
      "  for (int i = 0; i < 16; i++) { mn = min(mn, A[i]); mx = max(mx, A[i]); }",
      "  if (mn == mx) return uvec2(uint(mx) | (uint(mx) << 8), 0u);",
      "  int range = mx - mn; uint lo = 0u, hi = 0u;",
      "  for (int i = 0; i < 16; i++) {",
      "    int t = ((mx - A[i]) * 14 + range) / (2 * range);",
      "    uint idx = uint(t == 0 ? 0 : t == 7 ? 1 : t + 1);",
      "    int s = 3 * i;",
      "    if (s + 3 <= 32) lo |= idx << s;",
      "    else if (s >= 32) hi |= idx << (s - 32);",
      "    else { lo |= idx << s; hi |= idx >> (32 - s); }",
      "  }",
      "  return uvec2(uint(mx) | (uint(mn) << 8) | (lo << 16), (lo >> 16) | (hi << 16));",
      "}",
      "int aval(int a0, int a1, int k) {",
      "  if (uLut != 0) { uint w = alut[((a0 << 8) | a1) * 2 + (k >> 2)]; return int((w >> ((k & 3) * 8)) & 255u); }",
      "  if (k == 0) return a0;",
      "  if (k == 1) return a1;",
      "  if (a0 > a1) return ((8 - k) * a0 + (k - 1) * a1 + 3) / 7;",
      "  if (k == 6) return 0;",
      "  if (k == 7) return 255;",
      "  return ((6 - k) * a0 + (k - 1) * a1 + 2) / 5;",
      "}",
      "uint aidx(uint lo, uint hi, int i) {",
      "  int s = 3 * i;",
      "  if (s + 3 <= 32) return (lo >> s) & 7u;",
      "  if (s >= 32) return (hi >> (s - 32)) & 7u;",
      "  return ((lo >> s) | (hi << (32 - s))) & 7u;",
      "}",
      "int afit(int a0, int a1, out uint lo, out uint hi) {",
      "  int pal[8];",
      "  for (int k = 0; k < 8; k++) pal[k] = aval(a0, a1, k);",
      "  lo = 0u; hi = 0u; int err = 0;",
      "  for (int i = 0; i < 16; i++) {",
      "    int best = 0, bd = 1 << 30;",
      "    for (int k = 0; k < 8; k++) { int d = abs(A[i] - pal[k]); if (d < bd) { bd = d; best = k; } }",
      "    err += bd * bd;",
      "    uint idx = uint(best); int s = 3 * i;",
      "    if (s + 3 <= 32) lo |= idx << s;",
      "    else if (s >= 32) hi |= idx << (s - 32);",
      "    else { lo |= idx << s; hi |= idx >> (32 - s); }",
      "  }",
      "  return err;",
      "}",
      "uvec2 alphaHq() {",
      "  int mn = 255, mx = 0, imn = 255, imx = 0;",
      "  for (int i = 0; i < 16; i++) {",
      "    mn = min(mn, A[i]); mx = max(mx, A[i]);",
      "    if (A[i] > 0 && A[i] < 255) { imn = min(imn, A[i]); imx = max(imx, A[i]); }",
      "  }",
      "  if (mn == mx) return uvec2(uint(mx) | (uint(mx) << 8), 0u);",
      "  uint lo, hi; int b0 = mx, b1 = mn;",
      "  int err = afit(mx, mn, lo, hi); uint blo = lo, bhi = hi;",
      "  for (int pass = 0; pass < 2 && err > 0; pass++) {",
      "    float saa = 0.0, sab = 0.0, sbb = 0.0, sac = 0.0, sbc = 0.0;",
      "    for (int i = 0; i < 16; i++) {",
      "      uint k = aidx(blo, bhi, i);",
      "      int s = k == 0u ? 0 : k == 1u ? 7 : int(k) - 1;",
      "      float w0 = float(7 - s), w1 = float(s);",
      "      saa += w0 * w0; sab += w0 * w1; sbb += w1 * w1;",
      "      sac += w0 * float(A[i]) * 7.0; sbc += w1 * float(A[i]) * 7.0;",
      "    }",
      "    float det = saa * sbb - sab * sab;",
      "    if (abs(det) < 1e-9) break;",
      "    int n0 = clamp(int(round((sac * sbb - sbc * sab) / det)), 0, 255);",
      "    int n1 = clamp(int(round((sbc * saa - sac * sab) / det)), 0, 255);",
      "    if (n0 <= n1) break;",
      "    int e = afit(n0, n1, lo, hi);",
      "    if (e >= err) break;",
      "    err = e; b0 = n0; b1 = n1; blo = lo; bhi = hi;",
      "  }",
      "  if (imn <= imx) {",
      "    int e = afit(imn, imx, lo, hi);",
      "    if (e < err) { err = e; b0 = imn; b1 = imx; blo = lo; bhi = hi; }",
      "  } else if (err > 0) {",
      "    int e = afit(0, 255, lo, hi);",
      "    if (e < err) { err = e; b0 = 0; b1 = 255; blo = lo; bhi = hi; }",
      "  }",
      "  if (err > uAlphaSearchErr) {",
      "    int v[16]; int cnt[16]; int nv = 0;",
      "    for (int i = 0; i < 16; i++) {",
      "      int j = 0;",
      "      while (j < nv && v[j] != A[i]) j++;",
      "      if (j == nv) { v[nv] = A[i]; cnt[nv] = 0; nv++; }",
      "      cnt[j]++;",
      "    }",
      "    int bestLo = -1, bestHi = -1; int pal[8];",
      "    for (int i = 0; i < nv; i++) {",
      "      for (int j = 0; j < nv; j++) {",
      "        if (v[i] >= v[j]) continue;",
      "        for (int mode = uSearch8 != 0 ? 0 : 1; mode < 2; mode++) {",
      "          int a0 = mode == 0 ? v[j] : v[i], a1 = mode == 0 ? v[i] : v[j];",
      "          for (int k = 0; k < 8; k++) pal[k] = aval(a0, a1, k);",
      "          int e = 0;",
      "          for (int q = 0; q < nv && e < err; q++) {",
      "            int bd = 1 << 30;",
      "            for (int k = 0; k < 8; k++) { int d = v[q] - pal[k]; bd = min(bd, d * d); }",
      "            e += bd * cnt[q];",
      "          }",
      "          if (e < err) { err = e; bestLo = a0; bestHi = a1; }",
      "        }",
      "      }",
      "    }",
      "    if (bestLo >= 0) { afit(bestLo, bestHi, lo, hi); b0 = bestLo; b1 = bestHi; blo = lo; bhi = hi; }",
      "  }",
      "  return uvec2(uint(b0) | (uint(b1) << 8) | (blo << 16), (blo >> 16) | (bhi << 16));",
      "}",
      "void main() {",
      "  uint bw = uint(uSize.x + 3) >> 2, bh = uint(uSize.y + 3) >> 2;",
      "  uint id = gl_GlobalInvocationID.x;",
      "  if (id >= bw * bh) return;",
      "  int bx = int(id % bw), by = int(id / bw);",
      "  for (int y = 0; y < 4; y++) {",
      "    int sy = min(by * 4 + y, uSize.y - 1);",
      "    for (int x = 0; x < 4; x++) {",
      "      int sx = min(bx * 4 + x, uSize.x - 1);",
      "      uint p = px[uSrcBase + uint(sy * uSize.x + sx)];",
      "      int i = y * 4 + x;",
      "      R[i] = int(p & 255u); G[i] = int((p >> 8) & 255u); B[i] = int((p >> 16) & 255u); A[i] = int(p >> 24);",
      "      if (uPremul != 0) { R[i] = R[i] * A[i] / 255; G[i] = G[i] * A[i] / 255; B[i] = B[i] * A[i] / 255; }",
      "    }",
      "  }",
      "  uvec2 al = uHq != 0 ? alphaHq() : alphaBlock();",
      "  uvec2 co = uHq != 0 ? colorHq() : colorFast();",
      "  blk[uDstBase + id] = uvec4(al, co);",
      "}");

   /**
    * ImageData's mip filter on the GPU, one child texel per invocation, integer for integer: levels 1-2 average the
    * 2x2 parent texels with alpha > 0 (the first one also when preserveTransparentColor), deeper levels average all
    * four; parents are unpremultiplied, the encoder premultiplies (uPremul) as getMipMapData does on the CPU.
    */
   private static final String MIP_SRC = String.join("\n",
      "#version 430",
      "layout(local_size_x = 64) in;",
      "layout(std430, binding = 0) buffer Px { uint px[]; };",
      "uniform uint uParent;",
      "uniform uint uChild;",
      "uniform ivec2 uPSize;",
      "uniform ivec2 uCSize;",
      "uniform int uMaxAlpha;",
      "uniform int uPreserve;",
      "uvec4 at(int x, int y) { uint p = px[uParent + uint(y * uPSize.x + x)]; return uvec4(p & 255u, (p >> 8) & 255u, (p >> 16) & 255u, p >> 24); }",
      "void main() {",
      "  uint id = gl_GlobalInvocationID.x;",
      "  if (id >= uint(uCSize.x * uCSize.y)) return;",
      "  int x = int(id % uint(uCSize.x)), y = int(id / uint(uCSize.x));",
      "  int py0 = min(y * 2, uPSize.y - 1), py1 = y * 2 + 1, px0 = min(x * 2, uPSize.x - 1), px1 = x * 2 + 1;",
      "  bool row1 = py1 < uPSize.y, col1 = px1 < uPSize.x;",
      "  uvec4 s = uvec4(0u); uint n = 0u;",
      "  uvec4 c = at(px0, py0);",
      "  if (uMaxAlpha == 0 || uPreserve != 0 || c.a > 0u) { s += c; n++; }",
      "  if (col1) { c = at(px1, py0); if (uMaxAlpha == 0 || c.a > 0u) { s += c; n++; } }",
      "  if (row1) {",
      "    c = at(px0, py1); if (uMaxAlpha == 0 || c.a > 0u) { s += c; n++; }",
      "    if (col1) { c = at(px1, py1); if (uMaxAlpha == 0 || c.a > 0u) { s += c; n++; } }",
      "  }",
      "  if (n > 0u) s /= n;",
      "  px[uChild + id] = s.r | (s.g << 8) | (s.b << 16) | (s.a << 24);",
      "}");

   private static int lutBuf, uLut, uAlphaSearchErr, uSearch8;
   private static boolean lutSet;
   private static int mipProgram, uParent, uChild, uPSize, uCSize, uMaxAlpha, uPreserve, uPremul;
   private static int program, tabBuf, srcBuf, dstBuf, srcCap, dstCap;
   private static int uSrcBase, uDstBase, uSize, uHq;
   private static boolean tried, ok;
   public static String error;

   private TexBcGpu() {
   }

   private static int compile(String src) {
      int sh = GL20C.glCreateShader(GL43C.GL_COMPUTE_SHADER);
      GL20C.glShaderSource(sh, src);
      GL20C.glCompileShader(sh);
      if (GL20C.glGetShaderi(sh, GL20C.GL_COMPILE_STATUS) == 0) {
         error = "compile: " + GL20C.glGetShaderInfoLog(sh);
         GL20C.glDeleteShader(sh);
         return 0;
      }
      int prog = GL20C.glCreateProgram();
      GL20C.glAttachShader(prog, sh);
      GL20C.glLinkProgram(prog);
      GL20C.glDeleteShader(sh);
      if (GL20C.glGetProgrami(prog, GL20C.GL_LINK_STATUS) == 0) {
         error = "link: " + GL20C.glGetProgramInfoLog(prog);
         return 0;
      }
      return prog;
   }

   /** Compiles the shader once; false (with error set) when compute is unavailable or the compile fails. */
   public static boolean init() {
      if (tried) {
         return ok;
      }
      tried = true;
      try {
         if (!org.lwjgl.opengl.GL.getCapabilities().OpenGL43) {
            error = "no GL 4.3";
            return false;
         }
         int sh = GL20C.glCreateShader(GL43C.GL_COMPUTE_SHADER);
         GL20C.glShaderSource(sh, SRC);
         GL20C.glCompileShader(sh);
         if (GL20C.glGetShaderi(sh, GL20C.GL_COMPILE_STATUS) == 0) {
            error = "compile: " + GL20C.glGetShaderInfoLog(sh);
            GL20C.glDeleteShader(sh);
            return false;
         }
         program = GL20C.glCreateProgram();
         GL20C.glAttachShader(program, sh);
         GL20C.glLinkProgram(program);
         GL20C.glDeleteShader(sh);
         if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
            error = "link: " + GL20C.glGetProgramInfoLog(program);
            return false;
         }
         uSrcBase = GL20C.glGetUniformLocation(program, "uSrcBase");
         uDstBase = GL20C.glGetUniformLocation(program, "uDstBase");
         uSize = GL20C.glGetUniformLocation(program, "uSize");
         uHq = GL20C.glGetUniformLocation(program, "uHq");
         uPremul = GL20C.glGetUniformLocation(program, "uPremul");
         uLut = GL20C.glGetUniformLocation(program, "uLut");
         uAlphaSearchErr = GL20C.glGetUniformLocation(program, "uAlphaSearchErr");
         uSearch8 = GL20C.glGetUniformLocation(program, "uSearch8");
         lutBuf = GL15C.glGenBuffers();
         GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, lutBuf);
         GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, (131072L + 6144L) * 4, GL15C.GL_STATIC_DRAW); // zeros until setLut
         GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
         mipProgram = compile(MIP_SRC);
         if (mipProgram == 0) {
            return false;
         }
         uParent = GL20C.glGetUniformLocation(mipProgram, "uParent");
         uChild = GL20C.glGetUniformLocation(mipProgram, "uChild");
         uPSize = GL20C.glGetUniformLocation(mipProgram, "uPSize");
         uCSize = GL20C.glGetUniformLocation(mipProgram, "uCSize");
         uMaxAlpha = GL20C.glGetUniformLocation(mipProgram, "uMaxAlpha");
         uPreserve = GL20C.glGetUniformLocation(mipProgram, "uPreserve");
         IntBuffer tab = MemoryUtil.memAllocInt(1024);
         tab.put(TexBc.OMATCH5).put(TexBc.OMATCH6).flip();
         tabBuf = GL15C.glGenBuffers();
         GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, tabBuf);
         GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, tab, GL15C.GL_STATIC_DRAW);
         GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
         MemoryUtil.memFree(tab);
         srcBuf = GL15C.glGenBuffers();
         dstBuf = GL15C.glGenBuffers();
         ok = true;
      } catch (Throwable t) {
         error = String.valueOf(t);
      }
      return ok;
   }

   // --- zero-copy staging: one persistently mapped, coherent storage buffer the workers write the levels into ------

   private static int stageBuf, stageSize;
   private static long stageAddr;
   /** Free ranges of the staging buffer, offset -> length (first fit, merged on release). */
   private static final java.util.TreeMap<Integer, Integer> free = new java.util.TreeMap<>();
   /** In-flight ranges: GPU fence, offset, length (render thread only). */
   private static final java.util.ArrayDeque<long[]> inFlight = new java.util.ArrayDeque<>();
   private static volatile boolean staging;

   /** Render thread: maps the staging buffer (GL 4.4 / ARB_buffer_storage); false leaves the copy path. */
   /** Render thread: the measured decode (TexBcPalette) for the shader's error model. */
   public static void setLut() {
      if (!ok || !TexBc.measuredDecoder()) {
         return;
      }
      IntBuffer b = MemoryUtil.memAllocInt(131072 + 6144);
      byte[] a = TexBc.ALPHA_LUT;
      for (int i = 0; i < 131072; i++) {
         int o = i * 4;
         b.put((a[o] & 0xFF) | (a[o + 1] & 0xFF) << 8 | (a[o + 2] & 0xFF) << 16 | (a[o + 3] & 0xFF) << 24);
      }
      for (short v : TexBc.R_LUT) {
         b.put(v & 0xFFFF);
      }
      for (short v : TexBc.G_LUT) {
         b.put(v & 0xFFFF);
      }
      for (short v : TexBc.B_LUT) {
         b.put(v & 0xFFFF);
      }
      b.flip();
      GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, lutBuf);
      GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, b);
      GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
      MemoryUtil.memFree(b);
      lutSet = true;
   }

   public static boolean initStaging(int bytes) {
      org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
      if (!ok || !(caps.OpenGL44 || caps.GL_ARB_buffer_storage)) {
         return false;
      }
      int flags = org.lwjgl.opengl.GL44C.GL_MAP_WRITE_BIT | org.lwjgl.opengl.GL44C.GL_MAP_PERSISTENT_BIT | org.lwjgl.opengl.GL44C.GL_MAP_COHERENT_BIT;
      stageBuf = GL15C.glGenBuffers();
      GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, stageBuf);
      org.lwjgl.opengl.GL44C.glBufferStorage(GL43C.GL_SHADER_STORAGE_BUFFER, bytes, flags);
      stageAddr = org.lwjgl.opengl.GL30C.nglMapBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, bytes, flags);
      GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
      if (stageAddr == 0L) {
         GL15C.glDeleteBuffers(stageBuf);
         return false;
      }
      stageSize = bytes;
      synchronized (free) {
         free.put(0, bytes);
      }
      staging = true;
      return true;
   }

   public static boolean staging() {
      return staging;
   }

   /** Any thread: reserves len bytes (256-aligned) of the staging buffer; -1 when none is free. */
   public static int reserve(int len) {
      if (!staging) {
         return -1;
      }
      int need = (len + 255) & ~255;
      synchronized (free) {
         for (java.util.Map.Entry<Integer, Integer> e : free.entrySet()) {
            if (e.getValue() >= need) {
               int off = e.getKey(), rest = e.getValue() - need;
               free.remove(off);
               if (rest > 0) {
                  free.put(off + need, rest);
               }
               return off;
            }
         }
      }
      return -1;
   }

   /** Any thread: returns a range nothing on the GPU reads (never dispatched, or its fence signalled). */
   public static void release(int off, int len) {
      int need = (len + 255) & ~255;
      synchronized (free) {
         int start = off, size = need;
         java.util.Map.Entry<Integer, Integer> lo = free.floorEntry(off);
         if (lo != null && lo.getKey() + lo.getValue() == off) {
            start = lo.getKey();
            size += lo.getValue();
            free.remove(lo.getKey());
         }
         Integer hi = free.get(off + need);
         if (hi != null) {
            free.remove(off + need);
            size += hi;
         }
         free.put(start, size);
      }
   }

   /** Address of a staging offset, for the worker's copy. */
   public static long address(int off) {
      return stageAddr + off;
   }

   /** Render thread: releases every in-flight range whose fence has signalled. */
   public static void pollFences() {
      while (!inFlight.isEmpty()) {
         long[] f = inFlight.peekFirst();
         int st = org.lwjgl.opengl.GL32C.glClientWaitSync(f[0], 0, 0L);
         if (st != org.lwjgl.opengl.GL32C.GL_ALREADY_SIGNALED && st != org.lwjgl.opengl.GL32C.GL_CONDITION_SATISFIED) {
            return;
         }
         inFlight.pollFirst();
         org.lwjgl.opengl.GL32C.glDeleteSync(f[0]);
         release((int) f[1], (int) f[2]);
      }
   }

   /**
    * Creates the levels of the bound GL_TEXTURE_2D as BC3 from RGBA8 levels a worker wrote into the staging buffer at
    * off (consecutive, tightly packed); the range is released once the GPU has read it.
    */
   public static void uploadStaged(int off, int len, int[] widths, int[] heights, int firstLevel, boolean hq) {
      uploadStaged(off, len, widths, heights, firstLevel, hq, false, false, false);
   }

   /**
    * As above; gpuMips: only level 0 is staged (unpremultiplied), the GPU builds the other levels in place with
    * ImageData's filter first; premul: every level premultiplied as it is encoded.
    */
   public static void uploadStaged(int off, int len, int[] widths, int[] heights, int firstLevel, boolean hq, boolean gpuMips, boolean premul, boolean preserveTransparent) {
      if (gpuMips) {
         mips(stageBuf, off, widths, heights, preserveTransparent);
      }
      encode(stageBuf, off, widths, heights, firstLevel, hq, premul);
      long fence = org.lwjgl.opengl.GL32C.glFenceSync(org.lwjgl.opengl.GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
      inFlight.addLast(new long[] {fence, off, len});
   }

   /** Levels 1.. of a consecutive chain at byte offset base of buf, each from its parent (ImageData's filter). */
   public static void mips(int buf, int base, int[] widths, int[] heights, boolean preserveTransparent) {
      int prev = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
      GL20C.glUseProgram(mipProgram);
      GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, buf);
      GL20C.glUniform1i(uPreserve, preserveTransparent ? 1 : 0);
      long parent = base;
      for (int i = 1; i < widths.length; i++) {
         long child = parent + (long) widths[i - 1] * heights[i - 1] * 4;
         GL30C.glUniform1ui(uParent, (int) (parent / 4));
         GL30C.glUniform1ui(uChild, (int) (child / 4));
         GL20C.glUniform2i(uPSize, widths[i - 1], heights[i - 1]);
         GL20C.glUniform2i(uCSize, widths[i], heights[i]);
         GL20C.glUniform1i(uMaxAlpha, i <= 2 ? 1 : 0);
         GL43C.glDispatchCompute((widths[i] * heights[i] + 63) / 64, 1, 1);
         GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
         parent = child;
      }
      GL20C.glUseProgram(prev);
   }

   /**
    * Creates the levels of the bound GL_TEXTURE_2D as BC3 from RGBA8 levels. levels[i] holds w>>i x h>>i pixels
    * (at least 1), positioned at 0; hq picks the fit. The caller has set the texture parameters.
    */
   public static void upload(ByteBuffer[] levels, int[] widths, int[] heights, int firstLevel, boolean hq) {
      int n = levels.length;
      long srcBytes = 0, dstBytes = 0;
      for (int i = 0; i < n; i++) {
         srcBytes += (long) widths[i] * heights[i] * 4;
         dstBytes += TexBc.bc3Size(widths[i], heights[i]);
      }
      GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, srcBuf);
      if (srcBytes > srcCap) {
         srcCap = (int) Math.max(srcBytes, 1 << 20);
         GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, srcCap, GL15C.GL_STREAM_DRAW);
      } else {
         // orphan: the previous texture's dispatch may still read the old storage
         GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, srcCap, GL15C.GL_STREAM_DRAW);
      }
      long off = 0;
      for (int i = 0; i < n; i++) {
         ByteBuffer l = levels[i];
         int bytes = widths[i] * heights[i] * 4;
         ByteBuffer v = l.duplicate();
         v.position(0).limit(bytes);
         GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, off, v);
         off += bytes;
      }
      GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
      encode(srcBuf, 0, widths, heights, firstLevel, hq, false);
   }

   /** Dispatches the encoder over levels at byte offset base of src and creates the texture levels from the blocks. */
   private static void encode(int src, int base, int[] widths, int[] heights, int firstLevel, boolean hq, boolean premul) {
      int n = widths.length;
      long dstBytes = 0;
      for (int i = 0; i < n; i++) {
         dstBytes += TexBc.bc3Size(widths[i], heights[i]);
      }
      if (dstBytes > dstCap) {
         // only the GPU writes and reads this buffer (compute, then the texture upload), so it is never orphaned:
         // the driver orders those itself; it only grows
         dstCap = (int) Math.max(dstBytes, 8 << 20);
         GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, dstBuf);
         GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, dstCap, GL15C.GL_STREAM_COPY);
         GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
      }
      int prev = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
      GL20C.glUseProgram(program);
      GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, src);
      GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, dstBuf);
      GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, tabBuf);
      GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, lutBuf);
      GL20C.glUniform1i(uLut, lutSet ? 1 : 0);
      GL20C.glUniform1i(uAlphaSearchErr, TexBc.ALPHA_SEARCH_ERR);
      GL20C.glUniform1i(uSearch8, TexBc.SEARCH_8 ? 1 : 0);
      GL20C.glUniform1i(uHq, hq ? 1 : 0);
      GL20C.glUniform1i(uPremul, premul ? 1 : 0);
      long so = base, dO = 0;
      for (int i = 0; i < n; i++) {
         int blocks = ((widths[i] + 3) >> 2) * ((heights[i] + 3) >> 2);
         GL30C.glUniform1ui(uSrcBase, (int) (so / 4));
         GL30C.glUniform1ui(uDstBase, (int) (dO / 16));
         GL20C.glUniform2i(uSize, widths[i], heights[i]);
         GL43C.glDispatchCompute((blocks + 63) / 64, 1, 1);
         so += (long) widths[i] * heights[i] * 4;
         dO += (long) blocks * 16;
      }
      GL42C.glMemoryBarrier(GL42C.GL_PIXEL_BUFFER_BARRIER_BIT | GL42C.GL_TEXTURE_UPDATE_BARRIER_BIT);
      GL20C.glUseProgram(prev);
      GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, dstBuf);
      dO = 0;
      for (int i = 0; i < n; i++) {
         int size = TexBc.bc3Size(widths[i], heights[i]);
         GL13C.glCompressedTexImage2D(GL11C.GL_TEXTURE_2D, firstLevel + i, TexBc.GL_COMPRESSED_RGBA_S3TC_DXT5_EXT, widths[i], heights[i], 0, size, dO);
         dO += size;
      }
      GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
   }
}
