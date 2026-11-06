package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.vulkan.GpuBuffer;
import dev.overhaul.vulkan.VulkanContext;
import dev.overhaul.vulkan.VulkanStack;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Zeroes the instance count of every queued section draw whose box was behind the depth tiles of the latest queued
 * capture. It runs on the GPU right before the draws, in queue order behind that capture, so it never waits for a
 * readback and always tests the newest terrain depth. Owner-thread only.
 */
final class BlazeSectionCull implements AutoCloseable {
    static final int SECTION = 16;
    /** A box covering more tiles than this each way is near the camera and rarely hidden; it fails open. */
    private static final int MAX_SPAN = 32;
    private static final int GROUP = 64, SETS_PER_POOL = 64, CONSTANT_BYTES = 112;
    private static final String SHADER = """
            #version 450
            layout(local_size_x = 64) in;
            layout(std430, binding = 0) buffer Commands { uint commands[]; };
            layout(std430, binding = 1) readonly buffer Positions { float positions[]; };
            layout(std430, binding = 2) readonly buffer Tiles { float farthest[]; };
            layout(push_constant) uniform Cull {
                mat4 viewProjection;
                vec4 shift;
                uvec4 counts;
                ivec4 tiles;
            };
            void main() {
                uint index = gl_GlobalInvocationID.x;
                if (index >= counts.x) return;
                uint at = counts.z + index * 3u;
                vec3 low = vec3(positions[at], positions[at + 1u], positions[at + 2u]) + shift.xyz - shift.w;
                float across = %d.0 + 2.0 * shift.w;
                vec2 lowest = vec2(3.0e38), highest = vec2(-3.0e38);
                float nearest = 3.0e38;
                for (int corner = 0; corner < 8; corner++) {
                    vec3 point = low + across * vec3(corner & 1, (corner >> 1) & 1, (corner >> 2) & 1);
                    vec4 clip = viewProjection * vec4(point, 1.0);
                    if (clip.w <= 0.05) return;
                    vec3 screen = clip.xyz / clip.w;
                    lowest = min(lowest, screen.xy);
                    highest = max(highest, screen.xy);
                    nearest = min(nearest, screen.z);
                }
                vec2 toTiles = vec2(tiles.zw) * 0.5 / 16.0;
                ivec2 first = ivec2(floor((lowest + 1.0) * toTiles)), last = ivec2(ceil((highest + 1.0) * toTiles));
                if (any(lessThan(first, ivec2(0))) || any(greaterThan(last, tiles.xy)) || any(greaterThanEqual(first, last))) return;
                if (any(greaterThan(last - first, ivec2(%d)))) return;
                float depth = 0.5 * nearest + 0.5;
                if (depth < 0.0 || depth > 1.0) return;
                for (int y = first.y; y < last.y; y++) {
                    for (int x = first.x; x < last.x; x++) {
                        if (depth <= farthest[y * tiles.x + x] + 0.00001) return;
                    }
                }
                commands[counts.y + index * 5u + 1u] = 0u;
            }
            """.formatted(SECTION, MAX_SPAN);

    private final VulkanContext context;
    private final BlazeQueue queue;
    private final List<Long> pools = new ArrayList<>();
    private final ArrayDeque<Long> free = new ArrayDeque<>();
    private long setLayout, pipelineLayout, pipeline;

