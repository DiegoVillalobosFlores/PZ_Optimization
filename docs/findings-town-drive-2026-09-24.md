# Town drive at a locked 240 fps (2026-09-24)

Goal (maintainer, 2026-09-24): a locked 240 fps on the desktop (RTX 4090, 9800X3D, 5120x2160) on the
`drive-120-south` bench (120 km/h path drive through Rosewood, 36 s route). Worktree `../PZ_Optimization-towndrive`,
branch `town-drive` from origin/master 730e789. Every run: `--bench drive-120-south --launcher direct --prop
upscaler=dlss` (the maintainer's own options have DLSS at 67 %; pinned so a menu change cannot move the numbers).

## Baseline (runs `td-base-cap`, `td-base-unc`)

| run | fps | p99 | p99.9 | max | frames > 4.17 ms | GPU | CPU |
|---|---|---|---|---|---|---|---|
| 240 cap | 214.3 | 15.7 ms | 37.4 ms | 53.0 ms | 36.9 % | 65 % | 38 % |
| uncapped | 422 | 11.2 ms | 28.2 ms | 59.5 ms | 7.2 % | 98 % | 33 % |

Below the cap with the hardware idle. Mean cost is not the problem (uncapped averages 422 fps); the tail is.

## Where the late frames come from

New rigs (all written by instrumented runs):
- `harness/frame-causes.py <run>`: per presented frame, from `pzopt-pacing.out`: game step, step -> render-thread
  acquire, render submit (acquire -> swap call), swap, GPU completion; blames each late frame on the stage that grew;
  with `asprof.jfr` folds the render / game thread wall samples inside the late frames' windows
  (`harness/jfr-windows.java`, one pass over the recording).
- `pzopt-gpusections.out` (`--prop gpuSections=true`): every GPU section pair with the render thread's issue time,
  joined to frames (the section averages alone could not show bursts).
- `pzopt-bakes.out` (`pzopt.BakeLog`): per frame the chunk-level bakes by reason, plus one row per bake (chunk,
  level, dirty flags, player position).

Findings (runs `td-prof-cap`, `td-gpusec`, `td-topup`):
1. 3/4 of the time over the cap is the render thread (submit p90 4.9 ms, p99 11.6 ms vs 1.5 ms typical); in those
   frames it waits in `glClientWaitSync` of the persistent sprite ring (38 %: the GPU is > 128 batches behind),
   `glGetIntegerv` in the DLSS resolve (12 %, a threaded-driver sync), chunk FBO creation (6 %).
2. The GPU is bursty, not busy: per-frame GPU time p50 1.9 ms, p90 5.9, p99 14.6, max 42 ms. The `bake` pass is the
   burst: 3.5 ms mean (p90 8.3) in heavy frames vs 0.34 ms in light ones; one bake is ~0.25 ms of GPU.
3. 63 % of frames bake nothing; 1-7 % bake 12-120 chunk levels. Burst frames stack every reason at once:
   - arrivals: a chunk row is crossed every ~270 ms at 120 km/h; at max zoom on this screen the loaded grid's edge
     (distance 11-12 chunks) is on screen, so 20-38 new levels bake together. Their dirt is light + object-add +
     redraw (+ cutaway), not DIRTY_CREATE (set only inside the bake), so `bakeBudget` never saw them;
   - cutaway storms: a building entering or leaving the collapse set (driving past buildings) re-bakes every level
     of its chunks above the player, 30-90 at once, never held;
   - neighbour seam redraws (`checkSeamChunks`: all 8 neighbours of every loaded chunk), lighting spread.
4. 70 % of the late frames with no bake nearby are not overruns: their steps sit exactly on the 4.17 ms grid; the
   swap time jitters by 1-2 ms (render latency varies), so a swap-return interval exceeds the cap.

## Techniques, each implemented and measured

| run | technique | fps | late | p99 | p99.9 | max | verdict |
|---|---|---|---|---|---|---|---|
| td-base-cap | - | 214.3 | 36.9 % | 15.7 | 37.4 | 53.0 | baseline |
| td-slots4 | persistent sprite ring x4 (`persistentVboSlots=4`) | 218.1 | 34.1 % | 15.1 | | | within noise |
| td-thr0 | NVIDIA threaded GL off | 215.9 | 35.3 % | 14.6 | | | render thread CPU 37 -> 52 %, no gain |
| td-thr1 | NVIDIA threaded GL on (forced) | 219.7 | 34.0 % | 14.2 | 35.4 | | within noise |
| td-topup | render-chunk pool top-up (`renderChunkTopUp=32`) | 217.1 | 35.1 % | 14.5 | 36.2 | | all 210 extra made ahead of need; within noise |
| td-seam | seam re-bakes queued (`seamSpread`) | 219.4 | 34.8 % | 13.9 | 38.5 | 58.6 | within noise alone |
| td-lsd16 | strong-light threshold 6 -> 16 | 220.5 | 34.0 % | 14.3 | 34.4 | 51.5 | lighting bursts are not the strong ones |
| td-tightbud | per-kind budgets 2/2/2/4 | 223.8 | 30.1 % | 13.1 | 33.0 | 43.4 | small; cutaway/object/arrivals still stack |
| td-sched4 | one prioritized per-frame bake budget (`bakeScheduler`, 4) | 227.1 | 35.0 % | 10.3 | 17.1 | 33.6 | tail halved; arrivals starved (wasted grants) |
| td-sched6 | same, budget 6 | 225.8 | 36.3 % | 10.9 | 18.7 | 32.0 | same as 4 |
| td-pacegpu | present pacing forced (`presentPacing=gpu`) | 218.5 | 16.5 % | 13.4 | 32.0 | 51.0 | jitter 1.9 -> 0.8 ms; overruns unchanged |
| td-pacecpu | `presentPacing=cpu` | 219.3 | 15.3 % | 13.2 | 34.8 | 52.0 | same as gpu |
| td-noups | upscaler off (reference) | 222.0 | 32.7 % | 14.0 | 36.6 | 46.2 | within the band; DLSS stays |
| td-flies | FliesSound toggle fix (`fliesToggleFix`) | ~217 | 35.6 % | 15.4 | 35.5 | 54.5 | flag-192 must-bakes next to the car 430+ -> 0 |
| td-schedad | scheduler, adaptive budget 2..8 | ~222 | 33.6 % | 11.1 | 20.2 | 41.0 | GPU p99 7.4 ms |
| td-combo1 | adaptive scheduler + mip cap + top-up + flies + pacing | 219.6 | 18.3 % | 11.8 | 21.1 | 37.3 | jitter 0.6 ms |
| td-combo2 | + ring fenced per frame, 4 slots | 221.3 | 17.8 % | 11.5 | 19.9 | 45.4 | render submit p99 7.2 -> 6.3 ms |
| td-combo3 | + scheduler fixes, ring trust 8 frames | 225.6 | 16.5 % | 9.9 | 19.1 | 49.0 | 1 %-low 101 fps; all grants used |
| td-combo3r | same, repeat (noise) | ~227 | 15.8 % | 9.4 | 17.9 | 45.8 | noise: +-0.5 ms p99, +-1-2 ms p99.9 |
| td-combo4 | + `occlusionRetain` | 224.4 | 16.6 % | 10.7 | 18.2 | 26.0 | no re-creations to save (0 hidden bakes); stays off |
| td-combo5c | + bake preparation of granted levels only | 231.7 | 13.4 % | 9.7 | 16.8 | 50.1 | holes in the picture (see below) |
| td-combo7 | same, never-textured levels always prepared | 231.9 | 12.8 % | 9.3 | 16.6 | 38.3 | 1 %-low 108 fps; picture = reference |
| td-combo8 | + `upscaleNoGlGet` (no glGet in the DLSS resolve) | 231.2 | 13.0 % | 9.6 | 18.0 | 22.9 | 0 state mismatches over ~9,600 frames; the wait moves to the interop semaphore; stays off |
| td-combo7-b4 | combination 7, adaptive budget ceiling 4 | 229.9 | 13.6 % | | | | no better than 8 |
| td-c7-zgc | combination 7 on generational ZGC | | 14.8 % | 10.5 | 16.7 | 39.2 | 0 GC pauses but more late frames; G1 stays |
| td-c7-g1p5 | combination 7, G1 MaxGCPauseMillis=5 | | 14.1 % | 10.2 | 18.0 | 23.7 | 19 pauses instead of 8, no gain |
| td-combo9 | combination 7 + `seamDirections` | ~231 | 12.9 % | 9.0 | 17.0 | 44.6 | same: the budget is always spent (lighting drift backlog), class shifts do not lower it |
| td-c9-hard6 | + hard cap 6, lighting never overdue | 229.9 | 13.5 % | 9.9 | 17.4 | 38.3 | no gain |
| td-c10a | combination 9 + `occlusionGrantedOnly` | 233.1 | 11.8 % | 9.0 | 15.9 | 26.9 | best: occlusion rebuild only for granted levels |
| td-c10b | + `chunkHandoffSlack` | 233.4 | 12.3 % | 9.0 | 15.9 | 20.1 | hand-off is 0.3-0.5 ms a chunk; no gain, stays off |
| td-c10-aimd | combination 10, adaptive target 45-75 % | 233.7 | 12.0 % | 8.5 | 17.5 | 38.3 | within noise |
| td-c11 | combination 10 + `uniformCache` | 228.5 | 14.9 % | 10.1 | 19.9 | 38.2 | see the pairs below |
| td-ab-c10-1/2 vs td-ab-c11-1/2 | `uniformCache` A/B, alternating pairs | 220.8 / 232.1 vs 226.3 / 226.8 | | | | | no effect: the same configuration spreads 11 fps tonight; only 8 % of uniforms were redundant |
| td-cs-off-1/2 vs td-cs-on-1/2 | `compositeShaderRun` A/B pairs | 227.9 / 228.8 vs 227.2 / 228.2 | 15.3/14.2 vs 15.3/14.6 % | | | | program-0 starts outside bakes 760k -> 65k per 10 s, no fps change |

Per-bake GPU cost (`td-gpusec2`, `td-mip3`): 0.20 ms median of GPU vs 0.027 ms of render-thread submit: bakes are
GPU-bound; the mip chain after each bake (11 levels of 1024x1024) was 0.062 ms of it. `bakeMipLevels=3` builds levels
1-3 only (the composite samples LOD <= 1.33 at the widest zoom): 0.062 -> 0.020 ms, bake 0.201 -> 0.164 ms, same pixels.

Found on the way:
- `FliesSound` re-runs its corpse-flies update for the 3x3 chunks around the player whenever any chunk loads (every few
  frames while driving) and cleared + set the same square's `hasFlies`; each toggle dirtied the chunk level (object remove
  + add, never held): one immediate re-bake next to the player every few frames (`devInvalidateStacks` rig). New override
  `zombie/FliesSound`, key `fliesToggleFix` (default on): the clear is skipped when the square stays the same.
