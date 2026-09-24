#!/usr/bin/env python3
"""Is visual parity maintained between two harness recordings (stock vs optimized, or before vs
after a fix)? Code measures, TypeSafe (Jev) classifies the measurements.

  harness/parity-judge.py <run-or-mp4 A> <run-or-mp4 B> [--seconds 20 | --window START END]
                          [--scale 1280] [--shots a.png b.png] [--context "<what changed>"] [--json out.json]
                          [--window-a START END] [--window-b START END]   # a fixed window for one side only (the other keeps the rule)

Jev is text-only (it never sees a frame), so every visual property is a number computed here,
identically for both recordings over the same route phase (default: the last --seconds of
on-screen activity, i.e. aligned at the quit-to-black, the hard sync point of every capture):
  * transient px/frame (flicker.py's A-B-A detector): sprites that appear / disappear for 1-3 frames
  * black share per frame and black jumps (chunk-sized black squares popping in)
  * luma pops (frame-to-frame mean brightness steps: lighting patchwork, flash re-bakes)
  * where the transients are, as a 4x4 screen grid, with the HUD corners named so an overlay
    difference is not mistaken for a world difference
  * optional --shots: an aligned screenshot pair (same spot, same zoom): differing-pixel share and
    the cells that differ, HUD corners excluded.
Jev then answers: parity kind (parity / hud_only / flicker / black_tiles / lighting_pops /
static_scene_difference / inconclusive), parity maintained, and whether a person should look.
What the metrics do not measure (a wrong but steady sprite, a colour shift) Jev cannot flag.
"""
import json
import subprocess
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from flicker import read_frames, transients  # noqa: E402
from typesafe_client import ask, choice, noul, fmt  # noqa: E402

FPS = 60.0
GRID = 4
HUD_CELLS = {(0, 0): "top-left (in-game overlay / MangoHud)", (0, GRID - 1): "top-right (MangoHud)",
             (GRID - 1, 0): "bottom-left (game HUD)", (GRID - 1, GRID - 1): "bottom-right (game HUD)"}
NAMES = {0: "top", 1: "upper-middle", 2: "lower-middle", 3: "bottom"}
COLS = {0: "left", 1: "centre-left", 2: "centre-right", 3: "right"}


def video_of(spec):
    p = Path(spec)
    if p.is_dir():
        p = p / "recording.mp4"
    if not p.exists():
        cands = sorted(Path("harness/runs").glob(f"{spec}*/recording.mp4"))
        if not cands:
            sys.exit(f"no recording for {spec}")
        p = cands[-1]
    return p


def duration(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)],
                         capture_output=True, text=True, check=True).stdout.strip()
    return float(out)


def active_window(path, seconds):
    """[start, end) of the last `seconds` of on-screen activity: the route ends with the quit, a
    hard sync point in every capture; after it come a few black seconds, one menu flash and the
    desktop, none of which is the route. A second is active when >= 0.03 % of its pixels make a
    hard luminance jump; the route end is the last second that is active together with its
    predecessor (the menu flash is a lone active second)."""
    d = duration(path)
    f = read_frames(str(path), 0, d, 320, fps=10).astype(np.int16)
    hard = (np.abs(np.diff(f, axis=0)) > 40).mean(axis=(1, 2))
    per_sec = np.array([hard[i:i + 10].mean() for i in range(0, len(hard), 10)])
    active = per_sec >= 0.0003
    end = None
    for i in range(len(active) - 1, 0, -1):
        if active[i] and active[i - 1]:
            end = i + 1
            break
    if end is None:
        sys.exit(f"{path}: no active stretch found")
    return max(0.0, end - seconds), float(end)


def cell_name(cy, cx):
    return HUD_CELLS.get((cy, cx)) or f"{NAMES[cy]} {COLS[cx]}"


