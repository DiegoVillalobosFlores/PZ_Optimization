#!/usr/bin/env python3
"""Per-thread scheduler monitor for the game process (no root needed).

Waits for the ProjectZomboid64 JVM, then every PERIOD seconds writes one line per sampled thread:
  epoch_ms  tid  comm  run_ns  wait_ns  minflt  majflt  state  cpu  runs
from /proc/<pid>/task/<tid>/{schedstat,stat}, cumulative counters (analysed by harness/schedmon-report.py).
run_ns = time on the CPU, wait_ns = time runnable but waiting for a CPU (run-queue delay), majflt = major
page faults (swap / file page-ins). Plus one line per sample with the machine's PSI and vmstat:
  epoch_ms  -  PSI  cpu_some_us  mem_some_us  mem_full_us  io_some_us  pswpin  pswpout  pgmajfault  ctxt
Every 10 s also one memory line from /proc/<pid>/smaps, resident kB by mapping kind:
  epoch_ms  -  MEM  anon=  nvidia=  shmem=  so=  file=  total=  swap=
(anon = Java heap + native malloc, nvidia = /dev/nvidia* mappings, shmem = memfd / SysV / dri, so = libraries).
cpu = the logical CPU the thread last ran on; runs = how many times it was put on a CPU (schedstat's third field: its
wakeups, which keep cores out of their deep idle states). On a hybrid CPU (cores with different top clocks or boost rankings: Zen 5 +
Zen 5c, Intel P + E) the header names the classes (`# cores fast=0-3,12-15 slow=4-11,16-23`) and every sample adds
  epoch_ms  -  FREQ  <fast cores' mean MHz>  <slow cores' mean MHz>  <package W from amdgpu PPT, or ->
On an AMD APU whose amdgpu gpu_metrics is format 3.0 (Strix Point: the flip) every sample also adds the SMU's own
power rails in mW: socket, GFX, all cores, and the per-core sums of the fast and the slow cores
  epoch_ms  -  GM  socket_mw  gfx_mw  cores_mw  fast_cores_mw  slow_cores_mw  gfx_activity_pct  dram_read_mbs  dram_write_mbs
(SMU core index = 2 * core_id on the Zen 5 cores, core_id on the Zen 5c cores, mapped on the flip with a pinned busy
loop; the core power of each cpu's physical core is counted once).
Once a second, the other processes of the machine that used the most CPU in that second (compositor, Xwayland, audio):
  epoch_ms  -  PROC  <comm>:<pid>:<cpu ms>:<last cpu>  ...   (the ten busiest, the game excluded)
Once a second, every process's GPU time from the DRM fdinfo (amdgpu / i915 / xe: drm-engine-* ns; the compositor and
Xwayland next to the game):
  epoch_ms  -  GPU  <comm>:<pid>:<gfx ms>:<compute ms>  ...
(harness/corepower.py reads all of it).
Usage: schedmon.py OUT [PERIOD=0.05]
"""
import os, re, sys, time

out = open(sys.argv[1], "w", buffering=1 << 16)
period = float(sys.argv[2]) if len(sys.argv) > 2 else 0.05


def find_pid():
    for p in os.listdir("/proc"):
        if not p.isdigit():
            continue
        try:
            cmd = open(f"/proc/{p}/cmdline", "rb").read().split(b"\0")
        except OSError:
            continue
        if cmd and os.path.basename(cmd[0]) == b"ProjectZomboid64":
            return p
    return None


def psi(name):
    some = full = 0
    try:
        for line in open(f"/proc/pressure/{name}"):
            f = line.split()
            v = int(f[4].split("=")[1])
            if f[0] == "some":
                some = v
            else:
                full = v
    except OSError:
        pass
    return some, full


VM = ("pswpin", "pswpout", "pgmajfault")

pid = None
t_end = time.time() + 900
while pid is None and time.time() < t_end:
    pid = find_pid()
    if pid is None:
        time.sleep(0.5)
if pid is None:
    sys.exit(0)
out.write(f"# pid={pid} period={period} clk_tck={os.sysconf('SC_CLK_TCK')}\n")


def core_classes():
    """fast / slow logical CPUs by top clock, else by amd-pstate boost ranking (Zen 5c shares the top clock cap in
    power-saver). Empty when every core is alike."""
    rank = {}
    for c in os.listdir("/sys/devices/system/cpu"):
        if not re.fullmatch(r"cpu\d+", c):
            continue
        base = f"/sys/devices/system/cpu/{c}/cpufreq/"
        v = None
        for f in ("amd_pstate_highest_perf", "cpuinfo_max_freq"):
            try:
                v = int(open(base + f).read())
                break
            except (OSError, ValueError):
                pass
        if v is not None:
            rank[int(c[3:])] = v
    if len(set(rank.values())) < 2:
        return [], []
    top = max(rank.values())
    lo = min(rank.values())
    cut = (top + lo) / 2
    return sorted(c for c, v in rank.items() if v > cut), sorted(c for c, v in rank.items() if v <= cut)


