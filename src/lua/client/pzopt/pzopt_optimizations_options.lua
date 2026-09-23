-- pzopt: "Optimizations" tab in the options screen, right after Display.
--  Every pzopt.Config key is a control here: booleans are tick boxes, integers are combos whose first
--  entry is the build's default on this machine. The values live in Java: the overridden
--  PerformanceSettings forwards to pzopt.Config (what is in force since boot) and pzopt.UserOptions
--  (Zomboid/pzopt/options.ini, what the next launch will read). Everything applies on the next
--  launch, so a change away from the boot value raises the stock "restart required" dialog.
--  A key set in the install dir's pzopt.properties or as -Dpzopt.<key> (harness runs) wins over the
--  file; its control shows that value, is disabled, and the tooltip says what pins it.
--  The top of the tab is the master switch (key `enabled`): off = every override takes its stock
--  path, the same as a build mismatch, whatever the other keys say. "Disable all (stock)" turns it
--  off; "Enable all" turns it on and puts every other control back to the build's defaults.
--  Both only change the controls; Apply / Accept saves them like any other option.
--  Right of the controls sits the preview panel (PzoptPreview, fixed while the list scrolls): for the
--  setting under the mouse it plays two clips side by side, the stock game and the optimized build on the
--  same route (animated GIFs under media/ui/pzopt/compare/, made by harness/menu-gifs.py, decoded by
--  pzopt.GifTextures), shows the setting's description and its value now / at the next launch, and draws
--  one bar per resource (game thread, render thread, other cores, GPU, VRAM, RAM, disk, load time, chunk
--  arrival) from the EFFECTS table below: left = less work / sooner, right = more. Which clip a setting
--  shows is its section's `clip`, overridden per key in KEY_CLIP.
--  The performance overlay and its game-thread profiler (PROFILER_SECTIONS) have their own "Profiler" tab right after
--  it, built the same way (buildSettingsPage) without the master switch and the profile buttons.
-- Installed by scripts/pzopt.sh into <game dir>/media/lua/client/pzopt/ (loose game-dir Lua is
-- loaded like any other, no mod to enable).

local TAB = "Optimizations"
local PROFILER_TAB = "Profiler"
local RESTART_NOTE = "Takes effect on the next launch."

-- The master switch, drawn before the sections with the two buttons.
local MASTER = { key = "enabled", label = "Optimizations enabled (master switch)",
  tip = "Off = the game runs stock: every override takes its original code path and the settings below are ignored. On = the settings below apply." }

-- Colour names pzopt.Overlay.color knows (a RRGGBB hex typed into options.ini also works).
local FPS_COLOURS = { "blue", "green", "yellow", "red", "white", "cyan", "lime", "orange", "magenta", "purple" }

