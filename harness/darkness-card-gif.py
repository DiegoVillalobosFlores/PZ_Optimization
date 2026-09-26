#!/usr/bin/env python3
"""Render docs/workshop/images/37-darkness-memory-grading.gif: the Workshop's "New!" card of the darkness floor, remembered
places and colour grading (pzopt.Darkness / pzopt.Grade) in the animated New! format (harness/newcard.py). Left half:
every visual Enhancements option off vs on (ambient occlusion, sun shadows, reflections, per-pixel lighting, the darkness
floor, remembered places, colour grading) in three scenes, each a matched pair of runs captured in-game
(`--prop devCapture=13,12,12,50`: only the game, no desktop) and put on one timeline from their route starts, so the
cut from off to on keeps the moment: the Rosewood house at 16:00, the same house at 01:00 with a hand torch, the
Riverside pier at 15:00 (runs card-{day,night,water}-{off,on}). Right half: what the three new options are and what they
cost (docs/findings-darkness-grading-2026-09-26.md). HDR output and upscaling are not in it: the GIF is SDR, and the
upscaler changes the speed, not the look.

    harness/queue.sh submit media --label darkness-card-gif --out docs/workshop/images/37-darkness-memory-grading.gif \\
        -- python3 harness/darkness-card-gif.py
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

OUT = "docs/workshop/images/37-darkness-memory-grading.gif"
FPS = 10
OFF_S, ON_S = 1.5, 2.5  # per scene: the stock look, then everything on

INTRO = ("Three new looks, all off by default: a darkness floor, so rooms and nights you have already seen keep a faint "
         "moonlit light instead of a black void (never-seen places stay black); remembered places, where what you "
         "cannot see right now turns grey and dim instead of dark; and colour grading by hour and weather, with the "
         "eye's blue-green night vision after dark. The clip shows every Enhancements option on against all off.")
ROWS = [
    ("Rooms and nights you have seen", "no light at all",
     (None, "black"), (None, "a dim moonlit floor"), "new"),
    ("Out of sight", "behind walls, behind you",
     (None, "a little darker"), (None, "grey, remembered"), "new"),
    ("Colour by hour and weather", "night, dusk, rain, fog, snow",
     (None, "one look"), (None, "graded"), "new"),
    ("GPU, the screen pass", "grading on, RTX 4090 at 5K",
     (100.4, "100 µs"), (71.7, "72 µs"), "-29 %"),
    ("GPU, the view-cone pass", "remembered places on",
     (200.7, "201 µs"), (193.5, "194 µs"), "-4 %"),
]
FOOTER = [
    "Left: every visual Enhancements option off, then on (ambient occlusion, sun shadows, reflections, per-pixel lighting "
    "and the three new ones), the game's own frames: Rosewood at 16:00, at 01:00 with a torch, the Riverside pier at 15:00. "
    "HDR output and upscaling are not shown (SDR GIF; the upscaler changes speed, not the look).",
    "Off by default: Options > Enhancements > Darkness, remembered places and colour grading, applies at once. Windows and "
    "Linux; macOS (OpenGL 2.1) has the darkness floor only.",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-darkness-grading-2026-09-26.md.",
]
SCENES = [("card-day", "Rosewood, 16:00"), ("card-night", "Rosewood, 01:00, hand torch"), ("card-water", "Riverside pier, 15:00")]


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
    ap.add_argument("--crop-h", type=int, default=720, help="crop height in capture px (50 %% capture: 1080 high)")
    a = ap.parse_args()
    card = Card("New! Darkness, memory, colour", "2026-09-26", INTRO, ROWS, FOOTER, cols=("STOCK", "THIS RELEASE"))
    x, y, mw, mh = card.media
    lab, cap = font(24, "bold"), font(20)
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-dark-"))
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
            text, colour = ("ALL ENHANCEMENTS ON", OPT) if is_on else ("ENHANCEMENTS OFF (STOCK LOOK)", STOCK)
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
