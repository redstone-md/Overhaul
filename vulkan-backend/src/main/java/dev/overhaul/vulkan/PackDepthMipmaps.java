package dev.overhaul.vulkan;

import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.compile.ShaderStage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.LongBuffer;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** Box-filtered normal-Z depth, stored in real depth mip attachments rather than a color surrogate. */
final class PackDepthMipmaps implements AutoCloseable {
    private static final int MAX_LEVEL_VIEWS=28; // Two 16384-square depth images, excluding their bases.
    private static final String VERTEX="""
            #version 450
            void main(){
                vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
                gl_Position=vec4(p*2.0-1.0,0.0,1.0);
            }
            """;
    private static final String FRAGMENT="""
            #version 450
            layout(set=0,binding=0) uniform sampler2D sourceDepth;
            void main(){
                ivec2 size=textureSize(sourceDepth,0);
                ivec2 destination=max(size/2,ivec2(1));
                // Rational texel boundaries avoid floating-point gaps at odd-sized image edges.
                ivec2 first=ivec2(gl_FragCoord.xy)*size;
                ivec2 last=first+size;
                ivec2 start=first/destination;
                float total=0.0;
                // Odd extents need up to three source texels per axis, weighted by covered area.
                for(int y=0;y<3;y++)for(int x=0;x<3;x++){
                    ivec2 p=start+ivec2(x,y);
                    if(any(greaterThanEqual(p,size)))continue;
                    ivec2 extent=max(ivec2(0),min(last,(p+1)*destination)-max(first,p*destination));
                    total+=texelFetch(sourceDepth,p,0).r*float(extent.x)*float(extent.y);
                }
                gl_FragDepth=clamp(total/(float(size.x)*float(size.y)),0.0,1.0);
            }
            """;
    private final VulkanContext context;
    private final Map<Object,Long> sets=new IdentityHashMap<>(),framebuffers=new IdentityHashMap<>();
    private long renderPass,descriptorLayout,pool;
    private GpuSampler sampler;
    private NativeGraphicsPipeline pipeline;