- The render-chunk top-up first built its FBOs on the game thread, which waits for the render thread (`TextureFBO`
  constructor); now queued to the render thread (`pzopt.GlTask`).
- The persistent sprite ring fenced every 64 KB batch; with NVIDIA's threaded driver every `glClientWaitSync` is a round
  trip to the driver thread (~1 ms under load) even for a long-signalled fence. `persistentVboFrameSync` fences frames;
  `persistentVboTrustFrames` (8) reuses a buffer drawn 8+ frames ago with no call (the swap chain caps frames in flight).

## Visual validation (recordings + `harness/holes.py`)

Frame times alone are not enough: `td-combo5` (bake plan moved ahead of the bake preparation) reported 239.3 fps and
1.3 % late frames because it baked nothing (0 bakes: the plan wrote occlusion counts from last frame's grid, levels
stuck at 0 visible squares). Every candidate is now checked with `pzopt-bakes.out` (bakes > 0) and a recorded run.

`harness/holes.py <run>...` samples the recording at 2 fps over the route and measures black area not connected to
the screen border (a chunk level that should be drawn but is not) and black connected to it (the world beyond the grid).
Cut-away buildings with unexplored rooms are enclosed black too (the game's own), so runs of the same route are compared:

| run | enclosed black mean | p90 | frames > 0.5 % | what |
|---|---|---|---|---|
| td-rec-base | 0.85 % | 3.01 % | 23 / 73 | defaults |
| td-rec-nosched | 0.86 % | 2.96 % | 25 / 73 | combination without the scheduler |
| td-combo5b | 2.69 % | 5.54 % | 60 / 73 | scheduler + preparation of granted levels only |
| td-combo6r | 2.40 % | 6.33 % | 51 / 73 | + arrival quota (did not fix it) |
| td-combo7r | 0.88 % | 3.03 % | 25 / 73 | preparation skipped only for held levels that have a texture |

The holes were whole chunk levels missing inside the loaded world: the bake preparation also writes each level's
square flags, which the occlusion count reads; a level never prepared counted 0 squares, was skipped as occluded before
the bake decision and never prepared. A held level keeps its texture and the flags of its last preparation (they match
what is on screen), so only those skip the preparation.

## Draw calls (`pzopt.DrawStats`, instrumented runs)

Every tile sprite drawn with the tile-depth shader starts that shader again with its own depth uniforms, i.e. one draw
call plus 2 sampler + 4 value uniforms per sprite: ~220-270 shader starts per chunk bake and ~800-1,100 per frame outside
bakes (run `td-c10-draws`). With NVIDIA's threaded driver that is several thousand GL calls a frame for the driver
thread, and the render thread's late frames wait for it (40 % of their samples in the first `glGet` after the world pass,
run `td-prof4`). `uniformCache` skips repeated sampler setups and unchanged uniform values between consecutive starts of
one program; batching the sprites themselves (depth values as vertex attributes, one draw per texture change) is the
larger step.

