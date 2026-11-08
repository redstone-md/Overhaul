package dev.overhaul.minecraft.blaze.mixin;

import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puzzles Lib finds a mod's config by its class, and every lookup built the "no config for" message of the null check
 * before making it, though the config is nearly always there. Mods built on it read their config this way in entity
 * and tick events, so the message was built for every entity every tick. A config that is found is now returned
 * before the message is made; a missing one still fails the same way.
 */
@Pseudo
@Mixin(targets = "fuzs.puzzleslib.impl.config.ConfigHolderImpl", remap = false)
abstract class PuzzlesConfigHolder121Mixin {
    @Shadow private Map<Class<?>, Object> configsByClass;

    @Inject(method = "getHolder", at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$foundHolder(Class<?> type, CallbackInfoReturnable<Object> callback) {
        Map<Class<?>, Object> configs = configsByClass;
        Object holder = configs == null ? null : configs.get(type);
        if (holder != null) callback.setReturnValue(holder);
    }
}
