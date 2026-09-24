#!/usr/bin/env python3
"""Render docs/media/zombie-cores-comparison.png: the Louisville horde before vs with the zombie postupdate
pass (docs/archive/2026-09-24/results.md, "Zombie postupdate pass", 2026-09-23): four alternating pairs on the same build, the
pass's keys off vs on. Style and layout from harness/lowend-table.py (docs/media-style.md). The image
carries every word of the Workshop's "New! Zombie hordes on all cores" section.

    python3 harness/zombie-cores-table.py
    ffmpeg -y -i docs/media/zombie-cores-comparison.png -vf scale=1920:-1 -q:v 3 docs/workshop/images/20-zombie-cores.jpg
"""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

OUT = "docs/media/zombie-cores-comparison.png"
DATE = "2026-09-23"
# docs/media-style.md, SDR column
BG, PANEL, RULE, INK, INK2, MUTED = "#0b0b0e", "#111114", "#26262c", "#f0f0f4", "#b4b4b8", "#8a8a90"
STOCK, OPT = "#c9592b", "#44de7c"
plt.rcParams["font.family"] = ["Noto Sans", "DejaVu Sans", "sans-serif"]
MONO = "Noto Sans Mono"

# route, (fps, mean ms, p99 ms) stock, (fps, mean ms, p99 ms) profile
ROWS = [
    ("Louisville downtown, ~2,000 zombies, slow spin", (90.1, 11.2, 31), (105.4, 9.5, 28)),
]
FPS_MAX = 120.0

W, ROW_H, HEAD_H, TOP, BOT, PAD = 2100, 96, 84, 236, 150, 40
H = TOP + HEAD_H + ROW_H * len(ROWS) + BOT + PAD
fig = plt.figure(figsize=(W / 100, H / 100), dpi=100)
fig.patch.set_facecolor(BG)
ax = fig.add_axes([0, 0, 1, 1]); ax.set_xlim(0, W); ax.set_ylim(H, 0); ax.axis("off")
ax.add_patch(FancyBboxPatch((PAD, PAD), W - 2 * PAD, H - 2 * PAD, boxstyle="round,pad=0,rounding_size=10",
                            facecolor=PANEL, edgecolor="#505058", linewidth=0.8))

def text(x, y, s, size=15, color=INK, weight="normal", ha="left", va="center", family=None):
    ax.text(x, y, s, fontsize=size, color=color, fontweight=weight, ha=ha, va=va, family=family)

X0 = PAD + 48
text(X0, 86, "New! Zombie hordes on all cores", 30, OPT, "bold")
text(W - X0, 86, DATE, 16, MUTED, ha="right", family=MONO)
text(X0, 134, "Every zombie's animation (which clips play, turn and move speeds, the bones) now runs on the other cores; sounds, footsteps and "
     "attacks still happen in the game's order.", 14, INK2)
text(X0, 158, "The zombie finishing step went from 16 % to 4 % of the main thread. Measured on a Ryzen 7 9800X3D (16 threads), RTX 4090, "
     "5120x2160, uncapped, Linux.", 14, INK2)
text(X0, 196, "Before = this release with the new zombie options off. fps mean, frame time mean and p99; mean of four alternating runs each.", 13, MUTED)

X_ROUTE = X0
X_S, X_P = X0 + 760, X0 + 1360
BAR_W, VAL_X = 210, 226
y = TOP
text(X_ROUTE, y + 30, "ROUTE", 12.5, MUTED, "semibold")
for xr, name, col in ((X_S, "BEFORE", STOCK), (X_P, "WITH ZOMBIES ON ALL CORES", OPT)):
    text(xr, y + 18, name, 12.5, col, "semibold")
    text(xr, y + 44, "fps mean      mean ms  /  p99 ms", 11.5, MUTED)
y += HEAD_H
ax.plot([X0, W - X0], [y - 10, y - 10], color="#505058", lw=1)

for route, s, p in ROWS:
    cy = y + ROW_H / 2
    text(X_ROUTE, cy, route, 16, INK, "semibold")
    for xr, (fps, mean, p99), colour, weight in ((X_S, s, STOCK, "normal"), (X_P, p, OPT, "semibold")):
        w = BAR_W * min(fps, FPS_MAX) / FPS_MAX
        ax.add_patch(FancyBboxPatch((xr, cy - 11), w, 22, boxstyle="round,pad=0,rounding_size=4",
                                    facecolor=colour, edgecolor="none"))
        text(xr + VAL_X, cy, f"{fps:.0f}", 19, colour, weight, family=MONO)
        text(xr + VAL_X + 78, cy, f"{mean:.1f}  /  {p99:.0f}", 14.5, INK2, family=MONO)
    gain = p[0] / s[0]
    text(W - X0, cy, f"{gain:.1f}x", 17, OPT, "semibold", ha="right", family=MONO)
    y += ROW_H
    ax.plot([X0, W - X0], [y, y], color=RULE, lw=0.8)

text(X0, H - PAD - 84, "Bars: fps mean, 0 to 120. Frames over 33 ms: 18.8 -> 15.5 per run. On by default; each part is a switch in "
     "Options > Optimizations (search \"zombie\").", 12.5, INK2)
text(X0, H - PAD - 60, "Also fixed: the game gives every 3D model the same update lock, which made the extra cores wait for each other.", 12.5, INK2)
text(X0, H - PAD - 30, "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/archive/2026-09-24/results.md, " + DATE + " sections.", 11.5, MUTED)
fig.savefig(OUT, dpi=100, facecolor=BG)
print(OUT, W, H)
