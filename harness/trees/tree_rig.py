#!/usr/bin/env python3
"""Replays one chunk AO / sun compute offline from an in-game dump (2026-09-25, tree lighting): the real GLSL of
src/pzopt/pzopt/ChunkAo.java on the dumped source depth textures and uniforms, so kernel changes can be tried without a
game run.

    tree_rig.py [--dump ~/Zomboid] [--out /tmp/tree-rig] [--len-squares L] [--steps N] [--time N] [--define X]...

The dump: a run with --prop devAoDumpTree=N (the Nth compute of a texture whose chunk holds a tree) or devAoDumpFrame=N
writes ~/Zomboid/pzopt-chunkao-dump.txt (sizes, sources, uniforms), pzopt-chunkao-src<i>.bin (each source's depth,
float32, GL row order) and pzopt-chunkao-raw4.bin (the game's own raw output: AO, depth, sun, 1). Writes <out>-sun.png
(the raw sun term, 0.5 grey = none), <out>-ao.png, <out>-term.png (blurred, sun alone) and <out>-game-sun.png (the game's
raw sun for comparison), rows flipped to screen orientation. Needs the moderngl venv of harness/contact/kernel_rig.py.
"""
import argparse
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "contact"))
from kernel_rig import glsl, SRC  # noqa: E402

import moderngl  # noqa: E402
from PIL import Image  # noqa: E402

UNITS_PER_DEPTH = 0.6123724356957945 / (0.0028867084 * 0.5)


