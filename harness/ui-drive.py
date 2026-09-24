#!/usr/bin/env python3
"""Drive the game's own UI with OCR + TypeSafe (Jev) instead of an LLM looking at screenshots.

  harness/ui-drive.py step "<expected screen>" "<control to click>" [--screen shot.png] [--dry-run]
  harness/ui-drive.py read [--screen shot.png]          # OCR lines with screen coordinates

Every step is the same loop, ~1.5 s instead of an LLM round trip: screenshot (spectacle) -> the
active window must be the game (xdotool, deterministic; the 2026-09-21 retry typed into the
desktop after a focus loss) -> tesseract lines with boxes -> one Jev request: is the expected
screen up (noul), which line is the control (choice over the OCR lines + none), is an error /
failure text showing (noul) -> press-and-release at the line's centre (the game only hovers on a
plain click), pointer coordinates = screen px / --pointer-scale (1.25 on this KDE/XWayland
desktop). A step with a `fallback` uses the release-windows skill's known coordinates when Jev
finds no line (native dialogs, unreadable buttons) but only when the expected screen is confirmed.
Jev never sees pixels; it sees the OCR text and positions. A step that cannot be confirmed
stops the sequence (exit 2) with the screenshot path printed; nothing is retried blindly.

The Workshop upload no longer goes through here (2026-09-24): scripts/workshop-upload.py calls the
Steamworks API directly, no game and no OCR. The pad checks use `read`.
"""
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from typesafe_client import ask, choice, noul  # noqa: E402

SHOT = Path("/tmp/ui-drive.png")
TESSDATA = Path.home() / ".local/share/tessdata"
WINDOW_TITLE = "Project Zomboid"


def screenshot(path=SHOT):
    subprocess.run(["spectacle", "-b", "-n", "-f", "-o", str(path)], check=True, capture_output=True, timeout=20)
    return path


def active_window():
    try:
        return subprocess.run(["xdotool", "getactivewindow", "getwindowname"], capture_output=True, text=True, timeout=5).stdout.strip()
    except subprocess.SubprocessError:
        return ""


OCR_UP = 2  # the game's UI text is ~20 px tall at 5120x2160: inverted (dark on light) and doubled it reads cleanly


