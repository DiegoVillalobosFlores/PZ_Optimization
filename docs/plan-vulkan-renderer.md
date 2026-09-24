# Plan: move the renderer from OpenGL to Vulkan (2026-09-19)

Written after an inventory of every OpenGL touch point in the decompiled
build, the shipped shader set, the bundled LWJGL, and the frame-time runs
recorded in `docs/archive/2026-09-24/results.md` and `docs/plan-resource-use.md`. It follows the
same shape as `docs/plan-driving-frame-time.md`: what was found, what the
target is, the work in order, and how each step is verified. Numbers point at
run directories under `harness/runs/` or at the decompiled tree (`decompiled/`,
local only, see `docs/override-edits.md` for how overrides are recorded).

This is a large project. The honest summary is in section 2: the game is
currently GPU-bound on NVIDIA GL at the driving zoom, and a Vulkan backend does
not make fragments cheaper. The plan therefore starts with a measured
go/no-go gate (Phase 0) and only then commits to the port, which is ordered so
that every phase ends in a runnable, benchmarkable game.

## 1. What the inventory found

### 1.1 The render stack, as built

- **LWJGL 3.4.1** is shaded into `projectzomboid.jar` with the `glfw`,
  `opengl`, `system`, `util`, `jemalloc` and `tinyfd` modules. **The `vulkan`
  module is not bundled.** Because `.` is on the game classpath, loose
  classes under the game directory shadow the jar, which is how the overrides
  already load; the Vulkan bindings can be installed the same way.
- The window is The Indie Stone's LWJGL 2 shim (`org.lwjglx.opengl.Display`,
  already overridden for Wayland) over GLFW 3.4. GLFW can create a
  `VkSurfaceKHR` directly, on X11 and Wayland, with `GLFW_NO_API`.
- **Two threads.** The game thread records draw commands; the render thread
  replays them. The command is `zombie.core.textures.TextureDraw` with a
  `Type` enum of **55 variants** (quad draw, blend/stencil/depth state, FBO
  bind, shader start/update, model/skybox/water/puddle/particle/terrain
  draws, frame begin/end, ImGui, profiling). Three ring states
  (`SpriteRenderer.NUM_RENDER_STATES = 3`, one populating, one ready, one
  rendering) hand frames across; the game thread blocks on the single ready
  slot (`plan-resource-use.md`, 39 % of the route window on Zink).
- **The escape hatch.** `TextureDraw.GenericDrawer` lets any game code queue
  an arbitrary `render()` that issues raw GL on the render thread. **61 sites
  in 26 files** use it (character texture composition, dead-body and world-item
  atlases, weather, fog, water, puddles, tile depth, world map, physics debug,
  vehicle editor, save thumbnails, `IsoCell` itself).
- **Direct GL surface:** 119 Java files call `org.lwjgl.opengl.*`; about
  1,800 call sites (GL11 1,091, GL20 417, GL3x/4x 203, GL13–15 86, ARB/EXT
  34). Fixed-function residue is small: 5 `glBegin`, 31 matrix-stack calls
  (`glMatrixMode`/`glPushMatrix`/`glOrtho`), 2 client-state arrays. Most
  geometry already goes through VBOs (`VBORenderer`,
  `GLVertexBufferObject`, `VertexBufferObject`, `InstancedBuffer`) with
  explicit vertex attributes.
- **Readbacks and uploads** are concentrated: `Texture`/`TextureID` (upload,
  mipmaps, `glGetTexImage`), `TextureCombiner` (character textures),
  `TextureFBO`/`TextureFBODepth`/`MultiTextureFBO2` (per-player offscreen
  world, 12800×5400 at the driving zoom), `Core` (screenshots), `IsoPuddles`,
  `WorldMapVisited`, `ImagePyramid`, `SavefileThumbnail`, `VideoTexture`
  (Bink frames). 84 `invokeOnRenderContext` calls in 37 files hop work from
  loader threads onto the GL context.
- **A partial abstraction already exists.** `zombie.core.rendering`
  (`RenderTarget`, `RenderTexture`, `RenderList`, `ShaderBuffer`,
  `ShaderParameter`, `ShaderPropertyBlock`, `InstancedBuffer`) and
  `zombie.core.opengl.GLState`/`IOpenGLState` wrap FBOs, model draw lists
  and redundant-state elimination. They are still GL inside, but they are
  the natural seams.
