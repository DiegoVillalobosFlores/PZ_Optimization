# Visual-parity watch over the zombie / player game-thread passes (2026-09-22)

A separate session (this file's author) validates visual parity with stock after every build the
three sessions below run, and records what they say when a validation does not pass. They keep
working meanwhile; a drift is reported, not blocked.

| Session (queue id / ListAgents name) | Pass | Keys |
|---|---|---|
| `b2de8932` / pz-optimization-bc | player: `IsoPlayer.updateLOS`, zombie spotting, light-dirty tracking (`lou-los*` runs) | `playerLosFast`, `zombieSpotFast`, `lightDirtyOncePerFrame`, chunk-level clean stamp, `devLightDirtyCheck` |
| `c1a039f1` / pz-optimization-29 | zombies: ECS lookups, action conditions, skinning, shadows (`lou-zm-*` runs) | `ecsLookupFast`, `actionConditionFast`, `skinTransformsPrecompute`, `skinPalettePrecompute`, `shadowPrep`, `boneIndexCache`, `devSimChecksum` |
| `f069875d` / pz-optimization-b9 (added 04:44) | characters draw: the render thread's character pass on Louisville, target < 5 % | `charDrawPrep` (draw data of the on-screen zombies built on workers), `zombieAtlasFast` (flat render path for culled atlas-sprite zombies) |
| `40e728a1` / pz-optimization-96 (added 05:03 on the session's own word that the maintainer asked for it) | render-resolution upscaling: the offscreen buffer (`MultiTextureFBO2` / `Core.getOffscreenBuffer`, D24S8 depth) rendered smaller and resolved by an upscale pass, UI native; FSR 1.0 in GLSL, then DLSS via a Vulkan-interop shim on NVIDIA | `upscaler=off\|bilinear\|fsr1\|dlss` (default off), `upscalerQuality=quality\|balanced\|performance\|ultra`, `fsrSharpness`; runs `ups-*`, recorded, uncapped, bench + fog / storm presets; `upscaler=off` runs alongside. An upscaled frame is softer by design; flag flicker / ghosting / black tiles / HUD drift, not steady softening |

All four edit the one shared checkout, so every build (and every validation) carries the union of their
edits; a failing validation is attributed with the offending session's keys switched off.

## Validation

Two recorded runs per iteration on the installed build (`--install keep`), judged by
`harness/parity-judge.py` (Jev) against a stock (`enabled=false`) recording of the same route:

| Label | Route | Stock reference | What it covers |
|---|---|---|---|
| `vpN-lou` | `--preset louisville`, uncapped | `lou-rec-stock-20260922-030013` | 2,000+ zombies: skinning, shadows, action states, ECS; `see_all` masks LOS |
| `vpN-torch` | `--preset night-torch` (S:450, turn=90, max zoom), 240 cap | `bl-torch-stock-20260921-174650` | player LOS (no `see_all`), moving light → light-dirty tracking (the blocky-lights metric), the bench save's few zombies |

Metrics: transient px/frame (flicker), black share / jumps, luma pops, hard luma jumps and solid
jump blocks (stale lighting), HUD corners excluded. `parity=` kinds: parity / hud_only / flicker /
black_tiles / lighting_pops / static_scene_difference / inconclusive. Not measured: a wrong but
steady sprite, a colour shift.

## Iterations

### Declared intent (04:33, before any validation)

- **los (pz-optimization-bc):** "No intentional visible change from my keys: `playerLosFast` keeps the exact
  stock spotted lists (set beside the stack, same contents and order); `zombieSpotFast` changes only when
  zombies with an already-zero spot chance stop drawing `Rand.Next(10000)` (RNG sequence shift, no visible
  logic change); `lightDirtyOncePerFrame` was removed at 04:24, so nothing of mine touches lighting any
  more." One more build coming: default-off experiment key `playerLosNative` (C++ LOS arithmetic,
  `natives/libpzopt_los64.so` in `build/classes`); its off state ships. Will message when the last build
  is installed.
- **zm (pz-optimization-29):** "None of my keys is meant to change a pixel: `ecsLookupFast` /
  `actionConditionFast` are exact-result rewrites, `skinTransformsPrecompute` / `skinPalettePrecompute`
  (next build) / `shadowPrep` move the same arithmetic onto the bone worker with the same inputs,
  `boneIndexCache` is a lookup memo. The one thing that could show is `shadowPrep` serving a one-frame-old
  shadow ellipse if anything moved a zombie's bones between the batch join and `renderShadow` (grapples are
  excluded from the batch); a shadow blob a frame late is what to look for." Agrees the `devSimChecksum`
  simdiff between two runs is unusable (zombie count differs from frame 5: spawn timing, not the keys); the
  per-phase rig becomes an in-run dual evaluation. Next build: `skinPalettePrecompute` (`AnimatedModel`
  override) + `shadowPrep` counters; then the parallel action-context evaluation ("the one with real
  risk"). Will message when that build is installed and when the pass is done.

### Iteration 0 — install of 04:25 (los8 + zm shadow build), jobs 0203 / 0204

| Run | Judge | Reading |
|---|---|---|
| `vp0-lou` (04:34, 24.5 fps) vs `lou-rec-stock` | `parity=lighting_pops` conf 0.59, maintained 0.04, look 0.77 — transients 1048 px/frame (stock 67), black share 60.6 % (43.5 %) with 122 jumps (3), 11 luma pops (0), solid jump blocks 1.03 (0.25) | **Not attributable to the keys.** The run was GPU-starved: the game's own `gpu_ms` was 33 ms mean / 61 p90 against 12-15 ms in every other Louisville run of this build (`lou-los8`, `lou-zm-off`, `lou-zm-on2`, `lou-rec-opt/fix`), overlay `gpu_load` 80 % vs 42-58 %, and the game thread spent 20 % of its samples in `SpriteRenderer.waitForReadySlotToOpen` (1 % elsewhere). At 24 fps on a 6 tiles/s walk the black share and the "pops" are chunks arriving late and lighting re-bakes landing in bunches (the 09-22 night `lou-*` finding: a re-bake feedback loop below ~27 fps). sysmon shows nothing else on the GPU during the window (`gpu_pct` 73 %, 146 W all from the game) — the recording itself (NVENC AV1 at 5K) is the only difference to the peers' unrecorded runs, yet `lou-rec-opt` (recorded, 03:00) had 15 ms. Re-queued as `vp1-lou` (0217) with a keys-off control `vp1-lou-keysoff` (0218): if the control matches stock and `vp1-lou` does not, the drift is the keys; if both fail the same way, the Louisville route at 25-45 fps is not a parity route for the recorded judge and the reference has to be re-recorded on the same day. |
| `vp0-torch` (04:39) vs `bl-torch-stock` | confirmed invalid: 0 `[pzopt]` console lines (stock game), `route_complete=0`, the judge windowed 272-292 s of a 5 min idle capture (`parity=parity` 0.40 means nothing here). **Invalid by construction**: it launched at 04:39:14 onto a game dir with no overrides (`pzopt.sh status: installed: no`): job 0212 (zoom session, `--install opt`) ran `pzopt.sh reinstall` after the los session's `build-losn` (0209) had failed on `NativeLos() has private access`, so uninstall succeeded and install found "nothing built". `build-losn2` (0213) rebuilt at 04:39:14 but nothing reinstalled before 0204 started. | Re-queued as `vp1-torch` (0219). Note for the queue: `--install opt` after a failed build leaves the desktop stock for every following `--install keep` job. |

Known-good band: job 0220 `vp-band` judges `lou-rec-fix` (the 03:01 build, before both passes) against `lou-rec-stock` the same way, to know what "parity" reads like on this route.

**Known-good band on the Louisville route** (job 0220, `lou-rec-fix` = the 03:01 build before all three passes,
vs `lou-rec-stock`, both recorded the same way): transients 58 vs 67 px/frame (world 48.6k vs 52.5k), black
36.4 % vs 43.5 % (jumps 5 vs 3), luma pops 3 vs 0, hard jumps 7654/39750 vs 12353/58089, solid blocks 0.12 vs
0.25. Jev: `kind=parity` 0.57 but `parity_maintained` only 0.22 (the 3 luma pops). So on this route the
`parity=` line alone is not the verdict: a run is inside the band when its transients, black share, pops and
solid blocks sit within ~1.5x of these numbers; `vp0-lou` was 15x / +17 points / 11 / 4x outside it.

- **zm (04:50):** the parallel action-context evaluation build (`build-zm-eval`, 255 classes: `actionEvalParallel` +
  `skinPalettePrecompute` + `shadowPrep` counters) is installed by its queued `lou-zm-evalcheck` (`--install opt`),
  then `lou-zm-off3` / `lou-zm-on4`; validate after `lou-zm-evalcheck`. Stock-behaviour comparison keys:
  `actionEvalParallel=false skinPalettePrecompute=false shadowPrep=false` plus the four earlier ones. → `vp1-lou`
  (0217) and the control `vp1-lou-keysoff` (0223, all nine keys off) run on that install.

### Iteration 1 — install of 04:48 (`build-zm-eval`: `actionEvalParallel` + `skinPalettePrecompute` + `shadowPrep` counters, los8 + `playerLosNative` off), jobs 0217 / 0223 / 0219

| Run | Judge | Reading |
|---|---|---|
| `vp1-lou` (04:51, 50.3 fps, gpu_ms 13.4 — healthy this time) vs `lou-rec-stock` | `parity=parity` conf 0.37 (parity 0.46, **flicker 0.32**), maintained 0.43, look 0.74 — transients 71.9 px/frame (stock 67.1; world 69.0k vs 52.5k, band 48.6k), black 36.6 % (band 36.4), jumps 6 (5), luma pops 2 (3), hard jumps 7794/40727 (band 7654/39750), solid 0.10 (0.12) | Inside the known-good band on every metric but one: the world transients are 1.3x stock / 1.4x the band, and they sit in **one cell, "upper-middle centre-left" = 30.5k px** (stock 8.7k, band 12.3k; the other cells are at band level). That cell is the quarter of the screen up-left of the player. Two candidate explanations: (a) 50 fps vs 23-29 fps means every 60 fps sample is a distinct frame, so A-B-A transients are counted more often (fps-driven); (b) something near the player blinks for 1-3 frames (the zm session's own suspicion: a one-frame-old shadow ellipse or skin palette). The control `vp1-lou-keysoff` (0223, same install, all nine keys off) decides; `vp1-crops` (0233) dumps the before/during/after crops of the six worst bursts in that cell plus the colour-shift check. |

**Resolution of the `vp1-lou` transient cell (05:00, jobs 0233 / 0234, `harness/transient-crops.py`):** the
30.5k px in "upper-middle centre-left" are ~100-500 px/s for 11 s, then 7701 / 6540 / 3864 / 4982 / 3436 px/s
from 53 s on, all in the cell's top-left quarter; the before / during / after crops (`<run>/transient-crops/`)
show **the in-game overlay's game-thread tree and flame graph** redrawing (rows `IsoWorld.updateInternal`,
`IngameState.update`, `GameWindow.logic`, `game thread` …), no world object. The overlay panels extend far
below the top-left corner cell that the judge excludes, so their 1 Hz redraw counts as "world" transients
and their green / blue / amber row colours leak into the colour statistics (`colorshift.py` read
`midtone_ratio 1.59`, `sat_ratio 1.22` for the same reason). **`vp1-lou` therefore passes**: every world metric
inside the known-good band, the only excess is the overlay. `vp1-lou-keysoff` (0223, all nine keys off) ran in
the doubled-GPU regime (gpu_ms 30.5, 27 fps) and reproduced `vp0-lou`'s failure signature exactly (976 px/frame,
black 56.7 % with 133 jumps, solid blocks 1.03, `lighting_pops`), which attributes the `vp0-lou` failure to that
regime and not to any key.

