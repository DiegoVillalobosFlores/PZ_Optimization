#!/usr/bin/env bash
# Launch one hands-off game run and collect its log.
#
#   harness/run.sh --label <name> [--quit-after <secs>] [--mode <mode>] [--source-save <Mode/Name>]
#                  [--flag k=v]... [--prop k=v]... [--mangohud secs] [--no-mangohud]
#                  [--mangohud-config path]
#                  [--jfr] [--jfr-period ms] [--game-profiler] [--gc g1|zgc] [--no-dashboard]
#                  [--refresh-template] [--retries N] [--renderer nvidia|zink] [--env K=V]... [--mod ID]... [--vmarg ARG]...
#                  [--lead secs] [--route-seconds secs] [--launcher auto|steam|direct] [--option key=value]...
#                  [--preset night-torch|night-dark|storm|fog|storm-fog|louisville|helicopter]
#
# --preset NAME    scene preset: bench mode on the spinning game-thread route (route=S:450 turn=90 zoom=max,
#                  --route-seconds 25) plus the scene flags pzopt.Scene reads (time_of_day, torch, weather).
#                    night-torch  01:00, lit Base.HandTorch in the primary hand (the beam sweeps with the turn)
#                    night-dark   01:00, no light item
#                  (--flag visible=true keeps the player visible; the beam draws with the default invisible player too.
#                  The --shot-at captures do NOT show the beam: the player is held still for 2 s before the capture.)
#                    storm        the save's hour, pinned thunderstorm + a lightning strike every 6 s (thunder_secs)
#                    fog          the save's hour and weather period stopped, fog pinned at 1.0 (fog=heavy; rendered by
#                                 ImprovedFog with options.ini fogQuality 0/1, the legacy fog circle with 2)
#                    storm-fog    storm + fog=heavy with the stock storm fog tint: the heaviest STAGE_STORM the game rolls
#                    louisville   the spinning route through downtown Louisville with the zombie population
#                                 maxed: start=12450,1280 (teleport at world-ready; the leg runs through the
#                                 densest TownZone blocks, x 12000-13000 y 1300-1700), route=S:150 speed=6 (same
#                                 25 s and turn; at 18 tiles/s the walk outran chunk handoff at the ~30 fps this
#                                 scene runs at and the second half of the route was black) and population=max (sandbox
#                                 PopulationMultiplier / Start / Peak = 4, pushed to the native popman before the
#                                 chunks load, so the never-visited Louisville cells spawn at that density);
#                                 settle=20 so the far-teleport reload burst is over before the route. No see_all
#                                 since 2026-09-23 (maintainer): the spectator view made the native lighting spread NaN
#                                 into a re-bake flood in ~1 run in 3; the never-seen blocks stay black as in play.
#                                 --flag see_all=true brings it back. Check start= / population= / zombies_loaded=
#                                 / see_all= in pzopt-bench.out; compare only with other louisville runs of the same kind
#                  Preset flags go first, so any --flag / --mode / --route-seconds given on the command line wins
#                  (e.g. --preset storm --flag fog=0.5 is a storm with half fog).
#
# --launcher direct starts the native game itself (projectzomboid.sh, -Dzomboid.steam=0) instead of
# asking the running Steam client; auto (default) does that whenever Steam is not running or not
# logged in (a logged-out client silently ignores -applaunch). Steam mode needs the launch options set to
#   <repo>/harness/steam-launch.sh %command%
# (see that file): it is how MANGOHUD=1 and the renderer variables reach the game.
#   --renderer zink  Mesa Zink (GL over the NVIDIA Vulkan driver) instead of NVIDIA's GL; MangoHud then
#                    comes in through its Vulkan layer (MANGOHUD=1 only, no libMangoHud_opengl preload:
#                    both hooks at once killed the game at "VSync: OFF" on 2026-09-18, native-zink-1);
#                    recorded in run.opts and in console.txt's "OpenGL version" line
#   --env K=V        any other variable for the game process (repeatable)
#   --vmarg ARG      extra JVM option appended to the launcher JSON vmArgs for this run (repeatable; e.g. -javaagent:...;
#                    JAVA_TOOL_OPTIONS is the wrong place for an agent: the launcher's libjvm-locating helper JVM picks it up too)
#   --mod ID         extra mod (from ~/Zomboid/mods) enabled in mods/default.txt and the bench save for this run (repeatable;
#                    default.txt is restored on exit, the bench save is rebuilt every run)
#
# Profiling / A-B options (each restores what it touched on exit):
#   --jfr            record a JFR flight recording of the run (settings=profile, dumped on exit)
#                    by adding -XX:StartFlightRecording to the launcher JSON's vmArgs; collected as pzopt.jfr
#   --jfr-period N   execution-sample period in ms for --jfr (default: the profile setting's 10 ms)
#   --jfr-setting event#setting=value   extra JFR event setting for --jfr (repeatable), e.g.
#                    jdk.JavaMonitorWait#threshold=0ms jdk.ThreadPark#threshold=0ms jdk.NativeMethodSample#period=1ms
#                    (harness/waits.py reads the wait events: where each thread blocks and for how long)
#   --gc g1          launch with -XX:+UseG1GC instead of the JSON's -XX:+UseZGC
#   --no-dashboard   run without the PZDashboard mod (removed from the bench save's mods.txt and default.txt)
#
# What it does:
#   1. installs the pzopt-harness Lua mod into ~/Zomboid/mods (flag file goes to ~/Zomboid/Lua/) and enables it
#      in mods/default.txt (so it runs on the main menu),
#   2. creates the dedicated bench save Saves/Sandbox/pzopt-bench by copying
#      --source-save (default: whatever latestSave.ini points at) the first
#      time, and enables the harness mod in that save's mods.txt,
#   3. points latestSave.ini at the bench save and writes the flag file the
#      Lua mod reads, so the game auto-continues into the bench save,
#   4. launches the game through Steam, waits for it to exit,
#   5. copies console.txt (and any pzopt output files) into
#      harness/runs/<label>-<timestamp>/ and restores latestSave.ini.
#
# The player's real saves are never loaded or written by a harness run.
set -euo pipefail

APPID=108600
REPO="$(cd "$(dirname "$0")/.." && pwd)"
source "$REPO/scripts/pz-env.sh"   # PZ_DIR, ZOMBOID, PZ_LAYOUT, PZ_DIR_JVM
LAYOUT="$PZ_LAYOUT"
if [[ "$LAYOUT" == native ]]; then GAME_WRAPPER="${GAME_WRAPPER:-$PZ_DIR/../projectzomboid.sh}"; else GAME_WRAPPER="${GAME_WRAPPER:-$PZ_DIR/ProjectZomboid64.exe}"; fi
MOD_SRC="$REPO/harness/mod/pzopt-harness"
MOD_ID=pzopt-harness
BENCH_SAVE="Sandbox/pzopt-bench"
RUNS="$REPO/harness/runs"
FLAG_FILE="$ZOMBOID/Lua/pzopt-harness.txt"
NATIVE_FLAG_FILE="${NATIVE_ZOMBOID:-$HOME/Zomboid}/Lua/pzopt-harness.txt"

