# World sounds: the `WorldSoundManager.addSound` hitch (2026-09-22)

Report from the maintainer: a sizeable frame drop (10–20 fps, more with a bigger radius) inside
`WorldSoundManager.addSound()` for large-radius sounds, i.e. the meta events (helicopter, house alarms).

## What a call does (stock 42.20)

`WorldSoundManager.addSound(source, x, y, z, radius, volume, …)`, the full overload every other
overload and the Lua global `addSound` end in, on the game thread:

1. `WorldSound.init`: fields, the `OnWorldSound` Lua event (no listener in the vanilla Lua or the
   installed mods), then `FishSchoolManager.addSoundNoise(x, y, radius / 6)` — a walk of every square of
   the disc of radius `radius / 6` (the whole square `(radius / 3)²` is iterated, the disc test is a
   square root), with the procedural fish-point roll, the no-fish-zone list and a boxed
   `TLongIntHashMap` probe per square, writing "no fish for 180 game minutes" for each fish or chum
   point. Quadratic in the radius: 31k squares for the 600-tile house alarm, 445k for a 2000 radius.
2. Attach the sound to every chunk in the square of side `2 · radius · hearingMultiplier` around it by
   asking the cell for each world chunk coordinate: `(2 · 600 / 8)² = 22,500` `getChunk` calls for the
   alarm at normal hearing, 202,500 at pinpoint hearing (×3), 251,001 for a 2000 radius; only the loaded
   grid (361 chunks here) ever answers.
3. `ZombiePopulationManager.addWorldSound` → native `n_worldSound` (a hand-off, ~0 ms measured).

Why it is a sustained drop and not one hitch: `zombie.iso.Alarm.update()` calls
`addSound(this, x, y, 0, 600, 600)` **every frame** for the ~49 s a house alarm rings, so 1 + 2 are a
per-frame cost while an alarm rings. The helicopter (`Helicopter.update`, radius 500) fires about once
per 10 s, the meta gunshot / scream events once (600), vehicles and vehicle alarms 100–150 every
frame or rate-limited (`worldSoundUpdateLimit`). The 5000-radius thunder call (`handleThunderEvent`)
has no caller in 42.20. The game also adds ~12,000 tiny sounds a second on the bench route (zombies,
footsteps…): 1.24 M calls in 100 s at 0.0006 ms each, 0.7 % of the game thread, not a problem.

## Measurement rig

`--flag sound=R` (`pzopt.Scene`): a stock `addSound(null, x, y, 0, R, R)` every frame (`sound_every=N`),
from the moving player or from one still square with `sound_fixed=true` (an alarm), `addSound` timed
on the game thread; `sound_parts=true` times the fish walk and the popman call with an extra call
each; `--prop devWorldSoundTiming=true` adds section totals inside the override (lock, init = Lua +
fish, chunk loop with chunks asked / hit, list + popman) split at radius 100. Summary every 5 s in the
console, `sound_stats=` in `pzopt-bench.out`. Bench route, uncapped, `--no-dashboard`, hearing = 5
(sandbox default, = normal ×1).

## Numbers (desktop, 100 s bench route, uncapped)

| run | sound | `addSound` mean / max ms | of which fish walk | chunk loop (asked / hit) | fps mean | p99 ms |
|---|---|---|---|---|---|---|
| `sound-off` | none | — | — | — | 619.7 | 5.96 |
| `sound-600-stockpath` | 600 every frame, moving, `worldSoundFast=false` | 0.415 / 16.8 | 0.364 | ~0.05 (22,500 / 360) | 532.9 | 6.34 |
| `sound-600-fast` | same, fix on, moving source | 0.364 / 35.2 | 0.36 (memo misses: the source moves) | 0.005 (361 / 360) | 603.6 | 6.02 |
| `sound-600-sections2` | 600 every frame, still source, fix on | **0.007** / 7.1 | 0.001 | 0.006 (361 / 360) | 640.5 | 5.63 |
| `sound-2000-stockpath` | 2000 every frame, still, `worldSoundFast=false` | **4.392** / 17.7 | 3.962 | 0.430 (251,001 / 359) | **169.6** | 11.57 |
| `sound-2000-fast` | same, fix on | **0.008** / 12.8 | 0.003 | 0.005 (361 / 360) | 644.1 | 5.68 |