def measure(path, start, end, scale):
    f = read_frames(str(path), start, end, scale, fps=FPS).astype(np.int16)
    n, h, w = f.shape
    tr = transients(f, 32, 3)
    per_frame = tr.sum(axis=(1, 2))
    gh, gw = h // GRID, w // GRID
    cells = tr[:, :gh * GRID, :gw * GRID].reshape(n, GRID, gh, GRID, gw).sum(axis=(2, 4)).sum(axis=0)
    hud = sum(int(cells[c]) for c in HUD_CELLS)
    world = int(cells.sum()) - hud
    order = np.argsort(cells.ravel())[::-1][:4]
    top = [{"where": cell_name(*divmod(int(i), GRID)), "transient_px": int(cells.ravel()[i])} for i in order if cells.ravel()[i] > 0]
    black = (f < 8).mean(axis=(1, 2))
    black_jumps = int((np.abs(np.diff(black)) > 0.02).sum())
    luma = f.mean(axis=(1, 2))
    luma_steps = np.abs(np.diff(luma))
    px = h * w
    # the blocky-lights metric (docs/archive/2026-09-24/findings-blocky-lights-2026-09-21.md): pixels whose luminance
    # jumps by > 40 between consecutive frames at 30 fps; a stale chunk lands as a chunk-sized solid
    # block of jumps, a stock-like light sweep changes smoothly. Solid blocks: 32x32 reduced-px cells
    # (about half a tile at 1280 wide) where >= 90 % of the pixels jump in the same frame.
    f30 = f[::2]
    hard = np.abs(np.diff(f30, axis=0)) > 40
    hard_per_frame = hard.sum(axis=(1, 2))
    B = 32
    bh, bw = h // B, w // B
    solid = (hard[:, :bh * B, :bw * B].reshape(-1, bh, B, bw, B).mean(axis=(2, 4)) >= 0.9).sum(axis=(1, 2))
    return {
        "video": str(path), "window_s": [round(start, 1), round(end, 1)], "frames": int(n), "resampled_fps": FPS, "analysed_px_per_frame": int(px),
        "transient_px_per_frame": {"mean": round(float(per_frame.mean()), 1), "max": int(per_frame.max()),
                                   "per_mille_of_frame_mean": round(1000 * float(per_frame.mean()) / px, 3)},
        "transient_px_world_vs_hud_corners": {"world": world, "hud_corners": hud},
        "busiest_cells": top,
        "hard_luma_jumps_px_per_frame_30fps": {"mean": int(hard_per_frame.mean()), "p90": int(np.percentile(hard_per_frame, 90)),
                                               "max": int(hard_per_frame.max())},
        "solid_jump_blocks_per_frame_30fps": {"mean": round(float(solid.mean()), 2), "p90": int(np.percentile(solid, 90)), "max": int(solid.max())},
        "black_share": {"mean_pct": round(100 * float(black.mean()), 2), "max_pct": round(100 * float(black.max()), 2),
                        "jumps_over_2pct": black_jumps},
        "luma_0_255": {"mean": round(float(luma.mean()), 1), "pops_over_6": int((luma_steps > 6).sum()),
                       "largest_step": round(float(luma_steps.max()), 1)},
    }


