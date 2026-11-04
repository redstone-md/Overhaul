package dev.overhaul.shader.pack;

/** Owned renderer policy; existing Iris shadow directives never implicitly enable cascades. */
public record CascadeShadowSettings(int count,int resolution,float distance,float splitLambda,float blendFraction) {
    public CascadeShadowSettings {
        if(count<2||count>4||resolution<64||resolution>4096||!Float.isFinite(distance)||distance<=0
                ||!Float.isFinite(splitLambda)||splitLambda<0||splitLambda>1||!Float.isFinite(blendFraction)||blendFraction<0||blendFraction>0.25f)
            throw new IllegalArgumentException("Invalid owned cascade settings");
    }
    public static CascadeShadowSettings configured(){
        int count=Integer.getInteger("overhaul.shadowCascades",0);if(count==0)return null;
        return new CascadeShadowSettings(count,Integer.getInteger("overhaul.shadowResolution",1024),
                Float.parseFloat(System.getProperty("overhaul.shadowDistance","160")),
                Float.parseFloat(System.getProperty("overhaul.shadowSplitLambda","0.5")),
                Float.parseFloat(System.getProperty("overhaul.shadowBlendFraction","0.1")));
    }
}
