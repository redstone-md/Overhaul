package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.*;
import static org.lwjgl.vulkan.VK10.*;

/** Allocator-owned optimal 2D image. Layout state is tracked independently for every mip/layer. */
public final class GpuImage implements AutoCloseable {
    private final GpuAllocator allocator;
    private final ImageFormat format;
    private final int width,height,mips,layers,usage,flags;
    private final long allocation;
    private long handle;
    private final int[] layouts;
    private final Set<GpuImageView> views=Collections.newSetFromMap(new IdentityHashMap<>());
    private FrameRing.Submission lastUse;
    GpuImage(GpuAllocator allocator,long handle,long allocation,ImageFormat format,int width,int height,int mips,int layers,int usage,int flags){
        this.allocator=allocator;this.handle=handle;this.allocation=allocation;this.format=format;
        this.width=width;this.height=height;this.mips=mips;this.layers=layers;this.usage=usage;this.flags=flags;layouts=new int[Math.multiplyExact(mips,layers)];
    }
    public VulkanContext context(){requireOpen();return allocator.context();}
    public long handle(){requireOpen();return handle;}
    public ImageFormat format(){return format;}
    public int width(int mip){checkMip(mip);return Math.max(1,width>>mip);}
    public int height(int mip){checkMip(mip);return Math.max(1,height>>mip);}
    public int mipLevels(){return mips;}public int layers(){return layers;}public int usage(){return usage;}
    public int layout(int mip,int layer){requireOpen();checkSubresource(mip,layer);return layouts[layer*mips+mip];}
    public GpuImageView view(int baseMip,int mipCount,int baseLayer,int layerCount){
        return view(baseMip,mipCount,baseLayer,layerCount,layerCount==1?VK_IMAGE_VIEW_TYPE_2D:VK_IMAGE_VIEW_TYPE_2D_ARRAY,format.aspectMask());
    }
    public GpuImageView cubeView(int baseMip,int mipCount,int baseLayer){return view(baseMip,mipCount,baseLayer,6,VK_IMAGE_VIEW_TYPE_CUBE,format.aspectMask());}
    public GpuImageView view(int baseMip,int mipCount,int baseLayer,int layerCount,int viewType,int aspectMask){
        requireOpen();checkRange(baseMip,mipCount,baseLayer,layerCount);
        if(aspectMask==0||(aspectMask&~format.aspectMask())!=0)throw new IllegalArgumentException("view aspect differs from format");
        if(viewType==VK_IMAGE_VIEW_TYPE_CUBE){if((flags&VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT)==0||layerCount!=6||baseLayer%6!=0)throw new IllegalArgumentException("invalid cube image view");}
        else if(viewType!=VK_IMAGE_VIEW_TYPE_2D_ARRAY&&(viewType!=VK_IMAGE_VIEW_TYPE_2D||layerCount!=1))throw new IllegalArgumentException("unsupported 2D image view type");
        GpuImageView view=new GpuImageView(this,baseMip,mipCount,baseLayer,layerCount,viewType,aspectMask);views.add(view);return view;
    }
    public void retainUntil(FrameRing.Submission completion){requireOpen();Objects.requireNonNull(completion,"completion");
        if(completion.owningContext()!=context())throw new IllegalArgumentException("submission belongs to another Vulkan context");lastUse=completion;}
    void awaitUsage(){if(lastUse!=null){lastUse.await();lastUse=null;}}
    GpuAllocator allocator(){return allocator;}
    void removeView(GpuImageView view){views.remove(view);}
    void requireUsage(int flag){requireOpen();if((usage&flag)!=flag)throw new IllegalArgumentException("image lacks required usage: "+flag);}
    void requireOpen(){allocator.requireOpen();if(handle==0)throw new IllegalStateException("GPU image closed");}
    private void checkMip(int mip){if(mip<0||mip>=mips)throw new IllegalArgumentException("invalid mip level");}
    void checkSubresource(int mip,int layer){checkMip(mip);if(layer<0||layer>=layers)throw new IllegalArgumentException("invalid array layer");}
    private void checkRange(int mip,int mipCount,int layer,int layerCount){
        if(mip<0||mipCount<1||mip>mips-mipCount||layer<0||layerCount<1||layer>layers-layerCount)
            throw new IllegalArgumentException("image view/subresource range out of bounds");
    }
    /** Proposed layout changes must commit after successful submission; close rolls them back on recording failure. */
    public LayoutChange recordTransition(VkCommandBuffer command,int baseMip,int mipCount,int baseLayer,int layerCount,int newLayout){
        requireOpen();checkRange(baseMip,mipCount,baseLayer,layerCount);validateLayout(newLayout);
        if(command.getDevice()!=context().device())throw new IllegalArgumentException("command belongs to another device");
        int[] old=new int[Math.multiplyExact(mipCount,layerCount)];int cursor=0;
        for(int layer=baseLayer;layer<baseLayer+layerCount;layer++)for(int mip=baseMip;mip<baseMip+mipCount;mip++){
            int before=layouts[layer*mips+mip];old[cursor++]=before;
            try(MemoryStack stack=MemoryStack.stackPush()){
                VkImageMemoryBarrier.Buffer barrier=VkImageMemoryBarrier.calloc(1,stack).sType$Default().image(handle)
                    .oldLayout(before).newLayout(newLayout).srcAccessMask(access(before)).dstAccessMask(access(newLayout))
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                barrier.subresourceRange().aspectMask(format.aspectMask()).baseMipLevel(mip).levelCount(1).baseArrayLayer(layer).layerCount(1);
                vkCmdPipelineBarrier(command,before==VK_IMAGE_LAYOUT_UNDEFINED?VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT:VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,0,null,null,barrier);
            }
        }
        for(int layer=baseLayer;layer<baseLayer+layerCount;layer++)for(int mip=baseMip;mip<baseMip+mipCount;mip++)layouts[layer*mips+mip]=newLayout;
        return new LayoutChange(baseMip,mipCount,baseLayer,layerCount,old);
    }
    private void validateLayout(int layout){
        switch(layout){
            case VK_IMAGE_LAYOUT_GENERAL -> { }
            case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> requireUsage(VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> requireUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> {if((usage&(VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT))==0)throw new IllegalArgumentException("image is not sampleable");}
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> requireUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
            case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL -> requireUsage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT);
            default -> throw new IllegalArgumentException("unsupported owned image layout: "+layout);
        }
    }
    private static int access(int layout){return switch(layout){
        case VK_IMAGE_LAYOUT_UNDEFINED -> 0;case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> VK_ACCESS_TRANSFER_READ_BIT;
        case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> VK_ACCESS_TRANSFER_WRITE_BIT;
        case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> VK_ACCESS_SHADER_READ_BIT;
        case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> VK_ACCESS_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL -> VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT|VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
        case VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL -> VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT|VK_ACCESS_SHADER_READ_BIT;
        default -> VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT;
    };}
    public final class LayoutChange implements AutoCloseable {
        private final int mip,count,layer,layerCount;private final int[] old;private boolean committed,closed;
        private LayoutChange(int mip,int count,int layer,int layerCount,int[] old){this.mip=mip;this.count=count;this.layer=layer;this.layerCount=layerCount;this.old=old;}
        public void commit(){requireOpen();if(closed)throw new IllegalStateException("layout transaction closed");committed=true;}
        @Override public void close(){if(closed)return;requireOpen();if(!committed){int cursor=0;for(int l=layer;l<layer+layerCount;l++)for(int m=mip;m<mip+count;m++)layouts[l*mips+m]=old[cursor++];}closed=true;}
    }
    @Override public void close(){
        if(handle==0)return;requireOpen();awaitUsage();for(GpuImageView view:views.toArray(new GpuImageView[0]))view.close();
        allocator.destroyImage(this,handle,allocation);handle=0;
    }
}
