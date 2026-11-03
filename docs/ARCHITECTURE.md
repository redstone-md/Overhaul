# Architecture and ownership

## Dependency direction

`platform-adapters -> core-common`

Modern client adapters compose `core-common`, `vulkan-backend` and
`shader-pipeline`. Dedicated server adapters do not load graphics native libraries.
The engine modules never import Minecraft or loader classes.

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
