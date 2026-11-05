package dev.overhaul.minecraft.backend;

import dev.overhaul.vulkan.VulkanStack;

import com.mojang.renderpearl.api.device.*;
import dev.overhaul.vulkan.VulkanContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import java.util.Set;
import static org.lwjgl.vulkan.VK10.*;

/** Reports enabled capabilities rather than the physical device's unused optional features. */
final class OverhaulDeviceInfo {
    private OverhaulDeviceInfo() {}
    static DeviceInfo read(VulkanContext context) {
        try (MemoryStack stack=VulkanStack.push()) {
            var properties=VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(context.physicalDevice(),properties);
            var limits=properties.limits();
            var caps=context.capabilities();
            var apiLimits=new DeviceLimits(caps.samplerAnisotropy()?(int)caps.maxSamplerAnisotropy():1,
                    Math.toIntExact(limits.minUniformBufferOffsetAlignment()),limits.maxImageDimension2D(),
                    Integer.MAX_VALUE,1,limits.maxColorAttachments(),context.multiDrawIndirect()?context.maxIndirectDrawCount():1);
            var features=new DeviceFeatures(caps.fillModeNonSolid(),caps.shaderDrawParameters(),
                    false,false,context.multiDrawIndirect(),true,context.indirectFirstInstance(),true);
            DeviceType type=switch(properties.deviceType()) {
                case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU->DeviceType.INTEGRATED;
                case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU->DeviceType.DISCRETE;
                case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU->DeviceType.VIRTUAL;
                case VK_PHYSICAL_DEVICE_TYPE_CPU->DeviceType.CPU;
                default->DeviceType.OTHER;
            };
            return new DeviceInfo(properties.deviceNameString(),"PCI vendor 0x"+Integer.toHexString(properties.vendorID()),
                    "Vulkan driver "+Integer.toUnsignedString(properties.driverVersion()),true,"Overhaul Vulkan",
                    limits.timestampPeriod(),apiLimits,features,Set.of(),
                    new HintsAndWorkarounds(false,false,false,false),type);
        }
    }
}
