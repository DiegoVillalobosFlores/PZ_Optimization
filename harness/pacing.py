#!/usr/bin/env python3
"""Frame pacing as the display saw it, for variable refresh rate work.

    harness/pacing.py <run> [<run> ...] [--all] [--refresh HZ]

Reads, over the route window (pzopt-bench.out route_start/end_epoch_ms; --all = whole file):
  present.txt       harness/presentprobe: X Present completions (ust = when the frame reached the screen,
                    mode 1 = flip, 0/3 = copy); several completions at one ust = frames replaced before scan-out
  vrr.txt           harness/vrrprobe.py: DRM VRR_ENABLED of the active CRTC
  pzopt-pacing.out  pzopt.Pacing (optional): per frame sim start, swap call, swap return (ns) and the pacing wait
  pzopt-overlay.out swap-return intervals (what the in-game overlay calls frame time)

Prints per run: VRR on share; displayed frames/s and their interval distribution (p1/p50/p99, sd, mean
|delta| between consecutive intervals = jitter); the share of intervals that sit on a multiple of the fixed
refresh period (a fixed-refresh display shows ~100 %, a VRR display running below its maximum much less);
frames replaced before scan-out (rendered, never shown); flip vs copy; and with pzopt-pacing.out the
sim-to-screen error: how far each displayed interval is from the game-time step its frame advanced, which
is the judder a player sees (0 = motion on screen matches game time exactly).
"""
import argparse
import csv
import statistics
from pathlib import Path


def pct(v, p):
    if not v:
        return float("nan")
    v = sorted(v)
    return v[min(len(v) - 1, int(p / 100 * len(v)))]


def window(run):
    bench = run / "pzopt-bench.out"
    if bench.exists():
        kv = dict(l.split("=", 1) for l in bench.read_text().splitlines() if "=" in l)
        if "route_start_epoch_ms" in kv and "route_end_epoch_ms" in kv:
            return int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
    return None


def refresh_hz(run, given):
    if given:
        return given
    v = run / "vrr.txt"
    if v.exists():
        for line in v.read_text().splitlines():
            f = line.split()
            if len(f) >= 5 and not line.startswith("#"):
                return float(f[4])
    return None


def stats(iv):
    if len(iv) < 3:
        return None
    d = [abs(b - a) for a, b in zip(iv, iv[1:])]
    return dict(n=len(iv), mean=statistics.mean(iv), sd=statistics.pstdev(iv), p1=pct(iv, 1), p50=pct(iv, 50),
                p99=pct(iv, 99), p999=pct(iv, 99.9), jitter=statistics.mean(d))


def grid(iv, hz, tol_ms=0.25):
    """Share of intervals within tol of a multiple of the refresh period, and the interval histogram in periods."""
    period = 1000.0 / hz
    on = sum(1 for x in iv if abs(x / period - round(x / period)) * period < tol_ms)
    hist = {}
    for x in iv:
        k = round(x / period * 4) / 4  # quarter periods
        hist[k] = hist.get(k, 0) + 1
    top = sorted(hist.items(), key=lambda kv: -kv[1])[:6]
    return (f"{100 * on / max(1, len(iv)):.0f} % on the {period:.3f} ms grid; most common (in periods): "
            + ", ".join(f"{k:g}x {100 * v / len(iv):.0f}%" for k, v in top))


def fmt(s):
    return (f"n={s['n']} mean {s['mean']:.2f} ms ({1000 / s['mean']:.1f}/s) sd {s['sd']:.2f} p1 {s['p1']:.2f} "
            f"p50 {s['p50']:.2f} p99 {s['p99']:.2f} p99.9 {s['p999']:.2f} jitter {s['jitter']:.2f}")


