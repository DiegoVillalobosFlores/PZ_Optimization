# Driving micro-stutter ("rubber banding" of the car), 2026-09-26

The maintainer's report: while driving, the car stutters and jitters, a kind of micro rubber banding. Goal: remove it
completely. Worktree `../PZ_Optimization-carjitter` (branch `car-jitter`, from origin/master 8304fb3). Runs are on the flip
(AYANEO Flip, 1080x1920 @ 120 Hz eDP).

## Rig

- `pzopt.DriveJitter` (`--prop devDriveJitter=true`): one row per game frame, taken after the frame's update and before its
  render (`GameWindow.frameStep`), with the frame start, the simulated step, the Bullet steps run, the vehicle's physics
  and drawn positions, the camera offsets the world is drawn at (whole pixels and before the truncation), the driving
  look-ahead, how far the camera sits from the driver's current position ("stale"), and the car's position on screen.
  Log `pzopt-drivejitter.out`.
- `harness/drivejitter.py <run>`: for the world scroll (camera) and the car on screen, the residual of every frame
  against a quadratic fitted over the frames within ±80 ms, in screen pixels (RMS, p50, p99, max), and per frame the
  step against the fit's step: `stall` (< 50 %), `back` (against the motion: the rubber band), `jump` (> 150 %).
  `--vsync HZ` judges against display times on a refresh grid instead of frame starts.
- Checked offline against a simulated car (`/tmp/cj_sim.py`, 120 km/h at zoom 1): 100 Hz steps without interpolation
  give a world residual RMS of ~7 px at 240 / 144 / 116 / 60 fps (240 fps: 58 % stalled frames, 42 % double steps;
  116 fps: 14 % stalled); interpolated, 0.3 px (the whole-pixel camera).

## Causes found in the code

1. **100 Hz physics, drawn without interpolation.** `WorldSimulation.updatePhysic` runs whole 10 ms Bullet steps and
   carries the remainder to the next frame; the vehicle, its passengers and (through the driver) the camera are drawn
   at the state after the last whole step. At 120 fps a frame in six shows no motion at all; at 240 fps the car moves
   on 5 frames of 12; at 60 fps it alternates one and two steps.
2. **Driving look-ahead in whole pixels, paced by a millisecond clock.** `PlayerCamera.update` (pan camera while
   driving) moves the look-ahead towards a target that is itself truncated to whole pixels, by a step scaled with
   `System.currentTimeMillis()` deltas (4 or 5 ms at 240 fps: ±12 % per frame), and truncates the result again.
3. **Whole-pixel camera.** `PlayerCamera.getOffX/Y` truncate the camera to offscreen pixels (models are snapped to the
   same grid through `fixJigglyModels`); ±0.5 offscreen px.
4. **Simulation clock = frame starts.** `FPSTracking.frameStep` gives the frame the previous frame's measured length;
   with vsync the frame is shown on the refresh grid, so any wobble of the frame starts is motion error.

5. **Camera placed before the car moves (session-dependent).** `PlayerCamera.update` runs inside `IsoPlayer.update`;
   the driver's seat position is set in `BaseVehicle.update`. When the player is updated before the vehicle (object list
   order, fixed at load), the camera sits one physics step behind on every stepping frame and catches up on the others:
   the car jumps ~23 px (zoom 1) back and forth on screen, 20 times a second at 120 fps. Seen in 1 of 4 baseline
   sessions (`stale=83 %`); the others had `stale=0 %`. This is the rubber band.

## Results (flip, 120 Hz vsync, 120 km/h path drive, 3,700 frames each)

Residual RMS / p99 in screen px (lower is smoother), `back` = frames the thing moves against its motion.

