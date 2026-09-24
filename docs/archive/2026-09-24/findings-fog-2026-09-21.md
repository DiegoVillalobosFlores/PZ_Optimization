# Heavy fog at no cost — findings (2026-09-21)

What was asked: fog should cost nothing against clear weather, measured on the 120 km/h uncapped
drive after every change. What came out: stock heavy fog halved the desktop's frame rate
(512 → 256 fps, GPU-bound) and cut the laptop's to 38 % (259 → 98 fps, game-thread and GPU
bound); the fog pass (`fogPass`, `fogScalePct`, `fogMaskFrames`; `pzopt.FogPass`, overrides of
`ImprovedFog`, `ImprovedFogDrawer`, `MultiTextureFBO2`, a hook in `FBORenderCell.renderFog`)
brings the laptop to 223 fps and the desktop to 389 fps with the same picture.

Route for every run: `--mode drive --flag route=E:1200 --flag kmh=193 --flag zoom=max
--route-seconds 60 --prop uncappedFps=true --option uiRenderOffscreen=true --option fogQuality=0
--no-dashboard --no-mangohud --prop gpuSections=true`, fog runs add `--flag fog=heavy` (weather
period stopped, `FLOAT_FOG_INTENSITY` pinned at 1.0), the clear control `--flag fog=off` (same,
fog 0). Frame numbers are the in-game overlay log over the route window (`analyze.py`,
`overlay:`), GPU sections the periodic `gpu us/frame:` line. Laptop runs live under
`~/PZ_Optimization-fog/harness/runs/` on diego-flip (Radeon 890M, Mesa 26.2, 1920x1080), desktop
runs under `harness/runs/`.

## 1. Where stock fog spends its time

Every game file that touches fog was read (`ImprovedFog`, `ImprovedFogDrawer`, `FogShader`,
`fog.frag`/`fog.vert`, `FogParticle`, `IsoWeatherFX`, `WeatherFxMask`, `FBORenderCell.renderFog`,
`IsoCell.render` (legacy path), `ClimateManager`/`ClimateValues`/`WeatherPeriod` (values only),
`RenderSettings` (`fogMod`, unused), `SkyBox` (a uniform), `LightingJNI` (false match),
`IsoGameCharacter`/`IsoZombie` (sight range, game logic)). With the FBO renderer and
`fogQuality` 0/1 everything that costs is `ImprovedFog`:

- **GPU.** One screen-wide rectangle per tile row per level (two levels, 0 and 1), each 96 texture
  pixels tall while rows are 16 apart: every screen pixel is shaded by up to 12 rectangles, each
  doing 7 noise fetches, writing `gl_FragDepth` (which disables early depth rejection) and one
  blend; every rectangle is its own draw call with three uniform updates (~190 per frame).
  Desktop: 1.9 ms of a 3.9 ms frame. Laptop: 4.4 ms.
- **Game thread.** `FBORenderCell.renderFog` walks every on-screen chunk, every square of the
  level and its objects, per level, only to call `ImprovedFog.renderRowsBehind(square)` on the
  first floor object of each square: with the FBO renderer the rectangles are all drawn at
  `endFrame()` anyway, so that painter's-order interleaving did nothing. The row walk itself
  resolved the chunk map for every one of ~4.5k (laptop) / ~10k (desktop max zoom) squares per
  level per frame and touched every square object.

## 2. What the fog pass does

1. `renderFog` runs `startRender` / `endRender` per level and nothing else (the square walk is
   gone).
2. The row walk reads per-chunk masks of the squares that take fog (exterior and not in a room),
   packed per diagonal, refreshed every `fogMaskFrames` (20) frames staggered per chunk; whole
   all-fog or no-fog runs of up to eight squares advance in one step. Same rectangles as stock
   (92.7 segments per level in both walks). Laptop: 215 → 49 → ~35 µs per level.
3. The render thread draws all rectangles in one draw call (the per-rectangle uniforms are vertex
   attributes), with the depth from the vertex (early-Z works), into a fog buffer of
   `fogScalePct` % of the viewport, and composites it once with premultiplied alpha (exactly the
   sequential result, since every rectangle of a pixel has the same colour).
4. The noise texture is sampled with generated mipmaps through a sampler object: without them the
   scaled buffer's sparse fetches missed the texture cache and the draw was memory-bound
   (1.2 ms at 25 % on the laptop, the same at 50 %).
5. The offscreen buffer's depth is a DEPTH24_STENCIL8 texture instead of a renderbuffer
   (`MultiTextureFBO2`), so the fog pass reads the scene depth in place: below 100 % a reduction
   pass writes each fog texel the *nearest* depth of its block of screen pixels and the composite
   weighs the four nearest fog texels by how close their depth is to the pixel's own scene depth
   (only where the four straddle a depth edge). Without that, a nearest-sampled scaled depth
   picked one pixel per block and one-pixel power lines came out dotted at 50 % and 25 %;
   with it they are continuous (`harness/runs/shot3-*` on the laptop, bench-mode `--shot-at 8`).