- **Shaders:** 196 files in `media/shaders`, loaded only by
  `zombie.core.opengl.ShaderProgram` (and a validator in the map generator).
  GLSL versions: 111 at `#version 330`, 7 at `430` (instanced effects,
  `worldshader.vert`, `default.frag` with an SSBO), 1 each at 140/150/400,
  and **65 at `#version 110/120`** (puddles, water, vehicles, tree, wall and
  floor tiles, skybox, SDF text, blur, outlines). Ten of those use removed
  built-ins (`gl_ModelViewProjectionMatrix`, `gl_Vertex`, `ftransform`,
  `gl_TexCoord[]`). Uniforms are set by name at runtime
  (`ShaderUpdate` commands, `ShaderUniformSetter`, `ShaderParameter`).
- **Third-party GL consumers:** ImGui via `imgui.gl3.ImGuiImplGl3` (bundled
  imgui-java has no Vulkan backend), Bink video through `VideoTexture`, the
  Steam overlay (hooks both GL and Vulkan swap), MangoHud (has a Vulkan layer),
  gpu-screen-recorder (capture is display-side, unaffected).
- Lua mods never see GL. They draw through the Java UI API (`UIElement`,
  `SpriteRenderer`), so mod compatibility survives as long as the
  `TextureDraw` stream semantics survive.

### 1.2 What the measurements say the backend costs today

From `docs/archive/2026-09-24/results.md` (2026-09-19, 5120×2160, max zoom, uncapped):

| run | renderer | fps | GPU busy | game thread | render thread |
|---|---|---|---|---|---|
| `waits-uncap-1` | Zink / Wayland | 378 | 60 % | 60 % (39 % blocked on the ready slot) | 30 % (64 % of native samples inside `glfwSwapBuffers`) |
| `waits-uncap-gl-1` | NVIDIA GL | 629 | 93 % | 85 % | 59 % |
| `base60-gl-1` | NVIDIA GL, 60 km/h | 570 | **98 %** | 69 % | 43 % |

- On NVIDIA GL the GPU is the limit. The render thread has ~40 % headroom.
- On Zink (which is already "GL on Vulkan") the limit is the swap: ~1.8 ms of
  the frame is Mesa's presentation path, not the game.
- The CPU tail (`plan-driving-frame-time.md`) is the translucent/tree pass on
  the **game** thread: list building and per-object setup, before any GL
  call is made.

## 2. What Vulkan can and cannot buy here

Can:

- **Remove the driver's CPU share of the render thread.** GL state
  validation, redundant-bind elimination in the driver, and the implicit sync
  behind `glMapBuffer`/`glBufferSubData` go away. On NVIDIA GL that is a share
  of the 43–59 % render-thread time; measured in Phase 0.
- **Own presentation.** A mailbox or FIFO-relaxed swapchain with explicit
  acquire/present semaphores replaces `glfwSwapBuffers`, and removes the Zink
  swap stall entirely. Wayland presentation is native (no XWayland path).
- **Record in parallel.** The per-player offscreen world (`MultiTextureFBO2`)
  and the chunk FBO passes (`FBORenderChunk*`) can be recorded into secondary
  command buffers on worker threads; the game thread's translucent list
  could be turned into command recording instead of a serialized replay.
- **Batch by design.** Descriptor indexing (bindless textures) lets the
  translucent pass be one draw per chunk level regardless of texture changes;
  the current path flushes the tree batch on every non-tree object.
- **Frame pacing.** Timeline semaphores give exact GPU-done timestamps per
  frame, so the in-game sampler and the harness can separate CPU and GPU time
  without `glFinish`.

Cannot:

- Make the 69-Mpixel offscreen fill cheaper. Fragment cost is identical.
- Fix the game-thread tail. That work is CPU-side game logic
  (`plan-driving-frame-time.md` §3.1–3.2) and stays the higher-value item.
