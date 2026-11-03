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

/** Layout proposals and precise view/image receipts for one queued render operation. */
final class BlazeImageUse implements AutoCloseable {
    private final List<GpuImage.LayoutChange> changes=new ArrayList<>();
    private final Set<GpuImageView> views=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<GpuImage> images=Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean accepted;
    void transition(VkCommandBuffer command,GpuImageView view,int layout){
        views.add(view);images.add(view.image());
        changes.add(view.image().recordTransition(command,view.baseMip(),view.mipCount(),view.baseLayer(),view.layerCount(),layout));
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
