---
name: release-windows
description: Build the release zip of the class overrides (same zip for Windows and Linux) and publish it with the install.sh / install.ps1 installers as GitHub release assets. Use when asked for a new Windows or Linux build, a release, a zip for the Windows test, or to update the release asset after a change under src/ or the installers.
---

# Windows release

```bash
scripts/release.sh                        # build + test + build/pzopt-<rev>-classes.zip
scripts/release.sh --publish              # ...then gh release create win-<rev>-<commit>
scripts/release.sh --publish --notes "Adds X and Y over the previous build."
```

What the script does, in order:

1. `scripts/build.sh` (javac against the local jar, `build/classes/`) and `scripts/test.sh`.
2. Zips the flat content of `build/classes/` (class files, `media/lua/...`,
   `pzopt/build-info.properties`) plus a manifest `pzopt-files.txt` listing every entry.
   Python `zipfile` is used because this machine has no `zip` binary.
3. With `--publish`: tags the full SHA of HEAD as `win-<game revision>-<short commit>` and
   uploads the zip plus the repo-root `install.sh` and `install.ps1` with notes carrying the build date, commit, revision, manifest count and
   sha256. One release per commit; an existing tag makes it stop.

Rules and gotchas:

- **Publish only from a pushed, clean commit.** The script refuses if `src/` or `build.sh`
  has uncommitted changes or HEAD is not on `origin/master`; the tag must describe the bytes
  in the zip. Commit and push first (only when asked for the commit).
- **Never overwrite an earlier asset.** Each build gets its own release; the previous test
  build (`win-test-b0bbce05d5`) stays as the reference the Windows results were taken on.
- Put what changed since the last Windows build in `--notes` (read `git log <last tag>..HEAD`).
- The Windows install/uninstall steps live in `docs/windows-test.md`; it quotes the manifest
  line count (`Measure-Object -Line`). Update that number if it changed, and add a results
  section there when Windows numbers come back.
- The game revision in the tag comes from `build-info.properties`; after a game update run the
  `game-update` skill first, otherwise the guard on Windows disables the overrides.
