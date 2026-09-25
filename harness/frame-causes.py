#!/usr/bin/env python3
"""Why frames miss the cap: per-frame stage split from pzopt-pacing.out, then what the thread did in the late ones.

    harness/frame-causes.py <run> [--cap-ms 4.167] [--depth 4] [--top 25] [--no-jfr]

Stages per presented frame (pzopt.Pacing hooks, System.nanoTime):
  game step      step start -> next step start (game thread, includes the limiter wait when on time)
  step->acquire  step start -> the render thread takes the frame (game thread build + hand-off wait)
  render submit  acquire -> swap call minus the present-pacing hold (render thread GL submission, bakes, driver stalls)
  swap           swap call -> swap return (present back-pressure)
  gpu            swap call -> GPU completion (GL timestamp)
A frame is late when its swap-return interval exceeds the cap; it is blamed on the stage that grew most over its median.
With <run>/asprof.jfr (run.sh --asprof event=cpu,interval=2ms,wall=2ms) the render thread's wall samples inside the
late frames' submit windows and the game thread's inside the late steps are folded (harness/jfr-windows.java), each
next to the same count of on-time windows.
"""
import argparse, collections, os, random, subprocess, tempfile
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("run")
ap.add_argument("--cap-ms", type=float, default=1000 / 240)
ap.add_argument("--depth", type=int, default=4)
ap.add_argument("--top", type=int, default=25)
ap.add_argument("--no-jfr", action="store_true")
ap.add_argument("--render-late-ms", type=float, default=3.5, help="render submit above this = a late submit window")
ap.add_argument("--step-late-ms", type=float, default=5.0)
a = ap.parse_args()
run = Path(a.run)
kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
A, B = int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
rows, off = [], None
for l in (run / "pzopt-pacing.out").read_text().splitlines():
    if l.startswith("#"):
        continue
    p = list(map(int, l.split()))
    o = p[0] * 1_000_000 - p[4]
    off = o if off is None or o > off else off
    if A <= p[0] <= B:
        rows.append(p)


def pct(v, q):
    v = sorted(v)
    return v[min(len(v) - 1, int(q / 100 * len(v)))] if v else float("nan")


segs = collections.defaultdict(list)
late = collections.Counter()
late_ms = collections.Counter()
rs_slow, rs_ok, gs_slow, gs_ok = [], [], [], []
for i in range(1, len(rows) - 1):
    e, sim, acq, sc, sr, w, gd, _ = rows[i]
    psr, psim = rows[i - 1][4], rows[i - 1][1]
    nsim = rows[i + 1][1]
    iv, step = (sr - psr) / 1e6, (nsim - sim) / 1e6
    s2a, a2s, swp = (acq - sim) / 1e6, (sc - acq - w) / 1e6, (sr - sc) / 1e6  # w = the present-pacing hold, not render work
    for k, v in (("interval", iv), ("game step", step), ("step->acquire", s2a), ("render submit", a2s), ("swap", swp)):
        segs[k].append(v)
    if gd:
        segs["gpu"].append((gd - sc) / 1e6)
    if a2s > a.render_late_ms:
        rs_slow.append((acq + off, sc + off))
    elif a2s < 2.0:
        rs_ok.append((acq + off, sc + off))
    if step > a.step_late_ms:
        gs_slow.append((sim + off, nsim + off))
    elif step < a.cap_ms + 0.2:
        gs_ok.append((sim + off, nsim + off))
    if iv > a.cap_ms + 0.4:
        med = {"game step": a.cap_ms, "render submit": 1.6, "swap": 0.15}
        d = {"game step": step - med["game step"], "render submit": a2s - med["render submit"], "swap": swp - med["swap"]}
        k = max(d, key=d.get)
        late[k] += 1
        late_ms[k] += iv - a.cap_ms

n = len(rows)
print(f"== {run.name}: {n} frames in the route window, {sum(late.values())} late (> cap + 0.4 ms)")
for k, v in late.most_common():
    print(f"  late by {k:14} {v:5} frames, {late_ms[k]:7.0f} ms over the cap")
for k, v in segs.items():
    print(f"  {k:14} p50 {pct(v,50):6.2f}  p90 {pct(v,90):6.2f}  p99 {pct(v,99):6.2f}  p99.9 {pct(v,99.9):6.2f}  max {max(v):6.1f} ms")

