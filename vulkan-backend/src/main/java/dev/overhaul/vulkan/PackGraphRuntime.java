package dev.overhaul.vulkan;

import dev.overhaul.shader.compat.CoordinateConvention;
import dev.overhaul.shader.compat.UniformBlock;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.graph.PackTargetSwaps;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackRenderTargets;
import dev.overhaul.shader.pack.PackShadowSettings;
import dev.overhaul.shader.pack.ShaderPackProgram;
import dev.overhaul.shader.pack.ShaderPackProperties;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.lwjgl.vulkan.VK10.*;

/** Actual scene-image deferred/composite execution. Scene-program replacement requires a separately bound geometry path. */
public final class PackGraphRuntime implements AutoCloseable {
    @FunctionalInterface public interface Uniforms {void fill(CompiledShaderPack.Program program,UniformBlock values);}
    public record Frame(GpuImageView color,GpuImageView depth,PackRenderTargets.Color fog,Map<String,GpuImageView> textures){
        public Frame{java.util.Objects.requireNonNull(color);java.util.Objects.requireNonNull(fog);textures=Map.copyOf(textures);}
    }
    private final VulkanContext context;
    private final CompiledShaderPack pack;
    private final PackTargetSwaps swaps=new PackTargetSwaps();
    private final Map<CompiledShaderPack.Program,PackFullscreenPass> passes=new LinkedHashMap<>();
    private final int width,height;
    private PackGraphImages images;
    private PackShadowImages shadows;
    private GpuSampler sampler,depthSampler,mipSampler;
    private final PackMipmaps mipmaps;
    private PackFullscreenPass depthPass;
    private ImmediateCommands frameLane;
    private boolean initialized,closed,frameBegun,opaqueComplete,shadowsPrepared,shadowsComplete,shadowOpaque;
    private final boolean scene;

