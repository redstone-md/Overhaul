package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.systems.TimerQuery;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import java.util.Optional;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native timestamp queries are optional; never initialize the OpenGL timer loader in owned mode. */
@Mixin(TimerQuery.class)
public abstract class TimerQueryBlazeMixin {
    @Inject(method="getInstance()Ljava/util/Optional;",at=@At("HEAD"),cancellable=true,require=1)
    private static void overhaul$nativeTimer(CallbackInfoReturnable<Optional<TimerQuery>> callback){
        if(BlazeFrontend.current()!=null)callback.setReturnValue(Optional.empty());
    }
}
