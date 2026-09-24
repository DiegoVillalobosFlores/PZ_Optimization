#!/usr/bin/env python3
"""Where the game's CPU time goes on a hybrid CPU, and what it costs in watts (2026-09-24, E-core pass).

Reads <run>/schedmon.txt (run.sh --schedmon 0.25; harness/schedmon.py writes each thread's last CPU, the fast / slow
core classes and a FREQ line with both classes' mean clock and the amdgpu PPT package power) over the route window of
<run>/pzopt-bench.out and prints:
  - package power (mean / p50 / p95 W) and the mean clock of the fast and slow cores;
  - the process's CPU in cores (1.0 = one logical CPU busy all the time), split by where it ran;
  - per thread (pools grouped): cores used, the share of that time on fast cores, run-queue wait.
The fast / slow split weights each sample interval's run time by the CPU the thread was seen on at its end, so a thread
that migrates within a 250 ms interval is attributed to one class; good enough to verify a placement.
Usage: corepower.py RUN [--top 16] [--json]
"""
import argparse, collections, json, re, statistics
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("run")
ap.add_argument("--top", type=int, default=16)
ap.add_argument("--json", action="store_true", help="one JSON object instead of the table")
args = ap.parse_args()
run = Path(args.run)

kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
A, B = int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])


def cpus(spec):
    out = set()
    for part in spec.split(","):
        if "-" in part:
            a, b = part.split("-")
            out.update(range(int(a), int(b) + 1))
        elif part:
            out.add(int(part))
    return out


fast = set()
samples = collections.OrderedDict()  # t -> {tid: (comm, run_ns, wait_ns, cpu)}
freq = []  # (t, fast_mhz, slow_mhz, watts)
gpu_ms = {}  # process comm -> GPU engine ms in the route window (DRM fdinfo)
others = {}  # other processes: comm -> [cpu ms, cpu ms seen on fast cores] (the ten busiest per second)
gm = []  # (t, socket_mw, gfx_mw, cores_mw, fast_cores_mw, slow_cores_mw, gfx_activity)
for line in (run / "schedmon.txt").read_text().splitlines():
    if line.startswith("# cores"):
        m = re.search(r"fast=(\S+)", line)
        fast = cpus(m.group(1)) if m else set()
        continue
    if line.startswith("#"):
        continue
    f = line.split()
    t = int(f[0])
    if f[1] == "-":
        if f[2] == "FREQ":
            num = lambda x: float(x) if x != "-" else None
            freq.append((t, num(f[3]), num(f[4]), num(f[5])))
        elif f[2] == "GM":
            gm.append((t, *map(int, f[3:11])) if len(f) >= 11 else (t, *map(int, f[3:9]), 0, 0))
        elif f[2] == "GPU" and A <= t <= B:
            for item in f[3:]:
                comm, _pid, g, k = item.rsplit(":", 3)
                gpu_ms[comm] = gpu_ms.get(comm, 0.0) + float(g) + float(k)
        elif f[2] == "PROC" and A <= t <= B:
            for item in f[3:]:
                comm, _pid, ms, cpu = item.rsplit(":", 3)
                o = others.setdefault(comm, [0.0, 0.0])
                o[0] += float(ms)
                if int(cpu) in fast:
                    o[1] += float(ms)
        continue
    cpu = int(f[8]) if len(f) > 8 else -1
    runs = int(f[9]) if len(f) > 9 else 0
    samples.setdefault(t, {})[f[1]] = (f[2], int(f[3]), int(f[4]), cpu, runs)

ts = [t for t in samples if A <= t <= B]
if len(ts) < 2:
    raise SystemExit("schedmon.txt has no samples in the route window (run.sh --schedmon 0.25)")
wall_ns = (ts[-1] - ts[0]) * 1e6


def group(comm):
    if re.match(r"(GC_Thread|G1_|GC_|ZWorker|ZDirector|ZStat|ZUnmapper|ZDriver|VM_Periodic)", comm):
        return "[GC]"
    if re.match(r"(C2_Compiler|C1_Compiler|Compiler)", comm):
        return "[JIT]"
    m = re.match(r"(pzopt-frame|pzopt-char|pzopt-recalc|pool-\d+-thread|ForkJoinPool|pzopt-batch|pzopt-bone)", comm)
    if m:
        return "[" + m.group(1) + "]"
    return comm


per = collections.defaultdict(lambda: [0.0, 0.0, 0.0, 0.0])  # name -> run_ns, run_ns on fast, wait_ns, runs
tot = [0.0, 0.0]
for t0, t1 in zip(ts, ts[1:]):
    s0, s1 = samples[t0], samples[t1]
    for tid, (comm, r, w, cpu, runs) in s1.items():
        o = s0.get(tid)
        dr = r - (o[1] if o else 0)
        dw = w - (o[2] if o else 0)
        dn = runs - (o[4] if o else runs)
        if dr <= 0 and dw <= 0:
            continue
        g = per[group(comm)]
        g[0] += dr
        g[2] += dw
        g[3] += max(0, dn)
        tot[0] += dr
        if cpu in fast:
            g[1] += dr
            tot[1] += dr

