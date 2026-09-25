#!/usr/bin/env python3
"""Render docs/workshop/images/27-ambient-occlusion.gif: the Workshop's "New! Ambient occlusion" card in the animated
New! format (harness/newcard.py). Right half: what the feature is, its cost and the capped drive's frame-time tail
(docs/findings-ambient-occlusion-2026-09-24.md). Left half: the same frame of the Rosewood house with AO off and on,
a divider sweeping across it (off left of the divider, on right of it), holding on each whole picture. The two
`--shot-at` captures line up to the pixel (bench save, `start=8147,11507`, zoom 1, noon): their only difference is
the AO.

    harness/queue.sh submit media --label ao-card-gif --out docs/workshop/images/27-ambient-occlusion.gif ... \\
        -- python3 harness/ao-card-gif.py
    (--still <png>: write one frame with the divider in the middle; the layout check)
"""
import math
import os
import shutil
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from newcard import BG, INK, OPT, STOCK, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/27-ambient-occlusion.gif"
RUNS = os.environ.get("AO_RUNS", "/home/diegov/Documents/ZedProjects/PZ_Optimization-ao/harness/runs")
SHOT_OFF = f"{RUNS}/ao-shot-off2-20260924-161713/shot-game.png"
SHOT_ON = f"{RUNS}/ao-final-on-20260924-213814/shot-game.png"
CROP_CX, CROP_CY, CROP_H = 2700, 1050, 1400   # of the 5120x2160 captures: the small house, its stairs, furniture
FPS = 12
SWEEP, HOLD = 2.0, 0.9             # seconds per sweep, seconds on each whole picture

INTRO = ("Soft shading where surfaces meet: floors darken along the base of walls, in room corners, under and around "
         "furniture, stairs, fences and bushes, so buildings and objects sit on the ground. Computed in 3D from the "
         "depth the game already draws and baked into the chunk pictures, so a frame that draws no new chunk picture "
         "pays nothing and a moving camera pays nothing.")
ROWS = [
    ("GPU time, standing or panning", "nothing is baked, nothing is computed",
     (None, "0"), (None, "0"), ("none", "same")),
    ("GPU time, walking", "Rosewood, 3 tiles a second, 650 fps uncapped",
     (0, "0"), (4.1, "4.1 us/frame"), ("+0.3 %", "worse")),
    ("GPU time, 120 km/h drive", "max zoom: new chunk pictures every frame",
     (0, "0"), (12.9, "13 ms/s"), ("+1.3 %", "worse")),
    ("Capped drive at 240: fps", "drive-120-south, game-thread bound",
     (217.9, "218 fps"), (220.2, "220 fps"), ("=", "same")),
    ("Capped drive: p99 frame", "the objective's tail metric, same runs",
     (14.7, "14.2-14.7 ms"), (14.8, "14.2-14.8 ms"), ("=", "same")),
]
FOOTER = [
    "Left: the same frame with AO off and on (Rosewood, zoom 1, noon). Desktop, Linux, RTX 4090, 5120x2160.",
    "Off by default (it changes the picture): Options > Enhancements > Ambient occlusion, applies on the next launch. "
    "Windows and Linux; macOS runs OpenGL 2.1, where it stays off.",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-ambient-occlusion-2026-09-24.md.",
]


def ease(t):
    return 0.5 - 0.5 * math.cos(math.pi * t)


def divider_positions():
    """Fraction of the width shown with AO off (left of the divider): hold on, sweep to off, hold, sweep back."""
    seq = [0.0] * round(HOLD * FPS)
    n = round(SWEEP * FPS)
    seq += [ease((i + 1) / n) for i in range(n)]
    seq += [1.0] * round(HOLD * FPS)
    seq += [1 - ease((i + 1) / n) for i in range(n)]
    return seq


def main():
    card = Card("New! Ambient occlusion", "2026-09-24", INTRO, ROWS, FOOTER, cols=("AO OFF", "AO ON"))
    x, y, w, h = card.media
    ch = CROP_H
    cw = round(ch * w / h)
    box = (CROP_CX - cw // 2, CROP_CY - ch // 2, CROP_CX - cw // 2 + cw, CROP_CY + ch // 2)
    off = Image.open(SHOT_OFF).convert("RGB").crop(box).resize((w, h), Image.LANCZOS)
    on = Image.open(SHOT_ON).convert("RGB").crop(box).resize((w, h), Image.LANCZOS)
    still = sys.argv[2] if len(sys.argv) > 2 and sys.argv[1] == "--still" else None
    frames = [0.5] if still else divider_positions()
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-ao-"))
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
        for text, colour, left, shown in (("AO OFF", STOCK, True, split > 0), ("AO ON", OPT, False, split < w)):
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
