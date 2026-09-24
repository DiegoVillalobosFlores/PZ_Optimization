#!/usr/bin/env python3
"""Is the game's audio clean in a recorded run? Code measures, Jev (TypeSafe) judges from the numbers only.

  harness/audio-judge.py <run> [--against <reference run>] [--context "..."] [--json out.json] [--no-jev]

Needs `--record --record-audio game` (the game's own FMOD stream). Windows on the log clock (audio.py's placement):
`settle` (world-ready + 6 s .. route start: the world-entry sting decays and the ambience beds fade in over the first ~5 s,
earlier in the optimized build, which enters the world ~1.5 s sooner, so that transition is not judged as a gap), `route` (the measured window), `exit` (route end .. the end of the game's
audio: what the harness quit sounds like). Measured per window:

  exit         the cutoffs of the exit window: the stock quit (IngameState.exit -> SoundManager.stop) chops whatever still rolls
               (a thunder: 5 of 6 no-fade runs of 2026-09-24); `exit_fade_logged` = the harness faded the mix out first
  cutoffs      a sound stopped mid-play: in one band (sub 20-80, low 80-300, mid 300-2k, presence 2-6k, high 6-16k Hz,
               or the full mix), the 5 ms level falls >= 15 dB within 25 ms from an audible level (50 ms mean >= -40 dBFS)
               and stays >= 12 dB down for the next 75 ms; a burst (>= 12 dB onset in the 300 ms before, 20 ms-smoothed) that
               only falls back to within 6 dB of the bed it rose from is a gunshot or impact decaying, not a cutoff. A natural decay or a reverb tail never does that; a thunder or
               rain bed killed by a stop (FMOD_ChannelGroup_Stop, a stolen voice) does. Calibrated 2026-09-24 on the
               horde + storm + alarms runs: 0 detections in the scene of four runs (alarm beeps included), the thunder
               killed by the harness quit found in all four.
  gaps         an ambience bed that sags and comes back, in the bands that carry steady beds (mid, presence, high: rain,
               wind, alarms; sub and low are thunder, whose lull after a rumble is not a gap): a band's 250 ms level >= 10 dB under its median of the 8 s
               around it for >= 0.5 s, recovering within 6 s (a voice virtualised by FMOD under load, a rain loop that
               stops and restarts); the first 2026-09-24 optimized run's 3 s rain loss is the known example.
  gunshots     with gunshots=R the shots' phase is found from the strongest onsets folded modulo 1/R; a click-like
               transient within 45 ms of a shot is the shot (under the limiter its onset shows no level rise) and is
               reported apart (`transients_on_scripted_gunshots`), not as a click
  dropouts     5 ms holes (< -75 dBFS) inside sound (>= -45 dBFS 100 ms on both sides), clicks (sample steps > 10x the
               local mean step and > 0.05), clipping (samples at full scale), true peak (dBTP), loudness (LUFS)
  reference    with --against: loudness, band shares and per-second level of the route against the reference run
               (e.g. the stock run of the same scene), and the reference's own cutoff / gap counts

Then Jev: `verdict` (clean / cutoff / gap / dropout / distortion / differs_from_reference / inconclusive),
`audio_clean` (probability that nothing in the scene was cut, gapped, dropped or distorted), `exit_clean` (the quit
itself) and `matches_reference`. Exit 0 when the scene is clean (`audio_clean` >= 0.7 and verdict clean);
<run>/audio-judge.json by default.
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import audio  # noqa: E402
from typesafe_client import ask, choice, fmt, noul  # noqa: E402

SR = audio.SR
BANDS = [("sub", 20, 80), ("low", 80, 300), ("mid", 300, 2000), ("presence", 2000, 6000), ("high", 6000, 16000), ("full", 20, 20000)]


def band_signal(x, lo, hi):
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    X[(f < lo) | (f >= hi)] = 0
    return np.fft.irfft(X, len(x))


def levels(y, blk):
    nb = len(y) // blk
    return 20 * np.log10(np.sqrt(np.mean(y[:nb * blk].reshape(nb, blk) ** 2, axis=1)) + 1e-12)


def cutoffs(lv, t0):
    """lv: 5 ms levels. Returns [(route-relative s, level before, level 25 ms later, max over the next 75 ms)].
    The decay of a burst is not a cutoff: a drop within 200 ms of a >= 12 dB onset (a gunshot, an impact: the level rose
    that much within the 300 ms before) is skipped; a rolling thunder or a steady bed that is stopped has no such onset."""
    hits = []
    sm = np.convolve(lv, np.ones(4) / 4, mode="same")  # 20 ms: a 40 Hz rumble swings +-10 dB between 5 ms blocks
    for i in range(60, len(lv) - 20):
        pre = float(np.mean(lv[i - 10:i]))
        if pre < -40 or pre - lv[i + 5] < 15:
            continue
        post = float(np.max(lv[i + 5:i + 20]))
        win = sm[i - 60:i - 2]
        floor = float(np.min(win))  # the band's level before the last burst, 300 ms back (20 ms-smoothed)
        rise = float(np.max(win) - floor)
        if rise >= 12 and post >= floor - 6:
            continue  # a burst (shot, impact, crack) decaying back to the bed it rose from
        if pre - post < 12:
            continue
        t = i * 0.005 - t0
        if hits and t - hits[-1][0] < 0.3:
            continue
        hits.append((round(t, 3), round(pre, 1), round(float(lv[i + 5]), 1), round(post, 1)))
    return hits


def gaps(lv250, t0):
    """lv250: 250 ms levels. A sag >= 10 dB under the 8 s median, >= 0.5 s long, back within 6 s."""
    out, n, i = [], len(lv250), 0
    while i < n:
        a, b = max(0, i - 16), min(n, i + 16)
        med = float(np.median(lv250[a:b]))
        if med > -60 and lv250[i] <= med - 10:
            j = i
            while j < n and lv250[j] <= med - 10:
                j += 1
            dur = (j - i) * 0.25
            if 0.5 <= dur <= 6 and j < n:
                out.append((round(i * 0.25 - t0, 2), round(dur, 2), round(med, 1), round(float(np.min(lv250[i:j])), 1)))
            i = j
        else:
            i += 1
    return out


def click_times(mono):
    """audio.clicks' rule, returning the times (s from the segment start) and step sizes."""
    d = np.abs(np.diff(mono))
    k = SR // 100
    if len(d) < k:
        return []
    local = np.convolve(d, np.ones(k) / k, mode="same")
    w, out, last = SR // 200, [], -SR
    for i in np.nonzero((d > 10 * local) & (d > 0.05))[0]:
        if i < w or i + w >= len(mono) or i - last < w:
            continue
        before = np.sqrt(np.mean(mono[i - w:i] ** 2)) + 1e-9
        after = np.sqrt(np.mean(mono[i + 1:i + 1 + w] ** 2)) + 1e-9
        if abs(20 * np.log10(after / before)) < 6:
            out.append(i / SR)
            last = i
    return out


