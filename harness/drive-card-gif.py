#!/usr/bin/env python3
"""Render docs/workshop/images/36-fluid-driving.gif: the Workshop's "New! Fluid driving" card (vehicles drawn between
the physics steps, the driving camera placed after the car moved; docs/findings-car-jitter-2026-09-26.md) in the
animated New! format (harness/newcard.py).

Left half: the presented frames of two flip runs on the same stretch (devCapture at 120 fps, zoom 0.5, 60 km/h east
of Rosewood), stock above and this release below, each a fixed window of the screen around the car so its jumps show
against the frame edge and a fixed cross-hair, played 4x slower than real time (every captured frame shown: at real
speed the jumps are 8 ms apart, too fast for a GIF). Right half: the flip numbers, judged at the refresh each frame was
shown on (harness/drivejitter.py --present).

    harness/queue.sh submit media --label drive-card-gif --out docs/workshop/images/36-fluid-driving.gif \\
        -- python3 harness/drive-card-gif.py --stock <run> --new <run>
    (--still <png>: one frame, the layout check; --seconds S: how much of the capture, default 0.6 (the moving road
    compresses badly: 1.6 s with 385 px panels was 44 MB); the chart under the panels traces the car's jump off its
    path frame by frame from each run's drive log)
"""
import argparse
import os
import sys
import tempfile

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from newcard import BG, INK2, OPT, STOCK, Card, font, write_gif  # noqa: E402
import drivejitter as dj  # noqa: E402

OUT = "docs/workshop/images/36-fluid-driving.gif"
SLOW = 4  # 120 fps captured, shown at 30 fps

INTRO = ("The car no longer stutters or rubber-bands while you drive. Car physics runs in 10 ms steps and the game drew "
         "the car, its driver and the camera at the last whole step; on top, the camera could be placed before the car "
         "moved, so the car jumped back and forth on screen. Now every frame draws them between the steps, the "
         "simulation untouched.")
ROWS = [
    ("World judder", "how far the scrolling world is off its smooth path",
     (2.94, "2.94 px"), (0.83, "0.83 px"), "-72 %"),
    ("Car jumping on screen", "the car's own wobble, RMS",
     (5.38, "5.38 px"), (1.00, "1.00 px"), "-81 %"),
    ("Car moving backwards", "share of frames",
     (25.3, "25.3 %"), (1.4, "1.4 %"), "-24 pts"),
    ("Frames with no motion", "the world stands still for a frame",
     (10.9, "10.9 %"), (1.9, "1.9 %"), "-9 pts"),
    ("Zoomed in (50 %)", "world judder, open road, 120 km/h",
     (10.46, "10.46 px"), (0.93, "0.93 px"), "-91 %"),
]
FOOTER = [
    "AYANEO Flip, 120 Hz with vsync, Linux, driving at 60 km/h through Rosewood at zoom 100 %, the pan-while-driving "
    "camera on; each frame judged at the refresh it was shown on. Left: the presented frames at zoom 50 %, 4x slow "
    "motion, the same stretch of road. With a 60 fps cap the car's wobble went 12.7 -> 0.9 px.",
    "On by default: Options > Optimizations > Driving smoothness. Single player; the simulation is unchanged.",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-car-jitter-2026-09-26.md.",
]


