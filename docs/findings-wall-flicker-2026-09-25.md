# Walls flicker on the stairs with every lighting setting on, 2026-09-25

Maintainer's report (flip, save `Sandbox/2026-09-25_21-32-32`, Riverside police station, every new lighting setting on:
`pixelLight`, `pplShadows`, `ambientOcclusion`, `sunShadows`, FSR 1): going up the stairs to the top floor and down to the
basement again, the walls flicker a lot. Asked: reproduce with Jev directing the walk, confirm with Jev, fix, profile.
Worktree `../PZ_Optimization-wallflicker`, branch `wall-flicker`. Runs on the flip (Radeon 890M, 1920x1080).

## Rig

- `explore=stairs` (`pzopt.StairsWalk`, hooked into `pzopt.Explore`): the player walks on foot (movement keys) along a
  breadth-first path over every loaded level of the building, where a staircase is an edge from the square in front of its
  bottom step to the square past its top step (both ways) and the path walks the three stair squares in line. With
  `director=jev`, `harness/explore-director.py --machine flip` asks Jev each 0.3 s: go_up / go_down / look_around / hold /
  done (look around on each level, up to the top floor, down to the lowest). A basement is a BuildingDef of its own: every
  building overlapping it counts for the levels. Every level change is logged with its epoch ms. The walk: basement (-1) ->
  0 -> 1 -> 0 -> -1, ~34 s, 4 level changes, Jev ~110 decisions a run.
- `devCapture=0,125,240,33,gray`: every presented frame, a third of the size, one luma byte per pixel (new `gray` field).
- `harness/stairs-flicker.py`: flicker.py's A-B-A blink detector (>= 32/255, back within 4 frames), per phase of the walk
  (between the stairs events). The raw count is dominated by 1-px shimmer of edges and lines under the moving camera, which
  stock has as much as any build (stock 352 px/frame, the most); the **solid** count keeps only blink pixels inside 3x3
  patches (an eroded mask: a wall or a light patch blinking), and a patch frame has them over > 0.1 % of the picture.
- `devPplProbe=10`: per frame, for the squares within 10 of the player, how many native corner colours / light / vision bits
  and packed lattice values changed and how many came back to the value of two frames before.

## Reproduction and Jev

| run (flip, same walk) | solid blink px/frame, whole walk | basement walk | walk on level 0 -> stairs up | arrival on level 0 (1.5 s) |
|---|---|---|---|---|
| stock (`enabled=false`) | 0.5 | 0.4 | 0.9 | 7.2 |
| optimized, lighting keys off | 1.4 | 0.0 | 0.1 | 41.1 |
| optimized, all lighting on (release b5069a6) | 4.5 | 12.4 | 8.0 | 30.0 |

Jev (`stairs-flicker.py --judge`, all-on vs stock): flicker_in_test yes 0.75, kind lighting_flicker 0.64 (shared 0.23).
Two things: a steady blink on lit walls while walking (all lighting on only), and the new floor building up chunk by chunk
for ~10 frames on arrival (every optimized build, lighting or not; stock replaces it in one frame).

## Cause 1: the per-pixel light moved with the camera's sub-pixel offset

Bisect, one run each (solid px/frame): AO + sun alone 1.2 (clean), pixelLight alone 4.5, pixelLight without `pplShadows`
9.1, without `pplPointLights` 7.6, without `pplVariants` 8.4, **without `pplAnalytic` 1.6, without `pplNormals` 0.6**.
The blinking patch on a basement wall alternated frame by frame (luma 181 171 178 167 163 179 161 180 ...), and the probe
showed the native light never did (0 A-B-A of 3,900 corner changes over 4,600 frames): the shader did.

The chunk composite rebuilt each pixel's world position from its window position (`gl_FragCoord`, continuous) and the depth
of the chunk-texture texel it shows (GL_NEAREST, DEPTH16). As the camera slides by fractions of a pixel, that mix moves up to
1/128 of a level per pixel, and the torch's facing term (`pplNormals`) took its normal from `dFdx`/`dFdy` of it: along wall
and floor edges the 2x2 derivative quads straddle different texels on odd and even frames, the snapped normal flips between
wall and floor, and the torch light on the wall blinks.

