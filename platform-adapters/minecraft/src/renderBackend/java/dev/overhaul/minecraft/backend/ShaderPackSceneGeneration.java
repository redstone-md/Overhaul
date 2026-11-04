package dev.overhaul.minecraft.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import dev.overhaul.minecraft.shader.CascadeShadowFrame;
import dev.overhaul.shader.compat.UniformBlock;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.pack.CascadeShadowSettings;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackRenderTargets;
import dev.overhaul.shader.pack.PackShadowSettings;
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
    private final ShadowProgramSelection shadows;
    private final Map<OverhaulPipeline,ScenePackPipeline> pipelines=new IdentityHashMap<>();
    private final Map<OverhaulPipeline,ScenePackPipeline> shadowPipelines=new IdentityHashMap<>();
    private PackGraphRuntime runtime;
    private PackGraphRuntime.Uniforms uniforms;
    private SceneFallbackTexture identityTexture,normalTexture,specularTexture;
    private final PackShadowSettings shadowSettings;
    private CascadeShadowGeneration cascades;
    /** A null policy keeps the external pack route; owned cascades have no pack graph or colortex targets. */
    ShaderPackSceneGeneration(OverhaulDeviceBackend owner,CompiledShaderPack pack,int width,int height,ShadercCompiler compiler,CascadeShadowSettings cascadePolicy){
        super(owner);this.pack=pack;selection=new SceneProgramSelection(pack);shadows=new ShadowProgramSelection(pack);
        shadowSettings=cascadePolicy==null?pack.shadow():pack.shadow().withCamera(cascadePolicy.resolution(),cascadePolicy.distance());
        try{
            if(cascadePolicy==null){
                var bound=new java.util.HashSet<>(SceneProgramSelection.SUPPORTED);bound.addAll(SceneProgramSelection.VANILLA_DRAWN);bound.addAll(ShadowProgramSelection.SUPPORTED);
                runtime=new PackGraphRuntime(owner.context(),owner.allocator(),pack,width,height,compiler,bound);
            }else cascades=new CascadeShadowGeneration(owner,cascadePolicy);
            if(scene()||hasShadows()){identityTexture=SceneFallbackTexture.identity(owner);normalTexture=SceneFallbackTexture.flatNormal(owner);specularTexture=SceneFallbackTexture.noSpecular(owner);}
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    CompiledShaderPack pack(){return pack;}
    boolean scene(){return selection.hasScene();}
    boolean hasShadows(){return shadows.present();}
    PackShadowSettings shadowSettings(){return shadowSettings;}
    boolean ownsWorldPasses(){return runtime!=null;}
    CascadeShadowGeneration cascades(){return cascades;}
    boolean ownsColor(GpuImageView view){return runtime!=null&&view.image()==color().image();}
    GpuImageView color(){requireNativeAlive();return runtime.color(0);}
    void begin(ImmediateCommands lane,PackGraphRuntime.Frame frame,PackGraphRuntime.Uniforms uniforms,CascadeShadowFrame cascadeFrame){
        requireOpen();this.uniforms=uniforms;if(identityTexture!=null){identityTexture.initialize(lane);normalTexture.initialize(lane);specularTexture.initialize(lane);}
        if(runtime!=null)runtime.beginFrame(lane,frame,uniforms);else cascades.beginFrame(cascadeFrame);for(var pipeline:pipelines.values())pipeline.update();for(var pipeline:shadowPipelines.values())pipeline.update();
    }
    void afterOpaque(ImmediateCommands lane,PackGraphRuntime.Frame frame){requireOpen();runtime.afterOpaque(lane,frame,uniforms);}
    void prepareShadows(ImmediateCommands lane){requireOpen();runtime.prepareShadows(lane);}
    void snapshotOpaqueShadow(ImmediateCommands lane){requireOpen();runtime.snapshotOpaqueShadow(lane);}
    void finishShadows(ImmediateCommands lane,PackGraphRuntime.Frame frame){requireOpen();runtime.finishShadows(lane,frame,uniforms);}
    void finish(ImmediateCommands lane,PackGraphRuntime.Frame frame){requireOpen();if(runtime!=null)runtime.render(lane,frame,uniforms);}
    void fill(CompiledShaderPack.Program program,UniformBlock block){uniforms.fill(program,block);}
    CompiledShaderPack.Program select(OverhaulPipeline original){return selection.select(original);}
    ScenePackPipeline pipeline(OverhaulPipeline original,CompiledShaderPack.Program program){
        return pipeline(pipelines,original,program);
    }
    CompiledShaderPack.Program shadowProgram(OverhaulPipeline original){return shadows.select(original);}
    ScenePackPipeline shadowPipeline(OverhaulPipeline original){
        var program=shadowProgram(original);if(program==null)throw new UnsupportedShaderException("No bound shadow program for the captured source");
        return pipeline(shadowPipelines,original,program);
    }
    private ScenePackPipeline pipeline(Map<OverhaulPipeline,ScenePackPipeline> cache,OverhaulPipeline original,CompiledShaderPack.Program program){
        requireNativeAlive();ScenePackPipeline result=cache.get(original);
        if(result!=null){if(result.program!=program)throw new IllegalStateException("Scene source changed program inside one generation");return result;}
        if(pipelines.size()+shadowPipelines.size()>=1024)throw new UnsupportedShaderException("Scene/shadow pipeline generation exceeds the 1024-layout bound");
        result=new ScenePackPipeline(this,original,program);cache.put(original,result);return result;
    }
    PassAttachments attachments(PassAttachments ordinary,CompiledShaderPack.Program program){
        if(ordinary.x!=0||ordinary.y!=0||ordinary.areaWidth!=ordinary.width||ordinary.areaHeight!=ordinary.height)throw new UnsupportedShaderException("Scene attachment routing requires a full-frame render area");
        List<BackendResource> sources=new ArrayList<>(ordinary.resourceSources());if(!sources.contains(this))sources.add(this);
        return new PassAttachments(owner(),runtime.sceneTargets(program),ordinary.depthView(),sources,ordinary.width,ordinary.height);
    }
    PassAttachments shadowAttachments(CompiledShaderPack.Program program,List<BackendResource> capturedSources){
        if(program.stage()!=CompiledShaderPack.Stage.SHADOW)throw new IllegalArgumentException("A shadow attachment set requires a shadow program");
        int resolution=pack.shadow().resolution();return new PassAttachments(owner(),runtime.shadowTargets(program),runtime.shadowDepth(0),pinned(capturedSources),resolution,resolution);
    }
    PassAttachments cascadeAttachments(int cascade,List<BackendResource> capturedSources){
        int resolution=cascades.resolution();return new PassAttachments(owner(),List.of(),cascades.depth(cascade),pinned(capturedSources),resolution,resolution);
    }
    private List<BackendResource> pinned(List<BackendResource> capturedSources){
        List<BackendResource> sources=new ArrayList<>(capturedSources);if(!sources.contains(this))sources.add(this);return sources;
    }
    Sample sample(ScenePackPipeline pipeline,String name,PassBindings state){
        Sample routed=runtime==null?null:runtimeSample(pipeline,name);if(routed!=null)return routed;
        if(name.equals("normals"))return normalTexture.sample();
        if(name.equals("specular"))return specularTexture.sample();
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
        // The game binds the block atlas with LINEAR and sharpens it in terrain.fsh; packs read it with plain texture().
        var sampler=semantic.equals("Sampler0")&&metadata.family()==PipelineCompileMetadata.Family.TERRAIN?RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST,true):pair.sampler();
        return new Sample(owner().texture(pair.view()),owner().sampler(sampler).handle());
    }
    private Sample runtimeSample(ScenePackPipeline pipeline,String name){
        GpuImageView custom=runtime.customTexture(pipeline.program,name);
        if(custom!=null)return new Sample(custom,runtime.customSamplerHandle(pipeline.program,name));
        int shadowDepth=PackShadowSettings.depthIndex(name,pipeline.code.bindings.samplers().keySet());
        if(shadowDepth>=0){boolean comparison=pipeline.code.bindings.comparisonSampler(name);return new Sample(runtime.shadowDepthSample(shadowDepth,comparison),runtime.shadowSamplerHandle(shadowDepth,comparison));}
        int shadowColor=PackShadowSettings.colorIndex(name);if(shadowColor>=0)return new Sample(runtime.shadowColor(shadowColor),runtime.shadowColorSamplerHandle(shadowColor));
        if(pipeline.code.bindings.comparisonSampler(name))throw new UnsupportedShaderException("Depth comparison requires a bound shadow depth texture: "+name);
        int color=PackRenderTargets.colorIndex(name);if(color>=0)return new Sample(runtime.color(color),runtime.samplerHandle());
        int depth=PackRenderTargets.depthIndex(name);if(depth>=0)return new Sample(runtime.sampledDepth(depth),runtime.depthSamplerHandle());
        return null;
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
        for(var pipeline:pipelines.values())pipeline.close();pipelines.clear();for(var pipeline:shadowPipelines.values())pipeline.close();shadowPipelines.clear();if(runtime!=null){runtime.close();runtime=null;}
        if(cascades!=null){cascades.close();cascades=null;}
        if(identityTexture!=null){identityTexture.close();identityTexture=null;}
        if(normalTexture!=null){normalTexture.close();normalTexture=null;}
        if(specularTexture!=null){specularTexture.close();specularTexture=null;}
    }
}
