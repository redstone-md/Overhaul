package dev.overhaul.minecraft.blaze;

/** Version-specific uploads borrow the native mesh owned by the game's vertex buffer. */
public interface BlazeVertexBufferAccess {
    boolean overhaul$owned();
    /** Marks a buffer whose every draw brings new geometry, before its first upload. */
    void overhaul$streamUploads();
    BlazeMesh overhaul$uploadMesh();
    BlazeMesh overhaul$existingMesh();
    /** Null until the first upload; a section can be compiled before its buffer arrives. */
    BlazeMesh overhaul$meshOrNull();
}
