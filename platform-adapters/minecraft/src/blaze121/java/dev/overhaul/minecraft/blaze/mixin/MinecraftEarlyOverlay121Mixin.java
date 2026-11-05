package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeEarlyWindow;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** NeoForge's overlay keeps painting the early screen's OpenGL framebuffer, which went with its window. */
@Mixin(Minecraft.class)
abstract class MinecraftEarlyOverlay121Mixin {
    @WrapOperation(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/fml/loading/ImmediateWindowHandler;loadingOverlay(Ljava/util/function/Supplier;Ljava/util/function/Supplier;Ljava/util/function/Consumer;Z)Ljava/util/function/Supplier;", remap = false),
            require = 1, allow = 1)
    private Supplier<LoadingOverlay> overhaul$plainOverlay(Supplier<Minecraft> client, Supplier<ReloadInstance> reload, Consumer<Optional<Throwable>> failed, boolean fade, Operation<Supplier<LoadingOverlay>> overlay) {
        if (!BlazeEarlyWindow.replaced()) return overlay.call(client, reload, failed, fade);
        return () -> new LoadingOverlay(client.get(), reload.get(), failed, fade);
    }
}
