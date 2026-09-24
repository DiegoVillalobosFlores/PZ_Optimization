#!/usr/bin/env python3
"""Summarise one harness run: chunk-load throughput/timings and frame-time distribution.

  harness/analyze.py <run-dir> [--json <out.json>] [--skip-seconds N]

Reads pzopt-chunks.out and pzopt-frames.out written by pzopt.Stats. Frame
samples from the first --skip-seconds (default 20) are dropped: they cover the
initial world load, which is not what the benchmark measures.
"""
import json
import re
import statistics
import sys
from pathlib import Path


def pct(sorted_vals, p):
    if not sorted_vals:
        return 0
    k = (len(sorted_vals) - 1) * p / 100.0
    lo, hi = int(k), min(int(k) + 1, len(sorted_vals) - 1)
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (k - lo)


def summarize(run, skip_seconds=20):
    run = Path(run)
    out = {"run": run.name}
    bench_meta = run / "pzopt-bench.out"
    if bench_meta.exists():
        out["scenario"] = dict(
            line.split("=", 1) for line in bench_meta.read_text().splitlines() if "=" in line
        )
        out["valid"] = out["scenario"].get("route_status", "complete") == "complete"
    chunks = []
    marks = {}
    p = run / "pzopt-chunks.out"
    if p.exists():
        with p.open() as f:
            header = f.readline().rstrip("\n").split("\t")
            for line in f:
                if line.startswith("#"):
                    _, label, t = line.split()
                    marks[label] = int(t)
                    continue
                parts = line.rstrip("\n").split("\t")
                if len(parts) != len(header):
                    continue
                chunks.append(dict(zip(header, parts)))
    # a scripted route brackets the interesting part; keep only chunks enqueued inside it
    if "route-start" in marks and "route-end" in marks and "tEnqueueUs" in (chunks[0] if chunks else {}):
        t0, t1 = marks["route-start"], marks["route-end"]
        chunks = [c for c in chunks if t0 <= int(c["tEnqueueUs"]) <= t1]
        out["route_seconds"] = (t1 - t0) / 1e6
    if chunks:
        recalc = sorted(int(c["recalcUs"]) for c in chunks if c["recalcUs"] != "0" or c["loadUs"] != "0")
        load = sorted(int(c["loadUs"]) for c in chunks)
        wait = sorted(int(c["queueWaitUs"]) for c in chunks)
        rwait = sorted(int(c["recalcWaitUs"]) for c in chunks)
        threads = {}
        for c in chunks:
            threads[c["thread"]] = threads.get(c["thread"], 0) + 1
        total_load_s = sum(load) / 1e6
        total_recalc_s = sum(recalc) / 1e6
        out["chunks"] = {
            "count": len(chunks),
            "threads": threads,
            "load_us": {"mean": statistics.fmean(load), "p50": pct(load, 50), "p90": pct(load, 90), "p99": pct(load, 99), "max": load[-1], "total_s": total_load_s},
            "recalc_us": {"mean": statistics.fmean(recalc), "p50": pct(recalc, 50), "p90": pct(recalc, 90), "p99": pct(recalc, 99), "max": recalc[-1], "total_s": total_recalc_s},
            "queue_wait_us": {"mean": statistics.fmean(wait), "p50": pct(wait, 50), "p90": pct(wait, 90), "p99": pct(wait, 99), "max": wait[-1]},
            "recalc_wait_us": {"mean": statistics.fmean(rwait), "p50": pct(rwait, 50), "p99": pct(rwait, 99), "max": rwait[-1]},
            "recalc_share_of_streamer_time": total_recalc_s / (total_load_s + total_recalc_s) if (total_load_s + total_recalc_s) else 0,
        }
    frames = []
    p = run / "pzopt-frames.out"
    in_route = None  # None = no markers, keep all; else only frames between the markers
    if p.exists():
        for line in p.read_text().splitlines():
            if line.startswith("#"):
                label = line.split()[1]
                if label == "route-start":
                    frames = []
                    in_route = True
                    skip_seconds = 0
                elif label == "route-end":
                    in_route = False
                continue
            if in_route is False:
                continue
            if line.strip():
                frames.append(int(line))
    if frames:
        skip = 0 if in_route is not None else skip_seconds
        # drop the first N seconds of samples (initial load)
        acc, i = 0, 0
        while i < len(frames) and acc < skip * 1e6:
            acc += frames[i]
            i += 1
        used = sorted(frames[i:])
        if used:
            total_s = sum(used) / 1e6
            out["frames"] = {
                "count": len(used),
                "seconds": total_s,
                "fps_mean": len(used) / total_s if total_s else 0,
                "us": {"mean": statistics.fmean(used), "p50": pct(used, 50), "p90": pct(used, 90), "p99": pct(used, 99), "p99_9": pct(used, 99.9), "max": used[-1]},
                "over_33ms": sum(1 for x in used if x > 33333),
                "over_50ms": sum(1 for x in used if x > 50000),
                "over_100ms": sum(1 for x in used if x > 100000),
            }
    if chunks and out.get("frames"):
        out["chunks"]["per_second"] = out["chunks"]["count"] / out.get("route_seconds", out["frames"]["seconds"] + skip_seconds)
    # external MangoHud log (frametime column in ms; elapsed in ns) restricted to the
    # route window using the wall-clock stamps pzopt-bench.out records
    mh = run / "mangohud.csv"
    bench = run / "pzopt-bench.out"
    if mh.exists():
        m = mangohud_summary(mh, run / "mangohud.name", bench)
        if m:
            out["mangohud"] = m
    # the in-game overlay's log (pzopt.Overlay): same columns plus epoch_ms per row, so it
    # windows itself; the platform-independent replacement for the MangoHud log
    ov = run / "pzopt-overlay.out"
    if ov.exists():
        m = mangohud_summary(ov, run / "mangohud.name", bench)
        if m:
            out["overlay"] = m
    # the overlay's game-thread stack profile (pzopt.GameThreadProfile): what the game thread
    # spent the route on, by phase / sub-phase / hot method, and what it waited on
    gt = run / "pzopt-gamethread.out"
    if gt.exists():
        g = gamethread_summary(gt, bench)
        if g:
            out["gamethread"] = g
    sysmon = run / "sysmon.csv"
    if sysmon.exists() and bench.exists():
        sm = sysmon_summary(sysmon, bench)
        if sm:
            out["sysmon"] = sm
    # power (2026-09-24): the in-game sampler (pzopt.Power, cpu_w / gpu_w / soc_w / bat_w / total_w) and the Mac's
    # harness/macpower.py (system_w = the whole machine), same epoch_ms-first CSV shape as sysmon.csv
    for fname, key in (("pzopt-power.out", "power_game"), ("power.csv", "power_mac")):
        if (run / fname).exists() and bench.exists():
            pw = sysmon_summary(run / fname, bench)
            if pw:
                out[key] = pw
    threads = run / "pzopt-threads.out"
    if threads.exists():
        out["threads"] = thread_summary(threads)
    console = run / "console.txt"
    if console.exists():
        out["environment"] = environment(console)
    props = run / "pzopt.properties"
    if props.exists():
        out["props"] = props.read_text().strip()
    gc = gc_summary(run)
    if gc:
        out["gc"] = gc
    opts = run / "run.opts"
    if opts.exists():
        out["opts"] = dict(l.split("=", 1) for l in opts.read_text().splitlines() if "=" in l)
    return out


