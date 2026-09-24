# The characters draw on the Louisville horde (2026-09-22)

Objective of the pass: the `characters draw` sub-phase of the game thread (`GameThreadProfile`:
`FBORenderCell.renderMovingObjects` / `renderMovingObject` / `renderPlayer`, i.e. the game-thread half of drawing
every moving object) below 5 % of the game thread on `--preset louisville`, without changing a pixel. Runs `cd*-*`,
analysis with `harness/subtree.py <run> --self` (the callee tree under one frame from `pzopt-stacks.out`) next to
`analyze.py`.

## 1. What the sub-phase was made of

Louisville at max zoom: ~2,150 moving objects in the cell, ~1,600 of them on screen, ~480 zombies with a 3D model
(`sceneCullZombies` caps the models at 510), ~1,100 culled zombies drawn as atlas sprites, a few vehicles. Stock
(`lou-zm-off2`, the zombie session's four keys off): 17 % of the game thread; with those keys
(`skinTransformsPrecompute`, `skinPalettePrecompute`, `shadowPrep`, `boneIndexCache`) 7.7-13 %.

Per model zombie the stock chain is `renderMovingObject` → `renderShadow` → `IsoZombie.render` →
`IsoGameCharacter.render` → `IsoSprite.renderActiveModel` → `ModelInstance.updateLights` +
`SpriteRenderer.drawModel` → `TextureDraw.drawModel`. Facts that decided the design:

- `TextureDraw.drawModel` allocates a `ModelSlotRenderData`, runs `initModel` on the game thread (one
  `ModelInstanceRenderData` and one 3.8 KB matrix palette per body / hair / clothing model, the `isReady` chain, the
  refcounts) and submits `init` (depth, effect lights, the palette into shader parameters, attachment transforms,
  ~20 named shader-property writes) to the game's own eight-thread executor, one task and one `Future` per
  character: the debug option `Threading.ModelSlotInit` defaults to true and is true in
  `~/Zomboid/debug-options.ini`. So `init` never showed in the profile; the game-thread cost was `initModel`
  (5 % before the palette precompute), the executor submit (1.6 %: a `FutureTask` and a queue hand-off per
  zombie) and the shadows.
- The object list is a `HashSet`: the stock draw order is hash order. Models are drawn immediately in stream order
  (the instanced `RenderList` path only exists under a debug option), so the order is kept exactly.
- `renderShadow` computed the shadow ellipse (three bone→world transforms, three closest-point projections) before
  testing `isSceneCulled()`, i.e. for every one of the ~1,100 culled zombies whose shadow is never drawn (2.5 % of
  the game thread; the zombie session's `shadowPrep` only served the batched model zombies). Fixed by that session
  as a pure reorder in `IsoGameCharacter.renderShadow`, plus `getRagdollController() != null` before `canRagdoll()`
  in `render` (0.4 %).
- Thread-safety audit of `initModel` / `init` for a pre-pass: the object pools are synchronized (alloc on any thread,
  release on the game thread at `Core.StartFrame`), `IsoDepthHelper.Results`, `GameProfiler`, the `BaseVehicle`
  matrix pools and `Shader.tempVec3f` are thread-local, `ModelInstance.updateLights` used two static `ColorInfo`
  scratches (thread-local in the override now), `FBORenderTracerEffects.playerWeaponTransform` is a plain `HashMap`
  the stock executor already touches from eight threads (absent-key removes). A zombie's `AnimationPlayer` (lazy
  skinning-data init, the bone-index cache, the palette hand-off) expects one thread at a time: one task per zombie.

## 2. `charDrawPrep` (pzopt.CharDraw)

Right after `renderPlayers`, one walk of the object set in stock order keeps the on-screen objects and, for the
zombies whose model the stock chain is about to draw (the same tests as the chain: square light info, cutaway
visibility, not fake-dead, doRender, alpha, not seated, sprite visible, `useParts`, active model, no hand model, not
on furniture), runs `checkUpdateModelTextures` and hands them to the slot-init executor as up to eight strided
tasks: `updateLights`, `alloc`, `initModel`, `init` per zombie on one thread. The game thread draws the corpse
shadows, world items and puddles meanwhile; `renderMovingObjects` joins, runs the stock per-object loop over the
on-screen list, `TextureDraw.drawModel` takes the prepared data (no future), the `ModelInstance.updateLights`
override skips its second run by a frame stamp. Leftovers (prepared, not drawn) go through a no-op drawer so the
render thread runs their `postRender` in order; the counter stayed at 0.

Iteration 1 (`cd1-on`, FrameBatch at `renderMovingObjects`, the game thread joining in as a worker): 47.6 fps (from
43.4 on `lou-zm-on2`), characters draw 9.8 % — the game thread's own share of the batch (`init` included) was
2.6 %, the shadows 3.2 %, the walk 1 %. Hence iteration 2: the executor fork after the players, join at the loop.

Iteration 2 (`cd4-on`, the batch on the slot-init executor forked after `renderPlayers`, joined at the loop, plus
the zombie session's two reorders): 57.5 fps, characters draw 7.2 % — but 3.5 % of it was the game thread waiting
at the join (`join waits=1779/1799 frames, 0.5 ms each`): ~450 zombies x ~9 µs of draw data on eight threads is
~0.5 ms of wall time, and the corpse shadows / items / puddles between fork and join are shorter than that. The
sequential loop itself was 3.7 % (`IsoGameCharacter.render` 1.0 %, the atlas sprites 1.1 %, the model zombies'
enqueue 0.5 %, their shadows 0.6 %).

Iteration 3 (`cd5-*`, `cd6-*`): the walk itself moves to one worker task started at the top of the cell render; the
game thread only applies the frame's visibility test, the texture-creator check and `updateLights` (game thread
only: its square light reads are the lazy `JNILighting.update` with the dirty-tracking hooks) right before the
chunk bakes and submits the build tasks, which then overlap the bakes, the composite, the players and the corpse
pass. And `zombieAtlasFast`: the ~1,100 culled zombies drawn as atlas sprites take a flat copy of their render
chain (`IsoZombie.pzoptRenderAtlas`: the same tests, writes and sprite call, no virtual chain), falling back to the
stock chain for any other case. Plus the culled zombies' provably no-op `renderShadow` call is skipped. The `cd5`
numbers were void (an unrelated deferral flood in that install inflated the fps by skipping bakes — read
`bakes in period` / `deferred so far` on the log line before any Louisville fps).

Iteration 4 (`cd7-*` … `cd13-*`): the build tasks run on the pass's own pool (`charDrawThreads`, 14 on this
machine, clamped to cores − 2) instead of the game's eight-thread slot-init executor, handed over chunk by chunk as
the game thread tests the candidates; the flat render path covers the prepared model zombies too (their square
tests are not repeated, the on-screen loop skips the three tests its list was built from); the camera record is
built with the draw data; and `updateLights` moved to the worker behind a game-thread refresh of the lazy
per-square lighting reads it makes (`square.lighting[p].lightInfo()` for the zombie's square and the one above it
on stairs; stock's `renderShadow` refreshes the same square just before the stock call), with the square-class
static colour scratch of `interpolateLight` replaced by thread-local scratch in the `ModelInstance` override.

The Louisville preset turned out bistable during this pass (found with the parity session): `see_all=true` makes
the native lighting mark the whole loaded grid and the ambient fade moves every exterior level past
`lightingStrongDelta` each pass, so about half the runs since ~07:00 load into a GPU-bound re-bake regime
(27-30 fps, settle-phase frame 15-18 ms / gpu 12.7-13.9 instead of 13 / 10.5, `bakes in period` ~30k instead of
~4k, strong marks ~350k). Only runs with a healthy settle count for the numbers below; the regime is not decided by
any key of this pass (`cd7-off`, both keys off, is bound too) and belongs to the harness flag / the strong re-bake
logic.

## 3. Numbers

| run | fps | characters draw | of which | notes |
|---|---|---|---|---|
| `lou-zm-off2` (zombie keys off) | 38.4 | 17.2 % | initModel 5 %, shadows 3.4 %, submit 1.6 % | reference before both passes |
| `lou-zm-on2` (zombie keys on) | 43.4 | 13.2 % | initModel 5 %, shadows 2.9 % | the zombie session's four keys |
| `cd1-on` | 47.6 | 9.8 % | batch on the game thread 2.6 %, shadows 3.2 %, walk 1 % | FrameBatch at the loop |
| `cd4-on` | 57.5 | 7.2 % | join wait 3.5 %, loop 3.7 % | executor fork after the players + the shadow / ragdoll reorders |
| `cd5-on` | (64.2) | 4 % | — | void: the install's deferral flood (from another session's change; persisted with the keys off) skipped most bakes |
| `cd6-on2` | 63.3 | 5.2 % | walk-on-worker + start 1.1 % (updateLights 0.7, texture check 0.3), loop 4.1 % (atlas flat path 1.5, model enqueue 0.7, model shadows 0.7, join wait 0.8) | bakes 3597 / period, deferred 8.5k: healthy; the eight executor threads still left 0.2 ms of join wait a frame |
| `cd8-on` | 59.3 | 4.9 % | start 1.3 (updateLights 0.8, texture check 0.3), loop 3.6 (flat path 2.4 incl. the atlas sprites 1.1, shadows 0.4, join 0.3) | own pool of 12, flat path for the model zombies too |
| `cd9-on3` / `cd9-on4` | 59.6 / 65.4 | 4.8 / 5.0 % | start 1.2 / 1.1, loop 3.6 / 3.9 | chunked hand-off; join waits 425-503 frames, 55 ms in all |
| `cd12-on` / `on2` / `on3` | 62.8 / 62.8 / 66.0 | 4.5 / 4.4 / 4.7 % | start 0.6 / 0.5 / 0.4, loop 3.9 / 3.9 / 4.3 (join 0.4-0.7) | updateLights on the worker behind the game-thread refresh |
| `cd13-on` | 64.5 | 4.8 % | start 0.7 (texture creators 0.6), loop 4.1: flat path 2.3 (self 1.1, atlas sprites 0.9, model enqueue 0.3), model shadows 0.7, vehicles + player 0.4, join 0.4 | 14 threads; final build |
| `cd13-off` (both keys off, same build) | 55.5 | 11.3 % | initMatrixPalette 2, drawModel 2, isReady 1 | the stock chain on the same tree |

