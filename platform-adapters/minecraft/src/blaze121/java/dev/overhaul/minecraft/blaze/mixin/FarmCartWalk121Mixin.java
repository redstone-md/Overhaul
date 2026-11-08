package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeEntityClasses;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Farm and Charm walks every client entity on every client tick to turn cart wheels. With no cart in the level the walk
 * finds nothing, and the wheels it kept for carts that are gone are still dropped after it, as they were before.
 */
@Pseudo
@Mixin(targets = "net.satisfy.farm_and_charm.client.event.ClientEventHandler", remap = false)
abstract class FarmCartWalk121Mixin {
    @Unique private static Class<?> overhaul$cart;
    @Unique private static boolean overhaul$looked;

    @WrapOperation(method = "onClientTick(Lnet/minecraft/client/Minecraft;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;entitiesForRendering()Ljava/lang/Iterable;"),
            require = 0)
    private static Iterable<Entity> overhaul$skipWithoutCarts(ClientLevel level, Operation<Iterable<Entity>> all) {
        if (!overhaul$looked) {
            overhaul$looked = true;
            try {
                overhaul$cart = Class.forName("net.satisfy.farm_and_charm.core.entity.AbstractCartEntity");
            } catch (ClassNotFoundException | LinkageError missing) {
                overhaul$cart = null;
            }
        }
        if (overhaul$cart != null && BlazeEntityClasses.absent(level, ((LevelEntities121Access) level).overhaul$entities(), overhaul$cart)) return List.of();
        return all.call(level);
    }
}