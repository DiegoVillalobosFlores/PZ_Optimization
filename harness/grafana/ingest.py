#!/usr/bin/env python3
"""Load harness runs into the Grafana metrics DB (harness/grafana/stack.sh up starts it).

  harness/grafana/ingest.py <run-dir or name>...   # (re)import these runs, whatever their date
  harness/grafana/ingest.py --all                  # every run started after harness/grafana/since
  harness/grafana/ingest.py --follow               # the stack's follower: live game + finished runs

Every sample of a run goes in, not only its summary: each game frame (pzopt-frames.out), each presented
frame with GPU time and thread loads (pzopt-overlay.out), sysmon.csv, the game thread's per-second phase /
sub-phase / hot-method / wait shares (pzopt-gamethread.out), every streamed chunk (pzopt-chunks.out), GC
pauses (gc.log), flip intervals (present.txt), VRR state (vrr.txt), the path pilot's telemetry
(pzopt-drive.out), per-thread CPU (pzopt-threads.out), the numeric facts and counters of pzopt-bench.out,
and one `runs` row with analyze.py's route-window summary (the numbers compare.py and judge.py use), the
chunk latency and Jev's verdict from judge.json. Re-importing a run replaces its rows.

Every write also goes to the public dashboard's DB when ~/.config/pzopt/grafana-remote.env exists (harness/grafana/cloud.sh;
failures spool under ~/.local/state/pzopt-grafana/spool/ and replay in order); --no-remote keeps a write local.

--follow tails ~/Zomboid/pzopt-overlay.out, pzopt-frames.out, pzopt-gamethread.out and the running run's
sysmon.csv into the live_* tables once a second (the Live dashboard), and imports every run dir under
harness/runs/ of this checkout and its git worktrees once its files have been quiet for 20 s; a run
re-judged later (judge.json rewritten) is re-imported. Runs started before the date in
harness/grafana/since (the 2026-09-24 archive) are left out of --all and --follow.
"""
import argparse
import csv
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from analyze import power_watts, summarize  # noqa: E402
from compare import latency  # noqa: E402

REPO = HERE.parents[1]
ZOMBOID = Path(os.environ.get("ZOMBOID", Path.home() / "Zomboid"))
SINCE_FILE = HERE / "since"
ROUTE_ORIGIN = 946684800.0  # 2000-01-01 00:00:00 UTC = route start on the route-time axis
QUIET_S = 20
for k, v in (("PGHOST", "127.0.0.1"), ("PGPORT", "5433"), ("PGUSER", "pzopt"), ("PGPASSWORD", "pzopt"), ("PGDATABASE", "pzopt")):
    os.environ.setdefault(k, v)


# ------------------------------------------------------------------ helpers

def ts(epoch_s):
    """timestamptz literal for COPY text format (UTC, microseconds)."""
    if epoch_s is None:
        return None
    s = int(epoch_s // 1)
    us = int(round((epoch_s - s) * 1e6))
    if us >= 1000000:
        s, us = s + 1, us - 1000000
    return time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(s)) + f".{us:06d}+00"


def cell(v):
    if v is None or v == "":
        return r"\N"
    if isinstance(v, bool):
        return "t" if v else "f"
    if isinstance(v, float):
        if v != v or v in (float("inf"), float("-inf")):
            return r"\N"
        return repr(v)
    if isinstance(v, (dict, list)):
        v = json.dumps(v)
    return str(v).replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")


def copy_block(table, cols, rows):
    out = [f"COPY {table} ({', '.join(cols)}) FROM STDIN;\n"]
    for r in rows:
        out.append("\t".join(cell(v) for v in r) + "\n")
    out.append("\\.\n")
    return "".join(out)


def num(v):
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return f if f == f else None


def kv_file(p):
    if not p.exists():
        return {}
    return dict(l.split("=", 1) for l in p.read_text(errors="replace").splitlines() if "=" in l and not l.startswith("#"))


def run_started(name):
    m = re.search(r"(\d{8})-(\d{6})$", name)
    if not m:
        return None
    return datetime.datetime.strptime(m.group(1) + m.group(2), "%Y%m%d%H%M%S").timestamp()


def since_epoch():
    if not SINCE_FILE.exists():
        return 0.0
    txt = SINCE_FILE.read_text().split("#")[0].strip()
    return datetime.datetime.fromisoformat(txt).timestamp() if txt else 0.0


def run_roots():
    roots = [REPO / "harness" / "runs"]
    try:
        out = subprocess.run(["git", "-C", str(REPO), "worktree", "list", "--porcelain"], capture_output=True, text=True, timeout=10).stdout
        for line in out.splitlines():
            if line.startswith("worktree "):
                r = Path(line[9:]) / "harness" / "runs"
                if r not in roots:
                    roots.append(r)
    except (OSError, subprocess.SubprocessError):
        pass
    return [r for r in roots if r.is_dir()]


def psql_run(script, env=None):
    p = subprocess.run(["psql", "-q", "-X", "-v", "ON_ERROR_STOP=1", "-f", "-"], input=script, text=True, capture_output=True, env=env)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.strip()[-2000:])
    return p.stdout


REMOTE_FILE = Path.home() / ".config" / "pzopt" / "grafana-remote.env"
SPOOL = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local" / "state")) / "pzopt-grafana" / "spool"


