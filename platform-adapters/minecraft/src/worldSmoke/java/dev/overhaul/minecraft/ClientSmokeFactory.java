package dev.overhaul.minecraft;

import dev.overhaul.adapter.ClientRuntime;
import dev.overhaul.minecraft.smoke.WorldSmokeController;
import net.minecraft.client.Minecraft;

/** Compiled only with -PworldSmoke; excluded from normal target artifacts. */
public final class ClientSmokeFactory {
    private ClientSmokeFactory() { }
    public static ClientSmokeSession create(Minecraft minecraft,ClientRuntime runtime,MinecraftSectionSource source) {
        if (!Boolean.getBoolean("overhaul.diagnostics.worldSmoke")) return null;
        return new WorldSmokeController(minecraft,runtime,source,ModelSmokeFactory.create());
    }
}
