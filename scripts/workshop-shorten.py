#!/usr/bin/env python3
"""Shorten the page images' URLs in the Workshop description (stdin -> stdout), to save page characters.

Every [img]https://raw.githubusercontent.com/...[/img] becomes a da.gd link (~20 characters instead of ~100). da.gd
is the shortener that works there: Steam loads description images with crossorigin="anonymous", a CORS fetch, so the
redirect itself must send Access-Control-Allow-Origin. da.gd's 302 sends "*"; TinyURL's names its own origin and
spoo.me sends none, and both images failed to load on steamcommunity.com (Chrome, 2026-09-24); is.gd refused new
links. docs/workshop/description.txt keeps the long URLs; the short ones are cached in docs/workshop/short-urls.txt
("<long> <short>" per line, committed) and a missing one is created and checked (302 to the long URL, ACAO "*")
when scripts/workshop.sh stages the page.

    sed ... docs/workshop/description.txt | python3 scripts/workshop-shorten.py
    python3 scripts/workshop-shorten.py --length < staged-body.txt   # also print the page length to stderr
"""
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

CACHE = Path(__file__).resolve().parent.parent / "docs/workshop/short-urls.txt"
IMG = re.compile(r"\[img\](https://raw\.githubusercontent\.com/[^\[\s]+)\[/img\]")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def create(long_url):
    short = urllib.request.urlopen("https://da.gd/s?url=" + urllib.parse.quote(long_url, safe=""), timeout=20).read()
    short = short.decode().strip()
    if not re.fullmatch(r"https://da\.gd/\w+", short):
        sys.exit(f"da.gd did not shorten {long_url}: {short[:200]}")
    req = urllib.request.Request(short, method="GET", headers={"Origin": "https://steamcommunity.com"})
    try:
        urllib.request.build_opener(NoRedirect).open(req, timeout=20)
        sys.exit(f"{short} did not redirect")
    except urllib.error.HTTPError as e:
        if e.code not in (301, 302) or e.headers.get("Location") != long_url \
                or e.headers.get("Access-Control-Allow-Origin") != "*":
            sys.exit(f"{short}: {e.code} Location={e.headers.get('Location')} "
                     f"ACAO={e.headers.get('Access-Control-Allow-Origin')} (want a redirect to {long_url} with ACAO *)")
    return short


def main():
    cache = dict(line.split() for line in CACHE.read_text().splitlines() if line.strip()) if CACHE.exists() else {}
    text = sys.stdin.read()
    added = []

    def short(m):
        long_url = m.group(1)
        if long_url not in cache:
            cache[long_url] = create(long_url)
            added.append(long_url)
        return f"[img]{cache[long_url]}[/img]"

    out = IMG.sub(short, text)
    if added:
        CACHE.write_text("".join(f"{k} {v}\n" for k, v in sorted(cache.items())))
        for u in added:
            print(f"short url: {cache[u]} -> {u} (new, added to {CACHE.name}: commit it)", file=sys.stderr)
    if "--length" in sys.argv:
        print(f"page: {len(out.rstrip(chr(10)))} characters with short image URLs ({len(text.rstrip(chr(10)))} without)",
              file=sys.stderr)
    sys.stdout.write(out)


if __name__ == "__main__":
    main()
