# Overhaul implementation roadmap

The requested product is a Vulkan Minecraft engine overhaul spanning 1.12.2
through 26.3, across Fabric, Forge and NeoForge where each loader exists.
This file tracks the full scope; a standalone renderer is evidence for the
backend, not evidence that Minecraft's renderer has been replaced.

## Architecture

- `core-common`: Java 8 bytecode; immutable world snapshots, bounded workers,
  owned direct-memory leases, lighting, meshing, spatial queries and tick budgets.
- `vulkan-backend`: Java 17 bytecode; LWJGL Vulkan, VMA, resource lifetimes,
  command submission, indexed indirect rendering, presentation and culling.
- `shader-pipeline`: Java 17 bytecode; shader-pack reading, include resolution,
  explicit compatibility diagnostics, shaderc SPIR-V compilation and pass graph.
- `platform-adapters`: Java 8 baseline contracts; isolated loader/version builds,
  world-thread snapshot capture and lifecycle integration. Minecraft classes must
  never leak into engine modules. Legacy LWJGL2 and modern LWJGL3 are separate
  runtime targets, not interchangeable dependencies.
- `integration-tests`: connect real snapshots, worker output, native allocation
  and GPU draw/readback. Tests must distinguish fixtures from gameplay.

Mutable state belongs to an object with one documented owner. Off-thread tasks
consume snapshots; world mutation and gameplay authority remain on the game
thread. Resources close in reverse dependency order. Shared logic lives in the
engine; adapters only translate platform objects and lifecycle events.

Every source file must stay below 500 lines. Changes are committed as separate
Conventional Commits with no attribution trailers.

## Execution and acceptance gates

1. Reproducible Gradle wrapper, dependency pins, Java toolchains, module boundaries,
   CI, and an explicit loader/version matrix. Build real loader artifacts before
   labelling a target supported.
2. Tested chunk snapshot -> async lighting/meshing -> owned off-heap geometry.
   Exercise boundaries, cancellation, exhaustion, stale results and shutdown.
3. Vulkan device/VMA/upload/command submission and indirect indexed drawing.
   Validate native resource ownership and read back a rendered result.
4. GLFW presentation, resize/recreation, descriptor layouts, pipeline cache,
   CPU culling and render graph with depth, cascaded shadows and postprocessing.
5. Shader packs: safe source loading/includes, GLSL -> SPIR-V, uniform and
   framebuffer translation, Iris/OptiFine directives and validated real packs.
6. Loader integration: legacy CoreMod/MCP; intermediate Blaze3D/Mixin; modern
   pipeline accessors. Replace rendering only when all necessary render domains
   have a working Vulkan path. Test client and dedicated-server lifecycle.
7. Full render domains: chunks, entities, block entities, particles and weather;
   instancing, indirect batches, streaming and CPU/GPU occlusion.
8. Chunk generation/loading/deserialization, packet/state slabs, spatial entity
   tracking, path queries and tick loops, with deterministic correctness checks.
9. Package every compatible target; run gameplay parity and mod coexistence
   checks. Measure performance against vanilla with fixed worlds/settings.

## Initial state

The directory was empty at inspection. No supported Minecraft target, shader-pack
compatibility or performance gain has yet been demonstrated. The first workstreams
are build/toolchains, Vulkan resource/draw execution and core world processing.

## Validation rules

Unit tests establish algorithm contracts. Native smoke tests establish only the
specific GPU path exercised. Loader compilation establishes linkage, not gameplay.
Claims of full replacement require visible in-game parity for every render domain.
Performance claims require measurements, not implementation intent.

## Verified checkpoint: 2026-11-03

- All five specialist workstreams delivered code, reviewed and committed by domain.
- Engine build, source-size and module-boundary gates pass. There are 42 passing
  engine unit tests and four passing native integration tests.
- Worker-built geometry reaches Vulkan; source slab reuse, indirect draws,
  instancing, frame-ring retirement and sparse CPU visibility updates are tested.
- Semaphore-driven presentation passes 62 geometry frames across resize. GPU blits
  do not require CPU image readback or a queue-idle wait on each presentation.
- Unchanged pinned Iris example basic/textured programs execute with real texture,
  UBO, blending and coordinate-conversion pixel checks. This is a tested subset.
- All 11 pinned loader targets build and pass 22 loader tests from one recorded
  source snapshot. Source/artifact receipts are retained under `docs/evidence`.
- Fabric 26.3 starts, initializes resources, runs the actual client hook for 200
  ticks and stops normally. The game's log still reports its vanilla OpenGL backend.
- Khronos validation layers and desktop screenshot automation were unavailable.
  No in-game Vulkan replacement, live world capture or visual parity is claimed.

## Next end-to-end gates

1. Promote the now-verified live-world model/texture upload tracer into the actual
   game window/backend. The SDL surface provider and isolated LWJGL 3.4.3 runtime
   pass native tests; the 26.3 game still needs an Overhaul implementation of its
   RenderPearl device/command/resource boundary before window creation.
2. Implement model geometry, biome/light data and translucent rendering, then all
   entity, block-entity, particle, weather and GUI/text paths before cancelling the
   vanilla renderer. Preserve a runnable reference instance for visual comparisons.
3. Execute real depth/shadow/lighting/post passes and shader-pack render-state,
   ping-pong, custom-uniform, texture and unsupported-stage semantics.
4. Integrate vanilla generation, NBT/region/packet loading, cross-chunk lighting,
   server tracking and path queries with dependency/ownership correctness.
5. Expand/version-test the remaining releases, exercise real legacy JVM/game
   runtimes, perform mod coexistence checks and measure controlled performance.

## Live-world/model checkpoint: 2026-11-03

- The actual integrated server and client now run a disposable world, verify five
  server-placed block states through client packets and capture the live section.
- The adapter extracts 283 baked quads, including 19 non-cube, nine cutout and five
  translucent quads. Its real block atlas and lightmap reach a bounded worker.
- Overhaul draws those assets through Vulkan, verifies repeatable pixels after
  source slab overwrite and saves a 512x512 image. World unload returns all leases
  and the game saves/disconnects/stops normally.
- Baked-model unit/native checks cover UV/light/tint packing, alpha holes, sorting,
  camera precision and partial index writes. The engine now has 48 unit tests and
  eight native integration tests, all passing.
- All 11 normal loader targets were refreshed and their 22 tests/receipts pass.
  World/model smoke sources and output directories are isolated from normal jars.
- This proves an actual game-asset-to-Overhaul-Vulkan path. It does not prove that
  the game's onscreen renderer, entity domains or full shader packs are replaced.
