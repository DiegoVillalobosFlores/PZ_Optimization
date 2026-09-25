#!/usr/bin/env python3
"""Render docs/media/upscaler-card.png, the Workshop page's "New! Upscaler" image (2026-09-22 upscaling pass,
docs/plan-upscalers.md Log, README "Upscaling: FSR 1.0 and DLSS"). Same media style as harness/lowend-table.py
(docs/media-style.md): near-black surface, Noto Sans, numbers in Noto Sans Mono, the baseline in amber, an
improvement in green. The image carries every word of the section (the description embeds only the picture): title,
date, what the feature is, the machine, and the per-mode table with a bar per row.

    python3 harness/upscaler-card.py
    ffmpeg -y -i docs/media/upscaler-card.png -vf scale=1920:-1 -q:v 3 docs/workshop/images/17-upscaler.jpg
"""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

OUT = "docs/media/upscaler-card.png"
DATE = "2026-09-22"
BG, PANEL, RULE, INK, INK2, MUTED = "#0b0b0e", "#111114", "#26262c", "#f0f0f4", "#b4b4b8", "#8a8a90"
STOCK, OPT = "#c9592b", "#44de7c"
plt.rcParams["font.family"] = ["Noto Sans", "DejaVu Sans", "sans-serif"]
MONO = "Noto Sans Mono"

# mode, note, fps mean, frame time p50 ms, p99 ms  (120 km/h highway drive, 5120x2160, uncapped, one build;
# docs/plan-upscalers.md Log 2026-09-22 06:20 / 07:20, runs ups-off-p-3, ups-bicubic-p-3, ups-fsr1-p-3, ups-dlss-p-4, ups-dlss-f-1)
ROWS = [
    ("Upscaler off", "the world drawn at the screen size; the GPU is the wall (96-98 % busy)", 509, 1.5, 7.7),
    ("FSR 1.0, performance (50 %)", "AMD FidelityFX Super Resolution 1.0, EASU + RCAS as GLSL passes: any GPU, any OS", 619, 1.2, 6.4),
    ("Bicubic, 50 %", "the stock screen filter stretching the small frame: the plain baseline, soft", 676, 1.1, 6.2),
    ("DLSS, performance (50 %), preset F", "the older convolutional model: the cheaper DLSS", 377, 2.2, 9.2),
    ("DLSS, performance (50 %), default preset", "DLSS 4 transformer model: ~2.5 ms a frame at this output size, the best image", 232, 3.7, 12.2),
]
FPS_MAX = 700.0
BASE = ROWS[0][2]

W, ROW_H, HEAD_H, TOP, BOT, PAD = 2100, 96, 84, 236, 200, 40
H = TOP + HEAD_H + ROW_H * len(ROWS) + BOT + PAD
fig = plt.figure(figsize=(W / 100, H / 100), dpi=100)
fig.patch.set_facecolor(BG)
ax = fig.add_axes([0, 0, 1, 1]); ax.set_xlim(0, W); ax.set_ylim(H, 0); ax.axis("off")
ax.add_patch(FancyBboxPatch((PAD, PAD), W - 2 * PAD, H - 2 * PAD, boxstyle="round,pad=0,rounding_size=10",
                            facecolor=PANEL, edgecolor="#505058", linewidth=0.8))

def text(x, y, s, size=15, color=INK, weight="normal", ha="left", va="center", family=None):
    ax.text(x, y, s, fontsize=size, color=color, fontweight=weight, ha=ha, va=va, family=family)

X0 = PAD + 48
text(X0, 86, "New! Upscaler: FSR 1.0 and DLSS", 30, OPT, "bold")
text(W - X0, 86, DATE, 16, MUTED, ha="right", family=MONO)
text(X0, 134, "Options > Enhancements > Upscaling draws the world at a fraction of the screen size and scales it back up before the "
     "UI, the text, the cursor and the game's", 14, INK2)
