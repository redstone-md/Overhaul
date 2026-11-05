package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import dev.overhaul.minecraft.blaze.BlazeEarlyWindow;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import java.util.Optional;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Window.class)
abstract class WindowEarlyHandoff121Mixin {
    @Shadow @Final private long window;
    @Shadow private int width;
    @Shadow private int height;
    @Shadow private int x;
    @Shadow private int y;

    @WrapOperation(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/fml/loading/ImmediateWindowHandler;setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J", remap = false),
            require = 1, allow = 1)
    private long overhaul$windowWithoutContext(IntSupplier wide, IntSupplier high, Supplier<String> title, LongSupplier monitor, Operation<Long> setup) {
        long handed = setup.call(wide, high, title, monitor);
        if (!BlazeFrontend.requested()) return handed;
        return BlazeEarlyWindow.replace(handed, wide.getAsInt(), high.getAsInt(), title.get(), monitor.getAsLong());
    }

    /** The early screen reports where its window stood; the replacement goes to the same place. */
    @WrapOperation(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/fml/loading/ImmediateWindowHandler;positionWindow(Ljava/util/Optional;Ljava/util/function/IntConsumer;Ljava/util/function/IntConsumer;Ljava/util/function/IntConsumer;Ljava/util/function/IntConsumer;)Z", remap = false),
            require = 1, allow = 1)
    private boolean overhaul$placeReplacement(Optional<Object> monitor, IntConsumer wide, IntConsumer high, IntConsumer left, IntConsumer top, Operation<Boolean> position) {
        boolean positioned = position.call(monitor, wide, high, left, top);
        if (positioned && BlazeEarlyWindow.replaced() && GLFW.glfwGetWindowMonitor(window) == 0L) {
            GLFW.glfwSetWindowSize(window, width, height);
            GLFW.glfwSetWindowPos(window, x, y);
        }
        return positioned;
    }
}
