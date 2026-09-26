#!/usr/bin/env python3
"""Offline rig for the chunk kernel's sun term (pzopt.ChunkAo): the real GLSL, a synthetic chunk-texture depth.

    kernel_rig.py <out.png> [--hour 16] [--box x0,y0,x1,y1,h]... [--steps 24]
                  [--time N]

Needs moderngl (headless EGL): python -m venv /tmp/cs/venv && /tmp/cs/venv/bin/pip install moderngl numpy pillow.
The depth is ray-cast from the chunk texture's orthographic view (x screen right, y screen up, z away from the camera;
world +x, +y, +z(height, squares) = the three plane normals the kernel snaps to): the ground z = 0 and boxes (walls:
thin boxes). Prints the kernel + blur GPU time with --time N (N repetitions)."""
import argparse, math, os, re, sys, time
import numpy as np
import moderngl
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "..", "src", "pzopt", "pzopt")


def glsl(path, name):
    src = open(path).read()
    m = re.search(r'String ' + name + r' = String\.join\("\\n",\n(.*?)\);\n', src, re.S)
    parts = []
    for line in m.group(1).split("\n"):
        line = line.strip()
        if not line or line.startswith("//"):
            continue
        lit = re.match(r'"((?:[^"\\]|\\.)*)"\s*,?\s*(//.*)?$', line)
        if lit:
            parts.append(lit.group(1).encode().decode("unicode_escape"))
    return "\n".join(parts) + "\n"


WX = np.array([0.7071068, -0.3535534, -0.6123724])
WY = np.array([-0.7071068, -0.3535534, -0.6123724])
WZ = np.array([0.0, 0.8660254, -0.5])
KZ = 0.6123724356957945 / (0.0028867084 * 0.5)  # squares of view depth per unit of depth


def depth_map(w, h, ppu, boxes, trees=()):
    """Front-most surface per texel (rows top-down): ground z = 0 and axis-aligned boxes. 1.0 = empty."""
    cx, cy = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
    vx = (cx - w / 2) / ppu
    vy = -(cy - h * 0.6) / ppu
    # world(t) = o + t d along the view ray (vx, vy, t)
    o = np.stack([vx * WX[0] + vy * WX[1], vx * WY[0] + vy * WY[1], vx * WZ[0] + vy * WZ[1]], -1)
    d = np.array([WX[2], WY[2], WZ[2]])
    tbest = np.full((h, w), np.inf)
    tg = -o[..., 2] / d[2]  # ground z = 0
    ok = (np.abs(o[..., 0] + tg * d[0]) < 12) & (np.abs(o[..., 1] + tg * d[1]) < 12)
    tbest = np.where(ok, tg, tbest)
    for (x0, y0, x1, y1, hz) in boxes:
        lo = np.array([x0, y0, 0.0]); hi = np.array([x1, y1, hz])
        t0 = (lo - o) / d; t1 = (hi - o) / d
        tn = np.minimum(t0, t1).max(-1); tf = np.maximum(t0, t1).min(-1)
        hit = (tn <= tf)
        tbest = np.where(hit & (tn < tbest), tn, tbest)
    for (tx, ty, hw, hz) in trees:
        # a baked tree (pzopt.TreeBake): a camera-facing vertical card through its square's south corner, x + y = const
        c = tx + ty
        t = (c - (o[..., 0] + o[..., 1])) / (d[0] + d[1])
        px, py, pz = o[..., 0] + t * d[0], o[..., 1] + t * d[1], o[..., 2] + t * d[2]
        # a crown: an ellipse on the card from 0.25 of the height up, a trunk below it
        u, v = (px - py) / 2.0 - (tx - ty) / 2.0, pz / hz
        crown = (u / hw) ** 2 + ((v - 0.6) / 0.4) ** 2 < 1.0
        trunk = (np.abs(u) < 0.08) & (v >= 0.0) & (v < 0.4)
        hit = (crown | trunk) & (t < tbest)
        tbest = np.where(hit, t, tbest)
    dep = np.where(np.isfinite(tbest), 0.5 + tbest / KZ, 1.0)
    return dep.astype(np.float32)


