#!/usr/bin/env bash
# Compile the class overrides in src/ against the game's projectzomboid.jar.
#
# Output: build/classes/ — a package tree of .class files that install.sh
# copies over the game directory, plus build/classes/pzopt/build-info.properties
# recording which game build they were compiled against.
#
# The overridden game classes (OVERRIDES below) are compiled from Vineflower
# output of the shipped jar (see scripts/regen-overrides.sh), with our changes
# on top. New helper classes live in src/pzopt/ under the pzopt.* package.
#
# Compiles with --release <game JRE version> so the bytecode the game's bundled
# JRE loads is never newer than it can read.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/pz-env.sh"
JAR="$PZ_DIR/projectzomboid.jar"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$REPO/src"
BUILD="$REPO/build"
OUT="$BUILD/classes"
RELEASE="${RELEASE:-25}"   # java.class.version 69 in the shipped jar

# Game classes we shadow. Every inner class of these is shadowed too.
# The edited decompiled copies live in src/overrides/ (not committed); the two org.lwjglx classes are
# The Indie Stone's LWJGL 2 compatibility shim (HiDPI/Wayland fix, see docs/override-edits.md);
# TISLogoState is a from-scratch replacement in src/shims/ (committed).
OVERRIDES=(zombie/network/statistics/data/PerformanceStatistic zombie/core/skinnedmodel/animation/AnimationMultiTrack zombie/core/skinnedmodel/animation/AnimationTrack zombie/iso/IsoChunk zombie/iso/WorldStreamer zombie/iso/ChunkSaveWorker zombie/core/VBO/GLVertexBufferObject zombie/iso/fboRenderChunk/FBORenderCell zombie/GameWindow zombie/gameStates/TISLogoState org/lwjglx/opengl/Display org/lwjglx/input/Mouse zombie/fileSystem/FileSystemImpl zombie/tileDepth/TileDepthTextures zombie/core/textures/TextureIDAssetManager zombie/MapCollisionData zombie/iso/IsoMetaGrid zombie/scripting/ScriptParser zombie/iso/IsoMetaCell zombie/buildingRooms/BuildingRoomsEditor zombie/gameStates/GameLoadingState se/krka/kahlua/luaj/compiler/LuaCompiler zombie/core/skinnedmodel/advancedanimation/AnimationSet zombie/core/skinnedmodel/model/AnimationAssetManager zombie/fileSystem/TexturePackDevice zombie/scripting/objects/Item zombie/core/PerformanceSettings zombie/core/skinnedmodel/model/Model zombie/core/textures/ImageData zombie/iso/weather/fx/WeatherFxMask zombie/iso/objects/IsoLightSwitch se/krka/kahlua/j2se/KahluaTableImpl zombie/core/opengl/RenderThread zombie/iso/fboRenderChunk/FBORenderCutaways zombie/audio/parameters/ParameterZone zombie/iso/IsoChunkMap zombie/core/opengl/VBORenderer zombie/iso/IsoPuddles zombie/iso/weather/fx/ParticleRectangle zombie/iso/weather/fx/WeatherParticleDrawer zombie/iso/LightingJNI zombie/iso/weather/fog/ImprovedFog zombie/iso/weather/fog/ImprovedFogDrawer zombie/core/textures/MultiTextureFBO2 zombie/network/NetChecksum zombie/WorldSoundManager zombie/iso/FishSchoolManager zombie/characters/IsoZombie zombie/ai/State zombie/GameProfiler zombie/core/skinnedmodel/animation/AnimationPlayer zombie/MovingObjectUpdateScheduler zombie/characters/IsoPlayer zombie/characters/ecs/ECSComponent zombie/characters/ecs/ECSEntity zombie/characters/action/conditions/CharacterVariableCondition zombie/core/skinnedmodel/advancedanimation/AnimatedModel zombie/characters/IsoGameCharacter zombie/characters/action/ActionContext zombie/iso/IsoWorld zombie/core/textures/TextureDraw zombie/core/skinnedmodel/model/ModelInstance zombie/iso/IsoCamera zombie/iso/weather/WeatherShader zombie/iso/WaterShader zombie/iso/PuddlesShader zombie/core/skinnedmodel/ModelManager zombie/savefile/SavefileThumbnail zombie/gameStates/MainScreenState zombie/popman/ZombiePopulationManager zombie/core/properties/PropertyContainer zombie/core/textures/PNGDecoder zombie/tileDepth/TileDepthTexture zombie/iso/zones/Zone zombie/AmbientStreamManager zombie/Lua/Event zombie/vispoly/VisibilityPolygon2 zombie/iso/sprite/IsoCursor zombie/input/GameKeyboard zombie/input/KeyboardStateCache zombie/input/KeyboardState zombie/input/MouseStateCache zombie/input/MouseState zombie/input/ControllerStateCache zombie/input/Mouse zombie/pathfind/PolygonalMap2 zombie/core/opengl/ShaderUnit fmod/fmod/FMODSoundEmitter zombie/audio/ObjectAmbientEmitters zombie/audio/FMODAmbientWalls zombie/FliesSound zombie/core/opengl/ShaderUniformSetter zombie/core/skinnedmodel/DeadBodyAtlas zombie/core/textures/TextureID zombie/core/textures/TextureFBO zombie/iso/RoomDef zombie/randomizedWorld/randomizedBuilding/RBTrashed zombie/viewCone/ChunkRenderShader zombie/iso/fboRenderChunk/FBORenderTrees zombie/core/physics/WorldSimulation zombie/iso/PlayerCamera)

