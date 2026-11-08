package com.seibel.distanthorizons.core.render;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;

public class RenderParams extends DhApiRenderParam {
    public DhApiRenderParam apiCopy;
    public DhVec3d exactCameraPosition;
    public IClientLevelWrapper clientLevelWrapper;
}
