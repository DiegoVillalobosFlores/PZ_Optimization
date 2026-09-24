#!/usr/bin/env python3
"""Input lag of a run.sh --inputlag run: every injected press lined up with the stages pzopt.InputLag stamped.

  harness/inputlag.py <run dir> [--presses] [--csv <file>]

Inputs (run dir): inputlag-drive.log (harness/inputlag-drive.py: "in <device>:<what> <value> t=<us>", marks) and
pzopt-inputlag.out (pzopt.InputLag, see its javadoc). Per press, all in ms after the injection (the uinput SYN):
  os     glfwPollEvents handed it to lwjglx (render thread; keyboard / mouse only)
  smp    the render thread's input poll copied it into the polling state
  game   the game thread's logic() swapped it in (the frame that first sees it)
  push   that frame was handed to the render thread; swap: its buffer swap returned; gpu: the GPU finished it
  react  player 0 first changed after it (position / facing / rendered facing / aim), sampled before that frame's render
  vswap / vgpu  swap / GPU completion of the reacting frame = input to the first frame that shows a response
The display adds the compositor and scan-out after vgpu (not measured: ~1 refresh at 240 Hz under KWin).
Per section (the script's marks): median / p90 / max of each, and the median step between stages.
"""
import bisect
import re
import statistics
import sys
from pathlib import Path

BIT = {"key:w": 0, "key:s": 1, "key:a": 2, "key:d": 3, "mouse:left": 4, "mouse:right": 5, "pad:lt": 15, "pad:a": 14}
STICK_BIT = {("l", 0, -1): 6, ("l", 0, 1): 7, ("l", -1, 0): 8, ("l", 1, 0): 9,
             ("r", 0, -1): 10, ("r", 0, 1): 11, ("r", -1, 0): 12, ("r", 1, 0): 13}
STAGES = ["os", "smp", "game", "push", "swap", "gpu", "screen", "react", "vswap", "vgpu", "vscreen"]


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))] if xs else float("nan")


def load_game(path):
    g = {"os": [], "smp": [], "game": [], "R": [], "P": [], "A": {}, "S": {}, "G": {}}
    for line in path.read_text(errors="replace").splitlines():
        p = line.split()
        if not p or p[0].startswith("#"):
            continue
        try:
            if p[0] in ("os", "smp", "game"):
                g[p[0]].append((int(p[3][2:]), int(p[1], 16), int(p[2])))
            elif p[0] == "R":
                g["R"].append((int(p[1]), float(p[2]), float(p[3]), int(p[4]), int(p[5]), int(p[6])))
            elif p[0] == "P":
                g["P"].append((int(p[2]), int(p[1]), int(p[3])))
            elif p[0] in ("A", "S", "G"):
                g[p[0]][int(p[1])] = int(p[2])
        except (ValueError, IndexError):
            pass
    for k in ("os", "smp", "game", "R", "P"):
        g[k].sort()
    return g


def load_flips(run):
    """present.txt (harness/presentprobe.c, copied from the vrr branch): when each frame reached the screen, epoch us."""
    f = run / "present.txt"
    if not f.exists():
        return []
    off, flips = 0, []
    for line in f.read_text(errors="replace").splitlines():
        if line.startswith("# mono_to_epoch_us"):
            off = int(line.split()[-1])
        elif line and not line.startswith("#"):
            p = line.split()
            if len(p) >= 4 and p[3] != "2":  # mode 2 = skipped
                flips.append(int(p[0]) + off)
    return sorted(flips)


def load_drive(path):
    presses, marks = [], []
    for line in path.read_text(errors="replace").splitlines():
        m = re.match(r"in (\S+) (\S+) t=(\d+)", line)
        if m:
            presses.append({"what": m[1], "val": m[2], "t": int(m[3])})
            continue
        m = re.match(r"(mark|done) ?(.*?) t=(\d+)", line)
        if m:
            marks.append((int(m[3]), m[2].strip() or m[1]))
    return presses, marks


def first_after(rows, t, cond):
    i = bisect.bisect_left(rows, (t,))
    for r in rows[i:]:
        if r[0] > t + 2_000_000:
            return None
        if cond(r):
            return r
    return None


