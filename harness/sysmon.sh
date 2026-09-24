#!/usr/bin/env bash
# System utilization sampler for a harness run (started by run.sh in the
# background, killed when the game exits). One CSV row every INTERVAL seconds:
#
#   epoch_ms, cpu_pct, cpu_busiest_core_pct, gpu_pct, gpu_sm_mhz, gpu_mem_mhz, gpu_w, gpu_c, vram_mib, game_cpu_pct, bat_w,
#   cpu_w, soc_w, total_w
#
# cpu_pct is whole-machine busy time from /proc/stat (100 = every hardware
# thread busy); cpu_busiest_core_pct is the busiest single logical CPU, which
# is what a single-threaded game loop pins; game_cpu_pct is the game process
# (utime+stime, 100 = one core). GPU columns come from nvidia-smi, or from the
# amdgpu sysfs (gpu_busy_percent + hwmon) on an AMD GPU/APU; empty otherwise.
# bat_w is the battery discharge rate (W, empty when on mains or no battery).
# Power (2026-09-24): cpu_w is the CPU package from RAPL (energy_uj deltas of every package domain, Intel and AMD
# Zen; root-only by default, scripts/power-access.sh makes it readable), soc_w the socket power an AMD APU's SMU
# reports (hwmon PPT: CPU + iGPU; gpu_w carries the same value there, as before), total_w the best whole-machine
# figure: the battery discharge when on battery, else the CPU package (or APU socket) plus a discrete GPU's board
# power; empty when the CPU side is unknown (a GPU-only sum would read as a total).
# Independent of MangoHud.
#   harness/sysmon.sh <out.csv> [interval-seconds] [pid-pattern]
set -u
out="$1"; interval="${2:-0.5}"; pat="${3:-^([^ ]*[/\\])?ProjectZomboid64(\.exe)?( |$)}"   # argv[0] only: a shell mentioning the name is not the game
ncpu=$(nproc)
echo "epoch_ms,cpu_pct,cpu_busiest_core_pct,gpu_pct,gpu_sm_mhz,gpu_mem_mhz,gpu_w,gpu_c,vram_mib,game_cpu_pct,bat_w,cpu_w,soc_w,total_w" > "$out"
have_smi=0; command -v nvidia-smi >/dev/null && have_smi=1
# amdgpu: first card with a busy counter; hwmon gives power (uW), temp (mC), sclk (Hz)
amd_dev=""; amd_hwmon=""
if (( !have_smi )); then
  for d in /sys/class/drm/card*/device; do
    [[ -r "$d/gpu_busy_percent" ]] || continue
    amd_dev="$d"; amd_hwmon=$(ls -d "$d"/hwmon/hwmon* 2>/dev/null | head -1); break
  done
fi
# an APU reports gpu_metrics format 2 or 3 (a discrete card 1); its hwmon power is the whole socket
amd_apu=0
[[ -n "$amd_dev" ]] && (( $(od -An -tu1 -j2 -N1 "$amd_dev/gpu_metrics" 2>/dev/null || echo 0) >= 2 )) && amd_apu=1
# RAPL package domains (intel-rapl:N, not the :N:M sub-domains), when this user may read them
rapl=(); for d in /sys/class/powercap/intel-rapl:*; do
  [[ "$d" =~ intel-rapl:[0-9]+$ && -r "$d/energy_uj" ]] || continue
  [[ "$(cat "$d/name" 2>/dev/null)" == package* ]] && rapl+=("$d")
