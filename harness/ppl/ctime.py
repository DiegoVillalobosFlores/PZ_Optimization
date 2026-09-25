#!/usr/bin/env python3
"""The chunk composite's GPU time per segment from a devPplTiming run: ctime.py <run>... Segments follow the mode label
("on", "off", "on/m<mask>" with devPplCostAt); the first segment (warm-up: shader caches, clock ramp) and the first
600-frame line after every change are left out. Prints each segment and the per-label means of the rest.
devPplAlternate runs ("composite gpu alt:" lines, two masks flipping every few seconds): the per-line pair, the mean of each
mask over the lines after the first and their difference (drift cancels: both masks share every line's clocks)."""
import re
import sys

def alternate(run):
    pairs = []
    for line in open(run + "/console.txt", errors="replace"):
        m = re.search(r"composite gpu alt: m(\d+) ([\d.]+) us \((\d+)\) m(\d+) ([\d.]+) us \((\d+)\)", line)
        if m:
            pairs.append((m.group(1), float(m.group(2)), m.group(4), float(m.group(5))))
    if not pairs:
        return False
    a, b = pairs[0][0], pairs[0][2]
    print(f"{run}: alt m{a} / m{b}: " + " | ".join(f"{p[1]:.0f}/{p[3]:.0f}" for p in pairs))
    rest = pairs[1:] or pairs
    ma, mb = sum(p[1] for p in rest) / len(rest), sum(p[3] for p in rest) / len(rest)
    d = sorted(p[1] - p[3] for p in rest)
    print(f"   means without the first: m{a} {ma:.1f} us, m{b} {mb:.1f} us, difference {ma - mb:+.1f} us (median {d[len(d) // 2]:+.1f}, range {d[0]:+.0f}..{d[-1]:+.0f})")
    return True


for run in sys.argv[1:]:
    if alternate(run):
        continue
    rows = []
    for line in open(run + "/console.txt", errors="replace"):
        m = re.search(r"pixel light composite gpu: (\S+) ([\d.]+) us \((\d+) frames\)", line)
        if m:
            rows.append((m.group(1), float(m.group(2))))
    segs = []
    for mode, us in rows:
        if not segs or segs[-1][0] != mode:
            segs.append([mode, []])
        else:
            segs[-1][1].append(us)
    out = {}
    for mode, v in segs[1:]:
        if v:
            out.setdefault(mode, []).append(sum(v) / len(v))
    print(f"{run}: segments " + " | ".join(f"{m} {sum(v) / len(v):.0f}" for m, v in segs if v))
    print("   means without the first: " + ", ".join(f"{k} {sum(v) / len(v):.1f} us" for k, v in out.items()))
