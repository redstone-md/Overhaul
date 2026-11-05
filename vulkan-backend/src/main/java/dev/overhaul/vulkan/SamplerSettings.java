package dev.overhaul.vulkan;

import static org.lwjgl.vulkan.VK10.*;

/** Explicit sampler state; enum values map only to supported Vulkan sampler modes. */
public record SamplerSettings(Filter minFilter,Filter magFilter,Mipmap mipmap,Address u,Address v,Address w,
        float minLod,float maxLod,float anisotropy,Compare comparison,float lodBias) {
    public enum Filter { NEAREST(VK_FILTER_NEAREST),LINEAR(VK_FILTER_LINEAR);final int vk;Filter(int vk){this.vk=vk;} }
    public enum Mipmap { NEAREST(VK_SAMPLER_MIPMAP_MODE_NEAREST),LINEAR(VK_SAMPLER_MIPMAP_MODE_LINEAR);final int vk;Mipmap(int vk){this.vk=vk;} }
    public enum Address { REPEAT(VK_SAMPLER_ADDRESS_MODE_REPEAT),MIRROR(VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT),CLAMP(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE),BORDER(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER);final int vk;Address(int vk){this.vk=vk;} }
    public enum Compare { NONE(VK_COMPARE_OP_ALWAYS),NEVER(VK_COMPARE_OP_NEVER),LESS(VK_COMPARE_OP_LESS),EQUAL(VK_COMPARE_OP_EQUAL),LESS_OR_EQUAL(VK_COMPARE_OP_LESS_OR_EQUAL),GREATER(VK_COMPARE_OP_GREATER),NOT_EQUAL(VK_COMPARE_OP_NOT_EQUAL),GREATER_OR_EQUAL(VK_COMPARE_OP_GREATER_OR_EQUAL),ALWAYS(VK_COMPARE_OP_ALWAYS);final int vk;Compare(int vk){this.vk=vk;} }
    public SamplerSettings(Filter minFilter,Filter magFilter,Mipmap mipmap,Address u,Address v,Address w,float minLod,float maxLod,float anisotropy,Compare comparison){
        this(minFilter,magFilter,mipmap,u,v,w,minLod,maxLod,anisotropy,comparison,0);
    }
    public SamplerSettings {
        java.util.Objects.requireNonNull(minFilter);java.util.Objects.requireNonNull(magFilter);java.util.Objects.requireNonNull(mipmap);
        java.util.Objects.requireNonNull(u);java.util.Objects.requireNonNull(v);java.util.Objects.requireNonNull(w);java.util.Objects.requireNonNull(comparison);
        if(!Float.isFinite(minLod)||!Float.isFinite(maxLod)||minLod<0||maxLod<minLod||!Float.isFinite(anisotropy)||anisotropy<1||!Float.isFinite(lodBias))
            throw new IllegalArgumentException("invalid sampler LOD/anisotropy");
    }
    public static SamplerSettings nearest(){return new SamplerSettings(Filter.NEAREST,Filter.NEAREST,Mipmap.NEAREST,Address.CLAMP,Address.CLAMP,Address.CLAMP,0,0,1,Compare.NONE);}
}
