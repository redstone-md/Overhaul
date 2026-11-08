package dev.overhaul.minecraft.blaze.mixin;

import net.neoforged.neoforge.client.event.RenderLivingEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Alex's Mobs ends its pre-render hook with three pose tweaks (clinging, debilitating sting, ender flu), each gated on
 * the entity having that effect, and resolves each effect's registry holder on every frame to ask. An entity with no
 * active effects passes none of them, so the rest of the hook does nothing for it.
 */
@Pseudo
@Mixin(targets = "com.github.alexthe666.alexsmobs.client.event.ClientEvents", remap = false)
abstract class AlexsMobsEffectPoses121Mixin {
    @Inject(method = "onPreRenderEntity(Lnet/neoforged/neoforge/client/event/RenderLivingEvent$Pre;)V",
            at = @At(value = "INVOKE", target = "Lcom/github/alexthe666/alexsmobs/effect/EffectClinging;isFlippedUpsideDown(Lnet/minecraft/world/entity/LivingEntity;)Z"),
            cancellable = true, require = 0)
    private void overhaul$skipWithoutEffects(RenderLivingEvent.Pre<?, ?> event, CallbackInfo callback) {
        if (event.getEntity().getActiveEffectsMap().isEmpty()) callback.cancel();
    }
}
