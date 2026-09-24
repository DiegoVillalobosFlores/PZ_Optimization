#!/usr/bin/env python3
"""Ragdoll + crash verdict for the horde-shoot bench (2026-09-24, the "ragdolls slide, spin, sink and fly" report).

    harness/ragdoll-judge.py --stock <run-dir|label>... --opt <run-dir|label>... [--no-jev] [--json out.json]

Per run (a `--flag showcase=horde director=jev burn=false fire_line=false` run; bench `horde-shoot`): a card with
- crash: run.opts crashed=, hs_err files (the problematic frame), whether the world came up and the route completed;
- exceptions in console.txt after world-ready, grouped by type + first stack frame (the reported one was
  ArrayIndexOutOfBounds in CollideWithObstacles.getIntersection), ragdoll calls off the game thread
  (`animatorParallel: ragdoll ... not the game thread`), animatorParallel task failures (`failures=` in pzopt-bench.out);
- ragdoll episodes from pzopt-ragdoll.out (pzopt.RagdollWatch): count, deaths, p50 / p95 / max of each metric and the
  abnormal share per kind with the limits below (fly, slide, spin, under the floor, in the air, body off its character);
- the fight: rounds fired, zombies left, director commands (the `harness: showcase:` lines).
Then Jev compares the optimized cards with the stock ones (text only: the numbers, never the video). The limits are facts
in code; Jev weighs them with the run-to-run spread.
"""
import argparse
import glob
import json
import os
import re
import statistics
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

LIMITS = {  # an episode is abnormal of a kind past its limit (RagdollWatch counts the same live)
    "fly": ("pelvis_speed", ">", 12.0),    # tiles/s over 50 ms: a body thrown off
    "slide": ("late_travel", ">", 3.0),    # tiles of pelvis travel after the first 1.5 s: sliding along the ground
    "drift": ("net_late", ">", 2.0),       # tiles of net pelvis displacement after 1.5 s: carried away along the ground
    "spin": ("late_yaw", ">", 540.0),      # degrees of body-axis turn after 1.5 s: spinning
    "under": ("z_min", "<", -0.25),        # levels below the floor it started on: sinking through it
    "above": ("z_max", ">", 1.2),          # levels above that floor: up in the air
    "detached": ("sep", ">", 2.5),         # tiles between pelvis and character: the body left its character
}
METRICS = ["dur", "fps", "pelvis_speed", "late_travel", "net_late", "late_yaw", "yaw_rate", "z_min", "z_max", "sep", "char_travel"]


def resolve(arg):
    p = Path(arg)
    if p.is_dir():
        return p
    hits = sorted(glob.glob(str(HERE / "runs" / f"{arg}-2*"))) or sorted(glob.glob(str(HERE / "runs" / f"{arg}*")))
    if not hits:
        sys.exit(f"no run matches {arg}")
    return Path(hits[-1])


def kv_line(line):
    return {k: v for k, v in re.findall(r"(\w+)=(\S+)", line)}


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def pct(vals, q):
    if not vals:
        return None
    s = sorted(vals)
    return s[min(len(s) - 1, int(round(q * (len(s) - 1))))]


