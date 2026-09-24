#!/usr/bin/env bash
# harness/queue.sh - the run queue for every session and every computer (2026-09-21).
#
# The game directories, displays and Steam clients of the desktop and the three laptops are shared
# resources; the queue serialises every use of them per machine and routes each session's jobs to
# the machine that session is bound to. A session submits a job and reads the job's result.txt (or is
# woken by `watch`) instead of pgrep-ing, messaging peers and running ssh wrappers by hand.
#
#   Every submit carries the session's context for Jev (required):
#     --name "<session name, once per session; the ListAgents name>"  --intent "<why this job, what it decides>"
#     --progress "<where the session's task stands: 3/5 A/B runs, last check before a commit, ...>"
#   harness/queue.sh submit run      [--machine desktop|flip|dell|mac] [--install opt|stock|keep|<repo>]
#                                    [--goal "<what the change should do>"] [--against <run|baseline.json>]...
#                                    [--parity-against <recorded run>] [--cap 240] [--size <secs>] [--wait] -- <harness/run.sh args>
#   harness/queue.sh submit mp       [--goal ...] [--against ...] [--wait] -- <label> [stock]   # desktop only
#   harness/queue.sh submit workshop [--wait] --notes "<change notes>" -- --tag win-<rev>-<commit> | --zip <zip>   # desktop only
#   harness/queue.sh submit cmd      [--install ...] [--wait] --label <name> -- <command>          # desktop only
#   harness/queue.sh submit media    [--out <file>]... [--wait] --label <name> -- <encode / stitch command>   # desktop only
#   harness/queue.sh session [--name N] [--intent T] [--progress T]   # this session's context (Jev reads it); no args = show
#   harness/queue.sh suggest --intent "..." [--resource r1,r2] [-- <run.sh args>]   # Jev's bench for an intent (+ fit of your args)
#   harness/queue.sh resources                 # the resource vocabulary of harness/queue/benches.json
#   A `run` submit must name the resources it tests (--resource gpu,render-thread; `resources` lists them) and may take
#   its arguments from the catalog: --bench <name> (harness/queue/benches.json) or --bench auto (Jev picks from the
#   intent + resources); the arguments after -- are appended (--label, A/B --prop keys, --record ...). A run with its
#   own arguments gets Jev's suggestion printed beside it (fits= / matches_suggestion=), never overridden.
#   harness/queue.sh list                      # every job (machine, status, size estimate, verdict) and the workers
#   harness/queue.sh machines                  # connection state of every machine, queue depth, bound sessions
#   harness/queue.sh next [machine]            # Jev's current plan for a machine: order, estimates, ETAs (one Jev request)
#   harness/queue.sh bind <machine> | unbind   # this session's affinity (submit binds on first use, default desktop)
#   harness/queue.sh watch [--exit-on disconnect|job|any]   # this session's events as they happen (blocking)
#   harness/queue.sh events [N]                # the last N events of this session
#   harness/queue.sh status|wait|result|log [-f]|cancel <id|label>
#   harness/queue.sh start [machine...] | stop [--now] | worker --machine <m> | monitor
#
# Session affinity: the session id is $PZQ_SESSION, else $CLAUDE_CODE_SESSION_ID (every Claude Code tool
# shell has it), else user+parent pid. The first submit (or `bind`) records sessions/<sid>/machine and every
# later submit of that session goes to the same machine; `--machine` for another one is refused unless
# `--rebind`. mp, workshop, cmd and media jobs always run on the desktop (the stock server, the Steam upload,
# the recordings and NVENC are here).
#
# Order (2026-09-22 evening): Jev is the only sorter. Whenever a worker is free it hands every pending job of
# its machine to harness/queue-jev.py, which asks Jev (TypeSafe) one choice question over the facts: each job's
# intent, the session's task progress, name and age (from its Claude Code transcript), how long the job and the
# session have waited, the job's size estimate and where it came from, its place in the session's batch, what
# is running now. Jev's probabilities over the candidates are the whole order; the pick runs, the order is the
# plan (machines/<m>/plan, `next`), with an ETA per job from the estimates. No tier or FIFO rule in code: the
# policy (fairness between sessions, short jobs first when equal, wrap-up / media / release work unblocked,
# no starvation) is text Jev weighs. When Jev cannot be asked the pending jobs show `blocked: waiting for Jev`
# and the worker retries every 30 s; PZQ_JEV_FALLBACK=fifo lets it take the oldest instead.
# Every job gets a size estimate in seconds at submit time: `--size <secs>` when given, else the median
# duration of the finished jobs with the same signature (kind + arguments without --label / --prop /
# --option / --env, i.e. the same route, preset, mode and recording flags; the duration counted from the
# launch, not from the blocked wait), else a default from the arguments (run: ~40 s launch + analysis + the
# route / quit-after seconds; mp 240; workshop 90; cmd 60; media 120).
# Job start: a desktop notification (notify-send) with the label, the session name, the estimate and the intent.
# Overrun: a job still running past its estimate (counted from the launch) sends its session an event
# (`overrun: job ...`, wakes `watch --exit-on any|overrun`) and a notification, then again every further
# estimate (at least 5 min) while it runs; result.txt records `overran=`.
#
# Media jobs (encode-av1-hdr.sh, the stitch scripts, ffmpeg): same desktop queue as the runs, so an encode
# can never overlap a benchmark run; they are tier 1 and, like runs, wait for any game, run.sh or ffmpeg /
# gpu-screen-recorder started outside the queue. The result probes every output (--out, or the video / image paths in the command) with ffprobe and
# flags a video that is not AV1 10-bit PQ/BT.2020 (the publishing rule).
#
# Machines (harness/queue/machines.conf): one worker unit per machine (pzq-<m>) runs that machine's jobs
# in that order. Remote jobs: rsync harness/ (+ build/classes with --install opt) to the machine's checkout, a
# generated wrapper exports the desktop session's display env (plasmashell's environ), unlocks / inhibits
# sleep and shuts Steam down where the conf says so, runs harness/run.sh (or run-mac.sh) in the ssh
# foreground, the run dir is rsync'd back to harness/runs/<m>-<label>-<ts>/ and analysed + judged here.
#
# Connection monitor (unit pzq-monitor): a persistent ssh master per remote machine (ControlMaster,
# ServerAliveInterval 5 s x 2) checked every 5 s; a transition is written to machines/<m>/state, to
# events.log, to the events file of every session bound to that machine and of every session with a job
# queued there, to `notify-send`, and the machine's pending jobs show `blocked: <m> disconnected`. A
# running job whose connection drops fails with exit 70 and its session is told.
#
# Layout of $PZQ_DIR (default ~/.local/state/pzopt-queue):
#   jobs/<id>-<label>/  job (kind, label, machine, session, session_name, intent, progress, cwd, install, goal, against, size, size_from, sig, ...),
#                       argv (NUL-separated), status (pending|running|done|failed|cancelled), blocked (reason,
#                       while waiting), pid, output.log, took (s, whole job), ran (s, from the launch: the history
#                       for the size estimates), result.txt (exit, run_dir, analyze.py, verdict= from Jev, parity=, ...), failure.png
#   machines/<m>/       state (connected|disconnected|unknown), since, ssh.sock (the master), plan (Jev's last order)
#   sessions/<sid>/     machine (affinity), name, intent, progress, progress_at, first_seen,
#                       events (append-only: job done/failed/overrun, machine connect/disconnect)
#   events.log          every event; worker-<m>.pid / .log, monitor.pid / .log
set -uo pipefail

Q="${PZQ_DIR:-$HOME/.local/state/pzopt-queue}"
JOBS="$Q/jobs"
mkdir -p "$JOBS" "$Q/machines" "$Q/sessions"
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MACHINES_CONF="${PZQ_MACHINES:-$REPO/harness/queue/machines.conf}"
STEAM_LOGS="$HOME/.local/share/Steam/logs"
WORKSHOP_ID=3805285544
ZOMBOID_DIR="${ZOMBOID:-$HOME/Zomboid}"
WS_DIR="$ZOMBOID_DIR/Workshop/PZ_Optimization"
# anchored on argv[0]/argv[1]: the game binary itself and a shell executing one of the run scripts, never a
# tool shell whose command text merely mentions them (a peer's `grep harness/run.sh` must not block the queue)
GAME_PATTERN='^([^ ]*/)?ProjectZomboid64( |$)'
BUSY_PATTERN='^(([^ ]*/)?(bash|sh|zsh) )?([^ ]*/)?harness/(mp/run|run|showcase-record)\.sh( |$)'
ENCODE_PATTERN='^([^ ]*/)?(ffmpeg|gpu-screen-recorder|av1an|x265|SvtAv1EncApp)( |$)'   # an encode outside the queue skews a run, a run skews an encode's time
MONITOR_PERIOD=${PZQ_MONITOR_PERIOD:-5}
JEV_FALLBACK=${PZQ_JEV_FALLBACK:-block}   # Jev unreachable: block (retry every 30 s) or fifo (oldest pending job)
OVERRUN_REPEAT_MIN=300                     # an overrunning job re-notifies every max(estimate, this) seconds

die() { echo "queue: $*" >&2; exit 2; }
ts() { date '+%Y-%m-%d %H:%M:%S'; }
say() { echo "[queue $(date '+%H:%M:%S')] $*"; }

# --- job files ---------------------------------------------------------------------------------

jget() { sed -n "s/^$2=//p" "$1/job" 2>/dev/null | head -1; }
status_of() { cat "$1/status" 2>/dev/null || echo unknown; }
set_status() { printf '%s\n' "$2" > "$1/status.tmp" && mv -f "$1/status.tmp" "$1/status"; }
job_id() { basename "$1" | cut -d- -f1; }

