#!/usr/bin/env bash
# Side-by-side AV1 HDR video of two recordings of the same scene: stock SDR game (left) vs the HDR output (right).
# Both captures are gpu-screen-recorder av1_hdr (PQ / BT.2020, KWin's output), so the HDR pane shows real nits and
# the SDR pane the SDR window as KWin shows it; kept PQ / BT.2020 end to end (project rule: all videos AV1 HDR).
#   tools/hdr/stitch-hdr-sbs.sh <sdr run> <sdr start s> <hdr run> <hdr start s> <duration s> <out.mp4> [crop x0 as fraction, default 0.25]
# Each pane is the centre half of its 5120-wide capture (cropped at x0 .. x0 + w/2), stacked into one 5120x2160 frame.
set -euo pipefail
sdr=$1; s0=$2; hdr=$3; h0=$4; dur=$5; out=$6; cx=${7:-0.25}
[[ -d "$sdr" ]] && sdr="$sdr/recording.mp4"
[[ -d "$hdr" ]] && hdr="$hdr/recording.mp4"
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf
TXT=0xb4b4b8   # ~200 cd/m² in PQ
ffmpeg -hide_banner -v error -y \
  -ss "$s0" -t "$dur" -i "$sdr" -ss "$h0" -t "$dur" -i "$hdr" \
  -filter_complex "\
[0:v]setpts=PTS-STARTPTS,fps=60,crop=iw/2:ih:iw*${cx}:0,drawtext=fontfile=$FONT:text='STOCK (SDR)':fontsize=56:fontcolor=$TXT:x=(w-tw)/2:y=40[a];\
[1:v]setpts=PTS-STARTPTS,fps=60,crop=iw/2:ih:iw*${cx}:0,drawtext=fontfile=$FONT:text='HDR (pzopt)':fontsize=56:fontcolor=$TXT:x=(w-tw)/2:y=40[b];\
[a][b]hstack=inputs=2,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]" \
  -map "[v]" -an -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 100M -bufsize 200M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv "$out"
ffmpeg -hide_banner -v error -y -ss "$((${dur%.*} / 2))" -i "$out" -frames:v 1 \
  -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" \
  -q:v 2 "${out%.mp4}.jpg" || true
ls -la "$out" "${out%.mp4}.jpg"
