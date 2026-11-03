package dev.overhaul.minecraft.backendmixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.weather.OverhaulWeatherRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The normal LevelRenderer owner also closes the replacement renderer. */
@Mixin(LevelRenderer.class)
public abstract class InstancedWeatherRendererMixin {
    @Shadow public abstract WeatherEffectRenderer weatherEffectRenderer();
    @WrapOperation(method="<init>",at=@At(value="NEW",target="()Lnet/minecraft/client/renderer/WeatherEffectRenderer;"))
    private WeatherEffectRenderer overhaul$weather(Operation<WeatherEffectRenderer> original){return new OverhaulWeatherRenderer();}
    @Inject(method="resetLevelRenderData",at=@At("HEAD"))
    private void overhaul$resetWeather(CallbackInfo callback){
        if(weatherEffectRenderer() instanceof OverhaulWeatherRenderer owned)owned.resetFrame();
    }
}
