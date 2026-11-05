package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeEarlyWindow;
import net.neoforged.neoforge.client.loading.ClientModLoader;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** The early screen's tick repaints through OpenGL on a window that no longer exists; only its event pump is kept. */
@Mixin(value = ClientModLoader.class, remap = false)
abstract class ClientModLoaderEarlyTick121Mixin {
    @ModifyArg(
            method = "begin(Lnet/minecraft/client/Minecraft;Lnet/minecraft/server/packs/repository/PackRepository;Lnet/minecraft/server/packs/resources/ReloadableResourceManager;)V",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/loading/ClientModLoader;begin(Ljava/lang/Runnable;Z)V"),
            index = 0, require = 1, allow = 1)
    private static Runnable overhaul$pumpOnly(Runnable tick) {
        return BlazeEarlyWindow.replaced() ? GLFW::glfwPollEvents : tick;
    }
}
