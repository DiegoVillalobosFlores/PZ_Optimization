#!/usr/bin/env python3
"""Virtual Xbox 360 pad (uinput) that drives the game's menus from a script.

  harness/pad.py serve <fifo>   # creates the pad, runs the commands written to the fifo until "quit"

Commands, one per line: a b x y start back lb rb l3 r3 up down left right (a press: down, 0.12 s, up; the game
samples the pad once per frame), "hold <button> <s>", "chord <button> <button> [s]" (both down together, held s,
default 0.12, both up together), "sleep <s>", "mark <name>", "done", "quit". Every press, hold, mark and
"done" is printed with its epoch ms ("press down t=<ms> up=<ms>") so harness/padlat.py can line the presses up with
the game's own log (run.sh --pad). The device is the xpad layout (vendor 045e product 028e,
buttons BTN_A..BTN_THUMBR, sticks/triggers on ABS_X..ABS_RZ, D-pad on HAT0), so GLFW's GUID is
030000005e0400008e02000010010000 and media/gamecontrollerdb.txt maps it (dpad = h0.x, a = b0, b = b1).
"""
import sys
import time

from evdev import AbsInfo, UInput, ecodes as e

BUTTONS = {"a": e.BTN_A, "b": e.BTN_B, "x": e.BTN_X, "y": e.BTN_Y, "start": e.BTN_START, "back": e.BTN_SELECT,
           "lb": e.BTN_TL, "rb": e.BTN_TR, "l3": e.BTN_THUMBL, "r3": e.BTN_THUMBR}
HATS = {"up": (e.ABS_HAT0Y, -1), "down": (e.ABS_HAT0Y, 1), "left": (e.ABS_HAT0X, -1), "right": (e.ABS_HAT0X, 1)}


def make_pad():
    stick = AbsInfo(0, -32768, 32767, 16, 128, 0)
    trigger = AbsInfo(0, 0, 255, 0, 0, 0)
    hat = AbsInfo(0, -1, 1, 0, 0, 0)
    cap = {
        e.EV_KEY: [e.BTN_A, e.BTN_B, e.BTN_X, e.BTN_Y, e.BTN_TL, e.BTN_TR, e.BTN_SELECT, e.BTN_START, e.BTN_MODE,
                   e.BTN_THUMBL, e.BTN_THUMBR],
        e.EV_ABS: [(e.ABS_X, stick), (e.ABS_Y, stick), (e.ABS_Z, trigger), (e.ABS_RX, stick), (e.ABS_RY, stick),
                   (e.ABS_RZ, trigger), (e.ABS_HAT0X, hat), (e.ABS_HAT0Y, hat)],
    }
    return UInput(cap, name="Microsoft X-Box 360 pad", vendor=0x045E, product=0x028E, version=0x0110, bustype=e.BUS_USB)


def now_ms():
    return int(time.time() * 1000)


def press(ui, name, hold=0.12):
    """Presses name for hold seconds; returns the epoch ms of the down and the up event (None for an unknown name)."""
    if name in BUTTONS:
        ui.write(e.EV_KEY, BUTTONS[name], 1)
        ui.syn()
        t0 = now_ms()
        time.sleep(hold)
        ui.write(e.EV_KEY, BUTTONS[name], 0)
        ui.syn()
        return t0, now_ms()
    elif name in HATS:
        axis, value = HATS[name]
        ui.write(e.EV_ABS, axis, value)
        ui.syn()
        t0 = now_ms()
        time.sleep(hold)
        ui.write(e.EV_ABS, axis, 0)
        ui.syn()
        return t0, now_ms()
    print("unknown command:", name, flush=True)
    return None


def chord(ui, names, hold=0.12):
    """Presses the buttons together for hold seconds; returns the epoch ms of the down and the up event (None for an unknown name)."""
    if not all(n in BUTTONS for n in names):
        print("unknown chord:", " ".join(names), flush=True)
        return None
    for n in names:
        ui.write(e.EV_KEY, BUTTONS[n], 1)
    ui.syn()
    t0 = now_ms()
    time.sleep(hold)
    for n in names:
        ui.write(e.EV_KEY, BUTTONS[n], 0)
    ui.syn()
    return t0, now_ms()


def serve(fifo):
    ui = make_pad()
    print("pad ready:", ui.device.path, flush=True)
    try:
        while True:
            with open(fifo) as f:
                for line in f:
                    parts = line.split()
                    if not parts:
                        continue
                    if parts[0] == "quit":
                        print("pad quit", flush=True)
                        return
                    if parts[0].startswith("#"):
                        continue
                    if parts[0] == "sleep":
                        time.sleep(float(parts[1]))
                        continue
                    if parts[0] in ("mark", "done"):
                        print(parts[0], " ".join(parts[1:]), "t=%d" % now_ms(), flush=True)
                        continue
                    if parts[0] == "hold":              # hold <button> <seconds>
                        t = press(ui, parts[1], float(parts[2]))
                        if t:
                            print("hold", parts[1], "t=%d up=%d" % t, flush=True)
                        continue
                    if parts[0] == "chord":             # chord <button> <button> [seconds]
                        t = chord(ui, parts[1:3], float(parts[3]) if len(parts) > 3 else 0.12)
                        if t:
                            print("chord", parts[1], parts[2], "t=%d up=%d" % t, flush=True)
                        continue
                    t = press(ui, parts[0])
                    if t:
                        print("press", parts[0], "t=%d up=%d" % t, flush=True)
    finally:
        ui.close()


if __name__ == "__main__":
    if len(sys.argv) in (3, 4) and sys.argv[1] == "serve":  # run.sh also passes the flag file (inputlag-drive.py uses it)
        serve(sys.argv[2])
    else:
        print(__doc__)
        sys.exit(2)
