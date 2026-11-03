package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.List;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** Minecraft-independent, complete single-subpass graphics-state construction. */
public final class NativeGraphicsPipeline implements AutoCloseable {
    public record Shader(int stage,String entryPoint,ByteBuffer code) {
        public Shader {
            if(code.remaining()<20 || code.remaining()%4!=0 || entryPoint==null || entryPoint.isEmpty())throw new IllegalArgumentException("Invalid SPIR-V stage");
            ByteBuffer copy=ByteBuffer.allocateDirect(code.remaining()).order(ByteOrder.nativeOrder());copy.put(code.duplicate()).flip();code=copy.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
            if(code.getInt(0)!=0x07230203)throw new IllegalArgumentException("Invalid SPIR-V magic");
        }
        @Override public ByteBuffer code(){return code.duplicate().order(ByteOrder.nativeOrder());}
    }
    public record Binding(int slot,int stride,int stepRate) {}
    public record Attribute(int location,int slot,int format,int offset) {}
    public record Depth(boolean test,boolean write,int compare,float constantBias,float slopeBias) {}
    public record Color(boolean blend,int sourceRgb,int destinationRgb,int operationRgb,int sourceAlpha,int destinationAlpha,int operationAlpha,int writeMask) {}
    public record ColorLogic(boolean enabled,int operation){
        public ColorLogic{if(operation<VK_LOGIC_OP_CLEAR||operation>VK_LOGIC_OP_SET)throw new IllegalArgumentException("Invalid color logic operation");}
        public static ColorLogic disabled(){return new ColorLogic(false,VK_LOGIC_OP_COPY);}
    }
    public record Description(List<Shader> shaders,List<Binding> bindings,List<Attribute> attributes,long[] descriptorLayouts,
                              int pushConstantsSize,int topology,int polygonMode,int cullMode,int frontFace,Depth depth,
                              List<Color> colors,boolean dynamicViewport,int width,int height,ColorLogic colorLogic) {
        public Description(List<Shader> shaders,List<Binding> bindings,List<Attribute> attributes,long[] descriptorLayouts,
                           int pushConstantsSize,int topology,int polygonMode,int cullMode,int frontFace,Depth depth,
                           List<Color> colors,boolean dynamicViewport,int width,int height){
            this(shaders,bindings,attributes,descriptorLayouts,pushConstantsSize,topology,polygonMode,cullMode,frontFace,depth,colors,dynamicViewport,width,height,ColorLogic.disabled());
        }
        public Description {
            shaders=List.copyOf(shaders);bindings=List.copyOf(bindings);attributes=List.copyOf(attributes);descriptorLayouts=descriptorLayouts.clone();colors=List.copyOf(colors);
            colorLogic=java.util.Objects.requireNonNull(colorLogic);
            if(pushConstantsSize<0 || pushConstantsSize%4!=0 || (!dynamicViewport&&(width<1||height<1)))throw new IllegalArgumentException("Invalid pipeline dimensions/constants");
        }
        @Override public long[] descriptorLayouts(){return descriptorLayouts.clone();}
    }
    private final VulkanContext context;
    private long pipeline,layout,cache;

