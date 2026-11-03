package dev.overhaul.vulkan;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelCameraTest {
    @Test void subtractsWorldCameraBeforeFloatConversionAndAppliesExplicitClipConvention() {
        float[] identity={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
        ModelCamera view=ModelCamera.vulkan(identity,30_000_000,0,0);
        identity[0]=9;
        float[] local=view.transform(30_000_000.125,0,0);
        assertEquals(.125f,local[12]); assertEquals(1,local[0]);
        ModelCamera gl=ModelCamera.openGl(new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1},0,0,0);
        float[] converted=gl.transform(0,0,0);
        assertEquals(-1,converted[5]); assertEquals(.5f,converted[10]); assertEquals(.5f,converted[14]);
    }
}
