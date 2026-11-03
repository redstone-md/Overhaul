package dev.overhaul.minecraft.mixin;

import dev.overhaul.adapter.ClientRuntime;
import dev.overhaul.adapter.SectionKey;
import dev.overhaul.minecraft.MinecraftSectionSource;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Instance-owned lifecycle hook. Vanilla rendering and gameplay remain authoritative. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Unique private ClientRuntime overhaul$runtime;
    @Unique private MinecraftSectionSource overhaul$source;
    @Unique private long overhaul$reportedMeshes;
    @Unique private long overhaul$stopAfterTicks;

    @Inject(method="tick()V",at=@At("HEAD"),require=1)
    private void overhaul$tick(CallbackInfo callback) {
        if (overhaul$runtime==null) {
            overhaul$runtime=new ClientRuntime(Boolean.getBoolean("overhaul.diagnostics.enable"));
            overhaul$source=new MinecraftSectionSource((Minecraft)(Object)this);
            overhaul$stopAfterTicks=Long.getLong("overhaul.diagnostics.stopAfterTicks",0L);
            if (overhaul$runtime.enabled()) java.util.logging.Logger.getLogger("overhaul").info(
                    "Overhaul diagnostic client hook active; vanilla rendering remains enabled");
        }
        overhaul$runtime.tick();
        if (overhaul$stopAfterTicks>0 && overhaul$runtime.tickCount()>=overhaul$stopAfterTicks) {
            java.util.logging.Logger.getLogger("overhaul").info("Overhaul client smoke reached ticks="
                    +overhaul$runtime.tickCount()+"; requesting orderly shutdown");
            ((Minecraft)(Object)this).stop();
            return;
        }
        if (!overhaul$runtime.enabled()) return;
        Throwable failure=overhaul$runtime.takeFailure();
        if (failure!=null) java.util.logging.Logger.getLogger("overhaul").log(
                java.util.logging.Level.WARNING,"Overhaul diagnostic chunk work failed",failure);
        Minecraft minecraft=(Minecraft)(Object)this;
        if (minecraft.level==null || minecraft.player==null) { overhaul$runtime.leaveWorld(); return; }
        if (overhaul$runtime.tickCount()%100==0) {
            if (overhaul$runtime.acceptedMeshes()!=overhaul$reportedMeshes) {
                overhaul$reportedMeshes=overhaul$runtime.acceptedMeshes();
                java.util.logging.Logger.getLogger("overhaul").info("Overhaul diagnostic accepted meshes="+overhaul$reportedMeshes);
            }
            SectionKey key=MinecraftSectionSource.playerSection(minecraft);
            if (overhaul$source.haloLoaded(key)) overhaul$runtime.capture(minecraft.level,key,overhaul$source);
        }
    }

    @Inject(method="close()V",at=@At("HEAD"),require=1,remap=false)
    private void overhaul$close(CallbackInfo callback) {
        if (overhaul$runtime!=null) overhaul$runtime.close();
    }
}