def match(g, presses, marks, flips=()):
    def flip_after(t):
        if not t or not flips:
            return None
        i = bisect.bisect_left(flips, t - 100)
        return flips[i] if i < len(flips) and flips[i] - t < 100_000 else None

    maxx = max((r[2] for r in g["os"]), default=0)
    pushes = g["P"]
    push_t = [p[0] for p in pushes]

    def frame_after(t):
        i = bisect.bisect_right(push_t, t)
        return pushes[i] if i < len(pushes) else None

    events = []
    for pr in presses:
        what, val, t = pr["what"], pr["val"], pr["t"]
        cond = None
        if what.startswith(("key:", "mouse:", "pad:")) and val == "1" or what == "pad:lt" and val == "255":
            b = BIT.get(what)
            if b is None:
                continue
            cond = (lambda b: lambda r: r[1] >> b & 1)(b)
            kind = what
        elif what.startswith("stick:") and val != "0,0":
            x, y = (int(float(v)) for v in val.split(","))
            b = STICK_BIT.get((what[6:], x, y))
            if b is None:
                continue
            cond = (lambda b: lambda r: r[1] >> b & 1)(b)
            kind = "%s %s" % (what, val)
        elif what.startswith("mmove:"):
            dx = int(what[6:].split(",")[0])
            cond = (lambda r: r[2] < 400) if dx < 0 else (lambda r: r[2] > maxx - 400)
            kind = "mmove"
        else:
            continue
        ev = {"kind": kind, "t": t, "section": ""}
        for tm, name in marks:
            if tm <= t:
                ev["section"] = name
        for st in ("os", "smp", "game"):
            r = first_after(g[st], t - 200, cond)
            ev[st] = r[0] if r else None
        if ev["game"] is None:
            events.append(ev)
            continue
        f = frame_after(ev["game"])
        if f:
            ev["push"], ev["n"], ev["wait"] = f
            ev["acq"] = g["A"].get(f[1])
            ev["swap"] = g["S"].get(f[1])
            ev["gpu"] = g["G"].get(f[1])
            ev["screen"] = flip_after(ev["swap"])
        # the player must be at rest when the input lands: no R line in the 60 ms before the press
        prev = first_after(g["R"], t - 60_000, lambda r: True)
        ev["rest"] = prev is None or prev[0] >= ev["game"]
        want_aim = kind in ("mouse:right", "pad:lt")
        r = first_after(g["R"], ev["game"], (lambda r: r[5] == 1) if want_aim else (lambda r: True))
        if r and ev["rest"]:
            ev["react"] = r[0]
            fr = frame_after(r[0])
            if fr:
                ev["vswap"] = g["S"].get(fr[1])
                ev["vgpu"] = g["G"].get(fr[1])
                ev["vscreen"] = flip_after(ev["vswap"])
                ev["vframes"] = fr[1] - ev.get("n", fr[1])
        events.append(ev)

    return events


def compare(runs):
    """One row per input class, one column per run: p50 input -> game / swap / gpu done (ms), and the fps."""
    table, fps = {}, {}
    for run in runs:
        g = load_game(run / "pzopt-inputlag.out")
        presses, marks = load_drive(run / "inputlag-drive.log")
        s_sorted = sorted(g["S"].items())
        fm = [(t1 - t0) / 1000 for (n0, t0), (n1, t1) in zip(s_sorted, s_sorted[1:]) if n1 == n0 + 1]
        fps[run] = 1000 / statistics.median(fm) if fm else float("nan")
        evs = match(g, presses, marks, load_flips(run))
        for ev in evs:
            cls = CLASS.get(ev["kind"].split(" ")[0])
            if not cls or ev["section"].startswith("pad-bind"):
                continue
            if ev["kind"].startswith("stick:r") and ev["section"] != "pad-aim":
                continue
            d = table.setdefault(cls, {}).setdefault(run, {"game": [], "swap": [], "gpu": [], "screen": [], "vgpu": []})
            for st in d:
                if ev.get(st):
                    d[st].append((ev[st] - ev["t"]) / 1000)
    names = [r.name.rsplit("-", 2)[0] for r in runs]
    w = max(16, max(len(n) for n in names) + 1)
    print(f"{'input -> game / gpu done / screen, p50':<34}" + "".join(f"{n:>{w}}" for n in names))
    print(f"{'fps':<34}" + "".join(f"{fps[r]:>{w}.0f}" for r in runs))
    for cls in CLASS_ORDER:
        if cls not in table:
            continue
        cells = []
        for r in runs:
            d = table[cls].get(r)
            if not d or not d["game"]:
                cells.append(f"{'-':>{w}}")
                continue
            scr = f"{pct(d['screen'], 50):.1f}" if d["screen"] else "-"
            cells.append(f"{pct(d['game'], 50):.1f}/{pct(d['gpu'], 50):.1f}/{scr}".rjust(w))
        print(f"{cls:<34}" + "".join(cells))


CLASS = {"key:w": "keyboard (W/S walk)", "key:s": "keyboard (W/S walk)", "mmove": "mouse move (aiming)",
         "mouse:right": "mouse right button", "stick:l": "pad left stick (walk)", "stick:r": "pad right stick (aiming)",
         "pad:lt": "pad LT (aim)"}
CLASS_ORDER = ["keyboard (W/S walk)", "mouse move (aiming)", "mouse right button", "pad left stick (walk)",
               "pad right stick (aiming)", "pad LT (aim)"]


