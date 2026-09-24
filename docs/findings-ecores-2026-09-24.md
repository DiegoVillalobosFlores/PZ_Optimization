# E-core pass: power at a 120 fps cap on the flip (2026-09-24)

Maintainer's goal: split the game's work correctly between the performance and efficiency cores of hybrid CPUs, and on
the flip (Ayaneo Flip, Ryzen AI 9 HX 370: 4 Zen 5 + 8 Zen 5c cores, Radeon 890M, 1080p 120 Hz panel) reach a 120 fps
capped run in the balanced power profile at 12 W or less. Worktree `../PZ_Optimization-ecores`, branch `ecores` (from
origin/master 23f36f5). All runs: `bench-100` (the 100 s walk loop at the widest zoom), `--option frameRate=120`,
`--launcher direct --no-mangohud --schedmon 0.25`, balanced profile (EPP balance_performance), on AC; the flip's own
options.ini has `lightFPS=10` and `uiRenderOffscreen=true`. Power = the amdgpu `PPT` sensor (socket power, the SMU's
own number); `harness/corepower.py` for everything else.

## Measuring

- **Socket power** (`/sys/class/hwmon/hwmon4/power1_input`, label PPT) is readable without root; the flip's desktop
  idles at 3.4 W with the panel on.
- **SMU rails** from amdgpu `gpu_metrics` (format 3.0, 264 bytes, no root): socket, GFX, all cores, per core, GFX
  activity, DRAM MB/s. Per-core slots: `2 * core_id` for Zen 5, `core_id` for Zen 5c (mapped with a pinned busy loop).
  **One busy thread: 9.2 W on a Zen 5 core, 2.2 W on a Zen 5c core** (socket 17 W vs 6.8 W). The rails stop adding up to
  the socket figure once a GPU profile level is held (below): decisions use socket power only.
- Run-to-run noise of socket power on the same build: about ±1 W (origin/master: 34.0 and 31.1 W).
- MangoHud's `cpu_power` on this APU is socket minus GFX (SoC and memory included), not the cores.

## Baseline

| run | socket W | fps | p99 / p99.9 ms | where the CPU went |
|---|---|---|---|---|
| origin/master (JFR leftover, below) | 37.0 | 118.8 | 18.2 / 26.2 | game thread 0.98 cores (half of it spinning the limiter, 57 % on Zen 5), JIT 0.70, lighting 0.48 |
| origin/master, clean (2 runs) | 34.0 / 31.1 | 118.7 | 18.5 / 27.7 | as above |
| stock game (`enabled=false`) | 34.6 | 112.4 | 22.9 / 34.0 | game thread 1.09 |

A stale `-XX:StartFlightRecording=settings=profile` sat in the flip's launcher JSON (a `--jfr` run that died left it in
the copy the next run saved as the original); every flip run had JFR profiling on. `run.sh` now strips it unless `--jfr`.

## What was built, and what each did (socket W, same bench)

| change | effect |
|---|---|
| `limiterSleep` default on, one park to 0.2 ms before the step (timer slack 1 ns) | the stock limiter spun the game thread through half of every frame at 120 fps, at Zen 5 boost clocks |
| `corePlacement=auto` (all background threads on the Zen 5c cores; game + render + Mesa GL threads move between classes by the game thread's CPU p90 per frame) | with the limiter: 37 -> 29.9 W by an affinity rule alone, 28.5 W with the class; `efficient` (everything on Zen 5c) 29.1 W: once nothing spins, where the game thread runs changes little |
| `jitSteady` (C2 without speculative traps) | the walk deoptimized ~2,000 methods / 100 s (1,217 `unstable_if`) and recompiled 16,600; JIT 0.83 -> 0.43 cores, -1.4 to -2.1 W, frame tail slightly better. Trap limits 5/1: 0.59 cores; + tier-4 thresholds: 0.34 cores but a slower game thread (no net gain); C1 only: 28.8 W (worse) |
| `gpuPstate=auto` (amdgpu stable pstate on its own context, no root) | the 890M ran each frame's work at 2.7-2.9 GHz and idled; pinned at 1 GHz: -1.2 W, at 640 MHz (`min_sclk`): 23.9 W and still 120 fps. Profile levels switch GFX power / clock gating off in the kernel, which eats most of the GFX saving (the SMU's GFX rail falls from ~22 to ~5 W, the rest rises). `min_mclk` halves the frame rate |
| `visBlurReduce` | the vision cone's 25-tap blur once per vision texel: same image (screenshot diff), -0.13 ms GPU per frame; the pass is mostly its full-screen depth-writing blend, not the taps |
| `lightingSyncPark` | the lighting thread's 1 ms yield-spin per update removed |

**b9 (everything on, the defaults): 25.5 / 25.3 W, 119.8 fps, p99 17.3-17.6 ms, p99.9 22.7-24.1 ms**, vs 32.5 W mean
for origin/master and 34.6 W stock: -22 % against master, -27 % against stock, with a better frame tail.

