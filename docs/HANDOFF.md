# Handoff: Blaze backend with shader packs

The state of the NeoForge 1.21.1 Blaze backend, which draws the game and Iris-format
shader packs (BSL, Complementary) on Overhaul's own Vulkan renderer. It does not use
Iris, Sodium or a GL-to-Vulkan layer. `IMPLEMENTATION_STATUS.md` covers the wider
project. This file covers what a new person needs to carry on with this path.

## Build

Kill any running `javaw` first: Gradle and the game share the mod jar.

```
JAVA_HOME=<JDK 22>
gradlew -p platform-adapters/minecraft -Ptarget=neoforge-1.21.1 -PblazeBackend \
  -PloaderVersionOverride=21.1.265 processResources compileJava jar -x compileTestJava -x test
```

The jar is
`platform-adapters/minecraft/build/neoforge-1.21.1-blaze/libs/overhaul-neoforge-1.21.1-blaze-client-windows-0.1.0-SNAPSHOT.jar`.
Every Blaze target compiles: `neoforge-1.21.1`, `forge-1.21.1`, `fabric-1.21.1`,
`fabric-1.20.1` and `forge-1.20.1`. For Fabric, run `-Ptarget=fabric-1.21.1 -PblazeBackend assemble`.
The remapped jar is in `build/fabric-1.21.1-blaze/libs/` and needs Fabric Loader 0.20.1 or later.

| Target | Checked at runtime |
| --- | --- |
| NeoForge 1.21.1 | Yes, with Complementary, BSL and Distant Horizons |
| Fabric 1.21.1 | Yes, vanilla and Complementary, in the Prism instance `Overhaul Fabric Test` |
| Forge 1.21.1 (52.1.21) | Yes, vanilla and Complementary, in `Overhaul Forge Test` |
| Fabric 1.20.1 | Yes, vanilla and Complementary, in `Overhaul Fabric 120 Test` |
| Forge 1.20.1 (47.4.10) | Yes, vanilla and Complementary, in `Overhaul Forge 120 Test` |

The 1.20.1 tier has mesh uploads, terrain batching, shader packs (shadows, hand, bobbing)
the settings screen, section-scan reuse and entity occlusion. It has no Distant Horizons hooks. On Forge 52.1.21, a world copied from a Fabric instance
crashes the server with `Can not retrieve LootModifierManager until resources have loaded once`,
with or without Overhaul. A world created in that instance works.

## Source layout

| Source set | Holds |
| --- | --- |
| `blazeBackend` | Version-neutral renderer: section batches, buffer pool, mesh state, DH depth, mixins on Blaze3D |
| `blaze121` / `blaze120` | Version-specific mixins and the pack frame (`blaze/shader`: uniforms, shadows, frame graph) |
| `blaze121Neoforge` / `blaze121Forge` / `blaze121Fabric` | `BlazeLoader121`, the few calls that differ by loader, and loader-only mixins such as the NeoForge early window |

The build adds a `<tier><Loader>` source set when its folder exists. Loader-only mixins go under
`loaderMixins` in `gradle/blaze-backend.gradle`. Forge runs the mixin annotation processor, so
`@At` INVOKE targets need full descriptors and one injector may not name two methods with the same name.
A mixin class cannot be called from other code; put shared logic in a plain class.

Each optimisation is a `BlazeOptimization` key. You can switch one off in
`config/overhaul-optimizations.properties` (`key=false`) or with
`-Doverhaul.opt.<key>=false`. `BlazeMixinPlugin` skips the mixins that belong to a
key that is off.

## Testing in game without disturbing the desktop

- Launch the Prism instance with `--launch "<instance>" --world "<world>"`. Send input as
  window messages only, never by focusing the game.
- `-Doverhaul.dev.noGrab=true` stops the game grabbing the cursor. Use it whenever someone
  else is using the same desktop.
- `-Doverhaul.blaze.stats=true` logs a `Blaze stats per frame` line with the frame time,
  the batched sections, the indirect draws and the sections hidden by occlusion.
- For a CPU profile, record JFR with `jcmd <pid> JFR.start settings=profile duration=20s`.

## Measured, Complementary with Distant Horizons on an RTX 5070

| Scene | Frame time | Notes |
| --- | --- | --- |
| Spectator inside a hill, 0 110 0 | 7.0 ms to about 5.9 ms over the CPU commits | About 8050 sections in about 2455 indirect draws |
| Horizon view, Iris with Sodium | 7.7 ms | Baseline for the 2x target, so the target is 3.85 ms |
| Horizon view, Overhaul | about 3.6 ms (F3 counter) | 4213 sections in about 1250 indirect draws, 40 passes; 2.05x |

The fairest comparison reads the F3 counter in both, runs interleaved, two runs each
(`fpsbench.ps1`, see below). Latest numbers:

| View | Overhaul | Iris with Sodium | Ratio |
| --- | --- | --- | --- |
| Horizon | about 274 fps | about 130 fps | 2.11x |
| Looking down | about 256 fps | about 127 fps | 2.02x |

