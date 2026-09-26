#!/usr/bin/env python3
"""Tree lighting metrics of an explore=trees run (2026-09-25): a devCapture sequence + pzopt-trees.out.

    tree-metrics.py <run> [--json out.json] [--png DIR]

pzopt.TreeWalk logs every frame the on-screen trees' sprite rectangles (window px) and the camera; pzopt.FrameCapture
(devCapture=start,secs,fps,scalePct) the presented frames. Per captured frame the nearest camera record (<= 40 ms) gives the
boxes, scaled to the capture. Printed / written:

  black_share      share of tree-box pixels with max(R, G, B) < 14/255 (a crown drawn black; daylight has no such pixels
                   on foliage, and the box background is ground / walls, rarely that dark)
  band_luma        mean luma per height band of one level above the tree's ground row (0 = trunk and lower crown), over
                   the tree boxes: the shading from the foot to the top of the trees
  still_*          frames whose camera did not move since the previous captured frame (the player stands watching):
                   share of pixels whose luma jumps by > 24/255 from the previous frame, inside the tree boxes and over
                   the whole world view (the HUD rows and a disc round the player masked); a still picture should hold
  burst frames     still frames where > 0.5 % of the world view or > 2 % of the tree boxes jump
"""
import argparse
import json
import os
import sys

import numpy as np



