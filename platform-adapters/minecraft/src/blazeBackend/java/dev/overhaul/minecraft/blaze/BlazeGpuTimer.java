package dev.overhaul.minecraft.blaze;

import static org.lwjgl.vulkan.VK10.*;

import com.mojang.logging.LogUtils;
import dev.overhaul.vulkan.VulkanContext;
import dev.overhaul.vulkan.VulkanStack;
import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.slf4j.Logger;

/**
 * GPU time between named marks in the frame queue, read back a few frames late so nothing waits. Off unless
 * {@code overhaul.blaze.gpuStats} is set: a mark ends the open render pass, so it costs draw merging while on.
 */
final class BlazeGpuTimer implements AutoCloseable {
    static final boolean ENABLED=Boolean.getBoolean("overhaul.blaze.gpuStats");
    private static final Logger LOGGER=LogUtils.getLogger();
    private static final int FRAMES=5,MARKS=96,WINDOW=600;
    private final VulkanContext context;
    private final long[] pools=new long[FRAMES];
    private final String[][] labels=new String[FRAMES][MARKS];
    @SuppressWarnings("unchecked")
    private final java.util.Set<String>[] marked=new java.util.Set[FRAMES];
    private final int[] used=new int[FRAMES];
    private final double period;
    private final Map<String,Double> totals=new LinkedHashMap<>();
    private int frame,frames;
    private long lastEnd,lastMark;
    private final Map<String,Double> cpuTotals=new LinkedHashMap<>();

    BlazeGpuTimer(VulkanContext context){
        this.context=context;
        try(MemoryStack stack=VulkanStack.push()){
            var properties=VkPhysicalDeviceProperties.malloc(stack);vkGetPhysicalDeviceProperties(context.physicalDevice(),properties);
            period=properties.limits().timestampPeriod();
            var info=VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(MARKS);
            LongBuffer pointer=stack.mallocLong(1);
            for(int i=0;i<FRAMES;i++){
                if(vkCreateQueryPool(context.device(),info,null,pointer)!=VK_SUCCESS)throw new IllegalStateException("vkCreateQueryPool failed");
                pools[i]=pointer.get(0);
                marked[i]=new java.util.HashSet<>();
            }
        }
        dev.overhaul.vulkan.VulkanRenderGraph.observe((command,pass)->stamp(command,"pass:"+pass));
    }

    /** Records a timestamp once every earlier command in the queue has finished; a label seen this frame is ignored. */
    BlazeQueue.Operation mark(String label){
        if(!marked[frame].add(label))return null;
        long now=System.nanoTime();
        if(lastMark!=0)cpuTotals.merge(label,(now-lastMark)/1e6,Double::sum);
        lastMark=now;
        return new BlazeQueue.Operation(){
            @Override public void record(VkCommandBuffer command){stamp(command,label);}
            @Override public void close(){}
        };
    }

    /** Slots are taken as commands are recorded, so marks and shader pack passes stay in queue order. */
    private void stamp(VkCommandBuffer command,String label){
        int count=used[frame];
        if(count>=MARKS)return;
        labels[frame][count]=label;used[frame]=count+1;
        if(count==0)vkCmdResetQueryPool(command,pools[frame],0,MARKS);
        vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,pools[frame],count);
    }

    /** Ends this frame's marks and reads the frame that left the ring longest ago. */
    void endFrame(){
        frame=(frame+1)%FRAMES;
        if(used[frame]>1)read(frame);
        used[frame]=0;marked[frame].clear();
    }

    private void read(int slot){
        int count=used[slot];
        try(MemoryStack stack=VulkanStack.push()){
            LongBuffer values=stack.mallocLong(count);
            if(vkGetQueryPoolResults(context.device(),pools[slot],0,count,values,Long.BYTES,VK_QUERY_RESULT_64_BIT)!=VK_SUCCESS){lastEnd=0;return;}
            if(lastEnd!=0)totals.merge("between",(values.get(0)-lastEnd)*period/1e6,Double::sum);
            lastEnd=values.get(count-1);
            for(int i=1;i<count;i++)totals.merge(labels[slot][i],(values.get(i)-values.get(i-1))*period/1e6,Double::sum);
            totals.merge("total",(values.get(count-1)-values.get(0))*period/1e6,Double::sum);
        }
        if(++frames<WINDOW)return;
        StringBuilder line=new StringBuilder("Blaze GPU ms per frame:");
        for(var entry:totals.entrySet())line.append(' ').append(entry.getKey()).append(' ').append(Math.round(entry.getValue()/frames*100)/100d);
        LOGGER.info(line.toString());
        // Wall time on the render thread from the previous mark to each one, so a CPU phase reads next to its GPU phase.
        StringBuilder cpu=new StringBuilder("Blaze CPU ms per frame:");
        for(var entry:cpuTotals.entrySet())cpu.append(' ').append(entry.getKey()).append(' ').append(Math.round(entry.getValue()/frames*100)/100d);
        LOGGER.info(cpu.toString());
        totals.clear();cpuTotals.clear();frames=0;
    }

    @Override public void close(){dev.overhaul.vulkan.VulkanRenderGraph.observe(null);for(long pool:pools)if(pool!=0)vkDestroyQueryPool(context.device(),pool,null);}
}
