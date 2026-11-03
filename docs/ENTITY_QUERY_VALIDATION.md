# Entity query validation: 2026-11-03

The matching 26.3 sources integrate range seeks into the actual entity section
storage. The [integration contract](ENTITY_QUERY_INTEGRATION.md) explains the
existing index, derived occupancy cache, admission policy and callback semantics.

## Original-method comparisons

Each pinned 26.3 loader runs 23 query tests plus a descriptor test. The oracle
calls its untouched resolved `EntitySectionStorage` method. The tests include
300 seeded ordered queries, exact entity bounds, typed selection, visibility,
aborts, signed packed coordinates, prefix/seek transitions, insertion gaps,
removed/replaced prefetched nodes, rebuilding a current column, future columns,
nested mutation, exception recovery, occupancy thresholds and foreign events.
The [class audit](evidence/entity-query-class-audit-2026-11-03.json) independently
binds the three game classes and their identical normalized query instructions.
This is static/isolated evidence, not live Forge/NeoForge gameplay.

## Actual integrated server

The [query receipt](evidence/entity-query-world-2026-11-03.json) and
[world receipt](evidence/entity-query-fixture-2026-11-03.json) name the same
disposable world and pass normal save/disconnect. The server creates 72 armor
stands in 72 real sections across six already-loaded chunks, plus one ordinary
AI-enabled pig. It refuses to load missing chunks for the fixture.

Twenty-one ordered cases compare ordinary Level/getter APIs with a scoped call
to the original method: untyped/typed queries, small/empty/broad ranges, early
abort, nested queries, movement across sections, discard, hidden/tracked/ticking
states and exception-safe oracle restoration. Abort and nested cases use an
admitted three-entity column; nesting requires at least four optimized calls.

Between proof operations, 40 ordinary world/pig ticks issue 3,126 optimized
queries. This demonstrates normal traffic, without attributing it to a particular
AI caller. The final counters report 3,143 handled queries, 2,379 seeks and 29,737
visited keys, including 25,144 prefix keys. The live run does not mutate section
membership inside query consumers; independent unit cases cover that behavior.

All 73 owned entities are discarded and absent from ID, UUID and spatial lookup.
The remaining occupancy column belongs to normal world state; cleanup does not
claim the entire game index is empty. This server proof uses the normal OpenGL
client backend, independently of the separate Overhaul Vulkan regression run.

## Controlled helper costs

The [final benchmark](evidence/entity-query-benchmark-2026-11-03.json) preserves
all six interleaved pairs, checksums, warmup counts, query dimensions, JVM/OS
details and source hashes. Diagnostic counters are disabled and both paths reuse
the same query objects and consumer. Timings include candidate admission and its
original-method fallback; they exclude transformed Mixin entry cost and actual
AI callbacks. They do not establish server tick time or FPS.

| Controlled layout | Median original ns/query | Median candidate ns/query | Median paired candidate/original ratio |
| --- | ---: | ---: | ---: |
| Populated column, local hits | 13,212.6 | 146.5 | 0.011 |
| Populated column, local misses | 13,429.6 | 106.6 | 0.008 |
| Many global sections, short local columns | 35.2 | 42.2 | 1.144 |
| Dense 256-cell query | 2,751.1 | 2,826.3 | 1.023 |
| Short 48-section layout | 146.5 | 150.4 | 1.024 |
| Live-fixture-shaped 72-section layout | 217.1 | 109.4 | 0.523 |
| Small-tree fallback | 17.2 | 24.8 | 1.274 |

Ratios above one are slower. The median paired ratio is computed from each pair,
so it need not equal the ratio of the two displayed medians. The residual routing
cost remains an acceptance concern, especially for very cheap original queries.
These measurements support workload-specific gains, not a universal speedup.

Initial unconditional seeks regressed short and dense queries substantially.
The [initial measurements](evidence/entity-query-benchmark-initial-2026-11-03.json)
and [source](evidence/entity-query-initial-traversal-2026-11-03.txt), followed by
[prefix measurements](evidence/entity-query-benchmark-prefix-2026-11-03.json) and
[source](evidence/entity-query-prefix-traversal-2026-11-03.txt), retain the evidence
that motivated occupancy-based admission. They are historical candidates, not
the current implementation.

## Reproduction and scope

The [test summary](evidence/entity-query-tests-2026-11-03.json) records 229 passing
JUnit checks: 74 engine, 17 portable native, 38 adapter native, 6 backend unit and
94 normal loader checks. The latter include 24 query/hook checks for each pinned
26.3 loader and the existing 22 loader checks. Duplicate copies in the backend
variant are excluded. The 21 live ordered cases and two live weather pixel cases
are separate from this JUnit total.

The full Overhaul Vulkan regression also passes four terrain stages, four
occlusion transitions, three particle stages and all seven weather stages, with
normal reload/save/disconnect and closed resources. The
[shutdown receipt](evidence/entity-query-backend-shutdown-2026-11-03.json) and
[world receipt](evidence/entity-query-backend-world-2026-11-03.json) retain the
result. Two representative readbacks were inspected:
[the opened wall](evidence/entity-query-backend-occlusion-open-2026-11-03.png) and
[mixed weather](evidence/entity-query-backend-weather-mixed-2026-11-03.png).
Their transient HUD values are not controlled performance measurements. The four
actual weather parity PNGs match the earlier retained targets byte-for-byte.
The earlier intermittent occlusion-open timeout remains unresolved; this run
passes all transitions and does not establish its cause.

```powershell
.\gradlew.bat -p platform-adapters/minecraft '-Ptarget=fabric-26.3' build queryBenchmark --console=plain --no-configuration-cache
.\gradlew.bat -p platform-adapters/minecraft '-Ptarget=fabric-26.3' -PworldSmoke -PquerySmoke verifyWorldSmoke --console=plain --no-configuration-cache
.\platform-adapters\minecraft\verify-targets.ps1
.\platform-adapters\minecraft\verify-targets.ps1 -CheckReceipts
```

Run builds and clients serially. Source-size and module-boundary gates remain
required. Normal jars exclude world-creation and benchmark/test classes; the
query Mixin/helper is confined to the matching 26.3 artifacts. NeoForge's test
source set receives game dependencies through `addModdingDependenciesTo`, without
booting a transformed game for the independent oracle.

The [manifest](evidence/entity-query-2026-11-03.json) binds current source, test
reports, loader inputs and artifacts. Full tick/pathfinding changes, dedicated
server and ecosystem runs, remaining versions, allocation profiling and controlled
real-workload performance remain open. So do the original renderer, shader-pack,
world-system and cross-version requirements in the main roadmap.
