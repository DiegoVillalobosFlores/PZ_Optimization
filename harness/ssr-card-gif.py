#!/usr/bin/env python3
"""Render docs/workshop/images/35-reflections.gif: the Workshop's "New! Reflections" card (pzopt.Ssr) in the animated
New! format (harness/newcard.py). Left half: the channel below the Sunset bar on the Riverside pier, 15:00, zoom 1, one
run with the reflections switching on and off every 3 s (`--prop devSsrAlternate=3000`, run ssr-card2) taken with the
game's own frame capture (`--prop devCapture=6,12,10,50`: only the game, no desktop); the label follows the console's
alternation clock. Right half: what it is and what it costs (docs/findings-reflections-2026-09-25.md).

    harness/queue.sh submit media --label ssr-card-gif --out docs/workshop/images/35-reflections.gif \\
        -- python3 harness/ssr-card-gif.py   (the release's render: 96 frames, 96 colours)
    (--still <png>: one frame, the layout check; SSR_RUN=<run dir> picks the capture; --crop cx,cy,h in capture px)
"""
import argparse
import glob
import os
import re
import shutil
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from newcard import BG, OPT, STOCK, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/35-reflections.gif"

INTRO = ("Rivers, lakes and puddles mirror the scene: buildings, railings, lamp posts, cars and the people walking by, "
         "rippled by the waves and the rain, sharp where they meet the water and softer further out. Every surface the "
         "game already draws writes itself where its mirror image lands, so the water only looks up one value: nothing "
         "extra runs where there is no water on screen.")
ROWS = [
    ("Water and puddles", "what they reflect",
     (None, "the sky"), (None, "the scene, rippled"), "new"),
    ("Characters and cars", "walking by the water",
     (None, "no reflection"), (None, "reflected"), "new"),
    ("GPU, no water on screen", "RTX 4090, same run on / off",
     (1.245, "1.25 ms"), (1.245, "1.25 ms"), "same"),
    ("GPU, river view", "stock water shader alone: 1.4 ms",
     (4.326, "4.33 ms"), (4.337, "4.34 ms"), ("+0.01 ms", "worse")),
    ("GPU, rain with puddles", "no lightning, same scene",
     (4.687, "4.69 ms"), (4.716, "4.72 ms"), ("+0.03 ms", "worse")),
]
FOOTER = [
    "Left: the channel below the Sunset bar on the Riverside pier, 15:00, zoom 1, reflections switching off and on every "
    "3 s in one run. Desktop, Linux, RTX 4090, 5120x2160.",
    "Off by default (it changes the picture): Options > Enhancements > Reflections, applies at the next launch; strength "
    "and puddles apply at once. Windows and Linux; macOS runs OpenGL 2.1, where it stays off.",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-reflections-2026-09-25.md.",
]


def run_dir():
    if os.environ.get("SSR_RUN"):
        return Path(os.environ["SSR_RUN"])
    runs = sorted(glob.glob(os.path.join(HERE, "runs", "ssr-card2-*")))
    if not runs:
        sys.exit("no ssr-card2 run under harness/runs (SSR_RUN=<dir>)")
    return Path(runs[-1])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--crop", default="1330,470,370", help="centre x, centre y (top-down), height in capture px")
    ap.add_argument("--still")
    ap.add_argument("--frame", type=int, default=40, help="the capture frame of --still")
    ap.add_argument("--frames", type=int, default=96, help="capture frames in the GIF (96: on, off, on; under ~8 MB)")
    a = ap.parse_args()
    run = run_dir()
    con = (run / "console.txt").read_text(errors="replace")
    m = re.search(r"alternating every (\d+) ms from epoch_ms (\d+)", con)
    period, t0 = int(m.group(1)), int(m.group(2))
    idx = (run / "capture" / "index.txt").read_text().split()
    w, h = (int(v.split("=")[1]) for v in idx[:2])
    stamps = [int(v) for v in idx[2:]]
    raw = np.memmap(run / "capture" / "frames.rgba", np.uint8, "r").reshape(-1, h, w, 4)
    fps = (len(stamps) - 1) * 1000.0 / (stamps[-1] - stamps[0])
    card = Card("New! Reflections", "2026-09-26", INTRO, ROWS, FOOTER, cols=("STOCK", "THIS RELEASE"))
    x, y, mw, mh = card.media
    cx, cy, ch = (int(v) for v in a.crop.split(","))
    cw = round(ch * mw / mh)
    box = (cx - cw // 2, cy - ch // 2, cx - cw // 2 + cw, cy - ch // 2 + ch)
    lab = font(24, "bold")
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-ssr-"))
    n = 1 if a.still else min(len(stamps), raw.shape[0], a.frames)
    for k in range(n):
        k2 = a.frame if a.still else k
        pane = Image.fromarray(np.ascontiguousarray(np.flipud(raw[k2][..., :3]))).crop(box).resize((mw, mh), Image.LANCZOS)
        on = ((stamps[k2] - t0) // period) % 2 == 0
        im = card.base()
        im.paste(pane, (x, y))
        d = ImageDraw.Draw(im)
        text, colour = ("REFLECTIONS ON", OPT) if on else ("REFLECTIONS OFF (STOCK)", STOCK)
        tw = lab.getlength(text)
        d.rounded_rectangle((x + 14 - 10, y + 12, x + 14 + tw + 10, y + 50), radius=6, fill=BG)
        d.text((x + 14, y + 31), text, font=lab, fill=colour, anchor="lm")
        if a.still:
            im.save(a.still)
            print(f"wrote {a.still} (frame {k2}, {'on' if on else 'off'})")
            return
        im.save(work / f"{k + 1:04d}.png")
    print(f"{n} frames at {fps:.1f} fps from {run.name}")
    write_gif(str(work), round(fps), OUT, colours=int(os.environ.get("SSR_COLOURS", "96")))
    shutil.rmtree(work)
    print(f"wrote {OUT} ({os.path.getsize(OUT) / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
