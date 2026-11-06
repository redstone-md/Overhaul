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
    /** A game texture a pack names by location, with the sampler the game reads it through. */
    public record GameTexture(GpuImageView view,long sampler){}
    private final VulkanContext context;
    private final CompiledShaderPack pack;
    private java.util.function.Function<String,GameTexture> games=location->null;
    private static final String BLOCK_ATLAS="minecraft:textures/atlas/blocks.png";
    private static final java.util.Set<String> ATLAS_ALIASES=java.util.Set.of("tex","texture","gtexture");
    private final PackTargetSwaps swaps=new PackTargetSwaps();
    private PackFrameGraph.Shapes shapes=new PackFrameGraph.Shapes();
    private final Map<CompiledShaderPack.Program,PackFullscreenPass> passes=new LinkedHashMap<>();
    /** One fullscreen program or one compute dispatch, in the order the pack runs them. */
    private record Step(CompiledShaderPack.Program program,PackComputePass compute){
        CompiledShaderPack.Program owner(){return compute==null?program:compute.program;}
    }
    private final List<Step> steps=new ArrayList<>();
    private final List<PackComputePass> computePasses=new ArrayList<>();
    private PackImageSet packImages;
    private final int width,height;
    private PackGraphImages images;
    private PackShadowImages shadows;
    private PackTextureImages customTextures;
    private GpuSampler sampler,depthSampler,mipSampler;
    private final PackMipmaps mipmaps;
    private PackFullscreenPass depthPass;
    private ImmediateCommands frameLane;
    private PackRecording.Recorder recorder;
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
        this(context,allocator,pack,width,height,compiler,boundScenePrograms,true);
    }
    /** Packs read depth as far=1. {@code reversedSceneDepth} says the game's depth attachment holds far=0 and must be turned over on import. */
    public PackGraphRuntime(VulkanContext context,GpuAllocator allocator,CompiledShaderPack pack,int width,int height,ShadercCompiler compiler,Set<String> boundScenePrograms,boolean reversedSceneDepth){
        this.context=context;this.pack=pack;this.width=width;this.height=height;context.requireOwner();
        boundScenePrograms=Set.copyOf(boundScenePrograms);
        mipmaps=new PackMipmaps(context);
        scene=pack.programs().stream().anyMatch(program->program.stage()==CompiledShaderPack.Stage.GBUFFER);
        boolean hasShadows=pack.programs().stream().anyMatch(program->program.stage()==CompiledShaderPack.Stage.SHADOW);
        for(var program:pack.programs())if(program.scene()&&!boundScenePrograms.contains(program.name().substring(program.name().lastIndexOf('/')+1)))
            throw new UnsupportedShaderException("Game scene geometry is not bound to shader-pack stage: "+program.name());
        for(var program:pack.programs())if(!hasShadows&&(program.stage()==CompiledShaderPack.Stage.SHADOWCOMPOSITE||program.code().descriptors().samplers().keySet().stream().anyMatch(name->pack.textures().image(program,name)==null&&(PackShadowSettings.depthIndex(name)>=0||PackShadowSettings.colorIndex(name)>=0))))
            throw new UnsupportedShaderException("Shadow sampling requires bound light-camera geometry: "+program.name());
        try{
            images=new PackGraphImages(allocator,pack.targets(),width,height);
            customTextures=new PackTextureImages(context,allocator,pack.textures());
            packImages=new PackImageSet(context,allocator,pack.images(),width,height);
            for(var program:pack.programs()){
                packImages.requireStorage(program.code().descriptors(),program.name());
                if(program.scene()&&program.code().descriptors().hasStorageImages()&&!context.vertexStores())
                    throw new UnsupportedShaderException("Device cannot write storage images from scene geometry: "+program.name());
            }
            if(hasShadows)shadows=new PackShadowImages(context,allocator,pack.shadow(),compiler);
            sampler=allocator.createSampler(new SamplerSettings(SamplerSettings.Filter.LINEAR,SamplerSettings.Filter.LINEAR,SamplerSettings.Mipmap.NEAREST,
                    SamplerSettings.Address.CLAMP,SamplerSettings.Address.CLAMP,SamplerSettings.Address.CLAMP,0,0,1,SamplerSettings.Compare.NONE));
            depthSampler=allocator.createSampler(SamplerSettings.nearest());
            if(pack.targets().targets().values().stream().anyMatch(PackRenderTargets.Target::mipmapped))
                mipSampler=allocator.createSampler(new SamplerSettings(SamplerSettings.Filter.LINEAR,SamplerSettings.Filter.LINEAR,SamplerSettings.Mipmap.LINEAR,
                        SamplerSettings.Address.CLAMP,SamplerSettings.Address.CLAMP,SamplerSettings.Address.CLAMP,0,31,1,SamplerSettings.Compare.NONE));
            for(var program:pack.programs()){
                if(program.scene())continue;
                var outputs=program.stage()==CompiledShaderPack.Stage.SHADOWCOMPOSITE?shadows.targets(program):outputs(route(program));int passWidth=0,passHeight=0;List<Integer> formats=new ArrayList<>();
                for(GpuImageView output:outputs){
                    formats.add(output==null?VK_FORMAT_UNDEFINED:output.image().format().vkFormat());if(output==null)continue;
                    int x=output.image().width(0),y=output.image().height(0);
                    if(passWidth!=0&&(passWidth!=x||passHeight!=y))throw new UnsupportedShaderException("MRT targets require equal dimensions: "+program.name());passWidth=x;passHeight=y;
                }
                if(passWidth==0)throw new UnsupportedShaderException("Fullscreen pass has no active color attachment: "+program.name());
                passes.put(program,new PackFullscreenPass(context,allocator,program,formats,passWidth,passHeight));
            }
            orderSteps(allocator);
            boolean needsDepth=pack.programs().stream().anyMatch(program->program.code().descriptors().samplers().keySet().stream().anyMatch(name->PackRenderTargets.depthIndex(name)>=0&&pack.textures().image(program,name)==null));
            if(needsDepth)depthPass=new PackFullscreenPass(context,allocator,depthProgram(compiler,reversedSceneDepth),List.of(VK_FORMAT_R32_SFLOAT),width,height);
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    /** Each compute runs before the fullscreen program of its stage and ordinal, and also when that program is absent. */
    private void orderSteps(GpuAllocator allocator){
        var computes=new java.util.ArrayDeque<PackComputePass>();
        for(var compute:pack.computes()){
            packImages.requireStorage(compute.bindings(),compute.name());
            var pass=new PackComputePass(context,allocator,compute);computePasses.add(pass);computes.add(pass);
        }
        java.util.Comparator<CompiledShaderPack.Program> order=java.util.Comparator.comparing(CompiledShaderPack.Program::stage).thenComparingInt(CompiledShaderPack.Program::ordinal);
        for(var program:passes.keySet()){
            while(!computes.isEmpty()&&order.compare(computes.peek().program,program)<=0)steps.add(new Step(null,computes.poll()));
            steps.add(new Step(program,null));
        }
        while(!computes.isEmpty())steps.add(new Step(null,computes.poll()));
    }
    /** Zeroes the pack images that ask for it, every image on the first frame. */
    private void clearPackImages(PackFrameGraph plan,ImmediateCommands lane){
        packImages.initialize(lane);if(packImages.isEmpty())return;boolean all=!initialized;
        plan.pass("clear-pack-images",Map.of(),pass->packImages.clear(pass.command(),all));
    }
    /** Runs only before world capture; temporal buffers retain their contents unless their clear directive requests it. */
    public void beginFrame(ImmediateCommands lane,Frame frame,Uniforms uniforms){
        requireOpen();validateFrame(frame);if(frameBegun)throw new IllegalStateException("Shader-pack world frame is already open");
        customTextures.initialize(lane);
        int[] prior=swaps.snapshot();PackFrameGraph plan=new PackFrameGraph();
        clearTargets(plan,frame.fog());clearPackImages(plan,lane);
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
        for(Step step:steps){
            var program=step.owner();if(!stages.contains(program.stage()))continue;
            Map<String,VulkanRenderGraph.Usage> uses=new LinkedHashMap<>();Map<String,String> samples=new LinkedHashMap<>();Map<String,Long> sampleModes=new LinkedHashMap<>();
            if(step.compute()!=null){
                var compute=step.compute();uniforms.fill(program,compute.values());int uniformOffset=compute.upload();
                bind(plan,program,textures,uses,samples,sampleModes,VulkanRenderGraph.Usage.COMPUTE_SAMPLE);
                plan.pass("compute:"+compute.compute().name(),uses,scope->compute.record(scope,samples,sampleModes,sampler.handle(),uniformOffset,packImages,width,height));
                continue;
            }
            PackFullscreenPass pass=passes.get(program);
            uniforms.fill(program,pass.values);int uniformOffset=pass.upload();boolean shadow=program.stage()==CompiledShaderPack.Stage.SHADOWCOMPOSITE;
            var routing=shadow?null:route(program);
            bind(plan,program,textures,uses,samples,sampleModes,VulkanRenderGraph.Usage.GRAPHICS_SAMPLE);
            List<String> outputs=new ArrayList<>();for(GpuImageView image:shadow?shadows.targets(program):outputs(routing)){
                String name=image==null?null:plan.write(image);outputs.add(name);if(name!=null)uses.put(name,VulkanRenderGraph.Usage.COLOR_WRITE);
            }
            plan.pass(program.name(),uses,scope->pass.record(scope,outputs,samples,sampleModes,sampler.handle(),uniformOffset,packImages));
            if(shadow)shadows.complete(program);else{swaps.complete(program);finalWritten|=routing.finalOutput();}
        }
        return finalWritten;
    }
    /** Resolves every graph-tracked sampler of a program; pack images bind outside the graph. */
    private void bind(PackFrameGraph plan,CompiledShaderPack.Program program,Map<String,GpuImageView> textures,Map<String,VulkanRenderGraph.Usage> uses,
                      Map<String,String> samples,Map<String,Long> sampleModes,VulkanRenderGraph.Usage usage){
        var bindings=program.code().descriptors();
        for(int index:new java.util.TreeSet<>(program.mipmaps()))if(bindings.samplers().keySet().stream()
                .anyMatch(name->PackRenderTargets.colorIndex(name)==index&&!customTextures.overrides(program,name)))
            mipmaps.generate(plan,images.levels.get(swaps.current(index)));
        // Shadow composite can overwrite/flip a color between readers. Rebuild the selected side,
        // without persistent dirty state that could survive a failed graph transaction.
        var sampledShadowColors=new java.util.TreeSet<Integer>();
        for(String name:bindings.samplers().keySet()){
            int index=PackShadowSettings.colorIndex(name);if(index>=0&&!customTextures.overrides(program,name)&&!packImages.owns(bindings,name))sampledShadowColors.add(index);
        }
        for(int index:sampledShadowColors)shadows.generateColor(plan,index,mipmaps);
        for(String samplerName:bindings.samplers().keySet()){
            GameTexture game=gameTexture(program,samplerName);
            if(game!=null){
                String name=plan.read(game.view());samples.put(samplerName,name);uses.put(name,usage);
                sampleModes.put(samplerName,game.sampler());continue;
            }
            GpuImageView custom=customTexture(program,samplerName);
            if(custom!=null){
                String name=plan.read(custom);samples.put(samplerName,name);uses.put(name,usage);
                sampleModes.put(samplerName,customSamplerHandle(program,samplerName));continue;
            }
            if(packImages.owns(bindings,samplerName))continue;
            int colorIndex=PackRenderTargets.colorIndex(samplerName);var color=colorIndex<0?null:swaps.current(colorIndex);int depth=PackRenderTargets.depthIndex(samplerName);
            int shadowDepth=PackShadowSettings.depthIndex(samplerName,bindings.samplers().keySet()),shadowColor=PackShadowSettings.colorIndex(samplerName);
            boolean comparison=bindings.comparisonSampler(samplerName);
            GpuImageView image=shadowDepth>=0?shadowDepthSample(shadowDepth,comparison):shadowColor>=0?shadowColor(shadowColor):color!=null?(program.mipmaps().contains(color.color())?images.sampling.get(color):images.colors.get(color)):
                    depth==1?images.opaqueDepth:depth>=0?images.depth:textures.get(samplerName);
            if(image==null&&!comparison&&ATLAS_ALIASES.contains(samplerName)){
                // A fullscreen pass has no draw texture; packs read its size there for the block atlas.
                GameTexture atlas=games.apply(BLOCK_ATLAS);
                if(atlas!=null){String name=plan.read(atlas.view());samples.put(samplerName,name);uses.put(name,usage);sampleModes.put(samplerName,atlas.sampler());continue;}
            }
            if(image==null)throw new UnsupportedShaderException("Unbound shader-pack sampler: "+samplerName+" in "+program.name());
            String name=plan.read(image);samples.put(samplerName,name);uses.put(name,usage);
            if(comparison&&shadowDepth<0)throw new UnsupportedShaderException("Depth comparison requires a bound shadow depth texture: "+samplerName);
            sampleModes.put(samplerName,shadowDepth>=0?shadowSamplerHandle(shadowDepth,comparison):shadowColor>=0?shadowColorSamplerHandle(shadowColor):color!=null&&program.mipmaps().contains(color.color())?mipSampler.handle():sampler.handle());
        }
    }
    private void execute(PackFrameGraph plan,ImmediateCommands lane){
        if(plan.operations.isEmpty())return;
        if(recorder==null){try(VulkanRenderGraph graph=plan.create(context,lane,shapes)){graph.execute();}return;}
        recorder.add(new PackRecording(plan.create(context,lane,shapes)));
    }
    /**
     * Hands every later graph to the caller's ordered frame lane instead of a submission that waits for the GPU. The
     * lane records each one after the work queued before it, and before the next.
     */
    public void recordInto(PackRecording.Recorder recorder){requireOpen();this.recorder=recorder;}
    /** Off, every graph is built and validated from scratch instead of reusing an earlier frame's of the same shape. */
    public void reuseGraphShapes(boolean reuse){requireOpen();shapes=reuse?new PackFrameGraph.Shapes():null;}
    public boolean hasSceneProgram(){return scene;}
    public long samplerHandle(){requireOpen();return sampler.handle();}
    public long depthSamplerHandle(){requireOpen();return depthSampler.handle();}
    public GpuImageView customTexture(CompiledShaderPack.Program program,String name){requireOpen();return customTextures.view(program,name);}
    public long customSamplerHandle(CompiledShaderPack.Program program,String name){requireOpen();return customTextures.sampler(program,name);}
    /** Looks up the game textures packs name by location; it is asked again each frame, so a reloaded texture is seen. */
    public void resolveGameTextures(java.util.function.Function<String,GameTexture> resolver){requireOpen();games=java.util.Objects.requireNonNull(resolver);}
    /** The game texture a program's sampler names, or null when it names none. */
    public GameTexture gameTexture(CompiledShaderPack.Program program,String name){
        requireOpen();String location=pack.textures().gameTexture(program,name);if(location==null)return null;
        GameTexture texture=games.apply(location);
        if(texture==null)throw new UnsupportedShaderException("Game texture is unavailable: "+location);
        if(program.code().descriptors().comparisonSampler(name))throw new UnsupportedShaderException("Game texture cannot serve a depth comparison sampler: "+name);
        return texture;
    }
    /** True when a scene binding reads or writes a pack {@code image.*} rather than a graph image. */
    public boolean packImageBinding(dev.overhaul.shader.compat.ProgramBindings bindings,String name){requireOpen();return packImages.owns(bindings,name);}
    /** The pack image a binding names, in GENERAL layout; storage bindings name the image, sampled ones its sampler. */
    public GpuImageView packImage(dev.overhaul.shader.compat.ProgramBindings bindings,String name){
        requireOpen();GpuImageView view=bindings.storageImage(name)?packImages.storage(name):packImages.sampled(name);
        if(view==null)throw new IllegalArgumentException("Not a pack image binding: "+name);return view;
    }
    public long packImageSampler(String sampler){requireOpen();return packImages.sampler(sampler);}
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
        PackFrameGraph plan=new PackFrameGraph();shadows.snapshotOpaque(plan);shadows.generateDepth(plan,1);
        shadows.generateColors(plan,mipmaps);execute(plan,lane);shadowOpaque=true;
    }
    public void finishShadows(ImmediateCommands lane,Frame frame,Uniforms uniforms){
        requireShadows();validateFrame(frame);if(!shadowsPrepared||!shadowOpaque||shadowsComplete)throw new IllegalStateException("Finish opaque and translucent light-camera geometry before shadow completion");
        int[] prior=shadows.snapshot(),colors=swaps.snapshot();PackFrameGraph plan=new PackFrameGraph();
        try{
            shadows.generateDepth(plan,0);shadows.generateDepth(plan,1);
            recordPasses(plan,uniforms,Set.of(CompiledShaderPack.Stage.SHADOWCOMPOSITE,CompiledShaderPack.Stage.PREPARE),frame.textures());
            // Geometry samplers see the final current shadowcolor side, including the last flip.
            shadows.generateColors(plan,mipmaps);execute(plan,lane);shadowsComplete=true;
        }
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
        try(VulkanRenderGraph graph=plan.create(context,frameLane,shapes)){graph.record(command,changes);}
    }
    public GpuImageView color(int index){requireOpen();GpuImageView view=images.colors.get(swaps.current(index));if(view==null)throw new IllegalArgumentException("Pack color target is absent: "+index);return view;}
    public List<GpuImageView> sceneTargets(CompiledShaderPack.Program program){requireOpen();if(program.stage()!=CompiledShaderPack.Stage.GBUFFER)throw new IllegalArgumentException("Not a world gbuffer program");return outputs(route(program));}
    private PackTargetSwaps.Routing route(CompiledShaderPack.Program program){return swaps.route(program,name->!customTextures.overrides(program,name));}
    /** Clears pack-owned attachments before actual world geometry is captured; temporal buffers retain their content. */
    public void beginScene(ImmediateCommands lane,PackRenderTargets.Color fog){
        requireOpen();if(!scene)throw new IllegalStateException("Pack has no scene programs");PackFrameGraph plan=new PackFrameGraph();
        if(shadows!=null)throw new IllegalStateException("Use beginFrame and capture the light-camera geometry before the world scene");
        if(frameBegun)throw new IllegalStateException("Shader-pack world frame is already open");
        if(passes.keySet().stream().anyMatch(program->program.stage()==CompiledShaderPack.Stage.BEGIN||program.stage()==CompiledShaderPack.Stage.PREPARE))
            throw new IllegalStateException("Use beginFrame to bind uniforms for pre-scene pack programs");
        customTextures.initialize(lane);clearTargets(plan,fog);clearPackImages(plan,lane);execute(plan,lane);initialized=true;frameBegun=true;opaqueComplete=false;frameLane=lane;
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
            try(MemoryStack stack=VulkanStack.push()){
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
            try(MemoryStack stack=VulkanStack.push()){
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
        if((mipmaps.optimalFeatures(format)&feature)==0)throw new UnsupportedShaderException("Device cannot blit shader-pack image format: "+format);
    }
    private static CompiledShaderPack.Program depthProgram(ShadercCompiler compiler,boolean reversed){
        String vertex="#version 120\nattribute vec2 Position; varying vec2 uv; void main(){uv=Position;gl_Position=vec4(Position*2.0-1.0,0.0,1.0);}";
        String fragment="#version 120\nuniform sampler2D inputDepth; varying vec2 uv; void main(){gl_FragColor=vec4("+(reversed?"1.0-":"")+"texture2D(inputDepth,uv).r);}";
        var code=ShaderPackProgram.compile("overhaul-depth-import",vertex,fragment,compiler,CoordinateConvention.NATIVE_VULKAN);
        return new CompiledShaderPack.Program("overhaul-depth-import",CompiledShaderPack.Stage.FINAL,0,code,
                new ShaderPackProperties.ProgramState(true,Map.of(),java.util.Optional.empty(),Map.of()));
    }
    private void requireOpen(){context.requireOwner();if(closed)throw new IllegalStateException("Pack graph runtime closed");}
    @Override public void close(){
        context.requireOwner();if(closed)return;shapes=null;List<PackFullscreenPass> reverse=new ArrayList<>(passes.values());Collections.reverse(reverse);
        for(var pass:reverse)pass.close();passes.clear();
        for(int index=computePasses.size()-1;index>=0;index--)computePasses.get(index).close();computePasses.clear();steps.clear();if(depthPass!=null){depthPass.close();depthPass=null;}
        if(depthSampler!=null){depthSampler.close();depthSampler=null;}if(mipSampler!=null){mipSampler.close();mipSampler=null;}
        if(shadows!=null){shadows.close();shadows=null;}
        if(customTextures!=null){customTextures.close();customTextures=null;}
        if(packImages!=null){packImages.close();packImages=null;}
        if(sampler!=null){sampler.close();sampler=null;}if(images!=null){images.close();images=null;}frameLane=null;closed=true;
    }
}
