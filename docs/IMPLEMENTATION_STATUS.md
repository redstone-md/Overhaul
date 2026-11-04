# Implementation status

This is an implementation ledger, not a declaration of production support.
The full requested outcome remains the acceptance target in `roadmap.md`.

| Requested capability | Current evidence | Remaining acceptance |
| --- | --- | --- |
| Unified Gradle monorepo | Pinned, checksum-verified wrapper; five engine modules; Java 8/17 output; engine build passes | Loader artifacts and gameplay must be checked separately |
| Fabric, Forge, NeoForge | All 11 pinned targets build; 94 normal loader checks and source/artifact receipts pass | Full client/server gameplay and remaining releases in the requested range |
| Legacy/intermediate/modern separation | Java 8 common contracts; loader-specific source sets; isolated legacy CoreMod toolchain | Real legacy game execution and rendering integration |
| Owned native resources | Vulkan context, queue, VMA buffers, image ownership, descriptors and teardown | Validation-layer runs and broader GPU/OS coverage |
| Indexed indirect rendering | Ordinary 26.3 terrain, quad particles and rain/snow use native indirect instanced draws; actual-builder pixel parity and normal/OIT/reload paths verified | Entity/block-entity and custom particle domains, every remaining loader/version and controlled throughput |
| Async upload/render | Actual Fabric 26.3 game passes/transfers batch on three fenced slots; staged writes, range-aware maps, triple transient arenas and deferred pins/callbacks verified; bounded descriptor/framebuffer caches and device-preferred unmappable buffers pass native checks | Broader sessions, allocation profiling, pipeline reuse and measured throughput |
| Presentation | Borrowed GLFW/SDL surfaces, asynchronous split acquire/blit/present with three acquire semaphores; own Fabric 26.3 backend installed before its game window | Broader window/OS/GPU coverage and session/resize parity |
| CPU occlusion | Ordinary 26.3 draw groups use mesh-owned emitted bounds/certified SOLID faces, bounded final-projection software depth and float uncertainty guards; live wall/hole/camera rejection and restoration verified | Broader scenes, camera effects, shaders/mods, GPUs, allocation reduction and measured improvement |
| Shader compilation | Native shaderc preprocessing and GLSL-to-SPIR-V compilation | Broader pack syntax, stages, custom uniforms and resources |
| Iris/OptiFine compatibility | Unchanged pinned Iris basic/textured programs compile and draw with native uniforms/textures/blending; pixel checks pass. Complementary Reimagined r5.9.3 renders on 26.3 Vulkan without errors in one standing daytime view (sky, clouds, fog, water, terrain, hand) | Moving/night/entity scenes and FPS with that pack; `sampler3D`, `.gsh/.tcs/.tes` and compute programs; value-option overrides; `scale.*`; per-vertex `mc_Entity`, entity ids and `entity.properties`; PBR atlas stitching; shading of programs still drawn as vanilla |
| Cascaded shadows | Owned 26.3 mode launches with 3 cascades at 1024; vanilla terrain, entity and block-entity programs receive them. One captured daytime frame shows shaded cows and a chest under a roof, a lit armor stand in the sun and a terrain shadow with sky ambient | Entities do not cast shadows; acne and cascade-seam review, A/B cost, OIT shadows, emissive entities, the hard end at far distance, certified shaders across reload |
| Render graph | Dependency/lifetime plan plus native image allocation, barriers and callback execution | Actual depth/shadow/lighting/postprocessing shader passes and transient aliasing |
| Chunk meshing | Opt-in 26.3 replaces ordinary model/fluid compilation on owned workers with bounded streaming pages; native upload, all three layers, block-entity metadata, live boundary edits and reload verified | Fully frozen worker inputs, broader models/fluids/worlds, every loader/version and full scene parity |
| Lighting | Async emissive propagation over immutable snapshots | Vanilla skylight, light updates and cross-chunk engine parity |
| Generation/loading | Async immutable generator callbacks and a checked pooled snapshot codec | Integration with Minecraft generation stages, region/NBT codecs and loading dependencies |
| Memory churn | Reused slabs/worker scratch, fixed-budget streaming terrain pages, mapped GPU buffers and camera storage; particles/weather copy compact instances without four-vertex expansion | Allocations remain in extraction/group preparation, game tessellators, snapshots, futures and upload bookkeeping; no zero-allocation claim |
| Tick/entity/path queries | Actual 26.3 section queries use occupancy-admitted range seeks on the game's index, preserving callback mutation order; live server movement/removal/tracking/oracle checks pass. From 16 players, entity tracking on 1.20.1, 1.21.1 and 26.3 visits only players in nearby grid cells or already viewing; the mixin loads on 26.3 but that path has not run with 16 real players. Pathfinding node lookup without allocation is built for the same three versions and has loaded on 26.3 only | Multiplayer run of the tracking grid, loading both mixins on 1.20.1 and 1.21.1, remaining tick/pathfinding changes, remaining versions, dedicated-server/ecosystem runs and controlled real-workload performance |
| Renderer replacement | Opt-in Fabric 26.3 selects only Overhaul's native backend; actual GUI, continuous terrain edits, fluid/chest frames, resource reload and orderly teardown verified | Complete render-domain/session/mod parity, every remaining loader/version, packaging and throughput |
| Ecosystem compatibility | Strict lifecycle hooks and explicit dependency boundaries | In-game tests with major mods, reloads, dimensions, unloads, disconnects and dedicated servers |
| Performance | Geometry reduction and specific native execution paths verified | Controlled vanilla comparisons of FPS/frame times, memory, loading and tick costs |

