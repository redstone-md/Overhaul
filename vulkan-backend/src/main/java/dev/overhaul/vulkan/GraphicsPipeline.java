package dev.overhaul.vulkan;

import dev.overhaul.shader.compile.SpirvBinary;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** Shared graphics-state construction for built-in and translated pack shaders. */
final class GraphicsPipeline implements AutoCloseable {
    record Binding(int number,int stride,int rate) {}
    record Attribute(int location,int binding,int format,int offset) {}
    record Blend(boolean enabled,int sourceRgb,int destinationRgb,int sourceAlpha,int destinationAlpha) {
        static Blend disabled() { return new Blend(false,VK_BLEND_FACTOR_ONE,VK_BLEND_FACTOR_ZERO,VK_BLEND_FACTOR_ONE,VK_BLEND_FACTOR_ZERO); }
    }
    private final VulkanContext context;
    long pipeline;
    long layout;
    private long cache;

    GraphicsPipeline(VulkanContext context,long renderPass,long descriptorLayout,int width,int height,
                     SpirvBinary vertexBinary,SpirvBinary fragmentBinary,List<Binding> bindings,
                     List<Attribute> attributes,Blend blend) {
        this(context,renderPass,descriptorLayout,width,height,vertexBinary,fragmentBinary,bindings,attributes,blend,true);
    }

    GraphicsPipeline(VulkanContext context,long renderPass,long descriptorLayout,int width,int height,
                     SpirvBinary vertexBinary,SpirvBinary fragmentBinary,List<Binding> bindings,
                     List<Attribute> attributes,Blend blend,boolean depthWrite) {
        this.context = context;
        context.requireOwner();
        long vertex=0,fragment=0;
        try (MemoryStack stack=MemoryStack.stackPush()) {
            LongBuffer pointer=stack.mallocLong(1);
            check(vkCreatePipelineCache(context.device(),VkPipelineCacheCreateInfo.calloc(stack).sType$Default(),null,pointer),"vkCreatePipelineCache");
            cache=pointer.get(0);
            check(vkCreatePipelineLayout(context.device(),VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout)),null,pointer),"vkCreatePipelineLayout");
            layout=pointer.get(0);
            vertex=shader(vertexBinary); fragment=shader(fragmentBinary);
            VkPipelineShaderStageCreateInfo.Buffer stages=VkPipelineShaderStageCreateInfo.calloc(2,stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertex).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragment).pName(stack.UTF8("main"));
            VkVertexInputBindingDescription.Buffer binding=VkVertexInputBindingDescription.calloc(bindings.size(),stack);
            for (int i=0;i<bindings.size();i++) {
                Binding value=bindings.get(i); binding.get(i).binding(value.number()).stride(value.stride()).inputRate(value.rate());
            }
            VkVertexInputAttributeDescription.Buffer attribute=VkVertexInputAttributeDescription.calloc(attributes.size(),stack);
            for (int i=0;i<attributes.size();i++) {
                Attribute value=attributes.get(i); attribute.get(i).location(value.location()).binding(value.binding()).format(value.format()).offset(value.offset());
            }
            VkPipelineVertexInputStateCreateInfo input=VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
                    .pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attribute);
            VkPipelineInputAssemblyStateCreateInfo assembly=VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            VkViewport.Buffer viewport=VkViewport.calloc(1,stack).width(width).height(height).minDepth(0).maxDepth(1);
            VkRect2D.Buffer scissor=VkRect2D.calloc(1,stack); scissor.extent().set(width,height);
            VkPipelineViewportStateCreateInfo viewportInfo=VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                    .pViewports(viewport).pScissors(scissor);
            VkPipelineRasterizationStateCreateInfo raster=VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                    .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1);
            VkPipelineMultisampleStateCreateInfo samples=VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineDepthStencilStateCreateInfo depth=VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                    .depthTestEnable(true).depthWriteEnable(depthWrite).depthCompareOp(VK_COMPARE_OP_LESS);
            VkPipelineColorBlendAttachmentState.Buffer color=VkPipelineColorBlendAttachmentState.calloc(1,stack)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT|VK_COLOR_COMPONENT_G_BIT|VK_COLOR_COMPONENT_B_BIT|VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(blend.enabled()).srcColorBlendFactor(blend.sourceRgb()).dstColorBlendFactor(blend.destinationRgb())
                    .colorBlendOp(VK_BLEND_OP_ADD).srcAlphaBlendFactor(blend.sourceAlpha()).dstAlphaBlendFactor(blend.destinationAlpha()).alphaBlendOp(VK_BLEND_OP_ADD);
            VkPipelineColorBlendStateCreateInfo blending=VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(color);
            VkGraphicsPipelineCreateInfo.Buffer info=VkGraphicsPipelineCreateInfo.calloc(1,stack).sType$Default().pStages(stages)
                    .pVertexInputState(input).pInputAssemblyState(assembly).pViewportState(viewportInfo).pRasterizationState(raster)
                    .pMultisampleState(samples).pDepthStencilState(depth).pColorBlendState(blending).layout(layout).renderPass(renderPass).subpass(0);
            check(vkCreateGraphicsPipelines(context.device(),cache,info,null,pointer),"vkCreateGraphicsPipelines");
            pipeline=pointer.get(0);
        } catch (RuntimeException|Error failure) { close(); throw failure; }
        finally {
            if (vertex!=0) vkDestroyShaderModule(context.device(),vertex,null);
            if (fragment!=0) vkDestroyShaderModule(context.device(),fragment,null);
        }
    }

    private long shader(SpirvBinary binary) {
        try (MemoryStack stack=MemoryStack.stackPush()) {
            LongBuffer pointer=stack.mallocLong(1);
            check(vkCreateShaderModule(context.device(),VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(binary.directBuffer()),null,pointer),"vkCreateShaderModule");
            return pointer.get(0);
        }
    }
    byte[] cacheData() {
        context.requireOwner();
        if (cache==0) throw new IllegalStateException("Pipeline is closed");
        try (MemoryStack stack=MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer size=stack.mallocPointer(1);
            check(vkGetPipelineCacheData(context.device(),cache,size,null),"pipeline cache size");
            if (size.get(0)>Integer.MAX_VALUE) throw new IllegalStateException("Pipeline cache too large");
            ByteBuffer data=org.lwjgl.system.MemoryUtil.memAlloc((int)size.get(0));
            try {
                check(vkGetPipelineCacheData(context.device(),cache,size,data),"pipeline cache data");
                byte[] result=new byte[(int)size.get(0)]; data.get(result); return result;
            } finally { org.lwjgl.system.MemoryUtil.memFree(data); }
        }
    }
    @Override public void close() {
        context.requireOwner();
        if (pipeline!=0) vkDestroyPipeline(context.device(),pipeline,null);
        if (layout!=0) vkDestroyPipelineLayout(context.device(),layout,null);
        if (cache!=0) vkDestroyPipelineCache(context.device(),cache,null);
        pipeline=layout=cache=0;
    }
}
