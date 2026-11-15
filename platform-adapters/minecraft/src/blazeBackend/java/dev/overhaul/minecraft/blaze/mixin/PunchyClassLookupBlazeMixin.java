package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeClassCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Punchy looks Better Combat's client class up again every tick; the first outcome answers the rest. */
@Pseudo
@Mixin(targets = "punchy.compat.BetterCombatCompat", remap = false)
abstract class PunchyClassLookupBlazeMixin {
    @Redirect(method = "applyVanillaItemDisplay", at = @At(value = "INVOKE",
            target = "Ljava/lang/Class;forName(Ljava/lang/String;)Ljava/lang/Class;"), require = 0)
    private static Class<?> overhaul$knownClass(String name) throws ClassNotFoundException {
        Object outcome = BlazeClassCache.known(name);
        if (outcome == null) {
            // Merged into Punchy's class, so the lookup still runs from Punchy's own loader.
            try {
                outcome = Class.forName(name);
            } catch (ClassNotFoundException missing) {
                outcome = missing;
            }
            BlazeClassCache.remember(name, outcome);
        }
        return BlazeClassCache.answer(outcome);
    }
}
