#!/usr/bin/env python3
"""Power and P / E cluster load on an Apple silicon Mac, no root (2026-09-24, the E-core pass).

Every PERIOD seconds one CSV row:
  epoch_ms, system_w, p_busy_pct, e_busy_pct, p_cores, e_cores, gpu_w, cpu_w
gpu_w / cpu_w come from IOReport's "Energy Model" counters (libIOReport, no root; the counters macmon reads): GPU energy
works on the M1 Pro, the CPU channels stayed frozen there (empty when they do not move). system_w is the SMC's whole-system load (AppleSmartBattery PowerTelemetryData "SystemLoad", mW -> W; the display
included, so compare runs with the same brightness), averaged over the interval from its accumulators when they move.
p_busy_pct / e_busy_pct are the busy share of the performance / efficiency cores (100 = every core of that cluster busy)
from host_processor_info tick counters. Which logical CPUs are E cores comes from hw.perflevel1.logicalcpu: Apple
numbers the efficiency cluster first (M1 Pro: cpu0-1 E, cpu2-9 P; checked with a background-QoS busy loop).
Usage: macpower.py OUT.csv [PERIOD=1]
"""
import ctypes, ctypes.util, re, subprocess, sys, time

out = open(sys.argv[1], "w", buffering=1)
period = float(sys.argv[2]) if len(sys.argv) > 2 else 1.0

libc = ctypes.CDLL(ctypes.util.find_library("c"))
libc.mach_host_self.restype = ctypes.c_uint
libc.mach_task_self.restype = ctypes.c_uint
PROCESSOR_CPU_LOAD_INFO = 2


def ticks():
    count = ctypes.c_uint()
    info = ctypes.POINTER(ctypes.c_int)()
    n = ctypes.c_uint()
    r = libc.host_processor_info(libc.mach_host_self(), PROCESSOR_CPU_LOAD_INFO, ctypes.byref(count), ctypes.byref(info), ctypes.byref(n))
    if r != 0:
        return []
    v = [tuple(info[i * 4 + k] for k in range(4)) for i in range(count.value)]  # user, system, idle, nice
    libc.vm_deallocate(libc.mach_task_self(), ctypes.cast(info, ctypes.c_void_p), ctypes.c_size_t(n.value * 4))
    return v


def sysctl_int(name, default):
    try:
        return int(subprocess.run(["sysctl", "-n", name], capture_output=True, text=True).stdout.strip())
    except ValueError:
        return default


E = sysctl_int("hw.perflevel1.logicalcpu", 0)


