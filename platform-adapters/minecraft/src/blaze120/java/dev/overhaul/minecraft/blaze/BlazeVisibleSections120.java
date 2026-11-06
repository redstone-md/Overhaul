package dev.overhaul.minecraft.blaze;

import dev.overhaul.minecraft.blaze.mixin.LevelRendererChunkInfo120Access;

/** The visible list holds the renderer's private per-chunk entries, each naming its chunk. */
public final class BlazeVisibleSections120 extends BlazeVisibleSections<Object> {
    @Override protected boolean isDirty(Object entry) {
        return ((LevelRendererChunkInfo120Access) entry).overhaul$chunk().isDirty();
    }

    @Override protected BlazeSectionBuffers buffers(Object entry) {
        return (BlazeSectionBuffers) ((LevelRendererChunkInfo120Access) entry).overhaul$chunk();
    }

    /** Chunks here read their compile live, so the flat number is the compile's own. */
    @Override protected long compiledContents(Object entry) {
        return buffers(entry).overhaul$contents();
    }
}
