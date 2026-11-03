package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

/** Enabled subset features are explicit; a conformant device retains ordinary Vulkan guarantees. */
public record PortabilityCapabilities(boolean subset,boolean imageViewFormatSwizzle,boolean triangleFans,
        boolean pointPolygons,boolean constantAlphaColorBlendFactors,boolean mutableComparisonSamplers,
        boolean vertexAttributeAccessBeyondStride,int minVertexInputBindingStrideAlignment) {
    static PortabilityCapabilities conformant(){return new PortabilityCapabilities(false,true,true,true,true,true,true,1);}
    static PortabilityCapabilities capture(VkPhysicalDevice device,VkPhysicalDevicePortabilitySubsetFeaturesKHR features,MemoryStack stack){
        if(features==null)return conformant();
        var subset=VkPhysicalDevicePortabilitySubsetPropertiesKHR.calloc(stack).sType$Default();
        var properties=VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(subset.address());vkGetPhysicalDeviceProperties2(device,properties);
        int alignment=subset.minVertexInputBindingStrideAlignment();
        if(alignment<1||(alignment&(alignment-1))!=0)throw new IllegalStateException("Invalid portability vertex stride alignment");
        return new PortabilityCapabilities(true,features.imageViewFormatSwizzle(),features.triangleFans(),features.pointPolygons(),
                features.constantAlphaColorBlendFactors(),features.mutableComparisonSamplers(),features.vertexAttributeAccessBeyondStride(),alignment);
    }
    VkPhysicalDevicePortabilitySubsetFeaturesKHR enabled(MemoryStack stack){
        return VkPhysicalDevicePortabilitySubsetFeaturesKHR.calloc(stack).sType$Default()
                .imageViewFormatSwizzle(imageViewFormatSwizzle).triangleFans(triangleFans).pointPolygons(pointPolygons)
                .constantAlphaColorBlendFactors(constantAlphaColorBlendFactors).mutableComparisonSamplers(mutableComparisonSamplers)
                .vertexAttributeAccessBeyondStride(vertexAttributeAccessBeyondStride);
    }
    public void requireMutableComparisonSamplers(){
        if(!mutableComparisonSamplers)throw new UnsupportedOperationException("This device requires comparison samplers to be immutable descriptor-layout bindings");
    }
}