- The installers (`install.sh`, `install.ps1`) are standalone: they find the game via Steam's
  library list, read the revision from the jar, download the matching zip (curl, or `gh`
  when logged in) and write `pzopt-installed.txt` in the `pzopt.sh`
  format. Test them against a temp folder holding a symlinked jar and a copied launcher JSON,
  never the real game dir; portable PowerShell (`/tmp/pwsh/pwsh` when present, else the
  linux-x64 tarball from PowerShell's GitHub releases) runs `install.ps1` on Linux.
- Building does not touch the game dir, so no peer-session check is needed for a build-only
  run. It does not install either; use `build-install` for that.

## Steam Workshop mirror

After `--publish`, `scripts/workshop.sh --zip build/pzopt-<rev>-classes.zip` stages the same
zip as a Workshop item under `~/Zomboid/Workshop/PZ_Optimization/` (pure distribution: the
tree unpacked under `42/pzopt-classes/` plus `install.ps1` and `install.bash`, which is
`install.sh` renamed because the uploader bans `.sh`, `.zip`, `.bat`, `.exe`, `.dll`, `.so`).
The upload itself is in the game launched through Steam (Workshop > Create/Update item);
the first upload writes `id=` into `workshop.txt`, which goes to `docs/workshop/workshop.txt`.
Both installers auto-detect a `pzopt-classes/` sibling, so the Workshop instructions are one
line per OS. Details: `docs/workshop.md`.

## Steam Workshop deploy (hands-off, 2026-09-21)

### A user-visible change gets a "New!" section on the page, as one image

Before the upload, when the release adds something a player sees (a mode, a button, a scene
that got faster), the description gets a section at the top, right after the showcase GIF:

```
[h1]New! <feature name>[/h1]
[img]https://raw.githubusercontent.com/xD3I/PZ_Optimization/master/docs/workshop/images/<NN>-<slug>.jpg[/img]
```

**Nothing else in the section: the image carries all the text** — the title ("New! ..."), the
date of the measurement (top right, `YYYY-MM-DD`), one or two lines saying what the feature is
and the machine, and the stock-vs-new table with a bar per row. Render it with a script under
`harness/` in the `docs/media-style.md` style (`harness/lowend-table.py` is the template:
`DATE`, `ROWS`, the two colour roles stock amber / new green, a gain column), then
`ffmpeg -y -i docs/media/<name>.png -vf scale=1920:-1 -q:v 3 docs/workshop/images/<NN>-<slug>.jpg`,
numbered after the last image in `docs/workshop/images/` and listed in `docs/workshop.md`
(Images). Steps, in order:

1. Render the PNG and the JPG; look at the JPG (Read) before using it.
2. Add the two lines to `docs/workshop/description.txt`. Keep the substituted page under
   8,000 characters with margin (the game appends ~50): `python3 -c` the length after
   replacing `@VERSION@ @REV@ @NFILES@ @ID@`; aim for ≤ 7,900 and shorten an older caption if
   needed. The previous "New!" section moves down or goes when the next one arrives; the
   README keeps the long form.
3. Commit the script, the PNG, the JPG and the description and **push master first**: the
   `[img]` URLs are raw GitHub links to `master`, so the page shows a broken image until the
   push is public.
4. `scripts/workshop.sh --zip <the release zip>` re-stages `workshop.txt` from the description,
   copy it to `docs/workshop/workshop.txt`, commit, then the upload below. Verify on
   the item page, not only the changelog: `curl -s
   https://steamcommunity.com/sharedfiles/filedetails/?id=3805285544 | grep -c 'New!'` must be
   1 and the page must contain the image's raw URL. Re-staging usually re-encodes
   `preview.png`, so Steam records a new manifest and a changelog entry (2026-09-21 15:56,
   "Page update ..."); when it does not (`No content change detected`), the page check is the
   only proof.

**Upload = one Steamworks API call (2026-09-24), no game, no OCR:** `scripts/workshop-upload.py --notes "<change
notes>"` loads the game's `natives/libsteam_api.so` with `SteamAppId=108600`, attaches to the running, logged-on
Steam client (no password, no steamcmd) and does what the in-game uploader did with the same `workshop.txt` (title,
description + `Workshop ID:` / `Mod ID:` lines, visibility, tags, `Contents/`), plus the animated `preview.gif`
(≤ 1,000,000 bytes; else `preview.png`), then `SubmitItemUpdate` with the notes. Its own `SubmitItemUpdateResult_t` is the
verdict (`upload OK: EResult 1`), with the `workshop_log.txt` lines printed beside it; a few seconds end to end.
Nothing touches the game dir or the display, so it can run while a benchmark is going. `--check` does everything but
the submit (Steam session, fields, paths). Stage and upload in one command:

```bash
tail -3 ~/.local/share/Steam/logs/connection_log.txt       # ends in "[Logged On", no "Session Replaced"
scripts/workshop.sh --tag win-<rev>-<commit> --upload "<change notes>"
cp ~/Zomboid/Workshop/PZ_Optimization/workshop.txt docs/workshop/workshop.txt   # then commit it
```

Queued form (a `result.txt` with the verification): `harness/queue.sh submit workshop --wait --notes "<change notes>"
-- --tag win-<rev>-<commit>`: Steam session check with one client restart, `workshop.sh`, `workshop-upload.py`,
`workshop_log.txt` + change-notes page, `workshop.txt` copied to `docs/workshop/`. It skips the game / run / locked
screen preflight, since the upload uses neither.

If the connection log shows `Session Replaced` / `Logged Off` (or the upload ends in EResult 2 / 3 / 21 / 34):
`steam -shutdown`, wait for `pgrep -x steam` to clear, `setsid steam &`, wait ~20 s, re-check (the cached login
reconnects on its own; if it asks for a password or Steam Guard, hand over to the maintainer).

The game is only needed to *create* a new item (Main menu > Workshop > Create and update items; it writes `id=`
into `workshop.txt`). The OCR-driven click sequence through the game's wizard (`ui-drive.py workshop`, 2026-09-21
to 09-24) is gone; `git show c6c0ac1:harness/ui-drive.py` has it.

Change notes: one paragraph, "Release <commit> (game revision <rev>). <what changed for users>.
Everything else is unchanged from the previous upload (...)". The upload is ~6 s.

Verify the page too:

```bash
grep 3805285544 ~/.local/share/Steam/logs/workshop_log.txt | tail -3   # "Uploaded new content (ManifestID ...)" + "Upload finished ... : OK"
```
and fetch `https://steamcommunity.com/sharedfiles/filedetails/changelog/3805285544`: the new entry
must be the first one. A description-only update (same files) gets no changelog entry at all (`No content
change detected`): verify it on the item page instead. EResult 8 (`InvalidParam`) = the description
is over Steam's 8,000 characters (`workshop-upload.py` prints the length it sends, `Workshop ID` / `Mod ID` lines
included): trim `docs/workshop/description.txt`, re-stage, upload again. EResult 2 with `Failed to initialize build
on server (No Connection)` in `workshop_log.txt` = the Steam session is dead (restart Steam, above).
