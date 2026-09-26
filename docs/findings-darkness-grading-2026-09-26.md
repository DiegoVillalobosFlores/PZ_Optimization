# Darkness floor, remembered places, colour grading (candidate B, 2026-09-26)

Candidate B of `docs/plan-graphics-enhancements.md` ("the void", black rooms out of sight, night tint: player demands
#1, #3 and #7), implemented in the worktree `../PZ_Optimization-darkness` (branch `darkness-lut`), with the goal that
it costs nothing a frame. All three are off by default, change what a player can make out (the floor, remembered
places), so they stay out of parity comparisons, and apply at once from the Enhancements tab section "Darkness,
remembered places and colour grading". Classes: `pzopt.Darkness`, `pzopt.Grade`, `pzopt.GradeMath`; override edits in
`docs/override-edits.md`.

Desktop, RTX 4090, 5120x2160, NVIDIA GL, `--launcher direct`. Caution for the `dk-cost*`, `dk-abl*`, `dk-upl*` and
`dk-ab-*` runs: without their own options file they read this desktop's tab file (`~/Zomboid/pzopt/options.ini`: HDR
output always on, DLSS, AO), so they ran HDR (native Wayland FP16) + DLSS + AO; their on / off deltas are same-run and
valid, and show the grade working under the HDR expansion. `dk-cost-clean` repeats the measurement with an empty
options file (a player's defaults: SDR, no upscaler, no AO).

## What stock does (dumps of the per-square lighting, `harness/runs/flip-ppl-*/ppl/*-squares.txt`)

- Visibility bits per square and player: 1 seen, 2 can see now, 4 could see. The native multiplies its light (corners
  and flat light) by the fade multiplier `darkMulti`, which heads for `targetDarkMulti`.
- Seen squares out of sight keep `darkMulti` 1 outdoors (their light as seen); rooms of the building the player is in
  but cannot see have a target of 0 (B41's Java: other room, not could-see, same building) and go black — the
  "rooms outside the view cone go black" complaint. Unseen squares have light 0 (black) except the corners they share
  with lit neighbours.
- Objects of other rooms fade with `darkMulti` (alpha = 2 x darkMulti below 0.5).
- The vision cone's own pass (`VisibilityPolygon2`, `visibilityBlur.frag`) blends black at alpha
  `0.022 x viewConeOpacity` (at most 0.11) over the shadow polygons: a light darkening only.
- The world composite (`screen.frag`) on the plain path: bicubic sample, `desaturate(DesaturationVal)`, clamp,
  `contrast(desaturate(c, 0.1), 1.2)`, plus a film grain of at most 0.0015 computed from a 3D noise (`pnoise3D`)
  every pixel.

## Design

**Darkness floor** (`darknessFloorPct`, `darknessFloorBasements`). Where `LightingJNI.JNILighting` reads a square
from the native, a seen square above ground gets its eight corner colours and its flat light lifted to a soft minimum
luminance, `Y' = (Y^4 + F^4)^(1/4)`, the missing luminance added in a cool moonlight tint (`darknessFloorTint`); its
fade multiplier is held at 0.5 or more so furniture of remembered rooms stays drawn. Everything downstream takes the
floored values: chunk bakes, the per-pixel lighting lattice, character models. Unseen squares and basements are
untouched. The native's values are kept per square, so a live change re-derives every loaded square (41,855 at the
Rosewood spot) and re-bakes once. Cost control: the stock change test after it compares floored values (no extra
re-bakes); when the native repeats a square exactly (about two re-reads in three on the spin route) the derived values
are copied back without arithmetic; the rest go through a per-thread 4096-entry colour cache (night light takes few
distinct values: ~1 miss per 75 derivations).

**Remembered places** (`memoryTint`, `memoryTintPct`, `memoryLightPct`, `memoryFadeMs`). The vision pass is drawn by
`Darkness.memoryPass` instead of the stock shader: same full-screen quad, same polygon depth test, same shadow alpha;
the colour is the world pixel itself read after a texture barrier (a fragment reads only its own texel: defined),
desaturated (90 % x strength), dimmed (78 %) and cooled, blended by the alpha x strength; fragments outside the shadow
are discarded (stock blends a zero there). The edge is softened inwards over a ring of the visBlurReduce sums
(`devMemoryRingTexels`, 6: the depth test clips everything outside the polygons, so the ramp lies inside them). Rooms
the native fades to black get the `memoryLightPct` floor. Characters out of sight stay hidden (their alpha is stock).
Optional `memoryFadeMs`: a per-vision-texel pre-pass with a camera-reprojected R16F history eases the tint in (what
just left the view turns grey over a quarter second; reveals are immediate).

**Colour grading** (`colorGrading`, `colorGradingPct`, `colorGradingNightPct`). Nine looks (night, dawn, golden
hour, overcast, rain, storm, fog, snow; clear day neutral), each a set of parameters applied in linear light:
exposure, a white-balance filter as a mired shift (Planckian locus, CAT02 von Kries), tint, log contrast around mid
grey, saturation, multiplicative split toning (black stays black: the fog of war is never lifted), and for night the
scotopic / Purkinje shift (below a linear luminance of 0.04 colour gives way to the rods' blue-green grey response,
lamps and fires keep their hue). Weights come from the climate manager at 10 Hz (night strength, bells round the
season's dawn and the hour before dusk, cloud, rain, storm, fog, snow), smoothed over ~2 s, blended in parameter space
(additive deltas, multiplicative gains). When the blended look moves by more than 0.004 a worker bakes two tables:
the grade alone (33³, square-root shaper) and the **fused** table (65³, plain lattice) holding the stock composite's
tail (clamp, desaturate 0.1, contrast 1.2) followed by the grade, stored over -0.125..1.25 in RGB10_A2. The patched
`screen.frag` takes the fused table on its plain world path — bicubic sample, the stock `desaturate(DesaturationVal)`
(a per-frame uniform), one fetch — and skips the stock 3D noise and its sub-LSB grain; drunk, blurred (short-sighted
without glasses), search mode keep the stock path with the grade-only table after it; night-vision goggles are not
graded. `.cube` files named after a condition in `Zomboid/pzopt/luts/` are applied after the procedural look with that
condition's weight (`base.cube` always). The tables reach the GPU through a persistently mapped pixel-unpack buffer
(the worker writes a free slot; the render thread issues the copy and fences it).

## Picture (recorded runs, Rosewood house `start=8147,11507`, zoom 1)

- `dk-night1` (01:00): stock = black yard and black remembered rooms; floor 25 % = the yard, walls and the bookshelves
  of rooms seen before in a dim cool light; unseen areas stay black. The first night look (Purkinje 0.75, fade-out 0.10)
  also greyed the dark wood of the lamp-lit living room; tuned to 0.6 / 0.05.
- `dk-day1` / `dk-day2` (13:00, clear): remembered places turn the yard behind the player and the rooms out of sight
  grey and dimmer; the stock cone's hard diagonal edge became a ramp of several tiles after the ring softening.
- `dk-par1`: clear day, still camera, grading switched on mid-hold (neutral look, fused path): mean absolute difference
  to stock 1.035 (8-bit, AV1 recording) against 1.048 between two stock moments, signed mean ~0: the same picture; the
  fused frames are steadier (0.25 between two of them) since the animated grain is gone.
- Offline check of the fused table: 33³ max 2.8 / mean 0.07 8-bit steps on the night look, 65³ max 1.14 / mean 0.02;
  the neutral fused table is within 0.17 steps of the stock tail (`tests/pzopt/GradeMathTest`).

## Cost (spinning night route `route=S:450 turn=90`, uncapped, zoom max, `devDarkAlternate=30`: the render-side part
flips every 30 frames, GPU sections timed per state in the same run; median of ~4,500 frames per state)

| run | change | screen composite on / off | vision pass on / off |
|---|---|---|---|
| dk-cost1 | first version: RGBA16F 33³ LUT after the stock shader, dither, ring in-pass | 264.2 / 247.8 us (+16.4) | 148.5 / 149.5 (-1.0) |
| dk-cost2 | LUT as RGB10_A2 | +15.4 | -2.0 |
| dk-cost3 | dither hash computed but amplitude 0 | +14.3 | -1.0 |
| dk-abl1 / 2 / 3 | no shaper / no LUT fetch / wrapper only | +14.3 / +13.3 / -1.0 | (fade pre-pass at full vision res: +22.5) |
| dk-cost4 | dither branched out, fade pre-pass at 1/4 vision res | +12.3 | +13.3 (fade pre-pass) |
| dk-cost5 | **fused path** (stock tail + grade in one table), fade off | **210.9 / 247.8 (-36.9)** | 149.5 / 150.5 (-1.0) |
| dk-upl2 | fused + mapped uploads, a forced bake every 500 ms | 209.9 / 247.8 (-37.9) | -1.0 |

- The grade's cost after the stock shader was ALU throughput (the stock screen shader already saturates the ALUs at
  5K; every added vec3 op cost ~0.6 us); neither the texel format nor the fetch mattered. Folding the stock tail into the
  table removes the stock 3D noise: with grading on the composite is ~37 us a frame *cheaper* than stock.
