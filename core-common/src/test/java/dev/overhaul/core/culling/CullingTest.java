package dev.overhaul.core.culling;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class CullingTest {
    private final double[] identity = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};

    @Test void separatesVulkanAndOpenGlNearPlanesAndKeepsTouchingBounds() {
        Frustum vk = new Frustum(identity, Frustum.ClipDepth.VULKAN_ZERO_TO_ONE);
        Frustum gl = new Frustum(identity, Frustum.ClipDepth.OPENGL_NEGATIVE_ONE_TO_ONE);
        Bounds behind = new Bounds(-.1,-.1,-.5,.1,.1,-.1);
        assertFalse(vk.intersects(behind));
        assertTrue(gl.intersects(behind));
        assertTrue(vk.intersects(new Bounds(1,0,0,2,1,1)));
        assertFalse(vk.intersects(new Bounds(1.01,0,0,2,1,1)));
    }

    @Test void neverCullsThroughUncoveredPixelsOrEqualDepth() {
        SoftwareDepthBuffer buffer = new SoftwareDepthBuffer(7, 5);
        assertFalse(buffer.occluded(0,0,7,5,.9f));
        buffer.rasterize(0,0,.2f,7,0,.2f,0,5,.2f);
        assertTrue(buffer.occluded(0,0,1,1,.8f));
        assertFalse(buffer.occluded(0,0,7,5,.8f));
        assertFalse(buffer.occluded(0,0,1,1,.2f));
        assertFalse(buffer.occluded(-1,0,1,1,.8f));
        buffer.clear();
        assertFalse(buffer.occluded(0,0,1,1,.8f));
    }

    @Test void hierarchicalQueriesMatchIndependentSampleCoverageForRandomRectangles() {
        int w = 19, h = 13;
        SoftwareDepthBuffer buffer = new SoftwareDepthBuffer(w,h);
        buffer.rasterize(0,0,.3f,19,0,.3f,0,13,.3f);
        Random random = new Random(107);
        for (int trial = 0; trial < 1000; trial++) {
            int x0 = random.nextInt(w), y0 = random.nextInt(h);
            int x1 = x0 + 1 + random.nextInt(w-x0), y1 = y0+1+random.nextInt(h-y0);
            boolean covered = true;
            for (int y=y0;y<y1;y++) for (int x=x0;x<x1;x++) {
                if ((x+.5)/w + (y+.5)/h >= 1-1e-8) covered = false;
            }
            assertEquals(covered, buffer.occluded(x0,y0,x1,y1,.8f));
        }
    }
}
