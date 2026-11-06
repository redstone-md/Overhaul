package dev.overhaul.vulkan;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackTextures;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/** One generation's immutable pack-owned inputs, uploaded before BEGIN and retained with the generation. */
final class PackTextureImages implements AutoCloseable {
    private final VulkanContext context;
    private final GpuAllocator allocator;
    private final PackTextures metadata;
    private final Map<PackTextures.Image,SampledTexture> textures=new IdentityHashMap<>();
    private boolean initialized;
    PackTextureImages(VulkanContext context,GpuAllocator allocator,PackTextures metadata){
        this.context=context;this.allocator=allocator;this.metadata=metadata;
    }
    void initialize(ImmediateCommands lane){
        if(initialized)return;
        try{
            for(var image:metadata.images().values())textures.put(image,new SampledTexture(context,allocator,lane,image));
            initialized=true;
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    boolean overrides(CompiledShaderPack.Program program,String sampler){return metadata.image(program,sampler)!=null||metadata.gameTexture(program,sampler)!=null;}
    GpuImageView view(CompiledShaderPack.Program program,String sampler){
        var texture=texture(program,sampler);return texture==null?null:texture.nativeView();
    }
    long sampler(CompiledShaderPack.Program program,String sampler){
        var texture=texture(program,sampler);if(texture==null)throw new IllegalArgumentException("No custom texture binding: "+sampler);return texture.sampler;
    }
    private SampledTexture texture(CompiledShaderPack.Program program,String sampler){
        var image=metadata.image(program,sampler);if(image==null)return null;
        if(program.code().descriptors().comparisonSampler(sampler))throw new UnsupportedShaderException("RGBA custom texture cannot serve a depth comparison sampler: "+sampler);
        if(!initialized)throw new IllegalStateException("Initialize pack textures before shader execution");
        return java.util.Objects.requireNonNull(textures.get(image),"Missing native pack image");
    }
    @Override public void close(){
        var reverse=new ArrayList<>(textures.values());java.util.Collections.reverse(reverse);for(var texture:reverse)texture.close();textures.clear();initialized=false;
    }
}
