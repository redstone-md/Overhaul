package dev.overhaul.minecraft.backendmixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.overhaul.minecraft.terrain.TerrainCullingAccess;
import dev.overhaul.minecraft.terrain.TerrainCullingController;
import dev.overhaul.minecraft.particle.OverhaulQuadParticleRenderer;
import dev.overhaul.minecraft.particle.ParticleRendererAccess;
import net.minecraft.client.GameLoadCookie;
import net.minecraft.client.Minecraft;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Minecraft.class)
public abstract class ShaderReloadMixin {
    @WrapMethod(method="reloadResourcePacks(ZLnet/minecraft/client/GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;")
    private CompletableFuture<Void> overhaul$reload(boolean force,GameLoadCookie cookie,Operation<CompletableFuture<Void>> original){
        Minecraft minecraft=(Minecraft)(Object)this;
        var controller=minecraft.levelRenderer instanceof TerrainCullingAccess access?access.overhaul$culling():null;
        var features=minecraft.gameRenderer==null?null:minecraft.gameRenderer.featureRenderDispatcher();
        var particles=features instanceof ParticleRendererAccess access?access.overhaul$particles():null;
        long terrainToken=controller==null?0:controller.reloadStarted();
        long particleToken=particles==null?0:particles.reloadStarted();
        try {
            var future=original.call(force,cookie);
            if(controller!=null||particles!=null)future.whenComplete((ignored,failure)->minecraft.execute(
                    ()->overhaul$finish(controller,particles,terrainToken,particleToken,failure==null)));
            return future;
        }catch(RuntimeException|Error failure){overhaul$finish(controller,particles,terrainToken,particleToken,false);throw failure;}
    }
    @Unique private static void overhaul$finish(TerrainCullingController terrain,OverhaulQuadParticleRenderer particles,
            long terrainToken,long particleToken,boolean success){
        if(terrain!=null)terrain.reloadFinished(terrainToken,success);
        if(particles!=null)particles.reloadFinished(particleToken,success);
    }
}
