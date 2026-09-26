#!/usr/bin/env python3
"""Jev's verdict on the tree lighting of an explore=trees run against a control (2026-09-25, trees lit wrong with every
lighting key on). Reads two tree-metrics.py JSON files and asks over numbers only.

    tree-judge.py TEST.json CONTROL.json [--context "..."] [--out verdict.json]

TEST is the run with the lighting keys under test (pixelLight + ambientOcclusion + sunShadows + hdr), CONTROL the same
scene with the stock lighting (those keys off). Both walk the same trees (Jev directs the walk), so the frames differ;
the metrics are distributions over the tree boxes and over the frames with a still camera.
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from typesafe_client import ask, choice, noul, fmt  # noqa: E402


def summary(path):
    j = json.loads(Path(path).read_text())
    return {k: j[k] for k in ("frames", "fps", "actions_frames", "tree_boxes", "black_share_pct", "black_share_p90_pct", "band_luma",
                              "still_world_jumps", "still_tree_jumps", "burst_frames")} | {"first_bursts": j["bursts"][:8]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("test")
    ap.add_argument("control")
    ap.add_argument("--context", default="")
    ap.add_argument("--out")
    a = ap.parse_args()
    state = {
        "setup": "Two in-game frame captures of the same daylight scene: the character walks to four trees, circles each "
                 "once and stands still watching it for a few seconds (the camera follows the character, so it only stands "
                 "still while watching). `test` = the lighting features under test (per-pixel lighting, ambient occlusion, sun "
                 "shadows, HDR) all on; `control` = the same build and scene with the stock lighting. Tree boxes are the "
                 "trees' sprite rectangles (the tree plus the ground / walls behind it). black_share_pct: share of tree-box "
                 "pixels darker than 14/255 in every channel (a black crown). band_luma: mean luma (0-255) of the tree boxes "
                 "per height band of one storey above the tree's foot, band 0 = trunk and lower crown. still_*_jumps: on "
                 "frames where the camera did not move, the share of pixels whose luma changed by more than 24/255 since the "
                 "previous frame (a still picture should hold; wind sways leaves a little in both runs). burst_frames: still "
                 "frames with more than 0.5 % of the view or 2 % of the tree boxes jumping. " + a.context,
        "report": "with every new lighting setting on, the picture is unstable and trees are not lit correctly: their lower "
                  "part gets no shadow",
        "test": summary(a.test),
        "control": summary(a.control),
    }
    print(json.dumps(state, indent=1))
    questions = {
        "black_trees": noul("Does `test` draw trees (or parts of them) black where `control` does not: a clearly higher "
                            "black_share_pct or black_share_p90_pct?"),
        "unstable": noul("Is `test` unstable where `control` is steady: clearly more still-camera jumps (mean, p99 or max) or "
                         "burst frames, in the tree boxes or over the whole view?"),
        "lower_part_unshaded": noul("Is the lower part of the trees in `test` lit very differently from `control` relative to "
                                    "the upper bands (band 0 and 1 against bands 2+), i.e. the shading from foot to top does not "
                                    "follow the control's?"),
        "verdict": choice({"question": "Overall, how does the tree lighting of `test` compare with `control`?"},
                          {"bug_present": "test shows black or unstable trees or a clearly wrong foot-to-top shading that control does not",
                           "matches_control": "test's trees are as stable as control's, not black, and shaded from foot to top like control's (brightness may differ a little: the features add shadows)",
                           "inconclusive": "too few tree boxes or still frames, or contradicting metrics"}),
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