def load_capture(run):
    d = os.path.join(run, "capture")
    lines = open(os.path.join(d, "index.txt")).read().split("\n")
    head = dict(kv.split("=") for kv in lines[0].split())
    w, h = int(head["w"]), int(head["h"])
    n = len([x for x in lines[1:] if x.strip()])
    raw = np.memmap(os.path.join(d, "frames.rgba"), dtype=np.uint8, mode="r")
    n = min(n, raw.size // (w * h * 4))
    return raw[: n * w * h * 4].reshape(n, h, w, 4), w, h


def car_centre(run, w, h):
    """The car's usual spot on the captured frames: the rig's offscreen px / zoom = screen px, times the capture scale."""
    rows = [r for r in dj.load(run) if abs(r["kmh"]) > 40]
    sc = w / 1920.0  # capture scale (the flip's screen is 1920 wide in landscape)
    xs = sorted(r["scr_x"] / (r["zoom"] or 1.0) * sc for r in rows)
    ys = sorted(r["scr_y"] / (r["zoom"] or 1.0) * sc for r in rows)
    return xs[len(xs) // 2], ys[len(ys) // 2]


def car_offsets(run, n, skip_s=6.0):
    """The car's on-screen offset from its smooth path along its motion (px, signed), n frames from skip_s into the
    moving part of the drive log: what the eye sees as the car jumping back and forth."""
    rows = [r for r in dj.load(run) if abs(r["kmh"]) > 40]
    t = [r["t_us"] / 1e6 for r in rows]
    xs = [r["scr_x"] / (r["zoom"] or 1.0) for r in rows]
    ys = [r["scr_y"] / (r["zoom"] or 1.0) for r in rows]
    rx, _ = dj.quadfit_residuals(t, xs, 0.08)
    ry, _ = dj.quadfit_residuals(t, ys, 0.08)
    wx, wy = [r["cam_x"] for r in rows], [r["cam_y"] for r in rows]
    i0 = next(i for i in range(len(t)) if t[i] - t[0] >= skip_s)
    out = []
    for i in range(i0, i0 + n):
        dx, dy = wx[i + 1] - wx[i - 1], wy[i + 1] - wy[i - 1]  # the motion direction on screen (the camera's)
        l = (dx * dx + dy * dy) ** 0.5 or 1.0
        v = (rx[i] * dx + ry[i] * dy) / l
        out.append(0.0 if v != v else v)
    return out


def chart(d, box, series, upto, colours):
    """The two offset traces, drawn up to frame `upto`: stock's saw-tooth against this release's flat line."""
    x0, y0, w, h = box
    d.rounded_rectangle((x0, y0, x0 + w, y0 + h), radius=6, outline="#26262c", width=1)
    d.text((x0 + 10, y0 + 16), "the car's jump off its path, screen px, frame by frame", font=font(18), fill=INK2, anchor="lm")
    top = y0 + 34
    mid = top + (h - 44) / 2
    span = max(1.0, max(abs(v) for s in series for v in s))
    scale = (h - 54) / 2 / span
    d.line((x0 + 8, mid, x0 + w - 8, mid), fill="#26262c", width=1)
    for tick in (-10, 10):
        if abs(tick) <= span:
            y = mid - tick * scale
            d.text((x0 + w - 8, y), f"{tick:+d}", font=font(16, mono=True), fill="#8a8a90", anchor="rm")
            d.line((x0 + 8, y, x0 + w - 40, y), fill="#1c1c22", width=1)
    n = len(series[0])
    for s, c in zip(series, colours):
        pts = [(x0 + 8 + (w - 56) * k / (n - 1), mid - s[k] * scale) for k in range(min(upto + 1, n))]
        if len(pts) > 1:
            d.line(pts, fill=c, width=3)


def panel(frames, i, cx, cy, pw, ph, fh):
    f = frames[min(i, len(frames) - 1)]
    x0 = int(max(0, min(f.shape[1] - pw, cx - pw / 2)))
    y0 = int(max(0, min(fh - ph, cy - ph / 2)))
    img = Image.fromarray(np.ascontiguousarray(f[::-1][y0:y0 + ph, x0:x0 + pw, :3]))
    return img, (cx - x0, cy - y0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--stock", required=True)
    ap.add_argument("--new", required=True)
    ap.add_argument("--seconds", type=float, default=0.6)
    ap.add_argument("--panel-h", type=int, default=230)
    ap.add_argument("--colours", type=int, default=128)
    ap.add_argument("--skip", type=float, default=0.3, help="seconds of the capture left out at its start")
    ap.add_argument("--still")
    ap.add_argument("--out", default=OUT)
    a = ap.parse_args()
    card = Card("New! Fluid driving", "2026-09-26", INTRO, ROWS, FOOTER)
    mx, my, mw, mh = card.media
    label_h = 34
    ph = a.panel_h
    caps = []
    for run in (a.stock, a.new):
        frames, w, h = load_capture(run)
        cx, cy = car_centre(run, w, h)
        caps.append((frames, cx, cy, h))
    n = int(a.seconds * 120)
    start = int(a.skip * 120)
    traces = [car_offsets(run, n) for run in (a.stock, a.new)]
    cy0 = my + 2 * (ph + label_h + 12)
    chart_box = (mx, cy0, mw - 1, my + mh - cy0)
    lab = font(22, "semibold")
    small = font(18)
    todo = [start + n // 2] if a.still else range(start, start + n)
    tmp = tempfile.mkdtemp()
    for k, i in enumerate(todo):
        im = card.base()
        d = ImageDraw.Draw(im)
        for j, (frames, cx, cy, fh) in enumerate(caps):
            y = my + j * (ph + label_h + 12)
            colour, text = (STOCK, "STOCK") if j == 0 else (OPT, "THIS RELEASE")
            d.text((mx, y + label_h // 2), text, font=lab, fill=colour, anchor="lm")
            d.text((mx + mw, y + label_h // 2), f"{SLOW}x slow motion", font=small, fill=INK2, anchor="rm")
            img, (px, py) = panel(frames, i, cx, cy, mw, ph, fh)
            im.paste(img, (mx, y + label_h))
            # a fixed cross-hair on the car's usual spot: the car should stay under it
            ox, oy = mx + px, y + label_h + py
            for (x1, y1, x2, y2) in ((ox - 14, oy, ox - 4, oy), (ox + 4, oy, ox + 14, oy), (ox, oy - 14, ox, oy - 4), (ox, oy + 4, ox, oy + 14)):
                d.line((x1, y1, x2, y2), fill="#ffffff", width=2)
            d.rectangle((mx, y + label_h, mx + mw - 1, y + label_h + ph - 1), outline=colour, width=2)
        chart(d, chart_box, traces, k if not a.still else n - 1, (STOCK, OPT))
        if a.still:
            im.save(a.still)
            print("wrote", a.still)
            return
        im.save(os.path.join(tmp, f"{k:04d}.png"))
    write_gif(tmp, 120 // SLOW, a.out, colours=a.colours)


if __name__ == "__main__":
    main()
