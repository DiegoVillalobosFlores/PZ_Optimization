# Driving through town: the game-thread limit (follow-up of the car-jitter pass, 2026-09-26)

The car-jitter pass (`docs/findings-car-jitter-2026-09-26.md`) made the car's motion smooth on every frame that is
on time: world judder median 0.5-0.9 px, car 0.4-1.0 px, the rubber band gone. What is still visible while driving
is frames that are *late*: the display shows the previous frame for a second refresh (8.3 ms extra at 120 Hz), then
the world catches up by two steps. With the motion fixed, that is the whole remaining stutter. This file is the
starting point for that optimization.

## What was measured

Flip (AYANEO Flip, 1080x1920 @ 120 Hz, vsync on, its own tab file: adaptive vsync, AO, sun shadows, FSR 1), fixed
build, `harness/frame-causes.py <run> --cap-ms 8.333 --no-jfr` on `--prop instrument=true` runs:

| route | late frames (> 8.73 ms) | game step p99 | blamed on |
|---|---|---|---|
| straight road east of Rosewood, 120 km/h, zoom 1 | 0.2 % (6-18 s window) | - | - |
| same road, zoom 2 | 5.7 % (221 of 3,894) | 10.5 ms | render submit 124, game step 69, swap 28 |
| Rosewood, 120 km/h, zoom 1 (`cj-t120-ctl`) | 10.4 % (443 of 4,267) | 13.7 ms | game step 274, swap 119, render submit 50 |
| same, repeat (`cj-t120-lock`, lock inactive) | 8.5 % (364) | 13.0 ms | game step 213 |
| the 90-degree corner into Rosewood, 100-120 km/h | 22.6 % in the town stretch | - | - |

Late frames against the chunk-texture bakes of the same game step (Rosewood, 120 km/h):

| bakes in the step | share of frames | late | game step mean |
|---|---|---|---|
| 0 | 64 % | 3.9 % | 8.36 ms |
| 2 | 7 % | 11.7 % | 8.42 ms |
| 4 | 7 % | 27.8 % | 8.54 ms |
| 6 | 1.5 % | 39.4 % | 9.33 ms |
| 8+ | 2.4 % | 61.5 % | 11.08 ms |

The kinds in those steps: `create` (a new chunk level), `object`, `redraw`, `cutaway`, `lighting`, `trees`. The route
average hides it: over the whole drive the game thread is 58 % busy (update 32 %: player 8, chunk map 5, UI 4,
IsoWorld 2, vehicles 2, zombies 2; render 20 %: bakes 3, translucent 3, AO flush 2, chunk checks 2, characters 2;
lighting JNI 5 %) and 42 % in the limiter wait. The late frames are bursts on top of that.

## The limitation

- A frame is late when the game thread's step goes over the refresh interval. On the flip at 120 Hz that interval is
  8.33 ms and a normal driving step is ~4.5 ms busy, so a burst of ~4 ms is enough. The bursts come with chunk arrival:
  every chunk that enters the grid at 120 km/h (~70 hand-offs a second) brings its bake preparation (building the
  per-level render lists, occlusion counts, lighting reads), its lighting and its objects, and they land in whichever
  frame the streamer hands them over.
- The bake budget does not help: it limits the GPU-side bakes per frame (`bakeFrameBudget` 8 -> 4 with the hard cap
  12 -> 6 made no difference: 405 late vs 443 / 364), not the game-thread preparation. `treeBakeMaxChunksPerSec=24`
  (trees drawn per frame instead of baked into short-lived textures): 221 / 216 -> 205 / 175 at zoom 2, small.
- Present pacing cannot hide it: the frame is late before it is handed off. A frame-rate cap below the refresh (60 fps
  on the 120 Hz panel) turns each late frame into a 3-refresh + 1-refresh pair on the flip (the CPU limiter drifts
  against the vblank; `vsyncLock` could not hold the pacing there because XWayland / KWin re-time the presents).
- On the desktop (the town-drive pass, 2026-09-24/25) the same wall was reached at 240 Hz: "the game thread's broad
  bursts (tile render + bake prep ~1.8 ms, update ~1.5 ms extra in late steps); next would be structural (bake prep /
  chunk arrival off the game thread)". The flip reaches it at 120 Hz.

## What is missing to attack it

1. **Per-frame attribution of the late steps.** The 25 Hz stack sampler (`pzopt-gamethread.out`) and the route-average
   tree cannot say what a single late step did. `harness/frame-causes.py` can fold async-profiler wall samples inside
   the late steps (`--asprof event=cpu,interval=2ms,wall=2ms`), but on the flip the run never starts:
   `harness/asprof/libasyncProfiler.so` is a symlink into the desktop user's home and the queue syncs the link, not
   the library. Fix the sync (copy the real file, or resolve the link in the queue's rsync) first; the desktop runs work.
   Alternative without a profiler: per-frame section timers around chunk-map update, bake preparation, lighting and
   the Lua UI update in `pzopt-pacing.out`.
2. **Which chunk events coincide with the late steps**: log the frame of each streamer hand-off (`pzopt.ChunkHandoff`)
   and each `create` bake next to the pacing rows.

## Candidate directions (to measure one by one)

- Spread chunk hand-offs: at most N new chunks entering the grid per frame (the streamer already queues them; the
  hand-off to the game thread is what lands in one frame). `chunkHandoffDivisor` exists for the desktop; tune it per
  refresh rate or make it frame-time aware (hand off only while the step is under ~60 % of the interval).
- Bake preparation off the game thread: the per-level render-list build and occlusion counts for a new chunk level on
  a worker, the game thread only publishing the result (the town-drive pass named this as the next structural step).
- Chunk-map update (5 % average) and lighting JNI (5 %) in the bursts: time them per frame first.
- Player update (8 % while driving): what the IsoPlayer update does in a vehicle every frame (controls, sounds,
  vehicle UI); cheap to profile, maybe cheap to trim.
- Frame-time aware deferral in general: anything optional that a late-looking step could push to the next frame.

## Rigs

- `--prop devDriveJitter=true`, `harness/drivejitter.py <run> --present` (motion per displayed frame; the display
  intervals show which frames were shown twice).
- `--prop instrument=true`, `harness/frame-causes.py <run> --cap-ms 8.333 --no-jfr` (late frames, stage split, bakes per
  step).
- Routes: Rosewood at 120 km/h `--flag path=8010,11204.5/8106,11204.5/8106,11965.5 --flag kmh=120`, at 60 km/h
  `--flag path=8010,11204.5/8106,11204.5/8106,11650 --flag kmh=60`; run-to-run noise on the late count is ~20 %, so
  alternate A/B pairs.
