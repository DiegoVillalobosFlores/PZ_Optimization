#!/usr/bin/env python3
"""Animated Workshop thumbnail: the last beat of the showcase (the maintainer's own capture that
backs the results card), tone-mapped, from 25 s on. Default: a square crop centred on the
character (the game camera follows them, so the crop is fixed and the overlay in the top-left
corner stays outside it), "PZ Optimized" on a dark band at the top. GIF_END= turns on the older
zoom-and-pan variant (hold, ease from GIF_START to GIF_END, hold).

  harness/showcase-thumbnail-gif.py [out.gif]
  env: THUMB_SRC (video), GIF_T0 (default 25), GIF_LEN seconds (default 4.4), GIF_SIZE (default 448),
       GIF_FPS (default 12), GIF_START x:y:w (square crop, default 1750:440:1620 = the character centred between the header and the banner),
       GIF_END x:y:w (zoom target; unset = static), GIF_HOLD1 / GIF_ZOOM seconds for the zoom variant
       (default 1.6 / 1.4, the rest of GIF_LEN is the end hold), GIF_COLORS (default 96),
       GIF_DITHER (default none), GIF_MEDIAN (median filter size, 0 = off, default 3),
       GIF_RESAMPLE lanczos|box|hamming|bicubic (the crop's downscale, default lanczos),
       GIF_SCENE_VF (ffmpeg filter chain run over the scene rows only, default
       "hqdn3d=3:2:0:0,bilateral=sigmaS=2:sigmaR=0.03"; empty = none),
       GIF_LOSSY (gifsicle --lossy level, 0 = off, default 100; binary from GIFSICLE or PATH),
       GIF_LABEL (default "PZ Optimized"), GIF_LABEL_POS top|bottom (default top),
       GIF_BANNER x:y:w:h (source rectangle of the overlay pasted live along the bottom, default
       0:0:747:305 = fps / percentiles / loads / verdict / graph; empty = none), GIF_BANNER_SCALE
       (default 0.6, text ~14 px), GIF_BAND (header band height as a fraction of the side, default 0.15),
       GIF_GRAPH_ROW (first source row of the frame graph inside the banner rectangle, default 208),
       GIF_HUD_TOL (tolerance in 8-bit levels under which the header band keeps its previous pixels,
       default 32), GIF_TEXT_TOL (the same for the banner's text rows, default 64: glyph changes are
       ~190 levels and always pass, the panel background behind them is frozen), GIF_GRAPH_EVERY (the
       frame graph strip is redrawn every Nth frame, default 4)

The Steam preview limit is 1 MB; the script prints the size and fails above it. The asphalt
noise is what costs: plain LZW at 512 px is ~145 KB a frame whatever the palette, so a moving
clip needs the median filter (kills the grain, keeps edges), gifsicle's lossy LZW and a modest
size / frame count. ImageMagick's fuzz transparency was tried and ghosts badly on a panning
camera; dither triples the size. Both stay off.

Where the bytes go (2026-09-21, 448 px, 96 colours, lossy 100, per frame): the scene rows 16.4 KB,
the banner's text rows 10.2 KB, the frame graph 5.3 KB, the header band 3.3 KB. The banner and the
band are translucent, so the scrolling game behind them changed every pixel although their own
content is static; and the camera is never still in this clip (block motion search: a steady
(-4,-2) px/frame drift under the opening shot, 4-13 px/frame on the walk), so any temporal hold on
the scene rows turns the low-contrast asphalt into a stale mosaic (SSIM of the scene against the
composited frames fell from 0.957 to 0.915 at a 6-level tolerance, 0.87 at 12; the old 6 fps GIF
scores 0.948). What is done instead, element by element: the banner's text rows and the header
band keep their previous pixels within GIF_TEXT_TOL / GIF_HUD_TOL (bounded against the current
frame; the panel background freezes, the glyphs and the scene under the band still update), the
graph strip is redrawn every GIF_GRAPH_EVERY frames, and the scene rows get a spatial-only denoise
(GIF_SCENE_VF: hqdn3d luma 3 / chroma 2 plus a mild bilateral; 16.4 -> 13.9 KB a frame at 38.2 dB /
SSIM 0.943 vs the old GIF's 38.2 / 0.948 on its own frames; stronger settings, 64-80 colours and
gifski's dither all looked worse). The rest is frame count: at the old per-frame quality 12 fps is
what the limit holds (12 fps = 53 frames, ~987,000 bytes; 15 fps would need the scene at ~10.5 KB a
frame, which is the SSIM-0.86 tier). Steam re-encodes the GIF on its CDN (all frames kept) and shows
it at 268 px on the item page, 448 px behind the enlarge click and at native size where the
description embeds it from GitHub.

The limit Steam enforces is 1,000,000 bytes, not 1 MiB: a 1,011,209-byte file was refused with
"Limit exceeded". The in-game uploader only takes preview.png; scripts/workshop-upload.py sends the GIF
(docs/workshop.md, Images).
"""
import os, subprocess, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(REPO, 'docs/workshop/images/00-showcase-thumbnail.gif')
src = os.environ.get('THUMB_SRC', os.path.expanduser('~/Videos/Project Zomboid/Video_2026-09-20_19-10-46.mp4'))
t0 = float(os.environ.get('GIF_T0', '25'))
total = float(os.environ.get('GIF_LEN', '4.4'))
S = int(os.environ.get('GIF_SIZE', '448'))
fps = int(os.environ.get('GIF_FPS', '12'))
hold1, zoom = float(os.environ.get('GIF_HOLD1', '1.6')), float(os.environ.get('GIF_ZOOM', '1.4'))
x0, y0, w0 = (int(v) for v in os.environ.get('GIF_START', '1750:440:1620').split(':'))
end = os.environ.get('GIF_END')
x1, y1, w1 = (int(v) for v in end.split(':')) if end else (x0, y0, w0)
colors = int(os.environ.get('GIF_COLORS', '96'))
median = int(os.environ.get('GIF_MEDIAN', '3'))
lossy = int(os.environ.get('GIF_LOSSY', '100'))
gifsicle = os.environ.get('GIFSICLE', 'gifsicle')
dither = os.environ.get('GIF_DITHER', 'none')          # none: smallest and no crawling on the game noise
label = os.environ.get('GIF_LABEL', 'PZ Optimized')
label_top = os.environ.get('GIF_LABEL_POS', 'top') != 'bottom'
banner = os.environ.get('GIF_BANNER', '0:0:747:305')
bx, by, bw, bhh = (int(v) for v in banner.split(':')) if banner else (0, 0, 0, 0)
bscale = float(os.environ.get('GIF_BANNER_SCALE', '0.6'))
band_frac = float(os.environ.get('GIF_BAND', '0.15'))
resample = {'lanczos': Image.LANCZOS, 'box': Image.BOX, 'hamming': Image.HAMMING, 'bicubic': Image.BICUBIC}[os.environ.get('GIF_RESAMPLE', 'lanczos')]
scene_vf = os.environ.get('GIF_SCENE_VF', 'hqdn3d=3:2:0:0,bilateral=sigmaS=2:sigmaR=0.03')
graph_row = int(os.environ.get('GIF_GRAPH_ROW', '208'))
hud_tol = int(os.environ.get('GIF_HUD_TOL', '32'))
text_tol = int(os.environ.get('GIF_TEXT_TOL', '64'))
graph_every = int(os.environ.get('GIF_GRAPH_EVERY', '4'))
BLACK = '/usr/share/fonts/noto/NotoSans-Black.ttf'

