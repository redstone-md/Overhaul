package dev.overhaul.vulkan;

import dev.overhaul.core.culling.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SceneVisibilityTest {
    private static Frustum camera() {
        return new Frustum(new double[] {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1}, Frustum.ClipDepth.VULKAN_ZERO_TO_ONE);
    }

    @Test void takesImmutableDrawBoundsSnapshotAndLeavesUnknownProjectionsVisible() {
        Bounds[] world = {new Bounds(-.5, -.5, .2, .5, .5, .8)};
        SceneVisibility.ScreenBounds[] projected = {null};
        SceneVisibility scene = new SceneVisibility(world, projected);
        world[0] = new Bounds(3, 3, 3, 4, 4, 4);
        projected[0] = new SceneVisibility.ScreenBounds(2, 2, 6, 6, .5f);
        SoftwareDepthBuffer depth = new SoftwareDepthBuffer(16, 16);
        depth.rasterize(0, 0, .1f, 16, 0, .1f, 0, 16, .1f);
        assertTrue(scene.visible(0, camera(), depth));
        assertFalse(new SceneVisibility(world).visible(0, camera(), depth));
        assertThrows(IllegalArgumentException.class, () -> new SceneVisibility(world, new SceneVisibility.ScreenBounds[2]));
    }

    @Test void retainsNearAndUncertainCandidatesWhileCullingOnlyCoveredFarBounds() {
        Bounds visible = new Bounds(-.5, -.5, .1, .5, .5, .8);
        SceneVisibility scene = new SceneVisibility(new Bounds[] {visible, visible, visible, visible}, new SceneVisibility.ScreenBounds[] {
            new SceneVisibility.ScreenBounds(2, 2, 6, 6, .5f),
            new SceneVisibility.ScreenBounds(2, 2, 6, 6, .1f),
            new SceneVisibility.ScreenBounds(-1, 2, 6, 6, .5f),
            new SceneVisibility.ScreenBounds(10, 10, 14, 14, .5f)
        });
        SoftwareDepthBuffer depth = new SoftwareDepthBuffer(16, 16);
        depth.rasterize(0, 0, .2f, 16, 0, .2f, 0, 16, .2f);
        assertFalse(scene.visible(0, camera(), depth));
        assertTrue(scene.visible(1, camera(), depth));
        assertTrue(scene.visible(2, camera(), depth));
        assertTrue(scene.visible(3, camera(), depth));
        depth.clear();
        assertTrue(scene.visible(0, camera(), depth));
    }
}