def main():
    runs = [Path(a) for a in sys.argv[1:] if not a.startswith("--") and Path(a).is_dir()]
    if len(runs) > 1:
        compare(runs)
        return
    run = Path(sys.argv[1])
    show = "--presses" in sys.argv
    csv = sys.argv[sys.argv.index("--csv") + 1] if "--csv" in sys.argv else None
    g = load_game(run / "pzopt-inputlag.out")
    presses, marks = load_drive(run / "inputlag-drive.log")
    maxx = max((r[2] for r in g["os"]), default=0)
    pushes = g["P"]
    push_t = [p[0] for p in pushes]

    def frame_after(t):
        i = bisect.bisect_right(push_t, t)
        return pushes[i] if i < len(pushes) else None

    events = match(g, presses, marks, load_flips(run))

    frame_ms = []
    s_sorted = sorted(g["S"].items())
    for (n0, t0), (n1, t1) in zip(s_sorted, s_sorted[1:]):
        if n1 == n0 + 1:
            frame_ms.append((t1 - t0) / 1000)
    fps = 1000 / statistics.median(frame_ms) if frame_ms else float("nan")
    waits = [p[2] / 1000 for p in pushes]
    print(f"== {run.name}: {len(presses)} injected changes, {len(events)} presses matched to a watch, "
          f"{len(pushes)} frames; swap interval p50 {statistics.median(frame_ms):.2f} ms ({fps:.0f} fps), "
          f"p99 {pct(frame_ms, 99):.2f}; hand-off wait p50 {statistics.median(waits):.2f} ms p99 {pct(waits, 99):.2f}")
    # the steady pipeline, whatever the input: logic -> push -> acquire -> swap -> GPU done, per frame
    pipe = {"push->acq": [], "acq->swap": [], "swap->gpu": [], "push->gpu": []}
    for t, n, _ in pushes:
        a, s, gp = g["A"].get(n), g["S"].get(n), g["G"].get(n)
        if a and s:
            pipe["push->acq"].append((a - t) / 1000)
            pipe["acq->swap"].append((s - a) / 1000)
        if s and gp:
            pipe["swap->gpu"].append((gp - s) / 1000)
        if gp:
            pipe["push->gpu"].append((gp - t) / 1000)
    print("pipeline per frame (ms, p50 / p99): " + ", ".join(
        f"{k} {statistics.median(v):.2f} / {pct(v, 99):.2f}" for k, v in pipe.items() if v))

    groups = {}
    for ev in events:
        groups.setdefault((ev["section"], ev["kind"].split(" ")[0]), []).append(ev)
    print(f"{'section':<16}{'input':<13}{'n':>3} {'miss':>4}  " + "  ".join(f"{s:>16}" for s in STAGES) + "   vframes")
    print(" " * 38 + "  ".join(f"{'p50/p90/max':>16}" for _ in STAGES))
    for (sec, kind), evs in groups.items():
        cells = []
        for st in STAGES:
            xs = [(ev[st] - ev["t"]) / 1000 for ev in evs if ev.get(st)]
            cells.append(f"{pct(xs, 50):5.1f}/{pct(xs, 90):4.1f}/{max(xs):5.1f}" if xs else f"{'-':>16}")
        miss = sum(1 for ev in evs if not ev.get("game"))
        vf = [ev["vframes"] for ev in evs if "vframes" in ev]
        print(f"{sec:<16}{kind:<13}{len(evs):>3} {miss:>4}  " + "  ".join(f"{c:>16}" for c in cells)
              + (f"   {statistics.median(vf):.0f}" if vf else ""))
    print("\nmedian step between stages (ms):")
    for (sec, kind), evs in groups.items():
        steps = []
        prev = "t"
        for st in STAGES[:6]:
            xs = [(ev[st] - ev[prev]) / 1000 for ev in evs if ev.get(st) and ev.get(prev)]
            if xs:
                steps.append(f"{prev}->{st} {statistics.median(xs):.2f}")
                prev = st
        re_ = [(ev["react"] - ev["game"]) / 1000 for ev in evs if ev.get("react")]
        if re_:
            steps.append(f"game->react {statistics.median(re_):.2f}")
        print(f"  {sec:<16}{kind:<13}" + ", ".join(steps))
    if show:
        for ev in events:
            print(ev["section"], ev["kind"], " ".join(
                f"{st}={(ev[st] - ev['t']) / 1000:.1f}" for st in STAGES if ev.get(st)), "rest" if ev.get("rest") else "moving")
    if csv:
        with open(csv, "w") as f:
            f.write("section,input,t_us," + ",".join(STAGES) + "\n")
            for ev in events:
                f.write(f"{ev['section']},{ev['kind']},{ev['t']}," + ",".join(
                    f"{(ev[st] - ev['t']) / 1000:.3f}" if ev.get(st) else "" for st in STAGES) + "\n")


if __name__ == "__main__":
    main()