## Regression suite: the candidate set on the other benches (runs `td-reg-*`, 2026-09-25)

Set K = `bakeScheduler` + `bakeBudgetAdaptive` (2..8) + `bakeMipLevels=3` + `renderChunkTopUp=32` +
`persistentVboFrameSync` + `persistentVboSlots=4` + `occlusionGrantedOnly` (no present pacing), each bench once with
the previous defaults (ctl) and once with K, back to back. These benches do not pin the frame cap and the shared
options.ini had `frameRate=60` from another session, so all ten ran at the 60 cap (both sides alike):

| bench | p99 ctl -> K (ms) | p99.9 ctl -> K (ms) | picture |
|---|---|---|---|
| spin (Rosewood, facing spinning) | 30.7 -> 24.1 | 48 -> 36 | |
| zoom-cycle | 35 -> 28 (worst zoom step 43.7 -> 33.6) | 67 -> 41 | |
| flicker (`hold=10`) | 34.8 -> 31.0 | | `flicker.py` at 60 fps: 11.2k vs 12.3k transient px/frame, same p99 / max |
| night-torch | 35 -> 27 | 50 -> 33 | beam clean, no chunk patches; `flicker.py` 3.46k vs 3.20k px/frame |
| louisville | 68 -> 55 | 131 -> 114 | strongMarks 6.2k / 6.0k (not flooded); settle-window frames alike |

