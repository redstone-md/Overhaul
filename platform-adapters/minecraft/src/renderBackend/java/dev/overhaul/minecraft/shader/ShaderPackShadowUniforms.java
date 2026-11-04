package dev.overhaul.minecraft.shader;

import dev.overhaul.shader.compat.UniformBlock;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.pack.PackShadowSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** One player-relative light camera and celestial snapshot shared by actual shadow draws and their consumers. */
final class ShaderPackShadowUniforms {
    private final Matrix4f matrix=new Matrix4f();
    private final float[] projection=new float[16],projectionInverse=new float[16],view=new float[16],viewInverse=new float[16],lightRotation=new float[16];
    private final Vector3f sun=new Vector3f(),moon=new Vector3f(),up=new Vector3f();
    private float sunAngle,shadowAngle;
    private boolean day;

    void capture(Minecraft minecraft,LevelRenderState state,PackShadowSettings settings){
        var probe=minecraft.gameRenderer.mainCamera().attributeProbe();float partial=state.worldPartialTicks;
        float sunDegrees=probe.getValue(EnvironmentAttributes.SUN_ANGLE,partial),moonDegrees=probe.getValue(EnvironmentAttributes.MOON_ANGLE,partial);
        sunAngle=angle(sunDegrees);day=sunAngle<0.5f;shadowAngle=day?sunAngle:angle(moonDegrees);
        var camera=state.cameraRenderState;
        float skyAngle=shadowAngle<0.25f?shadowAngle+0.75f:shadowAngle-0.25f;
        matrix.rotationX(radians(90)).rotateZ(radians(-360*skyAngle)).rotateX(radians(settings.sunPathRotation()));matrix.get(lightRotation);
        float interval=settings.interval();
        if(interval!=0){float half=interval/2;matrix.translate((float)camera.pos.x%interval-half,(float)camera.pos.y%interval-half,(float)camera.pos.z%interval-half);}
        matrix.get(view);matrix.invert().get(viewInverse);
        if(settings.fov()!=null){
            // Preserve the production Iris 1.20/1.21/26.1 legacy matrix, including its unusual m33=1.
            float near=-100.05f,far=156f,y=(float)(1.0f/Math.tan(Math.toRadians(settings.fov())*0.5f));
            matrix.set(y,0,0,0,0,y,0,0,0,0,(far+near)/(near-far),-1,0,0,2*far*near/(near-far),1).get(projection);
        }else{
            float distance=minecraft.options.getEffectiveRenderDistance()*16f;
            float near=settings.near()==-1?-distance:settings.near(),far=settings.far()==-1?distance:settings.far();
            if(!(far>near))throw new UnsupportedShaderException("Resolved shadow clip range must have far > near");
            matrix.setOrthoSymmetric(settings.distance()*2,settings.distance()*2,near,far,false).get(projection);
        }
        matrix.invert().get(projectionInverse);
        finite(view);finite(viewInverse);finite(projection);finite(projectionInverse);
        celestial(camera.viewRotationMatrix,settings.sunPathRotation(),sunDegrees,sun);
        celestial(camera.viewRotationMatrix,settings.sunPathRotation(),moonDegrees,moon);
        matrix.set(camera.viewRotationMatrix).rotateY(radians(-90)).transformDirection(0,100,0,up);
    }
    float[] lightRotation(){return lightRotation;}
    boolean fill(String name,UniformBlock values){
        switch(name){
            case "shadowProjection"->values.setMatrix(name,4,projection);
            case "shadowProjectionInverse"->values.setMatrix(name,4,projectionInverse);
            case "shadowModelView"->values.setMatrix(name,4,view);
            case "shadowModelViewInverse"->values.setMatrix(name,4,viewInverse);
            case "sunAngle"->values.setFloat(name,sunAngle);
            case "shadowAngle"->values.setFloat(name,shadowAngle);
            case "sunPosition"->vector(values,name,sun);
            case "moonPosition"->vector(values,name,moon);
            case "shadowLightPosition"->vector(values,name,day?sun:moon);
            case "upPosition"->vector(values,name,up);
            default->{return false;}
        }return true;
    }
    /** Reads a non-matrix value {@link #fill} owns for custom uniform expressions; NaN marks any other name. */
    double read(String name,int component){
        return switch(name){
            case "sunAngle"->sunAngle;
            case "shadowAngle"->shadowAngle;
            case "sunPosition"->sun.get(component);
            case "moonPosition"->moon.get(component);
            case "shadowLightPosition"->(day?sun:moon).get(component);
            case "upPosition"->up.get(component);
            default->Double.NaN;
        };
    }
    private void celestial(Matrix4fc camera,float path,float degrees,Vector3f result){matrix.set(camera).rotateY(radians(-90)).rotateZ(radians(path)).rotateX(radians(degrees)).transformPosition(0,100,0,result);}
    private static void vector(UniformBlock values,String name,Vector3f vector){values.setVector(name,vector.x,vector.y,vector.z);}
    private static float angle(float degrees){
        if(!Float.isFinite(degrees))throw new UnsupportedShaderException("Celestial camera angle is not finite");
        float result=degrees+90;if(result<0)result+=360;else if(result>360)result-=360;
        if(result<0||result>360)throw new UnsupportedShaderException("Celestial camera angle exceeds its pinned degree range");return result/360;
    }
    private static float radians(float degrees){return(float)Math.toRadians(degrees);}
    private static void finite(float[] matrix){for(float value:matrix)if(!Float.isFinite(value))throw new UnsupportedShaderException("Singular or nonfinite shadow camera");}
}
