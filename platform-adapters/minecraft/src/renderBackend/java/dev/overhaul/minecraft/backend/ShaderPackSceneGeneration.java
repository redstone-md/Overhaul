package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.util.TextureViewAndSampler;
import dev.overhaul.shader.compat.UniformBlock;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackRenderTargets;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.ImmediateCommands;
import dev.overhaul.vulkan.PackGraphRuntime;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;

/** Captured draws and cached attachments pin a complete program generation through actual fence retirement. */
final class ShaderPackSceneGeneration extends BackendResource {
    record Sample(GpuImageView view,long sampler){}
    private final CompiledShaderPack pack;
    private final SceneProgramSelection selection;
    private final Map<OverhaulPipeline,ScenePackPipeline> pipelines=new IdentityHashMap<>();
    private PackGraphRuntime runtime;
    private PackGraphRuntime.Uniforms uniforms;
    private SceneFallbackTexture identityTexture;
    ShaderPackSceneGeneration(OverhaulDeviceBackend owner,CompiledShaderPack pack,int width,int height,ShadercCompiler compiler){
        super(owner);this.pack=pack;selection=new SceneProgramSelection(pack);
        try{
            runtime=new PackGraphRuntime(owner.context(),owner.allocator(),pack,width,height,compiler,SceneProgramSelection.SUPPORTED);
            if(scene())identityTexture=new SceneFallbackTexture(owner);
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    CompiledShaderPack pack(){return pack;}
    boolean scene(){return selection.hasScene();}
    GpuImageView color(){requireNativeAlive();return runtime.color(0);}
    void begin(ImmediateCommands lane,PackGraphRuntime.Frame frame,PackGraphRuntime.Uniforms uniforms){
        requireOpen();this.uniforms=uniforms;if(identityTexture!=null)identityTexture.initialize(lane);
        runtime.beginFrame(lane,frame,uniforms);for(var pipeline:pipelines.values())pipeline.update();
    }
    void afterOpaque(ImmediateCommands lane,PackGraphRuntime.Frame frame){requireOpen();runtime.afterOpaque(lane,frame,uniforms);}
    void finish(ImmediateCommands lane,PackGraphRuntime.Frame frame){requireOpen();runtime.render(lane,frame,uniforms);}
    void fill(CompiledShaderPack.Program program,UniformBlock block){uniforms.fill(program,block);}
    CompiledShaderPack.Program select(OverhaulPipeline original){return selection.select(original);}
    ScenePackPipeline pipeline(OverhaulPipeline original,CompiledShaderPack.Program program){
        requireNativeAlive();ScenePackPipeline result=pipelines.get(original);
        if(result!=null){if(result.program!=program)throw new IllegalStateException("Scene source changed program inside one generation");return result;}
        if(pipelines.size()>=512)throw new UnsupportedShaderException("Scene pipeline generation exceeds the 512-layout bound");
        result=new ScenePackPipeline(this,original,program);pipelines.put(original,result);return result;
    }
    PassAttachments attachments(PassAttachments ordinary,CompiledShaderPack.Program program){
        if(ordinary.x!=0||ordinary.y!=0||ordinary.areaWidth!=ordinary.width||ordinary.areaHeight!=ordinary.height)throw new UnsupportedShaderException("Scene attachment routing requires a full-frame render area");
        List<BackendResource> sources=new ArrayList<>(ordinary.resourceSources());if(!sources.contains(this))sources.add(this);
        return new PassAttachments(owner(),runtime.sceneTargets(program),ordinary.depthView(),sources,ordinary.width,ordinary.height);
    }
    Sample sample(ScenePackPipeline pipeline,String name,PassBindings state){
        int color=PackRenderTargets.colorIndex(name);if(color>=0)return new Sample(runtime.color(color),runtime.samplerHandle());
        int depth=PackRenderTargets.depthIndex(name);if(depth>=0)return new Sample(runtime.sampledDepth(depth),runtime.depthSamplerHandle());
        var metadata=state.pipeline().compileMetadata();
        if(name.equals("lightmap")&&metadata.family()==PipelineCompileMetadata.Family.ENTITY&&metadata.defined("EMISSIVE"))return identityTexture.sample();
        String semantic=switch(name){
            case "texture","tex","gtexture"->"Sampler0";case "lightmap"->"Sampler2";
            case EntityMaterialInputs.OVERLAY_SAMPLER->"Sampler1";
            case EntityMaterialInputs.DISSOLVE_SAMPLER->"DissolveMaskSampler";
            default->throw new UnsupportedShaderException("Unbound scene pack sampler: "+name+" in "+pipeline.program.name());
        };
        int slot=SceneVertexInputs.slot(state.pipeline(),semantic);Object value=state.uniforms()[slot];
        if(!(value instanceof TextureViewAndSampler pair))throw new IllegalStateException("Scene texture binding is missing: "+semantic);
        return new Sample(owner().texture(pair.view()),owner().sampler(pair.sampler()).handle());
    }
    void sampleTransitions(VkCommandBuffer command,ScenePackPipeline pipeline,PassBindings state,PassAttachments targets,List<GpuImage.LayoutChange> changes){
        if(pipeline.needsDepthRefresh()){
            if(targets.depthView()==null)throw new UnsupportedShaderException("Current-depth sampling requires the real world depth attachment");
            runtime.recordDepthSnapshot(command,targets.depthView(),changes);
        }
        for(String name:pipeline.code.bindings.samplers().keySet()){
            GpuImageView view=sample(pipeline,name,state).view();if(targets.overlaps(view))throw new UnsupportedShaderException("Scene shader reads its active attachment: "+name);
            changes.add(view.image().recordTransition(command,view.baseMip(),view.mipCount(),view.baseLayer(),view.layerCount(),VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
        }
    }
    @Override protected void destroyNative(){
        for(var pipeline:pipelines.values())pipeline.close();pipelines.clear();if(runtime!=null){runtime.close();runtime=null;}
        if(identityTexture!=null){identityTexture.close();identityTexture=null;}
    }
}
