package dev.overhaul.minecraft.backendmixin;

import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Urgent rebuilds keep the same task priority/cancellation path and execute on owned workers. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class AsyncSectionCompileMixin {
    @Inject(method="compileSync",at=@At("HEAD"),cancellable=true)
    private void overhaul$queueUrgentCompile(RenderSectionRegion region,CallbackInfo callback) {
        ((SectionRenderDispatcher.RenderSection)(Object)this).compileAsync(region);
        callback.cancel();
    }
}
