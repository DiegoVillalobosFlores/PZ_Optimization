# Sound engine: profile, audio defects and fixes (2026-09-24)

Goal (maintainer): clean audio and a sound engine that costs next to nothing, however many sounds or zombies.

## Rig

- Scene: `--preset louisville` (downtown, population max: ~1,800-2,300 zombies) `--flag weather=storm` (rain, wind,
  a lightning strike and thunder every 6 s) `--flag house_alarm=12 --flag car_alarm=8` (a stock house alarm and a car
  alarm next to the start) `--flag gunshots=2` (pistol; stress: `gunshots=8 gunshot_weapon=Base.AssaultRifle`)
  `--flag helicopter=true --flag route=S:25 --flag speed=1 --route-seconds 25` (a slow spinning walk, the sources stay in
  earshot), `--prop uncappedFps=true`. Rig code: `pzopt.SoundProbe` (alarms, gunshots, a census of every FMOD emitter,
  instance, virtual voice and global parameter at 1-4 Hz, `pzopt-sound.out`).
- Audio: `--record --record-audio game` records only the game's PipeWire stream ("FMOD Audio"). `harness/audio.py`
  (loudness, peaks, spectrum, timeline), `harness/audio-judge.py` (cutoffs, ambience gaps, dropouts, clicks, clipping,
  true peak, reference comparison; Jev's verdict; in every queue result of a `--record-audio game` run).
- Cost: `--asprof event=cpu,interval=5ms,threads` + `harness/soundprof.py` (sound code on the game thread by entry
  point and leaf, FMOD's own threads).

## What the sound engine costs

- FMOD's threads: 0.10-0.12 of a core (mixer, stream, the JVM-attached Studio thread). They inherit the name of the
  thread that ran `FMOD_System_Init` (`MainThread` in stock, `pzopt-fmod-init` with BootAsync); async-profiler never
  samples the pure native ones, `/proc` schedstat does (`pzopt-sound.out`).
- Game thread, horde + storm + alarms + pistol, optimized before this pass: 5.3 % (0.53 ms of a 10 ms frame); the
  biggest part was every zombie's three idle emitters checked every frame (2.8 %). Stock on the same scene: 3.9 %
  of a 34 ms frame = 1.35 ms, 0.36 ms of it the house alarm's per-frame fish-scaring walk (`worldSoundFast` fixed that
  on 2026-09-22).
- After `emitterIdleSkip` + `soundTickHz` (+ `worldSoundCleanupFast`, `hearingHoist`: 2.55 % in `snd-l2-ws-prefix`): 2.5-2.9 % of a 9 ms frame = 0.24-0.28 ms, including under the assault-rifle
  stress (stock 1.38 ms a frame there). What is left is spread thin, no item above 0.6 %: the busy zombies' emitters,
  the listener's ambience parameters, zombie hearing (`getBiggestSoundZomb`, per chunk), the per-chunk world-sound
  cleanup (`IsoChunk.updateSounds`: a world sound lives 16 frames and the alarm adds one every frame), vehicles.

## Audio defects found (Jev + the judge's measurements)

| Defect | Stock | Cause | Fix |
|---|---|---|---|
| Clipping under load | 6,161 samples at full scale in 25 s with a pistol, 19,113 with an assault rifle; +0.7 dBTP | the game mixes 5.1 at 32 kHz on every device (`libfmodintegration64` hardcodes it); on a stereo device the OS mixer sums the six channels into two after FMOD. FMOD's own output stayed under 0.75 per channel (limiter metering) | `audioLimiter` + `audioLimiterStereoFold`: FMOD folds to stereo ahead of its limiter DSP at the master's head, ceiling -2 dBFS. 0 clipped samples, -0.3 / -0.4 dBTP |
| Thunder cut at the end of every run | a rolling thunder chopped in 25 ms | leaving the world stops every FMOD sound (`IngameState.exit` -> `SoundManager.stop` -> `ChannelGroup_Stop`); stock does the same on quit to menu | harness: `exit_fade_ms` (default 1500 in a sound run) ramps the master bus to silence before the quit |
| Rain gap | none in 19 stock runs | a 1.25-2.5 s, ~17 dB drop of the rain bed (6-16 kHz) at route +10..+13 s, right after the player walks out of the building, in about one optimized run in six uncapped (8/46, then 2/10 in `snd-occl-*`); none in 10 optimized runs capped at 30 fps (`snd-hunt-30fps-*`), the rate stock runs at in this scene: frame-rate linked (0/29 at ~30 fps vs 8/46, p ~ 0.02). Not a stopped or virtual event, not a global parameter, not CPU starvation (3.9 of 16 cores). The rain bed's `Occlusion` flips to 1 for ~0.1 s twice at the doorway (the listener's own square is briefly not `isCouldSee`) in every run, gap or not | open; `sound_param_log=true` now logs the rain emitter's Occlusion, `couldSee` and every WorldAmbiance core channel by name, virtual flag and audibility (`# ambch`, `# ambvirtual`) |
| World entry | the entry sting decays over ~5 s while the ambience fades in; the optimized build enters ~1.5 s sooner, so a quieter dip | load timing, not a defect | the judge starts its settle window 6 s after world-ready |

Voices: with the horde at a window, 150-180 instances play and FMOD keeps ~64 real (`fmod_channels=169/63`), the rest
virtual (mostly distant zombie thumps); the 64-voice budget is set inside the integration library's init.

## Verdicts (Jev, `audio-judge.py`)

| Run | Verdict | audio_clean | exit_clean |
|---|---|---|---|
| stock, pistol scene (`snd-i4-stock`) | distortion (6,161 clipped) | 0.01 | 0.94 (with the harness fade) |
| optimized, pistol scene (`snd-i4-opt`) | clean | 0.96 | 0.97 |
| stock, rifle stress (`snd-i6-stress-stock2`) | distortion (19,113 clipped) | 0.01 | 0.94 |
| optimized, rifle stress (`snd-i6-stress-opt`) | clean | 0.96 | 0.96 |

Calibration of the detectors on eight recordings: no cutoff in any scene window (alarm beeps, gunshots, impacts
included), the quit's thunder cut found in 5 of 6 unfaded exits and in none of the faded ones.

## Open

- The rain gap (above). Two candidate fixes wait on the channel census of a gap run: the WorldAmbiance event's channel priority (`FMOD_Studio_EventInstance_SetProperty` CHANNELPRIORITY 0) if a rain channel is voice-stolen, or treating the listener's own square as never occluding the ambience (stock already exempts it in FMODSoundEmitter) if it is the Occlusion filter (FMOD's audibility leaves filters out, so an unchanged audibility during a gap points there).
- The 32 kHz / 5.1 mix format and the 64-voice budget are fixed in the native init; changing them needs the Studio
  system before `FMOD_System_Init`, which the Java side never gets.
- Zombie hearing and the per-chunk world-sound cleanup scale with zombies x live world sounds (AI, not audio).
