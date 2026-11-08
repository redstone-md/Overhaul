package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.VulkanStack;

import dev.overhaul.shader.blaze.BlazeProgramCompiler;
import dev.overhaul.vulkan.*;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Immutable capture of consecutive draws that differ only in mesh and uniform values: they share a pipeline and a
 * descriptor set, and each reads its own uniforms through a dynamic offset. All storage is returned after its fence.
 */
final class BlazePass implements BlazeQueue.Operation {
    /** Reading an image while it is the render target needs a pass dependency that does not exist here. */
    static final class SamplingFeedback extends RuntimeException { SamplingFeedback() { super("Framebuffer sampling feedback", null, false, false); } }
    private static final int MAX_DRAWS=4096;
    private final BlazeTarget target;
    private final BlazeProgramCompiler.Program program;
    private final BlazeDrawState.Snapshot state;
    private final long constants;
    private final List<BlazeMesh.Snapshot> meshes=new ArrayList<>(4);
    private final List<BlazeTextures.Sampled> samples=new ArrayList<>();
    private final BlazeImageUse images=new BlazeImageUse();
    private int[] offsets=new int[4];
    private BlazeBufferPool.Slice chunk;
    private BlazeBufferPool.Pin uniforms;
    private BlazeDescriptors.Lease descriptors;
    private NativeGraphicsPipeline pipeline;
    private boolean closed;

