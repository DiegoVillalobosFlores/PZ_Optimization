# harness/

Hands-off game runs and their analysis. Everything here is driven by `run.sh`; the Java side
is `src/pzopt/pzopt/Harness.java` (route driving, zoom, schedule) and `Stats.java` (frame and
chunk logs). Runs land in `harness/runs/<label>-<timestamp>/` (gitignored); reference numbers
in `harness/baseline/` (committed).

## run.sh

```
harness/run.sh --label <name> [--mode verify|bench|drive|parity|play] [--flag k=v]... [--prop k=v]...
               [--route-seconds N] [--source-save Mode/Name] [--renderer nvidia|zink]
               [--launcher auto|steam|direct] [--env K=V]... [--option key=value]...
               [--mod ID]... [--vmarg ARG]...
               [--mangohud secs] [--mangohud-config path] [--record] [--record-audio desktop|game] [--no-dashboard] [--no-mangohud]
               [--jfr] [--jfr-period ms] [--jfr-setting event#setting=value] [--game-profiler]
               [--gc g1|zgc] [--lead secs] [--quit-after secs] [--retries N] [--refresh-template]
```

What it does: installs the pzopt-harness Lua mod, creates/points at the bench save
`Saves/Sandbox/pzopt-bench`, writes the flag file the mod reads, launches the game, starts
`sysmon.sh` and the MangoHud log, waits for exit, collects console.txt and every `pzopt-*.out`
into the run dir, restores `latestSave.ini`. `run.opts` records launcher, renderer, flags.

Modes:
- `play` (2026-09-20 night): a copy of a real save for the maintainer to play in with the scene flags
  applied — `--mode play --source-save Apocalypse/<save> --flag weather=storm` (also `time_of_day=`,
  `torch=`, `thunder_secs=`): click-to-start is pressed, `pzopt.Scene` is applied at world-ready and
  kept pinned every frame, the player keeps their save's state (no god mode, no ghost), no route, no
  quit; run.sh returns when the game is quit from its menu, `latestSave.ini` restored. Pass
  `--retries 0`. The real save is never written (the template copy is `pzopt-template-<save>`, the
  played copy `<Mode>/pzopt-bench`).
- `verify`: nobody presses "Click to Start"; the game sits at the loading screen. Only for
  install smoke checks. Do not use it for HUD, Wayland or input tests.
- `bench`: fixed camera route on the bench save, ~100 s. `--flag turn=90` spins the player facing
  (degrees per second) so the vision cone, lighting cone and cutaways keep changing; the game-thread
  route since 2026-09-20 is `--flag route=S:450 --flag turn=90 --route-seconds 25` (south through
  Rosewood from the bench save, 55 chunks/s), reference runs `gt-q-*`. **Always** `--flag zoom=max` (the
  save's zoom drifts to 1.0; zoom 1.0 is CPU-bound near the 240 cap, zoom 2.5 is the real
  test). Check `zoom=2.5` in `pzopt-bench.out` before comparing. Since 2026-09-22 the summary also carries
  `zombie_batches=` (the `anim batch:` / `action eval:` / `lighting batch:` / `frame batches` counters of
  `pzopt.FrameBatch`'s users at route end: batched / inline / max per frame / work and wait ms / shadow served vs
  fallback / dev-rig checks and mismatches) and `bake_counters=` (bakes, deferred, held and strong re-bakes,
  strong marks, `strongBudgetCuts` of `lightingStrongFrameMs`, since boot) — the periodic FBORenderCell log line
  never prints in a run shorter than 1,800 frames, these do.
