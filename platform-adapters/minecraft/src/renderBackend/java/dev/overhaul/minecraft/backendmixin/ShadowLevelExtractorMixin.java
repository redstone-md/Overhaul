package dev.overhaul.minecraft.backendmixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.minecraft.backend.OverhaulGpuDevice;
import dev.overhaul.minecraft.shadow.ShadowCasterAccess;
import dev.overhaul.minecraft.shader.ShaderPackConfiguration;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class ShadowLevelExtractorMixin {
    @Shadow @Final private LevelRenderer levelRenderer;
    @Shadow @Final private LevelRenderState levelRenderState;
    @Shadow private ClientLevel level;
    @WrapOperation(method="extract",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;visibleSections()Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"),require=1,allow=1)
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> overhaul$compileCasters(LevelRenderer renderer,
            Operation<ObjectArrayList<SectionRenderDispatcher.RenderSection>> original,DeltaTracker delta,Camera camera,float partial){
        var visible=original.call(renderer);var frame=((ShadowCasterAccess)renderer).overhaul$shadowFrame();
        var configuration=ShaderPackConfiguration.capture(Minecraft.getInstance());
        var scenes=RenderSystem.getDevice() instanceof OverhaulGpuDevice device?device.nativeFrames().shaderPacks():null;
        boolean known=scenes!=null&&scenes.matchesConfiguration(configuration);
        var settings=known?scenes.shadowSettings():null;
        boolean requested=scenes!=null&&configuration.configured()&&(!known||settings!=null);
        return frame.select(renderer.viewArea(),visible,settings,requested,camera.position());
    }
    @Inject(method="extract",at=@At("RETURN"))
    private void overhaul$extractCasters(DeltaTracker delta,Camera camera,float partial,CallbackInfo callback){
        ((ShadowCasterAccess)levelRenderer).overhaul$shadowFrame().extract(level,levelRenderer,delta,levelRenderState);
    }
    @Inject(method="setLevel",at=@At("HEAD"))
    private void overhaul$releaseCasters(ClientLevel next,CallbackInfo callback){((ShadowCasterAccess)levelRenderer).overhaul$shadowFrame().reset();}
}
