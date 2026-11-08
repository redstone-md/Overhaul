package com.seibel.distanthorizons.api.methods.events.abstractEvents;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;

public abstract class DhApiBeforeBufferRenderEvent {
    public static class EventParam extends DhApiRenderParam {
        public native void update(DhApiRenderParam params, DhApiVec3f modelPos);
    }
}