package dev.overhaul.minecraft.backend;

import dev.overhaul.minecraft.shader.CascadeShadowFrame;
import dev.overhaul.shader.pack.CascadeShadowSettings;
import dev.overhaul.vulkan.CascadeShadowMaps;
import dev.overhaul.vulkan.GpuImageView;
import java.util.Objects;

/** Layered depth and per-frame cascade emission for one owned generation. */
final class CascadeShadowGeneration implements AutoCloseable {
    private final OverhaulDeviceBackend owner;
    private final CascadeShadowSettings settings;
    private final CascadeUniformPool uniforms;
    private CascadeShadowMaps maps;
    private CascadeShadowFrame frame;
    private int selected=-1;
    private boolean samplingReady;
    CascadeShadowGeneration(OverhaulDeviceBackend owner,CascadeShadowSettings settings){
        this.owner=owner;this.settings=settings;uniforms=new CascadeUniformPool(owner);
        maps=new CascadeShadowMaps(owner.context(),owner.allocator(),settings);
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
    int resolution(){return settings.resolution();}
    GpuImageView depth(int cascade){return maps.depth(cascade,false);}
    @Override public void close(){
        try{uniforms.close();}finally{if(maps!=null){maps.close();maps=null;}}
    }
}
