package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Map;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.entity.EntityRenderers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Mods call these from client setup, which NeoForge runs in parallel, on plain maps. Two registrations resizing a map
 * together end the launch with an index error, or lose a renderer without a word, so the writes take turns. The map is
 * the lock because vanilla registers from its static initializer, before any field a mixin adds would hold a value.
 */
@Mixin({EntityRenderers.class, BlockEntityRenderers.class})
abstract class RendererRegistration121Mixin {
    @WrapOperation(method = "register", at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private static Object overhaul$oneAtATime(Map<Object, Object> providers, Object type, Object provider, Operation<Object> put) {
        synchronized (providers) {
            return put.call(providers, type, provider);
        }
    }
}
