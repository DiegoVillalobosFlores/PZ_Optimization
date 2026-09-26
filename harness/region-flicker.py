#!/usr/bin/env python3
"""Where on screen a recording flickers: flicker.py's transient metric (a pixel that jumps by >= 32 and comes back
within 3 frames) streamed over a whole window at the capture's own resolution, summed per screen region.

Built for the flip report of 2026-09-25 (textures flicker at the top left while the player walks in circles): the
camera follows a player on a small circle, so steady motion is A -> B -> C and not counted, while a texture that
blinks for a frame or two is. The HUD (left icon column, top bars, hotbar, top-right icons, version text) and a disc
round the screen centre (the walking player and the mouse cursor) are masked out.

VIDEO may also be a run dir with a pzopt.FrameCapture sequence (capture/frames.rgba, --prop devCapture=...): every
presented frame, START/END counted from the first captured frame; frames the writer dropped show as stamp gaps.

Usage: region-flicker.py VIDEO|RUN START_S END_S [--json out.json] [--heat out.png] [--thresh 32] [--maxk 3]
Prints transient px per frame for a 3 x 3 grid of regions (the world pixels in each), per second for the top-left
region, the busiest 60 px cells and the frames with the most top-left transients.
"""
import argparse
import json
import os
import subprocess
import sys

import numpy as np


def frames(path, start, end, w, h):
    cmd = ["ffmpeg", "-v", "error", "-ss", str(start), "-to", str(end), "-i", path, "-vf", "format=gray", "-f", "rawvideo", "-"]
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    size = w * h
    while True:
        b = p.stdout.read(size)
        if len(b) < size:
            break
        yield np.frombuffer(b, dtype=np.uint8).reshape(h, w).astype(np.int16)
    p.wait()


