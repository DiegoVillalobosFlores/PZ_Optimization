#!/usr/bin/env bash
# Stitch three Louisville-preset recordings (downtown, zombie population maxed, see_all view, spinning walk,
# uncapped) into one 2:1 (3840x1920) quad-view video: the stock game (every optimization key off), all
# optimizations with the player line-of-sight pass off (playerLosFast / zombieSpotFast false), all
# optimizations (the 2026-09-22 player pass), results bottom right. HDR end to end: the gpu-screen-recorder
# captures are AV1 10-bit PQ / BT.2020 and so is the output (NVENC AV1, same colour tags, no tone-map; the
# poster .jpg is the only tone-mapped derivative). Alignment as in stitch-louisville-sbs.sh: the game quits the
# instant the route ends and the capture goes black, so each clip is [black - route_seconds - PRE, black).
# The in-game overlay region (top-left HUD_W x HUD_H of each capture) is pasted 1:1 scaled by HUD_SCALE over
# the panel's corner so the live numbers stay readable after the 0.375x panel scale. The result lines come
# from analyze.py (overlay line: fps mean, p99, p99.9, frames over 33 ms, 1 %-low; game-thread block: the
# player's share).
#
# Usage: harness/stitch-louisville-triple.sh [out.mp4]
# Env:   RUN_STOCK RUN_OPT RUN_ALL   run labels (latest run dir of each is used)
#        PRE                         seconds of clip before the route start (default 2)
#        HUD_W HUD_H HUD_SCALE       overlay cut-out (default 1420x400, x1.25)
set -euo pipefail
cd "$(dirname "$0")/.."

out="${1:-docs/media/louisville-horde-stock-vs-optimized-vs-player-los.mp4}"
RUN_STOCK="${RUN_STOCK:-losvid-stock}"; RUN_OPT="${RUN_OPT:-losvid-before}"; RUN_ALL="${RUN_ALL:-losvid-after}"
PRE="${PRE:-2}"
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf
[ -f "$FONT" ] || FONT=$(fc-match -f '%{file}' 'DejaVu Sans:bold')

run() { ls -d harness/runs/$1-* | tail -1; }
# per run: clip start (s into the capture), clip length, and the result line
info() {
  local d=$1
  local birth re
  birth=$(stat -c %W "$d/recording.mp4"); re=$(grep '^route_end_epoch_ms=' "$d/pzopt-bench.out" | cut -d= -f2)
  python3 - "$d" "$birth" "$re" "$PRE" <<'PY'
import re, subprocess, sys
import numpy as np
d, birth, rend, pre = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), float(sys.argv[4])
bench = open(d + '/pzopt-bench.out').read()
route_s = float(re.search(r'^route_seconds=([\d.]+)', bench, re.M).group(1))
guess = rend / 1000 - birth - 4
w, h = 64, 27
raw = subprocess.run(['ffmpeg', '-v', 'error', '-ss', f'{guess:.2f}', '-t', '8', '-i', d + '/recording.mp4',
                      '-vf', f'fps=20,scale={w}:{h},format=gray', '-f', 'rawvideo', '-'], capture_output=True, check=True).stdout