[[ -f "$JAR" ]] || { echo "jar not found: $JAR" >&2; exit 1; }
command -v javac >/dev/null || { echo "javac not on PATH" >&2; exit 1; }

rm -rf "$OUT" "$BUILD/stock"
mkdir -p "$OUT" "$BUILD/stock"

echo "compiling src/ against $JAR (--release $RELEASE)"
mapfile -t sources < <(find "$SRC/overrides" "$SRC/shims" "$SRC/pzopt" -name '*.java' | sort)
javac --release "$RELEASE" -nowarn -Xlint:-options -parameters -g \
  -cp "$JAR" -d "$OUT" "${sources[@]}"

# Experiment: the C++ LOS pass (Config playerLosNative). Only with PZOPT_NATIVE=1, so the shipped build never
# carries a native library; lands under natives/ like the game's own .so files.
if [[ "${PZOPT_NATIVE:-0}" == "1" && -f "$SRC/native/pzopt_los.cpp" ]]; then
  command -v g++ >/dev/null || { echo "PZOPT_NATIVE=1 but no g++" >&2; exit 1; }
  mkdir -p "$OUT/natives" "$BUILD/native"
  g++ -O2 -shared -fPIC -o "$BUILD/native/libpzopt_los64.so" "$SRC/native/pzopt_los.cpp"
  cp "$BUILD/native/libpzopt_los64.so" "$OUT/natives/"
  echo "built natives/libpzopt_los64.so (playerLosNative experiment; copy kept in build/native/)"
fi