find_job() { # by id (0012, 12) or label (newest match)
  local key="$1" d
  if [[ "$key" =~ ^[0-9]+$ ]]; then d=$(ls -d "$JOBS"/$(printf '%04d' "$((10#$key))")-* 2>/dev/null | head -1)
  else d=$(ls -dt "$JOBS"/*-"$key" 2>/dev/null | head -1); fi
  [[ -n "$d" ]] || die "no job $key (harness/queue.sh list)"
  echo "$d"
}

next_id() {
  local n
  exec 8>"$Q/id.lock"; flock 8
  n=$(cat "$Q/next-id" 2>/dev/null || echo 1); echo $((n + 1)) > "$Q/next-id"
  flock -u 8; printf '%04d' "$n"
}

# --- job size (seconds): the ordering key ----------------------------------------------------------

argv_of() { mapfile -d '' ARGV < "$1/argv" 2>/dev/null || ARGV=(); }   # sets ARGV

job_sig() { # <job dir>: kind + the arguments that decide the duration (no label, no props / options / env)
  local d="$1" kind sig="" i; kind=$(jget "$d" kind); argv_of "$d"
  case "$kind" in
    run|cmd|media|mp)
      for ((i = 0; i < ${#ARGV[@]}; i++)); do
        case "${ARGV[i]}" in --label|--prop|--option|--env|--vmarg|--out) i=$((i+1)); continue ;; esac
        sig+="${ARGV[i]} "
      done ;;
  esac
  printf '%s|%s' "$kind" "$sig" | md5sum | cut -c1-12
}

default_size() { # <job dir>: an estimate from the arguments alone
  local d="$1" kind i route="" quit="" mode="" preset="" secs; kind=$(jget "$d" kind); argv_of "$d"
  case "$kind" in
    run)
      for ((i = 0; i < ${#ARGV[@]}; i++)); do
        case "${ARGV[i]}" in
          --route-seconds) route="${ARGV[i+1]:-}" ;; --quit-after) quit="${ARGV[i+1]:-}" ;;
          --mode) mode="${ARGV[i+1]:-}" ;; --preset) preset="${ARGV[i+1]:-}" ;;
        esac
      done
      if [[ -n "$route" ]]; then secs=$route
      elif [[ -n "$quit" ]]; then secs=$quit
      elif [[ -n "$preset" ]]; then secs=45     # preset = bench on the spinning route (25 s) + settle / population
      else case "$mode" in drive) secs=90 ;; bench) secs=100 ;; *) secs=60 ;; esac; fi   # run.sh's route_seconds defaults; verify quits on its own
      echo $(( 40 + secs )) ;;                     # launch → world, quit, analyze.py + Jev
    mp) echo 240 ;;
    workshop) echo 20 ;;
    cmd) echo 60 ;;
    media) echo 120 ;;
    *) echo 120 ;;
  esac
}

history_size() { # <job dir>: median duration (ran, else took) of the finished jobs with the same signature
  local d="$1" sig o vals; sig=$(jget "$d" sig); [[ -n "$sig" ]] || sig=$(job_sig "$d")
  vals=$(for o in "$JOBS"/*/; do o=${o%/}
    [[ "$o" != "$d" && -f "$o/job" && "$(status_of "$o")" == done ]] || continue
    [[ "$(jget "$o" sig)" == "$sig" ]] || { [[ -z "$(jget "$o" sig)" && "$(job_sig "$o")" == "$sig" ]] || continue; }
    cat "$o/ran" 2>/dev/null || cat "$o/took" 2>/dev/null; done | sort -n)
  [[ -n "$vals" ]] || return 1
  awk '{ a[NR] = $1 } END { if (NR % 2) print a[(NR + 1) / 2]; else print int((a[NR / 2] + a[NR / 2 + 1]) / 2) }' <<<"$vals"
}

estimate_size() { # <job dir>: prints "<seconds> <source>" (arg, history, default)
  local d="$1" s
  s=$(jget "$d" size); [[ -n "$s" && "$(jget "$d" size_from)" == arg ]] && { echo "$s arg"; return; }
  if s=$(history_size "$d"); then echo "$s history"; else echo "$(default_size "$d") default"; fi
}

job_size() { local s; s=$(jget "$1" size); [[ -n "$s" ]] || s=$(default_size "$1"); echo "$s"; }   # jobs queued before the size field: a default

