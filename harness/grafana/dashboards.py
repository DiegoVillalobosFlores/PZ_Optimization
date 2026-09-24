#!/usr/bin/env python3
"""Write the provisioned Grafana dashboards (harness/grafana/dashboards/*.json); stack.sh up runs it.

  pzopt-runs     every run in the time range: table (links to Run / Compare), trends, headroom findings
  pzopt-run      one run on the route-time axis: tails, fps, GPU time, utilization, game-thread phases,
                 chunks, GC, flips, drive, profiler flame graphs (in-game / Lua / JFR / async-profiler), schedmon,
                 PSI, memory, pacing, every input the game saw + the harness configuration, console, counters
  pzopt-compare  several runs on one route-time axis, deltas against a base run, diff flame graph
  pzopt-live     the game running now (ingest.py --follow), refreshed every second

Route time: the Run and Compare dashboards plot `rt` = 2000-01-01 00:00:00 UTC + seconds since the
route start, so 00:00:00 is the route start of every run and 23:59:xx is the settle before it.
Edit this file, not the JSON; Grafana reloads the JSON within 10 s.
"""
import json
from pathlib import Path

OUT = Path(__file__).resolve().parent / "dashboards"
DS = {"type": "grafana-postgresql-datasource", "uid": "pzopt-pg"}
ROUTE_FROM, ROUTE_TO = "1999-12-31T23:59:30.000Z", "2000-01-01T00:02:00.000Z"
RT0 = "timestamptz '2000-01-01 00:00:00+00'"
RUN = "${run:sqlstring}"
RUNS = "${runs:sqlstring}"
IN_ROUTE = f"rt >= {RT0} AND rt <= {RT0} + make_interval(secs => coalesce((SELECT route_seconds FROM runs r WHERE r.run = x.run), 1e9))"

# the objective's thresholds (docs/plan-driving-frame-time.md): 240 fps cap, p99 10 ms, p99.9 16.7 ms
FRAME_STEPS = [("green", None), ("#EAB839", 4.167), ("orange", 10), ("red", 16.7)]
UTIL_STEPS = [("red", None), ("orange", 50), ("#EAB839", 75), ("green", 90)]
FPS_STEPS = [("red", None), ("orange", 60), ("#EAB839", 144), ("green", 235)]


def steps(s):
    return {"mode": "absolute", "steps": [{"color": c, "value": v} for c, v in s]}


def q(sql, fmt="time_series", ref="A", hide=False):
    return {"refId": ref, "datasource": DS, "rawQuery": True, "editorMode": "code", "format": fmt, "rawSql": sql.strip(), "hide": hide}


class Layout:
    def __init__(self):
        self.panels, self.x, self.y, self.row_h, self.id = [], 0, 0, 0, 1

    def add(self, p, w, h):
        if self.x + w > 24:
            self.x, self.y, self.row_h = 0, self.y + self.row_h, 0
        p["id"] = self.id
        p["gridPos"] = {"x": self.x, "y": self.y, "w": w, "h": h}
        self.id += 1
        self.x += w
        self.row_h = max(self.row_h, h)
        self.panels.append(p)
        return p

    def row(self, title):
        self.x, self.y = 0, self.y + self.row_h
        self.row_h = 0
        self.add({"type": "row", "title": title, "collapsed": False, "panels": []}, 24, 1)


def ts_panel(title, targets, unit="ms", desc="", stack=False, points=False, bars=False, thresholds=None, overrides=(),
             minv=None, maxv=None, legend="bottom", fill=None, log=False, point_size=None):
    custom = {
        "drawStyle": "bars" if bars else ("points" if points else "line"),
        "lineWidth": 1, "pointSize": point_size or (3 if points else 4), "showPoints": "never" if not points else "always",
        "fillOpacity": fill if fill is not None else (60 if stack else (80 if bars else 8)),
        "spanNulls": False, "stacking": {"mode": "normal" if stack else "none", "group": "A"},
        "gradientMode": "none", "axisSoftMin": 0,
        "insertNulls": 3000,  # break the line across gaps (between runs on the live view, loading stalls) instead of bridging them
    }
    if log:
        custom["scaleDistribution"] = {"type": "log", "log": 2}
    if thresholds:
        custom["thresholdsStyle"] = {"mode": "dashed"}
    d = {"unit": unit, "custom": custom, "color": {"mode": "palette-classic"}}
    if thresholds:
        d["thresholds"] = steps(thresholds)
    if minv is not None:
        d["min"] = minv
    if maxv is not None:
        d["max"] = maxv
    return {
        "type": "timeseries", "title": title, "description": desc, "datasource": DS, "targets": targets,
        "fieldConfig": {"defaults": d, "overrides": list(overrides)},
        "options": {"legend": {"displayMode": "list", "placement": legend, "showLegend": legend != "hidden"},
                    "tooltip": {"mode": "multi", "sort": "desc"}},
    }


def stat_panel(title, sql, unit="none", thr=None, desc="", decimals=None, text_mode="value_and_name", overrides=(), color_mode="background", value_size=26):
    d = {"unit": unit, "color": {"mode": "thresholds"}, "thresholds": steps(thr or [("text", None)])}
    if decimals is not None:
        d["decimals"] = decimals
    return {
        "type": "stat", "title": title, "description": desc, "datasource": DS, "targets": [q(sql, "table")],
        "fieldConfig": {"defaults": d, "overrides": list(overrides)},
        "options": {"reduceOptions": {"values": False, "calcs": ["lastNotNull"], "fields": "/.*/"}, "colorMode": color_mode,
                    "graphMode": "none", "textMode": text_mode, "justifyMode": "center", "orientation": "auto", "wideLayout": True,
                    "text": {"titleSize": 12, "valueSize": value_size}},
    }


def table_panel(title, sql, desc="", overrides=(), transformations=(), sort=None, unit=None, cell=None):
    d = {"custom": {"align": "auto", "cellOptions": cell or {"type": "auto"}, "filterable": False}}
    if unit:
        d["unit"] = unit
    return {
        "type": "table", "title": title, "description": desc, "datasource": DS, "targets": [q(sql, "table")],
        "fieldConfig": {"defaults": d, "overrides": list(overrides)},
        "transformations": list(transformations),
        "options": {"showHeader": True, "cellHeight": "sm", "footer": {"show": False, "reducer": ["sum"], "fields": ""},
                    "sortBy": [{"displayName": sort[0], "desc": sort[1]}] if sort else []},
    }


def ov(name, **props):
    """field override by name: unit=, thresholds=[...], color_cell=True, link=(title, url), decimals=, width=, display="""
    p = []
    if "unit" in props:
        p.append({"id": "unit", "value": props["unit"]})
    if "decimals" in props:
        p.append({"id": "decimals", "value": props["decimals"]})
    if "thresholds" in props:
        p.append({"id": "thresholds", "value": steps(props["thresholds"])})
        p.append({"id": "color", "value": {"mode": "thresholds"}})
    if props.get("color_cell"):
        p.append({"id": "custom.cellOptions", "value": {"type": "color-background", "mode": "basic"}})
        # a missing value (a run without that log) stays uncoloured instead of taking the base threshold colour
        p.append({"id": "mappings", "value": [{"type": "special", "options": {"match": "null", "result": {"text": "-", "color": "transparent"}}}]})
    if props.get("gauge"):
        p.append({"id": "custom.cellOptions", "value": {"type": "gauge", "mode": "basic", "valueDisplayMode": "text"}})
    if "link" in props:
        p.append({"id": "links", "value": [{"title": t, "url": u} for t, u in props["link"]]})
    if "width" in props:
        p.append({"id": "custom.width", "value": props["width"]})
    if "color" in props:
        p.append({"id": "color", "value": {"mode": "fixed", "fixedColor": props["color"]}})
    if "axis" in props:
        p.append({"id": "custom.axisPlacement", "value": props["axis"]})
    if "max" in props:
        p.append({"id": "max", "value": props["max"]})
    if "min" in props:
        p.append({"id": "min", "value": props["min"]})
    if "hidden" in props:
        p.append({"id": "custom.hidden", "value": True})
    if "points" in props:
        p.append({"id": "custom.drawStyle", "value": "points"})
        p.append({"id": "custom.pointSize", "value": 3})
    if "regex" in props:
        return {"matcher": {"id": "byRegexp", "options": name}, "properties": p}
    return {"matcher": {"id": "byName", "options": name}, "properties": p}


