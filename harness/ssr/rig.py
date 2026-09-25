#!/usr/bin/env python3
"""Offline rig for the screen-space reflections (pzopt.Ssr): real frames, the GPU, one variant at a time.

    rig.py <dump prefix> [--variant march|hiz|sspr|...] [--time N] [--out out.png] [--crop x0,y0,x1,y1]

<dump prefix> is a devSsrDumpAt file set, e.g. ~/Zomboid/pzopt-ssr/t12-water (the -before-color/-depth, -after-color and
-view.txt files). The water mask is where the water draw changed the frame. Needs moderngl (headless EGL):
python -m venv /tmp/cs/venv && /tmp/cs/venv/bin/pip install moderngl numpy pillow.

The water pixels are drawn through the stencil (as the water shader's own fragments would run), every variant on the same
frame; --time N prints the GPU time per frame of each variant minus the stencil-limited base draw (N frames each).

Frame of the mapping (Ssr.View): u = x - y (kA px + cA), v = x + y - 6z (kB py + cB), w = x + y + 2z (kC depth + cC), z in
levels. A reflected ray keeps g = w - v / 3 constant and runs straight up its column; the water pixel's own g is
2/3 v + 8 z_water. A scene pixel is on or in front of the ray where its g >= the ray's.
"""
import argparse, os, sys, time
import numpy as np
import moderngl
from PIL import Image

ap = argparse.ArgumentParser()
ap.add_argument("prefix")
ap.add_argument("--variant", default="all")
ap.add_argument("--time", type=int, default=0)
ap.add_argument("--out", default="")
ap.add_argument("--crop", default="")
ap.add_argument("--zw", type=float, default=0.0, help="water plane height, levels")
ap.add_argument("--strength", type=float, default=0.6)
args = ap.parse_args()

pre = args.prefix
view = dict(l.split("=", 1) for l in open(pre + "-before-view.txt").read().split())
W, H = int(view["w"]), int(view["h"])
K = {k: float(view[k]) for k in ("kA", "cA", "kB", "cB", "kC", "cC")}
VY = float(view["vy"])
before = np.fromfile(pre + "-before-color.bin", np.uint8).reshape(H, W, 4)
after = np.fromfile(pre + "-after-color.bin", np.uint8).reshape(H, W, 4)
depth = np.fromfile(pre + "-before-depth.bin", np.float32).reshape(H, W)
mask = np.abs(before.astype(np.int16) - after).max(-1) > 0
print(f"frame {W}x{H}, water {mask.mean() * 100:.1f} % of the pixels, zoom {view.get('zoom')}", file=sys.stderr)

ctx = moderngl.create_standalone_context(backend="egl", require=460)
tex_color = ctx.texture((W, H), 4, before.tobytes())
tex_after = ctx.texture((W, H), 4, after.tobytes())
tex_depth = ctx.texture((W, H), 1, depth.tobytes(), dtype="f4")
for t in (tex_color, tex_after, tex_depth):
    t.filter = (moderngl.NEAREST, moderngl.NEAREST)
    t.repeat_x = t.repeat_y = False
out_tex = ctx.texture((W, H), 4)
# the water pixels: an early depth test (moderngl has no stencil helper), depth 0 where water, 1 elsewhere
mask_depth = np.where(mask, 0.0, 1.0).astype(np.float32)
fbo = ctx.framebuffer(color_attachments=[out_tex], depth_attachment=ctx.depth_texture((W, H), mask_depth.tobytes()))
fbo.depth_mask = False  # the mask stays for every variant
# depth test GREATER on a quad at 0.5: passes where the stored depth is 0 (water); early-z culls the rest

QUAD_VS = """
#version 460
in vec2 pos;
void main() { gl_Position = vec4(pos, 0.0, 1.0); }
"""
quad = ctx.buffer(np.array([-1, -1, 1, -1, -1, 1, 1, 1], np.float32).tobytes())

