#!/usr/bin/env python3
"""Drive paths for harness drive runs (run.sh --mode drive --flag path=...), built from the map's own street
centrelines (media/maps/Muldraugh, KY/streets.xml: every named street, KY-60 and the other highways included, as a
polyline with a width) and town labels (worldmap-annotations.lua).

    drive-path.py streets [--near X,Y] [--radius R]
        the streets near a point: name, width, extent, points
    drive-path.py route --from X,Y --via "KY-60>North Main St>Jacks Lane" [--end-margin 12] [--to X,Y]
        the path that starts on the first street at the point nearest X,Y, follows each street to where the next one
        joins it and ends on the last street's far end (pulled back --end-margin tiles when that end meets another
        street, so the car stops before the junction); prints the path= flag, its length and legs
    drive-path.py plan --from X,Y --describe "east a little, then all south through Rosewood" [--min 300] [--max 1500]
        every route from X,Y over the street graph (up to --turns street changes), described as legs (compass
        direction, tiles, street names, towns passed); Jev (TypeSafe) picks the one the description asks for;
        prints the ranking and the pick's path= flag. --no-jev lists the candidates only.

The path flag is 'x,y/x,y/...' (no spaces or ';', so it survives the queue's argument handling). Harness rounds
every corner to --flag corner_radius (10 tiles), so the waypoints are the centreline corners.
"""
import argparse
import json
import math
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))


def map_dir():
    pz = os.environ.get("PZ_DIR", "/games/steamapps/common/ProjectZomboid/projectzomboid")
    return Path(pz) / "media" / "maps" / "Muldraugh, KY"


def load_streets():
    out = []
    for s in ET.parse(map_dir() / "streets.xml").getroot():
        pts = [(float(p.get("x")), float(p.get("y"))) for p in s.iter("point")]
        if len(pts) >= 2:
            out.append({"name": s.get("name"), "width": float(s.get("width") or 6), "pts": pts, "len": polylen(pts)})
    return out


def load_towns():
    txt = (map_dir() / "worldmap-annotations.lua").read_text(errors="replace")
    towns = []
    for name, kind, x, y in re.findall(r'addUntranslatedText\("MapLabel_([A-Za-z]+)", "text-(town|place)[a-z-]*", ([0-9.]+), ([0-9.]+)', txt):
        towns.append((re.sub(r"(?<=[a-z])(?=[A-Z])", " ", name), kind, float(x), float(y)))
    return towns


def polylen(pts):
    return sum(math.dist(a, b) for a, b in zip(pts, pts[1:]))


def project(pts, p):
    """(distance, arc length, point) of p's nearest point on the polyline."""
    best, s0 = (float("inf"), 0.0, pts[0]), 0.0
    for a, b in zip(pts, pts[1:]):
        dx, dy = b[0] - a[0], b[1] - a[1]
        L2 = dx * dx + dy * dy
        t = 0.0 if L2 == 0 else max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / L2))
        q = (a[0] + t * dx, a[1] + t * dy)
        d = math.dist(p, q)
        if d < best[0]:
            best = (d, s0 + t * math.sqrt(L2), q)
        s0 += math.sqrt(L2)
    return best


def point_at(pts, s):
    s0 = 0.0
    for a, b in zip(pts, pts[1:]):
        L = math.dist(a, b)
        if s0 + L >= s - 1e-9 and L > 0:
            t = (s - s0) / L
            return (a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))
        s0 += L
    return pts[-1]


def piece(pts, s_from, s_to):
    """The polyline between two arc lengths (either direction)."""
    rev = s_to < s_from
    lo, hi = (s_to, s_from) if rev else (s_from, s_to)
    out, s0 = [point_at(pts, lo)], 0.0
    for a, b in zip(pts, pts[1:]):
        s0 += math.dist(a, b)
        if lo < s0 < hi:
            out.append(b)
    out.append(point_at(pts, hi))
    return out[::-1] if rev else out


def seg_intersect(a, b, c, d):
    r = (b[0] - a[0], b[1] - a[1])
    q = (d[0] - c[0], d[1] - c[1])
    den = r[0] * q[1] - r[1] * q[0]
    if abs(den) < 1e-9:
        return None
    t = ((c[0] - a[0]) * q[1] - (c[1] - a[1]) * q[0]) / den
    u = ((c[0] - a[0]) * r[1] - (c[1] - a[1]) * r[0]) / den
    if 0 <= t <= 1 and 0 <= u <= 1:
        return (a[0] + t * r[0], a[1] + t * r[1])
    return None