def var_query(name, label, sql, multi=False, include_all=False, current=None):
    v = {"type": "query", "name": name, "label": label, "datasource": DS, "definition": sql, "query": sql,
         "refresh": 2, "multi": multi, "includeAll": include_all, "sort": 0, "hide": 0, "options": []}
    if include_all:
        v["allValue"] = None
    if current is not None:
        v["current"] = current
    return v


def dashboard(uid, title, layout, variables=(), time=("now-7d", "now"), tz="browser", refresh="", annotations=(), desc="", links=()):
    return {
        "uid": uid, "title": title, "description": desc, "tags": ["pzopt"], "timezone": tz, "editable": False,
        "graphTooltip": 1, "schemaVersion": 41, "version": 1, "refresh": refresh, "liveNow": False,
        "time": {"from": time[0], "to": time[1]},
        "timepicker": {"refresh_intervals": ["1s", "2s", "5s", "10s", "30s", "1m", "5m"]},
        "templating": {"list": list(variables)},
        "annotations": {"list": [{"builtIn": 1, "datasource": {"type": "grafana", "uid": "-- Grafana --"}, "enable": True,
                                  "hide": True, "iconColor": "rgba(0, 211, 255, 1)", "name": "Annotations & Alerts", "type": "dashboard"}]
                        + list(annotations)},
        "links": [{"title": "PZ Optimization", "type": "dashboards", "tags": ["pzopt"], "asDropdown": False, "includeVars": False}] + list(links),
        "panels": layout.panels,
    }


def annotation(name, sql, color):
    return {"name": name, "datasource": DS, "enable": True, "hide": False, "iconColor": color,
            "target": {"refId": "Anno", "rawQuery": True, "editorMode": "code", "format": "table", "rawSql": sql.strip()}}



def flame_sql(where):
    """Grafana's flame graph frame (level, value, self, label, depth-first) from the stacks matching `where`: every
    prefix of every stack is a node; ordering by the root-first frame array is a depth-first walk."""
    return f"""
WITH a AS (SELECT stack_id, sum(samples) AS c FROM stacks WHERE {where} GROUP BY 1),
f AS (SELECT d.frames, a.c FROM a JOIN stack_defs d ON d.id = a.stack_id),
n AS (SELECT f.frames[1:i] AS path, sum(f.c) AS value, sum(f.c) FILTER (WHERE i = cardinality(f.frames)) AS self
      FROM f, generate_series(0, cardinality(f.frames)) AS i GROUP BY 1)
SELECT cardinality(path) AS level, value, coalesce(self, 0) AS self, coalesce(path[cardinality(path)], 'all') AS label FROM n ORDER BY path"""


def flame_diff_sql(where_left, where_right):
    """Diff flame graph: value = both runs, valueRight = the right run (Grafana colours by the change in share)."""
    return f"""
WITH a AS (SELECT stack_id, sum(samples) AS c, 0 AS r FROM stacks WHERE {where_left} GROUP BY 1
           UNION ALL SELECT stack_id, 0, sum(samples) FROM stacks WHERE {where_right} GROUP BY 1),
f AS (SELECT d.frames, a.c, a.r FROM a JOIN stack_defs d ON d.id = a.stack_id),
n AS (SELECT f.frames[1:i] AS path, sum(f.c + f.r) AS value, sum(f.r) AS value_right,
             sum(f.c + f.r) FILTER (WHERE i = cardinality(f.frames)) AS self, sum(f.r) FILTER (WHERE i = cardinality(f.frames)) AS self_right
      FROM f, generate_series(0, cardinality(f.frames)) AS i GROUP BY 1)
SELECT cardinality(path) AS level, value, coalesce(self, 0) AS self, coalesce(path[cardinality(path)], 'all') AS label,
  value_right AS "valueRight", coalesce(self_right, 0) AS "selfRight" FROM n ORDER BY path"""


def flame_panel(title, sql, desc=""):
    return {"type": "flamegraph", "title": title, "description": desc, "datasource": DS, "targets": [q(sql, "table")],
            "fieldConfig": {"defaults": {"unit": "short"}, "overrides": []}, "options": {}}


def logs_panel(title, sql, desc=""):
    return {"type": "logs", "title": title, "description": desc, "datasource": DS, "targets": [q(sql, "table")],
            "options": {"showTime": True, "wrapLogMessage": True, "sortOrder": "Ascending", "enableLogDetails": True,
                        "dedupStrategy": "none", "prettifyLogMessage": False, "showLabels": False, "showCommonLabels": False}}


def state_panel(title, sql, desc=""):
    """Pressed / released per control; each change event holds until the next one."""
    return {"type": "state-timeline", "title": title, "description": desc, "datasource": DS, "targets": [q(sql)],
            "fieldConfig": {"defaults": {"custom": {"fillOpacity": 80, "lineWidth": 0, "spanNulls": True},
                                         "color": {"mode": "thresholds"}, "thresholds": steps([("transparent", None), ("green", 0.5)]),
                                         "mappings": [{"type": "value", "options": {"0": {"text": "up"}, "1": {"text": "down"}}}]},
                            "overrides": []},
            "options": {"mergeValues": True, "showValue": "never", "alignValue": "left", "rowHeight": 0.8,
                        "legend": {"showLegend": False}, "tooltip": {"mode": "single"}}}


def custom_var(name, label, values, default):
    return {"type": "custom", "name": name, "label": label, "query": ",".join(values), "multi": False, "includeAll": False,
            "hide": 0, "current": {"text": default, "value": default},
            "options": [{"text": v, "value": v, "selected": v == default} for v in values]}


BUTTONS = "control NOT IN ('x', 'y', 'wheel', 'lx', 'ly', 'rx', 'ry', 'lt', 'rt', 'hat')"
ANALOG = "control IN ('x', 'y', 'lx', 'ly', 'rx', 'ry', 'lt', 'rt')"

# ------------------------------------------------------------------ Runs

BOUND = """CASE
  WHEN gpu_pct >= 90 THEN 'GPU-bound'
  WHEN game_thread_pct >= 90 OR game_load_pct >= 90 THEN 'game thread'
  WHEN fps_mean >= 235 AND props NOT LIKE '%uncappedFps=true%' THEN 'at 240 cap'
  WHEN fps_mean IS NULL THEN NULL
  ELSE 'headroom left' END"""


