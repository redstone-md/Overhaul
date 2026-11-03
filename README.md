# overhaul

A modular Vulkan rendering and world-processing overhaul for Minecraft.

Development is underway. See [roadmap.md](roadmap.md) for the complete requested
scope and acceptance gates. This repository must not be described as a working
replacement for Minecraft's renderer until loader integration and gameplay
validation pass.

The engine separates immutable world processing, explicit native resource
ownership, shader translation and loader-specific adapters. Legacy and modern
runtime dependencies are isolated rather than bundled into one incompatible jar.