    BlazePass(BlazeRenderer owner,BlazeTarget target,BlazeMesh mesh,BlazeShaderSnapshot shader,BlazeDrawState.Snapshot state){
        this.target=target;this.program=shader.program();this.state=state;constants=owner.vertexConstants(program);
        try{
            for(String name:program.bindings().samplers().keySet()){
                BlazeTextures.Sampled sample=owner.textures().resolve(shader.samplers().get(name),program.bindings().samplerTypes().get(name));
                if(sample.view()!=null&&target.writes(sample.view().image()))throw new SamplingFeedback();
                samples.add(sample);
            }
            ByteBuffer bytes=shader.uniforms();
            int range=Math.max(16,bytes.remaining());
            if(range>owner.maximumUniformRange())throw new IllegalArgumentException("Shader uniform block exceeds Vulkan device range");
            chunk=owner.uniformArena().reserve(range);
            uniforms=chunk.retain();
            descriptors=owner.descriptors().acquire(program,chunk,range,samples);
            BlazeMesh.Snapshot first=mesh.snapshot(patches(program));
            meshes.add(first);
            pipeline=owner.pipeline(program,first.state(),state,target,descriptors.layout());
            offsets[0]=owner.uniformArena().write(bytes,range);
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    /** Joins this pass when only the mesh and uniform values differ; false leaves everything untouched. */
    boolean append(BlazeRenderer owner,BlazeTarget target,BlazeMesh mesh,BlazeShaderSnapshot shader,BlazeDrawState.Snapshot state){
        if(meshes.size()==MAX_DRAWS||target!=this.target||shader.program()!=program||!state.equals(this.state))return false;
        BlazeMeshState next=mesh.state(),first=meshes.get(0).state();
        if(!next.layout().equals(first.layout()))return false;
        boolean patches=patches(program);
        if(patches?next.pattern()!=BlazeMeshState.IndexPattern.QUADS:next.topology()!=first.topology())return false;
        int index=0;
        for(String name:program.bindings().samplers().keySet()){
            BlazeTextures.Sampled sample=owner.textures().resolve(shader.samplers().get(name),program.bindings().samplerTypes().get(name)),existing=samples.get(index++);
            if(sample.view()!=existing.view()||sample.sampler()!=existing.sampler()||sample.buffer()!=existing.buffer()||sample.texel()!=existing.texel())return false;
        }
        ByteBuffer bytes=shader.uniforms();
        int range=Math.max(16,bytes.remaining());
        if(owner.uniformArena().reserve(range)!=chunk)return false;
        BlazeMesh.Snapshot snapshot=mesh.snapshot(patches);
        if(meshes.size()==offsets.length)offsets=Arrays.copyOf(offsets,offsets.length*2);
        offsets[meshes.size()]=owner.uniformArena().write(bytes,range);
        meshes.add(snapshot);
        return true;
    }
    /** A lines-with-adjacency geometry stage over quads is tessellation run as geometry: each quad is one patch. */
    private static boolean patches(BlazeProgramCompiler.Program program){
        return program.primitive()==dev.overhaul.shader.compat.PackGeometryInterface.Primitive.LINES_ADJACENCY;
    }
    /** Records a batch in order; consecutive draws to one target share a render pass. */
    static void recordAll(VkCommandBuffer command,List<BlazeQueue.Operation> operations){
        BlazePass open=null;
        for(BlazeQueue.Operation operation:operations){
            if(operation instanceof BlazePass pass)open=pass.record(command,open);
            else{if(open!=null){vkCmdEndRenderPass(command);open=null;}operation.record(command);}
        }
        if(open!=null)vkCmdEndRenderPass(command);
    }
    @Override public void record(VkCommandBuffer command){if(record(command,null)!=null)vkCmdEndRenderPass(command);}
    /** Returns the pass left open for the next draw. Barriers are illegal inside one, so a needed transition ends it first. */
    private BlazePass record(VkCommandBuffer command,BlazePass open){
        int[] clip=clip(state,target);
        if(clip==null)return open;
        if(open!=null&&(open.target!=target||!samplesSettled())){vkCmdEndRenderPass(command);open=null;}
        for(BlazeTextures.Sampled sample:samples){
            if(sample.general())continue;
            GpuImageView view=sample.view();
            images.transition(command,view,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        target.prepare(command,images);
        try(MemoryStack stack=VulkanStack.push()){
            if(open==null)target.begin(command,stack);
            if(open==null||open.pipeline!=pipeline)vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline.handle());
            if(constants!=0)vkCmdBindVertexBuffers(command,1,stack.longs(constants),stack.longs(0));
            dynamicState(command,stack,state,clip);
            var set=stack.longs(descriptors.set());
            IntBuffer offset=stack.mallocInt(1);
            for(int index=0;index<meshes.size();index++){
                offset.put(0,offsets[index]);
                vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline.layout(),0,set,offset);
                meshes.get(index).record(command);
            }
        }
        return this;
    }
    private boolean samplesSettled(){
        for(BlazeTextures.Sampled sample:samples)if(!sample.general()&&!BlazeImageUse.settled(sample.view(),VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL))return false;
        return true;
    }
    /** The scissor rectangle as x, y, width, height clipped to the target; null when nothing can be drawn. */
    static int[] clip(BlazeDrawState.Snapshot state,BlazeTarget target){
        int x=state.scissor()?Math.max(0,state.sx()):0,y=state.scissor()?Math.max(0,state.sy()):0;
        long right=state.scissor()?Math.min(target.width(),(long)state.sx()+state.sw()):target.width();
        long top=state.scissor()?Math.min(target.height(),(long)state.sy()+state.sh()):target.height();
        return state.width()==0||state.height()==0||right<=x||top<=y?null:new int[]{x,y,(int)(right-x),(int)(top-y)};
    }
    static void dynamicState(VkCommandBuffer command,MemoryStack stack,BlazeDrawState.Snapshot state,int[] clip){
        vkCmdSetViewport(command,0,VkViewport.calloc(1,stack).x(state.x()).y(state.y()).width(state.width()).height(state.height()).minDepth(0).maxDepth(1));
        var scissor=VkRect2D.calloc(1,stack);scissor.offset().set(clip[0],clip[1]);scissor.extent().set(clip[2],clip[3]);vkCmdSetScissor(command,0,scissor);
    }
    @Override public void submitted(FrameRing.Submission submission){
        images.submitted(submission);
    }
    @Override public void close(){
        if(closed)return;closed=true;
        try{images.close();}
        finally{
            try{if(descriptors!=null)descriptors.close();}
            finally{
                try{if(uniforms!=null)uniforms.close();}
                finally{for(BlazeMesh.Snapshot mesh:meshes)mesh.close();}
            }
        }
    }
}
