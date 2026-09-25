#!/bin/bash
# harness/mac-update-e2e.sh - the in-game updater end to end on the Mac (runs ON the Mac, bash 3.2), 2026-09-26.
#
# With the pzopt build under test installed (queue --install), this script:
#   1. stages a Steam Workshop copy of that build in the Mac's Steam library
#      (steamapps/workshop/content/108600/3805285544/mods/PZ_Optimization/42/pzopt-classes) whose build-info says it is
#      newer (commit e2e<time>, built now+60), so the update check offers it without GitHub;
#   2. launches the game the way run-mac.sh does, but with no harness flag file (it stays on the main menu) and
#      pzopt.properties devUpdateDrive=true: the menu's Lua opens the update dialog, presses Update now, then
#      Restart game (the real buttons); pzopt.Restart relaunches the game once it has quit, and the restarted
#      process (same properties) logs the time since the press when its menu is up and quits;
#   3. checks the install (build-info commit = the copy's, every manifest line's sha256 matches the file) and prints
#      the timings from both consoles;
#   4. restores everything: the original build-info / manifest / file list, pzopt.properties, options.ini, and removes
#      the staged Workshop copy. Kills any game it started on a timeout.
# Refuses to run when a game is running, a harness flag file exists, or a real Workshop item 3805285544 is present.
set -u
PZ_APP="$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app"
PZ_DIR="$PZ_APP/Contents/Java"
ZOMBOID="$HOME/Zomboid"
ITEM="$HOME/Library/Application Support/Steam/steamapps/workshop/content/108600/3805285544"
COPY="$ITEM/mods/PZ_Optimization/42/pzopt-classes"
OUT="${1:-$HOME/PZ_Optimization/harness/runs/update-e2e-$(date +%Y%m%d-%H%M%S)}"
JAVA=$(ls -d "$PZ_APP"/Contents/PlugIns/jre-*/Contents/Home/bin/java 2>/dev/null | head -1)
die() { echo "e2e: $*" >&2; exit 1; }

pgrep -f 'zombie.gameStates.MainScreenState' >/dev/null && die "a game is running"
[[ -f "$ZOMBOID/Lua/pzopt-harness.txt" ]] && die "a harness flag file exists (a run is going?)"
[[ -e "$ITEM" ]] && die "a Workshop item 3805285544 exists on this Mac; not touching it"
[[ -f "$PZ_DIR/pzopt-installed.txt" ]] || die "no pzopt install in $PZ_DIR"
[[ -x "$JAVA" ]] || die "no bundled JRE"
mkdir -p "$OUT"
BACK="$OUT/backup"; mkdir -p "$BACK"
cp "$PZ_DIR/pzopt/build-info.properties" "$PZ_DIR/pzopt-installed.txt" "$BACK/"
[[ -f "$PZ_DIR/pzopt-files.txt" ]] && cp "$PZ_DIR/pzopt-files.txt" "$BACK/"
[[ -f "$PZ_DIR/pzopt.properties" ]] && cp "$PZ_DIR/pzopt.properties" "$BACK/pzopt.properties"
cp "$ZOMBOID/options.ini" "$BACK/options.ini"
orig_commit=$(sed -n 's/^commit=//p' "$BACK/build-info.properties")
pids=()

restore() {
  for p in "${pids[@]:-}"; do [[ -n "$p" ]] && kill -0 "$p" 2>/dev/null && kill "$p" 2>/dev/null; done
  for p in $(pgrep -f 'zombie.gameStates.MainScreenState' || true); do
    # only games started from this game dir after the script began
    kill "$p" 2>/dev/null
  done
  cp "$BACK/build-info.properties" "$PZ_DIR/pzopt/build-info.properties"
  cp "$BACK/pzopt-installed.txt" "$PZ_DIR/pzopt-installed.txt"
  if [[ -f "$BACK/pzopt-files.txt" ]]; then cp "$BACK/pzopt-files.txt" "$PZ_DIR/pzopt-files.txt"; else rm -f "$PZ_DIR/pzopt-files.txt"; fi
  if [[ -f "$BACK/pzopt.properties" ]]; then cp "$BACK/pzopt.properties" "$PZ_DIR/pzopt.properties"; else rm -f "$PZ_DIR/pzopt.properties"; fi
  cp "$BACK/options.ini" "$ZOMBOID/options.ini"
  rm -rf "$ITEM"
  rmdir "$(dirname "$ITEM")" 2>/dev/null
  # the restored install must match its manifest again
  python3 - "$PZ_DIR" <<'EOF'
import hashlib, sys, os
d = sys.argv[1]; bad = 0; n = 0
for line in open(os.path.join(d, "pzopt-installed.txt")):
    line = line.strip()
    if not line or line.startswith("#"): continue
    rel, sha = line.split(" ", 1); n += 1
    if hashlib.sha256(open(os.path.join(d, rel), "rb").read()).hexdigest() != sha: bad += 1; print("e2e: restore mismatch", rel)
print("e2e: restored install, %d files, %d mismatches" % (n, bad))
EOF
}
trap restore EXIT

