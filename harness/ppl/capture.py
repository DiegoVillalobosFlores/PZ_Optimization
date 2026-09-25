#!/usr/bin/env python3
"""Reads a pzopt.FrameCapture sequence (<run>/capture/frames.rgba + index.txt).

    capture.py <run> [--png DIR] [--every N] [--crop x0,y0,x1,y1]

Prints the temporal metrics of the sequence: per frame the pixels whose luminance jumps by more than 24/255 against the
previous frame (the "hard jump" count used for the blocky-light work: a light that moves in square steps or re-bakes
chunk by chunk jumps; a smoothly moving one does not), their mean and p90, and the mean absolute luminance change.
"""
import argparse
import os

import numpy as np


def load(run):
    d = os.path.join(run, "capture")
    lines = open(os.path.join(d, "index.txt")).read().split("\n")
    head = dict(kv.split("=") for kv in lines[0].split())
    w, h = int(head["w"]), int(head["h"])
    stamps = [int(x) for x in lines[1:] if x.strip()]
    raw = np.fromfile(os.path.join(d, "frames.rgba"), dtype=np.uint8)
    n = min(len(stamps), raw.size // (w * h * 4))
    frames = raw[: n * w * h * 4].reshape(n, h, w, 4)[:, ::-1, :, :3]
    return frames, np.array(stamps[:n])


def lum(f):
    return f[..., 0] * 0.299 + f[..., 1] * 0.587 + f[..., 2] * 0.114


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--png")
    ap.add_argument("--every", type=int, default=10)
    ap.add_argument("--crop")
    a = ap.parse_args()
    frames, stamps = load(a.run)
    if a.crop:
        x0, y0, x1, y1 = map(int, a.crop.split(","))
        frames = frames[:, y0:y1, x0:x1]
    L = lum(frames.astype(np.float32))
    d = np.abs(np.diff(L, axis=0))
    jumps = (d > 24).reshape(len(d), -1).sum(1)
    dt = np.diff(stamps)
    print(f"{len(frames)} frames {frames.shape[2]}x{frames.shape[1]}, {1000 / max(dt.mean(), 1):.1f} fps captured; hard jumps/frame mean {jumps.mean():.0f} "
          f"p90 {np.percentile(jumps, 90):.0f} max {jumps.max()}; mean |dL| {d.mean():.3f}")
    if a.png:
        from PIL import Image
        os.makedirs(a.png, exist_ok=True)
        for i in range(0, len(frames), a.every):
            Image.fromarray(frames[i]).save(os.path.join(a.png, f"f{i:04d}.png"))


if __name__ == "__main__":
    main()
