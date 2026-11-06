package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.GpuBuffer;

/**
 * The device buffer last resolved for one Distant Horizons vertex buffer, kept on that buffer so each frame reads a
 * field instead of taking the buffer table's lock and hashing its name. Valid while the name and the table's
 * generation match.
 */
public final class BlazeDhDeviceSlot {
    /** Implemented on DH's vertex buffer by a mixin; absent when {@code dhDeviceCache} is off. */
    public interface Holder {
        BlazeDhDeviceSlot overhaul$deviceSlot();
    }

    private int name, generation;
    private BlazeGlBuffers.Placed device;
    private BlazeSectionRun run;

    BlazeGlBuffers.Placed resolve(BlazeRenderer renderer, BlazeGlBuffers buffers, int name, int generation, boolean shared) {
        if (device == null || this.name != name || this.generation != generation) {
            device = buffers.placed(renderer, name, shared);
            this.name = name;
            this.generation = generation;
        }
        return device;
    }

    /** The shared draw key of the resolved buffer with these indices, found once instead of interned every frame. */
    BlazeSectionRun run(GpuBuffer indices, int indexBytes) {
        GpuBuffer vertices = device.buffer();
        if (run == null || run.vertices != vertices || run.indices != indices || run.indexBytes != indexBytes) {
            run = BlazeSectionRun.of(vertices, 0, indices, indexBytes, true);
        }
        return run;
    }
}
