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
    CUBE_AXES("cubeAxes", "Cubes of a model part reuse its transformed axis normals instead of transforming them per cube; needs cubeMesh"),
    ITEM_QUADS("itemQuads", "Item quads written directly instead of per-vertex"),
    ITEM_AXES("itemAxes", "Item quads facing an axis reuse their pose's transformed axis normals instead of transforming a normal per quad; needs itemQuads"),
    ENTITY_RENDERER_CACHE("entityRendererCache", "Entity renderer lookup stored on the entity type"),
    BIOME_CACHE("biomeCache", "Sky and fog biome samples cached per tick"),
    SPRITE_INTERPOLATION("spriteInterpolation", "Interpolated texture animation blended on raw memory"),
    XAERO_LIMITS("xaeroLimits", "Xaero map processing limited to 60 Hz, map writing and radar updates limited per frame"),
    CROSSHAIR_LIMIT("crosshairLimit", "Shoulder Surfing's crosshair raycast limited to 60 Hz"),
    SECTION_TABLE("sectionTable", "Terrain draws read flat per-layer tables of meshes and positions built by the section scan"),
    SECTION_BOUNDS("sectionBounds", "Frustum test over flat section bounds instead of the graph's linked nodes"),
    ENTITY_SCANS("entityScans", "Cataclysm and Alex's Caves camera effect entity scans once a tick instead of every frame"),
    WITHER_GLOWS("witherGlows", "Wither Reincarnated's per-frame glow walk and per-tick music walk over every client entity for withers are skipped while the level holds none"),
    VEIL_SKELETONS("veilSkeletons", "Veil's per-tick walk over every client entity for skeleton animators is skipped while the level holds no skeleton parent"),
    MOD_ENTITY_WALKS("modEntityWalks", "Adorable Hamster Pets' per-tick walk over every client entity for feverish hamsters is skipped while the level holds no hamster"),
    EFFECT_POSES("effectPoses", "Alex's Mobs' per-frame clinging, sting and ender flu pose checks skipped for entities with no active effects"),
    VILLAGER_TEXTURES("villagerTextures", "Villager type, profession and level texture paths built once per layer instead of on every frame"),
    HEAD_CURIOS("headCurios", "Youkai's Feasts camellia head check answered once per tick per entity instead of searching curios every frame"),
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
    DEBUG_TEXT("debugText", "F3 text lines gathered at 10 Hz instead of every time the HUD is drawn"),
    POOL_RANGES("poolRanges", "Free ranges of per-frame buffer pages kept in sorted arrays instead of a tree of boxed offsets"),
    UNIFORM_PLANS("uniformPlans", "Shader pack uniforms filled from the source each name resolved to the first time instead of asking every source again"),
    UNIFORM_READERS("uniformReaders", "Built-in values a pack's custom uniforms read bound once to the source that owns them instead of looked up by name every frame"),
    SHADOW_COLUMNS("shadowColumns", "Kept shadow casters tested against each frame's light frustum a column at a time, section by section only where the frustum cuts the column"),
    SHADOW_TIGHT_REUSE("shadowTightReuse", "The light frustum test of kept shadow casters widened by four blocks and reused while the camera moves less than half a block and the sun turns less than a quarter degree"),
    SHADOW_REACH("shadowReach", "Shadow casters whose shadow, swept along the light, cannot land inside the player's view left out of the shadow map; needs packShadowReuse"),
    SECTION_DRAWS("sectionDraws", "Each section's draw counts, offsets and buffers copied into the section scan's tables, so drawing never reads the meshes; needs sectionTable"),
    PIXEL_ROWS("pixelRows", "Screenshot and other RGBA pixel reads copied a row at a time instead of a byte at a time, so the GPU waits less"),
    STRIDE_OFFSETS("strideOffsets", "Section vertices placed on a multiple of their stride, so a shader pack's wider vertices share one draw per buffer page instead of one per leftover offset"),
    DH_SHARED_PAGES("dhSharedPages", "Distant Horizons vertex buffers drawn by a shader pack share device pages, so the LODs of a pass draw as one indirect multi-draw per page instead of one draw per LOD"),
    DH_DEVICE_CACHE("dhDeviceCache", "Each Distant Horizons vertex buffer keeps the device buffer it resolved to instead of looking it up by name every frame"),
    SHADOW_DRAWS("shadowDraws", "Shadow casters' draw fields copied into flat per-layer tables when they are gathered, so frames that keep them draw without reading each mesh; needs packShadowReuse"),
    RAW_DRAWS("rawDraws", "Mod draws from raw OpenGL vertex arrays, such as Veil's lights, particles and skins, drawn natively instead of dropped"),
    MERGED_BARRIERS("mergedBarriers", "Image layout changes over several mips or layers recorded as one barrier instead of one per mip and layer"),
    SELECTOR_ORDER("selectorOrder", "Entity selectors such as @e[tag=x] in datapack tick functions test an entity's tags, type and scores before reading its health"),
    MOD_ENTITY_TICKS("modEntityTicks", "Born in Chaos animation sync skips entities of other mods instead of testing each against 83 of its classes every tick"),
    SERVER_CLASS_SCANS("serverClassScans", "Server entity scans, such as mob goals looking for a wither, skipped for classes no entity in the level belongs to; needs entityClassScans"),
    SECTION_LOOKUP("entitySectionLookup", "Entity queries with a small box, such as mobs pushing each other, look their few entity sections up by key instead of walking every section in the world's x slab"),
    COLLISION_SCANS("entityCollisionScans", "A moving entity skips the search for boats, minecarts and other entities it could stand on while the level holds none of them"),
    DAMAGE_TEXT_SIGHT("damageTextSight", "JDamageIndicators casts its line-of-sight ray to each floating damage number once per tick instead of once per frame"),
    SABLE_PARTICLES("sableParticles", "Particles in a level with no Sable sub-level loaded, riding none and carrying no velocity from one, move as vanilla instead of through Sable's per-move sub-level search"),
    ATMOSPHERE_WEATHER("atmosphereWeather", "Atmosphere's rain check skips asking local weather areas and Sable's proxy for every position when no local weather area exists"),
    SABLE_RAIN("sableRain", "Rain and snow in a level with no Sable sub-level loaded take vanilla's column height instead of Sable's per-column sub-level search"),
    SABLE_ENTITY_CULL("sableEntityCull", "Entity culling in a level with no Sable sub-level loaded skips Sable's per-entity plot lookup and culls as vanilla"),
    RAIN_BIOMES("rainBiomes", "Rain and snow keep each column's blended biome for the client tick instead of blending eight biome cells per column every frame"),
    PARTICLE_LIGHT("particleLight", "Light at each particle sampled once per tick instead of once per frame"),
    PARTICLE_COLLISIONS("particleCollisions", "Particles collide against block shapes gathered straight into a list, not through vanilla's iterator and immutable list builder"),
    RAIN_LIGHT("rainLight", "Rain and snow light each column once per client tick and reuse it for the frames until the next"),
    LEVEL_HEIGHT("levelHeight", "A level keeps its build floor and height in a field instead of asking its dimension type on every block lookup"),
    ENTITY_BLOCK_COLLISIONS("entityBlockCollisions", "Entity moves gather the block shapes around them straight into a list, not through vanilla's iterator"),
    COLLISION_SECTIONS("collisionSections", "Block collision walks read loaded chunk sections directly and pass over sections holding only air in one check"),
    SUPPORTING_BLOCK("supportingBlock", "Grounded entities find the block they stand on without vanilla's collision iterator"),
    BLOCK_SEARCH("blockSearch", "Mobs out to break one kind of block, like zombies after turtle eggs, skip their block-by-block search when no loaded section around them holds that block"),
    SELECTOR_TAGS("selectorTags", "Entity selectors that require a tag no entity carries match nothing without scanning every entity"),
    SELECTOR_INDEX("selectorIndex", "Entity selectors that require a tag, a type or a type tag walk only the entities meeting it, in the level's own order, instead of every entity of the level"),
    ATTRIBUTE_OPERATIONS("attributeOperations", "New attribute instances copy one empty map of modifier operations instead of looking up the operation constants reflectively each time"),
    EVENT_LISTENERS("eventListeners", "Event listeners read whether they take cancelled events from a plain object instead of their annotation proxy, which costs a reflective lookup on every cancellable event"),
    COMMAND_SOURCE_NAMES("commandSourceNames", "Command sources made for an entity by execute as or at take that entity's name and display name only when chat feedback asks for them, not once per target"),
    PUPFISH_SAVE("pupfishSave", "Alex's Mobs' pupfish chunk, once found or given up on, is saved with the world instead of being searched for again by noise sampling every session"),
    ARCHITECTURY_EVENT_INVOKE("architecturyEventInvoke", "Architectury events call listeners reflectively instead of building a method handle per call"),
    ZETA_EVENT_INVOKE("zetaEventInvoke", "Zeta's forwarded events call a listener handle adapted once instead of converting its type on every post"),
    PUPFISH_COLUMN("pupfishColumn", "Alex's Mobs' pupfish search stops each noise column at the lowest height it can accept instead of generating it to the bottom"),
    FARM_CART_WALK("farmCartWalk", "Farm and Charm's client tick skips walking every entity for cart wheels while the level holds no cart"),
    MEKANISM_RADIATION_IDLE("mekanismRadiationIdle", "Mekanism's radiation tick returns at once for an entity that never held a dose, instead of creating radiation data on every mob"),
    WITHER_POSSESSED_IDLE("witherPossessedIdle", "Wither Reincarnated's possession tick returns at once for a mob that was never possessed, instead of creating possession data on every mob"),
    GPU_OCCLUSION("gpuOcclusion", "Terrain sections tested against the newest terrain depth on the GPU right before they draw, instead of one CPU test per section against depth read back frames earlier; needs sectionOcclusion; off by default, it measured no faster", false);

    private final String key, description;
    private final boolean fallback, enabled;

    BlazeOptimization(String key, String description) {
        this(key, description, true);
    }

    BlazeOptimization(String key, String description, boolean fallback) {
        this.key = key;
        this.description = description;
        this.fallback = fallback;
        String value = System.getProperty("overhaul.opt." + key);
        if (value == null) value = Settings.FILE_VALUES.getProperty(key);
        enabled = read(value, fallback);
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
        return read(Settings.FILE_VALUES.getProperty(key), fallback);
    }

    private static boolean read(String value, boolean fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.equalsIgnoreCase("true") || !trimmed.equalsIgnoreCase("false") && fallback;
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
                    if (!Settings.FILE_VALUES.containsKey(option.key)) writer.write("# " + option.description + "\n" + option.key + "=" + option.fallback + "\n");
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
