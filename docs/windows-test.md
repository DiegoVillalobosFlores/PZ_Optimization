# Testing the overrides on Windows

First Windows test of the class overrides. Nothing has to be compiled on Windows:
the zip built on Linux carries the finished class files, and the runtime guard
(`pzopt.Overrides`) checks both the game revision and the sha256 of every stock
class it shadows against the Windows jar. If anything differs it logs one line and
the game runs as stock, so the worst case of a mismatch is "no effect".

2026-09-20 release from `100f441` (flicker of objects inside buildings, doors, windows and corpses fixed; overlay fps colour) is 699 KB, 130 files plus the manifest (131 lines, unchanged); tag `win-b0bbce05d5-100f441`.
2026-09-20 night release from `cc99c05` (thunderstorm pass: puddle cache, rain tiles, VBORenderer batch, lighting re-bake spread, play mode; includes the 100f441 flicker fix and the overlay colours) is 740 KB, 145 files plus the manifest (146 lines); tag `win-b0bbce05d5-cc99c05`.
2026-09-21 release from `4dbe655` (RecalcPool: a failed chunk-recalc retry no longer leaves the publisher blocked, which stopped every later chunk from loading; found in a Windows user's console, StackOverflowError in the stock `isWallTo` recursion) is 741 KB, 145 files plus the manifest (146 lines, unchanged); tag `win-b0bbce05d5-4dbe655`.
2026-09-21 release from `75f9365` (issue #4: curtains draw in the same pass as the window they cover and baked curtains sit in front of the glass, key `curtainDepthNudgePct`; supersedes f93158f) is 743 KB, 145 files plus the manifest (146 lines, unchanged); tag `win-b0bbce05d5-75f9365`.
2026-09-21 release from `667b210` (issue #5: baked trees drawn by their own pass into every chunk texture the crown reaches, with a height-tilted depth, key `treeBakePass`; also the carport roof debounce `roofHideDebounceFrames`, the LightingJNI see-all override and new harness scene flags; supersedes 75f9365) is 782 KB, 151 files plus the manifest (152 lines); tag `win-b0bbce05d5-667b210`.
2026-09-21 release from `1cf5080` (the Windows 0.5 s micro stutter: the overlay's CPU-load sampling ran PDH queries on the game thread twice a second; sampling is now on a background thread and opt-in, key `overlaySampling`, default off, F9 shows a restart notice without it; supersedes 667b210) is 784 KB, 151 files plus the manifest (152 lines, unchanged); tag `win-b0bbce05d5-1cf5080`.
2026-09-22 evening release from `3441a1c` (smooth zoom: `zoomRetain` keeps chunk textures across camera zoom changes and bakes what a zoom-out reveals a few per frame nearest the player, `zoomEaseMs` / `zoomEase` move the zoom along a 300 ms cubic Bézier; also the Louisville horde pass: strong re-bake budget, creation-first bakes, zombie bone math on worker threads) is 35.6 MB, 243 files plus the manifest (244 lines); tag `win-b0bbce05d5-3441a1c`.
2026-09-22 release from `2980cf3` (the 3441a1c build's `zoomRetain` stall: chunk textures stopped appearing past a fixed radius and waiting never filled the rest — pending bits that no path cleared kept the zoom plan flooded and took its credits; now a credit nothing consumed is dropped, camera-motion returns bake at once, only a real zoom change or deferral keeps the flood on; supersedes 3441a1c) is 35.6 MB, 243 files plus the manifest (244 lines, unchanged); tag `win-b0bbce05d5-2980cf3`.
2026-09-22 release from `1555855` (the day's passes: the upscaler — Options > Optimizations > Upscaling, FSR 1.0 / bicubic / DLSS, off by default —, the player line-of-sight pass, the zombie simulation on all cores, the characters draw pass, the world-sound hitch fix, every new key on the tab; no native libraries in the zip, so `upscaler=dlss` runs as fsr1 unless the shim from the repository is under natives/) is 36.3 MB, 344 files plus the manifest (345 lines); tag `win-b0bbce05d5-1555855`.
2026-09-22 night release from `3c21099` (the performance overlay fits any screen size: `overlayFont=auto` picks the font by screen height, the frame graph, flame graph and tree give way on small and short screens, long lines end in "..."; video `docs/media/overlay-responsive-4-screen-sizes.jpg` poster) is 40.7 MB, 359 files plus the manifest (360 lines); tag `win-b0bbce05d5-3c21099`.
2026-09-22 night release from `06242da` (Options > Optimizations > "Zoom motion curve" gains four sliders, x1 / y1 / x2 / y2 of the Bezier ease, with a live plot of the curve; plus Instant Continue from `03b4494`: Continue -> world 3.95 -> ~2.0 s, no click to start, black loading screen) is 40.7 MB, 367 files plus the manifest (368 lines); tag `win-b0bbce05d5-06242da`.
2026-09-22 night release from `ef5ceb4` (fewer hitches on 4-core PCs: C2 JIT off during play on 4 cores or fewer (`jitMode=auto`), the overlay's game-thread sampler pauses only the game thread, zombie cell saves no longer block the game thread (`saveCellAsync`); Dell 120 km/h drive: frames over 100 ms 16.8 -> 8.0 per minute) is 40.7 MB, 373 files plus the manifest (374 lines); tag `win-b0bbce05d5-ef5ceb4`.
2026-09-22 night release from `214eef7` (off-screen zombies no longer thump doors and windows in bursts of 8 / 16 hits with up to 16x the door damage; 240 fps rig: 29 strikes in one second before, a steady 1-3 per second after) is 40.7 MB, 374 files plus the manifest (375 lines); tag `win-b0bbce05d5-214eef7`.
2026-09-22 night release from `da3cdea` (Render distance option: the chunk grid 7-15 chunks per side or vanilla, Rosewood uncapped spin 223.5 fps at the vanilla 19x19 vs 247.8 at 15x15; Dell hitching rounds 2 and 3) is 41.6 MB, 381 files
2026-09-23 release from `d36540a` (Continue: the world builds from the player outwards, and the ground around the player captured at the last exit is shown while loading, tiles popping in, instead of the loading screen; Mac bench save: filled in over 6.4 s of an 8.4 s load) is 41.7 MB, 384 files, sha256 `94a525365bd0d9e151eee34d3ee02dd00f8b2ac1f4d9b5557c286fc8de4d732b`, tag `win-b0bbce05d5-d36540a`.
2026-09-23 release from `e5d025a` (the Continue view no longer shows chunks with an upper floor as black squares; Dell hitching round 4) is 41.7 MB, 385 files, sha256 `5ec895a87869288cfd7e9def719b01ea32f7a46bf44b96fa5820dd1fe7e2c1a0`, tag `win-b0bbce05d5-e5d025a`.
2026-09-23 release from `102ccf9` (Render distance: an Auto entry that fills the screen at the widest zoom, 25 at 5120x2160, and fixed widths up to 31; tested on Linux and macOS) is 41.7 MB, 386 files

2026-09-22 night release from `71b2c6c` (the zombie game-thread pass: zombies 23 → 11 % of the game thread in the Louisville horde, eight default-on keys and three opt-in simulation-LOD keys; the `CanSee` self-recursion fix (StackOverflowError on the Lure action); the Optimizations tab's search box, collapsible sections and "Low-end hardware + FSR 1.0" button; Louisville preview clips per group) is 40.7 MB, 359 files plus the manifest (360 lines); tag `win-b0bbce05d5-71b2c6c`.

2026-09-22 release from `db825a0` (the main menu's UPDATE PZ OPTIMIZATION item: `pzopt.Updater` checks the GitHub releases once per boot and, when a newer build for the game revision exists, downloads it and replaces the files of `pzopt-installed.txt` in place, then asks to quit; build-info now carries `commit=` / `built=`) is 35.4 MB, 223 files plus the manifest (224 lines); tag `win-b0bbce05d5-db825a0`.
2026-09-22 release from `cd1a8de` (harness only: the bench player keeps their worn items during a run — the Louisville video's soft optimized side was the game's short-sighted blur after a zombie bump knocked the ghost player's glasses off, not a render change; no change to the shipped optimizations) is 35.4 MB, 219 files plus the manifest (220 lines); tag `win-b0bbce05d5-cd1a8de`.
2026-09-22 release from `cb48693` (the Optimizations tab's preview panel: stock-vs-optimized clips and per-resource effect bars per setting; `pzopt.GifTextures`, five `PerformanceSettings` forwards, 28 GIFs under `media/ui/pzopt/compare/`, 34 MB) is 35.4 MB, 219 files plus the manifest (220 lines); tag `win-b0bbce05d5-cb48693`.
2026-09-22 release from `0029f16` (installer only: `install.ps1` no longer throws `Cannot find drive` when `libraryfolders.vdf` lists a Steam library on a drive that is no longer connected, seen on a user's `G:`; workaround on older builds is `-Dir "<game folder>"`; the classes are identical to `8bbc11a`) is 893 KB, 188 files plus the manifest (189 lines); tag `win-b0bbce05d5-0029f16`.

2026-09-22 release from `8bbc11a` (game-thread profiler in the F9 overlay: tree of phases / sub-phases / hot methods / waits, flame graph, frame graph with axes, one dropdown per overlay element under Options > Optimizations; profile logs per run) is 893 KB, 188 files plus the manifest (189 lines); tag `win-b0bbce05d5-8bbc11a` (its predecessor `win-b0bbce05d5-dc46e1b` was deleted minutes after publishing: it carried a half-finished options-tab rework swept in from the shared checkout).

2026-09-21 release from `51a6f78` (multiplayer join fix: the `zombie.network.NetChecksum` override leaves the client-only `media/lua/*/pzopt/` files out of the Lua file check a joining client sends, so a community server no longer refuses with "File doesn't exist on the server: .../pzopt_keybinding.lua"; key `luaChecksumExempt`, default on; supersedes d434cde) is 869 KB, 183 files plus the manifest (184 lines); tag `win-b0bbce05d5-51a6f78`.
2026-09-21 release from `d434cde` (blocky / lagging lights of a moving light source fixed: `pzopt.LightDirt` re-bakes strongly changed chunk levels at once, the lighting refresh queue is flushed before each lighting pass; keys `lightingStrongDelta`, `lightingGlobalDeltaPct`, `lightingFlush`; supersedes 31f27f4) is 858 KB, 177 files plus the manifest (178 lines); tag `win-b0bbce05d5-d434cde`.
2026-09-21 release from `31f27f4` (installer fix only: `install.sh` / `install.ps1` pick the newest published release instead of GitHub's list order, which put dcc0ce9 ahead of c69c085; same 176 files plus the manifest (177 lines); supersedes c69c085) tag `win-b0bbce05d5-31f27f4`.
2026-09-21 release from `c69c085` (macOS support: `install.sh` finds `Project Zomboid.app` and installs into `Contents/Java`; plus the storm parity pass from `ce1dc0c` that dcc0ce9 predates: `puddleVbo`, `puddleEarlyZ` with the `pzopt_puddles_*` shaders, `rainSplashesFast`, `treeAppend`; supersedes dcc0ce9) is 854 KB, 176 files plus the manifest (177 lines); tag `win-b0bbce05d5-c69c085`.
2026-09-21 release from `dcc0ce9` (the Low-end hardware profile button in Options > Optimizations for 4-core machines, key `treeBakeMaxChunksPerSec` (trees baked into chunk textures only below that chunk rate; new class `pzopt.ChunkRate`), `workers` defaulting to 1 on 4 cores or fewer; supersedes 8889f72) is 853 KB, 161 files plus the manifest (162 lines); tag `win-b0bbce05d5-dcc0ce9`.
2026-09-21 release from `8889f72` (the fog pass: heavy fog in one draw call into a quarter-size fog buffer, depth-aware composite, keys `fogPass` (experimental, default on), `fogScalePct`, `fogMaskFrames`; new overrides `ImprovedFog`, `ImprovedFogDrawer`, `MultiTextureFBO2`; supersedes 1cf5080) is 850 KB, 160 files plus the manifest (161 lines); tag `win-b0bbce05d5-8889f72`.
2026-09-21 release from `f93158f` (IsoChunkMap mid-scroll guard: `getGridSquareDirect` rejects a chunk that is not where the index says, closing the stock race that made `isWallTo` overflow the stack on the streamer; supersedes 4dbe655) is 741 KB, 145 files plus the manifest (146 lines, unchanged); tag `win-b0bbce05d5-f93158f`.
2026-09-20 release from `b0d4fe6` (black chunk squares fixed, persistentVbo and translucentTilesInChunkTexture on by default) is 694 KB, 130 files plus the manifest
2026-09-20 release from `e398ce5` (master switch) is 607 KB, 114 files plus the manifest
2026-09-20 release from `aa9482b` is 606 KB, 114 files plus the manifest
2026-09-20 release from `2d34aa9` is 543 KB, 104 files plus the manifest
2026-09-23 release from `cbcd436` (fixes a hang while loading a save since the faster Continue: the chat-icon scan raced the early world entry; plus the Dell hitching rounds 2-5: chunk-map lookups without helper calls, allocation-free tile-surface scan, no glGet per frame in the weather drawer, learned render-chunk prewarm) is 43.7 MB, 385 files plus the manifest (386 lines); tag `win-b0bbce05d5-cbcd436`.
2026-09-23 release from `76755df` (Continue: the boot backlog of file tasks drains in parallel, Mac Continue to world ready 8.2 -> 6.3 s; PNG Paeth rows ~40 % faster; the load steps touching the shared texture table run on the main thread, replacing the cbcd436 retry; the Continue view fills top-left to bottom-right) is 41.7 MB, 389 files, sha256 `52ec1b019dda8496132e5db08c7d349bff8e8d524a95a70dc3df4d25d87b56c8`, tag `win-b0bbce05d5-76755df`.
2026-09-23 release from `53fe0e8` (palette PNGs and tile depth maps decode faster, the same bytes; Mac depth-map work 8.9 -> 7.7 s, Continue to world ready 6.4 -> 6.0 s) is 41.7 MB, 390 files, sha256 `58c68965e700a8499ce6ae8373e94b73c8c447f35d3b2007f1e79e4a69b1f974`, tag `win-b0bbce05d5-53fe0e8`.
2026-09-23 release from `4826971` (fixes the windowed resolution at launch: a 1920x1080 window on a 1920x1080 screen opened as 1920x1061 on Windows since the 2026-09-22 window-at-final-size change) is 41.7 MB, 390 files, sha256 `92b353aa78a912c2596d850b1ca87dd03473e012674283e0c96b450a4b3f671b`, tag `win-b0bbce05d5-4826971`.
2026-09-23 release from `2c0ca9d` (the performance overlay draws its panel as one texture per refresh instead of every letter and box as a sprite each frame, and builds its game-thread tree off the game thread: with every element on 12 -> 4 % of the frame rate on the flip laptop, 16 -> 2.3 % on the MacBook; new tab entries overlayTexture / overlayRefreshMs / overlayGraphHz; defaults overlayStats=tails, overlayFlame=off) is 43.7 MB, 392 manifest lines, sha256 `b315e95ed68716912ad1f4eb38ee5721d45445909ab2279d7366c4bec7cbde01`, tag `win-b0bbce05d5-2c0ca9d`.
2026-09-23 release from `4ab3fe8` (a SHOW / HIDE PERFORMANCE OVERLAY item below Options in the main menu and the pause menu, mouse and controller, the same toggle as the F9 key binding) is 43.7 MB, 393 manifest lines, sha256 `61c1de300af64cba028ede75bba435df4d61c7bf9718f94640f272808b9bd55d`, tag `win-b0bbce05d5-4ab3fe8`.
2026-09-23 release from `417778a` (fixes a load hang the previous releases could still hit: the chat-icon scan raced the World Streamer registering textures after the early world entry; the scan is retried on the main thread) is 43.7 MB, 392 files plus the manifest (393 lines); tag `win-b0bbce05d5-417778a`.
2026-09-23 release from `5db6a37` (zombie hordes on the other cores: each zombie's animator, move speeds and animation clock run on worker threads and the bone math runs alongside the rest of the frame; fixes the game's single shared model lock that serialised that work; Louisville horde postupdate 15.8 -> 3.8 % of the game thread, +17 % fps; fixes an occasional ConcurrentModificationException while a save loads; new tab entries animatorParallel / animBatchAsync / animatorPipeline / guardedCallbacks / modelLockPerInstance / poolStatsBatched / lazyPose / headOnWorker) is 43.8 MB, 408 manifest lines, sha256 `73c6b585c47d03f1acc729ef56d80d9c0b00efc3238e7e4103ad193d5acaf2d1`, tag `win-b0bbce05d5-5db6a37`.
2026-09-23 release from `a9a1558` (world entry: the Optimizations tab is built when first opened, the in-game options screen builds in ~28 ms instead of ~124 ms; geometry map zones skip far polygon edges, same result) is 41.8 MB, 411 files, sha256 `15e522661b752383b95ec20577249789f170f76f2108f5320bbe661958e02471`, tag `win-b0bbce05d5-a9a1558`.
2026-09-23 release from `8cb8ccf` (the options screen is built when first opened: main menu build 654 -> 410 ms on the flip, in-game menu 28 -> 2 ms at each world entry, key bindings still loaded at once; the world-entry power check walks only existing levels) is 41.8 MB, 421 files, sha256 `0f4e9411c437f9656904b537698474bd5b1749a1e56b86b081c4b6bfea3560c2`, tag `win-b0bbce05d5-8cb8ccf`.
2026-09-23 release from `8497d41` (the performance overlay's game-thread stack sampler takes 25 samples a second instead of 100: with every element on the overlay costs under 1 % of the frame rate, 2.5 % before on the MacBook) is 43.8 MB, 421 manifest lines, sha256 `9f8605ca4bf309b365f334fd2f549725eb2cd1aa0056326dde975450b54b1474`, tag `win-b0bbce05d5-8497d41`.
2026-09-23 release from `be1f28a` (parked cars spawn again: the decompiled `IsoChunk.AddVehicles_OnZone` filled one row of stalls per parking zone and chunk and gave the High car-spawn rate a flat 2 %; also the fish-per-spot hash in double like the jar, and the build-time bytecode audit of the overrides) is 43.8 MB, 421 manifest lines, sha256 `c3a1490074ec03f55bccd6467cc752340da37c13abac8777f0e339ff09d37bc4`, tag `win-b0bbce05d5-be1f28a`.
2026-09-23 release from `a4ed6f9` (the pause menu opens with its buttons again: Esc in game laid out the not-yet-built options screen and the Lua error aborted the menu's layout, broken since `8cb8ccf`) is 43.8 MB, 421 manifest lines, sha256 `a189a9d9a2134cae4e4356b58ddbff700713629b050e7b00980126cfdebd45ea`, tag `win-b0bbce05d5-a4ed6f9`.
2026-09-23 release from `4bb5acb` (G1 garbage collector by default: the launcher JSON's ZGC is switched for the next launch, `gcMode=stock` or uninstalling undoes it; texture compression in the low-end presets; JIT policy by core count) is 43.8 MB, 421 files plus the manifest (422 lines); tag `win-b0bbce05d5-4bb5acb`.
2026-09-23 release from `1718aa0` (borderless windowed: the window is created undecorated at the monitor origin, so the menu is centred and clicks land on the cursor again, issue #14; the pause menu runs at the Menu framerate cap) is 43.8 MB, 421 files plus the manifest (422 lines), sha256 `0b869a99ce41ea0f4278dd7009dabc1004e5a5a70cd75dba7bcfd71004c028e9`, tag `win-b0bbce05d5-1718aa0`.
2026-09-23 release from `5c72380` (supersedes `1718aa0`: the undecorated borderless creation is Windows-only, Linux keeps the stock window sequence; the Workshop uploader raises its native confirm dialog) is 43.8 MB, 421 files plus the manifest (422 lines), sha256 `749ab97626a7c93b8e657096db9eb9e18502788cb866458ec6d34b851d9a834c`, tag `win-b0bbce05d5-5c72380`.
2026-09-23 release from `d492a26` (supersedes `5c72380`: the Optimizations tab gets a Sort by dropdown, natural / alphabetical / effect on each resource, the preview follows the controller focus, and dependent settings are renamed so both orders agree) is 43.8 MB, 421 files plus the manifest (422 lines), sha256 `74385377b5b685ba72a780c36332283d76b3ae57c81a09ad5c9e77bb15d8e023`, tag `win-b0bbce05d5-d492a26`.
2026-09-24 release from `0fed198` (supersedes `d492a26`: Options > Optimizations draws only the rows on screen, 11.4 -> 4.1 ms a frame on an M1 Pro and D-pad response 18.5 -> 6.5 ms, the same rows reachable by controller; harness-only in-game virtual pad for menu profiling; unit tests no longer read the player's saved options) is 43.8 MB, 422 files plus the manifest (423 lines), sha256 `65d665acf8b59a26de5acf9cde1800046dd17f54ee386ece8696208327b3930f`, tag `win-b0bbce05d5-0fed198`.
2026-09-24 release from `d456427` (supersedes `0fed198`: with any upscaler the view cone no longer shows a second, shrunken cone and the aiming cursor reads its background from the right spot; new DLSS defaults for builds with the shim, preset E, DLSS at the render size with an FSR 1.0 RCAS sharpen, +25 % over no upscaler in a GPU-bound 4K scene; DLSS output size / finish in the Options tab; the release still carries no native libraries) is 43.9 MB, 437 files plus the manifest (438 lines), sha256 `f276073edd3e9f218252b7593760fd55ce35bde7218562000364ac0c70066c2b`, tag `win-b0bbce05d5-d456427`.
2026-09-24 release from `d317261` (supersedes `d456427`: the performance overlay and game-thread profiler settings move to their own Options > Profiler tab with a Reset to defaults button; Render distance is its own section of the Optimizations tab) is 43.9 MB, 437 files plus the manifest (438 lines), sha256 `8845756e40756252a2136ef0d105e46741f1455f04f45c7265c30c1348135ea9`, tag `win-b0bbce05d5-d317261`.
`pzopt-files.txt`; the 2026-09-19 test build from `a202778` had 97) for game
revision `b0bbce05d5` (Build 42.20.4).

## Before rebooting (on Linux)

Get the zip somewhere Windows can read. The Linux side is ZFS and XFS, which
Windows cannot mount, so use one of:

```sh
# a) the NTFS partition (sda2, 480 GB, unmounted right now)
sudo mount -t ntfs3 /dev/sda2 /mnt && cp build/pzopt-b0bbce05d5-classes.zip /mnt/ && sudo umount /mnt

# b) a GitHub release asset (download from a browser on Windows)
gh release create win-test-b0bbce05d5 build/pzopt-b0bbce05d5-classes.zip \
  --title "Windows test build (42.20.4 / b0bbce05d5)" --notes "class overrides for the Windows test, see docs/windows-test.md"
```

Also bring this file (or open it on GitHub).

## On Windows

All commands are PowerShell. `$PZ` is the game folder; adjust it if Steam is not
in the default place (Steam, right-click the game, Manage, Browse local files).

### 1. Check the game

- Steam, Properties, Betas: the game must be on **Build 42.20.4**. Any other
  version makes the guard disable the overrides.
- Close the game.

```powershell
$PZ = "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid"
Get-Content "$PZ\ProjectZomboid64.json" | Select-String -Context 0,3 classpath
```

The classpath block must list `"."` **before** `"projectzomboid.jar"`. If it does
not, stop: loose class files would never load on this depot.

Confirm the folders the zip will create do not already exist (they should not on
a stock install; `media` exists and only gets one Lua file added under
`media\lua\client\pzopt`):

```powershell
Test-Path "$PZ\pzopt"; Test-Path "$PZ\zombie"; Test-Path "$PZ\org"; Test-Path "$PZ\se"
```

All four should print `False`.

### 2. Install

Unpack the zip straight into the game folder. It only adds files; it never
touches `projectzomboid.jar`.

```powershell
Expand-Archive -Path "$env:USERPROFILE\Downloads\pzopt-b0bbce05d5-classes.zip" -DestinationPath $PZ
Get-Content "$PZ\pzopt-files.txt" | Measure-Object -Line     # 421 on the 8cb8ccf, 8497d41, be1f28a and a4ed6f9 releases, 411 on the a9a1558 release, 393 on the 4ab3fe8 release, 392 on the 2c0ca9d release, 390 on the 53fe0e8 and 4826971 releases, 389 on the 76755df release, 386 on the 102ccf9 release, 385 on the e5d025a release, 384 on the d36540a release, 381 on the da3cdea release, 345 on the 1555855 release, 244 on the 2980cf3 and 3441a1c releases, 224 on the db825a0 release, 220 on the cd1a8de and cb48693 releases, 189 on the 0029f16 and 8bbc11a releases, 184 on the 51a6f78 release, 178 on the d434cde release, 177 on the 31f27f4 and c69c085 releases, 162 on the dcc0ce9 release, 161 on the 8889f72 release, 152 on the 1cf5080 and 667b210 releases, 146 on the 75f9365, f93158f, 4dbe655 and cc99c05 releases (131 on b0d4fe6, 115 on e398ce5, 105 on the 2d34aa9 release, 97 on the 2026-09-19 test build)
Get-Content "$PZ\pzopt\build-info.properties" | Select-String "^revision"
```

`Expand-Archive` refuses to overwrite existing files unless `-Force` is given.
Do not give it.

### 3. First launch

Launch from Steam normally. On the first boot the caches under
`%USERPROFILE%\Zomboid\pzopt\` (animation clips, texture-pack index, Lua
prototypes) are written, so it is a slower boot than the ones after it.

Then read the log:

```powershell
Select-String -Path "$env:USERPROFILE\Zomboid\console.txt" -Pattern "\[pzopt\]" | Select-Object -First 40
```

What to look for:

| Line | Meaning |
|---|---|
| `[pzopt] loaded override zombie.iso.IsoChunk (target revision b0bbce05d5, active)` | the guard passed; one such line per class |
| `game revision is X but overrides were built for b0bbce05d5; overrides disabled` | Windows depot is a different revision. Note X and stop; the test is over |
| `jar copy of ... differs from the one the overrides were built against` | same revision, different class bytes on the Windows jar. Note which class; the overrides are off |
| no `[pzopt]` line at all | the classes did not load. Re-check the classpath in step 1 and that `$PZ\pzopt\Overrides.class` exists |

If the guard disabled the overrides, everything below is moot but the game
should still run normally. That result is itself worth bringing back.

### 4. What to test

Do these in order and write down what happens at each step.

1. **Main menu.** Time from double-click to the menu, roughly. The TIS logo
   screens are skipped; the menu should appear directly.
2. **Options, Display.** The framerate combo has an **Uncapped** entry and
   300/330/400/430/500 fps entries, and a separate **Menu framerate** combo
   sits below it. Pick a value, apply, quit to desktop, relaunch: the value
   must survive. The setting lives in `%USERPROFILE%\Zomboid\pzopt\framecap.ini`.
3. **Continue a save.** Use a copy of a save, not the real one, if the save
   matters. Note the Continue-to-world time.
4. **Drive at max zoom** for a couple of minutes on a road with trees and
   buildings. Watch for:
   - black one-tile rectangles beside walls or black floor rectangles
   - trees that are missing, or that pop in late next to buildings
   - windows or glass doors that do not draw, or draw when they should be cut away
   - chunk edges that arrive noticeably late at speed
   - stutter on the 2 s beat (that would be a mod, PZDashboard, not this)
5. **Walk through buildings.** Cutaway walls around the player, doors and
   curtains opening and closing, windows breaking: the baked chunk textures must
   refresh when the state changes.
6. **Quit to menu and Continue again** without closing the game.
7. **Frame rate.** With the in-game limiter at Uncapped and vsync off, note the
   fps the game shows (or use the Steam overlay's fps counter). No MangoHud on
   Windows; a rough number is enough.

For a stock comparison on the same machine, press **Disable all (stock game)** in
Options > Optimizations and relaunch (**Enable all** brings the defaults back), or
create `$PZ\pzopt.properties` with the master switch off and relaunch; delete the
file to return to the defaults:

```properties
enabled=false
```

Every override then takes its stock path, the same fallback a build mismatch uses;
the per-key switches are ignored while it is off.

For per-frame numbers add `instrument=true` to that file (or to an otherwise
empty one): the game then writes `pzopt-frames.out` and `pzopt-chunks.out` in
`%USERPROFILE%\Zomboid`, which `harness/analyze.py` can read back on Linux.

### 5. If the game crashes

Bring back `%USERPROFILE%\Zomboid\console.txt` and any `hs_err_pid*.log` from
`$PZ`. The line right before the crash in console.txt is usually the answer.

### 6. Uninstall

Removes exactly the files the zip added, then the empty folders. The jar was
never modified, so no Steam file verification is needed.

```powershell
$PZ = "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid"
Get-Content "$PZ\pzopt-files.txt" | ForEach-Object { Remove-Item -LiteralPath (Join-Path $PZ $_) -ErrorAction SilentlyContinue }
Remove-Item "$PZ\pzopt-files.txt", "$PZ\pzopt.properties" -ErrorAction SilentlyContinue
foreach ($d in "pzopt","zombie","org","se","media\lua\client\pzopt") {
  Get-ChildItem "$PZ\$d" -Recurse -Directory -ErrorAction SilentlyContinue | Sort-Object FullName -Descending |
    Where-Object { -not (Get-ChildItem $_.FullName -Force) } | Remove-Item
  if ((Test-Path "$PZ\$d") -and -not (Get-ChildItem "$PZ\$d" -Force)) { Remove-Item "$PZ\$d" }
}
Test-Path "$PZ\pzopt"   # False
```

The caches in `%USERPROFILE%\Zomboid\pzopt\` can be deleted by hand; the game
never reads them without the overrides installed.

## Results 2026-09-19 (first Windows test)

Machine: the same box booted into Windows 11 IoT Enterprise LTSC 2024 (Ryzen 7 9800X3D, RTX 4090,
NVIDIA driver 616.92 / 32.0.16.1692, Azul Zulu 25 bundled JRE, ZGC). Game at
`C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid`, Build 42.20.4, jar sha256 and
size identical to the Linux depot's, classpath `"."` before the jar. Desktop 5120x2160, game
fullscreen at desktop resolution, vsync off, in-game cap 500 fps (`framecap.ini gameFps=500`),
launched through Steam. No MangoHud; utilization from `Get-Counter` + `nvidia-smi`.

Install: `Expand-Archive` of the zip, 97 manifest files, no pre-existing folders. Guard: every
override logged `active`, no revision or class-hash mismatch. Nothing had to be compiled.

### Boot

| Boot | Menu after process start | Notes |
|---|---|---|
| 1 (cold caches) | ~40 s | 1522 anim clips written, FMOD "Error initializing output device" (no audio device set up on this Windows install, not ours) |
| 2 (warm) | 21 s | anim clip cache 2161 hits / 0 misses |
| 3+ (warm, bench runs) | ~15 s | boot pump 5.9 s, anim sets preloaded 1.0–1.2 s |

Quitting from the main menu of boot 1 crashed on exit: `EXCEPTION_ACCESS_VIOLATION` in
`ZNetJNI64.dll` under `SteamWorkshop.n_GetInstalledItemFolders`, reached from
`RenderThread.shutdown → IsoPuddles.getInstance → Texture.getSharedTexture → ZomboidFileSystem.validatePrefix`
(the puddle renderer is constructed for the first time during shutdown and asks Steam for mod
folders after the Steam API is gone). Stock code path; a menu quit of a later boot did not
reproduce it. Dump kept as `harness/runs/win-boot-20260919/hs_err_pid3536-boot1-quit.log`.

Frame-cap combos: the maintainer confirmed Uncapped, 300–500 and the separate Menu framerate combo; a
500 fps choice survived a relaunch (`framecap.ini`, `[pzopt] frame cap: game 500 fps`).

### Bench route, optimized vs stock switches

`harness/run-win.ps1` (a PowerShell port of the run.sh steps a bench needs) on the committed
bench save, `--flag zoom=max` (max zoom on this install is **2.0**, not the 2.5 of the Linux
runs: options.ini `zoomLevels2x` tops out at 200), dashboard off, `instrument=true`. Stock =
every switch in the list above off. Analysis with `harness/analyze.py` (embeddable Python 3.12
under `%LOCALAPPDATA%\Programs\Python312-embed`).

| Run | fps mean | frame mean | p50 | p90 | p99 | p99.9 | max | >33 ms |
|---|---|---|---|---|---|---|---|---|
| `win-bench-stock-20260919-222950` | 172.5 | 5.8 ms | 4.8 | 10.1 | 19.1 | 28.4 | 46.8 | 8 |
| `win-bench-opt-20260919-222646` | 251.7 | 4.0 ms | 3.2 | 6.8 | 13.9 | 19.9 | 42.2 | 2 |

| Run | chunks | queue wait mean | queue wait p99 | recalc threads |
|---|---|---|---|---|
| stock | 4294 | 174 ms | 351 ms | World Streamer only |
| optimized | 4294 | 30 ms | 74 ms | 4 × pzopt-recalc |

Utilization over the route window (sysmon, ~62 samples each):

| Run | machine CPU | busiest core | game process | main thread | GPU load | GPU W |
|---|---|---|---|---|---|---|
| stock | 29 % | 66 % | 271 % of a core | 88 % of wall | 70 % (p90 99) | 139 |
| optimized | 28 % | 70 % | 274 % of a core | 93 % of wall | 60 % (p90 82) | 157 |

Finding against the objective: at 252 fps under a 500 cap neither the machine (28 % of 16
cores) nor the GPU (60 %) is saturated; the game thread is (`main` 93 % of wall). The
optimized build is game-thread-bound on Windows exactly as on Linux. Stock burns more GPU per
frame (the per-frame tree/translucent pass) for fewer frames.

Not directly comparable with the Linux native numbers (zoom 2.0 vs 2.5, 500 vs 240 cap,
Windows driver 616.92), but the direction and the p99 gain (19.1 → 13.9 ms) match the Linux
result (19.3 → 8.3 ms at zoom 2.5).

One stock run only: no noise floor yet. Two runs were discarded and are kept with an
`-INVALID-` suffix: the first optimized run lost window focus for 11 s right after the world
came up (options.ini `focusloss=true` pauses the game; `IsoChunk.update` stops, so the
sampler's first route frame was 12.6 s and the route started late), and the first stock run
got its properties on one comma-joined line (`powershell -File` does not split `-Prop a,b`;
run-win.ps1 now splits on commas itself).

Not yet done from the list above: the manual drive at max zoom, walking through buildings,
quit-to-menu-and-Continue, and the visible-fps reading with the in-game limiter at Uncapped.

### Zoom 2.5 (same evening, after the 250 % zoom level was added)

Same machine, cap and route; options.ini `zoomLevels2x` now starts at 250, so `zoom=max`
gives 2.5 and the zoom-out buffer is 12800x5400, the Linux configuration. Two stock runs
for the noise floor. Summaries in `harness/baseline/windows/z25/`.

| Run | fps mean | frame mean | p50 | p90 | p99 | p99.9 | max | >33 ms |
|---|---|---|---|---|---|---|---|---|
| `win-bench-z25-stock-20260919-224824` | 122.4 | 8.2 ms | 6.7 | 14.3 | 26.1 | 36.5 | 52.3 | 32 |
| `win-bench-z25-stock-20260919-225229` | 121.7 | 8.2 ms | 6.6 | 14.6 | 27.0 | 38.3 | 47.3 | 30 |
| `win-bench-z25-opt-20260919-224518` | 194.0 | 5.2 ms | 4.1 | 9.4 | 18.5 | 26.6 | 49.8 | 8 |

Noise floor between the two stock runs: 0.7 fps, 0.9 ms at p99, 1.8 ms at p99.9. The
optimized deltas (+72 fps, −7.6 ms p99, −10 ms p99.9, 30 → 8 frames over 33 ms) are far above
twice that. Both stock runs have the same cluster of 25–30 frames over 33 ms between 56 s and
90 s of the route (the W and N legs through the built-up area); the optimized run has eight
spread over the whole route. Chunk queue wait: stock 184 / 180 ms mean, optimized 31.5 ms.

| Run | machine CPU | busiest core | game process | main thread | GPU load | GPU W |
|---|---|---|---|---|---|---|
| stock 1 / 2 | 28 / 30 % | 74 / 72 % | 270 / 275 % of a core | 88 % of wall | 68 / 65 % (p90 99 / 100) | 127 / 120 |
| optimized | 26 % | 73 % | 270 % of a core | 93 % of wall | 52 % (p90 65) | 142 |

Same verdict as at zoom 2.0: the optimized build is bound by the game thread (93 % of wall)
with the GPU at half load and the machine at a quarter; stock spends more GPU per frame on the
per-frame tree/translucent pass and touches 100 % GPU in its p90 while producing 122 fps.

Against Linux native at zoom 2.5 and the 240 cap (route mean 6.2 → 4.4 ms, p99 19.3 → 8.3 ms):
Windows stock is slower (8.2 ms mean, 26.1 ms p99) and Windows optimized lands at 5.2 ms mean,
18.5 ms p99. The p99 gap to Linux (8.3 vs 18.5 ms) is the open Windows question; the driver
(616.92 vs the Linux 580 series), the 500 cap and the absence of MangoHud's frame pacing are
the candidates, and the 500-cap-vs-240-cap comparison is the first thing to run.

## What to bring back to Linux

- `console.txt` from the first optimized boot (the `[pzopt]` lines) and from a
  drive.
- The rough boot, load and fps numbers, optimized and stock.
- Screenshots of any artifact.
- `pzopt-frames.out` / `pzopt-chunks.out` if `instrument=true` was used.
- Whether the frame-cap combos and the menu cap worked.
