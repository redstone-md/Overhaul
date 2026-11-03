package dev.overhaul.minecraft.shader;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.minecraft.backend.OverhaulGpuDevice;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackRenderTargets;
import dev.overhaul.shader.pack.ShaderPackSource;
import dev.overhaul.vulkan.PackGraphRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.level.Level;
import org.joml.Matrix4fc;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Game-renderer owned pack generation. Runs after world passes and before hand/HUD depth is cleared. */
public final class ShaderPackFrameRenderer implements AutoCloseable {
    private final Thread owner=Thread.currentThread();
    private final Minecraft minecraft;
    private final float[] projection=new float[16];
    private final ShaderPackFrameUniforms uniforms=new ShaderPackFrameUniforms();
    private PackGraphRuntime runtime;
    private Object dimension;
    private Object world;
    private String configuration="";
    private int width,height;
    private boolean projectionReady,closed;
    public ShaderPackFrameRenderer(Minecraft minecraft){this.minecraft=minecraft;}
    public void projection(Matrix4fc matrix){requireOpen();matrix.get(projection);projectionReady=true;}
    public void render(RenderTarget target,LevelRenderState state){
        requireOpen();String requested=System.getProperty("overhaul.shaderPack","").trim();
        if(requested.isEmpty()){invalidate();return;}
        if(!(RenderSystem.getDevice() instanceof OverhaulGpuDevice device))throw new IllegalStateException("A shader pack requires the owned Vulkan backend");
        if(minecraft.level==null||!projectionReady)throw new IllegalStateException("Shader-pack frame lacks the captured world projection");
        var resources=device.nativeFrames();var key=minecraft.level.dimension();
        String options=System.getProperty("overhaul.shaderPackOptions","");String macros=System.getProperty("overhaul.shaderPackDefines","");
        String nextConfiguration=requested+"\n"+options+"\n"+macros;
        if(runtime==null||world!=minecraft.level||width!=target.width||height!=target.height||!key.equals(dimension)||!configuration.equals(nextConfiguration)){
            Path path=minecraft.gameDirectory.toPath().resolve(requested).normalize();
            try(ShaderPackSource source=ShaderPackSource.open(path);ShadercCompiler compiler=new ShadercCompiler()){
                Integer packDimension=key.equals(Level.OVERWORLD)?0:key.equals(Level.NETHER)?-1:key.equals(Level.END)?1:null;
                CompiledShaderPack pack=CompiledShaderPack.compile(source,packDimension,options(options),definitions(macros),compiler);
                PackGraphRuntime replacement=new PackGraphRuntime(resources.context(),resources.allocator(),pack,target.width,target.height,compiler);
                if(runtime!=null)runtime.close();runtime=replacement;width=target.width;height=target.height;dimension=key;world=minecraft.level;configuration=nextConfiguration;uniforms.resetHistory();
            }catch(IOException failure){throw new IllegalStateException("Cannot load shader pack: "+path,failure);}
        }
        var color=target.getColorTextureView();var depth=target.getDepthTextureView();
        AutoCloseable colorPin=resources.retain(color),depthPin=null;
        try{
            if(depth!=null)depthPin=resources.retain(depth);
            var lane=resources.commands(); // Flushes all preceding frontend scene writes onto this same native queue.
            uniforms.capture(minecraft,state,target.width,target.height,projection);
            var fog=state.cameraRenderState.fogData.color;
            runtime.render(lane,new PackGraphRuntime.Frame(resources.texture(color),depth==null?null:resources.texture(depth),
                    new PackRenderTargets.Color(fog.x(),fog.y(),fog.z(),1),Map.of()),uniforms);
            uniforms.complete();projectionReady=false;
        }finally{try{if(depthPin!=null)depthPin.close();}catch(Exception failure){throw new IllegalStateException("Cannot release shader-pack depth view",failure);}
            finally{try{colorPin.close();}catch(Exception failure){throw new IllegalStateException("Cannot release shader-pack color view",failure);}}}
    }
    private static Map<String,Boolean> options(String text){
        Map<String,Boolean> result=new LinkedHashMap<>();for(var entry:definitions(text).entrySet()){
            if(!entry.getValue().equals("true")&&!entry.getValue().equals("false"))throw new IllegalArgumentException("Shader-pack options require true/false values");
            result.put(entry.getKey(),Boolean.valueOf(entry.getValue()));
        }return result;
    }
    private static Map<String,String> definitions(String text){
        Map<String,String> result=new LinkedHashMap<>();if(text.isBlank())return result;
        for(String token:text.split(",",-1)){
            int separator=token.indexOf('=');if(separator<1)throw new IllegalArgumentException("Shader-pack configuration requires NAME=VALUE entries");
            String name=token.substring(0,separator).trim(),value=token.substring(separator+1).trim();
            if(!name.matches("[A-Za-z_]\\w*")||value.isEmpty()||result.putIfAbsent(name,value)!=null)throw new IllegalArgumentException("Invalid or duplicate shader-pack option: "+name);
        }return result;
    }
    public void invalidate(){requireOpen();if(runtime!=null){runtime.close();runtime=null;}dimension=null;world=null;configuration="";uniforms.resetHistory();}
    private void requireOpen(){if(Thread.currentThread()!=owner||closed)throw new IllegalStateException("Shader-pack renderer is closed or accessed outside its owner");}
    @Override public void close(){if(closed)return;requireOpen();invalidate();closed=true;}
}
