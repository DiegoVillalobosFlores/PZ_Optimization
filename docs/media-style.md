# Media style

The look of everything published under `docs/media/` (videos, posters, GIFs, README images) and of
the in-game results overlays. It is the showcase video's results card (`harness/stitch-showcase.py`
`seg_results`, 2026-09-20) written down, so new media match without re-deriving it. Rules in the
present tense are the rules; numbers are the ones in use.

## Surface

- Dark only. The background is near-black, never a mid grey and never white: `0x060608` in the HDR
  compositions (a PQ code value), `#0b0b0e` in SDR images. Panels that carry text sit on a slightly
  lighter, blue-tinted black (`#111114`) with a hairline ring (`#505058`, ~1 px, 35 % over video).
  In the videos the panel is frosted glass: a crop of the scene behind it, `gblur=sigma=40`, darkened
  62 %, then the ring. Corners are rounded ~10 px in still images (the ASS panels are square).
- Dividers between panes are 4 px of `0x202026` (HDR) / `#26262c` (SDR); table rules are 1 px of the
  same, the header rule one step lighter (`#505058`). No boxes around marks, no dashed lines.

## Colour

Four roles, and every colour is one of them. Text is white or grey; a hue means one thing.

| Role | HDR (PQ code, `0xRRGGBB`; ASS `&H00BBGGRR`) | SDR / README (`#rrggbb`) | Used for |
|---|---|---|---|
| white | `B4B4B8` | `#f0f0f4` | titles, names, primary numbers |
| grey | `8A8A90` | `#b4b4b8` secondary, `#8a8a90` muted | explanations, units, footers, column heads |
| stock (amber) | `B88A40` | `#c9592b` | the shipped game's numbers, and anything *worse* (a regression, a warning) |
| optimized (green) | `5CB878` | `#44de7c` | this build's numbers, and anything *better* |
| de-emphasis | `3A3A40` | `#3a3a40` | marks of rows that are neither (other mods, context series) |

- The HDR values are what `drawtext` / ASS take in the PQ compositions: ~60 % code value is a
  comfortable white on an HDR display; brighter reads as glare. The SDR column is what those values
  become after the poster tone-map (`zscale ... npl=200, tonemap=hable`), sampled from the published
  poster, so a README image and a poster frame of the same card match side by side.
- Stock left, optimized right, always; the same two colours label the panes, the big fps numbers,
  the results columns and the change column (green when the change is an improvement, amber when
  not, whichever direction "better" is for that metric).
- A third series (a second optimized variant, the game-thread pass) takes the blue `5C9AC8` /
  `#3b8ee0`; nothing else gets a new hue. Other mods, other machines and anything that is "stock
  within noise" are drawn in the de-emphasis grey with their numbers in white, so the one row that
  matters is the only coloured one.
- Never a rainbow, never a hue per row, never colour as the only carrier of meaning: the label
  says stock / optimized, the colour repeats it.

## Type

- `Noto Sans` for everything, `Noto Sans Mono` for numbers that count or align (fps ticking up, the
  boot / load counters, table columns), never for prose. No serif, no display face.
- Weights: semibold for titles, series names and the row that matters; regular elsewhere. Column
  heads are small caps-style uppercase in muted grey.
- Sizes in the 3840x1800 videos: title 64, big numbers 260 (the per-pane fps), results rows 42-46,
  labels 42, explanations 34, footer 30. In a 2000-2100 px README image: title 22, rows 15, notes
  13.5, muted 11.5-12.5. Keep the same ratios at other widths.
- Numbers carry their unit in the label, not in the number: `156 fps`, `16.5 ms`, `7.3 s`; percent
  changes signed (`+228 %`, `-65 %`); fps as an integer, ms and s with one decimal.

## Layout

- Videos are 3840x1800 (2.13:1) or 3840x1620 (quad, 2:1), 60 fps. Two panes side by side, the
  divider in the middle, a header band above the panes for the title and the pane labels, numbers
  in the lower part of each pane where the overlay is not.
- Results are a table, centred: metric name left, stock, optimized, change. Metric and route on one
  line (`120 km/h highway drive · fps`), middle dots as separators, one footer line explaining what
  the numbers are (`fps: presented frames per second over the whole route · p99: the frame time 99 %
  of frames stay under`).
- Still images: one panel, title and one-line subtitle top left (what, where, when, one run per cell),
  the table, a two-line footer (how to read the bars, where the numbers come from). Inline bars share
  one scale across every column so bars are comparable across routes; thin (18 px), rounded 4 px,
  the value beside them in ink, never inside them.
- Every number in a video also exists in `docs/archive/2026-09-24/results.md` with its run label; the footer of a still
  image points there.

## Encoding

- Every video is AV1 10-bit HDR (PQ / BT.2020; NVENC `av1_nvenc`, `setparams` + `write_colr` tags),
  like the gpu-screen-recorder captures; HDR sources stay 10-bit end to end, SDR sources are mapped
  to PQ (reference white 203 nits). Never tone-map a published video to SDR H.264.
- Posters (`.jpg`), README GIFs and PNG images are the only SDR derivatives: tone-mapped from the HDR
  file with `zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200, tonemap=hable,
  zscale=p=bt709:t=bt709:m=bt709` (`harness/showcase-thumbnail.py`, `encode-av1-hdr.sh`), or drawn
  directly with the SDR column above. GIFs stay under GitHub's 10 MB.
- Audio: the game's own sounds only (menu theme, the rain loop), two-pass linear loudnorm to -16 LUFS.

## Scripts that implement it

| Script | Output |
|---|---|
| `harness/stitch-showcase.py` | the showcase video and its results card (the reference for this file) |
| `harness/stitch-triple-hdr.sh`, `stitch-louisville-sbs.sh`, `stitch-storm-sbs.sh`, `stitch-sbs.sh` | the side-by-side videos |
| `harness/showcase-thumbnail.py`, `stitch-sbs-gif.sh`, `encode-av1-hdr.sh` | posters, GIFs, the `-1080` copies |
| `harness/mods-table.py` | `docs/media/workshop-mods-comparison.png` (a still image in this style) |
| `harness/readme-chart.py` | `docs/media/drive-results.svg` (light, predates this file; redo in this style when it is next regenerated) |
