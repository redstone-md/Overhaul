package dev.overhaul.vulkan;

import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.compat.UniformBlock;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.pack.CompiledShaderPack;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** Original pack fullscreen programs, immutable descriptor ABI and bounded framebuffer reuse. */
final class PackFullscreenPass implements AutoCloseable {
    private record Sample(long view,long sampler){}
    private static final int MAX_DESCRIPTOR_SETS=32;
    private final VulkanContext context;
    private final CompiledShaderPack.Program program;
    private final Map<List<Long>,Long> framebuffers=new LinkedHashMap<>();
    private final int width,height;
    private final List<Integer> formats;
    private final Map<Map<String,Sample>,Long> descriptors=new LinkedHashMap<>();
    private long renderPass,descriptorLayout,pool;
    private GpuBuffer uniforms,vertices,indices;
    private NativeGraphicsPipeline pipeline;
    private final ByteBuffer upload;
    final UniformBlock values;

    PackFullscreenPass(VulkanContext context,GpuAllocator allocator,CompiledShaderPack.Program program,List<Integer> formats,int width,int height){
        this.context=context;this.program=program;this.formats=List.copyOf(formats);this.width=width;this.height=height;
        values=new UniformBlock(program.code().descriptors());
        upload=ByteBuffer.allocateDirect(program.code().descriptors().uniformBytes()).order(ByteOrder.nativeOrder());
        program.code().requireAlphaTest(program.state().alphaTest().orElse(null));
        try{
            renderPass=createRenderPass();descriptors(allocator);
            PackVertexLayout layout=program.code().descriptors().attributes().isEmpty()?null:new PackVertexLayout(program.code().descriptors());
            if(layout!=null){vertices=allocator.hostBuffer(layout.stride*4,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);vertices.write(quad(layout));}
            indices=allocator.hostBuffer(24,VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
            ByteBuffer indexData=ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder());for(int index:new int[]{0,1,2,0,2,3})indexData.putInt(index);indexData.flip();indices.write(indexData);
            var shaders=List.of(new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_VERTEX_BIT,"main",program.code().vertex().directBuffer()),
                    new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_FRAGMENT_BIT,"main",program.code().fragment().directBuffer()));
            var bindings=layout==null?List.<NativeGraphicsPipeline.Binding>of():List.of(new NativeGraphicsPipeline.Binding(0,layout.stride,0));
            var attributes=layout==null?List.<NativeGraphicsPipeline.Attribute>of():layout.fields.stream().map(field->new NativeGraphicsPipeline.Attribute(field.location(),0,field.format(),field.offset())).toList();
            List<NativeGraphicsPipeline.Color> colors=new ArrayList<>();
            for(int index=0;index<formats.size();index++)colors.add(PackPipelineState.color(program.state(),formats.get(index)==VK_FORMAT_UNDEFINED?-1:
                    program.stage()==CompiledShaderPack.Stage.FINAL?0:program.code().framebuffer().colorTargets().get(index)));
            pipeline=new NativeGraphicsPipeline(context,renderPass,new NativeGraphicsPipeline.Description(shaders,bindings,attributes,new long[]{descriptorLayout},0,
                    VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,VK_POLYGON_MODE_FILL,VK_CULL_MODE_NONE,VK_FRONT_FACE_COUNTER_CLOCKWISE,
                    new NativeGraphicsPipeline.Depth(false,false,VK_COMPARE_OP_ALWAYS,0,0),colors,false,width,height));
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    private static ByteBuffer quad(PackVertexLayout layout){
        ByteBuffer data=ByteBuffer.allocateDirect(layout.stride*4).order(ByteOrder.nativeOrder());
        for(int vertex=0;vertex<4;vertex++)for(var field:layout.fields){
            float x=vertex==1||vertex==2?1:0,y=vertex>=2?1:0;
            for(int component=0;component<field.components();component++){
                float value=switch(field.name()){
                    case "overhaulLegacyVertex","Position"->component==0?x:component==1?y:component==3?1:0;
                    case "overhaulLegacyTexCoord0"->component==0?x:component==1?y:component==3?1:0;
                    case "overhaulLegacyColor"->1;
                    case "overhaulLegacyNormal"->component==2?1:0;
                    case "overhaulLegacyTexCoord1"->component==3?1:0;
                    default->throw new UnsupportedShaderException("Unknown fullscreen vertex attribute: "+field.name());
                };data.putFloat(vertex*layout.stride+field.offset()+component*4,value);
            }
        }return data;
    }
    private void descriptors(GpuAllocator allocator){
        ProgramBindings bindings=program.code().descriptors();int count=bindings.samplers().size()+(bindings.uniformBytes()>0?1:0);
        try(MemoryStack stack=MemoryStack.stackPush()){
            var entries=VkDescriptorSetLayoutBinding.calloc(count,stack);int slot=0;
            if(bindings.uniformBytes()>0){uniforms=allocator.hostBuffer(bindings.uniformBytes(),VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
                entries.get(slot++).binding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).stageFlags(VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT);}
            for(int binding:bindings.samplers().values())entries.get(slot++).binding(binding).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).stageFlags(VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT);
            LongBuffer pointer=stack.mallocLong(1);check(vkCreateDescriptorSetLayout(context.device(),VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(entries),null,pointer),"pack graph descriptor layout");descriptorLayout=pointer.get(0);
            var sizes=VkDescriptorPoolSize.calloc((uniforms==null?0:1)+(bindings.samplers().isEmpty()?0:1),stack);int index=0;
            if(uniforms!=null)sizes.get(index++).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(MAX_DESCRIPTOR_SETS);
            if(!bindings.samplers().isEmpty())sizes.get(index).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(bindings.samplers().size()*MAX_DESCRIPTOR_SETS);
            check(vkCreateDescriptorPool(context.device(),VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(MAX_DESCRIPTOR_SETS).pPoolSizes(sizes),null,pointer),"pack graph descriptor pool");pool=pointer.get(0);
        }
    }
    void upload(){if(uniforms!=null){upload.clear();values.copyTo(upload);upload.flip();uniforms.write(upload);}}
    void record(VulkanRenderGraph.PassContext pass,List<String> outputs,Map<String,String> samples,long sampler){
        record(pass,outputs,samples,Map.of(),sampler);
    }
    void record(VulkanRenderGraph.PassContext pass,List<String> outputs,Map<String,String> samples,Map<String,Long> samplers,long sampler){
        long set=descriptorSet(pass,samples,samplers,sampler);
        try(MemoryStack stack=MemoryStack.stackPush()){
            List<Long> views=new ArrayList<>();for(String output:outputs)if(output!=null)views.add(pass.image(output).view());
            Long framebuffer=framebuffers.get(views);
            if(framebuffer==null){
                if(framebuffers.size()>=16)throw new IllegalStateException("Pack pass framebuffer cache exceeds bounded routing states");
                LongBuffer handles=stack.mallocLong(views.size());for(long view:views)handles.put(view);handles.flip();LongBuffer pointer=stack.mallocLong(1);
                check(vkCreateFramebuffer(context.device(),VkFramebufferCreateInfo.calloc(stack).sType$Default().renderPass(renderPass).pAttachments(handles).width(width).height(height).layers(1),null,pointer),"pack graph framebuffer");
                framebuffer=pointer.get(0);framebuffers.put(List.copyOf(views),framebuffer);
            }
            var begin=VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(renderPass).framebuffer(framebuffer);begin.renderArea().extent().set(width,height);
            vkCmdBeginRenderPass(pass.command(),begin,VK_SUBPASS_CONTENTS_INLINE);vkCmdBindPipeline(pass.command(),VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline.handle());
            vkCmdBindDescriptorSets(pass.command(),VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline.layout(),0,stack.longs(set),null);
            if(vertices!=null)vkCmdBindVertexBuffers(pass.command(),0,stack.longs(vertices.handle()),stack.longs(0));
            vkCmdBindIndexBuffer(pass.command(),indices.handle(),0,VK_INDEX_TYPE_UINT32);vkCmdDrawIndexed(pass.command(),6,1,0,0,0);vkCmdEndRenderPass(pass.command());
        }
    }
    private long descriptorSet(VulkanRenderGraph.PassContext pass,Map<String,String> samples,Map<String,Long> samplers,long sampler){
        Map<String,Sample> proposed=new LinkedHashMap<>();for(var sample:samples.entrySet())proposed.put(sample.getKey(),new Sample(pass.image(sample.getValue()).view(),samplers.getOrDefault(sample.getKey(),sampler)));
        Long existing=descriptors.get(proposed);if(existing!=null)return existing;
        if(descriptors.size()>=MAX_DESCRIPTOR_SETS)throw new IllegalStateException("Pack pass descriptor routing exceeds bounded immutable states");
        try(MemoryStack stack=MemoryStack.stackPush()){
            LongBuffer pointer=stack.mallocLong(1);check(vkAllocateDescriptorSets(context.device(),VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(descriptorLayout)),pointer),"pack graph descriptor allocation");long set=pointer.get(0);
            var writes=VkWriteDescriptorSet.calloc(samples.size()+(uniforms==null?0:1),stack);int slot=0;
            if(uniforms!=null)writes.get(slot++).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(uniforms.handle()).range(uniforms.size()));
            for(var sample:proposed.entrySet())writes.get(slot++).sType$Default().dstSet(set).dstBinding(program.code().descriptors().samplers().get(sample.getKey())).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(VkDescriptorImageInfo.calloc(1,stack).sampler(sample.getValue().sampler()).imageView(sample.getValue().view()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            vkUpdateDescriptorSets(context.device(),writes,null);descriptors.put(Map.copyOf(proposed),set);return set;
        }
    }
    private long createRenderPass(){
        try(MemoryStack stack=MemoryStack.stackPush()){
            int count=(int)formats.stream().filter(format->format!=VK_FORMAT_UNDEFINED).count();var attachments=VkAttachmentDescription.calloc(count,stack);
            var references=VkAttachmentReference.calloc(formats.size(),stack);int index=0;
            for(int location=0;location<formats.size();location++){
                int format=formats.get(location);references.get(location).attachment(format==VK_FORMAT_UNDEFINED?VK_ATTACHMENT_UNUSED:index).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                if(format!=VK_FORMAT_UNDEFINED)attachments.get(index++).format(format).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE).initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            }
            var subpass=VkSubpassDescription.calloc(1,stack).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(formats.size()).pColorAttachments(references);
            LongBuffer pointer=stack.mallocLong(1);check(vkCreateRenderPass(context.device(),VkRenderPassCreateInfo.calloc(stack).sType$Default().pAttachments(attachments).pSubpasses(subpass),null,pointer),"pack graph render pass");return pointer.get(0);
        }
    }
    @Override public void close(){
        context.requireOwner();if(pipeline!=null){pipeline.close();pipeline=null;}
        for(long framebuffer:framebuffers.values())vkDestroyFramebuffer(context.device(),framebuffer,null);framebuffers.clear();
        if(renderPass!=0)vkDestroyRenderPass(context.device(),renderPass,null);if(pool!=0)vkDestroyDescriptorPool(context.device(),pool,null);if(descriptorLayout!=0)vkDestroyDescriptorSetLayout(context.device(),descriptorLayout,null);
        renderPass=pool=descriptorLayout=0;descriptors.clear();if(indices!=null){indices.close();indices=null;}if(vertices!=null){vertices.close();vertices=null;}if(uniforms!=null){uniforms.close();uniforms=null;}
    }
}
