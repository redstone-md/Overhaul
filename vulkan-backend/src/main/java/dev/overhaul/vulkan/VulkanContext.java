package dev.overhaul.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static dev.overhaul.vulkan.VulkanException.check;

/** One graphics queue, optionally paired with a presentation surface. Thread confined. */
public final class VulkanContext implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private VkInstance instance;
    private VkPhysicalDevice physicalDevice;
    private VkDevice device;
    private VkQueue queue;
    private long surface;
    private int queueFamily;
    private String deviceName;
    private ValidationMonitor validation;
    private boolean closed;

    private VulkanContext() { }

    public static VulkanContext openHeadless() { return open(0, false); }
    public static VulkanContext openHeadless(boolean validation) { return open(0, validation); }
    public static boolean validationAvailable() { return ValidationMonitor.available(); }

    /** The caller owns the GLFW window and must keep it alive until this context closes. */
    public static VulkanContext openWindowed(long window) {
        if (window == 0) throw new IllegalArgumentException("window is null");
        return open(window, false);
    }

    public static VulkanContext openWindowed(long window, boolean validation) {
        if (window == 0) throw new IllegalArgumentException("window is null");
        return open(window, validation);
    }

    private static VulkanContext open(long window, boolean validate) {
        VulkanContext context = new VulkanContext();
        try {
            context.initialize(window, validate);
            return context;
        } catch (RuntimeException | Error failure) {
            context.close();
            throw failure;
        }
    }

    private void initialize(long window, boolean validate) {
        if (MemoryStack.stackGet().getSize() < 256 * 1024)
            throw new IllegalStateException("Vulkan device enumeration needs a larger LWJGL stack; set "
                + "-Dorg.lwjgl.system.stackSize=1024 before initializing LWJGL");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                .pApplicationName(stack.UTF8("overhaul")).applicationVersion(1)
                .pEngineName(stack.UTF8("overhaul")).engineVersion(1).apiVersion(VK_API_VERSION_1_1);
            PointerBuffer extensions = window == 0 ? null : glfwGetRequiredInstanceExtensions();
            if (window != 0 && extensions == null) throw new IllegalStateException("GLFW has no Vulkan surface extensions");
            if (validate) {
                validation = new ValidationMonitor();
                PointerBuffer expanded = stack.mallocPointer((extensions == null ? 0 : extensions.remaining()) + 1);
                if (extensions != null) expanded.put(extensions.duplicate());
                extensions = expanded.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)).flip();
            }
            VkInstanceCreateInfo create = VkInstanceCreateInfo.calloc(stack).sType$Default()
                .pApplicationInfo(app).ppEnabledExtensionNames(extensions);
            if (validation != null) create.ppEnabledLayerNames(stack.pointers(stack.UTF8(ValidationMonitor.LAYER)))
                .pNext(validation.info(stack).address());
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vkCreateInstance(create, null, pointer), "vkCreateInstance");
            instance = new VkInstance(pointer.get(0), create);
            if (validation != null) validation.attach(instance);
            if (window != 0) {
                LongBuffer handle = stack.mallocLong(1);
                check(glfwCreateWindowSurface(instance, window, null, handle), "glfwCreateWindowSurface");
                surface = handle.get(0);
            }
            selectPhysicalDevice(stack);
            VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, stack)
                .sType$Default().queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1f));
            VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default()
                .pQueueCreateInfos(queues);
            if (surface != 0) deviceInfo.ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME)));
            check(vkCreateDevice(physicalDevice, deviceInfo, null, pointer), "vkCreateDevice");
            device = new VkDevice(pointer.get(0), physicalDevice, deviceInfo);
            vkGetDeviceQueue(device, queueFamily, 0, pointer);
            queue = new VkQueue(pointer.get(0), device);
        }
    }

    private void selectPhysicalDevice(MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices");
        PointerBuffer physical = stack.mallocPointer(count.get(0));
        check(vkEnumeratePhysicalDevices(instance, count, physical), "vkEnumeratePhysicalDevices");
        int bestScore = -1;
        for (int i = 0; i < physical.remaining(); i++) {
            VkPhysicalDevice candidate = new VkPhysicalDevice(physical.get(i), instance);
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(candidate, properties);
            if (Integer.compareUnsigned(properties.apiVersion(), VK_API_VERSION_1_1) < 0) continue;
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
            for (int j = 0; j < families.remaining(); j++) {
                if ((families.get(j).queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0) continue;
                if (surface != 0) {
                    IntBuffer supported = stack.ints(0);
                    check(vkGetPhysicalDeviceSurfaceSupportKHR(candidate, j, surface, supported), "surface support");
                    if (supported.get(0) == 0 || !hasSwapchain(candidate, stack)) continue;
                }
                int score = properties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 2 : 1;
                if (score > bestScore) {
                    bestScore = score;
                    physicalDevice = candidate;
                    queueFamily = j;
                    deviceName = properties.deviceNameString();
                }
                break;
            }
        }
        if (physicalDevice == null) throw new IllegalStateException("No Vulkan 1.1 graphics device supports this target");
    }

    private static boolean hasSwapchain(VkPhysicalDevice physical, MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        check(vkEnumerateDeviceExtensionProperties(physical, (String) null, count, null), "device extensions");
        VkExtensionProperties.Buffer extensions = VkExtensionProperties.malloc(count.get(0), stack);
        check(vkEnumerateDeviceExtensionProperties(physical, (String) null, count, extensions), "device extensions");
        for (VkExtensionProperties extension : extensions) {
            if (extension.extensionNameString().equals(VK_KHR_SWAPCHAIN_EXTENSION_NAME)) return true;
        }
        return false;
    }

    void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Vulkan context belongs to another thread");
        if (closed) throw new IllegalStateException("Vulkan context is closed");
    }

    public VkInstance instance() { requireOwner(); return instance; }
    public VkPhysicalDevice physicalDevice() { requireOwner(); return physicalDevice; }
    public VkDevice device() { requireOwner(); return device; }
    public VkQueue queue() { requireOwner(); return queue; }
    public int queueFamily() { requireOwner(); return queueFamily; }
    public long surface() { requireOwner(); return surface; }
    public String deviceName() { requireOwner(); return deviceName; }
    public void waitIdle() { requireOwner(); check(vkDeviceWaitIdle(device), "vkDeviceWaitIdle"); }
    public List<String> validationErrors() { return validation == null ? List.of() : validation.errors(); }
    public void assertValidationClean() {
        List<String> errors = validationErrors();
        if (!errors.isEmpty()) throw new IllegalStateException("Vulkan validation errors:\n" + String.join("\n", errors));
    }

    @Override public void close() {
        if (closed) return;
        requireOwner();
        if (device != null) {
            vkDeviceWaitIdle(device);
            vkDestroyDevice(device, null);
        }
        if (surface != 0) vkDestroySurfaceKHR(instance, surface, null);
        if (validation != null) validation.close();
        if (instance != null) vkDestroyInstance(instance, null);
        closed = true;
    }
}
