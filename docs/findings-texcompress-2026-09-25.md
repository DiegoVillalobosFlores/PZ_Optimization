# Texture compression without the driver (2026-09-25)

Maintainer: "Why are the fps lower on the menu on the flip after boot?", then "make the texture compression cost virtually
zero". Flip = AYANEO Flip, Ryzen AI 9 HX 370, Radeon 890M, Mesa 26.2.3 radeonsi, `balanced` profile, 1920x1080.

## Cause

`textureCompression=true` (the low-end preset and stock's Steam Deck defaults turn it on) makes stock create every
texture as `GL_COMPRESSED_RGBA`. Mesa (and NVIDIA) pick DXT5 and compress on the CPU inside `glTexImage2D`, on the render
thread: 41 ns a pixel on the flip, 35x a plain upload (`tools/TexCompProbe.java`, 12 UI2 pages: 603 ms vs 17 ms). The main
menu after boot: 25-34 fps for 10-16 s with the render thread 99 % in `glTexImage2D` and the GPU idle (15 fps for 30 s in
`power-saver`); `textureCompression=false`: 60 fps (the menu cap) from 4 s after the first frame.

A second finding on the way: the 2026-09-25 A/B first ran a stale `build/classes` without `zombie/GameWindow.class`, so
`limiterSleep` was missing and the capped menu spun a core in `Thread.yield()`. With a complete build the game thread on
the idle 60 fps menu is ~12 % of a core: half of it the Bink background video's frame conversion
(`VideoTexture.getCurrentFrameData`, native, on the game thread), a quarter menu Lua.

## What `texCompress=auto` does (default)

| step | where | cost |
|---|---|---|
| PNG decode (stock) | file-pool worker | unchanged |
| raw level 0 copied into a persistently mapped staging buffer (128 MB, `glBufferStorage` persistent + coherent; a worker waits up to 50 ms for room, ranges return every frame by fence), pixels freed at once (`texCompressEarlyFree`) | worker | a memcpy |
| ImageData's mip chain built on the GPU (bit-exact: `TexCompProbe -Dprobe.mipcheck`, NVIDIA and Mesa, 0 of 134 / 268 MB differ), a pack page skips its stock `initMipMaps` | GPU compute | - |
| BC3 encode, premultiplied where stock premultiplies, against the GPU's measured decode palette | GPU compute (`pzopt.TexBcGpu`) | - |
| blocks into the texture through a pixel-unpack buffer | render thread: a few GL calls | ~0.13 ns a pixel |

Without GL 4.3 compute (macOS GL 4.1) the worker encodes on the CPU (`pzopt.TexBc`, `texCompress=worker`); textures made
outside the asset pipeline go through the GPU encoder by copy; `texCompress=driver` is stock.

## Numbers (flip, one run each unless noted)

Render-thread cost of all compressed textures of a boot: driver ~26 s (the whole set at 41 ns/px) -> GPU encoder with
copies 213 ms -> no-orphan block buffer 72 ms (768 textures, 542 MPixel) -> final build 99.5 ms (941 textures, 776
MPixel, 837 of 841 worker-built ones staged; with 64 MB of staging 111 found it full and the decoders waited 23.9 s
instead of 12.3 s).

Menu after boot (idle window from menu-ready, `textureCompression=true`):

| | fps | p99 | frames > 33 ms |
|---|---|---|---|
| driver | 47.0 | 48.2 ms | 306 |
| texCompress (every round, auto and worker) | 60.0 | 16.8-17.1 ms | 0 |

Game-process CPU over the first 30 s of boot (sysmon): driver 158.8 core-s, GPU encoder 147.8, + no orphan 130.8, + GPU
mips 124.9 (same build without: 131.9). Per-thread (schedmon, whole run): the file pool 27.9 CPU-s without the disk cache,
17.7 with it (JIT threads vary +-5 s between runs).

Quality (sampled: texelFetch of what the GPU decodes, game's own premultiplied pages, 12 UI2 pages):

| | flip (Mesa DXT5) RGB / alpha | desktop (NVIDIA DXT5) RGB / alpha |
|---|---|---|
| driver | 30.37 / 55.71 dB | 29.82 / 53.85 dB |
| ours, HQ | 31.43 / 56.37 dB | 31.42 / 56.40 dB |

In-world screenshots at zoom 1 (level-0 detail, high-pass PSNR against a no-compression run): driver 37.26 dB (0.25 %
of pixels off by > 32), ours 39.20 dB (0.14 %). At max zoom (high-pass PSNR against a no-compression run; a second no-compression run = noise floor
37.92 dB / 1.94 % of pixels off by > 8): driver 36.71 dB / 3.87 %, ours 37.30 dB / 3.12 % (the final build's run 36.28 dB / 3.39 %: single max-zoom runs swing with the weather); CPU vs GPU mips differ only
on wind-swayed foliage (39.38 dB between them).

## Techniques tried

- **Encoders**: van Waveren's inset bounding box (FAST, 3.7 ns/px on the desktop) and stb_dxt's principal axis + least
  squares with optimal single-colour tables (HQ, 21 ns/px); adaptive HQ (the fast fit first, HQ only above 16 squared
  error a pixel: 32 -> 22 ns/px, -0.03 dB). Projection index selection = exact nearest (the palette is a line).
- **Alpha**: least squares, 6-level mode, a hill-climb and an exhaustive +-8 window (no gain), then endpoint pairs from
  the block's own values (+2 dB; it covers the driver's trick of sending near-0/255 texels to the 6-level mode's exact 0
  and 255), scored on the block's histogram of distinct values with an early exit (CPU 59 -> 27 ns/px, same quality;
  8-level pairs add 0.02 dB for +4 ns/px and are off). Blocks the cheap fits leave within 64 squared error skip it.
- **CPU encoder cost** (`worker`, the macOS path) on the flip after the quality work: 60 ns/px (46.5 s of file-pool time
  for 776 MPixel, before the histogram search 161 ns/px); off the render thread, the menu stays at 60 fps.
- **Decoder-exact error model** (`pzopt.TexBcPalette`, `texCompressMeasureDecoder`): GPUs decode the interpolated BC3
  entries with their own weights (6-level alpha 0..255: formula 51 102 153 204, 890M 52 104 151 203, RTX 4090 48 96 159
  207); a 1024x1152 BC3 test texture read back once (37-51 ms at the first compressed texture) gives the encoders the
  real palette: +0.05 dB alpha on AMD, +0.4 on NVIDIA.
- **GPU encoder**: GL 4.3 compute, one invocation a block, same fits; blocks reach the texture via
  `GL_PIXEL_UNPACK_BUFFER` (no readback). Orphaning the block buffer per texture cost ~2 ms a texture on Mesa; not
  orphaning is race-free (back-to-back uploads bit-identical to finished ones, `-Dprobe.nofinish`, both GPUs).
- **Zero-copy staging** + **early free** + **GPU mips**: above.
- **Disk cache** (`texCompressCache`, off: disk-space decision): deflated BC3 per pack page, keyed by the resolved pack
  file; 450 pages = 228 MB on the flip; a warm boot served all 533 pack pages from it (1.35 s of inflate) and saved ~10
  CPU-s of file-pool time; the first boot spends ~27 CPU-s encoding on the CPU to fill it.

## Rigs

`tools/TexCompProbe.java` (+ `harness/texprobe/wrap.sh` to run it inside a queued `run` on a laptop: `--wrap`),
modes `probe.game`, `probe.lut`, `probe.nofinish`, `probe.mipcheck`, `probe.alphadiff`, `probe.palette=a0,a1`;
`harness/texdiff.py` (screenshot PSNR, `--highpass`); `harness/pad/menu-idle.txt` (idle main menu); `devTexCompTiming`
(console line, a final one when uploads go quiet).
