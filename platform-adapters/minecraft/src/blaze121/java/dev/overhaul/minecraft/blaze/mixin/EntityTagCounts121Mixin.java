package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeEntityTags;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every entity's command tags live in a set that keeps {@link BlazeEntityTags}' counts while it is not removed. */
@Mixin(Entity.class)
abstract class EntityTagCounts121Mixin {
    @Shadow @Final private Set<String> tags;

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/google/common/collect/Sets;newHashSet()Ljava/util/HashSet;"), require = 1)
    private HashSet<?> overhaul$countedTags(Operation<HashSet<?>> create) {
        return new BlazeEntityTags.TagSet();
    }

    @Inject(method = "setRemoved", at = @At("TAIL"))
    private void overhaul$tagsRemoved(Entity.RemovalReason reason, CallbackInfo callback) {
        if (tags instanceof BlazeEntityTags.TagSet counted) counted.live(false);
    }

    @Inject(method = "unsetRemoved", at = @At("TAIL"))
    private void overhaul$tagsBack(CallbackInfo callback) {
        if (tags instanceof BlazeEntityTags.TagSet counted) counted.live(true);
    }
}
