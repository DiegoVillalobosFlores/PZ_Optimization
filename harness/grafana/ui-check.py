#!/usr/bin/env python3
"""Is every dashboard element displayed correctly in Chrome? Headless Chrome renders each dashboard, Jev judges each panel.

  harness/grafana/ui-check.py                         # the public site, home page at 1920x1080, 1366x768 and 412x915
  harness/grafana/ui-check.py --base http://127.0.0.1:3000 --all   # local Grafana, every dashboard
  harness/grafana/ui-check.py --only pzopt-runs --sizes 1920x1080 --out /tmp/ui-check

ui-check.mjs (Node's built-in WebSocket, Chrome DevTools protocol) opens each page in google-chrome-stable --headless at
the screen width with a tall window, so every panel renders, and extracts per panel: its visible text, "No data" / error
markers, whether each canvas drew anything, text the browser clips, overlaps with other panels, the legend; on the home
page it also moves the pointer across the daily chart and records the tooltip. Jev (typesafe_client, text only) gets
each panel's facts with its title, type and description from the generated dashboard JSON and answers ok /
no_data / error / clipped / blank_chart / overlap / wrong_content; a page-level question covers horizontal scrolling and
the hover. Screenshots and report.json go to --out. Exit 0 when Jev passes everything.
"""
import argparse
import concurrent.futures as cf
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from typesafe_client import ask, choice, noul  # noqa: E402

DASHBOARDS = {"pzopt-runs": "/d/pzopt-runs/pz-runs", "pzopt-run": "/d/pzopt-run/run", "pzopt-compare": "/d/pzopt-compare/compare",
              "pzopt-live": "/d/pzopt-live/live"}
HOVER = {"pzopt-runs": "Each day's build on the desktop: fps"}
CONTEXT = ("A public, read-only Grafana dashboard of a Project Zomboid performance mod (PZ Optimization), seen by anonymous "
           "visitors in Chrome. Panels read a Postgres database of benchmark runs. Expected emptiness is fine: the live "
           "dashboard only has data while a game is running; profiler flame graphs and some per-run panels (JFR, "
           "async-profiler, schedmon, pacing, drive telemetry, inputs, power) are empty for runs recorded without that log; "
           "a stat panel may legitimately show a single value; row panels (type row) are section headers with no body. "
           "By Grafana's design a flame graph shortens frame names that do not fit their box (full name on hover) and a "
           "table shortens long cell text with an ellipsis (every table has cell inspect for the full value); only a "
           "clipped header, stat value or a public hero/table column a visitor must read counts as clipped. The run "
           "dashboard's Verdict panel shows Jev's own verdict of that run; invalid is a legitimate verdict. Trend panels of "
           "the runs dashboard put one series per run label in the legend. A stat showing – has no value for that run: the "
           "run lacks that sensor or log (Mac and laptop runs have no GPU or power telemetry), which is correct.")

PANEL_Q = choice(
    "Judge whether this one dashboard panel is displayed correctly for a visitor, from the rendered facts in `panel` "
    "(what Chrome drew: its text, markers, canvases, clipped text, overlaps) against `spec` (the panel's title, type and "
    "description: what it is meant to show) and `page` (the dashboard, the screen width, whether a game is running). "
    "Canvases with drawn_pixels 0 draw nothing; charts (timeseries, trend, flamegraph, state-timeline) need drawn pixels "
    "unless they show No data. Stat and table panels show their values as text.",
    {
        "ok": "Displayed correctly: the content matches the spec, values and labels are readable, nothing is cut off or "
              "overlapping; or it is legitimately empty per the context (No data where the log does not exist).",
        "no_data": "Shows No data (or nothing) although the spec and context say it should have data here.",
        "error": "Shows a query or rendering error.",
        "clipped": "A value, label or title a visitor needs is cut off or overflows (clipped list), so it cannot be read.",
        "blank_chart": "A chart panel reports data but its canvas draws nothing, or draws only an empty frame.",
        "overlap": "The panel overlaps another panel or runs off the page.",
        "wrong_content": "What it shows does not match what the title / description promise (wrong units, wrong series, "
                         "nonsense values, raw template text such as ${var}).",
    })
PAGE_Q = {
    "layout_ok": noul("Is the page layout sound for this screen width: no horizontal scrolling (`horizontal_scroll` false), "
                      "no panels off the page, and the variables bar present?"),
    "hover_ok": noul("If `hover` samples exist (the pointer moved across the daily chart), does each tooltip show a date, "
                     "fps values of the benchmark lines measured that day (a bench only has values from the first day its "
                     "harness could run it; the stock lines repeat every day; boot and load times have their own chart) and the release notes "
                     "rows (build, bullet notes), fit the screen (fits_width and fits_screen_height true) and have no "
                     "clipped_rows? Answer yes when there is no hover to judge."),
}


def panel_specs(uid):
    d = json.loads((HERE / "dashboards" / f"{uid}.json").read_text())
    specs = []

    def walk(ps):
        for p in ps:
            specs.append({"title": p.get("title", ""), "type": p.get("type"), "description": (p.get("description") or "")[:600]})
            walk(p.get("panels", []))
    walk(d["panels"])
    return specs


