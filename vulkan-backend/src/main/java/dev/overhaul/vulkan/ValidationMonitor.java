package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** Keeps native callback storage alive through device destruction and records driver-thread diagnostics. */
final class ValidationMonitor implements AutoCloseable {
    static final String LAYER = "VK_LAYER_KHRONOS_validation";
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private final VkDebugUtilsMessengerCallbackEXT callback;
    private VkInstance instance;
    private long messenger;

    ValidationMonitor() {
        if (!available()) throw new IllegalStateException("Khronos validation layer is not installed");
        callback = VkDebugUtilsMessengerCallbackEXT.create((severity, type, data, user) -> {
            if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0)
                errors.add(VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString());
            return VK_FALSE;
        });
    }

    static boolean available() {
        try (MemoryStack stack = VulkanStack.push()) {
            IntBuffer count = stack.ints(0);
            check(vkEnumerateInstanceLayerProperties(count, null), "validation layer count");
            VkLayerProperties.Buffer layers = VkLayerProperties.malloc(count.get(0), stack);
            check(vkEnumerateInstanceLayerProperties(count, layers), "validation layers");
            for (VkLayerProperties layer : layers) if (layer.layerNameString().equals(LAYER)) return true;
            return false;
        }
    }

    VkDebugUtilsMessengerCreateInfoEXT info(MemoryStack stack) {
        return VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default()
            .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT)
            .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT).pfnUserCallback(callback);
    }

    void attach(VkInstance instance) {
        this.instance = instance;
        try (MemoryStack stack = VulkanStack.push()) {
            LongBuffer pointer = stack.mallocLong(1);
            check(vkCreateDebugUtilsMessengerEXT(instance, info(stack), null, pointer), "vkCreateDebugUtilsMessengerEXT");
            messenger = pointer.get(0);
        }
    }

    List<String> errors() { synchronized (errors) { return List.copyOf(errors); } }

    @Override public void close() {
        if (messenger != 0) vkDestroyDebugUtilsMessengerEXT(instance, messenger, null);
        messenger = 0;
        callback.close();
    }
}