shot_at=""; label=""; quit_after=""; mode="verify"; source_save=""; extra_flags=(); props=(); mangohud_secs=""; mangohud_config=""
resume_shot=""; keep_save=0; resume_from=""; record=0; jfr=0; jfr_period=""; jfr_settings=(); game_profiler=0; gc=""; no_dashboard=0; refresh_template=0; retries=2; renderer="nvidia"; game_env=(); lead=""; route_seconds=""; launcher="auto"; game_options=(); extra_mods=(); vmargs=()
schedmon=""; asprof=""
preset=""; mode_set=0; pad_script=""; inputlag=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --label) label="$2"; shift 2 ;;
    --quit-after) quit_after="$2"; shift 2 ;;
    --mode) mode="$2"; mode_set=1; shift 2 ;;
    --preset) preset="$2"; shift 2 ;;              # scene preset (see the header); expanded after parsing
    --source-save) source_save="$2"; shift 2 ;;
    --flag) extra_flags+=("$2"); shift 2 ;;   # extra key=value for pzopt-harness.txt
    --prop) props+=("$2"); shift 2 ;;         # key=value for the game dir's pzopt.properties (see pzopt.Config)
    --mangohud) mangohud_secs="$2"; shift 2 ;; # external frame-time log via MangoHud for N seconds from launch
    --mangohud-config) mangohud_config="$2"; shift 2 ;; # config file used for this MangoHud run
    --no-mangohud) no_mangohud=1; shift ;;     # no MangoHud preload or CSV; frame times come from pzopt-overlay.out only
    --jfr) jfr=1; shift ;;
    --jfr-period) jfr_period="$2"; shift 2 ;;
    --jfr-setting) jfr_settings+=("$2"); shift 2 ;;
    --game-profiler) game_profiler=1; shift ;;
    --gc) gc="$2"; shift 2 ;;
    --no-dashboard) no_dashboard=1; shift ;;
    --refresh-template) refresh_template=1; shift ;; # rebuild the --source-save template from the source save
    --retries) retries="$2"; shift 2 ;;              # relaunches after a start-up crash (default 2)
    --renderer) renderer="$2"; shift 2 ;;
    --lead) lead="$2"; shift 2 ;;                    # fixed schedule: seconds from launch to the route start (world load + settle must
                                                     # fit). Default: none; the route starts settle s after the world is up (see below)
    --route-seconds) route_seconds="$2"; shift 2 ;;  # expected route length (bench: tiles/speed = 100; drive: max_seconds)
    --shot-at) shot_at="$2"; extra_flags+=("shot_at=$2"); shift 2 ;;  # bench: hold the camera N s into the route and capture <run>/shot-game.png (in-game) + shot-desktop.png (spectacle)
    --env) game_env+=("$2"); shift 2 ;;
    --mod) extra_mods+=("$2"); shift 2 ;;
    --vmarg) vmargs+=("$2"); shift 2 ;;
    --schedmon) schedmon="$2"; shift 2 ;;                # per-thread run / run-queue wait / page faults + PSI every N s -> <run>/schedmon.txt (harness/schedmon.py)
    --asprof) asprof="$2"; shift 2 ;;                    # async-profiler agent (harness/asprof/libasyncProfiler.so) with these options, e.g. event=cpu,interval=5ms,threads -> <run>/asprof.jfr
    --pad) pad_script="$2"; shift 2 ;;               # drive the menus with a virtual pad (see below)
    --inputlag) inputlag=1; pad_script="$2"; shift 2 ;; # in-game input-lag profile: uinput keyboard + mouse + pad (see below)
    --record) record=1; shift ;;
    --record-audio) record_audio="$2"; shift 2 ;;   # desktop (default: every sound the desktop plays) | game (only the game's FMOD stream, opus 384 kbps; harness/audio.py)
    --resume-shot) resume_shot="$2"; shift 2 ;;
    --keep-save) keep_save=1; shift ;;       # hard-link the bench save as the run's exit save left it into <run>/save (for --resume-from)
    --resume-from) resume_from="$2"; shift 2 ;;  # a --keep-save run dir: its exit save (player position + resume shot) is this run's bench save  # dir with pzopt-resume.jpg/.properties[/-load.txt] (a run dir): copied into the bench save, so Continue shows that shot

    --launcher) launcher="$2"; shift 2 ;;                # auto|steam|direct (see the header)
    --wrap) wrap_cmd="$2"; shift 2 ;;                    # direct launcher only: command prefix for the game, e.g. "gamescope --hdr-enabled -f --" (HDR pass)
    --option) game_options+=("$2"); shift 2 ;;           # key=value written into ~/Zomboid/options.ini for the run (restored on exit)                     # screen recording of the run (gpu-screen-recorder, first monitor, native res, AV1 HDR) -> <run>/recording.mp4
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
if [[ -n "$preset" ]]; then
  # scene flags are read by pzopt.Scene (src/pzopt/pzopt/Scene.java); the route is the 2026-09-20 game-thread one
  case "$preset" in
    night-torch) preset_flags=(time_of_day=1 torch=on) ;;
    night-dark)  preset_flags=(time_of_day=1 torch=off) ;;
    storm)       preset_flags=(weather=storm) ;;
    fog)         preset_flags=(fog=heavy) ;;
    storm-fog)   preset_flags=(weather=storm fog=heavy) ;;
    louisville)  preset_flags=(start=12450,1280 population=max settle=20 route=S:150 speed=6) ;;
    helicopter)  preset_flags=(helicopter=true) ;;   # the stock chopper event hovering over the route, its 500-radius world sound every ~10 s (2026-09-22)
    *) echo "unknown preset: $preset (night-torch|night-dark|storm|fog|storm-fog|louisville|helicopter)" >&2; exit 2 ;;
  esac
  extra_flags=(route=S:450 turn=90 zoom=max "${preset_flags[@]}" "${extra_flags[@]}")   # later duplicates win (Properties.load)
  [[ $mode_set -eq 1 ]] || mode=bench
  [[ -n "$route_seconds" ]] || route_seconds=25
fi
if [[ -z "$route_seconds" ]]; then
  if [[ "$mode" == drive ]]; then route_seconds=90; else route_seconds=100; fi
fi
# --pad <script>: menu profiling with a virtual Xbox 360 pad (harness/pad.py, uinput; needs python-evdev and write
# access to /dev/uinput). The pad exists before the launch and is the run's only active controller (options.ini
# controller=<its GUID>, restored on exit); mode menu: the harness Lua never continues a save, it writes
# Lua/pzopt-pad-ready.txt once the main menu accepts input, logs every pad handler it sees ("[pzopt-pad]" console
# lines: epoch ms, handler ms, the focused element) and every slow menu frame, and quits when the script is done.
# The script's lines go to pad.py 2 s after the menu is ready (commands: a b x y start back lb rb up down left right,
# hold <button> <s>, sleep <s>, mark <name>; see harness/pad/*.txt); <run>/pad.log has every press with its epoch
# ms. The frame log comes from the overlay (pzopt-overlay.out, add --prop overlaySampling=true --prop overlayLog=true);
# harness/padlat.py <run> lines them up.
# --inputlag <script> (2026-09-24): the same plumbing in the world: harness/inputlag-drive.py makes a uinput keyboard +
# mouse and the Xbox 360 pad before the launch, mode play on the bench save (god mode, invisible, no route), the harness
# Lua writes Lua/pzopt-inputlag-ready.txt 6 s after the player exists, the script (harness/inputlag/*.txt: key, mouse,
# mmove, stick, pad, flag, repeat) then runs; its "flag inputlag_pad=1" makes the Lua give the pad to player 1, and
# "done" quits. Keyboard / mouse events go to the focused window: the driver skips them unless the focused X window is
# the game. pzopt.InputLag (flag inputlag=1) stamps every stage into pzopt-inputlag.out; harness/inputlag.py <run>.
PAD_GUID=030000005e0400008e02000010010000
pad_driver=(python3 "$REPO/harness/pad.py" serve); pad_log=pad.log; pad_ready=pzopt-pad-ready.txt; pad_done_flag=pad_done=1
if (( inputlag )); then
  pad_driver=(python3 "$REPO/harness/inputlag-drive.py" serve); pad_log=inputlag-drive.log
  pad_ready=pzopt-inputlag-ready.txt; pad_done_flag=inputlag_done=1
fi
if [[ -n "$pad_script" ]]; then
  [[ -f "$pad_script" ]] || pad_script="$REPO/$pad_script"
  [[ -f "$pad_script" ]] || { echo "pad script not found: $pad_script" >&2; exit 2; }
  pad_script="$(cd "$(dirname "$pad_script")" && pwd)/$(basename "$pad_script")"
  python3 -c 'import evdev' 2>/dev/null || { echo "--pad needs python-evdev" >&2; exit 1; }
  [[ -w /dev/uinput ]] || { echo "--pad needs write access to /dev/uinput" >&2; exit 1; }
  if (( inputlag )); then
    [[ $mode_set -eq 1 ]] || mode=play
    [[ -n "$quit_after" ]] || quit_after=400
    extra_flags+=("inputlag=1")
  else
    [[ $mode_set -eq 1 ]] || mode=menu
    extra_flags+=("pad=1")
  fi
  game_options+=("controller=$PAD_GUID")
  no_mangohud=1
fi
[[ -n "$label" ]] || { echo "usage: $0 --label <name> [--quit-after secs] [--mode m] [--source-save Mode/Name] [--flag k=v]..." >&2; exit 2; }
[[ -d "$ZOMBOID" ]] || { echo "Zomboid user dir not found: $ZOMBOID" >&2; exit 1; }
if [[ ! -e "$GAME_WRAPPER" ]]; then
  echo "game launcher not found: $GAME_WRAPPER" >&2
  # pz-env.sh falls back to the Proton layout whenever the native binary is missing, so the Windows depot itself may be
  # absent: Steam holds one platform's depot at a time and swaps it when the compat tool changes (2026-09-19: only the
  # Linux depot 108603 installed, no ProjectZomboid64.exe / projectzomboid.jar at the root)
  [[ "$LAYOUT" == native ]] || echo "Windows depot not installed: in Steam, Properties > Compatibility, force a Proton tool and let it re-download; harness/proton-preflight.sh checks the rest (docs/proton-run-prep-2026-09-19.md)" >&2
  exit 1
