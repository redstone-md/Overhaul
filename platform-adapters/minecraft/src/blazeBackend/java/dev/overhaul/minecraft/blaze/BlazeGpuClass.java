package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.VulkanContext;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_CPU;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;

/**
 * The kind of card the renderer runs on, read once from the device it opened. Each class carries defaults for the
 * settings that trade image for speed, which a launch argument or the settings file still overrides, and with
 * {@code gpuPreset=true} it also picks which upscaler and ray traced lighting turn on.
 */
public enum BlazeGpuClass {
    /** NVIDIA GeForce RTX: hardware ray queries and DLSS. */
    RTX(Map.of("fsr2Quality", "quality", "dlssQuality", "quality", "rtDistance", "64"), true, true),
    /** RTX 20 series and the RTX 30/40 entry cards: ray queries, at a shorter distance. */
    RTX_ENTRY(Map.of("fsr2Quality", "quality", "dlssQuality", "balanced", "rtDistance", "32"), true, true),
    /** AMD or Intel discrete cards with ray queries. */
    RAY_QUERY(Map.of("fsr2Quality", "quality", "rtDistance", "32"), true, true),
    /** GeForce GTX 16 series: Turing without RT cores. */
    GTX_16(Map.of("fsr2Quality", "balanced"), true, false),
    /** GeForce GTX 10 series and older: Pascal or Maxwell. */
    GTX_10(Map.of("fsr2Quality", "performance"), true, false),
    /** Integrated graphics sharing system memory. */
    INTEGRATED(Map.of("fsr2Quality", "performance"), true, false),
    /** Any other discrete card, or a device not yet opened. */
    OTHER(Map.of(), false, false);

    private static final int NVIDIA = 0x10DE;
    private static volatile BlazeGpuClass current = OTHER;

    private final Map<String, String> defaults;
    private final boolean upscale, rayTracing;

    BlazeGpuClass(Map<String, String> defaults, boolean upscale, boolean rayTracing) {
        this.defaults = defaults;
        this.upscale = upscale;
        this.rayTracing = rayTracing;
    }

    public static BlazeGpuClass current() {
        return current;
    }

    /** What this class sets a value to when neither a launch argument nor the file does. */
    String preset(String key, String fallback) {
        return defaults.getOrDefault(key, fallback);
    }

    /** With the preset on, whether this class turns the option on although the file leaves it off. */
    boolean recommends(BlazeOptimization option) {
        return switch (option) {
            case FSR2 -> upscale;
            case RAY_TRACING -> rayTracing;
            default -> false;
        };
    }

    /** Classifies the opened device and logs it with every feature that will stay off for want of hardware. */
    static void adopt(VulkanContext context) {
        current = classify(context.vendorId(), context.deviceType(), context.deviceName(), context.rayQuery());
        List<String> missing = new ArrayList<>();
        if (!context.storageFormats()) missing.add("FSR2 and ray tracing (no shaderStorageImageExtendedFormats)");
        if (!context.rayQuery()) missing.add(BlazeOptimization.RAY_TRACING.on() || BlazeOptimization.GPU_PRESET.on()
                ? "ray tracing (no ray query extensions)" : "ray tracing (not asked for)");
        if (current != RTX && current != RTX_ENTRY) missing.add("DLSS (not an NVIDIA RTX card)");
        if (!context.multiDrawIndirect()) missing.add("multi draw indirect (terrain draws one call per section)");
        if (!context.geometryShaders()) missing.add("geometry shaders (shader pack geometry programs are dropped)");
        if (!context.samplerAnisotropy()) missing.add("anisotropic filtering");
        LoggerFactory.getLogger(BlazeGpuClass.class).info("GPU {} is {} with {} MiB device memory{}{}", context.deviceName(), current,
                context.deviceLocalBytes() >> 20, BlazeOptimization.GPU_PRESET.on() ? ", preset on" : "",
                missing.isEmpty() ? "" : "; off: " + String.join(", ", missing));
    }

    static BlazeGpuClass classify(int vendor, int type, String name, boolean rayQuery) {
        if (type == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU || type == VK_PHYSICAL_DEVICE_TYPE_CPU) return INTEGRATED;
        String model = name == null ? "" : name.toUpperCase(Locale.ROOT);
        if (vendor == NVIDIA) {
            if (model.contains("RTX")) return entry(model) ? RTX_ENTRY : RTX;
            if (model.contains("GTX 16")) return GTX_16;
            if (model.contains("GTX") || model.matches(".*\\bGT \\d.*")) return GTX_10;
        }
        return rayQuery ? RAY_QUERY : OTHER;
    }

    /** Turing RTX cards and the 50/60 tiers of later generations trace fewer rays per frame. */
    private static boolean entry(String model) {
        return model.matches(".*RTX 20\\d0.*") || model.matches(".*RTX \\d0[56]0.*");
    }
}
