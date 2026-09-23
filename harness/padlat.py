#!/usr/bin/env python3
"""Menu responsiveness of a run.sh --pad run: every pad press lined up with the game's handler call and frames.

  harness/padlat.py <run dir> [--presses]

Inputs (all in the run dir): pad.log (pad.py) or pzopt-pad.out (pzopt.VirtualPad, --flag pad=<script>): "press down t=<ms> up=<ms>", "hold ...", "mark <name> t=<ms>"),
console.txt ("[pzopt-pad] <handler> t=<ms> ms=<handler ms> <focus before> -> <focus after>", "[pzopt-pad] slow frame
t=<ms> gap=<ms>") and pzopt-overlay.out (one row per presented frame, frametime + epoch_ms; needs
--prop overlaySampling=true --prop overlayLog=true).

Per section (the script's marks): presses, press -> handler latency (median / max), handler ms (max), the presented
frames from each press to 300 ms after it (p50 / max; ">2x" = frames longer than twice the section's median) and the
section's own frame p50 / p99 / max. --presses prints every press.
"""
import re
import statistics
import sys
from pathlib import Path

DIR = {"up": "onPressUp", "down": "onPressDown", "left": "onPressLeft", "right": "onPressRight"}


def pct(xs, p):
    if not xs:
        return float("nan")
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))]


def main():
    run = Path(sys.argv[1])
    show = "--presses" in sys.argv
    presses, marks = [], []
    padlog = run / "pad.log" if (run / "pad.log").exists() else run / "pzopt-pad.out"  # uinput pad / pzopt.VirtualPad
    for line in padlog.read_text(errors="replace").splitlines():
        m = re.match(r"(press|hold) (\S+) .*?t=(\d+) up=(\d+)", line)
        if m:
            presses.append({"kind": m[1], "name": m[2], "t": int(m[3]), "up": int(m[4])})
            continue
        m = re.match(r"(mark|done) ?(.*?) t=(\d+)", line)
        if m:
            marks.append((int(m[3]), m[2].strip() or m[1]))
    calls, slow = [], []
    for line in (run / "console.txt").read_text(errors="replace").splitlines():
        m = re.search(r"\[pzopt-pad\] (on\w+)(?:\((.*?)\))? id=(\S+) t=(\d+) ms=(\d+) (.*) -> (.*)$", line)
        if m:
            calls.append({"h": m[1], "arg": m[2], "t": int(m[4]), "ms": int(m[5]), "before": m[6], "after": m[7]})
            continue
        m = re.search(r"\[pzopt-pad\] slow frame t=(\d+) gap=(\d+)", line)
        if m:
            slow.append((int(m[1]), int(m[2])))
    frames = []
    ov = run / "pzopt-overlay.out"
    if ov.exists():
        rows = ov.read_text().splitlines()
        head = rows[0].split(",")
        fi, ei = head.index("frametime"), head.index("epoch_ms")
        for r in rows[1:]:
            c = r.split(",")
            try:
                frames.append((int(c[ei]), float(c[fi])))
            except (ValueError, IndexError):
                pass
    print(f"== {run.name}: {len(presses)} presses, {len(calls)} handler calls, {len(slow)} slow menu frames (>=25 ms), "
          f"{len(frames)} presented frames logged")
    if not marks:
        print("no marks in pad.log (the script never ran?)")
        return
    # each press -> the first handler call of the matching kind at or after the press (a button's onPressButton;
    # a hold's first call), within 1 s
    used = set()
    for p in presses:
        want = DIR.get(p["name"])
        for i, c in enumerate(calls):
            if i in used or c["t"] < p["t"] - 5 or c["t"] > p["t"] + 1000:
                continue
            if (want and c["h"] == want) or (not want and c["h"] in ("onPressButton", "onPressButtonNoFocus")):
                used.add(i)
                p["call"] = c
                break
        # frames presented between the press and 300 ms after it (the frame that shows the new focus is among them)
        p["frames"] = [ms for (t, ms) in frames if p["t"] <= t <= p["t"] + 300]
        if p["kind"] == "hold":
            p["repeats"] = [c for c in calls if (want and c["h"] == want) and p["t"] - 5 <= c["t"] <= p["up"] + 20]
    bounds = marks + [(10 ** 15, "end")]
    print(f"{'section':<22}{'press':>6}{'lat p50':>9}{'lat max':>9}{'hdl max':>9}{'pf p50':>8}{'pf max':>8}"
          f"{'sec p50':>9}{'p99':>7}{'max':>7}{'>2x':>5}{'slow':>6}")
    for (t0, name), (t1, _) in zip(bounds, bounds[1:]):
        ps = [p for p in presses if t0 <= p["t"] < t1]
        fs = [ms for (t, ms) in frames if t0 <= t < t1]
        if not ps and not fs:
            continue
        lat = [p["call"]["t"] - p["t"] for p in ps if "call" in p]
        hdl = [p["call"]["ms"] for p in ps if "call" in p]
        for p in ps:
            hdl += [c["ms"] for c in p.get("repeats", [])]
        pf = [ms for p in ps for ms in p["frames"]]
        med = statistics.median(fs) if fs else 0
        over = sum(1 for ms in fs if med and ms > 2 * med)
        nslow = sum(1 for (t, g) in slow if t0 <= t < t1)
        f = lambda v: f"{v:.1f}" if v == v else "-"
        print(f"{name[:21]:<22}{len(ps):>6}{f(statistics.median(lat)) if lat else '-':>9}{f(max(lat)) if lat else '-':>9}"
              f"{f(max(hdl)) if hdl else '-':>9}{f(statistics.median(pf)) if pf else '-':>8}{f(max(pf)) if pf else '-':>8}"
              f"{f(med):>9}{f(pct(fs, 99)):>7}{f(max(fs)) if fs else '-':>7}{over:>5}{nslow:>6}")
    missing = [p for p in presses if "call" not in p]
    if missing:
        print(f"presses without a handler call within 1 s: {len(missing)} "
              + " ".join(f"{p['name']}@{p['t'] - marks[0][0]}ms" for p in missing[:12]))
    holds = [p for p in presses if p["kind"] == "hold"]
    for p in holds:
        ts = [c["t"] for c in p["repeats"]]
        gaps = [b - a for a, b in zip(ts, ts[1:])]
        print(f"hold {p['name']} {(p['up'] - p['t']) / 1000:.1f} s: {len(ts)} steps, gaps ms "
              + " ".join(str(g) for g in gaps[:40]))
    if slow:
        top = sorted(slow, key=lambda s: -s[1])[:12]
        print("slowest menu frames (game thread, OnFETick gap): " + ", ".join(
            f"{g} ms @{(t - marks[0][0]) / 1000:.1f}s[{section_of(t, bounds)}]" for t, g in top))
    if show:
        for p in presses:
            c = p.get("call")
            print(f"  {(p['t'] - marks[0][0]) / 1000:7.2f}s {p['kind']:5} {p['name']:6} "
                  + (f"lat {c['t'] - p['t']:4d} ms  hdl {c['ms']:3d} ms  {c['before']} -> {c['after']}" if c else "no call")
                  + (f"  frames max {max(p['frames']):.1f}" if p["frames"] else ""))


def section_of(t, bounds):
    name = "pre"
    for (t0, n) in bounds:
        if t >= t0:
            name = n
    return name


if __name__ == "__main__":
    main()
