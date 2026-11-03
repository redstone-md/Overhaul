package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.LongBuffer;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** A bounded child view. Closing one view leaves its image and sibling views alive. */
public final class GpuImageView implements AutoCloseable {
    public record Components(int red,int green,int blue,int alpha){
        public static final Components IDENTITY=new Components(VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY);
        public Components{for(int component:new int[]{red,green,blue,alpha})if(component<VK_COMPONENT_SWIZZLE_IDENTITY||component>VK_COMPONENT_SWIZZLE_A)throw new IllegalArgumentException("Invalid image component swizzle");}
        public boolean identity(){return (red==VK_COMPONENT_SWIZZLE_IDENTITY||red==VK_COMPONENT_SWIZZLE_R)
                &&(green==VK_COMPONENT_SWIZZLE_IDENTITY||green==VK_COMPONENT_SWIZZLE_G)
                &&(blue==VK_COMPONENT_SWIZZLE_IDENTITY||blue==VK_COMPONENT_SWIZZLE_B)
                &&(alpha==VK_COMPONENT_SWIZZLE_IDENTITY||alpha==VK_COMPONENT_SWIZZLE_A);}
    }
    private final GpuImage image;
    private final int baseMip,mips,baseLayer,layers;
    private final Components components;
    private long handle;
    private FrameRing.Submission lastUse;
    GpuImageView(GpuImage image,int baseMip,int mips,int baseLayer,int layers,int viewType,int aspectMask,Components components){
        this.image=image;this.baseMip=baseMip;this.mips=mips;this.baseLayer=baseLayer;this.layers=layers;this.components=java.util.Objects.requireNonNull(components);
        if(!components.identity()&&!image.context().portability().imageViewFormatSwizzle())throw new UnsupportedOperationException("This device cannot remap image-view components");
        try(MemoryStack stack=MemoryStack.stackPush()){
            VkImageViewCreateInfo info=VkImageViewCreateInfo.calloc(stack).sType$Default().image(image.handle())
                .viewType(viewType).format(image.format().vkFormat());
            info.components().r(components.red()).g(components.green()).b(components.blue()).a(components.alpha());
            info.subresourceRange().aspectMask(aspectMask).baseMipLevel(baseMip).levelCount(mips).baseArrayLayer(baseLayer).layerCount(layers);
            LongBuffer pointer=stack.mallocLong(1);check(vkCreateImageView(image.context().device(),info,null,pointer),"vkCreateImageView");handle=pointer.get(0);
        }
    }
    public GpuImage image(){requireOpen();return image;}public long handle(){requireOpen();return handle;}
    public int baseMip(){return baseMip;}public int baseMipLevel(){return baseMip;}public int mipCount(){return mips;}
    public int baseLayer(){return baseLayer;}public int layerCount(){return layers;}
    public Components components(){return components;}
    /**
     * Opts this view into precise tracking. The caller must update this receipt for every native use
     * of this view; image-only operations such as blits remain tracked on the parent image separately.
     */
    public void retainUntil(FrameRing.Submission completion){
        requireOpen();java.util.Objects.requireNonNull(completion,"completion");
        if(completion.owningContext()!=image.context())throw new IllegalArgumentException("submission belongs to another Vulkan context");
        lastUse=completion;
    }
    private void requireOpen(){image.requireOpen();if(handle==0)throw new IllegalStateException("GPU image view closed");}
    @Override public void close(){
        if(handle==0)return;image.requireOpen();
        if(lastUse!=null)lastUse.await();else image.awaitUsage();
        vkDestroyImageView(image.context().device(),handle,null);handle=0;image.removeView(this);
    }
}
