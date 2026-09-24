#!/usr/bin/env python3
"""Render docs/media/power-efficiency.png: power at a 120 fps cap, stock vs the previous release vs this one
(docs/findings-ecores-2026-09-24.md, the E-core pass). Same media style as harness/lowend-table.py
(docs/media-style.md): near-black surface, Noto Sans, numbers in Noto Sans Mono, stock in amber, the new build in green,
the previous release in grey. The image carries every word of the Workshop's "New! Power efficiency" section.

    python3 harness/power-table.py
    ffmpeg -y -i docs/media/power-efficiency.png -vf scale=1920:-1 -q:v 3 docs/workshop/images/25-power-efficiency.jpg
"""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

OUT = "docs/media/power-efficiency.png"
DATE = "2026-09-24"
BG, PANEL, RULE, INK, INK2, MUTED = "#0b0b0e", "#111114", "#26262c", "#f0f0f4", "#b4b4b8", "#8a8a90"
STOCK, PREV, OPT = "#c9592b", "#7a7a82", "#44de7c"
plt.rcParams["font.family"] = ["Noto Sans", "DejaVu Sans", "sans-serif"]
MONO = "Noto Sans Mono"

# scene, machine line, (W, fps) stock / previous release / this release (None = not measured)
ROWS = [
    ("Ayaneo Flip, balanced profile", "Ryzen AI 9 HX 370, Radeon 890M, Linux", (34.6, 112), (32.5, 119), (25.4, 120)),
    ("Ayaneo Flip, power-saver profile", "the same, every core capped at 2 GHz", (21.1, 93), (23.9, 112), (19.4, 119)),
    ("MacBook Pro M1 Pro", "system power incl. display; previous = the spinning limiter", None, (35.2, 118), (27.1, 118)),
]
W_MAX = 36.0

W, ROW_H, HEAD_H, TOP, BOT, PAD = 2100, 104, 84, 236, 150, 40
H = TOP + HEAD_H + ROW_H * len(ROWS) + BOT + PAD
fig = plt.figure(figsize=(W / 100, H / 100), dpi=100)
fig.patch.set_facecolor(BG)
ax = fig.add_axes([0, 0, 1, 1]); ax.set_xlim(0, W); ax.set_ylim(H, 0); ax.axis("off")
ax.add_patch(FancyBboxPatch((PAD, PAD), W - 2 * PAD, H - 2 * PAD, boxstyle="round,pad=0,rounding_size=10",
                            facecolor=PANEL, edgecolor="#505058", linewidth=0.8))


def text(x, y, s, size=15, color=INK, weight="normal", ha="left", va="center", family=None):
    ax.text(x, y, s, fontsize=size, color=color, fontweight=weight, ha=ha, va=va, family=family)


X0 = PAD + 48
text(X0, 86, "New! Power efficiency", 30, OPT, "bold")
text(W - X0, 86, DATE, 16, MUTED, ha="right", family=MONO)
text(X0, 134, "The game's threads go to the cores that cost the least: background work (lighting, compiler, audio, loading) on the "
     "efficient cores, the game and render", 14, INK2)
text(X0, 158, "threads on the fast cores only when they would miss the frame cap. The frame limiter sleeps instead of spinning a core, "
     "AMD GPUs on Linux hold the lowest clock", 14, INK2)
text(X0, 182, "that keeps the cap, and the Java compiler stops recompiling. Power at a 120 fps cap, the 100 s walk at the widest zoom; "
     "lower is better.", 14, INK2)

X_SCENE = X0
COLS = [(X0 + 700, "STOCK", STOCK), (X0 + 1100, "PREVIOUS RELEASE", PREV), (X0 + 1500, "THIS RELEASE", OPT)]
BAR_W, VAL_X = 170, 186
y = TOP
text(X_SCENE, y + 30, "MACHINE", 12.5, MUTED, "semibold")
for xr, name, col in COLS:
    text(xr, y + 18, name, 12.5, col, "semibold")
    text(xr, y + 44, "watts            fps", 11.5, MUTED)
text(W - X0, y + 30, "VS PREVIOUS", 12.5, MUTED, "semibold", ha="right")
y += HEAD_H
ax.plot([X0, W - X0], [y - 10, y - 10], color="#505058", lw=1)

for scene, machine, s, p, n in ROWS:
    cy = y + ROW_H / 2
    text(X_SCENE, cy - 14, scene, 16, INK, "semibold")
    text(X_SCENE, cy + 16, machine, 12.5, MUTED)
    for (xr, _, colour), cell in zip(COLS, (s, p, n)):
        if cell is None:
            text(xr, cy, "not measured", 13, MUTED)
            continue
        watts, fps = cell
        w = BAR_W * min(watts, W_MAX) / W_MAX
        ax.add_patch(FancyBboxPatch((xr, cy - 11), w, 22, boxstyle="round,pad=0,rounding_size=4",
                                    facecolor=colour, edgecolor="none"))
        weight = "semibold" if colour == OPT else "normal"
        text(xr + VAL_X, cy, f"{watts:.1f}", 19, colour, weight, family=MONO)
        text(xr + VAL_X + 86, cy, f"{fps:.0f}", 14.5, INK2, family=MONO)
    gain = (n[0] - p[0]) / p[0] * 100
    text(W - X0, cy, f"{gain:.0f} %", 17, OPT, "semibold", ha="right", family=MONO)
    y += ROW_H
    ax.plot([X0, W - X0], [y, y], color=RULE, lw=0.8)

text(X0, H - PAD - 84, "Flip: the chip's own package power (PPT); frame tail kept or better (balanced p99 18.5 -> 17.3 ms). Later in play 21 W, "
     "standing still 18 W. A 120 km/h drive: 29.6 -> 26.3 W.", 12.5, INK2)
text(X0, H - PAD - 60, "On by default: Options > Optimizations > CPU cores and power (corePlacement, gpuPstate, limiterSleep, jitSteady). "
     "Stock = the shipped game.", 12.5, INK2)
text(X0, H - PAD - 30, "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-ecores-2026-09-24.md.", 11.5, MUTED)
fig.savefig(OUT, dpi=100, facecolor=BG)
print(OUT, W, H)