def parse(path):
    kv = {}
    for ln in open(path):
        if "=" in ln:
            k, v = ln.strip().split("=", 1)
            kv[k] = v
    return kv


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump", default=os.path.expanduser("~/Zomboid"))
    ap.add_argument("--out", default="/tmp/tree-rig")
    ap.add_argument("--len-squares", type=float, help="the sun march length (default: the game's rule)")
    ap.add_argument("--steps", type=int)
    ap.add_argument("--time", type=int, default=0)
    ap.add_argument("--define", action="append", default=[])
    ap.add_argument("--aoscale", type=float, default=0.5)
    ap.add_argument("--canopy", type=float, default=0.35, help="sunTree.x: the crowns' optical depth per square for the sun (sunShadowCanopyPct / 100)")
    ap.add_argument("--sky", type=float, default=0.25, help="sunTree.y: the crowns' optical depth per square for the sky (aoTreeCanopyPct / 100)")
    ap.add_argument("--no-trees", action="store_true", help="the kernel without the crown proxies (sunTree = 0)")
    ap.add_argument("--radius-pct", type=float, default=100.0)
    ap.add_argument("--thickness-pct", type=float, default=60.0)
    a = ap.parse_args()
    kv = parse(os.path.join(a.dump, "pzopt-chunkao-dump.txt"))
    W, H, aw, ah, sc, ppu = int(kv["w"]), int(kv["h"]), int(kv["aw"]), int(kv["ah"]), float(kv["sc"]), float(kv["ppu"])
    srcs = []
    for i in range(9):
        if "src%d" % i not in kv:
            break
        x, y, w, h, off = kv["src%d" % i].split(",")
        srcs.append((float(x), float(y), int(w), int(h), float(off)))
    ctx = moderngl.create_standalone_context(backend="egl", require=330)
    texs = []
    for i, (x, y, w, h, off) in enumerate(srcs):
        d = np.fromfile(os.path.join(a.dump, "pzopt-chunkao-src%d.bin" % i), "<f4").reshape(h, w)
        t = ctx.texture((w, h), 1, np.ascontiguousarray(d).tobytes(), dtype="f4")
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        texs.append(t)
    quad = ctx.buffer(np.array([-1, -1, 1, -1, 1, 1, -1, 1], "f4").tobytes())
    vert = glsl(os.path.join(SRC, "AmbientOcclusion.java"), "QUAD_VERT")
    kernel = glsl(os.path.join(SRC, "ChunkAo.java"), "AO_FRAG")
    for d in a.define:
        kernel = kernel.replace("#version 140", "#version 140\n#define " + d, 1)
    blur = glsl(os.path.join(SRC, "ChunkAo.java"), "BLUR_FRAG")
    pk = ctx.program(vertex_shader=vert, fragment_shader=kernel)
    pb = ctx.program(vertex_shader=vert, fragment_shader=blur)
    ints = lambda s: [int(v) & 0xFFFFFFFF for v in s.split(",")]  # noqa: E731
    sun_dir = list(map(float, kv["sunDir"].split(",")))
    sun_perp = list(map(float, kv["sunPerp"].split(",")))
    tan_elev = float(kv["sunTanElev"])
    length = a.len_squares if a.len_squares else max(1.0, min(8.0, 2.0 * 2.4494897 / max(0.05, tan_elev)))
    steps = a.steps if a.steps else max(8, min(32, round(24 * length / 8.0)))
    radius = a.radius_pct / 100.0

    def s(p, n, val):
        if n in p:
            p[n].value = val

    def setu(p):
        rect = [(x, y, float(w), float(h)) for (x, y, w, h, off) in srcs] + [(0.0, 0.0, 0.0, 0.0)] * (9 - len(srcs))
        s(p, "rect", rect)
        s(p, "off", [off for (*_, off) in srcs] + [0.0] * (9 - len(srcs)))
        s(p, "nSrc", len(srcs))
        s(p, "geo", (1.0 / sc, ppu, -1.0, 0.0))
        s(p, "params", (radius * ppu, a.thickness_pct / 100.0, UNITS_PER_DEPTH, radius))
        s(p, "strength", (1.0, 0.5, 1.0, 0.5))
        s(p, "mode", (1.0, 0.0, 0.0, 0.0))
        s(p, "iso0", tuple(map(float, kv["iso0"].split(","))))
        s(p, "iso1", tuple(map(float, kv["iso1"].split(","))))
        nt = 0 if a.no_trees else int(kv.get("nTrees", "0"))
        if nt:
            fl = lambda k: [tuple(map(float, kv[k].split(",")[q * 4:q * 4 + 4])) for q in range(16)]  # noqa: E731
            s(p, "treeA", fl("treeA"))
            s(p, "treeB", fl("treeB"))
            s(p, "sunWorld", tuple(map(float, kv["sunWorld"].split(","))) + (0.0,))
        s(p, "sunTree", (a.canopy if nt else 0.0, a.sky if nt else 0.0, float(nt), 0.0))
        if "veg" in p:
            p["veg"].value = ints(kv["veg"])
        if "ext" in p:
            p["ext"].value = ints(kv["ext"])
        s(p, "sunDir", tuple(sun_dir))
        s(p, "sunPerp", tuple(sun_perp))
        s(p, "sunPar", (length * ppu, 1.0, float(steps), 1.0))
        for i in range(9):
            s(p, "Src%d" % i, min(i, len(srcs) - 1))

    raw = ctx.texture((aw, ah), 4, dtype="f4")
    raw.filter = (moderngl.NEAREST, moderngl.NEAREST)
    rawf = ctx.framebuffer([raw])
    outt = ctx.texture((aw, ah), 1, dtype="f1")
    outf = ctx.framebuffer([outt])

    def va(p):
        name = [n for n in p if isinstance(p[n], moderngl.Attribute)][0]
        return ctx.vertex_array(p, [(quad, "2f", name)])

    vk, vb = va(pk), va(pb)
    ppu_ao = ppu * sc

    def run(uniforms=True):
        for i, t in enumerate(texs):
            t.use(i)
        rawf.use()
        ctx.viewport = (0, 0, aw, ah)
        if uniforms:
            setu(pk)  # (outside the timed loop: moderngl's per-call uploads idle the GPU inside the timer query)
        vk.render(moderngl.TRIANGLE_FAN)
        outf.use()
        raw.use(0)
        pb["Ao"].value = 0
        pb["params"].value = (UNITS_PER_DEPTH, 1.0 / ppu_ao, float(aw - 1), float(ah - 1))
        pb["sunOnly"].value = 1.0
        vb.render(moderngl.TRIANGLE_FAN)

    run()
    r = np.frombuffer(raw.read(), np.float32).reshape(ah, aw, 4)
    term = np.frombuffer(outt.read(), np.uint8).reshape(ah, aw)
    game = np.fromfile(os.path.join(a.dump, "pzopt-chunkao-raw4.bin"), "<f4").reshape(ah, aw, 4)
    g = lambda v: (np.clip(np.where(v < 0, 0.5, v), 0, 1) * 255).astype(np.uint8)[::-1]  # noqa: E731
    Image.fromarray(g(r[..., 2])).save(a.out + "-sun.png")
    Image.fromarray(g(r[..., 0])).save(a.out + "-ao.png")
    Image.fromarray(term[::-1]).save(a.out + "-term.png")
    Image.fromarray(g(game[..., 2])).save(a.out + "-game-sun.png")
    diff = np.abs(np.where(r[..., 2] < 0, 1, r[..., 2]) - np.where(game[..., 2] < 0, 1, game[..., 2]))
    print(f"len {length:.2f} squares, {steps} steps; sun term: shaded texels {int((r[..., 2] >= 0).sum() and ((r[..., 2] >= 0) & (r[..., 2] < 0.99)).sum())}, "
          f"mean |rig - game| {diff.mean():.4f}")
    if a.time:
        ctx.finish()
        q = ctx.query(time=True)
        run()
        ctx.finish()
        with q:
            for _ in range(a.time):
                run(uniforms=False)
        print("gpu us per compute (kernel + blur): %.1f" % (q.elapsed / 1e3 / a.time))


if __name__ == "__main__":
    main()
