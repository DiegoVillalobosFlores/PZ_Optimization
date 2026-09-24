#!/usr/bin/env python3
"""When each part of the screen appears as the world builds itself on world entry, from a run's recording.

    harness/revealmap.py <run dir | recording.mp4> [--seconds 6] [--width 640] [--thresh 6] [--geom <properties> [--fit]]
                         [--out <png>]

The recording starts at launch; the world entry is the end of the last stretch of black frames (the noLoadingScreen
loading frame, run with --prop resumeShot=false to see the live world build, or with the shot on to see the fake one).
From there every pixel of a downscaled copy gets the time it turns non-black and stays so for the rest of the window.
Prints the reveal curve (share of the screen shown over time), the reveal time against the distance from the screen
centre, and the per-chunk-diamond spread when the save's pzopt-resume.properties geometry is given (--geom), and
writes a false-colour time map (<run>/revealmap.png: black = never, blue = first, red = last). --fit keeps the
geometry's chunk vectors (same zoom) and fits the grid offset to the map. Decode it through the queue (`queue.sh submit
media ... -- python3 harness/revealmap.py <run> ...`). First use: run worldload-rec2 (2026-09-24), the model of
pzopt.ResumeShot's chunk bursts.
"""
import argparse
import os
import subprocess
import sys

import numpy as np


def probe(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                          "stream=width,height,r_frame_rate", "-of", "csv=p=0", path],
                         capture_output=True, text=True, check=True).stdout.strip().split(",")
    w, h = int(out[0]), int(out[1])
    num, den = out[2].split("/")
    return w, h, float(num) / float(den)


