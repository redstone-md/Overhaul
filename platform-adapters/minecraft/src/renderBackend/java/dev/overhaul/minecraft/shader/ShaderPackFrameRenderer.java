package dev.overhaul.minecraft.shader;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.minecraft.backend.OverhaulGpuDevice;
import dev.overhaul.minecraft.backend.ShaderPackSceneState;
import dev.overhaul.minecraft.shadow.ShadowCasterAccess;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackBuiltinMacros;
import dev.overhaul.shader.pack.PackRenderTargets;
import dev.overhaul.shader.pack.ShaderPackSource;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owns installation and frame boundaries; the device pins captured scene generations through native retirement. */
public final class ShaderPackFrameRenderer implements AutoCloseable {
    private static final String TERRAIN_MANIFEST="/overhaul-terrain-shaders.properties";
    private final Thread owner=Thread.currentThread();
    private final Minecraft minecraft;
    private final float[] projection=new float[16];
    private final ShaderPackFrameUniforms uniforms=new ShaderPackFrameUniforms();
    private ShaderPackSceneState scenes;
    private CompiledShaderPack activePack;
    private CascadeShadowCameras cameras;
    private ShaderPackConfiguration configuration,blocked;
    private int width,height;
    private boolean installed,prepared,captured,closed;
    public ShaderPackFrameRenderer(Minecraft minecraft){this.minecraft=minecraft;}
    public void projection(Matrix4fc matrix){
        requireOpen();matrix.get(projection);prepared=true;
    }
    public void beginWorld(){
        requireOpen();
        RenderTarget target=minecraft.gameRenderer.mainRenderTarget();LevelRenderState state=minecraft.gameRenderer.gameRenderState().levelRenderState;
        var nextConfiguration=ShaderPackConfiguration.capture(minecraft);if(!nextConfiguration.configured()){invalidate();return;}
        if(!(RenderSystem.getDevice() instanceof OverhaulGpuDevice device))throw new IllegalStateException("A shader pack requires the owned Vulkan backend");
        if(minecraft.level==null||captured||!prepared)throw new IllegalStateException("Shader-pack frame lacks its world/projection or is already captured");
        var resources=device.nativeFrames();scenes=resources.shaderPacks();var key=minecraft.level.dimension();
        if(nextConfiguration.equals(blocked)||!receiversCertified(nextConfiguration))return;
        blocked=null;
        if(!installed||width!=target.width||height!=target.height||!nextConfiguration.equals(configuration)){
            try(ShaderPackSource source=open(nextConfiguration);ShadercCompiler compiler=new ShadercCompiler()){
                Map<String,String> definitions=PackBuiltinMacros.with(definitions(nextConfiguration.defines()),SharedConstants.getCurrentVersion().name(),System.getProperty("os.name"));Map<String,Boolean> selected=options(nextConfiguration.options());
                String dimension=key.identifier().toString();CompiledShaderPack pack;
                try{pack=CompiledShaderPack.compileForDimension(source,dimension,selected,definitions,compiler);}
                catch(RuntimeException failure){throw ShaderPackFailureReport.describe(source,dimension,selected,definitions,compiler,failure);}
                scenes.install(pack,target.width,target.height,compiler,nextConfiguration,nextConfiguration.cascades());activePack=pack;installed=true;
                var policy=nextConfiguration.cascades();cameras=policy==null?null:new CascadeShadowCameras(policy.count(),policy.resolution(),policy.splitLambda(),policy.blendFraction());
                width=target.width;height=target.height;configuration=nextConfiguration;uniforms.resetHistory();
            }catch(IOException failure){throw new IllegalStateException("Cannot load shader pack: "+nextConfiguration.requested(),failure);}
        }
        uniforms.capture(minecraft,state,target.width,target.height,projection,activePack);var fog=state.cameraRenderState.fogData.color;
        scenes.begin(target.getColorTextureView(),target.getDepthTextureView(),new PackRenderTargets.Color(fog.x(),fog.y(),fog.z(),1),uniforms,cameras==null?null:uniforms.cascades(cameras,configuration.cascades().distance()));captured=true;prepared=false;
    }
    public void render(RenderTarget target,LevelRenderState state){
        requireOpen();if(!ShaderPackConfiguration.requestedAny()){invalidate();return;}
        if(!captured&&blocked!=null)return;
        if(!captured)throw new IllegalStateException("Shader-pack projection was not captured before world rendering");
        try{scenes.finish();uniforms.complete();}finally{captured=false;}
    }
    /** Owned cascades shade the game's own passes, so its improved transparency stays in charge. */
    public boolean ownsWorldTransparency(){return !closed&&Thread.currentThread()==owner&&captured&&configuration.cascades()==null;}
    private ShaderPackSource open(ShaderPackConfiguration requested)throws IOException{
        return requested.cascades()!=null?OwnedCsmPack.open():ShaderPackSource.open(minecraft.gameDirectory.toPath().resolve(requested.requested()).normalize());
    }
    /** Owned receivers rewrite exact vanilla terrain text: any other shader bytes keep vanilla terrain, loudly. */
    private boolean receiversCertified(ShaderPackConfiguration requested){
        if(requested.cascades()==null||requested.equals(configuration))return true;
        var gate=new ShaderResourceGate(TERRAIN_MANIFEST);if(gate.eligible(minecraft.getResourceManager()))return true;
        LoggerFactory.getLogger(ShaderPackFrameRenderer.class).warn("Owned cascaded shadows disabled: {}",gate.reason());
        invalidate();blocked=requested;return false;
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
    public void abort(){invalidate();}
    public void invalidate(){
        requireOpen();
        try{if(scenes!=null)scenes.clear();}
        finally{
            prepared=captured=false;installed=false;activePack=null;cameras=null;configuration=blocked=null;uniforms.resetHistory();
            if(minecraft.levelRenderer instanceof ShadowCasterAccess shadows)shadows.overhaul$releaseShadows();
        }
    }
    private void requireOpen(){if(Thread.currentThread()!=owner||closed)throw new IllegalStateException("Shader-pack renderer is closed or accessed outside its owner");}
    @Override public void close(){if(closed)return;requireOpen();invalidate();closed=true;}
}
