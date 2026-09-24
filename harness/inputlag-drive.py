#!/usr/bin/env python3
"""Input-lag driver: a uinput keyboard + mouse and an Xbox 360 pad that play a script into the game (run.sh --inputlag).

  harness/inputlag-drive.py serve <fifo> <flag file>

Both devices exist before the launch (GLFW scans pads at start-up; the pad is pad.py's xpad layout and GUID). Script
commands, one per line (harness/inputlag/*.txt):
  key <w|s|a|d> <hold s>        press a key, hold, release
  mouse <left|right> <hold s>   press a mouse button, hold, release
  mdown <left|right> / mup <left|right>
  mmove <dx> <dy>               one relative pointer move (libinput accelerates it; +-8000 clamps at the screen edge)
  stick <l|r> <x> <y> <hold s>  put a stick at x, y (-1..1, y down), hold, back to centre
  sset <l|r> <x> <y>            put a stick there and leave it (a change of direction while held)
  pad <a|b|x|y|lb|rb|lt|rt> <hold s>  (lt aims while the right stick is off centre, rt attacks)
  pdown <lt|rt> / pup <lt|rt>
  sleep <s> | sleep <a>-<b>     fixed or uniform random wait (random gaps keep the presses off the frame phase)
  repeat <n> ... end            (nestable)
  activate                      raise the game window (xdotool windowactivate)
  flag <k=v>                    append a line to the harness flag file (inputlag_pad=1: the Lua gives the pad to player 1)
  mark <name> / done
Every injected change is printed with the epoch microseconds of its SYN ("in <device>:<what> <value> t=<us>"), the clock
pzopt.InputLag stamps with; harness/inputlag.py lines them up with the game's stages.

Keyboard and mouse events go to whatever window has focus, so each is sent only while the focused X window is the game
("Project Zomboid"); otherwise it is logged as "skip unfocused", and after 5 skips in a row the script stops ("abort").
"""
import random
import subprocess
import sys
import time

from evdev import UInput, ecodes as e

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from pad import make_pad  # noqa: E402

KEYS = {"w": e.KEY_W, "s": e.KEY_S, "a": e.KEY_A, "d": e.KEY_D}
MBTN = {"left": e.BTN_LEFT, "right": e.BTN_RIGHT}
PAD_BTN = {"a": e.BTN_A, "b": e.BTN_B, "x": e.BTN_X, "y": e.BTN_Y, "lb": e.BTN_TL, "rb": e.BTN_TR}
STICK = {"l": (e.ABS_X, e.ABS_Y), "r": (e.ABS_RX, e.ABS_RY)}


def now_us():
    return time.time_ns() // 1000


