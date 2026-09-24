#!/bin/bash
# harness/run-mac.sh — the run.sh steps a benchmark needs on the macOS Steam depot ("Project Zomboid.app").
# Runs ON the Mac (bash 3.2, python3 from the Command Line Tools, no Homebrew needed).
#
#   harness/run-mac.sh install <classes-dir>   copy the built overrides (build/classes from the desktop) into
#                                              <app>/Contents/Java; what was written goes to pzopt-installed.txt
#   harness/run-mac.sh uninstall               remove exactly the files install wrote
#   harness/run-mac.sh status                  installed or not, revision, file count
#   harness/run-mac.sh --label <name> [--mode drive|bench|verify] [--flag k=v]... [--prop k=v]... [--option k=v]... [--env K=V]...
#                      [--quit-after secs] [--timeout secs] [--vmarg ARG]... [--dashboard]
#
# What a run does (same contract as run.sh / run-win.ps1):
#   1. installs the pzopt-harness Lua mod into ~/Zomboid/mods and enables it in mods/default.txt,
#   2. rebuilds Saves/Sandbox/pzopt-bench from Saves/Sandbox/pzopt-bench-template (unpack
#      harness/bench-save/pzopt-bench-template.tar.zst there once; the Mac has no zstd, so send a plain tar),
#   3. points latestSave.ini at the bench save, writes ~/Zomboid/Lua/pzopt-harness.txt and
#      <app>/Contents/Java/pzopt.properties, applies --option keys to options.ini,
#   4. starts the game directly from the bundled JRE with the Info.plist JVMOptions (-Dzomboid.steam=0, the
#      classpath ".:projectzomboid.jar" so the loose classes shadow the jar, the same order the bundle's
#      JavaAppLauncher uses), wrapped in caffeinate so the display stays on; samples CPU (top) and GPU
#      (ioreg IOAccelerator "Device Utilization %") into sysmon.csv with harness/sysmon.sh's columns,
#   5. waits for the JVM to exit (the Java harness quits at the route end), collects console.txt, pzopt-*.out,
#      sysmon.csv, crash logs into harness/runs/<label>-<timestamp>/, restores every file it touched.
# No MangoHud, JFR or recording: frame times come from pzopt-overlay.out (the in-game overlay log).
# The player's real saves are never loaded or written.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
PZ_APP="${PZ_APP:-$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app}"
PZ_DIR="$PZ_APP/Contents/Java"                 # jar, natives, and where the loose classes go
ZOMBOID="${ZOMBOID:-$HOME/Zomboid}"
RUNS="${RUNS:-$REPO/harness/runs}"
JAR="$PZ_DIR/projectzomboid.jar"
MANIFEST="$PZ_DIR/pzopt-installed.txt"
MOD_SRC="$REPO/harness/mod/pzopt-harness"
MOD_ID=pzopt-harness
DASH_ID=PZDashboard
BENCH_SAVE="Sandbox/pzopt-bench"
TEMPLATE="$ZOMBOID/Saves/${BENCH_SAVE}-template"
FLAG_FILE="$ZOMBOID/Lua/pzopt-harness.txt"

die() { echo "$*" >&2; exit 1; }
[[ -f "$JAR" ]] || die "projectzomboid.jar not found under $PZ_APP (set PZ_APP)"

java_bin() {
  local arch; arch=$(uname -m)
  local j="$PZ_APP/Contents/PlugIns/jre-$( [[ "$arch" == arm64 ]] && echo aarch64 || echo x86_64 )/Contents/Home/bin/java"
  [[ -x "$j" ]] || j=$(ls -d "$PZ_APP"/Contents/PlugIns/jre-*/Contents/Home/bin/java 2>/dev/null | head -1)
  [[ -x "$j" ]] || die "no bundled JRE under $PZ_APP/Contents/PlugIns"
  echo "$j"
}

jar_revision() { # zombie.GitVersion holds REVISION as a constant-pool string
  unzip -p "$JAR" zombie/GitVersion.class | grep -aoE '\b[0-9a-f]{10}\b' | head -1
}