def sun_view(hour, max_elev=55.0):
    a = math.pi * (hour - 6.0) / 12.0
    elev = max(0.0, math.sin(a)) * math.radians(max_elev)
    wx, wy, wz = math.cos(a) * math.cos(elev), math.sin(a) * math.cos(elev), math.sin(elev)
    v = wx * WX + wy * WY + wz * WZ
    pl = math.hypot(v[0], v[1])
    perp = (-v[1] / pl, v[0] / pl, 0.0)
    return v, perp


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--hour", type=float, default=16.0)
    ap.add_argument("--box", action="append", default=[])
    ap.add_argument("--steps", type=int, default=24)
    ap.add_argument("--depth-bin", help="a devAoDumpTree / devAoDumpFrame pzopt-chunkao-raw4.bin (AO-scale depth in g), upsampled x2")
    ap.add_argument("--sundir", help="view-space direction to the sun vx,vy,vz (the game's 'sun shadows: ... dir' log line)")
    ap.add_argument("--tree", action="append", default=[], help="x,y,half_width,height (squares): a baked tree card")
    ap.add_argument("--time", type=int, default=0)
    ap.add_argument("--strength", type=float, default=0.45)
    a = ap.parse_args()
    boxes = [tuple(map(float, b.split(","))) for b in a.box] or [(-2.0, -0.1, 2.0, 0.1, 2.449), (1.0, 2.0, 1.3, 2.3, 1.5)]
    W = H = 1024
    ppu = 45.254834 * 2
    sc = 0.5
    aw, ah = int(math.ceil(W * sc)), int(math.ceil(H * sc))
    ctx = moderngl.create_standalone_context(backend="egl", require=330)
    dep = depth_map(W, H, ppu, boxes, [tuple(map(float, t.split(","))) for t in a.tree])
    if a.depth_bin:
        raw = np.fromfile(a.depth_bin, "<f4").reshape(H // 2, W // 2, 4)[..., 1]
        dep = np.ascontiguousarray(np.repeat(np.repeat(raw, 2, 0), 2, 1)).astype(np.float32)
    src0 = ctx.texture((W, H), 1, dep.tobytes(), dtype="f4")
    src0.filter = (moderngl.NEAREST, moderngl.NEAREST)
    quad = ctx.buffer(np.array([-1, -1, 1, -1, 1, 1, -1, 1], "f4").tobytes())
    vert = glsl(os.path.join(SRC, "AmbientOcclusion.java"), "QUAD_VERT")
    kernel = glsl(os.path.join(SRC, "ChunkAo.java"), "AO_FRAG")
    blur = glsl(os.path.join(SRC, "ChunkAo.java"), "BLUR_FRAG")

    def prog(define=None):
        f = kernel if define is None else kernel.replace("#version 140", "#version 140\n#define " + define, 1)
        return ctx.program(vertex_shader=vert, fragment_shader=f)

    v, perp = sun_view(a.hour)
    if a.sundir:
        v = np.array(list(map(float, a.sundir.split(","))))
        v = v / np.linalg.norm(v)
        pl = math.hypot(v[0], v[1])
        perp = (-v[1] / pl, v[0] / pl, 0.0)
    tanA = math.tan(math.radians(3.0))

    def setu(p, geox):
        def s(n, val):
            if n in p:
                p[n].value = val
        rect = [(0.0, 0.0, float(W), float(H))] + [(0.0, 0.0, 0.0, 0.0)] * 8
        s("rect", rect)
        s("off", [0.0] * 9)
        s("nSrc", 1)
        s("geo", (geox, ppu, -1.0, 0.0))
        s("params", (0.6 * ppu, 0.6, KZ, 0.6))
        s("strength", (1.0, 1.0, 1.0, 1.0))
        s("mode", (0.0, 1.0, 0.0, 0.0))
        s("sunDir", (float(v[0]), float(v[1]), float(v[2]), a.strength))
        s("sunPerp", (perp[0], perp[1], 0.0, tanA))
        s("sunPar", (8.0 * ppu, 1.0, float(a.steps), 0.0))
        s("iso1", (32.0, 192.0, 0.023093667 / 16, 0.0))
        for i in range(9):
            s("Src%d" % i, 0)

    raw = ctx.texture((aw, ah), 4, dtype="f4"); raw.filter = (moderngl.NEAREST, moderngl.NEAREST)
    rawf = ctx.framebuffer([raw])
    outt = ctx.texture((aw, ah), 1, dtype="f1")
    outf = ctx.framebuffer([outt])
    pk = prog()
    pb = ctx.program(vertex_shader=vert, fragment_shader=blur)

    def va(p):
        name = [n for n in p if isinstance(p[n], moderngl.Attribute)][0]
        return ctx.vertex_array(p, [(quad, "2f", name)])

    vk, vb = va(pk), va(pb)

    def run():
        src0.use(0)
        rawf.use(); ctx.viewport = (0, 0, aw, ah)
        setu(pk, 1.0 / sc)
        vk.render(moderngl.TRIANGLE_FAN)
        outf.use(); ctx.viewport = (0, 0, aw, ah)
        raw.use(0)
        pb["Ao"].value = 0
        pb["params"].value = (KZ, 1.0 / (ppu * sc), float(aw - 1), float(ah - 1))
        pb["sunOnly"].value = 1.0
        vb.render(moderngl.TRIANGLE_FAN)

    run()
    img = np.frombuffer(outt.read(), np.uint8).reshape(ah, aw)
    Image.fromarray(img).save(a.out)
    rawv = np.frombuffer(raw.read(), np.float32).reshape(ah, aw, 4)
    print("term: min %d mean %.1f | raw sun min %.3f max %.3f" % (img.min(), img.mean(), rawv[..., 2].min(), rawv[..., 2].max()))
    if a.time:
        ctx.finish()
        q = ctx.query(time=True)
        with q:
            for _ in range(a.time):
                run()
        print("gpu us per compute (kernel + blur): %.1f" % (q.elapsed / 1e3 / a.time))


if __name__ == "__main__":
    main()