def runs_dashboard():
    L = Layout()
    where = "$__timeFilter(started) AND machine IN (${machine:sqlstring}) AND coalesce(mode, '') IN (${mode:sqlstring}) AND label ~ ${label:sqlstring}"
    L.add(stat_panel("Runs", color_mode="none", sql=f"SELECT count(*) AS runs, count(*) FILTER (WHERE valid) AS valid, count(*) FILTER (WHERE verdict = 'invalid' OR NOT valid) AS invalid FROM runs WHERE {where}"), 6, 4)
    L.add(stat_panel("Newest run", f"SELECT run, fps_mean AS fps, p99_ms AS \"p99 ms\", p99_9_ms AS \"p99.9 ms\", gpu_pct AS \"GPU %\", busiest_core_pct AS \"busiest core %\" FROM runs WHERE {where} ORDER BY started DESC LIMIT 1",
                     decimals=1, color_mode="none", value_size=20), 18, 4)
    L.add(table_panel(
        "Runs", f"""
SELECT started, run, fps_mean AS fps, fps_1pct_low AS "1% low", p50_ms AS p50, p99_ms AS p99, p99_9_ms AS "p99.9", max_ms AS max,
  over_33ms AS ">33ms", jitter_ms AS jitter, cpu_pct AS "CPU %", busiest_core_pct AS "busiest core %", gpu_pct AS "GPU %",
  game_thread_pct AS "game thread %", total_w AS "W", j_per_frame AS "J/frame", {BOUND} AS bound, verdict, round(verdict_confidence::numeric, 2) AS conf, valid,
  variant, machine, mode, preset, chunk_p99_ms AS "chunk p99", gc_events AS gc, zoom, resolution, label,
  lag(run) OVER (PARTITION BY label ORDER BY started) AS previous
FROM runs WHERE {where} ORDER BY started DESC""",
        desc="Frame times in ms over the route window (pzopt-frames.out); CPU / GPU from sysmon, game thread from pzopt-threads.out. "
             "Click a run for its dashboard, 'previous' to compare with the last run of the same label.",
        overrides=[
            ov("run", link=[("Run dashboard", "/d/pzopt-run/run?var-run=${__value.raw}")], width=300),
            ov("previous", link=[("Compare with this run", "/d/pzopt-compare/compare?var-runs=${__data.fields.previous}&var-runs=${__data.fields.run}&var-base=${__data.fields.previous}")], width=260),
            ov("started", unit="dateTimeAsIso", width=160),
            ov("fps", thresholds=FPS_STEPS, color_cell=True, decimals=1),
            ov("1% low", decimals=1),
            *[ov(n, thresholds=FRAME_STEPS, color_cell=True, decimals=2, unit="ms") for n in ("p50", "p99", "p99.9", "max")],
            ov("jitter", unit="ms", decimals=2),
            *[ov(n, thresholds=UTIL_STEPS, color_cell=True, decimals=0, unit="percent") for n in ("CPU %", "busiest core %", "GPU %", "game thread %")],
            ov("chunk p99", unit="ms", decimals=0),
            ov("W", unit="watt", decimals=0), ov("J/frame", unit="joule", decimals=3),
        ]), 24, 14)
    L.row("Trends (one point per run, series = label)")
    trend = lambda col: f"SELECT started AS time, label AS metric, {col} AS value FROM runs WHERE {where} AND {col} IS NOT NULL ORDER BY 1"  # noqa: E731
    L.add(ts_panel("fps (route mean)", [q(trend("fps_mean"))], unit="none", points=True, point_size=7, thresholds=[("transparent", None), ("green", 240)], legend="right"), 12, 9)
    L.add(ts_panel("p99 frame time", [q(trend("p99_ms"))], points=True, point_size=7, thresholds=[("transparent", None), ("orange", 10)], legend="right", log=True), 12, 9)
    L.add(ts_panel("p99.9 frame time", [q(trend("p99_9_ms"))], points=True, point_size=7, thresholds=[("transparent", None), ("red", 16.7)], legend="right", log=True), 12, 9)
    L.add(ts_panel("GPU busy (route mean)", [q(trend("gpu_pct"))], unit="percent", points=True, point_size=7, minv=0, maxv=100, legend="right"), 12, 9)
    L.add(ts_panel("Busiest core (route mean)", [q(trend("busiest_core_pct"))], unit="percent", points=True, point_size=7, minv=0, maxv=100, legend="right"), 12, 9)
    L.add(ts_panel("Chunk latency p99 (enqueue → publish)", [q(trend("chunk_p99_ms"))], points=True, point_size=7, legend="right"), 12, 9)
    L.add(ts_panel("Power (route mean, whole machine)", [q(trend("total_w"))], unit="watt", points=True, point_size=7, legend="right",
                   desc="runs.total_w: sysmon's total_w (battery on battery, else CPU package or APU socket + discrete GPU), else the game's pzopt-power.out, else the Mac's macpower system_w. Empty without a CPU reading (scripts/power-access.sh for RAPL)."), 12, 9)
    L.add(ts_panel("Energy per frame (route)", [q(trend("j_per_frame"))], unit="joule", points=True, point_size=7, legend="right",
                   desc="route-mean total watts / route-mean fps"), 12, 9)
    L.row("Objective findings")
    L.add(table_panel(
        "Below the cap with hardware left over", f"""
SELECT started, run, fps_mean AS fps, p99_ms AS p99, gpu_pct AS "GPU %", busiest_core_pct AS "busiest core %",
  game_thread_pct AS "game thread %", headroom_finding AS "Jev headroom"
FROM runs WHERE {where} AND {BOUND} = 'headroom left' ORDER BY started DESC""",
        desc="The objective: fps below the 240 cap with neither the GPU (≥ 90 %) nor the game thread (≥ 90 %) saturated is itself a finding.",
        overrides=[ov("run", link=[("Run dashboard", "/d/pzopt-run/run?var-run=${__value.raw}")]), ov("started", unit="dateTimeAsIso"),
                   *[ov(n, thresholds=UTIL_STEPS, color_cell=True, decimals=0, unit="percent") for n in ("GPU %", "busiest core %", "game thread %")],
                   ov("fps", decimals=1), ov("p99", unit="ms", decimals=2)]), 24, 8)
    variables = [
        var_query("machine", "machine", "SELECT DISTINCT machine FROM runs ORDER BY 1", multi=True, include_all=True,
                  current={"text": "All", "value": "$__all"}),
        var_query("mode", "mode", "SELECT DISTINCT coalesce(mode, '') FROM runs ORDER BY 1", multi=True, include_all=True,
                  current={"text": "All", "value": "$__all"}),
        {"type": "textbox", "name": "label", "label": "label regex", "query": ".*", "current": {"text": ".*", "value": ".*"}, "hide": 0},
    ]
    return dashboard("pzopt-runs", "PZ runs", L, variables, desc="Every harness run imported by harness/grafana/ingest.py")


# ------------------------------------------------------------------ Run

