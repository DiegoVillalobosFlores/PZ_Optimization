#!/usr/bin/env python3
"""The Workshop page's "New!" card as an animated GIF (2026-09-24). Same card as the still ones (harness/audio-card.py,
docs/media-style.md: near-black surface, one rounded panel with a hairline ring, the title in green top left, the
date top right, Noto Sans with numbers in Noto Sans Mono, stock amber / new green), but the change itself plays in
the left half and the card's text sits in the right half: the intro, the stock-vs-new table with a bar per row and
the change column; the footer runs under both. A card script (harness/audio-card-gif.py is the first) fills a Card,
draws its left-hand frames into Card.media and hands the frames to write_gif().

    card = Card(title, date, intro, rows, footer)
    frame = card.base()               # static layer, RGB PIL image, card.W x card.H
    x, y, w, h = card.media           # the left slot the script animates
    write_gif(frame_dir, fps, out)    # frame_dir/%04d.png -> one-palette GIF, gifsicle -O3, real-time delays
"""
import os
import shutil
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

BG, PANEL, RULE, RING, INK, INK2, MUTED = "#0b0b0e", "#111114", "#26262c", "#505058", "#f0f0f4", "#b4b4b8", "#8a8a90"
STOCK, OPT = "#c9592b", "#44de7c"
FONT_DIR = "/usr/share/fonts/noto"


def font(size, weight="regular", mono=False):
    name = {"regular": "Regular", "semibold": "Medium", "bold": "Bold"}[weight]
    return ImageFont.truetype(os.path.join(FONT_DIR, f"NotoSans{'Mono' if mono else ''}-{name}.ttf"), size)


def wrap(text, f, width):
    """Greedy word wrap to a pixel width."""
    lines, cur = [], ""
    for word in text.split():
        test = f"{cur} {word}".strip()
        if cur and f.getlength(test) > width:
            lines.append(cur)
            cur = word
        else:
            cur = test
    return lines + ([cur] if cur else [])


