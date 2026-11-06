package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.minecraft.blaze.BlazeTickAnswers;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The camellia head layer asks the curios inventory of every living entity on every frame whether it wears the flower. */
@Pseudo
@Mixin(targets = "dev.xkmc.youkaishomecoming.compat.curios.CuriosManager", remap = false)
abstract class YoukaiHeadCurios121Mixin {
    @Unique private static final BlazeTickAnswers OVERHAUL$ANSWERS = new BlazeTickAnswers();

    @Inject(method = "hasHead", at = @At("HEAD"), cancellable = true, require = 0)
    private static void overhaul$known(LivingEntity entity, Item item, boolean visible, CallbackInfoReturnable<Boolean> callback) {
        if (!RenderSystem.isOnRenderThread()) return;
        Boolean known = OVERHAUL$ANSWERS.known(entity, item, visible);
        if (known != null) callback.setReturnValue(known);
    }

    @Inject(method = "hasHead", at = @At("RETURN"), require = 0)
    private static void overhaul$remember(LivingEntity entity, Item item, boolean visible, CallbackInfoReturnable<Boolean> callback) {
        if (RenderSystem.isOnRenderThread()) OVERHAUL$ANSWERS.remember(entity, item, visible, callback.getReturnValueZ());
    }
}
