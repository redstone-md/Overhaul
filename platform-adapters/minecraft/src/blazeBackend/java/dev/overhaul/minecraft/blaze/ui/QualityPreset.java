package dev.overhaul.minecraft.blaze.ui;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Options;
import net.minecraft.client.ParticleStatus;

/**
 * Named sets of the game's own video options, lowest first. A preset only writes options the video page already
 * shows, so picking one and then changing a row by hand leaves a custom setup the page can still describe.
 */
enum QualityPreset {
    ULTRA_LOW("Ultra low", "Integrated graphics and old laptops. Short view, flat leaves, almost no particles",
            4, 5, GraphicsStatus.FAST, CloudStatus.OFF, false, ParticleStatus.MINIMAL, 0, false, 50, 0),
    LOW("Low", "Entry graphics cards. Close view, flat leaves, fewer particles",
            8, 6, GraphicsStatus.FAST, CloudStatus.OFF, true, ParticleStatus.DECREASED, 1, false, 75, 2),
    OPTIMIZED("Optimized", "The usual choice for most players. Fancy leaves and smooth lighting at a moderate distance; clouds, entity shadows and extra particles that cost frames and add little are off",
            12, 6, GraphicsStatus.FANCY, CloudStatus.OFF, true, ParticleStatus.DECREASED, 2, false, 100, 4),
    MEDIUM("Medium", "Mid-range cards. Fancy leaves and clouds at a moderate distance",
            12, 8, GraphicsStatus.FANCY, CloudStatus.FAST, true, ParticleStatus.ALL, 2, true, 100, 4),
    HIGH("High", "Recent cards. Long view, fancy clouds, wider biome blending",
            20, 10, GraphicsStatus.FANCY, CloudStatus.FANCY, true, ParticleStatus.ALL, 3, true, 150, 4),
    ULTRA("Ultra", "Fast cards with plenty of memory. Furthest view, entities drawn far away, smoothest blending",
            32, 12, GraphicsStatus.FANCY, CloudStatus.FANCY, true, ParticleStatus.ALL, 5, true, 250, 4);

    final String label;
    final String description;
    private final int renderDistance, simulationDistance, biomeBlend, entityDistance, mipmaps;
    private final GraphicsStatus graphics;
    private final CloudStatus clouds;
    private final ParticleStatus particles;
    private final boolean ambientOcclusion, entityShadows;

    QualityPreset(String label, String description, int renderDistance, int simulationDistance, GraphicsStatus graphics, CloudStatus clouds,
                  boolean ambientOcclusion, ParticleStatus particles, int biomeBlend, boolean entityShadows, int entityDistance, int mipmaps) {
        this.label = label;
        this.description = description;
        this.renderDistance = renderDistance;
        this.simulationDistance = simulationDistance;
        this.graphics = graphics;
        this.clouds = clouds;
        this.ambientOcclusion = ambientOcclusion;
        this.particles = particles;
        this.biomeBlend = biomeBlend;
        this.entityShadows = entityShadows;
        this.entityDistance = entityDistance;
        this.mipmaps = mipmaps;
    }

    /** The furthest distance the sliders offer; a small heap cannot hold the chunks of a longer view. */
    static int maxDistance() {
        return Runtime.getRuntime().maxMemory() >= 1_000_000_000L ? 32 : 16;
    }

    int renderDistance() {
        return Math.min(renderDistance, maxDistance());
    }

    private int simulationDistance() {
        return Math.min(simulationDistance, maxDistance());
    }

    /** @param textures whether to set mipmaps too, which reloads every texture when the settings screen closes */
    void apply(Options options, boolean textures) {
        options.renderDistance().set(renderDistance());
        options.simulationDistance().set(simulationDistance());
        if (options.graphicsMode().get() != graphics) options.graphicsMode().set(graphics);
        options.cloudStatus().set(clouds);
        if (options.ambientOcclusion().get() != ambientOcclusion) options.ambientOcclusion().set(ambientOcclusion);
        options.particles().set(particles);
        if (options.biomeBlendRadius().get() != biomeBlend) options.biomeBlendRadius().set(biomeBlend);
        options.entityShadows().set(entityShadows);
        options.entityDistanceScaling().set(entityDistance / 100.0);
        if (textures) options.mipmapLevels().set(mipmaps);
    }

    boolean matches(Options options) {
        return options.renderDistance().get() == renderDistance() && options.simulationDistance().get() == simulationDistance()
                && options.graphicsMode().get() == graphics && options.cloudStatus().get() == clouds
                && options.ambientOcclusion().get() == ambientOcclusion && options.particles().get() == particles
                && options.biomeBlendRadius().get() == biomeBlend && options.entityShadows().get() == entityShadows
                && Math.round(options.entityDistanceScaling().get() * 100) == entityDistance && options.mipmapLevels().get() == mipmaps;
    }

    /** The preset the options match, or null for a setup changed by hand. */
    static QualityPreset current(Options options) {
        for (QualityPreset preset : values()) if (preset.matches(options)) return preset;
        return null;
    }
}