text(X0, 158, "own screen shader, which stay at full resolution. FSR 1.0 runs on every GPU (Windows, Linux, macOS). DLSS Super "
     "Resolution runs on RTX cards with the shim built from the", 14, INK2)
text(X0, 182, "repository (Linux; the release ships no natives), with the camera's and each character's and vehicle's own motion "
     "vectors. Off by default. Measured at 5120x2160, uncapped, RTX 4090 / 9800X3D, driving at 120 km/h.", 14, INK2)
text(X0, 214, "fps mean and frame time p50 / p99 over the whole route, one run per row, the same build; the change is against the "
     "first row. Bars: fps, 0 to 700.", 13, MUTED)

X_MODE = X0
X_B, X_MS = X0 + 900, X0 + 1420
BAR_W, VAL_X = 300, 316
y = TOP
text(X_MODE, y + 30, "MODE", 12.5, MUTED, "semibold")
text(X_B, y + 30, "FPS MEAN", 12.5, MUTED, "semibold")
text(X_MS, y + 30, "P50  /  P99 MS", 12.5, MUTED, "semibold")
text(W - X0, y + 30, "CHANGE", 12.5, MUTED, "semibold", ha="right")
y += HEAD_H
ax.plot([X0, W - X0], [y - 10, y - 10], color="#505058", lw=1)

for i, (mode, note, fps, p50, p99) in enumerate(ROWS):
    cy = y + ROW_H / 2
    better = fps > BASE
    colour = STOCK if i == 0 or not better else OPT
    text(X_MODE, cy - 12, mode, 15.5, INK, "semibold")
    text(X_MODE, cy + 15, note, 11.5, MUTED)
    w = BAR_W * min(fps, FPS_MAX) / FPS_MAX
    ax.add_patch(FancyBboxPatch((X_B, cy - 11), max(w, 3), 22, boxstyle="round,pad=0,rounding_size=4",
                                facecolor=colour, edgecolor="none"))
    text(X_B + VAL_X, cy, f"{fps:.0f}", 19, colour, "semibold" if i else "normal", family=MONO)
    text(X_MS, cy, f"{p50:.1f}  /  {p99:.1f}", 15, INK2, family=MONO)
    if i == 0:
        text(W - X0, cy, "baseline", 14, MUTED, ha="right", family=MONO)
    else:
        text(W - X0, cy, f"{(fps / BASE - 1) * 100:+.0f} %", 17, colour, "semibold", ha="right", family=MONO)
    y += ROW_H
    ax.plot([X0, W - X0], [y, y], color=RULE, lw=0.8)

text(X0, H - PAD - 132, "The GPU stays saturated at 50 %: chunk baking and the composite are not screen-pixel work, so a GPU-bound scene "
     "(fog, thunderstorm, a big town, a laptop) gains about its world pass's share.", 12.5, INK2)
text(X0, H - PAD - 108, "DLSS 4's transformer model costs ~2.5 ms per frame at 5120x2160 output on this card, so at 4K uncapped it is "
     "slower than native; at 1440p and below the cost is a third or less, and it brings back", 12.5, INK2)
text(X0, H - PAD - 84, "1-px detail (power lines, car lettering) that a 50 % spatial upscale loses. Spinning walk through Rosewood, same "
     "build: off 592 fps, FSR 1.0 50 % 802, DLSS 50 % 281, DLSS 67 % 301.", 12.5, INK2)
text(X0, H - PAD - 60, "Options > Enhancements > Upscaling: Upscaler, Upscaler quality (67 / 58 / 50 / 33 / 100 %), Upscaler render scale of your own, "
     "the AMD FSR 1.0 sharpening, the NVIDIA DLSS model preset and sharpening, the temporal motion vectors. Applies on the next launch.", 12.5, INK2)
text(X0, H - PAD - 30, "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, README \"Upscaling: FSR 1.0 and DLSS\" "
     "and docs/plan-upscalers.md, " + DATE + ".", 11.5, MUTED)
fig.savefig(OUT, dpi=100, facecolor=BG)
print(OUT, W, H)
