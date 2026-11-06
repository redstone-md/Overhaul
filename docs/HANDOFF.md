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
`-Ptarget=forge-1.20.1 -PblazeBackend compileJava` checks the 1.20.1 sources (`blaze120`).

## Source layout

| Source set | Holds |
| --- | --- |
| `blazeBackend` | Version-neutral renderer: section batches, buffer pool, mesh state, DH depth, mixins on Blaze3D |
| `blaze121` / `blaze120` | Version-specific mixins and the pack frame (`blaze/shader`: uniforms, shadows, frame graph) |

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

## CPU hot spots in the latest profile

The percentages are self time.

- `BlazeSectionBatch.append` and `run`, 10–15%: cache misses on mesh fields per section.
- `BlazePackUniforms.fill`, about 4% plus string hashing: uniforms are found by name.
- `PackFrameGraph.create` / `VulkanRenderGraph`, about 6%: the graph is rebuilt every frame.

Fixed since the previous profile: the buffer pool picks the best-fit slice through a size
index, the GL buffer maps are unboxed, and each shadow layer keeps its own caster list.

## Open items

- BSL flicker while walking, reported by a tester. Not checked again after the latest fixes.
- A flash with Complementary.
- Sky mapping.
- A crash in OpenAL.
- Hi-Z occlusion on the GPU.
- Running the remaining versions and loaders, beyond NeoForge 1.21.1 and a compile of Forge 1.20.1.
- About 0.3 ms of GPU time at the horizon still stands between 1.85x and the 2x target.
