package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Atmosphere answers every {@code isRainingAt}, twice per entity per tick in rain, by streaming its weather proxies
 * before the level-wide weather. Its own proxies are the local weather areas and Sable's projection of those same
 * areas, so with no local area defined none can answer, and the stream runs over nothing. Any proxy of another kind
 * keeps the full stream.
 */
@Pseudo
@Mixin(targets = "com.possible_triangle.atmosphere.impl.ServerLevelWeather", remap = false)
abstract class AtmosphereWeatherProxies121Mixin {
    @Unique private static final String LOCAL = "com.possible_triangle.atmosphere.impl.LocalWeather";
    @Unique private static final String SABLE = "com.possible_triangle.atmosphere.neoforge.compat.SableWeatherProxy";
    @Unique private Map<?, ?>[] overhaul$areas;
    @Unique private boolean overhaul$known;

    @ModifyExpressionValue(method = "atPosition", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lcom/possible_triangle/atmosphere/impl/ServerLevelWeather;proxies:Ljava/util/Set;"), require = 0)
    private Set<?> overhaul$skipEmptyAreas(Set<?> proxies) {
        if (!overhaul$known) {
            overhaul$areas = overhaul$areasOf(proxies);
            overhaul$known = true;
        }
        Map<?, ?>[] areas = overhaul$areas;
        if (areas == null) return proxies;
        for (Map<?, ?> area : areas) if (!area.isEmpty()) return proxies;
        return Set.of();
    }

    /** The area maps behind {@code proxies}, or null when a proxy could answer without one. */
    @Unique
    private static Map<?, ?>[] overhaul$areasOf(Set<?> proxies) {
        List<Map<?, ?>> areas = new ArrayList<>();
        for (Object proxy : proxies) {
            String type = proxy.getClass().getName();
            if (type.equals(LOCAL)) areas.add(((AtmosphereLocalWeather121Access) proxy).overhaul$providers());
            else if (!type.equals(SABLE)) return null;
        }
        return areas.isEmpty() ? null : areas.toArray(new Map<?, ?>[0]);
    }
}
