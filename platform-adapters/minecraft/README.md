# Minecraft adapter targets

These are lifecycle and world-worker adapters. They do not replace Minecraft's
renderer. No Vulkan, shaderc, GLFW or VMA code is bundled into the adapter jars.
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

## Server boundary

Common server mixins attach a cooperative work queue to the server's own thread.
The queue has no jobs unless a future integration explicitly enqueues work;
vanilla entity ticking, AI and world generation remain authoritative.
`EntrypointIsolationTest` loads each selected entrypoint with client, shader and
native packages forbidden. `InjectionTargetsTest` checks the strict callback
descriptors against the resolved Minecraft artifact without initializing it.

`runServer --args=--help` validates loader startup and mod discovery only. It
does not create a world or prove that the server tick callbacks executed.
