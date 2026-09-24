#!/usr/bin/env python3
"""Was a path drive (run.sh --mode drive --flag path=...) a usable measurement? Reads the run's pzopt-drive.out (the
pilot's 20 Hz telemetry) and the drive_* lines of pzopt-bench.out, computes the facts in code (checks against fixed
limits, speed dips, impacts, stops, chunk stalls; with --against, the difference in line and speed profile per 10
tiles of path) and asks Jev (TypeSafe) for the verdict over those numbers.

    drive_check.py <run> [--against <run>...] [--json out.json] [--no-jev]

Exit 0 = valid (and consistent with every --against run), 1 = not, 2 = no path telemetry in the run.
judge.py adds the card to every run that has pzopt-drive.out, so the queue's result carries it too.
"""
import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

# what a clean drive stays within (tiles, seconds, km/h); the pilot's own simulation keeps straights under 0.2 tiles
LIMITS = {
    "xte_max_tiles": 2.5,            # anywhere, corners included (North Main St leaves ~3 tiles either side)
    "xte_straight_max_tiles": 0.8,   # at cruise, no obstacle being passed
    "off_street_s": 0.25,
    "impacts": 0,
    "unplanned_dips": 0,             # the speed fell 10 km/h within 0.5 s with no brake command / stall / lower plan to explain it
    "stop_for_obstacle_s": 0.0,
    "held_back_s": 1.0,              # under the plan by 15 km/h without braking and without gaining speed
}
# consistency between two drives of the same path (per 10-tile bin of path)
SAME = {"speed_mean_abs_kmh": 4.0, "speed_max_abs_kmh": 20.0, "line_max_abs_tiles": 1.0, "time_diff_s": 2.0}


def read_bench(run):
    out = {}
    f = Path(run) / "pzopt-bench.out"
    if f.exists():
        for line in f.read_text(errors="replace").splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
    return out


def read_rows(run):
    f = Path(run) / "pzopt-drive.out"
    if not f.exists():
        return None
    lines = f.read_text(errors="replace").splitlines()
    head = lines[0].split("\t")
    rows = []
    for line in lines[1:]:
        parts = line.split("\t")
        if len(parts) != len(head):
            continue
        r = {}
        for k, v in zip(head, parts):
            try:
                r[k] = float(v)
            except ValueError:
                r[k] = None
        rows.append(r)
    return rows


def fnum(d, k, default=0.0):
    try:
        return float(d.get(k, default))
    except (TypeError, ValueError):
        return default


