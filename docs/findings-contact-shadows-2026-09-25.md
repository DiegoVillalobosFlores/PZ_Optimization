# Soft contact shadows (sun shadows), 2026-09-25

Asked by the maintainer: "implement soft contact shadows ... state of the art, bleeding edge techniques; secondary objective:
virtually zero performance impact; don't discard any idea, implement them one by one and profile them; you can use this
computer". Item 1 of `docs/plan-graphics-enhancements.md` ("Soft directional shadows (the 'soft contact shadows' ask)"),
with D (characters sitting in the world) and the character half of E. Worktree `../PZ_Optimization-contact`, branch
`contact-shadows`, on master (per-pixel lighting e1b28ab included). Runs on the desktop
(RTX 4090, 5120x2160) through the queue, labels `cs-*`.

Keys: `sunShadows` (off by default: a deliberate change of the picture), `sunShadowStrengthPct` (45),
`sunShadowSoftnessPct` (100 = a 3 degree sun radius), `sunShadowCharacters`, `sunShadowVehicles`, `sunShadowTorches`
(all live); non-live `sunShadowCharacterPct` (100), `sunShadowTorchPct` (85), `sunShadowAtlas`, `sunShadowMarch` (off),
`sunShadowLengthPct` (800), `sunShadowThicknessPct` (100), `sunShadowSteps` (24, at the full length), `sunComputeBudget`
(1), `sunAzimuthDeg`, `sunMaxElevationDeg` (55), `sunMinElevationDeg` (12), `sunStepDeg10` (15); dev `devSunView`,
`devSunHour`, `devSunHourSpeed`, `devSunAlternate`; harness flag `crowd=N`. Classes: `pzopt.SunShadow` (the sun, the characters' shade factor), the sun term in
`pzopt.ChunkAo`'s kernel, `pzopt.CapsuleShadow` (characters and vehicles). Rig: `harness/contact/alt.py`.

## Three parts

1. **The static world, baked** (walls, fences, trees, bushes, furniture, parked scenery onto ground, walls and each
   other). Computed by the chunk AO kernel when a chunk texture bakes and kept in its R8 term (AO x sun), so a frame that
   bakes nothing pays nothing and a moving camera pays nothing. Per texel: one slice through the sun and the view
   direction (the sun always lies in it), casters sampled along the slice in the nine depth textures (the texture and its
   eight neighbours), each covering the angular interval [front, front + thickness] seen from the texel; the sun's disk
   is 32 sectors of that slice (visibility bitmask, Therrien et al. 2023, here over an area light instead of the
   hemisphere), so the penumbra widens with the distance to the caster by construction (the PCSS rule without a blocker
   search). Across the disk: each texel of a 4x4 Bayer tile leans its slice by its rank, the AO's 4x4 box averages the
   16. Facing (attached shadow) on the floor / wall planes only: sprites' painted depth gives noisy normals, so bushes and
   crowns take cast shadows only and ignore casters closer than a third of a square (no self-shadowing speckle). Outdoor
   squares only (IsoGridSquare.isOutside, a 16 x 16 x 2 bit mask per compute): rooms have roofs the texture does not show.
2. **Characters and vehicles, per frame** (`CapsuleShadow`): the body as ten capsules between bones (end points on the
   bone worker right after the bones), a car as two body capsules and the cabin. One instanced draw after the chunk
   composite: per pixel of a caster's screen box, the world position from the scene depth (exact: depth is linear in
   x + y + 2z) and Quilez's soft capsule shadow towards the sun. The receivers are whatever the depth holds: the shadow
   runs over the ground and climbs walls and furniture. A receiver the static world already hides from the sun takes no
   second shadow (8 depth taps towards the sun).
3. **Characters in the shade** (`SunShadow.characterFactor`): a character standing in a static shadow gets darker: a
   grid march from its chest towards the sun (walls crossed, upper floors / roofs, solid objects, tree crowns), cached
   per half square and sun step, scales its ambient in `ModelInstance.updateLights` (the game eases it over ~10 frames).

## Log