class Driver:
    def __init__(self, flag_file):
        self.flag_file = flag_file
        self.kbm = UInput({e.EV_KEY: list(KEYS.values()) + [e.BTN_LEFT, e.BTN_RIGHT, e.BTN_MIDDLE],
                           e.EV_REL: [e.REL_X, e.REL_Y, e.REL_WHEEL]}, name="pzopt input-lag kbm")
        self.pad = make_pad()
        self.skips = 0
        self.aborted = False

    def log(self, *parts):
        print(*parts, flush=True)

    def focused(self):
        try:
            name = subprocess.run(["xdotool", "getactivewindow", "getwindowname"], capture_output=True, text=True,
                                  timeout=3).stdout.strip()
        except (OSError, subprocess.TimeoutExpired):
            name = ""
        if name == "Project Zomboid":
            self.skips = 0
            return True
        self.skips += 1
        self.log("skip unfocused (focused: %r) t=%d" % (name, now_us()))
        if self.skips >= 5:
            self.log("abort: the game is not the focused window")
            self.aborted = True
        return False

    def emit(self, ui, etype, code, value, what):
        ui.write(etype, code, value)
        ui.syn()
        t = now_us()
        self.log("in %s %d t=%d" % (what, value, t))
        return t

    def run(self, cmds):
        i = 0
        while i < len(cmds) and not self.aborted:
            parts = cmds[i]
            op = parts[0]
            if op == "repeat":
                depth, j = 1, i + 1
                while depth:
                    depth += {"repeat": 1, "end": -1}.get(cmds[j][0], 0)
                    j += 1
                body = cmds[i + 1:j - 1]
                for _ in range(int(parts[1])):
                    self.run(body)
                    if self.aborted:
                        break
                i = j
                continue
            self.step(parts)
            i += 1

    def step(self, p):
        op = p[0]
        if op == "sleep":
            if "-" in p[1]:
                a, b = map(float, p[1].split("-"))
                time.sleep(random.uniform(a, b))
            else:
                time.sleep(float(p[1]))
        elif op in ("mark", "done"):
            self.log(op, " ".join(p[1:]), "t=%d" % now_us())
        elif op == "activate":  # raise the game window once (the run was announced; it has focus after launch anyway)
            subprocess.run(["xdotool", "search", "--name", "^Project Zomboid$", "windowactivate", "--sync"],
                           capture_output=True, timeout=5)
            self.log("activate t=%d" % now_us())
        elif op == "flag":
            with open(self.flag_file, "a") as f:
                f.write(p[1] + "\n")
            self.log("flag", p[1], "t=%d" % now_us())
        elif op == "key":
            if self.focused():
                self.emit(self.kbm, e.EV_KEY, KEYS[p[1]], 1, "key:" + p[1])
                time.sleep(float(p[2]))
                self.emit(self.kbm, e.EV_KEY, KEYS[p[1]], 0, "key:" + p[1])
        elif op == "mouse":
            if self.focused():
                self.emit(self.kbm, e.EV_KEY, MBTN[p[1]], 1, "mouse:" + p[1])
                time.sleep(float(p[2]))
                self.emit(self.kbm, e.EV_KEY, MBTN[p[1]], 0, "mouse:" + p[1])
        elif op in ("mdown", "mup"):
            if op == "mup" or self.focused():  # a release always goes out
                self.emit(self.kbm, e.EV_KEY, MBTN[p[1]], 1 if op == "mdown" else 0, "mouse:" + p[1])
        elif op == "mmove":
            if self.focused():
                dx, dy = int(p[1]), int(p[2])
                if dx:
                    self.kbm.write(e.EV_REL, e.REL_X, dx)
                if dy:
                    self.kbm.write(e.EV_REL, e.REL_Y, dy)
                self.kbm.syn()
                self.log("in mmove:%d,%d 1 t=%d" % (dx, dy, now_us()))
        elif op == "stick":
            ax, ay = STICK[p[1]]
            x, y = float(p[2]), float(p[3])
            self.pad.write(e.EV_ABS, ax, int(x * 32767))
            self.pad.write(e.EV_ABS, ay, int(y * 32767))
            self.pad.syn()
            self.log("in stick:%s %s,%s t=%d" % (p[1], p[2], p[3], now_us()))
            time.sleep(float(p[4]))
            self.pad.write(e.EV_ABS, ax, 0)
            self.pad.write(e.EV_ABS, ay, 0)
            self.pad.syn()
            self.log("in stick:%s 0,0 t=%d" % (p[1], now_us()))
        elif op == "sset":  # sset <l|r> <x> <y>: put a stick there and leave it
            ax, ay = STICK[p[1]]
            self.pad.write(e.EV_ABS, ax, int(float(p[2]) * 32767))
            self.pad.write(e.EV_ABS, ay, int(float(p[3]) * 32767))
            self.pad.syn()
            self.log("in stick:%s %s,%s t=%d" % (p[1], p[2], p[3], now_us()))
        elif op in ("pdown", "pup"):  # pdown / pup <lt|rt>: hold or release a trigger
            axis = e.ABS_Z if p[1] == "lt" else e.ABS_RZ
            self.emit(self.pad, e.EV_ABS, axis, 255 if op == "pdown" else 0, "pad:" + p[1])
        elif op == "pad":
            if p[1] in ("lt", "rt"):
                axis = e.ABS_Z if p[1] == "lt" else e.ABS_RZ
                self.emit(self.pad, e.EV_ABS, axis, 255, "pad:" + p[1])
                time.sleep(float(p[2]))
                self.emit(self.pad, e.EV_ABS, axis, 0, "pad:" + p[1])
            else:
                self.emit(self.pad, e.EV_KEY, PAD_BTN[p[1]], 1, "pad:" + p[1])
                time.sleep(float(p[2]))
                self.emit(self.pad, e.EV_KEY, PAD_BTN[p[1]], 0, "pad:" + p[1])
        else:
            self.log("unknown command:", " ".join(p))

    def close(self):
        for ui in (self.kbm, self.pad):
            ui.close()


def serve(fifo, flag_file):
    d = Driver(flag_file)
    print("pad ready: kbm %s, pad %s" % (d.kbm.device.path, d.pad.device.path), flush=True)
    try:
        while True:
            with open(fifo) as f:
                cmds = [ln.split() for ln in f if ln.split() and not ln.lstrip().startswith("#")]
            if any(c[0] == "quit" for c in cmds):
                return
            d.run(cmds)
            if d.aborted:
                print("done aborted t=%d" % now_us(), flush=True)
    finally:
        d.close()


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "serve":
        serve(sys.argv[2], sys.argv[3])
    else:
        print(__doc__)
        sys.exit(2)
