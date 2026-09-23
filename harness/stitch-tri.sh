#!/usr/bin/env bash
# Stitch the three profiled recordings of one scene (stock game with every optimization key off, the optimized
# build before today's uncommitted changes, the optimized build with them; all three uncapped, in-game overlay
# with the game-thread profiler on screen, JFR attached) into one 2:1 quad-view video: stock top left, before top
# right, after bottom left, results bottom right. Each 5120x2160 capture is shown at half size (2560x1080) so the
# overlay's tree and flame graph stay readable as they were on screen; nothing is pasted over the panels. HDR end
# to end: the gpu-screen-recorder captures are AV1 10-bit PQ / BT.2020 and so is the output (NVENC AV1, same
# colour tags, no tone-map; the poster .jpg is the only tone-mapped derivative). Alignment at the route's motion
# onset (showcase-times.py's frame differencing around route_start_epoch_ms, the same for a teleport walk and a
# drive), each clip = [onset - PRE, onset + shortest route). The result lines come from analyze.py's overlay line
# (fps mean, p99, p99.9, frames over 33 ms, 1 %-low) and the game-thread block (load, the three biggest sub-phases).
#
# Usage: harness/stitch-tri.sh <lou|spin|drive120|storm120|stormfog> [out.mp4]
# Env:   RUN_STOCK RUN_BEFORE RUN_AFTER   run labels (latest run dir of each; default tri-<scene>-{stock,before,after})
#        PRE                              seconds of clip before the route start (default 2)
#        BEFORE_REV                       the commit of the "before" build (default: /tmp/pzopt-before's HEAD, else e1afa29)
#        LABEL_STOCK LABEL_BEFORE LABEL_AFTER   panel labels
#        COMPARE                          the comparison named in the title line (default: stock vs optimized before vs now)
set -euo pipefail
cd "$(dirname "$0")/.."

scene="${1:?scene: lou|spin|drive120|storm120|stormfog}"
case "$scene" in
  lou)      what='downtown Louisville, zombie population x4'; route='Teleport into downtown Louisville, spectator view, max zoom, walking south at 6 tiles/s while the camera spins'
            def_out=docs/media/louisville-horde-profiled-stock-vs-before-vs-after.mp4 ;;
  spin)     what='Rosewood spin route'; route='Rosewood, max zoom, the bench teleport route south at 18 tiles/s while the camera spins (25 s)'
            def_out=docs/media/rosewood-spin-profiled-stock-vs-before-vs-after.mp4 ;;
  drive120) what='120 km/h highway drive'; route='Highway east at ~120 km/h (Base.RaceCar12, 1200 tiles), max zoom, daylight'
            def_out=docs/media/drive-120kmh-profiled-stock-vs-before-vs-after.mp4 ;;
  storm120) what='120 km/h highway drive, thunderstorm'; route='Highway east at ~120 km/h (Base.RaceCar12, 1200 tiles), max zoom, pinned thunderstorm with a lightning strike every 6 s'
            def_out=docs/media/drive-120kmh-storm-profiled-stock-vs-before-vs-after.mp4 ;;
  stormfog) what='thunderstorm + heavy fog, GPU-bound'; route='Rosewood, zoom 1, pinned thunderstorm with heavy fog (stock fog on the stock side), walking south at 2 tiles/s while the facing turns'
            def_out=docs/media/storm-fog-stock-vs-optimized-vs-dlss.mp4 ;;
  *) echo "unknown scene: $scene" >&2; exit 2 ;;
esac
out="${2:-$def_out}"
RUN_STOCK="${RUN_STOCK:-tri-$scene-stock}"; RUN_BEFORE="${RUN_BEFORE:-tri-$scene-before}"; RUN_AFTER="${RUN_AFTER:-tri-$scene-after}"
PRE="${PRE:-2}"
COMPARE="${COMPARE:-stock vs optimized before vs optimized now}"
BEFORE_REV="${BEFORE_REV:-$(git -C /tmp/pzopt-before rev-parse --short HEAD 2>/dev/null || echo e1afa29)}"
LABEL_STOCK="${LABEL_STOCK:-STOCK GAME  (every optimization off, overlay + profiler on)}"
LABEL_BEFORE="${LABEL_BEFORE:-OPTIMIZED, BEFORE THE UNCOMMITTED CHANGES OF 2026-09-22  (commit $BEFORE_REV)}"
LABEL_AFTER="${LABEL_AFTER:-OPTIMIZED, WITH THE CHANGES OF 2026-09-22  (working tree)}"
# the results panel's three headings and three footnote lines (defaults: the 2026-09-22 comparison)
HEAD_STOCK="${HEAD_STOCK:-STOCK GAME}"
HEAD_BEFORE="${HEAD_BEFORE:-OPTIMIZED, BEFORE THE UNCOMMITTED CHANGES OF 2026-09-22}"
HEAD_AFTER="${HEAD_AFTER:-OPTIMIZED, WITH THE CHANGES OF 2026-09-22}"
NOTE1="${NOTE1:-Same save, route, settings and hardware. Every run shows the in-game overlay with the game-thread tree}"
NOTE2="${NOTE2:-and flame graph and records a 1 ms JFR profile. Stock = every optimization key off (the overlay needs the overrides loaded).}"
NOTE3="${NOTE3:-Before = the last commit. After = the working tree of 2026-09-22. Overlay numbers = the last 5 s; result lines = the whole route.}"
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf
[ -f "$FONT" ] || FONT=$(fc-match -f '%{file}' 'DejaVu Sans:bold')

