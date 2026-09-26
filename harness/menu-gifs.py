#!/usr/bin/env python3
"""Before-and-after preview clips for the Optimizations and Enhancements tabs (src/media/ui/pzopt/compare/<clip>-{stock,opt}.gif).

Each clip is a pair of GIFs cut from two harness recordings of the same route, stock and optimized, starting
the same number of seconds after the route's motion onset (found by frame differencing, showcase-times.py),
tone-mapped from the AV1 HDR capture (docs/media-style.md: GIFs are the one SDR derivative), scaled to
512x216 (the game pads textures to a power of two: 512x256, pzopt.GifTextures), 24 fps, 4 s. The encode is the
Workshop preview's (harness/showcase-thumbnail-gif.py): a 3x3 median plus a spatial-only denoise (hqdn3d +
bilateral) on the scene before the counter is drawn, 96 colours, no dither, gifsicle -O3 --lossy=100 with
per-frame delays from the cumulative centisecond rounding so the clip plays in real time (24 fps = 4,4,4,5 cs).
A live fps counter from the run's pzopt-frames.out is burned in at the bottom left (stock amber, optimized
green, the media-style colours); the 'load' clip counts seconds from the first log line to "world ready"
instead, and the optimized side freezes on its world so both GIFs last as long as the stock load.

The Lua draws the pair side by side; which clip a setting shows is the `clip` of its entry (or its
section) in pzopt_optimizations_options.lua. The GIF files are committed: they ship in the release zip and
the Workshop item via build.sh (src/media -> build/classes/media).

  harness/menu-gifs.py            # every clip whose runs are present
  harness/menu-gifs.py drive fog  # just these
  OUT=/tmp/x harness/menu-gifs.py # elsewhere
"""
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.environ.get('OUT', os.path.join(REPO, 'src/media/ui/pzopt/compare'))
W, H, FPS, DUR, COLOURS, LOSSY = 512, 216, 24, 4.0, 96, 100
DENOISE = 'median=radius=1,hqdn3d=3:2:0:0,bilateral=sigmaS=2:sigmaR=0.03'  # the Workshop GIF's scene chain
FONT = 'Noto Sans Mono'
# ASS colours are &HAABBGGRR; SDR values from docs/media-style.md
C_STOCK, C_OPT, C_GREY = '&H002B59C9', '&H007CDE44', '&H00B8B4B4'

spec = importlib.util.spec_from_file_location('showcase_times', os.path.join(REPO, 'harness/showcase-times.py'))
st = importlib.util.module_from_spec(spec)
spec.loader.exec_module(st)

# after = seconds after the motion onset the clip starts (the car has speed / the camera turns by then)
CLIPS = {
    'drive': dict(stock='show-drive120-stock-1', opt='show-drive120-opt-1', after=1.0,
                  title='120 km/h highway drive, clear day'),
    'spin': dict(stock='show-spin-stock-1', opt='show-spin-opt-1', after=2.0,
                 title='Rosewood, camera spinning'),
    'fog': dict(stock='show-fog120-stock-1', opt='show-fog120-opt-1', after=1.0,
                title='120 km/h drive in heavy fog'),
    'storm': dict(stock='show-storm120-stock-1', opt='show-storm120-opt-1', after=1.0,
                  title='120 km/h drive in a thunderstorm'),
    'horde': dict(stock='show-louisville-stock-3', opt='show-louisville-opt-3', after=3.0,
                  title='Louisville, zombie population maxed'),
    # the two night pairs were recorded at the 60 fps cap for the blocky-lights work: the picture is the point, no counter
    'torch': dict(stock='bl-torch-stock', opt='bl-torch-fix4', after=2.0, counter=False,
                  title='Night, hand torch, turning in place'),
    'nightdrive': dict(stock='bl-drive-stock', opt='bl-drive-fix4', after=1.0, counter=False,
                       title='Night drive with headlights'),
    'load': dict(stock='sbs-stock120-1', opt='sbs-opt120-uncap-1', kind='load',
                 title='Launch to main menu, Continue to world'),
}
# Louisville horde, one group of keys at a time (2026-09-22). `zombies` and `player`: the profiled stock run (every key
# off, overlay + profiler on screen) vs the same stock key list with only that group on (runs prev-lou-<clip>; the
# zombie group needs the worker pool, `parallel=true`); the shared stock GIF is written once as lou-stock.gif
# (`stock_file`, the Lua's STOCK_FILE). `zgt`, the zombie game-thread keys: on their own over stock they gain nothing
# (the stock frame is bound by the player LOS and lighting), so the clip is everything on without them (zt1-rec-off,
# the start of that pass) vs everything on (zt15-rec-plain). The characters-draw keys keep the `horde` clip: every
# everything-on-minus-them run tipped the preset into its strong re-bake flood (four of four), not comparable.
for name, stock, opt, title, extra in (
        ('zombies', 'tri-lou-stock', 'prev-lou-zombies', 'Louisville horde: stock vs stock + the zombie simulation keys', dict(stock_file='lou')),
        ('player', 'tri-lou-stock', 'prev-lou-player', 'Louisville horde: stock vs stock + the player line-of-sight keys', dict(stock_file='lou')),
        ('zgt', 'zt1-rec-off', 'zt15-rec-plain', 'Louisville horde: everything on without vs with the zombie game-thread keys', {})):
    CLIPS[name] = dict(stock=stock, opt=opt, after=3.0, title=title, **extra)
