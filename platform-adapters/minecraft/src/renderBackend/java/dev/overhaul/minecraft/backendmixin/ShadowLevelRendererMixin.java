package dev.overhaul.minecraft.backendmixin;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.overhaul.minecraft.backend.OverhaulGpuDevice;
import dev.overhaul.minecraft.shadow.ShadowCasterAccess;
import dev.overhaul.minecraft.shadow.ShadowCasterFrame;
import dev.overhaul.minecraft.shadow.ShadowDrawEmitter;
import dev.overhaul.minecraft.shadow.ShadowFeatureFrame;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class ShadowLevelRendererMixin implements ShadowCasterAccess {
    @Shadow @Final private GameRenderer gameRenderer;
    @Shadow @Final private RenderBuffers renderBuffers;
    @Shadow @Final private ModelManager modelManager;
    @Shadow @Final private AtlasManager atlasManager;
    @Shadow @Final private TextureManager textureManager;
    @Shadow @Final private OptionsRenderState optionsRenderState;
    @Shadow private void submitEntities(PoseStack pose,LevelRenderState state,SubmitNodeCollector collector){throw new AssertionError("Mixin shadow");}
    @Shadow private void submitBlockEntities(PoseStack pose,LevelRenderState state,SubmitNodeCollector collector){throw new AssertionError("Mixin shadow");}
    @Shadow public abstract ChunkSectionsToRender prepareChunkRenders(Matrix4fc view,boolean sorted);
    @Unique private final ShadowCasterFrame overhaul$casters=new ShadowCasterFrame();
    @Unique private SubmitNodeStorage overhaul$shadowSubmits=new SubmitNodeStorage();
    @Unique private final ShadowDrawEmitter overhaul$shadowDraws=new ShadowDrawEmitter();
    @Unique private ShadowFeatureFrame overhaul$shadowFeatures;
    @Unique private boolean overhaul$preparingShadowTerrain;
    @Override public ShadowCasterFrame overhaul$shadowFrame(){return overhaul$casters;}
    @Override public ObjectArrayList<SectionRenderDispatcher.RenderSection> overhaul$shadowSections(){return overhaul$preparingShadowTerrain?overhaul$casters.sections():null;}

    @Inject(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;submitFeatures(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;Z)V"),require=1,allow=1)
    private void overhaul$drawCasters(GraphicsResourceAllocator allocator,boolean outline,CameraRenderState camera,
            GpuBufferSlice fog,Vector4f color,boolean worldFog,boolean postProcessing,CallbackInfo callback){
        if(!(RenderSystem.getDevice() instanceof OverhaulGpuDevice device))return;
        var scenes=device.nativeFrames().shaderPacks();if(!scenes.isWorldFrameActive()||scenes.shadowSettings()==null)return;
        if(!overhaul$casters.ready()){scenes.captureShadows(()->{},()->{});return;}
        overhaul$casters.restrict(scenes.shadowSettings());
        if(overhaul$shadowFeatures==null)overhaul$shadowFeatures=new ShadowFeatureFrame(renderBuffers,modelManager,atlasManager,gameRenderer.gameRenderState());
        var states=overhaul$casters.states();var pose=new PoseStack();
        submitEntities(pose,states,overhaul$shadowSubmits);states.entityRenderStates.clear();
        submitBlockEntities(pose,states,overhaul$shadowSubmits);states.blockEntityRenderStates.clear();
        var previousFog=RenderSystem.getShaderFog();RenderSystem.setShaderFog(fog);
        gameRenderer.lighting().setupFor(Lighting.Entry.LEVEL);
        try(var features=overhaul$shadowFeatures.prepare(overhaul$shadowSubmits)){
            ChunkSectionsToRender terrain;
            overhaul$preparingShadowTerrain=true;
            try{terrain=prepareChunkRenders(RenderSystem.getModelViewStack(),true);}
            finally{overhaul$preparingShadowTerrain=false;}
            var atlas=textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();var target=gameRenderer.mainRenderTarget();
            scenes.captureShadows(()->overhaul$shadowDraws.draw(true,target,terrain,features,atlas,optionsRenderState),
                    ()->overhaul$shadowDraws.draw(false,target,terrain,features,atlas,optionsRenderState));
        }finally{try{overhaul$shadowFeatures.endFrame();}finally{overhaul$casters.reset();RenderSystem.setShaderFog(previousFog);}}
    }
    @Inject(method={"resetLevelRenderData","close"},at=@At("HEAD"))
    private void overhaul$releaseShadowState(CallbackInfo callback){overhaul$releaseShadows();}
    @Override public void overhaul$releaseShadows(){
        overhaul$casters.reset();overhaul$shadowSubmits=new SubmitNodeStorage();try{if(overhaul$shadowFeatures!=null)overhaul$shadowFeatures.close();}
        finally{overhaul$shadowFeatures=null;overhaul$shadowDraws.close();}
    }
}
