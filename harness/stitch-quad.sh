#!/usr/bin/env bash
# Stitch four drive recordings (60 & 120 km/h, stock vs optimized) into one
# 2:1 (3840x1920) quad-view video for YouTube: stock on the left, optimized on
# the right, 60 km/h on top, 120 km/h on the bottom. 2:1 sits between the
# native 21:9 recordings and 16:9, so it fills phone screens with small bars
# and monitors with small bars. Each 2560x1080 recording is scaled to 1920x810
# and letterboxed into its 1920x960 cell; the spare rows hold the labels.
#
# Usage: harness/stitch-quad.sh [out.mp4]
# Env:   ST_S60 ST_O60 ST_S120 ST_O120 (seconds into each recording where its
#        clip begins: motion onset minus 1.5 s, measured by frame differencing;
#        the route started ~73-74 s after launch with --lead 75; since 2026-09-19
#        run.sh starts the route settle s after the world is up, so read the
#        offset from the run's pzopt-schedule.out / run.opts launch_epoch)
#        LEN60 / LEN120 (clip length per row; the 120 km/h cells freeze on
#        their last frame until the 60 km/h cells finish)
set -euo pipefail
cd "$(dirname "$0")/.."

out="${1:-docs/media/drive-60-120kmh-stock-vs-optimized-quad.mp4}"
ST_S60="${ST_S60:-73.0}"; ST_O60="${ST_O60:-72.5}"; ST_S120="${ST_S120:-72.0}"; ST_O120="${ST_O120:-72.0}"
LEN60="${LEN60:-79}"; LEN120="${LEN120:-40.5}"   # 120 km/h clips end at route completion (38.7 s + 1.5 s lead), before the car coasts on
FONT=/usr/share/fonts/noto/NotoSans-Bold.ttf

run() { ls -d harness/runs/$1-*/recording.mp4 | tail -1; }
S60=$(run quad6-stock60-1);  O60=$(run quad6-opt60-1)      # runs made with Steam's performance monitor OFF (it pins the GL thread: ~160 fps instead of 240; see docs/archive/2026-09-24/results.md 2026-09-19)
S120=$(run quad6-stock120-1); O120=$(run quad6-opt120-2)    # config/mangohud-showcase-opt.conf (HUD left edge)

W=3840; H=1920; CW=1920; CH=810
TITLE_H=110; LABEL_H=50; PAD=$(( (H - TITLE_H - 2*LABEL_H - 2*CH) / 3 ))   # 30
Y1L=$TITLE_H; Y1=$((Y1L + LABEL_H)); Y2L=$((Y1 + CH + PAD)); Y2=$((Y2L + LABEL_H))
HOLD=$(python3 -c "print($LEN60 - $LEN120)")

cell() { # $1=label-in-stream idx
  echo "[$1:v]scale=${CW}:${CH}:flags=lanczos,setsar=1,fps=60,format=yuv420p"
}
done_tag="drawtext=fontfile=$FONT:text='ROUTE COMPLETE':fontsize=54:fontcolor=white:borderw=3:bordercolor=black:x=(w-tw)/2:y=h-th-40:enable='gte(t,${LEN120})'"
label() { # $1=text $2=x $3=y
  echo "drawtext=fontfile=$FONT:text='$1':fontsize=36:fontcolor=white:x=$2:y=$3"
}

filter="
color=c=0x0d0d10:s=${W}x${H}:r=60:d=${LEN60}[bg];
$(cell 0)[s60];
$(cell 1)[o60];
$(cell 2),tpad=stop_mode=clone:stop_duration=${HOLD},${done_tag}[s120];
$(cell 3),trim=0:${LEN120},setpts=PTS-STARTPTS,tpad=stop_mode=clone:stop_duration=${HOLD},${done_tag}[o120];
[bg][s60]overlay=0:${Y1}:shortest=1[b1];
[b1][o60]overlay=${CW}:${Y1}[b2];
[b2][s120]overlay=0:${Y2}[b3];
[b3][o120]overlay=${CW}:${Y2},drawbox=x=${CW}-2:y=0:w=4:h=${Y2}+${CH}:color=0x303038:t=fill[b4];
[b4]drawtext=fontfile=$FONT:text='Project Zomboid B42  \\|  driving frame time  \\|  stock vs optimized':fontsize=56:fontcolor=white:x=(w-tw)/2:y=28,
$(label 'STOCK   -   60 km/h' "(${CW}-tw)/2" "${Y1L}+6"),
$(label 'OPTIMIZED   -   60 km/h' "${CW}+(${CW}-tw)/2" "${Y1L}+6"),
$(label 'STOCK   -   120 km/h' "(${CW}-tw)/2" "${Y2L}+6"),
$(label 'OPTIMIZED   -   120 km/h' "${CW}+(${CW}-tw)/2" "${Y2L}+6"),
drawtext=fontfile=$FONT:text='same save, same route, same hardware (Ryzen 7 9800X3D, 32 GB DDR5 8000 MT/s, RTX 4090, 5120x2160, max zoom)  -  MangoHud overlay shows frame time and FPS':fontsize=30:fontcolor=0xa0a0a8:x=(w-tw)/2:y=h-th-22[v]
"

mkdir -p "$(dirname "$out")"
ffmpeg -hide_banner -y \
  -ss "$ST_S60"  -t "$LEN60"  -i "$S60" \
  -ss "$ST_O60"  -t "$LEN60"  -i "$O60" \
  -ss "$ST_S120" -t "$LEN120" -i "$S120" \
  -ss "$ST_O120" -t "$LEN60"  -i "$O120" \
  -filter_complex "$filter;[v]format=yuv420p,setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv,zscale=t=linear:npl=203,format=gbrpf32le,zscale=pin=bt709:tin=linear:p=bt2020:t=smpte2084:m=bt2020nc:r=tv:npl=203,format=yuv420p10le,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[vh];[3:a]atrim=0:${LEN60},loudnorm=I=-16:TP=-1.5:LRA=11,afade=t=out:st=$((LEN60-3)):d=3[a]" -map '[vh]' -map '[a]' -c:a aac -b:a 192k \
  `# the quad6 captures are SDR H.264: the composed SDR frame is mapped to PQ/BT.2020 (reference white 203 nits) so every published video is AV1 HDR` \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 100M -bufsize 200M \
  -pix_fmt p010le -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv \
  -movflags +faststart+write_colr -r 60 \
  "$out"

# Thumbnail: frame ~30 s in (all four cells moving).
ffmpeg -hide_banner -v error -y -ss 30 -i "$out" -frames:v 1 -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" -q:v 2 "${out%.mp4}.jpg"
echo "wrote $out (AV1 10-bit HDR PQ/BT.2020 from SDR captures) and ${out%.mp4}.jpg"
