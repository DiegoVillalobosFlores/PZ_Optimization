#!/usr/bin/env python3
"""Per-segment frame and GPU cost of a run with devPplToggleAt (pixelLight flipped mid-run).

    split.py <run> [--skip 3] [--start-mode on|off]

Segments are cut at the console's "pixel light: dev toggle ... epoch_ms=" lines; the first --skip seconds after each cut
(every texture re-bakes) and before the first route second are left out. Reports per segment: frames, fps, frame time
mean / p50 / p99, GPU busy ms per frame (the overlay's gpu_ms: the cap does not hide it), game / render thread load.
"""
import argparse
import csv
import os
import re

import numpy as np


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--skip", type=float, default=3.0)
    ap.add_argument("--start-mode", default=None, help="mode before the first toggle (default: from the console)")
    a = ap.parse_args()
    cuts, modes = [], []
    first = None
    with open(os.path.join(a.run, "console.txt"), errors="replace") as f:
        for line in f:
            m = re.search(r"pixel light: dev toggle, now (per-pixel|stock baked light) epoch_ms=(\d+)", line)
            if m:
                cuts.append(int(m.group(2)))
                modes.append("on" if m.group(1) == "per-pixel" else "off")
            if first is None and "pixelLight=true" in line:
                first = "on"
    start = a.start_mode or (("off" if modes[0] == "on" else "on") if modes else (first or "?"))
    rows = []
    with open(os.path.join(a.run, "pzopt-overlay.out")) as f:
        for r in csv.DictReader(f):
            rows.append((int(r["epoch_ms"]), float(r["frametime"]), float(r["gpu_ms"]), float(r["game_load"]), float(r["render_load"])))
    t = np.array([r[0] for r in rows])
    # the route window from pzopt-schedule.out when present
    t0 = t[0]
    sched = os.path.join(a.run, "pzopt-schedule.out")
    if os.path.exists(sched):
        for line in open(sched):
            if line.startswith("route_start_epoch_ms="):
                t0 = int(line.split("=")[1])
    edges = [t0] + cuts + [t[-1] + 1]
    seg_modes = [start] + modes
    for i in range(len(edges) - 1):
        lo = edges[i] + (a.skip * 1000 if i > 0 else 0)
        hi = edges[i + 1]
        m = (t >= lo) & (t < hi)
        if m.sum() < 30:
            continue
        ft = np.array([r[1] for r, k in zip(rows, m) if k])
        g = np.array([r[2] for r, k in zip(rows, m) if k])
        gl = np.array([r[3] for r, k in zip(rows, m) if k])
        rl = np.array([r[4] for r, k in zip(rows, m) if k])
        print(f"{seg_modes[i]:>3}  {(hi - lo) / 1000:5.1f} s  frames {len(ft):5d}  fps {1000 / ft.mean():6.1f}  frame mean {ft.mean():.2f} p50 {np.percentile(ft, 50):.2f} "
              f"p99 {np.percentile(ft, 99):.2f} ms  gpu {g.mean():.3f} ms (p50 {np.percentile(g, 50):.3f})  game {gl.mean():.0f} % render {rl.mean():.0f} %")


if __name__ == "__main__":
    main()
