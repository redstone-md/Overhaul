# Validation checkpoint: 2026-11-03

Local platform: Windows, Temurin JDK 25.0.3, NVIDIA GeForce RTX 5070.
The worktree began empty; no existing launcher instance or save was modified.

## Engine

The engine command below passed; the table also includes separately run backend checks:

```powershell
.\gradlew.bat build :integration-tests:nativeTest --console=plain
```

| Suite | Tests | Failures | Skipped |
| --- | ---: | ---: | ---: |
| core-common | 48 | 0 | 0 |
| shader-pipeline | 16 | 0 | 0 |
| platform-adapters | 3 | 0 | 0 |
| vulkan-backend | 7 | 0 | 0 |
| native integration | 17 | 0 | 0 |
| experimental Fabric 26.3 backend GPU | 30 | 0 | 0 |
| backend read-range unit | 2 | 0 | 0 |
| terrain encoding parity (native memory) | 3 | 0 | 0 |
| emitted terrain geometry capture (native memory) | 1 | 0 | 0 |
| particle pipeline state/ABI contract | 1 | 0 | 0 |
| terrain worker concurrency | 4 | 0 | 0 |

Source files are below 500 lines, and the engine module import-boundary gate passes.

The native smoke verified exact 65,536-byte staging/device/readback transfer;
indexed indirect depth-tested drawing; 11 transfers across three fenced slots;
multi-draw and single-draw fallback with three instances; sparse visibility changes
and restoration without geometry copies; and 62 geometry presentations across
resize using acquire/present semaphores. The reference triangle covered 5,202
pixels with CRC32 `835141031` on this device.

The native integration tests cover worker-built chunk geometry after source slabs
are overwritten, native graph image barriers, and unchanged pinned Iris example
basic/textured programs. Pixel checks exercise uniform updates, texture rows,
OpenGL-to-Vulkan clip conversion and blend state. PNGs were visually inspected at:

- `integration-tests/build/verification/chunk-vulkan.png`
- `integration-tests/build/verification/iris-basic.png`
- `integration-tests/build/verification/iris-basic-blended.png`
- `integration-tests/build/verification/iris-textured.png`
- `integration-tests/build/verification/iris-textured-toprow.png`

Khronos validation layers were unavailable. These results are native execution
checks without validation layers, not a claim of cross-device correctness.

The isolated `:vulkan-backend:sdlVulkanSmoke` also passes real SDL3/Vulkan
readback, 52 instanced presentations and resize on LWJGL 3.4.3+4, with no GLFW
class on that runtime. Borrowed-window ownership and rejection of non-Vulkan SDL
windows are checked. The separate `-sdl34.jar` contains the provider, not its smoke
launcher or native libraries.

## Loader packaging

The serial runner passed all 11 pinned targets with 22 tests, without source drift:

| Loader | Minecraft versions |
| --- | --- |
| Fabric | 1.16.5, 1.20.1, 1.21.1, 26.3 |
| Forge | 1.12.2, 1.16.5, 1.20.1, 1.21.1, 26.3 |
| NeoForge | 1.21.1, 26.3 |

The [current retained receipts](evidence/loader-targets-particles-2026-11-03.json) contain per-jar
SHA-256 values and a shared raw-source digest. `verify-targets.ps1 -CheckReceipts`
subsequently confirmed those sources and every existing jar still match. These
pins represent the requested eras; they do not cover every intervening release.
The dedicated-server entrypoint tests forbid loading client/native packages.
Legacy CoreMod fixtures are transformed and executed in tests; a real Java 8
Minecraft 1.12.2 runtime has not been exercised.

## Actual client lifecycle

This command passed with exit code zero:

```powershell
.\gradlew.bat -p platform-adapters/minecraft '-Ptarget=fabric-26.3' '-PsmokeClient=200' runClient --console=plain --no-configuration-cache
```