W, H = 5120, 2160
n = int(round(total * fps))
# the whole clip decoded once, tone-mapped to SDR at source resolution, streamed frame by frame
cmd = ['ffmpeg', '-v', 'error', '-ss', f'{t0}', '-t', f'{total + 0.2}', '-i', src,
       '-vf', f'fps={fps},zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,'
              f'zscale=p=bt709:t=bt709:m=bt709,format=rgb24', '-f', 'rawvideo', '-']
proc = subprocess.Popen(cmd, stdout=subprocess.PIPE)

def ease(u):                       # smoothstep, so the zoom starts and ends gently
    return u * u * (3 - 2 * u)

bh = int(S * band_frac)
band = Image.new('RGBA', (S, S), (0, 0, 0, 0))
ImageDraw.Draw(band).rectangle((0, 0, S, bh) if label_top else (0, S - bh, S, S), fill=(0, 0, 0, 170))
band = band.filter(ImageFilter.GaussianBlur(S // 40))
font = ImageFont.truetype(BLACK, int(S * 0.6 * band_frac))
ly = bh // 2 if label_top else S - bh // 2
# the overlay banner: its own scale (not the crop's), pasted 1:1 along the bottom, no denoise
bsz = (S, int(round(bhh * bscale * S / (bw * bscale)))) if banner else None    # full width, height in proportion
# row layout of the output: [0, band_end) header band over the scene, [band_end, scene_end) scene,
# [scene_end, graph_top) banner text rows, [graph_top, S) frame graph
band_end, scene_end = (bh, S - bsz[1]) if banner else (bh, S)
graph_top = scene_end + int(round(graph_row * bsz[1] / bhh)) if banner else S
if not label_top:
    band_end = 0

frames = []
for i in range(n):
    raw = proc.stdout.read(W * H * 3)
    if len(raw) < W * H * 3:
        break
    t = i / fps
    u = 0.0 if (not end or t < hold1) else 1.0 if t >= hold1 + zoom else ease((t - hold1) / zoom)
    x, y, w = (round(a + (b - a) * u) for a, b in ((x0, x1), (y0, y1), (w0, w1)))
    x, y = max(0, min(W - w, x)), max(0, min(H - w, y))
    full = np.frombuffer(raw, dtype=np.uint8).reshape(H, W, 3)
    im = Image.fromarray(np.ascontiguousarray(full[y:y + w, x:x + w])).resize((S, S), resample)
    if median:
        im = im.filter(ImageFilter.MedianFilter(median))
    im = im.convert('RGBA')
    im = Image.alpha_composite(im, band)
    if banner:
        ov = Image.fromarray(np.ascontiguousarray(full[by:by + bhh, bx:bx + bw])).resize(bsz, Image.LANCZOS)
        im.paste(ov, (0, S - bsz[1]))
    d = ImageDraw.Draw(im)
    d.text((S // 2 + 3, ly + 3), label, font=font, fill=(0, 0, 0, 220), anchor='mm', stroke_width=S // 120, stroke_fill=(0, 0, 0, 220))
    d.text((S // 2, ly), label, font=font, fill=(245, 245, 245), anchor='mm', stroke_width=S // 120, stroke_fill=(0, 0, 0, 255))
    frames.append(np.asarray(im.convert('RGB')))
proc.kill(); proc.wait()
assert len(frames) >= n - 1, f'only {len(frames)} frames decoded'

work = '/tmp/pzopt-thumb-gif'
os.makedirs(work, exist_ok=True)
for f in os.listdir(work):
    os.remove(os.path.join(work, f))

# scene rows: spatial-only denoise (never temporal: the camera is always moving, see the header)
if scene_vf:
    for i, f in enumerate(frames):
        Image.fromarray(f).save(f'{work}/s{i:03d}.png')
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-framerate', str(fps), '-i', f'{work}/s%03d.png',
                    '-vf', f'{scene_vf},format=rgb24', '-start_number', '0', f'{work}/v%03d.png'], check=True)
    for i, f in enumerate(frames):
        filt = np.asarray(Image.open(f'{work}/v{i:03d}.png').convert('RGB'))
        f = f.copy(); f[band_end:scene_end] = filt[band_end:scene_end]; frames[i] = f

# HUD rows: keep the previous pixels within tolerance (exact repeats become transparent under
# gifsicle -O3), the graph strip only every Nth frame; the scene rows are always the truth
prev = None
for i, cur in enumerate(frames):
    if prev is not None:
        d = np.abs(cur.astype(np.int16) - prev.astype(np.int16)).max(axis=2)
        keep = np.zeros(cur.shape[:2], bool)
        keep[:band_end] = d[:band_end] < hud_tol
        if banner:
            keep[scene_end:graph_top] = d[scene_end:graph_top] < text_tol
            if graph_every > 1 and i % graph_every:
                keep[graph_top:] = True
        cur = np.where(keep[..., None], prev, cur)
        frames[i] = cur
    prev = cur
    Image.fromarray(cur).save(f'{work}/f{i:03d}.png')

# one global palette, no dither by default (the game noise would otherwise crawl and triple the size), loop
subprocess.run(['ffmpeg', '-v', 'error', '-y', '-framerate', str(fps), '-i', f'{work}/f%03d.png',
                '-filter_complex', f'split[a][b];[a]palettegen=max_colors={colors}:stats_mode=diff[p];[b][p]paletteuse=dither={dither}:diff_mode=rectangle',
                '-loop', '0', out], check=True)
raw_size = os.path.getsize(out)
# GIF delays are whole centiseconds (12 fps would play at 8 cs = 12.5 fps): per-frame delays from the
# cumulative rounding keep the clip at real time (8,8,9,... for 12 fps)
delays = [round(100 * (i + 1) / fps) - round(100 * i / fps) for i in range(len(frames))]
per_frame = [a for i, d in enumerate(delays) for a in (f'-d{d}', f'#{i}')]
subprocess.run([gifsicle, '-O3'] + ([f'--lossy={lossy}'] if lossy else []) + [out] + per_frame + ['-o', out], check=True)
size = os.path.getsize(out)
print(f'lzw {raw_size // 1024} KB -> lossy {lossy} {size // 1024} KB')
print(f'wrote {out}: {len(frames)} frames, {S}x{S}, {fps} fps, {size} bytes')
if size > 1000000:
    sys.exit(f'{size} bytes is over the 1,000,000-byte Steam preview limit (SubmitItemUpdate says "Limit exceeded"): lower GIF_FPS / GIF_LEN / GIF_SIZE or raise GIF_LOSSY')
