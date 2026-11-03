package dev.overhaul.minecraft.backendmixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.overhaul.minecraft.terrain.TerrainCullingAccess;
import net.minecraft.client.GameLoadCookie;
import net.minecraft.client.Minecraft;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Minecraft.class)
public abstract class TerrainReloadMixin {
    @WrapMethod(method="reloadResourcePacks(ZLnet/minecraft/client/GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;")
    private CompletableFuture<Void> overhaul$reload(boolean force,GameLoadCookie cookie,Operation<CompletableFuture<Void>> original){
        Minecraft minecraft=(Minecraft)(Object)this;
        var controller=minecraft.levelRenderer instanceof TerrainCullingAccess access?access.overhaul$culling():null;
        long token=controller==null?0:controller.reloadStarted();
        try {
            var future=original.call(force,cookie);
            if(controller!=null)future.whenComplete((ignored,failure)->minecraft.execute(()->controller.reloadFinished(token,failure==null)));
            return future;
        }catch(RuntimeException|Error failure){if(controller!=null)controller.reloadFinished(token,false);throw failure;}
    }
}