class IOReport:
    """Cumulative energy counters of the "Energy Model" group, W over the interval between two reads."""
    def __init__(self):
        vp = ctypes.c_void_p
        self.cf = cf = ctypes.CDLL(ctypes.util.find_library("CoreFoundation"))
        self.ior = ior = ctypes.CDLL("/usr/lib/libIOReport.dylib")
        cf.CFStringCreateWithCString.restype = vp; cf.CFStringCreateWithCString.argtypes = [vp, ctypes.c_char_p, ctypes.c_uint32]
        cf.CFDictionaryGetValue.restype = vp; cf.CFDictionaryGetValue.argtypes = [vp, vp]
        cf.CFArrayGetCount.restype = ctypes.c_long; cf.CFArrayGetCount.argtypes = [vp]
        cf.CFArrayGetValueAtIndex.restype = vp; cf.CFArrayGetValueAtIndex.argtypes = [vp, ctypes.c_long]
        cf.CFStringGetCString.restype = ctypes.c_bool; cf.CFStringGetCString.argtypes = [vp, ctypes.c_char_p, ctypes.c_long, ctypes.c_uint32]
        cf.CFRelease.argtypes = [vp]
        ior.IOReportCopyChannelsInGroup.restype = vp; ior.IOReportCopyChannelsInGroup.argtypes = [vp, vp, ctypes.c_uint64, ctypes.c_uint64, ctypes.c_uint64]
        ior.IOReportCreateSubscription.restype = vp; ior.IOReportCreateSubscription.argtypes = [vp, vp, ctypes.POINTER(vp), ctypes.c_uint64, vp]
        ior.IOReportCreateSamples.restype = vp; ior.IOReportCreateSamples.argtypes = [vp, vp, vp]
        for f in ("IOReportChannelGetChannelName", "IOReportChannelGetUnitLabel"):
            getattr(ior, f).restype = vp; getattr(ior, f).argtypes = [vp]
        ior.IOReportSimpleGetIntegerValue.restype = ctypes.c_int64; ior.IOReportSimpleGetIntegerValue.argtypes = [vp, ctypes.c_int32]
        self.utf8 = 0x08000100
        chans = ior.IOReportCopyChannelsInGroup(self.cfs("Energy Model"), None, 0, 0, 0)
        self.subbed = vp()
        self.sub = ior.IOReportCreateSubscription(None, chans, ctypes.byref(self.subbed), 0, None)
        self.key = self.cfs("IOReportChannels")
        self.prev = self.read()

    def cfs(self, s):
        return self.cf.CFStringCreateWithCString(None, s.encode(), self.utf8)

    def pystr(self, r):
        if not r:
            return ""
        b = ctypes.create_string_buffer(128)
        self.cf.CFStringGetCString(r, b, 128, self.utf8)
        return b.value.decode()

    def read(self):
        smp = self.ior.IOReportCreateSamples(self.sub, self.subbed, None)
        arr = self.cf.CFDictionaryGetValue(smp, self.key)
        out = {}
        scale = {"mJ": 1e-3, "uJ": 1e-6, "nJ": 1e-9}
        for i in range(self.cf.CFArrayGetCount(arr)):
            ch = self.cf.CFArrayGetValueAtIndex(arr, i)
            u = self.pystr(self.ior.IOReportChannelGetUnitLabel(ch))
            if u in scale:
                out[self.pystr(self.ior.IOReportChannelGetChannelName(ch))] = self.ior.IOReportSimpleGetIntegerValue(ch, 0) * scale[u]
        self.cf.CFRelease(smp)
        return out, time.time()

    def watts(self):
        cur = self.read()
        (a, ta), (b, tb) = self.prev, cur
        self.prev = cur
        dt = max(1e-3, tb - ta)
        w = lambda k: (b.get(k, 0.0) - a.get(k, 0.0)) / dt
        gpu = w("GPU Energy")
        cpu = w("CPU Energy")
        return ("%.3f" % gpu if gpu > 0 else ""), ("%.3f" % cpu if cpu > 0 else "")


try:
    IOR = IOReport()
except Exception:
    IOR = None


def telemetry():
    o = subprocess.run(["ioreg", "-rw0", "-c", "AppleSmartBattery"], capture_output=True, text=True, timeout=3).stdout
    m = re.search(r'"PowerTelemetryData" = \{([^}]*)\}', o)
    if not m:
        return None
    return {k: int(v) for k, v in re.findall(r'"(\w+)"=(\d+)', m.group(1))}


out.write("epoch_ms,system_w,p_busy_pct,e_busy_pct,p_cores,e_cores,gpu_w,cpu_w\n")
prev_t = ticks()
prev_tel = telemetry()
while True:
    time.sleep(period)
    now = int(time.time() * 1000)
    cur_t = ticks()
    tel = telemetry()
    busy = {"p": [0, 0], "e": [0, 0]}
    for i, (a, b) in enumerate(zip(prev_t, cur_t)):
        d = [b[k] - a[k] for k in range(4)]
        tot = sum(d)
        cls = "e" if i < E else "p"
        busy[cls][0] += tot - d[2]
        busy[cls][1] += tot
    w = ""
    if tel and prev_tel:
        dn = tel.get("SystemLoadAccumulatorCount", 0) - prev_tel.get("SystemLoadAccumulatorCount", 0)
        de = tel.get("AccumulatedSystemLoad", 0) - prev_tel.get("AccumulatedSystemLoad", 0)
        w = "%.2f" % (de / dn / 1000.0) if dn > 0 else "%.2f" % (tel.get("SystemLoad", 0) / 1000.0)
    pp = "%.1f" % (100.0 * busy["p"][0] / busy["p"][1]) if busy["p"][1] else ""
    ee = "%.1f" % (100.0 * busy["e"][0] / busy["e"][1]) if busy["e"][1] else ""
    gw, cw = IOR.watts() if IOR else ("", "")
    out.write(f"{now},{w},{pp},{ee},{len(cur_t) - E},{E},{gw},{cw}\n")
    prev_t, prev_tel = cur_t, tel
