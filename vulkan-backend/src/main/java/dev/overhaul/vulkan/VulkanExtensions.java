package dev.overhaul.vulkan;

import java.util.LinkedHashSet;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import static dev.overhaul.vulkan.VulkanException.check;
import static org.lwjgl.vulkan.VK10.*;

/** Copies extension names before the enumeration stack is released. */
final class VulkanExtensions {
    private VulkanExtensions(){}
    static Set<String> instance(MemoryStack stack){return enumerate(null,stack);}
    static Set<String> device(VkPhysicalDevice device,MemoryStack stack){return enumerate(java.util.Objects.requireNonNull(device),stack);}
    private static Set<String> enumerate(VkPhysicalDevice device,MemoryStack stack){
        var count=stack.ints(0);check(device==null?vkEnumerateInstanceExtensionProperties((String)null,count,null):
                vkEnumerateDeviceExtensionProperties(device,(String)null,count,null),"Vulkan extension count");
        var properties=VkExtensionProperties.malloc(count.get(0),stack);
        check(device==null?vkEnumerateInstanceExtensionProperties((String)null,count,properties):
                vkEnumerateDeviceExtensionProperties(device,(String)null,count,properties),"Vulkan extensions");
        var names=new LinkedHashSet<String>();for(var property:properties)names.add(property.extensionNameString());return Set.copyOf(names);
    }
}