# MangoHud columns are per frame. frametime is in ms, elapsed in ns since the
# log started; cpu_load / gpu_load are percentages (cpu_load is of all threads,
# so 100/16 = 6.25 % per fully busy core on this machine).
FPS_TARGET = 240.0
FRAME_BUDGET_US = 1e6 / FPS_TARGET


def mangohud_summary(mh, name_file, bench):
    """Frame-time distribution AND utilization from the MangoHud CSV, restricted to
    the route window using the wall-clock stamps pzopt-bench.out records."""
    lines = mh.read_text().splitlines()
    hdr_i = next((i for i, l in enumerate(lines) if l.startswith("fps,")), None)
    if hdr_i is None:
        return None
    hdr = lines[hdr_i].split(",")
    cols = {k: hdr.index(k) for k in hdr}
    ft_i, el_i = cols["frametime"], cols["elapsed"]
    rows = []
    for l in lines[hdr_i + 1:]:
        parts = l.split(",")
        if len(parts) >= len(hdr):
            try:
                rows.append((float(parts[el_i]) / 1e9, float(parts[ft_i]), parts))
            except ValueError:
                pass
    t_start = None
    if "epoch_ms" in cols and rows:
        # the overlay log stamps every row: the log start is the first row's epoch minus its elapsed
        t_start = float(rows[0][2][cols["epoch_ms"]]) / 1000 - rows[0][0]
    else:
        # file name carries the log start time to the second: ProjectZomboid64_YYYY-MM-DD_HH-MM-SS.csv
        import datetime, re
        name = name_file.read_text().strip() if name_file.exists() else mh.name
        m = re.search(r"(\d{4}-\d{2}-\d{2})_(\d{2})-(\d{2})-(\d{2})", name)
        if m:
            t_start = datetime.datetime.strptime(f"{m.group(1)} {m.group(2)}:{m.group(3)}:{m.group(4)}", "%Y-%m-%d %H:%M:%S").timestamp()
    sel = rows
    if t_start is not None and bench.exists():
        kv = dict(l.split("=", 1) for l in bench.read_text().splitlines() if "=" in l)
        if "route_start_epoch_ms" in kv:
            a = int(kv["route_start_epoch_ms"]) / 1000 - t_start
            b = int(kv["route_end_epoch_ms"]) / 1000 - t_start
            sel = [r for r in rows if a <= r[0] <= b]
    if not sel:
        return None
    ft_seq = [r[1] * 1000 for r in sel]  # microseconds, in time order
    ft = sorted(ft_seq)
    out = {
        "count": len(ft),
        "seconds": sum(ft) / 1e6,
        "us": {"mean": statistics.fmean(ft), "p50": pct(ft, 50), "p90": pct(ft, 90), "p99": pct(ft, 99), "p99_9": pct(ft, 99.9), "max": ft[-1]},
        "over_33ms": sum(1 for x in ft if x > 33333),
        "windowed": sel is not rows,
        # consistency: how far the frame-to-frame time moves, and how much of the
        # route ran below the 240 fps cap (a frame at the cap is ~4.2 ms)
        "stdev_us": statistics.pstdev(ft),
        "jitter_us": statistics.fmean(abs(b - a) for a, b in zip(ft_seq, ft_seq[1:])) if len(ft_seq) > 1 else 0,
        "under_cap_share": sum(1 for x in ft if x > FRAME_BUDGET_US * 1.1) / len(ft),
        "fps_1pct_low": 1e6 / pct(ft, 99) if pct(ft, 99) else 0,
    }
    # utilization columns, when the profile logged them
    util = {}
    for k in ("cpu_load", "gpu_load", "game_load", "render_load", "gpu_ms", "gpu_core_clock", "gpu_mem_clock", "cpu_mhz", "gpu_power", "cpu_power", "gpu_temp", "cpu_temp", "gpu_vram_used", "ram_used", "process_rss"):
        if k not in cols:
            continue
        vals = []
        for r in sel:
            try:
                vals.append(float(r[2][cols[k]]))
            except (ValueError, IndexError):
                pass
        # MangoHud cannot read the GPU on the native GL path (gpu_load 0, idle clocks, cpu_power 0):
        # a column that never moves is dropped rather than reported as "0 %"; sysmon.csv is the GPU source
        if vals and max(vals) > 0:
            vs = sorted(vals)
            util[k] = {"mean": statistics.fmean(vals), "p10": pct(vs, 10), "p90": pct(vs, 90), "max": vs[-1]}
    if util:
        out["util"] = util
    return out


