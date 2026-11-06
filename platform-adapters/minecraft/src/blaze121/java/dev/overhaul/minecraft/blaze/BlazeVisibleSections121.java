package dev.overhaul.minecraft.blaze;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;

/** The visible list holds the sections themselves. */
public final class BlazeVisibleSections121 extends BlazeVisibleSections<RenderSection> {
    @Override protected boolean isDirty(RenderSection section) {
        return section.isDirty();
    }

    @Override protected BlazeSectionBuffers buffers(RenderSection section) {
        return (BlazeSectionBuffers) (Object) section;
    }

    @Override protected long compiledContents(RenderSection section) {
        return ((BlazeCompiledContents) section.getCompiled()).overhaul$contents();
    }
}
