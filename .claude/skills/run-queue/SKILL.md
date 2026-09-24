---
name: run-queue
description: Schedule any game run (bench, drive, preset, verify, multiplayer, Workshop upload, showcase recording) or media encode / stitch through harness/queue.sh on the desktop or one of the laptops (flip, dell, mac) and read its result file. Use whenever a run has to happen - instead of launching run.sh, mp/run.sh, ui-drive.py or an ssh wrapper by hand - and for every ffmpeg / encode-av1-hdr.sh / stitch-*.sh job (they must never overlap a run); also when asked what the queue, a machine or a session is doing.
---

# The run queue

One queue for every session and every computer. You submit; a worker runs your job when the
machine is free; the result lands in a file; you get an event when it ends or when your machine
disconnects. Never launch `harness/run.sh`, `harness/mp/run.sh`, `harness/showcase-record.sh`,
an ssh wrapper, or an encode (`ffmpeg`, `harness/encode-av1-hdr.sh`,
`harness/stitch-*.sh`) yourself while the queue exists: an encode running beside a benchmark corrupts
its readings and the benchmark stretches the encode.

## 1. Where am I, what is going on

```bash
harness/queue.sh machines        # every machine: connected / disconnected / local, queue depth, running job, bound sessions,
                                 # and "this session: <id> -> <machine>" (your affinity)
harness/queue.sh list            # every job: machine, status, order (media / first / ~85s), label, note (blocked reason / exit + Jev verdict)
harness/queue.sh next [machine]  # Jev's plan for a machine: order, estimates, ETAs, each job's intent
harness/queue.sh session         # your session's name / intent / progress as Jev sees them, your pending jobs
harness/queue.sh events          # the last 20 events for this session (jobs ended, machine dropped / came back)
```

Your session id is `$CLAUDE_CODE_SESSION_ID` (automatic). The first `submit` binds the session to a
machine (default `desktop`); after that **every run of this session goes to that machine**. Moving needs
`submit --rebind --machine <m>` or `bind <m>`; do it only when the user asks for another computer.

## 2. Submit

Always say in the message that a run is queued and on which machine. **Jev is the only sorter**
(2026-09-22 evening): when a worker is free, Jev picks the next job from every pending one on that machine,
reading each job's intent, your session's progress and name, how long the job and your session have waited,
your session's age and the job's size estimate (history of the same arguments, else the route / quit-after
seconds; `--size <secs>` corrects it). There is no FIFO or tier rule: tell Jev the truth, it is how it decides.
Every submit **must** carry:

- `--name "<your ListAgents session name>"` — once per session (the name peers message you by);
- `--intent "<why this job, what it decides>"` — e.g. "stock half of the fog A/B; decides whether fogPass ships";
- `--progress "<where your task stands>"` — e.g. "3/5 A/B runs", "last verification before the release commit".

A **`run` also names the resources it tests**: `--resource <r1,r2>` (required; `harness/queue.sh resources`
lists them: game-thread, render-thread, other-cores, gpu, vram, ram, disk, load-time, chunk-arrival, gc,
frame-pacing, visual-parity). Take the arguments from the bench catalog (`harness/queue/benches.json`, every entry
names the resources it tests) instead of writing them by hand:

```bash
harness/queue.sh suggest --intent "does puddleVbo cut the storm frame time" --resource gpu      # Jev: bench=storm, its args
harness/queue.sh submit run --intent "..." --progress "..." --resource gpu --bench auto -- --label <name> --prop puddleVbo=false
harness/queue.sh submit run --intent "..." --progress "..." --resource vram --bench zoom-cycle -- --label <name>
```
`--bench auto` lets Jev pick from the intent + resources; `--bench <name>` takes that entry; the arguments after `--`
are appended (label, A/B `--prop` keys, `--record`; later options win in run.sh). A run with its own arguments still
goes in, and `submit` prints Jev's suggestion beside it (`bench: Jev suggests 'louisville' ... your arguments differ,
fit 0.15`): read it, and cancel + resubmit when the catalog run is what you meant. When a one-off rig becomes a
standard, add it to `benches.json` with honest `resources`.

Keep your progress current between submits with `harness/queue.sh session --progress "..."` (Jev reads the
session's latest). `submit` prints Jev's current place and ETA for the job; `harness/queue.sh next [machine]`
prints the whole plan. When a job starts, the desktop shows a notification (label, session, estimate, intent).
If your job runs past its estimate you get an `overrun: job ...` event (see §3) — look at `log <id> -f`, cancel it
if it hangs. Then:

```bash
# desktop bench / preset / drive (the run.sh arguments go after --, unchanged from the bench-run skill)
harness/queue.sh submit run --name pz-optimization-b9 --intent "baseline of the zoom pass" --progress "1/4 runs" --resource chunk-arrival \
    -- --label <name> --mode bench --flag zoom=max --prop instrument=true --no-dashboard
