#!/usr/bin/env python3
"""Render docs/workshop/images/39-sun-moon-clouds.gif: the Workshop's "New!" card of the real sky (pzopt.Sky, the moon
shadows, pzopt.CloudShadow, the far-field low-sun shadows) in the animated New! format (harness/newcard.py). Left half:
the stock look against this release with sun shadows on, three scenes, each a matched pair of runs captured in-game
(`--prop devCapture=13,10,12,50`) and put on one timeline from their route starts: the Rosewood church at 16:00 under
50 % clouds drifting at 8x speed, the same place at 19:30 (a 13 deg sun: long shadows), and at 23:00 under the full moon
of 1993-07-03 (runs card-sky-{clouds,evening,moon}-{off,on}). Right half: what is new and what the cloud shadows cost
(docs/findings-sky-2026-09-26.md).

    harness/queue.sh submit media --label sky-card-gif --out docs/workshop/images/39-sun-moon-clouds.gif \\
        -- python3 harness/sky-card-gif.py
    (--still <png> [--scene N] [--on]: one frame, the layout check)
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

OUT = "docs/workshop/images/39-sun-moon-clouds.gif"
FPS = 10
OFF_S, ON_S = 1.5, 3.0  # per scene: the stock look, then this release

INTRO = ("With sun shadows on, the sun and the moon now stand where they really would over Kentucky for the game's date "
         "and hour: high and short-shadowed at summer noon, long soft shadows at dawn and dusk, and at night the moon "
         "casts shadows as strong as its phase allows. Clouds cast soft shadows that drift with the wind and follow the "
         "weather's cloud cover, over the ground, walls, roofs, water and characters.")
ROWS = [
    ("Sun and moon", "for the game's date and hour",
     (None, "no sun shadows"), (None, "the real sky"), "new"),
    ("Cloud shadows", "the weather's cover, the wind",
     (None, "none"), (None, "soft, drifting"), "new"),
    ("Dawn and dusk", "the sun under 12 degrees",
     (None, "none"), (None, "long soft shadows"), "new"),
    ("Moonlit nights", "full moon to crescent",
     (None, "none"), (None, "moon shadows"), "new"),
    ("GPU, the chunk composite", "clouds on, RTX 4090 at 5K",
     (632.0, "632 µs"), (623.0, "623 µs"), "±0 %"),
]
FOOTER = [
    "Left: the game's own frames, stock look, then this release with sun shadows on: Rosewood at 16:00 under 50 % clouds "
    "(drifting at 8x speed for the clip), at 19:30, and at 23:00 under a full moon.",
    "Off by default: Options > Enhancements > Sun, moon and cloud shadows. Windows and Linux (not macOS, OpenGL 2.1). With "
    "sun shadows off the game's shaders stay untouched.",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-sky-2026-09-26.md.",
]
SCENES = [("card-sky-clouds", "Rosewood, 16:00, clouds (8x speed)"), ("card-sky-evening", "Rosewood, 19:30, low sun"),
          ("card-sky-moon", "Rosewood, 23:00, full moon")]


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
    ap.add_argument("--on", action="store_true")
    ap.add_argument("--crop-h", type=int, default=1080, help="crop height in capture px (50 %% capture: 1080 high)")
    a = ap.parse_args()
    card = Card("New! Sun, moon, clouds", "2026-09-26", INTRO, ROWS, FOOTER, cols=("STOCK", "THIS RELEASE"))
    x, y, mw, mh = card.media
    lab, cap = font(24, "bold"), font(20)
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-sky-", dir=os.path.expanduser("~/.cache")))
    k = 0
    for si, (label, where) in enumerate(SCENES):
        if a.still and si != a.scene:
            continue
        off, on = capture(run_dir(label + "-off")), capture(run_dir(label + "-on"))
        lo = max(off[1][0], on[1][0]) + 0.1
        span = min(off[1][-1], on[1][-1]) - lo
        n = int(min(OFF_S + ON_S, span) * FPS)
        w, h = off[2], off[3]
        ch = min(a.crop_h, h)
        cw = round(ch * mw / mh)
        box = (w // 2 - cw // 2, h // 2 - ch // 2, w // 2 - cw // 2 + cw, h // 2 - ch // 2 + ch)  # the player is at the centre
        for i in range(n):
            t = lo + i / FPS
            is_on = a.on if a.still else i >= OFF_S * FPS
            frames, rel = (on if is_on else off)[:2]
            j = int(np.argmin(np.abs(rel - t)))
            pane = Image.fromarray(np.ascontiguousarray(np.flipud(frames[j][..., :3]))).crop(box).resize((mw, mh), Image.LANCZOS)
            im = card.base()
            im.paste(pane, (x, y))
            d = ImageDraw.Draw(im)
            text, colour = ("SUN, MOON AND CLOUD SHADOWS", OPT) if is_on else ("STOCK LOOK", STOCK)
            tw = lab.getlength(text)
            d.rounded_rectangle((x + 4, y + 12, x + 24 + tw, y + 50), radius=6, fill=BG)
            d.text((x + 14, y + 31), text, font=lab, fill=colour, anchor="lm")
            cwid = cap.getlength(where)
            d.rounded_rectangle((x + 4, y + mh - 46, x + 24 + cwid, y + mh - 12), radius=6, fill=BG)
            d.text((x + 14, y + mh - 29), where, font=cap, fill=INK2, anchor="lm")
            if a.still:
                im.save(a.still)
                print(f"wrote {a.still} ({label}, {'on' if is_on else 'off'})")
                return
            k += 1
            im.save(work / f"{k:04d}.png")
        print(f"{label}: route +{lo:.2f} s, {n} frames ({off[1].size} / {on[1].size} captured)")
    write_gif(str(work), FPS, OUT, colours=int(os.environ.get("CARD_COLOURS", "96")))
    shutil.rmtree(work)
    print(f"wrote {OUT} ({os.path.getsize(OUT) / 1e6:.1f} MB, {k} frames)")


if __name__ == "__main__":
    main()
