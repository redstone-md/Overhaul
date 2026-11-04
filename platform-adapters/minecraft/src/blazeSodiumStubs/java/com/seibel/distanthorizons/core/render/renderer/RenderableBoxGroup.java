package com.seibel.distanthorizons.core.render.renderer;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBoxGroupShading;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IDhGenericObjectVertexBufferContainer;

public class RenderableBoxGroup {
    public boolean active, ssaoEnabled;
    public DhApiRenderableBoxGroupShading shading;
    public IDhGenericObjectVertexBufferContainer vertexBufferContainer;
    public native DhApiVec3d getOriginBlockPos();
    public native int getSkyLight();
    public native int getBlockLight();
    public native void tryUpdateInstancedDataAsync();
    public native void preRender(DhApiRenderParam params);
    public native void postRender(DhApiRenderParam params);
}