def cpu_list(cs):
    out_, i = [], 0
    while i < len(cs):
        j = i
        while j + 1 < len(cs) and cs[j + 1] == cs[j] + 1:
            j += 1
        out_.append(f"{cs[i]}-{cs[j]}" if j > i else f"{cs[i]}")
        i = j + 1
    return ",".join(out_)


FAST, SLOW = core_classes()
if FAST:
    out.write(f"# cores fast={cpu_list(FAST)} slow={cpu_list(SLOW)}\n")
PPT = None
for h in sorted(os.listdir("/sys/class/hwmon")):
    try:
        if open(f"/sys/class/hwmon/{h}/name").read().strip() == "amdgpu" and os.path.exists(f"/sys/class/hwmon/{h}/power1_input"):
            PPT = f"/sys/class/hwmon/{h}/power1_input"
            break
    except OSError:
        pass


def mean_mhz(cs):
    v = []
    for c in cs:
        try:
            v.append(int(open(f"/sys/devices/system/cpu/cpu{c}/cpufreq/scaling_cur_freq").read()) / 1000)
        except (OSError, ValueError):
            pass
    return f"{sum(v) / len(v):.0f}" if v else "-"


GM = None
for d in sorted(os.listdir("/sys/class/drm")):
    f = f"/sys/class/drm/{d}/device/gpu_metrics"
    try:
        with open(f, "rb") as fh:
            h = fh.read(4)
        if len(h) == 4 and h[2] == 3 and h[3] == 0:
            GM = f
            break
    except OSError:
        pass
import struct


def smu_index(cpu):
    """SMU per-core slot of a logical cpu (Strix Point: Zen 5 at 2 * core_id, Zen 5c at core_id)."""
    try:
        core = int(open(f"/sys/devices/system/cpu/cpu{cpu}/topology/core_id").read())
    except (OSError, ValueError):
        return None
    return 2 * core if cpu in FAST else core


FAST_SMU = sorted({smu_index(c) for c in FAST} - {None})
SLOW_SMU = sorted({smu_index(c) for c in SLOW} - {None})


def gm_line(now):
    try:
        b = open(GM, "rb").read()
    except OSError:
        return ""
    # gpu_metrics_v3_0: header 4, temps 19 u16 (38), gfx/vcn activity 2 u16, ipu 8 u16, c0 16 u16, dram/ipu bw 4 u16
    # = offset 4 + 38 + 4 + 16 + 32 + 8 = 102, pad to 104 for the u64 clock counter, then socket u32 @112, ipu u16 @116,
    # apu u32 @120, gfx u32 @124, dgpu u32 @128, all cores u32 @132, core power 16 u16 @136
    act = struct.unpack_from("<H", b, 42)[0]
    sock, = struct.unpack_from("<I", b, 112)
    gfx, = struct.unpack_from("<I", b, 124)
    cores, = struct.unpack_from("<I", b, 132)
    cp = struct.unpack_from("<16H", b, 136)
    fast_mw = sum(cp[i] for i in FAST_SMU if i < 16)
    slow_mw = sum(cp[i] for i in SLOW_SMU if i < 16)
    dr, dw = struct.unpack_from("<HH", b, 94)  # average_dram_reads / writes, MB/s
    return f"{now} - GM {sock} {gfx} {cores} {fast_mw} {slow_mw} {act} {dr} {dw}\n"


proc_prev = {}
next_proc = 0.0


def proc_line(now):
    cur = {}
    for p in os.listdir("/proc"):
        if not p.isdigit() or p == str(pid):
            continue
        try:
            st = open(f"/proc/{p}/stat").read()
        except OSError:
            continue
        r = st.rfind(")")
        comm = st[st.find("(") + 1:r].replace(" ", "_").replace(":", "_")
        f = st[r + 2:].split()
        cur[p] = (comm, int(f[11]) + int(f[12]), f[36])
    tick_ms = 1000.0 / os.sysconf("SC_CLK_TCK")
    rows = []
    for p, (comm, t, cpu) in cur.items():
        o = proc_prev.get(p)
        if o and t > o[1]:
            rows.append((t - o[1], comm, p, cpu))
    proc_prev.clear()
    proc_prev.update(cur)
    rows.sort(reverse=True)
    return f"{now} - PROC " + " ".join(f"{c}:{p}:{d * tick_ms:.0f}:{cpu}" for d, c, p, cpu in rows[:10]) + "\n"


gpu_prev = {}
gpu_pids = []
next_gpu_scan = 0.0


def drm_fds(p):
    """(fd paths) of a process's DRM render / card nodes, dedup'ed by drm-client-id when reading."""
    out = []
    try:
        for fd in os.listdir(f"/proc/{p}/fd"):
            try:
                if os.readlink(f"/proc/{p}/fd/{fd}").startswith("/dev/dri/"):
                    out.append(fd)
            except OSError:
                pass
    except OSError:
        pass
    return out


