#!/usr/bin/env bash
# HDR showcase reel: three stock-SDR vs HDR side-by-side clips (fires + torch, headlights, lightning storm) joined into
# one AV1 HDR (PQ / BT.2020) video. Run through the queue (media job). Clip windows are route-aligned recording seconds
# (route_start_epoch_ms - launch_epoch per run, 2026-09-24).
set -euo pipefail
cd "$(dirname "$0")/../.."
R=harness/runs
out=${1:-docs/media/hdr-showcase-sdr-vs-hdr.mp4}
tmp=$(mktemp -d)
tools/hdr/stitch-hdr-sbs.sh $R/hdrcmp-sdr-20260924-090932 14.2 $R/hdrcmp-ours2-20260924-092912 14.5 7.5 "$tmp/1.mp4" 0.3
tools/hdr/stitch-hdr-sbs.sh $R/hdrcmp-drive-sdr-20260924-093601 16.7 $R/hdr19-nightdrive-20260924-093312 16.1 13.5 "$tmp/2.mp4" 0.25
tools/hdr/stitch-hdr-sbs.sh $R/hdrcmp-storm-sdr-20260924-093905 17.4 $R/hdrcmp-storm-ours2-20260924-094558 18.4 18 "$tmp/3.mp4" 0.25
printf "file '%s'\n" "$tmp/1.mp4" "$tmp/2.mp4" "$tmp/3.mp4" > "$tmp/list.txt"
ffmpeg -hide_banner -v error -y -f concat -safe 0 -i "$tmp/list.txt" -c copy -avoid_negative_ts make_zero -fflags +genpts "$out"
cp "$tmp/1.jpg" "${out%.mp4}.jpg"
rm -rf "$tmp"
ls -la "$out"
