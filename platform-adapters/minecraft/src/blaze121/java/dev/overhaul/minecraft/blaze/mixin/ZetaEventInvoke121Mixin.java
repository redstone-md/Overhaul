package dev.overhaul.minecraft.blaze.mixin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Zeta forwards every NeoForge event to its own listeners through a method handle typed for the listener's event
 * class, but calls it as taking a plain object, so each post on every living tick went through the generic type
 * check and adapter lookup. The handle is adapted to that call shape once, when the forwarding consumer is made; the
 * adapter casts the event exactly as the per-call conversion did.
 */
@Pseudo
@Mixin(targets = "org.violetmoon.zetaimplforge.event.ForgeEventsRemapper", remap = false)
abstract class ZetaEventInvoke121Mixin {
    @ModifyVariable(method = "createForgeConsumer(Ljava/lang/invoke/MethodHandle;Ljava/util/function/Function;Ljava/lang/Class;Ljava/lang/Class;)Ljava/util/function/Consumer;",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private MethodHandle overhaul$adaptOnce(MethodHandle consumer) {
        return consumer.asType(MethodType.methodType(void.class, Object.class));
    }
}