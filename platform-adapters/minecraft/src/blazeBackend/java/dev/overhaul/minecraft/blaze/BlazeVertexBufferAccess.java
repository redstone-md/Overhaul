package dev.overhaul.minecraft.blaze;

/** Version-specific uploads borrow the native mesh owned by the game's vertex buffer. */
public interface BlazeVertexBufferAccess {
    boolean overhaul$owned();
    BlazeMesh overhaul$uploadMesh();
    BlazeMesh overhaul$existingMesh();
}
