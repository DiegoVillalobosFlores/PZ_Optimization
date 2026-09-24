# Preparing a Proton (Windows build) run, 2026-09-19

Purpose: one harness run of the Windows build under Proton on the same bench
save and route as the native runs, so Proton + NVIDIA GL can be put next to
native NVIDIA GL and native Zink. Nothing here has been run yet; this records
what is on disk, what was changed in the harness, and the exact steps.

## 1. What is already in the repo and on disk

- `scripts/pz-env.sh` detects both layouts. Proton layout: install root
  `/games/steamapps/common/ProjectZomboid`, user dir in the compatdata prefix
  `/games/steamapps/compatdata/108600/pfx/drive_c/users/steamuser/Zomboid`,
  JVM-side path `S:/common/ProjectZomboid`. It prefers the native layout
  whenever `projectzomboid/projectzomboid.jar` exists, so a Proton run needs
  `PZ_DIR=/games/steamapps/common/ProjectZomboid` in the environment while both
  depots' leftovers are present.
- `harness/run.sh` already handles the Proton layout: CRLF-aware `mods.txt`
  editing, Wine paths for `--jfr`/`--gc`, the Proton process-tree tolerance in
  the exit wait, and (uncommitted since the previous session) forcing
  `--launcher steam` because only the Steam client can start the Windows
  build. The "MangoHud was not loaded" warning is skipped for non-native runs.
- The Proton prefix is intact: `Saves/Sandbox/pzopt-bench-template` is
  byte-identical to `harness/bench-save/pzopt-bench-template.tar.zst` and to
  the native `~/Zomboid` template (same `map_t.bin` / `map_meta.bin`
  checksums), `mods/default.txt` has the harness mod, PZDashboard is present.
- The Proton install root still holds the Sep-15 pzopt overrides
  (`pzopt/`, `zombie/`, `pzopt-installed.txt` for revision `b0bbce05d5`,
  installed 2026-09-15), `pzopt.properties` with `instrument=true dev=true`,
  the tuned launcher JSON variants (`.opt`, `.stock`, `.bak`, `.prev`) and the
  JVMs `jre64.graal` (what `jre64` links to), `jre64.stock`, `jre64.zulu`.
- Sep-15 Proton baselines: `harness/runs/stock4k-*`, `harness/archive/2026-09-24/baseline/bench-*.json`
  (5120x2160, NVIDIA GL 615, Oracle JVM, older harness). They are not directly
  comparable to native runs (`compare.py` warns on the "OpenGL version" line).

## 2. What is missing

**The Windows depot is not installed.** `appmanifest_108600.acf` lists only
depot 108603 (Linux); the root has no `projectzomboid.jar`,
`ProjectZomboid64.exe` or `ProjectZomboid64.json`. Steam keeps one platform's
depot and swaps it when the compat tool changes, and no per-app compat tool
is set for 108600 in `config.vdf` (only the global default
`proton-cachyos-slr`, which does not apply to a native title).

Getting it back is a Steam UI step, not a harness one:
Library > Project Zomboid > Properties > Compatibility > "Force the use of a
specific Steam Play compatibility tool" > pick the Proton tool. Steam then
downloads the Windows depot (a few GB) and removes the native depot's own
files. Files Steam did not write (the pzopt overrides under
`projectzomboid/`, `pzopt.properties`, JSON backups) survive, as the Proton
leftovers did on 2026-09-18. Undoing it later is the same toggle in reverse
plus the download.

Caveats that decide what the run is compared against:

- `jre64` is a symlink to `jre64.graal`. Steam writes the depot's `jre64/`
  through the symlink, so the game runs on whatever ends up there. The Sep-15
  baselines ran on this setup; a like-for-like comparison with the native
  build (Azul Zulu 25, stock JSON) would need `jre64 -> jre64.stock` and the
  stock JSON. Decide before the run and note it in the run label.
- `ProjectZomboid64.json` comes back stock from the depot (`-Xmx3072m`);
  `run.sh --gc`/`--jfr` edit and restore it as usual.
- The Steam performance monitor must stay off (it caps the optimized runs at
  ~160 fps, see `docs/archive/2026-09-24/results.md`).

