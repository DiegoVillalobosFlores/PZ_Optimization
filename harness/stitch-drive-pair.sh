#!/usr/bin/env bash
# Two recordings of the same path drive (run.sh --mode drive --flag path=..., e.g. the drive-120-south bench) side by
# side: the same build twice, to show the drive is clean and repeats. Each 5120x2160 capture at half size, aligned on
# its route start (the isolated flash the route starts with, before the picture pans), with a live readout burned into each panel from the pilot's own
# telemetry (pzopt-drive.out: speed, planned speed, lane error, progress along the path), and a results strip: the
# frame numbers (analyze.py overlay line), the drive card with Jev's verdict per run (drive_check.py), and the two runs
# compared (speed / line / arrival difference per 10 tiles of path, Jev's consistency answer). HDR chain as
# stitch-duo.sh (AV1 10-bit PQ / BT.2020, text at ~60 % code values; the poster .jpg is the only tone-mapped file).
#
# Usage: harness/stitch-drive-pair.sh <run-1 label> <run-2 label> <out.mp4>
# Env:   PRE (2, seconds before the route start)  TAIL (2.5, after the slower run stops)  WHAT (title scene text)
set -euo pipefail
cd "$(dirname "$0")/.."
RUN1="${1:?first run label}"; RUN2="${2:?second run label}"; out="${3:?out.mp4}"
PRE="${PRE:-2}"; TAIL="${TAIL:-2.5}"
WHAT="${WHAT:-120 km/h path drive  -  KY-60 east, then south through Rosewood (bench drive-120-south)}"
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf
[ -f "$FONT" ] || FONT=$(fc-match -f '%{file}' 'DejaVu Sans:bold')
tmp=$(mktemp -d /tmp/pzopt-drivepair.XXXX)
trap 'rm -rf "$tmp"' EXIT

run() { ls -d harness/runs/$1-* | tail -1; }
A=$(run "$RUN1"); B=$(run "$RUN2")

W=5120; CW=2560; CH=1080; TITLE_H=140; LABEL_H=60; RES_H=520; FOOT_H=90
Y1L=$TITLE_H; Y1=$((Y1L + LABEL_H)); YR=$((Y1 + CH + 20)); H=$((YR + RES_H + FOOT_H))

# everything the video says, computed in one place: start offsets, clip length, result lines, the ASS readout
python3 - "$A" "$B" "$PRE" "$TAIL" "$tmp" "$W" "$H" "$CW" "$Y1" "$CH" <<'PY'
import importlib.util, json, re, subprocess, sys
from pathlib import Path
A, B, pre, tail, tmp = sys.argv[1], sys.argv[2], float(sys.argv[3]), float(sys.argv[4]), Path(sys.argv[5])
W, H, CW, Y1, CH = map(int, sys.argv[6:11])
sys.path.insert(0, 'harness')
spec = importlib.util.spec_from_file_location('st', 'harness/showcase-times.py'); st = importlib.util.module_from_spec(spec); spec.loader.exec_module(st)
import drive_check

def clean(s):
    return s.replace(':', ' ').replace("'", '').replace('%', ' pct').replace(',', ' ')

