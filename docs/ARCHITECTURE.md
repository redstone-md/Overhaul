# Architecture and ownership

## Dependency direction

`platform-adapters -> core-common`

Modern client adapters compose `core-common`, `vulkan-backend` and
`shader-pipeline`. Dedicated server adapters do not load graphics native libraries.
The engine modules never import Minecraft or loader classes.

`vulkan-backend -> shader-pipeline -> core-common`; the renderer uses the same
shader compiler and graphics-state builder for built-in and translated pack
programs. This avoids maintaining separate native compilation or pipeline setup
implementations. Java 8 adapters depend only on `core-common` and carry no graphics
native libraries into a dedicated server. Build checks enforce those boundaries.

`integration-tests` exercises the composed engine without asserting Minecraft
integration. Each loader target has separate mappings, metadata and packaging.

## Thread and memory rules

World state is captured on its owning thread. Workers receive immutable
snapshots and return owned mesh data. Completion does not authorize mutation of
a live world from a worker. A chunk revision must still match when its output
is accepted. Unload, replacement and cancellation release abandoned geometry.

Direct-memory leases remain valid until their owner closes them. No allocator
may recycle a region while a worker or GPU submission can still access it.
Staging resources retire only after submission completion. Vulkan queues and
resource destruction have explicit synchronization and thread ownership.

## Render replacement contract

Adapter activation must distinguish availability from completeness. A successful
Vulkan device probe is not permission to cancel vanilla rendering. Activation
requires window ownership, resource uploads, all render domains, GUI/text parity
and lifecycle cleanup. Incomplete integrations leave the vanilla path active.

Shader compilation is separate from shader-pack semantic compatibility. Supporting
GLSL syntax or translating a simple shader does not establish Iris/OptiFine
framebuffer, uniform, blending, shadow or custom texture behavior.

## Design constraints

Use encapsulated objects for stateful ownership and small immutable values for
cross-thread messages. Share mechanisms once; keep version policy in adapters.
Keep source files below 500 lines. No global mutable engine singleton, guessed
version routing, or silent compatibility fallback may replace explicit contracts.

## Baked-model integration

The version adapter copies actual game quads into immutable `ModelSnapshot`
values with a double-precision section origin. `ModelMesher` packs owned 32-byte
vertices and layer index ranges into a slab. Native upload completes before the
source lease returns. Sorting retains only the translucent index scratch and
writes only that range; opaque and cutout index data remain resident.

The current 26.3 capture invokes the game's tessellator on the world owner to
preserve model/tint/AO/light semantics. It is an extraction tracer, not the final
off-thread tessellation replacement. Its development probe copies the live atlas
and lightmap through game GPU callbacks before passing owned bytes to the worker.
It never hands game textures, models or a live world object to that worker.

The 26.3 window is created through
`com.mojang.renderpearl.api.device.GpuBackend.createWindow`; its renderer consumes
the RenderPearl `GpuDevice` API. The new SDL `SurfaceProvider` supplies native
surface ownership but is not itself that game device implementation. A Vulkan
window must be selected before creation; borrowing an existing OpenGL SDL window
is rejected. Selecting Minecraft's built-in Vulkan backend would not prove that
Overhaul's renderer has replaced the game's rendering implementation.
