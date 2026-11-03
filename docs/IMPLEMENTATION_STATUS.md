# Implementation status

This is an implementation ledger, not a declaration of production support.
The full requested outcome remains the acceptance target in `roadmap.md`.

| Requested capability | Current evidence | Remaining acceptance |
| --- | --- | --- |
| Unified Gradle monorepo | Pinned, checksum-verified wrapper; five engine modules; Java 8/17 output; engine build passes | Loader artifacts and gameplay must be checked separately |
| Fabric, Forge, NeoForge | All 11 pinned targets build; 22 loader tests and source/artifact receipts pass | Full client/server gameplay and remaining releases in the requested range |
| Legacy/intermediate/modern separation | Java 8 common contracts; loader-specific source sets; isolated legacy CoreMod toolchain | Real legacy game execution and rendering integration |
| Owned native resources | Vulkan context, queue, VMA buffers, image ownership, descriptors and teardown | Validation-layer runs and broader GPU/OS coverage |
| Indexed indirect rendering | Worker geometry/instancing and captured actual Minecraft baked-model assets render through Vulkan | Continuous game scene integration and every render domain |
| Async upload/render | Actual Fabric 26.3 game passes/transfers batch on three fenced slots; staged writes, range-aware maps, triple transient arenas and deferred pins/callbacks verified; bounded descriptor/framebuffer caches and device-preferred unmappable buffers pass native checks | Broader sessions, allocation profiling, pipeline reuse and measured throughput |
| Presentation | Borrowed GLFW/SDL surfaces, asynchronous split acquire/blit/present with three acquire semaphores; own Fabric 26.3 backend installed before its game window | Broader window/OS/GPU coverage and session/resize parity |
| CPU occlusion | Frustum/software depth tests drive sparse GPU indirect-count updates; pixels and command restoration verified | Actual world occluder extraction/projection and game draw integration |
| Shader compilation | Native shaderc preprocessing and GLSL-to-SPIR-V compilation | Broader pack syntax, stages, custom uniforms and resources |
| Iris/OptiFine compatibility | Unchanged pinned Iris basic/textured programs compile and draw with native uniforms/textures/blending; pixel checks pass | Full-pack semantics, unsupported states/stages and real scene visual parity |
| Render graph | Dependency/lifetime plan plus native image allocation, barriers and callback execution | Actual depth/shadow/lighting/postprocessing shader passes and transient aliasing |
| Chunk meshing | Palette/cube meshing plus pooled arbitrary baked quads; actual slab/stair/cutout/glass capture, atlas/lightmap draw and sorted translucency verified | Replace owner-thread tessellation, fluids, special block entities, dynamic updates and full scene ordering |
| Lighting | Async emissive propagation over immutable snapshots | Vanilla skylight, light updates and cross-chunk engine parity |
| Generation/loading | Async immutable generator callbacks and a checked pooled snapshot codec | Integration with Minecraft generation stages, region/NBT codecs and loading dependencies |
| Memory churn | Reused slab storage, worker scratch, mapped GPU buffers and camera storage | Allocations remain in snapshots, futures and upload bookkeeping; no zero-allocation claim |
| Tick/entity/path queries | Owner-thread spatial hash and cooperative tick budgets with correctness tests | Wire actual server/entity tracking and pathfinder behavior and measure improvement |
| Renderer replacement | Opt-in Fabric 26.3 selects only Overhaul's native backend; actual GUI and disposable-world frames plus save/disconnect verified | Complete render-domain/session/mod parity, every remaining loader/version, packaging and throughput |
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
objects and release their pins on logical texture/view closure. The latest world run
recorded 2,947 passes in 400 rendering submissions plus 355 presentation blits,
with 28 positive-timeout fence-wait calls including the debug readback. It reused
attachment bundles 2,909 times with 38 native framebuffer/render-pass creations,
and descriptor pools 3,664 times with 490 creations. All tracked buffer bytes and
active pool leases returned to zero at shutdown. These are
API-call counts, not blocked durations or a measured FPS gain. Full performance
and allocation profiling remains required.

Fixtures and compile checks do not prove complete Minecraft scene parity, full shader
pack compatibility, cross-platform native behavior or performance gains. Those
remain required work rather than inferred successes.
