---
name: bench-run
description: Launch a hands-off Project Zomboid measurement run (bench, drive, parity, verify) with harness/run.sh, respecting the shared-machine and run-etiquette rules. Use for any request to benchmark, measure, A/B, smoke-test or reproduce a frame-time or chunk-latency number.
---

# Launch a measurement run

## 0. Use the queue (preferred, 2026-09-21; the `run-queue` skill has the full workflow)

```bash
harness/queue.sh submit run --wait -- --label <name> --mode bench --flag zoom=max --prop instrument=true --no-dashboard
harness/queue.sh submit run -- --label <name> --preset storm --prop instrument=true --no-dashboard   # then: harness/queue.sh wait <name>
harness/queue.sh submit run --goal "puddleVbo halves storm frame time" --against storm-stock-1 --parity-against storm-stock-1 \
    -- --label <name> --preset storm --record --prop instrument=true --no-dashboard          # Jev verdict + visual parity in the result
harness/queue.sh submit run --install stock -- --label <name>-stock ...   # uninstall first, reinstalled when the queue drains
harness/queue.sh submit mp -- <label> [stock]                             # the multiplayer drive against the stock server
harness/queue.sh submit run --machine flip -- --label <name> --mode bench ...   # a laptop: first submit binds this session to it
harness/queue.sh list; harness/queue.sh machines                          # who is ahead, what is blocking, which laptop is connected
harness/queue.sh watch --exit-on any                                      # background Bash: woken when a job finishes or a machine drops
```
A session always runs on the machine it first submitted to (`--rebind` to move); laptop jobs are rsync'd
out, run there through the machine's conf entry (`harness/queue/machines.conf`) and collected back as
`harness/runs/<machine>-<label>-*`. A disconnected laptop's jobs wait and the session is told the moment
the monitor sees the drop.
The worker does the preflight below itself (waits while a game / run.sh outside the queue / a locked
desktop holds the machine), runs jobs one at a time in submit order and writes
`~/.local/state/pzopt-queue/jobs/<id>-<label>/result.txt` (run dir, validity lines, analyze.py output, and
Jev's `verdict=` line from `judge.py`: without `--goal` it judges the run as a stand-alone measurement, with
`--goal`/`--against` it judges the uplift; `--parity-against` adds `parity-judge.py`'s `parity=` line).
`--wait` prints it and exits with the job's status; otherwise `Monitor` the job's `status` file. Still
announce to the user that a run is queued. Steps 1-3 below are for a run outside the queue only.

## 1. Preflight (every time, when not using the queue)

```bash
pgrep -fa '[P]rojectZomboid64'      # game already running?
pgrep -fa '[h]arness/run.sh'        # another session mid-run? (exclude your own)
loginctl show-session $XDG_SESSION_ID -p LockedHint   # locked screen stalls the game
scripts/pzopt.sh status             # which classes are installed (stock run needs uninstall)
```
If a peer session is busy, use ListAgents / SendMessage and wait; never launch behind their
game. If someone is editing `harness/run.sh`, copy it to `harness/.run-snapshot.sh` and launch
that. Keep the Steam performance monitor off (caps at ~160 fps).

## 2. Announce

Tell the user in the message before the launch: a run is starting, leave the game alone (no
clicking Continue, no launching, no screen lock). One run at a time, never a batch.

## 3. Launch

Bench (camera route, max zoom):
```bash
harness/run.sh --label <name> --mode bench --flag zoom=max --prop instrument=true --no-dashboard
```
Scene presets (night with / without the torch, thunderstorm, heavy fog, both; spinning Rosewood route, 25 s):
```bash
harness/run.sh --label <name> --preset night-torch --prop instrument=true --no-dashboard   # or night-dark, storm, fog, storm-fog, helicopter
```
Presets only add flags (`time_of_day`, `torch`, `visible`, `weather`, `fog`, route, turn, zoom); later `--flag`s override
(`--preset storm --flag fog=0.5` = storm with half fog).
Check `weather=`/`torch=`/`night_strength=` in `pzopt-bench.out` and compare preset runs only with
runs of the same preset.
Camera zoom steps on the bench route (2026-09-22; `zoom=` sets the start level, `zoom_cycle=` seconds between one-notch
steps in then out, `zoom_span=9` = whole 0.25..2.5 range per step, `zoom_jump=true` = no ease; read with `harness/zoomsteps.py`;
stock runs need `--option frameRate=240 --option uncappedFPS=false` because stock resets a saved uncappedFPS to a 60 lock):
```bash
harness/run.sh --label <name> --mode bench --flag route=S:450 --flag zoom=0.25 --flag zoom_cycle=1.5 --prop uncappedFps=true --prop instrument=true --no-dashboard
```
Drive, 60 km/h A/B route (queue bench `drive-60`; `drive-120` = the same at `kmh=120`, `--route-seconds 60`):
```bash
harness/run.sh --label <name> --mode drive --flag path=8010,11204.5/9210,11204.5 --flag kmh=60 --flag max_seconds=120 \
  --route-seconds 90 --option frameRate=240 --option uncappedFPS=false --prop instrument=true --no-dashboard --record
```
Add `--jfr`
plus `--jfr-setting jdk.JavaMonitorWait#threshold=0ms --jfr-setting jdk.ThreadPark#threshold=0ms`
for wait analysis, `--renderer zink` for the Zink A/B, `--env JAVA_TOOL_OPTIONS=-Dzomboid.wayland=1`
for native Wayland, `--option uiRenderOffscreen=true` for the offscreen UI, `--gc g1` for a GC A/B.
Stock runs: `scripts/pzopt.sh uninstall` first, reinstall after. `--launcher auto` is fine.
Use the DEFAULT bench save for drive runs (no `--source-save`).

Path drive (2026-09-24, no retries needed): the 120 km/h Rosewood route = queue bench `drive-120-south`
(`--mode drive --flag path=8010,11204.5/8106,11204.5/8106,11965.5 --flag kmh=120 --flag zoom=max --route-seconds 60 --option frameRate=240 --option uncappedFPS=false`: pins the 240 cap, a peer run once left frameRate=60 in options.ini).
`pzopt.DrivePilot` follows the centreline, plans corner / obstacle / end braking and stops on the last point; any
other route: `harness/drive-path.py route --from X,Y --via "Street A>Street B"` or `plan --from X,Y --describe
"..."` (Jev picks) prints the `path=` flag. Keep the first point clear of parked cars (the bench save's own car sits
at 8002,11204; an overlap is rejected at route start). Validate with `harness/drive_check.py <run> [--against <run>]`
(judge.py and so every queue result already carry its `drive=` line; a failed drive makes the verdict invalid).

## 4. Validate before trusting

```bash
python3 harness/analyze.py harness/runs/<label>-*/
grep -E 'route complete|Desktop resolution|OpenGL version' harness/runs/<label>-*/console.txt
grep zoom= harness/runs/<label>-*/pzopt-bench.out
```
Valid = mangohud, sysmon and thread lines present, `route complete`, zoom 2.5 on bench,
resolution/renderer match the baseline. On drive runs look at `recording.mp4` frames (ffmpeg
tile sheet) before concluding anything. Then `python3 harness/dashboard.py`.

## Do not

- edit run.sh mid-run; pkill with a self-matching pattern; use verify mode for anything
  interactive; compare across resolution / renderer / zoom / launcher / dashboard state;
  use Proton-era baselines for frame time; add `no_display` or long lingers.
