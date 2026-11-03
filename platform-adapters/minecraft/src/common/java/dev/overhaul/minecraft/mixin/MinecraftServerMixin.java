package dev.overhaul.minecraft.mixin;

import dev.overhaul.adapter.ServerRuntime;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Separate server lifecycle; no renderer/native/client references occur in this class. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Unique private ServerRuntime overhaul$runtime;

    @Inject(method="tickServer(Ljava/util/function/BooleanSupplier;)V",at=@At("HEAD"),require=1)
    private void overhaul$tick(CallbackInfo callback) {
        if (overhaul$runtime==null) {
            overhaul$runtime=new ServerRuntime(1024,500_000);
            java.util.logging.Logger.getLogger("overhaul").info("Overhaul server tick hook active; cooperative budget=500000ns");
        }
        overhaul$runtime.tick();
    }

    @Inject(method="stopServer()V",at=@At("HEAD"),require=1)
    private void overhaul$close(CallbackInfo callback) {
        if (overhaul$runtime!=null) overhaul$runtime.close();
    }
}
