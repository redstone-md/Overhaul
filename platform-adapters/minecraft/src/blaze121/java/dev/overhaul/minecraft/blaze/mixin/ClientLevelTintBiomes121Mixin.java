package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeTintBiomes;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block tinting averages the colour of every position in a square around the tinted block, reading the blended biome
 * of each; neighbouring blocks share most of that square. The biome of a position is kept per thread (see
 * {@link BlazeTintBiomes}) until the client loads, drops or rewrites any chunk, and the colour is still worked out
 * from it every time, so the tint is the game's to the bit.
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelTintBiomes121Mixin {
    @SuppressWarnings("unchecked")
    @WrapOperation(method="calculateBlockTint",require=0,at=@At(value="INVOKE",target="Lnet/minecraft/client/multiplayer/ClientLevel;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"))
    private Holder<Biome> overhaul$tintBiome(ClientLevel level, BlockPos position, Operation<Holder<Biome>> lookup){
        BlazeTintBiomes kept = BlazeTintBiomes.current();
        long key = position.asLong();
        int generation = BlazeTintBiomes.generation();
        Object biome = kept.find(level, key, generation);
        if (biome != null) return (Holder<Biome>) biome;
        Holder<Biome> found = lookup.call(level, position);
        kept.keep(key, generation, found);
        return found;
    }

    @Inject(method="onChunkLoaded",at=@At("HEAD"))
    private void overhaul$chunkLoaded(ChunkPos chunk, CallbackInfo callback){
        BlazeTintBiomes.invalidate();
    }

    @Inject(method="unload",require=0,at=@At("HEAD"))
    private void overhaul$chunkUnloaded(LevelChunk chunk, CallbackInfo callback){
        BlazeTintBiomes.invalidate();
    }

    @Inject(method="clearTintCaches",at=@At("HEAD"))
    private void overhaul$tintsCleared(CallbackInfo callback){
        BlazeTintBiomes.invalidate();
    }
}
