#!/usr/bin/env python3
"""Jev directs the showcase=horde scene (2026-09-24, the HDR horde video).

The game (pzopt.Showcase, flag director=jev) writes the scene's facts every 0.3 s to ~/Zomboid/pzopt-showcase-state.json;
this loop asks TypeSafe's Jev which action the character takes next and writes "<seq> <action>" to
~/Zomboid/pzopt-showcase-cmd.txt, which the game carries out until the next command. Jev only chooses; the moves are the
game's own (movement keys, the aim / fire buttons, the reload action). Every decision goes to the log with Jev's
probabilities and latency.

    harness/showcase-director.py [--log /tmp/showcase-director.log] [--wait 900]

Start it before the run (it waits for a state file newer than itself) and it exits when the state goes stale for 15 s
(the run ended).
"""
import argparse
import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from typesafe_client import ask, choice  # noqa: E402

ZOMBOID = Path(os.environ.get("ZOMBOID", Path.home() / "Zomboid"))
STATE = ZOMBOID / "pzopt-showcase-state.json"
CMD = ZOMBOID / "pzopt-showcase-cmd.txt"

QUESTION = choice(
    "You direct a short cinematic last stand in Project Zomboid, one decision at a time, from the scene state. The scene "
    "plays out in this order: the character sprints to the pier; at the pier the character turns to face the burning "
    "horde that chases them; then lights a line of fires across the horde's path; then fires the M16 at the horde, "
    "reloading whenever the magazine runs dry, until the horde is gone. Pick the character's next action.",
    {
        "run_to_pier": "Sprint to the pier. Right while the character is not at the pier yet.",
        "face_horde": "Turn to face the nearest zombie. Right when the character is at the pier, the fire line is not lit "
                      "yet and the angle between the facing and the nearest zombie is over 20 degrees.",
        "light_fire_line": "Light the line of fires across the horde's path. Right when the character is at the pier, "
                           "faces the horde (angle under 20 degrees) and the fire line is not lit.",
        "shoot": "Aim and fire the M16 at the nearest zombie. Right when the character is at the pier, the fire line is "
                 "lit, zombies are alive and the magazine has rounds or a round is chambered.",
        "reload": "Reload from a spare magazine. Right when the magazine has no rounds and no round is chambered, spare "
                  "magazines remain and no reload is in progress.",
        "hold": "Stand still. Right when no zombies are left, or while a reload is in progress.",
    },
)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", default="/tmp/showcase-director.log")
    ap.add_argument("--wait", type=float, default=900, help="seconds to wait for the game's first state")
    a = ap.parse_args()
    t0 = time.time()
    seq, last_t, seen, decisions = 0, None, False, 0
    log = open(a.log, "a", buffering=1)
    log.write(f"# director started {time.strftime('%Y-%m-%d %H:%M:%S')}, waiting for {STATE}\n")
    while True:
        try:
            fresh = STATE.exists() and STATE.stat().st_mtime > t0
            st = json.loads(STATE.read_text()) if fresh else None
        except (OSError, json.JSONDecodeError):
            st = None
        now = time.time()
        if st is None:
            if not seen and now - t0 > a.wait:
                log.write("# no state from the game, giving up\n")
                return 1
            time.sleep(0.2)
            continue
        if seen and now * 1000 - st["t"] > 15000:
            log.write(f"# state stale for 15 s: the run ended; {decisions} decisions\n")
            return 0
        if st["t"] == last_t:
            time.sleep(0.05)
            continue
        seen, last_t = True, st["t"]
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
        tmp = CMD.with_suffix(".tmp")
        tmp.write_text(f"{seq} {act}\n")
        tmp.replace(CMD)
        probs = " ".join(f"{k}={v:.2f}" for k, v in sorted(ans.get("probabilities", {}).items(), key=lambda kv: -kv[1]))
        p, h, w = st["player"], st["horde"], st["weapon"]
        log.write(f"{st['seconds_since_start']:6.1f}s  {act:<15} conf {ans.get('confidence', 0):.2f}  {lat[0]['ms'] if lat else '?'} ms | "
                  f"pier {p['distance_to_pier_tiles']:.1f} face {p['degrees_between_facing_and_nearest_zombie']:.0f} "
                  f"zombies {h['zombies_alive']} near {h['nearest_zombie_tiles']:.1f} mag {w['rounds_in_magazine']}"
                  f"{'+1' if w['round_chambered'] else ''} spare {w['spare_full_magazines']} reloading {w['reloading']} "
                  f"fire {st['fire_line_lit']} | {probs}\n")


if __name__ == "__main__":
    sys.exit(main())