def card(run):
    bench, rows = read_bench(run), read_rows(run)
    if not rows:
        return None
    cruise = fnum(bench, "cruise_kmh", 0) or max(r["target_kmh"] for r in rows)
    dt = [b["t"] - a["t"] for a, b in zip(rows, rows[1:])] + [0.05]
    at_cruise = [r["target_kmh"] >= cruise - 1 for r in rows]
    # lane keeping is judged while the car actually cruises (not while it accelerates out of a corner under a cruise plan)
    clean = [c and r["obstacles"] == 0 and abs(r["offset"]) < 0.01 and r["kmh"] >= min(cruise, 120) - 5 for c, r in zip(at_cruise, rows)]
    straight = [abs(r["xte"]) for r, c in zip(rows, clean) if c and r["t"] > 2]
    # a dip: the speed FELL by 10 km/h or more within 0.5 s while nothing asked for it (no brake command in that window,
    # no chunk stall, the plan not below the speed it fell from). Accelerating out of a corner under a cruise plan is
    # below the plan too, but rising: that is not a dip (a town loop has one after every turn)
    dips, dip_list, last_dip_t = 0, [], -10.0
    win = 10  # rows = 0.5 s at 20 Hz
    for i in range(win, len(rows)):
        w = rows[i - win:i + 1]
        top = max(w, key=lambda r: r["kmh"])
        r = rows[i]
        if (top["kmh"] - r["kmh"] >= 10 and not any(x["brake"] or x["chunk_ahead"] for x in w)
                and min(x["target_kmh"] for x in w) >= top["kmh"] - 5 and r["t"] - last_dip_t > 1.0):
            dips += 1
            last_dip_t = r["t"]
            dip_list.append({"t": round(r["t"], 1), "s": round(r["s"]), "kmh": round(r["kmh"]), "from_kmh": round(top["kmh"]), "target_kmh": round(r["target_kmh"])})
    # held back: 15 km/h under the plan, not braking, and not gaining speed (under 2 km/h over the last 0.5 s)
    held = 0.0
    for i in range(win, len(rows)):
        r, r0 = rows[i], rows[i - win]
        if r["kmh"] < r["target_kmh"] - 15 and not r["brake"] and r["kmh"] - r0["kmh"] < 2 and r["t"] > 2:
            held += dt[i]
    cruise_time = sum(d for d, c in zip(dt, at_cruise) if c)
    cruising = sum(d for d, c, r in zip(dt, at_cruise, rows) if c and r["kmh"] >= min(cruise, 120) - 5)
    total = rows[-1]["t"] - rows[0]["t"]
    c = {
        "run": Path(run).name,
        "path": bench.get("drive_path", ""),
        "path_length_tiles": fnum(bench, "drive_path_length"),
        "progress_tiles": round(max(r["s"] for r in rows), 1),
        "completed": bench.get("drive_done") == "true" and bench.get("route_status") == "complete",
        "route_status": bench.get("route_status", "?"),
        "seconds": round(total, 1),
        "cruise_kmh": cruise,
        "top_kmh": round(max(r["kmh"] for r in rows), 1),
        "mean_kmh": round(sum(r["kmh"] * d for r, d in zip(rows, dt)) / max(1e-6, sum(dt)), 1),
        "cruise_share": round(cruising / cruise_time, 3) if cruise_time > 0 else 0.0,
        "xte_rms_tiles": fnum(bench, "drive_xte_rms"),
        "xte_max_tiles": fnum(bench, "drive_xte_max"),
        "xte_straight_max_tiles": round(max(straight), 2) if straight else 0.0,
        "heading_err_max_deg": round(max(abs(r["hdg_err"]) for r in rows if r["t"] > 2), 1) if total > 2 else 0.0,
        "off_street_s": fnum(bench, "drive_off_street_s"),
        "impacts": int(fnum(bench, "drive_impacts")),
        "last_impact": bench.get("drive_last_impact", ""),
        "unplanned_dips": dips,
        "held_back_s": round(held, 2),
        "dips": dip_list[:5],
        "brake_s": fnum(bench, "drive_brake_s"),
        "avoid_s": fnum(bench, "drive_avoid_s"),
        "stop_for_obstacle_s": fnum(bench, "drive_stop_for_obstacle_s"),
        "chunk_ahead_s": fnum(bench, "drive_chunk_ahead_s"),
        "steer_gain_learned": fnum(bench, "drive_steer_gain", 1.0),
    }
    checks = {}
    for k, lim in LIMITS.items():
        checks[k] = {"value": c[k], "limit": f"<= {lim}", "ok": c[k] <= lim}
    checks["completed"] = {"value": c["completed"], "limit": "true", "ok": c["completed"]}
    c["checks"] = checks
    c["failed_checks"] = [k for k, v in checks.items() if not v["ok"]]
    c["streaming_note"] = ("the game braked by itself for chunks not loaded ahead for %.2f s (a chunk-arrival finding, "
                           "not a driving fault)" % c["chunk_ahead_s"]) if c["chunk_ahead_s"] > 0 else "no chunk stall"
    return c


