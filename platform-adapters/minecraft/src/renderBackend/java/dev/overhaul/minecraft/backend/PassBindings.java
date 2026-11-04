package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;

/** Every draw captures state; later frontend setters cannot mutate already recorded draws. */
record PassBindings(OverhaulPipeline pipeline,Object[] uniforms,Map<Integer,GpuBufferSlice> vertices,
                    GpuBuffer indices,IndexType indexType,ByteBuffer constants,Scissor scissor,Viewport viewport) {
    record Scissor(int x,int y,int width,int height) {}
    record Viewport(int x,int y,int width,int height) {
        Viewport {if(width<0||height<0)throw new IllegalArgumentException("Negative viewport dimensions");}
        boolean empty(){return width==0||height==0;}
    }
    PassBindings {uniforms=uniforms.clone();vertices=Map.copyOf(vertices);constants=constants==null?null:constants.asReadOnlyBuffer();}
    void bind(VkCommandBuffer command,NativePassResources resources,PassAttachments attachments,PassDescriptors descriptors) {
        var nativePipeline=pipeline.variant(attachments.renderPass,attachments.colorFormats(),attachments.depthFormat());
        vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,nativePipeline.handle());
        try(MemoryStack stack=MemoryStack.stackPush()){
            long set=descriptors.set(pipeline,uniforms);
            vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_GRAPHICS,nativePipeline.layout(),0,stack.longs(set),null);
            if(constants!=null&&pipeline.pushConstantsSize()>0)vkCmdPushConstants(command,nativePipeline.layout(),VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,constants);
        }
        geometry(command,resources,attachments);
    }
    void geometry(VkCommandBuffer command,NativePassResources resources,PassAttachments attachments){
        geometry(command,resources,attachments,pipeline.vertexBindings());
    }
    void geometry(VkCommandBuffer command,NativePassResources resources,PassAttachments attachments,java.util.List<dev.overhaul.vulkan.NativeGraphicsPipeline.Binding> expectedBindings){
        try(MemoryStack stack=MemoryStack.stackPush()){
            // Restore every draw's state, including the full-target default after a custom viewport.
            var view=VkViewport.calloc(1,stack).minDepth(0).maxDepth(1);
            if(viewport==null)view.width(attachments.width).height(attachments.height);
            else view.x(viewport.x()).y(viewport.y()).width(Math.max(1,viewport.width())).height(Math.max(1,viewport.height()));
            vkCmdSetViewport(command,0,view);
            for(var expected:expectedBindings){
                GpuBufferSlice slice=vertices.get(expected.slot());
                if(slice==null)throw new IllegalStateException("Missing vertex buffer slot "+expected.slot());
                var buffer=resources.buffer(slice.buffer());PassDescriptors.checkRange(slice,buffer.size());
                vkCmdBindVertexBuffers(command,expected.slot(),stack.longs(buffer.handle()),stack.longs(slice.offset()));
            }
            if(indices!=null)vkCmdBindIndexBuffer(command,resources.buffer(indices).handle(),0,indexType==IndexType.INT?VK_INDEX_TYPE_UINT32:VK_INDEX_TYPE_UINT16);
            int left=attachments.x,top=attachments.y,right=left+attachments.areaWidth,bottom=top+attachments.areaHeight;
            if(scissor!=null){left=Math.max(left,scissor.x());top=Math.max(top,scissor.y());right=(int)Math.min(right,(long)scissor.x()+scissor.width());bottom=(int)Math.min(bottom,(long)scissor.y()+scissor.height());}
            left=Math.min(attachments.width,Math.max(0,left));top=Math.min(attachments.height,Math.max(0,top));
            // Vulkan forbids a zero viewport width; an empty scissor preserves zero-area semantics.
            if(viewport!=null&&viewport.empty()){right=left;bottom=top;}
            VkRect2D.Buffer region=VkRect2D.calloc(1,stack);region.offset().set(left,top);region.extent().set(Math.max(0,right-left),Math.max(0,bottom-top));vkCmdSetScissor(command,0,region);
        }
    }
}