These are with F3 open, Complementary and Distant Horizons, run back to back on 2026-11-06
(Iris in `Overhaul Baseline`, then Overhaul). One looking-down sample of five dropped to 232;
without it that view is 262, 2.06x. An earlier
pair of runs gave 277 and 135 at the horizon; GPU clock modes move both sides between launches.

GPU time is bimodal from one launch to the next: about 3.3 to 3.4 ms or about 3.65 to
3.7 ms. Only the pixel-bound phases move (opaque, deferred, composite), so it looks like
GPU clocks. With F3 open the run always lands in the slow mode: the extra CPU work leaves
the GPU idle gaps and it clocks down. Without F3 Overhaul is about 2.2x.

At the horizon the GPU is the limit now: about 4.0 ms of GPU work per frame and
0.15 to 0.25 ms idle between frames. GPU time per phase:

| Phase | GPU ms |
| --- | --- |
| Shadow | 0.69 |
| Distant Horizons terrain | 1.0 |
| Opaque terrain | 0.75 |
| Deferred | 0.44 |
| Composite | 0.83 |
| Translucent | 0.08 |
| GUI | 0.07 |

`-Doverhaul.blaze.gpuStats=true` logs a `Blaze GPU ms per frame` line every 600
frames. Each mark ends the open render pass, so frames run slightly slower while it is on.

Experiments that did not help, all reverted:

- Submitting early after the shadow and deferred phases: 4.28 ms, the extra passes cost more.
- Drawing Distant Horizons containers far to near: the distant phase went from 1.01 to 1.35 ms.
  They are already near to far, and the pass is bound by fragment overdraw.
- Front-to-back order for vanilla opaque sections: no GPU change, and indirect draws rose from 1250 to 2020.
- Sorting Distant Horizons containers by distance: 0.93 against 0.94 ms, noise. Their quadtree order is already close.
- Masking Distant Horizons fragments behind vanilla terrain. Complementary discards them itself past 0.4 to
  0.6 of the far plane; an upper-bound probe saved about 0.05 ms, so it was dropped.
- Batching image barriers, and skipping near Distant Horizons containers: no measurable change.

## CPU hot spots in the latest profile

The percentages are self time.

- `BlazeSectionBatch.run`, `write` and `end`, about 10 to 15%: cache misses chasing each
  section's mesh and then its draw run.
- `PalettedContainer.get`, about 2 to 5%: vanilla block lookups.
- `BlazePackShadows.tighten`, about 3%: kept casters tested against the light frustum.
- `SoftwareDepthBuffer.behind`, about 3%: the CPU occlusion test.

Fixed since the previous profile: the buffer pool keeps free ranges in sorted long arrays
instead of a TreeMap (`poolRanges`), each uniform block remembers which source answers each
name (`uniformPlans`), kept shadow casters are tested a column at a time (`shadowColumns`),
pass graph shapes are validated once (`packGraphShapes`), F3 text is gathered at 10 Hz,
and sections find their draw run through a key their mesh shares. Distant Horizons vertex
buffers keep their device buffer (`dhDeviceCache`), planned uniforms are found by identity
through interned names, pack draws are looked up without building key strings, programs hash
by their binaries, and Distant Horizons buffer state is read under optimistic stamps instead of
two read locks per container.

Pack blits cache their format features, free descriptor sets are a primitive stack, and repeated
draw inputs reuse the last pipeline without building its key.

The render thread is no longer the limit. In a 20 s JFR at the horizon it had about 185 Java
samples against 92 native ones, and 62% of the native samples are `vkWaitForFences` in
`FrameRing.awaitSlot`, the CPU waiting for the GPU. More FPS has to come from GPU time.

The remaining F3 cost is vanilla text drawing (`CodepointMap`, `Font`) and Distant Horizons'
own debug string.

## Open items

- BSL flicker while walking, reported by a tester. Ten walk frames on 2026-11-06 were steady; not reproduced.
- A flash with Complementary. Not reproduced in the same walk check.
- Sky mapping matches Iris at noon, sunset, night and sunrise for BSL and Complementary.
- Distant Horizons LODs drawn through a pack had block and sky light swapped and stayed lit at
  night. Fixed by reading block light from the high nibble.
- A crash in OpenAL on one tester's machine: `OpenAL.dll` 1.23.1, `0xc0000409`, outside our
  code. Waiting on that machine's log.
- GPU occlusion (`gpuOcclusion`) is in but off by default: a compute pass tests each section against the newest
  queued depth tiles and zeroes its instance count. With Complementary and F3 it measured on par with the CPU test
  (horizon about 238 fps both ways, looking down 218 on vs 228 off), so terrain stays on the CPU readback test.
- With F3 open the 2x target is reached (2.11x horizon, 2.02x looking down, back to back). The run is GPU-bound, so GPU clock
  modes still swing single runs by about 5 to 10%.