# The DLSS shim (Config upscaler=dlss; src/native/pzopt_ngx.cpp, docs/plan-upscalers.md): Vulkan + NGX on the GL
# context's GPU, images shared with GL. Built when the DLSS SDK checkout is present (PZOPT_DLSS_SDK, default
# ~/.local/share/nvidia-dlss-sdk: git clone --depth 1 https://github.com/NVIDIA/DLSS) and g++ + the Vulkan headers
# are installed; PZOPT_DLSS=0 skips it. The DLSS library itself (lib/Linux_x86_64/rel/libnvidia-ngx-dlss.so.*) is
# copied next to the shim under natives/, where the shim tells NGX to look. Without either the key falls back to fsr1.
DLSS_SDK="${PZOPT_DLSS_SDK:-$HOME/.local/share/nvidia-dlss-sdk}"
if [[ "${PZOPT_DLSS:-1}" == "1" && -f "$SRC/native/pzopt_ngx.cpp" && -f "$DLSS_SDK/lib/Linux_x86_64/libnvsdk_ngx.a" ]] \
   && command -v g++ >/dev/null && [[ -f /usr/include/vulkan/vulkan.h ]]; then
  mkdir -p "$OUT/natives" "$BUILD/native"
  if [[ ! -f "$BUILD/native/libpzopt_ngx64.so" || "$SRC/native/pzopt_ngx.cpp" -nt "$BUILD/native/libpzopt_ngx64.so" ]]; then
    g++ -O2 -shared -fPIC -std=c++17 -fvisibility=hidden -I"$DLSS_SDK/include" -o "$BUILD/native/libpzopt_ngx64.so" \
      "$SRC/native/pzopt_ngx.cpp" "$DLSS_SDK/lib/Linux_x86_64/libnvsdk_ngx.a" -ldl -lpthread 2>&1 | grep -v -i "deprecated\|SR_DEPRECATED\|note:\|^\s*[0-9]* |\|^\s*|" || true
    [[ -f "$BUILD/native/libpzopt_ngx64.so" ]] || { echo "the DLSS shim did not build" >&2; exit 1; }
  fi
  cp "$BUILD/native/libpzopt_ngx64.so" "$OUT/natives/"
  dlss_lib=$(ls "$DLSS_SDK"/lib/Linux_x86_64/rel/libnvidia-ngx-dlss.so.* 2>/dev/null | head -1)
  if [[ -n "$dlss_lib" ]]; then
    cp "$dlss_lib" "$OUT/natives/"
    echo "built natives/libpzopt_ngx64.so with $(basename "$dlss_lib") (upscaler=dlss)"
  else
    echo "built natives/libpzopt_ngx64.so; no DLSS library in $DLSS_SDK/lib/Linux_x86_64/rel (upscaler=dlss needs it under natives/)"
  fi
fi

# Loose Lua under src/lua/ ships next to the classes: install copies build/classes/ onto the
# game dir, so build/classes/media/lua/client/pzopt/*.lua lands in media/lua/client/pzopt/.
if [[ -d "$SRC/lua" ]]; then
  # the Optimizations tab's search index of which Java classes read each Config key
  python3 "$REPO/scripts/option-classes.py"
  mkdir -p "$OUT/media/lua"
  cp -r "$SRC/lua/." "$OUT/media/lua/"
fi
# Other loose media (src/media/ui/pzopt/compare/*.gif: the Optimizations tab's stock-vs-optimized clips,
# harness/menu-gifs.py) lands under the game dir's media/ the same way.
if [[ -d "$SRC/media" ]]; then
  mkdir -p "$OUT/media"
  cp -r "$SRC/media/." "$OUT/media/"
fi
# Puddle shader variants for Config.puddleEarlyZ (media/shaders/pzopt_puddles_*), derived from the installed game's
# puddle shaders so a game update is picked up: the entry files with the include renamed, the .h prototype stubs
# copied, and the two shader units changed in one place each. The vertex unit also writes the depth attribute to
# gl_Position.z; the fragment unit loses its gl_FragDepth writes. Any assignment to gl_FragDepth in a fragment
# program, even in a function never called, makes the depth shader-written and switches the early depth test off.
sh="$(dirname "$JAR")/media/shaders"
if [[ -f "$sh/puddles_common.frag.glsl" && -f "$sh/puddles_common.vert.glsl" ]]; then
  osh="$OUT/media/shaders"
  mkdir -p "$osh"
  for q in hq mq lq; do
    sed 's/#include "puddles_common\.vert"/#include "pzopt_puddles_common.vert"/' "$sh/puddles_$q.vert" > "$osh/pzopt_puddles_$q.vert"
    sed 's/#include "puddles_common\.frag"/#include "pzopt_puddles_common.frag"/' "$sh/puddles_$q.frag" > "$osh/pzopt_puddles_$q.frag"
    grep -q pzopt_puddles_common "$osh/pzopt_puddles_$q.vert" && grep -q pzopt_puddles_common "$osh/pzopt_puddles_$q.frag" \
      || { echo "puddles_$q shader entry has no puddles_common include; puddleEarlyZ shaders not generated" >&2; exit 1; }
  done
  cp "$sh/puddles_common.vert.h" "$osh/pzopt_puddles_common.vert.h"
  cp "$sh/puddles_common.frag.h" "$osh/pzopt_puddles_common.frag.h"
  sed 's/^\(\s*\)vDepth = aFragDepth;/\1vDepth = aFragDepth;\n\1gl_Position.z = (aFragDepth * 2.0 - 1.0) * gl_Position.w; \/\/ pzopt: depth from the vertex (puddleEarlyZ, drawn with GL_DEPTH_CLAMP)/' \
    "$sh/puddles_common.vert.glsl" > "$osh/pzopt_puddles_common.vert.glsl"
  grep -v 'gl_FragDepth = vDepth;' "$sh/puddles_common.frag.glsl" > "$osh/pzopt_puddles_common.frag.glsl"
  grep -q 'gl_Position.z = (aFragDepth' "$osh/pzopt_puddles_common.vert.glsl" && ! grep -q 'gl_FragDepth' "$osh/pzopt_puddles_common.frag.glsl" \
    || { echo "puddles_common shader units changed shape; puddleEarlyZ shaders not generated" >&2; exit 1; }