def gamethread_summary(path, bench):
    """Shares of the game thread's stack samples over the route window, from the per-second rows
    of pzopt-gamethread.out (epoch_ms, samples, key=count...; keys p: phase, s: sub-phase,
    l: hot method, w: wait state + method). Without a route window every row counts."""
    a = b = None
    if bench.exists():
        kv = dict(l.split("=", 1) for l in bench.read_text().splitlines() if "=" in l)
        if "route_start_epoch_ms" in kv and "route_end_epoch_ms" in kv:
            a, b = int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
    hz = None
    totals = {}
    samples = seconds = 0
    for line in path.read_text(errors="replace").splitlines():
        if line.startswith("#"):
            m = re.search(r"(\d+) Hz", line)
            hz = int(m.group(1)) if m else None
            continue
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        try:
            epoch, n = int(parts[0]), int(parts[1])
        except ValueError:
            continue
        # a row covers the second ending at epoch: inside the window if that second overlaps it
        if a is not None and (epoch < a or epoch - 1000 > b):
            continue
        samples += n
        seconds += 1
        for kv in parts[2:]:
            k, _, v = kv.rpartition("=")
            if k and v.isdigit():
                totals[k] = totals.get(k, 0) + int(v)
    if samples == 0:
        return None

    def family(prefix, n):
        rows = sorted(((v, k[len(prefix):]) for k, v in totals.items() if k.startswith(prefix)), reverse=True)
        return [{"name": name, "share": v / samples} for v, name in rows[:n]]
    # the tree: phases, their sub-phases, the hottest methods under each, waits attached where they happened
    tree = []
    for p in family("p:", 8):
        phase = p["name"]
        subs = []
        for s_ in family(f"s:{phase}/", 12):
            sub = s_["name"]
            subs.append({
                "name": sub, "share": s_["share"],
                "wait_share": sum(v for k, v in totals.items() if k.startswith(f"w:{phase}/{sub}/")) / samples,
                "hot": family(f"l:{phase}/{sub}/", 3),
            })
        tree.append({"name": phase, "share": p["share"], "wait_share": sum(v for k, v in totals.items() if k.startswith(f"w:{phase}/")) / samples, "sub": subs})
    return {
        "samples": samples, "seconds": seconds, "hz": hz, "windowed": a is not None,
        "tree": tree, "hot": family("l:", 12),
        "wait_share": sum(v for k, v in totals.items() if k.startswith("w:")) / samples,
    }


