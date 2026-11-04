package dev.overhaul.vulkan;

import dev.overhaul.shader.pack.CascadeShadowSettings;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;

/** Two depth roles with independent cascade layers; no legacy Iris texture is reinterpreted. */
public final class CascadeShadowMaps implements AutoCloseable {
    private final VulkanContext context;
    private final CascadeShadowSettings settings;
    private final GpuImage[] images=new GpuImage[2];
    private final GpuImageView[][] views;
    private GpuSampler sampler;
    public CascadeShadowMaps(VulkanContext context,GpuAllocator allocator,CascadeShadowSettings settings){
        this.context=context;this.settings=settings;views=new GpuImageView[2][settings.count()];
        context.portability().requireMutableComparisonSamplers();
        try{
            ImageFormat format=PackShadowImages.depthFormat(context,true);
            for(int role=0;role<2;role++){
                images[role]=allocator.createImage(format,settings.resolution(),settings.resolution(),1,settings.count(),
                        VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT);
                for(int cascade=0;cascade<settings.count();cascade++)views[role][cascade]=images[role].view(0,1,cascade,1);
            }
            sampler=allocator.createSampler(new SamplerSettings(SamplerSettings.Filter.LINEAR,SamplerSettings.Filter.LINEAR,SamplerSettings.Mipmap.NEAREST,
                    SamplerSettings.Address.CLAMP,SamplerSettings.Address.CLAMP,SamplerSettings.Address.CLAMP,0,0,1,SamplerSettings.Compare.LESS_OR_EQUAL));
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    public int count(){return settings.count();}
    public GpuImageView depth(int cascade,boolean opaque){
        if(cascade<0||cascade>=count())throw new IllegalArgumentException("Invalid shadow cascade");return views[opaque?1:0][cascade];
    }
    public long sampler(){return sampler.handle();}
    public void clear(ImmediateCommands lane){
        PackFrameGraph graph=new PackFrameGraph();
        for(int role=0;role<2;role++)for(int cascade=0;cascade<count();cascade++){
            ShadowDepthPasses.clear(graph,views[role][cascade]);
        }
        execute(graph,lane);
    }
    public void snapshotOpaque(ImmediateCommands lane){
        PackFrameGraph graph=new PackFrameGraph();
        for(int cascade=0;cascade<count();cascade++){
            ShadowDepthPasses.copy(graph,depth(cascade,false),depth(cascade,true));
        }
        execute(graph,lane);
    }
    public void readable(ImmediateCommands lane){
        PackFrameGraph graph=new PackFrameGraph();Map<String,VulkanRenderGraph.Usage> uses=new LinkedHashMap<>();
        for(var role:views)for(var view:role)uses.put(graph.read(view),VulkanRenderGraph.Usage.GRAPHICS_SAMPLE);
        graph.pass("sample-cascade-depth",uses,pass->{});execute(graph,lane);
    }
    private void execute(PackFrameGraph graph,ImmediateCommands lane){try(var nativeGraph=graph.create(context,lane)){nativeGraph.execute();}}
    @Override public void close(){
        if(sampler!=null){sampler.close();sampler=null;}
        for(int index=images.length-1;index>=0;index--)if(images[index]!=null){images[index].close();images[index]=null;}
    }
}
