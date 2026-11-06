package dev.overhaul.minecraft.blaze;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.slf4j.LoggerFactory;

/**
 * Every optimisation that can be turned off to measure what it gives. Read once, before mixins apply, from
 * {@code config/overhaul-optimizations.properties}; {@code -Doverhaul.opt.<key>=false} overrides the file. The game must
 * restart for a change to count. Vulkan itself is not listed: the frontend has no OpenGL path to fall back to.
 */
public enum BlazeOptimization {
    SECTION_BATCH("sectionBatch", "Terrain layers as one indirect multi-draw per geometry page instead of one draw per section"),
    SECTION_OCCLUSION("sectionOcclusion", "Terrain sections hidden behind recent terrain depth are not drawn; needs sectionBatch"),
    ENTITY_OCCLUSION("entityOcclusion", "Entities hidden behind recent terrain depth are not drawn; needs sectionOcclusion"),
    BLOCK_ENTITY_OCCLUSION("blockEntityOcclusion", "Block entities in sections hidden behind recent terrain depth are not drawn; needs sectionOcclusion"),
    COMPACT_SECTIONS("compactSections", "Terrain vertices stored in 20 bytes instead of 32; needs sectionBatch"),
    SECTION_LISTS("sectionLists", "Per-layer visible section lists instead of walking every visible section for every layer"),
    DRAW_MERGE("drawMerge", "Consecutive draws with the same pipeline share one pass and descriptor set"),
    STREAM_UPLOADS("streamUploads", "Per-frame geometry written to host memory instead of copied to device memory"),
    BULK_VERTICES("bulkVertices", "Direct vertex writes for cubes and item quads; cubeMesh and itemQuads need this"),
    CUBE_MESH("cubeMesh", "Entity model cubes written from precomputed quads"),
    ITEM_QUADS("itemQuads", "Item quads written directly instead of per-vertex"),
    ENTITY_RENDERER_CACHE("entityRendererCache", "Entity renderer lookup stored on the entity type"),
    BIOME_CACHE("biomeCache", "Sky and fog biome samples cached per tick"),
    SPRITE_INTERPOLATION("spriteInterpolation", "Interpolated texture animation blended on raw memory"),
    XAERO_LIMITS("xaeroLimits", "Xaero map processing limited to 60 Hz, map writing and radar updates limited per frame"),
    CROSSHAIR_LIMIT("crosshairLimit", "Shoulder Surfing's crosshair raycast limited to 60 Hz"),
    SECTION_TABLE("sectionTable", "Terrain draws read flat per-layer tables of meshes and positions built by the section scan"),
    SECTION_BOUNDS("sectionBounds", "Frustum test over flat section bounds instead of the graph's linked nodes"),
    ENTITY_SCANS("entityScans", "Cataclysm and Alex's Caves camera effect entity scans once a tick instead of every frame"),
    SECTION_SCAN_REUSE("sectionScanReuse", "Visible section walks skipped while no section or the visible list has changed"),
    DH_CLOUDS("dhClouds", "Distant Horizons cloud movement updated at 60 Hz"),
    DH_RENDER_LIST("dhRenderList", "Distant Horizons visible buffer list built without hashing each buffer"),
    SECTION_GRID("sectionGrid", "Section grid moves only the columns that wrapped around the camera"),
    PARTICLE_QUADS("particleQuads", "Particle quads written in one go instead of vertex by vertex"),
    ENTITY_CLASS_SCANS("entityClassScans", "Client entity scans skipped for classes no loaded entity belongs to"),
    ENTITY_LIGHT("entityLight", "Light at each entity sampled once per tick instead of once per frame"),
    HUD_CACHE("hudCache", "HUD drawn at 30 Hz into its own image, composited every frame"),
    PACK_BLOCK_IDS("packBlockIds", "Terrain vertices carry the shader pack's block ids for mc_Entity; off, every block reads -1"),
    PACK_FRAME_QUEUE("packFrameQueue", "Shader pack passes recorded into the frame's command buffers instead of each waiting for the GPU"),
    PACK_SECTION_BATCH("packSectionBatch", "Shader pack terrain as one indirect draw per layer, each section's offset read per instance; needs sectionBatch"),
    PACK_SHADOW_REUSE("packShadowReuse", "Shadow casters kept between frames while the camera, the sun and the sections hold still, tested against a light frustum widened to cover the drift"),
    PACK_GRAPH_SHAPES("packGraphShapes", "Shader pack pass graphs validated once per shape and reused with each frame's passes"),
    SECTION_RUN_KEYS("sectionRunKeys", "Terrain sections find their indirect draw through a key their mesh shares instead of a hash lookup per section"),
    DEBUG_TEXT("debugText", "F3 text lines gathered at 10 Hz instead of every time the HUD is drawn");


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

    public String key() {
        return key;
    }

    public String description() {
        return description;
    }

    /** A launch argument wins over the file, so the settings screen cannot change this one. */
    public boolean launchOverride() {
        return System.getProperty("overhaul.opt." + key) != null;
    }

    /** What the file asks for now, which differs from {@link #on()} until the game restarts. */
    public boolean saved() {
        String value = Settings.FILE_VALUES.getProperty(key);
        return value == null || !value.trim().equalsIgnoreCase("false");
    }

    /** Rewrites each line in place, so comments and order the player left in the file survive. */
    public static void save(Map<BlazeOptimization, Boolean> values) {
        Path path = Settings.PATH;
        try {
            List<String> lines = Files.isRegularFile(path) ? new ArrayList<>(Files.readAllLines(path)) : new ArrayList<>();
            Map<BlazeOptimization, Boolean> missing = new EnumMap<>(values);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index).trim();
                int separator = line.indexOf('=');
                if (line.startsWith("#") || separator < 1) continue;
                String name = line.substring(0, separator).trim();
                for (var entry : values.entrySet()) {
                    if (!entry.getKey().key.equals(name)) continue;
                    lines.set(index, name + "=" + entry.getValue());
                    missing.remove(entry.getKey());
                }
            }
            for (var entry : missing.entrySet()) {
                lines.add("# " + entry.getKey().description);
                lines.add(entry.getKey().key + "=" + entry.getValue());
            }
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.write(path, lines);
            values.forEach((option, value) -> Settings.FILE_VALUES.setProperty(option.key, value.toString()));
        } catch (IOException failure) {
            LoggerFactory.getLogger(BlazeOptimization.class).warn("Cannot write {}", path, failure);
        }
    }

    private static void writeMissing(Path path) {
        try {
            Files.createDirectories(path.getParent());
            boolean created = !Files.exists(path);
            try (Writer writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (created) writer.write("# Set a line to false and restart the game to measure without that optimisation.\n");
                for (BlazeOptimization option : values()) {
                    if (!Settings.FILE_VALUES.containsKey(option.key)) writer.write("# " + option.description + "\n" + option.key + "=true\n");
                }
            }
        } catch (IOException failure) {
            LoggerFactory.getLogger(BlazeOptimization.class).warn("Cannot write {}", path, failure);
        }
    }

    /** Run once every constant exists, so the file lists them all, including ones newer than the file. */
    public static void describeInFile() {
        writeMissing(Settings.PATH);
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
