#!/usr/bin/env python3
"""Black holes inside the drawn world, per recorded run: a check for bakes deferred too long.

    harness/holes.py <run> [<run> ...] [--fps 2] [--width 640]

Decodes <run>/recording.mp4 over the route window (schedule.log: "route starts at +M s", pzopt-bench.out route length)
at --fps, downscaled to --width, and per frame measures the black area (all channels < 10/255) that is NOT connected to
the screen border: the unloaded world beyond the chunk grid touches the border, a chunk level that should be drawn but
is not leaves an enclosed black patch. Cut-away buildings with unexplored rooms are enclosed black too (the game's own),
so compare runs of the same route: the difference is what a change adds. Prints mean / p90 / max share of the frame
and how many frames exceed 0.5 %, then the black connected to the border (the world beyond the loaded grid, plus any
undrawn level at the screen edge): same route, same share expected.
"""
import argparse, re, subprocess
from pathlib import Path

import numpy as np

ap = argparse.ArgumentParser()
ap.add_argument("runs", nargs="+")
ap.add_argument("--fps", type=float, default=2.0)
ap.add_argument("--width", type=int, default=640)
a = ap.parse_args()

for r in a.runs:
    run = Path(r)
    kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
    length = (int(kv["route_end_epoch_ms"]) - int(kv["route_start_epoch_ms"])) / 1000.0
    sched = run / "schedule.log"
    m = re.search(r"route starts at \+(\d+) s", sched.read_text()) if sched.exists() else None
    if m:
        start = int(m.group(1))
    else:
        # --no-mangohud runs have no schedule.log: the capture runs from launch to exit, so it began at the file's
        # modification time minus its duration
        dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0",
                                    str(run / "recording.mp4")], capture_output=True, text=True).stdout)
        began = (run / "recording.mp4").stat().st_mtime - dur
        start = max(0.0, int(kv["route_start_epoch_ms"]) / 1000.0 - began)
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
                            "-of", "csv=p=0", str(run / "recording.mp4")], capture_output=True, text=True).stdout.split(",")
    w0, h0 = int(probe[0]), int(probe[1])
    w = a.width
    h = int(round(h0 * w / w0 / 2)) * 2
    raw = subprocess.run(["ffmpeg", "-v", "error", "-ss", str(start), "-t", str(length), "-i", str(run / "recording.mp4"),
                          "-vf", f"fps={a.fps},scale={w}:{h}", "-pix_fmt", "rgb24", "-f", "rawvideo", "-"],
                         capture_output=True).stdout
    frames = np.frombuffer(raw, np.uint8).reshape(-1, h, w, 3)
    shares, edge = [], []
    for f in frames:
        black = (f < 10).all(axis=2)
        # ignore the HUD strip at the top and the dashboard at the bottom centre
        black[: h // 40, :] = False
        # flood the black pixels reachable from the border (4-connected), by repeated dilation inside the mask
        reach = np.zeros_like(black)
        reach[0, :], reach[-1, :], reach[:, 0], reach[:, -1] = black[0, :], black[-1, :], black[:, 0], black[:, -1]
        while True:
            grow = reach.copy()
            grow[1:, :] |= reach[:-1, :]
            grow[:-1, :] |= reach[1:, :]
            grow[:, 1:] |= reach[:, :-1]
            grow[:, :-1] |= reach[:, 1:]
            grow &= black
            if (grow == reach).all():
                break
            reach = grow
        inside = black & ~reach
        shares.append(inside.mean() * 100)
        edge.append(reach.mean() * 100)
    s, e = np.array(shares), np.array(edge)
    print(f"{run.name}: {len(s)} frames, enclosed black mean {s.mean():.2f} % p90 {np.percentile(s, 90):.2f} % max {s.max():.2f} %,"
          f" frames > 0.5 %: {int((s > 0.5).sum())}; edge-connected black mean {e.mean():.1f} % p90 {np.percentile(e, 90):.1f} %")
