package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.vulkan.FrameRing;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.GpuSampler;
import dev.overhaul.vulkan.ImageFormat;
import dev.overhaul.vulkan.ImageRegion;
import dev.overhaul.vulkan.SamplerSettings;
import dev.overhaul.vulkan.VulkanContext;
import dev.overhaul.vulkan.VulkanStack;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Runs one {@link BlazePostShader} mode over a target's colour: the shader reads the colour through a linear sampler,
 * writes an image of its own, since colour targets cannot be written as storage, and that image is copied back over
 * the colour. The pipeline lives as long as the renderer; the output image follows the target's size.
 */
final class BlazePostPass implements AutoCloseable {
    private final BlazeRenderer owner;
    private final VulkanContext context;
    private final GpuSampler sampler;
    private long setLayout, pipelineLayout, pipeline;
    private GpuImage output;
    private GpuImageView outputView;
    private Binding binding;

    BlazePostPass(BlazeRenderer owner) {
        this.owner = owner;
        context = owner.context();
        sampler = owner.allocator().createSampler(new SamplerSettings(SamplerSettings.Filter.LINEAR,
                SamplerSettings.Filter.LINEAR, SamplerSettings.Mipmap.NEAREST, SamplerSettings.Address.CLAMP,
                SamplerSettings.Address.CLAMP, SamplerSettings.Address.CLAMP, 0, 0, 1, SamplerSettings.Compare.NONE));
        try {
            create();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private void create() {
        VkDevice device = context.device();
        try (MemoryStack stack = VulkanStack.push(); ShadercCompiler compiler = new ShadercCompiler(true)) {
            var pointer = stack.mallocLong(1);
            var bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            BlazeFsr2Pipelines.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, pointer), "post layout");
            setLayout = pointer.get(0);
            var range = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(BlazePostShader.PUSH_BYTES);
            BlazeFsr2Pipelines.check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(range),
                    null, pointer), "post pipeline layout");
            pipelineLayout = pointer.get(0);
            var code = compiler.compile("overhaul_post", BlazePostShader.SOURCE, ShaderStage.COMPUTE).directBuffer();
            BlazeFsr2Pipelines.check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, pointer), "post module");
            long module = pointer.get(0);
            try {
                var info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(pipelineLayout);
                info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
                BlazeFsr2Pipelines.check(vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, pointer), "post pipeline");
                pipeline = pointer.get(0);
            } finally {
                vkDestroyShaderModule(device, module, null);
            }
        }
    }

    /** Queues {@code mode} over {@code target}'s colour, then the copy of the result back over it. */
    void run(BlazeTarget target, int mode, float strength) {
        int width = target.width(), height = target.height();
        if (output == null || output.width(0) != width || output.height(0) != height) {
            if (binding != null) owner.retire(binding);
            if (output != null) owner.retire(output);
            binding = null;
            output = null;
            output = owner.allocator().createImage(ImageFormat.RGBA8_UNORM, width, height, 1, 1,
                    VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
            outputView = output.view(0, 1, 0, 1);
        }
        GpuImageView color = target.colorView();
        if (binding == null || !binding.reads(color)) {
            if (binding != null) owner.retire(binding);
            binding = null;
            binding = new Binding(color);
        }
        owner.enqueue(new Dispatch(binding, mode, strength, width, height));
        owner.copyImage(output, ImageRegion.whole(output, 0, 0), target.color(), ImageRegion.whole(target.color(), 0, 0));
    }

    /** One descriptor set over a target's colour and the output; closing it frees its pool. */
    private final class Binding implements AutoCloseable {
        private final GpuImageView color, output;
        private long pool, set;

        Binding(GpuImageView color) {
            this.color = color;
            output = outputView;
            VkDevice device = context.device();
            try (MemoryStack stack = VulkanStack.push()) {
                var pointer = stack.mallocLong(1);
                var sizes = VkDescriptorPoolSize.calloc(2, stack);
                sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
                sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
                BlazeFsr2Pipelines.check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, pointer), "post pool");
                pool = pointer.get(0);
                BlazeFsr2Pipelines.check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), pointer),
                        "post set");
                set = pointer.get(0);
                var writes = VkWriteDescriptorSet.calloc(2, stack);
                writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(sampler.handle()).imageView(color.handle()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
                writes.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(output.handle()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
                vkUpdateDescriptorSets(device, writes, null);
            }
        }

        boolean reads(GpuImageView color) {
            return this.color == color && output == outputView;
        }

        @Override public void close() {
            if (pool != 0) vkDestroyDescriptorPool(context.device(), pool, null);
            pool = set = 0;
        }
    }

    private final class Dispatch implements BlazeQueue.Operation {
        private final Binding binding;
        private final int mode, width, height;
        private final float strength;
        private final BlazeImageUse images = new BlazeImageUse();

        Dispatch(Binding binding, int mode, float strength, int width, int height) {
            this.binding = binding;
            this.mode = mode;
            this.strength = strength;
            this.width = width;
            this.height = height;
        }

        @Override public void record(VkCommandBuffer command) {
            images.transition(command, binding.color, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            images.transition(command, binding.output, VK_IMAGE_LAYOUT_GENERAL);
            try (MemoryStack stack = VulkanStack.push()) {
                var push = stack.malloc(BlazePostShader.PUSH_BYTES);
                push.putInt(0, width).putInt(4, height).putInt(8, mode).putFloat(12, strength);
                vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(binding.set), null);
                vkCmdPushConstants(command, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                vkCmdDispatch(command, (width + 7) / 8, (height + 7) / 8, 1);
            }
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
        if (output != null) output.close();
        output = null;
        VkDevice device = context.device();
        if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
        if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
        if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
        pipeline = pipelineLayout = setLayout = 0;
        sampler.close();
    }
}
