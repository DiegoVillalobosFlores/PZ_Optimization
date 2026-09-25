#!/usr/bin/env python3
"""Screenshot parity for texture compression: PSNR of each run's shot-game.png against a reference run, plus an
amplified difference image per run (<out>/texdiff-<label>.png). A second reference run gives the noise floor
(zombies, weather and animation differ between runs).

    harness/texdiff.py <reference run> <run> [<run> ...] [--out DIR] [--crop x0,y0,x1,y1]
"""
import argparse
import os
import sys

import numpy as np
from PIL import Image


HIGHPASS = 0.0


def load(run):
    p = os.path.join(run, "shot-game.png")
    if not os.path.exists(p):
        sys.exit(f"no shot-game.png in {run}")
    im = Image.open(p).convert("RGB")
    a = np.asarray(im, dtype=np.float64)
    if HIGHPASS > 0:
        # texture detail only: minus a Gaussian blur, so moving cloud shadows and light drift between runs drop out
        from PIL import ImageFilter
        a = a - np.asarray(im.filter(ImageFilter.GaussianBlur(HIGHPASS)), dtype=np.float64) + 128.0
    return a


def psnr(a, b):
    mse = np.mean((a - b) ** 2)
    return 99.0 if mse == 0 else 10 * np.log10(255.0 ** 2 / mse)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ref")
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--out", default="/tmp")
    ap.add_argument("--crop")
    ap.add_argument("--highpass", type=float, default=0.0, help="Gaussian sigma subtracted before comparing (0 = off)")
    a = ap.parse_args()
    global HIGHPASS
    HIGHPASS = a.highpass
    ref = load(a.ref)
    if a.crop:
        x0, y0, x1, y1 = map(int, a.crop.split(","))
        ref = ref[y0:y1, x0:x1]
    print(f"reference {os.path.basename(a.ref.rstrip('/'))} {ref.shape[1]}x{ref.shape[0]}")
    for run in a.runs:
        img = load(run)
        if a.crop:
            img = img[y0:y1, x0:x1]
        if img.shape != ref.shape:
            print(f"  {run}: size {img.shape} != {ref.shape}")
            continue
        d = np.abs(img - ref).max(axis=2)
        label = os.path.basename(run.rstrip("/"))
        print(f"  {label:48s} PSNR {psnr(img, ref):6.2f} dB   mean |d| {d.mean():5.2f}   "
              f"pixels |d|>8: {100 * np.mean(d > 8):5.2f} %   |d|>32: {100 * np.mean(d > 32):5.2f} %")
        Image.fromarray(np.clip(d * 4, 0, 255).astype(np.uint8)).save(os.path.join(a.out, f"texdiff-{label}.png"))


if __name__ == "__main__":
    main()