# chunkGridWidth (2026-09-22): the uncapped Rosewood spin with everything on, the vanilla 19x19 chunk grid vs 15x15 (runs
# prev-gridspin3-vanilla / prev-gridspin3-15; the Louisville pair of that evening was never-seen black on both sides)
CLIPS['grid'] = dict(stock='prev-gridspin3-vanilla', opt='prev-gridspin3-15', after=2.0,
                     title='Rosewood, camera spinning, uncapped: vanilla 19x19 chunk grid vs 15x15')
# The performance overlay's elements (2026-09-22): the spinning route recorded with the overlay off (`ov-off`) and with
# every default element on at overlayFont=Large (`ov-full`); each clip is one crop of the 5120x2160 capture, halved
# (Large text ~30 px -> 15 px, readable), 16 fps x 3 s = 48 frames (the overlay's own numbers move at 1-2 Hz), no
# burned counter (the overlay shows its own). The height stays <= 512 so a frame pads to at most 512x512 in VRAM. The
# tree's row count changes with the scene, so the verdict and graph crops are tall enough to hold their element wherever
# the tree ends.
OVERLAY_RUNS = ('ov-off', 'ov-full')
for name, crop, title in (
        ('overlay', '0:0:2700:1150', 'The whole overlay at its defaults: statistics, game-thread tree, verdict, frame graph, flame graph'),
        ('ovstats', '0:0:1024:432', 'Frame statistics: fps, frame time, p99 / p99.9 / max, 1 %-low, jitter, spikes, loads'),
        ('ovtree', '0:190:1024:640', 'Game-thread tree: phases, sub-phases and hot methods, biggest first'),
        ('ovverdict', '0:700:1024:420', 'Verdict line: what holds the frame rate below the cap'),
        ('ovgraph', '0:800:1024:420', 'Frame-time graph: one bar per presented frame, GPU time in blue, the budget line'),
        ('ovflame', '1640:0:1000:1000', 'Flame graph: the last 5 s of game-thread stacks, root at the bottom')):
    CLIPS[name] = dict(stock=OVERLAY_RUNS[0], opt=OVERLAY_RUNS[1], after=6.0, dur=3.0, fps=16, crop=crop, counter=False,
                       title=title)