fi
echo "layout=$LAYOUT install=$PZ_DIR user-dir=$ZOMBOID"
# The game is the process whose argv[0] is ProjectZomboid64 (./ProjectZomboid64 on the native depot, the .exe under
# Proton). A bare pgrep -f '[P]rojectZomboid64' also matched any shell whose command line mentions the name, e.g. a
# peer's `queue.sh wait ...; grep ... ProjectZomboid64.json`: job 1484 (2026-09-23) waited 8.5 min after the game quit.
GAME_PATTERN='^([^ ]*[/\\])?ProjectZomboid64(\.exe)?( |$)'
if pgrep -f "$GAME_PATTERN" >/dev/null; then echo "the game is already running" >&2; exit 1; fi
# A run that dies hard (SIGKILL, power cut, the whole-system freezes of 2026-09-22/24) never reaches the EXIT
# trap, and the per-run pzopt.properties it wrote stays in the install dir: every later boot pins those keys in
# the Optimizations tab, and a stock run's `enabled=false` leaves the mod OFF for the player (found 2026-09-24).
# Recover here, before this run takes its own backup: a .pzopt-orig is the player's file, so it goes back;
# a pzopt.properties identical to the last run's copy is ours, so it goes away; anything else is the player's.
recover_props() {
  if [[ -f "$PZ_DIR/pzopt.properties.pzopt-orig" ]]; then
    mv -f "$PZ_DIR/pzopt.properties.pzopt-orig" "$PZ_DIR/pzopt.properties"
    echo "previous run died before cleanup: restored the player's pzopt.properties"
  elif [[ -f "$PZ_DIR/pzopt.properties" && -f "$RUNS/.last-props" ]] && cmp -s "$PZ_DIR/pzopt.properties" "$RUNS/.last-props"; then
    rm -f "$PZ_DIR/pzopt.properties"
    echo "previous run died before cleanup: removed its pzopt.properties"
  fi
  return 0
}
recover_props
steam_logged_in() {
  pgrep -x steam >/dev/null || return 1
  local log="$HOME/.local/share/Steam/logs/connection_log.txt"
  [[ -f "$log" ]] || return 1
  grep -a -o '\[Logged O[nf]*' "$log" | tail -1 | grep -q 'Logged On'
}
case "$launcher" in
  auto) if steam_logged_in; then launcher=steam; else launcher=direct; echo "Steam is not running or not logged in: launching the game directly (--launcher direct)"; fi ;;
  steam|direct) ;;
  *) echo "unknown --launcher $launcher" >&2; exit 2 ;;
esac
if [[ "$LAYOUT" != native ]]; then
  # the Windows build (Proton) can only be started through the Steam client
  launcher=steam
  steam_logged_in || { echo "Proton layout needs the Steam client running and logged in (it launches the game)" >&2; exit 1; }
fi
# a locked desktop session never gives the game a display (it sits at "Creating display" forever)
if command -v loginctl >/dev/null; then
  sess=$(loginctl list-sessions --no-legend 2>/dev/null | awk '$3=="'"$USER"'" && $4=="seat0" {print $1; exit}')
  if [[ -n "$sess" ]] && loginctl show-session "$sess" -p LockedHint 2>/dev/null | grep -q '=yes'; then
    echo "the desktop session is locked; unlock it before a run (the game cannot create its window)" >&2; exit 1
  fi
fi