# --- install / uninstall / status -------------------------------------------------------------
case "${1:-}" in
  install)
    src="${2:-}"; [[ -d "$src" ]] || die "usage: $0 install <classes-dir>"
    [[ -f "$src/pzopt/build-info.properties" ]] || die "$src has no pzopt/build-info.properties (not a build/classes tree)"
    [[ -f "$MANIFEST" ]] && die "already installed (see: $0 status); uninstall first"
    want=$(sed -n 's/^revision=//p' "$src/pzopt/build-info.properties"); have=$(jar_revision)
    [[ "$want" == "$have" ]] || die "classes were built for game revision $want but the jar is $have"
    tmp=$(mktemp)
    { echo "# files written by harness/run-mac.sh install — do not edit"; echo "# revision=$want installed=$(date -u +%Y-%m-%dT%H:%M:%SZ)"; } > "$tmp"
    n=0
    while IFS= read -r f; do
      rel="${f#$src/}"
      [[ -e "$PZ_DIR/$rel" ]] && { rm -f "$tmp"; die "refusing to overwrite existing file: $PZ_DIR/$rel"; }
      mkdir -p "$PZ_DIR/$(dirname "$rel")"
      cp "$f" "$PZ_DIR/$rel"
      echo "$rel $(shasum -a 256 "$PZ_DIR/$rel" | cut -d' ' -f1)" >> "$tmp"
      n=$((n+1))
    done < <(find "$src" -type f \( -name '*.class' -o -name '*.properties' -o -name '*.lua' -o -name '*.txt' \) | sort)
    mv "$tmp" "$MANIFEST"
    echo "installed $n files into $PZ_DIR (revision $want)"; exit 0 ;;
  uninstall)
    [[ -f "$MANIFEST" ]] || { echo "not installed"; exit 0; }
    n=0
    while read -r rel sha; do
      [[ "$rel" == \#* || -z "$rel" ]] && continue
      rm -f "$PZ_DIR/$rel"; n=$((n+1))
      d=$(dirname "$rel")
      while [[ "$d" != "." && -d "$PZ_DIR/$d" ]] && [[ -z "$(ls -A "$PZ_DIR/$d")" ]]; do rmdir "$PZ_DIR/$d"; d=$(dirname "$d"); done
    done < "$MANIFEST"
    rm -f "$MANIFEST"
    echo "removed $n files from $PZ_DIR"; exit 0 ;;
  status)
    echo "app:            $PZ_APP"
    echo "game revision:  $(jar_revision)"
    if [[ -f "$MANIFEST" ]]; then
      echo "installed:      yes ($(grep -vc '^#' "$MANIFEST") files, $(sed -n 's/^# revision=\([0-9a-f]*\).*/\1/p' "$MANIFEST"))"
    else echo "installed:      no"; fi
    exit 0 ;;
esac

# --- run ---------------------------------------------------------------------------------------
label=""; mode="drive"; quit_after=""; timeout=900; extra_flags=(); props=(); game_options=(); vmargs=(); envs=(); dashboard=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --label) label="$2"; shift 2 ;;
    --mode) mode="$2"; shift 2 ;;
    --quit-after) quit_after="$2"; shift 2 ;;
    --timeout) timeout="$2"; shift 2 ;;        # hard limit: the JVM is killed after this many seconds
    --flag) extra_flags+=("$2"); shift 2 ;;    # extra key=value for pzopt-harness.txt
    --prop) props+=("$2"); shift 2 ;;          # key=value for Contents/Java/pzopt.properties (see pzopt.Config)
    --option) game_options+=("$2"); shift 2 ;; # key=value written into ~/Zomboid/options.ini for the run
    --vmarg) vmargs+=("$2"); shift 2 ;;        # extra JVM option
    --env) envs+=("$2"); shift 2 ;;            # KEY=VALUE in the game's environment (e.g. MTL_HUD_ENABLED=1)
    --dashboard) dashboard=1; shift ;;         # keep the PZDashboard mod (default: dropped from the bench save)
    *) die "unknown option: $1" ;;
  esac
done
[[ -n "$label" ]] || die "--label is required"
[[ -f "$ZOMBOID/options.ini" ]] || die "$ZOMBOID/options.ini not found (start the game once so ~/Zomboid exists)"
# a backup left by a run that never got to restore is the player's file: refuse rather than overwrite it
[[ -f "$ZOMBOID/options.ini.pzopt-orig" ]] && die "stale $ZOMBOID/options.ini.pzopt-orig from an unrestored run: restore it by hand first"
[[ -d "$TEMPLATE" ]] || die "bench save template missing: $TEMPLATE (unpack harness/bench-save/pzopt-bench-template.tar.zst into $ZOMBOID/Saves/Sandbox/)"
JAVA=$(java_bin)

