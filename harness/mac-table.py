#!/usr/bin/env python3
"""Render docs/media/mac-comparison.png: stock vs the overrides on a MacBook Pro (Apple M1 Pro), the
120 km/h highway drive (docs/archive/2026-09-24/results.md, 2026-09-21 macOS section). Same media style as
harness/lowend-table.py (docs/media-style.md): near-black surface, Noto Sans, numbers in Noto Sans Mono,
stock in amber, the overrides in green. The image carries every word of the Workshop's "New! macOS
support" section (the description embeds only the picture): title, machine, date, install line, table.

    python3 harness/mac-table.py                      # PNG
    ffmpeg -y -i docs/media/mac-comparison.png -vf scale=1920:-1 -q:v 3 docs/workshop/images/12-macos.jpg
"""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

OUT = "docs/media/mac-comparison.png"
DATE = "2026-09-21"
# docs/media-style.md, SDR column
BG, PANEL, RULE, INK, INK2, MUTED = "#0b0b0e", "#111114", "#26262c", "#f0f0f4", "#b4b4b8", "#8a8a90"
STOCK, OPT = "#c9592b", "#44de7c"
plt.rcParams["font.family"] = ["Noto Sans", "DejaVu Sans", "sans-serif"]
MONO = "Noto Sans Mono"

# metric, stock, optimized, unit, decimals, higher is better  (mean of two runs per side)
ROWS = [
    ("fps, mean", 144.9, 203.1, "fps", 0, True),
    ("frame time, mean", 6.9, 5.0, "ms", 1, False),
    ("frame time, p99", 16.5, 13.7, "ms", 1, False),
    ("frame time, p99.9", 32.2, 24.7, "ms", 1, False),
    ("frames over 33 ms on the route", 5.0, 1.5, "", 1, False),
    ("chunk queue wait, median", 156, 9, "ms", 0, False),
]

W, ROW_H, HEAD_H, TOP, BOT, PAD = 2100, 84, 84, 236, 150, 40
H = TOP + HEAD_H + ROW_H * len(ROWS) + BOT + PAD
fig = plt.figure(figsize=(W / 100, H / 100), dpi=100)
fig.patch.set_facecolor(BG)
ax = fig.add_axes([0, 0, 1, 1]); ax.set_xlim(0, W); ax.set_ylim(H, 0); ax.axis("off")
ax.add_patch(FancyBboxPatch((PAD, PAD), W - 2 * PAD, H - 2 * PAD, boxstyle="round,pad=0,rounding_size=10",
                            facecolor=PANEL, edgecolor="#505058", linewidth=0.8))

def text(x, y, s, size=15, color=INK, weight="normal", ha="left", va="center", family=None):
    ax.text(x, y, s, fontsize=size, color=color, fontweight=weight, ha=ha, va=va, family=family)

X0 = PAD + 48
text(X0, 86, "New! macOS support", 30, OPT, "bold")
text(W - X0, 86, DATE, 16, MUTED, ha="right", family=MONO)
text(X0, 134, "The same files run on the macOS Steam depot: install.bash copies them into Project Zomboid.app/Contents/Java, "
     "which the app's launcher already searches before the jar.", 14, INK2)
text(X0, 158, "Measured on a MacBook Pro: Apple M1 Pro (8 CPU / 14 GPU cores, 16 GB), macOS 27, Apple OpenGL over Metal, "
     "1920x1200 fullscreen, 240 fps cap, max zoom, driving at 120 km/h.", 14, INK2)
text(X0, 196, "Stock = the shipped game (every optimization off). Two runs per side, averaged; bars are scaled per row.", 13, MUTED)

X_METRIC = X0
X_S, X_P = X0 + 760, X0 + 1360
BAR_W, VAL_X = 250, 266
y = TOP
text(X_METRIC, y + 30, "120 KM/H HIGHWAY DRIVE", 12.5, MUTED, "semibold")
for xr, name, col in ((X_S, "STOCK", STOCK), (X_P, "OPTIMIZED", OPT)):
    text(xr, y + 30, name, 12.5, col, "semibold")
text(W - X0, y + 30, "CHANGE", 12.5, MUTED, "semibold", ha="right")
y += HEAD_H
ax.plot([X0, W - X0], [y - 10, y - 10], color="#505058", lw=1)

for metric, s, p, unit, dec, higher in ROWS:
    cy = y + ROW_H / 2
    text(X_METRIC, cy, metric, 16, INK, "semibold")
    top = max(s, p) or 1
    for xr, v, colour, weight in ((X_S, s, STOCK, "normal"), (X_P, p, OPT, "semibold")):
        w = BAR_W * v / top
        ax.add_patch(FancyBboxPatch((xr, cy - 11), max(w, 3), 22, boxstyle="round,pad=0,rounding_size=4",
                                    facecolor=colour, edgecolor="none"))
        text(xr + VAL_X, cy, f"{v:.{dec}f}" + (f" {unit}" if unit else ""), 19, colour, weight, family=MONO)
    if higher:
        change = f"{p / s:.1f}x"
    elif s and p:
        change = f"{(p / s - 1) * 100:+.0f} %"
    else:
        change = f"{s:.0f} -> {p:.0f}"
    text(W - X0, cy, change, 17, OPT, "semibold", ha="right", family=MONO)
    y += ROW_H
    ax.plot([X0, W - X0], [y, y], color=RULE, lw=0.8)

text(X0, H - PAD - 84, "Apple's OpenGL has no ARB_buffer_storage, so the persistent-VBO path falls back by itself; everything else "
     "runs as on Linux and Windows.", 12.5, INK2)
text(X0, H - PAD - 60, "Install: subscribe, close the game, then in Terminal  bash ~/Library/Application\\ Support/Steam/steamapps/workshop/"
     "content/108600/3805285544/mods/PZ_Optimization/42/install.bash", 12.5, INK2)
text(X0, H - PAD - 30, "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, README \"macOS\" and docs/archive/2026-09-24/results.md, "
     + DATE + ".", 11.5, MUTED)
fig.savefig(OUT, dpi=100, facecolor=BG)
print(OUT, W, H)
