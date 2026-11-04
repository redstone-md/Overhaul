package com.seibel.distanthorizons.api.methods.events.sharedParameterObjects;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogFalloff;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiHeightFogDirection;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiHeightFogMixMode;
import java.awt.Color;

public class DhApiFogRenderParam {
    public native Color getFogColor();
    public native EDhApiFogFalloff getFarFogFalloff();
    public native float getFarFogStartPercent();
    public native float getFarFogEndPercent();
    public native float getFarFogMinThickness();
    public native float getFarFogMaxThickness();
    public native float getFarFogDensity();
    public native EDhApiFogFalloff getHeightFogFalloff();
    public native EDhApiHeightFogMixMode getHeightFogMixingMode();
    public native EDhApiHeightFogDirection getHeightFogDirection();
    public native float getHeightFogBaseHeight();
    public native float getHeightFogStartPercent();
    public native float getHeightFogEndPercent();
}
