#!/usr/bin/env python3
"""Render docs/workshop/images/26-clear-audio.gif: the Workshop's "New! Clear audio" card in the animated New! format
(harness/newcard.py). Right half: the text and table of the still card (harness/audio-card.py, same numbers). Left
half: the assault-rifle stress runs of the sound pass, stock above and this release below, as the game's own audio
stream (`--record-audio game`) plays: the last WIN seconds of the waveform with samples at full scale in amber, the
frame's peak on a dBFS meter with a CLIP lamp, and the clipped-sample count so far. The picture of those runs is no
use here (the downtown storm without the spectator view is black under the profiler overlay), so the sound is the
whole left half. Both start the same seconds after their route start (the rifle fires 8 shots a second from there);
the clock is the recording's mtime minus its duration, as in harness/audio.py.

    harness/queue.sh submit media --label clear-audio-gif --out docs/workshop/images/26-clear-audio.gif ... \\
        -- python3 harness/audio-card-gif.py
    (--still <png>: write the first frame only; the layout check)
"""
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from newcard import BG, INK2, MUTED, OPT, RING, RULE, STOCK, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/26-clear-audio.gif"
RUNS = [("STOCK", STOCK, "harness/runs/snd-i6-stress-stock2-20260924-161256"),
        ("THIS RELEASE", OPT, "harness/runs/snd-i6-stress-opt-20260924-160738")]
T0, DUR, FPS = 4.0, 6.0, 12        # seconds after the route start, clip length, GIF frame rate
WIN = 2.0                          # seconds of waveform on screen
CLIP = 0.999                       # a sample at full scale, as harness/audio.py counts it
SR = 48000

INTRO = ("The game mixes 5.1 surround at 32 kHz on every device. On stereo speakers or headphones the system folds the six "
         "channels into two after the game, and under gunfire that sum clipped: crackle on every shot. The game now folds "
         "to stereo itself, ahead of a limiter that keeps the mix under -2 dBFS (5.1 setups keep their surround), and the "
         "per-frame sound upkeep costs a fifth of what it did. A downtown horde of ~2,000 zombies in a thunderstorm, a "
         "house and a car alarm, gunfire.")
ROWS = [
    ("Clipped samples, pistol", "2 shots a second, 25 s of the game's audio, stereo",
     (6161, "6,161"), (0, "0"), "none"),
    ("Clipped samples, assault rifle", "8 shots a second, the same scene, stress case",
     (19113, "19,113"), (0, "0"), "none"),
    ("Loudest peak (true peak)", "above 0 dBTP = distortion on the speakers",
     (0.7, "+0.7 dBTP"), (-0.3, "-0.3 dBTP"), "-1.0 dB"),
    ("Sound code, game thread", "per frame: emitters, ambience, zombie voices, world sounds",
     (1.35, "1.35 ms"), (0.24, "0.24 ms"), "-82 %"),
    ("Jev's verdict on the recording", "audio-judge.py: cutoffs, gaps, dropouts, clicks, clipping",
     (None, "distortion"), (None, "clean"), ""),
]
FOOTER = [
    "Left: the game's own audio in the assault-rifle runs as it plays; amber = samples at full scale (clipped). "
    "Desktop, Linux, RTX 4090, stereo output.",
    "On by default: Options > Optimizations > Sound (audioLimiter, audioLimiterStereoFold, emitterIdleSkip, soundTickHz, "
    "worldSoundCleanupFast, hearingHoist). Stock = the shipped game.",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-sound-2026-09-24.md.",
]


def kv(path):
    return dict(line.strip().split("=", 1) for line in open(path) if "=" in line)


def video_t0(run):
    """Seconds into recording.mp4 of the route start + T0."""
    rec = Path(run) / "recording.mp4"
    dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(rec)],
                               capture_output=True, text=True, check=True).stdout)
    start = rec.stat().st_mtime - dur
    return int(kv(Path(run) / "pzopt-schedule.out")["route_start_epoch_ms"]) / 1000 - start + T0


def decode(rec):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(rec), "-vn", "-ac", "2", "-ar", str(SR), "-f", "f32le", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype="<f4").reshape(-1, 2)


