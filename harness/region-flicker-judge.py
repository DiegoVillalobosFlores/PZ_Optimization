#!/usr/bin/env python3
"""Jev's verdict on a region flicker report: does recording `test` flicker in a screen region where the control does
not? Reads two region-flicker.py JSON files (same route, same window length) and asks over numbers only.

Usage: region-flicker-judge.py TEST.json CONTROL.json [--region top-left] [--context "..."] [--out verdict.json]
A burst frame is one where more than 4 % of a region's world pixels blink (appear / vanish for 1-3 frames); with
region-flicker.py's whole-screen burst list the judge sees every region's bursts, not only the one named.
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from typesafe_client import ask, choice, noul, fmt  # noqa: E402


def summary(path, region):
    j = json.loads(Path(path).read_text())
    r = j["regions"][region]
    others = {k: v for k, v in j["regions"].items() if k != region}
    area = r["world_px"]
    if "bursts" in j:  # region-flicker.py's whole-screen list: every frame where some region blinks over > 4 %
        screen = j["bursts"]
        bursts = [{"t": b["t"], "px": b["share_pct"] * area / 100} for b in screen if b["region"] == region]
    else:
        screen = None
        bursts = [f for f in j["worst_top_left_frames"] if f["px"] > 0.04 * area] if region == "top-left" else []
    secs = j["window_s"][1] - j["window_s"][0]
    return {
        "frames": j["frames"], "fps": round(j["fps"]), "capture_gaps": j.get("capture_gaps"), "seconds": secs,
        region: {"transient_px_per_frame": r["mean_px_per_frame"], "per_100k_world_px": r["per_100k_world_px"], "max_px_in_one_frame": r["max"],
                 "max_share_of_region_in_one_frame_pct": round(100 * r["max"] / max(1, area), 1),
                 "burst_frames_over_4pct": len(bursts), "burst_frames_per_10s": round(10 * len(bursts) / secs, 1),
                 "burst_frames": [{"t_s": f["t"], "share_pct": round(100 * f["px"] / area, 1)} for f in bursts]},
        "whole_screen_burst_frames": None if screen is None else len(screen),
        "whole_screen_burst_frames_per_10s": None if screen is None else round(10 * len(screen) / secs, 1),
        "whole_screen_bursts": None if screen is None else [{"t_s": b["t"], "region": b["region"], "share_pct": b["share_pct"]} for b in screen[:12]],
        "other_regions_per_100k_world_px": {k: v["per_100k_world_px"] for k, v in others.items()},
        "other_regions_max_share_in_one_frame_pct": {k: round(100 * v["max"] / max(1, v["world_px"]), 1) for k, v in others.items()},
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("test")
    ap.add_argument("control")
    ap.add_argument("--region", default="top-left")
    ap.add_argument("--context", default="")
    ap.add_argument("--out")
    a = ap.parse_args()
    state = {
        "setup": "Two frame-exact captures (every presented frame read back in-game) of the same scene: a copy of the "
                 "player's save, night, inside an office building, the character walking small circles (radius 1.5 tiles, "
                 "~3.7 s a lap) with the camera following. `test` = the optimization mod as the player runs it; `control` = the "
                 "same build with every optimization disabled (stock game). A transient pixel changes by >= 32/255 and returns "
                 "within 3 frames (something appearing or vanishing for 1-3 frames); steady camera motion is not counted. HUD "
                 "and a disc round the walking character are masked out. " + a.context,
        "report": f"the player sees textures flickering in the {a.region} of the screen while walking in circles",
        "test": summary(a.test, a.region),
        "control": summary(a.control, a.region),
    }
    print(json.dumps(state, indent=1))
    questions = {
        "flicker_confirmed": noul(f"Do the numbers confirm the report (flicker seen in the {a.region}): does `test` show frames where a "
                                  "large part of that region or of any other region blinks for one to three frames (whole_screen_bursts "
                                  "counts every region; a player notices a screen-wide blink wherever the list files it)?"),
        "stock_also": noul("Does `control` (stock) show the same kind of one-frame bursts at a comparable rate?"),
        "kind": choice({"question": "What best describes `test` compared with `control`, over the whole screen?",
                        "note": "Motion edges from the walking camera give a steady background of transients in every region "
                                "in both runs; one-frame bursts covering several percent of a region are the flicker a player notices."},
                       {"pzopt_flicker": "test has one-frame bursts that control does not have: the mod causes the flicker",
                        "shared_flicker": "both have comparable bursts: the stock game flickers there too",
                        "no_flicker": "neither has bursts beyond the motion background",
                        "inconclusive": "too few frames, capture gaps or contradicting metrics"}),
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
