package dev.overhaul.minecraft.blaze.mixin;

import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLIndexBuffer;
import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.render.renderer.RenderableBoxGroup;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import dev.overhaul.minecraft.blaze.BlazeDhFrame;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import java.util.concurrent.ConcurrentHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** DH's clouds, beacon beams and API boxes are raw instanced draws, which reach nothing on Vulkan. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.generic.GlGenericObjectRenderer", remap = false)
abstract class DhGenericBlazeMixin {
    @Shadow(remap = false) private GLVertexBuffer boxVertexBuffer;
    @Shadow(remap = false) private GLIndexBuffer boxIndexBuffer;
    @Shadow(remap = false) @Final private ConcurrentHashMap<Long, RenderableBoxGroup> boxGroupById;
    @Shadow(remap = false) public abstract void init();

    @Inject(method = "render(Lcom/seibel/distanthorizons/core/render/RenderParams;Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;Z)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$drawNatively(RenderParams params, IProfilerWrapper profiler, boolean ssao, CallbackInfo callback) {
        var renderer = BlazeFrontend.current();
        if (renderer == null) return;
        init();
        BlazeDhFrame.drawBoxes(renderer, params, boxGroupById.values(), ssao, boxVertexBuffer.getId(), boxIndexBuffer.getId());
        callback.cancel();
    }
}
