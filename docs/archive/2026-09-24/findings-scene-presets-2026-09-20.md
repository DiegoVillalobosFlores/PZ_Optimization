# Scene presets: night, torch, thunderstorm — findings (2026-09-20, 14:54–15:25)

What was asked: bench presets for night with the player flashlight on and off, and for a
thunderstorm. What came out of testing them: night and the torch cost nothing on the spinning
Rosewood route; a thunderstorm costs 3.4× the frame time with neither CPU nor GPU saturated, and
is the largest unexplained frame-time gap measured on this route so far.

Setup for every run: `harness/run.sh --preset <name> --prop instrument=true --no-dashboard`
(spinning game-thread route `route=S:450 turn=90 zoom=max`, 25 s requested / 31 s driven), desktop
5120x2160, NVIDIA GL 4.6 (615.71.09), launcher steam, 240 fps cap, defaults of commit b0d4fe6
(persistentVbo and translucentTilesInChunkTexture on). Frame numbers are the in-game overlay log
over the route window (`analyze.py`, `overlay:` block). Runs under `harness/runs/preset-*`.

## 1. Numbers

| run | preset | fps mean | p50 | p90 | p99 | p99.9 | max | notes |
|---|---|---|---|---|---|---|---|---|
| preset-night-dark-4-20260920-152119 | night-dark | 282 | 3.5 ms | 4.9 | 10.4 | 17.9 | 47 | no `--shot-at` |
| preset-night-torch-7-20260920-152006 | night-torch | 283 | 3.5 ms | 4.9 | 10.0 | 18.7 | 46 | no `--shot-at` |
| preset-night-dark-2-20260920-150254 | night-dark | 282 | 3.5 ms | 4.6 | 9.6 | 19.9 | 269 | `--shot-at 12` (the 269 ms is the capture) |
| preset-night-torch-4-20260920-151139 | night-torch, `visible=true` | 284 | 3.5 ms | 4.6 | 9.4 | 19.8 | 263 | `--shot-at 12` |
| preset-storm-1-20260920-152349 | storm | **83** | **12.1 ms** | 16.1 | **43.3** | 81.2 | 571 | `--shot-at 12`; 40 frames > 33 ms |

Reference on the same build and route in daylight, clear weather: ~279–283 fps (`bs-defaults-1`,
`docs/results.md` 13:55 section).

Utilization in the storm run (`sysmon` + overlay over the route window): game thread 94 % of a
core (p10 86, p90 100), render thread 69 %, process 381 % of one core = 24 % of the machine, GPU
76 % busy by the overlay's timer query / 49 % by nvidia-smi, GPU clock pinned at 600 MHz and
36 W. Nothing is at the wall.

## 2. Findings

**F1 — Night is free.** Forcing 01:00 (`night_strength=1.0`, `time_of_day=1`) changes nothing on
this route: 282 fps, p99 10.4 ms, the same as daylight. The night darkening is a shader-side
multiplier, not extra work per square.

**F2 — The player torch is free.** A lit `Base.HandTorch` in the primary hand, cone sweeping with the
90°/s turn, registered in the native lighting every pass (`jniTorches` in the `torch check` console
line): 283 vs 282 fps, tails within noise. The beam was visible on screen in every night-torch run
(maintainer watching).

**F3 — A thunderstorm is a 3.4× frame-time regression and the tail is the worst on this route.**
83 fps mean, p50 10.1 ms, p99 43 ms, 40 frames over 33 ms in 31 s. Where the work is, from the
periodic `translucent pass per frame` line of the storm run vs `preset-night-dark-4`:

| counter per 1800-frame period | clear (night-dark-4) | storm | ratio |
|---|---|---|---|
| chunk bakes | 1526 | 8156 | 5.3× |
| `lighting` dirty flags | 1112 | 6535 | 5.9× |
| `redraw` flags | 567 | 2481 | 4.4× |
| `create` flags | 176 | 1114 | 6.3× |
| `objectAdd` flags | 170 | 1050 | 6.2× |
| `cutaways` flags | 95 | 910 | 9.6× |
| budgeted lighting rebakes | 9393 | 6669 | — (budget saturated, `held` 13185) |