-- Keys, labels and tooltips. `choices` makes a combo (integer or string); `note[value]` annotates an entry;
-- `bezier` adds the curve sliders and plot under the combo (addBezierOption).
local SECTIONS = {
    {
        title = "Chunk textures: what bakes", clip = "drive",
        entries = {
            { key = "treesInChunkTexture", label = "Trees: bake into chunk textures",
              tip = "Static trees are drawn once into the chunk textures instead of every frame; only fading trees stay per-frame. Off = stock (every tree every frame)." },
            { key = "treeBakeMaxChunksPerSec", label = "Trees: bake only below this chunk rate (chunks/s)",
              choices = { "0", "12", "24", "48" }, note = { ["0"] = "always bake" },
              tip = "While chunks stream in faster than this (walking loads about 9 a second, driving at 60 km/h about 32, at 120 km/h about 72) new chunk textures are baked without their trees and the trees are drawn per frame instead: a texture that lives a second or two while driving costs more to bake its trees into than to draw them. Textures already baked keep their trees until they re-bake anyway." },
            { key = "treeBakeDirect", label = "Trees: bake through the plain sprite path",
              tip = "Baked trees go through the plain sprite path. The batched path drops the largest (jumbo) trees near buildings." },
            { key = "treeBakePass", label = "Trees: crowns across textures, depth by height",
              tip = "Baked trees are drawn by their own pass: into every chunk texture the crown reaches (a jumbo tree is up to 7 tiles wide) and with a depth that rises with the crown like walls do. Off = trees are clipped at their chunk texture's border and cut by upper-floor walls behind them (issue #5)." },
            { key = "treeAppend", label = "Trees: draw new ones into neighbour textures",
              tip = "A newly loaded chunk's trees that reach into an already baked neighbour texture are drawn on top of it instead of re-baking the whole texture; same picture, most of the re-bakes while driving." },
            { key = "windowsInChunkTexture", label = "Bake windows into chunk textures",
              tip = "Windows and glass doors bake like walls instead of being drawn every frame." },
            { key = "translucentTilesInChunkTexture", label = "Bake translucent tiles",
              tip = "Fences, railings, wall decorations and overlays bake into the chunk textures instead of being drawn every frame (about 3,000 draws a frame at max zoom)." },
            { key = "curtainDepthNudgePct", label = "Curtain depth nudge (hundredths of a tile)",
              choices = { "0", "3", "5", "10" }, note = { ["0"] = "off" },
              tip = "Closed curtains draw this much nearer the camera than their tile geometry says, so a baked window never shows through them (north windows sit 0.017 tile in front of their curtain in the game's tile geometry)." },
        },
    },
    {
        title = "Chunk textures: bake budgets", clip = "nightdrive",
        entries = {
            { key = "bakeBudget", label = "Chunk textures baked per frame",
              choices = { "0", "2", "4", "8", "16", "32" }, note = { ["0"] = "unlimited, stock" },
              tip = "Chunk-level textures (re)baked in one frame; the rest wait for the next frame." },
            { key = "rebakeBudget", label = "Chunk texture re-bakes per frame",
              choices = { "0", "2", "4", "8", "16" }, note = { ["0"] = "unlimited, stock" },
              tip = "Textures dirtied only by lighting drift, a redraw or a cutaway change keep their previous image for a few frames past this many re-bakes." },
            { key = "rebakeMaxFrames", label = "Chunk texture re-bakes: longest hold (frames)",
              choices = { "1", "2", "3", "4", "6" },
              tip = "A held re-bake lands after at most this many frames." },
            { key = "lightingRebakeBudget", label = "Lighting-only re-bakes per frame",
              choices = { "2", "4", "8", "16", "32" },
              tip = "Textures dirtied only by a lighting change (daylight drift, a lightning flash) re-bake at most this many per frame." },
            { key = "lightingRebakeMaxFrames", label = "Lighting-only re-bakes: longest hold (frames)",
              choices = { "3", "10", "30", "60" },
              tip = "A lighting-only re-bake lands after at most this many frames; a lightning strike spreads over this window instead of one long frame." },
            { key = "lightingRebakeMs", label = "Lighting-only re-bakes: minimum ms between two",
              choices = { "0", "50", "100", "250", "500" }, note = { ["0"] = "stock" },
              tip = "A chunk texture dirtied only by a lighting change is not re-baked more often than this." },
            { key = "zoomRetain", label = "Zoom: keep chunk textures across changes",
              tip = "Chunk-level textures that leave the screen when the camera zooms in are kept (while the chunk would still be on screen at the widest zoom) instead of being freed, so zooming back out reuses them; the ones that return, and new ones a zoom-out reveals, are baked a few per frame nearest the player first, with the kept image shown meanwhile. Stock bakes every level a zoom-out reveals in the frame it appears: a fast wheel spin from 0.25 to 2.5 is a 80-375 ms frame." },
            { key = "zoomRebakeBudget", label = "Zoom: kept and new textures baked per frame",
              choices = { "4", "8", "12", "16", "24" },
              tip = "The most chunk-level textures a zoom change (kept ones coming back, new ones it reveals) may start per frame; the count drops after a long frame and grows back after short ones." },
            { key = "zoomPlaceholder", label = "Zoom: kept texture shown while the new one bakes",
              tip = "A chunk level whose texture at the new zoom scale is still queued draws its complete texture from the other scale meanwhile, so a zoom change never shows a hole; off, the level is blank until its own bake lands." },
            { key = "zoomEaseMs", label = "Zoom: motion time (ms)",
              choices = { "0", "150", "200", "300", "400", "600" }, note = { ["0"] = "stock step" },
              tip = "A mouse-wheel zoom change takes this long, moving along a smooth curve (quick start, gentle stop) whatever the frame rate. Stock moves a fixed amount per frame and stops abruptly: 8 frames, 16 ms at 500 fps, 130 ms at 60." },
            { key = "zoomEase", label = "Zoom: motion timing curve",
              choices = { "0.25,0.1,0.25,1.0", "0.42,0,0.58,1", "0,0,0.58,1", "0.42,0,1,1", "0.333,0.333,0.667,0.667" },
              note = { ["0.25,0.1,0.25,1.0"] = "ease", ["0.42,0,0.58,1"] = "ease-in-out", ["0,0,0.58,1"] = "ease-out", ["0.42,0,1,1"] = "ease-in", ["0.333,0.333,0.667,0.667"] = "linear" },
              bezier = true,
              tip = "The cubic Bezier control points (x1,y1,x2,y2) of the zoom motion, as in CSS transitions: pick a preset or drag the four sliders under it (the plot beside them shows the zoom's progress over the motion time). x is the share of the time, y the share of the zoom change; y stays within 0-1 so the zoom never overshoots its target." },
            { key = "zoomFrameMs", label = "Zoom: slow-frame limit for the bakes (ms)",
              choices = { "6", "8", "10", "14", "20" },
              tip = "A frame longer than this halves the zoom bakes per frame; frames under three quarters of it grow the count back." },
            { key = "lightingStrongDelta", label = "Strong light changes: amount that re-bakes at once (0-255)",
              choices = { "2", "4", "6", "12", "24", "255" }, note = { ["255"] = "hold everything" },
              tip = "A square whose light moved by this much since its texture was last baked (a torch or headlight beam sweeping in) re-bakes now, like stock; smaller drift keeps the lighting-only re-bake hold (\"Lighting-only re-bakes: minimum ms between two\")." },
            { key = "lightingStrongBudget", label = "Strong light changes: budget per frame",
              choices = { "0", "4", "8", "16", "32" }, note = { ["0"] = "no cap" },
              tip = "How many chunk textures with a strong light change re-bake in the same frame; the rest keep the lighting-only re-bake hold. A torch or headlight beam touches a few per frame; turning moves the out-of-sight fade over every exterior tile (downtown Louisville: 10.8 fps with no cap)." },
            { key = "lightingStrongFrameMs", label = "Strong light changes: ease on slow frames",
              choices = { "0", "12", "20", "33" }, note = { ["0"] = "fixed budget" },
              tip = "A game-thread frame longer than this many milliseconds halves the number of strong-light chunk re-bakes allowed next frame (it grows back on fast frames). Stops the slow-frame -> more re-bakes -> slower-frame loop of a big downtown horde." },
            { key = "lightingGlobalDeltaPct", label = "Global light move that keeps the spread (%)",
              choices = { "1", "2", "5", "10", "100" }, note = { ["100"] = "never" },
              tip = "A lightning flash or a fast dusk moves the whole scene's light at once; past this per-frame move the lighting re-bakes stay spread over frames instead of landing at once." },
            { key = "lightingBudget", label = "Lighting refreshes per frame (chunks)",
              choices = { "0", "2", "4", "8", "16", "32" }, note = { ["0"] = "unlimited, stock" },
              tip = "Chunks whose square light info is refreshed in one frame; the rest continue next frame." },
            { key = "lightingFlush", label = "Lighting refreshes: flush the queue before a lighting pass",
              tip = "Chunks the per-frame lighting refresh budget still holds are refreshed just before the next lighting pass rewrites their dirty bits; off, they keep stale light until it changes again." },
        },
    },
    {
        title = "Cutaways, lighting and weather (game thread)", clip = "spin",
        entries = {
            { key = "cutawayFast", label = "Replay cutaway masks",
              tip = "Clean chunk levels replay their stored wall-cutaway occluder masks instead of re-testing every square." },
            { key = "cutawayRadius", label = "Cutaway radius (chunks)",
              choices = { "0", "1", "2", "3", "4", "6" }, note = { ["0"] = "all on screen, stock" },
              tip = "Cutaway wall visits only consider chunks within this many chunks of the camera." },
            { key = "gridStackInterval", label = "Frames between buildings-in-front scans",
              choices = { "0", "2", "4", "8" }, note = { ["0"] = "every frame, stock" },
              tip = "While the camera square and facing are unchanged, the buildings-in-front scan runs this often." },
            { key = "lightSwitchCheckFrames", label = "Frames between light-switch power checks",
              choices = { "0", "5", "15", "30", "60" }, note = { ["0"] = "every frame, stock" },
              tip = "Each light switch reuses its has-electricity answer for this many frames." },
            { key = "rainTiles", label = "Rain and snow as repeated tiles",
              tip = "The particle cell is packed once and drawn once per screen cell on the GPU instead of every copy being packed on both threads; same picture." },
            { key = "rainSplashesFast", label = "Rain splashes without the game RNG",
              tip = "Splash starts are drawn with a cheap local generator (one draw per splash instead of one game RNG call per idle square per frame); same chance, timing and sprites." },
            { key = "puddleCache", label = "Puddles: cache packed vertices per chunk",
              tip = "Rain puddles keep their packed vertices per chunk level and only refresh lighting, camera offset and depth each frame; 4.5 ms of a thunderstorm frame at max zoom." },
            { key = "puddleCacheFrames", label = "Puddles: cache rebuild interval (frames)",
              choices = { "1", "30", "60", "120" }, note = { ["1"] = "rebuild every frame (cache off)" },
              tip = "A cached puddle batch is rebuilt with the stock code after this many frames at the latest; bakes and cutaway changes rebuild it at once." },
            { key = "puddleEarlyZ", label = "Puddles: early depth test in the shader",
              tip = "The puddle shaders take their depth from the vertex instead of writing it per pixel, so wet ground hidden behind walls, roofs and objects is skipped before the expensive shader runs; same picture." },
            { key = "puddleVbo", label = "Puddles: keep batches on the GPU",
              tip = "Each chunk level's cached puddle vertices stay in their own GPU buffer and are re-sent only when a light changed, the camera crossed a chunk edge or the batch was rebuilt; the camera offset is a matrix translation. Nothing is copied per frame." },
            { key = "weatherMaskIdleSkip", label = "Skip the weather mask while nothing is drawn",
              tip = "Outdoors with no clouds, fog or rain the per-frame weather-mask view scan and mask draw are skipped; indoors only the player's building is scanned." },
            { key = "weatherFxScalePct", label = "Weather effects buffer size (% of screen)",
              choices = { "100", "75", "50", "33", "25" }, note = { ["100"] = "stock" },
              tip = "Clouds, fog, rain and the interior mask they are cut by are drawn into screen-sized buffers every frame; smaller buffers cost far less GPU and CPU and the soft content looks the same." },
            { key = "fogPass", label = "Fog drawn in one pass (experimental)",
              tip = "EXPERIMENTAL. Heavy fog is drawn in one batch into a smaller buffer that is depth-tested against the scene and blended over it once, instead of shading every pixel up to twelve times with one draw call per row: fog at 120 km/h went from 220 to ~340 fps (clear: 447) on the 5120x2160 desktop. Known issue: power lines can flicker slightly in fog while the camera moves; disabling this removes it (stock fog, stock cost)." },
            { key = "fogScalePct", label = "Fog pass: buffer size (% of screen)",
              choices = { "100", "75", "50", "33", "25" }, note = { ["100"] = "full resolution" },
              tip = "The fog buffer per axis as a percentage of the screen; 50 costs a quarter of the fog GPU work, 25 a sixteenth. Fog is soft, so the smaller buffers look the same, and edges where fog meets walls or wires are resolved against the real depth." },
            { key = "fogMaskFrames", label = "Fog pass: square masks refresh (frames)",
              choices = { "0", "10", "20", "60" }, note = { ["0"] = "read every square every frame" },
              tip = "The fog rows are built from per-chunk masks of the squares that take fog instead of reading every square each frame; a mask is refreshed this many frames after its last refresh, so a newly built room reaches the fog within that many frames." },
            { key = "roofHideDebounceFrames", label = "Carport roof hide/show settle time (frames)",
              choices = { "0", "4", "8", "15", "30" }, note = { ["0"] = "stock" },
              tip = "A carport or pergola roof is hidden or shown only after the decision has held for this many frames, so a player on its edge (or pushed by zombies) does not make the roof flicker every frame." },
            { key = "cutawayVisitPrefilter", label = "Skip cutaway walls that cannot cut",
              tip = "A cutaway visit only walks the squares of walls that occlude a cutaway room, belong to a collapsing building, or may hide a window being peeked through; the rest are skipped before their squares are looked up." },
            { key = "cutawayInvalidateChanged", label = "Re-bake cutaway chunks only when a cutaway changed",
              tip = "A cutaway visit re-flags every cut-away wall square; stock re-bakes every chunk holding one on every visit. Only chunks where a square's cutaway flag actually changed are re-baked." },
            { key = "occlusionSkipLightingOnly", label = "Keep the occlusion grid when only lighting changed",
              tip = "The occluded-squares grid and the per-level rendered-square counts are rebuilt only when a visible chunk level changed for a reason other than lighting drift." },
            { key = "lightInfoChunkGate", label = "Ask the lighting engine about a chunk level first",
              tip = "Before refreshing the 64 squares of a chunk level about to be re-baked, one chunk-level question to the lighting engine says whether any of them changed." },
            { key = "lightInfoOncePerFrame", label = "Ask the lighting engine once per square per frame",
              tip = "The per-square light-info JNI call is skipped when the same square was already refreshed this frame." },
            { key = "soundZoneCache", label = "Reuse ambient sound zone distances per square",
              tip = "The 80x80 zone scan behind each ambient zone parameter runs when the listener's square changes (at most every 30 frames), not every frame." },
            { key = "worldSoundFast", label = "Cheap world sounds (alarms, helicopter, gunshots)",
              tip = "A world sound is attached to the loaded chunks only instead of walking every chunk of its radius, and the fish-scaring square walk (a third of the radius squared, every frame while a house alarm rings) runs once per game minute per identical sound." },
            { key = "animBonesParallel", label = "Zombie animation bone math on other cores",
              tip = "After the object update loop the zombies' animation blending (bone, twist, model and skin matrices, 9 % of the game thread in a horde) runs on worker threads and is joined before rendering; off, it runs inline like stock." },
            { key = "frameThreads", label = "Worker threads for the zombie batches",
              choices = { "2", "4", "8", "15" }, tip = "Threads of the per-frame zombie batches (bone math, transition evaluation, lighting reads; the game thread joins in); more than cores - 1 is clamped." },
            { key = "actionEvalParallel", label = "Zombie decision rules evaluated on other cores",
              tip = "Each zombie's action-state transition rules (dozens of variable tests per zombie per frame, 7-10 % of the game thread in a horde) are evaluated on worker threads after the object loop; the state changes, the animator and the model update then run on the game thread in the stock order. Rules that call Lua or a zombie mid-grapple stay on the game thread." },
            { key = "animatorParallel", label = "Zombie animators on other cores",
              tip = "After its decision rules, each zombie's animator (which animations play and how they blend), its turn and move speeds and its animation clock run on worker threads; the sounds, footsteps and state hooks those animations trigger are replayed on the game thread in the stock order. A zombie whose animator fires such an event this frame finishes on the game thread. 12 % of the game thread in a horde." },
            { key = "headOnWorker", label = "Zombie turning checks on other cores",
              tip = "The start of each zombie's animation step (which way it faces, its aim angle, whether it is turning, turning 90 degrees or turning around) runs in its decision-rule task on a worker thread instead of on the game thread; a zombie starting or re-aiming a 180-degree turn, whose event must go through the game thread first, is left to it." },
            { key = "lazyPose", label = "Look up animation keyframes only when used",
              tip = "Every animation track searched the keyframes of all 60 bones every frame; the search now happens when a bone is first read. A zombie drawn as a far sprite reads one or two bones, a modelled one reads them in the bone batch on the workers. Same poses." },
            { key = "animatorPipeline", label = "Overlap the zombie batches with the game thread",
              tip = "The workers run the decision rules and the animators alone while the game thread applies each zombie's result as soon as it is ready, instead of taking a share of the batch and then applying everything." },
            { key = "animBatchAsync", label = "Zombie bone math alongside the rest of the frame",
              tip = "The bone math batch starts after the zombie updates and runs on the workers while the game thread carries on with the rest of the frame's logic; it is joined where the game itself would wait for animation, right before drawing (anything touching a zombie's bones earlier waits for it first)." },
            { key = "guardedCallbacks", label = "Read more zombie rules on other cores",
              tip = "Eight zombie rules (has a target, should sprint, should attack, thump, lunge, attack a car, passenger exposed, eat a body) only change something in rare cases (a target that died, a door that is out of reach, a body next to it). They are now read on the worker threads, and the rare case falls back to the game thread before anything changes." },
            { key = "modelLockPerInstance", label = "One model lock per character",
              tip = "The engine's model update lock is a single text constant shared by every character, so the worker threads queued behind each other on it (each animator task ran 2.4x slower in parallel). Each model now has its own lock; stock only ever updates a model from one thread." },
            { key = "poolStatsBatched", label = "Batch the pool statistics of the workers",
              tip = "Every reuse of a pooled engine object bumps shared statistics counters; the worker threads now count in private tallies and add them once per batch. Same totals, no fight over one memory line." },
            { key = "ecsLookupFast", label = "Cheap state-machine lookups",
              tip = "Every state-machine, action-context and animation-variable access of a character went through a class walk, a map probe and a reflective cast; the answer is memoised and zombies keep their component in a field (5 % of the game thread in a horde). Same results." },
            { key = "actionConditionFast", label = "Typed decision-rule variables",
              tip = "A rule comparing a true/false or integer animation variable reads it directly; stock printed the value to text and parsed it back for every rule of every zombie every frame. Same outcomes." },
            { key = "skinTransformsPrecompute", label = "Bone worker: skin matrices of zombies",
              tip = "The worker that blends a zombie's bones also multiplies them into the skin matrices of the body and clothing models it wore last frame, so the render pass finds them ready (3 % of the game thread in a horde)." },
            { key = "skinPalettePrecompute", label = "Bone worker: skin palettes for the shader",
              tip = "The same worker also stores those matrices in the shader palette layout, so the draw data copies one block instead of sixteen numbers per bone." },
            { key = "shadowPrep", label = "Bone worker: zombie shadow ellipses",
              tip = "The shadow blob under a zombie (head and feet projected to the ground) is computed right after its bones on the worker instead of in the render pass; same numbers." },
            { key = "boneIndexCache", label = "Remember bone lookups by name",
              tip = "The head and feet bone indices the shadow asks for by name every frame are cached per skeleton." },
            { key = "lightingReadParallel", label = "Lighting reads on other cores",
              tip = "When a lighting pass lands, the per-square light reads of the queued chunk levels (two engine calls per visible square, 12 % of the game thread in a horde) run one chunk level per worker thread; the room-discovery hooks run on the game thread afterwards in the stock order." },
            { key = "zombieCullSortFast", label = "Cheap zombie relevance sort",
              tip = "The per-frame sort that decides which zombies get a full update computes each zombie's relevance score once and sorts primitive keys; stock recomputed both scores in every comparison of the sort. Same order." },
            { key = "vehicleCull", label = "Skip far vehicles in the zombie line-of-sight test",
              tip = "A zombie checking whether a car blocks its view of you tests only the cars near you whose bounding circle reaches the line (one list per frame); stock ran the exact box test on every loaded car (6 % of the game thread downtown)." },
            { key = "playerLosFast", label = "Remember spotted zombies in a set",
              tip = "The list of everything you have spotted since the last quiet moment is searched once per spotted object per frame; stock walked it end to end each time, so in a horde the cost grew with the square of the zombies in view (12 % of the game thread in Louisville). The search is one hash probe now, and the sneak modifier zombies ask for is computed once per frame." },
            { key = "zombieSpotFast", label = "Skip the spot roll for zombies that cannot see you",
              tip = "A zombie facing away from you or with you beyond its vision radius already has a zero chance to notice you; it skips the remaining modifiers, the car test and the dice roll (same outcome), instead of computing them all first." },
            { key = "charDrawPrep", label = "Zombie draw data built on other cores",
              tip = "The draw data of every zombie model on screen (its lights, one render record and matrix palette per body and clothing model, the depth and light setup) is built on worker threads before the game thread queues the draws in the stock order; stock built it one zombie at a time on the game thread (13 % of it in a Louisville horde). Same pixels." },
            { key = "charDrawThreads", label = "Zombie draw data threads",
              choices = { "4", "8", "12", "14" }, tip = "Threads of the zombie draw-data pool; the data has to be ready before the zombies are queued, so on a 16-core machine 14 keep the game thread from waiting. More than cores - 2 is clamped." },
            { key = "zombieAtlasFast", label = "Flat draw call for far zombies",
              tip = "A zombie too far for a 3D model is drawn as a small pre-rendered sprite; its draw goes through a flat copy of the game's render chain (the same tests, the same sprite call) instead of five nested virtual calls per zombie. ~1,100 such zombies per frame in a Louisville horde. Same pixels." },
            { key = "actionSnapshotFilter", label = "Read only the animation variables that need it",
              tip = "Before a zombie's transitions are evaluated on a worker, the game thread reads the variables whose engine callback has a side effect. It used to resolve every variable of every transition to find out which those are; the answer only depends on the variable's name, so it is now decided once per state. Same values." },
            { key = "emitterParamSkip", label = "Skip sound parameters for silent characters",
              tip = "A character's sound parameters (the floor material under its feet, the room it is in) are recomputed only while it actually has a sound playing or about to start; stock recomputed all of them for every zombie every frame and wrote them nowhere. Same sounds." },
            { key = "separateFast", label = "Push-apart for zombies: cheap pass",
              tip = "The pass that pushes overlapping zombies apart skips the half of the engine's version that only ever applies to the player, and the grid answer 'is my square walled off from that neighbour' is computed once per square per frame instead of once per zombie. Same positions." },
            { key = "separateParallel", label = "Push-apart for zombies: on other cores",
              tip = "That push-apart is computed for the whole horde on worker threads before the update loop; the game thread applies each zombie's result at the same point in its update as before, so the collide events and window climbs keep their order. The neighbours' positions are read at the top of the frame instead of as the loop advances." },
            { key = "sleepCheckMemo", label = "Check 'everyone asleep' once per frame",
              tip = "The game asks whether all players are asleep on the way into every character update, and a zombie asks several times per update; the answer cannot change inside a frame, so it is computed once." },
            { key = "stateParamMemo", label = "Faster AI scratch lookups",
              tip = "The per-character scratch values the AI states keep were looked up through two maps and allocated a throwaway object on every read. Same values, one probe, no allocation." },
            { key = "actionGroupCache", label = "Keep the zombie action group",
              tip = "Every zombie asked the engine for its action group by name twice a frame, which copied the name into a new lower-case string and probed a map for a group loaded once at startup. It is held instead." },
            { key = "profilerThreadMemo", label = "Cheap profiler thread check",
              tip = "Every performance probe in the game asks twice whether it is on a profiled thread, and the engine answers by scanning a list of thread names. The answer per thread is remembered. Costs nothing when the profiler is off, which is always in normal play." },
            { key = "zombieSimLodTiles", label = "Distant zombies simulated less often (tiles)",
              choices = { "0", "8", "12", "15", "20", "25" },
              note = { ["0"] = "stock: the game's own 30 / 60 / 80 tile steps only" },
              tip = "The game already updates a zombie every 2nd, 4th or 8th frame once it is 30, 60 or 80 tiles from you. This adds one more step at a closer distance. It roughly halves what the horde costs the game thread, and distant zombies move in slightly coarser steps - it is a change to how the world is simulated, so it is off by default." },
            { key = "zombieSimLodSteps", label = "Distant zombies simulated less often: extra steps",
              choices = { "1", "2", "3" },
              note = { ["3"] = "not recommended: three steps made the Louisville test scene unstable" },
              tip = "Each further step applies at twice the distance of the previous one, like the game's own ladder. Two steps is the measured sweet spot." },
            { key = "zombieCheckSpread", label = "Spread the zombie thump probe (frames)",
              choices = { "0", "2", "3", "4", "6" },
              note = { ["0"] = "stock: every frame" },
              tip = "The grid test for 'is there a door or window in front of me to thump' runs on one frame in N per zombie, spread evenly by zombie. A thump starts at most N-1 frames later than it would." },
        },
    },
    {
        title = "Upscaling (render the world smaller, resolve to the screen)", clip = "drive",
        entries = {
            { key = "upscaler", label = "Upscaler",
              choices = { "off", "bicubic", "fsr1", "dlss", "xess" },
              note = { off = "stock: the world renders at the screen size", bicubic = "the stock screen filter, any GPU", fsr1 = "AMD FidelityFX Super Resolution 1.0, any GPU", dlss = "NVIDIA DLSS Super Resolution (RTX; needs the shim built from the repository under natives/, not in the release); else runs as fsr1", xess = "Intel XeSS: not available yet, runs as fsr1" },
              tip = "The world is rendered at a fraction of the screen size (see \"Upscaler quality\") and scaled back up before the UI, text and the stock screen shader, which stay at full resolution. GPU-bound scenes (fog, storms, big towns, 4K, laptops) gain roughly the pixel ratio. fsr1 is a sharp spatial upscaler that works on every GPU; dlss accumulates detail over frames on an RTX card; bicubic is the plain stretch. Applies on the next launch." },
            { key = "upscalerQuality", label = "Upscaler quality (render size)",
              choices = { "quality", "balanced", "performance", "ultra", "native" },
              note = { quality = "67 % per axis (44 % of the pixels)", balanced = "58 %", performance = "50 % (a quarter of the pixels)", ultra = "33 %", native = "100 % (dlss: DLAA anti-aliasing only)" },
              tip = "The render size per axis. Quality keeps most of the detail; performance halves the axes for a quarter of the world-pass GPU work." },
            { key = "upscalerScalePct", label = "Upscaler render scale of your own (%)",
              choices = { "0", "40", "50", "60", "67", "75", "85" }, note = { ["0"] = "use the quality preset" },
              tip = "A render scale of your own in percent per axis instead of the quality preset (10-100)." },
            { key = "fsrSharpnessPct", label = "Upscaler, AMD FSR 1.0: sharpening (%)",
              choices = { "0", "40", "60", "80", "100" }, note = { ["0"] = "none", ["100"] = "the sharpest" },
              tip = "The contrast-adaptive sharpening (RCAS) after the FSR 1.0 upsample; 80 is AMD's usual default." },
            { key = "dlssPreset", label = "Upscaler, NVIDIA DLSS: model preset",
              choices = { "e", "default", "f", "k", "j", "m", "l" },
              note = { default = "NVIDIA's choice: the transformer models K / M / L", f = "older convolutional model: ~1.5 ms cheaper a frame at 4K, a little softer", e = "recommended: the older convolutional model, half the cost of K and no trails in this game", k = "transformer, best quality", j = "transformer, less ghosting, more flicker", m = "transformer, the performance-mode default", l = "transformer, the ultra-performance default" },
              tip = "Which DLSS network runs. The transformer models (DLSS 4) reconstruct the most detail but cost ~2.5 ms a frame at 5120x2160 on an RTX 4090 (231 fps on the 120 km/h drive vs 377 with preset F and 619 with FSR 1.0); at 1440p and below the cost is a third or less." },
            { key = "dlssOutputPct", label = "Upscaler, NVIDIA DLSS: output size (%)",
              choices = { "67", "75", "0" },
              note = { ["0"] = "the screen size: full DLSS super resolution", ["75"] = "DLSS writes 75 % of the screen, the finish below does the rest", ["67"] = "recommended: DLSS anti-aliases at the render size (quality), the finish does the upscale" },
              tip = "DLSS costs time per pixel it writes, not per pixel it reads. Writing less than the screen and letting a cheap filter finish the upscale is what makes DLSS faster than no upscaler on a 4K screen: on an RTX 4090 at 5120x2160 in a heavy storm with fog, DLSS at quality with preset E gains +22 % at 67 % with the sharpen finish (and a sharper image than full-size DLSS with the default model), +17 % at 75 %, +10 % at the full size. At DLAA (native) the output is always the screen size." },
            { key = "dlssOutputFilter", label = "Upscaler, NVIDIA DLSS: output finish",
              choices = { "rcas", "bicubic", "fsr1" },
              note = { rcas = "recommended: FSR 1.0's sharpen at the DLSS size, then the bicubic", bicubic = "the stock screen shader's bicubic, free but soft", fsr1 = "FSR 1.0 EASU + RCAS: the sharpest, ~0.2 ms a frame at 4K" },
              tip = "How a DLSS output smaller than the screen (the size above) is brought to the screen." },
            { key = "dlssSharpen", label = "Upscaler, NVIDIA DLSS: sharpening",
              tip = "Asks DLSS for its mild extra sharpening pass on top of the super resolution; off is the plain reconstruction." },
            { key = "upscalerObjectMv", label = "Upscaler, temporal: character and vehicle motion vectors",
              tip = "The temporal upscalers get each character's and vehicle's own motion on top of the camera's, so moving zombies and cars do not ghost or smear. Off = camera motion only (an A/B)." },
        },
    },
    {
        title = "Sprite buffers", clip = "drive",
        entries = {
            { key = "persistentVbo", label = "Persistently mapped sprite buffers",
              tip = "Sprite ring buffers use persistently mapped buffer storage instead of an orphan and re-map per batch. About 2.7x the uncapped frame rate at max zoom." },
            { key = "vboBatchKb", label = "Line/particle batch buffer (KB)",
              choices = { "4", "256", "1024" }, note = { ["4"] = "stock: rain flushes every 28 particles" },
              tip = "Rain and snow particles, debug lines and other VBORenderer quads are uploaded and drawn in batches of this size instead of 4 KB." },
            { key = "vboFastQuads", label = "Single-advance particle quads",
              tip = "VBORenderer writes a textured quad's four vertices in one go instead of four bookkeeping round trips; same bytes." },
        },
    },
    {
        title = "Multiplayer", clip = "drive",
        entries = {
            { key = "luaChecksumExempt", label = "Leave the pzopt Lua files out of the server file check",
              tip = "When joining a server the game lists every Lua file under media/lua to the server; the pzopt files (this tab and its search index, the frame cap combo, the key binding, the update item) only exist on clients and a server without them refused the join with \"File doesn't exist on the server\". They are skipped like the game skips SandboxVars.lua. Applies on the next launch." },
        },
    },
    {
        title = "Updates", clip = "load",
        entries = {
            { key = "updateCheck", label = "Offer new releases in the main menu",
              tip = "Once per boot the main menu asks the GitHub releases (one request to api.github.com) whether a newer build for this game revision exists. The \"UPDATE PZ OPTIMIZATION\" item between Credits and Exit is greyed out while the build is current and enabled when a newer one exists: it downloads the zip, replaces the installed files and asks to quit so the next launch loads them. Nothing is downloaded without that click. Applies on the next launch." },
        },
    },
    {
        title = "Render distance", clip = "grid",
        entries = {
            { key = "chunkGridWidth", label = "Render distance (chunk grid width)",
              choices = { "0", "auto", "7", "9", "11", "13", "15", "19", "21", "23", "25", "27", "31" },
              note = { ["0"] = "vanilla", ["auto"] = "fill the screen at the widest zoom", ["7"] = "56 tiles", ["9"] = "72 tiles",
                       ["11"] = "88 tiles", ["13"] = "104 tiles", ["15"] = "120 tiles", ["19"] = "152 tiles, vanilla at 1080p and above",
                       ["21"] = "168 tiles, fills 4K", ["23"] = "184 tiles", ["25"] = "200 tiles, fills 5120x2160", ["27"] = "216 tiles",
                       ["31"] = "248 tiles" },
              tip = "How many chunks (8 tiles each) per side are loaded, simulated, lit and drawn around you. Vanilla picks it from the screen size but stops at 19 (152 tiles), sized for 1080p: on a 4K or ultrawide screen the world ends before the screen corners at the widest zooms. Auto picks the smallest grid that fills your screen at the widest zoom, never less than vanilla (21 at 3840x2160, 25 at 5120x2160, vanilla at 1080p). Smaller than vanilla = less world to update every frame, which helps CPU-limited setups (heavy mod lists, NPC mods), with the world ending nearer the screen edge. Larger = more CPU, RAM and VRAM and longer loads; zombies, vehicles and sounds are simulated further out. Only the grid size changes; zombie AI, streaming and culling are untouched." },
        },
    },
    {
        title = "Chunk streaming", clip = "drive",
        entries = {
            { key = "parallel", label = "Chunk loading in parallel",
              tip = "Chunk recalculation runs on a worker pool. Off = the stock single-threaded pass." },
            { key = "workers", label = "Chunk worker threads",
              choices = { "1", "2", "3", "4", "6", "8" },
              tip = "Width of the recalc pool; never more than cores - 1." },
            { key = "loadWorkers", label = "Chunk worker threads while a world loads",
              choices = { "1", "2", "4", "6", "8", "12" },
              tip = "The initial 361-chunk recalc uses this many threads, then the pool shrinks back." },
            { key = "wake", label = "Wake the streamer on demand",
              tip = "The streamer thread wakes when a chunk is queued instead of polling every 140 ms." },
            { key = "chunkHandoffDivisor", label = "Chunk hand-off budget (queue divisor)",
              choices = { "0", "4", "8", "16" }, note = { ["0"] = "stock: up to 4 chunks a frame" },
              tip = "At most 1 + queued/divisor freshly loaded chunks are handed to the game thread per frame, so a chunk row arriving at once is spread over a few frames instead of one long one." },
            { key = "hotsaveStaged", label = "Staged hot save",
              tip = "The periodic hot save serialises the meta grid and other systems one part per streamer update instead of all in one frame." },
            { key = "hotsaveIntervalSec", label = "Seconds between hot saves",
              choices = { "0", "5", "15", "30", "60", "120" }, note = { ["0"] = "every drain, stock" },
              tip = "Minimum seconds between the game-thread saves of the meta grid, game time, world map and entities that follow every drained chunk-save queue." },
        },
    },
    {
        title = "Boot: threads and caches", clip = "load",
        entries = {
            { key = "fmodAsync", label = "Start audio on a boot thread",
              tip = "FMOD and its banks (~1.6 s) initialise on a thread during boot." },
            { key = "preloadAnimSets", label = "Parse animation sets on a boot thread",
              tip = "The player and zombie animation-set XML trees parse off the loader thread (1.1 s)." },
            { key = "bootPump", label = "Decode textures during boot",
              tip = "A thread pumps the async file system during boot so texture pages and animations decode before the main menu." },
            { key = "bootFileThreads", label = "File threads during boot",
              choices = { "2", "4", "6", "8", "10", "12" },
              tip = "File pool width while the boot pump runs; shrinks to the in-game width at the load." },
            { key = "earlyModels", label = "Register models early",
              tip = "Models and the animation queue register right after the scripts load, giving the boot pump ~2 s more." },
            { key = "luaPrecompile", label = "Precompile Lua on a pool",
              tip = "Every Lua file compiles on a thread pool during boot; the game takes the prototypes from that cache." },
            { key = "animClipCache", label = "Cache animation clips",
              tip = "Imported animation clips are written under Zomboid/pzopt/anims and read from there on later boots." },
            { key = "packIndex", label = "Index texture packs",
              tip = "Texture packs keep their page offsets under Zomboid/pzopt/packs so the reader seeks instead of scanning 526 MB at boot." },
        },
    },
    {
        title = "Boot: parsers", clip = "load",
        entries = {
            { key = "scriptParserFast", label = "Linear script parser",
              tip = "Script comments strip in one pass and tokens parse without re-substringing; identical output (the stock passes cost 1.8 s at boot)." },
            { key = "itemParamSwitch", label = "Item parameter switch",
              tip = "Item script fields dispatch through a switch instead of a chain of 361 string compares per parameter (0.9 s of boot)." },
        },
    },
    {
        title = "World load: file system and decoding", clip = "load",
        entries = {
            { key = "fileThreads", label = "File threads in game",
              choices = { "1", "2", "4", "6", "8", "12" },
              tip = "Worker threads of the game's async file system (texture decode, model and animation import, depth maps). Stock: 2 on up to 4 cores, else 4." },
            { key = "fileInflight", label = "File tasks in flight",
              choices = { "8", "16", "32", "64" }, note = { ["16"] = "stock" },
              tip = "File tasks handed to the file threads at once." },
            { key = "textureBufferMb", label = "Decoded texture buffer (MB)",
              choices = { "50", "100", "256" }, note = { ["50"] = "stock" },
              tip = "Decoded texture bytes that may wait for the render thread before the decoders pause. Large values pile uploads onto one frame." },
            { key = "parallelDepthMaps", label = "Decode depth maps in parallel",
              tip = "The 218 depth-map tilesets decode concurrently instead of one at a time under one lock." },
            { key = "loaderCpuFixes", label = "Loader thread algorithmic fixes",
              tip = "Lot headers, vehicle zones and room ids resolve once per cell instead of repeatedly; identical results." },
            { key = "shaderCache", label = "Reuse model shaders",
              tip = "A model takes a shader an earlier model already created instead of waiting one loading-screen frame for the render thread." },
            { key = "mipmapArrays", label = "Row-based texture mipmaps",
              tip = "Texture mipmaps and alpha premultiply build row by row on byte arrays; same pixels as stock." },
            { key = "tileDefPreload", label = "Build tile definitions during boot",
              tip = "The ~60k tile sprites are built on a thread while the main menu loads; Continue only binds their textures instead of parsing the tile files again." },
            { key = "skipIdChecks", label = "Skip room-id consistency checks",
              tip = "A log-only check over every building and room of the map (six walks per load) runs in debug mode only." },
            { key = "voronoiFast", label = "Fast zombie-density noise",
              tip = "The zombie voronoi noise of every map cell is computed per sector instead of per sample; identical values, half the map loading time." },
            { key = "earlyTilePacks", label = "Load tile textures earlier in boot",
              tip = "The tile texture packs and depth maps start decoding before the boot Lua load instead of after it, so the world load does not wait for them." },
            { key = "aotCache", label = "Warm-start cache (Java AOT)",
              tip = "The game records a Java AOT cache on one launch and starts from it afterwards: warm code for the menu and every world load. Changes the launcher config (backed up once); off puts it back." },
        },
    },
    {
        title = "World load: loading screen", clip = "load",
        entries = {
            { key = "noLoadFade", label = "Skip the loading-screen fade",
              tip = "The loading screen does not fade to black (350 ms) before the world's own fade-in." },
            { key = "noIntroWait", label = "Click-to-start as soon as a new game is loaded",
              tip = "A new game shows click-to-start when loading is done instead of after the 33 s intro text." },
            { key = "noClickToStart", label = "Enter the world without click-to-start",
              tip = "The loading screen goes straight into the world when loading is done instead of waiting for a click or A." },
            { key = "noLoadingScreen", label = "No loading screen",
              tip = "Single player: no fade from black into the world, and with \"Show the last view while loading\" no loading screen either." },
            { key = "resumeShot", label = "Show the last view while loading",
              tip = "Quitting keeps the ground around you; Continue shows it while loading, tiles appearing as if the chunks were loading, and the world builds over it. Saves without it show the normal loading screen." },
            { key = "centerFirstLoad", label = "Load the world from the centre outwards",
              tip = "The chunks around you load and light first and the world appears as soon as they are ready; the rest streams in around you." },
        },
    },
}