# The Enhancements tab (2026-09-25, runs enh-*): every optimization on in both runs of a pair, only the enhancement differs
# (the maintainer's tab file sets upscaler=dlss and overlay=true, so every run pins upscaler / hdr / ambientOcclusion /
# overlay). Upscalers, each at the screen size (native), with FSR 1.0 at quality and with DLSS at its defaults: the full-frame
# clips with the fps counter are the uncapped Rosewood spin on a clear day (spin-uncapped bench, runs enh-native / -fsr1 /
# -dlss); the *zoom clips are a 1:1 pixel crop around the player in a furnished Rosewood house at zoom 1, still camera, the
# character turning in place (runs enh-still-*: a crop of the moving spin landed on different pixels in the two runs; the
# 512x216 frame is a tenth of the screen's width, so a full frame cannot show a resolution difference). On the desktop's
# RTX 4090 at 5120x2160 neither the spin (391 / 420 / 283 fps) nor the storm + heavy fog spin (storm-fog bench, runs
# enh-sf-*, GPU 94 % busy: 263 / 256 / 217) gains much from a smaller render size.
# The native GIFs are shared (`stock_file`). HDR: the night-torch preset with lamps and fires (the bench save seats the
# player in a police car beside them) and the river shore at 15:00, turning in place (the HDR pass's showcase flags), SDR vs
# hdr=true; tone-mapped like every GIF, so the HDR side shows brighter lights and water glitter within SDR.
# Neither scene (nor the still upscaler one) moves the camera, so the clips start at the route start (`align='route'`)
# rather than a motion onset.
# AO: Rosewood's houses at noon, zoom 1, walking south, a crop around the player (AO darkens a few pixels along wall bases:
# invisible at a tenth of the screen).
ZOOM_CROP = '2304:972:512:216'   # 1:1 pixels, centred on the player (screen centre of the 5120x2160 capture)
for name, stock, opt, crop, counter, sf, title in (
        ('upscale', 'enh-native', 'enh-fsr1', None, True, 'native', 'Rosewood spin, uncapped: native vs FSR 1.0 quality'),
        ('fsrzoom', 'enh-still-native', 'enh-still-fsr1', ZOOM_CROP, False, 'nativezoom', 'Rosewood house, 1:1 crop: native vs FSR 1.0 quality'),
        ('dlss', 'enh-native', 'enh-dlss', None, True, 'native', 'Rosewood spin, uncapped: native vs DLSS defaults'),
        ('dlsszoom', 'enh-still-native', 'enh-still-dlss', ZOOM_CROP, False, 'nativezoom', 'Rosewood house, 1:1 crop: native vs DLSS defaults')):
    still = crop is not None
    CLIPS[name] = dict(stock=stock, opt=opt, after=3.0 if still else 2.0, crop=crop, counter=counter, stock_file=sf,
                       title=title, denoise=not still, **(dict(align='route') if still else {}))
CLIPS['hdr'] = dict(stock='enh-sdr-night', opt='enh-hdr-night', align='route', after=6.0, counter=False,
                    crop='1360:760:2560:1080', title='Night, fires beside a police car: SDR vs HDR')
CLIPS['hdrday'] = dict(stock='enh-sdr-day', opt='enh-hdr-day', align='route', after=3.0, counter=False, denoise=False,
                       crop='2900:80:1280:540', title='River shore at 15:00, the pier and the water: SDR vs HDR')
# Darkness floor, remembered places, colour grading (2026-09-26, docs/findings-darkness-grading-2026-09-26.md): the
# in-game captures of the showcase video (runs cap-*: pzopt.FrameCapture, the desktop recorder had a browser window over
# the game), already SDR and on one route-start timeline per pair: harness/stitch-darkness.py writes <run>/pane.mkv
# (1918x1400, 30 fps, cropped round the Rosewood house, both panes of a pair starting at the same route time). kind='pane'
# cuts both GIFs `after` s into the pane, no tone-map; a 2.37:1 strip of the house and the yard behind it.
PANE_CROP = '0:330:1918:810'
for name, stock, opt, title in (
        ('darkness', 'cap-night-off', 'cap-night-on', 'Rosewood house at 01:00: stock vs darkness floor 20 % + remembered places + colour grading'),
        ('memory', 'cap-day-off', 'cap-day-on', 'Rosewood house at 13:00, turning in place: stock vs remembered places'),
        ('grade', 'cap-rain-off', 'cap-rain-on', 'Rosewood house in the rain at 14:00: stock vs colour grading')):
    CLIPS[name] = dict(stock=stock, opt=opt, kind='pane', after=2.0, counter=False, crop=PANE_CROP, title=title)
