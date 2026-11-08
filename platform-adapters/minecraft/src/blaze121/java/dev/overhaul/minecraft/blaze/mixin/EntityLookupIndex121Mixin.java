package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeLookupIndex;
import dev.overhaul.minecraft.blaze.BlazeLookupIndexed;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Each entity lookup keeps its entities by command tag and type, entered as they join its id map and dropped as they leave. */
@Mixin(EntityLookup.class)
abstract class EntityLookupIndex121Mixin implements BlazeLookupIndexed {
    @Unique private final BlazeLookupIndex overhaul$index = new BlazeLookupIndex();

    @Override
    public BlazeLookupIndex overhaul$index() {
        return overhaul$index;
    }

    @Inject(method = "add", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;put(ILjava/lang/Object;)Ljava/lang/Object;",
            shift = At.Shift.AFTER), require = 1)
    private void overhaul$entered(EntityAccess entity, CallbackInfo callback) {
        if (entity instanceof Entity indexed) overhaul$index.entered(indexed);
    }

    @Inject(method = "remove", at = @At("HEAD"), require = 1)
    private void overhaul$left(EntityAccess entity, CallbackInfo callback) {
        if (entity instanceof Entity indexed) overhaul$index.left(indexed);
    }
}