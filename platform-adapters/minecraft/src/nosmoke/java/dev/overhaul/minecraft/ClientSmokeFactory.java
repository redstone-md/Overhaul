package dev.overhaul.minecraft;

import dev.overhaul.adapter.ClientRuntime;
import net.minecraft.client.Minecraft;

/** Production source variant contains no world-creation smoke implementation. */
public final class ClientSmokeFactory {
    private ClientSmokeFactory() { }
    public static ClientSmokeSession create(Minecraft minecraft,ClientRuntime runtime,MinecraftSectionSource source) {
        return null;
    }
}