def panes(card):
    """(header y, wave box, meter box) per pane, stacked in the media slot."""
    x, y, w, h = card.media
    gap, head, meter = 40, 46, 38
    ph = (h - gap) // 2
    out = []
    for i in range(2):
        py = y + i * (ph + gap)
        wave_h = ph - head - 14 - meter
        out.append((py + head // 2, (x, py + head, w, wave_h), (x, py + head + wave_h + 14, w, meter)))
    return out


def draw_wave(d, box, audio, i0, i1):
    """The last WIN seconds as a min / max envelope per pixel column; a column holding a sample at full scale in amber."""
    x, y, w, h = box
    d.rectangle((x, y, x + w - 1, y + h - 1), fill=BG)
    mid, amp = y + h / 2, h / 2 - 6
    for fy in (mid - amp, mid + amp):                          # full scale
        d.line((x, fy, x + w - 1, fy), fill=RULE)
    seg = audio[max(0, i0):i1]
    if len(seg) < w:
        return
    cols = seg[: len(seg) // w * w].reshape(w, -1, 2)
    hi, lo = cols.max(axis=(1, 2)), cols.min(axis=(1, 2))
    clipped = (np.abs(cols) >= CLIP).any(axis=(1, 2))
    for c in range(w):
        d.line((x + c, mid - amp * min(1.0, hi[c]), x + c, mid - amp * max(-1.0, lo[c])),
               fill=STOCK if clipped[c] else INK2)
    label = font(18)
    for text, right in ((f"last {WIN:g} s", False), ("full scale", True)):
        lw = label.getlength(text)
        lx = x + w - lw - 8 if right else x + 8
        d.rectangle((lx - 6, mid - amp + 1, lx + lw + 6, mid - amp + 26), fill=BG)
        d.text((lx, mid - amp + 4), text, font=label, fill=MUTED, anchor="lt")


def draw_meter(d, box, frame):
    """Peak of the frame's audio on a -40..0 dBFS bar and a CLIP lamp lit when the frame held a sample at full scale."""
    x, y, w, h = box
    lamp = 86
    bw = w - lamp - 236
    peak = float(np.abs(frame).max()) if len(frame) else 0.0
    dbfs = 20 * np.log10(max(peak, 1e-6))
    clip = bool((np.abs(frame) >= CLIP).any())
    d.rounded_rectangle((x, y + 8, x + bw, y + h - 8), radius=4, fill=BG)
    fill = max(0.0, min(1.0, (dbfs + 40) / 40))
    if fill > 0:
        d.rounded_rectangle((x, y + 8, x + max(4, bw * fill), y + h - 8), radius=4, fill=STOCK if clip else INK2)
    d.text((x + bw + 14, y + h / 2), f"peak {dbfs:+5.1f} dBFS", font=font(20, mono=True), fill=INK2, anchor="lm")
    lx = x + w - lamp
    if clip:
        d.rounded_rectangle((lx, y + 3, x + w, y + h - 3), radius=5, fill=STOCK)
        d.text((lx + lamp / 2, y + h / 2), "CLIP", font=font(19, "bold"), fill=BG, anchor="mm")
    else:
        d.rounded_rectangle((lx, y + 3, x + w, y + h - 3), radius=5, outline=RULE, width=1)
        d.text((lx + lamp / 2, y + h / 2), "CLIP", font=font(19, "bold"), fill=RING, anchor="mm")


def main():
    card = Card("New! Clear audio", "2026-09-24", INTRO, ROWS, FOOTER)
    layout = panes(card)
    still = sys.argv[2] if len(sys.argv) > 2 and sys.argv[1] == "--still" else None
    sides = []
    for (name, colour, run), (hy, wbox, mbox) in zip(RUNS, layout):
        vt = video_t0(run)
        audio = decode(Path(run) / "recording.mp4")
        sides.append((name, colour, vt, audio, hy, wbox, mbox))
        clips = int(np.count_nonzero(np.abs(audio[int(vt * SR):int((vt + DUR) * SR)]) >= CLIP))
        print(f"{name}: route start + {T0:g} s = {vt:.2f} s into the recording, clipped samples in the clip {clips}")

    n = 1 if still else int(DUR * FPS)
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-audio-"))
    head_f, lab_f, num_f = font(22, "semibold"), font(20), font(26, "semibold", mono=True)
    for k in range(n):
        im = card.base()
        d = ImageDraw.Draw(im)
        for name, colour, vt, audio, hy, wbox, mbox in sides:
            t = vt + (k + 1) / FPS
            i1 = int(t * SR)
            count = int(np.count_nonzero(np.abs(audio[int(vt * SR):i1]) >= CLIP))
            d.text((wbox[0], hy), name, font=head_f, fill=colour, anchor="lm")
            d.text((wbox[0] + wbox[2], hy), f"{count:,}", font=num_f, fill=STOCK if count else OPT, anchor="rm")
            d.text((wbox[0] + wbox[2] - num_f.getlength("00,000") - 12, hy), "clipped samples", font=lab_f,
                   fill=MUTED, anchor="rm")
            draw_wave(d, wbox, audio, int((t - WIN) * SR), i1)
            draw_meter(d, mbox, audio[int((t - 1 / FPS) * SR):i1])
        if still:
            im.save(still)
            print(f"wrote {still}")
            return
        im.save(work / f"{k + 1:04d}.png")
    write_gif(str(work), FPS, OUT)
    shutil.rmtree(work)


if __name__ == "__main__":
    main()
