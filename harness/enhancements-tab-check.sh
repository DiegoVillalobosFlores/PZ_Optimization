#!/usr/bin/env bash
# Does the Options > Enhancements tab (2026-09-25) build and show its previews? Launches the installed game to the main
# menu (Steam off), then: OPTIONS -> Enhancements: the three sections (Upscaling, HDR output, Ambient occlusion), its Reset
# and Install DLSS files buttons; the mouse over an Upscaler / HDR / AO row -> screenshots of the preview (the off / on clips);
# OPTIONS -> Optimizations: no upscaler / HDR / AO rows left there; its "Low-end hardware + FSR 1.0 upscaling" button ->
# Enhancements shows Upscaler fsr1 (the profile reaches the other tab). Never presses Apply / Accept; closes the game itself
# (trap, saved PID, hard deadline) and restores both options files anyway.
# Run through the queue: harness/queue.sh submit cmd --label <l> -- harness/enhancements-tab-check.sh
set -u
cd "$(dirname "$0")/.."
source scripts/pz-env.sh
OUT=${OUT:-/tmp/enhancements-tab-check}; rm -rf "$OUT"; mkdir -p "$OUT"
UI=harness/ui-drive.py
DEADLINE=$(( $(date +%s) + 300 ))
scripts/pzopt.sh status | grep -q "^installed: *yes" || { echo "overrides not installed"; exit 1; }
OPT="$ZOMBOID/pzopt/options.ini"
GAMEOPT="$ZOMBOID/options.ini"

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
hover() { xdotool mousemove "$(( $1 * 100 / 125 ))" "$(( $2 * 100 / 125 ))"; sleep 0.3; } # screen px (pointer scale 1.25)
press() { hover "$1" "$2"; xdotool mousedown 1; sleep 0.15; xdotool mouseup 1; sleep 1.0; }
find_line() { # <regex> [top|left]: the matching OCR line (the topmost with "top", the leftmost with "left")
  case "${2:-first}" in
    top) lines | grep -E "^[0-9]+ [0-9]+ [0-9]+ [0-9]+ $1" | sort -n -k2 | head -1 ;;
    left) lines | grep -E "^[0-9]+ [0-9]+ [0-9]+ [0-9]+ .*$1" | sort -n -k1 | head -1 ;;
    *) lines | grep -E "^[0-9]+ [0-9]+ [0-9]+ [0-9]+ $1" | head -1 ;;
  esac
}
click_text() { # <regex> [top]
  local l; l=$(find_line "$1" "${2:-first}")
  [[ -n "$l" ]] || { echo "no OCR line for /$1/"; return 1; }
  read -r x y w h t <<<"$l"; press "$x" "$y"; echo "clicked /$1/ at $x,$y"
}
hover_row() { # <label regex> <shot name>: the mouse on the row's label (the leftmost match; the preview repeats it).
  # The regex follows "x y w h " (find_line's .* prefix): anchor a whole label with a leading space, not ^
  local l; l=$(find_line "$1" left)
  [[ -n "$l" ]] || { echo "no OCR line for /$1/"; return 1; }
  read -r x y w h t <<<"$l"; hover "$x" "$y"; sleep 2.5; shot "$2"; echo "hovered /$1/ at $x,$y"
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

# 1. the Enhancements tab: sections and buttons
click_text "OPTIONS *$" || exit 2; sleep 2
click_text "Enhancements" top || { fail "no Enhancements tab"; exit 2; }; sleep 3; shot enh-tab
for s in "Upscaling" "HDR output" "Ambient occlusion"; do
  has enh-tab "$s" && pass "Enhancements tab shows '$s'" || fail "Enhancements tab lacks '$s'"
done
# the headline and the two buttons sit over the menu's bright logo art, where the OCR misses the dim text: report only
for s in "Graphics enhancements" "Reset to defaults" "DLSS files"; do
  has enh-tab "$s" && echo "seen: '$s'" || echo "note: OCR did not read '$s' (check enh-tab.png)"
done
# 2. the previews: the mouse over one row of each section
hover_row " Upscaler *$" prev-upscaler || fail "no Upscaler row"
has prev-upscaler "NO UPSCALER" && pass "upscaler preview captions" || fail "upscaler preview captions missing"
hover_row "HDR output: automatic" prev-hdr || fail "no HDR row"
has prev-hdr "HDR ON" && pass "HDR preview captions" || fail "HDR preview captions missing"
hover_row " Ambient occlusion *$" prev-ao || fail "no AO row"
has prev-ao "OCCLUSION ON" && pass "AO preview captions" || fail "AO preview captions missing"
for s in prev-upscaler prev-hdr prev-ao; do has $s "no clip for this setting|could not be decoded" && fail "$s: clip missing"; done

# 3. the Optimizations tab no longer lists them; its FSR profile sets the Enhancements tab's upscaler
click_text "Optimizations" top || exit 2; sleep 3; shot opt-tab
has opt-tab "HDR output|Ambient occlusion \(|Upscaling \(" && fail "Optimizations tab still lists an enhancement section" || pass "Optimizations tab without the enhancement sections"
click_text "Low-end hardware \+ FSR" || fail "no FSR profile button"
sleep 1
click_text "Enhancements" top || exit 2; sleep 2; shot enh-after-profile
grep -iE "fsr1" "$OUT/enh-after-profile.ocr.txt" | head -3
has enh-after-profile "fsr1" && pass "profile set Upscaler fsr1 on the Enhancements tab" || fail "Upscaler not fsr1 after the profile"

# 4. console: the page built, no build failure
grep -a "options tab" "$ZOMBOID/console.txt" | tail -6 | tee "$OUT/console-tab.txt"
grep -aq "options tab Enhancements: [0-9]* controls" "$ZOMBOID/console.txt" && pass "console: Enhancements page built" || fail "console: no Enhancements build line"
grep -aq "build failed" "$ZOMBOID/console.txt" && fail "console: a tab build failed" || pass "console: no build failure"
cp "$ZOMBOID/console.txt" "$OUT/console.txt"

# leave without saving: BACK (no Apply / Accept); the trap restores the files anyway
click_text "BACK" || xdotool key Escape; sleep 2; shot back
kill "$GAME_PID" 2>/dev/null; sleep 4; GAME_PID_DONE=$GAME_PID; GAME_PID=""
kill -0 "$GAME_PID_DONE" 2>/dev/null && kill -9 "$GAME_PID_DONE"
echo "fails=$FAILS (screens in $OUT)"
exit $FAILS
