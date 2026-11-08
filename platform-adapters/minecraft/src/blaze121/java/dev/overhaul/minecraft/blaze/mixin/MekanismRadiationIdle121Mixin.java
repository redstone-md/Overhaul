package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mekanism decays and applies the radiation dose of every living entity each tick. An entity that has never held a
 * dose has no radiation data, which reads as the baseline dose: decaying it keeps it at the baseline, and the baseline
 * is below the least dose that harms. Both steps still created the data on every mob, so they now return at once for
 * an entity without it. Any dose received is stored as data first, and is then decayed and applied as before.
 */
@Pseudo
@Mixin(targets = "mekanism.common.lib.radiation.capability.RadiationEntity", remap = false)
abstract class MekanismRadiationIdle121Mixin {
    @Unique private static final ResourceLocation OVERHAUL$RADIATION = ResourceLocation.fromNamespaceAndPath("mekanism", "radiation");
    @Unique private static AttachmentType<?> overhaul$radiation;

    @Shadow @Final private LivingEntity entity;

    @Inject(method = {"update()V", "decay()V"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$skipBaseline(CallbackInfo callback) {
        AttachmentType<?> radiation = overhaul$radiation;
        if (radiation == null) {
            radiation = NeoForgeRegistries.ATTACHMENT_TYPES.get(OVERHAUL$RADIATION);
            if (radiation == null) return;
            overhaul$radiation = radiation;
        }
        if (!entity.hasData(radiation)) callback.cancel();
    }
}
