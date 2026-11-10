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
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Runs {@link BlazeRayShader} over a finished world target: it reads the target's colour and depth, writes the lit
 * picture to an image of its own, since colour targets cannot be written as storage, and copies that back over the
 * colour. The pipeline lives as long as the renderer; the output image follows the target's size.
 */
final class BlazeRayPass implements AutoCloseable {
    private static final int BINDINGS = 5;

    private final BlazeRenderer owner;
    private final VulkanContext context;
    private final GpuSampler sampler;
    private long setLayout, pipelineLayout, pipeline;
    private GpuImage output;
    private GpuImageView outputView;
    private Binding binding;

    BlazeRayPass(BlazeRenderer owner) {
        this.owner = owner;
        context = owner.context();
        sampler = owner.allocator().createSampler(new SamplerSettings(SamplerSettings.Filter.NEAREST,
                SamplerSettings.Filter.NEAREST, SamplerSettings.Mipmap.NEAREST, SamplerSettings.Address.CLAMP,
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
            var bindings = VkDescriptorSetLayoutBinding.calloc(BINDINGS, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            for (int i = 1; i < 4; i++) bindings.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(4).binding(4).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            BlazeFsr2Pipelines.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, pointer), "ray layout");
            setLayout = pointer.get(0);
            var range = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(BlazeRayShader.PUSH_BYTES);
            BlazeFsr2Pipelines.check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(range),
                    null, pointer), "ray pipeline layout");
            pipelineLayout = pointer.get(0);
            var code = compiler.compile("overhaul_ray_light", BlazeRayShader.SOURCE, ShaderStage.COMPUTE).directBuffer();
            BlazeFsr2Pipelines.check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, pointer), "ray module");
            long module = pointer.get(0);
            try {
                var info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(pipelineLayout);
                info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
                BlazeFsr2Pipelines.check(vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, pointer), "ray pipeline");
                pipeline = pointer.get(0);
            } finally {
                vkDestroyShaderModule(device, module, null);
            }
        }
    }

    /** What one frame's lighting reads beyond the target and the scene. */
    record Lighting(Matrix4f inverseViewProjection, float[] light, float[] sky, int flags, int frame, float distance) {}

    /** Queues the lighting of {@code target} from {@code scene}, then the copy of the result over its colour. */
    void trace(BlazeTarget target, BlazeTextures.Sampled atlas, BlazeRayScene.Frame scene, Lighting lighting) {
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
        GpuImageView color = target.colorView(), depth = target.sampledDepthView();
        if (binding == null || !binding.reads(scene.top(), color, depth, atlas)) {
            if (binding != null) owner.retire(binding);
            binding = null;
            binding = new Binding(scene.top(), color, depth, atlas);
        }
        owner.enqueue(new Dispatch(binding, scene, lighting, width, height));
        owner.copyImage(output, ImageRegion.whole(output, 0, 0), target.color(), ImageRegion.whole(target.color(), 0, 0));
    }

    /** One descriptor set over a top level structure, a target's views and the atlas; closing it frees its pool. */
    private final class Binding implements AutoCloseable {
        private final BlazeRayStructures.Structure top;
        private final GpuImageView color, depth, atlas, output;
        private final long atlasSampler;
        private final int atlasLayout;
        private long pool, set;

        Binding(BlazeRayStructures.Structure top, GpuImageView color, GpuImageView depth, BlazeTextures.Sampled atlas) {
            this.top = top;
            this.color = color;
            this.depth = depth;
            this.atlas = atlas.view();
            atlasSampler = atlas.sampler();
            atlasLayout = atlas.general() ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            output = outputView;
            VkDevice device = context.device();
            try (MemoryStack stack = VulkanStack.push()) {
                var pointer = stack.mallocLong(1);
                var sizes = VkDescriptorPoolSize.calloc(3, stack);
                sizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
                sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(3);
                sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
                BlazeFsr2Pipelines.check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, pointer), "ray pool");
                pool = pointer.get(0);
                BlazeFsr2Pipelines.check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), pointer),
                        "ray set");
                set = pointer.get(0);
                var writes = VkWriteDescriptorSet.calloc(BINDINGS, stack);
                var structure = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default().pAccelerationStructures(stack.longs(top.handle()));
                writes.get(0).sType$Default().pNext(structure.address()).dstSet(set).dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
                long[] samplers = {sampler.handle(), sampler.handle(), atlasSampler};
                GpuImageView[] views = {color, depth, this.atlas};
                for (int i = 0; i < 3; i++) {
                    int layout = i == 2 ? atlasLayout : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                    writes.get(i + 1).sType$Default().dstSet(set).dstBinding(i + 1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(samplers[i]).imageView(views[i].handle()).imageLayout(layout));
                }
                writes.get(4).sType$Default().dstSet(set).dstBinding(4).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(output.handle()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
                vkUpdateDescriptorSets(device, writes, null);
            }
        }

        boolean reads(BlazeRayStructures.Structure top, GpuImageView color, GpuImageView depth, BlazeTextures.Sampled atlas) {
            return this.top == top && this.color == color && this.depth == depth && this.atlas == atlas.view() && atlasSampler == atlas.sampler()
                    && output == outputView;
        }

        @Override public void close() {
            if (pool != 0) vkDestroyDescriptorPool(context.device(), pool, null);
            pool = set = 0;
        }
    }

    private final class Dispatch implements BlazeQueue.Operation {
        private final Binding binding;
        private final BlazeRayScene.Frame scene;
        private final Lighting lighting;
        private final int width, height;

        private final BlazeImageUse images = new BlazeImageUse();

        Dispatch(Binding binding, BlazeRayScene.Frame scene, Lighting lighting, int width, int height) {
            this.binding = binding;
            this.scene = scene;
            this.lighting = lighting;
            this.width = width;
            this.height = height;

        }

        @Override public void record(VkCommandBuffer command) {
            images.transition(command, binding.color, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            images.transition(command, binding.depth, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            images.transition(command, binding.atlas, binding.atlasLayout);
            images.transition(command, binding.output, VK_IMAGE_LAYOUT_GENERAL);
            try (MemoryStack stack = VulkanStack.push()) {
                var push = stack.malloc(BlazeRayShader.PUSH_BYTES);
                lighting.inverseViewProjection().get(0, push);
                for (int i = 0; i < 4; i++) push.putFloat(64 + i * 4, lighting.light()[i]).putFloat(80 + i * 4, lighting.sky()[i]);
                push.putInt(96, width).putInt(100, height).putLong(104, scene.table());
                push.putInt(112, lighting.flags()).putInt(116, lighting.frame()).putFloat(120, lighting.distance()).putInt(124, scene.instances());
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
