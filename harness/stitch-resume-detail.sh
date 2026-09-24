#!/usr/bin/env bash
# The fake loading screen (pzopt.ResumeShot) at the four resumeShotDetail levels, 2x2, each panel the screen at half
# size, aligned on the loading frame (world ready - load time from the console), AV1 10-bit HDR like every video here.
#   harness/stitch-resume-detail.sh <runs dir> [out.mp4]
# <runs dir> holds detailB-{floors,buildings,world,full}-* (harness/.resume-detail.sh in the resume worktree, 2026-09-24).
set -euo pipefail
RUNS=${1:?runs dir}
OUT=${2:-docs/media/resume-shot-detail-levels.mp4}
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf
TXT=0xb4b4b8; DIM=0x8a8a90
LEAD=0.6   # seconds before the loading frame
TAIL=4.0   # seconds after world entry
inputs=(); starts=(); lens=(); labels=()
for d in floors buildings world full; do
  run=$(ls -d "$RUNS"/detailB-"$d"-* | tail -1)
  ready=$(grep -o 'world ready [0-9]*' "$run/schedule.log" | grep -o '[0-9]*$')
  load=$(grep -ao 'loading frame to world entry [0-9]*' "$run/console.txt" | grep -o '[0-9]*$')
  start=$(python3 -c "print(max(0, $ready + 0.7 - $load / 1000 - $LEAD))")
  inputs+=("$run/recording.mp4"); starts+=("$start"); lens+=("$(python3 -c "print($LEAD + $load / 1000 + $TAIL)")")
  labels+=("$d  -  load $(python3 -c "print(f'{$load / 1000:.1f}')") s")
  echo "$d: $run start=$start load=${load} ms"
done
LEN=$(printf '%s\n' "${lens[@]}" | sort -n | tail -1)
args=()
for i in 0 1 2 3; do args+=(-ss "${starts[$i]}" -t "$LEN" -i "${inputs[$i]}"); done
fc=""
for i in 0 1 2 3; do
  fc+="[$i:v]scale=2560:1080:flags=lanczos,tpad=stop_mode=clone:stop_duration=5,trim=duration=$LEN,setpts=PTS-STARTPTS,"
  fc+="drawtext=fontfile=$FONT:text='${labels[$i]}':fontsize=40:fontcolor=$TXT:box=1:boxcolor=0x000000@0.6:boxborderw=12:x=24:y=h-th-28[p$i];"
done
fc+="[p0][p1][p2][p3]xstack=inputs=4:layout=0_0|w0_0|0_h0|w0_h0[g];"
fc+="[g]pad=5120:2240:0:80,drawtext=fontfile=$FONT:text='Fake loading screen on Continue  \\|  exit shot detail\\: floors / buildings / world / full  \\|  same Rosewood corner, each panel half size':fontsize=44:fontcolor=$TXT:x=(w-tw)/2:y=18,"
fc+="setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]"
mkdir -p "$(dirname "$OUT")"
ffmpeg -y -v error "${args[@]}" -filter_complex "$fc" -map '[v]' -an \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 120M -bufsize 240M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
  -movflags +faststart+write_colr -r 60 -t "$LEN" "$OUT"
echo "wrote $OUT ($LEN s)"
