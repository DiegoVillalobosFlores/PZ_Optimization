---
name: showcase-drive
description: Record the stock-vs-optimized driving showcase videos (60 and 120 km/h) with the big MangoHud overlay and stitch them into the quad-view video. Use when asked for a demo video, README figures, or the drive-results chart.
---

# Showcase drive recordings

Follow `bench-run` preflight and announcement first. Recipe is also in the header comment of
`harness/stitch-quad.sh`.

Per recording (four needed: stock/opt × 60/120 km/h):
```bash
# stock: scripts/pzopt.sh uninstall ; optimized: scripts/pzopt.sh install
harness/run.sh --label show60-stock-1 --mode drive --flag path=8010,11204.5/9210,11204.5 --flag kmh=60 --flag max_seconds=120 \
  --flag zoom=max --route-seconds 90 --prop instrument=true --record --mangohud-config config/mangohud-showcase-stock.conf
harness/run.sh --label show120-opt-1 --mode drive --flag path=8010,11204.5/9210,11204.5 --flag kmh=120 \
  --route-seconds 60 --flag zoom=max --prop instrument=true --record \
  --mangohud-config config/mangohud-showcase-opt.conf
```
- Use the DEFAULT bench save. The path pilot drives KY-60 east from 8010 (since 2026-09-24; the old `route=E:1200
  kmh=193` follower left the road about every other run) and stops at the end; `harness/drive_check.py <run>` says
  whether the drive was clean. The 2026-09-19..23 showcase videos were recorded with the old follower.
- The recording is a monitor capture: keep the game focused and uncovered the whole run.
  Shift_R+F9 (reset fps metrics) is sent by xdotool at route start on X11 only.
- Graph variant: `config/mangohud-showcase-graph.conf` (dynamic_frame_timing).
- Leave PZDashboard on only if the video is meant to show the mod; for numbers use `--no-dashboard`.

Afterwards:
```bash
harness/stitch-quad.sh <stock60> <opt60> <stock120> <opt120>   # 3840x1920 quad video
python3 harness/readme-chart.py                                 # docs/media/drive-results.svg
```
Update the run names inside `readme-chart.py` and paste the new figures into README.md.