Reading: the storm's climate values move every frame (the `WeatherPeriod` case-3 ambient / daylight /
cloud overrides interpolate continuously, and every lightning strike sets
`dirtyRecalcGridStackTime = 1` for ~100 frames), which marks chunk lighting dirty across the whole
view; `lightingRebakeMs=250` / `rebakeBudget=4` then re-bake chunk textures at the budget ceiling all
the time, on top of the rain FX pass. `objectAdd` / `create` ×6 says the rain also adds per-square
objects (puddles/splashes) that trigger bakes. This is the "fps < 240 and hardware not saturated"
case the objective names: game thread at 94 % but the machine at 24 %, GPU at 76 %.

**F4 — The `--shot-at` capture rig is blind to the player light.** Torch and dark captures are
pixel-identical after the 2 s stand-still hold before the capture, even though the beam is on screen
during the route. Judge lights live or from `--record`, never from the shots. Half the runs of the
afternoon (night-torch-2/3/5/6, night-dark-1/3, both `-stock-1` runs) were spent chasing the beam
in screenshots; wrong verdict, corrected by the maintainer.

**F5 — Harness details that bit.** (a) `ClimateManager.forceDayInfoUpdate()` before the first
climate tick NPEs (`currentDay` null); an exception inside `WAIT_WORLD` repeats every frame and the
game sits on the loading screen with the tips cycling (preset-night-torch-1). Removed; a scene
exception now rejects the run. (b) The bench save's character holds a pistol with an always-on
weapon light; `Scene` strips every light item before `torch=on|off`. (c) An invisible bench player is
what `LightingJNI.playerSet` receives as ghost mode; the torch draws anyway, so presets keep the
player invisible and `visible=true` stays opt-in. (d) The screenshot itself is a ~260 ms frame
(`Core.TakeFullScreenshot`); never compare `max` between runs with and without `--shot-at`.

## 3. Storm profile with the game's own profiler (15:34–15:38, uncapped, no Steam)

Runs `storm-prof-stock` / `storm-prof-opt`: `--preset storm --launcher direct --game-profiler
--no-mangohud --no-dashboard --prop uncappedFps=true --option uiRenderOffscreen=true`. Stock is the
per-key-off prop set of `u400show-stock` (a real uninstall cannot go uncapped: "Uncapped" is ours).
`harness/sections.py <run> --thread game|render` (now per thread; before this it merged the two
recorded threads into one frame keyspace and reported whichever header the glob found first).
The probes cost ~8 % (70 fps here vs 83 without them).

| | stock | optimized |
|---|---|---|
| overlay fps mean / p99 / p99.9 | 32 / 143 ms / 356 ms | 70 / 39 ms / 81 ms |
| game thread frame (`GameWindow.frameStep`) | 28.9 ms | 13.4 ms |
| `performRenderTiles` | 18.5 | 7.5 |
| `FBORenderCell.puddles` (game thread, per frame) | 5.3 | **4.5** |
| `FBORenderCell.renderOneChunk` (bakes) | 7.7 | 1.4 |
| `FBORenderCell.calculateRenderInfo` | 4.9 | 0.45 |
| `FBORenderCell.translucentNonFloor` | 4.0 | 0.16 |
| `renderWeatherFX` | 2.6 | 1.7 |
| `GameWindow.logic` | 3.6 | 2.4 |
| render thread frame (`RenderThread.renderStep`) | 26.0 ms | 13.2 ms |
| `buildStateDrawBuffer` (GL submission) | 16.4 | 8.7 |
| `waitForReadyState` (render waits for game) | 9.4 | 4.4 |
| utilization (overlay) | game 99 %, render 69 %, GPU 55 % | game 98 %, render 64 %, GPU 66 % |

