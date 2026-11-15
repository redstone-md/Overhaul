package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.vulkan.FrameRing;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.GpuSampler;
import dev.overhaul.vulkan.ImageFormat;
import dev.overhaul.vulkan.SamplerSettings;
import dev.overhaul.vulkan.VulkanContext;
import dev.overhaul.vulkan.VulkanStack;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Makes the glow and the exposure of Overhaul shading from the linear light a {@link BlazeShadingPass} leaves at half
 * size. The light is halved level by level, the levels are added back up into a soft glow, and the smallest one is
 * measured into one pixel that moves towards it over time, the way an eye adapts. The pass that lights the next frame
 * reads both, so they trail the picture by one frame and cost no pass over the whole screen. Every level is an image
 * of its own, so each step reads one and writes another. Either effect can be left out: its image is then empty,
 * which the lighting pass takes as none; the levels the other one needs are still made.
 */
final class BlazeShadingGlow implements AutoCloseable {
    private static final int LEVELS = 6;
    /** How fast the exposure follows the picture, per second. */
    private static final float PACE = 1.5f;
    /** Nanoseconds between two runs of the glow and the measure: a frame at 250 a second. */
    private static final long EVERY = 4_000_000;

    private final BlazeRenderer owner;
    private final VulkanContext context;
    private final GpuSampler nearest, smooth;
    private final long[] setLayouts = new long[1], pipelineLayouts = new long[1], pipelines = new long[1];
    private final GpuImage[] levels = new GpuImage[LEVELS];
    private final GpuImageView[] levelViews = new GpuImageView[LEVELS];
    private GpuImage measured;
    private GpuImageView measuredView;
    private Binding binding;
    private long measuredAt, finishedAt;
    /** Whether the glow and the measure hold something a frame made. */
    private boolean glowing, adapting;

