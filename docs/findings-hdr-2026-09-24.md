# HDR output pass (2026-09-24)

Goal (maintainer, 2026-09-24): add HDR support to the game, loop until the result is visually stunning, consider every
option, implement them one by one, profile each and analyse it visually. All runs without Steam (`--launcher direct`).
Worktree `../PZ_Optimization-hdr`, branch `hdr` (from origin/master 79ffb19).

## The display chain on the desktop

- KWin 6.7.5, HDR on for DP-1 (AW3926QW, 5120x2160 @ 165 Hz), NVIDIA 615.71.09 (RTX 4090), Mesa 26.2.2.
- `kscreen-doctor -o`: SDR brightness 505, peak 1317 (overridden 1307), max average 315, min 0.0008 nits.
- The compositor's own description of the output (surface feedback `get_preferred`, what matters for encoding):
  luminances min 0, max 1012, reference 391; target 0.005..1012; max-CLL 1012, max-FALL 244; primaries = the panel's
  (near DCI-P3: r 0.683,0.312 g 0.251,0.703 b 0.153,0.053). That is kscreen's numbers times the brightness setting
  (1307 x 391/505 = 1012): **the HDR headroom above the desktop's SDR white is 2.59x (1.37 stops)**.
- `wp_color_manager_v1` v2: features parametric, set_primaries, set_luminances, mastering primaries/luminance,
  extended_target_volume, windows_scrgb; transfer functions gamma22, st2084_pq, ext_linear, bt1886, compound_power_2_4;
  all named primaries.
- NVIDIA EGL on Wayland: no `EGL_EXT_gl_colorspace_*` (no PQ / scRGB colorspace attribute), but FP16 window configs
  (`EGL_EXT_pixel_format_float`, 16/16/16/16) and no 10-bit ones. Vulkan on Wayland: HDR10 ST2084 and extended sRGB
  linear swapchains (the GL-to-Vulkan bridge is possible; the DLSS shim already uses GL_EXT_memory_object_fd).

## Output probe (tools/hdr/hdrprobe.c + probe-capture.sh, capture via gpu-screen-recorder av1_hdr)

Fullscreen FP16 surface, patches at known nits, one frame per mode decoded as PQ (`pqpatches.py`). The capture is
KWin's output re-anchored to a 203 cd/m² reference (reference white 391 -> 203, peak 1012 -> 525, clip ~521):

| mode | 80-nit patch | 203 | 505 | 1000 | reads as |
|---|---|---|---|---|---|
| no description (plain FP16 surface) | 6.0 | 47.4 | 353 | clip | SDR surface decoded with **pure gamma 2.2**, 1.0 = reference white, **values above 1.0 are shown** (extended) |
| windows_scrgb (value = nits/80) | 80.2 | 202.4 | 502.8 | clip | scRGB 203 is anchored to the reference white |
| PQ, luminances left default (ref 203, mastering 0.005..10000) | 58.1 | 105.5 | 155 | 195 | KWin tone-maps the 10000-nit container into the panel: even mid-tones compress |
| PQ, ref 391, mastering = the panel | 41.3 | 105.5 | 260 | 526 | absolute: PQ nits = panel nits |
| ext_linear, luminances = the preferred ones (1.0 = 1012) | 108.6 | 272.8 | clip | clip | value * 1012 = panel nits, reference 391 at 0.386: pass-through |

Choice: **FP16 + ext_linear / sRGB primaries with the output's own luminances and mastering range** (no compositor
tone mapping, 1.0 = panel peak, encoding is one multiply). Gamma 2.2 is the decode for SDR-encoded values (KWin's own
SDR decode), so the HDR container at expansion 0 reproduces the SDR window exactly.

## Implementation (v1)

- `pzopt.HdrWayland`: the color-management protocol over libwayland-client with the JDK FFM API (interface tables
  built in native memory, `wl_proxy_marshal_flags` downcalls, upcall listeners, private event queue). No native
  library ships (release policy).
