# Controlled OpenGL / Overhaul Vulkan comparison

No retained result currently establishes an FPS or frame-time advantage over
OpenGL. Geometry byte counts, indirect draw counts and section-query helper
timings are separate evidence. This is the required comparison contract.

## Presentation prerequisite

The current `OverhaulSurface` advertises only FIFO and rejects other modes;
`SwapchainPresenter` also hardcodes `VK_PRESENT_MODE_FIFO_KHR`. Disabling the
game's VSync option therefore cannot produce uncapped Overhaul presentation.
Implement capability-based mode selection and faithful reconfiguration first.
Record the actual configured mode, rather than inferring it from an option.
Compare uncapped throughput only when both backends actually use IMMEDIATE.
An explicitly refresh-capped FIFO experiment is a different configuration.

## Shared measurement boundary

Pinned 26.3 `Minecraft.renderFrame(boolean)` calls `GpuSurface.present()` after
command submission and only for an acquired image. A benchmark-only wrapper at
this common call can timestamp successful returns on both backends. Consecutive
timestamps include frame limiting and acquisition/presentation backpressure.
They measure application frame intervals, not isolated GPU execution time or
physical display latency. The game's `frameTimeNs` assignment occurs before
submission/presentation and cannot substitute for this measurement.

Observe the common surface configuration call as well, retaining actual extent
and present mode. Keep collection instance-owned and bounded; write raw timing
data after sampling. Readbacks, fixture mutations and resource reloads belong
outside sampling. A failed/reconfigured acquisition invalidates the sample
rather than silently discarding its slow intervals.

## Paired runs and acceptance

- Use the same pinned game, Java, device/driver, resolution, camera, world input,
  resource packs and gameplay settings. Verify initial world-copy hashes and
  preserve source media and unrelated saves.
- Disable VSync and the game's FPS limiter consistently, then verify actual
  configuration. Reject differing resolution or present mode.
- Use at least six interleaved fresh-launch pairs, alternating which backend
  runs first. Finish world loading, meshing and shader warmup before sampling.
- Establish separate fixed scenarios for terrain, models, particles and weather.
  Verify scene state and rendered content before and after each sample; retain
  the actual backend identity and enabled optimization configuration.
- Sample at least 30 seconds and 1,800 frames per run, with an explicit timeout.
  Keep every interval after warmup, including hitches. Store raw nanoseconds,
  median/p95/p99 intervals and aggregate throughput. Define any reported 1% low
  explicitly; do not interchange it with a percentile.
- Retain per-pair ratios and individual results, source/artifact hashes, JVM
  arguments, device/driver identity and presentation settings. State variance
  and configuration limits. CPU/allocation/VRAM claims need their own profiling
  evidence; they cannot be inferred from frame intervals or vertex payloads.

Model correctness and live teardown verification precede this benchmark. Other
devices, drivers, loaders and versions remain separate acceptance configurations.
