#!/usr/bin/env python3
"""Driving smoothness from a run's pzopt-drivejitter.out (`--prop devDriveJitter=true`, pzopt.DriveJitter).

Every frame the eye follows two things while driving: the car (near the screen centre) and the world scrolling under
it. Both should move along a smooth curve through time; any frame that lands off that curve is judder. For each frame
this fits a quadratic in time over the frames within +-W seconds (W = 0.08 s) and takes the residual, in screen pixels
(offscreen pixels / zoom):

  world  the camera offset the world is drawn at (what the background does on screen)
  car    the car's position on screen (camera-relative)

and per frame the displacement against the fit's: stall (< 50 % of the expected step), back (moving against the
expected direction: the rubber band), jump (> 150 %). Only frames above --min-kmh count.

    harness/drivejitter.py <run dir|file> [...] [--min-kmh 20] [--window 0.08] [--skip 2] [--vsync HZ]

--present judges each frame at the time it was really shown (present.txt, CLOCK_MONOTONIC like the log's mono_us).
--vsync HZ judges the frames against display times on that refresh grid (vsync on: a frame is shown whole refresh
periods after the one before) instead of their start times.
"""
import argparse
import math
import os
import sys


def load(path):
    if os.path.isdir(path):
        path = os.path.join(path, "pzopt-drivejitter.out")
    rows = []
    with open(path) as f:
        head = f.readline().split()
        for line in f:
            parts = line.split()
            if len(parts) != len(head):
                continue
            try:
                rows.append({k: float(v) for k, v in zip(head, parts)})
            except ValueError:
                continue
    return rows


def quadfit_residuals(t, y, w):
    """Residual of y[i] against a least-squares quadratic over the points within +-w of t[i] (i excluded)."""
    n = len(t)
    res = [math.nan] * n
    vel = [math.nan] * n
    lo = 0
    hi = 0
    for i in range(n):
        while t[i] - t[lo] > w:
            lo += 1
        while hi < n and t[hi] - t[i] <= w:
            hi += 1
        idx = [j for j in range(lo, hi) if j != i]
        if len(idx) < 6 or t[i] - t[lo] < w * 0.6 or t[hi - 1] - t[i] < w * 0.6:
            continue
        # normal equations for y = a + b x + c x^2, x = t - t[i]
        s0 = s1 = s2 = s3 = s4 = 0.0
        r0 = r1 = r2 = 0.0
        for j in idx:
            x = t[j] - t[i]
            x2 = x * x
            s0 += 1
            s1 += x
            s2 += x2
            s3 += x2 * x
            s4 += x2 * x2
            r0 += y[j]
            r1 += y[j] * x
            r2 += y[j] * x2
        m = [[s0, s1, s2, r0], [s1, s2, s3, r1], [s2, s3, s4, r2]]
        try:
            for c in range(3):
                p = max(range(c, 3), key=lambda r: abs(m[r][c]))
                m[c], m[p] = m[p], m[c]
                if abs(m[c][c]) < 1e-18:
                    raise ZeroDivisionError
                for r in range(3):
                    if r != c:
                        k = m[r][c] / m[c][c]
                        for q in range(c, 4):
                            m[r][q] -= k * m[c][q]
            a = m[0][3] / m[0][0]
            b = m[1][3] / m[1][1]
        except ZeroDivisionError:
            continue
        res[i] = y[i] - a
        vel[i] = b
    return res, vel


def pct(vals, p):
    v = sorted(vals)
    if not v:
        return math.nan
    k = min(len(v) - 1, max(0, int(round(p / 100.0 * (len(v) - 1)))))
    return v[k]


def vsync_clock(t, period):
    """The frames' display times on a refresh grid: each frame is shown whole refresh periods after the previous one."""
    out = [t[0]]
    for i in range(1, len(t)):
        k = max(1, round((t[i] - t[i - 1]) / period))
        out.append(out[-1] + k * period)
    return out


def load_presents(run):
    """present.txt (harness presentprobe, X Present completions): [(ust_us, msc)] in order."""
    path = os.path.join(run, "present.txt") if os.path.isdir(run) else os.path.join(os.path.dirname(run), "present.txt")
    out = []
    try:
        with open(path) as f:
            for line in f:
                if line.startswith("#"):
                    continue
                p = line.split()
                if len(p) >= 2:
                    out.append((int(p[0]), int(p[1])))
    except OSError:
        return None
    return out or None