CLIPS['ao'] = dict(stock='enh-ao-off', opt='enh-ao-on', after=1.0, counter=False, crop='1792:756:1536:648',
                   title='Rosewood houses at noon, zoom 1, walking: ambient occlusion off vs on')


def sh(cmd, **kw):
    print('+', ' '.join(cmd)[:300], file=sys.stderr)
    subprocess.run(cmd, check=True, **kw)


def run_info(label, align='onset'):
    """Video offset (video_t = epoch_s + off), the route start and the log / ready points of a run. align='route'
    (scenes whose camera never moves: nothing to detect) takes the recording's start as the launch, like tools/hdr/reel.sh;
    `onset` is then the route start's video time."""
    d = st.run_dir(label)
    opts = st.kv(os.path.join(d, 'run.opts'))
    sched = st.kv(os.path.join(d, 'pzopt-schedule.out'))
    launch = int(opts['launch_epoch'])
    rs = int(sched['route_start_epoch_ms']) / 1000
    ready = int(sched['world_ready_epoch_ms']) / 1000
    video = os.path.join(d, 'recording.mp4')
    if align == 'route':
        on = rs - launch
    else:
        on, peak, base = st.onset(video, rs - launch - 1.0)
        if on is None:
            raise SystemExit(f'{label}: no motion onset found near {rs - launch - 1.0:.1f} s (peak diff {peak:.2f})')
    off = on - rs
    t_log = t_cont = None
    for l in open(os.path.join(d, 'pzopt-loadtrace.out')):
        ep = int(l.split('\t', 1)[0]) / 1000
        if t_log is None:
            t_log = ep
        if 'continuing latest save' in l and t_cont is None:
            t_cont = ep
    return dict(dir=d, video=video, onset=on, off=off, route_start=rs, ready=ready + off,
                log=t_log + off, cont=(t_cont or t_log) + off)


def frame_starts(run_dir):
    """Epoch seconds of every game-thread frame start (pzopt-frames.out anchors + durations)."""
    out = []
    t = None
    for l in open(os.path.join(run_dir, 'pzopt-frames.out')):
        if l.startswith('# anchor'):
            t = int(l.split()[2]) / 1e6
        elif t is not None and l.strip().isdigit():
            out.append(t)
            t += int(l) / 1e6
    return out


def ass_header():
    return (f'[Script Info]\nScriptType: v4.00+\nPlayResX: {W}\nPlayResY: {H}\nWrapStyle: 2\n\n'
            '[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, '
            'Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, '
            'Alignment, MarginL, MarginR, MarginV, Encoding\n'
            f'Style: Big,{FONT},46,{C_STOCK},{C_STOCK},&H00000000,&HA0000000,-1,0,0,0,100,100,0,0,1,2,0,1,10,10,8,1\n'
            f'Style: Unit,{FONT},20,{C_GREY},{C_GREY},&H00000000,&HA0000000,0,0,0,0,100,100,0,0,1,2,0,1,10,10,12,1\n\n'
            '[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n')


def ts(t):
    t = max(0.0, t)
    return f'{int(t // 3600)}:{int(t % 3600 // 60):02d}:{t % 60:05.2f}'


def fps_ass(info, clip_t0, dur, colour, path):
    """One 'NNN fps' event per 0.5 s bin of the clip window (counted from the frame log)."""
    starts = frame_starts(info['dir'])
    lines = [ass_header()]
    step = 0.5
    t = 0.0
    while t < dur:
        e0 = clip_t0 + t - info['off']
        n = sum(1 for s in starts if e0 <= s < e0 + step)
        fps = round(n / step)
        lines.append(f'Dialogue: 0,{ts(t)},{ts(min(t + step, dur))},Big,,0,0,0,,{{\\c{colour}}}{fps:>3}{{\\fs20\\c{C_GREY}}} fps\n')
        t += step
    open(path, 'w').write(''.join(lines))


TONEMAP = ('zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,'
           'tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p')


