package com.seibel.distanthorizons.api.methods.events.sharedParameterObjects;

import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;

public class DhApiRenderParam {
    public DhApiMat4f mcInverseMvmProjectionMatrix, dhMvmProjMatrix, dhInverseMvmProjectionMatrix, dhProjectionMatrix;
    public int worldYOffset;
}
