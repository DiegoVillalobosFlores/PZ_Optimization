#!/usr/bin/env python3
"""Render docs/workshop/images/28-hdr-output.gif: the Workshop's HDR card in the animated New! format
(harness/newcard.py), replacing the still 24-hdr.jpg. Right half: what HDR output does, the numbers of the still card
(docs/findings-hdr-2026-09-24.md) and the 2026-09-25 fix. Left half: the HDR showcase reel
(docs/media/hdr-showcase-sdr-vs-hdr.mp4: stock SDR and HDR of the same scene side by side, AV1 PQ) - fires and the torch,
headlights, a lightning strike - with a divider sweeping between the stock pane and the HDR pane. An SDR GIF cannot show
light above white, so both panes are decoded to nits and mapped with one exposure that puts the HDR peak at the GIF's
white: the SDR game's white lands at ~63 % grey, the HDR highlights above it (2.7x white in this reel).

    harness/queue.sh submit media --label hdr-card-gif --out docs/workshop/images/28-hdr-output.gif ... \\
        -- python3 harness/hdr-card-gif.py
    (--still <png>: one frame with the divider in the middle; the layout check)
"""
import math
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "tools", "hdr"))
from hdrvideo import pq_to_nits  # noqa: E402
from newcard import BG, INK, OPT, STOCK, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/28-hdr-output.gif"
REEL = os.environ.get("HDR_REEL", "/home/diegov/Documents/ZedProjects/PZ_Optimization/docs/media/hdr-showcase-sdr-vs-hdr.mp4")
PANE_W, PANE_H = 1280, 1080        # each half of the reel, decoded at 2560x1080
TOP_BAR = 70                       # the reel's pane labels ("STOCK (SDR)", "HDR (pzopt)") are cut off
PEAK = 600.0                       # capture nits mapped to the GIF's white (the reel's HDR peak; SDR white is ~220)
FPS = 10
SWEEP, HOLD = 1.1, 0.7             # seconds per divider sweep, seconds on each whole pane
# (start s, duration s, crop centre x in the pane, 0..1): fires + torch, headlights, the storm with a strike at 29.1 s
SEGMENTS = [(1.5, 5.0, 0.50), (10.5, 4.0, 0.50), (28.3, 3.5, 0.50)]

INTRO = ("On an HDR screen the menus and the HUD stay at your desktop's white, while lamps, torches, headlights, fires, "
         "sun on water and lightning go above it, each in its own colour. The game knows where its lights are: the "
         "brightness comes from them. New in this release: indoors by day, lamps and windows no longer flare up when "
         "you face one way.")
ROWS = [
    ("Night: brightest light", "torch, street lamps, fires; brightest 0.1 %",
     (142, "142 nits"), (475, "475 nits"), "3.3x"),
    ("Clear day: highlights", "sun glints on the river at 3 pm",
     (350, "350 nits"), (529, "529 nits"), "1.5x"),
    ("Thunderstorm", "lightning every few seconds; brightest 0.1 %",
     (138, "138 nits"), (268, "268 nits"), "1.9x"),
    ("Indoors by day, turning", "by facing; the last release swung 6-13 nits",
     (None, "steady"), (None, "6-7 nits"), ("fixed", "better")),
    ("Frame rate", "uncapped spinning bench, 5120x2160",
     (289, "289 fps"), (285, "285 fps"), ("-1 %", "worse")),
]
FOOTER = [
    "Left: stock SDR and the HDR output of the same scene (fires and torch, headlights, lightning), both shown darker on "
    "this SDR image so the light above white fits. RTX 4090, 5120x2160 HDR panel (1307 nits), KDE Plasma 6.",
    "On by itself on an HDR screen: Linux with HDR on in a Wayland desktop with colour management, and Macs with an XDR "
    "display. SDR screens are unchanged. Options > Optimizations > HDR output.",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-hdr-2026-09-24.md.",
]


def ease(t):
    return 0.5 - 0.5 * math.cos(math.pi * t)