def analyze(run, all_, hz):
    w = None if all_ else window(run)
    lo, hi = (w if w else (None, None))
    inwin = (lambda t: True) if w is None else (lambda t: lo <= t <= hi)
    print(f"== {run.name}" + ("" if w else "  (whole file)"))
    hz = refresh_hz(run, hz)
    vf = run / "vrr.txt"
    if vf.exists():
        on = tot = 0
        for line in vf.read_text().splitlines():
            f = line.split()
            if line.startswith("#") or len(f) < 4:
                continue
            if inwin(int(f[0])):
                tot += 1
                on += f[3] == "1"
        print(f"vrr: VRR_ENABLED on {on}/{tot} samples ({100 * on / tot if tot else 0:.0f} %), mode {hz} Hz")
    pf = run / "present.txt"
    shown = []
    if pf.exists():
        off = 0
        rows = []
        for line in pf.read_text().splitlines():
            if line.startswith("# mono_to_epoch_us"):
                off = int(line.split()[-1])
                continue
            if line.startswith("#"):
                continue
            f = line.split()
            if len(f) >= 4:
                rows.append((int(f[0]), int(f[1]), int(f[2]), int(f[3])))
        rows = [r for r in rows if inwin((r[0] + off) // 1000)]
        uniq = {}
        modes = {}
        for ust, msc, serial, mode in rows:
            uniq[ust] = uniq.get(ust, 0) + 1
            modes[mode] = modes.get(mode, 0) + 1
        ts = sorted(uniq)
        shown = [(b - a) / 1000.0 for a, b in zip(ts, ts[1:])]
        replaced = sum(n - 1 for n in uniq.values())
        s = stats(shown)
        if s:
            print(f"display: {fmt(s)}")
            if hz:
                period = 1000.0 / hz
                on_grid = sum(1 for x in shown if abs(x / period - round(x / period)) * period < 0.25)
                print(f"display: {100 * on_grid / len(shown):.0f} % of intervals on a multiple of the {period:.3f} ms refresh"
                      f" (fixed refresh ~100 %), {sum(1 for x in shown if x < period * 0.9)} faster than the max refresh")
            names = {0: "copy", 1: "flip", 2: "skip", 3: "suboptimal-copy"}
            print(f"presents: {len(rows)} completed, {replaced} replaced before scan-out ({100 * replaced / max(1, len(rows)):.1f} %), "
                  + ", ".join(f"{names.get(k, k)} {v}" for k, v in sorted(modes.items())))
    of = run / "pzopt-overlay.out"
    if of.exists():
        with open(of) as fh:
            ov = [float(r["frametime"]) for r in csv.DictReader(fh) if inwin(int(r["epoch_ms"]))]
        s = stats(ov)
        if s:
            print(f"swap-return: {fmt(s)}")
            if hz:
                print(f"swap-return: {grid(ov, hz)}")
    pp = run / "pzopt-pacing.out"
    if pp.exists():
        pacing(pp, inwin, pf if pf.exists() else None)


def flips(pf):
    """Unique presentation times (ns, CLOCK_MONOTONIC like System.nanoTime on Linux) from present.txt."""
    ts = set()
    for line in pf.read_text().splitlines():
        if line.startswith("#"):
            continue
        f = line.split()
        if len(f) >= 4:
            ts.add(int(f[0]) * 1000)
    return sorted(ts)


def pacing(pp, inwin, pf):
    """pzopt-pacing.out: epoch_ms sim_ns acquire_ns swap_call_ns swap_ret_ns wait_ns [gpu_done_ns] (monotonic ns, one row per frame)."""
    rows = []
    for line in pp.read_text().splitlines():
        if line.startswith("#"):
            continue
        f = line.split()
        if len(f) >= 6 and inwin(int(f[0])):
            rows.append([int(x) for x in f[:8]] + [0] * (8 - len(f[:8])))
    if len(rows) < 3:
        return
    sim = [(b[1] - a[1]) / 1e6 for a, b in zip(rows, rows[1:])]
    call = [(b[3] - a[3]) / 1e6 for a, b in zip(rows, rows[1:])]
    lat = [(r[4] - r[1]) / 1e6 for r in rows]
    waits = [r[5] / 1e6 for r in rows]
    err = [abs(c - s) for c, s in zip(call, sim)]
    print(f"sim step: {fmt(stats(sim))}")
    print(f"swap call: {fmt(stats(call))}")
    print(f"sim->swap-return latency: p50 {pct(lat, 50):.2f} p99 {pct(lat, 99):.2f} ms; pacing wait mean {statistics.mean(waits):.2f} ms")
    acq = [(r[2] - r[1]) / 1e6 for r in rows]
    ren = [(r[3] - r[2] - r[5]) / 1e6 for r in rows]
    swp = [(r[4] - r[3]) / 1e6 for r in rows]
    print(f"  p50 breakdown: step->acquire {pct(acq, 50):.2f}, acquire->swap call (render, no wait) {pct(ren, 50):.2f}, "
          f"swap call->return {pct(swp, 50):.2f} ms (p99 {pct(acq, 99):.2f} / {pct(ren, 99):.2f} / {pct(swp, 99):.2f})")
    gpu = [(r[6] - r[1]) / 1e6 for r in rows if r[6]]
    if gpu:
        print(f"  step->GPU done: p50 {pct(gpu, 50):.2f} p90 {pct(gpu, 90):.2f} p99 {pct(gpu, 99):.2f} ms")
    print(f"sim-to-submit error |swap call interval - sim step|: mean {statistics.mean(err):.2f} p99 {pct(err, 99):.2f} ms")
    import bisect
    if any(r[7] for r in rows):  # the game reported on-glass times itself (macOS Metal bridge presentedTime)
        shown = [(r[1], r[7], r[3]) for r in rows if r[7]]
        print(f"on-glass times from the game: {len(shown)} of {len(rows)} frames")
    elif pf is not None:
        fl = flips(pf)
        shown = []  # (sim_ns, flip_ns, swap_call_ns) per frame: the first flip at or after its swap call
        for r in rows:
            i = bisect.bisect_left(fl, r[3])
            if i < len(fl) and fl[i] - r[3] < 100_000_000:
                shown.append((r[1], fl[i], r[3]))
    else:
        return
    if len(shown) > 3:
        iv = [(b[1] - a[1]) / 1e6 for a, b in zip(shown, shown[1:]) if b[1] > a[1]]
        print(f"on-glass intervals: {fmt(stats(iv))}")
    if len(shown) < 3:
        return
    lat = [(f - c) / 1e6 for _, f, c in shown]
    jud = [abs((b[1] - a[1]) - (b[0] - a[0])) / 1e6 for a, b in zip(shown, shown[1:]) if b[1] != a[1]]
    tot = [(f - sm) / 1e6 for sm, f, _ in shown]
    print(f"swap call->on screen: p1 {pct(lat, 1):.2f} p50 {pct(lat, 50):.2f} p99 {pct(lat, 99):.2f} ms; step->on screen p50 {pct(tot, 50):.2f} p99 {pct(tot, 99):.2f} ms")
    print(f"ON-SCREEN JUDDER |flip interval - sim step|: mean {statistics.mean(jud):.2f} p50 {pct(jud, 50):.2f} p90 {pct(jud, 90):.2f} "
          f"p99 {pct(jud, 99):.2f} ms; frames off by > 2 ms: {100 * sum(1 for x in jud if x > 2) / len(jud):.1f} %")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--refresh", type=float)
    a = ap.parse_args()
    for r in a.runs:
        analyze(Path(r), a.all, a.refresh)


if __name__ == "__main__":
    main()
