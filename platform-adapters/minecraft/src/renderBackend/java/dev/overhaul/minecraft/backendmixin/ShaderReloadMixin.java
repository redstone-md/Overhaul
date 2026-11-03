package dev.overhaul.minecraft.backendmixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.overhaul.minecraft.terrain.TerrainCullingAccess;
import dev.overhaul.minecraft.terrain.TerrainCullingController;
import dev.overhaul.minecraft.particle.OverhaulQuadParticleRenderer;
import dev.overhaul.minecraft.particle.ParticleRendererAccess;
import dev.overhaul.minecraft.weather.OverhaulWeatherRenderer;
import dev.overhaul.minecraft.model.OverhaulModelRenderer;
import dev.overhaul.minecraft.model.ModelRendererAccess;
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
        var models=features instanceof ModelRendererAccess access?access.overhaul$models():null;
        var weather=minecraft.levelRenderer!=null&&minecraft.levelRenderer.weatherEffectRenderer() instanceof OverhaulWeatherRenderer owned?owned:null;
        long terrainToken=controller==null?0:controller.reloadStarted();
        long particleToken=particles==null?0:particles.reloadStarted();
        long weatherToken=weather==null?0:weather.reloadStarted();
        long modelToken=models==null?0:models.reloadStarted();
        try {
            var future=original.call(force,cookie);
            if(controller!=null||particles!=null||weather!=null||models!=null)future.whenComplete((ignored,failure)->minecraft.execute(
                    ()->overhaul$finish(controller,particles,weather,models,terrainToken,particleToken,weatherToken,modelToken,failure==null)));
            return future;
        }catch(RuntimeException|Error failure){overhaul$finish(controller,particles,weather,models,terrainToken,particleToken,weatherToken,modelToken,false);throw failure;}
    }
    @Unique private static void overhaul$finish(TerrainCullingController terrain,OverhaulQuadParticleRenderer particles,OverhaulWeatherRenderer weather,OverhaulModelRenderer models,
            long terrainToken,long particleToken,long weatherToken,long modelToken,boolean success){
        if(terrain!=null)terrain.reloadFinished(terrainToken,success);
        if(particles!=null)particles.reloadFinished(particleToken,success);
        if(weather!=null)weather.reloadFinished(weatherToken,success);
        if(models!=null)models.reloadFinished(modelToken,success);
    }
}