def frames(path, w, h, start=None, dur=None, fps=None):
    cmd = ["ffmpeg", "-v", "error"]
    if start is not None:
        cmd += ["-ss", f"{start:.3f}"]
    cmd += ["-i", path]
    if dur is not None:
        cmd += ["-t", f"{dur:.3f}"]
    vf = f"scale={w}:{h}:flags=area,format=gray"
    if fps:
        vf = f"fps={fps}," + vf
    cmd += ["-vf", vf, "-f", "rawvideo", "-pix_fmt", "gray", "-"]
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    n = w * h
    while True:
        b = p.stdout.read(n)
        if len(b) < n:
            break
        yield np.frombuffer(b, np.uint8).reshape(h, w)
    p.wait()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("--seconds", type=float, default=6.0, help="window after world entry")
    ap.add_argument("--width", type=int, default=640)
    ap.add_argument("--thresh", type=int, default=6, help="grey level above the black frame that counts as shown")
    ap.add_argument("--geom", help="pzopt-resume.properties of the save (screen geometry of the player's chunk)")
    ap.add_argument("--fit", action="store_true",
                    help="keep the geometry's chunk vectors, fit the grid offset to the map (the player moved since)")
    ap.add_argument("--out")
    a = ap.parse_args()

    path = os.path.join(a.src, "recording.mp4") if os.path.isdir(a.src) else a.src
    run_dir = a.src if os.path.isdir(a.src) else os.path.dirname(path)
    W, H, fps = probe(path)

    # pass 1: share of the screen lit per frame at 10 fps; the world is the first time a fifth of the screen is lit,
    # its entry the last sample before that with (almost) nothing lit (a cursor or a tips line stays under 2 %)
    shares = np.array([(f > a.thresh).mean() for f in frames(path, 320, max(2, 320 * H // W), fps=10)])
    # (the desktop before the game window and the menu are lit too: only a half-screen fill after >= 0.5 s of black counts)
    k, run = 0, 0
    for n, s in enumerate(shares):
        if s < 0.02:
            run += 1
        elif s > 0.5 and run >= 5:
            k = n
            break
        elif s > 0.5:
            run = 0
    while k > 0 and shares[k - 1] >= 0.02:
        k -= 1
    if k == 0:
        print("lit share per 0.1 s: " + " ".join(f"{s * 100:.0f}" for s in shares), file=sys.stderr)
        sys.exit("no black loading frame before the world in " + path)
    j = k - 1
    while j > 0 and shares[j - 1] < 0.02:
        j -= 1
    black_from, first_content = j / 10.0, k / 10.0
    print(f"recording {W}x{H} @ {fps:.0f} fps; black loading frame {black_from:.1f} .. {first_content:.1f} s")

    # pass 2: full frame rate, downscaled, from 0.3 s before the first content
    w = a.width
    h = w * H // W
    t0 = max(0.0, first_content - 0.3)
    stack = np.stack(list(frames(path, w, h, start=t0, dur=a.seconds + 0.3)))
    ts = t0 + np.arange(len(stack)) / fps
    lit = stack > a.thresh
    # first frame past the black frame's own content = world entry (t = 0)
    any_lit = lit.reshape(len(lit), -1).mean(axis=1) > 0.02
    k0 = int(np.argmax(any_lit))
    lit = lit[k0:]
    ts = ts[k0:] - ts[k0]
    # the time each pixel turns on and stays on
    stays = np.flip(np.logical_and.accumulate(np.flip(lit, 0), 0), 0)
    shown = stays.any(0)
    first = np.where(shown, np.argmax(stays, 0), -1)
    tmap = np.where(shown, ts[np.clip(first, 0, None)], np.nan)
    print(f"world entry at {t0 + k0 / fps:.2f} s of the recording; {shown.mean() * 100:.0f} % of the screen shown within "
          f"{a.seconds:.0f} s")

    share = np.array([(first[shown] <= k).mean() * shown.mean() for k in range(len(ts))])
    print("\nreveal curve (share of the screen shown):")
    for pct in (5, 10, 25, 50, 75, 90, 95, 99):
        k = int(np.argmax(share >= pct / 100.0 * shown.mean()))
        print(f"  {pct:3d} % of the final at {ts[k] * 1000:6.0f} ms")
    steps = np.diff(np.concatenate([[0], share]))
    burst = np.argsort(steps)[::-1][:8]
    print("  biggest single-frame steps: " + ", ".join(f"{ts[k] * 1000:.0f} ms +{steps[k] * 100:.1f} %"
                                                     for k in sorted(burst)))

    yy, xx = np.mgrid[0:h, 0:w]
    # distance from the screen centre in chunk-diamond units when the geometry is known, else in screen heights
    g = None
    gp = a.geom or os.path.join(run_dir, "pzopt-resume.properties")
    if os.path.exists(gp):
        g = {}
        for line in open(gp):
            if "=" in line and not line.startswith("#"):
                k, v = line.strip().split("=", 1)
                g[k] = float(v)
    if g:
        # screen (normalised) -> world chunk coordinates relative to the player's chunk corner
        m = np.array([[g["vxx"], g["vyx"]], [g["vxy"], g["vyy"]]])
        inv = np.linalg.inv(m)
        if a.fit:
            # the file's chunk vectors (same zoom), the grid offset fitted to the map: the one where the reveal time
            # varies least inside each chunk (chunks appear whole)
            sx0 = (xx + 0.5) / w
            sy0 = (yy + 0.5) / h
            tv = np.nan_to_num(tmap, nan=a.seconds * 2)
            best = None
            for fi in np.linspace(0, 1, 32, endpoint=False):
                for fj in np.linspace(0, 1, 32, endpoint=False):
                    cx = inv[0, 0] * sx0 + inv[0, 1] * sy0 - fi
                    cy = inv[1, 0] * sx0 + inv[1, 1] * sy0 - fj
                    key = (np.floor(cx).astype(np.int64) + 1000) * 4096 + np.floor(cy).astype(np.int64) + 1000
                    _, inv_idx = np.unique(key.ravel(), return_inverse=True)
                    n = np.bincount(inv_idx)
                    s = np.bincount(inv_idx, tv.ravel())
                    s2 = np.bincount(inv_idx, tv.ravel() ** 2)
                    var = (s2 - s * s / n).sum()
                    if best is None or var < best[0]:
                        best = (var, fi, fj)
            _, fi, fj = best
            # the grid corner nearest the screen centre becomes the origin
            c = inv @ np.array([0.5, 0.5])
            ci0, cj0 = np.floor(c[0] - fi) + fi, np.floor(c[1] - fj) + fj
            o = m @ np.array([ci0, cj0])
            g["ox"], g["oy"] = float(o[0]), float(o[1])
            print(f"\nfitted grid: chunk corner at screen {g['ox']:.4f}, {g['oy']:.4f} (within-chunk variance "
                  f"{best[0] / (w * h):.4f} s²)")
        sx = (xx + 0.5) / w - g["ox"]
        sy = (yy + 0.5) / h - g["oy"]
        cx = inv[0, 0] * sx + inv[0, 1] * sy
        cy = inv[1, 0] * sx + inv[1, 1] * sy
        ci, cj = np.floor(cx).astype(int), np.floor(cy).astype(int)
        print("\nper chunk (i, j relative to the player's chunk): first / median / last reveal ms, spread")
        rows = []
        for i in range(ci.min(), ci.max() + 1):
            for j in range(cj.min(), cj.max() + 1):
                sel = (ci == i) & (cj == j)
                if sel.sum() < 0.3 * (w * h) * abs(np.linalg.det(m)):
                    continue  # mostly off screen
                t = tmap[sel]
                t = t[~np.isnan(t)]
                if len(t) < 20:
                    continue
                rows.append((np.median(t), i, j, t.min(), t.max(), len(t) / sel.sum()))
        for med, i, j, lo, hi, cov in sorted(rows):
            ring = max(abs(i), abs(j))
            print(f"  chunk {i:+d},{j:+d} ring {ring}: {lo * 1000:5.0f} / {med * 1000:5.0f} / {hi * 1000:5.0f} ms "
                  f"(spread {(hi - lo) * 1000:4.0f} ms, {cov * 100:3.0f} % shown)")
        # how many chunks arrive per frame (by their median time)
        per = {}
        for med, i, j, *_ in rows:
            per.setdefault(int(round(med * fps)), []).append((i, j))
        print("\nchunk arrivals per frame (frame at %.0f fps: count, chunks):" % fps)
        for f in sorted(per):
            print(f"  {f * 1000 / fps:6.0f} ms: {len(per[f])}  " + " ".join(f"{i:+d},{j:+d}" for i, j in per[f]))
        dist = np.hypot(cx - 0.5, cy - 0.5)
        unit = "chunks from the player's chunk centre"
    else:
        dist = np.hypot((xx - w / 2) / h, (yy - h / 2) / h)
        unit = "screen heights from the centre"
    print(f"\nmedian reveal time by distance ({unit}):")
    d = dist[shown]
    t = tmap[shown]
    edges = np.quantile(d, np.linspace(0, 1, 9))
    for lo, hi in zip(edges[:-1], edges[1:]):
        s = (d >= lo) & (d < hi)
        if s.any():
            print(f"  {lo:5.2f} .. {hi:5.2f}: median {np.median(t[s]) * 1000:5.0f} ms, p10 {np.quantile(t[s], .1) * 1000:5.0f}, "
                  f"p90 {np.quantile(t[s], .9) * 1000:5.0f}")

    # false-colour map
    out = a.out or os.path.join(run_dir, "revealmap.png")
    top = max(float(np.nanquantile(tmap, 0.98)), 1e-6)  # a few late objects (tall sprites) would squash the scale
    tn = np.nan_to_num(np.clip(tmap / top, 0, 1), nan=-1)
    rgb = np.zeros((h, w, 3), np.uint8)
    on = tn >= 0
    rgb[..., 0] = np.where(on, np.clip(255 * (1.5 - abs(4 * tn - 3)), 0, 255), 0)
    rgb[..., 1] = np.where(on, np.clip(255 * (1.5 - abs(4 * tn - 2)), 0, 255), 0)
    rgb[..., 2] = np.where(on, np.clip(255 * (1.5 - abs(4 * tn - 1)), 0, 255), 0)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-i", "-",
                    "-frames:v", "1", out], input=rgb.tobytes(), check=True)
    print(f"\ntime map: {out} (blue first .. red at {top * 1000:.0f} ms = the 98th percentile and later, black never)")


if __name__ == "__main__":
    main()