## 3. Harness changes made today for this run

- `run.sh`: the launch-env file is always written to
  `~/Zomboid/pzopt-launch.env`, the fixed path `harness/steam-launch.sh`
  reads. Before, under the Proton layout it went to the compatdata prefix,
  where the wrapper never looked, so MangoHud and `--renderer`/`--env`
  variables would have silently not reached the game.
- `run.sh`: when the game launcher is missing in the Proton layout, the error
  says that the Windows depot is not installed and how to get it, instead of
  just the path.
- `harness/steam-launch.sh`: header note on what the preload does under
  Proton (the `$LIB` form gives the 32- and 64-bit wine processes each their
  own copy). Whether `libMangoHud_opengl.so` hooks `glXSwapBuffers` inside
  the wine process is untested; the verify smoke below is what tells.
- `harness/proton-preflight.sh` (new, read-only): checks depot, compat tool
  mapping, Steam login, launch options, prefix and template, stale pzopt
  overrides, JVM symlink, MangoHud library. Exits 1 while any hard check
  fails. Current output: two FAILs (depot, compat tool), everything else ok
  or a warning.

## 4. Steps, in order

```sh
# 0. make sure no harness run is in flight (one run at a time)
pgrep -fa '[P]rojectZomboid64'

# 1. Steam UI: force the Proton tool for Project Zomboid, wait for the download to finish

# 2. preflight until it says "ready"
harness/proton-preflight.sh

# 3. replace the Sep-15 overrides with the current build (harness Java side lives there,
#    stock runs switch the optimizations off with --prop)
export PZ_DIR=/games/steamapps/common/ProjectZomboid
scripts/pzopt.sh status
scripts/pzopt.sh uninstall && scripts/pzopt.sh install
scripts/pzopt.sh status        # game revision must equal built-for

# 4. smoke: menu -> bench save -> quit, confirms launch, env file, MangoHud hook
harness/run.sh --label proton-smoke-1 --mode verify --quit-after 60 --mangohud 30
#    check: harness/runs/proton-smoke-1-*/console.txt has "OpenGL version" (NVIDIA, not Mesa),
#           run.opts says layout=proton launcher=steam, ~/Zomboid/pzopt-launch.log shows the
#           mangohud shim line, mangohud.csv exists

# 5. stock bench, same shape as native-stock-3 (mode bench, 200 s MangoHud log, max zoom)
harness/run.sh --label proton-stock-1 --mode bench --mangohud 200 \
  --mangohud-config config/mangohud-benchmark.conf --flag zoom=max \
  --prop instrument=true --prop parallel=false --prop wake=false --prop persistentVbo=false \
  --prop treesInChunkTexture=false --prop windowsInChunkTexture=false \
  --prop translucentTilesInChunkTexture=false --prop hotsaveIntervalSec=0 \
  --prop bakeBudget=0 --prop lightingBudget=0 --prop cutawayFast=false

# 6. optimized bench (defaults on)
harness/run.sh --label proton-opt-1 --mode bench --mangohud 200 \
  --mangohud-config config/mangohud-benchmark.conf --flag zoom=max --prop instrument=true

# 7. compare against the native pair
python3 harness/analyze.py harness/runs/proton-stock-1-* harness/runs/native-stock-3-*
```

`--launcher direct` and `--renderer zink` do not apply to this run: the
Windows build is started by Steam only, and Zink would put Mesa under Wine's
GL, which is not the configuration being measured. `--no-dashboard` is
optional; keep it the same as the native run being compared against.

## 5. Open questions the smoke run answers

- Does MangoHud's OpenGL library hook the wine process? If not, the fallback
  is `MANGOHUD=1` with Proton's own MangoHud support or the Vulkan layer,
  neither of which covers a GL game, so frame times would have to come from
  `pzopt-frames.out` (the harness's in-game frame log) alone.
- Does the game read `S:/common/ProjectZomboid/pzopt.properties` as before
  (Sep-15 runs did), and does `pzopt.sh install` see the depot revision as
  `b0bbce05d5`? A different revision means rebuilding first.
