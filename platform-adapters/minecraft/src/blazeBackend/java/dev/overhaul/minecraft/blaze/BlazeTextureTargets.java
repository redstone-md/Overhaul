package dev.overhaul.minecraft.blaze;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The OpenGL texture targets the owned namespace stores: 2D, cube maps with their six face targets, and 2D arrays.
 * Each kind has its own binding per texture unit, as in OpenGL.
 */
final class BlazeTextureTargets {
    static final int TEXTURE_2D = 0x0DE1, CUBE_MAP = 0x8513, CUBE_FACE_FIRST = 0x8515, CUBE_FACE_LAST = 0x851A, ARRAY = 0x8C1A;
    static final int BINDING_CUBE = 0x8514, BINDING_ARRAY = 0x8C1D;
    /** Binding kinds, the index of each kind's binding table. */
    static final int KIND_2D = 0, KIND_CUBE = 1, KIND_ARRAY = 2, KINDS = 3;

    private BlazeTextureTargets() {}

    static boolean supported(int target) {
        return target == TEXTURE_2D || target == CUBE_MAP || target == ARRAY || face(target) >= 0;
    }

    /** The binding kind a target reads and writes. */
    static int kind(int target) {
        if (target == TEXTURE_2D) return KIND_2D;
        if (target == CUBE_MAP || face(target) >= 0) return KIND_CUBE;
        if (target == ARRAY) return KIND_ARRAY;
        throw new UnsupportedOperationException("Owned textures are 2D, cube or 2D array only: " + target);
    }

    /** The cube face a face target names, its layer in the cube image, or -1 for every other target. */
    static int face(int target) {
        return target >= CUBE_FACE_FIRST && target <= CUBE_FACE_LAST ? target - CUBE_FACE_FIRST : -1;
    }

    /** The layer a target's 2D image operations address. */
    static int layer(int target) {
        return Math.max(0, face(target));
    }

    static int viewType(int kind) {
        return switch (kind) {
            case KIND_CUBE -> VK_IMAGE_VIEW_TYPE_CUBE;
            case KIND_ARRAY -> VK_IMAGE_VIEW_TYPE_2D_ARRAY;
            default -> VK_IMAGE_VIEW_TYPE_2D;
        };
    }

    /** The binding kind a sampler of a Vulkan view type reads, or -1 for a dimension names never hold (3D). */
    static int kindOf(int viewType) {
        return switch (viewType) {
            case VK_IMAGE_VIEW_TYPE_2D -> KIND_2D;
            case VK_IMAGE_VIEW_TYPE_CUBE -> KIND_CUBE;
            case VK_IMAGE_VIEW_TYPE_2D_ARRAY -> KIND_ARRAY;
            default -> -1;
        };
    }
}
