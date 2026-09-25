# Reflections on water and puddles (2026-09-25, `reflections`, pzopt.Ssr)

The maintainer's ask: "implement screen-space reflections; sub-objective: virtually zero performance cost; implement the
state of the art one idea at a time and profile each". Plan item 3 of `docs/plan-graphics-enhancements.md`.

Result: the scene (buildings, fences, lamp posts, trees, cars, characters) is mirrored in rivers, lakes and puddles,
rippled by the waves and the rain. Off by default (a change of the picture; Enhancements tab "Reflections"). Cost on the
desktop (RTX 4090, 5120x2160, whole-frame GPU time, same-run on/off alternation): **0 us with no water on screen**,
**+15 us at a river** whose stock water pass alone costs 1.45 ms; see the table at the end.

## The geometry that makes it cheap

PZ draws an orthographic 2:1 isometric view. The mirror of the view ray about a horizontal surface is the same for every
pixel: on screen it runs **straight up the pixel's column**. In the frame the pixel-light / capsule-shadow passes already
use (u = x - y from the window x, v = x + y - 6z from the window y, w = x + y + 2z from the depth; z in levels):

- a scene point at height h above the water plane mirrors 12 h units of v below itself: **twice its height on screen, in
  the same column**; a vertical wall maps row for row (no holes), so the mirror image is exact wherever the camera sees
  the surface;
- along a reflected ray **g = w - v / 3 is constant**; the water pixel's own ray has g = 2/3 v + 8 z_water. "Does the ray
  meet the scene above?" becomes "the first pixel up the column whose g is at least the ray's": a 1D next-greater search.

## Variants, in the order they were built and measured

