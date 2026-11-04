package com.seibel.distanthorizons.common.render.openGl.glObject.buffer;

import java.util.concurrent.locks.StampedLock;

public class GLBuffer {
    public final StampedLock renderStampLock = null;
    public final native int getId();
}
