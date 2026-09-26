#!/usr/bin/env python3
"""Problem / fix video of the stairs wall flicker (2026-09-26), from explore=stairs walks.

    harness/stitch-stairs-flicker.py --flip PROBLEM FIX [--desk PROBLEM FIX] out.mp4 [--mask x0,y0,x1,y1] [--jev f.json]

Part 1, the wall flicker, where it was reported (the flip, Radeon 890M): two walks with a colour `devCapture` (every presented
frame read back in game, a third of 1920x1080; the flip has no screen recorder). Jev directs each walk live, so the walks are
cut at their matching `harness: stairs:` events and every segment starts together (the longer one trimmed); real time,
then close-ups of the problem run's worst blinking wall patches (stairs-flicker.py's solid patches, outside the floor
arrivals) and the same moment of the fix run, every captured frame held two video frames (~0.27x). SDR, mapped to PQ at
203 nits reference white (encode-av1-hdr.sh's mapping), never tone-mapped.
Part 2, the floor arrival, on the desktop with HDR + VRR + every optional setting: two `--record` walks (AV1 HDR, 60 fps);
the recorder's start epoch comes from cross-correlating the recording's mean luma with the capture's; the arrival on the
ground floor from the basement in real time, then at 0.25x.
Output: AV1 10-bit PQ / BT.2020, 5120 wide, each panel half the width.
"""
import argparse
import json
import os
import re
import subprocess
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = "/usr/share/fonts/noto/NotoSans-Bold.ttf"
if not os.path.exists(FONT):
    FONT = subprocess.run(["fc-match", "-f", "%{file}", "DejaVu Sans:bold"], capture_output=True, text=True).stdout
TMP = "/tmp/stitch-stairs"


def events(run):
    ev = []
    for ln in open(os.path.join(run, "console.txt"), errors="replace"):
        m = re.search(r"harness: stairs: (.*?) at \+[\d.]+ s epoch=(\d+)", ln)
        if m:
            ev.append((int(m.group(2)) / 1000.0, m.group(1).split(":")[0]))
    return ev


class Capture:
    def __init__(self, run):
        d = os.path.join(run, "capture")
        lines = open(os.path.join(d, "index.txt")).read().split("\n")
        head = dict(kv.split("=") for kv in lines[0].split())
        self.w, self.h = int(head["w"]), int(head["h"])
        st = [int(x) for x in lines[1:] if x.strip()]
        while len(st) > 1 and st[-1] < st[-2]:
            st.pop()
        self.gray = head.get("fmt") == "gray"
        self.bpp = 1 if self.gray else 4
        self.raw = np.memmap(os.path.join(d, "frames.gray" if self.gray else "frames.rgba"), dtype=np.uint8, mode="r")
        n = min(len(st), self.raw.size // (self.w * self.h * self.bpp))
        self.t = np.array(st[:n], dtype=np.float64) / 1000.0

    def rgb(self, i):
        f = self.raw[i * self.w * self.h * self.bpp:(i + 1) * self.w * self.h * self.bpp]
        if self.gray:
            g = f.reshape(self.h, self.w)[::-1]
            return np.repeat(g[:, :, None], 3, axis=2)
        return f.reshape(self.h, self.w, 4)[::-1, :, :3]

    def luma(self):
        return np.array([self.raw[i * self.w * self.h * self.bpp:(i + 1) * self.w * self.h * self.bpp].reshape(-1, self.bpp)[:, :min(self.bpp, 3)].mean()
                         for i in range(len(self.t))])

    def at(self, epoch):
        return int(min(len(self.t) - 1, max(0, np.searchsorted(self.t, epoch))))


def recording_luma(run):
    rec = os.path.join(run, "recording.mp4")
    pts = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts_time", "-of", "csv=p=0", rec],
                         capture_output=True, text=True, check=True).stdout.split()
    pts = np.array([float(x.strip(",")) for x in pts if x.strip(",")])
    p = subprocess.run(["ffmpeg", "-v", "error", "-i", rec, "-fps_mode", "passthrough", "-vf", "scale=160:68,format=gray", "-f", "rawvideo", "-"],
                       capture_output=True, check=True).stdout
    fr = np.frombuffer(p, dtype=np.uint8).reshape(-1, 68 * 160)
    n = min(len(pts), len(fr))
    return pts[:n], fr[:n].mean(axis=1)


