package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.textures.*;
import dev.overhaul.vulkan.SamplerSettings;
import java.util.OptionalDouble;
import static org.lwjgl.vulkan.VK10.VK_LOD_CLAMP_NONE;

/** Immutable API sampler state and its allocator-owned native sampler. */
public final class OverhaulSampler extends BackendResource implements GpuSampler {
    private final AddressMode u,v;
    private final FilterMode min,mag;
    private final int anisotropy;
    private final OptionalDouble maxLod;
    private dev.overhaul.vulkan.GpuSampler sampler;

    OverhaulSampler(OverhaulDeviceBackend owner,AddressMode u,AddressMode v,FilterMode min,
            FilterMode mag,int anisotropy,OptionalDouble maxLod) {
        super(owner);
        this.u=u; this.v=v; this.min=min; this.mag=mag; this.anisotropy=anisotropy; this.maxLod=maxLod;
        try {
            sampler=owner.allocator().createSampler(new SamplerSettings(filter(min),filter(mag),
                    SamplerSettings.Mipmap.LINEAR,address(u),address(v),SamplerSettings.Address.CLAMP,
                    0,maxLod.isPresent()?(float)maxLod.getAsDouble():VK_LOD_CLAMP_NONE,anisotropy,SamplerSettings.Compare.NONE));
        } catch (RuntimeException|Error failure) { close(); throw failure; }
    }
    private static SamplerSettings.Filter filter(FilterMode mode) {
        return switch(mode) { case NEAREST->SamplerSettings.Filter.NEAREST; case LINEAR->SamplerSettings.Filter.LINEAR; };
    }
    private static SamplerSettings.Address address(AddressMode mode) {
        return switch(mode) { case REPEAT->SamplerSettings.Address.REPEAT; case CLAMP_TO_EDGE->SamplerSettings.Address.CLAMP; };
    }
    public dev.overhaul.vulkan.GpuSampler nativeSampler() { requireNativeAlive(); return sampler; }
    @Override public AddressMode getAddressModeU() { return u; }
    @Override public AddressMode getAddressModeV() { return v; }
    @Override public FilterMode getMinFilter() { return min; }
    @Override public FilterMode getMagFilter() { return mag; }
    @Override public int getMaxAnisotropy() { return anisotropy; }
    @Override public OptionalDouble getMaxLod() { return maxLod; }
    @Override protected void destroyNative() { if (sampler!=null) sampler.close(); }
}
