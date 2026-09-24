# Plan: better frame rate while driving (2026-09-18)

Written after a review of every change, plan, harness and benchmark run in the
repository and a second reading of the decompiled render path. It replaces the
"next steps" in `openspec/changes/max-zoom-driving-frame-time` and the
attribution-only plan in `openspec/changes/frame-time-profile`; those changes
stay as the record of what was built. Numbers below all point at run
directories under `harness/runs/` or at the JFR attribution report
(`harness/attribute.py harness/runs/jfr1-20260915-153918`).

## 1. What the review found

### 1.1 The shipped change works, and does not touch frame time

Wake-on-enqueue + recalc pool (`parallel=true wake=true`, W=4) re-measured on
the native Linux build with NVIDIA GL (`native-both-w4-20260918-181000` against
the stock pair `native-stock-3-20260918-173527` / `-175009`):

| metric | stock | wake+pool W=4 | verdict |
|---|---|---|---|
| chunk latency p50 (enqueue → publish) | 160 ms | 6.2 ms | better 96 % |
| chunk latency p90 | 287 ms | 35 ms | better 88 % |
| chunk latency p99 | 1004 ms | 613 ms | better 39 % |
| frame mean / p99 / p99.9 | 6.2 / 19.3 / 29.9 ms | 6.1 / 19.3 / 28.7 ms | within noise |
| frames > 33 ms | 5.5 | 3 | within noise |
| GPU busy (sysmon) | 80 % | 79 % | within noise |

Same conclusion as on Proton: the streamer was never on the frame-time
critical path on this machine. The change is kept (chunks appear 25× sooner at
the median, which matters at car speed for pop-in), but nothing more should be
spent on the streamer.

### 1.2 Where the slow frames actually go (JFR, game thread, 10 ms samples)

`jfr1-20260915-153918`, 100 s teleport route at zoom 2.5 (the zoom the bench
save was left at, i.e. the driving zoom), 769 frames ≥ 20 ms:

| share of slow-frame samples | where |
|---|---|
| **75 %** | `GameWindow.renderInternal` → **68 % `IsoCell.render`** |
| 20 % | `GameWindow.logic` (of which 15 % Lua VM, 11 % Lua events) |
| 3.4 % | chunk hand-off (`IsoChunkMap.updateInternal` / `doLoadGridsquare`) |
| 1.5 % | lighting hand-off |

Top self-time methods in slow frames: `FBORenderCell.renderTranslucent` 11.3 %,
`ShaderUniformSetter.release` 3.1 %, `IsoGridSquare.IsOnScreen` 3.0 %,
`PZArrayList.indexOf` 2.4 %, `FBORenderCutaways$ChunkLevelData.calculateOccludingSquares`
2.3 %, `IsoSprite.prepareToRenderSprite` 2.3 %, `Shader.initShaderProgram` 1.9 %
(shader programs compiled lazily **during** the route), `TextureDraw.Create`
1.6 %. Ordinary frames look the same with `renderTranslucent` at 15 %: the
translucent pass is the per-frame cost, not only the spike cost.

### 1.3 What the translucent pass is (decompiled `FBORenderCell`)

Chunk textures are cached per chunk-level in FBOs and only redrawn when
invalidated (`FBORenderLevels` dirty flags; the cache is *not* keyed by zoom,
so zooming does not invalidate). Everything classified `Translucent` is
**not** in those textures and is drawn every frame, per player, per on-screen
chunk, per level (`renderTranslucentObjects` → `renderOneChunk_Translucent` →
`renderOneLevel_Translucent`):

- `isObjectRenderLayer_Translucent` returns true for **every `IsoTree`**
  (`isTreeRenderedEveryFrame` is unconditionally `object instanceof IsoTree`),
  for anything animating, on fire, with wind or render effects, windows,
  translucent doors, and walls/objects that are fading because they obscure
  the player.
- `renderOneLevel_Translucent` rebuilds a per-chunk-level list every frame:
  `addAll(items)`, then `contains()`-guarded appends of cutaway window frames
  and translucent squares (`PZArrayList.indexOf` in the profile), then a
  TimSort with a comparator that computes `getMetaGrid().getMaxX() * 256` per
  compare, then `IsOnScreen()` + `shouldRenderSquare()` per square, then
  `renderTranslucent(square)` which walks every object of the square and
  filters by render layer — twice (Translucent, then TranslucentSE), plus a
  third walk for cutaway outlines.
