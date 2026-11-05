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
    /** Names from this value up are raw OpenGL framebuffers that mods assemble; {@link BlazeRawFramebuffers} owns them. */
    static final int FOREIGN_FRAMEBUFFERS=0x40000000;
    private final VulkanContext context;
    private final long windowHandle;
    private final BlazeDrawState state=new BlazeDrawState();
    private final Set<BlazeMesh> meshes=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<GpuBuffer> glDeviceBuffers=Collections.newSetFromMap(new IdentityHashMap<>());
    private BlazeTerrain terrain;
    private BlazeDh dh;
    private BlazeRawFramebuffers rawFramebuffers;
    private final Map<Integer,Float> foreignClearDepths=new HashMap<>();
    private final Map<Integer,BlazeTarget> targets=new HashMap<>();
    private final LinkedHashMap<PipelineKey,NativeGraphicsPipeline> pipelines=new LinkedHashMap<>(32,.75f,true);
    private GpuAllocator allocator;
    private ImmediateCommands commands;
    private BlazeQueue queue;
    private BlazeBufferPool hostStorage,geometryStorage;
    private BlazePackScene packScene;
    private final java.util.Map<BlazeProgramCompiler.Program,BlazeProgramShape> shapes=new java.util.IdentityHashMap<>();
    private BlazeHostArena uniformArena,uploadArena,streamArena;
    private BlazeBufferPool streamStorage;
    private BlazeSequenceIndices sequenceIndices;
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
    private final List<AutoCloseable> closeWith=new ArrayList<>();
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
                hostStorage=new BlazeBufferPool(allocator,queue,true,VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT,2*1024*1024,128L*1024*1024,alignment);
                geometryStorage=new BlazeBufferPool(allocator,queue,false,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT|VK_BUFFER_USAGE_INDEX_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT,8*1024*1024,512L*1024*1024,16);
                uniformArena=new BlazeHostArena(hostStorage,alignment);uploadArena=new BlazeHostArena(hostStorage,Math.max(16,alignment));
                streamStorage=new BlazeBufferPool(allocator,queue,true,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT|VK_BUFFER_USAGE_INDEX_BUFFER_BIT,4*1024*1024,256L*1024*1024,16);
                streamArena=new BlazeHostArena(streamStorage,16);
            }
            descriptors=new BlazeDescriptors(context,queue);transfers=new BlazeTransfers(context,queue,hostStorage,geometryStorage,uploadArena,streamArena);
            sequenceIndices=new BlazeSequenceIndices(transfers,queue);
            programs=new BlazePrograms(this::forgetProgram);textures=new BlazeTextures(context,allocator,commands,queue,transfers);
            rawFramebuffers=new BlazeRawFramebuffers(context,allocator,queue,textures);
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
    BlazeHostArena uniformArena(){requireOpen();return uniformArena;}
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
    /** Tier code holding native resources of this device closes with it, once nothing is in flight. */
    public void closeWith(AutoCloseable resource){requireOpen();closeWith.add(resource);}
    public void windowReady(){requireOpen();windowConstructionCleanup=null;windowReady=true;}
    public void abortWindowConstruction(){
        Runnable cleanup=windowConstructionCleanup;windowConstructionCleanup=null;
        Throwable failure=release(this::close,null);
        if(cleanup!=null)failure=release(cleanup,failure);
        if(failure instanceof RuntimeException)throw(RuntimeException)failure;if(failure instanceof Error)throw(Error)failure;
    }
    public BlazeTerrain terrain(){requireOpen();if(terrain==null)terrain=new BlazeTerrain(this);return terrain;}
    public BlazeDh dh(){requireOpen();if(dh==null)dh=new BlazeDh(this);return dh;}
    void enqueue(BlazeQueue.Operation operation){queue.add(operation,0);}
    GpuBuffer newGlDeviceBuffer(long size){
        requireOpen();
        GpuBuffer buffer=allocator.deviceBuffer(Math.max(size,4),VK_BUFFER_USAGE_VERTEX_BUFFER_BIT|VK_BUFFER_USAGE_INDEX_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        glDeviceBuffers.add(buffer);return buffer;
    }
    /** Captured terrain draws reach the queue first: the retirement must follow every draw that still reads the buffer. */
    void retireGlDeviceBuffer(GpuBuffer buffer){requireOpen();if(terrain!=null)terrain.flush();if(dh!=null)dh.flush();glDeviceBuffers.remove(buffer);queue.retire(buffer);}
    void writeGlDeviceBuffer(GpuBuffer target,long offset,long source,long bytes){requireOpen();transfers.write(target,offset,source,bytes);}
    void copyGlDeviceBuffer(GpuBuffer source,long sourceOffset,GpuBuffer target,long targetOffset,long bytes){requireOpen();transfers.copy(source,sourceOffset,target,targetOffset,bytes);}
    public int nextHandle(){requireOpen();if(nextHandle==Integer.MAX_VALUE)throw new IllegalStateException("Blaze3D handles exhausted");return nextHandle++;}
    /** A streaming mesh is replaced before nearly every draw, so its bytes go where the GPU reads them. */
    public BlazeMesh newMesh(boolean stream){requireOpen();BlazeMesh mesh=new BlazeMesh(queue,transfers,sequenceIndices,stream,meshes::remove);meshes.add(mesh);return mesh;}
    public int registerTarget(BlazeTarget target){int id=nextHandle();target.attach(queue);targets.put(id,target);return id;}
    BlazeRawFramebuffers rawFramebuffers(){requireOpen();return rawFramebuffers;}
    public void unregisterTarget(int id){requireOpen();if(id>=FOREIGN_FRAMEBUFFERS){rawFramebuffers.delete(id);foreignClearDepths.remove(id);}else targets.remove(id);if(drawTarget==id)drawTarget=0;if(readTarget==id)readTarget=0;state.framebufferBindings(drawTarget,readTarget);}
    public BlazeTarget currentTarget(){return target(drawTarget);}
    public BlazeTarget readTarget(){return target(readTarget);}
    public BlazeTarget defaultTarget(){requireOpen();return backBuffer;}
    /** Null only for a raw framebuffer with nothing renderable attached. */
    public BlazeTarget target(int id){requireOpen();if(id>=FOREIGN_FRAMEBUFFERS)return rawFramebuffers.target(id);BlazeTarget target=id==0?backBuffer:targets.get(id);if(target==null)throw new IllegalArgumentException("Unknown framebuffer "+id);return target;}
    public void bindFramebuffer(int kind,int id){
        if(id<FOREIGN_FRAMEBUFFERS)target(id);
        switch(kind){
            case 0x8D40-> {drawTarget=id;readTarget=id;}
            case 0x8CA9->drawTarget=id;
            case 0x8CA8->readTarget=id;
            default->throw new UnsupportedOperationException("Framebuffer target: 0x"+Integer.toHexString(kind));}
        state.framebufferBindings(drawTarget,readTarget);
    }
    /** The name bound to a framebuffer binding point, as raw attachment calls address it. */
    int boundFramebuffer(int kind){return kind==0x8CA8?readTarget:drawTarget;}
    /** True while the draw framebuffer has nowhere to put output: it must not land on the last real target. */
    boolean drawingNowhere(){return currentTarget()==null;}
    boolean readingNowhere(){return readTarget()==null;}

    public void draw(BlazeMesh mesh){
        if(drawingNowhere())return;
        String missing=programs.incompleteActive();
        if(missing!=null){BlazeGlBoundary.reportOnce("incomplete program "+missing,"A draw used program "+missing+", which has no finished shader instance; it draws nothing");return;}
        BlazeTarget target=currentTarget();BlazeShaderSnapshot shader=programs.activeSnapshot();
        if(packScene!=null&&packScene.routes(target))packScene.draw(mesh,shader);else draw(mesh,target,shader);
    }
    void packScene(BlazePackScene scene){packScene=scene;}
    BlazeTarget packTarget(List<GpuImageView> colors,GpuImageView depth){requireOpen();BlazeTarget target=new BlazeTarget(context,colors,depth);target.attach(queue);return target;}
    public void blitImage(GpuImage source,int[] from,GpuImage destination,int[] to,int filter){requireOpen();transfers.blit(source,from,destination,to,filter);}
    public void clear(int mask){clear(mask,clearValues());}
    /**
     * Distant Horizons sets a reverse-Z clear depth for its own framebuffer and never restores it, so a depth set
     * while a mod framebuffer is bound stays with that framebuffer. It must not reach the game's targets or another
     * mod's framebuffer: Xaero's minimap clears without setting a depth, and a cleared 0 rejects every LEQUAL draw.
     */
    void clearDepth(double depth){if(drawTarget>=FOREIGN_FRAMEBUFFERS)foreignClearDepths.put(drawTarget,(float)Math.max(0,Math.min(1,depth)));else state.clearDepth(depth);}
    BlazeDrawState.Clear clearValues(){
        var values=state.clear();
        Float own=drawTarget>=FOREIGN_FRAMEBUFFERS?foreignClearDepths.get(drawTarget):null;
        return own!=null?new BlazeDrawState.Clear(values.r(),values.g(),values.b(),values.a(),own):values;
    }
    void clear(int mask,BlazeDrawState.Clear values){requireOpen();BlazeTarget target=currentTarget();if(target!=null)target.clear(commands,mask,state.snapshot(),values);}
    public void copyImage(GpuImage source,ImageRegion from,GpuImage destination,ImageRegion to){requireOpen();transfers.copy(source,from,destination,to);}
    void draw(BlazeMesh mesh,BlazeTarget target,BlazeShaderSnapshot shader){draw(mesh,target,shader,state.snapshot());}
    /** A draw whose raster state is not the game's, as the light camera's are. */
    void draw(BlazeMesh mesh,BlazeTarget target,BlazeShaderSnapshot shader,BlazeDrawState.Snapshot snapshot){
        requireOpen();if(mesh.state().indexCount()==0)return;
        try{
            long start=BlazeStats.start();
            if(BlazeOptimization.DRAW_MERGE.on()&&queue.last() instanceof BlazePass open&&open.append(this,target,mesh,shader,snapshot)){BlazeStats.capture(start);return;}
            BlazePass pass=new BlazePass(this,target,mesh,shader,snapshot);BlazeStats.pass();BlazeStats.capture(start);queue.add(pass,0);
        }
        catch(BlazePass.SamplingFeedback feedback){BlazeGlBoundary.reportOnce("sampling feedback","A draw sampled the framebuffer it renders to; it was dropped");}
    }

    NativeGraphicsPipeline pipeline(BlazeProgramCompiler.Program program,BlazeMeshState mesh,
            BlazeDrawState.Snapshot draw,BlazeTarget target,long descriptorLayout){
        int topology=mesh.topology();
        if((topology==VK_PRIMITIVE_TOPOLOGY_LINE_LIST||topology==VK_PRIMITIVE_TOPOLOGY_LINE_STRIP)&&draw.lineWidth()!=1)throw new UnsupportedOperationException("Wide native lines are not enabled");
        PipelineKey key=new PipelineKey(program,mesh.layout(),topology,draw.depth(),draw.color(),draw.logic(),draw.cull(),draw.polygonMode(),target.colorFormats(),target.depth()==null?null:target.depth().format());
        NativeGraphicsPipeline existing=pipelines.get(key);if(existing!=null)return existing;
        if(pipelines.size()==256){var oldest=pipelines.entrySet().iterator();var entry=oldest.next();oldest.remove();queue.retire(entry.getValue());}
        BlazeProgramShape shape=shapes.get(program);
        var description=new NativeGraphicsPipeline.Description(List.of(
                new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_VERTEX_BIT,"main",program.vertex().directBuffer()),
                new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_FRAGMENT_BIT,"main",program.fragment().directBuffer())),
                shape!=null?shape.bindings(mesh.layout()):List.of(new NativeGraphicsPipeline.Binding(0,mesh.layout().stride(),0)),
                shape!=null?shape.attributes(mesh.layout()):new BlazeVertexLayout(mesh.layout()).attributes(program.bindings()),
                new long[]{descriptorLayout},0,topology,draw.polygonMode(),draw.cull(),VK_FRONT_FACE_CLOCKWISE,draw.depth(),
                shape!=null?shape.colors(draw.color()):List.of(draw.color()),true,0,0,draw.logic());
        NativeGraphicsPipeline pipeline=new NativeGraphicsPipeline(context,target.renderPass(),description);
        try{pipelines.put(key,pipeline);return pipeline;}catch(RuntimeException|Error failure){pipeline.close();throw failure;}
    }
    void shape(BlazeProgramCompiler.Program program,BlazeProgramShape shape){requireOpen();shapes.put(program,shape);}
    long vertexConstants(BlazeProgramCompiler.Program program){BlazeProgramShape shape=shapes.get(program);return shape==null?0:shape.vertexConstants();}
    public void forgetProgram(BlazeProgramCompiler.Program program){
        requireOpen();shapes.remove(program);var iterator=pipelines.entrySet().iterator();while(iterator.hasNext()){var entry=iterator.next();if(entry.getKey().program()==program){iterator.remove();queue.retire(entry.getValue());}}
        if(descriptors!=null)descriptors.forget(program);
    }
    private record PipelineKey(BlazeProgramCompiler.Program program,BlazeMeshState.Layout layout,int topology,
            NativeGraphicsPipeline.Depth depth,NativeGraphicsPipeline.Color color,NativeGraphicsPipeline.ColorLogic logic,int cull,int polygonMode,List<ImageFormat> colorFormats,ImageFormat depthFormat){}

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
    public void present(){requireOpen();queue.flush();long start=BlazeStats.start();if(width>0&&height>0&&presenter.acquireNextTexture()){presenter.blitAcquiredImageAsync(backBuffer.colorView(),true);presenter.presentAcquired();}BlazeStats.present(start);}
    private void requireOpen(){context.device();if(closed)throw new IllegalStateException("Blaze3D device is closed");}
    @Override public void close(){
        if(closed)return;
        Throwable failure=null;
        if(queue!=null)failure=release(queue::drain,failure);
        failure=release(context::waitIdle,failure);
        for(AutoCloseable resource:closeWith)failure=release(()->{try{resource.close();}catch(Exception problem){throw new IllegalStateException(problem);}},failure);closeWith.clear();
        for(BlazeMesh mesh:new ArrayList<>(meshes))failure=release(mesh::close,failure);
        if(terrain!=null)failure=release(terrain::close,failure);
        if(dh!=null)failure=release(dh::close,failure);
        for(GpuBuffer buffer:new ArrayList<>(glDeviceBuffers))failure=release(buffer::close,failure);glDeviceBuffers.clear();
        for(NativeGraphicsPipeline pipeline:pipelines.values())failure=release(pipeline::close,failure);pipelines.clear();
        for(BlazeTarget target:new ArrayList<>(targets.values()))failure=release(target::close,failure);targets.clear();
        if(rawFramebuffers!=null)failure=release(rawFramebuffers::close,failure);
        if(textures!=null)failure=release(textures::close,failure);if(programs!=null)failure=release(programs::close,failure);
        if(backBuffer!=null)failure=release(backBuffer::close,failure);if(presenter!=null)failure=release(presenter::close,failure);
        if(descriptors!=null)failure=release(descriptors::close,failure);
        if(sequenceIndices!=null)failure=release(sequenceIndices::close,failure);
        if(streamArena!=null)failure=release(streamArena::close,failure);if(streamStorage!=null)failure=release(streamStorage::close,failure);
        if(uniformArena!=null)failure=release(uniformArena::close,failure);if(uploadArena!=null)failure=release(uploadArena::close,failure);if(geometryStorage!=null)failure=release(geometryStorage::close,failure);if(hostStorage!=null)failure=release(hostStorage::close,failure);
        if(queue!=null)failure=release(queue::close,failure);
        if(commands!=null)failure=release(commands::close,failure);if(allocator!=null)failure=release(allocator::close,failure);
        failure=release(context::close,failure);closed=true;
        if(failure instanceof RuntimeException)throw(RuntimeException)failure;if(failure instanceof Error)throw(Error)failure;
    }
    private static Throwable release(Runnable action,Throwable failure){try{action.run();}catch(RuntimeException|Error problem){if(failure==null)return problem;failure.addSuppressed(problem);}return failure;}
}
