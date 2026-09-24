#!/usr/bin/env python3
"""Render docs/media/input-latency-reflex.png: input -> screen latency, stock vs this build with the low-latency options
(docs/archive/2026-09-24/findings-input-latency-2026-09-24.md). Same media style as harness/lowend-table.py (docs/media-style.md): stock in
amber, the new build in green. The image carries every word of the Workshop's "New! NVIDIA Reflex-style low latency"
section (the description embeds only the picture).

    python3 harness/reflex-table.py
    ffmpeg -y -i docs/media/input-latency-reflex.png -vf scale=1920:-1 -q:v 3 docs/workshop/images/21-low-latency.jpg
"""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

OUT = "docs/media/input-latency-reflex.png"
DATE = "2026-09-24"
BG, PANEL, RULE, INK, INK2, MUTED = "#0b0b0e", "#111114", "#26262c", "#f0f0f4", "#b4b4b8", "#8a8a90"
STOCK, OPT = "#c9592b", "#44de7c"
plt.rcParams["font.family"] = ["Noto Sans", "DejaVu Sans", "sans-serif"]
MONO = "Noto Sans Mono"

# input, stock ms (il-60-base), new ms (mean of il-60-boost / il-60-boostb): press -> the frame on screen, p50
ROWS = [
    ("Keyboard (walk)", 47.1, (10.5 + 16.9) / 2),
    ("Mouse (aiming)", 32.6, (13.3 + 12.8) / 2),
    ("Controller: left stick (walk)", 35.8, (12.1 + 11.8) / 2),
    ("Controller: right stick (aiming)", 29.4, (11.2 + 11.4) / 2),
    ("Controller: left trigger (aim)", 28.1, (7.2 + 5.7) / 2),
]
MS_MAX = 50.0

W, ROW_H, HEAD_H, TOP, BOT, PAD = 2100, 88, 70, 250, 150, 40
H = TOP + HEAD_H + ROW_H * len(ROWS) + BOT + PAD
fig = plt.figure(figsize=(W / 100, H / 100), dpi=100)
fig.patch.set_facecolor(BG)
ax = fig.add_axes([0, 0, 1, 1]); ax.set_xlim(0, W); ax.set_ylim(H, 0); ax.axis("off")
ax.add_patch(FancyBboxPatch((PAD, PAD), W - 2 * PAD, H - 2 * PAD, boxstyle="round,pad=0,rounding_size=10",
                            facecolor=PANEL, edgecolor="#505058", linewidth=0.8))

def text(x, y, s, size=15, color=INK, weight="normal", ha="left", va="center", family=None):
    ax.text(x, y, s, fontsize=size, color=color, fontweight=weight, ha=ha, va=va, family=family)

X0 = PAD + 48
text(X0, 86, "New! NVIDIA Reflex-style low latency", 30, OPT, "bold")
text(W - X0, 86, DATE, 16, MUTED, ha="right", family=MONO)
text(X0, 134, "Keyboard, mouse and controller reach the screen sooner. The keyboard no longer runs a frame late and every frame reads the newest input"
     " (both on by default).", 14, INK2)
text(X0, 158, "Options > Optimizations > Input latency adds Reflex's low-latency mode (just-in-time frame start + a cap below the refresh) and"
     " Boost (GPU clocks held up, NVIDIA).", 14, INK2)
text(X0, 196, "Press of a virtual keyboard / mouse / pad -> the frame on screen, median. 60 fps cap, vsync on, RTX 4090, 165 Hz, Linux."
     " New = all options on.", 13, MUTED)

X_IN = X0
X_S, X_P = X0 + 640, X0 + 1180
BAR_W, VAL_X = 300, 318
y = TOP
text(X_IN, y + 28, "INPUT", 12.5, MUTED, "semibold")
for xr, name, col in ((X_S, "STOCK", STOCK), (X_P, "THIS BUILD", OPT)):
    text(xr, y + 18, name, 12.5, col, "semibold")
    text(xr, y + 42, "input -> screen, ms (shorter is better)", 11.5, MUTED)
text(W - X0, y + 28, "FASTER BY", 12.5, MUTED, "semibold", ha="right")
y += HEAD_H
ax.plot([X0, W - X0], [y - 10, y - 10], color="#505058", lw=1)

for name, s, p in ROWS:
    cy = y + ROW_H / 2
    text(X_IN, cy, name, 16, INK, "semibold")
    for xr, ms, colour, weight in ((X_S, s, STOCK, "normal"), (X_P, p, OPT, "semibold")):
        w = BAR_W * min(ms, MS_MAX) / MS_MAX
        ax.add_patch(FancyBboxPatch((xr, cy - 11), w, 22, boxstyle="round,pad=0,rounding_size=4",
                                    facecolor=colour, edgecolor="none"))
        text(xr + VAL_X, cy, f"{ms:.0f}", 19, colour, weight, family=MONO)
    text(W - X0, cy, f"{s - p:.0f} ms", 17, OPT, "semibold", ha="right", family=MONO)
    y += ROW_H
    ax.plot([X0, W - X0], [y, y], color=RULE, lw=0.8)

text(X0, H - PAD - 84, "Uncapped with vsync on: 25-28 ms -> 5-10 ms with the low-latency mode and its cap (157 fps at 165 Hz). Boost costs"
     " ~28 W at a 60 fps cap and is released when the game exits.", 12.5, INK2)
text(X0, H - PAD - 60, "NVIDIA Reflex itself exists only for Direct3D and Vulkan; the game uses OpenGL, so this implements Reflex's method with"
     " OpenGL and NVIDIA's NVML.", 12.5, INK2)
text(X0, H - PAD - 30, "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/archive/2026-09-24/findings-input-latency-2026-09-24.md.", 11.5, MUTED)
fig.savefig(OUT, dpi=100, facecolor=BG)
print(OUT, W, H)
