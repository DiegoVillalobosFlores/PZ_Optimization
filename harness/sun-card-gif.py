#!/usr/bin/env python3
"""Render docs/workshop/images/33-let-there-be-light.gif: the Workshop's "New! Let There Be Light" card (sun and contact
shadows) in the animated New! format (harness/newcard.py). Left half: the horde-shoot scene at Riverside (bench
`horde-shoot`, Jev shooting a horde from the pier) at 16:30, one recorded run with sun shadows switching off and on
every 4 s (`--prop devSunTogglePeriod=4000`, run cs-card-horde): the label follows the console's "dev toggle" lines.
Right half: what it is and what it costs (docs/findings-contact-shadows-2026-09-25.md).

    harness/queue.sh submit media --label sun-card-gif --out docs/workshop/images/33-let-there-be-light.gif \\
        -- env SUN_FPS=7 python3 harness/sun-card-gif.py --start 23.9 --len 8.8 --crop 2560,1330,1200
    (the release's render: on, off, on in 8.8 s, 8.4 MB; the source's encoder noise and the water's shimmer are
    denoised in time first, else every pixel changes every frame: 25.8 MB for 16 s at 10 fps)
    (--still <png>: one frame, the layout check; SUN_RUN=<run dir> picks the recording)
"""
import argparse
import glob
import os
import re
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
from newcard import BG, OPT, STOCK, Card, font, write_gif  # noqa: E402
from hdrvideo import pq_to_nits  # noqa: E402

OUT = "docs/workshop/images/33-let-there-be-light.gif"
FPS = int(os.environ.get("SUN_FPS", "8"))
REF_NITS = 203.0  # SDR white in the PQ capture

INTRO = ("Soft shadows of the sun, baked into the world's pictures at no cost while you look around: walls, trees, "
         "fences and furniture shade the ground and each other, sharp where they touch and softer further away, turning "
         "and lengthening through the day. Every character and car casts the shadow of its own body, onto walls too, "
         "and at night zombies in a torch beam or in headlights throw long shadows away from the light.")
ROWS = [
    ("Sun shadows", "walls, trees, fences, furniture",
     (None, "none"), (None, "soft, follow the sun"), "new"),
    ("Characters and cars", "their shadow on the ground",
     (None, "a blob"), (None, "their own body"), "new"),
    ("Torches and headlights", "zombies in the beam",
     (None, "no shadow"), (None, "long shadows"), "new"),
    ("GPU, 120 km/h drive", "RTX 4090, 240 cap, new chunks streaming",
     (2.476, "2.48 ms"), (2.509, "2.51 ms"), ("+0.03 ms", "worse")),
    ("Frame time p99, same drive", "7 runs, same scene",
     (8.99, "8.99 ms"), (8.92, "8.92 ms"), "same"),
]
FOOTER = [
    "Left: the horde-shoot scene at the Riverside pier, 16:30, zoom 1: one run, sun shadows switching off and on every "
    "4 s. Desktop, Linux, RTX 4090, 5120x2160. A crowd of 70-233 zombies on screen costs 0.01 ms a frame.",
    "Off by default (it changes the picture): Options > Enhancements > Sun shadows, applies at once; strength, softness, "
    "characters, vehicles and torch shadows each have a setting. Windows and Linux; macOS runs OpenGL 2.1, where it stays off.",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-contact-shadows-2026-09-25.md.",
]


def run_dir():
    if os.environ.get("SUN_RUN"):
        return Path(os.environ["SUN_RUN"])
    runs = sorted(glob.glob(os.path.join(HERE, "runs", "cs-card-horde-*")))
    if not runs:
        sys.exit("no cs-card-horde run under harness/runs (SUN_RUN=<dir>)")
    return Path(runs[-1])


def toggles(run):
    """[(epoch s, on)] from the console's dev toggle lines."""
    out = []
    for m in re.finditer(r"sun shadows: dev toggle (on|off) at epoch_ms (\d+)", (run / "console.txt").read_text(errors="replace")):
        out.append((int(m.group(2)) / 1000.0, m.group(1) == "on"))
    return out


def rec_start(run):
    """The capture's first frame as epoch s: the file's mtime minus its duration (as harness/smooth-card-gif.py)."""
    rec = run / "recording.mp4"
    dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(rec)],
                               capture_output=True, text=True).stdout.strip())
    return rec.stat().st_mtime - dur