# mods.txt / default.txt editing (CRLF-safe) — python3 ships with the Command Line Tools
mods_edit() { # $1 file, $2 enable|disable, $3 mod id
  python3 - "$1" "$2" "$3" <<'EOF'
import re, sys
path, op, mod = sys.argv[1:4]
txt = open(path, encoding="utf-8", errors="replace").read()
nl = "\r\n" if "\r\n" in txt else "\n"
line = "    mod = %s,%s" % (mod, nl)
if op == "enable":
    if re.search(r"^    mod = %s,\r?$" % re.escape(mod), txt, re.M): sys.exit(0)
    new, n = re.subn(r"(?m)^mods\r?\n\{\r?\n", lambda m: m.group(0) + line, txt, count=1)
    if n != 1: sys.exit("could not enable %s in %s" % (mod, path))
else:
    new = re.sub(r"(?m)^    mod = %s,\r?\n" % re.escape(mod), "", txt)
open(path, "w", encoding="utf-8", newline="").write(new)
EOF
}

# 1. harness mod
mkdir -p "$ZOMBOID/mods" "$ZOMBOID/Lua"
rm -rf "$ZOMBOID/mods/$MOD_ID"; cp -R "$MOD_SRC" "$ZOMBOID/mods/$MOD_ID"
[[ -f "$ZOMBOID/mods/default.txt" ]] || printf 'VERSION = 1,\n\nmods\n{\n}\n\nmaps\n{\n}\n' > "$ZOMBOID/mods/default.txt"
cp "$ZOMBOID/mods/default.txt" "$ZOMBOID/mods/default.txt.pzopt-orig"
mods_edit "$ZOMBOID/mods/default.txt" enable "$MOD_ID"
(( dashboard )) || mods_edit "$ZOMBOID/mods/default.txt" disable "$DASH_ID"

# 2. bench save, byte-identical every run
rm -rf "$ZOMBOID/Saves/$BENCH_SAVE"; cp -R "$TEMPLATE" "$ZOMBOID/Saves/$BENCH_SAVE"
mods_edit "$ZOMBOID/Saves/$BENCH_SAVE/mods.txt" enable "$MOD_ID"
(( dashboard )) || mods_edit "$ZOMBOID/Saves/$BENCH_SAVE/mods.txt" disable "$DASH_ID"

# 3. latestSave.ini, flag file, pzopt.properties, options.ini
[[ -f "$ZOMBOID/latestSave.ini" ]] && cp "$ZOMBOID/latestSave.ini" "$ZOMBOID/latestSave.ini.pzopt-orig"
[[ -f "$PZ_DIR/pzopt.properties" ]] && cp "$PZ_DIR/pzopt.properties" "$PZ_DIR/pzopt.properties.pzopt-orig"
cp "$ZOMBOID/options.ini" "$ZOMBOID/options.ini.pzopt-orig"
# the game writes no newline after the last option: an --option append would glue the key onto that line
[[ -z "$(tail -c1 "$ZOMBOID/options.ini")" ]] || echo >> "$ZOMBOID/options.ini"
game_pid=""; sampler_pid=""; macpower_pid=""
restore() {
  [[ -n "$sampler_pid" ]] && { kill "$sampler_pid" 2>/dev/null || true; }
  [[ -n "$macpower_pid" ]] && { kill "$macpower_pid" 2>/dev/null || true; }
  [[ -n "$game_pid" ]] && kill -0 "$game_pid" 2>/dev/null && { kill "$game_pid" 2>/dev/null || true; }
  [[ -f "$ZOMBOID/mods/default.txt.pzopt-orig" ]] && mv -f "$ZOMBOID/mods/default.txt.pzopt-orig" "$ZOMBOID/mods/default.txt"
  if [[ -f "$ZOMBOID/latestSave.ini.pzopt-orig" ]]; then mv -f "$ZOMBOID/latestSave.ini.pzopt-orig" "$ZOMBOID/latestSave.ini"; fi
  if [[ -f "$PZ_DIR/pzopt.properties.pzopt-orig" ]]; then mv -f "$PZ_DIR/pzopt.properties.pzopt-orig" "$PZ_DIR/pzopt.properties"; else rm -f "$PZ_DIR/pzopt.properties"; fi
  [[ -f "$ZOMBOID/options.ini.pzopt-orig" ]] && mv -f "$ZOMBOID/options.ini.pzopt-orig" "$ZOMBOID/options.ini"
  rm -f "$FLAG_FILE"
  return 0
}
trap restore EXIT
printf '%s\r\n%s\r\n' "$(basename "$BENCH_SAVE")" "$(dirname "$BENCH_SAVE")" > "$ZOMBOID/latestSave.ini"
{
  echo "mode=$mode"
  echo "dashboard=$( (( dashboard )) && echo enabled || echo disabled)"
  [[ -n "$quit_after" ]] && echo "quit_after=$quit_after"
  printf '%s\n' "${extra_flags[@]:-}" | grep -q '^settle=' || echo "settle=5"
  for f in "${extra_flags[@]:-}"; do [[ -n "$f" ]] && echo "$f"; done
} > "$FLAG_FILE"
# hdrAuto (default on in the game) would turn HDR on for every run on the XDR panel: runs stay SDR unless they ask
printf '%s\n' "${props[@]:-}" | grep -q '^hdrAuto=' || props+=("hdrAuto=false")
printf '%s\n' "${props[@]:-}" | grep . > "$PZ_DIR/pzopt.properties" || : > "$PZ_DIR/pzopt.properties"
for kv in "${game_options[@]:-}"; do
  [[ -n "$kv" ]] || continue
  k="${kv%%=*}"; v="${kv#*=}"
  if grep -q "^$k=" "$ZOMBOID/options.ini"; then sed -i '' "s|^$k=.*|$k=$v|" "$ZOMBOID/options.ini"; else printf '%s=%s\n' "$k" "$v" >> "$ZOMBOID/options.ini"; fi
