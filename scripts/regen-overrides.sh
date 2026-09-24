#!/usr/bin/env bash
# Decompile the overridden game classes from the current projectzomboid.jar
# with Vineflower into build/vineflower/, so after a game update the fresh
# output can be diffed against src/overrides/ and our changes re-applied.
#
# Vineflower (not CFR) is used for the overrides because its output for these
# classes recompiles with a single fix; CFR's needs several (loop-variable
# scoping, un-rendered StringConcatFactory calls). decompiled/ stays CFR for
# reading. Get the jar from https://github.com/Vineflower/vineflower/releases.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/pz-env.sh"
JAR="$PZ_DIR/projectzomboid.jar"
VF_JAR="${VF_JAR:-$HOME/.local/share/java/vineflower.jar}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$REPO/build/vineflower"
OVERRIDES=(zombie/network/statistics/data/PerformanceStatistic zombie/core/skinnedmodel/animation/AnimationMultiTrack zombie/core/skinnedmodel/animation/AnimationTrack zombie/iso/IsoChunk zombie/iso/WorldStreamer zombie/iso/ChunkSaveWorker zombie/core/VBO/GLVertexBufferObject zombie/iso/fboRenderChunk/FBORenderCell zombie/GameWindow org/lwjglx/opengl/Display org/lwjglx/input/Mouse zombie/fileSystem/FileSystemImpl zombie/tileDepth/TileDepthTextures zombie/core/textures/TextureIDAssetManager zombie/MapCollisionData zombie/iso/IsoMetaGrid zombie/scripting/ScriptParser zombie/iso/IsoMetaCell zombie/buildingRooms/BuildingRoomsEditor zombie/gameStates/GameLoadingState se/krka/kahlua/luaj/compiler/LuaCompiler zombie/core/skinnedmodel/advancedanimation/AnimationSet zombie/core/skinnedmodel/model/AnimationAssetManager zombie/fileSystem/TexturePackDevice zombie/scripting/objects/Item zombie/core/PerformanceSettings zombie/core/skinnedmodel/model/Model zombie/core/textures/ImageData zombie/iso/weather/fx/WeatherFxMask zombie/iso/objects/IsoLightSwitch se/krka/kahlua/j2se/KahluaTableImpl zombie/core/opengl/RenderThread zombie/iso/fboRenderChunk/FBORenderCutaways zombie/audio/parameters/ParameterZone zombie/iso/IsoChunkMap zombie/core/opengl/VBORenderer zombie/iso/IsoPuddles zombie/iso/weather/fx/ParticleRectangle zombie/iso/weather/fx/WeatherParticleDrawer zombie/iso/LightingJNI zombie/iso/weather/fog/ImprovedFog zombie/iso/weather/fog/ImprovedFogDrawer zombie/core/textures/MultiTextureFBO2 zombie/network/NetChecksum zombie/WorldSoundManager zombie/iso/FishSchoolManager zombie/characters/IsoZombie zombie/ai/State zombie/GameProfiler zombie/core/skinnedmodel/animation/AnimationPlayer zombie/MovingObjectUpdateScheduler zombie/characters/IsoPlayer zombie/characters/ecs/ECSComponent zombie/characters/ecs/ECSEntity zombie/characters/action/conditions/CharacterVariableCondition zombie/core/skinnedmodel/advancedanimation/AnimatedModel zombie/characters/IsoGameCharacter zombie/characters/action/ActionContext zombie/iso/IsoWorld zombie/core/textures/TextureDraw zombie/core/skinnedmodel/model/ModelInstance zombie/iso/IsoCamera zombie/iso/weather/WeatherShader zombie/iso/WaterShader zombie/iso/PuddlesShader zombie/core/skinnedmodel/ModelManager zombie/savefile/SavefileThumbnail zombie/gameStates/MainScreenState zombie/popman/ZombiePopulationManager zombie/core/properties/PropertyContainer zombie/core/textures/PNGDecoder zombie/tileDepth/TileDepthTexture zombie/iso/zones/Zone zombie/AmbientStreamManager zombie/Lua/Event zombie/vispoly/VisibilityPolygon2 zombie/iso/sprite/IsoCursor zombie/input/GameKeyboard zombie/input/KeyboardStateCache zombie/input/KeyboardState zombie/input/MouseStateCache zombie/input/MouseState zombie/input/ControllerStateCache zombie/input/Mouse zombie/pathfind/PolygonalMap2)

[[ -f "$JAR" ]] || { echo "jar not found: $JAR" >&2; exit 1; }
[[ -f "$VF_JAR" ]] || { echo "vineflower not found: $VF_JAR" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/src"
patterns=()
for c in "${OVERRIDES[@]}"; do patterns+=("$c.class" "$c\$*.class"); done
( cd "$OUT/classes" && for pat in "${patterns[@]}"; do unzip -q -o "$JAR" "$pat" || [[ $? -eq 11 ]]; done )  # 11 = pattern matched nothing (a class without inner classes)
java -jar "$VF_JAR" --silent=1 -jrt=1 --indent-string='   ' "$OUT/classes" "$OUT/src" >/dev/null

echo "decompiled into $OUT/src; diff against src/overrides:"
for c in "${OVERRIDES[@]}"; do
  echo "  diff -u $OUT/src/$c.java src/overrides/$c.java"
done
