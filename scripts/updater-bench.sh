#!/usr/bin/env bash
# Profiles the in-game updater against the real GitHub releases (tests/pzopt/UpdaterBench.java):
# the release check, the delta and whole-zip downloads, the install and the Workshop copy, each next to the
# pre-2026-09-26 updater. Downloads the three release zips it compares into /tmp/pzopt-updater-bench once.
#   scripts/updater-bench.sh [runs] [all|check|local|install|sweep|workshop]
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/pz-env.sh"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$PZ_DIR/projectzomboid.jar"
ZIPS=/tmp/pzopt-updater-bench
OUT="$REPO/build/updater-bench"
[[ -d "$REPO/build/classes" ]] || { echo "run scripts/build.sh first" >&2; exit 1; }
mkdir -p "$ZIPS"
for t in win-b0bbce05d5-ab4b22b win-b0bbce05d5-b70f234 win-b0bbce05d5-f837467; do
  [[ -f "$ZIPS/$t.zip" ]] || gh release download "$t" --repo xD3I/PZ_Optimization -p 'pzopt-*-classes.zip' -O "$ZIPS/$t.zip"
done
rm -rf "$OUT"; mkdir -p "$OUT"
javac --release 25 -nowarn -cp "$REPO/build/classes:$JAR" -d "$OUT" "$REPO/tests/pzopt/UpdaterBench.java"
java -Dpzopt.userOptionsFile="$OUT/none.ini" -cp "$OUT:$REPO/build/classes:$JAR" pzopt.UpdaterBench "$ZIPS" "${1:-3}" "${2:-all}"
