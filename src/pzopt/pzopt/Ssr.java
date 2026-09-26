package pzopt;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoWorld;

/**
 * Screen-space reflections on water and puddles (Config {@code reflections}, {@code reflectionStrengthPct},
 * {@code reflectionPuddles}; write-up docs/findings-reflections-2026-09-25.md).
 *
 * <p>The view is an orthographic 2:1 isometric one, so the mirror of the view ray about a horizontal surface is the same
 * for every pixel: on screen it runs straight up the pixel's column, and in the world frame of the mapping below
 * (u = x - y from the window x, v = x + y - 6z from the window y, w = x + y + 2z from the depth, z in levels) a scene
 * point at height h above the reflecting plane mirrors 12 h units of v below itself, i.e. twice its height on screen.
 *
 * <p>Pixel-projected reflections ({@code ssrMode=ppr}): every surface the game draws writes itself into the pixel it
 * mirrors to, instead of every water pixel searching for it. The chunk composite (it already reads each static pixel's
 * colour and depth) does it in the same fragment ({@link #SCATTER_GLSL}); the characters, animals and vehicles near
 * water, drawn after it, through one instanced box each right after the water ({@link Moving}, for the next frame). A
 * key is [frame epoch | 2047 - rows to the surface | colour 5:5:4] and imageAtomicMax keeps the nearest surface of the
 * newest frame; two hashes take turns every {@link #EPOCHS} frames while the other is cleared a slice a frame. Targets
 * are gated by a world-anchored square map (water 255, level-0 puddles 128) and a per-chunk-texture switch. The water
 * and puddle shaders only read: an 8x8 tile stamp first, then the key, the world colour at its source displaced by the
 * waves (the key's colour for a hidden layer), Fresnel and the distance / edge fades.
 * {@code ssrMode=march} keeps the classic alternative, a ray march up the column inside the water shader.
 */
public final class Ssr {
   private Ssr() {
   }

   /** The texture units the patched surface shaders read the world colour and depth from (free in the water / puddle shaders). */
   static final int COLOR_UNIT = 13, DEPTH_UNIT = 14;
   private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
   private static volatile boolean failed;
   private static long patched, frames, draws, noTarget;

   /** Could the reflections run at all (the shaders were patched at launch; macOS has a GLSL 1.20 context)? */
   static boolean supported() {
      return !MAC && !failed && patched > 0;
   }

   public static boolean active() {
      return Config.SSR && Config.SSR_STRENGTH_PCT > 0 && Overrides.enabled() && supported();
   }

   // ------------------------------------------------------------------------------------------------ shaders

   /**
    * ShaderUnit hook (after the HDR and pixel-light patches): the water shaders get the reflection lookup at the end of
    * their mainImage, where the finished colour, its alpha and the wave normal {@code gm} are known; the puddles and the
    * chunk composite theirs (below). GLSL 1.50 compatibility + ARB_shader_image_load_store (the stock files say 1.20;
    * the context is a compatibility one on Linux and Windows; macOS's GL 2.1 has neither).
    */
   public static String patchShader(String fileName, String code) {
      // only when the reflections are on at launch: with them off the game keeps its own programs, not patched ones with
      // the lookups switched off (those measured a few us dearer: registers)
      if (fileName == null || code == null || MAC || !Overrides.enabled() || !Config.SSR || Config.DEV_SSR_NO_PATCH) {
         return code;
      }
      String f = fileName.replace('\\', '/');
      if (f.endsWith("/chunkShader.frag") || f.endsWith("/pzopt_chunkBase.frag") || f.endsWith("/pzopt_chunkStock.frag")) {
         return patchComposite(fileName, code);
      }
      if (f.endsWith("puddles_common.frag.glsl")) {
         return patchPuddles(fileName, code);
      }
      if (f.matches(".*/(pzopt_)?puddles(_hq|_mq|_lq)?\\.frag") && code.startsWith("#version 120")) {
         // the puddle program's main unit: the same GLSL version as its patched common unit
         return "#version 150 compatibility" + code.substring("#version 120".length());
      }
      if (!(f.endsWith("/water.frag") || f.endsWith("/water_hq.frag"))) {
         return code;
      }
      String anchor = "gl_FragDepth = vDepth;";
      int at = code.indexOf(anchor);
      int mi = code.indexOf("void mainImage(");
      if (at < 0 || mi < 0 || at < mi || !code.contains("vec3 gm") || !code.startsWith("#version 120")) {
         Log.warn("ssr: " + fileName + " has changed, no reflections");
         return code;
      }
      String c = "#version 150 compatibility\n#extension GL_ARB_shader_image_load_store : require" + ("ppr".equals(Config.SSR_MODE) ? "\n#define PZ_SSR_PPR" : "")
            + code.substring("#version 120".length(), mi) + WATER_GLSL + "\n" + code.substring(mi, at + anchor.length())
            + "\n    fragColor = pzSsrApply(fragColor, gm);" + code.substring(at + anchor.length());
      int test = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
      GL20.glShaderSource(test, c);
      GL20.glCompileShader(test);
      boolean ok = GL20.glGetShaderi(test, GL20.GL_COMPILE_STATUS) != 0;
      String log = ok ? "" : GL20.glGetShaderInfoLog(test, 4096);
      GL20.glDeleteShader(test);
      if (!ok) {
         Log.warn("ssr: " + fileName + " reflection patch does not compile, stays as it was: " + log);
         return code;
      }
      patched++;
      Log.info("ssr: reflections patched into " + fileName);
      return c;
   }

   /**
    * The puddles' common fragment unit (stock or puddleEarlyZ's copy, HDR's glint patch already in): the scene mixed into
    * the reflective colour of the HQ and MQ puddles (the LQ ones have none), weighted there by the puddle's own mask.
    */
   private static String patchPuddles(String fileName, String code) {
      String anchor = "vec3 reflection = fragColor.rgb;";
      int sm = code.indexOf("vec2 SphereMap(");
      if (!code.startsWith("#version 120") || sm < 0 || !code.contains(anchor) || !code.contains("varying vec4 vertColour;")) {
         Log.warn("ssr: " + fileName + " has changed, no puddle reflections");
         return code;
      }
      String c = "#version 150 compatibility\n#extension GL_ARB_shader_image_load_store : require" + ("ppr".equals(Config.SSR_MODE) ? "\n#define PZ_SSR_PPR" : "")
            + code.substring("#version 120".length(), sm) + WATER_GLSL + "\n" + code.substring(sm);
      // only where the puddle shows its reflective colour (ambient / reflective parts), not the wet sheen around it
      c = c.replace(anchor, anchor + "\n    if (alphaPuddlesAmbient + alphaPuddlesReflection > 0.02) reflection = pzSsrPuddle(reflection, gm);");
      int test = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
      GL20.glShaderSource(test, c);
      GL20.glCompileShader(test);
      boolean ok = GL20.glGetShaderi(test, GL20.GL_COMPILE_STATUS) != 0;
      String log = ok ? "" : GL20.glGetShaderInfoLog(test, 4096);
      GL20.glDeleteShader(test);
      if (!ok) {
         Log.warn("ssr: " + fileName + " puddle patch does not compile, stays as it was: " + log);
         return code;
      }
      Log.info("ssr: puddle reflections patched into " + fileName);
      return c;
   }

   /**
    * The chunk composite: the stock chunkShader.frag (GLSL 1.20, gl_FragColor) or pixelLight's programs in its place
    * (GLSL 4.20, out fragColor). Its main is renamed and the scatter's main calls it, then mirrors what it wrote.
    */
   private static String patchComposite(String fileName, String code) {
      int nl = code.indexOf('\n');
      String first = nl < 0 ? "" : code.substring(0, nl).trim();
      boolean stock = first.equals("#version 120");
      boolean glsl420 = first.startsWith("#version 420") || first.startsWith("#version 430") || first.startsWith("#version 450") || first.startsWith("#version 460");
      String out = code.contains("out vec4 fragColor;") ? "fragColor" : "gl_FragColor";
      if (!(stock || glsl420) || code.indexOf("void main()") < 0 || !code.contains("gl_FragDepth") || !code.contains(out)) {
         Log.warn("ssr: " + fileName + " is not a composite shader we know, no reflection scatter");
         return code;
      }
      String head = stock ? "#version 150 compatibility\n#extension GL_ARB_shader_image_load_store : require" : first;
      String c = head + "\n#define PZ_SSR_OUT " + out + code.substring(nl).replace("void main()", "void pzChunkMain()") + "\n" + SCATTER_GLSL + "\n";
      int test = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
      GL20.glShaderSource(test, c);
      GL20.glCompileShader(test);
      boolean ok = GL20.glGetShaderi(test, GL20.GL_COMPILE_STATUS) != 0;
      String log = ok ? "" : GL20.glGetShaderInfoLog(test, 4096);
      GL20.glDeleteShader(test);
      if (!ok) {
         Log.warn("ssr: " + fileName + " scatter patch does not compile, stays as it was: " + log);
         return code;
      }
      scatterPatched = true;
      Log.info("ssr: reflection scatter appended to " + fileName);
      return c;
   }

   private static volatile boolean scatterPatched;

