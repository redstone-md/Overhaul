package com.seibel.distanthorizons.common.render.openGl.generic;

import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IDhGenericObjectVertexBufferContainer;

public class GlGenericObjectVertexContainer implements IDhGenericObjectVertexBufferContainer {
    public int chunkPos, subChunkPos, scale, color, uploadedBoxCount;
    @Override public native EState getState();
}