class Remote:
    """The public dashboard's Postgres (GCP VM pzopt-db, reached through the IAP tunnel unit pzopt-grafana-tunnel on
    localhost:15433). Every script the local DB gets is applied there too, in order; a script that fails (tunnel or VM
    down) goes to the spool and the spool is replayed, oldest first, before anything newer."""

    def __init__(self):
        cfg = {}
        if REMOTE_FILE.exists():
            cfg = dict(l.split("=", 1) for l in REMOTE_FILE.read_text().splitlines() if "=" in l and not l.startswith("#"))
        self.env = None
        if cfg.get("PZ_REMOTE_PGPASSWORD") and os.environ.get("PZOPT_NO_REMOTE") != "1":
            self.env = dict(os.environ, PGHOST=cfg.get("PZ_REMOTE_PGHOST", "127.0.0.1"), PGPORT=cfg.get("PZ_REMOTE_PGPORT", "15433"),
                            PGUSER=cfg.get("PZ_REMOTE_PGUSER", "pzopt"), PGPASSWORD=cfg["PZ_REMOTE_PGPASSWORD"],
                            PGDATABASE=cfg.get("PZ_REMOTE_PGDATABASE", "pzopt"), PGCONNECT_TIMEOUT="15",
                            PGSSLMODE="disable")  # the IAP tunnel is already encrypted
        self.live = None

    def spooled(self):
        return sorted(SPOOL.glob("*.sql")) if SPOOL.exists() else []

    def flush(self):
        """Replay the spool in order; False when something is still waiting."""
        for f in self.spooled():
            try:
                psql_run(f.read_text(), self.env)
            except RuntimeError as e:
                print(f"remote: {len(self.spooled())} scripts spooled, replay waits: {str(e)[:200]}", file=sys.stderr, flush=True)
                return False
            f.unlink()
        return True

    def apply(self, script, what):
        if self.env is None:
            return
        if self.flush():
            try:
                psql_run(script, self.env)
                return
            except RuntimeError as e:
                print(f"remote: {what} spooled: {str(e)[:200]}", file=sys.stderr, flush=True)
        SPOOL.mkdir(parents=True, exist_ok=True)
        (SPOOL / f"{time.time_ns()}.sql").write_text(script)

    def send_live(self, script):
        """Live samples: one persistent connection, best effort (a dropped batch only costs the live view seconds)."""
        if self.env is None or not script:
            return
        if self.live is None or self.live.poll() is not None:
            self.live = subprocess.Popen(["psql", "-q", "-X", "-f", "-"], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                                         stderr=subprocess.DEVNULL, text=True, env=self.env)
        try:
            self.live.stdin.write(script)
            self.live.stdin.flush()
        except (BrokenPipeError, OSError):
            self.live = None


REMOTE = None


def remote():
    global REMOTE
    if REMOTE is None:
        REMOTE = Remote()
    return REMOTE


def psql_query(sql):
    p = subprocess.run(["psql", "-X", "-At", "-F", "\t", "-c", sql], text=True, capture_output=True)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.strip())
    return [l.split("\t") for l in p.stdout.splitlines() if l]


# ------------------------------------------------------------------ file parsers (epoch seconds out)

def parse_frames(p):
    """pzopt-frames.out: '# anchor <epochUs> <startUs> <frameId>' blocks of frame durations (us).
    Returns frames [(end_epoch_s, ms)], marks [(epoch_s, label)] and the epoch of Stats' T0."""
    frames, marks, t0 = [], [], None
    cur = None
    for line in p.read_text(errors="replace").splitlines():
        if line.startswith("#"):
            f = line.split()
            if len(f) >= 4 and f[1] == "anchor":
                cur = int(f[2])
                if t0 is None:
                    t0 = (int(f[2]) - int(f[3])) / 1e6
            elif len(f) >= 3 and t0 is not None:
                try:
                    marks.append((t0 + int(f[2]) / 1e6, f[1]))
                except ValueError:
                    pass
            continue
        line = line.strip()
        if not line or cur is None:
            continue
        try:
            d = int(line)
        except ValueError:
            continue
        cur += d
        frames.append((cur / 1e6, d / 1000.0))
    return frames, marks, t0


def parse_overlay(p, start_offset=0, state=None):
    """pzopt-overlay.out rows -> (epoch_s, fps, ms, gpu_ms, cpu, gpu, game, render). The row's time is the
    log's start epoch plus its nanosecond `elapsed` (epoch_ms alone is whole milliseconds)."""
    state = state if state is not None else {}
    rows = []
    with p.open(errors="replace") as f:
        f.seek(start_offset)
        for line in f:
            if not line.endswith("\n"):
                break
            start_offset += len(line.encode())
            parts = line.rstrip("\n").split(",")
            if parts[0] == "fps" or len(parts) < 9:
                continue
            try:
                vals = [float(x) for x in parts[:7]]
                elapsed, epoch_ms = int(parts[7]), int(parts[8])
            except ValueError:
                continue
            if "origin" not in state:
                state["origin"] = epoch_ms / 1e3 - elapsed / 1e9
            rows.append((state["origin"] + elapsed / 1e9, *vals))
    return rows, start_offset


SYSMON_COLS = ["cpu_pct", "busiest_core_pct", "gpu_pct", "gpu_sm_mhz", "gpu_mem_mhz", "gpu_w", "gpu_c", "vram_mib", "game_cpu_pct", "bat_w",
               "cpu_w", "soc_w", "total_w"]
SYSMON_SRC = {"cpu_busiest_core_pct": "busiest_core_pct"}


def parse_sysmon_lines(lines, header):
    rows = []
    for line in lines:
        parts = line.rstrip("\n").split(",")
        if parts[0] == "epoch_ms" or len(parts) != len(header):
            continue
        d = dict(zip(header, parts))
        t = num(d.get("epoch_ms"))
        if t is None:
            continue
        rows.append((t / 1e3, *[num(d.get(next((k for k, v in SYSMON_SRC.items() if v == c), c))) for c in SYSMON_COLS]))
    return rows


POWER_COLS = ["cpu_w", "gpu_w", "soc_w", "bat_w", "total_w", "fps"]
# CSV column -> power column per source file (pzopt-power.out already uses the table's names)
POWER_SRC = {"game": {}, "mac": {"system_w": "total_w"}}


def parse_power_lines(lines, header, source):
    """pzopt-power.out (the game's pzopt.Power) / power.csv (harness/macpower.py): epoch_ms-first CSV rows ->
    (epoch_s, *POWER_COLS); columns a source lacks are NULL."""
    ren = POWER_SRC[source]
    cols = [ren.get(h, h) for h in header]
    rows = []
    for line in lines:
        parts = line.rstrip("\n").split(",")
        if parts[0] == "epoch_ms" or len(parts) != len(cols):
            continue
        d = dict(zip(cols, parts))
        t = num(d.get("epoch_ms"))
        if t is not None:
            rows.append((t / 1e3, *[num(d.get(c)) for c in POWER_COLS]))
    return rows


def parse_gamethread_lines(lines):
    """per-second rows 'epoch_ms samples key=count...' -> (epoch_s mid-second, kind, name, count, share)."""
    rows = []
    for line in lines:
        if line.startswith("#"):
            continue
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 2:
            continue
        try:
            epoch, n = int(parts[0]), int(parts[1])
        except ValueError:
            continue
        t = epoch / 1e3 - 0.5
        rows.append((t, "total", "samples", n, 1.0))
        for kv in parts[2:]:
            k, _, v = kv.rpartition("=")
            if not k or not v.isdigit() or ":" not in k:
                continue
            kind, name = k.split(":", 1)
            rows.append((t, kind, name, int(v), int(v) / n if n else None))
    return rows


