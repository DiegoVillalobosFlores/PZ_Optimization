#!/usr/bin/env python3
"""One provisioned Grafana dashboard per test machine (dashboards/pzopt-machine-<m>.json); dashboards.py main() writes them.

  pzopt-machine-desktop / -flip / -dell / -mac
    the machine's hardware (and what the newest runs reported), stock vs optimized on that machine (the home page's
    hero with its machine filter swapped), each day's build on it, then its runs: table, trends, hardware use per run,
    the objective's "below the cap with hardware left over".

Built from the panels of dashboards.py (hero(), runs_dashboard()) so a change there shows here too: the machine is a
hidden constant variable (`machine`, and `hmachine` for the daily chart) instead of a picker.
"""
import copy
import json

import dashboards as D

# lscpu / lspci / sysctl of each machine, 2026-09-24
MACHINES = {
    "desktop": ("Desktop", "AMD Ryzen 7 9800X3D (8 cores) · NVIDIA GeForce RTX 4090 · 30 GB · CachyOS · 5120×2160 at 240 Hz · "
                "NVIDIA OpenGL (XWayland)"),
    "flip": ("Flip (laptop)", "AMD Ryzen AI 9 HX 370 (Zen 5 + Zen 5c cores) · Radeon 890M integrated GPU, Mesa OpenGL · "
             "1920×1080 · runs in the balanced power profile, launched direct"),
    "dell": ("Dell (laptop)", "Intel Core i5-6300HQ (4 cores) · NVIDIA GeForce GTX 960M through PRIME offload (the Intel HD 530 "
             "drives the panel) · 1920×1080 · runs launched direct, never through Steam"),
    "mac": ("MacBook Pro", "Apple M1 Pro · 16 GB · macOS · Apple OpenGL 2.1 over Metal (no persistent buffers) · run-mac.sh, "
            "launched direct"),
}


def const_var(name, value):
    return {"type": "constant", "name": name, "label": name, "query": value, "hide": 2,
            "current": {"text": value, "value": value}, "options": [{"text": value, "value": value, "selected": True}]}


def panels_of(build):
    """The panels a dashboards.py layout function adds, in order."""
    L = D.Layout()
    build(L)
    return L.panels


def retarget(panel, m, title=None):
    """A copy of a desktop-only panel with its machine filter set to m."""
    p = json.loads(json.dumps(panel).replace("machine = 'desktop'", f"machine = '{m}'").replace("(desktop)", f"({m})"))
    if title:
        p["title"] = title
    return p