# (the examples below leave out --name / --intent / --progress / --resource for brevity; submit refuses them without)
harness/queue.sh submit run -- --label <name> --preset storm --prop instrument=true --no-dashboard
harness/queue.sh submit run -- --label <name> --mode drive --flag route=E:1200 --route-seconds 90 --prop instrument=true --no-dashboard --record

# with a goal for Jev (uplift verdict against earlier runs / a baseline json) and visual parity
harness/queue.sh submit run --goal "puddleVbo halves storm frame time" --against storm-stock-1 --parity-against storm-stock-1 \
    -- --label <name> --preset storm --record --prop instrument=true --no-dashboard

# a laptop (binds this session to it on first use); --install opt ships this checkout's build/classes there first
harness/queue.sh submit run --machine flip --install opt -- --label <name> --mode bench --flag zoom=max --prop instrument=true
harness/queue.sh submit run --machine dell -- --label <name> --mode drive --flag route=E:1200 --route-seconds 90 --prop instrument=true
harness/queue.sh submit run --machine mac  -- --label <name> --mode drive --flag route=E:1200 --prop instrument=true

# stock comparison on the desktop: uninstall for the job, reinstalled once the desktop queue drains
harness/queue.sh submit run --install stock -- --label <name>-stock ...      # or keep the classes and pass --prop enabled=false

# desktop-only kinds
harness/queue.sh submit mp -- <label> [stock]                                   # 120 km/h drive against the stock dedicated server
harness/queue.sh submit workshop --notes "Release <commit> (game revision <rev>). ..." -- --tag win-<rev>-<commit>
harness/queue.sh submit cmd --label <name> -- harness/showcase-record.sh storm120 opt