- `renderTranslucent(IsoObject)` recomputes `calculateObjectTargetAlpha_*`
  per object per frame and ends in `renderMinusFloor_NotDoorOrWall` →
  `IsoObject.render` → sprite/shader setup per object. Trees do get batched
  (`FBORenderTrees`), but the batch is flushed whenever a non-tree translucent
  object is met in sort order.

At the driving zoom (2.5 on a 5120×2160 desktop: offscreen 12800×5400,
`chunk_map_width=19`) the on-screen chunk set is the whole loaded map, so the
per-frame translucent work is proportional to *every tree and window within
~19 chunks*, and the vehicle moves through a chunk row every ~0.44 s so the
set is never stable.

### 1.4 GPU side

`native-stock-3-*` (sysmon, nvidia-smi 2 Hz): **GPU 80 % mean, p90 100 %**,
2932 MHz, 174 W; machine CPU 20 %, busiest logical core 52 %, 13 of 16
threads idle; 51.7 % of frames below the 240 fps cap. At the driving zoom the
route is GPU-bound about half the time and CPU-tail-bound the rest. The
translucent pass is on both sides of that: CPU (list building, per-object
setup) and GPU (thousands of individually issued tree/wall sprites per frame
on a 69-Mpixel target).

### 1.5 Other things found while reviewing (all fixed today, see section 5)

- `harness/compare.py` crashed with `StopIteration` on every native run (the
  GL thread is named `main` on the native build, `Render Thread` on Windows).
- `harness/archive/2026-09-24/baseline/native/bench-stock-1/2.json` were Zink runs, so every
  NVIDIA-GL comparison printed "not comparable" warnings; regenerated from the
  two NVIDIA-GL stock runs, Zink pair kept as `bench-zink-*.json`.
- MangoHud's `gpu_load` / `gpu_core_clock` / `cpu_power` columns read 0 or idle
  on the native GL path; `analyze.py` printed them as "gpu_load 0 %" and
  `compare.py` scored them. Zero-signal columns are dropped; sysmon is the
  GPU source.
- `harness/run.sh` armed its `EXIT` trap before three of the `restore_*`
  helpers existed, so an early exit left `pzopt.properties` and the launcher
  JSON modified (found: a stale `pzopt.properties` in the install dir). The
  `gc.log` copy condition was also mis-joined.
- Drive mode had never run. First run (`drive-1-20260918-180741`) was
  rejected: the Apocalypse save has the player on foot. The Lua mod only
  marked the flag file consumed after 90 ticks, which never elapsed before
  the rejected run was back at the menu, so the game sat at the main menu
  instead of quitting. Both fixed: flags are consumed on menu entry, and
  drive mode spawns a repaired `Base.OffRoad` under a player who is on foot
  and seats them as driver (`--flag vehicle=<script>|none`), disables the
  game's auto-zoom for the run (it retargets the zoom every frame in a
  vehicle) and records `vehicle_spawned` / `auto_zoom_option`.
- Drive mode, second round (`drive-rec-1-20260918-184255`, recorded with
  `--record`): keyboard-style input injection does not work because
  `CarController.updateControls()` rewrites `clientControls` from the keyboard
  every frame; the game's cruise control (`setRegulator` /
  `setRegulatorSpeed`) does, and takes the car to 60 km/h in 6 s. Driving
  straight from the spawn square, the car left the field and stopped in the
  tree line at a lake shore after 79 tiles (video frames confirm it). The
  vehicle is now spawned on the nearest road, centred, pointing along the
  longest straight run of street tiles (`--flag heading=auto|N|S|E|W`), and the
  route is shortened to that run; the run logs one telemetry line per second
  (position, speed, engine, regulator, throttle, gear, paused, zoom). The
  road-spawn version is built and installed but **not yet verified**: the last
  attempt (`drive-rec-2`) started while the desktop session was locked and
  the game never got a window; `run.sh` now refuses to launch on a locked
  session.
- `Core.quit()` only asks IngameState to leave the world; the harness now
  escalates (quitToDesktop after 10 s, System.exit after 20 s) so a run can
  never sit in the world waiting for a click.
