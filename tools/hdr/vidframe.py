#!/usr/bin/env python3
"""One frame of an AV1 HDR capture as SDR-viewable PNGs: <out>.ev0.png (reference white = SDR white) and <out>.ev-2.png.
  vidframe.py <recording.mp4|run dir> <seconds> <out-prefix> [--ref 505] [--width 2560] [--crop x,y,w,h (in output px)]"""
import argparse, os, subprocess
import numpy as np
from PIL import Image
from hdrvideo import pq_to_nits
ap = argparse.ArgumentParser()
ap.add_argument("path"); ap.add_argument("t", type=float); ap.add_argument("out")
ap.add_argument("--ref", type=float, default=505); ap.add_argument("--width", type=int, default=2560); ap.add_argument("--crop")
a = ap.parse_args()
p = os.path.join(a.path, "recording.mp4") if os.path.isdir(a.path) else a.path
w0, h0 = map(int, subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0", p], capture_output=True, text=True).stdout.strip().split(",")[:2])
w = a.width; h = int(round(h0 * w / w0 / 2)) * 2
raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-ss", str(a.t), "-i", p, "-frames:v", "1", "-vf", f"scale={w}:{h}:in_color_matrix=bt2020:in_range=tv:out_range=pc:flags=area,format=rgb48le", "-f", "rawvideo", "-"], capture_output=True, check=True).stdout
f = np.frombuffer(raw, dtype="<u2").reshape(h, w, 3).astype(np.float32) / 65535.0
nits2020 = pq_to_nits(f) * a.ref / 203.0
m = np.array([[1.6605, -0.5876, -0.0728], [-0.1246, 1.1329, -0.0083], [-0.0182, -0.1006, 1.1187]])  # BT.2020 -> BT.709
nits = np.clip(nits2020 @ m.T, 0, None)
if a.crop:
    x, y, cw, ch = map(int, a.crop.split(",")); nits = nits[y:y + ch, x:x + cw]
for ev, name in ((1, "ev0"), (4, "ev-2")):
    Image.fromarray((np.clip(nits / a.ref / ev, 0, 1) ** (1 / 2.2) * 255 + 0.5).astype(np.uint8)).save(f"{a.out}.{name}.png")
print(f"{a.out}: max {nits.max():.0f} nits, mean {nits.mean():.1f}")
