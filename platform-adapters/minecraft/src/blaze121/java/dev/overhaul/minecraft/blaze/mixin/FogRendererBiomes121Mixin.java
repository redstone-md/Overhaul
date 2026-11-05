package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeBiomeSamples;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FogRenderer.class)
abstract class FogRendererBiomes121Mixin {
    @WrapOperation(method="lambda$setupColor$0",require=0,at=@At(value="INVOKE",target="Lnet/minecraft/world/level/biome/BiomeManager;getNoiseBiomeAtQuart(III)Lnet/minecraft/core/Holder;"))
    private static Holder<Biome> overhaul$fogBiome(BiomeManager manager,int x,int y,int z,Operation<Holder<Biome>> lookup){
        return BlazeBiomeSamples.at(manager,x,y,z,lookup);
    }
}