pending_on() { # <machine>: the pending job dirs there, id order
  local d; for d in "$JOBS"/*/; do d=${d%/}
    [[ -f "$d/job" && "$(status_of "$d")" == pending && "$(jget "$d" machine)" == "$1" ]] && echo "$d"
  done
}

# Jev's order of a machine's pending jobs: "<dir>\t<p>\t<eta s>" lines, pick first; also writes machines/<m>/plan.
# Exit 1 = nothing pending, 3 = Jev could not be asked.
jev_py() { (cd "$REPO" && timeout 90 python3 harness/queue-jev.py "$@" 2>>"$Q/jev.log"); }
jev_rank() { (cd "$REPO" && timeout 120 python3 harness/queue-jev.py rank --q "$Q" --machine "$1" 2>>"$Q/jev.log"); }

next_pending() { # <machine>: the job Jev picks (fallback per PZQ_JEV_FALLBACK); 1 = none, 3 = Jev unreachable and blocking
  local m="$1" out rc d
  out=$(jev_rank "$m"); rc=$?
  if (( rc == 0 )); then
    for d in $(cut -f1 <<<"$out"); do grep -q '^waiting for Jev' "$d/blocked" 2>/dev/null && rm -f "$d/blocked"; done
    head -1 <<<"$out" | cut -f1; return 0
  fi
  (( rc == 1 )) && return 1
  [[ -n "$(pending_on "$m")" ]] || return 1
  if [[ "$JEV_FALLBACK" == fifo ]]; then say "Jev unreachable (rc $rc, $Q/jev.log); fifo fallback" >&2; pending_on "$m" | head -1; return 0; fi
  while read -r d; do [[ -n "$d" ]] && blocked_note "$d" "waiting for Jev to order the queue (unreachable: $Q/jev.log; PZQ_JEV_FALLBACK=fifo overrides)" >&2; done <<<"$(pending_on "$m")"
  return 3
}

# --- sessions and events ---------------------------------------------------------------------------

session_id() { printf '%s' "${PZQ_SESSION:-${CLAUDE_CODE_SESSION_ID:-$USER-pid$PPID}}" | tr -c 'A-Za-z0-9._-' '_'; }
session_machine() { cat "$Q/sessions/$1/machine" 2>/dev/null; }
session_name() { cat "$Q/sessions/$1/name" 2>/dev/null || printf '%s' "${1:0:8}"; }
session_set() { # <sid> <key> <value>: name / intent / progress (progress also stamps progress_at)
  mkdir -p "$Q/sessions/$1"; [[ -f "$Q/sessions/$1/first_seen" ]] || ts > "$Q/sessions/$1/first_seen"
  printf '%s\n' "$3" > "$Q/sessions/$1/$2.tmp" && mv -f "$Q/sessions/$1/$2.tmp" "$Q/sessions/$1/$2"
  [[ "$2" == progress ]] && ts > "$Q/sessions/$1/progress_at"; return 0
}
oneline() { printf '%s' "$1" | tr '\n\r' '  '; }   # job-file values are one line each
notify_session() { # <sid> <event line>
  mkdir -p "$Q/sessions/$1"
  echo "$(ts) $2" >> "$Q/sessions/$1/events"
}
event() { # <machine> <line>: every session bound to it or with a job queued there, the global log, the desktop
  local m="$1" line="$2" s d sids=""
  echo "$(ts) $m: $line" >> "$Q/events.log"
  for s in "$Q/sessions"/*/; do s=${s%/}; [[ -d "$s" ]] || continue; [[ "$(session_machine "$(basename "$s")")" == "$m" ]] && sids+=" $(basename "$s")"; done
  for d in "$JOBS"/*/; do d=${d%/}; [[ -f "$d/job" ]] || continue
    [[ "$(jget "$d" machine)" == "$m" && "$(status_of "$d")" =~ ^(pending|running)$ ]] && sids+=" $(jget "$d" session)"; done
  for s in $(tr ' ' '\n' <<<"$sids" | sort -u); do [[ -n "$s" ]] && notify_session "$s" "$m: $line"; done
  command -v notify-send >/dev/null && notify-send -a pzopt-queue "pzopt queue: $m" "$line" >/dev/null 2>&1 || true
}

# --- machines ----------------------------------------------------------------------------------------

machines() { awk '/^\[/{gsub(/[][]/, ""); print}' "$MACHINES_CONF"; }
mcfg() { # <machine> <key> [default]
  local v; v=$(awk -v m="$1" -v k="$2" '/^\[/{s=($0=="["m"]")} s && index($0,k"=")==1 {print substr($0,length(k)+2); exit}' "$MACHINES_CONF")
  printf '%s' "${v:-${3:-}}"
}
is_machine() { machines | grep -qx "$1"; }
is_local() { [[ "$(mcfg "$1" local)" == true ]]; }
mstate() { cat "$Q/machines/$1/state" 2>/dev/null || echo unknown; }
set_mstate() { mkdir -p "$Q/machines/$1"; printf '%s\n' "$2" > "$Q/machines/$1/state.tmp" && mv -f "$Q/machines/$1/state.tmp" "$Q/machines/$1/state"; ts > "$Q/machines/$1/since"; }
remote_path() { local p; p=$(mcfg "$1" "$2"); printf '%s' "${p#\~/}"; }   # relative to the remote home (rsync, ls)

ssh_args() { # sets SSH_ARGS for <machine>: the shared master socket; the keepalive is the heartbeat
  local m="$1" key; key=$(mcfg "$m" key); key="${key/#\~/$HOME}"
  mkdir -p "$Q/machines/$m"
  SSH_ARGS=(-i "$key" -p "$(mcfg "$m" port 22)" -o BatchMode=yes -o ConnectTimeout=8 -o StrictHostKeyChecking=accept-new
            -o ControlMaster=auto -o ControlPath="$Q/machines/$m/ssh.sock" -o ControlPersist=yes
            -o ServerAliveInterval=5 -o ServerAliveCountMax=2)
}
mssh() { local m="$1"; shift; ssh_args "$m"; ssh "${SSH_ARGS[@]}" "$(mcfg "$m" host)" "$@"; }
mrsync() { local m="$1"; shift; ssh_args "$m"; rsync -az -e "ssh ${SSH_ARGS[*]}" "$@"; }
probe() { local m="$1"; mssh "$m" -O check >/dev/null 2>&1 || timeout 20 ssh "${SSH_ARGS[@]}" "$(mcfg "$m" host)" true >/dev/null 2>&1; }   # master alive, else a fresh connection (which becomes the master)
connected() { is_local "$1" || [[ "$(mstate "$1")" == connected ]]; }

# --- units (workers, monitor) ------------------------------------------------------------------------

alive() { # <pidfile> <cmdline words>
  local p; p=$(cat "$1" 2>/dev/null) || return 1
  [[ -n "$p" ]] && kill -0 "$p" 2>/dev/null && tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null | grep -q "$2"
}
worker_alive() { alive "$Q/worker-$1.pid" "queue.sh worker --machine $1"; }
monitor_alive() { alive "$Q/monitor.pid" "queue.sh monitor"; }

start_unit() { # <unit> <pidfile> <log> <queue.sh args...>: transient user unit with the desktop env, else setsid
  local unit="$1" pidfile="$2" log="$3"; shift 3
  local envs=() v i how=setsid q
  for v in DISPLAY WAYLAND_DISPLAY XAUTHORITY DBUS_SESSION_BUS_ADDRESS XDG_RUNTIME_DIR XDG_SESSION_TYPE XDG_CURRENT_DESKTOP PATH HOME USER LOGNAME LANG PZ_DIR ZOMBOID PZQ_DIR PZQ_MACHINES PZQ_MONITOR_PERIOD TYPESAFE_API_KEY SSH_AUTH_SOCK; do
    [[ -n "${!v:-}" ]] && envs+=(-E "$v=${!v}")
  done
  printf -v q '%q ' "$SELF" "$@"
  if command -v systemd-run >/dev/null && [[ "$(systemctl --user is-system-running 2>/dev/null)" =~ ^(running|degraded)$ ]]; then
    systemctl --user reset-failed "$unit.service" 2>/dev/null
    systemd-run --user --unit="$unit" --collect -p WorkingDirectory="$REPO" "${envs[@]}" bash -c "exec $q>> '$log' 2>&1" >/dev/null 2>&1 && how="unit $unit"
  fi
  [[ "$how" == setsid ]] && setsid -f "$SELF" "$@" >> "$log" 2>&1
  for i in 1 2 3 4 5 6 7 8 9 10; do alive "$pidfile" "queue.sh" && break; sleep 0.5; done
  if alive "$pidfile" "queue.sh"; then say "$unit started ($how, pid $(cat "$pidfile"), log $log)"; else say "$unit did not start; see $log" >&2; return 1; fi
}
start_worker() { local m="$1"; worker_alive "$m" && return 0; rm -f "$Q/stop"; start_unit "pzq-$m" "$Q/worker-$m.pid" "$Q/worker-$m.log" worker --machine "$m"; }
start_monitor() { monitor_alive && return 0; rm -f "$Q/stop-monitor"; start_unit pzq-monitor "$Q/monitor.pid" "$Q/monitor.log" monitor; }

start_cmd() {
  local m; start_monitor
  if [[ $# -gt 0 ]]; then for m in "$@"; do is_machine "$m" || die "no machine $m in $MACHINES_CONF"; start_worker "$m"; done
  else start_worker desktop; fi
}

stop_cmd() {
  local now="${1:-}" m d pid
  if [[ "$now" == --now ]]; then
    for d in "$JOBS"/*/; do d=${d%/}; [[ -f "$d/job" && "$(status_of "$d")" == running && -f "$d/pid" ]] || continue
      touch "$d/cancel"; kill -TERM -- "-$(cat "$d/pid")" 2>/dev/null; done
    sleep 1
  else
    touch "$Q/stop"
  fi
  touch "$Q/stop-monitor"
  for m in $(machines); do
    worker_alive "$m" || continue; pid=$(cat "$Q/worker-$m.pid")
    if [[ "$now" == --now ]]; then kill -TERM "$pid" 2>/dev/null; systemctl --user stop "pzq-$m.service" 2>/dev/null; echo "worker $m ($pid) stopped"
    else echo "worker $m ($pid) stops after its current job"; fi
  done
  if monitor_alive; then pid=$(cat "$Q/monitor.pid"); kill -TERM "$pid" 2>/dev/null; systemctl --user stop pzq-monitor.service 2>/dev/null; rm -f "$Q/stop-monitor"; echo "monitor ($pid) stopped"; fi
  for m in $(machines); do is_local "$m" || { ssh_args "$m"; ssh "${SSH_ARGS[@]}" -O exit "$(mcfg "$m" host)" >/dev/null 2>&1; }; done
  if [[ "$now" == --now ]]; then
    sleep 1
    for d in "$JOBS"/*/; do d=${d%/}; [[ -f "$d/job" && "$(status_of "$d")" == running ]] || continue
      set_status "$d" "$( [[ -f "$d/cancel" ]] && echo cancelled || echo pending)"; rm -f "$d/pid" "$d/cancel"; done
    for m in $(machines); do rm -f "$Q/worker-$m.pid"; done; rm -f "$Q/monitor.pid"
  fi
}

# --- submit ------------------------------------------------------------------------------------------

sanitize() { printf '%s' "$1" | tr -c 'A-Za-z0-9._-' '_'; }

submit() {
  local kind="${1:-}"; shift || true
  [[ "$kind" =~ ^(run|mp|workshop|cmd|media)$ ]] || die "submit run|mp|workshop|cmd|media [options] -- <arguments>"
  local install=keep wait=0 start=1 notes="" label="" goal="" against=() parity_against="" cap="" machine="" rebind=0 outs=() size=""
  local sname="${PZQ_SESSION_NAME:-}" intent="" progress="" resource="" bench="" sugg=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --name) sname="$2"; shift 2 ;;
      --intent) intent="$2"; shift 2 ;;
      --progress) progress="$2"; shift 2 ;;
      --machine) machine="$2"; shift 2 ;;
      --rebind) rebind=1; shift ;;
      --install) install="$2"; shift 2 ;;
      --goal) goal="$2"; shift 2 ;;
      --against) against+=("$2"); shift 2 ;;
      --parity-against) parity_against="$2"; shift 2 ;;
      --cap) cap="$2"; shift 2 ;;
      --size) size="$2"; [[ "$size" =~ ^[0-9]+$ && "$size" -gt 0 ]] || die "--size takes the expected duration in seconds"; shift 2 ;;
      --wait) wait=1; shift ;;
      --no-start) start=0; shift ;;
      --notes) notes="$2"; shift 2 ;;
      --label) label="$2"; shift 2 ;;
      --out) outs+=("$2"); shift 2 ;;
      --resource) resource="$2"; shift 2 ;;
      --bench) bench="$2"; shift 2 ;;
      --) shift; break ;;
      *) die "unknown submit option $1 (the job's own arguments go after --)" ;;
    esac
  done
  local argv=("$@") i sid bound
  sid=$(session_id); bound=$(session_machine "$sid")
  # Jev orders the queue from what the sessions tell it: refuse a job without it
  local missing=()
  [[ -n "$sname" || -f "$Q/sessions/$sid/name" ]] || missing+=('--name "<this session'"'"'s ListAgents name>" (once per session)')
  [[ -n "$intent" ]] || missing+=('--intent "<why this job, what it decides>"')
  [[ -n "$progress" ]] || missing+=('--progress "<where your task stands, e.g. 3/5 A/B runs, last check before a commit>"')
  (( ${#missing[@]} == 0 )) || die "Jev orders the queue from each session's context; add $(printf '%s, ' "${missing[@]}" | sed 's/, $//') before --"
  [[ -n "$sname" ]] && session_set "$sid" name "$(oneline "$sname")"
  session_set "$sid" intent "$(oneline "$intent")"; session_set "$sid" progress "$(oneline "$progress")"
  # affinity: mp / workshop / cmd are desktop-only; otherwise the session's machine, bound on first use
  if [[ "$kind" != run ]]; then
    [[ -z "$machine" || "$machine" == desktop ]] || die "$kind jobs run on the desktop only (stock server, Steam upload, recordings and NVENC, local commands)"
    machine=desktop
  else
    if [[ -n "$machine" ]]; then
      is_machine "$machine" || die "no machine $machine (harness/queue.sh machines)"
      if [[ -n "$bound" && "$bound" != "$machine" && $rebind == 0 ]]; then
        die "session $sid is bound to $bound; its runs go there (--rebind with --machine $machine moves the session)"
      fi
    else
      machine="${bound:-desktop}"
    fi
    if [[ "$bound" != "$machine" ]]; then mkdir -p "$Q/sessions/$sid"; echo "$machine" > "$Q/sessions/$sid/machine"; echo "session $sid bound to $machine"; fi
  fi
  case "$kind" in
    run)
      [[ -n "$resource" ]] || die "a run must name the resources it tests: --resource <r1,r2> (one of: $(jev_py resources | awk '{print $1}' | tr '\n' ' '))"
      local rbad; rbad=$(tr ',' '\n' <<<"$resource" | while read -r r; do [[ -z "$r" ]] || jev_py resources | awk '{print $1}' | grep -qx "$r" || echo "$r"; done)
      [[ -z "$rbad" ]] || die "unknown resource(s): $(echo $rbad) (harness/queue.sh resources)"
      if [[ -n "$bench" ]]; then   # the catalog's arguments first, the session's after them (later options win in run.sh)
        local bname="$bench" bargs
        if [[ "$bench" == auto ]]; then
          sugg=$(jev_py suggest --intent "$intent" --resource "$resource") || die "Jev could not suggest a bench: $(sed -n 's/^error=//p' <<<"$sugg")"
          bname=$(sed -n 's/^bench=//p' <<<"$sugg")
          [[ "$bname" != none && -n "$bname" ]] || die "Jev finds no standard bench for this intent and these resources (alternatives: $(sed -n 's/^alternatives=//p' <<<"$sugg")); give the run.sh arguments yourself"
          echo "Jev picked bench $bname ($(sed -n 's/^confidence=//p' <<<"$sugg")) for: $intent"
        fi
        bargs=$(python3 -c 'import json,sys; b={x["name"]:x for x in json.load(open(sys.argv[1]))["benches"]}; print(b[sys.argv[2]]["args"]) if sys.argv[2] in b else sys.exit(1)' "$REPO/harness/queue/benches.json" "$bname") \
          || die "no bench $bname in harness/queue/benches.json (harness/queue.sh suggest, or --bench auto)"
        read -r -a barr <<<"$bargs"; argv=("${barr[@]}" "${argv[@]}"); bench="$bname"
      else
        [[ ${#argv[@]} -gt 0 ]] || die "run needs the harness/run.sh arguments after -- (or --bench <name>|auto)"
        sugg=$(jev_py suggest --intent "$intent" --resource "$resource" --args "${argv[*]}") || sugg=""
      fi
      for ((i = 0; i < ${#argv[@]}; i++)); do [[ "${argv[i]}" == --label ]] && label="${argv[i+1]:-}"; done
      [[ -n "$label" ]] || die "run.sh needs --label"
      [[ -x "$REPO/harness/run.sh" ]] || die "no harness/run.sh in $REPO" ;;
    mp)
      label="${argv[0]:-}"; [[ -n "$label" ]] || die "mp needs <label> [stock] after --"
      [[ -x "$REPO/harness/mp/run.sh" ]] || die "no harness/mp/run.sh in $REPO" ;;
    workshop)
      [[ -n "$notes" ]] || die "workshop needs --notes \"Release <commit> (game revision <rev>). ...\""
      [[ ${#argv[@]} -gt 0 ]] || die "workshop needs --tag <release tag> or --zip <zip> after --"
      [[ -x "$REPO/scripts/workshop.sh" ]] || die "no scripts/workshop.sh in $REPO"
      for ((i = 0; i < ${#argv[@]}; i++)); do
        case "${argv[i]}" in --tag) label="workshop-${argv[i+1]:-}" ;; --zip) [[ -n "$label" ]] || label="workshop-$(basename "${argv[i+1]:-zip}" .zip)" ;; esac
      done
      [[ -n "$label" ]] || label="workshop" ;;
    cmd)
      [[ -n "$label" ]] || die "cmd needs --label <name>"
      [[ ${#argv[@]} -gt 0 ]] || die "cmd needs the command after --" ;;
    media)
      [[ -n "$label" ]] || die "media needs --label <name>"
      [[ ${#argv[@]} -gt 0 ]] || die "media needs the encode / stitch command after -- (harness/encode-av1-hdr.sh in out [width], harness/stitch-*.sh, ffmpeg ...)"
      [[ "$install" == keep ]] || die "--install does not apply to a media job"
      if [[ ${#outs[@]} -eq 0 ]]; then   # outputs named in the command itself (existing inputs are skipped at result time by mtime)
        for a in "${argv[@]}"; do [[ "$a" =~ \.(mp4|mkv|webm|mov|gif|jpg|jpeg|png|svg)$ ]] && outs+=("$a"); done
      fi ;;
  esac
  case "$install" in
    opt|stock|keep) ;;
    *) [[ -x "$install/scripts/pzopt.sh" ]] || die "--install $install: opt, stock, keep or a checkout with scripts/pzopt.sh" ;;
  esac
  if ! is_local "$machine"; then
    [[ "$install" =~ ^(opt|keep)$ ]] || die "--install $install is not available on $machine (opt = rsync build/classes + install there, keep)"
  fi
  label=$(sanitize "$label")
  local id dir
  id=$(next_id); dir="$JOBS/$id-$label"; mkdir -p "$dir"
  printf '%s\0' "${argv[@]}" > "$dir/argv"
  {
    echo "id=$id"; echo "kind=$kind"; echo "label=$label"; echo "machine=$machine"; echo "session=$sid"
    echo "session_name=$(session_name "$sid")"; echo "intent=$(oneline "$intent")"; echo "progress=$(oneline "$progress")"
    echo "cwd=$REPO"; echo "submitter=${PZQ_SESSION:-$USER pid $PPID}"; echo "submitted=$(ts)"
    echo "install=$install"; echo "notes=$(oneline "$notes")"; echo "goal=$(oneline "$goal")"; echo "against=${against[*]:-}"
    echo "parity_against=$parity_against"; echo "cap=$cap"; echo "outs=${outs[*]:-}"; echo "args=$(oneline "${argv[*]}")"
    echo "resource=$resource"; echo "bench=$bench"
    [[ -n "$sugg" ]] && echo "suggested=$(sed -n 's/^bench=//p' <<<"$sugg") fits=$(sed -n 's/^fits=//p' <<<"$sugg") matches=$(sed -n 's/^matches_suggestion=//p' <<<"$sugg")"
    echo "sig=$(job_sig "$dir")"
    if [[ -n "$size" ]]; then echo "size=$size"; echo "size_from=arg"; fi
  } > "$dir/job"
  if [[ -z "$size" ]]; then   # after sig= is on disk: the history lookup matches on it
    local est; est=$(estimate_size "$dir"); size=${est% *}
    { echo "size=$size"; echo "size_from=${est#* }"; } >> "$dir/job"
  fi
  set_status "$dir" pending
  echo "job $id queued on $machine: $kind $label (session $(session_name "$sid"), ~$size s estimate from $(jget "$dir" size_from); --size <secs> corrects it)"
  if [[ "$kind" == run && -z "$bench" && -n "$sugg" ]]; then
    local sb; sb=$(sed -n 's/^bench=//p' <<<"$sugg")
    if [[ "$sb" == none ]]; then echo "  bench:  Jev: no standard bench fits this intent; your own arguments (fit $(sed -n 's/^fits=//p' <<<"$sugg"))"
    else
      echo "  bench:  Jev suggests '$sb' for this intent + $resource (confidence $(sed -n 's/^confidence=//p' <<<"$sugg")); your arguments $( [[ "$(sed -n 's/^matches_suggestion=//p' <<<"$sugg")" == yes ]] && echo "contain it" || echo "differ from it"), fit $(sed -n 's/^fits=//p' <<<"$sugg")"
      [[ "$(sed -n 's/^matches_suggestion=//p' <<<"$sugg")" == yes ]] || echo "          $sb = $(sed -n 's/^args=//p' <<<"$sugg")   (--bench $sb; cancel and resubmit if that is what you meant)"
    fi
  fi
  echo "  dir:    $dir"
  echo "  status: $dir/status"
  echo "  result: $dir/result.txt"
  echo "  events: $Q/sessions/$sid/events   (harness/queue.sh watch --exit-on any: job ended, overrun, disconnect)"
  local plan rc line pos eta
  plan=$(jev_rank "$machine"); rc=$?
  if (( rc == 0 )); then
    line=$(grep -n -F "$dir"$'\t' <<<"$plan" | head -1)
    pos=${line%%:*}; eta=$(cut -f3 <<<"${line#*:}")
    echo "  order:  Jev puts it #$pos of $(wc -l <<<"$plan") pending on $machine, starts in ~$(( (eta + 59) / 60 )) min by the estimates (Jev re-decides at every pick; harness/queue.sh next $machine)"
  else
    echo "  order:  Jev decides when the worker is free (no plan now: $( (( rc == 3 )) && echo "Jev unreachable, $Q/jev.log" || echo "rank rc $rc"))"
  fi
  if ! is_local "$machine" && [[ "$(mstate "$machine")" == disconnected ]]; then echo "  note:   $machine is disconnected (since $(cat "$Q/machines/$machine/since" 2>/dev/null)); the job waits for it" >&2; fi
  if (( start )); then start_worker "$machine"; is_local "$machine" || start_monitor; fi
  if (( wait )); then wait_job "$dir"; return $?; fi
  return 0
}

bind_cmd() {
  local sid m="${1:-}"; sid=$(session_id)
  [[ -n "$m" ]] || die "bind <machine>"
  is_machine "$m" || die "no machine $m (harness/queue.sh machines)"
  mkdir -p "$Q/sessions/$sid"; echo "$m" > "$Q/sessions/$sid/machine"; echo "session $sid bound to $m"
}
unbind_cmd() { local sid; sid=$(session_id); rm -f "$Q/sessions/$sid/machine"; echo "session $sid unbound (the next submit binds again, default desktop)"; }

# --- list / machines / status / wait / cancel / watch -------------------------------------------------

next_cmd() { # <machine>: Jev's order of the pending jobs there right now (one request), with ETAs
  local m="$1" rc; is_machine "$m" || die "no machine $m"
  jev_rank "$m" >/dev/null; rc=$?
  case "$rc" in
    0) cat "$Q/machines/$m/plan" ;;
    1) echo "nothing pending on $m" ;;
    *) echo "Jev could not be asked (rc $rc, $Q/jev.log)$( [[ -f "$Q/machines/$m/plan" ]] && echo "; its last plan:")"; cat "$Q/machines/$m/plan" 2>/dev/null ;;
  esac
}

session_cmd() { # [--name N] [--intent T] [--progress T]: set this session's context for Jev; always prints it
  local sid s f; sid=$(session_id); s="$Q/sessions/$sid"
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --name|--intent|--progress) [[ -n "${2:-}" ]] || die "$1 needs a value"; session_set "$sid" "${1#--}" "$(oneline "$2")"; shift 2 ;;
      --session) sid="$2"; s="$Q/sessions/$sid"; shift 2 ;;
      *) die "session [--name N] [--intent T] [--progress T]" ;;
    esac
  done
  echo "session $sid"
  for f in name machine intent progress progress_at first_seen; do printf '  %-12s %s\n' "$f" "$(cat "$s/$f" 2>/dev/null || echo -)"; done
  local d; for d in "$JOBS"/*/; do d=${d%/}; [[ -f "$d/job" && "$(jget "$d" session)" == "$sid" && "$(status_of "$d")" =~ ^(pending|running)$ ]] || continue
    printf '  job %s %-8s %-8s %s\n' "$(job_id "$d")" "$(status_of "$d")" "$(jget "$d" machine)" "$(jget "$d" label)"; done
}

