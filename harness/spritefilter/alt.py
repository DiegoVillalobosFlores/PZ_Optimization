#!/usr/bin/env python3
"""GPU cost of a devSpriteFilterAlternate run: pzopt-gpusections.out rows of <name>.on / <name>.off (the filter flips
every N frames, so both halves see the same scene), after a warm-up.

  harness/spritefilter/alt.py <run> [--section composite] [--skip-s 10]

Prints per half: samples, mean / median / p90 GPU us, and the on - off difference with a bootstrap 95 % interval of
the difference of means. Also the other sections' means (bake, bake.end ...) for context.
"""
import argparse
import os
import random
import statistics
from collections import defaultdict


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--section", default="composite")
    ap.add_argument("--skip-s", type=float, default=10.0)
    a = ap.parse_args()
    rows = defaultdict(list)
    events = []  # (begin ns, name, us) for the per-phase attribution
    t0 = None
    with open(os.path.join(a.run, "pzopt-gpusections.out")) as f:
        for line in f:
            if line.startswith("#"):
                continue
            p = line.split()
            if len(p) < 3:
                continue
            t = int(p[0])
            if t0 is None:
                t0 = t
            if (t - t0) / 1e9 < a.skip_s:
                continue
            rows[p[1]].append(int(p[2]) / 1000.0)
            events.append((t, p[1], int(p[2]) / 1000.0))
    subs = sorted(n[len(a.section) + 1:] for n in rows if n.startswith(a.section + "."))
    base = "stock" if "stock" in subs else None
    if base and len(subs) >= 2:
        # devSpriteFilterCycle: every entry against the stock one
        ref = rows[a.section + ".stock"]
        rm = statistics.fmean(ref)
        rnd = random.Random(7)
        print("%-22s %6s %9s %9s %9s  %s" % ("entry", "n", "mean us", "median", "p90", "vs stock (mean, 95 %)"))
        for sub in subs:
            v = rows[a.section + "." + sub]
            s_ = sorted(v)
            d = statistics.fmean(v) - rm
            boots = sorted(statistics.fmean(rnd.choices(v, k=len(v))) - statistics.fmean(rnd.choices(ref, k=len(ref))) for _ in range(300))
            print("%-22s %6d %9.1f %9.1f %9.1f  %+7.1f us (%+.1f .. %+.1f) %+5.1f %%" % (sub, len(v), statistics.fmean(v), s_[len(s_) // 2],
                                                                           s_[int(len(s_) * 0.9)], d, boots[7], boots[292], 100 * d / rm))
    if base and len(subs) >= 2:
        # every section attributed to the cycle entry of the composite before it (the phase that frame ran in):
        # GPU us per frame of the whole feature (bakes, the sharp-mip passes, the per-frame tiles) by phase
        events.sort()
        phase = None
        per = defaultdict(lambda: defaultdict(float))
        frames_in = defaultdict(int)
        for t, name, us in events:
            if name.startswith(a.section + "."):
                phase = name[len(a.section) + 1:]
                frames_in[phase] += 1
            if phase is not None:
                per[phase][name.split(".")[0] if name.startswith(a.section + ".") else name] += us
        names = ["world", "composite", "tiles", "chunks", "bake", "bake.end", "sharpmip", "items", "moving", "translucent"]
        print("GPU us per frame by phase:  " + "  ".join("%9s" % n for n in names))
        for ph in subs:
            n = max(1, frames_in[ph])
            print("  %-24s " % ph + "  ".join("%9.1f" % (per[ph][nm] / n) for nm in names))
    on, off = rows.get(a.section + ".on", []), rows.get(a.section + ".off", [])
    for name, v in ((a.section + ".on", on), (a.section + ".off", off)):
        if v:
            s = sorted(v)
            print("%-16s n=%6d mean=%8.1f us  median=%8.1f  p90=%8.1f" % (name, len(v), statistics.fmean(v), s[len(s) // 2], s[int(len(s) * 0.9)]))
    if on and off:
        d = statistics.fmean(on) - statistics.fmean(off)
        rnd = random.Random(7)
        boots = []
        for _ in range(400):
            mo = statistics.fmean(rnd.choices(on, k=len(on)))
            mf = statistics.fmean(rnd.choices(off, k=len(off)))
            boots.append(mo - mf)
        boots.sort()
        md = sorted(on)[len(on) // 2] - sorted(off)[len(off) // 2]
        print("on - off: mean %+.1f us (95%% %+.1f .. %+.1f), median %+.1f us, %+.1f %% of the off mean"
              % (d, boots[10], boots[389], md, 100 * d / statistics.fmean(off)))
    print("other sections (mean us per event, events):")
    for name in sorted(rows):
        if name.startswith(a.section + "."):
            continue
        v = rows[name]
        print("  %-22s %8.1f  %7d" % (name, statistics.fmean(v), len(v)))


if __name__ == "__main__":
    main()