def state_at(tog, epoch):
    on = None
    for t, s in tog:
        if t <= epoch:
            on = s
    return on


def frames(run, start, length, crop, w, h):
    """Tone-mapped RGB frames (w x h) of the recording from `start` s, `length` s at FPS: PQ -> nits -> SDR white 1.0."""
    cx, cy, ch = crop
    cw = round(ch * w / h)
    # temporal denoise: the capture's encoder noise and the water's shimmer changed every pixel of every frame, which the
    # GIF's frame differencing cannot keep small (25.8 MB for 16 s at 10 fps, the first render)
    dn = os.environ.get("SUN_DENOISE", "hqdn3d=3:3:14:14")
    vf = (f"fps={FPS},crop={cw}:{ch}:{cx - cw // 2}:{cy - ch // 2}" + (f",{dn}" if dn else "")  # (on the source's YUV: hqdn3d takes no RGB)
          + f",scale={w}:{h}:in_color_matrix=bt2020:in_range=tv:out_range=pc:flags=area,format=rgb48le")
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-ss", f"{start:.3f}", "-t", f"{length:.3f}", "-i", str(run / "recording.mp4"),
                          "-vf", vf, "-f", "rawvideo", "-"], capture_output=True, check=True).stdout
    n = len(raw) // (w * h * 6)
    f = np.frombuffer(raw[:n * w * h * 6], dtype="<u2").reshape(n, h, w, 3).astype(np.float32) / 65535.0
    m = np.array([[1.6605, -0.5876, -0.0728], [-0.1246, 1.1329, -0.0083], [-0.0182, -0.1006, 1.1187]], np.float32)
    out = []
    for i in range(n):
        lin = np.clip((pq_to_nits(f[i]) / REF_NITS) @ m.T, 0.0, 1.0)
        srgb = np.where(lin <= 0.0031308, lin * 12.92, 1.055 * np.power(lin, 1 / 2.4) - 0.055)
        out.append(Image.fromarray((srgb * 255.0 + 0.5).astype(np.uint8)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--start", type=float, default=-1.0, help="seconds into the recording (default: the second toggle)")
    ap.add_argument("--len", type=float, default=16.0)
    ap.add_argument("--crop", default=os.environ.get("SUN_CROP", "2560,1080,1500"), help="centre x, centre y, height in capture px")
    ap.add_argument("--lag", type=float, default=0.25, help="s the picture lags a toggle (the re-bake)")
    ap.add_argument("--still")
    a = ap.parse_args()
    run = run_dir()
    tog = toggles(run)
    if not tog:
        sys.exit(f"no dev toggle lines in {run}/console.txt")
    t0 = rec_start(run)
    start = a.start if a.start >= 0 else max(0.0, tog[min(2, len(tog) - 1)][0] - t0 - 0.5)
    card = Card("New! Let There Be Light", "2026-09-25", INTRO, ROWS, FOOTER, cols=("STOCK", "THIS RELEASE"))
    x, y, w, h = card.media
    crop = tuple(int(v) for v in a.crop.split(","))
    clip = frames(run, start, 1.0 / FPS if a.still else a.len, crop, w, h)
    lab = font(24, "bold")
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-sun-"))
    for k, pane in enumerate(clip):
        on = state_at(tog, t0 + start + k / FPS - a.lag)
        im = card.base()
        im.paste(pane, (x, y))
        d = ImageDraw.Draw(im)
        text, colour = ("SHADOWS ON", OPT) if on else ("SHADOWS OFF (STOCK)", STOCK)
        tw = lab.getlength(text)
        d.rounded_rectangle((x + 14 - 10, y + 12, x + 14 + tw + 10, y + 50), radius=6, fill=BG)
        d.text((x + 14, y + 31), text, font=lab, fill=colour, anchor="lm")
        if a.still:
            im.save(a.still)
            print(f"wrote {a.still} (recording {start:.1f} s, {'on' if on else 'off'})")
            return
        im.save(work / f"{k + 1:04d}.png")
    print(f"{len(clip)} frames from {start:.1f} s of {run.name}, toggles at "
          + ", ".join(f"{t - t0:.1f}{'+' if s else '-'}" for t, s in tog[:12]))
    write_gif(str(work), FPS, OUT, colours=int(os.environ.get("SUN_COLOURS", "96")))
    shutil.rmtree(work)
    print(f"wrote {OUT} ({os.path.getsize(OUT) / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