done

# 4. launch and sample
out="$RUNS/$label-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$out"
rm -f "$ZOMBOID"/pzopt-*.out "$ZOMBOID/console.txt" "$ZOMBOID/pzopt-schedule.out" "$ZOMBOID/pzopt-logdone"
rm -f "$ZOMBOID"/Screenshots/pzopt-*.png
rm -rf "$ZOMBOID/pzopt-hdr"   # HDR frame dumps (pzopt.Hdr) of the previous run
rm -f "$PZ_DIR"/hs_err_pid*.log "$ZOMBOID"/hs_err_pid*.log
jvm_opts=()
while IFS= read -r a; do
  [[ "$a" == "-Dzomboid.steam=1" ]] && a="-Dzomboid.steam=0"
  jvm_opts+=("$a")
done < <(plutil -extract JVMOptions json -o - "$PZ_APP/Contents/Info.plist" | python3 -c 'import json,sys; print("\n".join(json.load(sys.stdin)))')
launch_epoch=$(date +%s)
echo "launching $mode run ($(basename "$out")); jvm: ${jvm_opts[*]} ${vmargs[*]:-}"
(
  cd "$PZ_DIR"
  exec env ${envs[@]+"${envs[@]}"} caffeinate -dis "$JAVA" "${jvm_opts[@]}" ${vmargs[@]+"${vmargs[@]}"} -Djava.library.path=. \
    -cp ".:projectzomboid.jar" zombie.gameStates.MainScreenState </dev/null >"$out/launcher-stdout.txt" 2>&1
) &
caff_pid=$!
sleep 2
# caffeinate execs the utility in place and forks a child that holds the assertion, so the JVM is
# the pid whose command line starts with the java binary (the child's line also contains it)
game_pid=""
for p in $(pgrep -f 'zombie.gameStates.MainScreenState' || true); do
  case "$(ps -o comm= -p "$p" 2>/dev/null)" in *caffeinate*) ;; *) game_pid="$p"; break ;; esac
done
[[ -n "$game_pid" ]] || die "the JVM did not start (see $out/launcher-stdout.txt)"
echo "game running (pid $game_pid); waiting for exit (timeout ${timeout}s)"
ps -o pid,ppid,%cpu,command -p "$caff_pid,$game_pid" > "$out/sysmon.log" 2>&1 || true   # which pid the sampler follows

# sysmon.csv: same columns as harness/sysmon.sh. cpu_pct from top's interval samples (100 = every core busy),
# game_cpu_pct from top's per-process line (100 = one core), gpu_pct from the IOAccelerator utilization counter.
python3 - "$out/sysmon.csv" "$game_pid" >> "$out/sysmon.log" 2>&1 <<'EOF' &
import re, subprocess, sys, time
csv, pid = sys.argv[1], sys.argv[2]
f = open(csv, "w", buffering=1)
f.write("epoch_ms,cpu_pct,cpu_busiest_core_pct,gpu_pct,gpu_sm_mhz,gpu_mem_mhz,gpu_w,gpu_c,vram_mib,game_cpu_pct,bat_w,energy_impact\n")
top = subprocess.Popen(["top", "-l", "0", "-s", "1", "-n", "1", "-stats", "pid,cpu,power", "-pid", pid], stdout=subprocess.PIPE, text=True)
def gpu():
    try:
        o = subprocess.run(["ioreg", "-r", "-d", "1", "-c", "IOAccelerator"], capture_output=True, text=True, timeout=2).stdout
        m = re.search(r'"Device Utilization %"=(\d+)', o)
        return m.group(1) if m else ""
    except Exception:
        return ""
