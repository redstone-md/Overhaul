package dev.overhaul.minecraft;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.minecraft.backend.OverhaulGpuDevice;
import dev.overhaul.minecraft.smoke.WorldSmokeProbe;

/** This source variant keeps the disposable world alive until its actual game frame is captured. */
public final class ModelSmokeFactory {
    private ModelSmokeFactory() {}
    public static WorldSmokeProbe create() {
        return (minecraft,section)-> {
            if (!(RenderSystem.getDevice() instanceof OverhaulGpuDevice device)) {
                throw new IllegalStateException("World backend smoke requires the actual Overhaul device");
            }
            var captured=device.awaitWorldCapture();
            var id=minecraft.player.getUUID();
            var server=minecraft.getSingleplayerServer();
            return server.submit(()-> {
                if (!server.isSameThread()) throw new IllegalStateException("Camera placement requires the server owner");
                var player=server.getPlayerList().getPlayer(id);
                if (player==null||!player.teleportTo(player.level(),6.5,4,-5.5,java.util.Set.of(),0,7,true)) {
                    throw new IllegalStateException("Cannot place the smoke camera at its fixture reference pose");
                }
            }).thenCompose(ignored->captured);
        };
    }
}
