# Result: parallel-chunk-grid-recalc

Measured 2026-09-15 on the fixed route (`harness/baseline/comparison-2026-09-15.txt`
is the full table; per-run summaries in `harness/baseline/bench-*.json`; every
figure below comes from the harness, not from observation). Machine: Ryzen 7
9800X3D, RTX 4090, NVMe; game 42.20.4 `b0bbce05d5` under Proton.

## What changed for the player

Chunk latency — the time from the game asking for a chunk to the chunk being
ready for the game thread — on the 100 s car-speed route:

| variant | p50 | p90 | p99 |
|---|---|---|---|
| stock (two runs) | 166 ms | 310 ms | 1024 ms |
| wake-on-enqueue only | 20 ms | 184 ms | 811 ms |
| pool only, W=4 (no wake) | 174 ms (noise) | 293 ms (noise) | 707 ms |
| wake + pool W=2 | 9.7 ms | 103 ms | 556 ms |
| wake + pool W=4 (shipped default) | 9.3 ms | 84 ms | 517 ms |
| wake + pool W=8 | 9.1 ms | 87 ms | 503 ms |
| kill switch (`parallel=false wake=false`) | 175 ms | 337 ms | 923 ms |

Frame time (mean, p99, p99.9; in-game sampler cross-checked against MangoHud)
is within run-to-run noise for every variant, including W=8 — no
render-thread starvation, and no frame-time *improvement* either: the
stutter this change set out to address is not caused by the streamer on this
machine. Chunks per second is unchanged (the route sets it).

## Honest reading

- The proposal's premise was half right. The recalc pass is the larger part
  of the streamer's work (71 %), but the streamer is idle >90 % of the time
  at car speed. What players see as chunk latency was ~90 % the loop's fixed
  140 ms sleeps. Waking the streamer on enqueue (design Decision 8, added
  during implementation) is worth 8× at the median on its own; the pool on
  top brings it to 18×, mostly at p90/p99 where bursts of a full chunk row
  are being processed.
- The pool alone, as originally proposed, would have been a "within noise"
  result at the median. It is kept because with the wake in place it is a
  further 2× at the median and 2.2× at p90, at a cost of ~0.4 ms more CPU per
  chunk (workers contend for cache) — 4 workers is the knee; 8 buys nothing.
- No visible frame-time change. On a 16-thread machine with an NVMe disk the
  game thread's own chunk work (`doLoadGridsquare`, lighting) and rendering set
  the frame-time tail, not the streamer. A slower CPU or disk would gain more
  from both changes, but that is not measured here.
- Parity: identical recalc output to stock at W=1, 2, 4, 15 and across two
  W=4 runs (131,133 squares in 1,653 chunks); an injected worker failure is
  retried on the streamer thread and still matches.

## Max-zoom driving benchmark implementation status

The separate `drive` harness mode is implemented but not enabled as a default.
It validates that the local player remains the vehicle driver, applies forward
input through the vehicle `CarController`, measures completion from observed
vehicle displacement, and rejects a missing or changed driver/vehicle. The
legacy `bench` and `parity` modes retain their teleport route unchanged.

Driving and rejected runs write `pzopt-bench.out` metadata for route status,
movement source, vehicle identity and distance, effective/max zoom, offscreen
dimensions, camera-pan setting, chunk-map width, resolution, renderer backend,
dashboard state, and the complete runtime settings string. `harness/run.sh`
also has a `--game-profiler` switch that temporarily enables
`GameProfiler.Enabled=true`, copies the resulting recording into the run
directory, and restores the user's debug options on exit.

The installed game jar compiles the harness changes and the existing unit tests
pass. A fixed vehicle fixture and a live GameProfiler recording are still
required before the driving benchmark tasks can be marked complete; no cache
decision is made from the existing teleport-route data.

## 2026-09-18: native Linux build, harness re-validated, first frame-time baseline

See `docs/native-baseline-2026-09-18.md`. Short version: the game is now the
native build (NVIDIA GL, `~/Zomboid`); all frame-time baselines before this
date are Proton and not comparable. Stock on the teleport route at 5120x2160:
162 fps mean, p99 19–22 ms, half the frames below the 240 cap, **GPU 80 %
mean / p90 100 %**, 13 of 16 CPU threads idle — the route is GPU-bound at this
resolution, and the remaining frame-time tail is CPU-side stalls.

## 2026-09-18 (evening): review, native re-measure of the shipped change, new plan

Full write-up: `docs/plan-driving-frame-time.md`. Dashboard:
`docs/benchmark-progress.html` (regenerate with `python3 harness/dashboard.py`).

- Wake + pool W=4 on native NVIDIA GL (`native-both-w4-20260918-181000` vs the
  stock pair `native-stock-3-20260918-173527/-175009`): chunk latency p50
  160 → 6.2 ms (−96 %), p90 287 → 35 ms, p99 1004 → 613 ms; frame mean / p99 /
  p99.9 and GPU busy all within noise. Same verdict as on Proton.
- Harness bugs fixed (compare.py crash on native runs, Zink baseline pair,
  MangoHud zero GPU columns, run.sh restore ordering, Lua flag consumption,
  drive mode never validated): listed in the plan, section 5.
- The drive fixture `Apocalypse/2026-09-18_12-18-03` has the player on foot
  (`drive-1-20260918-180741`, `route_status=rejected`); drive mode now spawns
  a vehicle and seats the player.
- A/Bs on the teleport route: no PZDashboard → p99 −6 % (rest within noise);
  G1 → p99 −13 % but 3× the frames > 33 ms (not adopted).
- Drive mode drives (cruise control, 60 km/h in 6 s) but the first recorded
  run ended in a tree line; road spawn added, not yet verified (session was
  locked during the check run). No driving baseline yet.
- Next measured target (JFR attribution): the per-frame translucent render
  pass in `FBORenderCell` (trees, windows, fading walls), not the streamer.

## 2026-09-18 (night): max-zoom teleport route, three render-side changes adopted

All runs: native Linux build, NVIDIA GL 615, 5120x2160, zoom pinned at 2.5
(`--flag zoom=max`), teleport route 18 tiles/s, wake+pool on, direct launcher
(Steam was logged out; `harness/run.sh --launcher auto`). Run directories in
`harness/runs/`; attribution with `harness/attribute.py` (now also
`--thread main` for the render thread and caller chains in `--drill`).

| run | change | frame mean | p99 | p99.9 | GPU busy | game thread | render thread |
|---|---|---|---|---|---|---|---|
| direct-z25-1 | reference (wake+pool) | 6.0 ms | 18.5 | 28.8 | 80 % | 82 % | 63 % |
| pvbo-1 | + `persistentVbo` | 6.2 | 18.7 | 28.4 | 84 % | 80 % | 35 % |
| trees-1 | + `treesInChunkTexture` | 5.2 | 17.4 | 26.2 | 61 % | 98 % | 32 % |
| hotsave-1 | + `hotsaveIntervalSec=30` | 5.0 | 16.0 | 25.1 | 61 % | 99 % | 31 % |
| windows-1 | + `windowsInChunkTexture` | 5.0 | 16.4 | 25.3 | 62 % | 98 % | — |
| tltiles-1 | + `translucentTilesInChunkTexture` | **4.5** | **13.8** | **22.4** | 59 % | 95 % | 19 % |
| nodash-all-1 | same, PZDashboard mod disabled | 4.4 | 11.9 | 20.2 | — | 95 % | — |
| uifbo-1 | same, game option uiRenderOffscreen=true (UI at 120 fps) | 4.5 | 13.1 | 22.4 | 58 % | 95 % | — |
| budget-1 / budget-2 | + `bakeBudget=8 lightingBudget=8` | 4.4 | **8.3 / 8.4** | **19.2 / 18.3** | 64 % | 96 % | — |
| rebake-1 | + `lightingRebakeMs=500` | 4.4 | 9.0 | 19.9 | 61 % | 97 % | — |
| cutaway-1 | budgets + `cutawayFast cutawayRadius=6 gridStackInterval=10` | 4.4 | 8.4 | 18.9 | 66 % | 95 % | — |
| nodash-budget-1 | budgets + cutawayFast, PZDashboard disabled | **4.3** | **7.9** | **11.8** | — | 96 % | — |

Attribution that led there (`attr-jfr-z25`, 5 ms samples, both threads):

- Render thread: 25 % `glMapBufferRange` (an orphaning `glBufferData` + map per
  64 KB sprite batch), 14 % `ArrayList.grow` from `StateRun.ops.add`, 10 %
  per-float `Buffer.put` bounds checks, 7 % per-sprite uniform setters. The
  persistent mapping removed the first item; the thread is no longer near the
  frame budget, so frame time did not move until the game thread was helped.
- Game thread at max zoom: 80 % of ordinary-frame time in `IsoCell.render`;
  the per-frame translucent pass (`renderOneLevel_Translucent`) alone was 35 %
  of all game-thread samples, every `IsoTree` being drawn per frame the main
  reason. Baking static trees (`isTreeRenderedEveryFrame` = false) cut the
  GPU from 84 to 61 % and the frame mean by 1 ms; the recording
  (`trees-1/recording.mp4`) shows forests intact.
- The chunk save worker's ancillary hot save (whole meta grid serialised on
  the game thread) fired every ~0.45 s while moving (222 episodes on the
  zoom-1 route); at 30 s minimum spacing 82 drains are skipped per hot save.
- Zoom 1.0 runs (`attr-jfr-1`) sit at 233 fps / p99 8 ms; their tail is Lua
  (PZDashboard collectors: the fog sweep does 6000 Java calls per pass in a
  single tick, plus `string.match`/`gsub` allocation churn) and the hot save.
- The 250–290 ms frame at route start in earlier runs was the zoom change
  itself (hundreds of chunk levels baked in one frame); the harness now forces
  the zoom when the world comes up, before the settle time.

Dev counters (`instrument=true` logs "translucent pass per frame" every 1800
frames) showed what the per-frame pass still drew after the trees: 1,500–3,600
objects per frame carrying the `Translucent` tile property (`depthFlags & 2`;
16,476 tile definitions in `media/tileGeometry.txt`: damaged fences, railings,
wall decorations and overlays, farm crops, pylons, boulders…) against only
30–130 windows and 20–40 doors. Baking those tiles (`tltiles-1`) took the
count to 50–120 (the ones fading near the player), and the recording shows
fences, poles, planters and wall signs intact. The windows switch alone is
within noise (kept, harmless).

Slow-frame composition after the tiles change (`attr-jfr-all`, frames ≥ 8 ms,
game thread): chunk-texture bakes 26 % (a new chunk row at max zoom bakes
dozens of chunk-level textures in one frame), Lua OnTick events 15 %
(PZDashboard), lighting cache refresh 12 %, cutaways 11 %. A per-frame budget
of 8 texture bakes (deferred textures keep their previous image for a frame;
never-baked ones wait) and 8 lighting-cache chunk refreshes (continued next
frame) took the p99 from 13.8 to 8.3 ms. Holding lighting-only re-bakes for
500 ms on top did nothing measurable and is not adopted.

**What the tail is now.** In the budget profile every frame ≥ 15 ms except
five is a Lua `OnTick` burst of 18–24 ms that repeats every 2.00 s exactly:
PZDashboard's `vehicles`, `fog`, `containers` and `appearance` collectors all
default to a 2.0 s interval and fire on the same tick (the fog sweep alone
makes up to 6,000 Java calls per pass). Disabling the mod takes the p99.9 from
19.2 to 11.8 ms. Recommendation for the dashboard mod, not this repo: stagger
the collectors (offset each category's first run by a fraction of its
interval) and spread the fog sweep over ticks (a few hundred calls per tick).
The cutaway savings (mask replay, visit radius, grid-stack interval) are
within noise on this route because the frame cap hides CPU headroom; the mask
replay is on by default, the other two stay off. The last ≥ 30 ms frame in
every run sits at route start (+0.3 s) and is a route-start artefact.

Defaults in `Config.java` are now the adopted set (`persistentVbo`,
`treesInChunkTexture`, `windowsInChunkTexture`, `translucentTilesInChunkTexture`,
`cutawayFast` true; `hotsaveIntervalSec=30`, `bakeBudget=8`,
`lightingBudget=8`); each is a one-line kill switch in `pzopt.properties`.
Not yet done: a long free-play soak of the baked-object changes (interiors,
zombies behind fences, curtains/doors state changes), the Steam-launched vs
direct A/B (Steam was logged out), and the driving fixture (the car still
leaves the road; see `drive-race-*`).

Remaining game-thread profile (`attr-jfr-trees`, before the tiles change): translucent pass still
~35 % (windows, glass doors, `Translucent`-flagged tiles, wall lighting),
`IOpenGLState` set calls 5 %, `TilePropertyAliasMap` string lookups from
`IsoObject.prepareToRender`, per-sprite uniform HashMap lookups, cutaway
occlusion recompute every frame while any chunk texture is dirty.

## 2026-09-19 (00:50–01:30): first valid driving runs

Drive mode now follows the road (`Harness.roadFollow`: heading from the
vehicle's own motion, street centre sampled 4–14 tiles ahead, a bearing fan
when nothing is straight ahead; distance is path length). The Rosewood bench
save (`Sandbox/pzopt-bench`, default when no `--source-save` is given) puts the
car on the highway east of town; the Apocalypse save starts on a farm track by
the river and is not a driving fixture. Route: `E:1200` at 60 km/h cruise,
zoom 2.5, both runs complete the 1,200 tiles in 73 s, 44 chunks/s streamed.

| drive run | settings | frame mean | p90 | p99 | p99.9 | max | GPU busy | game thread | render thread |
|---|---|---|---|---|---|---|---|---|---|
| drive-stock-road-1 | everything off (stock) | 8.1 ms | 14.5 | 18.4 | 22.3 | 28.7 | 92 % | 52 % | 75 % |
| drive-road-2 | Config defaults (all adopted changes) | **4.2** | **4.2** | **5.4** | 18.2 | 25.7 | 55 % | 100 % | 12 % |

Stock driving at max zoom is GPU-bound (92 %) at 123 fps mean; with the
adopted set the route sits on the 240 fps cap for 99 % of frames and the GPU
has 45 % headroom. The p99.9 (18 ms) is the same 2-second PZDashboard burst
as on the teleport route.

## 2026-09-19 (01:30–03:40): quad-view showcase, Steam performance monitor found capping fps

Four showcase recordings (60 and 120 km/h, stock vs optimized) were re-taken for
the quad-view video (`harness/stitch-quad.sh`, `config/mangohud-showcase-*.conf`).
The first optimized takes stopped at ~157 fps instead of the 240 fps cap seen at
00:50, with every pzopt setting confirmed identical (`[pzopt] settings:` line) and
the same save, route and zoom. The analyzer's thread split pointed at the render
side: the GL thread (`main`) went from 12–17 % of a core to 90 %, the game thread
(`MainThread`) from 99 % to 30 %, GPU busy from 55 % to 42 %.

Cause: **Steam's in-game performance monitor** (the newer FPS/perf overlay in
Steam's settings, not the classic Steam overlay). It hooks every GL call and
serialises the render thread. Verified by A/B/A on the optimized 120 km/h route,
all launched through Steam with nothing else changed:

| Steam performance monitor | classic overlay | route | mean fps | render thread | game thread |
|---|---|---|---|---|---|
| on (as at 01:30–02:30) | on | 60 km/h | 157 | 92 % | 31 % |
| off, Steam shut down, `--launcher direct` | – | 60 km/h | 238 | 17 % | 99 % |
| off | off | 120 km/h | 237 | 21 % | 99 % |
| off | on | 120 km/h | 236 | 22 % | 97 % |
| **on** | on | 120 km/h | **164** | **92 %** | 24 % |
| off | on | 120 km/h | 237.5 | 19 % | 99 % |

The classic Steam overlay is harmless; only the performance monitor costs the
frames. It stays disabled for all benchmark and showcase runs. Stock is GPU-bound
at ~75 fps at max zoom either way, so the monitor does not change the stock
numbers, only hides the optimized headroom. Runs: `quad2-opt60-1` (capped),
`quad5-opt60-2`, `quad6-*` (video), `overlaycheck-opt120-1..4` (A/B/A).

Two smaller findings from the same session:

- Direct launches (`--launcher direct`) ran the JVM under the desktop's
  `LC_NUMERIC=de_DE`; MangoHud then failed to parse `fps_metrics=avg,0.01,0.001`
  (no 1 % / 0.1 % rows, `4,2ms` formatting). `run.sh` now pins `LC_NUMERIC=C` for
  direct launches, matching the Steam runtime.
- MangoHud 0.8.4 applies `offset_x` towards the anchored edge for right-anchored
  positions, so `position=top-right` needs a negative `offset_x` to move inwards.

Showcase runs also press MangoHud's `reset_fps_metrics` key (Shift_R+F9, via
xdotool) at route start so avg / 1 % / 0.1 % cover only the drive, and `--record`
now captures desktop audio. The quad video (`docs/media/drive-60-120kmh-stock-vs-
optimized-quad.mp4`, 3840x1920, git-ignored at 731 MB) uses the `quad6-*` runs:
optimized 60 and 120 km/h both at the 240 fps cap with the GPU at ~57–61 %,
stock at 72–75 fps with the GPU at 100 %.

## 2026-09-19 (15:00–15:45): why the GPU idles uncapped — Zink's swap, not the game

Full write-up: `docs/plan-resource-use.md`. New tooling: `run.sh --jfr-setting`,
wait events in `tools/JfrSamples.java`, `harness/waits.py` (per-thread blocking
sites in the route window).

| run | renderer | route | fps | GPU busy | game thread | GL thread |
|---|---|---|---|---|---|---|
| waits-uncap-1 | Zink / Wayland | 122 km/h | 378 | 60 % | 60 % (39 % of the window blocked waiting for the GL thread) | 30 % (64 % of native samples inside glfwSwapBuffers) |
| waits-uncap-zinkx11-1 | Zink / XWayland | 122 km/h (off road) | 293 → 195 | 51 % | 68 % | throttled to the 240 Hz refresh |
| waits-uncap-gl-1 | NVIDIA GL | 122 km/h (off road) | 629 moving | 93 % | 85 % | 59 % |
| base60-gl-1 | NVIDIA GL | 60 km/h, complete | 570 | **98 %** | 69 % | 43 % |
| uifbo60-gl-1 | NVIDIA GL, uiRenderOffscreen=true | 60 km/h, complete | 567 | 98 % | **57 %** | 42 % |

- The game thread runs one frame ahead and waits for the single ready slot; the
  GL thread's wall time sets the frame, and on Zink ~1.8 ms of it is the swap.
- NVIDIA GL uncapped is GPU-bound at max zoom (12800x5400 offscreen buffer);
  the CPU idle is the correct state there.
- Stock "render UI offscreen" removes the per-frame Lua UI draw (25–34 % of
  game-thread CPU); frames from both recordings are identical.
- 122 km/h runs left the road twice today at ~380 tiles; A/Bs use 60 km/h.

## 2026-09-19 (15:45–16:05): NVIDIA GL on native Wayland, MangoHud overlay fixed there

- `wl-gl60-1` (NVIDIA GL, `-Dzomboid.wayland=1`, 60 km/h): 527 fps, GPU 96 %,
  route complete, but no MangoHud HUD or CSV: GLFW resolves `eglSwapBuffers`
  with `dlsym` on its private libEGL handle, so the preloaded hook never sees
  the swap (on X11 the Steam overlay's dlsym hook chains to MangoHud).
  MangoHud's own dlsym shim kills the game's JNI launcher (`wl-gl-mhshim-1/2`).
- Fix in the `Display` override (`docs/override-edits.md`): when MangoHud's
  library is preloaded and the platform is Wayland, `swapBuffers()` calls that
  library's exported `eglSwapBuffers` with GLFW's EGL display/surface through
  the JDK foreign-function API. Proved first with `tools/GlfwSwapProbe.java`
  (the game's LWJGL build; MangoHud blacklists processes named `java`, so the
  probe runs under a copied launcher).
- `wl-gl-mh-3` (400-tile route): HUD drawn, control socket up, CSV logged;
  663 fps mean, frame 1.5 / 4.2 ms (mean / p99), GPU 92 %.
- The single 3.4 s frame at route start in `wl-gl60-1` came from the other
  session's texture-buffer override installed during that run (256 MB decode
  budget, since reverted to 50), not from the Wayland GL path.

## 2026-09-19 (20:48–22:15): native Wayland vs XWayland, capped 240, same build

Question: should `-Dzomboid.wayland=1` (native Wayland window) become the default? Runs
are NVIDIA GL 615.71, adopted Config defaults (persistentVbo and
translucentTilesInChunkTexture off), `--no-dashboard`, instrument on, back to back.

