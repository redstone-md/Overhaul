package com.seibel.distanthorizons.core.render.renderer;

import com.seibel.distanthorizons.core.util.math.DhVec3f;
import java.awt.Color;

public abstract class AbstractDebugWireframeRenderer {
    public static final class Box {
        public DhVec3f minPos, maxPos;
        public Color color;
    }
}
