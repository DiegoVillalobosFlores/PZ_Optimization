#!/usr/bin/env python3
"""Did a run get the performance uplift we were after? A TypeSafe (Jev) classifier over
analyze.py's numbers.

  harness/judge.py <run-dir> [--against <run-dir|baseline.json>...] --goal "<what the change
                   was meant to do>" [--cap 240] [--json out.json] [--quiet]

Code does the arithmetic: the run's frame-tail / utilization card (in-game overlay log first,
MangoHud second, the in-game sampler last), the deltas against each --against run with
compare.py's per-metric noise floors (a delta is "real" past twice the floor), and the
objective's own facts (at the cap? CPU or GPU saturated? "below cap and nothing saturated" is a
finding). Jev reads that JSON plus the free-text goal and answers typed questions: verdict
(achieved / partial / no_change / regressed / invalid), goal met, tail regressed, setup matches
the goal. Jev never sees raw logs; it sees the card. Exit status 0 = achieved, 1 = anything else,
so it can gate a script. Answers are written to <run>/judge.json.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from analyze import summarize, FPS_TARGET  # noqa: E402
from compare import METRICS, get  # noqa: E402
from typesafe_client import ask, choice, noul, fmt  # noqa: E402
import drive_check  # noqa: E402

RUNS = Path("harness/runs")

OBJECTIVE = ("Consistent frame time if the CPU and GPU utilization allows it; the CPU and GPU should always "
             "be used to the max if the framerate is not smoothly pegged at the cap. Every report shows "
             "frame-tail metrics (p99 / p99.9 / spikes / jitter) and utilization. 'fps below the cap and "
             "hardware not saturated' is itself a finding.")


def r(v, n=1):
    return None if v is None else round(v, n)


def card(s, cap):
    """Compact, human-readable metric card from an analyze.py summary."""
    src = next((k for k in ("overlay", "mangohud") if s.get(k)), None)
    fr = s.get(src) if src else s.get("frames")
    c = {"run": s.get("run"), "props": s.get("props") or "", "frame_source": src or ("in-game sampler" if fr else None)}
    if fr:
        u = fr["us"]
        c["frames"] = {
            "fps_mean": r(fr["count"] / fr["seconds"] if src else fr["fps_mean"]),
            "mean_ms": r(u["mean"] / 1000, 2), "p50_ms": r(u["p50"] / 1000, 2), "p99_ms": r(u["p99"] / 1000, 2),
            "p99_9_ms": r(u["p99_9"] / 1000, 2), "max_ms": r(u["max"] / 1000), "frames_over_33ms": fr.get("over_33ms"),
            "seconds": r(fr["seconds"]),
        }
        if src:
            c["frames"].update({"stdev_ms": r(fr["stdev_us"] / 1000, 2), "jitter_ms": r(fr["jitter_us"] / 1000, 2),
                                "fps_1pct_low": r(fr["fps_1pct_low"]), "pct_frames_below_cap": r(100 * fr["under_cap_share"])})
            util = fr.get("util") or {}
            c["utilization"] = {k: r(util[k]["mean"]) for k in ("cpu_load", "gpu_load", "game_load", "render_load") if util.get(k) and util[k].get("mean")}
    sm = s.get("sysmon")
    if sm:
        c["machine"] = {k: r(sm[k]["mean"]) for k in ("cpu_pct", "cpu_busiest_core_pct", "gpu_pct", "gpu_sm_mhz", "gpu_w") if sm.get(k)}
    th = s.get("threads")
    if th:
        c["process_cores_busy"] = r(th.get("process_share"), 2)
        c["top_threads"] = [f"{t['name']} {t['share'] * 100:.0f}%" for t in th["threads"][:4]]
    if s.get("environment"):
        c["environment"] = {k: v for k, v in s["environment"].items() if k in ("opengl", "gpu", "desktop", "jvm_vendor", "revision")}
    if s.get("chunks"):
        c["chunks"] = {"loaded": s["chunks"]["count"], "per_second": r(s["chunks"].get("per_second"))}
    # facts the objective defines; computed here, not asked
    fps = c.get("frames", {}).get("fps_mean")
    gpu = (c.get("machine") or {}).get("gpu_pct") or (c.get("utilization") or {}).get("gpu_load")
    cpu_core = (c.get("machine") or {}).get("cpu_busiest_core_pct")
    cpu_all = (c.get("machine") or {}).get("cpu_pct") or (c.get("utilization") or {}).get("cpu_load")
    at_cap = fps is not None and cap and fps >= 0.97 * cap
    saturated = (gpu or 0) >= 90 or (cpu_core or 0) >= 90 or (cpu_all or 0) >= 85
    c["facts"] = {
        "cap_fps": cap, "at_cap": bool(at_cap), "gpu_saturated": (gpu or 0) >= 90,
        "cpu_saturated": (cpu_core or 0) >= 90 or (cpu_all or 0) >= 85,
        "below_cap_and_hardware_idle": bool(fps is not None and not at_cap and not saturated),
        "valid_measurement": bool(fr) and bool(sm) and bool(th),
        "missing": [k for k, v in (("frame log", fr), ("sysmon", sm), ("thread table", th)) if not v],
    }
    return c


UTIL_WORDS = ("CPU", "GPU", "core", "thread", "battery", "GC")


def frame_deltas(a, b):
    """Presented-frame deltas from the same source (overlay / MangoHud) in both runs: what the
    goals are usually written in. Ratios make "2x" / "-30 %" goals checkable without arithmetic."""
    src = next((k for k in ("overlay", "mangohud") if a.get(k) and b.get(k)), None)
    if not src:
        return None
    fa, fb = a[src], b[src]
    fps_a, fps_b = fa["count"] / fa["seconds"], fb["count"] / fb["seconds"]
    rows = [("fps mean", fps_a, fps_b, "fps", False, 3.0)]
    for k, lab, floor in (("mean", "frame mean", 0.2), ("p99", "frame p99", 0.5), ("p99_9", "frame p99.9", 2.0), ("max", "frame max", 10.0)):
        rows.append((lab, fa["us"][k] / 1000, fb["us"][k] / 1000, "ms", True, floor))
    rows += [("frame-to-frame jitter", fa["jitter_us"] / 1000, fb["jitter_us"] / 1000, "ms", True, 0.2),
             ("stdev", fa["stdev_us"] / 1000, fb["stdev_us"] / 1000, "ms", True, 0.3),
             ("1%-low fps", fa["fps_1pct_low"], fb["fps_1pct_low"], "fps", False, 3.0),
             ("frames >33 ms", fa["over_33ms"], fb["over_33ms"], "", True, 3)]
    out = []
    for lab, x, y, unit, lower_better, floor in rows:
        d = x - y
        real = abs(d) > 2 * floor
        out.append({"metric": lab, "unit": unit, "run": r(x, 2), "against": r(y, 2), "delta": r(d, 2),
                    "ratio_run_over_against": r(x / y, 2) if y else None,
                    "verdict": (("better" if (d < 0) == lower_better else "worse") if real else "within noise")})
    return {"source": src, "rows": out}


def deltas(s, against):
    """compare.py's metrics (in-game sampler, chunk streamer, GC, utilization) with its absolute
    noise floors; a delta is real past twice the floor. Utilization rows are returned separately:
    by the objective they are only "worse" when the run is below the cap."""
    perf, util = [], []
    for m in METRICS:
        label, _, unit, lower_better, floor = m
        a, b = get(m, s), get(m, against)
        if a is None or b is None:
            continue
        d = a - b
        real = abs(d) > 2 * floor
        row = {"metric": label, "unit": unit, "run": r(a, 2), "against": r(b, 2), "delta": r(d, 2),
               "verdict": (("better" if (d < 0) == lower_better else "worse") if real else "within noise")}
        (util if any(w in label for w in UTIL_WORDS) else perf).append(row)
    return {"performance": perf, "utilization_and_gc": util}


def load_against(spec):
    p = Path(spec)
    if p.suffix == ".json" and p.exists():
        s = json.loads(p.read_text())
        return s, s.get("run", p.stem)
    d = p if p.is_dir() else next(RUNS.glob(f"{spec}*"), None)
    if d is None:
        sys.exit(f"no run matches {spec}")
    return summarize(str(d)), d.name


def judge(run_dir, against_specs, goal, cap=FPS_TARGET, quiet=False):
    s = summarize(str(run_dir))
    c = card(s, cap)
    # path drives (--flag path=): the pilot's own verdict first; a drive that crashed, stalled or left its line is no
    # measurement, and the facts go into the card so the verdict below can say so
    drive = drive_check.judge(str(run_dir), use_jev=True, quiet=True, out_json=str(Path(run_dir) / "drive-check.json"))
    if drive is not None:
        dc = drive["card"]
        c["drive"] = {k: dc[k] for k in ("completed", "failed_checks", "seconds", "top_kmh", "cruise_share", "xte_max_tiles",
                                          "impacts", "unplanned_dips", "stop_for_obstacle_s", "chunk_ahead_s", "streaming_note")}
        if "answers" in drive:
            c["drive"]["verdict"] = drive["answers"]["verdict"]["choice"]
    state = {"objective": OBJECTIVE, "goal": goal, "run": c, "against": []}
    for spec in against_specs:
        a, name = load_against(spec)
        d = deltas(s, a)
        state["against"].append({"name": name, "card": card(a, cap), "presented_frame_deltas": frame_deltas(s, a),
                                 "sampler_and_streamer_deltas": d["performance"], "utilization_and_gc_deltas": d["utilization_and_gc"]})
    questions = {
        "verdict": choice(
            {"question": "Judge `run` against `goal`. Each entry of `against` is a comparison run with deltas (run minus "
                         "comparison) and a noise verdict from measured run-to-run spread; take 'within noise' at face "
                         "value. `presented_frame_deltas` are the fps / frame-time numbers goals are written in, with "
                         "`ratio_run_over_against` for goals phrased as a multiple. Which outcome fits?",
             "note": "Only performance rows decide the verdict. `utilization_and_gc_deltas` describe how busy the "
                     "machine was; by `objective` more utilization is fine or desired, never a regression."},
            {"achieved": "the metrics the goal names moved as the goal asks, beyond noise, and nothing named regressed beyond noise",
             "partial": "some of what the goal asks moved beyond noise in the right direction, but not all of it, or a smaller gain than asked",
             "no_change": "every metric the goal names is within noise of the comparison",
             "regressed": "a metric the goal names, or the frame tail (p99, p99.9, max, frames >33 ms, jitter), is worse beyond noise",
             "invalid": "the run cannot answer the goal: missing logs (`run.facts.missing`), wrong setup for the goal (cap, props, renderer, route), a path drive that was not clean (`run.drive.failed_checks` not empty or `run.drive.verdict` other than valid), or no comparison run when the goal needs one"}),
        "goal_met": noul("Does `run` achieve what `goal` asks for, relative to `against`, taking the deltas' noise verdicts at face value?"),
        "tail_regressed": noul("Is any frame-tail metric (p99, p99.9, max, frames >33 ms, stdev, jitter) in the deltas 'worse' beyond noise?"),
        "setup_matches_goal": noul("Is the run's setup (`run.props`, `run.environment`, `run.facts.cap_fps`, the comparison runs' props) the right one for `goal`? "
                                   "For example an 'uncapped' goal needs uncappedFps in props; a 'storm' goal needs a storm preset in the props of both runs."),
        "hardware_headroom_finding": noul("Per `objective`, is `run` a case worth flagging: below the cap while neither CPU nor GPU is saturated (see `run.facts`)?"),
    }
    log = []
    answers = ask(state, questions, log=log)
    out = {"goal": goal, "state": state, "answers": answers, "typesafe": log[0]}
    (Path(run_dir) / "judge.json").write_text(json.dumps(out, indent=1))
    if not quiet:
        f = c.get("frames", {})
        print(f"== {c['run']}  [{c['props'].replace(chr(10), ' ')}]  source {c['frame_source']}")
        if f:
            print(f"  {f.get('fps_mean')} fps mean, p99 {f.get('p99_ms')} ms, p99.9 {f.get('p99_9_ms')} ms, max {f.get('max_ms')} ms, "
                  f">33 ms {f.get('frames_over_33ms')}, jitter {f.get('jitter_ms')} ms")
        print(f"  machine {c.get('machine')}  facts {c['facts']}")
        for a in state["against"]:
            rows = (a["presented_frame_deltas"] or {}).get("rows", []) + a["sampler_and_streamer_deltas"]
            real = [d for d in rows if d["verdict"] != "within noise"]
            print(f"  vs {a['name']}: " + ("; ".join(f"{d['metric']} {d['run']}{d['unit']} vs {d['against']} ({d['verdict']})" for d in real) or "everything within noise"))
        if drive is not None:
            print("  " + drive_check.line(drive).replace("\n", "\n  "))
        print("jev:")
        print("  " + fmt(answers).replace("\n", "\n  "))
        print(f"  ({log[0]['ms']} ms, {log[0]['model']}; judge.json written)")
    return out


def main(argv):
    run, against, goal, cap, out_json, quiet = None, [], None, FPS_TARGET, None, False
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--against":
            i += 1
            while i < len(argv) and not argv[i].startswith("--"):
                against.append(argv[i]); i += 1
            continue
        if a == "--goal":
            goal = argv[i + 1]; i += 2; continue
        if a == "--cap":
            cap = float(argv[i + 1]); i += 2; continue
        if a == "--json":
            out_json = argv[i + 1]; i += 2; continue
        if a == "--quiet":
            quiet = True; i += 1; continue
        run = a; i += 1
    if not run or not goal:
        sys.exit(__doc__)
    d = Path(run) if Path(run).is_dir() else next(RUNS.glob(f"{run}*"), None)
    if d is None:
        sys.exit(f"no run matches {run}")
    out = judge(d, against, goal, cap, quiet)
    if out_json:
        Path(out_json).write_text(json.dumps(out, indent=1))
    v = out["answers"]["verdict"]
    return 0 if v["choice"] == "achieved" else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