- `pzopt.Hdr`: FP16 window hints (Display.create), surface tagging, the world expansion appended to the stock
  `screen.frag` through a new `ShaderUnit` override (one line at glShaderSource), a 256x128 world-luminance mip chain
  on the GPU (scene-adaptive threshold: night vs day), and the encode pass at the swap (copy the back buffer, gamma
  2.2 decode, UI white -> the desktop's reference, roll-off into the peak).
- Keys: `hdr`, `hdrUiNits`, `hdrPaperPct`, `hdrPeakNits`, `hdrItmPct`, `hdrSaturationPct`, `hdrTune` (dev: live tuning
  file with `[sweep]` sets, dumped on `shot_at` holds into `<run>/hdr/`, read by `tools/hdr/hdrframe.py`).

## Options list (every one gets implemented, measured and looked at)

1. Output path: native Wayland FP16 + color management (v1, above).
2. Output path: GL -> Vulkan HDR swapchain bridge (XWayland / compositors without FP16 EGL).
3. Output path: gamescope `--hdr-enabled --hdr-itm-enabled` (no code; comparison point for our ITM).
4. Output path: Windows (scRGB FP16 via DXGI interop / WGL float) and macOS EDR (Metal bridge) - outside this desktop.
5. World inverse tone mapping in the composite (scene-adaptive threshold, luminance / max-channel key, curve).
6. World paper white below the UI white (more headroom for highlights; the headroom here is only 2.59x).
7. Bloom / glow from HDR highlights.
8. Light-source emitters: lamps, fire, headlights, torch, lightning pushed to the peak (engine knows where they are).
9. HDR world buffer (RGBA16F offscreen) so shaders (fire, light cones, puddle speculars) can write > 1.0.
10. Wide gamut: chroma expansion into the panel's P3-class gamut.
11. Eye adaptation / exposure when moving between dark interiors and daylight.
12. Options tab entries (HDR on/off, brightness, highlight strength) + the stock-vs-HDR preview.

## Log

- **03:11 hdr1 (job 1809)**: never ran. `--install opt` refused to overwrite a DLSS native left in the game dir by the
  Install-DLSS-files button; the reinstall had already uninstalled. HDR builds use `PZOPT_DLSS=0` (no natives), as releases.
- **03:20 hdr1-nighttorch**: HDR path up on the first try (FFM protocol, FP16 back buffer, description attached:
  `ext_linear sRGB, 1.0 = 1012 nits, reference white 391`), but every frame black: the appended world expansion did not
  compile (util/math.h declares `float max(float,float)` and float/vec2/vec3 `clamp`, which hide the builtin `max(vec3,
  float)`), the game kept a broken composite. Fixes: only math.h-safe forms in injected GLSL; `patchShader` test-compiles
  and falls back to the stock source; `tools/hdr/glslcheck.c` + `patchcheck.py` compile every injected / own shader on the
  driver before a build.
- **03:30 hdr3**: frames visible, every sweep set identical: `glProgramUniform` from the stats pass never reached the
  composite. The uniforms now go in `WeatherShader.startRenderThread` (program bound). The maintainer's
  `~/Zomboid/pzopt/options.ini` has `overlay=true`: visual runs pass `--prop overlay=false`.
- **03:37 hdr4-night (sweep2)**: expansion works (peak 391 -> 857 nits), but on the torch scene it lifts **albedo**,
  not light: the cream rug tiles and the player's legs went HDR, the torch beam barely moved. SDR night in PZ is
  median 0 / p99 17 nits; the stock composite's contrast(1.2) about 0.4 pushes dark pixels below zero. A luminance-keyed
  ITM cannot tell a lamp-lit floor from pale paint -> the light map (option 5b below).
- Option 5b, **light map** (`pzopt.HdrLight`, tune `light`): per frame the visible squares of the player's floor are read
  from JNILighting's cached `lightInfo` (VarHandle on the private field: no JNI call, no dirty-bit side effects) into a
  <=256x256 RGBA8 texture, alpha = excess over the frame's median light; the composite maps its UV to iso squares with an
  affine transform and scales the pixel by `1 + (headroom-1) * light * excess^curve`. Bloom (13-tap down / tent up chain
  from the energy the gains add) shares the gain code.
- **04:07-04:31 light map debugging** (hdr6-hdr10): `lightInfo` is the square's base light only (flat 0.161 at
  night: the torch is not in it); the per-vertex `cacheVertLight` (floor corners 0-3) is what the renderer draws with and
  holds the torch / headlight cones -> the map reads those. With the maintainer's options the world renders at 67 %
  (DLSS output 67 %) and the composite samples the upscaler's texture, so `vUV` is not the world texture: the map and the
  bloom are mapped from window pixels (`gl_FragCoord`). Debug views (`debugView` 1 excess in red, 2 per-square
  checkerboard, 3 raw light) and a CPU self-check (player's square through the same affine map) confirm the projection:
  the checkerboard sits on the floor tiles, the excess covers the torch-lit floor exactly.
- **hdr10 (light gain)**: p99 54 -> 131 nits, p99.9 205 -> 497, peak 591, only the lit area rises, colours intact.
  With the gain capped at the panel headroom (2.6x) the lit floor (0.2-0.3 linear in SDR) never gets near the peak:
  `lightMax` (default 4x) is its own knob, `lightCurve` > 1 concentrates it into the hot core. Luminance ITM in daylight
  turns white road paint into 2-3x highlights (paint is not a light): `itmDay` (default 0) scales it by the night factor.
  Map build 0.17-0.38 ms/frame on the game thread -> moved to a worker (`pzopt-hdr-light`, frames FREE -> BUILDING ->
  BUILT -> UPLOADING; the mapping is recomputed with the current frame's camera when queued).
- **04:35 desktop locked** (LockedHint=yes): the game froze at world entry (f:1) and the queue holds every job. Offline
  meanwhile: `pzopt.HdrFlash` (ThunderStorm's private PlayerLightningInfo via VarHandles; a strike lights the view
  evenly so the light map misses it -> global gain `flashMax`, default 3x at a full strike); `run.sh --wrap` (gamescope);
  Scene flags `fire=N` and `lights=on` for HDR scenes.
- Mesa (AMD / Intel) EGL on Wayland also has FP16 (`AB48`) and 10-bit window configs: the same path should work there;
  RGB10A2 + PQ would halve the back-buffer bandwidth on those drivers (NVIDIA has no 10-bit Wayland config).
- macOS: the MacBook's panel offers **16x** EDR headroom (NSScreen maximumPotentialEDR 16, macOS 27). GLFW cannot make
  a float back buffer on macOS, so the path there is the GL->Metal bridge (the vrr session's `MacPresent`, uncommitted)
  with RGBA16F IOSurfaces, `wantsExtendedDynamicRangeContent`, extended-linear sRGB, and the world gain carried through
  the 8-bit back buffer in its alpha channel (written by an alpha-only pass after the composite: the composite itself
  is drawn with blending on). Needs the maintainer's OK to rebind runs to the Mac.
- Depth-aware light map (idea): `IsoDepthHelper` depth is (x+y)-linear per chunk (0.00144 per square, one level
  0.00289); depth + screen x would give each pixel its own square, so walls and characters would sample their own light
  instead of the floor square behind them. Only if the tuning shows lit walls looking wrong.
- **Option 2 (GL -> Vulkan HDR bridge), evaluated and not built:** NVIDIA's X11 / XWayland Vulkan swapchains offer only
  `SRGB_NONLINEAR` (vulkaninfo, xcb surface), so a bridge would not give XWayland windows HDR either, and on native
  Wayland both NVIDIA and Mesa EGL already give the GL path an FP16 surface. A bridge only matters where neither holds.
- **Option 4 Windows (written, untested: nothing here can run it; `pzopt.HdrWin`):** GLFW's WGL path never picks a
  float pixel format (it only takes `WGL_TYPE_RGBA_ARB`), so Windows takes the Mac route: the 8-bit back buffer with the
  alpha-carried gain, a D3D11 device and a flip-model DXGI swap chain (R16G16B16A16_FLOAT, `SetColorSpace1(RGB_FULL_G10_
  NONE_P709)` = scRGB, tearing flag when available) on the game's HWND, a D3D11 FP16 texture shared with GL through
  `WGL_NV_DX_interop2` (lock, HdrMac's encode scaled to scRGB by SDR white / 80, unlock, CopyResource into the current
  buffer, Present with the game's vsync). Peak from `IDXGIOutput6::GetDesc1`; SDR white = `hdrUiNits` or 200 (the slider
  is not read yet). COM vtable slots from d3d11.h / dxgi1_6.h through FFM. Open risk: a flip-model swap chain on an HWND
  that already has a GL pixel format; `hdrWinFlip` if the interop maps rows the other way. Needs the Windows boot.
- **Option 4 macOS (written, untested):** `pzopt.HdrMac` (EDR CAMetalLayer, RGBA16F IOSurfaces, legacy-GL-safe EXT
  framebuffer calls, GLSL 1.20 encode that decodes gamma 2.2 x the alpha gain, soft knee into the current EDR headroom)
  + `Hdr.alphaGain` (alpha-only gain pass after the composite; the composite is not patched on macOS; stats / bloom need
  GL 3 and are skipped there). Waits for the maintainer's OK to run on the Mac.
- **Light reach** (`lightReach`, levels, default 0.6): the map is the floor's light, so a wall face or a character took
  the light of the square behind it. In this view the visible faces point at the camera, so the floor in front of a
  vertical surface is below it on screen: the gain takes the max over samples down to the reach (0.9 / 0.8 / 0.7
  falloff). Side effect: a lit floor pool also reaches that far up-screen onto the floor behind it; the sweep decides.
- `tools/hdr/hdrvideo.py`: per-second peak / p99.9 / mean panel nits of an AV1 HDR recording (capture nits x ref/203);
  checked on the probe capture (clipped patches read 1012-1030 = the panel peak).
- **09:04 unlocked.** KWin's preferred description now: reference 505, max 1307 (the brightness setting moved; headroom
  still 2.59x).
- **hdr13-lights (sweep8, lights + 3 fires on a road by a sheriff car):** SDR p99.9 379 / max 505 nits; HDR p99.9
  969-1061, max 1215-1274 (the panel's peak), average 13-19 nits (max-FALL 244: no ABL risk). lightMax 6 pushes p99
  251 -> 374. Looks: the fire-lit road rises by about the UI white, flames to 2-3x, but the pool is a flat bright
  plateau (the vertex light saturates at 1.0 over most of it) and colourless (white-grey where firelight should be
  warm). -> the light map now also sums, per square, the lights the native lighting found reaching it
  (`ResultLight`: position, radius, colour, occlusion already applied): (1 - d/r)^2 per light, unclamped, so the
  source square is hottest; map alpha = vertex excess x mix(1, analytic, `lightHot` 0.7); map rgb = the light's chroma,
  the gain tinted by it (`lightTint` 0.6). Bug found: the worker's absolute put hit the previous map's limit when the
  map grew (IndexOutOfBounds, stale map) -> `clear()` first.
- **Cost (hdrperf-off / -on, uncapped spinning bench, 5120x2160, native Wayland both, DLSS 67 %):** 289.3 -> 272.8
  fps, mean 3.5 -> 3.7 ms, p99 11.4 -> 10.7, p99.9 19.5 -> 17.3 (tails inside the noise), GPU 87-96 % both.
  GPU sections per frame: hdr.encode 0.25 ms (copy + decode, bandwidth: ~350 MB of FP16 at 4K), hdr.bloom 0.065,
  hdr.stats 0.044; light map 0.003 ms on the game thread, 0.17 ms on its worker. Daylight: median light 1.0, excess 0
  everywhere (the day picture is untouched by the light map, as intended).
- **Storm (hdr14, lightning every 4 s, 23:00):** strikes lift the whole frame to a mean of 100-165 nits with p99.9
  1100-1230 and peaks 1284-1302 (the panel), against 5-20 nits between them; mean stays under max-FALL 244.
- **SDR vs gamescope ITM vs ours (hdrcmp-*, same lit night scene, t 10-28 s medians):** SDR peak 423 / p99.9 123 /
  mean 3.0; gamescope (`--hdr-itm-enabled`, SDR in at 150, target 1000) 459 / 43 / 1.0 - darker than plain SDR: it
  anchors SDR white at 150 cd/m² against the desktop's 505 and only its top end expands; ours 824 / 231 / 5.7.
- **Defaults now** (`hdr=true` alone): light 100 %, lightMax 4, lightCurve 1.5, lightHot 0.7, lightTint 0.6, reach 0.6,
  bloom 30 %, ITM 50 % at night only (threshold 0.12, `nightLo/Hi` 0.01 / 0.04: PZ daylight averages ~0.09 linear, the
  first band 0.015-0.12 counted daylight as half night and pushed 0.1 % of day pixels above white).
- **Day (hdr17-day):** median light 1.0, map excess 0: the day picture equals SDR (p50 45 / p99 167 nits both).
- **Side-by-side video 1** (`docs/media/hdr-night-fires-sdr-vs-hdr.mp4`, AV1 PQ): showed two faults, both fixed:
  (1) fire-lit ground got almost no gain - the excess was measured on the light's luminance, where orange firelight
  weighs a fraction of a white torch -> max channel (ambient median too); (2) a hard edge where a light's per-square
  list ends (a cone's side) cut into the smooth vertex light -> the analytic field is blurred (separable 5-tap, ~2
  squares) before it scales the excess. Re-cut after the fix: fire-lit car and road glow, flames at the peak, average
  3x SDR (39 vs 13 nits), 0.65 % of pixels above UI white. The horizontal edge at the player's row is the game's own
  torch cone (in the SDR pane too).
- **Bloom:** the pass works (debug view 4: a warm glow around the torch core and the fires) but at 30 % it is a
  subtle halo next to already-bright pools; left at 30 %.
- **Headlights (hdr16 / hdr19):** the harness race car never emitted light; `Base.CarNormal` with `headlights=on`
  does (battery 1.0, can emit light). HDR: the beam is hottest at the lamps (the analytic core from the light's
  position) and fades down the road; p99 40 -> 139 nits, peak 707.
- **Lightning fix:** the flash multiplied the light-map gain, so a lamp-lit room during a strike went 4x x 3x and blew
  to flat white (reel v1). A strike adds light from outside: gain = light gain + (flash - 1) x (1 - excess). After
  (hdrcmp-storm-ours2): strikes still lift the outdoors to p99.9 ~1100 nits, the lit room keeps its detail.
- **Showcase reel** `docs/media/hdr-showcase-sdr-vs-hdr.mp4` (39 s, 5120x2160, AV1 10-bit PQ / BT.2020, `tools/hdr/reel.sh`
  via a queue media job): stock SDR | HDR side by side, route-aligned: fires + torch (7.5 s), headlights (13.5 s),
  lightning storm (18 s; strikes are random per run, so the panes flash at different times). Stitch pitfalls: one
  capture came out at 120 fps (the others 60) and a stream-copy concat of mixed time bases reported 2541 s: every pane is
  `setpts=PTS-STARTPTS,fps=60` now.
- **Cheaper output path (`hdrEncode`, hdrperf-enc-true / -false, same session):** KWin shows an FP16 surface without an
  image description as SDR decoded with gamma 2.2, 1.0 = the reference white, values above 1.0 extended up to the
  headroom - exactly what the encode pass computes. Without the description and the pass: 255.3 -> 284.7 fps uncapped
  (mean 3.9 -> 3.5 ms, p99 11.3 -> 9.9; HDR off was 289), hdr.encode (0.26 ms GPU) gone. Looks (hdrcmp-noenc vs
  hdrcmp-ours2 captures): medians peak 1214 vs 892, p99.9 270 vs 248 - the same picture, only the peak roll-off missing.
  The protocol does not promise that behaviour, so `hdrEncode=auto` (default) skips the pass on KDE Plasma
  (XDG_CURRENT_DESKTOP) and keeps the ext_linear description elsewhere; `on` / `off` force it.
- **Roll-off moved into the world composite:** with the no-encode path the dumps' decode asked 2828 nits of a 1307-nit
  panel (hdr20); the composite now rolls the world off into the headroom (peak / UI white, knee at 75 %) itself. hdr21
  (defaults only, `hdr=true`, auto picked the no-encode path under KDE): p99 223, p99.9 725, max 1070 nits, average 10
  (SDR 4), 0.16 % of pixels above the UI white.

- **11:00-11:30 speculars and sky (`pzopt.HdrGlint`, tune `glint`, key `hdrGlintPct`) + sun lift (`sunMax`, key
  `hdrSunPct`):** water / puddle shaders write the HDR part they reflect (sun glint on the wave normal, sky Fresnel,
  lamp glints from the light map) into a second colour attachment during a glint-only pass; car bodies get speculars of
  their five model lights and lamp emitters; sunlit outdoor squares (aux map: outdoors x light) x `sunMax` (1.6) on a
  clear day. Runs hdr22-hdr28 (river shore 6450,5200 `find=shore`, 15:00).
- **11:35-12:20 daylight fixes (hdr29-hdr38; the character turns in place in every run now: `route=S:1 speed=0.1
  turn=45 hold=15`, maintainer's request):**
  - Glints leaked into the unseen water (fog of war: +35 nits mean where SDR is 0): the water shader darkens by
    `vertColour`, the glint did not, and the composite's colour match cannot catch a pixel that was dark already.
    Glint x `vertColour.rgb` in water and puddles -> 0-5 nits there.
  - Sun glint 4 -> 8 (`sunGlint`): at 4 the sparkles peaked near the UI white; now max +1010 nits on lit water.
  - **Daylight lifted 1.5x with the sun off** (river: SDR avg 51 -> 85 nits, uniform). Bisect (sweeps 17-20, dump JSON
    now carries flash / daylight / light-map median / seen squares): not the flash, not the glints, not dump order - the
    light map's ambient was the median over *every* square in the map, and the fog-of-war squares are drawn darkened, so
    a sunny day's median was 0.54 and all sunlit ground counted as lamp-lit (max excess 255). The median now comes from
    the squares the player sees (`JNILighting.vis` bit 2 through a VarHandle, no JNI; all squares below 64 seen):
    day median 1.0, excess 0, `nosun` = SDR (54 vs 51, the rest is the water); the base day is the intended sun lift.
  - The frame-average night key is capped by `ClimateManager.getDayLightStrength()` (`pzHdrH.y`): a dark frame by day
    is not night. Real night (night-torch preset, 01:00): daylight 0.0, night ITM and light gain as before (hdr38: p99 65
    -> 217, p99.9 389 -> 901, avg 4 -> 11 nits). `time_of_day=22` is still daylight in September; the river at 01:00 has
    every square lit 0.86-1.0 and the torch drops at f:872 - not a night scene, use the preset.
  - **Exit segfault fixed:** every native-Wayland run (HDR on or off) died at exit in libnvidia-eglcore on the JVM's VM
    thread (EGL / wl_display never terminated). Most exits are `Core.quit()` from the main menu (System.exit on the game
    thread), so a shutdown hook (`Hdr.addWaylandExitHook`, registered at window creation on Wayland) hands
    `Display.destroy()` + `glfwTerminate()` to the render thread (2 s bound); the render loop's own exit calls
    `Hdr.beforeExit()` too. hdr32-hdr38: crashed=0, no hs_err. Parking the render thread afterwards hung the exit
    (another hook waits on the render context, hdr31) - not done.
  - Options tab: `hdrSunPct` (0/30/60/100) and `hdrGlintPct` (0/50/100/200) entries added (not verified in the menus).

- **Comparison video** `docs/media/hdr-stock-vs-hdr-vs-enhanced.mp4` (46 s, 5120x2160, AV1 10-bit PQ / BT.2020,
  `tools/hdr/stitch-hdr-3way.sh` via a queue media job): 2x2 grid per scene (stock `enabled=false` | HDR with the plain
  highlight expansion only, `tools/hdr/tune/plain.txt` | HDR enhanced = defaults | legend), night torch + lamps + fires,
  river by day, storm; runs `hdrvid-<scene>-<pane>`, character turning in place; panes aligned on the route start plus a
  lag found by frame correlation (`LAG_<scene>_<pane>`, 0.05-0.5 s). Pane luminance from the video (approx. panel nits):
  night avg 2 / 3 / 7, p99.9 142 / 149 / 475; day avg 43 / 42 / 71, p99.9 350 / 346 / 529; storm avg 7 / 8 / 15,
  p99.9 138 / 149 / 268 (stock / HDR / enhanced). The plain expansion only moves the rare near-white pixels.

- **13:15-13:30 macOS EDR, first run on the MacBook (M1 Pro, macOS 27, GL "2.1 Metal - 91.7").** Found before
  running: Core creates the window with `PixelFormat(32, 0, 24, 8, 0)`, no alpha, so the alpha-carried world gain would
  have read 1.0 everywhere (SDR inside an EDR layer); `Hdr.windowHints` now asks for 8 alpha bits on the alpha-gain
  platforms (macOS, Windows) and `windowCreated` refuses the Mac path with fewer than 8. Mac frame dumps added
  (`Hdr.alphaGainFrame` / `writeAlphaGainDump`: pre = the 8-bit back buffer, post = the IOSurface Metal presents, in %
  of the SDR white, rows turned so hdrframe.py shows the glass picture; the log line compares row profiles for the
  orientation, since ssh screencapture is blocked); `run-mac.sh` collects `~/Zomboid/pzopt-hdr` into `<run>/hdr`.
  Run `mac-hdrmac1-torch` (night, torch, spinning route, `hdr=true hdrUntestedPlatforms=true hdrDumpAt=10,18,26`): EDR
  layer on first try, headroom 1.20 at creation -> 16.00 once EDR content is on screen, game 1920x1200 = drawable (no
  upscale), orientation upright in all three dumps (row-profile r 0.86-0.92 upright vs negative flipped). Inside the store
  (t10) the torch pool reaches 3.8x SDR white (5 % of pixels above white, 2 % above 2x); outdoors on grass (t18 / t26) the
  pool stays at 1.0-1.35x; UI at SDR white. Cost vs `mac-hdrmac1-off` (same build, `hdr=false`): both 59.8 fps at the
  Mac's 60 cap, GPU 50 -> 54 %, p99 41.4 -> 47.2 ms, >33 ms frames 48 -> 67 (one run each; the tail is the Mac's own,
  game thread 93 % of a core in both). Stats / bloom stay off on the 2.1 context.
- **13:25-13:35 macOS opened** (`Hdr.REQUESTED` takes macOS without `hdrUntestedPlatforms`; Windows still needs it).
  On macOS only the light map and lightning apply: the composite is not patched on the 2.1 context, so sunlight, glints,
  bloom, night ITM, saturation and world brightness are Linux only (said in the Options tips), and `hdrUiNits` /
  `hdrPeakNits` are ignored (EDR has no nits: 1.0 is the screen's SDR white; a UI white of 200 would have turned the gain
  headroom into 16 / 200 < 1, i.e. off). Run `mac-hdrmac2-options`: the game's own screenshot was black, because
  `Core.TakeFullScreenshot` reads GL_FRONT and nothing is swapped under the Metal layer (the same holds for MacPresent);
  HdrMac now blits the SDR frame into GL_FRONT each frame. `mac-hdrmac3-options` (harness `options_search=HDR`): the tab
  shows the HDR section and the macOS notes. `mac-hdrmac4-fs` (borderless): game 1512x982 -> EDR drawable 3024x1964
  through MPSImageBilinearScale, headroom 16, upright in all dumps, torch indoors 3.7x SDR white, 59.5 fps at the
  60 cap, p99 41.1 ms, GPU 63 % (54 % windowed at 1920x1200 without the upscale). Not checked: lightning and headlights.

- **13:40-13:50 `hdrAuto` (default on): HDR by itself on an HDR screen.** `hdr=true` stays the manual "always on".
  Linux: `HdrWayland.probeHdrOutput` opens its own libwayland connection in `Display.init` (before GLFW picks the
  platform, so SDR desktops keep XWayland), binds wp_color_manager_v1 and asks every wl_output for its image
  description (~100 ms incl. the FFM warm-up). KWin describes an HDR output as gamma 2.2 too (tf 2: the desktop reads
  reference 505 / peak 1307 nits) and gives an SDR laptop panel backlight headroom (the flip: reference 64, peak 200), so
  the rule is PQ / HLG, or a peak of at least 400 nits (DisplayHDR 400) and 1.5x the reference. Probe: desktop HDR, flip
  SDR. macOS: potential EDR headroom of at least 4 (XDR panels 16; ordinary panels only have some backlight headroom),
  asked in windowCreated. The decision is logged from windowCreated (`hdr: auto: ...`): Hdr is initialised before the game
  log exists. Runs `mac-hdrmac5-auto` (EDR 16 -> HDR on, upright, 3.3x) and `hdrauto-desk` / `-desk2` (Wayland picked,
  HDR on, clean exit). The harness writes `hdrAuto=false` unless a run asks, so baselines on this HDR desktop stay SDR.
- **Horde cinematic, directed by Jev (13:30-15:15, runs `showcase-*`, `cine-*`):** `pzopt.Showcase` (`showcase=horde`) +
  `harness/showcase-director.py`. The character's run, aim, fire and reload are the game's own input paths (the Mouse /
  GameKeyboard override hooks: `pressedAttack()` and `pathToLocation` alone fired or moved nothing reliable); Jev picks
  one of six actions from the scene's facts about 3 times a second (230-620 ms, 0.77-0.99 confidence). Darkest night:
  2 am, storm, heavy fog with a near-black tint (`fog_tint=dark`; the storm tint glowed beige at 16 nits median), power
  off, pitch-black sandbox darkness: median 0 nits, p90 < 2.5, highlights 750-970. Video `docs/media/hdr-horde-cinematic-jev.mp4`
  (local). Found on the way: `animatorParallel` stepped ragdolls on frame workers (Bullet crash, fixed, see
  `docs/override-edits.md`); open: a quit-time crash `Ragdoll::deleteRigidBodies` after heavy kills (ours 5/5, stock 0/2;
  a key bisect was stopped halfway: 79 boolean keys off = clean).

- **2026-09-25 00:00-00:40 flip report: every light bloomed indoors by day, only while facing north.** Rig: `pzopt.Explore`
  (`explore=restaurant`, Jev walks the character through a building by the movement keys, `harness/explore-director.py`)
  + `devHdrTraceMs` (per-facing console line: frame average, night key, light-map reference, seen / couldSee counts,
  medians). Cause: the light map's reference ("ambient") was the median light of the squares in the view cone, with a hard
  switch to all squares below 64 seen. Indoors by day it followed the facing: the cone on the dim room gave 0.2-0.4
  (every lamp and window pool at full gain, `maxExcess` 160-220, plus bloom), the cone on a wall (< 64 squares) or out of a
  window gave 1.0 (nothing). Run `flip-hdrnorth-walk-*` (bakery / grocery strip, noon): grocery W 0.40 / 220 vs SE
  1.00 / 0; janitor NW 0.69 / 176 vs E 1.00 / 0; "north" was the dim side of the maintainer's building. The night-torch
  spin had the same swing, smaller (0.16 facing E / S, 0.28 facing N). Fix (`HdrLight.build`): the reference is floored
  at `1 - Hdr.nightCap()` (the climate's daylight, the composite's own night cap), so by day nothing stands out, as the
  design said; at night the median is blended from all squares to the seen ones by the seen count (no hard switch) and
  eased over ~0.4 s. Verify, same building (`flip-hdrnorth-walk-fix-bakery-*` vs `flip-hdrnorth-walk-*`, grocerystorage
  dumps at the four quarter turns): frame mean 6.3 / 13.2 / 6.5 / 12.2 nits (p99 33-73) by facing before, 6.0-7.0 nits
  (p99 32-35) in every facing after; reference 1.000, `maxExcess` 0 in every room and facing. Pizza Whirled
  (`flip-hdrnorth-walk-fix-*`, 7 rooms) the same, but it is bright inside (room median 255): it never showed the bug.
  Night-torch spin (`flip-hdrnorth-night-fix-*` vs `flip-hdrnorth-trace-*`): the torch / lamps keep `maxExcess` 255 in
  every facing; the reference's swing by facing narrows (N 0.165-0.278 -> 0.166-0.243, SE max 0.396 -> 0.201).

## State (2026-09-24 10:10)

`hdr=true` on KDE Plasma 6 with HDR on gives: UI at the desktop's white, the world as SDR in daylight, lamp / torch /
headlight / fire light 2-4x brighter with a hot core at each source and the light's colour, lightning strikes up to the
panel peak, soft roll-off, +~0 ms a frame on KDE (no encode pass) / +0.3 ms elsewhere (encode). Showcase:
`docs/media/hdr-showcase-sdr-vs-hdr.mp4`. macOS EDR (`HdrMac`) is on since 13:35 (MacBook Pro XDR: 16x headroom, lights and lightning only). Untested:
Windows scRGB (`HdrWin`, needs a Windows boot). Open: the Options-tab section is written but not yet verified in the
menus; daylight has no HDR content (PZ has no speculars / sky to expand).
