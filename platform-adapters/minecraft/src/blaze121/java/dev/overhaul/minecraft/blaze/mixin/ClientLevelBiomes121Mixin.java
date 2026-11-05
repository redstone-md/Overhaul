package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeBiomeSamples;
import java.util.function.BooleanSupplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
abstract class ClientLevelBiomes121Mixin {
    /** Ambient particles choose by biome; the eight-corner blend only moves borders by a few blocks, which random particles cannot show. */
    @WrapOperation(method="doAnimateTick",at=@At(value="INVOKE",target="Lnet/minecraft/client/multiplayer/ClientLevel;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"))
    private Holder<Biome> overhaul$ambientBiome(ClientLevel level,BlockPos position,Operation<Holder<Biome>> blended){
        return level.getNoiseBiome(QuartPos.fromBlock(position.getX()),QuartPos.fromBlock(position.getY()),QuartPos.fromBlock(position.getZ()));
    }

    @WrapOperation(method="lambda$getSkyColor$12",require=0,at=@At(value="INVOKE",target="Lnet/minecraft/world/level/biome/BiomeManager;getNoiseBiomeAtQuart(III)Lnet/minecraft/core/Holder;"))
    private static Holder<Biome> overhaul$skyBiome(BiomeManager manager,int x,int y,int z,Operation<Holder<Biome>> lookup){
        return BlazeBiomeSamples.at(manager,x,y,z,lookup);
    }

    @Inject(method="tick",at=@At("HEAD"))
    private void overhaul$expireBiomeSamples(BooleanSupplier budget,CallbackInfo callback){
        BlazeBiomeSamples.nextTick();
    }
}
