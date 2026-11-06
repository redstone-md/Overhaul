package dev.overhaul.minecraft.blaze;

import java.util.Arrays;
import java.util.List;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import org.joml.FrustumIntersection;

/**
 * The frustum test over a graph's sections without walking its linked set, node, section and box for each of them.
 * The test itself matches vanilla's: the same relative floats through the same intersection. Render-thread only.
 */
public final class BlazeSectionBounds {
    private static final int SIDE = 16;

    private RenderSection[] sections = new RenderSection[0];
    private int[] origins = new int[0];
    private int count, seenMoves = -1;
    private Object graph;
    private boolean stale = true;

    public void invalidate() {
        stale = true;
    }

    public boolean holds(Object graph) {
        return !stale && this.graph == graph && seenMoves == BlazeSectionChanges.originMoves();
    }

    public void fill(Object graph, List<RenderSection> all) {
        count = all.size();
        if (sections.length < count) {
            sections = new RenderSection[count + count / 4];
            origins = new int[sections.length * 3];
        }
        for (int index = 0; index < count; index++) {
            RenderSection section = all.get(index);
            var origin = section.getOrigin();
            sections[index] = section;
            origins[index * 3] = origin.getX();
            origins[index * 3 + 1] = origin.getY();
            origins[index * 3 + 2] = origin.getZ();
        }
        Arrays.fill(sections, count, sections.length, null);
        this.graph = graph;
        seenMoves = BlazeSectionChanges.originMoves();
        stale = false;
    }

    public void addVisible(FrustumIntersection frustum, double cameraX, double cameraY, double cameraZ, List<RenderSection> into) {
        int[] origins = this.origins;
        for (int index = 0, at = 0; index < count; index++, at += 3) {
            int x = origins[at], y = origins[at + 1], z = origins[at + 2];
            if (frustum.testAab((float) (x - cameraX), (float) (y - cameraY), (float) (z - cameraZ),
                    (float) (x + SIDE - cameraX), (float) (y + SIDE - cameraY), (float) (z + SIDE - cameraZ))) {
                into.add(sections[index]);
            }
        }
    }
}
