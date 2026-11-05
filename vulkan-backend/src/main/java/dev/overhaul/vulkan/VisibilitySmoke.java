package dev.overhaul.vulkan;

import dev.overhaul.core.culling.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.lwjgl.vulkan.VK10.*;

/** Proves CPU visibility changes real GPU indirect commands and pixels without geometry replacement. */
final class VisibilitySmoke {
    private VisibilitySmoke() { }

    static void verify(VulkanContext context) {
        verify(context, AsyncRenderer.DrawMode.AUTOMATIC);
        verify(context, AsyncRenderer.DrawMode.SINGLE_DRAW);
        verifyRegionBoundary(context);
    }

    private static void verify(VulkanContext context, AsyncRenderer.DrawMode mode) {
        ByteBuffer vertices = ByteBuffer.allocate(48).order(ByteOrder.nativeOrder());
        vertex(vertices, -.15f, -.15f); vertex(vertices, .15f, -.15f); vertex(vertices, 0, .15f);
        vertices.flip();
        ByteBuffer indices = ByteBuffer.allocate(12).order(ByteOrder.nativeOrder()).putInt(0).putInt(1).putInt(2).flip();
        float[] identity = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        Bounds[] bounds = {
            new Bounds(2.849, -.151, .499, 3.151, .151, .501),
            new Bounds(-.651, -.651, .499, -.349, -.349, .501),
            new Bounds(.349, .349, .499, .651, .651, .501),
            new Bounds(-.151, -.651, .099, .151, -.349, .101)
        };
        SceneVisibility scene = new SceneVisibility(bounds, new SceneVisibility.ScreenBounds[] {
            null, new SceneVisibility.ScreenBounds(2, 2, 6, 6, .5f),
            new SceneVisibility.ScreenBounds(10, 10, 14, 14, .5f), new SceneVisibility.ScreenBounds(6, 2, 10, 6, .099f)
        });
        SoftwareDepthBuffer depth = new SoftwareDepthBuffer(16, 16);
        depth.rasterize(0, 0, .2f, 16, 0, .2f, 0, 16, .2f);
        Frustum frustum = frustum(identity);
        try (AsyncRenderer renderer = new AsyncRenderer(context, 128, 128, 3, mode);
             MeshBatch packet = MeshBatch.builder()
                .add(vertices, indices, 3, new float[] {3, 0, 0})
                .add(vertices, indices, 3, new float[] {-.5f, -.5f, 0})
                .add(vertices, indices, 3, new float[] {.5f, .5f, 0})
                .add(vertices, indices, 3, new float[] {0, -.5f, -.4f}).build();
             GpuBatch gpu = renderer.uploadBatch(packet)) {
            long vertexHandle = gpu.vertices.handle(), indexHandle = gpu.indices.handle(), instanceHandle = gpu.instances.handle();
            ByteBuffer baseline = renderer.submitForReadback(gpu, identity).pixels();
            requireColored(baseline, 32, 32, true); requireColored(baseline, 96, 96, true); requireColored(baseline, 64, 32, true);
            ByteBuffer originalCommands = readCommands(context, gpu);
            VisibilityUpdate culled = renderer.updateVisibility(gpu, scene, frustum, depth);
            if (culled.visibleDraws() != 2 || culled.changedDraws() != 2 || culled.uploadedBytes() != 8)
                throw new IllegalStateException("CPU culling did not produce the expected sparse update");
            ByteBuffer hidden = renderer.submitForReadback(gpu, identity).pixels();
            requireColored(hidden, 32, 32, false); requireColored(hidden, 96, 96, true); requireColored(hidden, 64, 32, true);
            ByteBuffer commands = readCommands(context, gpu);
            requireCounts(commands, 0, 0, 3, 3);
            for (int i = 0; i < commands.limit(); i += 4)
                if (i % 20 != 0 && commands.getInt(i) != originalCommands.getInt(i))
                    throw new IllegalStateException("visibility changed a non-count indirect field");
            VisibilityUpdate unchanged = renderer.updateVisibility(gpu, scene, frustum, depth);
            if (unchanged.changedDraws() != 0 || unchanged.uploadedBytes() != 0)
                throw new IllegalStateException("unchanged visibility copied commands");
            depth.clear();
            VisibilityUpdate uncovered = renderer.updateVisibility(gpu, scene, frustum, depth);
            if (uncovered.visibleDraws() != 3 || uncovered.uploadedBytes() != 4) throw new IllegalStateException("uncovered draw was not restored");
            requireColored(renderer.submitForReadback(gpu, identity).pixels(), 32, 32, true);
            float[] wide = identity.clone(); wide[0] = .2f;
            VisibilityUpdate restored = renderer.updateVisibility(gpu, new SceneVisibility(bounds), frustum(wide), null);
            if (restored.visibleDraws() != 4 || restored.changedDraws() != 1 || restored.uploadedBytes() != 4)
                throw new IllegalStateException("camera change did not restore outside draw");
            requireColored(renderer.submitForReadback(gpu, wide).pixels(), 102, 64, true);
            if (!originalCommands.equals(readCommands(context, gpu))) throw new IllegalStateException("restored commands differ from originals");
            if (gpu.vertices.handle() != vertexHandle || gpu.indices.handle() != indexHandle || gpu.instances.handle() != instanceHandle)
                throw new IllegalStateException("culling replaced geometry allocations");
            renderer.updateVisibility(gpu, scene, frustum, depth);
            gpu.retire();
            renderer.drain();
            System.out.println("CPU visibility -> GPU indirect: outside/occluded hidden, near/uncovered visible, restored; "
                + "8+4+4 bytes staged; geometry retained; multiDraw=" + gpu.usesMultiDraw());
        }
    }

