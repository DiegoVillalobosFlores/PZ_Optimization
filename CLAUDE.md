# PZ_Optimization

Class overrides for Project Zomboid Build 42 (Java, LWJGL/OpenGL) that improve chunk
streaming and driving frame time, plus a hands-off benchmark harness that measures them.
Public repo (xD3I/PZ_Optimization). Maintainer: the repository owner (they/them).

## Requirement: overrides behave like the jar except where we edited them (2026-09-23)

Every method of `src/overrides/` that carries no `// pzopt:` edit must compile to the same behaviour as the
game jar. `scripts/bytecode-audit.py` checks it (compiler-neutral fingerprint of each unedited method against
`build/stock/`) and **`scripts/build.sh` fails when it does not pass**. Vineflower mis-renders compile fine
and shipped to players: the parking-lot loop that spawned one row of cars per chunk plus `case 5 -> 2` for
the High car-spawn rate (Workshop "cars barely spawn", 2026-09-23), a float divide for the jar's double one
(`FishSchoolManager`), a dropped cast that made `CanSee` call itself (2026-09-22). Therefore:

- A mismatch is a decompiler bug: fix `src/overrides` to match the jar's `javap -c` (CFR in `decompiled/`
  helps), mark the fixed lines `// pzopt: decompiler fix ...`, note it in `docs/override-edits.md`.
- An intended edit needs its `// pzopt:` marker on every changed line (the audit skips a method that has one);
  an unmarked edit fails the build.
- `scripts/bytecode-audit.allow` is only for differences read by hand and proven not to change what the
  method computes (compound-assignment re-reads, constant inlining), each with its reason.
- After `scripts/regen-overrides.sh` / a game update, the audit is the check that the new decompile is sound.
- When a player reports gameplay that differs from vanilla, audit the classes involved first.

## Objective (stated 2026-09-18)

"Consistent frame time if the CPU and GPU utilization allows it; the CPU and GPU should
always be used to the max if the framerate is not smoothly pegged at 240 fps."
Every benchmark report must show frame-tail metrics (p99 / p99.9 / spikes / jitter) AND
utilization (CPU/GPU load from sysmon) over the route window. "fps < 240 and hardware not
saturated" is itself a finding. Chunk-latency wins are done; do not spend more on the streamer.

## Hard rules

- **`decompiled/` stays local** (gitignored, 24 MB CFR output of the whole jar). Never `git add`
  it. `src/overrides/` (the 25 shadowed classes with our `// pzopt:` edits) IS committed since
  2026-09-19: the maintainer confirmed the sources may ship. Every edit is still described in prose in
  `docs/override-edits.md`, and the `// pzopt:` markers stay on every changed line.
- **Shared machine.** Several Claude sessions and the maintainer use the one game install and `~/Zomboid`.
  Before a launch or a reinstall check both `pgrep -f '[P]rojectZomboid64'` and
  `pgrep -f '[h]arness/run.sh'` (excluding your own). Message busy peers (ListAgents /
  SendMessage) before reinstalling or starting a batch. See `.claude/skills/bench-run`.
  **Preferred since 2026-09-21: submit through the run queue** (`harness/queue.sh submit run|mp|workshop|cmd
  [--machine desktop|flip|dell|mac] ... -- <args>`, `harness/CLAUDE.md` "Run queue"): one worker per machine
  runs its jobs in the order Jev picks (2026-09-22 evening: every submit carries `--intent`, `--progress`, and `--name` once per session; a run also `--resource` and optionally `--bench <name>|auto` from `harness/queue/benches.json`, Jev suggests the bench for the intent; Jev weighs them with waits, session age and the size estimate; `next` shows the plan; job start = desktop notification; a job past its estimate sends an `overrun:` event), waits for a game or run.sh outside the queue, routes laptop jobs over a monitored ssh
  connection, keeps a session on the machine it first used, notifies sessions (`watch`, `events`) when a
  machine drops or a job ends, and writes each job's `result.txt` with Jev's verdict; `--wait` blocks on it.
  No pgrep dance, no peer messages, no hand-rolled ssh wrappers for a run.
- **Run etiquette.** The maintainer is usually at the machine. Say a run is about to start before
  launching, one run at a time, never long batches. Never edit `harness/run.sh` while a run is in
  progress (bash reads it incrementally; a mid-edit launch died and its EXIT trap corrupted
  `latestSave.ini`). Launch from a copy (`harness/.run-snapshot.sh`) if someone else edits it.
- **Never kill with a self-matching pattern.** `pkill -f '<pattern>'` where the pattern appears in
  your own command line kills the tool shell (exit 144). Use bracket patterns like
  `[P]rojectZomboid64` or a saved PID.
- **All recordings and videos are AV1 HDR.** Every `--record` capture (gpu-screen-recorder
  `-k av1_hdr`: AV1 10-bit PQ / BT.2020) and every video published under `docs/media/` (the
  stitch scripts, `harness/encode-av1-hdr.sh`, the `-1080` README copies) is encoded AV1 10-bit
  with PQ / BT.2020 tags; never tone-map to SDR H.264. SDR sources are mapped to PQ; posters and
  GIFs are the only tone-mapped derivatives. See `harness/CLAUDE.md`.
- **Real saves are never loaded or written** by a harness run. Runs use the copied bench save
  `Saves/Sandbox/pzopt-bench` and must quit on their own. Launching the game via
  `harness/run.sh` is authorized without asking.
- **Verify every Louisville run before using its numbers** (maintainer, 2026-09-22). About one
  `--preset louisville` run in three flips into a broken state a few seconds after world-ready. Cause: the
  preset's `see_all` flag turns on the lighting engine's B41 spectator path, which spreads NaN light values,
  so the roofs flicker and our lighting re-bakes flood (fps halves, pale blue-grey blotches on the black roofs).
  It is a preset artefact (stock B42 never takes that path) and is left as is. So:
  1. Always pass `--record`.
  2. Before reading anything, check the frames of the settle window (world-ready → route start, before the
     measured route begins) for visual artifacts. `schedule.log` says `world ready N s after launch; route starts at
     +M s` and the recording starts at launch. Pull single frames, e.g. `ffmpeg -v error -ss <M-1> -i <run>/recording.mp4
     -frames:v 1 /tmp/lou-check.png` (plus one or two between N+2 and M), and look at them: roofs of the tall
     blocks must be uniform black, with no pale patches, no black/white speckle, no chunk-shaped holes.
  3. Check `bake_counters=` in `pzopt-bench.out`: a healthy 25 s route has strongMarks ≈ 14k and
     strongPastBudget ≈ 12k, fps ≈ 55 at the cap; a flooded one has 90k-350k marks and 20-26 fps.
  Any artifact or flooded counter: discard the run, re-run it, and say in the report which runs were thrown out.
