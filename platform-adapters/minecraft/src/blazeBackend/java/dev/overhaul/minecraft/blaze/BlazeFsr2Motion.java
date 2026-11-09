package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.vulkan.FrameRing;
import dev.overhaul.vulkan.GpuAllocator;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.GpuSampler;
import dev.overhaul.vulkan.SamplerSettings;
import dev.overhaul.vulkan.VulkanContext;
import dev.overhaul.vulkan.VulkanStack;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Turns the game's depth into what FSR2 reads. The game clears depth before it draws the hand, so the world's depth
 * is copied aside first; afterwards one pass writes FSR's depth and the motion of every pixel from the world depth
 * and camera matrices, holding the hand still where it was drawn. The pipelines live as long as the renderer.
 */
final class BlazeFsr2Motion implements AutoCloseable {
    private static final int PUSH_BYTES = 96;
    private static final String SHADER = """
            #version 450
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(binding = 0) uniform sampler2D gameDepth;
            layout(binding = 1, r32f) uniform image2D worldDepth;
            layout(binding = 2, r32f) writeonly uniform image2D fsrDepth;
            layout(binding = 3, rg16f) writeonly uniform image2D motion;
            // planes: projection m22, m32, near, far; size: pixels, then 0 to copy the world depth or 1 for motion.
            layout(push_constant) uniform Frame { mat4 currentToPrevious; vec4 planes; ivec4 size; };
            float fsrDepthOf(float windowDepth) {
                if (windowDepth >= 1.0) return 1.0;
                float distance = planes.y / (windowDepth * 2.0 - 1.0 + planes.x);
                return clamp(planes.w / (planes.w - planes.z) * (1.0 - planes.z / distance), 0.0, 1.0);
            }
            void main() {
                ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
                if (any(greaterThanEqual(pixel, size.xy))) return;
                float depth = texelFetch(gameDepth, pixel, 0).r;
                if (size.z == 0) {
                    imageStore(worldDepth, pixel, vec4(depth));
                    return;
                }
                if (depth < 1.0) {
                    imageStore(fsrDepth, pixel, vec4(fsrDepthOf(depth)));
                    imageStore(motion, pixel, vec4(0.0));
                    return;
                }
                float world = imageLoad(worldDepth, pixel).r;
                vec2 uv = (vec2(pixel) + 0.5) / vec2(size.xy);
                vec4 previous = currentToPrevious * vec4(uv * 2.0 - 1.0, world * 2.0 - 1.0, 1.0);
                vec2 previousUv = previous.xy / previous.w * 0.5 + 0.5;
                imageStore(fsrDepth, pixel, vec4(fsrDepthOf(world)));
                imageStore(motion, pixel, vec4(previousUv - uv, 0.0, 0.0));
            }
            """;

    private final VulkanContext context;
    private final GpuSampler sampler;
    private long setLayout, pipelineLayout, pipeline;