def gpu_line(now):
    global gpu_pids, next_gpu_scan
    if time.time() >= next_gpu_scan:
        next_gpu_scan = time.time() + 10.0
        gpu_pids = []
        for p in os.listdir("/proc"):
            if p.isdigit():
                fds = drm_fds(p)
                if fds:
                    gpu_pids.append((p, fds))
    rows = []
    for p, fds in gpu_pids:
        clients = {}
        for fd in fds:
            try:
                txt = open(f"/proc/{p}/fdinfo/{fd}").read()
            except OSError:
                continue
            cid, eng = None, {}
            for line in txt.splitlines():
                if line.startswith("drm-client-id:"):
                    cid = line.split()[1]
                elif line.startswith("drm-engine-"):
                    k, v = line.split(":", 1)
                    eng[k[len("drm-engine-"):]] = int(v.split()[0])
            if cid is not None:
                clients[cid] = eng
        gfx = sum(e.get("gfx", 0) + e.get("render", 0) for e in clients.values())
        comp = sum(e.get("compute", 0) for e in clients.values())
        o = gpu_prev.get(p)
        gpu_prev[p] = (gfx, comp)
        if o and (gfx > o[0] or comp > o[1]):
            try:
                comm = open(f"/proc/{p}/comm").read().strip().replace(" ", "_").replace(":", "_")
            except OSError:
                continue
            rows.append((gfx - o[0], comp - o[1], comm, p))
    rows.sort(reverse=True)
    return f"{now} - GPU " + " ".join(f"{c}:{p}:{g / 1e6:.1f}:{k / 1e6:.1f}" for g, k, c, p in rows[:8]) + "\n"


def freq_line(now):
    w = "-"
    if PPT:
        try:
            w = f"{int(open(PPT).read()) / 1e6:.2f}"
        except (OSError, ValueError):
            pass
    return f"{now} - FREQ {mean_mhz(FAST)} {mean_mhz(SLOW)} {w}\n"

task_dir = f"/proc/{pid}/task"
names = {}
next_mem = 0.0


def mem_line(now):
    kinds = {"anon": 0, "nvidia": 0, "shmem": 0, "so": 0, "file": 0}
    swap = 0
    kind = "anon"
    try:
        with open(f"/proc/{pid}/smaps") as f:
            for line in f:
                c = line[0]
                if c.isdigit() or c in "abcdef":  # a mapping header: range perms offset dev inode [path]
                    parts = line.split(None, 5)
                    path = parts[5].strip() if len(parts) > 5 else ""
                    if not path or path.startswith("[heap]") or path.startswith("[stack") or path.startswith("[anon"):
                        kind = "anon"
                    elif "nvidia" in path:
                        kind = "nvidia"
                    elif path.startswith("/memfd:") or path.startswith("/SYSV") or path.startswith("/dev/dri") or "(deleted)" in path:
                        kind = "shmem"
                    elif ".so" in path:
                        kind = "so"
                    else:
                        kind = "file"
                elif line.startswith("Rss:"):
                    kinds[kind] += int(line.split()[1])
                elif line.startswith("Swap:"):
                    swap += int(line.split()[1])
    except OSError:
        return ""
    return f"{now} - MEM " + " ".join(f"{k}={v}" for k, v in kinds.items()) + f" total={sum(kinds.values())} swap={swap}\n"
next_t = time.time()
while os.path.exists(task_dir):
    now = int(time.time() * 1000)
    try:
        tids = os.listdir(task_dir)
    except OSError:
        break
    lines = []
    for tid in tids:
        try:
            ss = open(f"{task_dir}/{tid}/schedstat").read().split()
            st = open(f"{task_dir}/{tid}/stat").read()
        except OSError:
            continue
        r = st.rfind(")")
        comm = st[st.find("(") + 1:r].replace(" ", "_")
        f = st[r + 2:].split()
        # f[0]=state, f[7]=minflt, f[9]=majflt, f[36]=processor (last CPU)
        lines.append(f"{now} {tid} {comm} {ss[0]} {ss[1]} {f[7]} {f[9]} {f[0]} {f[36]} {ss[2]}\n")
    cs, _ = psi("cpu")
    ms, mf = psi("memory")
    ios, _ = psi("io")
    vm = {}
    try:
        for line in open("/proc/vmstat"):
            k, v = line.split()
            if k in VM:
                vm[k] = v
    except OSError:
        pass
    ctxt = "0"
    try:
        for line in open("/proc/stat"):
            if line.startswith("ctxt"):
                ctxt = line.split()[1]
                break
    except OSError:
        pass
    if time.time() >= next_mem:
        next_mem = time.time() + 10.0
        ml = mem_line(now)
        if ml:
            lines.append(ml)
    if FAST or PPT:
        lines.append(freq_line(now))
    if GM:
        lines.append(gm_line(now))
    if time.time() >= next_proc:
        next_proc = time.time() + 1.0
        lines.append(proc_line(now))
        lines.append(gpu_line(now))
    lines.append(f"{now} - PSI {cs} {ms} {mf} {ios} {vm.get('pswpin',0)} {vm.get('pswpout',0)} {vm.get('pgmajfault',0)} {ctxt}\n")
    out.write("".join(lines))
    next_t += period
    d = next_t - time.time()
    if d > 0:
        time.sleep(d)
    else:
        next_t = time.time()
out.close()