def run_dashboard():
    L = Layout()
    one = f"FROM runs WHERE run = {RUN}"
    L.add(stat_panel("Frame time (route)", f"SELECT p50_ms AS p50, p99_ms AS p99, p99_9_ms AS \"p99.9\", max_ms AS max {one}",
                     unit="ms", thr=FRAME_STEPS, decimals=2, desc="pzopt-frames.out over the route window"), 8, 4)
    L.add(stat_panel("Throughput", f"SELECT fps_mean AS fps, fps_1pct_low AS \"1% low\", over_33ms AS \">33 ms\", jitter_ms AS \"jitter ms\" {one}",
                     decimals=1, thr=[("text", None)], color_mode="none"), 8, 4)
    L.add(stat_panel("Utilization (route mean)", f"SELECT cpu_pct AS CPU, busiest_core_pct AS \"busiest core\", gpu_pct AS GPU, game_thread_pct AS \"game thread\", render_thread_pct AS \"render thread\" {one}",
                     unit="percent", thr=UTIL_STEPS, decimals=0), 8, 4)
    L.add(stat_panel("Verdict", f"SELECT coalesce(verdict, '-') AS \"Jev\", {BOUND} AS bound, CASE WHEN valid THEN 'valid' ELSE 'INVALID' END AS route, variant {one}",
                     text_mode="value_and_name", color_mode="none", value_size=18), 8, 3)
    L.add(stat_panel("Chunks / GC", f"SELECT chunk_p99_ms AS \"chunk p99 ms\", chunks_per_s AS \"chunks/s\", gc_events AS \"GC pauses\", gc_max_ms AS \"worst GC ms\" {one}",
                     decimals=1, color_mode="none"), 8, 3)
    L.add(stat_panel("GPU", f"SELECT gpu_ms_mean AS \"GPU ms/frame\", gpu_w AS watts, gpu_c AS \"°C\", vram_mib AS \"VRAM MiB\" {one}",
                     decimals=1, color_mode="none"), 8, 3)
    L.add(table_panel("Run", f"""
SELECT k AS fact, v AS value FROM runs, LATERAL (VALUES
  ('started', to_char(started, 'YYYY-MM-DD HH24:MI:SS')), ('machine', machine), ('platform', platform), ('mode', mode), ('preset', preset),
  ('route', route), ('zoom', zoom), ('resolution', resolution), ('opengl', opengl), ('jvm', jvm), ('launcher', launcher),
  ('variant', variant), ('props', props), ('flags', flags), ('route seconds', round(route_seconds::numeric, 2)::text),
  ('zombies loaded', zombies_loaded::text), ('verdict', verdict || ' (' || round(verdict_confidence::numeric, 2) || ')'), ('path', path)
) AS x(k, v) WHERE run = {RUN} AND v IS NOT NULL""", overrides=[ov("fact", width=130)]), 24, 11)

    L.row("Frame time (x = route time, 00:00:00 = route start)")
    L.add(ts_panel("Every frame", [
        q(f"SELECT rt AS time, ms AS \"game frame\" FROM frames WHERE run = {RUN} AND $__timeFilter(rt) ORDER BY 1"),
        q(f"SELECT rt AS time, ms AS \"presented\" FROM overlay WHERE run = {RUN} AND $__timeFilter(rt) ORDER BY 1", ref="B"),
        q(f"SELECT rt AS time, ms AS \"GPU\" FROM (SELECT rt, gpu_ms AS ms FROM overlay WHERE run = {RUN} AND $__timeFilter(rt)) x ORDER BY 1", ref="C")],
        desc="game frame = pzopt-frames.out, presented = the overlay's presented frame, GPU = GL timer query per frame. Dashed: 4.17 (240 fps), 10 (p99 target), 16.7 ms (p99.9 target).",
        thresholds=[("transparent", None), ("green", 4.167), ("orange", 10), ("red", 16.7)], log=True,
        overrides=[ov("GPU", color="purple"), ov("presented", color="#8AB8FF")]), 24, 10)
    L.add(ts_panel("Per second: p50 / p99 / max", [q(f"""
SELECT date_trunc('second', rt) AS time, percentile_cont(0.5) WITHIN GROUP (ORDER BY ms) AS p50,
  percentile_cont(0.99) WITHIN GROUP (ORDER BY ms) AS p99, max(ms) AS max
FROM frames WHERE run = {RUN} AND $__timeFilter(rt) GROUP BY 1 ORDER BY 1""")], log=True,
        thresholds=[("transparent", None), ("orange", 10), ("red", 16.7)]), 12, 8)
    L.add(ts_panel("fps per second", [q(f"""
SELECT date_trunc('second', rt) AS time, count(*) / nullif(sum(ms) / 1000, 0) AS fps, min(1000 / nullif(ms, 0)) AS "slowest frame as fps"
FROM frames WHERE run = {RUN} AND $__timeFilter(rt) GROUP BY 1 ORDER BY 1""")], unit="none",
        thresholds=[("transparent", None), ("green", 240)]), 12, 8)
    L.add({"type": "histogram", "title": "Frame time distribution (route)", "datasource": DS,
           "targets": [q(f"SELECT ms AS \"frame ms\" FROM frames x WHERE run = {RUN} AND {IN_ROUTE}", "table")],
           "fieldConfig": {"defaults": {"unit": "ms", "custom": {"fillOpacity": 70}}, "overrides": []},
           "options": {"bucketSize": 0.5, "combine": False, "legend": {"showLegend": False}}}, 12, 8)
    L.add(table_panel("Percentiles (route)", f"""
SELECT 'game frame' AS source, count(*) AS frames,
  percentile_cont(0.5) WITHIN GROUP (ORDER BY ms) AS p50, percentile_cont(0.9) WITHIN GROUP (ORDER BY ms) AS p90,
  percentile_cont(0.99) WITHIN GROUP (ORDER BY ms) AS p99, percentile_cont(0.999) WITHIN GROUP (ORDER BY ms) AS "p99.9",
  max(ms) AS max, count(*) FILTER (WHERE ms > 16.7) AS ">16.7", count(*) FILTER (WHERE ms > 33.3) AS ">33.3", stddev(ms) AS stdev
FROM frames x WHERE run = {RUN} AND {IN_ROUTE}
UNION ALL
SELECT 'presented', count(*), percentile_cont(0.5) WITHIN GROUP (ORDER BY ms), percentile_cont(0.9) WITHIN GROUP (ORDER BY ms),
  percentile_cont(0.99) WITHIN GROUP (ORDER BY ms), percentile_cont(0.999) WITHIN GROUP (ORDER BY ms), max(ms),
  count(*) FILTER (WHERE ms > 16.7), count(*) FILTER (WHERE ms > 33.3), stddev(ms)
FROM overlay x WHERE run = {RUN} AND {IN_ROUTE}
UNION ALL
SELECT 'GPU time', count(*), percentile_cont(0.5) WITHIN GROUP (ORDER BY gpu_ms), percentile_cont(0.9) WITHIN GROUP (ORDER BY gpu_ms),
  percentile_cont(0.99) WITHIN GROUP (ORDER BY gpu_ms), percentile_cont(0.999) WITHIN GROUP (ORDER BY gpu_ms), max(gpu_ms),
  count(*) FILTER (WHERE gpu_ms > 16.7), count(*) FILTER (WHERE gpu_ms > 33.3), stddev(gpu_ms)
FROM overlay x WHERE run = {RUN} AND {IN_ROUTE}
UNION ALL
SELECT 'flip interval', count(*), percentile_cont(0.5) WITHIN GROUP (ORDER BY interval_ms), percentile_cont(0.9) WITHIN GROUP (ORDER BY interval_ms),
  percentile_cont(0.99) WITHIN GROUP (ORDER BY interval_ms), percentile_cont(0.999) WITHIN GROUP (ORDER BY interval_ms), max(interval_ms),
  count(*) FILTER (WHERE interval_ms > 16.7), count(*) FILTER (WHERE interval_ms > 33.3), stddev(interval_ms)
FROM present x WHERE run = {RUN} AND {IN_ROUTE}""", unit="ms",
        overrides=[ov("frames", unit="none"), ov(">16.7", unit="none"), ov(">33.3", unit="none"), ov("source", width=110)]), 12, 8)

    L.row("Utilization")
    L.add(ts_panel("Machine (sysmon)", [q(f"""
SELECT rt AS time, cpu_pct AS "CPU", busiest_core_pct AS "busiest core", gpu_pct AS "GPU"
FROM sysmon WHERE run = {RUN} AND $__timeFilter(rt) ORDER BY 1""")], unit="percent", minv=0, maxv=100), 12, 8)
    L.add(ts_panel("Threads (overlay, per frame)", [q(f"""
SELECT date_trunc('second', rt) + (extract(milliseconds FROM rt)::int / 100) * interval '100 ms' AS time,
  avg(game_load) AS "game thread", avg(render_load) AS "render thread", avg(gpu_load) AS "GPU", avg(cpu_load) AS "CPU"
FROM overlay WHERE run = {RUN} AND $__timeFilter(rt) GROUP BY 1 ORDER BY 1""")], unit="percent", minv=0, maxv=100,
        desc="100 ms means of the overlay's own load columns"), 12, 8)
    L.add(ts_panel("Game process cores / GPU power", [q(f"""
SELECT rt AS time, game_cpu_pct / 100 AS "game process cores", gpu_w AS "GPU W", gpu_c AS "GPU °C"
FROM sysmon WHERE run = {RUN} AND $__timeFilter(rt) ORDER BY 1""")], unit="none",
        overrides=[ov("GPU W", unit="watt", axis="right"), ov("GPU °C", unit="celsius", axis="right")]), 12, 8)
    L.add(ts_panel("GPU clocks / VRAM", [q(f"""
SELECT rt AS time, gpu_sm_mhz AS "SM MHz", gpu_mem_mhz AS "memory MHz", vram_mib AS "VRAM MiB"
FROM sysmon WHERE run = {RUN} AND $__timeFilter(rt) ORDER BY 1""")], unit="none",
        overrides=[ov("VRAM MiB", unit="mbytes", axis="right")]), 12, 8)
    L.add(table_panel("CPU per thread (route)", f"SELECT thread, cpu_ms AS \"CPU ms\", share * 100 AS \"% of wall\" FROM threads WHERE run = {RUN} ORDER BY share DESC LIMIT 40",
                      overrides=[ov("% of wall", unit="percent", gauge=True, max=100, min=0, thresholds=[("green", None), ("orange", 75), ("red", 90)]), ov("thread", width=260)]), 24, 9)

    L.row("Power")
    L.add(stat_panel("Power (route mean)", f"SELECT total_w AS \"total W\", cpu_w AS \"CPU W\", gpu_w AS \"GPU W\", j_per_frame AS \"J/frame\", power_source AS source {one}",
                     decimals=2, color_mode="none", value_size=20,
                     desc="The run's best power source over the route window: sysmon (harness), else the game's pzopt-power.out, else macpower. total = battery on battery, else CPU package (RAPL) or APU socket + discrete GPU; empty without a CPU reading (scripts/power-access.sh)."), 24, 4)
    L.add(ts_panel("Power (sysmon, every 0.5 s)", [q(f"""
SELECT rt AS time, total_w AS "total", cpu_w AS "CPU package", soc_w AS "APU socket", gpu_w AS "GPU", bat_w AS "battery"
FROM sysmon WHERE run = {RUN} AND $__timeFilter(rt) ORDER BY 1""")], unit="watt", minv=0,
        desc="harness/sysmon.sh: CPU package from RAPL, GPU from nvidia-smi / amdgpu hwmon, battery discharge, total as in the stat"), 12, 8)
    L.add(ts_panel("Power (in game / macpower)", [q(f"""
SELECT rt AS time, source || ' total' AS metric, total_w AS value FROM power WHERE run = {RUN} AND total_w IS NOT NULL AND $__timeFilter(rt)
UNION ALL SELECT rt, source || ' CPU', cpu_w FROM power WHERE run = {RUN} AND cpu_w IS NOT NULL AND $__timeFilter(rt)
UNION ALL SELECT rt, source || ' APU socket', soc_w FROM power WHERE run = {RUN} AND soc_w IS NOT NULL AND $__timeFilter(rt)
UNION ALL SELECT rt, source || ' GPU', gpu_w FROM power WHERE run = {RUN} AND gpu_w IS NOT NULL AND $__timeFilter(rt)
UNION ALL SELECT rt, source || ' battery', bat_w FROM power WHERE run = {RUN} AND bat_w IS NOT NULL AND $__timeFilter(rt) ORDER BY 1""")],
        unit="watt", minv=0, desc="game = pzopt.Power (the overlay's power line, pzopt-power.out); mac = harness/macpower.py (total = the SMC's whole-system load, display included)"), 12, 8)
    L.add(ts_panel("Energy per frame (per second)", [q(f"""
WITH w AS (SELECT date_trunc('second', rt) AS s, avg(total_w) AS w FROM sysmon WHERE run = {RUN} AND total_w IS NOT NULL AND $__timeFilter(rt) GROUP BY 1),
     f AS (SELECT date_trunc('second', rt) AS s, count(*) / nullif(sum(ms) / 1000, 0) AS fps FROM frames WHERE run = {RUN} AND $__timeFilter(rt) GROUP BY 1)
SELECT w.s AS time, w.w / nullif(f.fps, 0) AS "J/frame (sysmon)" FROM w JOIN f USING (s) ORDER BY 1"""),
                                                      q(f"""
SELECT date_trunc('second', rt) AS time, avg(total_w) / nullif(avg(fps), 0) AS "J/frame (in game)"
FROM power WHERE run = {RUN} AND source = 'game' AND total_w IS NOT NULL AND $__timeFilter(rt) GROUP BY 1 ORDER BY 1""", ref="B")],
        unit="joule", minv=0, desc="total watts / frames per second in each second"), 24, 8)

    L.row("Game thread (pzopt-gamethread.out, share of stack samples)")
    L.add(ts_panel("Phases", [q(f"SELECT rt AS time, name AS metric, share * 100 AS value FROM gamethread WHERE run = {RUN} AND kind = 'p' AND $__timeFilter(rt) ORDER BY 1")],
                   unit="percent", stack=True, minv=0, maxv=100, legend="right"), 24, 9)
    L.add(ts_panel("Sub-phases (top 12 over the route)", [q(f"""
WITH top AS (SELECT name FROM gamethread x WHERE run = {RUN} AND kind = 's' AND {IN_ROUTE} GROUP BY name ORDER BY sum(samples) DESC LIMIT 12)
SELECT rt AS time, name AS metric, share * 100 AS value FROM gamethread WHERE run = {RUN} AND kind = 's' AND name IN (SELECT name FROM top) AND $__timeFilter(rt) ORDER BY 1""")],
                   unit="percent", stack=True, minv=0, legend="right"), 24, 9)
    for title, kind, n in (("Sub-phases", "s", 30), ("Hot methods", "l", 40), ("Waits (not runnable)", "w", 25)):
        L.add(table_panel(f"{title} (route)", f"""
SELECT name, sum(samples) AS samples, 100.0 * sum(samples) / nullif((SELECT sum(samples) FROM gamethread x WHERE run = {RUN} AND kind = 'total' AND {IN_ROUTE}), 0) AS share
FROM gamethread x WHERE run = {RUN} AND kind = '{kind}' AND {IN_ROUTE} GROUP BY name ORDER BY 2 DESC LIMIT {n}""",
                          overrides=[ov("share", unit="percent", decimals=1, gauge=True, min=0, max=100 if kind != "l" else 25), ov("samples", width=80)]), 8, 12)

    L.row("Chunks, GC, flips")
    L.add(ts_panel("Chunk latency (enqueue → publish, one point per chunk)", [
        q(f"SELECT rt AS time, total_ms AS \"total\" FROM chunks WHERE run = {RUN} AND $__timeFilter(rt) ORDER BY 1")],
        points=True, log=True), 12, 8)
    L.add(ts_panel("Chunk stages (per-second mean) and chunks/s", [q(f"""
SELECT date_trunc('second', rt) AS time, avg(queue_wait_ms) AS "queue wait", avg(load_ms) AS load, avg(recalc_wait_ms) AS "recalc wait",
  avg(recalc_ms) AS recalc, avg(publish_wait_ms) AS "publish wait", count(*) AS "chunks/s"
FROM chunks WHERE run = {RUN} AND $__timeFilter(rt) GROUP BY 1 ORDER BY 1""")], stack=True,
        overrides=[ov("chunks/s", unit="none", axis="right", color="text")]), 12, 8)
    L.add(ts_panel("GC pauses", [q(f"SELECT rt AS time, pause_ms AS \"pause\" FROM gc WHERE run = {RUN} AND $__timeFilter(rt) ORDER BY 1")], points=True,
                   desc="gc.log: every stop-the-world pause"), 12, 8)
    L.add(ts_panel("Flip interval on screen (X Present)", [q(f"SELECT rt AS time, interval_ms AS \"flip interval\" FROM present WHERE run = {RUN} AND $__timeFilter(rt) ORDER BY 1")],
                   points=True, log=True, desc="present.txt: time between the game window's flips"), 12, 8)

    L.row("Drive (path pilot, pzopt-drive.out)")
    L.add(ts_panel("Speed", [q(f"SELECT rt AS time, key AS metric, value FROM series WHERE run = {RUN} AND source = 'drive' AND key IN ('kmh', 'target_kmh') AND $__timeFilter(rt) ORDER BY 1")],
                   unit="velocitykmh"), 12, 8)
    L.add(ts_panel("Line and inputs", [q(f"SELECT rt AS time, key AS metric, value FROM series WHERE run = {RUN} AND source = 'drive' AND key IN ('xte', 'hdg_err', 'steer', 'brake', 'offset', 'obstacles', 'chunk_ahead') AND $__timeFilter(rt) ORDER BY 1")],
                   unit="none"), 12, 8)

    L.row("Profiler: flame graph (pick the source and threads above; drag across any time panel to narrow the window)")
    sel = f"run = {RUN} AND source = ${{source:sqlstring}} AND thread IN (${{thread:sqlstring}}) AND $__timeFilter(rt)"
    L.add(flame_panel("Flame graph: $source, $thread", flame_sql(sel),
                      desc="game = the in-game profiler's game-thread stacks (pzopt-stacks.out, 25-100 Hz); lua = the Lua profile "
                           "(pzopt-lua.out); jfr = --jfr (pzopt.jfr, Java threads); asprof = --asprof (asprof.jfr, every thread with native "
                           "frames). Root 'all' = every sample in the window; the top table beside it ranks self and total time."), 24, 22)
    L.add(ts_panel("Samples per second by thread ($source)", [q(f"SELECT date_trunc('second', rt) AS time, thread AS metric, sum(samples) AS value FROM stacks WHERE {sel} GROUP BY 1, 2 ORDER BY 1")],
                   unit="none", stack=True, legend="right"), 24, 8)

    L.row("Scheduler (schedmon.txt: --schedmon), memory pressure, frame pacing")
    top = f"(SELECT thread FROM sched WHERE run = {RUN} GROUP BY thread ORDER BY sum(cpu_pct) DESC LIMIT 12)"
    L.add(ts_panel("CPU per OS thread (top 12)", [q(f"SELECT rt AS time, thread AS metric, cpu_pct AS value FROM sched WHERE run = {RUN} AND thread IN {top} AND $__timeFilter(rt) ORDER BY 1")],
                   unit="percent", legend="right"), 12, 9)
    L.add(ts_panel("Run-queue wait per OS thread (runnable, no CPU)", [q(f"SELECT rt AS time, thread AS metric, runq_pct AS value FROM sched WHERE run = {RUN} AND thread IN {top} AND $__timeFilter(rt) ORDER BY 1")],
                   unit="percent", legend="right"), 12, 9)
    L.add(ts_panel("Pressure stall (PSI) and paging", [q(f"SELECT rt AS time, key AS metric, value FROM series WHERE run = {RUN} AND source = 'psi' AND $__timeFilter(rt) ORDER BY 1")],
                   unit="none", overrides=[ov(".*_pct", regex=True, unit="percent")]), 12, 8)
    L.add(ts_panel("Game process memory by mapping", [q(f"SELECT rt AS time, key AS metric, value FROM series WHERE run = {RUN} AND source = 'mem' AND $__timeFilter(rt) ORDER BY 1")],
                   unit="mbytes", points=True), 12, 8)
    L.add(ts_panel("Frame pacing (pzopt-pacing.out)", [q(f"SELECT rt AS time, sim_step_ms AS \"sim step\", acquire_lag_ms AS \"acquire lag\", swap_ms AS swap, hold_ms AS hold, gpu_after_swap_ms AS \"GPU after swap\", shown_after_swap_ms AS \"shown after swap\" FROM pacing WHERE run = {RUN} AND $__timeFilter(rt) ORDER BY 1")],
                   log=True, points=True,
                   desc="sim step = game-frame interval; acquire lag = game frame done -> render thread took it; swap = the swap call; hold = presentPacing's wait; GPU after swap; shown after swap (Mac)."), 24, 9)

    L.row("Inputs sent to the game (pzopt-input.out: every key / mouse / pad change the game thread saw)")
    L.add(state_panel("Keys and buttons", f"SELECT rt AS time, device || ' ' || control AS metric, value FROM inputs WHERE run = {RUN} AND {BUTTONS} AND $__timeFilter(rt) ORDER BY 1",
                      desc="Green = held. Every source counts: a real keyboard / mouse / pad, uinput (pad.py, inputlag-drive.py), xdotool, the in-game VirtualPad."), 24, 10)
    L.add(ts_panel("Analog: pointer and sticks", [q(f"SELECT rt AS time, device || ' ' || control AS metric, value FROM inputs WHERE run = {RUN} AND {ANALOG} AND $__timeFilter(rt) ORDER BY 1")],
                   unit="none", overrides=[ov("mouse .*", regex=True, axis="right", unit="suffix: px")]), 12, 8)
    L.add(ts_panel("Mouse wheel and pad hat", [q(f"SELECT rt AS time, device || ' ' || control AS metric, value FROM inputs WHERE run = {RUN} AND control IN ('wheel', 'hat') AND $__timeFilter(rt) ORDER BY 1")],
                   unit="none", bars=True), 12, 8)
    L.add(table_panel("Every input event", f"SELECT round(rel_s::numeric, 3) AS \"s from route start\", device, control, value FROM inputs WHERE run = {RUN} AND $__timeFilter(rt) ORDER BY rt LIMIT 5000"), 12, 12)
    L.add(table_panel("Configured by the harness (flags, props, options, env, vmargs, mods, run.opts)", f"SELECT kind, key, value FROM run_inputs WHERE run = {RUN} ORDER BY CASE kind WHEN 'flag' THEN 0 WHEN 'prop' THEN 1 WHEN 'option' THEN 2 ELSE 3 END, kind, key",
                      overrides=[ov("kind", width=90), ov("key", width=220)]), 12, 12)

    L.row("Console (pzopt-loadtrace.out: every console line with its epoch)")
    L.add(logs_panel("Console", f"SELECT rt AS time, category || '  ' || text AS body, CASE WHEN level ILIKE 'err%' THEN 'error' WHEN level ILIKE 'warn%' THEN 'warning' ELSE 'info' END AS level FROM events WHERE run = {RUN} AND $__timeFilter(rt) AND (${{search:sqlstring}} = '' OR text ILIKE '%' || ${{search:sqlstring}} || '%') ORDER BY rt LIMIT 5000",
                     desc="Filter with the 'console search' box at the top; the time range applies (widen it to see the boot and load)."), 24, 14)

    L.row("Counters")
    L.add(table_panel("Counters (pzopt-bench.out, bake / batch counters, chunk stats)", f"SELECT grp AS group, key, value FROM counters WHERE run = {RUN} ORDER BY grp, key",
                      overrides=[ov("group", width=220)]), 24, 12)

    variables = [var_query("run", "run", "SELECT run FROM runs ORDER BY started DESC"),
                 var_query("source", "profiler", f"SELECT source FROM (SELECT DISTINCT source FROM stacks WHERE run = {RUN}) s ORDER BY CASE source WHEN 'game' THEN 0 WHEN 'asprof' THEN 1 WHEN 'jfr' THEN 2 ELSE 3 END"),
                 var_query("thread", "threads", f"SELECT DISTINCT thread FROM stacks WHERE run = {RUN} AND source = ${{source:sqlstring}} ORDER BY 1",
                           multi=True, include_all=True, current={"text": "All", "value": "$__all"}),
                 {"type": "textbox", "name": "search", "label": "console search", "query": "", "current": {"text": "", "value": ""}, "hide": 0}]
    annos = [annotation("marks", f"SELECT rt AS time, key AS text FROM series WHERE run = {RUN} AND source = 'mark' AND key NOT LIKE 'route-%' AND $__timeFilter(rt)", "#FADE2A"),
             annotation("route", f"SELECT {RT0} AS time, 'route start' AS text UNION ALL SELECT {RT0} + make_interval(secs => route_seconds), 'route end' FROM runs WHERE run = {RUN} AND route_seconds IS NOT NULL", "#73BF69")]
    links = [{"title": "Compare this run", "type": "link", "url": "/d/pzopt-compare/compare?var-runs=${run}&var-base=${run}", "icon": "dashboard"}]
    return dashboard("pzopt-run", "PZ run", L, variables, time=(ROUTE_FROM, ROUTE_TO), tz="utc", annotations=annos, links=links,
                     desc="One run on the route-time axis (00:00:00 = route start)")