Fix `pplTexelPos` (default on), in three steps:

1. First try: the pixel lit at the centre of the texel it shows (window position of the texel centre from the texture-
   coordinate derivatives) and the normal from that texel's four neighbours in the chunk depth texture. Same build, all
   lighting on: `pplTexelPos=false` 3.9 -> **1.1** solid px/frame (basement walk 9.4 -> 1.6, level 0 walk 5.6 -> 0.2, walking
   down 4.8 -> 0.6); the probe on both: the native values never A-B-A. But the still picture showed a fine static checker on
   torch-lit walls (`--shot-at 3` in the save's basement, fix vs release): one DEPTH16 texel step along a wall is only 2-6 LSB,
   so over one texel the rounding tilted the normal enough to flip the snapped plane texel by texel (the release's screen-
   derivative normal had the same noise, it just moved every frame). Cost +40 us a frame on the flip's chunk composite.
2. Neighbours `pplNormalSpan` (2) texels away and the centre depth by `texelFetch` of the texel `floor(tt)` names (the NEAREST
   sample can be the next texel where pixel centres fall on texel edges): no pattern (span 1 still showed it), but +92 us:
   the centre fetch ran for every pixel, the light-free variant included.
3. Final: the lighting position stays the release's (pixel centre; the bisect showed the normal alone blinks: `pplNormals=false`
   was clean with it), and only the torch facing term's normal is texel-space, computed once per pixel by the first light in
   reach (`pplLazyNormal`, declared in the shared light code, defined by the chunk composite): texel centre, its own depth, the
   span-2 neighbours, per axis the side with the smaller depth change (never across an edge), cleared texels left out, then the
   same snapping as before. Light-free chunk draws run the release's code. Cost (flip, `devPplAlternate=6,2,0,16384`, the
   screen-derivative normal on dev cost bit 16384 in the same program, 2 s each, 90 s, torch on in the lit basement): **+52 us**
   a frame on the chunk composite (590 vs 538 us), 0.6 % of a 120 fps frame. Still picture vs the release: 0.12 % of pixels
   differ by more than 24/255, all on torch-lit walls, where the release had light steps along wall tops, a one-texel bright
   vertical seam and dotted floor edges (the same derivative artefacts); no checker.

## Cause 2: the floor the player arrives on bakes chunk by chunk

The bake scheduler (`bakeScheduler`, 2026-09-24) spreads cutaway and never-textured bakes over frames (cutaway waits up to
3 frames, arrivals 4 a frame outside the budget); on a floor change almost every on-screen level needs one, so the new floor
appeared in pieces over ~110 ms with the old floor's textures in between. Fix `bakeLevelChangeFrames` (3): for 3 frames after
the camera character's level changes, every cutaway and never-textured level is granted at once, as stock does.
Same build: arrival on level 0 29.8 -> 6.5 px/frame, whole walk 1.3 -> **0.5** (= stock). Cost: the first arrival on a floor
never shown before is one long frame, as in stock (flip: 170 ms vs 46 ms spread; stock 161 ms); later floor changes cost
nothing either way.

Sizing (flip, first arrival on the ground floor from the basement; the ~48 ms frame there is the same in every build without
the burst): all on-screen chunks 170 ms, radius 2 111 ms (+ an unrelated 280 ms frame 0.46 s before), radius 1 80-101 ms
(3 runs), all with AO + sun off 84 ms. Radius 1 (the 3x3 chunks round the player, `bakeLevelChangeRadius`) is as calm as all
(arrival 3.3-8.2 px/frame vs 6.5) and is the default; farther chunks follow under the normal budget. Stock: 161 + 122 + 80 ms.

## Result

| run (flip, same Jev-directed walk) | solid blink px/frame | patch frames | Jev |
|---|---|---|---|
| stock | 0.5 | 3 | control |
| release b5069a6, all lighting on | 4.5 | 13 | lighting_flicker 0.64, flicker yes 0.75 |
| step 1 + level-change bake, all lighting on, 2 runs | 0.6 / 0.4 | 3 / 3 | flicker yes 0.08, better than before yes 0.91, shared (= stock) 0.54 |
| final (texel normal only + level-change bake), all lighting on, 2 runs | 0.6 / 0.8 | 6 / 3 | flicker yes 0.06, better than before yes 0.93 |