def rec_start_epoch(run):
    """The recording's time 0 as an epoch (s): the lag that best lines up the changes of the two luma curves."""
    cap = Capture(run)
    ct, cl = cap.t, cap.luma()
    rt, rl = recording_luma(run)
    opts = dict(l.strip().split("=", 1) for l in open(os.path.join(run, "run.opts")) if "=" in l)
    launch = float(opts["launch_epoch"])
    grid = np.arange(ct[0], ct[-1], 0.005)
    dc = np.diff(np.interp(grid, ct, cl))
    dc = (dc - dc.mean()) / (dc.std() + 1e-9)
    best = (-1e9, launch)
    for r0 in np.arange(launch - 4.0, launch + 4.0, 0.005):
        v = np.interp(grid - r0, rt, rl, left=np.nan, right=np.nan)
        if np.isnan(v).mean() > 0.2:
            continue
        dv = np.diff(np.nan_to_num(v, nan=np.nanmean(v)))
        dv = (dv - dv.mean()) / (dv.std() + 1e-9)
        s = float((dc * dv).mean())
        if s > best[0]:
            best = (s, r0)
    return best[1], best[0]


def segments(ev, lo, hi):
    """Matching segments of two walks: (name, [start epoch a, b], duration), clipped to the capture windows [lo, hi]."""
    names = [e[1] for e in ev[0]]
    if names != [e[1] for e in ev[1]]:
        sys.exit(f"the walks differ: {names} vs {[e[1] for e in ev[1]]}")
    out = []
    for k in range(len(names)):
        t0 = [ev[i][k][0] - (1.0 if k == 0 else 0.0) for i in range(2)]
        t1 = [ev[i][k + 1][0] if k + 1 < len(names) else ev[i][k][0] + 1.5 for i in range(2)]
        for i in range(2):  # inside both captures
            t0[i] = max(t0[i], lo[i])
            t1[i] = min(t1[i], hi[i])
        d = min(t1[0] - t0[0], t1[1] - t0[1])
        if d > 0.05:
            out.append((names[k], t0, d))
    return out


