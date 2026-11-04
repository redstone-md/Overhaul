package com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding;

import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;

public class LodBufferContainer {
    public DhBlockPos minCornerBlockPos;
    public boolean buffersUploaded;
    public IVertexBufferWrapper[] vboOpaqueWrappers, vboTransparentWrappers;
}
