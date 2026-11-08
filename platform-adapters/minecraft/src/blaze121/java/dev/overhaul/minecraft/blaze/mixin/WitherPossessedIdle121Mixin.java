package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeIdleState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wither Reincarnated ticks the possession state of every mob each tick, and reading that state created it on every
 * mob, and saved it with the mob. A mob that was never possessed has no state, or a saved one still at the default,
 * which reads as unpossessed with no cooldown and no possessor: ticking it changes nothing and sends nothing, so the
 * handler now returns at once for such a mob. A possession changes the state first, and from then on it ticks as
 * before.
 */
@Pseudo
@Mixin(targets = "com.alexander.witherreincarnated.events.HandlePossessedMobsEvent", remap = false)
abstract class WitherPossessedIdle121Mixin {
    @Unique private static final ResourceLocation OVERHAUL$POSSESSED = ResourceLocation.fromNamespaceAndPath("witherreincarnated", "possessed");
    @Unique private static AttachmentType<?> overhaul$possessed;

    @Inject(method = "possessedTick(Lnet/neoforged/neoforge/event/tick/EntityTickEvent$Pre;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private static void overhaul$skipUnpossessed(EntityTickEvent.Pre event, CallbackInfo callback) {
        AttachmentType<?> possessed = overhaul$possessed;
        if (possessed == null) {
            possessed = NeoForgeRegistries.ATTACHMENT_TYPES.get(OVERHAUL$POSSESSED);
            if (possessed == null) return;
            overhaul$possessed = possessed;
        }
        Entity entity = event.getEntity();
        if (!entity.hasData(possessed) || entity.getData(possessed) instanceof BlazeIdleState state && state.overhaul$idle()) callback.cancel();
    }
}
