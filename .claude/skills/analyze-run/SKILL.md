---
name: analyze-run
description: Read and compare finished harness runs - frame-time tails, utilization, chunk latency, JFR waits, load phases - and look them up in Grafana. Use when asked what a run shows, whether a change helped, or why a frame is slow.
---

# Analyze runs

Always report frame-tail consistency (mean, p99, p99.9, max, spikes > 33 ms, jitter) AND
utilization (cpu_pct, gpu_pct from sysmon) over the route window. Idle hardware below 240 fps
is a finding.

```bash
python3 harness/analyze.py harness/runs/<run>/                       # one run
python3 harness/compare.py harness/runs/<run>/                       # vs the newest *stock-1/*stock-2 pair (baselines archived 2026-09-24)
harness/grafana/stack.sh status                                      # Grafana http://127.0.0.1:3000: dashboards PZ runs / run / compare / live
python3 harness/waits.py harness/runs/<run>/                          # needs --jfr + wait thresholds
python3 harness/attribute.py harness/runs/<run>/                      # JFR samples per slow frame
python3 harness/sections.py harness/runs/<run>/                       # GameProfiler sections (--game-profiler)
python3 harness/loadtime.py harness/runs/<a>/ harness/runs/<b>/       # load phases side by side
harness/loadsheet.sh harness/runs/<run>/                              # contact sheet of the load
```
Validity checklist before any conclusion: `route complete` in console.txt; mangohud + sysmon
+ threads lines in analyze output; `zoom=2.5` in pzopt-bench.out for bench; same "Desktop
resolution" and "OpenGL version" as the comparison run; same launcher= and dashboard state
in run.opts. Noise floor = spread between the two stock baseline runs; real = > 2× that.

Known signatures:
- one ~20-28 ms frame every 2.000 s phase-locked to start = PZDashboard collectors.
- GL thread ("main" on native) at ~90 % and 160 fps cap = Steam performance monitor.
- 233 fps / p99 8 ms on a bench run = zoom drifted to 1.0.
- Zink uncapped: ~1.8 ms/frame blocked in glfwSwapBuffers, GPU 60 %. NVIDIA GL uncapped: GPU 98 %.
Large outputs: process with ctx_execute rather than reading whole CSVs.