## Other situations (same defaults)

| run | socket W | fps | p99 / p99.9 ms |
|---|---|---|---|
| the walk loop 3 times (300 s): loop 1 / 2 / 3 | 25.5 / 21.6 / 21.1 | 119.8-120.0 | 17.7 / 16.9 / 16.8 (p99) |
| standing still 100 s (governor put everything on Zen 5c) | 18.0 | 120.0 | 10.8 / 12.9 |
| walk, vsync on (the game option) | 25.4 | 119.7 | **9.7 / 16.4**, jitter 0.4 ms |
| walk, 60 fps cap | 21.4 | 60.0 | 27.7 / 36.9 |
| drive-120 (120 km/h path), placement + pstate off / on | 29.6 / **26.3** | 119.5 / 119.9 | 16.6 / 21.2 -> 14.6 / 20.0 |

In the later loops the JIT is done (0.03 cores) and the loop's chunks are known. The drive is where the "wide"
background mask mattered (background threads get every core while frames fall below the cap: 3.8 s of the drive).

## Power-saver profile (same walk, 120 cap; EPP `power`, every core capped at 2.0 GHz; runs ec-ps-*)

| run | socket W | fps | p99 / p99.9 ms | CPU |
|---|---|---|---|---|
| stock game | 21.1 | 92.8 | 32.2 / 52.0 | 4.07 cores |
| origin/master (2 runs) | 23.8 / 23.9 | 111.9 / 111.5 | 24.0-24.2 / 36.0-38.3 | 4.1 cores, JIT 1.4, all on Zen 5c |
| this branch (2 runs) | **19.2 / 19.6** | **118.9 / 118.7** | 21.4-21.5 / 31.8-33.2 | 2.7 cores, JIT 0.67, game thread on Zen 5 |

At 2 GHz nothing holds a clean 120 on this walk; this branch comes closest at the lowest power. The scheduler put
master's game thread on the Zen 5c cores (the cap removes Zen 5's clock advantage but not its IPC / L3); the governor
keeps it on Zen 5. The JIT takes longer at the lower clock (0.67 cores through the route).

## Where the remaining ~25 W goes

- **CPU ~1.9 cores**: native lighting thread 0.47 (10 Hz on this flip; 0.66 and +1.6 W at the stock 15 Hz), JIT 0.34-0.43
  (warm-up of a very large code base: ~7,600 compilations in the route, still tapering at its end), game thread 0.43 (on
  Zen 5, ~3.5 ms per frame), FMOD 0.12, render thread 0.10, chunk recalc 0.10, Mesa threads 0.13. 6,500 wakeups/s,
  none of it a wakeup problem (packing the background threads onto 4 or 2 Zen 5c cores: no gain).
- **GPU**: ~3.4 ms of GPU time per frame, 50-60 % GFX activity, ~33 GB/s DRAM. Render scale barely moves it: bicubic
  at 80 / 67 / 50 % = -0.9 / -1.4 / -2.2 W; zoom 1 instead of 2.5 (6.25x fewer world pixels) ~-1 W. At a fixed 1 GHz the
  frame's GPU time only grows 3.4 -> 4.2 ms: the work is not shader-bound.
- **Platform**: vkcube at 120 Hz draws 5.7 W (GPU 12 % busy at 1.15 GHz), so the SoC can run a light 120 Hz 3D load
  cheaply; KWin composites every game frame on the rotated panel (~10 % of GPU time, native Wayland the same).
- **Not ours**: inputplumber (the Flip's input daemon) 0.1 core, KWin 0.09 core.

## Frame-rate sensitivity

60 fps cap (b2 build): 21.2 W vs ~29 at 120 on the same build: ~0.13 J per frame, and a frame-independent ~13 W.

## Mac (M1 Pro, 6 P + 2 E), runs ec-mac3-* (b11, 120 fps cap, the Mac otherwise idle)

| run | system W (SMC, display included) | fps | p99 / p99.9 ms | game CPU |
|---|---|---|---|---|
| `limiterSleep=false` (the old default) | 35.2 | 117.5 | 28.6 / 50.0 | 277 % of a core |
| `limiterSleep=true` (the new default outside Windows) | **27.1** | 118.0 | 28.4 / 46.9 | 227 % |
| `corePlacement=off` | 29.5 | 116.8 | 29.8 / 51.9 | 236 % |
| `corePlacement=auto` (QoS classes) | 30.1 | 117.8 | 28.7 / 48.8 | 236 % |

The limiter park is the Mac's big win (-8 W); the QoS classes keep the game and render threads off the two-core E
cluster (with placement off it was saturated in an earlier pair, 99.8 % busy with other processes on it): a slightly
better tail, power within the SMC reading's noise. Power there: `top`'s energy impact equals %CPU on this Mac, IOReport's
CPU energy counters stay frozen (GPU energy works, 4.9 W in all four), no sudo for powermetrics. Two earlier pairs were
discarded: the Mac was in interactive use (26 fps, 437 s routes).
