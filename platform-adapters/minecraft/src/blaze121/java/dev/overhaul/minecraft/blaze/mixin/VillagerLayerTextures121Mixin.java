package dev.overhaul.minecraft.blaze.mixin;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.Map;
import net.minecraft.client.renderer.entity.layers.VillagerProfessionLayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The villager layer builds and validates up to three texture paths per villager per frame, though a path depends
 * only on the layer, the kind and the type, profession or level key. Each layer keeps the paths it has built.
 */
@Mixin(VillagerProfessionLayer.class)
abstract class VillagerLayerTextures121Mixin {
    @Unique private final Map<String, Map<ResourceLocation, ResourceLocation>> overhaul$textures = new Object2ObjectOpenHashMap<>(4);

    @Inject(method = "getResourceLocation", at = @At("HEAD"), cancellable = true)
    private void overhaul$known(String kind, ResourceLocation key, CallbackInfoReturnable<ResourceLocation> callback) {
        Map<ResourceLocation, ResourceLocation> byKey = overhaul$textures.get(kind);
        ResourceLocation known = byKey == null ? null : byKey.get(key);
        if (known != null) callback.setReturnValue(known);
    }

    @Inject(method = "getResourceLocation", at = @At("RETURN"))
    private void overhaul$remember(String kind, ResourceLocation key, CallbackInfoReturnable<ResourceLocation> callback) {
        overhaul$textures.computeIfAbsent(kind, ignored -> new Object2ObjectOpenHashMap<>()).put(key, callback.getReturnValue());
    }
}