| run | zoom | world RMS / p99 | car RMS / p99 | world stall | car back |
|---|---|---|---|---|---|
| cj-base-120 (stock) | 1 | 6.46 / 12.2 | 8.84 / 22.9 | 11.2 % | 19.5 % |
| cj-camlate (`driveCameraLate`) | 1 | 6.35 / 11.9 | 0.76 / 1.65 | 14.6 % | 3.6 % |
| cj-interp (`vehicleSmooth=interp`) | 1 | 0.91 / 2.02 | 0.79 / 1.71 | 0.1 % | 4.2 % |
| cj-extrap (`vehicleSmooth=extrap`) | 1 | 0.92 / 1.91 | 0.76 / 1.69 | 0.1 % | 4.5 % |
| cj-hz120 (`physicsStepHz=120` + camera late) | 1 | 1.06 / 2.21 | 0.85 / 1.91 | 0.1 % | 4.1 % |
| cj-frame (`physicsStepMode=frame` + camera late) | 1 | 0.96 / 2.09 | 0.85 / 1.90 | 0.1 % | 4.2 % |
| cj-z1-full (interp + `driveLookSmooth` + `frameClockSmooth`) | 1 | 0.88 / 2.10 | 0.67 / 1.59 | 0.1 % | 1.0 % |
| cj-z05-base (stock) | 0.5 | 12.63 / 23.0 | 1.08 / 2.82 | 15.5 % | 18.3 % |
| cj-z05-interp | 0.5 | 1.39 / 3.32 | 1.14 / 2.84 | 0 % | 16.4 % |
| cj-z05-full | 0.5 | 1.28 / 2.69 | 0.98 / 1.97 | 0 % | 4.8 % |
| cj-z2-base (stock) | 2 | 3.25 / 6.48 | 0.62 / 1.58 | 14.4 % | 0 % |
| cj-z2-interp | 2 | 0.94 / 3.22 | 0.63 / 1.57 | 0 % | 0.3 % |
| cj-z2-full | 2 | 0.76 / 2.59 | 0.58 / 1.48 | 0 % | 0.3 % |