The scheduler delays a strong-light re-bake by up to `bakeMaxWaitStrong` (6) frames; the torch runs show no blocky
patchwork. Louisville K counts 60k deferrals against 10k (a waiting level is counted every frame it waits) and 23 %
fewer bakes, with the same picture.

## Adopted as defaults (2026-09-25)

`bakeScheduler=true`, `bakeBudgetAdaptive=true`, `bakeFrameBudget=8`, `occlusionGrantedOnly=true`, `bakeMipLevels=3`,
`renderChunkTopUp=32`, `persistentVboFrameSync=true`, `persistentVboSlots=4` (with `fliesToggleFix`, already on), each with
an Options-tab entry. Not adopted: `presentPacing=gpu` (halves the late frames on its own but holds every frame to the
GPU's completion: the maintainer's call at a fixed refresh), `seamDirections`, `seamSpread`, `occlusionRetain`,
`chunkHandoffSlack`, `uniformCache`, `compositeShaderRun`, `upscaleNoGlGet` (all measured, no gain; they stay as keys).

Every run above had `upscaler=dlss` from the maintainer's tab file; the shipped default is `upscaler=off`, so the
confirmation pairs pin it off (drive-120-south, 240 cap, the new build installed, previous defaults by `--prop`):

| run | fps | p99 | p99.9 | max | 1 %-low | below cap | GPU | bakes |
|---|---|---|---|---|---|---|---|---|
| td-dfo-1 (previous) | 227.8 | 13.0 | 29.8 | 44.1 | 77 | 26.8 % | 61 % | 18,992 |
| td-dfn-1 (new) | 232.1 | 10.4 | 22.4 | 40.2 | 96 | 29.2 % | 47 % | 16,034 |
| td-dfo-2 (previous) | 224.9 | 13.0 | 35.5 | 43.6 | 77 | 28.4 % | 61 % | 19,145 |
| td-dfn-2 (new) | 234.2 | 9.5 | 17.4 | 25.3 | 105 | 29.1 % | 48 % | 16,065 |
| td-dfo-rec (previous, recorded) | 223.5 | 13.7 | 33.3 | 46.1 | 73 | 28.7 % | 62 % | 18,943 |
| td-dfn-rec (new, recorded) | 232.9 | 9.8 | 15.9 | 99.1 | 102 | 31.1 % | 49 % | 15,911 |

