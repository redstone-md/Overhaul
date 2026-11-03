# Ordinary model instancing (implementation in progress)

The next renderer increment replaces CPU-expanded `ModelFeatureRenderer` cube
vertices for ordinary animated entity and model-based block-entity submissions.
The existing game animation and `ModelPart.render` hierarchy remain authoritative.
An owned capture consumer receives each cube through a scoped compile hook; no
global capture state or mutable pose reference is retained for later GPU use.

## Tracer-bullet contract

Use the existing shared corner quad, pipeline-state variants and owned shader
source loading. Each polygon becomes one hardware instance in its original
prepared render-type batch. A 124-byte record contains three affine position
rows, exact bounding coordinates and corner-selection bits, four UV pairs and
packed normal/color/light/overlay. Standard cube faces select their actual corner
coordinates exactly; mirrored winding and omitted faces are preserved. Cache
immutable copies of geometry metadata, with explicit bounds and reload invalidation.
Source polygon/vertex identities, mutable normal bits and retained hierarchy
collections are checked for changes. Changed metadata is rebuilt before use;
animation is never replayed to recover from a late unsupported mutation.

The original ENTITY expansion consumes four 36-byte vertices per quad. The new
record reduces that vertex payload from 144 to 124 bytes, before shared geometry
and indirect arguments. Glint POSITION_TEX and crumbling BLOCK inputs are smaller
than this general record; do not claim a payload reduction for those formats.
These are data sizes, not measured performance results.

Copy poses during preparation and match JOML's position-operation association,
identity/translation paths and FMA setting. Transform each original face normal
with the actual `Pose.transformNormal` method, then preserve the game's signed-byte
quantization. UV mappings retain all four exact mapped pairs. Sheeted decals use
the actual game generator for projected UVs, including its white color override;
this special path can still perform CPU position calculations for UV projection.

Adapt certified original vertex programs around decoded instance inputs rather
than duplicating their lighting/fog/overlay/texture logic. Preserve original
fragment programs, definitions, resource bindings, depth/blend/MRT state and sort
keys. Unknown resource/mod semantics keep the existing CPU preparation on the
owned Vulkan backend.
All OIT pipeline variants are checked through read-only setup metadata before
animation. Preparing textures/transforms retains the original per-submit timing.

Match render-type consolidation and prepared-state timing, including layering,
texture transforms, scissor and borrowed textures. Classic translucency sorts
individual quad records using the actual game centroid and sorting contracts.
OIT reuses the same prepared records across its passes. All native frame data
uses existing submission-fenced storage; renderer-owned scratch and caches have
bounded lifetimes and close through the feature dispatcher.

## Required proof

Compare records and actual GPU pixels with the unchanged game cube/model builder.
Cover nonuniform/mirrored poses, quantized lighting, UV maps, sheeted decals,
glint/outline programs, classic face sorting, OIT, nonzero offsets, source reuse
and full pipeline-state preservation. Then verify actual mobs and chest models,
animation, reload, world unload and orderly native shutdown in a disposable world.
Keep every source file below 500 lines and make atomic commits.

Items, moving block models, text, beams, custom geometry, full shader packs and
other version/loader renderers remain additional work. This document is the
implementation contract; verification evidence must be added before claiming
the model path is complete.
