package dev.overhaul.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.*;
import static org.lwjgl.vulkan.KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static dev.overhaul.vulkan.VulkanException.check;

/** One graphics queue, optionally paired with a presentation surface. Thread confined. */
public final class VulkanContext implements AutoCloseable {
    private static volatile int requestedApiVersion = VK_API_VERSION_1_1;
    private final Thread owner = Thread.currentThread();
    private VkInstance instance;
    private VkPhysicalDevice physicalDevice;
    private VkDevice device;
    private VkQueue queue;
    private long surface;
    private SurfaceProvider surfaceProvider;
    private Set<String> enabledInstanceExtensions;
    private Set<String> availableDeviceExtensions;
    private PortabilityCapabilities portability=PortabilityCapabilities.conformant();
    private boolean presentationEnabled;
    private int queueFamily;
    private int queueFlags;
    private String deviceName;
    private boolean multiDrawIndirect;
    private boolean indirectFirstInstance;
    private boolean vertexStores,fragmentStores,storageFormats,geometryShaders;
    private int maxIndirectDrawCount;
    private long maxStorageBufferRange;
    private DeviceCapabilities capabilities;
    private ValidationMonitor validation;
    private boolean closed;

    private VulkanContext() { }

    public static VulkanContext openHeadless() { return open(null, null, false); }
    public static VulkanContext openHeadless(boolean validation) { return open(null, null, validation); }
    public static boolean validationAvailable() { return ValidationMonitor.available(); }

    /** The caller owns the GLFW window and must keep it alive until this context closes. */
    public static VulkanContext openWindowed(long window) {
        return openWindowed(new GlfwSurfaceProvider(window), false);
    }

    public static VulkanContext openWindowed(long window, boolean validation) {
        return openWindowed(new GlfwSurfaceProvider(window), validation);
    }

    public static VulkanContext openWindowed(SurfaceProvider provider) { return openWindowed(provider, false); }

    public static VulkanContext openWindowed(SurfaceProvider provider, boolean validation) {
        return open(Objects.requireNonNull(provider, "surface provider"), null, validation);
    }

    /** Enables presentation before a caller creates its window. attachSurface validates the chosen queue later. */
    public static VulkanContext openForPresentation(List<String> requiredInstanceExtensions) {
        return openForPresentation(requiredInstanceExtensions, false);
    }

    public static VulkanContext openForPresentation(List<String> requiredInstanceExtensions, boolean validation) {
        return open(null, List.copyOf(Objects.requireNonNull(requiredInstanceExtensions, "required extensions")), validation);
    }

    /**
     * Asks contexts opened from now on for a later instance API version, for native libraries that need one. A loader
     * that offers less keeps what it offers; Vulkan 1.1 stays the default.
     */
    public static void requestApiVersion(int version) {
        if (Integer.compareUnsigned(version, VK_API_VERSION_1_1) > 0) requestedApiVersion = version;
    }

    private static int instanceApiVersion(MemoryStack stack) {
        int requested = requestedApiVersion;
        if (requested == VK_API_VERSION_1_1) return requested;
        IntBuffer offered = stack.ints(VK_API_VERSION_1_1);
        if (org.lwjgl.vulkan.VK11.vkEnumerateInstanceVersion(offered) != VK_SUCCESS) return VK_API_VERSION_1_1;
        return Integer.compareUnsigned(offered.get(0), requested) < 0 ? offered.get(0) : requested;
    }