**Recipe change from `vp1-torch` (0236) on:** every validation run passes `--prop overlayTree=off --prop
overlayFlame=off --prop overlayGraph=off --prop overlayVerdict=off` (the stats line stays in the excluded
top-left corner; the overlay log `pzopt-overlay.out` is still written), so the judge's world metrics and the
colour check measure the world. `vp1-torch` (0219) was cancelled and resubmitted as 0236 with these props.

**Doubled-GPU regime (05:00):** 7 of ~25 Louisville runs today ran with the game's `gpu_ms` at 29-33 ms instead
of 12-15 (`lou-final` 03:21, `lou-los1` 03:50, `vp0-lou` 04:34, `lou-zm-on3` 04:37, `lou-losn-on` 04:45,
`lou-zm-evalcheck` 04:48, `vp1-lou-keysoff` 04:55), no adjacency rule (a run 7 s after a recorded run was normal,
`vp0-lou` itself followed an unrecorded GameProfiler run). Nothing of the validation jobs outlives them (run.sh
SIGINTs and waits for gpu-screen-recorder; the judge decode runs inside the job). A per-process GPU log runs
since 04:58 (`nvidia-smi pmon -s um -d 1` → `/tmp/pzopt-parity-watch/gpu-pmon.log`) to name the other GPU
client the next time; a parity run in that regime is judged invalid for fps and re-queued (the zm session does
the same for its measurements).

| `vp1-torch` (05:03, 615 fps — the framecap left uncapped by an earlier run's restore; the judge resamples to 60 fps) vs `bl-torch-stock` | **`parity=parity` conf 0.84, maintained 0.77, look 0.63** — transients 966 px/frame (stock 881), black 87.39 % (87.35), jumps 23 (20), luma pops 0 (0), hard jumps 4565/9201 (4299/8596), solid 0.51 (0.46); the same four busiest cells in the same order (the torch beam sweep) | **Pass.** Player LOS keys on, no `see_all`. This run already had the 05:01 install (`lightingReadParallel=true` from `build-zm-light`, installed by the los session's `--install opt` jobs 0231/0232 — the shared checkout means any session's `--install opt` ships every session's build). |

Iteration 1 verdict: **pass** on both routes for `actionEvalParallel` + `skinPalettePrecompute` + `shadowPrep` +
the los keys; `lightingReadParallel` is covered on the torch route only → `vp2-lou` (below).

### Iteration 2 — install of 05:04 (`build-zm-light`: `lightingReadParallel`; `build-losn3`: `playerLosNative` off; zoom session's staged build), job vp2-lou

| `vp2-lou` (05:07, "218 fps") vs `lou-rec-stock` | `parity=lighting_pops` conf 0.50, maintained 0.07 — transients 1724 px/frame, solid blocks 4.0, hard jumps 9341 mean / 1423 p90 | **Failed, not a rendering drift: the god-mode ghost player died at the route start.** The recording shows the death screen ("You survived for 1 month, 6 days, 3 hours. You killed 4114 zombies.") from 40 s on and a static picture until the quit (the judge's activity window therefore covered the load → world → death transitions); the console's route-end line has `wornRestored=8` (eight knock-offs, every other Louisville run today has 0), the route "completed" over the black death screen at 266 fps / gpu_ms 3 with 912 chunks loaded (456-565 normally). Same install, other runs: `lou-losn2-on` 05:01, `lou-losn2-off` 05:04, `lou-losn3-off` 05:09 survived. First death in ~25 Louisville runs today. Asked the zm session (actionEvalParallel / FrameBatch / lightingReadParallel) whether intentional or transitive, FYI to the los session; repeat queued as `vp2b-lou` (0253). |

- **los (05:18) on the `vp2-lou` death:** "Nothing on my side touches health, god mode, grapples or the player's
  state: `playerLosFast` only changes how the spotted lists are searched (same contents), `playerLosNative` is off
  (and the library was not even installed on that build — the run logged 'not found, Java loop used'), and the
  `IsoPlayer` override has no other edits. The one transitive effect I do have is `zombieSpotFast`: zombies with a
  zero spot chance no longer draw `Rand.Next(10000)`, so the shared RNG sequence differs from stock from the first
  frame — every random outcome after that (which zombie lunges, when) is a different roll. That has been in every
  `lou-los*` run since 03:50 without a death, so I would look at the 05:04 install's other half first; if
  `vp2b-lou` dies again, a run with `--prop zombieSpotFast=false` isolates my RNG shift." → recorded as
  *transitive (RNG sequence), not intentional*; the isolation run is queued if the repeat dies.

| `vp2b-lou` (05:14, repeat) | `parity=lighting_pops` 0.31 — but gpu_ms 35 / 22 fps: **doubled-GPU regime, invalid** | Player survived (`wornRestored=0`). Not a repeat of the same install either: at 05:12 the los session had installed a clean **worktree** build (`/tmp/pzlos-wt` = HEAD a69993f + `playerLosFast` / `zombieSpotFast` / `playerLosNative` off, 35 overrides, no zm keys) because the shared checkout did not compile with the zm files in flux — deliberate on their side, and it means every run from 05:12 until the zm session's next install carries no zombie key. Per-process GPU log of this doubled run: no external GPU client (gsr 7 % SM / 22 % NVENC as in every recorded run, kwin 6 %); the game itself at 42 % SM vs 20-23 % in normal runs, 130 W, game thread with prepare chunks 4 % + cutaways 4 % + hand-off wait 15 %. All 9 doubled runs of the day sit at 27-30 fps = the lighting re-bake tipping point of this scene (CLAUDE.md, Louisville horde pass); reading: a slow start drops a run under it and the strong re-bakes keep it there. Reported to the zm session with `lightingStrongBudget` as the knob. |

- **zm (05:19) on the `vp2-lou` death:** "not intentional, and a known defect of that install — the build-zm-light /
  build-zm-eval `actionEvalParallel` ran the zombies' transition conditions on workers including callbacks that are
  not pure: `blunge` calls `PolygonalMap2.lineClearCollide` (`LineClearCollideMain`'s PointPool is a plain
  ArrayDeque; my lou-zm-on4 crashed there with NoSuchElementException / NPE in ArrayDeque.push from a worker and then
  on the game thread, frame 1992, and the route 'completed' early like yours), `bHasTarget` clears the zombie's
  target, `bthump` drops the thump target, `beatbodytarget` scans corpses — side effects racing with the game thread.
  A corrupted point pool gives wrong line-clear answers to every pathfind / lunge / attack on the game thread, which
  fits a horde suddenly landing every hit on the ghost (wornRestored=8) and the death screen; nothing in the build
  touches hits, grapples or death directly, and the player never enters the batch (submit only takes IsoZombie). Fix
  queued as build-zm-snap2: the game thread reads every non-allowlisted callback of the zombie's current state into a
  per-zombie snapshot right where stock would have evaluated it, so the workers only touch stored slots and audited
  pure callbacks (`pzopt.ActionEval.PURE_CALLBACKS`). Until that build is installed, treat any run on the 05:04 install
  with actionEvalParallel on as invalid; `--prop actionEvalParallel=false` is the stock path." → recorded as
  *transitive (defect, fix pending)*; the fixed install gets a fresh pair.
- **los (05:22): pass done.** Final state to validate = the 05:12 worktree install in its shipped form (no
  `--prop playerLosNative`); native experiment: no gain (player 1.0 ms a frame either way), the library never ships.
  → `vp3-lou` (0262) + `vp3-torch` (0263) queued on that install.
- **cd (05:20):** key `charDrawPrep` (default on): the on-screen zombies' model draw data (`ModelInstance.updateLights`,
  `ModelSlotRenderData.initModel` + `init`) built on the frame workers before the stock enqueue loop; enqueue order,
  visibility / alpha rules, shadows, LOD unchanged, no pixel meant to change; model lights once per frame on a worker
  with the same smoothing count; a prepared-but-undrawn zombie is released through a no-op drawer and the
  `char draw:` console line's `leftovers=` must stay 0. Nothing installed yet (build 0254 is the verification build);
  A/B `--prop charDrawPrep=false`.

### Iteration 3 — install of 05:18 (`cd1-on --install opt`: the whole shared checkout = zm `build-zm-snap3` fix + cd `charDrawPrep` + los keys, 43 overrides), jobs 0262 / 0263

