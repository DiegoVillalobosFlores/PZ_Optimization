#!/usr/bin/env python3
"""Where the sound engine's time goes in a run: the game thread's sound code and FMOD's own native threads.

  harness/soundprof.py <run-dir> [<run-dir> ...] [--top 12] [--jfrconv PATH]

Game thread (pzopt-stacks.out, the in-game profiler, route window): every sampled stack that passes through
sound code (FMOD's Java side, emitters, sound parameters, the vocals / ambient / alarm / vehicle-sound managers,
world sounds = the zombies' hearing), as a share of all game-thread samples and in ms per frame (the route's mean
frame time from pzopt-sound.out or pzopt-bench.out). Broken down by entry point (the outermost sound frame of the
stack: who asked for the work) and by leaf (where it went: JNI into libfmod, Java bookkeeping, the world-sound
walks). The harness' own census (SoundProbe) is left out.

Native threads (asprof.jfr of a `run.sh --asprof event=cpu,interval=5ms,threads` run, route window, jfrconv): the
CPU of every FMOD thread (mixer, Studio update, streams, file / non-blocking loaders) and the audio client threads,
with their hottest native frames. Without asprof the per-thread CPU still comes from pzopt-sound.out
(/proc schedstat over the route, written by the sound probe).
"""
import argparse
import os
import subprocess
import sys
import tempfile
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from flamegraph import read_stacks, route_window  # noqa: E402

# class prefixes (simple names: the profiler folds frames as Class.method)
SOUND_CLASSES = (
    "javafmod", "javafmodJNI", "FMODManager", "FMODSoundEmitter", "FMODAudio", "FMODSoundBank", "FMODFootstep", "FMODVoice",
    "FMODParameter", "FMODGlobalParameter", "FMODLocalParameter", "FMODParameterList", "FMODParameterUtils", "FMODAmbientWalls",
    "BaseSoundEmitter", "BaseSoundManager", "SoundManager", "CharacterSoundEmitter", "BaseCharacterSoundEmitter",
    "DummyCharacterSoundEmitter", "DummySoundEmitter", "SoundListener", "BaseSoundListener", "GameSounds", "GameSound",
    "GameSoundClip", "SoundInstanceLimiter", "ZombieVocalsManager", "AmbientStreamManager", "BaseAmbientStreamManager",
    "Alarm", "VehicleSounds", "VehicleAlarm", "AlarmSound", "DoorAlarmSound", "EngineSound", "HornSound", "SirenSound",
    "VehicleHitCharacterSounds", "VehicleRunOverBodySounds", "ObjectAmbientEmitters", "TreeSoundManager", "SLSoundManager",
    "LoopedRangedWeaponSounds", "WorldSoundManager", "MusicIntensityEvents", "MusicThreatStatuses", "VoiceManager",
)
SOUND_METHODS = ("IsoGameCharacter.updateEmitter", "IsoZombie.updateEmitter", "IsoPlayer.updateEmitter", "BaseVehicle.updateSounds",
                 "BaseVehicle.doAlarm", "FishSchoolManager.addSoundNoise", "ZombiePopulationManager.addWorldSound",
                 "IsoGameCharacter.playSound", "IsoObject.playSound", "IsoGridSquare.playSound")


def is_sound(frame):
    if "FMOD" in frame and "." not in frame.split("(")[0][-40:]:  # a native libfmod frame (asprof)
        return True
    cls = frame.split(".", 1)[0].split("$", 1)[0]
    if cls == "SoundProbe":
        return False
    if cls.startswith("Parameter") and frame != "ParameterValue":  # zombie.audio.parameters.Parameter*
        return True
    return cls in SOUND_CLASSES or any(frame.startswith(m) for m in SOUND_METHODS)


def leaf_kind(stack):
    leaf = stack[-1]
    if leaf.startswith("javafmodJNI.") or leaf.startswith("javafmod."):
        return "JNI into libfmod: " + leaf.split(".", 1)[1]
    return leaf


def mean_frame_ms(run):
    p = Path(run) / "pzopt-sound.out"
    vals = []
    if p.exists():
        for line in p.read_text(errors="replace").splitlines():
            if "phase=route" in line:
                for tok in line.split():
                    if tok.startswith("frame_ms="):
                        vals.append(float(tok[9:]))
    if vals:
        return sum(vals) / len(vals), "pzopt-sound.out"
    kv = dict(l.split("=", 1) for l in (Path(run) / "pzopt-bench.out").read_text().splitlines() if "=" in l) if (Path(run) / "pzopt-bench.out").exists() else {}
    for k in ("frames", "route_frames"):
        if k in kv and "route_seconds" in kv:
            try:
                return float(kv["route_seconds"]) * 1000 / float(kv[k]), "pzopt-bench.out"
            except ValueError:
                pass
    return None, None