def sysmon_summary(path, bench):
    """harness/sysmon.sh samples inside the route window: machine CPU %, busiest core %,
    GPU % / clocks / power, and the game process's CPU (100 = one core)."""
    kv = dict(l.split("=", 1) for l in bench.read_text().splitlines() if "=" in l)
    if "route_start_epoch_ms" not in kv:
        return None
    a, b = int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
    lines = path.read_text().splitlines()
    if len(lines) < 2:
        return None
    hdr = lines[0].split(",")
    cols = {k: [] for k in hdr[1:]}
    for l in lines[1:]:
        parts = l.split(",")
        if len(parts) != len(hdr):
            continue
        try:
            t = int(parts[0])
        except ValueError:
            continue
        if not (a <= t <= b):
            continue
        for k, v in zip(hdr[1:], parts[1:]):
            try:
                cols[k].append(float(v))
            except ValueError:
                pass
    out = {}
    for k, vals in cols.items():
        if vals:
            vs = sorted(vals)
            out[k] = {"mean": statistics.fmean(vals), "p10": pct(vs, 10), "p90": pct(vs, 90), "max": vs[-1], "n": len(vals)}
    return out or None


def power_watts(s):
    """(total W, source, {rail: mean W}) over the route window: the harness sampler (sysmon.csv) first, then the game's
    pzopt-power.out, then the Mac's power.csv (system_w is its whole machine). total is None when the CPU side is unknown."""
    def mean(d, k):
        v = (d or {}).get(k)
        return v["mean"] if v else None
    found = []
    for key, src, rails in (("sysmon", "sysmon", ("cpu_w", "soc_w", "gpu_w", "bat_w")), ("power_game", "in game", ("cpu_w", "soc_w", "gpu_w", "bat_w")),
                            ("power_mac", "macpower", ("cpu_w", "gpu_w"))):
        d = s.get(key)
        got = {k: mean(d, k) for k in rails if mean(d, k) is not None}
        total = mean(d, "system_w" if key == "power_mac" else "total_w")
        if got or total is not None:
            found.append((total, src, got))
    # the first source with a total (a Mac run's sysmon.csv may carry GPU watts only), else the first with any rail
    return next((f for f in found if f[0] is not None), found[0] if found else (None, None, {}))


