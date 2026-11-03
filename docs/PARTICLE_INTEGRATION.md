# Ordinary quad-particle instancing

The opt-in Fabric 26.3 backend replaces the normal `QuadParticleFeatureRenderer`
registration. Actual particle simulation and extraction stay in `ParticleEngine`;
the owned renderer consumes the same feature groups, phases and texture layers.
It does not run a separate diagnostic particle renderer.

## Data and ownership

`SingleQuadParticle.extract` already produces center XYZ, quaternion XYZW, size,
UV bounds, ARGB and packed light. The previous feature renderer creates a
quaternion and four vectors per particle, then writes four 28-byte vertices.
The new renderer copies the extracted values into one 56-byte instance record.
One immutable four-corner quad and six indices serve every layer. Each draw uses
one 20-byte indexed-indirect command with that layer's particle count as its
instance count.

| Instance field | Offset | Format |
| --- | ---: | --- |
| Center | 0 | RGB32 float |
| Quaternion | 12 | RGBA32 float |
| Size | 28 | R32 float |
| UV bounds | 32 | RGBA32 float |
| ARGB | 48 | R32 unsigned integer |
| Packed light | 52 | R32 unsigned integer |

The shader reproduces JOML's non-unit quaternion division, the game's corner/UV
order, ARGB channels and signed 16-bit light components. Instance records are
copied synchronously during preparation into the existing mapped transient
arena. Its three submission-fenced generations own GPU lifetime. No mutable
particle-storage arrays escape to later rendering.

Layer identity, per-layer append order, group index and translucent phase remain
unchanged. Empty retained layer keys also participate in identity-map insertion,
preserving the original layer traversal. A group exceeding 8 MiB of instance
records uses the existing CPU feature path rather than dropping particles.

## Pipelines and resources

Renderer-owned variants preserve the original fragment shader, defines,
descriptors, depth/stencil, blending, MRT masks, polygon/culling state, push
constants and sort key. Only the vertex kernel, input bindings and triangle
topology change. Normal and improved-transparency pipelines share the same
prepared instances; OIT reuses them for depth bounds, transmittance and accumulation.

The owned kernel is packaged code. Fragment shaders and includes come from the
certified active resource manager. A renderer-owned pipeline cache closes before
reload and recompiles on the next eligible frame; its include callbacks remain
alive through compilation. The common `ShaderResourceGate` supplies terrain and
particle eligibility without duplicated allowlists or hashing logic. Twelve
pinned particle resources, unknown mods and reload state determine eligibility.
Unknown or changed semantics retain the original CPU feature path on Overhaul's
native graphics backend.

The normal feature-dispatcher close chain closes the owned cache and static
buffers. Disconnect clears prepared groups, and graphics-shutdown evidence checks
that the renderer and pipeline cache are closed.

## Verification

The native parity test renders real `QuadParticleRenderState.buildLayer` output
and the production instance writer/kernel through the shared game frontend into
separate Vulkan targets. It covers non-unit rotations, asymmetric UVs, non-grey
ARGB/alpha, signed packed light, nonzero transient offsets and a sentinel before
the selected instances. Caller bytes are overwritten after upload. The oracle
requires substantial nonblank, varied, partially transparent coverage and permits
at most four pixels differing by more than two byte values in any channel.

The live world uses actual FLAME and END_ROD providers, 64 of each per stage.
Normal mode, improved transparency and post-reload captures pass. Reload's
uncertainty window exercises the original CPU feature path, then the owned
instance path resumes with a new pipeline-cache generation. Three retained GPU
surface readbacks were visually inspected. See [validation](VALIDATION.md).

Minecraft's `RenderSystem` supports one initialization per process and does not
support shutdown followed by reinitialization. Native test classes therefore run
serially in fresh JVMs; production global state is not reset through reflection.

The 112-to-56-byte reduction concerns per-particle vertex input, excluding shared
geometry and per-draw commands. Remaining group/preparation allocations, first-use
compilation, stress scenes, additional GPUs, altered resources/mods, custom
non-quad particles and controlled frame-time comparisons remain acceptance work.
Entity, block-entity and weather instancing, full shader packs and the other
requested renderer/server/version capabilities remain separate requirements.
