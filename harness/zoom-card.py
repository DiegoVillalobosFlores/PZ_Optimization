#!/usr/bin/env python3
"""Render docs/media/zoom-card.png, the Workshop page's "New! Smooth zoom" image (2026-09-22 zoom pass, docs/archive/2026-09-24/results.md
"Camera zoom changes"). Same media style as harness/lowend-table.py (docs/media-style.md): near-black surface, Noto Sans,
numbers in Noto Sans Mono, stock in amber, new in green. The image carries every word of the section (the description
embeds only the picture): title, date, what changed, the machine, and the worst-frame table with a bar per cell.

    python3 harness/zoom-card.py
    ffmpeg -y -i docs/media/zoom-card.png -vf scale=1920:-1 -q:v 3 docs/workshop/images/16-smooth-zoom.jpg
"""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

OUT = "docs/media/zoom-card.png"
DATE = "2026-09-22"
BG, PANEL, RULE, INK, INK2, MUTED = "#0b0b0e", "#111114", "#26262c", "#f0f0f4", "#b4b4b8", "#8a8a90"
STOCK, OPT = "#c9592b", "#44de7c"
plt.rcParams["font.family"] = ["Noto Sans", "DejaVu Sans", "sans-serif"]
MONO = "Noto Sans Mono"

# case, worst frame ms stock, worst frame ms new (docs/archive/2026-09-24/results.md, runs zs-out-* / zo-jump9-plan5u / zo-wheel-* / zo-wheel9-ease)
ROWS = [
    ("Full wheel spin 0.25 -> 2.5 at once, first time at a spot (textures never baked)", 375, 25),
    ("Full wheel spin 0.25 -> 2.5 at once, again (median of three)", 83, 18),
    ("One notch out at wide zoom (2.0 -> 2.5) while driving", 51, 25),
    ("Full spin through the wheel's own ease", 28, 20),
]
MS_MAX = 400.0

W, ROW_H, HEAD_H, TOP, BOT, PAD = 2100, 96, 84, 236, 176, 40
H = TOP + HEAD_H + ROW_H * len(ROWS) + BOT + PAD
fig = plt.figure(figsize=(W / 100, H / 100), dpi=100)
fig.patch.set_facecolor(BG)
ax = fig.add_axes([0, 0, 1, 1]); ax.set_xlim(0, W); ax.set_ylim(H, 0); ax.axis("off")
ax.add_patch(FancyBboxPatch((PAD, PAD), W - 2 * PAD, H - 2 * PAD, boxstyle="round,pad=0,rounding_size=10",
                            facecolor=PANEL, edgecolor="#505058", linewidth=0.8))

def text(x, y, s, size=15, color=INK, weight="normal", ha="left", va="center", family=None):
    ax.text(x, y, s, fontsize=size, color=color, fontweight=weight, ha=ha, va=va, family=family)

X0 = PAD + 48
text(X0, 86, "New! Smooth zoom", 30, OPT, "bold")
text(W - X0, 86, DATE, 16, MUTED, ha="right", family=MONO)
text(X0, 134, "Stock throws away every chunk texture that leaves the screen when you zoom in and bakes them all again in the one frame "
     "they come back: a wheel spin out", 14, INK2)
text(X0, 158, "froze the game for 80 to 375 ms. Now the textures are kept, what a zoom reveals is baked a few per frame nearest you "
     "first, and the zoom itself moves along a", 14, INK2)
text(X0, 182, "300 ms curve at every frame rate instead of a fixed step per frame. Measured at 5120x2160, uncapped, RTX 4090 / 9800X3D, "
     "driving south through Rosewood.", 14, INK2)
text(X0, 214, "Worst frame in the second after the zoom change, ms (lower is better). The route's own spikes are 26 to 45 ms.", 13, MUTED)

X_ROUTE = X0
X_S, X_P = X0 + 1000, X0 + 1400
BAR_W, VAL_X = 280, 296
y = TOP
text(X_ROUTE, y + 30, "ZOOM CHANGE", 12.5, MUTED, "semibold")
for xr, name, col in ((X_S, "STOCK", STOCK), (X_P, "NEW", OPT)):
    text(xr, y + 18, name, 12.5, col, "semibold")
    text(xr, y + 44, "worst frame, ms", 11.5, MUTED)
y += HEAD_H
ax.plot([X0, W - X0], [y - 10, y - 10], color="#505058", lw=1)

for route, s, p in ROWS:
    cy = y + ROW_H / 2
    text(X_ROUTE, cy, route, 15.5, INK, "semibold")
    for xr, ms, colour, weight in ((X_S, s, STOCK, "normal"), (X_P, p, OPT, "semibold")):
        w = BAR_W * min(ms, MS_MAX) / MS_MAX
        ax.add_patch(FancyBboxPatch((xr, cy - 11), max(w, 3), 22, boxstyle="round,pad=0,rounding_size=4",
                                    facecolor=colour, edgecolor="none"))
        text(xr + VAL_X, cy, f"{ms:.0f}", 19, colour, weight, family=MONO)
    text(W - X0, cy, f"{s / p:.1f}x", 17, OPT, "semibold", ha="right", family=MONO)
    y += ROW_H
    ax.plot([X0, W - X0], [y, y], color=RULE, lw=0.8)

text(X0, H - PAD - 108, "Zoom motion: stock 0.03 per frame and a snap (8 frames per notch: 16 ms at 500 fps, 130 ms at 60), now a cubic Bezier over "
     "300 ms of real time (CSS \"ease\"; the curve and the time are settings).", 12.5, INK2)
text(X0, H - PAD - 84, "Options > Optimizations: Keep chunk textures across zoom changes, Chunk textures a zoom change bakes per frame, "
     "Zoom: motion time, Zoom: motion timing curve. Video: bench-zoom-spin-stock-vs-new on the repository.", 12.5, INK2)
text(X0, H - PAD - 60, "Kept textures use no more video memory than stock holds at the widest zoom; a chunk that unloads still frees everything.", 12.5, INK2)
text(X0, H - PAD - 30, "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/archive/2026-09-24/results.md, " + DATE + " Camera zoom changes.", 11.5, MUTED)
fig.savefig(OUT, dpi=100, facecolor=BG)
print(OUT, W, H)
