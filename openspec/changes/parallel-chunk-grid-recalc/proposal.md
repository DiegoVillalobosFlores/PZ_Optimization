## Why

When a chunk streams in, Project Zomboid recalculates every grid square in it —
`IsoChunk.loadInWorldStreamerThread()` walks 8×8 squares per level across
`minLevel..maxLevel` and runs `RecalcProperties()` then
`RecalcAllWithNeighbours(true, chunkGetter)` on each one, and each of those
touches the square's full 3×3×3 neighbourhood. That work is already off the
render thread, but it can only ever use one core: `WorldStreamer` starts exactly
one worker thread, and `IsoChunk.chunkGetter` is a mutable `private static final`
singleton that is bound to the chunk being processed for the duration of the
recalc pass, so two chunks can never be in that pass at once.

The result is that chunk-load throughput is fixed at one core's worth no matter
how many are idle. It shows up as the stutter and pop-in when the player moves
faster than chunks can be recalculated — driving is the worst case, since it
crosses chunk boundaries continuously.

Measurement (docs/archive/2026-09-24/baseline-findings.md) then showed a second, larger cause of
the same symptom: `WorldStreamer.threadLoop()` polls its job queue with fixed
`Thread.sleep(140L)` calls — two of them back to back when idle, and one after
the last chunk of a burst — so a chunk waits a median ~155 ms before the
streamer even looks at it, against ~1.5 ms of actual work. The recalc pass is
~10 % of a chunk's latency; the polling cadence is ~90 %.

## What Changes

- Replace `IsoChunk`'s static `chunkGetter` singleton with per-task getter
  instances so the recalc pass carries no shared mutable state.
- Wake `WorldStreamer`'s thread when a job is enqueued instead of letting it
  sleep on a fixed cadence, so the streamer starts on a burst of chunks within
  a millisecond rather than up to 280 ms later.
- Hand the recalculation pass (everything after `RecalcProperties`) to a
  bounded worker pool sized from available cores, so independent chunks
  recalculate concurrently. `WorldStreamer`'s thread keeps the queue, the disk
  read and the first (`RecalcProperties`) loop.
- Publish recalculated chunks to the game thread in the order they were
  submitted, so the game thread sees exactly the stock sequence. No adjacency
  scheduling is needed: the streamer pass resolves every neighbour through
  `IsoChunk.ChunkGetter`, which returns null outside its own chunk, so two
  concurrent passes cannot touch each other's squares.
- Keep the `PolygonalMap2` / `PathfindNative` `addChunkToWorld` hand-off on the
  game thread, where the stock code already gates it.
- Ship the result as loose `.class` files under the install directory, which the
  stock `ProjectZomboid64.json` classpath (`[".", "projectzomboid.jar"]`) loads
  in preference to the jar. The shipped jar is never modified and uninstalling
  is deleting files.
- Add a harness that measures chunk-load throughput and frame time before and
  after, and a parity mode that proves the parallel result is identical to the
  single-threaded one.

Not in scope: the systems the game already threads — `LightingThread`,
`WorldReuserThread`, `ChunkSaveWorker`, and the IsoRegion `JobChunkUpdate`
pipeline. Also not in scope: anything touching save format or network payloads,
which keeps this client-side and multiplayer-neutral.

## Capabilities

### New Capabilities

- `chunk-grid-recalc`: Concurrent recalculation of chunk grid squares — the
  worker pool, in-order publication, the main-thread hand-offs that must stay
  serialized, and the requirement that the resulting world state is
  bit-identical to the stock single-threaded pass.
- `streamer-wakeup`: The streamer thread reacts to enqueued work immediately
  instead of polling on a fixed sleep, with the stock cadence available as a
  fallback.
- `install-overlay`: Installing and removing compiled class overrides against a
  detected game install, including refusing to install against a build the
  overrides were not compiled from.
- `perf-harness`: Reproducible measurement of chunk-load throughput and frame
  time, and the parity check that compares parallel output against stock output.

### Modified Capabilities

None — this is the project's first change.

## Impact

- **Game classes overridden** (recompiled from decompiled Build 42 sources):
  `zombie.iso.IsoChunk`, `zombie.iso.WorldStreamer`. Any class that reads
  `IsoChunk.chunkGetter` must be recompiled with it.
- **Game install**: new `.class` files under
  `/games/steamapps/common/ProjectZomboid`, shadowing jar entries.
  `projectzomboid.jar` is not written to.
- **Hard constraint**: the parallel path must not reach
  `PolygonalMap2.instance.addChunkToWorld` / `PathfindNative.instance.addChunkToWorld`;
  the stock code gates those behind `GameWindow.gameThread` or
  `GameServer.mainThread`.
- **Risk**: `IsoGridSquare` and `IsoChunk` state is written during recalc with no
  locking today, on the assumption of a single writer. Every field the pass
  touches has to be shown to be chunk-local or moved behind the scheduler before
  the pool is widened.
- **Coupling to game version**: overrides are compiled against one Build 42
  revision and must be rebuilt when the game updates.
