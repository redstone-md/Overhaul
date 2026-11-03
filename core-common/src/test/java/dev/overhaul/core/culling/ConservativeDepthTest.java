package dev.overhaul.core.culling;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConservativeDepthTest {
    @Test void aTinyTriangleAroundTheSampleCenterCannotHideTheUncoveredCell() {
        SoftwareDepthBuffer depth=new SoftwareDepthBuffer(1,1);
        depth.rasterize(.4f,.4f,.1f,.6f,.4f,.1f,.5f,.6f,.1f);
        assertFalse(depth.occluded(0,0,1,1,.8f));
    }

    @Test void slopingOccludersUseTheFarthestCellDepthForBothWindings() {
        float[][] polygons={
            {0,0,.1f,8,0,.5f,8,8,.5f,0,8,.1f},
            {0,8,.1f,8,8,.5f,8,0,.5f,0,0,.1f}
        };
        for(float[] polygon:polygons) {
            SoftwareDepthBuffer depth=new SoftwareDepthBuffer(8,8);
            depth.rasterizePolygon(polygon,4);
            assertFalse(depth.occluded(2,2,3,3,.24f),"A candidate can be in front of the far corner while behind the center");
            assertFalse(depth.occluded(2,2,3,3,.25f));
            assertTrue(depth.occluded(2,2,3,3,.26f));
            for(int y=1;y<7;y++)for(int x=1;x<7;x++) {
                float far=.1f+.05f*(x+1);
                assertFalse(depth.occluded(x,y,x+1,y+1,far-.001f));
                assertTrue(depth.occluded(x,y,x+1,y+1,far+.001f));
            }
        }
    }

    @Test void convexFacesCoverTheirInteriorWithoutAnUnsafeTriangleSeamShortcut() {
        SoftwareDepthBuffer depth=new SoftwareDepthBuffer(8,8);
        depth.rasterize(0,0,.2f,8,0,.2f,0,8,.2f);
        depth.rasterize(8,0,.2f,8,8,.2f,0,8,.2f);
        assertFalse(depth.occluded(3,3,5,5,.8f),"Neither triangle fully covers diagonal cells");
        depth.clear();
        depth.rasterizePolygon(new float[]{0,0,.2f,8,0,.2f,8,8,.2f,0,8,.2f},4);
        assertTrue(depth.occluded(1,1,7,7,.8f));
        assertFalse(depth.occluded(0,0,8,8,.8f),"Outer edges remain conservative");
    }

    @Test void nonPlanarAndNonConvexInputsFailOpenAndClippedCoordinatesAreRequired() {
        SoftwareDepthBuffer depth=new SoftwareDepthBuffer(8,8);
        depth.rasterizePolygon(new float[]{0,0,.1f,8,0,.1f,8,8,.9f,0,8,.1f},4);
        assertFalse(depth.occluded(2,2,3,3,1));
        depth.rasterizePolygon(new float[]{0,0,.1f,8,0,.1f,2,2,.1f,0,8,.1f},4);
        assertFalse(depth.occluded(2,2,3,3,1));
        assertThrows(IllegalArgumentException.class,()->depth.rasterizePolygon(new float[]{-1,0,0,8,0,0,0,8,0},3));
    }
}
