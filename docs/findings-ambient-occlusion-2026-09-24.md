# Ambient occlusion at (almost) no cost, 2026-09-24

Keys: `ambientOcclusion` (off by default), `aoMode` (`chunk` | `screen`), `aoScalePct` (50), `aoRadiusPct` (60),
`aoStrengthPct` (100), `aoThicknessPct` (60), `aoBakeBudget` (4), `aoComputeBudget` (4), `aoSkipSlowFrames` (on),
`aoSlowFrameComputes` (0); dev: `devAoView`, `devAoTiming`,
`devAoDumpFrame`, `devAoVariant`, `devAoNoMips`, `aoChunkFlip`, `aoReuse`. Classes: `pzopt.ChunkAo` (chunk mode),
`pzopt.AmbientOcclusion` (screen mode and the shared constants). Hooks: `docs/override-edits.md`, "Ambient occlusion".

## What it looks like

![AO on (top) and off (bottom), Rosewood house, zoom 1](media/ao-rosewood-on-vs-off.jpg)

Soft occlusion where surfaces meet: floors darken along wall bases and in room corners, under and around furniture,
beds, sofas, shelves, stairs, fences, bushes and trees; the outer wall of a house gets a crease on the pavement.
Characters and vehicles keep their own shadows (they are not in the chunk textures). Screenshot rig: bench at the
Rosewood house `--flag start=8147,11507 --flag zoom=1 --flag route=S:30 --flag speed=1 --shot-at 3`, with
`--prop devAoView=1` for the AO term alone.

## The depth is a world-space height field

The FBO renderer's depth is linear in world space (`IsoDepthHelper`: `C - (x + y + 2z) * SQUARE_DEPTH / 2`) under an
orthographic 2:1 projection. A square is `32 sqrt(2) tileScale / zoom` screen pixels across (90.5 at tileScale 2,
zoom 1), and one unit of depth is 424.27 squares along the view (`cos 30 cos 45 / (SQUARE_DEPTH / 2)`); a level is
2.449 squares tall. Checked on a dumped depth buffer: the ground slope is 6.767e-5 per pixel (predicted 6.766e-5),
a wall face 2.25e-5 (predicted 2.26e-5). So every depth texel is an exact view-space position and the AO is computed
in true 3D, with no normal buffer.

## Kernel

Ground-truth-style horizon AO with visibility bitmasks (Therrien, Levesque, Gilet 2023): per slice, 32 sectors of the
hemisphere around the projected normal, equal in cosine-weighted solid angle (`sin` of the angle from the normal),
each sample covering the sectors between its front and an assumed back `aoThicknessPct` behind it; thin posts occlude
as little as they cover and a depth jump beyond the radius is no occluder (no halos). Trig-free form: the sector of a
slice vector is `sin(a - n)` from two dot products with the normalised vector (identical to the atan/sin form on a
dumped frame, max difference 0). Two slices per texel rotated over a 4x4 Bayer tile (32 directions), four steps per
side, a 4x4 depth-aware box over one tile period.

Specific to sprite depth:
- normals from 2-pixel differences (the chunk textures are drawn at zoom scale: one-pixel steps alternate 2:1) snapped
  to the three planes tiles are made of (ground, east wall, south wall) when within ~20 degrees (87 % of pixels);
- a 0.04-square height bias: occluders barely above the tangent plane are ignored;
- tile edge rows are written ~0.02 squares behind their neighbours: a centre texel behind both neighbours on an axis
  by less than 0.05 squares is filled (else a dark line along every chunk diamond edge);
- a neighbour texture's overlapping edge lies ~0.05 squares in front of ours: the centre takes the nearest of all
  sources (the surface the composite shows); taps take the texture's own depth where it has a pixel.

## Screen mode first: 90-140 us a frame

`aoMode=screen` runs the kernel on the scene depth right after the chunk composite (the scene then holds exactly the
static world) and multiplies the scene. Desktop (RTX 4090, 5120x2160, DLSS 67 % = 3413x1440 world), uncapped walking
route: AO 105 us + blur 13 + apply 25 at 50 %, 62 + 6 + 23 at 25 %; 500 -> 450 fps. Probes: a pass that reads one
depth texel costs 15 us, one that reads none 8 us; the trig-free kernel changed nothing, the apply early-out neither.
Fixed per-pass cost dominates on this driver, so any per-frame full-screen AO stays well above "nothing".