**F6 — The storm's biggest remaining game-thread item is not the lighting rebakes, it is the
puddle pass.** With the adopted defaults the bakes are down to 1.4 ms/frame; `FBORenderCell.puddles`
stays at 4.5 ms of a 13.4 ms frame (34 %), untouched by any override. `renderPuddles` (stock code,
`FBORenderCell.java:5000`) walks every on-screen chunk on every level 0..`maxHeight` (puddles
quality is 0 = high in `options.ini`, so `getPerfPuddles()==0` and `maxZ` is not clamped to 0),
filters the cached puddle squares (`shouldRenderSquare`, `IsOnScreen`, `getFloor`, `shouldRender`),
then `IsoPuddles.render` calls `shouldRender()` again, `updateLighting` (4 `lightverts`) and packs
32 floats + 4 `IsoDepthHelper.getSquareDepthData` per square into `RenderData` — every frame, for
every wet exterior square in view (~8k at max zoom on 5120x2160). Nothing in that per-square work
changes between frames except the lighting and the camera jiggle offset. The render thread then
re-uploads the whole vertex set (`renderSome`: `VBOs.next()`, `vertices.put`, index rebuild) and draws
it with the hq puddle shader; that upload+draw is inside the 8.7 ms `buildStateDrawBuffer` the
profiler does not break down further (`renderTiles` 0.7, `Render Style` 0.4, translucents 0.1).

**F7 — Both threads sit at ~13 ms, the pipeline is balanced-slow.** Game 13.4 ms busy 98 %, render
13.2 ms of which 4.4 ms waiting on the game thread and 8.7 ms submitting, GPU 66 %. Halving the
puddle pass on the game thread alone gives at most ~70 → ~95 fps; the render-thread submission has to
shrink with it (persistent puddle VBO, no per-frame index rebuild) for the storm to reach the cap.

**F8 — Stock in the storm is 32 fps and its p99 is 143 ms.** 191 of 843 route frames over 33 ms,
64 over 50 ms; the extra time of a slow frame is `waitForReadyState` (render thread starved by the
game thread: bakes 7.7 ms, `calculateRenderInfo` 4.9, `translucentNonFloor` 4.0 per frame with no
budget). The adopted defaults remove those three; what they leave is F6.

## 4. JFR breakdown and the two fixes (15:42–16:02)

`storm-jfr` (`--jfr --jfr-period 1`, same preset and flags): render thread 73 % of its busy time
in `WeatherParticleDrawer.render` → `VBORenderer.addQuad` (rain: `ParticleRectangle.render` tiles
a 512x512 cell of 1024 particles over the 5120x2160 offscreen, ~100k quads a frame, and stock
VBORenderer flushes every 28 quads through its 4 KB buffer); puddles are 2.5 % there. Game thread:
`renderPuddles` 37 % (`IsoPuddles.render` 22, the filter loop 15), rain particles 16 %
(`RenderPoints.getX` pointer chasing + `addParticle`), rain splashes 7 % (`Rand.NextBool` 3.4 %),
bakes 7 %, `GameWindow.logic` 16 %.

| run (uncapped, direct, storm) | fps | p50 | p99 | game thread | puddles | render submit | render wait |
|---|---|---|---|---|---|---|---|
| storm-prof-opt (profiler) | 70 | 13.1 ms | 39 | 13.4 ms | 4.53 | 8.68 | 4.36 |
| storm-vbo (profiler) + `vboBatchKb=1024 vboFastQuads` | 68 | 13.5 | 37.5 | 13.9 | 4.48 | 6.05 | 6.76 |
| storm-puddle (profiler) + `puddleCache` | **109** | 7.9 | 34 | 8.6 | **0.96** | 5.51 | 2.27 |
| storm-rec-vbostock (no profiler, `--record`, VBO keys at stock) | 108 | 8.4 | 27 | | | | |
| storm-rec-cur (no profiler, `--record`, all on) | **131** | 6.4 | 24.7 | | | | |

**F9 — VBORenderer batch (`vboBatchKb=1024`, `vboFastQuads`).** Render-thread submission
8.7 → 6.1 ms; on its own no fps change because the game thread was the wall, +21 % once the puddle
cache removed that wall (108 → 131). Every VBORenderer user (model atlases, shadows, trees, vision
polygon) gets the larger batch too.

