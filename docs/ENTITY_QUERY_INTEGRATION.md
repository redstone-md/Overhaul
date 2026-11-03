# Entity section queries

The pinned 26.3 adapters use `SectionQueryTraversal` inside the actual
`EntitySectionStorage.forEachAccessibleNonEmptySection` method. The helper borrows
the game's existing section map and ordered section IDs; it stores no entity
positions, bounds, duplicate membership index or per-tick snapshot. Other game
versions keep their original query method.

## Traversal and callback contract

The original method expands the query by two blocks in X/Z and four blocks below
Y, then scans every occupied key in each included X column. The replacement
retains the same padding, ascending X order, packed-key order, null/empty/status
filter and abort behavior. Actual `EntitySection.getEntities` still performs
entity intersection/type filtering and within-section iteration.

Each eligible column starts with its original full-column AVL iterator. At most
eight keys form a prefix sample. Short or dense columns drain that original
cursor. Qualifying sparse columns seek only the unvisited suffix of the requested
Z/Y intervals. Packed negative coordinates require positive raw-key
segments before negative segments, matching the tree's original order.

The full-column iterator is essential at every callback: fastutil advances its
cached successor before returning a key. Creating a new section between the
current and cached-next node can therefore remain unseen by the original query.
A direct cell loop or a subset narrowed to one query interval changes that
observable behavior. The helper's storage-owned epoch advances before actual
`createSection` and `remove`. If a consumer changes the epoch, traversal drains
that exact iterator for the remainder of the column. Later X columns are opened
after the callback, as in the original. Nested queries keep their own local
cursors; an inner structural change is also visible to the outer epoch check.

The helper admits at most 256 expanded cells and 32 seek intervals. A derived
primitive count per X column selects seeking only when its stored section count
exceeds both 32 and four times the queried Y/Z cell count. With no long columns,
admission returns before calculating query coordinates. Eight prefix keys per
qualifying X column are additional
reads. Dense samples and mutation fallback can still scan the original column;
this is a seek budget, not a universal bound on all scanned keys. Nonfinite,
unrepresentable, oversized, small-tree and foreign-thread queries use the original
method. A foreign structural mutation permanently disables seeking for that
storage instance. This does not make Minecraft's original structures thread-safe.

## Ownership and diagnostics

Each storage owns its helper, structural epoch and optional counters. There is
no mutable global query state. A small primitive occupancy cache holds one count
per X column, initialized from the existing map and maintained after successful
section creation and before removal of present sections. It contains no entity
membership and affects strategy only; traversal always reads the original index.
Empty columns leave the count map, while its backing capacity can retain its peak
size. Counters are enabled by the existing
`overhaul.diagnostics.enable` property and returned as immutable owner-thread
snapshots. They count handled queries, fallback routes, tree seeks, visited keys,
accepted sections, mutation drains, sampled keys and original-cursor drains.

`SectionQueryAccess.overhaul$withVanilla` scopes an independent call to the
untouched original method. Nesting and exceptions restore the prior mode in a
`finally` block. This oracle does not expose either mutable index collection.

The integration is server-safe and also applies to client-side section storage.
The three resolved 26.3 loader classes have identical normalized traversal
instructions and mutation points. That static check and descriptor tests support
the pinned builds; other transformations and ecosystem combinations still need
live verification. See the retained class audit in [validation](VALIDATION.md).

## Verification and remaining scope

Unit comparisons invoke the untouched resolved game method, rather than a copied
reference algorithm. They cover signed boundaries, seeded queries, actual entity
bounds/type filters, visibility, aborts, prefix/seek paths, insertion gaps,
prefetched removal/replacement, current-column rebuilding, future columns,
reentrancy and exception recovery.

The development query smoke creates real entities through the integrated server,
compares ordered results from ordinary Level/getter APIs with the original method,
and checks movement, removal, visibility, ordinary tick traffic and cleanup.
It runs separately from the native renderer smoke, using the normal game backend.
Its entities and reflection helpers are excluded from normal jars.

The standalone microbenchmark reuses queries and consumers, disables diagnostic
counters, warms both paths and alternates their order across six pairs. It checks
matching accepted counts/checksums without timing assertions. Its timings concern
section traversal on controlled inputs, excluding transformed Mixin entry cost,
actual AI callback work, server tick time and FPS. Initial unconditional seeks
regressed short/dense columns; prefix sampling and occupancy-based admission
reduce that cost, while routing overhead remains measurable. Retained
measurements describe each dataset separately. Broader workloads, allocation
profiling and tuning remain required.

This integrates one real spatial-query path. It does not replace tick scheduling,
AI/pathfinding algorithms, entity tracking ownership, generation, lighting,
loading, remaining render domains or other game versions.