    BlazeSectionCull(VulkanContext context, BlazeQueue queue) {
        this.context = context;
        this.queue = queue;
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
            var bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
            for (int binding = 0; binding < 3; binding++) {
                bindings.get(binding).binding(binding).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, pointer), "layout");
            setLayout = pointer.get(0);
            var range = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(CONSTANT_BYTES);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(range), null, pointer), "pipeline layout");
            pipelineLayout = pointer.get(0);
            var code = compiler.compile("overhaul_section_cull", SHADER, ShaderStage.COMPUTE).directBuffer();
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
        }
    }

    /** Queues the test of {@code count} draws, and their positions, ahead of whatever is queued next. */
    void enqueue(BlazeBufferPool.Slice commands, BlazeBufferPool.Slice positions, int count, GpuBuffer tiles, View view) {
        long set = set();
        try (MemoryStack stack = VulkanStack.push()) {
            var infos = VkDescriptorBufferInfo.calloc(3, stack);
            infos.get(0).buffer(commands.buffer().handle()).offset(0).range(VK_WHOLE_SIZE);
            infos.get(1).buffer(positions.buffer().handle()).offset(0).range(VK_WHOLE_SIZE);
            infos.get(2).buffer(tiles.handle()).offset(0).range(VK_WHOLE_SIZE);
            var writes = VkWriteDescriptorSet.calloc(3, stack);
            for (int binding = 0; binding < 3; binding++) {
                writes.get(binding).sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(VkDescriptorBufferInfo.create(infos.address(binding), 1));
            }
            vkUpdateDescriptorSets(context.device(), writes, null);
        }
        ByteBuffer constants = MemoryUtil.memAlloc(CONSTANT_BYTES);
        view.write(constants, count, (int) (commands.offset() / Integer.BYTES), (int) (positions.offset() / Integer.BYTES));
        queue.add(new Dispatch(set, constants, count), 0);
        queue.retire(() -> free.add(set));
    }

    private long set() {
        Long held = free.poll();
        if (held != null) return held;
        VkDevice device = context.device();
        try (MemoryStack stack = VulkanStack.push()) {
            var pointer = stack.mallocLong(1);
            var sizes = VkDescriptorPoolSize.calloc(1, stack).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(SETS_PER_POOL * 3);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(SETS_PER_POOL).pPoolSizes(sizes), null, pointer), "descriptor pool");
            long pool = pointer.get(0);
            pools.add(pool);
            var layouts = stack.mallocLong(SETS_PER_POOL);
            for (int index = 0; index < SETS_PER_POOL; index++) layouts.put(index, setLayout);
            var sets = stack.mallocLong(SETS_PER_POOL);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(layouts), sets), "descriptor sets");
            for (int index = 1; index < SETS_PER_POOL; index++) free.add(sets.get(index));
            return sets.get(0);
        }
    }

    @Override public void close() {
        VkDevice device = context.device();
        for (long pool : pools) vkDestroyDescriptorPool(device, pool, null);
        pools.clear();
        free.clear();
        if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
        if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
        if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
        pipeline = pipelineLayout = setLayout = 0;
    }

    private static void check(int result, String what) {
        if (result != VK_SUCCESS) throw new IllegalStateException("Section cull " + what + " failed: " + result);
    }

    /** What a capture saw, against the camera of the draws being tested. */
    record View(float[] viewProjection, float shiftX, float shiftY, float shiftZ, float grow, int tilesWide, int tilesHigh, int pixelsWide, int pixelsHigh) {
        void write(ByteBuffer into, int count, int commandWord, int positionWord) {
            for (float value : viewProjection) into.putFloat(value);
            into.putFloat(shiftX).putFloat(shiftY).putFloat(shiftZ).putFloat(grow);
            into.putInt(count).putInt(commandWord).putInt(positionWord).putInt(0);
            into.putInt(tilesWide).putInt(tilesHigh).putInt(pixelsWide).putInt(pixelsHigh);
            into.flip();
        }
    }

    private final class Dispatch implements BlazeQueue.Operation {
        private final long set;
        private final int count;
        private ByteBuffer constants;

        private Dispatch(long set, ByteBuffer constants, int count) {
            this.set = set;
            this.constants = constants;
            this.count = count;
        }

        @Override public void record(VkCommandBuffer command) {
            try (MemoryStack stack = VulkanStack.push()) {
                vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(set), null);
                vkCmdPushConstants(command, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, constants);
                vkCmdDispatch(command, (count + GROUP - 1) / GROUP, 1, 1);
                var barrier = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT, 0, barrier, null, null);
            }
        }

        @Override public void close() {
            MemoryUtil.memFree(constants);
            constants = null;
        }
    }
}
