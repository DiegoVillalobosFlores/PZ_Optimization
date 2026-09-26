#!/usr/bin/env python3
"""Sharpness and temporal stability of a moving camera from a devCapture sequence (2026-09-26, sprite filter).

  harness/spritefilter/shimmer.py <run> [<run>...] [--patch 384] [--pairs 60]

For every pair of consecutive captured frames: the global motion of the world (the camera follows the car, so the
centre band with the car and the top-left overlay corner are left out) by phase correlation on large patches, the
earlier frame moved by exactly that sub-pixel shift (a Fourier shift: what a perfectly band-limited picture would look
like one frame later), and the high-pass (Laplacian) energy of the difference to the later frame. A picture that
aliases (crawling texel edges, shimmering fine lines) changes in its fine detail beyond the shift; a blurred picture
changes little but also has little detail. Printed per run, medians over patches and pairs:

  detail      RMS Laplacian of the frames (sharpness)
  residual    RMS Laplacian of the motion-compensated difference (crawl and shimmer)
  ratio       residual / detail (lower = steadier for its sharpness)
"""
import argparse
import os
import sys

import numpy as np


def frames(run):
    d = os.path.join(run, "capture")
    lines = open(os.path.join(d, "index.txt")).read().split("\n")
    head = dict(kv.split("=") for kv in lines[0].split())
    w, h = int(head["w"]), int(head["h"])
    gray = head.get("fmt") == "gray"
    stamps = [int(x) for x in lines[1:] if x.strip()]
    raw = np.memmap(os.path.join(d, "frames.gray" if gray else "frames.rgba"), dtype=np.uint8, mode="r")
    n = min(len(stamps), raw.size // (w * h * (1 if gray else 4)))
    for i in range(n):
        if gray:
            yield raw[i * w * h:(i + 1) * w * h].reshape(h, w)[::-1, :].astype(np.float32)
        else:
            f = raw[i * w * h * 4:(i + 1) * w * h * 4].reshape(h, w, 4)[::-1, :, :3].astype(np.float32)
            yield f[..., 0] * 0.299 + f[..., 1] * 0.587 + f[..., 2] * 0.114


def lap(a):
    return 4 * a[1:-1, 1:-1] - a[:-2, 1:-1] - a[2:, 1:-1] - a[1:-1, :-2] - a[1:-1, 2:]


def phase_shift(a, b):
    """Sub-pixel (dy, dx) with b ~= a moved by (dy, dx)."""
    win = np.outer(np.hanning(a.shape[0]), np.hanning(a.shape[1]))
    A = np.fft.fft2((a - a.mean()) * win)
    B = np.fft.fft2((b - b.mean()) * win)
    r = B * np.conj(A)
    r /= np.abs(r) + 1e-9
    c = np.fft.ifft2(r).real
    iy, ix = np.unravel_index(np.argmax(c), c.shape)

    def sub(cm, c0, cp):
        d = cm - 2 * c0 + cp
        return 0.0 if abs(d) < 1e-12 else 0.5 * (cm - cp) / d

    h, w = c.shape
    dy = iy + sub(c[(iy - 1) % h, ix], c[iy, ix], c[(iy + 1) % h, ix])
    dx = ix + sub(c[iy, (ix - 1) % w], c[iy, ix], c[iy, (ix + 1) % w])
    if dy > h / 2:
        dy -= h
    if dx > w / 2:
        dx -= w
    return dy, dx, c.max()


def fourier_move(a, dy, dx):
    fy = np.fft.fftfreq(a.shape[0])[:, None]
    fx = np.fft.fftfreq(a.shape[1])[None, :]
    return np.fft.ifft2(np.fft.fft2(a) * np.exp(-2j * np.pi * (fy * dy + fx * dx))).real


def analyse(run, patch, pairs):
    it = frames(run)
    prev = next(it, None)
    if prev is None:
        return None
    h, w = prev.shape
    # patch origins: a grid over the frame without the overlay corner and the centre band (car, player)
    origins = []
    for y in range(0, h - patch, patch):
        for x in range(0, w - patch, patch):
            cy, cx = y + patch / 2, x + patch / 2
            if cy < h * 0.32 and cx < w * 0.3:
                continue
            if abs(cy - h / 2) < h * 0.18 and abs(cx - w / 2) < w * 0.12:
                continue
            origins.append((y, x))
    details, residuals, ratios, shifts = [], [], [], []
    m = 24  # margin dropped after the Fourier move (wrap-around)
    for k, cur in enumerate(it):
        if k >= pairs:
            break
        for (y, x) in origins:
            a, b = prev[y:y + patch, x:x + patch], cur[y:y + patch, x:x + patch]
            if a.std() < 4:
                continue  # flat (sky-less world: black void, water)
            dy, dx, peak = phase_shift(a, b)
            if peak < 0.05 or abs(dy) > patch / 4 or abs(dx) > patch / 4:
                continue  # new content or a moving object: no single shift
            moved = fourier_move(a, dy, dx)
            ra, rb = lap(moved)[m:-m, m:-m], lap(b)[m:-m, m:-m]
            det = float(np.sqrt((rb ** 2).mean()))
            res = float(np.sqrt(((rb - ra) ** 2).mean()))
            if det < 1:
                continue
            details.append(det)
            residuals.append(res)
            ratios.append(res / det)
            shifts.append(np.hypot(dy, dx))
        prev = cur
    if not ratios:
        return None
    return {
        "patches": len(ratios),
        "shift_px": float(np.median(shifts)),
        "detail": float(np.median(details)),
        "residual": float(np.median(residuals)),
        "ratio": float(np.median(ratios)),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--patch", type=int, default=384)
    ap.add_argument("--pairs", type=int, default=60)
    a = ap.parse_args()
    for run in a.runs:
        r = analyse(run, a.patch, a.pairs)
        if r is None:
            print("%s: no usable capture" % run)
            continue
        print("%-60s patches=%5d shift=%5.1f px  detail=%6.2f  residual=%6.2f  ratio=%.3f"
              % (os.path.basename(run.rstrip("/")), r["patches"], r["shift_px"], r["detail"], r["residual"], r["ratio"]))


if __name__ == "__main__":
    sys.exit(main())
