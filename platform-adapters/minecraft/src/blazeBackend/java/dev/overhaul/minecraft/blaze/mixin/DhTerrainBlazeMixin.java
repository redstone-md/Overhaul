package dev.overhaul.minecraft.blaze.mixin;

import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import dev.overhaul.minecraft.blaze.BlazeDhFrame;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** DH ships one copy of its renderers per loader; only the one for the running loader ever loads. */
@Pseudo
@Mixin(targets = {
        "com.seibel.distanthorizons.common.render.openGl.terrain.GlDhTerrainShaderProgram",
        "com.seibel.distanthorizons.common.render.openGl.terrain.GlDhTerrainShaderProgram_neoforge"}, remap = false)
abstract class DhTerrainBlazeMixin {
    @Inject(method = "render(Lcom/seibel/distanthorizons/core/render/RenderParams;ZLcom/seibel/distanthorizons/core/util/objects/SortedArraySet;Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$drawNatively(RenderParams params, boolean opaque, SortedArraySet<LodBufferContainer> containers, IProfilerWrapper profiler, CallbackInfo callback) {
        var renderer = BlazeFrontend.current();
        if (renderer == null) return;
        BlazeDhFrame.drawTerrain(renderer, params, opaque, containers);
        callback.cancel();
    }
}