## 3. Laptop, 1920x1080, 120 km/h uncapped (diego-flip)

| run | fog | fps | mean | p99 | p99.9 | game thread | GPU | fog GPU (blit / rects / composite) |
|---|---|---|---|---|---|---|---|---|
| clear120 | off | **259** | 3.9 ms | 13.5 | 24.1 | 84 % | 74 % | 0 |
| fog120-stockfog | stock (`fogPass=false`) | **98** | 10.2 ms | 23.8 | 34.5 | 98 % | 75 % | 4.37 ms |
| fog120-pass50 | pass 50 %, no mips | 170 | 5.9 ms | 16.5 | 25.1 | 94 % | 81 % | 1.63 |
| fog120-pass25 | pass 25 %, no mips | 172 | 5.8 ms | 16.3 | 24.5 | 92 % | 80 % | 1.43 (0.10 / 1.25 / 0.07) |
| fog120-mips25 | + mipmapped noise, mask walk | 241 | 4.1 ms | 14.2 | 22.6 | 87 % | 75 % | 0.33 (0.10 / 0.14 / 0.08) — dotted wires |
| fog120-bilat25 | + depth copy, min reduction, depth-aware composite | 186 | 5.4 ms | 17.6 | 25.8 | 95 % | 67 % | 0.68 (0.35 / 0.12 / 0.18) |
| fog120-edge25 | + scene depth read in place, edge-only depth read | **223** | 4.5 ms | 14.5 | 24.4 | 86 % | 74 % | 0.50 (0.20 / 0.13 / 0.16) |
| fog120-bilat50 | 50 %, depth copy variant | 165 | 6.0 ms | 18.7 | 27.9 | 93 % | 72 % | 1.42 (0.34 / 0.81 / 0.22) |
| fog120-final | the shipped defaults (25 %, in place, segment cache) | **223** | 4.5 ms | 14.5 | 22.9 | 86 % | 74 % | 0.50 (0.20 / 0.12 / 0.16); walk 22 µs/level |

On this laptop the CPU and the iGPU share one power budget, so a busier GPU slows the game thread:
the GameProfiler showed every game-thread section (chunk render, objects, update) ~40 % slower
in stock fog than in clear weather, not only `FBORenderCell.fog` (0.40 ms with the plain walk,
~0.1 ms with the masks). That is why the fps gap closes faster than the fog GPU time alone
predicts. Remaining on the laptop: ~0.5 ms of GPU (the depth reduction reads the full-size depth,
the composite reads the fog buffer four times per pixel) and ~0.1 ms of game thread.

## 4. Desktop, 5120x2160, 120 km/h uncapped (RTX 4090, NVIDIA GL, same build, 11:05–11:20)

| run | fog | fps | mean | p99 | p99.9 | game thread | GPU | fog GPU (depth / rects / composite) |
|---|---|---|---|---|---|---|---|---|
| fogd-clear | off (`fogPass` on, depth texture) | **447** | 2.2 ms | 9.6 | 15.6 | 55 % | 98 % | 0 |
| fogd-clear-rb | off, `fogPass=false` (depth renderbuffer) | 445 | 2.2 ms | 9.5 | 15.8 | 55 % | 98 % | 0 — the depth-texture swap is free |
| fogd-stockfog | stock (`fogPass=false`) | **220** | 4.5 ms | 13.9 | 19.3 | 61 % | 98 % | 1.52 ms |
| fogd-pass50 | pass 50 % | 329 | 3.0 ms | 11.0 | 17.1 | 51 % | 98 % | 0.39 (0.03 / 0.27 / 0.05) |
| fogd-pass25 | pass 25 % | 382 | 2.6 ms | 10.2 | 16.5 | 56 % | 98 % | 0.18 (0.04 / 0.07 / 0.05) |
| fogd-nodraw25 | 25 %, `devFogNoDraw` (no rectangles) | 405 | 2.5 ms | 9.6 | 16.4 | 59 % | 98 % | 0.11 |
| fogd-copy25 | 25 %, `fogDepthCopy` (blit instead of in place) | 356 | 2.8 ms | 10.5 | 17.0 | 54 % | 98 % | 0.32 (0.18 / 0.07 / 0.05) |
| fogd-cache25 / fogd-final | 25 % + segment cache (the shipped defaults) | **389** | 2.6 ms | 9.9 | 16.5 | 54 % | 98 % | 0.18 (0.04 / 0.07 / 0.05); walk 28 µs/level |

Game-thread walk: 59 µs per level per frame over 22k squares (masks), 28 µs averaged with the
segment cache (the walk runs about one frame in four while driving; the replay recomputes the
~197 screen rectangles and depths). Stock's square walk plus its per-square chunk-map lookups
were part of the 61 % game thread in the stock run; they are gone.