def swap_clock(rows, run, presents):
    """Exact display times from pzopt-driveswap.out (which frame each swap showed) and present.txt (when each present
    reached the screen): swaps and presents are both in order, each swap takes the first unused present completing at
    most 1 ms before it returned or later. A frame is shown at its first swap's present; frames never swapped (dropped:
    the render thread took a newer one) get None."""
    path = os.path.join(run, "pzopt-driveswap.out") if os.path.isdir(run) else os.path.join(os.path.dirname(run), "pzopt-driveswap.out")
    try:
        sw = [tuple(int(x) for x in l.split()) for l in open(path) if len(l.split()) == 2]
    except OSError:
        return None
    if not sw:
        return None
    ust = [p[0] for p in presents]
    import bisect
    j = bisect.bisect_left(ust, sw[0][1] - 1000)
    shown = {}
    lags = []
    for start, ret in sw:
        while j < len(ust) and ust[j] < ret - 1000:
            j += 1
        if j >= len(ust):
            break
        if start not in shown:
            shown[start] = ust[j]
            lags.append(ust[j] - ret)
        j += 1
    t = [shown.get(int(r["mono_us"])) for r in rows]
    return t, lags


def present_clock(rows, presents):
    """Each frame's display time: the completion of the first present at least D after the frame's start, D chosen so the
    most frames land on distinct presents (the render thread shows frame n after the game thread started it)."""
    import bisect
    starts = [r["mono_us"] for r in rows]
    ust = [p[0] for p in presents]
    best = None
    for d_us in range(0, 40000, 250):
        idx = [bisect.bisect_left(ust, s + d_us) for s in starts]
        distinct = len(set(i for i in idx if i < len(ust)))
        if best is None or distinct > best[0]:
            best = (distinct, d_us, idx)
    _, d_us, idx = best
    t = []
    for i in idx:
        t.append(ust[min(i, len(ust) - 1)] / 1e6)
    shared = sum(1 for k in range(1, len(idx)) if idx[k] == idx[k - 1])
    return t, d_us, shared


