package dev.overhaul.vulkan;

import java.util.List;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

/**
 * Hardware ray queries: acceleration structures built on the device and traced from compute shaders. Needs Vulkan
 * 1.2 for buffer device addresses and SPIR-V 1.4, and the acceleration structure and ray query extensions.
 */
final class RayQuerySupport {
    static final List<String> EXTENSIONS = List.of(VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
            VK_KHR_RAY_QUERY_EXTENSION_NAME, VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);

    private RayQuerySupport() {}

    /**
     * @param apiVersion the version the device runs at, the lesser of the instance's and the device's
     * @return the features to enable, chained in front of {@code next}, or 0 when the device cannot trace rays
     */
    static long chain(VkPhysicalDevice device, Set<String> extensions, int apiVersion, long next, MemoryStack stack) {
        if (Integer.compareUnsigned(apiVersion, VK_API_VERSION_1_2) < 0 || !extensions.containsAll(EXTENSIONS)) return 0;
        var rayQuery = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default();
        var structures = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default().pNext(rayQuery.address());
        var core = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default().pNext(structures.address());
        var features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(core.address());
        org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2(device, features);
        if (!core.bufferDeviceAddress() || !structures.accelerationStructure() || !rayQuery.rayQuery()) return 0;
        var enabledQuery = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default().rayQuery(true).pNext(next);
        var enabledStructures = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default()
                .accelerationStructure(true).pNext(enabledQuery.address());
        return VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default().bufferDeviceAddress(true)
                .pNext(enabledStructures.address()).address();
    }
}
