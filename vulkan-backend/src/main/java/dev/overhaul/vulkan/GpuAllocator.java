package dev.overhaul.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;
import static dev.overhaul.vulkan.VulkanException.check;

/** VMA allocator with deterministic child ownership; no native pointer escapes allocations. */
public final class GpuAllocator implements AutoCloseable {
    private final VulkanContext context;
    private final Set<GpuBuffer> buffers = Collections.newSetFromMap(new IdentityHashMap<>());
    private long handle;

    public GpuAllocator(VulkanContext context) {
        this.context = context;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VmaVulkanFunctions functions = VmaVulkanFunctions.calloc(stack).set(context.instance(), context.device());
            VmaAllocatorCreateInfo info = VmaAllocatorCreateInfo.calloc(stack)
                .instance(context.instance()).physicalDevice(context.physicalDevice()).device(context.device())
                .vulkanApiVersion(VK_API_VERSION_1_1).pVulkanFunctions(functions);
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vmaCreateAllocator(info, pointer), "vmaCreateAllocator");
            handle = pointer.get(0);
        }
    }

    public GpuBuffer hostBuffer(long size, int usage) {
        return createBuffer(size, usage, true);
    }

    public GpuBuffer deviceBuffer(long size, int usage) {
        return createBuffer(size, usage, false);
    }

    private GpuBuffer createBuffer(long size, int usage, boolean host) {
        requireOpen();
        if (size < 1 || size > Integer.MAX_VALUE) throw new IllegalArgumentException("buffer size out of range");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default()
                .size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocation = VmaAllocationCreateInfo.calloc(stack)
                .usage(host ? VMA_MEMORY_USAGE_AUTO_PREFER_HOST : VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            if (host) allocation.flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT);
            LongBuffer buffer = stack.mallocLong(1);
            PointerBuffer memory = stack.mallocPointer(1);
            check(vmaCreateBuffer(handle, info, allocation, buffer, memory, null), "vmaCreateBuffer");
            GpuBuffer result = new GpuBuffer(this, buffer.get(0), memory.get(0), (int) size, host);
            buffers.add(result);
            return result;
        }
    }

    ByteBuffer map(long allocation, int size) {
        requireOpen();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vmaMapMemory(handle, allocation, pointer), "vmaMapMemory");
            return memByteBuffer(pointer.get(0), size);
        }
    }

    void unmap(long allocation) { vmaUnmapMemory(handle, allocation); }
    void flush(long allocation, long size) { vmaFlushAllocation(handle, allocation, 0, size); }
    void invalidate(long allocation, long size) { vmaInvalidateAllocation(handle, allocation, 0, size); }

    void destroy(GpuBuffer buffer, long bufferHandle, long allocation) {
        requireOpen();
        vmaDestroyBuffer(handle, bufferHandle, allocation);
        buffers.remove(buffer);
    }

    void requireOpen() {
        context.requireOwner();
        if (handle == 0) throw new IllegalStateException("GPU allocator is closed");
    }

    @Override public void close() {
        if (handle == 0) return;
        context.requireOwner();
        context.waitIdle();
        for (GpuBuffer buffer : buffers.toArray(new GpuBuffer[0])) buffer.close();
        vmaDestroyAllocator(handle);
        handle = 0;
    }
}