Offline numbers: `harness/ssr/rig.py` (real frames dumped with `devSsrDumpAt`, moderngl on the 4090, 7 % water pixels).
In-game numbers: `devSsrTiming` (GL timestamps around the pass) and `devSsrAlternate=1000` (the reflections flip on / off
every second in the same run; `harness/contact/alt.py` splits the overlay's whole-frame `gpu_ms` by that clock; `harness/ssr/tails.py` the percentiles, `harness/ssr/timing.py` the pass timers).

| # | Variant | Cost | Verdict |
|---|---|---|---|
| 1 | Column march in the water shader (classic SSR: stride 8, 48 steps, 3 binary refinements, thickness test) | rig +9 us; **game +40..60 us** | the stock water shader is huge (1.45 ms here): low occupancy exposes the march's chain of dependent depth fetches; also misses (96 % of the water pixels) pay every step |
| 2 | Hi-Z: a max pyramid of g along y only (exact in this frame, O(log n) traversal) | rig +150 us | the 13-level build over the full screen dominates |
| 3 | Screen-space planar reflections (pixel-projected: every scene pixel writes its row into the pixel it mirrors to, atomicMin) as a separate compute pass | rig +85 us | a full-screen depth read + clear per frame |
| 4 | **The same scatter fused into the chunk composite** (the composite already reads every static pixel's colour and depth) | rig ~0 us, **even with every target valid** | the composite is bandwidth-bound: the ALU, a cached square-map fetch and the atomics ride free. Chosen. |

Refinements of 4, each measured in the game (river scene, water / composite deltas):

- Per-chunk-texture gating: only textures with water within three chunks in front scatter (a uniform per draw);
  without water on screen the composite is unchanged (delta 0.2 us).
- Keys: `atomicMax` of [epoch 7 bits | 2047 - rows 11 | colour 5:5:4]. A newer frame always outranks an older key, so
  the water only *reads* its texel (no reset, neighbours readable, early-Z safe). Two hashes alternate every 127 frames;
  the idle one is cleared 1/120 a frame, so an old key never meets the same epoch again. (A first version reset the
  texel after reading it: races, and hidden puddle fragments never reset theirs.)
- Hidden layers: the composite also scatters fragments that later get overdrawn, with their own colour, so the
  reflection shows surfaces the camera does not see (e.g. a wall behind a railing). The resolve samples the world
  colour at the source (displaced by the waves, filtered) and falls back to the key's colour when it does not match.
- Characters and vehicles are drawn after the composite: an instanced box per moving object near water (the square map
  decides) reads their final depth + colour and scatters them. Run right after the water draw, whose barrier already
  made them visible, it writes the *next* frame's epoch: no pipeline drain of its own, reflections one frame late.
- An 8x8 tile map stamped with the frame number by every writer (a plain store of the same value): water pixels without
  a reflection stop at one cached read (water delta 18 -> 12 us).
- One barrier a frame (texture + image) before the first water / puddle draw, one more after the characters.
- Scatter reach cut: a surface mirrored farther than the resolve's distance fade is not written.
- Puddles: the square map marks level-0 puddle squares (while puddles are big enough to reflect: puddle size > 0.11);
  puddle targets use half-resolution keys (even sources, even texels: a quarter of the atomics; the rain's rings blur
  them anyway); the resolve runs only where the puddle shows its reflective colour.

Look: Fresnel (Schlick, water F0 0.02) of the wave normal relative to flat water at the camera's 30 deg; distortion by
the wave normal growing with the reflected distance (contact hardening: sharp where an object meets the water); fade
with the distance (reach 1.5 levels) and near the top of the screen; the stock sky reflection stays underneath.

## Traps

- One GenericDrawer instance shared across frames gets its fields overwritten when the game thread queues the next
  frame before the render thread ran it: the moving scatter wrote the wrong epoch and blanked reflection blocks. One
  drawer per frame slot.
- `packed` is a GLSL keyword (1.40+); `layout(r32ui)` needs `#version 150`; the puddle main unit gets the same version as
  its patched common unit.
- The world framebuffer's texture is a power of two (8192x4096 for 5120x2160): size per-pixel buffers by the viewport.

## Rigs

`harness/ssr/rig.py` (offline variants on dumps), `harness/ssr/patchcheck.py` (driver compile of every patched unit),
`devSsrDumpAt`, `devSsrTiming`, `devSsrAlternate`, `devSsrSkip` (1 moving pass, 2 water resolve, 4 barriers, 8 composite
scatter), `devSsrView=1` (the reflection term alone), `devSsrNoPatch` (stock shaders).

## Measurements

Desktop, RTX 4090, 5120x2160, upscaler off, zoom 1.5, the Riverside pier restaurant (`--flag start=6404,5188 --flag
find=shore`, 15:00), still camera, reflections flipping on / off every second (`devSsrAlternate=1000`); whole-frame GPU
time from the overlay log split by that clock (`harness/contact/alt.py --skip 6`, `harness/ssr/tails.py`), 600-frame
windows for the pass timers (`devSsrTiming`).

| Scene | stock water pass | reflections, whole-frame GPU (median / mean) | composite scatter | water-side (resolve + moving objects) |
|---|---|---|---|---|
| no water on screen (bench start) | - | **+0.0 / -17 us** (noise) | 0.2 us | 0 |
| river, clear (runs ssr-fin-river, -river3, ppr10, ppr11) | 1.40-1.45 ms | **+11..15 / +8..26 us** | 2-8 us | 10-12 us |
| rain, puddles pinned 0.8, no lightning (ssr-fin-rain3, -rain5, pud8) | 1.40 ms | **+27..30 / +15..49 us** | 9-11 us | 16-26 us |

History of the river number: column march +40..60 us (water pass alone) -> pixel-projected +27 (water) +10 (composite) ->
per-chunk gating, read-only keys, one barrier, tile map, moving pass after the water, reach cut -> +11..15 whole-frame.
Rain: +46 -> +40 (reach cut, half-res puddle keys, puddle gate) -> +27 (fire-and-forget atomics: the tile mark had
made the scatter's atomic return its old value, a round trip per fragment) -> bounded puddle-only moving boxes (120 -> 37
a pass).

Dead ends, measured and removed:
- A reflective-puddle tile mask stamped by the puddle shader (+55 us instead of +40: 64 fragments per tile storing to
  one address, and a sparse 1-in-16 variant no better); the composite's cost was never its atomics.
- Resetting the key after reading it in the water shader (races for the displaced lookup; hidden puddle fragments
  never reset theirs).
- Separate barriers for the moving pass (it now rides the water's).

Patched-but-off shaders (reflections off in the options while the shaders carry the lookups) measured a few us dearer
(composite +3, water +8.5, two samples each): the shaders are patched only when the game starts with reflections on.
Render thread: no per-draw `glGet` (NVIDIA's threaded driver syncs on each); the bound program comes from
`ShaderHelper.currentlyBound`, uniform locations are cached, the viewport is read once a frame.

Frame-time tails: river p99 6.34 vs 6.05 ms, rain p99 within the run's noise (peers' encodes ran beside several of these
runs; the table's medians repeat across runs, the tails did not).

Stills (lossless `--shot-at`, zoom 1): `ssr-shot-river-on` / `-off`, `ssr-shot-rain-on` / `-off` (harness/runs, local).

## Not done / open

The follow-up list with the ideas not tried yet: `docs/plan-reflections-followup.md`.

- AMD / Intel (Mesa) and Windows untested: the patches need GLSL 1.50 compatibility + ARB_shader_image_load_store
  (Mesa has both); macOS stays off (GL 2.1).
- Water on levels above 0 and puddles on roofs are not reflected (one plane, level 0).
- Only chunk-texture surfaces and moving objects scatter; per-frame translucent sprites (per-frame trees when
  `treesInChunkTexture` is off, fire, particles) do not.
- The stock water shader itself costs 1.4 ms at 5K on a river view (noise loops per pixel): the largest GPU item in these
  scenes by far, and a candidate for its own pass.