Fabric loaded the adapter, initialized the actual game window, textures and sound,
ran the diagnostic hook for 200 ticks, then invoked the game's own `stop()` method
and shut down normally. An earlier launch exposed an overly broad Mixin namespace;
the package was narrowed and a jar invariant was added before the successful run.

That menu test reported its vanilla OpenGL backend and did not enter a world.
The subsequent test below establishes actual world capture and native asset
rendering. Desktop automation remained unavailable, so no screenshot of the
game window or full gameplay parity check has been claimed.

## Live world and native baked models

The latest-code command passed with exit code zero:

```powershell
.\gradlew.bat :vulkan-backend:jar :shader-pipeline:jar
.\gradlew.bat -p platform-adapters/minecraft '-Ptarget=fabric-26.3' -PworldSmoke -PmodelSmoke verifyWorldSmoke --console=plain --no-configuration-cache
```

The integrated server created a new world inside the isolated development run
directory, placed a slab, glass, a flower, stairs and leaves on its server thread,
and the client confirmed all five packet states before capture. Revision two
superseded revision one and was accepted with nonempty geometry.

The actual game tessellator then supplied 283 baked quads, including 19 with
non-integer geometry, nine cutout quads and five translucent quads. GPU callbacks
copied the real 2048x2048 block atlas and 16x16 lightmap into owned bytes. A bounded
worker packed and uploaded 1,698 indices, released and overwrote its source slab,
then rendered the same pixels twice through Overhaul's Vulkan model path. The
512x512 image had 132,868 non-background pixels and was visually inspected:

![Actual Minecraft model assets rendered by Overhaul](evidence/world-model-2026-11-03.png)

The [receipt and artifact hashes](evidence/world-model-2026-11-03.json) retain the
source commit, native jar hashes, image hash, world identity, exact fixture states
and zero-lease teardown result. The game saved, disconnected and stopped normally.
The game window remained on vanilla OpenGL: the Vulkan result is an offscreen
draw of its captured assets, not a replacement for its onscreen renderer.

Additional native tests verify partial mapped writes, preserved opaque/cutout
indices, correct translucent blend ordering and no upload for a stationary camera.
The mixed-layer fixture uploads 48 translucent index bytes on movement instead of
the full 96-byte index buffer. This is a measured transfer count for that fixture,
not an FPS claim.

## Actual Overhaul game backend

The opt-in Fabric 26.3 commands passed:

```powershell
.\gradlew.bat :vulkan-backend:assemble :shader-pipeline:jar
.\gradlew.bat -p platform-adapters/minecraft '-Ptarget=fabric-26.3' -PoverhaulBackend build backendNativeTest --console=plain --no-configuration-cache
.\gradlew.bat -p platform-adapters/minecraft '-Ptarget=fabric-26.3' -PoverhaulBackend '-PsmokeClient=200' verifyBackendSmoke --console=plain --no-configuration-cache
.\gradlew.bat -p platform-adapters/minecraft '-Ptarget=fabric-26.3' -PoverhaulBackend -PworldSmoke verifyWorldSmoke verifyBackendSmoke --console=plain --no-configuration-cache
```

The actual game logged `Using graphics backend Overhaul Vulkan`. Its factory
created the device before the SDL window. All native resources, pipelines,
commands and presentation use Overhaul; the shared game frontend supplies shader
compilation/validation. The backend list contains no fallback.

Nine native tests pass with LWJGL's debug allocator enabled. They verify actual
frontend shader compilation, UBO/image/texel descriptors, MRT masks, depth
load/test/write, push constants, direct/indexed/indirect/multi-draw results,
logical-close pins, nullable vertex-slot unbinding, mapping/transient reuse,
pitched/mip transfers, partial clears, timestamps, SDL acquire/blit/present,
resize and borrowed-window ownership. Three additional portable native tests
cover images/mapped ranges. The existing renderer smoke paths still pass.

The 200-tick GUI run rendered accessibility onboarding, panorama, text and buttons,
then stopped normally. Its [receipt](evidence/backend-screen-2026-11-03.json) and
[GPU image](evidence/backend-screen-2026-11-03.png) are retained. Inspection exposed
a missing presentation Y inversion, which was corrected before the retained run.

