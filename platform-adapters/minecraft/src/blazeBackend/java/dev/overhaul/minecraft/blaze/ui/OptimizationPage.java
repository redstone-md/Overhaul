package dev.overhaul.minecraft.blaze.ui;

import dev.overhaul.minecraft.blaze.BlazeOptimization;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every switchable optimisation, grouped by what it speeds up, so a gain can be measured with it off. The running game
 * keeps what it started with for the ones it reads at startup, so the page counts what waits for a restart.
 */
final class OptimizationPage extends SettingsPage {
    /** A rail entry and the options under it; options no group names fall to {@link #MODS}. */
    private enum Group {
        TERRAIN(SettingsIcon.TERRAIN, BlazeOptimization.SECTION_BATCH, BlazeOptimization.SECTION_OCCLUSION, BlazeOptimization.GPU_OCCLUSION, BlazeOptimization.COMPACT_SECTIONS,
                BlazeOptimization.SECTION_LISTS, BlazeOptimization.SECTION_TABLE, BlazeOptimization.SECTION_BOUNDS, BlazeOptimization.SECTION_SCAN_REUSE, BlazeOptimization.SECTION_GRID,
                BlazeOptimization.SECTION_RUN_KEYS, BlazeOptimization.SECTION_DRAWS, BlazeOptimization.STRIDE_OFFSETS, BlazeOptimization.SECTION_SLOT_KEEP, BlazeOptimization.BIOME_CACHE,
                BlazeOptimization.PALETTE_ENTRIES, BlazeOptimization.BIOME_FIDDLE, BlazeOptimization.CLIENT_PLAINS, BlazeOptimization.TINT_BIOMES, BlazeOptimization.FLUID_TINT,
                BlazeOptimization.NOISE_BIOMES, BlazeOptimization.FLUID_STATES, BlazeOptimization.LEVEL_HEIGHT),
        ENTITIES(SettingsIcon.ENTITY, BlazeOptimization.ENTITY_OCCLUSION, BlazeOptimization.BLOCK_ENTITY_OCCLUSION, BlazeOptimization.BULK_VERTICES, BlazeOptimization.CUBE_MESH,
                BlazeOptimization.CUBE_AXES, BlazeOptimization.ITEM_QUADS, BlazeOptimization.ITEM_AXES, BlazeOptimization.ENTITY_RENDERER_CACHE, BlazeOptimization.ENTITY_CLASS_SCANS,
                BlazeOptimization.ENTITY_LIGHT, BlazeOptimization.SELECTOR_ORDER, BlazeOptimization.SECTION_LOOKUP, BlazeOptimization.COLLISION_SCANS,
                BlazeOptimization.ENTITY_BLOCK_COLLISIONS, BlazeOptimization.COLLISION_SECTIONS, BlazeOptimization.SUPPORTING_BLOCK, BlazeOptimization.PLAYER_HAND_PACE,
                BlazeOptimization.PLAYER_HAND_REPLAY, BlazeOptimization.ENTITY_NBT_TICK, BlazeOptimization.ENTITY_NBT_RULES, BlazeOptimization.VILLAGER_TEXTURES,
                BlazeOptimization.EFFECT_POSES, BlazeOptimization.LAST_TARGET),
        PARTICLES(SettingsIcon.DOTS, BlazeOptimization.PARTICLE_QUADS, BlazeOptimization.PARTICLE_RESERVE, BlazeOptimization.PARTICLE_CULLING, BlazeOptimization.PARTICLE_BOUNDS,
                BlazeOptimization.PARTICLE_LIGHT, BlazeOptimization.PARTICLE_COLLISIONS, BlazeOptimization.PARTICLE_RIMS, BlazeOptimization.PARTICLE_ATLAS_WRITE,
                BlazeOptimization.RAIN_BIOMES, BlazeOptimization.RAIN_LIGHT, BlazeOptimization.ATMOSPHERE_WEATHER, BlazeOptimization.SABLE_PARTICLES, BlazeOptimization.SABLE_RAIN),
        FRAME(SettingsIcon.CHIP, BlazeOptimization.DRAW_MERGE, BlazeOptimization.STREAM_UPLOADS, BlazeOptimization.SPRITE_INTERPOLATION, BlazeOptimization.MIP_BLEND,
                BlazeOptimization.POOL_RANGES, BlazeOptimization.PIXEL_ROWS, BlazeOptimization.MERGED_BARRIERS, BlazeOptimization.RAW_DRAWS, BlazeOptimization.RAW_PROGRAMS,
                BlazeOptimization.RAW_DRAW_LAYOUT, BlazeOptimization.UNBOXED_PROGRAMS, BlazeOptimization.RT_SCENE_MEMORY),
        INTERFACE(SettingsIcon.WINDOW, BlazeOptimization.HUD_CACHE, BlazeOptimization.DEBUG_TEXT, BlazeOptimization.HUD_LAYER_MEMO, BlazeOptimization.XAERO_LIMITS,
                BlazeOptimization.CROSSHAIR_LIMIT, BlazeOptimization.DAMAGE_TEXT_SIGHT),
        PACKS(SettingsIcon.CUBE, BlazeOptimization.PACK_BLOCK_IDS, BlazeOptimization.PACK_FRAME_QUEUE, BlazeOptimization.PACK_SECTION_BATCH, BlazeOptimization.PACK_SHADOW_REUSE,
                BlazeOptimization.PACK_GRAPH_SHAPES, BlazeOptimization.UNIFORM_PLANS, BlazeOptimization.UNIFORM_READERS, BlazeOptimization.SHADOW_COLUMNS,
                BlazeOptimization.SHADOW_TIGHT_REUSE, BlazeOptimization.SHADOW_REACH, BlazeOptimization.ENTITY_SHADOW_REACH, BlazeOptimization.PLAYER_SHADOW_PACE,
                BlazeOptimization.PLAYER_SHADOW_REPLAY, BlazeOptimization.PLAYER_SHADOW_SLOW_PACE, BlazeOptimization.SHADOW_DRAWS, BlazeOptimization.SINGLE_PACK,
                BlazeOptimization.PACK_REMAP),
        HORIZONS(SettingsIcon.HORIZON),
        MODS(SettingsIcon.GRID),
        WORLD(SettingsIcon.WORLD, BlazeOptimization.SERVER_CLASS_SCANS, BlazeOptimization.SELECTOR_TAGS, BlazeOptimization.SELECTOR_INDEX, BlazeOptimization.SELECTOR_SCORES,
                BlazeOptimization.ATTRIBUTE_OPERATIONS, BlazeOptimization.EVENT_LISTENERS, BlazeOptimization.COMMAND_SOURCE_NAMES, BlazeOptimization.BLOCK_SEARCH,
                BlazeOptimization.FLUID_SOURCE_EVENT, BlazeOptimization.POI_RANGE_SECTIONS, BlazeOptimization.FIRE_SCAN, BlazeOptimization.CAMPFIRE_SCAN);

