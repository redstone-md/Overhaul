package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.SpirvBinary;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** Compact xyz/material vertex layout, descriptor camera, and driver-owned pipeline cache. */
final class IndexedPipeline implements AutoCloseable {
    private static final String VERTEX = """
        #version 450
        layout(location=0) in vec3 position;
        layout(location=1) in uint material;
        layout(location=2) in vec3 instanceOffset;
        layout(set=0,binding=0) uniform Camera { mat4 transform; } camera;
        layout(location=0) flat out uint surface;
        void main() { gl_Position=camera.transform*vec4(position+instanceOffset,1.0); surface=material; }
        """;
    private static final String FRAGMENT = """
        #version 450
        layout(location=0) flat in uint surface;
        layout(location=0) out vec4 color;
        void main() {
            uint m=surface & 65535u;
            color=vec4(0.2+float((m*73u)%181u)/255.0,
                       0.3+float((m*37u)%151u)/255.0,
                       0.2+float((m*19u)%181u)/255.0,1.0);
        }
        """;
    private final VulkanContext context;
    long pipeline;
    long layout;
    private long cache;

    IndexedPipeline(VulkanContext context, long renderPass, long descriptorLayout, int width, int height) {
        this.context = context;
        long vertex = 0, fragment = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pointer = stack.mallocLong(1);
            check(vkCreatePipelineCache(context.device(), VkPipelineCacheCreateInfo.calloc(stack).sType$Default(),
                null, pointer), "vkCreatePipelineCache");
            cache = pointer.get(0);
            check(vkCreatePipelineLayout(context.device(), VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default().pSetLayouts(stack.longs(descriptorLayout)), null, pointer), "vkCreatePipelineLayout");
            layout = pointer.get(0);
            try (ShadercCompiler compiler = new ShadercCompiler()) {
                vertex = shader(compiler.compile("overhaul-builtin.vert", VERTEX, ShaderStage.VERTEX));
                fragment = shader(compiler.compile("overhaul-builtin.frag", FRAGMENT, ShaderStage.FRAGMENT));
            }
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertex).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragment).pName(stack.UTF8("main"));
            VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(2, stack);
            binding.get(0).binding(0).stride(16).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            binding.get(1).binding(1).stride(16).inputRate(VK_VERTEX_INPUT_RATE_INSTANCE);
            VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(3, stack);
            attributes.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
            attributes.get(1).location(1).binding(0).format(VK_FORMAT_R32_UINT).offset(12);
            attributes.get(2).location(2).binding(1).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
            VkPipelineVertexInputStateCreateInfo input = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType$Default().pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attributes);
            VkPipelineInputAssemblyStateCreateInfo assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                .sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack).width(width).height(height).minDepth(0).maxDepth(1);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.extent().set(width, height);
            VkPipelineViewportStateCreateInfo viewportInfo = VkPipelineViewportStateCreateInfo.calloc(stack)
                .sType$Default().pViewports(viewport).pScissors(scissor);
            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType$Default().polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE)
                .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f);
            VkPipelineMultisampleStateCreateInfo samples = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                .sType$Default().depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS);
            VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            VkPipelineColorBlendStateCreateInfo blending = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType$Default().pAttachments(blend);
            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                .sType$Default().pStages(stages).pVertexInputState(input).pInputAssemblyState(assembly)
                .pViewportState(viewportInfo).pRasterizationState(raster).pMultisampleState(samples).pDepthStencilState(depth)
                .pColorBlendState(blending).layout(layout).renderPass(renderPass).subpass(0);
            check(vkCreateGraphicsPipelines(context.device(), cache, info, null, pointer), "vkCreateGraphicsPipelines");
            pipeline = pointer.get(0);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        } finally {
            if (vertex != 0) vkDestroyShaderModule(context.device(), vertex, null);
            if (fragment != 0) vkDestroyShaderModule(context.device(), fragment, null);
        }
    }

    private long shader(SpirvBinary binary) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pointer = stack.mallocLong(1);
            check(vkCreateShaderModule(context.device(), VkShaderModuleCreateInfo.calloc(stack)
                .sType$Default().pCode(binary.directBuffer()), null, pointer), "vkCreateShaderModule");
            return pointer.get(0);
        }
    }

    byte[] cacheData() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer size = stack.mallocPointer(1);
            check(vkGetPipelineCacheData(context.device(), cache, size, null), "pipeline cache size");
            if (size.get(0) > Integer.MAX_VALUE) throw new IllegalStateException("pipeline cache too large");
            ByteBuffer data = org.lwjgl.system.MemoryUtil.memAlloc((int) size.get(0));
            try {
                check(vkGetPipelineCacheData(context.device(), cache, size, data), "pipeline cache data");
                byte[] copy = new byte[(int) size.get(0)];
                data.get(copy);
                return copy;
            } finally { org.lwjgl.system.MemoryUtil.memFree(data); }
        }
    }

    @Override public void close() {
        context.requireOwner();
        if (pipeline != 0) vkDestroyPipeline(context.device(), pipeline, null);
        if (layout != 0) vkDestroyPipelineLayout(context.device(), layout, null);
        if (cache != 0) vkDestroyPipelineCache(context.device(), cache, null);
        pipeline = layout = cache = 0;
    }
}
