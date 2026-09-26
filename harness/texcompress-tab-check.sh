#!/usr/bin/env bash
# Does the Options > Optimizations "Texture compression: who compresses" combo (key texCompress, 2026-09-26) show and
# save? Launches the installed game to the main menu (Steam off), OPTIONS -> Optimizations -> search "texture
# compression": the row and its value auto; the mouse on it -> the preview's description; the combo -> worker -> ACCEPT
# (+ the restart dialog's OK) -> Zomboid/pzopt/options.ini must hold texCompress=worker. Closes the game itself (trap,
# saved PID, hard deadline) and restores both options files anyway.
# Run through the queue: harness/queue.sh submit cmd --label <l> -- harness/texcompress-tab-check.sh
set -u
cd "$(dirname "$0")/.."
source scripts/pz-env.sh
OUT=${OUT:-/tmp/texcompress-tab-check}; rm -rf "$OUT"; mkdir -p "$OUT"
UI=harness/ui-drive.py
DEADLINE=$(( $(date +%s) + 300 ))
scripts/pzopt.sh status | grep -q "^installed: *yes" || { echo "overrides not installed"; exit 1; }
OPT="$ZOMBOID/pzopt/options.ini"
GAMEOPT="$ZOMBOID/options.ini"

shot() { spectacle -b -n -f -o "$OUT/$1.png" >/dev/null 2>&1; python3 $UI read --screen "$OUT/$1.png" >"$OUT/$1.ocr.txt" 2>/dev/null; }
# OCR lines of the current screen as "x y w h text" (screen px, x / y the centre), from ui-drive's own OCR
lines() {
  spectacle -b -n -f -o "$OUT/cur.png" >/dev/null 2>&1
  python3 - "$OUT/cur.png" <<'EOF'
import sys
sys.path.insert(0, "harness")
import importlib.util
spec = importlib.util.spec_from_file_location("ud", "harness/ui-drive.py"); ud = importlib.util.module_from_spec(spec); spec.loader.exec_module(ud)
for L in ud.ocr_lines(sys.argv[1]):
    print(L["x"], L["y"], L["w"], L["h"], L["text"])
EOF
}
hover() { xdotool mousemove "$(( $1 * 100 / 125 ))" "$(( $2 * 100 / 125 ))"; sleep 0.3; } # screen px (pointer scale 1.25)
press() { hover "$1" "$2"; xdotool mousedown 1; sleep 0.15; xdotool mouseup 1; sleep 1.0; }
find_line() { # <regex> [top|left]
  case "${2:-first}" in
    top) lines | grep -E "^[0-9]+ [0-9]+ [0-9]+ [0-9]+ $1" | sort -n -k2 | head -1 ;;
    left) lines | grep -E "^[0-9]+ [0-9]+ [0-9]+ [0-9]+ .*$1" | sort -n -k1 | head -1 ;;
    *) lines | grep -E "^[0-9]+ [0-9]+ [0-9]+ [0-9]+ $1" | head -1 ;;
  esac
}
click_text() { # <regex> [top|left]
  local l; l=$(find_line "$1" "${2:-first}")
  [[ -n "$l" ]] || { echo "no OCR line for /$1/"; return 1; }
  read -r x y w h t <<<"$l"; press "$x" "$y"; echo "clicked /$1/ at $x,$y"
}
wait_for() { # <regex> <seconds>
  local i
  for i in $(seq 1 "$2"); do lines | grep -qE "$1" && return 0; sleep 1; (( $(date +%s) > DEADLINE )) && return 1; done
  return 1
}