# media: every encode, re-encode, stitch or GIF render (desktop only; shares the queue with the runs, so it
# can never overlap one; goes before every pending run; waits for any ffmpeg / run started outside the queue)
harness/queue.sh submit media --label <name> -- harness/encode-av1-hdr.sh <in.mp4> docs/media/<name>.mp4 [width]
harness/queue.sh submit media --label <name> -- harness/stitch-storm-sbs.sh
harness/queue.sh submit media --label <name> --out docs/media/<name>.mp4 --out docs/media/<name>.jpg -- python3 harness/stitch-showcase.py
harness/queue.sh submit media --label <name> -- ffmpeg -y -i <in> ... docs/media/<out>.gif
```
`--out` names the files to probe when the command does not list them; otherwise every video / image path
in the command that the job wrote is probed.

Options before `--`: `--machine`, `--rebind`, `--install opt|stock|keep|<repo>`, `--goal "..."`,
`--against <run|baseline.json>` (repeatable), `--parity-against <recorded run>`, `--cap N`, `--wait`,
`--notes` (workshop), `--label` (cmd, media), `--out <file>` (media, repeatable), `--size <secs>` (the expected
duration when the estimate is off). `submit` prints the job id, its order tier and size estimate, its dir, the
`result.txt` path and how many jobs go before it on that machine; it starts the worker and the connection monitor when they are not
running. A disconnected laptop still accepts the job; it waits (`blocked: <m> disconnected`) and runs
when the machine is back.

## 3. Wait for it without polling

Pick one:

- `harness/queue.sh submit ... --wait` — blocks, prints `result.txt`, exit code = the job's (0 = done).
- `harness/queue.sh watch --exit-on any` in a **background Bash** — returns the moment something happens
  to this session: exit 0 = one of your jobs ended (the line names it and its result path), exit 3 = your
  machine disconnected, exit 4 = a job of yours is running past its estimate (`overrun:`; it keeps running). Re-run it after each wake-up.
- `Monitor` the job's `status` file (`pending → running → done|failed|cancelled`) or
  `~/.local/state/pzopt-queue/sessions/<your id>/events`.

Never `sleep`-poll `list` in a loop; never touch `~/Zomboid`, the game dir or the laptops while a job
of yours is `running`.

## 4. Read the result

```bash
harness/queue.sh result <id|label>       # result.txt
harness/queue.sh log <id|label> [-f]     # everything the job printed (run.sh output, rsync, wrapper)
harness/queue.sh status <id|label>       # the job spec, blocked reason, pid, exit
```

`result.txt` for a `run`: `status= exit=`, `run_dir=` (a laptop run is collected to
`harness/runs/<machine>-<label>-<ts>/`), `route_complete=` (must be ≥ 1 for bench / drive),
resolution / OpenGL lines, `bench:` (`zoom=2.5` on bench), `opts:` (crashed, launcher, machine), the
whole `analyze.py` output, console errors, then Jev's `judge.py` block ending in

```
verdict=achieved|partial|no_change|regressed|invalid confidence= goal_met= tail_regressed= setup_matches_goal= headroom_finding=
parity=<kind> confidence= parity_maintained= look_needed=      # only with --parity-against
```

Report the verdict line plus the frame-tail and utilization numbers (the objective: consistent frame
time, hardware saturated unless pegged at the cap). `invalid` with a `run_dir` means the setup or logs
do not answer the goal (read the judge block); `invalid (no run)` means run.sh never produced a run dir
(read `log`). `exit=70` = the connection to the laptop was lost mid-run. For `mp`: the mp summary,
`window.py <run>:27` and the same verdict. For `workshop`: the `workshop_log.txt` tail (`Upload
finished ... : OK`), the change-notes page's newest entry, the Jev-judged UI steps; a failure has
`failure.png`. For `media`: an `output=` line per file with size, duration, `codec= pix_fmt= transfer=
primaries= hdr_av1_ok=yes|no`; a `WARNING` under any video that is not AV1 10-bit PQ/BT.2020 — never publish
that file under `docs/media/`, re-encode it with `harness/encode-av1-hdr.sh` (queued) first. Afterwards
`analyze-run` as usual (compare.py, dashboard).

## 5. Events: a machine dropped or came back

An event line `<ts> <machine>: disconnected (...)` reaches every session bound to the machine or with a
job queued there (and `notify-send` on the desktop). What to do: tell the user which machine dropped and
which of your jobs are waiting (`list` shows `blocked: <m> disconnected`); do not cancel them — they
resume on `connected again`. A job that was running when the link dropped is `failed` with `exit=70`:
resubmit it once the machine is back. Detection is ~2 s for a closed connection, ≤ 15 s for a silent
loss (ssh keepalive 5 s × 2).

## 6. Control

```bash
harness/queue.sh cancel <id|label>       # pending: dropped; running: SIGTERM to its process group (run.sh restores latestSave.ini)
harness/queue.sh start [machine...]      # monitor + workers (transient user units pzq-monitor, pzq-<m>); submit does this itself
harness/queue.sh stop [--now]            # after the current jobs / interrupt them; use only when the user asks
```

State lives outside the repo in `~/.local/state/pzopt-queue/` (`jobs/`, `machines/<m>/state`,
`sessions/<sid>/`, `events.log`, `worker-<m>.log`, `monitor.log`). Machines are defined in
`harness/queue/machines.conf` (host, key, checkout, PZ_ROOT, run_args, display env, inhibit, installer);
edit it when a laptop's address or path changes. Details: `harness/CLAUDE.md` "Run queue".

## Do not

- Run `harness/run.sh` (or an ssh wrapper to a laptop) directly while the queue exists, or launch behind a
  running job. The old preflight (`pgrep`, peer messages) is for the queue's own worker now.
- Start an `ffmpeg` / stitch / GIF encode outside the queue: a benchmark may be running or about to start
  on this desktop, and both would read wrong. Only short probes (`ffprobe`, a single frame extraction) are
  fine inline.
- Submit a run for another session's machine, or rebind without being asked.
- `stop --now` a queue with other sessions' jobs running.
- Poll `list` in a loop; use `--wait`, `watch --exit-on any` or `Monitor`.
- Trust an in-game "finished" for the Workshop upload; the result's `workshop_log.txt` line is the proof.
