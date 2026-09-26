#!/usr/bin/env python3
"""The sprite-filter showcase (2026-09-26, candidate A, docs/findings-sprite-filter-2026-09-26.md): three scenes, stock
filtering (left) beside `spriteFilter=sharp` (right) from matched pairs of runs, each first at 1:1 screen pixels, then a
2x nearest-neighbour close-up of the pane centre (so the difference survives a downscaled viewer), then a results card.

Pairs (harness/runs/<label>-*): sfv-walk0.75-stock/-sharp and sfv-walk1.5-stock/-sharp (the player walking south from
the Rosewood house spot, clear day, zombies off), sfv-drive-stock/-sharp (drive-120-south at the widest zoom). The source
is the game's own presented frames, a 1:1 crop round the player (pzopt.FrameCapture,
`--prop devCapture=<start>,<secs>,<fps>,100,crop=1601:380:1918:1400,ram`): nothing is resampled on the way, so the
panes show exactly the pixels the two filters produced. Both runs of a pair are put on one timeline measured from their
route start (pzopt-schedule.out; every captured frame has its epoch ms), the second shifted by the offset whose frames
match the first's best (the route start stamps differ by up to a second of movement).

Layout per docs/media-style.md: 3840x1800, 60 fps, header band (title + pane labels), panes 1918x1400, a caption strip;
AV1 10-bit PQ / BT.2020, SDR mapped to PQ at 203 nits, text in PQ code values; the poster .jpg the only tone-mapped
derivative.

Usage: harness/stitch-spritefilter.py <out.mp4>     (a queued media job: harness/queue.sh submit media ...)
"""
import glob
import os
import subprocess
import sys

import importlib.util

import numpy as np

_spec = importlib.util.spec_from_file_location('st', os.path.join(os.path.dirname(__file__), 'showcase-times.py'))
st = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(st)

OUT = sys.argv[1] if len(sys.argv) > 1 else 'docs/media/sprite-filter-stock-vs-sharp.mp4'
CARD = 8.0
W, H = 3840, 1800
HEAD = 150
PW, PH = 1918, 1400  # pane = the captured crop, 1:1; divider 4 px
CAP_Y = HEAD + PH

FONT_B = '/usr/share/fonts/noto/NotoSans-Bold.ttf'
FONT_R = '/usr/share/fonts/noto/NotoSans-Regular.ttf'
FONT_M = '/usr/share/fonts/noto/NotoSansMono-Regular.ttf'
WHITE, GREY, AMBER, GREEN = '0xB4B4B8', '0x8A8A90', '0xB88A40', '0x5CB878'

# label, title, caption, explanation, seconds at 1:1, seconds of the close-up
SCENES = [
    ('sfv-walk0.75', 'ZOOMED IN, 75 %', 'the game blurs this zoom level with a linear filter',
     'Sharp: every texel stays a hard square; only the one screen pixel a texel edge crosses is blended, by how much of it the texel covers.', 4.5, 4.0),
    ('sfv-walk1.5', 'ZOOMED OUT, 150 %', 'the game blends a half-size and a quarter-size copy of the art',
     'Sharp: the one copy that fits the zoom, two samples spread as far as the zoom needs, so fences and brickwork stay crisp without shimmer.', 4.5, 4.0),
    ('sfv-drive', 'DRIVING AT 120 KM/H, ZOOMED OUT 250 %', 'the widest zoom, where the game looks softest',
     'Sharp: 25 % more fine detail in motion, with no more crawl or shimmer per unit of detail than the game\'s own blur.', 3.4, 3.4),
]


def run_dir(label):
    runs = sorted(glob.glob(f'harness/runs/{label}-2*'))
    if not runs:
        raise SystemExit(f'no run {label}')
    return runs[-1]


