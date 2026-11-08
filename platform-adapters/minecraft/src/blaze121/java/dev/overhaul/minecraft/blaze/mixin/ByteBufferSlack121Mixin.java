package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import dev.overhaul.minecraft.blaze.BlazeBufferSlack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps reserved but unwritten bytes at the end of the buffer and hands them back before any reserve, build, clear,
 * discard or close, so nothing else ever sees them.
 */
@Mixin(ByteBufferBuilder.class)
abstract class ByteBufferSlack121Mixin implements BlazeBufferSlack {
    @Shadow private long pointer;
    @Shadow private int writeOffset;
    @Shadow public abstract long reserve(int bytes);

    @Unique private int overhaul$slack;

    @Override
    public long overhaul$reserveAhead(int bytes, int ahead) {
        int slack = overhaul$slack;
        if (slack >= bytes) {
            overhaul$slack = slack - bytes;
            return pointer + writeOffset - slack;
        }
        long at = reserve(bytes + ahead);
        overhaul$slack = ahead;
        return at;
    }

    @Inject(method = "reserve", at = @At("HEAD"), require = 1)
    private void overhaul$giveBackOnReserve(int bytes, CallbackInfoReturnable<Long> callback) {
        overhaul$giveBack();
    }

    @Inject(method = "build", at = @At("HEAD"), require = 1)
    private void overhaul$giveBackOnBuild(CallbackInfoReturnable<ByteBufferBuilder.Result> callback) {
        overhaul$giveBack();
    }

    @Inject(method = {"clear", "discard", "close"}, at = @At("HEAD"), require = 3)
    private void overhaul$giveBackOnReset(CallbackInfo callback) {
        overhaul$giveBack();
    }

    @Unique
    private void overhaul$giveBack() {
        if (overhaul$slack != 0) {
            writeOffset -= overhaul$slack;
            overhaul$slack = 0;
        }
    }
}
