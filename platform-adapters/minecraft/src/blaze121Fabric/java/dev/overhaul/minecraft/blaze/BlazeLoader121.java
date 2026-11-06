package dev.overhaul.minecraft.blaze;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.biome.Biome;

/** Fabric adds nothing to vanilla here: no custom outlines and no biome modifiers. */
public final class BlazeLoader121 {
    private static Biome lastBiome;
    private static float lastDownfall;

    private BlazeLoader121() {
    }

    public static boolean customOutline(Entity entity, Player viewer) {
        return false;
    }

    /** Vanilla keeps downfall private; the network codec writes it out. The camera's biome rarely changes, so one entry is enough. */
    public static float downfall(Biome biome) {
        if (biome != lastBiome) {
            lastDownfall = Biome.NETWORK_CODEC.encodeStart(JsonOps.INSTANCE, biome).result()
                    .filter(JsonElement::isJsonObject).map(json -> json.getAsJsonObject().get("downfall"))
                    .map(JsonElement::getAsFloat).orElse(0.5F);
            lastBiome = biome;
        }
        return lastDownfall;
    }
}
