#!/usr/bin/env python3
"""Render docs/workshop/images/34-faster-local-updates.gif: the Workshop's "New! Faster local updates" card in the
animated New! format (harness/newcard.py). Right half: the previous updater vs this release
(docs/findings-updater-2026-09-26.md: UpdaterBench on the MacBook and the desktop, the Mac in-game e2e). Left half:
pressing Update now on the MacBook, replayed in real time from those measured times: the update dialog of the previous
updater (download the 59 MB zip, unpack and move all 613 files: 2,164 ms) above this release's (the changed files were
fetched in the background when the update was offered; the click writes them: 4.7 ms, then Restart game).

    harness/queue.sh submit media --label updater-card-gif --out docs/workshop/images/34-faster-local-updates.gif \\
        -- python3 harness/updater-fast-card-gif.py
    (--still <png>: one frame at 1.2 s; the layout check)
"""
import os
import shutil
import sys
import tempfile
from pathlib import Path

from PIL import ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from newcard import BG, INK, INK2, MUTED, OPT, RING, RULE, STOCK, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/34-faster-local-updates.gif"
FPS = 20
OLD_MS, NEW_MS = 2164.0, 4.7     # MacBook, click -> files installed, one release apart (UpdaterBench medians)
SPAN, HOLD = 2.6, 2.4            # seconds replayed, seconds held at the end

INTRO = ("Update now used to download the whole 59 MB release and unpack every file. Now the check runs while the "
         "game boots, only the files that changed are fetched (read out of the release zip, in the background as soon "
         "as an update is offered), and Update now just writes them. Subscribers can update from the Workshop copy "
         "Steam already downloaded, even where GitHub is blocked, and Restart game brings the game back updated.")
ROWS = [
    ("Update now until installed", "MacBook (M1 Pro, Wi-Fi), a release apart",
     (OLD_MS, "2,164 ms"), (NEW_MS, "4.7 ms"), "-99.8 %"),
    ("Same on the desktop", "16 cores, gigabit, a release apart",
     (1233, "1,233 ms"), (1.1, "1.1 ms"), "-99.9 %"),
    ("Downloaded per update", "a release apart: 3 of 613 files changed",
     (59.0, "59 MB"), (0.057, "57 KB"), "-99.9 %"),
    ("Update check", "once per boot, ready when the menu shows",
     (139, "139 ms"), (69, "69 ms"), "-50 %"),
    ("GitHub blocked, subscribed", "the Workshop copy already on disk",
     (None, "no update"), (None, "42 ms, no download"), "new"),
]
FOOTER = [
    "Left: pressing Update now, replayed in real time from the times measured on the MacBook, the previous updater "
    "vs this release, one release apart. After the update, Restart game closes the game and starts it again.",
    "On by default: Options > Optimizations > Updates (background fetch and the Workshop copy can be switched off there).",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-updater-2026-09-26.md.",
]


def dialog(d, x, y, w, h, name, colour, t_ms, done_ms, busy_text, done_text, button):
    """A small copy of the game's update dialog (black panel, white border, the progress bar above the button)."""
    d.rectangle((x, y, x + w, y + h), fill=(0, 0, 0), outline=(150, 150, 150), width=1)
    d.text((x + 16, y + 26), "PZ Optimization update", font=font(22, "semibold"), fill=INK, anchor="lm")
    d.text((x + w - 16, y + 26), name, font=font(18, "semibold"), fill=colour, anchor="rm")
    frac = min(1.0, max(0.0, t_ms / done_ms)) if t_ms >= 0 else 0.0
    done = t_ms >= done_ms
    text = done_text if done else (busy_text if t_ms >= 0 else "a newer build is available")
    d.text((x + 16, y + 66), text, font=font(19), fill=INK2 if not done else colour, anchor="lm")
    by, bw = y + 100, w - 32
    d.rectangle((x + 16, by, x + 16 + bw, by + 18), outline=(160, 160, 160), width=1)
    if frac > 0:
        d.rectangle((x + 17, by + 1, x + 17 + (bw - 2) * frac, by + 17), fill=colour if done else (90, 140, 230))
    label = "installed" if done else (f"{frac * 100:.0f} %" if t_ms >= 0 else "")
    d.text((x + 16 + bw / 2, by + 9), label, font=font(16, "semibold"), fill=INK, anchor="mm")
    bx0, bx1 = x + w / 2 - 90, x + w / 2 + 90
    d.rounded_rectangle((bx0, y + h - 46, bx1, y + h - 14), radius=4, outline=(200, 200, 200), width=1,
                        fill=(35, 60, 35) if done else (30, 30, 30))
    d.text(((bx0 + bx1) / 2, y + h - 30), button if done else "Update now", font=font(18, "semibold"), fill=INK, anchor="mm")
    ms = min(t_ms, done_ms) if t_ms >= 0 else 0.0
    d.text((x + w - 16, y + 66), f"{ms:,.1f} ms" if ms < 100 else f"{ms:,.0f} ms", font=font(24, "bold", mono=True),
           fill=colour, anchor="rm")


def main():
    card = Card("New! Faster local updates", "2026-09-26", INTRO, ROWS, FOOTER, cols=("BEFORE", "THIS RELEASE"))
    x, y, w, h = card.media
    still = sys.argv[2] if len(sys.argv) > 2 and sys.argv[1] == "--still" else None
    lead = 0.3   # seconds before the click
    n = round((SPAN + lead) * FPS)
    times = [1.2] if still else [i / FPS - lead for i in range(n)] + [SPAN] * round(HOLD * FPS)
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-upd-"))
    dh = 230
    gap = (h - 70 - 2 * dh) / 3
    for k, now in enumerate(times):
        im = card.base()
        d = ImageDraw.Draw(im)
        d.rectangle((x, y, x + w - 1, y + h - 1), fill=BG)
        t_ms = now * 1000.0
        clock = "click Update now" if now < 0 else f"{now:5.2f} s after the click"
        d.text((x + 8, y + 30), clock, font=font(24, "semibold", mono=True), fill=INK, anchor="lm")
        d.line((x + 8, y + 56, x + w - 8, y + 56), fill=RULE, width=1)
        y1 = y + 70 + gap
        dialog(d, x + 8, y1, w - 16, dh, "BEFORE", STOCK, t_ms, OLD_MS, "downloading the 59 MB release zip...",
               "installed 613 files", "Quit game")
        y2 = y1 + dh + gap
        dialog(d, x + 8, y2, w - 16, dh, "THIS RELEASE", OPT, t_ms, NEW_MS, "3 changed files already fetched",
               "installed 3 of 613 files", "Restart game")
        if still:
            im.save(still)
            print(f"wrote {still}")
            return
        im.save(work / f"{k + 1:04d}.png")
    write_gif(str(work), FPS, OUT)
    shutil.rmtree(work)


if __name__ == "__main__":
    main()
