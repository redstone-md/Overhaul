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
| core-common | 19 | 0 | 0 |
| shader-pipeline | 16 | 0 | 0 |
| platform-adapters | 3 | 0 | 0 |
| vulkan-backend | 4 | 0 | 0 |
| native integration | 4 | 0 | 0 |

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

The game reported its vanilla OpenGL backend. No world was entered. Desktop
automation failed before initialization with `CryptUnprotectData failed:
2148073483`, so no game screenshot or visual/gameplay parity check was available.
The run used only this repository's isolated development directory.

## Remaining evidence

Remote CI has not executed. Full Minecraft Vulkan replacement, complete shader
packs, actual world-generation/loading/lighting/tick changes, all render domains,
remaining versions, ecosystem parity and controlled performance comparisons are
still required. See `IMPLEMENTATION_STATUS.md` and `roadmap.md`.