The live-world run verified all five server-to-client fixture states and latest
snapshot revision. It rendered world terrain, clouds, hand, HUD and fixture
models through the actual selected backend, then saved, disconnected and closed
with zero snapshot leases. The [world receipt](evidence/backend-world-2026-11-03.json),
[fixture receipt](evidence/backend-world-fixture-2026-11-03.json) and image are retained:

![Actual Overhaul backend world frame](evidence/backend-world-2026-11-03.png)

The [evidence manifest](evidence/backend-2026-11-03.json) records matching source
commits, native jar/image hashes, commands, test counts and the server-owned
reference camera pose.

These images are readbacks of the actual source passed to the swapchain blit,
with its presentation Y inversion applied; they are not desktop screenshots.
The test does not cover every entity/particle/weather path, long sessions,
reloads, dimensions or mod coexistence. That initial checkpoint used synchronous
submission; the asynchronous increment below supersedes it. Performance remains
unmeasured against a controlled reference.
No Khronos validation layers were available.

Adapter builds/clients must run serially: switching the same Gradle project to
another variant can clean the previous output directory while a client still
loads its classes. The final world checks ran without concurrent adapter builds.

## Bounded asynchronous game commands

The same GUI and disposable-world commands pass after removing per-pass waits.
An ordered queue batches passes, buffer copies, image operations and timestamp
commands on three fenced slots. Pins, descriptors and attachment views survive
until the owning fence completes; failed batches restore image layouts in reverse
order and do not publish callbacks or timestamp generations. CPU buffer access
waits only for overlapping work, respecting non-coherent atom granularity.
Transient arenas rotate over three frames under their shared memory budget.

Fence markers stay in their batch until submission; polling unsubmitted work
returns pending. A nonzero wait can submit it. This avoids premature submissions
from game resource-pool fences. Readback completion callbacks may safely map or
enqueue work. Private attachment views retire at their own fence and do not wait
for later image-only presentation blits.

New native cases verify real timed-out GPU work held by a native event, retirement
exactly once, many markers sharing a submission, older receipt retirement,
disjoint maps, copied-data ordering, source snapshot ownership, callback reentry,
rollback/recovery, stale query generations and private-view versus image lifetime.
The interval unit checks compare merged ranges with an independent byte oracle.
SDL verification additionally presents 24 asynchronous split frames over three
acquire slots and exercises resize, abandoned acquisition and close.

The [async world receipt](evidence/backend-async-world-2026-11-03.json) records:

| Counter | Observed |
| --- | ---: |
| Presented frames / native blits | 359 |
| Render passes / draws | 2,900 / 26,899 |
| Rendering queue submissions / operations | 403 / 21,324 |
| Rendering queue positive-timeout fence waits | 27 |
| Presentation positive-timeout fence waits | 1 |
| Synchronous verification submissions / waits | 1 / 1 |

Counters include startup and shutdown. A positive-timeout wait invocation may
return immediately; these numbers do not measure blocked time, FPS or frame-time
gains. Rendering submissions exclude the separate swapchain blit per frame.
The one synchronous submission reads the debug image. All five block fixtures
and zero snapshot leases were verified before normal save/disconnect and close.
The latest [world image](evidence/backend-async-world-2026-11-03.png),
[GUI image](evidence/backend-async-screen-2026-11-03.png) and
[manifest](evidence/backend-async-2026-11-03.json) retain this checkpoint.
This checkpoint retained host-visible static buffers. The subsequent native
allocation increment moves unmappable buffers to device-preferred storage and
adds bounded descriptor/framebuffer reuse. Allocation profiling and controlled
reference benchmarks remain open.

## Fence-retired native allocation reuse

