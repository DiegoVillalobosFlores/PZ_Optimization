#!/usr/bin/env python3
"""Compare benchmark runs against the stock baseline and say whether each
difference exceeds run-to-run noise.

  harness/compare.py <run-dir>... [--baseline harness/baseline]

Noise floor per metric = spread between the two stock baseline runs
(bench-stock-1.json / bench-stock-2.json), never less than a small absolute
floor. A difference counts as real when it exceeds twice that floor. Frame
times come from the in-game sampler and, when present, MangoHud.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from analyze import summarize  # noqa: E402

METRICS = [
    # (label, getter, unit, lower_is_better, absolute noise floor)
    ("chunk latency p50 (enqueue→publish)", lambda s: s["latency"]["p50"], "ms", True, 2.0),
    ("chunk latency p90", lambda s: s["latency"]["p90"], "ms", True, 5.0),
    ("chunk latency p99", lambda s: s["latency"]["p99"], "ms", True, 20.0),
    ("chunks/s", lambda s: s["chunks"]["per_second"], "/s", False, 1.0),
    ("recalc per chunk mean", lambda s: s["chunks"]["recalc_us"]["mean"] / 1000, "ms", True, 0.1),
    ("streamer busy", lambda s: 100 * (s["chunks"]["load_us"]["total_s"] + s["chunks"]["recalc_us"]["total_s"]) / s["route_seconds"], "%", True, 0.5),
    ("frame mean", lambda s: s["frames"]["us"]["mean"] / 1000, "ms", True, 0.2),
    ("frame p99", lambda s: s["frames"]["us"]["p99"] / 1000, "ms", True, 0.5),
    ("frame p99.9", lambda s: s["frames"]["us"]["p99_9"] / 1000, "ms", True, 2.0),
    ("frame max", lambda s: s["frames"]["us"]["max"] / 1000, "ms", True, 10.0),
    ("frames >33ms", lambda s: s["frames"]["over_33ms"], "", True, 3),
    ("MangoHud mean", lambda s: s["mangohud"]["us"]["mean"] / 1000, "ms", True, 0.2),
    ("MangoHud p99", lambda s: s["mangohud"]["us"]["p99"] / 1000, "ms", True, 0.5),
    ("MangoHud p99.9", lambda s: s["mangohud"]["us"]["p99_9"] / 1000, "ms", True, 2.0),
    ("MangoHud >33ms", lambda s: s["mangohud"]["over_33ms"], "", True, 3),
    # consistency: frame-time spread and frame-to-frame movement; share of the route below the 240 fps cap
    ("MangoHud stdev", lambda s: s["mangohud"]["stdev_us"] / 1000, "ms", True, 0.3),
    ("MangoHud frame-to-frame jitter", lambda s: s["mangohud"]["jitter_us"] / 1000, "ms", True, 0.2),
    ("frames below 240 fps cap", lambda s: 100 * s["mangohud"]["under_cap_share"], "%", True, 2.0),
    # utilization: the objective is to use the machine when not at the cap, so higher is better here
    ("CPU load (all threads)", lambda s: s["mangohud"]["util"]["cpu_load"]["mean"], "%", False, 2.0),
    # MangoHud's GPU columns read 0 / idle clocks on the native GL path (see docs/archive/2026-09-24/native-baseline-2026-09-18.md);
    # they are skipped when they carry no signal and the sysmon rows below are the GPU measurement
    ("GPU load (MangoHud)", lambda s: nonzero(s["mangohud"]["util"]["gpu_load"]["mean"]), "%", False, 3.0),
    ("GPU core clock (MangoHud)", lambda s: nonzero(s["mangohud"]["util"]["gpu_core_clock"]["mean"]), "MHz", False, 50.0),
    ("process CPU (cores busy)", lambda s: s["threads"]["process_share"], "", False, 0.1),
    ("machine CPU (sysmon)", lambda s: s["sysmon"]["cpu_pct"]["mean"], "%", False, 2.0),
    ("busiest core (sysmon)", lambda s: s["sysmon"]["cpu_busiest_core_pct"]["mean"], "%", False, 3.0),
    ("GPU load (sysmon)", lambda s: s["sysmon"]["gpu_pct"]["mean"], "%", False, 3.0),
    ("GPU SM clock (sysmon)", lambda s: s["sysmon"]["gpu_sm_mhz"]["mean"], "MHz", False, 50.0),
    ("GPU power (sysmon)", lambda s: s["sysmon"]["gpu_w"]["mean"], "W", True, 0.5),
    ("battery draw (sysmon)", lambda s: s["sysmon"]["bat_w"]["mean"], "W", True, 0.5),
    ("game thread CPU share", lambda s: thread_share(s, "MainThread"), "%", True, 3.0),
    # the GL thread is "Render Thread" on the Windows build and "main" on the native Linux build
    ("render thread CPU share", lambda s: thread_share(s, "Render Thread", "main"), "%", True, 3.0),
    # collector activity inside the route window (harness/analyze.py gc_summary): for G1 these are the
    # stop-the-world pauses; for ZGC the events are mostly concurrent cycles and only the JFR rows are pauses
    ("GC events in route", lambda s: s["gc"]["events_in_route"], "", True, 1),
    ("GC event wall time in route", lambda s: s["gc"]["wall_ms_in_route"], "ms", True, 100.0),
    ("GC pauses in route (G1 log)", lambda s: s["gc"]["pauses_in_route"], "", True, 1),
    ("GC pause time in route (G1 log)", lambda s: s["gc"]["pause_ms_in_route"], "ms", True, 5.0),
    ("GC max pause (G1 log)", lambda s: s["gc"]["max_pause_ms"], "ms", True, 2.0),
    ("GC STW pauses in route (JFR)", lambda s: s["gc"]["jfr_stw_pauses_in_route"], "", True, 1),
    ("GC STW pause time in route (JFR)", lambda s: s["gc"]["jfr_stw_pause_ms_in_route"], "ms", True, 1.0),
]


def latency(run):
    """enqueue→publish per chunk on the route, from pzopt-chunks.out."""
    p = Path(run) / "pzopt-chunks.out"
    vals = []
    marks = {}
    with p.open() as f:
        hdr = f.readline().rstrip("\n").split("\t")
        for line in f:
            if line.startswith("#"):
                _, label, t = line.split()
                marks[label] = int(t)
                continue
            d = dict(zip(hdr, line.rstrip("\n").split("\t")))
            if len(d) != len(hdr):
                continue
            t = int(d["tEnqueueUs"])
            if "route-start" in marks and t < marks["route-start"]:
                continue
            if "route-end" in marks and t > marks["route-end"]:
                continue
            vals.append((int(d["queueWaitUs"]) + int(d["loadUs"]) + int(d["recalcWaitUs"]) + int(d["recalcUs"]) + int(d["publishWaitUs"])) / 1000)
    vals.sort()

    def pct(p):
        k = (len(vals) - 1) * p / 100
        lo, hi = int(k), min(int(k) + 1, len(vals) - 1)
        return vals[lo] + (vals[hi] - vals[lo]) * (k - lo)
    return {"p50": pct(50), "p90": pct(90), "p99": pct(99)}


def thread_share(s, *names):
    """CPU/wall share of the first thread whose name is in names, or None when absent."""
    for t in s["threads"]["threads"]:
        if t["name"] in names:
            return t["share"] * 100
    return None


def nonzero(v):
    return v if v else None


def get(m, s):
    try:
        return m[1](s)
    except (KeyError, ZeroDivisionError, TypeError, StopIteration, IndexError):
        return None


def main(argv):
    base_dir = Path("harness/baseline")
    runs = []
    i = 0
    while i < len(argv):
        if argv[i] == "--baseline":
            base_dir = Path(argv[i + 1]); i += 2
        else:
            runs.append(argv[i]); i += 1
    runs_dir = Path("harness/runs")
    def load_base(name):
        f = base_dir / name
        if f.exists():
            b = json.loads(f.read_text())
            d = next(runs_dir.glob(b["run"]), None)
            b["latency"] = latency(d) if d else {}
            return b
        return None
    b1, b2 = load_base("bench-stock-1.json"), load_base("bench-stock-2.json")
    if b1 is None or b2 is None:
        # baselines are re-summarised from their run directories when the JSON is missing or stale
        stock_runs = sorted(runs_dir.glob("*stock-[12]-*"))
        if len(stock_runs) < 2:
            sys.exit(f"no baseline pair in {base_dir} (bench-stock-1.json / bench-stock-2.json)")
        b1, b2 = (dict(summarize(r), latency=latency(r)) for r in stock_runs[-2:])
    # the baseline and the candidates must have run on the same renderer and desktop, or the tail moves for reasons that are not code
    env_b = b1.get("environment", {})
    print(f"baseline: {b1['run']} and {b2['run']} (noise floor = their spread, min absolute floor per metric)\n")
    stock_of = {}
    header = f"{'metric':38s} {'stock':>9s} {'noise':>7s}"
    cands = []
    for r in runs:
        s = summarize(r)
        s["latency"] = latency(r)
        cands.append(s)
        env = s.get("environment", {})
        for k in ("opengl", "desktop"):
            if env_b.get(k) and env.get(k) and env_b[k] != env[k]:
                print(f"WARNING: {Path(r).name} {k} differs from the baseline: '{env[k]}' vs '{env_b[k]}' — frame-time rows are not comparable")
        header += f" | {Path(r).name[:22]:>22s}"
    print(header)
    for m in METRICS:
        v1, v2 = get(m, b1), get(m, b2)
        if v1 is None or v2 is None:
            continue
        stock = (v1 + v2) / 2
        noise = max(abs(v1 - v2), m[4])
        stock_of[m[0]] = (stock, noise)
        line = f"{m[0]:38s} {stock:9.1f} {noise:7.1f}"
        for s in cands:
            v = get(m, s)
            if v is None:
                line += f" | {'-':>22s}"
                continue
            d = v - stock
            better = (d < 0) == m[3]
            if abs(d) <= 2 * noise:
                verdict = "within noise"
            else:
                verdict = ("better" if better else "WORSE") + f" {abs(d)/stock*100:.0f}%" if stock else ("better" if better else "WORSE")
            line += f" | {v:8.1f} {verdict:>13s}"
        print(line)
    print("\nsettings per run:")
    for s in cands:
        opts = s.get("opts", {})
        extra = " ".join(f"{k}={v}" for k, v in opts.items() if k in ("jfr", "jfr_period", "gc", "no_dashboard", "game_profiler") and v not in ("0", "", "default"))
        print(f"  {s['run']}: {s.get('props','').replace(chr(10), ' ')}" + (f"  [{extra}]" if extra else ""))
        g = s.get("gc")
        if g and "jfr_stw_pauses_in_route" in g:
            print(f"    {g['collector']}: {g['jfr_stw_pauses_in_route']} STW pauses {g['jfr_stw_pause_ms_in_route']:.2f} ms (max {g['jfr_max_stw_pause_ms']:.2f} ms), "
                  f"{g['jfr_alloc_stalls_in_route']} allocation stalls {g['jfr_alloc_stall_ms_in_route']:.1f} ms in the route window (JFR)")
    # profiler overhead: a run recorded with --jfr against the stock frame distribution
    for s in cands:
        if s.get("opts", {}).get("jfr") != "1":
            continue
        parts = []
        for name in ("frame mean", "frame p99", "frame p99.9", "MangoHud p99"):
            m = next(mm for mm in METRICS if mm[0] == name)
            v = get(m, s)
            if v is None or name not in stock_of:
                continue
            stock, noise = stock_of[name]
            d = v - stock
            parts.append(f"{name} {d:+.1f} ms ({'within noise' if abs(d) <= 2 * noise else 'EXCEEDS noise'})")
        print(f"JFR overhead ({s['run']}, sampling every {s['opts'].get('jfr_period') or '10'} ms): " + ", ".join(parts))


if __name__ == "__main__":
    main(sys.argv[1:])
