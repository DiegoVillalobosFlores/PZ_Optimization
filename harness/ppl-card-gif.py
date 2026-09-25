#!/usr/bin/env python3
"""Render docs/workshop/images/31-per-pixel-lighting.gif: the Workshop's "New! Per-pixel lighting" card in the animated
New! format (harness/newcard.py). Right half: what the feature is and what it costs (drift-free A/B against the stock
chunk program on the flip, docs/findings-per-pixel-lighting-2026-09-25.md, runs ppl-r1 / ppl-r3). Left half: the same
frame of the torch-lit house at night with per-pixel lighting off and on, a divider sweeping across it (off left of the
divider, on right of it), holding on each whole picture. The two `--shot-at` captures (runs ppl-shot-false /
ppl-shot-true, bench save, start=8147,11521, zoom 1, 23:00, hand torch) differ only in `pixelLight`.

    harness/queue.sh submit media --label ppl-card-gif --out docs/workshop/images/31-per-pixel-lighting.gif \\
        -- python3 harness/ppl-card-gif.py
    (--still <png>: write one frame with the divider in the middle; the layout check)
"""
import glob
import math
import os
import shutil
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from newcard import BG, INK, OPT, STOCK, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/31-per-pixel-lighting.gif"
RUNS = os.environ.get("PPL_RUNS", os.path.join(os.path.dirname(os.path.abspath(__file__)), "runs"))
SHOT_OFF = sorted(glob.glob(f"{RUNS}/ppl-shot-false-*/shot-game.png"))[-1:]
SHOT_ON = sorted(glob.glob(f"{RUNS}/ppl-shot-true-*/shot-game.png"))[-1:]
CROP = os.environ.get("PPL_CROP", "2310,735,1470")  # centre x, centre y, height of the crop of the 4096x1728 captures: the room, the character, the grass beyond the wall
FPS = 12
SWEEP, HOLD = 2.0, 0.9             # seconds per sweep, seconds on each whole picture

INTRO = ("The world's light drawn per pixel instead of painted into the chunk pictures tile by tile: smooth from tile "
         "to tile and stopping at walls, torch and headlight beams drawn from their cone and following your aim every "
         "frame, lamps and fires shaped per pixel, wet ground glinting in the rain. The game's own lighting still "
         "decides how much light every tile gets.")
ROWS = [
    ("Light between tiles", "stock lights each tile's four corners",
     (None, "blocky steps"), (None, "smooth"), "per pixel"),
    ("Light through walls", "a lit room beside the dark outside",
     (None, "leaks half a tile"), (None, "stops at the wall"), "fixed"),
    ("Torch beam", "its shape as you turn",
     (None, "tile by tile"), (None, "cone, every frame"), "per pixel"),
    ("GPU, night street with a torch", "Radeon 890M laptop, 1080p, per frame",
     (0.647, "0.65 ms"), (0.697, "0.70 ms"), ("+0.05 ms", "worse")),
    ("GPU, torch-lit house", "same laptop, the heaviest scene measured",
     (0.719, "0.72 ms"), (0.872, "0.87 ms"), ("+0.15 ms", "worse")),
]
FOOTER = [
    "Left: the same frame with per-pixel lighting off and on (Rosewood, night, hand torch, zoom 1). Desktop, Linux, "
    "RTX 4090, 5120x2160. GPU rows: the world's chunk pictures drawn with the stock shader and with this one in the same "
    "run, every 2 s.",
    "Off by default (it changes the picture): Options > Enhancements > Per-pixel lighting, applies on the next launch. "
    "Windows and Linux; macOS runs OpenGL 2.1, where it stays off.",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-per-pixel-lighting-2026-09-25.md.",
]


def ease(t):
    return 0.5 - 0.5 * math.cos(math.pi * t)


def divider_positions():
    """Fraction of the width shown off (left of the divider): hold on, sweep to off, hold, sweep back."""
    seq = [0.0] * round(HOLD * FPS)
    n = round(SWEEP * FPS)
    seq += [ease((i + 1) / n) for i in range(n)]
    seq += [1.0] * round(HOLD * FPS)
    seq += [1 - ease((i + 1) / n) for i in range(n)]
    return seq


def main():
    if not SHOT_OFF or not SHOT_ON:
        sys.exit(f"no ppl-shot-false / ppl-shot-true captures under {RUNS}")
    card = Card("New! Per-pixel lighting", "2026-09-25", INTRO, ROWS, FOOTER, cols=("STOCK", "PER-PIXEL"))
    x, y, w, h = card.media
    cx, cy, ch = (int(v) for v in CROP.split(","))
    cw = round(ch * w / h)
    box = (cx - cw // 2, cy - ch // 2, cx - cw // 2 + cw, cy + ch // 2)
    off = Image.open(SHOT_OFF[0]).convert("RGB").crop(box).resize((w, h), Image.LANCZOS)
    on = Image.open(SHOT_ON[0]).convert("RGB").crop(box).resize((w, h), Image.LANCZOS)
    still = sys.argv[2] if len(sys.argv) > 2 and sys.argv[1] == "--still" else None
    frames = [0.5] if still else divider_positions()
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-ppl-"))
    lab = font(24, "bold")
    for k, f in enumerate(frames):
        im = card.base()
        pane = on.copy()
        split = round(w * f)
        if split:
            pane.paste(off.crop((0, 0, split, h)), (0, 0))
        im.paste(pane, (x, y))
        d = ImageDraw.Draw(im)
        if 0 < split < w:
            d.line((x + split, y, x + split, y + h - 1), fill=INK, width=3)
        for text, colour, left, shown in (("STOCK", STOCK, True, split > 0), ("PER-PIXEL", OPT, False, split < w)):
            if not shown:
                continue
            tw = lab.getlength(text)
            tx = x + 14 if left else x + w - 14 - tw
            d.rounded_rectangle((tx - 10, y + 12, tx + tw + 10, y + 50), radius=6, fill=BG)
            d.text((tx, y + 31), text, font=lab, fill=colour, anchor="lm")
        if still:
            im.save(still)
            print(f"wrote {still}")
            return
        im.save(work / f"{k + 1:04d}.png")
    write_gif(str(work), FPS, OUT)
    shutil.rmtree(work)


if __name__ == "__main__":
    main()
