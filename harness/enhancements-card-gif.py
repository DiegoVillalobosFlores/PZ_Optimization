#!/usr/bin/env python3
"""Render docs/workshop/images/29-enhancements-tab.gif: the Workshop's "New! Enhancements tab" card in the animated New!
format (harness/newcard.py). Right half: what moved to the tab and that it applies without a restart (2026-09-25).
Left half: a running game (run enh-live-card: the harness's live_set rig does what Apply does at 4, 8 and 13 s into the
route) - ambient occlusion switched on, its floor / wall / object strengths raised to 150 %, switched off - with a label
naming the change in force, cropped around the player in a Rosewood house.

    harness/queue.sh submit media --label enh-card-gif --out docs/workshop/images/29-enhancements-tab.gif ... \\
        -- python3 harness/enhancements-card-gif.py
    (--still <png>: one frame of the second phase; the layout check)
"""
import glob
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from newcard import BG, INK, OPT, STOCK, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/29-enhancements-tab.gif"
RUNS = os.environ.get("ENH_RUNS", "/home/diegov/Documents/ZedProjects/PZ_Optimization-enh/harness/runs")
FPS = 6
START, END = 1.0, 16.0  # seconds into the route shown
CROP_W = 1200  # capture pixels across the media box (5120x2160), centred on the player
TONEMAP = ('zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,'
           'tonemap=hable,zscale=p=bt709:t=bt709:m=bt709')
# (from s into the route, label, colour): what Apply has set by then
PHASES = [(0.0, "Ambient occlusion off", STOCK),
          (4.0, "Apply: ambient occlusion on", OPT),
          (8.0, "Apply: strength 150 % on floors, walls, objects", OPT),
          (13.0, "Apply: ambient occlusion off", STOCK)]

INTRO = ("Upscaling, HDR output and ambient occlusion have their own Options tab now, every setting with before / after "
         "preview clips, and each one applies the moment you press Apply: no restart. Ambient occlusion strength is set "
         "on its own for floors, walls, objects and vegetation.")
ROWS = [
    ("Upscaler, AO, HDR sliders", "what a change takes",
     (None, "restart"), (None, "Apply"), "no restart"),
    ("DLSS on or off, preset", "in a running game, 5120x2160",
     (None, "restart"), (None, "next frame"), "live"),
    ("AO strength settings", "floors, walls, objects, vegetation",
     (1, "1"), (4, "4"), "4x"),
    ("Preview clips", "native vs FSR / DLSS, SDR vs HDR, AO off / on",
     (None, "drive clip"), (None, "7 pairs"), "new"),
]
FOOTER = [
    "Left: a running game; Apply changed the settings at the marked moments (ambient occlusion on, strengths raised to "
    "150 %, off again) and the chunk pictures redrew within a few frames. RTX 4090, 5120x2160.",
    "Options > Enhancements. Switching HDR output itself on or off still applies on the next launch (on Linux it picks "
    "the game window).",
    "Source and runs: github.com/xD3I/PZ_Optimization (runs enh-live-*).",
]


def run_dir(label):
    dirs = sorted(glob.glob(os.path.join(RUNS, label + "-2*")))
    if not dirs:
        raise SystemExit(f"no run {label} under {RUNS}")
    return dirs[-1]


def route_start(d):
    """Video seconds of the route start: the recording starts at launch (schedule.log says when the route did)."""
    for line in open(os.path.join(d, "schedule.log")):
        if "route starts at +" in line:
            return float(line.rsplit("+", 1)[1].split()[0])
    raise SystemExit(f"{d}: no route start in schedule.log")


def frames(video, t0, dur, w, h):
    ch = round(CROP_W * h / w)
    x0, y0 = 2560 - CROP_W // 2, max(0, min(2160 - ch, 1080 - ch // 2))
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-ss", f"{t0:.3f}", "-t", f"{dur:.3f}", "-i", video, "-vf",
                          f"fps={FPS},{TONEMAP},crop={CROP_W}:{ch}:{x0}:{y0},scale={w}:{h}:flags=lanczos,format=rgb24",
                          "-f", "rawvideo", "-"], capture_output=True, check=True).stdout
    size = w * h * 3
    for k in range(len(raw) // size):
        yield Image.frombytes("RGB", (w, h), raw[k * size:(k + 1) * size])


def phase(t):
    cur = PHASES[0]
    for p in PHASES:
        if t >= p[0]:
            cur = p
    return cur


def main():
    card = Card("New! Enhancements tab", "2026-09-25", INTRO, ROWS, FOOTER, cols=("BEFORE", "THIS RELEASE"))
    x, y, w, h = card.media
    still = sys.argv[2] if len(sys.argv) > 2 and sys.argv[1] == "--still" else None
    d = run_dir("enh-live-card")
    rs = route_start(d)
    t_first = 5.0 if still else START
    lab = font(22, "bold")
    tag = font(18, "semibold")
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-enh-"))
    n = 0
    for k, im_media in enumerate(frames(os.path.join(d, "recording.mp4"), rs + t_first, 0.2 if still else END - START, w, h)):
        t = t_first + k / FPS
        _, text, colour = phase(t)
        im = card.base()
        im.paste(im_media, (x, y))
        dr = ImageDraw.Draw(im)
        tw = lab.getlength(text)
        dr.rounded_rectangle((x + 10, y + 10, x + 30 + tw, y + 48), radius=6, fill=BG)
        dr.text((x + 20, y + 29), text, font=lab, fill=colour, anchor="lm")
        note = "no restart"
        nw = tag.getlength(note)
        dr.rounded_rectangle((x + w - 30 - nw, y + h - 44, x + w - 10, y + h - 10), radius=6, fill=BG)
        dr.text((x + w - 20 - nw, y + h - 27), note, font=tag, fill=INK, anchor="lm")
        if still:
            im.save(still)
            print(f"wrote {still}")
            shutil.rmtree(work)
            return
        im.save(work / f"{k + 1:04d}.png")
        n += 1
    write_gif(str(work), FPS, OUT)
    shutil.rmtree(work)
    print(f"{n} frames")


if __name__ == "__main__":
    main()