- Be maintained for free. Every game update changes classes; the override
  guard disables everything on a revision mismatch until the tree is
  regenerated and re-applied (`scripts/regen-overrides.sh`). A renderer port
  multiplies the override surface from 12 classes to roughly 120.

Most of the batching gain is also reachable under GL (array textures,
`GL_ARB_bindless_texture`, persistent mapped rings, which
`GLVertexBufferObject` already uses). The port is only worth it if Phase 0
shows the driver and presentation cost is a large enough share of the frame.

## 3. Target and go/no-go gate

Machine: Ryzen 7 9800X3D, RTX 4090, 5120×2160, 240 Hz, NVIDIA driver 615,
Mesa Zink available as an A/B. Targets are for the driving route at max zoom,
measured with the in-game sampler plus MangoHud (`harness/run.sh --record`,
`--no-dashboard`, `--flag zoom=max`), compared with `harness/compare.py`.

| metric | gate to start (Phase 0 result) | target at completion |
|---|---|---|
| render-thread time inside the GL driver + swap (native JFR samples, route window) | **≥ 20 % of the frame** on NVIDIA GL, or ≥ 30 % on Zink | 0 (there is no driver thread) |
| frame p99 at 60 km/h, uncapped | — | better than the NVIDIA GL baseline by at least the driver share measured above |
| frames below the 240 fps cap (capped run) | — | ≤ NVIDIA GL baseline |
| GPU busy when not at the cap | — | ≥ 95 % |
| game thread blocked in the ready-slot wait | 39 % (Zink) / measured on NVIDIA GL | ≤ 10 % |
| pixel parity with GL, same save, same frame (Phase 1 screenshot diff) | — | no visible difference; mean abs error ≤ 1/255 outside UI text |
| load time to main menu / to world | — | within noise of GL (`harness/loadtime.py`) |

If Phase 0 comes in under the gate, the recommendation is to **stop after
Phase 1** (the backend abstraction) and spend the effort on the GL-level
batching and the game-thread tail instead. Phase 1 is still worth having: it
is where the batching work would live either way.

## 4. Architecture

### 4.1 Where the seam goes

The `TextureDraw` replay is the seam. The game thread side (`SpriteRenderer`,
`SpriteRendererState`, the ring buffer, every `IsoObject.render`, all Lua UI)
stays untouched. On the render thread, each `TextureDraw.Type` case currently
calls GL directly; it will call a `pzopt.render.Backend` interface instead:

- `Backend` (new, `src/pzopt/pzopt/render/`): device lifetime, frame
  begin/end, swapchain, render-target creation and binding, texture
  create/upload/readback/mipmap, buffer create/map, pipeline lookup by
  (shader, blend, depth, stencil, cull, vertex format), draw quads from a
  vertex ring, draw indexed from a VBO, push uniforms by name, debug label.
- `GlBackend`: the current GL code moved behind the interface, byte-for-byte
  behaviour, so Phase 1 can be verified by screenshot parity and by the
  frame-time harness against stock.
- `VkBackend`: the port.

Everything that today bypasses the typed stream must be routed through it:

- The 61 `GenericDrawer` sites are ported one by one to typed commands or to
  `Backend` calls inside their `render()` body. Debug-only sites (physics
  debug, vehicle editor `UI3DScene` with 100 GL calls, gizmos, building-room
  overlay, tileset image creator) are stubbed under Vulkan and remain GL-only
  until last.
- `zombie.core.rendering.RenderTarget`/`RenderTexture`,
  `GLFramebufferObject{30,ARB,EXT}`, `TextureFBO*`, `MultiTextureFBO2` become
  handles to backend render targets.
- `GLVertexBufferObject`, `VertexBufferObject`, `InstancedBuffer`,
  `ShaderBuffer` become backend buffers (the persistent-mapped ring the
  override already added maps directly to host-visible, host-coherent Vulkan
  memory).
- `GLState`/`IOpenGLState` values become fields of a pipeline key; the
  redundant-state cache turns into a pipeline cache lookup.
- `invokeOnRenderContext` becomes "run on the render thread"; uploads from
  loader threads go to a staging ring with a transfer queue submission and a
  timeline semaphore the render thread waits on before first use.

### 4.2 Shaders