run() { ls -d harness/runs/$1-* | tail -1; }
# per run: clip start (s into the capture), clip length, the result line, the profile line
info() {
  python3 - "$1" "$PRE" <<'PY'
import importlib.util, os, re, subprocess, sys
d, pre = sys.argv[1], float(sys.argv[2])
spec = importlib.util.spec_from_file_location('st', 'harness/showcase-times.py'); st = importlib.util.module_from_spec(spec); spec.loader.exec_module(st)
opts = st.kv(d + '/run.opts'); sched = st.kv(d + '/pzopt-schedule.out')
launch = int(opts['launch_epoch']); rs = int(sched['route_start_epoch_ms']) / 1000
guess = rs - launch - 1.0
on, peak, base = st.onset(d + '/recording.mp4', guess)
if on is None:
    raise SystemExit(f'{d}: no motion onset found near {guess:.1f} s (peak diff {peak})')
con = open(d + '/console.txt', errors='replace').read()
m = re.search(r'route (?:complete|done) in ([0-9.]+)s', con)
bench = open(d + '/pzopt-bench.out').read()
route_s = float(m.group(1)) if m else float(re.search(r'^route_seconds=([\d.]+)', bench, re.M).group(1))
out = subprocess.run(['python3', 'harness/analyze.py', d], capture_output=True, text=True).stdout
key = 'overlay:' if 'overlay:' in out else 'mangohud:'
ov = out[out.index(key):]
fps = re.search(r'([\d.]+) fps mean', ov).group(1)
p99 = re.search(r'p99 ([\d.]+)ms', ov).group(1)
p999 = re.search(r'p99\.9 ([\d.]+)ms', ov).group(1)
over = re.search(r'>33ms: (\d+)', ov).group(1)
low = re.search(r'1%-low (\d+) fps', ov).group(1)
gl = re.search(r'game_load (\d+)%', ov)
res = f'{fps} fps mean  -  p99 {p99} ms  -  p99.9 {p999} ms  -  {over} frames over 33 ms  -  1-percent low {low} fps'
prof = 'no game-thread profile'
if 'game thread (' in out:
    block = out[out.index('game thread ('):]
    block = block[:block.index('hottest methods')] if 'hottest methods' in block else block
    subs = []
    for l in block.splitlines()[1:]:
        ind = len(l) - len(l.lstrip())
        mm = re.match(r'\s+(\d+)%\s+(\S(?:.*?\S)?)(?:\s{2,}|\s+\[|$)', l)
        if mm and ind >= 6:
            subs.append((int(mm.group(1)), mm.group(2).strip()))
    subs.sort(key=lambda x: -x[0])
    top = ', '.join(f'{n} {p}' for p, n in subs[:3])
    prof = f'game thread {gl.group(1) if gl else "?"} pct busy  -  biggest {top} (pct of the game thread)'
z = re.search(r'^zombies_loaded=(\d+)', bench, re.M)
clean = lambda s: s.replace(':', ' ').replace("'", '').replace('%', ' pct')
print(f'{on - pre:.2f}|{route_s + pre:.2f}|{clean(res)}|{clean(prof)}|{z.group(1) if z else ""}')
PY
}
S=$(run "$RUN_STOCK"); O=$(run "$RUN_BEFORE"); A=$(run "$RUN_AFTER")
IFS='|' read -r ST_STOCK D_STOCK RES_STOCK PROF_STOCK Z_STOCK < <(info "$S")
IFS='|' read -r ST_BEFORE D_BEFORE RES_BEFORE PROF_BEFORE Z_BEFORE < <(info "$O")
IFS='|' read -r ST_AFTER D_AFTER RES_AFTER PROF_AFTER Z_AFTER < <(info "$A")
LEN=$(python3 -c "print(round(min($D_STOCK, $D_BEFORE, $D_AFTER) - 0.5, 2))")
echo "stock  $S: from ${ST_STOCK}s: $RES_STOCK | $PROF_STOCK"
echo "before $O: from ${ST_BEFORE}s: $RES_BEFORE | $PROF_BEFORE"
echo "after  $A: from ${ST_AFTER}s: $RES_AFTER | $PROF_AFTER; clip ${LEN}s"
zomb=""; [ "$scene" = lou ] && [ -n "$Z_AFTER" ] && zomb=", ~${Z_AFTER} zombies loaded"