GC_TS = re.compile(r"^\[(\d{4}-\d\d-\d\dT[\d:.]+[+-]\d{4})\]")
GC_PAUSE = re.compile(r"GC\((\d+)\) (Pause .*?)(?: (\d+)M->(\d+)M\(\d+M\))? ([\d.]+)ms\s*$")


def parse_gc(p):
    rows = []
    for line in p.read_text(errors="replace").splitlines():
        m, g = GC_TS.match(line), GC_PAUSE.search(line)
        if not m or not g:
            continue
        t = datetime.datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S.%f%z").timestamp()
        rows.append((t, int(g.group(1)), g.group(2).strip(), float(g.group(5)), num(g.group(3)), num(g.group(4))))
    return rows


def parse_present(p):
    """present.txt: X Present CompleteNotify flips (ust_us monotonic + '# mono_to_epoch_us'); mode 2 = skipped."""
    off, last, rows = None, None, []
    for line in p.read_text(errors="replace").splitlines():
        if line.startswith("#"):
            f = line.split()
            if len(f) >= 3 and f[1] == "mono_to_epoch_us":
                off = int(f[2])
            continue
        f = line.split()
        if len(f) < 4 or off is None or f[3] == "2":
            continue
        ust = int(f[0])
        if last is not None and ust > last:
            rows.append(((ust + off) / 1e6, (ust - last) / 1000.0))
        if last is None or ust > last:
            last = ust
    return rows


def parse_vrr(p):
    rows = []
    for line in p.read_text(errors="replace").splitlines():
        f = line.split()
        if line.startswith("#") or len(f) < 5:
            continue
        t = num(f[0])
        if t is None:
            continue
        crtc = f"{f[1]}/{f[2]}"
        rows.append((t / 1e3, f"{crtc} vrr_enabled", num(f[3])))
        rows.append((t / 1e3, f"{crtc} mode_hz", num(f[4])))
    return rows


def parse_drive(p):
    """pzopt-drive.out (20 Hz, t = seconds since the pilot started at the route start) -> (rel_s, key, value)."""
    lines = p.read_text(errors="replace").splitlines()
    if not lines:
        return []
    hdr = lines[0].split("\t")
    rows = []
    for line in lines[1:]:
        f = line.split("\t")
        if len(f) != len(hdr):
            continue
        rel = num(f[0])
        if rel is None:
            continue
        for k, v in zip(hdr[1:], f[1:]):
            x = num(v)
            if x is not None:
                rows.append((rel, k, x))
    return rows


def parse_chunks(p, t0):
    rows = []
    with p.open(errors="replace") as f:
        hdr = f.readline().rstrip("\n").split("\t")
        for line in f:
            if line.startswith("#"):
                continue
            d = dict(zip(hdr, line.rstrip("\n").split("\t")))
            if len(d) != len(hdr):
                continue
            try:
                # a stage the chunk never went through is logged as a negative offset: NULL, not a negative wait
                parts = [int(d[k]) / 1000.0 if int(d[k]) >= 0 else None for k in ("queueWaitUs", "loadUs", "recalcWaitUs", "recalcUs", "publishWaitUs")]
                rows.append((t0 + int(d["tEnqueueUs"]) / 1e6, int(d["wx"]), int(d["wy"]), int(d["minLevel"]), int(d["maxLevel"]),
                             d.get("jobType"), d.get("thread"), *parts, sum(x for x in parts if x is not None)))
            except (KeyError, ValueError):
                continue
    return rows


def parse_threads(p):
    rows, in_table = [], False
    for line in p.read_text(errors="replace").splitlines():
        if line.startswith("#"):
            continue
        f = line.split("\t")
        if f[0] == "thread":
            in_table = True
            continue
        if in_table and len(f) >= 3:
            rows.append((f[0], num(f[1]), num(f[2])))
    return rows


def parse_counters(scenario, s):
    rows = []
    for k, v in scenario.items():
        x = num(v)
        if x is not None:
            rows.append(("bench", k, x))
    for key in ("bake_counters",):
        for kv in scenario.get(key, "").split():
            k, _, v = kv.partition("=")
            if num(v) is not None:
                rows.append((key, k, num(v)))
    for grp in scenario.get("zombie_batches", "").split("|"):
        name, _, rest = grp.partition(":")
        for kv in rest.split():
            k, _, v = kv.partition("=")
            if num(v) is not None:
                rows.append((f"zombie_batches/{name.strip()}", k, num(v)))
    for k, v in (s.get("chunks") or {}).items():
        if isinstance(v, dict):
            for kk, vv in v.items():
                if isinstance(vv, (int, float)):
                    rows.append((f"chunks/{k}", kk, float(vv)))
    for k, v in (s.get("threads") or {}).items():
        if isinstance(v, (int, float)):
            rows.append(("threads", k, float(v)))
    return rows


def stack_id(frames):
    """64-bit id of a root-first stack, shared by every run (stack_defs)."""
    return int.from_bytes(hashlib.md5(";".join(frames).encode()).digest()[:8], "big", signed=True)


LAMBDA_ADDR = re.compile(r"/0x[0-9a-f]+")


class StackAgg:
    """(second, source, thread, stack) -> samples, plus the stack definitions seen."""

    def __init__(self):
        self.rows, self.defs = {}, {}

    def add(self, sec, source, thread, frames, n):
        # hidden-class addresses differ per JVM run (MainThread$$Lambda/0x00000000731f8d30.run): drop them so runs compare
        frames = tuple(LAMBDA_ADDR.sub("", f) for f in frames)
        if not frames or n <= 0:
            return
        sid = self.defs.get(frames)
        if sid is None:
            sid = self.defs[frames] = stack_id(frames)
        k = (sec, source, thread, sid)
        self.rows[k] = self.rows.get(k, 0) + n


def parse_game_stacks(lines, agg, frames_dict=None, state=None):
    """pzopt-stacks.out: 'f <id> <Class.method>' frame ids, 't <epoch_ms> <samples>' opens a second, '<id>;<id>... <count>'
    root -> leaf. frames_dict / state carry over between calls when tailing."""
    fd = frames_dict if frames_dict is not None else {}
    st = state if state is not None else {}
    for line in lines:
        if not line or line.startswith("#"):
            continue
        if line.startswith("f "):
            parts = line.rstrip("\n").split(" ", 2)
            if len(parts) == 3:
                fd[parts[1]] = parts[2]
        elif line.startswith("t "):
            f = line.split()
            if len(f) >= 2:
                st["sec"] = int(f[1]) / 1e3 - 0.5
        elif "sec" in st:
            ids, _, n = line.rstrip("\n").rpartition(" ")
            if ids and n.isdigit():
                agg.add(st["sec"], "game", "MainThread", [fd.get(i, f"#{i}") for i in ids.split(";")], int(n))