- Offline compile: GLSL → SPIR-V with `glslang` (or `shaderc`) at build time
  into `media/shaders/spv/`; the game's `ShaderProgram` loads the `.spv` when
  the backend is Vulkan. Reflection (SPIRV-Cross or `spirv-reflect`) generates
  a uniform-name → (set, binding, offset) table so the existing by-name
  `ShaderUpdate` path keeps working.
- Uniform strategy: per-pipeline UBO in a host-visible ring, written once per
  `ShaderUpdate` batch; `ModelViewProjection`, `chunkDepth`, `zDepth` and
  other per-draw floats go in push constants (≤ 128 bytes). Textures use one
  large descriptor set with descriptor indexing; each draw carries texture
  indices in the vertex stream or push constants.
- The 65 legacy shaders (`#version 110/120`) must be rewritten to `450` with
  explicit `layout(location)` inputs and `layout(set, binding)` uniforms
  before they compile to SPIR-V. The 10 that use removed built-ins need the
  matrices passed as uniforms. This rewrite is renderer-neutral and can be
  landed and verified under GL first (GL 4.6 accepts `450`).
- Vulkan clip space (Y down, depth 0..1) is handled in the projection matrix
  at the `pushIsoView`/`glDoStartFrame*` commands, not per shader.
  `glDoStartFrameFlipY` maps to a negative-height viewport.
- `default.frag` reads an SSBO; `basicEffect_instanced*` use instancing. Both
  are ordinary in Vulkan.

### 4.3 Frame structure and synchronization

- Three frames in flight, matching `NUM_RENDER_STATES`. Each frame owns a
  command pool, a vertex/index ring segment, a uniform ring segment, a
  staging segment, and a fence. The game thread's ready-slot wait is replaced
  by waiting on the fence of the frame two behind, which is what it is today
  minus the driver's implicit serialization.
- Passes, in the order the command stream already implies: chunk FBOs
  (`FBORenderChunkStart`/`End`, one render pass each, recordable on workers),
  per-player world offscreen (`MultiTextureFBO2`, one render pass with depth
  and stencil), UI (either directly to the swapchain image or, with
  `uiRenderOffscreen`, to its own target then composited), ImGui, present.
- Readbacks (`glGetTexImage`, screenshots, puddle and map-visited data) go
  through a staging buffer with a fence; callers that expect a synchronous
  result get one, but the plan lists each and converts the hot ones
  (`IsoPuddles`, `WorldMapVisited`) to a one-frame-late read.
- Swapchain: `MAILBOX` when uncapped (lowest latency, no tearing), `FIFO`
  when the in-game cap or VSync is on. Recreate on resize and on the Wayland
  scale change the `Display` override already tracks.
- Validation layers and `VK_EXT_debug_utils` labels are on for harness
  `verify` runs and off for benchmark runs.

### 4.4 Presentation and window

- `Display` (already overridden) gains a Vulkan path: `GLFW_CLIENT_API =
  GLFW_NO_API`, `glfwCreateWindowSurface`, no `glfwMakeContextCurrent`, no
  `glfwSwapBuffers`. The HiDPI scale handling stays.
- Wayland native works without the XWayland detour; MangoHud attaches as a
  Vulkan implicit layer (`MANGOHUD=1` is enough, the GL preload in the
  harness is skipped for `--renderer vulkan`).
- The Steam overlay hooks `vkQueuePresentKHR`; nothing to do.

### 4.5 Third-party pieces

- ImGui: bundled imgui-java has only the GL3 backend. Write a small Vulkan
  renderer for `ImDrawData` inside `VkBackend` (one pipeline, one font
  texture, a vertex/index ring); ~300 lines.
- Bink (`VideoTexture`): CPU frames uploaded via the staging ring; trivial.
- Screenshots (`Core`): swapchain image → staging → PNG on a worker.

### 4.6 Packaging and the override mechanism

- New code lives in `src/pzopt/pzopt/render/` (committed) plus the
  `lwjgl-vulkan` 3.4.1 classes installed loose in the game directory
  (`scripts/pzopt.sh install`), exactly like the override classes; no
  launcher JSON change. `pzopt.Config` gains `renderer=gl|vulkan`
  (default `gl`), and `harness/run.sh --renderer vulkan` sets it. A Vulkan
  init failure logs and falls back to GL for that launch.
