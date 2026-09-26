# Sprite filtering that ends the blur (candidate A), 2026-09-26

Candidate A of `docs/plan-graphics-enhancements.md`: "B42 looks blurry" (demand #2) and the soft picture zoomed out
while driving (#6), at no frame cost. `pzopt.SpriteFilter` (+ `pzopt.SpriteMips`), Enhancements tab section "Sprite
filtering", off (`spriteFilter=stock`) by default like every other change of the picture. Desktop, RTX 4090,
5120x2160, NVIDIA GL, upscaler off, `--launcher direct`, private options file (`-Dpzopt.userOptionsFile`).

## Where the blur comes from

The static world reaches the screen through the chunk composite: every chunk-level texture, baked at one texel per
world pixel, is drawn at 1 / zoom (`FBORenderChunk.renderInWorldMainThread`, our `FBORenderCell`). Stock samples it

- zoom 0.5 / 0.25 / 1: nearest (texels are whole pixel blocks: crisp);
- zoom 0.75: GL_LINEAR magnification, 1.33x: the soft level ("blurry");
- zoom 1.25 - 2.5 (driving zooms out to 2.5): trilinear mipmaps: at 2.5 the LOD is 1.32, a fixed 68 / 32 blend of the
  2x and 4x box-filtered copies.

The camera is orthographic and the chunk textures axis-aligned, so every pixel of a draw has the same footprint and
the same LOD: trilinear's blend between two levels only ever adds a constant blur (and costs two bilinear fetches).
Tiles drawn per frame outside the chunk textures (`tileWithDepth`, `opaqueWithDepth`) use their atlas textures'
LINEAR / trilinear filters. The offscreen buffer is screen-sized, so the stock "screen filter" option only matters with
a render scale (the upscalers).

## What it does

- **Magnified, non-integer (0.75, zoom animations)**: texel-aware anti-aliased point sampling (d7samurai, Cole
  Cecil's sharp bilinear). The coordinate snaps to the texel centre and ramps across a texel edge over exactly one
  screen pixel, and the linear filter then returns the edge texels' coverage of that pixel (the exact box filter of a
  hard-edged texel grid; `SpriteFilterTest` checks the identity). `spriteFilterSharpnessPct` narrows the ramp,
  `spriteFilterKernel=smooth` makes it a smoothstep.
- **Whole multiples (0.5, 0.25) and 1:1**: the stock program, point sampling (already exact: every texel an even block
  of pixels). `spriteFilterIntegerAa` puts the ramp there too (smoother sub-pixel glides, a little softer: shot below).
- **Minified**: one explicit mip level instead of trilinear (see the rounds below for which taps).
- **Per-frame tiles**: variants of `tileWithDepth` / `opaqueWithDepth` with the same fetch (the footprint picks the path
  per draw), started only while the world framebuffer is bound outside a chunk bake.
- `spriteFilter=nearest`: point sampling at every magnified zoom, 0.75 included.

How it stays free: the zoom (over the render scale) picks one program variant per frame; the game's own StartShader of
the chunk program is re-pointed to it (`SpriteFilter.remap` in `TextureDraw`, no extra GL call); each variant is compiled
with only its own path (no branch, no extra registers); zoom 1 / 0.5 / 0.25 and "stock" run the stock program; the
LINEAR magnification the ramp needs rides the two glTexParameteri calls the game already makes on every chunk-texture
bind (`TextureID.assignFilteringFlags`). With per-pixel lighting (it owns the composite programs) the fetch is compiled
into its programs at launch and picks its path from the footprint.

## Rigs

- `--prop devSpriteFilterShotAb=true` with `--shot-at`: `shot-game.png` stock, `shot2-game.png` filtered, same held
  camera; `harness/spritefilter/shotab.py <run>` (changed share, gradient, Laplacian, crops).
- `--prop devSpriteFilterShotModes=a,b,c` + `--prop devCapture=4,22,3,100,gray`: the entries one after the other
  through the hold (four slots clear of the harness screenshots, which stall ~0.9 s each), `harness/spritefilter/modes.py <run>`: detail and change per mode, crop sheets.
- `--prop devSpriteFilterAlternate=20 --prop devSpriteFilterCycle=stock,floor,rgss4+skip,...` + `gpuSections=true`:
  every entry timed on the same route (GPU section `composite.<entry>`), and every other section attributed to the
  phase it ran in: `harness/spritefilter/alt.py <run>`. Entry syntax: mode `[@<lod bias %>] [+w<ramp sharpness %>] [+smooth] [+skip] [+box] [+lin]` (mode: stock, nearest, rgssa2, rgssa, floor, rgss4, rgss2, bias, trilinear).
- `harness/spritefilter/shimmer.py <run>...`: a `devCapture` of a moving camera, motion-compensated Laplacian residual
  (crawl and shimmer) against the frames' own detail.
- `tests/pzopt/SpriteFilterTest`: every define combination of the three patched programs compiles (glslangValidator),
  the ramp is the exact coverage, the sharp-mip weights.

## Measurements

GPU numbers are `GL_TIMESTAMP` sections (`gpuSections=true`), the composite pass per frame, with the variants cycled
every 20 frames in one run so every entry sees the same route (`alt.py`, 95 % bootstrap interval of the difference of
means). The uncapped spinning route (`spin-uncapped`, ~360-600 fps) and the 120 km/h south path drive at the widest zoom
(`drive-120-south`, 240 cap).

### Magnified (zoom 0.75): free

| run | composite on - off |
|---|---|
| `sf-cost-z0.75` (spin, alternate 30 frames) | +0.4 us (-6.2 .. +6.8), 518 us a frame |

The same fetch plus a linear filter where stock is linear anyway; the variant switch rides the game's own StartShader.

### Minified, round 1: Golus's RGSS as published (four taps at bias -1, trilinear): +203 us

`sf-cost-zmax`: +202.8 us (+174 .. +230), +25 % of the composite at zoom 2.5. Four trilinear taps plus the empty-area
probe are ten bilinear fetches against stock's two.

### Round 2 and 3: one explicit level, bilinear taps

The footprint is the same for every pixel (orthographic camera, axis-aligned textures), so the taps read one level with
`textureLod` (bilinear, no trilinear blend). Level rule `floor(log2(texels a pixel) - bias)`:

| entry (vs stock, same run) | zoom 1.5 (`sf-cycle-z1.5`) | zoom 2 (`sf-cycle3-z2`) | zoom 2.5 (`sf-cycle3-zmax`) |
|---|---|---|---|
| rgss4, level 0 (round 2 rule: bias 0.5) | -52 us | +65 us | +52 us |
| rgss4, level `floor(log2)` | -52 us | +45 us | -42 us |
| rgss4 + empty-area probe | -53 us | -24 us | -142 us |
| rgss2 | -53 us | -48 us | -171 us |

Past 2 texels a pixel, level-0 taps read 4x the texels of level 1 (bandwidth); from 2x the 2:1 box level is the right
prefilter anyway. The probe (one tap a level above the taps: its bilinear reach, +-2 texels of the taps' level, covers
every tap, +-1.75 at most) skips the four taps over the empty air of a chunk texture.

### Sharp level 1 (Lanczos-2 at bake time): dropped

`pzopt.SpriteMips` rewrote mip level 1 of each chunk texture baked while zoomed out with a Lanczos-2 2:1 downsample
(16 bilinear taps, clamped to the premultiplied range), batched once a frame before the composite with the world
frame restarted the way the game does after a bake (no GL query). On the drive (`sf-drive-cyc`, one run, per phase):
the pass itself 19 us a frame while streaming, and the composite reading a Lanczos level 1 +96 us against the same
taps on the box level (-93 us vs stock with the box level, +3 us with Lanczos). The likely cause is NVIDIA's lossless
render-target compression: the ringing-rich level compresses worse than the box level. With the one-level taps below
the box level is already sharper than stock's blend, so `spriteFilterSharpMips` stays as an option, off.

### Round 4: how many taps

At the level `floor(log2(footprint))` the pixel covers 1 - 2 of that level's texels, inside a bilinear tap's reach:
one tap is already filtered enough and four taps spread over the pixel only add blur (Golus's four taps assume the
level one step finer, 2 - 4 texels a pixel). New entries: `floor` (one bilinear tap) and `rgssa` (the rotated grid
spread by `clamp(texels a pixel at that level - 1, 0, 1)`: one tap at 1:1, the full grid at 2:1).

Drive, zoom 2.5, per phase (`sf-drive-modes`):

| entry | composite vs stock |
|---|---|
| floor | -59 us (-108 .. -1) |
| rgssa + probe | -96 us (-139 .. -52) |
| rgss4 + probe | -77 us (-120 .. -32) |
| rgss4 level 0 + probe | +108 us (+70 .. +149) |
| rgss4 level 0 + probe + linear light | +110 us (+66 .. +153) |

Held frame at the Rosewood house spot (`modes.py`, RMS Laplacian of the world; aliasing counts too):

| entry | zoom 2.5 (`sf-modes-z2.5`) | zoom 1.5 (`sf-modes2-z1.5`) |
|---|---|---|
| stock | 22.85 | 21.98 |
| floor | 27.15 (+19 %) | 29.08 (+32 %) |
| rgss4 + probe | 23.21 | 24.30 |
| rgss4 level 0 + linear light | 28.55, **seams** along the chunk diamonds | |

Level-0 taps spread over a 2.5-texel pixel reach across a chunk texture's transparent border, and two textures meeting
there each draw a partly transparent edge: a faint dotted line along the chunk boundary. The one-level modes show none.

Motion, 120 km/h drive at zoom 2.5, 1.5 s of full-size luma frames at 60 fps (`shimmer.py`, ~3,060 patches each):

| run | detail | residual (crawl / shimmer) | residual / detail |
|---|---|---|---|
| `sf-cap-stock` | 14.62 | 9.55 | 0.678 |
| `sf-cap-sharp` (rgss4 + probe, Lanczos level 1) | 13.68 | 7.68 | 0.545 |
| `sf-cap-floor` | 17.89 | 12.94 | 0.734 |
| `sf-cap-rgssa` | 17.80 | 12.69 | 0.719 |

floor and rgssa are 22 % sharper in motion than stock for 6 - 8 % more residual per unit of detail.

### Round 5: two adaptive taps (the default)

`rgssa2`: two taps along the diagonal, spread like `rgssa` (none at 1:1, a quarter of the pixel's diagonal each way at
2:1). Same run, same route, per entry vs stock:

| entry | zoom 1.75 spin (`sf-cycle2-z1.75`) | zoom 2.5 spin (`sf-cycle2-zmax`) | held frame detail at 1.75 (`sf-modes2-z1.75`) |
|---|---|---|---|
| stock | 794 us | 910 us | 22.42 |
| rgssa + probe | -0.4 us (-31 .. +28) | +9.5 us (-39 .. +47) | 27.82 |
| **rgssa2 + probe** | **-59 us (-83 .. -35)** | **-147 us (-179 .. -114)** | 28.94 |
| floor | -104 us | -183 us | 32.18, grainy (asphalt speckle) |

At 1.75 (level 0, the widest spread) one tap turns the asphalt grainy; rgssa and rgssa2 are clean and as sharp as each
other, rgssa2 at half the fetches. No seams in any one-level mode at 1.25, 1.5, 1.75 or 2.5.

## Result (defaults: `spriteFilter=sharp` when chosen, `spriteFilterMin=rgssa2`, probe on, mip trim on, Lanczos off)

| | stock | sprite filter | |
|---|---|---|---|
| zoom 0.75 held frame, Laplacian detail (`sf-house-z0.75`) | 5.48 | 6.29 | +15 % |
| zoom 1.5 held frame (`sf-modes4-z1.5`, rgssa) | 21.93 | 28.41 | +30 % |
| zoom 2.5 in motion, 120 km/h drive (`sf-cap-stock` / `sf-cap-rgssa2`) | 14.62 | 18.30 | +25 % |
| same, crawl / shimmer per unit of detail | 0.678 | 0.685 | same |
| composite GPU, drive at 2.5, same run (`sf-drive-final`) | 789 us | 789 us | -0.3 us (-26 .. +28) |
| composite GPU, zoom 0.75 spin (`sf-cost-z0.75`) | 518 us | 518 us | +0.4 us (-6 .. +7) |
| composite GPU, zoom 1.75 / 2.5 spin (`sf-cycle2-*`) | 794 / 910 us | | -59 / -147 us |
| drive, back to back (`sf-tail-stock` / `sf-tail-sharp`): fps, p99, p99.9 | 232.9, 8.0, 16.6 ms | 232.4, 8.3, 16.2 ms | noise |
| same: 1 %-low, GPU busy (sysmon), game thread, power | 118 fps, 55 %, 49 % of a core, 268 W | 112 fps, 55 %, 50 %, 273 W | noise |

The drive is not pegged at 240 (13 % of frames under the cap in both) with the GPU at 55 % and the game thread at half a
core: the known game-thread bursts at chunk arrival (`docs/plan-drive-game-thread.md`), unchanged by this pass.

- The magnified path adds nothing measurable; the minified path costs less than stock's trilinear blend on the uncapped
  route and the same on the drive. The bake mip chain is 2 levels instead of 3 (`bake.end` 52 vs 61 us per frame on the
  drive, same run).
- Per-pixel lighting: all its composite programs (the full one and the seven variants) carry the fetch; 0.75 held frame
  detail 18.41 -> 20.61 (`sf-ppl-off-z0.75` / `sf-ppl2-z0.75`). Its setting applies at the next launch there.
- FSR 1.0 at 67 %: zoom 1 is 1.5 texels a pixel in the scaled world pass and takes the minified path
  (`sf-fsr-z1`: 19 % of pixels changed, detail +19 %).
- First use of a variant costs ~0.8 s of compile: variants are compiled after a swap when the settings change (boot,
  Apply), 2-70 ms measured at boot here.

## Not done / open

- Mac (GL 2.1): off (no GLSL 1.50).
- The flip (Radeon 890M) was disconnected all session: no iGPU numbers. The paths add at most two fetches over stock's
  two, so the iGPU cost should follow the desktop's, but it is unmeasured.
- Character models are drawn per frame from meshes and are not affected (only sprite art is).
- SMAA / TAA for model edges (plan item 7) is a separate pass, not part of this.