- **cs-sun1** (term alone, 16 h): geometry right: the sun in the WSW, east-facing walls in attached shadow, the house,
  fences and trees shadow east. Bushes and crowns grey and speckled (noisy normals, self-shadowing), streaks in wide
  penumbrae (16 samples over 8 squares miss thin casters).
- **cs-sun2v / cs-sun2**: facing on planes only, a third-of-a-square self-shadow skip for objects, 24 samples on a
  pow 1.6 spacing: foliage lit with ground shadows beside it; the railing streaks are the posts' own shadows. Kernel
  (AO + sun) 50-56 us a compute against ~25 for AO alone; ~2-11 us / frame while walking in.
- **cs-cap1v / cs-cap1**: the player's capsule shadow runs over the ground and climbs the wall; soft, widening.
- **cs-alt1**: the first A/B (2 s periods on the 90 deg/s spin) was confounded: each period turned the camera 180 deg,
  so on and off saw different halves of the scene. Redone without the spin (cs-alt2).
- **cs-alt2**: no caster at all (the spot is a roofed porch, no zombie came): both halves identical, a null A/B.
- **cs-walk-ao / cs-walk-sun** (3 tiles/s walk at max zoom, 15 h, uncapped): bake-term GPU 2-4 us / frame (AO) vs
  3-18 (AO + sun: the sun steps requeue every texture); a compute 35 vs 60-69 us; frame totals within noise (GPU median
  1.392 vs 1.390 ms, frame time mean 1.732 vs 1.739, p99 3.79 vs 3.80, p99.9 6.81 vs 6.95). Sun-step recomputes now
  go through their own trickle (`sunComputeBudget`, 1 a frame, on-screen textures first); strength steps have a
  hysteresis of two steps (drifting clouds).
- **cs-lou-alt1** (Louisville, 14 h): thrown out: the player starts indoors and most of the map is black fog of war in
  the recording; the frame-level halves were ~1000 frames each at ~130 fps, too noisy.
- **cs-horde-alt1** (population=max walk): no zombie came into view; player + a car: +2 us median GPU (noise).
- `crowd=N` harness flag (Scene): N zombies spawned 3 s in on outdoor squares 4-14 around the player (a ghost in bench
  runs, so they gather around the bench car).
