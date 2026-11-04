package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.blaze.BlazeProgramCompiler;
import dev.overhaul.vulkan.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.*;

/** The single owner of native resources for one selected no-API game window. */
public final class BlazeRenderer implements AutoCloseable {
    private final VulkanContext context;
    private final long windowHandle;
    private final BlazeDrawState state=new BlazeDrawState();
    private final Set<BlazeMesh> meshes=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Integer,BlazeTarget> targets=new HashMap<>();
    private final LinkedHashMap<PipelineKey,NativeGraphicsPipeline> pipelines=new LinkedHashMap<>(32,.75f,true);
    private GpuAllocator allocator;
    private ImmediateCommands commands;
    private BlazeQueue queue;
    private BlazeBufferPool hostStorage,geometryStorage;
    private BlazeDescriptors descriptors;
    private BlazeTransfers transfers;
    private BlazePrograms programs;
    private BlazeTextures textures;
    private SwapchainPresenter presenter;
    private BlazeTarget backBuffer;
    private int width,height,drawTarget,readTarget,nextHandle=1;
    private boolean closed;
    private SwapchainPresentMode mode=SwapchainPresentMode.FIFO;
    private Runnable windowConstructionCleanup;
    private boolean windowReady;
    private int maximumUniformRange;

    public BlazeRenderer(long glfwNoApiWindow,int width,int height){
        windowHandle=glfwNoApiWindow;
        context=VulkanContext.openWindowed(glfwNoApiWindow);
        try{
            allocator=new GpuAllocator(context);commands=new ImmediateCommands(context);
            queue=new BlazeQueue(context);
            try(MemoryStack stack=MemoryStack.stackPush()){
                var properties=VkPhysicalDeviceProperties.malloc(stack);vkGetPhysicalDeviceProperties(context.physicalDevice(),properties);
                int alignment=Math.toIntExact(Math.max(4,Math.max(properties.limits().nonCoherentAtomSize(),properties.limits().minUniformBufferOffsetAlignment())));
                maximumUniformRange=properties.limits().maxUniformBufferRange();
                hostStorage=new BlazeBufferPool(allocator,queue,true,VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_SRC_BIT,2*1024*1024,128L*1024*1024,alignment);
                geometryStorage=new BlazeBufferPool(allocator,queue,false,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT|VK_BUFFER_USAGE_INDEX_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT,8*1024*1024,512L*1024*1024,16);
            }
            descriptors=new BlazeDescriptors(context,queue);transfers=new BlazeTransfers(context,queue,hostStorage,geometryStorage);
            programs=new BlazePrograms(this::forgetProgram);textures=new BlazeTextures(context,allocator,commands,queue,transfers);
            backBuffer=new BlazeTarget(context,allocator,commands,Math.max(1,width),Math.max(1,height),true);
            backBuffer.attach(queue);
            presenter=new SwapchainPresenter(context,width,height);this.width=width;this.height=height;
            state.viewport(0,0,Math.max(0,width),Math.max(0,height));
        }catch(RuntimeException|Error failure){try{close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }

    public VulkanContext context(){requireOpen();return context;}
    public GpuAllocator allocator(){requireOpen();return allocator;}
    /** Explicit immediate callers are ordering boundaries; ordinary draws/uploads use the queued lane. */
    public ImmediateCommands commands(){requireOpen();queue.flush();return commands;}
    BlazeBufferPool hostStorage(){requireOpen();return hostStorage;}
    BlazeDescriptors descriptors(){requireOpen();return descriptors;}
    int maximumUniformRange(){return maximumUniformRange;}
    public BlazePrograms programs(){requireOpen();return programs;}
    public BlazeTextures textures(){requireOpen();return textures;}
    public BlazeDrawState drawState(){requireOpen();return state;}
    public int width(){return width;}public int height(){return height;}
    public boolean isClosed(){return closed;}
    public long windowHandle(){return windowHandle;}
    public void pendingWindowCleanup(Runnable cleanup){
        requireOpen();java.util.Objects.requireNonNull(cleanup,"cleanup");
        if(windowReady||windowConstructionCleanup!=null)throw new IllegalStateException("Window cleanup is already configured");
        windowConstructionCleanup=cleanup;
    }
    public void windowReady(){requireOpen();windowConstructionCleanup=null;windowReady=true;}
    public void abortWindowConstruction(){
        Runnable cleanup=windowConstructionCleanup;windowConstructionCleanup=null;
        Throwable failure=release(this::close,null);
        if(cleanup!=null)failure=release(cleanup,failure);
        if(failure instanceof RuntimeException)throw(RuntimeException)failure;if(failure instanceof Error)throw(Error)failure;
    }
    public int nextHandle(){requireOpen();if(nextHandle==Integer.MAX_VALUE)throw new IllegalStateException("Blaze3D handles exhausted");return nextHandle++;}
    public BlazeMesh newMesh(){requireOpen();BlazeMesh mesh=new BlazeMesh(queue,transfers,meshes::remove);meshes.add(mesh);return mesh;}
    public int registerTarget(BlazeTarget target){int id=nextHandle();target.attach(queue);targets.put(id,target);return id;}
    public void unregisterTarget(int id){requireOpen();targets.remove(id);if(drawTarget==id)drawTarget=0;if(readTarget==id)readTarget=0;state.framebufferBindings(drawTarget,readTarget);}
    public BlazeTarget currentTarget(){return target(drawTarget);}
    public BlazeTarget readTarget(){return target(readTarget);}
    public BlazeTarget defaultTarget(){requireOpen();return backBuffer;}
    private BlazeTarget target(int id){requireOpen();BlazeTarget target=id==0?backBuffer:targets.get(id);if(target==null)throw new IllegalArgumentException("Unknown framebuffer "+id);return target;}
    public void bindFramebuffer(int kind,int id){
        target(id);
        switch(kind){case 0x8D40-> {drawTarget=id;readTarget=id;}case 0x8CA9->drawTarget=id;case 0x8CA8->readTarget=id;
            default->throw new UnsupportedOperationException("Framebuffer target: 0x"+Integer.toHexString(kind));}
        state.framebufferBindings(drawTarget,readTarget);
    }

    public void draw(BlazeMesh mesh){draw(mesh,programs.activeSnapshot());}
    public void clear(int mask){requireOpen();currentTarget().clear(commands,mask,state.snapshot(),state.clear());}
    public void copyImage(GpuImage source,ImageRegion from,GpuImage destination,ImageRegion to){requireOpen();transfers.copy(source,from,destination,to);}
    private void draw(BlazeMesh mesh,BlazeShaderSnapshot shader){
        requireOpen();if(mesh.state().indexCount()==0)return;BlazeTarget target=currentTarget();
        queue.add(new BlazePass(this,target,mesh,shader,state.snapshot()),0);
    }

    NativeGraphicsPipeline pipeline(BlazeProgramCompiler.Program program,BlazeMeshState mesh,
            BlazeDrawState.Snapshot draw,BlazeTarget target,long descriptorLayout){
        int topology=mesh.topology();
        if((topology==VK_PRIMITIVE_TOPOLOGY_LINE_LIST||topology==VK_PRIMITIVE_TOPOLOGY_LINE_STRIP)&&draw.lineWidth()!=1)throw new UnsupportedOperationException("Wide native lines are not enabled");
        PipelineKey key=new PipelineKey(program,mesh.layout(),topology,draw.depth(),draw.color(),draw.logic(),draw.cull(),draw.polygonMode(),target.depth()!=null);
        NativeGraphicsPipeline existing=pipelines.get(key);if(existing!=null)return existing;
        if(pipelines.size()==256){var oldest=pipelines.entrySet().iterator();var entry=oldest.next();oldest.remove();queue.retire(entry.getValue());}
        var description=new NativeGraphicsPipeline.Description(List.of(
                new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_VERTEX_BIT,"main",program.vertex().directBuffer()),
                new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_FRAGMENT_BIT,"main",program.fragment().directBuffer())),
                List.of(new NativeGraphicsPipeline.Binding(0,mesh.layout().stride(),0)),new BlazeVertexLayout(mesh.layout()).attributes(program.bindings()),
                new long[]{descriptorLayout},0,topology,draw.polygonMode(),draw.cull(),VK_FRONT_FACE_CLOCKWISE,draw.depth(),
                List.of(draw.color()),true,0,0,draw.logic());
        NativeGraphicsPipeline pipeline=new NativeGraphicsPipeline(context,target.renderPass(),description);
        try{pipelines.put(key,pipeline);return pipeline;}catch(RuntimeException|Error failure){pipeline.close();throw failure;}
    }
    public void forgetProgram(BlazeProgramCompiler.Program program){
        requireOpen();var iterator=pipelines.entrySet().iterator();while(iterator.hasNext()){var entry=iterator.next();if(entry.getKey().program()==program){iterator.remove();queue.retire(entry.getValue());}}
        if(descriptors!=null)descriptors.forget(program);
    }
    private record PipelineKey(BlazeProgramCompiler.Program program,BlazeMeshState.Layout layout,int topology,
            NativeGraphicsPipeline.Depth depth,NativeGraphicsPipeline.Color color,NativeGraphicsPipeline.ColorLogic logic,int cull,int polygonMode,boolean depthAttachment){}

