#!/usr/bin/env python3
"""Split a run's per-frame times by a dev on/off alternation (devSunAlternate) read from the console.

usage: harness/contact/alt.py <run dir> [--skip S]
The console line 'alternating every P ms from epoch_ms T0 (on first)' gives the clock; frames of
pzopt-overlay.out (epoch_ms) fall in on / off periods. Prints medians and means of gpu_ms and frametime
per state, from the frames of whole periods only, skipping the first S seconds (default 20) and the
first 10 % of each period (the switch itself)."""
import re, sys, statistics as st
run = sys.argv[1]
skip = float(sys.argv[sys.argv.index('--skip') + 1]) if '--skip' in sys.argv else 20.0
con = open(f"{run}/console.txt", errors="replace").read()
m = re.search(r"alternating every (\d+) ms from epoch_ms (\d+)", con)
if not m:
    sys.exit("no alternation line in console.txt")
P, T0 = int(m.group(1)), int(m.group(2))
rows = open(f"{run}/pzopt-overlay.out").read().split("\n")
hdr = rows[0].split(",")
ix = {k: i for i, k in enumerate(hdr)}
data = {"on": {"gpu": [], "ft": []}, "off": {"gpu": [], "ft": []}}
for r in rows[1:]:
    c = r.split(",")
    if len(c) < len(hdr):
        continue
    try:
        e = int(float(c[ix["epoch_ms"]]))
        g = float(c[ix["gpu_ms"]]); ft = float(c[ix["frametime"]])
    except ValueError:
        continue
    dt = e - T0
    if dt < skip * 1000 or dt < 0:
        continue
    if (dt % P) < 0.1 * P:
        continue
    k = "on" if (dt // P) % 2 == 0 else "off"
    data[k]["gpu"].append(g); data[k]["ft"].append(ft)
for k in ("on", "off"):
    g, ft = data[k]["gpu"], data[k]["ft"]
    if not g:
        print(k, "no frames"); continue
    print(f"{k:3s} frames={len(g):6d} gpu_ms median={st.median(g):.4f} mean={st.mean(g):.4f} | frametime median={st.median(ft):.3f} mean={st.mean(ft):.3f}")
if data["on"]["gpu"] and data["off"]["gpu"]:
    d = (st.median(data["on"]["gpu"]) - st.median(data["off"]["gpu"])) * 1000
    dm = (st.mean(data["on"]["gpu"]) - st.mean(data["off"]["gpu"])) * 1000
    print(f"on - off: gpu median {d:+.1f} us, mean {dm:+.1f} us")
