# Near-instant updater (2026-09-26)

Goal (maintainer): make the in-game updater near instant; implement and profile every idea before keeping or
dropping it; replace "Quit game" after an update with "Restart game". Issue #16 (install from the Steam Workshop
copy on disk) is part of the same change.

Rig: `scripts/updater-bench.sh [runs] [all|check|local|install|sweep|spans|http3|workshop]`
(`tests/pzopt/UpdaterBench.java`): real GitHub releases, a temporary game folder with an older release installed
by the updater itself, every technique timed on its own next to the pre-change updater (carried verbatim in the
bench as the baseline). In game: `harness/mac-update-e2e.sh` (Mac) with the `devUpdateDrive` rig. Numbers are
medians of 3; desktop = 16 cores, gigabit, JDK 26; Mac = M1 Pro, Wi-Fi, the game's Zulu 25.

## What changes between releases

| Installed | New (ab4b22b) | Entries changed | Compressed bytes changed |
|---|---|---|---|
| b70f234 (one release before) | 613 entries, 59.0 MB zip | 3 | 57 KB |
| e3a5fdc (three before) | | 7 | 104 KB |
| f837467 (a day of releases) | | 150 | 0.97 MB |

Nothing was stored uncompressed; 47 GIFs are 90 % of the zip and almost never change. A zip's central directory
lists every entry's CRC-32, sizes and offset, so what changed is known from ~60 KB at the end of the file.
GitHub's asset store (release-assets.githubusercontent.com, behind the github.com redirect) answers single byte
ranges with 206, refuses multi-range (501 "Unsupported client range") and suffix ranges (501).

## Results

Click on Update now → files installed (what the player waits for):

| Path | Desktop, 1 release apart | Desktop, a day apart | Mac, 1 apart | Mac, a day apart |
|---|---|---|---|---|
| Baseline: 1 stream, unzip all, hash all, move all | 1,233 ms | 1,197 ms | 2,164 ms | 2,368 ms |
| Whole zip, 8 parallel ranges + delta install | 900 | 915 | 1,644 | 1,735 |
| Delta without prefetch (directory, CRC plan, changed ranges, write) | 213 | 309 | 278 | 392 |
| **Delta, prefetched when the update was offered (shipped)** | **1.1** | **1.3** | **4.7** | **28** |

Workshop copy (issue #16), click → installed: baseline copy-all 53 ms desktop / 311 ms Mac → changed files only
5 ms / 42-62 ms.

The check (once per boot, cold client): the 210 KB release list 139 ms → a conditional request with the kept ETag
(304) 69 ms; it now starts in `GameWindow.mainThreadInit`, so on the Mac it answered 764 ms (first boot, 200) and
397 ms (304) after starting, seconds before the menu existed. The Workshop copy scan is 1 ms and runs while the
request is in flight.

Restart game (Mac e2e): the new process started 60 ms after the old one ended and reached the main menu 12.9 s after
the press (the Mac's normal boot); `RestartTest` on Linux: 49 ms from the end of the first JVM to the second one's
marker.

## Every idea, measured

| Idea | Result | Kept |
|---|---|---|
| Delta download: central directory + CRC-32 compare + range requests of changed entries | 59 MB → 57 KB-1.2 MB over the wire; 1,233 → 213 ms cold | yes |
| Background prefetch of the delta once offered (`updatePrefetch`, capped by `updatePrefetchMaxKb`) | the click waits 1.1 ms (desktop) / 4.7 ms (Mac) | yes |
| Only changed files rewritten, atomic rename beside the target | install 53 → 5 ms (Workshop); unchanged files keep inode and mtime | yes |
| Manifest hashes of unchanged files reused from the old manifest | 5.5 → 1.1 ms desktop, 14.9 → 4.7 ms Mac | yes |
| Conditional request (ETag cache in `~/Zomboid/pzopt/update-releases.json`) | 139 → 69 ms, 210 KB → 0 | yes |
| Check started during boot instead of at the menu | answer ready before the menu on the Mac | yes |
| Workshop scan in parallel with the GitHub request | 1 ms, never waits on the network | yes |
| One shared HTTP/2 client (one TLS handshake per host, multiplexed ranges) | HTTP/1.1 304: 159 ms vs HTTP/2 69 ms; range fetch equal or better | yes |
| Parallel workers for ranges / CRC / inflate / hashing | ranges: 1 worker 374 ms, 4+ ~230 ms; CRC plan 11.7 → 4.0 ms; inflate 99 → 28 ms | yes (8) |
| Span merging (gap / cap) and directory tail | gigabit: all within noise; bytes: 16 KB gap + 1 MB cap 1.18 MB vs 1.84 MB (64 KB/4 MB), 64 KB tail enough | 16 KB / 1 MB / 64 KB |
| Whole zip in 8 parallel segments (no range support fallback) | 1,128 → 900 ms desktop, 1,783 → 1,644 ms Mac | yes (fallback) |
| Rate-limit fallback: /releases/latest redirect + asset probe + build-info by range | 365 ms, no API quota | yes (on 403/429) |
| `per_page=5` instead of 30 | 98 ms (200) vs 69 ms for the 304: the ETag wins | no |
| HTTP/3 | GitHub negotiated HTTP/2 even when asked; the game's JRE 25 has no HTTP/3 client | no |
| Hard links from the Workshop copy | desktop 4.9 vs 5.1 ms (equal), Mac 151 vs 62 ms (worse); the game's files would alias Steam's | no |
| zstd instead of deflate | parallel inflate of the whole zip is 28 ms desktop / 33 ms Mac, only paid in the whole-zip fallback where the download is 900+ ms; would change the release format and the installers | no |
| size+mtime cache instead of the CRC plan | the stat pass is 0.4 ms vs the 4 ms CRC plan (desktop), and the plan runs in the prefetch, off the click | no |
| Pre-connecting TLS before the offer | the prefetch already runs right after the offer; the click never waits on a connection | no |

## Restart game

`pzopt.Restart`: Linux / macOS start `/bin/sh` polling `kill -0 <pid>` every 50 ms (120 s cap), then `exec` of
`ProcessHandle.Info.command()` with its `arguments()` in the same working directory and environment, plus
`PZOPT_RESTARTED_FROM` / `PZOPT_RESTARTED_AT` so the new process logs the timing. Windows (no argument list for
a process in Java): a hidden PowerShell reads the command line from WMI, answers "ready" (the game waits for it,
15 s cap, before quitting), waits for the process and `Start-Process`es it again. The Lua presses the stock
`quitToDesktop` after starting the helper; if the helper fails the game still quits. Verified: `RestartTest`
(Linux, arguments with spaces and quotes, cwd, marks), Mac e2e (the game's own JVM command line, 13 arguments).
Not verified: Windows, and launches through Steam (the helper then relaunches the same executable Steam started,
with Steam's environment).
