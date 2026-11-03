package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
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
    private BlazePrograms programs;
    private BlazeTextures textures;
    private SwapchainPresenter presenter;
    private BlazeTarget backBuffer;
    private int width,height,drawTarget,readTarget,nextHandle=1;
    private boolean closed;
    private SwapchainPresentMode mode=SwapchainPresentMode.FIFO;
    private Runnable windowConstructionCleanup;
    private boolean windowReady;

    public BlazeRenderer(long glfwNoApiWindow,int width,int height){
        windowHandle=glfwNoApiWindow;
        context=VulkanContext.openWindowed(glfwNoApiWindow);
        try{
            allocator=new GpuAllocator(context);commands=new ImmediateCommands(context);
            programs=new BlazePrograms(this::forgetProgram);textures=new BlazeTextures(context,allocator,commands);
            backBuffer=new BlazeTarget(context,allocator,commands,Math.max(1,width),Math.max(1,height),true);
            presenter=new SwapchainPresenter(context,width,height);this.width=width;this.height=height;
            state.viewport(0,0,Math.max(0,width),Math.max(0,height));
        }catch(RuntimeException|Error failure){try{close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }

    public VulkanContext context(){requireOpen();return context;}
    public GpuAllocator allocator(){requireOpen();return allocator;}
    public ImmediateCommands commands(){requireOpen();return commands;}
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
    public BlazeMesh newMesh(){requireOpen();BlazeMesh mesh=new BlazeMesh(allocator,commands,meshes::remove);meshes.add(mesh);return mesh;}
    public int registerTarget(BlazeTarget target){int id=nextHandle();targets.put(id,target);return id;}
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

    public void draw(BlazeMesh mesh,Matrix4f modelView,Matrix4f projection,ShaderInstance shader){
        requireOpen();shader.setDefaultUniforms(mesh.state().mode(),modelView,projection,Minecraft.getInstance().getWindow());
        shader.apply();
        try{draw(mesh);}finally{shader.clear();}
    }
    public void draw(BlazeMesh mesh){draw(mesh,programs.activeSnapshot());}
    public void clear(int mask){requireOpen();currentTarget().clear(commands,mask,state.snapshot(),state.clear());}
    private void draw(BlazeMesh mesh,BlazeShaderSnapshot shader){
        requireOpen();if(mesh.state().indexCount()==0)return;BlazeTarget target=currentTarget();
        try(BlazePass pass=new BlazePass(this,target,mesh,shader,state.snapshot())){
            target.prepare(commands);commands.submit(pass::record);
        }
    }

    NativeGraphicsPipeline pipeline(BlazeProgramCompiler.Program program,MeshData.DrawState mesh,
            BlazeDrawState.Snapshot draw,BlazeTarget target,long descriptorLayout){
        int topology=topology(mesh.mode());
        if((topology==VK_PRIMITIVE_TOPOLOGY_LINE_LIST||topology==VK_PRIMITIVE_TOPOLOGY_LINE_STRIP)&&draw.lineWidth()!=1)throw new UnsupportedOperationException("Wide native lines are not enabled");
        PipelineKey key=new PipelineKey(program,mesh.format(),mesh.mode(),draw.depth(),draw.color(),draw.logic(),draw.cull(),draw.polygonMode(),target.depth()!=null);
        NativeGraphicsPipeline existing=pipelines.get(key);if(existing!=null)return existing;
        if(pipelines.size()==256){var oldest=pipelines.entrySet().iterator();var entry=oldest.next();entry.getValue().close();oldest.remove();}
        var description=new NativeGraphicsPipeline.Description(List.of(
                new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_VERTEX_BIT,"main",program.vertex().directBuffer()),
                new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_FRAGMENT_BIT,"main",program.fragment().directBuffer())),
                List.of(new NativeGraphicsPipeline.Binding(0,mesh.format().getVertexSize(),0)),new BlazeVertexLayout(mesh.format()).attributes(program.bindings()),
                new long[]{descriptorLayout},0,topology,draw.polygonMode(),draw.cull(),VK_FRONT_FACE_CLOCKWISE,draw.depth(),
                List.of(draw.color()),true,0,0,draw.logic());
        NativeGraphicsPipeline pipeline=new NativeGraphicsPipeline(context,target.renderPass(),description);
        try{pipelines.put(key,pipeline);return pipeline;}catch(RuntimeException|Error failure){pipeline.close();throw failure;}
    }
    public void forgetProgram(BlazeProgramCompiler.Program program){
        requireOpen();var iterator=pipelines.entrySet().iterator();while(iterator.hasNext()){var entry=iterator.next();if(entry.getKey().program()==program){entry.getValue().close();iterator.remove();}}
    }
    private static int topology(VertexFormat.Mode mode){return switch(mode){
        case QUADS,LINES,TRIANGLES->VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;case TRIANGLE_STRIP->VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
        case TRIANGLE_FAN->VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;case LINE_STRIP->VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
        case DEBUG_LINE_STRIP->VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;case DEBUG_LINES->VK_PRIMITIVE_TOPOLOGY_LINE_LIST;};}
    private record PipelineKey(BlazeProgramCompiler.Program program,VertexFormat format,VertexFormat.Mode mode,
            NativeGraphicsPipeline.Depth depth,NativeGraphicsPipeline.Color color,NativeGraphicsPipeline.ColorLogic logic,int cull,int polygonMode,boolean depthAttachment){}

    public void resize(int width,int height){
        requireOpen();if(width<0||height<0)throw new IllegalArgumentException("Negative window size");
        presenter.configure(width,height,mode);this.width=width;this.height=height;
        if(width>0&&height>0&&(backBuffer.width()!=width||backBuffer.height()!=height)){
            BlazeTarget next=new BlazeTarget(context,allocator,commands,width,height,true);backBuffer.close();backBuffer=next;
        }
    }
    public void presentMode(boolean vsync){
        requireOpen();var supported=SwapchainPresentMode.supported(context);
        mode=vsync?SwapchainPresentMode.FIFO:supported.contains(SwapchainPresentMode.IMMEDIATE)?SwapchainPresentMode.IMMEDIATE:
                supported.contains(SwapchainPresentMode.MAILBOX)?SwapchainPresentMode.MAILBOX:SwapchainPresentMode.FIFO;
        presenter.configure(width,height,mode);
    }
    public void present(){requireOpen();if(width>0&&height>0&&presenter.acquireNextTexture()){presenter.blitAcquiredImage(backBuffer.colorView(),true);presenter.presentAcquired();}}
    private void requireOpen(){context.device();if(closed)throw new IllegalStateException("Blaze3D device is closed");}
    @Override public void close(){
        if(closed)return;
        Throwable failure=null;
        failure=release(context::waitIdle,failure);
        for(BlazeMesh mesh:new ArrayList<>(meshes))failure=release(mesh::close,failure);
        for(NativeGraphicsPipeline pipeline:pipelines.values())failure=release(pipeline::close,failure);pipelines.clear();
        for(BlazeTarget target:new ArrayList<>(targets.values()))failure=release(target::close,failure);targets.clear();
        if(textures!=null)failure=release(textures::close,failure);if(programs!=null)failure=release(programs::close,failure);
        if(backBuffer!=null)failure=release(backBuffer::close,failure);if(presenter!=null)failure=release(presenter::close,failure);
        if(commands!=null)failure=release(commands::close,failure);if(allocator!=null)failure=release(allocator::close,failure);
        failure=release(context::close,failure);closed=true;
        if(failure instanceof RuntimeException)throw(RuntimeException)failure;if(failure instanceof Error)throw(Error)failure;
    }
    private static Throwable release(Runnable action,Throwable failure){try{action.run();}catch(RuntimeException|Error problem){if(failure==null)return problem;failure.addSuppressed(problem);}return failure;}
}
