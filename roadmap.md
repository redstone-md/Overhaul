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

1. Harden the now-working opt-in Fabric 26.3 native backend with longer sessions,
   resize/reload/dimension and full render-domain coverage. Profile the now-batched
   command path and bounded allocation caches, verify device-preferred static
   allocation under longer sessions, and measure against a controlled reference.
2. Connect the existing owned worker meshes/visibility path to continuous game
   section updates and replace remaining compatibility queries with frozen inputs.
   Ordinary model/fluid compilation now streams on owned workers; expand entities,
   particles, weather, lighting and full scene/occlusion integration.
   The verified 26.3 compile/upload seam and snapshot constraints are documented
   in `docs/TERRAIN_INTEGRATION.md`; vanilla section compilation is already async.
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

## Active RenderPearl backend increment

Implement the actual 26.3 `GpuDeviceBackend`/command/resource boundary, consumed
through the game's `FrontendGpuDevice`. Minecraft-specific types stay in the
adapter; native image/mapping/pipeline mechanisms stay in `vulkan-backend`.
The backend must create its device before the SDL window, then attach the surface.
Recorded commands pin native resources until their real submission completes.
First verify public frontend buffer/texture transfer and shader draws, then run
an isolated opt-in game backend. No vanilla Vulkan/OpenGL implementation may act
as a hidden fallback or count as Overhaul's replacement. Normal targets retain
their current behavior while the new backend is verified. All earlier full-scene,
shader-pack, world/tick, version and performance gates remain required.

## Actual game backend checkpoint: 2026-11-03

- The opt-in Fabric 26.3 build now installs Overhaul before SDL window creation.
  It uses the shared shader/validation frontend and owns native execution itself;
  Mojang's Vulkan/OpenGL backends are absent from the selected backend list.
- Owned images/views/samplers, direct mapping leases, generic graphics state,
  descriptors, push constants, depth/MRT and draw variants pass GPU checks.
  Nine adapter-native and eleven portable native tests pass, with 48 unit tests.
- A 200-tick onboarding GUI run and a real disposable-world run passed. Inspected
  GPU presentation-source images show textured terrain, clouds, player hand/HUD,
  slab, glass, flower, stairs and cutout leaves. The world saves/disconnects and
  the backend closes normally; source snapshot leases return to zero.
- The camera is placed by the integrated-server owner for repeatable fixture
  framing. Source-image exports apply the same Y inversion as native presentation.
- Ordinary loader outputs exclude the experimental backend. Adapter builds must
  run serially with clients; Gradle variant cleanup can remove live class outputs.
- Game submissions are currently synchronous and buffers host-visible. Full
  render-domain/session/mod parity, all version backends, shader-pack semantics,
  world/tick integration and measured performance remain open acceptance gates.

## Active bounded asynchronous game submission increment

Replace the game bridge's per-pass CPU waits with an ordered operation batch on
the existing fenced frame ring. Seal pass capture immediately, commit image
layouts only after successful queue submission, and retire descriptors, attachment
views and resource pins after the real fence. Staged buffer writes preserve draw
ordering; CPU mappings wait only on overlapping GPU buffer ranges. Triple-buffered
transient arenas remain unavailable for reuse until their submission completes.
Surface blits use frame-owned acquire semaphores and return real completion tokens;
explicit game fences and readback callbacks must cover all earlier queue work.
Test timeout/rollback/reuse/hazard cases, then repeat the isolated GUI/world runs
with submission/wait counters. Full scene/version/shader/world/tick and controlled
performance gates remain intact; batching counters alone are not an FPS claim.

## Asynchronous backend checkpoint: 2026-11-03

- Actual game passes, buffer uploads, image transfers and timestamps now use
  ordered batches on three fenced slots. Three transient arenas preserve data
  until completion; CPU mappings check overlapping reads/writes at atom granularity.
- Pass capture seals immediately. GPU resources, descriptors and private attachment
  views retire at their real completion point, with transactional failure cleanup.
- Logical fence markers avoid early submits. GPU readback callbacks run after
  retirement and may safely reenter the queue; query generations reject stale data.
- SDL split presentation is asynchronous with three acquire semaphores. Private
  attachment views no longer wait for later image-only blits of the same image.
- The actual world run passed 2,900 passes, 26,899 draws and 359 presented frames
  using 403 render submissions, 27 render-fence wait calls, one presentation-fence
  wait call and one synchronous debug readback. Call counts are not blocked time
  or FPS comparisons. The 200-tick GUI run and orderly world teardown also pass.
- Seventeen portable native cases, seventeen adapter native cases, two interval
  unit cases and the prior 48 engine unit cases pass. Remaining global acceptance
  gates are unchanged; host-visible static allocations and per-pass native-object
  creation still need profiling/optimization.