suggest_cmd() { # --intent T [--resource r1,r2] [-- run.sh args]: Jev's bench from the catalog, and the fit of your own arguments
  local intent="" resource="" rest=()
  while [[ $# -gt 0 ]]; do case "$1" in --intent) intent="$2"; shift 2 ;; --resource) resource="$2"; shift 2 ;; --) shift; rest=("$@"); break ;; *) die "suggest --intent \"...\" [--resource r1,r2] [-- <run.sh args>]" ;; esac; done
  [[ -n "$intent" ]] || die "suggest needs --intent"
  jev_py suggest --intent "$intent" --resource "$resource" ${rest[*]:+--args "${rest[*]}"}
}

order_of() { echo "~$(job_size "$1")s"; }

list() {
  local d st note m
  printf '%-5s %-9s %-8s %-9s %-7s %-30s %-12s %s\n' id status machine kind est label submitted note
  for d in "$JOBS"/*/; do
    d=${d%/}; [[ -f "$d/job" ]] || continue
    st=$(status_of "$d"); note=""
    case "$st" in
      running) note="since $(cut -c12-16 "$d/started" 2>/dev/null), pid $(cat "$d/pid" 2>/dev/null || echo -)$( [[ -f "$d/overran" ]] && echo ", OVERRUN $(cat "$d/overran") s")"; [[ -f "$d/blocked" ]] && note="blocked: $(cat "$d/blocked")" ;;
      pending) note="$(session_name "$(jget "$d" session)"): $(jget "$d" intent | cut -c1-60)"; [[ -f "$d/blocked" ]] && note="blocked: $(cat "$d/blocked")" ;;
      done|failed) note="exit $(cat "$d/exit" 2>/dev/null || echo ?), $(cat "$d/took" 2>/dev/null || echo ?) s$( [[ -f "$d/result.txt" ]] && sed -n 's/^verdict=\([a-z_]*\).*/, \1/p' "$d/result.txt" | head -1)" ;;
    esac
    printf '%-5s %-9s %-8s %-9s %-6s %-30s %-12s %s\n' "$(job_id "$d")" "$st" "$(jget "$d" machine)" "$(jget "$d" kind)" "$(order_of "$d")" "$(jget "$d" label | cut -c1-30)" "$(jget "$d" submitted | cut -c6-16)" "$note"
  done
  for m in $(machines); do
    worker_alive "$m" && echo "worker $m: running (pid $(cat "$Q/worker-$m.pid"))$( [[ -f "$Q/stop" ]] && echo ', stopping after the current job')"
  done
  if monitor_alive; then echo "monitor: running (pid $(cat "$Q/monitor.pid"))"; else echo "monitor: not running (harness/queue.sh start)"; fi
}

machines_cmd() {
  local m st since depth running sids s d
  printf '%-8s %-13s %-12s %-6s %-8s %-22s %s\n' machine state since queued running worker sessions
  for m in $(machines); do
    if is_local "$m"; then st=local; since=""; else st=$(mstate "$m"); since=$(cut -c6-16 "$Q/machines/$m/since" 2>/dev/null); fi
    depth=0; running=""
    for d in "$JOBS"/*/; do d=${d%/}; [[ -f "$d/job" && "$(jget "$d" machine)" == "$m" ]] || continue
      case "$(status_of "$d")" in pending) depth=$((depth+1)) ;; running) running=$(job_id "$d") ;; esac; done
    sids=""; for s in "$Q/sessions"/*/; do s=${s%/}; [[ -d "$s" && "$(session_machine "$(basename "$s")")" == "$m" ]] && sids+="${sids:+,}$(basename "$s" | cut -c1-8)"; done
    printf '%-8s %-13s %-12s %-6s %-8s %-22s %s\n' "$m" "$st" "${since:-}" "$depth" "${running:--}" "$(worker_alive "$m" && echo "running (pid $(cat "$Q/worker-$m.pid"))" || echo -)" "${sids:--}"
  done
  echo "this session: $(session_id) -> $(session_machine "$(session_id)" || echo '(unbound, default desktop)')"
}

status_cmd() {
  local d; d=$(find_job "$1")
  echo "job $(job_id "$d"): $(status_of "$d")"; cat "$d/job"
  [[ -f "$d/blocked" ]] && echo "blocked=$(cat "$d/blocked")"
  [[ -f "$d/pid" ]] && echo "pid=$(cat "$d/pid")"
  [[ -f "$d/started" ]] && echo "started=$(cat "$d/started")"
  [[ -f "$d/exit" ]] && echo "exit=$(cat "$d/exit")"
  echo "result=$d/result.txt"; return 0
}

wait_job() {
  local d="$1" st m
  [[ -d "$d" ]] || d=$(find_job "$d"); m=$(jget "$d" machine)
  while :; do
    st=$(status_of "$d")
    case "$st" in done|failed|cancelled) break ;; esac
    [[ "$st" == pending ]] && ! worker_alive "$m" && echo "queue: no worker for $m is running (harness/queue.sh start $m)" >&2
    sleep 5
  done
  cat "$d/result.txt" 2>/dev/null || echo "job $(job_id "$d") $st (no result.txt)"
  [[ "$st" == done ]]
}

cancel() {
  local d; d=$(find_job "$1")
  case "$(status_of "$d")" in
    pending) set_status "$d" cancelled; echo "cancelled=$(ts)" >> "$d/job"; rm -f "$d/blocked"; notify_session "$(jget "$d" session)" "job $(job_id "$d") $(jget "$d" label) cancelled"; echo "job $(job_id "$d") cancelled" ;;
    running)
      local pid; pid=$(cat "$d/pid" 2>/dev/null || true)
      [[ -n "$pid" ]] || die "job $(job_id "$d") is running but has no pid yet (blocked in preflight?); try again"
      touch "$d/cancel"
      local m; m=$(jget "$d" machine)
      if [[ -n "$m" ]] && ! is_local "$m"; then
        # killing only the local ssh left the laptop's run.sh and its game running (2026-09-22: the next six jobs failed
        # with "game is running"). Stop them first, while the worker still counts this job as running (once the local
        # side dies the worker starts the next job at once, and a later pkill would hit that job's game). run.sh's
        # EXIT trap restores latestSave.ini / options.ini / the launcher JSON.
        mssh "$m" "bash -c 'pkill -TERM -f \"[P]rojectZomboid\"; for i in 1 2 3 4 5 6 7 8 9 10; do pgrep -f \"[P]rojectZomboid64\" >/dev/null || break; sleep 1; done; pkill -KILL -f \"[P]rojectZomboid64\"; pkill -TERM -f \"[h]arness/run\\.sh\"; sleep 2; pgrep -f \"[P]rojectZomboid|[h]arness/run\\.sh\" >/dev/null && echo still-running || echo remote-stopped'" 2>&1 \
          | sed "s/^/job $(job_id "$d") on $m: /" || echo "job $(job_id "$d"): could not reach $m to stop its run"
      fi
      kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null
      echo "job $(job_id "$d"): SIGTERM sent to process group $pid" ;;
    *) echo "job $(job_id "$d") is $(status_of "$d"); nothing to cancel" ;;
  esac
}

