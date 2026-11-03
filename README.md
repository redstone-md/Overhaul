# overhaul

A modular Vulkan rendering and world-processing overhaul for Minecraft.

Development is underway. An opt-in Fabric 26.3 build now runs the actual game
window through Overhaul's Vulkan backend, with GUI and disposable-world evidence.
See [roadmap.md](roadmap.md) for the remaining scene, shader-pack, version and
performance acceptance gates. Production support and full parity are not established.

The engine separates immutable world processing, explicit native resource
ownership, shader translation and loader-specific adapters. Legacy and modern
runtime dependencies are isolated rather than bundled into one incompatible jar.

## Build and verify

Use JDK 25 to run the pinned Gradle wrapper. Engine modules produce Java 8 or
Java 17 bytecode as appropriate; loader targets have their own runtime requirements.

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'
.\gradlew.bat build
.\gradlew.bat :integration-tests:nativeTest
.\gradlew.bat :vulkan-backend:sdlVulkanSmoke
.\gradlew.bat :vulkan-backend:vulkanSmoke --args="--present --output build/verification/triangle.ppm"
```

`build` runs CPU and shader-compiler tests. `nativeTest` requires a working Vulkan
1.1 device and fails if one is unavailable. It renders real worker-built geometry
and executes image dependencies through Vulkan. The PNG result is written to
`integration-tests/build/verification/chunk-vulkan.png`.

`vulkanSmoke` verifies device-local staging, indirect draws, asynchronous frame
reuse, instancing, resource retirement and optional hidden-window presentation.
`--validation` additionally requires installed Khronos validation layers and fails
explicitly when they are absent. These tests do not establish in-game parity or
performance improvements over Minecraft.

The Gradle tasks configure LWJGL's native stack to 1024 KiB and enable native
access for JDK 25. Standalone applications using the engine need the same settings
before loading LWJGL: `-Dorg.lwjgl.system.stackSize=1024` and
`--enable-native-access=ALL-UNNAMED`.

## Current engine

- Immutable palette-packed sections and pooled off-heap packet/geometry storage.
- Bounded workers for decoding, generation callbacks, emissive lighting and greedy
  opaque-cube meshing, with revision gates and cancellation cleanup.
- Conservative frustum/software-depth culling, spatial queries and tick budgets.
- VMA staging, camera descriptors, depth-tested indexed indirect rendering,
  instanced batches, fence-backed frame rings and swapchain recreation.
- Shader-pack source/include handling, explicit GLSL compatibility lowering,
  SPIR-V compilation, uniform layouts and versioned render-graph planning.
- Real Vulkan draws of pinned Iris example programs with texture, uniform,
  coordinate-conversion and blending checks.
- Native graph image allocation and transitions with explicit pass callbacks.
- Pooled baked-model vertices with real atlas/lightmap sampling, cutout discard
  and translucent sorting that uploads only changed translucent indices.

The cube mesher is separate from the new baked-model path. A Fabric 26.3
development smoke captures actual baked geometry, final tint/AO, atlas and
lightmap, then draws those assets through Vulkan on a worker. See the
[captured result](docs/evidence/world-model-2026-11-03.png). Its extraction still
uses Minecraft's tessellator. That model-only smoke leaves the game window on its
normal backend; the separate backend build below selects Overhaul itself.
Emissive lighting is not the vanilla
skylight/lighting engine. The deferred graph still needs actual scene lighting,
shadow and post-processing passes. Shader-pack support remains a defined subset.
Direct slabs are reusable within their fixed budget; the JVM reclaims the pool's
backing direct memory after collection.

Loader builds are isolated under `platform-adapters/minecraft`; normal artifacts
retain their diagnostic behavior. The experimental native backend is selected
explicitly and currently targets Fabric 26.3 only.
All 11 pinned loader builds pass; see [validation evidence](docs/VALIDATION.md)
and the [implementation ledger](docs/IMPLEMENTATION_STATUS.md) for exact limits.

To reproduce the isolated live-world model check:

```powershell
.\gradlew.bat :vulkan-backend:jar :shader-pipeline:jar
.\gradlew.bat -p platform-adapters/minecraft '-Ptarget=fabric-26.3' -PworldSmoke -PmodelSmoke verifyWorldSmoke --no-configuration-cache
```

It creates a uniquely named development world inside this workspace, verifies
server-to-client block fixtures, renders their captured assets, then saves and
disconnects normally. Development drivers use separate build directories and are
excluded from normal loader jars. The optional `-sdl34.jar` surface adapter is
packaged separately and requires a runtime with matching LWJGL 3.4.3 bindings.

To verify the actual game backend:

```powershell
.\gradlew.bat :vulkan-backend:assemble :shader-pipeline:jar
.\gradlew.bat -p platform-adapters/minecraft '-Ptarget=fabric-26.3' -PoverhaulBackend build backendNativeTest --no-configuration-cache
.\gradlew.bat -p platform-adapters/minecraft '-Ptarget=fabric-26.3' -PoverhaulBackend -PworldSmoke verifyWorldSmoke verifyBackendSmoke --no-configuration-cache
```

The [world frame](docs/evidence/backend-terrain-reloaded-2026-11-03.png) includes terrain,
clouds, the player hand, HUD, edited boundary blocks, fluid basins and a chest. Its GPU source
image is saved with the presentation Y flip applied. Overhaul owns the native
device, resources, pipelines, commands and SDL swapchain; Minecraft's shared
frontend supplies shader compilation and validation. No vanilla graphics backend
acts as fallback. The adapter now batches ordered commands over three fenced
slots, stages GPU buffer writes, and synchronizes overlapping mappings and slot
reuse. Transient arenas also rotate over three frames. Unmappable buffers use
device-preferred allocations with staged initial data; mapped buffers retain host
access. Bounded caches reuse retired descriptors and framebuffers. FPS/frame-time
improvements have not been measured. Run adapter builds and
clients serially because Gradle can clean a previous variant's class output.

The opt-in 26.3 build also replaces ordinary section compilation: owned workers
stream all model layers and fluids through bounded vertex pages into the game's
normal terrain upload/draw path. The live check verifies edits after received
client states and resource reload. Shared game tessellation/light/tint queries
remain a compatibility boundary; fully frozen inputs and broader parity remain
open. See [terrain integration](docs/TERRAIN_INTEGRATION.md).

Ordinary terrain draw groups now use mesh-owned certified faces for bounded
software occlusion. The live smoke checks hiding behind a wall and restoration
after opening geometry or moving the camera. Unknown shader/mod semantics disable
the filter. Accepted native counters also verify indirect instanced terrain
batches. See [occlusion integration](docs/OCCLUSION_INTEGRATION.md); controlled
performance comparisons and broader compatibility remain open.
