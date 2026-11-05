package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeRenderer;
import dev.overhaul.minecraft.blaze.BlazeSectionBatch;
import dev.overhaul.minecraft.blaze.BlazeSectionOcclusion;
import dev.overhaul.minecraft.blaze.BlazeShaderAccess;
import dev.overhaul.minecraft.blaze.BlazeVertexBufferAccess;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Takes the sections away from the vanilla loop and draws them together once the shader's uniforms are final. The loop
 * still runs, on nothing, so what mods hook around it keeps its place; a mod hooking each section's draw sees none.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererSectionBatch121Mixin {
    @Unique private ObjectListIterator<RenderSection> overhaul$batched;

    @ModifyExpressionValue(method = "renderSectionLayer", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectArrayList;listIterator(I)Lit/unimi/dsi/fastutil/objects/ObjectListIterator;", remap = false))
    private ObjectListIterator<RenderSection> overhaul$takeSections(ObjectListIterator<RenderSection> sections) {
        overhaul$batched = null;
        BlazeRenderer renderer = BlazeFrontend.current();
        if (renderer == null || !renderer.batchesSections(RenderSystem.getShader() instanceof BlazeShaderAccess shader ? shader : null)) return sections;
        overhaul$batched = sections;
        return ObjectLists.<RenderSection>emptyList().listIterator();
    }

    @Inject(method = "renderSectionLayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ShaderInstance;apply()V", shift = At.Shift.AFTER))
    private void overhaul$drawSections(RenderType layer, double x, double y, double z, Matrix4f modelView, Matrix4f projection, CallbackInfo callback) {
        ObjectListIterator<RenderSection> sections = overhaul$batched;
        if (sections == null) return;
        overhaul$batched = null;
        boolean forward = layer != RenderType.translucent();
        BlazeRenderer renderer = BlazeFrontend.require();
        BlazeSectionBatch batch = renderer.beginSections((BlazeShaderAccess) RenderSystem.getShader(), !forward);
        BlazeSectionOcclusion occlusion = renderer.occlusion();
        if (occlusion != null) occlusion.prepare(x, y, z);
        while (forward ? sections.hasNext() : sections.hasPrevious()) {
            int place = forward ? sections.nextIndex() : sections.previousIndex();
            RenderSection section = forward ? sections.next() : sections.previous();
            if (section.getCompiled().isEmpty(layer)) continue;
            var mesh = ((BlazeVertexBufferAccess) section.getBuffer(layer)).overhaul$meshOrNull();
            if (mesh == null) continue;
            var origin = section.getOrigin();
            if (occlusion != null && occlusion.hidden(place, origin.getX(), origin.getY(), origin.getZ())) continue;
            batch.add(mesh, (float) (origin.getX() - x), (float) (origin.getY() - y), (float) (origin.getZ() - z));
        }
        batch.end();
        // After the last opaque terrain layer and before anything that moves: only terrain may hide terrain.
        if (occlusion != null && layer == RenderType.cutout()) occlusion.capture(renderer.currentTarget(), modelView, projection, x, y, z);
    }
}
