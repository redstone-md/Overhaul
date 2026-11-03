package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.LongBuffer;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** A bounded child view. Closing one view leaves its image and sibling views alive. */
public final class GpuImageView implements AutoCloseable {
    private final GpuImage image;
    private final int baseMip,mips,baseLayer,layers;
    private long handle;
    GpuImageView(GpuImage image,int baseMip,int mips,int baseLayer,int layers,int viewType,int aspectMask){
        this.image=image;this.baseMip=baseMip;this.mips=mips;this.baseLayer=baseLayer;this.layers=layers;
        try(MemoryStack stack=MemoryStack.stackPush()){
            VkImageViewCreateInfo info=VkImageViewCreateInfo.calloc(stack).sType$Default().image(image.handle())
                .viewType(viewType).format(image.format().vkFormat());
            info.subresourceRange().aspectMask(aspectMask).baseMipLevel(baseMip).levelCount(mips).baseArrayLayer(baseLayer).layerCount(layers);
            LongBuffer pointer=stack.mallocLong(1);check(vkCreateImageView(image.context().device(),info,null,pointer),"vkCreateImageView");handle=pointer.get(0);
        }
    }
    public GpuImage image(){requireOpen();return image;}public long handle(){requireOpen();return handle;}
    public int baseMip(){return baseMip;}public int baseMipLevel(){return baseMip;}public int mipCount(){return mips;}
    public int baseLayer(){return baseLayer;}public int layerCount(){return layers;}
    private void requireOpen(){image.requireOpen();if(handle==0)throw new IllegalStateException("GPU image view closed");}
    @Override public void close(){if(handle==0)return;image.requireOpen();image.awaitUsage();vkDestroyImageView(image.context().device(),handle,null);handle=0;image.removeView(this);}
}
