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
    private final Set<GpuImage> images = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<GpuSampler> samplers = Collections.newSetFromMap(new IdentityHashMap<>());
    private long handle;

    public GpuAllocator(VulkanContext context) {
        this.context = context;
        try (MemoryStack stack = VulkanStack.push()) {
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

    public VulkanContext context(){requireOpen();return context;}
    public GpuImage createImage(ImageFormat format,int width,int height,int mips,int layers,int usage){
        return createImage(format,width,height,mips,layers,usage,0);
    }
    public GpuImage createImage(ImageFormat format,int width,int height,int mips,int layers,int usage,int flags){
        return createImage(format,width,height,1,false,mips,layers,usage,flags);
    }
    /** A single-mip 3D color image, as shader packs declare for voxel and light volumes. */
    public GpuImage createVolume(ImageFormat format,int width,int height,int depth,int usage){
        if(depth<1)throw new IllegalArgumentException("invalid volume depth");
        if(format.aspectMask()!=VK_IMAGE_ASPECT_COLOR_BIT)throw new IllegalArgumentException("volumes hold color formats");
        return createImage(format,width,height,depth,true,1,1,usage,0);
    }
    private GpuImage createImage(ImageFormat format,int width,int height,int depth,boolean volume,int mips,int layers,int usage,int flags){
        requireOpen();java.util.Objects.requireNonNull(format,"format");
        if(width<1||height<1||mips<1||mips>32-Integer.numberOfLeadingZeros(Math.max(width,height))||layers<1||usage==0)
            throw new IllegalArgumentException("invalid image dimensions/mips/layers/usage");
        if((flags&~VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT)!=0||(flags&VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT)!=0&&(width!=height||layers<6||layers%6!=0))
            throw new IllegalArgumentException("invalid cube image flags/dimensions");
        boolean depthStencil=format.aspectMask()!=VK_IMAGE_ASPECT_COLOR_BIT;
        if(depthStencil&&(usage&VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)!=0||!depthStencil&&(usage&VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)!=0)
            throw new IllegalArgumentException("attachment usage differs from image format");
        try(MemoryStack stack=VulkanStack.push()){
            VkImageFormatProperties support=VkImageFormatProperties.malloc(stack);
            check(vkGetPhysicalDeviceImageFormatProperties(context.physicalDevice(),format.vkFormat(),volume?VK_IMAGE_TYPE_3D:VK_IMAGE_TYPE_2D,VK_IMAGE_TILING_OPTIMAL,usage,flags,support),"image format support");
            if(width>support.maxExtent().width()||height>support.maxExtent().height()||depth>support.maxExtent().depth()||mips>support.maxMipLevels()||layers>support.maxArrayLayers())
                throw new IllegalArgumentException("image dimensions exceed device limits");
            VkImageCreateInfo info=VkImageCreateInfo.calloc(stack).sType$Default().flags(flags).imageType(volume?VK_IMAGE_TYPE_3D:VK_IMAGE_TYPE_2D)
                .format(format.vkFormat()).mipLevels(mips).arrayLayers(layers).samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width,height,depth);
            LongBuffer image=stack.mallocLong(1);PointerBuffer allocation=stack.mallocPointer(1);
            check(vmaCreateImage(handle,info,VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE),image,allocation,null),"vmaCreateImage");
            GpuImage result=new GpuImage(this,image.get(0),allocation.get(0),format,width,height,depth,volume,mips,layers,usage,flags);
            images.add(result);return result;
        }
    }
    public GpuSampler createSampler(SamplerSettings settings){requireOpen();GpuSampler result=new GpuSampler(this,settings);samplers.add(result);return result;}
    void destroyImage(GpuImage image,long imageHandle,long allocation){requireOpen();vmaDestroyImage(handle,imageHandle,allocation);images.remove(image);}
    void removeSampler(GpuSampler sampler){samplers.remove(sampler);}

    private GpuBuffer createBuffer(long size, int usage, boolean host) {
        requireOpen();
        if (size < 1 || size > Integer.MAX_VALUE) throw new IllegalArgumentException("buffer size out of range");
        try (MemoryStack stack = VulkanStack.push()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default()
                .size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocation = VmaAllocationCreateInfo.calloc(stack)
                .usage(host ? VMA_MEMORY_USAGE_AUTO_PREFER_HOST : VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            if (host) allocation.flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT);
            LongBuffer buffer = stack.mallocLong(1);
            PointerBuffer memory = stack.mallocPointer(1);
            check(vmaCreateBuffer(handle, info, allocation, buffer, memory, null), "vmaCreateBuffer");
            GpuBuffer result = new GpuBuffer(this, buffer.get(0), memory.get(0), (int) size, host,usage);
            buffers.add(result);
            return result;
        }
    }

    ByteBuffer map(long allocation, int size) {
        requireOpen();
        try (MemoryStack stack = VulkanStack.push()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vmaMapMemory(handle, allocation, pointer), "vmaMapMemory");
            return memByteBuffer(pointer.get(0), size);
        }
    }

    void unmap(long allocation) { vmaUnmapMemory(handle, allocation); }
    // VMA expands unaligned ranges to nonCoherentAtomSize; offsets stay local to this allocation.
    void flush(long allocation, long offset, long size) { vmaFlushAllocation(handle, allocation, offset, size); }
    void invalidate(long allocation, long size) { invalidate(allocation, 0, size); }
    void invalidate(long allocation, long offset, long size) { vmaInvalidateAllocation(handle, allocation, offset, size); }

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
        for(GpuSampler sampler:samplers.toArray(new GpuSampler[0]))sampler.close();
        for(GpuImage image:images.toArray(new GpuImage[0]))image.close();
        for (GpuBuffer buffer : buffers.toArray(new GpuBuffer[0])) buffer.close();
        vmaDestroyAllocator(handle);
        handle = 0;
    }
}
