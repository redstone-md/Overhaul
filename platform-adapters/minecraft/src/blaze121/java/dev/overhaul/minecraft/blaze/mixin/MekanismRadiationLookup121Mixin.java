package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.capabilities.EntityCapability;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mekanism's entity radiation tick looked up the radiation capability of every living entity each tick, which runs the
 * provider and builds a fresh wrapper, only to decay and update a dose that an entity without radiation data does not
 * have. For such an entity the lookup now answers no capability, which the tick already handles by skipping both
 * steps; the occasional check of the surrounding radiation level, which can give the entity its first dose, still
 * runs as before.
 */
@Pseudo
@Mixin(targets = "mekanism.common.lib.radiation.PlayerExposure", remap = false)
abstract class MekanismRadiationLookup121Mixin {
    @Unique private static final ResourceLocation OVERHAUL$RADIATION = ResourceLocation.fromNamespaceAndPath("mekanism", "radiation");
    @Unique private static AttachmentType<?> overhaul$radiation;

    @Redirect(method = "updateEntityRadiation(Lnet/minecraft/world/entity/LivingEntity;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getCapability(Lnet/neoforged/neoforge/capabilities/EntityCapability;)Ljava/lang/Object;"),
            require = 0)
    private static Object overhaul$skipUndosed(LivingEntity entity, EntityCapability<Object, Void> capability) {
        AttachmentType<?> radiation = overhaul$radiation;
        if (radiation == null) {
            radiation = NeoForgeRegistries.ATTACHMENT_TYPES.get(OVERHAUL$RADIATION);
            if (radiation == null) return entity.getCapability(capability);
            overhaul$radiation = radiation;
        }
        return entity.hasData(radiation) ? entity.getCapability(capability) : null;
    }
}
