package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackRenderTargets;
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
    private boolean active;
    ShaderPackSceneState(OverhaulDeviceBackend owner){this.owner=owner;}
    public void install(CompiledShaderPack pack,int width,int height,ShadercCompiler compiler){
        owner.requireOpen();if(framePins!=null)throw new IllegalStateException("Cannot replace a pack during captured world rendering");
        ShaderPackSceneGeneration replacement=new ShaderPackSceneGeneration(owner,pack,width,height,compiler);
        try{clear();generation=replacement;}catch(RuntimeException|Error failure){replacement.close();throw failure;}
    }
    public void begin(GpuTextureView color,GpuTextureView depth,PackRenderTargets.Color fog,PackGraphRuntime.Uniforms uniforms){
        owner.requireOpen();if(generation==null||framePins!=null)throw new IllegalStateException("Shader-pack frame is missing its generation or already began");
        var lane=owner.commands();framePins=new BackendPins(owner,color,depth);
        try{
            frame=new PackGraphRuntime.Frame(owner.texture(color),depth==null?null:owner.texture(depth),fog,Map.of());
            if(generation.scene()&&(generation.color().image().format()!=frame.color().image().format()
                    ||generation.color().image().width(0)!=frame.color().image().width(frame.color().baseMip())
                    ||generation.color().image().height(0)!=frame.color().image().height(frame.color().baseMip())))throw new IllegalArgumentException("Scene colortex0 must preserve game color format/extent for ordinary image transfers");
            generation.begin(lane,frame,uniforms);active=generation.scene();
        }catch(RuntimeException|Error failure){framePins.close();framePins=null;frame=null;throw failure;}
    }
    public void finish(){
        owner.requireOpen();if(framePins==null)throw new IllegalStateException("Shader-pack frame was not captured");
        try{var lane=owner.commands();active=false;generation.finish(lane,frame);}
        finally{active=false;framePins.close();framePins=null;frame=null;}
    }
    public boolean isWorldFrameActive(){owner.requireOwner();return framePins!=null;}
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
    ShaderPackSceneGeneration capture(){owner.requireOpen();return active?generation:null;}
    public void clear(){
        owner.requireOpen();Throwable failure=null;
        try{if(active)owner.commands();}catch(RuntimeException|Error error){failure=error;}
        active=false;BackendPins pins=framePins;ShaderPackSceneGeneration previous=generation;framePins=null;generation=null;frame=null;
        try{if(pins!=null)pins.close();}catch(RuntimeException|Error error){failure=combine(failure,error);}
        try{if(previous!=null)previous.close();}catch(RuntimeException|Error error){failure=combine(failure,error);}
        if(failure instanceof RuntimeException error)throw error;if(failure instanceof Error error)throw error;
    }
    private static Throwable combine(Throwable first,Throwable next){if(first==null)return next;first.addSuppressed(next);return first;}
    @Override public void close(){clear();}
}
