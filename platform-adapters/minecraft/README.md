# Minecraft adapter targets

Normal builds are lifecycle and world-worker adapters. An opt-in Fabric 26.3
source variant implements the game's graphics backend with Overhaul's Vulkan
engine. Native libraries are not bundled into the adapter jars.
Client and dedicated-server runtime state belongs to each game/server instance.

`targets.json` is the target inventory. Fabric and NeoForge did not exist for
1.12.2; the legacy target is Forge only. The pins cover representative releases
in each requested era, not every intervening Minecraft release.

From the repository root, with `JAVA_HOME` pointing to an installed JDK25:

```powershell
.\gradlew.bat -p platform-adapters/minecraft '-Ptarget=fabric-26.3' build
.\platform-adapters\minecraft\verify-targets.ps1
.\platform-adapters\minecraft\verify-targets.ps1 -CheckReceipts
```

The verification script needs PowerShell7. It builds serially because Loom shares
its Minecraft artifact cache. Output jars and reports live under
`build/<target>/`; the serial runner records artifact SHA-256 values in
`build/target-results.json`. Builds use a Java25 compiler with the target's Java
bytecode release. Running older Minecraft versions also requires an appropriate
runtime JDK and is a separate acceptance gate.
`-CheckReceipts` checks existing jars against their hashes and the current source
digest without rebuilding them. A source change invalidates an earlier receipt.

## Toolchains

- Mapped Fabric/Forge: pinned Architectury Loom1.17.493, Mojang mappings,
  production namespace remapping. Forge uses the legacy Mixin annotation
  processor to generate SRG refmaps.
- Fabric26.3: Fabric Loom1.18.2 without obfuscation/remapping.
- NeoForge: ModDevGradle2.0.147, official patched development artifacts.
- Forge26.3: ForgeGradle7.0.17 and the API from Forge26.3-66.1.5's official MDK.
- Forge1.12.2: isolated custom Java8 toolchain against the universal Forge API,
  LaunchWrapper and ASM5.2. No Minecraft class/method calls are linked into this
  adapter. The CoreMod adds instance fields and straight-line lifecycle hooks
  after SRG deobfuscation. Method names are verified against MCP stable39-1.12.

Legacy transformation fixtures are verified and executed in unit tests. This
does not establish that a live legacy Minecraft client/server has been tested.

## Diagnostic client capture

```powershell
.\gradlew.bat -p platform-adapters/minecraft '-Ptarget=fabric-26.3' -Pdiagnostics runClient
```

The development client runs only under this workspace's `run/fabric-26.3`.
For a controlled menu/lifecycle smoke test, replace `-Pdiagnostics` with
`-PsmokeClient=200`. It logs 200 client ticks and calls the game's own `stop()`
method on the client thread. This option is off by default and does not enter a
world or establish that any chunk geometry was captured.
With the opt-in flag, the tick hook samples the loaded section around the player
every 100 ticks, including its loaded neighbor halo. It creates an immutable
snapshot on the client thread and sends it to the worker pipeline. Results are
revision checked, counted in the log and released. This path deliberately does
not upload or display those meshes, so its success is world-worker integration
evidence, not Vulkan-in-Minecraft rendering evidence.

Only opaque cube states are captured. Model meshes, fluids, cutout/translucent
blocks and skylight are outside this diagnostic path. Missing neighbor chunks
defer capture; sampling never requests chunk loading. The legacy hook currently
tracks lifecycle only and does not capture world sections.

## Real-world and model smoke

```powershell
.\gradlew.bat :vulkan-backend:jar :shader-pipeline:jar
.\gradlew.bat -p platform-adapters/minecraft '-Ptarget=fabric-26.3' -PworldSmoke -PmodelSmoke verifyWorldSmoke --no-configuration-cache
```

This development-only driver creates a uniquely named flat test world, places
five real blocks on the integrated-server thread and waits for matching client
packet states. It verifies live capture/revision/unload, then captures actual
baked quads and copies the game's block atlas and lightmap before dispatching
owned data to a worker for Vulkan upload/draw/readback. It saves, disconnects and
stops normally, writing the receipt to `run/fabric-26.3` and images under
`run/fabric-26.3/overhaul-model-smoke`.

Omit `-PmodelSmoke` for the world/worker-only test. Normal target builds use the
`nosmoke` factory; world/model builds select separate source variants and output
directories. Development world creation and native probe classes are excluded
from normal adapter jars. The model probe adds COPY_SRC usage to its development
lightmap solely for readback. It leaves the game's onscreen renderer active.