**F10 — Puddle cache (`puddleCache`, `pzopt.PuddleCache`).** Packed vertices kept per chunk level
on the IsoChunk, lights / jiggle / depth patched per frame (`docs/override-edits.md`). Puddles
4.5 → 0.96 ms, game thread 13.4 → 8.6 ms, 70 → 109 fps with the profiler. Counters over the
25 s route: 12.8k batches built, 569k reused, rebuilds 5.3k by bake, 42 by cutaway change, 7.5k by
expiry (~4 a frame at the 60-frame backstop).

**Where the storm stands (131 fps, 5120x2160, max zoom):** game thread ~7 ms of which rain
particles ~1.7 (two `ParticleRectangle` walks), splashes ~1, bakes ~1.1, logic ~1.8; render
thread ~5.5 ms submitting, mostly still the rain quads; GPU 71 %. The 240 cap in a thunderstorm
needs the rain particle path itself changed (particle count scales with screen area: 104 cells at
5120x2160, one game-thread walk instead of mask + layered, and a persistent VBO for the quads).

## 5. Rain tiles, and the "rain vanishes every 5 s" (16:20–17:00, laptop + desktop)

**Rain tiles** (`rainTiles`, `pzopt.RainTiles`, overrides of `ParticleRectangle` and
`WeatherParticleDrawer`): `ParticleRectangle.render` used to walk the 1024-particle cell once per
screen cell (13x7 cells at 5120x2160 max zoom) and hand every copy to the drawer as its own quad;
now it renders the particles once at the origin and lists the cell origins, and the render thread
uploads that template once and draws it once per origin with a translated ModelViewProjection
through VBORenderer's own PositionColorUV shader. Same picture (the per-particle on-screen cull
becomes GPU clipping).

| run (uncapped, direct) | fps | p99 | game thread | `renderWeatherFX` | render submit |
|---|---|---|---|---|---|
| desktop spinning storm, profiler, `rainTiles=false` (`storm-notiles`) | 111 | 23.6 ms | 8.5 ms | 1.76 | 5.46 |
| desktop spinning storm, profiler, tiles (`storm-tiles`) | **188** | 17.0 | 4.8 | **0.10** | **2.89** |
| laptop 120 km/h storm drive, all of today off (`lap-storm120-pre`) | 70 | 32.5 | | | |
| laptop, VBO batch + puddle cache, no tiles (`lap-storm120-notiles`) | 84 | 33.2 | | | |
| laptop, + tiles (`lap-storm120-cur`) | **106** | 28.0 | | | |
| laptop 120 km/h clear drive, before / after today (`lap-drive120-pre` / `-cur`, 2 runs each) | 211, 198 / 198, 201 | 18 | | | |

Laptop (Radeon 890M, 1920x1080; `--option puddles=0 renderPrecipitation=1` to run the same
paths as the desktop, its own settings are puddles=2 / precipitation=2): no regression on the
clear drive, +51 % on the storm drive. Desktop spinning storm: GPU 71 → 86 %.

**F11 — "Rain vanishes then reappears every ~5 s, chunks re-light in a sweep"** (maintainer,
watching the laptop storm runs; reproduced on the desktop with `--record`, runs `storm120-rec`).
Not the puddle cache, the rain tiles or the flicker fix of 100f441: the storm preset fires a
lightning strike every 6 s, the flash changes every square's light, all ~200 on-screen chunk
textures re-bake, and the re-bake budget's 3-frame cap released them all in one frame — five
times per strike (flash on, flash off 0.24 s later, then every 250 ms of the fade): 50-90 ms
frames at 21.84 / 22.08 / 23.10 / 23.34 / 23.61 s and +6 s each strike in the overlay log, and in
the 60 fps recording 4-5 identical captured frames followed by one big jump each time. Rain (and
everything moving) freezes and leaps; that is the "vanish". Fix: lighting-only re-bakes get their
own budget and cap (`lightingRebakeBudget=8`, `lightingRebakeMaxFrames=30`).