n = len(raw) // (w * h)
m = np.frombuffer(raw[:n * w * h], dtype=np.uint8).reshape(n, h, w).astype(float).mean(axis=(1, 2))
black = next(i for i in range(1, n) if m[i] < 8 and m[i - 1] >= 8)
black_t = guess + black / 20
start = black_t - route_s - pre
dur = black_t - start - 0.1
out = subprocess.run(['python3', 'harness/analyze.py', d], capture_output=True, text=True).stdout
# a stock run made with enabled=false before 2026-09-24 wrote no overlay log: its numbers come from the MangoHud line
key = 'overlay:' if 'overlay:' in out else 'mangohud:'
ov = out[out.index(key):]
fps = re.search(r'([\d.]+) fps mean', ov).group(1)
p99 = re.search(r'p99 ([\d.]+)ms', ov).group(1)
p999 = re.search(r'p99\.9 ([\d.]+)ms', ov).group(1)
over = re.search(r'>33ms: (\d+)', ov).group(1)
low = re.search(r'1%-low (\d+) fps', ov).group(1)
mp = re.search(r'^\s+(\d+)%\s+player', out[out.index('game thread ('):], re.M) if 'game thread (' in out else None
player = mp.group(1) if mp else None
res = f'{fps} fps mean  -  p99 {p99} ms  -  p99.9 {p999} ms  -  {over} frames over 33 ms  -  1-percent low {low} fps'
share = f'the player line-of-sight pass takes {player} percent of the game thread' if player else 'no game-thread profile in a stock run'
z = re.search(r'^zombies_loaded=(\d+)', bench, re.M).group(1)
print(f'{start:.2f}|{dur:.2f}|{res}|{share}|{z}|{1 if key == "overlay:" else 0}')
PY
}
S=$(run "$RUN_STOCK"); O=$(run "$RUN_OPT"); A=$(run "$RUN_ALL")
IFS='|' read -r ST_STOCK D_STOCK RES_STOCK SHARE_STOCK Z_STOCK HUD_STOCK < <(info "$S")
IFS='|' read -r ST_OPT D_OPT RES_OPT SHARE_OPT Z_OPT HUD_OPT < <(info "$O")
IFS='|' read -r ST_ALL D_ALL RES_ALL SHARE_ALL Z_ALL HUD_ALL < <(info "$A")
LEN=$(python3 -c "print(int(min($D_STOCK, $D_OPT, $D_ALL)))")
echo "stock  $S: from ${ST_STOCK}s: $RES_STOCK ($Z_STOCK zombies)"
echo "before $O: from ${ST_OPT}s: $RES_OPT ($Z_OPT zombies)"
echo "after  $A: from ${ST_ALL}s: $RES_ALL ($Z_ALL zombies); ${LEN}s"

W=3840; H=1920; CW=1920; CH=810
HUD_W="${HUD_W:-1420}"; HUD_H="${HUD_H:-400}"; HUD_SCALE="${HUD_SCALE:-1.25}"
HW=$(python3 -c "print(int($HUD_W*$HUD_SCALE))"); HH=$(python3 -c "print(int($HUD_H*$HUD_SCALE))")
TITLE_H=110; LABEL_H=50; PAD=$(( (H - TITLE_H - 2*LABEL_H - 2*CH) / 3 ))
Y1L=$TITLE_H; Y1=$((Y1L + LABEL_H)); Y2L=$((Y1 + CH + PAD)); Y2=$((Y2L + LABEL_H))

# PQ-space colours: full white is the display's peak, so text uses ~60 % code values.
TXT=0xb4b4b8; DIM=0x8a8a90; RED=0xb85c5c; AMB=0xb89a5c; GRN=0x5cb878
cell() { # scaled panel with the 1:1 overlay region pasted over its top-left corner ($2 = 1), or plain (a stock run has no overlay)
  if [ "$2" = 1 ]; then
    echo "[$1:v]fps=60,format=yuv420p10le,split[c$1a][c$1b];[c$1a]scale=${CW}:${CH}:flags=lanczos,setsar=1[p$1];[c$1b]crop=${HUD_W}:${HUD_H}:0:0,scale=${HW}:${HH}:flags=lanczos,setsar=1[h$1];[p$1][h$1]overlay=0:0"
  else
    echo "[$1:v]fps=60,format=yuv420p10le,scale=${CW}:${CH}:flags=lanczos,setsar=1"
  fi; }
label() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=36:fontcolor=$TXT:x=$2:y=$3"; }
ptext() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=$3:fontcolor=$4:x=${CW}+(${CW}-tw)/2:y=${Y2}+$2"; }