def gif(src, t0, dur, ass, out, fps=FPS, hold=0.0, extra='', crop=None, denoise=True, sdr=False):
    """Tone-map (+ crop) + scale + denoise + subtitles at the GIF's frame rate into a lossless temp, then a one-palette
    GIF (diff palette, no dither) recoded by gifsicle with lossy LZW and real-time per-frame delays. A crop
    ('x:y:w:h' of the 5120x2160 capture) is scaled to 512 wide with its own aspect; else the frame becomes 512x216.
    denoise=False skips the median + denoise chain: the 1:1 upscaler crops and the water glints are pixel-sized detail
    it would erase."""
    dn = ',' + DENOISE if denoise else ''
    tmp = tempfile.mktemp(suffix='.mkv')
    tm = 'format=yuv420p' if sdr else TONEMAP  # sdr: an SDR source (the in-game capture panes), nothing to tone-map
    if crop:
        cx, cy, cw, ch = (int(v) for v in crop.split(':'))
        vf = f'fps={fps},{tm},crop={cw}:{ch}:{cx}:{cy},scale={W}:{max(2, round(ch * W / cw / 2) * 2)}:flags=lanczos{dn}'
    else:
        vf = f'fps={fps},{tm},scale={W}:{H}:flags=lanczos{dn}'
    if extra:
        vf += ',' + extra
    if ass:
        vf += ',subtitles=' + ass.replace(':', '\\:')
    if hold > 0:
        vf += f',tpad=stop_mode=clone:stop_duration={hold:.3f}'
    sh(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-ss', f'{t0:.3f}', '-t', f'{dur:.3f}', '-i', src,
        '-vf', vf, '-an', '-c:v', 'ffv1', tmp])
    sh(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-i', tmp, '-filter_complex',
        f'[0:v]split[a][b];[a]palettegen=max_colors={COLOURS}:stats_mode=diff[p];'
        f'[b][p]paletteuse=dither=none:diff_mode=rectangle', '-loop', '0', out])
    os.unlink(tmp)
    frames = int(subprocess.run(['ffprobe', '-v', 'error', '-count_frames', '-select_streams', 'v:0', '-show_entries',
                                 'stream=nb_read_frames', '-of', 'csv=p=0', out], capture_output=True, text=True).stdout.strip() or 0)
    # GIF delays are whole centiseconds: per-frame delays from the cumulative rounding keep real time (24 fps = 4,4,4,5)
    gifsicle = shutil.which('gifsicle') or os.path.expanduser('~/.local/bin/gifsicle')
    if os.path.exists(gifsicle):
        delays = [round(100 * (i + 1) / fps) - round(100 * i / fps) for i in range(frames)]
        per_frame = [a for i, d in enumerate(delays) for a in (f'-d{d}', f'#{i}')]
        sh([gifsicle, '-O3', f'--lossy={LOSSY}', out] + per_frame + ['-o', out + '.tmp'])
        os.replace(out + '.tmp', out)
    else:
        print('gifsicle not found: GIF left at plain LZW with rounded delays', file=sys.stderr)
    size = os.path.getsize(out)
    n = subprocess.run(['ffprobe', '-v', 'error', '-count_frames', '-select_streams', 'v:0', '-show_entries',
                        'stream=nb_read_frames', '-of', 'csv=p=0', out], capture_output=True, text=True).stdout.strip()
    print(f'wrote {out} ({size / 1e6:.2f} MB, {n} frames)')
    return size


WRITTEN = set()  # GIFs written by this invocation