def ocr_lines(png, min_conf=40):
    """OCR'd text lines with their centre in screen pixels: [{id, text, x, y, w, h}]."""
    from PIL import Image, ImageOps
    im = ImageOps.invert(Image.open(png).convert("L"))
    im = im.resize((im.width * OCR_UP, im.height * OCR_UP), Image.LANCZOS)
    prep = Path(str(png) + ".ocr.png")
    im.save(prep)
    env = dict(os.environ, TESSDATA_PREFIX=str(TESSDATA)) if (TESSDATA / "eng.traineddata").exists() else os.environ
    tsv = subprocess.run(["tesseract", str(prep), "-", "-l", "eng", "--psm", "11", "tsv"], env=env,
                         capture_output=True, text=True, check=True).stdout.splitlines()
    hdr = tsv[0].split("\t")
    groups = {}
    for row in tsv[1:]:
        d = dict(zip(hdr, row.split("\t")))
        if d.get("level") != "5" or not d.get("text", "").strip() or float(d["conf"]) < min_conf:
            continue
        key = (d["block_num"], d["par_num"], d["line_num"])
        g = groups.setdefault(key, {"words": [], "x0": 1e9, "y0": 1e9, "x1": 0, "y1": 0})
        l, t, w, h = (int(d[k]) // OCR_UP for k in ("left", "top", "width", "height"))
        g["words"].append(d["text"])
        g["x0"], g["y0"], g["x1"], g["y1"] = min(g["x0"], l), min(g["y0"], t), max(g["x1"], l + w), max(g["y1"], t + h)
    lines = []
    for g in groups.values():
        text = " ".join(g["words"]).strip()
        if len(re.sub(r"[^A-Za-z]", "", text)) < 2:  # overlay numbers, stray glyphs
            continue
        lines.append({"text": text, "x": (g["x0"] + g["x1"]) // 2, "y": (g["y0"] + g["y1"]) // 2,
                      "w": g["x1"] - g["x0"], "h": g["y1"] - g["y0"]})
    lines.sort(key=lambda L: (L["y"], L["x"]))
    for i, L in enumerate(lines):
        L["id"] = f"L{i}"
    return lines


def judge_screen(lines, expected, target, extra=None):
    """One Jev request over the OCR lines: expected screen up?, which line is the control, error showing?"""
    cands = {L["id"]: f"{L['text']!r} at x={L['x']} y={L['y']} ({'left' if L['x'] < 1700 else 'centre' if L['x'] < 3400 else 'right'} "
                     f"{'top' if L['y'] < 720 else 'middle' if L['y'] < 1440 else 'bottom'})" for L in lines[:80]}
    cands["none"] = "no OCR line is that control (it may be unreadable, an icon, or not on this screen)"
    # the control name lives only in the target question: in the shared state it made Jev doubt a
    # correct screen whenever the control was not in the OCR (the item row under the overlay: 0.53 -> 0.74)
    state = {"screen_lines": [f"{L['id']}: {L['text']}  (x={L['x']}, y={L['y']})" for L in lines[:80]],
             "screen_size": "5120x2160, y grows downwards; the top-left corner holds a performance overlay whose lines are not controls",
             "expected_screen": expected}
    if extra:
        state.update(extra)
    q = {"on_screen": noul("Judging only from `screen_lines` (OCR of the current screen, may contain small misreads and noise lines), is `expected_screen` "
                           "what is showing? The screen's title line (top centre, y around 140) is the strongest evidence: a matching title with the other "
                           "lines missing or garbled still counts as showing; a different title or a different set of menu entries does not."),
         "target": choice({"control": target,
                           "question": "Which line is the control named in `control`? OCR may drop or swap a letter; a button label matches when the "
                                       "words are recognisably the same. Pick the line that IS the control, not a heading or a sentence that mentions it."},
                          cands),
         "error": noul("Does `screen_lines` contain an error or failure message from the game or Steam about the current action "
                       "(failed, error, invalid, no connection, could not)? Text the user typed into a change-notes box does not count.")}
    return ask(state, q)


def press(x, y, scale, hold=0.15):
    px, py = int(x / scale), int(y / scale)
    subprocess.run(["xdotool", "mousemove", str(px), str(py)], check=True)
    time.sleep(0.2)
    subprocess.run(["xdotool", "mousedown", "1"], check=True)
    time.sleep(hold)
    subprocess.run(["xdotool", "mouseup", "1"], check=True)


def run_step(step, opts, screen=None):
    """Screenshot -> focus -> OCR -> Jev -> click. Returns (ok, detail)."""
    name, expected, target = step["name"], step["expect"], step.get("click")
    t0 = time.time()
    dialog = None
    if step.get("native_dialog") and not screen and not opts["dry_run"]:
        # a dialog of another process may open behind a desktop-sized game window (and its size and place vary):
        # find it by title and raise it before the screenshot, so the OCR reads its buttons
        dialog = find_window(step["native_dialog"], step.get("dialog_timeout", 8.0))
        if dialog:
            subprocess.run(["xdotool", "windowactivate", "--sync", dialog], check=False, capture_output=True)
            time.sleep(0.5)
    png = Path(screen) if screen else screenshot()
    if not screen and not opts["no_focus"] and not step.get("focus_any"):
        title = active_window()
        if WINDOW_TITLE.lower() not in title.lower():
            return False, f"active window is {title!r}, not the game; no click sent"
    lines = ocr_lines(png)
    ans = judge_screen(lines, expected, target or "(nothing: this step only checks the screen)", step.get("extra"))
    on, err = ans["on_screen"]["noul"], ans["error"]["noul"]
    tgt = ans["target"]
    pick = next((L for L in lines if L["id"] == tgt["choice"]), None)
    detail = (f"[{name}] screen {on:.2f} error {err:.2f} target {tgt['choice']} {tgt['confidence']:.2f}"
              + (f" -> {pick['text']!r} ({pick['x']},{pick['y']})" if pick else "") + f"  ({time.time() - t0:.1f}s, {len(lines)} lines)")
    if err >= 0.6:
        return False, detail + "\n  an error text is showing: " + "; ".join(L["text"] for L in lines if re.search(r"fail|error|invalid|connect", L["text"], re.I))[:300]
    strong_target = pick is not None and tgt["confidence"] >= 0.9
    if on < opts["screen_threshold"] and not (on >= 0.35 and strong_target):
        # a 0.9+ match of the very control we expect corroborates a hesitant screen judgment; a
        # screen judged clearly wrong (the negatives score ~0.02) never clicks
        return False, detail + f"\n  expected screen not confirmed (need >= {opts['screen_threshold']}); screenshot {png}"
    if not target:
        if step.get("type_at") and not opts["dry_run"]:
            press(*step["type_at"], opts["scale"])
        return True, detail
    if pick and tgt["confidence"] >= opts["target_threshold"]:
        x, y = pick["x"], pick["y"]
    elif dialog and step.get("dialog_key"):
        if opts["dry_run"]:
            return True, detail + f"\n  dry run: would send {step['dialog_key']} to window {dialog}"
        subprocess.run(["xdotool", "key", "--window", dialog, step["dialog_key"]], check=False)
        return True, detail + f"\n  no readable control; sent {step['dialog_key']} to the dialog window {dialog} (its default button)"
    elif step.get("fallback") and (step.get("fallback_when") is None or step["fallback_when"]()):
        x, y = step["fallback"]
        detail += f"\n  no readable control, using the skill's coordinates ({x},{y})" + (" (condition met)" if step.get("fallback_when") else "")
    else:
        return False, detail + "\n  control not found on this screen; stopping"
    if opts["dry_run"]:
        return True, detail + f"\n  dry run: would press at screen ({x},{y}) = pointer ({int(x / opts['scale'])},{int(y / opts['scale'])})"
    press(x, y, opts["scale"])
    return True, detail


def find_window(title, timeout):
    """The id of a visible window whose title contains `title`, waiting up to `timeout` s; None when none shows."""
    end = time.time() + timeout
    while True:
        r = subprocess.run(["xdotool", "search", "--onlyvisible", "--name", re.escape(title)], capture_output=True, text=True)
        ids = r.stdout.split()
        if ids:
            return ids[-1]
        if time.time() >= end:
            return None
        time.sleep(0.5)


def main(argv):
    if not argv:
        sys.exit(__doc__)
    opts = {"dry_run": "--dry-run" in argv, "no_focus": "--no-focus-check" in argv, "scale": 1.25,
            # replays of the 2026-09-21 deploy screens: right screens 0.55-0.9 (+-0.1 between runs), wrong screens 0.01-0.09
            "screen_threshold": 0.5, "target_threshold": 0.5}
    screen = None
    args = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--screen":
            screen = argv[i + 1]; i += 2
        elif a == "--pointer-scale":
            opts["scale"] = float(argv[i + 1]); i += 2
        elif a.startswith("--"):
            i += 1
        else:
            args.append(a); i += 1
    cmd = args[0]
    if cmd == "read":
        for L in ocr_lines(Path(screen) if screen else screenshot()):
            print(f"{L['id']:5s} ({L['x']:5d},{L['y']:5d}) {L['text']}")
        return 0
    if cmd == "step":
        ok, detail = run_step({"name": "step", "expect": args[1], "click": args[2] if len(args) > 2 else None}, opts, screen)
        print(detail)
        return 0 if ok else 2
    sys.exit(__doc__)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