- Overridden game classes (`src/overrides/`, local only) are documented in
  prose in `docs/override-edits.md` per class, as now. Expect ~120 entries by
  the end; `scripts/build.sh`'s structural check (every non-private member
  keeps its descriptor) is what keeps the rest of the game linking.
- Shader rewrites are shipped as new files under `media/shaders/` in the game
  directory (never edited in place), with the loader preferring the rewritten
  name when the override is active.

## 5. Phases

Each phase ends with a game that launches, plays the bench save, and is
measured by the harness. Effort is one person, full time, wide error bars.

### Phase 0: measure the gate (2–3 days)

1. Add a `--jfr-setting` native-sample profile of the render thread over the
   route window and extend `harness/waits.py` to attribute samples to
   `libnvidia-glcore`/`libGLX_nvidia` (or Mesa/Zink) versus game code, and to
   `glfwSwapBuffers` separately.
2. Run the 60 km/h A/B set (`base60-gl-1` style) on NVIDIA GL and on Zink,
   three runs each, capped and uncapped.
3. Verify the bundle: `org.lwjgl.glfw.GLFWVulkan` present in the jar (else the
   matching `lwjgl-glfw` classes are installed loose too); `vulkaninfo` shows
   the 4090 with `VK_KHR_swapchain`, descriptor indexing, timeline semaphores;
   `glslang`/`spirv-cross` installed (`paru`).
4. Decide against section 3. Record the decision and the numbers in
   `docs/archive/2026-09-24/results.md`.

Exit: the gate table in section 3 is filled in.

### Phase 1: backend seam with a GL implementation (3–4 weeks)

1. Introduce `Backend`/`GlBackend`; move the `TextureDraw` replay switch and
   `GLStateRenderThread` behind it. Override `SpriteRenderer` (render-thread
   half only), `TextureDraw`, `GLStateRenderThread`, `PZGLUtil`.
2. Route render targets (`RenderTarget`, `TextureFBO*`, `MultiTextureFBO2`,
   `GLFramebufferObject*`), buffers (`GLVertexBufferObject`,
   `VertexBufferObject`, `InstancedBuffer`, `ShaderBuffer`, `VBORenderer`),
   textures (`Texture`, `TextureID`, `TextureCombiner`, `VideoTexture`) and
   shaders (`ShaderProgram`, `ShaderUnit`, both `Shader` classes,
   `ShaderUniformSetter`) through it.
3. Convert the 61 `GenericDrawer` sites into typed commands or backend calls;
   tag the debug-only ones.
4. Land the 65 legacy shader rewrites to `450` and verify under GL.
5. Add screenshot parity to the harness: a `verify` mode that teleports to
   three fixed camera positions on the bench save, captures the swapchain,
   and diffs against a stored GL reference (`harness/parity.py` already
   exists for the streamer; extend it with an image diff).

Exit: stock-vs-Phase-1 frame-time within noise on NVIDIA GL and Zink; parity
diff clean; `docs/override-edits.md` updated. This is the stopping point if
Phase 0 said no.

### Phase 2: Vulkan core, sprite path (5–6 weeks)

1. Device/queues/swapchain/frames-in-flight in `VkBackend`; `Display` Vulkan
   path; ImGui renderer; MangoHud and the harness's `--renderer vulkan`.
2. Texture system: 2D textures with mipmaps, staging ring, descriptor
   indexing set, readback path; `TextureCombiner` as a render pass.
3. Vertex ring and the quad pipeline family (blend modes, stencil, depth,
   `glIgnoreStyles`, colour mask) for `glDraw`/`glBuffer`/`DrawQueued`.
4. Render targets: chunk FBOs, per-player offscreen world, UI offscreen,
   `TileDepthTextures`.
5. SPIR-V build step and reflection table; the sprite, tile, wall, floor,
   tree, SDF and blit shaders.

Exit: main menu, world load and the bench save render with sprites, tiles,
lighting and UI; models, weather, water and puddles are stubbed. First
Vulkan frame-time run recorded.

### Phase 3: 3D and effects (4–6 weeks)

