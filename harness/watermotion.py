#!/usr/bin/env python3
"""Does the water still flow? Water-surface motion of two harness recordings of the same still
camera by water (stock reference `a`, candidate `b`); code measures, TypeSafe (Jev) judges.

  harness/watermotion.py <run-or-mp4 A> <run-or-mp4 B> [--a2 <repeat of A>] [--b2 <repeat of B>]
                         [--seconds 14] [--width 480] [--fps 15] [--context "<what changed>"] [--json out.json]
                         [--mask-png /tmp/prefix]

Rig: `--mode bench --flag start=6450,5200 --flag find=shore --flag zombies=off --flag time_of_day=15
--flag weather=clear --flag route=S:1 --flag speed=0.1 --flag hold=20 --flag zoom=1.5 --record` (runs
`waterflow-*`, 2026-09-25): the player stands on the river bank, the camera is still for the hold.

Jev is text-only, so the motion is numbers, computed identically for both recordings over the last
`--seconds` before the quit (the end of the longest stretch of the world on screen, minus a second):
  * water mask: pixels whose median colour over the window is blue-grey (b > r + 5, b >= g > r), i.e. the
    river; land (grass, trees, road) is the control, it sways the same with or without our build
  * per region: mean |frame(t+1) - frame(t)| (frame-to-frame change), mean |frame(t+1 s) - frame(t)|,
    the share of pixels whose temporal std exceeds 1.0 / 2.5 levels (0-255, from 16-bit decoding of
    the 10-bit PQ capture), the drift of the region's mean over the window, and `spatial_detail` (ripple
    contrast inside a frame: motion = contrast x speed, so fainter ripples and slower ones read apart)
  * `--a2` / `--b2`: a repeat run of each side, measured the same, so Jev sees the run-to-run spread
Jev answers: water state of `b` against `a` (flowing_like_reference / static_water / reduced_motion /
more_motion / inconclusive), whether the water visibly moves in `b`, and whether `b` regressed.
"""
import argparse
import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from typesafe_client import ask, choice, noul, fmt  # noqa: E402

_pj = importlib.util.spec_from_file_location("parity_judge", Path(__file__).resolve().parent / "parity-judge.py")
parity_judge = importlib.util.module_from_spec(_pj)
_pj.loader.exec_module(parity_judge)


def read_rgb(path, start, end, width, fps):
    height = width * 2160 // 5120
    cmd = ["ffmpeg", "-v", "error", "-ss", str(start), "-to", str(end), "-i", str(path),
           "-vf", f"fps={fps},scale={width}:{height},format=rgb48le", "-f", "rawvideo", "-"]
    raw = subprocess.run(cmd, check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype="<u2").reshape(-1, height, width, 3).astype(np.float32) / 256.0


def region(f, luma, mask, fps):
    if mask.sum() == 0:
        return {"px_share": 0.0}
    step = np.abs(np.diff(luma, axis=0))[:, mask]
    lag = np.abs(luma[fps:] - luma[:-fps])[:, mask]
    sd = luma.std(axis=0)[mask]
    means = luma[:, mask].mean(axis=1)
    # ripple contrast inside one frame: |pixel - right neighbour| where both are in the region, averaged over the
    # frames; motion = contrast x speed, so this tells fainter ripples from slower ones
    pair = mask[:, :-1] & mask[:, 1:]
    detail = np.abs(np.diff(luma, axis=2))[:, pair]
    return {
        "px_share": round(float(mask.mean()), 4),
        "mean_luma": round(float(means.mean()), 2),
        "frame_to_frame_change": round(float(step.mean()), 3),
        "change_over_1s": round(float(lag.mean()), 3),
        "share_std_over_1": round(float((sd > 1.0).mean()), 4),
        "share_std_over_2_5": round(float((sd > 2.5).mean()), 4),
        "p90_temporal_std": round(float(np.percentile(sd, 90)), 3),
        "mean_drift": round(float(means.max() - means.min()), 3),
        "spatial_detail": round(float(detail.mean()), 3) if detail.size else 0.0,
    }


def scene_window(path, seconds):
    """[start, end) of the last `seconds` of the world on screen. parity-judge's active_window looks for motion,
    which a still camera does not have; here the end is the last frame of the longest stretch (>= 20 s) whose mean
    luminance stays above the black of the loading / quit frames, minus one second for the quit."""
    f = parity_judge.read_frames(str(path), 0, parity_judge.duration(path), 160, fps=4)
    lit = f.reshape(len(f), -1).mean(axis=1) > 8
    best, run_start = None, None
    for i, on in enumerate(list(lit) + [False]):
        if on and run_start is None:
            run_start = i
        elif not on and run_start is not None:
            if best is None or i - run_start >= best[1] - best[0]:
                best = (run_start, i)
            run_start = None
    if best is None or (best[1] - best[0]) / 4 < 20:
        sys.exit(f"{path}: no 20 s stretch of the world on screen")
    end = best[1] / 4 - 1.0
    return max(best[0] / 4, end - seconds), end