def divider(n_frames):
    """Fraction of the width showing the stock pane, per frame: hold on HDR, sweep to stock, hold, sweep back."""
    cycle = [0.0] * round(HOLD * FPS)
    n = round(SWEEP * FPS)
    cycle += [ease((i + 1) / n) for i in range(n)]
    cycle += [1.0] * round(HOLD * FPS)
    cycle += [1 - ease((i + 1) / n) for i in range(n)]
    return [cycle[i % len(cycle)] for i in range(n_frames)]


def to_display(nits):
    """Capture nits (BT.2020) -> sRGB 8-bit with the HDR peak at white: one exposure for both panes."""
    m = np.array([[1.6605, -0.5876, -0.0728], [-0.1246, 1.1329, -0.0083], [-0.0182, -0.1006, 1.1187]])  # 2020 -> 709
    lin = np.clip((nits @ m.T) / PEAK, 0, 1)
    srgb = np.where(lin <= 0.0031308, 12.92 * lin, 1.055 * np.power(lin, 1 / 2.4) - 0.055)
    return (srgb * 255 + 0.5).astype(np.uint8)


def segment_frames(start, dur):
    """Every frame of the segment at FPS as (stock pane, HDR pane) nits arrays, one ffmpeg decode."""
    w, h = 2 * PANE_W, PANE_H
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-ss", str(start), "-t", str(dur), "-i", REEL, "-vf",
                          f"fps={FPS},scale={w}:{h}:in_color_matrix=bt2020:in_range=tv:out_range=pc:flags=area,format=rgb48le",
                          "-f", "rawvideo", "-"], capture_output=True, check=True).stdout
    size = w * h * 3 * 2
    for k in range(len(raw) // size):
        f = np.frombuffer(raw[k * size:(k + 1) * size], dtype="<u2").reshape(h, w, 3).astype(np.float32) / 65535.0
        nits = pq_to_nits(f)
        yield nits[:, :PANE_W], nits[:, PANE_W:]


def main():
    card = Card("New! HDR output (Linux, macOS)", "2026-09-25", INTRO, ROWS, FOOTER, cols=("STOCK (SDR)", "HDR"))
    x, y, w, h = card.media
    ch = PANE_H - TOP_BAR
    cw = min(PANE_W, round(ch * w / h))
    still = sys.argv[2] if len(sys.argv) > 2 and sys.argv[1] == "--still" else None
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-hdr-"))
    lab = font(24, "bold")
    total = 0
    frames = []
    for start, dur, cx in SEGMENTS:
        left = int(min(max(cx * PANE_W - cw / 2, 0), PANE_W - cw))
        for sdr, hdr in segment_frames(start, 0.1 if still else dur):
            crop = lambda n: Image.fromarray(to_display(n[TOP_BAR:TOP_BAR + ch, left:left + cw])).resize((w, h), Image.LANCZOS)
            frames.append((crop(sdr), crop(hdr)))
            if still:
                break
        if still:
            break
    splits = [0.5] if still else divider(len(frames))
    for k, ((sdr, hdr), f) in enumerate(zip(frames, splits)):
        im = card.base()
        pane = hdr.copy()
        split = round(w * f)
        if split:
            pane.paste(sdr.crop((0, 0, split, h)), (0, 0))
        im.paste(pane, (x, y))
        d = ImageDraw.Draw(im)
        if 0 < split < w:
            d.line((x + split, y, x + split, y + h - 1), fill=INK, width=3)
        for text, colour, is_left, shown in (("STOCK (SDR)", STOCK, True, split > 0), ("HDR", OPT, False, split < w)):
            if not shown:
                continue
            tw = lab.getlength(text)
            tx = x + 14 if is_left else x + w - 14 - tw
            d.rounded_rectangle((tx - 10, y + 12, tx + tw + 10, y + 50), radius=6, fill=BG)
            d.text((tx, y + 31), text, font=lab, fill=colour, anchor="lm")
        if still:
            im.save(still)
            print(f"wrote {still}")
            return
        im.save(work / f"{k + 1:04d}.png")
        total += 1
    write_gif(str(work), FPS, OUT)
    shutil.rmtree(work)


if __name__ == "__main__":
    main()
