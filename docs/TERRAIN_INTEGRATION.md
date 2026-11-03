# Continuous terrain integration

The opt-in Fabric 26.3 build now replaces ordinary section compilation and streams
its geometry into the existing terrain upload/draw path. The separate model smoke
remains diagnostic. Compilation uses Minecraft's task-local model/fluid
tessellators for compatibility; fully frozen portable inputs remain future work.

## Verified 26.3 boundary

Inspection of the resolved `fabric-loom/26.3/minecraft-client.jar` shows that vanilla
already compiles terrain asynchronously. The owner-thread tessellation currently
performed by `MinecraftModelCapture` belongs to our diagnostic capture, not the
ordinary game section compiler.

The relevant classes live in `net.minecraft.client.renderer.chunk`:

| Stage | Game boundary |
| --- | --- |
| Schedule | `SectionRenderDispatcher.RenderSection.compileAsync(RenderSectionRegion)` |
| Compile | `RenderSection.CompileTask.doTask(SectionBufferBuilderPack)` calls `SectionCompiler.compile(...)` |
| Result | `SectionCompiler.Results`: rendered layers, visibility, block entities and transparency state |
| CPU staging | `RenderSection.addSectionBuffersToUberBuffer(...)` copies each layer's vertex/index data before closing `MeshData` |
| GPU upload | `SectionRenderDispatcher.uploadTerrainBuffersToGpu()` calls `UberGpuBuffer.uploadStagedAllocations(...)` |
| Invalidate | `RenderSection.reset()` cancels tasks and releases old geometry; `setSectionNode(long)` resets before section reuse |
| Transparency | `ResortTransparencyTask` builds sorted indices from `MeshData.SortState` |

## Implemented path

An instance-owned compiler adapter replaces the normal compile invocation and
produces the game's `Results`. Worker-produced geometry reaches the existing
staging/upload/draw path without another renderer, Vulkan context, atlas readback
or offscreen target.

`StreamingVertexWriter` uses one reusable page per active layer. The adapter
encodes the exact 28-byte BLOCK layout and copies pages into normal game mesh
storage; it does not reinterpret the earlier portable 32-byte diagnostic format.
Models and fluids are tessellated once. Visibility, block entities, leaf policy
and translucent sorting remain part of the returned result. Unsupported vertex
formats fail explicitly instead of silently emitting incompatible data.

Each compiler owns 64 pages of 64 KiB. Up to eight owned workers need at most three
pages each; complexity flushes pages instead of accumulating them. A coalesced
bounded MPMC scheduler admits worker wakeups; Minecraft's synchronized priority
queue still orders logical tasks. Urgent builds queue asynchronously too.
Generation retirement waits for tasks that could have captured the previous
compiler. Disposal cancels active staging-upload retries before joining workers.

`RenderSectionRegion` clones block palettes through `SectionCopy`, but still uses
the live client level for tint and the live light engine. Fully portable work
therefore needs frozen neighbor states, light samples, tint/model inputs and a
resource generation. Using the game's worker-region contract alone does not prove
immutable snapshot isolation.

The adapter preserves AO, directional shading, emission, per-vertex light and leaf opacity.
Fluids have a separate `FluidRenderer.tesselate(...)` path; waterlogged blocks need
both model and fluid output. The live check includes a chest and translucent
geometry. Broad model/fluid/block-entity parity remains part of the final target.

## Acceptance evidence

The retained scenario captures initial fixtures, edits on both sides of x=16,
distinct final block states after intermediate writes, water/lava basins and a
chest, then resource reload and another edit. Installed mesh start times must be
newer than a final client-packet barrier. Each edit requests a fresh neighbor
rebuild after that barrier. Both compiler generations run exclusively on owned
workers, complete without failures and return all page leases. Compiler closure
is checked at the appropriate retirement/app-shutdown point.

For 26.3, `levelExtractor.allChanged()` resets the section-update tracker and asks
the renderer to invalidate geometry on the next extraction. Calling only
`levelRenderer.invalidateCompiledGeometry(...)` leaves that upstream tracker
stale. The dispatcher remains client-owned in menus: null-world frames skip
extraction, and final renderer closure occurs before GPU shutdown.

Long sessions, camera-driven section reuse, dimensions, mod coexistence, stronger
snapshot isolation and broader world parity remain open. Intermediate packet
writes may coalesce; deterministic concurrency tests separately cover active-task
cancellation and generation-retirement races. Benchmark against the game's
existing async compiler before claiming a speedup.