- Remembered places costs nothing (it replaces the stock pass; discarding unshadowed fragments pays for the colour
  read). The fade pre-pass costs +13 us at a quarter of the vision resolution (a separate pass on NVIDIA has a fixed
  cost): off by default (`memoryFadeMs=0`).
- LUT bakes: 3 ms (33³) + ~21 ms (65³, transfer functions from tables) on a minimum-priority worker, only when the
  look moves (one bake per run at a pinned hour). Uploads on the render thread: 118 us average / 467 us worst copying
  the arrays (dk-upl1); 30 us average through the mapped buffer (dk-upl2).
- Darkness floor on the lighting reads: see the A/B below.

| dk-cost-clean | fused + mapped, **a player's default settings** (SDR, no upscaler, no AO) | **71.7 / 100.4 us (-28.7)** | **193.5 / 200.7 (-7.2)** |

With a player's defaults the stock screen composite costs 100 us at 5K and the fused grade 72; the remembered-places
pass is 7 us cheaper than the stock black blend it replaces.

## End-to-end A/B (night spin route, uncapped, same settings: the tab file's HDR + DLSS + AO)

| run | fps | p99 | p99.9 | 1 % low | jitter | GPU | game thread |
|---|---|---|---|---|---|---|---|
| dk-ab-off (all three off) | 290.0 | 9.3 ms | 18.1 ms | 106 | 1.0 ms | 94 % | 59 % of a core |
| dk-ab-on (floor 25, remembered places, grading) | 296.3 | 8.4 ms | 15.8 ms | 117 | 0.9 ms | 97 % | 62 % of a core |

