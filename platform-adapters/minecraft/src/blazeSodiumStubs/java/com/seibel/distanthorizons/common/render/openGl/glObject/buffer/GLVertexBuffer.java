package com.seibel.distanthorizons.common.render.openGl.glObject.buffer;

import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;

public class GLVertexBuffer extends GLBuffer implements IVertexBufferWrapper {
    public native int getVertexCount();
    public native GlQuadIndexBuffer getQuadIBO();
}
