package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.FrameRing;
import dev.overhaul.vulkan.GpuBuffer;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.ImageRegion;
import dev.overhaul.vulkan.VulkanStack;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * One FSR2 upscaler at one render and display size: its images, a descriptor set per pass and frame parity, and a
 * small ring of constant slots. {@link #dispatch} queues the SDK's passes in order and leaves the upscaled frame in
 * {@link BlazeFsr2Resources.Image#OUTPUT}. Owner-thread only; a new size means a new instance.
 */
final class BlazeFsr2 implements AutoCloseable {
    private static final int SLOTS = 16, NEAREST = 9728;
    private static final BlazeFsr2Shaders.Stage[] PLAIN = {
            BlazeFsr2Shaders.Stage.LUMINANCE, BlazeFsr2Shaders.Stage.RECONSTRUCT, BlazeFsr2Shaders.Stage.DEPTH_CLIP,
            BlazeFsr2Shaders.Stage.LOCK, BlazeFsr2Shaders.Stage.ACCUMULATE};
    private static final BlazeFsr2Shaders.Stage[] SHARPENED = {
            BlazeFsr2Shaders.Stage.LUMINANCE, BlazeFsr2Shaders.Stage.RECONSTRUCT, BlazeFsr2Shaders.Stage.DEPTH_CLIP,
            BlazeFsr2Shaders.Stage.LOCK, BlazeFsr2Shaders.Stage.ACCUMULATE_SHARPEN, BlazeFsr2Shaders.Stage.RCAS};

    /** What a frame tells FSR beyond its images. */
    record Frame(float verticalFov, float near, float far, float seconds, boolean reset, float sharpness) {}

    private final BlazeRenderer owner;
    private final BlazeFsr2Pipelines pipelines;
    private final BlazeFsr2Resources resources;
    private final BlazeFsr2Constants constants;
    private final Map<BlazeFsr2Shaders.Stage, long[]> sets = new EnumMap<>(BlazeFsr2Shaders.Stage.class);
    private final ArrayDeque<Integer> free = new ArrayDeque<>();
    private GpuBuffer uniforms;
    private long pool;
    private long dispatches;
    private boolean fresh = true;

    BlazeFsr2(BlazeRenderer owner, BlazeFsr2Pipelines pipelines, int renderWidth, int renderHeight, int displayWidth, int displayHeight) {
        this.owner = owner;
        this.pipelines = pipelines;
        resources = new BlazeFsr2Resources(owner, renderWidth, renderHeight, displayWidth, displayHeight);
        constants = new BlazeFsr2Constants(renderWidth, renderHeight, displayWidth, displayHeight);
        try {
            uniforms = owner.allocator().hostBuffer((long) SLOTS * BlazeFsr2Constants.SLOT_BYTES, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            for (int slot = 0; slot < SLOTS; slot++) free.add(slot);
            createSets();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    BlazeFsr2Resources resources() {
        return resources;
    }

    BlazeFsr2Constants constants() {
        return constants;
    }

    boolean matches(int renderWidth, int renderHeight, int displayWidth, int displayHeight) {
        return resources.renderWidth() == renderWidth && resources.renderHeight() == renderHeight
                && resources.displayWidth() == displayWidth && resources.displayHeight() == displayHeight;
    }

    /**
     * Queues the game's jittered colour copy and every FSR2 pass for this frame.
     *
     * @return false when every constant slot is still with the GPU, so the caller shows the frame unscaled
     */
    boolean dispatch(GpuImage sceneColor, Frame frame) {
        Integer slot = free.poll();
        if (slot == null) return false;
        boolean odd = (dispatches & 1) != 0;
        boolean reset = frame.reset() || fresh;
        fresh = false;
        try (var mapped = uniforms.mapRange(slot * BlazeFsr2Constants.SLOT_BYTES, BlazeFsr2Constants.SLOT_BYTES, false, true)) {
            ByteBuffer bytes = mapped.buffer().order(ByteOrder.nativeOrder());
            constants.packFsr2(bytes, BlazeFsr2Constants.FSR2_OFFSET, frame.verticalFov(), frame.near(), frame.far(), frame.seconds(), reset);
            constants.packSpd(bytes, BlazeFsr2Constants.SPD_OFFSET);
            BlazeFsr2Constants.packRcas(bytes, BlazeFsr2Constants.RCAS_OFFSET, frame.sharpness());
        }
        GpuImage input = resources.image(BlazeFsr2Resources.Image.INPUT_COLOR);
        if (sceneColor.format() == input.format()) owner.copyImage(sceneColor, ImageRegion.whole(sceneColor, 0, 0), input, ImageRegion.whole(input, 0, 0));
        else owner.blitImage(sceneColor, new int[]{0, 0, sceneColor.width(0), sceneColor.height(0)}, input, new int[]{0, 0, input.width(0), input.height(0)}, NEAREST);
        if (reset) resources.reset(owner, odd);
        owner.enqueue(new Passes(frame.sharpness() > 0 ? SHARPENED : PLAIN, odd, slot));
        owner.retire(() -> free.add(slot));
        dispatches++;
        return true;
    }

    private void createSets() {
        VkDevice device = owner.context().device();
        int sampled = 0, storage = 0, uniform = 0, count = 0;
        for (BlazeFsr2Shaders.Stage stage : BlazeFsr2Shaders.Stage.values()) {
            for (BlazeFsr2Shaders.Binding binding : pipelines.pass(stage).pass().bindings()) {
                switch (binding.kind()) {
                    case SAMPLED -> sampled += 2;
                    case STORAGE -> storage += 2;
                    case UNIFORM -> uniform += 2;
                    default -> { }
                }
            }
            count += 2;
        }
        try (MemoryStack stack = VulkanStack.push()) {
            var pointer = stack.mallocLong(1);
            var sizes = VkDescriptorPoolSize.calloc(3, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).descriptorCount(Math.max(1, sampled));
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(Math.max(1, storage));
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(Math.max(1, uniform));
            BlazeFsr2Pipelines.check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(count).pPoolSizes(sizes), null, pointer),
                    "descriptor pool");
            pool = pointer.get(0);
        }
        for (BlazeFsr2Shaders.Stage stage : BlazeFsr2Shaders.Stage.values()) {
            BlazeFsr2Pipelines.Compiled compiled = pipelines.pass(stage);
            long[] pair = new long[2];
            for (int parity = 0; parity < 2; parity++) pair[parity] = allocate(device, compiled, parity == 1);
            sets.put(stage, pair);
        }
    }

    private long allocate(VkDevice device, BlazeFsr2Pipelines.Compiled compiled, boolean odd) {
        try (MemoryStack stack = VulkanStack.push()) {
            var pointer = stack.mallocLong(1);
            var allocate = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(compiled.setLayout()));
            BlazeFsr2Pipelines.check(vkAllocateDescriptorSets(device, allocate, pointer), "descriptor set");
            long set = pointer.get(0);
            List<BlazeFsr2Shaders.Binding> bindings = compiled.pass().bindings();
            var writes = VkWriteDescriptorSet.calloc(bindings.size(), stack);
            for (int i = 0; i < bindings.size(); i++) {
                BlazeFsr2Shaders.Binding binding = bindings.get(i);
                var write = writes.get(i).sType$Default().dstSet(set).dstBinding(binding.binding()).descriptorCount(1)
                        .descriptorType(BlazeFsr2Pipelines.descriptorType(binding.kind()));
                if (binding.kind() == BlazeFsr2Shaders.Kind.UNIFORM) {
                    int[] block = block(binding.name());
                    write.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(uniforms.handle()).offset(block[0]).range(block[1]));
                } else {
                    GpuImageView view = resources.resolve(binding.name(), odd);
                    write.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(view.handle()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
                }
            }
            vkUpdateDescriptorSets(device, writes, null);
            return set;
        }
    }

    /** The offset and size of a constant block within one slot. */
    private static int[] block(String name) {
        return switch (name) {
            case "cbFSR2" -> new int[]{BlazeFsr2Constants.FSR2_OFFSET, 128};
            case "cbSPD" -> new int[]{BlazeFsr2Constants.SPD_OFFSET, 32};
            case "cbRCAS" -> new int[]{BlazeFsr2Constants.RCAS_OFFSET, 16};
            default -> throw new IllegalStateException("FSR2 declares an unknown constant block " + name);
        };
    }

    private int[] groups(BlazeFsr2Shaders.Stage stage) {
        int renderX = (resources.renderWidth() + 7) / 8, renderY = (resources.renderHeight() + 7) / 8;
        return switch (stage) {
            case LUMINANCE -> constants.spdGroups();
            case RECONSTRUCT, DEPTH_CLIP, LOCK -> new int[]{renderX, renderY};
            case ACCUMULATE, ACCUMULATE_SHARPEN -> new int[]{(resources.displayWidth() + 7) / 8, (resources.displayHeight() + 7) / 8};
            case RCAS -> new int[]{(resources.displayWidth() + 15) / 16, (resources.displayHeight() + 15) / 16};
        };
    }

    @Override public void close() {
        if (pool != 0) vkDestroyDescriptorPool(owner.context().device(), pool, null);
        pool = 0;
        sets.clear();
        if (uniforms != null) uniforms.close();
        uniforms = null;
        resources.close();
    }

    private final class Passes implements BlazeQueue.Operation {
        private final BlazeFsr2Shaders.Stage[] stages;
        private final boolean odd;
        private final int slot;
        private final BlazeImageUse images = new BlazeImageUse();

        private Passes(BlazeFsr2Shaders.Stage[] stages, boolean odd, int slot) {
            this.stages = stages;
            this.odd = odd;
            this.slot = slot;
        }

        @Override public void record(VkCommandBuffer command) {
            for (GpuImageView view : resources.all()) images.transition(command, view, VK_IMAGE_LAYOUT_GENERAL);
            try (MemoryStack stack = VulkanStack.push()) {
                var barrier = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                for (int i = 0; i < stages.length; i++) {
                    BlazeFsr2Shaders.Stage stage = stages[i];
                    BlazeFsr2Pipelines.Compiled compiled = pipelines.pass(stage);
                    if (i > 0) vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
                    int uniformsBound = compiled.pass().uniforms();
                    int[] offsets = new int[uniformsBound];
                    java.util.Arrays.fill(offsets, slot * BlazeFsr2Constants.SLOT_BYTES);
                    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, compiled.pipeline());
                    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, compiled.pipelineLayout(), 0,
                            stack.longs(pipelines.samplerSet(), sets.get(stage)[odd ? 1 : 0]), stack.ints(offsets));
                    int[] groups = groups(stage);
                    vkCmdDispatch(command, groups[0], groups[1], 1);
                }
                // The output is blitted next and the history read by the next frame's passes.
                var after = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT);
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT, 0, after, null, null);
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