    BlazeFsr2Motion(VulkanContext context, GpuAllocator allocator) {
        this.context = context;
        sampler = allocator.createSampler(new SamplerSettings(SamplerSettings.Filter.NEAREST,
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
        try (MemoryStack stack = VulkanStack.push(); ShadercCompiler compiler = new ShadercCompiler()) {
            var pointer = stack.mallocLong(1);
            var bindings = VkDescriptorSetLayoutBinding.calloc(4, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            for (int i = 1; i < 4; i++) bindings.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            BlazeFsr2Pipelines.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, pointer), "motion layout");
            setLayout = pointer.get(0);
            var range = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            BlazeFsr2Pipelines.check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(range),
                    null, pointer), "motion pipeline layout");
            pipelineLayout = pointer.get(0);
            var code = compiler.compile("overhaul_fsr2_motion", SHADER, ShaderStage.COMPUTE).directBuffer();
            BlazeFsr2Pipelines.check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, pointer), "motion module");
            long module = pointer.get(0);
            try {
                var info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(pipelineLayout);
                info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
                BlazeFsr2Pipelines.check(vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, pointer), "motion pipeline");
                pipeline = pointer.get(0);
            } finally {
                vkDestroyShaderModule(device, module, null);
            }
        }
    }

    /** A descriptor set over one game depth view and one context's images; closing it frees its pool. */
    final class Binding implements AutoCloseable {
        private final GpuImageView depth;
        private final BlazeFsr2Resources resources;
        private long pool, set;

        Binding(GpuImageView depth, BlazeFsr2Resources resources) {
            this.depth = depth;
            this.resources = resources;
            VkDevice device = context.device();
            try (MemoryStack stack = VulkanStack.push()) {
                var pointer = stack.mallocLong(1);
                var sizes = VkDescriptorPoolSize.calloc(2, stack);
                sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
                sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(3);
                BlazeFsr2Pipelines.check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, pointer), "motion pool");
                pool = pointer.get(0);
                BlazeFsr2Pipelines.check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), pointer),
                        "motion set");
                set = pointer.get(0);
                var writes = VkWriteDescriptorSet.calloc(4, stack);
                writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(sampler.handle()).imageView(depth.handle()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
                GpuImageView[] storage = {resources.view(BlazeFsr2Resources.Image.WORLD_DEPTH), resources.view(BlazeFsr2Resources.Image.INPUT_DEPTH),
                        resources.view(BlazeFsr2Resources.Image.INPUT_MOTION)};
                for (int i = 0; i < 3; i++) {
                    writes.get(i + 1).sType$Default().dstSet(set).dstBinding(i + 1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(storage[i].handle()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
                }
                vkUpdateDescriptorSets(device, writes, null);
            }
        }

        boolean reads(GpuImageView depth, BlazeFsr2Resources resources) {
            return this.depth == depth && this.resources == resources;
        }

        /** Queues the copy of the world's depth, made before the game clears depth for the hand. */
        void copyWorld(BlazeRenderer owner) {
            owner.enqueue(new Dispatch(this, null, null));
        }

        /**
         * Queues FSR's depth and motion.
         *
         * @param currentToPrevious maps this frame's unjittered NDC with world depth to the previous frame's clip space
         * @param planes the projection's m22 and m32, then the near and far planes
         */
        void motion(BlazeRenderer owner, Matrix4f currentToPrevious, float[] planes) {
            owner.enqueue(new Dispatch(this, new Matrix4f(currentToPrevious), planes.clone()));
        }

        @Override public void close() {
            if (pool != 0) vkDestroyDescriptorPool(context.device(), pool, null);
            pool = set = 0;
        }
    }

    private final class Dispatch implements BlazeQueue.Operation {
        private final Binding binding;
        private final Matrix4f matrix;
        private final float[] planes;
        private final BlazeImageUse images = new BlazeImageUse();

        private Dispatch(Binding binding, Matrix4f matrix, float[] planes) {
            this.binding = binding;
            this.matrix = matrix;
            this.planes = planes;
        }

        @Override public void record(VkCommandBuffer command) {
            BlazeFsr2Resources resources = binding.resources;
            images.transition(command, binding.depth, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            images.transition(command, resources.view(BlazeFsr2Resources.Image.WORLD_DEPTH), VK_IMAGE_LAYOUT_GENERAL);
            images.transition(command, resources.view(BlazeFsr2Resources.Image.INPUT_DEPTH), VK_IMAGE_LAYOUT_GENERAL);
            images.transition(command, resources.view(BlazeFsr2Resources.Image.INPUT_MOTION), VK_IMAGE_LAYOUT_GENERAL);
            int width = resources.renderWidth(), height = resources.renderHeight();
            try (MemoryStack stack = VulkanStack.push()) {
                var push = stack.malloc(PUSH_BYTES);
                (matrix != null ? matrix : new Matrix4f()).get(0, push);
                float[] values = planes != null ? planes : new float[4];
                for (int i = 0; i < 4; i++) push.putFloat(64 + i * 4, values[i]);
                push.putInt(80, width).putInt(84, height).putInt(88, matrix != null ? 1 : 0).putInt(92, 0);
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
        VkDevice device = context.device();
        if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
        if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
        if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
        pipeline = pipelineLayout = setLayout = 0;
        sampler.close();
    }
}
