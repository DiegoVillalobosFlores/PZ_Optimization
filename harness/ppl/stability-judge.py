#!/usr/bin/env python3
"""Visual stability of one harness recording, judged by Jev (2026-09-25, the per-pixel lighting restaurant walk).

    harness/ppl/stability-judge.py <run dir> START END [--seg 10] [--scale 1280] [--context "..."] [--json out.json]

No reference recording: every visual property is a number from parity-judge.py's measure() (flicker.py's A-B-A transient
detector, hard luma jumps and solid jump blocks at 30 fps, black share and jumps, luma pops), computed per SEG-second
segment of [START, END) seconds of <run>/recording.mp4. Jev (text-only, it never sees a frame) reads the segment table
with the scene's context and answers: what kind of instability if any, whether the picture is stable, whether a person
should watch it, and which segment is the worst. Exit 0 when Jev calls it stable.
"""
import argparse
import importlib.util
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from typesafe_client import ask, choice, noul, fmt  # noqa: E402

_spec = importlib.util.spec_from_file_location("parity_judge", HERE.parent / "parity-judge.py")
pj = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pj)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("start", type=float)
    ap.add_argument("end", type=float)
    ap.add_argument("--seg", type=float, default=10.0)
    ap.add_argument("--scale", type=int, default=1280)
    ap.add_argument("--context", default="")
    ap.add_argument("--json")
    a = ap.parse_args()
    video = pj.video_of(a.run)
    segs = []
    t = a.start
    while t < a.end - 1.0:
        e = min(a.end, t + a.seg)
        m = pj.measure(video, t, e, a.scale)
        m.pop("video", None)
        segs.append(m)
        print(f"{t:6.1f}-{e:6.1f} s: transients {m['transient_px_per_frame']['per_mille_of_frame_mean']:.3f} per mille "
              f"(max {m['transient_px_per_frame']['max']} px), hard jumps mean {m['hard_luma_jumps_px_per_frame_30fps']['mean']} "
              f"p90 {m['hard_luma_jumps_px_per_frame_30fps']['p90']}, solid blocks max {m['solid_jump_blocks_per_frame_30fps']['max']}, "
              f"black jumps {m['black_share']['jumps_over_2pct']}, luma pops {m['luma_0_255']['pops_over_6']} (largest {m['luma_0_255']['largest_step']})")
        t = e
    names = {f"seg{i}": f"{s['window_s'][0]}-{s['window_s'][1]} s" for i, s in enumerate(segs)}
    state = {
        "scene": a.context or "one harness recording",
        "how_to_read": "Metrics per segment of one recording, no reference. A walking, turning character and a torch beam "
                       "sweeping with the facing change the picture smoothly: that is motion, not instability. Instability is "
                       "things blinking for 1-3 frames (transient px, A-B-A), whole areas or lights jumping in brightness "
                       "between consecutive frames (hard luma jumps, solid jump blocks: a chunk-sized patch of light landing "
                       "at once), black squares popping in (black jumps), or the whole picture pulsing (luma pops). Transients "
                       "near 0.1-0.5 per mille of the frame are the level of a stable scene with a moving character; several "
                       "per mille, or single segments far above the others, point at flicker.",
        "segments": {k: segs[i] for i, k in enumerate(names)},
    }
    questions = {
        "kind": choice({"question": "What best describes the visual stability of this recording?"},
                       {"stable": "no segment shows blinking, light jumps, black squares or pulsing beyond what the moving "
                                  "character and the sweeping torch beam explain",
                        "light_flicker": "lights or lit areas blink or pulse: hard luma jumps / luma pops well above the other "
                                         "segments, without a matching movement",
                        "sprite_flicker": "sprites or objects blink in and out for a few frames: transient px clearly high",
                        "lighting_patchwork": "chunk-sized patches of light land at once: solid jump blocks well above zero",
                        "black_tiles": "black squares pop in: black jumps",
                        "inconclusive": "too few frames or metrics that contradict each other"}),
        "stable": noul("Is the picture visually stable throughout: no flickering lights, no blinking objects, no light patches or black squares popping in?"),
        "look_needed": noul("Should a person watch the recording because a segment moved enough to maybe be an artifact but not enough to be certain?"),
        "worst": choice({"question": "Which segment looks the least stable (the one to watch first)?"},
                        {k: f"the segment at {v}" for k, v in names.items()}),
    }
    log = []
    answers = ask(state, questions, log=log)
    print("jev:")
    print("  " + fmt(answers).replace("\n", "\n  "))
    print(f"  ({log[0]['ms']} ms, {log[0]['model']})")
    if a.json:
        Path(a.json).write_text(json.dumps({"state": state, "answers": answers, "typesafe": log[0], "segments_s": names}, indent=1))
    return 0 if answers["kind"]["choice"] == "stable" and answers["stable"]["noul"] >= 0.7 else 1


if __name__ == "__main__":
    sys.exit(main())
