-- Metrics DB for the harness runs (harness/grafana/stack.sh applies it on every `up`; idempotent).
SET client_min_messages = warning;
-- Every per-sample table carries
--   t     the wall-clock instant (timestamptz),
--   rel_s seconds since the run's route start (negative = settle / load before it),
--   rt    "route time": 2000-01-01 00:00:00 UTC + rel_s, so the Run and Compare dashboards put every
--         run on one x axis (00:00:00 = route start, 23:59:xx the day before = the settle).

CREATE TABLE IF NOT EXISTS runs (
  run text PRIMARY KEY,
  label text, machine text, started timestamptz, mode text, preset text, route text, zoom text,
  resolution text, opengl text, gpu text, jvm text, platform text, variant text, props text, flags text,
  enabled boolean, launcher text,
  route_start timestamptz, route_end timestamptz, route_seconds double precision,
  valid boolean, verdict text, verdict_confidence double precision, headroom_finding double precision,
  fps_mean double precision, frame_mean_ms double precision, p50_ms double precision, p90_ms double precision,
  p99_ms double precision, p99_9_ms double precision, max_ms double precision,
  over_33ms integer, over_50ms integer, over_100ms integer,
  presented_p99_ms double precision, presented_p99_9_ms double precision,
  jitter_ms double precision, stdev_ms double precision, fps_1pct_low double precision, under_cap_pct double precision,
  gpu_ms_mean double precision, game_load_pct double precision, render_load_pct double precision,
  cpu_pct double precision, busiest_core_pct double precision, gpu_pct double precision, gpu_p90_pct double precision,
  gpu_w double precision, gpu_c double precision, vram_mib double precision, process_cores double precision,
  game_thread_pct double precision, render_thread_pct double precision,
  chunk_p50_ms double precision, chunk_p90_ms double precision, chunk_p99_ms double precision,
  chunks_per_s double precision, gc_events integer, gc_wall_ms double precision, gc_max_ms double precision,
  zombies_loaded integer,
  scenario jsonb, opts jsonb, summary jsonb, judge jsonb,
  path text, files_mtime double precision, ingested timestamptz DEFAULT now()
);
CREATE INDEX IF NOT EXISTS runs_started ON runs (started);

-- pzopt-frames.out: every game frame (Stats), the harness's own frame source on both stock and optimized
CREATE TABLE IF NOT EXISTS frames (run text, t timestamptz, rel_s double precision, rt timestamptz, ms real, in_route boolean);
-- pzopt-overlay.out: every presented frame with the overlay's GPU time and thread loads
CREATE TABLE IF NOT EXISTS overlay (run text, t timestamptz, rel_s double precision, rt timestamptz, fps real, ms real,
  gpu_ms real, cpu_load real, gpu_load real, game_load real, render_load real);
-- sysmon.csv: machine CPU / GPU utilization, clocks, power, VRAM every 0.5 s
CREATE TABLE IF NOT EXISTS sysmon (run text, t timestamptz, rel_s double precision, rt timestamptz, cpu_pct real,
  busiest_core_pct real, gpu_pct real, gpu_sm_mhz real, gpu_mem_mhz real, gpu_w real, gpu_c real, vram_mib real,
  game_cpu_pct real, bat_w real);
-- pzopt-gamethread.out: per second, share of the game thread's stack samples per key
-- kind p = phase, s = phase/sub-phase, l = phase/sub/hot method, w = waits
CREATE TABLE IF NOT EXISTS gamethread (run text, t timestamptz, rel_s double precision, rt timestamptz, kind text,
  name text, samples integer, share real);
-- pzopt-chunks.out: one row per streamed chunk, at its enqueue instant
CREATE TABLE IF NOT EXISTS chunks (run text, t timestamptz, rel_s double precision, rt timestamptz, wx integer,
  wy integer, min_level integer, max_level integer, job text, thread text, queue_wait_ms real, load_ms real,
  recalc_wait_ms real, recalc_ms real, publish_wait_ms real, total_ms real);
-- gc.log: every GC pause
CREATE TABLE IF NOT EXISTS gc (run text, t timestamptz, rel_s double precision, rt timestamptz, gc_id integer,
  kind text, pause_ms real, heap_before_mb real, heap_after_mb real);
-- present.txt: interval between the game window's flips on screen (X Present)
CREATE TABLE IF NOT EXISTS present (run text, t timestamptz, rel_s double precision, rt timestamptz, interval_ms real);
-- anything else with a time column (pzopt-drive.out, vrr.txt ...): long format
CREATE TABLE IF NOT EXISTS series (run text, t timestamptz, rel_s double precision, rt timestamptz, source text,
  key text, value double precision);
