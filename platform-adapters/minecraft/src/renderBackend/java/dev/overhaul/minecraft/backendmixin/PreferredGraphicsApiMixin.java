package dev.overhaul.minecraft.backendmixin;

import com.mojang.renderpearl.api.device.GpuBackend;
import dev.overhaul.minecraft.backend.OverhaulBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Client-only registration shared by the explicitly selected 26.3 loader backend artifacts. */
@Mixin(PreferredGraphicsApi.class)
public abstract class PreferredGraphicsApiMixin {
    @Inject(method="getBackendsToTry",at=@At("HEAD"),cancellable=true)
    private void overhaul$selectOwnBackend(CallbackInfoReturnable<GpuBackend[]> callback) {
        callback.setReturnValue(new GpuBackend[]{new OverhaulBackend()});
    }
}