At code commit `f212d21`, the backend build and all 27 native cases pass. Ten
additional cases cover device-preferred initial uploads and partial-update order,
staging ownership/rollback, descriptor capacity reuse across destroyed pipelines,
real-fence isolation, early close and reentrant retirement, framebuffer load/clear
semantics, attachment order/holes/depth/mips, invalidation and bounded eviction.
The normal source-size and module-boundary gates also pass.

Both the actual disposable-world command above and the 200-tick GUI command pass
with the new allocation policy and caches. Their GPU-source PNGs were inspected:
[world](evidence/backend-reuse-world-2026-11-03.png) and
[GUI](evidence/backend-reuse-screen-2026-11-03.png). Terrain, five block fixtures,
clouds, player hand/HUD and readable onboarding controls remain present.

| Counter | World | GUI |
| --- | ---: | ---: |
| Presented frames | 355 | 528 |
| Render passes | 2,947 | 4,900 |
| Render submissions | 400 | 573 |
| Framebuffer/render-pass creations | 38 | 31 |
| Attachment bundle reuses | 2,909 | 4,869 |
| Descriptor pool creations | 490 | 838 |
| Descriptor pool reuses | 3,664 | 5,598 |
| Device-preferred buffer allocations | 58 | 42 |
| Host-accessible buffer allocations | 103 | 68 |
| Peak tracked live buffer bytes | 471,031,358 | 32,874,816 |

The world has 26 render-fence wait calls, one presentation-fence wait call and one
synchronous debug readback. All tracked buffer bytes, active/idle descriptor pools
and attachment leases are zero at shutdown; descriptor creation/destruction counts
match. Five fixture packet states, revision 2 and zero snapshot leases also pass.
The [manifest](evidence/backend-reuse-2026-11-03.json) hashes the receipts and images.

Counters include startup and shutdown. They are not a controlled A/B benchmark,
blocked-time measurement or FPS result. Buffer byte totals exclude images, driver
storage and the rest of the process. Device-preferred VMA selection does not imply
dedicated VRAM on unified-memory hardware; CPU mapping is retained only when the
buffer usage requests it. Idle caches are bounded at 16 pools/8,192 descriptors and
64 complete attachment bundles; accepted in-flight work owns separate leases.
Validation layers, broad session/render-domain parity and controlled performance
remain open. The continuous terrain integration boundary is recorded in
[TERRAIN_INTEGRATION.md](TERRAIN_INTEGRATION.md).

## Continuous ordinary terrain compilation

The opt-in 26.3 compiler now replaces ordinary section output, including model
layers and fluids, and feeds the existing mesh upload/draw path. Urgent rebuilds
also run on owned workers. Each layer streams through one 64 KiB page under a
compiler-owned 4 MiB budget. The three encoding checks compare actual bytes and
translucent indices against Minecraft's builder; four concurrency checks cover
coalesced admission, active cancellation, generation retirement and failures.

The actual world command passes four captured stages:

| Stage | Completed compiles in generation | Left/right observed completions | Evidence |
| --- | ---: | ---: | --- |
| Initial fixtures | 40 | 1 / 1 | [frame](evidence/backend-terrain-initial-2026-11-03.png) |
| Boundary wool blocks, fluid basins and chest | 74 | 3 / 3 | [frame](evidence/backend-terrain-boundary-2026-11-03.png) |
| Gold/emerald replacement | 98 | 4 / 4 | [frame](evidence/backend-terrain-edited-2026-11-03.png) |
| Reload, new compiler, iron/diamond replacement | 66 | 2 / 2 | [frame](evidence/backend-terrain-reloaded-2026-11-03.png) |

Every edited mesh's compile start is newer than its final received client-state
barrier. The smoke explicitly requests fresh neighbor rebuilds after each barrier.
It verifies both boundary sections, translucent geometry, water/lava client states
and chest metadata. Intermediate server writes may coalesce; the live run is not
proof that every cancellation interleaving occurred.

