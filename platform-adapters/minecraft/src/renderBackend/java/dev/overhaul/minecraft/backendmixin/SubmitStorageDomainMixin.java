package dev.overhaul.minecraft.backendmixin;

import dev.overhaul.minecraft.model.SubmitDomainCollectionAccess;
import dev.overhaul.minecraft.model.SubmitDomainSource;
import dev.overhaul.minecraft.model.SubmitDomainState;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every ordered collection shares its storage owner's nested submission scope. */
@Mixin(SubmitNodeStorage.class)
public abstract class SubmitStorageDomainMixin implements SubmitDomainSource {
    @Unique private final SubmitDomainState overhaul$domains = new SubmitDomainState();
    @Override public SubmitDomainState overhaul$submitDomains() { return overhaul$domains; }

    @Inject(method = "order(I)Lnet/minecraft/client/renderer/SubmitNodeCollection;", at = @At("RETURN"), require = 1)
    private void overhaul$shareDomain(CallbackInfoReturnable<SubmitNodeCollection> callback) {
        ((SubmitDomainCollectionAccess) callback.getReturnValue()).overhaul$bindSubmitDomains(overhaul$domains);
    }
}
