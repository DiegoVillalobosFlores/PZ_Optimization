#!/usr/bin/env bash
# Record one segment of the showcase video (harness/stitch-showcase.py): the same route once
# with every optimization off and once with the build defaults, in-game overlay (Large font)
# on screen, no MangoHud, AV1 HDR monitor capture. Both sides are uncapped so the overlay's
# fps is the real frame time. The optimized side passes the stock option uiRenderOffscreen=true;
# the stock side keeps ~/Zomboid/options.ini as it is (the maintainer's has uiRenderOffscreen=true
# too, so the 2026-09-20 recordings compare pzopt alone; check `game_options` in run.opts).
#
#   harness/showcase-record.sh <segment> <stock|opt> [label-suffix]
#   segments: drive120 (E:1200 at ~122 km/h), spin (S:450 turn=90, 25 s),
#             louisville (the spin route through downtown Louisville, zombie population maxed: run.sh --preset louisville),
#             fog120 (drive120 + fog=heavy), storm120 (drive120 + weather=storm),
#             menu (verify mode: enters the world and sits there; drive Escape > Options > Optimizations with
#             xdotool while it runs. quit_after cannot quit while the Options screen is open: close it, or
#             click Quit to desktop yourself)
#
# Stock = every runtime AND boot key off (the boot/load counters of the video need the shipped
# game's boot too), cap removed (uncappedFps=true). The 120 km/h drive leaves the road about
# every other attempt: check `route complete` in console.txt and re-run the side that failed.
set -euo pipefail
cd "$(dirname "$0")/.."
seg="${1:?segment}"; side="${2:?stock|opt}"; suffix="${3:-1}"
label="show-${seg}-${side}-${suffix}"

common=(--record --no-mangohud --env MANGOHUD_CONFIG=no_display --no-dashboard --retries 0
        --prop instrument=true --prop overlay=true --prop overlayFont=Large)
stock_props=(uncappedFps=true parallel=false wake=false persistentVbo=false treesInChunkTexture=false
  windowsInChunkTexture=false translucentTilesInChunkTexture=false hotsaveIntervalSec=0 bakeBudget=0
  lightingBudget=0 cutawayFast=false lightingRebakeMs=0 rebakeBudget=0 cutawayRadius=0 gridStackInterval=0
  lightSwitchCheckFrames=0 weatherMaskIdleSkip=false treeBakeDirect=false cutawayInvalidateChanged=false
  cutawayVisitPrefilter=false lightInfoOncePerFrame=false lightInfoChunkGate=false
  occlusionSkipLightingOnly=false soundZoneCache=false chunkHandoffDivisor=0 hotsaveStaged=false
  weatherFxScalePct=100 vboBatchKb=4 vboFastQuads=false puddleCache=false rainTiles=false
  parallelDepthMaps=false loaderCpuFixes=false scriptParserFast=false fmodAsync=false noLoadFade=false
  noIntroWait=false bootPump=false earlyModels=false luaPrecompile=false preloadAnimSets=false
  animClipCache=false packIndex=false itemParamSwitch=false shaderCache=false mipmapArrays=false)
case "$side" in
  stock) for p in "${stock_props[@]}"; do common+=(--prop "$p"); done ;;
  opt)   common+=(--prop uncappedFps=true --option uiRenderOffscreen=true) ;;
  *) echo "side must be stock or opt" >&2; exit 2 ;;
esac

drive=(--mode drive --flag path=8010,11204.5/9210,11204.5 --flag kmh=120 --flag zoom=max --route-seconds 60)  # the path pilot, KY-60 east (2026-09-24; before: route=E:1200 kmh=193)
case "$seg" in
  drive120) args=("${drive[@]}") ;;
  fog120)   args=("${drive[@]}" --flag fog=heavy) ;;
  storm120) args=("${drive[@]}" --flag weather=storm) ;;
  spin)     args=(--mode bench --flag route=S:450 --flag turn=90 --flag zoom=max --route-seconds 25) ;;
  louisville) args=(--preset louisville) ;;
  menu)     args=(--mode verify --quit-after 75) ;;
  *) echo "unknown segment: $seg" >&2; exit 2 ;;
esac

echo "recording $label"
harness/run.sh --label "$label" "${args[@]}" "${common[@]}"
d=$(ls -d harness/runs/"$label"-* | tail -1)
grep -E 'route complete|harness: world ready' "$d/console.txt" | tail -2 || true