| Run | Display | Route | fps | frame mean / p99 / p99.9 (ms) | under cap | GPU | render thread |
|---|---|---|---|---|---|---|---|
| `xwl-bench-1` | XWayland | bench, max zoom | 203 | 4.9 / 14.0 / 20.3 | – | 56 % | 37–51 % + NVIDIA worker 47 % |
| `wl-bench-glthr-2` | Wayland | bench, max zoom | 199 | 5.0 / 14.0 / 20.5 | – | 55 % | 80–88 %, no worker |
| `wl-bench-1` / `wl-bench-jfr-1` / `wl-bench-glthr-1` | Wayland | bench, max zoom | 196 / 196 / 196 | 5.1 / 14.8–15.0 / 21–22 | 41–43 % | 55 % | 81 % |
| `xwl-drive60-1` | XWayland | drive 60 km/h | 242 | 4.1 / 5.9 / 10.3 | 17.5 % | 54 % | 37 % |
| `wl-drive60-1` | Wayland | drive 60 km/h | 242 | 4.1 / 6.3 / 10.1 | 17.5 % | 56 % | 65 % |

- Verdict: at the 240 cap native Wayland is a wash. Bench mean 5.0 vs 4.9 ms and p99
  14.0 vs 14.0; drive p99 6.3 vs 5.9 and p99.9 10.1 vs 10.3, all inside the
  `compare.py` noise floor (0.2 ms mean, 0.6 ms p99). XWayland stays the default.
- The first comparison (`wl-bench-1` 196 fps against `fpscap-stock240` 215 fps, logged
  earlier tonight as a Wayland regression) was mostly a build difference: the artifact
  fixes installed at 20:52 turned persistentVbo and translucentTilesInChunkTexture off,
  and the two runs did not share settings. The same-build pair is 4 fps apart.
- Where the CPU goes differs, and it is the one real Wayland finding. A per-thread
  snapshot of the live process (`harness/native-threads.sh`, all native threads, not
  only the Java ones `pzopt-threads.out` sees) shows an unnamed second `ProjectZomboid6`
  thread at 47 % of a core on XWayland: NVIDIA's threaded-optimisation worker under GLX.
  On native Wayland (EGL) there is no such thread, even with
  `__GL_THREADED_OPTIMIZATIONS=1` in the process environment (verified in
  `/proc/<pid>/environ`); the render thread does that work itself (88 % vs 56 % of a
  core) and its JFR profile is dominated by `glDrawRangeElements` (15 % of samples)
  where XWayland's is dominated by `glGetInteger` and `glClientWaitSync` sync points.
  Total process CPU is equal (259 vs 281 % of a core). Capped, the render thread has
  slack either way; uncapped the GL thread's wall time sets the frame, which matches the
  earlier 527 fps (Wayland, `wl-gl60-1`) vs 570 fps (XWayland, `base60-gl-1`).
- MangoHud on native Wayland: HUD and CSV work through the `Display` override's swap
  hand-off, and the log starts and stops through the control socket in bench and drive
  mode (`wl-drive60-1`). The only piece that cannot work is the xdotool keypress that
  resets the HUD fps metrics at route start: MangoHud's Wayland keybind path needs its
  `eglGetPlatformDisplay` hook to see GLFW's `wl_display`, which GLFW's private `dlsym`
  bypasses, and the control socket has no reset command (only hud / logging / fcat).
  It is cosmetic: MangoHud's fps metrics are a rolling window of the last 10 000 frames
  (about 45 s at 240 fps), so by the end of any route the HUD shows route-only numbers.
  `run.sh` now says so and skips the keypress on native Wayland instead of sending it to
  nowhere (`native_wayland` flag from `--env ...zomboid.wayland=1`).
- Also fixed tonight: `analyze.py` did not parse `gc.log` files with comma decimals
  (runs before the `LC_NUMERIC=C` launch), so yesterday's benches showed "0 GC events".
  With the fix every run has 6–12 ZGC cycles in the route window; there was no GC
  regression.

## 2026-09-19 (22:20–22:50): native Wayland vs XWayland, uncapped

Same build and options as the capped pair above, `--prop uncappedFps=true`, back to back.

| Run | Display | Route | fps | frame mean / p99 / p99.9 (ms) | jitter | GPU | game thread / render thread |
|---|---|---|---|---|---|---|---|
| `xwl-uncap-bench-1` | XWayland | bench, max zoom | 296 | 3.4 / 12.7 / 18.7 | 1.0 | 70 % | 95 % / 68 % |
| `wl-uncap-bench-1` | Wayland | bench, max zoom | 262 | 3.8 / 13.7 / 20.0 | 0.7 | 65 % | 86 % / 94 % |
| `xwl-uncap-drive60-1` | XWayland | drive 60 km/h | 471 | 2.1 / 5.1 / 8.8 | 0.5 | 86 % | 97 % / 73 % |
| `wl-uncap-drive60-1` | Wayland | drive 60 km/h | 391 | 2.6 / 5.0 / 9.2 | 0.2 | 73 % | 82 % / 98 % |

- Uncapped, native Wayland is 12 % (bench) to 17 % (drive) slower in mean frame time,
  well outside the noise floor. The tails are the same: p99 12.7 vs 13.7 and 5.1 vs 5.0,
  p99.9 within 1 ms. Jitter is slightly lower on Wayland because the render thread is the
  steady bottleneck there.
- The cause is the one found in the capped runs: no NVIDIA threaded-optimisation worker
  under EGL. The render thread ("main") sits at 94–98 % of a core on Wayland with the game
  thread waiting on it (82–86 %, down from 95–97 %), and the GPU is left at 65–73 % instead
  of 70–86 %. On XWayland the driver worker takes the command building off the render
  thread (73 % there) and the GPU is the limit on the drive route (86 %, p90 100 %).
