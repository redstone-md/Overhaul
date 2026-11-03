package dev.overhaul.minecraft.shader;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.minecraft.backend.OverhaulGpuDevice;
import dev.overhaul.minecraft.backend.ShaderPackSceneState;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackRenderTargets;
import dev.overhaul.shader.pack.ShaderPackSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owns installation and frame boundaries; the device pins captured scene generations through native retirement. */
public final class ShaderPackFrameRenderer implements AutoCloseable {
    private final Thread owner=Thread.currentThread();
    private final Minecraft minecraft;
    private final float[] projection=new float[16];
    private final ShaderPackFrameUniforms uniforms=new ShaderPackFrameUniforms();
    private ShaderPackSceneState scenes;
    private Object dimension,world;
    private String configuration="";
    private int width,height;
    private boolean installed,prepared,captured,closed;
    public ShaderPackFrameRenderer(Minecraft minecraft){this.minecraft=minecraft;}
    public void projection(Matrix4fc matrix){
        requireOpen();matrix.get(projection);prepared=true;
    }
    public void beginWorld(){
        requireOpen();
        RenderTarget target=minecraft.gameRenderer.mainRenderTarget();LevelRenderState state=minecraft.gameRenderer.gameRenderState().levelRenderState;
        String requested=System.getProperty("overhaul.shaderPack","").trim();if(requested.isEmpty()){invalidate();return;}
        if(!(RenderSystem.getDevice() instanceof OverhaulGpuDevice device))throw new IllegalStateException("A shader pack requires the owned Vulkan backend");
        if(minecraft.level==null||captured||!prepared)throw new IllegalStateException("Shader-pack frame lacks its world/projection or is already captured");
        var resources=device.nativeFrames();scenes=resources.shaderPacks();var key=minecraft.level.dimension();
        String options=System.getProperty("overhaul.shaderPackOptions","");String macros=System.getProperty("overhaul.shaderPackDefines","");
        String nextConfiguration=requested+"\n"+options+"\n"+macros;
        if(!installed||world!=minecraft.level||width!=target.width||height!=target.height||!key.equals(dimension)||!configuration.equals(nextConfiguration)){
            Path path=minecraft.gameDirectory.toPath().resolve(requested).normalize();
            try(ShaderPackSource source=ShaderPackSource.open(path);ShadercCompiler compiler=new ShadercCompiler()){
                Map<String,String> definitions=definitions(macros);Map<String,Boolean> selected=options(options);
                CompiledShaderPack pack=CompiledShaderPack.compileForDimension(source,key.identifier().toString(),selected,definitions,compiler);
                scenes.install(pack,target.width,target.height,compiler);installed=true;
                width=target.width;height=target.height;dimension=key;world=minecraft.level;configuration=nextConfiguration;uniforms.resetHistory();
            }catch(IOException failure){throw new IllegalStateException("Cannot load shader pack: "+path,failure);}
        }
        uniforms.capture(minecraft,state,target.width,target.height,projection);var fog=state.cameraRenderState.fogData.color;
        scenes.begin(target.getColorTextureView(),target.getDepthTextureView(),new PackRenderTargets.Color(fog.x(),fog.y(),fog.z(),1),uniforms);captured=true;prepared=false;
    }
    public void render(RenderTarget target,LevelRenderState state){
        requireOpen();if(System.getProperty("overhaul.shaderPack","").isBlank()){invalidate();return;}
        if(!captured)throw new IllegalStateException("Shader-pack projection was not captured before world rendering");
        try{scenes.finish();uniforms.complete();}finally{captured=false;}
    }
    public boolean worldActive(){return !closed&&Thread.currentThread()==owner&&captured;}
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
    public void abort(){invalidate();}
    public void invalidate(){requireOpen();try{if(scenes!=null)scenes.clear();}finally{prepared=captured=false;installed=false;dimension=null;world=null;configuration="";uniforms.resetHistory();}}
    private void requireOpen(){if(Thread.currentThread()!=owner||closed)throw new IllegalStateException("Shader-pack renderer is closed or accessed outside its owner");}
    @Override public void close(){if(closed)return;requireOpen();invalidate();closed=true;}
}