watch_cmd() { # blocking: this session's events as they happen; --exit-on disconnect|job|overrun|any ends it (exit 3 on a disconnect, 4 on an overrun)
  local sid on="" f; sid=$(session_id)
  while [[ $# -gt 0 ]]; do case "$1" in --exit-on) on="$2"; shift 2 ;; --session) sid="$2"; shift 2 ;; *) die "watch [--session S] [--exit-on disconnect|job|overrun|any]" ;; esac; done
  mkdir -p "$Q/sessions/$sid"; f="$Q/sessions/$sid/events"; touch "$f"
  echo "watching $f (session $sid, machine $(session_machine "$sid" || echo unbound))"
  tail -n0 -F "$f" 2>/dev/null | while IFS= read -r line; do
    echo "$line"
    case "$on" in
      disconnect) [[ "$line" == *disconnected* ]] && exit 3 ;;
      job) [[ "$line" == *" job "* && "$line" != *" overrun: "* ]] && exit 0 ;;
      overrun) [[ "$line" == *" overrun: "* ]] && exit 4 ;;
      any) [[ "$line" == *disconnected* ]] && exit 3; [[ "$line" == *" overrun: "* ]] && exit 4; exit 0 ;;
    esac
  done
}
events_cmd() { local sid; sid=$(session_id); tail -n "${1:-20}" "$Q/sessions/$sid/events" 2>/dev/null || echo "no events for session $sid yet"; }

# --- preflight -----------------------------------------------------------------------------------------

game_running() { pgrep -f "$GAME_PATTERN" >/dev/null; }
foreign_run() { pgrep -f "$BUSY_PATTERN" >/dev/null; }
session_locked() {
  command -v loginctl >/dev/null || return 1
  local sess; sess=$(loginctl list-sessions --no-legend 2>/dev/null | awk '$3=="'"$USER"'" && $4=="seat0" {print $1; exit}')
  [[ -n "$sess" ]] && loginctl show-session "$sess" -p LockedHint 2>/dev/null | grep -q '=yes'
}
steam_logged_on() { # the last Logged On / Logged Off / Session Replaced marker must be a Logged On
  pgrep -x steam >/dev/null || return 1
  local log="$STEAM_LOGS/connection_log.txt"; [[ -f "$log" ]] || return 1
  tail -200 "$log" | grep -a -o 'Logged On\|Logged Off\|Session Replaced' | tail -1 | grep -q 'Logged On'
}

blocked_note() { # <job> <reason>: the blocked file, one log line per change
  local d="$1" reason="$2"
  if [[ "$reason" != "$(cat "$d/blocked" 2>/dev/null)" ]]; then
    echo "$reason" > "$d/blocked"; say "job $(job_id "$d") waiting: $reason"; echo "[$(ts)] waiting: $reason" >> "$d/output.log"
  fi
}

foreign_encode() { pgrep -f "$ENCODE_PATTERN" >/dev/null; }

wait_until_free() { # <job> [media]: nothing else uses this computer; 1 when the worker was asked to stop meanwhile
  local d="$1" kind="${2:-}" reason
  [[ "$kind" == workshop ]] && { rm -f "$d/blocked"; return 0; }   # a Steam API call: no game, no display
  while :; do
    [[ -f "$Q/stop" ]] && return 1
    reason=""
    if game_running; then reason="the game is running outside the queue (pid $(pgrep -f "$GAME_PATTERN" | head -1))"
    elif foreign_run; then reason="a run outside the queue is going: $(pgrep -fa "$BUSY_PATTERN" | head -1 | cut -c1-120)"
    elif foreign_encode; then reason="an encode outside the queue is going: $(pgrep -fa "$ENCODE_PATTERN" | head -1 | cut -c1-120)"
    elif [[ "$kind" != media ]] && session_locked; then reason="the desktop session is locked"; fi
    if [[ -z "$reason" ]]; then rm -f "$d/blocked"; return 0; fi
    blocked_note "$d" "$reason"; sleep 20
  done
}

wait_until_connected() { # the monitor keeps the state; probe ourselves when it is not running
  local d="$1" m="$2"
  while :; do
    [[ -f "$Q/stop" ]] && return 1
    monitor_alive || { if probe "$m"; then set_mstate "$m" connected; else set_mstate "$m" disconnected; fi; }
    if connected "$m"; then rm -f "$d/blocked"; return 0; fi
    blocked_note "$d" "$m disconnected (since $(cat "$Q/machines/$m/since" 2>/dev/null))"; sleep 10
  done
}

ensure_steam() { # a really connected Steam client, restarting it once if the session was replaced elsewhere
  local d="$1" i
  steam_logged_on && return 0
  say "Steam is not logged on; restarting the client"; echo "[$(ts)] Steam not logged on; steam -shutdown, then start" >> "$d/output.log"
  steam -shutdown >/dev/null 2>&1 || true
  for i in $(seq 1 30); do pgrep -x steam >/dev/null || break; sleep 2; done
  pgrep -x steam >/dev/null && pkill -x steam; sleep 3
  setsid -f steam >/dev/null 2>&1
  for i in $(seq 1 45); do sleep 2; steam_logged_on && { echo "[$(ts)] Steam logged on" >> "$d/output.log"; return 0; }; done
  echo "[$(ts)] Steam still not logged on after the restart: it needs the password or Steam Guard (the maintainer's)" >> "$d/output.log"
  return 1
}

apply_install() { # local --install handling; leaves $Q/left-stock when the game is stock afterwards
  local d="$1" install="$2" cwd="$3" kind="${4:-}" repo=""
  case "$install" in
    keep)
      # keep = whatever the previous jobs left. A game dir with no install and no --install stock job behind it
      # (a reinstall that failed half-way, a manual uninstall) has no pzopt.Harness, so a run would sit at
      # click-to-start forever (job 0204 vp0-torch, 2026-09-22): put the job's own build back first.
      [[ "$kind" =~ ^(run|mp)$ ]] || return 0
      [[ -f "$Q/left-stock" ]] && return 0
      # grep without -q: -q exits at the first match, pzopt.sh status then dies of SIGPIPE on its file list and
      # pipefail fails the whole check on an installed game (jobs 0997 / 1000 / 1001, 2026-09-22)
      (cd "$cwd" && scripts/pzopt.sh status 2>/dev/null | grep '^installed: *yes' >/dev/null) && return 0
      echo "[$(ts)] --install keep, but the game is stock and no --install stock job left it so; reinstalling from $cwd" >> "$d/output.log"
      repo="$cwd" ;;
    opt) repo="$cwd" ;;
    stock)
      echo "[$(ts)] --install stock: scripts/pzopt.sh uninstall" >> "$d/output.log"
      (cd "$cwd" && scripts/pzopt.sh uninstall) >> "$d/output.log" 2>&1 || { echo "[$(ts)] uninstall failed" >> "$d/output.log"; return 1; }
      echo "$cwd" > "$Q/left-stock"; return 0 ;;
    *) repo="$install" ;;
  esac
  echo "[$(ts)] --install $install: $repo/scripts/pzopt.sh reinstall" >> "$d/output.log"
  (cd "$repo" && scripts/pzopt.sh reinstall) >> "$d/output.log" 2>&1 || { echo "[$(ts)] reinstall failed (is build/classes built?)" >> "$d/output.log"; return 1; }
  (cd "$repo" && scripts/pzopt.sh status | head -3) >> "$d/output.log" 2>&1
  rm -f "$Q/left-stock"
}

launch() { # <job> <cwd> <command...> as the job's process group, output to output.log, exit code returned
  local d="$1" cwd="$2"; shift 2
  echo "[$(ts)] \$ (cd $cwd && $*)" >> "$d/output.log"
  PZQ_JOB="$d" setsid bash -c 'cd "$1" && shift && exec "$@"' _ "$cwd" "$@" >> "$d/output.log" 2>&1 &
  local pid=$!; echo "$pid" > "$d/pid"
  wait "$pid"; local rc=$?; rm -f "$d/pid"
  echo "[$(ts)] exit $rc" >> "$d/output.log"; return $rc
}

newest_run_dir() { # the run dir this job produced: newest <cwd>/harness/runs/<label>-* created after the job started
  local cwd="$1" label="$2" since="$3" r
  r=$(ls -td "$cwd/harness/runs/$label"-* 2>/dev/null | head -1)
  [[ -n "$r" && "$(stat -c %Y "$r")" -ge "$since" ]] && echo "$r"
}

# --- remote jobs -----------------------------------------------------------------------------------------

