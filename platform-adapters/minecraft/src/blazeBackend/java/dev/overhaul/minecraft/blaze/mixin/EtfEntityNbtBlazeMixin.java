package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeEntityNbt;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;
import java.util.function.Supplier;

/** Entity Texture Features' per-draw NBT save served from {@link BlazeEntityNbt} for the rest of the tick. */
@Pseudo
@Mixin(targets = "traben.entity_texture_features.features.ETFRenderContext", remap = false)
abstract class EtfEntityNbtBlazeMixin {
    @Inject(method = "cacheEntityNBTForFrame", at = @At("HEAD"), cancellable = true, require = 0)
    private static void overhaul$tickNbt(UUID id, Supplier<CompoundTag> save, CallbackInfoReturnable<CompoundTag> callback) {
        CompoundTag tag = BlazeEntityNbt.get(id, save);
        if (tag != null) callback.setReturnValue(tag);
    }
}
