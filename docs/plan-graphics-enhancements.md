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

### 2. Wind on foliage

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

### 3. Screen-space reflections on water and puddles

The scene mirrored into rivers, lakes and puddles, on top of the HDR glints.

- How: water is a flat plane at a known height; in an isometric view a reflection is the baked texture sampled
  mirrored about the shoreline along the view's vertical, faded by distance, broken up by the water's own normal
  animation. Puddles reuse the same lookup through `puddleVbo`'s squares.
- Cost: one texture read per water pixel in the water shader; the puddle shaders are already ours.
- Rig: the Riverside pier spot (`start=6445,5195`) at dawn and at night with lamps.

### 4. Light shafts (god rays) through windows and canopies

Sun shafts in dusty interiors and under trees, lamp cones in fog.

- How: the fog pass already has a depth-aware fog buffer at 25 % resolution; a shaft is a radial march from the
  light's screen position through that buffer, masked by the height field (windows and tree gaps let light
  through, walls block it). Lamps and headlights use the same march from their known positions.
- Cost: bounded by the fog buffer size; one march per light on screen.
- Ships under the fog tick box's family, strength as a percentage like `hdrBloomPct`.

### 5. Per-pixel normals from the height field

Directional lighting on the tiles: the sun lights east-facing walls differently from south-facing ones, rims on
wall edges, wet-surface specular during rain.

- How: the AO kernel already snaps normals to the three planes tiles are made of; the composite multiplies a
  light term from that normal and the sun / light-source direction. Rain adds a specular lobe (the surface is
  darker and glossier while it rains, then dries).
- Cost: per-pixel in the composite, one depth gradient and a dot product; cheaper than the fog composite.
- Interacts with the per-square light map: keep the light map as the intensity, use the normal for the shape.

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
  next re-bake; only worth it together with 2.

## What does not map

- Tessellation and displacement need geometry to subdivide. Tiles have none; the character models are too low-poly
  and too small on screen for it to show.
- Subsurface scattering applies to skin and leaves. The only skin on screen is on models a few dozen pixels tall at
  play zoom; a wrap-lighting term on the model shader would give the visible part of it for free if ever wanted.
- Ray tracing: nothing to trace against; the height-field marches above are the isometric equivalent.

## Suggested first step

Item 1 reuses most of `pzopt.ChunkAo` (the bake hook, the neighbour depth context, the kept term across
lighting-only re-bakes) and lands as one more tick box. Measure it the way AO was measured: `devAoTiming`-style
route cost, the capped 120 km/h drive's frame-time tail against off, and a shot comparison for the picture.