    private static VulkanContext open(SurfaceProvider provider, List<String> requiredExtensions, boolean validate) {
        VulkanContext context = new VulkanContext();
        try {
            context.initialize(provider, requiredExtensions, validate);
            return context;
        } catch (RuntimeException | Error failure) {
            try { context.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void initialize(SurfaceProvider provider, List<String> requiredExtensions, boolean validate) {
        try (MemoryStack stack = VulkanStack.push()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                .pApplicationName(stack.UTF8("overhaul")).applicationVersion(1)
                .pEngineName(stack.UTF8("overhaul")).engineVersion(1).apiVersion(instanceApiVersion(stack));
            surfaceProvider = provider;
            presentationEnabled = provider != null || requiredExtensions != null;
            LinkedHashSet<String> names = new LinkedHashSet<>();
            boolean enumeratePortability=VulkanExtensions.instance(stack).contains(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
            if(enumeratePortability)names.add(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
            if (presentationEnabled) {
                List<String> required = provider == null ? requiredExtensions : Objects.requireNonNull(provider.requiredInstanceExtensions(), "required extensions");
                if (required.isEmpty()) throw new IllegalArgumentException("surface provider requires no instance extensions");
                for (String name : required) {
                    if (name == null || name.isBlank()) throw new IllegalArgumentException("invalid Vulkan extension name");
                    names.add(name);
                }
            }
            if (validate) {
                validation = new ValidationMonitor();
                names.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            }
            enabledInstanceExtensions = Set.copyOf(names);
            PointerBuffer extensions = names.isEmpty() ? null : stack.mallocPointer(names.size());
            if (extensions != null) { for (String name : names) extensions.put(stack.UTF8(name)); extensions.flip(); }
            VkInstanceCreateInfo create = VkInstanceCreateInfo.calloc(stack).sType$Default()
                .pApplicationInfo(app).ppEnabledExtensionNames(extensions).flags(enumeratePortability?VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR:0);
            if (validation != null) create.ppEnabledLayerNames(stack.pointers(stack.UTF8(ValidationMonitor.LAYER)))
                .pNext(validation.info(stack).address());
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vkCreateInstance(create, null, pointer), "vkCreateInstance");
            instance = new VkInstance(pointer.get(0), create);
            if (validation != null) validation.attach(instance);
            if (provider != null) {
                surface = provider.createSurface(instance);
                if (surface == 0) throw new IllegalStateException("surface provider returned a null Vulkan surface");
            }
            selectPhysicalDevice(stack);
            VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, stack)
                .sType$Default().queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1f));
            VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default()
                .pQueueCreateInfos(queues);
            VkPhysicalDeviceShaderDrawParametersFeatures draw = VkPhysicalDeviceShaderDrawParametersFeatures.calloc(stack).sType$Default();
            boolean subset=availableDeviceExtensions.contains(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME);
            var subsetFeatures=subset?VkPhysicalDevicePortabilitySubsetFeaturesKHR.calloc(stack).sType$Default():null;
            if(subsetFeatures!=null)draw.pNext(subsetFeatures.address());
            VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(draw.address());
            org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2(physicalDevice, features);
            portability=PortabilityCapabilities.capture(physicalDevice,subsetFeatures,stack);
            VkPhysicalDeviceFeatures supported = features.features();
            multiDrawIndirect = supported.multiDrawIndirect();
            indirectFirstInstance = supported.drawIndirectFirstInstance();
            vertexStores = supported.vertexPipelineStoresAndAtomics();fragmentStores = supported.fragmentStoresAndAtomics();storageFormats = supported.shaderStorageImageExtendedFormats();geometryShaders = supported.geometryShader();
            deviceInfo.pEnabledFeatures(VkPhysicalDeviceFeatures.calloc(stack)
                .multiDrawIndirect(multiDrawIndirect).drawIndirectFirstInstance(indirectFirstInstance)
                .samplerAnisotropy(supported.samplerAnisotropy()).fillModeNonSolid(supported.fillModeNonSolid())
                .independentBlend(supported.independentBlend()).logicOp(supported.logicOp())
                .vertexPipelineStoresAndAtomics(vertexStores).fragmentStoresAndAtomics(fragmentStores).shaderStorageImageExtendedFormats(storageFormats).geometryShader(geometryShaders));
            var enabledDraw=VkPhysicalDeviceShaderDrawParametersFeatures.calloc(stack).sType$Default().shaderDrawParameters(draw.shaderDrawParameters());
            if(subset)enabledDraw.pNext(portability.enabled(stack).address());
            deviceInfo.pNext(enabledDraw.address());
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(physicalDevice, properties);
            maxStorageBufferRange = Integer.toUnsignedLong(properties.limits().maxStorageBufferRange());
            capabilities = new DeviceCapabilities(supported.samplerAnisotropy(), supported.fillModeNonSolid(),
                supported.independentBlend(), draw.shaderDrawParameters(), supported.samplerAnisotropy()
                ? properties.limits().maxSamplerAnisotropy() : 1f, properties.limits().maxImageDimension2D(),
                properties.limits().maxImageArrayLayers(), properties.limits().maxColorAttachments(),supported.logicOp());
            var deviceNames=new LinkedHashSet<String>();if(presentationEnabled)deviceNames.add(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
            if(subset)deviceNames.add(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME);
            if(!deviceNames.isEmpty()){
                var enabled=stack.mallocPointer(deviceNames.size());for(String name:deviceNames)enabled.put(stack.UTF8(name));enabled.flip();deviceInfo.ppEnabledExtensionNames(enabled);
            }
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
            Set<String> extensions=VulkanExtensions.device(candidate,stack);
            if (presentationEnabled && !extensions.contains(VK_KHR_SWAPCHAIN_EXTENSION_NAME)) continue;
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
                    if (supported.get(0) == 0) continue;
                }
                int score = (extensions.contains(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME)?0:4)
                        +(properties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 2 : 1);
                if (score > bestScore) {
                    bestScore = score;
                    physicalDevice = candidate;
                    availableDeviceExtensions=extensions;
                    queueFamily = j;
                    queueFlags = families.get(j).queueFlags();
                    deviceName = properties.deviceNameString();
                    // Vulkan exposes uint32_t; Java draw counts and RenderPearl limits are signed ints.
                    maxIndirectDrawCount = (int)Math.min(Integer.MAX_VALUE,
                            Integer.toUnsignedLong(properties.limits().maxDrawIndirectCount()));
                }
                break;
            }
        }
        if (physicalDevice == null) throw new IllegalStateException("No Vulkan 1.1 graphics device supports this target");
    }

