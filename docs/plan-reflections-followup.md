# Reflections: what is left for a follow-up (2026-09-26)

State: `reflections` (pzopt.Ssr) shipped off by default; design, variants and measurements in
`docs/findings-reflections-2026-09-25.md`. Desktop (RTX 4090, 5120x2160) whole-frame GPU cost: 0 us without water,
+11..15 us at a river, +27..30 us in rain with puddles. The items below are ordered by what a player would notice first.

## 1. Measure the frame-time tail on a quiet machine

Early runs showed the frame-time p99 of the "on" frames 0.16-1.5 ms above the "off" frames while the GPU medians moved
by 0.01-0.03 ms. The likely cause (a `glGet` per chunk draw: NVIDIA's threaded driver syncs its worker on each) was
removed, but every run after that fix overlapped a peer's ffmpeg at ~1400 % CPU outside the queue, so the tail is not
measured yet. Rig: `harness/ssr/tails.py <run>` on the river and rain runs of the findings (`devSsrAlternate=1000`,
`devSsrTiming=true`), with nothing else on the machine (`ps -eo pcpu,args --sort=-pcpu | head`). If the tail is still
there, bisect with `devSsrSkip` (1 moving pass, 2 water resolve, 4 barriers, 8 composite scatter) and look at the
render thread (`--asprof` or the overlay's render load).

## 2. Other GPUs and systems

Only the desktop (NVIDIA, Linux) ran it. The patches need GLSL 1.50 compatibility + `GL_ARB_shader_image_load_store`:
Mesa (radeonsi, iris) has both, but Mesa is stricter about sampler / image unit validation (the PPL session met
`glValidateProgram` failures with mixed sampler types on one unit). To do: a flip run (AMD 890M, Mesa) and a Dell run
(Intel + NVIDIA PRIME) with the river rig and `devSsrView=1`; `harness/ssr/patchcheck.py` against Mesa (glslcheck on
the flip); a Windows look through the Windows test (docs/windows-test.md). macOS stays off (GL 2.1).

## 3. Rain cost

+27..30 us with puddles pinned at 0.8: composite scatter ~10 us (every chunk texture near a puddle runs the scatter's
maths; the half-resolution early-out already drops 3 of 4 fragments), moving pass ~10 us (bounded to 40 characters
within 14 squares), two barriers ~5 us. Ideas not tried yet:
- a composite program variant without the scatter for chunk textures far from any reflective surface (as PixelLight's
  light-free variant), so those draws do not even carry the branch (helps clear weather more than rain);
- quarter-density puddle sources (every 4th column) at wide zoom, where a puddle's reflection is a few pixels;
- a CPU per-square "reflective puddle" bit (the puddle shader's noise decides where puddles reflect; porting it exactly is
  hard because the GPU's sin-hash differs from Java's, a conservative margin would do);
- the moving pass for puddles every other frame (keys would need to live two frames).
Dead ends already measured: a tile mask stamped by the puddle shader (+15 us more), per-texel resets in the resolve.

## 4. What does not reflect yet

- Water or puddles above level 0 (rooftop puddles, raised water): the scatter mirrors about one plane, z = 0. A per-
  target plane from the square map (level per square) would generalise it.
- Per-frame sprites drawn after the composite without a model: fire, smoke, particles, per-frame trees (when
  `treesInChunkTexture` is off), corpses drawn per frame, items on the ground drawn per frame. The moving-object box pass
  could take their screen boxes the same way.
- Surfaces the camera cannot see and that were never drawn (the backs of buildings): out of reach of any screen-space
  method; the stock sky reflection stays underneath.
- Characters' reflections are one frame late (the moving pass runs after the water for the next frame).

## 5. Quality options

- Rain roughness: blur the reflection with the rain intensity (a mip or a few taps at the source), today only the wave
  normal's displacement spreads it.
- Night: lamps and lit windows reflect as they are drawn (8-bit world); with HDR output the glint pass could add the
  reflected highlights above SDR white.
- Distance fade and reach: fixed at 1.5 levels (`ssrReachPct`); tall buildings on a far bank fade out. A per-scene
  reach (from the tallest level on screen) would keep them.
- Hidden-layer colour is 5:5:4 bits; a 64-bit key (NV_shader_atomic_int64 where present) would carry full colour.

## 6. Options tab and media

- The Enhancements tab's preview uses the HDR clip for the Reflections section: a stock-vs-reflections clip
  (`harness/menu-gifs.py`, `src/media/ui/pzopt/compare/`) would show the effect itself.
- Turning reflections on applies at the next launch (the shaders are patched at load). A live switch would need the
  water, puddle and composite programs recompiled on demand (ShaderProgram reload) or both variants kept.

## 7. The stock water shader

Not reflections, but found on the way: the game's `water_hq.frag` costs ~1.4 ms at 5120x2160 on a river view (25-tap
Voronoi noise and six gradient evaluations of four noise octaves per pixel), the largest single GPU item in those scenes.
A version that computes the wave normal once per square or from a small noise texture would pay for the reflections many
times over; it needs a parity rig (the waves' look must not change).
