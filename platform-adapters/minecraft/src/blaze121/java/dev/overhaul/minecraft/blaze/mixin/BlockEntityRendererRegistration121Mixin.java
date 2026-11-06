package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeRegistrationLock;
import java.util.Map;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Block entity renderers take turns as {@link RendererRegistration121Mixin} explains; a mixin per class keeps each target exact. */
@Mixin(BlockEntityRenderers.class)
abstract class BlockEntityRendererRegistration121Mixin {
    @WrapOperation(method = "register", at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private static Object overhaul$oneAtATime(Map<Object, Object> providers, Object type, Object provider, Operation<Object> put) {
        return BlazeRegistrationLock.put(providers, type, provider, put);
    }
}