else
  echo "puddle shaders not found next to the jar; the puddleEarlyZ shaders are not generated" >&2
fi
# Vision-cone blur split for Config.visBlurReduce (media/shaders/pzopt_visBlurReduce.*, pzopt_visibilityBlur.*), derived
# from the installed game's visibilityBlur shader: its 25-tap sum depends only on the blur-texture texel a screen pixel
# maps to, so pzopt_visBlurReduce runs the stock loop once per blur texel into a float texture and pzopt_visibilityBlur is
# the stock screen pass with the loop replaced by one texelFetch of that sum. The loop is copied verbatim; the build
# fails if the stock shader no longer has it.
if [[ -f "$sh/visibilityBlur.frag" && -f "$sh/visibilityBlur.vert" ]]; then
  osh="$OUT/media/shaders"
  mkdir -p "$osh"
  python3 - "$sh/visibilityBlur.frag" "$osh" <<'PY' || { echo "visibilityBlur shader changed shape; visBlurReduce shaders not generated" >&2; exit 1; }
import re, sys
src = open(sys.argv[1]).read()
out = sys.argv[2]
loop = re.search(r"\n([ \t]*for \(int y = -BLUR_RANGE; y <= BLUR_RANGE; y\+\+\)\s*\{\s*for \(int x = -BLUR_RANGE; x <= BLUR_RANGE; x\+\+\)\s*\{.*?alpha \+= sam\.a;\s*\}\s*\})", src, re.S)
head = src[:src.index("void main()")]
assert loop and "uniform sampler2D depth;" in src and "ivec2 pixel = ivec2(gl_FragCoord.xy / screenSize * texSize);" in src
assert "vec2 maxUV = texSize / TextureSize;" in src and "vec2 pixelStep = vec2(1.0, 1.0) / TextureSize;" in src
# the reduce pass: one fragment per blur texel, the stock loop verbatim, the raw sum out
reduce = head + """void main()
{
    // pzopt: visBlurReduce, the stock screen pass's 25-tap sum for the blur texel this fragment covers
    vec2 maxUV = texSize / TextureSize;
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec2 pixelStep = vec2(1.0, 1.0) / TextureSize;
    float alpha = 0.0;
""" + loop.group(1) + """
    colour = vec4(alpha, 0.0, 0.0, 1.0);
}
"""
open(out + "/pzopt_visBlurReduce.frag", "w").write(reduce)
screen = src.replace("uniform sampler2D depth;", "uniform sampler2D depth;\nuniform sampler2D reduced; // pzopt: visBlurReduce", 1)
screen = screen.replace(loop.group(1), "    alpha = texelFetch(reduced, clamp(pixel, ivec2(0), ivec2(texSize) - 1), 0).r; // pzopt: visBlurReduce, the 25-tap sum from pzopt_visBlurReduce", 1)
open(out + "/pzopt_visibilityBlur.frag", "w").write(screen)
PY
  cp "$sh/visibilityBlur.vert" "$osh/pzopt_visBlurReduce.vert"
  cp "$sh/visibilityBlur.vert" "$osh/pzopt_visibilityBlur.vert"
