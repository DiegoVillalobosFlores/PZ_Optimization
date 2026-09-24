#!/usr/bin/env python3
"""Reads pzopt.Hdr frame dumps (<run>/hdr/<tag>.post.f16 / .pre.f16 / .json) and turns them into numbers and
pictures an SDR screen (or a reviewer) can judge:

  hdrframe.py <run-or-hdr-dir> [--tags a,b] [--out DIR] [--crop x,y,w,h] [--scale N]

Per dump: luminance stats in cd/m² on the panel (post * encMax), the share of pixels above the UI white / 2x / 4x,
the brightest 0.1 %, the frame-average light level (vs the panel's max-FALL), and images:
  <tag>.sdr.png      the SDR frame (pre-encode clamped to 0..1: what the game shows without HDR, same frame)
  <tag>.ev0.png      HDR frame tone-mapped for SDR viewing with the UI white at SDR white (highlights clip)
  <tag>.ev-2.png     the same 2 stops darker: highlight detail / how far above white the highlights go
  <tag>.heat.png     false colour: grey < UI white, then blue 1-1.5x, green 1.5-2x, yellow 2-3x, red > 3x UI white
  <tag>.pq.png       16-bit PNG, PQ / BT.2020 (cICP chunk) for viewing on an HDR display
  <tag>.side.png     SDR | ev-2 | heat side by side (half size) for quick review
"""
import argparse
import json
import os
import struct
import sys
import zlib

import numpy as np
from PIL import Image

M709_TO_2020 = np.array([[0.6274, 0.3293, 0.0433], [0.0691, 0.9195, 0.0114], [0.0164, 0.0880, 0.8956]])


def load(path, w, h):
    a = np.fromfile(path, dtype=np.float16).astype(np.float32)
    return a.reshape(h, w, 3)[::-1]  # GL rows are bottom-up


def lum(rgb):
    return rgb @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)


def to8(x):
    return (np.clip(x, 0, 1) * 255 + 0.5).astype(np.uint8)


def srgb_oetf(x):
    """Gamma 2.2 encode: KWin decodes SDR surfaces (and the game's UI white) with a pure 2.2 power (probe, 2026-09-24)."""
    return np.power(np.clip(x, 0, 1), 1 / 2.2)


def pq_oetf(nits):
    y = np.clip(nits / 10000.0, 0, 1)
    m1, m2, c1, c2, c3 = 0.1593017578125, 78.84375, 0.8359375, 18.8515625, 18.6875
    p = np.power(y, m1)
    return np.power((c1 + c2 * p) / (1 + c3 * p), m2)


def write_pq_png(path, nits_rgb709):
    rgb2020 = np.clip(nits_rgb709 @ M709_TO_2020.T, 0, None)
    v = (pq_oetf(rgb2020) * 65535 + 0.5).astype(">u2")
    h, w = v.shape[:2]
    raw = b"".join(b"\x00" + v[y].tobytes() for y in range(h))

    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 16, 2, 0, 0, 0))
    png += chunk(b"cICP", bytes([9, 16, 0, 1]))  # BT.2020 primaries, PQ, RGB, full range
    png += chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def heat(nits_y, ui):
    r = nits_y / ui
    out = np.zeros(r.shape + (3,), dtype=np.float32)
    g = np.clip(r, 0, 1) ** (1 / 2.2) * 0.8
    out[...] = g[..., None]
    bands = [(1.0, 1.5, (0.2, 0.4, 1.0)), (1.5, 2.0, (0.1, 0.9, 0.2)), (2.0, 3.0, (1.0, 0.9, 0.1)), (3.0, 1e9, (1.0, 0.1, 0.1))]
    for lo, hi, col in bands:
        m = (r >= lo) & (r < hi)
        out[m] = col
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("path")
    ap.add_argument("--tags")
    ap.add_argument("--out")
    ap.add_argument("--crop")
    ap.add_argument("--scale", type=int, default=1)
    ap.add_argument("--no-images", action="store_true")
    a = ap.parse_args()
    d = a.path
    if os.path.isdir(os.path.join(d, "hdr")):
        d = os.path.join(d, "hdr")
    elif not any(f.endswith(".post.f16") for f in os.listdir(d)):
        sys.exit(f"{a.path}: no HDR dumps (no hdr/ dir: did the shot_at hold happen before the route ended?)")
    out = a.out or d
    os.makedirs(out, exist_ok=True)
    tags = sorted(f[:-5] for f in os.listdir(d) if f.endswith(".json") and os.path.exists(os.path.join(d, f[:-5] + ".post.f16")))
    if a.tags:
        want = a.tags.split(",")
        tags = [t for t in tags if t in want or any(t.endswith("-" + x) for x in want)]
    for tag in tags:
        meta = json.load(open(os.path.join(d, tag + ".json")))
        w, h, enc_max = meta["w"], meta["h"], meta["encMax"]
        ui = meta["uiNits"]
        post = load(os.path.join(d, tag + ".post.f16"), w, h) * enc_max
        pre_path = os.path.join(d, tag + ".pre.f16")
        pre = load(pre_path, w, h) if os.path.exists(pre_path) else None
        if a.crop:
            x, y, cw, ch = map(int, a.crop.split(","))
            post = post[y:y + ch, x:x + cw]
            pre = pre[y:y + ch, x:x + cw] if pre is not None else None
        if a.scale > 1:
            s = a.scale
            post = post[: post.shape[0] // s * s, : post.shape[1] // s * s].reshape(post.shape[0] // s, s, post.shape[1] // s, s, 3).mean((1, 3))
            if pre is not None:
                pre = pre[: pre.shape[0] // s * s, : pre.shape[1] // s * s].reshape(pre.shape[0] // s, s, pre.shape[1] // s, s, 3).mean((1, 3))
        y = lum(post)
        pct = np.percentile(y, [1, 10, 50, 90, 99, 99.9])
        above = [(y > ui * k).mean() * 100 for k in (1.0, 1.5, 2.0, 3.0)]
        print(f"{tag}: nits p1 {pct[0]:.2f} p10 {pct[1]:.1f} p50 {pct[2]:.0f} p90 {pct[3]:.0f} p99 {pct[4]:.0f} p99.9 {pct[5]:.0f} max {y.max():.0f}"
              f" | avg {y.mean():.0f} (max-FALL ~{meta.get('panelPeak', 0) and 244}) | >UI {above[0]:.2f}% >1.5x {above[1]:.2f}% >2x {above[2]:.2f}% >3x {above[3]:.2f}%"
              f" | UI white {ui:.0f}, 1.0 = {enc_max:.0f}")
        print(f"   tune: {meta['tune']}")
        if a.no_images:
            continue
        base = os.path.join(out, tag)
        ev0 = srgb_oetf(post / ui)
        ev2 = srgb_oetf(post / ui / 4)
        hm = heat(y, ui)
        Image.fromarray(to8(ev0)).save(base + ".ev0.png")
        Image.fromarray(to8(ev2)).save(base + ".ev-2.png")
        Image.fromarray(to8(hm)).save(base + ".heat.png")
        write_pq_png(base + ".pq.png", post)
        tiles = []
        if pre is not None:
            sdr = np.clip(pre, 0, 1)
            Image.fromarray(to8(sdr)).save(base + ".sdr.png")
            tiles.append(sdr)
        tiles += [ev2, hm]
        side = np.concatenate([t[::2, ::2] for t in tiles], axis=1)
        Image.fromarray(to8(side)).save(base + ".side.png")


if __name__ == "__main__":
    main()
