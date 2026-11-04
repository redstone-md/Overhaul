package dev.overhaul.shader.pack;

import java.util.List;
import java.util.Map;

/**
 * Names for the renderStage uniform. Packs compare it against the MC_RENDER_STAGE_* macros, so the numbers
 * only have to agree with those macros. A draw has no stage of its own here: each program reads the stage
 * its name stands for, which is wrong for a program that also serves other stages through fallback.
 */
public final class PackRenderStages {
    private static final List<String> STAGES = List.of("NONE","SKY","SUNSET","CUSTOM_SKY","SUN","MOON","STARS","VOID",
            "TERRAIN_SOLID","TERRAIN_CUTOUT_MIPPED","TERRAIN_CUTOUT","ENTITIES","BLOCK_ENTITIES","DESTROY","OUTLINE","DEBUG",
            "HAND_SOLID","TERRAIN_TRANSLUCENT","TRIPWIRE","PARTICLES","CLOUDS","RAIN_SNOW","WORLD_BORDER","HAND_TRANSLUCENT");
    private static final Map<String,String> PROGRAMS = Map.ofEntries(
            Map.entry("gbuffers_skybasic","SKY"),Map.entry("gbuffers_skytextured","SUN"),
            Map.entry("gbuffers_terrain","TERRAIN_SOLID"),Map.entry("gbuffers_terrain_solid","TERRAIN_SOLID"),
            Map.entry("gbuffers_terrain_cutout","TERRAIN_CUTOUT"),Map.entry("gbuffers_terrain_cutout_mip","TERRAIN_CUTOUT_MIPPED"),
            Map.entry("gbuffers_water","TERRAIN_TRANSLUCENT"),Map.entry("gbuffers_entities","ENTITIES"),
            Map.entry("gbuffers_entities_glowing","ENTITIES"),Map.entry("gbuffers_spidereyes","ENTITIES"),
            Map.entry("gbuffers_armor_glint","ENTITIES"),Map.entry("gbuffers_block","BLOCK_ENTITIES"),
            Map.entry("gbuffers_beaconbeam","BLOCK_ENTITIES"),Map.entry("gbuffers_damagedblock","DESTROY"),
            Map.entry("gbuffers_basic","OUTLINE"),Map.entry("gbuffers_line","OUTLINE"),
            Map.entry("gbuffers_hand","HAND_SOLID"),Map.entry("gbuffers_hand_water","HAND_TRANSLUCENT"),
            Map.entry("gbuffers_textured","PARTICLES"),Map.entry("gbuffers_textured_lit","PARTICLES"),
            Map.entry("gbuffers_clouds","CLOUDS"),Map.entry("gbuffers_weather","RAIN_SNOW"));

    private PackRenderStages() {}

    /** {@code program} may carry its world folder. Anything outside the gbuffers reads NONE. */
    public static int of(String program) {
        String stage = PROGRAMS.get(program.substring(program.lastIndexOf('/')+1));
        return stage == null ? 0 : STAGES.indexOf(stage);
    }

    static void define(Map<String,String> macros) {
        for (int index = 0; index < STAGES.size(); index++) macros.put("MC_RENDER_STAGE_"+STAGES.get(index),Integer.toString(index));
    }
}
