#!/usr/bin/env python3
"""Render docs/media/workshop-mods-comparison.png: the Workshop performance mods vs this build on the
three uncapped routes (docs/archive/2026-09-24/results.md, 2026-09-21). Table form with an inline fps bar per route, in
the media style of docs/media-style.md (the showcase video's results card): near-black surface, Noto
Sans, numbers in Noto Sans Mono, stock in amber, this build in green, everything else de-emphasised."""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

OUT = "docs/media/workshop-mods-comparison.png"
# docs/media-style.md, SDR column
BG, PANEL, RULE, INK, INK2, MUTED = "#0b0b0e", "#111114", "#26262c", "#f0f0f4", "#b4b4b8", "#8a8a90"
STOCK, OPT, DEEMPH = "#c9592b", "#44de7c", "#3a3a40"
plt.rcParams["font.family"] = ["Noto Sans", "DejaVu Sans", "sans-serif"]
MONO = "Noto Sans Mono"

# name, subscribers, nature, (fps, p99) x drive / storm / spin, note
ROWS = [
    ("Stock game", "", "the shipped game", (166.7, 15.5), (74.6, 58.7), (135.4, 27.2), ""),
    ("Project Zomboid Optimiser", "23 k", "Lua toggles, F10 control centre", (159.0, 15.6), (71.7, 66.2), (127.8, 29.5), ""),
    ("   + PZO-Launcher engine jar", "", "agent jar, native lib, JVM flags", (165.3, 15.5), (72.2, 65.7), (128.6, 28.5), ""),
    ("Tempo", "42 k", "Lua sampler, menu memo", (160.0, 15.3), (70.8, 61.0), (130.0, 27.9), ""),
    ("   + its class shadows", "", "chunk-finalize budget, 3D-zombie cap", (162.1, 15.3), (75.7, 56.9), (130.5, 30.3), ""),
    ("Multi-Cpu Enhance", "28 k", "launcher JSON: ParallelGC, 8 GB heap", (169.4, 15.2), (73.8, 61.9), (135.9, 28.7), "300-350 ms GC stall in every route"),
    ("Every Texture Optimized", "616 k", "6,142 re-encoded textures", (163.1, 15.2), (72.3, 63.3), (134.6, 28.8), ""),
    ("Lugli - Optimizations", "3 k", "ZombieBuddy: wind gate, z-extents", (162.0, 15.3), (72.8, 60.0), (135.2, 28.0), ""),
    ("Zed's Better FPS (42.20 fix)", "47 k", "ZombieBuddy: GL state, sprite batching", (161.4, 15.2), (74.5, 59.0), (134.3, 27.7), ""),
    ("PZ_Optimization", "", "class overrides (this repo)", (481.2, 8.8), (245.6, 13.8), (456.2, 8.5), ""),
]
ROUTES = ["120 km/h drive", "120 km/h drive, thunderstorm", "Rosewood spin"]
FPS_MAX = 500.0

W, ROW_H, HEAD_H, TOP, BOT, PAD = 2100, 58, 96, 150, 100, 40
H = TOP + HEAD_H + ROW_H * len(ROWS) + BOT + PAD
fig = plt.figure(figsize=(W / 100, H / 100), dpi=100)
fig.patch.set_facecolor(BG)
ax = fig.add_axes([0, 0, 1, 1]); ax.set_xlim(0, W); ax.set_ylim(H, 0); ax.axis("off")
ax.add_patch(FancyBboxPatch((PAD, PAD), W - 2 * PAD, H - 2 * PAD, boxstyle="round,pad=0,rounding_size=10",
                            facecolor=PANEL, edgecolor="#505058", linewidth=0.8, alpha=1.0))

def text(x, y, s, size=15, color=INK, weight="normal", ha="left", va="center", family=None):
    ax.text(x, y, s, fontsize=size, color=color, fontweight=weight, ha=ha, va=va, family=family)

X0 = PAD + 48
text(X0, 82, "Project Zomboid Build 42 performance mods, measured on the same routes", 22, INK, "semibold")
text(X0, 120, "Uncapped, 5120x2160, one mod at a time on the stock game, in-game overlay log over the route window; "
     "fps mean and p99 frame time. One run per cell, 2026-09-21.", 13.5, INK2)

X_MOD, X_SUB, X_WHAT = X0, X0 + 422, X0 + 512
X_R = [X0 + 870, X0 + 1250, X0 + 1630]; BAR_W = 165; VAL_X = 178
y = TOP
text(X_MOD, y + 30, "MOD", 12.5, MUTED, "semibold")
text(X_SUB, y + 30, "SUBS", 12.5, MUTED, "semibold")
text(X_WHAT, y + 30, "WHAT IT IS", 12.5, MUTED, "semibold")
for xr, name in zip(X_R, ROUTES):
    text(xr, y + 18, name.upper(), 12.5, MUTED, "semibold")
    text(xr, y + 44, "fps mean   /   p99 ms", 11.5, MUTED)
y += HEAD_H
ax.plot([X0, W - X0], [y - 10, y - 10], color="#505058", lw=1)

for name, subs, what, *cells, note in ROWS:
    ours = name == "PZ_Optimization"; stock = name == "Stock game"
    colour = OPT if ours else STOCK if stock else DEEMPH
    num_colour = OPT if ours else STOCK if stock else INK
    cy = y + ROW_H / 2
    text(X_MOD, cy, name, 15, INK, "semibold" if (ours or stock) else "normal")
    text(X_SUB, cy, subs, 14, INK2, family=MONO)
    text(X_WHAT, cy, what, 13.5, INK2)
    for xr, (fps, p99) in zip(X_R, cells):
        w = BAR_W * fps / FPS_MAX
        ax.add_patch(FancyBboxPatch((xr, cy - 9), w, 18, boxstyle="round,pad=0,rounding_size=4",
                                    facecolor=colour, edgecolor="none"))
        text(xr + VAL_X, cy, f"{fps:.0f}", 15, num_colour, "semibold" if ours else "normal", family=MONO)
        text(xr + VAL_X + 60, cy, f"/  {p99:.1f}", 13.5, INK2, family=MONO)
    if note:
        text(X_WHAT, cy + 20, note, 11, STOCK)
    y += ROW_H
    ax.plot([X0, W - X0], [y, y], color=RULE, lw=0.8)

text(X0, H - PAD - 42, "Bars: fps mean, same scale on every route (0 to 500). Every mod is within run-to-run noise of stock; "
     "Multi-Cpu Enhance's ParallelGC adds a 300-350 ms stop-the-world pause per route (stock G1 max 21 ms).", 12, INK2)
text(X0, H - PAD - 20, "Per-mod details and every number: docs/archive/2026-09-24/results.md, 2026-09-21 sections.", 11.5, MUTED)
fig.savefig(OUT, dpi=100, facecolor=BG)
print(OUT, W, H)