Holes (`holes.py`): enclosed black 0.86 % vs 0.97 %, frames > 0.5 % 24 / 73 both, edge-connected 6.7 vs 6.8 %.
The 99 ms frame of `td-dfn-rec` is one game step (hand-off 97 ms, GPU 2.4 ms, no GC pause, 9 bakes that frame, the
five other runs max 25-46 ms): not a bake burst, cause not found. The share of frames below the cap stays ~29 %
without present pacing: most of them are swap-time jitter on steps that sit on the 4.17 ms grid.

## After the adoption: what the late frames still are (run `td-dfn-prof`, upscaler off, `presentPacing=gpu`, async-profiler)

With pacing on, 14 % of the frames are late: 901 by the game step, 195 by the swap, 95 by the render submit.
- Late render submits: ~40 % of their samples are driver round trips (GL calls that return a value wait for NVIDIA's
  driver thread): `glGetInteger(GL_CURRENT_PROGRAM)` in `DeadBodyAtlas.toBodyAtlas` (corpses drawn into the atlas),
  `glGenTextures` (corpse clothing `TextureCombiner`, new atlas pages, render-chunk textures), `glCheckFramebufferStatus`
  (`TextureFBO`), and our own tree-append check (`glGetFramebufferAttachmentParameteri`).
- Late game steps (> 5 ms): 6.2 ms of CPU against 1.9 for on-time ones; the extra 4.2 ms is spread: tile rendering and
  bake preparation +1.8, chunk arrivals on the game thread (`IsoChunk.doLoadGridsquare`, `loadInMainThread`) +0.6,
  lighting of new chunks +0.5, occlusion +0.3, translucents +0.3. Late share by bakes that frame: 4 % with none, 19 % with
  4, 49 % with 8+.

| run pair (x2, alternating) | technique | fps | p99 | p99.9 | 1 %-low | below cap | bakes | verdict |
|---|---|---|---|---|---|---|---|---|
| td-e-base | new defaults | 236.6 / 234.8 | 8.8 / 9.6 | 15.5 / 16.1 | 113 / 104 | 28.5 / 28.7 % | 16.4k / 16.3k | reference |
| td-e-smooth | `bakeSmooth` (EDF demand bound) | 236.1 / 236.0 | 9.2 / 9.0 | 15.3 / 16.2 | 108 / 111 | 26.9 / 26.5 % | 13.5k / 13.6k | 17 % fewer bakes (waiting lighting re-bakes merge), 2 points fewer frames below the cap, jitter 1.3 -> 1.2 ms; fps / p99 unchanged |
| td-e-nosync | `glNoSync` | 236.2 / 233.8 | 9.1 / 9.6 | 15.7 / 15.5 | 110 / 104 | 29.7 / 30.5 % | 16.4k / 15.9k | no effect; correct (`td-e-check`: 10,749 record-vs-driver checks, 0 mismatches; 629 of 646 FBO status checks skipped) |
| td-e-both | both | 236.0 / 234.0 | 8.7 / 9.6 | 15.3 / 15.5 | 115 / 104 | 27.2 / 27.7 % | 13.5k / 13.5k | as `bakeSmooth` |

The driver round trips cluster in the late render windows but add up to ~1.5 % of the render thread's time; removing
them does not move the frame rate, which is set by the game thread's bursts.

