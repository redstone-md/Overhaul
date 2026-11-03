package dev.overhaul.core.culling;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PerspectiveEyeTest {
    @Test void projectionTranslationCanReverseTheVisibleSideOfANearbyPlane() {
        double[] bob=identity();bob[12]=.1;
        PerspectiveEye eye=PerspectiveEye.fromMatrix(multiply(projection(),bob));
        assertNotNull(eye);assertEquals(-.1,eye.coordinate(0),1e-12);
        // The unmodified eye at zero would classify this positive-X face as front-facing.
        assertFalse(eye.faces(0,1,-.05));assertTrue(eye.faces(0,-1,-.05));
        assertFalse(eye.faces(0,1,-.1));assertFalse(eye.faces(0,-1,-.1));
    }
    @Test void finalProjectionAndViewRotationLocateTheEyeInCameraRelativeWorldCoordinates() {
        double[] bob=identity();bob[12]=.25;bob[13]=-.125;bob[14]=.0625;
        double[] rotation=identity();rotation[0]=0;rotation[2]=-1;rotation[8]=1;rotation[10]=0;
        PerspectiveEye eye=PerspectiveEye.fromMatrix(multiply(multiply(projection(),bob),rotation));
        assertNotNull(eye);assertEquals(.0625,eye.coordinate(0),1e-12);
        assertEquals(.125,eye.coordinate(1),1e-12);assertEquals(-.25,eye.coordinate(2),1e-12);
        PerspectiveEye ordinary=PerspectiveEye.fromMatrix(projection());
        assertNotNull(ordinary);for(int axis=0;axis<3;axis++)assertEquals(0,ordinary.coordinate(axis),1e-12);
    }
    @Test void orthographicSingularNonFiniteAndIllConditionedTransformsFailOpen() {
        assertNull(PerspectiveEye.fromMatrix(identity()));assertNull(PerspectiveEye.fromMatrix(new double[16]));
        double[] invalid=projection();invalid[3]=Double.NaN;assertNull(PerspectiveEye.fromMatrix(invalid));
        double[] illConditioned=projection();illConditioned[14]=1e-15;assertNull(PerspectiveEye.fromMatrix(illConditioned));
    }
    private static double[] projection(){double[] p=new double[16];p[0]=p[5]=1;p[10]=.1/99.9;p[11]=-1;p[14]=10/99.9;return p;}
    private static double[] identity(){double[] result=new double[16];result[0]=result[5]=result[10]=result[15]=1;return result;}
    private static double[] multiply(double[] a,double[] b){double[] result=new double[16];for(int c=0;c<4;c++)for(int r=0;r<4;r++)for(int k=0;k<4;k++)result[c*4+r]+=a[k*4+r]*b[c*4+k];return result;}
}