remote_wrapper() { # <job> <machine> <install> <argv...> -> $job/remote-job.sh (bash 3.2-safe) + pzq-inhibit.py
  local d="$1" m="$2" install="$3"; shift 3
  local os repo pz_root game_dir denv q
  os=$(mcfg "$m" os linux); repo=$(mcfg "$m" repo); pz_root=$(mcfg "$m" pz_root); game_dir=$(mcfg "$m" game_dir); denv=$(mcfg "$m" display_env)
  printf -v q '%q ' "$@"
  {
    echo '#!/bin/bash'
    echo "# generated by harness/queue.sh for job $(job_id "$d") ($m); runs in the ssh foreground"
    echo 'set -u'
    echo "cd $repo || exit 1"
    [[ -n "$pz_root" ]] && echo "export PZ_ROOT=$pz_root"
    if [[ "$os" == linux ]]; then
      if [[ -n "$denv" ]]; then
        echo "p=\$(pgrep -o $denv || true)"
        echo 'if [[ -n "$p" ]]; then for v in DISPLAY WAYLAND_DISPLAY XAUTHORITY DBUS_SESSION_BUS_ADDRESS XDG_SESSION_TYPE XDG_CURRENT_DESKTOP XDG_RUNTIME_DIR; do'
        echo '  val=$(tr "\0" "\n" < /proc/$p/environ 2>/dev/null | sed -n "s/^$v=//p" | head -1); [[ -n "$val" ]] && export "$v=$val"; done'
        echo "else echo \"queue: no $denv process: is the desktop session logged in?\" >&2; fi"
      fi
      [[ "$(mcfg "$m" steam_shutdown)" == true ]] && echo 'pgrep -x steam >/dev/null && { steam -shutdown >/dev/null 2>&1; for i in $(seq 1 20); do pgrep -x steam >/dev/null || break; sleep 1; done; echo "queue: steam shut down"; }'
      if [[ "$(mcfg "$m" inhibit)" == true ]]; then
        echo 'for s in $(loginctl list-sessions --no-legend 2>/dev/null | awk -v u="$USER" '"'"'$3==u {print $1}'"'"'); do loginctl unlock-session "$s" 2>/dev/null; done'
        echo 'setsid -f python3 /tmp/pzq-inhibit.py </dev/null >/dev/null 2>&1; echo "queue: sleep / lock inhibited"'
      fi
      if [[ "$install" == opt ]]; then
        case "$(mcfg "$m" install install.sh)" in
          pzopt.sh) echo 'scripts/pzopt.sh reinstall || exit 1' ;;
          *) echo "./install.sh --uninstall --dir $game_dir >/dev/null || exit 1"   # install.sh refuses over an install; --uninstall is a no-op without one
             echo "./install.sh --from build/classes --dir $game_dir || exit 1" ;;
        esac
      fi
      echo "$(mcfg "$m" runner harness/run.sh) $q$(mcfg "$m" run_args)"
      echo 'rc=$?'
      [[ "$(mcfg "$m" inhibit)" == true ]] && echo "pkill -f '[p]zq-inhibit.py' 2>/dev/null"
      echo 'exit $rc'
    else
      # run-mac.sh install refuses over an install (as install.sh does); uninstall is a no-op without one. Before this,
      # sessions uninstalled by hand over ssh before an --install opt job and removed the files under a running one
      # (jobs 1117 / 1118, 2026-09-23: ClassNotFoundException mid-boot, then a run with nothing installed)
      [[ "$install" == opt ]] && echo 'harness/run-mac.sh uninstall >/dev/null || exit 1; harness/run-mac.sh install build/classes || exit 1'
      # --install keep on a Mac left stock (a manual uninstall) launched a game with no pzopt.Harness, which sits at
      # click-to-start until the 900 s timeout (job 1133, 2026-09-23): reinstall the classes last synced to the Mac, or
      # fail at once when there are none.
      [[ "$install" == keep ]] && echo 'if ! harness/run-mac.sh status 2>/dev/null | grep "^installed: *yes" >/dev/null; then echo "queue: --install keep, but the Mac game is stock; reinstalling build/classes"; [[ -d build/classes ]] || { echo "queue: no build/classes on the Mac; submit with --install opt"; exit 1; }; harness/run-mac.sh install build/classes || exit 1; fi'
      echo "$(mcfg "$m" runner harness/run-mac.sh) $q$(mcfg "$m" run_args)"
    fi
  } > "$d/remote-job.sh"
  cat > "$d/pzq-inhibit.py" <<'PY'
# hold the screensaver + power-management inhibits while a queued run goes (harness/queue.sh, a laptop that idles to sleep)
import gi
gi.require_version("Gio", "2.0")
from gi.repository import Gio, GLib
bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
for name, path, iface in (("org.freedesktop.ScreenSaver", "/org/freedesktop/ScreenSaver", "org.freedesktop.ScreenSaver"),
                          ("org.freedesktop.PowerManagement.Inhibit", "/org/freedesktop/PowerManagement/Inhibit", "org.freedesktop.PowerManagement.Inhibit")):
    try:
        bus.call_sync(name, path, iface, "Inhibit", GLib.Variant("(ss)", ("pzopt-queue", "benchmark run")), None, Gio.DBusCallFlags.NONE, -1, None)
    except Exception as e:
        print(name, e)
GLib.MainLoop().run()
PY
}

remote_job() { # sync -> wrapper -> run in the ssh foreground -> collect; sets REMOTE_RUN_DIR (the local copy); 70 = connection lost
  local d="$1" m="$2" cwd="$3" label="$4" install="$5"; shift 5
  local repo os rc remote_dir local_dir host base
  repo=$(remote_path "$m" repo); os=$(mcfg "$m" os linux); host=$(mcfg "$m" host)
  REMOTE_RUN_DIR=""
  echo "[$(ts)] sync harness/ -> $m:$repo/" >> "$d/output.log"
  if [[ "$os" == linux ]]; then
    mrsync "$m" --exclude runs/ --exclude bench-save/ --exclude __pycache__/ "$cwd/harness/" "$host:$repo/harness/" >> "$d/output.log" 2>&1 || { echo "[$(ts)] rsync harness/ failed ($m disconnected?)" >> "$d/output.log"; return 70; }
    mrsync "$m" "$cwd/scripts/" "$host:$repo/scripts/" >> "$d/output.log" 2>&1
    mrsync "$m" "$cwd/install.sh" "$host:$repo/install.sh" >> "$d/output.log" 2>&1
  else
    mrsync "$m" "$cwd/harness/run-mac.sh" "$host:$repo/harness/run-mac.sh" >> "$d/output.log" 2>&1 || { echo "[$(ts)] rsync run-mac.sh failed ($m disconnected?)" >> "$d/output.log"; return 70; }
    mrsync "$m" "$cwd/harness/mod/" "$host:$repo/harness/mod/" >> "$d/output.log" 2>&1
  fi
  if [[ "$install" == opt ]]; then
    [[ -d "$cwd/build/classes" ]] || { echo "[$(ts)] --install opt: no $cwd/build/classes (scripts/build.sh first)" >> "$d/output.log"; return 1; }
    echo "[$(ts)] sync build/classes/ -> $m" >> "$d/output.log"
    mrsync "$m" --delete "$cwd/build/classes/" "$host:$repo/build/classes/" >> "$d/output.log" 2>&1 || { echo "[$(ts)] rsync build/classes failed" >> "$d/output.log"; return 70; }
  fi
  remote_wrapper "$d" "$m" "$install" "$@"
  mrsync "$m" "$d/remote-job.sh" "$d/pzq-inhibit.py" "$host:/tmp/" >> "$d/output.log" 2>&1 || return 70
  echo "[$(ts)] \$ ssh $m bash /tmp/remote-job.sh   ($(sed -n '$p' "$d/remote-job.sh" | cut -c1-160))" >> "$d/output.log"
  ssh_args "$m"
  PZQ_JOB="$d" setsid ssh "${SSH_ARGS[@]}" "$host" bash /tmp/remote-job.sh >> "$d/output.log" 2>&1 &
  local pid=$!; echo "$pid" > "$d/pid"
  wait "$pid"; rc=$?; rm -f "$d/pid"
  echo "[$(ts)] remote exit $rc" >> "$d/output.log"
  if (( rc == 255 )); then
    echo "[$(ts)] the ssh connection to $m ended (255): the machine disconnected or went to sleep mid-run" >> "$d/output.log"
    probe "$m" || { set_mstate "$m" disconnected; event "$m" "disconnected during job $(job_id "$d") $label"; return 70; }
  fi
  # through bash: the laptops' login shell is fish with ls aliased to eza; the Mac's is zsh
  remote_dir=$(mssh "$m" "bash -c 'command ls -td $repo/harness/runs/$label-* 2>/dev/null | head -1'" 2>/dev/null | tr -d '\r')
  if [[ -z "$remote_dir" ]]; then echo "[$(ts)] no harness/runs/$label-* on $m" >> "$d/output.log"; return $(( rc ? rc : 1 )); fi
  base=$(basename "$remote_dir")
  if [[ "$base" == "$m-"* ]]; then local_dir="$cwd/harness/runs/$base"; else local_dir="$cwd/harness/runs/$m-$base"; fi
  echo "[$(ts)] collect $m:$remote_dir -> $local_dir" >> "$d/output.log"
  mkdir -p "$local_dir"
  mrsync "$m" "$host:$remote_dir/" "$local_dir/" >> "$d/output.log" 2>&1 || { echo "[$(ts)] rsync of the run dir failed" >> "$d/output.log"; return 70; }
  echo "machine=$m" >> "$local_dir/run.opts"
  REMOTE_RUN_DIR="$local_dir"
  return $rc
}

# --- results (analyze + Jev) ---------------------------------------------------------------------------

DEFAULT_GOAL="A valid measurement run on its own (no comparison run is needed): the logs a benchmark needs are present and, by the objective, the hardware is either at the cap or saturated; flag headroom left on the table."

judge_run() { # Jev's verdict on the run: harness/judge.py over analyze.py's card, the --against deltas and the goal
  local d="$1" cwd="$2" run="$3" goal against cap a args=() rc_n crashed
  goal=$(jget "$d" goal); [[ -n "$goal" ]] || goal="$DEFAULT_GOAL"
  against=$(jget "$d" against); cap=$(jget "$d" cap)
  # facts the card cannot carry, stated for Jev: did the route finish, did the game crash, which machine
  rc_n=$(awk '/route (complete|done)/{n++} END{print n+0}' "$run/console.txt" 2>/dev/null)
  crashed=$(sed -n 's/^crashed=//p' "$run/run.opts" 2>/dev/null | head -1)
  goal="$goal Facts from the run's console: route complete lines = ${rc_n:-0}$( [[ "${rc_n:-0}" == 0 ]] && echo ' (the route did not report completion: a drive that left the road, a crash or a mode without a route)'); crashed = ${crashed:-?}; machine = $(jget "$d" machine)."
  for a in $against; do args+=(--against "$a"); done
  [[ -n "$cap" ]] && args+=(--cap "$cap")
  echo "--- judge.py (Jev)  goal: $goal"
  (cd "$cwd" && timeout 600 python3 harness/judge.py "$run" "${args[@]}" --goal "$goal" 2>&1) || true
  if [[ -f "$run/judge.json" ]]; then
    python3 - "$run/judge.json" <<'PY'
import json, sys
a = json.load(open(sys.argv[1]))["answers"]
v = a["verdict"]
print(f"verdict={v['choice']} confidence={v['confidence']:.2f} goal_met={a['goal_met']['noul']:.2f} tail_regressed={a['tail_regressed']['noul']:.2f} setup_matches_goal={a['setup_matches_goal']['noul']:.2f} headroom_finding={a['hardware_headroom_finding']['noul']:.2f}")
PY
  else
    echo "verdict=(judge.py produced no judge.json; see above)"
  fi
}

