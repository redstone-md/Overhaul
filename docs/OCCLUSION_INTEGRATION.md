# Ordinary terrain occlusion

The opt-in Fabric 26.3 backend filters normal section draw groups using a bounded
160 x 90 software depth hierarchy. It does not replace the game's visible-section
list or the entity/block-entity lists. Other versions and loaders retain their
existing paths.

## Geometry ownership

The existing single terrain emission pass records every emitted vertex in its
bounds. Only actually emitted SOLID, axis-aligned, integral unit quads qualify as
occluders. Six signed face masks merge adjacent coplanar faces without covering
holes. Each mesh retains immutable bounds and at most 4,096 face rectangles;
dropping excess rectangles reduces culling. Protruding models affect candidate
bounds, while partial, non-grid and uncertain faces cannot become occluders.

Metadata travels with `SectionCompiler.Results` into `CompiledSectionMesh`. The
draw-group transaction snapshots each section node and mesh under the game's
copy lock, then uses that same mesh for draw extraction. A worker can publish a
new mesh during extraction, but retirement cannot invalidate the captured mesh
before that lock is released. No global section-to-mesh registry is introduced.

Only uploaded SOLID geometry with ready allocations and completed fade-in supplies
depth. Entries are visited near to far; returned sections preserve their original
order. Each frame attempts at most 8,192 faces and rasterizes at most 256. Frames
with more than 8,192 sections bypass culling. Exhausting a face budget leaves
additional geometry visible.

## Projection and numerical uncertainty

The projection hook captures the final matrix uploaded by `GameRenderer`, including
bob, hurt and portal effects. It composes the view rotation with camera-relative
double coordinates and converts reversed Vulkan depth to standard software depth.
A finite effective perspective eye accounts for translations inside the final
projection when selecting front faces. Reflected, singular and uncertain transforms
disable the filter.

The precision guard retains both uploaded float matrices. Its roundoff budget
accounts for position additions and matrix operations; ill-conditioned geometry
fails open. Occluders crossing the original near plane are excluded. Accepted
occluders must stay within a 0.002 software-pixel coordinate error and 0.000001
depth error. Full-cell edge tests retain an additional 0.01-pixel inset. Stored
occluder depth is at least the farthest original corner plus its depth error,
even when viewport clipping would yield a nearer polygon.

Candidates require bounded projection error, expand their screen rectangle by one
software cell and subtract the accepted depth error from their nearest depth.
Cells contribute depth only when the entire cell lies inside a certified face;
the hierarchy retains the farthest child depth. These margins deliberately reduce
culling rather than treating numerically uncertain coverage as opaque.

## Shader eligibility

Fifteen hashes cover the pinned vanilla terrain shaders and their recursive
includes. Unknown mods, changed shader resources and reload-in-progress disable
occlusion. Reload completion invalidates certification and clears frame state.
The current known-mod set is intentionally small; this is a conservative gate,
not evidence of shader-pack or general mod compatibility.

## Verification boundary

The live smoke uses an actual server-placed wall and a diamond block in a separate
section. It requires rejection behind the wall, restoration through an opened
hole, renewed rejection after closing the hole and restoration after moving the
camera around the wall. Each decision must observe the current frame and the
installed mesh compiled after the final client-packet barrier. Native surface
readbacks accompany all four stages.

Native draw counters count accepted submissions beside the actual Vulkan calls.
The device's unsigned `maxDrawIndirectCount` is clamped to Java's positive range;
the prior signed conversion incorrectly disabled native terrain multidraw on the
tested GPU. Counts establish execution, not frame-rate improvement.

Controlled benchmarks, frame-allocation reduction, broader scenes, dimensions,
extreme camera effects, additional GPUs, validation-layer runs and shader/mod
coexistence remain open. This increment does not add entity/particle instancing,
shader-pack rendering, lighting, generation or server-system replacements.
