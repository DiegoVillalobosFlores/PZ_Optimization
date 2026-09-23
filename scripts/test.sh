#!/usr/bin/env bash
# Unit tests for the pzopt classes that do not need the game running.
# Compiles tests/ against build/classes and the game jar, then runs each *Test main.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/pz-env.sh"
JAR="$PZ_DIR/projectzomboid.jar"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$REPO/build/tests"
[[ -d "$REPO/build/classes" ]] || { echo "run scripts/build.sh first" >&2; exit 1; }
rm -rf "$OUT"; mkdir -p "$OUT"
javac --release 25 -nowarn -cp "$REPO/build/classes:$JAR" -d "$OUT" $(find "$REPO/tests" -name '*.java')
fail=0
# never the player's Zomboid/pzopt/options.ini: a saved menu choice (dlssPreset=e on this machine, 2026-09-24) made
# UpscalerTest read a non-default Config value and fail the release; tests that need the file set their own path
NO_USER_OPTIONS="$OUT/no-user-options.ini"
for t in $(cd "$OUT" && find . -name '*Test.class' | sed 's|^\./||;s|\.class$||;s|/|.|g' | sort); do
  if ! java -Dpzopt.dev=true -Dpzopt.userOptionsFile="$NO_USER_OPTIONS" -cp "$OUT:$REPO/build/classes:$JAR" "$t"; then echo "FAILED: $t" >&2; fail=1; fi
done
exit $fail
