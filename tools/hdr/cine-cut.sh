#!/usr/bin/env bash
# Cut the Jev-directed HDR horde cinematic (run cine-jev2, 2026-09-24) into docs/media: full size (local) + -1080 copy + posters.
# Queue media job: harness/queue.sh submit media --label cine-cut -- tools/hdr/cine-cut.sh
set -euo pipefail
cd /home/diegov/Documents/ZedProjects/PZ_Optimization-hdr
src=harness/runs/cine-jev2-20260924-151117/recording.mp4
out=docs/media/hdr-horde-cinematic-jev.mp4
ffmpeg -hide_banner -v error -y -ss 12.87 -t 40 -i "$src" -vf "setpts=PTS-STARTPTS,fps=60,format=yuv420p10le,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv" -an \
  -c:v av1_nvenc -preset p7 -tune hq -rc vbr -cq 22 -b:v 0 -maxrate 100M -bufsize 200M -pix_fmt p010le \
  -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -color_range tv -movflags +faststart+write_colr "$out"
ffmpeg -hide_banner -v error -y -ss 24 -i "$out" -frames:v 1 -vf "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=100,format=gbrpf32le,tonemap=hable:desat=0,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p" -q:v 2 "${out%.mp4}.jpg"
CQ=30 NOJPG=1 harness/encode-av1-hdr.sh "$out" "${out%.mp4}-1080.mp4" 2560
python3 -c "
from PIL import Image
im=Image.open('${out%.mp4}.jpg'); im.resize((2560, round(im.height*2560/im.width)), Image.LANCZOS).save('${out%.mp4}-1080.jpg', quality=88)"
ls -la "$out" "${out%.mp4}"*.jpg "${out%.mp4}-1080.mp4"
