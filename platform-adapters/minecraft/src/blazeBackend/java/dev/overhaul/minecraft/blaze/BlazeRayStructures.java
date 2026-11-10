package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.GpuAllocator;
import dev.overhaul.vulkan.GpuBuffer;
import dev.overhaul.vulkan.VulkanContext;
import dev.overhaul.vulkan.VulkanStack;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

/**
 * The device side of ray tracing: acceleration structures, each in a buffer of its own, and the scratch memory their
 * builds share. One scratch buffer serves a frame's builds side by side; it only grows, and a smaller one retires.
 */
final class BlazeRayStructures implements AutoCloseable {
    private static final int SCRATCH_USAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
    private static final int STORAGE_USAGE = VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

    private final BlazeRenderer owner;
    private final VulkanContext context;
    private final GpuAllocator allocator;
    private final long scratchAlignment;
    private final boolean compactVertices;
    private GpuBuffer scratch;

    BlazeRayStructures(BlazeRenderer owner) {
        this.owner = owner;
        context = owner.context();
        allocator = owner.allocator();
        try (MemoryStack stack = VulkanStack.push()) {
            var structures = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
            var properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(structures.address());
            VK11.vkGetPhysicalDeviceProperties2(context.physicalDevice(), properties);
            scratchAlignment = Math.max(1, structures.minAccelerationStructureScratchOffsetAlignment());
            var format = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(context.physicalDevice(), VK_FORMAT_R16G16B16A16_UNORM, format);
            compactVertices = (format.bufferFeatures() & VK_FORMAT_FEATURE_ACCELERATION_STRUCTURE_VERTEX_BUFFER_BIT_KHR) != 0;
        }
    }

    /** Whether the device builds from the 20-byte terrain vertices' 16-bit positions. */
    boolean compactVertices() {
        return compactVertices;
    }

    long align(long size) {
        return (size + scratchAlignment - 1) / scratchAlignment * scratchAlignment;
    }

    /**
     * @return the base device address of scratch memory holding at least {@code bytes}, aligned for builds. A buffer
     * too small for this frame retires once the frames already recorded with it finish.
     */
    long scratch(long bytes) {
        long needed = bytes + scratchAlignment;
        if (scratch == null || scratch.size() < needed) {
            if (scratch != null) owner.retire(scratch);
            scratch = null;
            scratch = allocator.deviceBuffer(Math.max(needed + needed / 2, 4L << 20), SCRATCH_USAGE);
        }
        return align(scratch.deviceAddress());
    }

    /** The sizes a build of these geometries needs; the build info must carry its type, flags and geometries. */
    VkAccelerationStructureBuildSizesInfoKHR sizes(VkAccelerationStructureBuildGeometryInfoKHR build, int[] primitives, MemoryStack stack) {
        var sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
        vkGetAccelerationStructureBuildSizesKHR(context.device(), VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, build, primitives, sizes);
        return sizes;
    }

    Structure create(int type, long bytes) {
        return new Structure(type, bytes);
    }

    /** One acceleration structure and the buffer it lives in. */
    final class Structure implements AutoCloseable {
        private final GpuBuffer buffer;
        private final long bytes;
        private long handle, address;

        private Structure(int type, long bytes) {
            this.bytes = bytes;
            buffer = allocator.deviceBuffer(Math.max(256, bytes), STORAGE_USAGE);
            try (MemoryStack stack = VulkanStack.push()) {
                var pointer = stack.mallocLong(1);
                var info = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default().buffer(buffer.handle()).offset(0).size(bytes).type(type);
                int result = vkCreateAccelerationStructureKHR(context.device(), info, null, pointer);
                if (result != VK_SUCCESS) {
                    buffer.close();
                    throw new IllegalStateException("vkCreateAccelerationStructureKHR failed: " + result);
                }
                handle = pointer.get(0);
                address = vkGetAccelerationStructureDeviceAddressKHR(context.device(),
                        VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(handle));
            }
        }

        long handle() {
            return handle;
        }

        long address() {
            return address;
        }

        long bytes() {
            return bytes;
        }

        @Override public void close() {
            if (handle != 0) vkDestroyAccelerationStructureKHR(context.device(), handle, null);
            handle = address = 0;
            buffer.close();
        }
    }

    /** Orders every earlier write, such as geometry uploads, before builds read it, and builds before rays read them. */
    static void barrier(VkCommandBuffer command, int sourceStage, int sourceAccess, int destinationStage, int destinationAccess) {
        try (MemoryStack stack = VulkanStack.push()) {
            var memory = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(sourceAccess).dstAccessMask(destinationAccess);
            vkCmdPipelineBarrier(command, sourceStage, destinationStage, 0, memory, null, null);
        }
    }

    @Override public void close() {
        if (scratch != null) scratch.close();
        scratch = null;
    }
}
