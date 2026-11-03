package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.OptionalLong;

import static dev.overhaul.vulkan.VulkanException.check;
import static org.lwjgl.vulkan.VK10.*;

/** Owner-thread timestamp queries with real GPU reset/write/read and explicit readiness. */
public final class OverhaulQueryPool extends BackendResource implements GpuQueryPool {
    private final int count;
    private final float nanosecondsPerTick;
    private final long timestampMask;
    private final boolean[] prepared,written;
    private long handle;

    public OverhaulQueryPool(OverhaulDeviceBackend owner,int count) {
        super(owner);
        if (count<1) { close(); throw new IllegalArgumentException("query count must be positive"); }
        this.count=count;
        prepared=new boolean[count]; written=new boolean[count];
        try (MemoryStack stack=MemoryStack.stackPush()) {
            IntBuffer familyCount=stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(owner.context().physicalDevice(),familyCount,null);
            VkQueueFamilyProperties.Buffer families=VkQueueFamilyProperties.malloc(familyCount.get(0),stack);
            vkGetPhysicalDeviceQueueFamilyProperties(owner.context().physicalDevice(),familyCount,families);
            int bits=families.get(owner.context().queueFamily()).timestampValidBits();
            if (bits==0) throw new UnsupportedOperationException("Selected Vulkan queue does not support timestamps");
            timestampMask=bits==64 ? -1L : (1L<<bits)-1;
            VkPhysicalDeviceProperties properties=VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(owner.context().physicalDevice(),properties);
            nanosecondsPerTick=properties.limits().timestampPeriod();
            LongBuffer result=stack.mallocLong(1);
            VkQueryPoolCreateInfo create=VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(count);
            check(vkCreateQueryPool(owner.context().device(),create,null,result),"vkCreateQueryPool");
            handle=result.get(0);
        } catch (RuntimeException | Error failure) {
            close(); throw failure;
        }
    }

    /** Called while recording pass state, before a native render pass has begun. */
    public void prepare(int index) {
        requireIndex(index);
        owner().commands().submit(command->vkCmdResetQueryPool(command,handle,index,1));
        prepared[index]=true; written[index]=false;
    }

    /** Records only the timestamp; its reset has already completed outside the render pass. */
    public void write(VkCommandBuffer command,int index) {
        requireNativeAlive();
        if (index<0 || index>=count) throw new IndexOutOfBoundsException("query index");
        if (!prepared[index]) throw new IllegalStateException("Timestamp query was not prepared outside the render pass");
        vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,handle,index);
        prepared[index]=false; written[index]=true;
    }

    @Override public int size() { requirePoolOpen(); return count; }

    @Override public OptionalLong getValue(int index) {
        requireIndex(index);
        if (!written[index]) return OptionalLong.empty();
        try (MemoryStack stack=MemoryStack.stackPush()) {
            LongBuffer result=stack.callocLong(2);
            int status=vkGetQueryPoolResults(owner().context().device(),handle,index,1,result,16,
                    VK_QUERY_RESULT_64_BIT|VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
            if (status==VK_NOT_READY) return OptionalLong.empty();
            check(status,"vkGetQueryPoolResults");
            if (result.get(1)==0) return OptionalLong.empty();
            long ticks=result.get(0)&timestampMask;
            return OptionalLong.of((long)(ticks*(double)nanosecondsPerTick));
        }
    }

    @Override public OptionalLong[] getValues(int first,int queryCount) {
        requirePoolOpen();
        if (first<0 || queryCount<0 || (long)first+queryCount>count) throw new IndexOutOfBoundsException("query range");
        OptionalLong[] values=new OptionalLong[queryCount];
        for (int i=0;i<queryCount;i++) values[i]=getValue(first+i);
        return values;
    }

    /** Approximate CPU/GPU clock offset using the midpoint of a completed GPU timestamp submission. */
    public static long calibrationOffset(OverhaulDeviceBackend owner) {
        try (OverhaulQueryPool pool=new OverhaulQueryPool(owner,1)) {
            pool.prepare(0);
            long before=System.nanoTime();
            owner.commands().submit(command->pool.write(command,0));
            long after=System.nanoTime();
            long gpu=pool.getValue(0).orElseThrow(()->new IllegalStateException("Completed calibration timestamp is unavailable"));
            return before+(after-before)/2-gpu;
        }
    }

    private void requireIndex(int index) {
        requirePoolOpen();
        if (index<0 || index>=count) throw new IndexOutOfBoundsException("query index");
    }

    private void requirePoolOpen() {
        requireOpen();
        if (handle==0) throw new IllegalStateException("query pool is closed");
    }

    @Override protected void destroyNative() {
        if (handle==0) return;
        owner().context().waitIdle();
        vkDestroyQueryPool(owner().context().device(),handle,null); handle=0;
    }
}
