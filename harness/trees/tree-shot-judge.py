#!/usr/bin/env python3
"""Jev's verdict on tree lighting from pixel-aligned --shot-at captures (2026-09-26): the same camera, the same frame,
stock lighting as the control. Per tree box (window px of the 4096-wide shot-game.png; defaults: the trees of the Rosewood
church spot, start=8147,11507, zoom 1, shot at 3 s of route S:30 speed 1):

  black_px      pixels near-black (max channel < 14) in the run where the control has them lit (a black crown)
  lower_upper   luma of the box's lower third / its upper third, divided by the control's (1 = shaded like stock's flat
                tree; below 1: the lower part darker than the top, the crown shading its trunk and lower branches)

    tree-shot-judge.py CONTROL_RUN TEST_RUN [BEFORE_RUN] [--context "..."] [--out f.json]
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from typesafe_client import ask, choice, noul, fmt  # noqa: E402

# x0, y0, x1, y1 in the 4096 x 1728 shot; the tree bodies only (ground cropped out where possible)
BOXES = {
    "river_birch": (2590, 1260, 2790, 1640),
    "red_maple_cemetery": (3330, 150, 3530, 560),
    "birch_left": (2210, 1080, 2420, 1500),
}


def shot(run):
    return np.asarray(Image.open(Path(run) / "shot-game.png").convert("RGB")).astype(np.float32)


def luma(a):
    return a[..., 0] * 0.299 + a[..., 1] * 0.587 + a[..., 2] * 0.114


def measure(ctrl, run):
    out = {}
    for name, (x0, y0, x1, y1) in BOXES.items():
        c, r = ctrl[y0:y1, x0:x1], run[y0:y1, x0:x1]
        black = (r.max(axis=2) < 14) & (c.max(axis=2) >= 30)
        lc, lr = luma(c), luma(r)
        h = y1 - y0
        up, low = slice(0, h // 3), slice(2 * h // 3, h)
        ratio = (lr[low].mean() / max(1e-3, lr[up].mean())) / max(1e-3, lc[low].mean() / max(1e-3, lc[up].mean()))
        out[name] = {"black_px": int(black.sum()), "black_pct": round(100 * float(black.mean()), 2),
                     "lower_upper_vs_control": round(float(ratio), 3), "mean_luma": round(float(lr.mean()), 1),
                     "control_mean_luma": round(float(lc.mean()), 1)}
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("control")
    ap.add_argument("test")
    ap.add_argument("before", nargs="?")
    ap.add_argument("--context", default="")
    ap.add_argument("--out")
    a = ap.parse_args()
    ctrl = shot(a.control)
    state = {
        "setup": "Pixel-aligned screenshots of the same frame (same camera, same moment) of a sunny scene with three trees. "
                 "`control` = stock lighting. `test` = the lighting features (per-pixel lighting, ambient occlusion, sun "
                 "shadows, HDR) with the tree fixes. `before` (if given) = the same features without the tree fixes. "
                 "black_px: tree pixels drawn near-black where stock lights them (a black crown bug). lower_upper_vs_control: "
                 "the tree's lower third's brightness over its upper third, relative to stock's (stock draws a tree evenly lit: "
                 "1.0 = no shading from the crown; clearly below 1 = the lower part of the tree is in the crown's shade, as a "
                 "real tree in sunlight; far below ~0.5 would be too dark). " + a.context,
        "report": "trees are not lit correctly: their lower part gets no shadow; some crowns black",
        "test": measure(ctrl, shot(a.test)),
    }
    if a.before:
        state["before"] = measure(ctrl, shot(a.before))
    print(json.dumps(state, indent=1))
    questions = {
        "black_crowns_in_test": noul("Does `test` draw any tree (partly) black where stock lights it (a clearly nonzero black_px, "
                                     "say more than 1 % of the box)?"),
        "lower_part_shaded_in_test": noul("In `test`, is the lower part of the trees shaded relative to the top "
                                          "(lower_upper_vs_control clearly below 1, e.g. 0.6-0.95, for the trees)?"),
        "verdict": choice({"question": "Is the report fixed in `test`?"},
                          {"fixed": "no black crowns and the lower parts are shaded by their crowns",
                           "partly_fixed": "one of the two is fixed, or only some trees",
                           "not_fixed": "black crowns remain and the lower parts are not shaded",
                           "inconclusive": "the numbers do not tell"}),
    }
    if a.before:
        questions["before_had_problem"] = noul("Did `before` show the problem (black crowns, or lower parts not shaded: "
                                               "lower_upper_vs_control near or above 1)?")
    log = []
    ans = ask(state, questions, log=log)
    print("jev:")
    print("  " + fmt(ans).replace("\n", "\n  "))
    print(f"  ({log[0]['ms']} ms, {log[0]['model']})")
    if a.out:
        Path(a.out).write_text(json.dumps({"state": state, "answers": ans, "typesafe": log[0]}, indent=1))


if __name__ == "__main__":
    main()