parity_run() { # Jev's visual-parity verdict between the --parity-against recording and this run's
  local d="$1" cwd="$2" run="$3" other goal
  other=$(jget "$d" parity_against); [[ -n "$other" ]] || return 0
  goal=$(jget "$d" goal)
  if [[ ! -f "$run/recording.mp4" ]]; then echo "parity=(no recording.mp4 in the run; --parity-against needs --record)"; return 0; fi
  echo "--- parity-judge.py (Jev)  against $other"
  (cd "$cwd" && timeout 900 python3 harness/parity-judge.py "$other" "$run" ${goal:+--context "$goal"} --json "$run/parity-judge.json" 2>&1) || true
  if [[ -f "$run/parity-judge.json" ]]; then
    python3 - "$run/parity-judge.json" <<'PY'
import json, sys
a = json.load(open(sys.argv[1])).get("answers", {})
k = a.get("kind", {})
print(f"parity={k.get('choice', '?')} confidence={k.get('confidence', 0):.2f} parity_maintained={a.get('parity_maintained', {}).get('noul', 0):.2f} look_needed={a.get('look_needed', {}).get('noul', 0):.2f}")
PY
  fi
}

result_header() {
  local d="$1" rc="$2"
  echo "job=$(job_id "$d") kind=$(jget "$d" kind) label=$(jget "$d" label) machine=$(jget "$d" machine) session=$(jget "$d" session)"
  echo "status=$( (( rc == 0 )) && echo done || echo failed) exit=$rc$( (( rc == 70 )) && echo ' (connection to the machine lost)')"
  echo "cwd=$(jget "$d" cwd)"; echo "args=$(jget "$d" args)"
  echo "started=$(cat "$d/started") finished=$(ts) took=$(cat "$d/took") s"
  echo "output=$d/output.log"
}

result_run() { # <job> <rc> <cwd> <run dir or empty>
  local d="$1" rc="$2" cwd="$3" run="$4"
  if [[ -z "$run" ]]; then
    echo "run_dir=(none: the run did not get to the collect step)"; echo "verdict=invalid (no run)"; echo "--- output tail"; tail -25 "$d/output.log"; return
  fi
  echo "run_dir=$run"
  echo "route_complete=$(awk '/route (complete|done)/{n++} END{print n+0}' "$run/console.txt" 2>/dev/null)"
  grep -a -E 'Desktop resolution|OpenGL version' "$run/console.txt" 2>/dev/null | sed 's/^/console: /' | cut -c1-160
  grep -a -E '^(zoom|weather|torch|time_of_day|population)=' "$run/pzopt-bench.out" 2>/dev/null | tr '\n' ' ' | sed 's/^/bench: /;s/ $/\n/'
  grep -a -E 'crashed=|attempts=|launcher=|renderer=|run_seconds=|machine=' "$run/run.opts" 2>/dev/null | tr '\n' ' ' | sed 's/^/opts: /;s/ $/\n/'
  echo "pzopt_lines=$(grep -a -F '[pzopt' "$run/console.txt" 2>/dev/null | wc -l)"
  echo "--- analyze.py"
  (cd "$cwd" && timeout 300 python3 harness/analyze.py "$run" 2>&1) || echo "(analyze.py failed; verify/play runs have no route window)"
  echo "--- console errors"
  grep -a -iE 'exception|error' "$run/console.txt" 2>/dev/null | grep -a -v 'ERROR: 0:0\|GL_' | head -8
  judge_run "$d" "$cwd" "$run"
  parity_run "$d" "$cwd" "$run"
}

result_mp() {
  local d="$1" rc="$2" cwd="$3" run="$4"
  echo "run_dir=${run:-(none)}"
  echo "--- harness/mp/run.sh"; grep -v '^\[queue' "$d/output.log" | tail -20
  if [[ -n "$run" ]]; then
    echo "--- window.py (27 s route window)"; (cd "$cwd" && python3 harness/mp/window.py "$run:27" 2>&1) || true
    judge_run "$d" "$cwd" "$run"
  else echo "verdict=invalid (no run)"; fi
}

changelog_first_entry() { # the public change-notes page; the newest entry comes first
  command -v curl >/dev/null || { echo "(no curl)"; return; }
  curl -s -m 30 "https://steamcommunity.com/sharedfiles/filedetails/changelog/$WORKSHOP_ID" 2>/dev/null \
    | tr -d '\r' | sed -n '/changelog_header\|changelog_body\|detailBox/p' | sed 's/<[^>]*>//g;s/&quot;/"/g;s/&amp;/\&/g' | sed 's/^[[:space:]]*//' | grep -v '^$' | head -6
}

workshop_job() { # stage, then the Steamworks API upload (scripts/workshop-upload.py): no game, no screen, seconds
  local d="$1" cwd="$2" notes="$3"; shift 3
  local argv=("$@")
  [[ -x "$cwd/scripts/workshop-upload.py" ]] || { echo "[$(ts)] no scripts/workshop-upload.py in $cwd" >> "$d/output.log"; return 1; }
  launch "$d" "$cwd" scripts/workshop.sh "${argv[@]}" || return 1
  grep -q "^id=$WORKSHOP_ID" "$WS_DIR/workshop.txt" 2>/dev/null || { echo "[$(ts)] $WS_DIR/workshop.txt is not staged for item $WORKSHOP_ID" >> "$d/output.log"; return 1; }
  launch "$d" "$cwd" python3 scripts/workshop-upload.py --dir "$WS_DIR" --notes "$notes" || return $?
  cp "$WS_DIR/workshop.txt" "$cwd/docs/workshop/workshop.txt" 2>/dev/null && echo "[$(ts)] copied workshop.txt to $cwd/docs/workshop/workshop.txt (uncommitted)" >> "$d/output.log"
  return 0
}

result_workshop() {
  local d="$1" rc="$2" cwd="$3"
  echo "--- workshop_log.txt ($WORKSHOP_ID)"; grep -a "$WORKSHOP_ID" "$STEAM_LOGS/workshop_log.txt" 2>/dev/null | tail -3 | cut -c1-200
  echo "--- change-notes page (newest entry)"; changelog_first_entry
  echo "--- staged"; grep -E '^(id|title)=' "$WS_DIR/workshop.txt" 2>/dev/null
  echo "--- workshop-upload.py"
  grep -a -E '^(steam:|item |description |submitted|  +[0-9.]+ s  |upload (OK|FAILED)|check OK|Steam |SteamAPI_|no result|the SubmitItemUpdate|note:)' "$d/output.log" | tail -16
  if (( rc == 0 )); then
    echo "next: commit $cwd/docs/workshop/workshop.txt (\"workshop: stage the <commit> release\")"
  else
    echo "--- output (last lines)"; grep -v '^\[queue' "$d/output.log" | tail -12
  fi
}

result_cmd() {
  local d="$1" rc="$2" cwd="$3" run="$4"
  [[ -n "$run" ]] && echo "run_dir=$run"
  echo "--- output tail"; grep -v '^\[queue' "$d/output.log" | tail -30
}

