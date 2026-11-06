package dev.overhaul.minecraft.blaze.ui;

import dev.overhaul.minecraft.blaze.BlazeOptimization;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;

/**
 * Every switchable optimisation, so a gain can be measured with it off. Each change is written to the file at once;
 * the running game keeps what it started with, so the page counts what waits for a restart.
 */
final class OptimizationPage extends SettingsPage {
    private static final Map<BlazeOptimization, String> LABELS = labels();
    private static final List<Group> GROUPS = List.of(
            new Group("Terrain", List.of(BlazeOptimization.SECTION_BATCH, BlazeOptimization.SECTION_OCCLUSION, BlazeOptimization.GPU_OCCLUSION, BlazeOptimization.COMPACT_SECTIONS,
                    BlazeOptimization.SECTION_LISTS, BlazeOptimization.SECTION_TABLE, BlazeOptimization.SECTION_BOUNDS,
                    BlazeOptimization.SECTION_SCAN_REUSE, BlazeOptimization.SECTION_GRID, BlazeOptimization.SECTION_RUN_KEYS, BlazeOptimization.SECTION_DRAWS,
                    BlazeOptimization.STRIDE_OFFSETS)),
            new Group("Entities", List.of(BlazeOptimization.ENTITY_OCCLUSION, BlazeOptimization.BLOCK_ENTITY_OCCLUSION, BlazeOptimization.BULK_VERTICES,
                    BlazeOptimization.CUBE_MESH, BlazeOptimization.CUBE_AXES, BlazeOptimization.ITEM_QUADS, BlazeOptimization.ENTITY_RENDERER_CACHE,
                    BlazeOptimization.ENTITY_CLASS_SCANS, BlazeOptimization.ENTITY_LIGHT, BlazeOptimization.PARTICLE_QUADS)),
            new Group("Frame", List.of(BlazeOptimization.DRAW_MERGE, BlazeOptimization.STREAM_UPLOADS, BlazeOptimization.SPRITE_INTERPOLATION,
                    BlazeOptimization.BIOME_CACHE, BlazeOptimization.HUD_CACHE, BlazeOptimization.DEBUG_TEXT, BlazeOptimization.POOL_RANGES, BlazeOptimization.PIXEL_ROWS,
                    BlazeOptimization.MERGED_BARRIERS)),
            new Group("Other mods", List.of(BlazeOptimization.XAERO_LIMITS, BlazeOptimization.CROSSHAIR_LIMIT, BlazeOptimization.ENTITY_SCANS,
                    BlazeOptimization.DH_CLOUDS, BlazeOptimization.DH_RENDER_LIST, BlazeOptimization.DH_SHARED_PAGES)));
    private final Map<BlazeOptimization, Boolean> saved = new EnumMap<>(BlazeOptimization.class);

    OptimizationPage(Runnable refresh) {
        super(refresh);
        for (BlazeOptimization option : BlazeOptimization.values()) saved.put(option, option.saved());
    }

    private record Group(String title, List<BlazeOptimization> options) {
    }

    @Override
    String title() {
        return "Optimisations";
    }

    @Override
    List<SettingsRow> rows() {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(new SettingsRow.Header("All"));
        rows.add(new SettingsRow.Action(Component.literal("Turn all on"), "Every optimisation on, the default", () -> setAll(true), () -> count(true) + " on"));
        rows.add(new SettingsRow.Action(Component.literal("Turn all off"), "Every optimisation off, for a baseline that only runs on Vulkan", () -> setAll(false), () -> count(false) + " off"));
        List<BlazeOptimization> listed = new ArrayList<>();
        for (Group group : GROUPS) {
            rows.add(new SettingsRow.Header(group.title()));
            for (BlazeOptimization option : group.options()) {
                rows.add(row(option));
                listed.add(option);
            }
        }
        List<SettingsRow> rest = new ArrayList<>();
        for (BlazeOptimization option : BlazeOptimization.values()) if (!listed.contains(option)) rest.add(row(option));
        if (!rest.isEmpty()) {
            rows.add(new SettingsRow.Header("More"));
            rows.addAll(rest);
        }
        return rows;
    }

    private SettingsRow row(BlazeOptimization option) {
        return new SettingsRow.Toggle(Component.literal(LABELS.getOrDefault(option, option.key())), option.description(),
                () -> saved.get(option), value -> set(Map.of(option, value)))
                .enabledWhen(() -> !option.launchOverride())
                .note(() -> option.launchOverride() ? "Set by -Doverhaul.opt." + option.key() + ", the screen cannot change it"
                        : saved.get(option) != option.on() ? "Restart the game to apply" : option.key());
    }

    private void setAll(boolean value) {
        Map<BlazeOptimization, Boolean> values = new EnumMap<>(BlazeOptimization.class);
        for (BlazeOptimization option : BlazeOptimization.values()) if (!option.launchOverride()) values.put(option, value);
        set(values);
    }

    private void set(Map<BlazeOptimization, Boolean> values) {
        BlazeOptimization.save(values);
        saved.putAll(values);
    }