- The physics-side fixes all land on the same floor (~0.9-1.0 px at zoom 1): what is left is the whole-pixel camera
  (±0.5 offscreen px = ±1 screen px at zoom 0.5), the look-ahead and frame-time hitches (the p99/max outliers are
  11-23 ms frames). Interpolation keeps Bullet exactly as stock at every frame rate (120 Hz steps only match a 120 fps
  display; per-frame steps change the physics' step with the frame rate), so it is the one to ship.
- The look-ahead fix is what removes the car's own backward frames (4.2 -> 1.0 % at zoom 1, 16.4 -> 4.8 % at 0.5).
- Zoomed out (2) the flip misses more refreshes (11.6 % of frame starts off the 8.33 ms grid vs 1.2 % at 0.5): there the
  tail is frame pacing.

### Real display times (swap log + present.txt), the maintainer's scenarios

`harness/drivejitter.py --present`: each frame judged at the vblank it reached the screen (pzopt-driveswap.out maps
every swap to its game frame, present.txt gives the completion; both CLOCK_MONOTONIC). Fixed = `vehicleSmooth=interp
driveLookSmooth cameraScreenPixels frameClockSmooth`. World residual p50 / car residual RMS, screen px:

| scenario (flip, 120 Hz vsync, zoom 1 unless noted) | stock | fixed |
|---|---|---|
| straight, 120 km/h, pan camera off (game default) | 5.05 / 0.40, stall 16.7 % | 0.66 / 0.44, stall 1.4 % |
| straight, 120 km/h, zoom 2, pan on | 2.48 / 4.45 (car back 17.5 %) | 0.51-0.66 / ~0.6 |
| Rosewood, 60 km/h, pan on | 2.73 / 3.08 | 0.82 / 1.01 |
| Rosewood, 60 km/h, pan off | 2.71 / 3.75 | 0.61 / 0.43 |
| Rosewood, 120 km/h, pan on | 3.94 / 7.08 (car p99 24.7) | 0.77 / 0.73 (car p99 2.0) |
| 90-degree corner, pan on | 3.05 / 6.81 (car back 21.5 %) | 0.73 / 0.64 (back 1.1 %) |
| 60 fps cap, straight, pan on | 6.86 / 12.65 (car back 27.9 %) | 1.03 / 0.87 (back 0.1 %) |

- The stale camera hit 2 of 6 stock sessions on the open road and every town session (28-56 % of frames): moving
  through town reorders the updates, so the rubber band comes and goes while driving.
- What is left is the frames the flip shows for two refreshes: 97-98 % of frames are shown one refresh after the
  previous, the rest follow a late frame (12-40 ms). Straight road at zoom 1: 0.2 % late; Rosewood at 100-120 km/h:
  22.6 % late (chunk arrival, bakes, lighting on the flip's iGPU). That is frame time, not motion: with those frames
  excluded the fixed runs sit at 0.5-0.7 px.
- 60 fps cap on the 120 Hz panel: 2.5 % of frames stay 3 refreshes then 1 in stock and fixed builds alike (the CPU
  limiter's clock drifts against the vblank) -> `vsyncLock` (swap interval refresh / cap, limiter 3 % faster).
- `frameClockSmooth` against real display times: 2.51 -> 2.10 and 1.68 -> 1.62 px RMS (zoom 2 pairs): within noise;
  the refresh-grid model favoured it by construction.
- `treeBakeMaxChunksPerSec=24` at zoom 2: late frames 221 / 216 -> 205 / 175, motion residual unchanged.
- `bakeFrameBudget=4 bakeFrameBudgetHard=6`, Rosewood 120 km/h: 405 late frames vs 443 / 364 in the two controls
  (run-to-run noise ~20 %): no effect. The town tail is the game step (274 of 443 late frames, p99 13.7 ms), growing
  with the bakes of the frame (4 % late without bakes, 28 % with 4, 62 % with 8+): bake preparation and chunk arrival
  on the game thread, the structural wall of the town drive pass.
- `vsyncLock` (flip, adaptive vsync, XWayland copy presents): 60 fps cap, frames shown for 1 or 3 refreshes 5.6 ->
  5.4 %; 120 fps cap, the 3 %-fast limiter queued frames and 202 were replaced before being shown (2 without). The
  compositor re-times the presents, so the lock cannot hold the pacing there. Off.
- async-profiler on the flip: `harness/asprof/libasyncProfiler.so` is a symlink into the desktop user's home; the queue
  syncs the link, not the library, so `--asprof` runs never start there.

## Defaults (worktree)

On: `vehicleSmooth=interp` (the camera re-centring of `driveCameraLate` included), `driveLookSmooth`,
`cameraScreenPixels`. Off (measured, kept as keys): `extrap` (same result, overshoots at crashes), `physicsStepHz`,
`physicsStepMode=frame` (same floor, physics change with the frame rate), `frameClockSmooth` (noise level against real
display times), `vsyncLock`. Multiplayer clients: the smoothing is off (their vehicles follow the server's
interpolation; not measured).

## Regression: no crash damage with vehicleSmooth (fixed 2026-09-26 afternoon)

Workshop report after the c17a039 release: "no more damage to cars in a crash regardless of speed ... the car just
bounces and there's no sound/damage to car or player". `Bullet.getVehiclePhysics` (libPZBullet64) writes each
vehicle's collide flag to the array and clears it in the same call, so it is a once-per-read latch. `VehicleSmooth.read`
called it twice a frame (before the last step, after the steps) ahead of the stock read in
`WorldSimulation.updateInternal`, which then always saw 0: `jniIsCollide` never set, `BaseVehicle.crash` /
`damageObjects` never ran. Fix: the flags our reads take are handed to the stock read (`VehicleSmooth.collide`).

Rig: `--mode drive --flag path=8010,11204.5/8080,11204.5/8130,11185/8230,11147 --flag ram=true --flag kmh=120
--flag lat_accel=100 --flag max_seconds=14` (the car leaves KY-60 at ~104 km/h and hits an object at 8116,11190;
telemetry `crashes= cond= hp=`). Mac runs, same path and impact speed:

| run | build | crash counter | part condition sum | driver health |
|---|---|---|---|---|
| crash-bug-mac2 | c17a039..3dd6fba (no fix) | +0 | 3500 -> 3500, car stuck on the object | 100 -> 100 |
| crash-fix-mac2 | fix | +1 | 3500 -> 3182, object destroyed | 100 -> 89.1 |
| crash-stock-mac1 | `enabled=false` | +1 | 3500 -> 3184, object destroyed | 100 -> 51.4 |
| crash-fix-frame-mac1 | fix, `physicsStepHz=120 physicsStepMode=frame` | +1 | 3410 -> 3140 | 100 -> 70.0 |

(The driver's injury is random per crash, `addRandomDamageFromCrash`.) flip run crash-fix-1 (tab file with
`physicsStepMode=frame`): +1, 3500 -> 3324, 100 -> 62.4 at ~60 km/h.
