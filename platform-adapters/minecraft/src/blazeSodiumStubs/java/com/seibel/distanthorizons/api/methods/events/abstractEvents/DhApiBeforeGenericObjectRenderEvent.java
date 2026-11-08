package com.seibel.distanthorizons.api.methods.events.abstractEvents;

import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;

public abstract class DhApiBeforeGenericObjectRenderEvent {
    public static class EventParam extends DhApiRenderParam {
        public native void update(DhApiRenderParam params, IDhApiRenderableBoxGroup group);
    }
}