-- The Profiler tab (2026-09-24): the performance overlay and the game-thread profiler it draws, on a page of their
-- own; same controls, preview and search as the Optimizations tab.
local PROFILER_SECTIONS = {
    {
        title = "Performance overlay (F9, or the \"Toggle performance overlay\" key binding)", clip = "spin",
        entries = {
            { key = "overlaySampling", label = "Sample frame times and utilization (needed for F9)",
              tip = "Records every presented frame, times the GPU with GL timer queries and samples the CPU load twice a second on a background thread. Off by default: without it F9 only shows a notice. \"Show the overlay from boot\" and \"Log every presented frame\" turn it on too. Applies on the next launch." },
            { key = "overlay", label = "Show the overlay from boot",
              tip = "Frame rate, frame-time tail (p99, p99.9, max, 1%-low, jitter, spikes), GPU busy share, game and render thread load, and a frame-time graph. The key toggles it any time." },
            { key = "overlayLog", label = "Log every presented frame",
              tip = "Writes Zomboid/pzopt-overlay.out, one CSV row per frame in MangoHud's column names, for harness/analyze.py. Harness runs log regardless." },
            { key = "overlayStats", label = "Frame statistics",
              choices = { "off", "fps", "tails", "full" },
              note = { fps = "the fps line", tails = "+ p99 / p99.9 / max, 1%-low, jitter, spikes", full = "+ GPU, thread, process and machine load, heap" },
              tip = "The lines at the top of the overlay." },
            { key = "overlayTree", label = "Game-thread tree",
              choices = { "off", "0", "3", "5", "8" },
              note = { ["0"] = "phases only", ["3"] = "3 sub-phases per phase", ["5"] = "5 sub-phases per phase", ["8"] = "8 sub-phases per phase" },
              tip = "What the game thread is doing, from its call stack sampled on a background thread: the phases (update / render / lighting) with their share of the time, under each the biggest sub-phases (chunk bakes, zombies, UI draw, frame hand-off...) with a bar, the wait share in red and the hottest methods. Also logged per second to Zomboid/pzopt-gamethread.out for harness/analyze.py. Applies on the next launch." },
            { key = "overlayVerdict", label = "Verdict line",
              choices = { "off", "short", "detailed" },
              note = { short = "\"at the cap\" / \"GPU bound\" / \"nothing saturated\"", detailed = "+ the two biggest game-thread sub-phases when it is the game thread" },
              tip = "What is holding the frame rate below the cap." },
            { key = "overlayGraph", label = "Frame-time graph",
              choices = { "off", "240", "480", "960" },
              note = { ["240"] = "last 240 frames (480 px)", ["480"] = "last 480 frames (960 px)", ["960"] = "last 960 frames (1920 px)" },
              tip = "A bar per presented frame (green under 1.1x the cap budget, amber under 2x, red above; GPU time in blue) with ms ticks and the budget line." },
            { key = "overlayGraphHz", label = "Frame-time graph redraws per second",
              choices = { "0", "15", "30", "60" },
              note = { ["0"] = "every frame (one sprite per bar)", ["15"] = "15 times a second", ["30"] = "30 times a second", ["60"] = "60 times a second" },
              tip = "With \"Draw the overlay as one texture\" on, the frame-time bars are redrawn into that texture this often instead of drawn as ~480 sprites every frame. 0 draws them every frame." },
            { key = "overlayFlame", label = "Game-thread flame graph",
              choices = { "off", "right", "right-wide", "below" },
              note = { right = "column beside the statistics, 900 px", ["right-wide"] = "column beside the statistics, 1400 px", below = "under the frame graph, panel width" },
              tip = "The last 5 s of stack samples as a flame graph: root (GameWindow.frameStep) at the bottom, callees above, width = share of the time, biggest first from the left; update green, render blue, lighting amber, pzopt frames magenta. Off by default: with \"Draw the overlay as one texture\" off it is the heaviest element (one sprite per box and label every frame). harness/flamegraph.py draws a whole run as an SVG." },
            { key = "overlayFlameDepth", label = "Game-thread flame graph rows",
              choices = { "12", "16", "24", "32", "48" },
              tip = "How many call levels above GameWindow.frameStep the flame graph shows." },
            { key = "gameThreadProfileHz", label = "Game-thread stack samples per second",
              choices = { "10", "25", "50", "100", "200", "500" },
              tip = "Higher resolves short phases sooner; each sample briefly stops the game thread (tens of microseconds). 25 (the default) gives 125 samples over the overlay's 5 s window; each sample costs the game thread about 0.15 ms on a MacBook, so 100 a second takes 1.5 % of its time. Sampling only runs while the overlay is shown or its log is on, and only when the tree, the flame graph, the detailed verdict or the log needs it." },
            { key = "overlayTexture", label = "Draw the overlay as one texture",
              tip = "The overlay's text, tree and flame graph only change four times a second, so they are drawn into an offscreen texture then and each frame shows that texture plus the live frame-time bars. Off draws every letter and box as its own sprite every frame (thousands of quads), which costs frame rate on slower PCs." },
            { key = "overlayRefreshMs", label = "Overlay refresh interval (ms)",
              choices = { "100", "250", "500", "1000" },
              tip = "How often the overlay's numbers, game-thread tree and verdict are recomputed and its texture redrawn. Longer is cheaper; the frame-time graph has its own rate." },
            { key = "overlayCorner", label = "Overlay corner",
              choices = { "tl", "tr", "bl", "br" },
              tip = "Where the overlay sits: top-left, top-right, bottom-left, bottom-right." },
            { key = "overlayFont", label = "Overlay font",
              choices = { "auto", "CodeMedium", "CodeSmall", "CodeLarge", "Small", "Medium", "Large" },
              tip = "The UI font the overlay text uses. auto follows the screen height: CodeSmall under 1000 px, CodeMedium under 1800, CodeLarge above. Whatever the font, the panel fits the screen: the frame graph shows fewer frames, the flame graph keeps up to a third of the width (hints and legend are cut to the rest) or moves under the frame graph and long lines are cut when it would not." },
        },
    },
    {
        title = "Performance overlay: fps colour", clip = "spin",
        entries = {
            { key = "overlayFpsColor", label = "Colour the fps number",
              tip = "The fps number takes one of four colours by how close it is to the target; off = white like the rest of the line." },
            { key = "overlayFpsFollowCap", label = "Colour thresholds follow the framerate cap",
              tip = "On: with a framerate cap the thresholds are percentages of it (the three \"% of the cap\" values). Off, or uncapped: the three fixed fps thresholds apply." },
            { key = "overlayFpsCapBluePct", label = "Threshold, capped: blue, at the cap (% of the cap)",
              choices = { "100", "99", "98", "95", "90" },
              tip = "At or above this share of the cap counts as at the cap. The limiter rarely lands exactly on it, so 100 is stricter than it looks." },
            { key = "overlayFpsCapGreenPct", label = "Threshold, capped: green, at or above (% of the cap)",
              choices = { "95", "90", "85", "80", "75" },
              tip = "Green from this share of the cap up to the blue threshold." },
            { key = "overlayFpsCapYellowPct", label = "Threshold, capped: yellow, at or above (% of the cap)",
              choices = { "75", "66", "50", "33", "25" },
              tip = "Yellow from this share of the cap up to the green threshold; red below it." },
            { key = "overlayFpsBlueAbove", label = "Threshold, uncapped: blue, above (fps)",
              choices = { "500", "400", "300", "240", "200", "165", "144", "120", "60" },
              tip = "Uncapped, or with follow-cap off: blue above this many fps." },
            { key = "overlayFpsGreenAbove", label = "Threshold, uncapped: green, at or above (fps)",
              choices = { "300", "240", "200", "150", "120", "100", "60", "45" },
              tip = "Uncapped, or with follow-cap off: green from this many fps up to the blue threshold." },
            { key = "overlayFpsYellowAbove", label = "Threshold, uncapped: yellow, at or above (fps)",
              choices = { "200", "150", "120", "100", "75", "60", "45", "30" },
              tip = "Uncapped, or with follow-cap off: yellow from this many fps up to the green threshold; red below it." },
            { key = "overlayFpsColorBlue", label = "Tier colour 1: \"at the cap\"",
              choices = FPS_COLOURS,
              tip = "Named colour, or a RRGGBB hex value typed into Zomboid/pzopt/options.ini." },
            { key = "overlayFpsColorGreen", label = "Tier colour 2: \"near the cap\"",
              choices = FPS_COLOURS,
              tip = "Named colour, or a RRGGBB hex value typed into Zomboid/pzopt/options.ini." },
            { key = "overlayFpsColorYellow", label = "Tier colour 3: \"well below\"",
              choices = FPS_COLOURS,
              tip = "Named colour, or a RRGGBB hex value typed into Zomboid/pzopt/options.ini." },
            { key = "overlayFpsColorRed", label = "Tier colour 4: \"far below\"",
              choices = FPS_COLOURS,
              tip = "Named colour, or a RRGGBB hex value typed into Zomboid/pzopt/options.ini." },
        },
    },
}

-- The "Sort by" combo shows the sections in this source order ("natural"), alphabetically (sections by title, settings
-- by label) or by their effect on one resource. Settings that only make sense next to another one (a setting and its
-- sub-settings, the fps colour tiers) carry labels that sort into the same order both ways, and a tip names another
-- setting by its label rather than saying "above" / "below".
local function alphaLess(a, b)
    return string.lower(a) < string.lower(b)
end

local function perf()
    return getPerformance()
end

