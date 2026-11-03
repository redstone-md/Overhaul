package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.GpuImageView;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import static org.lwjgl.vulkan.VK10.*;

/** Captures frontend calls, pins resources, and records native commands into the encoder's submission. */
public final class OverhaulRenderPass implements RenderPassBackend,AutoCloseable {
    private record Command(PassBindings bindings,Consumer<VkCommandBuffer> draw) {}
    private record TimestampKey(GpuQueryPool pool,int index) {}
    private record TimestampReset(GpuQueryPool pool,int index,long generation) {}
    private final OverhaulDeviceBackend owner;
    private final PassAttachments attachments;
    private final PassDescriptors descriptors;
    private final BackendDrawStatistics.Recording drawMetrics=new BackendDrawStatistics.Recording();
    private final List<Command> commands=new ArrayList<>();
    private final List<TimestampReset> timestampResets=new ArrayList<>();
    private final Set<TimestampKey> timestampKeys=new HashSet<>();
    private final Set<BackendResource> retained=Collections.newSetFromMap(new IdentityHashMap<>());
    private final ReadFootprints<OverhaulBuffer> bufferReads=new ReadFootprints<>();
    private final List<AutoCloseable> pins=new ArrayList<>();
    private final List<GpuImage.LayoutChange> transitions=new ArrayList<>();
    private final Set<GpuImageView> sampled=Collections.newSetFromMap(new IdentityHashMap<>());
    private final ArrayDeque<String> debugGroups=new ArrayDeque<>();
    private final Map<Integer,GpuBufferSlice> vertices=new LinkedHashMap<>();
    private OverhaulPipeline pipeline;
    private Object[] uniforms=new Object[0];
    private ByteBuffer constants;
    private GpuBuffer indices;
    private IndexType indexType;
    private PassBindings.Scissor scissor;
    private boolean sealed,recorded,recordingSucceeded,closed,complete;
    private int drawCount;
    private dev.overhaul.vulkan.FrameRing.Submission submission;

