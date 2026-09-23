#!/usr/bin/env bash
# Build (and with --publish upload) the files the Optimizations tab's "Install DLSS files" button downloads
# (pzopt.UpscalerDeps): build/pzopt-dlss-linux-x64.zip with
#   libpzopt_ngx64.so      the DLSS shim of this checkout (build/native/, made by scripts/build.sh with the SDK),
#   pzopt-dlss-files.txt   "name sha256 [source url]" per file: the shim, and NVIDIA's DLSS library pinned by its
#                          sha256 with its URL in NVIDIA's own DLSS repository at the SDK tag checked out here,
#   NOTICE.txt             where each file comes from.
# NVIDIA's library is not in the zip: the button fetches it from NVIDIA (the DLSS SDK license allows distributing it
# only as part of an application, not stand-alone). Releases of the classes stay native-free (release.sh, PZOPT_DLSS=0).
#
# Usage: scripts/dlss-natives.sh [--publish]
#   --publish   check that NVIDIA's URL serves exactly that file, then create the GitHub release dlss-linux-<commit>
#               (not marked latest: the installers and the updater look for pzopt-<rev>-classes.zip by name).
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=$PWD
SDK="${PZOPT_DLSS_SDK:-$HOME/.local/share/nvidia-dlss-sdk}"
publish=0; [[ "${1:-}" == --publish ]] && publish=1

shim="$REPO/build/native/libpzopt_ngx64.so"
[[ -f "$shim" && ! "$REPO/src/native/pzopt_ngx.cpp" -nt "$shim" ]] || { echo "no current $shim: run scripts/build.sh with the DLSS SDK first" >&2; exit 1; }
lib=$(ls "$SDK"/lib/Linux_x86_64/rel/libnvidia-ngx-dlss.so.* 2>/dev/null | head -1)
[[ -n "$lib" ]] || { echo "no libnvidia-ngx-dlss.so.* in $SDK/lib/Linux_x86_64/rel" >&2; exit 1; }
name=$(basename "$lib"); ver=${name#libnvidia-ngx-dlss.so.}
tag=$(git -C "$SDK" describe --tags --exact-match 2>/dev/null || true)
[[ "$tag" == "v$ver" ]] || { echo "the SDK checkout is at '$tag', not the tag v$ver of its library" >&2; exit 1; }
url="https://github.com/NVIDIA/DLSS/raw/$tag/lib/Linux_x86_64/rel/$name"
commit=$(git rev-parse --short HEAD)

out="$REPO/build/pzopt-dlss-linux-x64.zip"
python3 - "$shim" "$lib" "$url" "$out" "$commit" "$tag" <<'PY'
import hashlib, sys, zipfile
shim, lib, url, out, commit, tag = sys.argv[1:]
sha = lambda p: hashlib.sha256(open(p, 'rb').read()).hexdigest()
name = lib.rsplit('/', 1)[1]
files = (f"# name sha256 [source url]: pzopt.UpscalerDeps checks every file against its sha256\n"
         f"libpzopt_ngx64.so {sha(shim)}\n{name} {sha(lib)} {url}\n")
notice = (f"PZ_Optimization DLSS files (shim from commit {commit}, NVIDIA DLSS {tag}).\n\n"
          "libpzopt_ngx64.so is the PZ_Optimization DLSS shim (src/native/pzopt_ngx.cpp); it links NVIDIA's NGX SDK\n"
          "library (libnvsdk_ngx.a) from the NVIDIA DLSS SDK, used under NVIDIA's DLSS SDK license.\n\n"
          f"{name} is NVIDIA's DLSS runtime. It is not in this zip: the Optimizations tab downloads it from NVIDIA's\n"
          f"DLSS repository ({url}) and checks it against the sha256 in pzopt-dlss-files.txt. It is NVIDIA's\n"
          "software, under the NVIDIA DLSS SDK license (https://github.com/NVIDIA/DLSS/blob/main/LICENSE.txt).\n")
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    z.write(shim, 'libpzopt_ngx64.so')
    z.writestr('pzopt-dlss-files.txt', files)
    z.writestr('NOTICE.txt', notice)
print(files, end='')
PY
ls -la "$out" | awk '{print "wrote " $9 " (" $5 " bytes)"}'

if [[ $publish -eq 1 ]]; then
  want=$(sha256sum "$lib" | cut -d' ' -f1)
  got=$(curl -fsSL "$url" | sha256sum | cut -d' ' -f1)
  [[ "$want" == "$got" ]] || { echo "NVIDIA's URL serves $got, not the SDK's $want: not publishing" >&2; exit 1; }
  git merge-base --is-ancestor HEAD origin/master || { echo "HEAD is not on origin/master: push first" >&2; exit 1; }
  rtag="dlss-linux-$commit"
  gh release create "$rtag" "$out" --target "$(git rev-parse HEAD)" --latest=false \
    --title "DLSS files for Linux (shim $commit, NVIDIA DLSS $tag)" \
    --notes "What the Optimizations tab's \"Install DLSS files\" button downloads (Linux x86-64, NVIDIA RTX): the PZ_Optimization DLSS shim built from $commit and the list that pins NVIDIA's DLSS library ($name) by sha256. NVIDIA's library itself is fetched from NVIDIA's DLSS repository ($url), not from this release. Needs the proprietary NVIDIA driver (libnvidia-ngx.so.1) and the Vulkan loader. Not a build of the classes: install those from the win-* releases."
fi