1. Skinned and static models: `Model`, `ModelMesh`, `ModelSlotRenderData`,
   `RenderList` (array textures → texture arrays or descriptor indexing),
   `AnimatedModel`, `ItemModelRenderer`, `IsoObjectModelDrawer`,
   `WorldItemAtlas`, `DeadBodyAtlas`, vehicle shaders, `HeightTerrain`.
2. Weather, fog, sky, water, puddles, particles, `VisibilityPolygon2`, world
   map (`WorldMapRenderer`, `ImagePyramid`, `WorldMapVisited`).
3. Fonts (`AngelCodeFont`, SDF), save thumbnails, screenshots, Bink.

Exit: parity diff clean at all three camera positions plus a vehicle and a
rain scene; harness A/B set run on Vulkan vs NVIDIA GL.

### Phase 4: the reasons to have done it (3–4 weeks)

1. Parallel recording of chunk FBO passes and the per-player world pass on
   worker threads (secondary command buffers).
2. One-draw-per-level translucent pass using texture indices in the vertex
   stream; remove the tree-batch flush.
3. Replace the ready-slot wait with the frame fence; measure the game-thread
   blocked share.
4. Timeline-semaphore GPU timestamps into the in-game sampler and
   `harness/analyze.py`.

Exit: the completion column of section 3.

### Phase 5: tail (2–3 weeks)

Debug tools (vehicle editor, physics debug, gizmos), Zink retirement in the
harness docs, validation-layer clean run, a `docs/archive/2026-09-24/results.md` write-up, and
the fallback path tested (Vulkan unavailable → GL).

Total: roughly 4–5 months. Phases 2 and 3 are the ones most likely to
overrun, because each `GenericDrawer` site hides its own GL state assumptions.

## 6. Risks

- **Game updates.** Build 42 unstable updates rewrite render classes; every
  update means `regen-overrides.sh`, re-applying ~120 edits, and re-verifying
  parity. This is the dominant ongoing cost and the main reason to keep the
  seam (Phase 1) small and the backend (`pzopt.render`) free of game classes.
- **Hidden GL state.** Sites that rely on state left behind by an earlier
  command (blend mode, bound texture, matrix stack) work by accident today.
  The pipeline-key approach makes every draw explicit; the parity diff is the
  net that catches what was missed.
- **Synchronous readbacks** in game logic (`IsoPuddles`, `WorldMapVisited`,
  thumbnails). Converting to one-frame-late reads changes behaviour slightly;
  each is listed and A/B'd.
- **Loader threads.** 37 files hop onto the GL context; some may assume the
  result is visible on return. Staging-plus-timeline-semaphore preserves that
  if the render thread waits before first use, at a small stall; measured.
- **Wayland + Vulkan + NVIDIA** presentation quirks (mailbox availability,
  HDR surface formats used by the recording). Phase 2 step 1 tests X11 and
  Wayland both, capped and uncapped, before anything else is built on it.
- **ImGui** stays debug-only until its Vulkan renderer exists; PZDashboard
  does not depend on it.
- **The payoff may be small.** Section 2 and the Phase 0 gate exist so this
  is known after days, not months.

## 7. Not planned

- GL/Vulkan interop (`VK_KHR_external_memory` + `GL_EXT_memory_object`) to
  migrate pass by pass. It doubles the synchronization surface and leaves two
  drivers loaded; the phase order above gives a runnable game at every step
  without it.
- A new scene representation or a change to how the game thread builds the
  translucent list; that is `plan-driving-frame-time.md` §3.1–3.2 and stays
  independent of the backend.
- Supporting GPUs without descriptor indexing or timeline semaphores. Both
  are Vulkan 1.2 core and present on every GPU this project targets.
- Windows or macOS (MoltenVK) paths. The native Linux build is the only
  target; nothing in the design prevents them later.

## 8. Order of work

1. Phase 0 (this week): profile, bundle check, decision written to
   `docs/archive/2026-09-24/results.md`.
2. Phase 1 regardless of the decision, because the seam is also where the
   GL batching work lands.
3. Phases 2–5 only on a "go", each ending in a harness run that is compared
   against the NVIDIA GL baseline of the same day.
