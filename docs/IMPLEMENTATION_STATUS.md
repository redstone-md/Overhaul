# Implementation status

This is an implementation ledger, not a declaration of production support.
The full requested outcome remains the acceptance target in `roadmap.md`.

| Requested capability | Current evidence | Remaining acceptance |
| --- | --- | --- |
| Unified Gradle monorepo | Pinned, checksum-verified wrapper; five engine modules; Java 8/17 output; engine build passes | Loader artifacts and gameplay must be checked separately |
| Fabric, Forge, NeoForge | All 11 pinned targets build; 22 loader tests and source/artifact receipts pass | Full client/server gameplay and remaining releases in the requested range |
| Legacy/intermediate/modern separation | Java 8 common contracts; loader-specific source sets; isolated legacy CoreMod toolchain | Real legacy game execution and rendering integration |
| Owned native resources | Vulkan context, queue, VMA buffers, image ownership, descriptors and teardown | Validation-layer runs and broader GPU/OS coverage |
| Indexed indirect rendering | Native readbacks of worker-produced geometry and instanced batches | Full Minecraft scene domains and asset ingestion |
| Async upload/render | Bounded fenced frame ring, deferred staging retirement, independent per-flight render targets | Connect the complete live game streaming/render loop |
| Presentation | Semaphore-driven GPU geometry blit to GLFW swapchain; resizing/suspension and 62 frames checked without steady queue-idle waits | Bridge actual game windows, including SDL on 26.3 |
| CPU occlusion | Frustum/software depth tests drive sparse GPU indirect-count updates; pixels and command restoration verified | Actual world occluder extraction/projection and game draw integration |
| Shader compilation | Native shaderc preprocessing and GLSL-to-SPIR-V compilation | Broader pack syntax, stages, custom uniforms and resources |
| Iris/OptiFine compatibility | Unchanged pinned Iris basic/textured programs compile and draw with native uniforms/textures/blending; pixel checks pass | Full-pack semantics, unsupported states/stages and real scene visual parity |
| Render graph | Dependency/lifetime plan plus native image allocation, barriers and callback execution | Actual depth/shadow/lighting/postprocessing shader passes and transient aliasing |
| Chunk meshing | Immutable palette snapshots, neighbor halo, greedy face merging, off-heap output | Minecraft model geometry, texture/biome data, translucency and special blocks |
| Lighting | Async emissive propagation over immutable snapshots | Vanilla skylight, light updates and cross-chunk engine parity |
| Generation/loading | Async immutable generator callbacks and a checked pooled snapshot codec | Integration with Minecraft generation stages, region/NBT codecs and loading dependencies |
| Memory churn | Reused slab storage, worker scratch, mapped GPU buffers and camera storage | Allocations remain in snapshots, futures and upload bookkeeping; no zero-allocation claim |
| Tick/entity/path queries | Owner-thread spatial hash and cooperative tick budgets with correctness tests | Wire actual server/entity tracking and pathfinder behavior and measure improvement |
| Renderer replacement | Adapter hooks deliberately leave the game render path active | Chunks, entities, block entities, particles, weather, GUI/text and lifecycle parity before replacement |
| Ecosystem compatibility | Strict lifecycle hooks and explicit dependency boundaries | In-game tests with major mods, reloads, dimensions, unloads, disconnects and dedicated servers |
| Performance | Geometry reduction and specific native execution paths verified | Controlled vanilla comparisons of FPS/frame times, memory, loading and tick costs |

## Verification boundaries

`gradlew build` checks CPU/shader algorithms and engine packaging. The opt-in
`integration-tests:nativeTest` checks GPU behavior and writes a rendered chunk PNG.
`vulkan-backend:vulkanSmoke --args="--present"` additionally checks the local
window/swapchain path. Native verification has run on this machine's RTX 5070.
Khronos validation layers were unavailable at inspection; a clean validation-layer
run has not been claimed. CI workflows are authored but have not run remotely.

Fabric 26.3 completed an actual isolated 200-tick client startup/shutdown smoke.
The vanilla OpenGL backend remained active. No world was entered, so this does not
establish capture of a real game chunk. Other loader runtime and legacy JVM/game
execution remain separate from their successful compile/package gates.

Fixtures and compile checks do not prove Minecraft scene replacement, full shader
pack compatibility, cross-platform native behavior or performance gains. Those
remain required work rather than inferred successes.
