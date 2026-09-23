#!/usr/bin/env python3
"""Render docs/media/profiler-card.png: the Workshop's "New! Better profiling" section as one image
(the description embeds only the picture, see the release-windows skill). Same media style as
harness/mac-table.py (docs/media-style.md): near-black surface, Noto Sans, the date in Noto Sans Mono,
green for what is new. The picture is the full in-game overlay, cropped from a recorded run
(docs/media/profiler-overlay-full.png: harness/runs/gtq-steady-20260921-233647, the uncapped Rosewood
spin at 5120x2160, overlayFont=Large).

    python3 harness/profiler-card.py
    ffmpeg -y -i docs/media/profiler-card.png -vf scale=1920:-1 -q:v 3 docs/workshop/images/13-profiler.jpg
"""
from PIL import Image, ImageDraw, ImageFont

OUT = "docs/media/profiler-card.png"
SHOT = "docs/media/profiler-overlay-full.png"
DATE = "2026-09-22"
BG, PANEL, RING, INK, INK2, MUTED, OPT = "#0b0b0e", "#111114", "#505058", "#f0f0f4", "#b4b4b8", "#8a8a90", "#44de7c"
FONTS = "/usr/share/fonts/noto/"


def font(name, size):
    return ImageFont.truetype(FONTS + name, size)

TITLE, BODY, NOTE, MONO = font("NotoSans-Bold.ttf", 60), font("NotoSans-Regular.ttf", 28), font("NotoSans-Regular.ttf", 26), font("NotoSansMono-Regular.ttf", 32)

shot = Image.open(SHOT).convert("RGB")
W = 2100
PAD, X0 = 40, 88
inner = W - 2 * X0
scale = inner / shot.width
shot = shot.resize((inner, round(shot.height * scale)), Image.LANCZOS)

LINES = [
    (BODY, INK2, "The F9 overlay now says what the game thread is doing, not only how busy it is: a tree of phases and"),
    (BODY, INK2, "sub-phases (chunk bakes, zombies, UI draw, the wait for the render thread...) with the hottest methods, a flame"),
    (BODY, INK2, "graph of the last 5 seconds, and a frame-time graph with axes. The verdict line names the two biggest items."),
    (NOTE, MUTED, "Every element is a dropdown under Options > Profiler (off, or its own options); the profile is also logged per"),
    (NOTE, MUTED, "run for the analysis scripts. Uncapped Rosewood spin at 5120x2160 on an RTX 4090; the whole panel at the Large font."),
]
TOP = 170
y_text = TOP
for f, _, _ in LINES:
    y_text += f.size + 10
SHOT_Y = y_text + 28
FOOT = [
    "Left: frame statistics, the game-thread tree (bars = share of the time, waits in red), the verdict and the frame graph.",
    "Right: the flame graph, root at the bottom, width = share of the time; update green, render blue, lighting amber, pzopt frames magenta.",
]
H = SHOT_Y + shot.height + 18 + len(FOOT) * (NOTE.size + 8) + 24 + PAD

im = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(im)
d.rounded_rectangle((PAD, PAD, W - PAD, H - PAD), radius=10, fill=PANEL, outline=RING, width=1)
d.text((X0, 66), "New! Better profiling", font=TITLE, fill=OPT)
d.text((W - X0, 84), DATE, font=MONO, fill=MUTED, anchor="ra")
y = TOP
for f, colour, text in LINES:
    d.text((X0, y), text, font=f, fill=colour)
    y += f.size + 10
d.rounded_rectangle((X0 - 1, SHOT_Y - 1, X0 + shot.width, SHOT_Y + shot.height), radius=4, outline=RING, width=1)
im.paste(shot, (X0, SHOT_Y))
fy = SHOT_Y + shot.height + 18
for line in FOOT:
    d.text((X0, fy), line, font=NOTE, fill=MUTED)
    fy += NOTE.size + 8
im.save(OUT)
print(OUT, im.size)
