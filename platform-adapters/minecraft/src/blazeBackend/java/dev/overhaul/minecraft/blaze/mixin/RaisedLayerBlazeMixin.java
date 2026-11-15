package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.minecraft.blaze.BlazeOptimization;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Raised looks each HUD element's layer up in a case-insensitive tree several times per element per frame. The layer
 * found for an exact name is kept in a plain map, dropped whenever the tree is replaced or changes size.
 */
@Pseudo
@Mixin(targets = "dev.yurisuika.raised.util.Configure", remap = false)
abstract class RaisedLayerBlazeMixin {
    @Unique
    private static final Object overhaul$NONE = new Object();
    @Unique
    private static final Map<String, Object> overhaul$layers = new HashMap<>();
    @Unique
    private static Map<?, ?> overhaul$source;
    @Unique
    private static int overhaul$size;

    @Shadow
    public static TreeMap<String, ?> getLayers() {
        throw new AssertionError();
    }

    @Inject(method = "getLayer", at = @At("HEAD"), cancellable = true, require = 0)
    private static void overhaul$knownLayer(String name, CallbackInfoReturnable<Object> callback) {
        if (!BlazeOptimization.HUD_LAYER_MEMO.on() || !RenderSystem.isOnRenderThread()) return;
        Map<?, ?> layers = getLayers();
        if (layers != overhaul$source || layers.size() != overhaul$size) {
            overhaul$layers.clear();
            overhaul$source = layers;
            overhaul$size = layers.size();
            return;
        }
        Object layer = overhaul$layers.get(name);
        if (layer != null) callback.setReturnValue(layer == overhaul$NONE ? null : layer);
    }

    @Inject(method = "getLayer", at = @At("RETURN"), require = 0)
    private static void overhaul$rememberLayer(String name, CallbackInfoReturnable<Object> callback) {
        if (!BlazeOptimization.HUD_LAYER_MEMO.on() || !RenderSystem.isOnRenderThread() || overhaul$source == null) return;
        Object layer = callback.getReturnValue();
        overhaul$layers.put(name, layer == null ? overhaul$NONE : layer);
    }

    /** A save follows every change Raised makes to a layer in place, so the kept layers are read again after it. */
    @Inject(method = {"setLayers", "setLayer", "addLayer", "removeLayer"}, at = @At("HEAD"), require = 0)
    private static void overhaul$forgetLayers(CallbackInfo callback) {
        overhaul$source = null;
    }
}