filter="
color=c=0x060608:s=${W}x${H}:r=60:d=${LEN},format=yuv420p10le[bg];
$(cell 0 "$HUD_STOCK")[s];
$(cell 1 "$HUD_OPT")[o];
$(cell 2 "$HUD_ALL")[g];
[bg][s]overlay=0:${Y1}:shortest=1[b1];
[b1][o]overlay=${CW}:${Y1}[b2];
[b2][g]overlay=0:${Y2},drawbox=x=${CW}-2:y=0:w=4:h=${Y2}+${CH}:color=0x202026:t=fill,drawbox=x=${CW}:y=${Y2}:w=${CW}:h=${CH}:color=0x0c0c10:t=fill[b3];
[b3]drawtext=fontfile=$FONT:text='Project Zomboid B42.20  \\|  downtown Louisville, zombie population x4  \\|  uncapped  \\|  stock vs optimized vs the player line-of-sight pass':fontsize=50:fontcolor=$TXT:x=(w-tw)/2:y=28,
$(label 'STOCK GAME  (every optimization off)' "(${CW}-tw)/2" "${Y1L}+6"),
$(label 'ALL OPTIMIZATIONS, player LOS pass OFF  (playerLosFast / zombieSpotFast off)' "${CW}+(${CW}-tw)/2" "${Y1L}+6"),
$(label 'ALL OPTIMIZATIONS  (player LOS pass on, 2026-09-22)' "(${CW}-tw)/2" "${Y2L}+6"),
$(label "RESULTS  -  25 s route, ~${Z_ALL} zombies loaded, no frame cap" "${CW}+(${CW}-tw)/2" "${Y2L}+6"),
$(ptext 'Teleport into downtown Louisville, spectator view, max zoom, walking south at 6 tiles/s while the camera spins' 30 28 $TXT),
$(ptext 'STOCK GAME' 100 38 $RED),
$(ptext "$RES_STOCK" 146 30 $TXT),
$(ptext 'ALL OPTIMIZATIONS, PLAYER LOS PASS OFF' 220 38 $AMB),
$(ptext "$RES_OPT" 266 30 $TXT),
$(ptext "$SHARE_OPT" 302 28 $DIM),
$(ptext 'ALL OPTIMIZATIONS, PLAYER LOS PASS ON' 374 38 $GRN),
$(ptext "$RES_ALL" 420 30 $TXT),
$(ptext "$SHARE_ALL" 456 28 $DIM),
$(ptext 'Stock searches the list of every zombie spotted since the last quiet moment once per spotted zombie per frame;' 540 28 $DIM),
$(ptext 'in a horde that list never empties, so the search grows with the square of the zombies in view (16 pct of the game thread).' 578 28 $DIM),
$(ptext 'The pass keeps a set beside the list, skips the spot roll of zombies that cannot see you and tests only nearby cars.' 616 28 $DIM),
$(ptext 'Same save, route and hardware. The 0.5 s frame 7 s into the optimized run is stock world generation (a building trashed on load).' 654 28 $DIM),
drawtext=fontfile=$FONT:text='Ryzen 7 9800X3D, 32 GB DDR5 8000 MT/s, RTX 4090, 5120x2160, NVIDIA GL, uncapped  -  the overlay shows frame time and FPS live':fontsize=30:fontcolor=$DIM:x=(w-tw)/2:y=h-th-22,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]
"

mkdir -p "$(dirname "$out")"
ffmpeg -hide_banner -v error -y \
  -ss "$ST_STOCK" -t "$LEN" -i "$S/recording.mp4" \
  -ss "$ST_OPT"   -t "$LEN" -i "$O/recording.mp4" \
  -ss "$ST_ALL"   -t "$LEN" -i "$A/recording.mp4" \
  -filter_complex "$filter;[2:a]atrim=0:${LEN},loudnorm=I=-16:TP=-1.5:LRA=11,afade=t=out:st=$((LEN-3)):d=3[a]" -map '[v]' -map '[a]' -c:a aac -b:a 192k \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 100M -bufsize 200M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
  -movflags +faststart+write_colr -r 60 \
  "$out"

ffmpeg -hide_banner -v error -y -ss 12 -i "$out" -frames:v 1 -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" -q:v 2 "${out%%.mp4}.jpg" || true
ls -la "$out" | awk '{print $5, $9}'
ffprobe -v error -show_entries format=duration:stream=width,height,codec_name,color_transfer -of csv=p=0 "$out"
