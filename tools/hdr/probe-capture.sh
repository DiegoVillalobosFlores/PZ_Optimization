#!/usr/bin/env bash
# Runs /tmp/hdrprobe in each output mode while gpu-screen-recorder captures the monitor in AV1 HDR, then
# extracts one frame per mode as 16-bit PQ RGB (no tone mapping) for tools/hdr/pqpatches.py.
#   tools/hdr/probe-capture.sh <outdir> [modes...]
set -u
out=${1:?outdir}; shift
modes=("$@"); [ ${#modes[@]} -eq 0 ] && modes=(info scrgb pq203 pqabs linear)
mkdir -p "$out"
mon=$(gpu-screen-recorder --list-monitors 2>/dev/null | head -1 | cut -d'|' -f1)
for m in "${modes[@]}"; do
   gpu-screen-recorder -w "${mon:-DP-1}" -f 30 -q very_high -k av1_hdr -cursor no -o "$out/$m.mp4" > "$out/$m.rec.log" 2>&1 &
   rec=$!
   sleep 1.5
   /tmp/hdrprobe "$m" 4 > "$out/$m.probe.txt" 2>&1
   kill -INT $rec; wait $rec
   ffmpeg -loglevel error -y -ss 3.5 -i "$out/$m.mp4" -frames:v 1 \
      -vf "scale=in_color_matrix=bt2020:in_range=tv:out_range=pc,format=rgb48le" "$out/$m.png"
done
ls -la "$out"
