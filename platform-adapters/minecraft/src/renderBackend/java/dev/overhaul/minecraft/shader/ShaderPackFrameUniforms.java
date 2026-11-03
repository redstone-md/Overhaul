package dev.overhaul.minecraft.shader;

import dev.overhaul.shader.compat.UniformBlock;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.vulkan.PackGraphRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4f;

/** Pinned camera state translated to pack coordinates; unknown uniforms never receive invented zero values. */
final class ShaderPackFrameUniforms implements PackGraphRuntime.Uniforms {
    private static final float[] IDENTITY={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
    private static final float[] ORTHOGRAPHIC={2,0,0,0,0,2,0,0,0,0,-2,0,-1,-1,-1,1};
    private static final float[] ORTHOGRAPHIC_INVERSE={.5f,0,0,0,0,.5f,0,0,0,0,-.5f,0,.5f,.5f,-.5f,1};
    private static final float[] NORMAL_IDENTITY={1,0,0,0,1,0,0,0,1};
    private final long started=System.nanoTime();
    private long previousTime=started;
    private final float[] projection=new float[16],projectionInverse=new float[16],view=new float[16],viewInverse=new float[16];
    private final float[] previousProjection=new float[16],previousView=new float[16];
    private final float[] originalProjection=new float[16];
    private final Matrix4f matrix=new Matrix4f();
    private LevelRenderState state;
    private Minecraft minecraft;
    private int width,height,frame;
    private float frameTime,counter,near;
    private double previousX,previousY,previousZ;
    private boolean initialized;
    void capture(Minecraft minecraft,LevelRenderState state,int width,int height,float[] nativeProjection){
        this.minecraft=minecraft;this.state=state;this.width=width;this.height=height;
        long now=System.nanoTime();frameTime=(float)((now-previousTime)*1e-9);previousTime=now;counter=(float)(((now-started)*1e-9)%3600);
        System.arraycopy(nativeProjection,0,projection,0,16);
        // The world uses reversed Vulkan Z. The imported depth is 1-z; reconstruct it with normal GL clip Z.
        for(int column=0;column<4;column++){
            projection[column*4+2]=nativeProjection[column*4+3]-2*nativeProjection[column*4+2];
        }
        matrix.set(projection).invert().get(projectionInverse);state.cameraRenderState.viewRotationMatrix.get(view);
        matrix.set(view).invert().get(viewInverse);
        state.cameraRenderState.projectionMatrix.get(originalProjection);
        near=originalProjection[14]/(1+originalProjection[10]);
        for(float value:projectionInverse)if(!Float.isFinite(value))throw new UnsupportedShaderException("Singular shader-pack camera projection");
        if(!Float.isFinite(near)||near<=0)throw new UnsupportedShaderException("Unsupported shader-pack camera near plane");
        if(!initialized){System.arraycopy(projection,0,previousProjection,0,16);System.arraycopy(view,0,previousView,0,16);
            var position=state.cameraRenderState.pos;previousX=position.x;previousY=position.y;previousZ=position.z;}
    }
    @Override public void fill(CompiledShaderPack.Program program,UniformBlock values){
        for(String name:values.bindings().uniforms().keySet())switch(name){
            case "viewWidth","overhaulViewportHeight"->{if(name.equals("viewWidth"))values.setFloat(name,width);else values.setFloat(name,height);}
            case "viewHeight"->values.setFloat(name,height);
            case "aspectRatio"->values.setFloat(name,(float)width/height);
            case "frameCounter"->values.setInt(name,frame);
            case "frameTime"->values.setFloat(name,frameTime);
            case "frameTimeCounter"->values.setFloat(name,counter);
            case "near"->values.setFloat(name,near);
            case "far"->values.setFloat(name,state.cameraRenderState.depthFar);
            case "rainStrength"->values.setFloat(name,minecraft.level.getRainLevel(state.worldPartialTicks));
            case "cameraPosition"->{var position=state.cameraRenderState.pos;values.setVector(name,(float)position.x,(float)position.y,(float)position.z);}
            case "previousCameraPosition"->values.setVector(name,(float)previousX,(float)previousY,(float)previousZ);
            case "fogColor"->{var color=state.cameraRenderState.fogData.color;values.setVector(name,color.x(),color.y(),color.z());}
            case "overhaulFogColor"->{var color=state.cameraRenderState.fogData.color;values.setVector(name,color.x(),color.y(),color.z(),color.w());}
            case "gbufferProjection"->values.setMatrix(name,4,projection);
            case "gbufferProjectionInverse"->values.setMatrix(name,4,projectionInverse);
            case "gbufferModelView"->values.setMatrix(name,4,view);
            case "gbufferModelViewInverse"->values.setMatrix(name,4,viewInverse);
            case "gbufferPreviousProjection"->values.setMatrix(name,4,previousProjection);
            case "gbufferPreviousModelView"->values.setMatrix(name,4,previousView);
            case "overhaulModelViewProjection","overhaulProjection"->values.setMatrix(name,4,ORTHOGRAPHIC);
            case "overhaulProjectionInverse"->values.setMatrix(name,4,ORTHOGRAPHIC_INVERSE);
            case "overhaulModelView","overhaulModelViewInverse","overhaulTextureMatrix0","overhaulTextureMatrix1"->values.setMatrix(name,4,IDENTITY);
            case "overhaulNormalMatrix"->values.setMatrix(name,3,NORMAL_IDENTITY);
            default->throw new UnsupportedShaderException("Unbound shader-pack uniform: "+name+" in "+program.name());
        }
    }
    void complete(){
        System.arraycopy(projection,0,previousProjection,0,16);System.arraycopy(view,0,previousView,0,16);
        var position=state.cameraRenderState.pos;previousX=position.x;previousY=position.y;previousZ=position.z;
        frame=(frame+1)%720720;initialized=true;
    }
    void resetHistory(){initialized=false;}
}