The final build's walking phases are 0.1-0.5 solid px/frame (stock 0.3-0.9); what is left sits in the first 1.5 s on a new
floor (one patch frame each, as in stock).

HDR: the flip reported "no HDR screen" (its output is not in HDR mode), so `hdrAuto` stayed SDR and the maintainer's session
presumably too; the HDR floor-change fix of PR #20 (ea0c912) is on master under these changes.

## Desktop confirmation (2026-09-26: HDR, VRR, every optional setting)

Maintainer: "try it on this desktop with HDR VRR, and all optional settings enabled to confirm". RTX 4090, 5120x2160, the
flip save copied over, the same Jev-directed walk through a queue `cmd` job (`/tmp/wf/desk-walk.sh`: KWin's VRR policy
Automatic for the job, the previous policy restored on exit): `hdrAuto=true hdr=true` (native Wayland, HDR on), VRR active
(165 Hz, cap 157, present pacing gpu), and every default-off key of the tabs: pixelLight, pplShadows, ambientOcclusion,
sunShadows, zombieLodDynamic, headOnWorker, reflexSleep, reflexBoost, vblankLock, frameStartGate, vsyncAdaptive, cursorLatch,
hotsaveStaged, overlay / overlaySampling / overlayLog, upscaler dlss (+ dlssSharpen), then again with fsr1. `--record` plus a
`devCapture` at a fifth of the size; `stairs-flicker.py --mask 0.75,0.75,1,1` (the overlay's frame graph scrolls every frame).

- With DLSS: no wall flicker in either build (recording, 1/8 size: solid A-B-A 0.03 released vs 0.00 fixed px/frame). DLSS's
  temporal accumulation (sub-pixel jitter + history) averages the per-frame flip of the old normal away.
- With FSR 1: small (recording 0.42 released vs 0.29 fixed; the capture metric equal, ~2-3 px/frame of scattered specks in
  both, Jev "shared" 0.64). The torch wall blinking of the flip hardly appears on the 4090. What does show is the floor
  arrival: the released behaviour rebuilt the ground floor in 4 blinking frames over 0.2 s (377, 99, 43, 19 px at 1/8 size),
  the fixed build in one change (286 px, then 21).
- fps (overlay, route window): released 97, fixed 121 (DLSS runs; one run each, not a controlled A/B).

So the desktop confirms part 2 and no regression; the wall flicker is the Radeon's (flip) case, where the colour-capture pair
of 2026-09-26 again measured 4.0 -> 0.6 px/frame (Jev: flicker in the fixed run 0.05, better than the released 0.91).

Video: `docs/media/stairs-wall-flicker-problem-vs-fix.mp4` (`harness/stitch-stairs-flicker.py`, AV1 10-bit PQ): (1) the flip
walk side by side from the colour captures (every presented frame, cut at the floor changes so both start together), (2) 0.27x
close-ups of the problem run's worst blinking walls and the same moments of the fix, (3) the desktop HDR recordings of the
ground-floor arrival, real time and 0.25x. Flip panes are SDR mapped to PQ at 203 nits (encode-av1-hdr.sh's mapping).

Known, not this change: the tree-light session (pz-optimization-10) found tall tree crowns near a chunk's south edge baked at
depth 0 (clamped), so pixelLight lights those crowns from the wrong squares under a torch (TreeBake's card-depth model).

## Rig lessons

- The raw A-B-A count is dominated by the 1-px shimmer of edges under a moving camera (stock highest); count solid patches.
- One director per run, started before the job; it now ignores states older than itself (a previous run's leftover state was
  answered "done" and the next run never moved).
- A bench run ends at route distance / speed, not at `--route-seconds`: still shots use `route=S:1 speed=0.1`.
- Judge lighting stability with FSR 1 or no upscaler: DLSS's accumulation hides per-frame flicker.