class Card:
    """rows: (label, detail, (stock value, shown), (new value, shown), change) as in audio-card.py; a None value
    draws the text without a bar. The bars scale per row, like the still cards. change is the text (an improvement,
    green) or (text, "worse" | "same"): amber for a cost, grey for no difference (docs/media-style.md, Colour).

    Sized for where it is read: Steam's description column is 655 px wide (steamcommunity.com, 2026-09-24), so the
    1280 px card shows at half size and every font here is about twice the size it should read at (body 24 px ->
    ~12 px on the page). The first GIF card at 1920 px with 15-18 px text was unreadable there."""
    W, PAD, X0, MEDIA_W, GAP, TOP = 1280, 16, 44, 540, 34, 116
    SIZES = dict(title=46, date=22, intro=24, intro_lh=33, head=18, label=24, detail=21, value=24, footer=22, footer_lh=29)

    def __init__(self, title, date, intro, rows, footer, cols=("STOCK", "THIS RELEASE")):
        self.title, self.date, self.intro, self.rows, self.footer, self.cols = title, date, intro, rows, footer, cols
        self.xr = self.X0 + self.MEDIA_W + self.GAP          # right column
        self.xe = self.W - self.X0                          # right edge of the text
        z = self.SIZES
        self.footer_lines = [(line, i == len(footer) - 1) for i, text in enumerate(footer)
                             for line in wrap(text, font(z["footer"]), self.xe - self.X0)]
        self.table_bottom = self._layout()
        self.footer_y = self.table_bottom + 30
        self.H = self.footer_y + z["footer_lh"] * len(self.footer_lines) + 26 + self.PAD
        self.media = (self.X0, self.TOP, self.MEDIA_W, self.table_bottom - self.TOP)
        self._base = None

    def _layout(self, d=None):
        """Right column: intro, then per row the label with the change at the right, the detail line, and the two
        values with their bars under the STOCK / THIS RELEASE heads. Draws when given a canvas; returns the bottom."""
        z = self.SIZES
        f = font(z["intro"])
        y = self.TOP + 14
        for line in wrap(self.intro, f, self.xe - self.xr):
            if d:
                d.text((self.xr, y), line, font=f, fill=INK2, anchor="lm")
            y += z["intro_lh"]
        cs, cn = self.xr, self.xr + (self.xe - self.xr) // 2  # value columns
        BAR, VAL = 64, 76                                    # bar length at the row's top value, value offset
        y += 14
        head = font(z["head"], "semibold")
        if d:
            d.text((cs, y), self.cols[0], font=head, fill=STOCK, anchor="lm")
            d.text((cn, y), self.cols[1], font=head, fill=OPT, anchor="lm")
            d.text((self.xe, y), "CHANGE", font=head, fill=MUTED, anchor="rm")
        y += 18
        if d:
            d.line((self.xr, y, self.xe, y), fill=RING, width=1)
        lab, det = font(z["label"], "semibold"), font(z["detail"])
        val_f, val_b = font(z["value"], mono=True), font(z["value"], "semibold", mono=True)
        for label, detail, s, n, change in self.rows:
            top = max(abs(s[0]), abs(n[0])) if s[0] is not None else None
            if d:
                d.text((self.xr, y + 24), label, font=lab, fill=INK, anchor="lm")
                if change:
                    text, kind = change if isinstance(change, tuple) else (change, "better")
                    d.text((self.xe, y + 24), text, font=val_b, fill={"better": OPT, "worse": STOCK}.get(kind, MUTED),
                           anchor="rm")
                d.text((self.xr, y + 53), detail, font=det, fill=MUTED, anchor="lm")
                cy = y + 88
                for x, colour, (v, shown) in ((cs, STOCK, s), (cn, OPT, n)):
                    tx = x
                    if top:
                        w = max(4.0, BAR * abs(v) / top) if v else 4.0
                        d.rounded_rectangle((x, cy - 10, x + w, cy + 10), radius=4, fill=colour)
                        tx = x + VAL
                    d.text((tx, cy), shown, font=val_b if colour == OPT else val_f, fill=colour, anchor="lm")
            y += 116
            if d:
                d.line((self.xr, y, self.xe, y), fill=RULE, width=1)
        return y

    def base(self):
        if self._base is None:
            self._base = self._draw()
        return self._base.copy()

    def _draw(self):
        z = self.SIZES
        im = Image.new("RGB", (self.W, self.H), BG)
        d = ImageDraw.Draw(im)
        P = self.PAD
        d.rounded_rectangle((P, P, self.W - P, self.H - P), radius=10, fill=PANEL, outline=RING, width=1)
        d.text((self.X0, 66), self.title, font=font(z["title"], "bold"), fill=OPT, anchor="lm")
        d.text((self.xe, 66), self.date, font=font(z["date"], mono=True), fill=MUTED, anchor="rm")
        self._layout(d)
        # footer, under both halves; the last line (the source) muted, as on the still cards
        y = self.footer_y
        for line, last in self.footer_lines:
            d.text((self.X0, y), line, font=font(z["footer"]), fill=MUTED if last else INK2, anchor="lm")
            y += z["footer_lh"]
        return im


def write_gif(frame_dir, fps, out, colours=128, lossy=0):
    """frame_dir/%04d.png -> one-palette GIF (no dither, the rectangle diff keeps the static text untouched), then
    gifsicle -O3 with centisecond delays from cumulative rounding so the clip plays in real time. Lossy LZW is off by
    default: even --lossy=20 left ghosts of the moving half as speckle over the static text (clear-audio, 2026-09-24)."""
    n = len([f for f in os.listdir(frame_dir) if f.endswith(".png")])
    subprocess.run(["ffmpeg", "-hide_banner", "-v", "error", "-y", "-framerate", str(fps), "-i",
                    os.path.join(frame_dir, "%04d.png"), "-filter_complex",
                    f"[0:v]split[a][b];[a]palettegen=max_colors={colours}:stats_mode=full[p];"
                    "[b][p]paletteuse=dither=none:diff_mode=rectangle", "-loop", "0", out], check=True)
    gifsicle = shutil.which("gifsicle") or os.path.expanduser("~/.local/bin/gifsicle")
    if os.path.exists(gifsicle):
        delays = [round(100 * (i + 1) / fps) - round(100 * i / fps) for i in range(n)]
        per_frame = [a for i, dl in enumerate(delays) for a in (f"-d{dl}", f"#{i}")]
        subprocess.run([gifsicle, "-O3"] + ([f"--lossy={lossy}"] if lossy else []) + [out] + per_frame
                       + ["-o", out + ".tmp"], check=True)
        os.replace(out + ".tmp", out)
    else:
        print("gifsicle not found: GIF left at plain LZW with rounded delays", file=sys.stderr)
    print(f"wrote {out} ({os.path.getsize(out) / 1e6:.2f} MB, {n} frames at {fps} fps)")