| run pair (x2, alternating) | technique | fps | p99 | p99.9 | 1 %-low | below cap | frames held | verdict |
|---|---|---|---|---|---|---|---|---|
| td-f-base | new defaults | 236.0 / 236.0 | 8.7 / 9.0 | 17.2 / 15.4 | 115 / 111 | 30.2 / 25.8 % | - | reference |
| td-f-dl40 | `bakeDeadlinePct=40` (no normal-tier bake once the step used 40 % of its interval before the render phase) | 230.4 / 236.3 | 10.7 / 9.0 | 18.2 / 16.2 | 93 / 111 | 30.5 / 27.5 % | 819 / 577 | no gain (the first run is an outlier its pair does not repeat) |
| td-f-dl55 | `bakeDeadlinePct=55` | 236.2 / 236.5 | 9.1 / 9.0 | 14.7 / 16.5 | 110 / 111 | 27.5 / 27.7 % | 274 / 286 | within noise |
| td-f-sdl40 | `bakeSmooth` + `bakeDeadlinePct=40` | 235.9 / 236.3 | 9.2 / 8.9 | 15.9 / 15.5 | 109 / 112 | 26.2 / 25.1 % | 591 / 604 | as `bakeSmooth` alone |

`bakeSmooth`, `glNoSync` and `bakeDeadlinePct` stay off (keys kept for A/Bs): each is correct and none moves the frame
rate or the tail beyond the run-to-run spread. Every configuration since the adoption sits at 234-237 fps, p99 ~9 ms,
p99.9 ~15-17 ms on this route, GPU ~45-48 %, game thread ~45 % of a core on average.

## Where the locked 240 stops (2026-09-25)

The route is not locked at 240 on this desktop: ~236 fps, 1.7 % of the frames missing, 1 %-low ~110 fps, with the GPU
at 45-48 % and the CPU at ~19 %. What is left is the game thread's bursts, not one subsystem: a late step carries ~4 ms
more CPU than an on-time one, spread over tile rendering and bake preparation (~1.8 ms), world update (~1.5 ms:
chunk arrivals, lighting of new chunks, objects, Lua) and occlusion. Every bake-side limit tried (budgets, AIMD,
demand bound, in-frame deadline) trades bakes for waits without removing the bursts, and the render-thread driver syncs
are gone without effect. Present pacing (`presentPacing=gpu`) removes most of the swap jitter (below-cap frames
~29 -> ~12 %) at the cost of latency; that choice is the maintainer's. The next step would be structural: the bake
preparation and the chunk arrival work off the game thread (worker-built per-level object lists, chunk loads split
across frames), or rendering decoupled from the world update.

With `presentPacing=gpu` on the new defaults (`td-g-pace-1/2`, upscaler off, no profiler): 235.6 / 235.6 fps, p99 8.4 /
7.9 ms, p99.9 13.7 / 15.9 ms, jitter 0.4 ms (1.3 without), 1 %-low 120 / 126 fps, frames below the cap 11.0 / 10.8 %
(~28 % without); render thread 40 % of a core (the hold), GPU 48 %.

## Release (2026-09-25): present pacing on by default, stock vs this release

The maintainer took the pacing trade: `presentPacing=auto` now resolves to gpu wherever the GL has timestamp queries
(fixed refresh included), cpu only while VRR is active, off under the macOS Metal bridge. Its cost from the pacing logs
(`td-g-pace-*` vs `td-f-base-*` / `td-e-base-1`): the swap is held 2.95 ms on average, but without pacing the flip waits
for the GPU anyway, so step-to-screen goes ~5.0 -> ~6.7 ms on average (+1.7), 3.5 -> ~6.1 ms at the median (+2.6), and
the p99 is unchanged (~16-20 ms: late frames go at once). The render thread's share of a core rises 24 -> 40 % (the hold).

