# Findings, 2026-09-18: harness re-validation on the native build, first frame-time baseline

Objective restated by the maintainer this session: **consistent frame time, and the CPU
and GPU driven to the max whenever the game is not smoothly pegged at 240 fps.**
Everything below is what the harness measured after it was made trustworthy
again; nothing is from observation. Run directories are under `harness/runs/`,
summaries under `harness/baseline/native/`.

## 1. The harness was measuring nothing (fixed)

- Steam swapped the Proton depot for the **native Linux build** at 12:00 today.
  The install is now `…/ProjectZomboid/projectzomboid/` and the user dir is
  `~/Zomboid`. `harness/run.sh` had been half-migrated: it launched the native
  game but collected `console.txt` from the dead Proton prefix. Every run
  directory made earlier today (`drive-fixture-*`, `mangohud-full-confirm-*`,
  `crash-check-*`) holds a **byte-identical copy of the Sep-15 `stock4k-2`
  console** (same md5) or nothing. None of them is a measurement.
  Fix: `scripts/pz-env.sh` detects the layout for every script and the harness.
- The Sep-15 Proton numbers (`harness/baseline/bench-stock-*.json`,
  `stock4k-*`) are not comparable with native runs: different GL driver path,
  different JVM (Oracle → Azul Zulu 25), same desktop resolution.
  `compare.py` now refuses silently comparing across a different
  "OpenGL version" / "Desktop resolution" line (warning per candidate).
- Env vars from `run.sh` never reach a game started through the running Steam
  client. The maintainer set the game's Steam launch options to
  `<repo>/harness/steam-launch.sh %command%`; `run.sh` writes
  `~/Zomboid/pzopt-launch.env` for the run (deleted afterwards). Without the
  file the wrapper is a no-op.
- MangoHud on this LWJGL/GL game only hooks when `libMangoHud_opengl.so` is
  preloaded (the dlsym shim and `MANGOHUD=1` alone do nothing — the process
  had the shim mapped but no overlay and no log). Its CSV is written **only if
  `log_duration` elapses while the game is still running**, so the route is
  now on a schedule (`--lead 75` s after launch) and the log covers exactly the
  route; the game closes ≤ 5 s after the route. MangoHud's own `gpu_load`
  column reads 0 in this path; GPU utilization comes from `harness/sysmon.sh`
  (nvidia-smi + /proc/stat, 2 Hz, independent of MangoHud).
- `restore()` in `run.sh` ran under `set -e` and a failing `kill` in an `&&`
  list aborted it, leaving the launcher JSON, `latestSave.ini`, the flag file
  and `~/.config/MangoHud/MangoHud.conf` modified after two runs. The original
  MangoHud config was overwritten by the second backup and is **lost**;
  `~/.config/MangoHud/MangoHud.conf` is a reconstruction of the ~35 keys that
  had been seen. `restore()` now does `set +e`.
- Native GL crashes at display creation (`SIGSEGV` in
  `libnvidia-glcore.so.615.71.09`, `hs_err_pid74662.log` kept in
  `native-stock-2-20260918-171900/`) happened on 1 of 7 launches. `run.sh`
  keeps the hs_err, records `crashed=`/`attempts=` in `run.opts` and retries a
  start-up crash (`--retries`, default 2).
- The previous launch options forced **Mesa Zink** on the RTX 4090. Two
  runs on Zink (`native-stock-1`, `native-stock-2`) are kept for contrast; via
  the wrapper Zink now dies silently at "VSync: OFF" (`native-zink-1`), so it
  is not an A/B leg yet.
- Lua mod: `quit_after` never armed when the game auto-continued the save by
  itself (Build 42 always does), so a `verify` run never quit. Fixed.
- Drive mode: starts the engine if the fixture was saved with it off, has a
  `max_seconds` timeout (`route_status=timeout`), brakes at the end. Still
  **unexercised**: no run has reached it; whether
  `Apocalypse/2026-09-18_12-18-03` has the player in a vehicle is unknown.

New per-run data: `pzopt-threads.out` (per-thread CPU over the route),
`sysmon.csv` (machine CPU %, busiest core %, game process CPU, GPU %, SM clock,
power, VRAM), MangoHud consistency figures (stdev, frame-to-frame jitter,
1 %-low, share of frames below the 240 fps cap), `environment` (GL driver,
desktop, JVM, revision) in `analyze.py`; matching rows in `compare.py`.

## 2. First trustworthy baseline (stock behaviour: `parallel=false wake=false`)

