#!/usr/bin/env python3
"""One snapshot of the Steam Workshop item's public numbers into workshop_stats (local DB + the public dashboard's DB).

  harness/grafana/workshop_stats.py            # fetch + store, print the row
  harness/grafana/workshop_stats.py --dry-run  # fetch + print only

Subscribers, favorites and unique visitors come from the keyless Web API (ISteamRemoteStorage/GetPublishedFileDetails);
the star rating (Steam's rounded 0-5 stars image), the rating count, the comment count and the award reactions only from
the public item page, which Steam rate-limits per IP (HTTP 429): those columns stay NULL then and the dashboard shows the
newest snapshot that has them. No Steamworks session: a process holding app 108600 would look like a running game to
Steam and could block a harness launch. The follower (ingest.py --follow) calls snapshot() every 30 min.
"""
import argparse
import json
import re
import sys
import urllib.parse
import urllib.request

from ingest import psql_run, remote

ITEM = 3805285544
API = "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/"
PAGE = f"https://steamcommunity.com/sharedfiles/filedetails/?id={ITEM}"
UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) pzopt-dashboard"}


def fetch_api():
    body = urllib.parse.urlencode({"itemcount": 1, "publishedfileids[0]": ITEM}).encode()
    d = json.load(urllib.request.urlopen(urllib.request.Request(API, body, UA), timeout=20))["response"]["publishedfiledetails"][0]
    if d.get("result") != 1:
        raise RuntimeError(f"GetPublishedFileDetails result {d.get('result')}")
    return {"subscribers": d["subscriptions"], "lifetime_subscribers": d["lifetime_subscriptions"], "favorites": d["favorited"],
            "lifetime_favorites": d["lifetime_favorited"], "visitors": d["views"]}


def fetch_page():
    html = urllib.request.urlopen(urllib.request.Request(PAGE, headers=UA), timeout=20).read().decode(errors="replace")
    n = lambda pat: (m := re.search(pat, html)) and int(m.group(1).replace(",", ""))  # noqa: E731
    return {"stars": n(r"sharedfiles/(\d)-star_large\.png"),  # "not-yet_large.png" until Steam has enough ratings
            "ratings": n(r'class="numRatings">([\d,]+) rating'),
            "comments": n(rf'_{ITEM}_totalcount">([\d,]+)<'),
            "awards": sum(int(x) for x in re.findall(r'data-reactioncount="(\d+)"', html)) or None}


def snapshot(dry_run=False):
    row = {"item": ITEM, **fetch_api()}
    try:
        row.update(fetch_page())
    except Exception as e:  # 429 and friends: the counts still go in
        print(f"workshop: page skipped ({e})", file=sys.stderr, flush=True)
    if not dry_run:
        cols = list(row)
        script = (f"INSERT INTO workshop_stats (t, {', '.join(cols)}) VALUES (now(), "
                  + ", ".join("NULL" if row[c] is None else str(int(row[c])) for c in cols) + ") ON CONFLICT DO NOTHING;\n")
        psql_run(script)
        remote().apply(script, "workshop stats")
    return row


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--dry-run", action="store_true", help="fetch and print, store nothing")
    print(json.dumps(snapshot(ap.parse_args(argv).dry_run)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
