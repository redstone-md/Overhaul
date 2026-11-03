package dev.overhaul.minecraft.backendmixin;

import dev.overhaul.minecraft.shadow.ShadowViewAreaAccess;
import java.util.function.Consumer;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ViewArea.class)
public abstract class ShadowViewAreaMixin implements ShadowViewAreaAccess {
    @Shadow @Final private RotatingSectionStorage<SectionRenderDispatcher.RenderSection> sections;
    @Override public void overhaul$forEachSection(Consumer<SectionRenderDispatcher.RenderSection> consumer){sections.forEach(consumer);}
}