-- The value the next launch will read: pinned > saved > default.
local function nextValue(entry)
    local p = perf()
    if p:getPzoptOptionPinnedBy(entry.key) ~= "" then
        return p:getPzoptOption(entry.key)
    end
    local saved = p:getPzoptOptionSaved(entry.key)
    if saved ~= "" then
        return saved
    end
    return p:getPzoptOptionDefault(entry.key)
end

local function store(entry, value)
    if value == perf():getPzoptOptionDefault(entry.key) then
        perf():setPzoptOption(entry.key, "")
    else
        perf():setPzoptOption(entry.key, value)
    end
end

local function tooltipFor(entry, pinnedBy)
    local t = entry.tip .. " " .. RESTART_NOTE .. " Key: " .. entry.key .. "."
    if pinnedBy ~= "" then
        t = t .. " Pinned by " .. pinnedBy .. " for this install; the menu cannot change it."
    end
    return t
end

-- The Java classes that read a key: PzoptOptionClasses from pzopt_optimizations_classes.lua, generated by
-- scripts/option-classes.py at build time (looked up late: the client files load in name order, after this one
-- is parsed but before the options screen is built).
local function optionClasses(key)
    local t = PzoptOptionClasses
    return (t and t[key]) or {}
end

-- ---------------------------------------------------------------------------------------------------
-- Preview panel: the two clips, the description and the effect bars of the setting under the mouse.

-- Clips under media/ui/pzopt/compare/<clip>-stock.gif / -opt.gif (harness/menu-gifs.py, harness/menu-gifs.json
-- names the runs). A section's `clip` is the default for its keys; KEY_CLIP picks another for one key.
local CLIP_TITLES = {
    drive = "120 km/h highway drive, clear day, max zoom",
    spin = "Rosewood, camera spinning through town, max zoom",
    fog = "120 km/h drive in heavy fog, max zoom",
    storm = "120 km/h drive in a thunderstorm, max zoom",
    horde = "Downtown Louisville, zombie population maxed",
    torch = "Night, hand torch, turning in place",
    nightdrive = "Night drive with headlights",
    load = "Launch to the main menu, then Continue to the world (real time)",
    -- the performance overlay's elements: the spinning route with the overlay off / with every default element on
    -- (overlayFont=Large, each clip a crop of the same capture; the overlay shows its own numbers, no burned counter)
    overlay = "The whole overlay with every element on: statistics, game-thread tree, verdict, frame graph, flame graph",
    ovstats = "The statistics lines: fps, frame time, p99 / p99.9 / max, 1 %-low, jitter, spikes, GPU and thread loads",
    ovtree = "The game-thread tree: phases, their sub-phases and hot methods, biggest first, waits in red",
    ovverdict = "The verdict line: what holds the frame rate below the cap",
    ovgraph = "The frame-time graph: one bar per presented frame, GPU time in blue, the budget line and ms ticks",
    ovflame = "The flame graph: the last 5 s of game-thread stacks, root at the bottom, biggest first from the left",
    -- the Louisville horde, one group of keys at a time (overlay + profiler on both sides)
    zombies = "Downtown Louisville horde: stock vs stock + only the zombie simulation settings (all cores, lookups, push-apart)",
    player = "Downtown Louisville horde: stock vs stock + only the player line-of-sight settings",
    zgt = "Downtown Louisville horde: every optimization on, without vs with the zombie game-thread settings (on their own over stock they gain nothing: the stock frame waits on other work)",
    grid = "Rosewood, camera spinning, uncapped, every optimization on: the vanilla chunk grid (19x19) vs 15x15",
}
-- Clips whose stock side is a shared GIF (one stock run for several group clips): <STOCK_FILE[clip]>-stock.gif.
local STOCK_FILE = { zombies = "lou", player = "lou" }
-- The captions over the two clips; the overlay clips are "off" / "on" rather than stock / optimized.
local CLIP_SIDES = {
    default = { "STOCK GAME", "OPTIMIZED (every optimization on)" },
    overlay = { "OVERLAY OFF", "OVERLAY ON (F9)" },
    alone = { "STOCK GAME", "STOCK + THESE SETTINGS ONLY" },
    without = { "EVERYTHING ON EXCEPT THESE", "EVERYTHING ON" },
    grid = { "VANILLA GRID (19x19)", "15x15 GRID" },
}
local function clipSides(clip)
    if string.sub(clip, 1, 2) == "ov" then return CLIP_SIDES.overlay end
    if STOCK_FILE[clip] then return CLIP_SIDES.alone end
    if clip == "zgt" then return CLIP_SIDES.without end
    if clip == "grid" then return CLIP_SIDES.grid end
    return CLIP_SIDES.default
end
local KEY_CLIP = {
    rainTiles = "storm", puddleCache = "storm", rainSplashesFast = "storm", puddleEarlyZ = "storm", puddleVbo = "storm",
    puddleCacheFrames = "storm", weatherMaskIdleSkip = "storm", weatherFxScalePct = "storm", vboBatchKb = "storm",
    vboFastQuads = "storm", lightingRebakeBudget = "storm", lightingRebakeMaxFrames = "storm",
    fogPass = "fog", fogScalePct = "fog", fogMaskFrames = "fog",
    upscaler = "drive", upscalerQuality = "drive", upscalerScalePct = "drive", fsrSharpnessPct = "drive", upscalerObjectMv = "drive", dlssPreset = "drive", dlssSharpen = "drive", dlssOutputPct = "drive", dlssOutputFilter = "drive",
    lightingStrongDelta = "torch", lightingStrongBudget = "horde", lightingStrongFrameMs = "horde", lightingFlush = "torch", lightingBudget = "torch",
    lightSwitchCheckFrames = "horde", soundZoneCache = "horde", worldSoundFast = "horde", gridStackInterval = "horde",
    playerLosFast = "player", zombieSpotFast = "player", charDrawPrep = "horde", zombieAtlasFast = "horde", charDrawThreads = "horde",
    actionSnapshotFilter = "zgt", emitterParamSkip = "zgt", separateFast = "zgt", separateParallel = "zgt", sleepCheckMemo = "zgt",
    stateParamMemo = "zgt", actionGroupCache = "zgt", profilerThreadMemo = "zgt", zombieSimLodTiles = "zgt", zombieSimLodSteps = "zgt",
    zombieCheckSpread = "zgt", chunkGridWidth = "grid",
    animBonesParallel = "zombies", vehicleCull = "zombies", frameThreads = "zombies", actionEvalParallel = "zombies", ecsLookupFast = "zombies",
    actionConditionFast = "zombies", skinTransformsPrecompute = "zombies", skinPalettePrecompute = "zombies", shadowPrep = "zombies",
    boneIndexCache = "zombies", lightingReadParallel = "zombies", zombieCullSortFast = "zombies",
    animatorParallel = "zombies", headOnWorker = "zombies", lazyPose = "zombies", animatorPipeline = "zombies", animBatchAsync = "zombies", guardedCallbacks = "zombies",
    modelLockPerInstance = "zombies", poolStatsBatched = "zombies",
    bakeBudget = "drive", rebakeBudget = "drive", rebakeMaxFrames = "drive", treeBakeMaxChunksPerSec = "drive",
    curtainDepthNudgePct = "spin", treeBakePass = "spin", treeBakeDirect = "spin", roofHideDebounceFrames = "spin",
    overlaySampling = "overlay", overlay = "overlay", overlayLog = "overlay", overlayCorner = "overlay", overlayFont = "overlay", overlayTexture = "overlay", overlayRefreshMs = "overlay", overlayGraphHz = "ovgraph",
    gameThreadProfileHz = "ovtree", overlayStats = "ovstats", overlayTree = "ovtree", overlayVerdict = "ovverdict",
    overlayGraph = "ovgraph", overlayFlame = "ovflame", overlayFlameDepth = "ovflame",
    overlayFpsColor = "ovstats", overlayFpsFollowCap = "ovstats", overlayFpsCapBluePct = "ovstats", overlayFpsCapGreenPct = "ovstats",
    overlayFpsCapYellowPct = "ovstats", overlayFpsBlueAbove = "ovstats", overlayFpsGreenAbove = "ovstats", overlayFpsYellowAbove = "ovstats",
    overlayFpsColorBlue = "ovstats", overlayFpsColorGreen = "ovstats", overlayFpsColorYellow = "ovstats", overlayFpsColorRed = "ovstats",
}

-- One bar per resource; `moreIsWork` colours the right side blue instead of amber: putting idle cores to work is
-- the point, not a cost.
local AXES = {
    { id = "cpu", label = "CPU: game thread",
      tip = "The thread that simulates the world and prepares every frame; it is what limits the frame rate most of the time." },
    { id = "render", label = "CPU: render thread",
      tip = "The thread that feeds the GPU." },
    { id = "cores", label = "CPU: other cores", moreIsWork = true,
      tip = "Worker threads: chunk loading, boot and file decoding, sampling." },
    { id = "gpu", label = "GPU" },
    { id = "vram", label = "VRAM" },
    { id = "ram", label = "RAM" },
    { id = "disk", label = "Disk / caches" },
    { id = "load", label = "Boot and load time" },
    { id = "chunks", label = "Chunk arrival" },
}
-- The x axis: the load on that part with the setting, the stock game being "mid". -3 .. 3 -> the word beside the
-- bar; the same words are the axis ticks (-1 shares "low": its bar is a third of the way, the word cannot be finer).
local LEVELS = { [-3] = "lowest", [-2] = "low", [-1] = "low", [0] = "mid", [1] = "high", [2] = "ultra", [3] = "max" }
local AXIS_TICKS = { -3, -2, 0, 1, 2, 3 }
local AXIS_TITLE = "load on that part with this setting  (stock game = mid)"

-- How each key changes the load on the parts above against the stock game, -3 .. 3 (0 / absent = no measurable
-- change): -1 a few percent, -2 clearly measurable, -3 the big wins (docs/results.md, docs/findings-*.md). A combo's
-- bars describe moving it away from stock in the direction the tab offers.
local EFFECTS = {
    enabled = { cpu = -3, render = -3, gpu = -1, cores = 2, ram = 1, disk = 1, load = -3, chunks = -2 },
    -- chunk textures
    treesInChunkTexture = { cpu = -3, render = -2, gpu = -1 },
    treeBakeMaxChunksPerSec = { cpu = -1 },
    treeBakeDirect = {},
    treeBakePass = { cpu = 1 },
    treeAppend = { cpu = -2, gpu = -1 },
    windowsInChunkTexture = { cpu = -2, render = -1, gpu = -1 },
    translucentTilesInChunkTexture = { cpu = -3, render = -2, gpu = -1 },
    curtainDepthNudgePct = {},
    bakeBudget = { cpu = -2 },
    rebakeBudget = { cpu = -1 },
    rebakeMaxFrames = {},
    lightingRebakeBudget = { cpu = -2, gpu = -1 },
    lightingRebakeMaxFrames = { cpu = -1 },
    lightingRebakeMs = { cpu = -1 },
    zoomRetain = { cpu = -2, gpu = -1, vram = 1 },
    zoomRebakeBudget = { cpu = -1 },
    zoomPlaceholder = { vram = 1 },
    zoomFrameMs = {},
    zoomEaseMs = {},
    zoomEase = {},
    lightingStrongDelta = { cpu = 1 },
    lightingStrongBudget = { cpu = -1, gpu = -2 },
    lightingStrongFrameMs = { gpu = -1 },
    animBonesParallel = { cpu = -1, cores = 1 },
    animBonesThreads = { cores = 1 },
    frameThreads = { cores = 1 },
    actionEvalParallel = { cpu = -2, cores = 1 },
    animatorParallel = { cpu = -2, cores = 1 },
    headOnWorker = { cpu = -1, cores = 1 },
    lazyPose = { cpu = -1 },
    animatorPipeline = { cpu = -1, cores = 1 },
    animBatchAsync = { cpu = -1, cores = 1 },
    guardedCallbacks = { cpu = -1 },
    modelLockPerInstance = { cores = 1 },
    poolStatsBatched = { cores = 1 },
    ecsLookupFast = { cpu = -1 },
    actionConditionFast = { cpu = -1 },
    skinTransformsPrecompute = { cpu = -1, cores = 1 },
    skinPalettePrecompute = { cpu = -1 },
    shadowPrep = { cpu = -1 },
    boneIndexCache = { cpu = -1 },
    lightingReadParallel = { cpu = -2, cores = 1 },
    zombieCullSortFast = { cpu = -1 },
    vehicleCull = { cpu = -1 },
    playerLosFast = { cpu = -2 },
    zombieSpotFast = { cpu = -1 },
    charDrawPrep = { cpu = -2, cores = 1 },
    zombieAtlasFast = { cpu = -1 },
    actionSnapshotFilter = { cpu = -2 },
    emitterParamSkip = { cpu = -1 },
    separateFast = { cpu = -1 },
    separateParallel = { cpu = -2, cores = 1 },
    sleepCheckMemo = { cpu = -1 },
    stateParamMemo = { cpu = -1, ram = -1 },
    actionGroupCache = { cpu = -1, ram = -1 },
    profilerThreadMemo = { cpu = -1 },
    zombieSimLodTiles = { cpu = -3 },
    zombieSimLodSteps = { cpu = -1 },
    zombieCheckSpread = { cpu = -1 },
    charDrawThreads = { cores = 1 },
    lightingGlobalDeltaPct = { cpu = -1 },
    lightingFlush = { cpu = 1 },
    lightingBudget = { cpu = -2 },
    -- cutaways, lighting, weather
    cutawayFast = { cpu = -2 },
    cutawayRadius = { cpu = -2 },
    gridStackInterval = { cpu = -1 },
    lightSwitchCheckFrames = { cpu = -1 },
    rainTiles = { cpu = -3, render = -3, gpu = -1 },
    puddleCache = { cpu = -3, ram = 1 },
    rainSplashesFast = { cpu = -1 },
    puddleEarlyZ = { gpu = -2 },
    puddleVbo = { render = -3, gpu = -1, vram = 1 },
    puddleCacheFrames = { cpu = -1 },
    weatherMaskIdleSkip = { cpu = -1, gpu = -1 },
    weatherFxScalePct = { gpu = -2, render = -1, vram = -1 },
    fogPass = { gpu = -3, render = -2, cpu = -1, vram = 1 },
    upscaler = { gpu = -3, render = -1, vram = 1 },
    upscalerQuality = { gpu = -2 },
    upscalerScalePct = { gpu = -2 },
    fsrSharpnessPct = { gpu = 0 },
    upscalerObjectMv = { gpu = 1, render = 1 },
    dlssPreset = { gpu = -1 },
    dlssSharpen = { gpu = 0 },
    dlssOutputPct = { gpu = -2 },
    dlssOutputFilter = { gpu = 1 },
    fogScalePct = { gpu = -2, vram = -1 },
    fogMaskFrames = { cpu = -1 },
    roofHideDebounceFrames = {},
    cutawayVisitPrefilter = { cpu = -1 },
    cutawayInvalidateChanged = { cpu = -2, gpu = -1 },
    occlusionSkipLightingOnly = { cpu = -1 },
    lightInfoChunkGate = { cpu = -1 },
    lightInfoOncePerFrame = { cpu = -1 },
    soundZoneCache = { cpu = -1 },
    worldSoundFast = { cpu = -1 },
    -- sprite buffers
    persistentVbo = { render = -3, gpu = -1 },
    vboBatchKb = { render = -2, vram = 1 },
    vboFastQuads = { render = -1 },
    -- multiplayer
    luaChecksumExempt = {},
    -- updates
    updateCheck = {},
    -- overlay
    overlaySampling = { cpu = 1, cores = 1 },
    overlay = { cpu = 1 },
    overlayLog = { disk = 1 },
    overlayTree = { cpu = 1, cores = 1 },
    overlayVerdict = {},
    overlayGraph = { cpu = 1 },
    overlayTexture = { cpu = -1, render = -2, gpu = -1 },
    overlayRefreshMs = { cpu = -1 },
    overlayGraphHz = { cpu = 1, render = 1 },
    overlayFlame = { cpu = 1, cores = 1 },
    gameThreadProfileHz = { cores = 1, cpu = 1 },
    -- chunk streaming
    parallel = { cores = 3, ram = 1, chunks = -3 },
    workers = { cores = 2, chunks = -1 },
    loadWorkers = { cores = 2, load = -1 },
    wake = { chunks = -2 },
    chunkHandoffDivisor = { cpu = -1, chunks = 1 },
    chunkGridWidth = { cpu = -2, gpu = -1, ram = -1, vram = -1, load = -1 }, -- the bars describe the smaller grids
    hotsaveStaged = { cpu = -1 },
    hotsaveIntervalSec = { cpu = -2, disk = -2 },
    -- boot
    fmodAsync = { load = -2, cores = 1 },
    preloadAnimSets = { load = -2, cores = 1 },
    bootPump = { load = -2, cores = 2, ram = 1 },
    bootFileThreads = { load = -1, cores = 2 },
    earlyModels = { load = -1 },
    luaPrecompile = { load = -2, cores = 2 },
    animClipCache = { load = -2, disk = 2 },
    packIndex = { load = -2, disk = 1 },
    scriptParserFast = { load = -2, ram = -1 },
    itemParamSwitch = { load = -1 },
    -- world load
    fileThreads = { load = -1, cores = 2 },
    fileInflight = { load = -1, ram = 1 },
    textureBufferMb = { load = -1, ram = 2 },
    parallelDepthMaps = { load = -1, cores = 1 },
    loaderCpuFixes = { load = -1, cpu = -1 },
    shaderCache = { load = -3, render = -1 },
    mipmapArrays = { cores = -1 },
    noLoadFade = { load = -1 },
    noIntroWait = { load = -2 },
    noClickToStart = { load = -2 },
    noLoadingScreen = { load = -1 },
    resumeShot = { load = -1, disk = 1 },
    centerFirstLoad = { load = -2, chunks = -1 },
    tileDefPreload = { load = -2, cores = 1 },
    skipIdChecks = { load = -1 },
    voronoiFast = { load = -2, cores = -1 },
    earlyTilePacks = { load = -1 },
    aotCache = { load = -2, disk = 1 },
}

