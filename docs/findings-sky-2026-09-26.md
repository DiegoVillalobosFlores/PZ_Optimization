# The real sky: true sun, moonlight and cloud shadows (2026-09-26)

Asked by the maintainer: "implement true moonlight and sunlight with cloud shadows; secondary objective: make the performance
hit virtually inexistent; implement all the state of the art solutions, don't discard any idea, implement and profile them".
Worktree `../PZ_Optimization-sunmoon`, branch `sunmoon-shadows`, on master 8516196. Builds on the soft sun shadows
(`docs/findings-contact-shadows-2026-09-25.md`). Runs on the desktop (RTX 4090, 5120x2160) through the queue, labels `sky-*`.

Keys: `skyPath` (astro | arc), `skyLatitudeDeg` (0 = the season's), `sunStepAngle`, `sunStaleOffscreen`; live
`moonShadows`, `moonShadowPct`, `cloudShadows`, `cloudOpacityPct`, `cloudSpeedPct`, `cloudScalePct`; `cloudHeight`,
`cloudReplaceStock`, `cloudFieldSize`, `cloudBindless`, `cloudTermMips`, `cloudCull`; dev `devSkyDate`, `devCloudCover`,
`devCloudView` (1 the cloud factor, 2 the direct-sun share), `devCloudTiming`, `devCloudAlternate`, `devCloudSkip`,
`devSunView` 4-7 (the sun term's branch, reconstructed height, x / y offset from the square edge). Classes: `pzopt.Sky`,
`pzopt.SunShadow` (the key light), `pzopt.CloudShadow`, `pzopt.ChunkAo` (kernel: the direct-sun share, wall faces).

## The sky (`pzopt.Sky`)

- Sun: Astronomical Almanac low-precision solar position (declination, right ascension) for the game's date; the hour angle
  from the game's own clock around the season's high noon (`ErosionSeason.getDayHighNoon`, 13.9 h in July: the game models
  daylight saving and the longitude) at the season's latitude (38 N). The stock dawn / dusk come from the same latitude
  formula, so the sun crosses the horizon when the game's daylight says so. July at 16 h: elevation 53, azimuth 238 (WSW);
  8 h: 9.6 up in the ENE; the stock's arc had 55 deg max and a fixed E-S-W path.
- Moon: Almanac low-precision lunar longitude / latitude, hour angle offset from the sun's by the difference of right
  ascensions; lit fraction from the elongation; brightness from the lunar phase law (a quarter moon ~0.1 of full).
  `SkyTest`: solstice / equinox sun heights, the 1993-07-03 full moon (up at midnight, down at noon), the 07-19 new moon, the
  07-26 first quarter (waxing, south at dusk).
- Key light (`SunShadow.update`): the sun when it is up past `sunMinElevationDeg`; else the moon, strength
  `sunShadowStrengthPct x moonShadowPct x sqrt(brightness) x its rise x how dark the sky is` (sun 4 deg under: none, 12: all).
  A new shadow step when the light has turned `sunStepDeg10` from the last step's direction (azimuth steps near a high sun
  would otherwise come in bursts), or changed body. Off-screen textures take a stale step only when they show again.
  Water glints follow the true sun, and the full moon glitters on the water at night (HdrGlint).
- sky-moon1 (1993-07-03, 23 h): the moon at 18 deg, azimuth 135, strength 0.34: a tree's shadow falls north-west.

## Cloud shadows (`pzopt.CloudShadow`)

A cloud holds back the direct sun exactly as a wall does, so a cloud shadow is the static sun shadow applied where the
cloud is. The chunk AO kernel keeps, per chunk texture, the direct light's share of the light the texture is baked with,
`q = s lit / (1 - s out + s lit)` (G of the kept term: 0 indoors and in shade, s on sunlit ground, walls facing the sun and
roofs), and the chunk composite multiplies each pixel by `1 - q (1 - T)`. The static sun shadows fade under a cloud, as
they do outside. Nothing re-bakes when the clouds move.

- Field: tiled Perlin-Worley base (histogram-equalised: a cover c covers a share c), an inverted Worley fBm detail that
  erodes the edges and drifts at its own speed, 256 x 256 RG8, generated off-thread in ~65 ms. Density: Nubis-style coverage
  remap, Beer-Lambert transmittance. 2.5 squares a texel (fair-weather cumulus 100-200 squares across; `cloudScalePct`).
- Drift with the climate's wind (its angle is the compass point it blows from; 0 = SE) at 1.6 x the surface speed, in the
  game clock's real seconds (pauses, fast-forward). Projected along the key light from `cloudHeight` squares up.
- The composite reconstructs each pixel's world position from its depth (Ssr's mapping); the cloud uv is linear in
  (window x, window y, depth): two dot products. Water is patched the same way. Characters (their shade) and the capsule
  shadows read the same field on the CPU.
