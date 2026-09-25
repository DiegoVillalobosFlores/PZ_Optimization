#!/usr/bin/env python3
"""Jev directs the explore=restaurant scene (2026-09-25, the flip HDR "north-facing bloom" report).

The game (pzopt.Explore, flags explore=restaurant director=jev) writes the scene's facts every 0.3 s to
~/Zomboid/pzopt-explore-state.json; this loop asks TypeSafe's Jev what the character does next and writes
"<seq> <action>" to ~/Zomboid/pzopt-explore-cmd.txt, which the game carries out until the next command. Jev only chooses;
the walking is the game's own movement keys along a grid path, doors opened on the way. Every decision goes to the log
with Jev's probabilities and latency.

    harness/explore-director.py [--machine flip] [--log /tmp/explore-director.log] [--wait 900]

--machine <m> (a [m] entry of harness/queue/machines.conf): the game runs there; one ssh connection streams its state
file here and carries the commands back (the TypeSafe key stays on this machine). Start it before the run (it waits for a
state file newer than itself); it exits when the state goes stale for 15 s (the run ended).
"""
import argparse
import base64
import configparser
import json
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from typesafe_client import ask, choice  # noqa: E402

ZOMBOID = Path(os.environ.get("ZOMBOID", Path.home() / "Zomboid"))

QUESTION = choice(
    "You direct a character in Project Zomboid who inspects a restaurant, one decision at a time, from the scene "
    "state. The visit goes in this order: walk to the restaurant; in every room of it, first look around once (a full "
    "turn in place), then face north for about four seconds; then walk to the next room not visited yet; when no room is "
    "left to visit, finish. Pick the character's next action.",
    {
        "go_to_restaurant": "Walk to the restaurant from the street. Right only while visited_any_room is false; never "
                            "once any room of the restaurant has been visited (then next_room moves between rooms).",
        "next_room": "Walk to the nearest room of the restaurant not visited yet. Right when rooms are left to visit and "
                     "either the character is outside after the first visit, or in the current room the full turn is "
                     "done (looked_around true) and the character has faced north for at least 4 seconds.",
        "look_around": "Turn once round in place. Right when the character is inside the restaurant and has not "
                       "finished looking around in this room (looked_around false).",
        "face_north": "Turn to face north and stand. Right when the character is inside, has looked around in this room "
                      "(looked_around true) and has faced north here for less than 4 seconds.",
        "hold": "Stand still. Right only while nothing else applies.",
        "done": "Finish the visit. Right when no room is left to visit (rooms_left_to_visit is 0) and the current room is "
                "finished (looked around and faced north for 4 seconds), or the character is outside with no room left.",
    },
)

BRIDGE = r"""
import os, sys, time, select
z = os.path.expanduser('~/Zomboid')
st, cmd = os.path.join(z, 'pzopt-explore-state.json'), os.path.join(z, 'pzopt-explore-cmd.txt')
last = None
while True:
    try:
        m = os.stat(st).st_mtime_ns
        if m != last:
            last = m
            with open(st) as f:
                s = f.read().strip()
            if s:
                sys.stdout.write(s + '\n'); sys.stdout.flush()
    except OSError:
        pass
    r, _, _ = select.select([sys.stdin], [], [], 0.05)
    if r:
        line = sys.stdin.readline()
        if not line:
            break
        with open(cmd + '.tmp', 'w') as f:
            f.write(line)
        os.replace(cmd + '.tmp', cmd)
"""


class Local:
    def __init__(self):
        self.state, self.cmd, self.t0 = ZOMBOID / "pzopt-explore-state.json", ZOMBOID / "pzopt-explore-cmd.txt", time.time()

    def read(self):
        try:
            if self.state.exists() and self.state.stat().st_mtime > self.t0:
                return json.loads(self.state.read_text())
        except (OSError, json.JSONDecodeError):
            pass
        return None

    def send(self, line):
        tmp = self.cmd.with_suffix(".tmp")
        tmp.write_text(line)
        tmp.replace(self.cmd)