def norm_frame(f):
    """asprof frame -> the in-game profiler's Class.method: 'zombie/audio/FMODSoundEmitter.tick_[j]' -> 'FMODSoundEmitter.tick';
    native frames keep their symbol (FMOD's C++ ones contain 'FMOD')."""
    f = f.rsplit("_[", 1)[0]
    if "/" in f and "." in f:
        f = f.rsplit("/", 1)[1]
    return f


def asprof_collapsed(run, jfrconv, win):
    jfr = Path(run) / "asprof.jfr"
    if not (jfr.exists() and win and Path(jfrconv).exists()):
        return None
    with tempfile.TemporaryDirectory() as td:
        out = Path(td) / "c.txt"
        subprocess.run([jfrconv, "--cpu", "-t", "--from", str(win[0]), "--to", str(win[1]), "-o", "collapsed", str(jfr), str(out)],
                       capture_output=True, timeout=300)
        return out.read_text(errors="replace").splitlines() if out.exists() else None


def game_thread(run, top, jfrconv):
    win = route_window(run)
    lines = asprof_collapsed(run, jfrconv, win)
    if lines:
        stacks = Counter()
        for line in lines:
            st, _, n = line.rpartition(" ")
            fr = st.split(";")
            if n.isdigit() and fr[0].startswith("[MainThread"):
                stacks[tuple(norm_frame(f) for f in fr[1:])] += int(n)
        seconds, hz = (win[1] - win[0]) / 1000, "asprof cpu 200"
    else:
        _, stacks, samples, seconds, hz = read_stacks(run, win)
    total = sum(stacks.values())
    if not total:
        print("  game thread: no samples in the route window")
        return
    fms, src = mean_frame_ms(run)
    entry, leaf, sound = Counter(), Counter(), 0
    probe = sum(c for st, c in stacks.items() if any(f.startswith("SoundProbe.") for f in st))
    for st, c in stacks.items():
        if any(f.startswith("SoundProbe.") for f in st):
            continue
        idx = next((i for i, f in enumerate(st) if is_sound(f)), None)
        if idx is None:
            continue
        sound += c
        # entry = the caller of the outermost sound frame + that frame (what asked for sound work)
        entry[(st[idx - 1] + " > " if idx > 0 else "") + st[idx]] += c
        leaf[leaf_kind(st)] += c
    pct = lambda c: 100.0 * c / total
    ms = lambda c: f"{pct(c) / 100 * fms:6.3f} ms/frame" if fms else ""
    print(f"  game thread, route window: {total} samples over {seconds:.0f} s ({hz or '?'} Hz); mean frame "
          + (f"{fms:.2f} ms ({src})" if fms else "unknown"))
    print(f"  sound code: {pct(sound):5.2f} % of the game thread  {ms(sound)}   (the harness probe itself: {pct(probe):.2f} %, left out)")
    print("  by entry point (caller > outermost sound frame):")
    for k, c in entry.most_common(top):
        print(f"    {pct(c):6.2f} %  {ms(c)}  {k}")
    print("  by leaf (where the time went):")
    for k, c in leaf.most_common(top):
        print(f"    {pct(c):6.2f} %  {ms(c)}  {k}")
    jni = sum(c for k, c in leaf.items() if k.startswith("JNI"))
    print(f"  JNI into libfmod from the game thread: {pct(jni):.2f} %  {ms(jni)}")


