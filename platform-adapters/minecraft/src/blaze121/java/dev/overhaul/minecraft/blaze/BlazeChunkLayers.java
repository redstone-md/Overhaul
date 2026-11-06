package dev.overhaul.minecraft.blaze;

import net.minecraft.client.renderer.RenderType;

/** A chunk layer's index in {@link RenderType#chunkBufferLayers()}, the same on every loader. */
public final class BlazeChunkLayers {
    private static final RenderType[] LAYERS = RenderType.chunkBufferLayers().toArray(RenderType[]::new);

    private BlazeChunkLayers() {
    }

    /** The layer's index, or -1 for a type that is not a chunk layer; the list is five long, so a scan beats a map. */
    public static int id(RenderType type) {
        for (int index = 0; index < LAYERS.length; index++) if (LAYERS[index] == type) return index;
        return -1;
    }
}