- Verdict unchanged and now stronger: XWayland stays the default; native Wayland costs
  frame rate uncapped and gains nothing capped. Against the objective ("CPU and GPU maxed
  if not pegged at 240"), Wayland is the worse state: one core pegged, GPU idle time.

## 2026-09-19 (22:25–23:05): ZombieBuddy + ZBBetterFPS vs our overrides

Zed's ZBBetterFPS (Steam Workshop build of 2026-08-09, `42.13` jar, supports 42.12–42.17;
game is 42.20.4) loaded through ZombieBuddy 2.3.3 (GitHub release jar, `-javaagent` in the
launcher JSON via the new `--vmarg`; `JAVA_TOOL_OPTIONS` is unusable because the launcher's
libjvm-locating helper JVM picks the agent up and dies on `zombie.Lua.LuaManager`). Mods copied
to `~/Zomboid/mods`, enabled per run with the new `--mod`. Every ZBBetterFPS option on except
render distance (game default, 152 tiles, so the view is identical), uncapped FPS (default),
instant zoom, background throttling ("never"). The mod runs use our build with every
runtime optimization switched off (the same stock-behaviour property set as the showcase stock
runs) so the harness route driver is present; "stock" below is that set without the mod.
All six runs: XWayland, NVIDIA GL, 5120x2160, max zoom, no dashboard, `--option frameRate=240`
(options.ini had drifted to 60 fps: the 22:06–22:16 forced-uncapped runs left uncappedFPS=true and
stock Core.loadOptions rewrote it as frameRate=60 on the next boot; fixed in a0d323b, FrameCap now
snapshots and restores the file. The boot log's `frame cap:` line is the tell).

Findings:
- **Object separation patch crashes 42.20.** `optimizeIsoMovingObject` reads
  `IsoZombie.networkAi`, which no longer exists: `NoSuchFieldError` on the game thread 75 s
  into `zbfps-bench-1`, game thread dead, native SIGSEGV on shutdown. Disabled for the runs below.
- **Ring-buffer patch never applies in a fresh boot.** `SpriteRenderer$RingBuffer.create`
  runs before the Lua `OnGameBoot` handler sets the flag, so its 1 MB buffers were not in effect
  (no "Patching SpriteRenderer.RingBuffer" line). The other patches (IndieGL alpha/depth cache,
  chunk-depth uniform cache, MVP matrix cache, IsoChunkMap width, MultiTextureFBO2, main-loop
  sleeps) were transformed and enabled.
- The mod is within noise of stock on both routes; our overrides are the only thing that
  moves the tail.

| run | route | fps mean | frame mean / p99 / p99.9 ms (game) | mangohud p99 / p99.9 | >33 ms | GPU busy | game CPU |
|---|---|---|---|---|---|---|---|
| `stock-bench-1` | teleport, max zoom | 156.7 | 6.4 / 19.3 / 27.4 | 22.4 / 35.5 | 24 | 78 % | 306 % |
| `zbfps-bench-2` | teleport, max zoom | 161.8 | 6.2 / 18.6 / 27.9 | 22.1 / 34.2 | 19 | 79 % | 306 % |
| `ours-bench-1` | teleport, max zoom | 198.1 | 5.0 / 14.4 / 20.2 | 16.9 / 25.2 | 4 | 55 % | 298 % |
| `stock-drive120-1` | E:1200 at 122 km/h | 121.3 | 8.2 / 19.1 / 22.1 | 17.6 / 21.9 | 0 | 90 % | 288 % |
| `zbfps-drive120-1` | E:1200 at 122 km/h | 122.5 | 8.2 / 18.8 / 22.2 | 17.2 / 20.7 | 1 | 90 % | 286 % |
| `ours-drive120-1` | E:1200 at 122 km/h | 234.8 | 4.3 / 7.3 / 13.9 | 9.3 / 17.0 | 1 | 58 % | 291 % |

Stock and the mod are GPU-bound at the 240 cap (90 % busy while driving, 16 % of the machine's
CPU); the chunk-texture baking in our build is what halves the GPU work and puts the drive at the
cap. Nothing in ZBBetterFPS touches that path, so stacking it on top of our build is not worth a
run. Recordings of the three drives were checked frame-by-frame: identical scenes, no artifacts.

## 2026-09-19 (23:00): side-by-side video, stock 244 fps cap vs optimized uncapped, 120 km/h

`harness/stitch-sbs.sh` -> `docs/media/drive-120kmh-stock-244cap-vs-optimized-uncapped.mp4`
(3840x1450, 63 s). Both panes start at their own launch with live boot / load counters; the
optimized pane waits at its route start until the stock run is loaded, then both drive in sync.

| run | build | cap | boot (first log -> Continue) | load (Continue -> world) | fps mean | frame mean / p99 / p99.9 ms | GPU busy |
|---|---|---|---|---|---|---|---|
| `sbs-stock120-1` | every pzopt flag off (render, loader and boot flags) | 244 (`--option frameRate=244`) | 7.31 s | 8.74 s | 122.1 | 8.2 / 18.9 / 22.6 | 91 % |
| `sbs-opt120-uncap-1` | defaults, `--prop uncappedFps=true` | none | 6.01 s | 3.68 s | 412.4 | 2.4 / 6.3 / 11.4 | 86 % |

Stock at the 244 cap is GPU-bound at ~122 fps exactly as at the 240 cap. The optimized boot
here (6.0 s) is slower than the 5.0 s figure from the load loop: the caches under
`~/Zomboid/pzopt` were cold-ish after the stock run and the machine was busy with a render
check; the load (3.7 s) matches. Hardware panel: Ryzen 7 9800X3D, RTX 4090 (driver 615.71),
32 GB DDR5-8000, Crucial T705 2 TB (13.5 GB/s sequential read measured with fio, 1 MiB QD32,
direct I/O through ZFS).

Side finding: `zpool status zpcachyos` reports 5 permanent data errors (ZFS-8000-8A) on the
T705 pool that holds /games and /home; `zpool status -v` lists the files.

## 2026-09-20 (03:10–04:00): game-thread pass on a Rosewood teleport route

Question: nothing so far touched the simulation half of the game thread; what
does the thread spend on, and how far is 240 locked? New route: the bench save
loads at 8002,11204 just north of Rosewood; `--flag route=S:1800` (100 s) goes
south through the town, and the short form `--flag route=S:450 --flag turn=90
--route-seconds 25` (new harness flag: the player facing spins 90°/s so the
vision cone, lighting cone and cutaways keep changing) loads 55 chunks/s. All
runs: NVIDIA GL, 5120x2160, zoom 2.5, 240 cap, no dashboard, `gt-*` in
`harness/runs/`.

Attribution (5 ms JFR, route window, share of game-thread samples): on the 100 s
route render-command recording 67 % (`IsoCell.render` 46 %, Lua UI draw 15 %,
weather mask 2.6 %), logic 30 % (`IsoWorld.update` 19 %: player 4 %, zombies
and animation post-update 5 %, chunk hand-off 5 %), lighting JNI 3 %. The
over-budget time is bursts: 90 % of the excess over 4.17 ms came from frames
over 6 ms, and those were chunk-texture bakes (36 % of their samples), world
update (21 %), chunk hand-off (10 %), cutaway visits (6 %). Bakes were 2.4 to
4.3 per frame and 87 % of them re-bakes (lighting drift, neighbour-loaded seam
redraws, cutaway changes); the existing bake budget only defers never-baked
levels, which is why `bakeBudget=3` changed nothing.

| run | change | fps mean | frame mean | p90 | p99 | p99.9 | under the cap | game thread |
|---|---|---|---|---|---|---|---|---|
| gt-q-1 | reference, previous defaults | 199.1 | 5.0 ms | 8.1 | 16.9 | 29.8 | 31.2 % | 92 % |
| gt-q-3 | + weather-mask scan gate and building-only scan | 203.1 | 4.9 | 7.6 | 16.5 | 28.1 | 29.9 % | 92 % |
| gt-q-cut-1 | + `cutawayRadius=6 gridStackInterval=8` | 208.2 | 4.8 | 7.1 | 15.9 | 28.1 | 30.2 % | 91 % |
| gt-q-bake3-1 | + `bakeBudget=3` | 208.3 | 4.8 | 7.1 | 15.6 | 27.0 | 30.2 % | not adopted |
| gt-q-ui-1 | + `uiRenderOffscreen=true` (stock option) | 208.4 | 4.8 | 7.0 | 16.2 | 28.8 | 32.6 % | not adopted |
| gt-q-lrb-1 | + `lightingRebakeMs=250` | 215.6 | 4.6 | 6.5 | 13.6 | 23.8 | 29.1 % | 95 % |
| gt-q-rb-1 | + `rebakeBudget=4` (new) | 225.9 | 4.4 | 5.5 | 11.2 | 19.7 | 28.3 % | 97 % |
| gt-q-def-1 | + light-switch cache, all as defaults | 225.6 | 4.4 | 5.5 | 11.3 | 20.4 | 27.9 % | 97 % |
| gt-q-lrb1000-1 | `lightingRebakeMs=1000` | 225.7 | 4.4 | 5.5 | 11.1 | 21.7 | 26.7 % | not adopted |
| gt-q-lua-1 | + single-lookup Kahlua `rawget` | 228.1 | 4.4 | 5.3 | 10.5 | 20.0 | 27.8 % | 97 % |
| gt-q-occ-1 | + occluder masks on the chunk | **228.8** | **4.4** | **5.4** | **10.2** | **17.8** | **27.1 %** | 97 % |
| gt-base-1 | 100 s route, previous defaults | 230.1 | 4.3 | 5.1 | 10.5 | 20.9 | 24.4 % | 90 % |
| gt-long-final-1 | 100 s route, final build | **238.5** | **4.2** | **4.6** | **7.3** | **14.4** | **20.6 %** | 94 % |

Visual check: `gt-q-rb-2` (final keys) and `gt-q-ref-1` (every new key off)
were recorded; frames every 2 s over the route are the same, including the
dark unloaded and unlit areas at the leading edge that both show at 18
tiles/s. Console errors are the same stock map warnings in both.

What each trim was (details in `docs/override-edits.md`): the weather mask
rasterized the whole view every frame even when it could add no mask
(`isInteriorLocation` per exterior tile, 70 % of the pass in slow frames);
`checkTreeTranslucency` did a `HashSet.remove` per tree per frame on an
almost always empty set (our own edit) and read the aim key per tree;
`LightingJNI.checkLights` asked every light switch for power every frame
(2.3 %); the exact occluder mask lookup was a `HashMap.get` per on-screen
level per frame (1.8 %); Kahlua's `rawget` did `containsKey` then `get`.

Honest reading against the objective. The game thread is now 97 % busy on the
spinning route and 94 % on the plain one with the GPU at 59 to 68 %, so the
frame is game-thread-bound and the remaining cost is broad: chunk texture
bakes 20 % (create and object changes while streaming; the re-bakes that can
be held are held), `IsoWorld.update` 23 % (player 4 %, zombies 3 %, animation
post-update 6 %, vehicles 2 %, chunk hand-off 4 %), the Lua UI draw 10 % plus
its update 3 %, JNI light info caching 3 %, `LightingJNI.update` 3 %. There is
no single hot spot left worth a class override; 240 locked on this route needs
the game thread to do less per frame structurally: recording chunk-texture
bakes off the game thread, or overlapping the render-command recording with
the next frame's logic (`docs/plan-resource-use.md`). Both are multi-day
changes with real race risk and need their own plan and gate.

Video (04:26–04:31): `docs/media/rosewood-spin-stock-vs-optimized-vs-game-thread.mp4`
(`harness/stitch-triple.sh`, 3840x1920, 28 s) from three recorded runs of the spinning
route with the showcase HUD: `gtshow-stock-2` (every Config key at its stock value, the
harness plumbing still installed so the run auto-starts: 105.3 fps mean, 9.5 ms, p99
28.3), `gtshow-opt-1` (today's keys off: 197.4 fps, 5.1 ms, p99 18.0) and `gtshow-gt-1`
(defaults: 225.8 fps, 4.4 ms, p99 11.6). The "optimized before" cell still carries the
three key-less code trims (tree check, Kahlua rawget, occluder masks on the chunk),
which are within noise. A stock run with the overrides uninstalled cannot auto-start
(click-to-start is pressed by pzopt.AutoStart), which is why the keys-at-stock form is used.

Small measured negatives, not adopted: `bakeBudget=3` (bursts are re-bakes,
not first bakes), `uiRenderOffscreen=true` (UI draw 10 → 7 % but frame time
unchanged at 240), `lightingRebakeMs=1000` (fewer bakes, same frame time).


## 2026-09-20 (05:05–05:50): laptop, JVM x GC matrix on the spinning route, in-game overlay only

Machine: the laptop (`diego-flip`, Ryzen AI 9 / Radeon 890M, Mesa 26.2.3, 1920x1080,
**on battery** throughout, ~25 to 40 W package draw), not the desktop; none of the
desktop numbers above apply. Every run: max zoom, no dashboard, `--prop uncappedFps=true`,
frame times from `pzopt-overlay.out` with MangoHud not loaded at all (new
`harness/run.sh --no-mangohud`; `analyze.py` reads the overlay log the same way).
One run per cell, so treat differences under ~5 % as noise.

Spinning route (`--flag route=S:450 --flag turn=90 --route-seconds 25`), optimized
build. "Stock" is our build with every render and game-thread key at its stock value
(`parallel wake persistentVbo treesInChunkTexture windowsInChunkTexture
translucentTilesInChunkTexture translucentCache cutawayFast weatherMaskIdleSkip` false,
`hotsaveIntervalSec bakeBudget lightingBudget lightingRebakeMs rebakeBudget
lightSwitchCheckFrames cutawayRadius gridStackInterval` 0), the harness plumbing and
the overlay still installed. GC is the launcher JSON's `-XX:+UseZGC` unless `--gc g1`.

| run | JVM | GC | fps | mean | p50 / p90 | p99 | p99.9 / max | >33 ms | jitter | JIT+GC CPU |
|---|---|---|---|---|---|---|---|---|---|---|
| spin-stock-uncap-1 | Zulu 25.0.1 | ZGC | 44.2 | 22.6 | 19.2 / 38.0 | 67.1 | 110 / 127 | 185 | 11.2 | |
| spin-opt-uncap-1 | Zulu 25.0.1 | ZGC | 66.9 | 14.9 | 13.3 / 24.4 | 41.0 | 98 / 125 | 50 | 5.7 | 87 s |
| spin-opt-uncap-g1-1 | Zulu 25.0.1 | **G1** | **81.8** | **12.2** | **10.6 / 20.2** | 40.8 | **72 / 110** | 46 | **4.8** | 102 s |
| spin-opt-uncap-graal-1 | Oracle GraalVM 25.2.4 | ZGC | 56.3 | 17.8 | 15.6 / 29.1 | 52.1 | 104 / 116 | 86 | 6.8 | 143 s |
| spin-opt-uncap-graal-g1-1 | Oracle GraalVM 25.2.4 | G1 | 65.8 | 15.2 | 13.2 / 25.4 | 46.3 | 111 / 273 | 63 | 6.0 | 159 s |
| spin-opt-uncap-graal253-1 | Oracle GraalVM 25.3.4.1 | ZGC | 57.1 | 17.5 | 15.5 / 28.9 | 51.0 | 106 / 163 | 88 | 6.8 | 143 s |
| spin-opt-uncap-graal253-g1-1 | Oracle GraalVM 25.3.4.1 | G1 | 70.0 | 14.3 | 12.6 / 23.4 | 44.0 | 92 / 159 | 54 | 5.5 | 151 s |

Milliseconds except fps and counts. "JIT+GC CPU" is `process_cpu_ms - live_threads_cpu_ms`
from `pzopt-threads.out` over the 25 s route: CPU the JVM's own (non-Java) threads used.
The Java threads did the same work in every row (65 to 66 s of CPU; game thread 96 to 98 %
of a core, Lighting Thread 75 to 79 %).

Findings:
- The overrides at their defaults are 1.5x stock on this route here (44 → 67 fps, p99
  67 → 41 ms, >33 ms frames 185 → 50), same shape as the desktop result, at a lower level.
- **G1 beats ZGC by 22 % on this laptop** (67 → 82 fps) and the gain is in the body of
  the distribution (mean, p50, p90), not the tail: p99 and the >33 ms count are unchanged.
  ZGC's concurrent threads and load barriers cost CPU that, on a battery-limited APU, comes
  out of the clock budget of the single pegged game thread; G1 trades that for stop-the-world
  pauses (up to 53 ms each in `gc.log`, ~0.8 s total over the run) that show up as the p99.9
  still being 72 ms. On the desktop uncapped the GPU is the wall (98 % busy), so this is a
  laptop/APU finding until measured there.
- **GraalVM (Oracle, Graal JIT on by default) is ~15 % slower than HotSpot C2 on the same
  collector**, both releases. Its compiler threads used 55 to 60 s more CPU than C2 during
  the 25 s route (it warms up slower and does far more work per method), and that CPU is
  taken from the game thread. Not adopted. Both copies stay in the game dir
  (`jre64_linux` = 25.2.4, `jre64_graal253` = 25.3.4.1); `jre64` is the shipped Zulu.
  Note `analyze.py`'s "gc (ZGC) N events, M ms wall" counts ZGC's concurrent cycles, not
  pauses; ZGC pauses are sub-millisecond, so the ZGC tail is not GC pauses.

Does GraalVM catch up once warm? 100 s route (`--flag route=S:1800 --route-seconds 100`),
G1 on both, same conditions:

| run | JVM | fps | mean | p50 / p90 | p99 | p99.9 / max | >33 ms | under 240 cap | GPU busy | JIT+GC CPU |
|---|---|---|---|---|---|---|---|---|---|---|
| long-opt-uncap-g1-1 | Zulu 25.0.1 | **233.5** | **4.3** | 3.0 / 8.1 | **20.1** | 40 / 210 | 55 | 24 % | 65 % | 203 s |
| long-opt-uncap-graal253-g1-1 | GraalVM 25.3.4.1 | 204.1 | 4.9 | 3.4 / 9.1 | 24.2 | 48 / 140 | 70 | 31 % | 61 % | 283 s |

In 20 s slices (fps, Zulu vs Graal): 0–20 s in town 72.7 vs 56.6 (−22 %), 20–40 s 233.6 vs
189.2 (−19 %), 40–60 s 343.7 vs 294.3 (−14 %), 60–80 s 300.5 vs 266.4 (−11 %), 80–100 s
217.1 vs 214.0 (−1 %). The gap closes steadily as the compiler drains its queue but only
reaches parity about two minutes after launch; nothing in these runs shows Graal ahead of
C2. Not worth the 680 MB and the slow first minutes.

Side finding: the 100 s route is a different regime from the spinning one on this laptop.
Out of town the GPU gets busy (65 %, clocks to 2.4 GHz, 40 W battery draw) and a quarter of
the frames hit the 240 cap; in town on the spinning route the game thread is the wall at
~⅓ CPU and GPU utilization. Both are "fps < 240 with hardware not saturated"; the first
20 s of the long route (72 fps, p99 43 ms) is the part that matters for play.

Next, if pursued: repeat the Zulu G1 vs ZGC pair on AC before shipping `-XX:+UseG1GC` in
the launcher JSON for laptop users (n=1, battery), and the same pair on the desktop.

## 2026-09-20 (05:50–05:55): launcher JSON tuned for G1; GC pauses gone from the frame tail, tail unchanged

`ProjectZomboid64.json` on the laptop now carries `-Xms4096m -Xmx4096m -XX:+UseG1GC
-XX:MaxGCPauseMillis=25 -XX:+AlwaysPreTouch -XX:+PerfDisableSharedMem` (copies:
`config/launcher/ProjectZomboid64.g1.json`, Steam's original as
`config/launcher/ProjectZomboid64.stock.json`; Steam rewrites the file on an update, `cp`
the tuned copy back). Reasons: the G1 log of `spin-opt-uncap-g1-1` showed the heap growing
436 MB → 2.7 GB during the run with ~1.7 GB live, so a young collection every 2 to 3 s and
pauses up to 53 ms; THP is already `always` here so `UseTransparentHugePages` adds nothing.
`harness/run.sh` passes the JSON through (it only adds the gc log), so runs without `--gc`
inherit these flags from now on.

Spinning route, same conditions as the matrix above:

| run | launcher | fps | mean | p50 / p90 | p99 | p99.9 / max | >33 ms | jitter | GC pauses (run): n / total / max |
|---|---|---|---|---|---|---|---|---|---|
| spin-opt-uncap-g1-1 | `-Xmx3072m -XX:+UseG1GC` | 81.8 | 12.2 | 10.6 / 20.2 | 40.8 | 72 / 110 | 46 | 4.8 | 92 / 762 ms / 53 ms |
| spin-opt-uncap-g1tuned-1 | tuned set above | 82.6 | 12.1 | 10.5 / 20.1 | 42.1 | 77 / 122 | 39 | 4.9 | 54 / 623 ms / **9.9 ms** |

The flags did what they are for (worst pause 53 → 9.9 ms, fewer pauses, no extra JIT+GC
CPU: 102 → 100 s) and the frame distribution did not move at all: every column is within
single-run noise. So the tail that remains on this route (p99 ~41 ms, p99.9 ~75 ms, a few
100+ ms frames) is not GC; it is the game thread's own bursts while streaming (chunk
hand-off and bakes, the same bursts the 03:10 game-thread pass attributed on the desktop).
JVM flags are exhausted as a lever here. Kept anyway: no 50 ms stop-the-world hits, no
heap growth, no hsperfdata writes, for ~1 GB more RSS and a slightly longer boot
(`AlwaysPreTouch`).

## 2026-09-20 (12:10–13:10): uncapped 400 fps pass on the spinning Rosewood route

Route as the game-thread pass (`--flag route=S:450 --flag turn=90 --route-seconds 25`, max zoom,
5120x2160, NVIDIA GL, Steam launcher, no dashboard) but uncapped (`--prop uncappedFps=true`), frame
source the in-game overlay log (`--no-mangohud`), JFR at 1 ms. Every run used the maintainer's
Optimizations-tab file (persistentVbo, translucentTilesInChunkTexture on). Full table and GPU
breakdown in the run directories (`u400-*`).

| run | change | fps mean | p50 | p90 | p99 | p99.9 | game thread | GPU |
|---|---|---|---|---|---|---|---|---|
| u400-base-1 | previous build, UI drawn every frame | 272.8 | 2.8 | 6.3 | 13.2 | 27.8 | 98 % | 80 % |
| u400-uifbo-2 | stock option `uiRenderOffscreen=true` | 374.3 | 1.9 | 4.8 | 12.3 | 23.4 | 93 % | 89 % |
| u400-it1-1 | + cutawayInvalidateChanged, soundZoneCache, lightInfoOncePerFrame | 442.2 | 1.7 | 3.8 | 9.5 | 18.3 | 91 % | 92 % |
| u400-it4-1 | + chunkHandoffDivisor=8, occlusionSkipLightingOnly | 454.2 | 1.7 | 3.7 | 9.0 | 18.4 | 89 % | 92 % |
| u400-it8-1 | + cutawayVisitPrefilter | 466.1 | 1.7 | 3.4 | 8.6 | 18.6 | 90 % | 93 % |
| u400-it9-1 | + lightInfoChunkGate | 500.8 | 1.6 | 3.1 | 7.7 | 16.8 | 89 % | 93 % |
| u400-final-1 | final build, confirmation | 499.1 | 1.6 | 3.2 | 7.6 | 15.1 | 89 % | 94 % |

Caveat found while recording the video: the game's Steam launch options are
`harness/steam-launch.sh mangohud %command%`, so the MangoHud HUD was drawn (the maintainer's
full config) on every run above even with `--no-mangohud`; it depresses the absolute numbers
(same build: 273 fps with the HUD in u400-base-1 vs 391 fps with `MANGOHUD_CONFIG=no_display`
in u400show-prev-1, UI drawn every frame in both) but was identical across the runs, so the
deltas hold. The recordings for `docs/media/rosewood-spin-uncapped-stock-vs-optimized-vs-all-hdr.mp4`
(u400show-stock-2 / -prev-1 / -all-1) hide it: stock settings 114 fps mean, p99 31.4 ms;
optimized before the pass 391 fps, p99 10.2 ms; all optimizations 492 fps, p99 7.7 ms.

No gain: weatherFxScalePct=50, lightingRebakeMs=1000, bakeBudget=4; uiRenderFPS=60 +2 % (noise).
The whole weather FX pass off is +11 % (measurement only). Verdict against the objective: the
mean is past 400 but the frame is not locked; ~13 % of frames exceed 2.5 ms, all of them chunk
streaming on the game thread (loot roll, new-row bakes, cutaway data) or the UI FBO refresh,
and from ~450 fps the GPU (chunk composite + bakes) is saturated, so the machine is now used to
the max on both sides at this resolution.

## 2026-09-20 (13:35–13:47): desktop JVM matrix — Zulu vs GraalVM 25.0.3, stock ZGC vs tuned G1 JSON

Same spinning route and conditions as the 400 fps pass (`--flag route=S:450 --flag turn=90
--route-seconds 25`, max zoom, uncapped, `--option uiRenderOffscreen=true`, in-game overlay log,
`--no-mangohud`, no dashboard, Steam launcher). This time the MangoHud HUD was really absent
(`harness: MangoHud is NOT loaded`), so the absolute numbers sit above the 400 fps pass table.
GraalVM is Oracle GraalVM JDK 25.0.3+9.1 (Graal JIT on by default) unpacked to the game dir;
the tuned JSON is `config/launcher/ProjectZomboid64.g1.json` (4 GB fixed heap, 25 ms pause
goal, pretouch, no hsperfdata). Every run passes `--prop persistentVbo=true --prop
translucentTilesInChunkTexture=true --prop hotsaveStaged=false` explicitly, see the caveat.

| run | JRE | launcher JSON | fps mean | p50 | p90 | p99 | p99.9 | max | 1 %-low | < 240 fps | game thread | GPU | cpu | GC in window |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jvm-zulu-vbo-1 | Zulu 25.0.1 (C2) | stock, ZGC 3 GB | 508.5 | 1.6 | 3.1 | 7.7 | 15.2 | 52.6 | 131 | 3.7 % | 87 % | 95 % | 28 % | ZGC 3 cycles |
| jvm-graal-1 | GraalVM 25.0.3 | stock, ZGC 3 GB | 463.0 | 1.7 | 3.5 | 9.9 | 19.7 | 64.2 | 101 | 5.7 % | 89 % | 91 % | 36 % | ZGC 6 cycles |
| jvm-graal-g1-1 | GraalVM 25.0.3 | tuned G1 4 GB | 474.3 | 1.6 | 3.4 | 9.3 | 18.7 | 54.5 | 107 | 5.2 % | 85 % | 92 % | 39 % | G1 17 events, max 407 ms wall |
| jvm-zulu-g1-1 | Zulu 25.0.1 (C2) | tuned G1 4 GB | 508.7 | 1.6 | 3.0 | 7.3 | 16.7 | 49.1 | 137 | 3.4 % | 81 % | 96 % | 30 % | G1 10 events, max 333 ms wall |

- **GraalVM is 7–9 % behind HotSpot C2 on the desktop too**, on either collector, with a worse
  tail (p99 7.7 → 9.9 ms, 1 %-low 131 → 101). Its compiler threads show as process CPU 28 → 36–39 %.
  Same verdict as the laptop matrix; not adopted. The copy stays at `jre64_graal`.
- **Zulu + tuned G1 JSON is the best of the four**: the mean is GPU-bound at 96 % either way, but
  the tail is a little tighter than ZGC (p99 7.3 vs 7.7 ms, max 49 vs 53, 1 %-low 137 vs 131) and
  the game thread drops 87 → 81 % (no ZGC load barriers / concurrent cycles competing with it).
  Adopted: the game dir now runs Zulu with the G1 JSON; re-apply it from `config/launcher/` after
  a game update.
- **Caveat that cost two runs (jvm-zulu-1, jvm-zulu-2: 184 fps, p50 4.3 ms, game thread 99 %).**
  The ~500 fps numbers depend on `persistentVbo=true` and `translucentTilesInChunkTexture=true`,
  which are OFF by default (artifacts, 2026-09-19) and were only on through the maintainer's
  Optimizations-tab file `~/Zomboid/pzopt/options.ini`. That file was rewritten at 13:32 (now only
  `hotsaveStaged=true`) and the same route dropped to 184 fps; `u120-mine-1` (148 vs 558 fps) is the
  same effect earlier in the day. Forcing the two keys via `--prop` restores 508. One of them
  (almost certainly the persistent VBO mapping) is worth ~2.7x uncapped, so the artifact question
  is worth solving instead of leaving the key off. Comparisons across runs must check the
  `settings:` line in console.txt. Also: the launcher JSON had carried `-Dzomboid.steam=0` since
  an earlier run; steam=1 vs 0 made no difference (jvm-zulu-1 vs -2), the G1 JSON in the game dir
  keeps steam=0.

## 2026-09-20 (13:55–14:50): the black chunk squares were the light-info chunk gate, not persistentVbo

Question: why does `persistentVbo=true` (worth ~2.7x uncapped) draw black chunk-sized squares on
the left of the screen, and can it be fixed instead of left off. Rig built for it: `run.sh --shot-at
12` holds the camera 12 s into the spinning Rosewood route (`--flag route=S:450 --flag turn=90
--route-seconds 25`, max zoom, 240 cap unless stated, `--no-mangohud`, no dashboard) and captures the
screen twice 2 s apart; `harness/blacktiles.py` counts the 32 px tiles that are entirely black in a
run but drawn in the control (`bs-off-1`, everything default). All 19 runs, one change each:

| run | change vs defaults | fps mean | p99 | black tiles |
|---|---|---|---|---|
| bs-off-1 | control | 216.5 | 11.9 | 0 |
| bs-both-1 | persistentVbo + translucentTiles | 283.9 | 7.5 | 290 |
| bs-vbo-1 | persistentVbo | 226.2 | 11.2 | 225 |
| bs-vbofinish-1 | + glFinish before every map | 134.5 | 17.9 | 2 |
| bs-vboff-1 | + per-frame fence (rewrite after the drawing frame is done) | 221.7 | 11.4 | 284 |
| bs-vbolag2-1 | + frame fence, 2 frames of extra lag | 213.4 | 12.6 | 81 |
| bs-vbodelay-1 | + 40 us CPU park per map, no GPU sync | 191.3 | 11.7 | 9 |
| bs-vboslots4-1 | + 4 storage slots per buffer (reuse after 512 batches) | 215.5 | 11.7 | 303 |
| bs-vboflush-1 | + MAP_FLUSH_EXPLICIT instead of MAP_COHERENT | 208.1 | 11.9 | 147 |
| bs-vbostock-1 | persistentVbo with every other key at stock | 140.8 | 21.8 | 0 |
| bs-vbonobudget-1 | persistentVbo, bake/rebake/lighting budgets off | 214.4 | 14.4 | 237 |
| bs-vbonotrees-1 | persistentVbo, trees/windows out of the chunk texture | 192.3 | 12.3 | 197 |
| bs-vbonostream-1 | persistentVbo, parallel streamer / wake / hand-off off | 218.8 | 11.7 | 148 |
| bs-vbonodepth-1 | persistentVbo, parallelDepthMaps off | 204.3 | 14.8 | 128 |
| bs-vbonolightinfo-1 | persistentVbo, lightInfoChunkGate / OncePerFrame / occlusionSkip off | 200.5 | 12.4 | 1 |
| bs-gatefix-1 | persistentVbo, **gate fix** | 199.9 | 13.1 | 0 |
| bs-gatefix-both-1 | persistentVbo + translucentTiles, gate fix | 278.3 | 7.6 | 0 |
| bs-gatefix-both-2 | same, held at 20 s (no matching control; clean by eye) | 276.2 | 7.9 | n/a |
| bs-gatefix-uncap-1 | same, uncapped, uiRenderOffscreen | **511.7** | 6.7 | 0 |

- **The GPU buffer race hypothesis is dead.** Every synchronisation variant on the persistent
  mapping (per-frame fences, extra lag, four storage slots, explicit flush) left the squares; what
  reduced them were the things that slowed the render thread down (glFinish 2, a CPU-only park 9),
  i.e. timing, not memory. Both captures 2 s apart were identical every time: the black is baked into
  the chunk texture.
- **Cause: `lightInfoChunkGate`** (400 fps pass). In `prepareChunkForUpdating` a square whose light
  info had never been cached (fresh chunk whose lighting pass consumed the JNI dirty bit before the
  level's first bake) stayed `lightInfo == null`, failed the loop's null test and was left out of
  `squareFlags`, so the whole level baked black. Stock refreshes every square unconditionally.
  `persistentVbo` only shifts the render/lighting thread timing enough for the window to open often.
  Fix: the gated branch refreshes any square with null light info. 0 black tiles in every
  configuration afterwards, uncapped 511.7 fps mean / p99 6.7 ms / GPU 92 %: the experimental keys
  keep their whole gain.
- Left as opt-in diagnostics in `GLVertexBufferObject` (`persistentVboFrameFence`, `-FrameLag`,
  `-Slots`, `-Coherent`, `-DelayUs`, `-Finish`; a "persistent VBO:" counter line every 5 s with
  `instrument=true`). The 2026-09-19 "black building lot" (`artfix-opt120-2`) predates the gate and
  was not reproduced today; the 2026-09-19 `translucentTilesInChunkTexture` black floor rectangles
  did not show on this route either. The maintainer confirmed the fix in game the same afternoon and
  both keys are ON by default from this commit (the Optimizations tab labels lose "experimental");
  if either 2026-09-19 report comes back, `--shot-at` + `blacktiles.py` on a copy of the real save is
  the way to bisect it.

## 2026-09-20 (14:54–15:25): scene presets — night with/without torch, thunderstorm

New `run.sh --preset night-torch|night-dark|storm` (spinning Rosewood route, `pzopt.Scene` forces the
hour, weather and torch at world-ready; `harness/CLAUDE.md`). 240 cap, `--no-dashboard`, defaults
(persistentVbo + translucentTiles on). Runs `preset-*`.

| preset | fps mean | p50 | p99 | p99.9 | max | >33 ms | notes |
|---|---|---|---|---|---|---|---|
| night-dark (01:00, no light) | 282 | 3.3 ms | 10.4 | 17.9 | 47 | 1 | `night_strength=1.0` |
| night-torch (01:00, lit HandTorch) | 283 | 3.3 ms | 10.0 | 18.7 | 46 | 1 | beam visible live; no measurable cost |
| storm (save's hour, pinned STAGE_STORM values, strike every 6 s) | **83** | 10.1 ms | **43** | 81 | 571 | 40 | game thread 94 %, GPU 76 % |

- Night alone is free on this route (283 vs ~283 in daylight on the same build).
- **The thunderstorm is a 3.4x frame-time regression and the tail is the worst measured on this
  route**: 8156 chunk bakes per 1800-frame period vs 1526 in clear weather (`lighting=6535` flags vs
  1112: the storm's per-frame ambient/daylight changes dirty the chunk lighting continuously, and every
  lightning strike sets `dirtyRecalcGridStackTime=1` for ~100 frames), plus the rain FX pass. Game
  thread 94 % of a core, render thread 69 %, GPU 76 % (overlay) / 49 % (nvidia-smi): neither is at
  the wall, so this is a "fps < 240 and hardware not saturated" finding.
- GameProfiler A/B, uncapped, direct launcher (15:34, runs `storm-prof-stock` / `storm-prof-opt`,
  `docs/findings-scene-presets-2026-09-20.md` §3): stock 32 fps (p99 143 ms), optimized 70 fps
  (p99 39 ms; the probes cost ~8 %). Game thread 28.9 → 13.4 ms; the bakes are down to 1.4 ms but
  **`FBORenderCell.puddles` stays at 4.5 ms/frame (34 % of the game thread)** — stock
  `renderPuddles` re-filters, re-lights and re-packs every wet square on every level every frame,
  and the render thread re-uploads and redraws them (8.7 ms `buildStateDrawBuffer`, 4.4 ms waiting
  on the game thread). Not the lighting rebakes: the puddle pass is the storm's structural item.
  `sections.py` is per thread now (`--thread game|render`); it used to merge both recordings.
- Fixes (15:42–16:02, `docs/findings-scene-presets-2026-09-20.md` §4): JFR put 73 % of the render
  thread in the rain quads (`VBORenderer` 4 KB buffer, a flush every 28 quads); `vboBatchKb=1024` +
  `vboFastQuads` (new `VBORenderer` override) → submission 8.7 → 6.1 ms. `puddleCache`
  (`pzopt.PuddleCache`, `IsoPuddles` override, slot on `IsoChunk`): packed puddle vertices kept per
  chunk level, lights / jiggle / depth patched per frame → puddles 4.5 → 0.96 ms. Storm route
  uncapped: **83 → 131 fps** (p99 43 → 25 ms), game thread 13.4 → ~7 ms, GPU 71 %. Remaining: the
  rain particle path itself (~100k quads a frame at 5120x2160, walked twice on the game thread).
- Rain tiles + the 6 s rain freeze (16:20–17:00, `docs/findings-scene-presets-2026-09-20.md` §5):
  `rainTiles` (template once, one draw per screen cell) → desktop spinning storm 111 → 188 fps,
  laptop 120 km/h storm drive 84 → 106 (70 before today), laptop clear drive unchanged (~200 fps).
  The rain "vanishing" every ~6 s the maintainer saw was five 50-90 ms stalls per lightning
  strike: the re-bake budget's 3-frame cap released every flash-dirtied chunk texture in one
  frame. Lighting-only re-bakes now have `lightingRebakeBudget=8` / `lightingRebakeMaxFrames=30`:
  storm drive p99.9 57 → 12.5 ms, max 88 → 19, 0 frames over 33 ms (was 48); `lightingRebakeMs=100`
  tried and worse. Runs `storm120-rec` / `-spread` / `-lrb100` / `-lrb100b16` (recorded), `storm-tiles`
  / `storm-notiles`, laptop `lap-*`.
- Torch: the beam was visible on screen in every night-torch run (maintainer watching), with the
  default invisible bench player, and costs nothing measurable (283 vs 282 fps). The `--shot-at`
  captures never show it: torch and dark shots were pixel-identical after the 2 s stand-still hold, so
  the hold-and-capture rig is blind to the player light (lights must be judged live or from
  `--record`); a wrong afternoon was spent concluding the opposite from the shots. Also learned: the
  bench save's pistol has an always-on weapon light that `Scene` strips before `torch=on|off`, and an
  invisible player is what `LightingJNI.playerSet` receives as ghost mode (`visible=true` flag exists,
  off by default: the beam does not need it).
- First run (`preset-night-torch-1`) hung at the loading screen: `ClimateManager.forceDayInfoUpdate()`
  before the first climate tick NPEs every frame in `WAIT_WORLD`; removed, and a scene exception now
  rejects the run instead of looping.

## 2026-09-20 (15:15–15:31): laptop on AC, power profile x stock/optimized on the spinning route

Machine: the laptop again (`diego-flip`, an AYANEO Flip 1S DS: Ryzen AI 9 HX 370 / Radeon 890M, Mesa 26.2.3, 1920x1080),
this time **on AC** with `powerprofilesctl` set to each profile by the maintainer between pairs.
Every run: spinning Rosewood route (`--flag route=S:450 --flag turn=90 --flag zoom=max
--route-seconds 25`), `--prop uncappedFps=true`, `--option uiRenderOffscreen=true`, `--launcher
direct` (no Steam), `--game-profiler`, `--no-mangohud --no-dashboard`, tuned G1 launcher JSON
(`config/launcher/ProjectZomboid64.g1.json`, already installed there). Stock = `--prop
enabled=false` (the master switch: stock code path everywhere, harness only). One run per cell.
Runs `flip-spin-uncap-gp-ac-{perf,balanced,powersave}[-stock]-*`.

| profile | build | fps mean | mean | p99 | p99.9 | max | >33 ms | jitter | CPU (24 c) | GPU | game thread |
|---|---|---|---|---|---|---|---|---|---|---|---|
| performance | stock | 56.2 | 17.8 | 39.3 | 57.5 | 74 | 24 | - | 21 % | 24 % | 99 % |
| performance | optimized | **125.6** | 8.0 | 22.4 | 33.2 | 61 | 4 | 2.4 | 21 % | 42 % | 99 % |
| balanced | stock | 56.4 | 17.7 | 40.3 | 68.0 | 121 | 34 | - | 21 % | 25 % | 99 % |
| balanced | optimized | **124.9** | 8.0 | 22.3 | 33.9 | 62 | 4 | 2.5 | 21 % | 38 % | 99 % |
| power-saver | stock | 40.8 | 24.5 | 63.1 | 97.8 | 126 | 176 | - | 29 % | 19 % | 98 % |
| power-saver | optimized | **64.3** | 15.6 | 42.2 | 75.5 | 105 | 47 | 5.1 | 30 % | 20 % | 98 % |

(ms unless stated; jitter = overlay frame-to-frame; stock runs have no overlay log, so no jitter.)

- **performance and balanced are the same run** on this laptop, stock and optimized alike: the
  game thread is pegged at 99 % of one core either way, so balanced is not clocking the busy core
  down. power-saver is: optimized 125 → 64 fps, 1 %-low 41 → 22 fps, and the tail doubles.
- **Overrides: 2.2x on AC** (56 → 125 fps, p99 40 → 22 ms, spikes 24-34 → 4), 1.6x under
  power-saver, where the side threads (Lighting 78 %, four recalc workers ~10 % each) share the
  smaller power budget with the game thread.
- **Hardware not saturated in any cell** (the objective's own finding): ~20 % of 24 cores, GPU
  ≤ 42 % at 38-41 W. Single game thread is the wall, as on the desktop (~500 fps on this route),
  scaled down by the laptop's clock/IPC. GameProfiler on the slow frames (≥ 20 ms, ~41 per run):
  all of the extra time is `GameWindow.logic` (23 vs 4.7 ms) that its own sub-sections do not cover.
- GC is not it: `gc.log` in the route window shows five stop-the-world pauses of 0.1-17.6 ms; the
  ~300 ms entries `analyze.py` sums are the concurrent mark cycle (background threads).

Thermals over the same route windows (`sysmon.csv`; on this APU `gpu_c` is the edge sensor and
`gpu_w` the package draw, CPU included):

| profile | build | temp start → end (max) °C | package W | fps / W | GPU clock first 5 s → last 5 s |
|---|---|---|---|---|---|
| balanced | optimized | 66 → 72 (74) | 40.6 | 3.08 | 1256 → 2076 MHz |
| balanced | stock | 67 → 68 (70) | 38.4 | 1.47 | 1321 → 1476 |
| performance | optimized | 70 → 77 (77) | 40.5 | 3.10 | 1105 → 2070 |
| performance | stock | 70 → 72 (74) | 37.7 | 1.49 | 1342 → 1450 |
| power-saver | optimized | 63 → 64 (65) | 23.2 | 2.77 | 1536 → 1749 |
| power-saver | stock | 60 → 64 (65) | 20.3 | 2.01 | 1308 → 1525 |

- **No thermal throttling in any run**: peak 77 °C and the clocks rise over the route rather than
  sag. performance-opt started 4 °C warmer than balanced-opt (back to back) and matched it, so
  heat is not what equalised the two profiles; both sit at the same ~41 W package cap.
- power-saver is a **power cap, not a thermal one**: ~21 W, 12 °C cooler; that is the whole
  125 → 64 fps drop.
- **Efficiency**: the overrides roughly double frames per joule at the same package power
  (balanced 1.47 → 3.08 fps/W, performance 1.49 → 3.10); under power-saver 2.01 → 2.77. The
  optimized build on power-saver is still more efficient than stock on any profile, at 57 % of the
  power.
- 25 s is not steady state on a thin laptop; whether sustained play throttles needs the 100 s
  route on `performance` (the sysmon CSV already logs everything needed).

## 2026-09-20 (16:05–16:45): flicker of objects inside buildings, doors, windows, corpses — held re-bakes drew empty per-frame lists

Report (maintainer, normal play, clear weather): objects inside buildings, doors, windows and
corpses appear / disappear. Repro recipe from the maintainer: spawn where the south route ends
and spin. New harness flag `hold=N` (stay on the end square, `turn` keeps spinning) and a
metric, `harness/flicker.py` (pixels that change and revert within 3 frames of a `--record`),
runs `flick-*`, all `--flag route=S:450 --flag speed=90 --flag turn=90 --flag hold=10 --flag
zoom=1`, hold window analysed at `--scale 2560`, the game classes of the 16:01 install.

| run | keys | transient px/frame | what the heat map shows |
|---|---|---|---|
| flick-opt-1 | defaults | 26.2 | papers on the desks, the table beside the player, doors, wall objects |
| flick-stock-1 | `enabled=false` | 3.8 | the spinning player only |
| flick-norebake-1 | `rebakeBudget=0` | 28.1 | same as defaults |
| flick-g1cut-1 | cutawayInvalidateChanged/VisitPrefilter/Fast off, cutawayRadius=0, gridStackInterval=0 | 34.3 | same as defaults |
| flick-g2bake-1 | lightingRebakeMs=0 lightInfoChunkGate/OncePerFrame off, occlusionSkipLightingOnly=false, bakeBudget=0 lightingBudget=0 | 11.5 | edge shimmer only |
| flick-g3tex-1 | windows/translucentTiles/treesInChunkTexture off, persistentVbo=false | 670.7 | everything per-frame flickers (more objects per frame = more flicker) |
| flick-lb0-1 | `lightingBudget=0` | 25.4 | same as defaults |
| flick-lrb0-1 | `lightingRebakeMs=0` | 7.8 | edge shimmer only |
| flick-bb0-1 | `bakeBudget=0` | 22.4 | same as defaults |
| flick-fix-1 | fix, defaults | 11.5 (0.1 at `--scale 1280`; stock 0.0, broken 3.4) | edge shimmer only |

Frames of the table region (flick-opt-1, 29.43 s): fading table + lamp per frame -> opaque table
with the papers (fresh bake) -> table, lamp and papers gone for three frames -> back.

Cause (`docs/override-edits.md`, FBORenderCell entry of this evening): stock
`FBORenderLevels.NLevels.invalidate()` also empties the level's per-frame square lists (items,
obscuring objects, cutaway window frames, corpses, flies, attachments, puddles) outside
`performRenderTiles`, because stock always re-bakes in the same frame. A held re-bake
(`lightingRebakeMs=250`, `rebakeBudget=4`) drew the previous texture with those lists already
empty, so for the held frames the per-frame objects were nowhere. Group 3 was worse because with
windows, translucent tiles and trees per frame there is more per-frame content to lose.

Fix: keep the lists across invalidations when a hold is configured (they only change at a bake,
so they always match the texture on screen), clear them when a chunk object returns to the pool,
and never hold cutaway (2048) re-bakes (their per-frame draws re-test live flags). Cost on the
uncapped spinning route: `flickfix-u-1` 488.7 fps mean, p99 7.2 ms, ~15 % more bakes per period
(`jvm-zulu-g1-1` reference 508.7 / 7.3), GPU 96 % in both.

## 2026-09-20 (17:05–17:15): side-by-side video, stock vs optimized, 120 km/h thunderstorm drive

`harness/stitch-storm-sbs.sh` -> `docs/media/drive-120kmh-storm-stock-vs-optimized.mp4` (3840x810,
42 s, both panes aligned at the car's motion onset, each run's in-game overlay inset at full
resolution). Direct launcher (no Steam), `--flag weather=storm`, `--no-mangohud --no-dashboard`,
`--prop overlay=true overlayFont=Large`.

| run | build | cap | fps mean | p50 / p99 / p99.9 / max ms | >33 ms | GPU / game / render |
|---|---|---|---|---|---|---|
| `sbs-storm120-stock-1` | every pzopt render key off (the `u400show-stock` set + today's keys off) | 300 (framecap.ini won over `--option frameRate=244`; moot at 71 fps) | 71.5 | 13.1 / 67 / 87 / 103 | 42 | 96 % / 83 % / 84 % |
| `sbs-storm120-opt-1` | defaults incl. rain tiles, puddle cache, lighting re-bake spread | none | 268.7 | 3.3 / 8.8 / 12.7 / 19.9 | 0 | 98 % / 61 % / 97 % |

## 2026-09-20 (23:30–23:55): downtown Louisville with the zombie population maxed, stock vs optimized

New bench preset `--preset louisville` (`harness/run.sh`): teleport to 12450,1280 at world-ready
(`start=` flag, `IsoChunkMap.ProcessChunkPos` reloads the grid), sandbox zombie population
multipliers forced to 4 before the chunks load (`population=max`, `Scene` -> native popman), 20 s
settle, the spinning walk (`turn=90`) at 6 tiles/s over 150 tiles (25 s), `see_all=true` (new
`LightingJNI` override: every square seen and visible, else the tall blocks leave most of the screen
never-seen black). ~2,000 zombies loaded at the route start, ~2,500 by the end. Recorded with
`harness/showcase-record.sh louisville stock|opt` (uncapped, in-game overlay, no MangoHud, AV1 HDR),
stitched with `harness/stitch-louisville-sbs.sh` ->
`docs/media/louisville-horde-spin-stock-vs-optimized.mp4` (3840x810, 27 s, aligned at the quit instant).

| run | build | fps mean | p50 / p99 / p99.9 / max ms | >33 ms | GPU / game / render | zombies |
|---|---|---|---|---|---|---|
| `show-louisville-stock-3` | every pzopt key off, uncapped | 23.7 | 39.5 / 94.4 / 135.5 / 160.6 | 402 of 592 | 43 % / 98 % / 28 % | 2503 |
| `show-louisville-opt-3` | defaults, uncapped | 31.7 | 29.8 / 56.6 / 122.6 / 171.0 | 234 of 793 | 42 % / 98 % / 17 % | 2441 |

Both sides are game-thread bound (98 % of a core) with the GPU at ~42 %: this scene is the zombie
update (character update / animation / pathing on the game thread), which no pzopt key touches yet;
the +34 % fps and halved p99 come from the render-side keys (chunk textures, cutaways, light info).
The character update has no pzopt edit; this preset is its benchmark.
Earlier attempts: at 18 tiles/s (`show-louisville-opt-1`, 33.9 fps) the walk outran chunk handoff and
the second half of the route was black; without `see_all` (`-2`, 89.6 fps because most of the screen
was black) the recording was unwatchable. The 5,116-zombie first run also had 21 GC events / 1.2 s in
the window (max 567 ms).

## 2026-09-21 (07:30–07:55): the Windows "micro stutter every 0.5 s" — overlay utilization sampling on the game thread

Several Windows 10 users on older hardware reported a stutter every half second. The only 500 ms
cadence in the overrides was `pzopt.Overlay.sampleUtilization`, which `Overlay.draw()` ran on the game
thread every `UTIL_NS` (500 ms) whether or not the overlay was visible, calling
`OperatingSystemMXBean.getProcessCpuLoad()` and `getCpuLoad()`. On Linux the JDK reads `/proc` for
those (microseconds, so no desktop or laptop run ever showed it). On Windows
(`jdk.management/windows/native/libmanagement_ext/OperatingSystemImpl.c`) they go through PDH:
`getProcessCpuLoad` re-enumerates the "Process" performance object (every process on the machine,
`PdhEnumObjectItems`) on every call to find the JVM's `java#N` instance, then `PdhCollectQueryData`
once per `MIN_UPDATE_INTERVAL` (500 ms) reads the counters of every process. That is 5-50 ms on an
older PC, on the game thread, twice a second: the reported stutter.

Fix (`Overlay`): the sampling moved to a daemon thread (`pzopt-overlay-util`, min priority, started on
the first `draw()` once the game thread id is known); it reads the game / render thread CPU times,
the process and machine load, and the GPU busy share, and publishes them in volatiles. The PDH-backed
calls only run while the overlay is visible or the frame log is on. Nothing periodic is left on the
game thread apart from the 250 ms stats refresh while the overlay is shown (sorting the 5 s window).

Smoke run `util-thread` (spinning Rosewood route, `--prop overlay=true`, `--no-mangohud`): 281 fps mean,
p99 8.7 ms; all four utilization columns of `pzopt-overlay.out` populate from the sampler thread
(game 97 %, GPU 81 %, process 21 %, render 29 %). The stall itself cannot be reproduced on Linux; the
Windows test is the next release build.

Second step (maintainer's call, same morning): the overlay's measurement is now opt-in. New key
`overlaySampling` (Optimizations tab > Performance overlay, "Sample frame times and utilization",
**default false**) gates everything the overlay does — the presented-frame ring, the GL timer queries
on the render thread and the sampler thread. With it off the toggle key draws an 8 s notice in the
overlay's corner ("Performance overlay: sampling is off. Tick ... then restart the game for it to take
effect.") and logs the same line; `overlay=true`, `overlayLog=true` and harness runs imply sampling, so
`--prop overlay=true` runs and `pzopt-overlay.out` are unchanged. Verified with verify runs
`notice-f9`: on this machine `~/Zomboid/pzopt/options.ini` has `overlay=true`, so F9 toggled the
overlay as before; with `--prop overlay=false` F9 drew the notice (`notice-desktop.png` in the run dir).

Laptop check (diego-flip over ssh, same commit built and installed there, runs `lp-sampling*` /
`lp-nosampling*` under its `harness/runs/`): with sampling off (`--mode verify --prop overlay=false`)
the live thread list (`/proc/<pid>/task/*/comm`) has no `pzopt-overlay-u` thread and no
`pzopt-overlay.out` is written; the bench (harness implies sampling) fills all four utilization columns
(cpu 37 %, gpu 19 %, game 89 %, render 24 %), 81 fps mean on the spinning route warm (73 on the first
boot after the rebuild while the caches regenerated, p99.9 361 → 92 ms).

## 2026-09-21 (09:15–09:35): "Project Zomboid Optimiser" (Workshop 3787481250) vs our overrides

prop11's *Project Zomboid Optimiser* 1.4.3 (mod id `MPOptimizer`, Workshop item 3787481250, updated
2026-09-11, 23 k subscribers, 341 ratings; fetched anonymously with `steamcmd +workshop_download_item 108600 3787481250`
and copied to `~/Zomboid/mods/MPOptimizer`, the directory name `--mod` looks up). It is Lua only: no jar,
no class overrides. What it changes at its defaults on Build 42 (read from `42/media/lua`, confirmed by the
console line `[MPOptimizer] Engine Optimizations Applied (Imposters: false, BlendedZombies: 20,
FalloffCount: 5, LightingFPS: 30)`):

- `DebugOptions` flips on `OnGameStart`: `useNewVisibility=true`, `cheapOcclusionCount=true`,
  `zombieAnimationDelay=true`, `threadModelSlotInit=true`; `PerformanceSettings.setNewRoofHiding(true)`,
  `interpolateAnims`, `baseStaticAnimFramerate` 30, `zombieBonusFullspeedFalloff` 4, `modelLighting`,
  `setPerfReflections(false)`; `numberZombiesBlended` 10 → 4 when zoom ≥ 1.5.
- Replaces ~40 of the game's GLSL shaders (`media/shaders/*.vert|.frag`, e.g. puddles, door, outline,
  overlaymask, basiceffect, fogcircle) with its own copies — the only rendering change that is not a toggle.
- A per-tick Lua heartbeat (`Events.OnTick`) dispatching FPS-limiter / zoom LOD / horde / audio /
  weather / GC checks on modulo schedules, a second `OnTick` for the vehicle optimizer every 15 ticks,
  `OnPlayerMove` building culling (only when inside a building above floor 1), a `collectgarbage` hook.
- Forces `textureCompression=true` into `options.ini` via `Core.saveOptions()` on every game start
  (the maintainer's is `false`; the runs pass `--option textureCompression=false` so run.sh backs the
  file up and restores it after the run).
- No-ops on 42.20: the weather clamp writes `RainManager.maxRainSplashObjects` (no such class field) and
  `IsoPuddles.isShaderEnable` (private static, unreachable from Kahlua); `Config.SyncToEngine` looks for a
  `PZOEngineBridge` global that only the author's launcher would provide; the `JVM_*` keys and the
  `Launcher_Optimizer/*.json` files are heap-size launcher JSONs the user copies by hand (none used here).
  Corpse / blood / debris sweeps, the GC purge and the rain / fire particle clamps are opt-in (off).

Runs `pzo-<seg>-<side>` (2026-09-21 09:16–09:33): XWayland, NVIDIA GL 615.71, 5120x2160, max zoom,
Zulu + G1 JSON, uncapped (`uncappedFps=true`), in-game overlay + MangoHud, `--no-dashboard`,
`uiRenderOffscreen=true` from options.ini on every side. `stock` = our build with every runtime and boot
key off (the showcase stock property set, so the harness route driver is present), `mod` = the same set
plus `--mod MPOptimizer` at its defaults (first-launch dialog pre-acknowledged in
`~/Zomboid/Lua/MPOptim_Settings.ini`), `opt` = build defaults. Numbers from the in-game overlay log
(`analyze.py` `overlay:` block, route window), utilization from the overlay and sysmon. One run per cell.

| run | route | fps | frame mean / p99 / p99.9 / max ms | >33 ms | stdev / jitter | 1 % low | < 240 fps | GPU busy | game thread | streamer wait mean / p99 |
|---|---|---|---|---|---|---|---|---|---|---|
| `pzo-drive120-stock` | E:1200 at 122 km/h | 166.7 | 6.0 / 15.5 / 18.8 / 49.7 | 2 | 4.7 / 1.1 | 65 | 42 % | 90 % | 68 % | 138 / 316 ms |
| `pzo-drive120-mod` | E:1200 at 122 km/h | 159.0 | 6.3 / 15.6 / 18.1 / 23.5 | 0 | 4.7 / 1.2 | 64 | 45 % | 88 % | 67 % | 150 / 321 ms |
| `pzo-drive120-opt` | E:1200 at 122 km/h | **481.2** | 2.1 / 8.8 / 14.9 / 25.4 | 0 | 1.6 / 1.1 | 113 | 7 % | 97 % | 56 % | 4.7 / 21.7 ms |
| `pzo-storm120-stock` | same, thunderstorm | 74.6 | 13.4 / 58.7 / 83.8 / 96.9 | 44 | 7.0 / 3.0 | 17 | 100 % | 83 % | 90 % | 153 / 313 ms |
| `pzo-storm120-mod` | same, thunderstorm | 71.7 | 13.9 / 66.2 / 80.5 / 89.5 | 57 | 8.1 / 2.1 | 15 | 100 % | 85 % | 86 % | 145 / 318 ms |
| `pzo-storm120-opt` (093136) | same, thunderstorm | **245.6** | 4.1 / 13.8 / 18.8 / 25.5 | 0 | 2.4 / 1.3 | 72 | 26 % | 98 % | 68 % | 5.0 / 18.0 ms |
| `pzo-spin-stock` | S:450 turn=90, 25 s | 135.4 | 7.4 / 27.2 / 42.1 / 74.6 | 14 | 5.3 / 3.0 | 37 | 72 % | 83 % | 91 % | 151 / 304 ms |
| `pzo-spin-mod` | S:450 turn=90, 25 s | 127.8 | 7.8 / 29.5 / 40.0 / 63.9 | 19 | 5.4 / 3.3 | 34 | 74 % | 83 % | 92 % | 146 / 305 ms |
| `pzo-spin-opt` | S:450 turn=90, 25 s | **456.2** | 2.2 / 8.5 / 17.4 / 38.3 | 1 | 1.6 / 0.8 | 118 | 6 % | 96 % | 80 % | 9.0 / 143 ms |

Findings:
- **The Optimiser is stock within noise on all three routes**, and on the slow side of it each time
  (−5 % fps, p99 equal or slightly worse, more >33 ms frames in the storm and the spin). Nothing it toggles
  is on the hot path of these routes: the game thread stays 86–92 % busy on the spin and storm routes and
  the GPU 83–90 % busy uncapped, exactly like stock; the chunk streamer still queues 140–150 ms per chunk.
  Its own per-tick Lua work is the likeliest source of the small loss.
- Our build is 2.9x (drive), 3.3x (storm) and 3.4x (spin) the Optimiser's frame rate with a tail 2–5x
  tighter (p99 15.6 → 8.8, 66 → 14, 29.5 → 8.5 ms) and no >33 ms frames on the drives; streamer queue
  wait 150 → 5 ms. On the drives the CPU sits at ~20 % of the machine on every side: the difference is the
  GPU work per frame (chunk-texture baking and the puddle / rain paths), which is why the mod's toggles
  cannot reach it.
- Harness note: at 450+ fps the drive controller's per-frame steering wanders more (lateral up to
  10–13 tiles vs ≤ 4 at 160 fps) and two of the three `pzo-storm120-opt` attempts clipped roadside
  objects (speed −18 km/h, ~8 s of recovery, routes of 47 and 45 s; 265 / 260 fps mean, kept in
  `harness/runs/` but not in the table). The third attempt (093136) drove the 38.6 s route clean.
  Stock/mod sides never left the road.

### 10:35–10:50 addendum: the full stack — PZO-Launcher engine jar + native lib + its JVM flags

The Workshop mod's "[+] JVM Engine" features come from the author's separate
[PZO-Launcher](https://github.com/prop11/PZO-Launcher) (release V0.9.7.8, 2026-09-15). On Linux its
`pzo_optimizer.sh` copies `PZOptimEngine.jar` + `libpzo_native64.so` into the game dir and rewrites
`ProjectZomboid64.json`: `mainClass` → `com/pzoptimizer/PZOEntrypoint`, the jar first on the classpath,
`-agentlib:pzo_native64` (JVMTI + JNI: thread priority / affinity, timer, AVX2 distance batches, a
miniz inflater), `-Xmx8192m` (its tier for 16–31 GB), G1 with `IHOP=45`, `G1ReservePercent=15`,
`AlwaysPreTouch`, `+UseCompactObjectHeaders`, `+UseSuperWord`, `MaxInlineLevel=15`,
`InlineSmallCode=2500`, `+UseNUMA`, no `-Xms`. The installer only searches `~/.local/share/Steam`
paths, so `/tmp/pzo-full.sh` applied the same edits by hand for each run and restored the original JSON
(diff-identical afterwards) and removed the files; its `ZomboidConfigMigrator` also rewrites the JSON at
boot when it does not find its own flags. The entrypoint then arms ~60 "governors" (its log
`~/Zomboid/Lua/pzo_engine.log`): a 12-worker `MultiCoreChunkStreamer` hooked into
`WorldStreamer.jobQueue`, a frame-budgeted `ChunkIngestionPacer`, `VehicleTravelOptimizer` (replaces the
`IsoChunkMap` lock, zombie-simulation governor while driving), `EngineThreadGovernor` (renames and
re-prioritises the game and render threads: `PZO-MainSimulationThread`, `PZO-RenderThread`),
`PredictiveChunkStreamer`, GL "shadow state", a `HighPrecisionTimer`, plus seven small bytecode patches
(`IsoChunkMap.calculateZExtentsForChunkMap` loop bound, `IsoGridSquare.splatBlood` early-out,
`BaseVehicle.addKeyToGloveBox` null guard, `FBORenderLevels` bounds guards, `IsoChunk$SanityCheck.log`
and `SpriteConfig` warn no-ops, `HumanVisual.skinTexture` default). Only its SQLite WAL toggles failed.
It also raises the game's `DebugType` log severities to Error, which silences our `[pzopt] harness:`
console lines (the run files are complete; read `pzopt-bench.out` instead of the console).

Runs `pzo-<seg>-full`: the `mod` cell plus the engine as above (stock code path, Lua mod at defaults,
engine at defaults; Steam launcher this time — Steam had logged back in — the JSON keeps
`-Dzomboid.steam=1`). Same settings and windows as the table above.

| run | route | fps | frame mean / p99 / p99.9 / max ms | >33 ms | stdev / jitter | 1 % low | < 240 fps | GPU busy | game thread | streamer wait mean / p99 |
|---|---|---|---|---|---|---|---|---|---|---|
| `pzo-drive120-full` | E:1200 at 122 km/h | 165.3 | 6.1 / 15.5 / 19.4 / 23.7 | 0 | 4.7 / 1.2 | 65 | 43 % | 89 % | 71 % | 157 / 332 ms |
| `pzo-storm120-full` | same, thunderstorm | 72.2 | 13.9 / 65.7 / 79.4 / 101.3 | 62 | 8.4 / 2.4 | 15 | 100 % | 83 % | 88 % | 144 / 304 ms |
| `pzo-spin-full` | S:450 turn=90, 25 s | 128.6 | 7.8 / 28.5 / 42.4 / 60.1 | 14 | 5.5 / 3.3 | 35 | 75 % | 83 % | 92 % | 158 / 306 ms |

- **The full stack is stock within noise too**: 165 vs 167 fps on the drive, 72 vs 75 in the storm,
  129 vs 135 on the spin; p99 / p99.9 unchanged; the chunk streamer's queue wait is unchanged at
  ~150 ms mean / ~310 ms p99 despite the "12 parallel chunk streaming workers" (the harness counts
  every chunk from `World Streamer`, so its dispatcher did not take the work over, or took it over
  without changing when chunks reach the game thread). The lighting thread runs busier with the mod
  (21 → 37–48 %; its `EngineFeaturesTuner` sets a 15 fps lighting rate and the Lua side 30).
- Nothing in either half touches what limits these routes: the render thread's per-frame chunk /
  tree / translucent work and the game thread's world update. Our build stays 2.9x / 3.4x / 3.5x
  ahead of the full stack with a 2–5x tighter tail.
- Cleanup: launcher JSON diff-identical, no `PZO*` / `libpzo*` / `.bak` files in the game dir, the
  engine's `~/Zomboid/Lua/pzo_engine.log`, `pzo_update.json`, `Logs/pzo_stutter_diagnostics.log`
  removed. The Lua mod stays at `~/Zomboid/mods/MPOptimizer` (not enabled), the engine release under
  `/tmp/pzo`.

## 2026-09-21 (09:50–10:30): the Dell laptop (i5-6300HQ / GTX 960M), stock vs optimized, spin and 120 km/h drive

Machine: `diego-dell` (192.168.0.109): Core i5-6300HQ (4 cores, no SMT, 2.3 GHz), GTX 960M via
PRIME offload (NVIDIA 580.178.04, `__NV_PRIME_RENDER_OFFLOAD=1 __GLX_VENDOR_LIBRARY_NAME=nvidia`),
7.8 GB RAM, SATA SSD, KDE Wayland 1920x1080, on AC; game 42.20.4 `b0bbce05d5` with **its stock
launcher JSON: `-Xmx3072m -XX:+UseZGC`** (the desktop runs on the tuned G1 JSON with a 4 GB heap).
Runs over ssh from the desktop, game started directly (never through Steam), `--no-mangohud`
(in-game overlay log), `--no-dashboard`, `--option frameRate=240`, max zoom = 2.0 on this display
(the desktop's is 2.5). "Stock" = the build installed with `--prop enabled=false` (every override on
the build-mismatch stock path, harness plumbing only). One run per cell. Runs `dell-*`.

| run | route | fps | mean | p50 / p90 | p99 | p99.9 / max | >33 ms | cpu | gpu | GC cycles in window | game-thread alloc stalls |
|---|---|---|---|---|---|---|---|---|---|---|---|
| dell-spin-stock-1 | spin S:450 turn=90 | 6.8 | 147 | 120 / 221 | 492 | 2711 / 3472 | 280 / 283 | 98 % | 20 % | 2 (14 s wall) | 2, 0.7 s |
| dell-spin-opt-1 | spin S:450 turn=90 | **9.1** | **110** | 86 / 183 | **328** | 1451 / 1548 | 306 / 319 | 98 % | 26 % | 7 (71 s wall) | 2, 0.5 s |
| dell-drive120-stock-1 | E:1200 kmh=193 | **25.5** | **39** | 30 / 65 | **141** | 346 / 2654 | 454 / 1098 | 99 % | 39 % | 3 (16 s wall) | 2, 1.2 s |
| dell-drive120-opt-1 | E:1200 kmh=193 | 11.6 | 86 | 62 / 125 | 242 | 3812 / 6730 | 547 / 649 | 100 % | 39 % | 9 (85 s wall) | 10, 6.7 s |

Milliseconds except fps and counts; cpu/gpu from `sysmon.sh` over the route window; GC from
`gc.log` (ZGC concurrent cycles overlapping the window and `Allocation Stall (MainThread)` lines).

Findings:
- **This machine is CPU-saturated in every cell** (all four cores at 98–100 %, GPU never above
  40 %), so the frame rate is the sum of every thread that wants a core, not the game thread's
  own frame. Stock puts ~330 % of a core into `MainThread` 65 / `World Streamer` 50 / `Lighting`
  49 / render 22; optimized puts the same ~320 % into `MainThread` 54 / `Lighting` 35 / three
  `pzopt-recalc` workers 31 / render 12 / streamer 9. The game thread gets **fewer** cycles with
  the overrides on because the recalc pool and ZGC compete with it for four cores.
- **The 3 GB ZGC heap is the wall on the drive.** The optimized drive ran 9 GC cycles in a 56 s
  window and froze the game thread in ten allocation stalls (6.7 s, max 3.2 s: the p99.9 of
  3.8 s), stock three cycles and two stalls. ZGC needs idle cores to finish its concurrent cycles;
  with none it falls behind the allocation rate and stalls the allocator. Same mechanism as the
  laptop's G1 > ZGC finding (2026-09-20 05:05 section), much worse here with half the cores.
- On the spinning route the overrides still win (6.8 → 9.1 fps, p99 492 → 328 ms) because the
  route is streamer-bound (1064 chunks in 34–38 s: stock queue wait 419 ms mean, optimized 152 ms).
  On the drive (3,000 chunks in 40–49 s) the heap/CPU budget dominates and the overrides lose
  (25.5 → 11.6 fps).
- Not a launcher/renderer artefact: both sides identical launch (direct, PRIME, same JSON,
  same options), routes complete, `OpenGL version: 4.6.0 NVIDIA` in every console.
- The optimized drive run sat **22 min at "Creating display"** (`pzopt-loadtrace.out`: gap
  between `Creating display` and `closest width=320 freq=58`, one thread at 106 % of a core,
  GPU 0) before the world loaded and the route ran normally; the route-window numbers are from
  after that. KDE's idle lock had refused the previous launch attempt (`the desktop session is
  locked`), `loginctl unlock-session` cleared it and a `SimulateUserActivity` D-Bus poke every 30 s
  did not keep it away; the journal shows no lock/DPMS event, so what held the window creation is
  open. `systemd-inhibit` needs polkit over ssh.

Next on this class of machine (not run, maintainer's call): the G1 launcher JSON
(`config/launcher/ProjectZomboid64.g1.json`) and/or `-Xmx4096m` on the Dell, and `workers=1`
for the recalc pool when `cores <= 4` (the pool's three threads on four cores starve the game
thread). Both are one `--gc g1` / `--prop workers=1` run each.

### 10:30–10:58 follow-up: redo of the optimized drive, GC / heap / pool one-at-a-time

Same machine, same route (E:1200, kmh=193, max zoom 2.0, 240 cap, direct launch), one run per
cell, KDE sleep + lock held off by a D-Bus inhibit holder (`org.freedesktop.ScreenSaver` +
`PowerManagement.Inhibit` from a long-lived python process; the 22 min hole in `opt-1` was the
laptop suspending). Boot = launch → Continue, load = Continue → world ready (`loadtime.py`).

| run | GC | heap | recalc workers | fps | mean | p50 | p99 | p99.9 / max | game thread % | boot | load |
|---|---|---|---|---|---|---|---|---|---|---|---|
| drive120-stock-1 | ZGC | 3 GB | – | 25.5 | 39.3 | 29.8 | 141 | 346 / 2654 | 65 | 24.9 s | 92.0 s |
| drive120-stock-g1-2 | G1 | 3 GB | – | 22.3 | 44.8 | 32.4 | 176 | 335 / 2306 | 55 | 26.3 s | 79.2 s |
| drive120-opt-1 | ZGC | 3 GB | 3 | 11.6 | 86.0 | 62.4 | 242 | 3812 / 6730 | 54 | (suspend) | 43.6 s |
| drive120-opt-2 | ZGC | 3 GB | 3 | 13.0 | 76.8 | 61.4 | 228 | 946 / 2035 | 54 | 26.0 s | 43.3 s |
| drive120-opt-x4g-1 | ZGC | **4 GB** | 3 | 14.2 | 70.5 | 54.7 | 201 | 1518 / 2830 | 53 | 22.4 s | 50.2 s |
| drive120-opt-w1-1 | ZGC | 3 GB | **1** | **25.7** | **39.0** | 30.3 | **112** | 437 / 3007 | **72** | 22.3 s | 44.8 s |
| drive120-opt-g1-1 | **G1** | 3 GB | 3 | 22.9 | 43.7 | 34.4 | 148 | 420 / 1490 | 63 | 23.1 s | **30.4 s** |
| drive120-opt-g1w1-1 | G1 | 3 GB | 1 | 17.5 | 57.3 | 46.2 | 190 | 561 / 1346 | 54 | 27.4 s | 39.5 s |

Findings:
- **The optimized defaults halve the drive frame rate on this 4-core machine, reproducibly**
  (11.6 / 13.0 / 14.2 fps over three runs vs 22–26 stock). Not the heap: 4 GB changes nothing
  (still 8 game-thread allocation stalls, 4.5 s).
- **Either fix alone brings it back to stock**: `workers=1` (no `pzopt-recalc` threads; recalc
  back on the World Streamer) gives the best line of the day, 25.7 fps / p99 112 ms with the game
  thread at 72 % of a core; `--gc g1` gives 22.9 fps and zero allocation stalls (21 pauses,
  0.3 s total, max 69–85 ms). Both together read 17.5 fps, inside this box's run-to-run spread
  (stock ZGC vs G1 differ by 3 fps on identical work; no thermal throttling: counters 0, 52–56 °C).
- Mechanism: with four cores and every one pegged, the three recalc workers plus ZGC's concurrent
  threads take cycles straight from the game thread (54 % of a core with the pool vs 65–72 %
  without). The pool sizing (`workers` = cores − 1) assumes idle cores; on ≤ 4 cores it should
  default to 1. ZGC additionally stalls the allocator when it cannot keep up (2–10 stalls of up to
  3.6 s per run in every ZGC cell with the pool on).
- Load: the pzopt caches take Continue → world from 79–92 s (stock) to 30–45 s (D "animations +
  file tasks" 32–38 s → 0.2 s once `~/Zomboid/pzopt/` is warm). Boot to the menu is 22–27 s on
  every row; the desktop's boot gains do not show on this CPU.
- `stock-g1-1` died at t=34 s with an NVIDIA `Xid 69` class error and SIGABRT in the GL driver
  (960M, driver 580.178.04): a driver fault, retried as `-2`.

Proposal from this: default `workers` to 1 when `cores <= 4` (one line in `Config`), and note in
the README that ZGC-shipping launcher JSONs on 4-core machines benefit from `--gc g1` / the G1
JSON. Not changed yet.

## 2026-09-21 (10:38–11:05): the other Workshop performance mods, same three routes

Workshop text search (Build 42 tag) for optimization / optimizer / fps / performance / lag / stutter /
smooth / boost, ranked by current subscribers (`/tmp/pzo/workshop-candidates.json`). Taken: every general
client-side performance mod that targets 42.20. Skipped: BetterFPS_B42 (80 k, deprecated 2025-11, its page
points to Zed's), HigherFPS (6 k, only removes the 244 cap; every run here is uncapped already), Undying
Optimizer (11 k, UI/menu only), Horde Optimizer (5 k, horde-only), Lucy's Streaming Cuts (DAMN-library-only),
RenderLessZombie (2025, manual file copy), Potato textures (18 k, the same idea as ETO with fewer subscribers).
Downloaded with `steamcmd +login anonymous +workshop_download_item 108600 <id>`, copied to
`~/Zomboid/mods/<mod id>`, enabled with `--mod`, ZombieBuddy 2.3.3 through `--vmarg -javaagent:...`.
Runs `wm-<seg>-<side>` (helper `/tmp/pzo-mods.sh`): the `pzo-*-stock` set-up (our build, every key off,
uncapped, overlay log, no dashboard) plus one mod at a time, one run per cell, direct launcher.

| side | mod (Workshop id, subscribers) | what it is on 42.20 | verified active by |
|---|---|---|---|
| `tempo` | Tempo – A Performance & FPS Optimizer 1.2.0 (3736629791, 42 k) | Lua: OnTick/OnRenderTick frame sampler and spike attribution, context-menu `calcWidth` memo, options page; defaults | `[Tempo_PerfKit]` lines, build SHA verified |
| `tempopatch` | Tempo + its optional class shadows for 42.20.4 (`IsoChunkMap` 2.5 ms/frame chunk-finalize budget, `IsoWorld` 3D-zombie cap at the vanilla 510) | copied over our `IsoChunkMap.class` for the run (nothing else in our tree calls pzopt members on it); its `PerformanceSettings` shadow left out (ours carries the frame limiter / options tab the harness build's Lua needs; the only thing it adds is a `numberZombiesBlended` override) | `[ChunkBudget] engine shadow ACTIVE`, `3D model cap shadow ACTIVE` |
| `multicpu` | Multi-Cpu Enhance 2.1 (3459875383, 28 k) | its `ProjectZomboid64.json`: `-Xmx8192m`, `+UseParallelGC`, 4 GC threads, `+UseNUMA`, `+AlwaysPreTouch`, `+DisableExplicitGC`, `+ParallelRefProcEnabled`, string dedup; library path set to `natives/` for Linux, `windows` block dropped | `launcher vmArgs` line, `gc.log` |
| `eto` | Every Texture Optimized: Well Balanced 1.2.1 (3119788162, 616 k) | 6,142 re-encoded PNGs replacing the game's textures (`--mod ETO_B`) | 18,420 `mod "ETO_B" overrides` lines |
| `lugli` | Lugli – Optimizations 1.0.0 (3790863696, 3 k) | ZombieBuddy jar: wind gate (calm-wind vegetation left in the chunk texture), `calculateZExtentsForChunkMap` 19×19 instead of 361×361, room-square index, UI tick stagger, Lua chunk-event spread | 13 methods patched, `[Lugli/Opt/zextents] active`, `windgate ... CALM (baked)` |
| `zeds` | Zed's Better FPS – B42.20.2 Fix 1.0.0 (3782613536, 9 k; the 47 k original 3622986450 ships a 42.13 jar we measured on 09-19) | ZombieBuddy jar; every optimization tick box on via `ModOptions.ini` (IndieGL state cache, sprite batching, ring buffer, DefaultShader, 3D models, IsoMovingObject separation), render distance and cap at default | 17 methods patched incl. `IsoMovingObject.separate`, no errors |

Overlay log, route window, fps mean / frame mean, p99, p99.9, max ms / >33 ms frames / 1 %-low / GPU busy.

| route | stock | Tempo | Tempo + shadows | Multi-Cpu | ETO | Lugli | Zed's fix | **ours** |
|---|---|---|---|---|---|---|---|---|
| drive 120 km/h | 166.7 / 6.0, 15.5, 18.8, 49.7 / 2 / 65 / 90 % | 160.0 / 6.3, 15.3, 18.2, 24.1 / 0 / 65 / 88 % | 162.1 / 6.2, 15.3, 17.9, 27.0 / 0 / 65 / 88 % | 169.4 / 5.9, 15.2, 17.8, **347** / 1 / 66 / 89 % | 163.1 / 6.1, 15.2, 18.0, 27.8 / 0 / 66 / 88 % | 162.0 / 6.2, 15.3, 18.0, 21.8 / 0 / 65 / 88 % | 161.4 / 6.2, 15.2, 18.2, 27.7 / 0 / 66 / 87 % | **481.2** / 2.1, 8.8, 14.9, 25.4 / 0 / 113 / 97 % |
| storm 120 km/h | 74.6 / 13.4, 58.7, 83.8, 96.9 / 44 / 17 / 83 % | 70.8 / 14.1, 61.0, 98.2, 118 / 44 / 16 / 80 % | 75.7 / 13.2, 56.9, 85.1, 104 / 45 / 18 / 83 % | 73.8 / 13.5, 61.9, 87.0, **339** / 42 / 16 / 85 % | 72.3 / 13.8, 63.3, 85.9, 98.8 / 46 / 16 / 82 % | 72.8 / 13.7, 60.0, 82.4, 113 / 43 / 17 / 86 % | 74.5 / 13.4, 59.0, 81.7, 91.1 / 44 / 17 / 87 % | **245.6** / 4.1, 13.8, 18.8, 25.5 / 0 / 72 / 98 % |
| Rosewood spin | 135.4 / 7.4, 27.2, 42.1, 74.6 / 14 / 37 / 83 % | 130.0 / 7.7, 27.9, 39.9, 71.6 / 13 / 36 / 81 % | 130.5 / 7.7, 30.3, 44.9, 67.5 / 18 / 33 / 82 % | 135.9 / 7.4, 28.7, 46.6, **368** / 16 / 35 / 82 % | 134.6 / 7.4, 28.8, 41.0, 67.3 / 14 / 35 / 82 % | 135.2 / 7.4, 28.0, 43.3, 63.1 / 12 / 36 / 81 % | 134.3 / 7.4, 27.7, 39.4, 67.0 / 15 / 36 / 83 % | **456.2** / 2.2, 8.5, 17.4, 38.3 / 1 / 118 / 96 % |

Findings:
- **Every mod is within run-to-run noise of stock** on all three routes (fps ±4 %, p99 ±3 ms, the same
  >33 ms frame counts), the Optimiser's 12 "governors" included (section above). None of them touches the
  per-frame work that sets the frame time here: the render thread's chunk / tree / translucent drawing and the
  game thread's world update; GPU busy stays 80–90 % and the streamer queue wait ~150 ms on every side.
- **Multi-Cpu Enhance adds stalls.** Its `-XX:+UseParallelGC` (a stop-the-world collector; the shipped
  game uses G1 with `MaxGCPauseMillis=25`) produced one 305–350 ms *Pause Full* inside every route
  window (two on the spin) — the 340–370 ms max frames in the table — where the stock G1 run's longest
  pause was 21 ms. The 8 GB heap and pre-touch do not change the mean. Its page says "optimized GC +
  memory = less lag spikes"; measured, the opposite.
- Tempo's Lua side costs ~4 % (the per-frame sampler on the game thread, as the Optimiser's heartbeat
  does); its `IsoChunkMap` chunk budget gets it back on the drives (the chunk finalize work is spread
  the way our `chunkHandoffDivisor` spreads it) but does nothing for p99 at 122 km/h, where the queue
  wait, not the finalize, is the tail. Lugli's wind gate needs calm wind and zoomed-out vegetation; the
  bench save's wind is above its threshold on the drives (`windType 1..3 -> CALM (baked)` fires at boot
  only) and the spin route is town. ETO changes VRAM, not frame time, on a 24 GB card. Zed's 42.20 fix
  applies cleanly (the 09-19 crash in `IsoMovingObject.separate` is gone) and measures like its 42.13 jar.
- Our build is 2.8–3.0x (drive), 3.2–3.5x (storm) and 3.4–3.5x (spin) every one of them, with the
  p99 2–4x tighter and no >33 ms frames on the drives.
- Cleanup: class files and launcher JSON diff-identical after each run (the `tempopatch` and `multicpu`
  helpers restore on exit), the mods stay under `~/Zomboid/mods` disabled, `ModOptions.ini` keeps the
  `ZBBetterFPSB4220Fix` lines.

## 2026-09-21 (11:30–13:30): low-end profile for the Dell — towards 60 fps on a 4-core i5-6300HQ / GTX 960M

Objective set by the maintainer at 11:20: "make the Dell run the game at 60 fps locked". Machine and
routes as in the morning sections; every run in this section is `--gc g1` (ZGC's allocation stalls
made same-config runs differ 2x on this box: 4,300–4,700 stalls per ZGC run, 0 under G1) under the
maintainer's new `scx_cosmos` scheduler, direct launch, no MangoHud, `--no-dashboard`, max zoom
(2.0 at 1920x1080), 240 cap. Two routes: **drive** = the 120 km/h E:1200 route (72 chunks/s, the
stress case) and **walk** = `route=S:120 speed=3 turn=90` (3 tiles/s through Rosewood with the
facing spinning, 9 chunks/s; 40 s: the play case). Runs `dell-lo-*`, `dell-walk-*`, `dell-v6-*`,
`dell-v7-*`.

### Where the game thread went (JFR, `gametree.py`)

Drive, optimized defaults (63 ms frames with JFR): chunk-texture bakes 35 % (floors 10, the tree
pass 7.6 — of which 4 is its 5x5-chunk readiness scan — object render info 3.7, minus-floor 3.6,
puddle geometry 1.7), world update 26 % (chunk hand-off `loadInMainThread` 10 %: half of it the
neighbour recalcs that stay on the game thread; moving objects 7.5), Lua UI 13 % (render 7.4 +
update 5.4), lighting apply 5, `ProcessChunkPos` 3. The game thread was blocked only 6 % of the
window; the rest of its lost time was preemption by the three recalc workers (41 % of a core), the
lighting thread (34 %) and the GC on four cores.

Walk, optimized defaults (21 ms frames): Lua UI 19 % (render 12.6 + update 6.5), moving objects 14
(+ player post-update 4), bakes 8.6 (cutaway / lighting re-bakes while the facing spins),
translucent objects 4.6, lighting apply 5.4, newly-on-screen chunk checks 4, vispoly 2.5,
`FishSchoolManager.generateSplashes` 1. Nothing key-sized is left there.

### Key A/Bs (G1, cosmos; Steam client still resident on the box until 12:40)

| drive 120 | fps | mean | p99 | p99.9 / max | >33 ms |
|---|---|---|---|---|---|
| stock | 29.1 | 34.4 | 135 | 327 / 1721 | 382 |
| defaults (3 recalc workers) | 21.2 | 47.2 | 148 | 436 / 2052 | 600 |
| workers=1 | 19.5 | 51.3 | 117 | 603 / 2081 | 629 |
| workers=1 + treeBakePass=false (old tree bake) | 28.6 | 35.0 | 103 | 257 / 1890 | 426 |
| **workers=1 + treesInChunkTexture=false (trees per frame)** | **42.7** | **23.4** | **97** | **193 / 236** | **261** |
| workers=1 + treeBakePass=false + bake/lighting budgets | 33.5 | 29.9 | 94 | 282 / 1448 | 313 |

| walk | fps | mean | p99 | p99.9 / max | >33 ms |
|---|---|---|---|---|---|
| stock | 31.5 | 31.7 | 136 | 235 / 2333 | 377 |
| **defaults** | **47.3** | **21.1** | **67** | 308 / 328 | **140** |
| workers=1 | 37.6 | 26.6 | 100 | 355 / 1313 | 215 |
| workers=1 + trees per frame | 32.7 | 30.6 | 117 | 348 / 2068 | 307 |
| workers=1 + treeBakePass=false + budgets | 37.9 | 26.4 | 101 | 267 / 1831 | 221 |

The tree bake is the swing factor and it swings both ways: at 120 km/h a chunk texture lives a
second or two and baking its trees (own texture, the tree pass's neighbour copies and its 5x5-chunk
scan) costs more game-thread time than drawing them per frame for that long (19.5 → 42.7 fps);
walking, the bake amortises over hundreds of frames (per-frame trees 32.7 vs baked 47.3). Hence
`treeBakeMaxChunksPerSec` (pzopt.ChunkRate, override-edits entry of this afternoon): above that many
chunk hand-offs per second new textures are baked without trees. Budgets (`bakeBudget=4
lightingBudget=4 lightingRebakeMs=1000 lightingRebakeBudget=2 chunkHandoffDivisor=16
cutawayRadius=3 gridStackInterval=16`), per-frame windows, per-frame translucent tiles and
`bakeBudget=2` were each within noise of the trees-per-frame line on the drive (matrix `dell-lo-g1-nt-*`,
27.9–30.3 fps, but that batch ran under growing memory pressure — see below — so it is not
conclusive).

### The Steam client was eating the box

At 12:32 the repeats of the best drive config and of stock read 22 / 20 fps against 43 / 29 an
hour earlier, with a 129 s world load: 7.8 GB RAM, the game at 2.2 GB RSS, three Steam
`steamwebhelper` processes resident since boot (~0.35 cores between them), 2 GB in zram swap,
`kswapd0` busy, memory PSI `some` 9 %. `steam -shutdown` on the Dell (used memory 4.5 → 1.9 GB,
swap 2.0 → 0.9 GB). Every number below is with Steam down; the morning's Dell numbers all carry
that handicap.

### Validation of the worktree build (`low-end-profile`: `workers` defaults to 1 on ≤ 4 cores, `treeBakeMaxChunksPerSec`, the Options tab profile button)

| Dell, G1, cosmos, Steam down | fps | mean | p50 | p99 | p99.9 / max | >33 ms | load |
|---|---|---|---|---|---|---|---|
| drive 120 stock (×2) | 43.4 / 44.3 | 23.0 / 22.6 | 18.5 | 79 / 82 | 148 / 256 | 298 / 257 | 70 / 65 s |
| drive 120 new defaults (workers=1) | 33.5 | 29.9 | 27.4 | 71 | 184 / 222 | 390 | 31 s |
| **drive 120 + treeBakeMaxChunksPerSec=24 (×2)** | **60.3 / 61.6** | **16.6 / 16.2** | 13.8 | 67 / 57 | 99 / 181 | 119 / 118 | 23 / 25 s |
| walk stock | 46.1 | 21.7 | 18.1 | 74 | 214 / 279 | 190 | 71 s |
| walk new defaults | 63.0 | 15.9 | 14.5 | 38 | 151 / 319 | 43 | 68 s |
| **walk + treeBakeMaxChunksPerSec=24** | **74.7** | **13.4** | 12.0 | 37 | 113 / 303 | 38 | 26 s |

The adaptive mode engaged for 1,640 frames of the drive (86 chunks/s at the peak) and 42 frames of
the walk (the start burst). Against stock on the same box: drive 43.9 → 61 fps (+39 %, p99 80 →
62 ms), walk 46.1 → 74.7 (+62 %, p99 74 → 37 ms), world load 70 → 25 s. **Mean frame time is at or
under 16.7 ms on both routes, i.e. 60 fps on average; it is not locked**: 3 % (drive) / 1.3 % (walk)
of the frames are over 33 ms, scattered about a second apart (33–50 ms, a few of 100–300 ms).

### Tail attribution and the last two levers

`attribute.py` on a JFR of the adaptive walk (32 slow frames ≥ 33 ms of 3,047): an ordinary
frame is 12.6 ms, a slow one 66.8, and the extra 54 ms is spread over everything in proportion
(logic +30: world update +19, Lua VM +9, vehicles +6.5, chunk hand-off +4.5; render +22) with only
40 % of the slow frames' wall time sampled on the game thread at all. That is the game thread
**off-CPU**: four cores at 96–100 %, so whenever another thread bursts (the lighting thread at
50–65 % of a core, a chunk row's recalc, the render thread's uploads, a G1 young pause — 68–100 ms
each with the plain `--gc g1` flag) the game thread waits for a core. Not a hot spot to trim; the
lever left is the other threads' work. Two of them are stock Display options at their defaults on
the Dell: `lightFPS` (the lighting thread's rate, 15) and `uiRenderFPS` (the offscreen UI redraw,
60).

| adaptive build + | walk fps | mean | p99 | >33 ms | drive fps | mean | p99 | p99.9 / max | >33 ms |
|---|---|---|---|---|---|---|---|---|---|
| (nothing; ×3 / ×2) | 74.7 / 74.0 / 66.4 | 13.4–15.1 | 33–40 | 28–48 | 60.3 / 61.6 | 16.6 / 16.2 | 67 / 57 | 99–131 / 154–181 | 119 / 118 |
| lightFPS=10 | 79.0 | 12.7 | 32 | 26 | 62.4 | 16.0 | **40** | 141 / 187 | **52** |
| uiRenderFPS=30 | 77.7 | 12.9 | 38 | 47 | | | | | |
| lightFPS=10 + uiRenderFPS=30 + MaxGCPauseMillis=25 | **81.4** | **12.3** | **30** | **23** | **68.5** | **14.6** | **40** | **80 / 126** | **49** |
| re-bake budgets (lightingRebakeMs=1000 rebakeBudget=2 …) | 69.6 | 14.4 | 34 | 31 | | | | | |
| drive: bakeBudget=4 + chunkHandoffDivisor=16 | | | | | 54.5 | 18.3 | 66 | 136 / 189 | 94 |
| drive: treeBakeMaxChunksPerSec=12 | | | | | 54.6 | 18.3 | 45 | 128 / 186 | 77 |

The lighting rate is the tail lever on the drive (p99 57–67 → 40 ms, slow frames 118 → 52) at no
cost in mean. G1's pause target is a goal, not a bound: young pauses stayed 68–87 ms with
`MaxGCPauseMillis=25`; they are the p99.9 now.

### Result and the profile

Against stock on the same box, same GC and scheduler: **120 km/h drive 43.9 → 68.5 fps (mean 22.8 →
14.6 ms, p99 80 → 40), walking 49 → 81 fps (20.5 → 12.3 ms, p99 69 → 30), world load 70 → 25 s.**
60 fps on average on both routes at max zoom; not locked — 1.5 % (drive) / 0.8 % (walk) of the
frames are still over 33 ms, and the p99 is 2x the 16.7 ms budget. A lock on this CPU needs less
work on the other threads (lighting, recalc, GC pauses) or bakes and Lua UI off the game thread,
none of which is a key.

The Options > Optimizations tab has a third button, **"Low-end hardware (4 cores or less)"**
(`PROFILES` in `pzopt_optimizations_options.lua`): master on, `workers=1`, `loadWorkers=2`,
`treeBakeMaxChunksPerSec=24`, every other pzopt key back to default, and on the Display page
lighting updates 10/s and UI redraw 30/s (`GameOptions:get("lightingFPS" / "UIRenderFPS")`).
Apply / Accept saves them as usual; next launch. `workers` also now defaults to 1 on 4 cores or
fewer (`Config.defaultWorkers`). The G1 launcher JSON is still a manual step
(`config/launcher/ProjectZomboid64.g1.json`; the stock JSON's ZGC is the 2x run-to-run noise on
this box).

### 14:45–15:00: Oracle GraalVM 25.3.4.1 on the Dell (same profile set, G1, cosmos)

`jre64` pointed at a copy of GraalVM 25.3.4.1 (`jre64_graal`, Zulu kept as `jre64_zulu`), the
profile set of the rows above (`treeBakeMaxChunksPerSec=24 lightFPS=10 uiRenderFPS=30
MaxGCPauseMillis=25`), runs `dell-graal-*`:

| route | Zulu 25.0.1 (HotSpot C2) | GraalVM 25.3.4.1 | load |
|---|---|---|---|
| walk (×2 Graal) | 82.4 fps · 12.1 ms · p99 32 | 51.2 / 64.7 · 19.5 / 15.5 · p99 54 / 48 | 25 → 73 s |
| drive 120 | 69.0 · 14.5 · p99 40 | 39.8 · 25.1 · p99 62 | 23 → 68 s |
| drive 60 | 93.0 · 10.7 · p99 28 | 68.0 · 14.7 · p99 35 | 28 → 66 s |

25–40 % slower than HotSpot C2 on every route with the game thread busier (87–89 % of a core vs
84), i.e. more CPU per frame, and the world load three times longer (the boot-time Lua
precompile, script parsing and cache reads all run on cold Graal-compiled code). The desktop and
laptop verdicts (2026-09-20: ~15 % behind C2) hold and are worse on four cores. Not adopted;
`jre64` is Zulu again, the Graal copy stays at `jre64_graal` on the Dell.

### 15:20–15:28: night + thunderstorm + heavy fog at 120 km/h on the Dell, stock vs the low-end profile (with the fog pass)

Branch `low-end-profile` with master merged (fog pass `b67c94f`, `fogPass=true` default).
`--preset storm-fog --mode drive --flag route=E:1200 --flag kmh=193 --flag time_of_day=1`, both
runs verified in `pzopt-bench.out`: `night_strength=1.0 weather=storm precipitation=1.0 fog=1.0
fog_quality=0` (ImprovedFog, so the pass applies: fog buffer 480x270), routes complete. G1 on both;
stock = `--prop enabled=false` with the Dell's stock Display options, optimized = the profile set
(`treeBakeMaxChunksPerSec=24 lightFPS=10 uiRenderFPS=30 MaxGCPauseMillis=25`). Runs `dell-nightstorm-*`.

| | fps | mean | p50 | p90 | p99 | p99.9 / max | >33 ms | CPU | GPU | game thread | render thread | load |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| stock | 15.4 | 64.8 | 50.9 | 110 | 202 | 826 / 2153 | 627 | 100 % | 53 % | 70 % | 41 % | 94 s |
| **profile + fog pass** | **31.4** | **31.8** | **27.1** | **45.8** | **101** | **274 / 1369** | **361** | 100 % | 62 % | 80 % | 24 % | 35 s |

2.0x: 15.4 → 31.4 fps, p99 202 → 101 ms; the render thread drops from 41 % to 24 % of a core
(the fog pass's one draw call and the rain tiles / puddle cache replacing ~100k per-frame quads),
which on four cores is CPU the game thread gets back (70 → 80 %). Still far from 60 on this
scene: the box is at 100 % with the streamer at 46 % (120 km/h) and the lighting thread at
32–40 %, and the storm's lightning re-bakes land in the tail (max 1.4 s).

## 2026-09-21 (15:26–15:35): side-by-side video, stock vs optimized, 120 km/h thunderstorm drive (storm parity pass)

`harness/stitch-storm2-sbs.sh` -> `docs/media/drive-120kmh-storm-stock-vs-optimized-2026-09-21.mp4`
(3840x810, 42 s, AV1 10-bit PQ / BT.2020, both panes aligned at the car's motion onset from
`harness/showcase-times.py`, each run's in-game overlay inset at full resolution). Direct launcher,
`--flag weather=storm`, `--no-mangohud --no-dashboard`, `--prop overlay=true overlayFont=Large`,
master 5645dc1 merged (fog pass included). Findings: `docs/findings-storm-parity-2026-09-21.md`.

| run | build | cap | fps mean | p50 / p99 / p99.9 / max ms | >33 ms | GPU / game / render |
|---|---|---|---|---|---|---|
| `sbs2-storm120-stock-1` | every pzopt render key off (the `sbs-storm120-stock` set + fogPass, treeBakePass, treeAppend, puddleVbo, puddleEarlyZ, rainSplashesFast off) | 300 (framecap.ini; moot at 70 fps) | 70.2 | 13.3 / 67 / 84 / 92 | 46 | 85 % / 85 % / 83 % |
| `sbs2-storm120-opt-1` | defaults (2026-09-21 storm pass: puddleVbo, puddleEarlyZ, rainSplashesFast, treeAppend on top of the 09-20 storm work and the fog pass) | none | 392.1 | 2.0 / 9.9 / 14.4 / 21.3 | 0 | 98 % / 68 % / 45 % |

Same route, same build for both panes (props only). The 2026-09-20 pair was 71.5 vs 268.7 fps.

## 2026-09-21 (15:45–16:15): macOS — MacBook Pro M1 Pro, 120 km/h drive, stock vs optimized

First run of the overrides on the macOS Steam depot. Machine: MacBook Pro, Apple M1 Pro (8 CPU /
14 GPU cores), 16 GB, macOS 27.0, Apple's OpenGL over Metal (`OpenGL version: 2.1 Metal - 91.7`),
game 42.20.4 `b0bbce05d5` (same jar as the desktop), 1920x1200 fullscreen at 120 Hz, vsync off,
`frameRate=240`, `uiRenderOffscreen=true` (the Mac's own option), zoom 2.5, bundled Zulu 25.0.1
JRE with the Info.plist options (ZGC, `-Xmx3072m`). Route: `--mode drive --flag route=E:1200
--flag kmh=193 --flag zoom=max --prop instrument=true --option frameRate=240` through
`harness/run-mac.sh` over ssh (direct launch, `-Dzomboid.steam=0`, no MangoHud; frame times from
`pzopt-frames.out`, machine CPU/GPU from `top` + `ioreg`). Stock = the same install with
`--prop enabled=false`. Two runs per side, one at a time; every route completed (1,200 tiles in
38.6–39.1 s, ~122 km/h), no crash.

| Run | fps mean | frame mean / p99 / p99.9 / max (ms) | >33 / >50 ms | queue wait mean / p50 (ms) | CPU (8 cores) | GPU | game / render thread |
|---|---|---|---|---|---|---|---|
| mac-drive120-stock-1 | 143.2 | 7.0 / 17.0 / 35.3 / 74.6 | 7 / 3 | 155.5 / 157.8 | 56 % | 72 % | 78 / 76 % |
| mac-drive120-stock-2 | 146.5 | 6.8 / 16.0 / 29.1 / 235.7 | 3 / 2 | 156.1 / 154.9 | 57 % | 72 % | 80 / 77 % |
| mac-drive120-opt-1 | **213.0** | 4.7 / 11.7 / 23.2 / 47.9 | 1 / 0 | 48.0 / 8.9 | 54 % | 77 % | 87 / 64 % |
| mac-drive120-opt-2 | **193.2** | 5.2 / 15.7 / 26.1 / 38.9 | 2 / 0 | 40.8 / 8.8 | 57 % | 76 % | 85 / 66 % |

Means: 145 → 203 fps (1.4x), frame mean 6.9 → 5.0 ms, p99 16.5 → 13.7, p99.9 32.2 → 24.7, chunk
queue wait median 156 → 9 ms. The optimized side spreads 10 % between its two runs (the stock
side 2 %), so p99 differences under ~4 ms are inside the noise here. Notes:

- The loose classes load under the stock launcher too: `JavaAppLauncher` builds
  `-Djava.class.path=<Contents/Java>/` and appends the jars after it (33 `[pzopt] loaded override
  ... active` lines at the main menu from `open "Project Zomboid.app"`), so `install.sh` on macOS
  is the Linux install pointed at `Contents/Java`, no launcher file to edit. `install.sh` was
  adapted (Steam library path, `.app` layout, `shasum`, bash 3.2's empty-array trap under `set -u`,
  a python-less release lookup) and tested with and without python3 on the machine.
- `persistentVbo` falls back (no `ARB_buffer_storage` in Apple's GL, the guard in
  `GLVertexBufferObject.pzoptUsePersistent`); the settings line still prints `persistentVbo=true`.
- Chunk latency p90/p99 is *worse* optimized (585 / 1649 ms vs 419 / 1729) while the median wins
  18x: the same 4-worker recalc pool pattern the Dell showed (`workers=4` on 8 cores; the game
  thread is at 86 %). `--prop workers=2` is the A/B to run next; not done.
- Neither side is saturated at 203 fps: game thread 86 % of a core, GPU 77 %, machine 56 %. The
  remaining tail (p99.9 25 ms, 39–48 ms maxima) is unattributed on this machine; no JFR run yet.
- One stock run had a single 236 ms frame (stock-2 max); nothing like it on the optimized side.
- The Steam URL handler (`open steam://rungameid/108600`) from an ssh session does nothing
  visible; launching the `.app` with `open` or the JRE directly both work from ssh.

## 2026-09-21 (16:38–16:58): "Let Me Drive! – Anti-Stutter Car Controls" (Workshop 3805307651) on the 120 km/h drive, four weathers

Mod id `LetMeDrive` 0.2.3 by "under" (fetched with `steamcmd +login anonymous +workshop_download_item 108600
3805307651`, copied to `~/Zomboid/mods/LetMeDrive`, enabled with `--mod LetMeDrive`, its "Debug tools" tick box
pre-set in `~/Zomboid/Lua/ModOptions.ini` so its report lines land in console.txt). Lua only: on load it replaces
`Events.X.Add` for OnTick / OnRenderTick / OnPlayerMove / OnPlayerUpdate / OnZombieUpdate / OnZombieCreate /
OnLoadedMapZones / LoadGridsquare / LoadChunk, so any mod registered after it goes through its gate: LoadGridsquare
handlers are queued per square and drained under a per-frame budget (`minFps` 60, `maxBudgetMs` 4), the per-frame
events are timed and, above `shedKmh`, "heavy" ones run every other tick. Controls off by default (zoom cap, speed
limit, shedder, its FPS limiter); on by default: the gate and a `collectgarbage("collect")` block. Nothing for the
Java side of chunk streaming, which its page says outright.

Runs `lmd-<seg>-<side>` (helper `/tmp/lmd-run.sh`): the `pzo-*-stock` set-up (our build with every runtime and
boot key off, harness route driver present, uncapped, in-game overlay log + MangoHud, `--no-dashboard`,
`uiRenderOffscreen=true` from options.ini), **direct launcher on every run (no Steam)**. `stock` = that set,
`mod` = that set + `--mod LetMeDrive`, `opt` = build defaults (storm parity pass included). E:1200 at 122 km/h
(`kmh=193`, 60 s), one run per cell; every route completed in 38.6–39.3 s at zoom 2.5 (the first `storm-opt`
attempt clipped a roadside object at 1179 tiles — the known high-fps steering wander — and was re-run).
Weathers: `weather=save` (clear), `fog=heavy` (1.0), `weather=storm` (lightning every 6 s), both.

| run | fps | mean / p99 / p99.9 / max ms | >33 ms | stdev / jitter | 1 % low | < 240 fps | CPU | GPU busy | game thread | render thread |
|---|---|---|---|---|---|---|---|---|---|---|
| `lmd-clear-stock` | 160.4 | 6.2 / 15.5 / 19.1 / 28.0 | 0 | 4.6 / 1.1 | 65 | 45 % | 31 % | 88 % | 71 % | 80 % |
| `lmd-clear-mod` | 153.6 | 6.5 / 15.9 / 23.0 / 40.5 | 1 | 4.7 / 1.3 | 63 | 48 % | 31 % | 86 % | 72 % | 79 % |
| `lmd-clear-opt` | **505.8** | 2.0 / 7.4 / 10.2 / 19.8 | 0 | 1.4 / 0.9 | 134 | 6 % | 29 % | 97 % | 59 % | 47 % |
| `lmd-fog-stock` | 114.3 | 8.8 / 17.7 / 24.1 / 38.3 | 1 | 4.9 / 1.7 | 57 | 70 % | 29 % | 92 % | 72 % | 77 % |
| `lmd-fog-mod` | 110.7 | 9.0 / 18.2 / 27.4 / 43.6 | 3 | 4.9 / 1.8 | 55 | 72 % | 29 % | 90 % | 75 % | 74 % |
| `lmd-fog-opt` | **400.4** | 2.5 / 7.9 / 11.2 / 18.8 | 0 | 1.4 / 1.1 | 127 | 9 % | 27 % | 98 % | 57 % | 46 % |
| `lmd-storm-stock` | 73.6 | 13.6 / 60.6 / 83.1 / 95.5 | 45 | 7.4 / 3.1 | 17 | 100 % | 30 % | 83 % | 89 % | 78 % |
| `lmd-storm-mod` | 68.9 | 14.5 / 60.2 / 87.4 / 99.3 | 48 | 7.8 / 3.0 | 17 | 100 % | 30 % | 83 % | 92 % | 79 % |
| `lmd-storm-opt` | **411.4** | 2.4 / 8.6 / 13.3 / 19.5 | 0 | 1.6 / 1.1 | 117 | 9 % | 28 % | 97 % | 75 % | 48 % |
| `lmd-stormfog-stock` | 66.2 | 15.1 / 66.0 / 86.1 / 101.0 | 44 | 7.8 / 3.5 | 15 | 100 % | 30 % | 81 % | 89 % | 77 % |
| `lmd-stormfog-mod` | 63.1 | 15.8 / 63.7 / 95.0 / 106.2 | 52 | 8.4 / 3.7 | 16 | 100 % | 29 % | 81 % | 93 % | 77 % |
| `lmd-stormfog-opt` | **357.0** | 2.8 / 9.2 / 14.0 / 22.5 | 0 | 1.7 / 1.2 | 109 | 12 % | 27 % | 98 % | 67 % | 46 % |

- The mod is 4–5 % slower than stock in every weather (160 → 154, 114 → 111, 74 → 69, 66 → 63 fps), p99 within
  noise, p99.9 / max a few ms worse, and never better on any tail metric. Its console reports explain why: on the
  harness mod list it `captured 0 LoadGridsquare handlers` — nothing to defer — yet its gate still takes every
  square through its own queue (~5,000 squares/s drained on this route, 455 dropped in the storm) and times every
  per-frame event; `gc calls blocked 0`, `shed 0` in every run. Its own "stalls ≥ 33 ms" line counts the same
  storm stalls stock has (12–26 per 10 s window with the mod, all lightning re-bakes on the Java side).
- The stock game is game-thread-bound in the storms (89 % of a core, 100 % of frames under 240 fps, 1 % low 15–17
  fps); fog on stock costs 29 % (the ~190 screen-wide fog rectangles per level, `docs/findings-fog-2026-09-21.md`).
- Our build, same runs, same direct launcher: 3.2x clear, 3.5x fog, 5.6x storm, 5.4x storm + fog, zero frames
  over 33 ms in all four; GPU at 97–98 % (the wall since the 400 fps pass), storm re-bakes spread by
  `lightingRebakeBudget`. The mod's "fps" figure in its report is Lua ticks per 10 s window (~half the presented
  frames on this uncapped route), not the frame rate.

## 2026-09-21 (21:30–22:20): multiplayer — 120 km/h drive as a client of a stock dedicated server

Does the client-side work hold up in multiplayer? The client (this desktop, overrides installed) joins a
stock dedicated server running on the same PC (`harness/mp/server.sh`: a hardlinked copy of the game dir
with every pzopt file removed, its own `-cachedir`, `DoLuaChecksum=true`, `SpeedLimit=150` — the server
option caps every vehicle at 70 km/h by default — anti-cheat off, spawn point on the route start). The
server has its own fresh world: the single-player bench save's `map_meta.bin` does not load on a server
(invalid room metaIDs, then a `BufferUnderflow`), so the scene is the same map with a different
population and the world's own wrecks; route `E:800` because a wreck blocks the road at ~8820.
`harness/mp/run.sh <label> [stock]`: `pzopt.Harness` in its multiplayer path (teleport to `start=`,
`/addvehicle` as admin, seat, turn the physics body once the client has authority, corridor sweep of
leftover vehicles at start and end), `--no-mangohud`, in-game overlay log. Stock = the per-key stock
property list (the Let Me Drive! runs' set plus the storm-parity keys off); the two stock runs hit
nothing, `opt-11` hit two zombies (54 and 42 km/h dips), `opt-12` ended on the previous run's car at the
route end (the corridor sweep was added after it; `opt-14` ran on the swept road and matches). Same 27 s window from the route start for every run
(`harness/mp/window.py`, the stock runs' whole route); the single-player `pzo-drive120-*` runs of the
morning over the same window for reference.

| run | fps | mean / p50 / p99 / p99.9 / max ms | < 240 fps | CPU | GPU busy | game thread | render thread |
|---|---|---|---|---|---|---|---|
| `mp-drive120-stock-1` | 208.0 | 4.8 / 2.8 / 18.6 / 21.5 / 25.2 | 32 % | 22 % | 89 % | 69 % | 79 % |
| `mp-drive120-stock-2` | 208.4 | 4.8 / 2.8 / 18.4 / 20.9 / 22.6 | 32 % | 22 % | 88 % | 68 % | 79 % |
| `mp-drive120-opt-11` | **523.1** | 1.9 / 1.2 / 13.7 / 20.7 / 26.7 | 6 % | 23 % | 96 % | 57 % | 29 % |
| `mp-drive120-opt-12` | **501.1** | 2.0 / 1.2 / 13.9 / 19.7 / 23.7 | 7 % | 12 % | 98 % | 37 % | 28 % |
| `mp-drive120-opt-14` (after the corridor sweep: clean route, no collision) | **502.8** | 2.0 / 1.2 / 14.1 / 19.3 / 26.7 | 7 % | | 98 % | | |
| `pzo-drive120-stock` (single player) | 205.4 | 4.9 / 3.0 / 15.1 / 19.4 / 49.7 | 36 % | | | | |
| `pzo-drive120-opt` (single player) | 548.7 | 1.8 / 1.4 / 7.7 / 14.1 / 25.4 | 5 % | | | | |

Multiplayer keeps the gain: 208 → 501–523 fps mean (2.4–2.5x, the single-player ratio is 2.7x), mean
frame 4.8 → 2.0 ms, and the GPU is the wall again (96–98 % busy) with the render thread at 29 % of a
core instead of 79 %. The tail is worse than in single player on both sides (stock p99 18.5 vs 15.1 ms,
optimized 13.8 vs 7.7): the client applies the server's vehicle and zombie updates on the game thread
and the fresh world has a real population where the bench save is quiet, so a good part of the
optimized tail here is zombies streaming in beside the road, not the render path. The 1200-tile route
and the bench-save scene are not reproducible on a server; treat these as "the gain survives
multiplayer", not as a number to compare to the single-player rows.

## Camera zoom changes (2026-09-22, keys `zoomRetain` / `zoomRebakeBudget` / `zoomFrameMs` / `zoomEaseMs` / `zoomEase`)

Rig: `--mode bench --flag route=S:450 --flag zoom=0.25 --flag zoom_cycle=S [zoom_span=N] [zoom_jump=true]`
(one wheel notch (span N notches) every S seconds, in to the closest level then back out; `zoom_jump`
sets the zoom at once, the fast-wheel-spin worst case with no ease), `harness/zoomsteps.py <run>
--window 1.0` (frame times in the second after each step vs the rest of the route), the per-step bake
trace and `retain:` counters in the console, `attribute.py --after-mark zoom-:1` / `sections.py
--after-mark zoom-2.5:1` for the change frame. Desktop, 5120x2160, south route (18 tiles/s teleport
route, so the route itself loads chunks: its own spikes are 26-45 ms).

What stock does: a level's textures are freed the frame it leaves the screen; zooming in frees most of
what was visible, zooming back out bakes everything that reappears in the frame it appears, and the bake
budget never caught those (`DIRTY_CREATE` is set after the deferral decision). The zoom itself moves
0.03 per frame and snaps (8 frames per notch whatever the frame rate).

| case (worst frame in the step windows, ms) | stock (240 cap) | ours before | `zoomRetain` + plan | + `zoomEase` |
|---|---|---|---|---|
| 0.25 → 2.5 at once, four jumps (`zs-out-jump9`, `zo-jump9-off-u`, `zo-jump9-plan5u`) | 375 / 101 / 77 / 83 | 429 / 112 / 133 / 99 | 25 / 27 / 18 / 14 | (no ease on a jump) |
| one notch out per 1.5 s, at wide zoom 1.75-2.5 (`zs-out-wheel`, `zo-wheel-plan5u`, `zo-wheel-ease`) | 48 / 51 / 51 / 46 | 21-39 | 22 / 15 / 17 / 28 | 25 (median step 11.5) |
| 0.25 ↔ 2.5 eased spins every 3 s (`zs-out-wheel9`, `zo-wheel9-ease`) | 28 / 20 / 26 / 13 | | | 9 / 18 / 20 / 12 |
| route without zoom steps, max (`zs-ctl`, `zo-ctl-plan5u`) | 45 | | 41 | |

The step windows are now inside the route's own spike range (26-45 ms from chunk rows at max zoom),
the change frame itself is 15-30 ms on an instant 2.25-level jump (its remaining cost is the on-screen
scan of ~200 new chunk levels: cutaway walls, occlusion, light info; the bakes are spread 4-12 a frame
nearest-first over the next 20-40 frames, the kept textures on screen meanwhile), and a wheel notch or an
eased spin no longer shows in the frame times at all. The 240-cap frame rate during the fill is ~120-200
fps for 0.1-0.2 s instead of one 80-375 ms freeze; with `zoomEaseMs=300` the motion itself is a 300 ms
Bézier (CSS "ease") at every frame rate instead of 8 linear frames and a snap.
Video: `docs/media/bench-zoom-spin-stock-vs-new-1080.mp4` (`harness/stitch-zoom-sbs.sh`, runs `zoom-rec-stock` /
`zoom-rec-new`: the stock zoom paths on our build vs the new ones, side by side, aligned at the first zoom-out).

## 2026-09-22 (00:53–01:12, 12:42–12:55): "Ultimate ZBetterFPS" (user-submitted fork) on the Louisville horde, before and after the day's game-thread passes

A mod user sent the maintainer "Ultimate ZBetterFPS" (`~/Downloads/mods/ZBBetterFPS`, jar sha256
`0b53bfb3…663cb13`): a fork of zed_0xff's ZBetterFPS (13 of 14 upstream classes unchanged) plus ~100
"ZomPerfBuddy" classes; loaded through ZombieBuddy (`--mod ZombieBuddy --mod ZBBetterFPS --vmarg
-javaagent:…/ZombieBuddy.jar=policy=allow-all,frontend=console`, copy under `~/Zomboid/mods/ZBBetterFPS`,
the Workshop 3622986450 copy moved aside because `searchForModInfo` lets the last scanned copy win).
Audited before the runs: no network, exec, native loading or file writes; two raw `ClassFileTransformer`s
(`StreamCoreTransformer`, `ZombieCullCapTransformer`). Its defaults: **max 3D zombies 192** (vanilla 510,
rewritten in place in `IsoWorld.sceneCullZombies`, "510 -> 192 (2 sites)"), **12 blended zombie
animations** (vanilla 20), the six graphics patches off (IndieGL state cache, sprite batching, ring buffer,
uniform cache, chunk multi-texture, 3D models), `optimizeIsoMovingObject` off (NoSuchFieldError on 42.20),
zombie sort caching / high-rise dirty fast / atlas job pacer / the ZomPerf stream, neighbour and vehicle-index
patches on. It transforms ~30 game classes at load, 13 of them ours (IsoWorld, FBORenderCell/Cutaways/Levels,
IsoChunk, IsoChunkMap, WorldStreamer, MovingObjectUpdateScheduler, IsoGameCharacter, TextureDraw,
MultiTextureFBO2, RenderThread, GameWindow); its stream-wake patch refuses our WorldStreamer ("unsupported
bytecode shape"). Mod options live in `~/Zomboid/Lua/ModOptions.ini` as `tickbox|ZBBetterFPS|<key>|…` — the
zombie combos under a **second id, `combobox|ZBBetterFPS_Zombies|max3DZombies|1`** (index 1 = vanilla); a
line under `ZBBetterFPS` is silently ignored (run `zbu2-lou-both-vanillaq` was a wasted repeat of the defaults).

`--preset louisville`, uncapped, no dashboard, in-game overlay, 5120x2160. The 01:00 runs are on the 00:5x
build, the 12:4x runs on the 12:24 build (zombie simulation on all cores, characters-draw pass, player LOS,
zoom retain, strong re-bake budget).

| run | build | mod | fps mean | p50 / p99 / p99.9 / max ms | >33 ms | jitter | GPU (sysmon / GL timer) | game thread |
|---|---|---|---|---|---|---|---|---|
| `zbu-lou-stock` 00:53 | 72 keys off (stock behaviour) | – | 22.3 | 42.9 / 75.9 / 111.4 / 125.4 | 477 of 558 | 17.6 | 40 % / 43 % | 98 % |
| `zbu-lou-mod2` 00:58 | 72 keys off | defaults | 32.4 | 29.5 / 61.8 / 89.3 / 95.5 | 256 of 811 | 13.8 | 38 % / 44 % | 97 % |
| `zbu-lou-both2` 01:00 | 00:5x defaults | defaults + six graphics boxes on | 11.6 | 86.9 / 134.5 / 152.4 / 156.2 | 295 of 295 | 20.0 | 99 % / 91 % | 73 % (26 % waiting on the GPU) |
| `zbu-lou-both-nogfx` 01:02 | 00:5x defaults | defaults | 42.2 | 22.5 / 45.3 / 71.6 / 109.4 | 85 of 1055 | 9.3 | 33 % / 43 % | 98 % |
| `zbu2-lou-ours` 12:42 | 12:24 defaults | – | 55.9 | 16.4 / 35.5 / 95.5 / 138.4 | 26 of 1401 | 4.3 | 58 % / 84 % | 95 % |
| `zbu2-lou-both` 12:44 | 12:24 defaults | defaults (192 / 12) | 81.7 | 11.0 / 29.3 / 61.9 / 100.0 | 12 of 2042 | 2.5 | 62 % / 87 % | 90 % (9 % waiting) |
| `zbu2-lou-both-vanillaq` 12:47 | 12:24 defaults | defaults again (wrong ini id) | 84.2 | 10.8 / 27.4 / 55.8 / 112.8 | 9 of 2106 | 2.2 | 61 % / 88 % | 89 % (9 % waiting) |
| `zbu2-lou-both-vq2` 12:50 | 12:24 defaults | vanilla caps 510 / 20 | 26.3 | 28.6 / 173.8 / 258.8 / 277.2 | 240 of 692 | 26.8 | 65 % / 88 % | 73 % (32 % waiting) |
| `zbu2-lou-both-vq2-rec` 12:53 | 12:24 defaults | vanilla caps 510 / 20, `--record --shot-at 12` | 59.6 | 15.0 / 34.6 / 185.1 / 425.4 | 22 of 1851 | 4.2 | 61 % / 83 % | 92 % (5 % waiting) |

- **The mod's gain on top of our build is its quality caps.** With its defaults it is +46 % (55.9 → 81.7 fps,
  p99 35.5 → 29.3 ms, jitter 4.3 → 2.5 ms) because 192 instead of 510 zombies are skinned 3D models (the rest
  are atlas sprites: game thread −4.3 ms a frame, of which zombies −1.8 and postupdate −1.4, and GPU 12.7 →
  8.7 ms a frame in the settle phase) and 12 instead of 20 blend animations. With the caps at vanilla the
  same mod is 59.6 fps against 55.9 alone (recorded, so slightly loaded): its remaining patches are within
  noise of our build. Stock + mod (32.4) vs our build alone (55.9): the build is 1.7x the mod, 2.5x stock.
- **`zbu2-lou-both-vq2` collapsed into the strong-re-bake flood** (the `see_all` regime of the Louisville
  notes): it settled at 45-58 fps / 14-15 ms GPU, took a 345 ms frame at the route start and then sat at
  16-22 fps with 50-59 ms of GPU per frame; `bake_counters` = 24,914 bakes / 386,822 deferred / 343,237
  strong marks / 299,645 re-bakes held (the healthy runs: ~8k / 22-29k / 14-16k / 13-14k), 0 creations
  deferred. The maintainer watched it and saw black chunk squares — that is the held-re-bake state (levels
  shown before their re-bake), but the run was not recorded, so it is not confirmed on video: the recorded
  repeat of the same config did not tip (59.6 fps, normal counters) and a 2 fps scan of its route window
  (black 16 px tiles per frame, overlay panels masked: min / median / max 369 / 695 / 1916 vs 372 / 640 / 1799
  on the stock recording, no step beyond the stock recording's own roof-edge steps) shows no square. The large
  black blocks in every Louisville capture (`shot-game.png` at t=12 s vs `tri-lou-stock` at the same second)
  are the tall buildings' roofs at this zoom in stock too.
- **Three further recorded attempts did not reproduce it** (13:11–13:31, `zbu2-lou-flood-a|b|forced`, same
  config): 41.4 fps with the re-bake hold half-way up (20k bakes / 99k deferred / 111k strong marks / 66k held),
  46.3 fps with healthy counters, and 56.4 fps with `lightingStrongBudget=1` forced — which does *not*
  reproduce the regime (held re-bakes 17k against the flood's 300k): the flood comes from the grid-wide strong
  marking (343k marks), not from the budget. All three recordings scanned at 10 fps with the overlay panels
  masked (`/tmp/zbu2-flood-scan.py`, fully-black 16 px tiles per frame against the ±2 s local median): every
  outlier is 40-80 tiles and drifts smoothly over a second of camera spin, i.e. roofs turning into frame — a
  held level would step by hundreds of tiles in one frame. So the black squares the maintainer saw at 26 fps
  remain unconfirmed on video; the tipped regime is roughly a 1-in-3 event per run and did not recur in three.
- The six graphics patches stay a no-go with our renderer (11.6 fps, GPU 99 %).
- Worth a key of our own: the 3D-zombie cap and the blend count as an Optimizations-tab quality knob
  (stock 510 / 20 by default) would give the same +50 % on hordes without the mod; our `IsoWorld`
  override already owns `sceneCullZombies`.

## 2026-09-22 (11:40–13:20): the Dell again — a broken window, a machine-state halving, and the upscaler on a low-end box

Goal: fresh numbers from the Dell (i5-6300HQ / GTX 960M, 1920x1080, G1, cosmos, Steam down) on the
day's build, and a second Options-tab profile that adds the upscaler to the low-end set. Three
findings, in the order they appeared.

### 1. Every run presented a quarter of the screen (fixed)

From 11:43 on, the Dell drew the frame's bottom-left 640x480 at the top-left of the screen and black
elsewhere — with the stock shim too (`--install stock`-equivalent probes `lo2-probe-stockshim*`). The
LWJGL2 shim creates its GLFW window at 640x480 and lets `Core.setDisplayModeInternal` resize it to
fullscreen afterwards; under NVIDIA PRIME render offload on XWayland the GL drawable kept the creation
size. On 09-21 KWin had grown the window to 1920x1022 before the switch ("Display mode changed to
1920x1022" in every 09-21 Dell console), which masked it. Fixed in the `Display` override (create the
window at `Core.width x Core.height` / fullscreen, wait for the WM's resize before re-binding the
context; `docs/override-edits.md`, "Fourth edit in `Display`"). Verified with the game's own capture
(`--shot-at`, `dell-lo2-fix-probe`: the whole 1920x1080 frame) and on the desktop's windowed
5120x2160 runs; a peer's desktop A/B of the same change is a wash (484.3 → 483.6 fps).
**Numbers from the 11:43–12:12 Dell runs are void** (a quarter of the pixels = ~2x the fps).

### 2. The Dell itself is ~2x slower today than on 09-21, in every configuration

| walk route (S:120 speed=3 turn=90, max zoom) | fps | mean | p99 | load |
|---|---|---|---|---|
| 09-21 stock | 51.9 | 19.3 | 64 | 75 s |
| 09-21 low-end profile | **81.4** | 12.3 | 30 | 23 s |
| 09-22 stock (`enabled=false`) | 36.6 | 27.3 | 78 | 85 s |
| 09-22 low-end profile | 41.9 | 23.9 | 57 | 34 s |
| 09-22, **the 09-21 build rebuilt and installed** (`ctl0921`) | 44.3 | 22.5 | 64 | 31 s |

Ruled out, each with its own run: the build (the 09-21 tree gives today's number, not yesterday's),
`zoomRetain`, `treeAppend`+`puddleVbo`, `charDrawPrep`+the parallel zombie keys, `textureCompression`,
`texture2x`, fullscreen vs borderless vs windowed, Steam (down), thermals (68 °C, zero throttle
events), the PCIe link (gen3 x8) and the GPU clocks. The scene work is identical (same chunk counts,
same bake counters) and `gpu_ms` is 5–6 ms both days, but **every frame costs twice the CPU**
(141 s of process CPU / 3,298 frames on 09-21 vs 146 s / 1,792 today) while the machine sits at 99 %
CPU on both days, and the game thread gets *less* of it (game_load 54 % → 36–42 %). The extra time is
in the process's non-Java threads (live Java threads 71 s of 141 s on 09-21, 62 s of 146 s today),
i.e. the GL driver / present path, not our code. Boot-to-menu is unchanged (20.0 s vs 18.6 s), so the
CPU and disk are not slower.

The one machine-state difference found: today's boot logs `nvidia 0000:02:00.0: [drm] No compatible
format found` (boot of 11:37; also present in the 09-21 08:38 boot, absent from the 09-21 09:31 boot
that produced every good 09-21 number). Next step when the Dell is free: **reboot it and re-run
`ctl0921-walk-prof`** — if it returns to ~80 fps, the day's absolute numbers below are a floor and the
low-end section of `README.md` / `docs/media/dell-lowend-comparison.png` keeps its 09-21 figures.
Until then the 09-22 Dell numbers are only comparable **to each other**.

### 3. FSR 1.0 on a low-end box: free on clear scenes, a third off the tail in heavy weather

Same machine state, same session, profile = `workers=1 loadWorkers=2 treeBakeMaxChunksPerSec=24
lightFPS=10 uiRenderFPS=30 -XX:MaxGCPauseMillis=25`:

| route | keys | fps | mean | p99 | p99.9 / max | >33 ms | GPU |
|---|---|---|---|---|---|---|---|
| walk | profile | 41.9 | 23.9 | 57 | 168 / 453 | 197 | 47 % |
| walk | + fsr1 quality (67 %) | 42.0 | 23.8 | 59 | 192 / 280 | 184 | 49 % |
| walk | + fsr1 performance (50 %) | 41.4 | 24.1 | 62 | 211 / 270 | 215 | 46 % |
| drive 120 | profile | 30.8 | 32.5 | 90 | 160 / 210 | 440 | 41 % |
| drive 120 | + fsr1 quality | 31.2 | 32.1 | 92 | 172 / 262 | 429 | 40 % |
| drive 120 | + fsr1 performance | 29.6 | 33.8 | 97 | 196 / 279 | 452 | 39 % |
| **storm + fog, night, 120 km/h** | profile | 25.9 | 38.6 | **145** | 230 / 271 | 450 | 48 % |
| storm + fog | + fsr1 quality | 26.6 | 37.6 | **111** | 175 / 204 | 554 | 42 % |
| storm + fog | + fsr1 performance | 27.2 | 36.7 | **92** | 196 / 215 | 534 | 40 % |

On the clear routes this box is CPU-bound (GPU 40–49 %), so the upscaler is free but idle — within
noise on mean and tail. In the night thunderstorm with heavy fog, where the GPU does carry weight,
FSR 1.0 takes the p99 from 145 ms to 111 (Quality) / 92 ms (Performance) and the GPU load from 48 %
to 40 % at no cost in mean. Hence the new profile button uses **Quality (67 %)**: no visible cost in
normal play, the tail relief when the weather turns. A GPU-bound low-end machine (the Ayaneo Flip's
iGPU, 4K) should gain the pixel ratio outright, as the desktop numbers show.

### The second Options-tab profile

Options > Optimizations now has a fourth button, **"Low-end hardware + FSR 1.0 upscaling"**: the
low-end set plus `upscaler=fsr1`, `upscalerQuality=quality`. The plain "Low-end hardware (4 cores or
less)" button is unchanged.

The button was verified in game on the desktop (`preset-check6`): clicking it and pressing Accept
leaves `~/Zomboid/pzopt/options.ini` as exactly `workers=1 loadWorkers=2 treeBakeMaxChunksPerSec=24
upscaler=fsr1 upscalerQuality=quality` (every other saved key cleared) and the stock Display page at
`lightFPS=10 uiRenderFPS=30`. Rig note: `xdotool click 1` on a tab button only hovers it (the tooltip
appears, the handler never runs); `mousedown 1; sleep 0.15; mouseup 1` fires it.

## Zombie postupdate pass (2026-09-23 night, runs `zt4-*`, worktree branch `zombie-lt4`)

Goal: `MovingObjectUpdateScheduler.postupdate`, the biggest update sub-phase of the game thread on the Louisville horde
(18 % after the zombie game-thread pass), under 4 %, with no visual change and a clean `console.txt`; secondary: use the
cores. Desktop, `--preset louisville --prop uncappedFps=true --no-dashboard`, 25 s route, measured on origin/master
874d3ec (the centerFirstLoad / resumeShot / Dell-hitching base) with this pass's keys off vs on, alternating.

| | keys off | keys on |
|---|---|---|
| `postupdate` share (4 alternating pairs) | 15.5 / 17.0 / 14.7 / 16.1 → **15.8 %** | 3.5 / 3.3 / 4.3 / 4.2 → **3.8 %** |
| all 16 clean keys-on runs (incl. the final build: 3.4 / 4.7 / 4.1) | | mean 4.07 %, 3.1-5.0 (the share moves ±0.8 run to run) |
| fps (same 4 pairs) | 90.1 | **105.4** (+17 %) |

In frame time the sub-phase went ~3.0 ms → ~0.6 ms a frame. What did it, in order of effect:

1. **The bone batch runs asynchronously** (`animBatchAsync`): started on the workers at the end of the loop, joined at
   `IsoWorld.FinishAnimation` (the game's own animation join point) or by the next batch. 18 → 10 % on its own.
   0 early joins in every run (`guardJoins=`).
2. **The animators on the workers** (`animatorParallel`): each zombie's animator, move deltas and track tick run in a
   worker task, anim events captured and dispatched on the game thread in order. A zombie whose animator fires an event
   finishes on the game thread (~15 per frame of ~800). 10 → 8 % at first: the tasks ran 2.4x slower in parallel than on
   one thread, because
3. **stock's model lock is one interned string for every model** (`modelLockPerInstance`): the only user,
   `ModelSlot.Update`, serialised every worker. Per-instance lock: animator task time 5.3 → 2.7 ms a frame.
4. **Pipelines** (`animatorPipeline`): the workers alone run the transition evaluation and then the animator batch while
   the game thread applies each zombie as soon as it is ready (and takes an unstarted task while it waits).
5. **Guarded callbacks** (`guardedCallbacks`): eight side-effecting zombie variables only have a rare side-effect branch;
   they are read on the workers and that branch falls back to the game thread. Fewer game-thread pre-reads, and the whole
   zombie anim set became safe for the workers (`statesUnsafe=0`).
6. `poolStatsBatched` (the pool statistics CAS loops) and `lazyPose` (keyframe spans found at first read) trim the rest.

Visual parity: recorded pair on the final build, keys off (`zt4-pA`) vs on (`zt4-pB`), same route, window 37-57 s:
`parity-judge.py` verdict parity 0.97; black share 74.31 / 74.22 %, luma pops 0 / 0, solid blocks 0.09 / 0.08,
transient pixels 255 / 221 per frame (fewer with the keys on); a matched frame pair at 47 s shows the same scene.

Checks: `console.txt` has no exception in any run of the final build (the boot-time ConcurrentModificationException in
`TileDepthTextureAssignmentManager.initSprites`, a race of earlyTilePacks with the world loader seen in about a third of
the loads, is fixed; the main-menu update check no longer runs, and no longer logs GitHub's 403, in harness runs);
`devActionEvalCheck` over 1,109,911 and 947,004 worker evaluations: 0 mismatches; `impure=0` and `failures=0` in every
run. More workers do not help (`frameThreads=12` = 8); spinning workers between batches did not either (`frameSpinUs`
default 0).

Findings worth keeping:

- **Measure the task time, not only the share.** Parallel code that "works" can be serialised by one shared monitor in
  the game (the model lock) or by shared CAS counters; the per-task time with 1 vs 8 workers found it in one run.
- **`headOnWorker` stays off.** Moving the head of `postUpdateAnimating` to the evaluation task was exact (0 mismatches)
  but every run with it tipped the preset into its see_all re-bake flood (3 of 3, 0 of 3 off, same build), and a flooded
  run shows an artificially low `postupdate` share. The same clustering showed up on the old base right after
  `guardedCallbacks`; on 874d3ec none of the 14 clean runs flooded. The flood is the native NaN race (see
  `louisville-see-all-nan-flood`), so this looks like a timing sensitivity of that race; not understood.

## Zombie game-thread pass (2026-09-22 afternoon, runs `zt*`)

Goal: the `zombies` sub-phase of the game-thread profile (`IsoZombie.update` + `IsoZombie.postupdate`, as
`analyze.py` prints it) under 5 % on the Louisville horde preset. Every number below is the same build measured
with the keys off and on, on the desktop, `--preset louisville --prop uncappedFps=true --no-dashboard`; runs that
tipped into the preset's strong-re-bake flood (`strongMarks` ~350 k instead of ~15 k) were discarded and repeated.

| Configuration | zombies | fps | run |
|---|---|---|---|
| all new keys off (start of the pass) | 23 % | 54.4 | `zt1-off` |
| + snapshot name filter, emitter gate, separation specialisation, sleep and state-param memos | 18 % | 59.6 | `zt1-on` |
| + second callback audit, handle-direct operand resolution | 16 % | 59.3 | `zt2-on` |
| + `separateParallel` | 12 % | 58.5 | `zt3-sep` |
| + `State` override, action-group cache, profiler thread memo, snapshot dedupe | 11 % | 58.2 | `zt13-plain` |
| + `zombieSimLodTiles=12 zombieSimLodSteps=2 zombieCheckSpread=4` (opt-in) | 6 % | 64.4 | `zt13-lod` |
| + `zombieSimLodTiles=8 zombieSimLodSteps=2 zombieCheckSpread=4` (opt-in) | **5.4 / 5.8 / 6.5 %** | 63.2 / 65.4 / 61.0 | `zt15-lod8x2`, `zt20-r1`, `zt20-r2` |

The last row is three repeats of the same configuration: the run-to-run spread of the sub-phase share on this
preset is about ±0.6 points, so the best usable setting sits at roughly 6 %, not reliably under 5 %. Going further
was tried and does not work: every configuration with a third LOD step, or with the first step closer than 8 tiles,
tipped the preset into its strong-re-bake flood (six attempts, all discarded).

In frame time the `zombies` sub-phase went 4.2 ms → 1.9 ms with no behaviour change, and → 0.85 ms with the LOD
key on. Adopted as defaults (no behaviour change): `actionSnapshotFilter`, `emitterParamSkip`, `separateFast`,
`separateParallel`, `sleepCheckMemo`, `stateParamMemo`, `actionGroupCache`, `profilerThreadMemo`.
`zombieSimLodTiles` / `zombieSimLodSteps` / `zombieCheckSpread` default to off: they change how often a distant
zombie is simulated, which is the game's own mechanism (it already steps at 30 / 60 / 80 tiles) but is still a
behaviour change.

Checks run on every step: `console.txt` had no exception in any of the runs; `devActionEvalCheck` over 1,361,996
batched contexts reported `mismatches=0 filterMisses=0` (the second number is the rig for the snapshot name
filter: it resolves every operand the filter dropped and counts the ones that would have needed a snapshot);
`harness/parity-judge.py` on recorded pairs of the same route and the same window gave parity 0.98 for
`separateParallel` alone, 0.97 for `zombieSimLodTiles=15` alone, and 0.88 for the two-step LOD with the thump
spread; the 120 km/h drive route is unchanged (257.6 fps with the keys on, 259.0 with them off).

Findings worth keeping:

- **Three LOD steps tip the preset.** Every run with `zombieSimLodSteps=3` (four attempts) landed in the
  strong-re-bake flood; two steps did not in eight attempts. Not understood; two steps is the usable setting.
- **The preset's `see_all=true` is a rendering multiplier, not a simulation one.** With `--flag see_all=false` the
  same horde runs at 102.6 fps instead of 58.2 — and the `zombies` share *rises* to 20 %, because the render phase
  it is a share of has collapsed. Percentages of the game thread move when any other phase moves; compare frame
  time as well.
- **`updateSeenVisibility` must run every frame** — see the dead end in `docs/override-edits.md`.
- With the pass in, the Louisville horde is GPU-bound again: 13-14 % of the game thread is the frame hand-off wait
  and `gpu_load` is 88 %.
