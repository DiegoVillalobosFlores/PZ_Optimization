#!/usr/bin/env python3
"""Per-frame light levels of an AV1 HDR capture (gpu-screen-recorder av1_hdr: PQ / BT.2020, KWin's output re-anchored
so the desktop's reference white is 203 cd/m²; tools/hdr/probe-capture.sh established it).

  hdrvideo.py <recording.mp4 | run dir> [--fps 10] [--ref 391] [--from S] [--to S] [--peaks N]

Prints one line per second (max over its frames of the frame's peak, p99.9 and mean in panel nits: capture nits x
ref/203) and the N brightest frames with their times, so a lightning strike or a headlight sweep shows up as numbers.
"""
import argparse
import os
import subprocess
import sys

import numpy as np


def pq_to_nits(v):
    m1, m2, c1, c2, c3 = 0.1593017578125, 78.84375, 0.8359375, 18.8515625, 18.6875
    p = np.power(np.clip(v, 0, 1), 1 / m2)
    return 10000 * np.power(np.maximum(p - c1, 0) / (c2 - c3 * p), 1 / m1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("path")
    ap.add_argument("--fps", type=float, default=10)
    ap.add_argument("--ref", type=float, default=391, help="the desktop's reference white in panel nits (KWin preferred description)")
    ap.add_argument("--from", dest="t0", type=float, default=0)
    ap.add_argument("--to", dest="t1", type=float, default=0)
    ap.add_argument("--peaks", type=int, default=8)
    ap.add_argument("--width", type=int, default=640)
    ap.add_argument("--window", help="a,b: medians over frames in [a, b] s (peak, p99.9, mean, share above the reference white)")
    a = ap.parse_args()
    path = a.path
    if os.path.isdir(path):
        path = os.path.join(path, "recording.mp4")
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0", path],
                           capture_output=True, text=True, check=True).stdout.strip().split(",")
    w0, h0 = int(probe[0]), int(probe[1])
    w = a.width
    h = int(round(h0 * w / w0 / 2)) * 2
    cmd = ["ffmpeg", "-loglevel", "error"]
    if a.t0:
        cmd += ["-ss", str(a.t0)]
    cmd += ["-i", path]
    if a.t1:
        cmd += ["-t", str(a.t1 - a.t0)]
    cmd += ["-vf", f"fps={a.fps},scale={w}:{h}:in_color_matrix=bt2020:in_range=tv:out_range=pc:flags=area,format=rgb48le", "-f", "rawvideo", "-"]
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    frames = np.frombuffer(raw, dtype="<u2").reshape(-1, h, w, 3).astype(np.float32) / 65535.0
    scale = a.ref / 203.0
    rows = []
    for i, f in enumerate(frames):
        nits = pq_to_nits(f) @ np.array([0.2627, 0.6780, 0.0593], dtype=np.float32) * scale
        rows.append((a.t0 + i / a.fps, float(nits.max()), float(np.percentile(nits, 99.9)), float(nits.mean()), float((nits > a.ref).mean() * 100)))
    if not rows:
        sys.exit("no frames decoded")
    print(f"{path}: {len(rows)} frames at {a.fps} fps, panel nits = capture x {scale:.3f} (reference white {a.ref:.0f})")
    if a.window:
        lo, hi = map(float, a.window.split(","))
        sel = np.array([r[1:] for r in rows if lo <= r[0] <= hi])
        med = np.median(sel, axis=0)
        print(f"  window {lo:.0f}-{hi:.0f} s ({len(sel)} frames): median peak {med[0]:.0f}  p99.9 {med[1]:.0f}  mean {med[2]:.1f}  "
              f"above white {med[3]:.2f}%  | max p99.9 {sel[:, 1].max():.0f}")
        return
    per_sec = {}
    for t, mx, p999, mean, _ in rows:
        s = int(t)
        cur = per_sec.get(s, (0, 0, 0))
        per_sec[s] = (max(cur[0], mx), max(cur[1], p999), max(cur[2], mean))
    for s in sorted(per_sec):
        mx, p999, mean = per_sec[s]
        print(f"  t={s:4d}s  peak {mx:6.0f}  p99.9 {p999:6.0f}  mean {mean:5.1f}")
    print("brightest frames:")
    for t, mx, p999, mean, _ in sorted(rows, key=lambda r: -r[2])[: a.peaks]:
        print(f"  t={t:7.2f}s  peak {mx:6.0f}  p99.9 {p999:6.0f}  mean {mean:5.1f}")


if __name__ == "__main__":
    main()
