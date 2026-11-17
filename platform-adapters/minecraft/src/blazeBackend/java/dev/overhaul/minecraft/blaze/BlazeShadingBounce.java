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
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Runs {@link BlazeShadingBounceShader} before the lighting pass: one dispatch at half the main target's size reads
 * the surfaces terrain wrote and the depth, traces the scene and writes the light that came back into one of two
 * histories, which take turns as last frame's and this frame's. The lighting pass then reads this frame's. The
 * pipeline lives as long as this pass; the histories follow the target's size.
 */
final class BlazeShadingBounce implements AutoCloseable {
    private final BlazeRenderer owner;
    private final VulkanContext context;
    private final boolean colored;
    private final GpuSampler sampler, historySampler;
    private long setLayout, pipelineLayout, pipeline;
    private final GpuImage[] histories = new GpuImage[2];
    private final GpuImageView[] historyViews = new GpuImageView[2];
    private final Binding[] bindings = new Binding[2];
    /** Which history this frame writes; whether the last frame wrote one; where the camera stood then. */
    private int turn, frame;
    private boolean gathered;
    private final Matrix4f previousViewProjection = new Matrix4f();
    private double previousX, previousY, previousZ;

    BlazeShadingBounce(BlazeRenderer owner, boolean colored) {
        this.owner = owner;
        this.colored = colored;
        context = owner.context();
        sampler = owner.allocator().createSampler(new SamplerSettings(SamplerSettings.Filter.NEAREST,
                SamplerSettings.Filter.NEAREST, SamplerSettings.Mipmap.NEAREST, SamplerSettings.Address.CLAMP,
                SamplerSettings.Address.CLAMP, SamplerSettings.Address.CLAMP, 0, 0, 1, SamplerSettings.Compare.NONE));
        historySampler = owner.allocator().createSampler(new SamplerSettings(SamplerSettings.Filter.LINEAR,
                SamplerSettings.Filter.LINEAR, SamplerSettings.Mipmap.NEAREST, SamplerSettings.Address.CLAMP,
                SamplerSettings.Address.CLAMP, SamplerSettings.Address.CLAMP, 0, 0, 1, SamplerSettings.Compare.NONE));
        try {
            create();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    /** Whether this pass reads the light volume. */
    boolean colored() {
        return colored;
    }

    private int[] sampled() {
        return colored ? new int[]{1, 2, 3, 5, 9, 10} : new int[]{1, 2, 3, 5};
    }

    private void create() {
        VkDevice device = context.device();
        try (MemoryStack stack = VulkanStack.push(); ShadercCompiler compiler = new ShadercCompiler(true)) {
            var pointer = stack.mallocLong(1);
            int[] sampled = sampled();
            var bindings = VkDescriptorSetLayoutBinding.calloc(sampled.length + 2, stack);
            int at = 0;
            for (int slot : sampled) bindings.get(at++).binding(slot).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(at++).binding(6).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(at).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            BlazeFsr2Pipelines.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, pointer), "bounce layout");
            setLayout = pointer.get(0);
            var range = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(BlazeShadingShader.PUSH_BYTES);
            BlazeFsr2Pipelines.check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(range),
                    null, pointer), "bounce pipeline layout");
            pipelineLayout = pointer.get(0);
            var code = compiler.compile("overhaul_shading_bounce" + (colored ? "_colored" : ""), BlazeShadingBounceShader.source(colored), ShaderStage.COMPUTE).directBuffer();
            BlazeFsr2Pipelines.check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, pointer), "bounce module");
            long module = pointer.get(0);
            try {
                var info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(pipelineLayout);
                info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
                BlazeFsr2Pipelines.check(vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, pointer), "bounce pipeline");
                pipeline = pointer.get(0);
            } finally {
                vkDestroyShaderModule(device, module, null);
            }
        }
    }

    /**
     * This frame's values for the hit table's trailer: last frame's view projection, where the camera stood then
     * relative to now and whether a history from it exists. Called once a frame before {@link #gather}; histories
     * that no longer fit the target are made again here, and a fresh history counts as none.
     */
    float[] trailer(int width, int height, Matrix4f viewProjection, double x, double y, double z) {
        boolean ready = gathered && fits(width, height);
        if (!ready) resize(width, height);
        float[] values = new float[BlazeRayShader.TRAILER_FLOATS];
        previousViewProjection.get(values);
        values[16] = (float) (x - previousX);
        values[17] = (float) (y - previousY);
        values[18] = (float) (z - previousZ);
        values[19] = ready ? 1 : 0;
        previousViewProjection.set(viewProjection);
        previousX = x;
        previousY = y;
        previousZ = z;
        gathered = false;
        return values;
    }

    private boolean fits(int width, int height) {
        return histories[0] != null && histories[0].width(0) == (width + 1) / 2 && histories[0].height(0) == (height + 1) / 2;
    }

    private void resize(int width, int height) {
        if (fits(width, height)) return;
        for (int i = 0; i < 2; i++) {
            if (bindings[i] != null) owner.retire(bindings[i]);
            if (histories[i] != null) owner.retire(histories[i]);
            bindings[i] = null;
            histories[i] = null;
            histories[i] = owner.allocator().createImage(ImageFormat.RGBA16_SFLOAT, (width + 1) / 2, (height + 1) / 2, 1, 1,
                    VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
            historyViews[i] = histories[i].view(0, 1, 0, 1);
        }
    }

    /**
     * Queues the gathering of the light that comes back to the terrain of {@code target}, whose {@code surfaces} are
     * still as terrain wrote them, and gives the image the lighting pass reads it from.
     */
    GpuImageView gather(BlazeTarget target, GpuImageView surfaces, BlazeTextures.Sampled atlas, BlazeRayScene.Frame scene, BlazeShadingPass.Lighting lighting) {
        int width = target.width(), height = target.height();
        resize(width, height);
        turn ^= 1;
        List<GpuImageView> views = new ArrayList<>(List.of(surfaces, target.sampledDepthView(), atlas.view(), historyViews[turn ^ 1]));
        List<Long> samplers = new ArrayList<>(List.of(sampler.handle(), sampler.handle(), atlas.sampler(), historySampler.handle()));
        if (colored) {
            views.add(lighting.colors().view());
            samplers.add(lighting.colors().sampler());
            views.add(lighting.colors().blocks());
            samplers.add(lighting.colors().exact());
        }
        Binding binding = bindings[turn];
        if (binding == null || !binding.reads(scene.top(), views, samplers, historyViews[turn])) {
            if (binding != null) owner.retire(binding);
            bindings[turn] = null;
            binding = bindings[turn] = new Binding(scene.top(), views, samplers, atlas.general(), historyViews[turn]);
        }
        owner.enqueue(new Dispatch(binding, scene, lighting, width, height, frame++));
        gathered = true;
        return historyViews[turn];
    }

    /** One descriptor set over the scene, what is read of the frame and the history written; closing it frees its pool. */
    private final class Binding implements AutoCloseable {
        private final BlazeRayStructures.Structure top;
        private final List<GpuImageView> views;
        private final List<Long> samplers;
        private final List<Integer> layouts = new ArrayList<>();
        private final GpuImageView accumulated;
        private long pool, set;

        Binding(BlazeRayStructures.Structure top, List<GpuImageView> views, List<Long> samplers, boolean atlasGeneral, GpuImageView accumulated) {
            this.top = top;
            this.views = views;
            this.samplers = samplers;
            this.accumulated = accumulated;
            int[] slots = sampled();
            VkDevice device = context.device();
            try (MemoryStack stack = VulkanStack.push()) {
                var pointer = stack.mallocLong(1);
                var sizes = VkDescriptorPoolSize.calloc(3, stack);
                sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(slots.length);
                sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
                sizes.get(2).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
                BlazeFsr2Pipelines.check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, pointer), "bounce pool");
                pool = pointer.get(0);
                BlazeFsr2Pipelines.check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), pointer),
                        "bounce set");
                set = pointer.get(0);
                var writes = VkWriteDescriptorSet.calloc(slots.length + 2, stack);
                int at = 0;
                for (int i = 0; i < slots.length; i++) {
                    int layout = slots[i] == 3 && atlasGeneral ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                    layouts.add(layout);
                    writes.get(at++).sType$Default().dstSet(set).dstBinding(slots[i]).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(samplers.get(i)).imageView(views.get(i).handle()).imageLayout(layout));
                }
                writes.get(at++).sType$Default().dstSet(set).dstBinding(6).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(accumulated.handle()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
                var structure = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default().pAccelerationStructures(stack.longs(top.handle()));
                writes.get(at).sType$Default().pNext(structure.address()).dstSet(set).dstBinding(0).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
                vkUpdateDescriptorSets(device, writes, null);
            }
        }

        boolean reads(BlazeRayStructures.Structure top, List<GpuImageView> views, List<Long> samplers, GpuImageView accumulated) {
            return this.top == top && this.views.equals(views) && this.samplers.equals(samplers) && this.accumulated == accumulated;
        }

        void transition(VkCommandBuffer command, BlazeImageUse images) {
            for (int i = 0; i < views.size(); i++) images.transition(command, views.get(i), layouts.get(i));
            images.transition(command, accumulated, VK_IMAGE_LAYOUT_GENERAL);
        }

        @Override public void close() {
            if (pool != 0) vkDestroyDescriptorPool(context.device(), pool, null);
            pool = set = 0;
        }
    }

    private final class Dispatch implements BlazeQueue.Operation {
        private final Binding binding;
        private final BlazeRayScene.Frame scene;
        private final BlazeShadingPass.Lighting lighting;
        private final int width, height, number;
        private final BlazeImageUse images = new BlazeImageUse();

        Dispatch(Binding binding, BlazeRayScene.Frame scene, BlazeShadingPass.Lighting lighting, int width, int height, int number) {
            this.binding = binding;
            this.scene = scene;
            this.lighting = lighting;
            this.width = width;
            this.height = height;
            this.number = number;
        }

        @Override public void record(VkCommandBuffer command) {
            binding.transition(command, images);
            try (MemoryStack stack = VulkanStack.push()) {
                var push = stack.malloc(BlazeShadingShader.PUSH_BYTES);
                BlazeShadingPass.frame(push, lighting, width, height, scene.table());
                // The fog's words are not read here: the frame's number and the scene's instances take their place.
                push.putInt(112, number).putInt(116, scene.instances());
                vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(binding.set), null);
                vkCmdPushConstants(command, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                vkCmdDispatch(command, ((width + 1) / 2 + 7) / 8, ((height + 1) / 2 + 7) / 8, 1);
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
        for (int i = 0; i < 2; i++) {
            if (bindings[i] != null) bindings[i].close();
            if (histories[i] != null) histories[i].close();
            bindings[i] = null;
            histories[i] = null;
        }
        VkDevice device = context.device();
        if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
        if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
        if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
        pipeline = pipelineLayout = setLayout = 0;
        sampler.close();
        historySampler.close();
    }
}
