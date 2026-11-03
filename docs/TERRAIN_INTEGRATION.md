# Continuous terrain integration

This is the next integration design, not a claim that Overhaul's worker mesh
already replaces ordinary terrain compilation. The installed 26.3 backend executes
Minecraft's terrain draws, while the separate model smoke only proves captured
game assets can pass through Overhaul's portable mesher and native renderer.

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

## Implementation direction

Add an instance-owned compiler adapter at the existing compile invocation. It must
produce the game's normal `Results`, allowing worker-produced geometry to reach
the existing staging/upload/draw path. Reusing this path avoids another renderer,
Vulkan context, atlas readback or offscreen target.

Extract task-owned model inputs once, build portable worker geometry, and encode
it against each `ChunkSectionLayer.vertexFormat()`. The portable 32-byte vertex
format is not interchangeable with Minecraft's format. Preserve visibility,
block-entity metadata and translucent sorting. A capability check must precede
tessellation: incremental coverage must not tessellate with both compilers or
silently omit unsupported geometry.

`RenderSectionRegion` clones block palettes through `SectionCopy`, but still uses
the live client level for tint and the live light engine. Fully portable work
therefore needs frozen neighbor states, light samples, tint/model inputs and a
resource generation. Using the game's worker-region contract alone does not prove
immutable snapshot isolation.

Preserve AO, directional shading, emission, per-vertex light and leaf opacity.
Fluids have a separate `FluidRenderer.tesselate(...)` path; waterlogged blocks need
both model and fluid output. Full coverage also includes block entities and
translucency. These remain part of the requested final state.

## Acceptance evidence

Verify continuous block edits, cross-section boundary edits, rapidly superseded
rebuilds, section-coordinate reuse, resource reload and world unload. Revision
checks must include world identity, coordinates and resource generation. CPU leases
must survive staging copies, and GPU allocations must survive their last fence.
Ordinary world frames must visibly use the new geometry. Benchmark against the
existing async compiler before claiming a speedup.