    /** Attaches exactly one surface without recreating the device or taking ownership of its window. */
    public void attachSurface(SurfaceProvider provider) {
        requireOwner();
        Objects.requireNonNull(provider, "surface provider");
        if (!presentationEnabled) throw new IllegalStateException("device was not opened for presentation");
        if (surface != 0) throw new IllegalStateException("a Vulkan surface is already attached");
        List<String> required = Objects.requireNonNull(provider.requiredInstanceExtensions(), "required extensions");
        if (required.isEmpty() || !enabledInstanceExtensions.containsAll(required))
            throw new IllegalArgumentException("surface needs instance extensions that were not enabled before device creation");
        long candidate = provider.createSurface(instance);
        if (candidate == 0) throw new IllegalStateException("surface provider returned a null Vulkan surface");
        try (MemoryStack stack = VulkanStack.push()) {
            IntBuffer supported = stack.ints(0);
            check(vkGetPhysicalDeviceSurfaceSupportKHR(physicalDevice, queueFamily, candidate, supported), "deferred surface support");
            if (supported.get(0) == 0) throw new IllegalStateException("selected graphics queue cannot present to this surface");
            surfaceProvider = provider;
            surface = candidate;
        } catch (RuntimeException | Error failure) {
            try { provider.destroySurface(instance, candidate); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
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
    public boolean supportsCompute() { requireOwner(); return (queueFlags & VK_QUEUE_COMPUTE_BIT) != 0; }
    public long surface() { requireOwner(); return surface; }
    public String deviceName() { requireOwner(); return deviceName; }
    public boolean multiDrawIndirect() { requireOwner(); return multiDrawIndirect; }
    public boolean indirectFirstInstance() { requireOwner(); return indirectFirstInstance; }
    /** Vertex and geometry stages may write storage images, as shader-pack voxelization does. */
    public boolean vertexStores() { requireOwner(); return vertexStores; }
    public boolean fragmentStores() { requireOwner(); return fragmentStores; }
    /** Storage images may use the small and packed formats, such as r8ui and r11f_g11f_b10f. */
    public boolean storageFormats() { requireOwner(); return storageFormats; }
    /** Shader packs' geometry programs need the stage; without it their draws are dropped. */
    public boolean geometryShaders() { requireOwner(); return geometryShaders; }
    public int maxIndirectDrawCount() { requireOwner(); return maxIndirectDrawCount; }
    /** Largest storage buffer range one descriptor may bind. */
    public long maxStorageBufferRange() { requireOwner(); return maxStorageBufferRange; }
    public DeviceCapabilities capabilities() { requireOwner(); return capabilities; }
    public PortabilityCapabilities portability(){requireOwner();return portability;}
    public boolean samplerAnisotropy(){return capabilities().samplerAnisotropy();}
    public boolean fillModeNonSolid(){return capabilities().fillModeNonSolid();}
    public boolean independentBlend(){return capabilities().independentBlend();}
    public boolean logicOp(){return capabilities().logicOp();}
    public boolean shaderDrawParameters(){return capabilities().shaderDrawParameters();}
    public boolean vertexAttributeDivisor(){requireOwner();return false;}
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
        if (surface != 0) surfaceProvider.destroySurface(instance, surface);
        if (validation != null) validation.close();
        if (instance != null) vkDestroyInstance(instance, null);
        closed = true;
    }
}