-- profiler stacks, root first, deduplicated across runs: id = first 8 bytes of md5(frames joined by ';')
CREATE TABLE IF NOT EXISTS stack_defs (id bigint PRIMARY KEY, frames text[]);
-- per second, per sampled thread: source game = the in-game profiler (pzopt-stacks.out, game thread), lua = the Lua
-- profile (pzopt-lua.out), jfr = --jfr (pzopt.jfr), asprof = --asprof (asprof.jfr, every thread, native frames)
CREATE TABLE IF NOT EXISTS stacks (run text, t timestamptz, rel_s double precision, rt timestamptz, source text,
  thread text, stack_id bigint, samples integer);
-- pzopt-pacing.out: one row per frame (ms): game-frame interval, frame done -> render thread took it, the swap call,
-- presentPacing's hold, GPU done after the swap, on glass after the swap (Mac bridge); NULL where the stage has no stamp
CREATE TABLE IF NOT EXISTS pacing (run text, t timestamptz, rel_s double precision, rt timestamptz, sim_step_ms real,
  acquire_lag_ms real, swap_ms real, hold_ms real, gpu_after_swap_ms real, shown_after_swap_ms real);
-- schedmon.txt: per second, per thread: CPU and run-queue wait as % of wall, major faults
CREATE TABLE IF NOT EXISTS sched (run text, t timestamptz, rel_s double precision, rt timestamptz, thread text,
  cpu_pct real, runq_pct real, majflt integer);
-- console lines with their epoch (pzopt-loadtrace.out): boot, load, harness and pzopt messages
CREATE TABLE IF NOT EXISTS events (run text, t timestamptz, rel_s double precision, rt timestamptz, source text,
  level text, category text, text text);
-- pzopt-input.out: every key / mouse / pad change the game thread saw (pzopt.InputRecorder)
CREATE TABLE IF NOT EXISTS inputs (run text, t timestamptz, rel_s double precision, rt timestamptz, device text,
  control text, value double precision);
-- what the harness configured: run.opts, the harness flag file, pzopt.properties, options, env, vmargs, mods
CREATE TABLE IF NOT EXISTS run_inputs (run text, kind text, key text, value text);

-- pzopt-threads.out: CPU per Java thread over the route
CREATE TABLE IF NOT EXISTS threads (run text, thread text, cpu_ms double precision, share double precision);
-- numeric facts of pzopt-bench.out, its bake_counters= / zombie_batches= lines and the summary
CREATE TABLE IF NOT EXISTS counters (run text, grp text, key text, value double precision);

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY['frames','overlay','sysmon','gamethread','chunks','gc','present','series','stacks','sched','events','inputs','pacing'] LOOP
    EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON %I (run, rt)', tbl || '_run_rt', tbl);
  END LOOP;
END $$;
CREATE INDEX IF NOT EXISTS threads_run ON threads (run);
CREATE INDEX IF NOT EXISTS counters_run ON counters (run);
CREATE INDEX IF NOT EXISTS run_inputs_run ON run_inputs (run);
CREATE INDEX IF NOT EXISTS stacks_stack_id ON stacks (stack_id);

-- live: the game that is running now (ingest.py --follow tails ~/Zomboid and the run dir), pruned after 6 h
CREATE TABLE IF NOT EXISTS live_overlay (t timestamptz, fps real, ms real, gpu_ms real, cpu_load real, gpu_load real,
  game_load real, render_load real);
CREATE TABLE IF NOT EXISTS live_frames (t timestamptz, ms real);
CREATE TABLE IF NOT EXISTS live_sysmon (t timestamptz, cpu_pct real, busiest_core_pct real, gpu_pct real,
  gpu_sm_mhz real, gpu_mem_mhz real, gpu_w real, gpu_c real, vram_mib real, game_cpu_pct real, bat_w real);
CREATE TABLE IF NOT EXISTS live_gamethread (t timestamptz, kind text, name text, samples integer, share real);
CREATE TABLE IF NOT EXISTS live_state (k text PRIMARY KEY, v text, t timestamptz DEFAULT now());
CREATE TABLE IF NOT EXISTS live_stacks (t timestamptz, stack_id bigint, samples integer);
CREATE TABLE IF NOT EXISTS live_inputs (t timestamptz, device text, control text, value double precision);
CREATE INDEX IF NOT EXISTS live_overlay_t ON live_overlay (t);
CREATE INDEX IF NOT EXISTS live_frames_t ON live_frames (t);
CREATE INDEX IF NOT EXISTS live_sysmon_t ON live_sysmon (t);
CREATE INDEX IF NOT EXISTS live_gamethread_t ON live_gamethread (t);
CREATE INDEX IF NOT EXISTS live_stacks_t ON live_stacks (t);
CREATE INDEX IF NOT EXISTS live_stacks_stack_id ON live_stacks (stack_id);
CREATE INDEX IF NOT EXISTS live_inputs_t ON live_inputs (t);

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'grafana') THEN
    CREATE ROLE grafana LOGIN PASSWORD 'grafana';
  END IF;
END $$;
GRANT USAGE ON SCHEMA public TO grafana;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO grafana;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO grafana;
