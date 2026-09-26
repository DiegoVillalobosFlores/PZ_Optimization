#!/usr/bin/env python3
"""Sprite filter modes on one held frame: a --shot-at run with --prop devSpriteFilterShotModes=<entries> and a devCapture
over the hold (e.g. --prop devCapture=4,24,3,100,gray). The console logs "sprite filter: shot mode <entry> from
epoch_ms=<t>"; for each entry this takes the last captured frame before the next switch (settled: the variant compiled,
the frame presented) and prints, over the world area (overlay corner and the player masked):

  detail     RMS Laplacian of the luma (sharpness; aliasing counts too, judge with the crops)
  vs_stock   mean absolute luma difference to the stock entry (what the mode changes)

and writes <run>/spritefilter/modes-<x>-<y>.png: the same crop for every entry side by side, 3x nearest.

  harness/spritefilter/modes.py <run> [--crop x,y,w,h] ...
"""
import argparse
import os
import re

import numpy as np
from PIL import Image, ImageDraw


def load(run):
    d = os.path.join(run, "capture")
    lines = open(os.path.join(d, "index.txt")).read().split("\n")
    head = dict(kv.split("=") for kv in lines[0].split())
    w, h = int(head["w"]), int(head["h"])
    gray = head.get("fmt") == "gray"
    stamps = np.array([int(x) for x in lines[1:] if x.strip()])
    raw = np.memmap(os.path.join(d, "frames.gray" if gray else "frames.rgba"), dtype=np.uint8, mode="r")
    n = min(len(stamps), raw.size // (w * h * (1 if gray else 4)))

    def frame(i):
        if gray:
            return np.ascontiguousarray(raw[i * w * h:(i + 1) * w * h].reshape(h, w)[::-1, :])
        return np.ascontiguousarray(raw[i * w * h * 4:(i + 1) * w * h * 4].reshape(h, w, 4)[::-1, :, :3])
    return w, h, stamps[:n], frame


def luma(f):
    f = f.astype(np.float32)
    return f if f.ndim == 2 else f[..., 0] * 0.299 + f[..., 1] * 0.587 + f[..., 2] * 0.114


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--crop", action="append", default=[], help="x,y,w,h in screen pixels (default: three spots)")
    a = ap.parse_args()
    switches = []
    for line in open(os.path.join(a.run, "console.txt"), errors="replace"):
        m = re.search(r"sprite filter: shot mode (\S+) from epoch_ms=(\d+)", line)
        if m:
            switches.append((m.group(1), int(m.group(2))))
        m = re.search(r"sprite filter: shot modes done at epoch_ms=(\d+)", line)
        if m:
            switches.append((None, int(m.group(1))))
    w, h, stamps, frame = load(a.run)
    picks = []
    for k, (name, t) in enumerate(switches):
        if name is None or k + 1 >= len(switches):
            continue
        t1 = switches[k + 1][1]
        idx = np.nonzero((stamps > t + 250) & (stamps < t1 - 30))[0]
        if len(idx):
            picks.append((name, int(idx[-1])))
    if not picks:
        print("no capture frame inside the shot modes")
        return
    mask = np.ones((h, w), bool)
    mask[: int(h * 0.30), : int(w * 0.28)] = False
    mask[h // 2 - h // 10: h // 2 + h // 12, w // 2 - w // 30: w // 2 + w // 30] = False
    lums, imgs = {}, {}
    for name, i in picks:
        f = frame(i)
        imgs[name] = f
        lums[name] = luma(f)
    ref = lums.get("stock")
    print("%-22s %6s %8s %9s" % ("entry", "frame", "detail", "vs_stock"))
    for name, i in picks:
        y = lums[name]
        l = 4 * y[1:-1, 1:-1] - y[:-2, 1:-1] - y[2:, 1:-1] - y[1:-1, :-2] - y[1:-1, 2:]
        det = float(np.sqrt((l[mask[1:-1, 1:-1]] ** 2).mean()))
        vs = float(np.abs(y - ref)[mask].mean()) if ref is not None else float("nan")
        print("%-22s %6d %8.2f %9.3f" % (name, i, det, vs))
    crops = [tuple(int(v) for v in c.split(",")) for c in a.crop] or [
        (int(w * 0.55), int(h * 0.55), 300, 200), (int(w * 0.30), int(h * 0.62), 300, 200), (int(w * 0.66), int(h * 0.28), 300, 200)]
    out = os.path.join(a.run, "spritefilter")
    os.makedirs(out, exist_ok=True)
    s = 3
    for (x, y0, cw, ch) in crops:
        sheet = Image.new("RGB", (len(picks) * (cw * s + 8), ch * s + 40), (20, 20, 24))
        dr = ImageDraw.Draw(sheet)
        for j, (name, _) in enumerate(picks):
            c = imgs[name][y0:y0 + ch, x:x + cw]
            im = Image.fromarray(c).convert("RGB").resize((cw * s, ch * s), Image.NEAREST)
            sheet.paste(im, (j * (cw * s + 8), 40))
            dr.text((j * (cw * s + 8) + 6, 10), name, fill=(230, 230, 230))
        p = os.path.join(out, "modes-%d-%d.png" % (x, y0))
        sheet.save(p)
        print("crops:", p)


if __name__ == "__main__":
    main()
