# Per-pixel lighting, 2026-09-25

Asked by the maintainer: "implement per pixel lighting ... try all state of the art and cutting edge solutions ...
secondary objective: virtually no performance impact; you can use the flip". Plan item 2 of
`docs/plan-graphics-enhancements.md`. Keys: `pixelLight` (off by default while it is judged), dev `devPplView`,
`devPplTiming`, `devPplDumpAt`. Class: `pzopt.PixelLight`. Rigs: `harness/ppl/` (dump loader, offline prototype).
Worktree `../PZ_Optimization-ppl`, branch `per-pixel-lighting`. Runs on the flip (Radeon 890M, 1920x1080).

## What stock does (measured on dumps)

`devPplDumpAt=s1,s2` writes every loaded square's cached lighting (JNILighting: vis bits, lightInfo, dark multipliers,
the eight corner colours `cacheVertLight`, the native's per-square light list `ResultLight`), every light source
(lampposts / placed lights, room lights, torches and headlights as sent to the native) and the scene depth + colour right
after the chunk composite (static world only). Runs `flip-ppl-dump1` (Rosewood house, 01:00, room lights on, torch, two
fires) and `flip-ppl-dump2` (street at night, torch, one fire, no room lights).

- **Every pixel knows its world position.** Window depth after the composite equals `IsoDepthHelper`'s `depthStart`,
  linear in x + y + 2z (0.0014434 per unit); screen x gives x - y, screen y gives x + y - 6z (world px = screen px x
  zoom + camera offset; 32 tileScale px per unit of x - y, 16 per unit of x + y). Three equations, three unknowns: the
  centre pixel of dump 1 reconstructs to (8147.06, 11508.14, -0.0003) for the player at (8147.04, 11508.14, 0); walls
  span z 0..1 of their level.
- **The light model of the bake.** Floors: the square's four corner colours (0 = (x, y), 1 = (x+1, y), 2 = (x+1, y+1),
  3 = (x, y+1)) interpolated across the tile; walls: bottom corners 0-3 and top corners 4-7 across the wall face;
  objects: the flat `lightInfo`. Dividing the dumped frame by that model gives a clean, evenly lit albedo (the whole
  house and garden as if in daylight, `harness/ppl/proto.py`): the model is complete.
- **The corners form a lattice.** 96 % of the corners shared by neighbouring squares hold the same colour; the rest
  are deliberate discontinuities up to 0.9 where walls separate a lit room from the dark outside. A level's top corners
  equal its bottom corners in 88 % of squares, but equal the next level's floor corners in only 57 % (ceilings), so
  both are kept.
- **The native light is quantised per square.** Plateaus and linear steps (ambient 41/255 at night, fire cones in
  steps of 13-32 per square, room lights flat at 229); the torch beam's edge follows the square grid (stair steps on
  the grass), which is what "blocky light" is. `lightInfo` equals the corner mean in 90 % of squares.
- Per-square light lists: room lights (flags 1, radius 7, position = the nearest room-light cell), point lights
  (flags 0, the source colour), torches (flags 2, the colour attenuated at that square).
- `IsoGridSquare.getLightInfo()` returns a cached *reference* to JNILighting's `lightInfo` object (set by
  `cacheLightInfo`), while corner colours go through the `lightverts(i)` method.

## Design (step A: deferred light, stock-equivalent)

1. **Unlit bake.** While a chunk texture bakes (`FBORenderChunkManager.isCaching()`), every square of that chunk hands
   out white light: `lightverts` returns white and the aliased `lightInfo` object holds (1, 1, 1) with the real values
   kept aside (a lazy refresh during the bake reads and writes the real ones). Trees of the tree pass bake white too.
   Blacked-out room fades, tints and custom colours stay in the texture (they multiply).
2. **No lighting re-bakes.** A square whose light is re-read marks its chunk level's lattice block dirty instead of
   invalidating the texture; only a change of the visibility bits (canSee / couldSee / seen, which also decide object
   alphas in the bake) still re-bakes.
