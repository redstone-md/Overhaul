package dev.overhaul.minecraft.blaze.mixin;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * LWJGL lists every device extension on its shared stack, and a current driver's list outgrows the 64 KiB a normal launch gives it.
 * The handler runs inside LWJGL's module, which can reach nothing of ours, so it makes a throwaway stack for the one list.
 */
@Mixin(value = VkInstance.class, remap = false)
abstract class VkInstanceStackMixin {
    @Redirect(
            method = "getAvailableDeviceExtensions",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/system/MemoryStack;stackPush()Lorg/lwjgl/system/MemoryStack;"),
            require = 1, allow = 1)
    private static MemoryStack overhaul$largeStack() {
        return MemoryStack.create(1024 * 1024).push();
    }
}
