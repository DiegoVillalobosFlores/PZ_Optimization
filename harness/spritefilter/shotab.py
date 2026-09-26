#!/usr/bin/env python3
"""Sprite filter (candidate A) shot A/B: a --shot-at run with --prop devSpriteFilterShotAb=true holds the camera, writes
shot-game.png with stock filtering (2 s into the hold) and shot2-game.png with spriteFilter (4 s). This compares them.

  harness/spritefilter/shotab.py <run> [--crops N] [--out dir]

Prints, over the world area (the top-left overlay corner and a band round the player masked out):
  changed      share of pixels whose colour moved by more than 4/255 (the filter did something where it should)
  grad         mean gradient magnitude (Sobel, luma 0..255): edges crisper -> higher
  hf           high-frequency energy (luma minus its 3x3 box blur, RMS): texel detail -> higher
  edge_width   median 10-90 % rise width across strong edges, in pixels (blur -> wider)
and writes <out>/crops.png: N crops (the tiles where the two differ most), stock left, filtered right, 3x nearest.
"""
import argparse
import os
import sys

import numpy as np
from PIL import Image


def luma(a):
    return 0.2126 * a[..., 0] + 0.7152 * a[..., 1] + 0.0722 * a[..., 2]


def box3(y):
    p = np.pad(y, 1, mode="edge")
    s = sum(p[i:i + y.shape[0], j:j + y.shape[1]] for i in range(3) for j in range(3))
    return s / 9.0


def sobel(y):
    p = np.pad(y, 1, mode="edge")
    gx = (p[:-2, 2:] + 2 * p[1:-1, 2:] + p[2:, 2:]) - (p[:-2, :-2] + 2 * p[1:-1, :-2] + p[2:, :-2])
    gy = (p[2:, :-2] + 2 * p[2:, 1:-1] + p[2:, 2:]) - (p[:-2, :-2] + 2 * p[:-2, 1:-1] + p[:-2, 2:])
    return np.hypot(gx, gy) / 4.0


def edge_widths(y, mask, samples=4000, seed=1):
    """Median 10-90 % rise width along x across strong horizontal steps (a blur widens it)."""
    g = np.abs(np.diff(y, axis=1))
    ys, xs = np.nonzero((g > 40) & mask[:, 1:])
    if len(ys) == 0:
        return float("nan")
    rng = np.random.default_rng(seed)
    idx = rng.choice(len(ys), size=min(samples, len(ys)), replace=False)
    widths = []
    for k in idx:
        r, c = ys[k], xs[k]
        lo, hi = max(0, c - 6), min(y.shape[1], c + 8)
        seg = y[r, lo:hi]
        a, b = seg.min(), seg.max()
        if b - a < 40:
            continue
        t10, t90 = a + 0.1 * (b - a), a + 0.9 * (b - a)
        inside = np.nonzero((seg > t10) & (seg < t90))[0]
        widths.append(len(inside))
    return float(np.median(widths)) if widths else float("nan")


def world_mask(h, w):
    m = np.ones((h, w), bool)
    m[: int(h * 0.30), : int(w * 0.28)] = False  # the overlay corner
    cy, cx = h // 2, w // 2
    m[cy - h // 10: cy + h // 12, cx - w // 30: cx + w // 30] = False  # the player
    return m


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--crops", type=int, default=6)
    ap.add_argument("--crop", type=int, default=160)
    ap.add_argument("--out")
    a = ap.parse_args()
    fa, fb = os.path.join(a.run, "shot-game.png"), os.path.join(a.run, "shot2-game.png")
    if not (os.path.exists(fa) and os.path.exists(fb)):
        sys.exit("no shot-game.png / shot2-game.png in " + a.run)
    A = np.asarray(Image.open(fa).convert("RGB")).astype(np.float32)
    B = np.asarray(Image.open(fb).convert("RGB")).astype(np.float32)
    if A.shape != B.shape:
        sys.exit("the shots differ in size: %s vs %s" % (A.shape, B.shape))
    h, w = A.shape[:2]
    m = world_mask(h, w)
    ya, yb = luma(A), luma(B)
    diff = np.abs(A - B).max(axis=2)
    changed = float(((diff > 4) & m).sum() / m.sum())
    ga, gb = sobel(ya), sobel(yb)
    ha, hb = ya - box3(ya), yb - box3(yb)
    print("shots %dx%d, world px %d" % (w, h, m.sum()))
    print("changed=%.4f" % changed)
    print("grad stock=%.3f filtered=%.3f ratio=%.3f" % (ga[m].mean(), gb[m].mean(), gb[m].mean() / max(1e-9, ga[m].mean())))
    print("hf stock=%.3f filtered=%.3f ratio=%.3f" % (np.sqrt((ha[m] ** 2).mean()), np.sqrt((hb[m] ** 2).mean()),
                                                     np.sqrt((hb[m] ** 2).mean()) / max(1e-9, np.sqrt((ha[m] ** 2).mean()))))
    print("edge_width stock=%.2f filtered=%.2f" % (edge_widths(ya, m), edge_widths(yb, m)))
    out = a.out or os.path.join(a.run, "spritefilter")
    os.makedirs(out, exist_ok=True)
    c = a.crop
    tiles = []
    for y0 in range(0, h - c, c):
        for x0 in range(0, w - c, c):
            if not m[y0:y0 + c, x0:x0 + c].all():
                continue
            tiles.append((float(diff[y0:y0 + c, x0:x0 + c].mean()), y0, x0))
    tiles.sort(reverse=True)
    picked = []
    for t in tiles:
        if all(abs(t[1] - p[1]) >= c or abs(t[2] - p[2]) >= c for p in picked):
            picked.append(t)
        if len(picked) >= a.crops:
            break
    s = 3
    sheet = Image.new("RGB", (2 * c * s + 12, len(picked) * (c * s + 8)), (20, 20, 24))
    for i, (_, y0, x0) in enumerate(picked):
        for j, src in enumerate((A, B)):
            im = Image.fromarray(src[y0:y0 + c, x0:x0 + c].astype(np.uint8)).resize((c * s, c * s), Image.NEAREST)
            sheet.paste(im, (j * (c * s + 12), i * (c * s + 8)))
    sheet.save(os.path.join(out, "crops.png"))
    print("crops=%s (%d; stock left, filtered right, %dx nearest)" % (os.path.join(out, "crops.png"), len(picked), s))


if __name__ == "__main__":
    main()