def shot_phase(mono, rate):
    """The harness fires gunshots=R at exactly R a second: the phase (s, 0..1/R) of the strongest onsets, from the 5 ms
    envelope's rises folded modulo the shot period, or None when no rhythm stands out."""
    period = 1.0 / rate
    lv = levels(mono, SR // 200)
    rise = np.diff(lv)
    idx = np.argsort(rise)[-max(8, int(len(lv) * 0.005 / period)):]
    ang = (idx * 0.005 % period) / period * 2 * np.pi
    c, s_ = float(np.mean(np.cos(ang))), float(np.mean(np.sin(ang)))
    if np.hypot(c, s_) < 0.5:  # onsets not locked to the period
        return None
    return (np.arctan2(s_, c) % (2 * np.pi)) / (2 * np.pi) * period


def windows(run, x):
    rec = Path(run) / "recording.mp4"
    opts, bench = audio.kv(Path(run) / "run.opts"), audio.kv(Path(run) / "pzopt-bench.out")
    rec0 = audio.start_epoch(rec)
    t0 = int(bench["route_start_epoch_ms"]) / 1000 - rec0
    t1 = int(bench["route_end_epoch_ms"]) / 1000 - rec0
    sched = (Path(run) / "schedule.log").read_text() if (Path(run) / "schedule.log").exists() else ""
    import re
    m = re.search(r"world ready (\d+) s after launch", sched)
    ready = (float(opts.get("launch_epoch", rec0)) + int(m.group(1)) - rec0) if m else max(0.0, t0 - 15)
    full = levels(x.mean(axis=1), SR // 20)
    alive = np.nonzero(full > -60)[0]
    end = alive[-1] * 0.05 + 0.05 if len(alive) else t1
    return opts, bench, t0, t1, ready, end


def measure(run):
    rec = Path(run) / "recording.mp4"
    x = audio.decode(rec)
    opts, bench, t0, t1, ready, end = windows(run, x)
    mono = x.mean(axis=1)
    win = {"settle": (ready + 6 - t0, 0.0), "route": (0.0, t1 - t0), "exit": (t1 - t0, end - t0 + 0.2)}

    def which(t):
        for k, (a, b) in win.items():
            if a <= t < b:
                return k
        return "outside"
    cuts = {k: [] for k in win}
    sags = {k: [] for k in win}
    for name, lo, hi in BANDS:
        y = mono if name == "full" else band_signal(mono, lo, hi)
        for h in cutoffs(levels(y, SR // 200), t0):
            w = which(h[0])
            if w in cuts:
                cuts[w].append({"band": name, "t_route_s": h[0], "db_before": h[1], "db_25ms_after": h[2], "db_max_next_75ms": h[3]})
        for g in gaps(levels(y, SR // 4), t0):
            w = which(g[0])
            if w in sags and name in ("mid", "presence", "high"):  # the steady beds (rain, wind, alarms); sub / low are thunder
                sags[w].append({"band": name, "t_route_s": g[0], "seconds": g[1], "median_db": g[2], "min_db": g[3]})
    flags = opts.get("flags", "")
    import re as _re
    mg = _re.search(r"gunshots=([0-9.]+)", flags)
    rate = float(mg.group(1)) if mg else 0.0
    route_mono = mono[int(t0 * SR):int(t1 * SR)]
    phase = shot_phase(route_mono, rate) if rate > 0 else None
    stats = {}
    for k, (a, b) in win.items():
        if k == "exit" or b - a < 1:
            continue
        w = audio.window_stats(x, rec, t0 + a, t0 + b)
        if w and phase is not None:
            # a transient on the scripted gunshot rhythm is the shot itself (under the limiter its onset no longer shows as
            # a level rise, so audio.clicks' rule counts it): only clicks off the rhythm (> 45 ms from a shot: a shot fires on a frame, ~9 ms, and reaches the mix on FMOD's 20 ms update) count
            ct = click_times(mono[int((t0 + a) * SR):int((t0 + b) * SR)])
            period = 1.0 / rate
            off = [t for t in ct if min((t + a - phase) % period, period - (t + a - phase) % period) > 0.045]
            w["clicks_on_gunshots"] = len(ct) - len(off)
            w["clicks"] = len(off)
        if w:
            stats[k] = {"seconds": round(w["secs"], 1), "lufs": w["lufs"], "true_peak_dbtp": w["true_peak"], "clipped_samples": w["clip"],
                        "over_full_scale": w["over"], "dropouts": [{"t_route_s": round(t + a, 3), "ms": round(d * 1000)} for t, d in w["dropouts"]],
                        "clicks": w["clicks"], "transients_on_scripted_gunshots": w.get("clicks_on_gunshots"),
                        "band_shares_db": {kk: round(v, 1) for kk, v in w["bands"].items()},
                        "silent": bool(w["silent"])}
    per_second = [round(r["rms"], 1) for r in audio.per_second(x, t0, t1)]
    console = Path(run) / "console.txt"
    fade = console.exists() and "fading the game's audio out" in console.read_text(errors="replace")
    sound = Path(run) / "pzopt-sound.out"
    scene = {"preset": opts.get("preset", ""), "flags": opts.get("flags", ""), "audio_source": opts.get("record_audio", "desktop"),
             "lightning_strikes": bench.get("lightning_strikes"), "zombies_loaded": bench.get("zombies_loaded"),
             "sound_probe": bench.get("sound_probe", "").strip()}
    if sound.exists():
        last = [l for l in sound.read_text(errors="replace").splitlines() if "phase=route" in l]
        if last:
            d = dict(tok.split("=", 1) for tok in last[-1].split() if "=" in tok)
            scene["at_route_end"] = {k: d.get(k) for k in ("instances", "virtual", "ambiance", "house_alarm", "car_alarm", "gunshots", "world_sounds", "vocal_slots")}
    scene_stats = [stats[k] for k in ("settle", "route") if k in stats]
    summary = {"scene_windows_cutoffs": len(cuts["settle"]) + len(cuts["route"]),
               "scene_windows_ambience_gaps": len(sags["settle"]) + len(sags["route"]),
               "scene_windows_dropouts": sum(len(w["dropouts"]) for w in scene_stats),
               "scene_windows_clicks": sum(w["clicks"] for w in scene_stats),
               "scene_windows_clipped_samples": sum(w["clipped_samples"] for w in scene_stats),
               "scene_windows_max_true_peak_dbtp": max((w["true_peak_dbtp"] for w in scene_stats if w["true_peak_dbtp"] == w["true_peak_dbtp"]), default=None),
               "scene_windows_silent": any(w["silent"] for w in scene_stats),
               "exit_window_cutoffs": len(cuts["exit"]), "exit_fade_logged": fade}
    return {"run": Path(run).name, "summary": summary, "scene": scene, "windows_s_from_route_start": {k: [round(a, 2), round(b, 2)] for k, (a, b) in win.items()},
            "cutoffs": cuts, "ambience_gaps": sags, "stats": stats, "route_level_per_second_dbfs": per_second}


def compare(m, r):
    a, b = r["stats"].get("route"), m["stats"].get("route")
    if not a or not b:
        return None
    pa, pb = r["route_level_per_second_dbfs"], m["route_level_per_second_dbfs"]
    n = min(len(pa), len(pb))
    corr = float(np.corrcoef(pa[:n], pb[:n])[0, 1]) if n >= 3 else None
    return {"reference_run": r["run"], "lufs_delta": round(b["lufs"] - a["lufs"], 2),
            "band_share_delta_db": {k: round(b["band_shares_db"][k] - a["band_shares_db"][k], 1) for k in a["band_shares_db"]},
            "per_second_level_correlation": round(corr, 3) if corr is not None else None,
            "per_second_mean_delta_db": round(float(np.mean(np.array(pb[:n]) - np.array(pa[:n]))), 2) if n else None,
            "reference_cutoffs_in_scene": len(r["cutoffs"]["settle"]) + len(r["cutoffs"]["route"]),
            "reference_gaps_in_scene": len(r["ambience_gaps"]["settle"]) + len(r["ambience_gaps"]["route"]),
            "reference_exit_cutoffs": len(r["cutoffs"]["exit"]),
            "reference_route_clipped_samples": a["clipped_samples"], "reference_route_true_peak_dbtp": a["true_peak_dbtp"],
            "note": ("the reference clips (" + str(a["clipped_samples"]) + " samples at full scale): its loudness is inflated and its "
                     "per-second level pinned near full scale by the distortion, so a lower loudness and a lower per-second correlation "
                     "in `run` with no clipping are that distortion removed, not a mismatch") if a["clipped_samples"] > 100 else ""}


def plain(o):
    """numpy scalars -> Python numbers, NaN / inf -> None: the TypeSafe request is strict JSON."""
    if isinstance(o, dict):
        return {k: plain(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [plain(v) for v in o]
    if isinstance(o, (np.integer,)):
        return int(o)
    if isinstance(o, (float, np.floating)):
        return None if not np.isfinite(o) else round(float(o), 3)
    return o


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("run")
    ap.add_argument("--against")
    ap.add_argument("--context", default="")
    ap.add_argument("--json")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    m = measure(a.run)
    state = {"context": a.context or "Project Zomboid run recorded with the game's own audio stream; the scene flags say what plays "
                                    "(weather=storm: rain + thunder every 6 s; house_alarm / car_alarm; gunshots=R shots a second; "
                                    "helicopter; population=max: a zombie horde).",
             "run": m}
    if a.against:
        r = measure(a.against)
        state["reference"] = compare(m, r)
    for k in ("settle", "route", "exit"):
        s = m["stats"].get(k, {})
        print(f"{k:6s} cutoffs {len(m['cutoffs'][k]):2d} gaps {len(m['ambience_gaps'][k]):2d}"
              + (f"  {s.get('lufs')} LUFS, {s.get('true_peak_dbtp')} dBTP, clip {s.get('clipped_samples')}, dropouts {len(s.get('dropouts', []))}, clicks {s.get('clicks')}" if s else ""))
        for c in m["cutoffs"][k][:6]:
            print(f"         cut {c['band']:8s} at {c['t_route_s']:+.2f} s  {c['db_before']} -> {c['db_25ms_after']} dB")
        for g in m["ambience_gaps"][k][:6]:
            print(f"         gap {g['band']:8s} at {g['t_route_s']:+.2f} s for {g['seconds']} s  (median {g['median_db']}, min {g['min_db']} dB)")
    if state.get("reference"):
        print("reference:", json.dumps(state["reference"]))
    state = plain(state)
    out = {"state": state}
    code = 0
    if not a.no_jev:
        questions = {
            "verdict": choice({"question": "What best describes the game's audio in `run` during the scene (the settle and route windows)?",
                               "note": "Cutoffs and gaps are counted per band by the rules in the context; zero of both, no dropouts, no clicks, "
                                       "no clipping and a true peak under 0 dBTP is clean. The exit window is judged separately."},
                              {"clean": "no sound cut off, no ambience gap, no dropout, no click, no clipping in settle or route",
                               "cutoff": "at least one sound in the scene stops abruptly mid-play (a cutoff in settle or route)",
                               "gap": "an ambience bed (rain, wind, alarm) sags and comes back: a gap in settle or route",
                               "dropout": "holes in the sound (dropouts) in settle or route",
                               "distortion": "clipping, over-full-scale samples, a true peak at or above 0 dBTP, or clicks",
                               "differs_from_reference": "clean on its own but clearly louder, quieter or spectrally different from the reference run of the same scene, beyond the ~1.5 LU two runs of one build differ by, and not explained by `reference.note` (a clipping reference)",
                               "inconclusive": "no game audio, silent windows, desktop audio mixed in, or contradictory numbers"}),
            "audio_clean": noul("Judge only `run.summary.scene_windows_*` (the settle and route windows; the exit window is a separate question): "
                                "is the game's audio in the scene clean, i.e. no cutoff, no ambience gap, no dropout, no click, no clipped sample, "
                                "true peak below 0 dBTP and not silent?"),
            "exit_clean": noul("Does the game's audio end cleanly when the run quits, i.e. is `run.summary.exit_window_cutoffs` zero "
                               "(no sound, typically a rolling thunder, chopped by the quit)?"),
            "matches_reference": noul("If `reference` is present: does the route's audio match the reference run of the same scene (loudness "
                                      "within ~2 LU, band shares within ~3 dB, per-second level correlation above ~0.6; two runs of one build "
                                      "differ by up to ~1.5 LU because the horde is never the same)? When `reference.note` says the reference "
                                      "clips, judge only the band shares (the spectrum), since its loudness and per-second level are the "
                                      "distortion's. If no reference is given, answer yes."),
        }
        log = []
        answers = ask(state, questions, log=log)
        print("jev:")
        print("  " + fmt(answers).replace("\n", "\n  "))
        out.update({"answers": answers, "typesafe": log[0] if log else None})
        code = 0 if answers["audio_clean"]["noul"] >= 0.7 and answers["verdict"]["choice"] == "clean" else 1
        print(f"audio_verdict={answers['verdict']['choice']} audio_clean={answers['audio_clean']['noul']:.2f} "
              f"exit_clean={answers['exit_clean']['noul']:.2f} matches_reference={answers['matches_reference']['noul']:.2f}")
    Path(a.json or (Path(a.run) / "audio-judge.json")).write_text(json.dumps(out, indent=1))
    return code


if __name__ == "__main__":
    sys.exit(main())
