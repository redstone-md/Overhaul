package dev.overhaul.vulkan;

/** A reusable device-local batch. retire() defers native release until the last referencing fence. */
public final class GpuBatch implements AutoCloseable {
    private final AsyncRenderer owner;
    final GpuBuffer vertices;
    final GpuBuffer indices;
    final GpuBuffer instances;
    final GpuBuffer indirect;
    final int commandCount;
    final int[] firstInstances;
    final boolean multiDraw;
    final IndirectCommandState commands;
    FrameRing.Submission lastUse;
    boolean retired;

    GpuBatch(AsyncRenderer owner, GpuAllocator allocator, MeshBatch batch, boolean multiDraw) {
        this.owner = owner;
        this.multiDraw = multiDraw;
        commandCount = batch.commandCount;
        firstInstances = batch.firstInstances.clone();
        commands = new IndirectCommandState(batch);
        GpuBuffer vertex = null, index = null, instance = null, command = null;
        try {
            vertex = allocator.deviceBuffer(batch.vertexBytes, org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT
                | org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            index = allocator.deviceBuffer(batch.indexBytes, org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT
                | org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            instance = allocator.deviceBuffer(batch.instanceBytes, org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT
                | org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            command = allocator.deviceBuffer((long) commandCount * 20, org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
                | org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT | org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        } catch (RuntimeException | Error failure) {
            if (command != null) command.close();
            if (instance != null) instance.close();
            if (index != null) index.close();
            if (vertex != null) vertex.close();
            commands.close();
            throw failure;
        }
        vertices = vertex; indices = index; instances = instance; indirect = command;
    }

    void destroy() {
        indirect.close(); instances.close(); indices.close(); vertices.close();
        commands.close();
    }

    public int drawCount() { return commandCount; }
    public boolean usesMultiDraw() { return multiDraw; }
    public void retire() { owner.release(this, true); }
    @Override public void close() { owner.release(this, false); }
}