    public NativeGraphicsPipeline(VulkanContext context,long renderPass,Description description) {
        this.context=context;context.device();
        validateFeatures(description);
        long[] modules=new long[description.shaders().size()];
        try(MemoryStack stack=MemoryStack.stackPush()) {
            LongBuffer pointer=stack.mallocLong(1);
            check(vkCreatePipelineCache(context.device(),VkPipelineCacheCreateInfo.calloc(stack).sType$Default(),null,pointer),"vkCreatePipelineCache");cache=pointer.get(0);
            VkPipelineLayoutCreateInfo pipelineLayout=VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(description.descriptorLayouts()));
            if(description.pushConstantsSize()>0)pipelineLayout.pPushConstantRanges(VkPushConstantRange.calloc(1,stack)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(description.pushConstantsSize()));
            check(vkCreatePipelineLayout(context.device(),pipelineLayout,null,pointer),"vkCreatePipelineLayout");layout=pointer.get(0);
            VkPipelineShaderStageCreateInfo.Buffer stages=VkPipelineShaderStageCreateInfo.calloc(modules.length,stack);
            for(int index=0;index<modules.length;index++){
                Shader shader=description.shaders().get(index);
                check(vkCreateShaderModule(context.device(),VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(shader.code()),null,pointer),"vkCreateShaderModule");
                modules[index]=pointer.get(0);
                stages.get(index).sType$Default().stage(shader.stage()).module(modules[index]).pName(stack.UTF8(shader.entryPoint()));
            }
            VkVertexInputBindingDescription.Buffer bindings=VkVertexInputBindingDescription.calloc(description.bindings().size(),stack);
            int divisorCount=(int)description.bindings().stream().filter(binding->binding.stepRate()>1).count();
            VkVertexInputBindingDivisorDescriptionEXT.Buffer divisors=VkVertexInputBindingDivisorDescriptionEXT.calloc(divisorCount,stack);
            int divisor=0;
            for(int index=0;index<description.bindings().size();index++){
                Binding value=description.bindings().get(index);
                bindings.get(index).binding(value.slot()).stride(value.stride()).inputRate(value.stepRate()>0?VK_VERTEX_INPUT_RATE_INSTANCE:VK_VERTEX_INPUT_RATE_VERTEX);
                if(value.stepRate()>1)divisors.get(divisor++).binding(value.slot()).divisor(value.stepRate());
            }
            VkVertexInputAttributeDescription.Buffer attributes=VkVertexInputAttributeDescription.calloc(description.attributes().size(),stack);
            for(int index=0;index<description.attributes().size();index++){
                Attribute value=description.attributes().get(index);attributes.get(index).location(value.location()).binding(value.slot()).format(value.format()).offset(value.offset());
            }
            VkPipelineVertexInputStateCreateInfo input=VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default().pVertexBindingDescriptions(bindings).pVertexAttributeDescriptions(attributes);
            if(divisorCount>0)input.pNext(VkPipelineVertexInputDivisorStateCreateInfoEXT.calloc(stack).sType$Default().pVertexBindingDivisors(divisors));
            VkPipelineInputAssemblyStateCreateInfo assembly=VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(description.topology());
            VkPipelineViewportStateCreateInfo viewport=VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1);
            if(!description.dynamicViewport()){
                viewport.pViewports(VkViewport.calloc(1,stack).width(description.width()).height(description.height()).minDepth(0).maxDepth(1));
                VkRect2D.Buffer scissor=VkRect2D.calloc(1,stack);scissor.extent().set(description.width(),description.height());viewport.pScissors(scissor);
            }
            Depth value=description.depth();
            VkPipelineRasterizationStateCreateInfo raster=VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                    .polygonMode(description.polygonMode()).cullMode(description.cullMode()).frontFace(description.frontFace()).lineWidth(1)
                    .depthBiasEnable(value.constantBias()!=0||value.slopeBias()!=0).depthBiasConstantFactor(value.constantBias()).depthBiasSlopeFactor(value.slopeBias());
            VkPipelineMultisampleStateCreateInfo samples=VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineDepthStencilStateCreateInfo depth=VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                    .depthTestEnable(value.test()).depthWriteEnable(value.write()).depthCompareOp(value.compare()).stencilTestEnable(false);
            VkPipelineColorBlendAttachmentState.Buffer colors=VkPipelineColorBlendAttachmentState.calloc(description.colors().size(),stack);
            for(int index=0;index<description.colors().size();index++){
                Color color=description.colors().get(index);colors.get(index).blendEnable(color.blend()).srcColorBlendFactor(color.sourceRgb())
                        .dstColorBlendFactor(color.destinationRgb()).colorBlendOp(color.operationRgb()).srcAlphaBlendFactor(color.sourceAlpha())
                        .dstAlphaBlendFactor(color.destinationAlpha()).alphaBlendOp(color.operationAlpha()).colorWriteMask(color.writeMask());
            }
            VkPipelineColorBlendStateCreateInfo blending=VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(colors)
                    .logicOpEnable(description.colorLogic().enabled()).logicOp(description.colorLogic().operation());
            VkGraphicsPipelineCreateInfo.Buffer info=VkGraphicsPipelineCreateInfo.calloc(1,stack).sType$Default().pStages(stages)
                    .pVertexInputState(input).pInputAssemblyState(assembly).pViewportState(viewport).pRasterizationState(raster)
                    .pMultisampleState(samples).pDepthStencilState(depth).pColorBlendState(blending).layout(layout).renderPass(renderPass).subpass(0);
            if(description.dynamicViewport())info.pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default().pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR)));
            check(vkCreateGraphicsPipelines(context.device(),cache,info,null,pointer),"vkCreateGraphicsPipelines");pipeline=pointer.get(0);
        }catch(RuntimeException|Error failure){close();throw failure;}
        finally{for(long module:modules)if(module!=0)vkDestroyShaderModule(context.device(),module,null);}
    }
    private void validateFeatures(Description description){
        if(description.colorLogic().enabled()&&!context.logicOp())throw new UnsupportedOperationException("Color logic operations are unavailable");
        if(description.polygonMode()!=VK_POLYGON_MODE_FILL&&!context.fillModeNonSolid())throw new UnsupportedOperationException("Non-solid fill mode is unavailable");
        if(description.bindings().stream().anyMatch(binding->binding.stepRate()>1)&&!context.vertexAttributeDivisor())throw new UnsupportedOperationException("Vertex instance divisors are unavailable");
        if(!context.independentBlend()&&description.colors().stream().distinct().count()>1)throw new UnsupportedOperationException("Independent MRT blend state is unavailable");
    }
    public long handle(){context.device();if(pipeline==0)throw new IllegalStateException("Pipeline closed");return pipeline;}
    public long layout(){context.device();if(layout==0)throw new IllegalStateException("Pipeline closed");return layout;}
    public byte[] cacheData(){
        context.device();if(cache==0)throw new IllegalStateException("Pipeline closed");
        try(MemoryStack stack=MemoryStack.stackPush()){
            org.lwjgl.PointerBuffer size=stack.mallocPointer(1);check(vkGetPipelineCacheData(context.device(),cache,size,null),"pipeline cache size");
            if(size.get(0)>Integer.MAX_VALUE)throw new IllegalStateException("Pipeline cache too large");
            ByteBuffer data=org.lwjgl.system.MemoryUtil.memAlloc((int)size.get(0));
            try{check(vkGetPipelineCacheData(context.device(),cache,size,data),"pipeline cache data");byte[] result=new byte[(int)size.get(0)];data.get(result);return result;}
            finally{org.lwjgl.system.MemoryUtil.memFree(data);}
        }
    }
    @Override public void close(){context.device();if(pipeline!=0)vkDestroyPipeline(context.device(),pipeline,null);if(layout!=0)vkDestroyPipelineLayout(context.device(),layout,null);if(cache!=0)vkDestroyPipelineCache(context.device(),cache,null);pipeline=layout=cache=0;}
}
