package dev.overhaul.minecraft.blaze.shader;

import dev.overhaul.shader.pack.PackBiomeCategory;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

/** The biome under the camera as expression values. Looked up only for packs that read one, once per frame. */
final class BlazePackBiome {
    private final Map<String, Double> identifiers = new HashMap<>();
    private Object registryOwner;
    /** Weak so a left world is not kept alive while the menu shows. */
    private java.lang.ref.WeakReference<Object> identifierLevel = new java.lang.ref.WeakReference<>(null);
    private Minecraft minecraft;
    private boolean fresh;
    private double id, category, precipitation, temperature, rainfall;

    void invalidate(Minecraft minecraft) {
        this.minecraft = minecraft;
        fresh = false;
    }

    /** NaN marks a name that is not a biome value, so the caller can report it. */
    double read(String name) {
        if (name.startsWith("BIOME_")) return identifier(name);
        return switch (name) {
            case "biome" -> {
                refresh();
                yield id;
            }
            case "biome_category" -> {
                refresh();
                yield category;
            }
            case "biome_precipitation" -> {
                refresh();
                yield precipitation;
            }
            case "temperature" -> {
                refresh();
                yield temperature;
            }
            case "rainfall" -> {
                refresh();
                yield rainfall;
            }
            default -> Double.NaN;
        };
    }

    private void refresh() {
        if (fresh) return;
        fresh = true;
        id = category = precipitation = temperature = rainfall = 0;
        var level = minecraft.level;
        var camera = minecraft.getCameraEntity();
        if (level == null || camera == null) return;
        BlockPos position = camera.blockPosition();
        Holder<Biome> holder = level.getBiome(position);
        Biome biome = holder.value();
        id = level.registryAccess().registryOrThrow(Registries.BIOME).getId(biome);
        temperature = biome.getBaseTemperature();
        rainfall = BlazePackGame.downfall(biome);
        Biome.Precipitation fall = biome.getPrecipitationAt(position);
        precipitation = fall == Biome.Precipitation.NONE ? 0 : fall == Biome.Precipitation.RAIN ? 1 : 2;
        category = category(holder, fall).ordinal();
    }

    private static PackBiomeCategory category(Holder<Biome> biome, Biome.Precipitation fall) {
        if (biome.is(BiomeTags.IS_NETHER)) return PackBiomeCategory.NETHER;
        if (biome.is(BiomeTags.IS_END)) return PackBiomeCategory.THE_END;
        if (biome.is(Biomes.LUSH_CAVES) || biome.is(Biomes.DRIPSTONE_CAVES) || biome.is(Biomes.DEEP_DARK)) return PackBiomeCategory.UNDERGROUND;
        if (biome.is(BiomeTags.IS_OCEAN)) return PackBiomeCategory.OCEAN;
        if (biome.is(BiomeTags.IS_RIVER)) return PackBiomeCategory.RIVER;
        if (biome.is(BiomeTags.IS_BEACH)) return PackBiomeCategory.BEACH;
        if (biome.is(BiomeTags.IS_BADLANDS)) return PackBiomeCategory.MESA;
        if (biome.is(BiomeTags.IS_JUNGLE)) return PackBiomeCategory.JUNGLE;
        if (biome.is(BiomeTags.IS_SAVANNA)) return PackBiomeCategory.SAVANNA;
        if (biome.is(BiomeTags.IS_TAIGA)) return PackBiomeCategory.TAIGA;
        if (biome.is(BiomeTags.IS_FOREST)) return PackBiomeCategory.FOREST;
        if (biome.is(BiomeTags.IS_MOUNTAIN)) return PackBiomeCategory.MOUNTAIN;
        if (biome.is(BiomeTags.IS_HILL)) return PackBiomeCategory.EXTREME_HILLS;
        if (biome.is(Biomes.DESERT)) return PackBiomeCategory.DESERT;
        if (biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP)) return PackBiomeCategory.SWAMP;
        if (biome.is(Biomes.MUSHROOM_FIELDS)) return PackBiomeCategory.MUSHROOM;
        if (fall == Biome.Precipitation.SNOW) return PackBiomeCategory.ICY;
        if (biome.is(Biomes.PLAINS) || biome.is(Biomes.SUNFLOWER_PLAINS)) return PackBiomeCategory.PLAINS;
        return PackBiomeCategory.NONE;
    }

    /** BIOME_PLAINS names a registry id. A biome this world lacks reads -1, which no camera biome can equal. */
    private double identifier(String name) {
        var level = minecraft.level;
        if (level == null) return -1;
        // A level keeps its registries, so the registry lookup runs once per level rather than per uniform.
        if (level != identifierLevel.get()) {
            identifierLevel = new java.lang.ref.WeakReference<>(level);
            var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
            if (registry != registryOwner) fill(registry);
        }
        return identifiers.getOrDefault(name, -1.0);
    }

    private void fill(net.minecraft.core.Registry<Biome> registry) {
        registryOwner = registry;
        identifiers.clear();
        registry.entrySet().forEach(entry -> {
            String path = entry.getKey().location().getPath().toUpperCase(Locale.ROOT).replace('/', '_');
            identifiers.putIfAbsent("BIOME_" + path, (double) registry.getId(entry.getValue()));
        });
    }
}
