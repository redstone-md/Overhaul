package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import dev.overhaul.shader.compat.UniformBlock;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackRenderTargets;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.ImmediateCommands;
import dev.overhaul.vulkan.PackGraphRuntime;
import org.lwjgl.vulkan.VkCommandBuffer;
import dev.overhaul.vulkan.GpuImage;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;

/** A captured scene generation owns its pipelines/targets until the last recorded draw fence releases it. */
final class ShaderPackSceneGeneration extends BackendResource {
    record Sample(GpuImageView view,long sampler){}
    private final CompiledShaderPack pack;
    private final CompiledShaderPack.Program program;
    private final Map<OverhaulPipeline,TerrainPackPipeline> terrain=new IdentityHashMap<>();
    private PackGraphRuntime runtime;
    private PackGraphRuntime.Uniforms uniforms;
    ShaderPackSceneGeneration(OverhaulDeviceBackend owner,CompiledShaderPack pack,int width,int height,ShadercCompiler compiler){
        super(owner);this.pack=pack;program=pack.programs().stream().filter(CompiledShaderPack.Program::scene).findFirst().orElse(null);
        try{
            if(program!=null)for(var target:pack.targets().targets().values())if(target.width(width)!=width||target.height(height)!=height)
                throw new UnsupportedShaderException("Scene attachments must match the actual game frame extent");
            runtime=new PackGraphRuntime(owner.context(),owner.allocator(),pack,width,height,compiler,true);
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    CompiledShaderPack pack(){return pack;}
    CompiledShaderPack.Program program(){return program;}
    boolean scene(){return program!=null;}
    GpuImageView color(){requireNativeAlive();return runtime.color(0);}
    void begin(ImmediateCommands lane,PackRenderTargets.Color fog,PackGraphRuntime.Uniforms uniforms){
        requireOpen();this.uniforms=uniforms;if(scene())runtime.beginScene(lane,fog);for(var pipeline:terrain.values())pipeline.update();
    }
    void finish(ImmediateCommands lane,PackGraphRuntime.Frame frame){requireOpen();runtime.render(lane,frame,uniforms);}
    void fill(UniformBlock block){uniforms.fill(program,block);}
    boolean terrain(OverhaulPipeline pipeline){
        if(program==null)return false;var info=pipeline.info();
        if(info.depthStencilState()==null||!info.depthStencilState().writeDepth()||info.colorTargetStates().size()!=1)return false;
        if(info.colorTargetStates().get(0)==null||info.colorTargetStates().get(0).blendFunction().isPresent())return false;
        if(info.vertexBuffers().stream().noneMatch(value->value.bufferSlot()==0&&value.stride()==28&&value.stepRate()==0))return false;
        if(info.attribBindings().size()!=4&&info.attribBindings().size()!=6)return false;
        var formats=List.of(GpuFormat.RGB32_FLOAT,GpuFormat.RGBA8_UNORM,GpuFormat.RG32_FLOAT,GpuFormat.RG16_SINT);int[] offsets={0,12,16,24};
        for(int index=0;index<4;index++){
            final int location=index;if(info.attribBindings().stream().noneMatch(value->value.location()==location&&value.bufferSlot()==0&&value.format()==formats.get(location)&&value.offset()==offsets[location]))return false;
        }
        return pipeline.uniforms().stream().anyMatch(value->value.name().equals("TerrainUniform"))&&pipeline.uniforms().stream().anyMatch(value->value.name().equals("Globals"));
    }
    TerrainPackPipeline pipeline(OverhaulPipeline original){
        requireNativeAlive();TerrainPackPipeline result=terrain.get(original);if(result!=null)return result;
        if(terrain.size()>=256)throw new UnsupportedShaderException("Terrain pipeline generation exceeds the 256-layout bound");
        result=new TerrainPackPipeline(this,original);terrain.put(original,result);return result;
    }
    PassAttachments attachments(PassAttachments ordinary){
        if(ordinary.depthView()==null)throw new UnsupportedShaderException("Terrain pack pass requires the real game depth attachment");
        if(ordinary.x!=0||ordinary.y!=0||ordinary.areaWidth!=ordinary.width||ordinary.areaHeight!=ordinary.height)throw new UnsupportedShaderException("Terrain pack attachment routing requires a full-frame render area");
        List<BackendResource> sources=new ArrayList<>(ordinary.resourceSources());if(!sources.contains(this))sources.add(this);
        return new PassAttachments(owner(),runtime.sceneTargets(program),ordinary.depthView(),sources,ordinary.width,ordinary.height);
    }
    Sample sample(String name,PassBindings state){
        int color=PackRenderTargets.colorIndex(name);if(color>=0)return new Sample(runtime.color(color),runtime.samplerHandle());
        String semantic=switch(name){case "texture","tex","gtexture"->"Sampler0";case "lightmap"->"Sampler2";
            default->throw new UnsupportedShaderException("Unbound terrain pack sampler: "+name);};
        int slot=TerrainPackProgram.slot(state.pipeline(),semantic);Object value=state.uniforms()[slot];
        if(!(value instanceof TextureViewAndSampler pair))throw new IllegalStateException("Terrain texture binding is missing: "+semantic);
        return new Sample(owner().texture(pair.view()),owner().sampler(pair.sampler()).handle());
    }
    void sampleTransitions(VkCommandBuffer command,PassBindings state,PassAttachments targets,List<GpuImage.LayoutChange> changes){
        TerrainPackPipeline pipeline=pipeline(state.pipeline());
        for(String name:pipeline.code.bindings.samplers().keySet()){
            GpuImageView view=sample(name,state).view();if(targets.overlaps(view))throw new UnsupportedShaderException("Terrain shader reads its active attachment: "+name);
            changes.add(view.image().recordTransition(command,view.baseMip(),view.mipCount(),view.baseLayer(),view.layerCount(),VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
        }
    }
    @Override protected void destroyNative(){for(var pipeline:terrain.values())pipeline.close();terrain.clear();if(runtime!=null){runtime.close();runtime=null;}}
}
