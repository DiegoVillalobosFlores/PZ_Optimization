#!/usr/bin/env python3
"""Render docs/workshop/images/32-texture-compression.gif: the Workshop's "New! Texture compression on the GPU" card in the
animated New! format (harness/newcard.py). Right half: stock vs this release on the flip laptop
(docs/findings-texcompress-2026-09-25.md). Left half: the main menu's frame rate from the first frame after launch, drawn
as it happened, stock driver compression (run tc-final-driver) against this release's defaults (run tc-stage-128), both
with textureCompression=true, same laptop, same boot; a cursor sweeps the seconds with the two live readouts.

    harness/queue.sh submit media --label texcomp-card-gif --out docs/workshop/images/32-texture-compression.gif \\
        -- python3 harness/texcomp-card-gif.py
    (--still <png>: one frame at 10 s; the layout check)
"""
import csv
import glob
import os
import shutil
import sys
import tempfile
from pathlib import Path

from PIL import ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from newcard import BG, INK, INK2, MUTED, OPT, RING, RULE, STOCK, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/32-texture-compression.gif"
RUNS = os.environ.get("TC_RUNS", os.path.join(os.path.dirname(os.path.abspath(__file__)), "runs"))
STOCK_RUN = sorted(glob.glob(f"{RUNS}/flip-tc-final-driver-*"))[-1:]
NEW_RUN = sorted(glob.glob(f"{RUNS}/flip-tc-stage-128-*"))[-1:]
FPS = 12
T0, SPAN = 3.0, 24.0   # seconds of the boot shown (from the loading-screen hitch to the settled menu)
PLAY, HOLD = 7.0, 2.5  # seconds the cursor takes, seconds held at the end
BIN = 0.5              # fps averaged over half-second bins

INTRO = ("With Texture compression on (the low-end preset and the game's own Steam Deck defaults) the graphics driver "
         "used to compress every texture on the CPU while the menu was being drawn. The GPU does it now, fitted to how "
         "your GPU decodes them: the menu is smooth right after launch and the textures keep more of their detail.")
ROWS = [
    ("Main menu, 4-16 s after launch", "frames per second (capped at 60)",
     (26, "25-28 fps"), (60, "60 fps"), "+130 %"),
    ("Slowest 1 % of menu frames", "frame time, the first 40 s on the menu",
     (46.5, "46.5 ms"), (17.0, "17.0 ms"), "-63 %"),
    ("Render thread compressing", "the whole boot, every compressed texture",
     (26.0, "~26 s"), (0.1, "0.1 s"), "-99 %"),
    ("Game CPU, first 30 s of boot", "core-seconds, all threads",
     (158.8, "159"), (124.9, "125"), "-21 %"),
    ("Texture detail", "screenshot vs uncompressed, zoom 1",
     (37.26, "37.3 dB"), (39.20, "39.2 dB"), "+1.9 dB"),
]
FOOTER = [
    "Left: the main menu's frame rate from the first frame after launch, stock driver compression vs this release, "
    "same laptop and settings (AYANEO Flip, Radeon 890M, Linux/Mesa, 1080p, Texture compression on, menu capped at 60).",
    "On wherever Texture compression is enabled; nothing to set. GPUs with OpenGL 4.3 compress on the GPU, macOS on the "
    "file-loading threads.",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-texcompress-2026-09-25.md.",
]


def fps_series(run):
    """(second, fps) per BIN from the run's overlay log, time from its first frame."""
    rows = list(csv.reader(open(os.path.join(run, "pzopt-overlay.out"))))
    h = rows[0]
    ix = {k: i for i, k in enumerate(h)}
    data = [r for r in rows[1:] if len(r) == len(h)]
    t0 = float(data[0][ix["epoch_ms"]])
    bins = {}
    for r in data:
        b = int((float(r[ix["epoch_ms"]]) - t0) / 1000 / BIN)
        bins.setdefault(b, []).append(float(r[ix["frametime"]]))
    return [((b + 0.5) * BIN, min(1000 * len(ft) / sum(ft), 75)) for b, ft in sorted(bins.items()) if T0 <= b * BIN and (b + 1) * BIN <= SPAN]


def main():
    if not STOCK_RUN or not NEW_RUN:
        sys.exit(f"no flip-tc-final-driver / flip-tc-stage-128 runs under {RUNS}")
    stock, new = fps_series(STOCK_RUN[0]), fps_series(NEW_RUN[0])
    card = Card("New! Texture compression on the GPU", "2026-09-25", INTRO, ROWS, FOOTER)
    x, y, w, h = card.media
    # chart area inside the left slot
    cx0, cx1 = x + 64, x + w - 12
    cy0, cy1 = y + 140, y + h - 60
    ymax = 70.0

    def px(t, f):
        return cx0 + (cx1 - cx0) * (t - T0) / (SPAN - T0), cy1 - (cy1 - cy0) * f / ymax

    still = sys.argv[2] if len(sys.argv) > 2 and sys.argv[1] == "--still" else None
    n = round(PLAY * FPS)
    times = [10.0] if still else [T0 + (SPAN - T0) * (i + 1) / n for i in range(n)] + [SPAN] * round(HOLD * FPS)
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-tc-"))
    ax, lab, big = font(20), font(22, "semibold"), font(40, "bold", mono=True)
    for k, now in enumerate(times):
        im = card.base()
        d = ImageDraw.Draw(im)
        d.rectangle((x, y, x + w - 1, y + h - 1), fill=BG)
        # axes: fps gridlines, seconds along the bottom
        for f in (0, 20, 40, 60):
            _, gy = px(0, f)
            d.line((cx0, gy, cx1, gy), fill=RULE if f else RING, width=1)
            d.text((cx0 - 10, gy), str(f), font=ax, fill=MUTED, anchor="rm")
        for s in (5, 10, 15, 20):
            gx, _ = px(s, 0)
            d.text((gx, cy1 + 20), f"{s} s", font=ax, fill=MUTED, anchor="mm")
        d.text((cx0 - 52, cy0 - 4), "fps", font=ax, fill=MUTED, anchor="lm")
        d.text(((cx0 + cx1) / 2, cy1 + 46), "seconds after launch", font=ax, fill=MUTED, anchor="mm")
        # the two curves up to the cursor
        for series, colour in ((stock, STOCK), (new, OPT)):
            pts = [px(t, f) for t, f in series if t <= now]
            if len(pts) > 1:
                d.line(pts, fill=colour, width=4, joint="curve")
        cur_x, _ = px(now, 0)
        d.line((cur_x, cy0, cur_x, cy1), fill=INK2, width=1)
        # live readouts at the cursor
        for i, (series, colour, name) in enumerate(((stock, STOCK, "STOCK"), (new, OPT, "THIS RELEASE"))):
            shown = [f for t, f in series if t <= now]
            val = f"{shown[-1]:.0f} fps" if shown else "-"
            bx = x + 24 + i * (w // 2)
            d.text((bx, y + 30), name, font=lab, fill=colour, anchor="lm")
            d.text((bx, y + 74), val, font=big, fill=colour if i else STOCK, anchor="lm")
        if still:
            im.save(still)
            print(f"wrote {still}")
            return
        im.save(work / f"{k + 1:04d}.png")
    write_gif(str(work), FPS, OUT)
    shutil.rmtree(work)


if __name__ == "__main__":
    main()
