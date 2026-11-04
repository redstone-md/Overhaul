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

## Ordinary terrain occlusion checkpoint: 2026-11-03

- Immutable emitted bounds and merged certified SOLID faces travel with each
  compiled mesh. Normal draw extraction consumes coherent snapshots under the
  existing copy lock; entity and block-entity visibility lists remain intact.
- Final projection/view capture handles reversed depth and the effective eye.
  Full-cell coverage, original-corner far depth, candidate expansion and bounded
  float-error guards keep near-crossing/ill-conditioned geometry visible.
- Actual wall, opened hole, closed hole and moved-camera stages pass after fresh
  client-state rebuild barriers, with native GPU readbacks for every stage.
- Unsigned device-limit conversion is corrected. Accepted native terrain batches
  contain multiple indirect records with per-instance section data; no throughput
  comparison is implied by those counters.
- Eligibility is restricted to pinned terrain shaders and known mods. Unknown
  semantics bypass occlusion. Per-frame work is bounded; allocation profiling,
  broader worlds/effects/devices and shader/mod coexistence remain open.
- The requested complete renderer, cross-version runtime coverage, shader packs,
  lighting/generation/loading, server systems and controlled benchmarks remain
  acceptance work. This checkpoint does not close the full overhaul objective.

## Active ordinary particle instancing increment

Replace the ordinary 26.3 quad-particle feature renderer's CPU-expanded vertices
with the already extracted center/quaternion/size/UV/color/light instance records.
Own one immutable corner quad, stream bounded 56-byte instances through existing
fence-owned transient storage, and execute indexed indirect instanced draws in
the normal feature groups. Preserve layer identity, texture, append order, phase
and all normal/OIT fragment/depth/blend semantics. Match non-unit quaternion
rotation, UV corner order, ARGB channels and packed light; verify against the
pinned game expansion and actual GPU pixels. Keep shader-resource uncertainty,
reload, frame reuse, world unload and renderer closure explicit. Exercise actual
ordinary particles in the live world and retain accepted execution diagnostics.
Keep files under 500 lines, encapsulate ownership and reuse existing backend
upload/descriptor/lifetime paths. Other render domains and the full acceptance
scope remain required.

## Ordinary particle checkpoint: 2026-11-03

- Actual quad-particle feature groups upload existing extraction records directly
  as 56-byte instances and use indexed indirect instanced draws. One owned static
  corner quad replaces per-particle CPU vertex/quaternion/vector expansion.
- Pipeline variants preserve the original normal/OIT fragment and full render
  state. Native pixels match the actual game expansion with non-unit rotations,
  UV/color/light checks, nonzero offsets and source-byte reuse.
- Live FLAME/END_ROD scenes pass normal, improved-transparency and post-reload
  stages. Shader uncertainty uses the original CPU feature path; two owned cache
  generations and orderly closure are verified. Resource hashing is shared with
  terrain eligibility.
- Payload reduction is proven, not an FPS improvement or zero-allocation result.
  Stress scenes, more devices, mods/resources and custom particle types remain.
- Entity, block-entity and weather instancing, full shader packs and every other
  original acceptance requirement remain open. Server spatial-query research
  also found a callback-prefetch behavior that must survive any index replacement.

## Active ordinary weather instancing increment

Replace the ordinary 26.3 weather renderer's four-vertex column expansion with
48-byte rain/snow instances and indexed indirect draws. Keep its actual world
extraction, column order, camera-relative rounding, direction tables, UV drift,
opacity/light and normal/OIT pipeline state. Extract shared owned quad geometry,
pipeline cloning and packaged shader-source loading from the particle path rather
than duplicating them. Preserve reload/unload/empty-frame lifetime and original
rendering for uncertain resource semantics. Verify against the actual live game
weather builder (not a partially constructed headless mock), inspect rain/snow
GPU frames, exercise normal/OIT/reload and retain source-bound evidence. Keep
source files below 500 lines and make atomic commits. The complete original
renderer/shader/world/server/version/performance acceptance scope stays open.

## Ordinary weather checkpoint: 2026-11-03

- The actual 26.3 weather renderer retains world extraction and prepares 48-byte
  rain/snow instances instead of four 28-byte vertices. Normal and OIT passes use
  indexed indirect draws and the original fragment/render-state contracts.
- Particles/weather now share owned quad geometry, pipeline variants, packaged
  shader loading and vertex-output code. Frame slices are fence-owned; reload,
  clear weather, world reset and final cache/static-buffer closure are explicit.
- The real game's private weather builder supplies the live Vulkan pixel oracle,
  including UV quirks, opacity, negative fractional coordinates, signed light,
  sentinels, offsets and source-byte reuse. Headless tests remain ABI/ownership
  checks rather than claims of an authentic game constructor.
- Live server weather/biome packets exercise rain/snow normal and OIT rendering,
  reload, a mixed-biome frame and no stale draws after clearing precipitation.
