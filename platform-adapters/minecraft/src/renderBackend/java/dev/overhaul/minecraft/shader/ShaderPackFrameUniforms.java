package dev.overhaul.minecraft.shader;

import dev.overhaul.shader.compat.UniformBlock;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackCustomUniforms;
import dev.overhaul.vulkan.PackGraphRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4f;

/** Pinned camera state translated to pack coordinates; unknown uniforms never receive invented zero values. */
final class ShaderPackFrameUniforms implements PackGraphRuntime.Uniforms,PackCustomUniforms.Source {
    private static final float[] IDENTITY={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
    private static final float[] ORTHOGRAPHIC={2,0,0,0,0,2,0,0,0,0,-2,0,-1,-1,-1,1};
    private static final float[] ORTHOGRAPHIC_INVERSE={.5f,0,0,0,0,.5f,0,0,0,0,-.5f,0,.5f,.5f,-.5f,1};
    private static final float[] NORMAL_IDENTITY={1,0,0,0,1,0,0,0,1};
    private static final float[] LIGHTMAP_MATRIX={1f/256,0,0,0,0,1f/256,0,0,0,0,1,0,8f/256,8f/256,0,1};
    private final long started=System.nanoTime();
    private long previousTime=started;
    private final float[] projection=new float[16],projectionInverse=new float[16],view=new float[16],viewInverse=new float[16];
    private final float[] previousProjection=new float[16],previousView=new float[16];
    private final float[] originalProjection=new float[16];
    private final float[] modelViewProjection=new float[16],normal=new float[9];
    private final Matrix4f matrix=new Matrix4f();
    private final ShaderPackEnvironmentUniforms environment=new ShaderPackEnvironmentUniforms();
    private final ShaderPackShadowUniforms shadow=new ShaderPackShadowUniforms();
    private final ShaderPackPlayerUniforms player=new ShaderPackPlayerUniforms();
    private PackCustomUniforms custom;
    private LevelRenderState state;
    private int width,height,frame;
    private float frameTime,counter,near;
    private double previousX,previousY,previousZ;
    private boolean initialized;
    void capture(Minecraft minecraft,LevelRenderState state,int width,int height,float[] nativeProjection,CompiledShaderPack pack){
        this.state=state;this.width=width;this.height=height;custom=pack.customUniforms();
        environment.capture(minecraft,state);
        shadow.capture(minecraft,state,pack.shadow());
        long now=System.nanoTime();frameTime=(float)((now-previousTime)*1e-9);previousTime=now;counter=(float)(((now-started)*1e-9)%3600);
        System.arraycopy(nativeProjection,0,projection,0,16);
        // The world uses reversed Vulkan Z. The imported depth is 1-z; reconstruct it with normal GL clip Z.
        for(int column=0;column<4;column++){
            projection[column*4+2]=nativeProjection[column*4+3]-2*nativeProjection[column*4+2];
        }
        matrix.set(projection).invert().get(projectionInverse);state.cameraRenderState.viewRotationMatrix.get(view);
        matrix.set(view).invert().get(viewInverse);
        matrix.set(projection).mul(state.cameraRenderState.viewRotationMatrix).get(modelViewProjection);
        for(int column=0;column<3;column++)for(int row=0;row<3;row++)normal[column*3+row]=viewInverse[row*4+column];
        state.cameraRenderState.projectionMatrix.get(originalProjection);
        near=originalProjection[14]/(1+originalProjection[10]);
        for(float value:projectionInverse)if(!Float.isFinite(value))throw new UnsupportedShaderException("Singular shader-pack camera projection");
        if(!Float.isFinite(near)||near<=0)throw new UnsupportedShaderException("Unsupported shader-pack camera near plane");
        if(!initialized){System.arraycopy(projection,0,previousProjection,0,16);System.arraycopy(view,0,previousView,0,16);
            var position=state.cameraRenderState.pos;previousX=position.x;previousY=position.y;previousZ=position.z;}
        player.capture(minecraft,state,frameTime,pack.smoothing(),environment,near,state.cameraRenderState.depthFar);
        custom.update(frameTime,this);
    }
    @Override public double read(String name,int component){
        var position=state.cameraRenderState.pos;
        return switch(name){
            case "viewWidth"->width;
            case "viewHeight"->height;
            case "aspectRatio"->(double)width/height;
            case "frameCounter"->frame;
            case "frameTime"->frameTime;
            case "frameTimeCounter"->counter;
            case "near"->near;
            case "far"->state.cameraRenderState.depthFar;
            case "cameraPosition"->component==0?position.x:component==1?position.y:position.z;
            case "previousCameraPosition"->component==0?previousX:component==1?previousY:previousZ;
            case "fogColor"->state.cameraRenderState.fogData.color.get(component);
            default->{
                double value=shadow.read(name,component);if(Double.isNaN(value))value=environment.read(name,component);
                yield Double.isNaN(value)?player.read(name,component):value;
            }
        };
    }
    @Override public void fill(CompiledShaderPack.Program program,UniformBlock values){
        for(String name:values.bindings().uniforms().keySet())if(!custom.fill(name,values))switch(name){
            case "viewWidth","overhaulViewportHeight"->{if(name.equals("viewWidth"))values.setFloat(name,width);else values.setFloat(name,height);}
            case "viewHeight"->values.setFloat(name,height);
            case "aspectRatio"->values.setFloat(name,(float)width/height);
            case "frameCounter"->values.setInt(name,frame);
            case "frameTime"->values.setFloat(name,frameTime);
            case "frameTimeCounter"->values.setFloat(name,counter);
            case "near"->values.setFloat(name,near);
            case "far"->values.setFloat(name,state.cameraRenderState.depthFar);
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
            case "overhaulModelViewProjection"->values.setMatrix(name,4,program.scene()?modelViewProjection:ORTHOGRAPHIC);
            case "overhaulProjection"->values.setMatrix(name,4,program.scene()?projection:ORTHOGRAPHIC);
            case "overhaulProjectionInverse"->values.setMatrix(name,4,program.scene()?projectionInverse:ORTHOGRAPHIC_INVERSE);
            case "overhaulModelView"->values.setMatrix(name,4,program.scene()?view:IDENTITY);
            case "overhaulModelViewInverse"->values.setMatrix(name,4,program.scene()?viewInverse:IDENTITY);
            case "overhaulTextureMatrix0"->values.setMatrix(name,4,IDENTITY);
            case "overhaulTextureMatrix1"->values.setMatrix(name,4,program.scene()?LIGHTMAP_MATRIX:IDENTITY);
            case "overhaulNormalMatrix"->values.setMatrix(name,3,program.scene()?normal:NORMAL_IDENTITY);
            case "alphaTestRef"->values.setFloat(name,program.state().alphaTest().map(test->test.reference()).orElse(0f));
            default->{if(!shadow.fill(name,values)&&!environment.fill(name,values)&&!player.fill(name,values))throw new UnsupportedShaderException("Unbound shader-pack uniform: "+name+" in "+program.name());}
        }
    }
    void complete(){
        System.arraycopy(projection,0,previousProjection,0,16);System.arraycopy(view,0,previousView,0,16);
        var position=state.cameraRenderState.pos;previousX=position.x;previousY=position.y;previousZ=position.z;
        frame=(frame+1)%720720;initialized=true;
    }
    void resetHistory(){initialized=false;player.reset();}
}