3. **Lattice.** `GL_TEXTURE_2D_ARRAY` RGBA8, a 2x2 texel block per square (one bilinear fetch interpolates the four
   corners, discontinuities stay exact), a bottom and a top layer per level, toroidal in x / y (the chunk grid rounded up
   to a power of two, 128 squares on the flip: 256 x 256 x 32 layers = 8 MB) and in z (16 levels). A dirty chunk level is
   two 16 x 16 texel uploads (2 KB).
4. **Pass.** Right after the chunk composite (the AO seam): a full-screen quad reads the scene depth, rebuilds the world
   position, takes the surface normal from the neighbour depth with the smaller step, nudges the position 0.02 squares
   along it into the owning square, and multiplies the scene by the interpolated lattice (two fetches on walls, one on
   floors).

## Log
- **01:52-02:14 dumps + step A.** Model fitted offline (above); step A built: unlit bake + lattice + a full-screen pass
  after the composite. Picture = stock (mean RGB 15.9 / 8.9 / 5.2 both); GPU-lit frame vs the offline model: mean error
  0.002. Cost of the separate pass on the flip (890M, 1080p): 409 us with five depth fetches, 255 us with one (derivatives
  for the level, a hair's nudge towards the viewer for the owning square). Bandwidth-bound: a full-screen read-modify-write
  of colour + a depth read is ~24 MB a frame; no shader trick makes a separate pass free on an iGPU.
- **02:40-03:00 composite mode.** The light moved into the chunk composite shader itself (`chunkShader.frag` replaced
  through the ShaderUnit hook, the `ChunkRenderShader` override sets the uniforms at the first chunk draw of a frame with the
  viewport as drawn; the lattice stays bound on texture unit 7). Trap: the game's `ShaderProgram.compile` runs
  `glValidateProgram` with every sampler on unit 0, and a `sampler2D` plus a `sampler2DArray` on one unit fail validation on
  Mesa (NVIDIA accepts it); `SceneShaderStore` then silently sets `chunkRenderShader = null` and the chunk textures are drawn
  unlit by the plain sprite shader. Fixed with `layout(binding = 7)`; `tools/hdr/glslcheck` now validates too.
- **Objects and the player's view.** Same-run A/B (`devPplToggleAt`, dumps 16 s apart): furniture in squares just outside
  the view was lit by the per-pixel light and black in stock. An unseen square's corners can be lit (shared with the lit
  neighbour) while its `lightInfo` is 0: stock draws objects with `lightInfo`, so that is where the native puts the
  player's view for objects. Now packed into the lattice alpha; pixels off the floor / wall grid planes are scaled by
  `lightInfo` over the corners' mean (`textureGather` of the alphas). Tile edge rows sit up to 0.005 levels low (black
  dashes at tile corners until the level margin became 0.006). Result: 98.6 % of pixels within 24/255 of stock, mean 1.5.
- **Cost, composite mode** (uncapped, static night scene, toggle mid-run, both orders averaged because the second segment
  is always faster): GPU 2.49 vs 2.40 ms/frame (+0.09 ms), fps within noise (game thread 98 % either way). Empty chunk-
  texture texels (alpha 0, cleared depth) are now discarded instead of blended (`pplDiscardClear`, same picture).
- **Step B, dynamic lights shaped per pixel** (`pplShape`): the frame's torches / headlights (from `LightingJNI.torches`:
  position, direction, cone, reach) and the nearest active point lights (lamps, fires) go to the shader (16 at most). Per
  pixel and light in reach: the analytic shape T (linear falloff, the torch's soft cone) at the pixel and the owner
  square's four corners; the corners are fitted as a + k T (least squares, k >= 0 per channel); the pixel gets
  k (T(p) - bilinear T). Exact at the corners, the light's own shape between them, and self-gating: a square the native
  occludes does not correlate with T, so k = 0 (no light through walls). Constraints found offline on the dumps: skip the
  source's own squares (shape undefined, corners saturated: dark diamonds by the player) and clamp to the range of the
  square's own corners (the shape redistributes light inside a square, never adds or removes it).
- The native's per-square `ResultLight` entry of a torch is its unoccluded intensity at that square (a smooth cone even
  behind walls); the corners are the occluded, saturated result.
