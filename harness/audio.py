#!/usr/bin/env python3
"""What the game sounded like during a recorded run: loudness, peaks, dropouts, spectrum, alarm onset.

  harness/audio.py <run-dir> [<run-dir> ...] [--pre 10] [--no-png]

Needs a run recorded with `--record --record-audio game` (only the game's FMOD stream; a plain `--record`
has every sound the desktop played mixed in and is flagged). The audio track is decoded to 48 kHz float,
placed on the log clock at the recording's mtime minus its duration (the recorder stops ~0.1-0.5 s after the
last frame; the onset line below checks it),
and cut into two windows: `settle` (world-ready + 2 s .. route start: the scene without the route-start
events) and `route` (route_start_epoch_ms .. route_end_epoch_ms of pzopt-bench.out). Per window:

  loudness   EBU R128 integrated (LUFS), loudness range (LU), true peak (dBTP) - ffmpeg ebur128
  level      RMS and sample peak (dBFS), crest factor, samples at or above full scale (clipping)
  stereo     L-R balance (dB) and L/R correlation
  spectrum   energy per band: sub <80 Hz, low 80-300, mid 300-2k, presence 2k-6k, high 6k-16k (dB re. the total)
  dropouts   5 ms blocks below -75 dBFS with 100 ms of sound at -45 dBFS or more on both sides (an FMOD
             buffer starve or a stream restart; opus never codes a loud-quiet-loud 5 ms hole)
  clicks     sample steps above 10x the local 10 ms mean step and above 0.05 (a discontinuity)
  onset      the biggest 50 ms loudness jump in the first 3 s of the route (the alarms start at the route start
             with house_alarm= / car_alarm=; its offset from route start checks the clock placement)

Then a per-second table over the route (short-term loudness, band shares, and the pzopt-sound.out census of that
second when the run has it: sound instances, zombie vocal slots, frame time), and <run>/audio-timeline.png (small
multiples, one measure per panel, every run in its own colour) plus <run>/audio-spectrogram.png (ffmpeg
showspectrumpic, route window with the pre-roll). Two or more runs: a side-by-side table and the per-band
difference of the route window against the first run.
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

import numpy as np

SR = 48000
BANDS = [("sub", 20, 80), ("low", 80, 300), ("mid", 300, 2000), ("presence", 2000, 6000), ("high", 6000, 16000)]
COLORS = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100"]  # dataviz reference palette, fixed order


def kv(path):
    out = {}
    if path.exists():
        for line in path.read_text(errors="replace").splitlines():
            if "=" in line and not line.startswith("#"):
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
    return out


def start_epoch(p):
    """Epoch second of the recording's first sample: its mtime (written when run.sh stops the recorder) minus the
    container duration. Survives copies that keep mtimes (cp -a, rsync -t), unlike the birth time."""
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(p)],
                       capture_output=True, text=True)
    try:
        return p.stat().st_mtime - float(r.stdout.strip())
    except ValueError:
        return None


def decode(p):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(p), "-vn", "-ac", "2", "-ar", str(SR), "-f", "f32le", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype="<f4").reshape(-1, 2)


def db(x, floor=-120.0):
    return float(max(floor, 10 * np.log10(max(x, 1e-30))))


def ebur128(rec, t0, dur):
    r = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-ss", f"{t0:.3f}", "-t", f"{dur:.3f}", "-i", str(rec), "-vn",
                        "-af", "ebur128=peak=true:framelog=quiet", "-f", "null", "-"], capture_output=True, text=True)
    txt = r.stderr[r.stderr.rfind("Summary:"):]
    get = lambda pat: (float(m.group(1)) if (m := re.search(pat, txt)) else float("nan"))
    return get(r"I:\s+(-?[\d.]+|-inf) LUFS"), get(r"LRA:\s+(-?[\d.]+) LU"), get(r"Peak:\s+(-?[\d.]+|-inf) dBFS")


def band_db(x):
    """Welch-averaged power per band (dB re. the 20 Hz-16 kHz total) of a mono float signal."""
    n = 8192
    if len(x) < n:
        return {b[0]: float("nan") for b in BANDS}
    win = np.hanning(n)
    segs = [x[i:i + n] * win for i in range(0, len(x) - n, n // 2)]
    psd = np.mean([np.abs(np.fft.rfft(s)) ** 2 for s in segs], axis=0)
    f = np.fft.rfftfreq(n, 1 / SR)
    total = psd[(f >= 20) & (f < 16000)].sum()
    return {name: db(psd[(f >= lo) & (f < hi)].sum() / total) for name, lo, hi in BANDS}


def dropouts(mono):
    blk = SR // 200  # 5 ms
    nb = len(mono) // blk
    if nb < 60:
        return []
    r = np.sqrt(np.mean(mono[:nb * blk].reshape(nb, blk) ** 2, axis=1) + 1e-30)
    lvl = 20 * np.log10(r)
    ctx = 20  # 100 ms of 5 ms blocks
    out = []
    for i in np.nonzero(lvl < -75)[0]:
        if i < ctx or i + ctx >= nb:
            continue
        before = 20 * np.log10(np.sqrt(np.mean(r[i - ctx:i] ** 2)))
        after = 20 * np.log10(np.sqrt(np.mean(r[i + 1:i + 1 + ctx] ** 2)))
        if before >= -45 and after >= -45:
            out.append(i * blk / SR)
    merged = []
    for t in out:  # adjacent quiet blocks are one gap
        if merged and t - merged[-1][1] <= 0.0051:
            merged[-1][1] = t
        else:
            merged.append([t, t])
    return [(a, b - a + 0.005) for a, b in merged]


def clicks(mono):
    """Discontinuities that start no sound: a sample step > 10x the local 10 ms mean step and > 0.05 whose following
    5 ms is within 6 dB of the preceding 5 ms (a gunshot or impact onset raises the level after it: not a click)."""
    d = np.abs(np.diff(mono))
    k = SR // 100
    if len(d) < k:
        return 0
    local = np.convolve(d, np.ones(k) / k, mode="same")
    idx = np.nonzero((d > 10 * local) & (d > 0.05))[0]
    w = SR // 200
    n = 0
    last = -SR
    for i in idx:
        if i < w or i + w >= len(mono) or i - last < w:
            continue
        before = np.sqrt(np.mean(mono[i - w:i] ** 2)) + 1e-9
        after = np.sqrt(np.mean(mono[i + 1:i + 1 + w] ** 2)) + 1e-9
        if abs(20 * np.log10(after / before)) < 6:
            n += 1
            last = i
    return n


def window_stats(x, rec, t0, t1):
    seg = x[int(t0 * SR):int(t1 * SR)]
    if len(seg) < SR // 2:
        return None
    mono = seg.mean(axis=1)
    rms_l, rms_r = np.sqrt(np.mean(seg[:, 0] ** 2)), np.sqrt(np.mean(seg[:, 1] ** 2))
    rms = np.sqrt(np.mean(seg ** 2))
    peak = float(np.max(np.abs(seg)))
    corr = float(np.corrcoef(seg[:, 0], seg[:, 1])[0, 1]) if rms_l > 0 and rms_r > 0 else float("nan")
    i, lra, tp = ebur128(rec, t0, t1 - t0)
    return {
        "secs": (t1 - t0), "lufs": i, "lra": lra, "true_peak": tp,
        "rms": 20 * np.log10(max(rms, 1e-12)), "peak": 20 * np.log10(max(peak, 1e-12)),
        "crest": 20 * np.log10(max(peak, 1e-12) / max(rms, 1e-12)),
        "clip": int(np.count_nonzero(np.abs(seg) >= 0.999)), "over": int(np.count_nonzero(np.abs(seg) > 1.0)),
        "balance": 20 * np.log10(max(rms_l, 1e-12) / max(rms_r, 1e-12)), "corr": corr,
        "bands": band_db(mono), "dropouts": dropouts(mono), "clicks": clicks(mono),
        "silent": rms < 10 ** (-70 / 20),
    }


def onset(x, t0):
    blk = SR // 20  # 50 ms
    a = int(max(0, t0 - 1) * SR)
    seg = x[a:a + 4 * SR].mean(axis=1)
    nb = len(seg) // blk
    if nb < 4:
        return None
    lv = 20 * np.log10(np.sqrt(np.mean(seg[:nb * blk].reshape(nb, blk) ** 2, axis=1)) + 1e-12)
    j = int(np.argmax(np.diff(lv))) + 1
    return a / SR + j * blk / SR - t0, float(lv[j] - lv[j - 1])


def per_second(x, t0, t1):
    rows = []
    for s in range(int(np.floor(t1 - t0))):
        seg = x[int((t0 + s) * SR):int((t0 + s + 1) * SR)]
        if len(seg) < SR // 2:
            break
        mono = seg.mean(axis=1)
        rows.append({"s": s, "rms": 20 * np.log10(np.sqrt(np.mean(seg ** 2)) + 1e-12), "bands": band_db(mono)})
    return rows


def census(run):
    """pzopt-sound.out route lines by whole second: {second: {key: value}}."""
    p = Path(run) / "pzopt-sound.out"
    out = {}
    if not p.exists():
        return out
    for line in p.read_text(errors="replace").splitlines():
        if not line.startswith("t=") or "phase=route" not in line:
            continue
        d = dict(tok.split("=", 1) for tok in line.split() if "=" in tok)
        try:
            out[int(float(d["t"]))] = d
        except (KeyError, ValueError):
            pass
    return out


def analyse(run, pre, png):
    run = Path(run)
    rec = run / "recording.mp4"
    if not rec.exists():
        sys.exit(f"{rec} not found: record with --record --record-audio game")
    opts, bench = kv(run / "run.opts"), kv(run / "pzopt-bench.out")
    if "route_start_epoch_ms" not in bench:
        sys.exit(f"{run}: no route_start_epoch_ms in pzopt-bench.out")
    rec0 = start_epoch(rec)
    clock = "mtime - duration"
    if rec0 is None:
        rec0, clock = float(opts.get("launch_epoch", "0")), "launch_epoch (whole second)"
    rs, re_ = int(bench["route_start_epoch_ms"]) / 1000, int(bench["route_end_epoch_ms"]) / 1000
    t0, t1 = rs - rec0, re_ - rec0
    sched = (run / "schedule.log").read_text() if (run / "schedule.log").exists() else ""
    m = re.search(r"world ready (\d+) s after launch", sched)
    ready = (float(opts.get("launch_epoch", rec0)) + int(m.group(1)) - rec0) if m else max(0.0, t0 - pre)
    x = decode(rec)
    res = {
        "run": run.name, "audio_src": opts.get("record_audio", "desktop"), "clock": clock, "t0": t0, "t1": t1,
        "settle": window_stats(x, rec, ready + 2, t0) if t0 - ready > 3 else None,
        "route": window_stats(x, rec, t0, t1), "onset": onset(x, t0), "seconds": per_second(x, t0, t1),
        "census": census(run), "x": x, "rec": rec, "pre": pre,
        "flags": opts.get("flags", ""), "preset": opts.get("preset", ""),
    }
    if png:
        a = max(0.0, t0 - pre)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{a:.3f}", "-t", f"{t1 - a + 1:.3f}", "-i", str(rec), "-vn",
                        "-lavfi", "showspectrumpic=s=1600x512:mode=combined:scale=log:fscale=log:legend=1:stop=16000",
                        str(run / "audio-spectrogram.png")], check=False)
    return res


def fmt_window(name, w):
    if w is None:
        return f"  {name:<7} (too short)"
    b = " ".join(f"{k} {v:+.1f}" for k, v in w["bands"].items())
    drops = w["dropouts"]
    dtxt = f"{len(drops)}" + (" at " + ", ".join(f"{t:.2f}s/{d * 1000:.0f}ms" for t, d in drops[:6]) if drops else "")
    return (f"  {name:<7} {w['secs']:5.1f} s  {w['lufs']:6.1f} LUFS  LRA {w['lra']:4.1f}  true peak {w['true_peak']:+5.1f} dBTP  "
            f"rms {w['rms']:6.1f} peak {w['peak']:+5.1f} dBFS crest {w['crest']:4.1f} dB  clip {w['clip']} (>1.0: {w['over']})  "
            f"L-R {w['balance']:+.1f} dB corr {w['corr']:.2f}\n"
            f"          bands (dB re. total): {b}\n"
            f"          dropouts {dtxt}  clicks {w['clicks']}" + ("  SILENT (no game audio in this window)" if w["silent"] else ""))


def print_run(r):
    print(f"{r['run']}  (audio: {r['audio_src']}, clock: {r['clock']}, route {r['t0']:.2f}-{r['t1']:.2f} s into the recording)")
    if r["audio_src"] != "game":
        print("  WARNING: desktop audio - everything else the desktop played is mixed in; re-record with --record-audio game")
    print(fmt_window("settle", r["settle"]))
    print(fmt_window("route", r["route"]))
    if r["onset"]:
        off, jump = r["onset"]
        print(f"  onset   biggest 50 ms rise {jump:+.1f} dB at route {off:+.2f} s")
    cen = r["census"]
    print("  per second: s  rms dBFS  sub/low/mid/pres/high dB" + ("  | instances active vocal_slots world_sounds frame_ms house car" if cen else ""))
    for row in r["seconds"]:
        b = "/".join(f"{v:+.0f}" for v in row["bands"].values())
        line = f"    {row['s']:3d} {row['rms']:7.1f}  {b}"
        c = cen.get(row["s"])
        if c:
            line += (f"  | {c.get('instances', '?'):>5} {c.get('active', '?'):>5} {c.get('vocal_slots', '?'):>3} {c.get('world_sounds', '?'):>4} "
                     f"{c.get('frame_ms', '?'):>6} {c.get('house_alarm', '-')} {c.get('car_alarm', '-')}")
        print(line)


def compare(runs):
    base = runs[0]
    print("\ncomparison, route window (first run is the reference):")
    head = f"  {'run':<44} {'LUFS':>6} {'LRA':>5} {'dBTP':>6} {'rms':>6} {'clip':>5} {'drops':>5} {'clicks':>6}  " + " ".join(f"{b[0]:>8}" for b in BANDS)
    print(head)
    for r in runs:
        w = r["route"]
        if w is None:
            continue
        bands = " ".join(f"{w['bands'][b[0]] - (base['route']['bands'][b[0]] if r is not base else 0):+8.1f}" for b in BANDS)
        print(f"  {r['run'][:44]:<44} {w['lufs']:6.1f} {w['lra']:5.1f} {w['true_peak']:+6.1f} {w['rms']:6.1f} {w['clip']:5d} {len(w['dropouts']):5d} {w['clicks']:6d}  {bands}")
    print("  (band columns: the first run's shares, then each run's difference from it)")
    if len(runs) >= 2 and runs[0]["seconds"] and runs[1]["seconds"]:
        a = np.array([s["rms"] for s in runs[0]["seconds"]])
        b = np.array([s["rms"] for s in runs[1]["seconds"]])
        n = min(len(a), len(b))
        if n >= 3:
            print(f"  per-second level: correlation {np.corrcoef(a[:n], b[:n])[0, 1]:.2f}, mean difference {np.mean(b[:n] - a[:n]):+.1f} dB over {n} s")


def timeline_png(runs):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    panels = [("level, dBFS (100 ms RMS)", None), ("sound instances (census)", "instances"),
              ("zombie vocal slots in use", "vocal_slots"), ("frame time, ms", "frame_ms")]
    has_census = any(r["census"] for r in runs)
    if not has_census:
        panels = panels[:1]
    fig, axes = plt.subplots(len(panels), 1, figsize=(11, 2.1 * len(panels) + 0.6), sharex=True, facecolor="#fcfcfb")
    axes = np.atleast_1d(axes)
    for ax, (title, key) in zip(axes, panels):
        ax.set_facecolor("#fcfcfb")
        ax.set_title(title, loc="left", fontsize=10, color="#3d3d3a")
        ax.grid(axis="y", color="#e4e3dc", linewidth=0.8)
        for s in ("top", "right"):
            ax.spines[s].set_visible(False)
        for s in ("left", "bottom"):
            ax.spines[s].set_color("#c3c2b7")
        ax.tick_params(colors="#6b6a63", labelsize=8)
        ax.axvline(0, color="#9a9990", linewidth=1, linestyle=":")
        for i, r in enumerate(runs):
            c = COLORS[i % len(COLORS)]
            if key is None:
                x = r["x"].mean(axis=1)
                blk = SR // 10
                a = int(max(0.0, r["t0"] - r["pre"]) * SR)
                b = int(r["t1"] * SR)
                seg = x[a:b]
                nb = len(seg) // blk
                lv = 20 * np.log10(np.sqrt(np.mean(seg[:nb * blk].reshape(nb, blk) ** 2, axis=1)) + 1e-12)
                t = a / SR - r["t0"] + np.arange(nb) * blk / SR
                ax.plot(t, lv, color=c, linewidth=1.5, label=r["run"])
            else:
                pts = sorted((s, float(d[key])) for s, d in r["census"].items() if key in d)
                if pts:
                    ax.plot([p[0] + 0.5 for p in pts], [p[1] for p in pts], color=c, linewidth=2, marker="o", markersize=4, label=r["run"])
    axes[-1].set_xlabel("seconds from route start (dotted line); the alarms start there", fontsize=9, color="#6b6a63")
    if len(runs) > 1:
        axes[0].legend(loc="lower right", fontsize=8, frameon=False)
    fig.tight_layout()
    out = Path(runs[0]["rec"]).parent / "audio-timeline.png"
    fig.savefig(out, dpi=110)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--pre", type=float, default=10.0, help="seconds before the route start in the spectrogram / timeline")
    ap.add_argument("--no-png", action="store_true")
    a = ap.parse_args()
    runs = [analyse(r, a.pre, not a.no_png) for r in a.runs]
    for r in runs:
        print_run(r)
        print()
    if len(runs) > 1:
        compare(runs)
    if not a.no_png:
        print(f"\ntimeline: {timeline_png(runs)}; spectrograms: <run>/audio-spectrogram.png")


if __name__ == "__main__":
    main()
