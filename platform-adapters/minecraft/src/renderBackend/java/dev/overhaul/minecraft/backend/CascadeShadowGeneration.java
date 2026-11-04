package dev.overhaul.minecraft.backend;

import dev.overhaul.minecraft.shader.CascadeShadowFrame;
import dev.overhaul.minecraft.shader.OwnedTerrainShaders;
import dev.overhaul.shader.pack.CascadeShadowSettings;
import dev.overhaul.vulkan.CascadeShadowMaps;
import dev.overhaul.vulkan.GpuImageView;
import java.util.Objects;

/** Layered depth, per-frame cascade emission and terrain receivers for one owned generation. */
final class CascadeShadowGeneration implements AutoCloseable {
    private final OverhaulDeviceBackend owner;
    private final CascadeShadowSettings settings;
    private final CascadeUniformPool uniforms;
    private CascadeTerrainPipelines terrain;
    private CascadeShadowMaps maps;
    private GpuImageView sampledDepth;
    private CascadeShadowFrame frame;
    private int selected=-1;
    private boolean samplingReady;
    CascadeShadowGeneration(OverhaulDeviceBackend owner,CascadeShadowSettings settings,OwnedTerrainShaders shaders){
        this.owner=owner;this.settings=settings;uniforms=new CascadeUniformPool(owner);
        try{
            terrain=new CascadeTerrainPipelines(owner,shaders);
            maps=new CascadeShadowMaps(owner.context(),owner.allocator(),settings);
            // The opaque role: translucent casters must not shadow the water that casts them.
            sampledDepth=maps.arrayView(true);
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    void beginFrame(CascadeShadowFrame next){
        frame=Objects.requireNonNull(next,"Owned cascades require a captured camera frame");selected=-1;samplingReady=false;
    }
    void clear(){maps.clear(owner.commands());}
    /** Prepared geometry replays once per cascade; each role's layers settle before the next role begins. */
    void capture(Runnable opaque,Runnable translucent){
        try{
            emit(opaque);maps.snapshotOpaque(owner.commands());
            emit(translucent);maps.readable(owner.commands());samplingReady=true;
        }finally{selected=-1;}
    }
    private void emit(Runnable draws){
        for(int cascade=0;cascade<maps.count();cascade++){selected=cascade;draws.run();}
        selected=-1;
    }
    int selected(){return selected;}
    boolean samplingReady(){return samplingReady;}
    CascadeShadowFrame frame(){return frame;}
    CascadeUniformPool uniforms(){return uniforms;}
    CascadeTerrainPipelines terrain(){return terrain;}
    int resolution(){return settings.resolution();}
    GpuImageView depth(int cascade){return maps.depth(cascade,false);}
    GpuImageView sampledDepth(){return sampledDepth;}
    long depthSampler(){return maps.sampler();}
    @Override public void close(){
        try{uniforms.close();}
        finally{
            try{if(terrain!=null){terrain.close();terrain=null;}}
            finally{if(maps!=null){maps.close();maps=null;}}
        }
    }
}