def junctions(streets, bbox=None):
    """{street index: [(s on it, other index, s on other), ...]} for crossings and T-joins (an end within half the
    widths + 2 tiles of the other street)."""
    def overlaps(st):  # the street's own box against the region (a highway is one long segment)
        xs = [p[0] for p in st["pts"]]; ys = [p[1] for p in st["pts"]]
        return min(xs) <= bbox[2] and max(xs) >= bbox[0] and min(ys) <= bbox[3] and max(ys) >= bbox[1]
    idx = [i for i, st in enumerate(streets) if bbox is None or overlaps(st)]
    J = {i: [] for i in idx}
    for ii, i in enumerate(idx):
        A = streets[i]
        for j in idx[ii + 1:]:
            B = streets[j]
            ax = [p[0] for p in A["pts"]]; ay = [p[1] for p in A["pts"]]
            bx = [p[0] for p in B["pts"]]; by = [p[1] for p in B["pts"]]
            m = (A["width"] + B["width"]) / 2 + 2
            if min(ax) > max(bx) + m or min(bx) > max(ax) + m or min(ay) > max(by) + m or min(by) > max(ay) + m:
                continue
            found = []
            for a, b in zip(A["pts"], A["pts"][1:]):
                for c, d in zip(B["pts"], B["pts"][1:]):
                    x = seg_intersect(a, b, c, d)
                    if x:
                        found.append((project(A["pts"], x)[1], project(B["pts"], x)[1]))
            for end, sb in ((B["pts"][0], 0.0), (B["pts"][-1], B["len"])):
                d, sa, _ = project(A["pts"], end)
                if d <= m:
                    found.append((sa, sb))
            for end, sa in ((A["pts"][0], 0.0), (A["pts"][-1], A["len"])):
                d, sb, _ = project(B["pts"], end)
                if d <= m:
                    found.append((sa, sb))
            for sa, sb in found:
                if not any(abs(sa - x[0]) < 3 and x[1] == j for x in J[i]):
                    J[i].append((sa, j, sb))
                    J[j].append((sb, i, sa))
    for i in J:
        J[i].sort()
    return J