def encode_pane(path, frames_iter, w, h):
    """Raw RGB frames at 60 fps into a lossless intermediate."""
    p = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-r", "60", "-i", "-",
                          "-c:v", "ffv1", "-pix_fmt", "bgr0", path], stdin=subprocess.PIPE)
    n = 0
    for f in frames_iter:
        p.stdin.write(np.ascontiguousarray(f).tobytes())
        n += 1
    p.stdin.close()
    p.wait()
    return n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--flip", nargs=2, required=True, metavar=("PROBLEM", "FIX"))
    ap.add_argument("--desk", nargs=2, metavar=("PROBLEM", "FIX"))
    ap.add_argument("out")
    ap.add_argument("--mask", action="append", default=[])
    ap.add_argument("--slow", type=int, default=2)
    ap.add_argument("--jev", help="stairs-flicker.py --judge JSON of the flip pair (the strip quotes it)")
    a = ap.parse_args()
    os.makedirs(TMP, exist_ok=True)
    mk = [x for m in a.mask for x in ("--mask", m)]

    # ---- part 1: the flip captures
    caps = [Capture(r) for r in a.flip]
    ev = [events(r) for r in a.flip]
    segs = segments(ev, [c.t[0] for c in caps], [c.t[-1] for c in caps])
    sf = []
    for r, crops in zip(a.flip, (40, 0)):
        j = f"{TMP}/sf-{os.path.basename(r)}.json"
        subprocess.run([sys.executable, os.path.join(HERE, "stairs-flicker.py"), r, "--crops", str(crops), "--json", j, *mk], capture_output=True, check=True)
        sf.append(json.load(open(j))["test"])
    arrivals = [e["t_s"] for e in sf[0]["events"] if e["what"].startswith("level")]
    slows = []
    for c in sf[0].get("crops", []):
        epoch = caps[0].t[0] + c["t_s"]
        if not ev[0][0][0] <= epoch <= ev[0][-1][0] or any(-0.3 <= c["t_s"] - t <= 1.6 for t in arrivals) or any(abs(epoch - s[0]) < 3.0 for s in slows):
            continue
        k = max(i for i, e in enumerate(ev[0]) if e[0] <= epoch)
        ef = ev[1][k][0] + (epoch - ev[0][k][0])
        x0, y0, x1, y1 = c["box"]
        slows.append((epoch, ef, (x0 + x1) / 2, (y0 + y1) / 2, ev[0][k][1]))
        if len(slows) >= a.slow:
            break
    W1, H1 = caps[0].w, caps[0].h
    zw, zh = W1 // 2 - W1 // 2 % 2, H1 // 2 - H1 // 2 % 2  # the close-up: a quarter of the capture, shown at the pane size

    def real(i):
        c = caps[i]
        for nm, t0, d in segs:
            for j in range(int(d * 60)):
                yield c.rgb(c.at(t0[i] + j / 60.0))

    def slow(i):
        c = caps[i]
        for s in slows:
            x = int(min(max(0, s[2] - zw / 2), W1 - zw))
            y = int(min(max(0, s[3] - zh / 2), H1 - zh))
            for f in range(c.at(s[i] - 0.6), c.at(s[i] + 0.6) + 1):
                im = np.repeat(np.repeat(c.rgb(f)[y:y + zh, x:x + zw], 2, axis=0), 2, axis=1)
                yield im
                yield im  # two video frames per captured frame
    n_real = n_slow = 0
    for i, tag in ((0, "old"), (1, "fix")):
        n_real = encode_pane(f"{TMP}/a-{tag}.mkv", real(i), W1, H1)
        n_slow = max(n_slow, encode_pane(f"{TMP}/s-{tag}.mkv", slow(i), zw * 2, zh * 2))
    d_real, d_slow = n_real / 60.0, n_slow / 60.0
    print(f"part 1: {len(segs)} segments {d_real:.1f} s real time; close-ups {d_slow:.1f} s at "
          + ", ".join(f"{s[4]} (+{s[0] - ev[0][0][0]:.1f} s)" for s in slows), file=sys.stderr)

    # ---- part 2: the desktop recordings, the arrival on the ground floor
    d_arr = d_arr_slow = 0.0
    desk = []
    if a.desk:
        for r in a.desk:
            r0, score = rec_start_epoch(r)
            arr = [e[0] for e in events(r) if e[1] == "level 0"][0]
            desk.append(arr - r0)
            print(f"{r}: recording starts at epoch {r0:.3f} (correlation {score:.2f}), ground-floor arrival at {arr - r0:.2f} s", file=sys.stderr)
        d_arr, d_arr_slow = 3.0, 4.0  # real time -1.2 .. +1.8 s, then -0.2 .. +0.8 s at 0.25x

    # ---- compose
    W, PW, PH = 5120, 2560, 1080
    TOP, LAB, STRIP = 130, 64, 240
    H = TOP + LAB + PH + STRIP
    TXT, DIM = "0xE8E8EC", "0x9A9AA6"
    sdr2pq = ("format=yuv420p,setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv,zscale=t=linear:npl=203,format=gbrpf32le,"
              "zscale=pin=bt709:tin=linear:p=bt2020:t=smpte2084:m=bt2020nc:r=tv:npl=203,format=yuv420p10le")
    inputs = ["-i", f"{TMP}/a-old.mkv", "-i", f"{TMP}/a-fix.mkv", "-i", f"{TMP}/s-old.mkv", "-i", f"{TMP}/s-fix.mkv"]
    if a.desk:
        inputs += ["-i", os.path.join(a.desk[0], "recording.mp4"), "-i", os.path.join(a.desk[1], "recording.mp4")]
    fc = []
    for i in range(2):
        fc.append(f"[{i}:v]scale=1920:1080:flags=bicubic,{sdr2pq},pad={PW}:{PH}:(ow-iw)/2:0:black,fps=60[a{i}]")
        fc.append(f"[{2 + i}:v]scale=1920:1080:flags=neighbor,{sdr2pq},pad={PW}:{PH}:(ow-iw)/2:0:black,fps=60,tpad=stop_mode=clone:stop_duration={d_slow:.2f},trim=duration={d_slow:.3f}[s{i}]")
        parts = f"[a{i}][s{i}]"
        n = 2
        if a.desk:
            t = desk[i]
            fc.append(f"[{4 + i}:v]trim=start={t - 1.2:.3f}:duration=3.0,setpts=PTS-STARTPTS,fps=60,scale={PW}:{PH}:flags=lanczos,format=yuv420p10le[r{i}]")
            fc.append(f"[{4 + i}:v]trim=start={t - 0.2:.3f}:duration=1.0,setpts=4*(PTS-STARTPTS),fps=60,scale={PW}:{PH}:flags=lanczos,format=yuv420p10le[q{i}]")
            parts += f"[r{i}][q{i}]"
            n = 4
        fc.append(parts + f"concat=n={n}:v=1:a=0[p{i}]")
    t1, t2, t3 = d_real, d_real + d_slow, d_real + d_slow + d_arr
    dur = t3 + d_arr_slow if a.desk else t2

    def dt(text, size, color, x, y, start=None, end=None):
        text = text.replace(":", "\\:").replace("'", "").replace("%", " pct").replace(",", "\\,")
        en = f":enable='between(t\\,{start:.2f}\\,{end:.2f})'" if start is not None else ""
        return f"drawtext=fontfile={FONT}:text='{text}':fontsize={size}:fontcolor={color}:x={x}:y={y}{en}"
    wp, wf = sf[0]["whole"], sf[1]["whole"]
    jev = ""
    if a.jev and os.path.exists(a.jev):
        ans = json.load(open(a.jev))["jev"]["answers"]
        yn = lambda q: f"{ans[q]['noul']:.2f}" if q in ans and "noul" in ans[q] else "?"
        jev = f"Jev (numbers only): flicker in the fix run {yn('flicker_in_test')}  -  fix better than the problem run {yn('better_than_before')}"
    walk = "the same Jev-directed walk: basement, up to the top floor, back to the basement (cut at each floor change so both start together)"
    txt = [
        dt("Project Zomboid B42  |  walls flicker on the stairs with every lighting setting on  |  problem vs fix", 58, TXT, "(w-tw)/2", 34),
        dt("PROBLEM: the released per-pixel lighting (pplTexelPos=false, bakeLevelChangeFrames=0)", 38, "0xFF9A7A", 24, TOP + 12),
        dt("FIX: torch normal from the baked texture (pplTexelPos) + the new floor baked at once (bakeLevelChangeFrames)", 38, "0x8AE0A0", PW + 24, TOP + 12),
        dt("1/3  flip (Radeon 890M, 1920x1080, FSR 1, the report settings): every presented frame read back in game, real time. " + walk, 32, DIM, "(w-tw)/2", TOP + LAB + PH + 20, 0, t1),
        dt("2/3  flip: close-ups of lit walls while walking, every presented frame held for 2 video frames (0.27x)", 32, DIM, "(w-tw)/2", TOP + LAB + PH + 20, t1, t2),
        dt(f"blinking wall and light patches (px per frame)   problem {wp['solid_px_per_frame']}   -   fix {wf['solid_px_per_frame']}", 40, TXT, "(w-tw)/2", TOP + LAB + PH + 80, 0, t2),
        dt(jev or " ", 36, TXT, "(w-tw)/2", TOP + LAB + PH + 150, 0, t2),
    ]
    if a.desk:
        txt += [
            dt("3/3  desktop (RTX 4090, 5120x2160, HDR, VRR 165 Hz, every optional setting on, FSR 1), screen recording: arriving on the ground floor from the basement, real time, then 0.25x", 32, DIM, "(w-tw)/2", TOP + LAB + PH + 20, t2, dur),
            dt("problem: the floor is rebuilt in pieces over about 0.2 s (4 blinking frames)   -   fix: one clean change", 40, TXT, "(w-tw)/2", TOP + LAB + PH + 80, t2, dur),
            dt("on this desktop the torch wall flicker itself hardly shows (DLSS hides it completely): the flip Radeon shows it", 34, DIM, "(w-tw)/2", TOP + LAB + PH + 150, t2, dur),
        ]
    fc.append(f"color=c=0x060608:s={W}x{H}:r=60:d={dur:.3f},format=yuv420p10le[bg]")
    fc.append(f"[bg][p0]overlay=0:{TOP + LAB}:shortest=1[b1]")
    fc.append(f"[b1][p1]overlay={PW}:{TOP + LAB}:shortest=1,drawbox=x={PW - 2}:y={TOP}:w=4:h={LAB + PH}:color=0x202026:t=fill,"
              + ",".join(txt) + ",setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]")
    cmd = ["ffmpeg", "-hide_banner", "-v", "error", "-y", *inputs, "-filter_complex", ";".join(fc), "-map", "[v]", "-an",
           "-c:v", "av1_nvenc", "-preset", "p7", "-tune", "hq", "-rc", "vbr", "-cq", "24", "-b:v", "0", "-maxrate", "100M", "-bufsize", "200M",
           "-pix_fmt", "p010le", "-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc", "-color_range", "tv",
           "-movflags", "+faststart", a.out]
    print(f"encoding {dur:.1f} s -> {a.out}", file=sys.stderr)
    subprocess.run(cmd, check=True)
    subprocess.run(["ffmpeg", "-hide_banner", "-v", "error", "-y", "-ss", f"{min(10.0, dur / 3):.1f}", "-i", a.out, "-frames:v", "1", "-vf",
                    "zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p",
                    "-q:v", "2", os.path.splitext(a.out)[0] + ".jpg"])


if __name__ == "__main__":
    main()
