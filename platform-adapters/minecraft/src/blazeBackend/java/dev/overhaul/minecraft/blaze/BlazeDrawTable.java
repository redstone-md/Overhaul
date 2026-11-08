package dev.overhaul.minecraft.blaze;

import java.util.Arrays;

/**
 * Slots of meshes with flat copies of their draw fields, so a batch adds a slot without reading the scattered mesh. A
 * copied mesh marks itself, and any later change to it moves the section change counter, which makes the copy stale.
 * Only slots are rewritten that changed: each reference store pays a GC barrier.
 */
public class BlazeDrawTable {
    private BlazeMesh[] meshes = new BlazeMesh[256];
    /** Index count, first index, base vertex and topology; a zero count draws nothing. */
    private int[] draws = new int[4 * 256];
    private BlazeSectionRun[] runs = new BlazeSectionRun[256];
    private BlazeMeshState.Layout[] layouts = new BlazeMeshState.Layout[256];

    /** Stores {@code mesh} at {@code at}, with its draw fields when {@code copy} is set; slots below {@code at} stay. */
    public final void put(int at, BlazeMesh mesh, boolean copy) {
        if (at >= meshes.length) grow(Math.max(at + 1, meshes.length * 2));
        if (meshes[at] != mesh) meshes[at] = mesh;
        if (copy) copyDraw(at, mesh);
    }

    /** Drops the meshes from {@code from} up to {@code to}, so a shrunk table holds none of them alive. */
    public final void clear(int from, int to) {
        to = Math.min(to, meshes.length);
        if (from >= to) return;
        Arrays.fill(meshes, from, to, null);
        Arrays.fill(runs, from, to, null);
        Arrays.fill(layouts, from, to, null);
    }

    /** Empties slot {@code at} if it still holds {@code mesh}, so its stale copy is read again. */
    final void forget(int at, BlazeMesh mesh) {
        if (at < meshes.length && meshes[at] == mesh) meshes[at] = null;
    }

    /** Grows every per-slot array to {@code slots}; a subclass grows its own arrays alongside. */
    void grow(int slots) {
        meshes = Arrays.copyOf(meshes, slots);
        draws = Arrays.copyOf(draws, slots * 4);
        runs = Arrays.copyOf(runs, slots);
        layouts = Arrays.copyOf(layouts, slots);
    }

    final int capacity() {
        return meshes.length;
    }

    /** Marked before the fields are read, so a change racing the copy moves the change counter after it. */
    private void copyDraw(int at, BlazeMesh mesh) {
        if (mesh != null && !mesh.tabled) mesh.tabled = true;
        int count = mesh == null ? 0 : mesh.drawIndexCount;
        BlazeSectionRun run = count == 0 ? null : mesh.drawRun;
        BlazeMeshState.Layout layout = count == 0 ? null : mesh.drawLayout;
        if (run == null || layout == null) count = 0;
        draws[at * 4] = count;
        if (count != 0) {
            draws[at * 4 + 1] = mesh.drawFirstIndex;
            draws[at * 4 + 2] = mesh.drawBaseVertex;
            draws[at * 4 + 3] = mesh.drawTopology;
        }
        if (runs[at] != run) runs[at] = run;
        if (layouts[at] != layout) layouts[at] = layout;
    }

    int indexCount(int index) { return draws[index * 4]; }
    int firstIndex(int index) { return draws[index * 4 + 1]; }
    int baseVertex(int index) { return draws[index * 4 + 2]; }
    int topology(int index) { return draws[index * 4 + 3]; }
    BlazeSectionRun run(int index) { return runs[index]; }
    BlazeMeshState.Layout layout(int index) { return layouts[index]; }
    public final BlazeMesh mesh(int index) { return meshes[index]; }
}
