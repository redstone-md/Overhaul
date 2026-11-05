package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.GpuBuffer;

/** Buffer storage a mesh binds. A snapshot pins it for its fence; the mesh decides how its own hold ends. */
interface BlazeGeometryRange extends AutoCloseable {
    GpuBuffer buffer();
    int offset();
    BlazeBufferPool.Pin retain();
    /** Ends the mesh's hold once the storage is replaced; queued work that still needs it holds its own pin. */
    void retire(BlazeQueue queue);
    @Override void close();
}
