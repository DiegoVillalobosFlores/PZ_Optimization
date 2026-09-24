# Results

Fresh start on 2026-09-24. Every earlier result (the old `results.md`, the findings, the baselines and the last
`benchmark-progress.html` snapshot) is in `docs/archive/2026-09-24/` and `harness/archive/2026-09-24/`; the run
directories behind them are in `harness/archive/2026-09-24/runs/` (local only).

The metrics of every run from here on are in Grafana (`harness/grafana/stack.sh up`, http://127.0.0.1:3000):
per-frame times, presented frames and GPU time, utilization, game-thread phases, chunk latency, GC, flips and
Jev's verdict. This file keeps the prose: what was run, why, and what it showed. Each entry names its runs, so
the dashboards "PZ run" / "PZ compare" show the numbers behind it, and states the frame tail (p99 / p99.9 /
spikes / jitter) and the utilization over the route window, as the objective requires.

## 2026-09-24 evening: render distance upstairs (`chunkGridFollowView`)

The maintainer's report: with a render distance above vanilla the screen corners are filled at the widest zoom on the
ground, but "on a higher z-level it reverts to the vanilla distance". Cause: the camera centres on the player with the
height in it, so the ground under the screen centre is 3 tiles north and 3 west of the player per level, while the
grid stays centred on the player (details: `override-edits.md`, IsoChunkMap, edit of 2026-09-24). Rig: bench mode,
`--flag start=12450,1280 --flag upstairs=4 --flag upstairs_roof=true --flag zoom=max --flag weather=clear --flag
zombies=off --shot-at 8 --prop chunkGridWidth=auto`, desktop 5120x2160, NVIDIA GL, zoom 2.5, direct launch; the
`harness: grid coverage:` console line (new) gives, for each screen corner, how many tiles its level-0 ground lies
outside the grid.

| Run | chunkGridFollowView | grid centre chunk | TL / TR / BL / BR outside (tiles) | black px in the TL wedge |
|---|---|---|---|---|
| `gridz-cov4-follow-false-20260924-171020` | false | 1556,170 | 7.1 / 7.1 / 0 / 0 | 9 % |
| `gridz-cov4-follow-true-20260924-171112` | true | 1554,168 | 0 / 0 / 0 / 0 | 1 % |

Player on a flat roof at 12450,1361, level 4, grid 25 (stock 19). The wedge is mostly covered by the sidebar and a
roof drawn up from inside the grid, so the picture shows little at level 4; the numbers grow by 3 tiles per level.
The top-right corner is dark in both shots from the view cone, not the grid. No frame-time reading: screenshot runs
(the save's leftover 60 fps cap). Discarded: `gridz-lv6-*` (no level-6 room near the start, player stayed indoors on
the ground floor), `gridz-roof4-*` (no coverage line yet).
Release check on origin/master 2456dbb + the change (`gridz-rel-cov4-20260924-172608`, follow view on by default):
centre chunk 1554,168, corners 0 / 0 / 0 / 0, no exceptions.
