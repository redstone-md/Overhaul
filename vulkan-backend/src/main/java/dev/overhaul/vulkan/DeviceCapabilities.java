package dev.overhaul.vulkan;

/** Features actually enabled on the logical device, plus physical limits relevant to those features. */
public record DeviceCapabilities(boolean samplerAnisotropy, boolean fillModeNonSolid, boolean independentBlend,
        boolean shaderDrawParameters, float maxSamplerAnisotropy, int maxImageDimension2D,
        int maxImageArrayLayers, int maxColorAttachments,boolean logicOp) {
    public DeviceCapabilities(boolean samplerAnisotropy,boolean fillModeNonSolid,boolean independentBlend,
                              boolean shaderDrawParameters,float maxSamplerAnisotropy,int maxImageDimension2D,
                              int maxImageArrayLayers,int maxColorAttachments){
        this(samplerAnisotropy,fillModeNonSolid,independentBlend,shaderDrawParameters,maxSamplerAnisotropy,maxImageDimension2D,maxImageArrayLayers,maxColorAttachments,false);
    }
}
