# Validation checkpoint: 2026-11-03

Local platform: Windows, Temurin JDK 25.0.3, NVIDIA GeForce RTX 5070.
The worktree began empty; no existing launcher instance or save was modified.

## Engine

The combined command passed:

```powershell
.\gradlew.bat build :integration-tests:nativeTest :vulkan-backend:vulkanSmoke --args="--present --output build/verification/triangle.ppm"
```

| Suite | Tests | Failures | Skipped |
| --- | ---: | ---: | ---: |
| core-common | 22 | 0 | 0 |
| shader-pipeline | 16 | 0 | 0 |
| platform-adapters | 3 | 0 | 0 |
| vulkan-backend | 7 | 0 | 0 |
| native integration | 8 | 0 | 0 |

Source files are below 500 lines, and the engine module import-boundary gate passes.

The native smoke verified exact 65,536-byte staging/device/readback transfer;
indexed indirect depth-tested drawing; 11 transfers across three fenced slots;
multi-draw and single-draw fallback with three instances; sparse visibility changes
and restoration without geometry copies; and 62 geometry presentations across
resize using acquire/present semaphores. The reference triangle covered 5,202
pixels with CRC32 `835141031` on this device.

The native integration tests cover worker-built chunk geometry after source slabs
are overwritten, native graph image barriers, and unchanged pinned Iris example
basic/textured programs. Pixel checks exercise uniform updates, texture rows,
OpenGL-to-Vulkan clip conversion and blend state. PNGs were visually inspected at:

- `integration-tests/build/verification/chunk-vulkan.png`
- `integration-tests/build/verification/iris-basic.png`
- `integration-tests/build/verification/iris-basic-blended.png`
- `integration-tests/build/verification/iris-textured.png`
- `integration-tests/build/verification/iris-textured-toprow.png`

Khronos validation layers were unavailable. These results are native execution
checks without validation layers, not a claim of cross-device correctness.

The isolated `:vulkan-backend:sdlVulkanSmoke` also passes real SDL3/Vulkan
readback, 52 instanced presentations and resize on LWJGL 3.4.3+4, with no GLFW
class on that runtime. Borrowed-window ownership and rejection of non-Vulkan SDL
windows are checked. The separate `-sdl34.jar` contains the provider, not its smoke
launcher or native libraries.

## Loader packaging

The serial runner passed all 11 pinned targets with 22 tests, without source drift:

| Loader | Minecraft versions |
| --- | --- |
| Fabric | 1.16.5, 1.20.1, 1.21.1, 26.3 |
| Forge | 1.12.2, 1.16.5, 1.20.1, 1.21.1, 26.3 |
| NeoForge | 1.21.1, 26.3 |

The [retained receipts](evidence/loader-targets-2026-11-03.json) contain per-jar
SHA-256 values and a shared raw-source digest. `verify-targets.ps1 -CheckReceipts`
subsequently confirmed those sources and every existing jar still match. These
pins represent the requested eras; they do not cover every intervening release.
The dedicated-server entrypoint tests forbid loading client/native packages.
Legacy CoreMod fixtures are transformed and executed in tests; a real Java 8
Minecraft 1.12.2 runtime has not been exercised.

## Actual client lifecycle

This command passed with exit code zero:

```powershell
.\gradlew.bat -p platform-adapters/minecraft '-Ptarget=fabric-26.3' '-PsmokeClient=200' runClient --console=plain --no-configuration-cache
```

Fabric loaded the adapter, initialized the actual game window, textures and sound,
ran the diagnostic hook for 200 ticks, then invoked the game's own `stop()` method
and shut down normally. An earlier launch exposed an overly broad Mixin namespace;
the package was narrowed and a jar invariant was added before the successful run.

That menu test reported its vanilla OpenGL backend and did not enter a world.
The subsequent test below establishes actual world capture and native asset
rendering. Desktop automation remained unavailable, so no screenshot of the
game window or full gameplay parity check has been claimed.

## Live world and native baked models

The latest-code command passed with exit code zero:

```powershell
.\gradlew.bat :vulkan-backend:jar :shader-pipeline:jar
.\gradlew.bat -p platform-adapters/minecraft '-Ptarget=fabric-26.3' -PworldSmoke -PmodelSmoke verifyWorldSmoke --console=plain --no-configuration-cache
```

The integrated server created a new world inside the isolated development run
directory, placed a slab, glass, a flower, stairs and leaves on its server thread,
and the client confirmed all five packet states before capture. Revision two
superseded revision one and was accepted with nonempty geometry.

The actual game tessellator then supplied 283 baked quads, including 19 with
non-integer geometry, nine cutout quads and five translucent quads. GPU callbacks
copied the real 2048x2048 block atlas and 16x16 lightmap into owned bytes. A bounded
worker packed and uploaded 1,698 indices, released and overwrote its source slab,
then rendered the same pixels twice through Overhaul's Vulkan model path. The
512x512 image had 132,868 non-background pixels and was visually inspected:

![Actual Minecraft model assets rendered by Overhaul](evidence/world-model-2026-11-03.png)

The [receipt and artifact hashes](evidence/world-model-2026-11-03.json) retain the
source commit, native jar hashes, image hash, world identity, exact fixture states
and zero-lease teardown result. The game saved, disconnected and stopped normally.
The game window remained on vanilla OpenGL: the Vulkan result is an offscreen
draw of its captured assets, not a replacement for its onscreen renderer.

Additional native tests verify partial mapped writes, preserved opaque/cutout
indices, correct translucent blend ordering and no upload for a stationary camera.
The mixed-layer fixture uploads 48 translucent index bytes on movement instead of
the full 96-byte index buffer. This is a measured transfer count for that fixture,
not an FPS claim.

## Remaining evidence

Remote CI has not executed. Full onscreen Minecraft Vulkan replacement, complete shader
packs, actual world-generation/loading/lighting/tick changes, all render domains,
remaining versions, ecosystem parity and controlled performance comparisons are
still required. See `IMPLEMENTATION_STATUS.md` and `roadmap.md`.
