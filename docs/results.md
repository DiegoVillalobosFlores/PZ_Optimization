# Results

Fresh start on 2026-09-24. Every earlier result (the old `results.md`, the findings, the baselines and the last
`benchmark-progress.html` snapshot) is in `docs/archive/2026-09-24/` and `harness/archive/2026-09-24/`; the run
directories behind them are in `harness/archive/2026-09-24/runs/` (local only).

The metrics of every run from here on are in Grafana (`harness/grafana/stack.sh up`, http://127.0.0.1:3000):
per-frame times, presented frames and GPU time, utilization, game-thread phases, chunk latency, GC, flips and
Jev's verdict. This file keeps the prose: what was run, why, and what it showed. Each entry names its runs, so
the dashboards "PZ run" / "PZ compare" show the numbers behind it, and states the frame tail (p99 / p99.9 /
spikes / jitter) and the utilization over the route window, as the objective requires.