## Active native allocation reuse increment

Reuse descriptor pools and attachment objects only after their last owning fence,
with bounded device-owned caches and explicit lease teardown. Cache keys must
preserve load/clear semantics, attachment order, formats, layers and dimensions;
closed textures/pipelines must not leave unbounded retained resources. Move buffers
without CPU-map usage to device-preferred allocations and stage their initial
contents through the existing ordered queue. Preserve mapped-buffer behavior and
copy/update order. Verify native readback, overlapping in-flight leases, rollback,
reuse and destruction before repeating the isolated game world. Record allocation
counters rather than inferring performance gains from fewer object creations.

## Native allocation reuse checkpoint: 2026-11-03

- Unmappable game buffers now use device-preferred allocations with owned queued
  initial uploads. CPU-mappable and transient buffers preserve host access.
- Descriptor pool idle retention is bounded by 16 pools and 8,192 descriptors.
  Attachment bundles retain at most 64 idle entries; close invalidation releases
  cached source pins and marks accepted leases for discard after their fence.
- Twenty-seven backend-native checks pass, including ten new upload/reuse/lifetime
  cases. Build, source-size and module-boundary checks pass.
- Actual GUI and world runs pass with normal shutdown. The world records 2,947
  passes, 38 native framebuffer/render-pass creations and 2,909 bundle reuses;
  descriptor pools record 490 creations and 3,664 reuses. All tracked buffer bytes
  and active pool leases return to zero. These counters do not establish an FPS gain.
- The next terrain integration must replace ordinary continuous section output,
  preserving the existing upload/draw seam and complete scene semantics. See
  `docs/TERRAIN_INTEGRATION.md`. Full shader-pack, world/tick, loader/version,
  session/mod parity and controlled performance requirements remain open.

## Active continuous terrain increment

Replace the opt-in 26.3 section compiler at its normal compile boundary, emitting
all model layers and fluids once into pooled worker-owned geometry. Preserve AO,
tint/emission, visibility, block entities, leaf policy and translucent sorting;
feed normal MeshData into the existing UberGpuBuffer upload and draw path. Keep
portable packing/storage contracts in core-common and Minecraft translation in
the adapter, with fewer than 500 lines per file. Integrate the bounded lock-free
worker scheduler where the dispatch/lifecycle contract permits without blocking
the owner thread. Verify byte parity against the pinned game builder, concurrent
lease ownership, failures, live edits/boundaries and orderly unload. Do not run a
second tessellator or count offscreen diagnostic output as ordinary terrain.
Minecraft's worker-region light/tint queries remain an explicit compatibility
boundary until frozen inputs replace them. Full scope and performance gates remain.

## Continuous terrain checkpoint: 2026-11-03

- Normal 26.3 terrain compilation now emits models/fluids once through bounded
  streaming pages into the existing native upload/draw path, preserving metadata.
  Up to eight owned workers also handle urgent builds. Admission uses shared MPMC
  storage; the game's priority queue remains authoritative for section order.
- Three new portable page tests, three exact game-builder encoding tests and four
  scheduling/lifetime concurrency cases pass. Files remain below 500 lines.
- Four actual world frames pass across boundary edits, water/lava, chest metadata,
  resource reload and distinct post-reload edits. Installed mesh starts must follow
  final client packet barriers. Two generations finish 98/66 compiles on owned
  workers, with no failures, owner-thread compiles or outstanding leases.
- The scenario verifies idle world disconnect and final compiler closure before
  graphics shutdown. The dispatcher is intentionally client-owned while in menus.
- Frozen inputs, independent lighting, broader scenes/worlds, continuous owned
  occlusion/indirect integration, all other version/loader renderers, shader-pack
  semantics, server systems and controlled benchmarks remain acceptance gates.

## Active ordinary terrain occlusion increment

Connect conservative software depth culling to ordinary terrain draw groups.
Certify occluders from actual emitted SOLID geometry, not block collision proxies;
merge adjacent certified planar faces and include actual emitted candidate bounds.
Keep metadata mesh-owned through compilation/replacement, and snapshot meshes
coherently while extracting draw groups without mutating entity/visibility lists.
Capture the final bob/hurt/portal-adjusted projection, compose view rotation, use
camera-relative doubles and account for reversed zero-to-one depth. Fully cover
software cells and store farthest occluder depth; uncertain inputs fail open.
Keep source/shader eligibility and frame budgets explicit. Verify hidden-section
rejection and restoration after opening geometry/moving the camera in the actual
world. Record accepted native indirect/instancing counters to prove the already
selected terrain path; do not substitute unused CPU multidraw changes or infer FPS
gains. Preserve the full version/shader/world/server/performance acceptance scope.