fw = [x for x in freq if A <= x[0] <= B]
watts = [x[3] for x in fw if x[3] is not None]
fmhz = [x[1] for x in fw if x[1] is not None]
smhz = [x[2] for x in fw if x[2] is not None]


def pct(v, q):
    v = sorted(v)
    return v[min(len(v) - 1, int(q * len(v)))] if v else None


gw = [x for x in gm if A <= x[0] <= B]
rails = {}
if gw:
    for i, k in enumerate(("socket_w", "gfx_w", "cores_w", "fast_cores_w", "slow_cores_w"), 1):
        rails[k] = round(statistics.mean(x[i] for x in gw) / 1000, 2)
    rails["other_w"] = round(rails["socket_w"] - rails["gfx_w"] - rails["cores_w"], 2)
    rails["gfx_activity"] = round(statistics.mean(x[6] for x in gw))
    rails["dram_gbs"] = round(statistics.mean(x[7] + x[8] for x in gw) / 1000, 1)

res = {
    "rails": rails,
    "route_s": round((B - A) / 1000, 1),
    "ppt_w_mean": round(statistics.mean(watts), 2) if watts else None,
    "ppt_w_p50": pct(watts, 0.5),
    "ppt_w_p95": pct(watts, 0.95),
    "fast_mhz": round(statistics.mean(fmhz)) if fmhz else None,
    "slow_mhz": round(statistics.mean(smhz)) if smhz else None,
    "cores_used": round(tot[0] / wall_ns, 2),
    "cores_on_fast": round(tot[1] / wall_ns, 2),
    "others": {k: {"cores": round(v[0] / (B - A), 3), "fast_share": round(v[1] / v[0], 2) if v[0] else None}
               for k, v in sorted(others.items(), key=lambda kv: -kv[1][0])[:8]},
    "gpu_busy": {k: round(v / (B - A), 3) for k, v in sorted(gpu_ms.items(), key=lambda kv: -kv[1])[:6]},
    "threads": {k: {"cores": round(v[0] / wall_ns, 3), "fast_share": round(v[1] / v[0], 2) if v[0] else None,
                    "wait": round(v[2] / wall_ns, 3), "wakeups_s": round(v[3] / (wall_ns / 1e9))} for k, v in sorted(per.items(), key=lambda kv: -kv[1][0])},
    "wakeups_s": round(sum(v[3] for v in per.values()) / (wall_ns / 1e9)),
}
if args.json:
    print(json.dumps(res))
    raise SystemExit
print(f"route {res['route_s']} s   package {res['ppt_w_mean']} W mean, p50 {res['ppt_w_p50']}, p95 {res['ppt_w_p95']}"
      f"   clocks fast {res['fast_mhz']} MHz, slow {res['slow_mhz']} MHz")
if rails:
    print(f"SMU rails: socket {rails['socket_w']} W = GFX {rails['gfx_w']} + cores {rails['cores_w']} (fast {rails['fast_cores_w']},"
          f" slow {rails['slow_cores_w']}) + rest {rails['other_w']};  GFX activity {rails['gfx_activity']} %;  DRAM {rails['dram_gbs']} GB/s")
print(f"process CPU {res['cores_used']} cores, {res['cores_on_fast']} of them on fast cores"
      + ("" if fast else "   (no core classes in schedmon.txt: not a hybrid CPU or an old schedmon)"))
print(f"process wakeups {res['wakeups_s']}/s")
print(f"{'thread':<24}{'cores':>7}{'on fast':>9}{'rq wait':>9}{'wakeups/s':>11}")
for k, v in list(res["threads"].items())[: args.top]:
    fs = "-" if v["fast_share"] is None else f"{100 * v['fast_share']:.0f} %"
    print(f"{k:<24}{v['cores']:>7.3f}{fs:>9}{v['wait']:>9.3f}{v['wakeups_s']:>11}")
print("most wakeups: " + "  ".join(f"{k} {v['wakeups_s']}/s" for k, v in sorted(res["threads"].items(), key=lambda kv: -kv[1]["wakeups_s"])[:10]))
if res["others"]:
    print("other processes: " + "  ".join(f"{k} {v['cores']:.3f}" + ("" if v["fast_share"] is None else f" ({100 * v['fast_share']:.0f} % fast)")
                                       for k, v in res["others"].items()))
if res["gpu_busy"]:
    print("GPU engine time (share of wall): " + "  ".join(f"{k} {100 * v:.1f} %" for k, v in res["gpu_busy"].items()))
