---
name: game-update
description: Re-sync the repo after a Project Zomboid update (jar mtime changed) - re-decompile, regenerate and re-apply overrides, rebuild, and re-baseline. Use when build.sh reports a build mismatch, pzopt.sh check fails, or the game updated.
---

# After a game update

```bash
scripts/pzopt.sh check                  # reports the build mismatch
scripts/pzopt.sh uninstall              # removes exactly the old files (pzopt-installed.txt)
scripts/decompile.sh                    # CFR -> decompiled/ (parallel, minutes)
scripts/regen-overrides.sh              # Vineflower -> build/vineflower/
```
For each class in `OVERRIDES`: diff `build/vineflower/<class>.java` against
`src/overrides/<class>.java`, re-apply the edits listed in `docs/override-edits.md` onto the
fresh copy, then `scripts/build.sh`, `scripts/test.sh`, `scripts/accept.sh` (parity), and
`scripts/pzopt.sh install`. Re-run stock and optimized bench + drive baselines into
`harness/archive/2026-09-24/baseline/native/` (see `bench-run`), because numbers from the old build are not
comparable. Update `.codegraph` with `codegraph index` if the decompiled tree changed.
Steam may also swap the platform depot (native <-> Windows); `scripts/pz-env.sh` detects the
layout, and the Proton dir keeps stale overrides that must be uninstalled separately.