fi

# Extract the stock copies of the overridden classes for comparison.
patterns=()
for c in "${OVERRIDES[@]}"; do patterns+=("$c.class" "$c\$*.class"); done
( cd "$BUILD/stock" && for pat in "${patterns[@]}"; do unzip -q -o "$JAR" "$pat" || [[ $? -eq 11 ]]; done )  # 11 = pattern matched nothing (a class without inner classes)

# Every class file the jar has for an override must exist in our output too,
# otherwise the loose top-level class would load against the jar's inner class.
missing=0
while IFS= read -r f; do
  [[ -f "$OUT/$f" ]] || { echo "MISSING from build: $f" >&2; missing=1; }
done < <(cd "$BUILD/stock" && find . -name '*.class' | sed 's|^\./||' | sort)
[[ $missing -eq 0 ]]

# Structural check: every member other game classes can link against
# (everything but private) must still exist with the same descriptor in our
# build. Added members are fine; removed or changed ones would break linking.
sig() { javap -p -cp "$1" "$2" 2>/dev/null | grep -vE '^\s*(private|Compiled from|})' | grep -v 'lambda\$' | sort; }
for c in "${OVERRIDES[@]}"; do
  cls="${c//\//.}"
  missing_members=$(comm -23 <(sig "$BUILD/stock" "$cls") <(sig "$OUT" "$cls"))
  if [[ -n "$missing_members" ]]; then
    echo "SIGNATURE MISMATCH in $cls — stock members missing or changed in build:" >&2
    echo "$missing_members" | head -20 >&2
    exit 1
  fi
done

# Bytecode parity (2026-09-23): every override method without a pzopt edit must compile to what the jar has.
# Vineflower mis-renders compile fine (the parking-lot loop that spawned one row of cars, a float divide for a
# double one); scripts/bytecode-audit.py catches them. A mismatch is a decompiler bug to fix in src/overrides,
# or an unmarked edit that needs its `// pzopt:` marker.
python3 "$REPO/scripts/bytecode-audit.py" --classes "$OUT" --stock "$BUILD/stock" --src "$SRC/overrides" \
  || { echo "BYTECODE AUDIT FAILED: see the MISMATCH lines above (scripts/CLAUDE.md, bytecode-audit.py)" >&2; exit 1; }

# Record what we built against. The overrides read this at runtime and
# disable themselves if the loaded game's revision differs.
revision=$(javap -constants -cp "$JAR" zombie.GitVersion | sed -n 's/.*REVISION = "\([^"]*\)".*/\1/p')
[[ -n "$revision" ]] || { echo "could not read zombie.GitVersion.REVISION from jar" >&2; exit 1; }
jar_sha=$(sha256sum "$JAR" | cut -d' ' -f1)
{
  echo "# generated by scripts/build.sh $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "revision=$revision"
  echo "jar.sha256=$jar_sha"
  echo "jar.size=$(stat -c %s "$JAR")"
  echo "release=$RELEASE"
  # what this build is, for the in-game updater (pzopt.Updater): the short commit the release tag
  # win-<revision>-<commit> carries ("-dirty" when src/ has uncommitted changes) and the build time
  echo "commit=$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo unknown)$( [[ -n "$(git -C "$REPO" status --porcelain -- src scripts/build.sh 2>/dev/null)" ]] && echo -dirty )"
  echo "built=$(date -u +%s)"
  echo "overrides=$(IFS=,; echo "${OVERRIDES[*]}")"
  # sha256 of each stock class we shadow, so a same-revision hotfix is caught too
  while IFS= read -r f; do
    echo "stock.$(echo "$f" | tr '/$' '._' | sed 's/\.class$//')=$(sha256sum "$BUILD/stock/$f" | cut -d' ' -f1)"
  done < <(cd "$BUILD/stock" && find . -name '*.class' | sed 's|^\./||' | sort)
} > "$OUT/pzopt/build-info.properties"

n=$(find "$OUT" -name '*.class' | wc -l)
echo "built $n class files into build/classes for game revision $revision"
