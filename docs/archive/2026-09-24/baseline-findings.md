# Baseline findings (sections 3–5), 2026-09-14

Machine: Ryzen 7 9800X3D (8c/16t), RTX 4090, NVMe. Game 42.20.4 `b0bbce05d5`
under Proton, GraalVM 25 JRE. Bench save: copy of Sandbox/2026-08-16_01-09-42,
route `E:400,S:500,W:400,N:500` at 18 tiles/s from 8002,11204 (all inside the
already-visited region, so no world generation). Runs in `harness/runs/`,
summaries in `harness/baseline/`.

## Stock numbers (two runs, `base1`/`base2`)

| | base1 | base2 |
|---|---|---|
| chunks loaded on route | 4636 (46.4/s) | 4521 (45.2/s) |
| disk load per chunk, mean / p99 | 0.5 / 2.9 ms | 0.5 / 2.4 ms |
| recalc per chunk, mean / p99 | 1.3 / 5.1 ms | 1.3 / 5.5 ms |
| streamer thread busy | 8.5 s of 100 s | 8.4 s of 100 s |
| enqueue → load-start wait, p50 / p90 / p99 | 156 / 278 / 622 ms | 153 / 271 / 352 ms |
| frame time mean / p99 / p99.9 (in-game sampler) | 6.7 / 17.6 / 25.8 ms | 7.0 / 18.1 / 24.7 ms |
| frame time mean / p99 / p99.9 (MangoHud) | 6.7 / 18.1 / 27.2 ms | 7.0 / 18.7 / 31.9 ms |

Instrumentation off (`noinstr1`, MangoHud only): mean 6.7, p99 18.5, p99.9
31.5 ms — inside run-to-run noise, so the sampler costs nothing measurable.

## What the timeline shows

Chunks arrive in bursts of ~19 (one row of the 19-wide chunk grid) every
~440 ms as the player crosses a chunk boundary. A burst takes ~30 ms of
streamer CPU (1.5 ms/chunk, 71 % of it recalc). The wait before a burst is
processed is 40–260 ms, and it comes from `WorldStreamer.threadLoop()`:

- when `jobList` is empty it sleeps 140 ms, then falls through to a second
  140 ms sleep at the end of the loop → a 280 ms poll period while idle;
- after the last queued chunk (`busy == false`) it also falls through to the
  end-of-loop 140 ms sleep.

So the recalc CPU is ~10 % of the latency a chunk experiences; the poll cadence
is ~90 %. Recalc *does* dominate disk read (open question 1: yes, 71 % vs
29 %), but neither dominates chunk-load latency.

## Model (harness/simulate.py, replaying base1's arrivals and service times)

| variant | enqueue→publish p50 | p90 | p99 |
|---|---|---|---|
| stock (measured) | 164 ms | 318 ms | 1042 ms |
| stock (model) | 175 ms | 291 ms | 693 ms |
| pool W=2 | 147 ms | 261 ms | 289 ms |
| pool W=4 | 137 ms | 253 ms | 280 ms |
| wake-on-enqueue, W=1 | 18 ms | 107 ms | 693 ms |
| wake-on-enqueue + pool W=4 | 6 ms | 27 ms | 153 ms |

The pool alone buys ~15–20 % at the median because the sleeps dominate. Waking
the streamer when a job is enqueued (replacing the two fixed sleeps with a
wait on the queue) is worth ~9× at the median on its own, and the pool on top
of that is worth another ~3×.

## Audit result (docs/recalc-static-audit.md)

The streamer recalc pass is chunk-local (the `ChunkGetter` clips to its
chunk), so concurrent passes on different chunks cannot interact and the
adjacency rule in design Decision 4 is not needed for parity. The only shared
mutable state reachable from the pool is avoided by keeping loop 1
(`RecalcProperties`, square creation) on the streamer thread.

## Parity harness

Capture at publish time of every recalc-written square field; two stock runs
produced identical captures over 125,962 squares in 1,577 chunks; an injected
corruption is reported by coordinate and field. Baseline in
`harness/baseline/parity-stock.out`; gate in `harness/parity-gate.sh` /
`scripts/accept.sh`.