class Remote:
    """One ssh connection: the remote bridge prints each new state file, takes one command per stdin line."""

    def __init__(self, machine):
        cfg = configparser.ConfigParser()
        cfg.read(Path(__file__).resolve().parent / "queue" / "machines.conf")
        m = cfg[machine]
        code = base64.b64encode(BRIDGE.encode()).decode()  # one quoting-free word for the remote shell (fish on the flip)
        self.p = subprocess.Popen(["ssh", "-i", os.path.expanduser(m["key"]), "-o", "ServerAliveInterval=5", m["host"], "python3", "-u", "-c",
                                   f"\"import base64;exec(base64.b64decode('{code}'))\""],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
        self.fd = self.p.stdout.fileno()
        os.set_blocking(self.fd, False)
        self.buf, self.t0_ms = "", time.time() * 1000 - 60000  # a state older than a minute is a previous run's

    def read(self):
        latest = None
        try:
            chunk = os.read(self.fd, 1 << 16).decode(errors="replace")
        except BlockingIOError:
            chunk = None
        if chunk:
            self.buf += chunk
            *lines, self.buf = self.buf.split("\n")
            for ln in lines:
                try:
                    s = json.loads(ln)
                    if s["t"] > self.t0_ms:
                        latest = s
                except (json.JSONDecodeError, KeyError):
                    pass
        return latest

    def send(self, line):
        self.p.stdin.write(line)
        self.p.stdin.flush()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--machine", default="desktop")
    ap.add_argument("--log", default="/tmp/explore-director.log")
    ap.add_argument("--wait", type=float, default=900, help="seconds to wait for the game's first state")
    a = ap.parse_args()
    io = Local() if a.machine == "desktop" else Remote(a.machine)
    t0 = time.time()
    seq, last_t, last_seen, decisions, st = 0, None, time.time(), 0, None
    log = open(a.log, "a", buffering=1)
    log.write(f"# explore director started {time.strftime('%Y-%m-%d %H:%M:%S')} on {a.machine}\n")
    while True:
        new = io.read()
        now = time.time()
        if new is not None:
            st, last_seen = new, now
        if st is None:
            if now - t0 > a.wait:
                log.write("# no state from the game, giving up\n")
                return 1
            time.sleep(0.1)
            continue
        if now - last_seen > 15 or isinstance(io, Local) and now * 1000 - st["t"] > 15000:
            log.write(f"# state stale for 15 s: the run ended; {decisions} decisions\n")
            return 0
        if st["t"] == last_t:
            time.sleep(0.05)
            continue
        last_t = st["t"]
        lat = []
        try:
            ans = ask(st, {"action": QUESTION}, log=lat)["action"]
        except Exception as e:  # keep the last command; the next state gets another try
            log.write(f"{st['seconds_since_start']:6.1f}s  jev error: {e}\n")
            time.sleep(0.5)
            continue
        act = ans["choice"]
        seq += 1
        decisions += 1
        io.send(f"{seq} {act}\n")
        probs = " ".join(f"{k}={v:.2f}" for k, v in sorted(ans.get("probabilities", {}).items(), key=lambda kv: -kv[1]))
        p, r, h = st["player"], st["restaurant"], st["this_room"]
        log.write(f"{st['seconds_since_start']:6.1f}s  {act:<17} conf {ans.get('confidence', 0):.2f}  {lat[0]['ms'] if lat else '?'} ms | "
                  f"room {p['current_room']} inside {p['inside_restaurant']} dist {p['distance_to_restaurant_tiles']:.0f} facing {p['facing']} "
                  f"visited {r['rooms_visited']}/{r['rooms_total']} left {r['rooms_left_to_visit']} looked {h['looked_around']} "
                  f"({h['degrees_turned']:.0f} deg) north {h['seconds_facing_north']:.1f}s | {probs}\n")


if __name__ == "__main__":
    sys.exit(main())