GPU-bound either way; with the features on the run is slightly faster (the fused composite). The darkness floor's work
on the lighting reads, timed (`devDarkStats`, the timing calls included): 17-26 us a frame spread over the lighting reads
(~100 squares derived and ~190 served by the repeat copy a frame on this streaming route, one colour-cache miss per ~75
derivations); after a live change of the floor, one pass over the 42k loaded squares plus one re-bake of the 361
on-screen chunks.

## Final looks (`dk-fin-*`, still camera, grading switched on mid-hold)

- Night (floor 20 + remembered places, then the night look): the floor-lit yard and remembered rooms turn blue-grey
  (the rods' response), the lamp-lit living room keeps most of its warmth; eased once more to Purkinje 0.55 below a linear
  luminance of 0.04 after this run.
- Golden hour (19:18, weight dusk 0.59): warmer highlights, slightly richer greens and browns, cool shadows.
- Rain with cloud (weights rain 1.0, overcast 0.4; the harness storm pins rain but not the thunder flag): cooler, a
  little greyer and softer.
- The first table upload of a session goes through the mapped buffer too (the first bake waits for its setup) but
  still costs 0.2-0.6 ms once: it creates the two textures. Later uploads: 30-70 us.

## Video

`docs/media/darkness-grading-stock-vs-new.mp4` (+ poster), `harness/stitch-darkness.py`: the stock look beside the new
options in four scenes (night: all three; clear day: remembered places; golden hour and rain: grading), then the cost
card. The sources are in-game captures (`--prop devCapture=15.5,9,30,75`, runs `cap-*`): the desktop recorder captures
the whole monitor and had a browser window over the game (the first `vid-*` runs, discarded). Both runs of a pair are
put on one timeline from their route starts, so the turning player matches frame for frame.

## Options-tab previews

The section's preview panel shows three GIF pairs (`src/media/ui/pzopt/compare/{darkness,memory,grade}-{stock,opt}.gif`,
`harness/menu-gifs.py darkness memory grade`, kind `pane`: cut from the video's aligned in-game capture panes, no
tone-map): `darkness` (night, all three; default of the section and `darknessFloorPct`, `darknessFloorBasements`,
`colorGradingNightPct`), `memory` (clear day; the `memory*` keys), `grade` (rain; `colorGrading`, `colorGradingPct`).
The rain and dusk "on" captures were redone at 50 % scale (`cap-*-on-*1214*` / `*1215*`): at 75 % the readback held
that scene to 13-18 captured frames a second. Checked in game with `--flag options_tab=Enhancements --flag
options_select=<key>` (runs `dk-prev-*`).

## Release card

`docs/workshop/images/37-darkness-memory-grading.gif` (the Workshop page's "New! Darkness, memory, colour", 1280x1281,
120 frames at 10 fps, 3.8 MB; `harness/darkness-card-gif.py`): **every visual Enhancements option** off vs on —
ambient occlusion, sun shadows, reflections, per-pixel lighting, darkness floor 20 %, remembered places, colour grading —
in three scenes (Rosewood at 16:00, at 01:00 with a hand torch, the Riverside pier at 15:00; runs `card-*-off` / `card-*-on`,
in-game captures `devCapture=13,12,12,50` on one route-start timeline per pair), each cutting from 1.5 s off to 2.5 s on.
HDR output and upscaling are not in it (SDR GIF; the upscaler changes speed, not the look). Added at the top of
`docs/workshop/description.txt` (the reflections card keeps its image, loses its heading; ~6,560 staged characters).

## Not done / follow-ups

- The fade of remembered places (`memoryFadeMs`) costs ~13 us at a quarter of the vision resolution, all of it the
  fixed cost of one more pass; folding it into the visBlurReduce pass (same pass, second render target, last frame's
  sums for the ring) would make it free.
- Split screen: the grade is shared by the players; the remembered-places pass keeps the stock pass for more than one
  player only through VisBlur's own single-player rule (the 25-tap variant runs otherwise).
- macOS (GL 2.1) is not covered: the grade and remembered places switch themselves off there; the floor works everywhere.
