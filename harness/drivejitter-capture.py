#!/usr/bin/env python3
"""Driving smoothness from the presented pictures (a devCapture sequence), the check that what the screen shows moves
like pzopt-drivejitter.out says.

For every pair of consecutive captured frames: the world's shift (phase correlation over the frame with a box around
the car and the HUD corners masked out, sub-pixel peak) and the car's shift on screen (phase correlation of a box around
the car, the peak nearest the car's expected motion). Both in screen pixels. Then, like drivejitter.py, each frame's
cumulative position against a quadratic fitted over +-W s of display times (the refresh grid when --vsync is given,
else the capture's epoch ms), and the per-frame steps against the fit's (stall / back / jump).

    harness/drivejitter-capture.py <run> [--car x,y,w,h] [--vsync HZ] [--window 0.08]

--car is the car's box in capture pixels (default: from pzopt-drivejitter.out's scr_x/scr_y at the capture start).
"""
import argparse
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import drivejitter as dj  # noqa: E402


def load(run):
    d = os.path.join(run, "capture")
    lines = open(os.path.join(d, "index.txt")).read().split("\n")
    head = dict(kv.split("=") for kv in lines[0].split())
    w, h = int(head["w"]), int(head["h"])
    stamps = [int(x) for x in lines[1:] if x.strip()]
    raw = np.fromfile(os.path.join(d, "frames.rgba"), dtype=np.uint8, count=len(stamps) * w * h * 4)
    n = min(len(stamps), raw.size // (w * h * 4))
    frames = raw[: n * w * h * 4].reshape(n, h, w, 4)[:, ::-1, :, :3]
    return frames, np.array(stamps[:n], dtype=np.float64), head


def lum(f):
    return (f[..., 0] * 0.299 + f[..., 1] * 0.587 + f[..., 2] * 0.114).astype(np.float32)


def phase_shift(a, b, mask=None, near=None, radius=None, avoid=None, ratio=0.35):
    """Shift (dx, dy) that moves a onto b, sub-pixel; mask weights pixels (1 = used). avoid=(x, y): the strongest peak
    at least 2 px away from that shift when it reaches `ratio` of the peak there, else the peak there (the car box
    holds the car and the road: the road moves with the world, the car moves on its own unless it is still in the world)."""
    if mask is not None:
        a = (a - a[mask > 0].mean()) * mask
        b = (b - b[mask > 0].mean()) * mask
    else:
        a = a - a.mean()
        b = b - b.mean()
    win = np.outer(np.hanning(a.shape[0]), np.hanning(a.shape[1])).astype(np.float32)
    A = np.fft.fft2(a * win)
    B = np.fft.fft2(b * win)
    R = B * np.conj(A)
    R /= np.abs(R) + 1e-9
    r = np.real(np.fft.ifft2(R))
    h, w = r.shape
    if near is not None:
        # only peaks within radius of the expected shift
        ys = np.fft.fftfreq(h) * h
        xs = np.fft.fftfreq(w) * w
        dy = ys[:, None] - near[1]
        dx = xs[None, :] - near[0]
        r = np.where(dx * dx + dy * dy <= radius * radius, r, -np.inf)
    if avoid is not None:
        ys = np.fft.fftfreq(h) * h
        xs = np.fft.fftfreq(w) * w
        d2 = (xs[None, :] - avoid[0]) ** 2 + (ys[:, None] - avoid[1]) ** 2
        top = r[d2 <= 4].max() if (d2 <= 4).any() else -np.inf
        other = np.where(d2 > 4, r, -np.inf)
        if other.max() < ratio * top:
            r = np.where(d2 <= 4, r, -np.inf)
        else:
            r = other
    iy, ix = np.unravel_index(np.argmax(r), r.shape)

    def sub(c, m, n):
        l, cc, rr = m[(c - 1) % n], m[c], m[(c + 1) % n]
        den = l - 2 * cc + rr
        return 0.0 if not np.isfinite(den) or den == 0 else 0.5 * (l - rr) / den

    row = r[iy, :]
    col = r[:, ix]
    fx = ix + sub(ix, row, w)
    fy = iy + sub(iy, col, h)
    if fx > w / 2:
        fx -= w
    if fy > h / 2:
        fy -= h
    return fx, fy


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("run")
    ap.add_argument("--car", help="x,y,w,h of the car box in capture pixels")
    ap.add_argument("--vsync", type=float, default=0.0)
    ap.add_argument("--window", type=float, default=0.08)
    ap.add_argument("--scale", type=float, default=0.0, help="capture scale (default from index.txt or 0.5)")
    a = ap.parse_args()
    frames, stamps, head = load(a.run)
    scale = a.scale or float(head.get("scale", 0.5))
    n, h, w = frames.shape[0], frames.shape[1], frames.shape[2]
    if a.car:
        cx, cy, cw, ch = map(int, a.car.split(","))
    else:
        rows = dj.load(a.run)
        # the drive log's t_us counts from its first row; the capture's stamps are epoch ms: use the first moving row near
        # the capture's middle frame by position only (the car sits almost still on screen while driving)
        mov = [r for r in rows if abs(r["kmh"]) > 20]
        r = mov[len(mov) // 2]
        z = r["zoom"] or 1.0
        px, py = r["scr_x"] / z * scale, r["scr_y"] / z * scale
        cw = ch = int(64 * scale * 2)
        cx, cy = int(px - cw / 2), int(py - ch / 2)
    cx = max(0, min(w - cw, cx))
    cy = max(0, min(h - ch, cy))
    L = [lum(f) for f in frames]
    mask = np.ones((h, w), np.float32)
    pad = int(40 * scale * 2)
    mask[max(0, cy - pad):cy + ch + pad, max(0, cx - pad):cx + cw + pad] = 0
    mask[: int(h * 0.12), :] = 0  # HUD / overlay rows
    mask[int(h * 0.88):, :] = 0
    world = [(0.0, 0.0)]
    car = [(0.0, 0.0)]
    for i in range(1, n):
        dx, dy = phase_shift(L[i - 1], L[i], mask)
        world.append((world[-1][0] + dx, world[-1][1] + dy))
        ca = L[i - 1][cy:cy + ch, cx:cx + cw]
        cb = L[i][cy:cy + ch, cx:cx + cw]
        ex, ey = phase_shift(ca, cb, avoid=(dx, dy))
        car.append((car[-1][0] + ex, car[-1][1] + ey))
    t = [s / 1000.0 for s in stamps]
    if a.vsync > 0:
        t = dj.vsync_clock(t, 1.0 / a.vsync)
    print(f"{os.path.basename(os.path.normpath(a.run))}: {n} captured frames {w}x{h} (scale {scale}), car box {cx},{cy} {cw}x{ch}")
    for name, pos in (("world", world), ("car", car)):
        xs = [p[0] / scale for p in pos]
        ys = [p[1] / scale for p in pos]
        rx, vx = dj.quadfit_residuals(t, xs, a.window)
        ry, vy = dj.quadfit_residuals(t, ys, a.window)
        mags = [math.hypot(rx[i], ry[i]) for i in range(n) if not math.isnan(rx[i]) and not math.isnan(ry[i])]
        stall = back = jump = counted = 0
        for i in range(1, n):
            if math.isnan(vx[i]) or math.isnan(vy[i]):
                continue
            dt = t[i] - t[i - 1]
            ex, ey = vx[i] * dt, vy[i] * dt
            el = math.hypot(ex, ey)
            if el < 0.75:
                continue
            proj = ((xs[i] - xs[i - 1]) * ex + (ys[i] - ys[i - 1]) * ey) / el
            counted += 1
            if proj < 0:
                back += 1
            elif proj < 0.5 * el:
                stall += 1
            elif proj > 1.5 * el:
                jump += 1
        if not mags:
            print(f"  {name}: no fit")
            continue
        rms = math.sqrt(sum(m * m for m in mags) / len(mags))
        pc = (lambda c: 100.0 * c / counted) if counted else (lambda c: math.nan)
        print(f"  {name:5s} residual rms={rms:.2f}px p50={dj.pct(mags, 50):.2f} p99={dj.pct(mags, 99):.2f} max={max(mags):.1f}  "
              f"steps: stall={pc(stall):.1f}% back={pc(back):.1f}% jump={pc(jump):.1f}% (of {counted})")


if __name__ == "__main__":
    main()
