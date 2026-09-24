# Plan: native Wayland as the default display path

Started 2026-09-19 evening. Goal: clear numbers for `-Dzomboid.wayland=1` (native Wayland
window) against the XWayland default, with MangoHud working the same way on both, so the
launch default can be chosen on evidence.

## What is known (2026-09-19)

| Run | Display | fps mean | frame mean / p99 / p99.9 (ms) | render thread ("main") CPU |
|---|---|---|---|---|
| `fpscap-stock240` | XWayland | 215 | 4.6 / 13.3 / 19.7 | 26–28 % |
| `wl-bench-1` | Wayland | 196 | 5.1 / 14.8 / 22.3 | 81 % |
| `wl-bench-jfr-1` | Wayland + JFR | 196 | 5.1 / 14.8 / 21.7 | 81 % |
| `wl-bench-glthr-1` | Wayland + `__GL_THREADED_OPTIMIZATIONS=1` | 196 | 5.1 / 15.0 / 21.2 | 81 % |

All capped at 240, bench route, max zoom, `--no-dashboard`, NVIDIA GL 615.71.

- Streaming metrics (chunk latency, chunks/s, streamer busy) are identical; the loss is in
  the render step.
- JFR (`wl-bench-jfr-1`): the render thread is blocked only 17.9 % of the route waiting
  for the game thread (`SpriteRenderer.waitForReadyState`), and the MangoHud swap hand-off
  (`Display.pzoptHudSwap`) is 3 % of its samples. The rest is real work: 15 % of samples
  inside `glDrawRangeElements` (driver CPU), then `RingBuffer.add`, `glUseProgram`.
- On XWayland (`attr-jfr-budget`) the same thread's top frame is `glGetInteger` from
  `WeatherParticleDrawer.render` (26 % of its samples) and `glClientWaitSync` from the
  persistent VBO map: sync points where the thread sleeps while the driver works elsewhere.
  `glDrawRangeElements` is not in its top ten.
- Total process CPU is the same on both (272 vs 279 % of a core). So the driver's
  command-building work moved from a non-Java thread (invisible to `pzopt-threads.out`)
  onto the render thread. Reading: NVIDIA's threaded optimisation runs under GLX/XWayland
  and not under EGL/Wayland. Forcing `__GL_THREADED_OPTIMIZATIONS=1` did not change it
  (`wl-bench-glthr-1`), although both `libnvidia-glcore` and `libnvidia-eglcore` read that
  variable.
- The "ZGC regression" seen first was a parsing bug: older `gc.log` files use comma
  decimals, and `analyze.py` counted zero cycles for them. Fixed; cycle counts match.

## Outcome (2026-09-19 22:15)

Answered; see `docs/archive/2026-09-24/results.md` (2026-09-19 20:48–22:15). Native Wayland is within the
noise floor of XWayland at the 240 cap on bench and drive routes. The one structural
difference is that NVIDIA's threaded-optimisation worker thread exists under GLX
(XWayland) and not under EGL (Wayland), so the render thread carries the driver's
command-building itself. That is invisible capped and costs 12–17 % of frame rate uncapped (measured 22:20–22:50, `docs/archive/2026-09-24/results.md`). XWayland
stays the default; native Wayland remains available through the `--env` flag for the
scaled-desktop case it was built for. MangoHud CSV and HUD work on both; only the
cosmetic fps-metrics reset key is Wayland-incapable, and the harness now skips it there.

Not pursued: `ydotool` (uinput) plus handing MangoHud GLFW's `wl_display` through its
exported `eglGetPlatformDisplay` from the `Display` override would make keybinds work on
Wayland. It needs a root-owned daemon for a cosmetic reset that the rolling metrics
window makes moot after ~45 s.
