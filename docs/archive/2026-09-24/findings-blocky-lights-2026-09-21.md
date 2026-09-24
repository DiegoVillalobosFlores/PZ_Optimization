# Blocky lights: a moving light source through held chunk-texture re-bakes (2026-09-21, 17:45–18:40)

Maintainer video (streamable `bxkjzk`, 22 s, 60 fps): a hand torch swept in a garage at night
shows its beam as a patchwork of tile- and chunk-stepped pieces that do not follow the turn.
Reproduced on the night-torch spinning bench and on the 120 km/h night drive; parity with
stock reached on both. All runs at 5120x2160, NVIDIA GL, `--no-dashboard --record`, 60 fps
(the in-game cap of the bench save's options), zoom 1 for the torch runs, max zoom (2.5) for the
drive.

## What it was not

Stock and the optimized build render floors with the same four per-vertex light colours
(`IsoGridSquare.getVertLight` → `FloorShaperDeDiamond`); `LightingJNI` is stock apart from
the harness see-all flag and the puddle hook. The luminance histograms of the two recordings
over the same route window are identical (mid band 1.59 M vs 1.52 M pixels), so the ramps are
there: the artefact is *when* a chunk texture shows the light, not *how*.

## Two mechanisms, both timing

1. **Held lighting-only re-bakes.** `lightingRebakeMs=250` (a texture dirtied only by lighting
   is not re-baked within 250 ms of its last bake) and the lighting-only spread
   (`lightingRebakeBudget=8` starts a frame, a level may stay stale `lightingRebakeMaxFrames=30`
   frames) were tuned for sky drift and lightning flashes. A torch sweep dirties the chunk
   levels in its beam every pass by tens of levels; every held chunk kept the beam at a past
   angle, one angle per chunk. Stock re-bakes every dirty level the frame the pass lands.
2. **The lighting-refresh queue.** `lightingBudget=8` refreshes at most eight chunks' square
   light info a frame and queues the rest. The lighting engine rewrites every per-square dirty
   bit when its next pass lands, so a queued chunk read after that finds its squares "not dirty"
   and keeps the previous light (`getVertLight` reads a cache that only `cacheLightInfo()` →
   `JNILighting.update()` refreshes, and that needs the bit). At 120 km/h the headlight beam
   had chunk-sized dark patches. Reading a non-dirty square anyway is no cure: the engine
   answers with the previous pass (a forced read produced a checkerboard, run `bl-drive-fix3`).

## Fix (`pzopt.LightDirt`, default on; `docs/override-edits.md` for the edits)

- Each square sums how far its light moved since its level was last baked
  (`LightingJNI.JNILighting.pzoptLightChanged`); past `lightingStrongDelta` (6/255) the level is
  *strong* and re-bakes this frame, skipping the 250 ms hold and the spread. Sky drift (1/255 a
  tick) stays weak and held as before.
- A per-frame move of the player's global light (colour mods, ambient, night, sky level) past
  `lightingGlobalDeltaPct` (2 %) is a flash or a fast dusk: the spread stays on for
  `lightingRebakeMaxFrames + 2` frames, so the lightning fix of the storm pass is unchanged. A
  count of strong levels a frame was tried first and rejected: turning moves the vision cone
  (darkMulti) across the whole screen, so a sweep marked as many levels as a flash (run
  `bl-torch-fix1`: 87 false "flash" windows in 25 s).
- `FBORenderCell.pzoptFlushPendingLighting`, called from `LightingJNI.update` before the pass
  lands, drains the lighting-refresh queue while the dirty bits are still valid. The budget
  now spreads the refreshes over the frames between two passes and the remainder lands in the
  frame the pass arrives, never lost.

## Runs and metric

Single-frame edge counts were scene-alignment noise (recording start from a whole-second
mtime). The metric that separates the runs is timing-independent: hard luminance jumps (> 40)
between consecutive recording frames at 30 fps over the route window; stale chunks land as
chunk-sized jumps, a stock-like sweep changes smoothly. `/tmp/blocky/burst.py` (torch, whole
frame) and `burst_box.py` (drive, the beam box ahead of the car).

| Run | Build | Hard jumps / frame (mean, p90) |
|---|---|---|
| `bl-torch-stock` | stock (`enabled=false`) | 7 667, 16 729 |
| `bl-torch-opt` | before | 9 885, 22 313 |
| `bl-torch-lb0` | before, `lightingBudget=0` | 9 870, 23 338 |
| `bl-torch-nohold` | before, `lightingRebakeMs=0 rebakeBudget=0` | 7 921, 17 491 |
| `bl-torch-fix1` | strong-level count heuristic | 8 991, 20 661 |
| `bl-torch-fix2` | global-light detection | 7 913, 17 924 |
| `bl-torch-fix4` | **final (LightDirt + flush)** | **7 660, 16 335** |
| `bl-drive-stock` | stock, SportsCar, beam box | 387, 922 |
| `bl-drive-fix` | LightDirt without the flush | 861, 1 575 |
| `bl-drive-lb0` | same, `lightingBudget=0` (clean by eye) | 584, 1 102 |
| `bl-drive-fix3` | forced read of queued squares (checkerboard) | 752, 1 305 |
| `bl-drive-fix4` | **final** | **603, 950** |

The drive residual over stock sits at the car itself (the beam origin is baked at the
15-30 Hz lighting-pass rate while the car model moves every frame, the same in stock; the
worst fix4 frame pairs show a smooth beam), not in the beam. Frame times at the 60 fps cap:
mean 16.7 ms, p99 18.3-19.4 ms in every optimized run.

## Ambulance confirmation (19:27–19:31)

The maintainer's vehicle: `Base.VanAmbulance` (maxSpeed 70) with headlights and the lightbar in
mode 1 (new drive flag `lightbar=0..3`; its rotating red/blue world lights change every frame).
Same route, same beam-box metric: stock 320 / p90 591, before (`lightingStrongDelta=100000
lightingGlobalDeltaPct=100 lightingFlush=false`) 962 / 2 318, **fix 559 / 863**. Before shows
chunk-checkerboard patches inside the beam in every sampled frame; the fix matches stock,
lightbar glow included (runs `bl-amb-before`, `bl-amb-fix`, `bl-amb-stock`).

## Video

`docs/media/blocky-lights-before-vs-after.mp4` (`harness/stitch-blocky-lights.sh`, AV1 HDR,
3840x790, 60 s): torch spin, SportsCar drive and ambulance drive, each BEFORE | AFTER | STOCK,
aligned at the harness route start.

## Notes

- Race cars have decorative headlights (`Vehicles.Create.Headlight_Racecar` creates no spot
  light): the night drive uses `--flag vehicle=Base.SportsCar` (maxSpeed 120, real
  headlights). New drive flag `headlights=on|off|auto` (auto = on when `time_of_day` is a night
  hour, so daytime baselines keep their light state).
- The `--shot-at` captures still never show the beam (player held still); use `--record`.
- Daytime cost (18:54–18:59): the vision cone is a strong change in daylight too, so more
  levels re-bake at once than with the 30-frame spread. Uncapped 120 km/h drive
  (`--prop uncappedFps=true --option uiRenderOffscreen=true --no-mangohud`): fix defaults
  `bl-dayu-fix` 471.9 fps, p99 7.7 ms, p99.9 11.8, max 18.2 (2 205 strong re-bakes in 39 s);
  old holds `bl-dayu-old` (`lightingStrongDelta=255 lightingGlobalDeltaPct=100`; a full vision
  change still reaches 255, 1 397 strong re-bakes) 470.8 fps, p99 7.7, p99.9 11.2, max 16.1.
  Within noise. At the 60 fps cap (`bl-day-fix`) p99 18.4 ms, p99.9 20.9.
