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
 * and the surfaces beside them, and lights the colour where it lies when
 * its image can be written as storage; otherwise it writes the lit picture to an image of its own and copies that
 * back over the colour. One pass is one variant, traced or plain, alone or working with a {@link BlazeShadingGlow}:
 * then it also leaves linear light at half size and reads the glow and the measure made of the frame before. The
 * pipeline lives as long as the pass; the image of its own follows the target's size.
 */
final class BlazeShadingPass implements AutoCloseable {
    private final BlazeRenderer owner;
    private final VulkanContext context;
    private final boolean traced, linear, shafts, occlusion, colored;
    private final GpuSampler sampler;
    private long setLayout, pipelineLayout, pipeline;
    private GpuImage output;
    private GpuImageView outputView;
    private Binding binding;

    BlazeShadingPass(BlazeRenderer owner, boolean traced, boolean linear, boolean shafts, boolean occlusion, boolean colored) {
        this.owner = owner;
        this.traced = traced;
        this.linear = linear;
        this.shafts = shafts;
        this.occlusion = occlusion;
        this.colored = colored;
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
        int[] slots = new int[2 + (traced ? 1 : 0) + (linear ? 2 : 0) + (colored ? 2 : 0)];
        int at = 0;
        slots[at++] = 1;
        slots[at++] = 2;
        if (traced) slots[at++] = 3;
        if (linear) {
            slots[at++] = 6;
            slots[at++] = 7;
        }
        if (colored) {
            slots[at++] = 9;
            slots[at] = 10;
        }
        return slots;
    }

    /** The images written: the screen colour, the surfaces, then with a glow the half size light. */
    private int[] stored() {
        return linear ? new int[]{4, 5, 8} : new int[]{4, 5};
    }