def native(run, top, jfrconv):
    """FMOD's own threads. FMOD never names its threads on Linux: they inherit the name of the thread that ran
    FMOD_System_Init (MainThread in stock, pzopt.BootAsync's pzopt-fmod-init with the optimizations on) and are created
    back to back. async-profiler samples only the one FMOD attaches to the JVM for its callbacks ('Thread-N', found by its
    libfmod frames); the pure native ones (mixer, stream) it never sees. So: the attached thread's tid from asprof, then
    its siblings (tids within 3, same inherited name, not a Java thread asprof knows) with their CPU from the probe's
    /proc table in pzopt-sound.out."""
    win = route_window(run)
    lines = asprof_collapsed(run, jfrconv, win) or []
    per_thread, leaves, fmod, java_tids = Counter(), {}, Counter(), set()
    for line in lines:
        st, _, n = line.rpartition(" ")
        if not n.isdigit():
            continue
        fr = st.split(";")
        n = int(n)
        th = fr[0].strip("[]")
        if " tid=" in th and not th.startswith("tid="):
            java_tids.add(th.rsplit("tid=", 1)[1])
        per_thread[th] += n
        leaves.setdefault(th, Counter())[fr[-1].rsplit("/", 1)[-1]] += n
        if any("libfmod" in f or f.startswith("FMOD::") for f in fr[1:]):
            fmod[th] += n
    attached = [th.rsplit("tid=", 1)[1] for th in fmod if not th.startswith("MainThread")]
    p = Path(run) / "pzopt-sound.out"
    rows = []
    if p.exists():
        text = p.read_text(errors="replace").splitlines()
        i = next((i for i, l in enumerate(text) if l.startswith("# native threads")), None)
        if i is not None:
            rows = [l.split("\t") for l in text[i + 2:] if l.count("\t") >= 4]
            by_tid = {r[1]: r for r in rows}
            fm = set()
            for t in attached:
                if t in by_tid:
                    fm.add(t)
                    for d in range(-3, 4):
                        s2 = str(int(t) + d)
                        if s2 in by_tid and by_tid[s2][0] == by_tid[t][0] and (s2 == t or s2 not in java_tids):
                            fm.add(s2)
            fm |= {r[1] for r in rows if is_audio_thread(r[0])}
            audio = sorted((by_tid[t] for t in fm), key=lambda r: -float(r[3]))
            print("  native threads (/proc schedstat over the route, pzopt-sound.out): " + text[i][2:].split(", audio threads")[0].split(": ", 1)[1])
            print(f"    FMOD threads together: {sum(float(r[3]) for r in audio):.3f} cores"
                  + ("" if attached else "  (no asprof.jfr: found by name only, stock FMOD threads are called MainThread)"))
            for r in audio[:top]:
                print(f"    {float(r[3]):6.3f} cores  {r[0]} (tid {r[1]}){'  [JVM-attached, asprof sees it]' if r[1] in attached else ''}")
            print("    busiest threads overall: " + ", ".join(f"{r[0]} {float(r[3]):.2f}" for r in rows[:6]))
    if not rows:
        print("  native threads: no thread table in pzopt-sound.out")
    if fmod:
        secs = (win[1] - win[0]) / 1000
        print("  threads with libfmod frames in asprof (cpu, 5 ms samples):")
        for th, c in fmod.most_common(top):
            hot = ", ".join(f"{f} {100 * k / per_thread[th]:.0f}%" for f, k in leaves[th].most_common(4))
            print(f"    {th:<32} {c * 0.005 / secs:6.3f} cores in FMOD of {per_thread[th] * 0.005 / secs:.3f}; hot: {hot}")


def is_audio_thread(name):
    """FMOD never names its threads on Linux: they inherit the name of the thread that ran FMOD_System_Init, which is
    pzopt.BootAsync's 'pzopt-fmod-init' with the optimizations on (the one FMOD attaches to the JVM for its callbacks
    shows up as 'Thread-N' and is found by its libfmod frames instead)."""
    c = name.lower()
    return c.startswith("fmod") or c.startswith("pzopt-fmod-init") or "pulse" in c or "pipewire" in c or c.startswith("pw-") or "alsa" in c or "audio" in c


def census(run):
    p = Path(run) / "pzopt-sound.out"
    if not p.exists():
        return
    rows = []
    for line in p.read_text(errors="replace").splitlines():
        if line.startswith("t="):
            rows.append(dict(tok.split("=", 1) for tok in line.split() if "=" in tok))
    route = [r for r in rows if r.get("phase") == "route"]
    settle = [r for r in rows if r.get("phase") == "settle"]

    def mean(rs, k):
        v = [float(r[k]) for r in rs if k in r]
        return sum(v) / len(v) if v else float("nan")
    print("  sound census (mean per second, settle -> route):")
    for k, label in (("zombies", "zombies loaded"), ("emitters", "emitters registered"), ("active", "emitters holding a sound"),
                     ("instances", "sound instances playing"), ("stopping", "instances stopping"), ("vocal_slots", "zombie vocal slots"),
                     ("world_sounds", "world sounds live"), ("frame_ms", "frame ms"), ("probe_ms", "census cost ms")):
        print(f"    {label:<26} {mean(settle, k):8.1f} -> {mean(route, k):8.1f}")
    if route:
        last = route[-1]
        print(f"    by emitter type at the end (emitters/holding/instances): voice {last.get('voice')} footstep {last.get('footstep')} "
              f"extra {last.get('extra')} other {last.get('other')}")
        print(f"    alarms at the end: house {last.get('house_alarm')} (dist {last.get('house_dist')}), car {last.get('car_alarm')}")
        print(f"    most common sounds at the end: {last.get('top')}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--top", type=int, default=12)
    ap.add_argument("--jfrconv", default=os.path.expanduser("~/.local/share/async-profiler/async-profiler-4.1-linux-x64/bin/jfrconv"))
    a = ap.parse_args()
    for run in a.runs:
        print(Path(run).name)
        census(run)
        game_thread(run, a.top, a.jfrconv)
        native(run, a.top, a.jfrconv)
        print()


if __name__ == "__main__":
    main()
