package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.lucko.spark.common.sampler.Sampler;
import me.lucko.spark.common.sampler.SamplerContainer;
import me.lucko.spark.common.sampler.java.JavaSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * spark keeps a profiler running in the background of the integrated server. Where its async engine is unavailable,
 * as on Windows, it samples with its Java engine, which dumps thread stacks at a global safepoint every ten
 * milliseconds and so halts the render thread and every worker a hundred times a second. Such a background sampler is
 * stopped as soon as spark starts it; profiling started by a command is untouched.
 */
@Pseudo
@Mixin(targets = "me.lucko.spark.common.sampler.BackgroundSamplerManager", remap = false)
abstract class SparkBackgroundSampler121Mixin {
    @WrapOperation(method = "startSampler", require = 0, at = @At(value = "INVOKE",
        target = "Lme/lucko/spark/common/sampler/SamplerContainer;setActiveSampler(Lme/lucko/spark/common/sampler/Sampler;)V"))
    private void overhaul$skipSafepointSampler(SamplerContainer container, Sampler sampler, Operation<Void> set) {
        if (sampler instanceof JavaSampler) sampler.stop(true);
        else set.call(container, sampler);
    }
}