    PackDepthMipmaps(VulkanContext context,GpuAllocator allocator,ShadercCompiler compiler,int format){
        this.context=context;
        try{
            sampler=allocator.createSampler(SamplerSettings.nearest());
            createResources(format);
            var vertex=compiler.compile("overhaul-shadow-depth-mip.vert",VERTEX,ShaderStage.VERTEX);
            var fragment=compiler.compile("overhaul-shadow-depth-mip.frag",FRAGMENT,ShaderStage.FRAGMENT);
            pipeline=new NativeGraphicsPipeline(context,renderPass,new NativeGraphicsPipeline.Description(
                    List.of(new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_VERTEX_BIT,"main",vertex.directBuffer()),
                            new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_FRAGMENT_BIT,"main",fragment.directBuffer())),
                    List.of(),List.of(),new long[]{descriptorLayout},0,VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,VK_POLYGON_MODE_FILL,
                    VK_CULL_MODE_NONE,VK_FRONT_FACE_COUNTER_CLOCKWISE,new NativeGraphicsPipeline.Depth(true,true,VK_COMPARE_OP_ALWAYS,0,0),List.of(),true,1,1));
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    void generate(PackFrameGraph plan,List<GpuImageView> levels){
        for(int mip=1;mip<levels.size();mip++){
            GpuImageView source=levels.get(mip-1),target=levels.get(mip);
            String read=plan.read(source),write=plan.write(target);
            plan.pass("shadow-depth-mip-"+write,Map.of(read,VulkanRenderGraph.Usage.GRAPHICS_SAMPLE,write,VulkanRenderGraph.Usage.DEPTH_WRITE),
                    pass->record(pass,read,write));
        }
    }
    private void createResources(int format){
        try(MemoryStack stack=MemoryStack.stackPush()){
            LongBuffer pointer=stack.mallocLong(1);
            var attachment=VkAttachmentDescription.calloc(1,stack).format(format).samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            var reference=VkAttachmentReference.calloc(stack).attachment(0).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            var subpass=VkSubpassDescription.calloc(1,stack).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).pDepthStencilAttachment(reference);
            check(vkCreateRenderPass(context.device(),VkRenderPassCreateInfo.calloc(stack).sType$Default().pAttachments(attachment).pSubpasses(subpass),null,pointer),"shadow depth mip render pass");renderPass=pointer.get(0);
            var binding=VkDescriptorSetLayoutBinding.calloc(1,stack).binding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            check(vkCreateDescriptorSetLayout(context.device(),VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binding),null,pointer),"shadow depth mip descriptor layout");descriptorLayout=pointer.get(0);
            var size=VkDescriptorPoolSize.calloc(1,stack).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_LEVEL_VIEWS);
            check(vkCreateDescriptorPool(context.device(),VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(MAX_LEVEL_VIEWS).pPoolSizes(size),null,pointer),"shadow depth mip pool");pool=pointer.get(0);
        }
    }
    private void record(VulkanRenderGraph.PassContext pass,String read,String write){
        var source=pass.image(read);var target=pass.image(write);
        try(MemoryStack stack=MemoryStack.stackPush()){
            long set=descriptor(source,stack),framebuffer=framebuffer(target,stack);
            var begin=VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(renderPass).framebuffer(framebuffer);
            begin.renderArea().extent().set(target.width(),target.height());
            vkCmdBeginRenderPass(pass.command(),begin,VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(pass.command(),VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline.handle());
            vkCmdBindDescriptorSets(pass.command(),VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline.layout(),0,stack.longs(set),null);
            vkCmdSetViewport(pass.command(),0,VkViewport.calloc(1,stack).width(target.width()).height(target.height()).minDepth(0).maxDepth(1));
            var scissor=VkRect2D.calloc(1,stack);scissor.extent().set(target.width(),target.height());vkCmdSetScissor(pass.command(),0,scissor);
            vkCmdDraw(pass.command(),3,1,0,0);vkCmdEndRenderPass(pass.command());
        }
    }
    private long descriptor(VulkanRenderGraph.Image source,MemoryStack stack){
        Long existing=sets.get(source.identity());if(existing!=null)return existing;
        if(sets.size()>=MAX_LEVEL_VIEWS)throw new IllegalStateException("Shadow depth mip descriptor bound exceeded");
        LongBuffer pointer=stack.mallocLong(1);
        check(vkAllocateDescriptorSets(context.device(),VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(descriptorLayout)),pointer),"shadow depth mip descriptor");
        long set=pointer.get(0);
        var image=VkDescriptorImageInfo.calloc(1,stack).sampler(sampler.handle()).imageView(source.view()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        vkUpdateDescriptorSets(context.device(),VkWriteDescriptorSet.calloc(1,stack).sType$Default().dstSet(set).dstBinding(0)
                .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(image),null);
        sets.put(source.identity(),set);return set;
    }
    private long framebuffer(VulkanRenderGraph.Image target,MemoryStack stack){
        Long existing=framebuffers.get(target.identity());if(existing!=null)return existing;
        if(framebuffers.size()>=MAX_LEVEL_VIEWS)throw new IllegalStateException("Shadow depth mip framebuffer bound exceeded");
        LongBuffer pointer=stack.mallocLong(1);
        check(vkCreateFramebuffer(context.device(),VkFramebufferCreateInfo.calloc(stack).sType$Default().renderPass(renderPass)
                .pAttachments(stack.longs(target.view())).width(target.width()).height(target.height()).layers(1),null,pointer),"shadow depth mip framebuffer");
        long framebuffer=pointer.get(0);framebuffers.put(target.identity(),framebuffer);return framebuffer;
    }
    @Override public void close(){
        context.requireOwner();if(pipeline!=null){pipeline.close();pipeline=null;}
        for(long framebuffer:framebuffers.values())vkDestroyFramebuffer(context.device(),framebuffer,null);framebuffers.clear();
        if(pool!=0)vkDestroyDescriptorPool(context.device(),pool,null);if(descriptorLayout!=0)vkDestroyDescriptorSetLayout(context.device(),descriptorLayout,null);
        if(renderPass!=0)vkDestroyRenderPass(context.device(),renderPass,null);pool=descriptorLayout=renderPass=0;sets.clear();
        if(sampler!=null){sampler.close();sampler=null;}
    }
}
