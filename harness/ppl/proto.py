#!/usr/bin/env python3
"""Offline prototype of per-pixel lighting on a pzopt.PixelLight dump.

    proto.py <run> <tag> [--out /tmp/ppl]

Reconstructs every pixel's world position from the scene depth (IsoDepthHelper: depth linear in x + y + 2z) and the
iso projection, classifies the surface (floor / north wall / west wall / other) from the position's gradients, builds
the stock per-pixel light (the owning square's corner colours, interpolated the way the bake draws them) and writes:
  <out>/<tag>-stock.png      the dumped frame
  <out>/<tag>-lstock.png     the stock light estimate
  <out>/<tag>-kind.png       surface classes (floor red, N wall green, W wall blue, other grey)
"""
import argparse
import os

import numpy as np
from PIL import Image

from dump import load

DEPTH_PER_XY = 0.0014433542  # IsoDepthHelper: SQUARE_DEPTH / 2 per unit of x + y (a level is 2 units)


def positions(d):
    ts = int(d.cam["tileScale"])
    zoom = d.cam["zoom"]
    offX, offY = d.cam["offX"], d.cam["offY"]
    x0, y0, z0, d0 = d.depth_at[0]
    h, w = d.depth.shape
    sy, sx = np.mgrid[0:h, 0:w].astype(np.float64) + 0.5
    A = (sx * zoom + offX) / (32 * ts)  # x - y
    B = (sy * zoom + offY) / (16 * ts)  # x + y - 6z
    C = (x0 + y0 + 2 * z0) - (d.depth.astype(np.float64) - d0) / DEPTH_PER_XY  # x + y + 2z
    Z = (C - B) / 8
    S = C - 2 * Z
    return (S + A) / 2, (S - A) / 2, Z


def classify(X, Y, Z):
    """0 floor, 1 north wall (y = const, faces +y), 2 west wall (x = const, faces +x), 3 other."""
    k = np.full(X.shape, 3, np.uint8)

    def grad(a):
        gx = np.zeros_like(a)
        gy = np.zeros_like(a)
        gx[:, 1:-1] = (a[:, 2:] - a[:, :-2]) / 2
        gy[1:-1, :] = (a[2:, :] - a[:-2, :]) / 2
        return np.maximum(np.abs(gx), np.abs(gy))

    gz, gxx, gyy = grad(Z), grad(X), grad(Y)
    k[gz < 1e-3] = 0
    k[(gyy < 1e-3) & (gz >= 1e-3)] = 1
    k[(gxx < 1e-3) & (gz >= 1e-3)] = 2
    return k


def lattice(d, zlist):
    """Per-square corner arrays: V[z][(x - xmin), (y - ymin)] -> (8, 3); info -> (3,)."""
    xs = [k[0] for k in d.sq]
    ys = [k[1] for k in d.sq]
    xmin, ymin = min(xs), min(ys)
    W, H = max(xs) - xmin + 1, max(ys) - ymin + 1
    V = {z: np.zeros((W, H, 8, 3), np.float32) for z in zlist}
    I = {z: np.zeros((W, H, 3), np.float32) for z in zlist}
    for (x, y, z), s in d.sq.items():
        if z in V:
            V[z][x - xmin, y - ymin] = s.verts
            I[z][x - xmin, y - ymin] = s.info
    return V, I, xmin, ymin, W, H


def stock_light(d, X, Y, Z, kind):
    zl = sorted({k[2] for k in d.sq})
    V, I, xmin, ymin, W, H = lattice(d, zl)
    eps = 1e-3
    L = np.zeros(X.shape + (3,), np.float32)
    lz = np.floor(Z + 0.05).astype(int)  # tile edge rows are written a little behind (below) their surface
    for z in zl:
        m = lz == z
        if not m.any():
            continue
        Vz, Iz = V[z], I[z]
        # owner square: nudge along the surface normal (floors up, N walls +y, W walls +x)
        xo = X + np.where(kind == 2, eps, 0)
        yo = Y + np.where(kind == 1, eps, 0)
        ix = np.clip(np.floor(xo).astype(int) - xmin, 0, W - 1)
        iy = np.clip(np.floor(yo).astype(int) - ymin, 0, H - 1)
        fx = np.clip(xo - np.floor(xo), 0, 1)[..., None]
        fy = np.clip(yo - np.floor(yo), 0, 1)[..., None]
        fz = np.clip(Z - z, 0, 1)[..., None]
        v = Vz[ix, iy]  # (h, w, 8, 3)
        bot = (1 - fx) * (1 - fy) * v[..., 0, :] + fx * (1 - fy) * v[..., 1, :] + fx * fy * v[..., 2, :] + (1 - fx) * fy * v[..., 3, :]
        top = (1 - fx) * (1 - fy) * v[..., 4, :] + fx * (1 - fy) * v[..., 5, :] + fx * fy * v[..., 6, :] + (1 - fx) * fy * v[..., 7, :]
        tri = (1 - fz) * bot + fz * top
        flat = Iz[ix, iy]
        lk = np.where((kind == 3)[..., None], flat, tri)
        L[m] = lk[m]
    return L