- Presets (`--preset name`, 2026-09-20): a named bundle of scene flags on top of the spinning
  game-thread route (`route=S:450 turn=90 zoom=max`, 25 s; any later `--flag`/`--mode`/`--route-seconds`
  wins). `night-torch` = 01:00 with a lit Base.HandTorch in the primary hand (beam sweeps with the
  turn; it draws with the invisible bench player), `night-dark` = 01:00 no light item,
  `storm` = the save's hour with a pinned thunderstorm (rain/cloud 1.0, wind 0.9, dim ambient) and a
  lightning strike 60 tiles from the player every 6 s (`--flag thunder_secs=N`), `fog` = the save's hour
  with the weather period stopped and fog pinned at 1.0 (`fog=heavy`; `ImprovedFog` with `fogQuality`
  0/1 in options.ini, legacy fog circle with 2), `storm-fog` = storm + `fog=heavy` with the stock storm
  fog tint (the heaviest STAGE_STORM the game can roll; `--preset storm --flag fog=0.5` for a lighter one),
  `louisville` (2026-09-20 night) = the spin through downtown Louisville with the zombie population maxed:
  `start=12450,1280` (teleport at world-ready; `IsoChunkMap.ProcessChunkPos` reloads the grid around the new
  square), `population=max` (sandbox PopulationMultiplier / Start / Peak = 4 pushed to the native popman before
  the chunks load: ~2,000 zombies at the route start, ~2,500 by the end), `settle=20`, `route=S:150 speed=6`
  (same 25 s and turn; at 18 tiles/s the walk outran chunk handoff at the ~30 fps this scene runs at) and
  no `see_all` since 2026-09-23 (maintainer's call: the spectator view, `--flag see_all=true`, made the native
  lighting spread NaN into a re-bake flood in ~1 run in 3; without it the never-seen blocks stay black as in play,
  so runs before and after that date are not comparable). Runs `show-louisville-*`; stock 23.7 fps / p99 94 ms, optimized
  31.7 / 57, both game-thread bound at 98 %. The soft optimized side of that video was not a render change
  (2026-09-22, runs `lvroof-*` / `lvpin-*`): a zombie bump on the ghost player still rolls `helmetFall` (god mode
  only cancels the health loss), the short-sighted bench character lost their glasses and `screen.frag`'s
  `screenBlur` blurred everything outside a small circle around the player for the rest of the run; which side
  keeps its glasses is chance. The Harness now pins every worn item (`Scene.pinWornItems`, `chanceToFall=0`) at
  world-ready, wears any pinned item that still leaves its slot back every frame (`Scene.keepWornItems` in
  `Scene.tick`, with `updateVisionEffects()` so the blur target never flips; the first two pinned runs still ended
  with `eyes=none` and `blur=0.00`, so a second removal path exists) and logs `harness: N worn items pinned;
  eyes=... shortSighted=... blur=... wornRestored=...` at world-ready and `harness: player at route end: ...` at
  the end; a `blur` above 0 in a console means a soft capture, `wornRestored` counts the re-wears. Sharpness rig:
  Laplacian stdev of a full-res crop of `shot-game.png` (`magick ... -morphology Convolve Laplacian:0 -format
  %[fx:standard_deviation*1000]`): stock 22.9, blurred optimized 7.9, pinned optimized 27.1 / stock 24.5; AV1
  recordings are too noisy for it.
  Scene flags on their own: `start=X,Y`, `population=N|max`, `car_spawn=1..5` (sandbox CarSpawnRate forced before the teleport, so never-visited chunks spawn their parked cars with it; `car_spawn=` / `vehicles_loaded=` in pzopt-bench.out; 2026-09-23), `zombies=off` (population 0 + every loaded zombie removed each tick), `jitter=T` (with hold: player X flips across the end square's east edge by ±T tiles every frame), `see_all=true`, `time_of_day=H`, `weather=storm|clear`, `fog=heavy|off|0..1`, `torch=on|off`, `headlights=on|off|auto` (drive: the spawned car's headlights; auto = on when `time_of_day` is a night hour, 2026-09-21), `lightbar=0..3` (drive: the emergency lightbar lights mode of an ambulance / police car, 0 = off),
  Scene flags on their own: `start=X,Y`, `population=N|max`, `zombies=off` (population 0 + every loaded zombie removed each tick), `jitter=T` (with hold: player X flips across the end square's east edge by ±T tiles every frame), `see_all=true`, `time_of_day=H`, `weather=storm|clear`, `fog=heavy|off|0..1`, `torch=on|off`, `headlights=on|off|auto` (drive: the spawned car's headlights; auto = on when `time_of_day` is a night hour, 2026-09-21), `lightbar=0..3` (drive: the emergency lightbar lights mode of an ambulance / police car, 0 = off),
  `lua_wrap=<table>[,<table>]` (harness Lua mod, 2026-09-23: every function of those global Lua tables timed, calls over 2 ms logged as `[pzopt-harness] lua_wrap: T.f N ms`; splits a slow handler named by `--prop luaEventProfile=true`, runs `flip-luawrap`, `flip-menuwrap`; never the base UI classes ISUIElement / ISPanel / ISPanelJoypad / ISBaseObject, which the rig now refuses: wrapping them broke the main menu build),
  `pad=<script>` (2026-09-24, controller menu profiling: `pzopt.VirtualPad`, an in-game virtual Xbox pad, plays `harness/mod/pzopt-harness/pad/<script>.txt` (pad.py's commands, `menu-tour` walks the main menu, Options > Display, the Optimizations tab and the other tabs) 2 s after the main menu accepts input; the harness Lua stays on the menu, logs every pad handler (`[pzopt-pad]`: epoch ms, handler ms, focus) and menu frames of 25 ms or more, and quits when the script is done; needs `--option controller=030000005e0400008e02000010010000` and, for frame times, `--prop overlaySampling=true --prop overlayLog=true`; works on the Mac (no uinput), JFR there via `--vmarg -XX:StartFlightRecording=filename=<home>/Zomboid/pzopt-jfr.out,settings=profile,dumponexit=true`; `run.sh --pad <file>` is the same with the uinput pad.py; `harness/padlat.py <run>` lines presses, handler calls and frames up per script section),
  `lua_prof=<table>[,<table>]` (2026-09-24: the render / prerender / update / draw* / onJoypad* methods of those Lua tables summed per 2 s, `[pzopt-luaprof] t= frames= T.m <ms per frame> <calls per frame>`; base UI classes allowed here, it only accumulates),
  `menu_check=1` (harness Lua mod, 2026-09-23: at the main menu, before Continue, shows and hides the server settings, sandbox, character creation, multiplayer, credits and spawn screens and logs `[pzopt-harness] menu check: <screen> ... ok=`; a lazy build of those screens was tried and dropped the same night: main menu 378 -> 348 ms only, the screens share first-time UI costs),
  `pause_menu=<s> [pause_menu_secs=5] [pause_menu_cap=<fps>]` (harness Lua mod, 2026-09-23: `s` seconds into the world opens the
  pause menu through `ToggleEscapeMenu`, closes it `pause_menu_secs` later; `pause_menu_cap` sets the Menu framerate combo for the
  rig and restores the player's choice; read the console's `frame cap: menu phase ... fps (cap ...)` line),
  `options_check=<s>` (harness Lua mod, 2026-09-23: `s` seconds into the world activates the in-game options screen's Optimizations tab without showing it and logs `[pzopt-harness] options check: built before/after, controls, changed`; the lazy tab build's rig, run `flip-lazytab`),
  `options_tab=<tab name>` (harness Lua mod, 2026-09-24: stays on the main menu, opens Options on that tab, logs
  `[pzopt-harness] options: tabs ...`, writes `Screenshots/pzopt-options.png` 3 s later and quits; run-mac.sh copies
  `Screenshots/pzopt-*.png` into the run dir; menu checks without xdotool, e.g. `--machine mac -- --label x --mode verify
  --flag options_tab=Profiler`; `options_search=<text>` types into the Optimizations tab's search box 1 s after opening,
  so a section far down the page is on the screenshot, e.g. `options_search=HDR`),
  `lure=<animal>` (harness Lua mod, 2026-09-22: `lure_at` s after the player exists spawns the animal 8 tiles away, puts a Base.Carrots in the primary hand and queues the stock `ISLureAnimal`; `[pzopt-harness] lure: dist= lured=` every 2 s; the CanSee repro, runs `cansee-*`: `--mode bench --flag route=S:1 --flag speed=1 --flag hold=30 --flag zoom=0.5 --flag lure=cow --flag lure_at=8 --route-seconds 1 --record`),
  `thump=N` (`pzopt.ThumpRig`, 2026-09-22 night: `thump_at` s (3) after the route start, N zombies on the outside square of the locked exterior door nearest the player (on screen), door health `thump_hp` (500), thump target re-set when cleared; `thump_leave` s (8) after the spawn the player teleports `thump_dist` (35) tiles east of the door (the zombies, drawn once, are then scene-culled: SIXTEENTH sim level; never-drawn zombies stay FULL at high fps), `thump_reveal` s after the spawn back to the start; `harness: thump t= strikes= last_s= door_hp= sim=` every second, `thump=` in pzopt-bench.out; the off-screen thump-burst repro, before = `--prop devActionEvalUnitMultiplier=true`, runs `thump-*`),
  `helicopter=true` (= `--preset helicopter`: the stock chopper event started at the route start and placed next to the player, its 500-radius world sound every few seconds from its moving position; state line every 5 s, `helicopter=` in pzopt-bench.out; 2026-09-22 runs `heli-*`),
  `sound=R` (a stock 600-radius-style world sound every `sound_every` frames, `sound_fixed=true` from one still square like a house alarm, `sound_parts=true` times the fish walk and the popman scan too; `addSound` timed on the game thread, `sound_stats=` in pzopt-bench.out; 2026-09-22 runs `sound-*`),
  `house_alarm=D` / `car_alarm=D` / `sound_probe=true` (`pzopt.SoundProbe`, 2026-09-24: at the route start a stock `zombie.iso.Alarm` on the building square nearest D (12) tiles east of the player (the `event:/Meta/HouseAlarm` FMOD event ~49 s + a 600-radius world sound every frame) and a `car_alarm_script` (Base.ModernCar) on the road nearest D (8) tiles west, battery full, `setAlarmed` + `triggerAlarm` (its looping alarm through `VehicleSounds`, 150-radius world sound); with either alarm a census every second, settle included, into `pzopt-sound.out`: frame ms, zombies, FMOD emitters `SoundManager` holds by type (vocals / footsteps / extra / other: emitters / holding a sound / instances), instances stopping, most common sound names, zombie vocal slots, live world sounds, both alarms' FMOD state; at the end every native thread's CPU over the route from `/proc/self/task/*/schedstat` (FMOD's mixer / Studio / stream threads are not Java threads) and `sound_probe=` in pzopt-bench.out. `gunshots=R [gunshot_weapon=Base.Pistol]` fires R shots a second from the route start through stock's two calls (`playRangedWeaponShootSound` + the weapon's world sound, radius x FirearmNoiseMultiplier, halved indoors; the ghost player's `addWorldSoundUnlessInvisible` would drop it); `sound_probe_hz=N` samples the census N times a second; the census also records FMOD's virtual instances (`FMOD_Studio_EventInstance_IsVirtual` via FFM), the core `fmod_channels=playing/real`, the WorldAmbiance (rain / wind bed) state, every FMOD global parameter, the master limiter's input / output peak, and in its header FMOD's mix format and PipeWire's view of the game stream (`pactl`). The audio rig: `--preset louisville --flag weather=storm --flag house_alarm=12 --flag car_alarm=8 --flag gunshots=2 --flag helicopter=true --flag sound_probe_hz=4 --flag route=S:25 --flag speed=1 --route-seconds 25 --record --record-audio game --asprof event=cpu,interval=5ms,threads --prop uncappedFps=true` (bench `sound-storm-horde`; stress `--flag gunshots=8 --flag gunshot_weapon=Base.AssaultRifle`), then `soundprof.py`, `audio.py`, `audio-judge.py`; runs `snd-*`, `docs/findings-sound-2026-09-24.md`),
  `exit_fade_ms=N` (2026-09-24: at the route end the harness ramps FMOD's master bus to silence over N ms before it quits; default 1500 in a sound run (SoundProbe active), else 0. The quit itself stops every sound at once (`IngameState.exit` -> `SoundManager.stop`), which cut a rolling thunder in every recorded sound run; note that it moves the quit 1.5 s after the route end, which the quit-aligned stitch scripts assume is immediate),
  `visible=true` (`pzopt.Scene`; applied at world-ready, re-pinned every frame, recorded in `pzopt-bench.out`
  as `time_of_day/game_hour/weather/fog/torch/visible/night_strength/precipitation/fog_intensity/fog_fx/
  fog_quality/lightning_strikes/population/zombies_loaded/see_all`, plus `start=` = the route's first square; `torch check` console line every 5 s; the sandbox `MaxFogIntensity` cap
  and `FogCycle` are logged at apply time, a cap other than 1 is a warning).
  Compare a preset only with runs of the same preset. 2026-09-20 numbers (`docs/archive/2026-09-24/results.md`): night
  283 fps = daylight, torch on or off (the beam costs nothing measurable); storm 83 fps, p99 43 ms
  (chunk lighting rebakes ×5). The `--shot-at` captures never show the beam (player held still 2 s
  before the capture); judge lights live or from `--record`, not from the shots.
- `drive`: spawns a car and drives it. **Every drive bench is a path drive since 2026-09-24** (below): `drive-120` /
  `drive-60` = `--flag path=8010,11204.5/9210,11204.5` (KY-60 east, 1,200 tiles from 8010) at `kmh=120` / `kmh=60`, `load` = 60 tiles of
  it, `showcase-record.sh` drive120 / fog120 / storm120 = the 120 km/h one. Runs before that date used `route=E:1200`
  from 8002 with `kmh=193` (flat out, the car caps at ~122) and never stopped at the end: not comparable frame for frame.
  Without `path=` the old follower still drives `--flag route=E:N` (`roadFollow`, tile scans; at 120 km/h it left the
  road about every other run); the multiplayer drive (`harness/mp/run.sh`) keeps it, the path pilot does not handle a
  server-owned car. `--flag vehicle=none` requires a fixture instead of spawning.
  **Path drives** (2026-09-24, `--flag path=x,y/x,y/...`): the car starts on the first point facing the second
  (the player is teleported there first when it is far / not loaded) and `pzopt.DrivePilot` drives the centreline
  instead of `roadFollow`: pure pursuit with analogue steering through `clientControls.steering` (the input picked
  from CarController's own steering law each frame), corners rounded to `corner_radius` (10) and taken at the speed
  `lat_accel` (5 tiles/s²) allows, braking planned at `decel` (6) for corners, a stop at the last point and before a
  hard obstacle nothing can pass; other vehicles / solid squares on the street are passed inside the paved band,
  zombies when there is room (`avoid_zombies`). Tuning flags in `Harness.startPilot()`. The route is complete when
  the car stops on the last point (`route=` is ignored). Output: `pzopt-drive.out` (20 Hz: s, cross-track error,
  speed, plan, input, brake, offset, obstacles, chunk stalls), `drive_*` lines in `pzopt-bench.out`, the 1 Hz
  console `harness: drive t=` line with `s= target= hdg_err= offset= obstacles= brake= chunk_ahead= gain= impacts=`.
  `drive_check.py <run> [--against <run>...]` = the drive's card (limits in code: completed, cross-track, off street,
  impacts, unplanned speed dips (the speed falling 10 km/h in 0.5 s unasked; accelerating out of a corner is not one), obstacle stops, held back under the plan without braking or gaining; line / speed profile vs reference drives) and Jev's
  verdict valid / left_the_line / crashed / stalled / slow; `judge.py` (so every queue result) runs it on any run
  with `pzopt-drive.out` and a failed drive makes the run `invalid`. `drive-path.py` builds paths from the map's
  `streets.xml` (`route --from X,Y --via "KY-60>North Main St>Jacks Lane"`, or `plan --from X,Y --describe "..."`:
  every street-graph route from there, Jev picks the one the words describe). Bench `drive-120-south` (Rosewood:
  KY-60 east 96 tiles from 8010,11204.5, 8 tiles past the bench save's own car at 8002,11204 (a start on it pins both cars: the harness now rejects that), North Main St + Jacks Lane south 761, stop 12 tiles before Angel Road, 120 km/h).
  Town loop (runs `rwloop-1/2`, 2026-09-24, 1,191 tiles, 6 turns, ~75 s): `--flag path=8010,11204.5/8106,11204.5/8106,11579/8265,11579/8265,11382/8317.5,11382/8326.5,11379/8334,11371.5/8337,11365.5/8337,11204.5/8200,11204.5 --flag corner_radius=6 --flag kmh=120 --flag max_seconds=150 --route-seconds 90` (KY-60 > North Main St > Frederick Lane > Butterfly St > JC Carroll Road > KY-60 west; `drive-path.py route ... --to 8200,11204`); corner_radius 6 on 6-8 wide streets keeps the inner kerb clear. Longer paths need `max_seconds` above the default 90.
  `tests/pzopt/DrivePilotTest` drives the path in a simulated car (240 / 60 / 30 fps, steering gain x0.6 / x1.7,
  a parked car, a road block, a zombie).
- `parity`: captures recalc output per chunk; `parity-gate.sh` compares with
  `baseline/parity-stock.out`.

`--shot-at N` (bench): N s into the route the harness holds the camera for 6 s (no teleport, no
turn); at +2 s the game writes its own `Screenshots/pzopt-shot.png` and touches
`Zomboid/pzopt-shot.now`, on which run.sh takes a desktop capture (`spectacle -b -n -f`), and
again at +4 s (`shot2-*`). Collected as `<run>/shot-game.png`, `shot-desktop.png`,
`shot2-game.png`, `shot2-desktop.png`. `blacktiles.py <control.png> <run.png>...` counts pixels
black in a run but drawn in a control capture at the same hold point, and the 32 px tiles that
are entirely black; the control is a same-route run with the suspect key off (2026-09-20 bisect of
the black chunk squares, runs `bs-*`). Two captures 2 s apart tell a baked artifact (identical)
from a per-frame one.

`--resume-shot <dir>` (2026-09-24): the bench save is rebuilt from its template every run, so no run starts with a
resume shot (`pzopt.ResumeShot`) and Continue shows the plain black frame. The option copies `pzopt-resume.jpg`,
`.properties` and `-load.txt` from `<dir>` into the fresh bench save; every run collects the shot its own exit save wrote
into its run dir. Recipe: run A `--flag route=S:1 --flag speed=1 --route-seconds 1` (exit shot at the start square), then
run B the same `--record --resume-shot <A's run dir>`; runs `resume-capture-20260924-124201`, `resume-fake-20260924-124801`.
The live world's build-up for comparison: `--prop resumeShot=false --prop overlay=false --record` (run `worldload-rec2`).
Somewhere else than the save's own spot: run A `--flag start=X,Y --flag settle=10 --keep-save` (the exit save, player
position and shot included, hard-linked into `<run>/save`), run B `--resume-from <A's run dir>` (that save is the bench
save, so Continue loads at X,Y). Submit both as queue `run` jobs, B after A. Real-save copies are a poor source: a
player indoors, underground or at night leaves a mostly black shot (the capture is ground-level floors as last seen).

`--flag hold=N` (bench): after the last route leg the player stays on the end square N s before
the run ends; `turn` keeps spinning the facing, so the camera is still while cutaways, fades and
the obscuring set keep changing. The flicker rig (2026-09-20 evening, runs `flick-*`):
`--flag route=S:450 --flag speed=90 --flag turn=90 --flag hold=10 --flag zoom=1 --route-seconds 16
--record`, then `flicker.py <run>/recording.mp4 START END` over the hold (quit instant minus 12 to
minus 4 s) counts pixels that change and revert within 3 frames (a per-frame appear / disappear);
`--heat out.png` paints where. Stock reads 3.8 px/frame at `--scale 2560` (the spinning player
only) and 0.0 at `--scale 1280`; the broken build read 26 / 3.4. Compare only same-scale numbers.

Flags that must be on every measured run: `--prop instrument=true` (else no
`pzopt-chunks.out` / `pzopt-frames.out` and compare.py crashes), `--flag zoom=max` on bench,
`--no-dashboard` when measuring (the PZDashboard mod fires four collectors every 2.000 s, one
20-28 ms frame). Use the DEFAULT bench save for drive runs; `--source-save
Apocalypse/2026-09-18_12-18-03` starts off the highway and the car crashes.

HDR (2026-09-24): the game's `hdrAuto` (default on) turns HDR on by itself on an HDR screen, and this desktop's is one
(KDE HDR on, reference 505 / peak 1307 nits; the MacBook's XDR panel too). run.sh and run-mac.sh therefore write
`hdrAuto=false` into every run's pzopt.properties unless the run passes `--prop hdrAuto=...`; HDR runs pass
`--prop hdr=true` (forced) or `--prop hdrAuto=true` (the player's default path).

Ambient occlusion (2026-09-24, `pzopt.ChunkAo`): `--prop ambientOcclusion=true` (off by default). `--prop devAoView=1`
shows the AO term alone (chunk textures baked grey), with the Rosewood-house shot rig `--flag start=8147,11507 --flag
zoom=1 --flag route=S:30 --flag speed=1 --shot-at 3`; an on / off pair at the same shot point gives the AO as a brightness
ratio (seams show as lines along the chunk diamonds). `--prop devAoTiming=true` logs `chunk ao gpu us/frame=... | us/job:
...` every 200 jobs (timestamp queries; weight by frames over the route window, and multiply by fps for ms/s: a faster run
dilutes us/frame). `--prop devAoDumpFrame=N` writes the Nth computed texture's raw AO (screen mode: the scene depth and
colour) to `~/Zomboid/pzopt-*ao*` for offline work on the kernel.

## Run queue (harness/queue.sh, 2026-09-21)

The game dirs, displays and Steam clients of the desktop and the three laptops are shared resources;
`queue.sh` serialises every use of them per machine, routes each session's jobs to the machine that
session is bound to, and writes a result file per job, so sessions never pgrep, message peers or hand-roll
ssh wrappers. State is machine-global under `~/.local/state/pzopt-queue/` (`PZQ_DIR`); a job runs from the
checkout whose `queue.sh` submitted it (its `run.sh`, `build/classes`, `harness/runs/`).

```
# every submit (Jev orders the queue from it): --name "<ListAgents name>" (once per session) --intent "..." --progress "..."
# every run also: --resource <r1,r2> [--bench <name>|auto]   ·   harness/queue.sh suggest --intent "..." [--resource ...] | resources
harness/queue.sh submit run      [--machine desktop|flip|dell|mac] [--install opt|stock|keep|<repo>]
                                 [--goal "<what the change should do>"] [--against <run|baseline.json>]...
                                 [--parity-against <recorded run>] [--cap N] [--wait] -- <harness/run.sh args>
harness/queue.sh submit mp       [--goal ...] [--against ...] [--wait] -- <label> [stock]   # desktop only
harness/queue.sh submit workshop [--wait] --notes "<change notes>" -- --tag win-<rev>-<commit>   # desktop only
harness/queue.sh submit cmd      [--install ...] [--wait] --label <name> -- <showcase-record.sh ...>   # desktop only
harness/queue.sh submit media    [--out <file>]... [--wait] --label <name> -- <encode-av1-hdr.sh | stitch-*.sh | ffmpeg ...>   # desktop only
harness/queue.sh session [--name N] [--intent T] [--progress T]   # this session's context for Jev
harness/queue.sh list | machines | next [machine] | bind <machine> | unbind | watch [--exit-on disconnect|job|overrun|any] | events [N]
harness/queue.sh status | wait | result | log [-f] | cancel <id|label>   # a running laptop job: stops its game and run.sh there first (2026-09-23)
harness/queue.sh start [machine...] | stop [--now]   # monitor + workers (transient user units pzq-monitor, pzq-<m>)
```

**Machines** (`harness/queue/machines.conf`: desktop = local; flip, dell, mac = ssh host, key, checkout,
`PZ_ROOT`, `run_args`, display-env source, inhibit / steam_shutdown flags, installer, `runner=`). One worker
unit per machine runs that machine's jobs; **Jev is the only sorter** (2026-09-22 evening, replacing the media /
first-of-batch / shortest-first tiers): whenever a worker is free, `harness/queue-jev.py rank` hands Jev (TypeSafe)
every pending job of the machine with the facts: the job's `--intent`, the session's `--progress` and name
(`--name`, once per session: its ListAgents name), the session's age (first timestamp of its Claude Code transcript),
how long the job and the session have waited in all, the size estimate and its source, the job's place in its
session's batch, what is running. One choice question; its probabilities are the whole order: the pick runs,
the order is the plan (`machines/<m>/plan`, `next [machine]` with an ETA per job; `submit` prints the new job's
place and ETA). The policy (fairness between sessions, short jobs first when equal, wrap-up / media / release
work unblocked, no starvation past ~30 min, a session's batch kept in order) is text in `queue-jev.py`, not code.
Jev unreachable: pending jobs show `blocked: waiting for Jev` and the worker retries every 30 s
(`PZQ_JEV_FALLBACK=fifo` takes the oldest instead); `$PZQ_DIR/jev.log`. `submit` refuses a job without
`--intent` / `--progress` (and `--name` on a session's first submit); `queue.sh session [--progress "..."]`
updates the session's context between submits. A `run` must name the resources it tests (`--resource`, vocabulary in
`harness/queue/benches.json`, `queue.sh resources`); the same file is the **bench catalog** (spin, spin-uncapped, bench-100,
walk-hitch, drive-120, drive-60, storm, fog, storm-fog, night-torch, louisville, helicopter, alarm-storm, zoom-cycle,
flicker, shot, load; each with the resources it tests, what it exercises and its run.sh args). `queue-jev.py suggest` =
Jev's pick from the catalog for an intent + resources (and `fits=` of a session's own args, `matches_suggestion=` in
code); `submit run --bench <name>|auto` expands the entry's args before the session's; a run with its own args gets the
suggestion printed and stored (`suggested= fits= matches=` in the job file), never overridden; Jev's ranking sees
`resources_tested` / `bench` too. `queue.sh suggest --intent ... [--resource ...] [-- args]` asks without submitting. The size is estimated at submit time: `--size <secs>`, else the median `ran` of the
finished jobs with the same signature (kind + arguments minus `--label` / `--prop` / `--option` / `--env`),
else a default from the arguments (run: 40 s + route / quit-after seconds; mp 240; workshop 20; cmd 60;
media 120). **Job start** = a desktop notification (label, session name, estimate, intent). **Overrun**: a job
still running past its estimate (counted from the launch, not the preflight wait) sends its session an event
`overrun: job ...` (wakes `watch --exit-on any|overrun`, exit 4) and a notification, again every further
estimate (≥ 5 min); `result.txt` gets `overran=yes`, `list` shows `OVERRUN <s>`. A remote `run` job: rsync `harness/` (+ `build/classes` with
`--install opt`, installed there with `install.sh --uninstall` + `install.sh --from` (2026-09-22: install.sh refuses
over an existing manifest, so a laptop's second `--install opt` job used to die in 1 s) / `run-mac.sh install`) to the machine's checkout →
a generated wrapper (`<job>/remote-job.sh`) exports the desktop session's display env from plasmashell's
environ, unlocks + inhibits sleep (Dell), shuts Steam down (Dell), runs `harness/run.sh <args> <run_args>`
(`run-mac.sh` on the Mac) in the ssh foreground → the run dir is rsync'd back to
`harness/runs/<m>-<label>-<ts>/` (`machine=` added to `run.opts`) and analysed + judged on the desktop.

**Session affinity**: the session id is `$CLAUDE_CODE_SESSION_ID` (every tool shell has it; `PZQ_SESSION`
overrides). The first `submit` (or `bind <m>`) records `sessions/<sid>/machine`; every later submit goes there,
`--machine` elsewhere is refused unless `--rebind`. mp / workshop / cmd jobs are desktop-only.

**Constant connection + notifications**: `pzq-monitor` keeps an ssh master per remote machine
(ControlMaster socket under `machines/<m>/`, ServerAliveInterval 5 s × 2 = the heartbeat) and probes it every
5 s. A transition (measured: 2 s after a killed sshd, ≤ 15 s for a silent link loss) goes to `machines/<m>/state`,
`events.log`, the `events` file of every session bound to the machine or with a job queued there, and
`notify-send`; the machine's pending jobs show `blocked: <m> disconnected` and start when it is back; a
running job whose connection drops fails with exit 70 and its session is told. Sessions get woken by running
`harness/queue.sh watch --exit-on any` in a background Bash (exit 3 = a disconnect, 0 = a job of yours
finished) or by `Monitor`ing `sessions/<sid>/events` / a job's `status` file; `events` prints the recent ones.

**Preflight** on the desktop (waits with the reason in `blocked`): a game process, a `run.sh` /
`mp/run.sh` / `showcase-record.sh` outside the queue, a locked desktop; a `workshop` job skips those (it uses
neither the game nor the display) and needs a really logged-on Steam client (one client restart when the session was replaced).
`--install opt` = `pzopt.sh reinstall` from the job's checkout (build first), `stock` = uninstall, reinstalled
from the same checkout once the desktop queue drains; `--prop enabled=false` needs no install. `keep` (the
default) leaves the install alone, except that a `run` / `mp` job finding the game dir stock with no `--install
stock` job behind it reinstalls from its checkout first: an uninstalled game has no `pzopt.Harness`, so nothing
presses click-to-start and a bench run sits there forever (job 0204 on 2026-09-22, after a peer's `--install opt`
had uninstalled and then failed on an empty `build/classes`; `pzopt.sh reinstall` now checks the build before
removing anything).

**result.txt** (`--wait` prints it, exit = the job's):
- `run`: exit code, `run_dir`, `route complete` count, resolution / OpenGL lines, `zoom=` & scene lines from
  `pzopt-bench.out`, `run.opts` facts (machine), `analyze.py` output, console errors, then **Jev**: `judge.py`
  over the card with `--goal` (default: a valid stand-alone measurement + the objective's headroom question;
  the route-completion, crash and machine facts are appended to the goal text because the card cannot carry
  them) and every `--against` run / baseline json → `verdict=achieved|partial|no_change|regressed|invalid
  confidence= goal_met= tail_regressed= setup_matches_goal= headroom_finding=` and `<run>/judge.json`;
  `--parity-against <run>` adds `parity-judge.py` over the two recordings → `parity=<kind> confidence=
  parity_maintained= look_needed=` and `<run>/parity-judge.json`. `list` shows the verdict per job.
- `mp`: the `harness/mp/run.sh` summary, `window.py <run>:27` and the same `judge.py` verdict; the dedicated
  server is stopped after the job unless the next pending desktop job is also `mp`.
- `workshop`: `scripts/workshop.sh` staging → `scripts/workshop-upload.py --notes` (Steamworks API through the
  game's `libsteam_api.so` and the running Steam client, no game, a few seconds; its `steam:` / `item` / progress /
  `upload OK: EResult 1` lines are in the result, 2026-09-24) → `workshop_log.txt` tail + the change-notes page's
  newest entry; `~/Zomboid/.../workshop.txt` copied to `docs/workshop/workshop.txt` (uncommitted).
- `cmd`: exit code, output tail, the run dir if the command produced one under the label.
- `media` (every encode / re-encode / stitch / GIF render, 2026-09-21 night): shares the desktop queue with
  the runs, so an encode never overlaps a benchmark on this machine; since 2026-09-22 it goes before every
  pending run (tier 1, see above) and both kinds wait for a game, run.sh or
  `ffmpeg` / `gpu-screen-recorder` started outside the queue. The result probes every output (`--out`, else
  the video / image paths in the command that the job wrote) with ffprobe: `output=<file> WxH s MB codec=
  pix_fmt= transfer= primaries= hdr_av1_ok=yes|no`, with a WARNING under a video that is not AV1 10-bit
  PQ/BT.2020 (the publishing rule).

`cancel` drops a pending job or SIGTERMs a running one's process group (run.sh's EXIT trap restores
`latestSave.ini`; a remote job's ssh is cut). Logs: `$PZQ_DIR/worker-<m>.log`, `monitor.log`;
`systemctl --user status pzq-monitor pzq-desktop`. Built 2026-09-21 and tested against a throw-away sshd
(connect / hard disconnect / reconnect, blocked job resuming) — the first real laptop job is the smoke test
of each machine's conf entry.

## Launchers

- `steam` needs the Steam launch options `<repo>/harness/steam-launch.sh %command%`; env vars
  cannot reach a game started by the running client, so run.sh writes
  `~/Zomboid/pzopt-launch.env` per run and the wrapper sources it.
- `direct` runs `projectzomboid.sh` with `-Dzomboid.steam=0`; `auto` (default) picks direct
  when Steam is not running or logged out (a logged-out client silently ignores -applaunch).
  Direct numbers match Steam numbers. run.sh sets `LC_NUMERIC=C` (de_DE locale breaks
  MangoHud's fps_metrics) and has a 120 s start-up watchdog.
- Steam's newer **performance monitor** caps optimized runs at ~160 fps by pinning the GL
  thread. Keep it off in Steam settings; the classic overlay is harmless.
- run.sh refuses when a game process exists or the session is locked (locked screen stalls
  the game at "Creating display").

## Timing and MangoHud log

No default lead since 2026-09-19: the Java harness fixes route start = world ready + settle
(run.sh passes settle=5) and publishes it in `~/Zomboid/pzopt-schedule.out`; run.sh starts the
MangoHud log 3 s earlier over the control socket (abstract unix socket `mangoapp`,
`control=mangoapp` in the conf) with its own python `mh_control` that waits for the greeting.
Do NOT use `mangohudctl` (it hangs up before the accept, log never starts, exit 0). xdotool
Shift_L+F2 is the fallback; Shift_R+F9 resets fps metrics at route start (lost on native
Wayland). `--lead N` restores the fixed clock. `pzopt-logdone` ends the Java linger. Never
poke `@mangoapp` while a run is going. MangoHud's own gpu_load reads 0 for this game; GPU
utilization comes from `sysmon.sh` (nvidia-smi). See `config/CLAUDE.md` for hook details.

## Comparability

Compare runs only with the same: desktop resolution (console.txt "Desktop resolution"),
renderer ("OpenGL version" line: Mesa = Zink, NVIDIA = GL), `zoom=`, `launcher=`, route and
speed, dashboard on/off, display server. `compare.py` warns on opengl/desktop mismatch.
Baselines: started from scratch on 2026-09-24. The frame-time baselines of before (`native/`, Proton `bench-stock-*`,
`windows/`) are in `harness/archive/2026-09-24/baseline/`, only `baseline/parity-stock.out` (the parity gate's
reference) stayed. Without `baseline/bench-stock-{1,2}.json`, `compare.py` takes the newest two `*stock-1-*` /
`*stock-2-*` run dirs as the pair. Noise floor = spread between the two stock runs; a change is real above twice that.

## Multiplayer runs (harness/mp, 2026-09-21)

`harness/mp/server.sh setup|start|stop|up|cmd` runs a stock dedicated server on this PC (hardlinked copy of
the game dir without any pzopt file at `/games/.../pzsrv-stock`, cachedir `/tmp/pzsrv-home`, transient user
unit `pzsrv.service`, stdin from `tail -f /tmp/pzsrv-cmds.txt`). `harness/mp/run.sh <label> [stock]` joins
it with run.sh (`-Dzomboid.steam=0`, `-Dargs.server.connect=127.0.0.1:16261`) and a throw-away
`media/lua/client/pzopt/pzopt_devjoin.lua` that clicks through the connect popup and character creation;
`pzopt.Harness` handles the rest when `GameClient.client` (teleport wait, `/addvehicle`, seating, physics
authority, corridor sweep of leftover vehicles). Facts that cost time: the SP bench save does not load on a
server (own fresh world instead, route `E:800`); the server rewrites its ini at start and shutdown, edit it
stopped, `SpeedLimit` max 150 (default 70 caps every car); `/addvehicle` right at world-ready answers
"Invalid location" (chunk not loaded), the harness repeats it at 8 s; a killed client stays connected
(`kickuser`); ufw blocks the ports on diego-flip and the Mac sleeps, hence localhost. Compare runs over the
same window with `harness/mp/window.py <run>:27 ...`; results in `docs/archive/2026-09-24/results.md` (2026-09-21 21:30).

## Analysis scripts

| Script | Reads | Gives |
|---|---|---|
| `analyze.py <run>` | pzopt-frames/chunks.out, mangohud CSV, pzopt-overlay.out (in-game overlay log, same shape), pzopt-gamethread.out (the overlay's game-thread stack profile: `game thread:` phases / sub-phases / hot methods / waits over the route, `pzopt.GameThreadProfile`), sysmon | route-window frame stats, chunk latency, per-thread CPU. First check a run is valid (mangohud + sysmon + threads lines present) |
| `compare.py --baseline <dir> <runs>` | analyze output | deltas vs stock with noise verdict |
| `grafana/ingest.py <run>...` / `--all` | every file of a run | the run's samples into the Grafana DB (the stack's follower does it by itself ~20 s after a run's files go quiet); see "Grafana" below |
| `waits.py <run>` | JFR wait events (`--jfr --jfr-setting jdk.JavaMonitorWait#threshold=0ms` etc.) | per-thread blocking sites in the route window |
| `attribute.py` / `sections.py` | JFR samples / GameProfiler recording (`--game-profiler`) | where slow-frame time goes (`sections.py --thread game\|render`: the game records `MainThread` = game thread and `main` = render thread; the probes themselves cost ~8 % of the game thread; prefer JFR) |
| `flamegraph.py <run> [--out x.svg] [--folded x.txt] [--all]` | pzopt-stacks.out (the overlay's folded game-thread stacks, one block per second, frame-id dictionary; every harness run with the overrides on) | self-contained SVG flame graph of the game thread over the route window (root `GameWindow.frameStep` at the bottom, hover = share, click = zoom, search box; update green / render blue / lighting amber / pzopt magenta), `<run>/flamegraph.svg` by default; `--folded` = classic `a;b;c count` lines; `--all` = the whole run (boot, load). No JFR needed; safepoint-biased at 100 Hz |
| `subtree.py <run>... [--frame Class.method] [--depth N] [--min-pct P] [--self] [--all]` | pzopt-stacks.out | the callee tree under one game frame over the route window (default `FBORenderCell.renderMovingObjects`, the characters draw), inclusive share of all game-thread samples per callee, `--self` adds the leaf frames; several runs = one tree each, so a before / after pair is one command (2026-09-22, the characters draw pass) |
| `schedmon-report.py <run> [--spike 50]` | schedmon.txt (`run.sh --schedmon 0.05`: `harness/schedmon.py` reads /proc per thread, no root) | per thread over the route: CPU share, run-queue wait (runnable, no CPU), major faults; machine PSI / swap-ins; then the game thread's running / waiting-for-CPU / off-CPU split in spike frames vs normal frames and who ran instead (2026-09-22, the Dell hitch pass: JIT threads and the sampler's safepoints) |
| `corepower.py <run> [--top 16] [--json]` | schedmon.txt from `run.sh --schedmon 0.25` (2026-09-24: schedmon also writes each thread's last CPU and run count, the `# cores fast= slow=` classes, FREQ lines with both classes' clock + amdgpu PPT, GM lines with the SMU rails of an amdgpu gpu_metrics v3.0 APU: socket / GFX / cores / fast- and slow-core sums / GFX activity / DRAM MB/s, PROC lines with the ten busiest other processes, GPU lines with every process's DRM engine time) | package power (PPT mean / p50 / p95), the SMU rails, clocks, the process's CPU in cores and how much of it ran on fast cores, per thread (pools grouped) cores / share on fast cores / run-queue wait / wakeups per s, the other processes, GPU engine time per process. The flip's SMU rails do not add up once a GPU profile level is held: compare runs on socket power (the E-core pass, `docs/findings-ecores-2026-09-24.md`) |
| `macpower.py OUT.csv [s]` | Apple silicon, no root (run-mac.sh starts it beside the sampler -> `<run>/power.csv`) | P / E cluster busy % (host_processor_info ticks; Apple numbers the E cores first), GPU W from IOReport's "Energy Model" (its CPU channels stay frozen on the M1 Pro), SMC system load (coarse, display included) |
| `hitch-table.py <run or label>...` | pzopt-overlay.out, sysmon.csv, schedmon.txt | one row per run: fps, p50 / p99 / p99.9 / max, frames > 50 / > 100 ms per minute, jitter, CPU / GPU %, game-thread run / queue / off %, JIT and GC cores, swap-ins |
| `spike-wall.py <run> [--spike 50] [--skip 5]` | asprof.jfr from `run.sh --asprof event=cpu,interval=5ms,wall=5ms` | wall-clock samples of the game and render threads summed over the frames above the threshold: what the game thread ran or waited in, and what the render thread was stuck in when the game waited at the frame hand-off (2026-09-23: the weather drawer's glGetInteger, first-bake glGenTextures) |
| `luaprof.py <run> [--by fn\|file\|chain]` | pzopt-lua.out (`pzopt.LuaProfile`, key `luaProfile`, on in instrumented runs) | which Lua functions the game thread runs over the route, as shares of all game-thread samples (2026-09-23: the Dell's Lua was 8.6 %, spread over vanilla UI: inventory page prerender / loadWeight 0.6 %, hotbar, buttons) |
| `pzopt-nmt.out` | a run with `--vmarg -XX:NativeMemoryTracking=summary` | the JVM's native memory summary at route end (heap, class, thread, code, GC, other = direct buffers), written by the harness through the DiagnosticCommand MBean; `schedmon.txt` MEM lines give the process's resident memory by mapping kind every 10 s |
| `gc-spikes.py <run> [ms]`, `badseconds.py <run> [ms] [key prefix]`, `long-windows.py <run> [s]` | gc.log / pzopt-gamethread.out / overlay + schedmon | spike frames overlapping a GC pause; game-thread phases (or `w:` waits) in the worst vs calmest route seconds; a long run in windows |
| `run.sh --asprof event=cpu,interval=5ms[,wall=10ms,alloc=2m,lock=1ms]` | async-profiler 4.x `libasyncProfiler.so` placed in `harness/asprof/` (gitignored; github.com/async-profiler/async-profiler releases) | `<run>/asprof.jfr` with native frames (JIT compiler, GL driver, FMOD threads); `jfrconv --cpu -t --from <route_start_ms> --to <route_end_ms> -o collapsed`, or `jfr print --json` for per-sample times. Safepoints: `--vmarg -Xlog:safepoint=info:file=/tmp/sp.log:time,uptime` |
| `simdiff.py <run-a> <run-b>` | pzopt-sim.out (`--prop devSimChecksum=true`: one line per frame hashing every zombie's position, target, action and animation state after postupdate) | the first frame two runs' checksums differ. Caveat found on 2026-09-22: two runs of the game are never frame-aligned (the zombie count differs from frame 5, spawn timing), so it cannot prove a parallel phase equal to the serial one; the per-phase in-run rigs do (`devActionEvalCheck`, `devLightingReadCheck`, counters on the `zombie_batches=` line) |
| `soundprof.py <run>...` | pzopt-stacks.out (or the MainThread of asprof.jfr, 200 Hz), asprof.jfr / pzopt-sound.out | the sound engine's cost: share of the game thread in sound code (FMOD Java side, emitters, parameters, vocals / ambient / alarm / vehicle-sound managers, world sounds) and ms per frame, by entry point and by leaf (JNI into libfmod separately), the census means settle -> route, and FMOD's native threads (asprof: cores + hottest native frames; else /proc schedstat) (2026-09-24) |
| `audio.py <run>... [--pre 10] [--no-png]` | recording.mp4 of a `--record --record-audio game` run (`--record-audio game` = only the game's PipeWire stream "FMOD Audio", opus 384 kbps; plain `--record` mixes in the whole desktop and is flagged) | what the game sounded like, settle vs route window: EBU R128 loudness / LRA / true peak, RMS / peak / crest, clipped samples, L-R balance and correlation, band shares (sub / low / mid / presence / high), dropouts (5 ms holes in sound), clicks, the route-start onset (alarm start; checks the clock), a per-second table joined with the pzopt-sound.out census, `<run>/audio-timeline.png` and `audio-spectrogram.png`; several runs = a comparison table. Clock = recording mtime minus duration (2026-09-24) |
| `audio-judge.py <run> [--against <ref run>] [--context ...] [--json out] [--no-jev]` | recording.mp4 of a `--record-audio game` run (+ pzopt-sound.out, console) | is the game's audio clean? Code measures per window (settle = world-ready + 6 s .. route start, route, exit): cutoffs (a band's 5 ms level falls >= 15 dB in 25 ms from >= -40 dBFS and stays down, unless it is a burst decaying back to the bed it rose from), ambience gaps (a band sags >= 10 dB under its 8 s median for 0.5-6 s and recovers), dropouts, clicks off the scripted gunshot rhythm (`gunshots=R`: phase found from the onsets), clipped samples, true peak, LUFS, and against a reference run the loudness / band / per-second deltas (a clipping reference is flagged: its loudness is the distortion's); Jev answers verdict clean / cutoff / gap / dropout / distortion / differs_from_reference / inconclusive, `audio_clean`, `exit_clean`, `matches_reference`. Exit 0 = clean; `<run>/audio-judge.json`. The queue runs it on every `--record-audio game` run (reference = the `--parity-against` / first `--against` run with game audio). Calibrated on eight recordings 2026-09-24 (2026-09-24) |
| `gametree.py <run>` | JFR samples (`--jfr --jfr-period 1`) | inclusive call tree of the game thread over the route (`--root`, `--thread main` for the GL thread, `--callers method`, `--min-pct`) |
| `loadtime.py <runs>` | pzopt-loadtrace.out | load-after-Continue phases side by side |
| `loadsheet.sh <run>` | recording.mp4 + loadtrace | contact sheet around the load |
| `revealmap.py <run> [--seconds S] [--geom <pzopt-resume.properties> --fit]` | recording.mp4 of a Continue (`--prop resumeShot=false --prop overlay=false` for the live world, the shot on for the fake one) | when each pixel appears after the black loading frame and stays: reveal curve, biggest per-frame steps, per-chunk first / median / last times and chunk arrivals per frame (grid fitted to the map with `--fit`), reveal time by distance, `<run>/revealmap.png` time map. Run `worldload-rec2` (2026-09-24): ~50 chunks whole, 267-533 ms after entry, 9 bursts, no spatial order — the model of `pzopt.ResumeShot`. A queue `media` job |
| `parity.py a b` | pzopt-parity.out | square-by-square recalc diff |
| `pacing.py <run>` | `pzopt-pacing.out` (`--prop instrument=true` or `pacingLog=true`) + `present.txt` / `vrr.txt` (Linux, written by every run.sh run: `presentprobe.c` = X Present CompleteNotify flip times of the game window, built on demand with `-lxcb -lxcb-present`; `vrrprobe.py` = DRM `VRR_ENABLED` per CRTC via libdrm, no DRM master) or Metal `presentedTime` (Mac bridge) | on-screen judder \|flip interval - sim step\|, frames off by > 2 ms, step -> screen latency, VRR on-share; ~1 ms mean is the floor at a fixed refresh (2026-09-24, `docs/archive/2026-09-24/findings-vrr-2026-09-24.md`) |
| `blacktiles.py ctrl.png run.png...` | `--shot-at` captures | newly-black pixels and fully black 32 px tiles of a run against a control capture |
| `blackframes.py <recording.mp4>... [--fps 4] [--csv out]` | `--record` captures | per-frame count of entirely black 8 px blocks (32 px at 5K) over the scene, whole and centre 50 %, worst seconds; compare runs of one route only — downtown interiors and the void beyond the loaded grid are black in stock too (2026-09-22, Louisville black squares), so read it next to frames, not alone |
| `zoomsteps.py <run> [--window S]` | pzopt-frames.out of a `--flag zoom_cycle=S [zoom_span=N] [zoom_jump=true]` run (the harness marks `zoom-<level>` at every step) | frame times in the window after each camera zoom step vs the rest of the route (max / p99 / >8 >16 >33 ms); the console has a per-step bake trace (`zoom step N trace`) and the `retain:` / `change-frame` counters; `attribute.py --after-mark zoom-:1` and `sections.py --after-mark zoom-2.5:1` profile the change frames |
| `flicker.py <run>/recording.mp4 START END [--scale W] [--heat png]` | `--record` of a `--flag hold=N` run | per-frame appear / disappear metric (pixels that change and revert within 3 frames), busiest screen cells, heat map |
| `flicker-triple.py <run>/recording.mp4 FRAME` | same | crops of one frame triple with the A-B-A pixels marked (frame-numbered; use `-ss` times for anything compared with flicker.py) |
| `drive_check.py <run> [--against <run>...] [--no-jev]` | `pzopt-drive.out` + `pzopt-bench.out` of a path drive | the drive's card and Jev's verdict (valid / left_the_line / crashed / stalled / slow, usable, consistent with the reference drives); exit 0 = valid; writes `<run>/drive-check.json`; judge.py runs it too |
| `drive-path.py streets\|route\|plan` | the map's `streets.xml` + town labels | `path=` flags for path drives: `route --via "A>B>C"` follows named streets, `plan --describe "..."` lets Jev pick among the street-graph routes from a start |
| `judge.py <run> --against <run|baseline.json>... --goal "<what the change should do>"` | analyze.py summaries | TypeSafe (Jev) verdict on the uplift: achieved / partial / no_change / regressed / invalid, goal met, tail regressed, setup matches the goal, hardware-headroom finding. Code computes the card, the deltas (compare.py noise floors, presented-frame ratios) and the objective's facts; Jev only reads that JSON. Exit 0 = achieved; writes `<run>/judge.json` |
| `parity-judge.py <run|mp4 A> <run|mp4 B> [--seconds 20] [--context "..."] [--shots a.png b.png]` | two `--record` recordings of the same route | visual-parity verdict from Jev over numbers only (it never sees pixels): flicker transients, black share, luma pops, the blocky-lights hard-jump metric (> 40 at 30 fps) and solid jump blocks, HUD corners separated, window aligned at the end of on-screen activity (the quit). Known pairs: `bl-amb-stock` vs `bl-amb-before` → lighting_pops 0.93, vs `bl-amb-fix` → parity 0.79. Exit 0 = parity |
| `ui-drive.py step "<screen>" "<control>"` / `read` | live screen (spectacle) or `--screen png` | drives the game's menus: OCR (tesseract, inverted 2x; `~/.local/share/tessdata`) → one Jev request per step (screen up?, which line is the control, error showing?) → press-and-release at the line; focus check via xdotool. `read` is the pad checks' OCR. The Workshop deploy sequence it ran 2026-09-21 to 09-24 is gone: the upload is `scripts/workshop-upload.py` (Steamworks API, no game) since 2026-09-24 |
| `showcase-director.py [--log f] [--wait s] [--runs N]` | `~/Zomboid/pzopt-showcase-state.json` from a `--flag showcase=horde --flag director=jev` run | Jev directs the showcase scene: one choice request per new state (run_to_pier, face_horde, light_fire_line, shoot, reload, hold), the answer written to `~/Zomboid/pzopt-showcase-cmd.txt`, every decision logged with probabilities and latency. Start it in the background before the run (it waits for a fresh state); it exits when the state is stale 15 s (with `--runs N` it then waits for the next run's state, N runs in all). Scene flags (`pzopt.Showcase`): `start=` (run start), `pier=x,y`, `horde_dir`, `horde`, `horde_gap`, `mags`, `gun`, `ambulance`, `lightbar`, `fire_ms`, `power=off`, `darkness=pitch`; with the Scene flags `weather=storm fog=heavy fog_tint=dark`. Pass `--option panCameraWhileAiming=false --option focusloss=false`. Take `cine-jev2` → `docs/media/hdr-horde-cinematic-jev.mp4` (`tools/hdr/cine-cut.sh`, local only: 502 MB) | Bench flags (2026-09-24): `burn=false` (no ignition), `fire_line=false` (the line only marks lit), `no_attack=true` (stock's `SystemDisabler.zombiesDontAttack`: GOD_MODE is a cheat that needs `Core.debug`, so without it the horde bit the player to death).
| `ragdoll-judge.py --stock <runs> --opt <runs> [--no-jev] [--json f]` | `pzopt-ragdoll.out` (`pzopt.RagdollWatch`, written by every `showcase=horde` run), console.txt, run.opts, pzopt-bench.out of runs of the `horde-shoot` bench | stability + ragdoll A/B (2026-09-24, the "ragdolls slide, spin, sink and fly" report): per run crash / hs_err frame, exceptions after world-ready by type + first frame, ragdoll calls off the game thread, rounds / kills, and per ragdoll episode (controller live → released or corpse) pelvis speed, travel / net drift / turn after 1.5 s (20 Hz samples: summed per frame they grew with the fps), height over the floor, body-to-character gap; abnormal shares past `LIMITS`; then Jev's verdict (`same_as_stock` / `optimized_worse` / ...). Compare runs at the same fps only (the card prints the sampled fps): stock at a 60 cap vs optimized at ~290 fps looked like 18 % vs 3 % sliding bodies with the per-frame metric. Pin `--install opt` on every job of a series (another session's `--install <tree>` in between leaves a build without the scene: 0 showcase lines). The `LungeState` `setForwardDirection` IllegalStateException is stock behaviour when a zombie stands on the player (`no_attack`), in both builds |
| `pad.py serve <fifo>` / `pad-check.sh` (`OFFER=true|false`) | a queue `cmd` job | virtual Xbox 360 pad (python-evdev UInput, xpad layout: GLFW GUID `030000005e0400008e02000010010000`, mapped by `media/gamecontrollerdb.txt`) driven by lines on a fifo (`a b x y start back lb rb l3 r3 up down left right`, `hold <btn> <s>`, `chord <btn> <btn> [s]`); the game only lists a pad whose GUID is a `controller=` line of `options.ini` (the script appends it after a newline guard: the game leaves none after the last option; restored on exit) and activates it on the first A. `pad-check.sh` (2026-09-22) boots to the main menu with `devUpdateOffer`, walks the D-pad from the stock default focus (SOLO, 7 downs to the update item), A / B / scroll / quit through the pad, OCR verdicts, screenshots under `/tmp/pad-check-<OFFER>/`; the game quits through Quit → Yes. Never sends A while the update dialog is up (that is Update now); `pad-overlay-check.sh` (2026-09-23) checks the SHOW / HIDE PERFORMANCE OVERLAY item: main menu by pad (4 downs from SOLO) and mouse, pause menu (bench run, Escape) by mouse; its pause-menu pad step needs the pad to be player 1's controller (a keyboard player's pause menu takes no joypad focus); `pad-chord-check.sh` (2026-09-24) checks the overlay's controller shortcut L3 + R3 (main menu: before the pad is activated, a 1.5 s hold = one toggle, L3 / R3 alone = none, F9, the item text `(F9 / L3 + R3)`; bench world with the pad bound to no player), screenshots under `/tmp/pad-chord-check/` |
| `typesafe_client.py` | | shared Jev client (`ask`, `noul`, `choice`, `fmt`); `typesafe-sdk` when importable (user-level pip on the desktop), plain urllib elsewhere; key from `$TYPESAFE_API_KEY` or `~/.config/pzopt/typesafe.key`, never in the repo; ~0.6-1 s per request; `python3 harness/typesafe_client.py` is the smoke test |
| `readme-chart.py` | named runs | `docs/media/drive-results.svg` |
| `mods-table.py` | numbers typed in from results.md | `docs/media/workshop-mods-comparison.png` (the Workshop mods table image in the README) |
| `updater-card.py` | `docs/media/updater-{menu-current,menu,dialog-available,dialog-installed}.png` (desktop captures, 2026-09-22) | `docs/media/updater-card.png`, the Workshop page's "New! In-game updater" section as one image (media style; `ffmpeg -vf scale=1920:-1` → `docs/workshop/images/15-updater.jpg`) |
| `newcard.py` / `audio-card-gif.py` / `ao-card-gif.py [--still png]` | the card's numbers + the recordings behind them (`audio-card-gif.py`: the `snd-i6-stress-*` pair) | the Workshop page's animated "New!" card (2026-09-24): the still card's style at 1920x1080, the change playing in the left half, intro + table on the right, footer under both; `--still` = the first frame without ffmpeg (layout check). `docs/workshop/images/26-clear-audio.gif`, `27-ambient-occlusion.gif` (AO off / on wipe over two pixel-aligned `--shot-at` captures); render as a queue `media` job |
| `stitch-quad.sh` | four drive recordings | 2:1 quad video (header comment has the launch recipe); its quad6 captures are SDR H.264, composed in SDR and mapped to PQ/BT.2020 (reference white 203 nits) at the end |
| `stitch-louisville-sbs.sh <stock-label> <opt-label> [out]` | two `--preset louisville` recordings (in-game overlay on) | side-by-side aligned at the quit-to-black instant (the game quits the moment the route ends: a hard sync point in both captures, unlike the file birth time, which is 0.3-0.6 s off and drifts 6 ms/s) minus the route length; overlay insets at half size; header numbers from `analyze.py`'s overlay line; `docs/media/louisville-horde-spin-stock-vs-optimized.mp4` |
| `stitch-duo.sh <stock-label> <opt-label> <out>` | two profiled recordings of one scene (overlay + profiler on, `overlayFont=Large`) | the two-panel form of `stitch-tri.sh`: both captures side by side at half size (overlay tree and flame graph readable), results strip underneath from `analyze.py`, aligned at the route's motion onset, AV1 HDR; env `WHAT ROUTE LABEL_STOCK LABEL_OPT TITLE_OPT NOTE`; `docs/media/louisville-horde-profiled-stock-vs-zombie-game-thread.mp4` |
| `stitch-vehspawn.sh <before-run-dir> <after-run-dir> <out>` | two recordings of the vehicle-spawn rig (2026-09-23, Workshop "cars barely spawn"): `--mode bench --flag start=15580,2885 --flag settle=20 --flag route=S:160 --flag speed=8 --flag zoom=max --flag zombies=off --flag see_all=true --flag car_spawn=5 --flag time_of_day=12 --route-seconds 25 --record --prop overlay=false`, before = the released IsoChunk, after = the `AddVehicles_OnZone` fix (runs `vehspawn-before-20260923-035043` 2 vehicles, `vehspawn-after-20260923-035201` 68) | `docs/media/vehicle-spawn-before-vs-after.mp4` (AV1 HDR 5120x1730, 24 s): both panels at half size aligned on the route onset, `vehicles_loaded` per side |
| `stitch-cansee.sh <broken> <fixed> <out>` | two `lure=cow` recordings (`cansee-broken` = HEAD a7ca5b2, `cansee-fixed2` = HEAD + the cast) | `docs/media/cansee-lure-before-vs-after.mp4` (AV1 HDR, 3840x1302, 24 s): before / after panels cut 1.5 s before the cow's first frame (`START_BAD=19.8 START_FIX=19.0`), StackOverflowError count from the consoles, left audio = before |
| `stitch-thump.py <before> <after> <out>` | two `thump=3` recordings (run recipe in its docstring; before with `--prop devActionEvalUnitMultiplier=true`) | `docs/media/zombie-thump-offscreen-before-vs-after.mp4`: both cut 2 s before the zombie spawn (console epoch vs file birth), crop around the player and the door, phase captions, the per-second `harness: thump` counters burned in (half-open `gte*lt` ranges: `between` shows two values on a boundary frame), left audio = before. Record with the overlay panels off (`overlayTree/Flame/Graph/Verdict=off overlayStats=fps`), the flame graph covered the door |
| `stitch-overlay-sizes.sh [out]` | the `ovr-resp-5120x2160` and `ovr-resp4-{1920x1080,1280x720,1024x768}` spin recordings (overlay on; the small ones windowed with `--option borderless=false --option fullScreen=false --option width= --option height=`, centred on the desktop, cropped to the client area, which sits exactly at the centre) | `docs/media/overlay-responsive-4-screen-sizes.mp4` (AV1 HDR 5120x2600, 26 s, 2026-09-22): the responsive overlay at four screen sizes, the small ones 1:1 |
| `stitch-storm-sbs.sh` | stock + optimized 120 km/h thunderstorm recordings (`sbs-storm120-*`, in-game overlay on) | side-by-side aligned at the car's motion onset, each run's overlay inset at full resolution |
| `stitch-stormfog-sbs.sh` | stock + optimized 120 km/h heavy-fog thunderstorm recordings (`sbs-stormfog-*`, preset storm-fog, 2026-09-21) | the same layout: `docs/media/drive-120kmh-storm-fog-stock-vs-optimized.mp4` (AV1 HDR, 3840x810, 42 s) + poster jpg |
| `stitch-blocky-lights.sh` | the `bl-torch-*`, `bl-drive-*` and `bl-amb-*` night recordings (before = the fix keys at their old values) | `docs/media/blocky-lights-before-vs-after.mp4` (AV1 HDR, 3840x790, 60 s: torch spin, SportsCar drive, ambulance drive, each BEFORE / AFTER / STOCK) + poster jpg |
| `encode-av1-hdr.sh in out [width]` | any mp4 | AV1 10-bit HDR re-encode (HDR input kept, SDR input mapped to PQ/BT.2020), optional downscale; used for the 60 km/h video and the `-1080` README copies |
| `stitch-triple.sh` | three bench recordings (stock settings, optimized 2026-09-19, optimized + game-thread pass) | 2:1 quad video with a results panel; clip starts derived from run.opts launch_epoch and pzopt-schedule.out (recorder starts ~1 s after launch_epoch) |
| `stitch-triple-hdr.sh` | three uncapped bench recordings (stock settings, optimized before the 2026-09-20 evening pass, all optimizations) | 2:1 quad video kept in HDR end to end (NVENC AV1 10-bit, PQ/BT.2020 tags via `setparams` + `write_colr`); the in-game overlay region of each capture is pasted 1:1 (x1.25) into its panel so the numbers stay readable; results panel from env `RES_*`. Recordings: `--record --no-mangohud --env MANGOHUD_CONFIG=no_display --prop overlay=true --prop overlayFont=Large` (the maintainer's Steam launch options are `steam-launch.sh mangohud %command%`, so MangoHud is injected on every Steam launch and must be hidden explicitly) |
| `stitch-sbs.sh` | stock + optimized 120 km/h recordings | side-by-side video with live boot/load counters and a hardware panel; header explains the HUD-clock sync. The first ~2.3 s of every capture show the desktop: never start a pane before the game window appears |
| `stitch-drive-pair.sh <run-1> <run-2> <out.mp4>` | two `--record` runs of the same path drive (runs `dsv-1` / `dsv-2`, bench `drive-120-south`, 2026-09-24) | `docs/media/drive-120-south-two-runs.mp4` (AV1 HDR 5120x1910, 41 s): both at half size aligned on the route-start flash (the last spike before the picture pans; `showcase-times.onset` fails there: a HUD blink every second breaks its stillness test, and correlating motion with the logged speed landed 1.4 s early), each panel with the pilot's live readout (km/h, plan, lane error, tiles) as ASS from `pzopt-drive.out`, results strip = overlay frame numbers + `drive_check.py` card and Jev verdict per run + the pair compared (Jev consistency). Submit as a queue `media` job |
| `stitch-sbs-gif.sh` | the stitch-sbs.sh mp4 | two README GIFs under GitHub's 10 MB limit: `-load.gif` (boot + load, real time) and `-drive.gif` (10 s of the route + the result lines) |
| `showcase-record.sh <drive120\|spin\|louisville\|fog120\|storm120\|menu> <stock\|opt>` | | one showcase recording: in-game overlay (Large), no MangoHud, AV1 HDR capture, both sides uncapped; `stock` = every runtime and boot key off via `--prop`, `menu` = verify mode sitting in the world for the Options tab capture (driven by hand with xdotool; the XWayland pointer is 1.25x the xdotool coordinates on this desktop). Runs `show-*` (2026-09-20 evening) |
| `showcase-times.py <labels>` | run.opts, pzopt-schedule.out, pzopt-loadtrace.out, pzopt-overlay.out, recording.mp4 | per run: route-start onset in the video (frame differencing on 48x20 thumbnails around route_start_epoch_ms; drive runs show one isolated spike there), the epoch→video offset, boot / load seconds and their video times, whole-route fps / p99 / 1 %-low, fps in 0.25 s bins. JSON for the stitch |
| `stitch-showcase.py [out]` | the eight `show-*` recordings + the menu one | the ≤2 min showcase (`docs/media/showcase-stock-vs-all-optimizations.mp4`, 3840x1800 2.13:1, AV1 HDR): title, boot + load counters flowing into the 120 km/h drive, the Optimizations tab, spin / fog / storm, results card = centred table on a black frosted-glass panel (blurred, darkened crop of `RESULTS_BG`, the maintainer's own capture from `RESULTS_BG_SS` s on) (no title card); stock left, optimized right, big 1 s-average fps per side from the overlay log, ASS text (libass), silent segments as intermediates under `/tmp/pzopt-showcase` joined with xfade; audio built in the join: the menu theme, swapped for the game's rain loop (`ZomboidSound.bank` stream 15042 `world_ext_rain_general_very_strong`, index from the FSB5 name table, vgmstream) over the storm, then a two-pass linear loudnorm to -16 LUFS with the video copied (`FAST=1` preview, `SKIP_SEGS=1` / `ONLY=3,7` re-join). acrossfade truncated an audio chain once; delays + amix instead |
| `showcase-thumbnail.py [out]` | a frame of the results-card capture (`THUMB_SRC`, `THUMB_T`, `THUMB_CROP`) | the YouTube thumbnail: tone-mapped, cropped 16:9 around the player and the zombie, "PZ optimized before GTA VI" on top, `FPS_STOCK` vs `FPS_OPT` big at the bottom, Pillow |
| `menu-gifs.py [clip...]` | pairs of harness recordings (`show-drive120-*`, `show-spin-*`, `show-fog120-*`, `show-storm120-*`, `show-louisville-*-3`, `bl-torch-stock` / `bl-torch-fix4`, `bl-drive-stock` / `bl-drive-fix4`, `sbs-stock120-1` / `sbs-opt120-uncap-1`) | the Optimizations tab's preview clips `src/media/ui/pzopt/compare/<clip>-{stock,opt}.gif` (2026-09-21 night): both sides cut the same seconds after the motion onset (showcase-times.py), tone-mapped, 512x216, 24 fps, 4 s, encoded like the Workshop preview (3x3 median + hqdn3d/bilateral scene denoise before the counter, 96 colours, no dither, gifsicle `-O3 --lossy=100` with cumulative-rounded centisecond delays for real time; ~2 MB each, storm 1.5, the dark night pairs 0.4, 19 MB in all), the run's live fps from `pzopt-frames.out` burned in bottom-left (stock amber / optimized green, none on the 60 fps-capped night pairs); `load` = launch → world with a seconds counter at 6 fps, both 16 s, the optimized side frozen on its world. The overlay clips (2026-09-22: `overlay`, `ovstats`, `ovtree`, `ovverdict`, `ovgraph`, `ovflame`) are crops of the runs `ov-off` / `ov-full` (the spinning route with the overlay off and with every default element on at `overlayFont=Large`), halved so the Large text stays readable, 16 fps x 3 s, no counter; the verdict and graph crops are tall because the tree above them changes its row count. `harness/menu-gifs.json` records the runs and cut points. Submit it as a queue `media` job |
| `issue4-gif.py [before after out]` | `shot-game.png` of two `--shot-at` runs at the same camera position (default the newest `i4-repro-*` and `i4-fix2-*`: Rosewood living room 8147,11507, `--flag close_curtains=true`, zoom 1) | `docs/media/issue4-curtains-before-after.gif`, two labelled 2.2 s frames (issue #4, windows through closed curtains); `--flag find=curtains` lists curtain squares to pick a `start=` |
| `proton-preflight.sh` | Steam manifests | read-only Proton readiness report |
| `simulate.py` | | streamer queue simulation |

**Every published video under `docs/media/` is AV1 10-bit HDR (PQ / BT.2020, NVENC `av1_nvenc`,
`setparams` + `write_colr` tags), like the gpu-screen-recorder captures; the stitch scripts keep HDR
sources 10-bit end to end (no tone-map) and map SDR sources to PQ. Text colours in the HDR
compositions are PQ code values (~60 % = comfortable white). Posters (`.jpg`) and the README GIFs
are tone-mapped (hable, 200 nits) from the HDR file (2026-09-20 night). Colours, type and layout for
all of it: `docs/media-style.md`.**

Use `--record` on drive runs and read frames from `recording.mp4` with ffmpeg (tile contact
sheet) before concluding anything about visuals; it is a monitor capture, so the game window
must stay focused and uncovered.

## Windows

`run-win.ps1` (PowerShell) does the run.sh steps a bench needs on a Windows depot: harness mod,
bench save rebuilt from `bench-save/pzopt-bench-template.tar.zst` (Windows tar reads zstd), flag
file, pzopt.properties, Steam launch, a `Get-Counter` + `nvidia-smi` sampler with sysmon.sh's
columns, collection into `harness/runs/`. No MangoHud, JFR or recording. `-Prop`/`-Flag` accept
comma-joined values (`powershell -File` hands the script one string). The Java harness quits at
the route end by itself when no MangoHud is loaded. `analyze.py` runs on the embeddable Python
(no installer needed); `analyze-win.ps1` is a PowerShell fallback. The game pauses on focus loss
(`focusloss=true`), which stops `IsoChunk.update` and with it the frame sampler: keep the window
focused. Reference numbers in `archive/2026-09-24/baseline/windows/`, findings in `docs/windows-test.md`.

## macOS

`run-mac.sh` (bash 3.2, runs on the Mac) is the same for the macOS depot (`Project Zomboid.app`):
`install <classes-dir>` / `uninstall` / `status` put the desktop's `build/classes` into
`Contents/Java` with a `pzopt-installed.txt` manifest (the bundle's `JavaAppLauncher` builds
`-Djava.class.path=<Contents/Java>/` ahead of the jars, so the loose classes load under Steam too),
`--label ... --mode drive --flag ... --prop ... --option ...` does a run: harness mod, bench save
rebuilt from `Saves/Sandbox/pzopt-bench-template` (unpack the `.tar.zst` there once, streamed as a
plain tar: the Mac has no zstd), flag file, `pzopt.properties`, direct launch from the bundled
`jre-aarch64` with the Info.plist `JVMOptions` (`-Dzomboid.steam=0`, `-XstartOnFirstThread`,
`-cp .:projectzomboid.jar`) under `caffeinate -dis`, a `top` + `ioreg` (IOAccelerator "Device
Utilization %") sampler with sysmon.sh's columns, collection into `harness/runs/`, every touched
file restored. No MangoHud, JFR or recording; `pzopt-frames.out` is the frame source for both
sides, the overlay log only exists with the overrides on. Stock = `--prop enabled=false`. The
desktop side: rsync `harness/run-mac.sh`, `harness/mod`, `build/classes` over, run in the ssh
session's foreground, rsync `harness/runs/mac-*` back and analyze here. Apple's GL is "2.1 Metal"
(no `ARB_buffer_storage`: `persistentVbo` falls back). A launch from an ssh session opens the
window on the logged-in desktop; `open steam://rungameid/108600` from ssh does nothing visible,
`open "<the .app>"` runs the stock launcher. Reference runs `mac-drive120-*` (2026-09-21).

## Grafana (harness/grafana, 2026-09-24)

Replaces `dashboard.py` / `docs/archive/2026-09-24/benchmark-progress.html`. `harness/grafana/stack.sh up|down|disable|status|psql|logs|reset`:
rootless podman pod `pzopt-grafana` (postgres:15 at 127.0.0.1:5433, db / user / password `pzopt`; grafana-oss at
http://127.0.0.1:3000, anonymous viewer, admin password in `~/.config/pzopt/grafana-admin`), data under
`~/.local/share/pzopt-grafana/`, plus the follower `ingest.py --follow`. Both are enabled systemd user units
(`pzopt-grafana.service` = the pod, `pzopt-grafana-follow.service`; `up` writes them to `~/.config/systemd/user/`), so the
stack comes back after a reboot at login (the transient units of the first day did not). No sudo.
`harness/grafana/check.py [--run R] [--runs A,B] [--source asprof]` runs every dashboard query through Grafana's API
with the variables filled in: the test after editing `dashboards.py` (0 errors expected).
- **Power traces** (2026-09-24): `sysmon.sh` writes `cpu_w` (CPU package from RAPL energy deltas), `soc_w` (an AMD
  APU's socket, hwmon PPT), `total_w` (battery discharge on battery, else CPU or socket + discrete GPU; empty without a
  CPU reading) beside `gpu_w` / `bat_w`; the game writes `pzopt-power.out` (`pzopt.Power`, same rules) whenever the
  overlay log is on, i.e. every harness run; the Mac's `power.csv` (macpower.py) is imported too. `analyze.py` prints
  `power (<source>, route window): total W = J/frame`; `runs` carries `cpu_w`, `total_w`, `j_per_frame`, `power_source`
  (sysmon first, then the game, then macpower). Panels: PZ runs (W and J/frame per run + trends), PZ run (row "Power"),
  PZ compare (power W / J/frame in the tables, per-second power), PZ live (power stat + trace). The desktop's RAPL
  counters are root-only until the maintainer runs `scripts/power-access.sh` once (udev rule); until then desktop runs
  have GPU watts only and no total.
- **Every sample, not only summaries** (`schema.sql`): `frames` (pzopt-frames.out), `overlay` (presented frames, GPU ms,
  thread loads), `sysmon` (with the power columns cpu_w / soc_w / total_w since 2026-09-24), `power` (the other power traces: source
  `game` = `pzopt-power.out` from `pzopt.Power`, `mac` = `power.csv` from macpower.py), `gamethread` (per-second phase / sub-phase / hot-method / wait shares), `chunks`, `gc` pauses,
  `present` flip intervals, `series` (drive telemetry, VRR, marks), `threads`, `counters` (pzopt-bench.out numbers,
  `bake_counters=`, `zombie_batches=`), `stacks` + `stack_defs` (every profiler's stacks per second and thread, root first,
  deduplicated across runs by an md5 id; JVM hidden-class addresses `/0x...` stripped so runs compare): `game` =
  `pzopt-stacks.out` (the in-game profiler), `lua` = `pzopt-lua.out`, `jfr` = `pzopt.jfr` (`--jfr`), `asprof` = `asprof.jfr`
  (`--asprof`, every thread, native frames; both through `tools/JfrSamples.java`, cached in `~/.cache/pzopt-grafana/`),
  `sched` (`schedmon.txt`: per OS thread per second CPU % / run-queue % / major faults; PSI and memory lines into `series`),
  `series` source `pacing` (`pzopt-pacing.out`), `events` (every console line of `pzopt-loadtrace.out` with level /
  category), `inputs` (`pzopt-input.out`, `pzopt.InputRecorder`: every key / mouse / pad change the game thread saw) and
  `run_inputs` (what the harness configured: flag file, `pzopt.properties`, options, env, vmargs, mods, run.opts), and one
  `runs` row with analyze.py's route-window summary + chunk latency + Jev's verdict. Each sample has `t` (wall clock), `rel_s` (seconds since route start) and `rt` (route time = 2000-01-01 UTC +
  rel_s: the x axis of the Run / Compare dashboards, 00:00:00 = route start).
- **Follower**: imports every run dir under `harness/runs/` of this checkout and all its git worktrees ~20 s after its
  files go quiet (re-imports when `judge.json` etc. change), laptop runs once the queue rsyncs them back; and tails
  `~/Zomboid/pzopt-overlay.out`, `pzopt-frames.out`, `pzopt-gamethread.out`, `pzopt-stacks.out`, `pzopt-input.out`, `pzopt-power.out` plus the
  running run's `sysmon.csv` into the `live_*` tables (desktop only, pruned after 6 h; unreferenced stack definitions hourly). Runs named with a timestamp before `harness/grafana/since` are skipped
  by the follower and `--all`; `ingest.py <run>` imports any run on purpose. `journalctl --user -u pzopt-grafana-follow`.
- **Dashboards** (generated by `dashboards.py`, provisioned read-only; edit the generator): *PZ runs* (table with links,
  trends per label, "below the cap with hardware left over" = the objective's finding), *PZ run* (tails, fps per second,
  histogram, percentile table incl. presented / GPU / flips, utilization, per-thread CPU, game-thread phases and
  top tables, chunks, GC, flips, drive, a flame graph with a profiler and thread picker (Grafana's flame graph panel with its
  top table; it follows the time range, so a drag across any time panel profiles just that window), samples per thread,
  schedmon CPU / run-queue per OS thread, PSI, memory, pacing, the inputs (keys / buttons timeline, analog axes, every
  event, the harness configuration), the console as a searchable log, counters), *PZ compare* (runs on one route-time
  axis, % change vs a base run, percentile bars, phase matrix, a diff flame graph base vs another run), *PZ live* (the game
  running now, 1 s refresh, with the last 10 s as a flame graph and the live inputs).

## Public dashboard (GCP, 2026-09-24)

https://pzo.diegov.dev (linked from the README; Cloud Run domain mapping, `cloud.sh domain`, DNS on Cloudflare: CNAME
`pzo` -> `ghs.googlehosted.com`, DNS only, or Google's certificate never issues; raw URL
https://pzopt-dashboard-20282332834.europe-west1.run.app): the same four dashboards, public, anonymous read-only, fed live
from the desktop. Project `diegov`, Cloud Run + Artifact Registry in europe-west1, the VM in us-central1 (the free tier is
US-only; the VPC is global and pg_hba's 10.128.0.0/9 covers the europe-west1 subnet, ~0.2 s a query), everything labelled `app=pzopt-dashboard`,
EUR 5 budget alert (20 / 50 / 100 %) on that label; `harness/grafana/cloud.sh deploy|url|status|seed|tunnel|ssh`.
- **Postgres** on `pzopt-db`, an e2-micro VM (the always-free tier: e2-micro + 30 GB pd-standard in us-central1), Debian 12,
  Postgres 15, 1 GB swap file, no external IP (charged since 2024; set up with a temporary one). Roles: `pzopt` (writer,
  only from the IAP range 35.235.240.0/20) and `grafana` (read-only, only from the VPC, `statement_timeout` 30 s, 10
  connections). Passwords in `~/.config/pzopt/grafana-remote.env` (0600), nowhere else.
- **Sync**: the desktop reaches it through IAP TCP forwarding (user unit `pzopt-grafana-tunnel`, localhost:15433; firewall
  rule `pzopt-db-allow-iap-pg`). `ingest.py` applies every run import and every live batch to both databases; a remote
  failure goes to `~/.local/state/pzopt-grafana/spool/` and replays in order (`cloud.sh status` counts it). `--no-remote`
  keeps a write local. After a schema change: apply `schema.sql` on both (`stack.sh up` locally, `cloud.sh seed` copies all).
- **Grafana** on Cloud Run `pzopt-dashboard`: the image from `harness/grafana/Dockerfile` (dashboards + `cloud/datasource.yaml`
  baked in, Grafana's own DB migrated at build time for fast cold starts, no login form / basic auth / explore / snapshots /
  plugin downloads, min refresh 5 s), built by Cloud Build's free default pool into Artifact Registry `pzopt` (the 2 newest
  images kept, under the free 0.5 GB). Scale to zero, max 2 instances, request-based CPU, 512 MiB, service account with no
  roles, Direct VPC egress to the VM's internal IP (no connector fee). Dashboard changes need `cloud.sh deploy` (~2 min).
- Anonymous viewers can post any read-only SQL to `/api/ds/query` (Grafana lets viewers query a datasource they can see):
  the `grafana` role only has SELECT, a 30 s statement timeout and 10 connections, and the instance cap bounds the cost.

## Pitfalls that already cost runs

- EXIT trap under `set -e`: a failing last command of an `&&` list aborts the restore.
  `restore()` does `set +e`. The maintainer's original MangoHud.conf was lost that way once.
- A run killed outright (SIGKILL, power cut, whole-system freeze) never reaches the EXIT trap, and the
  per-run `pzopt.properties` stays in the install dir: the Optimizations tab shows its keys pinned and a stock
  `--prop enabled=false` run leaves the mod OFF for the player (2026-09-24, the 01:47 freeze). run.sh's
  `recover_props` at start puts back `pzopt.properties.pzopt-orig` or removes a file identical to
  `runs/.last-props`. The other `.pzopt-orig` backups (launcher JSON, options.ini, MangoHud.conf) are still
  restored by hand after such a death.
- Another session's run.sh EXIT trap can fire after you launched behind their game and strip
  your flags (game sits at the main menu). Check for run.sh processes, not just the game.
- Do not add `no_display` or long lingers: The maintainer wants the overlay visible and the game to
  close by itself after a run.

`native-threads.sh <out> [start-after] [window]`: run beside `run.sh`; snapshots every native thread's CPU (driver workers, MangoHud, JIT) and the GL-related environment of the live game process. `pzopt-threads.out` only sees Java threads.
