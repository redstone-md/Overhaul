package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.FrameRing;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.GpuImageView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.vulkan.VK10.*;

/** Layout proposals and precise view/image receipts for one queued render operation. */
final class BlazeImageUse implements AutoCloseable {
    private final List<GpuImage.LayoutChange> changes=new ArrayList<>();
    private static final GpuImageView[] NO_VIEWS=new GpuImageView[0];
    private static final GpuImage[] NO_IMAGES=new GpuImage[0];
    /** A pass touches a handful of these, so a short identity scan is cheaper than a hash table per pass. */
    private GpuImageView[] views=NO_VIEWS;
    private GpuImage[] images=NO_IMAGES;
    private int viewCount,imageCount;
    private boolean accepted;
    void transition(VkCommandBuffer command,GpuImageView view,int layout){
        remember(view);
        if(settled(view,layout))return;
        changes.add(view.image().recordTransition(command,view.baseMip(),view.mipCount(),view.baseLayer(),view.layerCount(),layout));
    }
    private void remember(GpuImageView view){
        for(int index=viewCount-1;index>=0;index--)if(views[index]==view)return;
        if(viewCount==views.length)views=Arrays.copyOf(views,Math.max(4,viewCount*2));
        views[viewCount++]=view;
        GpuImage image=view.image();
        for(int index=imageCount-1;index>=0;index--)if(images[index]==image)return;
        if(imageCount==images.length)images=Arrays.copyOf(images,Math.max(4,imageCount*2));
        images[imageCount++]=image;
    }
    /**
     * Whether a transition would have nothing to order. Reads need no barrier after reads, because every write first
     * leaves this layout, and attachment writes are ordered by the render pass's external dependencies.
     */
    static boolean settled(GpuImageView view,int layout){
        if(layout!=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL&&layout!=VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL&&layout!=VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)return false;
        GpuImage image=view.image();
        for(int layer=view.baseLayer();layer<view.baseLayer()+view.layerCount();layer++)
            for(int mip=view.baseMip();mip<view.baseMip()+view.mipCount();mip++)if(image.layout(mip,layer)!=layout)return false;
        return true;
    }
    void submitted(FrameRing.Submission submission){
        complete();
        for(int index=0;index<viewCount;index++)views[index].retainUntil(submission);
        for(int index=0;index<imageCount;index++)images[index].retainUntil(submission);
    }
    void complete(){
        accepted=true;
        var iterator=changes.iterator();
        while(iterator.hasNext()){GpuImage.LayoutChange change=iterator.next();change.commit();change.close();iterator.remove();}
        changes.clear();
    }
    @Override public void close(){if(accepted)complete();else for(int index=changes.size()-1;index>=0;index--)changes.get(index).close();changes.clear();views=NO_VIEWS;images=NO_IMAGES;viewCount=imageCount=0;}
}
