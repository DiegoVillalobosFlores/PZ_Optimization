#!/usr/bin/env bash
# Install, remove and inspect the class overrides in a Project Zomboid install.
#
#   scripts/pzopt.sh install     copy build/classes/ into the game directory
#   scripts/pzopt.sh uninstall   remove exactly the files install wrote
#   scripts/pzopt.sh reinstall   uninstall then install (after a rebuild); refuses before touching anything when nothing is built
#   scripts/pzopt.sh status      report installed/not, file list, target build
#   scripts/pzopt.sh check       run the preflight checks only
#
# The game's launcher config (ProjectZomboid64.json) puts "." ahead of
# projectzomboid.jar on the classpath, so loose .class files under the install
# directory shadow the jar's copies. The jar is never written to. What was
# written is recorded in $PZ_DIR/pzopt-installed.txt so uninstall removes
# exactly that and nothing else, even after a game update.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/pz-env.sh"
JAR="$PZ_DIR/projectzomboid.jar"
LAUNCHER_JSON="${LAUNCHER_JSON:-$PZ_DIR/ProjectZomboid64.json}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
CLASSES="$REPO/build/classes"
MANIFEST="$PZ_DIR/pzopt-installed.txt"

die() { echo "error: $*" >&2; exit 1; }

# --- checks -----------------------------------------------------------------

