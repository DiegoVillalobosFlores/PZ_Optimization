# Variable refresh rate (G-SYNC / FreeSync / ProMotion), 2026-09-24

Goal (maintainer, 2026-09-24): variable refresh working correctly in the game on NVIDIA, AMD and Apple silicon
Macs. Branch `vrr` (worktree `../PZ_Optimization-vrr`, cut from origin/master f51a663).

## Measuring it

A VRR display shows every frame the moment it is ready, so "correct" has three testable parts:

1. **VRR is on while the game runs.** Linux: the compositor asks the kernel for it through the CRTC property
   `VRR_ENABLED`; `harness/vrrprobe.py` reads it with libdrm (no DRM master needed) and every `run.sh` run now logs
   it to `<run>/vrr.txt`.
2. **On-screen timing follows the game.** `harness/presentprobe.c` (built on demand by `run.sh`) selects X Present
   `CompleteNotify` events for the game window from outside the game: `ust` is when each frame reached the screen
   (under XWayland: the compositor's presentation feedback, i.e. the page flip), `mode` flip / copy. The game logs
   per frame its step start, render-thread acquire, swap call / return, pacing hold and GPU completion
   (`pzopt.Pacing`, `Zomboid/pzopt-pacing.out`, on in instrumented runs). Both use CLOCK_MONOTONIC, so each frame
   pairs with its flip. On macOS the game reports on-glass times itself (Metal `presentedTime`, see below).
3. **Judder**: `harness/pacing.py <run>` prints `ON-SCREEN JUDDER |flip interval - sim step|`: how far the gap
   between two frames on screen is from the game-time step between them (0 = motion on screen matches game time),
   plus latency from step start to screen. At a fixed refresh at its maximum rate the flip timestamps sit on the
   refresh grid (91-94 %), the rest is timestamp noise, so ~1 ms mean is the floor of this metric.

## Desktop: NVIDIA RTX 4090, KDE Plasma 6.7.5 Wayland (game on XWayland), AW3926QW 5120x2160 165 Hz, VRR 48-165

KWin's VRR policy for the output was **Never** on this machine; set to **Automatic** for the tests
(`kscreen-doctor output.DP-1.vrrpolicy.automatic`; restore with `.never`).

| run (spin bench, S:450) | window | VRR_ENABLED | notes |
|---|---|---|---|
| vrr-borderless | stock borderless | **0 %** | vsync on, 60 fps |
| vrr-fullscreen | fullScreen=true | 100 % | |
| vrr-bfs-144 (and every later run) | borderless, `borderlessFullscreen` | **100 %** | |

**Finding 1: the game's borderless window never got VRR.** KWin (like Mutter and gamescope) enables VRR only for a
window in the fullscreen state; the stock borderless window is a screen-sized undecorated window. Fix
(`borderlessFullscreen=auto`, Linux): the borderless window is a GLFW monitor window at the desktop's own mode (no
mode switch; `_NET_WM_STATE_FULLSCREEN` on X11, `xdg_toplevel.set_fullscreen` on Wayland), auto-iconify off so it
stays up when focus moves away, `Display.isFullscreen()` still false so options.ini keeps `fullScreen=false`.

**Finding 2: with VRR on, uneven per-frame work is visible judder.** The limiter paces step starts perfectly
(100 fps cap: steps 10.00 ms apart, 0.26 ms jitter), but each frame then takes a different time to update, hand
off, render and finish on the GPU. At a fixed refresh the vsync grid hides that; with VRR every frame flips when
ready:

| 100 fps cap, VRR on | judder mean | frames off > 2 ms | step -> screen p50 | fps |
|---|---|---|---|---|
| presentPacing=off (vrr-100) | 3.10 ms | 54 % | 10.5 ms | 99.4 |
| cpu: hold swap to step + p90 of CPU-ready lag (vrr4-100-cpu) | 1.40 | 23 % | 13.1 | 99.6 |
| **gpu**: same, lag at GPU completion (GL_TIMESTAMP, async) (vrr4-100-gpu) | **0.95** | **13.5 %** | 16.1 | 99.6 |
| gpufinish: wait on the frame's fence, then hold (vrr4-100-gpufinish) | 0.85 | 10.9 % | 19.8 | 99.4 |

