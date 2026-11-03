package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.LongBuffer;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** Owns an optimal-tiled attachment and its view and device-local memory. */
final class AttachmentImage implements AutoCloseable {
    private final VulkanContext context;
    long image;
    long view;
    private long memory;

    AttachmentImage(VulkanContext context, int width, int height, int format, int usage, int aspect) {
        this.context = context;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pointer = stack.mallocLong(1);
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D)
                .format(format).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width, height, 1);
            check(vkCreateImage(context.device(), info, null, pointer), "vkCreateImage");
            image = pointer.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(context.device(), image, requirements);
            VkMemoryAllocateInfo allocate = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                .allocationSize(requirements.size()).memoryTypeIndex(memoryType(requirements.memoryTypeBits(), stack));
            check(vkAllocateMemory(context.device(), allocate, null, pointer), "vkAllocateMemory");
            memory = pointer.get(0);
            check(vkBindImageMemory(context.device(), image, memory, 0), "vkBindImageMemory");
            VkImageViewCreateInfo imageView = VkImageViewCreateInfo.calloc(stack).sType$Default()
                .image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
            imageView.subresourceRange().aspectMask(aspect).levelCount(1).layerCount(1);
            check(vkCreateImageView(context.device(), imageView, null, pointer), "vkCreateImageView");
            view = pointer.get(0);
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    private int memoryType(int bits, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties properties = VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(context.physicalDevice(), properties);
        for (int i = 0; i < properties.memoryTypeCount(); i++) {
            if ((bits & (1 << i)) != 0 && (properties.memoryTypes(i).propertyFlags() & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) return i;
        }
        throw new IllegalStateException("no device-local image memory type");
    }

    @Override public void close() {
        context.requireOwner();
        if (view != 0) vkDestroyImageView(context.device(), view, null);
        if (image != 0) vkDestroyImage(context.device(), image, null);
        if (memory != 0) vkFreeMemory(context.device(), memory, null);
        view = image = memory = 0;
    }
}