   /**
    * The reflection of one water pixel. Its reflected ray keeps g = w - v / 3 and climbs its column; the first scene pixel
    * with g at or above the ray's is what the ray meets (infinitely thick surfaces). Stride march, then a binary refinement;
    * the hit is looked up displaced by the wave normal; blended over the water (and the bed it lets through) with alpha.
    */
   static final String WATER_GLSL = String.join("\n",
         "layout(r32ui) coherent uniform uimage2D pzSsrHash;",
         "layout(r32ui) coherent uniform uimage2D pzSsrTiles;",
         "uniform int pzSsrFrame;",
         "uniform vec4 pzSsrK;    // ppr: on (1) / march (0), epoch, reach rows, hidden-layer colour tolerance",
         "uniform sampler2D pzSsrColor;",
         "uniform sampler2D pzSsrDepth;",
         "uniform vec4 pzSsrMapA; // kA, cA, kB, cB: v = kB py + cB",
         "uniform vec4 pzSsrMapC; // kC, cC (w = kC depth + cC), z of the water (levels), strength (0 = off)",
         "uniform vec4 pzSsrP;    // stride px, steps, refinements, distortion px",
         "uniform vec4 pzSsrQ;    // reach px (fade), top of the viewport (px), thickness (units of w), -",
         "float pzSsrG(int x, int y) {",
         "   float d = texelFetch(pzSsrDepth, ivec2(x, y), 0).r;",
         "   return pzSsrMapC.x * d + pzSsrMapC.y - (pzSsrMapA.z * (float(y) + 0.5) + pzSsrMapA.w) / 3.0;",
         "}",
         "vec4 pzSsrBlend(vec4 c, vec3 r, float k) {",
         "   float a = 1.0 - (1.0 - c.a) * (1.0 - k);",
         "   c.rgb = (c.rgb * c.a * (1.0 - k) + r * k) / max(a, 1e-4);",
         "   c.a = a;",
         "   return c;",
         "}",
         "// pixel-projected: the scatter left the first surface above this pixel in its texel (epoch in the top 7 bits, 2047 -",
         "// the rows up to it in the next 11, its colour as 5:5:4); the texel is only read (a later frame's epoch outranks it)",
         "#ifdef PZ_SSR_PPR",
         "float pzSsrLookup(vec3 n, out vec3 r, bool half_) {",
         "   r = vec3(0.0);",
         "   ivec2 p = ivec2(gl_FragCoord.xy);",
         "   int px = p.x;",
         "   if (half_) p &= ~1; // puddles: the half-resolution keys",
         "   // the 8x8 tile holds the last frame anything was mirrored into it: most surface pixels stop at this cached read",
         "   if (imageLoad(pzSsrTiles, p >> 3).r != uint(pzSsrFrame)) return 0.0;",
         "   uint epoch = uint(pzSsrK.y);",
         "   uint key = imageLoad(pzSsrHash, p).r;",
         "   if ((key >> 25) != epoch) {",
         "      p.y += half_ ? 2 : 1; // a one-row gap: the texel above, its source is ours one row up",
         "      key = imageLoad(pzSsrHash, p).r;",
         "      if ((key >> 25) != epoch) return 0.0;",
         "   }",
         "   int dist = 2047 - int((key >> 14) & 2047u);",
         "   vec3 pk = vec3(float((key >> 9) & 31u) / 31.0, float((key >> 4) & 31u) / 31.0, float(key & 15u) / 15.0);",
         "   float far = clamp(float(dist) / pzSsrK.z, 0.0, 1.0);",
         "   // the world texture at the source, displaced by the waves: sharp where the object meets the water and rougher the",
         "   // farther the ray went (contact hardening); if it does not match the key's colour the source is a hidden layer",
         "   // (or the waves moved the lookup off it) and the key's own colour stands",
         "   vec2 src = vec2(float(px) + 0.5, float(p.y + dist) + 0.5);",
         "   r = texture2D(pzSsrColor, (src + vec2(n.x, -n.z) * pzSsrP.w * (0.3 + 1.4 * far)) / vec2(textureSize(pzSsrColor, 0))).rgb;",
         "   if (any(greaterThan(abs(r - pk), vec3(pzSsrK.w)))) r = pk;",
         "   // Fresnel (Schlick, water F0 0.02) of the wave normal against the view, relative to flat water at the camera's 30 deg",
         "   float cv = clamp(dot(normalize(n), vec3(0.0, 0.5, 0.8660254)), 0.0, 1.0);",
         "   float fr = (0.02 + 0.98 * pow(1.0 - cv, 5.0)) / 0.050625;",
         "   float fade = clamp(2.0 - 2.0 * far, 0.0, 1.0) * clamp((pzSsrQ.y - src.y) / 48.0, 0.0, 1.0);",
         "   return clamp(pzSsrMapC.w * fade * clamp(fr, 0.35, 2.2), 0.0, 1.0);",
         "}",
         "vec4 pzSsrPpr(vec4 c, vec3 n) {",
         "   vec3 r;",
         "   float k = pzSsrLookup(n, r, false);",
         "   if (pzSsrQ.w > 0.5) return vec4(r * k, 1.0); // dev view: the reflection term alone",
         "   return k > 0.0 ? pzSsrBlend(c, r, k) : c;",
         "}",
         "#endif",
         "// puddles: the scene into the puddle's reflective colour (lit already: the puddle multiplies by its square's light later)",
         "vec3 pzSsrPuddle(vec3 refl, vec3 n) {",
         "#ifdef PZ_SSR_PPR",
         "   if (pzSsrMapC.w <= 0.0) return refl;",
         "   vec3 r;",
         "   float k = pzSsrLookup(n, r, true);",
         "   if (pzSsrQ.w > 0.5) return r * k / max(vertColour.rgb, vec3(0.15)); // dev view (the puddle's own alpha still applies)",
         "   return k > 0.0 ? mix(refl, r / max(vertColour.rgb, vec3(0.15)), k) : refl;",
         "#else",
         "   return refl;",
         "#endif",
         "}",
         "vec4 pzSsrApply(vec4 c, vec3 n) {",
         "   if (pzSsrMapC.w <= 0.0) return c;",
         "#ifdef PZ_SSR_PPR",
         "   return pzSsrPpr(c, n);",
         "#else",
         "   int x = int(gl_FragCoord.x), y0 = int(gl_FragCoord.y);",
         "   float g0 = 2.0 / 3.0 * (pzSsrMapA.z * gl_FragCoord.y + pzSsrMapA.w) + 8.0 * pzSsrMapC.z;",
         "   int stride = int(pzSsrP.x), top = int(pzSsrQ.y);",
         "   // a crossing (the ray goes from in front of the scene to behind it) is refined, then kept only if the surface",
         "   // there is within the thickness of the ray: else the ray passed behind something standing in front, go on",
         "   int hi = -1;",
         "   bool below = true;",
         "   for (int i = 1; i <= int(pzSsrP.y); i++) {",
         "      int y = y0 + i * stride;",
         "      if (y >= top) break;",
         "      bool above = pzSsrG(x, y) >= g0;",
         "      if (above && below) {",
         "         int a = y - stride, b = y;",
         "         for (int j = 0; j < int(pzSsrP.z) && b - a > 1; j++) {",
         "            int mid = (a + b) / 2;",
         "            if (pzSsrG(x, mid) >= g0) b = mid; else a = mid;",
         "         }",
         "         if (pzSsrG(x, b) - g0 < pzSsrQ.z) { hi = b; break; }",
         "      }",
         "      below = !above;",
         "   }",
         "   if (hi < 0) return c;",
         "   vec2 at = vec2(float(x) + 0.5 + n.x * pzSsrP.w, float(hi) + 0.5 - n.z * pzSsrP.w);",
         "   vec3 r = texture2D(pzSsrColor, at / vec2(textureSize(pzSsrColor, 0))).rgb;",
         "   float dist = float(hi - y0);",
         "   float fade = clamp(2.0 - 2.0 * dist / pzSsrQ.x, 0.0, 1.0) * clamp((float(top) - float(hi)) / 32.0, 0.0, 1.0);",
         "   return pzSsrBlend(c, r, pzSsrMapC.w * fade);",
         "#endif",
         "}");