def action_clip(name, c, work):
    align = c.get('align', 'onset')
    s, o = run_info(c['stock'], align), run_info(c['opt'], align)
    res = {}
    dur, fps = c.get('dur', DUR), c.get('fps', FPS)
    for side, info, colour in (('stock', s, C_STOCK), ('opt', o, C_OPT)):
        t0 = info['onset'] + c['after']
        # clips sharing one stock run write its GIF once (`stock_file`)
        stem = c['stock_file'] if side == 'stock' and c.get('stock_file') else name
        out = os.path.join(OUT, f'{stem}-{side}.gif')
        if out in WRITTEN:
            res[side] = dict(run=os.path.basename(info['dir']), video_t0=round(t0, 2), file=os.path.basename(out),
                             bytes=os.path.getsize(out))
            continue
        ass = None
        if c.get('counter', True):
            ass = os.path.join(work, f'{name}-{side}.ass')
            fps_ass(info, t0, dur, colour, ass)
        res[side] = dict(run=os.path.basename(info['dir']), video_t0=round(t0, 2), file=os.path.basename(out),
                         bytes=gif(info['video'], t0, dur, ass, out, fps=fps, crop=c.get('crop'),
                                   denoise=c.get('denoise', True)))
        WRITTEN.add(out)
    return res


def load_clip(name, c, work):
    """Launch -> menu -> world for both, real time, with a running seconds counter; the optimized side holds
    its finished world until the stock one is ready, so the two GIFs have the same length."""
    s, o = run_info(c['stock']), run_info(c['opt'])
    total = s['ready'] - s['log']
    fps = 6
    res = {}
    for side, info, colour in (('stock', s, C_STOCK), ('opt', o, C_OPT)):
        length = info['ready'] - info['log']
        ass = os.path.join(work, f'{name}-{side}.ass')
        lines = [ass_header()]
        t = 0.0
        while t < length:
            lines.append(f'Dialogue: 0,{ts(t)},{ts(min(t + 0.5, length))},Big,,0,0,0,,{{\\c{colour}}}{t:4.1f}{{\\fs20\\c{C_GREY}}} s\n')
            t += 0.5
        lines.append(f'Dialogue: 0,{ts(0)},{ts(length)},Unit,,0,0,0,,{{\\pos(10,8)\\an7}}launch, main menu, Continue, world\n')
        open(ass, 'w').write(''.join(lines))
        out = os.path.join(OUT, f'{name}-{side}.gif')
        res[side] = dict(run=os.path.basename(info['dir']), video_t0=round(info['log'], 2), seconds=round(length, 2),
                         bytes=gif(info['video'], info['log'], length, ass, out, fps=fps, hold=max(0.0, total - length)))
    return res


def pane_clip(name, c, work):
    """Both sides from the pair's in-game capture panes (<run>/pane.mkv, SDR, one timeline): no onset, no tone-map."""
    res = {}
    for side in ('stock', 'opt'):
        d = st.run_dir(c[side])
        src = os.path.join(d, 'pane.mkv')
        if not os.path.exists(src):
            raise SystemExit(f'{name}: {src} missing (harness/stitch-darkness.py writes it)')
        out = os.path.join(OUT, f'{name}-{side}.gif')
        res[side] = dict(run=os.path.basename(d), pane_t0=c['after'], file=os.path.basename(out),
                         bytes=gif(src, c['after'], c.get('dur', DUR), None, out, fps=c.get('fps', FPS), crop=c.get('crop'),
                                   denoise=c.get('denoise', True), sdr=True))
    return res


def main():
    names = sys.argv[1:] or list(CLIPS)
    os.makedirs(OUT, exist_ok=True)
    work = tempfile.mkdtemp(prefix='menu-gifs-')
    report = {}
    for name in names:
        c = CLIPS[name]
        try:
            st.run_dir(c['stock']); st.run_dir(c['opt'])
        except IndexError:
            print(f'{name}: runs {c["stock"]} / {c["opt"]} not under harness/runs, skipped', file=sys.stderr)
            continue
        fn = load_clip if c.get('kind') == 'load' else pane_clip if c.get('kind') == 'pane' else action_clip
        report[name] = dict(title=c['title'], **fn(name, c, work))
    shutil.rmtree(work, ignore_errors=True)
    # which runs and cut points made the shipped GIFs; a partial run updates its clips and keeps the rest
    path = os.path.join(REPO, 'harness/menu-gifs.json')
    merged = json.load(open(path)) if os.path.exists(path) else {}
    merged.update(report)
    with open(path, 'w') as f:
        json.dump(merged, f, indent=1, sort_keys=True)
    print(json.dumps(report, indent=1))


if __name__ == '__main__':
    main()