result_media() { # <job> <rc> <cwd> <since>: ffprobe every output written by the job; the publishing rule is AV1 10-bit PQ/BT.2020
  local d="$1" rc="$2" cwd="$3" since="$4" o f probe codec pix trc prim w h dur size ok
  echo "--- outputs"
  for o in $(jget "$d" outs); do
    f="$o"; [[ "$f" = /* ]] || f="$cwd/$o"
    if [[ ! -f "$f" ]]; then echo "output=$o missing"; continue; fi
    if [[ ! "$f" -nt "$d/started" ]]; then echo "input=$o (older than the job; not written by it)"; continue; fi
    size=$(stat -c %s "$f")
    case "$f" in
      *.mp4|*.mkv|*.webm|*.mov)
        probe=$(ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,pix_fmt,width,height,color_primaries,color_transfer,color_space:format=duration -of default=nw=1 "$f" 2>/dev/null | tr '\n' ' ')
        codec=$(sed -n 's/.*codec_name=\([^ ]*\).*/\1/p' <<<"$probe"); pix=$(sed -n 's/.*pix_fmt=\([^ ]*\).*/\1/p' <<<"$probe")
        trc=$(sed -n 's/.*color_transfer=\([^ ]*\).*/\1/p' <<<"$probe"); prim=$(sed -n 's/.*color_primaries=\([^ ]*\).*/\1/p' <<<"$probe")
        w=$(sed -n 's/.*width=\([^ ]*\).*/\1/p' <<<"$probe"); h=$(sed -n 's/.*height=\([^ ]*\).*/\1/p' <<<"$probe"); dur=$(sed -n 's/.*duration=\([^ ]*\).*/\1/p' <<<"$probe")
        if [[ "$codec" == av1 && "$pix" == yuv420p10le && "$trc" == smpte2084 && "$prim" == bt2020 ]]; then ok=yes; else ok=no; fi
        echo "output=$o ${w}x${h} ${dur%.*} s $((size / 1048576)) MB codec=$codec pix_fmt=$pix transfer=$trc primaries=$prim hdr_av1_ok=$ok"
        [[ "$ok" == yes ]] || echo "  WARNING: not AV1 10-bit PQ/BT.2020; every video published under docs/media/ must be (harness/encode-av1-hdr.sh)" ;;
      *)
        probe=$(ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,width,height,nb_frames -of default=nw=1 "$f" 2>/dev/null | tr '\n' ' ')
        echo "output=$o $((size / 1024)) KB $probe" ;;
    esac
  done
  echo "--- output tail"; grep -v '^\[queue' "$d/output.log" | tail -25
}

# --- the worker ------------------------------------------------------------------------------------------

desktop_notify() { # <title> <body> [urgency]
  command -v notify-send >/dev/null && notify-send -a pzopt-queue -u "${3:-normal}" "$1" "$2" >/dev/null 2>&1 || true
}

job_sname() { local n; n=$(jget "$1" session_name); [[ -n "$n" ]] && echo "$n" || session_name "$(jget "$1" session)"; }

mins() { local s="$1"; (( s < 90 )) && echo "${s} s" || echo "$(( (s + 30) / 60 )) min"; }

announce_start() { # <job> <machine>: the desktop notification when a job really starts (after the preflight waits)
  local d="$1" m="$2"
  desktop_notify "pzopt queue: $(jget "$d" label) started on $m" \
    "Session: $(job_sname "$d")
Estimate: ~$(mins "$(job_size "$d")") ($(jget "$d" size_from))
Intent: $(i=$(jget "$d" intent); echo "${i:-(not given; queued before the Jev queue)}")"
}

overrun_watch() { # <job> <machine> <worker pid>, background: tell the session when the job runs past its estimate
  local d="$1" m="$2" wpid="$3" size next el launched n=0 sid label
  size=$(job_size "$d"); next=$size; sid=$(jget "$d" session); label=$(jget "$d" label)
  while sleep 10; do
    [[ "$(status_of "$d")" == running ]] && kill -0 "$wpid" 2>/dev/null || return 0
    launched=$(cat "$d/launched" 2>/dev/null) || continue          # still in the preflight wait: not counted
    el=$(( $(date +%s) - launched ))
    (( el > next )) || continue
    n=$((n + 1)); echo "$el" > "$d/overran"
    notify_session "$sid" "overrun: job $(job_id "$d") $label on $m still running after $(mins "$el"), estimate was ~$(mins "$size") (log: harness/queue.sh log $(job_id "$d") -f; cancel if it hangs)"
    echo "[$(ts)] overrun: running $el s, estimate $size s" >> "$d/output.log"
    desktop_notify "pzopt queue: $label over its estimate" "Session: $(job_sname "$d"), running $(mins "$el") of ~$(mins "$size") on $m" "$( (( n > 1 )) && echo critical || echo normal)"
    next=$(( next + (size > OVERRUN_REPEAT_MIN ? size : OVERRUN_REPEAT_MIN) ))
  done
}

run_job() {
  local d="$1" m="$2" kind label cwd install notes rc since launched run="" wpid=$BASHPID owpid
  kind=$(jget "$d" kind); label=$(jget "$d" label); cwd=$(jget "$d" cwd); install=$(jget "$d" install); notes=$(jget "$d" notes)
  local argv=(); mapfile -d '' argv < "$d/argv"
  set_status "$d" running; ts > "$d/started"; since=$(date +%s)
  say "job $(job_id "$d") start on $m: $kind $label (from $cwd)"
  echo "[$(ts)] job $(job_id "$d") $kind $label on $m, session $(jget "$d" session) ($(jget "$d" session_name)), estimate $(job_size "$d") s" >> "$d/output.log"
  rm -f "$d/launched" "$d/overran"
  overrun_watch "$d" "$m" "$wpid" & owpid=$!
  if is_local "$m"; then
    if ! wait_until_free "$d" "$kind"; then kill "$owpid" 2>/dev/null; set_status "$d" pending; rm -f "$d/started" "$d/blocked"; return 1; fi
    launched=$(date +%s); rc=0; echo "$launched" > "$d/launched"; announce_start "$d" "$m"
    if [[ "$kind" == workshop ]] && ! ensure_steam "$d"; then rc=1; fi
    if (( rc == 0 )) && ! apply_install "$d" "$install" "$cwd" "$kind"; then rc=1; fi
    if (( rc == 0 )); then
      case "$kind" in
        run) launch "$d" "$cwd" harness/run.sh "${argv[@]}"; rc=$? ;;
        mp) launch "$d" "$cwd" harness/mp/run.sh "${argv[@]}"; rc=$? ;;
        cmd) launch "$d" "$cwd" "${argv[@]}"; rc=$? ;;
        media) launch "$d" "$cwd" "${argv[@]}"; rc=$? ;;
        workshop) workshop_job "$d" "$cwd" "$notes" "${argv[@]}"; rc=$? ;;
      esac
    fi
    [[ "$kind" =~ ^(run|mp|cmd)$ ]] && run=$(newest_run_dir "$cwd" "$label" "$since")
  else
    if ! wait_until_connected "$d" "$m"; then kill "$owpid" 2>/dev/null; set_status "$d" pending; rm -f "$d/started" "$d/blocked"; return 1; fi
    launched=$(date +%s); echo "$launched" > "$d/launched"; announce_start "$d" "$m"
    remote_job "$d" "$m" "$cwd" "$label" "$install" "${argv[@]}"; rc=$?
    run="$REMOTE_RUN_DIR"
  fi
  kill "$owpid" 2>/dev/null
  echo $(( $(date +%s) - since )) > "$d/took"; echo $(( $(date +%s) - launched )) > "$d/ran"; echo "$rc" > "$d/exit"
  {
    result_header "$d" "$rc"
    [[ -f "$d/overran" ]] && echo "overran=yes: ran $(cat "$d/ran") s against an estimate of $(job_size "$d") s ($(jget "$d" size_from))"
    case "$kind" in
      run) result_run "$d" "$rc" "$cwd" "$run" ;;
      mp) result_mp "$d" "$rc" "$cwd" "$run" ;;
      workshop) result_workshop "$d" "$rc" "$cwd" ;;
      cmd) result_cmd "$d" "$rc" "$cwd" "$run" ;;
      media) result_media "$d" "$rc" "$cwd" "$since" ;;
    esac
    [[ -f "$Q/left-stock" ]] && echo "note: the game is stock now (--install stock); reinstalled from $(cat "$Q/left-stock") when the desktop queue drains"
  } > "$d/result.txt" 2>&1
  if [[ -f "$d/cancel" ]]; then set_status "$d" cancelled; rm -f "$d/cancel"
  elif (( rc == 0 )); then set_status "$d" done
  else set_status "$d" failed; fi
  local verdict; verdict=$(sed -n 's/^verdict=\([a-z_]*\).*/\1/p' "$d/result.txt" | head -1)
  say "job $(job_id "$d") $(status_of "$d") (exit $rc, $(cat "$d/took") s${verdict:+, $verdict}): $d/result.txt"
  notify_session "$(jget "$d" session)" "job $(job_id "$d") $label $(status_of "$d") on $m (exit $rc${verdict:+, verdict $verdict}): $d/result.txt"
  if [[ "$kind" == mp ]]; then   # a stock dedicated server ticking its world would skew the next single-player run
    local nxt="" p; for p in $(pending_on desktop); do [[ "$(jget "$p" kind)" == mp ]] && nxt=$p; done   # no Jev request just for this
    if [[ -z "$nxt" ]]; then (cd "$cwd" && harness/mp/server.sh stop) >> "$d/output.log" 2>&1 && say "dedicated server stopped"; fi
  fi
  return 0
}

idle() { # the desktop queue drained after a --install stock job: put the overrides back
  if [[ -f "$Q/left-stock" ]]; then
    local repo; repo=$(cat "$Q/left-stock")
    if ! game_running && ! foreign_run; then
      say "queue idle after a stock job: reinstalling from $repo"
      (cd "$repo" && scripts/pzopt.sh reinstall) >> "$Q/worker-desktop.log" 2>&1 && rm -f "$Q/left-stock"
    fi
  fi
}

worker() { # --machine <m>
  local m=""
  [[ "${1:-}" == --machine && -n "${2:-}" ]] && m="$2"
  [[ -n "$m" ]] && is_machine "$m" || die "worker --machine <m> (one of: $(machines | tr '\n' ' '))"
  local pidfile="$Q/worker-$m.pid"
  ( flock 9; if worker_alive "$m"; then echo "queue: a worker for $m is already running (pid $(cat "$pidfile"))" >&2; exit 1; fi; echo $$ > "$pidfile" ) 9>"$Q/worker.lock" || exit 1
  trap "rm -f '$pidfile'" EXIT
  trap 'say "worker $m ($$) terminated"; exit 143' TERM INT
  say "worker $m ($$) up, queue dir $Q"
  local d rc
  while :; do
    if [[ -f "$Q/stop" ]]; then say "worker $m stopping"; break; fi
    d=$(next_pending "$m"); rc=$?
    case "$rc" in
      0) run_job "$d" "$m" ;;
      3) sleep 30 ;;                                   # Jev unreachable: the pending jobs say so in blocked
      *) is_local "$m" && idle; sleep 5 ;;
    esac
  done
}

# --- the connection monitor --------------------------------------------------------------------------------

monitor() {
  ( flock 9; if monitor_alive; then echo "queue: the monitor is already running (pid $(cat "$Q/monitor.pid"))" >&2; exit 1; fi; echo $$ > "$Q/monitor.pid" ) 9>"$Q/monitor.lock" || exit 1
  trap 'rm -f "$Q/monitor.pid"' EXIT
  trap 'say "monitor ($$) terminated"; exit 143' TERM INT
  say "monitor ($$) up: $(machines | grep -v '^desktop$' | tr '\n' ' ')every ${MONITOR_PERIOD} s (ssh master, ServerAlive 5 s x 2)"
  local m old new d old_since
  while :; do
    if [[ -f "$Q/stop-monitor" ]]; then rm -f "$Q/stop-monitor"; say "monitor stopping"; break; fi
    for m in $(machines); do
      is_local "$m" && continue
      old=$(mstate "$m"); old_since=$(cat "$Q/machines/$m/since" 2>/dev/null)
      if probe "$m"; then new=connected; else new=disconnected; fi
      [[ "$new" == "$old" ]] && continue
      set_mstate "$m" "$new"
      say "$m $new"
      if [[ "$new" == disconnected ]]; then
        event "$m" "disconnected (no ssh answer from $(mcfg "$m" host); queued jobs wait, a running job fails)"
        for d in "$JOBS"/*/; do d=${d%/}; [[ -f "$d/job" && "$(jget "$d" machine)" == "$m" && "$(status_of "$d")" == pending ]] && echo "$m disconnected (since $(ts))" > "$d/blocked"; done
      else
        if [[ "$old" == unknown ]]; then event "$m" "connected"; else event "$m" "connected again (was disconnected since $old_since)"; fi
        for d in "$JOBS"/*/; do d=${d%/}; [[ -f "$d/job" && "$(jget "$d" machine)" == "$m" && "$(status_of "$d")" == pending ]] && rm -f "$d/blocked"; done
      fi
    done
    sleep "$MONITOR_PERIOD"
  done
}

case "${1:-}" in
  submit) shift; submit "$@" ;;
  list|ls) list ;;
  machines) machines_cmd ;;
  next) next_cmd "${2:-desktop}" ;;
  session) shift; session_cmd "$@" ;;
  resources) jev_py resources ;;
  suggest) shift; suggest_cmd "$@" ;;
  bind) bind_cmd "${2:-}" ;;
  unbind) unbind_cmd ;;
  watch) shift; watch_cmd "$@" ;;
  events) events_cmd "${2:-}" ;;
  status) [[ -n "${2:-}" ]] || die "status <id|label>"; status_cmd "$2" ;;
  wait) [[ -n "${2:-}" ]] || die "wait <id|label>"; wait_job "$2" ;;
  result) [[ -n "${2:-}" ]] || die "result <id|label>"; d=$(find_job "$2"); cat "$d/result.txt" 2>/dev/null || echo "job $(job_id "$d") is $(status_of "$d"); no result yet" ;;
  log) [[ -n "${2:-}" ]] || die "log <id|label> [-f]"; d=$(find_job "$2"); if [[ "${3:-}" == -f ]]; then tail -f "$d/output.log"; else cat "$d/output.log"; fi ;;
  cancel) [[ -n "${2:-}" ]] || die "cancel <id|label>"; cancel "$2" ;;
  start) shift; start_cmd "$@" ;;
  stop) stop_cmd "${2:-}" ;;
  worker) shift; worker "$@" ;;
  monitor) monitor ;;
  dir) echo "$Q" ;;
  *) sed -n '2,/^set -uo pipefail/{/^set -uo/!p}' "$0"; exit 2 ;;
esac
