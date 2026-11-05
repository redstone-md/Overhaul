package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.overhaul.minecraft.blaze.BlazeLayerLists;
import dev.overhaul.minecraft.blaze.BlazeVisibleSections;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla loop bodies stay in place for other mods; they only receive shorter lists. The translucent resort loop keeps the whole list. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererSections121Mixin implements BlazeLayerLists {
    @Shadow private ObjectArrayList<RenderSection> visibleSections;
    @Unique private final BlazeVisibleSections overhaul$sections=new BlazeVisibleSections();

    @Override public boolean overhaul$layerListed(){
        return overhaul$sections.scanned(visibleSections);
    }

    @ModifyExpressionValue(method="compileSections",at=@At(value="FIELD",target="Lnet/minecraft/client/renderer/LevelRenderer;visibleSections:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
    private ObjectArrayList<RenderSection> overhaul$dirtySections(ObjectArrayList<RenderSection> visible){
        return overhaul$sections.dirty(visible);
    }

    @Inject(method="compileSections",at=@At("TAIL"))
    private void overhaul$scanLayers(Camera camera,CallbackInfo callback){
        overhaul$sections.scan(visibleSections);
    }

    @ModifyExpressionValue(method="renderSectionLayer",at={
            @At(value="FIELD",target="Lnet/minecraft/client/renderer/LevelRenderer;visibleSections:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",ordinal=1),
            @At(value="FIELD",target="Lnet/minecraft/client/renderer/LevelRenderer;visibleSections:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",ordinal=2)})
    private ObjectArrayList<RenderSection> overhaul$layerSections(ObjectArrayList<RenderSection> visible,@Local(argsOnly=true) RenderType layer){
        return overhaul$sections.layer(visible,layer);
    }
}
