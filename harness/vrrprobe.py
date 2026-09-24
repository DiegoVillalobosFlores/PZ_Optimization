#!/usr/bin/env python3
"""Log whether the compositor has variable refresh rate switched on, as the kernel sees it.

Reads the VRR_ENABLED property of every active CRTC (libdrm, no DRM master needed, so it runs beside
KWin / Mutter / gamescope as a normal user in the video group) and the vrr_capable property of the
connected connectors. VRR_ENABLED is what the compositor asked the display engine for: 1 while the
display refreshes when a frame arrives, 0 on a fixed refresh. It is the only signal that does not
depend on the game's own view of the world.

    harness/vrrprobe.py [--interval 0.25] [--duration S] [--out FILE]   # one line per sample
    harness/vrrprobe.py --once                                          # current state and exit
    harness/vrrprobe.py --summary FILE [--from-ms A --to-ms B]          # share of samples with VRR on

Output columns: epoch_ms card crtc vrr_enabled mode_hz (one row per active CRTC per sample).
"""
import argparse
import ctypes
import ctypes.util
import glob
import os
import sys
import time

DRM_MODE_OBJECT_CRTC = 0xCCCCCCCC
DRM_MODE_OBJECT_CONNECTOR = 0xC0C0C0C0


class ModeInfo(ctypes.Structure):
    _fields_ = [("clock", ctypes.c_uint32),
                ("hdisplay", ctypes.c_uint16), ("hsync_start", ctypes.c_uint16), ("hsync_end", ctypes.c_uint16),
                ("htotal", ctypes.c_uint16), ("hskew", ctypes.c_uint16),
                ("vdisplay", ctypes.c_uint16), ("vsync_start", ctypes.c_uint16), ("vsync_end", ctypes.c_uint16),
                ("vtotal", ctypes.c_uint16), ("vscan", ctypes.c_uint16),
                ("vrefresh", ctypes.c_uint32), ("flags", ctypes.c_uint32), ("type", ctypes.c_uint32),
                ("name", ctypes.c_char * 32)]


class Res(ctypes.Structure):
    _fields_ = [("count_fbs", ctypes.c_int), ("fbs", ctypes.POINTER(ctypes.c_uint32)),
                ("count_crtcs", ctypes.c_int), ("crtcs", ctypes.POINTER(ctypes.c_uint32)),
                ("count_connectors", ctypes.c_int), ("connectors", ctypes.POINTER(ctypes.c_uint32)),
                ("count_encoders", ctypes.c_int), ("encoders", ctypes.POINTER(ctypes.c_uint32)),
                ("min_width", ctypes.c_uint32), ("max_width", ctypes.c_uint32),
                ("min_height", ctypes.c_uint32), ("max_height", ctypes.c_uint32)]


class Crtc(ctypes.Structure):
    _fields_ = [("crtc_id", ctypes.c_uint32), ("buffer_id", ctypes.c_uint32),
                ("x", ctypes.c_uint32), ("y", ctypes.c_uint32), ("width", ctypes.c_uint32), ("height", ctypes.c_uint32),
                ("mode_valid", ctypes.c_int), ("mode", ModeInfo), ("gamma_size", ctypes.c_int)]


class ObjProps(ctypes.Structure):
    _fields_ = [("count_props", ctypes.c_uint32), ("props", ctypes.POINTER(ctypes.c_uint32)),
                ("prop_values", ctypes.POINTER(ctypes.c_uint64))]


class Prop(ctypes.Structure):
    _fields_ = [("prop_id", ctypes.c_uint32), ("flags", ctypes.c_uint32), ("name", ctypes.c_char * 32),
                ("count_values", ctypes.c_int), ("values", ctypes.POINTER(ctypes.c_uint64)),
                ("count_enums", ctypes.c_int), ("enums", ctypes.c_void_p),
                ("count_blobs", ctypes.c_int), ("blob_ids", ctypes.POINTER(ctypes.c_uint32))]


drm = ctypes.CDLL(ctypes.util.find_library("drm") or "libdrm.so.2")
drm.drmModeGetResources.restype = ctypes.POINTER(Res)
drm.drmModeGetCrtc.restype = ctypes.POINTER(Crtc)
drm.drmModeGetCrtc.argtypes = [ctypes.c_int, ctypes.c_uint32]
drm.drmModeObjectGetProperties.restype = ctypes.POINTER(ObjProps)
drm.drmModeObjectGetProperties.argtypes = [ctypes.c_int, ctypes.c_uint32, ctypes.c_uint32]
drm.drmModeGetProperty.restype = ctypes.POINTER(Prop)
drm.drmModeGetProperty.argtypes = [ctypes.c_int, ctypes.c_uint32]
for f in ("drmModeFreeResources", "drmModeFreeCrtc", "drmModeFreeObjectProperties", "drmModeFreeProperty"):
    getattr(drm, f).argtypes = [ctypes.c_void_p]

