package dev.overhaul.minecraft.blaze.mixin;

import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The local weather areas Atmosphere keeps per level, for {@link AtmosphereWeatherProxies121Mixin}. */
@Pseudo
@Mixin(targets = "com.possible_triangle.atmosphere.impl.LocalWeather", remap = false)
public interface AtmosphereLocalWeather121Access {
    @Accessor("providers")
    Map<?, ?> overhaul$providers();
}
