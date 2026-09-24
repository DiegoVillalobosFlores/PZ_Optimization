# Input latency: keyboard, mouse and controller (2026-09-24)

Maintainer's request: profile input lag for keyboard + mouse and controller (no Steam: every run `--launcher
direct`), then try every input-lag reduction technique, implement and measure each, NVIDIA Reflex included.

## Measuring it

`run.sh --inputlag <script>` (harness/inputlag/short.txt, kbm-pad.txt): harness/inputlag-drive.py creates a uinput
keyboard + mouse and an Xbox 360 pad before the launch and plays the script into the game (keys W/S, right-mouse
aim with pointer sweeps, left stick walk, LT + right stick aim; random gaps keep presses off the frame phase; keys
and mouse are only sent while the game window has focus). Mode play on the bench save, god mode, the player is got
out of the drive bench's car first (the save starts in one: W/S were throttle and brake in the first runs), the pad
is given to player 1 half-way. The harness-only probe `pzopt.InputLag` (flag `inputlag=1`) stamps every stage in
epoch µs, the clock the driver stamps its presses with; `harness/presentprobe.c` (copied from the vrr branch) logs
when each frame really reached the screen (X Present CompleteNotify: the compositor's page flip).
`harness/inputlag.py <run>` lines every press up with:

| stage | what |
|---|---|
| os | the render thread's glfwPollEvents handed it to lwjglx |
| smp | the render thread's input poll copied it into the polling state |
| game | the game thread's frame swapped it in (the first frame that sees it) |
| push / swap / gpu | that frame handed to the render thread / its swap returned / the GPU finished it |
| screen | the flip that showed that frame |
| react | player 0 first changed (position, facing, aim) |

`harness/inputlag.py <run> <run> ...` prints one table, input class x run, p50 input -> game / gpu done / screen.
Desktop: RTX 4090, KDE Plasma Wayland (game on XWayland, flips), AW3926QW 5120x2160 **165 Hz**.

## What the stock pipeline does

1. The render thread pumps OS events and polls the input caches only right after its buffer swap; the polled
   state is then frozen (`wasPolled`) until the game thread swaps it in at the start of its next frame. A press
   waits for the next swap, then for the next game frame.
2. `GameKeyboard.update()` fills the key table from the *previous* poll and swaps the new one in at its end: the
   keyboard is one frame behind the mouse and the pad (4 ms at 240 fps, 17 ms at 60).
3. With vsync on and an uncapped game the frame then queues: ~6 ms in the one-slot hand-off to the render thread
   and ~6 ms in a swap that blocks until a buffer is free, i.e. two refreshes before the GPU even starts it.
4. Right-mouse aim needs a 0.15 s hold (`Mouse.isRightDelay`: a shorter click is the context menu). On foot the
   player reacts in the frame that reads the input (no animation delay before walking or turning).

Our default build and stock (`--prop enabled=false`) are the same at every stage (runs il-kbmpad-240, il-stock-240).

## Results

### The maintainer's settings: 60 fps cap, vsync on (runs il-60-base, il-60-all)

| input -> screen, p50 | default build | keyboardFresh + inputLatch + reflexSleep |
|---|---|---|
| keyboard (walk) | 47.1 ms | **17.9 ms** |
| mouse (aim sweep) | 32.6 | **13.6** |
| right button | 37.6 | 26.9 (+ the 150 ms aim hold) |
| pad left stick (walk) | 35.8 | **15.1** |
| pad right stick (aim) | 29.4 | **13.9** |
| pad LT | 28.1 | **8.5** |

Nothing queues at a 60 cap, so `reflexSleep` sleeps 0 there; the gain is the fresh keyboard and the latch. What is
left: ~6 ms average wait for the next frame start (half a 60 Hz frame), ~2 ms building, ~4 ms of GPU time (the
GPU clocks down at a 60 cap: 1.3 ms per frame at 240 fps; NVIDIA "Prefer maximum performance" would cut it) and
the wait for the next vblank.

### 240 fps cap, vsync off (runs il-kbmpad-240, il-kf-240, il-latch-240, il-240-all2)

| input -> game / gpu done / screen, p50 | default | keyboardFresh | inputLatch | all three |
|---|---|---|---|---|
| keyboard | 9.4 / 11.7 / - | 4.6 / 7.0 / - | 6.5 / 8.9 / - | **2.2 / 5.4 / 6.9** |
| mouse | 5.5 / 8.0 / - | 5.1 / 7.6 / - | 2.2 / 5.1 / - | **2.0 / 4.2 / 5.5** |
| pad left stick | 5.7 / 7.8 / - | 5.8 / 8.1 / - | 2.0 / 4.0 / - | **1.9 / 4.6 / 7.1** |
| pad right stick | 5.2 / 8.2 / - | 5.3 / 8.1 / - | 2.2 / 5.2 / - | **2.5 / 4.9 / 7.1** |

(The early runs predate the present probe: no screen column.) The latch costs the game thread 0.015-0.03 ms a frame.

### vsync on, uncapped (165 Hz; runs il-vsf-*)

| technique | input -> screen p50 | fps |
|---|---|---|
| fresh input only (keyboardFresh + inputLatch) | 25-28 ms | 165 |
| + gpuMaxFrames=1 + frameStartGate | 14-15.5 | 165 |
| NVIDIA driver `__GL_MaxFramesAllowed=1` | 24-27 (no gain: the queue is the game's hand-off slot and the blocking swap) | 165 |
| vsyncAdaptive (swap interval -1) | 24-27 (no gain at a steady 165) | 165 |
| reflexSleep v2 (mean-queue feedback) | 7-11 | 145-148 |
| reflexSleep v3 (p10 slack incl. the blocked swap) | 7-11 | 163 |
| reflexSleep v3.1-v5 | 9-12 (pad phase) / 22-27 (kbm phase) | 164-165 |
| **reflexSleep + reflexCapFps=-1 (auto cap 157)** | **4.6-9.7** (two runs, every input class) | 157 |
| vblankLock (GLX_NV_delay_before_swap) | unavailable: client-only extension, XWayland's GLX server lacks it | |

The vsync pipeline is bistable: "stuffed" (one extra frame queued, the swap blocks a whole refresh) or "drained".
A game producing frames a hair faster than the refresh (5.98 vs 6.06 ms) refills the queue after any drain; a
hair slower (6.16) keeps it drained. A per-frame sleep below one interval cannot move it out of the stuffed state;
Reflex's own answer with vsync is a cap just below the refresh (`reflexCapFps=-1`: refresh - refresh^2/3600, 157
at 165 Hz): with it the queue stays at the 0.5 ms target, no drains, 4.6-9.7 ms input -> screen (runs il-vsf-rcap,
il-vsf-rcapb). On a fixed-refresh screen one frame in ~20 is shown twice, so it is an option, not a default.

### uncapped, vsync off (GPU-bound, runs il-unc-*)

Already 4-9 ms input -> screen at 739 fps; reflexSleep takes ~1 ms off some inputs for 15 % of the frame rate: not
worth it there.

## NVIDIA Reflex

Reflex is an SDK for D3D11 / D3D12 / Vulkan (VK_NV_low_latency2); there is none for OpenGL, which the game uses.
`pzopt.LowLatency` implements its parts with GL: latency markers (the probe), a frames-in-flight limit with GL
fences (`gpuMaxFrames`, the driver's low-latency mode), the just-in-time sleep before the input sample
(`reflexSleep`: a feedback controller on the measured queue, including the driver queue from GL_TIMESTAMP queries),
the auto frame cap below the refresh (`reflexCapFps`), and a vblank-locked frame start (`vblankLock`, needs
GLX_NV_delay_before_swap on a real X11 GLX server).

## Keys (Optimizations tab, section "Input latency")

`keyboardFresh`, `inputLatch` (+ `inputLatchWaitUs`), `reflexSleep` (+ `reflexQueueUs`, `reflexCapFps`),
`gpuMaxFrames`, `frameStartGate`, `vsyncAdaptive`, `vblankLock` (+ `vblankLockMarginUs`), `cursorLatch` (the
game-drawn cursor with "Lock cursor to window", moved to the newest pointer position just before the frame is drawn),
`aimHoldMs` (the right-mouse aim hold, stock 150 ms).

## Cursor late latch (run il-cursor)

With "Lock cursor to window" the game draws its own cursor at the frame's mouse position. `cursorLatch` moved that
sprite on every frame to the newest pointer position: mean shift 14.5 px, 97.9 px during fast sweeps (the distance
the drawn cursor used to trail the pointer). The OS cursor (lock off, the default) has no game lag.

## Defaults

`keyboardFresh` and `inputLatch` are on by default (a gain in every configuration measured, no cost). The rest are
Optimizations-tab options, off: `reflexSleep` + `reflexCapFps` trade a few fps on a fixed-refresh screen for the
lowest latency with vsync, `aimHoldMs` changes how a right-click behaves, `gpuMaxFrames` / `frameStartGate` are
superseded by `reflexSleep` with vsync, `vsyncAdaptive` measured no gain at a steady frame rate, `vblankLock` needs an
X11 GLX server, `cursorLatch` only matters with "Lock cursor to window".

At a 60 fps cap with vsync (il-60-final, all keys on incl. `aimHoldMs=60`): 15-21 ms input -> screen, the same band
as il-60-all; the reflex sleep has nothing to remove there.

## Reflex Boost: GPU clocks held up (`reflexBoost`, runs il-60-noboost / il-60-boost / il-60-boostb)

At a 60 fps cap the RTX 4090 is idle most of each frame and the driver lowers its clocks (SM 810 MHz, memory 5 GHz),
so the frame it does draw takes 3.9 ms from swap to GPU completion against 1.3 ms at 240 fps: input latency. Reflex's
"On + Boost" keeps the clocks up; there is no Reflex for OpenGL, but NVML (libnvidia-ml / nvml.dll, part of the driver)
has `nvmlDeviceSetPowerMizerMode_v1`: "prefer maximum performance", settable by a normal user and scoped to the
process that set it (the driver puts the user's mode back when the game exits or crashes; checked after the runs:
mode 0 again). `pzopt.GpuBoost` (Java FFM, a background thread) holds it while a world is loaded and releases it at the
menu. `nvidia-settings -a GPUPowerMizerMode=1` does the same but is released as soon as nvidia-settings exits.

| 60 fps cap, vsync on, all latency keys | no boost | boost (two runs) |
|---|---|---|
| swap -> GPU done per frame | 3.89 ms | 1.28 / 1.29 ms |
| input -> screen, keyboard | 18.0 | 10.5 / 16.9 |
| mouse | 16.0 | 13.3 / 12.8 |
| pad left stick | 18.0 | 12.1 / 11.8 |
| pad right stick | 16.7 | 11.2 / 11.4 |
| pad LT | 20.4 | 7.2 / 5.7 |
| GPU SM / memory clock | 810 / 5001 MHz | 2520 / 10951 MHz |
| GPU power (sysmon, median over the script) | 56 W | 84 W |

About 2.6 ms off every frame's GPU time and 4-8 ms off most inputs at a 60 cap, for +28 W. Off by default (power);
NVIDIA only; nothing to gain once the game is GPU-bound (the clocks are already up).