def power_line(s):
    total, src, rails = power_watts(s)
    if src is None:
        return None
    names = {"cpu_w": "CPU", "soc_w": "APU socket", "gpu_w": "GPU", "bat_w": "battery"}
    parts = [f"{names[k]} {v:.1f} W" for k, v in rails.items()]
    fps = (s.get("frames") or {}).get("fps_mean")
    head = f"power ({src}, route window): " + (f"total {total:.1f} W" if total is not None else "total n/a (no CPU reading)")
    if total is not None and fps:
        head += f" = {total / fps:.3f} J/frame at {fps:.0f} fps"
    return head + ("; " + ", ".join(parts) if parts else "")


def thread_summary(path):
    """pzopt-threads.out: per-thread CPU over the route, written by pzopt.Harness."""
    out = {"threads": []}
    for line in path.read_text().splitlines():
        if line.startswith("#"):
            for kv in line[1:].split():
                if "=" in kv:
                    k, v = kv.split("=", 1)
                    try:
                        out[k] = float(v)
                    except ValueError:
                        out[k] = v
            continue
        parts = line.split("\t")
        if len(parts) == 3 and parts[0] != "thread":
            out["threads"].append({"name": parts[0], "cpu_ms": int(parts[1]), "share": float(parts[2])})
    return out


def environment(console):
    """Renderer / JVM / display facts from console.txt that make runs comparable or not."""
    env = {}
    keys = {"OpenGL version:": "opengl", "Desktop resolution": "desktop", "java.vm.vendor=": "jvm_vendor", "java.vm.version=": "jvm_version",
            "revision=": "revision", "GPU:": "gpu", "Launching with": "launch"}
    with console.open(errors="replace") as f:
        for line in f:
            if len(env) == len(keys):
                break
            for needle, k in keys.items():
                if k not in env and needle in line:
                    env[k] = line.split("> ", 1)[-1].strip()[:160]
    return env


def gc_summary(run):
    """Collector events inside the route window, from the run's gc.log (-Xlog:gc, info level).

    G1 logs every stop-the-world pause with its duration; ZGC at this level logs
    its (mostly concurrent) cycles, so for ZGC the wall time is not pause time —
    the stop-the-world pauses then come from the JFR recording (samples.tsv,
    jdk.GCPhasePause) when the run was profiled.
    """
    import datetime, re
    run = Path(run)
    log = run / "gc.log"
    bench = run / "pzopt-bench.out"
    if not log.exists() or not bench.exists():
        return None
    kv = dict(l.split("=", 1) for l in bench.read_text().splitlines() if "=" in l)
    if "route_start_epoch_ms" not in kv:
        return None
    a, b = int(kv["route_start_epoch_ms"]) / 1000, int(kv["route_end_epoch_ms"]) / 1000
    collector = None
    events, pauses = [], []
    pat = re.compile(r"^\[(\S+?)\]\[[\d.,]+s\] GC\((\d+)\) (.*?) ([\d.,]+)(ms|s)$")  # older runs logged comma decimals (LC_NUMERIC)
    for line in log.read_text().splitlines():
        if "Using" in line and "Garbage Collector" in line:
            collector = "G1" if "G1" in line else "ZGC" if "Z Garbage" in line else line.split("Using ")[1]
            continue
        m = pat.match(line)
        if not m:
            continue
        t_end = datetime.datetime.fromisoformat(m.group(1)).timestamp()
        ms = float(m.group(4).replace(",", ".")) * (1000 if m.group(5) == "s" else 1)
        desc = m.group(3)
        if not (a <= t_end <= b):
            continue
        events.append((t_end, desc, ms))
        if desc.startswith("Pause"):
            pauses.append((t_end, desc, ms))
    out = {"collector": collector, "events_in_route": len(events), "wall_ms_in_route": sum(e[2] for e in events),
           "max_event_ms": max([e[2] for e in events], default=0)}
    if collector == "G1":
        out["pauses_in_route"] = len(pauses)
        out["pause_ms_in_route"] = sum(p[2] for p in pauses)
        out["max_pause_ms"] = max([p[2] for p in pauses], default=0)
    tsv = run / "samples.tsv"
    if tsv.exists():
        stw, stalls = [], []
        with tsv.open() as f:
            for line in f:
                if line.startswith("pause\t") or line.startswith("stall\t"):
                    kind, name, t, us = line.rstrip("\n").split("\t")
                    t = int(t) / 1e9
                    if a <= t <= b:
                        (stw if kind == "pause" else stalls).append(int(us) / 1000)
        out["jfr_stw_pauses_in_route"] = len(stw)
        out["jfr_stw_pause_ms_in_route"] = sum(stw)
        out["jfr_max_stw_pause_ms"] = max(stw, default=0)
        out["jfr_alloc_stalls_in_route"] = len(stalls)
        out["jfr_alloc_stall_ms_in_route"] = sum(stalls)
    return out