## Chunk mode: baked into the chunk textures

The static world is drawn into chunk-level textures when they bake and only composited every frame, so the AO is
computed per texture and multiplied into its colour; frames that bake nothing pay nothing, a moving camera pays
nothing. Pieces, each found necessary by measurement:

- The multiply runs at the end of every bake while the texture's framebuffer is bound (before the stock mipmap build).
  The AO depends on the depth only, so lighting-only re-bakes (the frequent kind) reuse the kept R8 AO.
- Computes (a new texture, changed objects / cutaways / trees) run inside the bake up to `aoBakeBudget` per frame
  (no ratio, no mip work: the stock mipmap build follows), else deferred (`aoComputeBudget` per frame) and applied later as a ratio new / old (blend `DST_COLOR, SRC_COLOR` =
  2 src dst, lightens and darkens) with only the mip levels the current zoom / render scale samples (the rest
  re-bake when the player zooms out past them). `glGenerateMipmap` costs ~65 us per 1024 texture here, and every
  framebuffer re-attachment ~3 us.
- Occluders across the chunk edge: the kernel reads the eight neighbour textures of the same level pair and zoom at
  their composite offsets and chunk depth offsets. Neighbours computed without a new texture get one refresh (after 8
  frames, one for the whole streaming wave), and only when something but floor stands on the new chunk's border squares
  facing them (the only place within the radius). An 8-frame wait before a new texture's own first compute did not
  reduce the refreshes (2,239 vs 2,272) and was dropped.
- The first multiply after a compute runs under an occlusion query; a texture with no occlusion skips its later
  multiplies.
- The `redraw` dirty flag (a level back on screen, water shader toggles, light switches) is not a geometry change
  (6,000 of them in a 25 s storm run).

## Cost (desktop, RTX 4090, 5120x2160, DLSS at 67 %, uncapped; `devAoTiming`, route window, `/tmp/ao/routecost.sh`)

| scene | AO GPU time | frame time |
|---|---|---|
| standing still, or a camera that only moves | 0 (nothing bakes) | = |
| walking through Rosewood, 3 tiles/s (`ao-f-walk-s50`, `ao-h-walk`) | 4.1-4.2 us / frame (0.27 % at 650 fps) | = |
| 120 km/h path drive south at max zoom (`ao-m-drive`) | 12.9 ms / s = 1.3 % | 2.4 ms mean both, p99 11.3 (AO) vs 11.5 (off), `ao-k-drive-on/off` |
| storm preset route (18 tiles/s run + spin: 75 new chunk textures / s, cutaways) (`ao-m-storm`) | 12.9 ms / s = 1.3 % | fps within the run-to-run noise (off: 246-264) |

Per job: a compute is ~25 us of kernel + 5 of box + 7 of multiply inside a bake (+ ~20 us of ratio / copy / mip
levels when deferred), a lighting re-bake's multiply ~6 us. The remaining cost scales with jobs, not texels:
`aoScalePct=25` gave 12.50 ms/s against 12.87 at 50 % on the storm route, and a pass that reads nothing costs ~8 us
on this driver. The loop's steps, storm route cost in ms of GPU per second (per-frame figures before the metric
changed): screen mode 90-140 us / frame -> chunk mode with context pass 14.2 ms/s -> scissored context, deferred
creations 14.3 -> in-bake creations 14.3 -> bare-border refresh skip + in-bake budget 4: 12.9. The 25 % default was
rejected: blockier contact edges for a 3 % saving.

Quality check: `devAoView=1` shots (the AO term alone) and the on / off brightness ratio at the Rosewood house (no
chunk seams after the centre / pit / bias fixes; offline check of the kernel and the fixes on dumped depth with numpy,
`devAoDumpFrame`). Max zoom-out keeps the AO (mip levels).

## Frame-time tail (the objective's metric)

