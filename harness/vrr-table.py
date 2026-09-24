#!/usr/bin/env python3
"""Render docs/media/vrr.png: variable refresh (G-SYNC / FreeSync / ProMotion), without vs with this build's VRR work
(docs/findings-vrr-2026-09-24.md). Same media style as harness/reflex-table.py (docs/media-style.md): the "without" side in
amber, this build in green. The image carries every word of the Workshop's "New! Variable refresh" section.

    python3 harness/vrr-table.py
    ffmpeg -y -i docs/media/vrr.png -vf scale=1920:-1 -q:v 3 docs/workshop/images/22-vrr.jpg
"""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

OUT = "docs/media/vrr.png"
DATE = "2026-09-24"
BG, PANEL, RULE, INK, INK2, MUTED = "#0b0b0e", "#111114", "#26262c", "#f0f0f4", "#b4b4b8", "#8a8a90"
STOCK, OPT = "#c9592b", "#44de7c"
plt.rcParams["font.family"] = ["Noto Sans", "DejaVu Sans", "sans-serif"]
MONO = "Noto Sans Mono"

# (label, detail, without, with, unit, bar max, higher is better)
ROWS = [
    ("VRR on while playing", "borderless window, KWin (vrr-borderless vs vrr-bfs-144)", 0.0, 100.0, "%", 100.0, True),
    ("On-screen judder", "100 fps cap, VRR on, mean |flip gap - game step| (vrr-100 vs vrr4-100-gpu)", 3.10, 0.95, "ms", 3.5, False),
    ("Frames off by > 2 ms", "same runs", 54.0, 13.5, "%", 60.0, False),
    ("Frame -> screen", "uncapped + vsync, 165 Hz, median (vrr6-off vs vrr6-auto)", 21.2, 12.7, "ms", 25.0, False),
]

W, ROW_H, HEAD_H, TOP, BOT, PAD = 2100, 96, 70, 250, 176, 40
H = TOP + HEAD_H + ROW_H * len(ROWS) + BOT + PAD
fig = plt.figure(figsize=(W / 100, H / 100), dpi=100)
fig.patch.set_facecolor(BG)
ax = fig.add_axes([0, 0, 1, 1]); ax.set_xlim(0, W); ax.set_ylim(H, 0); ax.axis("off")
ax.add_patch(FancyBboxPatch((PAD, PAD), W - 2 * PAD, H - 2 * PAD, boxstyle="round,pad=0,rounding_size=10",
                            facecolor=PANEL, edgecolor="#505058", linewidth=0.8))

def text(x, y, s, size=15, color=INK, weight="normal", ha="left", va="center", family=None):
    ax.text(x, y, s, fontsize=size, color=color, fontweight=weight, ha=ha, va=va, family=family)

X0 = PAD + 48
text(X0, 86, "New! Variable refresh (G-SYNC, FreeSync, ProMotion)", 30, OPT, "bold")
text(W - X0, 86, DATE, 16, MUTED, ha="right", family=MONO)
text(X0, 134, "Stock borderless is a plain screen-sized window, so KDE, GNOME and gamescope never switch VRR on for it. Now it is a"
     " fullscreen window at the desktop's own mode (Linux).", 14, INK2)
text(X0, 158, "While VRR is on, the game caps itself inside the display's range (157 fps at 165 Hz) and holds each frame to an even"
     " delay, so frames reach the screen as evenly as the game steps.", 14, INK2)
text(X0, 196, "Same build with the VRR work off vs on. RTX 4090, 5120x2160 at 165 Hz (VRR 48-165), Linux, spinning bench route."
     " Options > Optimizations > Variable refresh rate.", 13, MUTED)

X_IN = X0
X_S, X_P = X0 + 760, X0 + 1240
BAR_W, VAL_X = 300, 318
y = TOP
text(X_IN, y + 28, "MEASURE", 12.5, MUTED, "semibold")
for xr, name, col in ((X_S, "WITHOUT", STOCK), (X_P, "THIS BUILD", OPT)):
    text(xr, y + 28, name, 12.5, col, "semibold")
text(W - X0, y + 28, "CHANGE", 12.5, MUTED, "semibold", ha="right")
y += HEAD_H
ax.plot([X0, W - X0], [y - 10, y - 10], color="#505058", lw=1)

def fmt(v, unit):
    return (f"{v:.0f}" if unit == "%" or v >= 10 else f"{v:.2f}") + (" %" if unit == "%" else " ms")

for name, detail, a, b, unit, top, higher in ROWS:
    cy = y + ROW_H / 2
    text(X_IN, cy - 14, name, 16, INK, "semibold")
    text(X_IN, cy + 16, detail, 11.5, MUTED)
    for xr, v, colour, weight in ((X_S, a, STOCK, "normal"), (X_P, b, OPT, "semibold")):
        w = max(BAR_W * min(v, top) / top, 3)
        ax.add_patch(FancyBboxPatch((xr, cy - 11), w, 22, boxstyle="round,pad=0,rounding_size=4",
                                    facecolor=colour, edgecolor="none"))
        text(xr + VAL_X, cy, fmt(v, unit), 18, colour, weight, family=MONO)
    d = b - a
    change = (f"{d:+.0f} pts" if unit == "%" else f"{d:+.1f} ms")
    text(W - X0, cy, change, 17, OPT, "semibold", ha="right", family=MONO)
    y += ROW_H
    ax.plot([X0, W - X0], [y, y], color=RULE, lw=0.8)

text(X0, H - PAD - 110, "macOS (Apple silicon, opt-in \"present through Metal\"): OpenGL frames only land on the 120 Hz grid, so 80 or 90 fps"
     " alternates 8 and 17 ms. Through Metal, ProMotion shows", 12.5, INK2)
text(X0, H - PAD - 86, "any multiple of 4.17 ms: at a steady game step 94 % of frames reach the screen exactly on time at 60 fps and 84 % at 80"
     " fps (M1 Pro). Costs ~10-15 ms of latency, so it is off by default.", 12.5, INK2)
text(X0, H - PAD - 58, "Windows: the borderless window already covers the screen; set Variable refresh rate = on for the cap and the even"
     " delivery. Not yet measured there or on AMD FreeSync.", 12.5, INK2)
text(X0, H - PAD - 28, "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-vrr-2026-09-24.md.", 11.5, MUTED)
fig.savefig(OUT, dpi=100, facecolor=BG)
print(OUT, W, H)
