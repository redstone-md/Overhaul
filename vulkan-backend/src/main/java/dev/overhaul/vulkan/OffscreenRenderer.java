package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;

/** Indexed-indirect validation renderer. It never creates or calls an OpenGL context. */
public final class OffscreenRenderer implements AutoCloseable {
    private final VulkanContext context;
    private final GpuAllocator allocator;
    private final ImmediateCommands commands;
    private final Set<GpuMesh> meshes = Collections.newSetFromMap(new IdentityHashMap<>());
    private ColorTarget target;
    private CameraUniform camera;
    private IndexedPipeline pipeline;
    private GpuBuffer readback;
    private boolean closed;

    public OffscreenRenderer(VulkanContext context, int width, int height) {
        this.context = context;
        allocator = new GpuAllocator(context);
        ImmediateCommands lane = null;
        try {
            lane = new ImmediateCommands(context);
            commands = lane;
            target = new ColorTarget(context, width, height);
            camera = new CameraUniform(context, allocator);
            pipeline = new IndexedPipeline(context, target.renderPass, camera.layout, width, height);
            readback = allocator.hostBuffer((long) width * height * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        } catch (RuntimeException | Error failure) {
            if (readback != null) readback.close();
            if (pipeline != null) pipeline.close();
            if (camera != null) camera.close();
            if (target != null) target.close();
            if (lane != null) lane.close();
            allocator.close();
            throw failure;
        }
    }

    public GpuMesh upload(ByteBuffer vertices, ByteBuffer indices, int indexCount) {
        requireOpen();
        GpuMesh mesh = new GpuMesh(context, allocator, commands, vertices, indices, indexCount, meshes::remove);
        meshes.add(mesh);
        return mesh;
    }

    /** Returns tightly packed RGBA8 pixels; synchronized readback is intended for verification. */
    public ByteBuffer render(GpuMesh mesh, float[] columnMajorTransform) {
        requireOpen();
        if (!meshes.contains(mesh)) throw new IllegalArgumentException("mesh belongs to another renderer");
        camera.write(columnMajorTransform);
        commands.submit(command -> {
            try (MemoryStack stack = VulkanStack.push()) {
                IndexedPass.begin(command, target, camera, pipeline);
                vkCmdBindVertexBuffers(command, 0, stack.longs(mesh.vertices.handle(), mesh.instances.handle()), stack.longs(0, 0));
                vkCmdBindIndexBuffer(command, mesh.indices.handle(), 0, VK_INDEX_TYPE_UINT32);
                vkCmdDrawIndexedIndirect(command, mesh.indirect.handle(), 0, 1, 20);
                vkCmdEndRenderPass(command);
                target.copyTo(command, readback);
            }
        });
        return readback.read();
    }

    public byte[] pipelineCacheData() { requireOpen(); return pipeline.cacheData(); }
    public int width() { requireOpen(); return target.width; }
    public int height() { requireOpen(); return target.height; }
    int activeMeshCount() { requireOpen(); return meshes.size(); }

    private void requireOpen() {
        context.requireOwner();
        if (closed) throw new IllegalStateException("renderer closed");
    }

    @Override public void close() {
        if (closed) return;
        context.requireOwner();
        context.waitIdle();
        for (GpuMesh mesh : meshes.toArray(new GpuMesh[0])) mesh.close();
        meshes.clear();
        readback.close();
        pipeline.close();
        camera.close();
        target.close();
        commands.close();
        allocator.close();
        closed = true;
    }
}