The old/new generations finish 98/66 calls with zero failures or owner-thread
compiles, exclusively on `overhaul-terrain-*` workers. At disconnect, tasks/pumps
and page leases are zero and the old compiler is retired. Minecraft retains the
current dispatcher in menus; [graphics shutdown](evidence/backend-terrain-shutdown-2026-11-03.json)
confirms its final closure before the device closes. The app renders 1,017 frames,
6,426 passes and 80,628 draws and saves/disconnects normally. Four synchronous
verification submissions capture the GPU-source images. These are not FPS or
blocked-time comparisons.

The [scenario receipt](evidence/backend-terrain-scenarios-2026-11-03.json) and
[manifest](evidence/backend-terrain-2026-11-03.json) retain the checks and hashes.
The driver uses the full `levelExtractor.allChanged()` invalidation facade after
resource reload. The actual compiler still uses shared game tessellation and
worker-region light/tint queries. Fully frozen inputs, independent lighting,
larger worlds, dimensions and full terrain/mod parity remain open.

## Ordinary terrain occlusion and native multidraw

The current opt-in Fabric 26.3 run passes the existing boundary/fluid/chest/reload
scenarios plus four actual draw-filter transitions. The watched diamond-block
section stays in vanilla's visible list; rejection happens in Overhaul's ordinary
draw-group extraction. Each observation matches the current filter frame and the
exact installed mesh, whose compile start follows a fresh final-client-state
barrier.

| Scene | Watched section culled | Filter frame | GPU surface source |
| --- | --- | ---: | --- |
| Wall in front | yes | 987 | [frame](evidence/backend-occlusion-wall-2026-11-03.png) |
| Hole opened | no | 1096 | [frame](evidence/backend-occlusion-open-2026-11-03.png) |
| Hole closed | yes | 1208 | [frame](evidence/backend-occlusion-closed-2026-11-03.png) |
| Camera moved around the wall | no | 1317 | [frame](evidence/backend-occlusion-camera-2026-11-03.png) |

All four readbacks were visually inspected: the target appears through the hole
and beside the wall after camera movement. The retained section diagnostics also
show both wall meshes remaining visible and the opened wall's certified face
rectangles changing from 5 to 15, preserving the hole.

The [scenario receipt](evidence/backend-occlusion-scenarios-2026-11-03.json),
[compiler receipt](evidence/backend-occlusion-terrain-2026-11-03.json) and
[shutdown receipt](evidence/backend-occlusion-shutdown-2026-11-03.json) retain the
observations. The run completed 99/143 compiles across its two compiler
generations, exclusively on owned workers, with no failures or remaining page
leases. The current compiler closes before GPU shutdown.

The accepted native counters report 3,903 indexed-indirect calls containing
25,341 records; 2,410 calls contain multiple records, with a maximum batch of 21.
All 3,903 calls bind a per-instance vertex stream. The ordinary renderer reports
that its indirect terrain path was selected. A separate native pixel test also
checks two records with different nonzero first instances in one actual call,
and excludes rejected recordings from both counters and pixels. Correct unsigned
conversion of Vulkan's draw-count limit enables this path on the tested device.

The final culling counters report at most four rejected sections per frame and
eight restorations across the run. Near crossings and excessive float uncertainty
are explicitly rejected; tests reproduce the grazing 16-block narrow-FOV
counterexample and exercise both shader matrix-association orders. The hierarchy
uses whole-cell coverage and outward depth/rectangle margins. See
[the integration contract](OCCLUSION_INTEGRATION.md).

Current commands passed:

```powershell
.\gradlew.bat build :integration-tests:nativeTest --console=plain
.\gradlew.bat -p platform-adapters/minecraft '-Ptarget=fabric-26.3' -PoverhaulBackend build backendNativeTest --console=plain --no-configuration-cache
.\gradlew.bat -p platform-adapters/minecraft '-Ptarget=fabric-26.3' -PoverhaulBackend -PworldSmoke verifyWorldSmoke verifyBackendSmoke --console=plain --no-configuration-cache
.\platform-adapters\minecraft\verify-targets.ps1
.\platform-adapters\minecraft\verify-targets.ps1 -CheckReceipts
```

