#!/usr/bin/env python3
"""Render docs/media/zombie-lod-card.png: the Workshop's "New! Zombie detail follows the frame cap" card, the
Louisville horde at a 144 and a 240 fps cap with zombieLodDynamic off vs on (runs lod-144-off, lod2-144-off,
lod2-144-on, lod-240-off, lod2-240-on; 2026-09-24). Same media style as harness/lowend-table.py
(docs/media-style.md): near-black surface, Noto Sans, numbers in Noto Sans Mono, off in amber, on in green. The
image carries every word of the section (the description embeds only the picture).

    python3 harness/zombie-lod-card.py
    ffmpeg -y -i docs/media/zombie-lod-card.png -vf scale=1920:-1 -q:v 3 docs/workshop/images/23-zombie-detail.jpg
"""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

OUT = "docs/media/zombie-lod-card.png"
DATE = "2026-09-24"
BG, PANEL, RULE, INK, INK2, MUTED = "#0b0b0e", "#111114", "#26262c", "#f0f0f4", "#b4b4b8", "#8a8a90"
STOCK, OPT = "#c9592b", "#44de7c"
plt.rcParams["font.family"] = ["Noto Sans", "DejaVu Sans", "sans-serif"]
MONO = "Noto Sans Mono"

# cap, (fps, p99 ms, frames over 33 ms in the 25 s route) off, the same on
ROWS = [
    ("144 fps cap", (117.3, 35.4, 33), (131.6, 24.2, 12)),
    ("240 fps cap", (122.4, 34.9, 34), (160.8, 22.2, 16)),
]
FPS_MAX = 180.0

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
text(X0, 86, "New! Zombie detail follows the frame cap", 30, OPT, "bold")
text(W - X0, 86, DATE, 16, MUTED, ha="right", family=MONO)
text(X0, 134, "The game draws the 510 nearest zombies as 3D models and blends the animations of 20. With this option those "
     "numbers come down while frames miss your cap", 14, INK2)
text(X0, 158, "(farthest zombies first: they turn into the flat sprites the game already uses) and climb back when there is "
     "room. Options > Optimizations, zombie section; off by default.", 14, INK2)
text(X0, 196, "Downtown Louisville, ~2,300 zombies, all other optimizations on. RTX 4090, 16 cores, 5120x2160, max zoom, Linux. "
     "fps mean, p99, frames over 33 ms in 25 s.", 13, MUTED)

X_ROUTE = X0
X_S, X_P = X0 + 560, X0 + 1180
BAR_W, VAL_X = 210, 226
y = TOP
text(X_ROUTE, y + 30, "FRAME CAP", 12.5, MUTED, "semibold")
for xr, name, col in ((X_S, "OPTION OFF", STOCK), (X_P, "ZOMBIE DETAIL FOLLOWS THE CAP", OPT)):
    text(xr, y + 18, name, 12.5, col, "semibold")
    text(xr, y + 44, "fps mean      p99 ms  /  over 33 ms", 11.5, MUTED)
y += HEAD_H
ax.plot([X0, W - X0], [y - 10, y - 10], color="#505058", lw=1)

for cap, s, p in ROWS:
    cy = y + ROW_H / 2
    text(X_ROUTE, cy, cap, 16, INK, "semibold")
    for xr, (fps, p99, slow), colour, weight in ((X_S, s, STOCK, "normal"), (X_P, p, OPT, "semibold")):
        w = BAR_W * min(fps, FPS_MAX) / FPS_MAX
        ax.add_patch(FancyBboxPatch((xr, cy - 11), w, 22, boxstyle="round,pad=0,rounding_size=4",
                                    facecolor=colour, edgecolor="none"))
        text(xr + VAL_X, cy, f"{fps:.0f}", 19, colour, weight, family=MONO)
        text(xr + VAL_X + 78, cy, f"{p99:.0f}  /  {slow:.0f}", 14.5, INK2, family=MONO)
    gain = p[0] / s[0]
    text(W - X0, cy, f"+{(gain - 1) * 100:.0f} %", 17, OPT, "semibold", ha="right", family=MONO)
    y += ROW_H
    ax.plot([X0, W - X0], [y, y], color=RULE, lw=0.8)

text(X0, H - PAD - 84, "Bars: fps mean, 0 to 180. The horde's simulation is most of this frame, so even at the floor "
     "(128 models, 6 blending by default) it stays under the cap; the tail halves.", 12.5, INK2)
text(X0, H - PAD - 60, "Settings next to it: the fewest 3D and blending zombies it may go down to, and a target frame rate "
     "for when the game is uncapped (default: full detail uncapped).", 12.5, INK2)
text(X0, H - PAD - 30, "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/override-edits.md, "
     "zombieLodDynamic, " + DATE + ".", 11.5, MUTED)
fig.savefig(OUT, dpi=100, facecolor=BG)
print(OUT, W, H)
