#!/usr/bin/env bash
# Stock vs HDR vs HDR enhanced: one 5120x2160 AV1 HDR (PQ / BT.2020) video, a 2x2 grid per scene (stock top left,
# HDR top right, HDR enhanced bottom left, legend bottom right), the scenes joined. Each pane is the full capture scaled
# to 2560x1080; kept PQ end to end (project rule: every video AV1 HDR). Run through the queue (media job).
#   tools/hdr/stitch-hdr-3way.sh [out.mp4]
# Runs: harness/runs/hdrvid-<scene>-{stock,hdr,enh}-* (latest of each), recorded 2026-09-24 with the character turning
# in place (route=S:1 speed=0.1 turn=45 hold=15). Panes are aligned on each run's route start (pzopt-schedule.out
# route_start_epoch_ms - run.opts launch_epoch - the recorder's ~1 s start delay), plus LAG_<scene>_<hdr|enh> seconds
# (the facing's phase differs by a few tenths between runs; found by frame correlation against the stock pane).
set -euo pipefail
cd "$(dirname "$0")/../.."
out=${1:-docs/media/hdr-stock-vs-hdr-vs-enhanced.mp4}
SCENES=${SCENES:-"night day storm"}
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf
TXT=0xb4b4b8; DIM=0x8a8a90; RED=0xb85c5c; AMB=0xb89a5c; GRN=0x5cb878   # PQ code values, ~200 cd/m² for TXT

run() { ls -d harness/runs/hdrvid-$1-* | tail -1; }
start() {
  python3 - "$1" "$2" <<'PY'
import sys
d, lead = sys.argv[1], float(sys.argv[2])
le = int([l for l in open(d + '/run.opts') if l.startswith('launch_epoch=')][0].split('=')[1])
rs = int([l for l in open(d + '/pzopt-schedule.out') if l.startswith('route_start_epoch_ms=')][0].split('=')[1])
print(f"{max(0.0, rs / 1000 - le - 1.0 - lead):.2f}")
PY
}
scene_title() {
  case $1 in
    night) echo 'Night, 1 am - hand torch, street lamps, three fires';;
    day) echo 'Clear day, 3 pm - river shore (sun on the water)';;
    storm) echo 'Thunderstorm, 11 pm - lightning every ~4 s (strikes differ per recording)';;
  esac
}
scene_len() { [ -n "${LEN:-}" ] && { echo "$LEN"; return; }; case $1 in storm) echo 18;; *) echo 14;; esac; }

tmp=$(mktemp -d)
parts=()
for sc in $SCENES; do
  S=$(run $sc-stock); H=$(run $sc-hdr); E=$(run $sc-enh)
  len=$(scene_len $sc)
  lh=$(eval echo "\${LAG_${sc}_hdr:-0}"); le=$(eval echo "\${LAG_${sc}_enh:-0}")
  s0=$(start "$S" 0.5); h0=$(start "$H" "$(python3 -c "print(0.5 - $lh)")"); e0=$(start "$E" "$(python3 -c "print(0.5 - $le)")")
  echo "$sc: stock $S @ $s0 s, hdr $H @ $h0 s, enhanced $E @ $e0 s, $len s"
  lab() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=52:fontcolor=$2:box=1:boxcolor=0x000000@0.55:boxborderw=14:x=(w-tw)/2:y=28"; }
  lg() { echo "drawtext=fontfile=$FONT:text='$1':fontsize=$3:fontcolor=$4:x=2560+80:y=1080+$2"; }
  ffmpeg -hide_banner -v error -y \
    -ss "$s0" -t "$len" -i "$S/recording.mp4" -ss "$h0" -t "$len" -i "$H/recording.mp4" -ss "$e0" -t "$len" -i "$E/recording.mp4" \
    -filter_complex "\
color=c=0x060608:s=5120x2160:r=60:d=$len,format=yuv420p10le[bg];\
[0:v]setpts=PTS-STARTPTS,fps=60,format=yuv420p10le,scale=2560:1080:flags=lanczos,setsar=1,$(lab 'STOCK  (SDR)' $RED)[a];\
[1:v]setpts=PTS-STARTPTS,fps=60,format=yuv420p10le,scale=2560:1080:flags=lanczos,setsar=1,$(lab 'HDR  (highlight expansion only)' $AMB)[b];\
[2:v]setpts=PTS-STARTPTS,fps=60,format=yuv420p10le,scale=2560:1080:flags=lanczos,setsar=1,$(lab 'HDR ENHANCED' $GRN)[c];\
[bg][a]overlay=0:0:shortest=1[t1];[t1][b]overlay=2560:0[t2];[t2][c]overlay=0:1080[t3];\
[t3]drawbox=x=2558:y=0:w=4:h=2160:color=0x202026:t=fill,drawbox=x=0:y=1078:w=5120:h=4:color=0x202026:t=fill,\
$(lg 'Project Zomboid B42  -  HDR output (pzopt)' 90 64 $TXT),\
$(lg "$(scene_title $sc)" 190 44 $DIM),\
$(lg 'STOCK' 320 46 $RED),\
$(lg 'the unmodified game, SDR, the desktop shows it at its SDR white' 380 36 $TXT),\
$(lg 'HDR' 480 46 $AMB),\
$(lg 'HDR window, UI at the desktop white, near-white pixels at night pushed above it' 540 36 $TXT),\
$(lg 'HDR ENHANCED  (the defaults)' 640 46 $GRN),\
$(lg 'plus lamp / torch / headlight / fire light up to the panel peak in its own colour,' 700 36 $TXT),\
$(lg 'sun glints and sky on water and puddles, brighter sunlit ground, lightning, glow' 750 36 $TXT),\
$(lg 'Same save and camera, the character turns in place. Watch on an HDR screen (PQ, BT.2020).' 880 32 $DIM),\
setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]" \
    -map "[v]" -an -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 100M -bufsize 200M \
    -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv -r 60 "$tmp/$sc.mp4"
  parts+=("$tmp/$sc.mp4")
done
printf "file '%s'\n" "${parts[@]}" > "$tmp/list.txt"
mkdir -p "$(dirname "$out")"
ffmpeg -hide_banner -v error -y -f concat -safe 0 -i "$tmp/list.txt" -c copy -avoid_negative_ts make_zero -fflags +genpts \
  -movflags +faststart+write_colr "$out"
ffmpeg -hide_banner -v error -y -ss 6 -i "$out" -frames:v 1 \
  -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" \
  -q:v 2 "${out%.mp4}.jpg" || true
rm -rf "$tmp"
ls -la "$out" "${out%.mp4}.jpg"