# The launcher must search the install directory before the jar, or the loose
# classes would never be loaded. Reads the "classpath" array from the JSON.
check_classpath() {
  [[ -f "$LAUNCHER_JSON" ]] || die "launcher config not found: $LAUNCHER_JSON"
  local cp
  cp=$(python3 -c '
import json,sys
d=json.load(open(sys.argv[1]))
print("\n".join(d.get("classpath",[])))' "$LAUNCHER_JSON") || die "could not parse $LAUNCHER_JSON"
  local dot jar n=0
  dot=""; jar=""
  while IFS= read -r e; do
    n=$((n+1))
    [[ "$e" == "." && -z "$dot" ]] && dot=$n
    [[ "$e" == "projectzomboid.jar" && -z "$jar" ]] && jar=$n
  done <<< "$cp"
  if [[ "$(head -1 <<< "$cp")" == "pzopt/aot/pzopt.jar" && -n "$jar" ]]; then
    return 0  # pzopt.AotCache's cache form: the installed classes run from pzopt/aot/pzopt.jar (install/uninstall reset it)
  fi
  if [[ -z "$dot" || -z "$jar" || "$dot" -gt "$jar" ]]; then
    echo "refusing: $LAUNCHER_JSON classpath does not put \".\" ahead of projectzomboid.jar" >&2
    echo "  found classpath: [$(echo "$cp" | paste -sd, -)]" >&2
    echo "  loose .class files would not shadow the jar, so the overrides could never load" >&2
    return 1
  fi
}

jar_revision() {
  javap -constants -cp "$JAR" zombie.GitVersion 2>/dev/null | sed -n 's/.*REVISION = "\([^"]*\)".*/\1/p'
}

built_revision() {
  sed -n 's/^revision=//p' "$CLASSES/pzopt/build-info.properties" 2>/dev/null
}

# The overrides are whole-class copies of one game revision; against another
# revision they would run stale code. Compared at install time here, and again
# at runtime by pzopt.Overrides.
check_build() {
  [[ -f "$CLASSES/pzopt/build-info.properties" ]] || die "nothing built; run scripts/build.sh first"
  local want have
  want=$(built_revision); have=$(jar_revision)
  [[ -n "$have" ]] || die "could not read zombie.GitVersion.REVISION from $JAR"
  if [[ "$want" != "$have" ]]; then
    echo "refusing: game revision is $have but build/classes was compiled for $want" >&2
    echo "  rebuild with scripts/build.sh against the current jar" >&2
    return 1
  fi
}

check() {
  [[ -f "$JAR" ]] || die "jar not found: $JAR"
  check_classpath
  check_build
  echo "ok: classpath prefers loose classes; game revision $(jar_revision) matches build"
}

# --- install / uninstall -----------------------------------------------------

# A launcher JSON in pzopt's AOT-cache form (pzopt.AotCache: the overrides from pzopt/aot/pzopt.jar, -XX:AOTCache*)
# goes back to the loose classes, and the jar and cache go: the loose files are about to change.
reset_aot() {
  if [[ -f "$LAUNCHER_JSON" ]]; then
    python3 - "$LAUNCHER_JSON" <<'PYEOF'
import json,sys
p=sys.argv[1]; j=json.load(open(p)); jar="pzopt/aot/pzopt.jar"
cp=j.get("classpath",[]); args=j.get("vmArgs",[])
aot=[a for a in args if a.startswith("-XX:AOTCache") or a.startswith("-Xlog:aot=info:file=pzopt/aot/")]
if jar in cp or aot:
    j["classpath"]=["."]+[e for e in cp if e not in (".",jar)]
    j["vmArgs"]=[a for a in args if a not in aot]
    json.dump(j,open(p,"w"),indent="\t"); print("launcher: AOT-cache form put back to the loose classes")
PYEOF
  fi
  rm -rf "$PZ_DIR/pzopt/aot"
}

reset_gc() {  # undo pzopt.GcChoice's launcher edits (-Dpzopt.gc=g1 marker: G1 back to ZGC, our pause target removed; -Dpzopt.jit=steady: our JIT flags removed)
  [[ -f "$1" ]] && command -v python3 >/dev/null || return 0
  python3 - "$1" <<'PYEOF'
import json,sys
p=sys.argv[1]; j=json.load(open(p)); ch=[False]
M,MP="-Dpzopt.gc=g1","-Dpzopt.gc=g1,pause"
def fix(a):
    if M not in a and MP not in a: return a
    if MP in a: a=[x for x in a if not x.startswith("-XX:MaxGCPauseMillis=")]
    a=[x for x in a if x not in (M,MP)]
    ch[0]=True; return ["-XX:+UseZGC" if x=="-XX:+UseG1GC" else x for x in a]
if "vmArgs" in j: j["vmArgs"]=fix(j["vmArgs"])
for v in j.values():
    if isinstance(v,dict) and "vmArgs" in v: v["vmArgs"]=fix(v["vmArgs"])
J="-Dpzopt.jit=steady"; JP=("-XX:PerMethodTrapLimit=","-XX:PerBytecodeTrapLimit=")
def fixj(a):
    if J not in a: return a
    ch[0]=True; return [x for x in a if x!=J and not x.startswith(JP)]
if "vmArgs" in j: j["vmArgs"]=fixj(j["vmArgs"])
for v in j.values():
    if isinstance(v,dict) and "vmArgs" in v: v["vmArgs"]=fixj(v["vmArgs"])
if ch[0]: json.dump(j,open(p,"w"),indent="\t"); print("launcher: pzopt's G1 switch / JIT flags undone (back to the launcher's own)")
PYEOF
}

install_overrides() {
  reset_aot
  check
  [[ -f "$MANIFEST" ]] && { echo "already installed (see: $0 status); uninstall first" >&2; exit 1; }
  local jar_before jar_after
  jar_before=$(sha256sum "$JAR" | cut -d' ' -f1)
  local tmp; tmp=$(mktemp)
  {
    echo "# files written by scripts/pzopt.sh install — do not edit"
    echo "# revision=$(built_revision) installed=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } > "$tmp"
  while IFS= read -r rel; do
    if [[ -e "$PZ_DIR/$rel" ]]; then
      rm -f "$tmp"; die "refusing to overwrite existing file: $PZ_DIR/$rel"
    fi
  done < <(cd "$CLASSES" && find . -type f | sed 's|^\./||' | sort)
  while IFS= read -r rel; do
    mkdir -p "$PZ_DIR/$(dirname "$rel")"
    cp "$CLASSES/$rel" "$PZ_DIR/$rel"
    echo "$rel $(sha256sum "$PZ_DIR/$rel" | cut -d' ' -f1)" >> "$tmp"
    echo "wrote $rel"
  done < <(cd "$CLASSES" && find . -type f | sed 's|^\./||' | sort)
  mv "$tmp" "$MANIFEST"
  jar_after=$(sha256sum "$JAR" | cut -d' ' -f1)
  [[ "$jar_before" == "$jar_after" ]] || die "projectzomboid.jar checksum changed during install (this should be impossible)"
  echo "installed $(grep -vc '^#' "$MANIFEST") files; projectzomboid.jar untouched (sha256 $jar_after)"
}

uninstall_overrides() {
  reset_aot
  reset_gc "$PZ_DIR/ProjectZomboid64.json"
  [[ -f "$MANIFEST" ]] || { echo "not installed (no $MANIFEST)"; return 0; }
  local n=0
  while read -r rel sha; do
    [[ "$rel" == \#* || -z "$rel" ]] && continue
    if [[ -f "$PZ_DIR/$rel" ]]; then
      rm -f "$PZ_DIR/$rel"; n=$((n+1)); echo "removed $rel"
    else
      echo "already gone: $rel"
    fi
    # remove now-empty package directories we created, but never the install dir itself
    local d; d=$(dirname "$rel")
    while [[ "$d" != "." && -d "$PZ_DIR/$d" ]] && [[ -z "$(ls -A "$PZ_DIR/$d")" ]]; do
      rmdir "$PZ_DIR/$d"; d=$(dirname "$d")
    done
  done < "$MANIFEST"
  rm -f "$MANIFEST"
  echo "removed $n files and the manifest; projectzomboid.jar was never modified"
}

status() {
  echo "game dir:        $PZ_DIR"
  echo "game revision:   $(jar_revision || echo unknown)"
  if [[ -f "$CLASSES/pzopt/build-info.properties" ]]; then
    echo "built for:       $(built_revision) (build/classes)"
  else
    echo "built for:       (nothing built)"
  fi
  if [[ ! -f "$MANIFEST" ]]; then
    echo "installed:       no"
    return 0
  fi
  echo "installed:       yes"
  echo "installed for:   $(sed -n 's/^# revision=\([^ ]*\).*/\1/p' "$MANIFEST")"
  echo "files:"
  while read -r rel sha; do
    [[ "$rel" == \#* || -z "$rel" ]] && continue
    if [[ ! -f "$PZ_DIR/$rel" ]]; then
      echo "  $rel  MISSING"
    elif [[ "$(sha256sum "$PZ_DIR/$rel" | cut -d' ' -f1)" != "$sha" ]]; then
      echo "  $rel  MODIFIED since install"
    else
      echo "  $rel"
    fi
  done < "$MANIFEST"
  if [[ -f "$PZ_DIR/pzopt/build-info.properties" ]]; then
    echo "settings (pzopt/build-info.properties + pzopt.properties if present):"
    [[ -f "$PZ_DIR/pzopt.properties" ]] && sed 's/^/  /' "$PZ_DIR/pzopt.properties" || echo "  (no pzopt.properties; defaults apply)"
  fi
}

case "${1:-}" in
  install)   install_overrides ;;
  uninstall) uninstall_overrides ;;
  reinstall) reset_aot; check >/dev/null; uninstall_overrides; install_overrides ;;  # check first: a failed check must leave the old install in place
  status)    status ;;
  check)     check ;;
  *) echo "usage: $0 install|uninstall|reinstall|status|check" >&2; exit 2 ;;
esac