- **cs-crowd-v**: 80 zombies, every one with its own soft shadow east-north-east (15 h).
- **cs-crowd-alt** (69 casters on screen, 1.7 s alternation): pass timestamps 62 us / frame; frame time mean +25 us,
  median +18 us, GPU halves inconsistent (-35 median / +29 mean): ~20-60 us. Too much for "virtually zero":
  screen-aligned boxes around diagonal shadows are mostly empty pixels, each paying a depth fetch and a capsule test.
  Now oriented quads (along the shadow's screen direction, built in the vertex shader from the bounding capsule) and a
  bounding-capsule early out.
- **Torch and headlight shadows** (`sunShadowTorches`): the same capsules against the frame's torches / headlights
  (LightingJNI's list, at most 4), strength = PixelLight's fitted torch model at the pixel x darkness (none above 90 %
  daylight: the bench pistol's weapon light is always on), holder excluded, a vehicle never shades its own headlights.
  **cs-night-v / cs-night-real** (40 zombies around the car, 23 h, headlights + torch): long soft shadows radiating
  from the car. First cost: 170 us / frame (29 casters x 4 lights, quads up to the headlights' 30+ square reach). Now
  the caster x light pairs are culled on the render thread (reach, cone with the caster's angular size, holder) and
  drawn from a pair texture.
- **cs-crowd-alt2 / cs-night-alt**: pair culling took the night pass from 170 to 5.6 us / frame (24 casters x 4
  lights; frame GPU +11 us median, inside the noise; a peer's GL probes overlapped the run, re-done below). The day
  crowd did not improve (79 us, 73 casters): the quads were padded with 1.6 px per square where a square spans at most
  1.12 / |kA| px, ~200k px a character, and every shadowed pixel paid the 8-tap static-shade march.
- Per-caster static-shade suppression: a caster's own sun share through the grid (`SunShadow.visibleAt`, the cached
  march that also shades its model) scales its sun shadow; the per-pixel march is kept as `sunShadowMarch` (off).
- **cs-crowd-alt3 / cs-night-alt3**: day, 70 casters: pass timestamps 41 us, frame GPU +10 us median / +9 mean, frame
  time +6-12 us (of 1.53 ms uncapped at ~650 fps, ~0.7 %); night, 25 casters x 4 lights: 5.6 us, frame GPU +10 us.
- **Kernel steps** (cs-walk-steps12/32 vs 24): ~48 / ~58 / ~63 us a compute (AO alone ~35): the march's steps are not
  the cost; 24 kept.
- **cs-shade1 / cs-shade2**: the crowd killed the bench player on foot (god mode by setGodMod or the cheat flag does not
  hold outside debug); the bench save is rebuilt from its template every run, so nothing carried over. The crowd rig
  now makes its zombies `useless` (never target). **cs-shade3**: a zombie in the church's shade is dimmed, the church's
  shadow lies over the plaza and the parking lot.
- **cs-sweep1** (the sun swept at 0.15 h/s, ~37x the game clock, recorded): the recompute waves (1 a frame, ~58 us)
  cost ~38 us / frame while they run; tone-mapped frames 1 s apart differ by encoder noise, the walls turning from lit
  to shaded as the sun turns, and no chunk-shaped seams.
- Under a cap the deferred computes follow the last frame's slack with a 90 us estimate when the sun march is on (60
  for AO alone).
- **cs-drive-off1/2, on1/2** (drive-120-south, 240 cap, 14 h, AO on in both): fps 232.8 / 231.2 off vs 232.3 / 231.9
  on; p99 8.66 / 8.97 vs 8.60 / 8.82 ms; p99.9 16.2 / 17.5 vs 17.3 / 18.1; max 36.8 / 151.9 vs 36.9 / 37.4; GPU mean
  2.461 / 2.475 vs 2.503 / 2.500 ms. The bake term ~30 us / frame (AO) vs 55-85 (AO + sun) while streaming at max zoom:
  a compute ~30 vs ~57 us. One sun wave requeued ~990 textures (every loaded one), drained at 1 a frame.
- **Tried: the sun march on one texel in four** (`sunShadowSparse`, the 4x4 box averaging the four): the kernel went from
  ~52-58 to ~45-55 us, not the expected quarter: every warp holds a sampling lane, so every warp runs the march
  (divergence); the box stepped every other texel (stair-stepped penumbrae, cs-sparse-v, fixed with a 5x5 tent).
  cs-drive-on3: p99 9.31, GPU 2.532: no gain. Removed.
- **Tried: the sun term in a pass of its own** at half the AO scale (`sunShadowPass`, every lane marching; the AO kernel
  compiled without the sun code; depth-aware tent upsample in the blur): kernel total ~55 us in game (cs-walk-pass), in
  the offline rig 82 vs 69 us for the combined kernel: the cost is the long-range depth fetches (up to nine sources,
  scattered) plus a fixed per-pass cost, not the lane count. In game the term came out black (cs-pass-v; the dump,
  cs-pass-dump, showed a correct sun texture that the blur read as zeros; the rig, with depths near 0.5 instead of the
  game's ~0, could not show it). Removed; what stays is the AO-only kernel variant for computes with no sun (sun
  shadows off, night): the sun code's registers are not paid there.
- **Offline kernel rig** (`harness/contact/kernel_rig.py`, moderngl on EGL): the real GLSL from ChunkAo.java on a ray-cast
  synthetic depth (ground + boxes), writes the term and times kernel + blur. The sun term costs ~1.4 us per march step
  per compute (8 / 16 / 24 steps: 45 / 59 / 71 us, rig without AO).
- **Sun-height-adaptive march**: nothing on a texture's two levels stands taller than ~4.9 squares, so a shadow reaches
  at most 4.9 / tan(elevation): at noon (55 deg) 3.4 squares instead of 8. The march length follows the sun and the
  steps scale with it (24 at the full 8 squares, at least 8): denser samples for the same work, fewer at a high sun.
- **cs-drive-off4 / on4** (adaptive march): p99 9.34 off / 8.95 on, p99.9 17.7 / 17.5. Over all capped drives: sun on
  (4 runs) p99 8.60-9.31 (mean 8.92), p99.9 17.3-18.2 (17.8); off (3 runs) p99 8.66-9.34 (8.99), p99.9 16.2-17.7
  (17.1); fps 230.9-232.8 both; GPU mean +33 us / frame (2.476 vs 2.509 ms).
- **Per-pixel lighting** (cs-ppl-day, cs-ppl-night, `pixelLight=true`): both work together; at night the headlight beam
  throws the crowd's shadows away from the car. A black tree in the day shot is there with sun shadows off too
  (cs-ppl-day-nosun, same pixels): the per-pixel lighting snapshot's, not this work's.
- **Atlas zombies** (`sunShadowAtlas`): zombies drawn as atlas sprites (no model, no bones, no stock shadow) cast one
  upright capsule's shadow, so a horde at max zoom keeps its shadows. cs-atlas-v: 300 zombies around the bench car at
  max zoom, every one with its shadow. cs-atlas-alt (233 casters, 30 s): GPU +11 us median, frame time +12 us mean of
  4.9 ms.
- Harness note: `route=S:1 speed=0.004` lasts 250 s whatever `--route-seconds` says; still-player runs use
  `speed=1/seconds`.

## Where it stands

| What | Cost (desktop, RTX 4090, 5120x2160) |
|---|---|
| Static sun shadows, still or moving camera | 0 (baked; frames that bake nothing pay nothing) |
| Static, walking in at 3 tiles/s | bake term 3-18 us / frame (AO alone 2-4); frame time within noise |
| Static, 120 km/h drive at max zoom, 240 cap (worst streaming) | GPU +33 us / frame; p99 / p99.9 / fps at parity (7 runs) |
| Sun step (every ~6 game minutes) | one recompute a frame (~58 us), on-screen textures first |
| Characters' sun shadows, 70 casters at zoom 1 | frame GPU +10 us median (~0.7 % uncapped at 650 fps) |
| 233 casters at max zoom | +11 us median |
| Torch / headlight shadows, 25 casters x 4 lights | +10 us median (5.6 us by the pass's own timer) |
| Characters in static shade | a cached grid march per half square and sun step (game thread / draw pool) |
| Sun shadows off | 0 (every path returns first) |

Look: soft sun shadows of walls, fences, trees, bushes and furniture on the outdoor world, sharp at contact and softening
with distance, turning and lengthening through the day, thinned by clouds, rain and fog, gone at night; east- or
west-facing walls in attached shade as the sun crosses; every character (bone capsules, atlas capsules far away) and car
with a soft shadow that climbs walls; characters dimmed inside a building's shadow; zombies caught in a torch beam or in
headlights casting long shadows away from the light. Off by default (a change of the picture), Options > Enhancements >
"Sun shadows" with strength, softness and the characters / vehicles / torches switches, all live.

Not done / limits: the static march sees one chunk ring (shadows longer than ~8 squares are cut, tall buildings' upper
floors in the level pair above cast nothing); no moonlight; the static term darkens the torch light too where both fall
(at night the sun term is off); macOS (GL 2.1) has neither pass, like the AO.

## Sanity runs (2026-09-25 evening)

cs-sanity-desktop (RTX 4090, NVIDIA 615) and flip-cs-sanity-flip (Radeon 890M, Mesa 26.2, 1080p): 40-zombie crowd around
the bench car at 16 h, AO + sun shadows. No crash, no shader or compile failure on either driver; every zombie and the car
cast their shadows on both. The flip's character pass read 124-199 us / frame for 16 casters (the desktop ~20): on an
iGPU the per-frame part is not yet "virtually zero" (the per-pixel work of the oriented quads is bandwidth-bound there);
next: scale it to the GPU (fewer capsules, a lower-resolution shadow buffer, or the frame's GPU budget).