def spec_for(specs, title):
    for s in specs:
        # a title with a variable: everything before the first ${...} must match, the rest may be anything
        pat = "^" + re.sub(r"\\\$\\\{[^}]*\\\}", ".*", re.escape(s["title"])) + "$"
        if s["title"] and (s["title"] == title or re.match(pat, title)):
            return s
    return {"title": title, "type": "unknown", "description": ""}


def start_chrome():
    prof = tempfile.mkdtemp(prefix="pzopt-uicheck-")
    exe = shutil.which("google-chrome-stable") or shutil.which("chromium")
    p = subprocess.Popen([exe, "--headless=new", "--remote-debugging-port=0", f"--user-data-dir={prof}", "--no-first-run",
                          "--hide-scrollbars", "--disable-gpu-sandbox", "about:blank"], stderr=subprocess.PIPE, text=True)
    for _ in range(200):
        line = p.stderr.readline()
        m = re.search(r"ws://127\.0\.0\.1:(\d+)/", line)
        if m:
            return p, int(m.group(1)), prof
    p.kill()
    sys.exit("chrome did not print its DevTools port")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--base", default="https://pzo.diegov.dev")
    ap.add_argument("--all", action="store_true", help="every dashboard, not only the home page")
    ap.add_argument("--only", action="append", help="dashboard uid (repeatable)")
    ap.add_argument("--sizes", default="1920x1080,1366x768,412x915", help="screen widths x heights (the window is made tall)")
    ap.add_argument("--vars", default="", help="extra URL query, e.g. var-hmachine=flip")
    ap.add_argument("--out", default="/tmp/pzopt-ui-check")
    a = ap.parse_args()
    uids = a.only or (list(DASHBOARDS) if a.all else ["pzopt-runs"])
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    game_running = subprocess.run(["pgrep", "-f", "[P]rojectZomboid64"], capture_output=True).returncode == 0
    chrome, port, prof = start_chrome()
    report, failures = [], 0
    try:
        for uid in uids:
            specs = panel_specs(uid)
            for size in a.sizes.split(","):
                w, h = map(int, size.split("x"))
                url = f"{a.base}{DASHBOARDS[uid]}?orgId=1&kiosk" + (f"&{a.vars}" if a.vars else "")
                shot = out / f"{uid}-{w}x{h}.png"
                r = subprocess.run(["node", str(HERE / "ui-check.mjs"), str(port), url, str(w), str(max(h, 6000) if uid != "pzopt-run" else 16000),
                                    str(shot), HOVER.get(uid, ""), str(h)], capture_output=True, text=True, timeout=240)
                if r.returncode != 0 or not r.stdout.strip():
                    print(f"{uid} {size}: page failed: {r.stderr.strip()[-300:]}")
                    failures += 1
                    continue
                facts = json.loads(r.stdout)
                page = {"dashboard": uid, "screen": size, "game_running": game_running, "title": facts["title"],
                        "horizontal_scroll": facts["horizontal_scroll"], "page_width": facts["page_width"]}

                def judge(p):
                    spec = spec_for(specs, p["title"])
                    ans = ask({"context": CONTEXT, "page": page, "spec": spec, "panel": p}, {"verdict": PANEL_Q})["verdict"]
                    return p, spec, ans
                with cf.ThreadPoolExecutor(6) as ex:
                    judged = list(ex.map(judge, facts["panels"]))
                page_ans = ask({"context": CONTEXT, "page": page, "variables": facts.get("variables"),
                                "panels_off_page": [p["title"] for p in facts["panels"] if p["off_page"]],
                                "hover": facts.get("hover"), "hover_missing": facts.get("hover_missing")}, PAGE_Q)
                bad = [(p, s, v) for p, s, v in judged if v["choice"] != "ok"]
                page_bad = [k for k, v in page_ans.items() if v["noul"] < 0.5]
                failures += len(bad) + len(page_bad)
                print(f"{uid} {size}: {len(judged)} panels, {len(bad)} flagged; layout {page_ans['layout_ok']['noul']:.2f} "
                      f"hover {page_ans['hover_ok']['noul']:.2f} ({len(facts.get('hover', []))} tooltips) -> {shot}")
                for p, s, v in bad:
                    print(f"   {v['choice']:13s} {v['confidence']:.2f}  {p['title'] or '(untitled)'}: "
                          f"{'clipped ' + str(p['clipped'][:3]) + ' ' if p['clipped'] else ''}{'overlaps ' + str(p['overlaps']) + ' ' if p['overlaps'] else ''}"
                          f"{p['text'][:120]}")
                report.append({"page": page, "page_answers": page_ans, "hover": facts.get("hover"),
                               "panels": [{"facts": p, "spec": s, "verdict": v} for p, s, v in judged]})
    finally:
        chrome.kill()
        shutil.rmtree(prof, ignore_errors=True)
    (out / "report.json").write_text(json.dumps(report, indent=1))
    print(f"report: {out / 'report.json'}; {failures} findings")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