def machine_dashboard(m):
    name, hardware = MACHINES[m]
    L = D.Layout()
    others = " · ".join(f"[{MACHINES[o][0]}](/d/pzopt-machine-{o})" for o in MACHINES if o != m)
    L.add({"type": "text", "title": "", "transparent": True, "options": {"mode": "markdown", "content": (
        f"## {name}\n{hardware}\n\nOther machines: {others} · [every run](/d/pzopt-runs)")}}, 24, 4)

    mine = f"machine = '{m}'"
    L.add(D.stat_panel("Runs (time range)", color_mode="none", sql=f"""
SELECT count(*) AS runs, count(*) FILTER (WHERE valid) AS valid, count(*) FILTER (WHERE NOT enabled) AS stock,
  count(DISTINCT label) AS labels FROM runs WHERE {mine} AND $__timeFilter(started)"""), 6, 4)
    L.add(D.stat_panel("Last run", f"SELECT extract(epoch FROM max(started)) * 1000 AS \"last run\" FROM runs WHERE {mine}",
                       unit="dateTimeFromNow",
                       color_mode="none", desc="The newest run of this machine in the database, any time range."), 4, 4)
    L.add(D.table_panel("What the newest runs reported", f"""
SELECT DISTINCT ON (resolution, opengl, gpu, jvm, launcher) resolution, gpu, opengl, jvm, launcher, max(started) OVER
  (PARTITION BY resolution, opengl, gpu, jvm, launcher) AS "last seen"
FROM runs WHERE {mine} AND $__timeFilter(started) ORDER BY resolution, opengl, gpu, jvm, launcher""",
                        desc="Each distinct setup the console reported (desktop resolution, GPU, OpenGL renderer, JVM, launcher). "
                             "Compare runs only within one row.",
                        overrides=[D.ov("last seen", unit="dateTimeAsIso", width=160), D.ov("resolution", width=110)]), 14, 4)

    # stock vs optimized on this machine: the home page's hero, its desktop filter swapped (a tree without hero() skips it)
    hero = {p.get("title"): p for p in panels_of(D.hero)} if hasattr(D, "hero") else {}
    if hero:
        L.row(f"Stock vs PZ Optimization on the {m}")
        for title, w, h in (("Stock game", 10, 7), ("Faster than stock", 4, 7), ("With PZ Optimization", 10, 7)):
            L.add(retarget(hero[title], m), w, h)
        # the daily charts (fps per benchmark, then boot and load time), retitled for the machine
        for t, p in hero.items():
            if t and t.startswith("Each day's build"):
                L.add(retarget(p, m, t.replace("${hmachine}", m)), 24, 11 if "fps" in t else 8)
        L.add(retarget(hero["Every scene measured both ways (desktop)"], m, f"Every scene measured both ways ({m})"), 24, 6)

    # the runs part of the home page, restricted by the hidden machine constant
    runs = D.runs_dashboard()
    start = next((i for i, p in enumerate(runs["panels"]) if p.get("type") == "row" and p["title"] == "All runs"), 0)
    for p in runs["panels"][start:]:
        if p.get("type") == "row":
            L.row(p["title"])
        else:
            L.add(copy.deepcopy(p), p["gridPos"]["w"], p["gridPos"]["h"])

    L.row("Hardware use (one point per run, series = label or machine)")
    where = (f"$__timeFilter(started) AND {mine} AND coalesce(mode, '') IN (${{mode:sqlstring}}) AND label ~ ${{label:sqlstring}}")
    trend = lambda col: D.trend_sql(where, col)  # noqa: E731
    pts = dict(points=True, point_size=7, legend="right")
    L.add(D.ts_panel("Game thread busy (route mean)", [D.q(trend("game_thread_pct"))], unit="percent", minv=0, maxv=100, **pts,
                     desc="pzopt-threads.out: the game thread's CPU share; ≥ 90 % means the game thread is the wall."), 12, 8)
    L.add(D.ts_panel("Cores used by the game process", [D.q(trend("process_cores"))], unit="none", minv=0, **pts,
                     desc="The game process's CPU time over the route in cores (sysmon)."), 12, 8)
    L.add(D.ts_panel("GPU temperature", [D.q(trend("gpu_c"))], unit="celsius", **pts,
                     desc="sysmon's GPU temperature, route mean (empty where the machine has no reading)."), 12, 8)
    L.add(D.ts_panel("GPU power", [D.q(trend("gpu_w"))], unit="watt", minv=0, **pts,
                     desc="sysmon's GPU board power, route mean (discrete GPUs; the Mac's from IOReport)."), 12, 8)
    L.add(D.ts_panel("VRAM in use", [D.q(trend("vram_mib"))], unit="mbytes", minv=0, **pts), 12, 8)
    L.add(D.ts_panel("Chunks streamed per second", [D.q(trend("chunks_per_s"))], unit="none", minv=0, **pts), 12, 8)

    variables = [
        const_var("machine", m),
        D.var_query("mode", "mode", f"SELECT DISTINCT coalesce(mode, '') FROM runs WHERE {mine} ORDER BY 1", multi=True,
                    include_all=True, current={"text": "All", "value": "$__all"}),
        {"type": "textbox", "name": "label", "label": "label regex", "query": ".*", "current": {"text": ".*", "value": ".*"}, "hide": 0},
        const_var("hmachine", m),
    ]
    d = D.dashboard(f"pzopt-machine-{m}", f"PZ machine: {name}", L, variables,
                    desc=f"Every harness run of the {m} imported by harness/grafana/ingest.py, stock vs optimized there, its hardware")
    d["tags"] = ["pzopt", "machine"]
    return d


def machine_dashboards():
    return [machine_dashboard(m) for m in MACHINES]


if __name__ == "__main__":
    D.OUT.mkdir(exist_ok=True)
    for d in machine_dashboards():
        (D.OUT / f"{d['uid']}.json").write_text(json.dumps(d, indent=1) + "\n")
        print(d["uid"])
