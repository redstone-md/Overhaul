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
            case "rendertype_entity_shadow", "rendertype_entity_glint", "rendertype_entity_glint_direct", "rendertype_entity_translucent_emissive" -> null;
            case "rendertype_item_entity_translucent_cull", "rendertype_entity_translucent", "rendertype_entity_translucent_cull", "rendertype_armor_translucent" -> "gbuffers_entities_translucent";
            default -> shader.startsWith("rendertype_entity_") || shader.startsWith("rendertype_armor_cutout") ? "gbuffers_entities" : null;
        };
    }
}