# 1. harness mod
rm -rf "$ZOMBOID/mods/$MOD_ID"
cp -r "$MOD_SRC" "$ZOMBOID/mods/$MOD_ID"
enable_mod() { # $1 = mods.txt path, $2 = mod id (default: the harness mod); inserted at the top of the list
  local id="${2:-$MOD_ID}"
  # files written by the Windows build under Proton are CRLF; the native build writes LF
  grep -q "mod = $id," "$1" || sed -i "0,/^mods\r$/{n;s/^{\r$/{\r\n    mod = $id,\r/}" "$1"
  grep -q "mod = $id," "$1" || sed -i "0,/^mods$/{n;s/^{$/{\n    mod = $id,/}" "$1"
  grep -q "mod = $id," "$1" || { echo "could not enable $id in $1" >&2; exit 1; }
}
enable_extra_mods() { # $1 = mods.txt path; --mod IDs go in above the harness mod, in the order given
  local i
  for (( i=${#extra_mods[@]}-1; i>=0; i-- )); do
    [[ -d "$ZOMBOID/mods/${extra_mods[i]}" ]] || { echo "--mod ${extra_mods[i]}: not found in $ZOMBOID/mods" >&2; exit 1; }
    enable_mod "$1" "${extra_mods[i]}"
  done
}
[[ -f "$ZOMBOID/mods/default.txt.pzopt-orig" ]] || cp "$ZOMBOID/mods/default.txt" "$ZOMBOID/mods/default.txt.pzopt-orig"
enable_mod "$ZOMBOID/mods/default.txt"
DASH_ID=PZDashboard
disable_dashboard() { sed -i "/^    mod = $DASH_ID,\r\{0,1\}\$/d" "$1"; grep -q "mod = $DASH_ID," "$1" && { echo "could not remove $DASH_ID from $1" >&2; exit 1; }; return 0; }
if (( no_dashboard )); then
  cp "$ZOMBOID/mods/default.txt" "$ZOMBOID/mods/default.txt.pzopt-dash"
  disable_dashboard "$ZOMBOID/mods/default.txt"
fi
if (( ${#extra_mods[@]} )); then
  [[ -f "$ZOMBOID/mods/default.txt.pzopt-dash" ]] || cp "$ZOMBOID/mods/default.txt" "$ZOMBOID/mods/default.txt.pzopt-dash"
  enable_extra_mods "$ZOMBOID/mods/default.txt"
fi

# 2. bench save: a pristine template is made once from the source save, and
#    the actual bench save is recreated from it before every run, so each run
#    loads byte-identical chunk data regardless of what earlier runs wrote.
if [[ -n "$source_save" ]]; then
  # An explicit source gets its own template, keyed by the source name, and a
  # bench save in the same game-mode directory (latestSave.ini carries the mode).
  # Templates are only rebuilt on request, so a driving fixture and the teleport
  # route can coexist without silently replacing each other.
  [[ -d "$ZOMBOID/Saves/$source_save" ]] || { echo "source save not found: $ZOMBOID/Saves/$source_save" >&2; exit 1; }
  BENCH_SAVE="$(dirname "$source_save")/pzopt-bench"
  TEMPLATE="$ZOMBOID/Saves/$(dirname "$source_save")/pzopt-template-$(basename "$source_save")"
  if (( refresh_template )); then rm -rf "$TEMPLATE"; fi
  if [[ ! -d "$TEMPLATE" ]]; then
    echo "creating bench save template $(basename "$TEMPLATE") from $source_save ($(du -sh "$ZOMBOID/Saves/$source_save" | cut -f1))"
    cp -r "$ZOMBOID/Saves/$source_save" "$TEMPLATE"
    enable_mod "$TEMPLATE/mods.txt"
  fi
else
  TEMPLATE="$ZOMBOID/Saves/${BENCH_SAVE}-template"
  if [[ ! -d "$TEMPLATE" ]]; then
    # latestSave.ini: line 1 = save name, line 2 = game mode
    source_save="$(sed -n 2p "$ZOMBOID/latestSave.ini" | tr -d '\r')/$(sed -n 1p "$ZOMBOID/latestSave.ini" | tr -d '\r')"
    [[ -d "$ZOMBOID/Saves/$source_save" ]] || { echo "source save not found: $ZOMBOID/Saves/$source_save" >&2; exit 1; }
    echo "creating bench save template from $source_save ($(du -sh "$ZOMBOID/Saves/$source_save" | cut -f1))"
    cp -r "$ZOMBOID/Saves/$source_save" "$TEMPLATE"
    enable_mod "$TEMPLATE/mods.txt"
  fi
fi
if [[ -n "$resume_from" ]]; then
  [[ -d "$resume_from/save" ]] || { echo "--resume-from: no save/ in $resume_from (run it with --keep-save)" >&2; exit 2; }
  TEMPLATE="$(cd "$resume_from/save" && pwd)"
fi
echo "bench save: $BENCH_SAVE (template ${resume_from:+the exit save of }$(basename "${resume_from:-$TEMPLATE}"))"
rm -rf "$ZOMBOID/Saves/$BENCH_SAVE"
cp -r "$TEMPLATE" "$ZOMBOID/Saves/$BENCH_SAVE"
if [[ -n "$resume_shot" ]]; then
  [[ -f "$resume_shot/pzopt-resume.jpg" && -f "$resume_shot/pzopt-resume.properties" ]] || { echo "--resume-shot: no pzopt-resume.jpg/.properties in $resume_shot" >&2; exit 2; }
  cp "$resume_shot"/pzopt-resume.jpg "$resume_shot"/pzopt-resume.properties "$ZOMBOID/Saves/$BENCH_SAVE/"
  if [[ -f "$resume_shot/pzopt-resume-load.txt" ]]; then cp "$resume_shot/pzopt-resume-load.txt" "$ZOMBOID/Saves/$BENCH_SAVE/"; fi
  echo "bench save: resume shot from $resume_shot"
fi
(( no_dashboard )) && disable_dashboard "$ZOMBOID/Saves/$BENCH_SAVE/mods.txt"  # the save is rebuilt from the template every run; nothing to restore
(( ${#extra_mods[@]} )) && enable_extra_mods "$ZOMBOID/Saves/$BENCH_SAVE/mods.txt"

# 3. point the game at the bench save and write the flag file
# read by harness/steam-launch.sh (the Steam launch option) at this fixed path, whatever the layout: under Proton
# $ZOMBOID is the compatdata prefix, which the wrapper does not look at
LAUNCH_ENV="$HOME/Zomboid/pzopt-launch.env"; mkdir -p "$HOME/Zomboid"
# native Wayland window (--env JAVA_TOOL_OPTIONS=-Dzomboid.wayland=1): xdotool keypresses cannot reach it
native_wayland=0; for e in "${game_env[@]}"; do [[ "$e" == *zomboid.wayland=1* ]] && native_wayland=1; done
write_launch_env() {
  {
    [[ -n "$mangohud_secs" ]] && echo "PZOPT_MANGOHUD=1"
    case "$renderer" in
      zink) echo "MESA_LOADER_DRIVER_OVERRIDE=zink"; echo "__GLX_VENDOR_LIBRARY_NAME=mesa"; echo "__EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/50_mesa.json"
            echo "PZOPT_MANGOHUD_LIB=none" ;;   # Zink presents through Vulkan: MangoHud's Vulkan layer hooks it, the GL preload must stay out
      nvidia) ;;
      *) echo "unknown --renderer $renderer" >&2; exit 2 ;;
    esac
    for e in "${game_env[@]}"; do echo "$e"; done
  } > "$LAUNCH_ENV"
}
restore_harness_flag() {
  rm -f "$FLAG_FILE" "$FLAG_FILE.pzopt-orig" "$NATIVE_FLAG_FILE" "$NATIVE_FLAG_FILE.pzopt-orig" "$LAUNCH_ENV"
}
# The flag file is runner-owned state. Remove leftovers from an interrupted
# run before writing a new request; never allow an old benchmark to restart.
restore_harness_flag
cp "$ZOMBOID/latestSave.ini" "$ZOMBOID/latestSave.ini.pzopt-orig"
restore_mangohud() {
  if [[ -f "$HOME/.config/MangoHud/MangoHud.conf.pzopt-orig" ]]; then mv -f "$HOME/.config/MangoHud/MangoHud.conf.pzopt-orig" "$HOME/.config/MangoHud/MangoHud.conf"; fi
  return 0
}
restore_launcher() { [[ -f "$PZ_DIR/ProjectZomboid64.json.pzopt-orig" ]] && mv -f "$PZ_DIR/ProjectZomboid64.json.pzopt-orig" "$PZ_DIR/ProjectZomboid64.json"; return 0; }
restore_game_profiler() { [[ -f "$ZOMBOID/debug-options.ini.pzopt-orig" ]] && mv -f "$ZOMBOID/debug-options.ini.pzopt-orig" "$ZOMBOID/debug-options.ini"; return 0; }
restore_game_options() {
  [[ -f "$ZOMBOID/options.ini.pzopt-orig" ]] && mv -f "$ZOMBOID/options.ini.pzopt-orig" "$ZOMBOID/options.ini"
  [[ -f "$ZOMBOID/pzopt/framecap.ini.pzopt-orig" ]] && mv -f "$ZOMBOID/pzopt/framecap.ini.pzopt-orig" "$ZOMBOID/pzopt/framecap.ini"
  return 0
}
restore_pad() {
  [[ -n "${present_pid:-}" ]] && kill "$present_pid" 2>/dev/null
  [[ -n "${pad_feed_pid:-}" ]] && kill "$pad_feed_pid" 2>/dev/null
  [[ -n "${pad_pid:-}" ]] && kill "$pad_pid" 2>/dev/null
  [[ -n "${PAD_FIFO:-}" ]] && rm -f "$PAD_FIFO"
  [[ -n "$pad_script" && "${pad_joypad_cfg_existed:-1}" == 0 ]] && rm -f "$ZOMBOID/joypads/$PAD_GUID.config"
  rm -f "$ZOMBOID/Lua/pzopt-pad-ready.txt" "$ZOMBOID/Lua/pzopt-inputlag-ready.txt"
  return 0
}
restore() {
  # runs under set -e from the EXIT trap: every step must succeed or be guarded, or the rest is skipped.
  # Defined (with every helper it calls) before the trap is armed, so an early exit restores everything.
  set +e
  [[ -n "${sysmon_pid:-}" ]] && kill "$sysmon_pid" 2>/dev/null
  [[ -n "${present_pid:-}" ]] && kill "$present_pid" 2>/dev/null
  [[ -n "${vrrprobe_pid:-}" ]] && kill "$vrrprobe_pid" 2>/dev/null
  [[ -n "${rec_pid:-}" ]] && kill -INT "$rec_pid" 2>/dev/null
  restore_harness_flag
  restore_mangohud
  restore_launcher
  [[ -f "$ZOMBOID/mods/default.txt.pzopt-dash" ]] && mv -f "$ZOMBOID/mods/default.txt.pzopt-dash" "$ZOMBOID/mods/default.txt"
  mv -f "$ZOMBOID/latestSave.ini.pzopt-orig" "$ZOMBOID/latestSave.ini" 2>/dev/null || true
  if [[ -f "$PZ_DIR/pzopt.properties.pzopt-orig" ]]; then mv -f "$PZ_DIR/pzopt.properties.pzopt-orig" "$PZ_DIR/pzopt.properties"; else rm -f "$PZ_DIR/pzopt.properties"; fi
  restore_game_profiler
  restore_game_options
  restore_pad
}
# a backup left by a run that never got to restore (machine dropped mid-run): the backup is the player's file and this
# run would overwrite it with the modified one; refuse until someone has looked
if [[ -f "$ZOMBOID/options.ini.pzopt-orig" ]]; then
  echo "stale $ZOMBOID/options.ini.pzopt-orig from an unrestored run: restore it by hand first" >&2; exit 1
fi
trap 'restore' EXIT
printf '%s\r\n%s\r\n' "$(basename "$BENCH_SAVE")" "$(dirname "$BENCH_SAVE")" > "$ZOMBOID/latestSave.ini"
write_flags() {
  {
    echo "mode=$mode"
    echo "dashboard=$([[ $no_dashboard -eq 1 ]] && echo disabled || echo enabled)"
    [[ -n "$quit_after" ]] && echo "quit_after=$quit_after"
    [[ -n "${route_start_epoch:-}" ]] && echo "route_start_epoch=$route_start_epoch"
    [[ -n "${mangohud_end_epoch:-}" ]] && echo "mangohud_end_epoch=$mangohud_end_epoch"
    [[ -n "$mangohud_secs" ]] && echo "mangohud_secs=$mangohud_secs"
    printf '%s\n' "${extra_flags[@]}" | grep -q '^settle=' || echo "settle=5"
    for f in "${extra_flags[@]}"; do echo "$f"; done
  } > "$FLAG_FILE"   # Lua getFileReader resolves under Zomboid/Lua/
}
write_flags

# runtime settings for this run; the previous pzopt.properties comes back afterwards (or at the next
# run's start via recover_props, when this one dies without reaching the EXIT trap)
# hdrAuto (default on in the game) would switch every run on an HDR desktop (this one) to HDR + native Wayland: runs
# stay SDR unless they ask (--prop hdr=true, or --prop hdrAuto=true)
printf '%s\n' "${props[@]}" | grep -q '^hdrAuto=' || props+=("hdrAuto=false")
if [[ -f "$PZ_DIR/pzopt.properties" ]]; then cp "$PZ_DIR/pzopt.properties" "$PZ_DIR/pzopt.properties.pzopt-orig"; fi
printf '%s\n' "${props[@]}" > "$PZ_DIR/pzopt.properties"
cp "$PZ_DIR/pzopt.properties" "$RUNS/.last-props" 2>/dev/null || true

# --option key=value: game options (~/Zomboid/options.ini, the game's Display/UI settings) for this run only;
# the file is restored byte-for-byte on exit (the game rewrites it when it quits, so the backup wins).
if (( ${#game_options[@]} )); then
  [[ -f "$ZOMBOID/options.ini" ]] || { echo "options.ini not found in $ZOMBOID" >&2; exit 1; }
  cp "$ZOMBOID/options.ini" "$ZOMBOID/options.ini.pzopt-orig"
  # pzopt.FrameCap: a forced uncappedFps run leaves restore=<player's cap> in framecap.ini for the next boot, which would
  # override this run's --option frameRate; the file is restored on exit, so neither run leaks into the other
  if [[ -f "$ZOMBOID/pzopt/framecap.ini" ]]; then
    cp "$ZOMBOID/pzopt/framecap.ini" "$ZOMBOID/pzopt/framecap.ini.pzopt-orig"
    sed -i '/^restore=/d' "$ZOMBOID/pzopt/framecap.ini"
  fi
  # the game writes no newline after the last option: an append would glue the key onto that line
  [[ -z "$(tail -c1 "$ZOMBOID/options.ini")" ]] || echo >> "$ZOMBOID/options.ini"
  for kv in "${game_options[@]}"; do
    k="${kv%%=*}"; v="${kv#*=}"
    if grep -q "^$k=" "$ZOMBOID/options.ini"; then sed -i "s|^$k=.*|$k=$v|" "$ZOMBOID/options.ini"; else printf '%s=%s\n' "$k" "$v" >> "$ZOMBOID/options.ini"; fi
  done
  echo "game options for this run: ${game_options[*]}"
fi

# GameProfiler writes its frame recording only when the debug option is enabled.
# Keep the user's file byte-for-byte restorable, just like the other run knobs.
DEBUG_OPTIONS="$ZOMBOID/debug-options.ini"
if (( game_profiler )); then
  [[ -f "$DEBUG_OPTIONS" ]] || printf 'Version=1\n' > "$DEBUG_OPTIONS"
  cp "$DEBUG_OPTIONS" "$DEBUG_OPTIONS.pzopt-orig"
  grep -q '^GameProfiler.Enabled=true$' "$DEBUG_OPTIONS" || printf '\nGameProfiler.Enabled=true\n' >> "$DEBUG_OPTIONS"
fi

# external frame-time + utilization log. MangoHud is injected by the Steam
# launch options (MANGOHUD=1); env vars from here never reach a game started
# through the running Steam client, and the only config file MangoHud reliably
# reads for the native build is the user's ~/.config/MangoHud/MangoHud.conf.
# So for the run that file is replaced by the selected profile plus the
# autostart/duration keys, and put back byte-for-byte on exit.
# MangoHud writes the CSV only when log_duration elapses while the game is
# still running, so the log and the route are timed together. Default: as soon
# as the world is up pzopt.Harness fixes the route start at world-ready + settle
# (5 s unless --flag settle=N) and publishes it in Zomboid/pzopt-schedule.out;
# the scheduler below reads that, starts the log 3 s before the route through
# MangoHud's control socket (mangohudctl, socket "mangoapp"; toggle_logging key
# via xdotool as the fallback) and the log runs --route-seconds + 8. The game
# quits at the route end or when the log has closed, whichever is later.
# --lead N instead puts everything on a fixed clock from launch (autostart_log,
# route at launch + N); a world that comes up too late for it is logged as
# "route start LATE" and the console's "world ready" line prints the margin.
if (( ${no_mangohud:-0} )); then mangohud_secs=""; fi
if [[ -z "$mangohud_secs" && "$mode" != verify && ! ${no_mangohud:-0} -eq 1 ]]; then mangohud_secs=$((route_seconds + 8)); fi
[[ -n "$mangohud_config" ]] || mangohud_config="config/mangohud-benchmark.conf"
MH_PROFILE="$mangohud_config"
[[ "$MH_PROFILE" = /* ]] || MH_PROFILE="$REPO/$MH_PROFILE"
MH_USER_CONF="$HOME/.config/MangoHud/MangoHud.conf"
MH_OUT="$HOME/Documents/mangohud/benchmarks"
if [[ -n "$mangohud_secs" ]]; then
  [[ -f "$MH_PROFILE" ]] || { echo "MangoHud config not found: $MH_PROFILE" >&2; exit 1; }
  mkdir -p "$(dirname "$MH_USER_CONF")" "$MH_OUT"
  [[ -f "$MH_USER_CONF" ]] && cp "$MH_USER_CONF" "$MH_USER_CONF.pzopt-orig"
  { grep -v '^log_duration=\|^autostart_log=\|^output_folder=\|^log_interval=\|^control=' "$MH_PROFILE"
    printf 'output_folder=%s/\nlog_duration=%s\nlog_interval=0\ncontrol=mangoapp\n' "$MH_OUT" "$mangohud_secs"
    [[ -n "$lead" ]] && printf 'autostart_log=%s\n' "$((lead - 3))"; } > "$MH_USER_CONF"
fi

# launcher JSON (vmArgs) edits for --jfr / --gc: the original comes back on exit
# (if a run dies mid-way, ProjectZomboid64.json.pzopt-orig is the copy to restore by hand).
# Paths inside the JVM are Wine paths: the install dir is S:/common/ProjectZomboid (see the gc.log line).
LAUNCHER="$PZ_DIR/ProjectZomboid64.json"
JFR_OUT="$PZ_DIR/pzopt.jfr"
ASPROF_OUT="$PZ_DIR/pzopt-asprof.jfr"
rm -f "$ASPROF_OUT"
[[ -n "$asprof" ]] && vmargs+=("-agentpath:$REPO/harness/asprof/libasyncProfiler.so=start,$asprof,jfr,file=$ASPROF_OUT")
# pzopt.GcChoice writes the player's launcher JSON (jitSteady: C2 trap limits) but leaves harness runs alone, so an
# optimized run gets the same flags here; --prop jitSteady=false or enabled=false (a stock run) goes without
jit_steady=1; [[ -f "$PZ_DIR/pzopt-installed.txt" ]] || jit_steady=0   # no pzopt installed: a stock game, stock flags
for p in "${props[@]}"; do [[ "$p" == "jitSteady=false" || "$p" == "enabled=false" ]] && jit_steady=0; done
(( jit_steady )) && vmargs+=("-Dpzopt.jit=steady" "-XX:PerMethodTrapLimit=0" "-XX:PerBytecodeTrapLimit=0")
# The launcher is always edited for a run: a gc log (-Xlog:gc) is added when the
# JSON has none, so harness/analyze.py can count collector events in the route window.
{
  cp "$LAUNCHER" "$LAUNCHER.pzopt-orig"
  rm -f "$JFR_OUT"
  PZOPT_VMARGS="$(printf '%s\n' "${vmargs[@]}")" python3 - "$LAUNCHER" "$jfr" "$jfr_period" "$gc" "$PZ_DIR_JVM/pzopt.jfr" "$PZ_DIR_JVM/gc.log" "$launcher" "$(IFS=,; echo "${jfr_settings[*]:-}")" <<'PY'
import json, sys, os
path, jfr, period, gc, jfr_file, gc_log, launcher, jfr_settings = sys.argv[1:]
j = json.load(open(path))
if launcher == "direct":
    # no Steam client to talk to: the game must not try to initialise steam_api
    j["vmArgs"] = ["-Dzomboid.steam=0" if a == "-Dzomboid.steam=1" else a for a in j["vmArgs"]]
if not any(a.startswith("-Xlog:gc") for a in j["vmArgs"]):
    j["vmArgs"].append(f"-Xlog:gc:file={gc_log}:time,uptime:filecount=3,filesize=20M")
# a --jfr run that died before its EXIT trap left its recording in the JSON the next run saved as the original (the flip,
# 2026-09-24: every run profiled with JFR for a day); only a --jfr run records
j["vmArgs"] = [a for a in j["vmArgs"] if not a.startswith("-XX:StartFlightRecording")]
for v in j.get("windows", {}).values():
    v["vmArgs"] = [a for a in v["vmArgs"] if not a.startswith("-XX:StartFlightRecording")]
if jfr == "1":
    opt = f"-XX:StartFlightRecording=settings=profile,filename={jfr_file},dumponexit=true"
    if period:
        opt += f",jdk.ExecutionSample#period={period}ms"
    if jfr_settings:
        opt += "," + jfr_settings
    j["vmArgs"].append(opt)
if gc:
    want = {"g1": "-XX:+UseG1GC", "zgc": "-XX:+UseZGC"}[gc]
    def swap(args):
        return [want if a in ("-XX:+UseZGC", "-XX:+UseG1GC") else a for a in args]
    j["vmArgs"] = swap(j["vmArgs"])
    for v in j.get("windows", {}).values():
        v["vmArgs"] = swap(v["vmArgs"])
    if want not in j["vmArgs"] and not any(want in v["vmArgs"] for v in j.get("windows", {}).values()):
        j["vmArgs"].append(want)
for a in os.environ.get("PZOPT_VMARGS", "").split("\n"):
    if a and a not in j["vmArgs"]:
        j["vmArgs"].append(a)
json.dump(j, open(path, "w"), indent="\t")
PY
  echo "launcher vmArgs for this run: $(python3 -c 'import json,sys; j=json.load(open(sys.argv[1])); print(" ".join(a for a in j["vmArgs"]+[x for v in j.get("windows",{}).values() for x in v["vmArgs"]] if "GC" in a or "Flight" in a or "Xm" in a or "Xlog" in a))' "$LAUNCHER")"
}

# 4. launch and wait. The native build sometimes dies in the GL driver while
#    creating the display (SIGSEGV in libnvidia-glcore, hs_err_pid*.log in the
#    install dir) before any harness code runs; such a start-up crash is retried
#    --retries times, a crash after the world was up is reported as a crash.
out="$RUNS/$label-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"
attempt=0
crashed=0
pad_pid=""; pad_feed_pid=""
if [[ -n "$pad_script" ]]; then
  # the pad first, so GLFW's start-up scan sees it
  [[ -f "$ZOMBOID/joypads/$PAD_GUID.config" ]] && pad_joypad_cfg_existed=1 || pad_joypad_cfg_existed=0
  PAD_FIFO="$out/.pad.fifo"; rm -f "$PAD_FIFO"; mkfifo "$PAD_FIFO"
  "${pad_driver[@]}" "$PAD_FIFO" "$FLAG_FILE" > "$out/$pad_log" 2>&1 &
  pad_pid=$!
  for _ in $(seq 1 20); do grep -q "pad ready" "$out/$pad_log" && break; sleep 0.2; done
  grep -q "pad ready" "$out/$pad_log" || { echo "pad failed: $(cat "$out/$pad_log")" >&2; exit 1; }
  cp "$pad_script" "$out/pad-script.txt"
  echo "virtual pad up ($(sed -n 's/^pad ready: //p' "$out/$pad_log")), script $pad_script"
fi
while :; do
  attempt=$((attempt+1))
  rm -f "$ZOMBOID/Lua/pzopt-pad-ready.txt" "$ZOMBOID/Lua/pzopt-inputlag-ready.txt"
  rm -rf "$ZOMBOID/pzopt-hdr"   # HDR frame dumps (pzopt.Hdr) of the previous run
  rm -f "$ZOMBOID"/pzopt-*.out "$ZOMBOID/console.txt" "$ZOMBOID/pzopt-shot.now" "$ZOMBOID/pzopt-shot2.now" "$ZOMBOID/Screenshots/pzopt-shot.png" "$ZOMBOID/Screenshots/pzopt-shot2.png"
  restore_harness_flag; write_flags; write_launch_env
  launch_epoch=$(date +%s)
  route_start_epoch=""; mangohud_end_epoch=""
  if [[ -n "$lead" ]]; then
    route_start_epoch=$((launch_epoch + lead))
    [[ -n "$mangohud_secs" ]] && mangohud_end_epoch=$((launch_epoch + lead - 3 + mangohud_secs + 1))
  fi
  rm -f "$ZOMBOID/pzopt-schedule.out" "$ZOMBOID/pzopt-logdone"
  write_flags
  echo "launching app $APPID (mode=$mode quit_after=${quit_after:-none}, attempt $attempt); output -> $out"
  start=$(date +%s)
  # machine-level CPU/GPU utilization for the whole run (harness/sysmon.sh), windowed by the analyzer
  "$REPO/harness/sysmon.sh" "$out/sysmon.csv" 0.5 "$GAME_PATTERN" &
  sysmon_pid=$!
  # presentation probes (Linux): when the display really flipped each frame (X Present, harness/presentprobe.c)
  # and whether the compositor had variable refresh on (DRM VRR_ENABLED, harness/vrrprobe.py); harness/pacing.py reads both
  present_pid=""; vrrprobe_pid=""
  if [[ "$(uname)" == Linux && -n "${DISPLAY:-}" ]]; then
    if [[ ! -x "$REPO/harness/presentprobe" || "$REPO/harness/presentprobe.c" -nt "$REPO/harness/presentprobe" ]]; then
      cc -O2 -o "$REPO/harness/presentprobe" "$REPO/harness/presentprobe.c" -lxcb -lxcb-present 2>/dev/null || true
    fi
    [[ -x "$REPO/harness/presentprobe" ]] && { "$REPO/harness/presentprobe" --out "$out/present.txt" & present_pid=$!; }
    python3 "$REPO/harness/vrrprobe.py" --interval 0.25 --out "$out/vrr.txt" 2>/dev/null & vrrprobe_pid=$!
  fi
  schedmon_pid=""
  if [[ -n "$schedmon" ]]; then python3 "$REPO/harness/schedmon.py" "$out/schedmon.txt" "$schedmon" & schedmon_pid=$!; fi
  rec_pid=""
  if (( record )); then
    # whole monitor through KMS (Wayland session; window capture is X11-only) at the native desktop
    # resolution. The desktop runs in HDR, so the capture is kept as HDR: NVENC AV1 10-bit, PQ / BT.2020
    # (av1_hdr; the SDR codecs tone-map instead, and h264 NVENC stops at 4096 px wide anyway; verified
    # 2026-09-19). Desktop audio (game sound) goes on an AAC track; stopped with SIGINT once the game has
    # exited. harness/stitch-quad.sh rescales the inputs itself; being HDR they need a tone-map there
    # for an SDR upload.
    rec_mon=$(gpu-screen-recorder --list-monitors 2>/dev/null | head -1 | cut -d'|' -f1)
    # --record-audio game: the game's own FMOD client stream only (PipeWire app "FMOD Audio", linked when it appears),
    # nothing else the desktop plays, at 384 kbps opus instead of the default 128 (harness/audio.py, 2026-09-24)
    rec_audio=(-a default_output)
    [[ "${record_audio:-desktop}" == game ]] && rec_audio=(-a "app:FMOD Audio" -ab 384)
    gpu-screen-recorder -w "${rec_mon:-DP-1}" -f 60 -q very_high -k av1_hdr -cursor no "${rec_audio[@]}" -o "$out/recording.mp4" > "$out/recording.log" 2>&1 &
    rec_pid=$!
  fi
  if [[ "$launcher" == direct ]]; then
    # same variables the Steam wrapper would apply, on the host instead of inside the Steam runtime container
    (
      set -a; . "$LAUNCH_ENV"; set +a
      # The Steam runtime runs the game under the C locale; on the host the JVM picks up the desktop's
      # LC_NUMERIC (de_DE), and MangoHud then fails to parse "fps_metrics=avg,0.01,0.001" (only AVG is
      # shown, "4,2ms" formatting). Pin the numeric locale so direct launches match Steam launches.
      export LC_NUMERIC=C
      # The game reaches GL through glvnd and LWJGL resolves glXSwapBuffers with dlsym, so MangoHud's OpenGL
      # library alone never hooks on the host (no CSV, no HUD in every direct run before 2026-09-19), and its
      # dlsym shim deadlocks the Java launcher at start-up (mh-direct-check). What made it work under Steam was
      # the Steam overlay library in the same preload chain: it interposes dlsym/glX itself and chains to the
      # next hook, so it is preloaded here too when present.
      # With PZOPT_MANGOHUD_LIB=none (Zink) nothing is preloaded: MANGOHUD=1 enables the Vulkan layer.
      if [[ "${PZOPT_MANGOHUD:-0}" == 1 && "${PZOPT_MANGOHUD_LIB:-opengl}" == none ]]; then
        export MANGOHUD=1
      elif [[ "${PZOPT_MANGOHUD:-0}" == 1 && -f /usr/lib/mangohud/libMangoHud_opengl.so ]]; then
        overlay="$HOME/.local/share/Steam/ubuntu12_64/gameoverlayrenderer.so"
        export MANGOHUD=1 LD_PRELOAD="${overlay:+$( [[ -f "$overlay" ]] && echo "$overlay:" )}/usr/lib/mangohud/libMangoHud_opengl.so"
      fi
      # shellcheck disable=SC2086  # --wrap is a command line, split on purpose
      cd "$PZ_DIR/.." && exec setsid ${wrap_cmd:-} "$GAME_WRAPPER" </dev/null >"$out/launcher-stdout.txt" 2>&1
    ) &
  else
    steam -applaunch $APPID >/dev/null 2>&1 &
  fi
  game_pid=""
  for _ in $(seq 1 120); do
    game_pid=$(pgrep -f "$GAME_PATTERN" | head -1 || true)
    [[ -n "$game_pid" ]] && break
    sleep 1
  done
  [[ -n "$game_pid" ]] || { echo "game process did not appear within 120s" >&2; exit 1; }
  echo "game running (pid $game_pid); waiting for exit"
  # Scheduler: waits for the harness to publish the route start (pzopt-schedule.out, written at
  # world-ready), starts the MangoHud log 3 s before it unless --lead put autostart_log in charge,
  # on drive runs presses MangoHud's reset keybind (reset_fps_metrics=Shift_R+F9, the default)
  # through XTEST just as the route starts, because its avg / 1% / 0.1% FPS accumulate from process
  # start (menus, world load; the game is an XWayland window, so xdotool reaches it), and stops the
  # log 2 s after the route ends so the CSV is written at once and the game can quit
  # (pzopt-logdone flag, read by pzopt.Harness's linger state) instead of waiting out log_duration.
  # Everything it does is echoed to <run>/schedule.log as well.
  # The control socket (control=mangoapp, abstract unix socket) speaks ":cmd=param;" and the game
  # accepts a client once per frame, then greets it; a client that sends and hangs up before that
  # (mangohudctl does) is dropped with its message unread. mh_control waits for the greeting first.
  # --shot-at: the Java harness touches pzopt-shot.now when it has asked the game for its own screenshot;
  # a desktop capture (spectacle, whole screen, Wayland session) follows one second later as a second source
  shot_pid=""
  if [[ -n "$shot_at" ]]; then
    ( for _ in $(seq 1 3000); do [[ -f "$ZOMBOID/pzopt-shot.now" ]] && break; sleep 0.2; done
      if [[ -f "$ZOMBOID/pzopt-shot.now" ]]; then
        sleep 1; spectacle -b -n -f -o "$out/shot-desktop.png" >/dev/null 2>&1 || true
        echo "shot-desktop.png at epoch $(date +%s)" | tee -a "$out/schedule.log"
        for _ in $(seq 1 50); do [[ -f "$ZOMBOID/pzopt-shot2.now" ]] && break; sleep 0.2; done
        if [[ -f "$ZOMBOID/pzopt-shot2.now" ]]; then
          sleep 1; spectacle -b -n -f -o "$out/shot2-desktop.png" >/dev/null 2>&1 || true
          echo "shot2-desktop.png at epoch $(date +%s)" | tee -a "$out/schedule.log"
        fi
      fi ) &
    shot_pid=$!
  fi
  if (( inputlag )); then
    # on-screen time of every frame (X Present CompleteNotify, harness/presentprobe.c from the vrr branch) -> present.txt
    probe_bin="${XDG_CACHE_HOME:-$HOME/.cache}/pzopt/presentprobe"
    if [[ ! -x "$probe_bin" || "$REPO/harness/presentprobe.c" -nt "$probe_bin" ]]; then
      mkdir -p "$(dirname "$probe_bin")"
      cc -O2 -o "$probe_bin" "$REPO/harness/presentprobe.c" -lxcb -lxcb-present 2>>"$out/schedule.log" || true
    fi
    [[ -x "$probe_bin" ]] && { "$probe_bin" --out "$out/present.txt" 2>>"$out/schedule.log" & present_pid=$!; }
  fi
  if [[ -n "$pad_script" ]]; then
    ( ready="$ZOMBOID/Lua/$pad_ready"; slog="$out/schedule.log"
      for _ in $(seq 1 900); do [[ -f "$ready" ]] && break; kill -0 "$game_pid" 2>/dev/null || exit 0; sleep 0.2; done
      [[ -f "$ready" ]] || { echo "pad: the menu / world never became ready" | tee -a "$slog" >&2; exit 0; }
      echo "pad: ready at +$(( $(date +%s) - launch_epoch )) s; script starts in 2 s" | tee -a "$slog"
      sleep 2
      grep -v '^[[:space:]]*$' "$pad_script" > "$PAD_FIFO"
      for _ in $(seq 1 6000); do grep -q '^done' "$out/$pad_log" && break; kill -0 "$game_pid" 2>/dev/null || exit 0; sleep 0.2; done
      echo "$pad_done_flag" >> "$FLAG_FILE"
      echo "pad: script done at +$(( $(date +%s) - launch_epoch )) s, the harness quits the game" | tee -a "$slog" ) &
    pad_feed_pid=$!
  fi
  sched_pid=""
  if [[ -n "$mangohud_secs" ]]; then
    ( sched="$ZOMBOID/pzopt-schedule.out"; start_ms=""; slog="$out/schedule.log"
      say() { echo "$*" | tee -a "$slog"; }
      mh_control() {  # mh_control ':logging=1;'  -> 0 when the command was delivered to the overlay
        python3 - "$1" <<'PYC'
import socket, sys, time
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM); s.settimeout(3)
try:
    s.connect(b"\0mangoapp")
    s.recv(512)                       # ":MangoHudControlVersion=1;:DeviceName=...;" - sent once the game accepted us
    s.sendall(sys.argv[1].encode()); time.sleep(0.3); s.close()
except OSError as e:
    print("control socket: " + str(e), file=sys.stderr); sys.exit(1)
PYC
      }
      for _ in $(seq 1 1200); do   # up to 240 s for the world to come up
        [[ -f "$sched" ]] && start_ms=$(sed -n 's/^route_start_epoch_ms=//p' "$sched") && [[ -n "$start_ms" ]] && break
        kill -0 "$game_pid" 2>/dev/null || exit 0; sleep 0.2
      done
      [[ -n "$start_ms" ]] || { say "schedule: the harness never published a route start (no pzopt-schedule.out)" >&2; exit 0; }
      ready_ms=$(sed -n 's/^world_ready_epoch_ms=//p' "$sched")
      say "schedule: world ready $(( (ready_ms - launch_epoch*1000) / 1000 )) s after launch; route starts at +$(( (start_ms - launch_epoch*1000) / 1000 )) s"
      sleep_until_ms() { local d=$(( $1 - $(date +%s%3N) )); (( d > 0 )) && sleep "$(printf '%d.%03d' $((d/1000)) $((d%1000)))"; return 0; }
      new_csv() { find "$MH_OUT" -name '*.csv' ! -name '*_summary.csv' -newermt "@$launch_epoch" 2>/dev/null | grep -q .; }
      if [[ -z "$lead" ]]; then
        sleep_until_ms $((start_ms - 3000))
        started=0
        for attempt in 1 2 3; do
          if mh_control ':logging=1;' 2>>"$slog"; then
            for _ in 1 2 3 4 5 6 7 8 9 10; do new_csv && { started=1; break; }; sleep 0.3; done   # the CSV appears with the first logged frame
            (( started )) && { say "mangohud: log started through the control socket at +$(( $(date +%s) - launch_epoch )) s (attempt $attempt)"; break; }
            say "mangohud: command delivered but no CSV appeared (attempt $attempt)" >&2
          else
            say "mangohud: control socket not reachable (attempt $attempt)" >&2; sleep 1
          fi
        done
        if (( ! started )) && (( native_wayland )); then
          say "mangohud: control socket failed and the toggle_logging key cannot reach a native Wayland window; use --lead for the fixed schedule" >&2
        elif (( ! started )) && command -v xdotool >/dev/null; then
          xdotool keydown Shift_L keydown F2; sleep 0.3; xdotool keyup F2 keyup Shift_L
          say "mangohud: falling back to the toggle_logging key at +$(( $(date +%s) - launch_epoch )) s" >&2
        elif (( ! started )); then
          say "mangohud: could not start the log (no control socket, no xdotool); use --lead for the fixed schedule" >&2
        fi
      fi
      if [[ "$mode" == drive ]] && (( native_wayland )); then
        say "mangohud: native Wayland window, fps metrics reset key skipped (xdotool cannot reach it; the HUD metrics are a rolling 10000-frame window anyway)"
      elif [[ "$mode" == drive ]] && command -v xdotool >/dev/null; then
        sleep_until_ms $((start_ms + 500))
        xdotool keydown Shift_R keydown F9; sleep 0.3; xdotool keyup F9 keyup Shift_R
        say "mangohud: fps metrics reset key sent $(( $(date +%s) - launch_epoch )) s after launch"
      fi
      # route end: pzopt-bench.out is written when the route finishes (or is rejected / times out)
      bench="$ZOMBOID/pzopt-bench.out"
      for _ in $(seq 1 3000); do   # up to 10 min
        [[ -f "$bench" ]] && break
        kill -0 "$game_pid" 2>/dev/null || exit 0; sleep 0.2
      done
      [[ -f "$bench" ]] || exit 0
      if [[ -z "$lead" ]]; then
        sleep 2
        if mh_control ':logging=0;' 2>>"$slog"; then
          say "mangohud: log stopped through the control socket at +$(( $(date +%s) - launch_epoch )) s"
          sleep 1; : > "$ZOMBOID/pzopt-logdone"   # lets the harness quit instead of lingering to log_end_epoch
        else
          say "mangohud: could not stop the log; the game lingers until log_duration elapses" >&2
        fi
      fi ) &
    sched_pid=$!
  fi
  # start-up watchdog: a launch that never gets as far as writing console.txt (a preload deadlock, a driver hang)
  # is killed by PID after 120 s instead of stalling the run forever
  ( for _ in $(seq 1 60); do sleep 2; [[ -f "$ZOMBOID/console.txt" ]] && exit 0; kill -0 "$game_pid" 2>/dev/null || exit 0; done
    echo "start-up watchdog: no console.txt after 120 s, killing pid $game_pid" >&2; kill "$game_pid" 2>/dev/null; sleep 5; kill -9 "$game_pid" 2>/dev/null ) &
  watchdog_pid=$!
  # the Proton process tree is replaced a few times during startup; only treat
  # the game as gone after several consecutive checks find nothing
  gone=0
  while (( gone < 5 )); do
    if pgrep -f "$GAME_PATTERN" >/dev/null; then gone=0; else gone=$((gone+1)); fi
    sleep 2
  done
  end=$(date +%s)
  kill "$watchdog_pid" 2>/dev/null || true; wait "$watchdog_pid" 2>/dev/null || true
  [[ -n "$sched_pid" ]] && { kill "$sched_pid" 2>/dev/null || true; wait "$sched_pid" 2>/dev/null || true; }
  [[ -n "$shot_pid" ]] && { kill "$shot_pid" 2>/dev/null || true; wait "$shot_pid" 2>/dev/null || true; }
  [[ -n "$pad_feed_pid" ]] && { kill "$pad_feed_pid" 2>/dev/null || true; wait "$pad_feed_pid" 2>/dev/null || true; pad_feed_pid=""; }
  kill "$sysmon_pid" 2>/dev/null; wait "$sysmon_pid" 2>/dev/null || true
  [[ -n "$present_pid" ]] && { kill "$present_pid" 2>/dev/null || true; wait "$present_pid" 2>/dev/null || true; }
  [[ -n "$vrrprobe_pid" ]] && { kill "$vrrprobe_pid" 2>/dev/null || true; wait "$vrrprobe_pid" 2>/dev/null || true; }
  [[ -n "$schedmon_pid" ]] && { kill "$schedmon_pid" 2>/dev/null || true; wait "$schedmon_pid" 2>/dev/null || true; }
  if [[ -n "$rec_pid" ]]; then kill -INT "$rec_pid" 2>/dev/null; wait "$rec_pid" 2>/dev/null || true; echo "recording: $out/recording.mp4 ($(du -h "$out/recording.mp4" 2>/dev/null | cut -f1))"; fi
  sleep 2
  crashed=0
  for h in "$PZ_DIR"/hs_err_pid*.log "$ZOMBOID"/hs_err_pid*.log; do
    [[ -f "$h" && "$(stat -c %Y "$h")" -ge "$launch_epoch" ]] || continue
    crashed=1
    mv -f "$h" "$out/$(basename "$h" .log)-attempt$attempt.log"
    echo "CRASH: $(sed -n 's/^# Problematic frame:.*//;/^# C  \[/p' "$out/$(basename "$h" .log)-attempt$attempt.log" | head -1)" >&2
  done
  rm -f "$PZ_DIR"/core.[0-9]*
  if (( crashed )) && ! grep -a -q 'harness: world ready' "$ZOMBOID/console.txt" 2>/dev/null && (( attempt <= retries )); then
    echo "start-up crash after $((end-start))s; retrying" >&2
    cp "$ZOMBOID/console.txt" "$out/console-attempt$attempt.txt" 2>/dev/null || true
    continue
  fi
  break
done

# 5. collect
[[ -f "$ZOMBOID/console.txt" ]] || { echo "fresh game produced no console.txt" >&2; exit 1; }
cp "$ZOMBOID/console.txt" "$out/console.txt"
cp "$ZOMBOID"/pzopt-*.out "$out/" 2>/dev/null || true
[[ -d "$ZOMBOID/pzopt-hdr" ]] && mv "$ZOMBOID/pzopt-hdr" "$out/hdr"   # HDR frame dumps (tools/hdr/hdrframe.py)
[[ -f "$ZOMBOID/Screenshots/pzopt-shot.png" ]] && cp "$ZOMBOID/Screenshots/pzopt-shot.png" "$out/shot-game.png"
[[ -f "$ZOMBOID/Screenshots/pzopt-shot2.png" ]] && cp "$ZOMBOID/Screenshots/pzopt-shot2.png" "$out/shot2-game.png"
cp "$PZ_DIR/pzopt.properties" "$out/pzopt.properties"
cp "$LAUNCHER" "$out/ProjectZomboid64.json"
{ echo "layout=$LAYOUT"; echo "mode=$mode"; echo "crashed=$crashed"; echo "attempts=$attempt"; echo "jfr=$jfr"; echo "jfr_period=$jfr_period"; echo "jfr_settings=${jfr_settings[*]:-}"; echo "game_profiler=$game_profiler"; echo "gc=${gc:-default}"; echo "no_dashboard=$no_dashboard"; echo "mangohud_secs=$mangohud_secs"; echo "no_mangohud=${no_mangohud:-0}"; echo "lead=${lead:-dynamic}"; echo "route_seconds=$route_seconds"; echo "renderer=$renderer"; echo "game_env=${game_env[*]:-}"; echo "record=$record"; echo "record_audio=${record_audio:-desktop}"; echo "launcher=$launcher"; echo "game_options=${game_options[*]:-}"; echo "mods=${extra_mods[*]:-}"; echo "vmargs=${vmargs[*]:-}"; echo "mangohud_config=${mangohud_config:-default}"; echo "launch_epoch=$launch_epoch"; echo "run_seconds=$((end-start))"; echo "preset=${preset:-none}"; echo "flags=${extra_flags[*]:-}"; } > "$out/run.opts"
# gc.log rolls over (filecount=3); keep the segments that were written during this run
for g in "$PZ_DIR"/gc.log "$PZ_DIR"/gc.log.[0-9]*; do
  [[ -f "$g" ]] || continue
  if [[ "$(stat -c %Y "$g")" -ge "$launch_epoch" ]]; then cp "$g" "$out/$(basename "$g")"; fi
done
if (( keep_save )); then
  # hard links when the run dir is on the save's filesystem, else a plain copy (~330 MB for the bench save)
  if cp -al "$ZOMBOID/Saves/$BENCH_SAVE" "$out/save" 2>/dev/null; then echo "exit save kept: $out/save (hard links)"
  else rm -rf "$out/save"; cp -a "$ZOMBOID/Saves/$BENCH_SAVE" "$out/save" && echo "exit save kept: $out/save (copy)"; fi
fi
# the resume shot this run's exit save wrote (pzopt.ResumeShot), for a later --resume-shot
for g in "$ZOMBOID/Saves/$BENCH_SAVE"/pzopt-resume.jpg "$ZOMBOID/Saves/$BENCH_SAVE"/pzopt-resume.properties "$ZOMBOID/Saves/$BENCH_SAVE"/pzopt-resume-load.txt; do
  if [[ -f "$g" && "$(stat -c %Y "$g")" -ge "$launch_epoch" ]]; then cp "$g" "$out/"; fi
done
if [[ -f "$ASPROF_OUT" ]]; then mv "$ASPROF_OUT" "$out/asprof.jfr"; fi
if (( jfr )); then
  if [[ -f "$JFR_OUT" ]]; then mv "$JFR_OUT" "$out/pzopt.jfr"; echo "jfr recording: $out/pzopt.jfr ($(du -h "$out/pzopt.jfr" | cut -f1))"; else echo "no JFR recording found at $JFR_OUT" >&2; fi
fi
if (( game_profiler )); then
  if [[ -d "$ZOMBOID/Recording" ]]; then
    mkdir -p "$out/profiler"
    cp -a "$ZOMBOID/Recording/." "$out/profiler/"
  else
    echo "GameProfiler recording directory not found: $ZOMBOID/Recording" >&2
  fi
fi
if [[ -n "$mangohud_secs" && "$launcher" == steam && "$LAYOUT" == native ]] && ! grep -a -q 'harness: MangoHud is loaded' "$out/console.txt"; then
  echo "MangoHud was not loaded into the game: set the Steam launch options to '$REPO/harness/steam-launch.sh %command%' (see harness/steam-launch.sh)" >&2
fi
if [[ -n "$mangohud_secs" ]]; then
  # newest MangoHud csv written since launch
  mh=$(find "$MH_OUT" -name '*.csv' ! -name '*_summary.csv' -newermt "@$launch_epoch" -printf '%T@ %p\n' 2>/dev/null | sort -n | tail -1 | cut -d' ' -f2-)
  if [[ -n "$mh" ]]; then cp "$mh" "$out/mangohud.csv"; basename "$mh" > "$out/mangohud.name"; echo "mangohud log: $mh"; else echo "no MangoHud log found in $MH_OUT" >&2; fi
fi
echo "run took $((end-start))s (attempt $attempt$( (( crashed )) && echo ', CRASHED')); log at $out/console.txt"
echo "--- [pzopt] lines:"
grep -a -F '[pzopt' "$out/console.txt" | head -40 || true
echo "--- errors:"
grep -a -iE 'exception|error' "$out/console.txt" | grep -a -v 'ERROR: 0:0\|GL_' | head -10 || true