   /**
    * Appended to the chunk composite (chunkShader.frag, main renamed): every opaque composite fragment standing above the
    * water plane writes itself into the texel it mirrors to, if that texel's square is water (the square map). The key's
    * top bits are the rows between the two, so atomicMin keeps the first surface the reflected ray meets; hidden
    * fragments (overdrawn later in the composite) write theirs too, with their own colour: the reflection sees layers the
    * camera does not.
    */
   static final String SCATTER_FN = String.join("\n",
         "layout(r32ui) coherent uniform uimage2D pzSsrHash;",
         "layout(r32ui) coherent uniform uimage2D pzSsrTiles;",
         "uniform int pzSsrFrame;",
         "uniform float pzSsrMapMin; // the square map's value that counts: water 1.0, puddles 0.5 (while they draw)",
         "uniform float pzSsrReach;  // rows: a surface mirrored farther is faded out entirely by the resolve, so it is not written",
         "uniform sampler2D pzSsrWater;",
         "uniform vec4 pzSsrMapA; // kA, cA, kB, cB",
         "uniform vec4 pzSsrMapC; // kC, cC, z of the water, on",
         "uniform vec4 pzSsrO;    // origin square x, y (mod the map size), map size, epoch",
         "void pzSsrScatter(vec3 rgb, float depth, vec2 fc) {",
         "   float v = pzSsrMapA.z * fc.y + pzSsrMapA.w;",
         "   float h = (pzSsrMapC.x * depth + pzSsrMapC.y - v) / 8.0 - pzSsrMapC.z;",
         "   if (h <= 0.02) return;",
         "   // the mirrored pixel spans [yt - 0.5, yt + 0.5] (a wall maps row for row): both rows it overlaps get the key",
         "   float ytf = fc.y - 12.0 * h / -pzSsrMapA.z;",
         "   int ya = int(floor(ytf - 0.5)), yb = int(floor(ytf + 0.5));",
         "   int dist = int(fc.y) - ya;",
         "   if (yb < 0 || float(dist) > pzSsrReach) return;",
         "   float u = pzSsrMapA.x * fc.x + pzSsrMapA.y;",
         "   float s = pzSsrMapA.z * ytf + pzSsrMapA.w + 6.0 * pzSsrMapC.z;",
         "   vec2 sq = mod(floor(vec2(s + u, s - u) * 0.5) + pzSsrO.xy, pzSsrO.z);",
         "   float kind = texture(pzSsrWater, (sq + 0.5) / pzSsrO.z).r;",
         "   if (kind < pzSsrMapMin) return;",
         "   int x = int(fc.x);",
         "   if (kind < 0.75) {",
         "      // a puddle: the rain's rings blur it anyway, so half resolution (even sources, keys on even texels): a quarter of the atomics",
         "      if (((x | int(fc.y)) & 1) != 0) return;",
         "      yb &= ~1;",
         "      dist = int(fc.y) - yb + 1;",
         "   }",
         "   uvec3 q = uvec3(clamp(rgb, 0.0, 1.0) * vec3(31.0, 31.0, 15.0) + 0.5);",
         "   uint high = (uint(pzSsrO.w) << 25) | (q.r << 9) | (q.g << 4) | q.b;",
         "   // atomicMax: this frame's epoch outranks any earlier key, then the nearest surface (2047 - rows) wins. One row: the",
         "   // resolve fills a one-row gap from the row above. Its return value is not used: a fire-and-forget reduction, no",
         "   // round trip that would stall the fragment; the tile mark is a plain store of the same value by every writer.",
         "   imageAtomicMax(pzSsrHash, ivec2(x, yb), high | (uint(2048 - dist) << 14));",
         "   imageStore(pzSsrTiles, ivec2(x, yb) >> 3, uvec4(uint(pzSsrFrame)));",
         "}");

   /** the composite's own main, then the scatter of what it wrote (PZ_SSR_OUT: gl_FragColor, or pixelLight's fragColor) */
   static final String SCATTER_GLSL = SCATTER_FN + String.join("\n",
         "",
         "void main() {",
         "   pzChunkMain();",
         "   if (pzSsrMapC.w <= 0.0 || PZ_SSR_OUT.a < 0.5) return;",
         "   // a chunk texture only puddles are near (switch 0.5): their keys are half resolution, the odd pixels have none",
         "   if (pzSsrMapC.w < 0.75 && ((int(gl_FragCoord.x) | int(gl_FragCoord.y)) & 1) != 0) return;",
         "   pzSsrScatter(PZ_SSR_OUT.rgb / PZ_SSR_OUT.a, gl_FragDepth, gl_FragCoord.xy);",
         "}");

   /** The moving objects near water (characters, animals, vehicles): one box each, their drawn pixels scattered. */
   static final String MOVING_VERT = String.join("\n",
         "#version 150",
         "uniform vec4 pzSsrMapA;",
         "uniform vec4 vp;           // viewport x, y, w, h",
         "uniform vec4 box[128];     // x, y relative to the origin square, z (levels), half width (squares)",
         "uniform float boxH[128];   // height (levels); negative: only puddles near (half-resolution sources)",
         "flat out vec3 band;        // the object's own range of w (x + y + 2z), 1 = half resolution",
         "void main() {",
         "   vec4 b = box[gl_InstanceID];",
         "   float bh = boxH[gl_InstanceID], hgt = abs(bh);",
         "   float u = b.x - b.y, s = b.x + b.y;",
         "   float u0 = u - 2.0 * b.w, u1 = u + 2.0 * b.w;",
         "   float v0 = s - 2.0 * b.w - 6.0 * (b.z + hgt), v1 = s + 2.0 * b.w - 6.0 * b.z;",
         "   band = vec3(s - 2.0 * b.w + 2.0 * b.z - 0.25, s + 2.0 * b.w + 2.0 * (b.z + hgt) + 0.25, bh < 0.0 ? 1.0 : 0.0);",
         "   vec2 lo = vec2((u0 - pzSsrMapA.y) / pzSsrMapA.x, (v1 - pzSsrMapA.w) / pzSsrMapA.z);",
         "   vec2 hi = vec2((u1 - pzSsrMapA.y) / pzSsrMapA.x, (v0 - pzSsrMapA.w) / pzSsrMapA.z);",
         "   vec2 corner = vec2(float(gl_VertexID == 1 || gl_VertexID == 2), float(gl_VertexID >= 2));",
         "   vec2 px = mix(lo, hi, corner);",
         "   gl_Position = vec4((px - vp.xy) / vp.zw * 2.0 - 1.0, 0.0, 1.0);",
         "}");

   static final String MOVING_FRAG = "#version 150\n#extension GL_ARB_shader_image_load_store : require\n" + SCATTER_FN + String.join("\n",
         "",
         "uniform sampler2D sceneDepth;",
         "uniform sampler2D sceneColor;",
         "flat in vec3 band;",
         "void main() {",
         "   ivec2 p = ivec2(gl_FragCoord.xy);",
         "   if (band.z > 0.5 && ((p.x | p.y) & 1) != 0) return;",
         "   float d = texelFetch(sceneDepth, p, 0).r;",
         "   // only the object's own pixels (the background around it was scattered by the composite already)",
         "   float w = pzSsrMapC.x * d + pzSsrMapC.y;",
         "   if (d >= 1.0 || w < band.x || w > band.y) return;",
         "   pzSsrScatter(texelFetch(sceneColor, p, 0).rgb, d, gl_FragCoord.xy);",
         "}");

   // ------------------------------------------------------------------------------------------------ pixel-projected

   static final int HASH_IMAGE_UNIT = 6, TILE_IMAGE_UNIT = 5, WATER_MAP_UNIT = 15;
   /** The water square map: one byte per square of level 0, world-anchored, wrapping (square coordinates mod N). */
   static final int MAP_SIZE = 1024, SLOTS = MAP_SIZE / 8;
   private static final Object[] slotOwner = new Object[SLOTS * SLOTS];
   private static final long[] slotBits = new long[SLOTS * SLOTS]; // water squares
   private static final long[] slotPuddle = new long[SLOTS * SLOTS]; // puddle squares (they reflect while it is wet)
   private static final int[] slotSize = new int[SLOTS * SLOTS];
   private static long epochCounter;
   static final int EPOCHS = 127, CLEAR_FRAMES = 120;

   static boolean ppr() {
      return "ppr".equals(Config.SSR_MODE) && scatterPatched;
   }

   /** A frame's facts for the render thread: the camera, the epoch, the water map slots that changed. */
   private static final class Frame extends TextureDraw.GenericDrawer {
      final View view = new View();
      int epoch, clearIndex, buffer;
      int nextEpoch, nextBuffer; // the next frame's: the moving scatter runs after this frame's water and writes for it
      int stamp; // the frame's number for the tile map (never repeats in practice: 2^31 frames)
      // the moving objects near water this frame (FBORenderCell adds them as it draws them), scattered before the water
      // chunk textures of this composite whose reflections can reach water (their scatter runs; the rest skip it)
      final java.util.IdentityHashMap<Object, Boolean> nearWater = new java.util.IdentityHashMap<>();
      static final int MAX_BOXES = 128;
      int boxes, puddleBoxes;
      final float[] box = new float[MAX_BOXES * 4], boxH = new float[MAX_BOXES];
      final Moving moving = new Moving(this); // its own drawer: the game thread queues frames ahead of the render thread
      int uploads;
      boolean puddles; // the puddles draw this frame: their squares count in the scatter
      int[] uploadSlot = new int[64];
      long[] uploadBits = new long[64], uploadPuddle = new long[64];

      @Override
      public void render() {
         frameStart(this);
      }
   }

   private static final Frame[] FRAMES = {new Frame(), new Frame(), new Frame(), new Frame()};
   private static int frameIndex;

