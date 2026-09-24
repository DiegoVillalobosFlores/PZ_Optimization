#!/usr/bin/env python3
"""Render docs/media/dell-lowend-comparison.png: stock vs the "Low-end hardware" profile on the Dell
(i5-6300HQ / GTX 960M), four routes (docs/archive/2026-09-24/results.md, 2026-09-21 low-end section). Same media style as
harness/mods-table.py (docs/media-style.md): near-black surface, Noto Sans, numbers in Noto Sans Mono,
stock in amber, the profile in green. The image carries every word of the Workshop's "New! Low-end
hardware mode" section (the description embeds only the picture), so the title, the machine, the
date and the profile's contents are all in here.

    python3 harness/lowend-table.py                      # PNG
    ffmpeg -y -i docs/media/dell-lowend-comparison.png -vf scale=1920:-1 docs/workshop/images/11-low-end-hardware.jpg
"""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

OUT = "docs/media/dell-lowend-comparison.png"
DATE = "2026-09-21"
# docs/media-style.md, SDR column
BG, PANEL, RULE, INK, INK2, MUTED = "#0b0b0e", "#111114", "#26262c", "#f0f0f4", "#b4b4b8", "#8a8a90"
STOCK, OPT = "#c9592b", "#44de7c"
plt.rcParams["font.family"] = ["Noto Sans", "DejaVu Sans", "sans-serif"]
MONO = "Noto Sans Mono"

# route, (fps, mean ms, p99 ms) stock, (fps, mean ms, p99 ms) profile
ROWS = [
    ("Walking through Rosewood, facing spinning", (49.0, 20.5, 69), (81.4, 12.3, 30)),
    ("Driving at 60 km/h", (76.5, 13.1, 43), (93.0, 10.7, 28)),
    ("Driving at 120 km/h", (43.9, 22.8, 80), (68.5, 14.6, 40)),
    ("Driving at 120 km/h: night, thunderstorm, heavy fog", (15.4, 64.8, 202), (31.4, 31.8, 101)),
]
FPS_MAX = 100.0

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
text(X0, 86, "New! Low-end hardware mode", 30, OPT, "bold")
text(W - X0, 86, DATE, 16, MUTED, ha="right", family=MONO)
text(X0, 134, "One button in Options > Optimizations for machines with 4 cores or fewer: no chunk worker pool, trees baked into chunk "
     "textures only while walking,", 14, INK2)
text(X0, 158, "lighting updates 10/s, UI redrawn 30/s; with the G1 launcher JSON. Measured on a Dell laptop: "
     "Core i5-6300HQ (4 cores), GeForce GTX 960M, 1920x1080, max zoom, Linux.", 14, INK2)
text(X0, 196, "Stock = the shipped game (every optimization off). fps mean, frame time mean and p99; one run per cell.", 13, MUTED)

X_ROUTE = X0
X_S, X_P = X0 + 760, X0 + 1360
BAR_W, VAL_X = 210, 226
y = TOP
text(X_ROUTE, y + 30, "ROUTE", 12.5, MUTED, "semibold")
for xr, name, col in ((X_S, "STOCK", STOCK), (X_P, "LOW-END HARDWARE MODE", OPT)):
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

text(X0, H - PAD - 84, "Bars: fps mean, 0 to 100. World load 70 s -> 25 s. 60 fps on average on every route; the tail is the four cores being full "
     "(1 to 2 % of frames over 33 ms).", 12.5, INK2)
text(X0, H - PAD - 60, "Apply the profile, restart, and copy config/launcher/ProjectZomboid64.g1.json over the game's launcher JSON "
     "(the stock ZGC stalls for seconds on four cores).", 12.5, INK2)
text(X0, H - PAD - 30, "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/archive/2026-09-24/results.md, " + DATE + " sections.", 11.5, MUTED)
fig.savefig(OUT, dpi=100, facecolor=BG)
print(OUT, W, H)
