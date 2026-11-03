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
