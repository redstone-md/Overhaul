package dev.overhaul.vulkan;

/** Features actually enabled on the logical device, plus physical limits relevant to those features. */
public record DeviceCapabilities(boolean samplerAnisotropy, boolean fillModeNonSolid, boolean independentBlend,
        boolean shaderDrawParameters, float maxSamplerAnisotropy, int maxImageDimension2D,
        int maxImageArrayLayers, int maxColorAttachments) { }
