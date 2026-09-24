# Modern graphics enhancements that fit this renderer (2026-09-25)

Candidates for further visual features after the ambient-occlusion pass
(`docs/findings-ambient-occlusion-2026-09-24.md`), asked by the maintainer: "surface scattering, tessellation, soft
contact shadows, wind simulation, things that modern games have". Each entry says what the engine gives us, what it
would cost, and how it would ship. Nothing here is implemented; ordering is payoff per effort.

## What the engine gives us

- Tiles are pre-rendered isometric sprites baked into chunk textures (FBORenderChunk). There is no geometry for the
  world, so anything that needs a mesh (tessellation, displacement) has nothing to work on.
- The FBO depth is an exact world-space height field under an orthographic 2:1 projection (the AO pass's finding:
  `IsoDepthHelper`, 424.27 squares per unit of depth). That is the lever for shadows, normals, reflections and
  light shafts: every one of them is a walk over that height field, at bake time when the result is static.
- Characters, animals and vehicles are low-poly skinned models with normals, lit by the per-square light map.
- Lighting is per square (libLighting64), flat across the square; light sources (lamps, fire, headlights) are known
  to the engine. The HDR pass already expands highlights, adds bloom and glints (`docs/findings-hdr-2026-09-24.md`).
- The climate manager has wind speed and direction, cloud cover, fog, rain and snow; `fogPass` owns a depth-aware
  fog buffer and composite; `puddleVbo` owns the wet squares.
- Two GPU-side seams are already ours: the chunk bake (`FBORenderCell`, where AO multiplies in) and the composite
  (`pzopt.RenderScale` / `pzopt.Upscaler`, where a screen-space pass can run before the resolve).

## Candidates, in order

### 1. Soft directional shadows (the "soft contact shadows" ask)

Sun shadows cast by walls, trees, fences, furniture and vehicles, with a penumbra that widens with the distance
between caster and receiver (PCSS-style: blocker search, then a filter radius from the blocker distance).

- How: a ray march along the sun direction through the height field when a chunk texture bakes, the same
  neighbour-depth context the AO uses across chunk edges; the result multiplied into the texture next to the AO term.
  Sun direction from the time of day (the stock day cycle gives the elevation, the azimuth is a fixed choice per
  world since the map has no compass in its light).
- Time of day moves the shadows: a lighting-only re-bake already exists (`lightingRebake*`); the shadow term is
  recomputed on those re-bakes (a few per minute), never per frame.
- Characters: a projected shadow of the model onto the height field instead of the blob ellipse, drawn per frame
  as the shadow ellipse is today (`shadowPrep` already computes the ellipse off-thread).
- Cost: bake-time like AO (~1.25 % of GPU time in the heaviest streaming for AO); near zero on a still scene.
- Ships as a second tick box next to "Ambient occlusion", off by default (a deliberate change of the picture).
- Rig: the AO `--shot-at` comparison, a time-of-day sweep (`time_of_day=` flag) for the shadow direction.

### 2. Per-pixel lighting (light composed per frame instead of baked per square)

Today the light map is per square (libLighting64), applied when the chunk texture bakes: a lamp is a stepped
patch of squares, a flickering lamp or a torch that moves with the player re-bakes its chunks
(`lightingRebakeMs`, `lightingRebakeBudget`, the lightning-flash stalls, the `LightDirt` floods), and headlights
sweep in square-sized steps. Per-pixel lighting is the biggest "modern" change available and also a structural
performance win: light stops being a reason to re-bake.

- What the engine gives us: a depth texel plus its screen position is an exact world position (screen x gives
  x - y, screen y gives (x + y) / 2 - z, the depth gives x + y + 2z: three equations, three unknowns; the AO pass
  already works in that space). So the composite knows, per pixel, which square and level it is on, its snapped
  normal, and its distance to every light source. The engine knows every light source (position, radius, colour)
  and the sun.
- Phase A, shape only: keep the stock per-square light as the intensity, blend it across square edges per pixel and
  shape it with the per-pixel distance falloff of the nearest sources (normalised so a square's mean stays what
  libLighting says). Removes the blockiness, cannot leak light through walls because the stock map still gates it,
  no bake changes. Cheap.
- Phase B, normals: multiply a per-pixel `max(0, n . l)` from the height-field normal and each source's direction
  (sun included). East-facing and south-facing walls light differently, wall edges get a rim, rain adds a specular
  lobe (the surface darker and glossier while it rains, drying after).
- Phase C, deferred: the chunk texture bakes unlit (albedo only) and the light is composed per frame from a
  light-map texture (per square per level, written from libLighting's per-square results each pass, one small upload
  per dirty chunk level) sampled at the pixel's world position. Light changes never re-bake a chunk again: flicker,
  torches, headlights, lightning are per-frame and smooth, and the lighting re-bake machinery (budgets, held
  re-bakes, strong marks) goes away. This is where the game-thread and GPU cost of light disappears, not just the
  look.
- Phase D, shadowed dynamic lights: the height field gives, per light, a short march from the pixel toward the light
  (walls are tall depth steps), so headlights and torches cast real shadows and sweep across walls. Lights are
  clustered per screen tile (32x32 px tiles, a light list per tile, the N nearest) so cost is lights on screen x a
  few steps, in a half-resolution light buffer.
