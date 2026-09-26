#!/usr/bin/env python3
"""Check the daily re-measure runs (labels <bench>-<MMDD>-<n> / <bench>-stock-<n>, dashboards.DAILY_BENCHES) before they
count in the home chart.

  harness/grafana/daily-drives.py [run dirs or globs...]   # default: every daily bench's runs under /tmp/pzopt-daily-* and harness/runs

Bench runs (no drive lines): BAD = route not complete. Drive runs: route status, distance, the largest distance off the line and any speed drop of more than 40 km/h from above
60 km/h, from the console's 1 Hz `harness: drive t=` lines, and the route-mean fps. BAD = not complete, > 10 tiles off
the line or such a drop: its fps is not the drive's (a crash changes the scenery, a parked car renders 750+ fps). A hit
always shows as the drop; the pre-09-22 road follower swings 7-9 tiles through the wide start stretch at full speed
without touching anything, hence 10 rather than the 8 of the 2026-09-23 crash notes. Put the
BAD ones in release-days.json "excluded" with the reason, regenerate the dashboards.
"""
import glob
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]


def check(rd):
    rd = Path(rd)
    c = (rd / "console.txt").read_text(errors="replace") if (rd / "console.txt").exists() else ""
    pts = [(float(t), float(l), float(v)) for t, l, v in
           re.findall(r"harness: drive t=(\d+)s .*?lateral=(-?[\d.]+) .*?speed=(-?[\d.]+)km/h", c)]
    bench = (rd / "pzopt-bench.out").read_text() if (rd / "pzopt-bench.out").exists() else ""
    field = lambda k: (m := re.search(rf"^{k}=(\S+)", bench, re.M)) and m.group(1)  # noqa: E731
    drops = [(a[0], a[2], b[2]) for a, b in zip(pts, pts[1:]) if a[2] > 60 and a[2] - b[2] > 40]
    off = max((abs(p[1]) for p in pts), default=None)
    ok = field("route_status") == "complete" and not drops and (off is None if not pts else off <= 10)
    fps = None
    if (rd / "pzopt-frames.out").exists():
        sys.path.insert(0, str(REPO / "harness"))
        from analyze import summarize
        fps = summarize(rd).get("frames", {}).get("fps_mean")
    return ok, (f"{rd.name:34s} {field('route_status') or '?':9s} dist={(field('vehicle_distance') or '?')[:6]:6s} "
                f"off={off if off is not None else '?':<6} drops={drops[:2]} fps={fps and round(fps, 1)} -> {'OK' if ok else 'BAD'}")


def main(argv):
    prefixes = ("daily", "dstorm", "dspin", "dlou", "dhorde")
    pats = argv or [f"{root}/{p}-*" for p in prefixes for root in ("/tmp/pzopt-daily-*/harness/runs", str(REPO / "harness/runs"))]
    runs = sorted({p for pat in pats for p in glob.glob(pat)}, key=lambda p: Path(p).name)
    bad = 0
    for rd in runs:
        ok, line = check(rd)
        bad += not ok
        print(line)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
