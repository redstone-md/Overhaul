package dev.overhaul.minecraft.shadow;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;

/** Owns independent model arenas, prepared groups and staged vertices for real shadow submissions. */
public final class ShadowFeatureFrame implements AutoCloseable {
    private final FeatureRenderDispatcher dispatcher;
    private final StagedVertexBuffer staging;
    public ShadowFeatureFrame(RenderBuffers buffers,ModelManager models,AtlasManager atlases,GameRenderState state){
        StagedVertexBuffer allocated=new StagedVertexBuffer(()->"Overhaul shadow feature staging",1<<20);
        FeatureRenderDispatcher created=null;
        try{
            created=new FeatureRenderDispatcher(buffers,models,atlases,Minecraft.getInstance().font,state);
            ((ShadowFeatureDispatcherAccess)created).overhaul$ownShadowStaging(allocated);
        }catch(RuntimeException|Error failure){
            try{if(created!=null)created.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
            try{allocated.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;
        }
        dispatcher=created;staging=allocated;
    }
    public FeatureRenderDispatcher.PreparedFrame prepare(net.minecraft.client.renderer.SubmitNodeStorage submits){return dispatcher.prepareFrame(submits);}
    public void endFrame(){staging.endFrame();}
    @Override public void close(){try{dispatcher.close();}finally{staging.close();}}
}
