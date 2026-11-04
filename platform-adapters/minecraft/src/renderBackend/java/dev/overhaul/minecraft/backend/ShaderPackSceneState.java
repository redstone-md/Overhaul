package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.overhaul.minecraft.shader.CascadeShadowFrame;
import dev.overhaul.minecraft.shader.OwnedTerrainShaders;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.pack.CascadeShadowSettings;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackRenderTargets;
import dev.overhaul.shader.pack.PackShadowSettings;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.PackGraphRuntime;
import java.util.Map;

/** Device-owned publication point for one ordinary world frame; no static registry or cross-device state. */
public final class ShaderPackSceneState implements AutoCloseable {
    private final OverhaulDeviceBackend owner;
    private ShaderPackSceneGeneration generation;
    private BackendPins framePins;
    private PackGraphRuntime.Frame frame;
    private Object configuration;
    private boolean active,shadowCapture;
    ShaderPackSceneState(OverhaulDeviceBackend owner){this.owner=owner;}
    public void install(CompiledShaderPack pack,int width,int height,ShadercCompiler compiler){
        install(pack,width,height,compiler,null);
    }
    public void install(CompiledShaderPack pack,int width,int height,ShadercCompiler compiler,Object configuration){
        install(pack,width,height,compiler,configuration,null,null);
    }
    public void install(CompiledShaderPack pack,int width,int height,ShadercCompiler compiler,Object configuration,CascadeShadowSettings cascades,OwnedTerrainShaders terrain){
        owner.requireOpen();if(framePins!=null)throw new IllegalStateException("Cannot replace a pack during captured world rendering");
        ShaderPackSceneGeneration replacement=new ShaderPackSceneGeneration(owner,pack,width,height,compiler,cascades,terrain);
        try{clear();generation=replacement;this.configuration=configuration;}catch(RuntimeException|Error failure){replacement.close();throw failure;}
    }
    public void begin(GpuTextureView color,GpuTextureView depth,PackRenderTargets.Color fog,PackGraphRuntime.Uniforms uniforms){
        begin(color,depth,fog,uniforms,null);
    }
    public void begin(GpuTextureView color,GpuTextureView depth,PackRenderTargets.Color fog,PackGraphRuntime.Uniforms uniforms,CascadeShadowFrame cascades){
        owner.requireOpen();if(generation==null||framePins!=null)throw new IllegalStateException("Shader-pack frame is missing its generation or already began");
        var lane=owner.commands();framePins=new BackendPins(owner,color,depth);
        try{
            frame=new PackGraphRuntime.Frame(owner.texture(color),depth==null?null:owner.texture(depth),fog,Map.of());
            if(generation.scene()&&(generation.color().image().width(0)!=frame.color().image().width(frame.color().baseMip())
                    ||generation.color().image().height(0)!=frame.color().image().height(frame.color().baseMip())))throw new IllegalArgumentException("Scene colortex0 must preserve game color extent for ordinary image transfers");
            generation.begin(lane,frame,uniforms,cascades);active=generation.scene();
        }catch(RuntimeException|Error failure){framePins.close();framePins=null;frame=null;throw failure;}
    }
    public void finish(){
        owner.requireOpen();if(framePins==null)throw new IllegalStateException("Shader-pack frame was not captured");
        try{var lane=owner.commands();active=false;generation.finish(lane,frame);}
        finally{active=false;framePins.close();framePins=null;frame=null;}
    }
    public boolean isWorldFrameActive(){owner.requireOwner();return framePins!=null;}
    public PackShadowSettings shadowSettings(){owner.requireOwner();return generation!=null&&generation.hasShadows()?generation.shadowSettings():null;}
    /** Owned cascades shade the game's own passes; only an external pack replaces them with its deferred boundary. */
    public boolean ownsWorldPasses(){owner.requireOwner();return framePins!=null&&generation.ownsWorldPasses();}
    public boolean matchesConfiguration(Object key){owner.requireOwner();return generation!=null&&key!=null&&key.equals(configuration);}
    /** Opaque and translucent callbacks must close their frontend passes before returning. */
    public void captureShadows(Runnable opaque,Runnable translucent){
        owner.requireOpen();if(framePins==null||generation==null||!generation.hasShadows())return;
        if(shadowCapture)throw new IllegalStateException("Nested shadow caster capture");
        var cascades=generation.cascades();
        if(cascades!=null)cascades.clear();else generation.prepareShadows(owner.commands());
        shadowCapture=true;
        try{
            if(cascades!=null)cascades.capture(opaque,translucent);
            else{
                opaque.run();generation.snapshotOpaqueShadow(owner.commands());
                translucent.run();generation.finishShadows(owner.commands(),frame);
            }
        }finally{shadowCapture=false;}
    }
    boolean isShadowCapture(){owner.requireOwner();return shadowCapture;}
    /** The cascades published for the frame in progress; null outside it and on the external pack route. */
    CascadeShadowGeneration cascades(){owner.requireOwner();return framePins!=null?generation.cascades():null;}
    /** The generation whose cascades this frame's main passes may sample; null until they have been written. */
    ShaderPackSceneGeneration receivingGeneration(){var ready=cascades();return ready!=null&&ready.samplingReady()?generation:null;}
    public void afterOpaque(){
        owner.requireOpen();if(framePins==null)throw new IllegalStateException("Opaque boundary outside a captured shader-pack world frame");
        generation.afterOpaque(owner.commands(),frame);
    }
    GpuImageView redirect(GpuImageView original){
        if(!active||original.image()!=frame.color().image())return original;
        if(original.baseMip()!=frame.color().baseMip()||original.baseLayer()!=frame.color().baseLayer())throw new IllegalArgumentException("Unexpected world color subresource alias");
        return generation.color();
    }
    GpuImage redirect(GpuImage original){return active&&original==frame.color().image()?generation.color().image():original;}
    boolean suppressesColorClear(GpuTexture texture){return active&&texture instanceof OverhaulTexture image&&image.image()==frame.color().image();}
    ShaderPackSceneGeneration capture(){owner.requireOpen();return active||shadowCapture?generation:null;}
    public void clear(){
        owner.requireOpen();Throwable failure=null;
        try{if(active)owner.commands();}catch(RuntimeException|Error error){failure=error;}
        active=shadowCapture=false;BackendPins pins=framePins;ShaderPackSceneGeneration previous=generation;framePins=null;generation=null;frame=null;configuration=null;
        try{if(pins!=null)pins.close();}catch(RuntimeException|Error error){failure=combine(failure,error);}
        try{if(previous!=null)previous.close();}catch(RuntimeException|Error error){failure=combine(failure,error);}
        if(failure instanceof RuntimeException error)throw error;if(failure instanceof Error error)throw error;
    }
    private static Throwable combine(Throwable first,Throwable next){if(first==null)return next;first.addSuppressed(next);return first;}
    @Override public void close(){clear();}
}
