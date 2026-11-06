package dev.overhaul.minecraft.blaze;

/** A buffer that stamps the block being built into each vertex it starts, when its format has the field. */
public interface BlazePackVertexTag {
    void overhaul$tag(short id, short type);
}
