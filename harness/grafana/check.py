#!/usr/bin/env python3
"""Run every query of the provisioned dashboards through Grafana's /api/ds/query and report errors / row counts.

  harness/grafana/check.py [--run R] [--runs A,B] [--base A] [--other B] [--source game] [--errors-only]

Grafana expands the $__timeFilter macros on the server; the dashboard variables are the frontend's job, so this
script substitutes them: by default the newest run, the two newest runs (base = the older), the profiler source
'game' and every thread. Run it after editing dashboards.py.
"""
import argparse
import base64
import json
import os
import pathlib
import subprocess
import sys
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
URL = "http://127.0.0.1:3000/api/ds/query"
ROUTE = ("946684770000", "946684920000")  # the route-time dashboards' range
RANGES = {"pzopt-runs": ("now-30d", "now"), "pzopt-live": ("now-10m", "now")}
for k, v in (("PGHOST", "127.0.0.1"), ("PGPORT", "5433"), ("PGUSER", "pzopt"), ("PGPASSWORD", "pzopt"), ("PGDATABASE", "pzopt")):
    os.environ.setdefault(k, v)


def sql_list(values):
    return ",".join("'" + v.replace("'", "''") + "'" for v in values)


def db(sql):
    out = subprocess.run(["psql", "-X", "-At", "-c", sql], capture_output=True, text=True, check=True).stdout
    return [l for l in out.splitlines() if l]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run")
    ap.add_argument("--runs")
    ap.add_argument("--base")
    ap.add_argument("--other")
    ap.add_argument("--source", default="game")
    ap.add_argument("--errors-only", action="store_true")
    a = ap.parse_args()
    newest = db("SELECT run FROM runs ORDER BY started DESC LIMIT 2")
    run = a.run or (newest[0] if newest else "none")
    runs = a.runs.split(",") if a.runs else list(reversed(newest))
    base = a.base or runs[0]
    other = a.other or next((r for r in runs if r != base), base)
    threads = db(f"SELECT DISTINCT thread FROM stacks WHERE run = {sql_list([run])} AND source = {sql_list([a.source])}") or ["MainThread"]
    subs = {"${run:sqlstring}": sql_list([run]), "${runs:sqlstring}": sql_list(runs), "${base:sqlstring}": sql_list([base]),
            "${other:sqlstring}": sql_list([other]), "${source:sqlstring}": sql_list([a.source]), "${thread:sqlstring}": sql_list(threads),
            "${machine:sqlstring}": sql_list(db("SELECT DISTINCT machine FROM runs") or ["desktop"]),
            "${mode:sqlstring}": sql_list(db("SELECT DISTINCT coalesce(mode, '') FROM runs") or [""]),
            "${label:sqlstring}": "'.*'", "${search:sqlstring}": "''"}
    pw = (pathlib.Path.home() / ".config/pzopt/grafana-admin").read_text().strip()
    auth = "Basic " + base64.b64encode(f"admin:{pw}".encode()).decode()
    print(f"run={run} runs={runs} base={base} other={other} source={a.source}")
    errors = 0
    for path in sorted((HERE / "dashboards").glob("*.json")):
        d = json.loads(path.read_text())
        frm, to = RANGES.get(d["uid"], ROUTE)
        items = [(p["title"], t) for p in d["panels"] for t in p.get("targets", [])]
        items += [("annotation " + x["name"], x["target"]) for x in d["annotations"]["list"] if "target" in x]
        items += [("variable " + v["name"], {"rawSql": v["query"], "format": "table"}) for v in d["templating"]["list"] if v["type"] == "query"]
        for title, t in items:
            sql = t["rawSql"]
            for k, v in subs.items():
                sql = sql.replace(k, v)
            body = {"queries": [{"refId": "A", "datasource": {"uid": "pzopt-pg"}, "rawSql": sql, "format": t.get("format", "table"), "rawQuery": True}],
                    "from": frm, "to": to}
            req = urllib.request.Request(URL, json.dumps(body).encode(), {"Content-Type": "application/json", "Authorization": auth})
            try:
                res = json.load(urllib.request.urlopen(req, timeout=120))
            except urllib.error.HTTPError as e:
                res = json.load(e)
            r = res.get("results", {}).get("A", {})
            if "error" in r:
                errors += 1
                print(f"{d['uid']:14} {title[:58]:58} ERROR {r['error'][:300]}")
            elif not a.errors_only:
                rows = sum(len(f["data"]["values"][0]) if f["data"]["values"] else 0 for f in r.get("frames", []))
                print(f"{d['uid']:14} {title[:58]:58} {rows} rows")
    print(f"{errors} errors")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