The [manifest](evidence/backend-occlusion-2026-11-03.json) binds the source commit,
test totals, loader input digest and artifact/evidence hashes. The totals cover
152 checks: 74 engine unit, 17 portable native, 29 adapter GPU, 3 native vertex
parity, 1 native geometry capture, 2 backend range, 4 worker and 22 normal loader
checks, with no failures or skips. This remains local execution on one RTX 5070
without validation layers. Counters include startup, edits, reload and teardown;
they are not unique section counts, blocked durations or an FPS comparison.

## Ordinary particle instancing

The latest opt-in Fabric 26.3 run passes ordinary terrain, occlusion and particle
scenarios, including normal save/disconnect and shutdown. Actual particle
providers create 64 FLAME and 64 END_ROD particles per stage. The normal feature
path copies extracted records into mapped frame-owned storage and issues indexed
indirect draws with up to 64 instances per layer.

| Stage | Normal draw requests | OIT draw requests | Prepared records | Instance bytes | GPU surface source |
| --- | ---: | ---: | ---: | ---: | --- |
| Normal transparency | 200 | 0 | 12,800 | 716,800 | [frame](evidence/backend-particles-normal-2026-11-03.png) |
| Improved transparency | 100 | 300 | 12,800 | 716,800 | [frame](evidence/backend-particles-oit-2026-11-03.png) |
| Reload, improved transparency | 100 | 300 | 12,800 | 716,800 | [frame](evidence/backend-particles-reloaded-2026-11-03.png) |

All three readbacks were visually inspected. The OIT phase reuses the same
prepared records across depth bounds, transmittance and accumulation. Its extra
draw requests are not extra CPU vertex expansion. The native parity test also
compares the production writer/kernel against actual game `buildLayer` output,
with a substantial nonblank/varied/partial-alpha oracle. It covers non-unit
quaternions, UVs, packed color/light, sentinels, nonzero instance/command offsets
and caller-byte reuse. A separate contract checks full pipeline state cloning.

The [scenario receipt](evidence/backend-particles-scenarios-2026-11-03.json) and
[shutdown receipt](evidence/backend-particles-shutdown-2026-11-03.json) retain
the counters. The renderer closes both owned static buffers and its pipeline
cache. Two cache generations are observed across reload. During the reload's
uncertainty window, 86 groups use the existing CPU feature path; every captured
stage returns to the instance path with no fallback groups during its capture.

Particle counters are frontend requests. Device counters separately report 7,823
accepted native indexed-indirect calls containing 44,135 records across the
entire world scenario, including terrain and background work. The particle
`frames` field counts feature preparation calls, which can occur several times
per presented frame. None of these counts establishes FPS or blocked time.

The [manifest](evidence/backend-particles-2026-11-03.json) binds source commit,
raw loader-input digest, artifacts, three frames and the receipts. Its
[test summary](evidence/backend-particles-tests-2026-11-03.json) records 154
passing checks: 74 engine unit, 17 portable native, 30 backend GPU, 3 native
terrain parity, 1 geometry capture, 1 particle pipeline contract, 2 range,
4 worker and 22 normal loader checks. There are no failures or skips.

The engine build/native integration, full isolated backend native suite, live
world verifiers and serial 11-target packaging/receipt checks all pass. Native
test classes use fresh JVMs because Minecraft does not support reinitializing
its process-owned render system after shutdown. Source-size and module-boundary
gates pass. See [particle integration](PARTICLE_INTEGRATION.md) for ownership,
fallback and shader-resource boundaries. Validation remains local to the tested
RTX 5070 without validation layers; controlled performance comparisons remain.

## Remaining evidence

Remote CI has not executed. Complete Minecraft Vulkan scene parity, full shader
packs, actual world-generation/loading/lighting/tick changes, all render domains,
remaining versions, ecosystem parity and controlled performance comparisons are
still required. See `IMPLEMENTATION_STATUS.md` and `roadmap.md`.
