package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;

/** Immutable draw capture; all descriptor and uniform storage is returned after its fence. */
final class BlazePass implements BlazeQueue.Operation {
    private final BlazeTarget target;
    private final BlazeMesh.Snapshot mesh;
    private final BlazeDrawState.Snapshot state;
    private final List<BlazeTextures.Sampled> samples=new ArrayList<>();
    private final BlazeImageUse images=new BlazeImageUse();
    private BlazeBufferPool.Slice uniforms;
    private BlazeDescriptors.Lease descriptors;
    private NativeGraphicsPipeline pipeline;
    private boolean closed;

    BlazePass(BlazeRenderer owner,BlazeTarget target,BlazeMesh mesh,BlazeShaderSnapshot shader,BlazeDrawState.Snapshot state){
        this.target=target;this.mesh=mesh.snapshot();this.state=state;
        try{
            for(String name:shader.program().bindings().samplers().keySet()){
                BlazeTextures.Sampled sample=owner.textures().resolve(shader.samplers().get(name));
                if(sample.view().image()==target.color()||sample.view().image()==target.depth())throw new UnsupportedOperationException("Framebuffer sampling feedback requires an explicit pass dependency");
                samples.add(sample);
            }
            ByteBuffer bytes=shader.uniforms();
            if(Math.max(16,bytes.remaining())>owner.maximumUniformRange())throw new IllegalArgumentException("Shader uniform block exceeds Vulkan device range");
            uniforms=owner.hostStorage().acquire(Math.max(16,bytes.remaining()));
            if(bytes.remaining()==uniforms.size())uniforms.write(bytes);
            else try(MemoryStack stack=MemoryStack.stackPush()){ByteBuffer padded=stack.calloc(uniforms.size());padded.put(bytes).clear();uniforms.write(padded);}
            descriptors=owner.descriptors().acquire(shader.program(),uniforms,samples);
            pipeline=owner.pipeline(shader.program(),this.mesh.state(),state,target,descriptors.layout());
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    @Override public void record(VkCommandBuffer command){
        int x=state.scissor()?Math.max(0,state.sx()):0,y=state.scissor()?Math.max(0,state.sy()):0;
        long right=state.scissor()?Math.min(target.width(),(long)state.sx()+state.sw()):target.width();
        long top=state.scissor()?Math.min(target.height(),(long)state.sy()+state.sh()):target.height();
        if(state.width()==0||state.height()==0||right<=x||top<=y||mesh.state().indexCount()==0)return;
        for(BlazeTextures.Sampled sample:samples){
            GpuImageView view=sample.view();
            images.transition(command,view,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        target.prepare(command,images);
        try(MemoryStack stack=MemoryStack.stackPush()){
            target.begin(command,stack);
            vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline.handle());
            vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline.layout(),0,stack.longs(descriptors.set()),null);
            vkCmdSetViewport(command,0,VkViewport.calloc(1,stack).x(state.x()).y(state.y()).width(state.width()).height(state.height()).minDepth(0).maxDepth(1));
            var clip=VkRect2D.calloc(1,stack);clip.offset().set(x,y);clip.extent().set((int)(right-x),(int)(top-y));vkCmdSetScissor(command,0,clip);
            mesh.record(command);vkCmdEndRenderPass(command);
        }
    }
    @Override public void submitted(FrameRing.Submission submission){
        images.submitted(submission);
    }
    @Override public void close(){
        if(closed)return;closed=true;
        try{images.close();}
        finally{try{if(descriptors!=null)descriptors.close();}finally{try{if(uniforms!=null)uniforms.close();}finally{mesh.close();}}}
    }
}
