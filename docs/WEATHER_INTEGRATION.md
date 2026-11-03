# Ordinary weather instancing

The opt-in Fabric 26.3 backend replaces `LevelRenderer`'s normal weather renderer
with an owned subclass. World precipitation, column selection, random UV drift,
snow lighting and heightmap rules still use the inherited game extraction. The
replacement prepares compact rain/snow instances and executes ordinary normal
and OIT passes through Overhaul's Vulkan backend.

## Data contract

Each column previously expanded into four 28-byte PARTICLE vertices. The new
48-byte record stores the exact CPU-computed endpoint floats, UVs and packed
color/light. The GPU selects corners rather than recomputing camera subtraction,
direction tables or opacity with a different rounding order.

| Field | Offset | Format |
| --- | ---: | --- |
| Left X/Z, right X/Z | 0 | RGBA32 float |
| Top Y, bottom Y | 16 | RG32 float |
| U0, U1, bottom V, top V | 24 | RGBA32 float |
| ARGB | 40 | R32 unsigned integer |
| Packed light | 44 | R32 unsigned integer |

Rain and snow retain their original list order and distinct textures, with rain
drawn first. Opacity uses the game's distance/radius interpolation and ARGB
quantization, including rain's near factor 1 and snow's 0.8. Top world vertices
use bottom-Y V coordinates, matching the pinned game. The center direction-table
entry produces nonfinite values in the original; the writer preserves those
values rather than inventing another direction.

Preparation copies records synchronously into the existing submission-fenced
transient arena. Normal/OIT draws reuse those copied records. Model-view uniforms
are captured at draw time, as in the original weather renderer. Empty preparation
and world reset release frame references and zero counts. Texture-manager assets
are borrowed and refreshed during preparation; they are not closed by weather.

## Shared ownership

Particles and weather share `InstancedQuadGeometry`, `InstancedPipelineVariants`,
`OwnedVertexShaderSource` and a packaged vertex-output include. These keep one
authoritative implementation for quad indices/corners, pipeline-state cloning,
shader reading, fog, color and light decoding. Each renderer retains its own
native buffers and pipeline-cache lifetime; no mutable global resource cache is
introduced.

The original fragment shader, definitions, descriptors, depth/stencil, blending,
MRT masks, culling and sort key remain intact. Weather uses the same pinned
particle shader dependency closure, so it reuses `ShaderResourceGate` and its
existing manifest. Changed/unknown shader or mod semantics, and oversized groups,
use the original CPU weather preparation on the owned native backend. Reload
closes the owned cache; subsequent eligible frames compile a new generation.
Final renderer closure releases the cache, static quad and inherited fallback
buffer. Other version/loader renderers remain separate acceptance work.

## Verification boundary

Headless tests cover the 48-byte writer, large-world subtraction, UV/opacity/light
layout, storage changes, center-column nonfinites and packaged shader ownership.
The original weather constructor needs a real Minecraft instance, so the pixel
oracle runs in the live game. It calls the actual private `prepareInstances`
method through a read-only diagnostic bridge and uses the game's BufferBuilder
as the independent reference.

Controlled rain/snow comparisons render that reference and the production writer
and shader into separate Vulkan targets. They exercise negative fractional camera
coordinates, repeating UVs, asymmetric lighting/color, partial alpha, nonzero
buffer/command offsets, a sentinel first instance and caller-byte reuse. The
oracle requires substantial varied nonblank coverage. Reference and instanced
images are retained alongside the live world frames.

Live scenes use server weather changes and actual biome update packets. Rain,
snow, normal/OIT rendering, reload, a mixed-biome frame and clear weather are
checked. The mixed fixture compares every client column against a server-sampled
precipitation snapshot: Minecraft's jittered biome lookup does not produce a
straight visual boundary at a quart-cell edge. Both rain and snow must draw in
every OIT stage while preserving all 121 columns of the controlled radius-five
scene. Clear weather must issue no stale draws. See [validation](VALIDATION.md).

The 112-to-48-byte reduction concerns per-column vertex input, excluding shared
geometry and indirect commands. Extracted column records and preparation objects
still allocate. Stress scenes, other dimensions/devices/resources/mods and
controlled frame-time comparisons remain open. This does not complete entity or
block-entity instancing, full shader packs, world/server replacements or the full
cross-version objective.