- `cpu` holds the swap but the GPU may still be drawing: the flip then lands 0.5-8 ms after the swap call.
  Measuring "ready" at GPU completion (a GL_TIMESTAMP query per frame, read back a few frames later and mapped onto
  System.nanoTime) makes an on-time frame complete at its swap: swap -> flip 0.44 ms p50, 1.2 ms p99.
- First version of `gpu` blocked on the fence (now `gpufinish`) and had no throughput guard: the render thread
  stopped overlapping the GPU, fell to 84 fps (63 with the p98 target) and the queue fed back into the lag
  target (34-48 ms step -> screen). The guard (never spend more than one cap interval on a frame) fixed it.
- `limiterSleep` (park until ~1 ms before the step, spin the rest): same pacing (judder 3.05 vs 3.10), game CPU
  406 -> 340 % of a core, busiest core 46 -> 36 %.

**Finding 3: running at the VRR ceiling is vsync with extra latency.** Player uncapped, vsync on:

| player uncapped + vsync | fps | judder mean / > 2 ms | step -> screen p50 |
|---|---|---|---|
| vrr=off (vrr6-off): at 165 Hz, 91 % of flips on the refresh grid | 163 | 1.48 / 22.9 % | 21.2 ms |
| **vrr=auto (vrr6-auto)**: detected VRR, cap 157, gpu pacing | 154 | **1.16 / 17.1 %** | **12.7 ms** |

`pzopt.Vrr` polls `VRR_ENABLED` in the game (libdrm through java.lang.foreign, a daemon thread, 2 Hz); while it is
on, `vrrCap` keeps the frame cap at refresh - refresh^2/3600 (157 at 165 Hz, 224 at 240) and `presentPacing=auto`
becomes `gpu`. Console: `[pzopt] vrr: VRR active (drm, 165 Hz), cap 157 fps, present pacing auto (gpu)`.

Swap behaviour: NVIDIA returns from the swap in ~0.1 ms under VRR (XWayland, explicit sync).