def shot_diff(a, b, scale):
    from PIL import Image
    ia = np.asarray(Image.open(a).convert("L").resize((scale, scale * 2160 // 5120)), dtype=np.int16)
    ib = np.asarray(Image.open(b).convert("L").resize((scale, scale * 2160 // 5120)), dtype=np.int16)
    d = np.abs(ia - ib)
    h, w = d.shape
    gh, gw = h // GRID, w // GRID
    cells = (d[:gh * GRID, :gw * GRID] >= 32).reshape(GRID, gh, GRID, gw).mean(axis=(1, 3))
    mask = np.ones((GRID, GRID), bool)
    for c in HUD_CELLS:
        mask[c] = False
    order = np.argsort(cells.ravel())[::-1][:4]
    return {"a": str(a), "b": str(b),
            "differing_px_pct_all": round(100 * float((d >= 32).mean()), 2),
            "differing_px_pct_outside_hud_corners": round(100 * float(cells[mask].mean()), 2),
            "mean_abs_diff": round(float(d.mean()), 2),
            "cells_differing_most_pct": [{"where": cell_name(*divmod(int(i), GRID)), "pct": round(100 * float(cells.ravel()[i]), 1)} for i in order]}


def ratio(x, y):
    return round(x / y, 2) if y else None


def main(argv):
    specs, window, scale, shots, context, out_json, seconds = [], None, 1280, [], "", None, 20.0
    windows = [None, None]  # per-side override: the auto window slides when the desktop stays busy after the quit (2026-09-22)
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--window":
            window = (float(argv[i + 1]), float(argv[i + 2])); i += 3
        elif a in ("--window-a", "--window-b"):
            windows[0 if a == "--window-a" else 1] = (float(argv[i + 1]), float(argv[i + 2])); i += 3
        elif a == "--scale":
            scale = int(argv[i + 1]); i += 2
        elif a == "--seconds":
            seconds = float(argv[i + 1]); i += 2
        elif a == "--shots":
            shots = [argv[i + 1], argv[i + 2]]; i += 3
        elif a == "--context":
            context = argv[i + 1]; i += 2
        elif a == "--json":
            out_json = argv[i + 1]; i += 2
        else:
            specs.append(a); i += 1
    if len(specs) != 2:
        sys.exit(__doc__)
    va, vb = video_of(specs[0]), video_of(specs[1])
    m = []
    for side, v in enumerate((va, vb)):
        if windows[side]:
            s, e = windows[side]
        elif window:
            s, e = window
        else:
            s, e = active_window(v, seconds)
        m.append(measure(v, s, e, scale))
        print(f"{v}: {m[-1]['frames']} frames {s:.1f}-{e:.1f}s, transient {m[-1]['transient_px_per_frame']['mean']} px/frame "
              f"(world {m[-1]['transient_px_world_vs_hud_corners']['world']}, hud {m[-1]['transient_px_world_vs_hud_corners']['hud_corners']}), "
              f"black {m[-1]['black_share']['mean_pct']}% (jumps {m[-1]['black_share']['jumps_over_2pct']}), luma pops {m[-1]['luma_0_255']['pops_over_6']}, "
              f"hard jumps {m[-1]['hard_luma_jumps_px_per_frame_30fps']['mean']}/{m[-1]['hard_luma_jumps_px_per_frame_30fps']['p90']}, solid blocks {m[-1]['solid_jump_blocks_per_frame_30fps']['mean']}")
    ma, mb = m
    state = {
        "context": context or "A = reference recording, B = the recording under test, same route and settings",
        "a": ma, "b": mb,
        "b_over_a": {"transient_px_per_frame": ratio(mb["transient_px_per_frame"]["mean"], ma["transient_px_per_frame"]["mean"]),
                     "world_transients": ratio(mb["transient_px_world_vs_hud_corners"]["world"], ma["transient_px_world_vs_hud_corners"]["world"]),
                     "black_share_mean": ratio(mb["black_share"]["mean_pct"], ma["black_share"]["mean_pct"]),
                     "luma_pops": ratio(mb["luma_0_255"]["pops_over_6"], ma["luma_0_255"]["pops_over_6"]),
                     "hard_luma_jumps_mean": ratio(mb["hard_luma_jumps_px_per_frame_30fps"]["mean"], ma["hard_luma_jumps_px_per_frame_30fps"]["mean"]),
                     "hard_luma_jumps_p90": ratio(mb["hard_luma_jumps_px_per_frame_30fps"]["p90"], ma["hard_luma_jumps_px_per_frame_30fps"]["p90"]),
                     "solid_jump_blocks_mean": ratio(mb["solid_jump_blocks_per_frame_30fps"]["mean"], ma["solid_jump_blocks_per_frame_30fps"]["mean"])},
        "reference_points": {"transients": "stock 3.8 px/frame vs a known broken build 26 at scale 2560 (flicker rig)",
                             "hard_luma_jumps": "torch rig at scale 1280: stock 7667 mean / 16729 p90; the build with the blocky-lights "
                                                "(lighting patchwork) bug 9885 / 22313, i.e. 1.3x; the fixed build 7660 / 16335",
                             "note": f"pixel counts scale with analysed pixels; this run used scale {scale}"},
    }
    if shots:
        state["screenshot_pair"] = shot_diff(shots[0], shots[1], scale)
        print(f"shots: {state['screenshot_pair']['differing_px_pct_outside_hud_corners']}% of world px differ, "
              f"{state['screenshot_pair']['differing_px_pct_all']}% overall")
    questions = {
        "kind": choice({"question": "Compare recording `b` with reference `a` (same route, same window, metrics computed the same way). "
                                    "What best describes the visual relationship?",
                        "note": "Ratios near 1 and small absolute counts mean the same picture. HUD-corner transients are the "
                                "overlay's own numbers changing, not the world. Luma pops in both at similar counts are the "
                                "scene (lightning, headlights), not a defect."},
                       {"parity": "b looks like a: transients, black share and luma pops comparable to a, differences within what two runs of the same build show",
                        "hud_only": "the only notable difference sits in the HUD corners",
                        "flicker": "b has clearly more world transients than a: sprites blinking in and out for a few frames",
                        "black_tiles": "b has a higher black share or more black jumps than a: black squares or tiles appearing",
                        "lighting_pops": "b has clearly more hard luma jumps / solid jump blocks or luma pops than a: lighting patchwork (stale chunks landing late), flash re-bakes, stepped light sweeps",
                        "static_scene_difference": "the screenshot pair (when present) differs over a large share of the world while the motion metrics agree: something is drawn differently but steadily",
                        "inconclusive": "too few frames, windows that do not match, or metrics that contradict each other"}),
        "parity_maintained": noul("Is visual parity maintained in `b` relative to `a`, i.e. no metric shows a new kind of artifact in `b` beyond run-to-run variation?"),
        "look_needed": noul("Should a person watch the two recordings before adopting `b`, because a metric moved enough to be an artifact but not enough to be certain?"),
    }
    log = []
    answers = ask(state, questions, log=log)
    print("jev:")
    print("  " + fmt(answers).replace("\n", "\n  "))
    print(f"  ({log[0]['ms']} ms, {log[0]['model']})")
    out = {"state": state, "answers": answers, "typesafe": log[0]}
    if out_json:
        Path(out_json).write_text(json.dumps(out, indent=1))
    return 0 if answers["parity_maintained"]["noul"] >= 0.7 and answers["kind"]["choice"] in ("parity", "hud_only") else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
