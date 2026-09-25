#!/usr/bin/env python3
"""Jev's verdict on chunk AO arriving late while driving: does a new chunk texture (ground, grass, bushes) show on screen
without its AO shading and darken later, and does a change fix that without hurting the frame tail or chunk arrival?

    harness/ao-late-judge.py --test <run>... --control <run>... [--off <run>...] [--context "..."] [--out verdict.json]

`test` / `control` are AO-on runs of the same path drive (e.g. bench drive-120-south with --record), `off` the same
drive with ambientOcclusion=false (the frame-tail and chunk-arrival reference). Per run it reads `ao_latency=` from
pzopt-bench.out (ChunkAo.latency(): first AOs, in-bake share, latency buckets, texture-frames composited without their
AO, frames showing at least one), the route-window frame times from pzopt-overlay.out, drive-check.json (valid drive,
chunk-ahead stall) and, with --holes, holes.py's enclosed / edge-connected black share of the recording. Jev sees
numbers only.
"""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from typesafe_client import ask, choice, noul, fmt  # noqa: E402

HERE = Path(__file__).resolve().parent


def bench(run):
    return dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)


def latency(kv):
    s = kv.get("ao_latency")
    if not s:
        return None
    d = {}
    for tok in s.split():
        k, _, v = tok.partition("=")
        d[k] = float(v) if "." in v else int(v)
    frames = max(1, d.get("frames", 1))
    d["share_frames_showing_without_ao_pct"] = round(100.0 * d.get("frames_showing_without_ao", 0) / frames, 1)
    d["late_over_100ms"] = sum(d.get(k, 0) for k in ("le250ms", "le500ms", "le1000ms", "gt1000ms"))
    return d


def frame_tail(run, kv):
    rs, re_ = int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
    ft = []
    with open(run / "pzopt-overlay.out") as f:
        next(f)
        for line in f:
            p = line.rstrip().split(",")
            if len(p) < 9:
                continue
            e = int(p[8])
            if rs <= e <= re_:
                ft.append(float(p[1]))
    ft = np.array(ft)
    if len(ft) == 0:
        return {}
    return {"fps_mean": round(1000.0 / ft.mean(), 1), "p99_ms": round(float(np.percentile(ft, 99)), 2),
            "p999_ms": round(float(np.percentile(ft, 99.9)), 2), "max_ms": round(float(ft.max()), 1),
            "frames_over_16ms": int((ft > 16.7).sum())}


def holes(run):
    out = subprocess.run([sys.executable, str(HERE / "holes.py"), str(run), "--fps", "2"], capture_output=True, text=True).stdout
    m = re.search(r"enclosed black mean ([\d.]+) % p90 ([\d.]+) % max ([\d.]+) %.*edge-connected black mean ([\d.]+) %", out)
    if not m:
        return None
    return {"enclosed_black_mean_pct": float(m.group(1)), "enclosed_black_p90_pct": float(m.group(2)),
            "edge_black_mean_pct": float(m.group(4))}


def card(r, with_holes):
    run = Path(r)
    kv = bench(run)
    c = {"run": run.name, "ambient_occlusion": latency(kv) is not None, "ao_latency": latency(kv), "frames": frame_tail(run, kv)}
    dc = run / "drive-check.json"
    if dc.exists():
        k = json.loads(dc.read_text()).get("card", {})
        c["drive"] = {x: k.get(x) for x in ("completed", "seconds", "chunk_ahead_s", "held_back_s", "cruise_share", "mean_kmh") if x in k}
    if with_holes:
        c["holes"] = holes(run)
    return c


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--test", nargs="+", required=True)
    ap.add_argument("--control", nargs="+", required=True)
    ap.add_argument("--off", nargs="*", default=[])
    ap.add_argument("--holes", action="store_true")
    ap.add_argument("--context", default="")
    ap.add_argument("--out")
    a = ap.parse_args()
    state = {
        "setup": "Path drive at 120 km/h through Rosewood at the widest zoom, 240 fps cap, 5120x2160, the same build in every "
                 "run. The world is drawn from per-chunk textures baked when a chunk arrives on screen; ambient occlusion (AO) "
                 "darkens the ground under and around grass tufts, bushes and walls, and is computed per texture on the GPU. "
                 "A texture composited before its first AO shows flat, pale grass that darkens later: the player reports it "
                 "as grass rendered late. ao_latency: first_aos = textures that got their first AO; in_bake = computed in the "
                 "texture's own first bake (never shown without it); leNNms / gtNNNNms = waited that long after the bake; "
                 "shown_without_ao = texture-frames composited while waiting; frames_showing_without_ao = frames with at "
                 "least one. `test` = the change, `control` = the same AO build without it, `off` = AO disabled (frame-tail "
                 "and chunk-arrival reference). Run-to-run noise on this drive: p99 +-1.5 ms, p99.9 +-4 ms, a chunk-ahead "
                 "stall of ~0.5 s can occur in any run (streaming). " + a.context,
        "report": "driving fast with ambient occlusion on, grass is rendered late",
        "test": [card(r, a.holes) for r in a.test],
        "control": [card(r, a.holes) for r in a.control],
        "off": [card(r, a.holes) for r in a.off],
    }
    print(json.dumps(state, indent=1))
    questions = {
        "issue_in_control": noul("Do the `control` numbers confirm the report: new chunk textures shown on screen without their AO "
                                 "for a noticeable time (many texture-frames without AO, first AOs waiting hundreds of ms)?"),
        "fixed_in_test": noul("Does `test` remove it: nearly every first AO computed in the texture's own bake, few or no "
                              "texture-frames shown without AO, compared with `control`?"),
        "tail_regressed": noul("Is `test`'s frame tail (p99, p99.9, max, frames over 16 ms) clearly worse than `control`'s and "
                               "`off`'s beyond the stated run-to-run noise?"),
        "arrival_regressed": noul("Is chunk arrival clearly worse in `test` (more enclosed black holes or edge black, longer "
                                  "chunk-ahead stalls) than in `control` and `off`, beyond the stated noise?"),
        "verdict": choice({"question": "Overall, what do the runs say about the change?"},
                          {"fixed": "control shows the late AO, test does not, frame tail and chunk arrival hold",
                           "fixed_with_cost": "the late AO is gone in test but the frame tail or chunk arrival got clearly worse",
                           "not_fixed": "test still shows textures without AO for a noticeable time",
                           "not_reproduced": "control does not show the late AO either",
                           "inconclusive": "missing counters, invalid drives or contradicting numbers"}),
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