- What must stay from the stock map: the per-player "can see" gating (rooms the player has not seen are black), the
  indoor / outdoor ambient split, cutaway (removed walls are not in the height field, so light passes where the
  player sees through, which is what one expects).
- Cost: A and B are a few instructions per pixel in the composite, less than the fog composite. C trades the
  re-bake work for one light-map sample per pixel and small uploads. D is the one to budget: it scales with lights
  on screen (night in Louisville with many lamps); the tile clusters and the half-resolution buffer keep it bounded.
- Risks: the sprites carry painted shading, so directional light on top can double-shade (keep the strength a
  percentage, default modest); the Mac's GL 2.1 context (GLSL 1.20, the AO note) needs a fallback for C and D;
  peers' visual-parity judges compare pictures, so every phase is a tick box, off by default until it is judged.
- Rig: the night bench route with the lamp-heavy Rosewood main street, the storm preset for lightning (frame-time
  tail with the re-bake path removed vs today), the Louisville night preset for the light count.

### 3. Wind on foliage

Trees, bushes and grass sway with the climate manager's wind, with gusts, stronger in storms.

- How: a vertex-shader sway (a phase per sprite from its world position, amplitude from wind speed, bending from
  the sprite's base upward). Standard in every modern engine.
- The catch: trees are baked into the chunk textures (`treesInChunkTexture`, `treeBakePass`), so a static texture
  cannot sway. Options, to measure: (a) a separate tree layer per chunk level (the `TreeBake` pass already draws
  trees last, so it could target its own texture) warped per frame in the composite; (b) per-frame tree draws as
  stock does, only while the wind is above a threshold; (c) sway only the small foliage (grass, bushes) that is
  cheap to draw per frame and leave the trunks still.
- Cost: (a) one extra texture per exterior chunk level plus one warped blit; (b) is the per-frame cost the tree
  pass removed, so the storm scenes pay for it exactly when they are slowest.
- Rig: the storm preset with the tree-heavy spot of issue #5.

### 4. Screen-space reflections on water and puddles

The scene mirrored into rivers, lakes and puddles, on top of the HDR glints.

- How: water is a flat plane at a known height; in an isometric view a reflection is the baked texture sampled
  mirrored about the shoreline along the view's vertical, faded by distance, broken up by the water's own normal
  animation. Puddles reuse the same lookup through `puddleVbo`'s squares.
- Cost: one texture read per water pixel in the water shader; the puddle shaders are already ours.
- Rig: the Riverside pier spot (`start=6445,5195`) at dawn and at night with lamps.

### 5. Light shafts (god rays) through windows and canopies

Sun shafts in dusty interiors and under trees, lamp cones in fog.

- How: the fog pass already has a depth-aware fog buffer at 25 % resolution; a shaft is a radial march from the
  light's screen position through that buffer, masked by the height field (windows and tree gaps let light
  through, walls block it). Lamps and headlights use the same march from their known positions.
- Cost: bounded by the fog buffer size; one march per light on screen.
- Ships under the fog tick box's family, strength as a percentage like `hdrBloomPct`.

### 6. Cloud shadows

Soft cloud shadows drifting across the ground with the wind.

- How: two scrolling noise octaves multiplied into the outdoor squares in the composite, strength from the cloud
  cover and the sun height, direction and speed from the wind. No engine data beyond what the climate manager has.
- Cost: negligible. Very high mood per line of code. Check first that stock B42 has nothing similar.

### 7. Anti-aliasing and depth of field

- SMAA or a small TAA on the composite for players who do not run DLSS (FSR 1.0 and bicubic upscale do not
  anti-alias). Sprite edges and model edges are where the game looks dated at 4K.
- Optional tilt-shift depth of field from the depth buffer, a "miniature" look; cheap, contentious, off by default.

### 8. Smaller ones

- Wet and snowy material response: darken plus specular while raining, a snow tint on the up-facing normal in
  snowfall (stock swaps snow tiles; this would blend the transition).
- Eye adaptation between dark interiors and daylight (HDR options list item 11).
- Foliage interaction (grass pushed by characters and vehicles): decals into the baked texture, restored on the
  next re-bake; only worth it together with 3.

## What does not map

- Tessellation and displacement need geometry to subdivide. Tiles have none; the character models are too low-poly
  and too small on screen for it to show.
- Subsurface scattering applies to skin and leaves. The only skin on screen is on models a few dozen pixels tall at
  play zoom; a wrap-lighting term on the model shader would give the visible part of it for free if ever wanted.
- Ray tracing: nothing to trace against; the height-field marches above are the isometric equivalent.

## Suggested first step

Items 1 and 2A/2B are the first pair: both are one term in a pass we already own. Item 2C is the one worth a
proper plan of its own, since it removes the lighting re-bakes from the game thread as well as changing the look.

Item 1 reuses most of `pzopt.ChunkAo` (the bake hook, the neighbour depth context, the kept term across
lighting-only re-bakes) and lands as one more tick box. Measure it the way AO was measured: `devAoTiming`-style
route cost, the capped 120 km/h drive's frame-time tail against off, and a shot comparison for the picture.
