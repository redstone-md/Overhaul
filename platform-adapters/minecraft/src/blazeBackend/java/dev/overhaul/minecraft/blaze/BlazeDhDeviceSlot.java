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
    private GpuBuffer device;

    GpuBuffer resolve(BlazeRenderer renderer, BlazeGlBuffers buffers, int name, int generation) {
        if (device == null || this.name != name || this.generation != generation) {
            device = buffers.device(renderer, name);
            this.name = name;
            this.generation = generation;
        }
        return device;
    }
}