        final SettingsIcon icon;
        final List<BlazeOptimization> named;

        Group(SettingsIcon icon, BlazeOptimization... named) {
            this.icon = icon;
            this.named = List.of(named);
        }
    }

    private static final Map<Group, List<BlazeOptimization>> GROUPS = groups();
    private final OptimizationValues values;
    private int group;

    OptimizationPage(OptimizationValues values, Runnable refresh) {
        super("optimization", SettingsIcon.BOLT, refresh);
        this.values = values;
    }

    private static Map<Group, List<BlazeOptimization>> groups() {
        Map<Group, List<BlazeOptimization>> groups = new EnumMap<>(Group.class);
        Map<BlazeOptimization, Group> owner = new EnumMap<>(BlazeOptimization.class);
        for (Group group : Group.values()) {
            groups.put(group, new ArrayList<>());
            for (BlazeOptimization option : group.named) owner.put(option, group);
        }
        for (BlazeOptimization option : BlazeOptimization.values()) {
            if (OptimizationValues.GRAPHICS.contains(option)) continue;
            Group fallback = option.name().startsWith("DH_") ? Group.HORIZONS : Group.MODS;
            groups.get(owner.getOrDefault(option, fallback)).add(option);
        }
        return groups;
    }

    @Override
    List<Category> categories() {
        List<Category> categories = new ArrayList<>();
        for (Group entry : Group.values()) {
            categories.add(new Category(entry.icon, text("group." + entry.name().toLowerCase(Locale.ROOT)), String.valueOf(GROUPS.get(entry).size())));
        }
        return categories;
    }

    @Override
    int category() {
        return group;
    }

    @Override
    void category(int index) {
        group = index;
    }

    @Override
    List<SettingsRow> rows() {
        Group shown = Group.values()[group];
        List<BlazeOptimization> options = GROUPS.get(shown);
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(new SettingsRow.Header(text("section.all"), () -> on(all()) + " / " + all().size()));
        rows.add(new SettingsRow.Action(text("all_on.name"), text("all_on.desc"), SettingsIcon.CHECK, () -> text("all_on.run"), () -> setAll(true)));
        rows.add(new SettingsRow.Action(text("all_off.name"), text("all_off.desc"), null, () -> text("all_off.run"), () -> setAll(false)));
        rows.add(new SettingsRow.Header(text("group." + shown.name().toLowerCase(Locale.ROOT)), () -> on(options) + " / " + options.size()));
        for (BlazeOptimization option : options) rows.add(row(option));
        return rows;
    }

    private SettingsRow row(BlazeOptimization option) {
        String key = "overhaul.opt." + option.key();
        return values.toggle(option, OverhaulLang.or(key + ".name", option.key()), OverhaulLang.or(key + ".desc", option.description())).note(() -> {
            if (option.launchOverride()) return OverhaulLang.get("overhaul.ui.launch_override", "-Doverhaul.opt." + option.key());
            return waits(option) ? OverhaulLang.get("overhaul.ui.restart") : null;
        });
    }

    /** Whether the game started with another value than the file holds, for an option it only reads at startup. */
    private static boolean waits(BlazeOptimization option) {
        return !option.live() && option.saved() != option.launched();
    }

    private static List<BlazeOptimization> all() {
        return GROUPS.values().stream().flatMap(List::stream).toList();
    }

    private long on(List<BlazeOptimization> options) {
        return options.stream().filter(option -> values.flag(option).get()).count();
    }

    private void setAll(boolean value) {
        for (BlazeOptimization option : all()) if (!option.launchOverride()) values.flag(option).set(value);
    }

    @Override
    String readout() {
        return on(all()) + " / " + all().size();
    }

    @Override
    String status() {
        long waiting = all().stream().filter(OptimizationPage::waits).count();
        return waiting == 0 ? "" : text("waiting", waiting);
    }

    @Override
    boolean statusIsPending() {
        return true;
    }
}
