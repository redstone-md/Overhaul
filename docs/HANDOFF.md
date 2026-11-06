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
| Spectator inside a hill, 0 110 0 | 7.0 ms to about 5.9 ms over the latest CPU commits | About 8050 sections in about 2455 indirect draws |
| Surface, occlusion on | 7.61 ms | 3148 sections, 971 hidden |
| Surface, occlusion off | 7.85 ms | 3898 sections |

Each surface run gave only five samples, so the 3% occlusion gain is near noise.
There is no controlled comparison against Iris with Sodium, so the 2x target is
unproven.

## CPU hot spots in the latest profile

The percentages are self time.

- `BlazeSectionBatch.append` and `run`, 10–15%: mesh field reads and the run lookup per section.
- `BlazePackShadows.collect` / `cast`, about 13%: the shadow pass is about a third of the
  frame. It rebuilds its batch every frame even when no caster changed.
- `BlazePackUniforms.fill`, about 4% plus HashMap lookups: uniforms are found by name.
- `PackFrameGraph.create` / `VulkanRenderGraph`, about 6%: the graph is rebuilt every frame.
- `BlazeBufferPool` page acquire, about 4%: a first-fit TreeMap scan.

## Open items

- BSL flicker while walking, reported by a tester. Not checked again after the latest fixes.
- A flash with Complementary.
- Sky mapping.
- A crash in OpenAL.
- Hi-Z occlusion on the GPU.
- Running the remaining versions and loaders, beyond NeoForge 1.21.1 and a compile of Forge 1.20.1.
- A controlled FPS comparison against Iris with Sodium, to prove or disprove the 2x target.