def card(run):
    c = {"run": run.name}
    opts = {}
    if (run / "run.opts").exists():
        for line in (run / "run.opts").read_text(errors="replace").splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                opts[k] = v
    c["crashed"] = opts.get("crashed") == "1"
    frames = []
    for h in sorted(run.glob("hs_err_pid*.log")):
        t = h.read_text(errors="replace")
        m = re.search(r"^# (C|J|V|j)\s+\[?(.*)$", t, re.M)
        frames.append(m.group(0)[2:].strip()[:160] if m else h.name)
    c["hs_err"] = frames
    con = (run / "console.txt").read_text(errors="replace") if (run / "console.txt").exists() else ""
    c["world_ready"] = "harness: world ready" in con
    bench = (run / "pzopt-bench.out").read_text(errors="replace") if (run / "pzopt-bench.out").exists() else ""
    c["route_status"] = (re.search(r"^route_status=(\S+)", bench, re.M) or [None, "missing"])[1]
    m = re.search(r"animator parallel:.*?failures=(\d+)", bench)
    c["animator_task_failures"] = int(m.group(1)) if m else None
    m = re.search(r"serialRagdoll=(\d+)", bench)
    c["serial_ragdoll"] = int(m.group(1)) if m else None
    c["settings"] = "stock-path" if re.search(r"^settings=.*enabled=false", bench, re.M) else "optimized"
    # exceptions after world-ready: type + first "at" frame
    body = con.split("harness: world ready", 1)[1] if "harness: world ready" in con else con
    lines = body.splitlines()
    exc = {}
    for i, line in enumerate(lines):
        m = re.search(r"\b((?:[a-z]\w*\.)*[A-Z]\w*(?:Exception|Error))\b(?::\s*(.{0,80}))?", line)
        if not m or "harness:" in line or "DebugLog" in line and "Exception" not in line:
            continue
        where = ""
        for nxt in lines[i + 1:i + 6]:
            f = re.search(r"^\s*(?:at )?([a-z][\w.$]+\([\w.]*:?\d*\))", nxt)  # "at x.y(F.java:1)" or the game's tab-indented form
            if f:
                where = f.group(1)
                break
        key = f"{m.group(1).split('.')[-1]} @ {where or '?'}"
        exc[key] = exc.get(key, 0) + 1
    c["exceptions"] = dict(sorted(exc.items(), key=lambda kv: -kv[1])[:12])
    c["exceptions_total"] = sum(exc.values())
    c["ragdoll_off_game_thread_logs"] = len(re.findall(r"animatorParallel: ragdoll .* not the game thread", con))
    c["worker_task_failed_logs"] = len(re.findall(r"animatorParallel: a worker task failed", con))
    # the fight
    sc = re.findall(r"harness: showcase: (\w+) \((\d+) director commands\), (\d+) zombies left.*?rounds (\d+)", con)
    if sc:
        c["director_commands"], c["zombies_left"], c["rounds_fired"] = int(sc[-1][1]), int(sc[-1][2]), int(sc[-1][3])
    m = re.search(r"harness: showcase: (\d+) zombies at", con)
    c["horde"] = int(m.group(1)) if m else None
    # ragdolls
    eps, state = [], {}
    rp = run / "pzopt-ragdoll.out"
    if rp.exists():
        for line in rp.read_text(errors="replace").splitlines():
            if line.startswith("ep "):
                eps.append(kv_line(line))
            elif line.startswith("state "):
                state = kv_line(line)
    c["ragdoll_file"] = rp.exists()
    c["episodes"] = len(eps)
    c["deaths"] = sum(1 for e in eps if e.get("dead") == "true")
    c["off_thread_count"] = int(state.get("off_thread", 0) or 0)
    calc = [e for e in eps if e.get("calculated") == "true"]
    for e in calc:  # the frame rate the episode was sampled at (the ragdoll physics may depend on it)
        if num(e.get("dur")) and num(e["dur"]) > 0.5:
            e["fps"] = str(num(e["frames"]) / num(e["dur"]))
    c["episodes_measured"] = len(calc)
    fl = [num(e["fps"]) for e in calc if "fps" in e]
    c["fps_median"] = round(statistics.median(fl)) if fl else None
    c["metrics"] = {}
    for k in METRICS:
        vals = [num(e.get(k)) for e in calc if num(e.get(k)) is not None]
        if vals:
            c["metrics"][k] = {"p50": round(pct(vals, 0.5), 2), "p95": round(pct(vals, 0.95), 2),
                               "max": round(min(vals) if k == "z_min" else max(vals), 2)}
    ab = {}
    worst = []
    for kind, (key, op, lim) in LIMITS.items():
        hit = [e for e in calc if num(e.get(key)) is not None and (num(e[key]) > lim if op == ">" else num(e[key]) < lim)]
        ab[kind] = len(hit)
        for e in hit[:3]:
            worst.append(f"{kind}: ep {e.get('id')} t={e.get('t')}s {key}={e.get(key)} at {e.get('at')} dead={e.get('dead')}")
    c["abnormal"] = ab
    anyab = [e for e in calc if any(
        num(e.get(key)) is not None and (num(e[key]) > lim if op == ">" else num(e[key]) < lim) for key, op, lim in LIMITS.values())]
    c["abnormal_episodes"] = len(anyab)
    c["abnormal_share"] = round(len(anyab) / len(calc), 3) if calc else None
    c["abnormal_examples"] = worst[:8]
    return c