- Retained evidence records seven inspected world frames, two exact live
  game-builder pixel comparisons, 157 passing JUnit checks and all 11 current
  loader packaging receipts. See [validation](docs/VALIDATION.md). An earlier
  occlusion-open timeout did not recur in two later runs; its cause remains an
  open test-stability issue, with refreshed readiness diagnostics retained.
- Input-size reduction is not a measured FPS gain. Entity/block-entity instancing,
  remaining renderers/versions, complete shader packs, world/server systems,
  broader compatibility and controlled benchmarks remain acceptance work.

## Active server section-query integration

Integrate bounded range seeks into the existing 26.3 entity section index. Small
queries should avoid scanning distant Z/Y keys in each X column without creating
a second index or retaining entity bounds. Keep the actual full-column AVL
iterator at callback boundaries. Track structural changes per storage instance;
after a callback mutates membership, drain that same iterator with the original
filters so its prefetched next-node semantics survive. Nested queries must keep
independent traversal locals. Large, unsupported and foreign-thread queries keep
the original method. A derived primitive count per X column admits long, sparse
workloads; counts affect strategy rather than entity membership. Diagnostic
counters are opt-in, instance-owned and read-only.

Compare against the untouched resolved game method for key order, padding,
negative coordinates, visibility, aborts, nested queries and insert/remove/reinsert
callbacks. Then exercise actual integrated-server entity queries, entity movement,
removal, tracking visibility and ordinary ticks in a uniquely named disposable
world, comparing with a scoped call to the original method. Package all 11 loader
targets with the integration isolated to the matching 26.3 sources. Keep source
files below 500 lines and commit runtime, proof and evidence atomically. This
does not complete pathfinding, tick scheduling, rendering or remaining versions.

## Server query checkpoint: 2026-11-03

- Matching 26.3 artifacts hook the actual entity section storage. Derived primitive
  column counts admit sparse queries; full-column iterators retain callback
  prefetch behavior through structural mutation and nested queries.
- Each pinned 26.3 loader passes 23 query oracle cases plus a descriptor test.
  The real integrated server passes 21 ordered original-method comparisons,
  movement/removal/tracking checks, 40 ordinary ticks and cleanup of 73 fixtures.
- All 11 loader packages and input/artifact receipts pass, alongside 229 recorded
  JUnit checks and the complete terrain/occlusion/particle/weather Vulkan suite.
  NeoForge test sources receive game dependencies through its documented API.
- Seven controlled helper workloads show substantial sparse-column gains and
  measurable residual routing overhead. Full tick-time, Mixin entry cost and
  allocations are still open. See [query validation](docs/ENTITY_QUERY_VALIDATION.md).
- Occupancy admission now replaces the prefix's cost-estimation role. A next
  measured simplification can remove redundant prefix scanning while keeping
  full-column interval iterators, the Long.MIN_VALUE seek guard and exact
  mutation draining. That change is proposed, not implemented in this checkpoint.
- Dedicated servers, live Forge/NeoForge sessions, other versions/mods and full
  tick/pathfinding ownership remain required, as does the original full scope.
- 2026-11-04: 1.20.1 and 1.21.1 share this integration since `da279e2`, and the
  jar verifier now expects that. NeoForge 1.21.1 has a static class and pack audit
  only; see [entity queries](docs/ENTITY_QUERY_INTEGRATION.md). Its oracle, live
  run and measured tick gain remain open.

## Next ordinary model-rendering contract

The pinned model feature path CPU-expands animated entity and chest-model cubes.
Use an owned `FeatureRenderer` registered for `ModelFeatureRenderer.TYPE`;
`RenderTypeFeatureRenderer.prepareGroup` is final. Preserve actual `setupAnim`,
hierarchy visibility/skipDraw and child order. Cache actual polygon geometry,
UVs and winding, then copy immutable part poses, color, overlay and light into
frame-owned instance records. Do not retain shared mutable model poses.

Classic translucency sorts both submits and individual quads; cube-only grouping
cannot preserve arbitrary face order. Keep cached quad ranges and the actual
game sort contract. Normal handling must preserve conditional normalization and
signed-byte quantization. Sprite UV mappings and pose-dependent sheeted decals
need explicit equivalent paths. Preserve phase order, prepared render-type state,
resource reload and fenced lifetimes. Items, moving blocks and custom geometry
are additional domains. This is a design contract, not an implementation claim.

The active tracer bullet uses shared quad geometry and 124-byte polygon instances
to preserve per-face ordering and exact mapped/decal UVs. See the
[model integration contract](docs/MODEL_INTEGRATION.md) before extending it.

The controlled [OpenGL comparison contract](docs/PERFORMANCE_COMPARISON.md)
requires actual uncapped presentation support before throughput sampling. The
current Vulkan swapchain is FIFO-only; no FPS advantage has been established.