first = True; cpu = ""; game = ""
for line in top.stdout:
    m = re.match(r"CPU usage: ([\d.]+)% user, ([\d.]+)% sys, ([\d.]+)% idle", line)
    if m:
        cpu = "%.1f" % (float(m.group(1)) + float(m.group(2)))
        continue
    m = re.match(r"^\s*%s\s+([\d.]+)\s+([\d.]+)?" % pid, line)
    if m:
        game = m.group(1)
        power = m.group(2) or ""  # top's energy impact of the game process (Apple's model: CPU time by cluster, GPU, wakeups)
        if first:  # top's first sample is since process start, not an interval
            first = False; continue
        f.write("%d,%s,,%s,,,,,,%s,,%s\n" % (int(time.time() * 1000), cpu, gpu(), game, power))
EOF
sampler_pid=$!
# P / E cluster load and the SMC system power (harness/macpower.py) beside it
python3 "$HERE/macpower.py" "$out/power.csv" 1 >> "$out/sysmon.log" 2>&1 &
macpower_pid=$!

start=$(date +%s)
while kill -0 "$game_pid" 2>/dev/null; do
  now=$(date +%s)
  if (( now - start > timeout )); then echo "timeout after ${timeout}s: killing pid $game_pid" >&2; kill "$game_pid" 2>/dev/null || true; sleep 5; kill -9 "$game_pid" 2>/dev/null || true; break; fi
  if (( now - start > 120 )) && [[ ! -f "$ZOMBOID/console.txt" ]]; then echo "no console.txt after 120 s: killing pid $game_pid" >&2; kill "$game_pid" 2>/dev/null || true; break; fi
  sleep 2
done
wait "$caff_pid" 2>/dev/null || true
end=$(date +%s)
kill "$sampler_pid" 2>/dev/null || true; wait "$sampler_pid" 2>/dev/null || true; sampler_pid=""
kill "$macpower_pid" 2>/dev/null || true; wait "$macpower_pid" 2>/dev/null || true; macpower_pid=""
game_pid=""

# 5. collect
crashed=0
for h in "$PZ_DIR"/hs_err_pid*.log "$ZOMBOID"/hs_err_pid*.log; do [[ -f "$h" ]] || continue; cp "$h" "$out/"; crashed=1; done
[[ -f "$ZOMBOID/console.txt" ]] && cp "$ZOMBOID/console.txt" "$out/console.txt" || echo "no console.txt written" >&2
cp "$ZOMBOID"/pzopt-*.out "$out/" 2>/dev/null || true
cp "$ZOMBOID"/Screenshots/pzopt-*.png "$out/" 2>/dev/null || true   # harness screenshots (options_tab rig)
[[ -d "$ZOMBOID/pzopt-hdr" ]] && mv "$ZOMBOID/pzopt-hdr" "$out/hdr"   # HDR frame dumps (tools/hdr/hdrframe.py)
cp "$PZ_DIR/pzopt.properties" "$out/pzopt.properties"
cp "$FLAG_FILE" "$out/pzopt-harness.txt"
{ echo "layout=mac"; echo "mode=$mode"; echo "crashed=$crashed"; echo "attempts=1"; echo "jfr=0"; echo "game_profiler=0"; echo "gc=default"
  echo "no_dashboard=$(( 1 - dashboard ))"; echo "mangohud_secs="; echo "no_mangohud=1"; echo "lead=dynamic"; echo "route_seconds="
  echo "renderer=apple-gl"; echo "game_env="; echo "record=0"; echo "launcher=direct"; echo "game_options=${game_options[*]:-}"
  echo "mods="; echo "vmargs=${vmargs[*]:-}"; echo "launch_epoch=$launch_epoch"; echo "run_seconds=$((end-start))"; echo "preset=none"
  echo "flags=${extra_flags[*]:-}"; echo "jvm_opts=${jvm_opts[*]}"; echo "host=$(scutil --get LocalHostName 2>/dev/null) $(sysctl -n machdep.cpu.brand_string) macOS $(sw_vers -productVersion)"
} > "$out/run.opts"
echo "run finished in $((end-start))s (crashed=$crashed): $out"
grep -a -E 'harness: (world ready|route complete|route)|settings:|OpenGL version|Display mode' "$out/console.txt" 2>/dev/null | head -8 || true
