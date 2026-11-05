package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.VulkanStack;

import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.vulkan.FrameRing;
import dev.overhaul.vulkan.GpuAllocator;
import dev.overhaul.vulkan.GpuBuffer;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.GpuSampler;
import dev.overhaul.vulkan.SamplerSettings;
import dev.overhaul.vulkan.VulkanContext;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.function.BiConsumer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Reduces a depth image to the farthest depth of each 16-pixel tile and hands the result to the CPU once its fence has
 * passed. A capture is skipped while every readback is still with the GPU, so this never waits. Owner-thread only.
 */
final class BlazeDepthTiles implements AutoCloseable {
    static final int TILE_PIXELS = 16;
    private static final int READBACKS = 3;
    private static final String SHADER = """
            #version 450
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(binding = 0) uniform sampler2D depthImage;
            layout(std430, binding = 1) writeonly buffer Tiles { float farthest[]; };
            layout(push_constant) uniform Size { ivec2 pixels; ivec2 tiles; };
            void main() {
                ivec2 tile = ivec2(gl_GlobalInvocationID.xy);
                if (tile.x >= tiles.x || tile.y >= tiles.y) return;
                ivec2 start = tile * 16, end = min(start + 16, pixels);
                float farthestDepth = 0.0;
                for (int y = start.y; y < end.y; y++) {
                    for (int x = start.x; x < end.x; x++) farthestDepth = max(farthestDepth, texelFetch(depthImage, ivec2(x, y), 0).r);
                }
                farthest[tile.y * tiles.x + tile.x] = farthestDepth;
            }
            """;

    private final VulkanContext context;
    private final GpuAllocator allocator;
    private final BlazeQueue queue;
    private final GpuSampler sampler;
    private final ArrayDeque<Readback> free = new ArrayDeque<>();
    private final Readback[] all = new Readback[READBACKS];
    private long setLayout, pipelineLayout, pipeline, pool;

    BlazeDepthTiles(VulkanContext context, GpuAllocator allocator, BlazeQueue queue) {
        this.context = context;
        this.allocator = allocator;
        this.queue = queue;
        sampler = allocator.createSampler(new SamplerSettings(SamplerSettings.Filter.NEAREST, SamplerSettings.Filter.NEAREST, SamplerSettings.Mipmap.NEAREST,
                SamplerSettings.Address.CLAMP, SamplerSettings.Address.CLAMP, SamplerSettings.Address.CLAMP, 0, 0, 1, SamplerSettings.Compare.NONE));
        try {
            create();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private void create() {
        VkDevice device = context.device();
        try (MemoryStack stack = VulkanStack.push(); ShadercCompiler compiler = new ShadercCompiler()) {
            var pointer = stack.mallocLong(1);
            var bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, pointer), "layout");
            setLayout = pointer.get(0);
            var range = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(range), null, pointer), "pipeline layout");
            pipelineLayout = pointer.get(0);
            var code = compiler.compile("overhaul_depth_tiles", SHADER, ShaderStage.COMPUTE).directBuffer();
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, pointer), "shader module");
            long module = pointer.get(0);
            try {
                var info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(pipelineLayout);
                info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
                check(vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, pointer), "compute pipeline");
                pipeline = pointer.get(0);
            } finally {
                vkDestroyShaderModule(device, module, null);
            }
            var sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(READBACKS);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(READBACKS);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(READBACKS).pPoolSizes(sizes), null, pointer), "descriptor pool");
            pool = pointer.get(0);
            for (int index = 0; index < READBACKS; index++) {
                var allocate = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(setLayout));
                check(vkAllocateDescriptorSets(device, allocate, pointer), "descriptor set");
                free.add(all[index] = new Readback(pointer.get(0)));
            }
        }
    }

    /** @param done receives the tiles, row 0 first as the image stores them, valid for the call only */
    void capture(GpuImageView depth, int pixelsWide, int pixelsHigh, BiConsumer<FloatBuffer, int[]> done) {
        Readback readback = free.poll();
        if (readback == null) return;
        int tilesWide = (pixelsWide + TILE_PIXELS - 1) / TILE_PIXELS, tilesHigh = (pixelsHigh + TILE_PIXELS - 1) / TILE_PIXELS;
        int[] tiles = {tilesWide, tilesHigh};
        readback.point(depth, tilesWide * tilesHigh * Float.BYTES);
        queue.add(new Reduce(readback, depth, new int[]{pixelsWide, pixelsHigh, tilesWide, tilesHigh}), 0);
        queue.retire(() -> {
            try (var mapped = readback.buffer.mapRange(0, readback.buffer.size(), true, false)) {
                done.accept(mapped.buffer().order(ByteOrder.nativeOrder()).asFloatBuffer().limit(tilesWide * tilesHigh), tiles);
            } finally {
                free.add(readback);
            }
        });
    }

    @Override public void close() {
        VkDevice device = context.device();
        for (Readback readback : all) if (readback != null && readback.buffer != null) readback.buffer.close();
        if (pool != 0) vkDestroyDescriptorPool(device, pool, null);
        if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
        if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
        if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
        pool = pipeline = pipelineLayout = setLayout = 0;
        sampler.close();
    }

    private static void check(int result, String what) {
        if (result != VK_SUCCESS) throw new IllegalStateException("Depth tile " + what + " failed: " + result);
    }

    private final class Readback {
        private final long set;
        private GpuBuffer buffer;

        private Readback(long set) {
            this.set = set;
        }

        /** Only a readback no submission still uses gets here, so its set and buffer can change. */
        private void point(GpuImageView depth, int bytes) {
            if (buffer != null && buffer.size() != bytes) {
                buffer.close();
                buffer = null;
            }
            if (buffer == null) buffer = allocator.hostBuffer(bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            try (MemoryStack stack = VulkanStack.push()) {
                var writes = VkWriteDescriptorSet.calloc(2, stack);
                var image = VkDescriptorImageInfo.calloc(1, stack).sampler(sampler.handle()).imageView(depth.handle()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                var storage = VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer.handle()).offset(0).range(bytes);
                writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(image);
                writes.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(storage);
                vkUpdateDescriptorSets(context.device(), writes, null);
            }
        }
    }

    private final class Reduce implements BlazeQueue.Operation {
        private final Readback readback;
        private final GpuImageView depth;
        private final int[] size;
        private final BlazeImageUse images = new BlazeImageUse();

        private Reduce(Readback readback, GpuImageView depth, int[] size) {
            this.readback = readback;
            this.depth = depth;
            this.size = size;
        }

        @Override public void record(VkCommandBuffer command) {
            images.transition(command, depth, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            try (MemoryStack stack = VulkanStack.push()) {
                vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(readback.set), null);
                vkCmdPushConstants(command, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(size));
                vkCmdDispatch(command, (size[2] + 7) / 8, (size[3] + 7) / 8, 1);
                var barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).buffer(readback.buffer.handle()).offset(0).size(VK_WHOLE_SIZE);
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
            }
        }

        @Override public void submitted(FrameRing.Submission submission) {
            images.submitted(submission);
        }

        @Override public void close() {
            images.close();
        }
    }
}
