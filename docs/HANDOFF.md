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
| Forge 1.21.1, Fabric 1.20.1, Forge 1.20.1 | Compile only, no test instance yet |

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
| Horizon view, Overhaul | about 4.16 ms | 4213 sections in about 1250 indirect draws, 40 passes; 1.85x |

The fairest comparison reads the F3 counter in both, runs interleaved, two runs each
(`fpsbench.ps1`, see below). Latest numbers:

| View | Overhaul | Iris with Sodium | Ratio |
| --- | --- | --- | --- |
| Horizon | about 259 fps | about 135 fps | 1.92x |
| Looking down | about 244 to 251 fps | about 132 fps | 1.85x to 1.90x |

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

- `BlazeSectionBatch.append`, about 6%: cache misses on mesh fields per section.
- `Int2ObjectOpenHashMap.get` in `BlazeGlBuffers.device`, about 3.5%: two lookups per Distant Horizons container.
- `BlazePackUniforms.fill`, about 4% plus string hashing: uniforms are found by name.
- `PackFrameGraph.create` / `VulkanRenderGraph`, about 6%: the graph is rebuilt every frame.
  A timing probe puts it at about 27 µs for each of five builds, about 0.14 ms per frame.

Fixed since the previous profile: the buffer pool picks the best fit by binary search over a
sorted array, section runs are hashed by all four keys (the chain by vertex buffer was
quadratic within a page, `run` fell from 11% to 3.5%), F3 counts rendered sections from the
layer scan instead of walking every section, the GL buffer maps are unboxed, and each shadow
layer keeps its own caster list and draws through the tight frustum.

The remaining F3 cost is vanilla text drawing (`CodepointMap`, `Font`) and Distant Horizons'
own debug string.

## Open items

- BSL flicker while walking, reported by a tester. Not checked again after the latest fixes.
- A flash with Complementary.
- Sky mapping.
- A crash in OpenAL.
- Hi-Z occlusion on the GPU.
- Runtime checks of Forge 1.21.1, Fabric 1.20.1 and Forge 1.20.1. All three compile.
- With F3 open, about 0.1 to 0.2 ms of GPU time stands between 1.9x and the 2x target. Without F3 it is past 2x.