def capture(run, start, end):
    """(w, h, fps, frame generator, gaps) of a pzopt.FrameCapture sequence."""
    d = os.path.join(run, "capture")
    lines = open(os.path.join(d, "index.txt")).read().split("\n")
    head = dict(kv.split("=") for kv in lines[0].split())
    w, h = int(head["w"]), int(head["h"])
    stamps = np.array([int(x) for x in lines[1:] if x.strip()])
    gray = head.get("fmt") == "gray"  # devCapture=...,gray: one luma byte per pixel
    raw = np.memmap(os.path.join(d, "frames.gray" if gray else "frames.rgba"), dtype=np.uint8, mode="r")
    n = min(len(stamps), raw.size // (w * h * (1 if gray else 4)))
    t = (stamps[:n] - stamps[0]) / 1000.0
    sel = np.nonzero((t >= start) & (t < end))[0]
    dt = np.diff(stamps[sel])
    fps = 1000.0 / np.median(dt) if len(dt) else 60.0
    gaps = int((dt > 1.6 * np.median(dt)).sum()) if len(dt) else 0

    def gen():
        for i in sel:
            if gray:
                yield raw[i * w * h:(i + 1) * w * h].reshape(h, w)[::-1, :].astype(np.int16)
                continue
            f = raw[i * w * h * 4:(i + 1) * w * h * 4].reshape(h, w, 4)[::-1, :, :3].astype(np.float32)
            yield (f[..., 0] * 0.299 + f[..., 1] * 0.587 + f[..., 2] * 0.114).astype(np.int16)
    return w, h, fps, gen(), gaps


def hud_mask(w, h):
    """True where the world is (1920x1080 layout of the flip; scaled for other sizes)."""
    m = np.ones((h, w), dtype=bool)
    sx, sy = w / 1920, h / 1080
    def box(x0, y0, x1, y1):
        m[int(y0 * sy):int(y1 * sy), int(x0 * sx):int(x1 * sx)] = False
    box(0, 0, 80, 560)        # left icon column
    box(0, 0, 1920, 22)       # inventory / loot title bars
    box(1740, 0, 1920, 180)   # speed buttons, moodle
    box(840, 990, 1080, 1080)  # hotbar
    box(1660, 1040, 1920, 1080)  # version text
    yy, xx = np.mgrid[0:h, 0:w]
    m &= (xx - w / 2) ** 2 + (yy - h / 2) ** 2 > (190 * sx) ** 2  # the walking player + the cursor
    return m


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("start", type=float)
    ap.add_argument("end", type=float)
    ap.add_argument("--thresh", type=int, default=32)
    ap.add_argument("--maxk", type=int, default=3)
    ap.add_argument("--cell", type=int, default=60)
    ap.add_argument("--json")
    ap.add_argument("--heat")
    a = ap.parse_args()
    gaps = None
    if os.path.isdir(os.path.join(a.video, "capture")):
        w, h, fps, source, gaps = capture(a.video, a.start, a.end)
    else:
        probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,r_frame_rate",
                                "-of", "csv=p=0", a.video], capture_output=True, text=True, check=True).stdout.strip().split(",")
        w, h = int(probe[0]), int(probe[1])
        num, den = (int(x) for x in probe[2].split("/"))
        fps = num / den if den else 60.0
        source = frames(a.video, a.start, a.end, w, h)
    world = hud_mask(w, h)
    K, t = a.maxk, a.thresh
    ring = []
    heat = np.zeros((h, w), dtype=np.int32)
    per_frame = []  # (frame index, [3x3 counts])
    rx = [0, w // 3, 2 * w // 3, w]
    ry = [0, h // 3, 2 * h // 3, h]
    first = None
    for i, f in enumerate(source):
        if first is None:
            first = f.astype(np.uint8)
        ring.append(f)
        if len(ring) < K + 2:
            continue
        base, cur, nxt = ring[0], ring[1], ring[2:]
        changed = (np.abs(cur - base) >= t) & world
        hit = np.zeros_like(changed)
        if changed.any():
            away = changed.copy()
            for g in nxt:
                d = np.abs(g - base)
                back = d < t // 2
                hit |= away & back
                away &= ~back & (d >= t)
        heat += hit
        per_frame.append((i - K, [[int(hit[ry[r]:ry[r + 1], rx[c]:rx[c + 1]].sum()) for c in range(3)] for r in range(3)]))
        ring.pop(0)
    n = len(per_frame)
    if n == 0:
        sys.exit("no frames")
    counts = np.array([p[1] for p in per_frame])  # n x 3 x 3
    area = np.array([[int(world[ry[r]:ry[r + 1], rx[c]:rx[c + 1]].sum()) for c in range(3)] for r in range(3)])
    names = [["top-left", "top-centre", "top-right"], ["mid-left", "centre", "mid-right"], ["bottom-left", "bottom-centre", "bottom-right"]]
    regions = {}
    print(f"{a.video}: {n} frames {w}x{h} at {fps:.0f} fps, {a.start}-{a.end} s, thresh {t}, maxk {K}"
          + (f", {gaps} capture gaps (dropped frames)" if gaps is not None else ""))
    print("region          transient px/frame  per 100k world px  frames with >= 50 px   max")
    for r in range(3):
        for c in range(3):
            s = counts[:, r, c]
            regions[names[r][c]] = {"mean_px_per_frame": round(float(s.mean()), 1), "per_100k_world_px": round(1e5 * float(s.mean()) / max(1, area[r, c]), 2),
                                    "frames_ge_50px": int((s >= 50).sum()), "max": int(s.max()), "world_px": int(area[r, c])}
            g = regions[names[r][c]]
            print(f"{names[r][c]:14s}  {g['mean_px_per_frame']:18.1f}  {g['per_100k_world_px']:17.2f}  {g['frames_ge_50px']:20d}  {g['max']:5d}")
    share = counts / np.maximum(1, area)[None]
    burst = share.max(axis=(1, 2)) > 0.04  # a frame where some region blinks over > 4 % of its world pixels
    bursts = [{"frame": per_frame[i][0], "t": round(a.start + per_frame[i][0] / fps, 2), "region": names[int(np.argmax(share[i]) // 3)][int(np.argmax(share[i]) % 3)],
               "share_pct": round(100 * float(share[i].max()), 1)} for i in np.nonzero(burst)[0]]
    print(f"burst frames (a region > 4 % blinks): {len(bursts)} = {10 * len(bursts) / max(1e-6, n / fps):.1f} per 10 s:",
          " ".join(f"{b['t']}s/{b['region']}/{b['share_pct']}%" for b in bursts[:20]))
    tl = counts[:, 0, 0]
    secs = []
    step = int(round(fps))
    for s0 in range(0, n, step):
        seg = tl[s0:s0 + step]
        secs.append({"t": round(a.start + s0 / fps, 1), "mean": round(float(seg.mean()), 1), "max": int(seg.max())})
    print("top-left per second (t mean max):", " ".join(f"{x['t']}:{x['mean']}/{x['max']}" for x in secs))
    C = a.cell
    gh, gw = h // C, w // C
    cells = heat[:gh * C, :gw * C].reshape(gh, C, gw, C).sum(axis=(1, 3))
    order = np.argsort(cells.ravel())[::-1][:10]
    top = [{"x": int(i % gw) * C, "y": int(i // gw) * C, "size": C, "transient_px": int(cells.ravel()[i])} for i in order if cells.ravel()[i] > 0]
    print("busiest cells:", " ".join(f"({c['x']},{c['y']}):{c['transient_px']}" for c in top))
    worst = sorted(per_frame, key=lambda p: -p[1][0][0])[:8]
    print("worst top-left frames (frame, t, px):", " ".join(f"{p[0]}@{a.start + p[0] / fps:.2f}s:{p[1][0][0]}" for p in worst))
    if a.heat:
        rgb = np.stack([first] * 3, axis=-1).astype(np.float32) * 0.6
        hm = np.clip(heat / max(1, np.percentile(heat[heat > 0], 99) if (heat > 0).any() else 1), 0, 1)
        rgb[..., 0] = np.maximum(rgb[..., 0], hm * 255)
        rgb[~world] *= 0.4
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-i", "-", a.heat],
                       input=rgb.astype(np.uint8).tobytes(), check=True)
        print("heat map:", a.heat)
    if a.json:
        with open(a.json, "w") as fh:
            json.dump({"video": a.video, "window_s": [a.start, a.end], "frames": n, "fps": fps, "capture_gaps": gaps, "size": [w, h], "regions": regions, "bursts": bursts, "bursts_per_10s": round(10 * len(bursts) / max(1e-6, n / fps), 2),
                       "top_left_per_second": secs, "busiest_cells": top,
                       "worst_top_left_frames": [{"frame": p[0], "t": round(a.start + p[0] / fps, 2), "px": p[1][0][0]} for p in worst]}, fh, indent=1)


if __name__ == "__main__":
    main()