def summary_line(c):
    ab = " ".join(f"{k}={v}" for k, v in c["abnormal"].items() if v)
    return (f"{c['run']}: {'CRASH ' + '; '.join(c['hs_err'] or ['crashed=1']) if c['crashed'] else 'no crash'}, "
            f"route {c['route_status']}, exceptions {c['exceptions_total']}, off-thread ragdoll {c['off_thread_count']}, "
            f"{c['fps_median']} fps, episodes {c['episodes']} ({c['deaths']} deaths), abnormal {c['abnormal_episodes']}"
            f"{' = ' + format(c['abnormal_share'] * 100, '.1f') + ' %' if c['abnormal_share'] is not None else ''}"
            f"{' [' + ab + ']' if ab else ''}, rounds {c.get('rounds_fired', '?')}, zombies left {c.get('zombies_left', '?')}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--stock", nargs="+", required=True)
    ap.add_argument("--opt", nargs="+", required=True)
    ap.add_argument("--no-jev", action="store_true")
    ap.add_argument("--json")
    a = ap.parse_args()
    stock = [card(resolve(r)) for r in a.stock]
    opt = [card(resolve(r)) for r in a.opt]
    print("== stock")
    for c in stock:
        print("  " + summary_line(c))
        for k, v in c["exceptions"].items():
            print(f"      exc {v:4d}x {k}")
        for w in c["abnormal_examples"]:
            print("      " + w)
    print("== optimized")
    for c in opt:
        print("  " + summary_line(c))
        for k, v in c["exceptions"].items():
            print(f"      exc {v:4d}x {k}")
        for w in c["abnormal_examples"]:
            print("      " + w)
    print("== metrics p50 / p95 / max (measured episodes, pooled)")
    for k in METRICS:
        row = []
        for name, cs in (("stock", stock), ("opt", opt)):
            ms = [c["metrics"].get(k) for c in cs if c["metrics"].get(k)]
            if ms:
                row.append(f"{name} " + " | ".join(f"{m['p50']}/{m['p95']}/{m['max']}" for m in ms))
        print(f"  {k:<12} " + "   ".join(row))
    out = {"stock": stock, "optimized": opt, "limits": {k: f"{v[0]} {v[1]} {v[2]}" for k, v in LIMITS.items()}}
    if not a.no_jev:
        from typesafe_client import ask, choice, noul
        state = {
            "bench": "horde-shoot: Jev directs a god-mode player who shoots a horde of fast zombies with an M16 in daylight; "
                     "every ragdoll episode (a zombie knocked down or killed) is measured frame by frame; travel and turn are sampled "
                     "at 20 Hz; the ragdoll physics itself may depend on the frame rate, so compare runs at the same fps_median",
            "abnormal_limits": out["limits"],
            "stock_runs": [{k: c[k] for k in ("run", "crashed", "hs_err", "route_status", "exceptions_total", "exceptions",
                                              "off_thread_count", "fps_median", "episodes", "deaths", "abnormal", "abnormal_episodes",
                                              "abnormal_share", "metrics", "rounds_fired", "zombies_left") if k in c} for c in stock],
            "optimized_runs": [{k: c[k] for k in ("run", "crashed", "hs_err", "route_status", "exceptions_total", "exceptions",
                                                  "off_thread_count", "fps_median", "episodes", "deaths", "abnormal", "abnormal_episodes",
                                                  "abnormal_share", "metrics", "rounds_fired", "zombies_left") if k in c} for c in opt],
        }
        q = {
            "ragdolls": choice(
                "Compare the optimized runs' ragdoll behaviour with the stock runs'. Use the abnormal episode shares and "
                "kinds, the metric percentiles and the spread between runs of the same build; a difference inside the "
                "spread between runs of one build is noise.",
                {"same_as_stock": "The optimized ragdolls behave like stock's (differences within run-to-run spread).",
                 "optimized_worse": "The optimized runs have clearly more or larger abnormal ragdolls than stock.",
                 "optimized_better": "The optimized runs have clearly fewer abnormal ragdolls than stock.",
                 "inconclusive": "Too few episodes or runs to tell."}),
            "crash": noul("Did any optimized run crash, fail to complete its route, log ragdoll calls off the game "
                          "thread, or log an exception type that no stock run logged?"),
        }
        lat = []
        ans = ask(state, q, log=lat)
        r = ans["ragdolls"]
        probs = " ".join(f"{k}={v:.2f}" for k, v in sorted(r.get("probabilities", {}).items(), key=lambda kv: -kv[1]))
        print(f"== Jev ({lat[0]['ms'] if lat else '?'} ms)")
        print(f"  ragdolls: {r['choice']} (confidence {r.get('confidence', 0):.2f}; {probs})")
        print(f"  optimized crash / new exception / off-thread ragdoll: p={ans['crash']['noul']:.2f}")
        out["jev"] = ans
    if a.json:
        Path(a.json).write_text(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
