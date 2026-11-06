package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.overhaul.minecraft.blaze.BlazeLayerLists;
import dev.overhaul.minecraft.blaze.BlazeVisibleSections;
import dev.overhaul.minecraft.blaze.BlazeVisibleSections120;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla loop bodies stay in place for other mods; they only receive shorter lists. The translucent resort loop keeps the whole list. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererSections120Mixin implements BlazeLayerLists {
    private static final String CHUNKS = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunksInFrustum:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;";

    @Shadow @Final private ObjectArrayList<Object> renderChunksInFrustum;
    @Unique private final BlazeVisibleSections<Object> overhaul$sections = new BlazeVisibleSections120();

    @Override public boolean overhaul$layerListed() {
        return overhaul$sections.scanned(renderChunksInFrustum);
    }

    @Override public BlazeVisibleSections.Layer overhaul$layerTable(RenderType layer) {
        return overhaul$sections.table(renderChunksInFrustum, layer);
    }

    @ModifyExpressionValue(method = "compileChunks", at = @At(value = "FIELD", target = CHUNKS))
    private ObjectArrayList<Object> overhaul$dirtyChunks(ObjectArrayList<Object> visible) {
        return overhaul$sections.dirty(visible);
    }

    @Inject(method = "applyFrustum", at = @At("TAIL"))
    private void overhaul$visibleListRefilled(CallbackInfo callback) {
        BlazeVisibleSections.changed();
    }

    @Inject(method = "compileChunks", at = @At("TAIL"))
    private void overhaul$scanLayers(Camera camera, CallbackInfo callback) {
        overhaul$sections.scan(renderChunksInFrustum);
    }

    @ModifyExpressionValue(method = "renderLevel", at = @At(value = "FIELD", target = CHUNKS, ordinal = 0))
    private ObjectArrayList<Object> overhaul$blockEntityChunks(ObjectArrayList<Object> visible) {
        return overhaul$sections.blockEntitySections(visible);
    }

    @ModifyExpressionValue(method = "renderChunkLayer", at = {@At(value = "FIELD", target = CHUNKS, ordinal = 1), @At(value = "FIELD", target = CHUNKS, ordinal = 2)})
    private ObjectArrayList<Object> overhaul$layerChunks(ObjectArrayList<Object> visible, @Local(argsOnly = true) RenderType layer) {
        return overhaul$sections.layer(visible, layer);
    }

    @Inject(method = "countRenderedChunks", at = @At("HEAD"), cancellable = true)
    private void overhaul$renderedChunks(CallbackInfoReturnable<Integer> callback) {
        int rendered = overhaul$sections.rendered(renderChunksInFrustum);
        if (rendered >= 0) callback.setReturnValue(rendered);
    }
}