def align(d, epoch_t, fps=20):
    """The route start in the recording. The route begins with one isolated flash (the engine start / zoom
    re-assert) and the picture starts panning once the car has speed: take the last big spike before the sustained
    motion, within 2.5 s before the epoch estimate (route_start_epoch - launch_epoch; the capture starts ~0.6 s after
    launch_epoch). showcase-times.onset needs half a second of stillness first, which a HUD blink every second breaks;
    correlating the motion with the logged speed was tried and lands ~1.4 s early (the picture change is not
    proportional to speed at low speed)."""
    import numpy as np
    t0 = max(0.0, epoch_t - 3.0)
    f = st.frames(d + '/recording.mp4', t0, 8.0, fps, 48, 20)
    diff = np.abs(np.diff(f, axis=0)).mean(axis=(1, 2))
    ts = t0 + (np.arange(len(diff)) + 1) / fps
    motion = np.convolve(np.minimum(diff, 3.0), np.ones(fps // 2) / (fps // 2), mode='same')
    moving = None
    for i in range(len(motion) - fps):
        if ts[i] > epoch_t - 2.5 and (motion[i:i + fps] > 0.8).all():
            moving = i
            break
    if moving is None:
        return None, 0.0
    spikes = [i for i in range(moving) if diff[i] > 4.0 and ts[i] >= epoch_t - 2.5]
    i = spikes[-1] if spikes else max(0, moving - fps // 3)
    return float(ts[i]), float(diff[i])

def info(d):
    opts = st.kv(d + '/run.opts'); sched = st.kv(d + '/pzopt-schedule.out')
    launch = int(opts['launch_epoch']); rs = int(sched['route_start_epoch_ms']) / 1000
    on, spike = align(d, rs - launch)
    if on is None:
        raise SystemExit(f'{d}: no sustained motion near the route start in the recording')
    con = open(d + '/console.txt', errors='replace').read()
    route_s = float(re.search(r'route (?:complete|done) in ([0-9.]+)s', con).group(1))
    ov = subprocess.run(['python3', 'harness/analyze.py', d], capture_output=True, text=True).stdout
    ov = ov[ov.index('overlay:' if 'overlay:' in ov else 'mangohud:'):]
    g = lambda pat: re.search(pat, ov).group(1)
    frames = f"{g(r'([\d.]+) fps mean')} fps mean   p99 {g(r'p99 ([\d.]+)ms')} ms   p99.9 {g(r'p99\.9 ([\d.]+)ms')} ms   {g(r'>33ms: (\d+)')} frames over 33 ms"
    # the queue's judge.py already asked Jev (drive-check.json); otherwise ask now
    j = Path(d) / 'drive-check.json'
    res = json.loads(j.read_text()) if j.exists() else None
    if not res or 'answers' not in res:
        res = drive_check.judge(d, use_jev=True, quiet=True, out_json=str(j))
    c, a = res['card'], res['answers']
    drive = (f"{c['progress_tiles']:.0f} of {c['path_length_tiles']:.0f} tiles in {c['seconds']} s   top {c['top_kmh']:.0f} km/h   "
             f"lane error {c['xte_straight_max_tiles']} tiles on the straights / {c['xte_max_tiles']} max   impacts {c['impacts']}   "
             f"Jev  {a['verdict']['choice']} ({a['verdict']['confidence']:.2f})")
    return {'dir': d, 'on': on, 'route_s': route_s, 'frames': frames, 'drive': drive, 'rows': drive_check.read_rows(d), 'len': c['path_length_tiles']}

ra, rb = info(A), info(B)
cmp = drive_check.judge(B, against=[A], use_jev=True, quiet=True, out_json=str(tmp / 'pair.json'))
d = cmp['comparisons'][0]
consistent = cmp['answers'].get('consistent', {}).get('noul', 0)
pair = (f"RUN 2 AGAINST RUN 1  -  speed differs by {d['speed_mean_abs_kmh']} km/h on average ({d['speed_max_abs_kmh']} at most)   "
        f"line by {d['line_max_abs_tiles']} tiles at most   arrival {d['time_diff_s']} s apart   Jev  consistent {consistent:.2f}")
length = round(max(ra['route_s'], rb['route_s']) + pre + tail, 2)

# the live readout: one ASS event per 0.25 s per panel, from the 20 Hz telemetry (t = seconds since the route start)
def events(rows, x, total):
    ev, step = [], 0.25
    for k in range(int((length - pre) / step)):
        t = k * step
        r = min(rows, key=lambda r: abs(r['t'] - t)) if t <= rows[-1]['t'] else rows[-1]
        t0, t1 = pre + t, pre + t + step
        fmt = lambda s: f"{int(s // 3600)}:{int(s % 3600 // 60):02d}:{s % 60:05.2f}"
        txt = (f"{r['kmh']:5.1f} km/h  (plan {r['target_kmh']:.0f}){'  BRAKE' if r['brake'] else ''}\\N"
               f"lane error {r['xte']:+.2f} tiles    {r['s']:.0f} / {total:.0f} tiles")
        ev.append(f"Dialogue: 0,{fmt(t0)},{fmt(t1)},Readout,,0,0,0,,{{\\pos({x},{Y1 + CH - 40})}}{txt}")
    return ev
ass = [
    '[Script Info]', 'ScriptType: v4.00+', f'PlayResX: {W}', f'PlayResY: {H}', '',
    '[V4+ Styles]',
    'Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding',
    # PQ space: ~60 % code value text on a translucent dark box, bottom-left anchored (alignment 1)
    'Style: Readout,Noto Sans,46,&H00B8B4B4,&H00B8B4B4,&H00000000,&H60080806,1,0,0,0,100,100,0,0,3,14,0,1,0,0,0,1', '',
    '[Events]', 'Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text',
] + events(ra['rows'], 40, ra['len']) + events(rb['rows'], CW + 40, rb['len'])
(tmp / 'readout.ass').write_text('\n'.join(ass) + '\n')
vals = {'ST1': f"{ra['on'] - pre:.2f}", 'ST2': f"{rb['on'] - pre:.2f}", 'LEN': str(length),
        'F1': clean(ra['frames']), 'F2': clean(rb['frames']), 'D1': clean(ra['drive']), 'D2': clean(rb['drive']), 'PAIR': clean(pair)}
(tmp / 'vals.sh').write_text(''.join(f"{k}='{v}'\n" for k, v in vals.items()))
print(f"run 1 {A}: from {vals['ST1']} s | {ra['frames']} | {ra['drive']}")
print(f"run 2 {B}: from {vals['ST2']} s | {rb['frames']} | {rb['drive']}")
print(pair, f'| clip {length} s')
PY
. "$tmp/vals.sh"

TXT=0xb4b4b8; DIM=0x8a8a90; GRN=0x5cb878
cell() { echo "[$1:v]fps=60,format=yuv420p10le,scale=${CW}:${CH}:flags=lanczos,setsar=1,tpad=stop_mode=clone:stop_duration=10"; }
label() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=44:fontcolor=$TXT:x=$2:y=$3"; }
ctext() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=$3:fontcolor=$4:x=(w-tw)/2:y=${YR}+$2"; }

filter="
color=c=0x060608:s=${W}x${H}:r=60:d=${LEN},format=yuv420p10le[bg];
$(cell 0)[a];
$(cell 1)[b];
[bg][a]overlay=0:${Y1}:shortest=1[b1];
[b1][b]overlay=${CW}:${Y1},drawbox=x=${CW}-2:y=${Y1L}:w=4:h=${LABEL_H}+${CH}:color=0x202026:t=fill,drawbox=x=0:y=${YR}:w=${W}:h=${RES_H}:color=0x0c0c10:t=fill[b2];
[b2]subtitles=filename=$tmp/readout.ass:fontsdir=$(dirname "$FONT"),
drawtext=fontfile=$FONT:text='Project Zomboid B42.20  \\|  ${WHAT}  \\|  two runs of the same build':fontsize=58:fontcolor=$TXT:x=(w-tw)/2:y=38,
$(label "RUN 1  ($RUN1)" "(${CW}-tw)/2" "${Y1L}+8"),
$(label "RUN 2  ($RUN2)" "${CW}+(${CW}-tw)/2" "${Y1L}+8"),
$(ctext 'RESULTS  -  each over its own route  -  240 fps cap  -  the car is driven by the harness path pilot (pzopt.DrivePilot)' 30 38 $TXT),
$(ctext 'RUN 1' 100 46 $GRN),
$(ctext "$F1" 156 38 $TXT),
$(ctext "$D1" 204 34 $DIM),
$(ctext 'RUN 2' 262 46 $GRN),
$(ctext "$F2" 318 38 $TXT),
$(ctext "$D2" 366 34 $DIM),
$(ctext "$PAIR" 440 36 $TXT),
drawtext=fontfile=$FONT:text='RTX 4090, 5120x2160, NVIDIA GL, 240 fps cap  -  each panel is the screen at half size  -  bottom-left of each panel  the pilot telemetry live':fontsize=34:fontcolor=$DIM:x=(w-tw)/2:y=h-th-26,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]
"

mkdir -p "$(dirname "$out")"
ffmpeg -hide_banner -v error -y \
  -ss "$ST1" -t "$LEN" -i "$A/recording.mp4" \
  -ss "$ST2" -t "$LEN" -i "$B/recording.mp4" \
  -filter_complex "$filter;[0:a]atrim=0:${LEN},apad=whole_dur=${LEN},loudnorm=I=-16:TP=-1.5:LRA=11,afade=t=out:st=$(python3 -c "print(max(0, $LEN-3))"):d=3[aud]" -map '[v]' -map '[aud]' -c:a aac -b:a 192k \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 120M -bufsize 240M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
  -movflags +faststart+write_colr -r 60 -t "$LEN" \
  "$out"

ffmpeg -hide_banner -v error -y -ss 20 -i "$out" -frames:v 1 -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" -q:v 2 "${out%%.mp4}.jpg" || true
ls -la "$out" | awk '{print $5, $9}'
ffprobe -v error -show_entries format=duration:stream=width,height,codec_name,color_transfer -of csv=p=0 "$out"