    private long count(boolean value) {
        return saved.values().stream().filter(v -> v == value).count();
    }

    @Override
    String status() {
        long pending = saved.entrySet().stream().filter(e -> e.getValue() != e.getKey().on()).count();
        if (pending == 0) return "Changes here apply after a restart";
        return "Restart the game to apply " + pending + (pending == 1 ? " change" : " changes");
    }

    @Override
    boolean statusIsPending() {
        return saved.entrySet().stream().anyMatch(e -> e.getValue() != e.getKey().on());
    }

    private static Map<BlazeOptimization, String> labels() {
        Map<BlazeOptimization, String> labels = new EnumMap<>(BlazeOptimization.class);
        labels.put(BlazeOptimization.SECTION_BATCH, "Batched terrain draws");
        labels.put(BlazeOptimization.SECTION_OCCLUSION, "Terrain occlusion");
        labels.put(BlazeOptimization.GPU_OCCLUSION, "Terrain occlusion on the GPU");
        labels.put(BlazeOptimization.ENTITY_OCCLUSION, "Entity occlusion");
        labels.put(BlazeOptimization.BLOCK_ENTITY_OCCLUSION, "Block entity occlusion");
        labels.put(BlazeOptimization.COMPACT_SECTIONS, "Compact terrain vertices");
        labels.put(BlazeOptimization.SECTION_LISTS, "Per-layer section lists");
        labels.put(BlazeOptimization.DRAW_MERGE, "Merged draws");
        labels.put(BlazeOptimization.STREAM_UPLOADS, "Streamed uploads");
        labels.put(BlazeOptimization.BULK_VERTICES, "Bulk vertex writes");
        labels.put(BlazeOptimization.CUBE_MESH, "Precomputed model cubes");
        labels.put(BlazeOptimization.CUBE_AXES, "Shared cube normals");
        labels.put(BlazeOptimization.ITEM_QUADS, "Direct item quads");
        labels.put(BlazeOptimization.ENTITY_RENDERER_CACHE, "Entity renderer cache");
        labels.put(BlazeOptimization.BIOME_CACHE, "Sky and fog biome cache");
        labels.put(BlazeOptimization.SPRITE_INTERPOLATION, "Fast texture animation");
        labels.put(BlazeOptimization.XAERO_LIMITS, "Xaero's map limits");
        labels.put(BlazeOptimization.CROSSHAIR_LIMIT, "Shoulder Surfing crosshair limit");
        labels.put(BlazeOptimization.SECTION_TABLE, "Terrain draw tables");
        labels.put(BlazeOptimization.SECTION_BOUNDS, "Flat frustum test");
        labels.put(BlazeOptimization.ENTITY_SCANS, "Camera effect scans per tick");
        labels.put(BlazeOptimization.SECTION_SCAN_REUSE, "Reused section walks");
        labels.put(BlazeOptimization.DH_CLOUDS, "Distant Horizons clouds at 60 Hz");
        labels.put(BlazeOptimization.DH_RENDER_LIST, "Distant Horizons render list");
        labels.put(BlazeOptimization.SECTION_GRID, "Incremental section grid");
        labels.put(BlazeOptimization.PARTICLE_QUADS, "Direct particle quads");
        labels.put(BlazeOptimization.ENTITY_CLASS_SCANS, "Skipped entity class scans");
        labels.put(BlazeOptimization.ENTITY_LIGHT, "Entity light per tick");
        labels.put(BlazeOptimization.HUD_CACHE, "HUD at 30 Hz");
        labels.put(BlazeOptimization.DEBUG_TEXT, "F3 text at 10 Hz");
        labels.put(BlazeOptimization.SECTION_RUN_KEYS, "Shared terrain draw keys");
        labels.put(BlazeOptimization.PACK_GRAPH_SHAPES, "Reused pack pass graphs");
        labels.put(BlazeOptimization.POOL_RANGES, "Flat buffer pool ranges");
        labels.put(BlazeOptimization.UNIFORM_PLANS, "Resolved pack uniforms");
        labels.put(BlazeOptimization.UNIFORM_READERS, "Bound custom uniform inputs");
        labels.put(BlazeOptimization.SHADOW_COLUMNS, "Shadow casters by column");
        labels.put(BlazeOptimization.SHADOW_TIGHT_REUSE, "Reused shadow frustum test");
        labels.put(BlazeOptimization.SHADOW_REACH, "Shadow casters by view");
        labels.put(BlazeOptimization.SECTION_DRAWS, "Flat terrain draw fields");
        labels.put(BlazeOptimization.PIXEL_ROWS, "Row copies for pixel reads");
        labels.put(BlazeOptimization.STRIDE_OFFSETS, "Stride-aligned section vertices");
        labels.put(BlazeOptimization.DH_SHARED_PAGES, "Distant Horizons shared pages");
        labels.put(BlazeOptimization.MERGED_BARRIERS, "Merged image barriers");
        labels.put(BlazeOptimization.SHADOW_DRAWS, "Flat shadow draw fields");
        return labels;
    }
}