Teleport route `E:400,S:500,W:400,N:500` at 18 tiles/s from 8002,11204, 100 s,
desktop 5120x2160, borderless, `frameRate=240`, vsync off, PZDashboard on.

| run | renderer | fps mean | p50 | p90 | p99 | p99.9 | >33 ms | MainThread | render thread | Lighting | process |
|---|---|---|---|---|---|---|---|---|---|---|---|
| native-stock-1 | Mesa Zink 26.2 | 136 | 6.1 ms | 12.7 | 22.1 | 32.9 | 12 | 74 % | 31 % (`main`) | 27 % | 3.0 cores |
| native-stock-2 | Mesa Zink 26.2 | 138 | 6.0 ms | 12.5 | 21.8 | 34.1 | 16 | 74 % | 31 % | 27 % | 3.1 cores |
| native-stock-3 | NVIDIA GL 615.71 | 162 | 4.7 ms | 10.6 | 19.4 | 30.7 | 4 | 83 % | 66 % | 27 % | 2.8 cores |

`native-stock-3` also has MangoHud (16,251 frames in the window, agrees with
the in-game sampler: mean 6.2 ms both, p99 22.2 vs 19.4 ms) and sysmon:

- **GPU 80 % mean, p90 100 %**, SM clock 2932 MHz, 174 W (p90 192 W)
- machine CPU 20 %, busiest logical core 52 % (p90 73 %), game process ≈ 2.9 cores
- consistency: stdev 3.9 ms, frame-to-frame jitter 2.2 ms, 1 %-low 45 fps,
  **51.7 % of frames below the 240 fps cap**

Thread shares are CPU time / wall time. Note that `MainThread.mainLoop` and
the render loop **busy-spin with `Thread.yield()`** when the frame cap holds a
frame back or no render state is ready, so those shares are upper bounds on
real work; the time they are *not* on-CPU is genuine blocking (game thread in
`SpriteRenderer.pushFrameDown` waiting for a free state slot, render thread in
`Display.update` = buffer swap).

## 3. Reading

1. On NVIDIA GL at 5120x2160 the route is **GPU-bound about half the time**:
   GPU p90 = 100 % while the CPU side has 13 of 16 threads idle. Half the
   frames hit the 240 cap; the other half are the GPU (and the render thread
   waiting on it) — the p99 tail (19–22 ms) is where CPU-side stalls still
   show (chunk `doLoadGridsquare`, lighting hand-off, ZGC: 6 cycles / 2.5 s
   wall in the window, no pause figures without JFR).
   For the objective this means: at this resolution the GPU side *is* used;
   the frame-time lever is **less GPU work per frame** (the offscreen FBO
   scales with zoom — at max zoom it is worse than measured here) and the
   CPU-side tail, not more parallelism.
2. Zink costs ~16 % fps and raises the tail (p99.9 33–34 vs 31 ms) — but it was
   also what made MangoHud appear "active" before (Vulkan layer). Keep NVIDIA
   GL as the baseline leg.
3. The streamer changes (wake + pool) are still expected to be within noise on
   frame time here, as on Proton; not re-measured today.
4. The chunk-latency numbers are unchanged by the platform move
   (46 chunks/s, recalc 1.2 ms/chunk, queue wait p50 ~150–175 ms stock).

## 4. What was not done (stopped on request)

- `--game-profiler` run to split MainThread time by section
  (`native-profiler-1-*` was killed mid-route; ignore that directory).
- Second NVIDIA-GL stock run (noise floor for `compare.py --baseline
  harness/baseline/native` needs `bench-stock-1/2.json` from the *same*
  renderer; the JSONs there are currently Zink, Zink, NVIDIA — rename/regenerate
  before comparing).
- Drive fixture validation, max-zoom A/B, dashboard A/B, translucent-cache
  work (`openspec/changes/max-zoom-driving-frame-time`): tasks 1.x–2.x remain
  open; only the harness prerequisites are in place.

## 5. How to run now

```sh
# Steam launch options must be:  <repo>/harness/steam-launch.sh %command%
harness/run.sh --label stock-nv-1 --mode bench --prop parallel=false --prop wake=false --prop instrument=true
harness/run.sh --label drive-1   --mode drive --source-save Apocalypse/2026-09-18_12-18-03 --flag route=E:900
python3 harness/analyze.py harness/runs/<dir>
python3 harness/compare.py harness/runs/<dir>... --baseline harness/baseline/native
```