   /**
    * Game thread, FBORenderCell right before the chunk composite: the water square map of the on-screen chunks (a chunk's
    * 8x8 bits are rebuilt when it takes a slot or its water list changes), then the frame's setup on the render thread.
    */
   public static void beforeComposite(int playerIndex, java.util.ArrayList<zombie.iso.IsoChunk> chunks) {
      if (!active() || !ppr()) {
         if (scatterOn) {
            scatterOn = false;
            SpriteRenderer.instance.drawGeneric(OFF); // the composite programs keep their uniforms: switch the scatter off once
         }
         return;
      }
      scatterOn = true;
      alternate();
      Frame f = FRAMES[frameIndex++ & 3];
      f.view.capture(playerIndex);
      f.view.on = alternateOn;
      f.boxes = 0;
      f.puddleBoxes = 0;
      f.nearWater.clear();
      // puddles reflect only once they are big enough: the shader's reflective part needs puddle size x level > 0.341, and the
      // level peaks near 3
      f.puddles = Config.SSR_PUDDLES && zombie.core.PerformanceSettings.puddlesQuality < 2 && zombie.iso.IsoPuddles.getInstance().shouldRenderPuddles()
         && zombie.iso.IsoPuddles.getInstance().getPuddlesSizeFinalValue() > 0.11F && !zombie.iso.fboRenderChunk.FBORenderSnow.getInstance().isSnowAnywhere();
      gameFrame = f;
      // two hashes, each written for EPOCHS frames (epochs 1..EPOCHS) while the other is cleared a slice a frame
      f.buffer = (int)(epochCounter / EPOCHS & 1L);
      f.epoch = (int)(epochCounter % EPOCHS) + 1;
      f.clearIndex = (int)(epochCounter % EPOCHS);
      epochCounter++;
      f.stamp = (int)epochCounter; // 1, 2, ... (0 = the cleared tile)
      f.nextBuffer = (int)(epochCounter / EPOCHS & 1L);
      f.nextEpoch = (int)(epochCounter % EPOCHS) + 1;
      f.uploads = 0;
      for (int i = 0; i < chunks.size(); i++) {
         zombie.iso.IsoChunk ch = chunks.get(i);
         if (ch.minLevel > 0 || ch.maxLevel < 0) {
            continue;
         }
         zombie.iso.fboRenderChunk.FBORenderLevels rl = ch.getRenderLevels(playerIndex);
         java.util.List<zombie.iso.IsoGridSquare> water = rl.getCachedSquares_Water(0), shore = rl.getCachedSquares_WaterShore(0);
         java.util.List<zombie.iso.IsoGridSquare> puddles = rl.getCachedSquares_Puddles(0);
         int size = (water.size() * 4099 + shore.size()) * 65 + puddles.size();
         int slot = Math.floorMod(ch.wx, SLOTS) + Math.floorMod(ch.wy, SLOTS) * SLOTS;
         if (slotOwner[slot] == ch && slotSize[slot] == size) {
            continue;
         }
         long bits = 0L;
         bits |= waterBits(water, ch);
         bits |= waterBits(shore, ch);
         long pbits = puddleBits(puddles, ch) & ~bits;
         slotOwner[slot] = ch;
         slotSize[slot] = size;
         if ((bits != slotBits[slot] || pbits != slotPuddle[slot]) && f.uploads < f.uploadSlot.length) {
            slotBits[slot] = bits;
            slotPuddle[slot] = pbits;
            f.uploadSlot[f.uploads] = slot;
            f.uploadPuddle[f.uploads] = pbits;
            f.uploadBits[f.uploads++] = bits;
         }
      }
      java.util.ArrayList<zombie.iso.fboRenderChunk.FBORenderChunk> list = zombie.iso.fboRenderChunk.FBORenderChunkManager.instance.toRenderThisFrame;
      for (int i = 0; i < list.size(); i++) {
         zombie.iso.fboRenderChunk.FBORenderChunk rc = list.get(i);
         compositeChunk(f, rc.chunk, rc.depth);
      }
      SpriteRenderer.instance.drawGeneric(f);
   }

   private static Frame gameFrame; // game thread: the frame the moving objects drawn now add their boxes to

   /** Is (x, y) of level 0 a water square in the map (its chunk in its slot)? */
   private static boolean waterAt(int x, int y, boolean puddles) {
      int cx = x >> 3, cy = y >> 3;
      int slot = Math.floorMod(cx, SLOTS) + Math.floorMod(cy, SLOTS) * SLOTS;
      Object o = slotOwner[slot];
      if (!(o instanceof zombie.iso.IsoChunk ch) || ch.wx != cx || ch.wy != cy) {
         return false;
      }
      long b = puddles ? slotBits[slot] | slotPuddle[slot] : slotBits[slot];
      return (b >>> ((y & 7) * 8 + (x & 7)) & 1L) != 0L;
   }

   private static long movingAdded, movingSkipped, chunksNear, chunksFar;
   /** reflections reach this many chunks in front (x + i, y + j) of a chunk: 3 levels of height mirror 18 squares away */
   private static final int CHUNK_REACH = 3;
   private static final float PUDDLE_MOVING_RADIUS = 14F;
   private static final int PUDDLE_MOVING_MAX = 40;

   /**
    * Game thread, beforeComposite for each chunk texture of this frame's composite: does its chunk have water within
    * reflection reach in front of it (the chunks at +0..3 in x and y)? That texture's draws scatter only then.
    */
   private static void compositeChunk(Frame f, zombie.iso.IsoChunk ch, Object depthTexture) {
      if (ch == null || depthTexture == null) {
         return;
      }
      boolean near = false, water = false;
      for (int j = 0; j <= CHUNK_REACH && !water; j++) {
         for (int i = 0; i <= CHUNK_REACH && !water; i++) {
            int cx = ch.wx + i, cy = ch.wy + j;
            int slot = Math.floorMod(cx, SLOTS) + Math.floorMod(cy, SLOTS) * SLOTS;
            Object o = slotOwner[slot];
            if (o instanceof zombie.iso.IsoChunk c2 && c2.wx == cx && c2.wy == cy) {
               water = slotBits[slot] != 0L;
               near |= water || f.puddles && slotPuddle[slot] != 0L;
            }
         }
      }
      if (near) {
         f.nearWater.put(depthTexture, water ? Boolean.TRUE : Boolean.FALSE); // FALSE: puddles only (half-resolution scatter)
         chunksNear++;
      } else {
         chunksFar++;
      }
   }

   /**
    * Game thread, FBORenderCell as it draws a moving object on screen: a box around it for this frame's moving scatter if
    * it stands on level 0 or 1 with water where its reflection lands (the squares in front of it on screen, x + t, y + t).
    */
   public static void addMoving(zombie.iso.IsoMovingObject o) {
      Frame f = gameFrame;
      if (f == null || f.boxes >= Frame.MAX_BOXES) {
         return;
      }
      float z = o.getZ();
      if (z < 0F || z >= 2F) {
         return;
      }
      boolean vehicle = o instanceof zombie.vehicles.BaseVehicle;
      float half = vehicle ? 2.6F : 0.4F, height = vehicle ? 0.8F : 0.75F;
      int x = (int)Math.floor(o.getX()), y = (int)Math.floor(o.getY());
      int r = (int)Math.ceil(half), reach = 2 + (int)Math.ceil(6F * (z + height));
      boolean near = false, water = false;
      for (int t = 0; t <= reach && !water; t++) {
         for (int k = -r; k <= r && !water; k++) {
            water = waterAt(x + t + k, y + t, false) || waterAt(x + t, y + t + k, false);
            near |= water || f.puddles && (waterAt(x + t + k, y + t, true) || waterAt(x + t, y + t + k, true));
         }
      }
      if (!near) {
         movingSkipped++;
         return;
      }
      if (!water) {
         // puddles only: near the camera's focus and a bounded number (a horde in the rain would otherwise put every
         // zombie into the pass, a cost that follows the crowd); their reflections are small and rain-blurred further out
         float dx = o.getX() - IsoCamera.frameState.camCharacterX, dy = o.getY() - IsoCamera.frameState.camCharacterY;
         if (dx * dx + dy * dy > PUDDLE_MOVING_RADIUS * PUDDLE_MOVING_RADIUS || f.puddleBoxes >= PUDDLE_MOVING_MAX) {
            movingSkipped++;
            return;
         }
         f.puddleBoxes++;
      }
      int i = f.boxes++;
      f.box[i * 4] = o.getX() - f.view.ox;
      f.box[i * 4 + 1] = o.getY() - f.view.oy;
      f.box[i * 4 + 2] = z;
      f.box[i * 4 + 3] = half;
      f.boxH[i] = water ? height : -height; // negative: puddles only (half-resolution sources)
      movingAdded++;
   }

   /** Game thread, right before the water: the moving objects' scatter (after they are all drawn). */
   private static void queueMoving() {
      Frame f = gameFrame;
      gameFrame = null;
      if (f != null && f.boxes > 0 && f.view.on && (Config.DEV_SSR_SKIP & 1) == 0) {
         SpriteRenderer.instance.drawGeneric(f.moving);
      }
   }

   private static final class Moving extends TextureDraw.GenericDrawer {
      final Frame frame;
      static int program, vao;
      static int[] u;
      static java.nio.FloatBuffer boxBuf, hBuf;

      Moving(Frame frame) {
         this.frame = frame;
      }

