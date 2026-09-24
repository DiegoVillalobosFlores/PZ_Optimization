#!/bin/bash
# Check of the controller shortcut of the performance overlay (pzopt.Overlay: L3 + R3 held together on one pad toggles
# it, once per press) and of the shortcut text on the menu item (pzopt_mainscreen_overlay.lua: "... (F9 / L3 + R3)"),
# with a virtual Xbox 360 pad (harness/pad.py). Run it through the queue:
#   harness/queue.sh submit cmd --install keep --label pad-chord -- harness/pad-chord-check.sh
#  Main menu (direct launch, overlaySampling on): the item names both shortcuts; the chord before the pad is activated,
#    the chord again, a 1.5 s chord hold (one toggle, not one per frame), L3 alone and R3 alone (no toggle), F9 (one
#    toggle); every step counts the "overlay: shown|hidden" console lines.
#  World (run.sh bench on the bench save, 1-tile route + hold; the harness implies sampling; the player is on keyboard
#    + mouse, the pad belongs to no player): the chord shows the overlay, the chord again hides it.
# Needs python-evdev and write access to /dev/uinput; the pad GUID goes into options.ini as a controller= line
# (backed up and restored).
set -u
cd "$(dirname "$0")/.."
source scripts/pz-env.sh
OUT=${OUT:-/tmp/pad-chord-check}; rm -rf "$OUT"; mkdir -p "$OUT"
UI=harness/ui-drive.py
FIFO=/tmp/pzopt-pad.fifo
GUID=030000005e0400008e02000010010000
LUA=pzopt_mainscreen_overlay.lua
scripts/pzopt.sh status | tee "$OUT/status.txt" | grep -q "^installed: *yes" || { echo "overrides not installed"; exit 1; }
cmp -s "src/lua/client/pzopt/$LUA" "$PZ_DIR/media/lua/client/pzopt/$LUA" || { echo "installed $LUA differs from src"; exit 1; }

ocr() { python3 $UI read 2>/dev/null; }
shot() { sleep "${2:-1.2}"; spectacle -b -n -f -o "$OUT/$1.png" >/dev/null 2>&1; echo "shot $1"; }
pad() { echo "$1" > "$FIFO"; sleep "${2:-1.5}"; }
toggles() { grep -ac "overlay: shown\|overlay: hidden" "$ZOMBOID/console.txt"; }
key() { xdotool keydown "$1"; sleep 0.12; xdotool keyup "$1"; sleep "${2:-1.5}"; }   # a plain `key` is too short for the per-frame poll
# $1 = what was done, $2 = the toggle count before, $3 = the toggle lines expected from it
expect() {
  local now; now=$(toggles)
  if (( now - $2 == $3 )); then echo "PASS: $1 ($3 toggle line(s), last: $(grep -a "overlay: shown\|overlay: hidden" "$ZOMBOID/console.txt" | tail -1 | grep -oE "overlay: [a-z]+"))"
  else echo "FAIL: $1 (expected $3 toggle line(s), got $((now - $2)))"; FAILS=$((FAILS + 1)); fi
}
step() { local n; n=$(toggles); "${@:3}"; expect "$1" "$n" "$2"; }
FAILS=0
GAME_PID=""; RUN_PID=""
cleanup() {
  [[ -n "$GAME_PID" ]] && kill "$GAME_PID" 2>/dev/null
  echo quit > "$FIFO" 2>/dev/null; sleep 0.5; rm -f "$FIFO"
  cp "$OUT/options.ini.orig" "$ZOMBOID/options.ini"; rm -f "$ZOMBOID/joypads/$GUID.config"
}
console_tail() {
  grep -a "\[pzopt\] overlay\|overlay: \|LuaError\|Callframe\|attempt to\|ERROR: General\|$LUA" "$ZOMBOID/console.txt" | tail -30 > "$OUT/console-$1.txt"
}

