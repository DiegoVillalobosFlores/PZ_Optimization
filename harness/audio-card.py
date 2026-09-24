#!/usr/bin/env python3
"""Render docs/media/clear-audio.png: the sound pass of 2026-09-24 (docs/findings-sound-2026-09-24.md), stock vs this
release in the horde + thunderstorm + alarms + gunfire scene. Same media style as harness/power-table.py
(docs/media-style.md): near-black surface, Noto Sans, numbers in Noto Sans Mono, stock in amber, the new build in green.
The image carries every word of the Workshop's "New! Clear audio" section.

    python3 harness/audio-card.py
    ffmpeg -y -i docs/media/clear-audio.png -vf scale=1920:-1 -q:v 3 docs/workshop/images/26-clear-audio.jpg
"""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

OUT = "docs/media/clear-audio.png"
DATE = "2026-09-24"
BG, PANEL, RULE, INK, INK2, MUTED = "#0b0b0e", "#111114", "#26262c", "#f0f0f4", "#b4b4b8", "#8a8a90"
STOCK, OPT = "#c9592b", "#44de7c"
plt.rcParams["font.family"] = ["Noto Sans", "DejaVu Sans", "sans-serif"]
MONO = "Noto Sans Mono"

# measurement, detail line, (stock value, label), (new value, label), change column; bars scale per row
ROWS = [
    ("Clipped samples, pistol twice a second", "25 s of the game's own audio stream, stereo output",
     (6161, "6,161"), (0, "0"), "none"),
    ("Clipped samples, assault rifle 8 shots a second", "the same scene, stress case",
     (19113, "19,113"), (0, "0"), "none"),
    ("Loudest peak (true peak)", "above 0 dBTP = distortion on the speakers",
     (0.7, "+0.7 dBTP"), (-0.3, "-0.3 dBTP"), "-1.0 dB"),
    ("Sound code on the game thread, per frame", "emitters, ambience, zombie voices, world sounds",
     (1.35, "1.35 ms"), (0.24, "0.24 ms"), "-82 %"),
    ("Jev's verdict on the recording", "harness/audio-judge.py: cutoffs, gaps, dropouts, clicks, clipping",
     (None, "distortion"), (None, "clean"), ""),
]

W, ROW_H, HEAD_H, TOP, BOT, PAD = 2100, 96, 70, 236, 150, 40
H = TOP + HEAD_H + ROW_H * len(ROWS) + BOT + PAD
fig = plt.figure(figsize=(W / 100, H / 100), dpi=100)
fig.patch.set_facecolor(BG)
ax = fig.add_axes([0, 0, 1, 1]); ax.set_xlim(0, W); ax.set_ylim(H, 0); ax.axis("off")
ax.add_patch(FancyBboxPatch((PAD, PAD), W - 2 * PAD, H - 2 * PAD, boxstyle="round,pad=0,rounding_size=10",
                            facecolor=PANEL, edgecolor="#505058", linewidth=0.8))


def text(x, y, s, size=15, color=INK, weight="normal", ha="left", va="center", family=None):
    ax.text(x, y, s, fontsize=size, color=color, fontweight=weight, ha=ha, va=va, family=family)


X0 = PAD + 48
text(X0, 86, "New! Clear audio", 30, OPT, "bold")
text(W - X0, 86, DATE, 16, MUTED, ha="right", family=MONO)
text(X0, 134, "The game mixes 5.1 surround at 32 kHz on every device. On stereo speakers or headphones the system folds the six "
     "channels into two after the game, and under gunfire", 14, INK2)
text(X0, 158, "that sum clipped: crackle on every shot. The game now folds to stereo itself, ahead of a limiter that keeps the mix "
     "under -2 dBFS (5.1 setups keep their surround),", 14, INK2)
text(X0, 182, "and the per-frame sound upkeep costs a fifth of what it did. A downtown horde of ~2,000 zombies in a thunderstorm, "
     "a house and a car alarm, gunfire.", 14, INK2)

COLS = [(X0 + 860, "STOCK", STOCK), (X0 + 1330, "THIS RELEASE", OPT)]
BAR_W, VAL_X = 170, 186
y = TOP
text(X0, y + 30, "MEASUREMENT", 12.5, MUTED, "semibold")
for xr, name, col in COLS:
    text(xr, y + 30, name, 12.5, col, "semibold")
text(W - X0, y + 30, "CHANGE", 12.5, MUTED, "semibold", ha="right")
y += HEAD_H
ax.plot([X0, W - X0], [y - 10, y - 10], color="#505058", lw=1)

for label, detail, s, n, change in ROWS:
    cy = y + ROW_H / 2
    text(X0, cy - 14, label, 16, INK, "semibold")
    text(X0, cy + 16, detail, 12.5, MUTED)
    top = max(abs(s[0]), abs(n[0])) if s[0] is not None else None
    for (xr, _, colour), (val, shown) in zip(COLS, (s, n)):
        x = xr
        if top:
            w = max(3.0, BAR_W * abs(val) / top) if val else 3.0
            ax.add_patch(FancyBboxPatch((xr, cy - 11), w, 22, boxstyle="round,pad=0,rounding_size=4",
                                        facecolor=colour, edgecolor="none"))
            x = xr + VAL_X
        text(x, cy, shown, 19, colour, "semibold" if colour == OPT else "normal", family=MONO)
    if change:
        text(W - X0, cy, change, 17, OPT, "semibold", ha="right", family=MONO)
    y += ROW_H
    ax.plot([X0, W - X0], [y, y], color=RULE, lw=0.8)

text(X0, H - PAD - 84, "Desktop, Linux, RTX 4090, stereo output; the game's stream recorded alone. Game-thread ms from async-profiler "
     "samples; FMOD's own mixer threads stay at 0.1 of a core.", 12.5, INK2)
text(X0, H - PAD - 60, "On by default: Options > Optimizations > Sound (audioLimiter, audioLimiterStereoFold, emitterIdleSkip, "
     "soundTickHz, worldSoundCleanupFast, hearingHoist). Stock = the shipped game.", 12.5, INK2)
text(X0, H - PAD - 30, "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-sound-2026-09-24.md.", 11.5, MUTED)
fig.savefig(OUT, dpi=100, facecolor=BG)
print(OUT, W, H)