# ------------------------------------------------------------------ Compare

METRICS = [("fps_mean", "fps", "none", True), ("fps_1pct_low", "1% low", "none", True), ("p50_ms", "p50", "ms", False), ("p99_ms", "p99", "ms", False),
           ("p99_9_ms", "p99.9", "ms", False), ("max_ms", "max", "ms", False), ("over_33ms", ">33 ms", "none", False), ("jitter_ms", "jitter", "ms", False),
           ("gpu_ms_mean", "GPU ms", "ms", False), ("cpu_pct", "CPU %", "percent", None), ("busiest_core_pct", "busiest core %", "percent", None),
           ("gpu_pct", "GPU %", "percent", None), ("game_thread_pct", "game thread %", "percent", None), ("chunk_p99_ms", "chunk p99", "ms", False),
           ("gc_events", "GC pauses", "none", False), ("total_w", "power W", "watt", False), ("j_per_frame", "J/frame", "joule", False)]


def compare_dashboard():
    L = Layout()
    cols = ", ".join(f"{c} AS \"{n}\"" for c, n, _, _ in METRICS)
    L.add(table_panel("Runs", f"SELECT run, variant, machine, mode, preset, verdict, {cols} FROM runs WHERE run IN ({RUNS}) ORDER BY started",
                      overrides=[ov("run", link=[("Run dashboard", "/d/pzopt-run/run?var-run=${__value.raw}")], width=300),
                                 *[ov(n, unit=u, decimals=2) for _, n, u, _ in METRICS]]), 24, 7)
    deltas = ", ".join(f"round((100.0 * (r.{c} - b.{c}) / nullif(b.{c}, 0))::numeric, 1) AS \"{n}\"" for c, n, _, _ in METRICS)
    L.add(table_panel(
        "Change vs the base run (%)", f"SELECT r.run, {deltas} FROM runs r, runs b WHERE r.run IN ({RUNS}) AND b.run = ${{base:sqlstring}} AND r.run <> b.run ORDER BY r.started",
        desc="Green = better: higher fps / 1% low, lower frame times, spikes, jitter, latency. Utilization is left uncoloured (higher is better only below the cap).",
        overrides=[ov("run", width=300),
                   *[ov(n, unit="percent", color_cell=True, thresholds=([("red", None), ("text", -2), ("green", 2)] if better_up else [("green", None), ("text", -2), ("red", 2)]))
                     for _, n, _, better_up in METRICS if better_up is not None]]), 24, 6)
    per_sec = lambda expr, tbl="frames": f"SELECT date_trunc('second', rt) AS time, run AS metric, {expr} AS value FROM {tbl} WHERE run IN ({RUNS}) AND $__timeFilter(rt) GROUP BY 1, 2 ORDER BY 1"  # noqa: E731
    L.row("Per second on the route-time axis")
    L.add(ts_panel("p99 frame time per second", [q(per_sec("percentile_cont(0.99) WITHIN GROUP (ORDER BY ms)"))], log=True,
                   thresholds=[("transparent", None), ("orange", 10), ("red", 16.7)]), 12, 9)
    L.add(ts_panel("Worst frame per second", [q(per_sec("max(ms)"))], log=True, thresholds=[("transparent", None), ("red", 33.3)]), 12, 9)
    L.add(ts_panel("fps per second", [q(per_sec("count(*) / nullif(sum(ms) / 1000, 0)"))], unit="none", thresholds=[("transparent", None), ("green", 240)]), 12, 9)
    L.add(ts_panel("GPU time per frame (per-second mean)", [q(per_sec("avg(gpu_ms)", "overlay"))]), 12, 9)
    L.add(ts_panel("GPU busy (sysmon)", [q(per_sec("avg(gpu_pct)", "sysmon"))], unit="percent", minv=0, maxv=100), 12, 9)
    L.add(ts_panel("Busiest core (sysmon)", [q(per_sec("avg(busiest_core_pct)", "sysmon"))], unit="percent", minv=0, maxv=100), 12, 9)
    L.add(ts_panel("Game thread load (overlay)", [q(per_sec("avg(game_load)", "overlay"))], unit="percent", minv=0, maxv=100), 12, 9)
    L.add(ts_panel("Chunk latency p99 per second", [q(per_sec("percentile_cont(0.99) WITHIN GROUP (ORDER BY total_ms)", "chunks"))], log=True), 12, 9)
    L.add(ts_panel("Power, whole machine (sysmon total)", [q(per_sec("avg(total_w)", "sysmon"))], unit="watt", minv=0,
                   desc="battery on battery, else CPU package / APU socket + discrete GPU; runs without a CPU reading have no line (see GPU power)"), 12, 9)
    L.add(ts_panel("GPU power (sysmon)", [q(per_sec("avg(gpu_w)", "sysmon"))], unit="watt", minv=0), 12, 9)
    L.add(ts_panel("CPU package power (sysmon, RAPL)", [q(per_sec("avg(cpu_w)", "sysmon"))], unit="watt", minv=0), 12, 9)
    L.add(ts_panel("Power in game / macpower (total)", [q(per_sec("avg(total_w)", "power"))], unit="watt", minv=0,
                   desc="pzopt-power.out (pzopt.Power) or the Mac's power.csv (system_w)"), 12, 9)
    L.row("Distributions over the route")
    L.add({"type": "barchart", "title": "Frame-time percentiles", "datasource": DS, "targets": [q(f"""
SELECT p.label AS percentile, x.run, percentile_cont(p.q) WITHIN GROUP (ORDER BY x.ms) AS ms
FROM frames x, (VALUES (1, 'p50', 0.5), (2, 'p90', 0.9), (3, 'p99', 0.99), (4, 'p99.9', 0.999), (5, 'max', 1.0)) AS p(i, label, q)
WHERE x.run IN ({RUNS}) AND {IN_ROUTE} GROUP BY p.i, p.label, p.q, x.run ORDER BY p.i""", "table")],
           "transformations": [{"id": "groupingToMatrix", "options": {"rowField": "percentile", "columnField": "run", "valueField": "ms", "emptyValue": "null"}}],
           "fieldConfig": {"defaults": {"unit": "ms", "custom": {"fillOpacity": 80, "scaleDistribution": {"type": "log", "log": 2}}, "color": {"mode": "palette-classic"}}, "overrides": []},
           "options": {"xField": "percentile\\run", "orientation": "vertical", "groupWidth": 0.8, "barWidth": 0.95, "showValue": "auto",
                       "legend": {"showLegend": True, "placement": "bottom", "displayMode": "list"}, "tooltip": {"mode": "multi"}}}, 12, 10)
    L.add(table_panel("Game-thread phases over the route (% of samples)", f"""
SELECT x.name AS phase, x.run, 100.0 * sum(x.samples) / nullif(max(t.total), 0) AS share
FROM gamethread x JOIN (SELECT run, sum(samples) AS total FROM gamethread y WHERE y.run IN ({RUNS}) AND y.kind = 'total'
  AND y.rt >= {RT0} AND y.rt <= {RT0} + make_interval(secs => coalesce((SELECT route_seconds FROM runs r WHERE r.run = y.run), 1e9)) GROUP BY run) t USING (run)
WHERE x.run IN ({RUNS}) AND x.kind = 'p' AND {IN_ROUTE} GROUP BY x.name, x.run ORDER BY 1""",
        transformations=[{"id": "groupingToMatrix", "options": {"rowField": "phase", "columnField": "run", "valueField": "share", "emptyValue": "null"}}],
        unit="percent", overrides=[ov("phase\\run", width=220, unit="none")]), 12, 10)
    L.row("Profiler: diff flame graph (base run vs the other run; drag across a time panel to narrow both)")
    both = "source = ${source:sqlstring} AND $__timeFilter(rt)"
    L.add(flame_panel("Diff flame graph: $base (baseline) vs $other, $source",
                      flame_diff_sql(f"run = ${{base:sqlstring}} AND {both}", f"run = ${{other:sqlstring}} AND {both}"),
                      desc="Red = a bigger share of the samples in the other run, green = smaller; shares, so runs of different length compare."), 24, 22)
    variables = [
        var_query("runs", "runs", "SELECT run FROM runs ORDER BY started DESC", multi=True),
        var_query("base", "base run", "SELECT run FROM runs WHERE run IN (${runs:sqlstring}) ORDER BY started"),
        var_query("other", "vs run", "SELECT run FROM runs WHERE run IN (${runs:sqlstring}) AND run <> ${base:sqlstring} ORDER BY started"),
        custom_var("source", "profiler", ["game", "asprof", "jfr", "lua"], "game"),
    ]
    annos = [annotation("route end", f"SELECT {RT0} + make_interval(secs => route_seconds) AS time, run || ' route end' AS text FROM runs WHERE run IN ({RUNS}) AND route_seconds IS NOT NULL", "#73BF69")]
    return dashboard("pzopt-compare", "PZ compare", L, variables, time=(ROUTE_FROM, ROUTE_TO), tz="utc", annotations=annos,
                     desc="Several runs on one route-time axis (00:00:00 = route start), deltas against a base run")