Stock game (`--prop enabled=false`) against the release build, drive-120-south, 240 cap, upscaler off, recorded
(`td-rel-*`; `pacing: presentPacing=auto -> auto (gpu)` in the release runs' console):

| run | fps | p99 | p99.9 | 1 %-low | below cap | jitter | game thread |
|---|---|---|---|---|---|---|---|
| td-rel-stock-1 / 2 | 152.8 / 152.9 | 37.0 / 35.6 | 56.5 / 51.0 | 27 / 28 | 42.0 / 43.6 % | 2.6 / 2.4 | 97 % |
| td-rel-new-1 / 2 | 235.2 / 235.4 | 8.2 / 8.2 | 13.8 / 15.8 | 122 / 121 | 11.1 / 10.6 % | 0.4 / 0.4 | 45-46 % |

The Workshop card (`docs/workshop/images/30-smooth-operator-driving.gif`, `harness/smooth-card-gif.py`) plays
td-rel-stock-1 above td-rel-new-1 at the same route second with both frame-time traces.

## Structural pass: the game thread's bursts (2026-09-25, after the release)

Rig: `harness/jfr-subtree.java` (callee tree under one frame from the async-profiler JFR, 2 ms samples) and
`harness/jfr-windows.java` per worst-step windows. **Trap:** native threads inherit their creator's OS name, so Bink's
and FMOD's idle workers are also "MainThread": wall samples filtered by name alone showed a third of the worst steps as
libc waits. Both tools now match a Java thread name against Java threads only (`JFR_JAVA_ONLY=0` for the old behaviour),
and a `cstack=vm` profile (`--asprof event=cpu,interval=2ms,wall=2ms,cstack=vm`) walks through native frames.

Game-thread CPU on the drive (release build, `td-dfn-prof`): tile rendering 28.6 % (chunk-level bakes 12.5 %:
emission 5.5 %, occlusion counts 1.5 %, object render info 1.4 %, puddles 0.9 %; translucents 4.6 %; view cone 1.8 %),
chunk arrival `IsoChunk.doLoadGridsquare` 4.4 % (border neighbour recalculation 2.4 %), lighting of new chunks 3 %.
The 40 worst steps (10-104 ms, `td-s-profvm`), wall time on the game thread: 20 % `RBTrashed.trashHouse` ->
`RoomDef.isKidsRoom` on chunk load, 19 % waiting for the render thread (`pushFrameDown`), the rest spread.

| step | technique | result |
|---|---|---|
| 1 | `lightingVisionParallel` on the 16-core desktop (existing key, vision tests of new chunks on the workers) | 236.2 / 234.0 vs 236.2 / 234.9 fps, tails the same: stays off |
| 2 | `occlusionCountParallel`: every on-screen level's rendered-squares count on the FrameBatch workers right after the occlusion grid (stock recounted level by level on the game thread whenever the grid changed; `FBORenderOcclusion.isOccluded` writes a shared field, so the test runs on locals) | 1,214,010 worker counts checked against the stock count, 0 mismatches (`devOcclusionCountCheck`); p99 8.1 / 8.7 -> 7.8 / 7.9 ms, 1 %-low 124 / 115 -> 128 / 127: **on by default** |
| 3 | `kidsRoomMemo`: `RoomDef.isKidsRoom` answers kept per room through one trashed-house pass (new `RoomDef` + `RBTrashed` overrides, `pzopt.KidsRoom`; the pass changes no kids-room tile, so exact) and the 19 tile names in a static set | 689 memo answers rescanned, 0 mismatches; a pass that answered 2,005 times from the memo took 6.3 ms, the checked pass (every answer rescanned, i.e. stock's cost) 19.8 ms for 689; the spike source of the 10-104 ms steps: **on by default** |

Measurement: the harness's MangoHud preload hooks `glXSwapBuffers` and calls `glXQueryDrawable` on every swap (a driver
round trip; 26 % of the render thread's wall time in the worst windows). Release defaults with / without it
(`td-mh-*`): 235.6 / 235.6 vs 236.5 / 236.9 fps, p99 8.1 / 8.0 vs 7.1 / 7.2 ms, 1 %-low 123 / 125 vs 141 / 140. Players
see the second; the in-game overlay logs the same metrics, so drive measurements from here on use `--no-mangohud`.

`glNoSync` re-tested without MangoHud and with a 256-name pool (`td-ns-*`): 236.5 / 236.7 vs 236.2 / 236.6 fps, p99
7.2 / 7.5 vs 7.5 / 7.2 ms: still no effect, although driver round trips are ~36 % of the render thread's wall time in
the worst windows (`td-s-profvm2`): removing one moves the wait to the next sync point while the driver thread is busy.

| step | technique | result |
|---|---|---|
| 4 | `lightingNewChunkBudget`: `LightingJNI.update` lights at most one never-lit chunk a pass (nearest first; a chunk is not drawn until lit, so it appears a pass later at the grid edge); chunks already lit always update; with more than `lightingNewChunkBacklog` (8) never-lit chunks waiting (a world load, a teleport) every one is lit at once, as stock | budget 1 against the defaults, three runs each (`td-l-*`): 237.0 / 237.2 / 237.0 vs 235.0 / 235.6 / 235.9 fps, p99 7.2 / 6.9 / 7.0 vs 8.3 / 7.6 / 7.5 ms, 1 %-low 139 / 145 / 144 vs 121 / 131 / 133, game thread 41-45 vs 45-48 %; p99.9 not better (14.0-16.7 vs 13.9-14.9 ms). Budget 2 in between. Holes equal (1.06 vs 1.02 % enclosed black, 34 vs 35 frames over 0.5 %). **On by default** |
| 5 | JIT warm-up with the JDK's AOT cache (Leyden, JEP 483 / 514 / 515 in the game's Zulu 25.0.1): C2 compiles 0.2-1.3 cores' worth all through the route, peaking when the drive enters town (seconds 14-18, the game thread's own CPU doubles there), i.e. the town's code paths run cold the first time. A drive training run wrote the configuration; the cache (88 MB) from `-XX:AOTMode=create`; runs with `run.sh --overrides-jar` (new: the overrides as `<game>/pzopt-harness/pzopt.jar` on the classpath instead of `.`, since the AOT cache refuses directories; the path contains "pzopt.jar" so the build guard skips it) | cache used (`-Xlog:aot`: opened and mapped): 236.9 / 237.0 vs 237.3 / 237.1 fps, p99 7.4 / 7.1 vs 7.0 / 7.0 ms, boot and load within noise. JDK 25 caches classes and profiles, not compiled code: no gain on the drive. Found on the way: assembling the cache with the game's own module options (`--add-exports java.base/jdk.internal.misc`, `--enable-native-access`) crashes Zulu 25.0.1 (`guarantee` in `ArchiveHeapWriter::compute_ptrmap`), which is what the in-game assembly child does: the existing `aotCache` feature (`pzopt.AotCache`) likely never writes a cache on this JDK; without those options the assembly works (module graph not archived) |

