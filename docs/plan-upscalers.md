# Plan: render-resolution upscaling (FSR 1.0 / DLSS / XeSS), 2026-09-22

The world is rendered at a fraction of the screen size and resolved to the screen by an upscaler;
the UI, text, cursor and the stock post-process ("weather" screen shader) stay at native resolution.
Every GPU-bound scene (fog 389 fps, storm 390, Louisville's composite, every laptop) gains roughly
the pixel ratio in the world pass. Keys default off: with `upscaler=off` nothing changes.

## The seam (how the game composes a frame)

- `Core.StartFrame(nPlayer)` binds the offscreen `MultiTextureFBO2` (one POT texture, 8192x4096 at
  5120x2160, RGBA8 + the pzopt D24S8 depth texture) and queues `glDoStartFrame(screenW, screenH,
  zoom, player)`; the render thread's `Core.DoStartFrameStuffInternal` sets the projection to
  `ortho(0, w*zoom, h*zoom)` (world pixels) and the viewport + scissor to the player's screen rect.
  Everything in the world pass is drawn in world pixels through that projection, so shrinking the
  viewport alone renders the same content into fewer pixels.
- `IsoWorld.render()` … `Core.EndFrame(nPlayer)` unbinds. `Core.RenderOffScreenBuffer()` →
  `MultiTextureFBO2.render()` draws the texture region (sx, sy, sw, sh) to the screen rect under
  `SceneShaderStore.weatherShader` (`media/shaders/screen.frag`: bicubic sample, colour grade,
  desaturation, night vision, search-mode circle via `gl_FragCoord`, drunk / blur, film grain).
- Every viewport restore inside the world pass goes through `glDoStartFrame` (TextureDraw case
  `glDoStartFrame` / `glDoStartFrameNoZoom`) or `IsoCamera.getScreen*` on the render thread
  (ModelOutlines, VisibilityPolygon2), or a game-thread `SpriteRenderer.glViewport` of the player
  rect (IsoWorld view cone). Shader passes that map `gl_FragCoord` through a screen size:
  `visibilityBlur` (screenSize / displayOrigin from IsoCamera on the render thread), `water` /
  `puddles` (`WViewport` = offscreen origin + `camera.offscreenWidth / zoom`), `fog` (`screenInfo`
  from the game thread), fire / smoke particles (viewport = offscreen size).

## Mechanism (`pzopt.RenderScale`, render thread only)

1. TextureDraw `glDoStartFrame` / `glDoStartFrameNoZoom` with a player index while the world FBO
   is bound: after the stock call, viewport and scissor become the scaled player rect
   `(sx*s, sy*s, sw*s, sh*s)`; the projection is untouched. The world FBO keeps its texture; the
   low-res image occupies the top-left of each player's region.
2. TextureDraw `glViewport`: a rect equal to a player screen rect or the full screen is scaled the
   same way (IsoWorld's view-cone restore); anything else passes through (FX mask, cone texture).
3. `IsoCamera.getScreenLeft/Top/Width/Height` and `getOffscreenLeft/Top` return the scaled values
   when called on the render thread inside the world pass (ModelOutlines, VisibilityPolygon2 and
   its uniforms). The game thread never sees a scaled screen: culling, chunk work, UI and mouse
   are untouched.
4. `WaterShader` / `PuddlesShader` `WViewport.zw`, the pzopt puddle shader, `FogShader` /
   `ImprovedFogDrawer` `screenInfo.xy`, `ModelManager.RenderParticles` viewport: scaled at the
   site. `FogPass` already derives its buffers from the current viewport.
5. `MultiTextureFBO2.render()`: with an upscaler active the frame goes through
   `pzopt.Upscaler` (a `GenericDrawer` queued before the quad): the low-res region is upscaled
   into a screen-sized texture (`pzopt.UpscaleTexture`, a `Texture` subclass over a raw GL id),
   which the stock weather quad then draws; `WeatherShader.startMainThread` reports that
   texture's size as `TextureSize` so the stock bicubic stays 1:1.
6. `SavefileThumbnail` frames are rendered unscaled (a suspend / resume marker pair queued
   around them).

## Modes

| `upscaler` | Resolve | Needs |
|---|---|---|
| `off` | stock | – |
| `bicubic` | the stock screen shader samples the low-res region directly (its bicubic filter is the upscaler) | nothing (first milestone, pipeline check) |
| `fsr1` | AMD FidelityFX Super Resolution 1.0: EASU + RCAS (`media/shaders/pzopt_fsr1_*.frag`, MIT) | GLSL 3.30 |
| `dlss` | NVIDIA DLSS Super Resolution through NGX on a Vulkan device sharing the images with GL (`natives/libpzopt_ngx64.so`, `GL_EXT_memory_object_fd` + `GL_EXT_semaphore_fd`); camera motion vectors + depth + sub-pixel jitter (float viewport offset) | NVIDIA RTX, driver NGX (`libnvidia-ngx.so.1`), the DLSS library next to the natives |
| `xess` | Intel XeSS through the same shim (Vulkan) | Windows only (no Linux runtime); untestable here |

`upscalerQuality`: `quality` 67 %, `balanced` 59 %, `performance` 50 %, `ultra` 33 %, `native` 100 %
(DLAA-style for dlss). `fsrSharpness` 0..1 (RCAS, default 0.2 = FSR "quality" default).

## Milestones

1. bicubic at 50 % on the bench route uncapped: fps up, screenshots sane, `ups-off` parity.
2. FSR 1.0: EASU + RCAS ported from `ffx_fsr1.h`; screenshots vs stock; laptop numbers.
3. DLSS: shim (Vulkan device matched by UUID, exported memory + semaphores, NGX init / create /
   evaluate), Java side imports the images, copies the low-res colour + depth into the shared
   images, writes camera motion vectors, jitters the viewport; then per-object motion vectors for
   characters and vehicles (rect + depth band).
4. XeSS backend in the shim behind `_WIN32`, cross-compiled with mingw when available, shipped as
   experimental.

Run labels `ups-*`; the parity watch (pz-optimization-63) compares each `ups-<mode>` recording
against the matching `ups-off` recording of the same route (overlay panels off).

## Log

- 2026-09-22 06:20 — milestone 1 and 2 on the 120 km/h drive (E:1200, kmh=193, uncapped, 5120x2160, one build,
  `--install opt`): `upscaler=off` 509.5 fps / p99 7.7 ms (`ups-off-p-3`), `bicubic` 50 % 676 fps / p99 6.2 ms
  (`ups-bicubic-p-3`), `fsr1` 50 % 619 fps / p99 6.4 ms (`ups-fsr1-p-3`; EASU + RCAS ≈ 0.14 ms a frame at 4K).
  The GPU stays ~95 % busy at 50 %: the chunk bakes and the composite are not screen-pixel work.
- 2026-09-22 06:23 — first DLSS run segfaulted inside `libnvidia-ngx.so` at
  `NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements`: NGX init needs more than the 1 MB stack of the game's
  render thread (reproduced with `ulimit -s 1024` on the standalone test; fine at 4 MB). The shim now runs every
  entry point on one worker thread with a 64 MB stack.
- 2026-09-22 07:20 — DLSS on the 120 km/h drive, same build (`ups-build6`): `ups-dlss-p-3` (camera motion vectors
  only) 231.9 fps / p99 12.0 ms; `ups-dlss-p-4` (per-object motion vectors: characters + vehicles through stencil ids)
  231.8 fps (the object pass is free); `ups-dlss-f-1` (`dlssPreset=f`, the older convolutional model) 377.2 fps.
  The transformer model (preset M at performance) costs ~2.5 ms a frame at 5120x2160 output on the 4090 (GPU
  100 %, 346 W vs 282 W with fsr1). Bench route S:450 turn=90 uncapped: control 592 fps, DLSS 50 % 281 fps.
  Parity watch: bicubic, fsr1 and both DLSS variants pass (the bench pair with object motion vectors: transients
  0.98x the control, no trail on the player or the zombies, Jev parity 0.94 — "the cleanest pair today").
- Decisions: `dlss` / `xess` that cannot run (no RTX, no shim, split screen, XeSS not built) continue as `fsr1` at
  the same scale (`RenderScale.fallback`); the Windows DLL of the shim needs MSVC (src/native/README.md).
- 2026-09-22 07:30 — bench route S:450 turn=90 uncapped, one build: off 592 fps / p99 6.8 ms; fsr1 50 % 802 / 5.5;
  DLSS 50 % 281 / 12.2; DLSS 67 % (quality) 301 / 11.0 (the DLSS cost is per output pixel, not per input pixel).
  Jitter-sign A/B (`dlssJitterSign=-1`, run `ups-bench-dlss-jneg-1`): the car lettering doubles and blurs, so the
  viewport-offset sign as reported (+1, memory-row convention) is the right one; motion-vector sign confirmed by the
  drive pairs (no trail).
- 2026-09-22 07:40 — parity watch closed the pass with every judged mode passing: fsr1 50 % on the bench route
  (transients 0.85x the control, Jev parity 0.82), DLSS 67 % (1.04x, parity 0.88), on top of the earlier bicubic /
  fsr1 / DLSS 50 % pairs. Judging note from the watch: an auto window that slides onto the quit-to-black reads as
  black tiles + a hue shift; `parity-judge.py` / `colorshift.py` take `--window-a` / `--window-b` to pin a side —
  check the printed window against the route before trusting a black_tiles verdict.
- 2026-09-22 ~12:00 — committed in ba89d84 and released as win-b0bbce05d5-1555855 (release session). Maintainer's decision:
  no native libraries in releases (`scripts/release.sh` sets `PZOPT_DLSS=0`; the Workshop uploader also refuses any
  `.so`), so a released install runs `upscaler=dlss` as fsr1 with the logged reason; DLSS needs the shim and the DLSS
  library built and installed from a checkout (`scripts/build.sh` with the SDK under `~/.local/share/nvidia-dlss-sdk`,
  then `scripts/pzopt.sh install`).
- 2026-09-22 12:20-12:50 — DLSS presets and quality levels on the 120 km/h drive (E:1200, kmh=193, 60 s, uncapped, 5120x2160,
  recorded, overlay panels off, runs `upsd-*`, one build for every DLSS row and the `-2` references; the desktop had been on
  the natives-free release install since the updater test at 11:50, `pzopt.sh reinstall` from the checkout put the shim
  back, console `dlss: ready`). Mean fps / p99 / p99.9 ms / frames > 33 ms / sysmon GPU % and W; every run route
  complete, first attempt:

  | run | fps | p99 | p99.9 | >33 | GPU | W | soft % | trail % |
  |---|---|---|---|---|---|---|---|---|
  | off (`upsd-off-2`) | 483.6 | 7.6 | 12.3 | 0 | 99 | 309 | 0 (ref) | 0 |
  | fsr1 quality 67 % (`upsd-fsr1-q-2`) | 511.7 | 7.3 | 12.5 | 0 | 98 | 304 | 2.5 | 0.0 |
  | dlss quality, preset default | 249.4 | 11.9 | 17.5 | 1 | 100 | 343 | 15.3 | 0.9 |
  | dlss quality, preset k | 255.3 | 11.3 | 16.2 | 0 | 100 | 348 | 19.9 | 1.2 |
  | dlss quality, preset j | 248.5 | 10.9 | 18.1 | 0 | 100 | 346 | 12.2 | 0.2 |
  | dlss quality, preset e | 332.6 | 9.8 | 13.9 | 0 | 99 | 316 | 14.8 | 0.1 |
  | dlss quality, preset f | 324.3 | 9.9 | 14.2 | 0 | 99 | 310 | 15.5 | 0.3 |
  | dlss quality, preset m | 169.2 | 13.9 | 23.1 | 3 | 100 | 362 | 9.5 | 0.2 |
  | dlss quality, preset l | 149.5 | 15.2 | 23.8 | 0 | 100 | 364 | 11.9 | 0.4 |
  | dlss native (DLAA), default | 212.9 | 12.3 | 17.4 | 0 | 100 | 345 | 7.9 | 0.5 |
  | dlss balanced 58 %, default | 263.3 | 10.9 | 16.6 | 0 | 99 | 343 | 20.5 | 0.8 |
  | dlss performance 50 %, default | 235.6 | 11.9 | 16.1 | 0 | 99 | 353 | 18.2 | 0.2 |
  | dlss ultra 33 %, default | 277.8 | 10.8 | 15.2 | 0 | 100 | 343 | 30.9 | 0.9 |

  Readings: (1) at 5120x2160 on the 4090 fsr1 is the only upscaler that is a net win (+6 % mean, p99 7.3 vs 7.6); every
  DLSS preset costs more than the render-scale saving, the cheapest (the convolutional e / f) 30 % below off and the
  transformer ones (default = k at quality, j) 48 % below, with 25-28 % of the frames under 240 fps (e / f: 13-16 %,
  off: 6 %). (2) The DLSS cost is per output pixel: the quality levels move the mean by ± 10 % only (ultra 278, balanced
  263, quality 249, performance 236 — the driver picks a different network per level, so the order is not monotonic),
  DLAA at 213 is the price of the network alone. (3) Presets m and l (the transformer performance / ultra-performance
  models) are the heaviest networks at this output size (150-170 fps, 362-364 W, one `dlss: ready`, no re-creation)
  and are not usable at 4K. (4) The `-2` references booted the Display "fourth edit" build (window created at its final
  size): off 484.3 → 483.6, fsr1 525.1 → 511.7, i.e. the edit is a wash on the desktop drive. Verdict for the tab: keep
  `dlssPreset=default` as the quality choice; `e` / `f` are the DLSS presets to name when fps matters; at 4K/240 Hz DLSS
  is an image-quality option, fsr1 the performance one.
- 2026-09-22 13:30 — ghosting pass over the same 15 recordings (`harness/ghostscan.py`, new; whole-route window
  route_start+2 .. route_end-2, ~2,100 capture frames per run, HUD excluded). Two trail metrics and one
  detail metric, all measured identically for every run: `asym` (after aligning the previous and the next
  capture frame onto the current one, does a frame resemble its past more than its future? a renderer that
  leaks history leans on the past), `trail` (loss of gradient energy ALONG the scroll direction vs across it,
  relative to `off`), and `soft` (Laplacian RMS vs `off`). Noise floor from the repeat pairs (two runs of the
  same mode): soft 1.8 % (off), 0.3 % (fsr1).
  **No mode trails**: `asym` is within ±0.002 (SE 0.006) and `trail` within 1.2 % for all seven DLSS presets,
  every quality level, fsr1 and off alike — i.e. camera-motion reprojection on this route is working and the
  per-object motion vectors (`upscalerObjectMv`) are not leaving smears behind the car. What DLSS costs here
  is **detail, not trails**: the `soft` column of the table above, 2.5 % for fsr1 against 8-31 % for DLSS, and
  it tracks the render scale (ultra 33 % is the softest at 30.9 %, DLAA the sharpest at 7.9 %) rather than fps
  — the two heaviest networks, m and l, are among the sharper DLSS rows (9.5 / 11.9 %) at half the frame rate.
  Scope: a 120 km/h highway drive with no zombie crowd, captured at 60 fps AV1 (same encoder for every run),
  so character ghosting and low-contrast temporal flicker are not exercised; a null `asym` means "no trail
  above this rig's noise floor", not a proof of none. Earlier attempts that did NOT work, for the next person:
  a two-predictor regression of the frame on (re-projected previous, unshifted previous) — the two predictors
  are collinear at 4 px/frame and the fit returned noise; and sharpness of the moving phase against the parked
  settling phase — different scenery, so the ratio measured content, not the renderer.
- 2026-09-23 — second pass (branch `upscaler-fix`, session pz-optimization-87): the FSR "second view cone", and
  the DLSS cost at 5120x2160 on the 4090 taken apart. Runs `vcone*`, `dl1-*`, `dl2-*`, `dg-*`, `sf1-*`, `sw*`; every
  number below is the harness route window (`result.txt` / analyze.py `overlay:`), uncapped, quality = 67 %.
  - **Second view cone (fixed).** The view-cone shadow's screen quad (`visibilityBlur`) writes the shadow's depth
    from `displaySize`, the offscreen buffer's size, which the scaled world pass left unscaled while `screenSize` /
    `displayOrigin` were scaled; the depth came from a copy of the shadow shrunk by the render scale towards the
    bottom-left, and the depth test (less-or-equal) cut the true cone with that copy: wedges missing and a second
    apex. New `VisibilityPolygon2` override scales `displaySize` (docs/override-edits.md). Shots `vcone2-*` at max
    zoom-out: against no upscaler the stock value's diff shows straight-edged cone wedges, the fix's only cloud
    noise. Same override: its closing integer viewport restore now puts the DLSS jitter back.
  - **A correction for whoever reads the raw logs**: `dl1-off` stalled on the route (`route_complete=0`), so its
    whole-log 774 fps is mostly a parked car; with it every upscaler looked slower than off. `dg-off-2` (route
    complete) is the drive reference, and bicubic at 99 % (`dg-bicubic-99`, 464 fps) equals it: the scaled pass
    costs nothing by itself.
  - **Where the frame goes.** Drive (E:1200, 120 km/h, zoom 2.5): off 468 fps, bicubic 67 % 530 (+13 %), fsr1 486
    (+4 %), bicubic 50 % 646; dlss E 327, dlss default (K) 249. Storm + fog spinning route: off 266, bicubic 321
    (+21 %), fsr1 290, dlss E 226, dlss K 186. So in the moving scenes even a free upscaler gains 13–21 % at
    quality: most of the world pass does not follow the render resolution. Per frame (GPU sections): chunk bakes
    (world pixels whatever the zoom or scale; lightning / light re-bakes are paced per second, so their per-frame
    share grows as fps falls) and the chunk composite, which shrinks only 710 → 605 us for 44 % of the pixels
    (hundreds of chunk-level quads, each a texture + depth-texture fetch with gl_FragDepth: bound by draws, not
    fill). A near-static GPU-bound scene (storm + fog, walking 1 tile/s, zoom 1, `sw-z1-*`) is pixel-bound: off
    494, bicubic 814 (+65 %), dlss E 458, dlss K 328; at zoom 2.5 off 424, bicubic 584, dlss E 359.
  - **The DLSS path, measured piece by piece** (Vulkan timestamps around the evaluation in the shim, GL timer
    sections, render-thread timings, and GL `glQueryCounter` vs the evaluation's Vulkan start / end on the same GPU
    clock, `devDlssGaps`): evaluation E 0.64–0.74 ms, K 1.54 ms at 5120x2160 output (NVIDIA's table: 4090 at
    3840x2160 E 0.53 / K 1.06 ms, i.e. ours is on the table once scaled to 11 MP); GL input copies 0.13 ms (colour
    blit, depth pass, motion pass; the depth and motion passes are now one two-target pass); hand-over GL → Vulkan
    12 us; **Vulkan → GL 0.19–0.21 ms of idle GPU after every evaluation**. The render thread returns from
    `glWaitSemaphoreEXT` in 8 us, so the wait is on the GPU side: the GL commands behind it (the composite, the UI)
    stay in the driver's command buffer until its next flush. `dlssFlushAfterWait` (default on) flushes right after
    the wait; A/B runs `sw3-*` pending (the desktop was locked and Steam showed a blocking cloud-sync dialog from
    09:53; resubmitted with `--launcher direct`).
  - **Tried and rejected, measured**: one-frame pipelining (`dlssPipeline`, two image sets, the composite shows the
    previous evaluation): 241 vs 244 fps on the drive — GL and Vulkan are separate GPU channels that time-slice,
    the evaluation's wall time stretched to 2.7 ms, no overlap is gained. Auto-exposure off (`dlssAutoExposure`,
    now default off; the input is LDR at exposure 1): 1545 vs 1538 us, no change.
  - **What does help**: DLSS output below the screen size (`dlssOutputPct`), since the network's cost follows the
    output pixels; the stock screen shader's bicubic does the rest (`dlssOutputFilter=bicubic`, no extra pass; `fsr1`
    = EASU + RCAS first, 0.18 ms more). Drive, K: 247 → 283 fps at 75 %. Static storm-fog zoom 1, E: full output 454
    fps (−8 % vs off 495), 75 % 513 (+3.5 %), 67 % 528 (+6.7 %) — before the flush fix.
