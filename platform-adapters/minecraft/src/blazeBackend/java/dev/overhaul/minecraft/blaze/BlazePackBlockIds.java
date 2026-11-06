package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.pack.PackIdMap;

/**
 * The pack whose block.properties ids terrain builds write into each vertex, read back as mc_Entity. Null while no
 * pack draws the world, so sections then build in the game's own format.
 */
public final class BlazePackBlockIds {
    /** Vertex field holding the id and the render type as two shorts. */
    public static final String FIELD = "OverhaulEntity";
    private static volatile PackIdMap active;

    private BlazePackBlockIds() {}

    public static PackIdMap active() { return active; }

    /** True when built sections now carry the wrong ids and must rebuild. */
    static boolean activate(PackIdMap ids) {
        PackIdMap next = BlazeOptimization.PACK_BLOCK_IDS.on() && ids != null && ids.hasBlocks() ? ids : null;
        PackIdMap previous = active;
        active = next;
        return previous != next;
    }
}