State after the structural pass (defaults, no MangoHud, `td-l-def-*`, `td-aot-jar-*`): 236.7-237.4 fps, p99 7.0-7.3 ms,
p99.9 12.9-15.0 ms, 1 %-low 136-143, frames below the cap ~12 % (present pacing on), GPU 50 %, game thread 44-45 % of a
core. Still not locked at 240: the worst steps (up to ~22 ms) are now a quarter waiting for the render thread (NVIDIA
driver round trips in the frames that create textures or bake a burst) and otherwise spread across tile rendering, the
entity update and chunk arrival, each 1 % or less; the bake emission itself (5.5 % of the game thread) records into the
single-threaded SpriteRenderer state through static APIs, which rules out recording bakes on workers without a rewrite
of the sprite path.

## The flip (2026-09-25, release f05e11d, `flip-td-*`)

Same route, 240 cap, balanced power profile, `--launcher direct`, no MangoHud; stock = `--prop enabled=false` on the
installed build (the same stock path as the Workshop card), alternating pairs; all four drove the whole 852-tile path.

| run | fps | p99 | p99.9 | max | 1 %-low | jitter | game thread | GPU |
|---|---|---|---|---|---|---|---|---|
| flip-td-stock-1 / 2 | 91.8 / 92.7 | 38.3 / 38.6 | 59.6 / 61.2 | 94.0 / 72.4 | 26 / 26 | 3.0 / 2.7 | 90-91 % | 37 % |
| flip-td-opt-1 / 2 | 116.2 / 116.6 | 15.6 / 15.0 | 25.0 / 26.3 | 48.0 / 37.4 | 64 / 67 | 0.7 / 0.7 | 80 % | 40 % |

The flip stays game-thread bound (80 % of a core with the release), far below the cap; the tail shrinks the most.
