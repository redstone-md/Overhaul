package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.GpuImageView;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import static org.lwjgl.vulkan.VK10.*;

/** Per-pass clear state leases exclusive fence-retired native objects from the device cache. */
final class PassAttachments implements AutoCloseable {
    private record Entry(GpuImageView view,boolean depth,float[] color,Double clearDepth) {}
    private final NativePassResources resources;
    private final List<Entry> entries=new ArrayList<>();
    private final AttachmentPool.Lease lease;
    private final List<BackendResource> sources;
    private final List<Integer> colorFormats=new ArrayList<>();
    private final int[] colorReferences;
    private int depthReference=VK_ATTACHMENT_UNUSED;
    private int depthFormat=VK_FORMAT_UNDEFINED;
    final int width,height,x,y,areaWidth,areaHeight;
    long renderPass,framebuffer;

    PassAttachments(OverhaulDeviceBackend resources,RenderPassDescriptor descriptor,Consumer<Object> retain) {
        this(resources,descriptor,retain,null);
    }
    PassAttachments(OverhaulDeviceBackend resources,RenderPassDescriptor descriptor,Consumer<Object> retain,BackendResource generation) {
        this.resources=resources;
        int width=0,height=0;
        List<BackendResource> sources=new ArrayList<>();
        if(generation!=null)sources.add(generation);
        colorReferences=new int[descriptor.colorAttachments().size()];
        for(int slot=0;slot<colorReferences.length;slot++) {
            var attachment=descriptor.colorAttachments().get(slot);
            if(attachment==null){colorReferences[slot]=VK_ATTACHMENT_UNUSED;colorFormats.add(VK_FORMAT_UNDEFINED);continue;}
            retain.accept(attachment.textureView());GpuImageView view=resources.texture(attachment.textureView());
            sources.add((OverhaulTextureView)attachment.textureView());
            if(view.layerCount()!=1)throw new UnsupportedOperationException("Layered color attachments require an explicit layer view");
            float[] clear=attachment.clearValue().map(value->new float[]{value.x(),value.y(),value.z(),value.w()}).orElse(null);
            if(generation instanceof ShaderPackSceneGeneration scene&&view.image()==scene.color().image())clear=null;
            colorReferences[slot]=entries.size();entries.add(new Entry(view,false,clear,null));colorFormats.add(view.image().format().vkFormat());
            int w=view.image().width(view.baseMip()),h=view.image().height(view.baseMip());
            if(width!=0&&(width!=w||height!=h))throw new IllegalArgumentException("Mismatched render attachment extents");width=w;height=h;
        }
        var depth=descriptor.depthAttachment();
        if(depth!=null){
            retain.accept(depth.textureView());GpuImageView view=resources.texture(depth.textureView());
            sources.add((OverhaulTextureView)depth.textureView());
            if(view.layerCount()!=1)throw new UnsupportedOperationException("Layered depth attachments require an explicit layer view");
            depthReference=entries.size();depthFormat=view.image().format().vkFormat();
            entries.add(new Entry(view,true,null,depth.clearValue().isPresent()?depth.clearValue().getAsDouble():null));
            int w=view.image().width(view.baseMip()),h=view.image().height(view.baseMip());
            if(width!=0&&(width!=w||height!=h))throw new IllegalArgumentException("Mismatched depth attachment extent");width=w;height=h;
        }
        var area=descriptor.renderArea();
        if(width==0&&area!=null){width=area.x()+area.width();height=area.y()+area.height();}
        if(width<1||height<1)throw new IllegalArgumentException("A render pass needs attachments or a positive render area");
        this.width=width;this.height=height;x=area==null?0:area.x();y=area==null?0:area.y();areaWidth=area==null?width:area.width();areaHeight=area==null?height:area.height();
        if(x<0||y<0||areaWidth<1||areaHeight<1||(long)x+areaWidth>width||(long)y+areaHeight>height)throw new IllegalArgumentException("Render area outside attachment bounds");
        this.sources=List.copyOf(sources);lease=acquire(resources,this.sources);
    }
    PassAttachments(OverhaulDeviceBackend resources,List<GpuImageView> colors,GpuImageView depth,List<BackendResource> sources,int width,int height){
        this.resources=resources;this.sources=List.copyOf(sources);this.width=width;this.height=height;x=y=0;areaWidth=width;areaHeight=height;
        colorReferences=new int[colors.size()];
        for(int slot=0;slot<colors.size();slot++){
            var view=colors.get(slot);colorReferences[slot]=view==null?VK_ATTACHMENT_UNUSED:entries.size();colorFormats.add(view==null?VK_FORMAT_UNDEFINED:view.image().format().vkFormat());
            if(view!=null){checkExtent(view);entries.add(new Entry(view,false,null,null));}
        }
        if(depth!=null){checkExtent(depth);depthReference=entries.size();depthFormat=depth.image().format().vkFormat();entries.add(new Entry(depth,true,null,null));}
        lease=acquire(resources,this.sources);
    }
    private PassAttachments(OverhaulDeviceBackend resources,PassAttachments original,GpuImageView from,GpuImageView to,BackendResource generation,boolean load){
        this.resources=resources;width=original.width;height=original.height;x=original.x;y=original.y;areaWidth=original.areaWidth;areaHeight=original.areaHeight;
        colorReferences=original.colorReferences.clone();depthReference=original.depthReference;depthFormat=original.depthFormat;
        List<BackendResource> retained=new ArrayList<>(original.sources);if(generation!=null)retained.add(generation);sources=List.copyOf(retained);
        for(Entry entry:original.entries){var view=!entry.depth()&&same(entry.view(),from)?to:entry.view();checkExtent(view);
            entries.add(new Entry(view,entry.depth(),load?null:entry.color(),load?null:entry.clearDepth()));}
        for(int reference:colorReferences)colorFormats.add(reference==VK_ATTACHMENT_UNUSED?VK_FORMAT_UNDEFINED:entries.get(reference).view().image().format().vkFormat());
        lease=acquire(resources,sources);
    }
    PassAttachments loading(OverhaulDeviceBackend owner){return new PassAttachments(owner,this,null,null,null,true);}
    PassAttachments redirect(OverhaulDeviceBackend owner,GpuImageView from,GpuImageView to,BackendResource generation){return new PassAttachments(owner,this,from,to,generation,false);}
    boolean hasColor(GpuImageView view){for(Entry entry:entries)if(!entry.depth()&&same(entry.view(),view))return true;return false;}
    GpuImageView depthView(){return depthReference==VK_ATTACHMENT_UNUSED?null:entries.get(depthReference).view();}
    List<BackendResource> resourceSources(){return sources;}
    private static boolean same(GpuImageView left,GpuImageView right){return right!=null&&left.image()==right.image()&&left.baseMip()==right.baseMip()&&left.baseLayer()==right.baseLayer();}
    private void checkExtent(GpuImageView view){if(view.layerCount()!=1||view.mipCount()!=1||view.image().width(view.baseMip())!=width||view.image().height(view.baseMip())!=height)throw new IllegalArgumentException("Pack scene attachment extent differs from game pass");}
    private AttachmentPool.Lease acquire(OverhaulDeviceBackend resources,List<BackendResource> sources){
        List<AttachmentKey.Target> targets=new ArrayList<>();
        for(var entry:entries){var view=entry.view();targets.add(new AttachmentKey.Target(view.image(),view.baseMip(),view.baseLayer(),
                view.image().format().vkFormat(),view.image().format().aspectMask(),entry.depth(),entry.depth()?entry.clearDepth()!=null:entry.color()!=null));}
        List<Integer> colors=new ArrayList<>();for(int reference:colorReferences)colors.add(reference);
        return resources.attachments().acquire(new AttachmentKey(targets,colors,depthReference,width,height,1),sources);
    }
    List<Integer> colorFormats(){return List.copyOf(colorFormats);}
    int depthFormat(){return depthFormat;}
    boolean overlaps(GpuImageView sampled){
        for(Entry entry:entries){var target=entry.view();
            if(target.image()==sampled.image()&&target.baseMip()>=sampled.baseMip()&&target.baseMip()<sampled.baseMip()+sampled.mipCount()
                    &&target.baseLayer()>=sampled.baseLayer()&&target.baseLayer()<sampled.baseLayer()+sampled.layerCount())return true;
        }return false;
    }
    void begin(VkCommandBuffer command,List<GpuImage.LayoutChange> transitions) {
        create();
        for(Entry entry:entries){GpuImageView view=entry.view();transitions.add(view.image().recordTransition(command,view.baseMip(),1,view.baseLayer(),1,
                entry.depth()?VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL:VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL));}
        try(MemoryStack stack=MemoryStack.stackPush()){
            VkClearValue.Buffer clear=VkClearValue.calloc(entries.size(),stack);
            for(int index=0;index<entries.size();index++){Entry entry=entries.get(index);
                if(entry.depth())clear.get(index).depthStencil().depth(entry.clearDepth()==null?1:entry.clearDepth().floatValue()).stencil(0);
                else if(entry.color()!=null)for(int channel=0;channel<4;channel++)clear.get(index).color().float32(channel,entry.color()[channel]);
            }
            VkRenderPassBeginInfo begin=VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(renderPass).framebuffer(framebuffer).pClearValues(clear);
            begin.renderArea().offset().set(x,y);begin.renderArea().extent().set(areaWidth,areaHeight);
            vkCmdBeginRenderPass(command,begin,VK_SUBPASS_CONTENTS_INLINE);
            vkCmdSetViewport(command,0,VkViewport.calloc(1,stack).width(width).height(height).minDepth(0).maxDepth(1));
        }
    }
    private void create(){
        lease.create();renderPass=lease.renderPass();framebuffer=lease.framebuffer();
    }
    void retainUntil(dev.overhaul.vulkan.FrameRing.Submission completion) { lease.retainUntil(completion); }
    @Override public void close(){lease.close();framebuffer=renderPass=0;}
}
