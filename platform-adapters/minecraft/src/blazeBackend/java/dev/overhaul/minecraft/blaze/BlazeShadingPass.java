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

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Runs {@link BlazeShadingShader} over the main target once terrain is drawn: it reads the target's colour and depth
 * and the surfaces beside them, writes the lit picture to an image of its own, since colour targets cannot be written
 * as storage, and copies it back over the colour. One pass is one variant, traced or plain, finishing the picture or
 * leaving linear light in an image {@link BlazeShadingGlow} gives it; the pipeline lives as long as the pass, the
 * output follows the target's size.
 */
final class BlazeShadingPass implements AutoCloseable {
    private final BlazeRenderer owner;
    private final VulkanContext context;
    private final boolean traced, linear;
    private final GpuSampler sampler;
    private long setLayout, pipelineLayout, pipeline;
    private GpuImage output;
    private GpuImageView outputView;
    private Binding binding;

    BlazeShadingPass(BlazeRenderer owner, boolean traced, boolean linear) {
        this.owner = owner;
        this.traced = traced;
        this.linear = linear;
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

    private int[] sampled() {
        return traced ? new int[]{1, 2, 3, 5} : new int[]{1, 2, 5};
    }

    private void create() {
        VkDevice device = context.device();
        try (MemoryStack stack = VulkanStack.push(); ShadercCompiler compiler = new ShadercCompiler(true)) {
            var pointer = stack.mallocLong(1);
            int[] sampled = sampled();
            var bindings = VkDescriptorSetLayoutBinding.calloc(sampled.length + (traced ? 2 : 1), stack);
            int at = 0;
            for (int slot : sampled) bindings.get(at++).binding(slot).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(at++).binding(4).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            if (traced) bindings.get(at).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            BlazeFsr2Pipelines.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, pointer), "shading layout");
            setLayout = pointer.get(0);
            var range = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(BlazeShadingShader.PUSH_BYTES);
            BlazeFsr2Pipelines.check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(range),
                    null, pointer), "shading pipeline layout");
            pipelineLayout = pointer.get(0);
            var code = compiler.compile("overhaul_shading" + (traced ? "_traced" : "") + (linear ? "_linear" : ""), BlazeShadingShader.source(traced, linear), ShaderStage.COMPUTE).directBuffer();
            BlazeFsr2Pipelines.check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, pointer), "shading module");
            long module = pointer.get(0);
            try {
                var info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(pipelineLayout);
                info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
                BlazeFsr2Pipelines.check(vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, pointer), "shading pipeline");
                pipeline = pointer.get(0);
            } finally {
                vkDestroyShaderModule(device, module, null);
            }
        }
    }

    /**
     * What one frame's lighting reads beyond the target: the sun or moon and the sky as {@link BlazeRayTracing} gives
     * them, the fog's colour, start and end, and where traced shadows reach.
     */
    record Lighting(Matrix4f inverseViewProjection, float[] light, float[] sky, float[] fogColor, float fogStart, float fogEnd, float reach) {}

    /**
     * Queues the lighting of {@code target} from {@code surfaces}. A finishing pass then copies the result over the
     * target's colour; a linear one writes {@code into} and leaves the colour as it is.
     */
    void shade(BlazeTarget target, GpuImageView surfaces, BlazeTextures.Sampled atlas, BlazeRayScene.Frame scene, Lighting lighting, GpuImageView into) {
        int width = target.width(), height = target.height();
        if (!linear && (output == null || output.width(0) != width || output.height(0) != height)) {
            if (binding != null) owner.retire(binding);
            if (output != null) owner.retire(output);
            binding = null;
            output = null;
            output = owner.allocator().createImage(ImageFormat.RGBA8_UNORM, width, height, 1, 1,
                    VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
            outputView = output.view(0, 1, 0, 1);
        }
        GpuImageView[] views = traced ? new GpuImageView[]{target.colorView(), target.sampledDepthView(), atlas.view(), surfaces}
                : new GpuImageView[]{target.colorView(), target.sampledDepthView(), surfaces};
        long[] samplers = traced ? new long[]{sampler.handle(), sampler.handle(), atlas.sampler(), sampler.handle()}
                : new long[]{sampler.handle(), sampler.handle(), sampler.handle()};
        BlazeRayStructures.Structure top = traced ? scene.top() : null;
        GpuImageView written = linear ? into : outputView;
        if (binding == null || !binding.reads(top, views, samplers, written)) {
            if (binding != null) owner.retire(binding);
            binding = null;
            binding = new Binding(top, views, samplers, traced && atlas.general(), written);
        }
        owner.enqueue(new Dispatch(binding, traced ? scene.table() : 0, lighting, width, height));
        if (!linear) owner.copyImage(output, ImageRegion.whole(output, 0, 0), target.color(), ImageRegion.whole(target.color(), 0, 0));
    }

    /** Fills the shaders' {@code Frame}: the camera, the lights, the size and two more words, then the fog and the reach. */
    static void frame(java.nio.ByteBuffer push, Lighting lighting, int width, int height, long words) {
        lighting.inverseViewProjection().get(0, push);
        for (int i = 0; i < 4; i++) push.putFloat(64 + i * 4, lighting.light()[i]).putFloat(80 + i * 4, lighting.sky()[i]);
        push.putInt(96, width).putInt(100, height).putLong(104, words);
        float[] fog = lighting.fogColor();
        int packed = 0;
        for (int i = 0; i < 4; i++) packed |= Math.round(Math.max(0, Math.min(1, fog[i])) * 255) << (i * 8);
        push.putInt(112, packed).putFloat(116, lighting.fogStart()).putFloat(120, lighting.reach()).putFloat(124, lighting.fogEnd());
    }

    /** One descriptor set over a target's views, the surfaces, and for the traced variant the scene and the atlas. */
    private final class Binding implements AutoCloseable {
        private final BlazeRayStructures.Structure top;
        private final GpuImageView[] views;
        private final long[] samplers;
        private final List<Integer> layouts = new ArrayList<>();
        private final GpuImageView written;
        private long pool, set;

        Binding(BlazeRayStructures.Structure top, GpuImageView[] views, long[] samplers, boolean atlasGeneral, GpuImageView written) {
            this.top = top;
            this.views = views;
            this.samplers = samplers;
            this.written = written;
            int[] slots = sampled();
            VkDevice device = context.device();
            try (MemoryStack stack = VulkanStack.push()) {
                var pointer = stack.mallocLong(1);
                var sizes = VkDescriptorPoolSize.calloc(traced ? 3 : 2, stack);
                sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(slots.length);
                sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
                if (traced) sizes.get(2).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
                BlazeFsr2Pipelines.check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, pointer), "shading pool");
                pool = pointer.get(0);
                BlazeFsr2Pipelines.check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), pointer),
                        "shading set");
                set = pointer.get(0);
                var writes = VkWriteDescriptorSet.calloc(slots.length + (traced ? 2 : 1), stack);
                for (int i = 0; i < slots.length; i++) {
                    int layout = slots[i] == 3 && atlasGeneral ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                    layouts.add(layout);
                    writes.get(i).sType$Default().dstSet(set).dstBinding(slots[i]).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(samplers[i]).imageView(views[i].handle()).imageLayout(layout));
                }
                writes.get(slots.length).sType$Default().dstSet(set).dstBinding(4).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(written.handle()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
                if (traced) {
                    var structure = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default().pAccelerationStructures(stack.longs(top.handle()));
                    writes.get(slots.length + 1).sType$Default().pNext(structure.address()).dstSet(set).dstBinding(0).descriptorCount(1)
                            .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
                }
                vkUpdateDescriptorSets(device, writes, null);
            }
        }

        boolean reads(BlazeRayStructures.Structure top, GpuImageView[] views, long[] samplers, GpuImageView written) {
            return this.top == top && java.util.Arrays.equals(this.views, views) && java.util.Arrays.equals(this.samplers, samplers) && this.written == written;
        }

        void transition(VkCommandBuffer command, BlazeImageUse images) {
            for (int i = 0; i < views.length; i++) images.transition(command, views[i], layouts.get(i));
            images.transition(command, written, VK_IMAGE_LAYOUT_GENERAL);
        }

        @Override public void close() {
            if (pool != 0) vkDestroyDescriptorPool(context.device(), pool, null);
            pool = set = 0;
        }
    }

    private final class Dispatch implements BlazeQueue.Operation {
        private final Binding binding;
        private final long table;
        private final Lighting lighting;
        private final int width, height;
        private final BlazeImageUse images = new BlazeImageUse();

        Dispatch(Binding binding, long table, Lighting lighting, int width, int height) {
            this.binding = binding;
            this.table = table;
            this.lighting = lighting;
            this.width = width;
            this.height = height;
        }

        @Override public void record(VkCommandBuffer command) {
            binding.transition(command, images);
            try (MemoryStack stack = VulkanStack.push()) {
                var push = stack.malloc(BlazeShadingShader.PUSH_BYTES);
                frame(push, lighting, width, height, table);
                vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(binding.set), null);
                vkCmdPushConstants(command, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
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
