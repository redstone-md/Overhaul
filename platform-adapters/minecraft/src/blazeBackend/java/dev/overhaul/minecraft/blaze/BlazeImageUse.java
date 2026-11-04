package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.FrameRing;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.GpuImageView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.vulkan.VK10.*;

/** Layout proposals and precise view/image receipts for one queued render operation. */
final class BlazeImageUse implements AutoCloseable {
    private final List<GpuImage.LayoutChange> changes=new ArrayList<>();
    private final Set<GpuImageView> views=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<GpuImage> images=Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean accepted;
    void transition(VkCommandBuffer command,GpuImageView view,int layout){
        views.add(view);images.add(view.image());
        if(settled(view,layout))return;
        changes.add(view.image().recordTransition(command,view.baseMip(),view.mipCount(),view.baseLayer(),view.layerCount(),layout));
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
        for(GpuImageView view:views)view.retainUntil(submission);
        for(GpuImage image:images)image.retainUntil(submission);
    }
    void complete(){
        accepted=true;
        var iterator=changes.iterator();
        while(iterator.hasNext()){GpuImage.LayoutChange change=iterator.next();change.commit();change.close();iterator.remove();}
        changes.clear();
    }
    @Override public void close(){if(accepted)complete();else for(int index=changes.size()-1;index>=0;index--)changes.get(index).close();changes.clear();views.clear();images.clear();}
}