- The flip's sudo password given in chat was refused, so no screen recorder there: `pzopt.FrameCapture`
  (`devCapture=start,seconds,fps,scalePct`) reads the presented frames back before the swap; `harness/ppl/capture.py`.
- **Temporal A/B on open ground** (`--flag start=8174,11542`, torch sweeping at 30 deg/s, `devCapture`): the corner-
  calibrated shaping (step B above) made stock's staircase *crisper*, not straighter, and left dashes along tile edges.
  Rejected. The native decides the cone per square, so fitting its corners can only sharpen its stairs.
- **Where the blockiness comes from.** `lightInfo` is the native's own light of the square (a smooth cone: 83, 120,
  140 ...); each corner is ~the max of the four squares around it. That max-dilation is the staircase. Reconstructing
  from the square values at the centres (edge-aware: a neighbour only counts when it shares its corner colours, which the
  native breaks at walls) gives smooth beam edges. A second calibrated shaping on those centre samples was discontinuous
  between dual cells (checker along the edge): rejected too.
- **The native's light decomposes exactly.** `lightInfo = ambient + max over torches of the torch's per-square entry`
  (residual 1e-8 on 105 unsaturated squares); point lights: `max(ambient, colour x (1 - d / r))` per channel (a radius-8
  fire at d = 6: red 0.25 exactly, green / blue below the ambient). The torch entry is its unoccluded intensity at the
  square (listed even behind walls and in unseen squares, where the native then does not add it).
- **v3 (current).** Per square the CPU stores the base (`lightInfo` minus the torch, the ambient under saturation), the
  connectivity and whether the torches light it for the native (lit, or the model says none reaches it). Per pixel: the
  base between centres + the brightest torch from its analytic model (`PixelLight.torchModel`: fitted to the entries,
  mean error 0.04: falloff^1.15, angular ramp from the cone cosine to 0.95, 1.76 x strength) x the interpolated
  visibility; point lights as `max(light, colour (1 - d / r))` with their visibility read from the centre samples
  (the native's value vs the prediction); walls keep the corners' vertical gradient; the lights' share shaded by the
  surface's facing (normals from the depth, snapped to the floor / wall planes: the tile edge rows tilt them). Result on
  open ground: a straight-edged cone from the torch hand, following the aim every frame, where stock shows a blocky blob.
  Being faithful to the square values also removes stock's half-square light spill through walls (a lit room's grass
  strip outside), so night scenes are darker than stock (mean 16.8 vs 24.7 in the fire scene).
- The harness quits when the route and its hold are done: toggle A/Bs need `hold=` longer than the last toggle.
- **Cost of v3** (flip, uncapped, `devPplToggleAt` A-B-A-B with `hold=125`, `harness/ppl/split.py`; the first segment is
  always a warm-up, so stock segments are compared with the neighbouring per-pixel ones). First v3 (8 texel fetches a
  fragment): GPU +0.25 ms (house, static), +0.38 (house, torch spin), +0.56 (street, torch spin), fps -7 to -15 %.
  Fast path (base light and torch visibility share an RGBA texel, the connectivity bits have their own; a fully connected
  pixel costs one connectivity fetch and one hardware-bilinear fetch): street torch spin **stock 381.8 fps / 1.96 ms,
  per-pixel 399.8 / 1.92, stock 410.1 / 1.78: within noise**. CPU packing 9-29 us a frame.
- **Screen-space torch shadows in the composite** (`pplShadows`, 10 steps through the chunk texture's own depth): GPU
  +1.2 ms, fps -30 % on the street spin (every torch-lit fragment of every overlapping chunk texture marches). Off by
  default until made cheap.
- **Vehicle lights** (`--mode drive ... vehicle=Base.CarNormal headlights=on`, time 01:00). Headlights reach 36 with
  strength 0.75, cone 0.75, `focusing` 20; their entries fit a different law: 1.57 x strength, falloff^1.57, cosine ramp
  from the cone's - 0.28 to 1.0 (mean error 0.055). A moving car's native footprint lags a lighting pass behind the
  car, so taking the native torch out and putting the model back left a colour-shifted hole (the red tail lights
  subtracted) and a doubled beam: vehicle lights now stay in the base and the model only adds its shape between the
  centres (`T(p) - bilinear T(centres)`), faded out within 2.5 squares of the source (steep there) and capped at +-0.15.
  Handheld torches keep the full replacement.
- **Fog of war.** With "no torch entry: trust the model" the analytic torch lit rooms the player has not seen (the
  native lists nothing there): without an entry the model may only light squares the player can see (`canSee`).
  Unseen rooms stay black at max zoom again (mean 15.0 vs stock 15.8).
- **A drift-free cost metric.** `devPplTiming` now brackets the chunk composite with timestamp queries in both modes
  (`harness/ppl/ctime.py`): the flip's clocks move frame rates by +-15 % between segments, more than the effect.
  Night house with torch and fire: composite 1290 us per-pixel vs 990 us stock (+294 us). Stock's own composite is ~1 ms
  on the 890M (overdraw of chunk textures, `gl_FragDepth` disables early depth tests), so every per-fragment instruction
  is paid a few times per pixel.
- **Temporal shadow mask** (`pplShadows`, replacing the in-composite march): after the composite a half-resolution pass
  marches from every pixel towards the player's torch through the scene depth (16 fixed steps, no noise), a 3x3
  depth-aware blur, and the next frame's composite reads it by projecting the fragment's world position into the
  previous frame's screen (one bilinear fetch).
- **Measurement corrections.** (1) The first segment of every run is the slowest (warm-up); `ctime.py` averaged it into
  "on" (every toggle run starts per-pixel). (2) Timestamps measure GPU wall time and the 890M's clock drifts within a run
  (+-150 us on a ~1 ms composite between segments of one mode); only interleaved segments compare. (3) Shader ablation in
  one run (`devPplCostAt`, per-pixel mode throughout, night house): full lighting 966 us, light off 955, position only
  922, no edge path 917, no dynamic lights 919, no normals 911: **the lighting math is within noise**. The on / off
  difference of the toggle runs therefore comes from what only the per-pixel mode does inside the timed bracket: the
  frame's uploads into a 32 MB texture array the GPU is still reading (Mesa copies or waits).
- **The program cache.** After a pass of mine `glUseProgram(0)` left the game's `ShaderHelper` believing the chunk program
  was still bound; the next composite then ran with program 0, which in this compatibility context is fixed-function
  texturing: the unlit albedo, bright. The shadow-mask run showed it (and its cost number was invalid). Both passes now
  call `ShaderHelper.forgetCurrentlyBound()`.
- **Smaller data.** The corner lattice was only still used for walls' vertical gradient: now a per-square wall-gradient
  texel (top corners' mean minus the bottom's). A chunk level is 768 bytes in three 4 MB arrays instead of 2.5 KB in
  40 MB.
- **Occupancy is the cost.** A later ablation with the light switched off by uniform (same program) saved ~250 us, but
  a program without the light code saved more: the price is registers, not instructions. Mesa's shader stats
  (`MESA_SHADER_CACHE_DISABLE=true AMD_DEBUG=ps,stats` + a one-draw GLSL harness on the flip): stock chunk shader 16 VGPRs
  (16 waves per SIMD), the per-pixel shader 64 (8 waves). The composite is latency-bound (texture fetches from chunk
  textures with heavy overdraw), so halving the waves in flight shows up almost one to one.
- **Program variants.** A chunk texture with no dynamic light in reach draws with `pzopt_chunkBase` (the same shader
  compiled with `PPL_BASE`: base light and walls, no light loop): 32 VGPRs, 16 waves. The dev views (`devPplView`) are
  compiled in only when asked (`PPL_DEV`): they kept intermediate values alive to the end of the shader. The vehicle
  lights' residual sharpening (four extra model evaluations for an edge barely visible under the native's own pool) is
  gone; vehicle lights still light the wet glints. Full variant 64 -> 48 VGPRs.
- **The one-fetch path ran almost nowhere.** Heat map (`devPplView=10`): 99 % of the pixels took the masked four-fetch
  path. Two bugs and one design gap: the connectivity test read a missing neighbour's corners as white (-1) and broke
  every link to it (sentinel `NO_SQUARE` now); the torch-hidden rule ran for squares far from any torch; and at night
  every square the player cannot see near a torch has its torch visibility at 0, which the single "simple" flag counted
  as not simple. The flag has three states now: 255 simple + torch visible, 0 simple + torch hidden, 128 not simple.
  Only a neighbourhood of four 255s (or four 0s) filters to exactly 1 (0), the extremes of a weighted mean, so one
  bilinear fetch tells the shader both the light and the torch visibility there.
- **Texture units.** The shadow mask sat on unit 4, which the fog pass binds between chunk composites (and HdrGlint uses);
  the mask read the fog's texture: black nearly everywhere. The per-square arrays sat on 5-7, which the HDR passes use
  (`Hdr.LIGHT/BLOOM/STATS_UNIT`, `HdrLight` binds 5 during the world render): with `hdrAuto` on (the players' default)
  the lighting could read an HDR texture. All pixel-light samplers now use units 9-12, which nothing else binds.
- **The game renumbers sampler2D uniforms.** Still black after the move: `ShaderProgram.onCompileSuccess` walks the active
  uniforms and sets every `sampler2D` after the first to units 1, 2, 3... (`layout(binding)` is lost; the
  `sampler2DArray` ones are skipped, which is why the per-square arrays worked). The game re-sets its own samplers when
  it binds their textures; the mask's unit is now set by `glUniform1i` the first time the program is seen. The mask
  itself (read back by the dump, `<tag>-lit-mask.bin`) was right all along: its depth channel equals the scene depth
  at half resolution.
- **Tiled light lists.** The composite cost grew during a run (night house: +20 us over stock at 35-65 s, +260 us at
  125-165 s) as the fire spread: every lit pixel looped over up to 16 lights. Each chunk texture now gets the lights
  whose reach touches its rectangle and levels (one bit each, `pplSel`, sent per draw only when it changes); the
  shader walks the set bits (`findLSB`), a loop that is uniform across the draw.
- **Torch shadows, working** (runs `ppl-sh3` view 9, `ppl-sh4`): furniture casts contact shadows away from the torch (each
  chair's legs, the table). Screen-space limits that keep `pplShadows` opt-in: a cut-away wall's stub is in the depth
  buffer, so the torch light the native lets past a cut-away wall (the grass beyond the room's south wall) is taken out;
  only the front-most surface exists, so shadows behind tall objects are guessed by the thickness window. Cost unchanged,
  the half-resolution march + blur ~160 us on the flip.
- **A drift-free A/B** (`devPplAlternate=start,period,maskA,maskB`, `ctime.py` reads it): the cost mask flips every 2 s and
  the composite timer sums each mask apart, so both halves share every minute's clocks; cost bit 256 draws with a copy of
  the stock chunk program (`pzopt_chunkStock`) on the same unlit bakes, i.e. exactly the per-pixel shader's price. The
  toggle runs (pixelLight on / off, 30 s segments, a re-bake at each switch) had understated it. Night house with torch
  and fire (`ppl-alt1`): **+268 us** median (stock program 815 us); day house (`alt2`) +258; night street spin (`alt3`) +102.
- **Ablation, same method** (night house, each against the stock program): light-free variant everywhere (bit 512) +45;
  full program with the light switched off by its uniform (64) +147; no dynamic lights executed (1) +142; full +268.
  About 100 us is the full program's register count alone (48 VGPRs = 10 waves vs 16), about 120 us the light math.
- **Pass mode re-measured** after the fast-path fixes, now with the stock composite program (the patched one cost its
  registers even with the light off) and bit 1024 = no pass: +267 us night house, +257 night street. Never better than
  the composite (+268 / +102): the full-screen pass pays the full program on every pixel plus the colour read-modify-write.
- **Program variants per draw.** The full program's registers come from the light loop as a whole (no single part: 48;
  without the loop 32). Each chunk draw now picks a program compiled for the kinds of light its list holds
  (`PPL_NO_POINT`, `PPL_NO_TORCH`, `PPL_NO_WET`, `PPL_NO_MASK`; compiled on first use from the one placeholder,
  `pzopt_chunkBase`): torch only / lamps only / both, dry, 40 VGPRs (12 waves); a vehicle light without rain needs
  nothing (its model only lights wet glints), so those chunks take the light-free program.
- **Why the day house cost as much as the night.** The bench save's pistol carries an always-on weapon light (strength
  1.5, 15 squares; `torch=off` cannot switch it off), so a torch is in the table day and night and half the chunk
  textures took a lit program. In daylight its light sits under the clamp. The pack now records per chunk level whether
  every square's light is saturated and whether every square hides the torch (fog of war); a chunk texture whose levels
  and eight neighbours carry the flag leaves those lights out of its list (`pzoptPplFlags` on `IsoChunk`), which usually
  leaves it on the light-free program. Wet glints go on top of saturated light, so the saturation cull is off in rain.
- **Lamp shapes** (`pplPointLights`, runs `ppl-pl-on` / `ppl-pl-off`, night street, lamps and a fire): 2 % of the pixels
  differ by more than 8/255, most of it the fire's flicker between the two runs; the smooth native field already draws
  lamps well. Kept on for now; its cost decides.
- **Culling, first cut** (`ppl-cu1..3`): the saturation / hidden flags fired almost never (0 lights culled at night,
  48k draws' worth in daylight, costs unchanged): noon daylight is not saturated, and "hidden" failed on every chunk
  holding a square beyond the torch's reach (those keep a visibility of 255 by design). Now "hidden" counts squares no
  torch reaches, the chunk's own squares decide it (not the eight around), and torches / headlights are tested against
  the chunk rectangle with their cone (the shader's ramp start minus 0.05, the spill at the holder's feet, 3 squares of
  margin for tall sprites drawn into a neighbour's texture): the reach circle admitted every chunk within 15 squares,
  the beam covers about a third of that.
- **Rig `devPplTint`**: every chunk program tints its pixels (light-free green, torch red, lamps blue, torch + lamps or
  the full program yellow), so one dump shows whether the torch-lit area lies inside torch-program chunks.
- **Culling verified** (`ppl-tint1/2`, `devPplTint`): the whole torch beam lies in torch-program chunks, light-free ones
  start where it cannot reach. Lit draws fell from about half to 10-30 %.
- **Program switches are free** (`ppl-sw2`: two identical light-free programs alternating draw by draw vs one: +2 us).
- **Where it stands** (drift-free, vs the stock program on the same bakes): night street spin +60 us; night house with
  torch, no fire +161 (`ppl-hl4`: 10 % of the draws lit; the light-free part is about +45, so the torch chunks around
  the player, the middle of the screen, cost about 115); with a spreading fire +200-260 depending on the run.
- **Coloured ring in the rain (fixed).** Under the storm's bright ambient (0.47) a purple / teal ring of squares
  appeared where the torch's reach ends (`ppl-rain`, light-only view `ppl-rainv1`, base-only view `ppl-rain11`): not the
  glints, not the lightning (`thunder_secs=1000`), not the puddles, and the per-square arrays read back by the dump
  matched the native exactly on the ring. The base view showed it: where the native light clips in some channels (a
  warm torch clips red, then green) the pack replaced only those channels with the ambient estimate, and that estimate
  was the smallest light of any seen square (14/255 here, a dark corner; the real ambient 121). Now a square with any
  clipped channel takes the estimate in all three, and the estimate is the most common light among the seen squares
  without torch light, outdoors and indoors apart (outdoors without a lamp every square has exactly the ambient).
- **Torch-chunk registers** (radeonsi stats): the torch variant stays at 40 VGPRs (12 waves); only dropping both the
  surface facing and the edge path reaches 32. Tried without effect on the count: the torch visibility moved into the
  info texel's alpha (non-simple squares 192 visible / 64 hidden; kept: the edge path does four fetches instead of up to
  eight), the edge path as coordinate selects plus four independent fetches (kept: no chained values), the normal
  computed after the edge path (reverted: the compiler hoists the derivatives anyway, and it lost the transparent-texel
  skip). Same-run ablations (`ppl-abn4`, `ppl-abn1`, medians): the facing term costs 0 us, the torch math about 28.
- Two more tries on the torch variant's 40 registers, measured and dropped: the torch cone read from a texture instead
  of computed (a world-space torch map; still 40, so the map is not worth building), and the edge path's four fetches
  forced one after another (still 40).

## Where it stands (build 51, 2026-09-25 midday)

Drift-free, per-pixel vs the stock chunk program on the same bakes (`devPplAlternate=30,2,0,256`), flip (Radeon 890M,
1920x1080, uncapped), medians of the 3000-frame lines:

| Scene | Stock composite | Per-pixel | Lit draws |
|---|---|---|---|
| Night street, torch, spinning (`ppl-r3`) | 647 us | +50 us | 33 % |
| Night house, torch, no fire (`ppl-r1`) | 719 us | +153 us | 11 % |
| Day house, weapon light, no fire (`ppl-r2`) | 730 us | +187 us | 16 % |
| (earlier, full program everywhere: night house with fire, `ppl-alt1`) | 815 us | +268 us | 100 % |

What carries it: the light-free program on every chunk texture no light can change (+45 us if it drew everything),
per-draw variants for the rest, cone / reach / saturation / fog-of-war culling of each chunk's light list, merged
point-light clusters. Left: the torch chunks around the player at 40 VGPRs (12 waves vs 16).

Visual state: smooth light between square centres that stops at walls; torch and headlight beams from their cone,
following the aim each frame; fog of war as stock; no light through walls (stock bleeds half a square); FSR, upstairs,
zoom cycle, storm with lightning and wet glints checked by dumps. Opt-in: `pixelLight` (off by default), `pplShadows`
(experimental screen-space torch shadows, +160 us).

## Final check: Jev walks a restaurant at night (2026-09-25 15:30, desktop, run `ppl-restaurant`)

Build 53 on master 804159c, 5120x2160, `explore=restaurant explore_match=pizzawhirled director=jev`, 23:00, hand torch,
`lights=on` (it now also switches the restaurant's lights on when the walk comes within 30 tiles of it: 12 switches;
`Explore.tick` -> `Scene.lightsOnAround`), recorded. Jev walked all 9 ground-floor rooms (661 decisions); 300 fps,
p99 4.1 ms, p99.9 6.3 ms. `harness/ppl/stability-judge.py` (new: parity-judge's measurements per 10 s segment of one
recording, Jev classifies): transients at most 0.05 per mille of the frame (no blinking objects); Jev "lighting
patchwork" at low confidence (0.40 vs light_flicker 0.33, stable 0.23, look needed 0.52), worst 185-195 s. Looked at: every
flagged brightness step is the building cutaway (the roof hiding / showing) as the walk crosses a doorway: 112.8-114.4 s
at the front door, 178.3-178.7 s leaving by the back for the bathroom, and one 0.1 s roof flash at 189.8 s while the
path grazed the front door outside. Not lighting; whether stock flashes the roof the same way at that spot was not run.
The lit frames inside (torch beam through the front windows, lamps in the kitchen and break room) are steady.

## One-frame whole-screen flashes at chunk crossings, fixed (2026-09-25 evening, flip)

Maintainer's report on the flip (release dc24455, `pixelLight` on): textures flicker at the top left while walking in
circles. Rig: `explore=circle` on a copy of the save (`harness/CLAUDE.md`, circle-walk flicker rig), `devCapture` of every
presented frame, `region-flicker.py` + `region-flicker-judge.py`. The flashes are single frames of the whole screen: the
static world lit from the wrong squares (hidden rooms lit, lit rooms black; frame 596 of `flip-circle-cap-opt-*`: every
chunk texture black, only the per-frame sprites left). 7 burst frames in 16 s with `pixelLight`, 0 with it off; `pplMode=pass`
10 and `pplVariants=false` 5, so neither the composite program nor its variants. They came in pairs once per lap, where the
circle crossed y = 10312, a chunk boundary.

`devPplTrace` (new dev key: one game-thread line per frame with the lattice packs and the mapping inputs, one render-thread
line per frame with the uniforms applied and the first chunk draw's `chunkDepth`) showed it: at the crossing the render
thread renders its last state again while the game thread is late (the chunk-map shift), 72 replays in 16 s. `Frame.render()`
set `free = true` after its first render, so the game thread had already refilled that Frame with the next frame's camera;
the replay applied the new origin (`d0` one centre chunk later) to the old list's chunk depths: the reconstructed position 8
squares (nearly a level in z) off for one frame. Fix: a Frame is freed only in `postRender()` (the state is recycled,
`GenericSpriteRenderState.clear`, after which it cannot be replayed), and its chunk keys stay until then (a replay found them
nulled: `chunk=?`). Runs `flip-fl-fix1-*` (trace: 72 replays, 0 depth mismatches) and `flip-fl-final-fix-*`: 0 bursts in 16 s,
stock control `flip-fl-final-stock-*` 0. Jev: before the fix vs stock `pzopt_flicker` 0.97; after `no_flicker` 0.83.
The 0.1 s roof flash at 189.8 s of the restaurant walk above may have been the same replay.

## Grid lines along the chunk edges, fixed (2026-09-25 night, desktop)

Maintainer's report: grid square lines on the ground with AO, per-pixel lighting, sun shadows and HDR on. Shot rig (Rosewood,
`--flag start=8147,11507 --flag zoom=1 --flag route=S:30 --flag speed=1 --flag time_of_day=12 --flag weather=clear
--route-seconds 8 --shot-at 3 --prop overlay=false`), one feature off per run (`gl-*`): the lines are `pixelLight` alone (AO,
sun shadows and HDR off: same lines; `pixelLight=false`: none), thin dark lines every 8 squares, i.e. along the chunk
texture edges. `devPplCostAt=0:8` (constant light) and `0:16` (no light) clear them, `0:2` (no edge path) does not: the
light lookup. At render resolution each line is dotted single pixels (DLSS widens them to 2-3 px). The chunk depth
textures are `GL_NEAREST` DEPTH16 (a gather over the covered texels changed nothing: no filtering). Cause: along the chunk
edges the tile edge rows sit deeper than the 0.006-level tolerance of `lz = floor(P.z + 0.006)`, so they read level -1, a
black lattice (the final depth of a seam pixel reconstructs at z -0.001..-0.004, the other chunk's edge fragment on top).

Fix: a per-draw uniform `pplLv` (the chunk texture's `getMinLevel()` / `getTopLevel()`, `Gl.selectLevels`) clamps the light
level to the levels the texture holds. The top bound also stops crowns of trees (z >= 1) in single-storey chunks reading the
empty level-1 lattice (the black trees). Rows the tolerance lifts a level (`P.z < lz`: a floor's edge row a hair low, or the
top row of a wall of the level below) keep the brighter of the two squares' light (an unseen upper floor put dark dots along
wall tops and a jagged dark edge where floors meet cut-away walls).

`harness/seam-lines.py`: seam pixels = dark ridges on straight iso-diagonal runs, "new" against a pixel-aligned control
without the feature, "long" = runs of two squares and more. New long seams per MP, control floor 0-6: all four on 1,700 ->
28 (`gl-all` -> `gl-fix4-all`), `pixelLight` alone 2,030 -> 21 (`gl-fix-pplonly` -> `gl-fix4-pplonly`); ~80 % of what is
left is one faint line under a single exterior wall's top trim. Jev before the fix: `before_has_lines` 0.92-0.95; after:
`kind` fixed 0.49 / still_lines 0.50 (all on), fixed 0.58 (`pixelLight` alone). Remaining: a thin dark rim on some sprite
tops (lamp shades); odd-level floors of a two-level texture could in principle still drop to the level below at a chunk
edge (the brighter-level rule covers it when the lower square is darker).