Why the frame time moves more than the fog sections: on this route the GPU spends ~1.0 ms of
every 2.2 ms frame baking chunk textures, i.e. ~450 ms of every second regardless of frame rate.
With T = F + 0.45·T, a per-frame addition ΔF costs ΔF / 0.55 of frame time: the pass's 0.18 ms
shows up as ~0.35 ms (447 → ~395 fps predicted, 389 measured), and the same amplification made
stock's 1.5 ms of fog into 2.3 ms of frame. The fog's own remaining cost is 0.18 ms of GPU
(rectangles 0.07, composite 0.05, depth reduction 0.04) and ~0.06 ms of game thread per frame.

## 5. Power-line flicker (open, 2026-09-21 afternoon) — the pass is marked experimental

The maintainer sees a slight flicker on diagonal power lines in heavy fog while the camera moves
(120 km/h drive; also when teleporting a few tiles back and forth at the bench route's quarter
point, x=8402 y=11254). What was found and changed on the way:

- The first composite (nearest-depth fog texel, 1/|Δdepth| weights) made wires *crisper* than
  stock and pulsed as they crossed fog-buffer blocks: at wire pixels the scene depth is larger
  (farther) than the pixels beside them (0.25 vs 0.13–0.14 six px away, `devFogDepthView=1`), so
  stock draws far more rows over a wire than over its surroundings, and a block reduced to its
  nearest depth cannot express that. Now each fog texel holds the fog in front of the block's
  nearest depth (depth-tested pass) and in front of its farthest depth (second pass, shader-side
  test against the R32F max), and the composite interpolates by the pixel's own depth
  (`fog.rects` 0.07 → 0.18 ms, `fog.composite` 0.05 → 0.08 ms; 389 → ~340 fps).
- Frame-exact captures after that change show no flicker: bench route 38 s (three diagonal wires,
  20 unique captured fps, wire contrast steady 89 → 82 while stock does 86 → 79); jitter rig
  `--flag hold=6 --flag jitter=1 --flag jitter_y=true --flag shot_burst=8` (new harness flags:
  `jitter_y` jitters the Y, `shot_burst=N` writes N consecutive in-game frames 1 s into the hold,
  `Screenshots/pzopt-burst-NN.png`) at zoom 2.5 and zoom 1 under the wire pair (pole ≈ 8381,11207):
  same-camera frames differ only at the player sprite, the two camera positions aligned differ
  only at the player sprite, pass and stock alike (`harness/runs/burst*-*/burst/`).
- The maintainer still sees "a bit" of flicker live at 240 Hz and asked to ship the pass as
  experimental, on by default, with the disclaimer that turning it off removes the power-line
  flicker (the tab text says so). Candidates not yet excluded: something at the display rate the
  60 fps captures average out, or the depth texture being read in place on NVIDIA
  (`fogDepthCopy=true` is the A/B), or `fogScalePct=100` (no blocks at all, ~0.8 ms).

## 6. Showcase video (13:00)

`harness/stitch-stormfog-sbs.sh` → `docs/media/drive-120kmh-storm-fog-stock-vs-optimized.mp4`
(AV1 10-bit PQ / BT.2020, 3840x810, 42 s, poster `.jpg`): 120 km/h through the heavy-fog
thunderstorm (`--preset storm-fog`, 6 lightning strikes on the route), stock (every optimization
off, `sbs-stormfog-stock-20260921-125922`) **60 fps mean, p99 72 ms, 49 frames over 33 ms** on the
left, all optimizations plus the fog pass uncapped (`sbs-stormfog-opt-20260921-130035`)
**191 fps mean, p99 16.5 ms, 0 frames over 33 ms** on the right, aligned at the car's motion onset.

## 7. What is left

- The composite is a full-screen pass (11 Mpx on the desktop, ~0.05 ms) and the reduction reads
  the whole depth once (~0.04 ms); both are fixed per frame while fog is up. A stencil of the
  rows that actually drew fog could skip the composite where nothing was drawn (indoors, black
  never-seen area), not on a foggy road.
- The 12 overlapping rows per pixel could share their three fog-noise fetches through a small
  noise pre-pass (7 → 4 fetches per fragment); at 25 % the rectangles are 0.07 ms, so it was not
  done.
- Visual difference to stock: none found at 1:1 (`harness/runs/shotd-*` desktop, `shot3-*`
  laptop: stock vs 25 % vs 50 %, bench `--shot-at 8`, heavy fog); the fog is animated by wind, so
  captures differ in pattern, not in character. Below 100 % the noise is mip-filtered, which is
  what a smaller buffer needs to not alias; at 100 % the sampler stays at level 0.
- `fogQuality=1` (options.ini "low") still halves the rows (`renderEveryXRow=2`) as stock does;
  the pass is the same otherwise. `fogQuality=2` (legacy fog circle + particles) is untouched.