def measure(path, seconds, width, fps, mask_png=None):
    start, end = scene_window(path, seconds)
    f = read_rgb(path, start, end, width, fps)
    med = np.median(f, axis=0)
    r, g, b = med[..., 0], med[..., 1], med[..., 2]
    water = (b > r + 5) & (b >= g) & (g > r)
    # keep the frame edges out (HUD corners, the letterbox of a windowed capture)
    h, w = water.shape
    edge = np.zeros_like(water)
    edge[h // 12: h - h // 12, w // 20: w - w // 20] = True
    water &= edge
    land = edge & ~water & (med.mean(axis=2) > 8)
    if mask_png:
        from PIL import Image
        view = np.clip(med, 0, 255).astype(np.uint8)
        view[water] = (view[water] * 0.4 + np.array([0, 0, 255]) * 0.6).astype(np.uint8)
        Image.fromarray(view).save(mask_png)
    luma = f @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
    return {
        "window_s": [round(start, 1), round(end, 1)],
        "frames": int(f.shape[0]),
        "water": region(f, luma, water, fps),
        "land_control": region(f, luma, land, fps),
    }


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("a")
    ap.add_argument("b")
    ap.add_argument("--a2", help="a repeat of the reference run (run-to-run spread)")
    ap.add_argument("--b2", help="a repeat of the candidate run")
    ap.add_argument("--seconds", type=float, default=14)
    ap.add_argument("--width", type=int, default=480)
    ap.add_argument("--fps", type=int, default=15)
    ap.add_argument("--context", default="")
    ap.add_argument("--json")
    ap.add_argument("--mask-png", help="write <prefix>-a.png / -b.png: the median frame with the water mask in blue")
    args = ap.parse_args(argv)

    va, vb = parity_judge.video_of(args.a), parity_judge.video_of(args.b)
    mp = (lambda n: f"{args.mask_png}-{n}.png") if args.mask_png else (lambda n: None)
    ma = measure(va, args.seconds, args.width, args.fps, mp("a"))
    mb = measure(vb, args.seconds, args.width, args.fps, mp("b"))
    reps = {}
    for name, spec in (("a_repeat", args.a2), ("b_repeat", args.b2)):
        if spec:
            reps[name] = measure(parity_judge.video_of(spec), args.seconds, args.width, args.fps, mp(name))
    for name, m in [("a", ma), ("b", mb)] + list(reps.items()):
        print(f"{name}: {m['window_s']} {m['frames']} frames")
        for reg in ("water", "land_control"):
            print(f"   {reg:13s} " + " ".join(f"{k}={v}" for k, v in m[reg].items()))

    state = {
        "task": "Two screen recordings of the same still camera next to a river in Project Zomboid (player standing on the bank, "
                "no zombies, clear daytime weather, same window before the quit). `a` is the reference (stock game), `b` the "
                "candidate build. In the stock game the river surface is animated by a water shader (ripples scroll with time "
                "and wind), so its pixels keep changing while the land only sways with grass and trees. Numbers are luminance "
                "levels 0-255; `water` is the river region, `land_control` the rest of the scene. `a_repeat` / `b_repeat`, when "
                "present, are a second run of each side with the identical setup: their spread is the run-to-run variation.",
        "context": args.context,
        "a": ma,
        "b": mb,
        **reps,
    }
    questions = {
        "water_state": choice(
            "How does the water surface in `b` move compared with reference `a`? Judge the `water` metrics of `b` against `a`, "
            "using `land_control` to tell a global recording difference from a water-only one.",
            {
                "flowing_like_reference": "b's water changes over time about as much as a's (within run-to-run variation)",
                "static_water": "b's water is (nearly) frozen: its frame-to-frame and 1 s change collapse toward zero while a's water moves",
                "reduced_motion": "b's water still moves but clearly less than a's, beyond what the land control explains",
                "more_motion": "b's water moves clearly more than a's",
                "inconclusive": "the water region is missing or too small in either recording, or the land control differs so much the comparison is void",
            }),
        "water_moves_b": noul("Does the river surface in `b` visibly move (animated ripples), judging from its temporal change metrics?"),
        "regression": noul("Is there a water flow regression in `b` relative to `a`, i.e. the water in `b` moves noticeably less or not at all?"),
    }
    log = []
    answers = ask(state, questions, log=log)
    print("jev:")
    print("   " + fmt(answers).replace("\n", "\n   "))
    if args.json:
        Path(args.json).write_text(json.dumps({"state": state, "answers": answers, "log": log}, indent=1))


if __name__ == "__main__":
    main(sys.argv[1:])