COMMON = """
#version 460
layout(early_fragment_tests) in;
uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
uniform sampler2D AfterColor;
uniform vec4 mapA;  // kA, cA, kB, cB
uniform vec4 mapC;  // kC, cC, z water, strength
uniform vec2 vp;    // viewport origin y
out vec4 fragColor;
float vOf(float py) { return mapA.z * py + mapA.w; }
float gAt(ivec2 p) {
   float d = texelFetch(SceneDepth, p, 0).r;
   return mapC.x * d + mapC.y - vOf(float(p.y) + 0.5 + vp.y) / 3.0;
}
float gWater(float py) { return 2.0 / 3.0 * vOf(py + vp.y) + 8.0 * mapC.z; }
vec4 finish(vec3 refl, float a) {
   vec3 base = texelFetch(AfterColor, ivec2(gl_FragCoord.xy), 0).rgb;
   return vec4(mix(base, refl, a * mapC.w), 1.0);
}
"""

BASE_FS = COMMON + """
void main() { fragColor = finish(vec3(0.0), 0.0); }
"""

# 1. linear march up the column, then a binary refinement between the last miss and the hit
MARCH_FS = COMMON + """
uniform ivec4 marchP; // step px, max steps, refine steps, thickness (x100)
void main() {
   ivec2 p = ivec2(gl_FragCoord.xy);
   float g0 = gWater(gl_FragCoord.y);
   int stepPx = marchP.x;
   float thick = float(marchP.w) / 100.0;
   int lo = p.y, hi = -1;
   for (int i = 1; i <= marchP.y; i++) {
      int y = p.y + i * stepPx;
      if (y >= textureSize(SceneDepth, 0).y) break;
      float g = gAt(ivec2(p.x, y));
      if (g >= g0) { hi = y; break; }
      lo = y;
   }
   if (hi < 0) { fragColor = finish(vec3(0.0), 0.0); return; }
   for (int i = 0; i < marchP.z && hi - lo > 1; i++) {
      int mid = (lo + hi) / 2;
      if (gAt(ivec2(p.x, mid)) >= g0) hi = mid; else lo = mid;
   }
   float gh = gAt(ivec2(p.x, hi));
   float a = gh - g0 < thick ? 1.0 : 0.0;
   fragColor = finish(texelFetch(SceneColor, ivec2(p.x, hi), 0).rgb, a);
}
"""

# 1b. the game's own lookup (pzopt.Ssr.WATER_GLSL, extracted from the Java), on a flat water normal
def java_glsl(name):
    import re
    src = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "src", "pzopt", "pzopt", "Ssr.java")).read()
    m = re.search(name + r' = String.join\("\\n",(.*?)\);\n', src, re.S)
    return "\n".join(l.encode().decode("unicode_escape") for l in re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))) + "\n"


GAME_FS = """
#version 460
#define texture2D texture
layout(early_fragment_tests) in;
uniform sampler2D AfterColor;
out vec4 fragColor;
""" + java_glsl("WATER_GLSL") + """
void main() {
   vec4 c = texelFetch(AfterColor, ivec2(gl_FragCoord.xy), 0);
   c.a = 1.0;
   fragColor = vec4(pzSsrApply(c, vec3(0.0, 1.0, 0.0)).rgb, 1.0);
}
"""

