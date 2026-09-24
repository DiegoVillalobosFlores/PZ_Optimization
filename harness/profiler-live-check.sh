#!/usr/bin/env bash
# Do the Options > Profiler settings apply without a restart? (2026-09-24)
# Launches the installed game to the main menu (Steam off) with the overlay keys removed from Zomboid/pzopt/options.ini,
# then: F9 -> the "sampling is off" notice (no restart wording); OPTIONS -> Profiler -> tick "Sample frame times" and
# "Show the overlay from boot" -> ACCEPT -> the overlay must show at once, no restart dialog; OPTIONS -> Profiler ->
# untick "Show the overlay from boot" -> ACCEPT -> the overlay must be gone. Closes the game itself (trap, saved PID,
# hard deadline) and restores the player's options file.
# Run through the queue: harness/queue.sh submit cmd --label <l> -- harness/profiler-live-check.sh
set -u
cd "$(dirname "$0")/.."
source scripts/pz-env.sh
OUT=${OUT:-/tmp/profiler-live-check}; rm -rf "$OUT"; mkdir -p "$OUT"
UI=harness/ui-drive.py
DEADLINE=$(( $(date +%s) + 300 ))
scripts/pzopt.sh status | grep -q "^installed: *yes" || { echo "overrides not installed"; exit 1; }
OPT="$ZOMBOID/pzopt/options.ini"

shot() { spectacle -b -n -f -o "$OUT/$1.png" >/dev/null 2>&1; python3 $UI read --screen "$OUT/$1.png" >"$OUT/$1.ocr.txt" 2>/dev/null; }
# OCR lines of the current screen as "x y w h text" (screen px), from ui-drive's own OCR
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
press() { # <x> <y> screen px
  xdotool mousemove "$(( $1 * 100 / 125 ))" "$(( $2 * 100 / 125 ))"; sleep 0.3
  xdotool mousedown 1; sleep 0.15; xdotool mouseup 1; sleep 1.0
}
click_text() { # <regex> [top|first]: the matching OCR line (the topmost with "top")
  local l
  if [[ "${2:-first}" == top ]]; then l=$(lines | grep -E "^[0-9]+ [0-9]+ [0-9]+ [0-9]+ $1" | sort -n -k2 | head -1)
  else l=$(lines | grep -E "^[0-9]+ [0-9]+ [0-9]+ [0-9]+ $1" | head -1); fi
  [[ -n "$l" ]] || { echo "no OCR line for /$1/"; return 1; }
  read -r x y w h t <<<"$l"; press "$x" "$y"; echo "clicked /$1/ at $x,$y"
}
tick_row() { # <label regex>: the tick box sits right of its right-aligned label (MainOptions:addYesNo), ~38 px from
  # the text's end to the box centre at 5120x2160; the leftmost match is the row label (the preview repeats it as a title)
  local l
  l=$(lines | grep -E "^[0-9]+ [0-9]+ [0-9]+ [0-9]+ .*$1" | sort -n -k1 | head -1)
  [[ -n "$l" ]] || { echo "no OCR line for /$1/"; return 1; }
  read -r x y w h t <<<"$l"; press "$(( x + w / 2 + 38 ))" "$y"; echo "ticked /$1/ at $(( x + w / 2 + 38 )),$y"
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
  echo "options restored"
}
[[ -f "$OPT" ]] && cp "$OPT" "$OUT/pzopt-options.ini.orig"
trap cleanup EXIT
mkdir -p "$(dirname "$OPT")"
grep -vE '^(overlay|gameThreadProfileHz)' "$OUT/pzopt-options.ini.orig" 2>/dev/null >"$OPT" || true
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

# 1. F9 with sampling off: the notice, without the old "restart" wording
xdotool key F9; sleep 1.5; shot notice
grep -qi "sampling is off" "$OUT/notice.ocr.txt" && pass "F9 notice shown" || fail "F9 notice not found"
grep -qi "restart the game" "$OUT/notice.ocr.txt" && fail "notice still says restart" || pass "notice has no restart wording"
sleep 7 # the notice times out (8 s)

# 2. tick sampling + show from boot, ACCEPT: overlay shows now, no restart dialog
click_text "OPTIONS *$" || exit 2; sleep 2
click_text "Profiler" top || exit 2; sleep 2; shot profiler-tab
tick_row "Sample frame times" || exit 2
tick_row "Show the overlay from boot" || exit 2
shot ticked
click_text "ACCEPT" || exit 2; sleep 3; shot accepted
grep -qiE "restart" "$OUT/accepted.ocr.txt" && fail "a restart dialog / text after ACCEPT" || pass "no restart dialog after ACCEPT"
grep -qiE "[0-9]+ *fps" "$OUT/accepted.ocr.txt" && pass "overlay visible after ACCEPT (fps line)" || fail "no overlay fps line after ACCEPT"
grep -a "applied now\|Profiler settings applied" "$ZOMBOID/console.txt" | tail -4
grep -aq "overlay: Profiler settings applied (sampling true, visible true" "$ZOMBOID/console.txt" && pass "console: sampling + visible applied" || fail "console has no 'sampling true, visible true'"

# 3. untick show from boot, ACCEPT: overlay hidden now
click_text "OPTIONS *$" || exit 2; sleep 2
click_text "Profiler" top || exit 2; sleep 2
tick_row "Show the overlay from boot" || exit 2
shot unticked
click_text "ACCEPT" || exit 2; sleep 3; shot hidden
grep -qiE "[0-9]+ *fps" "$OUT/hidden.ocr.txt" && fail "overlay fps line still visible" || pass "overlay hidden after unticking"
grep -aq "overlay: Profiler settings applied (sampling true, visible false" "$ZOMBOID/console.txt" && pass "console: hidden applied" || fail "console has no 'sampling true, visible false'"
grep -a "overlay:\|options:" "$ZOMBOID/console.txt" | tail -20 >"$OUT/console-overlay.txt"
cp "$OPT" "$OUT/pzopt-options.ini.after"
cp "$ZOMBOID/console.txt" "$OUT/console.txt"

kill "$GAME_PID" 2>/dev/null; sleep 4; GAME_PID_DONE=$GAME_PID; GAME_PID=""
kill -0 "$GAME_PID_DONE" 2>/dev/null && kill -9 "$GAME_PID_DONE"
echo "fails=$FAILS (screens in $OUT)"
exit $FAILS
