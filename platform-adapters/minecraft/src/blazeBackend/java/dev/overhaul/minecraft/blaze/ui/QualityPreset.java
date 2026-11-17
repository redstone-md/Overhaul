package dev.overhaul.minecraft.blaze.ui;

import java.util.Locale;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.ParticleStatus;

/**
 * Named sets of the game's own video options, lowest first. A preset only writes options the settings pages already
 * show, so picking one and then changing a row by hand leaves a custom setup the pages can still describe.
 */
enum QualityPreset {
    ULTRA_LOW(4, 5, GraphicsStatus.FAST, CloudStatus.OFF, false, ParticleStatus.MINIMAL, 0, false, 50, 0),
    LOW(8, 6, GraphicsStatus.FAST, CloudStatus.OFF, true, ParticleStatus.DECREASED, 1, false, 75, 2),
    OPTIMIZED(12, 6, GraphicsStatus.FANCY, CloudStatus.OFF, true, ParticleStatus.DECREASED, 2, false, 100, 4),
    MEDIUM(12, 8, GraphicsStatus.FANCY, CloudStatus.FAST, true, ParticleStatus.ALL, 2, true, 100, 4),
    HIGH(20, 10, GraphicsStatus.FANCY, CloudStatus.FANCY, true, ParticleStatus.ALL, 3, true, 150, 4),
    ULTRA(32, 12, GraphicsStatus.FANCY, CloudStatus.FANCY, true, ParticleStatus.ALL, 5, true, 250, 4);

    /** Where a preset reads and writes options: the game itself, or a draft that holds them until Apply. */
    interface Sink {
        <T> T get(OptionInstance<T> option);

        <T> void set(OptionInstance<T> option, T value);
    }

    private static final Sink DIRECT = new Sink() {
        @Override
        public <T> T get(OptionInstance<T> option) {
            return option.get();
        }

        /** Some options reload the world when set, so one that already holds the value is left alone. */
        @Override
        public <T> void set(OptionInstance<T> option, T value) {
            if (!option.get().equals(value)) option.set(value);
        }
    };

    private final int renderDistance, simulationDistance, biomeBlend, entityDistance, mipmaps;
    private final GraphicsStatus graphics;
    private final CloudStatus clouds;
    private final ParticleStatus particles;
    private final boolean ambientOcclusion, entityShadows;

    QualityPreset(int renderDistance, int simulationDistance, GraphicsStatus graphics, CloudStatus clouds,
                  boolean ambientOcclusion, ParticleStatus particles, int biomeBlend, boolean entityShadows, int entityDistance, int mipmaps) {
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

    String label() {
        return OverhaulLang.get("overhaul.preset." + name().toLowerCase(Locale.ROOT) + ".name");
    }

    String description() {
        return OverhaulLang.get("overhaul.preset." + name().toLowerCase(Locale.ROOT) + ".desc");
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

    /** Writes the preset to the game at once. */
    void apply(Options options, boolean textures) {
        apply(options, DIRECT, textures);
    }

    /** @param textures whether to set mipmaps too, which reloads every texture */
    void apply(Options options, Sink sink, boolean textures) {
        sink.set(options.renderDistance(), renderDistance());
        sink.set(options.simulationDistance(), simulationDistance());
        sink.set(options.graphicsMode(), graphics);
        sink.set(options.cloudStatus(), clouds);
        sink.set(options.ambientOcclusion(), ambientOcclusion);
        sink.set(options.particles(), particles);
        sink.set(options.biomeBlendRadius(), biomeBlend);
        sink.set(options.entityShadows(), entityShadows);
        sink.set(options.entityDistanceScaling(), entityDistance / 100.0);
        if (textures) sink.set(options.mipmapLevels(), mipmaps);
    }

    boolean matches(Options options, Sink sink) {
        return sink.get(options.renderDistance()) == renderDistance() && sink.get(options.simulationDistance()) == simulationDistance()
                && sink.get(options.graphicsMode()) == graphics && sink.get(options.cloudStatus()) == clouds
                && sink.get(options.ambientOcclusion()) == ambientOcclusion && sink.get(options.particles()) == particles
                && sink.get(options.biomeBlendRadius()) == biomeBlend && sink.get(options.entityShadows()) == entityShadows
                && Math.round(sink.get(options.entityDistanceScaling()) * 100) == entityDistance && sink.get(options.mipmapLevels()) == mipmaps;
    }

    /** The preset the options match, or null for a setup changed by hand. */
    static QualityPreset current(Options options, Sink sink) {
        for (QualityPreset preset : values()) if (preset.matches(options, sink)) return preset;
        return null;
    }

    /** The preset the game runs now, or null. */
    static QualityPreset current(Options options) {
        return current(options, DIRECT);
    }
}