      @Override
      public void render() {
         Frame f = this.frame;
         if (f == null || renderFrame == null || hashTex == 0 || cachedDepth == 0 || cachedColor == 0) {
            return;
         }
         try {
            if (program == 0) {
               program = AmbientOcclusion.link(MOVING_VERT, MOVING_FRAG);
               if (program == 0) {
                  throw new IllegalStateException("moving scatter shaders");
               }
               String[] names = {"pzSsrMapA", "pzSsrMapC", "pzSsrO", "pzSsrHash", "pzSsrWater", "sceneDepth", "sceneColor", "vp", "box", "boxH", "pzSsrTiles", "pzSsrFrame", "pzSsrMapMin", "pzSsrReach"};
               u = new int[names.length];
               for (int i = 0; i < names.length; i++) {
                  u[i] = GL20.glGetUniformLocation(program, names[i]);
               }
               vao = GL30.glGenVertexArrays();
               boxBuf = BufferUtils.createFloatBuffer(Frame.MAX_BOXES * 4);
               hBuf = BufferUtils.createFloatBuffer(Frame.MAX_BOXES);
               Log.info("ssr: moving-object scatter ready");
            }
            viewport();
            f.view.mapping(VP, MAP_K);
            if (charsPending) {
               GL45.glTextureBarrier(); // no water drew after the characters: their pixels still need the barrier
               charsPending = false;
            }
            org.lwjgl.opengl.GL42.glBindImageTexture(HASH_IMAGE_UNIT, hashTexs[f.nextBuffer], 0, false, 0, org.lwjgl.opengl.GL15.GL_READ_WRITE, GL30.GL_R32UI);
            // (no glGet: the sprite renderer works on unit 0, restored below)
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + COLOR_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, cachedColor);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, cachedDepth);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            bound = true;
            GL20.glUseProgram(program);
            GL20.glUniform4f(u[0], MAP_K[0], MAP_K[1], MAP_K[2], MAP_K[3]);
            GL20.glUniform4f(u[1], MAP_K[4], MAP_K[5], 0F, 1F);
            GL20.glUniform4f(u[2], Math.floorMod(f.view.ox, MAP_SIZE), Math.floorMod(f.view.oy, MAP_SIZE), MAP_SIZE, f.nextEpoch);
            GL20.glUniform1i(u[3], HASH_IMAGE_UNIT);
            GL20.glUniform1i(u[4], WATER_MAP_UNIT);
            GL20.glUniform1i(u[5], DEPTH_UNIT);
            GL20.glUniform1i(u[6], COLOR_UNIT);
            GL20.glUniform4f(u[7], VP[0], VP[1], VP[2], VP[3]);
            boxBuf.clear();
            boxBuf.put(f.box, 0, f.boxes * 4).flip();
            GL20.glUniform4fv(u[8], boxBuf);
            hBuf.clear();
            hBuf.put(f.boxH, 0, f.boxes).flip();
            GL20.glUniform1fv(u[9], hBuf);
            GL20.glUniform1i(u[10], TILE_IMAGE_UNIT);
            GL20.glUniform1i(u[11], f.stamp + 1); // for the next frame
            GL20.glUniform1f(u[12], f.puddles ? 0.4F : 0.75F);
            GL20.glUniform1f(u[13], reachRows(f.view, VP));
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDepthMask(false);
            GL11.glColorMask(false, false, false, false);
            GL30.glBindVertexArray(vao);
            org.lwjgl.opengl.GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, f.boxes);
            GL30.glBindVertexArray(0); // (restoreVbos below: the ring buffer binds its own again)
            org.lwjgl.opengl.GL42.glBindImageTexture(HASH_IMAGE_UNIT, hashTex, 0, false, 0, org.lwjgl.opengl.GL15.GL_READ_WRITE, GL30.GL_R32UI);
            movingDraws++;
            // back to the sprite renderer's state
            GL20.glUseProgram(0);
            GL11.glColorMask(true, true, true, true);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            zombie.core.opengl.GLStateRenderThread.restore();
            zombie.core.ShaderHelper.forgetCurrentlyBound();
            SpriteRenderer.ringBuffer.restoreVbos = true;
            SpriteRenderer.ringBuffer.restoreBoundTextures = true;
         } catch (Throwable t) {
            failed = true;
            Log.warn("ssr: moving scatter failed, reflections off: " + t);
         }
      }
   }

   private static long movingDraws;

   private static long puddleBits(java.util.List<zombie.iso.IsoGridSquare> squares, zombie.iso.IsoChunk ch) {
      long bits = 0L;
      for (int j = 0, n = squares.size(); j < n; j++) {
         zombie.iso.IsoGridSquare sq = squares.get(j);
         if (sq.getZ() != 0 || sq.getPuddles() == null) {
            continue;
         }
         int lx = sq.getX() - ch.wx * 8, ly = sq.getY() - ch.wy * 8;
         if (lx >= 0 && lx < 8 && ly >= 0 && ly < 8) {
            bits |= 1L << (ly * 8 + lx);
         }
      }
      return bits;
   }

   private static long waterBits(java.util.List<zombie.iso.IsoGridSquare> squares, zombie.iso.IsoChunk ch) {
      long bits = 0L;
      for (int j = 0, n = squares.size(); j < n; j++) {
         zombie.iso.IsoGridSquare sq = squares.get(j);
         if (sq.getZ() != 0 || sq.getWater() == null || !sq.getWater().isValid()) {
            continue;
         }
         int lx = sq.getX() - ch.wx * 8, ly = sq.getY() - ch.wy * 8;
         if (lx >= 0 && lx < 8 && ly >= 0 && ly < 8) {
            bits |= 1L << (ly * 8 + lx);
         }
      }
      return bits;
   }

   private static int hashW, hashH, mapTex, pprFbo = -1;
   private static final int[] hashTexs = new int[2];
   private static int hashTex; // the one written this frame
   private static int tileTex;
   private static int serial;
   private static Frame renderFrame; // render thread: this frame's composite setup (null = no scatter this frame)
   private static final java.nio.ByteBuffer SLOT_BYTES = BufferUtils.createByteBuffer(64);
   private static java.nio.IntBuffer clearValue;
   private static long scatterFrames;

   /** Render thread, ahead of the composite: resources, the rolling clear, the map uploads, the bindings. */
   private static void frameStart(Frame f) {
      renderFrame = null;
      if (Config.DEV_SSR_TIMING) {
         Timing.beginComposite(f.view.on);
      }
      try {
         int fbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
         if (fbo != cachedFbo) {
            cachedFbo = fbo;
            cachedColor = attachment(fbo, GL30.GL_COLOR_ATTACHMENT0);
            cachedDepth = attachment(fbo, GL30.GL_DEPTH_ATTACHMENT);
            Log.info("ssr: world framebuffer " + fbo + " colour texture " + cachedColor + " depth texture " + cachedDepth);
         }
         int color = cachedColor;
         if (color == 0) {
            noTarget++;
            return;
         }
         // the hash covers the offscreen area, not the framebuffer's texture (a power of two: 8192x4096 for 5120x2160)
         int vw = zombie.core.Core.getInstance().getOffscreenWidth(0), vh = zombie.core.Core.getInstance().getOffscreenHeight(0);
         if (fbo != pprFbo || vw > hashW || vh > hashH) {
            pprFbo = fbo;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
            int w = Math.min(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH), vw);
            int h = Math.min(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT), vh);
            if (hashTexs[0] == 0 || w > hashW || h > hashH) {
               hashW = Math.max(w, hashW);
               hashH = Math.max(h, hashH);
               clearValue = BufferUtils.createIntBuffer(1).put(0, 0);
               for (int i = 0; i < 2; i++) {
                  if (hashTexs[i] != 0) {
                     GL11.glDeleteTextures(hashTexs[i]);
                  }
                  hashTexs[i] = GL11.glGenTextures();
                  GL11.glBindTexture(GL11.GL_TEXTURE_2D, hashTexs[i]);
                  org.lwjgl.opengl.GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_R32UI, hashW, hashH);
                  org.lwjgl.opengl.GL44.glClearTexImage(hashTexs[i], 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, clearValue);
               }
               if (tileTex != 0) {
                  GL11.glDeleteTextures(tileTex);
               }

               tileTex = GL11.glGenTextures();
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, tileTex);
               org.lwjgl.opengl.GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_R32UI, (hashW + 7) / 8, (hashH + 7) / 8);
               org.lwjgl.opengl.GL44.glClearTexImage(tileTex, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, clearValue);
               Log.info("ssr: 2 x " + hashW + "x" + hashH + " R32UI reflection hashes + 8x8 tile map for framebuffer " + fbo);
            }
            if (mapTex == 0) {
               mapTex = GL11.glGenTextures();
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, mapTex);
               GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, MAP_SIZE, MAP_SIZE, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer)null);
               GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
               GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
               org.lwjgl.opengl.GL44.glClearTexImage(mapTex, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer)null);
            }
         }
         // this frame's hash; the other one is cleared a slice a frame, done well before it is written again, so a key of
         // an earlier use can never meet the same epoch
         hashTex = hashTexs[f.buffer];
         int rows = (hashH + CLEAR_FRAMES - 1) / CLEAR_FRAMES, y0 = f.clearIndex * rows;
         if (y0 < hashH) {
            org.lwjgl.opengl.GL44.glClearTexSubImage(hashTexs[f.buffer ^ 1], 0, 0, y0, 0, hashW, Math.min(rows, hashH - y0), 1, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, clearValue);
         }
         if (f.uploads > 0) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, mapTex);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            for (int i = 0; i < f.uploads; i++) {
               long bits = f.uploadBits[i], pbits = f.uploadPuddle[i];
               for (int b = 0; b < 64; b++) {
                  SLOT_BYTES.put(b, (byte)((bits >>> b & 1L) != 0L ? 255 : (pbits >>> b & 1L) != 0L ? 128 : 0));
               }
               int slot = f.uploadSlot[i];
               GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, (slot % SLOTS) * 8, (slot / SLOTS) * 8, 8, 8, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, SLOT_BYTES);
            }
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
         }
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         Texture_lastReset(); // the sprite renderer binds again what it needs
         org.lwjgl.opengl.GL42.glBindImageTexture(HASH_IMAGE_UNIT, hashTex, 0, false, 0, org.lwjgl.opengl.GL15.GL_READ_WRITE, GL30.GL_R32UI);
         org.lwjgl.opengl.GL42.glBindImageTexture(TILE_IMAGE_UNIT, tileTex, 0, false, 0, org.lwjgl.opengl.GL15.GL_READ_WRITE, GL30.GL_R32UI);
         // (no glGet: the sprite renderer works on unit 0, restored below)
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + WATER_MAP_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, mapTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         renderFrame = f;
         serial++;
         scatterFrames++;
      } catch (Throwable t) {
         failed = true;
         Log.warn("ssr: frame setup failed, reflections off: " + t);
      }
   }

   private static boolean scatterOn; // game thread

   private static final TextureDraw.GenericDrawer OFF = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         renderFrame = null;
         serial++;
      }
   };

   private static void Texture_lastReset() {
      zombie.core.textures.Texture.lastTextureID = -1;
   }

   private static final java.util.HashMap<Integer, Integer> appliedSerial = new java.util.HashMap<>();

   /**
    * Render thread, ChunkRenderShader.startRenderThread (the composite program bound): the scatter's uniforms, once per
    * program per frame, from the viewport as drawn.
    */
   /** The scatter switch of a chunk texture's draws: 1 water within reach, 0.5 only puddles (half resolution), 0 none. */
   private static float switchOf(Frame f, Object tex) {
      Boolean b = f.nearWater.get(tex);
      return b == null ? 0F : b ? 1F : 0.5F;
   }

   // ---- render thread GL state without glGet: NVIDIA's threaded driver syncs its worker thread on every glGet, and a
   // per-draw query (the chunk composite draws hundreds of times a frame) showed up in the frame-time tail
   private static final java.lang.invoke.VarHandle BOUND;

   static {
      java.lang.invoke.VarHandle h = null;
      try {
         h = java.lang.invoke.MethodHandles.privateLookupIn(zombie.core.ShaderHelper.class, java.lang.invoke.MethodHandles.lookup())
            .findStaticVarHandle(zombie.core.ShaderHelper.class, "currentlyBound", int.class);
      } catch (ReflectiveOperationException | RuntimeException e) {
         Log.warn("ssr: ShaderHelper.currentlyBound not readable (" + e + "); the bound program is queried from GL");
      }
      BOUND = h;
   }

   /** The program bound through ShaderHelper (the game binds every program through it), else asked from GL. */
   static int boundProgram() {
      int p = BOUND != null ? (int)BOUND.get() : -1;
      return p > 0 ? p : GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
   }

   static final String[] UNIFORMS = {"pzSsrMapA", "pzSsrMapC", "pzSsrO", "pzSsrHash", "pzSsrTiles", "pzSsrFrame", "pzSsrMapMin", "pzSsrReach",
      "pzSsrWater", "pzSsrColor", "pzSsrDepth", "pzSsrP", "pzSsrQ", "pzSsrK"};
   static final int U_MAPA = 0, U_MAPC = 1, U_O = 2, U_HASH = 3, U_TILES = 4, U_FRAME = 5, U_MAPMIN = 6, U_REACH = 7, U_WATER = 8, U_COLOR = 9,
      U_DEPTH = 10, U_P = 11, U_Q = 12, U_K = 13;
   private static final java.util.HashMap<Integer, int[]> LOCATIONS = new java.util.HashMap<>();

   /** A program's uniform locations, looked up once. */
   private static int[] locations(int prog) {
      int[] l = LOCATIONS.get(prog);
      if (l == null) {
         l = new int[UNIFORMS.length];
         for (int i = 0; i < l.length; i++) {
            l[i] = GL20.glGetUniformLocation(prog, UNIFORMS[i]);
         }
         LOCATIONS.put(prog, l);
      }
      return l;
   }

   private static int viewportSerial = -1;

   /** The viewport once a frame (the composite, the water and the moving pass draw into the same one). */
   private static void viewport() {
      if (viewportSerial != serial) {
         viewportSerial = serial;
         GL11.glGetIntegerv(GL11.GL_VIEWPORT, VPI);
         VP[0] = VPI[0];
         VP[1] = VPI[1];
         VP[2] = VPI[2];
         VP[3] = VPI[3];
      }
   }

   /** per program: the scatter switch's location and the value it holds (-1 unknown) */
   private static final java.util.HashMap<Integer, float[]> programSwitch = new java.util.HashMap<>();

   public static void chunkDraw(TextureDraw texd) {
      if (!scatterPatched) {
         return;
      }
      Frame f = renderFrame;
      try {
         int prog = boundProgram();
         Integer applied = appliedSerial.get(prog);
         if (applied != null && applied == serial) {
            // same frame: only the per-texture switch (the scatter runs for the chunk textures near water)
            float[] sw = programSwitch.get(prog);
            if (sw != null && f != null) {
               float want = f.view.on && (Config.DEV_SSR_SKIP & 8) == 0 ? switchOf(f, texd.tex1) : 0F;
               if (want != sw[1]) {
                  sw[1] = want;
                  GL20.glUniform4f((int)sw[0], sw[2], sw[3], 0F, want);
               }
            }
            return;
         }
         appliedSerial.put(prog, serial);
         int[] l = locations(prog);
         int uMapC = l[U_MAPC];
         if (uMapC < 0) {
            return;
         }
         if (f == null) {
            GL20.glUniform4f(uMapC, 0F, 0F, 0F, 0F);
            programSwitch.remove(prog);
            return;
         }
         viewport();
         f.view.mapping(VP, MAP_K);
         float sw = f.view.on && active() && (Config.DEV_SSR_SKIP & 8) == 0 ? switchOf(f, texd.tex1) : 0F;
         GL20.glUniform4f(l[U_MAPA], MAP_K[0], MAP_K[1], MAP_K[2], MAP_K[3]);
         GL20.glUniform4f(uMapC, MAP_K[4], MAP_K[5], 0F, sw);
         float[] swState = programSwitch.get(prog);
         if (swState == null) {
            programSwitch.put(prog, swState = new float[4]);
         }
         swState[0] = uMapC;
         swState[1] = sw;
         swState[2] = MAP_K[4];
         swState[3] = MAP_K[5];
         GL20.glUniform4f(l[U_O], Math.floorMod(f.view.ox, MAP_SIZE), Math.floorMod(f.view.oy, MAP_SIZE), MAP_SIZE, f.epoch);
         GL20.glUniform1i(l[U_HASH], HASH_IMAGE_UNIT);
         GL20.glUniform1i(l[U_TILES], TILE_IMAGE_UNIT);
         GL20.glUniform1i(l[U_FRAME], f.stamp);
         GL20.glUniform1f(l[U_MAPMIN], f.puddles ? 0.4F : 0.75F);
         GL20.glUniform1f(l[U_REACH], reachRows(f.view, VP));
         GL20.glUniform1i(l[U_WATER], WATER_MAP_UNIT);
      } catch (Throwable t) {
         failed = true;
         Log.warn("ssr: composite uniforms failed, reflections off: " + t);
      }
   }

   private static final float[] MAP_K = new float[6];

   /** Window px of one level of height in this viewport. */
   static float pxPerLevel(View view, float[] vp) {
      return 96F * view.ts / view.zoom * (vp[3] / Math.max(1F, view.screenH));
   }

   /** The rows a reflection may span before the resolve's distance fade has taken it out (reach: 2 x height on screen). */
   static float reachRows(View view, float[] vp) {
      return Math.min(2047F, 2F * pxPerLevel(view, vp) * Config.SSR_REACH_PCT / 100F + 2F);
   }

   // ------------------------------------------------------------------------------------------------ per frame

   private static final View[] VIEWS = {new View(), new View(), new View(), new View()};
   private static int viewIndex;
   private static View renderView; // render thread: this frame's camera, set by BEFORE ahead of the water draws

   private static final class Before extends TextureDraw.GenericDrawer {
      View view;

      @Override
      public void render() {
         renderView = this.view;
         charsPending = true; // the moving objects were drawn since any barrier
         if (Config.DEV_SSR_TIMING) {
            Timing.begin(this.view.on);
         }
      }
   }

   /**
    * dev (devSsrTiming): GPU time of the water pass (every water draw of the frame, the reflection lookups included), summed
    * apart for the frames with the reflections on and off (devSsrAlternate), one log line every 600 frames.
    */
   static final class Timing {
      private static int[] q;
      private static final boolean[] slotOn = new boolean[16];
      private static int slot, used;
      private static boolean open, openOn;
      private static final long[] ns = new long[2], n = new long[2];
      private static long lines;

      private static int[] qc;
      private static final boolean[] slotOnC = new boolean[16];
      private static int slotC, usedC;
      private static boolean openC;
      private static final long[] nsC = new long[2], nC = new long[2];

      static void beginComposite(boolean on) {
         if (qc == null) {
            qc = new int[32];
            org.lwjgl.opengl.GL15.glGenQueries(qc);
         }
         if (usedC >= 16) {
            int b = slotC * 2;
            if (org.lwjgl.opengl.GL15.glGetQueryObjecti(qc[b + 1], org.lwjgl.opengl.GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
               long t = org.lwjgl.opengl.GL33.glGetQueryObjecti64(qc[b + 1], org.lwjgl.opengl.GL15.GL_QUERY_RESULT)
                     - org.lwjgl.opengl.GL33.glGetQueryObjecti64(qc[b], org.lwjgl.opengl.GL15.GL_QUERY_RESULT);
               int k = slotOnC[slotC] ? 1 : 0;
               nsC[k] += t;
               nC[k]++;
            }
         }
         org.lwjgl.opengl.GL33.glQueryCounter(qc[slotC * 2], org.lwjgl.opengl.GL33.GL_TIMESTAMP);
         slotOnC[slotC] = on;
         openC = true;
      }

      static void endComposite() {
         if (!openC) {
            return;
         }
         openC = false;
         org.lwjgl.opengl.GL33.glQueryCounter(qc[slotC * 2 + 1], org.lwjgl.opengl.GL33.GL_TIMESTAMP);
         slotC = (slotC + 1) & 15;
         usedC = Math.min(16, usedC + 1);
      }

      static String composite() {
         String s = String.format(" | composite (frame setup + chunk composite) on=%.2f off=%.2f delta=%.2f", nC[1] > 0 ? nsC[1] / 1e3 / nC[1] : 0.0,
               nC[0] > 0 ? nsC[0] / 1e3 / nC[0] : 0.0, nC[1] > 0 && nC[0] > 0 ? nsC[1] / 1e3 / nC[1] - nsC[0] / 1e3 / nC[0] : 0.0);
         nsC[0] = nsC[1] = nC[0] = nC[1] = 0;
         return s;
      }

      static void begin(boolean on) {
         if (q == null) {
            q = new int[32];
            org.lwjgl.opengl.GL15.glGenQueries(q);
         }
         collect();
         int b = slot * 2;
         org.lwjgl.opengl.GL33.glQueryCounter(q[b], org.lwjgl.opengl.GL33.GL_TIMESTAMP);
         slotOn[slot] = on;
         open = true;
         openOn = on;
      }

      static void end() {
         if (!open) {
            return;
         }
         open = false;
         org.lwjgl.opengl.GL33.glQueryCounter(q[slot * 2 + 1], org.lwjgl.opengl.GL33.GL_TIMESTAMP);
         slot = (slot + 1) & 15;
         used = Math.min(16, used + 1);
      }

      /** the slot about to be reused: its result is long available (15 frames later) */
      private static void collect() {
         if (used < 16) {
            return;
         }
         int b = slot * 2;
         if (org.lwjgl.opengl.GL15.glGetQueryObjecti(q[b + 1], org.lwjgl.opengl.GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
            return;
         }
         long t = org.lwjgl.opengl.GL33.glGetQueryObjecti64(q[b + 1], org.lwjgl.opengl.GL15.GL_QUERY_RESULT)
               - org.lwjgl.opengl.GL33.glGetQueryObjecti64(q[b], org.lwjgl.opengl.GL15.GL_QUERY_RESULT);
         int k = slotOn[slot] ? 1 : 0;
         ns[k] += t;
         n[k]++;
         if (n[0] + n[1] >= 600) {
            Log.info(String.format("ssr timing: water pass gpu us/frame on=%.2f (%d frames) off=%.2f (%d frames) delta=%.2f | %s",
                  n[1] > 0 ? ns[1] / 1e3 / n[1] : 0.0, n[1], n[0] > 0 ? ns[0] / 1e3 / n[0] : 0.0, n[0],
                  n[1] > 0 && n[0] > 0 ? ns[1] / 1e3 / n[1] - ns[0] / 1e3 / n[0] : 0.0, stats()) + composite());
            ns[0] = ns[1] = n[0] = n[1] = 0;
            lines++;
         }
      }
   }

   private static final Before[] BEFORES = {new Before(), new Before(), new Before(), new Before()};

   private static long alternateT0;
   private static boolean alternateOn = true; // game thread: this frame's state under devSsrAlternate (set before the composite)

   private static void alternate() {
      alternateOn = true;
      if (Config.DEV_SSR_ALTERNATE > 0) {
         // dev: the reflections on and off every period from the first frame's wall clock (the shaders run either way)
         long now = System.currentTimeMillis();
         if (alternateT0 == 0L) {
            alternateT0 = now;
            Log.info("ssr: alternating every " + Config.DEV_SSR_ALTERNATE + " ms from epoch_ms " + now + " (on first)");
         }
         alternateOn = ((now - alternateT0) / Config.DEV_SSR_ALTERNATE & 1L) == 0L;
      }
   }

   /** Game thread, FBORenderCell right before the water: this frame's camera for the water shaders' uniforms. */
   public static void beforeWater(int playerIndex) {
      if (!active()) {
         return;
      }
      if (!ppr()) {
         alternate();
      }
      int i = viewIndex++ & 3;
      View v = VIEWS[i];
      v.capture(playerIndex);
      v.on = alternateOn;
      BEFORES[i].view = v;
      frames++;
      SpriteRenderer.instance.drawGeneric(BEFORES[i]);
   }

   /** Game thread, FBORenderCell right after the chunk composite (dev timing only). */
   public static void afterComposite() {
      if (Config.DEV_SSR_TIMING && scatterOn) {
         SpriteRenderer.instance.drawGeneric(AFTER_COMPOSITE);
      }
   }

   private static final TextureDraw.GenericDrawer AFTER_COMPOSITE = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         Timing.endComposite();
      }
   };

   public static void afterWater() {
      if (!active() || viewIndex == 0) {
         return;
      }
      // the moving objects' scatter, for the next frame: the water's barrier already made them visible to the reads, so this
      // pass costs no pipeline drain of its own (their reflections are one frame late)
      queueMoving();
      SpriteRenderer.instance.drawGeneric(AFTER);
   }

   private static boolean bound;
   private static final TextureDraw.GenericDrawer AFTER = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         if (Config.DEV_SSR_TIMING) {
            Timing.end();
         }
         if (bound) {
            bound = false;
            // (no glGet: the sprite renderer works on unit 0, restored below)
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + COLOR_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
         }
      }
   };

   private static int cachedFbo = -1, cachedColor, cachedDepth, barrierSerial = -1;
   private static boolean charsPending; // render thread: characters drawn since the last barrier
   private static final float[] VP = new float[4], MAP = new float[6];
   private static final int[] VPI = new int[4];

   /** Render thread, PuddlesShader.updatePuddlesParams (the puddle program bound): as the water's, level 0 only. */
   public static void puddleUniforms(int z) {
      if (z != 0 || !Config.SSR_PUDDLES) {
         int prog = boundProgram();
         int u = prog <= 0 ? -1 : locations(prog)[U_MAPC];
         if (u >= 0) {
            GL20.glUniform4f(u, 0F, 0F, 0F, 0F);
         }
         return;
      }
      surfaceUniforms();
   }

   /** Render thread, WaterShader.updateWaterParams (the water program bound): the world textures and the mapping. */
   public static void surfaceUniforms() {
      int prog = boundProgram();
      if (prog <= 0) {
         return;
      }
      int[] l = locations(prog);
      int uMapC = l[U_MAPC];
      if (uMapC < 0) {
         return; // not a patched shader
      }
      Frame cur = renderFrame;
      View view = cur != null && ppr() ? cur.view : renderView; // the puddles draw before the water's camera is set
      if (view != null && !view.on && Config.DEV_SSR_BARRIER_OFF && active()) {
         GL45.glTextureBarrier(); // dev: the off frames pay the barrier too (the delta is the lookups alone)
      }
      if (view == null || !view.on || !active() || HdrGlint.glintOnlyNow()) {
         GL20.glUniform4f(uMapC, 0F, 0F, 0F, 0F);
         return;
      }
      try {
         // the world framebuffer the frame's setup found (the water draws into it); asked only without that setup (march)
         int fbo = renderFrame != null && pprFbo > 0 ? pprFbo : GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
         if (fbo != cachedFbo) { // the attachment queries are round trips: once per framebuffer
            cachedFbo = fbo;
            cachedColor = attachment(fbo, GL30.GL_COLOR_ATTACHMENT0);
            cachedDepth = attachment(fbo, GL30.GL_DEPTH_ATTACHMENT);
            Log.info("ssr: world framebuffer " + fbo + " colour texture " + cachedColor + " depth texture " + cachedDepth);
         }
         if (cachedColor == 0 || cachedDepth == 0) {
            noTarget++;
            GL20.glUniform4f(uMapC, 0F, 0F, 0F, 0F);
            return;
         }
         viewport();
         view.mapping(VP, MAP);
         Frame pf = renderFrame;
         boolean usePpr = pf != null && ppr();
         if ((Config.DEV_SSR_SKIP & 4) == 0 && (barrierSerial != serial || charsPending)) {
            // once for the composite's keys (the puddles' draw or the water's), once more after the characters (the water):
            // the shore and later water draws write only surface pixels, which nothing reads
            barrierSerial = serial;
            charsPending = false;
            GL45.glTextureBarrier();
            if (usePpr) {
               org.lwjgl.opengl.GL42.glMemoryBarrier(org.lwjgl.opengl.GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            }
         }
         // (no glGet: the sprite renderer works on unit 0, restored below)
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + COLOR_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, cachedColor);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, cachedDepth);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         bound = true;
         GL20.glUniform1i(l[U_COLOR], COLOR_UNIT);
         GL20.glUniform1i(l[U_DEPTH], DEPTH_UNIT);
         GL20.glUniform4f(l[U_MAPA], MAP[0], MAP[1], MAP[2], MAP[3]);
         GL20.glUniform4f(uMapC, MAP[4], MAP[5], 0F, (Config.DEV_SSR_SKIP & 2) != 0 ? 0F : Config.SSR_STRENGTH_PCT / 100F);
         float pxPerLevel = pxPerLevel(view, VP);
         GL20.glUniform4f(l[U_P], Config.SSR_STRIDE, Config.SSR_STEPS, Config.SSR_REFINE, Config.SSR_DISTORT_PCT / 100F * pxPerLevel * 0.1F);
         GL20.glUniform4f(l[U_Q], Math.max(8F, Config.SSR_STRIDE * Config.SSR_STEPS), VP[1] + VP[3], Config.SSR_THICKNESS_PCT / 100F, Config.DEV_SSR_VIEW);
         GL20.glUniform4f(l[U_K], usePpr ? 1F : 0F, usePpr ? pf.epoch : 0F, reachRows(view, VP), 0.14F);
         GL20.glUniform1i(l[U_HASH], HASH_IMAGE_UNIT);
         GL20.glUniform1i(l[U_TILES], TILE_IMAGE_UNIT);
         GL20.glUniform1i(l[U_FRAME], usePpr ? pf.stamp : -1);
         draws++;
      } catch (Throwable t) {
         failed = true;
         Log.warn("ssr: reflections failed, off: " + t);
         GL20.glUniform4f(uMapC, 0F, 0F, 0F, 0F);
      }
   }

   private static int attachment(int fbo, int attachment) {
      if (fbo == 0) {
         return 0;
      }
      int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
      if (type != GL11.GL_TEXTURE) {
         return 0;
      }
      return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
   }

   public static String stats() {
      return "ssr: chunk textures near water " + chunksNear + " / far " + chunksFar + ", frames " + frames + ", water draws " + draws + ", scatter frames " + scatterFrames + ", moving " + movingAdded + " near water / " + movingSkipped
            + " not, " + movingDraws + " moving passes" + ", no target " + noTarget + (failed ? ", failed" : "");
   }

   /** Game thread: the camera facts a render-thread pass needs to map window px + depth to the world (as CapsuleShadow). */
   static final class View {
      boolean on = true;
      int ox, oy;
      float zoom, ts, screenW, screenH, offX, offY, d0;

      void capture(int playerIndex) {
         this.ox = (int)Math.floor(IsoCamera.frameState.camCharacterX);
         this.oy = (int)Math.floor(IsoCamera.frameState.camCharacterY);
         Core core = Core.getInstance();
         this.zoom = core.getZoom(playerIndex);
         this.ts = Core.tileScale;
         this.screenW = IsoCamera.getScreenWidth(playerIndex);
         this.screenH = IsoCamera.getScreenHeight(playerIndex);
         this.offX = IsoCamera.getOffX();
         this.offY = IsoCamera.getOffY();
         this.d0 = IsoDepthHelper.getSquareDepthData(this.ox, this.oy, this.ox, this.oy, 0.0F).depthStart;
      }

      /**
       * Render thread, viewport (x, y, w, h) in window px: out = {kA, cA, kB, cB, kC, cC} with u = kA px + cA,
       * v = kB py + cB, w = kC depth + cC, relative to the origin square (ox, oy).
       */
      void mapping(float[] vp, float[] out) {
         double vx = vp[0], vy = vp[1], vw = vp[2], vh = vp[3];
         double sxPerPx = this.screenW / vw, syPerPx = this.screenH / vh;
         double a32 = 32.0 * this.ts, a16 = 16.0 * this.ts;
         out[0] = (float)(sxPerPx * this.zoom / a32);
         out[1] = (float)(((-vx * sxPerPx) * this.zoom + this.offX) / a32 - (this.ox - this.oy));
         out[2] = (float)(-syPerPx * this.zoom / a16);
         out[3] = (float)((((vy + vh) * syPerPx) * this.zoom + this.offY) / a16 - (this.ox + this.oy));
         out[4] = (float)(-1.0 / PixelLight.DEPTH_PER_XY);
         out[5] = (float)(this.d0 / PixelLight.DEPTH_PER_XY);
      }
   }

   // ---------------------------------------------------------------------------------------------------------------
   // dev: devSsrDumpAt=<s>[,<s>...] writes the world colour + depth right before the puddles and the water draw, and the
   // colour right after each (the difference is the surface mask), to ~/Zomboid/pzopt-ssr/, for the offline rigs
   // (harness/ssr/). Seconds after the world is up.
   private static float[] dumpAt;
   private static int dumpAtNext;
   private static long worldUpNs;
   private static String dumpTag;

   /** Game thread, FBORenderCell before the puddles (stage "puddles") and the water ("water"); {@code after} right after. */
   public static void devDump(int playerIndex, String stage, boolean after) {
      if (Config.DEV_SSR_DUMP_AT.isEmpty()) {
         return;
      }
      if (!after && "puddles".equals(stage)) {
         dumpTag = null;
         if (dumpAt == null) {
            String[] parts = Config.DEV_SSR_DUMP_AT.split(",");
            dumpAt = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
               dumpAt[i] = Float.parseFloat(parts[i].trim());
            }
         }
         if (dumpAtNext >= dumpAt.length || IsoWorld.instance == null || IsoWorld.instance.currentCell == null) {
            return;
         }
         long now = System.nanoTime();
         if (worldUpNs == 0L) {
            worldUpNs = now;
         }
         if ((now - worldUpNs) / 1e9 < dumpAt[dumpAtNext]) {
            return;
         }
         dumpTag = "t" + (int)dumpAt[dumpAtNext++];
      }
      if (dumpTag == null) {
         return;
      }
      Dump d = new Dump();
      d.tag = dumpTag + "-" + stage + (after ? "-after" : "-before");
      d.depth = !after;
      d.view.capture(playerIndex);
      SpriteRenderer.instance.drawGeneric(d);
   }

   static File dir() {
      File d = new File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt-ssr");
      d.mkdirs();
      return d;
   }

   static final class Dump extends TextureDraw.GenericDrawer {
      String tag;
      boolean depth;
      final View view = new View();

      @Override
      public void render() {
         try {
            int[] vp = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
            int vx = vp[0], vy = vp[1], vw = vp[2], vh = vp[3];
            int sceneFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sceneFbo);
            if (this.depth) {
               FloatBuffer depthBuf = BufferUtils.createFloatBuffer(vw * vh);
               GL11.glReadPixels(vx, vy, vw, vh, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depthBuf);
               ByteBuffer db = ByteBuffer.allocate(vw * vh * 4).order(ByteOrder.LITTLE_ENDIAN);
               db.asFloatBuffer().put(depthBuf);
               Files.write(new File(dir(), this.tag + "-depth.bin").toPath(), db.array());
            }
            ByteBuffer color = BufferUtils.createByteBuffer(vw * vh * 4);
            GL11.glReadPixels(vx, vy, vw, vh, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, color);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            byte[] cb = new byte[vw * vh * 4];
            color.get(cb);
            Files.write(new File(dir(), this.tag + "-color.bin").toPath(), cb);
            float[] vpf = {vx, vy, vw, vh};
            float[] m = new float[6];
            this.view.mapping(vpf, m);
            StringBuilder sb = new StringBuilder();
            sb.append("w=").append(vw).append("\nh=").append(vh).append("\nvx=").append(vx).append("\nvy=").append(vy).append("\nfbo=").append(sceneFbo).append('\n');
            sb.append("kA=").append(m[0]).append("\ncA=").append(m[1]).append("\nkB=").append(m[2]).append("\ncB=").append(m[3]).append("\nkC=").append(m[4]).append("\ncC=").append(m[5]).append('\n');
            sb.append("ox=").append(this.view.ox).append("\noy=").append(this.view.oy).append("\nzoom=").append(this.view.zoom).append("\nts=").append(this.view.ts).append('\n');
            Frame rf = renderFrame;
            if (rf != null && hashTex != 0 && tileTex != 0) {
               // the reflection state as the water read it: this frame's hash and the tile map, raw R32UI
               ByteBuffer hb = BufferUtils.createByteBuffer(hashW * hashH * 4);
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, hashTex);
               GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, hb);
               byte[] ha = new byte[hb.capacity()];
               hb.get(ha);
               Files.write(new File(dir(), this.tag + "-hash.bin").toPath(), ha);
               int tw = (hashW + 7) / 8, th = (hashH + 7) / 8;
               ByteBuffer tb = BufferUtils.createByteBuffer(tw * th * 4);
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, tileTex);
               GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, tb);
               byte[] ta = new byte[tb.capacity()];
               tb.get(ta);
               Files.write(new File(dir(), this.tag + "-tiles.bin").toPath(), ta);
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
               zombie.core.textures.Texture.lastTextureID = -1;
               sb.append("hashW=").append(hashW).append("\nhashH=").append(hashH).append("\nstamp=").append(rf.stamp).append("\nepoch=").append(rf.epoch)
                     .append("\nbuffer=").append(rf.buffer).append("\non=").append(rf.view.on).append('\n');
            }
            Files.writeString(new File(dir(), this.tag + "-view.txt").toPath(), sb.toString());
            Log.info("ssr: dump " + this.tag + ": " + vw + "x" + vh + " at " + vx + "," + vy + " fbo " + sceneFbo);
         } catch (Throwable t) {
            Log.warn("ssr: dump " + this.tag + " failed: " + t);
         }
      }
   }
}
