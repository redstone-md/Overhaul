package dev.overhaul.vulkan;

import dev.overhaul.shader.compat.CoordinateConvention;
import dev.overhaul.shader.compat.UniformBlock;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.graph.PackTargetSwaps;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackRenderTargets;
import dev.overhaul.shader.pack.ShaderPackProgram;
import dev.overhaul.shader.pack.ShaderPackProperties;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private GpuSampler sampler,depthSampler;
    private PackFullscreenPass depthPass;
    private boolean initialized,closed;
    private final boolean scene;

    public PackGraphRuntime(VulkanContext context,GpuAllocator allocator,CompiledShaderPack pack,int width,int height,ShadercCompiler compiler){
        this(context,allocator,pack,width,height,compiler,false);
    }
    public PackGraphRuntime(VulkanContext context,GpuAllocator allocator,CompiledShaderPack pack,int width,int height,ShadercCompiler compiler,boolean bindTerrainScene){
        this.context=context;this.pack=pack;this.width=width;this.height=height;context.requireOwner();
        scene=pack.programs().stream().anyMatch(CompiledShaderPack.Program::scene);
        for(var program:pack.programs())if(program.scene()&&(!bindTerrainScene||!program.name().endsWith("gbuffers_terrain"))||!program.scene()&&program.stage()!=CompiledShaderPack.Stage.DEFERRED
                &&program.stage()!=CompiledShaderPack.Stage.COMPOSITE&&program.stage()!=CompiledShaderPack.Stage.FINAL)
            throw new UnsupportedShaderException("Game scene geometry is not bound to shader-pack stage: "+program.name());
        try{
            images=new PackGraphImages(allocator,pack.targets(),width,height);
            sampler=allocator.createSampler(new SamplerSettings(SamplerSettings.Filter.LINEAR,SamplerSettings.Filter.LINEAR,SamplerSettings.Mipmap.NEAREST,
                    SamplerSettings.Address.CLAMP,SamplerSettings.Address.CLAMP,SamplerSettings.Address.CLAMP,0,0,1,SamplerSettings.Compare.NONE));
            depthSampler=allocator.createSampler(SamplerSettings.nearest());
            for(var program:pack.programs()){
                if(program.scene())continue;
                var outputs=outputs(swaps.route(program));int passWidth=0,passHeight=0;List<Integer> formats=new ArrayList<>();
                for(GpuImageView output:outputs){
                    formats.add(output==null?VK_FORMAT_UNDEFINED:output.image().format().vkFormat());if(output==null)continue;
                    int x=output.image().width(0),y=output.image().height(0);
                    if(passWidth!=0&&(passWidth!=x||passHeight!=y))throw new UnsupportedShaderException("MRT targets require equal dimensions: "+program.name());passWidth=x;passHeight=y;
                }
                if(passWidth==0)throw new UnsupportedShaderException("Fullscreen pass has no active color attachment: "+program.name());
                passes.put(program,new PackFullscreenPass(context,allocator,program,formats,passWidth,passHeight));
            }
            boolean needsDepth=pack.programs().stream().anyMatch(program->program.code().descriptors().samplers().keySet().stream().anyMatch(name->PackRenderTargets.depthIndex(name)==0));
            if(needsDepth)depthPass=new PackFullscreenPass(context,allocator,depthProgram(compiler),List.of(VK_FORMAT_R32_SFLOAT),width,height);
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    public void render(ImmediateCommands lane,Frame frame,Uniforms uniforms){
        requireOpen();validateFrame(frame);int[] prior=swaps.snapshot();
        for(var entry:passes.entrySet()){uniforms.fill(entry.getKey(),entry.getValue().values);entry.getValue().upload();}
        PackFrameGraph plan=new PackFrameGraph();
        if(!scene)for(var entry:images.colors.entrySet()){
            var target=pack.targets().targets().get(entry.getKey().color());if(!initialized||target.clear())clear(plan,entry.getValue(),target.fogColor()?frame.fog():target.color());
        }
        clear(plan,images.output,new PackRenderTargets.Color(0,0,0,0));
        if(!scene)blit(plan,frame.color(),images.colors.get(swaps.current(0)),false,"import-scene-color");
        if(depthPass!=null){
            if(frame.depth()==null)throw new UnsupportedShaderException("depthtex0 requires the real scene depth view");
            clear(plan,images.depth,new PackRenderTargets.Color(1,1,1,1));
            String input=plan.read(frame.depth()),output=plan.write(images.depth);
            plan.pass("import-scene-depth",Map.of(input,VulkanRenderGraph.Usage.GRAPHICS_SAMPLE,output,VulkanRenderGraph.Usage.COLOR_WRITE),
                    pass->depthPass.record(pass,List.of(output),Map.of("inputDepth",input),depthSampler.handle()));
        }
        boolean finalWritten=false;
        try{
            for(var entry:passes.entrySet()){
                var program=entry.getKey();var routing=swaps.route(program);
                Map<String,VulkanRenderGraph.Usage> uses=new LinkedHashMap<>();Map<String,String> samples=new LinkedHashMap<>();
                for(String samplerName:program.code().descriptors().samplers().keySet()){
                    var color=routing.samplers().get(samplerName);GpuImageView image=color==null?
                            PackRenderTargets.depthIndex(samplerName)==0?images.depth:frame.textures().get(samplerName):images.colors.get(color);
                    if(image==null)throw new UnsupportedShaderException("Unbound shader-pack sampler: "+samplerName+" in "+program.name());
                    String name=plan.read(image);samples.put(samplerName,name);uses.put(name,VulkanRenderGraph.Usage.GRAPHICS_SAMPLE);
                }
                List<String> outputs=new ArrayList<>();for(GpuImageView image:outputs(routing)){
                    String name=image==null?null:plan.write(image);outputs.add(name);if(name!=null)uses.put(name,VulkanRenderGraph.Usage.COLOR_WRITE);
                }
                PackFullscreenPass pass=entry.getValue();plan.pass(program.name(),uses,scope->pass.record(scope,outputs,samples,sampler.handle()));
                swaps.complete(program);finalWritten|=routing.finalOutput();
            }
            blit(plan,finalWritten?images.output:images.colors.get(swaps.current(0)),frame.color(),false,"export-scene-color");
            try(VulkanRenderGraph graph=plan.create(context,lane)){graph.execute();}initialized=true;
        }catch(RuntimeException|Error failure){swaps.restore(prior);throw failure;}
    }
    public boolean hasSceneProgram(){return scene;}
    public GpuImageView color(int index){requireOpen();GpuImageView view=images.colors.get(swaps.current(index));if(view==null)throw new IllegalArgumentException("Pack color target is absent: "+index);return view;}
    public List<GpuImageView> sceneTargets(CompiledShaderPack.Program program){requireOpen();if(!program.scene())throw new IllegalArgumentException("Not a scene program");return outputs(swaps.route(program));}
    /** Clears pack-owned attachments before actual world geometry is captured; temporal buffers retain their content. */
    public void beginScene(ImmediateCommands lane,PackRenderTargets.Color fog){
        requireOpen();if(!scene)throw new IllegalStateException("Pack has no scene programs");PackFrameGraph plan=new PackFrameGraph();
        for(var entry:images.colors.entrySet()){
            var target=pack.targets().targets().get(entry.getKey().color());if(!initialized||target.clear())clear(plan,entry.getValue(),target.fogColor()?fog:target.color());
        }
        if(!plan.operations.isEmpty())try(VulkanRenderGraph graph=plan.create(context,lane)){graph.execute();}initialized=true;
    }
    public static NativeGraphicsPipeline.Color colorState(ShaderPackProperties.ProgramState state,int target){return PackPipelineState.color(state,target);}
    private List<GpuImageView> outputs(PackTargetSwaps.Routing routing){
        if(routing.finalOutput())return List.of(images.output);
        List<GpuImageView> result=new ArrayList<>();for(var output:routing.outputs())result.add(output==null?null:images.colors.get(output));return result;
    }
    private void validateFrame(Frame frame){
        if(frame.color().image().context()!=context||frame.color().image().width(frame.color().baseMip())!=width||frame.color().image().height(frame.color().baseMip())!=height)
            throw new IllegalArgumentException("Pack frame color differs from runtime dimensions/device");
        if((frame.color().image().format().aspectMask()&VK_IMAGE_ASPECT_COLOR_BIT)==0)throw new IllegalArgumentException("Pack color input must be a color image");
        if(frame.depth()!=null&&frame.depth().image().context()!=context)throw new IllegalArgumentException("Pack depth belongs to another device");
        for(GpuImageView image:frame.textures().values())if(image.image().context()!=context)throw new IllegalArgumentException("Pack sampler belongs to another device");
    }
    private static void clear(PackFrameGraph plan,GpuImageView target,PackRenderTargets.Color color){
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
        if(depthSampler!=null){depthSampler.close();depthSampler=null;}if(sampler!=null){sampler.close();sampler=null;}if(images!=null){images.close();images=null;}closed=true;
    }
}