rm -f "$FIFO"; mkfifo "$FIFO"
python3 harness/pad.py serve "$FIFO" > "$OUT/pad.log" 2>&1 &
sleep 1.5; grep -q "pad ready" "$OUT/pad.log" || { echo "pad failed: $(cat "$OUT/pad.log")"; exit 1; }
cp "$ZOMBOID/options.ini" "$OUT/options.ini.orig"
[[ -z "$(tail -c1 "$ZOMBOID/options.ini")" ]] || echo >> "$ZOMBOID/options.ini"   # the game leaves no newline after the last option
grep -q "^controller=$GUID" "$ZOMBOID/options.ini" || echo "controller=$GUID" >> "$ZOMBOID/options.ini"

# --- main menu ---
( cd "$PZ_DIR/.." && JAVA_TOOL_OPTIONS="-Dzomboid.steam=0 -Dpzopt.overlaySampling=true -Dpzopt.updateCheck=false" exec setsid ./projectzomboid.sh </dev/null >"$OUT/game.log" 2>&1 ) &
sleep 3; GAME_PID=$(pgrep -f '^([^ ]*/)?ProjectZomboid64( |$)' | head -1)
for i in $(seq 1 45); do sleep 2; ocr | grep -qi "PERFORMANCE OVERLAY" && break; done
sleep 3; shot menu 0
ocr > "$OUT/menu-ocr.txt"
if grep -qE "PERFORMANCE OVERLAY.*F9.*L3 ?\+ ?R3" "$OUT/menu-ocr.txt"; then echo "PASS: the item names both shortcuts: $(grep -oE "(SHOW|HIDE) PERFORMANCE OVERLAY.*" "$OUT/menu-ocr.txt" | head -1)"
else echo "FAIL: item text without the shortcuts: $(grep -oE "(SHOW|HIDE) PERFORMANCE OVERLAY.*" "$OUT/menu-ocr.txt" | head -1)"; FAILS=$((FAILS + 1)); fi
xdotool search --name "Project Zomboid" windowactivate --sync 2>/dev/null; sleep 0.5

step "main menu: L3 + R3 before any other pad press" 1 pad "chord l3 r3"; shot main-chord-1 0.3
step "main menu: L3 + R3 again" 1 pad "chord l3 r3"
step "main menu: L3 + R3 held 1.5 s" 1 pad "chord l3 r3 1.5"; shot main-chord-hold 0.3
step "main menu: L3 + R3 held 1.5 s again" 1 pad "chord l3 r3 1.5"
step "main menu: L3 alone" 0 pad l3
step "main menu: R3 alone" 0 pad r3
step "main menu: F9" 1 key F9; shot main-f9 0.3
step "main menu: F9 again" 1 key F9
ocr > "$OUT/menu-ocr-end.txt"
console_tail main
kill "$GAME_PID" 2>/dev/null
for i in $(seq 1 20); do kill -0 "$GAME_PID" 2>/dev/null || break; sleep 1; done
kill -0 "$GAME_PID" 2>/dev/null && { kill -9 "$GAME_PID"; sleep 2; }
GAME_PID=""

# --- world: bench save, the player holds still on a 1-tile route for 60 s ---
harness/run.sh --label padchord-world --mode bench --launcher direct --flag route=S:1 --flag speed=1 --flag hold=60 --route-seconds 1 --no-dashboard --no-mangohud > "$OUT/run.log" 2>&1 &
RUN_PID=$!
sleep 10   # console.txt is rewritten at launch
for i in $(seq 1 90); do sleep 2; grep -aq "harness: route start" "$ZOMBOID/console.txt" 2>/dev/null && break; done
sleep 8; GAME_PID=$(pgrep -f '^([^ ]*/)?ProjectZomboid64( |$)' | head -1)
shot world-before 0
step "world: L3 + R3" 1 pad "chord l3 r3"; shot world-chord 0.3
step "world: L3 + R3 again" 1 pad "chord l3 r3"; shot world-chord-2 0.3
console_tail world
wait "$RUN_PID"; echo "run.sh exit $?"; GAME_PID=""
cleanup
echo "done: $FAILS failure(s); $(ls "$OUT" | tr '\n' ' ')"
exit $(( FAILS > 0 ? 2 : 0 ))
