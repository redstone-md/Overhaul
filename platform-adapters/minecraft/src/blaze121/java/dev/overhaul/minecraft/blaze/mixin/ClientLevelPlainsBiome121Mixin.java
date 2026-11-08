package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Where the client holds no chunk, its biome lookup falls back to plains, resolving the key through the registry
 * (and the loader's alias table) on every call. Biome tinting meets this at the edge of loaded terrain for every
 * neighbour it blends, so the level keeps the holder it got the first time: a level's registries never change.
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelPlainsBiome121Mixin {
    @Unique private Holder.Reference<?> overhaul$plains;
    @Unique private ResourceKey<?> overhaul$plainsKey;

    @WrapOperation(method="getUncachedNoiseBiome",require=0,at=@At(value="INVOKE",target="Lnet/minecraft/core/Registry;getHolderOrThrow(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Holder$Reference;"))
    private Holder.Reference<?> overhaul$plains(Registry<?> registry, ResourceKey<?> key, Operation<Holder.Reference<?>> resolve){
        Holder.Reference<?> kept = overhaul$plains;
        if (kept != null && key == overhaul$plainsKey) return kept;
        kept = resolve.call(registry, key);
        overhaul$plainsKey = key;
        overhaul$plains = kept;
        return kept;
    }
}