W=5120; H=2560; CW=2560; CH=1080
TITLE_H=140; LABEL_H=60; PAD=$(( (H - TITLE_H - 2*LABEL_H - 2*CH) / 3 ))
Y1L=$TITLE_H; Y1=$((Y1L + LABEL_H)); Y2L=$((Y1 + CH + PAD)); Y2=$((Y2L + LABEL_H))

# PQ-space colours: full white is the display's peak, so text uses ~60 % code values.
TXT=0xb4b4b8; DIM=0x8a8a90; RED=0xb85c5c; AMB=0xb89a5c; GRN=0x5cb878
cell() { echo "[$1:v]fps=60,format=yuv420p10le,scale=${CW}:${CH}:flags=lanczos,setsar=1"; }
label() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=44:fontcolor=$TXT:x=$2:y=$3"; }
ptext() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=$3:fontcolor=$4:x=${CW}+(${CW}-tw)/2:y=${Y2}+$2"; }

filter="
color=c=0x060608:s=${W}x${H}:r=60:d=${LEN},format=yuv420p10le[bg];
$(cell 0)[s];
$(cell 1)[o];
$(cell 2)[g];
[bg][s]overlay=0:${Y1}:shortest=1[b1];
[b1][o]overlay=${CW}:${Y1}[b2];
[b2][g]overlay=0:${Y2},drawbox=x=${CW}-2:y=0:w=4:h=${Y2}+${CH}:color=0x202026:t=fill,drawbox=x=${CW}:y=${Y2}:w=${CW}:h=${CH}:color=0x0c0c10:t=fill[b3];
[b3]drawtext=fontfile=$FONT:text='Project Zomboid B42.20  \\|  ${what}  \\|  uncapped  \\|  ${COMPARE}, game-thread profiler on screen':fontsize=62:fontcolor=$TXT:x=(w-tw)/2:y=36,
$(label "$LABEL_STOCK" "(${CW}-tw)/2" "${Y1L}+8"),
$(label "$LABEL_BEFORE" "${CW}+(${CW}-tw)/2" "${Y1L}+8"),
$(label "$LABEL_AFTER" "(${CW}-tw)/2" "${Y2L}+8"),
$(label "RESULTS  -  the route window${zomb}, no frame cap" "${CW}+(${CW}-tw)/2" "${Y2L}+8"),
$(ptext "$route" 40 36 $TXT),
$(ptext "$HEAD_STOCK" 130 50 $RED),
$(ptext "$RES_STOCK" 192 40 $TXT),
$(ptext "$PROF_STOCK" 240 34 $DIM),
$(ptext "$HEAD_BEFORE" 330 50 $AMB),
$(ptext "$RES_BEFORE" 392 40 $TXT),
$(ptext "$PROF_BEFORE" 440 34 $DIM),
$(ptext "$HEAD_AFTER" 530 50 $GRN),
$(ptext "$RES_AFTER" 592 40 $TXT),
$(ptext "$PROF_AFTER" 640 34 $DIM),
$(ptext "$NOTE1" 760 34 $DIM),
$(ptext "$NOTE2" 806 34 $DIM),
$(ptext "$NOTE3" 852 34 $DIM),
drawtext=fontfile=$FONT:text='Ryzen 7 9800X3D, 32 GB DDR5 8000 MT/s, RTX 4090, 5120x2160, NVIDIA GL, uncapped  -  each panel is the screen at half size':fontsize=36:fontcolor=$DIM:x=(w-tw)/2:y=h-th-26,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]
"

mkdir -p "$(dirname "$out")"
ffmpeg -hide_banner -v error -y \
  -ss "$ST_STOCK"  -t "$LEN" -i "$S/recording.mp4" \
  -ss "$ST_BEFORE" -t "$LEN" -i "$O/recording.mp4" \
  -ss "$ST_AFTER"  -t "$LEN" -i "$A/recording.mp4" \
  -filter_complex "$filter;[2:a]atrim=0:${LEN},loudnorm=I=-16:TP=-1.5:LRA=11,afade=t=out:st=$(python3 -c "print(max(0, $LEN-3))"):d=3[a]" -map '[v]' -map '[a]' -c:a aac -b:a 192k \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 120M -bufsize 240M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
  -movflags +faststart+write_colr -r 60 \
  "$out"

ffmpeg -hide_banner -v error -y -ss 12 -i "$out" -frames:v 1 -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" -q:v 2 "${out%%.mp4}.jpg" || true
ls -la "$out" | awk '{print $5, $9}'
ffprobe -v error -show_entries format=duration:stream=width,height,codec_name,color_transfer -of csv=p=0 "$out"