# 2. Hi-Z: a max pyramid of g along y only (level k = max over 2^k rows), in an atlas of R32F rows; exact traversal
HIZ_BUILD0_CS = """
#version 460
layout(local_size_x = 64, local_size_y = 4) in;
layout(r32f, binding = 0) uniform writeonly image2D Atlas;
uniform sampler2D SceneDepth;
uniform vec4 mapA; uniform vec4 mapC; uniform vec2 vp;
uniform int H;
float gAt(ivec2 p) {
   float d = texelFetch(SceneDepth, p, 0).r;
   return mapC.x * d + mapC.y - (mapA.z * (float(p.y) + 0.5 + vp.y) + mapA.w) / 3.0;
}
void main() {
   ivec2 o = ivec2(gl_GlobalInvocationID.xy); // level 1 texel
   int y0 = o.y * 2;
   if (o.x >= textureSize(SceneDepth, 0).x || y0 >= H) return;
   float g = gAt(ivec2(o.x, y0));
   if (y0 + 1 < H) g = max(g, gAt(ivec2(o.x, y0 + 1)));
   imageStore(Atlas, o, vec4(g));
}
"""
HIZ_BUILD_CS = """
#version 460
layout(local_size_x = 64, local_size_y = 4) in;
layout(r32f, binding = 0) uniform image2D Atlas;
uniform ivec3 lv; // source row offset, source rows, destination row offset
void main() {
   ivec2 o = ivec2(gl_GlobalInvocationID.xy);
   int n = (lv.y + 1) / 2;
   if (o.x >= imageSize(Atlas).x || o.y >= n) return;
   float g = imageLoad(Atlas, ivec2(o.x, lv.x + o.y * 2)).r;
   if (o.y * 2 + 1 < lv.y) g = max(g, imageLoad(Atlas, ivec2(o.x, lv.x + o.y * 2 + 1)).r);
   imageStore(Atlas, ivec2(o.x, lv.z + o.y), vec4(g));
}
"""
HIZ_FS = COMMON + """
uniform sampler2D Atlas;
uniform int offs[16];
uniform int levels;
uniform ivec4 marchP; // -, max iterations, -, thickness x100
float level(int l, int x, int b) { return l == 0 ? gAt(ivec2(x, b)) : texelFetch(Atlas, ivec2(x, offs[l] + b), 0).r; }
void main() {
   ivec2 p = ivec2(gl_FragCoord.xy);
   int Hh = textureSize(SceneDepth, 0).y;
   float g0 = gWater(gl_FragCoord.y);
   int r = p.y + 1, l = 0, hit = -1;
   for (int i = 0; i < marchP.y && r < Hh; i++) {
      int b = r >> l;
      if (level(l, p.x, b) < g0) {
         r = (b + 1) << l;
         if (((b + 1) & 1) == 0 && l + 1 < levels) l++;
      } else if (l == 0) {
         hit = r;
         break;
      } else {
         l--;
      }
   }
   if (hit < 0) { fragColor = finish(vec3(0.0), 0.0); return; }
   float a = gAt(ivec2(p.x, hit)) - g0 < float(marchP.w) / 100.0 ? 1.0 : 0.0;
   fragColor = finish(texelFetch(SceneColor, ivec2(p.x, hit), 0).rgb, a);
}
"""

# 3. SSPR (pixel-projected reflections): every scene pixel above the plane writes its row into the pixel it mirrors to
# (same column, 12 (z - z water) units of v below); the lowest source row wins (the first surface the ray meets)
SSPR_SCATTER_CS = """
#version 460
layout(local_size_x = 64, local_size_y = 4) in;
layout(r32ui, binding = 0) uniform uimage2D Hash;
uniform sampler2D SceneDepth;
uniform usampler2D Mask;
uniform vec4 mapA; uniform vec4 mapC; uniform vec2 vp;
uniform ivec4 region; // x0, y0, x1, y1 of the source pixels
void main() {
   ivec2 p = ivec2(gl_GlobalInvocationID.xy) + region.xy;
   if (p.x >= region.z || p.y >= region.w) return;
   float d = texelFetch(SceneDepth, p, 0).r;
   if (d >= 1.0) return;
   float v = mapA.z * (float(p.y) + 0.5 + vp.y) + mapA.w;
   float w = mapC.x * d + mapC.y;
   float h = (w - v) / 8.0 - mapC.z;
   if (h <= 0.02) return;
   float vt = v + 12.0 * h;
   int yt = int(floor((vt - mapA.w) / mapA.z - vp.y));
   if (yt < 0 || yt >= p.y) return;
   if (texelFetch(Mask, ivec2(p.x, yt), 0).r == 0u) return;
   imageAtomicMin(Hash, ivec2(p.x, yt), uint(p.y));
}
"""
SSPR_FS = COMMON + """
uniform usampler2D Hash;
void main() {
   ivec2 p = ivec2(gl_FragCoord.xy);
   uint s = texelFetch(Hash, p, 0).r;
   if (s == 0xffffffffu) { // a one-row gap (surfaces steeper than a wall): the neighbour's source, shifted
      uint s1 = texelFetch(Hash, p + ivec2(0, 1), 0).r;
      s = s1 != 0xffffffffu ? s1 + 1u : s1;
   }
   if (s == 0xffffffffu) { fragColor = finish(vec3(0.0), 0.0); return; }
   fragColor = finish(texelFetch(SceneColor, ivec2(p.x, int(s)), 0).rgb, 1.0);
}
"""