    private void create() {
        VkDevice device = context.device();
        try (MemoryStack stack = VulkanStack.push(); ShadercCompiler compiler = new ShadercCompiler(true)) {
            var pointer = stack.mallocLong(1);
            int[] sampled = sampled(), stored = stored();
            var bindings = VkDescriptorSetLayoutBinding.calloc(sampled.length + stored.length + (traced ? 1 : 0), stack);
            int at = 0;
            for (int slot : sampled) bindings.get(at++).binding(slot).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            for (int slot : stored) bindings.get(at++).binding(slot).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            if (traced) bindings.get(at).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            BlazeFsr2Pipelines.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, pointer), "shading layout");
            setLayout = pointer.get(0);
            var range = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(BlazeShadingShader.PUSH_BYTES);
            BlazeFsr2Pipelines.check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(range),
                    null, pointer), "shading pipeline layout");
            pipelineLayout = pointer.get(0);
            var code = compiler.compile("overhaul_shading" + (traced ? "_traced" : "") + (linear ? "_linear" : "") + (shafts ? "_shafts" : "") + (occlusion ? "_occlusion" : "") + (colored ? "_colored" : ""), BlazeShadingShader.source(traced, linear, shafts, occlusion, colored), ShaderStage.COMPUTE).directBuffer();
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
     * them, the fog's colour, start and end, how far shadows are looked for: traced, or walked on screen by the plain variant,
     * and {@link BlazeShadingClouds}' words.
     */
    record Lighting(Matrix4f inverseViewProjection, float[] light, float[] sky, float[] fogColor, float fogStart, float fogEnd, float reach, long clouds, int place, BlazeLightVolume.Sampled colors) {
        /** A camera position as the shaders read it: where it is in a cube of sixteen blocks, a sixty-fourth of a block fine. */
        static int place(double x, double y, double z) {
            return part(x) | part(y) << 10 | part(z) << 20;
        }

        private static int part(double value) {
            return (int) ((value - Math.floor(value / 16) * 16) * 64) & 1023;
        }

        /** Both ends of the fog in one word, a sixteenth of a block fine. */
        int fog() {
            return sixteenths(fogStart) | sixteenths(fogEnd) << 16;
        }

        /** The reach's bits, with the light volume's word for the camera in the lowest byte, which a reach in whole blocks leaves empty. */
        int reachBits() {
            return Float.floatToRawIntBits((float) Math.round(reach)) & ~0xFF | (colors == null ? 0 : colors.cell());
        }

        private static int sixteenths(float blocks) {
            return Math.round(Math.max(0, Math.min(4095, blocks)) * 16);
        }
    }

    /**
     * Queues the lighting of {@code target}'s colour from {@code surfaces}, which it leaves wiped where terrain was.
     * A linear pass reads and writes the images {@code glow} gives it.
     */
    void shade(BlazeTarget target, GpuImageView surfaces, BlazeTextures.Sampled atlas, BlazeRayScene.Frame scene, Lighting lighting, BlazeShadingGlow.Light glow) {
        int width = target.width(), height = target.height();
        // Each pixel is read and written by the one invocation that lights it, so a colour image that can be stored to
        // is lit where it lies; any other is lit into an image of this pass and copied over.
        boolean inPlace = (target.color().usage() & VK_IMAGE_USAGE_STORAGE_BIT) != 0;
        if (!inPlace && (output == null || output.width(0) != width || output.height(0) != height)) {
            if (binding != null) owner.retire(binding);
            if (output != null) owner.retire(output);
            binding = null;
            output = null;
            output = owner.allocator().createImage(ImageFormat.RGBA8_UNORM, width, height, 1, 1,
                    VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
            outputView = output.view(0, 1, 0, 1);
        }
        List<GpuImageView> views = new ArrayList<>(List.of(target.colorView(), target.sampledDepthView()));
        List<Long> samplers = new ArrayList<>(List.of(sampler.handle(), sampler.handle()));
        if (traced) {
            views.add(atlas.view());
            samplers.add(atlas.sampler());
        }
        if (linear) {
            views.add(glow.glow());
            samplers.add(glow.smooth());
            views.add(glow.measured());
            samplers.add(sampler.handle());
        }
        if (colored) {
            views.add(lighting.colors().view());
            samplers.add(lighting.colors().sampler());
            views.add(lighting.colors().blocks());
            samplers.add(lighting.colors().exact());
        }
        GpuImageView lit = inPlace ? target.colorView() : outputView;
        List<GpuImageView> written = linear ? List.of(lit, surfaces, glow.spread()) : List.of(lit, surfaces);
        BlazeRayStructures.Structure top = traced ? scene.top() : null;
        if (binding == null || !binding.reads(top, views, samplers, written)) {
            if (binding != null) owner.retire(binding);
            binding = null;
            binding = new Binding(top, views, samplers, traced && atlas.general(), inPlace, written);
        }
        owner.enqueue(new Dispatch(binding, traced ? scene.table() : lighting.clouds(), lighting, width, height));
        if (!inPlace) owner.copyImage(output, ImageRegion.whole(output, 0, 0), target.color(), ImageRegion.whole(target.color(), 0, 0));
    }

    /** Fills the shaders' {@code Frame}: the camera, the lights, the size and two more words, then the fog, the reach and the camera's place. */
    static void frame(java.nio.ByteBuffer push, Lighting lighting, int width, int height, long words) {
        lighting.inverseViewProjection().get(0, push);
        for (int i = 0; i < 4; i++) push.putFloat(64 + i * 4, lighting.light()[i]).putFloat(80 + i * 4, lighting.sky()[i]);
        push.putInt(96, width).putInt(100, height).putLong(104, words);
        float[] fog = lighting.fogColor();
        int packed = 0;
        for (int i = 0; i < 4; i++) packed |= Math.round(Math.max(0, Math.min(1, fog[i])) * 255) << (i * 8);
        push.putInt(112, packed).putInt(116, lighting.fog()).putInt(120, lighting.reachBits()).putInt(124, lighting.place());
    }

    /** One descriptor set over a target's views, the surfaces, the images written, and for the traced variant the scene and the atlas. */
    private final class Binding implements AutoCloseable {
        private final BlazeRayStructures.Structure top;
        private final List<GpuImageView> views, written;
        private final List<Long> samplers;
        private final List<Integer> layouts = new ArrayList<>();
        private long pool, set;

        Binding(BlazeRayStructures.Structure top, List<GpuImageView> views, List<Long> samplers, boolean atlasGeneral, boolean colorGeneral, List<GpuImageView> written) {
            this.top = top;
            this.views = views;
            this.samplers = samplers;
            this.written = written;
            int[] slots = sampled(), stored = stored();
            VkDevice device = context.device();
            try (MemoryStack stack = VulkanStack.push()) {
                var pointer = stack.mallocLong(1);
                var sizes = VkDescriptorPoolSize.calloc(traced ? 3 : 2, stack);
                sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(slots.length);
                sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(stored.length);
                if (traced) sizes.get(2).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
                BlazeFsr2Pipelines.check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, pointer), "shading pool");
                pool = pointer.get(0);
                BlazeFsr2Pipelines.check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), pointer),
                        "shading set");
                set = pointer.get(0);
                var writes = VkWriteDescriptorSet.calloc(slots.length + stored.length + (traced ? 1 : 0), stack);
                int at = 0;
                for (int i = 0; i < slots.length; i++) {
                    // An image read and written at once has one layout for both.
                    int layout = slots[i] == 3 && atlasGeneral || slots[i] == 1 && colorGeneral ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                    layouts.add(layout);
                    writes.get(at++).sType$Default().dstSet(set).dstBinding(slots[i]).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(samplers.get(i)).imageView(views.get(i).handle()).imageLayout(layout));
                }
                for (int i = 0; i < stored.length; i++) writes.get(at++).sType$Default().dstSet(set).dstBinding(stored[i]).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(written.get(i).handle()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
                if (traced) {
                    var structure = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default().pAccelerationStructures(stack.longs(top.handle()));
                    writes.get(at).sType$Default().pNext(structure.address()).dstSet(set).dstBinding(0).descriptorCount(1)
                            .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
                }
                vkUpdateDescriptorSets(device, writes, null);
            }
        }

        boolean reads(BlazeRayStructures.Structure top, List<GpuImageView> views, List<Long> samplers, List<GpuImageView> written) {
            return this.top == top && this.views.equals(views) && this.samplers.equals(samplers) && this.written.equals(written);
        }

        void transition(VkCommandBuffer command, BlazeImageUse images) {
            for (int i = 0; i < views.size(); i++) images.transition(command, views.get(i), layouts.get(i));
            for (GpuImageView view : written) images.transition(command, view, VK_IMAGE_LAYOUT_GENERAL);
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
                vkCmdDispatch(command, (width + 15) / 16, (height + 15) / 16, 1);
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
