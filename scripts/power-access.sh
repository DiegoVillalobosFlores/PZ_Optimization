#!/usr/bin/env bash
# Lets a normal user read the CPU's RAPL energy counters, so harness/sysmon.sh (cpu_w / total_w in every run's
# sysmon.csv) and the in-game profiler (pzopt.Power, the overlay's power line) can report CPU package watts.
# Linux makes /sys/class/powercap/intel-rapl:*/energy_uj root-only (0400) since the PLATYPUS power side channel
# (CVE-2020-8694 Intel, CVE-2020-12912 AMD); this undoes that for this machine, so run it only where every local
# user is trusted. Needs sudo (asks for the password itself):
#   scripts/power-access.sh            # install the udev rule (every boot) and apply it now
#   scripts/power-access.sh --remove   # remove the rule; the counters are root-only again after the next boot
set -euo pipefail
RULE=/etc/udev/rules.d/90-pzopt-rapl.rules
if [[ "${1:-}" == "--remove" ]]; then
  sudo rm -f "$RULE"
  echo "removed $RULE (root-only again after a reboot, or now: sudo chmod o-r /sys/class/powercap/intel-rapl:*/energy_uj)"
  exit 0
fi
sudo tee "$RULE" >/dev/null <<'EOF'
# PZ_Optimization scripts/power-access.sh: RAPL energy counters readable by every user (power traces)
ACTION=="add", SUBSYSTEM=="powercap", KERNEL=="intel-rapl:*", RUN+="/usr/bin/chmod o+r /sys%p/energy_uj"
EOF
sudo chmod 644 "$RULE"
sudo chmod o+r /sys/class/powercap/intel-rapl:*/energy_uj
for d in /sys/class/powercap/intel-rapl:*; do
  [[ "$d" =~ intel-rapl:[0-9]+$ ]] || continue
  a=$(cat "$d/energy_uj"); sleep 0.5; b=$(cat "$d/energy_uj")
  echo "$(cat "$d/name"): $(awk -v a="$a" -v b="$b" 'BEGIN{printf "%.1f", (b-a)/5e5}') W (readable: $RULE)"
done
