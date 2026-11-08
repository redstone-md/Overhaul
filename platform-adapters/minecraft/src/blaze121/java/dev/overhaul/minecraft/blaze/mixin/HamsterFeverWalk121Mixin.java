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
 * Adorable Hamster Pets walks every client entity on every client tick to start breathing sounds for feverish
 * hamsters. With no hamster in the level the walk starts nothing, and stopped sounds are still cleared before it.
 */
@Pseudo
@Mixin(targets = "net.dawson.adorablehamsterpets.client.sound.HamsterFeverBreathingSoundManager", remap = false)
abstract class HamsterFeverWalk121Mixin {
    @Unique private Class<?> overhaul$hamster;
    @Unique private boolean overhaul$looked;

    @WrapOperation(method = "tick(Lnet/minecraft/client/Minecraft;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;entitiesForRendering()Ljava/lang/Iterable;"),
            require = 0)
    private Iterable<Entity> overhaul$skipWithoutHamsters(ClientLevel level, Operation<Iterable<Entity>> all) {
        if (!overhaul$looked) {
            overhaul$looked = true;
            try {
                overhaul$hamster = Class.forName("net.dawson.adorablehamsterpets.entity.custom.HamsterEntity", false, getClass().getClassLoader());
            } catch (ClassNotFoundException | LinkageError missing) {
                overhaul$hamster = null;
            }
        }
        if (overhaul$hamster != null && BlazeEntityClasses.absent(level, ((LevelEntities121Access) level).overhaul$entities(), overhaul$hamster)) return List.of();
        return all.call(level);
    }
}
