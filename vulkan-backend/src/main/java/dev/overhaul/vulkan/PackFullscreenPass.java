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
    private record Attachment(Object identity,long view){}
    private final VulkanContext context;
    private final CompiledShaderPack.Program program;
    private final Map<List<Attachment>,Long> framebuffers=new LinkedHashMap<>();
    private final int width,height;
    private final List<Integer> formats;
    private long renderPass;
    private GpuBuffer vertices,indices;
    private NativeGraphicsPipeline pipeline;
    private PackPassDescriptors descriptors;
    UniformBlock values;

    PackFullscreenPass(VulkanContext context,GpuAllocator allocator,CompiledShaderPack.Program program,List<Integer> formats,int width,int height){
        this.context=context;this.program=program;this.formats=List.copyOf(formats);this.width=width;this.height=height;
        program.code().requireAlphaTest(program.state().alphaTest().orElse(null));
        try{
            if(program.code().descriptors().samplerTypes().containsValue("sampler2DShadow"))context.portability().requireMutableComparisonSamplers();
            renderPass=createRenderPass();
            descriptors=new PackPassDescriptors(context,allocator,program.code().descriptors(),VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT);values=descriptors.values;
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
                    program.stage()==CompiledShaderPack.Stage.FINAL?0:program.code().framebuffer().colorTargets().get(index),program.stage()==CompiledShaderPack.Stage.SHADOWCOMPOSITE?"shadowcolor":"colortex"));
            pipeline=new NativeGraphicsPipeline(context,renderPass,new NativeGraphicsPipeline.Description(shaders,bindings,attributes,new long[]{descriptors.layout()},0,
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
    /** Writes the current values into the next copy and returns where it starts, for the matching record. */
    int upload(){return descriptors.upload();}
    void record(VulkanRenderGraph.PassContext pass,List<String> outputs,Map<String,String> samples,long sampler){
        record(pass,outputs,samples,Map.of(),sampler,upload(),null);
    }
    void record(VulkanRenderGraph.PassContext pass,List<String> outputs,Map<String,String> samples,Map<String,Long> samplers,long sampler,int uniformOffset){
        record(pass,outputs,samples,samplers,sampler,uniformOffset,null);
    }
    /** With pack images bound, earlier shader writes to them are made visible first, and this pass's writes after. */
    void record(VulkanRenderGraph.PassContext pass,List<String> outputs,Map<String,String> samples,Map<String,Long> samplers,long sampler,int uniformOffset,PackImageSet images){
        long set=descriptors.set(pass,samples,samplers,sampler,images);
        boolean fenced=images!=null&&images.uses(program.code().descriptors());
        if(fenced)PackImageSet.barrier(pass.command());
        try(MemoryStack stack=VulkanStack.push()){
            List<Attachment> views=new ArrayList<>();for(String output:outputs)if(output!=null){
                var image=pass.image(output);views.add(new Attachment(image.identity(),image.view()));
            }
            Long framebuffer=framebuffers.get(views);
            if(framebuffer==null){
                if(framebuffers.size()>=16)throw new IllegalStateException("Pack pass framebuffer cache exceeds bounded routing states");
                LongBuffer handles=stack.mallocLong(views.size());for(Attachment view:views)handles.put(view.view());handles.flip();LongBuffer pointer=stack.mallocLong(1);
                check(vkCreateFramebuffer(context.device(),VkFramebufferCreateInfo.calloc(stack).sType$Default().renderPass(renderPass).pAttachments(handles).width(width).height(height).layers(1),null,pointer),"pack graph framebuffer");
                framebuffer=pointer.get(0);framebuffers.put(List.copyOf(views),framebuffer);
            }
            var begin=VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(renderPass).framebuffer(framebuffer);begin.renderArea().extent().set(width,height);
            vkCmdBeginRenderPass(pass.command(),begin,VK_SUBPASS_CONTENTS_INLINE);vkCmdBindPipeline(pass.command(),VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline.handle());
            vkCmdBindDescriptorSets(pass.command(),VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline.layout(),0,stack.longs(set),descriptors.offsets(stack,uniformOffset));
            if(vertices!=null)vkCmdBindVertexBuffers(pass.command(),0,stack.longs(vertices.handle()),stack.longs(0));
            vkCmdBindIndexBuffer(pass.command(),indices.handle(),0,VK_INDEX_TYPE_UINT32);vkCmdDrawIndexed(pass.command(),6,1,0,0,0);vkCmdEndRenderPass(pass.command());
        }
        if(fenced)PackImageSet.barrier(pass.command());
    }
    private long createRenderPass(){
        try(MemoryStack stack=VulkanStack.push()){
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
        if(renderPass!=0)vkDestroyRenderPass(context.device(),renderPass,null);renderPass=0;
        if(descriptors!=null){descriptors.close();descriptors=null;}if(indices!=null){indices.close();indices=null;}if(vertices!=null){vertices.close();vertices=null;}
    }
}