def analyse(rows, min_kmh, w, skip, vsync_hz=0.0, presents=None, run_dir=None):
    if not rows:
        return None
    t = [r["t_us"] / 1e6 for r in rows]
    present_info = None
    swapped = swap_clock(rows, run_dir, presents) if presents and "mono_us" in rows[0] and run_dir else None
    if swapped:
        ts, lags = swapped
        keep = [i for i, x in enumerate(ts) if x is not None]
        rows = [rows[i] for i in keep]
        t = [ts[i] / 1e6 for i in keep]
        present_info = ("swap", len(ts) - len(keep), pct(lags, 50) / 1000.0)
    elif presents and "mono_us" in rows[0]:
        t, d_us, shared = present_clock(rows, presents)
        present_info = (d_us, shared)
    elif vsync_hz > 0:
        t = vsync_clock(t, 1.0 / vsync_hz)
    zoom = [r["zoom"] or 1.0 for r in rows]
    out = {"frames": 0}
    series = {
        "world": ([r["cam_x"] for r in rows], [r["cam_y"] for r in rows]),
        "car": ([r["scr_x"] for r in rows], [r["scr_y"] for r in rows]),
    }
    moving = [abs(r["kmh"]) >= min_kmh and t[i] - t[0] >= skip for i, r in enumerate(rows)]
    keep_any = [i for i in range(len(rows)) if moving[i]]
    if len(keep_any) < 50:
        return None
    for name, (xs, ys) in series.items():
        rx, vx = quadfit_residuals(t, xs, w)
        ry, vy = quadfit_residuals(t, ys, w)
        mags = []
        stall = back = jump = counted = 0
        for i in range(1, len(rows)):
            if not moving[i] or math.isnan(rx[i]) or math.isnan(ry[i]) or math.isnan(vx[i]):
                continue
            z = zoom[i]
            mags.append(math.hypot(rx[i], ry[i]) / z)
            dt = t[i] - t[i - 1]
            ex, ey = vx[i] * dt / z, vy[i] * dt / z
            el = math.hypot(ex, ey)
            if el < 0.75:  # expected motion under 0.75 px this frame: steps are not judged
                continue
            dx, dy = (xs[i] - xs[i - 1]) / z, (ys[i] - ys[i - 1]) / z
            proj = (dx * ex + dy * ey) / el
            counted += 1
            if proj < 0:
                back += 1
            elif proj < 0.5 * el:
                stall += 1
            elif proj > 1.5 * el:
                jump += 1
        if not mags:
            continue
        out[name] = {
            "rms": math.sqrt(sum(m * m for m in mags) / len(mags)),
            "p50": pct(mags, 50), "p99": pct(mags, 99), "max": max(mags),
            "stall": 100.0 * stall / counted if counted else math.nan,
            "back": 100.0 * back / counted if counted else math.nan,
            "jump": 100.0 * jump / counted if counted else math.nan,
            "judged": counted,
        }
    frames = [i for i in keep_any]
    out["frames"] = len(frames)
    dts = [t[i] - t[i - 1] for i in frames if i > 0]
    out["fps"] = len(dts) / sum(dts) if dts else math.nan
    out["dt_p99_ms"] = pct(dts, 99) * 1000
    hist = {}
    for i in frames:
        s = int(rows[i]["steps"])
        hist[s] = hist.get(s, 0) + 1
    out["steps"] = hist
    stale = [math.hypot(rows[i]["stale_x"], rows[i]["stale_y"]) / zoom[i] for i in frames]
    out["stale_frames"] = 100.0 * sum(1 for s in stale if s > 0.5) / len(stale)
    out["stale_p99"] = pct(stale, 99)
    # sim step vs wall step: the game simulates fpsMultiplier/60 s per frame (the previous frame's length)
    err = [abs(rows[i]["sim_dt_us"] / 1e6 - (t[i] - t[i - 1])) * 1000 for i in frames if i > 0]
    out["simdt_err_p50_ms"] = pct(err, 50)
    out["simdt_err_p99_ms"] = pct(err, 99)
    out["kmh"] = sum(abs(rows[i]["kmh"]) for i in frames) / len(frames)
    out["zoom"] = sum(zoom[i] for i in frames) / len(frames)
    out["present"] = present_info
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--min-kmh", type=float, default=20.0)
    ap.add_argument("--window", type=float, default=0.08)
    ap.add_argument("--skip", type=float, default=2.0, help="seconds after the first logged frame left out")
    ap.add_argument("--vsync", type=float, default=0.0, help="judge against display times on this refresh grid (Hz) instead of frame starts")
    ap.add_argument("--present", action="store_true", help="judge against each frame's real display time from the run's present.txt (needs mono_us)")
    a = ap.parse_args()
    for run in a.runs:
        try:
            rows = load(run)
        except OSError as e:
            print(f"{run}: {e}", file=sys.stderr)
            continue
        r = analyse(rows, a.min_kmh, a.window, a.skip, a.vsync, load_presents(run) if a.present else None, run)
        name = os.path.basename(os.path.normpath(run))
        if r is None:
            print(f"{name}: too few moving frames ({len(rows)} rows)")
            continue
        steps = ",".join(f"{k}:{100.0 * v / r['frames']:.0f}%" for k, v in sorted(r["steps"].items()))
        print(f"{name}: frames={r['frames']} fps={r['fps']:.1f} dt_p99={r['dt_p99_ms']:.2f}ms kmh={r['kmh']:.0f} zoom={r['zoom']:.2f} "
              f"steps={steps} stale={r['stale_frames']:.0f}% (p99 {r['stale_p99']:.1f}px) simdt_err p50/p99={r['simdt_err_p50_ms']:.2f}/{r['simdt_err_p99_ms']:.2f}ms"
              + ((f" clock=swap+present ({r['present'][1]} frames never shown, present {r['present'][2]:.2f} ms after the swap returned)"
                  if r["present"][0] == "swap" else
                  f" clock=present (start->shown {r['present'][0] / 1000:.1f} ms, {r['present'][1]} frames never shown alone)") if r.get("present") else ""))
        for k in ("world", "car"):
            if k in r:
                s = r[k]
                print(f"  {k:5s} residual rms={s['rms']:.2f}px p50={s['p50']:.2f} p99={s['p99']:.2f} max={s['max']:.1f}  "
                      f"steps: stall={s['stall']:.1f}% back={s['back']:.1f}% jump={s['jump']:.1f}% (of {s['judged']})")


if __name__ == "__main__":
    main()
