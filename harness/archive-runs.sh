#!/usr/bin/env bash
# Move the run dirs older than a cutoff out of harness/runs/ into harness/archive/<date>/runs/ (same disk: a rename,
# instant and reversible with mv back). The runs named with a timestamp before the cutoff move; newer ones stay.
#
#   harness/archive-runs.sh [--cutoff 2026-09-24T11:15:00+02:00] [--dest harness/archive/2026-09-24/runs] [--dry-run]
#
# Default cutoff: harness/grafana/since (the Grafana follower imports the runs after it). Submit it as a queue cmd job so
# it never moves a run dir while a job writes into harness/runs/.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
cutoff="$(sed 's/#.*//' "$REPO/harness/grafana/since" | tr -d ' \n')"
dest=""
dry=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --cutoff) cutoff="$2"; shift 2 ;;
    --dest) dest="$2"; shift 2 ;;
    --dry-run) dry=1; shift ;;
    *) sed -n 2,8p "$0"; exit 2 ;;
  esac
done
stamp="$(date -d "$cutoff" +%Y%m%d-%H%M%S)"
dest="${dest:-$REPO/harness/archive/$(date -d "$cutoff" +%Y-%m-%d)/runs}"
mkdir -p "$dest"
moved=0 kept=0
for d in "$REPO"/harness/runs/*/; do
  d="${d%/}"
  name="$(basename "$d")"
  ts="$(grep -oE '[0-9]{8}-[0-9]{6}$' <<<"$name" || true)"
  # a dir without a timestamp is from before the naming scheme: archive it too
  if [[ -n "$ts" && ! "$ts" < "$stamp" ]]; then
    kept=$((kept + 1))
    continue
  fi
  if [[ -e "$dest/$name" ]]; then
    echo "skip $name: already in $dest" >&2
    continue
  fi
  [[ $dry -eq 1 ]] || mv "$d" "$dest/"
  moved=$((moved + 1))
done
# loose files (run.sh's .last-props etc.) stay: run.sh reads them
echo "archived $moved run dirs to $dest (before $cutoff), kept $kept$([[ $dry -eq 1 ]] && echo ' [dry run]')"