    public void resize(int width,int height){
        requireOpen();if(width<0||height<0)throw new IllegalArgumentException("Negative window size");
        queue.flush();presenter.configure(width,height,mode);this.width=width;this.height=height;
        if(width>0&&height>0&&(backBuffer.width()!=width||backBuffer.height()!=height)){
            BlazeTarget next=new BlazeTarget(context,allocator,commands,width,height,true);next.attach(queue);backBuffer.close();backBuffer=next;
        }
    }
    public void presentMode(boolean vsync){
        requireOpen();var supported=SwapchainPresentMode.supported(context);
        mode=vsync?SwapchainPresentMode.FIFO:supported.contains(SwapchainPresentMode.IMMEDIATE)?SwapchainPresentMode.IMMEDIATE:
                supported.contains(SwapchainPresentMode.MAILBOX)?SwapchainPresentMode.MAILBOX:SwapchainPresentMode.FIFO;
        queue.flush();presenter.configure(width,height,mode);
    }
    public void present(){requireOpen();queue.flush();if(width>0&&height>0&&presenter.acquireNextTexture()){presenter.blitAcquiredImageAsync(backBuffer.colorView(),true);presenter.presentAcquired();}}
    private void requireOpen(){context.device();if(closed)throw new IllegalStateException("Blaze3D device is closed");}
    @Override public void close(){
        if(closed)return;
        Throwable failure=null;
        if(queue!=null)failure=release(queue::drain,failure);
        failure=release(context::waitIdle,failure);
        for(BlazeMesh mesh:new ArrayList<>(meshes))failure=release(mesh::close,failure);
        for(NativeGraphicsPipeline pipeline:pipelines.values())failure=release(pipeline::close,failure);pipelines.clear();
        for(BlazeTarget target:new ArrayList<>(targets.values()))failure=release(target::close,failure);targets.clear();
        if(textures!=null)failure=release(textures::close,failure);if(programs!=null)failure=release(programs::close,failure);
        if(backBuffer!=null)failure=release(backBuffer::close,failure);if(presenter!=null)failure=release(presenter::close,failure);
        if(descriptors!=null)failure=release(descriptors::close,failure);
        if(geometryStorage!=null)failure=release(geometryStorage::close,failure);if(hostStorage!=null)failure=release(hostStorage::close,failure);
        if(queue!=null)failure=release(queue::close,failure);
        if(commands!=null)failure=release(commands::close,failure);if(allocator!=null)failure=release(allocator::close,failure);
        failure=release(context::close,failure);closed=true;
        if(failure instanceof RuntimeException)throw(RuntimeException)failure;if(failure instanceof Error)throw(Error)failure;
    }
    private static Throwable release(Runnable action,Throwable failure){try{action.run();}catch(RuntimeException|Error problem){if(failure==null)return problem;failure.addSuppressed(problem);}return failure;}
}