# ------------------------------------------------------------------ Live

def live_dashboard():
    L = Layout()
    last5 = "t > now() - interval '5 seconds'"
    L.add(stat_panel("Now", "SELECT v AS run, extract(epoch FROM now() - t)::int AS \"s since its sysmon started\" FROM live_state WHERE k = 'run'", color_mode="none", value_size=16), 6, 4)
    L.add(stat_panel("Last 5 s", f"""
SELECT count(*) / nullif(sum(ms) / 1000, 0) AS fps, percentile_cont(0.99) WITHIN GROUP (ORDER BY ms) AS "p99 ms",
  percentile_cont(0.999) WITHIN GROUP (ORDER BY ms) AS "p99.9 ms", max(ms) AS "max ms", stddev(ms) AS "stdev ms"
FROM live_overlay WHERE {last5}""", decimals=2, color_mode="none"), 10, 4)
    L.add(stat_panel("Utilization, last 5 s", f"""
SELECT (SELECT avg(gpu_pct) FROM live_sysmon WHERE {last5}) AS GPU, (SELECT avg(busiest_core_pct) FROM live_sysmon WHERE {last5}) AS "busiest core",
  (SELECT avg(game_load) FROM live_overlay WHERE {last5}) AS "game thread", (SELECT avg(render_load) FROM live_overlay WHERE {last5}) AS "render thread" """,
                     unit="percent", thr=UTIL_STEPS, decimals=0), 8, 4)
    L.add(ts_panel("Frame time", [q("SELECT t AS time, ms AS presented, gpu_ms AS GPU FROM live_overlay WHERE $__timeFilter(t) ORDER BY 1"),
                                  q("SELECT t AS time, ms AS \"game frame\" FROM live_frames WHERE $__timeFilter(t) ORDER BY 1", ref="B")],
                   log=True, thresholds=[("transparent", None), ("green", 4.167), ("orange", 10), ("red", 16.7)],
                   desc="presented + GPU: pzopt-overlay.out (flushed every second); game frame: pzopt-frames.out (5 s blocks)",
                   overrides=[ov("GPU", color="purple")]), 24, 10)
    L.add(ts_panel("fps per second", [q("SELECT date_trunc('second', t) AS time, count(*) / nullif(sum(ms) / 1000, 0) AS fps, percentile_cont(0.99) WITHIN GROUP (ORDER BY ms) AS \"p99 ms\" FROM live_overlay WHERE $__timeFilter(t) GROUP BY 1 ORDER BY 1")],
                   unit="none", overrides=[ov("p99 ms", unit="ms", axis="right")]), 12, 8)
    L.add(ts_panel("Utilization", [q("SELECT t AS time, cpu_pct AS CPU, busiest_core_pct AS \"busiest core\", gpu_pct AS GPU FROM live_sysmon WHERE $__timeFilter(t) ORDER BY 1"),
                                   q("SELECT date_trunc('second', t) AS time, avg(game_load) AS \"game thread\", avg(render_load) AS \"render thread\" FROM live_overlay WHERE $__timeFilter(t) GROUP BY 1 ORDER BY 1", ref="B")],
                   unit="percent", minv=0, maxv=100), 12, 8)
    L.add(ts_panel("Game thread phases", [q("SELECT t AS time, name AS metric, share * 100 AS value FROM live_gamethread WHERE kind = 'p' AND $__timeFilter(t) ORDER BY 1")],
                   unit="percent", stack=True, minv=0, maxv=100, legend="right"), 16, 9)
    L.add(table_panel("Hot methods, last 10 s", """
SELECT name, 100.0 * sum(samples) / nullif((SELECT sum(samples) FROM live_gamethread WHERE kind = 'total' AND t > now() - interval '10 seconds'), 0) AS share
FROM live_gamethread WHERE kind = 'l' AND t > now() - interval '10 seconds' GROUP BY name ORDER BY 2 DESC LIMIT 20""",
                      overrides=[ov("share", unit="percent", decimals=1, gauge=True, min=0, max=25)]), 8, 9)
    L.add(ts_panel("GPU power / clocks / VRAM", [q("SELECT t AS time, gpu_w AS \"GPU W\", gpu_sm_mhz AS \"SM MHz\", vram_mib AS \"VRAM MiB\" FROM live_sysmon WHERE $__timeFilter(t) ORDER BY 1")],
                   unit="none", overrides=[ov("GPU W", unit="watt"), ov("SM MHz", axis="right"), ov("VRAM MiB", unit="mbytes", axis="right")]), 24, 7)
    L.add(stat_panel("Power, last 5 s", f"""
SELECT coalesce((SELECT avg(total_w) FROM live_sysmon WHERE {last5}), (SELECT avg(total_w) FROM live_power WHERE {last5})) AS "total W",
  coalesce((SELECT avg(cpu_w) FROM live_sysmon WHERE {last5}), (SELECT avg(cpu_w) FROM live_power WHERE {last5})) AS "CPU W",
  coalesce((SELECT avg(gpu_w) FROM live_sysmon WHERE {last5}), (SELECT avg(gpu_w) FROM live_power WHERE {last5})) AS "GPU W",
  (SELECT avg(total_w) / nullif(avg(fps), 0) FROM live_power WHERE {last5}) AS "J/frame" """, decimals=2, color_mode="none",
                     desc="sysmon while a harness run is going, else the game's own sampler (pzopt-power.out, written while the overlay's frame log is on)"), 6, 8)
    L.add(ts_panel("Power", [q("SELECT t AS time, total_w AS \"total (sysmon)\", cpu_w AS \"CPU (sysmon)\", soc_w AS \"APU socket (sysmon)\", gpu_w AS \"GPU (sysmon)\", bat_w AS \"battery (sysmon)\" FROM live_sysmon WHERE $__timeFilter(t) ORDER BY 1"),
                             q("SELECT t AS time, total_w AS \"total (game)\", cpu_w AS \"CPU (game)\", soc_w AS \"APU socket (game)\", gpu_w AS \"GPU (game)\", bat_w AS \"battery (game)\" FROM live_power WHERE $__timeFilter(t) ORDER BY 1", ref="B")],
                   unit="watt", minv=0), 18, 8)
    L.add(flame_panel("Game thread flame graph, last 10 s", flame_sql("false").replace(
        "SELECT stack_id, sum(samples) AS c FROM stacks WHERE false GROUP BY 1",
        "SELECT stack_id, sum(samples) AS c FROM live_stacks WHERE t > now() - interval '10 seconds' GROUP BY 1")), 24, 18)
    L.add(state_panel("Inputs: keys and buttons", f"SELECT t AS time, device || ' ' || control AS metric, value FROM live_inputs WHERE {BUTTONS} AND $__timeFilter(t) ORDER BY 1"), 16, 8)
    L.add(table_panel("Inputs, last 30 s", "SELECT to_char(t, 'HH24:MI:SS.MS') AS at, device, control, value FROM live_inputs WHERE t > now() - interval '30 seconds' ORDER BY t DESC LIMIT 200"), 8, 8)
    return dashboard("pzopt-live", "PZ live", L, time=("now-2m", "now"), refresh="1s",
                     desc="The running game: harness/grafana/ingest.py --follow tails ~/Zomboid/pzopt-*.out and the run's sysmon.csv")


def main():
    OUT.mkdir(exist_ok=True)
    for d in (runs_dashboard(), run_dashboard(), compare_dashboard(), live_dashboard()):
        (OUT / f"{d['uid']}.json").write_text(json.dumps(d, indent=1) + "\n")
    print(f"dashboards: {', '.join(sorted(p.name for p in OUT.glob('*.json')))}")


if __name__ == "__main__":
    main()
