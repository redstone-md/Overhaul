package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeTickScan;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Screen shake looks for shaking mobs within 64 blocks of the player on every frame. */
@Pseudo
@Mixin(targets = "com.github.alexmodguy.alexscaves.client.event.ClientEvents", remap = false)
abstract class AlexsCavesScans121Mixin {
    @Unique private final BlazeTickScan overhaul$scan = new BlazeTickScan();

    @WrapOperation(method = "computeCameraAngles", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"), require = 0)
    private <T> List<T> overhaul$oncePerTick(ClientLevel level, Class<T> type, AABB box, Predicate<? super T> filter, Operation<List<T>> scan) {
        return overhaul$scan.of(level, type, () -> scan.call(level, type, box, filter));
    }
}
