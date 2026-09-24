## Why

`parallel-chunk-grid-recalc` cut chunk latency 18× but left frame time
untouched: on the fixed bench route the game thread runs 6.7 ms mean, ~18 ms
at p99, 25–30 ms at p99.9, with 40–200 ms spikes (docs/archive/2026-09-24/results.md), and none
of it moved with any streamer setting. The stutter the player feels is that
tail, and we do not yet know what is in it — the harness measures how long
frames take, not what they spend it on. Guessing at a fix would be the
mistake the first change nearly made.

## What Changes

- Attribute the game thread's slow frames: a JFR recording of a bench run
  (GraalVM 25 supports it; `vmArgs` is editable) aligned with the per-frame
  durations in `pzopt-frames.out`, so the stacks sampled inside >20 ms frames
  are separated from the ones in ordinary frames. Complemented by the game's
  own `GameProfiler` sections and the `gc.log` already being written.
- Two cheap A/B runs on the existing harness that need no code: PZDashboard
  (a per-tick Lua mod) disabled, and G1 in place of ZGC (the `.stock`
  launcher JSON uses G1).
- A ranked attribution report with the same noise discipline as before: which
  contributors account for the p99/p99.9 tail and the spikes, with figures
  from the harness.
- If — and only if — chunk publication on the game thread
  (`IsoChunkMap.updateInternal` draining `IsoChunk.loadGridSquare` into
  `doLoadGridsquare()`, currently up to `1 + 3·count/chunkGridWidth` chunks per
  frame with no time bound) is among the top contributors: replace the
  count-based drain with a time-budgeted one, in a third overridden class
  (`zombie.iso.IsoChunkMap`), kill-switchable, and gated by the parity harness.
  Other contributors (lighting, GC, Lua) are reported, not fixed, in this
  change.

Not in scope: rendering (`states.render`), anything in the Lua mods
themselves, JVM flag tuning beyond the one G1/ZGC comparison.

## Capabilities

### New Capabilities

- `frame-time-attribution`: Producing a ranked, noise-qualified breakdown of
  where the game thread's slow frames go on the fixed bench route, from a JFR
  recording correlated with per-frame timings plus A/B runs.
- `chunk-publication-budget`: Bounding the time the game thread spends per
  frame handing published chunks into the world, so a burst of arriving
  chunks is spread over frames instead of landing in one — applied only when
  attribution shows publication in the tail.

### Modified Capabilities

None. `perf-harness` (from `parallel-chunk-grid-recalc`, not yet archived) is
reused as-is; its requirements do not change.

## Impact

- **Game classes overridden**: possibly `zombie.iso.IsoChunkMap` in addition to
  `IsoChunk` and `WorldStreamer` — one more class to re-verify per game update.
- **Launcher config**: a JFR run needs `-XX:StartFlightRecording=...` in
  `ProjectZomboid64.json` `vmArgs` for that run only; the harness must add and
  remove it (the user already maintains that file; `.stock`/`.prev` copies
  exist).
- **Harness**: `harness/run.sh` grows a `--jfr` option and a JSON/vmArgs
  swap; a new `harness/attribute.py` joins the JFR samples with
  `pzopt-frames.out`; `harness/compare.py` gains the A/B runs.
- **Risk**: JFR sampling overhead on the game thread is low (default 20 ms
  interval) but must be checked against the noise floor like the
  instrumentation was; a time-budgeted publication delays chunk readiness by
  a frame or two, which the chunk-latency metric will show.
