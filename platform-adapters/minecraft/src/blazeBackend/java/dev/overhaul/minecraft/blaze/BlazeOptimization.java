package dev.overhaul.minecraft.blaze;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.slf4j.LoggerFactory;

/**
 * Every optimisation that can be turned off to measure what it gives. Read once, before mixins apply, from
 * {@code config/overhaul-optimizations.properties}; {@code -Doverhaul.opt.<key>=false} overrides the file. The game must
 * restart for a change to count. Vulkan itself is not listed: the frontend has no OpenGL path to fall back to.
 */
public enum BlazeOptimization {
    SECTION_BATCH("sectionBatch", "Terrain layers as one indirect multi-draw per geometry page instead of one draw per section"),
    SECTION_LISTS("sectionLists", "Per-layer visible section lists instead of walking every visible section for every layer"),
    DRAW_MERGE("drawMerge", "Consecutive draws with the same pipeline share one pass and descriptor set"),
    STREAM_UPLOADS("streamUploads", "Per-frame geometry written to host memory instead of copied to device memory"),
    BULK_VERTICES("bulkVertices", "Direct vertex writes for cubes and item quads; cubeMesh and itemQuads need this"),
    CUBE_MESH("cubeMesh", "Entity model cubes written from precomputed quads"),
    ITEM_QUADS("itemQuads", "Item quads written directly instead of per-vertex"),
    ENTITY_RENDERER_CACHE("entityRendererCache", "Entity renderer lookup stored on the entity type"),
    BIOME_CACHE("biomeCache", "Sky and fog biome samples cached per tick"),
    SPRITE_INTERPOLATION("spriteInterpolation", "Interpolated texture animation blended on raw memory"),
    XAERO_LIMITS("xaeroLimits", "Xaero map writing and radar updates limited per frame");


    private final String key, description;
    private final boolean enabled;

    BlazeOptimization(String key, String description) {
        this.key = key;
        this.description = description;
        String value = System.getProperty("overhaul.opt." + key);
        if (value == null) value = Settings.FILE_VALUES.getProperty(key);
        enabled = value == null || !value.trim().equalsIgnoreCase("false");
        if (!enabled) LoggerFactory.getLogger(BlazeOptimization.class).info("Overhaul optimisation off: {}", key);
    }

    public boolean on() {
        return enabled;
    }

    private static void writeDefaults(Path path) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                writer.write("# Set a line to false and restart the game to measure without that optimisation.\n");
                for (BlazeOptimization option : values()) writer.write("# " + option.description + "\n" + option.key + "=true\n");
            }
        } catch (IOException failure) {
            LoggerFactory.getLogger(BlazeOptimization.class).warn("Cannot write {}", path, failure);
        }
    }

    /** Written once every constant exists, so the file lists them all. */
    public static void describeOnFirstRun() {
        Path path = Settings.PATH;
        if (!Files.exists(path)) writeDefaults(path);
    }

    /** Enum constants initialise before the enum's own statics, so what they read lives here. */
    private static final class Settings {
        private static final Path PATH = Path.of("config", "overhaul-optimizations.properties");
        private static final Properties FILE_VALUES = load();

        private static Properties load() {
            Properties properties = new Properties();
            if (!Files.isRegularFile(PATH)) return properties;
            try (Reader reader = Files.newBufferedReader(PATH)) {
                properties.load(reader);
            } catch (IOException failure) {
                LoggerFactory.getLogger(BlazeOptimization.class).warn("Cannot read {}", PATH, failure);
            }
            return properties;
        }
    }
}