_names = {}


def prop_values(fd, obj, kind):
    """{name: value} of a DRM object's properties (names cached per property id)."""
    p = drm.drmModeObjectGetProperties(fd, obj, kind)
    if not p:
        return {}
    out = {}
    for i in range(p.contents.count_props):
        pid = p.contents.props[i]
        name = _names.get((fd, pid))
        if name is None:
            pr = drm.drmModeGetProperty(fd, pid)
            name = pr.contents.name.decode() if pr else str(pid)
            if pr:
                drm.drmModeFreeProperty(pr)
            _names[(fd, pid)] = name
        out[name] = p.contents.prop_values[i]
    drm.drmModeFreeObjectProperties(p)
    return out


def open_cards():
    cards = []
    for path in sorted(glob.glob("/dev/dri/card[0-9]*")):
        try:
            fd = os.open(path, os.O_RDWR | os.O_CLOEXEC)
        except OSError:
            continue
        res = drm.drmModeGetResources(fd)
        if not res:
            os.close(fd)
            continue
        crtcs = [res.contents.crtcs[i] for i in range(res.contents.count_crtcs)]
        conns = [res.contents.connectors[i] for i in range(res.contents.count_connectors)]
        drm.drmModeFreeResources(res)
        cards.append((os.path.basename(path), fd, crtcs, conns))
    return cards


def sample(cards):
    """[(card, crtc, vrr_enabled, mode_hz)] for every CRTC that drives a mode."""
    rows = []
    for name, fd, crtcs, _ in cards:
        for c in crtcs:
            cr = drm.drmModeGetCrtc(fd, c)
            if not cr:
                continue
            valid = cr.contents.mode_valid
            m = cr.contents.mode
            hz = m.clock * 1000.0 / (m.htotal * m.vtotal) if valid and m.htotal and m.vtotal else 0.0
            drm.drmModeFreeCrtc(cr)
            if not valid:
                continue
            props = prop_values(fd, c, DRM_MODE_OBJECT_CRTC)
            rows.append((name, c, int(props.get("VRR_ENABLED", -1)), hz))
    return rows


def capable(cards):
    out = []
    for name, fd, _, conns in cards:
        for c in conns:
            props = prop_values(fd, c, DRM_MODE_OBJECT_CONNECTOR)
            if "vrr_capable" in props:
                out.append((name, c, int(props["vrr_capable"])))
    return out


def summary(path, lo, hi):
    on = total = 0
    for line in open(path):
        if line.startswith("#"):
            continue
        f = line.split()
        if len(f) < 4:
            continue
        t = int(f[0])
        if lo is not None and t < lo or hi is not None and t > hi:
            continue
        total += 1
        on += f[3] == "1"
    share = on / total if total else 0.0
    print(f"vrr: on {on}/{total} samples ({share * 100:.0f} %)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--interval", type=float, default=0.25)
    ap.add_argument("--duration", type=float, default=0.0, help="seconds, 0 = until killed")
    ap.add_argument("--out")
    ap.add_argument("--once", action="store_true")
    ap.add_argument("--summary")
    ap.add_argument("--from-ms", type=int)
    ap.add_argument("--to-ms", type=int)
    a = ap.parse_args()
    if a.summary:
        summary(a.summary, a.from_ms, a.to_ms)
        return
    cards = open_cards()
    if a.once:
        for name, c, cap in capable(cards):
            if cap:
                print(f"{name} connector {c}: vrr_capable=1")
        for row in sample(cards):
            print(f"{row[0]} crtc {row[1]}: VRR_ENABLED={row[2]} mode {row[3]:.2f} Hz")
        return
    out = open(a.out, "w", buffering=1) if a.out else sys.stdout
    out.write("# epoch_ms card crtc vrr_enabled mode_hz\n")
    for name, c, cap in capable(cards):
        out.write(f"# {name} connector {c} vrr_capable={cap}\n")
    end = time.time() + a.duration if a.duration > 0 else None
    try:
        while end is None or time.time() < end:
            now = int(time.time() * 1000)
            for row in sample(cards):
                out.write(f"{now} {row[0]} {row[1]} {row[2]} {row[3]:.2f}\n")
            time.sleep(a.interval)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