The los session's 05:12 worktree install was replaced six minutes later, so `vp3-*` validate the combined install
(their keys in their shipped state, together with everyone else's — which is what ships). `cd1-on` (05:18, 47.6 fps)
logged `char draw: … leftovers=0`.

| `vp3-lou` (05:20, 53 fps, gpu_ms 13.5 — healthy) vs `lou-rec-stock` | **`parity=flicker` conf 0.62 (flicker 0.68), maintained 0.20, look 0.78** — transients 140 px/frame (stock 67; world 135.6k vs 52.5k, band 48.6k), black 40.7 % (band 36-43), jumps 5, luma pops 4 (band 0-3), hard jumps 10224/54397 (stock 12353/58089), solid 0.11 | **Failed on transients**, everything else in band; 81k of the 135.6k world transients in "upper-middle centre-left" (stock 8.7k, band 12.3k, `vp1-lou` 30.5k of which all was the overlay). Overlay panels off this time, player alive (`wornRestored=0`), `char draw: … leftovers=0`. Asked the cd session (charDrawPrep, first install) and the zm session (snap3 fix, lightingReadParallel, shadowPrep) whether intentional or transitive; controls queued: `vp3-lou-cdoff` (0265, charDrawPrep=false), `vp3-lou-zmoff` (0266, the eight zm keys off), crop dump `vp3-crops` (0264). |

**What blinks in `vp3-lou` (05:28, job 0264 crops):** not a character — the big roof up-left of the player: its lit
area changes shape between consecutive frames (a lit stripe extends left, a chunk-sized black rectangle appears
mid-roof, the AC unit's side goes lit → black) and reverts within 1-3 frames; bursts at 53.5 / 55.3 / 56.1 / 56.5 /
59.3 / 60.7 s (10-15k px/s in seconds 11-15 of the window). Chunk-sized lighting disagreement between neighbouring
chunk textures at the vision-cone edge while the player spins = the blocky-lights signature; stock reads 8.7k px in
that cell. Prime suspect `lightingReadParallel` (new in this install; `vp1-lou` without it was clean); `charDrawPrep`
and the los keys do not touch lighting. Colour check: rgb ratios 0.98-0.99, saturation 1.11, hue L1 0.15 — no
colour shift. Told the zm session; the zm-keys-off control (0266) decides.

- **cd (05:31) on the `vp3-lou` flicker:** "nothing in charDrawPrep changes what is drawn on purpose, so read it as
  transitive until your cdoff control says otherwise." What it changes in time for the ~480 batched zombies:
  `ModelInstance.updateLights` once at the start of `renderMovingObjects` on a worker (same smoothing step count, the
  second call skipped by a frame stamp); the draw data (`initModel` + `init`) built at that point, up to a millisecond
  earlier than stock's per-zombie `initModel`, from state the update phase left still; `checkUpdateModelTextures` at
  the start of the pass instead of inside `render()`, same frame; the ready-model fallback read at the same phase
  as stock. Their diff of my six crop triples: during ≈ after in all six (b→d 10-38k px, d→a ≤ 3k) — a step that
  stays within the triple (the detector's revert can be at +2 / +3, so consistent with a 2-3-frame flip). If cdoff
  clears it they bisect (texture-creator path first, then batch timing vs the corpse / item passes); if zmoff clears
  it, it is the zm build. Next change on their side: fork after `renderPlayers`, join at `renderMovingObjects`, on the
  game's slot-init executor instead of the FrameBatch workers.

| `vp3-lou-cdoff` (05:26, charDrawPrep=false) | `lighting_pops` — **doubled-GPU regime (gpu_ms 29.8, 28 fps), invalid** (black 67 %, 208 jumps, solid 2.86, the regime signature) | Tells nothing about charDrawPrep; re-queued as `vp3-lou-cdoff2`. The regime has now taken 10 of 37 Louisville runs today. |

- **zm (05:35) on the `vp3-lou` roof flicker:** their dev rigs on the snap3 install (`lou-zm-check2`, 05:22): lighting
  batch 653 batches / 140,282 chunk levels, 425,436 squares re-read on the game thread after the batches,
  mismatches=0 (vis bits, colour, dark multipliers, light level, 8 vertex lights equal what the workers stored);
  action eval 860,240 contexts re-evaluated serially at apply time, mismatches=0; no exceptions, player alive — "so
  the parallel reads are not torn." Design note: the drain runs where stock runs it (before `stateBeginUpdate`, the
  lighting thread parked in WaitingForMain), every per-level side effect (`invalidateLevel`, `markStrong`, puddle
  flag) identical; the only order change is `checkRoomSeen` / `dealWithSquareSeen` after the batch instead of
  interleaved, same frame, before the render; an effect through `room.explored` / roof-cutaway timing cannot be
  ruled out by reading. They queued the requested control themselves: `lou-zm-lroff-rec` (all keys on,
  `lightingReadParallel=false`, recorded, overlay panels off) — to be judged against `lou-rec-stock` when it lands;
  with `vp3-lou-zmoff` that isolates the key. (Their `lou-zm-check2` sat in the doubled regime, gpu_ms 31; `vp3-lou`
  was healthy, so the roof flicker is not the regime.)

| `vp3-lou-zmoff` (05:28, the eight zm keys off incl. `lightingReadParallel` / `actionEvalParallel`, charDrawPrep on; healthy: gpu_ms 13.6, 45 fps) | `parity=flicker` — transients 152 px/frame (world 153.1k), black 41.8 % (jumps 5), pops 3, solid 0.10; **"upper-middle centre-left" = 100.4k** (vp3-lou 81k, stock 8.7k), rest in band | **Clears the zm keys**: the roof flicker is present without any of them. Remaining suspects among what the 05:18 install has and `vp1-lou` (04:51, clean) did not: `charDrawPrep` (control `vp3-lou-cdoff2`, 0268) and the zoom session's newer `zoomRetain` / `zoomPlaceholder` builds (`zoomPlaceholder` = a placeholder texture for a chunk level whose texture is not ready — chunk-sized roof patches that resolve a frame later fit it; control `vp3-lou-zoomoff`, 0277, both keys off). |

- **zm (05:38):** the deferred hooks are not a frame late — `LightingBatch.applyAll` runs right after the batch join
  inside the same `pzoptFlushPendingLighting` call, before `stateBeginUpdate` and the frame's render, and the
  recording condition (first-time seen / room unexplored) is exactly when stock's `checkRoomSeen` /
  `dealWithSquareSeen` act. Superseded by the control above: the zm keys are cleared for the roof flicker.

| `lou-zm-lroff-rec` (zm session's own control, 05:30, `lightingReadParallel=false`, recorded) | not judged: **doubled-GPU regime** (gpu_ms 30.3, 29 fps) | Invalid for parity; superseded anyway by `vp3-lou-zmoff`, which cleared the zm keys. 11 of 40 Louisville runs today in the regime. |

| `vp3-lou-cdoff2` (05:31, charDrawPrep=false, zm keys on; healthy: gpu_ms 13.5, 50 fps; 05:18 install, 43 overrides) | `parity=flicker` — transients 140 px/frame (world 143.2k), black 40.7 %, pops 3, solid 0.10; **"upper-middle centre-left" = 95.0k** | **Clears `charDrawPrep`** as well. Neither the zm keys nor the cd key: the roof flicker is in the 05:18 install with either of them off. Left: the zoom session's `zoomRetain` / `zoomPlaceholder` builds (`build-zoom-ease` 04:51, `build-zoom-staged2` ~05:09 — after the clean `vp1-lou` install of 04:48; a placeholder drawn for a chunk-level texture until its staged bake lands would give exactly chunk-sized roof patches that resolve within frames) and, less likely, the los keys (on and clean in `vp1-lou`). `vp4-pair` (0281): all-on + `zoomRetain=false zoomPlaceholder=false` back to back on one install. |

- **cd (05:44) on the next install (`cd2-on` ships `build-chardraw2` 0274 + `build-zm-shadow2` 0275):** their pre-pass
  now forks right after `renderPlayers` on the game's slot-init executor and joins at `renderMovingObjects` (was a
  FrameBatch at `renderMovingObjects`), same data, earlier in the frame, nothing visible intended; the zm session's two
  pure reorders in `IsoGameCharacter` (`renderShadow` tests the culled early-return before computing the shadow params
  — culled zombies never drew a shadow anyway; `render` tests `getRagdollController() != null` before `canRagdoll()`);
  plus whatever the zoom / los sessions changed since 05:18. The `char draw:` line now prints `join waits=` / `join
  ms=` instead of `batch ms=`; `leftovers=` must still read 0.

| `vp3-torch` (05:38) | invalid: the game exited 17 s after launch with an empty console — the same happened to every launch between 05:36 and 05:39 (`ups-bicubic-p-1` 20 s, `cd2-on` 19 s, `cd2-off` 17 s, `losvid-after` 17 s); the 05:39 launch booted normally again. A dead-launch window (a lingering instance and Steam refusing the next launch is the usual shape), not a build fault. | Re-queued as `vp4-torch`. |

### Iteration 4 — the zm session's final install (`lou-zm-final-off --install opt`, 05:4x: `build-zm-cull-test3`, 292 classes), jobs `vp4-pair` (0281) + `vp4-torch`

- **zm (05:41):** the install adds `zombieCullSortFast` (`IsoWorld` override, same order as stock's stable sort, JVM
  test), `lightingStrongFrameMs=20` (adaptive strong re-bake budget: halves when the previous game-thread frame step
  exceeded 20 ms — aimed at the doubled-GPU regime; `bake_counters=` incl. `strongBudgetCuts=` in `pzopt-bench.out`),
  the `renderShadow` / `render` reorders, and summary lines. **Declared pixel effect:** held strong re-bakes show
  slightly stale light for a few frames when frames are slow — by design, same as `lightingStrongBudget`'s holds.

**Steam launches dying since 05:36 (05:48):** `lou-zm-final-off` / `-on`, `cd2-on` / `-off`, `vp3-torch`,
`vp4-torch`, `ups-bicubic-p-1`, one `losvid-after` — 8 of 9 Steam launches since 05:36 exit ~17 s after launch with an
empty console (0 overrides, `route_complete=0`). `connection_log.txt`: the client got `RecvMsgClientLoggedOff('Session
Replaced')` at 04:00:02 and logged off; no `[Logged On` since. The game tolerated the logged-off client for 90 min;
since 05:36 `projectzomboid.sh.log` ends in "Steam user is not logged in. Offline mode?" and the JVM exits before the
logger starts. run.sh's `auto` launcher does not catch it (the client process is running). Workaround told to every
session: `--launcher direct`. A client restart (`/tmp/steam-restart.sh`, used by a peer at 03:2x-03:5x) would reconnect
the desktop but may kick the session that replaced it at 04:00 — left to the maintainer. `vp4-pair` (0293) and
`vp4-torch` (0294) resubmitted with the direct launcher.

- **ups (05:50):** resubmitted `ups-bicubic-p-2` with `--launcher direct` (`--install keep`; the build with the
  upscaler keys is the one installed at 05:37, `upscaler=off` by default → the `vp4-*` pairs on that install are its
  "pixel-equivalent with the key off" check); will check `route_complete` before calling anything a pair and announce
  the first fsr1 / ups-off pair on the 120 km/h drive.

- **cd (05:51):** `cd3-on` / `cd3-off` resubmitted with `--launcher direct` and `--install opt` on both ("every session
  installs its own worktree into the game dir, so a keep A/B is not safe any more"); the install `cd3-on` ships = the
  shared checkout as of 05:51: their executor fork + the zm shadow / ragdoll reorders + the zm cull sort + the
  upscaler session's `IsoCamera` / `ModelManager` / shader overrides with their keys at defaults. (The ups overrides
  entered the tree with `ups-build2` at 05:35, after `vp3-lou`'s 05:18 install — not a candidate for that flicker.)

| `vp4-pair` (0293, 05:43 / 05:44, direct launcher): `vp4-lou` + `vp4-lou-zoomoff` | both `lighting_pops` 0.80 — **both in the doubled-GPU regime** (gpu_ms 32 / 30, 25-27 fps; black 64 % / 63 %, 173 / 186 jumps) | Invalid twice over: the install at 05:41:47 was the los session's worktree again (35 overrides, no zm / cd / ups keys — their `losvid-*` runs reinstall `/tmp/pzlos-wt`), not the zm final build. The desktop install now alternates between sessions' worktrees every few minutes; a validation has to install what it validates itself. Resubmitted as `vp4-pair2` with `--install opt` (the shared checkout: zm final + cd + ups keys off + los). 13 of ~45 Louisville runs today in the regime. |

- **ups (05:58):** `ups-bicubic-p-2` had run on the los worktree install (35 overrides, no upscaler class) — from now
  on a build job then the run with `--install opt` (`ups-build3` + `ups-bicubic-p-3`); the build prints
  `[pzopt] upscaler: <mode> at <N> % (<quality>)` at boot when the key is on (nothing with `upscaler=off`, every hook a
  no-op); numbers only from runs whose console has that line; run dirs named when a pair is ready.

**Correction (06:02): the launches die because the shared build dies at JVM start, not because of Steam.** With
`--launcher direct` the run dir's `launcher-stdout.txt` ends in `Exception in thread "main" ` with no trace and the
process is gone within seconds (`cd3-on` 05:49, `lou-zm-final-off3` 05:51); the Steam launches died the same way with
an empty console. Every launch of the los worktree build (35 overrides) succeeds with both launchers (`losvid-after`
0287 05:39, `vp4-pair` 05:43, `ups-bicubic-p-2` 05:48). Last good shared-build launch: `lou-zm-check2` 05:22; builds
between it and the first death (`ups-bicubic-p-1`, 05:36): `build-chardraw2` + `build-zm-shadow2` (05:29), `ups-build2`
(05:35 — `IsoCamera` / `ModelManager` / `MultiTextureFBO2` / `RenderScale` hooks on the render seam; the first dead run
followed it directly), `build-zm-cull-test3` (05:37). Headless `Class.forName` of every new override with the game's
JRE 25 succeeds (`/tmp/pzopt-parity-watch/cl/LoadCheck`), so it is a runtime init on the display / GL path in `main`.
The Steam client's logged-off state (04:00) is real but was tolerated for 90 min and is not the cause. Told all three
sessions, the upscaling session asked to bisect first; until the shared build launches again no validation counts.

- **cd (06:05):** agrees it is the shared build; queued `diag-exc` (0309: `--install opt --launcher direct`, verify
  mode, `--env JAVA_TOOL_OPTIONS=-Xlog:exceptions=info:file=/tmp/pzopt-exc.log`) so the JVM logs the uncaught
  exception of `main` with its throwing method; will post the culprit class. Symptom explained: the prefix goes to
  the original stderr, the trace to the already-redirected `System.err` (the 0-byte console.txt), and the JVM exits
  before that stream flushes.

**Culprit of the launch deaths (06:10, cd session's `diag-exc` + `/tmp/pzopt-exc.log`):** the zm session's new
`src/overrides/zombie/iso/IsoWorld.java` (05:34, `zombieCullSortFast`) used the loud `pzopt.Overrides.onClassLoaded`
in its static marker; `IsoWorld` gets initialised from `DebugLog.formatLogStringForConsole` while the game logger
formats its first line, the marker's `Log.info` re-enters `DebugLogStream.println` on a stream not built yet → NPE
in `<clinit>` → `ExceptionInInitializerError` → "Could not initialize class zombie.iso.IsoWorld" → `main` dies; the
trace went to the redirected stderr that never flushes (the 0-byte console). Fix = `onClassLoadedQuiet` (what
`IsoCamera` / `ModelManager` use); already in the tree at 05:59:46 with a comment naming the 05:36-06:00 deaths. The
upscaling session's files were not involved — my "first suspect" was wrong; told them. Every shared-build install
from the next build on should launch again.

- **zm (06:12):** owns it — "the dead shared-build launches since 05:36 were mine"; fixed with the deferred marker,
  `build-zm-isoworld-fix` queued; the next `--install opt` from `build/classes` after it is the one to validate; will
  re-queue their final pair with `--install opt --launcher direct` and say so. **ups (06:11):** had queued `ups-diag`
  (0314, direct JVM launch with `-Xlog:exceptions=info -verbose:class`) before the pin; `ups-bicubic-p-3` (0307) behind
  it reinstalls `ups-build3`.

**Release alert (06:18):** the zoom session (`ff5642f8` / pz-optimization-ab, not on the watch list) committed its pass
(a69993f "zoom: keep chunk textures across zoom changes, plan their re-bakes, Bezier zoom motion") and queued the
Workshop upload of release 3441a1c (job 0317) — the release ships only committed work, i.e. the zoom pass on top of
the earlier commits, none of the zm / cd / los / ups working trees. Since the roof-flicker attribution has cleared the
zm and cd keys and points at the zoom builds, the session was told before the upload, with the crops and the
pending zoom-off control (`vp4-pair2`); their answer goes here.

- **zm (06:20):** `build-zm-isoworld-fix` built (298 classes); re-queued `lou-zm-final-off4` (`--install opt
  --launcher direct`, installs it) then `lou-zm-final-on4`, plus `spin-zm-off` / `spin-zm-on` (Rosewood spin
  regression pair). The install `lou-zm-final-off4` stamps is the one to validate → `vp4-pair2` / `vp4-torch` install
  the same `build/classes` themselves, so they validate that build regardless of order.

- **zoom session (06:27) on the roof flicker — attributed, transitive (a defect of the retention), fixed:** "without a
  zoom change neither `zoomPlaceholder` nor the staged zoom bakes can fire (both act only on levels marked during a
  zoom flood), but the retention itself does change a walk-only route. A chunk level that leaves and re-enters the
  screen by camera motion now has a kept texture, so stock's `invalidateLevel(1024)` on re-entry became a *holdable*
  redraw: the existing `rebakeBudget` path (4/frame, up to 3 frames) drew the stale kept texture for 1-3 frames before
  the re-bake landed — before zoomRetain that level was a fresh create baked at once. That is your chunk-sized lit
  patches reverting within 1-3 frames on the roof up-left (a tall building's upper level pair crossing the screen
  bounds as the camera moves). Fixed in the 06:08 build (`build-zoom-return-fix`): a camera-motion return is allowed
  at once and bakes that frame, never held; only levels returning during a zoom flood take the plan." Release 3441a1c
  (uploaded 06:05) shipped with the hold; a follow-up release is cut if the re-run is back in band. → `vp4-pair2`
  (0304, `--install opt`) is that re-run.

- **cd (06:32):** `cd4-on` (06:02, the first launching shared build) healthy: 57.5 fps, gpu_ms 12-13, `leftovers=0`,
  characters draw 7.2 % of which 3.5 % is the join wait (fork too late, after the players). Next install (`cd5-on`,
  `--install opt`, `build-chardraw3` 06:10): fork moved earlier (object walk in one executor task at the top of the
  cell render, visibility test + texture-creator check on the game thread right before the chunk bakes, draw data
  built during the bakes) and a second key **`zombieAtlasFast`** (default true): a culled zombie drawn as an atlas
  sprite (~1,100 of the 1,600 on-screen objects) goes through a flat copy of `IsoZombie.render` →
  `IsoGameCharacter.render` for exactly that case (same tests in the same order, same writes: alpha rule, depth mask,
  light scratch, facing, last-rendered statics, texture check, sprite scale, the same `renderTextureInsteadOfModel`
  call, item updaters, debug renders); anything else falls back to the stock chain. No pixel meant to change; A/B
  `--prop zombieAtlasFast=false`. Their cd5 trio (on / noatlas / off) runs after `cd5-on`. → watched keys for cd:
  `charDrawPrep`, `zombieAtlasFast`.

| `vp4-torch` (06:13, direct launcher, 59 fps at the cap; install = the zm final build with the IsoWorld fix, 50 overrides: `lightingStrongFrameMs=20`, `zombieCullSortFast`, `actionEvalParallel`, `lightingReadParallel`, `charDrawPrep`, `zoomRetain`, los keys, `upscaler=off`) vs `bl-torch-stock` | **`parity=parity` conf 0.81, maintained 0.78, look 0.64** — transients 836 px/frame (stock 881), black 87.33 % (87.35), jumps 19 (20), luma pops 0 (0), hard jumps 4542/9142 (4299/8596), solid 0.44 (0.46); the same four busiest cells in the same order | **Pass** on the night-torch route for the whole combined install (zm final + cd `charDrawPrep` + los + zoom + ups off). |

### Upscaling pass — first pairs (06:45, 120 km/h drive, same build, `--install opt`, overlay panels off, route complete)

- control `ups-off-p-3-20260922-061444` (509.5 fps, p99 7.7 ms); `ups-bicubic-p-3-20260922-061154` (bicubic 50 %:
  676 fps, p99 6.2 ms); `ups-fsr1-p-3-20260922-061720` (FSR1 50 %: 619 fps, p99 6.4 ms). Declared by-design
  differences: the world softer at 50 % (bicubic more than fsr1), 1-px details thinner or gone; UI / dashboard /
  cursor native in all three; the black leading-edge chunks at 120 km/h are in the control too. Next: `ups-dlss-p-1`
  (temporal — ghosting on the car / zombies possible until per-object motion vectors are in). Judge job
  `ups-judge-p3` queued (parity-judge + colorshift of both "on" runs against the control).

### Iteration 5 — install of 06:18:41 (`vp4-pair2`'s own `--install opt`: zm `build-zm-batchfix` 06:14 + cd `build-chardraw3` 06:10 (`zombieAtlasFast`, earlier fork) + zoom `build-zoom-return-fix` 06:08 + ups off; 50 overrides), job 0304

| Run | Judge | Reading |
|---|---|---|
| `vp4-lou` (06:18, 37 fps, gpu_ms 22.6) vs `lou-rec-stock` | `lighting_pops` 0.64 — transients 382 px/frame, **black 82.5 %** (jumps 35), solid 1.34 | **Fails on lighting, not the regime:** a frame at 55 s is a mostly black screen with a handful of chunk levels drawn. `bake_counters`: bakes=13933 **deferred=651421 strongMarks=337693** lightingRebakesHeld=84635 strongPastBudget=129142 rebakesHeld=38177 strongBudgetCuts=638 — vs the healthy `lou-zm-final-off4` (06:05: strongMarks=15073, deferred=29232) and `cd4-on` (06:02, `lightingStrongFrameMs=20` already on: strongMarks=13996, deferred=39420). Strong marks 22x, deferrals 17-22x. |
| `vp4-lou-zoomoff` (06:20, `zoomRetain=false zoomPlaceholder=false`, 30 fps, gpu_ms 28) | `lighting_pops` 0.67 — black 64.6 % (jumps 97), solid 1.46 | Worse without the zoom keys (strongMarks=352017, deferred=483851) → **not the zoom pass**; the roof cell reads 37.5k / 16.9k but neither run is a valid parity read. The flood is in what the 06:18 install added over the healthy 06:02 / 06:05 ones: zm `batchfix` or cd `chardraw3` (`zombieAtlasFast`'s flat path writes "light scratch"). Both sessions asked whether intentional or transitive; the cd5 trio's noatlas / off runs are the cheapest discriminator (their `bake_counters`). |

- **zm (06:52) on the strong-mark flood:** transitive on their side — `build-zm-batchfix` only changed
  `pzopt.FrameBatch`'s bookkeeping (one immutable batch object per run instead of loose static fields; a late worker
  could read a null runner against the next batch's cursor, drop a task and switch AnimBatch off — `spin-zm-on` had
  "anim batch FAILED", `spin-zm-on2` on the fix is clean: 472 fps, 0 exceptions); nothing in it marks squares. Their
  reading of the 22x strong marks: per-square light deltas churning pass to pass, and the one new thing touching
  square lighting off the game thread is the cd session's earlier `charDrawPrep` fork on the stock slot-init
  executor — `ModelInstance.updateLights` reads the character's squares through `square.lighting[p].lightInfo()`,
  the lazy `JNILighting.update()` (`getSquareDirty` / `getSquareLighting` + field writes + `pzoptLightChanged` /
  `markStrong` + `invalidateLevel`); if that fork overlaps `LightingJNI.update()` (the pass landing) or the lighting
  drain, squares get read under two passes and their `was*` deltas explode into strong marks every frame, then
  `lightingStrongFrameMs` halves the budget (`strongBudgetCuts=638`) and creation bakes starve black. Their own
  batch is joined inside `pzoptFlushPendingLighting` before `stateBeginUpdate`. Discriminators: `charDrawPrep=false`
  on that install; `lightingStrongFrameMs=0` (black gone but strong marks still 22x = the budget only amplified it).
  They are asking the cd session where the fork joins relative to `LightingJNI.update`.

- **cd5-on (06:25 install, cd's own run, 64.2 fps, unrecorded):** `bakes=2476 deferred=820844 strongMarks=13297
  strongBudgetCuts=113` — strong marks normal again, deferrals still the flood figure and a quarter of the healthy
  bakes: the shape of a run that never bakes most chunk levels (and the highest fps on the route today). The cd
  session was asked to look at the picture before reading the fps. `vp5-trio` (0336, `--install opt`, direct: all-on
  / `charDrawPrep=false zombieAtlasFast=false` / `lightingStrongFrameMs=0`, recorded and judged on one install) queued
  as the discriminator.

- **zm (07:00):** confirms the churn on their own unrecorded `lou-zm-final-on4` (06:18 install, healthy GPU 11.9 ms,
  57.3 fps): deferred=798,973 vs 39,420 (`cd4-on`, 06:02 install, same keys) and 29,232 (their off4), strongMarks 52k
  vs 14k, bakes 5060 vs 8816 — "the install is sick even when the screen still looks fine; the recording run just
  tipped it into black". Their split on this install, zm keys on: `lou-zm-iso-cdoff` (`charDrawPrep=false
  zombieAtlasFast=false`) and `lou-zm-iso-zoomoff` (`zoomRetain=false zoomPlaceholder=false`); whichever brings
  deferred / strongMarks back to ~40k / 14k names the pass. (My `vp4-lou-zoomoff` already answered the zoom half:
  352k strong marks with the zoom keys off.)

- **cd (07:08) — owns the deferral / strong-mark flood, transitive:** "the executor task ran
  `ModelInstance.updateLights` per batched zombie, whose square light reads are the lazy `JNILighting.update`
  (field writes + LightDirt / `invalidateLevel` hooks); with the fork moved before the chunk bakes those off-thread
  reads overlapped the bakes' own lazy reads and the lighting deltas doubled into the strong-mark / deferral flood
  (`cd5-on`: deferred 442k, bakes a quarter, the 64 fps is void; `cd4-on` with the fork after the players had
  deferred 14k and was healthy at 57 fps). Fixed: `updateLights` runs on the game thread in `CharDraw.start` before
  the fork (`build-chardraw4`, job 0342), the tasks only do `initModel` + `init`, which read no square lighting."
  `cd5-*` void; the 06:18 / 06:25 installs broken by their fork; `cd6-on` / `-noatlas` / `-off` follow the fix. The
  zm session's `lou-zm-iso-cdoff` (charDrawPrep=false, still deferred 759k) shows the key did not gate the fork
  in that build. Corroboration across routes: on installs after `build-chardraw3` (06:10) even the 120 km/h drive
  runs had deferred 540k-1.0M (`ups-*-p-3`) and the Rosewood spin 1.3M (`spin-zm-on2`) vs 122k on the 06:02 install
  (`spin-zm-off`). → `vp5-trio` (0336, `--install opt`) validates the chardraw4 build when it runs.

- **zoom session (07:14) — correction on its fix:** the 06:08 `build-zoom-return-fix` is *not* the fix — their own parity run
  on it (`vp-zoomfix-lou`, 06:26) was black 89 %: a level marked "returned" that is then occlusion-culled or freed off
  screen never reaches the bake block, its pending bit never clears, and the plan treated any pending bit as an
  ongoing zoom flood, budgeting every first-sight chunk level downtown (12 a frame against hundreds arriving) —
  "very likely in the 05:18 build you measured too". Fixed: the bits clear on the occlusion and off-screen paths and a
  stale bit can no longer start a flood; `build-zoom-leak-fix` (0341) queued, `vp-zoomfix2-lou` (`--install opt`,
  parity against `lou-rec-stock`) follows. So two independent floods hit the 06:18-06:25 installs (cd's off-thread
  `updateLights`, zoom's stale pending bits) — consistent with `vp4-lou-zoomoff` (zoom keys off) still flooding.
- **Queue blocked (07:12-):** job 0338 `ups-dlss-p-2` (upscaling session) has its game hung at quit for 11+ min
  (futex wait after "run finished, quitting to desktop"); run.sh waits for exit, everything behind it waits. The
  session was asked to cancel its own job (a hung Vulkan / NGX teardown on exit is the likely shape); the watch does
  not kill another session's game.
- **zm (07:12): pass complete** — final A/B on the 06:00 build: Louisville 46.4 → 57.5 fps keys off → on, spin 336 →
  472; the pending `build-zm-final` only adds `bstoplunging` to the pure-callback allowlist and re-runs the unit tests.

- **cd (07:20) — correction:** the deferral flood is not `charDrawPrep`: `lou-zm-iso-cdoff` (06:29, both cd keys off on
  the 06:25 install) logged `char draw: frames=0` (none of their code ran; everything is behind `CharDraw.enabled()`)
  and still read deferred 478k / 1319 bakes in period; `cd5-on` 442k / 1202; `cd4-on` (06:03 install) 14k / 4118.
  Their chardraw4 fix (`updateLights` on the game thread) stands as a correctness fix regardless. Reconciled reading:
  the flood on the 06:18-06:25 installs is the **zoom session's stale pending bits** (their own correction at 07:14:
  a "returned" level that is occlusion-culled or freed keeps its bit and the plan budgets every first-sight level
  downtown to 12 a frame) — which explains why every route flooded (drive 540k-1.0M, spin 1.3M, Louisville 480-820k)
  and why neither the cd-keys-off nor the zm-keys-off run recovered; `vp4-lou-zoomoff` still flooding is the one
  loose end (`zoomRetain=false` may not clear bits already set at install / world-ready, or the plan reads the bits
  regardless of the key). `build-zoom-leak-fix` (0341) is the candidate fix; `vp5-trio` reads the counters on it.

**Decomposition of the `bake_counters` (07:30) — settles the flood.** `deferred` (= `pzoptDeferredTotal`) counts every
hold; the two named sub-counters `lightingRebakesHeld` + `rebakesHeld` add up to it exactly in every healthy run
(cd4-on 12776 + 26644 = 39420; final-off4 8133 + 21099 = 29232) and in the one run with `zoomRetain=false`
(vp4-lou-zoomoff 188986 + 294865 = 483851). On the 06:18-06:25 installs with the key on, the remainder is the
**zoom plan's holds**: vp4-lou 651421 − 122812 = 529k; lou-zm-final-on4 799k − 35k = 764k; cd5-on 821k − 4k =
817k; lou-zm-iso-cdoff 759k − 3k = 756k. So: (1) the deferral flood = the zoom session's stale-pending-bit leak,
present with the key on regardless of the cd / zm keys and absent with the key off — their diagnosis and the
statement "the key gates everything" both confirmed by arithmetic; `build-zoom-leak-fix` (0341) is the fix to
validate. (2) A second, separate effect in the two *recorded* (slow: 30-37 fps) runs on the 06:18 install: strong
marks 25x (338k / 352k), `lightingRebakesHeld` 85-189k, `rebakesHeld` 38-295k, `strongBudgetCuts` 638 / 853 — vs
3.7x marks at 57 fps unrecorded on the same install and normal (13k) on the 06:25 install at 59-64 fps. That scales
with slow frames and with the cuts, i.e. it is the adaptive `lightingStrongFrameMs=20` budget halving under slow
frames and the held strong squares being re-marked every pass — a feedback amplifier of the very regime it was meant
to kill, not a lighting read race (the zm session's off-thread-read theory is not needed for it). `vp5-trio`'s
`lightingStrongFrameMs=0` leg tests exactly that. The cd session's `charDrawPrep` is cleared of both.

- **zm (07:36):** agreed on both counts and acted — `lightingStrongFrameMs` now defaults to 0 (kept as an A/B key; a
  spike-relative rule is the version worth trying later), documented in Config / override-edits / plan §6 /
  CLAUDE.md with the zoom leak credited for the deferral flood; `build-zm-final2` queued (also `bstoplunging` on the
  pure-callback allowlist + unit tests); the first `--install opt` after it is the install to validate — with the key
  at 0, `vp5-trio`'s `lightingStrongFrameMs=0` leg is the all-on configuration.

**Upscaling pairs judged (07:45, job 0335):** the 120 km/h drive at 5K is a high-motion route where the A-B-A
detector fires on scenery streaming past (control 12,109 px/frame, spread over the same four road cells in every
run), so the reading is relative. `ups-bicubic-p-3` vs control: transients 0.70x (a 50 % render loses the 1-px
detail that flickers in the control — the softening, by design), black 25.4 % vs 22.2 % (jumps 63 vs 67), pops 4 vs 3,
solid 0.54 vs 0.61, `colorshift=none` (rgb 0.99, sat 1.007, hue L1 0.02); Jev `static_scene_difference` 0.41 /
parity 0.39 — the expected steady difference, no new artefact class. `ups-fsr1-p-3`: transients 0.95x, black 20.5 %
(jumps 39), pops 5, solid 0.50, `colorshift=none` (hue L1 0.09); Jev `parity` 0.63. **Both pass** the upscaling
criteria (no flicker / ghosting / black tiles / HUD drift beyond the control; HUD corner transients 1.77M / 2.32M vs
2.23M). DLSS (`ups-dlss-p-2`) hung at quit and was cancelled; not judged.

- **zoom session (07:52), leak-fix build on the Louisville walk (`vp-zoomfix2-lou`, 06:53, `--install opt`, 62 fps,
  gpu_ms 13, overlay panels off):** black 40 % (stock 43 %), `deferred 11,776 = lightingRebakesHeld 3,645 +
  rebakesHeld 8,131` exactly (their plan held nothing: returned=76 rebakes=0 creations=8 urgent=0) — **the deferral
  flood and the black levels are gone.** The judge still reads flicker: world transients 130.8k / 20 s (stock 52.5k),
  and it is the same cell again: "upper-middle centre-left" = **82.1k** (stock 8.7k; `vp3-lou` 81k, `vp3-lou-zmoff`
  100k, `vp3-lou-cdoff2` 95k). Their twin `vp-zoomfix2-lou-off` (`zoomRetain=false zoomEaseMs=0`, same install) is
  queued; `zoomfix2-crops` (0357) dumps what blinks this time. The 06:49 install carries the zm-final2 default
  `lightingStrongFrameMs=0`, cd's chardraw4 and the ups keys off.

### Iteration 6 — install of 06:57 (`vp5-trio`'s own `--install opt`: zoom leak-fix + cd chardraw4 + zm-final2 with `lightingStrongFrameMs=0` default + ups off), job 0336

| Run | Judge | Reading |
|---|---|---|
| `vp5-lou` (all on) / `vp5-lou-cdoff` (`charDrawPrep=false zombieAtlasFast=false`) / `vp5-lou-strong20` (`lightingStrongFrameMs=20`) | all three `lighting_pops` 0.80-0.84 | **All three in the doubled-GPU regime** (gpu_ms 30.6 / 31.7 / 28.4, 28-30 fps): invalid for parity. Counters: bakes 25-27k, deferred 424-473k, **strongMarks 350k in all three with `strongBudgetCuts=0` in the two `FrameMs=0` legs** → the 25x strong-mark churn is intrinsic to the regime, not the adaptive budget (my 07:30 reading was wrong on that point; the zm session's default flip to 0 stands as harmless). The deferred remainder (424k − held) is *not* the zoom plan this time (leak-fix in) — it is the regime's held re-bakes. `strong20` had 3x fewer world transients (180k vs 530-580k): the adaptive budget does soften the picture inside the regime without curing it. |

**Regime finding for the watch's own method (08:05):** recorded Louisville runs fall into the regime far more often
than unrecorded ones — 11 of 16 recorded (`vp0`, `vp1-keysoff`, `vp2b`, `vp3-cdoff`, `vp4` ×2, `vp4-pair2` ×2,
`vp5` ×3, `lou-zm-lroff-rec`) vs ~7 of 30 unrecorded today. The recorder's NVENC + capture load (gsr 7 % SM / 22 %
NVENC, 130-150 W) is enough to tip this scene over its ~27-30 fps tipping point at the start, after which the
strong re-bake loop holds it there (350k strong marks, gpu_ms ~30). The mechanism matches CLAUDE.md's 09-22 note
(`darkMulti` fade marking exterior levels strong): the strong decision is a per-frame delta against
`lightingStrongDelta=6`, so a slow frame produces a larger delta → more strong marks → more re-bakes → slower
frame; a time-normalised delta (per 16 ms) or a comparison against the level's last *baked* value would remove
the bistability — reported to the zm session (owner of the lighting keys today) as a suggestion. Consequence: the
recorded Louisville route is a coin-flip parity route; the zoom session's healthy `vp-zoomfix2-lou` (06:53, all keys
on, 62 fps) stands as the all-on recorded validation of the 06:49 install, with the roof cell as the only open item.

- `cd6-on` (07:03, cd's own, unrecorded, chardraw4 build): gpu_ms 27, 31 fps, strongMarks 352k, deferred 471k =
  lightingRebakesHeld 122k + rebakesHeld 349k — the regime again, now on an unrecorded run too (the 06:57 install
  went 4 of 5 into it, the recorder is a push, not the cause). `cd6-noatlas` / `cd6-off` died on run.sh's
  argument parsing ("unknown arg: --prop zombieAtlasFast=false" — the props passed as one string).

**Watch status at 08:10.** Open parity item: the roof-cell flicker (81-100k px in "upper-middle centre-left" on
every healthy Louisville run since the 05:18 install, 1-3-frame chunk-sized lit / dark flips on the roof up-left
of the player); cleared for the zm keys (`vp3-lou-zmoff`), the cd keys (`vp3-lou-cdoff2`) and the los keys
(`vp1-lou`, 04:48 install, clean with them on); left by elimination to the zoom pass, whose own `zoomRetain=false`
twin on the leak-fix install decides. Blocking method issue: the Louisville route's strong re-bake regime (frame-
time-dependent strong delta) makes ~half the runs invalid; no more recorded triples there, single runs only when a
session announces a final install, gpu_ms read first.

- **zm (08:15), mechanism of the regime (recorded in `docs/plan-zombie-multithread.md` §6):** the strong delta is
  already accumulated since the level's last bake (LightDirt), so a time-normalised delta only changes who gets
  marked; the feedback is that every bake budget is **per frame** (`bakeBudget` 8, `rebakeBudget` 4,
  `lightingRebakeBudget` 8, `lightingStrongBudget` 8, `zoomRebakeBudget` 12) while pending work grows with frame time —
  at ~30 fps all budgets fill every frame, ~30 tall-chunk bakes at 5120x2160 ≈ the +15 ms gpu_ms, which slows the
  frame further: two stable points. Cure: a bake budget per unit of wall time (one shared pool refilled at N bakes/s)
  or in GPU ms (`gpuSections` measures them), so the bake GPU share is constant instead of proportional to frame
  time. Left as the documented lead (not zombie work). `lightingStrongFrameMs=0` stays the default.

**`vp-zoomfix2-lou` crops (08:20, job 0357):** the same roof, the same phenomenon as `vp3-lou` — the lit stripe on
the big roof up-left of the player changes its boundary between consecutive frames in chunk-sized pieces while the
player's vision cone sweeps it (turn=90°/s), reverting within 1-3 frames; bursts at 53.8 / 54.3 / 55.5 / 56.9 s, i.e.
the same seconds 11-18 of the window in every run (the route passes that roof then). Reading: the cone edge
crossing a multi-chunk roof makes each chunk level re-bake in a different frame, and pzopt's held / budgeted
re-bakes (`lightingRebakeMs` / `rebakeBudget` / `lightingRebakeBudget`, adopted keys from 09-20) draw the old and the
new texture side by side for 1-3 frames — the blocky-lights class, not necessarily anything from today. Check
queued (`roofcell-history`, 0361): the per-second profile of that cell in the 03:01 build's recordings
(`lou-rec-fix` / `lou-rec-opt`, known-good band 12.3k) and in `vp1-lou` (04:48 install, 30.5k with the overlay on)
— a burst at seconds 11-15 there means the flicker predates every pass of today and the "elimination" to the zoom
pass was wrong; a flat profile means it entered between 04:48 and 05:18.

- **ups (08:25):** `ups-dlss-p-3-20260922-070629` = the first DLSS run with `route_complete=1` and a clean quit (the
  hang was the shim's worker thread torn down under a waiter at exit; fixed): 231.9 fps, p99 12.0 ms — the DLSS 4
  transformer costs ~2.5 ms a frame at 5120x2160 output (GPU 100 %, 346 W vs 282 W for fsr1), slower than fsr1 (619)
  at 4K uncapped but looks the best (thin power lines come back at 50 %). Camera motion vectors only in that run →
  the car / zombies are the ghosting candidates; queued behind `ups-build6`: `ups-dlss-p-4` (per-object motion
  vectors for characters and vehicles) and `ups-dlss-f-1` (the cheaper CNN preset F); a bench-route pair
  (S:450 turn=90 uncapped) once p-4 is in. Judge job `ups-judge-dlss3` queued (p-3 vs `ups-off-p-3`, plus crops of
  the road cell where the car is).

**Roof-cell history (08:30, job 0361) — the flicker predates today's passes; the zoom pass is at most a multiplier.**
Per-second transient px in "upper-middle centre-left", seconds 11-18 of the window (the roof pass): stock spread
~0.2-1.1k/s with no burst; `lou-rec-fix` (03:01 build, 29 fps) **burst 2.7k / 2.6k / 2.1k / 2.4k ≈ 9.8k**;
`lou-rec-opt` (02:58, 29 fps) ≈ 7.8k; `vp1-lou` (04:48 install, 50 fps, overlay on) 7.7k / 6.5k / 3.9k / 5.0k / 3.4k
≈ 27k; `vp3-lou-zmoff` (05:18 install, 45 fps) 15.6k / 15.8k / 14.7k / 9.2k / 9.3k / 6.5k / 11.3k / 4.9k ≈ 87k;
`vp-zoomfix2-lou` (06:49 install, 62 fps) ≈ 80k. So the chunk-sized lit / dark flips of the roof under the sweeping
vision cone are pzopt's held / budgeted re-bake behaviour (adopted 09-20: `lightingRebakeMs`, `rebakeBudget`,
`lightingRebakeBudget`; "faint chunk checkerboard while a flash ramps"), present in the 03:01 build at ~10k, 3x by
04:48, 8-9x from 05:18 on. Part of the growth is fps (29 → 45-62 fps means every resampled frame is distinct), the
rest is what entered between 04:48 and 05:18 — the zoom session's `zoomRetain=false` twin on the leak-fix install
tells whether the retention multiplies it (a kept texture drawn on camera re-entry with stale lighting, re-baked a
frame later, is the same class). My 07:xx "elimination to the zoom pass" overstated it: the class is old; the
magnitude is the open question.

- **zoom session (08:35):** their `vp-zoomfix2-lou-off` twin fell into the regime (27.8 fps, black 62 %, 126 jumps —
  invalid). They moved the discriminator to a clean worktree build (`/tmp/pzopt-fix` = master 7908f0f + only the
  zoomRetain return / leak fix, tests pass), `--install /tmp/pzopt-fix`: `vp-clean-on` (keys on) and `vp-clean-off`
  (`zoomRetain=false zoomEaseMs=0`), both judged against `lou-rec-stock`. ~80k vs ~9k in the roof cell = retention;
  ~9k on both = the growth belongs to someone's working tree. (Note: master 7908f0f itself is the 03:01-era state
  plus the committed zoom pass, so `vp-clean-off` ≈ the `lou-rec-fix` band of 9.8k.)

**DLSS p-3 judged (08:45, job 0366)** vs `ups-off-p-3`: transients 0.72x the control (the same softening/detail
effect as bicubic: 8745 vs 12109 px/frame, the same four road cells), black 25.8 % vs 22.2 % (jumps 57 vs 67), luma
pops 5 vs 3, hard jumps 37705/55015 vs 42856/59287, solid 0.64 vs 0.61, `colorshift=none` (rgb 0.99, sat 1.008,
hue L1 0.014). Jev read `black_tiles` 0.80 on the +3.6-point black share, but the per-second black profile shows it
is the window edge, not tiles: 16-25 % in every second for all three runs (control 18-27, DLSS 16-25, bicubic 16-29)
and the last second of the DLSS / bicubic windows is the quit-to-black (92 % / 74 %) that the control's window
happened to cut before; inside the route the DLSS run's black share tracks the control second by second. Crops of
the car's road cell (`transient-crops/`, bursts ≤ 620 px/frame ≈ the control's scenery motion) show no trailing
ghost behind the car. **Pass** for the drive route with camera-only motion vectors; the finer bench-route pair
(S:450 turn=90 uncapped) is the read for per-object ghosting once `ups-dlss-p-4` exists.

**DLSS bench-route pair judged (09:00, job 0371; per-object motion vectors, `ups-build6`, same install):**
`ups-bench-dlss-1` (281 fps) vs `ups-bench-off-1` (592 fps): transients 2562 vs 2613 px/frame (0.98x; world 2.38M
vs 2.40M), black 10.5 % vs 10.7 % (jumps 28 vs 25), luma pops 9 vs 7, hard jumps 24051/55418 vs 25368/56957,
solid 0.87 vs 0.88, `colorshift=none` (rgb 0.998, sat 1.01, hue L1 0.006). **Jev `parity` 0.94.** The four cells
around the player track the control second by second (e.g. lower-middle centre-left 303k vs 299k, upper-middle
centre-right 255k vs 299k — the control is busier); the DLSS run's largest per-second burst in any cell is below
the control's in the same second. No trailing-ghost signature on the spinning player or the zombies. **Pass** — the
cleanest pair of the day; the spinning route at ~300-600 fps is the parity route the upscaling pass should keep
using.

- **ups (09:05):** the spinning bench route stays their parity route; drive runs `ups-dlss-p-4-20260922-071632`
  (object motion vectors, 231.8 fps) and `ups-dlss-f-1-20260922-072041` (CNN preset F, 377 fps) exist vs
  `ups-off-p-3`, not judged (the bench pair covers the object-vector question). Next on the bench route:
  `ups-bench-fsr1-p-1` and `ups-bench-dlss-q-1` (quality, 67 %) against the same `ups-bench-off-1` control (the
  build only gained an fsr1 fallback for a dlss / xess that cannot run and a tab entry) — judged when they land.

- **ups (09:30): pass closed on their side.** The shared build since `ups-build8` (07:30) carries all of it, keys off
  by default (`upscaler=off` = every hook a no-op), so the pixel-equivalence pairs on the shared build still apply.
  Verified today: bicubic / fsr1 / dlss (camera and per-object motion vectors) passed the judge; the DLSS jitter-sign
  A/B (`ups-bench-dlss-jneg-1`) confirmed the +1 convention (−1 doubles the car lettering). Bench route, one build:
  off 592 fps, fsr1 50 % 802, dlss 50 % 281, dlss 67 % 301. Drift classes still worth a look if someone turns dlss
  on: a resolution change mid-session (the feature rebuilds; untested) and split screen (falls back to fsr1). The
  FSR1 / DLSS-quality bench judges (`ups-judge-bench3`) are still pending in the queue.

**FSR1 / DLSS-quality bench pairs judged (09:40, job 0385, per-side route windows):** `ups-bench-fsr1-p-1` (window
fixed at 23-43 s; the auto window had slid onto the quit-to-black and produced a false `black_tiles` + hue shift) vs
control: transients 0.85x (2215 vs 2613), black 10.6 % vs 10.7 %, pops 6 vs 7, solid 0.63 vs 0.88, colour none
(hue L1 0.036), Jev parity 0.82. `ups-bench-dlss-q-1` (quality 67 %): transients 1.04x (2711 vs 2613), black 10.6 %
(jumps 32 vs 25), pops 8 vs 7, solid 0.90 vs 0.88, colour none (hue L1 0.006), Jev parity 0.88. **Both pass.**
`parity-judge.py` / `colorshift.py` gained `--window-a` / `--window-b` for a fixed window on one side.

- **zoom session (09:50), clean pair result:** does not read — `vp-clean-on` 16.5 fps (machine 43 % CPU, p90 84 %;
  the game alone at 37 % SM, no other GPU client in the pmon log), black 64.5 %, 88 jumps, 610 px/frame; `vp-clean-off`
  (`zoomRetain=false zoomEaseMs=0`, master 7908f0f with no zoom code active) 27 fps, black 64.8 %, 173 jumps,
  1264 px/frame — both the regime, and the keys-off side worse than keys-on for the third time (shared-install twin
  894 vs 134; here 1264 vs 610). Their conclusion, which the watch shares: **the black-jump regime exists on
  committed master + this route + tonight's machine state independently of the zoom pass** (gpu_ms medians 6.0 on /
  0.5 off, p90 39 / 24). Only clean readings of the leak-fix build: `vp-zoomfix2-lou` (61 fps, black 40 % = stock's
  43 %, deferred = holds exactly, plan counters zero) and the zoom jump rig. They commit the fix as is; a healthy
  clean-worktree pair later would still be welcome for the roof-cell number.


## Summary at 09:55 (superseded by the closing summary below)

| Pass | Keys | Parity verdict |
|---|---|---|
| **los** (`playerLosFast`, `zombieSpotFast`; `playerLosNative` off) — done 05:22 | night-torch `vp1-torch` **pass** (parity 0.87), `vp4-torch` **pass** (0.84) with everything else on; Louisville `vp1-lou` (04:48 install) in band with the keys on | **Parity preserved.** Declared transitive effect: the RNG sequence shift of `zombieSpotFast`. |
| **zm** (`ecsLookupFast`, `actionConditionFast`, `skinTransformsPrecompute`, `skinPalettePrecompute`, `shadowPrep`, `boneIndexCache`, `actionEvalParallel`, `lightingReadParallel`, `zombieCullSortFast`, `lightingStrongFrameMs`=0) — done 07:12 | Louisville `vp1-lou` (eval build) in band; the keys-off control `vp3-lou-zmoff` shows the roof flicker is not theirs; torch `vp4-torch` **pass** with the final build | **Parity preserved.** Two transitive defects found and fixed on the way: `actionEvalParallel` running non-pure callbacks on workers (a ghost-player death, `vp2-lou`), the loud `IsoWorld` class-load marker (every launch dead 05:36-06:00). |
| **cd** (`charDrawPrep`, `zombieAtlasFast`) — in progress | torch `vp4-torch` **pass** with `charDrawPrep` on; Louisville: the keys-off control `vp3-lou-cdoff2` shows the roof flicker is not theirs; `leftovers=0` in every run | **Parity preserved so far.** One transitive defect fixed (off-thread `updateLights` in chardraw3 → chardraw4). Next install to validate when they say so. |
| **ups** (`upscaler` off by default) — done 09:30 | bicubic / fsr1 / dlss 50 % / dlss 67 % on the spinning bench route: parity 0.63-0.94, colour unchanged, no ghosting on the player or zombies | **Parity preserved** (softening by design, off by default). |
| zoom (not on the list; release 3441a1c) | its stale-pending-bit leak was *the* deferral flood of the 06:18-06:25 installs (fixed, committed); the roof-cell flicker is a pre-existing held-re-bake artefact (03:01 build ≈ 10k px) whose 8x growth tonight is unattributed | open item for the maintainer, not a drift of the watched passes |

Method findings recorded above: the overlay panels count as world flicker (hide them on judged runs); the
recorded Louisville route tips into the per-frame-budget re-bake regime in ~2/3 of runs (gpu_ms ~30, 350k
strong marks, black jumps) and is invalid then — the spinning bench route is the reliable parity route;
`--install opt` per validation because the desktop install alternates between sessions' worktrees;
`parity-judge.py` / `colorshift.py` `--window-a/-b` when the auto window slides onto the quit.
- **cd (10:05), the regime is decided at load, not by the turn:** game-thread profile of the 10 s settle *before* the
  route (player still), healthy `cd8-on` (59 fps) vs bound `cd9-on` (29 fps), same build and keys: healthy = update
  72 % / render 24 % (zombies 32, postupdate 19, characters draw 5.8, no chunk bakes / lighting jni in the top 9);
  bound = update 55 % / render 44 % with chunk bakes 7.5 %, lighting jni 7.2 %, prepare chunks 6.8 %, cutaways 4.4 % —
  chunk levels prepared / re-lit / re-baked continuously from the moment the world is up; gpu_ms 12 → 25-40 the
  moment the route adds the turn. 7 of their last 8 runs since 07:00 bound, `cd7-off` (their keys off) included.
  Candidates: a per-level state that never settles after the world-ready zoom force to 2.5 (zoom plan / retain bits)
  or a lighting-refresh queue that never drains.

**Settle-phase table from the overlay logs (10:10, the 10 s before the route; the watch's own check):**

| run | settle frame / gpu ms | route frame / gpu ms | fps |
|---|---|---|---|
| `cd8-on` 07:40 healthy | 13.4 / 10.7 | 16.6 / 13.1 | 60 |
| `vp-zoomfix2-lou` 06:53 healthy | 13.1 / 10.6 | 16.1 / 12.9 | 62 |
| `vp3-lou` 05:20 healthy | 15.1 / 10.4 | 18.8 / 13.5 | 53 |
| `vp1-lou` 04:51 healthy | 15.2 / 11.1 | 19.3 / 13.4 | 52 |
| `cd9-on` 07:44 bound | 17.3 / 12.7 | 33.0 / 30.4 | 30 |
| `cd9-on2` bound | 18.0 / 12.8 | 34.2 / 31.3 | 29 |
| `cd7-off` bound (cd keys off) | 19.8 / 13.4 | 33.5 / 30.1 | 30 |
| `vp5-lou` bound (`lightingStrongFrameMs=0`) | 20.3 / 13.9 | 34.0 / 30.6 | 29 |
| `lou-rec-fix` 03:01 build, 30 fps game-thread-bound | 23.7 / 10.4 | 33.1 / **14.6** | 30 |

The bound runs are already +2-3 ms GPU and +4-6 ms frame in the settle phase (the continuous re-bakes the cd
profile shows), and the turn then doubles the GPU. `vp5-lou` with the adaptive budget off is bound in settle too →
not the lighting-budget key. **The 03:01 build ran this route at 30 fps game-thread-bound with a healthy GPU
(14.6 ms)** — so the GPU doubling under slow frames is *new since 03:01*, not a property of per-frame budgets alone;
something in tonight's tree (or master's zoom pass) re-invalidates levels continuously from load in about half the
runs. A harness-side candidate worth one run: the Louisville preset's `see_all=true` (the `LightingJNI` override
marking every square seen and visible) racing the first lighting pass — if the marking re-arms `pzoptLightChanged`
every frame in the losing order, every visible level re-bakes forever and the coin flip is the load-order race.
Test: the same route with `--flag see_all=false` (scene darker, but the counters / settle gpu tell), or a settle-phase
`bake_counters` print. Not the watch's to fix; handed to the cd session (active) and the record.

- **`see_all` test (10:20, cd's `cd10-seeall-off`):** with `see_all=false` the settle phase is 8.0 ms / GPU 2.7, the
  route 8.9 / 4.5 at 113 fps, strongMarks 6208, deferred 14k (a much darker scene — most of downtown never seen — so
  not a like-for-like, but quiet); the three `see_all=true` runs of the same build were all bound (settle 18 / 13,
  route 32 / 29 at 30-32 fps, strongMarks 352.5k / 352.5k / 355.6k, deferred 455-463k). The strong-mark figure of
  every bound run today is ~350-356k — a constant, i.e. a fixed set re-marked every pass (~470 per frame at 30 fps),
  which is what `see_all` does by construction: `LightingJNI.playerSet(..., seeAll, ...)` passes B41's spectator flag,
  so the native lighting lights and marks *every* square of the loaded grid instead of the vision cone, and the
  ambient fade then moves every exterior level's light past `lightingStrongDelta` each pass (the 09-22 night finding,
  "capped" by `lightingStrongBudget=8`, not removed). The coin flip is which of two attractors the load lands in.
  **For parity: the Louisville preset with `see_all` is a stress scene whose bound attractor invalidates ~half the
  runs; healthy runs only, and the maintainer decides whether the preset should keep `see_all`.** The watch stops
  chasing the regime here.

- **cd (10:25): pass done otherwise, measuring paused until the regime is understood.** Healthy runs read
  characters draw 4.8-5.0 % (`cd8-on` 4.9, `cd9-on3` 4.8, `cd9-on4` 5.0; `renderMovingObjects` 3.6-3.9 + the
  pre-pass's game-thread part 1.1-1.2), 59-65 fps, leftovers 0 — the < 5 % objective met. Their probes: `see_all=false`
  removes every bake / lighting / prepare-chunks churn from the settle profile (necessary ingredient, not the coin
  flip); zombies_loaded does not separate the regimes (healthy 2304-2466, bound 2304-2500); 6 of 6 runs on the
  07:40-07:54 builds bound after three healthy ones at 07:40-07:49 (the only commit in that window is the zoom fix
  e1afa29 at 07:42; the tree's FBORenderCell also carries the zm `lightingReadParallel` hunks and cd's edits).

**Final validation queued (10:30):** `vp6-pair` (0402, `--install opt` of the tree as of now: cd chardraw8 + zoom
e1afa29 + zm final2 + los + ups off, every key at its default): the night-torch route (reliable) and one Louisville
run (read only if its settle phase is healthy).


### Iteration 7 — final validation of the whole tree, install of 08:02:30 (`vp6-pair`, 0402; 50 overrides; every key at its default: `zoomRetain`, `lightingStrongFrameMs=0`, `playerLosFast`, `actionEvalParallel`, `lightingReadParallel`, `charDrawPrep`, `zombieAtlasFast`, `upscaler=off`)

| Run | Judge | Reading |
|---|---|---|
| `vp6-torch` (60 fps at the cap) vs `bl-torch-stock` | **`parity` 0.81, maintained 0.84**, `colorshift=none` (rgb 1.006, hue L1 0.018) — transients 777 vs 881 px/frame, black 87.29 vs 87.35 %, jumps 17 vs 20, pops 0 vs 0, solid 0.43 vs 0.46 | **Pass.** |
| `vp6-lou` (healthy at last: settle 12.5 ms / GPU 10.3, route 15.4 / 12.5, **64 fps**, bakes 9612 / deferred 33k / strongMarks 15k = band) vs `lou-rec-stock` | `flicker` 0.61 — transients 148 px/frame (world 138.9k), black **41.1 %** (band), jumps 5, pops 3, hard jumps 10100/54270, solid 0.11 (all band); the excess is the one roof cell again: **"upper-middle centre-left" = 88.5k** (stock 8.7k), the next cells 15.9k / 14.4k (HUD corners) | **Pass on every metric except the known pre-existing roof-cell flicker**, which persists at 88k on the final tree with everything on. Twin queued: `vp6-lou-zoomoff` (`zoomRetain=false zoomEaseMs=0`, same tree, `--install opt`) — the last open attribution. |

| `vp6-lou-zoomoff` (08:09, `zoomRetain=false zoomEaseMs=0`, same tree) | `lighting_pops` — **bound regime** (settle 17.9 / 13.4, route 34.3 / 31.8 at 29 fps, strongMarks 353.6k, deferred 457k) | Invalid — and the fourth `zoomRetain=false` Louisville run in a row to land in the regime (`vp4-lou-zoomoff`, `vp-zoomfix2-lou-off`, `vp-clean-off`, this one: 4 of 4) while zoom-on runs land healthy about half the time. Reading: with the retention off every chunk level leaving and re-entering the screen is a fresh create + bake, the extra bake load tips this scene into its bound attractor — the retention keeps the route healthy (the zoom session's "keys-off side is worse every time"). Consequence: a healthy zoom-off control is not obtainable on this route by repetition, so the roof-cell attribution stays circumstantial. |

**Roof-cell timeline (the closing state of the one open item):** 03:01 build, no zoom code: 9.8k px (band; 29 fps);
04:48 install, `zoomRetain` plan v5: ≤ 27k incl. the overlay's share (50 fps); every install from 05:18 on (zoom
ease 04:51 + staged 05:09, later the return / leak fixes): 81-100k, still **88.5k on the final tree** (`vp6-lou`,
64 fps, otherwise fully in band). The class is pzopt's held / budgeted re-bake behaviour under the sweeping vision
cone (adopted 09-20 keys); the 3-8x growth coincides with the zoom pass's ease / staged builds and could not be
separated from it by a control (see above) nor from the fps growth (29 → 64 fps). Handed to the maintainer with the
crops (`harness/runs/vp-zoomfix2-lou-20260922-065351/transient-crops/`, `vp3-lou-.../transient-crops/`) and the
rig (`harness/transient-crops.py <run> --cell 'upper-middle centre-left'`); the zoom session has the same numbers.


## Closing summary (10:50)

| Pass | Final validation | Verdict |
|---|---|---|
| **los** — done 05:22 | torch `vp1` / `vp4` / `vp6` pass; Louisville in band with its keys | **parity preserved**; declared transitive: `zombieSpotFast`'s RNG shift |
| **zm** — done 07:12 | torch `vp4` / `vp6` pass; Louisville `vp1-lou` in band, `vp6-lou` in band but the roof cell; zm-keys-off control shows the roof cell is not theirs | **parity preserved**; two transitive defects found and fixed (worker callbacks → ghost death; loud `IsoWorld` marker → dead launches); `lightingStrongFrameMs` defaulted to 0 after the watch's regime data |
| **cd** — done 10:25 (< 5 % met, measuring paused) | torch `vp4` / `vp6` pass with both keys on; Louisville `vp6-lou` in band but the roof cell; cd-keys-off control shows the roof cell is not theirs; `leftovers=0` in every run | **parity preserved**; one transitive defect found and fixed (off-thread `updateLights` in chardraw3) |
| **ups** — done 09:30 | bicubic / fsr1 / dlss 50 % / dlss 67 % on the bench route: parity 0.63-0.94, colour unchanged, no ghosting; `upscaler=off` default pixel-equivalent (`vp6-*`) | **parity preserved** |
| zoom (not on the list) | its stale pending bits were the 06:18-06:25 deferral flood (fixed, committed e1afa29); the roof-cell flicker is pre-existing (03:01: 9.8k) and grew to 88k alongside the zoom builds — control unobtainable (zoom-off tips the regime 4/4) | open, circumstantial, for the maintainer |

**Whole tree at every default:** night-torch **pass** on the final tree (`vp7-torch`, 08:29: parity 0.86, colour
identical) and one build earlier (`vp6-torch`, 08:02: parity 0.81 / maintained 0.84); Louisville **pass on every
metric but the pre-existing roof cell** (`vp6-lou`, 08:02: 88.5k px vs stock 8.7k, 64 fps, counters in band).

For the maintainer, beyond parity: (1) the Louisville preset's `see_all` spectator lighting + pzopt's per-frame bake
budgets give the scene two attractors (healthy ~60 fps / bound ~30 fps with GPU doubled, 350k strong marks a run);
the load picks one, recorded runs and `zoomRetain=false` push toward the bound one; zm's §6 lead (a bake budget per
unit of wall time / GPU ms) is the cure candidate; (2) the roof-cell held-re-bake flicker under a sweeping cone;
(3) `harness/queue.sh`: `--install opt` reinstalls after any session's build, so a failed peer build empties the
game dir for the next `--install opt` (jobs 0204, 0408) — reinstall should refuse an empty `build/classes` (it does
since 05:xx) and `find_job` must parse ids as decimal (fixed in place at 05:15).
- **zoom session (11:00), code-side answer on the roof cell:** (1) the retention rectangle is the screen at the widest
  zoom + an 8-tile margin; the Louisville preset runs at the widest zoom, so almost nothing off screen is kept —
  their `vp-zoomfix2-lou` counters (returned=76, creations=8, rebakes=0, urgent=0) say every re-entering level that
  reached the bake block had no texture and was created and baked that frame, as stock does; (2) the only path that
  composites a kept texture with older state is the flood hold, which needs a zoom change and cannot start on a
  walk; (3) a re-entering level gets stock's `invalidateLevel(1024)` and since e1afa29 bakes in the same frame, so
  its lighting is as fresh as a stock create. Their conclusion: the 9.8k → 88.5k growth is not a kept texture;
  something else between the 04:48 and 05:18 installs. Offered discriminator that keeps the bake load: a
  `zoomEaseMs=0`-only twin (retention on) separates the ease from the retention. → With the code argument on record
  and the zm / cd / los keys cleared by controls, the roof-cell growth is **unattributed**: candidates left are the
  fps growth itself (29 → 64 fps: more distinct frames per second in the resampled video) and pzopt-wide keys that
  changed between 04:48 and 05:18 outside the four passes. Closed on the watch's side as "pre-existing, magnitude
  open".

- **cd (11:05): pass done, final code in the tree** (`build-chardraw13`, installed by `cd13-on` 08:22). Keys:
  `charDrawPrep` (true), `zombieAtlasFast` (true), `charDrawThreads` (14, clamped to cores − 2). Final shape: object
  walk on a pool thread from the top of the cell render; before the chunk bakes the game thread does the visibility
  test, `checkUpdateModelTextures` and the lazy per-square lighting refresh (the zombie's square and the one above on
  stairs, as stock's `renderShadow` does before `updateLights`); the pool then does `updateLights` (plain reads; the
  `ModelInstance` override interpolates with thread-local Color scratch instead of `IsoGridSquare`'s statics),
  `initModel`, `init` and the camera record per zombie; the stock loop runs with `IsoZombie.pzoptRenderFlat` for atlas
  and prepared model zombies (same tests, writes and enqueue calls). Healthy runs 4.4-4.8 % characters draw (63-66 fps,
  leftovers 0) vs 11.3 % with both keys off (`cd13-off`, 55.5 fps). Nothing visible intended. → `vp7-pair` queued
  (`--install opt` of the tree as of now, torch + Louisville at every default) as the closing validation.


### Iteration 8 — closing validation of the final tree, install of 08:29:37 (`vp7-pair`, 0423: cd `build-chardraw13` final + everything else; 50 overrides; every key at its default)

| Run | Judge | Reading |
|---|---|---|
| `vp7-torch` (60 fps at the cap) vs `bl-torch-stock` — window pinned at 20-40 s after the auto window slid 4 s onto the quit-to-black (the same slip as the fsr1 bench run; first read as "12 % fewer lit pixels", disproved by the per-second profile matching stock and `vp6` second by second once aligned) | **`parity` 0.86, maintained 0.68**, `colorshift=none` (rgb 1.009 / 1.014, sat 0.98, hue L1 0.004) — transients 832 vs 881 px/frame, black 87.16 vs 87.35 %, jumps 18 vs 20, pops 0 vs 0, hard jumps 4411/9121 vs 4299/8596, solid 0.43 vs 0.46; player-centre luma 34-37 in stock / vp4 / vp6 / vp7 alike | **Pass.** The cd session's final `ModelInstance` / `CharDraw` / `IsoZombie` code changes no pixel that the torch route can show. |
| `vp7-lou` | bound regime (32 fps, strongMarks 354k) | invalid; the healthy Louisville reading of the tree stays `vp6-lou` (08:02, one cd build earlier: in band but the pre-existing roof cell). |

**Method note:** the judge's auto window slid twice today when the desktop stayed busy after the quit (a peer's
window or the next queue job starting); always compare the printed windows of the two sides and pin the slipped one
with `--window-a/-b` — a slipped window reads as `black_tiles` / a hue shift / fewer lit pixels.