# 1. the Workshop copy: the installed files, build-info newer
stamp=$(date +%s)
new_commit="e2e$(printf '%x' "$stamp" | tail -c 5)"
mkdir -p "$COPY"
( cd "$PZ_DIR" && grep -v '^#' pzopt-installed.txt | cut -d' ' -f1 | grep -v '^pzopt-files.txt$' > "$OUT/files.txt" )
while IFS= read -r rel; do
  mkdir -p "$COPY/$(dirname "$rel")"
  cp "$PZ_DIR/$rel" "$COPY/$rel"
done < "$OUT/files.txt"
sed -i '' -e "s/^commit=.*/commit=$new_commit/" -e "s/^built=.*/built=$((stamp + 60))/" "$COPY/pzopt/build-info.properties"
{ cat "$OUT/files.txt"; echo "pzopt-files.txt"; } > "$COPY/pzopt-files.txt"
echo "e2e: staged Workshop copy $new_commit ($(wc -l < "$OUT/files.txt" | tr -d ' ') files) over installed $orig_commit"

# 2. launch: menu only, the drive rig on
printf 'devUpdateDrive=true\nhdrAuto=false\n' > "$PZ_DIR/pzopt.properties"
rm -f "$ZOMBOID/console.txt"
jvm_opts=()
while IFS= read -r a; do
  [[ "$a" == "-Dzomboid.steam=1" ]] && a="-Dzomboid.steam=0"
  jvm_opts+=("$a")
done < <(plutil -extract JVMOptions json -o - "$PZ_APP/Contents/Info.plist" | python3 -c 'import json,sys; print("\n".join(json.load(sys.stdin)))')
t_launch=$(python3 -c 'import time; print(int(time.time()*1000))')
( cd "$PZ_DIR" && exec "$JAVA" "${jvm_opts[@]}" -Djava.library.path=. -cp ".:projectzomboid.jar" zombie.gameStates.MainScreenState \
    </dev/null >"$OUT/boot1-stdout.txt" 2>&1 ) &
boot1=$!
pids+=("$boot1")
caffeinate -dis -w "$boot1" &
echo "e2e: boot 1 pid $boot1"
for i in $(seq 1 1800); do kill -0 "$boot1" 2>/dev/null || break; sleep 0.1; done
if kill -0 "$boot1" 2>/dev/null; then cp "$ZOMBOID/console.txt" "$OUT/boot1-console.txt"; die "boot 1 did not quit within 180 s"; fi
t_exit1=$(python3 -c 'import time; print(int(time.time()*1000))')
cp "$ZOMBOID/console.txt" "$OUT/boot1-console.txt"
echo "e2e: boot 1 ended $((t_exit1 - t_launch)) ms after launch"

# 3. the restarted process
boot2=""
for i in $(seq 1 200); do
  for p in $(pgrep -f 'zombie.gameStates.MainScreenState' || true); do [[ "$p" != "$boot1" ]] && boot2="$p"; done
  [[ -n "$boot2" ]] && break
  sleep 0.05
done
[[ -n "$boot2" ]] || die "no restarted process within 10 s of boot 1's end"
pids+=("$boot2")
t_seen2=$(python3 -c 'import time; print(int(time.time()*1000))')
echo "e2e: boot 2 pid $boot2 seen $((t_seen2 - t_exit1)) ms after boot 1 ended; its command line:"
ps -o args= -p "$boot2" | cut -c1-300
for i in $(seq 1 1800); do kill -0 "$boot2" 2>/dev/null || break; sleep 0.1; done
kill -0 "$boot2" 2>/dev/null && { cp "$ZOMBOID/console.txt" "$OUT/boot2-console.txt"; die "boot 2 did not quit within 180 s"; }
cp "$ZOMBOID/console.txt" "$OUT/boot2-console.txt"

# 4. verify and report
installed_commit=$(sed -n 's/^commit=//p' "$PZ_DIR/pzopt/build-info.properties")
echo "e2e: installed commit after the update: $installed_commit (want $new_commit)"
python3 - "$PZ_DIR" <<'EOF'
import hashlib, sys, os
d = sys.argv[1]; bad = 0; n = 0
for line in open(os.path.join(d, "pzopt-installed.txt")):
    line = line.strip()
    if not line or line.startswith("#"): continue
    rel, sha = line.split(" ", 1); n += 1
    if hashlib.sha256(open(os.path.join(d, rel), "rb").read()).hexdigest() != sha: bad += 1; print("e2e: manifest mismatch", rel)
print("e2e: updater manifest, %d files, %d mismatches" % (n, bad))
EOF
echo "--- boot 1"
grep -a -E '\[pzopt\] (update|restart)' "$OUT/boot1-console.txt" | sed 's/^.*\[pzopt\]/[pzopt]/' | cut -c1-260
echo "--- boot 2"
grep -a -E '\[pzopt\] (update|restart)|loaded override' "$OUT/boot2-console.txt" | grep -a -v 'loaded override' | sed 's/^.*\[pzopt\]/[pzopt]/' | cut -c1-260
echo "e2e: boot 2 loaded $(grep -a -c 'loaded override' "$OUT/boot2-console.txt") overrides"
[[ "$installed_commit" == "$new_commit" ]] && echo "e2e: PASS" || echo "e2e: FAIL"
