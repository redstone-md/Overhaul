package dev.overhaul.minecraft.backendmixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.terrain.TerrainCullingAccess;
import dev.overhaul.minecraft.terrain.TerrainCullingController;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;
import java.util.Map;

@Mixin(LevelRenderer.class)
public abstract class TerrainCullingMixin implements TerrainCullingAccess {
    @Shadow @Final private LevelRenderState levelRenderState;
    @Shadow @Final private OptionsRenderState optionsRenderState;
    @Shadow private SectionRenderDispatcher sectionRenderDispatcher;
    @Unique private final TerrainCullingController overhaul$culling=new TerrainCullingController();
    @Override public TerrainCullingController overhaul$culling(){return overhaul$culling;}
    @Inject(method={"prepareChunkRenders","prepareChunkRendersIndirect"},at=@At("HEAD"))
    private void overhaul$prepare(Matrix4fc view,boolean sorted,CallbackInfoReturnable<ChunkSectionsToRender> callback){
        overhaul$culling.prepare(view,levelRenderState.cameraRenderState);
    }
    @ModifyExpressionValue(method="extractSectionDrawGroups",at=@At(value="FIELD",target="Lnet/minecraft/client/renderer/LevelRenderer;visibleSections:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> overhaul$filter(ObjectArrayList<SectionRenderDispatcher.RenderSection> original){
        return overhaul$culling.filter(original,sectionRenderDispatcher,optionsRenderState.chunkSectionFadeInTime);
    }
    @WrapOperation(method="extractSectionDrawGroups",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;getSectionMesh()Lnet/minecraft/client/renderer/chunk/SectionMesh;"))
    private SectionMesh overhaul$mesh(SectionRenderDispatcher.RenderSection section,Operation<SectionMesh> original){
        return overhaul$culling.mesh(section,original.call(section));
    }
    @WrapMethod(method="extractSectionDrawGroups")
    private int overhaul$scope(boolean sorted,List<DynamicGpuData.ChunkSectionInfo> infos,Map<?,?> groups,Operation<Integer> original){
        try{return original.call(sorted,infos,groups);}finally{overhaul$culling.clearFrame();}
    }
    @Inject(method="resetLevelRenderData",at=@At("HEAD"))
    private void overhaul$reset(CallbackInfo callback){overhaul$culling.reset();}
}