GAME_PID=""
cleanup() {
  xdotool key Escape 2>/dev/null
  [[ -n "$GAME_PID" ]] && kill "$GAME_PID" 2>/dev/null; sleep 3
  [[ -n "$GAME_PID" ]] && kill -9 "$GAME_PID" 2>/dev/null
  if [[ -f "$OUT/pzopt-options.ini.orig" ]]; then cp "$OUT/pzopt-options.ini.orig" "$OPT"; else rm -f "$OPT"; fi
  [[ -f "$OUT/options.ini.orig" ]] && cp "$OUT/options.ini.orig" "$GAMEOPT"
  echo "options restored"
}
[[ -f "$OPT" ]] && cp "$OPT" "$OUT/pzopt-options.ini.orig"
[[ -f "$GAMEOPT" ]] && cp "$GAMEOPT" "$OUT/options.ini.orig"
trap cleanup EXIT
( cd "$PZ_DIR/.." && JAVA_TOOL_OPTIONS="-Dzomboid.steam=0 -Dpzopt.updateCheck=false" exec setsid ./projectzomboid.sh </dev/null >"$OUT/game.log" 2>&1 ) &
sleep 3; GAME_PID=$(pgrep -f '^([^ ]*/)?ProjectZomboid64( |$)' | head -1)
[[ -n "$GAME_PID" ]] || { echo "game did not start"; exit 2; }
echo "game pid $GAME_PID"
wait_for "OPTIONS" 90 || { echo "main menu did not appear"; exit 2; }
sleep 3
xdotool search --name "Project Zomboid" windowactivate 2>/dev/null; sleep 0.5
FAILS=0
pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*"; FAILS=$((FAILS + 1)); }
has() { grep -qiE "$2" "$OUT/$1.ocr.txt"; }

# 1. the row, found through the tab's search box (right of its "Search settings" label)
click_text "OPTIONS *$" || exit 2; sleep 2
click_text "Optimizations" top || exit 2; sleep 3
l=$(find_line "Search settings") || true
[[ -n "$l" ]] || { fail "no search box"; exit 2; }
read -r x y w h t <<<"$l"; press "$(( x + w / 2 + 140 ))" "$y"
xdotool type --delay 40 "texture compression"; sleep 2.5; shot search
has search "who compresses" && pass "row 'Texture compression: who compresses' listed" || fail "row not found by the search"
has search "Default \(auto" && pass "value Default (auto ...) shown" || fail "combo does not show Default (auto ...)"
# the combo's position now: with the mouse on the row, the OCR reads label and combo as one line
cl=$(lines | awk '$5 ~ /^Default/ && $0 ~ /Default \(auto/' | head -1)

# 2. the preview's description for it
l=$(find_line "who compresses" left) || true
if [[ -n "$l" ]]; then
  read -r x y w h t <<<"$l"; hover "$x" "$y"; sleep 2.5; shot preview
  has preview "Texture compression.*option on|graphics driver|file threads" && pass "preview description shown" || fail "no preview description"
fi

# 3. the combo -> worker -> ACCEPT: saved to Zomboid/pzopt/options.ini
if [[ -n "$cl" ]]; then
  read -r cx cy cw ch ct <<<"$cl"; press "$cx" "$cy"; sleep 1; shot combo-open
  click_text "worker \\(the file" || fail "no worker entry in the open combo"
else
  fail "combo (Default (auto ...)) not found on the row"
fi
sleep 1; shot combo-worker
has combo-worker "worker \(the file" && pass "combo shows worker" || fail "combo does not show worker"
click_text "ACCEPT" || fail "no ACCEPT"; sleep 2; shot accepted
click_text "^.*\bOK\b *$" >/dev/null 2>&1   # the restart-required dialog
sleep 1
grep -q "^texCompress=worker" "$OPT" 2>/dev/null && pass "options.ini: texCompress=worker" || fail "options.ini: $(grep texCompress "$OPT" 2>/dev/null || echo 'no texCompress line')"
cp "$OPT" "$OUT/pzopt-options.ini.after" 2>/dev/null

grep -a "options tab" "$ZOMBOID/console.txt" | tail -4 | tee "$OUT/console-tab.txt"
grep -aq "unknown key texCompress" "$ZOMBOID/console.txt" && fail "console: unknown key texCompress" || pass "console: key known"
grep -aq "build failed" "$ZOMBOID/console.txt" && fail "console: a tab build failed" || pass "console: no build failure"
cp "$ZOMBOID/console.txt" "$OUT/console.txt"

kill "$GAME_PID" 2>/dev/null; sleep 4; GAME_PID_DONE=$GAME_PID; GAME_PID=""
kill -0 "$GAME_PID_DONE" 2>/dev/null && kill -9 "$GAME_PID_DONE"
echo "fails=$FAILS (screens in $OUT)"
exit $FAILS