- The launcher runs the game with `-Xmx3072m` under ZGC on a 30 GB machine;
  6–8 ZGC cycles (2.5–3.2 s wall) land inside every 100 s route. JFR on
  Proton showed 0 allocation stalls and STW pauses of 0.01 ms, so ZGC is not
  in the tail today, but the heap is worth an A/B once driving has a baseline.

## 2. Performance target

Machine: Ryzen 7 9800X3D, RTX 4090, NVMe, 5120×2160 desktop, 240 Hz cap. This
is close to the fastest single-player configuration that exists, so the
target is set where the machine, not the game, is the limit:

| metric (driving route, max zoom, in-game sampler + MangoHud) | target | stock today (teleport route, same zoom) |
|---|---|---|
| frame p99 | **≤ 10.0 ms** (100 fps floor for 99 % of frames) | 19.3 ms |
| frame p99.9 | **≤ 16.7 ms** (nothing slower than 60 fps beyond 1 in 1000) | 29.9 ms |
| frames > 33 ms on the route | **0** | 3–7 |
| frame-to-frame jitter (MangoHud) | **≤ 1.0 ms** | 2.2 ms |
| frames below the 240 fps cap | **≤ 25 %** | 51.7 % |
| GPU busy when not at the cap | **≥ 90 %** (the GPU is the limit, not a stall) | 80 % |
| chunk latency p50 | keep ≤ 10 ms | 6.2 ms |

Provisional until the first valid driving baseline exists (section 4, step 0):
if driving turns out to be GPU-bound at 100 % for most of the route, the p99
target moves to the GPU's own frame cost and the CPU-side goal becomes "no
frame slower than the GPU frame by more than 2 ms".

## 3. Optimization plan, in order

Each item is a switchable, parity-checked override in the pattern of the
shipped change; each is benchmarked against the driving baseline with
`compare.py` and shipped only on a tail improvement beyond noise.

### 3.1 Translucent pass: build once per invalidation, not once per frame (biggest, CPU)

Override `zombie.iso.fboRenderChunk.FBORenderCell` (and its
`PerPlayerData`/`FBORenderLevels` touch points). Per chunk-level, keep a
prepared list of `(square, object)` pairs already filtered to the Translucent /
TranslucentSE / cutaway-outline layers and sorted with the world-order key
(precomputed `worldRight`, no per-compare lookup). Rebuild only when the
cached square lists for that level are cleared/rebuilt (`clearCachedSquares`,
i.e. when the chunk texture itself is invalidated) or when an object's render
layer changes (fade-in / obscuring-player sets change — those are per-player
sets the cell already tracks, so the invalidation hook is where they mutate).
Per frame: iterate the prepared list, keep the existing `IsOnScreen` /
`shouldRenderSquare` / target-alpha checks so visible output is identical.
This removes `contains()`, the sort, the `getMetaGrid()` comparator, and two of
the three object walks per square per frame. Setting `translucentCache`
(already in `Config`). Parity: a dev trace of ordered (square, object,
visible) tuples, uncached vs cached, over the driving route.

### 3.2 Trees out of the per-frame path (biggest, CPU + GPU)

`isTreeRenderedEveryFrame` makes every tree translucent, so forests are
re-issued as individual sprites every frame. Trees only *need* per-frame
drawing when they are in the player stencil (`isTranslucentTree`), fading, or
wind-animated. Plan: with wind sprite effects off (the option is already a
branch in `isObjectRenderLayer_Translucent`), classify a tree as `Translucent`
only if `isTranslucentTree` / fading / has effects, and let the rest bake into
the chunk texture like any other static object; when a tree *becomes*
translucent (player walks under it) invalidate that chunk-level (the code
already does this for `checkTreeTranslucency` with flag 4096). Measured
separately from 3.1 because it changes what is baked, and it is the item most
likely to move the GPU number. Setting `treesInChunkTexture`.

### 3.3 Warm the shader and texture caches before the route (spikes)

`Shader.initShaderProgram` and `TextureDraw.Create` / texture loading appear in
the ≥ 33 ms bucket: programs compile on first use and tile textures load on
first sight, mid-drive. Plan: at world load, compile every shader in
`media/shaders` the renderer can pick (the set is small and static) and
pre-touch the texture pages for the tiles present in the loaded chunk map.
This is a `zombie.core.opengl.ShaderManager` / `TextureID` hook, no parity
risk, and it can be measured by the ≥ 33 ms count alone.