The current baked-model capture preserves the game's tessellation, tint, AO and
light on the owner thread. Packing/native draw run on a bounded worker. Replacing
the original tessellator, fluids, special block entities and continuous scene
updates remain required work. Vertex UV/light coordinates use UNORM16, normals
use SNORM8 and cutoff uses UNORM8; full scene parity is not established.

## Experimental Fabric 26.3 backend

```powershell
.\gradlew.bat :vulkan-backend:assemble :shader-pipeline:jar
.\gradlew.bat -p platform-adapters/minecraft '-Ptarget=fabric-26.3' -PoverhaulBackend build backendNativeTest --no-configuration-cache
.\gradlew.bat -p platform-adapters/minecraft '-Ptarget=fabric-26.3' -PoverhaulBackend '-PsmokeClient=200' verifyBackendSmoke --no-configuration-cache
.\gradlew.bat -p platform-adapters/minecraft '-Ptarget=fabric-26.3' -PoverhaulBackend -PworldSmoke verifyWorldSmoke verifyBackendSmoke --no-configuration-cache
```

The factory installs Overhaul before window creation, reuses the game's shared
shader/validation frontend, and implements native Vulkan execution itself. The
selection contains only Overhaul. Buffers, textures, views, samplers, pipelines,
descriptors and command pins have explicit owners. Native tests use Minecraft's
matching LWJGL 3.4.3 runtime and enable its debug allocator.

The world variant waits for an actual world image before normal save/disconnect.
PNG and JSON evidence is written under `run/fabric-26.3/overhaul-backend-smoke`.
These are GPU presentation-source captures with the surface's Y flip applied,
not desktop screenshots. The backend build has separate `-backend` output and
development dependencies; it is not a self-contained release jar. Normal artifacts
exclude these classes. Run all adapter Gradle commands serially, including native
tests: changing variants can delete a running client's classes through Gradle's
stale-output cleanup.

The bridge batches passes/uploads on three fenced slots. Pass captures seal at
frontend close; attachment views, descriptors and pins retire after their actual
submission. Buffer writes use staged GPU copies, and CPU mapping synchronizes
overlapping ranges including non-coherent atom boundaries. Three transient arenas
share a 128 MiB budget. Batches flush at 256 operations or a 64 MiB staging
threshold; a larger single image upload submits alone.

Fence creation captures a marker without splitting the batch. A zero-time poll
of unsubmitted work returns pending; a nonzero wait flushes it first and applies
the timeout to its native fence wait. Readback callbacks run after completion,
outside native retirement, and may map or enqueue further work. Timestamp results
are published by submitted generation. Private attachment views track their own
fence independently of later image-only blits.

GPU presentation is asynchronous and uses three acquire semaphores. Synchronous
commands remain available for explicit verification/readback; ordinary game
passes use the batch queue. Buffers without map usage use device-preferred VMA
allocations and snapshot initial data into an ordered staging copy. CPU-mappable
buffers retain host access; transient GPU arenas remain host-visible.

The device caches up to 16 idle descriptor pools under an 8,192-descriptor budget,
and 64 idle attachment bundles. Accepted work retains an exclusive lease until its
fence; a new lease allocates fresh sets and bindings. Texture/view logical close invalidates
matching framebuffers immediately or discards their active leases on retirement.
Clear values and render areas remain per-pass; load/clear semantics, attachment
order/holes, formats and subresources belong to the cache key. Smoke receipts
record pool allocation/reuse counters and live buffer bytes at teardown.
Long sessions, every render domain, complete shader packs, reload/dimension/mod
parity, other loader/version renderers and controlled performance remain open.
Instance divisors above one are unavailable; DrawIndex-sensitive direct multi-draw
fallback is rejected explicitly rather than assigned incorrect semantics.

## Server boundary

Common server mixins attach a cooperative work queue to the server's own thread.
The queue has no jobs unless a future integration explicitly enqueues work;
vanilla entity ticking, AI and world generation remain authoritative.
`EntrypointIsolationTest` loads each selected entrypoint with client, shader and
native packages forbidden. `InjectionTargetsTest` checks the strict callback
descriptors against the resolved Minecraft artifact without initializing it.

`runServer --args=--help` validates loader startup and mod discovery only. It
does not create a world or prove that the server tick callbacks executed.