**Native Wayland** (`-Dzomboid.wayland=1`, vrr9-wayland, 2026-09-24 02:53, player uncapped, vsync on): the borderless
window (xdg fullscreen) gets VRR from KWin, `VRR_ENABLED` 1 from the window's creation to the exit, and `vrr=auto`
engages the same way as under XWayland: `vrr: VRR active (drm, 165 Hz), cap 157 fps, present pacing auto (gpu)`,
151.5 fps, sim-to-submit error 0.77 ms mean, swap call -> return 0.09 ms. The overlay's first line reads `cap 157 fps
VRR on (165 Hz), paced` (shot at 12 s). On-screen flip times are not measured there (`presentprobe` sees X windows only).

Every optimized native-Wayland run crashed **at the exit** (route complete, SIGSEGV in `libnvidia-eglcore.so.615.71.09`
on the JVM's VM thread during the Exit safepoint), also with `borderlessFullscreen=false` and with every runtime path
of this pass off (`vrr=off presentPacing=off pacingLog=false`); the stock path (`enabled=false`) exited cleanly.  It is
not the pzopt MangoHud-on-Wayland swap hand-off either (vrr9-wayland-nomh, `--no-mangohud`: same crash, same pc
`libnvidia-eglcore+0xa54691`). The 2026-09-19 Wayland runs (same driver, 615.71 since 09-15) never crashed, so some optimized path added since then tears down badly under
native EGL. Not a player path (XWayland is the default); left for a bisect outside the VRR pass.

## AMD: flip (Ryzen AI 9 HX 370, Radeon 890M, Mesa 26.2.3, KWin 6.7.5)

Both panels of the AYANEO Flip report no VRR (KWin "Vrr: incapable", connector `vrr_capable=0`; the 1080x1920 OLED
has fixed 60/90/120/144 Hz modes). What could be checked there (balanced profile, `--launcher direct`):

- borderless-fullscreen vs the stock window at a 120 fps cap on the fixed 120 Hz panel: same judder (1.78 vs 1.64
  ms), every present a flip in both, no regression;
- `presentPacing=gpu` on a fixed refresh: no gain (1.62 ms), +6.5 ms latency. So `auto` stays off without VRR
  (it does: `VRR_ENABLED` 0 there).
- radeonsi exposes GL_TIMESTAMP queries (the `gpu` mode works); Mesa's swap blocks ~5.6 ms under vsync (NVIDIA
  0.1 ms), so latency there is one frame higher regardless of pacing.

A real FreeSync test needs a VRR display on an AMD output (the desktop's Ryzen iGPU outputs are unused).

## macOS: M1 Pro MacBook Pro, 14" Liquid Retina XDR (ProMotion), macOS 27.0

No Xcode on the Mac, and the Metal HUD does not attach to GL, so the display's behaviour was measured with two
probes built with the command-line tools (`tools/mac/mtlprobe.m`: Metal presents; `tools/mac/glbridge.m`: a legacy GL
2.1 context like the game's, bridged to Metal), reading each drawable's `presentedTime`. NSScreen reports
`maximumFramesPerSecond 120`, refresh interval 8.33-41.7 ms, **update granularity 4.17 ms**.

| present (90 fps asked unless noted) | window | on-glass intervals |
|---|---|---|
| plain present / GL flushBuffer equivalent | any | 120 Hz grid: 8.3 / 16.7 ms mix (judder) |
| `afterMinimumDuration` | window | rounded up to 60 Hz |
| `afterMinimumDuration` | native fullscreen | **80 Hz exactly (12.5 ms, sd 0.000)** |
| `afterMinimumDuration` | borderless screen-sized (GLFW style) | 12.5 ms 76-90 % |
| `atTime` | fullscreen | 8.3 / 12.5 / 16.7 mix |

So ProMotion "VRR" is variable refresh in 4.17 ms steps (120, 80, 60, 48, 40 Hz ...), only for Metal presents with
a minimum duration from a fullscreen or screen-sized window. A GL game never gets it on its own.

**Metal present bridge** (`macPresent=on`, `pzopt.MacPresent`, java.lang.foreign + Objective-C runtime, no native
library): instead of glfwSwapBuffers, the GL back buffer is blitted into one of three IOSurface-backed rectangle
textures (glFlush is the hand-off, as in Apple's "Mixing Metal and OpenGL" sample; the prototype's pixel check
matches), copied into a CAMetalLayer drawable on the content view, and presented with the minimum duration of the
frame cap snapped to what the panel can show. `presentedTime` is fed back into `pzopt-pacing.out`.

| in game, fullscreen (1920x1200), player cap 90 | on-glass p50 | judder p50 / mean | swap -> glass p50 | step -> glass p50 |
|---|---|---|---|---|
| mac6-bridge-fs (3 drawables) | 12.50 ms (cap snapped 90 -> 80) | 0.00 / 1.70 ms | 44 ms | 62 ms |
| mac7-bridge-fs-80 (2 drawables) | 12.50 ms | 0.06 / 2.06 ms | 33 ms | 53 ms |

A minimum-duration present shows a frame one panel step after its predecessor, so at the panel's rate the drawable
queue stays full and the game settles at the worst phase. (In progress: phase control, see below.)

Phase control (`macPresentPhase`, `MacPresent.steer`: the limiter shifts the next step start by 30 % of how much
longer than 1 ms `nextDrawable` blocked): step -> glass 53 -> 39 ms at 80 Hz and 55 -> 38 ms at 60 Hz
(mac8-phase-80 / -60), but judder stayed or grew (80 Hz mean 3.1 ms, 60 Hz on-glass intervals alternating
12.5 / 20.8 ms, 70 % of frames off by > 2 ms).

**The 60 Hz alternation is the window server resampling a scale-1 layer.** The game creates no Retina framebuffer
on macOS (GLFW hint), so the drawable was 1920x1200 at contentsScale 1 on a 3024x1964 panel. Reproduced in the
prototype (`glbridge ... lowres=1`, 150 blended full-screen quads of GPU load): scale-1 layer in native fullscreen
60 Hz = 12.5 / 16.7 / 20.8 mix (65 % exact); a native-size drawable = 100 % 16.67 ms. Keeping the game's resolution
and upscaling on the GPU into a native-size drawable (`MPSImageBilinearScale`) = **60 Hz 100 % 16.67 ms (sd 0.000),
80 Hz 98 % 12.5 ms**, native fullscreen. A screen-sized borderless window at menu-bar level (GLFW's style) was
unreliable across probe runs (97 % one run, alternating another), so borderless on the Mac now enters a native
fullscreen Space (`macNativeFullscreen`). Neither glFinish nor glFlush as the hand-off changed anything
(mac8-60-finish). Also found: the IOSurface is filled upside down unless the GL blit flips rows (GL row 0 = bottom).

Latency of the minimum-duration path in the prototype: frame ready -> glass ~22 ms at 80 Hz, ~27 ms at 60 Hz (one
panel step plus the window server). `atTime` targets taken from the measured glass timeline (last presentedTime +
n periods) held 80 Hz at 99 % with sd 0.21 ms in native fullscreen but alternated in a scaled layer; plain presents
~12 ms but on the 120 Hz grid.

**In game with the MPS upscale and the native fullscreen Space** (borderless option, `macNativeFullscreen`, 2026-09-24
02:47, runs mac9b-bfs-60 / -80, `--prop enabled=true`: the Mac's `~/Zomboid/pzopt/options.ini` has `enabled=false`, so
a run without it is stock; mac9-bfs-60 was): game 1512x949 (the screen below the notch) -> drawable 3024x1898.
`devMacPresentCheck` (IOSurface rows vs the GL back buffer, the window cannot be screen-captured over ssh): 32/32 rows
upright at frames 1000 and 2000 in both runs, so the Y-flipped blit is right. The frame cap snapped 80 -> 60 before
`FrameCap.macAdaptive` counted the native fullscreen Space (fixed).

| run | fps | on-glass p50 | judder mean / > 2 ms | steps on the cap: shown exactly at the cap | step -> glass p50 |
|---|---|---|---|---|---|
| mac8-phase-60 (scale-1 layer, before) | 58.3 | 16.67 ms (12.5 / 20.8 alternating) | 4.19 / 71.2 % | 29.9 % (p50 4.11, p90 4.92 ms) | 38.3 ms |
| mac9b-bfs-60 | 57.9 | 16.67 ms | 1.90 / 20.9 % | **94.2 %** (judder p50 0.23, p90 0.82 ms) | 37.6 ms |
| mac9b-bfs-80 | 72.1 | 12.50 ms | 2.23 / 30.3 % | **83.5 %** (p50 0.29, p90 4.17 ms = one panel step) | 39.9 ms |

The rest of the judder is the game missing its own step (the M1 Pro does not hold 80 fps on the spin route: sim step
p99 30 ms). The bridge costs latency: a minimum-duration present shows a frame one panel step after its predecessor
plus the window server (~22-27 ms ready -> glass in the prototype vs ~12 ms for a plain present on the 120 Hz grid).
At 60 / 40 / 30 fps, rates the 120 Hz grid already shows, that latency buys little; at 80 / 48 it is the only way to an
even cadence. `macPresent` stays off by default (the maintainer's call: latency vs exact pacing).

## State (2026-09-24 ~03:00)

- Worktree `../PZ_Optimization-vrr`, branch `vrr`, nothing committed; build 12 = everything here.
- KWin VRR policy on the desktop is Never (the maintainer's setting); tests set Automatic only inside the job
  (`/tmp/pzopt-vrr-wayland.sh` pattern: trap restores Never).
- Docs written: `docs/override-edits.md` (Variable refresh section), src/CLAUDE.md (Vrr / Pacing / MacPresent rows),
  harness/CLAUDE.md (`pacing.py` row).
- Native Wayland VRR works (see Desktop); the overlay VRR line checked. Open: the native-Wayland exit crash (optimized paths since 09-19, not VRR code, not MangoHud; bisect by key); options-tab check in game; Windows untested (G-SYNC /
  FreeSync with GL need the fullscreen-covering window, which borderless already is there; `vrr=on` is the opt-in
  since Windows has no VRR state readable here); FreeSync needs a VRR display on an AMD output.