Capped 240 fps drive-120-south (bench args, game-thread bound: game load 94 %, GPU 60 %):

| | fps | p99 | p99.9 | max |
|---|---|---|---|---|
| AO off (`ao-cap-off1/2`) | 217.9 / 217.9 | 14.7 / 14.2 ms | 36.0 / 33.3 | 51.5 / 53.4 |
| AO on, fixed budgets (up to 4 in-bake + 2 deferred a frame, `ao-cap-on1/2`) | 214.0 / 215.9 | 16.4 / 15.4 ms | 37.3 / 36.9 | 196 / 54.8 |
| AO on, slack gating (`ao-cap-on3..6`) | 220.1-220.4 | 14.2-14.8 ms | 31.4-36.6 | 46.8-52.9 |

The computes landed on the slowest frames (streaming bursts). Now, under a cap, a frame gets as many computes as the
last frame's slack fits (~60 us each), none in bakes after a frame that missed the cap, and one deferred compute every
8 such frames; the deferred queue serves the textures composited this frame first. Trade-off: on this drive most frames
miss the 240 cap (the game thread), so the queue peaks at ~340 textures and new chunks show their AO late while the
car races at max zoom; it drains when the player slows down. One compute on every slow frame (`aoSlowFrameComputes=1`)
barely shortened the queue (325) and one of two runs read p99 16.1 ms, so the default stays the trickle. Uncapped play
keeps the fixed budgets.

## Other GPUs

The Mac's game runs a legacy OpenGL 2.1 context (Apple's Metal-backed GL, GLSL 1.20): `#version 140` is refused, the
feature logs the compile error and switches itself off, the game runs normally (the fog pass is in the same position).
A GLSL 1.20 kernel would need the bitmask without integer operations. The flip (AMD / Mesa) was offline.

## Default

Off: a deliberate change of the picture (peers' visual-parity judges compare stock and optimized pictures), one tick
box in Options > Optimizations > "Ambient occlusion" to turn on.

## Strength per surface (2026-09-25)

The single `aoStrengthPct` became three keys (four since the vegetation one below) on the Options > Enhancements tab: `aoStrengthFloorPct`,
`aoStrengthWallPct`, `aoStrengthObjectPct` (0 / 50 / 75 / 100 / 150, default 100). The AO kernel already snaps the
reconstructed normal to the ground or one of the two wall planes (dot > 0.94); that class picks the strength, applied
to the computed occlusion before the blur (`1 - (1 - vis) * s`), so the kept R8 AO carries it and the multiply / ratio
passes run at 1. Everything that is neither floor nor wall (furniture, fences, stairs, bushes) is "objects". An unset
key takes `aoStrengthPct`, so options files from before keep their look. Same in `aoMode=screen`. No cost: one select
per computed texel. Check (runs `enh-aosplit-default` / `enh-aosplit-floor`, `devAoView=1`, `--shot-at 3` at the
Rosewood spot): floors 150 / walls 0 / objects 0 leaves walls, chairs, shelves and bushes white and darkens only the
floor under and around them.

A fourth, `aoStrengthVegetationPct` (chunk mode only; screen mode counts vegetation as objects): the chunk depth has no
stencil or spare channel to tag vegetation in the bake, so the kernel reconstructs an "object" pixel's square from its own
depth (`calculateDepth` is k (20 - (x + y) - 2z) within the chunk, k = 0.023093667 / 16) and its texture position (x - y
from the column, (x + y) 16 - z 96 world px from the row) and looks it up in a per-compute mask built on the game thread:
16 x 16 squares (the chunk and 4 around it), one plane per level for bushes / grass / flowers (`isBush`, `canBeRemoved`,
`vegitation`), one for tree crowns at any height (a tree marks the squares its crown spans along its screen row, 3 each
side). The mask is only built (and the lookup only runs) when the vegetation strength differs from the objects one.
Check `enh-aoveg` (vegetation 0, objects 150, `devAoView=1`): bush and tree bodies white, the ground under them and the
fence shaded; a few twig-tip pixels at bush tops stay shaded (their depth slope reads as floor or wall).
