package pzopt;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * Runtime settings for the overrides, read once from pzopt.properties in the
 * game install directory (next to projectzomboid.jar, so scripts/pzopt.sh
 * status can show it) with -Dpzopt.<key> system properties overriding. Below both sits the
 * player's Zomboid/pzopt/options.ini, written by the Options > Optimizations tab
 * (pzopt.UserOptions); every key is exposed there (booleans as tick boxes, ints as combos) and
 * takes effect on the next launch, except the Profiler tab's (the overlay's), which apply at once
 * ({@link #reloadLive}). A key set in pzopt.properties or -D is shown pinned in the tab.
 *
 * Keys:
 *   enabled     true/false   master switch: false makes every override take its stock path, exactly as a build
 *                            mismatch does (Overrides.enabled() is false); the other keys are then ignored. The
 *                            "Disable all (stock)" / "Enable all" buttons of the Optimizations tab set it (default true).
 *                            The overlay and its profiler (the Profiler tab's keys) keep working with it off
 *                            (Overlay needs only Overrides.buildMatches(), 2026-09-24)
 *   parallel    true/false   kill switch: false forces the stock single-threaded pass (default true)
 *   workers     int          recalc pool width; clamped to [1, availableProcessors - 1] (default: min(4, cores - 1), 1 on
 *                            4 cores or fewer: there the three workers took the game thread's core, Dell i5-6300HQ 2026-09-21)
 *   instrument  true/false   record per-chunk timings and frame times to pzopt-*.out (default false)
 *   wake        true/false   wake the streamer thread on enqueue instead of the stock 140 ms polls (default true)
 *   chunkGridWidth int|auto  the player's chunk grid (loaded / simulated / rendered chunks around the player) is this many
 *                            chunks wide, 8 tiles each, instead of the stock size from the screen resolution (19 at 1080p
 *                            and above, 13 at 720p); odd, clamped to 5..41. Smaller = less world simulated, lit and baked
 *                            around the player (CPU-bound setups, heavy mod lists) but the world ends nearer the screen
 *                            edge at wide zoom; larger = more of everything. "auto" = wide enough to fill the screen at the
 *                            widest zoom, never below stock (25 at 5120x2160, 21 at 3840x2160, stock 19 at 1080p;
 *                            pzopt.ChunkGrid). 0 = stock (default 0; IsoChunkMap.CalcChunkWidth)
 *   chunkGridFollowView true/false  with a chunkGridWidth wider than stock, the grid centre follows the ground the camera
 *                            looks at on an upper floor (3 tiles north and west per level), so the screen corners stay
 *                            filled upstairs as on the ground floor; the player stays at least as deep inside the grid as
 *                            in a stock one. Single player only (default true; pzopt.ChunkGrid.heightShiftTiles)
 *   dev         true/false   development assertions, e.g. game-thread-only code reached from a worker (default false)
 *   luaChecksumExempt true/false  the pzopt Lua files (media/lua/{shared,client}/pzopt/) are left out of the multiplayer
 *                            Lua checksum a client sends to the server, like SandboxVars.lua is in stock: a server without
 *                            them otherwise refuses the join with "File doesn't exist on the server" (default true; not
 *                            tied to `enabled`, the files are on disk either way)
 *   updateCheck true/false   the main menu asks the GitHub releases once per boot whether a newer build for this game
 *                            revision exists and offers an "Update PZ Optimization" menu item that downloads and
 *                            installs it (pzopt.Updater; default true; never in harness runs)
 *   devUpdateOffer true/false  dev: offer the newest release for this revision whatever this build is, to see the
 *                            menu item, the dialog and the install (default false)
 *   translucentCache true/false  reuse prepared translucent render lists (default false)
 *   hotsaveIntervalSec int   (default 30) minimum seconds between the "hot saves" of the ancillary systems (meta grid, game time,
 *                            world map, entities) that ChunkSaveWorker runs on the game thread whenever its chunk
 *                            save queue drains; 0 = stock (every drain, i.e. every chunk row while moving)
 *   persistentVbo   true/false  sprite ring buffers use persistently mapped buffer storage instead of an orphaning
 *                            glBufferData + glMapBufferRange per 64 KB batch (default true)
 *   treesInChunkTexture true/false  static trees bake into the chunk textures; only translucent/fading trees are
 *   treeBakeMaxChunksPerSec int  above this many chunk hand-offs per second (pzopt.ChunkRate: walking ~9, 60 km/h ~32,
 *                            120 km/h ~72) trees are not baked into new chunk textures but drawn per frame, as with
 *                            treesInChunkTexture=false; textures already baked keep their trees until they re-bake.
 *                            A texture that lives a second or two while driving costs more to bake its trees into
 *                            (own texture plus neighbour copies) than drawing them per frame for that long: Dell
 *                            i5-6300HQ 2026-09-21, 120 km/h 28.6 -> 42.7 fps; walking is the other way round
 *                            (default 0 = always bake; the low-end profile sets 24)
 *   treeBakeDirect true/false  bake trees through IsoTree.render without a FBORenderTrees batch (the batch drops JUMBO trees)
 *   treeBakePass true/false   baked trees are drawn by the pzopt tree pass (pzopt.TreeBake): into every chunk-level texture
 *                            the sprite overlaps, last in the texture, with a depth that gets nearer with height like the
 *                            wall depth textures (issue #5: crowns clipped at the texture border, strips cut by upper
 *                            walls); false = the MinusFloor loop of the sprite path (default true)
 *   devRedrawFrame N          dev: force a full redraw of on-screen chunk levels N frames after the first render
 *   devCutawayLog true/false  dev: log every change of the buildings-to-collapse list and every orphan-structure
 *                            (carport / pergola roof) hide-show flip with the frame number (first 400 lines)
 *                            drawn every frame (default true; false = stock: every tree every frame)
 *   windowsInChunkTexture true/false  windows and glass doors bake like walls instead of being drawn every frame
 *                            (default true)
 *   translucentTilesInChunkTexture true/false  tiles flagged Translucent in tileGeometry.txt (fences, railings, wall
 *                            decorations, overlays, crops: 16k definitions) bake instead of being drawn every frame
 *                            (default true)
 *   curtainDepthNudgePct int     a curtain hanging in front of a window on its own square draws this many hundredths of
 *                            a tile nearer the camera than its tile geometry says (default 5; 0 = off). The north
 *                            window glass sits 0.017 tile in front of the north curtain in tileGeometry.txt; stock
 *                            draws both per frame in object order with depth writes off, so it never notices, but
 *                            once either bakes the depth test lets the glass through the closed curtain (issue #4)
 *   bakeBudget      int          chunk-level textures (re)baked per frame, the rest deferred to the next frame (default 8; 0 = stock, unlimited)
 *   lightingBudget  int          chunks whose square light info is refreshed per frame, the rest continue next frame (default 8; 0 = stock)
 *   lightingRebakeMs int         a chunk texture dirtied only by a lighting change is not re-baked more often than this (0 = stock)
 *   rebakeBudget    int          re-bakes per frame of on-screen chunk textures dirtied only by lighting, redraw or cutaways; past it the previous image stays for up to rebakeMaxFrames frames (default 4; 0 = every re-bake lands the same frame)
 *   rebakeMaxFrames int          longest hold for such a re-bake (default 3)
 *   lightingRebakeBudget int     lighting-only re-bakes (flag 32 alone: daylight drift, a lightning flash) started per frame
 *                            (default 8) ...
 *   lightingRebakeMaxFrames int  ... and how long one may stay stale (default 30). A lightning strike dirties every
 *                            on-screen texture; with the 3-frame cap of rebakeMaxFrames they all landed in one
 *                            50-90 ms frame, five times per strike (flash on, off, and every 250 ms of the ramp)
 *   lightingStrongDelta int      a square whose light moved by this much (0-255, largest channel, summed since its level was last
 *                            baked) marks the level strong: it skips lightingRebakeMs and the spread and re-bakes at once, like
 *                            stock (default 6; a moving torch changes squares by tens a frame, sky drift by 1 a tick)
 *   vehicleCull     true/false   a zombie's "is a vehicle between me and the player" test skips vehicles whose bounding circle
 *                            misses the segment before the exact box test (default true; 6 % of the game thread in downtown)
 *   animBonesParallel true/false the zombies' animation bone math (keyframe blend, twist bones, model and skin matrices,
 *                            9 % of the game thread on the Louisville horde) runs on worker threads after the object
 *                            postupdate loop instead of inline (default true; pzopt.AnimBatch)
 *   frameThreads    int          worker threads of the per-frame zombie batches (bone math, transition evaluation), clamped
 *                            to cores - 1 (default 8, or animBonesThreads when set; the game thread joins in)
 *   actionEvalParallel true/false the zombies' action-context transitions are evaluated on the frame workers after the
 *                            postupdate loop, applied on the game thread in order (default true; pzopt.ActionEval)
 *   devActionEvalCheck true/false dev: re-evaluate on the game thread at apply time and count disagreements
 *   devActionEvalUnitMultiplier true/false dev: the pre-fix deferred postupdate at perObjectMultiplier 1 (off-screen thump bursts; ThumpRig A/B)
 *   zombieCullSortFast true/false the per-frame zombie relevance sort computes each score once (default true; same order)
 *   lightingReadParallel true/false the lighting queue's pre-pass drain reads its chunk levels on the frame workers
 *                            (default true; pzopt.LightingBatch)
 *   devLightingReadCheck true/false dev: re-read a sample of squares after a parallel batch and count mismatches
 *   skinTransformsPrecompute true/false the bone worker also computes the skin-transform sets of the models drawn last
 *                            frame, so the render phase finds them ready (default true; needs animBonesParallel)
 *   skinPalettePrecompute true/false the worker stores each skin-transform set as the shader palette too; the draw data
 *                            copies it in one bulk put instead of sixteen per matrix (default true)
 *   shadowPrep      true/false   a zombie's shadow ellipse computed on the bone worker instead of in renderShadow (default true)
 *   boneIndexCache  true/false   bone-name -> index answers cached per skinning data on the animation player (default true)
 *   ecsLookupFast   true/false   the entity-component lookups behind getStateMachine / getActionContext / getVariable are a
 *                            memoised class walk, a lean map probe and a cached field on IsoZombie (default true)
 *   actionConditionFast true/false action-context transition conditions read boolean / int animation variables through
 *                            their typed getters instead of print-and-parse (default true; same outcomes)
 *   charDrawPrep    true/false   the render phase's characters draw: the on-screen zombies' model draw data (model lights,
 *                            the render data of every sub-model, the depth / lights / matrix palette init) is built on the
 *                            game's slot-init executor right after the players are drawn, joined before the sequential
 *                            pass enqueues it in the stock order (pzopt.CharDraw; default true)
 *   zombieAtlasFast true/false   a culled zombie's atlas-sprite draw through a flat copy of its render chain instead of
 *                            the virtual IsoZombie.render -> IsoGameCharacter.render chain (same tests, same writes,
 *                            same sprite; default true)
 *   charDrawThreads int          threads of the pre-pass pool (default 14, clamped to cores - 2)
 *   devSimChecksum  true/false   dev: per-frame hash of every zombie's state after postupdate in Zomboid/pzopt-sim.out
 *   lightingStrongBudget int     how many strong levels re-bake at once in one frame (default 8); the rest are held like
 *                            drift (they still re-bake within lightingRebakeMs / the spread). A beam touches a few levels a
 *                            frame; turning moves the out-of-sight fade across every exterior square, ~every level. 0 = no cap
 *   lightingStrongFrameMs int    a game-thread frame step longer than this halves the strong re-bake budget for the next
 *                            frame (down to 1; grows back by one per frame under 3/4 of it): a slow frame moves the
 *                            out-of-sight fade further, marks more levels strong and bakes more, which is the loop that
 *                            held downtown Louisville at twice the GPU time per frame (default 0 = fixed budget: at 20 it held the strong
 *                            levels of a scene that is steadily slow, 30-45 fps downtown, and the held squares are
 *                            re-marked every pass — stale light instead of a broken loop; keep it for A/Bs)
 *   lightingGlobalDeltaPct int   a per-frame move of the player's global light (colour mods, ambient, night, sky level) past this
 *                            percentage is a global event (lightning flash, fast-forwarded dusk): strong levels keep the spread
 *                            for lightingRebakeMaxFrames frames (default 2; a torch or the vision cone never moves it)
 *   lightingFlush   true/false   chunks the lightingBudget queue still holds are refreshed just before the next lighting pass
 *                            rewrites their dirty bits (default true; false loses them, chunk-sized stale light at 120 km/h)
 *   lightSwitchCheckFrames int   frames a light switch reuses its "has electricity around" answer (default 15; 0 = every frame, stock)
 *   cutawayFast     true/false   replay stored occluder masks for clean chunk levels instead of re-testing every square (default true)
 *   cutawayRadius   int          cutaway wall visits only consider chunks within this many chunks of the camera (0 = all on screen)
 *   gridStackInterval int        frames between buildings-in-front scans while the camera square and facing are unchanged (0 = every frame)
 *   roofHideDebounceFrames int   a carport / pergola roof (orphan structure) is hidden or shown only after the decision has held
 *                            for this many consecutive frames (default 8; 0 = stock, the roof can flip every frame)
 *   weatherMaskIdleSkip true/false skip the per-frame weather-mask tile scan and mask FBO draw while the player is outdoors and no cloud/fog/rain layer is active (default true)
 *
 * Game load (docs/plan-game-load.md):
 *   fileThreads     int          worker threads of the game's async file system (texture decode, model and animation
 *                            import, depth maps); stock is 2 on <= 4 cores, else 4 (default: max(4, cores / 2): with cores - 2
 *                            the game's own 8 meta-grid loader threads ran 2.5x slower and the load was 0.65 s longer, load-s3 vs load-s3f8)
 *   fileInflight    int          file tasks handed to those threads at once (stock 16; default 4 * fileThreads)
 *   fileInflightLoad int         the same from boot until the world is entered (default 128, at least fileInflight): the
 *                            game takes finished tasks once a frame, so 16 in flight drained the boot backlog at 16 a
 *                            frame and the Continue waited on it (Mac: assetLock2 wait 3.4 -> 2.0 s); in play the file
 *                            system's own priority order matters again (the pool's queue is first come, first served)
 *   pngPaethFast    true/false   PNG decode: the Paeth row filter of 4-byte pixels (every texture-pack page) runs as one
 *                            interleaved loop with the neighbours in locals (pzopt.PngFilters), byte-identical, the filter
 *                            40 % faster (it was 69 % of a page decode); palette images (every tile depth map) also copy
 *                            to RGBA with one table lookup a pixel and a bulk put a line, 3.2x faster (default true)
 *   depthMapFast    true/false   tile depth maps: each tile's pixels read with one bulk get per row instead of two
 *                            bounds-checked gets per pixel (TileDepthTexture override, pzopt.PngFilters.depthTile), the
 *                            same values (default true)
 *   zoneEdgePrefilter true/false map zones on Continue: a geometry zone's chunk tests skip the polygon edges whose bounding
 *                            box is more than a tile from the chunk side before the stock arithmetic (pzopt.ZoneGeom, Zone
 *                            override), the same answers; they were 20 % of the map-zones step (default true)
 *   lazyOptionsScreen true/false the options screen (main menu, in-game menu) is built when first opened; while hidden only
 *                            its key bindings are loaded (pzopt_optimizations_options.lua) (default true)
 *   electricityLevelRange true/false AmbientStreamManager.checkHaveElectricity (world entry, power changes) walks only the
 *                            levels some loaded chunk has instead of all 64, same squares in the same order (default true)
 *   fileThreadsWait int          file pool width while the loader thread only waits for the file tasks (assetLock2, the
 *                            main thread idle too); back to fileThreads afterwards (default: cores)
 *   textureBufferMb int          decoded-texture bytes that may wait for the render thread before the decoders pause
 *                            (stock 50; default 50: 256 MB let ~200 MB of uploads pile up on the render thread and
 *                            gave a 5 s frame a few seconds into the world, run load-s1-155507)
 *   parallelDepthMaps true/false  the 218 depth-map tilesets decode concurrently instead of one at a time under
 *                            one lock (default true)
 *   loaderCpuFixes  true/false   algorithmic fixes on the loader thread with identical results: MapCollisionData.init
 *                            resolves lot headers once per cell, IsoMetaGrid.checkVehiclesZones dedupes with a hash
 *                            set, IsoMetaCell.getChunk memoizes the lot header per cell, BuildingRoomsEditor
 *                            .checkBuildingAndRoomIDs indexes rooms once per cell (default true)
 *   scriptParserFast true/false  ScriptParser.stripComments in one linear pass and parseTokens without re-substringing
 *                            (identical output, tests/pzopt/ScriptTextTest; the stock passes cost 1.8 s at boot) (default true)
 *   earlyModels     true/false   ModelManager.create (models + the animation queue) runs right after the scripts load
 *                            instead of after the Lua load, giving the boot pump ~2 s more to import animations (default true)
 *   luaPrecompile   true/false   every Lua file (game, mods, map objects.lua) compiles on a pool during boot; the
 *                            game's LuaCompiler.loadis takes the prototype from that cache (default true)
 *   preloadAnimSets true/false   the player/zombie animation-set XML trees parse on a boot thread (1.1 s of the
 *                            loader thread otherwise) (default true)
 *   tileDefPreload  true/false   the tile definitions (~100k sprites and their properties, 0.6 s of the loader thread)
 *                            are built on a boot thread into a private sprite manager right after the tile packs
 *                            register; the first world load binds their textures and moves them in instead of parsing
 *                            the .tiles files again. Used once per boot, only when the mod list and language are
 *                            unchanged (pzopt.TileDefPreload) (default true)
 *   skipIdChecks    true/false   BuildingRoomsEditor.checkBuildingAndRoomIDs, a walk over every building and room of the
 *                            map that only logs ids that disagree with their position (never changes anything), runs in
 *                            debug mode only; stock runs it six times per world load, 0.37 s (default true)
 *   voronoiFast     true/false   the zombie-density voronoi noise of every map cell (IsoMetaGrid's loader threads, 1.2 s of
 *                            every world load) generates each sector's points once per cell and keeps the two smallest
 *                            distances instead of re-seeding and sorting boxed doubles per sample; identical values
 *                            (pzopt.ZombieNoise, tests/pzopt/ZombieNoiseTest) (default true)
 *   earlyTilePacks  true/false   the tile texture packs register and the 218 tile depth-map loads are queued right after the
 *                            UI packs and the script load instead of after the boot Lua load, so their decode (12-14 +
 *                            ~4 thread-s) runs during boot; with a warm load the loader used to wait 0.4-0.6 s for the
 *                            depth maps at the end of the load (default true)
 *   aotCache        true/false   JDK AOT cache (classes + method profiles of a whole session) so launches start warm: the
 *                            overrides run from pzopt/aot/pzopt.jar and the launcher JSON records the cache on one launch
 *                            and uses it from the next (pzopt.AotCache; the JSON is backed up once as
 *                            ProjectZomboid64.json.pzopt-backup). Continue -> world 2.64 -> 2.02 s, launch -> menu -0.6 s
 *                            on 2026-09-22. false puts the launcher back to the loose classes on the next boot (default true, the
 *                            maintainer's decision of 2026-09-22; harness runs leave it inert unless devAotCacheHarness)
 *   animClipCache   true/false   imported animation clips are written to <cache>/pzopt/anims/ after a stock import and
 *                            read from there on later boots instead of parsing the .X files with jassimp (default true)
 *   packIndex       true/false   version-0 texture packs keep their page end offsets in <cache>/pzopt/packs/*.idx so the
 *                            reader seeks instead of scanning 526 MB byte by byte at boot (default true)
 *   itemParamSwitch true/false   Item.DoParam dispatches through a switch on the lower-cased key instead of a chain of
 *                            361 equalsIgnoreCase tests per parameter (0.9 s of boot) (default true)
 *   dumpItems       true/false   after the scripts load, write every item script's fields to Zomboid/pzopt-items.out
 *                            (reflection) so two runs can be diffed (default false)
 *   bootPump        true/false   a thread pumps the async file system every 3 ms during GameWindow.init, so the queued
 *                            texture pages and animations decode during boot instead of after the main menu appears
 *                            (default true)
 *   bootFileThreads int          file pool width while the boot pump runs (default cores - 6: with cores - 2 the 16 cores
 *                            saturated and the main thread's Lua load ran 1.6x slower); shrinks to fileThreads at the load
 *   noLoadFade      true/false   GameLoadingState.exit does not fade the loading screen to black (350 ms of sleeps) before
 *                            the world's own 2 s fade-in, and MainScreenState.exit does not fade the main menu to black
 *                            (250 ms of renders and 33 ms sleeps) after Continue (default true)
 *   noClickToStart  true/false   the loading screen goes into the world the moment loading is done instead of waiting for
 *                            "click to start" / A (new games still honour noIntroWait) (default true)
 *   noLoadingScreen true/false   single player: no fade from black into the world (the fader loop skipped), and with a cached
 *                            view (resumeShot) no loading screen either; the world then appears from the player outwards
 *                            (pzopt.NoLoadingScreen; errors, conversions and multiplayer keep the stock screen) (default true)
 *   centerFirstEntryRadius int   A/B: chunks around the player handed to the chunk map before the first world frame
 *                            (0..3, default 3; 1 was inside the noise on the flip)
 *   centerFirstLoad true/false   single player: the initial chunk map loads nearest-first and the loader enters the world
 *                            once the 7 x 7 chunks around the player are loaded; the rest streams in during play like
 *                            chunks do while walking (pzopt.CenterFirstLoad) (default true)
 *   resumeShot      true/false   the exit save also keeps the view around the player (as resumeShotDetail keeps it, at their
 *                            zoom, pzopt-resume.jpg + .properties with the chunk grid's screen geometry); Continue shows the
 *                            7 x 7 chunks around the player from it at full brightness, whole chunks popping in in random
 *                            bursts like the live world's own build-up, paced to the save's last load time,
 *                            until the live world builds over it; a save without it gets the stock loading screen
 *                            (pzopt.ResumeShot) (default true)
 *   resumeShotDetail floors|buildings|world|full   what the exit shot keeps: floors = ground-level floors only;
 *                            buildings = every level's floors, walls, doors, furniture, items (no trees, translucent
 *                            tiles); world = everything static, trees included; full = the frame as seen, characters,
 *                            vehicles and corpses too (default full, the maintainer's choice 2026-09-24)
 *   fmodAsync       true/false   FMODManager.init (system + 12 banks, ~1.6 s) runs on a thread from the top of
 *                            GameWindow.mainThreadInit and is joined before the scripts load; the sound managers
 *                            (whose FMOD global parameters need the banks) are built at the join (default true)
 *   loadWorkers     int          recalc pool width while a world is loading (GameLoadingState.loader alive): the 361
 *                            chunks of the initial chunk map recalc on this many threads, then the pool shrinks back
 *                            to `workers` (default max(workers, cores / 2); clamped like workers. More is slower while the
 *                            recalc code is not C2-compiled yet: the interpreter / C1 profile counters are shared, so 15
 *                            workers made the first chunks 26 -> 48-65 ms each and the recalc CPU 4.5 -> 6.6-11 s,
 *                            load-b1-w15 / load-b2 2026-09-22; cores - 2 tripled it on 2026-09-19, load-s3)
 *   shaderCache     true/false   Model.CreateShader takes a shader an earlier model already created from pzopt.ModelShaders
 *                            instead of posting to the render thread and waiting one render step per model; the 73
 *                            animal models of AnimalDefinitions were 16.5 s of the load on a laptop whose loading-screen
 *                            render step is ~220 ms (GitHub issue #1) (default true)
 *   puddleCache     true/false   FBORenderCell.renderPuddles keeps the packed puddle vertices of each chunk level on the IsoChunk
 *                            and only patches the vertex lights, the camera jiggle and the depth per frame instead of
 *                            re-filtering, re-lighting and re-packing every wet square (pzopt.PuddleCache); 4.5 ms of a
 *                            13 ms thunderstorm frame at max zoom on 5120x2160 (default true)
 *   puddleCacheFrames  N      a cached puddle batch is rebuilt with the stock code after N frames at the latest,
 *                            staggered per chunk; bakes and cutaway changes rebuild it at once (default 60)
 *   puddleEarlyZ    true/false   the puddle shaders (media/shaders/pzopt_puddles_hq|mq|lq) take their depth from the vertex
 *                            instead of writing gl_FragDepth, so the GPU's early depth test drops every wet-ground pixel
 *                            hidden behind a wall, roof or object before the ~200-op puddle shader runs; same colour
 *                            math, same depth values (default true)
 *   rainSplashesFast true/false  rain splash starts are drawn by geometric skipping over the idle squares with a local
 *                            xorshift generator (one draw per splash) instead of one Rand.NextBool through the game's
 *                            CellularAutomatonRNG per idle square of every on-screen chunk level per frame; same
 *                            per-square start probability, timing, sprites and positions (pzopt.RainSplashes)
 *                            (default true)
 *   treeAppend      true/false   tree pass: when a chunk exports trees into a neighbour's texture for the first time
 *                            (a newly loaded chunk next to baked ones while driving), the quads are drawn on top of
 *                            that finished texture (same draw, same depth test, mipmaps regenerated) instead of
 *                            re-baking it; a texture that is dirty, off screen or not composited this frame is
 *                            re-baked as before, and every later change still re-bakes (default true)
 *   puddleVbo       true/false   with puddleCache: every cached puddle batch lives in its own GL buffer on the render
 *                            thread, re-uploaded only when a square's light changed, the camera crossed a chunk edge
 *                            or the batch was rebuilt; the camera jiggle is a translation of the ModelViewProjection.
 *                            Nothing is copied or patched per frame on either thread (pzopt.PuddleVbo); 12 % of a
 *                            thunderstorm frame on the laptop, ~7 ring-buffer uploads per frame on the render thread
 *                            (default true)
 *   rainTiles       true/false   ParticleRectangle renders its particles once at the origin and lists the screen cells; the
 *                            render thread uploads that template once and draws it once per cell with a translated
 *                            ModelViewProjection (pzopt.RainTiles) instead of ~90k per-cell quads through VBORenderer
 *                            (13x7 cells of 1024 rain particles at 5120x2160; 1.7 ms game thread, 6 ms render thread)
 *                            (default true)
 *   fogPass         true/false   heavy fog (ImprovedFog) drawn as one batch into a fog buffer of fogScalePct % of the viewport,
 *                            depth-tested against the scene depth read in place (the offscreen buffer's depth becomes a
 *                            texture), composited once with a depth-aware blend; the rectangle depth comes from the
 *                            vertex (early depth rejection), the noise is sampled with mipmaps, and the game thread
 *                            skips the per-square walk that only fed the row iterator (pzopt.FogPass,
 *                            docs/archive/2026-09-24/findings-fog-2026-09-21.md). Stock shades every pixel up to twelve times with a
 *                            gl_FragDepth write and one draw call per row segment: 447 -> 220 fps on the 5120x2160
 *                            120 km/h uncapped route, ~340 with the pass at 25 %. EXPERIMENTAL (2026-09-21): the
 *                            maintainer still sees a slight flicker on power lines in fog while the camera moves that
 *                            the frame captures do not reproduce; false = stock fog, no flicker (default true)
 *   fogDepthCopy    true/false   keep the offscreen buffer's depth a renderbuffer and copy it for the fog pass instead of
 *                            reading it in place (measurement, or a driver that refuses the texture; slower) (default false)
 *   fogScalePct     25..100      the fog buffer size per axis as % of the viewport (100 = full resolution; 50 = a quarter
 *                            of the fog fragment and blend work; below 100 the depth is reduced per block to its nearest
 *                            value and the composite is depth-aware, so thin objects keep their fog; 25 reads the same as
 *                            stock at 1:1 on 5120x2160 and 1920x1080 captures) (default 25)
 *   fogMaskFrames   N            the fog row walk reads per-chunk masks of the squares that take fog (exterior, not in a
 *                            room) instead of touching every square object (~10k per level per frame at max zoom on
 *                            5120x2160); a chunk's masks are refreshed every N frames, staggered per chunk, and the
 *                            row segments found are replayed while the visible diamond is unchanged and fewer than N
 *                            frames old, so a new room or wall reaches the fog within N frames; 0 = the stock
 *                            per-square walk every frame (default 20)
 *   vboBatchKb      4..1536      VBORenderer element buffer in KB (4 = stock). The rain FX add ~100k particle quads a frame
 *                            at 5120x2160 through VBORenderer.addQuad, and the 4 KB stock buffer flushes (glBufferData + draw)
 *                            every 28 quads; the render thread spent 73 % of a thunderstorm frame there (default 1024)
 *   vboFastQuads    true/false   VBORenderer.addQuad writes the four vertices of a textured quad with one position advance
 *                            and no per-vertex isFull/currentRun/position() round trips; same bytes, same indices (default true)
 *   mipmapArrays    true/false   ImageData builds texture mipmaps and premultiplies alpha row by row on byte[] copies
 *                            (pzopt.MipMaps) instead of the stock per-byte absolute reads/writes of the malloc'd
 *                            direct buffers; same pixels (tests/pzopt/MipMapsTest), same speed. Written for GitHub
 *                            issue #2, whose crash turned out to be the laptop (truncated stack address on a plain
 *                            spill reload), so this is a simplification, not the fix (default true)
 */
public final class Config {
   /** Every key read at init: key -> {effective value, default}, in declaration order (for the options tab). */
   private static final java.util.LinkedHashMap<String, String[]> REGISTRY = new java.util.LinkedHashMap<>();
   /** The keys that apply while the game runs (registered by {@link #loadLive}): the Profiler tab's. */
   private static final java.util.HashSet<String> LIVE = new java.util.HashSet<>();
   private static boolean loadingLive;
   private static final Properties props = load();
   /** The player's Options > Optimizations choices (Zomboid/pzopt/options.ini), below props and -D. */
   private static final Properties userProps = UserOptions.load();
   /** Master switch, read by {@link Overrides#enabled()}; false = stock behaviour everywhere. */
   public static final boolean ENABLED = bool("enabled", true);
   public static final boolean PARALLEL = bool("parallel", true);
   public static final int WORKERS = clampWorkers(integer("workers", defaultWorkers(Runtime.getRuntime().availableProcessors())));
   public static final boolean INSTRUMENT = bool("instrument", false);
   public static final boolean INPUT_LOG = bool("inputLog", INSTRUMENT); // every key / mouse / pad change the game thread sees -> pzopt-input.out (pzopt.InputRecorder); on in harness runs
   public static final boolean WAKE = bool("wake", true);
   public static final String CHUNK_GRID_SETTING = string("chunkGridWidth", "0"); // chunks per side of the player's chunk grid, or "auto"; 0 = the stock screen-size value (IsoChunkMap.CalcChunkWidth, pzopt.ChunkGrid)
   public static final boolean CHUNK_GRID_AUTO = "auto".equalsIgnoreCase(CHUNK_GRID_SETTING);
   public static final int CHUNK_GRID_WIDTH = CHUNK_GRID_AUTO ? 0 : Math.max(0, parseInt("chunkGridWidth", CHUNK_GRID_SETTING, 0));
   public static final boolean CHUNK_GRID_FOLLOW_VIEW = bool("chunkGridFollowView", true); // a grid wider than stock moves 3 tiles north + west per level the player stands on, the ground the camera looks at (IsoChunkMap.ProcessChunkPos, pzopt.ChunkGrid.heightShiftTiles)
   public static final boolean DEV = bool("dev", false);
   public static final boolean LUA_CHECKSUM_EXEMPT = bool("luaChecksumExempt", true); // NetChecksum skips media/lua/*/pzopt/ files: they only exist on clients
   public static final boolean UPDATE_CHECK = bool("updateCheck", true); // main menu: check the GitHub releases for a newer build and offer the update item (pzopt.Updater)
   public static final boolean DEV_UPDATE_OFFER = bool("devUpdateOffer", false); // dev: offer the newest release for this revision whatever this build is (menu item / dialog / install checks)
   public static final boolean TRANSLUCENT_CACHE = bool("translucentCache", false);
   public static final int HOTSAVE_INTERVAL_SEC = integer("hotsaveIntervalSec", 30);
   public static final boolean HOTSAVE_STAGED = bool("hotsaveStaged", false);
   public static final boolean PERSISTENT_VBO = bool("persistentVbo", true);
   public static final boolean PERSISTENT_VBO_FRAME_FENCE = bool("persistentVboFrameFence", false); // diagnostic: also wait for the fence of the frame the buffer was drawn in (did not affect the black squares, 2026-09-20)
   public static final int PERSISTENT_VBO_FRAME_LAG = integer("persistentVboFrameLag", 0); // diagnostic: wait for the fence of the unmap frame + N
   public static final int PERSISTENT_VBO_DELAY_US = integer("persistentVboDelayUs", 0); // diagnostic: CPU-only delay per persistent map (timing vs GPU race)
   public static final int PERSISTENT_VBO_SLOTS = integer("persistentVboSlots", 1); // storage slots per sprite buffer object (reuse distance x K)
   public static final boolean PERSISTENT_VBO_COHERENT = bool("persistentVboCoherent", true); // false: MAP_FLUSH_EXPLICIT + glFlushMappedBufferRange at unmap
   public static final boolean PERSISTENT_VBO_FINISH = bool("persistentVboFinish", false); // diagnostic: glFinish before every persistent map (GPU read race check)
   public static final boolean TREES_IN_CHUNK_TEXTURE = bool("treesInChunkTexture", true);
   public static final boolean WINDOWS_IN_CHUNK_TEXTURE = bool("windowsInChunkTexture", true);
   public static final boolean TRANSLUCENT_TILES_IN_CHUNK_TEXTURE = bool("translucentTilesInChunkTexture", true);
   public static final float CURTAIN_DEPTH_NUDGE = Math.max(0, integer("curtainDepthNudgePct", 5)) / 100.0F; // tiles; issue #4
   public static final int BAKE_BUDGET = integer("bakeBudget", 8);
   public static final int LIGHTING_BUDGET = integer("lightingBudget", 8);
   public static final int LIGHTING_REBAKE_MS = integer("lightingRebakeMs", 250);
   public static final int REBAKE_BUDGET = integer("rebakeBudget", 4);
   public static final int REBAKE_MAX_FRAMES = Math.max(1, integer("rebakeMaxFrames", 3));
   public static final int LIGHTING_REBAKE_BUDGET = Math.max(1, integer("lightingRebakeBudget", 8)); // lighting-only (flag 32) re-bakes started per frame once rebakeBudget applies
   public static final int LIGHTING_REBAKE_MAX_FRAMES = Math.max(1, integer("lightingRebakeMaxFrames", 30)); // longest hold of a lighting-only re-bake
   // zoom smoothness (2026-09-22): chunk-level textures that leave the screen when the camera zooms in are kept while
   // the chunk stays inside the screen rectangle of the widest zoom (high-res ones: of the widest zoom below 0.75)
   // instead of being freed, so zooming back out re-uses them; a kept texture returning to the screen is re-baked under
   // its own per-frame budget and shown as it was meanwhile; a level whose texture at the new scale (the 0.75 crossing)
   // is still to be baked shows its other-scale texture instead of nothing
   public static final boolean ZOOM_RETAIN = bool("zoomRetain", true);
   public static final int ZOOM_REBAKE_BUDGET = Math.max(1, integer("zoomRebakeBudget", 12)); // most chunk-level bakes a zoom change may start per frame (returned textures and, during a flood, new ones); the plan adapts below it
   public static final float ZOOM_FRAME_MS = Math.max(1.0F, integer("zoomFrameMs", 10)); // game-thread frame length above which the per-frame count halves (grows back under 3/4 of it)
   public static final boolean ZOOM_PLACEHOLDER = bool("zoomPlaceholder", true); // other-scale texture while the new scale bakes
   // zoom motion (2026-09-22, pzopt.ZoomEase): a manual zoom change takes zoomEaseMs of wall-clock time along a cubic Bézier
   // (stock: 0.03 per frame and a snap, 8 frames whatever the frame rate); 0 = the stock step
   public static final int ZOOM_EASE_MS = Math.max(0, integer("zoomEaseMs", 300));
   public static final String ZOOM_EASE = string("zoomEase", "0.25,0.1,0.25,1.0"); // Bézier control points x1,y1,x2,y2 (CSS "ease")
   public static final int LIGHTING_STRONG_DELTA = Math.max(1, integer("lightingStrongDelta", 6)); // a square's light moved by this much (0-255, summed since the level's last bake): the level re-bakes now instead of being held (pzopt.LightDirt)
   public static final int LIGHTING_STRONG_BUDGET = Math.max(0, integer("lightingStrongBudget", 8)); // strong levels that re-bake at once per frame; the rest take the holds (0 = unlimited, the 2026-09-21 behaviour: turning marks ~every exterior level strong, 10.8 fps in downtown Louisville)
   public static final float LIGHTING_STRONG_FRAME_MS = Math.max(0, integer("lightingStrongFrameMs", 0)); // game-thread frame step above which the strong re-bake budget halves for the next frame (grows back under 3/4 of it); 0 = fixed budget. Breaks the slow-frame -> more strong marks -> more bakes -> slower frame loop of downtown Louisville (FBORenderCell.pzoptStrongBudget)
   public static final float LIGHTING_GLOBAL_DELTA = Math.max(0, integer("lightingGlobalDeltaPct", 2)) / 100.0F; // a per-frame move of the global light (colour, ambient, night, sky) past this is a flash or dusk: the spread applies for lightingRebakeMaxFrames
   public static final boolean LIGHTING_FLUSH = bool("lightingFlush", true); // drain the lightingBudget queue before a lighting pass rewrites the dirty bits (false = the 2026-09-21 morning behaviour, for A/Bs)
   public static final int LIGHT_SWITCH_CHECK_FRAMES = integer("lightSwitchCheckFrames", 15);
   public static final int DEV_REDRAW_FRAME = integer("devRedrawFrame", 0);
   public static final boolean DEV_WORLD_SOUND_TIMING = bool("devWorldSoundTiming", false); // dev: per-section nanoTime totals of WorldSoundManager.addSound (pzoptTiming())
   public static final boolean DEV_PROFILE_LOG_OFF = bool("devProfileLogOff", false); // dev: harness runs sample the game-thread stack only while the overlay is shown (baseline for the overlay cost)
   public static final boolean DEV_CUTAWAY_LOG = bool("devCutawayLog", false); // dev: roof hide/show decisions per frame in the log
   public static final boolean GPU_SECTIONS = bool("gpuSections", false); // measurement only: GPU time per frame section in the log
   public static final boolean DEV_WEATHER_FX_OFF = bool("devWeatherFxOff", false); // measurement only: skip the weather FX pass
   public static final boolean TREE_BAKE_DIRECT = bool("treeBakeDirect", true);
   public static final int TREE_BAKE_MAX_CHUNKS_PER_SEC = integer("treeBakeMaxChunksPerSec", 0); // 0 = always bake (pzopt.ChunkRate)
   public static final boolean TREE_BAKE_PASS = bool("treeBakePass", true); // issue #5: pzopt.TreeBake draws the baked trees
   public static final boolean CUTAWAY_FAST = bool("cutawayFast", true);
   public static final int CUTAWAY_RADIUS = integer("cutawayRadius", 6);
   public static final int GRID_STACK_INTERVAL = integer("gridStackInterval", 8);
   public static final boolean WEATHER_MASK_IDLE_SKIP = bool("weatherMaskIdleSkip", true);
   public static final int CHUNK_HANDOFF_DIVISOR = integer("chunkHandoffDivisor", 8);
   public static final int WEATHER_FX_SCALE_PCT = integer("weatherFxScalePct", 100);
   public static final boolean CUTAWAY_VISIT_PREFILTER = bool("cutawayVisitPrefilter", true);
   public static final int ROOF_HIDE_DEBOUNCE_FRAMES = integer("roofHideDebounceFrames", 8);
   public static final boolean CUTAWAY_INVALIDATE_CHANGED = bool("cutawayInvalidateChanged", true);
   public static final boolean SOUND_ZONE_CACHE = bool("soundZoneCache", true);
   public static final boolean VEHICLE_CULL = bool("vehicleCull", true); // IsoZombie.isVehicleBetween: bounding-circle test per vehicle before the exact box test, over the per-frame list of vehicles near the player (pzopt.VehicleCull)
   public static final boolean PLAYER_LOS_FAST = bool("playerLosFast", true); // IsoPlayer.updateLOS: the remembered-spotted membership is an identity set beside the stack (stock walked the stack per spotted object per frame), loop invariants hoisted, the sneak spot modifier memoised per frame (pzopt.PlayerLos)
   public static final boolean ZOMBIE_SPOT_FAST = bool("zombieSpotFast", true); // IsoZombie.spottedNew: a zombie whose spot chance is already zero (facing away, beyond its vision radius) skips the modifiers, the vehicle test and the roll, keeping the same outcome
   public static final boolean PLAYER_LOS_NATIVE = bool("playerLosNative", false); // experiment: the LOS arithmetic (distances, close count, branch per object) in C++ over arrays Java packs, one FFM call per player per frame (pzopt.NativeLos, natives/libpzopt_los64.so built with PZOPT_NATIVE=1); off = the Java loop
   public static final String PLAYER_LOS_NATIVE_LIB = string("playerLosNativeLib", ""); // experiment: absolute path of libpzopt_los64.so when it is not under the game dir's natives/ (build/native/ of the checkout survives other sessions' builds; build/classes does not)
   public static final boolean ANIM_BONES_PARALLEL = bool("animBonesParallel", true); // the zombies' animation bone math (blend, twist, model and skin matrices) runs on worker threads after the postupdate loop (pzopt.AnimBatch)
   public static final int ANIM_BONES_THREADS = Math.max(1, integer("animBonesThreads", 8)); // (2026-09-22: the old name of frameThreads, read as its default)
   public static final int FRAME_THREADS = Math.max(1, integer("frameThreads", ANIM_BONES_THREADS)); // worker threads of the per-frame zombie batches (bone math, action-context transitions; pzopt.FrameBatch; the game thread joins in); clamped to cores - 1
   public static final boolean ANIM_BATCH_ASYNC = bool("animBatchAsync", true); // the zombies' bone batch (animBonesParallel) runs on the frame workers while the game thread carries on with the frame's logic; joined at IsoWorld.FinishAnimation (the game's own animation join point, before the render phase) or by the next frame batch (pzopt.AnimBatch, pzopt.FrameBatch)
   public static final boolean ANIMATOR_PARALLEL = bool("animatorParallel", true); // the zombies' animator, move deltas and model update (track tick) run on the frame workers after the transition evaluation, anim events captured and dispatched on the game thread in order; a zombie whose animator fires an event, or whose anim states read a side-effecting variable callback, finishes on the game thread (pzopt.AnimParallel; IsoGameCharacter, IsoZombie, AnimationTrack, AnimationMultiTrack overrides)
   public static final boolean ANIMATOR_PIPELINE = bool("animatorPipeline", true); // animatorParallel: the workers alone run the animator batch while the game thread dispatches each zombie's events in order as soon as its task is done (instead of taking tasks itself)
   public static final int FRAME_SPIN_US = Math.max(0, integer("frameSpinUs", 0)); // how long a frame worker spins for the next batch before parking (pzopt.FrameBatch); 0 = park at once
   public static final boolean GUARDED_CALLBACKS = bool("guardedCallbacks", true); // bHasTarget / shouldSprint / bPassengerExposed are read on the workers like pure callbacks; their rare side effect throws there and falls back to the game thread (pzopt.ActionEval.GUARDED_CALLBACKS)
   public static final boolean POOL_STATS_BATCHED = bool("poolStatsBatched", true); // the frame workers tally the game's pool statistics counters (shared AtomicDouble CAS loops) per thread and publish them once per batch (pzopt.PoolStats; PerformanceStatistic override)
   public static final boolean MODEL_LOCK_PER_INSTANCE = bool("modelLockPerInstance", true); // ModelInstance.lock is a new object per instance instead of stock's shared interned string (its only user, ModelSlot.Update, then no longer serialises the frame workers; ModelInstance override)
   public static final boolean HEAD_ON_WORKER = bool("headOnWorker", false); // a queued zombie's postUpdateAnimating head (forward direction, aim angle, turning flags) runs in its transition-evaluation task on a frame worker; a turn-around that would fire Turn180Started / TargetChanged goes back to the game thread (IsoGameCharacter override, pzopt.ActionEval)
   public static final boolean LAZY_POSE = bool("lazyPose", true); // an animation track finds a bone's keyframe span when the bone is first read after a tick instead of all 60 spans every tick (same spans; AnimationTrack override)
   public static final boolean DEV_ANIM_ASYNC_TRACE = bool("devAnimAsyncTrace", false); // log the stack of the first 20 early joins of the async bone batch (a game-thread touch of an in-flight player)
   public static final boolean ACTION_EVAL_PARALLEL = bool("actionEvalParallel", true); // the zombies' action-context transition evaluation runs on the frame workers between the postupdate loop and the animator updates (pzopt.ActionEval; IsoGameCharacter + ActionContext + MovingObjectUpdateScheduler overrides); a state with a Lua condition, a grappled / grappling / reanimated zombie and multiplayer take the stock path
   public static final boolean ACTION_SNAPSHOT_FILTER = bool("actionSnapshotFilter", true); // actionEvalParallel: the game-thread snapshot before a batch only reads the operands whose variable name is a callback with side effects (the name is fixed by the XML, the callback set by the character constructor), instead of resolving every operand of every transition of every batched zombie every frame
   public static final boolean EMITTER_PARAM_SKIP = bool("emitterParamSkip", true); // IsoGameCharacter.updateEmitter: the character's FMOD parameters (footstep material walk, zone lookup, ...) are recomputed only while its emitter has a running event instance or one about to start; a silent character's values go nowhere and are refreshed before its next sound starts
   public static final boolean SEPARATE_FAST = bool("separateFast", true); // IsoZombie.separate: the separation pass specialised for a zombie (stock's player bump / charged-spear half is unreachable there) with the "blocked to that neighbour" grid answers cached per square for the frame (pzopt.SeparateMask) instead of recomputed per character
   public static final boolean SLEEP_CHECK_MEMO = bool("sleepCheckMemo", true); // IsoPlayer.allPlayersAsleep memoised per frame: GameTime.getMultiplier asks it on the way into every character update, so the player array was walked tens of thousands of times a frame
   public static final boolean STATE_PARAM_MEMO = bool("stateParamMemo", true); // IsoZombie.getStateMachineParams keeps the last (state class, map) pair: an AI state reads several State.Param values of the same class in a row, each through the component map and an IdentityHashMap probe
   public static final int ZOMBIE_SIM_LOD_TILES = integer("zombieSimLodTiles", 0); // experiment: one extra simulation-level step (stock's own MovingObjectUpdateScheduler LOD, which already steps at 30 / 60 / 80 tiles) for a zombie further than this many tiles from the nearest player; 0 = stock
   public static final boolean SEPARATE_PARALLEL = bool("separateParallel", true); // the zombies' separation pass computed on the frame workers before the update loop, applied by the game thread in loop order (pzopt.SeparateBatch; needs separateFast); the neighbours' positions are then read at the top of the frame instead of as the loop advances
   public static final boolean ACTION_GROUP_CACHE = bool("actionGroupCache", true); // IsoZombie holds the "zombie" and "zombie-crawler" ActionGroups instead of asking ActionGroup for them by name (a lower-cased copy of the string and a HashMap probe) twice per zombie per frame
   public static final boolean PROFILER_THREAD_MEMO = bool("profilerThreadMemo", true); // GameProfiler.isValidThread memoised per thread: every performance probe in the game calls it twice and the valid-thread list is an ArrayList of names scanned with String.equals
   public static final int ZOMBIE_SIM_LOD_STEPS = Math.max(1, integer("zombieSimLodSteps", 1)); // how many extra simulation-level steps zombieSimLodTiles may take, each at twice the distance of the previous one
   public static final boolean ZOMBIE_LOD_DYNAMIC = bool("zombieLodDynamic", false); // how many zombies get a 3D model (stock 510) and blend their animations (stock 20) follows the frame cap: lowered while frames miss it, raised back while there is headroom (pzopt.ZombieLod)
   public static final int ZOMBIE_LOD_MIN_3D = Math.max(0, integer("zombieLodMin3d", 128)); // zombieLodDynamic's floor for zombies drawn as 3D models (the rest are flat sprites)
   public static final int ZOMBIE_LOD_MIN_BLEND = Math.max(0, integer("zombieLodMinBlend", 6)); // zombieLodDynamic's floor for zombies that blend their animations
   public static final int ZOMBIE_LOD_UNCAPPED_FPS = Math.max(0, integer("zombieLodUncappedFps", 0)); // zombieLodDynamic's target while uncapped; 0 = stock detail when uncapped
   public static final int ZOMBIE_CHECK_SPREAD = integer("zombieCheckSpread", 0); // experiment: a zombie's thump probe (the "is something thumpable in front of me" grid test) runs on one frame in N, spread by zombie id; 0 or 1 = stock, every frame. It must not cover the visible-to-player test: that one also decides whether the zombie is drawn
   public static final boolean ZOMBIE_CULL_SORT_FAST = bool("zombieCullSortFast", true); // IsoWorld.sceneCullZombies: one relevance score per zombie and a primitive key sort instead of a comparator sort that recomputes both scores per comparison; same order
   public static final boolean LIGHTING_READ_PARALLEL = bool("lightingReadParallel", true); // the pre-pass drain of the lighting queue reads its chunk levels on the frame workers, one task per level (pzopt.LightingBatch; FBORenderCell.pzoptFlushPendingLighting, LightingJNI override); the room-seen / meta hooks are applied by the game thread after the join
   public static final boolean DEV_LIGHTING_READ_CHECK = bool("devLightingReadCheck", false); // dev: after a parallel lighting batch re-read one square in sixteen on the game thread and count squares whose stored fields differ from the native's answer
   public static final boolean DEV_ACTION_EVAL_CHECK = bool("devActionEvalCheck", false); // dev: evaluate every batched transition set again on the game thread at apply time and count / log disagreements with the worker's result
   public static final boolean DEV_ACTION_EVAL_UNIT_MULTIPLIER = bool("devActionEvalUnitMultiplier", false); // dev: reproduce the bug fixed on 2026-09-22 (deferred postupdate ran at perObjectMultiplier 1, a reduced-simulation zombie counted each thump up to 16 times)
   public static final boolean SKIN_TRANSFORMS_PRECOMPUTE = bool("skinTransformsPrecompute", true); // the worker that updated a zombie's bones also multiplies them into the skin-transform sets its models used last frame, so the render phase finds them computed (AnimationPlayer.pzoptPrecomputeSkinTransforms; only with animBonesParallel)
   public static final boolean SKIN_PALETTE_PRECOMPUTE = bool("skinPalettePrecompute", true); // the worker also stores each precomputed skin-transform set as the shader palette buffer, so initMatrixPalette is one bulk copy (AnimatedModel override; needs skinTransformsPrecompute)
   public static final boolean SHADOW_PREP = bool("shadowPrep", true); // the worker that updated a zombie's bones also computes its shadow ellipse (pzopt.ShadowPrep); IsoZombie.calculateShadowParams serves it until the next update
   public static final boolean BONE_INDEX_CACHE = bool("boneIndexCache", true); // AnimationPlayer.getSkinningBoneIndex keeps the last few name -> index answers per skinning data (the shadow of every drawn character asked for three names per frame through two HashMap probes each)
   public static final boolean ECS_LOOKUP_FAST = bool("ecsLookupFast", true); // ECSComponent.getECSClass memoised per class, ECSEntity.tryGetECSComponent without the null checks and the reflective cast; IsoZombie keeps its StateMachineComponent in a field so getStateMachine / getActionContext / getCurrentState / isCurrentState / getVariable are field reads (stock: a class walk + a HashMap probe per call, ~5 % of the game thread on the Louisville horde)
   public static final boolean ACTION_CONDITION_FAST = bool("actionConditionFast", true); // CharacterVariableCondition: a boolean or int animation variable is compared from its typed getter instead of being printed to a string and parsed back per transition per frame (same result; floats and strings keep the stock path)
   public static final boolean CHAR_DRAW_PREP = bool("charDrawPrep", true); // the characters draw of the render phase: the on-screen zombies' draw data (ModelInstance.updateLights, ModelSlotRenderData.initModel + init, the camera record) built on the pass's own threads before the sequential enqueue pass (pzopt.CharDraw; FBORenderCell.renderMovingObjects, TextureDraw + ModelInstance overrides; the loop also skips the shadow call that returns without drawing for culled atlas zombies); players, animals, vehicles, fake-dead and hand-model zombies keep the stock path
   public static final boolean ZOMBIE_ATLAS_FAST = bool("zombieAtlasFast", true); // a culled zombie drawn as an atlas sprite (no active model, ~1,100 of the 1,600 on-screen objects of the Louisville horde) is drawn through a flat copy of its render chain (IsoZombie.pzoptRenderAtlas: the same tests, writes and sprite call as IsoZombie.render -> IsoGameCharacter.render, without the virtual chain); anything else falls back to the stock chain
   public static final int CHAR_DRAW_THREADS = Math.max(1, integer("charDrawThreads", 14)); // threads of the characters draw pre-pass pool (pzopt.CharDraw; clamped to cores - 2): the ~480 zombies' draw data must finish inside the chunk bakes, eight threads left the game thread waiting 0.2 ms a frame, twelve 0.13
   public static final boolean DEV_SIM_CHECKSUM = bool("devSimChecksum", false); // dev: one line per frame in Zomboid/pzopt-sim.out hashing every zombie's position, target, action state and animation state after postupdate (pzopt.SimChecksum, harness/simdiff.py)
   public static final String THREAD_NICE = string("threadNice", ""); // pzopt.ThreadNice rules "<comm prefix>=<nice|idle|batch>,...": lower the CPU priority of JIT / GC / background threads (empty = off)
   public static final boolean PROFILE_HANDSHAKE = bool("profileHandshake", true); // GameThreadProfile samples with Thread.getStackTrace (handshake with the game thread only) instead of ThreadMXBean.getThreadInfo (a global ThreadDump safepoint per sample in Java 25)
   public static final boolean LUA_PROFILE = bool("luaProfile", INSTRUMENT); // pzopt.LuaProfile: the game-thread sampler also names the Lua function it caught (Zomboid/pzopt-lua.out); on in instrumented runs
   public static final String GC_MODE = string("gcMode", "g1"); // pzopt.GcChoice: the next launch's collector. g1 (default, the maintainer's decision 2026-09-23) | auto (G1 on gcG1Cores cores or fewer) | stock (the launcher's own ZGC; undoes our switch)
   public static final int GC_G1_CORES = integer("gcG1Cores", 4);
   /**
    * pzopt.GcChoice: the next launch's C2 compiles without speculative traps (-XX:PerMethodTrapLimit=0
    * -XX:PerBytecodeTrapLimit=0, marker -Dpzopt.jit=steady): every branch is compiled instead of pruning the ones the
    * profile never saw. A walk through the world deoptimized ~2,000 compiled methods per 100 s (unstable_if: a branch
    * never taken while profiling, then taken in a new street) and recompiled 16,600, a compiler thread busy for the whole
    * walk; without the traps the JIT used 0.43 cores instead of 0.83, same game-thread time (the flip, 2026-09-24).
    */
   public static final boolean JIT_STEADY = bool("jitSteady", true);
   public static final int GC_PAUSE_MS = integer("gcPauseMs", 0); // -XX:MaxGCPauseMillis added with the G1 switch (0 = G1's own 200 ms target)
   public static final String JIT_MODE = string("jitMode", "auto"); // pzopt.JitGovernor: tiered (stock) | c1play (C2 excluded from world start on) | c2idle (C2 threads at SCHED_IDLE from world start, Linux; no measured gain) | auto (default: c1play on jitC1Cores cores or fewer, stock above)
   public static final int JIT_C1_CORES = integer("jitC1Cores", 4);
   public static final boolean LIGHTING_VISION_PARALLEL = bool("lightingVisionParallel", false); // pzopt.VisionBatch (off: on the CPU-bound 4-core Dell the workers got no core and the game thread did the batch itself, no fps change): the vision tests of every dirty chunk level on the FrameBatch workers before LightingJNI.updateChunk
   public static final boolean DEV_VISION_CHECK = bool("devVisionCheck", false); // dev: recompute each precomputed vision value on the game thread and count mismatches (log line)
   public static final int RENDER_CHUNK_PREWARM = integer("renderChunkPrewarm", -1); // render chunks (texture + depth + FBO) created when the world loads, so first bakes in play find the pool filled (0 = off, -1 = auto: the most render chunks in use at once in the previous session + 5 %, kept in Zomboid/pzopt/renderchunk-pool.txt; 96 on the first run; a Dell walk used 114)
   public static final boolean WEATHER_NO_GLGET = bool("weatherNoGlGet", true); // WeatherParticleDrawer takes the current shader program from ShaderHelper's record instead of glGetInteger (a driver sync per call)
   public static final boolean DEV_GL_STATE_CHECK = bool("devGlStateCheck", false); // dev: also query the driver and count disagreements with the recorded program
   public static final boolean PROPERTY_SURFACE_NOALLOC = bool("propertySurfaceNoAlloc", true); // PropertyContainer.initSurface walks its entries without allocating a capturing lambda per call (C1 code, jitMode)
   public static final boolean CHUNK_MAP_FAST = bool("chunkMapFast", true); // IsoChunkMap.getGridSquareDirect without helper calls, calculateZExtentsForChunkMap over the grid width instead of length x length
   public static final boolean SAVE_CELL_ASYNC = bool("saveCellAsync", true); // ZombiePopulationManager.requestSaveCell snapshots without saveLock (held by the MapCollisionData thread through each native cell write) and keeps one pending write per cell (a drive unloads dozens of chunks of the same cell)
   public static final boolean WORLD_SOUND_FAST = bool("worldSoundFast", true); // addSound walks only the loaded chunk grid; FishSchoolManager.addSoundNoise skips a repeat of an identical call in the same game minute (the house alarm adds its sound every frame)
   public static final boolean KEYBOARD_FRESH = bool("keyboardFresh", true); // GameKeyboard reads the keyboard poll it swaps in (stock: the previous one, a frame behind mouse and pad)
   public static final boolean INPUT_LATCH = bool("inputLatch", true); // the game thread gets a fresh event pump + input poll from the render thread right before its input swap (pzopt.InputLatch)
   public static final int INPUT_LATCH_WAIT_US = integer("inputLatchWaitUs", 1500); // longest the game thread waits for that poll
   public static final boolean FRAME_START_GATE = bool("frameStartGate", false); // the game thread waits for pipeline room before it reads input, not after building the frame
   public static final int GPU_MAX_FRAMES = integer("gpuMaxFrames", 0); // frames the render thread lets queue behind the GPU (GL fence after the swap), 0 = the driver's own limit
   public static final boolean CURSOR_LATCH = bool("cursorLatch", false); // the game-drawn cursor (lock cursor to window) moved to the newest pointer position right before the frame is drawn
   public static final int AIM_HOLD_MS = integer("aimHoldMs", 150); // right-mouse hold before the player aims (stock 150: shorter taps open the context menu)
   public static final boolean VBLANK_LOCK = bool("vblankLock", false); // with vsync on: the game starts each frame a measured time before the display's vblank (GLX_NV_delay_before_swap)
   public static final int VBLANK_LOCK_MARGIN_US = integer("vblankLockMarginUs", 1000); // margin over the p95 of go -> swap
   public static final boolean VSYNC_ADAPTIVE = bool("vsyncAdaptive", false); // with vsync on: swap interval -1 (GLX/WGL_EXT_swap_control_tear), a late frame tears instead of waiting for the next refresh
   public static final boolean REFLEX_BOOST = bool("reflexBoost", false); // NVIDIA: hold "prefer maximum performance" (NVML PowerMizer, released when the game exits) while a world is loaded
   public static final boolean REFLEX_SLEEP = bool("reflexSleep", false); // Reflex-style just-in-time sleep before the input sample (pzopt.LowLatency)
   public static final int REFLEX_QUEUE_US = integer("reflexQueueUs", 500); // the frame queue reflexSleep aims for
   public static final int REFLEX_CAP_FPS = integer("reflexCapFps", 0); // with reflexSleep: frame starts no faster than this (-1 = auto with vsync: refresh - refresh^2/3600, 0 = off)
   public static final boolean OCCLUSION_SKIP_LIGHTING_ONLY = bool("occlusionSkipLightingOnly", true);
   public static final boolean LIGHT_INFO_CHUNK_GATE = bool("lightInfoChunkGate", true);
   public static final boolean LIGHT_INFO_ONCE_PER_FRAME = bool("lightInfoOncePerFrame", true);
   public static final int FILE_THREADS = Math.max(1, integer("fileThreads", Math.max(4, Runtime.getRuntime().availableProcessors() / 2)));
   public static final int FILE_INFLIGHT = Math.max(1, integer("fileInflight", 4 * FILE_THREADS));
   public static final boolean PNG_PAETH_FAST = bool("pngPaethFast", true);
   public static final boolean DEPTH_MAP_FAST = bool("depthMapFast", true);
   public static final boolean ZONE_EDGE_PREFILTER = bool("zoneEdgePrefilter", true);
   public static final boolean ELECTRICITY_LEVEL_RANGE = bool("electricityLevelRange", true);
   public static final boolean LAZY_OPTIONS_SCREEN = bool("lazyOptionsScreen", true); // the options screen built when first opened, key bindings at once (Lua)
   public static final boolean LUA_EVENT_PROFILE = bool("luaEventProfile", false); // measurement: time every Lua event handler (pzopt.LuaEventProfile)
   public static final boolean DEV_ELECTRICITY_CHECK = bool("devElectricityCheck", false); // dev: count the stock 64-level walk in the same call (instrumented runs)
   public static final int FILE_INFLIGHT_LOAD = Math.max(FILE_INFLIGHT, integer("fileInflightLoad", 128));
   public static final int FILE_THREADS_WAIT = Math.max(1, integer("fileThreadsWait", Runtime.getRuntime().availableProcessors()));
   public static final int TEXTURE_BUFFER_MB = Math.max(1, integer("textureBufferMb", 50));
   public static final boolean PARALLEL_DEPTH_MAPS = bool("parallelDepthMaps", true);
   public static final boolean LOADER_CPU_FIXES = bool("loaderCpuFixes", true);
   public static final boolean SCRIPT_PARSER_FAST = bool("scriptParserFast", true);
   public static final boolean FMOD_ASYNC = bool("fmodAsync", true);
   public static final boolean NO_LOAD_FADE = bool("noLoadFade", true);
   /** new game: show click-to-start as soon as loading is done instead of after the 33 s intro text. */
   public static final boolean NO_INTRO_WAIT = bool("noIntroWait", true);
   /** the loading screen enters the world as soon as it is loaded instead of waiting for "click to start". */
   public static final boolean NO_CLICK_TO_START = bool("noClickToStart", true);
   /** single player: a black screen instead of the loading screen, then the world with no fade from black. */
   public static final boolean NO_LOADING_SCREEN = bool("noLoadingScreen", true);
   /** single player: the initial chunks load nearest-first and the world is entered once the 7 x 7 around the player are in. */
   public static final boolean CENTER_FIRST_LOAD = bool("centerFirstLoad", true);
   public static final int CENTER_FIRST_ENTRY_RADIUS = integer("centerFirstEntryRadius", 3); // chunks around the player handed over before the first world frame (0..3)
   /** exit saves keep the view around the player; Continue shows it with chunks popping in (else the stock loading screen). */
   public static final boolean RESUME_SHOT = bool("resumeShot", true);
   /** how much of the world the exit shot keeps: floors | buildings | world | full (pzopt.ResumeShot.beginCapture) */
   public static final String RESUME_SHOT_DETAIL = string("resumeShotDetail", "full").trim().toLowerCase(java.util.Locale.ROOT);
   public static final boolean BOOT_PUMP = bool("bootPump", true);
   public static final boolean EARLY_MODELS = bool("earlyModels", true);
   public static final boolean LUA_PRECOMPILE = bool("luaPrecompile", true);
   public static final boolean PRELOAD_ANIM_SETS = bool("preloadAnimSets", true);
   public static final boolean TILE_DEF_PRELOAD = bool("tileDefPreload", true);
   public static final boolean SKIP_ID_CHECKS = bool("skipIdChecks", true);
   public static final boolean VORONOI_FAST = bool("voronoiFast", true);
   public static final boolean EARLY_TILE_PACKS = bool("earlyTilePacks", true);
   public static final boolean AOT_CACHE = bool("aotCache", true);
   /** dev: let a harness run drive the aotCache cycle (normally inert there: run.sh owns the launcher JSON). */
   public static final boolean DEV_AOT_CACHE_HARNESS = bool("devAotCacheHarness", false);
   public static final boolean ANIM_CLIP_CACHE = bool("animClipCache", true);
   /** auto = honour options.ini (frameRate / uncappedFPS); true / false force the frame cap off / on for a run. */
   public static final String UNCAPPED_FPS = string("uncappedFps", "auto");
   /** Run key: > 0 locks game and menus at exactly this fps for this launch, nothing persisted (A/B runs; the uncappedFps restore dance can leave another cap). */
   public static final int FRAME_CAP_FPS = integer("frameCapFps", 0);
   /** Variable refresh (pzopt.Pacing, Display): per-frame step / swap timestamps to Zomboid/pzopt-pacing.out (harness/pacing.py). */
   public static final boolean PACING_LOG = bool("pacingLog", INSTRUMENT);
   /** Variable refresh (pzopt.Vrr): auto = act while the kernel reports VRR on (Linux), on = always, off = never. */
   public static final String VRR = string("vrr", "auto");
   /** While VRR is active, keep the frame cap inside its range (refresh - refresh^2/3600) when the player's cap is higher or uncapped. */
   public static final boolean VRR_CAP = bool("vrrCap", true);
   /** > 0: the VRR cap in fps instead of the formula. */
   public static final int VRR_CAP_FPS = integer("vrrCapFps", 0);
   /** macOS on Apple silicon: present through Metal (pzopt.MacPresent) for ProMotion / Adaptive-Sync timing; on | off (auto = on). */
   public static final String MAC_PRESENT = string("macPresent", "off");
   /** macPresent: glFinish instead of glFlush before Metal reads the frame (only if the flush hand-off ever shows torn frames). */
   public static final boolean MAC_PRESENT_FINISH = bool("macPresentFinish", false);
   /** macPresent: CAMetalLayer drawables in flight (2 = one shown + one queued; 3 queues ~3 frames of latency at the panel's rate). */
   public static final int MAC_PRESENT_DRAWABLES = integer("macPresentDrawables", 2);
   /** macPresent: shift the game's frame starts so frames are ready just before their panel slot (latency; pzopt.MacPresent.steer). */
   public static final boolean MAC_PRESENT_PHASE = bool("macPresentPhase", true);
   /** macPresent + the borderless option: a native macOS fullscreen Space instead of a screen-sized borderless window. */
   public static final boolean MAC_NATIVE_FULLSCREEN = bool("macNativeFullscreen", true);
   /** Rig: every 1000th bridged frame (5 times), compare the IOSurface rows with the GL back buffer and log whether the picture reaches Metal upright. */
   public static final boolean DEV_MAC_PRESENT_CHECK = bool("devMacPresentCheck", false);
   /**
    * Hold each swap until step start + a high percentile of the recent step-to-ready times, so present gaps equal game-time
    * steps (capped only). off | cpu | gpu | gpufinish | auto (auto = gpu while VRR is active, else off); see pzopt.Pacing.
    */
   public static final String PRESENT_PACING = string("presentPacing", "auto");
   public static final int PRESENT_PACING_PCT = Math.max(50, Math.min(100, integer("presentPacingPct", 90)));
   public static final int PRESENT_PACING_MARGIN_US = Math.max(0, integer("presentPacingMarginUs", 200));
   /**
    * A borderless window covering the monitor is created as a GLFW monitor window at the desktop's own mode (no mode switch,
    * no auto-iconify), so the compositor sees a fullscreen window: KWin / Mutter / gamescope only switch variable refresh on
    * for fullscreen windows. auto = Linux (X11 / XWayland / Wayland), true = every platform, false = stock window.
    */
   public static final String BORDERLESS_FULLSCREEN = string("borderlessFullscreen", "auto");
   /**
    * The frame limiter parks the game thread until limiterSpinUs before the next step instead of spinning it through the
    * whole wait (a core at full clock for nothing: at a 120 fps cap half of the game thread's time on the flip). Default on
    * except on Windows, where a park wakes on the 1 ms timer tick.
    */
   public static final boolean LIMITER_SLEEP = bool("limiterSleep", !System.getProperty("os.name", "").startsWith("Win"));
   /** limiterSleep: how long before the step the park ends; the stock loop spins the rest (the game thread's timer slack is 1 ns on Linux). */
   public static final int LIMITER_SPIN_US = Math.max(0, integer("limiterSpinUs", System.getProperty("os.name", "").startsWith("Win") ? 1500 : 200));
   /**
    * Hybrid CPUs (Zen 5 + Zen 5c, Intel P + E, Apple P + E): which cores the game's threads run on (pzopt.CorePlacement).
    * off (stock: the OS decides) | auto (background threads on the efficient cores; the game and render threads there too
    * while they keep the frame cap, moved to the fast cores when they fall short) | efficient (everything on the efficient
    * cores) | performance (game and render threads on the fast cores, everything else on the efficient ones).
    */
   public static final String CORE_PLACEMENT = string("corePlacement", "auto");
   /**
    * The machine's logical CPUs, read when Config loads (boot, before corePlacement narrows any thread's affinity: Java's
    * availableProcessors() follows the calling thread's mask, so a pool sized later from the game thread got 8 of 24).
    */
   public static final int CPUS = Runtime.getRuntime().availableProcessors();
   /**
    * AMD GPUs on Linux: the GPU clock level while a world is on screen (pzopt.GpuPstate): off | auto (the lowest fixed
    * level whose GPU time per frame fits the frame cap, automatic clocks otherwise) | standard | min_sclk | min_mclk | peak.
    */
   public static final String GPU_PSTATE = string("gpuPstate", "auto");
   /** gpuPstate=auto: a level holds while the frame's GPU time p90 stays under this share of the frame interval. */
   public static final int GPU_PSTATE_FIT_PCT = Math.max(30, Math.min(100, integer("gpuPstateFitPct", 95)));
   /** The vision cone's edge blur sums its 25 taps once per vision texel instead of once per world pixel (pzopt.VisBlur; same result). */
   public static final boolean VIS_BLUR_REDUCE = bool("visBlurReduce", true);
   /** The lighting thread parks to its next update instead of LWJGL's sleep + yield-spin (pzopt.LightingSync). */
   public static final boolean LIGHTING_SYNC_PARK = bool("lightingSyncPark", true);
   /**
    * corePlacement: the CPUs background threads may use instead of every efficient core (a list like "4-7,16-19"; empty =
    * the efficient class). Packing them onto fewer cores lets the others reach their deepest idle state.
    */
   public static final String CORE_BACKGROUND_CPUS = string("coreBackgroundCpus", "");
   /** corePlacement: the CPUs the game / render / GL threads use when they are on the fast class (a list; empty = the fast class). */
   public static final String CORE_CRITICAL_CPUS = string("coreCriticalCpus", "");
   /** corePlacement=auto: the game step's p90 (share of the frame interval) above which the game and render threads move to the fast cores. */
   public static final int CORE_PROMOTE_PCT = Math.max(30, Math.min(100, integer("corePromotePct", 85)));
   /** corePlacement=auto: back to the efficient cores when the work, scaled by the measured fast/slow speed ratio, fits this share of the interval. */
   public static final int CORE_DEMOTE_PCT = Math.max(20, Math.min(95, integer("coreDemotePct", 70)));
   /** corePlacement=auto: the least time (ms) the game and render threads stay on the fast cores before the governor may move them back. */
   public static final int CORE_HOLD_MS = Math.max(250, integer("coreHoldMs", 3000));
   public static final boolean PACK_INDEX = bool("packIndex", true);
   public static final boolean ITEM_PARAM_SWITCH = bool("itemParamSwitch", true);
   public static final boolean DUMP_ITEMS = bool("dumpItems", false);
   public static final int BOOT_FILE_THREADS = Math.max(1, integer("bootFileThreads", Math.max(4, Runtime.getRuntime().availableProcessors() - 6)));
   public static final int LOAD_WORKERS = clampWorkers(integer("loadWorkers", Math.max(WORKERS, Runtime.getRuntime().availableProcessors() / 2)));
   public static final boolean SHADER_CACHE = bool("shaderCache", true);
   public static final boolean MIPMAP_ARRAYS = bool("mipmapArrays", true);
   public static final boolean PUDDLE_CACHE = bool("puddleCache", true); // FBORenderCell.renderPuddles reuses packed puddle vertices per chunk level (pzopt.PuddleCache)
   public static final int PUDDLE_CACHE_FRAMES = integer("puddleCacheFrames", 60); // backstop rebuild interval of a cached puddle batch, staggered per chunk
   public static final boolean PUDDLE_EARLY_Z = bool("puddleEarlyZ", true); // puddle shaders take their depth from the vertex, no gl_FragDepth write: early depth test rejects occluded wet ground (media/shaders/pzopt_puddles_*)
   public static final boolean RAIN_SPLASHES_FAST = bool("rainSplashesFast", true); // splash starts by geometric skipping with a local generator instead of Rand.NextBool per idle square per frame (pzopt.RainSplashes)
   // --- render-resolution upscaling (docs/plan-upscalers.md, pzopt.RenderScale / pzopt.Upscaler) ---
   public static final String UPSCALER = string("upscaler", "off").trim().toLowerCase(java.util.Locale.ROOT); // off | bicubic | fsr1 | dlss | xess: the world pass renders at upscalerQuality's fraction of the screen and is resolved to the screen by this upscaler; the UI, text and the stock screen shader stay native
   public static final String UPSCALER_QUALITY = string("upscalerQuality", "quality").trim().toLowerCase(java.util.Locale.ROOT); // quality 67 % | balanced 59 % | performance 50 % | ultra 33 % | native 100 % (dlss: DLAA) of the screen size per axis
   public static final int UPSCALER_SCALE_PCT = integer("upscalerScalePct", 0); // explicit render scale in percent (10..100) instead of upscalerQuality's preset; 0 = use the preset
   public static final int FSR_SHARPNESS_PCT = Math.max(0, Math.min(100, integer("fsrSharpnessPct", 80))); // RCAS sharpening after EASU as a percentage: 100 = the sharpest (0 stops of attenuation), 0 = 2 stops (the mildest); AMD ships 80-100 in its sample
   public static final boolean DLSS_SHARPEN = bool("dlssSharpen", false); // dlss: the NGX sharpening flag (a mild extra sharpen; off = plain super resolution)
   public static final boolean UPSCALER_OBJECT_MV = bool("upscalerObjectMv", true); // dlss / xess: characters and vehicles write their own motion vectors (a rect masked by the depth band) on top of the camera motion; false = camera motion only
   public static final boolean DEV_DLSS_GAPS = bool("devDlssGaps", false); // dev: GL timestamps at the DLSS hand-over and after the wait, matched with the evaluation's Vulkan start / end (the GL <-> Vulkan switch cost) in the dlss stats line
   public static final boolean DEV_UPSCALER_STOCK_VIS_BLUR = bool("devUpscalerStockVisBlur", false); // dev A/B: the view-cone blur keeps the stock unscaled displaySize under an upscaler (the 2026-09-23 "second view cone" bug)
   public static final boolean DEV_UPSCALER_LOG = bool("devUpscalerLog", false); // dev: log every upscaler state change, the shared-image import and the first evaluations
   public static final String DLSS_PRESET = string("dlssPreset", "e").trim().toLowerCase(java.util.Locale.ROOT); // dlss: the render preset for every quality level: default (the driver's: transformer K / M / L), e or f (the older convolutional models, half the cost), j, k, l, m. e since 2026-09-23: at 5120x2160 K costs 1.5 ms a frame, more than the render scale saves
   public static final int DLSS_OUTPUT_PCT = integer("dlssOutputPct", 67); // dlss: DLSS writes this percentage of the screen size (never below the render size; its cost follows the output pixels) and dlssOutputFilter finishes the upscale; 0 = the screen size. 67 since 2026-09-23: the configuration that beats no upscaler (+22 % in a GPU-bound 4K scene) with a sharper image than full-size DLSS K
   public static final String DLSS_OUTPUT_FILTER = string("dlssOutputFilter", "rcas").trim().toLowerCase(java.util.Locale.ROOT); // dlss with dlssOutputPct: bicubic = the stock screen shader samples the smaller output directly (no extra pass), fsr1 = EASU + RCAS first, rcas = RCAS alone at the DLSS output size, then the bicubic
   public static final boolean DLSS_FLUSH_AFTER_WAIT = bool("dlssFlushAfterWait", false); // dlss A/B: glFlush right after GL's wait on the DLSS-done semaphore (2026-09-23: no effect, the ~0.2 ms after an evaluation is not unflushed GL work)
   public static final boolean DLSS_FLUSH_AFTER_COMPOSITE = bool("dlssFlushAfterComposite", false); // dlss: also glFlush after the composite quad (A/B)
   public static final boolean DLSS_WAIT_OUTPUT_ONLY = bool("dlssWaitOutputOnly", false); // dlss: GL's wait names only the output image (A/B of the layout hand-back)
   public static final boolean DLSS_DIRECT_COLOR = bool("dlssDirectColor", true); // dlss: the world pass draws straight into the DLSS colour image (its colour attachment swapped for the frame) instead of a copy at the resolve (2026-09-23: same image, +3 %)
   public static final boolean DLSS_PIPELINE = bool("dlssPipeline", false); // dlss: two image sets, the composite shows the previous frame's evaluation so GL never waits for the evaluation it just submitted (+1 frame of world latency)
   public static final boolean DLSS_AUTO_EXPOSURE = bool("dlssAutoExposure", false); // dlss: the NGX AutoExposure flag (a luminance reduction per frame; the input is LDR at exposure 1, so off by default since 2026-09-23)
   public static final boolean DLSS_DEPTH_INVERTED = bool("dlssDepthInverted", false); // dlss: the scene depth's larger values are nearer (the NGX DepthInverted flag)
   public static final boolean DLSS_JITTER = bool("dlssJitter", true); // dlss: draw the world with the Halton sub-pixel jitter DLSS accumulates from (false = no jitter, an A/B)
   public static final float DLSS_JITTER_SIGN = integer("dlssJitterSign", 1) < 0 ? -1.0F : 1.0F; // dlss: the sign the viewport offset is reported to NGX with (1 or -1, an A/B of the convention)
   public static final float DLSS_MV_SIGN = integer("dlssMvSign", 1) < 0 ? -1.0F : 1.0F; // dlss: the sign of the motion vectors (1 = current to previous position, NGX's convention; -1 the other way)
   public static final boolean TREE_APPEND = bool("treeAppend", true); // a new chunk's trees are drawn into the finished neighbour textures they reach instead of re-baking those textures (tree pass, issue #5)
   public static final boolean PUDDLE_VBO = bool("puddleVbo", true); // cached puddle batches kept in per-chunk-level GL buffers, jiggle as a matrix translation (pzopt.PuddleVbo)
   public static final boolean RAIN_TILES = bool("rainTiles", true); // weather particles rendered once as a template and drawn once per screen cell (pzopt.RainTiles)
   public static final int VBO_BATCH_KB = integer("vboBatchKb", 1024); // VBORenderer element buffer (4 = stock): rain particles flush every 28 quads at 4 KB
   public static final boolean VBO_FAST_QUADS = bool("vboFastQuads", true); // VBORenderer.addQuad writes the four vertices with one position advance
   public static final boolean FOG_PASS = bool("fogPass", true); // ImprovedFog as one batch into a scaled, depth-copied fog buffer (pzopt.FogPass)
   public static final int FOG_SCALE_PCT = integer("fogScalePct", 25); // fog buffer size per axis, % of the viewport
   public static final boolean HDR = bool("hdr", false); // HDR output (pzopt.Hdr): FP16 window on Wayland tagged with the output's HDR image description, world highlights expanded, UI at the desktop's white
   public static final boolean HDR_AUTO = bool("hdrAuto", true); // HDR output whenever the screen is HDR (Linux: a Wayland output in HDR mode; macOS: an EDR display), even with hdr=false; hdr=true forces it
   public static final String HDR_ENCODE = string("hdrEncode", "auto").toLowerCase(java.util.Locale.ROOT); // on: ext_linear description + encode pass at the swap (standard, exact roll-off); off: no description, the compositor's own SDR decode shows the FP16 values above 1.0 (KWin; ~0.3 ms a frame cheaper at 4K); auto: off on KDE Plasma, on elsewhere
   public static final int HDR_UI_NITS = integer("hdrUiNits", 0); // UI / SDR white on the panel in cd/m², 0 = the desktop's reference white
   public static final int HDR_PAPER_PCT = integer("hdrPaperPct", 100); // world paper white, % of the UI white (lower = more room for highlights)
   public static final int HDR_PEAK_NITS = integer("hdrPeakNits", 0); // brightest highlight in cd/m², 0 = the panel's peak
   public static final int HDR_ITM_PCT = integer("hdrItmPct", 50); // highlight expansion strength (0 = the SDR picture in an HDR container)
   public static final int HDR_BLOOM_PCT = integer("hdrBloomPct", 30); // bloom from the expanded highlights, % strength (0 = off)
   public static final int HDR_LIGHT_PCT = integer("hdrLightPct", 100); // light-map gain: lit squares (lamps, torches, fire) brightened by their light over the ambient, % strength
   public static final int HDR_GLINT_PCT = integer("hdrGlintPct", 100); // sun glints and sky reflections on water and puddles, lamp glints at night, % strength (0 = off, shaders stay stock)
   public static final int HDR_SUN_PCT = integer("hdrSunPct", 60); // sunlit outdoors on a clear day brighter than the SDR picture by this %, scaled by sun height and clouds (0 = daylight as SDR)
   public static final int HDR_SATURATION_PCT = integer("hdrSaturationPct", 0); // extra world chroma, %
   public static final boolean HDR_UNTESTED_PLATFORMS = bool("hdrUntestedPlatforms", false); // dev: allow the untested Windows (scRGB) HDR path; HDR is Linux / macOS without it
   public static final String HDR_TUNE = string("hdrTune", "");
   public static final boolean HDR_WIN_FLIP = bool("hdrWinFlip", true); // Windows HDR: write the interop texture upside down (D3D rows run top-down); false if a driver maps it the other way
   public static final String HDR_DUMP_AT = string("hdrDumpAt", ""); // dev: seconds after the world is up at which frames are dumped (with the tune file's [sweep] sets), e.g. "20,35" // dev: tuning file re-read once a second (key=value lines, [sweep] sets for pzopt-hdr.req dumps)
   public static final boolean DEV_FOG_NO_DRAW = bool("devFogNoDraw", false); // measurement: the fog pass does everything but the rectangle draw call
   public static final boolean DEV_FOG_FLAT = bool("devFogFlat", false);
   public static final int DEV_FOG_DEPTH_VIEW = integer("devFogDepthView", 0); // measurement: the composite shows 1 = the scene depth, 2 = the fog texel depth, 3 = the fog buffer alpha (R/G = depth * 255 integer / fraction)
   public static final boolean FOG_DEPTH_COPY = bool("fogDepthCopy", false); // keep the offscreen depth a renderbuffer and copy it for the fog pass (measurement / driver fallback) // measurement: the rectangles with a flat fragment shader (no noise fetches)
   public static final int FOG_MASK_FRAMES = integer("fogMaskFrames", 20); // a chunk's fog masks (which squares take fog) are refreshed this often; 0 = read every square every frame
   // The Profiler tab's keys (the overlay and its game-thread profiler): they apply while the game runs. loadLive()
   // reads them at init and again from reloadLive() when the player changes one (UserOptions.set); pzopt.Overlay and
   // pzopt.GameThreadProfile read them per use or re-derive their state (Overlay.reconfigure). 2026-09-24.
   public static volatile boolean OVERLAY_SAMPLING; // measure at all (ring, GL timer queries, sampler thread); off by default since 2026-09-21
   public static volatile boolean OVERLAY;
   public static volatile boolean OVERLAY_LOG;
   // The overlay's elements, each a dropdown on the Profiler options tab: "off" or the element's own options.
   public static volatile String OVERLAY_STATS; // off | fps (the fps line) | tails (+ p99 / jitter lines) | full (+ utilization); tails by default since 2026-09-23 (overlay cost pass)
   public static volatile String OVERLAY_TREE; // the game-thread tree: off | 0 (phases only) | 3 | 5 | 8 sub-phases per phase
   public static volatile String OVERLAY_VERDICT; // off | short ("GPU bound") | detailed (+ the two biggest game-thread sub-phases)
   public static volatile String OVERLAY_GRAPH; // the frame-time graph: off | 240 | 480 | 960 frames (2 px each)
   public static volatile String OVERLAY_FLAME; // the game-thread flame graph: off | right (900 px column) | right-wide (1400) | below (under the frame graph); off by default since 2026-09-23 (the heaviest element)
   public static volatile boolean OVERLAY_TEXTURE; // draw the panel into a texture at each 4 Hz refresh, one quad per frame (Overlay.renderToTexture); false = every glyph as a sprite every frame
   public static volatile int OVERLAY_REFRESH_MS; // how often the overlay's numbers, tree and texture are refreshed
   public static volatile int OVERLAY_GRAPH_HZ; // frame-graph redraws per second into the overlay texture; 0 = drawn live every frame (default: 30 Hz measured no cheaper on the Mac)
   public static volatile int OVERLAY_FLAME_DEPTH; // rows of the flame graph (frames from GameWindow.frameStep up)
   public static volatile int GAME_THREAD_PROFILE_HZ; // game-thread stack samples per second (10..1000); sampling runs when the tree, the flame graph, the detailed verdict or the frame log wants it 25 since 2026-09-23: each capture pauses the game thread ~150 us on the Mac, 1.5 % of wall at 100 Hz
   public static volatile String OVERLAY_FONT; // auto = CodeSmall / CodeMedium / CodeLarge by screen height (Overlay.font), or a UIFont name
   public static volatile String OVERLAY_CORNER;
   public static volatile boolean OVERLAY_FPS_COLOR; // colour the fps number (see Overlay.fpsColor)
   public static volatile boolean OVERLAY_FPS_FOLLOW_CAP; // thresholds are % of the cap when one is set; else the fixed fps ones
   public static volatile int OVERLAY_FPS_CAP_BLUE_PCT; // "at the cap": at or above this % of it
   public static volatile int OVERLAY_FPS_CAP_GREEN_PCT;
   public static volatile int OVERLAY_FPS_CAP_YELLOW_PCT; // below: red
   public static volatile int OVERLAY_FPS_BLUE_ABOVE; // uncapped / follow-cap off: fixed fps thresholds
   public static volatile int OVERLAY_FPS_GREEN_ABOVE;
   public static volatile int OVERLAY_FPS_YELLOW_ABOVE; // below: red
   public static volatile String OVERLAY_FPS_COLOR_BLUE; // a name Overlay.color knows or RRGGBB hex
   public static volatile String OVERLAY_FPS_COLOR_GREEN;
   public static volatile String OVERLAY_FPS_COLOR_YELLOW;
   public static volatile String OVERLAY_FPS_COLOR_RED;

   static {
      loadLive();
   }

   private static void loadLive() {
      loadingLive = true;
      OVERLAY_SAMPLING = bool("overlaySampling", false);
      OVERLAY = bool("overlay", false);
      OVERLAY_LOG = bool("overlayLog", false);
      OVERLAY_STATS = string("overlayStats", "tails");
      OVERLAY_TREE = string("overlayTree", "5");
      OVERLAY_VERDICT = string("overlayVerdict", "detailed");
      OVERLAY_GRAPH = string("overlayGraph", "240");
      OVERLAY_FLAME = string("overlayFlame", "off");
      OVERLAY_TEXTURE = bool("overlayTexture", true);
      OVERLAY_REFRESH_MS = integer("overlayRefreshMs", 250);
      OVERLAY_GRAPH_HZ = integer("overlayGraphHz", 0);
      OVERLAY_FLAME_DEPTH = integer("overlayFlameDepth", 24);
      GAME_THREAD_PROFILE_HZ = integer("gameThreadProfileHz", 25);
      OVERLAY_FONT = string("overlayFont", "auto");
      OVERLAY_CORNER = string("overlayCorner", "tl");
      OVERLAY_FPS_COLOR = bool("overlayFpsColor", true);
      OVERLAY_FPS_FOLLOW_CAP = bool("overlayFpsFollowCap", true);
      OVERLAY_FPS_CAP_BLUE_PCT = integer("overlayFpsCapBluePct", 98);
      OVERLAY_FPS_CAP_GREEN_PCT = integer("overlayFpsCapGreenPct", 90);
      OVERLAY_FPS_CAP_YELLOW_PCT = integer("overlayFpsCapYellowPct", 50);
      OVERLAY_FPS_BLUE_ABOVE = integer("overlayFpsBlueAbove", 300);
      OVERLAY_FPS_GREEN_ABOVE = integer("overlayFpsGreenAbove", 150);
      OVERLAY_FPS_YELLOW_ABOVE = integer("overlayFpsYellowAbove", 100);
      OVERLAY_FPS_COLOR_BLUE = string("overlayFpsColorBlue", "blue");
      OVERLAY_FPS_COLOR_GREEN = string("overlayFpsColorGreen", "green");
      OVERLAY_FPS_COLOR_YELLOW = string("overlayFpsColorYellow", "yellow");
      OVERLAY_FPS_COLOR_RED = string("overlayFpsColorRed", "red");
      loadingLive = false;
   }

   public static final int OVERLAY_KEY = integer("overlayKey", 67); // LWJGL 2 code, 67 = F9; used when the Lua binding is absent

   private Config() {
   }

   private static Properties load() {
      Properties p = new Properties();
      File f = new File("pzopt.properties"); // cwd is the install dir when launched by ProjectZomboid64.exe
      if (f.isFile()) {
         try (InputStream in = new FileInputStream(f)) {
            p.load(in);
         } catch (Exception e) {
            Log.warn("could not read " + f.getAbsolutePath() + ": " + e);
         }
      }
      return p;
   }

   /** -Dpzopt.<key>, then the install dir's pzopt.properties, then the player's options.ini. */
   private static String raw(String key) {
      String v = System.getProperty("pzopt." + key);
      if (v == null) {
         v = props.getProperty(key);
      }
      return v != null ? v : userProps.getProperty(key);
   }

   private static <T> T register(String key, T effective, T def) {
      REGISTRY.put(key, new String[] {String.valueOf(effective), String.valueOf(def)});
      if (loadingLive) {
         LIVE.add(key);
      }
      return effective;
   }

   private static boolean bool(String key, boolean def) {
      String v = raw(key);
      return register(key, v == null ? def : Boolean.parseBoolean(v.trim()), def);
   }

   private static String string(String key, String def) {
      String v = raw(key);
      return register(key, v == null ? def : v.trim(), def);
   }

   private static int parseInt(String key, String v, int def) {
      try {
         return Integer.parseInt(v.trim());
      } catch (NumberFormatException e) {
         Log.warn("bad integer for " + key + ": " + v + "; using " + def);
         return def;
      }
   }

   private static int integer(String key, int def) {
      String v = raw(key);
      if (v == null) {
         return register(key, def, def);
      }
      try {
         return register(key, Integer.parseInt(v.trim()), def);
      } catch (NumberFormatException e) {
         Log.warn("bad integer for " + key + ": " + v + "; using " + def);
         return register(key, def, def);
      }
   }

   // --- options tab (Options > Optimizations; see UserOptions) --------------------------------

   /** Is this one of the keys read at init? */
   public static boolean knows(String key) {
      return key != null && REGISTRY.containsKey(key);
   }

   /** Does a change of this key apply while the game runs ({@link #reloadLive}) rather than on the next launch? */
   public static boolean isLive(String key) {
      return key != null && LIVE.contains(key);
   }

   /**
    * The player changed {@code key} (UserOptions.set, game thread): when it is a live key, re-read every live key
    * through the usual -D > pzopt.properties > options.ini order and return true; the caller then lets the classes
    * that derive state from them know. A key pinned by -D or pzopt.properties keeps its pinned value.
    */
   static synchronized boolean reloadLive(String key) {
      if (!isLive(key)) {
         return false;
      }
      loadLive();
      return true;
   }

   /** The value in force now (since boot for every key but the live ones; before the clamps some keys apply), or null for an unknown key. */
   public static String value(String key) {
      String[] r = key == null ? null : REGISTRY.get(key);
      return r == null ? null : r[0];
   }

   /** The build's default on this machine, or null for an unknown key. */
   public static String defaultValue(String key) {
      String[] r = key == null ? null : REGISTRY.get(key);
      return r == null ? null : r[1];
   }

   /**
    * What pins the key above the player's options.ini: "-Dpzopt.<key>" or "pzopt.properties",
    * or null when the menu choice is what counts.
    */
   public static String pinnedBy(String key) {
      if (key == null) {
         return null;
      }
      if (System.getProperty("pzopt." + key) != null) {
         return "-Dpzopt." + key;
      }
      return props.getProperty(key) != null ? "pzopt.properties" : null;
   }

   /** min(4, cores - 1); 1 on 4 cores or fewer, where a pool only competes with the game, lighting and render threads. */
   static int defaultWorkers(int cores) {
      return cores <= 4 ? 1 : Math.min(4, cores - 1);
   }

   /** At least 1, and never the full processor count: the render thread keeps one core. */
   static int clampWorkers(int requested) {
      int max = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
      return Math.max(1, Math.min(requested, max));
   }

   /** Effective pool width: 1 when parallelism is switched off or the build guard tripped. */
   public static int effectiveWorkers() {
      return PARALLEL && Overrides.enabled() ? WORKERS : 1;
   }

   /** Wake-on-enqueue is also off when the build guard tripped, so a mismatched build is fully stock. */
   public static boolean effectiveWake() {
      return WAKE && Overrides.enabled();
   }

   public static String describe() {
      return "parallel=" + PARALLEL + " workers=" + WORKERS + " (effective " + effectiveWorkers() + ", cores "
            + Runtime.getRuntime().availableProcessors() + ") wake=" + WAKE + " (effective " + effectiveWake() + ") chunkGridWidth=" + CHUNK_GRID_SETTING + " instrument=" + INSTRUMENT + " dev=" + DEV + " luaChecksumExempt=" + LUA_CHECKSUM_EXEMPT
            + " translucentCache=" + TRANSLUCENT_CACHE + " hotsaveIntervalSec=" + HOTSAVE_INTERVAL_SEC + " persistentVbo=" + PERSISTENT_VBO + " treesInChunkTexture=" + TREES_IN_CHUNK_TEXTURE + " windowsInChunkTexture=" + WINDOWS_IN_CHUNK_TEXTURE + " translucentTilesInChunkTexture=" + TRANSLUCENT_TILES_IN_CHUNK_TEXTURE + " treeBakePass=" + TREE_BAKE_PASS + " curtainDepthNudgePct=" + Math.round(CURTAIN_DEPTH_NUDGE * 100.0F) + " bakeBudget=" + BAKE_BUDGET + " lightingBudget=" + LIGHTING_BUDGET + " lightingRebakeMs=" + LIGHTING_REBAKE_MS + " rebakeBudget=" + REBAKE_BUDGET + " rebakeMaxFrames=" + REBAKE_MAX_FRAMES + " lightingRebakeBudget=" + LIGHTING_REBAKE_BUDGET + " lightingRebakeMaxFrames=" + LIGHTING_REBAKE_MAX_FRAMES + " zoomRetain=" + ZOOM_RETAIN + " zoomRebakeBudget=" + ZOOM_REBAKE_BUDGET + " zoomFrameMs=" + Math.round(ZOOM_FRAME_MS) + " zoomPlaceholder=" + ZOOM_PLACEHOLDER + " zoomEaseMs=" + ZOOM_EASE_MS + " zoomEase=" + ZOOM_EASE + " lightingStrongDelta=" + LIGHTING_STRONG_DELTA + " lightingStrongBudget=" + LIGHTING_STRONG_BUDGET + " lightingStrongFrameMs=" + Math.round(LIGHTING_STRONG_FRAME_MS) + " lightingGlobalDeltaPct=" + Math.round(LIGHTING_GLOBAL_DELTA * 100.0F) + " lightingFlush=" + LIGHTING_FLUSH + " lightSwitchCheckFrames=" + LIGHT_SWITCH_CHECK_FRAMES + " cutawayFast=" + CUTAWAY_FAST + " cutawayRadius=" + CUTAWAY_RADIUS + " gridStackInterval=" + GRID_STACK_INTERVAL + " roofHideDebounceFrames=" + ROOF_HIDE_DEBOUNCE_FRAMES + " weatherMaskIdleSkip=" + WEATHER_MASK_IDLE_SKIP + " worldSoundFast=" + WORLD_SOUND_FAST + " keyboardFresh=" + KEYBOARD_FRESH + " inputLatch=" + INPUT_LATCH + " inputLatchWaitUs=" + INPUT_LATCH_WAIT_US + " frameStartGate=" + FRAME_START_GATE + " gpuMaxFrames=" + GPU_MAX_FRAMES + " reflexSleep=" + REFLEX_SLEEP + " reflexBoost=" + REFLEX_BOOST + " reflexQueueUs=" + REFLEX_QUEUE_US + " reflexCapFps=" + REFLEX_CAP_FPS + " vsyncAdaptive=" + VSYNC_ADAPTIVE + " vblankLock=" + VBLANK_LOCK + " vblankLockMarginUs=" + VBLANK_LOCK_MARGIN_US + " aimHoldMs=" + AIM_HOLD_MS + " cursorLatch=" + CURSOR_LATCH + " saveCellAsync=" + SAVE_CELL_ASYNC + " chunkMapFast=" + CHUNK_MAP_FAST + " propertySurfaceNoAlloc=" + PROPERTY_SURFACE_NOALLOC + " weatherNoGlGet=" + WEATHER_NO_GLGET + " renderChunkPrewarm=" + RENDER_CHUNK_PREWARM + " lightingVisionParallel=" + LIGHTING_VISION_PARALLEL + " threadNice=" + THREAD_NICE + " profileHandshake=" + PROFILE_HANDSHAKE + " luaProfile=" + LUA_PROFILE + " gcMode=" + GC_MODE + " gcPauseMs=" + GC_PAUSE_MS + " jitMode=" + JIT_MODE + " jitC1Cores=" + JIT_C1_CORES + " vehicleCull=" + VEHICLE_CULL + " playerLosFast=" + PLAYER_LOS_FAST + " zombieSpotFast=" + ZOMBIE_SPOT_FAST + " playerLosNative=" + PLAYER_LOS_NATIVE + " animBonesParallel=" + ANIM_BONES_PARALLEL + " animBatchAsync=" + ANIM_BATCH_ASYNC + " animatorParallel=" + ANIMATOR_PARALLEL + " animatorPipeline=" + ANIMATOR_PIPELINE + " frameSpinUs=" + FRAME_SPIN_US + " frameThreads=" + FRAME_THREADS + " actionEvalParallel=" + ACTION_EVAL_PARALLEL + " actionSnapshotFilter=" + ACTION_SNAPSHOT_FILTER + " emitterParamSkip=" + EMITTER_PARAM_SKIP + " separateFast=" + SEPARATE_FAST + " separateParallel=" + SEPARATE_PARALLEL + " actionGroupCache=" + ACTION_GROUP_CACHE + " profilerThreadMemo=" + PROFILER_THREAD_MEMO + " sleepCheckMemo=" + SLEEP_CHECK_MEMO + " stateParamMemo=" + STATE_PARAM_MEMO + " zombieSimLodTiles=" + ZOMBIE_SIM_LOD_TILES + " zombieSimLodSteps=" + ZOMBIE_SIM_LOD_STEPS + " zombieCheckSpread=" + ZOMBIE_CHECK_SPREAD + " lightingReadParallel=" + LIGHTING_READ_PARALLEL + " zombieCullSortFast=" + ZOMBIE_CULL_SORT_FAST + " skinTransformsPrecompute=" + SKIN_TRANSFORMS_PRECOMPUTE + " skinPalettePrecompute=" + SKIN_PALETTE_PRECOMPUTE + " shadowPrep=" + SHADOW_PREP + " boneIndexCache=" + BONE_INDEX_CACHE + " ecsLookupFast=" + ECS_LOOKUP_FAST + " actionConditionFast=" + ACTION_CONDITION_FAST + " charDrawPrep=" + CHAR_DRAW_PREP + " zombieAtlasFast=" + ZOMBIE_ATLAS_FAST + " charDrawThreads=" + CHAR_DRAW_THREADS
            + " fileThreads=" + FILE_THREADS + " fileInflight=" + FILE_INFLIGHT + " textureBufferMb=" + TEXTURE_BUFFER_MB + " parallelDepthMaps=" + PARALLEL_DEPTH_MAPS + " loaderCpuFixes=" + LOADER_CPU_FIXES + " loadWorkers=" + LOAD_WORKERS + " scriptParserFast=" + SCRIPT_PARSER_FAST + " fmodAsync=" + FMOD_ASYNC + " noLoadFade=" + NO_LOAD_FADE + " noIntroWait=" + NO_INTRO_WAIT + " noClickToStart=" + NO_CLICK_TO_START + " noLoadingScreen=" + NO_LOADING_SCREEN + " centerFirstLoad=" + CENTER_FIRST_LOAD + " resumeShot=" + RESUME_SHOT + " resumeShotDetail=" + RESUME_SHOT_DETAIL + " bootPump=" + BOOT_PUMP + " earlyModels=" + EARLY_MODELS + " luaPrecompile=" + LUA_PRECOMPILE + " preloadAnimSets=" + PRELOAD_ANIM_SETS + " tileDefPreload=" + TILE_DEF_PRELOAD + " skipIdChecks=" + SKIP_ID_CHECKS + " voronoiFast=" + VORONOI_FAST + " earlyTilePacks=" + EARLY_TILE_PACKS + " aotCache=" + AOT_CACHE + " animClipCache=" + ANIM_CLIP_CACHE + " packIndex=" + PACK_INDEX + " itemParamSwitch=" + ITEM_PARAM_SWITCH + " bootFileThreads=" + BOOT_FILE_THREADS + " shaderCache=" + SHADER_CACHE + " mipmapArrays=" + MIPMAP_ARRAYS + " puddleCache=" + PUDDLE_CACHE + " puddleCacheFrames=" + PUDDLE_CACHE_FRAMES + " puddleVbo=" + PUDDLE_VBO + " treeAppend=" + TREE_APPEND + " puddleEarlyZ=" + PUDDLE_EARLY_Z + " rainSplashesFast=" + RAIN_SPLASHES_FAST + " rainTiles=" + RAIN_TILES + " vboBatchKb=" + VBO_BATCH_KB + " vboFastQuads=" + VBO_FAST_QUADS + " fogPass=" + FOG_PASS + " fogScalePct=" + FOG_SCALE_PCT + " fogMaskFrames=" + FOG_MASK_FRAMES
            + " upscaler=" + UPSCALER + " upscalerQuality=" + UPSCALER_QUALITY + " upscalerScalePct=" + UPSCALER_SCALE_PCT + " fsrSharpnessPct=" + FSR_SHARPNESS_PCT + " dlssSharpen=" + DLSS_SHARPEN + " upscalerObjectMv=" + UPSCALER_OBJECT_MV + " dlssPreset=" + DLSS_PRESET + " dlssAutoExposure=" + DLSS_AUTO_EXPOSURE + " dlssPipeline=" + DLSS_PIPELINE + " dlssOutputPct=" + DLSS_OUTPUT_PCT + " dlssOutputFilter=" + DLSS_OUTPUT_FILTER + " dlssFlushAfterWait=" + DLSS_FLUSH_AFTER_WAIT + " dlssFlushAfterComposite=" + DLSS_FLUSH_AFTER_COMPOSITE + " dlssWaitOutputOnly=" + DLSS_WAIT_OUTPUT_ONLY + " dlssDirectColor=" + DLSS_DIRECT_COLOR;
   }
}
