package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeTickScan;
import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Fog colour and camera shake each look for their one marker entity within 48 blocks of the player, on every frame. */
@Pseudo
@Mixin(targets = "com.github.L_Ender.cataclysm.client.event.ClientEvent", remap = false)
abstract class CataclysmScans121Mixin {
    @Unique private static final BlazeTickScan OVERHAUL$SCAN = new BlazeTickScan();

    @WrapOperation(method = {"onComputFog", "onCameraSetup"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"), require = 0)
    private static <T> List<T> overhaul$oncePerTick(Level level, Class<T> type, AABB box, Operation<List<T>> scan) {
        return OVERHAUL$SCAN.of(level, type, () -> scan.call(level, type, box));
    }
}