- Commit and push only when asked. Commits end with
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Releasing (GitHub + Steam Workshop), 2026-09-21

Order, all hands-off except the login: commit + push → `scripts/release.sh --publish --notes "..."`
(tag `win-<rev>-<commit>`, zip + installers) → note the release in `docs/windows-test.md` (manifest
line count) → `scripts/workshop.sh --tag win-<rev>-<commit>` (stages item 3805285544 under
`~/Zomboid/Workshop/PZ_Optimization/`, regenerates `workshop.txt`; copy it to `docs/workshop/workshop.txt`
and commit "workshop: stage the <commit> release") → upload → verify. The upload is one Steamworks API call
since 2026-09-24, no game and no OCR: `scripts/workshop.sh --tag <tag> --upload "<notes>"` stages and uploads in
seconds (`scripts/workshop-upload.py`: the game's `libsteam_api.so` + the running Steam client). Details and pitfalls:
`.claude/skills/release-windows` ("Steam Workshop deploy").

- **The upload needs a really connected Steam client.** Before uploading run
  `tail -3 ~/.local/share/Steam/logs/connection_log.txt`: it must end in `[Logged On` with no
  `Session Replaced` after it. The client UI looks logged in and launches the game even when the same
  account logged in elsewhere (laptop) replaced its session; every upload then ends in EResult 2
  (the game's `failed to update workshop item, result=2`, three times on 2026-09-21) and Steam's
  `workshop_log.txt` says `Failed to initialize build on server (No Connection)`. Fix: `steam -shutdown`,
  start Steam again (cached login reconnects), re-check the log, upload.
- **Verify the upload**: `workshop-upload.py` prints Steam's own result (`upload OK: EResult 1`); `grep 3805285544
  ~/.local/share/Steam/logs/workshop_log.txt | tail -3` shows `Upload finished ... : OK`, and the
  public change-notes page `steamcommunity.com/sharedfiles/filedetails/changelog/3805285544` must list
  the new entry. The upload sends the animated `preview.gif` itself (the in-game uploader could only send
  `preview.png`). `steamcmd` has no cached login on this machine and is not needed; never type the maintainer's password.
- **No native libraries in a release** (maintainer's decision, 2026-09-22): `scripts/release.sh` defaults
  `PZOPT_DLSS=0`, so the zip carries no `natives/` (the DLSS shim would add NVIDIA's 58 MB library, Windows cannot
  use the .so, and `workshop.sh` refuses `*.so` because Steam bans the extension); `upscaler=dlss` without the shim
  runs as fsr1. Release from a detached worktree of the pushed commit (`git worktree add --detach /tmp/pzopt-release-<x>
  <sha>`) so peers' uncommitted files never block or leak into the zip; the page must stay ≤ 7,900 substituted
  characters (a "New!" section is ~150; retire the oldest card's heading or fold its image into a text section).

## Environment facts

| Item | Value |
|---|---|
| OS | CachyOS (Arch), fish shell, `paru` for AUR, 16 cores, 30 GB, RTX 4090 |
| JDK | system `jdk-openjdk` 26; game bundles its own JRE (build.sh compiles with `--release` for it) |
| Game dir | `/games/steamapps/common/ProjectZomboid/projectzomboid/` (native Linux depot since 2026-09-18) |
| User dir | `~/Zomboid` (console.txt, Saves, Lua/, mods/, options.ini) |
| Layout detection | `scripts/pz-env.sh` (PZ_DIR / ZOMBOID env override) |
| Desktop | 5120x2160, 240 Hz; the game renders windowed at desktop resolution |
| Decompiler | CFR at `~/.local/share/java/cfr.jar` for reading; Vineflower for overrides |
| Code index | `.codegraph/` exists; use `codegraph_explore` before grep/Read. Since 2026-09-22 `codegraph.json` (`include: ["decompiled/"]`) indexes the game source too (3,402 of 3,407 classes: `build/`, `obj/` dirs are hard-ignored by codegraph, so `BuildLogic` and `zombie.erosion.obj.*` are not indexed; the three >1 MB `generation/*ScriptGenerator` are skipped). Rebuild after a re-decompile: `codegraph index` (~30 s, DB ~1.7 GB) |
| Java LSP | `jdtls` not installed (check `command -v jdtls` before relying on the LSP tool) |

## Reading game code

Read game classes from `decompiled/` (CFR output of all 3,407 game classes, package tree such as
`decompiled/zombie/iso/IsoChunk.java`). Never re-decompile or unzip the jar for those packages.
Only two methods lack bodies (`CompressIdenticalItems.areItemsIdentical`,
`ChooseGameInfo.readModInfoAux`); use `javap -c -p` for them. If the jar mtime changes (game
update) re-run `scripts/decompile.sh` and `scripts/regen-overrides.sh`.

## Layout

| Path | Purpose | Details |
|---|---|---|
| `src/` | overrides, shims and the `pzopt` helper package | `src/CLAUDE.md` |
| `scripts/` | build / install / decompile / test | `scripts/CLAUDE.md` |
| `harness/` | run.sh, analysis scripts, baselines, run outputs | `harness/CLAUDE.md` |
| `config/` | MangoHud profiles | `config/CLAUDE.md` |
| `docs/` | plans, findings, override edit log, dashboard | `docs/CLAUDE.md` |
| `tools/` | standalone Java probes (JFR dump, GLFW swap probe, static audit) | `tools/CLAUDE.md` |
| `tests/` | JVM-only unit tests for pzopt classes (`scripts/test.sh`) | |
| `decompiled/` | CFR output, local only | |
| `openspec/` | OpenSpec change proposals (opsx skills) | |

## Skills (in `.claude/skills/`)

| Skill | Use when |
|---|---|
| `run-queue` | scheduling any run (bench / drive / preset / mp / Workshop upload / showcase) on the desktop or a laptop, and every media encode / stitch (never beside a run), through `harness/queue.sh`; reading its result, reacting to machine events |
| `bench-run` | the run.sh arguments of a measurement run (bench / drive / parity / verify); launch them through `run-queue` |
| `showcase-drive` | recording the stock-vs-optimized drive videos and the quad stitch |
| `build-install` | compiling the overrides and installing them into the game dir |
| `release-windows` | building the Windows zip and publishing it as a GitHub release asset |
| `analyze-run` | reading a finished run: analyze, compare, waits, loadtime, dashboard |
| `override-game-class` | adding or changing an overridden game class |
| `game-update` | the jar changed: re-decompile, regen overrides, rebuild, re-baseline |
| `proton-run` | preparing a Windows/Proton comparison run |
| `mangohud-overlay` | HUD/CSV problems, launcher and display-server hooks |

## Current state (2026-09-19)

- Adopted Config defaults (max-zoom route mean 6.2 → 4.4 ms, p99 19.3 → 8.3):
  treesInChunkTexture, windowsInChunkTexture, bakeBudget=8 (never-baked
  levels only), lightingBudget=8 (queued, never drops JNI dirty bits), hotsaveIntervalSec=30,
  on top of wake + recalc pool. persistentVbo and translucentTilesInChunkTexture are ON by
  default since 2026-09-20 afternoon (maintainer's decision after confirming the fix): the
  chunk-sized black squares seen with them were `lightInfoChunkGate` leaving never-cached squares
  out of the bake (fixed; persistentVbo only changed the timing). The 2026-09-19 reports (black
  building lot, Translucent tiles baking black) were not reproduced on the bench route. Rig:
  `run.sh --shot-at N` + `harness/blacktiles.py`; 0 black tiles after the fix, uncapped 512 fps.
  Black one-tile rectangles beside walls were cutawayFast replaying the stock int-shifted
  occluder mask (fixed with an exact mask); JUMBO trees missing near buildings were the
  FBORenderTrees batch in chunk-texture mode (trees now bake via the plain sprite path).
- Verify runs on a copy of a real save: `--source-save Apocalypse/<name>` (template kept under
  Saves/<mode>/pzopt-template-<name>); auto-start presses click-to-start and marks started
  in every mode, so the run exits to desktop after `--quit-after`.
- Remaining tail with the PZDashboard mod is its 2 s collectors; measure with `--no-dashboard`.
- Uncapped: NVIDIA GL is GPU-bound (98 %) at 570 fps; Zink blocks ~1.8 ms/frame in swap.
  The in-game limiter is stock again; "Uncapped" is a real Display-options entry and a
  second "Menu framerate" combo caps the menus separately, the in-game pause menu included since 2026-09-23 (`pzopt.FrameCap`, Lua under
  `src/lua/`, setting in `~/Zomboid/pzopt/framecap.ini`). Uncapped runs: `--prop uncappedFps=true`.
  `uiRenderOffscreen=true` in options.ini removes the per-frame Lua UI draw. Both combos also
  offer 500/430/400/330/300 fps; the in-game choice is snapshotted before `Core.loadOptions`
  rewrites options.ini and a cap above 244 lives in framecap.ini (`gameFps=`); forced
  `uncappedFps=` runs restore the player's choice on the next boot (`restore=`).
- Options > Optimizations tab (2026-09-20): every Config key as a tick box / combo
  (`src/lua/client/pzopt/pzopt_optimizations_options.lua`, Java side `pzopt.UserOptions` +
  `PerformanceSettings` forwards), saved to `~/Zomboid/pzopt/options.ini`, applied on the next
  launch; `-Dpzopt.*` and the game dir's `pzopt.properties` (harness `--prop`) still win, so runs
  never depend on menu choices. Since 2026-09-25 upscaling, HDR output and ambient occlusion sit on their own
  Options > Enhancements tab (between Optimizations and Profiler), each with off / on preview clips (runs `enh-*`), and
  apply on Apply without a restart (`pzopt.Enhancements`: RenderScale / Dlss / Hdr / ChunkAo reconfigure through
  render-thread generation counters) except `hdr` / `hdrAuto` (on Linux they pick the window). Rig: `--flag live_set=`.
  Master switch `enabled` (2026-09-20 evening): `enabled=false` folds into `Overrides.enabled()`,
  i.e. the build-mismatch stock path everywhere; tab buttons "Disable all (stock game)" /
  "Enable all (recommended defaults)". `--prop enabled=false` is a stock run without a reinstall.
  Preview panel (2026-09-21 night): the whole page area right of the controls, fixed while the list scrolls, every
  element in a fixed slot (description slot = the longest description wrapped; the clips take the height left, spare
  height widens the bar rows) so nothing shifts between rows; for the setting under the mouse:
  the stock and the optimized clip of the same route side by side (28 GIFs: 8 route pairs at 512x216, 24 fps, 4 s, plus 6 overlay-off / overlay-on crop pairs for the overlay keys from runs `ov-off` / `ov-full`; Workshop-preview encode, under
  `src/media/ui/pzopt/compare/`, made by `harness/menu-gifs.py` from the show-*/bl-*/sbs-* recordings with the
  live fps burned in; decoded to textures by `pzopt.GifTextures`, at most two clips held, freed on close), the
  description, the value since boot / next launch, and effect bars per resource (`EFFECTS` in the Lua: game
  thread, render thread, other cores, GPU, VRAM, RAM, disk, load time, chunk arrival, -3..3, x axis labelled lowest / low / mid / high / ultra / max with mid = stock). Verified in game
  via a queue `cmd` job driving the menus with `ui-drive.py` (`/tmp/pzopt-menu-check.sh`); new
  `src/media/` ships through build.sh like the Lua. The paths are `media/ui/...` relative to the game dir:
  `ZomboidFileSystem`'s "work dir" is already `<game>/media`, strip the prefix before `getMediaFile`.
- Game thread (2026-09-20, `docs/archive/2026-09-24/results.md`): new heavy bench route `--flag route=S:450 --flag turn=90
  --route-seconds 25` (south through Rosewood, facing spinning). Adopted: weatherMaskIdleSkip,
  cutawayRadius=6, gridStackInterval=8, lightingRebakeMs=250, rebakeBudget=4/rebakeMaxFrames=3,
  lightSwitchCheckFrames=15, single-lookup Kahlua rawget, occluder masks on IsoChunk. 199 → 229 fps
  there, 230 → 238.5 on the 100 s route. No gain from bakeBudget=3, uiRenderOffscreen or
  lightingRebakeMs=1000. Game thread now 97 % busy with broad work (bakes 20 %, world update 23 %,
  Lua UI 10 %): 240 locked on that route needs a structural change, not more trims.
- In-game performance overlay (2026-09-20, `pzopt.Overlay`, F9 or `--prop overlay=true`; since 2026-09-21 its
  measurement is opt-in, `overlaySampling` tab tick box, default off, F9 shows a notice without it; since 2026-09-24 every Profiler-tab key applies at once, no restart (`Config.reloadLive`, `Overlay.reconfigure`); harness
  runs and `overlay=true` imply it — the Windows 0.5 s stutter was its JMX load sampling on the game thread): presented
  frame time, p99/p99.9/max/1%-low/jitter/spikes over 5 s, GPU busy (GL timer query), game/render
  thread load, verdict line, frame graph; every harness run also writes `pzopt-overlay.out`
  (MangoHud columns + epoch_ms) and `analyze.py` prints it as `overlay:`. Same numbers on
  Windows/Linux without MangoHud or RivaTuner. The stock "Display FPS" graph (K) is debug-only bars.
  Since 2026-09-21 night the overlay also says *what* the game thread does (`pzopt.GameThreadProfile`; every overlay element is a
  tab dropdown, off + its options: `overlayStats`, `overlayTree`, `overlayVerdict`, `overlayGraph`, `overlayFlame`, plus
  `gameThreadProfileHz`, 25 Hz since 2026-09-23): its stack sampled off-thread and folded into phases,
  sub-phases, hot methods and waits (a colour-coded tree in the overlay, biggest first, bars per row; the "game thread bound" verdict names the two biggest);
  `pzopt-gamethread.out` per run, `analyze.py` prints it as `game thread:`; the folded stacks go to `pzopt-stacks.out` and
  `harness/flamegraph.py <run>` renders the route as an SVG flame graph (the overlay draws the last 5 s live, `overlayFlame=right` by default;
  the frame graph has ms / frames axes). First reading on the uncapped spinning
  route (runs `gtprof-*`): 21 % of the game thread is the frame hand-off wait (`SpriteRenderer.pushFrameDown`, i.e.
  the GPU is the wall there), then chunk bakes 8 %, lighting JNI 7 %, player 6 %, zombies 4 %.
- Uncapped 400 fps pass (2026-09-20 evening, runs `u400-*`, in-game overlay log
  only, `--no-mangohud`): spinning route 273 → ~500 fps mean, p99 13.2 → 7.7 ms. Stock option
  `uiRenderOffscreen=true` is +40 % uncapped (runs pass `--option uiRenderOffscreen=true`). Adopted
  keys: cutawayInvalidateChanged, cutawayVisitPrefilter, lightInfoOncePerFrame, lightInfoChunkGate,
  occlusionSkipLightingOnly, soundZoneCache, chunkHandoffDivisor=8. Dead ends: weatherFxScalePct,
  lightingRebakeMs=1000, bakeBudget=4, hotsaveStaged (off: cross-file consistency). GPU is the wall
  from ~450 fps (`pzopt.GpuSections`, `--prop gpuSections=true`: chunk composite ~0.6 ms, bakes
  ~0.3-0.5 ms a frame). `harness/gametree.py` prints the game-thread call tree from a JFR run.
  Never build or decompile while a run is going; check `pzopt.sh status` says installed before a launch.
- JVM matrix on the desktop (2026-09-20 13:35, `docs/archive/2026-09-24/results.md`): GraalVM 25.0.3 is 7-9 % behind
  Zulu/C2 uncapped on the spinning route (copy kept at `jre64_graal`); Zulu + the tuned G1 JSON
  (`config/launcher/ProjectZomboid64.g1.json`, now installed) has the tightest tail (508 fps, p99 7.3 ms,
  game thread 81 %). The ~500 fps numbers need `persistentVbo=true translucentTilesInChunkTexture=true`
  (tab file or `--prop`); with both off the same route is 184 fps, so check the console `settings:` line.
- Thunderstorm pass (2026-09-20 evening, `docs/archive/2026-09-24/findings-scene-presets-2026-09-20.md` §3-5): the storm
  preset was 83 fps uncapped with nothing saturated. GameProfiler A/B (`--game-profiler`, `sections.py
  --thread game|render`, the file names are `MainThread` = game thread, `main` = render thread) and JFR
  showed puddles (4.5 ms/frame, stock re-packs every wet square) and the rain quads (73 % of the render
  thread, `VBORenderer` flushing every 28 quads). Adopted: `puddleCache` (`pzopt.PuddleCache`, packed
  puddle vertices per chunk level, lights/jiggle/depth patched per frame; `IsoPuddles` override, slot
  on `IsoChunk`) and `vboBatchKb=1024` + `vboFastQuads` (`VBORenderer` override). Storm route 83 → 131
  fps, p99 43 → 25 ms. Left: the rain particle path (~100k quads a frame at 5120x2160, walked twice on
  the game thread), splashes, `GameWindow.logic`. The 15:49 build with the VBORenderer change is on a
  peer session's suspect list for interior-object flicker; recordings `storm-rec-cur` vs
  `storm-rec-vbostock` are the A/B.
- Rain tiles + re-bake spread (2026-09-20 night, findings §5): `rainTiles` (`pzopt.RainTiles`,
  `ParticleRectangle` + `WeatherParticleDrawer` overrides: particle cell packed once, drawn once per
  screen cell) → desktop spinning storm 111 → 188 fps, laptop storm drive 84 → 106. The rain
  "vanishing" every 6 s in storms was five 50-90 ms stalls per lightning strike (all flash-dirtied
  chunk textures re-baked in one frame); lighting-only re-bakes now spread with
  `lightingRebakeBudget=8` / `lightingRebakeMaxFrames=30` (storm drive p99.9 57 → 12.5 ms, faint
  chunk checkerboard for ~90 ms while a flash ramps). Laptop runs go through
  `/tmp/pzopt-laptop-run.sh` on diego-flip (`~/PZ_Optimization-rain` worktree). The interior-object
  flicker itself was the peer's find, fixed in 100f441 (held re-bakes drew empty per-frame lists).
- Flicker fix (2026-09-20 evening, `docs/archive/2026-09-24/results.md`): objects inside buildings, doors, windows and
  corpses blinked out for 1-3 frames because stock `FBORenderLevels.invalidate()` empties the per-frame
  square lists and every pzopt held re-bake (`lightingRebakeMs`, `rebakeBudget`) drew the previous
  texture with them empty. FBORenderCell now keeps the lists across invalidations (IsoChunk clears them
  on pool reuse) and never holds cutaway re-bakes. Repro/metric: `run.sh --flag hold=10` + `harness/flicker.py`
  (runs `flick-*`); stock 3.8 vs broken 26 transient px/frame at `--scale 2560`.
- Carport roof flicker (2026-09-21, maintainer video: detached carport roof toggling every frame with the player
  on its SE edge and zombies around, steady while paused): the hide/show of an orphan structure (`emptyoutside`-only
  building) is now debounced, `roofHideDebounceFrames=8` (`FBORenderCutaways.checkOrphanStructures`, tab entry,
  counter `roof flips held`). No harness rig reproduced the per-frame toggle (teleport walk, edge `jitter=`, manual
  walk with `zombies=off` gave 0 flips; the maintainer's session with zombies gave 13 flips in 30 s), so the driver is
  still open; `--prop devCutawayLog=true` logs every decision. Spot: save copy `Apocalypse/2026-09-20_22-30-24`,
  `start=11024,6721`, roof 11021-11024 / 6718-6721. Issue #5: `treesInChunkTexture` clips JUMBO trees to their
  chunk-level texture (rectangle / flat top) at that spot.
- Issue #5 (2026-09-21, `docs/override-edits.md` tree pass entry): JUMBO trees baked through the plain sprite
  path were clipped to their chunk-level texture (up to 7 tiles wide, 16 tile heights tall vs a texture that
  covers the chunk plus two levels) and cut by upper-floor walls behind them (flat sprite depth vs the walls'
  height-tilted depth). `pzopt.TreeBake` + `FBORenderCell.pzoptBakeTrees` (`treeBakePass`, default on) draw
  baked trees last in every texture their sprite reaches, with a depth that rises with the crown; export
  fingerprints on `IsoChunk` re-bake neighbours holding a copy when a tree changes. Stock's own chunk-texture
  tree batch is broken (dark crown behind the house), so it stays unused. Repro / A-B: the issue's `--shot-at`
  command, `--prop treeBakePass=false` (old bake), `--prop treesInChunkTexture=false` (per frame). Found on the
  way: `vboFastQuads=true` draws per-frame FBORenderTrees quads ~40 % darker than the stock element path
  (runs `trees-town-off` vs `trees-town-off-slowvbo`), reported to the VBORenderer session, not fixed here.
- Fog pass (2026-09-21, `docs/archive/2026-09-24/findings-fog-2026-09-21.md`): heavy fog was 447 → 220 fps on the 120 km/h uncapped
  desktop route (stock draws ~190 screen-wide row rectangles per level, 12 per pixel, `gl_FragDepth`, one draw call
  each, plus a game-thread square walk that fed nothing). `fogPass` (default on): `pzopt.FogPass` + overrides of
  `ImprovedFog`, `ImprovedFogDrawer`, `MultiTextureFBO2` (the offscreen depth is a D24S8 texture now, read in place),
  hook in `FBORenderCell.renderFog`: one draw call into a `fogScalePct` (25) % fog buffer with a min-depth reduction
  and a depth-aware composite (thin wires stay intact), mipmapped noise sampling, per-chunk fog masks + segment cache
  on the game thread (`fogMaskFrames`, 20). Now 389 fps / 2.6 ms (clear 447 / 2.2), laptop 98 → 223 (clear 259).
  A/Bs: `--prop fogPass=false`, `fogScalePct=50|100`, `fogDepthCopy=true`, `devFogNoDraw`, `devFogFlat`; GPU
  sub-sections `fog.blit/rects/composite` with `gpuSections=true`. Screenshot rig: bench `--flag fog=heavy --shot-at 8`.
- Storm parity pass (2026-09-21 afternoon, `docs/archive/2026-09-24/findings-storm-parity-2026-09-21.md`, branch
  `worktree-lightning-zero-cost`): the 120 km/h storm drive on the desktop was GPU-bound at 232 fps / 4.3 ms vs
  ~450 clear; the puddle "GPU cost" was the render thread streaming every wet square through the ring buffer each
  frame. Adopted (all default on): `puddleVbo` (per-chunk-level GL buffers, uploads only on light / camera-chunk
  / rebuild changes, jiggle as a matrix translation), `puddleEarlyZ` (build.sh-generated `pzopt_puddles_*`
  shaders: depth from the vertex, drawn with GL_DEPTH_CLAMP), `rainSplashesFast` (geometric skipping instead of
  one game-RNG call per idle square per frame), `treeAppend` (a new chunk's trees drawn into the finished
  neighbour textures instead of re-baking them; half the neighbour re-bakes while driving). Storm with lightning
  232 → 390 fps (2.6 ms, p99 8.6), clear 420 → 454 fps; the flashes cost ~0.1 ms mean. Laptop numbers pending
  (it was shut down mid-pass). A reduced-resolution puddle layer and a two-texture flash blend were rejected.
- World-sound hitch (2026-09-22, `docs/archive/2026-09-24/findings-world-sound-2026-09-22.md`): `WorldSoundManager.addSound` walks
  `(radius/3)²` squares to scare fish and asks the cell for `(2·radius·hearing/8)²` chunks per call, and
  `Alarm.update` makes that call every frame while a house alarm rings: 0.4 ms per call at 600 (620 → 533 fps
  uncapped), 4.4 ms at 2000 (→ 170 fps). `worldSoundFast` (default on; `WorldSoundManager` + `FishSchoolManager`
  overrides: chunk walk clamped to the loaded grid, identical-call memo per game minute for walks of radius ≥ 8)
  → 0.007 ms. Rig: `--flag sound=R [sound_fixed=true sound_every=N sound_parts=true]`, `--prop devWorldSoundTiming=true`;
  the game itself adds ~12k tiny sounds/s on the bench route (0.7 % of the game thread), which is why the memo ignores small walks.
  `--preset helicopter` (flag `helicopter=true`) runs the stock chopper event over the route: its moving 500-radius sound is
  not a hitch (14–18 calls / 25 s, 0.3 ms stock → 0.08 ms with the exact fast fish walk, `FishNoiseWalkTest`).
- Louisville horde pass (2026-09-22 night, runs `lou-*`): the baseline had collapsed to 10.8 fps / GPU 97 % — the
  09-21 `LightDirt` strong re-bake path had no per-frame cap and turning marks ~every exterior level strong
  (`darkMulti` fade), a fps feedback loop near the scene's ~27 fps tipping point; `lightingStrongBudget=8` caps it,
  and the never-baked budget now counts creations alone and holds optional re-bakes while a creation was deferred
  (black squares beat stale light). Then the game thread (98 %, update 57 %): `animBonesParallel` (`pzopt.AnimBatch`,
  `AnimationPlayer` + `MovingObjectUpdateScheduler` overrides: the zombies' bone math on 8 worker threads after the
  postupdate loop, joined before rendering; `isBoneReparented` allocated a pooled lambda per bone per zombie, now a
  loop), `vehicleCull` (`IsoZombie.isVehicleBetween` bounding circle, 6 % → 0.7 %). Clean all-in run `lou-final2`:
  26.4 → 32.2 fps (p50 30 ms), GPU 46 %, batch flush 1 % of the game thread, zombie postupdate 19 → 14.5 %.
  Runs right after a peer's `update-*` / `release-updater` cmd job showed GPU 75-80 % with the game's own gpu_ms
  tripled (a leftover game / Steam UI client; a game at the main menu alone is ~40 % GPU here) — re-run those.
- Player LOS pass (2026-09-22 early morning, runs `lou-los*`): the player was 16 % of the game thread on the Louisville
  preset, 12 of it `IsoPlayer.updateLOS` walking the `lastSpotted` Stack once per spotted object per frame (it never
  empties in a horde: spotted × remembered compares). `playerLosFast` (new `IsoPlayer` override + `pzopt.PlayerLos`:
  identity set beside the stack, one addAll instead of 2.4k synchronized adds, per-frame `getSneakSpotMod` memo) and
  `zombieSpotFast` (`spottedNew` zero-chance early-out, look-vector trig skipped when already zero, `isVehicleBetween`
  over a per-frame list of the vehicles near the player) → player 3.7 %, +3 fps on the same tree. Dead ends, both
  recorded in `docs/override-edits.md`: a per-frame "not dirty" memo in `JNILighting` (sound, 0 misses, but the native
  says dirty for 99 % of the visible squares every frame — that is what the 8 % "lighting jni" is), and `getChunkDirty`
  outside `LightingJNI.update` (throws; zombies vanish). Builds go through `queue.sh submit cmd -- scripts/build.sh`
  when peers are running. The peer session's §3.1 losParallel plan is dropped (nothing left to parallelise).
- Camera zoom (2026-09-22, `docs/archive/2026-09-24/results.md` "Camera zoom changes", `docs/override-edits.md`): stock frees a chunk level's
  textures the frame it leaves the screen and bakes every level a zoom-out reveals in the frame it appears (the bake budget
  never caught them: DIRTY_CREATE is set after the deferral decision); a 0.25 → 2.5 wheel spin was an 80-375 ms frame, a
  notch at wide zoom 45-51 ms. `zoomRetain` (`pzopt.ZoomRetain`, default on): textures kept while the chunk is inside the
  widest zoom's screen rect, levels a zoom brings back or reveals baked under a per-frame plan (nearest first, count adapts to
  the last frame step, `zoomRebakeBudget` 12 / `zoomFrameMs` 10) with the kept texture on screen meanwhile; `zoomEaseMs` 300 /
  `zoomEase` (`pzopt.ZoomEase`, CSS-style cubic Bézier, default "ease"): the zoom motion is time-based instead of 0.03 per frame
  and a snap. Jumps 375 → 25 ms worst frame, notches and eased spins inside the route's own noise. Rig: `--flag zoom=0.25
  zoom_cycle=S [zoom_span=9] [zoom_jump=true]`, `harness/zoomsteps.py`, `attribute.py`/`sections.py --after-mark zoom-:1`.
  Other sessions' forced `uncappedFps=` runs leave `framecap.ini restore=` that the next boot applies: pass `--prop uncappedFps=true`
  for a deterministic cap in an optimized run; stock runs need `--option frameRate=240 --option uncappedFPS=false`.
- Zombie simulation on all cores (2026-09-22 04:00-06:30, `docs/plan-zombie-multithread.md` §6, runs `lou-zm-*`,
  `spin-zm-*`): the zombie side of the game thread now runs through `pzopt.FrameBatch` (`frameThreads`, 8 workers +
  the game thread, one batch at a time). Keys, all default on: `ecsLookupFast` (the component lookup behind every
  `getStateMachine` / `getActionContext` / `getVariable` memoised; `ECSComponent` + `ECSEntity` overrides, cached
  field on `IsoZombie`), `actionConditionFast` (bool / int rule operands read typed, `CharacterVariableCondition`
  override), `actionEvalParallel` (each zombie's action-state transitions evaluated on the workers between the
  postupdate loop and the animator: `IsoGameCharacter` override splits `postUpdateAnimating` at
  `pzoptPostUpdateAnimatingRest`, `ActionContext` override evaluates / applies; the game thread first snapshots every
  callback variable not in `ActionEval.PURE_CALLBACKS` — `blunge` runs a pathfind line test through a plain
  ArrayDeque pool, `bHasTarget` / `bthump` / `beatbodytarget` have side effects — rig `devActionEvalCheck`: 860k
  contexts, 0 mismatches), `skinTransformsPrecompute` + `skinPalettePrecompute` + `shadowPrep` + `boneIndexCache`
  (the bone worker also fills the skin sets, the shader palette and the shadow ellipse; `AnimatedModel` override,
  `pzopt.ShadowPrep`), `lightingReadParallel` (the pre-pass lighting drain reads its chunk levels one per worker —
  libLighting64's getters are pure reads, the room / meta hooks are deferred to the game thread;
  `pzopt.LightingBatch`, rig `devLightingReadCheck`: 425k squares, 0 mismatches), `zombieCullSortFast` (`IsoWorld`
  override, `pzopt.SortKeys`), `lightingStrongFrameMs` (A/B key, default 0: halving the strong re-bake
  budget after a slow frame only held stale light on a steadily slow scene; the "GPU-doubled" Louisville regime of
  ~30 % of the day's runs was `see_all=true` marking the whole grid every pass — a preset artefact, ~2/3 of recorded
  runs tip into it; its deferral flood was the zoom session's stale pending bits, `build-zoom-leak-fix`).
  Same build (06:00), keys off → on: Louisville 46.4 → 57.5 fps (lou-zm-final-off4 vs cd4-on; p50 21.6 → 15.8 ms;
  32.2 at lou-final2 in the morning),
  Rosewood spin uncapped 336 → 472 fps (p99 11.5 → 7.3 ms). §3.1 losParallel dropped (the player session's
  `playerLosFast` took `updateLOS` 16 → 3.7 %); the per-frame simulation checksum (`devSimChecksum`, `pzopt-sim.out`,
  `harness/simdiff.py`) cannot compare two runs (never frame-aligned) — per-phase in-run dual evaluation is the rig.
  Pitfalls: an override the JVM touches before the logger (IsoWorld) must use `Overrides.onClassLoadedQuiet`
  (the loud marker killed every launch for 25 minutes); Vineflower's `IsoWorld.init` needs 7 typing fixes;
  `GameThreadProfile` is safepoint-biased (hot-method self time lands on loop back-edges). The harness summary now
  carries `zombie_batches=` and `bake_counters=` (`pzopt-bench.out`) so short runs still report the batch and
  bake / re-bake counters.
- Characters draw pass (2026-09-22 morning, `docs/archive/2026-09-24/findings-characters-draw-2026-09-22.md`, runs `cd*-*`): the game-thread
  render sub-phase `characters draw` (renderMovingObjects) on the Louisville horde 13.2 % → 4.4-4.8 % (~3.0 → ~0.75 ms a
  frame; 11.3 % with the keys off on the same tree). Stock already ran `ModelSlotRenderData.init` on its eight-thread
  executor (`Threading.ModelSlotInit`); the game-thread cost was `initModel`, one submit per zombie, the culled zombies'
  shadow ellipse and the HashSet walk. `charDrawPrep` (`pzopt.CharDraw`): the object walk on a worker from the top of the
  cell render; before the chunk bakes the game thread does the visibility test, `checkUpdateModelTextures` and the lazy
  per-square lighting refresh (the only part that must stay on the game thread: `JNILighting.update` behind
  `lighting[p].lightInfo()`), then `updateLights` + `initModel` + `init` + the camera record run per zombie on the pass's
  own pool (`charDrawThreads`); the stock loop runs over the on-screen list, `TextureDraw.drawModel` takes the prepared
  data. `zombieAtlasFast`: `IsoZombie.pzoptRenderFlat`, a flat copy of the render chain for the ~1,100 atlas zombies and
  the prepared model zombies. Overrides: TextureDraw, ModelInstance (thread-local light scratch, frame stamp). Analysis:
  `harness/subtree.py <run> [--frame pzopt.CharDraw.start]` (analyze.py rounds the sub-phase to whole percents). The
  preset is bistable (`see_all=true`, the parity session's finding): a run with settle-phase gpu_ms ~13 / bakes ~30k per
  period is in the GPU-bound re-bake regime and not comparable. A startup death with a 0-byte console that day was an
  override marker calling `onClassLoaded` from a class the logger touches first (use `onClassLoadedQuiet`); diagnose with
  `--env JAVA_TOOL_OPTIONS=-Xlog:exceptions=info:file=/tmp/x.log`.
- Upscaler second pass (2026-09-23, branch `upscaler-fix`, `docs/plan-upscalers.md`): the FSR "second view cone"
  (`VisibilityPolygon2` blur `displaySize` unscaled) and the aiming cursor's background (`IsoCursor`) fixed. DLSS on the
  4090 at 5120x2160: the network costs 0.64 ms (E) / 1.54 ms (K) at full output, so it only beats no upscaler when it
  writes less than the screen: new defaults `dlssPreset=e dlssOutputPct=67 dlssOutputFilter=rcas dlssDirectColor=true`
  (+22 % in the static storm + stock-fog scene, image sharper than full-size K); the drive stays DLSS-negative. The
  120 km/h drive harness no longer swings into the roadside (check `harness: drive t=` telemetry before trusting a drive).
- Variable refresh (2026-09-24, `docs/archive/2026-09-24/findings-vrr-2026-09-24.md`): stock borderless never got VRR (KWin / Mutter /
  gamescope need a fullscreen window); `borderlessFullscreen` (Linux default) makes it a GLFW monitor window at the desktop
  mode. `pzopt.Vrr` reads DRM `VRR_ENABLED`; while on, `vrrCap` (157 at 165 Hz) and `presentPacing=auto` (gpu: swap held to
  GPU-completion lag, judder 3.10 -> 0.95 ms). Mac `macPresent` (Metal bridge, ProMotion 4.17 ms steps, off by default,
  `devMacPresentCheck` rig). Rigs: `harness/pacing.py`, `presentprobe.c`, `vrrprobe.py` (every run.sh run writes
  `present.txt` / `vrr.txt`). The desktop's KWin VRR policy is Never by the maintainer's choice: set Automatic only inside
  the test job. Native-Wayland optimized runs segfault at exit (not VRR code, open).
- Sound engine pass (2026-09-24, `docs/findings-sound-2026-09-24.md`): rig `pzopt.SoundProbe` (`house_alarm`, `car_alarm`,
  `gunshots`, census `pzopt-sound.out`), bench `sound-storm-horde`, `run.sh --record-audio game` (the game's own PipeWire
  stream), `harness/soundprof.py` / `audio.py` / `audio-judge.py` (Jev: cutoffs, gaps, dropouts, clicks, clipping; in every
  queue result of a `--record-audio game` run). The game mixes 5.1 at 32 kHz on every device (hardcoded in
  `libfmodintegration64`), so on stereo output the OS downmix clipped under gunfire (6,000-19,000 samples in 25 s, stock);
  `audioLimiter` (FMOD's limiter at the master's head, stereo fold ahead of it on stereo devices, -2 dBFS) -> 0. Sound
  code on the game thread 5.3 -> 2.7 % in the horde (`emitterIdleSkip`, `soundTickHz` 60); FMOD's threads 0.1 core. The
  harness fades the mix out before quitting in sound runs (`exit_fade_ms`; the quit stops every sound at once).
- Ambient occlusion (2026-09-24, `docs/findings-ambient-occlusion-2026-09-24.md`, off by default, tab section "Ambient
  occlusion"): `pzopt.ChunkAo` bakes ground-truth-style horizon AO with 32-sector visibility bitmasks into the chunk
  textures at bake time (the FBO depth is an exact ortho view space: 424.27 squares per unit of depth), context from the
  eight neighbour textures, lighting-only re-bakes multiply the kept R8 AO, computes budgeted in-bake / deferred (ratio
  blend 2·src·dst). Cost on the desktop: 0 standing, ~4 us/frame walking, ~1.3 % of GPU time in the worst streaming
  (storm route, 120 km/h drive at max zoom). The per-frame screen mode (`aoMode=screen`) cost 90-140 us/frame: fixed
  per-pass cost dominates on NVIDIA (~8 us for an empty pass, `glGenerateMipmap` of a 1024 chunk texture ~65 us).
  macOS (GL 2.1 context) rejects GLSL 1.40: AO switches itself off there. Rigs: `devAoView=1`, `devAoTiming`, `devAoDumpFrame`.
- Town drive pass (2026-09-24/25, `docs/findings-town-drive-2026-09-24.md`): Rosewood 120 km/h drive at the 240 cap,
  stock 153 fps / p99 36 ms -> 235 fps / p99 8.2 ms / 1 %-low 122. On by default: `bakeScheduler` + `bakeBudgetAdaptive`
  (one prioritized per-frame bake budget, `pzopt.BakeScheduler`), `occlusionGrantedOnly`, `bakeMipLevels=3`,
  `renderChunkTopUp=32`, `persistentVboFrameSync` + `persistentVboSlots=4`, `fliesToggleFix` (new `FliesSound` override),
  and `presentPacing=auto` now paces at a fixed refresh too (maintainer's choice: +1.7 ms step-to-screen on average for
  frames off their slot 28 -> 11 %). Measured and off: `bakeSmooth`, `glNoSync` (`TextureID` / `TextureFBO` /
  `DeadBodyAtlas` overrides), `bakeDeadlinePct`, `uniformCache`, `compositeShaderRun`, `seamSpread`, `occlusionRetain`.
  Not locked at 240: game-thread bursts (tile rendering + bake preparation, chunk arrivals, lighting). Rigs:
  `harness/frame-causes.py`, `holes.py` (black holes in the picture; a fake 239 fps run baked nothing), `jfr-windows.java`.
  Queue runs of a worktree build need `--install opt`.
- Texture compression (2026-09-25, `docs/findings-texcompress-2026-09-25.md`): with `textureCompression=true` (low-end
  preset, stock Steam Deck defaults) Mesa compressed DXT5 on the CPU inside `glTexImage2D` on the render thread (41 ns/px,
  ~26 s a boot): the flip's menu 25-34 fps for 10-16 s after boot. `texCompress=auto` (default): workers stage the raw
  level 0 in a persistent 128 MB buffer, the GPU builds ImageData's mip chain (bit-exact) and encodes BC3
  (`pzopt.TexBcGpu`, fitted to the GPU's measured decode palette, `pzopt.TexBcPalette`); render thread ~0.1 s a boot, menu
  60 fps from 4 s, quality above both drivers. `worker` = CPU encode (`pzopt.TexBc`, the macOS GL 4.1 path, untested on
  the Mac); `texCompressCache` (off, ~230 MB). Rigs: `tools/TexCompProbe.java` (+ `harness/texprobe/wrap.sh` via
  `run.sh --wrap`), `harness/texdiff.py`, `harness/pad/menu-idle.txt`, `devTexCompTiming`.
- Soft sun / contact shadows (2026-09-25, `docs/findings-contact-shadows-2026-09-25.md`, off by default, Enhancements tab
  "Sun shadows"): the static world's sun shadows baked by the chunk AO kernel (visibility bitmask over the sun disk, march
  length from the sun height, outdoor squares only, a sun step recomputes a texture a frame); characters, atlas zombies and
  cars as capsules in one instanced pass after the composite (`pzopt.CapsuleShadow`, Quilez capsule shadows on the scene
  depth), also against torches / headlights at night; characters dimmed in static shade (`SunShadow.characterFactor`).
  Desktop: capped drive tails at parity (+33 us GPU streaming), crowds +10-15 us; the flip's character pass ~125-200 us
  (open). Rigs: `harness/contact/kernel_rig.py` (offline kernel on EGL), `alt.py` + `devSunAlternate`, flag `crowd=N`.
- Open plans: `docs/plan-graphics-enhancements.md` (2026-09-25: visual features after AO, sun shadows and per-pixel lighting first), `docs/plan-game-load.md`, `docs/plan-vulkan-renderer.md`, `docs/plan-resource-use.md`,
  `docs/plan-zombie-multithread.md` (2026-09-22: the rest of the zombie simulation on all cores, phased).
  The game-thread optimization plans were dropped on 2026-09-21 at the maintainer's request.
- Native Wayland works via `--env JAVA_TOOL_OPTIONS=-Dzomboid.wayland=1`; A/B on 2026-09-19 is a
  wash at the 240 cap (XWayland stays default; the NVIDIA GL worker thread only exists under
  GLX). `docs/plan-wayland.md`.
- Proton run prepared but blocked on the maintainer forcing a compat tool in Steam.
- Boot/load (2026-09-19 evening, `docs/plan-instant-load.md`): launch → menu 7.35 → 5.00 s,
  Continue → world 6.53 → 4.03 s, via boot threads (FMOD, anim sets), a boot-time file-pool
  pump, Lua precompile, animation clip + pack index caches (`~/Zomboid/pzopt/`), linear script
  parser, `Item.DoParam` switch, loader memos. First boot after a cache wipe is slower.
- Issue #1 (laptop, 2026-09-19 night): 16.5 s of its load was `Model.CreateShader` blocking on
  the render thread once per model (73 animal models × one ~220 ms loading-screen step). The
  `Model` override serves repeat shaders from `pzopt.ModelShaders` (`shaderCache`);
  `loadtime.py` row C2a shows the window. Why that laptop's loading-screen step is 220 ms is open.