## Verification boundaries

`gradlew build` checks CPU/shader algorithms and engine packaging. The opt-in
`integration-tests:nativeTest` checks GPU behavior and writes a rendered chunk PNG.
`vulkan-backend:vulkanSmoke --args="--present"` additionally checks the local
window/swapchain path. Native verification has run on this machine's RTX 5070.
Khronos validation layers were unavailable at inspection; a clean validation-layer
run has not been claimed. CI workflows are authored but have not run remotely.

Fabric 26.3 completed isolated menu and live-world smoke tests. The integrated
server placed five real block fixtures; the client verified their packet states,
captured a live section twice and accepted the latest revision. The native probe
captured 283 actual baked quads, the 2048x2048 atlas and 16x16 lightmap, then drew
them through Vulkan on a bounded worker after recycling the source slab. The
saved image was inspected. All leases were returned before normal world save,
disconnect and shutdown. That earlier model-only smoke kept the game window on
its normal backend.
Other loader runtime and legacy JVM/game execution remain separate from their
successful compile/package gates.

The newer `-PoverhaulBackend` build supplies its own RenderPearl native backend.
It reuses the shared Minecraft shader/validation frontend without delegating to
Mojang's Vulkan or OpenGL backend. Native checks cover descriptors, depth/MRT,
push constants, draw variants, nullable vertex-slot unbinding, resource lifetimes,
mapped transfers, timestamps and actual SDL presentation. A disposable live world
rendered terrain, clouds, the player hand, HUD and the five block fixtures before
normal save/disconnect. GPU frame captures are retained in `docs/evidence`.
The bridge now batches GPU work asynchronously and preserves resource lifetimes
and upload order. Unmappable buffers use device-preferred allocations; mapped
buffers keep host access. Bounded descriptor/framebuffer caches reuse only retired
objects and release their pins on logical texture/view closure. The earlier cache verification run
recorded 2,947 passes in 400 rendering submissions plus 355 presentation blits,
with 28 positive-timeout fence-wait calls including the debug readback. It reused
attachment bundles 2,909 times with 38 native framebuffer/render-pass creations,
and descriptor pools 3,664 times with 490 creations. All tracked buffer bytes and
active pool leases returned to zero at shutdown. These are
API-call counts, not blocked durations or a measured FPS gain. Full performance
and allocation profiling remains required.

Continuous terrain compilation now emits all model and fluid layers directly into
the normal game upload path. The retained terrain-only scenario spans both sides of x=16, checks
installed compile timestamps after final client states, preserves a chest's
block-entity metadata and captures four native frames across resource reload.
The two compiler generations complete 98 and 66 calls exclusively on Overhaul
workers, with zero failures, owner-thread compiles or outstanding page leases.
The compiler still uses Minecraft's task-local tessellators and worker-region
light/tint queries; this is not a fully frozen portable world snapshot or an
independent replacement of the game's lighting algorithm.

The newer occlusion scenario retains four additional actual GPU frames: a wall
hides a watched section, opening a hole restores it, closing the hole hides it
again and moving the camera around the wall restores it. Decisions must match
the current draw-filter frame and a coherent installed mesh newer than the final
client-state barrier. All stages pass with precision guards enabled. The final
native receipt records 2,410 multi-record indexed-indirect calls, 3,903 calls with
an instanced binding and a maximum batch of 21 records. These are cumulative
execution counts across the scenario, not FPS or unique section counts. See
[occlusion integration](OCCLUSION_INTEGRATION.md) and its retained evidence.

Ordinary quad particles now use 56-byte instances, one static corner quad and
indexed indirect draws. A native pixel comparison uses the actual game quad
builder as its reference. Actual FLAME/END_ROD scenes pass in normal mode,
improved transparency and after reload; all three captures are retained. The
owned shader cache and static buffers close through the ordinary feature renderer
lifecycle. Unknown shader/mod semantics retain the existing CPU feature path.
See [particle integration](PARTICLE_INTEGRATION.md). The payload reduction is
verified; an overall performance gain has not been measured.

Weather now copies rain/snow columns into 48-byte records and reuses them across
normal and all three OIT stages. Shared owned quad/pipeline/source helpers also
serve particles. The live pixel oracle uses the actual weather renderer's private
game builder, avoiding a partially constructed headless game object. Server
weather/biome fixtures cover both pure precipitation types, reload, mixed columns
and clear-state lifetime. See [weather integration](WEATHER_INTEGRATION.md).

The 26.3 entity-query integration now seeks within the game's authoritative
section index. A derived primitive column count admits sparse workloads; short
and dense columns use original traversal. Callback mutations retain the actual
AVL iterator's prefetched successor, including insert/remove/reinsert and nested
queries. The integrated-server proof compares 21 ordered cases with the original
method and verifies 72 real section fixtures, ordinary tick traffic and cleanup.
Controlled helper benchmarks show workload-specific gains and residual routing
overhead; full tick-time and transformed-entry costs remain unmeasured. See
[entity query integration](ENTITY_QUERY_INTEGRATION.md).

Fixtures and compile checks do not prove complete Minecraft scene parity, full shader
pack compatibility, cross-platform native behavior or performance gains. Those
remain required work rather than inferred successes.
