package dev.overhaul.minecraft.blaze;

/** A buffer that stamps the block being built into each vertex it starts, when its format has the fields. */
public interface BlazePackVertexTag {
    /**
     * @param x the block's position within its section, the space section vertices are in; so are {@code y} and {@code z}
     * @param light the block's light emission, which packs read as at_midBlock.w
     */
    void overhaul$tag(short id, short type, int x, int y, int z, int light);
}
