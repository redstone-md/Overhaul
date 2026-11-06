package dev.overhaul.minecraft.blaze;

/** What a compiled section holds as one number, since it never changes once the section is published and is read every frame. */
public interface BlazeCompiledContents {
    /** Set when the section has block entities to render; the bits below it are chunk layer ids. */
    long BLOCK_ENTITIES = 1L << 63;
    long KNOWN = 1L << 62;

    long overhaul$contents();
}
