#!/usr/bin/env python3
"""Thin dark seam lines along the iso grid in a `--shot-at` capture, and Jev's verdict on them.

A seam pixel is a dark ridge (luma at least --ridge below both pixels 2-4 px above and below it: the seams run at
+-1/2 slope, so their cross-section is near-vertical; DLSS widens them to 2-3 px) that lies on a straight run along one
of the two iso diagonals (2 px across, 1 px down per step, +-1 px of slack), 30 of 33 steps. "new" seam pixels are the
ones with no control seam pixel within 3 px (same camera, same size): sprite outlines, brick courses and pavement joints
are in both and cancel out; a chunk- or square-edge seam of the composite does not.

Usage: seam-lines.py <run|png> [...] [--control <run|png>] [--before <run|png>] [--context "..."] [--out verdict.json]
Prints seam px per megapixel for every image (a run means its shot-game.png and shot2-game.png). With --control (the
same shot without the feature) and --before (the build with the seams), Jev answers whether the tested images still
show them.
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))


def shots(arg):
    p = Path(arg)
    if p.is_dir():
        return [q for q in (p / "shot-game.png", p / "shot2-game.png") if q.exists()]
    return [p]


def shift(a, dy, dx, fill):
    out = np.full_like(a, fill)
    h, w = a.shape
    ys, yd = (slice(0, h - dy), slice(dy, h)) if dy >= 0 else (slice(-dy, h), slice(0, h + dy))
    xs, xd = (slice(0, w - dx), slice(dx, w)) if dx >= 0 else (slice(-dx, w), slice(0, w + dx))
    out[yd, xd] = a[ys, xs]
    return out


def seam_mask(path, ridge, half=16, need=30):
    im = np.asarray(Image.open(path).convert("RGB"), dtype=np.float32)
    y = 0.2126 * im[..., 0] + 0.7152 * im[..., 1] + 0.0722 * im[..., 2]
    d = np.max([np.minimum(shift(y, o, 0, 0.0), shift(y, -o, 0, 0.0)) - y for o in (2, 3, 4)], axis=0)
    r = d > ridge
    rv = r | shift(r, 1, 0, False) | shift(r, -1, 0, False)  # +-1 px of vertical slack
    lines = np.zeros_like(r)
    for sy in (1, -1):
        hits = np.sum([shift(rv, -sy * k, -2 * k, False) for k in range(-half, half + 1)], axis=0, dtype=np.int16)
        lines |= r & (hits >= need)
    return lines


def seams(path, ridge, control=None):
    m = seam_mask(path, ridge)
    mp = m.size / 1e6
    row = {"image": str(path), "size": f"{m.shape[1]}x{m.shape[0]}", "seam_px_per_mp": round(float(m.sum()) / mp, 1)}
    if control is not None and control.shape == m.shape:
        near = np.zeros_like(control)
        for dy in range(-3, 4):
            for dx in range(-3, 4):
                near |= shift(control, dy, dx, False)
        row["new_seam_px_per_mp"] = round(float((m & ~near).sum()) / mp, 1)
        # grid lines across the ground: new seam pixels on straight runs of 129 px (two squares and more at zoom 1),
        # not the short dark edges of single tiles or walls
        long_ = m & seam_mask(path, ridge, half=64, need=116)
        row["new_long_seam_px_per_mp"] = round(float((long_ & ~near).sum()) / mp, 1)
    return row


def group(args, ridge, control):
    rows = []
    for a in args:
        for p in shots(a):
            c = next((m for m in control if m.shape == Image.open(p).size[::-1]), None)
            rows.append(seams(p, ridge, c))
    for row in rows:
        print(f"  {row['image']}: {row['size']} seam {row['seam_px_per_mp']}/MP, not in the control {row.get('new_seam_px_per_mp', '-')}/MP"
              f" (on long runs {row.get('new_long_seam_px_per_mp', '-')}/MP)")
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("test", nargs="+")
    ap.add_argument("--control", nargs="*", default=[])
    ap.add_argument("--before", nargs="*", default=[])
    ap.add_argument("--ridge", type=float, default=10.0)
    ap.add_argument("--context", default="")
    ap.add_argument("--out")
    a = ap.parse_args()
    cmasks = [seam_mask(p, a.ridge) for c in a.control for p in shots(c)][:1]  # the control's first shot is the reference
    control = before = []
    if a.control:
        print("control:")
        control = group(a.control, a.ridge, cmasks)
    print("test:")
    test = group(a.test, a.ridge, cmasks)
    if a.before:
        print("before:")
        before = group(a.before, a.ridge, cmasks)
    if not a.control:
        return
    from typesafe_client import ask, choice, noul, fmt  # noqa: E402
    strip = lambda rows: [{k: v for k, v in r.items() if k != "image"} for r in rows]
    state = {
        "setup": "Lossless in-game screenshots of the same camera position (Rosewood, noon, clear weather, zoom 1, the camera "
                 "held still; two shots 2 s apart per run). A seam pixel is a dark line one or two pixels wide lying on a straight run along one of the two "
                 "isometric grid diagonals: the lines a player sees along the square / chunk edges of the ground. "
                 "`control` = the same build and scene with the feature under test off (no such lines expected: its count is the "
                 "background of sprite outlines and pavement texture); `before` = the build the player reported; `test` = the "
                 "candidate fix. " + a.context,
        "report": "grid square lines show up on the ground when ambient occlusion, per-pixel lighting, sun shadows and HDR are on",
        "test": strip(test), "control": strip(control), "before": strip(before),
    }
    print(json.dumps(state, indent=1))
    questions = {
        "before_has_lines": noul("Does `before` show clearly more long-run seam pixels than `control` (the reported grid lines)?"),
        "test_has_lines": noul("Does `test` still show clearly more long-run seam pixels (grid lines) than `control`?"),
        "test_has_short_edges": noul("Does `test` show clearly more short new seam pixels (new_seam minus new_long_seam) than `control`?"),
        "kind": choice({"question": "What best describes `test`?",
                        "note": "new_long_seam_px_per_mp (seams absent from the control's first shot, on straight runs two "
                                "squares and longer: lines across the ground grid) is the metric for the report; new_seam_px_per_mp "
                                "also counts short dark edges of single tiles and walls. The control's own second shot gives the noise "
                                "floor. seam_px_per_mp includes sprite outlines and pavement joints present in every image."},
                       {"fixed": "test's long-run seams are at the control's level while before is well above it: the grid lines are gone",
                        "still_lines": "test stays well above control: the lines remain",
                        "not_reproduced": "before is not above control: the capture does not show the lines",
                        "inconclusive": "contradicting or too few images"}),
    }
    log = []
    answers = ask(state, questions, log=log)
    print("jev:")
    print("  " + fmt(answers).replace("\n", "\n  "))
    print(f"  ({log[0]['ms']} ms, {log[0]['model']})")
    if a.out:
        Path(a.out).write_text(json.dumps({"state": state, "answers": answers, "typesafe": log[0]}, indent=1))


if __name__ == "__main__":
    main()