    public OverhaulRenderPass(OverhaulDeviceBackend owner,RenderPassDescriptor descriptor){
        this.owner=owner;owner.requireOpen();descriptors=new PassDescriptors(owner);
        try{attachments=new PassAttachments(owner,descriptor,this::retain);}
        catch(RuntimeException|Error failure){releasePins();throw failure;}
    }
    private void requireCapture(){owner.requireOpen();if(closed||sealed)throw new IllegalStateException("Render pass no longer accepts commands");}
    private void retain(Object resource){
        if(resource==null||retained.contains(resource))return;
        if(!(resource instanceof BackendResource backend)||backend.owner()!=owner)throw new IllegalArgumentException("Foreign backend resource");
        AutoCloseable pin=owner.retain(backend);retained.add(backend);pins.add(pin);
    }
    @Override public void pushDebugGroup(Supplier<String> label){requireCapture();debugGroups.push(java.util.Objects.requireNonNull(label.get()));}
    @Override public void popDebugGroup(){requireCapture();if(debugGroups.isEmpty())throw new IllegalStateException("Unbalanced render-pass debug scopes");debugGroups.pop();}
    @Override public void setPipeline(BackendRenderPipeline value){
        requireCapture();if(!(value instanceof OverhaulPipeline own)||own.owner()!=owner||own.isClosed())throw new IllegalArgumentException("Foreign or closed render pipeline");
        retain(own);pipeline=own;uniforms=new Object[own.uniforms().size()];constants=null;
    }
    @Override public void setUniform(int slot,Object value){
        requireCapture();if(pipeline==null||slot<0||slot>=uniforms.length)throw new IllegalArgumentException("Uniform slot outside bound pipeline");
        if(value instanceof GpuBufferSlice slice){retain(slice.buffer());owner.buffer(slice.buffer());}
        else if(value instanceof TextureViewAndSampler pair){retain(pair.view());retain(pair.sampler());owner.texture(pair.view());owner.sampler(pair.sampler());}
        else if(value!=null)throw new IllegalArgumentException("Unsupported uniform payload "+value.getClass().getName());
        uniforms[slot]=value;
    }
    @Override public void pushConstants(ByteBuffer source){
        requireCapture();if(pipeline==null)throw new IllegalStateException("Bind a pipeline before push constants");int size=pipeline.pushConstantsSize();
        if(source.remaining()<size)throw new IllegalArgumentException("Push constants are too small");
        ByteBuffer input=source.duplicate();input.limit(input.position()+size);constants=ByteBuffer.allocateDirect(size);constants.put(input).flip();
    }
    @Override public void enableScissor(int x,int y,int width,int height){requireCapture();if(width<0||height<0)throw new IllegalArgumentException("Negative scissor dimensions");scissor=new PassBindings.Scissor(x,y,width,height);}
    @Override public void disableScissor(){requireCapture();scissor=null;}
    @Override public void setVertexBuffer(int slot,GpuBufferSlice slice){
        requireCapture();if(slot<0||slot>=com.mojang.renderpearl.api.commands.RenderPass.MAX_VERTEX_BUFFERS)throw new IllegalArgumentException("Vertex slot outside supported range");
        // Keep earlier draw snapshots pinned; only subsequent draws observe this unbind.
        if(slice==null){vertices.remove(slot);return;}
        retain(slice.buffer());PassDescriptors.checkRange(slice,owner.buffer(slice.buffer()).size());vertices.put(slot,slice);
    }
    @Override public void setIndexBuffer(GpuBuffer buffer,IndexType type){
        requireCapture();java.util.Objects.requireNonNull(buffer,"indexBuffer");java.util.Objects.requireNonNull(type,"indexType");
        retain(buffer);owner.buffer(buffer);indices=buffer;indexType=type;
    }
    private PassBindings snapshot(boolean indexed){
        requireCapture();if(pipeline==null)throw new IllegalStateException("Draw requires a pipeline");
        for(var binding:pipeline.info().vertexBuffers())if(!vertices.containsKey(binding.bufferSlot()))throw new IllegalStateException("Missing vertex buffer slot "+binding.bufferSlot());
        if(indexed&&indices==null)throw new IllegalStateException("Indexed draw requires an index buffer");
        if(pipeline.pushConstantsSize()>0&&constants==null)throw new IllegalStateException("Draw requires push constants");
        var active=pipeline.activeUniforms();
        for(int slot=active.nextSetBit(0);slot>=0;slot=active.nextSetBit(slot+1)){
            if(uniforms[slot]==null)throw new IllegalStateException("Missing uniform "+pipeline.uniforms().get(slot).name());
            if(uniforms[slot] instanceof TextureViewAndSampler pair){var view=owner.texture(pair.view());if(attachments.overlaps(view))throw new UnsupportedOperationException("Attachment feedback requires separate resource versions");sampled.add(view);}
        }
        return new PassBindings(pipeline,uniforms,vertices,indices,indexType,constants,scissor);
    }
    @Override public void drawIndexed(int indexCount,int instanceCount,int firstIndex,int vertexOffset,int firstInstance){
        checkUnsigned(indexCount,instanceCount,firstIndex,firstInstance);var state=snapshot(true);
        if(((long)firstIndex+indexCount)*indexType.bytes>owner.buffer(indices).size())throw new IllegalArgumentException("Indexed draw exceeds the index allocation");
        commands.add(new Command(state,command->{vkCmdDrawIndexed(command,indexCount,instanceCount,firstIndex,vertexOffset,firstInstance);drawMetrics.direct(true);}));drawCount++;
        if(indexCount>0&&instanceCount>0)captureReads(state,true);
    }
    @Override public void draw(int vertexCount,int instanceCount,int firstVertex,int firstInstance){
        checkUnsigned(vertexCount,instanceCount,firstVertex,firstInstance);var state=snapshot(false);
        commands.add(new Command(state,command->{vkCmdDraw(command,vertexCount,instanceCount,firstVertex,firstInstance);drawMetrics.direct(false);}));drawCount++;
        if(vertexCount>0&&instanceCount>0)captureReads(state,false);
    }
    @Override public void multiDrawIndexed(IntBuffer info,int instances,int firstInstance,int count){
        validateDirectMultiDraw(count);checkUnsigned(instances,firstInstance,count);if(info.remaining()<(long)count*3)throw new IllegalArgumentException("Indexed multi-draw data truncated");
        IntBuffer data=info.duplicate();for(int i=0;i<count;i++){int first=data.get(),length=data.get(),base=data.get();drawIndexed(length,instances,first,base,firstInstance);}
    }
    @Override public void multiDrawIndexed(PointerBuffer offsets,IntBuffer counts,IntBuffer bases,int count){
        validateDirectMultiDraw(count);checkUnsigned(count);if(indexType==null||offsets.remaining()<count||counts.remaining()<count||bases.remaining()<count)throw new IllegalArgumentException("Separate indexed multi-draw data truncated");
        for(int i=0;i<count;i++){long offset=offsets.get(offsets.position()+i);if(offset<0||offset%indexType.bytes!=0||offset/indexType.bytes>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid multi-draw index byte offset");drawIndexed(counts.get(counts.position()+i),1,(int)(offset/indexType.bytes),bases.get(bases.position()+i),0);}
    }
    @Override public void multiDraw(IntBuffer info,int instances,int firstInstance,int count){
        validateDirectMultiDraw(count);checkUnsigned(instances,firstInstance,count);if(info.remaining()<(long)count*2)throw new IllegalArgumentException("Multi-draw data truncated");
        IntBuffer data=info.duplicate();for(int i=0;i<count;i++){int first=data.get(),length=data.get();draw(length,instances,first,firstInstance);}
    }
    @Override public void multiDraw(IntBuffer first,IntBuffer counts,int count){
        validateDirectMultiDraw(count);checkUnsigned(count);if(first.remaining()<count||counts.remaining()<count)throw new IllegalArgumentException("Separate multi-draw data truncated");
        for(int i=0;i<count;i++)draw(counts.get(counts.position()+i),1,first.get(first.position()+i),0);
    }
    @Override public void drawIndexedIndirect(GpuBufferSlice slice,int count){indirect(slice,count,true);}
    @Override public void drawIndirect(GpuBufferSlice slice,int count){indirect(slice,count,false);}
    private void indirect(GpuBufferSlice slice,int count,boolean indexed){
        checkUnsigned(count);var state=snapshot(indexed);int stride=indexed?20:16;retain(slice.buffer());var buffer=owner.buffer(slice.buffer());PassDescriptors.checkRange(slice,buffer.size());
        if(slice.offset()%4!=0||(long)count*stride>slice.length())throw new IllegalArgumentException("Indirect draw range/alignment invalid");
        if(count>1&&pipeline.usesDrawIndex()&&(!owner.context().multiDrawIndirect()||count>owner.context().maxIndirectDrawCount()))throw new UnsupportedOperationException("Splitting this indirect batch would change shader DrawIndex semantics");
        boolean instanced=usesInstanceBindings(state);
        commands.add(new Command(state,command->{
            if(owner.context().multiDrawIndirect()&&count<=owner.context().maxIndirectDrawCount()){
                if(indexed)vkCmdDrawIndexedIndirect(command,buffer.handle(),slice.offset(),count,stride);else vkCmdDrawIndirect(command,buffer.handle(),slice.offset(),count,stride);
                drawMetrics.indirect(indexed,count,instanced);
            }else for(int index=0;index<count;index++){
                if(indexed)vkCmdDrawIndexedIndirect(command,buffer.handle(),slice.offset()+(long)index*stride,1,stride);else vkCmdDrawIndirect(command,buffer.handle(),slice.offset()+(long)index*stride,1,stride);
                drawMetrics.indirect(indexed,1,instanced);
            }
        }));drawCount+=count;
        if(count>0){captureReads(state,indexed);readBuffer(slice.buffer(),slice.offset(),(long)count*stride);}
    }
    @Override public void writeTimestamp(GpuQueryPool pool,int index){
        requireCapture();TimestampKey key=new TimestampKey(pool,index);
        if(timestampKeys.contains(key))throw new IllegalStateException("A timestamp index can be written only once per render pass");
        retain(pool);owner.prepareTimestamp(pool,index);long generation=owner.timestampGeneration(pool,index);
        timestampKeys.add(key);timestampResets.add(new TimestampReset(pool,index,generation));
        commands.add(new Command(null,command->owner.timestamp(command,pool,index)));
    }
    public int drawCount(){return drawCount;}
    void retainAttachmentsUntil(dev.overhaul.vulkan.FrameRing.Submission completion) {
        submission=completion;
        descriptors.submitted(completion); attachments.retainUntil(completion);
    }
    /** Freezes frontend capture without beginning native recording or waiting for the GPU. */
    public void seal(){
        owner.requireOpen();if(closed)throw new IllegalStateException("Render pass is closed");if(sealed)return;
        if(!debugGroups.isEmpty())throw new IllegalStateException("Unclosed render-pass debug scopes");sealed=true;
    }
    public boolean isSealed(){owner.requireOwner();return sealed;}
    public boolean isRecorded(){owner.requireOwner();return recorded;}
    /** Queue tracking receives a detached snapshot, never the mutable retention set. */
    List<BackendResource> retainedResources(){owner.requireOwner();return List.copyOf(retained);}
    boolean usesResource(BackendResource resource){owner.requireOwner();return retained.contains(resource);}
    boolean usesBuffer(OverhaulBuffer buffer,long offset,long length){
        owner.requireOwner();if(buffer.owner()!=owner)throw new IllegalArgumentException("Foreign buffer");
        if(offset<0||length<0||offset>buffer.size()||length>buffer.size()-offset)throw new IllegalArgumentException("Buffer query range outside allocation");
        return bufferReads.overlaps(buffer,offset,length);
    }
    private void captureReads(PassBindings state,boolean indexed){
        var active=state.pipeline().activeUniforms();
        for(int slot=active.nextSetBit(0);slot>=0;slot=active.nextSetBit(slot+1))if(state.uniforms()[slot] instanceof GpuBufferSlice slice)readBuffer(slice.buffer(),slice.offset(),slice.length());
        for(var expected:state.pipeline().info().vertexBuffers()){
            GpuBufferSlice slice=state.vertices().get(expected.bufferSlot());readBuffer(slice.buffer(),slice.offset(),slice.length());
        }
        if(indexed)readBuffer(state.indices(),0,state.indices().size());
    }
    private static boolean usesInstanceBindings(PassBindings state){
        for(var binding:state.pipeline().info().vertexBuffers())if(binding.stepRate()>0)return true;
        return false;
    }
    private void readBuffer(GpuBuffer buffer,long offset,long length){
        if(!(buffer instanceof OverhaulBuffer own)||own.owner()!=owner)throw new IllegalArgumentException("Foreign read buffer");
        if(offset<0||length<0||offset>own.size()||length>own.size()-offset)throw new IllegalArgumentException("Draw read outside buffer allocation");
        bufferReads.add(own,offset,length);
    }
    public void record(VkCommandBuffer command){
        owner.requireOpen();if(closed||recorded)throw new IllegalStateException("Render pass can be recorded only once");
        if(!sealed)seal();recorded=true;
        for(var reset:timestampResets)owner.resetTimestamp(command,reset.pool(),reset.index());
        for(var view:sampled)transitions.add(view.image().recordTransition(command,view.baseMip(),view.mipCount(),view.baseLayer(),view.layerCount(),VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
        attachments.begin(command,transitions);
        try{for(Command entry:commands){if(entry.bindings()!=null)entry.bindings().bind(command,owner,attachments,descriptors);entry.draw().accept(command);}}
        finally{vkCmdEndRenderPass(command);}
        recordingSucceeded=true;
    }
    /** Called only after the encoder's native queue submission succeeds. */
    public void complete(){
        owner.requireOwner();if(closed||!recordingSucceeded||complete)throw new IllegalStateException("Invalid render-pass completion");
        for(var change:transitions)change.commit();
        for(var reset:timestampResets)owner.timestampSubmitted(reset.pool(),reset.index(),reset.generation());
        owner.acceptDraws(drawMetrics);complete=true;
    }
    private static void checkUnsigned(int... values){for(int value:values)if(value<0)throw new IllegalArgumentException("Negative draw parameter");}
    private void validateDirectMultiDraw(int count){requireCapture();checkUnsigned(count);if(pipeline==null)throw new IllegalStateException("Multi-draw requires a pipeline");if(count>1&&pipeline.usesDrawIndex())throw new UnsupportedOperationException("Native direct multi-draw extension is unavailable for DrawIndex shaders");}
    private void releasePins(){RuntimeException failure=null;for(int index=pins.size()-1;index>=0;index--)try{pins.get(index).close();}catch(Exception error){if(failure==null)failure=new IllegalStateException("Resource pin release failed",error);else failure.addSuppressed(error);}pins.clear();retained.clear();if(failure!=null)throw failure;}
    @Override public void close(){
        if(closed)return;owner.requireOwner();
        if(submission!=null){submission.await();if(closed)return;}
        closed=true;
        try{descriptors.close();attachments.close();}
        finally{try{for(int index=transitions.size()-1;index>=0;index--)transitions.get(index).close();transitions.clear();}finally{commands.clear();timestampResets.clear();timestampKeys.clear();sampled.clear();bufferReads.clear();releasePins();}}
    }
}
