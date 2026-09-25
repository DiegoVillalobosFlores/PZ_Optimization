#!/usr/bin/env python3
"""Loader for pzopt.PixelLight dumps (devPplDumpAt): <run>/ppl/<tag>-{squares.txt,depth.bin,color.bin,view.txt}.

    from dump import load
    d = load("harness/runs/<run>", "t30")
    d.color  (h, w, 4) uint8, row 0 = top of the screen
    d.depth  (h, w) float32 window depth, row 0 = top
    d.sq     dict (x, y, z) -> Square
    d.cam, d.lights, d.rooms, d.torches
"""
import os
import sys
from dataclasses import dataclass, field

import numpy as np


@dataclass
class Square:
    x: int
    y: int
    z: int
    vis: int
    info: tuple
    dark: float
    target_dark: float
    level: int
    tick: int
    verts: np.ndarray  # (8, 3) float 0..1: corners 0-3 floor (x,y) (x+1,y) (x+1,y+1) (x,y+1), 4-7 the same one level up
    lights: list = field(default_factory=list)  # (id, x, y, z, radius, r, g, b, flags)
    objects: list = field(default_factory=list)


def abgr(h):
    v = int(h, 16)
    return ((v & 0xFF) / 255.0, ((v >> 8) & 0xFF) / 255.0, ((v >> 16) & 0xFF) / 255.0)


class Dump:
    pass


def load(run, tag):
    d = Dump()
    base = os.path.join(run, "ppl", tag)
    d.cam, d.lights, d.rooms, d.torches, d.sq = {}, [], [], [], {}
    d.depth_at = []
    with open(base + "-squares.txt") as f:
        for line in f:
            if line.startswith("#"):
                continue
            if line.startswith("sq "):
                head, rest, objs = line.rstrip("\n").split("|")
                x, y, z = map(int, head.split()[1:4])
                p = rest.split()
                verts = np.array([abgr(h) for h in p[8:16]], dtype=np.float32)
                n = int(p[16])
                lights = []
                for k in range(n):
                    a = p[17 + k].split(",")
                    lights.append((int(a[0]), int(a[1]), int(a[2]), int(a[3]), int(a[4]), float(a[5]), float(a[6]), float(a[7]), int(a[8])))
                d.sq[(x, y, z)] = Square(x, y, z, int(p[0]), (float(p[1]), float(p[2]), float(p[3])), float(p[4]), float(p[5]), int(p[6]), int(p[7]),
                                         verts, lights, objs.split())
                continue
            p = line.split()
            if not p:
                continue
            if p[0] == "light":
                d.lights.append(dict(id=int(p[1]), x=int(p[2]), y=int(p[3]), z=int(p[4]), radius=int(p[5]), r=float(p[6]), g=float(p[7]), b=float(p[8]),
                                     active=p[9] == "true", building=int(p[10])))
            elif p[0] == "room":
                d.rooms.append(dict(id=int(p[1]), x=int(p[2]), y=int(p[3]), z=int(p[4]), w=int(p[5]), h=int(p[6]), active=p[7] == "true"))
            elif p[0] == "torch":
                d.torches.append(dict(id=int(p[1]), x=float(p[2]), y=float(p[3]), z=float(p[4]), r=float(p[5]), g=float(p[6]), b=float(p[7]),
                                      ax=float(p[8]), ay=float(p[9]), dist=float(p[10]), strength=float(p[11]), cone=p[12] == "true", dot=float(p[13]),
                                      focusing=int(p[14])))
            elif p[0] == "depthAt":
                d.depth_at.append(tuple(map(float, p[1:5])))
            else:
                d.cam[p[0]] = [float(v) for v in p[1:]] if len(p) > 2 else float(p[1])
    view = {}
    with open(base + "-view.txt") as f:
        for line in f:
            k, v = line.strip().split("=", 1)
            view[k] = v
    d.view = view
    w, h = int(view["w"]), int(view["h"])
    d.w, d.h = w, h
    d.color = np.fromfile(base + "-color.bin", dtype=np.uint8).reshape(h, w, 4)[::-1]
    d.depth = np.fromfile(base + "-depth.bin", dtype=np.float32).reshape(h, w)[::-1]
    return d


def world_px(x, y, z, ts):
    """IsoUtils.XToScreen / YToScreen (world pixels before the camera offset and zoom)."""
    return (x - y) * 32 * ts, (x + y) * 16 * ts - z * 96 * ts


if __name__ == "__main__":
    d = load(sys.argv[1], sys.argv[2])
    print(d.cam)
    print(len(d.sq), "squares", len(d.lights), "lights", len(d.rooms), "rooms", len(d.torches), "torches")


def load_lit(run, tag):
    """The scene right after this frame's pixel-light pass (<tag>-lit-*.bin), row 0 = top."""
    base = os.path.join(run, "ppl", tag + "-lit")
    view = {}
    with open(base + "-view.txt") as f:
        for line in f:
            k, v = line.strip().split("=", 1)
            view[k] = v
    w, h = int(view["w"]), int(view["h"])
    return np.fromfile(base + "-color.bin", dtype=np.uint8).reshape(h, w, 4)[::-1]