    BlazeShadingGlow(BlazeRenderer owner) {
        this.owner = owner;
        context = owner.context();
        nearest = sampler(SamplerSettings.Filter.NEAREST);
        smooth = sampler(SamplerSettings.Filter.LINEAR);
        try {
            create(0, "overhaul_shading_glow", BlazeShadingGlowShader.STEP, 1, BlazeShadingGlowShader.STEP_BYTES);
            measured = owner.allocator().createImage(ImageFormat.RGBA16_SFLOAT, 1, 1, 1, 1,
                    VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            measuredView = measured.view(0, 1, 0, 1);
            owner.clearImage(measured, 0, 0, 0, 0, 0, 0);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private GpuSampler sampler(SamplerSettings.Filter filter) {
        return owner.allocator().createSampler(new SamplerSettings(filter, filter, SamplerSettings.Mipmap.NEAREST, SamplerSettings.Address.CLAMP,
                SamplerSettings.Address.CLAMP, SamplerSettings.Address.CLAMP, 0, 0, 1, SamplerSettings.Compare.NONE));
    }

    /** One compute pipeline reading {@code sampled} textures at the first bindings and writing the image after them. */
    private void create(int index, String name, String source, int sampled, int pushBytes) {
        VkDevice device = context.device();
        try (MemoryStack stack = VulkanStack.push(); ShadercCompiler compiler = new ShadercCompiler(true)) {
            var pointer = stack.mallocLong(1);
            var bindings = VkDescriptorSetLayoutBinding.calloc(sampled + 1, stack);
            for (int i = 0; i <= sampled; i++) bindings.get(i).binding(i).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                    .descriptorType(i < sampled ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            BlazeFsr2Pipelines.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, pointer), name + " layout");
            setLayouts[index] = pointer.get(0);
            var range = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pushBytes);
            BlazeFsr2Pipelines.check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayouts[index]))
                    .pPushConstantRanges(range), null, pointer), name + " pipeline layout");
            pipelineLayouts[index] = pointer.get(0);
            var code = compiler.compile(name, source, ShaderStage.COMPUTE).directBuffer();
            BlazeFsr2Pipelines.check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, pointer), name + " module");
            long module = pointer.get(0);
            try {
                var info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(pipelineLayouts[index]);
                info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
                BlazeFsr2Pipelines.check(vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, pointer), name + " pipeline");
                pipelines[index] = pointer.get(0);
            } finally {
                vkDestroyShaderModule(device, module, null);
            }
        }
    }

    /** What the lighting pass works with: the half size image it writes, and the glow and the measure it reads. */
    record Light(GpuImageView spread, GpuImageView glow, GpuImageView measured, long smooth) {}

    /**
     * The images for a frame of this size. An effect that is off, or whose images are new, shows as an empty image.
     */
    Light light(int width, int height, boolean bloom, boolean adapt) {
        int wide = Math.max(1, width / 2), high = Math.max(1, height / 2);
        if (levels[0] == null || levels[0].width(0) != wide || levels[0].height(0) != high) {
            release();
            for (int i = 0; i < LEVELS; i++) {
                levels[i] = owner.allocator().createImage(ImageFormat.RGBA16_SFLOAT, Math.max(1, wide >> i), Math.max(1, high >> i), 1, 1,
                        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
                levelViews[i] = levels[i].view(0, 1, 0, 1);
            }
            binding = new Binding();
            owner.clearImage(levels[1], 0, 0, 0, 0, 0, 0);
            glowing = false;
        }
        if (glowing && !bloom) owner.clearImage(levels[1], 0, 0, 0, 0, 0, 0);
        if (adapting && !adapt) {
            owner.clearImage(measured, 0, 0, 0, 0, 0, 0);
            measuredAt = 0;
        }
        glowing = bloom;
        adapting = adapt;
        return new Light(levelViews[0], levelViews[1], measuredView, smooth.handle());
    }

    /**
     * Queues the glow and the measure over the light the pass left in the images {@link #light} gave out. The eye
     * cannot follow either faster than {@link #EVERY}, so frames that come sooner keep what the last one made.
     */
    void finish(boolean bloom, boolean adapt) {
        long now = System.nanoTime();
        if (finishedAt != 0 && now - finishedAt < EVERY) return;
        finishedAt = now;
        float blend = measuredAt == 0 ? 1 : 1 - (float) Math.exp(-(now - measuredAt) * 1e-9 * PACE);
        if (adapt) measuredAt = now;
        owner.enqueue(new Finish(binding, bloom, adapt, blend));
    }

    private void release() {
        if (binding != null) owner.retire(binding);
        binding = null;
        for (int i = 0; i < LEVELS; i++) {
            if (levels[i] != null) owner.retire(levels[i]);
            levels[i] = null;
            levelViews[i] = null;
        }
    }

    /** The descriptor sets over this size's images: a set per halving, one per doubling and the measure. */
    private final class Binding implements AutoCloseable {
        private final long[] down = new long[LEVELS], up = new long[LEVELS];
        private long pool, measure;

        Binding() {
            VkDevice device = context.device();
            int steps = 2 * LEVELS - 2;
            try (MemoryStack stack = VulkanStack.push()) {
                var pointer = stack.mallocLong(1);
                var sizes = VkDescriptorPoolSize.calloc(2, stack);
                sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(steps);
                sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(steps);
                BlazeFsr2Pipelines.check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(steps).pPoolSizes(sizes), null, pointer),
                        "shading glow pool");
                pool = pointer.get(0);
                for (int i = 1; i < LEVELS; i++) down[i] = set(stack, levelViews[i], levelViews[i - 1]);
                for (int i = LEVELS - 2; i >= 1; i--) up[i] = set(stack, levelViews[i], levelViews[i + 1]);
                measure = set(stack, measuredView, levelViews[LEVELS - 1]);
            }
        }

        /** A set writing {@code written} from {@code read}, smoothed unless it is the measure, which reads each pixel. */
        private long set(MemoryStack stack, GpuImageView written, GpuImageView read) {
            var pointer = stack.mallocLong(1);
            BlazeFsr2Pipelines.check(vkAllocateDescriptorSets(context.device(), VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool)
                    .pSetLayouts(stack.longs(setLayouts[0])), pointer), "shading glow set");
            long set = pointer.get(0);
            var writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler((written != measuredView ? smooth : nearest).handle()).imageView(read.handle())
                            .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            writes.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(written.handle()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
            vkUpdateDescriptorSets(context.device(), writes, null);
            return set;
        }

        @Override public void close() {
            if (pool != 0) vkDestroyDescriptorPool(context.device(), pool, null);
            pool = 0;
        }
    }

    private final class Finish implements BlazeQueue.Operation {
        private final Binding binding;
        private final boolean bloom, adapt;
        private final float blend;
        private final GpuImageView[] views = levelViews.clone();
        private final GpuImageView measuredView = BlazeShadingGlow.this.measuredView;
        private final BlazeImageUse images = new BlazeImageUse();

        Finish(Binding binding, boolean bloom, boolean adapt, float blend) {
            this.binding = binding;
            this.bloom = bloom;
            this.adapt = adapt;
            this.blend = blend;
        }

        @Override public void record(VkCommandBuffer command) {
            try (MemoryStack stack = VulkanStack.push()) {
                vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelines[0]);
                // The glow ends in the second level; the measure reads the last one.
                int deepest = LEVELS - 1;
                for (int i = 1; i <= deepest; i++) step(command, stack, binding.down[i], views[i - 1], views[i], BlazeShadingGlowShader.HALVE, 1);
                if (adapt) step(command, stack, binding.measure, views[LEVELS - 1], measuredView, BlazeShadingGlowShader.MEASURE, blend);
                if (bloom) for (int i = LEVELS - 2; i >= 1; i--) step(command, stack, binding.up[i], views[i + 1], views[i], BlazeShadingGlowShader.DOUBLE, 0.5f);
            }
        }

        private void step(VkCommandBuffer command, MemoryStack stack, long set, GpuImageView source, GpuImageView written, int mode, float blend) {
            images.transition(command, source, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            images.transition(command, written, VK_IMAGE_LAYOUT_GENERAL);
            int wide = written.image().width(0), high = written.image().height(0);
            var push = stack.malloc(BlazeShadingGlowShader.STEP_BYTES);
            push.putInt(0, wide).putInt(4, high).putInt(8, mode).putFloat(12, blend);
            vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayouts[0], 0, stack.longs(set), null);
            vkCmdPushConstants(command, pipelineLayouts[0], VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(command, (wide + 7) / 8, (high + 7) / 8, 1);
        }

        @Override public void submitted(FrameRing.Submission submission) {
            images.submitted(submission);
        }

        @Override public void close() {
            images.close();
        }
    }

    @Override public void close() {
        if (binding != null) binding.close();
        binding = null;
        for (int i = 0; i < LEVELS; i++) {
            if (levels[i] != null) levels[i].close();
            levels[i] = null;
        }
        if (measured != null) measured.close();
        measured = null;
        VkDevice device = context.device();
        for (int i = 0; i < pipelines.length; i++) {
            if (pipelines[i] != 0) vkDestroyPipeline(device, pipelines[i], null);
            if (pipelineLayouts[i] != 0) vkDestroyPipelineLayout(device, pipelineLayouts[i], null);
            if (setLayouts[i] != 0) vkDestroyDescriptorSetLayout(device, setLayouts[i], null);
            pipelines[i] = pipelineLayouts[i] = setLayouts[i] = 0;
        }
        nearest.close();
        smooth.close();
    }
}
