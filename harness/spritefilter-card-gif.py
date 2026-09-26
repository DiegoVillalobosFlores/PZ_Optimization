#!/usr/bin/env python3
"""Render docs/workshop/images/38-sharp-sprites.gif: the Workshop's "New!" card of sprite filtering (pzopt.SpriteFilter,
candidate A) in the animated New! format (harness/newcard.py). Left half: the same spot with stock filtering (top) and
spriteFilter=sharp (bottom) at the same moment, 3x nearest-neighbour close-ups of the game's own 1:1 frames (the pairs
sfv-{walk0.75,walk1.5,drive}-{stock,sharp} of harness/stitch-spritefilter.py, put on one timeline from their route
starts): the card shows at half size on the page, so only a close-up keeps the difference visible. Right half: what
the option does and what it costs (docs/findings-sprite-filter-2026-09-26.md).

    harness/queue.sh submit media --label sprite-card-gif --out docs/workshop/images/38-sharp-sprites.gif \\
        -- python3 harness/spritefilter-card-gif.py
    (--still <png> [--scene N]: one frame, the layout check)
"""
import argparse
import glob
import os
import shutil
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from newcard import BG, INK2, OPT, STOCK, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/38-sharp-sprites.gif"
FPS = 10
SECONDS = 3.2  # per scene
ZOOM = 3

INTRO = ("Sprite filtering, off by default: the game draws the world soft at 75 % zoom and whenever you zoom out "
         "(a blend of two blurred copies of the art, the look while driving). Sharp keeps every texel crisp at any zoom "
         "and reads the one copy that fits when zoomed out, for no GPU time. The clip: the same spot at the same moment, "
         "stock above, sharp below, 3x close-ups.")
ROWS = [
    ("Zoomed in to 75 %", "fine detail, a still frame",
     (5.48, "soft"), (6.29, "crisp"), "+15 %"),
    ("Zoomed out, 175 %", "fine detail, a still frame",
     (22.4, "blended"), (28.9, "crisp"), "+29 %"),
    ("Driving at 120 km/h, 250 %", "fine detail in motion, same shimmer",
     (14.6, "14.6"), (18.3, "18.3"), "+25 %"),
    ("GPU, the world pass", "120 km/h drive, RTX 4090 at 5K",
     (789, "789 µs"), (789, "789 µs"), ("0 %", "same")),
    ("GPU, zoomed out 250 %", "spinning route",
     (910, "910 µs"), (763, "763 µs"), "-16 %"),
]
FOOTER = [
    "Left: the game's own frames (5120x2160, 1:1, then 3x), the same run spot and moment: walking through a Rosewood house at "
    "75 % and 150 % zoom, the 120 km/h drive at 250 %. Detail: RMS of the Laplacian of the picture (higher = sharper).",
    "Off by default: Options > Enhancements > Sprite filtering, applies at once. Windows and Linux (not macOS, OpenGL 2.1). "
    "Characters are 3D models and unchanged.",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-sprite-filter-2026-09-26.md.",
]
# run pair, caption, centre of the close-up in the 1918x1400 capture (a picture on a wall, wainscot and tiles, a storefront)
SCENES = [("sfv-walk0.75", "Zoom 75 %, walking", (700, 430)),
          ("sfv-walk1.5", "Zoom 150 %, walking", (760, 900)),
          ("sfv-drive", "Zoom 250 %, 120 km/h", (800, 1150))]


def run_dir(label):
    runs = sorted(glob.glob(os.path.join(HERE, "runs", label + "-2*")))
    if not runs:
        sys.exit(f"no {label} run under harness/runs")
    return Path(runs[-1])


def capture(d):
    lines = (d / "capture" / "index.txt").read_text().split("\n")
    head = dict(kv.split("=") for kv in lines[0].split())
    w, h = int(head["w"]), int(head["h"])
    stamps = np.array([int(x) for x in lines[1:] if x.strip()])
    raw = np.memmap(d / "capture" / "frames.rgba", np.uint8, "r")
    n = min(len(stamps), raw.size // (w * h * 4))
    sched = dict(l.split("=", 1) for l in (d / "pzopt-schedule.out").read_text().split() if "=" in l)
    route = int(sched["route_start_epoch_ms"])
    return raw[: n * w * h * 4].reshape(n, h, w, 4), (stamps[:n] - route) / 1000.0, w, h


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--still")
    ap.add_argument("--scene", type=int, default=0)
    a = ap.parse_args()
    card = Card("New! Sharp sprites", "2026-09-26", INTRO, ROWS, FOOTER, cols=("STOCK", "THIS RELEASE"))
    x, y, mw, mh = card.media
    ph = (mh - 8) // 2                      # two panes, 8 px apart
    cw, ch = mw // ZOOM, ph // ZOOM         # capture pixels per pane
    lab, cap = font(22, "bold"), font(20)
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-sf-"))
    k = 0
    for si, (label, where, (cx, cy)) in enumerate(SCENES):
        if a.still and si != a.scene:
            continue
        st, sh = capture(run_dir(label + "-stock")), capture(run_dir(label + "-sharp"))
        lo = max(st[1][0], sh[1][0]) + 0.3
        n = int(min(SECONDS, min(st[1][-1], sh[1][-1]) - lo) * FPS)
        h = st[3]
        x0, y0 = cx - cw // 2, cy - ch // 2
        for i in range(n if not a.still else 1):
            t = lo + i / FPS
            im = card.base()
            d = ImageDraw.Draw(im)
            for row, (frames, rel), text, colour in ((0, st[:2], "STOCK", STOCK), (1, sh[:2], "SHARP", OPT)):
                j = int(np.argmin(np.abs(rel - t)))
                f = np.flipud(frames[j][..., :3])[y0:y0 + ch, x0:x0 + cw]
                pane = Image.fromarray(np.ascontiguousarray(f)).resize((cw * ZOOM, ch * ZOOM), Image.NEAREST)
                py = y + row * (ph + 8)
                im.paste(pane, (x, py))
                tw = lab.getlength(text)
                d.rounded_rectangle((x + 4, py + 8, x + 24 + tw, py + 42), radius=6, fill=BG)
                d.text((x + 14, py + 25), text, font=lab, fill=colour, anchor="lm")
            cwid = cap.getlength(where)
            d.rounded_rectangle((x + 4, y + mh - 42, x + 24 + cwid, y + mh - 10), radius=6, fill=BG)
            d.text((x + 14, y + mh - 26), where, font=cap, fill=INK2, anchor="lm")
            if a.still:
                im.save(a.still)
                print(f"wrote {a.still} ({label})")
                return
            k += 1
            im.save(work / f"{k:04d}.png")
        print(f"{label}: route +{lo:.2f} s, {n} frames")
    write_gif(str(work), FPS, OUT, colours=int(os.environ.get("CARD_COLOURS", "96")))
    shutil.rmtree(work)
    print(f"wrote {OUT} ({os.path.getsize(OUT) / 1e6:.1f} MB, {k} frames)")


if __name__ == "__main__":
    main()
