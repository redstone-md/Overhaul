package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.VulkanContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.*;

/** Reports the selected native device and limits implemented by this frontend, without creating GL capabilities. */
public final class BlazeCapabilities {
    private BlazeCapabilities(){}
    public static int integer(VulkanContext context,int name,int textureUnits){
        if(textureUnits<1)throw new IllegalArgumentException("The frontend needs a positive texture unit capacity");
        try(MemoryStack stack=MemoryStack.stackPush()){
            var properties=VkPhysicalDeviceProperties.malloc(stack);vkGetPhysicalDeviceProperties(context.physicalDevice(),properties);var limits=properties.limits();
            return switch(name){
                case 3379->limits.maxImageDimension2D();
                case 34076->limits.maxImageDimensionCube();
                case 34921->limits.maxVertexInputAttributes();
                case 34930,35660->Math.min(textureUnits,Math.min(limits.maxPerStageDescriptorSamplers(),limits.maxPerStageDescriptorSampledImages()));
                case 35661->Math.min(textureUnits,Math.min(limits.maxDescriptorSetSamplers(),limits.maxDescriptorSetSampledImages()));
                case 34852,36063->1;
                case 36183->1;
                default->throw new UnsupportedOperationException("Unbound Blaze3D integer query: 0x"+Integer.toHexString(name));
            };
        }
    }
    public static String string(VulkanContext context,int name){
        if(name==7937)return context.deviceName();
        if(name==7939)return "";
        if(name==35724)return "SPIR-V";
        try(MemoryStack stack=MemoryStack.stackPush()){
            var properties=VkPhysicalDeviceProperties.malloc(stack);vkGetPhysicalDeviceProperties(context.physicalDevice(),properties);
            return switch(name){
                case 7936->"Vulkan vendor 0x"+Integer.toHexString(properties.vendorID());
                case 7938->{int version=properties.apiVersion();yield "Vulkan "+VK_VERSION_MAJOR(version)+"."+VK_VERSION_MINOR(version)+"."+VK_VERSION_PATCH(version);}
                default->throw new UnsupportedOperationException("Unbound Blaze3D string query: 0x"+Integer.toHexString(name));
            };
        }
    }
}