def fmt_us(v):
    return f"{v/1000:.1f}ms"


def print_summary(s):
    print(f"== {s['run']}  [{s.get('props','')}]")
    c = s.get("chunks")
    if c:
        print(f"chunks: {c['count']} loaded, {c.get('per_second',0):.1f}/s; threads {c['threads']}")
        for k in ("load_us", "recalc_us", "queue_wait_us"):
            d = c[k]
            extra = f"  total {d['total_s']:.1f}s" if "total_s" in d else ""
            print(f"  {k:14s} mean {fmt_us(d['mean'])}  p50 {fmt_us(d['p50'])}  p90 {fmt_us(d['p90'])}  p99 {fmt_us(d['p99'])}  max {fmt_us(d['max'])}{extra}")
        print(f"  recalc share of streamer time: {c['recalc_share_of_streamer_time']*100:.0f}%")
    f = s.get("frames")
    if f:
        u = f["us"]
        print(f"frames: {f['count']} over {f['seconds']:.0f}s, {f['fps_mean']:.1f} fps mean")
        print(f"  frame  mean {fmt_us(u['mean'])}  p50 {fmt_us(u['p50'])}  p90 {fmt_us(u['p90'])}  p99 {fmt_us(u['p99'])}  p99.9 {fmt_us(u['p99_9'])}  max {fmt_us(u['max'])}")
        print(f"  frames >33ms: {f['over_33ms']}  >50ms: {f['over_50ms']}  >100ms: {f['over_100ms']}")
    for src in ("mangohud", "overlay"):
        m = s.get(src)
        if not m:
            continue
        u = m["us"]
        print(f"{src}: {m['count']} frames over {m['seconds']:.0f}s{' (route window)' if m['windowed'] else ''}, {m['count'] / m['seconds']:.1f} fps mean")
        print(f"  frame  mean {fmt_us(u['mean'])}  p50 {fmt_us(u['p50'])}  p90 {fmt_us(u['p90'])}  p99 {fmt_us(u['p99'])}  p99.9 {fmt_us(u['p99_9'])}  max {fmt_us(u['max'])}  >33ms: {m['over_33ms']}")
        print(f"  consistency: stdev {fmt_us(m['stdev_us'])}  frame-to-frame jitter {fmt_us(m['jitter_us'])}  1%-low {m['fps_1pct_low']:.0f} fps  frames below the {FPS_TARGET:.0f} fps cap: {m['under_cap_share'] * 100:.1f}%")
        ut = m.get("util")
        if ut:
            def f(k, unit="", scale=1):
                d = ut.get(k)
                return f"{k} {d['mean'] * scale:.0f}{unit} (p10 {d['p10'] * scale:.0f}, p90 {d['p90'] * scale:.0f})" if d else None
            parts = [x for x in (f("cpu_load", "%"), f("gpu_load", "%"), f("game_load", "% of a core"), f("render_load", "% of a core"), f("gpu_core_clock", "MHz"), f("cpu_mhz", "MHz"), f("gpu_power", "W"), f("cpu_power", "W")) if x]
            print("  utilization: " + "; ".join(parts))
    sm = s.get("sysmon")
    if sm:
        def f(k, unit=""):
            d = sm.get(k)
            return f"{k} {d['mean']:.0f}{unit} (p10 {d['p10']:.0f}, p90 {d['p90']:.0f})" if d else None
        parts = [x for x in (f("cpu_pct", "%"), f("cpu_busiest_core_pct", "%"), f("game_cpu_pct", "% of a core"), f("gpu_pct", "%"), f("gpu_sm_mhz", "MHz"), f("gpu_w", "W"), f("bat_w", "W battery")) if x]
        print(f"machine (sysmon, {sm[next(iter(sm))]['n']} samples in the route window): " + "; ".join(parts))
    pw = power_line(s)
    if pw:
        print(pw)
    g = s.get("gamethread")
    if g:
        # the tree, biggest first at every level; waits in brackets where they happened; the hottest methods as a hint
        print(f"game thread ({g['samples']} stack samples over {g['seconds']} s{' in the route window' if g['windowed'] else ''}, {g['hz'] or '?'} Hz), most time first; waiting {g['wait_share'] * 100:.0f}% of it")
        for p in g["tree"]:
            if p["share"] < 0.01:
                continue
            w = f"  [waiting {p['wait_share'] * 100:.0f}%]" if p["wait_share"] >= 0.005 else ""
            print(f"  {p['share'] * 100:3.0f}%  {p['name']}{w}")
            for sub in p["sub"]:
                if sub["share"] < 0.01:
                    continue
                w = f"  [waiting {sub['wait_share'] * 100:.0f}%]" if sub["wait_share"] >= 0.005 else ""
                hot = "  ".join(f"{h['name'].split('/')[-1]} {h['share'] * 100:.0f}%" for h in sub["hot"] if h["share"] >= 0.01)
                print(f"      {sub['share'] * 100:3.0f}%    {sub['name']}{w}" + (f"    {hot}" if hot else ""))
        hot = "  ".join(f"{h['name'].split('/')[-1]} {h['share'] * 100:.0f}%" for h in g["hot"][:8] if h["share"] >= 0.01)
        print(f"  hottest methods: {hot}")
    t = s.get("threads")
    if t and t["threads"]:
        cores = int(t.get("cores", 0))
        proc = t.get("process_share")
        head = f"threads over the route (cpu / wall): process {proc * 100:.0f}% of one core" + (f" = {proc / cores * 100:.0f}% of {cores} cores" if cores and proc else "") if proc else "threads over the route (cpu / wall)"
        print(head)
        for th in t["threads"][:8]:
            print(f"  {th['share'] * 100:6.1f}%  {th['name']}")
    e = s.get("environment")
    if e:
        print("environment: " + "; ".join(f"{k}={v}" for k, v in e.items() if k in ("opengl", "desktop", "jvm_vendor", "revision")))
    g = s.get("gc")
    if g:
        line = f"gc ({g['collector']}): {g['events_in_route']} events in the route window, {g['wall_ms_in_route']:.0f} ms wall (max {g['max_event_ms']:.0f} ms)"
        if "pauses_in_route" in g:
            line += f"; {g['pauses_in_route']} pauses totalling {g['pause_ms_in_route']:.1f} ms (max {g['max_pause_ms']:.1f} ms)"
        if "jfr_stw_pauses_in_route" in g:
            line += (f"; JFR: {g['jfr_stw_pauses_in_route']} STW pauses {g['jfr_stw_pause_ms_in_route']:.2f} ms (max {g['jfr_max_stw_pause_ms']:.2f} ms), "
                     f"{g['jfr_alloc_stalls_in_route']} allocation stalls {g['jfr_alloc_stall_ms_in_route']:.1f} ms")
        print(line)


if __name__ == "__main__":
    args = sys.argv[1:]
    out_json = None
    skip = 20
    if "--json" in args:
        i = args.index("--json"); out_json = args[i + 1]; del args[i:i + 2]
    if "--skip-seconds" in args:
        i = args.index("--skip-seconds"); skip = float(args[i + 1]); del args[i:i + 2]
    for run in args:
        s = summarize(run, skip)
        print_summary(s)
        if out_json:
            Path(out_json).write_text(json.dumps(s, indent=1))
