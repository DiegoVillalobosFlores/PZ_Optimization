#!/usr/bin/env python3
"""Reads a 16-bit PQ / BT.2020 RGB frame (from probe-capture.sh) and prints the displayed nits of the
hdrprobe patches: 10 patches on the top 45 %, the log ramp below, the reference-white strip at the bottom."""
import sys

import numpy as np
from PIL import Image


def pq_to_nits(v):
    m1, m2, c1, c2, c3 = 0.1593017578125, 78.84375, 0.8359375, 18.8515625, 18.6875
    p = np.power(np.clip(v, 0, 1), 1 / m2)
    return 10000 * np.power(np.maximum(p - c1, 0) / (c2 - c3 * p), 1 / m1)


def load(path):
    a = np.asarray(Image.open(path)).astype(np.float64)
    return a / (65535.0 if a.max() > 255 else 255.0)


def main():
    for path in sys.argv[1:]:
        img = load(path)
        h, w = img.shape[:2]
        # luminance from BT.2020 PQ: decode each channel then weight
        nits = pq_to_nits(img[..., :3])
        y = nits @ np.array([0.2627, 0.6780, 0.0593])
        patches = [np.median(y[int(h * 0.2):int(h * 0.25), int(w * (i + 0.4) / 10):int(w * (i + 0.6) / 10)]) for i in range(10)]
        ramp = [np.median(y[int(h * 0.6):int(h * 0.65), int(w * x) - 2:int(w * x) + 2]) for x in (0.1, 0.3, 0.5, 0.7, 0.8, 0.9, 0.99)]
        strip = np.median(y[int(h * 0.92):int(h * 0.95), w // 3:2 * w // 3])
        print(f"{path}: patches(target 0 .05 1 10 80 203 505 1000 1300 2000) = " + " ".join(f"{p:.1f}" for p in patches))
        print(f"   ramp@.1..0.99 = " + " ".join(f"{r:.1f}" for r in ramp) + f"   ref strip = {strip:.1f}   max = {y.max():.0f}")


if __name__ == "__main__":
    main()
