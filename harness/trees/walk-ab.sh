#!/usr/bin/env bash
# Jev-directed tree walks for the tree lighting A/B (2026-09-25): per label a tree-director.py in the background, then the
# queued explore=trees run with a devCapture sequence, then tree-metrics.py on it. Desktop only (the director needs the
# TypeSafe key here).
#
#   harness/trees/walk-ab.sh <label>:<extra run.sh args> ...
#
# e.g. walk-ab.sh "tw-fix:" "tw-before:--prop pplAirFill=false --prop pplPackRing=false --prop sunShadowTrees=false"
#      "tw-ctl:--prop pixelLight=false --prop ambientOcclusion=false --prop sunShadows=false"
# Common scene: TW_START (8147,11507), TW_HOUR (16), TW_TREES (3). Output: harness/runs/<label>-*/trees.json.
set -euo pipefail
cd "$(dirname "$0")/../.."
start=${TW_START:-8147,11507}
hour=${TW_HOUR:-16}
ntrees=${TW_TREES:-3}
for spec in "$@"; do
  label=${spec%%:*}
  extra=${spec#*:}
  python3 harness/trees/tree-director.py --log "/tmp/tree-director-$label.log" --wait 3600 &
  dpid=$!
  # shellcheck disable=SC2086
  harness/queue.sh submit run --machine desktop --install opt --wait \
    --intent "tree lighting A/B: Jev walks to $ntrees trees, circles and watches each, frame capture ($label)" \
    --progress "tree fixes built; confirming with Jev" --resource visual-parity -- \
    --mode bench --launcher direct --flag start="$start" --flag explore=trees --flag director=jev --flag trees_max="$ntrees" \
    --flag zombies=off --flag route=S:1 --flag speed=0.004 --flag time_of_day="$hour" --flag weather=clear --flag zoom=1 \
    --prop overlay=false --no-dashboard --prop hdr=true --prop pixelLight=true --prop ambientOcclusion=true --prop sunShadows=true \
    --prop devCapture=10,50,20,20 $extra --label "$label" | tail -3 || true
  wait "$dpid" || true
  run=$(ls -d harness/runs/"$label"-2* | tail -1)
  python3 harness/trees/tree-metrics.py "$run" --json "$run/trees.json" --png "$run/trees-png" | head -40
done