# 4. the scatter fused into the chunk composite: a full-screen composite-like draw (colour + depth in, colour out) whose
# fragments also write their mirrored key (distance to the target row in the top 11 bits, the colour in the low 21)
COMPOSITE_FS = """
#version 460
uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
uniform usampler2D Mask;
uniform vec4 mapA; uniform vec4 mapC; uniform vec2 vp;
uniform int scatter;
layout(r32ui, binding = 0) uniform uimage2D Hash;
out vec4 fragColor;
void main() {
   ivec2 p = ivec2(gl_FragCoord.xy);
   vec4 c = texelFetch(SceneColor, p, 0);
   float d = texelFetch(SceneDepth, p, 0).r;
   gl_FragDepth = d;
   fragColor = c;
   if (scatter == 0 || d >= 1.0) return;
   float v = mapA.z * gl_FragCoord.y + mapA.w;
   float h = (mapC.x * d + mapC.y - v) / 8.0 - mapC.z;
   if (h <= 0.02) return;
   int dist = int(12.0 * h / -mapA.z);         // rows down to the mirror
   int yt = p.y - dist;
   if (yt < 0 || dist > 2047) return;
   if (texelFetch(Mask, ivec2(p.x >> 3, yt >> 3), 0).r == 0u) return;
   uvec3 q = uvec3(clamp(c.rgb, 0.0, 1.0) * vec3(127.0, 127.0, 127.0) + 0.5);
   imageAtomicMin(Hash, ivec2(p.x, yt), (uint(dist) << 21) | (q.r << 14) | (q.g << 7) | q.b);
}
"""


def program(fs):
    return ctx.program(vertex_shader=QUAD_VS, fragment_shader=fs)


def setu(prog, **kw):
    for k, v in kw.items():
        if k in prog:
            prog[k].value = v


def common_uniforms(prog):
    setu(prog, SceneColor=0, SceneDepth=1, AfterColor=2,
         mapA=(K["kA"], K["cA"], K["kB"], K["cB"]), mapC=(K["kC"], K["cC"], args.zw, args.strength), vp=(0.0, VY))


def bind_common():
    tex_color.use(0)
    tex_depth.use(1)
    tex_after.use(2)


def draw(prog):
    vao = ctx.vertex_array(prog, [(quad, "2f", "pos")])
    fbo.use()
    ctx.enable(moderngl.DEPTH_TEST)
    ctx.depth_func = ">"
    fbo.depth_mask = False
    vao.render(moderngl.TRIANGLE_STRIP)
    return vao


class Variant:
    name = "?"

    def prepare(self):  # passes before the water draw (timed with it)
        pass

    def water(self):  # the water draw
        pass

    def run(self):
        self.prepare()
        self.water()


class Base(Variant):
    name = "base"

    def __init__(self):
        self.p = program(BASE_FS)
        common_uniforms(self.p)
        self.vao = ctx.vertex_array(self.p, [(quad, "2f", "pos")])

    def water(self):
        bind_common()
        fbo.use()
        ctx.enable(moderngl.DEPTH_TEST)
        ctx.depth_func = ">"
        self.vao.render(moderngl.TRIANGLE_STRIP)


class March(Base):
    def __init__(self, step, steps, refine, thick):
        self.p = program(MARCH_FS)
        common_uniforms(self.p)
        setu(self.p, marchP=(step, steps, refine, int(thick * 100)))
        self.vao = ctx.vertex_array(self.p, [(quad, "2f", "pos")])
        self.name = f"march s{step}x{steps} r{refine}"


class Game(Base):
    def __init__(self, stride=8, steps=48, refine=3, thick=1.5):
        self.p = program(GAME_FS)
        setu(self.p, pzSsrColor=0, pzSsrDepth=1, AfterColor=2, pzSsrMapA=(K["kA"], K["cA"], K["kB"], K["cB"]),
             pzSsrMapC=(K["kC"], K["cC"], args.zw, args.strength), pzSsrP=(stride, steps, refine, 0.0),
             pzSsrQ=(max(8.0, stride * steps), VY + H, thick, 0.0))
        self.vao = ctx.vertex_array(self.p, [(quad, "2f", "pos")])
        self.name = f"game s{stride}x{steps} r{refine} t{thick}"