done
declare -A rapl_prev=()
rapl_prev_ms=0
read_rapl() { # sets cpu_w: W of every package over the last interval (energy_uj wraps at max_energy_range_uj);
  cpu_w=""    # empty on the first call. Not called in $(...): the counters must persist between samples.
  (( ${#rapl[@]} )) || return 0
  local now e d du uj=0 ok=1
  now=$(date +%s%3N)
  for d in "${rapl[@]}"; do
    e=$(cat "$d/energy_uj" 2>/dev/null) || return 0
    if [[ -n "${rapl_prev[$d]:-}" ]]; then
      du=$(( e - rapl_prev[$d] ))
      (( du < 0 )) && du=$(( du + $(cat "$d/max_energy_range_uj") ))
      uj=$(( uj + du ))
    else ok=0; fi
    rapl_prev[$d]=$e
  done
  (( ok && rapl_prev_ms > 0 && now > rapl_prev_ms )) && cpu_w=$(awk -v u="$uj" -v ms="$(( now - rapl_prev_ms ))" 'BEGIN{printf "%.2f", u/ms/1000}')
  rapl_prev_ms=$now
}
bat=""; for b in /sys/class/power_supply/BAT*; do [[ -r "$b/power_now" || -r "$b/current_now" ]] && { bat="$b"; break; }; done
read_bat() { # W while discharging, else empty
  [[ -n "$bat" ]] || return 0
  [[ "$(cat "$bat/status" 2>/dev/null)" == Discharging ]] || return 0
  if [[ -r "$bat/power_now" ]]; then awk -v p="$(cat "$bat/power_now")" 'BEGIN{printf "%.2f", p/1e6}'
  else awk -v i="$(cat "$bat/current_now")" -v v="$(cat "$bat/voltage_now")" 'BEGIN{printf "%.2f", i*v/1e12}'; fi
}
read_amd() { # gpu_pct,gpu_sm_mhz,gpu_mem_mhz,gpu_w,gpu_c,vram_mib
  local pct mhz w c vram
  pct=$(cat "$amd_dev/gpu_busy_percent" 2>/dev/null)
  mhz=""; [[ -r "$amd_hwmon/freq1_input" ]] && mhz=$(awk -v f="$(cat "$amd_hwmon/freq1_input")" 'BEGIN{printf "%d", f/1e6}')
  w=""; if [[ -r "$amd_hwmon/power1_average" ]]; then w=$(cat "$amd_hwmon/power1_average"); elif [[ -r "$amd_hwmon/power1_input" ]]; then w=$(cat "$amd_hwmon/power1_input"); fi
  [[ -n "$w" ]] && w=$(awk -v p="$w" 'BEGIN{printf "%.2f", p/1e6}')
  c=""; [[ -r "$amd_hwmon/temp1_input" ]] && c=$(awk -v t="$(cat "$amd_hwmon/temp1_input")" 'BEGIN{printf "%d", t/1000}')
  vram=""; [[ -r "$amd_dev/mem_info_vram_used" ]] && vram=$(awk -v m="$(cat "$amd_dev/mem_info_vram_used")" 'BEGIN{printf "%d", m/1048576}')
  echo "$pct,$mhz,,$w,$c,$vram"
}
read_cpus() { # prints "idx busy total" per cpu line plus the aggregate as idx=all
  awk '/^cpu/ { idle=$5+$6; tot=0; for(i=2;i<=NF;i++) tot+=$i; print $1, tot-idle, tot }' /proc/stat
}
prev=$(read_cpus)
prev_game=""; game_pid=""
clk=$(getconf CLK_TCK)
while :; do
  sleep "$interval"
  now=$(date +%s%3N)
  cur=$(read_cpus)
  # per-cpu deltas -> aggregate and busiest core
  read -r cpu_pct busiest < <(paste <(echo "$prev") <(echo "$cur") | awk '
    { db=$5-$2; dt=$6-$3; p=(dt>0)?100*db/dt:0; if($1=="cpu"){agg=p} else if(p>mx){mx=p} }
    END { printf "%.1f %.1f\n", agg, mx }')
  prev=$cur
  gpu=",,,,,"
  if (( have_smi )); then
    gpu=$(nvidia-smi --query-gpu=utilization.gpu,clocks.sm,clocks.mem,power.draw,temperature.gpu,memory.used --format=csv,noheader,nounits 2>/dev/null | head -1 | tr -d ' ')
    [[ -n "$gpu" ]] || gpu=",,,,,"
  elif [[ -n "$amd_dev" ]]; then
    gpu=$(read_amd)
  fi
  [[ -n "$game_pid" && -d /proc/$game_pid ]] || { game_pid=$(pgrep -f "$pat" | head -1 || true); prev_game=""; }
  game_pct=""
  if [[ -n "$game_pid" && -r /proc/$game_pid/stat ]]; then
    t=$(awk '{print $14+$15}' /proc/$game_pid/stat 2>/dev/null || echo "")
    if [[ -n "$t" && -n "$prev_game" ]]; then
      game_pct=$(awk -v a="$prev_game" -v b="$t" -v c="$clk" -v dt="$interval" 'BEGIN{printf "%.1f", 100*(b-a)/c/dt}')
    fi
    prev_game=$t
  fi
  bat_w=$(read_bat); read_rapl; soc_w=""; dgpu_w=""
  gpu_w=$(cut -d, -f4 <<< "$gpu")
  if (( amd_apu )); then soc_w=$gpu_w; else dgpu_w=$gpu_w; fi
  total_w=$bat_w
  if [[ -z "$total_w" ]]; then
    base=${cpu_w:-$soc_w}
    [[ -n "$base" ]] && total_w=$(awk -v a="$base" -v b="${dgpu_w:-0}" 'BEGIN{printf "%.2f", a+b}')
  fi
  echo "$now,$cpu_pct,$busiest,$gpu,$game_pct,$bat_w,$cpu_w,$soc_w,$total_w" >> "$out"
done
