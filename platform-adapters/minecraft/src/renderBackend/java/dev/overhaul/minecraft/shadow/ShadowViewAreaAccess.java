package dev.overhaul.minecraft.shadow;

import java.util.function.Consumer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

public interface ShadowViewAreaAccess {
    void overhaul$forEachSection(Consumer<SectionRenderDispatcher.RenderSection> consumer);
}