class HiZ(Base):
    def __init__(self, iters=64, thick=1e6):
        self.p = program(HIZ_FS)
        common_uniforms(self.p)
        offs, rows, off, n = [0], [], 0, H
        while n > 1 and len(offs) < 16:
            n = (n + 1) // 2
            offs.append(off)
            rows.append(n)
            off += n
        self.offs, self.rows, self.levels = offs, rows, len(offs)
        self.atlas = ctx.texture((W, off), 1, dtype="f4")
        self.atlas.filter = (moderngl.NEAREST, moderngl.NEAREST)
        self.b0 = ctx.compute_shader(HIZ_BUILD0_CS)
        setu(self.b0, SceneDepth=1, mapA=(K["kA"], K["cA"], K["kB"], K["cB"]), mapC=(K["kC"], K["cC"], args.zw, 0.0), vp=(0.0, VY), H=H)
        self.b = ctx.compute_shader(HIZ_BUILD_CS)
        setu(self.p, Atlas=3, offs=tuple(offs + [0] * (16 - len(offs))), levels=self.levels, marchP=(0, iters, 0, int(min(thick, 1e6) * 100)))
        self.vao = ctx.vertex_array(self.p, [(quad, "2f", "pos")])
        self.name = f"hiz {self.levels} levels"

    def prepare(self):
        tex_depth.use(1)
        self.atlas.bind_to_image(0, read=False, write=True)
        self.b0.run((W + 63) // 64, (self.rows[0] + 3) // 4)
        for l in range(2, self.levels):
            ctx.memory_barrier(moderngl.SHADER_IMAGE_ACCESS_BARRIER_BIT)
            self.atlas.bind_to_image(0, read=True, write=True)
            self.b["lv"].value = (self.offs[l - 1], self.rows[l - 2], self.offs[l])
            self.b.run((W + 63) // 64, (self.rows[l - 1] + 3) // 4)
        ctx.memory_barrier(moderngl.TEXTURE_FETCH_BARRIER_BIT)

    def water(self):
        self.atlas.use(3)
        super().water()


class Sspr(Base):
    def __init__(self, region=None):
        self.p = program(SSPR_FS)
        common_uniforms(self.p)
        setu(self.p, Hash=4)
        self.hash = ctx.texture((W, H), 1, dtype="u4")
        self.hash.filter = (moderngl.NEAREST, moderngl.NEAREST)
        self.mask = ctx.texture((W, H), 1, mask.astype(np.uint8).tobytes(), dtype="u1")
        self.mask.filter = (moderngl.NEAREST, moderngl.NEAREST)
        self.cs = ctx.compute_shader(SSPR_SCATTER_CS)
        self.region = region or (0, 0, W, H)
        setu(self.cs, SceneDepth=1, Mask=5, mapA=(K["kA"], K["cA"], K["kB"], K["cB"]), mapC=(K["kC"], K["cC"], args.zw, 0.0), vp=(0.0, VY), region=self.region)
        self.vao = ctx.vertex_array(self.p, [(quad, "2f", "pos")])
        self.clear = np.full((H, W), 0xFFFFFFFF, np.uint32).tobytes()
        self.name = "sspr" + ("" if region is None else f" region {region}")
        self.cleared = False

    def prepare(self):
        # glClearTexImage through a framebuffer clear of the R32UI texture
        if not hasattr(self, "hfbo"):
            self.hfbo = ctx.framebuffer(color_attachments=[self.hash])
        self._clear()
        tex_depth.use(1)
        self.mask.use(5)
        self.hash.bind_to_image(0, read=True, write=True)
        x0, y0, x1, y1 = self.region
        self.cs.run((x1 - x0 + 63) // 64, (y1 - y0 + 3) // 4)
        ctx.memory_barrier(moderngl.TEXTURE_FETCH_BARRIER_BIT)

    def _clear(self):
        # a fragment pass writing ~0 (moderngl's clear() converts to float for integer targets)
        if not hasattr(self, "cprog"):
            self.cprog = ctx.program(vertex_shader=QUAD_VS, fragment_shader="#version 460\nout uvec4 o; void main(){ o = uvec4(0xffffffffu); }")
            self.cvao = ctx.vertex_array(self.cprog, [(quad, "2f", "pos")])
        self.hfbo.use()
        ctx.disable(moderngl.DEPTH_TEST)
        self.cvao.render(moderngl.TRIANGLE_STRIP)

    def water(self):
        self.hash.use(4)
        super().water()


class Composite(Variant):
    """the composite-like pass alone (scatter=0) or with the fused scatter (scatter=1); the water draw does not run;
    allmask: every target counts as reflective (puddles everywhere, the worst case of the atomics)"""
    def __init__(self, scatter, allmask=False):
        self.p = ctx.program(vertex_shader=QUAD_VS, fragment_shader=COMPOSITE_FS)
        tiles = mask.reshape(H // 8, 8, W // 8, 8).any(axis=(1, 3)).astype(np.uint8) if H % 8 == 0 and W % 8 == 0 else None
        if allmask:
            tiles = np.ones_like(tiles)
        self.mask = ctx.texture((W // 8, H // 8), 1, tiles.tobytes(), dtype="u1")
        self.mask.filter = (moderngl.NEAREST, moderngl.NEAREST)
        self.hash = ctx.texture((W, H), 1, dtype="u4")
        self.out = ctx.texture((W, H), 4)
        self.ofbo = ctx.framebuffer(color_attachments=[self.out], depth_attachment=ctx.depth_texture((W, H)))
        setu(self.p, SceneColor=0, SceneDepth=1, Mask=5, mapA=(K["kA"], K["cA"], K["kB"], K["cB"]), mapC=(K["kC"], K["cC"], args.zw, 0.0),
             vp=(0.0, VY), scatter=scatter)
        self.vao = ctx.vertex_array(self.p, [(quad, "2f", "pos")])
        self.name = "composite" + (" + fused scatter" if scatter else " alone") + (" (all targets)" if allmask else "")

    def water(self):
        bind_common()
        self.mask.use(5)
        self.hash.bind_to_image(0, read=True, write=True)
        self.ofbo.use()
        ctx.enable(moderngl.DEPTH_TEST)
        ctx.depth_func = "<="
        self.vao.render(moderngl.TRIANGLE_STRIP)


def gpu_time(v, n):
    q = ctx.query(time=True)
    for _ in range(3):
        v.run()
    ctx.finish()
    with q:
        for _ in range(n):
            v.run()
    ctx.finish()
    return q.elapsed / n / 1000.0  # us


variants = {
    "base": lambda: Base(),
    "march": lambda: March(4, 96, 3, 1e6),
    "march8": lambda: March(8, 64, 4, 1e6),
    "march16": lambda: March(16, 32, 5, 1e6),
    "game": lambda: Game(),
    "game4": lambda: Game(4, 96, 2),
    "gamethick": lambda: Game(thick=1e6),
    "hiz": lambda: HiZ(),
    "comp0": lambda: Composite(0),
    "comp1": lambda: Composite(1),
    "comp1all": lambda: Composite(1, True),
    "sspr": lambda: Sspr(),
}
names = list(variants) if args.variant == "all" else ["base"] + args.variant.split(",")
base_us = None
for nm in names:
    v = variants[nm]()
    v.run()
    ctx.finish()
    img = np.frombuffer(out_tex.read(), np.uint8).reshape(H, W, 4)
    if args.out:
        o = np.flipud(img[..., :3])
        if args.crop:
            x0, y0, x1, y1 = map(int, args.crop.split(","))
            o = o[y0:y1, x0:x1]
        root, ext = os.path.splitext(args.out)
        Image.fromarray(o).save(f"{root}-{nm}{ext or '.png'}")
    if args.time:
        us = gpu_time(v, args.time)
        if nm == "base":
            base_us = us
        print(f"{v.name:28s} {us:8.1f} us/frame" + (f"   (+{us - base_us:.1f} over the base water draw)" if base_us is not None and nm != "base" else ""))
