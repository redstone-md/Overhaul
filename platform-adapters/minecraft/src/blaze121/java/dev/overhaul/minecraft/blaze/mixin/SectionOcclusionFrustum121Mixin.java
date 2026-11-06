package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeSectionBounds;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A frustum subclass may see boxes differently, so it gets the vanilla walk; that walk also reads the graph out, through one that sees everything. */
@Mixin(SectionOcclusionGraph.class)
abstract class SectionOcclusionFrustum121Mixin {
    @Unique private static final Frustum OVERHAUL$EVERYTHING = new Frustum(new Matrix4f(), new Matrix4f()) {
        @Override public boolean isVisible(AABB box) {
            return true;
        }
    };

    @Shadow @Final private AtomicReference<?> currentGraph;
    @Unique private final BlazeSectionBounds overhaul$bounds = new BlazeSectionBounds();

    @Shadow public abstract void addSectionsInFrustum(Frustum frustum, List<RenderSection> into);

    @Inject(method = "addSectionsInFrustum", at = @At("HEAD"), cancellable = true)
    private void overhaul$testBounds(Frustum frustum, List<RenderSection> into, CallbackInfo callback) {
        if (frustum.getClass() != Frustum.class) return;
        Object graph = currentGraph.get();
        if (!overhaul$bounds.holds(graph)) {
            List<RenderSection> all = new ArrayList<>();
            addSectionsInFrustum(OVERHAUL$EVERYTHING, all);
            overhaul$bounds.fill(graph, all);
        }
        Frustum121Accessor view = (Frustum121Accessor) frustum;
        overhaul$bounds.addVisible(view.overhaul$intersection(), view.overhaul$camX(), view.overhaul$camY(), view.overhaul$camZ(), into);
        callback.cancel();
    }

    /** A full update swaps in a new graph and asks for this frustum update. */
    @Inject(method = "consumeFrustumUpdate", at = @At("RETURN"))
    private void overhaul$graphChanged(CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ()) overhaul$bounds.invalidate();
    }

    /** A partial update adds nodes to the same graph without asking for a frustum update, so the copy misses them. */
    @Inject(method = "runPartialUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;runUpdates(Lnet/minecraft/client/renderer/SectionOcclusionGraph$GraphStorage;Lnet/minecraft/world/phys/Vec3;Ljava/util/Queue;ZLjava/util/function/Consumer;)V"))
    private void overhaul$graphGrew(CallbackInfo callback) {
        overhaul$bounds.invalidate();
    }
}