-- SDR values of the media style (docs/media-style.md): stock amber, optimized green, a third series blue
local C_STOCK = { r = 0.79, g = 0.35, b = 0.17 }
local C_OPT = { r = 0.27, g = 0.87, b = 0.49 }
local C_BLUE = { r = 0.23, g = 0.56, b = 0.88 }
local C_TEXT = { r = 0.94, g = 0.94, b = 0.96 }
local C_GREY = { r = 0.66, g = 0.66, b = 0.70 }
local C_DIM = { r = 0.40, g = 0.40, b = 0.45 }
local CLIP_W, CLIP_H = 512, 216

local function clipPath(clip, side)
    if side == "stock" and STOCK_FILE[clip] then clip = STOCK_FILE[clip] end
    return "media/ui/pzopt/compare/" .. clip .. "-" .. side .. ".gif"
end

PzoptPreview = ISPanel:derive("PzoptPreview")

function PzoptPreview:new(x, y, w, h, panel, rows)
    local o = ISPanel.new(self, x, y, w, h)
    o.panel = panel   -- the scrolling options panel the rows live in
    o.rows = rows     -- { entry, option, clip, y, h } in the panel's content space
    o.row = nil
    o.backgroundColor = { r = 0.04, g = 0.04, b = 0.06, a = 1 } -- opaque: the list's long labels pass under it
    o.borderColor = { r = 0.31, g = 0.31, b = 0.35, a = 1 }
    o.pad = 12
    o.fontS = UIFont.Small
    o.fontM = UIFont.Medium
    o.hS = getTextManager():getFontHeight(UIFont.Small)
    o.hM = getTextManager():getFontHeight(UIFont.Medium)
    -- every controller-focusable element of a row (the curve sliders are several lines) -> the row
    o.byControl = {}
    for _, row in ipairs(rows) do
        for _, line in ipairs(row.option.pzoptJoyLines or { { row.option.control } }) do
            for _, el in ipairs(line) do o.byControl[el] = row end
        end
    end
    o:layoutSlots()
    return o
end