def compass(dx, dy):
    a = math.degrees(math.atan2(dx, -dy)) % 360  # 0 = north, y points south
    return ["N", "NE", "E", "SE", "S", "SW", "W", "NW"][int((a + 22.5) // 45) % 8]


def simplify(pts, tol=0.4):
    """Douglas-Peucker: the corners that matter."""
    if len(pts) < 3:
        return pts
    a, b = pts[0], pts[-1]
    L = math.dist(a, b) or 1e-9
    dmax, k = 0.0, 0
    for i in range(1, len(pts) - 1):
        p = pts[i]
        d = abs((b[0] - a[0]) * (a[1] - p[1]) - (a[0] - p[0]) * (b[1] - a[1])) / L
        if d > dmax:
            dmax, k = d, i
    if dmax <= tol:
        return [a, b]
    return simplify(pts[:k + 1], tol)[:-1] + simplify(pts[k:], tol)


def assemble(streets, pieces, end_margin, J):
    """pieces: [(street, s_from, s_to)] -> waypoints, legs. The last piece is pulled back end_margin tiles when its end
    meets another street."""
    st, s_from, s_to = pieces[-1]
    if end_margin > 0 and any(abs(sa - s_to) < 3 for sa, _, _ in J.get(st, [])):
        s_to = s_to - end_margin if s_to > s_from else s_to + end_margin
        pieces = pieces[:-1] + [(st, s_from, s_to)]
    pts = []
    for st, a, b in pieces:
        for p in piece(streets[st]["pts"], a, b):
            if not pts or math.dist(pts[-1], p) > 0.05:
                pts.append(p)
    wp = simplify(pts)
    return wp, pieces


def describe(streets, pieces, wp, towns):
    legs = []
    for a, b in zip(wp, wp[1:]):
        d = compass(b[0] - a[0], b[1] - a[1])
        L = math.dist(a, b)
        if legs and legs[-1][0] == d:
            legs[-1][1] += L
        else:
            legs.append([d, L])
    names = []
    for st, _, _ in pieces:
        n = streets[st]["name"]
        if not names or names[-1] != n:
            names.append(n)
    passed = []
    for i in range(0, len(wp) - 1):
        a, b = wp[i], wp[i + 1]
        n = max(2, int(math.dist(a, b) // 20))
        for k in range(n + 1):
            p = (a[0] + (b[0] - a[0]) * k / n, a[1] + (b[1] - a[1]) * k / n)
            for name, kind, tx, ty in towns:
                if math.dist(p, (tx, ty)) < (350 if kind == "town" else 120) and name not in passed:
                    passed.append(name)
    total = polylen(wp)
    text = ", then ".join(f"{d} {L:.0f} tiles" for d, L in legs) + f" ({total:.0f} tiles; streets: {' > '.join(names)}" \
        + (f"; passes {', '.join(passed)})" if passed else "; no town)")
    return {"legs": [[d, round(L)] for d, L in legs], "streets": names, "towns": passed, "length": round(total), "text": text}


def flag(wp):
    def f(v):
        return f"{v:.1f}".rstrip("0").rstrip(".")
    return "/".join(f"{f(x)},{f(y)}" for x, y in wp)


def start_on(streets, J, p):
    """The street nearest p and the arc length there."""
    best = None
    for i in J:
        d, s, _ = project(streets[i]["pts"], p)
        if best is None or d < best[0]:
            best = (d, i, s)
    return best


def cmd_streets(a):
    streets = load_streets()
    near = tuple(map(float, a.near.split(","))) if a.near else None
    for st in streets:
        if near:
            d, s, q = project(st["pts"], near)
            if d > a.radius:
                continue
        xs = [p[0] for p in st["pts"]]; ys = [p[1] for p in st["pts"]]
        print(f"{st['name']!r:28} w={st['width']:<4g} len={st['len']:6.0f}  x {min(xs):.0f}-{max(xs):.0f}  y {min(ys):.0f}-{max(ys):.0f}"
              + (f"  dist {d:.1f} at s={s:.0f}" if near else "") + "  " + " ".join(f"{x:g},{y:g}" for x, y in st["pts"][:8]))


def bbox_around(p, r):
    return (p[0] - r, p[1] - r, p[0] + r, p[1] + r)


def cmd_route(a):
    streets = load_streets()
    p = tuple(map(float, getattr(a, "from").split(",")))
    names = [n.strip() for n in a.via.split(">")]
    J = junctions(streets, bbox_around(p, 3000))
    cur = None
    d0, i0, s0 = None, None, None
    for i in J:
        if streets[i]["name"] == names[0]:
            d, s, _ = project(streets[i]["pts"], p)
            if d0 is None or d < d0:
                d0, i0, s0 = d, i, s
    if i0 is None:
        sys.exit(f"no street named {names[0]!r} near {p}")
    pieces, cur_s = [], s0
    cur = i0
    for nxt in names[1:]:
        cands = [(abs(sa - cur_s), sa, j, sb) for sa, j, sb in J[cur] if streets[j]["name"] == nxt]
        if not cands:
            sys.exit(f"{streets[cur]['name']!r} has no junction with {nxt!r}")
        # the nearest junction with the next street ahead of where we are
        _, sa, j, sb = min(cands)
        pieces.append((cur, cur_s, sa))
        cur, cur_s = j, sb
    L = streets[cur]["len"]
    if a.to:
        # the last street up to the point nearest --to
        end = project(streets[cur]["pts"], tuple(map(float, a.to.split(","))))[1]
        margin = 0
    else:
        # the last street: toward its far end
        end = L if (L - cur_s) >= cur_s else 0.0
        margin = a.end_margin
    pieces.append((cur, cur_s, end))
    wp, pieces = assemble(streets, pieces, margin, J)
    info = describe(streets, pieces, wp, load_towns())
    print(f"path={flag(wp)}")
    print(info["text"])
    if a.json:
        print(json.dumps({"path": flag(wp), **info}))


def enumerate_routes(streets, J, p, turns, min_len, max_len):
    d, i0, s0 = start_on(streets, J, p)
    out = []

    def walk(st, s, direction, pieces, length, changes):
        # the junctions ahead on this street, in travel order, then its end
        L = streets[st]["len"]
        ahead = [(sa, j, sb) for sa, j, sb in J[st] if (sa - s) * direction > 2]
        ahead.sort(key=lambda x: (x[0] - s) * direction)
        end = L if direction > 0 else 0.0
        stops = [(sa, j, sb) for sa, j, sb in ahead] + [(end, None, None)]
        for sa, j, sb in stops:
            seg = abs(sa - s)
            total = length + seg
            if total > max_len:
                # cut the route at max_len on this street
                cut = s + direction * (max_len - length)
                out.append(pieces + [(st, s, cut)])
                return
            here = pieces + [(st, s, sa)]
            if j is None:  # routes end where a street ends (a dead end or a T into another street) or at max_len
                if total >= min_len:
                    out.append(here)
                continue
            if changes >= turns:
                continue
            for dirn in (1, -1):
                Lj = streets[j]["len"]
                room = (Lj - sb) if dirn > 0 else sb
                if room < 15:
                    continue
                walk(j, sb, dirn, here, total, changes + 1)

    for dirn in (1, -1):
        walk(i0, s0, dirn, [], 0.0, 0)
    return out


def cmd_plan(a):
    streets = load_streets()
    towns = load_towns()
    p = tuple(map(float, getattr(a, "from").split(",")))
    J = junctions(streets, bbox_around(p, a.max + 200))
    routes = enumerate_routes(streets, J, p, a.turns, a.min, a.max)
    cands, seen = [], set()
    for pieces in routes:
        wp, pieces2 = assemble(streets, pieces, a.end_margin, J)
        info = describe(streets, pieces2, wp, towns)
        key = info["text"]
        if key in seen or info["length"] < a.min:
            continue
        seen.add(key)
        cands.append({"path": flag(wp), **info, "turns": len(info["legs"]) - 1})
    # fewer legs first, then length: Jev sees at most --limit of them
    cands.sort(key=lambda c: (c["turns"], -c["length"]))
    cands = cands[:a.limit]
    for k, c in enumerate(cands):
        c["id"] = f"r{k + 1}"
    if not cands:
        sys.exit("no route of the requested length from there")
    if a.no_jev:
        for c in cands:
            print(f"{c['id']:>4}  {c['text']}\n      path={c['path']}")
        return
    from typesafe_client import ask, choice, fmt
    state = {"description": a.describe, "start": {"x": p[0], "y": p[1], "street": streets[start_on(streets, J, p)[1]]["name"]},
             "compass": "N = up on the map (y decreasing), S = down (y increasing), E = x increasing, W = x decreasing",
             "candidates": {c["id"]: c["text"] for c in cands}}
    questions = {"route": choice("Which candidate route is the one `description` asks for? Match the order and compass direction "
                                 "of the legs, the relative leg lengths ('a little' = short, 'all' = the rest of the route) and "
                                 "the towns it must pass through.",
                                 {c["id"]: c["text"] for c in cands})}
    log = []
    ans = ask(state, questions, log=log)
    r = ans["route"]
    ranked = sorted(r["probabilities"].items(), key=lambda kv: -kv[1])
    by = {c["id"]: c for c in cands}
    print(f"jev ({log[0]['ms']} ms): {fmt(ans)}")
    for cid, pr in ranked[:5]:
        print(f"  {pr:5.2f}  {cid}: {by[cid]['text']}")
    pick = by[r["choice"]]
    print(f"pick {pick['id']}: {pick['text']}")
    print(f"path={pick['path']}")
    if a.json:
        Path(a.json).write_text(json.dumps({"description": a.describe, "pick": pick, "ranking": ranked, "candidates": cands}, indent=1))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("streets")
    s.add_argument("--near")
    s.add_argument("--radius", type=float, default=60)
    r = sub.add_parser("route")
    r.add_argument("--from", required=True)
    r.add_argument("--via", required=True)
    r.add_argument("--end-margin", type=float, default=12)
    r.add_argument("--to", help="X,Y: end on the last street at the point nearest this (a loop back to a known spot)")
    r.add_argument("--json", action="store_true")
    p = sub.add_parser("plan")
    p.add_argument("--from", required=True)
    p.add_argument("--describe", required=True)
    p.add_argument("--min", type=float, default=300)
    p.add_argument("--max", type=float, default=1500)
    p.add_argument("--turns", type=int, default=2)
    p.add_argument("--limit", type=int, default=24)
    p.add_argument("--end-margin", type=float, default=12)
    p.add_argument("--no-jev", action="store_true")
    p.add_argument("--json")
    a = ap.parse_args()
    {"streets": cmd_streets, "route": cmd_route, "plan": cmd_plan}[a.cmd](a)


if __name__ == "__main__":
    main()
