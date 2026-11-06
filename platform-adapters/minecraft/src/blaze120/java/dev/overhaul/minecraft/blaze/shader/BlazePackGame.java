package dev.overhaul.minecraft.blaze.shader;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.biome.Biome;

/** The few game calls the shared pack frame makes that changed between game versions. */
final class BlazePackGame {
    private static Biome lastBiome;
    private static float lastDownfall;

    private BlazePackGame() {}

    /** How far an effect with a fade, such as darkness, has faded in. */
    static float blend(MobEffectInstance effect, LivingEntity living, float partial) {
        return effect.getFactorData().map(factor -> factor.getFactor(living, partial)).orElse(0F);
    }

    /** The game keeps downfall private; the network codec writes it out. The camera's biome rarely changes, so one entry is enough. */
    static float downfall(Biome biome) {
        if (biome != lastBiome) {
            lastDownfall = Biome.NETWORK_CODEC.encodeStart(JsonOps.INSTANCE, biome).result()
                    .filter(JsonElement::isJsonObject).map(json -> json.getAsJsonObject().get("downfall"))
                    .map(JsonElement::getAsFloat).orElse(0.5F);
            lastBiome = biome;
        }
        return lastDownfall;
    }
}
