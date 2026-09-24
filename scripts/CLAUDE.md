# scripts/

Build, install and decompile. All scripts source `pz-env.sh` for the game and user dirs
(native: `.../ProjectZomboid/projectzomboid/`, `~/Zomboid`; Proton: `.../ProjectZomboid/`,
compatdata prefix). Override with `PZ_DIR` / `ZOMBOID`.

| Script | Does |
|---|---|
| `build.sh` | `javac --release <game JRE>` of `src/overrides`, `src/shims`, `src/pzopt` against `projectzomboid.jar` into `build/classes/` plus `build-info.properties` (game build compiled against). `OVERRIDES=(...)` lists the shadowed game classes. Copies `src/lua/` under `build/classes/media/lua/` and generates the `puddleEarlyZ` shader variants (`media/shaders/pzopt_puddles_*`) from the installed game's puddle shaders (2026-09-21). |
| `pzopt.sh install\|uninstall\|reinstall\|status\|check` | copies `build/classes/` over the game dir. The launcher JSON puts `.` ahead of the jar, so loose `.class` files shadow it; the jar is never written. `pzopt-installed.txt` records what was written so uninstall is exact even after a game update. |
| `accept.sh [--prop k=v]` | build + install + parity gate against the stock capture; fails if recalc output differs. |
| `release.sh [--publish] [--notes ...]` | build + test + `build/pzopt-<rev>-classes.zip` (flat `build/classes/` plus a `pzopt-files.txt` manifest, Python zipfile); `--publish` = `gh release create win-<rev>-<commit>` with the zip plus the repo-root `install.sh` / `install.ps1` from a pushed clean HEAD. Skill `release-windows`. |
| `workshop.sh [--tag <tag> \| --zip <release zip>] [--out <dir>] [--upload "<notes>"]` | stages the Steam Workshop item (pure distribution) under `~/Zomboid/Workshop/PZ_Optimization/`: the zip unpacked as `42/pzopt-classes/`, `install.ps1`, `install.bash`, `mod.info`, `workshop.txt` from `docs/workshop/description.txt`, preview/poster PNGs; checks the uploader's banned extensions and preview size. `--upload` then runs `workshop-upload.py`. `docs/workshop.md`. |
| `workshop-shorten.py [--length]` | stdin -> stdout: every `[img]` raw GitHub URL of the Workshop page swapped for a da.gd link (cache `docs/workshop/short-urls.txt`, new links created and checked for a redirect with `Access-Control-Allow-Origin: *`); `workshop.sh` runs it on the page and refuses one over 7,900 characters (2026-09-24). |
| `workshop-upload.py --notes "..." [--check] [--preview gif\|png\|<file>]` | uploads the staged item through the Steamworks API (2026-09-24): the game's `natives/libsteam_api.so` with `SteamAppId=108600` attached to the running, logged-on Steam client; the same fields the in-game uploader sends from `workshop.txt` plus the animated `preview.gif`; Steam's `SubmitItemUpdateResult_t` is the verdict. No game, no OCR, seconds. `--check` stops before the submit. Only creating a new item still needs the game. |
| `option-classes.py [-v]` | which Java classes read each `pzopt.Config` key (`Config.FIELD`, or a small Config helper such as `effectiveWorkers()`) → `src/lua/client/pzopt/pzopt_optimizations_classes.lua`, the Optimizations tab's search index; build.sh runs it; `-v` lists keys nothing outside Config reads. |
| `power-access.sh [--remove]` | (sudo, maintainer) udev rule making the RAPL `energy_uj` counters readable by every user, so `sysmon.sh` and `pzopt.Power` can report CPU package watts (2026-09-24). Undoes the kernel's PLATYPUS mitigation on this machine. |
| `test.sh` | compiles `tests/` against `build/classes` + jar, runs each `*Test` main (no game needed). |
| `decompile.sh` | CFR, all game packages in parallel into `decompiled/`. Re-run after a game update only. |
| `bytecode-audit.py [-v] [class ...]` | bytecode parity of the overrides (2026-09-23, the top requirement in the root CLAUDE.md): every method with no `pzopt` line compared with the jar's copy in `build/stock/` on a compiler-neutral fingerprint (calls, field access, constants, arithmetic, loops; not local slots, branch polarity, returns). build.sh runs it and fails on a mismatch; `bytecode-audit.allow` holds the hand-checked benign differences with reasons. Found `AddVehicles_OnZone` (cars) and `procedureRandomFloat` (fish) on its first run. |
| `regen-overrides.sh` | Vineflower decompile of the OVERRIDES list into `build/vineflower/` to diff against `src/overrides/` after an update. Vineflower because its output recompiles with one fix; CFR's needs several. |

Rules:
- Before `reinstall`, check no game or `harness/run.sh` process is running (yours or a peer
  session's); a reinstall seconds before another session's launch made their run measure your
  classes. Announce to peers.
- `build/` is gitignored; `pzopt.properties` in the game dir switches Config keys off at
  runtime (defaults live in `src/pzopt/pzopt/Config.java`).
- Proton dir still holds Sep-15 overrides and `jre64 -> jre64.graal`; before any Proton run
  do `PZ_DIR=/games/steamapps/common/ProjectZomboid scripts/pzopt.sh uninstall && install`.