def profile(rows, bin_tiles=10.0):
    """per path bin: (mean km/h, mean lateral offset from the planned line, first time)"""
    bins = {}
    for r in rows:
        b = int(r["s"] // bin_tiles)
        bins.setdefault(b, []).append(r)
    return {b: (sum(x["kmh"] for x in v) / len(v), sum(x["xte"] for x in v) / len(v), v[0]["t"]) for b, v in bins.items()}


def compare(run_rows, ref_rows):
    a, b = profile(run_rows), profile(ref_rows)
    common = sorted(set(a) & set(b))
    if not common:
        return None
    dv = [abs(a[k][0] - b[k][0]) for k in common]
    dl = [abs(a[k][1] - b[k][1]) for k in common]
    last = common[-1]
    d = {"bins": len(common), "speed_mean_abs_kmh": round(sum(dv) / len(dv), 2), "speed_max_abs_kmh": round(max(dv), 1),
         "line_max_abs_tiles": round(max(dl), 2), "time_diff_s": round(abs((a[last][2] - a[common[0]][2]) - (b[last][2] - b[common[0]][2])), 2)}
    d["within"] = {k: d[k] <= lim for k, lim in SAME.items()}
    return d


def judge(run, against=(), use_jev=True, quiet=False, out_json=None):
    c = card(run)
    if c is None:
        if not quiet:
            print(f"drive: no pzopt-drive.out in {run} (not a path drive)")
        return None
    comps = []
    for ref in against:
        rr = read_rows(ref)
        if rr:
            d = compare(read_rows(run), rr)
            if d:
                comps.append({"against": Path(ref).name, "limits": SAME, **d})
    result = {"card": c, "comparisons": comps}
    if use_jev:
        from typesafe_client import ask, choice, noul
        state = {"drive": c, "limits": LIMITS, "comparisons": comps,
                 "context": "A scripted benchmark drive in Project Zomboid: a pilot steers a car along a fixed path at a fixed "
                            "cruise speed; the run's frame times are only comparable with other runs when the drive itself was clean."}
        questions = {
            "verdict": choice("Classify the drive from `drive.checks` / `drive.failed_checks` and the other numbers in `drive`.",
                              {"valid": "completed the path, every check ok: a usable measurement",
                               "left_the_line": "completed, but the car strayed from the planned line beyond the limits (xte, off street)",
                               "crashed": "an impact or an unplanned speed dip: the car hit something",
                               "stalled": "stopped for an obstacle, or did not complete the path (timeout / incomplete)",
                               "slow": "completed cleanly but was held under its planned speed without braking for longer than the limit (held_back_s)"}),
            "usable": noul("Can this run's frame-time numbers be compared with other runs of the same path, i.e. was the drive clean "
                           "(completed, no failed check)? A chunk stall (`drive.chunk_ahead_s`) alone does not make it unusable."),
        }
        if comps:
            questions["consistent"] = noul("Did this drive follow the same line at the same speeds as every drive in `comparisons` "
                                           "(every `within` entry true)?")
        log = []
        ans = ask(state, questions, log=log)
        result["answers"] = ans
        result["typesafe"] = log[0] if log else None
    if out_json:
        Path(out_json).write_text(json.dumps(result, indent=1))
    if not quiet:
        print(line(result))
    return result


def line(result):
    c = result["card"]
    a = result.get("answers", {})
    head = "drive="
    if a:
        v = a["verdict"]
        head += f"{v['choice']} ({v['confidence']:.2f}) usable={a['usable']['noul']:.2f}" + (f" consistent={a['consistent']['noul']:.2f}" if "consistent" in a else "")
    else:
        head += "valid" if not c["failed_checks"] else "failed:" + ",".join(c["failed_checks"])
    s = (f"{head}  {c['progress_tiles']:.0f}/{c['path_length_tiles']:.0f} tiles in {c['seconds']} s, top {c['top_kmh']} km/h, "
         f"cruise share {c['cruise_share']}, xte max {c['xte_max_tiles']} (straight {c['xte_straight_max_tiles']}), off street {c['off_street_s']} s, "
         f"impacts {c['impacts']}, dips {c['unplanned_dips']}, held back {c['held_back_s']} s, avoid {c['avoid_s']} s, stop {c['stop_for_obstacle_s']} s, chunk stall {c['chunk_ahead_s']} s"
         + (f", failed: {', '.join(c['failed_checks'])}" if c["failed_checks"] else ""))
    for d in result.get("comparisons", []):
        s += (f"\n  vs {d['against']}: speed diff mean {d['speed_mean_abs_kmh']} / max {d['speed_max_abs_kmh']} km/h, line diff max "
              f"{d['line_max_abs_tiles']} tiles, time diff {d['time_diff_s']} s" + ("" if all(d["within"].values()) else "  (outside: " + ", ".join(k for k, ok in d["within"].items() if not ok) + ")"))
    return s


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    run, against, out_json, use_jev = argv[0], [], None, True
    i = 1
    while i < len(argv):
        if argv[i] == "--against":
            i += 1
            while i < len(argv) and not argv[i].startswith("--"):
                against.append(argv[i]); i += 1
            continue
        if argv[i] == "--json":
            out_json = argv[i + 1]; i += 2; continue
        if argv[i] == "--no-jev":
            use_jev = False; i += 1; continue
        sys.exit(f"unknown argument {argv[i]}")
    r = judge(run, against, use_jev=use_jev, out_json=out_json or str(Path(run) / "drive-check.json"))
    if r is None:
        return 2
    c = r["card"]
    ok = not c["failed_checks"] and all(all(d["within"].values()) for d in r["comparisons"])
    if "answers" in r:
        ok = ok and r["answers"]["verdict"]["choice"] == "valid"
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