- Cover: the climate's cloud intensity; with cloud shadows the sun shadows no longer thin with cloud cover (the gaps keep
  the full sun); full overcast (no gaps) fades them.

## Bugs found on the way (the maintainer's reports)

- **Grid lines under cloud shadows** (two causes). (1) The kept term's empty texels outside a chunk's drawn diamond held
  "no share"; the composite's bilinear read at half resolution pulled it into every chunk edge. The blur now dilates the
  drawn neighbours into empty texels (the same leak lightened the baked AO / sun term along the edges). (2) Pixels along the
  chunk seams carry colour but no depth (tile edges): their world position from depth 1.0 landed far away and read a cloud
  (or a gap) from elsewhere: light dots on every seam. They take the nearest drawn neighbour's depth. Rig: `devCloudView=1`
  (sky-cview1/2: identical with and without culling, so not the cull; sky-cview3/4: gone).
- **East facades wrong with the sun in the west** (Rosewood church). The roof exclusion of the sun term (the egg-crate roof
  fix of 2026-09-25) tested the pixel's square and its 8 neighbours for roof tiles: an exterior wall's square is beside the
  building's roofed squares, so the upper half of every wall under an eave was taken for roof (no sun term: lit). Rig
  `devSunView=4` (sky-branch1: the church's east walls at 0.2 = roof). Now a pixel on a wall face of the grid (a new wall
  mask per texture: W / N wall edges per level) takes that wall's exact normal and is never a roof; texels whose depth is
  clamped (tall sprites above the texture's two levels) are solved from the screen position on the candidate wall planes.
- **A light seam between storeys**: the join of two chunk textures on a tall wall (levels 0-1 and 2-3; `devSunView=5`,
  sky-pos5b): the texture's bottom rows reach a little under its lowest level and its top rows sit on its top line, both
  outside the wall test's level range, so the roof test took them. Rows within 0.1 of a level line (0, 1 or 2) and 0.4 of a
  wall edge take the wall's face.

## Cost (desktop, 5120x2160, GL timestamps around the chunk composite, devCloudAlternate 1 s in one run)

| Composite, max zoom, 50 % cover | median | mean |
|---|---|---|
| stock program (cloudShadows=false, sky-unpatched1) | 632 us | 670 |
| patched, clouds off (sky-alt1 off half) | 643 | 686 |
| clouds on, a texture bind per draw (sky-alt1/2) | 660-700 | 785-830 |
| on, no kept-term read (devCloudSkip=1, sky-alt3) | 582 (off 626) | 637 |
| on, one bind a frame (devCloudSkip=4, sky-alt5) | 597 (off 620) | 668 |
| on, bindless handles (sky-alt7) | 650-665 (off 632) | 710-723 |
| on, + the seam depth search (sky-alt9/10/11) | 795-811 (off 624-644) | 854-873 |
| on, + transparent padding skipped, share from level 1 (sky-alt12) | **619-627 (off 620-626)** | 692-697 (off 678-694) |
| the same, share from level 0 (sky-alt13) | 636-648 (off 637-644) | 692-708 (off 687-694) |

The cloud math is free; the cost was the kept-term read: first the ~100 texture binds between draws (bindless handles:
`ARB_bindless_texture`, a uniform per draw; the bind per draw stays the fallback), then the seam fix's depth search, which
ran on every transparent pixel of every chunk quad (most of a quad is padding): pixels with nothing drawn now return first.
Reading the share from level 1 of the kept term (levels 1-2 built with one glGenerateMipmap per compute, +~10 us a compute)
or level 0 measured the same; level 1 stays (less traffic on smaller GPUs). An implicit-LOD read (bias) was as slow as the
depth search in these runs and is not used (explicit textureLod). At zoom 1 with binds: +7 us (sky-alt6).

## Frame level (120 km/h south drive, 240 cap, 16 h, sun shadows + AO)

| run | fps | p99 | p99.9 | 1%-low | GPU |
|---|---|---|---|---|---|
| sky-drive-ao (AO only) | 230.8 | 9.45 ms | 16.0 | 106 | 61 % |
| sky-drive-arc (sun shadows, 09-25 feature set: arc path, no clouds / moon / far field) | 229.9 | 9.97 | 18.4 | 100 | 60 % |
| sky-drive-new (true sun, far field, 50 % clouds) | 230.0 | 9.68 | 17.3 | 103 | 63 % |

Parity. (sky-drive-on1 / off1 an hour earlier read 217 / 206 fps for clouds on / off with the machine busier: noise.)

## Low sun: the far field (`sunShadowFar`, `sunShadowFarSquares` 32)

The near march sees the texture's own two levels in the nine depth textures (±8 squares); a low sun's shadows reach
further, so shadows faded out below 12 deg (`sunMinElevationDeg`; the true sun is under 12 deg for over an hour after sunrise
and before sunset in July). Now a coarse heightfield march follows: per chunk a cached 8 x 8 map of column tops (walls and
solid objects to their level's top, upper floors and roof tiles just above their level; not trees: the crown proxies
already shade them to two chunks, as columns they came out twice as blocks, sky-low1 / sky-low2), assembled per compute
into a 72 x 72-square R8 window round the chunk (FAR_UNIT 9, the previous binding restored). The march starts at half a
square: inside the near range only what stands above the texture's two levels counts (the upper storeys of a tall building
are in the level pair above: before, they cast nothing within 8 squares), past it everything; steps grow with the distance,
three taps across the ray (the columns' hard sides drew a sawtooth), a penumbra growing with the distance, the last quarter
of the reach fades (the church's ~40-square shadow at 13 deg was cut on a line at 20). `sunMinElevationDeg` is 2 now.
Kernel ~42-43 us a compute at a low sun (sky-low4, sky-tall) vs ~35-58 before; nothing at a high sun without tall columns.

## Characters at a low sun, water

- Capsule shadows (40-zombie crowd, zoom 1, uncapped, `devSunAlternate` + `harness/contact/alt.py`): 16 h +22 us GPU median
  (sky-crowd-16); 19:30 (13.5 deg) +36 us (sky-crowd-low), +31 us (sky-crowd-low2) after: past `sunShadowCharacterLodPct`
  (150 % of a square) from the feet the shadow is the bounding capsule's alone, thinned to the body (the penumbra there is
  wider than a limb), blended over a square; the quads end at `sunShadowCharacterReach` (12 squares along the ground,
  the last 30 % fading). The long shadows read crisp at the feet and soft further out (sky-crowd-lowv).
- Water under a cloud (Riverside pier, sky-water2 vs sky-water3 at cover 0): the river and the pier darken together. The
  dark curly band on the river in both is the game's own smoke column, not ours.

## Open

- Moonlight darkens torch light where both fall (the baked term cannot tell the two apart; with pixelLight the same); the
  moon's strength is modest.
- `cloudReplaceStock` (off): the stock screen-space cloud layer (a translucent haze between camera and ground) stays; not
  judged side by side yet.
- The flip (Radeon 890M, Mesa) was disconnected all day: the bindless path, the kept-term mips and the far field are
  untested on Mesa (the bind per draw is the fallback when GL_ARB_bindless_texture is missing).
