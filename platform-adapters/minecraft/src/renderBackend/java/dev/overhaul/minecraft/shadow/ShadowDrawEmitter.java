package dev.overhaul.minecraft.shadow;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.OptionsRenderState;

/** Emits the game's original terrain/feature commands; backend capture substitutes only shadow programs/targets. */
public final class ShadowDrawEmitter implements AutoCloseable {
    private GpuSampler sampler;
    private int anisotropy;
    public void draw(boolean opaque,RenderTarget placeholder,ChunkSectionsToRender terrain,
            FeatureRenderDispatcher.PreparedFrame features,GpuTextureView atlas,OptionsRenderState options){
        int requested=options.textureFiltering==TextureFilteringMethod.ANISOTROPIC?options.maxAnisotropyValue:1;
        if(sampler==null||anisotropy!=requested){
            if(sampler!=null)sampler.close();sampler=null;
            sampler=RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR,FilterMode.LINEAR,requested,OptionalDouble.empty());anisotropy=requested;
        }
        try(RenderPass pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(()->"Overhaul shadow casters",
                placeholder.getColorTextureView(),Optional.empty(),placeholder.getDepthTextureView(),OptionalDouble.empty())){
            RenderSystem.bindDefaultUniforms(pass);
            if(opaque){terrain.renderGroup(ChunkSectionLayerGroup.OPAQUE,pass,sampler,atlas,false);features.executeSolid(pass);}
            else{
                features.executeTranslucent(pass);terrain.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT,pass,sampler,atlas,false);
                features.executeTranslucentAfterTerrain(pass);
            }
        }
    }
    @Override public void close(){if(sampler!=null){sampler.close();sampler=null;}}
}