### 3.4 Cutaway occluders (CPU, medium)

`FBORenderCutaways$ChunkLevelData.calculateOccludingSquares` and
`cutawayVisit` are 4 % of slow-frame samples; they run whenever the player's
square changes, which while driving is every few frames. Plan: skip the
cutaway recompute entirely while the camera character is in a vehicle and
outdoors (nothing to cut away), recompute once on exit. Setting
`cutawayWhileDriving`.

### 3.5 Lua / PZDashboard (CPU, measured A/B, no code)

Lua is 15–20 % of slow-frame time; PZDashboard streams state every tick.
Measured (`native-stock-nodash-20260918-181319` vs the stock pair): frame p99
19.3 → 18.1 ms (better, 6 %, just outside noise), p99.9 / spikes / mean within
noise. Small but real; worth a dashboard-side throttle, not an override here.

### 3.6 JVM collector (measured A/B, no code)

`--gc g1` (`native-stock-g1-20260918-182229`): p99 19.3 → 16.8 ms (better
13 %) but frames > 33 ms 5.5 → 15 (worse) and 17 GC events in the window: G1
trades the p99 for pauses. Not adopted. A larger ZGC heap (`-Xmx8g`) remains
to be tried on the driving route.

### Not planned

- Lower render resolution / dynamic resolution: it would meet the GPU target
  by lowering the objective; out of scope unless 3.1–3.2 leave the route
  GPU-bound at 100 %.
- Moving `IsoCell.render` work to another thread: GL context ownership; not
  reversible within the override pattern.
- Any further streamer work.

## 4. Order of work

0. **Driving baseline** (harness ready, road spawn unverified — first run
   `--record` and check `recording.mp4` + the `harness: drive t=` lines): three
   `harness/run.sh --label drive-stock-N --mode drive --source-save Apocalypse/2026-09-18_12-18-03 --flag route=E:900 --route-seconds 60 --prop parallel=false --prop wake=false --prop instrument=true`
   runs, one with `--jfr --jfr-period 5` and one with `--game-profiler`;
   `harness/attribute.py` and `harness/sections.py` on them. Confirm the
   section-1.2 ranking holds while driving, fix the target (section 2), and
   store the pair in `harness/archive/2026-09-24/baseline/native/drive-stock-{1,2}.json`.
1. 3.1 translucent cache — build, parity trace, three A/B runs.
2. 3.2 trees — same gate, on top of 3.1.
3. 3.3 warm-up — spike count gate.
4. 3.4 cutaways, 3.5 and 3.6 A/Bs.
5. Update `docs/results.md` (Grafana holds every run's metrics), long play session on the final build.

## 5. Harness and tooling changes made in this review

- `harness/compare.py`: no crash on missing threads; thread lookup by either
  render-thread name; MangoHud GPU rows only when they carry signal.
- `harness/analyze.py`: zero-signal MangoHud utilization columns dropped.
- `harness/run.sh`: restore helpers defined before the trap; gc.log copy fixed;
  `--record` (gpu-screen-recorder, monitor capture, `recording.mp4` in the run
  directory); refuses to launch on a locked desktop session.
- `harness/mod/.../pzopt_harness.lua`: continues the save as soon as the menu
  accepts input (no fixed wait); quits the process when the flag file carries
  `started=1` (written by the Java harness once the world was up) — Lua state
  cannot be used because the file is reloaded between the menu's two
  OnMainMenuEnter events.
- `src/pzopt/pzopt/Harness.java`: drive mode spawns/seats a vehicle on the
  nearest road when the save has the player on foot, drives it with the
  game's cruise control (`--flag kmh`, default 60), pins max zoom with
  auto-zoom off, measures distance from the route start, logs telemetry every
  second, records `vehicle_spawned`, `cruise_kmh`, `auto_zoom_option`, and
  escalates the quit if the world does not close.
- `harness/archive/2026-09-24/baseline/native/`: NVIDIA-GL stock pair; Zink pair renamed.
- `harness/dashboard.py` → `benchmark-progress.html` (+ `.json`): the progress dashboard, built from
  the run directories (replaced by Grafana, `harness/grafana/`, on 2026-09-24; last snapshot in `docs/archive/2026-09-24/`).