def save(path, a):
    Image.fromarray(np.clip(a * 255 + 0.5, 0, 255).astype(np.uint8)).save(path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("tag")
    ap.add_argument("--out", default="/tmp/ppl")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    d = load(a.run, a.tag)
    X, Y, Z = positions(d)
    kind = classify(X, Y, Z)
    L = stock_light(d, X, Y, Z, kind)
    col = d.color[..., :3].astype(np.float32) / 255
    save(f"{a.out}/{a.tag}-stock.png", col)
    save(f"{a.out}/{a.tag}-lstock.png", L)
    pal = np.array([[1, 0.3, 0.3], [0.3, 1, 0.3], [0.3, 0.3, 1], [0.5, 0.5, 0.5]], np.float32)
    save(f"{a.out}/{a.tag}-kind.png", pal[kind])
    np.savez_compressed(f"{a.out}/{a.tag}-geo.npz", X=X.astype(np.float32), Y=Y.astype(np.float32), Z=Z.astype(np.float32), kind=kind, L=L)
    print("kinds:", np.bincount(kind.ravel(), minlength=4) / kind.size)


if __name__ == "__main__":
    main()


# ---------------------------------------------------------------------------------------------- per-pixel shaping (numpy port)

def shape_at(qx, qy, a, b):
    """pplShapeAt: linear falloff to the reach, a torch's soft cone (full within ~a square of the source)."""
    vx, vy = qx - a[0], qy - a[1]
    d = np.hypot(vx, vy)
    f = np.clip(1.0 - d / a[3], 0.0, 1.0)
    if b[2] > -1.5:
        c = (vx * b[0] + vy * b[1]) / np.maximum(d, 1e-4)
        t = np.clip((c - (b[2] - 0.06)) / 0.12, 0, 1)
        sm = t * t * (3 - 2 * t)
        w = np.clip(d - 0.5, 0, 1)
        f = f * ((1 - w) + w * sm)
    return f


def lights_of(d):
    """The shader's light table from a dump: torches, then active point lights (absolute coordinates)."""
    out = []
    for t in d.torches:
        n = np.hypot(t["ax"], t["ay"])
        out.append(((t["x"], t["y"], t["z"], max(1.0, t["dist"])), (t["ax"] / n, t["ay"] / n, t["dot"] if t["cone"] else -2.0, t["strength"])))
    for l in d.lights:
        if l["active"] and l["radius"] > 0:
            out.append(((l["x"] + 0.5, l["y"] + 0.5, l["z"], min(l["radius"], 20)), (0, 0, -2.0, 1.0)))
    return out[:16]


def shaped_light(d, X, Y, Z, kind, lights):
    """Stock per-pixel lattice light + the calibrated per-pixel shape of each light (the shader's pplLight, floors / walls)."""
    zl = sorted({k[2] for k in d.sq})
    V, I, xmin, ymin, W, H = lattice(d, zl)
    lz = np.floor(Z + 0.006).astype(int)
    sx = np.floor(X + 0.004)
    sy = np.floor(Y + 0.004)
    fx = np.clip(X - sx, 0, 1)
    fy = np.clip(Y - sy, 0, 1)
    fz = np.clip(Z - lz, 0, 1)
    Lout = np.zeros(X.shape + (3,), np.float32)
    shape = np.zeros(X.shape + (3,), np.float32)
    for z in zl:
        m = lz == z
        if not m.any():
            continue
        ix = np.clip(sx[m].astype(int) - xmin, 0, W - 1)
        iy = np.clip(sy[m].astype(int) - ymin, 0, H - 1)
        v = V[z][ix, iy]
        fxm, fym, fzm = fx[m][:, None], fy[m][:, None], fz[m][:, None]
        c = [v[:, i, :] for i in range(4)]
        bot = (1 - fxm) * (1 - fym) * c[0] + fxm * (1 - fym) * c[1] + fxm * fym * c[2] + (1 - fxm) * fym * c[3]
        top = (1 - fxm) * (1 - fym) * v[:, 4] + fxm * (1 - fym) * v[:, 5] + fxm * fym * v[:, 6] + (1 - fxm) * fym * v[:, 7]
        L = (1 - fzm) * bot + fzm * top
        cm = (c[0] + c[1] + c[2] + c[3]) / 4
        sh = np.zeros_like(L)
        qx, qy = sx[m], sy[m]
        for a, b in lights:
            if abs(a[2] - z) > 1.5:
                continue
            t = [shape_at(qx, qy, a, b), shape_at(qx + 1, qy, a, b), shape_at(qx + 1, qy + 1, a, b), shape_at(qx, qy + 1, a, b)]
            tm = (t[0] + t[1] + t[2] + t[3]) / 4
            dt = [ti - tm for ti in t]
            vt = sum(x * x for x in dt)
            ok = (vt >= 1e-5) & (np.maximum(np.abs(qx + 0.5 - a[0]), np.abs(qy + 0.5 - a[1])) > 1.5)
            cov = sum(dt[i][:, None] * (c[i] - cm) for i in range(4))
            k = np.clip(cov / np.maximum(vt, 1e-9)[:, None], 0, 4 * max(b[3], 0.5)) * ok[:, None]
            tp = shape_at(X[m], Y[m], a, b)
            ti = (1 - fx[m]) * (1 - fy[m]) * t[0] + fx[m] * (1 - fy[m]) * t[1] + fx[m] * fy[m] * t[2] + (1 - fx[m]) * fy[m] * t[3]
            sh += k * (tp - ti)[:, None]
        lo = np.minimum(np.minimum(c[0], c[1]), np.minimum(c[2], c[3]))
        hi = np.maximum(np.maximum(c[0], c[1]), np.maximum(c[2], c[3]))
        lo = np.minimum(lo, np.minimum(np.minimum(v[:, 4], v[:, 5]), np.minimum(v[:, 6], v[:, 7])) + (fzm <= 0.001) * 9)
        hi = np.maximum(hi, np.maximum(np.maximum(v[:, 4], v[:, 5]), np.maximum(v[:, 6], v[:, 7])) - (fzm <= 0.001) * 9)
        Lout[m] = np.clip(L + sh, lo, hi)
        shape[m] = sh
    return Lout, shape


CLAMP_LO = 0


def centre_light(d, X, Y, Z, kind):
    """The per-square light (lightInfo, the native's own samples at square centres) interpolated bilinearly between
    centres, clamped to the range of the owner square's corners (walls: the corners keep the native's discontinuities)."""
    zl = sorted({k[2] for k in d.sq})
    V, I, xmin, ymin, W, H = lattice(d, zl)
    lz = np.floor(Z + 0.006).astype(int)
    out = np.zeros(X.shape + (3,), np.float32)
    for z in zl:
        m = lz == z
        if not m.any():
            continue
        qx, qy = X[m] - 0.5, Y[m] - 0.5
        x0, y0 = np.floor(qx).astype(int), np.floor(qy).astype(int)
        tx, ty = (qx - x0)[:, None], (qy - y0)[:, None]
        def info(ax, ay):
            return I[z][np.clip(ax - xmin, 0, W - 1), np.clip(ay - ymin, 0, H - 1)]
        S = (1 - tx) * (1 - ty) * info(x0, y0) + tx * (1 - ty) * info(x0 + 1, y0) + tx * ty * info(x0 + 1, y0 + 1) + (1 - tx) * ty * info(x0, y0 + 1)
        sx, sy = np.floor(X[m] + 0.004).astype(int), np.floor(Y[m] + 0.004).astype(int)
        v = V[z][np.clip(sx - xmin, 0, W - 1), np.clip(sy - ymin, 0, H - 1)]
        fz = np.clip(Z[m] - z, 0, 1)[:, None]
        lo = v[:, :4].min(1)
        hi = v[:, :4].max(1)
        S = np.clip(S, lo * CLAMP_LO, 1.0) if CLAMP_LO else S
        fx = np.clip(X[m] - np.floor(X[m] + 0.004), 0, 1)[:, None]
        fy = np.clip(Y[m] - np.floor(Y[m] + 0.004), 0, 1)[:, None]
        top = (1 - fx) * (1 - fy) * v[:, 4] + fx * (1 - fy) * v[:, 5] + fx * fy * v[:, 6] + (1 - fx) * fy * v[:, 7]
        out[m] = (1 - fz) * S + fz * top
    return out


def connectivity(V, z, W, H, tol=3.5 / 255):
    """Per square: bits E, S, W, N, SE, SW, NW, NE set where the shared corners are equal (pzopt.PixelLight.pack)."""
    v = V[z]  # (W, H, 8, 3)

    def nb(dx, dy):
        out = np.full_like(v, -1.0)
        xs = slice(max(dx, 0), W + min(dx, 0))
        xd = slice(max(-dx, 0), W + min(-dx, 0))
        ys = slice(max(dy, 0), H + min(dy, 0))
        yd = slice(max(-dy, 0), H + min(-dy, 0))
        out[xd, yd] = v[xs, ys]
        return out

    def eq(a, b):
        return np.all(np.abs(a - b) <= tol, axis=-1)

    e, s_, w, n = nb(1, 0), nb(0, 1), nb(-1, 0), nb(0, -1)
    c = np.zeros((W, H), np.int32)
    c |= (eq(v[..., 1, :], e[..., 0, :]) & eq(v[..., 2, :], e[..., 3, :])) * 1
    c |= (eq(v[..., 3, :], s_[..., 0, :]) & eq(v[..., 2, :], s_[..., 1, :])) * 2
    c |= (eq(v[..., 0, :], w[..., 1, :]) & eq(v[..., 3, :], w[..., 2, :])) * 4
    c |= (eq(v[..., 0, :], n[..., 3, :]) & eq(v[..., 1, :], n[..., 2, :])) * 8
    c |= ((c & 3) == 3) * eq(v[..., 2, :], nb(1, 1)[..., 0, :]) * 16
    c |= ((c & 6) == 6) * eq(v[..., 3, :], nb(-1, 1)[..., 1, :]) * 32
    c |= ((c & 12) == 12) * eq(v[..., 0, :], nb(-1, -1)[..., 2, :]) * 64
    c |= ((c & 9) == 9) * eq(v[..., 1, :], nb(1, -1)[..., 3, :]) * 128
    return c


def light_v2(d, X, Y, Z):
    """The shader's pplLight without the dynamic-light shaping: edge-aware interpolation between square centres, walls'
    vertical gradient from the corner layers."""
    zl = sorted({k[2] for k in d.sq})
    V, I, xmin, ymin, W, H = lattice(d, zl)
    lz = np.floor(Z + 0.006).astype(int)
    out = np.zeros(X.shape + (3,), np.float32)
    for z in zl:
        m = lz == z
        if not m.any():
            continue
        C = connectivity(V, z, W, H)
        sx = np.floor(X[m] + 0.004).astype(int)
        sy = np.floor(Y[m] + 0.004).astype(int)
        fx = np.clip(X[m] - sx, 0, 1)
        fy = np.clip(Y[m] - sy, 0, 1)
        fz = np.clip(Z[m] - z, 0, 1)[:, None]
        ix, iy = np.clip(sx - xmin, 0, W - 1), np.clip(sy - ymin, 0, H - 1)
        conn = C[ix, iy]
        qx, qy = fx - 0.5, fy - 0.5
        dx = np.where(qx < 0, -1, 1)
        dy = np.where(qy < 0, -1, 1)
        wx, wy = np.abs(qx)[:, None], np.abs(qy)[:, None]
        bx = np.where(dx > 0, 1, 4)
        by = np.where(dy > 0, 2, 8)
        bxy = np.where(dx > 0, np.where(dy > 0, 16, 128), np.where(dy > 0, 32, 64))

        def inf(ax, ay):
            return I[z][np.clip(ax, 0, W - 1), np.clip(ay, 0, H - 1)]

        i00 = inf(ix, iy)
        i10 = np.where(((conn & bx) != 0)[:, None], inf(ix + dx, iy), i00)
        i01 = np.where(((conn & by) != 0)[:, None], inf(ix, iy + dy), i00)
        i11 = np.where(((conn & bxy) != 0)[:, None], inf(ix + dx, iy + dy), np.where(((conn & bx) != 0)[:, None], i10, i01))
        S = (1 - wx) * (1 - wy) * i00 + wx * (1 - wy) * i10 + (1 - wx) * wy * i01 + wx * wy * i11
        v = V[z][ix, iy]
        fxm, fym = fx[:, None], fy[:, None]
        cb = (1 - fxm) * (1 - fym) * v[:, 0] + fxm * (1 - fym) * v[:, 1] + fxm * fym * v[:, 2] + (1 - fxm) * fym * v[:, 3]
        ct = (1 - fxm) * (1 - fym) * v[:, 4] + fxm * (1 - fym) * v[:, 5] + fxm * fym * v[:, 6] + (1 - fxm) * fym * v[:, 7]
        out[m] = np.where(fz > 0.001, np.clip(S + fz * (ct - cb), 0, 1), S)
    return out
