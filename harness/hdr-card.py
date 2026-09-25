#!/usr/bin/env python3
"""Render docs/media/hdr-card.png: HDR output (docs/findings-hdr-2026-09-24.md), stock SDR vs HDR enhanced (the defaults).
Same media style as harness/vrr-table.py (docs/media-style.md): stock in amber, this build in green. The image carries
every word of the Workshop's "New! HDR output" section. Brightness numbers are the panel nits of each pane of
docs/media/hdr-stock-vs-hdr-vs-enhanced.mp4 (8 frames per scene, PQ decoded); the frame cost is hdrperf-off vs
hdrperf-enc-false (uncapped spinning bench, KDE, no encode pass).

    python3 harness/hdr-card.py
    ffmpeg -y -i docs/media/hdr-card.png -vf scale=1920:-1 -q:v 3 docs/workshop/images/24-hdr.jpg
"""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

OUT = "docs/media/hdr-card.png"
DATE = "2026-09-24"
BG, PANEL, RULE, INK, INK2, MUTED = "#0b0b0e", "#111114", "#26262c", "#f0f0f4", "#b4b4b8", "#8a8a90"
STOCK, OPT = "#c9592b", "#44de7c"
plt.rcParams["font.family"] = ["Noto Sans", "DejaVu Sans", "sans-serif"]
MONO = "Noto Sans Mono"

# (label, detail, stock, HDR, unit, bar max)
ROWS = [
    ("Night: brightest light", "torch, street lamps, three fires; brightest 0.1 % of the screen", 142, 475, "nits", 1300),
    ("Night: whole picture", "average of the screen (the dark stays dark)", 2, 7, "nits", 80),
    ("Clear day: highlights", "river shore at 3 pm, sun glints on the water; brightest 0.1 %", 350, 529, "nits", 1300),
    ("Thunderstorm", "lightning every few seconds; brightest 0.1 %", 138, 268, "nits", 1300),
    ("Frame rate cost", "uncapped spinning bench, 5120x2160, KDE (HDR off vs on)", 289, 285, "fps", 320),
]

W, ROW_H, HEAD_H, TOP, BOT, PAD = 2100, 92, 70, 250, 150, 40
H = TOP + HEAD_H + ROW_H * len(ROWS) + BOT + PAD
fig = plt.figure(figsize=(W / 100, H / 100), dpi=100)
fig.patch.set_facecolor(BG)
ax = fig.add_axes([0, 0, 1, 1]); ax.set_xlim(0, W); ax.set_ylim(H, 0); ax.axis("off")
ax.add_patch(FancyBboxPatch((PAD, PAD), W - 2 * PAD, H - 2 * PAD, boxstyle="round,pad=0,rounding_size=10",
                            facecolor=PANEL, edgecolor="#505058", linewidth=0.8))

def text(x, y, s, size=15, color=INK, weight="normal", ha="left", va="center", family=None):
    ax.text(x, y, s, fontsize=size, color=color, fontweight=weight, ha=ha, va=va, family=family)

X0 = PAD + 48
text(X0, 86, "New! HDR output (Linux, macOS)", 30, OPT, "bold")
text(W - X0, 86, DATE, 16, MUTED, ha="right", family=MONO)
text(X0, 134, "On an HDR screen the menus and the HUD stay at your desktop's white, while lamps, torches, headlights, fires, sunlight"
     " on water and lightning", 14, INK2)
text(X0, 158, "go above it up to the screen's peak, each in its own colour. The game already knows where its lights are: the brightness"
     " comes from them, not from guessing.", 14, INK2)
text(X0, 196, "Stock SDR vs HDR at the defaults, same save and camera. RTX 4090, 5120x2160 HDR panel (1307 nits peak), KDE Plasma 6."
     " Options > Enhancements > HDR output.", 13, MUTED)

X_IN = X0
X_S, X_P = X0 + 760, X0 + 1240
BAR_W, VAL_X = 300, 318
y = TOP
text(X_IN, y + 28, "MEASURE", 12.5, MUTED, "semibold")
for xr, name, col in ((X_S, "STOCK (SDR)", STOCK), (X_P, "HDR", OPT)):
    text(xr, y + 28, name, 12.5, col, "semibold")
text(W - X0, y + 28, "CHANGE", 12.5, MUTED, "semibold", ha="right")
y += HEAD_H
ax.plot([X0, W - X0], [y - 10, y - 10], color="#505058", lw=1)

for name, detail, a, b, unit, top in ROWS:
    cy = y + ROW_H / 2
    text(X_IN, cy - 14, name, 16, INK, "semibold")
    text(X_IN, cy + 16, detail, 11.5, MUTED)
    for xr, v, colour, weight in ((X_S, a, STOCK, "normal"), (X_P, b, OPT, "semibold")):
        w = max(BAR_W * min(v, top) / top, 3)
        ax.add_patch(FancyBboxPatch((xr, cy - 11), w, 22, boxstyle="round,pad=0,rounding_size=4",
                                    facecolor=colour, edgecolor="none"))
        text(xr + VAL_X, cy, f"{v:.0f} {unit}", 18, colour, weight, family=MONO)
    change = f"{b / a:.1f}x" if unit == "nits" else f"{(b - a) / a * 100:+.0f} %"
    text(W - X0, cy, change, 17, OPT if unit == "nits" else INK2, "semibold", ha="right", family=MONO)
    y += ROW_H
    ax.plot([X0, W - X0], [y, y], color=RULE, lw=0.8)

text(X0, H - PAD - 86, "On by itself on an HDR screen: Linux with HDR on in a Wayland desktop with colour management (tested on KDE Plasma 6; the game"
     " then runs as a native Wayland window), and Macs with an XDR display (lights and lightning).", 12.5, INK2)
text(X0, H - PAD - 58, "SDR screens are unchanged. \"HDR output: automatic\" can be unticked; strengths for light, sunlight, water sparkle, glow and"
     " the peak are on the same tab. Windows HDR comes later.", 12.5, INK2)
text(X0, H - PAD - 28, "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-hdr-2026-09-24.md"
     " (video: docs/media/hdr-stock-vs-hdr-vs-enhanced-1080.mp4).", 11.5, MUTED)
fig.savefig(OUT, dpi=100, facecolor=BG)
print(OUT, W, H)