def parse_lua_profile(p, agg):
    """pzopt-lua.out: epoch_ms, samples, then 'fn@file:line < caller < caller=count' (leaf first)."""
    for line in p.read_text(errors="replace").splitlines():
        if line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 3 or not parts[0].isdigit():
            continue
        sec = int(parts[0]) / 1e3 + 0.5
        for kv in parts[2:]:
            chain, _, n = kv.rpartition("=")
            if chain and n.isdigit():
                agg.add(sec, "lua", "MainThread", [c.strip() for c in reversed(chain.split(" < "))], int(n))


CACHE = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "pzopt-grafana"


def parse_jfr(run, name, source, agg):
    """A JFR recording's execution samples (tools/JfrSamples.java: kind, thread, epoch ns, frames top-first) per second
    and thread. The TSV is cached outside the run dir (writing into it would re-trigger the follower)."""
    jfr = run / name
    CACHE.mkdir(parents=True, exist_ok=True)
    tsv = CACHE / f"{run.name}-{source}.tsv"
    if not tsv.exists() or tsv.stat().st_mtime < jfr.stat().st_mtime:
        with tsv.open("w") as out:
            subprocess.run(["java", str(REPO / "tools" / "JfrSamples.java"), str(jfr)], stdout=out, stderr=subprocess.DEVNULL, check=True, timeout=600)
    with tsv.open(errors="replace") as f:
        f.readline()
        for line in f:
            kind, th, tns, rest = (line.rstrip("\n").split("\t", 3) + ["", "", "", ""])[:4]
            if kind not in ("java", "native") or not tns.isdigit():
                continue
            frames = rest.split(";")[::-1]
            agg.add(int(tns) // 1_000_000_000 + 0.5, source, th if th not in ("", "null") else "native", frames, 1)


def parse_schedmon(p):
    """schedmon.txt cumulative per-thread counters -> per second, per thread name: cpu %, run-queue %, major faults;
    PSI / vmstat and memory lines -> series rows."""
    last, acc, series = {}, {}, []
    last_psi = None
    for line in p.read_text(errors="replace").splitlines():
        f = line.split()
        if line.startswith("#") or len(f) < 3 or not f[0].isdigit():
            continue
        t = int(f[0]) / 1e3
        if f[1] == "-" and f[2] == "PSI" and len(f) >= 11:
            vals = [int(x) for x in f[3:11]]
            if last_psi is not None and t > last_psi[0]:
                dt = t - last_psi[0]
                for k, a, b in zip(("cpu_some_pct", "mem_some_pct", "mem_full_pct", "io_some_pct"), vals[:4], last_psi[1][:4]):
                    series.append((t, "psi", k, (a - b) / 1e4 / dt))
                for k, a, b in zip(("pswpin_s", "pswpout_s", "pgmajfault_s", "ctxt_s"), vals[4:], last_psi[1][4:]):
                    series.append((t, "psi", k, (a - b) / dt))
            last_psi = (t, vals)
            continue
        if f[1] == "-" and f[2] == "MEM":
            for kv in f[3:]:
                k, _, v = kv.partition("=")
                if num(v) is not None:
                    series.append((t, "mem", f"{k}_mib", num(v) / 1024))
            continue
        if len(f) < 8:
            continue
        tid, comm = f[1], f[2]
        try:
            run_ns, wait_ns, majflt = int(f[3]), int(f[4]), int(f[6])
        except ValueError:
            continue
        prev = last.get(tid)
        last[tid] = (t, run_ns, wait_ns, majflt)
        if prev is None or t <= prev[0]:
            continue
        sec = int(t)
        k = (sec, comm)
        a = acc.setdefault(k, [0.0, 0.0, 0, 0.0])
        a[0] += run_ns - prev[1]
        a[1] += wait_ns - prev[2]
        a[2] += majflt - prev[3]
    rows = [(sec + 0.5, comm, a[0] / 1e7, a[1] / 1e7, a[2]) for (sec, comm), a in sorted(acc.items())]
    return rows, series


def parse_pacing(p):
    """pzopt-pacing.out (nanoTime stamps per frame) -> (t, sim step, acquire lag, swap, hold, GPU after swap, shown after swap)."""
    rows, prev_sim = [], None
    for line in p.read_text(errors="replace").splitlines():
        f = line.split()
        if line.startswith("#") or len(f) < 8 or not f[0].isdigit():
            continue
        sim, acq, sc, sr, wait, gpu, shown = (int(x) for x in f[1:8])
        step = (sim - prev_sim) / 1e6 if prev_sim and sim > prev_sim else None
        if sim:
            prev_sim = sim
        rows.append((int(f[0]) / 1e3, step, (acq - sim) / 1e6 if sim and acq > sim else None,
                     (sr - sc) / 1e6 if sr > sc > 0 else None, wait / 1e6 if wait else None,
                     (gpu - sr) / 1e6 if gpu > sr > 0 else None, (shown - sr) / 1e6 if shown > sr > 0 else None))
    return rows


CONSOLE = re.compile(r"^\s*(LOG|WARN|ERROR|DEBUG|TRACE|INFO)\s*:\s*(\w+)[^>]*>\s?(.*)$", re.S)  # "WARN : General   at Log.warn   > text"


def parse_loadtrace(p):
    """pzopt-loadtrace.out: '<epoch_ms>\t<n>,<cat>,<cat>,"<console line>"' -> (t, level, category, text)."""
    rows = []
    for line in p.read_text(errors="replace").splitlines():
        ms_, _, rest = line.partition("\t")
        if not ms_.isdigit():
            continue
        try:
            fields = next(csv.reader([rest]))
        except (csv.Error, StopIteration):
            fields = [rest]
        text = fields[-1].replace("%0A", "\n").replace("%09", "\t")
        m = CONSOLE.match(text)
        level, cat, msg = (m.group(1), m.group(2), m.group(3)) if m else ("", fields[1] if len(fields) > 1 else "", text)
        rows.append((int(ms_) / 1e3, level, cat, msg.strip()))
    return rows


def parse_inputs_lines(lines):
    rows = []
    for line in lines:
        f = line.rstrip("\n").split("\t")
        if line.startswith("#") or len(f) != 4 or not f[0].isdigit():
            continue
        rows.append((int(f[0]) / 1e6, f[1], f[2], num(f[3])))
    return rows


def run_inputs(run, opts):
    rows = [("run.opts", k, v) for k, v in opts.items() if v != ""]
    for name, kind in (("pzopt-harness.txt", "flag"), ("pzopt.properties", "prop")):
        for k, v in kv_file(run / name).items():
            rows.append((kind, k, v))
    for kind, key in (("option", "game_options"), ("env", "game_env"), ("vmarg", "vmargs"), ("mod", "mods"), ("flag-arg", "flags")):
        for item in (opts.get(key) or "").split():
            k, _, v = item.partition("=")
            rows.append((kind, k, v))
    return rows


def stack_blocks(agg, name, times):
    """COPY blocks for an aggregated stack set: new definitions via a temp table (ON CONFLICT), then the samples."""
    out = ["CREATE TEMP TABLE IF NOT EXISTS stack_defs_in (id bigint, frames text[]);\nTRUNCATE stack_defs_in;\n"]
    out.append(copy_block("stack_defs_in", ["id", "frames"], ((sid, "{" + ",".join(pg_array_item(f) for f in fr) + "}") for fr, sid in agg.defs.items())))
    out.append("INSERT INTO stack_defs SELECT DISTINCT ON (id) id, frames FROM stack_defs_in ON CONFLICT (id) DO NOTHING;\n")
    if name is None:
        out.append(copy_block("live_stacks", ["t", "stack_id", "samples"], ((ts(sec), sid, n) for (sec, _, _, sid), n in agg.rows.items())))
    else:
        out.append(copy_block("stacks", ["run", "t", "rel_s", "rt", "source", "thread", "stack_id", "samples"],
                              ((name, *times(sec), src, th, sid, n) for (sec, src, th, sid), n in agg.rows.items())))
    return "".join(out)


def pg_array_item(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


# ------------------------------------------------------------------ one run

def g(d, *path):
    for p in path:
        if not isinstance(d, dict):
            return None
        d = d.get(p)
    return d


def ms(v):
    return v / 1000.0 if isinstance(v, (int, float)) else None


def variant_of(enabled, props, extra):
    """stock = the master switch off (--prop enabled=false) or harness/stock-props.py's every-key-at-stock set."""
    if not enabled:
        return "stock"
    if sum(1 for v in props.values() if v == "false") >= 20:
        return "stock (every key at its stock value)"
    return "optimized" + (f" [{extra[:120]}{'...' if len(extra) > 120 else ''}]" if extra else "")


def run_row(run, s, lat, judge, t_route):
    sc, opts, env = s.get("scenario", {}), s.get("opts", {}), s.get("environment", {})
    props = kv_file(run / "pzopt.properties")
    enabled = props.get("enabled", "true") != "false"
    std = {"instrument", "overlay", "overlaySampling", "overlayLog", "enabled"}
    extra = " ".join(f"{k}={v}" for k, v in sorted(props.items()) if k not in std)
    fr, ov, sm, th, gc = s.get("frames", {}), s.get("overlay", {}), s.get("sysmon", {}), s.get("threads", {}), s.get("gc", {})
    ans = g(judge, "answers") or {}
    threads = {t["name"]: t["share"] for t in th.get("threads", [])}
    launch = num(opts.get("launch_epoch")) or run_started(run.name)
    machine = opts.get("machine") or "desktop"
    gl = env.get("opengl", "").replace("OpenGL version: ", "")
    gpu = re.sub(r"^GPU: |, vendor.*$", "", env.get("gpu", ""))
    total_w, power_src, rails = power_watts(s)
    return {
        "run": run.name,
        "label": re.sub(r"-\d{8}-\d{6}$", "", run.name),
        "machine": machine,
        "started": ts(launch),
        "mode": sc.get("mode") or opts.get("mode"),
        "preset": opts.get("preset") or None,
        "route": sc.get("route"),
        "zoom": sc.get("zoom"),
        "resolution": sc.get("resolution") or env.get("desktop", "").replace("Desktop resolution ", "") or None,
        "opengl": gl or None,
        "gpu": gpu or None,
        "jvm": env.get("jvm_version", "").replace("java.vm.version=", "") or None,
        "platform": " · ".join(x for x in (machine, gpu, gl) if x),
        "variant": variant_of(enabled, props, extra),
        "props": " ".join(f"{k}={v}" for k, v in sorted(props.items())),
        "flags": opts.get("flags"),
        "enabled": enabled,
        "launcher": opts.get("launcher"),
        "route_start": ts(t_route[0]) if t_route[0] else None,
        "route_end": ts(t_route[1]) if t_route[1] else None,
        "route_seconds": num(sc.get("route_seconds")) or s.get("route_seconds"),
        "valid": bool(s.get("valid", True)) and sc.get("route_status", "complete") == "complete" and opts.get("crashed", "0") == "0",
        "verdict": g(ans, "verdict", "choice"),
        "verdict_confidence": g(ans, "verdict", "confidence"),
        "headroom_finding": g(ans, "hardware_headroom_finding", "noul"),
        "fps_mean": fr.get("fps_mean"),
        "frame_mean_ms": ms(g(fr, "us", "mean")),
        "p50_ms": ms(g(fr, "us", "p50")),
        "p90_ms": ms(g(fr, "us", "p90")),
        "p99_ms": ms(g(fr, "us", "p99")),
        "p99_9_ms": ms(g(fr, "us", "p99_9")),
        "max_ms": ms(g(fr, "us", "max")),
        "over_33ms": fr.get("over_33ms"),
        "over_50ms": fr.get("over_50ms"),
        "over_100ms": fr.get("over_100ms"),
        "presented_p99_ms": ms(g(ov, "us", "p99")),
        "presented_p99_9_ms": ms(g(ov, "us", "p99_9")),
        "jitter_ms": ms(ov.get("jitter_us")),
        "stdev_ms": ms(ov.get("stdev_us")),
        "fps_1pct_low": ov.get("fps_1pct_low"),
        "under_cap_pct": ov["under_cap_share"] * 100 if isinstance(ov.get("under_cap_share"), (int, float)) else None,
        "gpu_ms_mean": g(ov, "util", "gpu_ms", "mean"),
        "game_load_pct": g(ov, "util", "game_load", "mean"),
        "render_load_pct": g(ov, "util", "render_load", "mean"),
        "cpu_pct": g(sm, "cpu_pct", "mean"),
        "busiest_core_pct": g(sm, "cpu_busiest_core_pct", "mean"),
        "gpu_pct": g(sm, "gpu_pct", "mean"),
        "gpu_p90_pct": g(sm, "gpu_pct", "p90"),
        "gpu_w": g(sm, "gpu_w", "mean"),
        "gpu_c": g(sm, "gpu_c", "mean"),
        "vram_mib": g(sm, "vram_mib", "max"),
        "cpu_w": rails.get("cpu_w"),
        "total_w": total_w,
        "j_per_frame": total_w / fr["fps_mean"] if total_w is not None and fr.get("fps_mean") else None,
        "power_source": power_src,
        "process_cores": th.get("process_share"),
        "game_thread_pct": threads["MainThread"] * 100 if "MainThread" in threads else None,
        "render_thread_pct": threads["main"] * 100 if "main" in threads else None,
        "chunk_p50_ms": lat.get("p50"),
        "chunk_p90_ms": lat.get("p90"),
        "chunk_p99_ms": lat.get("p99"),
        "chunks_per_s": g(s, "chunks", "per_second"),
        "gc_events": gc.get("events_in_route"),
        "gc_wall_ms": gc.get("wall_ms_in_route"),
        "gc_max_ms": gc.get("max_event_ms"),
        "zombies_loaded": int(num(sc["zombies_loaded"])) if num(sc.get("zombies_loaded")) is not None else None,
        "scenario": sc,
        "opts": opts,
        "summary": s,
        "judge": judge or None,
        "path": str(run),
    }


def newest_mtime(run):
    try:
        return max((e.stat().st_mtime for e in os.scandir(run) if e.is_file()), default=0.0)
    except OSError:
        return 0.0


def is_run(run):
    return run.is_dir() and any((run / f).exists() for f in ("pzopt-frames.out", "pzopt-overlay.out", "mangohud.csv"))


def build_script(run):
    run = Path(run).resolve()
    name = run.name
    s = summarize(run)
    try:
        lat = latency(run) if (run / "pzopt-chunks.out").exists() else {}
    except Exception:
        lat = {}
    judge = {}
    if (run / "judge.json").exists():
        try:
            judge = json.loads((run / "judge.json").read_text())
        except ValueError:
            judge = {}
    sc = s.get("scenario", {})
    sched = kv_file(run / "pzopt-schedule.out")
    r0 = num(sc.get("route_start_epoch_ms")) or num(sched.get("route_start_epoch_ms"))
    r1 = num(sc.get("route_end_epoch_ms"))
    frames, marks, t0 = parse_frames(run / "pzopt-frames.out") if (run / "pzopt-frames.out").exists() else ([], [], None)
    overlay = parse_overlay(run / "pzopt-overlay.out")[0] if (run / "pzopt-overlay.out").exists() else []
    if not r0:  # no route: the first sample is the origin
        first = [x[0] for x in (frames[:1] + overlay[:1])]
        r0 = min(first) * 1e3 if first else None
    origin = r0 / 1e3 if r0 else 0.0
    end = r1 / 1e3 if r1 else None

    def times(t):
        rel = t - origin
        return ts(t), rel, ts(ROUTE_ORIGIN + rel)

    row = run_row(run, s, lat, judge, (origin if r0 else None, end))
    q = name.replace("'", "''")
    out = ["BEGIN;\n"]
    for tbl in ("runs", "frames", "overlay", "sysmon", "power", "gamethread", "chunks", "gc", "present", "series", "threads", "counters",
                "stacks", "sched", "events", "inputs", "run_inputs", "pacing"):
        out.append(f"DELETE FROM {tbl} WHERE run = '{q}';\n")
    out.append(copy_block("runs", list(row) + ["files_mtime"], [list(row.values()) + [newest_mtime(run)]]))
    out.append(copy_block("frames", ["run", "t", "rel_s", "rt", "ms", "in_route"],
                          ((name, *times(t), m, (t >= origin and (end is None or t <= end)) if r0 else None) for t, m in frames)))
    out.append(copy_block("overlay", ["run", "t", "rel_s", "rt", "fps", "ms", "gpu_ms", "cpu_load", "gpu_load", "game_load", "render_load"],
                          ((name, *times(r[0]), *r[1:]) for r in overlay)))
    if (run / "sysmon.csv").exists():
        lines = (run / "sysmon.csv").read_text(errors="replace").splitlines()
        rows = parse_sysmon_lines(lines, lines[0].split(",")) if lines else []
        out.append(copy_block("sysmon", ["run", "t", "rel_s", "rt", *SYSMON_COLS], ((name, *times(r[0]), *r[1:]) for r in rows)))
    for fname, source in (("pzopt-power.out", "game"), ("power.csv", "mac")):
        if (run / fname).exists():
            lines = (run / fname).read_text(errors="replace").splitlines()
            rows = parse_power_lines(lines, lines[0].split(","), source) if lines else []
            out.append(copy_block("power", ["run", "t", "rel_s", "rt", "source", *POWER_COLS], ((name, *times(r[0]), source, *r[1:]) for r in rows)))
    if (run / "pzopt-gamethread.out").exists():
        rows = parse_gamethread_lines((run / "pzopt-gamethread.out").read_text(errors="replace").splitlines())
        out.append(copy_block("gamethread", ["run", "t", "rel_s", "rt", "kind", "name", "samples", "share"],
                              ((name, *times(r[0]), *r[1:]) for r in rows)))
    if (run / "pzopt-chunks.out").exists() and t0 is not None:
        rows = parse_chunks(run / "pzopt-chunks.out", t0)
        out.append(copy_block("chunks", ["run", "t", "rel_s", "rt", "wx", "wy", "min_level", "max_level", "job", "thread",
                                         "queue_wait_ms", "load_ms", "recalc_wait_ms", "recalc_ms", "publish_wait_ms", "total_ms"],
                              ((name, *times(r[0]), *r[1:]) for r in rows)))
    if (run / "gc.log").exists():
        out.append(copy_block("gc", ["run", "t", "rel_s", "rt", "gc_id", "kind", "pause_ms", "heap_before_mb", "heap_after_mb"],
                              ((name, *times(r[0]), *r[1:]) for r in parse_gc(run / "gc.log"))))
    if (run / "present.txt").exists():
        out.append(copy_block("present", ["run", "t", "rel_s", "rt", "interval_ms"],
                              ((name, *times(r[0]), r[1]) for r in parse_present(run / "present.txt"))))
    series_extra = []
    series = [(t, "mark", label, 1.0) for t, label in marks]
    if (run / "vrr.txt").exists():
        series += [(t, "vrr", k, v) for t, k, v in parse_vrr(run / "vrr.txt")]
    if (run / "pzopt-drive.out").exists():
        series += [(origin + rel, "drive", k, v) for rel, k, v in parse_drive(run / "pzopt-drive.out")]
    out.append(copy_block("series", ["run", "t", "rel_s", "rt", "source", "key", "value"], ((name, *times(r[0]), *r[1:]) for r in series)))
    if (run / "pzopt-threads.out").exists():
        out.append(copy_block("threads", ["run", "thread", "cpu_ms", "share"], ((name, *r) for r in parse_threads(run / "pzopt-threads.out"))))
    out.append(copy_block("counters", ["run", "grp", "key", "value"], ((name, *r) for r in parse_counters(sc, s))))
    agg = StackAgg()
    if (run / "pzopt-stacks.out").exists():
        parse_game_stacks((run / "pzopt-stacks.out").read_text(errors="replace").splitlines(), agg)
    if (run / "pzopt-lua.out").exists():
        parse_lua_profile(run / "pzopt-lua.out", agg)
    for fname, src in (("pzopt.jfr", "jfr"), ("asprof.jfr", "asprof")):
        if (run / fname).exists():
            try:
                parse_jfr(run, fname, src, agg)
            except (subprocess.SubprocessError, OSError) as e:
                print(f"{name}: {fname} skipped: {e}", file=sys.stderr)
    if agg.rows:
        out.append(stack_blocks(agg, name, times))
    if (run / "schedmon.txt").exists():
        rows, more = parse_schedmon(run / "schedmon.txt")
        out.append(copy_block("sched", ["run", "t", "rel_s", "rt", "thread", "cpu_pct", "runq_pct", "majflt"], ((name, *times(r[0]), *r[1:]) for r in rows)))
        series_extra.extend(more)
    if (run / "pzopt-pacing.out").exists():
        out.append(copy_block("pacing", ["run", "t", "rel_s", "rt", "sim_step_ms", "acquire_lag_ms", "swap_ms", "hold_ms", "gpu_after_swap_ms",
                                         "shown_after_swap_ms"], ((name, *times(r[0]), *r[1:]) for r in parse_pacing(run / "pzopt-pacing.out"))))
    if series_extra:
        out.append(copy_block("series", ["run", "t", "rel_s", "rt", "source", "key", "value"], ((name, *times(r[0]), *r[1:]) for r in series_extra)))
    if (run / "pzopt-loadtrace.out").exists():
        out.append(copy_block("events", ["run", "t", "rel_s", "rt", "source", "level", "category", "text"],
                              ((name, *times(r[0]), "console", *r[1:]) for r in parse_loadtrace(run / "pzopt-loadtrace.out"))))
    if (run / "pzopt-input.out").exists():
        out.append(copy_block("inputs", ["run", "t", "rel_s", "rt", "device", "control", "value"],
                              ((name, *times(r[0]), *r[1:]) for r in parse_inputs_lines((run / "pzopt-input.out").read_text(errors="replace").splitlines()))))
    out.append(copy_block("run_inputs", ["run", "kind", "key", "value"], ((name, *r) for r in run_inputs(run, s.get("opts", {})))))
    out.append("COMMIT;\n")
    return "".join(out), row


def ingest(run):
    script, row = build_script(run)
    psql_run(script)
    remote().apply(script, Path(run).name)
    return row


def summary_line(row):
    f = lambda v, n=1: "-" if v is None else f"{v:.{n}f}"  # noqa: E731
    return (f"{row['run']}: fps {f(row['fps_mean'])} p99 {f(row['p99_ms'], 2)} ms p99.9 {f(row['p99_9_ms'], 2)} ms "
            f"cpu {f(row['cpu_pct'])}% gpu {f(row['gpu_pct'])}% verdict {row['verdict'] or '-'}")


def resolve(arg):
    p = Path(arg)
    if p.is_dir():
        return p
    for root in run_roots():
        cand = sorted(root.glob(f"{arg}*"))
        if cand:
            return cand[-1]
    raise SystemExit(f"no run dir for {arg}")


def pending_runs(known, since, quiet=QUIET_S):
    now = time.time()
    for root in run_roots():
        for d in root.iterdir():
            st = run_started(d.name)
            if st is None or st < since or not is_run(d):
                continue
            m = newest_mtime(d)
            if now - m < quiet:
                continue
            if known.get(d.name, -1.0) >= m - 0.001:
                continue
            yield d, m


# ------------------------------------------------------------------ live follower

class Tail:
    """Complete lines appended to a file since the last read; starts over when the file is replaced or truncated."""

    def __init__(self, path):
        self.path, self.ino, self.pos, self.fresh = Path(path), None, 0, False

    def read(self, active_s=30):
        try:
            st = self.path.stat()
        except OSError:
            self.ino = None
            return []
        if st.st_ino != self.ino or st.st_size < self.pos:
            stale = self.ino is None and time.time() - st.st_mtime > active_s
            self.ino, self.pos, self.fresh = st.st_ino, (st.st_size if stale else 0), True
        if st.st_size == self.pos:
            return []
        with self.path.open("rb") as f:
            f.seek(self.pos)
            data = f.read(st.st_size - self.pos)
        cut = data.rfind(b"\n")
        if cut < 0:
            return []
        self.pos += cut + 1
        return data[:cut].decode(errors="replace").split("\n")


class Live:
    def __init__(self):
        self.proc = None
        self.overlay = Tail(ZOMBOID / "pzopt-overlay.out")
        self.frames = Tail(ZOMBOID / "pzopt-frames.out")
        self.gt = Tail(ZOMBOID / "pzopt-gamethread.out")
        self.stacks = Tail(ZOMBOID / "pzopt-stacks.out")
        self.inputs = Tail(ZOMBOID / "pzopt-input.out")
        # the header is fixed (pzopt.Power): a tail that starts mid-file has not seen it
        self.power, self.power_hdr = Tail(ZOMBOID / "pzopt-power.out"), ["epoch_ms", *POWER_COLS]
        self.stack_frames, self.stack_state, self.known_stacks = {}, {}, set()
        self.sysmon, self.sysmon_hdr, self.run = None, None, None
        self.ov_origin, self.frame_cur = None, None

    def send(self, script):
        if not script:
            return
        remote().send_live(script)
        if self.proc is None or self.proc.poll() is not None:
            self.proc = subprocess.Popen(["psql", "-q", "-X", "-f", "-"], stdin=subprocess.PIPE, text=True)
        try:
            self.proc.stdin.write(script)
            self.proc.stdin.flush()
        except BrokenPipeError:
            self.proc = None

    def current_run_dir(self):
        best = None
        for root in run_roots():
            for d in root.iterdir():
                p = d / "sysmon.csv"
                try:
                    m = p.stat().st_mtime
                except OSError:
                    continue
                if time.time() - m < 5 and (best is None or m > best[0]):
                    best = (m, d)
        return best[1] if best else None

    def tick(self):
        out = []
        lines = self.overlay.read()
        if self.overlay.fresh:
            self.ov_origin, self.overlay.fresh = None, False
        rows = []
        for line in lines:
            p = line.split(",")
            if len(p) < 9 or p[0] == "fps":
                continue
            try:
                vals = [float(x) for x in p[:7]]
                el, ep = int(p[7]), int(p[8])
            except ValueError:
                continue
            if self.ov_origin is None:
                self.ov_origin = ep / 1e3 - el / 1e9
            rows.append((ts(self.ov_origin + el / 1e9), *vals))
        if rows:
            out.append(copy_block("live_overlay", ["t", "fps", "ms", "gpu_ms", "cpu_load", "gpu_load", "game_load", "render_load"], rows))
        rows = []
        for line in self.frames.read():
            f = line.split()
            if line.startswith("#"):
                if len(f) >= 4 and f[1] == "anchor":
                    self.frame_cur = int(f[2])
                continue
            if self.frame_cur is None or not line.strip().isdigit():
                continue
            self.frame_cur += int(line)
            rows.append((ts(self.frame_cur / 1e6), int(line) / 1000.0))
        if rows:
            out.append(copy_block("live_frames", ["t", "ms"], rows))
        rows = [(ts(r[0]), *r[1:]) for r in parse_gamethread_lines(self.gt.read())]
        if rows:
            out.append(copy_block("live_gamethread", ["t", "kind", "name", "samples", "share"], rows))
        lines = self.stacks.read()
        if self.stacks.fresh:
            self.stack_frames, self.stack_state, self.stacks.fresh = {}, {}, False
        if lines:
            agg = StackAgg()
            parse_game_stacks(lines, agg, self.stack_frames, self.stack_state)
            new = {fr: sid for fr, sid in agg.defs.items() if sid not in self.known_stacks}
            self.known_stacks.update(new.values())
            agg.defs = new
            if agg.rows:
                out.append(stack_blocks(agg, None, None))
        rows = [(ts(r[0]), *r[1:]) for r in parse_inputs_lines(self.inputs.read())]
        if rows:
            out.append(copy_block("live_inputs", ["t", "device", "control", "value"], rows))
        lines = self.power.read()
        if lines and lines[0].startswith("epoch_ms"):
            self.power_hdr = lines[0].split(",")
        if lines:
            rows = [(ts(r[0]), *r[1:]) for r in parse_power_lines(lines, self.power_hdr, "game")]
            if rows:
                out.append(copy_block("live_power", ["t", *POWER_COLS], rows))
        d = self.current_run_dir()
        if d is not None and d != self.run:
            self.run, self.sysmon, self.sysmon_hdr = d, Tail(d / "sysmon.csv"), None
            out.append("INSERT INTO live_state (k, v, t) VALUES ('run', '%s', now()) ON CONFLICT (k) DO UPDATE SET v = EXCLUDED.v, t = now();\n"
                       % d.name.replace("'", "''"))
        if self.sysmon is not None:
            lines = self.sysmon.read()
            if lines and lines[0].startswith("epoch_ms"):
                self.sysmon_hdr = lines[0].split(",")
            if self.sysmon_hdr:
                rows = [(ts(r[0]), *r[1:]) for r in parse_sysmon_lines(lines, self.sysmon_hdr)]
                if rows:
                    out.append(copy_block("live_sysmon", ["t", *SYSMON_COLS], rows))
        self.send("".join(out))

    def prune(self, stacks=False):
        sql = "".join(f"DELETE FROM {t} WHERE t < now() - interval '6 hours';\n"
                      for t in ("live_overlay", "live_frames", "live_sysmon", "live_power", "live_gamethread", "live_stacks", "live_inputs"))
        if stacks:  # stack definitions no run and no live sample points at any more (re-imports, deleted runs)
            sql += ("DELETE FROM stack_defs d WHERE NOT EXISTS (SELECT 1 FROM stacks s WHERE s.stack_id = d.id) "
                    "AND NOT EXISTS (SELECT 1 FROM live_stacks l WHERE l.stack_id = d.id);\n")
        self.send(sql)


def follow():
    live, since = Live(), since_epoch()
    known = {r[0]: float(r[1]) for r in psql_query("SELECT run, coalesce(files_mtime, 0) FROM runs")}
    failed = {}
    last_scan = last_prune = last_spool = 0.0
    last_stack_prune = time.time() - 3000  # first definition prune ~10 min after start
    print(f"follow: live from {ZOMBOID}, runs since {datetime.datetime.fromtimestamp(since)} under {', '.join(map(str, run_roots()))}", flush=True)
    while True:
        try:
            live.tick()
        except Exception as e:  # a half-written line must never stop the follower
            print(f"live: {e}", file=sys.stderr, flush=True)
        now = time.time()
        if now - last_scan > 10:
            last_scan = now
            for d, m in list(pending_runs(known, since)):
                if failed.get(d.name) == m:
                    continue
                try:
                    row = ingest(d)
                    known[d.name] = m
                    print("imported " + summary_line(row), flush=True)
                except Exception as e:
                    failed[d.name] = m
                    print(f"import {d.name} failed: {e}", file=sys.stderr, flush=True)
        if now - last_spool > 30 and remote().spooled():
            last_spool = now
            remote().flush()
        if now - last_prune > 60:
            last_prune = now
            live.prune(stacks=now - last_stack_prune > 3600)
            if now - last_stack_prune > 3600:
                last_stack_prune = now
        time.sleep(1)


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("runs", nargs="*")
    ap.add_argument("--all", action="store_true", help="every run started after harness/grafana/since")
    ap.add_argument("--follow", action="store_true", help="live game + import finished runs (the stack's follower)")
    ap.add_argument("--dry-run", action="store_true", help="parse and print the summary line, write nothing")
    ap.add_argument("--no-remote", action="store_true", help="local DB only (not the public dashboard's DB)")
    a = ap.parse_args(argv)
    if a.no_remote:
        os.environ["PZOPT_NO_REMOTE"] = "1"
    if a.follow:
        follow()
        return 0
    targets = [resolve(r) for r in a.runs]
    if a.all:
        targets += [d for d, _ in pending_runs({}, since_epoch(), quiet=0)]
    if not targets:
        ap.print_help()
        return 2
    rc = 0
    for d in targets:
        try:
            if a.dry_run:
                script, row = build_script(d)
                print(summary_line(row) + f" ({len(script) // 1024} KB of COPY data)")
            else:
                print(summary_line(ingest(d)))
        except Exception as e:
            rc = 1
            print(f"{d.name}: {e}", file=sys.stderr)
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
