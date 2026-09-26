#!/usr/bin/env python3
"""Flicker along an explore=stairs walk (2026-09-25, the flip report: with every new lighting key on the walls flicker
while the player climbs to the top floor and goes back down to the basement), per phase of the walk, and Jev's verdict.

    harness/stairs-flicker.py RUN [--control RUN] [--before RUN] [--context "..."] [--crops N] [--json out.json] [--judge]

RUN is a run dir with a pzopt.FrameCapture sequence (`--prop devCapture=0,150,240,33,gray`) and the console lines of
pzopt.StairsWalk (`harness: stairs: <event> ... epoch=<ms>`). A transient pixel changes by >= --thresh (32) against the
frame before and comes back within --maxk (4) frames (region-flicker.py's A-B-A detector; steady camera motion and
fades are not counted); the HUD and a disc round the player are masked. A burst frame blinks over > 0.5 % of the world
pixels; a patch frame has solid blinking patches (3x3-eroded mask: a wall or room blinking, not the 1-px shimmer of edges
under the moving camera) over > 0.1 %. Per phase (between two stairs events: looking around on a level, walking to / on the stairs, the first second
on a new level) it prints transient px per frame, burst frames per 10 s, and the worst frames; --crops N writes, for the
N worst burst frames, a strip of frames i-1 .. i+2 cropped to the blinking area (2x) with the blink mask in red, to
RUN/stairs-flicker/. `--mask x0,y0,x1,y1` (fractions) leaves out more of the frame, e.g. `--mask 0.75,0.75,1,1` for the
performance overlay in the bottom-right corner (its frame graph scrolls every frame). --judge asks Jev (text only: the numbers) whether RUN shows the wall flicker, compared with
--control (the same walk with the lighting keys off or the stock game) and --before (the build with the flicker).
"""
import argparse
import json
import os
import re
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent


def load(run):
    d = os.path.join(run, "capture")
    lines = open(os.path.join(d, "index.txt")).read().split("\n")
    head = dict(kv.split("=") for kv in lines[0].split())
    w, h = int(head["w"]), int(head["h"])
    stamps = [int(x) for x in lines[1:] if x.strip()]
    while len(stamps) > 1 and stamps[-1] < stamps[-2]:  # the game quit mid-line: a truncated last stamp
        stamps.pop()
    stamps = np.array(stamps)
    gray = head.get("fmt") == "gray"
    raw = np.memmap(os.path.join(d, "frames.gray" if gray else "frames.rgba"), dtype=np.uint8, mode="r")
    bpp = 1 if gray else 4
    n = min(len(stamps), raw.size // (w * h * bpp))

    def frame(i):
        f = raw[i * w * h * bpp:(i + 1) * w * h * bpp]
        if gray:
            return f.reshape(h, w)[::-1].astype(np.int16)
        f = f.reshape(h, w, 4)[::-1, :, :3].astype(np.float32)
        return (f[..., 0] * 0.299 + f[..., 1] * 0.587 + f[..., 2] * 0.114).astype(np.int16)
    return w, h, stamps[:n], frame


def events(run):
    ev = []
    for ln in open(os.path.join(run, "console.txt"), errors="replace"):
        m = re.search(r"harness: stairs: (.*?) at \+[\d.]+ s epoch=(\d+)", ln)
        if m:
            ev.append((int(m.group(2)), m.group(1)))
    return ev


def world_mask(w, h):
    sys.path.insert(0, str(HERE))
    import importlib.util
    spec = importlib.util.spec_from_file_location("rf", HERE / "region-flicker.py")
    rf = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(rf)
    return rf.hud_mask(w, h)


def solid(m):
    """The blink pixels inside a solid 3x3 patch of blinking (an eroded mask): a wall or a room going dark, not the 1-px
    shimmer of edges and lines under a moving camera."""
    e = m.copy()
    e[1:, :] &= m[:-1, :]
    e[:-1, :] &= m[1:, :]
    e2 = e.copy()
    e2[:, 1:] &= e[:, :-1]
    e2[:, :-1] &= e[:, 1:]
    e2[0, :] = e2[-1, :] = False
    e2[:, 0] = e2[:, -1] = False
    return e2


def transients(w, h, n, frame, world, t, K):
    """Per frame: blink pixels, and blink pixels inside solid patches (the masks of the worst frames are recomputed later)."""
    counts = np.zeros(n, dtype=np.int64)
    areas = np.zeros(n, dtype=np.int64)
    ring = []
    for i in range(n):
        ring.append(frame(i))
        if len(ring) < K + 2:
            continue
        m = blink(ring, world, t)
        counts[i - K] = m.sum()
        areas[i - K] = solid(m).sum()
        ring.pop(0)
    return counts, areas


def blink(ring, world, t):
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
    return hit


def phases(stamps, ev):
    """(name, i0, i1) frame ranges between the stairs events, plus the first second on every new level."""
    t0 = stamps[0]
    idx = lambda e: int(np.searchsorted(stamps, e))
    out = []
    marks = [(e, what) for e, what in ev if stamps[0] <= e <= stamps[-1] + 2000]
    for k, (e, what) in enumerate(marks):
        end = marks[k + 1][0] if k + 1 < len(marks) else stamps[-1]
        nxt = marks[k + 1][1] if k + 1 < len(marks) else "end"
        if what.startswith("done"):
            break
        name = f"{what.split(':')[0]} -> {nxt.split(':')[0]}"
        out.append((name, idx(e), idx(end)))
        if what.startswith("level"):
            out.append((f"first 1.5 s on {what}", idx(e), idx(e + 1500)))
    return out


MASKS = []  # --mask x0,y0,x1,y1 (fractions of the frame): more areas left out, e.g. the performance overlay's corner


def analyse(run, thresh, maxk, crops):
    w, h, stamps, frame = load(run)
    n = len(stamps)
    world = world_mask(w, h)
    for x0, y0, x1, y1 in MASKS:
        world[int(y0 * h):int(y1 * h), int(x0 * w):int(x1 * w)] = False
    wp = int(world.sum())
    counts, areas = transients(w, h, n, frame, world, thresh, maxk)
    ev = events(run)
    t = (stamps - stamps[0]) / 1000.0
    dt = np.diff(stamps)
    burst = counts > 0.005 * wp
    patch = areas > 0.001 * wp  # a patch frame: solid blinking patches over > 0.1 % of the world pixels
    res = {"run": run, "frames": n, "size": [w, h], "fps": round(1000.0 / float(np.median(dt)), 1), "capture_gaps": int((dt > 2.5 * np.median(dt)).sum()),
           "world_px": wp, "events": [{"t_s": round((e - stamps[0]) / 1000.0, 2), "what": what} for e, what in ev], "phases": []}
    for name, i0, i1 in phases(stamps, ev):
        if i1 - i0 < 5:
            continue
        c = counts[i0:i1]
        secs = max(1e-3, t[min(i1, n - 1)] - t[i0])
        worst = np.argsort(c)[::-1][:5]
        res["phases"].append({"phase": name, "t_s": [round(float(t[i0]), 2), round(float(t[min(i1, n - 1)]), 2)], "frames": int(i1 - i0),
                              "transient_px_per_frame": round(float(c.mean()), 1), "per_100k_world_px": round(1e5 * float(c.mean()) / wp, 1),
                              "burst_frames": int(burst[i0:i1].sum()), "burst_frames_per_10s": round(10 * float(burst[i0:i1].sum()) / secs, 1),
                              "max_share_pct": round(100 * float(c.max()) / wp, 2),
                              "solid_px_per_frame": round(float(areas[i0:i1].mean()), 1), "patch_frames": int(patch[i0:i1].sum()),
                              "patch_frames_per_10s": round(10 * float(patch[i0:i1].sum()) / secs, 1), "max_solid_pct": round(100 * float(areas[i0:i1].max()) / wp, 2),
                              "worst": [{"frame": int(i0 + j), "t_s": round(float(t[i0 + j]), 3), "share_pct": round(100 * float(c[j]) / wp, 2)} for j in worst if c[j] > 0]})
    whole = counts[: max(1, n - maxk - 1)]
    res["whole"] = {"transient_px_per_frame": round(float(whole.mean()), 1), "per_100k_world_px": round(1e5 * float(whole.mean()) / wp, 1),
                    "burst_frames": int(burst.sum()), "burst_frames_per_10s": round(10 * float(burst.sum()) / max(1e-3, t[-1]), 1),
                    "max_share_pct": round(100 * float(counts.max()) / wp, 2), "solid_px_per_frame": round(float(areas.mean()), 1),
                    "patch_frames": int(patch.sum()), "patch_frames_per_10s": round(10 * float(patch.sum()) / max(1e-3, t[-1]), 1),
                    "max_solid_pct": round(100 * float(areas.max()) / wp, 2)}
    if crops:
        from PIL import Image, ImageDraw
        od = os.path.join(run, "stairs-flicker")
        os.makedirs(od, exist_ok=True)
        order = [int(i) for i in np.argsort(areas)[::-1] if areas[i] > 0]
        chosen = []
        for i in order:  # distinct events: at least 10 frames apart
            if all(abs(i - j) >= 10 for j in chosen):
                chosen.append(i)
            if len(chosen) >= crops:
                break
        res["crops"] = []
        for i in chosen:
            ring = [frame(j) for j in range(i, min(n, i + maxk + 2))]
            m = blink(ring, world, thresh) if len(ring) >= 3 else np.zeros((h, w), bool)
            m = solid(m)
            ys, xs = np.nonzero(m)
            if len(xs) == 0:
                continue
            x0, x1 = max(0, int(np.percentile(xs, 2)) - 30), min(w, int(np.percentile(xs, 98)) + 30)
            y0, y1 = max(0, int(np.percentile(ys, 2)) - 30), min(h, int(np.percentile(ys, 98)) + 30)
            panels = []
            for j in range(i, min(n, i + 3)):
                f = np.clip(frame(j), 0, 255).astype(np.uint8)[y0:y1, x0:x1]
                panels.append(Image.fromarray(f).convert("RGB"))
            f = np.clip(frame(i + 1), 0, 255).astype(np.uint8)[y0:y1, x0:x1]
            rgb = np.stack([f] * 3, -1).astype(np.float32) * 0.5
            rgb[m[y0:y1, x0:x1], 0] = 255
            panels.append(Image.fromarray(rgb.astype(np.uint8)))
            pw, ph = (x1 - x0) * 2, (y1 - y0) * 2
            sheet = Image.new("RGB", (pw * len(panels), ph + 16), (30, 0, 30))
            dr = ImageDraw.Draw(sheet)
            for k, p in enumerate(panels):
                sheet.paste(p.resize((pw, ph), Image.NEAREST), (k * pw, 16))
            dr.text((2, 2), f"{os.path.basename(run)} frames {i}..{i + 2} (base, blink, after) + mask; t={t[i]:.3f}s share {100 * counts[i] / wp:.2f}%", fill=(255, 255, 0))
            p = os.path.join(od, f"blink-{i:05d}.png")
            sheet.save(p)
            res["crops"].append({"frame": i, "t_s": round(float(t[i]), 3), "share_pct": round(100 * float(counts[i]) / wp, 2), "png": p, "box": [x0, y0, x1, y1]})
    return res


def show(r):
    print(f"{r['run']}: {r['frames']} frames {r['size'][0]}x{r['size'][1]} at {r['fps']} fps, {r['capture_gaps']} capture gaps")
    print("  events: " + "; ".join(f"{e['t_s']}s {e['what'].split(':')[0]}" for e in r["events"]))
    print(f"  {'phase':34s} {'t (s)':>13s} {'px/frame':>9s} {'/100k':>7s} {'bursts':>6s} {'/10s':>6s} {'max %':>6s} {'solid':>6s} {'patch':>5s} {'/10s':>6s} {'max %':>6s}")
    for p in r["phases"]:
        print(f"  {p['phase'][:34]:34s} {p['t_s'][0]:6.1f}-{p['t_s'][1]:6.1f} {p['transient_px_per_frame']:9.1f} {p['per_100k_world_px']:7.1f} {p['burst_frames']:6d} "
              f"{p['burst_frames_per_10s']:6.1f} {p['max_share_pct']:6.2f} {p['solid_px_per_frame']:6.1f} {p['patch_frames']:5d} {p['patch_frames_per_10s']:6.1f} {p['max_solid_pct']:6.2f}")
    wh = r["whole"]
    print(f"  whole walk: {wh['transient_px_per_frame']} px/frame, {wh['per_100k_world_px']} per 100k, {wh['burst_frames']} bursts ({wh['burst_frames_per_10s']}/10 s), max {wh['max_share_pct']} %;"
          f" solid {wh['solid_px_per_frame']} px/frame, {wh['patch_frames']} patch frames ({wh['patch_frames_per_10s']}/10 s), max {wh['max_solid_pct']} %")
    for c in r.get("crops", []):
        print(f"  crop {c['png']}  t={c['t_s']}s {c['share_pct']}%")


def judge(test, control, before, context):
    sys.path.insert(0, str(HERE))
    from typesafe_client import ask, choice, noul, fmt  # noqa: E402

    def brief(r):
        if r is None:
            return None
        return {"whole_walk": r["whole"], "fps": r["fps"], "capture_gaps": r["capture_gaps"],
                "phases": [{k: p[k] for k in ("phase", "t_s", "per_100k_world_px", "burst_frames_per_10s", "solid_px_per_frame", "patch_frames",
                                             "patch_frames_per_10s", "max_solid_pct")} for p in r["phases"]]}
    state = {
        "setup": "Frame-exact captures (every presented frame read back in-game, grayscale) of one scene: a copy of the player's "
                 "save, a police station; the character (directed by Jev) looks around in the basement, walks up the stairs "
                 "floor by floor to the top floor, looking around on each, then back down to the basement. A transient pixel "
                 "changes by >= 32/255 and returns within 4 frames (something appearing, vanishing or changing brightness for "
                 "1-4 frames); steady camera motion, turns and slow fades are not counted; HUD and a disc round the character "
                 "are masked. A burst frame blinks over > 0.5 % of the world pixels; thin edges and lines shimmer under the moving camera "
                 "in every build (stock too), so bursts mostly measure that. `solid_px_per_frame` counts only blink pixels inside "
                 "solid 3x3 patches (a wall, a light patch or a room area blinking), and a patch frame has such patches over "
                 "> 0.1 % of the world pixels: that is the flicker a player notices. Phases run between the walk's events; "
                 "'first 1.5 s on level N' is the arrival on a new floor (cutaway and chunk re-bakes happen there in every "
                 "build). " + context,
        "report": "the player sees the walls flicker a lot while going up the stairs to the top floor and back down to the "
                  "basement, with every new lighting setting on (per-pixel lighting, ambient occlusion, sun shadows)",
        "test": brief(test), "control": brief(control), "before": brief(before),
    }
    print(json.dumps(state, indent=1))
    qs = {
        "flicker_in_test": noul("Does `test` show the reported flicker: frames where a noticeable part of the picture blinks for "
                                "one to four frames while the character walks or looks around, clearly above `control`?"),
        "control_flickers": noul("Does `control` show comparable blinking at comparable rates (a flicker the lighting settings "
                                 "do not cause)?"),
        "kind": choice({"question": "What best describes `test` over the whole walk?"},
                       {"lighting_flicker": "test blinks where control does not: the lighting settings cause flicker",
                        "shared_flicker": "test and control blink alike: not the lighting settings",
                        "arrival_only": "the only excess is on arriving at a new floor (the floor being built up), not steady flicker",
                        "stable": "neither shows blinking beyond the motion background",
                        "inconclusive": "missing phases, capture gaps or contradicting numbers"}),
    }
    if before is not None:
        qs["better_than_before"] = noul("Is `test` clearly less flickery than `before` (the build with the reported flicker)?")
    log = []
    ans = ask(state, qs, log=log)
    print("jev:")
    print("  " + fmt(ans).replace("\n", "\n  "))
    print(f"  ({log[0]['ms']} ms, {log[0]['model']})")
    return {"state": state, "answers": ans}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--control")
    ap.add_argument("--before")
    ap.add_argument("--context", default="")
    ap.add_argument("--thresh", type=int, default=32)
    ap.add_argument("--maxk", type=int, default=4)
    ap.add_argument("--crops", type=int, default=0)
    ap.add_argument("--json")
    ap.add_argument("--judge", action="store_true")
    ap.add_argument("--mask", action="append", default=[], help="x0,y0,x1,y1 fractions of the frame left out (repeatable)")
    a = ap.parse_args()
    MASKS.extend(tuple(float(v) for v in m.split(",")) for m in a.mask)
    test = analyse(a.run, a.thresh, a.maxk, a.crops)
    show(test)
    ctl = bef = None
    if a.control:
        ctl = analyse(a.control, a.thresh, a.maxk, 0)
        show(ctl)
    if a.before:
        bef = analyse(a.before, a.thresh, a.maxk, 0)
        show(bef)
    out = {"test": test, "control": ctl, "before": bef}
    if a.judge:
        out["jev"] = judge(test, ctl, bef, a.context)
    if a.json:
        Path(a.json).write_text(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
