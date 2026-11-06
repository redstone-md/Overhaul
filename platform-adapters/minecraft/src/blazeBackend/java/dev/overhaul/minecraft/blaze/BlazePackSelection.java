package dev.overhaul.minecraft.blaze;

/** Which gbuffer program stands in for a game shader; null keeps the game's shader on the pack's first colour target. */
final class BlazePackSelection {
    private BlazePackSelection() {}

    static String program(String shader) {
        return switch (shader) {
            case "rendertype_solid" -> "gbuffers_terrain_solid";
            case "rendertype_cutout", "rendertype_cutout_mipped" -> "gbuffers_terrain_cutout";
            case "rendertype_translucent", "rendertype_translucent_moving_block", "rendertype_tripwire" -> "gbuffers_water";
            case "rendertype_eyes" -> "gbuffers_spidereyes";
            case "particle" -> "gbuffers_textured_lit";
            case "rendertype_clouds" -> "gbuffers_clouds";
            case BlazePackScene.WEATHER -> "gbuffers_weather";
            case "rendertype_entity_shadow", "rendertype_entity_glint", "rendertype_entity_glint_direct", "rendertype_entity_translucent_emissive" -> null;
            case "rendertype_item_entity_translucent_cull", "rendertype_entity_translucent", "rendertype_entity_translucent_cull", "rendertype_armor_translucent" -> "gbuffers_entities_translucent";
            default -> shader.startsWith("rendertype_entity_") || shader.startsWith("rendertype_armor_cutout") ? "gbuffers_entities" : null;
        };
    }

    /** The first-person hand's stand-in: translucent layers go to the hand's water program, as Iris sorts them. */
    static String handProgram(String shader) {
        String world = program(shader);
        if (world == null) return null;
        return world.equals("gbuffers_water") || world.equals("gbuffers_entities_translucent") ? "gbuffers_hand_water" : "gbuffers_hand";
    }

    /** The sky's stand-in: the plain sky, its sunrise fan and the stars go to skybasic, the sun and moon to skytextured. */
    static String skyProgram(String shader) {
        return switch (shader) {
            case "position", "position_color" -> "gbuffers_skybasic";
            case "position_tex", "position_tex_color" -> "gbuffers_skytextured";
            default -> null;
        };
    }

    /** The block outline and debug lines: no pack program draws them, and the pack's passes would paint over them. */
    static boolean onTop(String shader) {
        return "rendertype_lines".equals(shader);
    }

    /**
     * The alpha test a pack without its own expects from the loader: the game's shaders discard below a tenth except
     * for solid and translucent terrain, and Iris keeps that cutoff for the programs standing in for them.
     */
    static float alphaCutoff(String shader) {
        return switch (shader) {
            case "rendertype_solid", "rendertype_translucent", "rendertype_translucent_moving_block" -> 0f;
            default -> 0.1f;
        };
    }
}