def load(run):
    """The capture as a memory map (frames x h x w x 4, rows bottom-up) and its epoch ms stamps."""
    d = os.path.join(run, "capture")
    lines = open(os.path.join(d, "index.txt")).read().split("\n")
    head = dict(kv.split("=") for kv in lines[0].split())
    w, h = int(head["w"]), int(head["h"])
    stamps = [int(x) for x in lines[1:] if x.strip()]
    raw = np.memmap(os.path.join(d, "frames.rgba"), dtype=np.uint8, mode="r")
    n = min(len(stamps), raw.size // (w * h * 4))
    return raw[: n * w * h * 4].reshape(n, h, w, 4), np.array(stamps[:n])


def frame(frames, i):
    return np.ascontiguousarray(frames[i, ::-1, :, :3])


def lum(f):
    return f[..., 0] * 0.299 + f[..., 1] * 0.587 + f[..., 2] * 0.114


def records(run):
    cams, boxes = {}, {}
    for ln in open(os.path.join(run, "pzopt-trees.out")):
        if ln.startswith("#"):
            continue
        p = ln.split()
        ms = int(p[0])
        if p[1] == "P":
            cams[ms] = {"x": float(p[2]), "y": float(p[3]), "moving": p[4] == "true", "action": p[5],
                        "sw": int(p[6]), "sh": int(p[7]), "zoom": float(p[8]), "offx": float(p[9]), "offy": float(p[10])}
        else:
            boxes.setdefault(ms, []).append({"id": p[1], "x0": float(p[2]), "y0": float(p[3]), "x1": float(p[4]), "y1": float(p[5]),
                                             "ground": float(p[6]), "ppl": float(p[7])})
    return cams, boxes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--json")
    ap.add_argument("--png", help="write every 20th frame with the boxes drawn")
    a = ap.parse_args()
    frames, stamps = load(a.run)
    cams, boxes = records(a.run)
    cms = np.array(sorted(cams))
    n, h, w = frames.shape[0], frames.shape[1], frames.shape[2]
    Lprev = None
    world = np.ones((h, w), bool)
    world[int(h * 0.88):, :] = False  # the hotbar / HUD rows
    yy, xx = np.mgrid[0:h, 0:w]
    world &= (xx - w / 2) ** 2 + (yy - h / 2) ** 2 > (0.06 * h) ** 2  # the player (centred)
    black, bands = [], [[] for _ in range(7)]
    still_tree, still_world, bursts, actions = [], [], [], {}
    prev_cam = None
    for i in range(n):
        fr = frame(frames, i)
        Li = lum(fr.astype(np.float32))
        Lp, Lprev = Lprev, Li
        j = np.searchsorted(cms, stamps[i])
        cand = [k for k in (j - 1, j) if 0 <= k < len(cms)]
        if not cand:
            prev_cam = None
            continue
        k = min(cand, key=lambda k: abs(cms[k] - stamps[i]))
        if abs(cms[k] - stamps[i]) > 40:
            prev_cam = None
            continue
        cam = cams[cms[k]]
        actions[cam["action"]] = actions.get(cam["action"], 0) + 1
        s = w / cam["sw"]
        mask = np.zeros((h, w), bool)
        for b in boxes.get(cms[k], []):
            x0, x1 = int(max(0, b["x0"] * s)), int(min(w, b["x1"] * s))
            y0, y1 = int(max(0, b["y0"] * s)), int(min(h * 0.88, b["y1"] * s))
            if x1 - x0 < 4 or y1 - y0 < 4:
                continue
            mask[y0:y1, x0:x1] = True
            f = fr[y0:y1, x0:x1]
            black.append(float((f.max(axis=2) < 14).mean()))
            for lv in range(7):
                r1, r0 = int((b["ground"] - lv * b["ppl"]) * s), int((b["ground"] - (lv + 1) * b["ppl"]) * s)
                r0, r1 = max(r0, y0), min(r1, y1)
                if r1 - r0 >= 2:
                    bands[lv].append(float(Li[r0:r1, x0:x1].mean()))
        mask &= world
        still = prev_cam is not None and Lp is not None and abs(cam["offx"] - prev_cam["offx"]) < 0.01 and abs(cam["offy"] - prev_cam["offy"]) < 0.01 \
            and cam["zoom"] == prev_cam["zoom"]
        if still:
            d = np.abs(Li - Lp) > 24
            sw_ = float(d[world].mean())
            st_ = float(d[mask].mean()) if mask.any() else None
            still_world.append(sw_)
            if st_ is not None:
                still_tree.append(st_)
            if sw_ > 0.005 or (st_ or 0) > 0.02:
                bursts.append({"frame": i, "t_ms": int(stamps[i] - stamps[0]), "world_pct": round(100 * sw_, 2),
                               "trees_pct": None if st_ is None else round(100 * st_, 2)})
        if a.png and i % 20 == 0:
            from PIL import Image, ImageDraw
            im = Image.fromarray(fr)
            dr = ImageDraw.Draw(im)
            for b in boxes.get(cms[k], []):
                dr.rectangle([b["x0"] * s, b["y0"] * s, b["x1"] * s, b["y1"] * s], outline=(255, 0, 255))
                dr.line([b["x0"] * s, b["ground"] * s, b["x1"] * s, b["ground"] * s], fill=(0, 255, 255))
            os.makedirs(a.png, exist_ok=True)
            im.save(os.path.join(a.png, f"f{i:04d}.png"))
        prev_cam = cam

    def stat(v):
        v = np.array(v)
        return None if v.size == 0 else {"mean_pct": round(100 * v.mean(), 3), "p99_pct": round(100 * np.percentile(v, 99), 3),
                                         "max_pct": round(100 * v.max(), 3), "frames": int(v.size)}
    out = {
        "run": os.path.basename(os.path.normpath(a.run)), "frames": int(n), "capture": f"{w}x{h}",
        "fps": round(1000 / max(np.diff(stamps).mean(), 1), 1) if n > 1 else 0, "actions_frames": actions,
        "tree_boxes": len(black), "black_share_pct": None if not black else round(100 * float(np.mean(black)), 2),
        "black_share_p90_pct": None if not black else round(100 * float(np.percentile(black, 90)), 2),
        "band_luma": [None if not b else round(float(np.mean(b)), 1) for b in bands],
        "still_world_jumps": stat(still_world), "still_tree_jumps": stat(still_tree),
        "burst_frames": len(bursts), "bursts": bursts[:20],
    }
    print(json.dumps(out, indent=1))
    if a.json:
        with open(a.json, "w") as f:
            json.dump(out, f, indent=1)


if __name__ == "__main__":
    main()