gs = run / "pzopt-gpusections.out"
if gs.exists():
    import bisect
    pairs = []
    for l in gs.read_text().splitlines():
        if l.startswith("#"):
            continue
        f = l.split()
        pairs.append((int(f[0]), f[1], int(f[2])))
    pairs.sort()
    keys = [p[0] for p in pairs]
    per = []  # (frame gpu total from its sections, {name: ms})
    for i in range(1, len(rows) - 1):
        e, sim, acq, sc, sr, w, gd, _ = rows[i]
        lo, hi = bisect.bisect_left(keys, acq), bisect.bisect_right(keys, sc)
        d = collections.Counter()
        for t, name, ns in pairs[lo:hi]:
            d[name] += ns / 1e6
        per.append(((gd - sc) / 1e6 if gd else 0.0, d))
    heavy = [d for g, d in per if g > 4.0]
    light = [d for g, d in per if 0 < g < 2.5]
    names = sorted({n for _, d in per for n in d})
    print(f"\n#### GPU sections per frame (ms, mean): {len(heavy)} frames finishing > 4 ms after the swap call vs {len(light)} < 2.5 ms")
    rowsout = []
    for n in names:
        mh = sum(d[n] for d in heavy) / max(1, len(heavy))
        ml = sum(d[n] for d in light) / max(1, len(light))
        ph = pct([d[n] for d in heavy], 90) if heavy else 0
        rowsout.append((mh - ml, n, mh, ml, ph))
    for diff, n, mh, ml, ph in sorted(rowsout, reverse=True)[:a.top]:
        print(f"  {n:20} heavy {mh:6.2f} (p90 {ph:6.2f})  light {ml:6.2f}  diff {diff:+6.2f}")

bk = run / "pzopt-bakes.out"
if bk.exists():
    import bisect
    brow = [list(map(int, l.split())) for l in bk.read_text().splitlines() if l and l[0].isdigit()]
    bns = [r[0] for r in brow]
    names = ["create", "object", "cutaway", "trees", "redraw", "lighting", "other"]
    by = collections.defaultdict(lambda: [0, 0, 0.0, 0.0, collections.Counter()])  # bucket -> frames, late, submit, step, classes
    for i in range(1, len(rows) - 2):
        s1, s2 = rows[i + 1][1], rows[i + 2][1]
        j = bisect.bisect_left(bns, s1)
        if j >= len(brow) or brow[j][0] >= s2:
            continue
        cls = brow[j][1:]
        n = sum(cls)
        b = n if n < 8 else (8 if n < 12 else (12 if n < 20 else (20 if n < 30 else 30)))
        iv = (rows[i][4] - rows[i - 1][4]) / 1e6
        e = by[b]
        e[0] += 1
        e[1] += iv > a.cap_ms + 0.4
        e[2] += (rows[i][3] - rows[i][2]) / 1e6
        e[3] += (rows[i + 1][1] - rows[i][1]) / 1e6
        for k, v in zip(names, cls):
            e[4][k] += v
    tot = sum(e[0] for e in by.values())
    print(f"\n#### bakes recorded per game step (pzopt-bakes.out) vs that frame")
    for b in sorted(by):
        f, lt, sub, st, c = by[b]
        mix = " ".join(f"{k}={c[k]/f:.1f}" for k in names if c[k])
        print(f"  {b:>2}{'+' if b >= 8 else ' '} bakes: {f:5} frames ({100*f/tot:4.1f}%), late {100*lt/f:5.1f}%, render submit {sub/f:5.2f} ms, game step {st/f:5.2f} ms  per frame: {mix}")

jfr = run / "asprof.jfr"
if a.no_jfr or not jfr.exists():
    raise SystemExit(0)
random.seed(1)
here = Path(__file__).resolve().parent
for thread, slow, ok, name in (("main", rs_slow, rs_ok, "render submit"), ("MainThread", gs_slow, gs_ok, "game step")):
    ok = random.sample(ok, min(len(ok), len(slow)))
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
        for s, e in slow:
            f.write(f"late {s} {e}\n")
        for s, e in ok:
            f.write(f"ontime {s} {e}\n")
    print(f"\n#### {name} ({thread}): {len(slow)} late windows vs {len(ok)} on-time")
    r = subprocess.run(["java", str(here / "jfr-windows.java"), str(jfr), f.name, thread, "wall", str(a.depth), str(a.top)],
                       capture_output=True, text=True)
    print(r.stdout + r.stderr[-2000:])
    os.unlink(f.name)
