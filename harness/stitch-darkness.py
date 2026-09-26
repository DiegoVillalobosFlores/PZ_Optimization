#!/usr/bin/env python3
"""The candidate-B showcase (2026-09-26, docs/findings-darkness-grading-2026-09-26.md): four scenes, each the stock look
(left) beside the new options (right) from a matched pair of recorded runs, then a results card with the measured cost.

Pairs (harness/runs/<label>-*): cap-night-off/-on, cap-day-off/-on, cap-dusk-off/-on, cap-rain-off/-on, all on the
Rosewood house spot with a still camera and the player turning at 15 deg/s (the vision cone sweeps), an empty options
file each (a player's defaults: SDR, no upscaler). The source is the game's own presented frames (pzopt.FrameCapture,
`--prop devCapture=15.5,9,30,75`: the desktop recorder captures the whole monitor, and a window in front of the game
ends up in the video). Both runs of a pair are put on one 30 fps timeline measured from their route start
(pzopt-schedule.out; every captured frame has its epoch ms), so the turning player lines up frame for frame; each run
first becomes a lossless FFV1 pane (<run>/pane.mkv, cropped round the house), SDR mapped to PQ at 203 nits in the final
composition (as encode-av1-hdr.sh does).

Layout per docs/media-style.md: 3840x1800, 60 fps, header band (title + pane labels), panes 1918x1400 cropped round
the house from the 5120x2160 captures, a caption strip; HDR chain unchanged (AV1 10-bit PQ / BT.2020, text in PQ code
values), the poster .jpg the only tone-mapped derivative.

Usage: harness/stitch-darkness.py <out.mp4>     (a queued media job: harness/queue.sh submit media ...)
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

OUT = sys.argv[1] if len(sys.argv) > 1 else 'docs/media/darkness-grading-stock-vs-new.mp4'
SEG = 8.5  # seconds per scene (at most; the captures cover ~9 s of the hold)
CARD = 7.0
W, H = 3840, 1800
HEAD = 150
PW, PH = 1918, 1400  # pane size; divider 4 px
CROP = (700, 130, 2600, 1898)  # x, y, w, h in the 5120x2160 capture (same aspect as the pane)
CAP_Y = HEAD + PH

FONT_B = '/usr/share/fonts/noto/NotoSans-Bold.ttf'
FONT_R = '/usr/share/fonts/noto/NotoSans-Regular.ttf'
FONT_M = '/usr/share/fonts/noto/NotoSansMono-Regular.ttf'
WHITE, GREY, AMBER, GREEN = '0xB4B4B8', '0x8A8A90', '0xB88A40', '0x5CB878'

SCENES = [
    ('cap-night', 'NIGHT, 01:00', 'darkness floor 20 %  ·  remembered places  ·  colour grading (night: the eye\'s blue-green night vision)',
     'Rooms and the yard you have seen keep a dim moonlit floor instead of black; never-seen places stay black.'),
    ('cap-day', 'CLEAR DAY, 13:00', 'remembered places',
     'What you cannot see right now (behind walls, behind you) is drawn like a memory: grey, dimmer, cooler, with a soft edge.'),
    ('cap-dusk', 'GOLDEN HOUR, 19:18', 'colour grading',
     'The hour before dusk warms the light and cools the shadows; clear daylight itself is left as the game draws it.'),
    ('cap-rain', 'RAIN, 14:00', 'colour grading',
     'Rain and cloud turn the picture cooler, greyer and softer; storms, fog and snow have looks of their own.'),
]


def run_dir(label):
    runs = sorted(glob.glob(f'harness/runs/{label}-2*'))
    if not runs:
        raise SystemExit(f'no run {label}')
    return runs[-1]


def capture(d):
    """(memmap of the RGBA frames, bottom-up rows; epoch ms per frame; w; h; route start epoch ms)."""
    c = os.path.join(d, 'capture')
    lines = open(os.path.join(c, 'index.txt')).read().split('\n')
    head = dict(kv.split('=') for kv in lines[0].split())
    w, h = int(head['w']), int(head['h'])
    stamps = np.array([int(x) for x in lines[1:] if x.strip()])
    raw = np.memmap(os.path.join(c, 'frames.rgba'), dtype=np.uint8, mode='r')
    n = min(len(stamps), raw.size // (w * h * 4))
    route = int(st.kv(os.path.join(d, 'pzopt-schedule.out'))['route_start_epoch_ms'])
    return raw[:n * w * h * 4].reshape(n, h, w, 4), stamps[:n], w, h, route


def pane(d, t0, n, fps=30):
    """<run>/pane.mkv: n frames at fps from t0 s after the route start (nearest captured frame), cropped, lossless."""
    frames, stamps, w, h, route = capture(d)
    k = w / 5120.0
    x, y, cw, ch = (int(round(v * k)) for v in CROP)
    x, y = min(x, w - cw), min(y, h - ch)
    rel = (stamps - route) / 1000.0
    out = os.path.join(d, 'pane.mkv')
    ff = subprocess.Popen(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{cw}x{ch}',
                           '-r', str(fps), '-i', '-', '-vf', f'scale={PW}:{PH}:flags=lanczos', '-c:v', 'ffv1', '-pix_fmt', 'gbrp', out],
                          stdin=subprocess.PIPE)
    for i in range(n):
        j = int(np.argmin(np.abs(rel - (t0 + i / fps))))
        f = frames[j, h - y - ch:h - y, x:x + cw, :3][::-1]  # rows are bottom-up
        ff.stdin.write(np.ascontiguousarray(f).tobytes())
    ff.stdin.close()
    if ff.wait() != 0:
        raise SystemExit(f'{d}: pane encode failed')
    return out


def window(a, b):
    """The route-relative span both captures cover."""
    ra, rb = capture(a), capture(b)
    lo = max((ra[1][0] - ra[4]) / 1000.0, (rb[1][0] - rb[4]) / 1000.0) + 0.1
    hi = min((ra[1][-1] - ra[4]) / 1000.0, (rb[1][-1] - rb[4]) / 1000.0) - 0.1
    return lo, hi


def esc(s):
    return s.replace('\\', '\\\\').replace(':', '\\:').replace("'", '’')  # expansion=none: % is literal


def text(t, x, y, size, color, font=FONT_R):
    return f"drawtext=fontfile={font}:expansion=none:text='{esc(t)}':fontsize={size}:fontcolor={color}:x={x}:y={y}"


inputs, chains, segs = [], [], []
seg_len = []
for i, (label, title, what, line) in enumerate(SCENES):
    a, b = run_dir(label + '-off'), run_dir(label + '-on')
    lo, hi = window(a, b)
    n = int(min(SEG, hi - lo) * 30)
    if n < 90:
        raise SystemExit(f'{label}: the captures overlap only {hi - lo:.1f} s')
    pa, pb = pane(a, lo, n), pane(b, lo, n)
    seg_len.append(n / 30.0)
    print(f'{label}: {a} + {b}, route +{lo:.2f} s, {n} frames')
    ia, ib = len(inputs) // 2, len(inputs) // 2 + 1
    inputs += ['-i', pa, '-i', pb]
    sdr2pq = ('fps=60,scale=out_color_matrix=bt709:out_range=tv,format=yuv444p10le,'  # encode-av1-hdr.sh's SDR -> PQ mapping
              'setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv,zscale=t=linear:npl=203,format=gbrpf32le,'
              'zscale=pin=bt709:tin=linear:p=bt2020:t=smpte2084:m=bt2020nc:r=tv:npl=203,format=yuv420p10le,setsar=1')
    chains.append(f'[{ia}:v]{sdr2pq}[a{i}]')
    chains.append(f'[{ib}:v]{sdr2pq}[b{i}]')
    chains.append(
        f'color=c=0x060608:s={W}x{H}:r=60:d={n / 30.0},format=yuv420p10le[bg{i}];'
        f'[bg{i}][a{i}]overlay=0:{HEAD}:shortest=1[s{i}a];[s{i}a][b{i}]overlay={PW + 4}:{HEAD}[s{i}b];'
        f'[s{i}b]drawbox=x={PW}:y={HEAD - 60}:w=4:h={PH + 60}:color=0x202026:t=fill,'
        + text(f'{title}', '(w-tw)/2', 22, 60, WHITE, FONT_B) + ','
        + text('STOCK LOOK', f'({PW}-tw)/2', HEAD - 56, 42, AMBER, FONT_B) + ','
        + text('WITH THE NEW OPTIONS', f'{PW + 4}+({PW}-tw)/2', HEAD - 56, 42, GREEN, FONT_B) + ','
        + text(what, '(w-tw)/2', CAP_Y + 40, 44, WHITE) + ','
        + text(line, '(w-tw)/2', CAP_Y + 108, 36, GREY) + ','
        + text('Project Zomboid B42.20  ·  Rosewood, zoom 1, the player turning in place  ·  the same save, spot and hour on both sides  ·  the game\'s own frames, RTX 4090',
               '(w-tw)/2', H - 58, 30, GREY)
        + f',fade=t=in:st=0:d=0.3,fade=t=out:st={n / 30.0 - 0.3}:d=0.3[seg{i}]')
    segs.append(f'[seg{i}]')

# results card (numbers: docs/findings-darkness-grading-2026-09-26.md, runs dk-cost-clean and dk-ab-off / dk-ab-on)
rows = [
    ('Screen pass with colour grading on', '100.4 µs', '71.7 µs', '-29 %'),
    ('Vision-cone pass with remembered places on', '200.7 µs', '193.5 µs', '-4 %'),
    ('Darkness floor on the GPU', '0 µs', '0 µs', 'none'),
    ('Night spin route, all three on  ·  fps', '290', '296', '+2 %'),
    ('Night spin route, all three on  ·  p99 frame time', '9.3 ms', '8.4 ms', '-10 %'),
]
card = [f'color=c=0x060608:s={W}x{H}:r=60:d={CARD},format=yuv420p10le,'
        + text('WHAT IT COSTS', '(w-tw)/2', 150, 64, WHITE, FONT_B) + ','
        + text('GPU time per frame at 5120x2160 on an RTX 4090, each option switched on and off every 30 frames of the same run', '(w-tw)/2', 250, 34, GREY)]
cx = [520, 2200, 2640, 3080]
card.append(text('STOCK', cx[1], 380, 34, GREY, FONT_B))
card.append(text('NEW', cx[2], 380, 34, GREY, FONT_B))
card.append(text('CHANGE', cx[3], 380, 34, GREY, FONT_B))
for r, (name, s, n, ch) in enumerate(rows):
    yy = 470 + r * 110
    card.append(text(name, cx[0], yy, 46, WHITE))
    card.append(text(s, cx[1], yy, 46, AMBER, FONT_M))
    card.append(text(n, cx[2], yy, 46, GREEN, FONT_M))
    card.append(text(ch, cx[3], yy, 46, GREY if ch == 'none' else GREEN, FONT_M))
card.append(f'drawbox=x={cx[0]}:y=440:w={W - 2 * cx[0] + 200}:h=2:color=0x505058:t=fill')
card.append(text('Grading folds the game’s own screen filter and the grade into one colour lookup, so the screen pass gets cheaper than stock;',
                 '(w-tw)/2', 1110, 36, GREY))
card.append(text('remembered places replaces the view cone’s own darkening; the floor changes the light values the game already computes.',
                 '(w-tw)/2', 1160, 36, GREY))
card.append(text('All three are off by default: Options > Enhancements > Darkness, remembered places and colour grading. Runs dk-cost-clean (default settings), dk-ab-off / dk-ab-on.',
                 '(w-tw)/2', H - 90, 30, GREY))
chains.append(','.join(card) + f',fade=t=in:st=0:d=0.3[seg{len(SCENES)}]')
segs.append(f'[seg{len(SCENES)}]')

fc = ';'.join(chains) + ';' + ''.join(segs) + f'concat=n={len(segs)}:v=1:a=0,' \
     + 'setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]'
os.makedirs(os.path.dirname(OUT) or '.', exist_ok=True)
cmd = ['ffmpeg', '-hide_banner', '-v', 'error', '-y'] + inputs + [
    '-filter_complex', fc, '-map', '[v]',
    '-c:v', 'av1_nvenc', '-preset', 'p7', '-tune', 'hq', '-rc', 'vbr', '-cq', '22', '-b:v', '0', '-maxrate', '120M', '-bufsize', '240M',
    '-pix_fmt', 'p010le', '-color_primaries', 'bt2020', '-color_trc', 'smpte2084', '-colorspace', 'bt2020nc', '-color_range', 'tv',
    '-movflags', '+faststart+write_colr', '-r', '60', OUT]
subprocess.run(cmd, check=True)
poster = OUT[:-4] + '.jpg'
subprocess.run(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-ss', '4', '-i', OUT, '-frames:v', '1', '-vf',
                'zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,'
                'zscale=p=bt709:t=bt709:m=bt709,format=yuv420p', '-q:v', '2', poster], check=False)
print(subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration:stream=width,height,codec_name,color_transfer',
                      '-of', 'csv=p=0', OUT], capture_output=True, text=True).stdout.strip())