Thunderstorm preset (spinning Rosewood route, 25 s, 4 lightning strikes, still 600-radius sound every frame,
maintainer's request of 2026-09-22 02:00):

| run | `addSound` mean ms | fps mean | p99 / p99.9 / max ms | > 33 ms | game thread | GPU |
|---|---|---|---|---|---|---|
| `sound-storm-stock` (`worldSoundFast=false`) | 0.443 (fish 0.391, chunks 0.051: 22,801 asked / 359 hit) | 192.2 | 34.8 / 71.0 / 109 | 58 | 91 % busy, world sounds 9 % of it (`isNoFishZone` 7 %) | 68 % |
| `sound-storm-fast` | 0.009 (361 / 359) | 290.8 | 15.4 / 47.1 / 98 | 19 | 85 %, world sounds 1 %, hand-off wait 13 % | 90 % |

With the stock path the game thread is the wall (GPU 68 %); with the fix the GPU is (90 %). The storm route
varies run to run, so the fps headline is indicative; the per-call 0.443 → 0.009 ms and the 9 % → 1 % thread
share are exact. Inside the stock walk, `isNoFishZone` (the per-square scan of the no-fish-zone rectangles) is
most of the cost, not the procedural roll: an exact follow-up for a *moving* big source would pre-select the
zones intersecting the walk's box once per call.

120 km/h thunderstorm drive (`--mode drive --flag route=E:1200 --flag kmh=193 --flag zoom=max --route-seconds 60
--flag weather=storm`, 6 strikes, same still alarm sound, `--no-mangohud`):

| run | `addSound` mean ms | fps mean | p99 / p99.9 / max ms | > 33 ms | game thread | GPU |
|---|---|---|---|---|---|---|
| `sound-storm-d120-stock` (`worldSoundFast=false`) | 0.418 (fish 0.373, chunks 0.044: 22,801 asked / 229 hit) | 377.2 | 9.4 / 40.2 / 80.7 | 19 | 80 % busy, world sounds 18 % of it, hand-off wait 20 % | 95 % |
| `sound-storm-d120-fast` | 0.005 (241 / 241) | 393.7 | 9.1 / 37.2 / 85.0 | 18 | 69 %, world sounds 2 %, hand-off wait 32 % | 97 % |

GPU-bound on both sides on the desktop, so the gain shows as game-thread headroom (80 → 69 % busy, the
freed 11 % becomes hand-off wait) rather than frames (+4 %); the > 33 ms frames are the lightning re-bake
stalls in both. On a CPU-bound machine (the laptop, or the spinning route above) the same cost is frames.

## The helicopter itself (`--preset helicopter`, runs `heli-*`)

`--flag helicopter=true` (`pzopt.Scene`): the stock event (`IsoWorld.helicopter.setTarget(player)`) at the route start,
moved next to the player at once (stock spawns it 1000 tiles out, ~60 s away), so it hovers / searches over the spinning
route and adds its 500-radius sound at the stock random cadence from its position — a *moving* big source, the case the
memo cannot cover. `devWorldSoundTiming` logs one line per big sound with its cost and `epoch_ms`, matched against the
overlay's per-frame log. Four runs (spinning Rosewood route, 25 s):

| run | calls in the route | per call mean / median / max ms | init (Lua + fish walk) | chunk loop (asked) | worst frame within ±2 of a call | route fps | p99 / p99.9 / max ms | > 33 ms |
|---|---|---|---|---|---|---|---|---|
| `heli-stock-2` (`worldSoundFast=false`) | 14 | 0.296 / 0.288 / 0.427 | 0.244 | 0.042 (15,876) | 10.0 | 370.0 | 11.9 / 23.0 / 43.5 | 3 |
| `heli-stock-3` | 17 | 0.326 / 0.292 / 0.603 | 0.267 | 0.052 (15,876) | 18.4 | 376.9 | 12.0 / 23.8 / 44.3 | 4 |
| `heli-fast-2` (memo + chunk clamp) | 18 | 0.427 / 0.278 / 2.654 | 0.404 (one 2.6 ms outlier) | 0.014 (361) | 22.0 | 372.9 | 11.8 / 23.3 / 45.1 | 4 |
| `heli-fast-3` (+ the exact fast walk) | 16 | **0.102** / 0.091 / 0.265 | 0.082 | 0.013 (361) | 7.7 | 377.1 | 11.4 / 24.5 / 49.0 | 3 |

The helicopter's `addSound` is not a hitch in single player: 14–18 calls in 25 s at 0.3 ms (stock) — 0.005 s of a 25 s
route — and no frame near a call is worse than the route's ordinary tail; the > 33 ms frames of every run are the
lightning-free chunk-load stalls of the spinning route, seconds away from any call. The four routes are within noise of
each other (370–377 fps). The remaining per-call cost of a moving source is the fish walk; with the exact fast walk
(no-fish rectangles pre-selected once per call, integer-exact disc test, chum probe only when chum points exist;
`tests/pzopt/FishNoiseWalkTest.java` compares it against the stock loop key for key) it is 0.08 ms, 3× cheaper.

Recorded storm spin route with the alarm sound, stitched side by side in
`docs/media/bench-storm-house-alarm-stock-vs-fixed.mp4` (`harness/stitch-alarm-storm-sbs.sh`): `alarm-storm-rec-stock`
241.8 fps, p99 17.8 / p99.9 52.6 / max 122.5 ms, 16 frames > 33 ms, 0.47 ms per call; `alarm-storm-rec-fast` 290.1 fps,
p99 14.1 / p99.9 28.2 / max 51.2 ms, 2 frames > 33 ms, 0.05 ms per call (the recording costs both sides some fps against
the unrecorded pair above). Three-way version with the stock game (every optimization key off, per-key list,
uncapped, same alarm: `alarm-storm-rec-stockgame` 37.9 fps, p99 105 / p99.9 132 / max 157 ms, 178 frames > 33 ms, 1 % low
8 fps, game thread 92 %: puddles 25 %, weather fx 15 %, translucent 11 %) in
`docs/media/bench-storm-house-alarm-stock-vs-optimized-vs-fixed.mp4` (`harness/stitch-alarm-storm-triple.sh`).

Recorded E:400 teleport bench (18 tiles/s, 22 s) in a thunderstorm with the helicopter over the route, stitched side by
side in `docs/media/bench-e400-storm-helicopter-stock-vs-fixed.mp4` (`harness/stitch-heli-storm-sbs.sh`):
`heli-storm-e400-stock` 435.0 fps, p99 7.8 / p99.9 32.5 / max 89.5 ms, 10 frames > 33 ms, 14 helicopter calls at 0.33 ms;
`heli-storm-e400-fast` 445.9 fps, p99 7.3 / p99.9 30.1 / max 90.6 ms, 10 frames > 33 ms, 20 calls at 0.10 ms. Within run
noise: the helicopter alone does not make a visible difference, as the numbers above predicted.

(`sound-600-fast-fixed` and `sound-600-sections`, 0.37 ms with the fix on and a still source, were the
first memo: the ~20 tiny sounds the game adds per frame flushed its 8-entry ring before the next alarm
call; the memo now ignores walks under radius 8.)

The cost is quadratic in the radius, as the loops are: 0.4 ms per call at 600, 4.4 ms at 2000. At 600
fps a 0.4 ms per-frame cost is 620 → 533 fps; on a CPU 2–3× slower at 100 fps it is the 10-fps drop
reported; a modded 2000-radius sound every frame is 620 → 170 fps.

## Fix: `worldSoundFast` (default on, Optimizations tab "Cheap world sounds")

- `WorldSoundManager.addSound` override: the radius rectangle is intersected with the union of the
  active players' loaded chunk ranges before the chunk walk. The cell returns null outside that union,
  so the chunks that get the sound are exactly stock's (asked 361 / hit 360 instead of 22,500–251,001 / 360).
- `FishSchoolManager.addSoundNoise` override: a repeat of a call with the same centre, radius and game
  minute rewrites the same keys with the same expiry, i.e. is a no-op; the last eight such calls
  (radius ≥ 8 only) are remembered and skipped. The memo is dropped whenever the noise or chum maps are
  cleared, purged, replaced by the server's copy or a chum point is added, so any call that could write
  something new runs. Saved / transmitted fishing data are untouched.

A moving big source (the helicopter) still pays its walk once per new square, now 0.08 ms instead of 0.27
(the exact fast walk above); restricting it to loaded water chunks would change which far fish points get
disabled, so the walk stays complete.

Prose of both edits: `docs/override-edits.md` (2026-09-22 entries).
