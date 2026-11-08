package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeEntityClasses;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Wither Reincarnated rebuilds its boss music map on every client tick by walking every client entity for withers.
 * With none in the level the walk adds nothing, so the cleared map and the fade-out that follow stay the same.
 */
@Pseudo
@Mixin(targets = "com.alexander.witherreincarnated.events.HandleWitherMusicEvent", remap = false)
abstract class WitherMusicScan121Mixin {
    @WrapOperation(method = "tickMusic(Lnet/neoforged/neoforge/client/event/ClientTickEvent$Pre;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;entitiesForRendering()Ljava/lang/Iterable;"),
            require = 0)
    private static Iterable<Entity> overhaul$skipWithoutWithers(ClientLevel level, Operation<Iterable<Entity>> all) {
        if (BlazeEntityClasses.absent(level, ((LevelEntities121Access) level).overhaul$entities(), WitherBoss.class)) return List.of();
        return all.call(level);
    }
}