    public PackGraphRuntime(VulkanContext context,GpuAllocator allocator,CompiledShaderPack pack,int width,int height,ShadercCompiler compiler){
        this(context,allocator,pack,width,height,compiler,false);
    }
    public PackGraphRuntime(VulkanContext context,GpuAllocator allocator,CompiledShaderPack pack,int width,int height,ShadercCompiler compiler,boolean bindTerrainScene){
        this(context,allocator,pack,width,height,compiler,bindTerrainScene?Set.of("gbuffers_terrain"):Set.of());
    }
    /** The adapter supplies only program names for which it binds actual geometry draws. */
    public PackGraphRuntime(VulkanContext context,GpuAllocator allocator,CompiledShaderPack pack,int width,int height,ShadercCompiler compiler,Set<String> boundScenePrograms){
        this.context=context;this.pack=pack;this.width=width;this.height=height;context.requireOwner();
        boundScenePrograms=Set.copyOf(boundScenePrograms);
        mipmaps=new PackMipmaps(context);
        scene=pack.programs().stream().anyMatch(program->program.stage()==CompiledShaderPack.Stage.GBUFFER);
        boolean hasShadows=pack.programs().stream().anyMatch(program->program.stage()==CompiledShaderPack.Stage.SHADOW);
        for(var program:pack.programs())if(program.scene()&&!boundScenePrograms.contains(program.name().substring(program.name().lastIndexOf('/')+1)))
            throw new UnsupportedShaderException("Game scene geometry is not bound to shader-pack stage: "+program.name());
        for(var program:pack.programs())if(!hasShadows&&(program.stage()==CompiledShaderPack.Stage.SHADOWCOMPOSITE||program.code().descriptors().samplers().keySet().stream().anyMatch(name->PackShadowSettings.depthIndex(name)>=0||PackShadowSettings.colorIndex(name)>=0)))
            throw new UnsupportedShaderException("Shadow sampling requires bound light-camera geometry: "+program.name());
        try{
            images=new PackGraphImages(allocator,pack.targets(),width,height);
            if(hasShadows)shadows=new PackShadowImages(context,allocator,pack.shadow());
            sampler=allocator.createSampler(new SamplerSettings(SamplerSettings.Filter.LINEAR,SamplerSettings.Filter.LINEAR,SamplerSettings.Mipmap.NEAREST,
                    SamplerSettings.Address.CLAMP,SamplerSettings.Address.CLAMP,SamplerSettings.Address.CLAMP,0,0,1,SamplerSettings.Compare.NONE));
            depthSampler=allocator.createSampler(SamplerSettings.nearest());
            if(pack.targets().targets().values().stream().anyMatch(PackRenderTargets.Target::mipmapped))
                mipSampler=allocator.createSampler(new SamplerSettings(SamplerSettings.Filter.LINEAR,SamplerSettings.Filter.LINEAR,SamplerSettings.Mipmap.LINEAR,
                        SamplerSettings.Address.CLAMP,SamplerSettings.Address.CLAMP,SamplerSettings.Address.CLAMP,0,31,1,SamplerSettings.Compare.NONE));
            for(var program:pack.programs()){
                if(program.scene())continue;
                var outputs=program.stage()==CompiledShaderPack.Stage.SHADOWCOMPOSITE?shadows.targets(program):outputs(swaps.route(program));int passWidth=0,passHeight=0;List<Integer> formats=new ArrayList<>();
                for(GpuImageView output:outputs){
                    formats.add(output==null?VK_FORMAT_UNDEFINED:output.image().format().vkFormat());if(output==null)continue;
                    int x=output.image().width(0),y=output.image().height(0);
                    if(passWidth!=0&&(passWidth!=x||passHeight!=y))throw new UnsupportedShaderException("MRT targets require equal dimensions: "+program.name());passWidth=x;passHeight=y;
                }
                if(passWidth==0)throw new UnsupportedShaderException("Fullscreen pass has no active color attachment: "+program.name());
                passes.put(program,new PackFullscreenPass(context,allocator,program,formats,passWidth,passHeight));
            }
            boolean needsDepth=pack.programs().stream().anyMatch(program->program.code().descriptors().samplers().keySet().stream().anyMatch(name->PackRenderTargets.depthIndex(name)>=0));
            if(needsDepth)depthPass=new PackFullscreenPass(context,allocator,depthProgram(compiler),List.of(VK_FORMAT_R32_SFLOAT),width,height);
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    /** Runs only before world capture; temporal buffers retain their contents unless their clear directive requests it. */
    public void beginFrame(ImmediateCommands lane,Frame frame,Uniforms uniforms){
        requireOpen();validateFrame(frame);if(frameBegun)throw new IllegalStateException("Shader-pack world frame is already open");
        int[] prior=swaps.snapshot();PackFrameGraph plan=new PackFrameGraph();
        clearTargets(plan,frame.fog());
        if(!initialized){clear(plan,images.depth,new PackRenderTargets.Color(1,1,1,1));clear(plan,images.opaqueDepth,new PackRenderTargets.Color(1,1,1,1));}
        if(!initialized&&shadows!=null)shadows.clear(plan);
        try{
            recordPasses(plan,uniforms,shadows==null?Set.of(CompiledShaderPack.Stage.BEGIN,CompiledShaderPack.Stage.PREPARE):Set.of(CompiledShaderPack.Stage.BEGIN),frame.textures());
            execute(plan,lane);if(shadows!=null)shadows.initialized();initialized=true;frameBegun=true;opaqueComplete=false;frameLane=lane;
            shadowsPrepared=false;shadowOpaque=false;shadowsComplete=shadows==null;
        }catch(RuntimeException|Error failure){swaps.restore(prior);throw failure;}
    }
    /** Executes after all opaque writes are submitted and before any classic translucent geometry is captured. */
    public void afterOpaque(ImmediateCommands lane,Frame frame,Uniforms uniforms){
        requireOpen();validateFrame(frame);if(!frameBegun)throw new IllegalStateException("Begin the shader-pack frame before the opaque boundary");
        if(!shadowsComplete)throw new IllegalStateException("Finish the actual light-camera geometry before world gbuffer execution");
        if(opaqueComplete)return;int[] prior=swaps.snapshot();PackFrameGraph plan=new PackFrameGraph();
        try{
            if(!scene)blit(plan,frame.color(),images.colors.get(swaps.current(0)),false,"import-opaque-color");
            importDepth(plan,frame,images.depth,"import-opaque-depth");importDepth(plan,frame,images.opaqueDepth,"retain-opaque-depth");
            recordPasses(plan,uniforms,Set.of(CompiledShaderPack.Stage.DEFERRED),frame.textures());
            if(!scene)blit(plan,images.colors.get(swaps.current(0)),frame.color(),false,"export-deferred-color");
            execute(plan,lane);opaqueComplete=true;
        }catch(RuntimeException|Error failure){swaps.restore(prior);throw failure;}
    }
    /** Completes the world frame after transparency. Standalone callers retain a synchronous all-phases entry point. */
    public void render(ImmediateCommands lane,Frame frame,Uniforms uniforms){
        requireOpen();validateFrame(frame);
        if(!frameBegun)beginFrame(lane,frame,uniforms);if(!opaqueComplete)afterOpaque(lane,frame,uniforms);
        int[] prior=swaps.snapshot();PackFrameGraph plan=new PackFrameGraph();
        try{
            clear(plan,images.output,new PackRenderTargets.Color(0,0,0,0));
            if(!scene)blit(plan,frame.color(),images.colors.get(swaps.current(0)),false,"import-translucent-color");
            importDepth(plan,frame,images.depth,"import-world-depth");
            boolean finalWritten=recordPasses(plan,uniforms,Set.of(CompiledShaderPack.Stage.COMPOSITE,CompiledShaderPack.Stage.FINAL),frame.textures());
            blit(plan,finalWritten?images.output:images.colors.get(swaps.current(0)),frame.color(),false,"export-scene-color");
            execute(plan,lane);frameBegun=false;opaqueComplete=false;frameLane=null;
        }catch(RuntimeException|Error failure){swaps.restore(prior);throw failure;}
    }
    private void clearTargets(PackFrameGraph plan,PackRenderTargets.Color fog){
        for(var entry:images.colors.entrySet()){
            var target=pack.targets().targets().get(entry.getKey().color());if(!initialized||target.clear())clear(plan,entry.getValue(),target.fogColor()?fog:target.color());
        }
    }
    private void importDepth(PackFrameGraph plan,Frame frame,GpuImageView target,String name){
        if(depthPass==null)return;
        if(frame.depth()==null)throw new UnsupportedShaderException("Pack depth samplers require the real scene depth view");
        String input=plan.read(frame.depth()),output=plan.write(target);
        plan.pass(name,Map.of(input,VulkanRenderGraph.Usage.GRAPHICS_SAMPLE,output,VulkanRenderGraph.Usage.COLOR_WRITE),
                pass->depthPass.record(pass,List.of(output),Map.of("inputDepth",input),depthSampler.handle()));
    }
    private boolean recordPasses(PackFrameGraph plan,Uniforms uniforms,Set<CompiledShaderPack.Stage> stages,Map<String,GpuImageView> textures){
        boolean finalWritten=false;
        for(var entry:passes.entrySet()){
            var program=entry.getKey();if(!stages.contains(program.stage()))continue;
            uniforms.fill(program,entry.getValue().values);entry.getValue().upload();boolean shadow=program.stage()==CompiledShaderPack.Stage.SHADOWCOMPOSITE;
            var routing=shadow?null:swaps.route(program);
            for(int index:new java.util.TreeSet<>(program.mipmaps()))mipmaps.generate(plan,images.levels.get(swaps.current(index)));
            Map<String,VulkanRenderGraph.Usage> uses=new LinkedHashMap<>();Map<String,String> samples=new LinkedHashMap<>();Map<String,Long> sampleModes=new LinkedHashMap<>();
            for(String samplerName:program.code().descriptors().samplers().keySet()){
                int colorIndex=PackRenderTargets.colorIndex(samplerName);var color=colorIndex<0?null:swaps.current(colorIndex);int depth=PackRenderTargets.depthIndex(samplerName);
                int shadowDepth=PackShadowSettings.depthIndex(samplerName),shadowColor=PackShadowSettings.colorIndex(samplerName);
                boolean comparison=program.code().descriptors().comparisonSampler(samplerName);
                GpuImageView image=shadowDepth>=0?shadowDepthSample(shadowDepth,comparison):shadowColor>=0?shadowColor(shadowColor):color!=null?(program.mipmaps().contains(color.color())?images.sampling.get(color):images.colors.get(color)):
                        depth==1?images.opaqueDepth:depth>=0?images.depth:textures.get(samplerName);
                if(image==null)throw new UnsupportedShaderException("Unbound shader-pack sampler: "+samplerName+" in "+program.name());
                String name=plan.read(image);samples.put(samplerName,name);uses.put(name,VulkanRenderGraph.Usage.GRAPHICS_SAMPLE);
                if(comparison&&shadowDepth<0)throw new UnsupportedShaderException("Depth comparison requires a bound shadow depth texture: "+samplerName);
                sampleModes.put(samplerName,shadowDepth>=0?shadowSamplerHandle(shadowDepth,comparison):shadowColor>=0?shadowColorSamplerHandle(shadowColor):color!=null&&program.mipmaps().contains(color.color())?mipSampler.handle():sampler.handle());
            }
            List<String> outputs=new ArrayList<>();for(GpuImageView image:shadow?shadows.targets(program):outputs(routing)){
                String name=image==null?null:plan.write(image);outputs.add(name);if(name!=null)uses.put(name,VulkanRenderGraph.Usage.COLOR_WRITE);
            }
            PackFullscreenPass pass=entry.getValue();plan.pass(program.name(),uses,scope->pass.record(scope,outputs,samples,sampleModes,sampler.handle()));
            if(shadow)shadows.complete(program);else{swaps.complete(program);finalWritten|=routing.finalOutput();}
        }
        return finalWritten;
    }
    private void execute(PackFrameGraph plan,ImmediateCommands lane){
        if(!plan.operations.isEmpty())try(VulkanRenderGraph graph=plan.create(context,lane)){graph.execute();}
    }
    public boolean hasSceneProgram(){return scene;}
    public long samplerHandle(){requireOpen();return sampler.handle();}
    public long depthSamplerHandle(){requireOpen();return depthSampler.handle();}
    public GpuImageView shadowDepth(int index){requireShadows();return shadows.depth(index);}
    public GpuImageView shadowDepthSample(int index,boolean comparison){requireShadows();return shadows.depthSample(index,comparison);}
    public GpuImageView shadowColor(int index){requireShadows();return shadows.color(index);}
    public long shadowSamplerHandle(int index,boolean comparison){requireShadows();return shadows.depthSampler(index,comparison);}
    public long shadowColorSamplerHandle(int index){requireShadows();return shadows.colorSampler(index);}
    public List<GpuImageView> shadowTargets(CompiledShaderPack.Program program){requireShadows();if(program.stage()!=CompiledShaderPack.Stage.SHADOW)throw new IllegalArgumentException("Not a light-camera scene program");return shadows.targets(program);}
    public void prepareShadows(ImmediateCommands lane){
        requireShadows();if(!frameBegun||shadowsPrepared)throw new IllegalStateException("Shadow capture requires one active unprepared world frame");
        PackFrameGraph plan=new PackFrameGraph();shadows.clear(plan);execute(plan,lane);shadowsPrepared=true;
    }
    public void snapshotOpaqueShadow(ImmediateCommands lane){
        requireShadows();if(!shadowsPrepared||shadowsComplete||shadowOpaque)throw new IllegalStateException("Opaque shadow boundary is not active");
        PackFrameGraph plan=new PackFrameGraph();shadows.snapshotOpaque(plan);execute(plan,lane);shadowOpaque=true;
    }
    public void finishShadows(ImmediateCommands lane,Frame frame,Uniforms uniforms){
        requireShadows();validateFrame(frame);if(!shadowsPrepared||!shadowOpaque||shadowsComplete)throw new IllegalStateException("Finish opaque and translucent light-camera geometry before shadow completion");
        int[] prior=shadows.snapshot(),colors=swaps.snapshot();PackFrameGraph plan=new PackFrameGraph();
        try{recordPasses(plan,uniforms,Set.of(CompiledShaderPack.Stage.SHADOWCOMPOSITE,CompiledShaderPack.Stage.PREPARE),frame.textures());execute(plan,lane);shadowsComplete=true;}
        catch(RuntimeException|Error failure){shadows.restore(prior);swaps.restore(colors);throw failure;}
    }
    private void requireShadows(){requireOpen();if(shadows==null)throw new IllegalStateException("No bound light-camera generation");}
    /** These are copies of the scene depth; sampling never feeds back through its active writable attachment. */
    public GpuImageView sampledDepth(int index){
        requireOpen();if(index<0||index>2)throw new IllegalArgumentException("Pack depth sampler index must be in [0,2]");
        return index==1?images.opaqueDepth:images.depth;
    }
    /** Refreshes world depth between geometry runs without a nested queue submission or writable-attachment feedback. */
    public void recordDepthSnapshot(VkCommandBuffer command,GpuImageView source,List<GpuImage.LayoutChange> changes){
        requireOpen();if(!frameBegun||frameLane==null||depthPass==null)throw new IllegalStateException("No captured shader-pack depth generation");
        if(source==null||source.mipCount()!=1||source.layerCount()!=1||!source.image().format().isDepth()||source.image().context()!=context||source.image().width(source.baseMip())!=width||source.image().height(source.baseMip())!=height)
            throw new IllegalArgumentException("Depth snapshot must use the real full-frame scene attachment");
        PackFrameGraph plan=new PackFrameGraph();String input=plan.read(source),output=plan.write(images.depth);
        plan.pass("snapshot-scene-depth",Map.of(input,VulkanRenderGraph.Usage.GRAPHICS_SAMPLE,output,VulkanRenderGraph.Usage.COLOR_WRITE),
                pass->depthPass.record(pass,List.of(output),Map.of("inputDepth",input),depthSampler.handle()));
        try(VulkanRenderGraph graph=plan.create(context,frameLane)){graph.record(command,changes);}
    }
    public GpuImageView color(int index){requireOpen();GpuImageView view=images.colors.get(swaps.current(index));if(view==null)throw new IllegalArgumentException("Pack color target is absent: "+index);return view;}
    public List<GpuImageView> sceneTargets(CompiledShaderPack.Program program){requireOpen();if(program.stage()!=CompiledShaderPack.Stage.GBUFFER)throw new IllegalArgumentException("Not a world gbuffer program");return outputs(swaps.route(program));}
    /** Clears pack-owned attachments before actual world geometry is captured; temporal buffers retain their content. */
    public void beginScene(ImmediateCommands lane,PackRenderTargets.Color fog){
        requireOpen();if(!scene)throw new IllegalStateException("Pack has no scene programs");PackFrameGraph plan=new PackFrameGraph();
        if(shadows!=null)throw new IllegalStateException("Use beginFrame and capture the light-camera geometry before the world scene");
        if(frameBegun)throw new IllegalStateException("Shader-pack world frame is already open");
        if(passes.keySet().stream().anyMatch(program->program.stage()==CompiledShaderPack.Stage.BEGIN||program.stage()==CompiledShaderPack.Stage.PREPARE))
            throw new IllegalStateException("Use beginFrame to bind uniforms for pre-scene pack programs");
        clearTargets(plan,fog);execute(plan,lane);initialized=true;frameBegun=true;opaqueComplete=false;frameLane=lane;
    }
    public static NativeGraphicsPipeline.Color colorState(ShaderPackProperties.ProgramState state,int target){return PackPipelineState.color(state,target);}
    public static NativeGraphicsPipeline.Color shadowColorState(ShaderPackProperties.ProgramState state,int target){return PackPipelineState.color(state,target,"shadowcolor");}
    private List<GpuImageView> outputs(PackTargetSwaps.Routing routing){
        if(routing.finalOutput())return List.of(images.output);
        List<GpuImageView> result=new ArrayList<>();for(var output:routing.outputs())result.add(output==null?null:images.colors.get(output));return result;
    }
    private void validateFrame(Frame frame){
        if(frame.color().image().context()!=context||frame.color().image().width(frame.color().baseMip())!=width||frame.color().image().height(frame.color().baseMip())!=height)
            throw new IllegalArgumentException("Pack frame color differs from runtime dimensions/device");
        if((frame.color().image().format().aspectMask()&VK_IMAGE_ASPECT_COLOR_BIT)==0)throw new IllegalArgumentException("Pack color input must be a color image");
        if(frame.depth()!=null&&(frame.depth().image().context()!=context||frame.depth().image().width(frame.depth().baseMip())!=width
                ||frame.depth().image().height(frame.depth().baseMip())!=height))throw new IllegalArgumentException("Pack depth differs from frame dimensions/device");
        for(GpuImageView image:frame.textures().values())if(image.image().context()!=context)throw new IllegalArgumentException("Pack sampler belongs to another device");
    }
    static void clear(PackFrameGraph plan,GpuImageView target,PackRenderTargets.Color color){
        String name=plan.write(target);plan.pass("clear-"+name,Map.of(name,VulkanRenderGraph.Usage.TRANSFER_WRITE),pass->{
            try(MemoryStack stack=MemoryStack.stackPush()){
                var value=VkClearColorValue.calloc(stack).float32(0,color.red()).float32(1,color.green()).float32(2,color.blue()).float32(3,color.alpha());
                var range=VkImageSubresourceRange.calloc(1,stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(target.baseMip()).levelCount(1).baseArrayLayer(target.baseLayer()).layerCount(1);
                vkCmdClearColorImage(pass.command(),target.image().handle(),VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,value,range);
            }
        });
    }
    private void blit(PackFrameGraph plan,GpuImageView source,GpuImageView target,boolean flip,String passName){
        requireBlit(source.image().format().vkFormat(),VK_FORMAT_FEATURE_BLIT_SRC_BIT);requireBlit(target.image().format().vkFormat(),VK_FORMAT_FEATURE_BLIT_DST_BIT);
        String read=plan.read(source),write=plan.write(target);
        plan.pass(passName,Map.of(read,VulkanRenderGraph.Usage.TRANSFER_READ,write,VulkanRenderGraph.Usage.TRANSFER_WRITE),pass->{
            try(MemoryStack stack=MemoryStack.stackPush()){
                var region=VkImageBlit.calloc(1,stack);region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(source.baseMip()).baseArrayLayer(source.baseLayer()).layerCount(1);
                region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(target.baseMip()).baseArrayLayer(target.baseLayer()).layerCount(1);
                int sourceHeight=source.image().height(source.baseMip());region.srcOffsets(0).set(0,flip?sourceHeight:0,0);
                region.srcOffsets(1).set(source.image().width(source.baseMip()),flip?0:sourceHeight,1);
                region.dstOffsets(0).set(0,0,0);region.dstOffsets(1).set(target.image().width(target.baseMip()),target.image().height(target.baseMip()),1);
                vkCmdBlitImage(pass.command(),source.image().handle(),VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,target.image().handle(),VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,region,VK_FILTER_NEAREST);
            }
        });
    }
    private void requireBlit(int format,int feature){
        try(MemoryStack stack=MemoryStack.stackPush()){
            var properties=VkFormatProperties.malloc(stack);vkGetPhysicalDeviceFormatProperties(context.physicalDevice(),format,properties);
            if((properties.optimalTilingFeatures()&feature)==0)throw new UnsupportedShaderException("Device cannot blit shader-pack image format: "+format);
        }
    }
    private static CompiledShaderPack.Program depthProgram(ShadercCompiler compiler){
        String vertex="#version 120\nattribute vec2 Position; varying vec2 uv; void main(){uv=Position;gl_Position=vec4(Position*2.0-1.0,0.0,1.0);}";
        String fragment="#version 120\nuniform sampler2D inputDepth; varying vec2 uv; void main(){gl_FragColor=vec4(1.0-texture2D(inputDepth,uv).r);}";
        var code=ShaderPackProgram.compile("overhaul-depth-import",vertex,fragment,compiler,CoordinateConvention.NATIVE_VULKAN);
        return new CompiledShaderPack.Program("overhaul-depth-import",CompiledShaderPack.Stage.FINAL,0,code,
                new ShaderPackProperties.ProgramState(true,Map.of(),java.util.Optional.empty(),Map.of()));
    }
    private void requireOpen(){context.requireOwner();if(closed)throw new IllegalStateException("Pack graph runtime closed");}
    @Override public void close(){
        context.requireOwner();if(closed)return;List<PackFullscreenPass> reverse=new ArrayList<>(passes.values());Collections.reverse(reverse);
        for(var pass:reverse)pass.close();passes.clear();if(depthPass!=null){depthPass.close();depthPass=null;}
        if(depthSampler!=null){depthSampler.close();depthSampler=null;}if(mipSampler!=null){mipSampler.close();mipSampler=null;}
        if(shadows!=null){shadows.close();shadows=null;}
        if(sampler!=null){sampler.close();sampler=null;}if(images!=null){images.close();images=null;}frameLane=null;closed=true;
    }
}