| storm drive 120 km/h, desktop, recorded | fps | p99 | p99.9 | max | >33 ms |
|---|---|---|---|---|---|
| `storm120-rec` (cap 3) | 278 | 8.4 | 57 | 88 | 48 |
| `storm120-spread` (8 / 30, **default**) | 274 | 8.3 | 12.5 | 19 | 0 |
| `storm120-lrb100` (`lightingRebakeMs=100`, 8 / 30) | 276 | 9.1 | 25.5 | 33 | 0 |
| `storm120-lrb100b16` (100, 16 / 30) | 270 | 10.5 | 15.5 | 31.5 | 0 |

Trade-off: the flash now arrives over ~25 frames and chunks baked at different points of the ramp
form a faint checkerboard for ~90 ms (visible on the road at 31.9 s of `storm120-spread`); stock
avoids it by paying the stall. `harness/../vidstats` method: per-frame luminance, frame difference
and gradient of the 480x203 grayscale recording; frozen runs = consecutive zero-difference frames.

## 6. What to do next

1. Rain particles: done (§5, rain tiles). Left on the game thread in a storm: rain splashes
   (`IsoChunkLevel.updateRainSplashes`, `Rand.NextBool` per square per frame, ~1 ms) and
   `GameWindow.logic`; the desktop spinning storm is GPU-bound at 86 % now.
2. Rain splashes (`IsoChunkLevel.updateRainSplashes`, `Rand.NextBool` per square per frame,
   ~1 ms) and `GameWindow.logic` (~1.8 ms) are next; the lighting rebakes are already at ~1.1 ms
   with the adopted budget and are not the storm's problem.
3. Add the storm preset to the dashboard as its own series; compare only storm with storm.
4. Watch for the flicker of interior objects / doors / windows / corpses reported by the
   maintainer on the 15:49 build (VBORenderer is on that build's suspect list; the
   `storm-rec-cur` / `storm-rec-vbostock` recordings are the A/B).

## 7. Heavy fog presets (17:29–17:32): fog is the slowest scene so far

New `run.sh --preset fog` (`fog=heavy`: weather period stopped, `FLOAT_FOG_INTENSITY` pinned at 1.0
every frame, the other climate values left to the save) and `--preset storm-fog` (storm + `fog=heavy`
with the stock storm fog tint, i.e. the heaviest `STAGE_STORM` the game can roll; `pzopt.Scene`,
`fog=heavy|off|0..1` as a bare flag). Both use `ImprovedFog` (options.ini `fogQuality=0`); the
sandbox caps were `MaxFogIntensity=1 FogCycle=1` (uncapped). `pzopt-bench.out` records
`fog/fog_intensity/fog_fx/fog_quality`; `fog_fx=1.0` in both runs, so the renderer's stepped ramp
had reached full fog before the route.

| run | preset | fps | mean | p99 | p99.9 | max | game thread | GPU |
|---|---|---|---|---|---|---|---|---|
| preset-fog-1-20260920-173048 | fog | 55.5 | 18.0 ms | 38.7 | 72.2 | 144.5 | 98 % | 42 % |
| preset-storm-fog-1-20260920-172918 | storm-fog (4 strikes) | 30.5 | 32.8 ms | 64.0 | 99.5 | 101.7 | 97 % | 34 % |

Against 283 fps clear and 188 fps storm on the same route, heavy fog alone is a 5× frame-time
cost with the GPU under half busy: game-thread bound ("fps < 240 and hardware not saturated").
Caveat: a peer session was NVENC-encoding the docs/media videos during both runs (encoder block
+ some CPU), so treat these as smoke numbers and re-run clean before profiling. Next: GameProfiler
(`--game-profiler`, `sections.py --thread game`) on `preset-fog`; the suspect is
`FBORenderCell.renderFog` → `ImprovedFog.renderRowsBehind(square)` per visible square per level
on the game thread.