def capture(d):
    """(frames as (n, h, w, 4) with bottom-up rows; epoch ms per frame; w; h; route start epoch ms)."""
    c = os.path.join(d, 'capture')
    lines = open(os.path.join(c, 'index.txt')).read().split('\n')
    head = dict(kv.split('=') for kv in lines[0].split())
    w, h = int(head['w']), int(head['h'])
    stamps = np.array([int(x) for x in lines[1:] if x.strip()])
    raw = np.memmap(os.path.join(c, 'frames.rgba'), dtype=np.uint8, mode='r')
    n = min(len(stamps), raw.size // (w * h * 4))
    route = int(st.kv(os.path.join(d, 'pzopt-schedule.out'))['route_start_epoch_ms'])
    return raw[:n * w * h * 4].reshape(n, h, w, 4), stamps[:n], w, h, route


def fps_of(stamps):
    return 60 if np.median(np.diff(stamps)) < 25 else 30


def pane(d, name, t0, n, fps, zoom2):
    """<run>/<name>.mkv: n frames at fps from t0 s after the route start (nearest captured frame), lossless; zoom2 = the
    centre half of the crop, each pixel repeated 2x2 (nearest neighbour: the close-up shows real pixels)."""
    frames, stamps, w, h, route = capture(d)
    if (w, h) != (PW, PH):
        raise SystemExit(f'{d}: capture is {w}x{h}, expected the {PW}x{PH} crop')
    rel = (stamps - route) / 1000.0
    out = os.path.join(d, name + '.mkv')
    ff = subprocess.Popen(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{PW}x{PH}',
                           '-r', str(fps), '-i', '-', '-c:v', 'ffv1', '-pix_fmt', 'gbrp', out], stdin=subprocess.PIPE)
    for i in range(n):
        j = int(np.argmin(np.abs(rel - (t0 + i / fps))))
        f = np.ascontiguousarray(frames[j, :, :, :3][::-1])  # rows are bottom-up
        if zoom2:
            y0, x0 = (PH - PH // 2) // 2, (PW - PW // 2) // 2
            f = f[y0:y0 + PH // 2, x0:x0 + PW // 2].repeat(2, axis=0).repeat(2, axis=1)
            f = np.pad(f, ((0, PH - f.shape[0]), (0, PW - f.shape[1]), (0, 0)))
        ff.stdin.write(np.ascontiguousarray(f).tobytes())
    ff.stdin.close()
    if ff.wait() != 0:
        raise SystemExit(f'{d}: pane encode failed')
    return out


def small(frames, j):
    """A 16x-reduced luma of captured frame j (for the alignment)."""
    f = frames[j, ::16, ::16, :3].astype(np.float32)
    return f[..., 0] * 0.299 + f[..., 1] * 0.587 + f[..., 2] * 0.114


def align(a, b):
    """The time offset (s) to add to run b's route clock so its frames show what run a's show: the route start stamps
    of two runs differ by up to a second of walking or driving (world load, the pilot's first step)."""
    ra, rb = capture(a), capture(b)
    rel_a, rel_b = (ra[1] - ra[4]) / 1000.0, (rb[1] - rb[4]) / 1000.0
    lo, hi = max(rel_a[0], rel_b[0]) + 1.6, min(rel_a[-1], rel_b[-1]) - 1.6
    probes = np.linspace(lo, hi, 6)
    step = float(np.median(np.diff(rb[1]))) / 1000.0
    best, best_err = 0.0, None
    for d in np.arange(-1.5, 1.5 + 1e-6, step):
        err = 0.0
        for t in probes:
            ja = int(np.argmin(np.abs(rel_a - t)))
            jb = int(np.argmin(np.abs(rel_b - (t + d))))
            err += float(np.abs(small(ra[0], ja) - small(rb[0], jb)).mean())
        if best_err is None or err < best_err:
            best, best_err = float(d), err
    return best


def window(a, b, d):
    """The span of run a's route clock both captures cover, run b shifted by d."""
    ra, rb = capture(a), capture(b)
    lo = max((ra[1][0] - ra[4]) / 1000.0, (rb[1][0] - rb[4]) / 1000.0 - d) + 0.1
    hi = min((ra[1][-1] - ra[4]) / 1000.0, (rb[1][-1] - rb[4]) / 1000.0 - d) - 0.1
    return lo, hi


def esc(s):
    return s.replace('\\', '\\\\').replace(':', '\\:').replace("'", '’')  # expansion=none: % is literal


def text(t, x, y, size, color, font=FONT_R):
    return f"drawtext=fontfile={font}:expansion=none:text='{esc(t)}':fontsize={size}:fontcolor={color}:x={x}:y={y}"


SDR2PQ = ('fps=60,scale=out_color_matrix=bt709:out_range=tv,format=yuv444p10le,'  # encode-av1-hdr.sh's SDR -> PQ mapping
          'setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv,zscale=t=linear:npl=203,format=gbrpf32le,'
          'zscale=pin=bt709:tin=linear:p=bt2020:t=smpte2084:m=bt2020nc:r=tv:npl=203,format=yuv420p10le,setsar=1')

inputs, chains, segs = [], [], []
k = 0
for label, title, what, line, s1, s2 in SCENES:
    a, b = run_dir(label + '-stock'), run_dir(label + '-sharp')
    d = align(a, b)
    lo, hi = window(a, b, d)
    fps = fps_of(capture(a)[1])
    if hi - lo < s1 + s2:
        s1 = s2 = max(1.0, (hi - lo) / 2)
    print(f'{label}: {a} + {b}, route +{lo:.2f} .. +{hi:.2f} s, {fps} fps, sharp run shifted {d:+.3f} s')
    for part, (t0, secs, zoom2) in enumerate(((lo, s1, False), (lo + s1, s2, True))):
        n = int(secs * fps)
        pa, pb = pane(a, f'pane{part}', t0, n, fps, zoom2), pane(b, f'pane{part}', t0 + d, n, fps, zoom2)
        ia, ib = len(inputs) // 2, len(inputs) // 2 + 1
        inputs += ['-i', pa, '-i', pb]
        dur = n / fps
        chains.append(f'[{ia}:v]{SDR2PQ}[a{k}]')
        chains.append(f'[{ib}:v]{SDR2PQ}[b{k}]')
        sub = '2x close-up (nearest neighbour: every screen pixel shown as 2x2)' if zoom2 else '1:1 screen pixels of a 5120x2160 frame'
        chains.append(
            f'color=c=0x060608:s={W}x{H}:r=60:d={dur},format=yuv420p10le[bg{k}];'
            f'[bg{k}][a{k}]overlay=0:{HEAD}:shortest=1[s{k}a];[s{k}a][b{k}]overlay={PW + 4}:{HEAD}[s{k}b];'
            f'[s{k}b]drawbox=x={PW}:y={HEAD - 60}:w=4:h={PH + 60}:color=0x202026:t=fill,'
            + text(title, '(w-tw)/2', 22, 60, WHITE, FONT_B) + ','
            + text('STOCK FILTERING', f'({PW}-tw)/2', HEAD - 56, 42, AMBER, FONT_B) + ','
            + text('SPRITE FILTERING: SHARP', f'{PW + 4}+({PW}-tw)/2', HEAD - 56, 42, GREEN, FONT_B) + ','
            + text(f'{sub}  ·  stock: {what}', '(w-tw)/2', CAP_Y + 40, 40, WHITE) + ','
            + text(line, '(w-tw)/2', CAP_Y + 104, 34, GREY) + ','
            + text('Project Zomboid B42.20  ·  Rosewood  ·  the same save, spot, hour and route on both sides  ·  the game\'s own frames, RTX 4090',
                   '(w-tw)/2', H - 58, 30, GREY)
            + f',fade=t=in:st=0:d=0.25,fade=t=out:st={dur - 0.25}:d=0.25[seg{k}]')
        segs.append(f'[seg{k}]')
        k += 1

# results card (numbers: docs/findings-sprite-filter-2026-09-26.md)
rows = [
    ('Fine detail, zoom 75 %, still frame', '5.48', '6.29', '+15 %'),
    ('Fine detail, zoom 175 %, still frame', '22.4', '28.9', '+29 %'),
    ('Fine detail, 120 km/h drive at 250 %, in motion', '14.6', '18.3', '+25 %'),
    ('Crawl / shimmer per unit of detail, same drive', '0.678', '0.685', 'same'),
    ('World composite GPU time, zoom 75 %', '518 µs', '518 µs', '+0.4 µs'),
    ('World composite GPU time, 120 km/h drive', '789 µs', '789 µs', '-0.3 µs'),
    ('World composite GPU time, spinning route at 250 %', '910 µs', '763 µs', '-16 %'),
    ('120 km/h drive  ·  fps  ·  p99 frame time', '233  ·  8.0 ms', '232  ·  8.3 ms', 'noise'),
]
card = [f'color=c=0x060608:s={W}x{H}:r=60:d={CARD},format=yuv420p10le,'
        + text('SHARPER, AND IT COSTS NOTHING', '(w-tw)/2', 130, 64, WHITE, FONT_B) + ','
        + text('5120x2160 on an RTX 4090; GPU times with the filter switched on and off every 20 frames of the same run', '(w-tw)/2', 230, 34, GREY)]
cx = [360, 2080, 2600, 3140]
card.append(text('STOCK', cx[1], 350, 34, GREY, FONT_B))
card.append(text('SHARP', cx[2], 350, 34, GREY, FONT_B))
card.append(text('CHANGE', cx[3], 350, 34, GREY, FONT_B))
for r, (name, s, n, ch) in enumerate(rows):
    yy = 430 + r * 100
    card.append(text(name, cx[0], yy, 44, WHITE))
    card.append(text(s, cx[1], yy, 44, AMBER, FONT_M))
    card.append(text(n, cx[2], yy, 44, GREEN, FONT_M))
    card.append(text(ch, cx[3], yy, 44, GREY if ch in ('same', 'noise') else GREEN, FONT_M))
card.append(f'drawbox=x={cx[0]}:y=410:w={W - 2 * cx[0]}:h=2:color=0x505058:t=fill')
card.append(text('Detail: RMS of the Laplacian over the world (higher = sharper). The zoom picks one precompiled variant of the world shader per frame;',
                 '(w-tw)/2', 1270, 34, GREY))
card.append(text('zoomed out it reads one mip level with two samples instead of the game’s blend of two levels, which is why it costs less.',
                 '(w-tw)/2', 1320, 34, GREY))
card.append(text('Off by default: Options > Enhancements > Sprite filtering. Runs sf-house-z0.75, sf-modes2-z1.75, sf-cap-*, sf-cost-z0.75, sf-drive-final, sf-cycle2-zmax, sf-tail-*.',
                 '(w-tw)/2', H - 90, 30, GREY))
chains.append(','.join(card) + f',fade=t=in:st=0:d=0.3[seg{k}]')
segs.append(f'[seg{k}]')

fc = ';'.join(chains) + ';' + ''.join(segs) + f'concat=n={len(segs)}:v=1:a=0,' \
     + 'setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]'
os.makedirs(os.path.dirname(OUT) or '.', exist_ok=True)
cmd = ['ffmpeg', '-hide_banner', '-v', 'error', '-y'] + inputs + [
    '-filter_complex', fc, '-map', '[v]',
    '-c:v', 'av1_nvenc', '-preset', 'p7', '-tune', 'hq', '-rc', 'vbr', '-cq', '20', '-b:v', '0', '-maxrate', '160M', '-bufsize', '320M',
    '-pix_fmt', 'p010le', '-color_primaries', 'bt2020', '-color_trc', 'smpte2084', '-colorspace', 'bt2020nc', '-color_range', 'tv',
    '-movflags', '+faststart+write_colr', '-r', '60', OUT]
subprocess.run(cmd, check=True)
poster = OUT[:-4] + '.jpg'
subprocess.run(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-ss', '6', '-i', OUT, '-frames:v', '1', '-vf',
                'zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,'
                'zscale=p=bt709:t=bt709:m=bt709,format=yuv420p', '-q:v', '2', poster], check=False)
print(subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration:stream=width,height,codec_name,color_transfer',
                      '-of', 'csv=p=0', OUT], capture_output=True, text=True).stdout.strip())
