# overhaul

A modular Vulkan rendering and world-processing overhaul for Minecraft.

Development is underway. See [roadmap.md](roadmap.md) for the complete requested
scope and acceptance gates. This repository must not be described as a working
replacement for Minecraft's renderer until loader integration and gameplay
validation pass.

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

The cube mesher does not implement Minecraft's model bakery, texture atlas,
transparent blocks or biome shading. Emissive lighting is not the vanilla
skylight/lighting engine. The deferred graph still needs actual scene lighting,
shadow and post-processing passes. Shader-pack support remains a defined subset.
Direct slabs are reusable within their fixed budget; the JVM reclaims the pool's
backing direct memory after collection.

Loader builds are isolated under `platform-adapters/minecraft`; their diagnostic
lifecycle hooks leave vanilla rendering active. A packaged diagnostic adapter is
not a completed Vulkan renderer replacement. See the roadmap for remaining gates.
All 11 pinned loader builds pass; see [validation evidence](docs/VALIDATION.md)
and the [implementation ledger](docs/IMPLEMENTATION_STATUS.md) for exact limits.
