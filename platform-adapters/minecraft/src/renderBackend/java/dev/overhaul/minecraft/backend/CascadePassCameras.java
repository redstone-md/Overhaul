package dev.overhaul.minecraft.backend;

import dev.overhaul.minecraft.shader.CascadeShadowFrame;
import dev.overhaul.vulkan.GpuBuffer;
import java.util.IdentityHashMap;
import java.util.Map;

/** One caster pass's cascade index and immutable camera buffers, fixed while its draws are captured. */
final class CascadePassCameras implements AutoCloseable {
    private final CascadeUniformPool pool;
    private final CascadeShadowFrame frame;
    private final int cascade;
    private final Map<ScenePackPipeline,GpuBuffer> buffers=new IdentityHashMap<>();
    CascadePassCameras(CascadeShadowGeneration source){
        pool=source.uniforms();frame=source.frame();cascade=source.selected();
        if(cascade<0)throw new IllegalStateException("Caster pass opened outside cascade emission");
    }
    int cascade(){return cascade;}
    void capture(ScenePackPipeline pipeline){
        if(pipeline.uniforms==null||buffers.containsKey(pipeline))return;
        GpuBuffer buffer=pool.acquire(pipeline.uniforms.size());buffers.put(pipeline,buffer);
        pipeline.writeCascade(buffer,frame,cascade);
    }
    GpuBuffer buffer(ScenePackPipeline pipeline){
        if(pipeline.uniforms==null)return null;
        GpuBuffer buffer=buffers.get(pipeline);if(buffer==null)throw new IllegalStateException("Caster draw recorded without its captured cascade camera");return buffer;
    }
    @Override public void close(){for(var buffer:buffers.values())pool.release(buffer);buffers.clear();}
}