    private static void verifyRegionBoundary(VulkanContext context) {
        ByteBuffer vertices = ByteBuffer.allocate(48).order(ByteOrder.nativeOrder());
        vertex(vertices, -.15f, -.15f); vertex(vertices, .15f, -.15f); vertex(vertices, 0, .15f);
        vertices.flip();
        ByteBuffer indices = ByteBuffer.allocate(12).order(ByteOrder.nativeOrder()).putInt(0).putInt(1).putInt(2).flip();
        MeshBatch.Builder builder = MeshBatch.builder();
        Bounds[] bounds = new Bounds[300];
        for (int i = 0; i < bounds.length; i++) {
            builder.add(vertices, indices, 3, new float[] {3, 0, 0});
            bounds[i] = new Bounds(2.849, -.151, .499, 3.151, .151, .501);
        }
        float[] identity = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        try (AsyncRenderer renderer = new AsyncRenderer(context, 16, 16, 3);
             MeshBatch packet = builder.build(); GpuBatch gpu = renderer.uploadBatch(packet)) {
            ByteBuffer original = readCommands(context, gpu);
            SceneVisibility scene = new SceneVisibility(bounds);
            VisibilityUpdate hidden = renderer.updateVisibility(gpu, scene, frustum(identity), null);
            if (hidden.changedDraws() != 300 || hidden.uploadedBytes() != 1200) throw new IllegalStateException("large sparse update differs");
            ByteBuffer commands = readCommands(context, gpu);
            for (int i = 0; i < 300; i++) if (commands.getInt(i * 20) != 0) throw new IllegalStateException("copy-region boundary lost a change");
            float[] wide = identity.clone(); wide[0] = .2f;
            renderer.updateVisibility(gpu, scene, frustum(wide), null);
            if (!original.equals(readCommands(context, gpu))) throw new IllegalStateException("large visibility restoration differs");
        }
        System.out.println("Sparse visibility transfer: 300 commands restored across bounded copy-region batches");
    }

    private static Frustum frustum(float[] matrix) {
        double[] values = new double[16];
        for (int i = 0; i < values.length; i++) values[i] = matrix[i];
        return new Frustum(values, Frustum.ClipDepth.VULKAN_ZERO_TO_ONE);
    }

    private static void vertex(ByteBuffer vertices, float x, float y) {
        vertices.putFloat(x).putFloat(y).putFloat(.5f).putInt(3);
    }

    private static void requireCounts(ByteBuffer commands, int... expected) {
        for (int i = 0; i < expected.length; i++)
            if (commands.getInt(i * 20) != expected[i]) throw new IllegalStateException("GPU indirect count differs for draw " + i);
    }

    private static void requireColored(ByteBuffer pixels, int x, int y, boolean expected) {
        if ((Byte.toUnsignedInt(pixels.get((y * 128 + x) * 4)) > 20) != expected)
            throw new IllegalStateException("unexpected visibility at pixel " + x + "," + y);
    }

    private static ByteBuffer readCommands(VulkanContext context, GpuBatch gpu) {
        try (GpuAllocator allocator = new GpuAllocator(context);
             ImmediateCommands commands = new ImmediateCommands(context);
             GpuBuffer readback = allocator.hostBuffer(gpu.indirect.size(), VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
            commands.submit(command -> {
                try (MemoryStack stack = VulkanStack.push()) {
                    VkMemoryBarrier.Buffer before = VkMemoryBarrier.calloc(1, stack).sType$Default()
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
                    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0, before, null, null);
                    vkCmdCopyBuffer(command, gpu.indirect.handle(), readback.handle(), VkBufferCopy.calloc(1, stack).size(gpu.indirect.size()));
                    VkMemoryBarrier.Buffer after = VkMemoryBarrier.calloc(1, stack).sType$Default()
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
                    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                        0, after, null, null);
                }
            });
            return readback.read();
        }
    }
}