-- Every part of the panel has a fixed place, so nothing moves when the mouse crosses to another row: the
-- panel is the whole free area of the page, the text column is centred in it (capped so lines stay readable),
-- the description slot is as tall as the longest description wrapped at that width, the clips take the height
-- that is left (up to 3x their 512 px source), the bars and the legend sit under the description.
function PzoptPreview:layoutSlots()
    local pad, gap = self.pad, self.pad
    local cw = math.min(self.width - 2 * pad, 2 * CLIP_W * 3 + gap)
    self.colX = math.floor((self.width - cw) / 2)
    self.colW = cw
    local lines = 1
    local function count(tip)
        local n = 0
        for _ in string.gmatch(getTextManager():WrapText(self.fontS, tip, cw), "[^\n]+") do n = n + 1 end
        if n > lines then lines = n end
    end
    for _, row in ipairs(self.rows) do count(row.entry.tip) end
    self.descLines = lines
    local fixed = pad + self.hM + 2 + self.hS + 2 + self.hS + 8 -- title, values, Java classes
        + self.hS + 2 + 4 + self.hS + 8                    -- clip captions, clip title line
        + lines * self.hS + 8                              -- description
        + self.hM + 4 + #AXES * (self.hS + 6)              -- bars
        + 6 + self.hS + 2 + self.hS                        -- x axis ticks and title
        + 4 + 2 * self.hS + pad                            -- legend
    local ih = math.max(60, self.height - fixed)
    local iw = math.floor((cw - gap) / 2)
    if math.floor(iw * CLIP_H / CLIP_W) < ih then
        ih = math.floor(iw * CLIP_H / CLIP_W)
    else
        iw = math.floor(ih * CLIP_W / CLIP_H)
    end
    self.clipW, self.clipH = iw, ih
    -- height the clips did not need (a wide page) makes the bar rows taller, up to twice the font height
    local spare = math.max(0, self.height - fixed - ih)
    self.barRowH = math.min(2 * self.hS + 8, self.hS + 6 + math.floor(spare / #AXES))
    self.slotW, self.slotH = self.width, self.height
end

-- the wheel over the preview scrolls the options list, like anywhere else on the page
function PzoptPreview:onMouseWheel(del)
    return false
end

function PzoptPreview:select(row)
    self.row = row
end

-- The row under the mouse: the list's mouse position is in its content space (scroll included), like row.y.
-- With a controller (the page holds the joypad focus) the row of the focused line instead, wherever the mouse
-- is; a section heading or the search row keeps the last setting shown.
function PzoptPreview:pick()
    local panel = self.panel
    if panel.joyfocus then
        local line = panel.joypadButtonsY and panel.joypadButtonsY[panel.joypadIndexY or 0]
        for _, el in ipairs(line or {}) do
            local row = self.byControl[el]
            if row then
                self:select(row)
                return
            end
        end
        return
    end
    if not panel:isMouseOver() or self:isMouseOver() then return end
    local mx, my = panel:getMouseX(), panel:getMouseY()
    if mx >= self.x then return end
    for _, row in ipairs(self.rows) do
        if not row.hidden and my >= row.y and my < row.y + row.h then
            self:select(row)
            return
        end
    end
end

function PzoptPreview:text(str, x, y, col, font, alpha)
    self:drawText(str, x, y, col.r, col.g, col.b, alpha or 1, font or self.fontS)
end

-- One clip box: caption, the current frame (or why there is none), a hairline frame.
function PzoptPreview:drawClip(x, y, w, h, caption, path, now, col)
    self:text(caption, x, y, col, self.fontS)
    y = y + self.hS + 2
    self:drawRect(x, y, w, h, 1, 0.02, 0.02, 0.03)
    local ok, tex = pcall(function() return perf():getPzoptGifFrame(path, now) end)
    if ok and tex then
        self:drawTextureScaledAspect(tex, x, y, w, h, 1, 1, 1, 1)
    else
        local state = ok and perf():getPzoptGifState(path) or "error"
        local msg = "no clip for this setting yet"
        if state == "loading" then msg = "loading..." elseif state == "error" then msg = "clip could not be decoded" end
        self:drawTextCentre(msg, x + w / 2, y + h / 2 - self.hS / 2, C_DIM.r, C_DIM.g, C_DIM.b, 1, self.fontS)
    end
    self:drawRectBorder(x, y, w, h, 1, 0.31, 0.31, 0.35)
    return y + h
end

function PzoptPreview:drawWrapped(str, x, y, w, col, font)
    local wrapped = getTextManager():WrapText(font or self.fontS, str, w)
    local h = font == self.fontM and self.hM or self.hS
    for line in string.gmatch(wrapped, "[^\n]+") do
        self:text(line, x, y, col, font)
        y = y + h
    end
    return y
end

-- The effect bars: a track per axis with the zero line in the middle; the bar grows left for less work / sooner
-- (green) and right for more (amber, or blue where more means idle cores put to work), a word says the same.
function PzoptPreview:drawBars(x, y, w, fx)
    local labW = math.min(170, math.floor(w * 0.3))
    local wordW = 110
    local barX = x + labW + 10
    local barW = w - labW - 10 - wordW - 8
    local rowH = self.barRowH or (self.hS + 6)
    local textY = math.floor((rowH - self.hS) / 2)
    local mid = barX + math.floor(barW / 2)
    local half = math.floor(barW / 2) - 2
    for _, axis in ipairs(AXES) do
        local v = fx[axis.id] or 0
        if v > 3 then v = 3 elseif v < -3 then v = -3 end
        self:drawTextRight(axis.label, x + labW, y + textY, C_GREY.r, C_GREY.g, C_GREY.b, 1, self.fontS)
        self:drawRect(barX, y + 3, barW, rowH - 6, 1, 0.11, 0.11, 0.13)
        local word, wcol = LEVELS[0], C_DIM
        if v ~= 0 then
            local len = math.floor(half * math.abs(v) / 3)
            local col = v < 0 and C_OPT or (axis.moreIsWork and C_BLUE or C_STOCK)
            local bx = v < 0 and (mid - len) or (mid + 1)
            self:drawRect(bx, y + 3, len, rowH - 6, 1, col.r, col.g, col.b)
            word = LEVELS[v]
            wcol = col
        end
        -- faint ticks through the track at every level, the zero line stronger
        for _, t in ipairs(AXIS_TICKS) do
            local tx = mid + math.floor(half * t / 3)
            self:drawRect(tx, y + 3, 1, rowH - 6, 1, 0.22, 0.22, 0.26)
        end
        self:drawRect(mid, y + 1, 1, rowH - 2, 1, 0.55, 0.55, 0.60)
        self:text(word, barX + barW + 8, y + textY, wcol, self.fontS)
        y = y + rowH
    end
    -- the x axis: a rule, a tick and a word per level, the title under them
    self:drawRect(barX, y, barW, 1, 1, 0.55, 0.55, 0.60)
    for _, t in ipairs(AXIS_TICKS) do
        local tx = mid + math.floor(half * t / 3)
        self:drawRect(tx, y, 1, 5, 1, 0.55, 0.55, 0.60)
        local label = LEVELS[t]
        local lw = getTextManager():MeasureStringX(self.fontS, label)
        local lx = tx - math.floor(lw / 2)
        if lx < barX then lx = barX elseif lx + lw > barX + barW then lx = barX + barW - lw end
        local col = t < 0 and C_OPT or (t > 0 and C_STOCK or C_GREY)
        self:text(label, lx, y + 6, col, self.fontS)
    end
    y = y + 6 + self.hS + 2
    self:drawTextCentre(AXIS_TITLE, mid, y, C_DIM.r, C_DIM.g, C_DIM.b, 1, self.fontS)
    return y + self.hS
end

function PzoptPreview:prerender()
    ISPanel.prerender(self)
    if self.width ~= self.slotW or self.height ~= self.slotH then self:layoutSlots() end -- window resized
    self:pick()
    local row = self.row
    local pad = self.pad
    local x, y, w = self.colX, pad, self.colW
    if not row then
        self:text("Point at a setting to see what it does.", x, y, C_GREY, self.fontM)
        return
    end
    local entry = row.entry
    local p = perf()
    -- title and values: one line each, cut with "..." rather than wrapped
    self:text(getTextManager():WrapText(self.fontM, entry.label, w, 1, "..."), x, y, C_TEXT, self.fontM)
    y = y + self.hM + 2
    local pinnedBy = p:getPzoptOptionPinnedBy(entry.key)
    local values = "Key " .. entry.key .. "   since this boot: " .. p:getPzoptOption(entry.key)
        .. "   next launch: " .. row.option:pzoptCurrent()
    if pinnedBy ~= "" then values = values .. "   (pinned by " .. pinnedBy .. ")" end
    self:text(getTextManager():WrapText(self.fontS, values, w, 1, "..."), x, y, C_GREY)
    y = y + self.hS + 2
    local classes = optionClasses(entry.key)
    local java = #classes > 0 and ("Java: " .. table.concat(classes, ", ")) or "Java: read by pzopt.Config only"
    self:text(getTextManager():WrapText(self.fontS, java, w, 1, "..."), x, y, C_DIM)
    y = y + self.hS + 8
    -- the two clips, centred in the column
    local iw, ih, gap = self.clipW, self.clipH, pad
    local cx = x + math.floor((w - (2 * iw + gap)) / 2)
    local now = getTimestampMs()
    local sides = clipSides(row.clip)
    self:drawClip(cx, y, iw, ih, sides[1], clipPath(row.clip, "stock"), now, C_STOCK)
    y = self:drawClip(cx + iw + gap, y, iw, ih, sides[2], clipPath(row.clip, "opt"), now, C_OPT) + 4
    local same = sides == CLIP_SIDES.overlay and ". Same save, route and machine, a crop of the top-left corner at the Large overlay font."
        or ". Same save, route and machine; the number is that run's live frame rate."
    self:text(getTextManager():WrapText(self.fontS, (CLIP_TITLES[row.clip] or row.clip) .. same, w, 1, "..."), x, y, C_DIM)
    y = y + self.hS + 8
    -- what it does, in a slot tall enough for the longest description
    self:drawWrapped(entry.tip, x, y, w, C_TEXT)
    y = y + self.descLines * self.hS + 8
    -- the bars
    self:text("Effect on your hardware", x, y, C_TEXT, self.fontM)
    y = y + self.hM + 4
    y = self:drawBars(x, y, w, EFFECTS[entry.key] or {})
    self:drawWrapped("Against the stock game, from the measurements in docs/results.md: green = less load (or a shorter "
        .. "load, chunks sooner), amber = more, blue = idle cores put to work. " .. RESTART_NOTE, x, y + 4, w, C_DIM)
end

-- ---------------------------------------------------------------------------------------------------
-- Search: BM25 over every setting's label, key, description, combo notes, section, the resources its EFFECTS bars
-- move (the AXES names) and the Java classes that read its key, with fuzzy term matching (prefix, substring, one
-- typo from 4 letters, two from 7). Each typed word must match (camelCase and dotted names count as one word:
-- "FogPass" matches the class or both "fog" and "pass"); a word may also match a term that is a longer form of it.

local STOPWORDS = {}
for w in string.gmatch("a an and are as at be by for from in into is it its of on or so than that the then this to with", "%a+") do
    STOPWORDS[w] = true
end
local BM25_K1, BM25_B = 1.2, 0.75
-- field weights (term-frequency multipliers, BM25F-style)
local W_LABEL, W_KEY, W_CLASS, W_RESOURCE, W_SECTION, W_TIP = 3, 3, 2, 2, 1, 1

-- The parts of one identifier-ish word: "treesInChunkTexture" -> trees chunk texture, "FBORenderCell" -> fbo render
-- cell, "pzopt.FogPass" -> pzopt fog pass.
local function parts(word)
    local spaced = string.gsub(word, "(%l)(%u)", "%1 %2")
    spaced = string.gsub(spaced, "(%u)(%u%l)", "%1 %2")
    local out = {}
    for w in string.gmatch(string.lower(spaced), "%w+") do
        if not STOPWORDS[w] then table.insert(out, w) end
    end
    return out
end

-- Adds the terms of a text to tf with a weight: every part, and the whole word (dots dropped) when it had several.
local function addTerms(tf, text, weight)
    local n = 0
    for word in string.gmatch(tostring(text or ""), "[%w%.]+") do
        local ps = parts(word)
        for _, t in ipairs(ps) do
            tf[t] = (tf[t] or 0) + weight
            n = n + weight
        end
        local whole = string.lower((string.gsub((string.gsub(word, "^pzopt%.", "")), "%.", "")))
        if #ps > 1 and whole ~= "" then
            tf[whole] = (tf[whole] or 0) + weight
            n = n + weight
        end
    end
    return n
end

-- Levenshtein distance, giving up (limit + 1) once every path is past the limit.
local function editDistance(a, b, limit)
    local la, lb = #a, #b
    if math.abs(la - lb) > limit then return limit + 1 end
    local prev = {}
    for j = 0, lb do prev[j] = j end
    for i = 1, la do
        local cur = { [0] = i }
        local best = i
        local ca = string.byte(a, i)
        for j = 1, lb do
            local v = math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + ((ca == string.byte(b, j)) and 0 or 1))
            cur[j] = v
            if v < best then best = v end
        end
        if best > limit then return limit + 1 end
        prev = cur
    end
    return prev[lb]
end

-- The index over the tab's rows: one document per setting.
local function buildIndex(rows, sectionOf)
    local index = { docs = {}, df = {}, vocab = {}, expand = {}, avgdl = 1 }
    local total = 0
    for _, row in ipairs(rows) do
        local entry, tf, len = row.entry, {}, 0
        len = len + addTerms(tf, entry.label, W_LABEL)
        len = len + addTerms(tf, entry.key, W_KEY)
        len = len + addTerms(tf, entry.tip, W_TIP)
        if entry.note then
            for _, v in pairs(entry.note) do len = len + addTerms(tf, v, W_TIP) end
        end
        len = len + addTerms(tf, sectionOf[row] and sectionOf[row].title, W_SECTION)
        local fx = EFFECTS[entry.key] or {}
        for _, axis in ipairs(AXES) do
            if fx[axis.id] and fx[axis.id] ~= 0 then
                len = len + addTerms(tf, axis.id .. " " .. axis.label, W_RESOURCE)
            end
        end
        for _, name in ipairs(optionClasses(entry.key)) do
            len = len + addTerms(tf, name, W_CLASS)
        end
        table.insert(index.docs, { row = row, tf = tf, len = len })
        total = total + len
        for t in pairs(tf) do
            if not index.df[t] then table.insert(index.vocab, t) end
            index.df[t] = (index.df[t] or 0) + 1
        end
    end
    local n = #index.docs
    if n > 0 then index.avgdl = total / n end
    index.idf = {}
    for t, df in pairs(index.df) do
        index.idf[t] = math.log(1 + (n - df + 0.5) / (df + 0.5))
    end
    return index
end

-- The vocabulary terms a query word stands for, with a weight: exact 1, a longer form 0.8, the word inside a term
-- 0.5, one typo 0.6, two typos 0.35.
local function expand(index, q)
    local hit = index.expand[q]
    if hit then return hit end
    hit = {}
    local lq = #q
    for _, t in ipairs(index.vocab) do
        local w
        if t == q then
            w = 1
        elseif lq >= 2 and string.sub(t, 1, lq) == q then
            w = 0.8
        elseif #t >= 4 and lq > #t and lq - #t <= 2 and string.sub(q, 1, #t) == t then
            w = 0.7 -- "chunks" -> "chunk"
        elseif lq >= 3 and string.find(t, q, 1, true) then
            w = 0.5
        elseif lq >= 4 and (string.byte(t, 1) == string.byte(q, 1) or string.byte(t, 2) == string.byte(q, 2)) then
            -- a typo rarely hits both of the first two letters; the check keeps the scan cheap under Kahlua
            local d = editDistance(q, t, lq >= 7 and 2 or 1)
            if d == 1 then w = 0.6 elseif d == 2 and lq >= 7 then w = 0.35 end
        end
        if w then table.insert(hit, { t = t, w = w }) end
    end
    index.expand[q] = hit
    return hit
end

local function termScore(index, doc, t)
    local tf = doc.tf[t]
    if not tf then return 0 end
    local norm = 1 - BM25_B + BM25_B * doc.len / index.avgdl
    return index.idf[t] * tf * (BM25_K1 + 1) / (tf + BM25_K1 * norm)
end

-- Best weighted BM25 score of one query term in one document (0 = no match).
local function wordScore(index, doc, q)
    local best = 0
    for _, e in ipairs(expand(index, q)) do
        local s = doc.tf[e.t] and e.w * termScore(index, doc, e.t) or 0
        if s > best then best = s end
    end
    return best
end

-- Scores every row for a query: { [row] = score } of the rows every query word matched (all words must match;
-- if no row has them all, the rows matching any word), and how many matched.
local function search(index, query)
    local units = {}
    for word in string.gmatch(query, "[%w%.]+") do
        local ps = parts(word)
        local whole = string.lower((string.gsub((string.gsub(word, "^pzopt%.", "")), "%.", "")))
        if #ps > 0 or (whole ~= "" and not STOPWORDS[whole]) then
            table.insert(units, { whole = whole, parts = ps })
        end
    end
    if #units == 0 then return nil, 0 end
    local all, any = {}, {}
    local nAll, nAny = 0, 0
    for _, doc in ipairs(index.docs) do
        local total, matched = 0, 0
        for _, u in ipairs(units) do
            local s = wordScore(index, doc, u.whole)
            if #u.parts > 1 then
                local sum = 0
                for _, p in ipairs(u.parts) do
                    local ps = wordScore(index, doc, p)
                    if ps == 0 then sum = 0 break end
                    sum = sum + ps
                end
                if sum > s then s = sum end
            end
            if s > 0 then matched = matched + 1 end
            total = total + s
        end
        if matched == #units then all[doc.row] = total; nAll = nAll + 1 end
        if matched > 0 then any[doc.row] = total; nAny = nAny + 1 end
    end
    local hits, n = all, nAll
    if nAll == 0 then hits, n = any, nAny end
    -- drop the weak tail fuzzy matching drags in
    local top = 0
    for _, s in pairs(hits) do if s > top then top = s end end
    for row, s in pairs(hits) do
        if s < 0.15 * top then hits[row] = nil; n = n - 1 end
    end
    return hits, n
end

-- ---------------------------------------------------------------------------------------------------
-- Collapsible sections. Every row below the search box (section headings, settings, the closing note) remembers its
-- UI elements and their offsets; pzoptRelayout places the visible ones top to bottom, hides the rest and rebuilds
-- the controller navigation in the same order. Which sections are folded is kept for the session.

local COLLAPSED = {}
-- The "Sort by" choice, kept for the session like the folds: "natural", "alpha" or an AXES id.
local SORT = { [TAB] = "alpha", [PROFILER_TAB] = "natural" }
-- What the three headings of a resource sort say, per axis id (default: load).
local LESS_WORDS = { load = "shorter", chunks = "sooner" }
local MORE_WORDS = { load = "longer", chunks = "later", cores = "more work for idle cores" }

-- The groups the list shows for the current sort, in display order: { sec = <heading>, rows = { ... } }.
-- A resource sort regroups every setting under three headings of its own (S.virtual): the ones that lower that
-- resource's load (biggest change first), the ones that raise it, and the rest alphabetically.
local function sortedGroups(S)
    if SORT[S.tab] == "natural" then
        local out = {}
        for _, sec in ipairs(S.sections) do table.insert(out, { sec = sec, rows = sec.rows }) end
        return out
    end
    local axis
    for _, a in ipairs(AXES) do
        if a.id == SORT[S.tab] then axis = a end
    end
    if not axis then return S.alphaGroups end
    local less, more, none = {}, {}, {}
    local value = {}
    for _, row in ipairs(S.managed) do
        local v = (EFFECTS[row.entry.key] or {})[axis.id] or 0
        value[row] = v
        table.insert(v < 0 and less or (v > 0 and more or none), row)
    end
    local function byLabel(a, b) return alphaLess(a.entry.label, b.entry.label) end
    table.sort(less, function(a, b)
        if value[a] ~= value[b] then return value[a] < value[b] end
        return byLabel(a, b)
    end)
    table.sort(more, function(a, b)
        if value[a] ~= value[b] then return value[a] > value[b] end
        return byLabel(a, b)
    end)
    table.sort(none, byLabel)
    local V = S.virtual
    V[1].title, V[1].rows = axis.label .. ": " .. (LESS_WORDS[axis.id] or "less load") .. ", biggest change first", less
    V[2].title, V[2].rows = axis.label .. ": " .. (MORE_WORDS[axis.id] or "more load") .. ", biggest change first", more
    V[3].title, V[3].rows = axis.label .. ": no measured change", none
    if axis.moreIsWork then
        return { { sec = V[2], rows = more }, { sec = V[1], rows = less }, { sec = V[3], rows = none } }
    end
    return { { sec = V[1], rows = less }, { sec = V[2], rows = more }, { sec = V[3], rows = none } }
end

local function placeRow(row, y)
    for _, e in ipairs(row.elems) do
        e.el:setY(y + e.dy)
        e.el:setVisible(true)
    end
    row.hidden = false
    if row.controlDy then row.y = y + row.controlDy end
end

local function hideRow(row)
    for _, e in ipairs(row.elems) do e.el:setVisible(false) end
    row.hidden = true
end

-- Off-screen rows draw nothing. The UI renders every child of a scrolled panel each frame and lets the stencil drop
-- what is outside, so this page (~100 rows, ~340 controls) cost ~8 ms a frame on an M1 Pro against ~0.5 ms for the
-- ~25 rows on screen (controller menu profile, 2026-09-23). A control more than CULL_MARGIN px outside the scrolled
-- band gets no-op prerender / render instead of hiding it: hidden controls would drop out of the controller rows
-- (ISPanelJoypad walks visible children only) and ensureVisible could no longer scroll to them. Recomputed only when
-- the scroll band or the layout (search, fold, sort) changed; the instance's own prerender / render come back as
-- they were.
local CULL_MARGIN = 50
local NOOP = function() end

local function cullElement(el, off)
    if off == (el.pzoptCulled == true) then return end
    if off then
        el.pzoptCulled = true
        el.pzoptOwnPrerender, el.pzoptOwnRender = rawget(el, "prerender"), rawget(el, "render")
        el.prerender, el.render = NOOP, NOOP
    else
        el.pzoptCulled = nil
        el.prerender, el.render = el.pzoptOwnPrerender, el.pzoptOwnRender
        el.pzoptOwnPrerender, el.pzoptOwnRender = nil, nil
    end
end

local function cullRow(row, top, bottom)
    for _, e in ipairs(row.elems) do
        local el = e.el
        local y = el:getY()
        cullElement(el, y + el:getHeight() < top - CULL_MARGIN or y > bottom + CULL_MARGIN)
    end
end

local function cullRows(S)
    local panel = S.panel
    local top = -panel:getYScroll()
    local bottom = top + panel:getHeight()
    if S.cullTop == top and S.cullBottom == bottom and S.cullGen == S.layoutGen then return end
    S.cullTop, S.cullBottom, S.cullGen = top, bottom, S.layoutGen
    for _, row in ipairs(S.managed) do cullRow(row, top, bottom) end
    for _, sec in ipairs(S.allSections) do cullRow(sec.header, top, bottom) end
    cullRow(S.footer, top, bottom)
end

local function relayout(S)
    local panel = S.panel
    local y = S.top
    local joy = {}
    local groups
    if S.hits then
        -- a search ranks: the sections by their best match, the matches inside by score, whatever the sort
        local order = {}
        for _, sec in ipairs(S.sections) do table.insert(order, sec) end
        table.sort(order, function(a, b)
            if a.best ~= b.best then return a.best > b.best end
            return a.index < b.index
        end)
        groups = {}
        for _, sec in ipairs(order) do table.insert(groups, { sec = sec, rows = sec.hitRows }) end
    else
        groups = sortedGroups(S)
    end
    local shown, placed = {}, {}
    for _, g in ipairs(groups) do
        local sec = g.sec
        if #g.rows > 0 then
            placeRow(sec.header, y)
            placed[sec] = true
            y = y + sec.header.step
            table.insert(joy, { sec.header.button })
            if S.hits or not COLLAPSED[sec.title] then
                for _, row in ipairs(g.rows) do
                    placeRow(row, y)
                    y = y + row.step
                    shown[row] = true
                    for _, line in ipairs(row.option.pzoptJoyLines or { { row.option.control } }) do
                        table.insert(joy, line)
                    end
                end
            end
        end
    end
    for _, sec in ipairs(S.allSections) do
        if not placed[sec] then hideRow(sec.header) end
    end
    for _, row in ipairs(S.managed) do
        if not shown[row] then hideRow(row) end
    end
    placeRow(S.footer, y)
    y = y + S.footer.step
    panel:setScrollHeight(y + 20)
    local maxScroll = math.max(0, y + 20 - panel:getHeight())
    if -panel:getYScroll() > maxScroll then panel:setYScroll(-maxScroll) end
    -- controller navigation: the fixed rows at the top, then the visible rows in display order
    for i = #panel.joypadButtonsY, S.joyTop + 1, -1 do table.remove(panel.joypadButtonsY, i) end
    for _, line in ipairs(joy) do table.insert(panel.joypadButtonsY, line) end
    panel.joypadButtons = panel.joypadButtonsY[#panel.joypadButtonsY]
    if (panel.joypadIndexY or 1) > #panel.joypadButtonsY then
        panel.joypadIndexY = #panel.joypadButtonsY
        panel.joypadIndex = 1
    end
    S.layoutGen = (S.layoutGen or 0) + 1 -- cullRows looks again
end

local function runSearch(S, text)
    local hits, n = nil, 0
    if text and string.match(text, "%w") then
        hits, n = search(S.index, text)
    end
    S.hits = hits
    for _, sec in ipairs(S.sections) do
        sec.hitRows, sec.best = {}, 0
        if hits then
            for _, row in ipairs(sec.rows) do
                local s = hits[row]
                if s then
                    table.insert(sec.hitRows, row)
                    if s > sec.best then sec.best = s end
                end
            end
            table.sort(sec.hitRows, function(a, b)
                if hits[a] ~= hits[b] then return hits[a] > hits[b] end
                return a.index < b.index
            end)
        end
    end
    if not hits then
        S.status:setName(S.total .. " settings")
    elseif n == 0 then
        S.status:setName("Nothing matches")
    else
        S.status:setName(n .. " of " .. S.total .. " match")
    end
    S.panel:setYScroll(0)
    relayout(S)
end

-- A section heading that folds its section: a rule stopping short of the preview panel, "+" / "-", the title, and
-- how many settings it holds (or match the search). It is a button, so the mouse and a controller's A both work.
local function addSectionHeader(self, S, sec, y, x0, width)
    local spacing = MainOptions.style.borderSpacing
    local hM = MainOptions.style:getFontHeight("Medium")
    local hS = getTextManager():getFontHeight(UIFont.Small)
    local b = ISButton:new(x0, self.addY + y, width, spacing + hM, "", S, function(target)
        if target.hits then return end -- a search shows every match unfolded
        COLLAPSED[sec.title] = not COLLAPSED[sec.title] or nil
        relayout(target)
    end)
    b:initialise()
    b.prerender = function() end
    b.render = function(o)
        local hot = o:isMouseOver() or o.joypadFocused
        o:drawRect(0, 0, o.width, 1, 1.0, 0.5, 0.5, 0.5)
        local open = S.hits or not COLLAPSED[sec.title]
        local c = hot and 1 or 0.85
        local markW = getTextManager():MeasureStringX(UIFont.Medium, "+ ")
        o:drawText(open and "-" or "+", 2, spacing, c, c, c, 1, UIFont.Medium)
        local count = S.hits and (#sec.hitRows .. " of " .. #sec.rows) or (#sec.rows .. (#sec.rows == 1 and " setting" or " settings"))
        local countW = getTextManager():MeasureStringX(UIFont.Small, count)
        o:drawTextRight(count, o.width, spacing + math.floor((hM - hS) / 2), C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
        -- the title is cut with "..." before the count (WrapText does not cut a single line reliably)
        local room, title = math.max(20, o.width - markW - countW - 16), sec.title
        local tm = getTextManager()
        if tm:MeasureStringX(UIFont.Medium, title) > room then
            while #title > 1 and tm:MeasureStringX(UIFont.Medium, title .. "...") > room do
                title = string.sub(title, 1, #title - 1)
            end
            title = title .. "..."
        end
        o:drawText(title, markW + 2, spacing, c, c, c, 1, UIFont.Medium)
    end
    self.mainPanel:addChild(b)
    self.addY = self.addY + spacing * 2 + hM
    return b
end

-- The search box and, under it, "Collapse all" / "Expand all" and the match count.
local function addSearchRows(self, S, splitpoint, y, width)
    local style = MainOptions.style
    local BUTTON_HGT = style.buttonHeight
    local spacing = style.borderSpacing
    local label = ISLabel:new(splitpoint, y + self.addY, BUTTON_HGT, "Search settings", 1, 1, 1, 1, UIFont.Small)
    label:initialise()
    self.mainPanel:addChild(label)
    local entry = ISTextEntryBox:new("", splitpoint + 20, y + self.addY, width, BUTTON_HGT)
    entry:initialise()
    entry:instantiate()
    entry:setClearButton(true)
    entry.tooltip = "Type words from a setting's name, description or key, a resource (gpu, vram, game thread, "
        .. "load time...) or a Java class that reads it (FBORenderCell, IsoChunk, pzopt.FogPass...). Typos and "
        .. "partial words are fine; the best matches come first."
    -- the text is polled each frame (the clear button and pasting do not all go through onTextChange) and searched
    -- once it has been still for 120 ms, so typing a word runs one search, not one per letter
    entry.prerender = function(o)
        ISTextEntryBox.prerender(o)
        local text, now = o:getText(), getTimestampMs()
        if text ~= S.typed then
            S.typed, S.typedAt = text, now
        elseif text ~= S.lastText and now - S.typedAt >= 120 then
            S.lastText = text
            runSearch(S, text)
        end
    end
    self.mainPanel:addChild(entry)
    self.mainPanel:insertNewLineOfButtons(entry)
    self.addY = self.addY + BUTTON_HGT + spacing
    local x = splitpoint + 20
    local fold = ISButton:new(x, y + self.addY, 100, BUTTON_HGT, "Collapse all", S, function(target)
        for _, sec in ipairs(target.allSections) do COLLAPSED[sec.title] = true end
        relayout(target)
    end)
    fold:initialise()
    fold:setWidthToTitle()
    self.mainPanel:addChild(fold)
    local unfold = ISButton:new(x + fold:getWidth() + spacing, y + self.addY, 100, BUTTON_HGT, "Expand all", S, function(target)
        for _, sec in ipairs(target.allSections) do COLLAPSED[sec.title] = nil end
        relayout(target)
    end)
    unfold:initialise()
    unfold:setWidthToTitle()
    self.mainPanel:addChild(unfold)
    local status = ISLabel:new(unfold:getX() + unfold:getWidth() + spacing * 2, y + self.addY, BUTTON_HGT, "", C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small, true)
    status:initialise()
    self.mainPanel:addChild(status)
    self.mainPanel:insertNewLineOfButtons(fold, unfold)
    self.addY = self.addY + BUTTON_HGT + spacing
    -- "Sort by": the topic order, alphabetical, or one resource (the settings that lower its load first)
    local keys, names = { "natural", "alpha" }, { "Natural (grouped by topic)", "Alphabetical" }
    for _, axis in ipairs(AXES) do
        table.insert(keys, axis.id)
        table.insert(names, "Effect on " .. axis.label)
    end
    local sortLabel = ISLabel:new(splitpoint, y + self.addY, BUTTON_HGT, "Sort by", 1, 1, 1, 1, UIFont.Small)
    sortLabel:initialise()
    self.mainPanel:addChild(sortLabel)
    local sort = ISComboBox:new(splitpoint + 20, y + self.addY, width, BUTTON_HGT, S, function(target, box)
        SORT[target.tab] = keys[box.selected] or "alpha"
        target.panel:setYScroll(0)
        relayout(target)
    end)
    sort:initialise()
    for i, name in ipairs(names) do
        sort:addOption(name)
        if keys[i] == SORT[S.tab] then sort.selected = i end
    end
    sort.tooltip = "Natural: the settings grouped by topic, in the order they were added. Alphabetical: the topics and "
        .. "the settings in each by name. Effect on a resource: every setting that lowers that part's load first, "
        .. "biggest change first (the bars in the preview), then the ones that raise it, then the rest. A search "
        .. "always lists the best matches first."
    self.mainPanel:addChild(sort)
    self.mainPanel:insertNewLineOfButtons(sort)
    self.addY = self.addY + BUTTON_HGT + spacing
    S.entry, S.status, S.sort = entry, status, sort
end

local function comboLabels(entry, default, saved)
    local labels = { "Default (" .. default .. ((entry.note and entry.note[default]) and (", " .. entry.note[default]) or "") .. ")" }
    local values = {}
    local seen = {}
    for _, v in ipairs(entry.choices) do
        local text = v
        if entry.note and entry.note[v] then
            text = v .. " (" .. entry.note[v] .. ")"
        end
        table.insert(labels, text)
        table.insert(values, v)
        seen[v] = true
    end
    -- a value typed into options.ini by hand that is not in the list stays selectable
    if saved ~= "" and not seen[saved] then
        table.insert(labels, saved)
        table.insert(values, saved)
    end
    return labels, values
end

local function addBoolOption(self, entry, splitpoint, y, BUTTON_HGT)
    local p = perf()
    local pinnedBy = p:getPzoptOptionPinnedBy(entry.key)
    local box = self:addYesNo(splitpoint, y, BUTTON_HGT, BUTTON_HGT, entry.label)
    box.tooltip = tooltipFor(entry, pinnedBy)
    if pinnedBy ~= "" then
        box.enable = false
    end
    local option = GameOption:new("pzopt." .. entry.key, box)
    function option.toUI(self)
        self.control:setSelected(1, nextValue(entry) == "true")
    end
    function option.apply(self)
        if pinnedBy ~= "" then return end
        local value = tostring(self.control:isSelected(1))
        store(entry, value)
        self:restartRequired(perf():getPzoptOption(entry.key), value)
    end
    -- the "Enable all" button puts the control back to the build's default
    function option.pzoptReset(self)
        if pinnedBy ~= "" then return end
        self.control:setSelected(1, perf():getPzoptOptionDefault(entry.key) == "true")
    end
    -- a profile button sets an explicit value (nil = the build's default)
    function option.pzoptSet(self, value)
        if pinnedBy ~= "" then return end
        if value == nil then return self:pzoptReset() end
        self.control:setSelected(1, value == "true")
    end
    -- what the control says right now (the preview panel's "next launch" value)
    function option.pzoptCurrent(self)
        return tostring(self.control:isSelected(1))
    end
    option.pzoptKey = entry.key
    self.gameOptions:add(option)
    return option
end

local function addIntOption(self, entry, splitpoint, y, comboWidth)
    local p = perf()
    local pinnedBy = p:getPzoptOptionPinnedBy(entry.key)
    local labels, values = comboLabels(entry, p:getPzoptOptionDefault(entry.key), p:getPzoptOptionSaved(entry.key))
    local combo = self:addCombo(splitpoint, y, comboWidth, 20, entry.label, labels, 1)
    combo:setToolTipMap({ defaultTooltip = tooltipFor(entry, pinnedBy) })
    if pinnedBy ~= "" then
        combo.disabled = true
    end
    local function indexOf(value)
        for i, v in ipairs(values) do
            if v == value then return i + 1 end
        end
        return nil
    end
    local option = GameOption:new("pzopt." .. entry.key, combo)
    function option.toUI(self)
        local pp = perf()
        local box = self.control
        if pinnedBy ~= "" then
            box.selected = indexOf(pp:getPzoptOption(entry.key)) or 1
        else
            local saved = pp:getPzoptOptionSaved(entry.key)
            box.selected = (saved ~= "" and indexOf(saved)) or 1
        end
    end
    function option.apply(self)
        if pinnedBy ~= "" then return end
        local box = self.control
        local value = box.selected > 1 and values[box.selected - 1] or ""
        perf():setPzoptOption(entry.key, value)
        local effective = value ~= "" and value or perf():getPzoptOptionDefault(entry.key)
        self:restartRequired(perf():getPzoptOption(entry.key), effective)
    end
    function option.pzoptReset(self)
        if pinnedBy ~= "" then return end
        self.control.selected = 1 -- "Default (...)"
    end
    function option.pzoptSet(self, value)
        if pinnedBy ~= "" then return end
        local index = value ~= nil and indexOf(value) or nil
        if index == nil then
            -- a profile value outside the combo's list becomes selectable, like a hand-typed one
            if value ~= nil and value ~= perf():getPzoptOptionDefault(entry.key) then
                table.insert(labels, value)
                table.insert(values, value)
                self.control:addOption(value)
                index = #values + 1
            else
                index = 1
            end
        end
        self.control.selected = index
    end
    function option.pzoptCurrent(self)
        local box = self.control
        if box.selected > 1 and values[box.selected - 1] then
            return values[box.selected - 1]
        end
        return perf():getPzoptOptionDefault(entry.key) .. " (default)"
    end
    option.pzoptKey = entry.key
    self.gameOptions:add(option)
    return option
end

-- The zoom curve (`bezier` entries, key zoomEase; 2026-09-22): the preset combo, then one slider per control-point
-- coordinate (x1, y1, x2, y2 as in CSS cubic-bezier(), each 0..1 so the zoom never overshoots its target) with a
-- plot of the curve in the label column beside them. A slider move selects the preset it matches, else "custom";
-- picking a preset moves the sliders. The option's value is always what the sliders say.
local BEZIER_AXES = { "Point 1 time (x1)", "Point 1 zoom (y1)", "Point 2 time (x2)", "Point 2 zoom (y2)" }

local function parseBezier(spec)
    local v = {}
    for part in string.gmatch(spec or "", "[^,; ]+") do
        table.insert(v, tonumber(part))
    end
    if #v ~= 4 then return nil end
    for i = 1, 4 do
        if v[i] == nil then return nil end
    end
    return v
end

local function sameBezier(a, b)
    if not a or not b then return false end
    for i = 1, 4 do
        if math.abs(a[i] - b[i]) > 0.005 then return false end
    end
    return true
end

local function formatBezier(v)
    local parts = {}
    for i = 1, 4 do parts[i] = string.format("%.2f", v[i]) end
    return table.concat(parts, ",")
end

-- The curve with its two handles, sampled like pzopt.ZoomEase: x(t) and y(t) share the parameter t.
PzoptBezierPlot = ISPanel:derive("PzoptBezierPlot")

function PzoptBezierPlot:render()
    local w, h = self.width, self.height
    self:drawRect(0, 0, w, h, 1, 0.06, 0.06, 0.07)
    self:drawLine(nil, 0, h, w, 0, 1, 1, 0.22, 0.22, 0.26) -- linear, for reference (drawLine2 draws nothing in B42 menus)
    local v = {}
    for i = 1, 4 do v[i] = self.sliders[i]:getCurrentValue() end
    local function px(x) return x * (w - 1) end
    local function py(y) return (1 - y) * (h - 1) end
    self:drawLine(nil, px(0), py(0), px(v[1]), py(v[2]), 1, 1, C_GREY.r, C_GREY.g, C_GREY.b)
    self:drawLine(nil, px(1), py(1), px(v[3]), py(v[4]), 1, 1, C_GREY.r, C_GREY.g, C_GREY.b)
    local function b(t, a, c)
        local u = 1 - t
        return 3 * u * u * t * a + 3 * u * t * t * c + t * t * t
    end
    local lx, ly = px(0), py(0)
    for s = 1, 32 do
        local t = s / 32
        local x, y = px(b(t, v[1], v[3])), py(b(t, v[2], v[4]))
        self:drawLine(nil, lx, ly, x, y, 2, 1, C_OPT.r, C_OPT.g, C_OPT.b)
        lx, ly = x, y
    end
    self:drawRect(px(v[1]) - 2, py(v[2]) - 2, 5, 5, 1, C_TEXT.r, C_TEXT.g, C_TEXT.b)
    self:drawRect(px(v[3]) - 2, py(v[4]) - 2, 5, 5, 1, C_TEXT.r, C_TEXT.g, C_TEXT.b)
    self:drawRectBorder(0, 0, w, h, 1, 0.31, 0.31, 0.35)
end

local function addBezierOption(self, entry, splitpoint, y, comboWidth, BUTTON_HGT)
    local p = perf()
    local pinnedBy = p:getPzoptOptionPinnedBy(entry.key)
    local default = p:getPzoptOptionDefault(entry.key)
    local labels, values = comboLabels(entry, default, "")
    table.insert(labels, "custom (the sliders below)")
    local customIndex = #labels
    local combo = self:addCombo(splitpoint, y, comboWidth, 20, entry.label, labels, 1)
    combo:setToolTipMap({ defaultTooltip = tooltipFor(entry, pinnedBy) })
    if pinnedBy ~= "" then
        combo.disabled = true
    end
    local spacing = MainOptions.style.borderSpacing
    local top = y + self.addY
    local valueW = getTextManager():MeasureStringX(UIFont.Small, "0.00") + 8
    local labelW = 0
    local sliders = {}
    for i, name in ipairs(BEZIER_AXES) do
        local rowY = y + self.addY
        local label = ISLabel:new(splitpoint, rowY, BUTTON_HGT, name, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
        label:initialise()
        self.mainPanel:addChild(label)
        labelW = math.max(labelW, getTextManager():MeasureStringX(UIFont.Small, name))
        local value = ISLabel:new(splitpoint + 20, rowY, BUTTON_HGT, "", 1, 1, 1, 1, UIFont.Small, true)
        value:initialise()
        self.mainPanel:addChild(value)
        local slider = ISSliderPanel:new(splitpoint + 20 + valueW, rowY, comboWidth - valueW, BUTTON_HGT)
        slider:initialise()
        slider:setValues(0, 1, 0.01, 0.1)
        slider.doToolTip = false -- its own tooltip is the radio's "increase step size"
        slider.valueLabel = value
        self.mainPanel:addChild(slider)
        self.mainPanel:insertNewLineOfButtons(slider)
        self.addY = self.addY + BUTTON_HGT + spacing
        sliders[i] = slider
    end
    local size = y + self.addY - spacing - top
    local plot = PzoptBezierPlot:new(math.max(16, splitpoint - labelW - 12 - size), top, size, size)
    plot:initialise()
    plot.sliders = sliders
    self.mainPanel:addChild(plot)

    local function current()
        local v = {}
        for i = 1, 4 do v[i] = sliders[i]:getCurrentValue() end
        return v
    end
    -- the sliders to a value; setCurrentValue ignores a disabled slider, so a pinned value is written directly
    local function setSliders(spec)
        local v = parseBezier(spec) or parseBezier(default)
        for i = 1, 4 do
            local s = sliders[i]
            s.currentValue = math.max(0, math.min(1, v[i]))
            s.valueLabel:setName(string.format("%.2f", s.currentValue))
            s.disabled = pinnedBy ~= ""
        end
    end
    -- the combo entry the sliders match: the default, a preset, or "custom"
    local function syncCombo()
        local v = current()
        if combo.selected == 1 and sameBezier(v, parseBezier(default)) then return end
        for i, value in ipairs(values) do
            if sameBezier(v, parseBezier(value)) then
                combo.selected = i + 1
                return
            end
        end
        combo.selected = customIndex
    end

    local option = GameOption:new("pzopt." .. entry.key, combo)
    for _, slider in ipairs(sliders) do
        slider.target = option
        slider.onValueChange = function(opt, value, s)
            s.valueLabel:setName(string.format("%.2f", value))
            syncCombo()
            opt:invokeOnChangeEvent()
        end
    end
    function option.onChange(self, box)
        if box.selected == 1 then
            setSliders(default)
        elseif values[box.selected - 1] then
            setSliders(values[box.selected - 1])
        end
    end
    function option.toUI(self)
        local pp = perf()
        local saved = pp:getPzoptOptionSaved(entry.key)
        if pinnedBy ~= "" then
            setSliders(pp:getPzoptOption(entry.key))
            syncCombo()
        elseif saved == "" then
            self.control.selected = 1
            setSliders(default)
        else
            setSliders(saved)
            self.control.selected = 0
            syncCombo()
        end
    end
    function option.apply(self)
        if pinnedBy ~= "" then return end
        local value = ""
        if self.control.selected ~= 1 then
            local v = current()
            value = sameBezier(v, parseBezier(default)) and "" or formatBezier(v)
        end
        perf():setPzoptOption(entry.key, value)
        local effective = value ~= "" and value or default
        self:restartRequired(perf():getPzoptOption(entry.key), effective)
    end
    function option.pzoptReset(self)
        if pinnedBy ~= "" then return end
        self.control.selected = 1
        setSliders(default)
    end
    function option.pzoptSet(self, value)
        if pinnedBy ~= "" then return end
        if value == nil then return self:pzoptReset() end
        setSliders(value)
        self.control.selected = 0
        syncCombo()
    end
    function option.pzoptCurrent(self)
        if self.control.selected == 1 then
            return default .. " (default)"
        end
        return formatBezier(current())
    end
    option.pzoptKey = entry.key
    option.pzoptJoyLines = { { combo }, { sliders[1] }, { sliders[2] }, { sliders[3] }, { sliders[4] } }
    self.gameOptions:add(option)
    return option
end

-- "Enable all": master on, every other control back to the build's default. "Disable all (stock)":
-- master off, the other controls untouched (they are ignored while the master is off). Neither writes
-- anything: the controls are marked changed and Apply / Accept saves them through the options above.
local function setAll(self, enable)
    local master = self.pzoptMaster
    if master and master.control.enable then
        master.control:setSelected(1, enable)
        master:invokeOnChangeEvent()
    end
    if enable then
        for _, option in ipairs(self.pzoptOptions) do
            option:pzoptReset()
            option:invokeOnChangeEvent()
        end
    end
end

-- Profiles: one button sets a named group of controls (the rest go back to the build's default),
-- master on; Apply / Accept saves them like the other buttons. Values are the option strings.
-- The low-end set (2026-09-21, docs/results.md): no chunk worker pool (its threads took the game thread's
-- core on four cores), trees baked into chunk textures only while walking, and on the Display page lighting
-- updates 10/s and the UI redrawn 30/s. The stock Display-page combos go by GameOption name -> combo index
-- (MainOptions.lua lists): lightingFPS {5, 10, 15, 20, 25, 30, 45, 60}, UIRenderFPS {120, 60, 30, 25, 20, 15, 10}.
local LOW_END_VALUES = { workers = "1", loadWorkers = "2", treeBakeMaxChunksPerSec = "24" }
-- Plus texture compression (2026-09-23, same laptop: with uncompressed textures the 4 GB card was full, 4034 MiB, the
-- driver spilled into system RAM and the machine swapped 34k pages in a 40 s walk; compressed, 2372 MiB and 1.2k swap-ins,
-- 51 -> 54 fps, frames over 50 ms 34 -> 22 a minute, worst frame 292 -> 120 ms). Tick boxes go by name -> true / false.
local LOW_END_STOCK = { lightingFPS = 2, UIRenderFPS = 3, texcompress = true }
local function withValues(base, extra)
    local t = {}
    for k, v in pairs(base) do t[k] = v end
    for k, v in pairs(extra) do t[k] = v end
    return t
end
local PROFILES = {
    {
        button = "Low-end hardware (4 cores or less)",
        tip = "Turns the master switch on and picks the settings measured on a 4-core CPU with an old GPU "
           .. "(Core i5-6300HQ / GTX 960M, 2026-09-21): no chunk worker pool (its threads took the game thread's core), "
           .. "trees baked only while walking (while driving a chunk texture lives seconds, and baking its trees cost "
           .. "more than drawing them per frame), and on the Display page lighting updates 10/s and the UI redrawn 30 "
           .. "times a second (the lighting thread and the Lua UI were the next biggest users of the four cores). "
           .. "Everything else goes back to the build's default. 120 km/h drive 44 -> 68 fps, walking 49 -> 81 "
           .. "(p99 80 -> 40 ms / 69 -> 30 ms). It also turns on texture compression (Display page), which kept a 4 GB "
           .. "graphics card from filling up and the machine from swapping (worst frame 292 -> 120 ms, 2026-09-23). The G1 "
           .. "collector these numbers need is now the default (gcMode). See docs/results.md.",
        values = LOW_END_VALUES,
        stock = LOW_END_STOCK,
    },
    {
        button = "Low-end hardware + FSR 1.0 upscaling",
        tip = "The Low-end hardware set above, plus the world rendered at 67 % of the screen per axis (44 % of the "
           .. "pixels) and scaled back up with AMD FidelityFX Super Resolution 1.0, which runs on any GPU; the "
           .. "interface, text and cursor stay at full resolution. For a machine whose GPU is the wall as well as "
           .. "its CPU: measured on the same Core i5-6300HQ / GTX 960M at 1920x1080 (2026-09-22, docs/results.md) "
           .. "the GPU-bound scenes gain the most. Everything else goes back to the build's default; G1 "
           .. "collector these numbers need is now the default (gcMode).",
        values = withValues(LOW_END_VALUES, { upscaler = "fsr1", upscalerQuality = "quality" }),
        stock = LOW_END_STOCK,
    },
}

local function applyProfile(self, profile)
    local master = self.pzoptMaster
    if master and master.control.enable then
        master.control:setSelected(1, true)
        master:invokeOnChangeEvent()
    end
    for _, option in ipairs(self.pzoptOptions) do
        option:pzoptSet(profile.values[option.pzoptKey])
        option:invokeOnChangeEvent()
    end
    for name, index in pairs(profile.stock or {}) do
        local option = self.gameOptions:get(name)
        local box = option and option.control
        if box and type(index) == "boolean" and box.setSelected then
            box:setSelected(1, index)
            option:invokeOnChangeEvent()
        elseif name == "texcompress" and not option then
            -- the game hides the tick box where the GPU cannot compress textures: nothing to set
        elseif box and box.options and box.options[index] then
            box.selected = index
            option:invokeOnChangeEvent()
        else
            print("[pzopt] options tab: profile could not set stock option " .. name)
        end
    end
end

local function addAllButtons(self, splitpoint, y)
    local on = self:addButton(splitpoint, y, "Enable all (recommended defaults)")
    on.tooltip = "Turns the master switch on and puts every setting below back to the build's default on this machine. " .. RESTART_NOTE
    on.target = self
    on.onclick = function(target) setAll(target, true) end
    local off = self:addButton(splitpoint, y, "Disable all (stock game)")
    off.tooltip = "Turns the master switch off: the game runs its original code everywhere, as if the overrides were not installed. The settings below are kept for when you enable them again. " .. RESTART_NOTE
    off.target = self
    off.onclick = function(target) setAll(target, false) end
    local profileButtons = {}
    for _, profile in ipairs(PROFILES) do
        local b = self:addButton(splitpoint, y, profile.button)
        b.tooltip = profile.tip .. " " .. RESTART_NOTE
        b.target = self
        b.onclick = function(target) applyProfile(target, profile) end
        table.insert(profileButtons, b)
    end
    if self.pzoptMaster and not self.pzoptMaster.control.enable then
        on:setEnable(false)
        off:setEnable(false)
        on.tooltip = "Pinned by " .. perf():getPzoptOptionPinnedBy(MASTER.key) .. " for this install."
        off.tooltip = on.tooltip
        for _, b in ipairs(profileButtons) do
            b:setEnable(false)
            b.tooltip = on.tooltip
        end
    end
end

-- The Profiler tab's one button: its own settings back to the build's defaults (the Optimizations tab's buttons
-- leave this tab alone).
local PROFILER_RESET = "Reset to defaults"
local function addProfilerButtons(self, splitpoint, y)
    local b = self:addButton(splitpoint, y, PROFILER_RESET)
    b.tooltip = "Puts every setting on this tab back to the build's default. " .. RESTART_NOTE
    b.target = self
    b.onclick = function(target)
        for _, option in ipairs(target.pzoptProfilerOptions or {}) do
            option:pzoptReset()
            option:invokeOnChangeEvent()
        end
    end
end

-- A section heading: a rule that stops short of the preview panel and the title above the label column.
local function addSectionLine(self, y, text, x0, width)
    local spacing = MainOptions.style.borderSpacing
    local hM = MainOptions.style:getFontHeight("Medium")
    local line = ISPanel:new(x0, self.addY + y, width, 2)
    line.prerender = function() end
    line.render = function(o) o:drawRect(0, 0, o.width, 1, 1.0, 0.5, 0.5, 0.5) end
    line:initialise()
    self.mainPanel:addChild(line)
    local shown = getTextManager():WrapText(UIFont.Medium, text, width, 1, "...")
    local label = ISLabel:new(x0, self.addY + y + spacing, hM, shown, 1, 1, 1, 1, UIFont.Medium, true)
    label:initialise()
    self.mainPanel:addChild(label)
    self.addY = self.addY + spacing * 2 + hM
end

-- Layout: the label column (right-aligned labels) and the controls at the left margin, the fixed preview panel
-- filling the rest of the page's width and height. Measured over both tabs, so the preview does not move between them.
local function layout(self, comboWidth)
    local W = self:getWidth()
    local gap, margin, sbar = 40, 16, 13
    local labelW = getTextManager():MeasureStringX(UIFont.Small, MASTER.label)
    for _, sections in ipairs({ SECTIONS, PROFILER_SECTIONS }) do
        for _, section in ipairs(sections) do
            for _, entry in ipairs(section.entries) do
                labelW = math.max(labelW, getTextManager():MeasureStringX(UIFont.Small, entry.label))
            end
        end
    end
    labelW = labelW + 8
    local controlW = comboWidth
    for _, title in ipairs({ "Enable all (recommended defaults)", "Disable all (stock game)", PROFILER_RESET }) do
        controlW = math.max(controlW, getTextManager():MeasureStringX(UIFont.Small, title) + 24)
    end
    for _, profile in ipairs(PROFILES) do
        controlW = math.max(controlW, getTextManager():MeasureStringX(UIFont.Small, profile.button) + 24)
    end
    local controlsW = labelW + 20 + controlW
    -- the controls at the left margin, the preview everything to the right of them: a fixed box that never
    -- moves or resizes, however long the hovered row's description is
    local previewW = math.max(360, W - 2 * margin - sbar - controlsW - gap)
    local x0 = margin
    return { x0 = x0, splitpoint = x0 + labelW, previewX = x0 + controlsW + gap, previewW = previewW,
             lineW = controlsW + gap / 2, margin = margin, controlW = controlW }
end

-- The two pages: the Optimizations tab (master switch, profiles) and the Profiler tab (the overlay's settings).
-- `panel` / `options` / `search` / `preview` name the MainOptions fields that hold the page's parts.
local PAGES = {
    {
        tab = TAB, sections = SECTIONS, master = MASTER, buttons = addAllButtons,
        panel = "pzoptPanel", options = "pzoptOptions", search = "pzoptSearch", preview = "pzoptPreview",
        headline = function(p)
            return "All optimizations (since this boot: " .. (p:isPzoptEnabled() and "on" or "OFF: the game is running stock") .. ")"
        end,
    },
    {
        tab = PROFILER_TAB, sections = PROFILER_SECTIONS, buttons = addProfilerButtons,
        panel = "pzoptProfilerPanel", options = "pzoptProfilerOptions", search = "pzoptProfilerSearch",
        preview = "pzoptProfilerPreview",
        headline = function(p)
            if p:isPzoptEnabled() then return "Performance overlay and game-thread profiler" end
            return "Performance overlay: unavailable (the optimizations are off since this boot)"
        end,
    },
}

-- The pages are added with the others (so the tabs sit after Display) but their controls are built the first
-- time each is shown: the in-game menu builds the whole options screen while the world is entered, and the
-- Optimizations tab was 101 of that screen's 124 ms on the flip (flip-opttime), on every Continue.
function MainOptions:pzoptAddOptimizationsPanel()
    self.pzoptBuilt = {}
    for _, page in ipairs(PAGES) do
        self:addPage(page.tab)
        self[page.panel] = self.mainPanel
    end
end

local function buildSettingsPage(self, page)
    local pzoptT0 = getTimestampMs()
    local savedPanel, savedAddY = self.mainPanel, self.addY
    local firstOption = #self.gameOptions.options + 1
    local wasChanged = self.gameOptions.changed
    local style = MainOptions.style
    local BUTTON_HGT = style.buttonHeight
    local y = style.initialY
    self.addY = 0
    local comboWidth = 45 * (getCore():getOptionFontSizeReal() + 1) + 60
    local L = layout(self, comboWidth)
    local splitpoint = L.splitpoint

    self.mainPanel = self[page.panel]
    local panel = self.mainPanel
    local p = perf()
    local added, pinned = 0, 0
    local options = {}
    self[page.options] = options
    local rows = {}
    local function addRow(entry, option, clip)
        table.insert(rows, { entry = entry, option = option, clip = clip,
                             y = option.control:getY(), h = math.max(option.control:getHeight(), BUTTON_HGT) })
    end
    addSectionLine(self, y, page.headline(p), L.x0, L.lineW)
    if page.master then
        self.pzoptMaster = nil
        if p:isPzoptOptionKnown(page.master.key) then
            self.pzoptMaster = addBoolOption(self, page.master, splitpoint, y, BUTTON_HGT)
            addRow(page.master, self.pzoptMaster, "drive")
            if p:getPzoptOptionPinnedBy(page.master.key) ~= "" then pinned = pinned + 1 end
        end
    end
    page.buttons(self, splitpoint, y)
    -- Everything below the search rows is placed by relayout: each row records the elements the stock add*
    -- helpers create (caught by wrapping the page's addChild) and their offsets from the row's top.
    local S = { panel = panel, tab = page.tab, sections = {}, total = 0 }
    self[page.search] = S
    addSearchRows(self, S, splitpoint, y, math.max(comboWidth, L.controlW))
    S.top = y + self.addY
    S.joyTop = #panel.joypadButtonsY
    local sink
    panel.addChild = function(o, child)
        if sink then table.insert(sink, child) end
        return ISPanelJoypad.addChild(o, child)
    end
    local function capture(fn)
        local top = y + self.addY
        sink = {}
        local result = fn()
        local row = { elems = {}, step = y + self.addY - top }
        for _, el in ipairs(sink) do table.insert(row.elems, { el = el, dy = el:getY() - top }) end
        sink = nil
        return row, result, top
    end
    local sectionOf = {}
    local managed = {}
    for si, section in ipairs(page.sections) do
        local sec = { title = section.title, index = si, rows = {}, hitRows = {}, best = 0 }
        local header, button = capture(function() return addSectionHeader(self, S, sec, y, L.x0, L.lineW) end)
        header.button = button
        sec.header = header
        for _, entry in ipairs(section.entries) do
            if p:isPzoptOptionKnown(entry.key) then
                local row, option, top = capture(function()
                    if entry.bezier then
                        return addBezierOption(self, entry, splitpoint, y, comboWidth, BUTTON_HGT)
                    end
                    if entry.choices then
                        return addIntOption(self, entry, splitpoint, y, comboWidth)
                    end
                    return addBoolOption(self, entry, splitpoint, y, BUTTON_HGT)
                end)
                table.insert(options, option)
                addRow(entry, option, KEY_CLIP[entry.key] or section.clip or "drive")
                local r = rows[#rows]
                r.elems, r.step, r.controlDy, r.index = row.elems, row.step, option.control:getY() - top, #managed + 1
                -- a multi-line control (the curve sliders) picks its preview row over its whole height
                if option.pzoptJoyLines then r.h = r.step - r.controlDy end
                table.insert(sec.rows, r)
                table.insert(managed, r)
                sectionOf[r] = sec
                added = added + 1
                if p:getPzoptOptionPinnedBy(entry.key) ~= "" then pinned = pinned + 1 end
            else
                print("[pzopt] options tab: unknown key " .. entry.key .. ", skipped")
            end
        end
        if #sec.rows > 0 then table.insert(S.sections, sec) else hideRow(header) end
    end
    -- the three headings of a resource sort (titles and rows set by sortedGroups)
    S.virtual = {}
    for i = 1, 3 do
        local vsec = { title = "", index = #page.sections + i, rows = {}, hitRows = {}, best = 0 }
        local header, button = capture(function() return addSectionHeader(self, S, vsec, y, L.x0, L.lineW) end)
        header.button = button
        vsec.header = header
        hideRow(header)
        table.insert(S.virtual, vsec)
    end
    S.allSections = {}
    for _, sec in ipairs(S.sections) do table.insert(S.allSections, sec) end
    for _, sec in ipairs(S.virtual) do table.insert(S.allSections, sec) end
    -- alphabetical: the sections by title, the settings in each by label
    S.alphaGroups = {}
    for _, sec in ipairs(S.sections) do
        local sorted = {}
        for _, row in ipairs(sec.rows) do table.insert(sorted, row) end
        table.sort(sorted, function(a, b) return alphaLess(a.entry.label, b.entry.label) end)
        table.insert(S.alphaGroups, { sec = sec, rows = sorted })
    end
    table.sort(S.alphaGroups, function(a, b) return alphaLess(a.sec.title, b.sec.title) end)
    S.footer = capture(function()
        addSectionLine(self, y, "Changes take effect on the next launch. File: Zomboid/pzopt/options.ini", L.x0, L.lineW)
    end)
    panel.addChild = nil -- back to the class method
    S.total, S.managed = #managed, managed
    S.index = buildIndex(managed, sectionOf)
    S.lastText, S.typed, S.typedAt = "", "", 0
    runSearch(S, "")
    -- the page's prerender runs before its children draw: cull the rows for this frame's scroll first
    local pagePrerender = panel.prerender
    panel.prerender = function(o, ...)
        cullRows(S)
        return pagePrerender(o, ...)
    end
    -- The preview panel: a child of the page that does not scroll with it, full page height, the master
    -- switch shown until the mouse points at another row.
    local preview = PzoptPreview:new(L.previewX, L.margin, L.previewW, panel:getHeight() - 2 * L.margin, panel, rows)
    preview:initialise()
    preview:instantiate()
    preview:setScrollWithParent(false)
    preview:setAnchorTop(true)
    preview:setAnchorBottom(true)
    panel:addChild(preview)
    if rows[1] then preview:select(rows[1]) end
    self[page.preview] = preview
    -- the screen's toUI ran before this tab existed: show the saved values and remember them as the current ones
    for i = firstOption, #self.gameOptions.options do
        local option = self.gameOptions.options[i]
        option:toUI()
        option:storeCurrentValue()
    end
    self.gameOptions.changed = wasChanged
    self.mainPanel, self.addY = savedPanel, savedAddY
    print("[pzopt] options tab " .. page.tab .. ": " .. added .. " controls, " .. pinned .. " pinned by pzopt.properties or -D, "
        .. #rows .. " preview rows, preview " .. L.previewW .. " px at x=" .. L.previewX
        .. ", built in " .. (getTimestampMs() - pzoptT0) .. " ms")
end

local function install()
    if not MainOptions or MainOptions.pzoptOptimizationsTab then return end
    local ok, has = pcall(function() return getPerformance():hasPzoptOptions() end)
    if not ok or not has then
        print("[pzopt] options tab: PerformanceSettings override not loaded or overrides disabled, tab not added")
        return
    end
    MainOptions.pzoptOptimizationsTab = true
    -- The tabs go right after Display (Optimizations, then Profiler): create() adds the pages in order, so hook
    -- the Display page.
    -- the whole options screen's build time, for the load trace (the in-game menu builds it while the world is entered)
    local stockCreate = MainOptions.create
    -- The full build: stock create (timed for the load trace), then the Optimizations tab's lazy hook.
    local function fullCreate(self, ...)
        self.pzoptCreatePending = false
        self.pzoptCreated = true
        local t0 = getTimestampMs()
        local r = stockCreate(self, ...)
        print("[pzopt] options screen: MainOptions:create took " .. (getTimestampMs() - t0) .. " ms")
        -- build the Optimizations / Profiler tab when it is first shown (pzoptAddOptimizationsPanel added them empty)
        local tabs = self.tabs
        if tabs and self.pzoptPanel then
            local stockOnActivate = tabs.onActivateView
            tabs.onActivateView = function(target, tabPanel)
                for _, page in ipairs(PAGES) do
                    local pagePanel = target and target[page.panel]
                    if pagePanel and not target.pzoptBuilt[page.tab] and tabPanel:getActiveView() == pagePanel then
                        target.pzoptBuilt[page.tab] = true
                        local ok, err = pcall(buildSettingsPage, target, page)
                        if not ok then
                            print("[pzopt] options tab " .. page.tab .. ": build failed: " .. tostring(err))
                        end
                    end
                end
                if stockOnActivate then
                    return stockOnActivate(target, tabPanel)
                end
            end
        end
        return r
    end
    -- lazyOptionsScreen: the main menu and the in-game menu build the whole options screen while they are built
    -- (boot, every world entry, exit to menu), ~130 ms of vanilla panels on the flip on top of our tab. The hidden
    -- screen now only does the one part of create() the game needs without it, the key bindings (loadKeys, and the
    -- keysB42.ini rewrite stock does after a key-file version change), and builds the rest the first time it is
    -- used: toUI (called before the screen is shown) or setVisible(true). Only while our wrapper is still the
    -- installed MainOptions.create: a mod that wrapped create after us runs the stock build as before.
    local lazy = true
    pcall(function() lazy = getPerformance():getPzoptOption("lazyOptionsScreen") ~= "false" end)
    local ourCreate
    ourCreate = function(self, ...)
        if lazy and MainOptions.create == ourCreate and not self.pzoptCreated and not self:getIsVisible() then
            local reload = MainOptions.loadKeys()
            if reload then
                local fileOutput = getFileWriter("keysB42.ini", true, false)
                fileOutput:write("VERSION=" .. tostring(MainOptions.KEYS_VERSION) .. "\r\n")
                for _, v in ipairs(MainOptions.keyText) do
                    if not v.isModBind then
                        MainOptions.writeKey(v, fileOutput)
                    end
                end
                fileOutput:close()
            end
            self.pzoptCreatePending = true
            print("[pzopt] options screen: build deferred until it is opened (key bindings loaded)")
            return
        end
        return fullCreate(self, ...)
    end
    MainOptions.create = ourCreate
    local function ensureCreated(self)
        if self.pzoptCreatePending then
            fullCreate(self)
        end
    end
    local stockToUI = MainOptions.toUI
    function MainOptions:toUI(...)
        ensureCreated(self)
        return stockToUI(self, ...)
    end
    local stockOnResolutionChange = MainOptions.onResolutionChange
    function MainOptions:onResolutionChange(...)
        if self.pzoptCreatePending then
            return -- nothing built yet; the build later uses the size in force then
        end
        return stockOnResolutionChange(self, ...)
    end
    -- The parent's doLayout reaches the hidden screen too (ISUIElement.setVisible(true) lays out every child, e.g.
    -- MainScreen on Esc in game): stock centerKeybindings reads keyButtonWidth, which only the build sets, and its
    -- error aborted the pause menu's layout (pause menu without its buttons). The build lays itself out.
    local stockDoLayout = MainOptions.doLayout
    function MainOptions:doLayout(...)
        if self.pzoptCreatePending then
            return
        end
        return stockDoLayout(self, ...)
    end
    local stockAddDisplayPanel = MainOptions.addDisplayPanel
    function MainOptions:addDisplayPanel()
        stockAddDisplayPanel(self)
        local okPanel, err = pcall(MainOptions.pzoptAddOptimizationsPanel, self)
        if not okPanel then
            print("[pzopt] options tab: failed: " .. tostring(err))
        end
    end
    -- Closing the options screen (Back / Accept hide it) frees the preview clips' textures; the next
    -- visit decodes them again.
    local stockSetVisible = MainOptions.setVisible
    function MainOptions:setVisible(bVisible, ...)
        if bVisible then
            local wasPending = self.pzoptCreatePending
            ensureCreated(self)
            if wasPending then
                stockToUI(self) -- the values the screen shows, as toUI would have set them
            end
        end
        stockSetVisible(self, bVisible, ...)
        if not bVisible then
            pcall(function() getPerformance():releasePzoptGifs() end)
        end
    end
end

install()
Events.OnGameBoot.Add(install)