The absolute game-thread cost of the sub-phase went from ~3.0 ms a frame (`lou-zm-on2`, 13.2 % of 23 ms) and
~2.0 ms (`cd13-off`, 11.3 % of 18 ms, the same tree with the keys off) to ~0.75 ms (`cd13-on`, 4.8 % of 15.5 ms);
`analyze.py` prints the sub-phase rounded to whole percents, so a 4.5-4.9 % run reads "5%" there —
`harness/subtree.py <run>` and `--frame pzopt.CharDraw.start` give the two parts exactly. What is left is the
per-object work of the stock design: ~1,600 on-screen objects x (the zombie's fields, the square's vertex lights,
two sprite slots for an atlas zombie) ≈ 0.5 ms, the texture creators of zombies that changed clothes, the model
zombies' shadows, and 0.1-0.2 ms of join wait when the chunk-bake window is shorter than the batch (the batch is
bound by the synchronized render-data pools and the palette load in `init`, not by threads: 14 threads did not
shorten it over 12). Leftovers stayed at 0 in every run.

Visual parity (the parity session's watch, `docs/parity-watch-2026-09-22.md`): the final tree (08:29 install,
every key at default) on the night-torch route vs the stock recording — parity 0.86 / maintained 0.68, colour
identical (rgb 1.009, saturation 0.98, hue L1 0.004), transients 832 vs 881 px/frame, black share 87.16 vs 87.35 %,
player-centre brightness equal to stock; the healthy Louisville reading (`vp6-lou`, one build earlier) everything in
band except a roof cell that predates the pass. Closed as parity preserved.

## 4. What did not make it

- Batching the atlas sprites into one drawer per frame: the models are drawn immediately in stream order (the
  instanced `RenderList` path is debug-only), so moving ~1,100 depth-tested alpha-edged sprites to one point in
  the stream changes the blend order at every zombie / sprite overlap — not the same pixels. An order-preserving
  version (runs of consecutive atlas zombies between model zombies) would save about half of the two sprite slots
  per zombie but needs the private atlas drawer and its shader; not worth an override of `DeadBodyAtlas`.
- Skipping the `StartShader(0)` slot before each atlas drawer: not redundant — `checkShaderChangedTexture1` on it
  is what clears the ring buffer's texture-unit-1 bookkeeping after a shader that bound unit 1.
- Moving `updateLights` off the game thread without the refresh: the lazy `JNILighting.update` behind the square
  light reads (JNI reads, field writes, dirty-tracking hooks) races the game thread's own lazy reads during the
  bakes.
- The draw data cost itself (`init`: ~25 string-keyed shader-property writes and a 60-matrix palette load per
  sub-model, x5 per zombie) is the zombie session's / a `ModelSlotRenderData` override's territory; it is off the
  game thread now, so it only bounds the batch's wall time.
