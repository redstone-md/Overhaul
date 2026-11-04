package dev.overhaul.shader.pack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which program stands in for a missing one, from Iris ProgramId. Differs from OptiFine at
 * gbuffers_clouds, which OptiFine draws with no fallback. gbuffers_terrain_cutout_mip is absent
 * from Iris's table and comes from the OptiFine shader documentation.
 */
public final class PackProgramFallbacks {
    private static final Map<String,String> PARENTS = parents();
    private static final ShaderPackProperties.Blend BLEND_OFF = new ShaderPackProperties.Blend(false,"ONE","ZERO","ONE","ZERO");
    private static final ShaderPackProperties.Blend SPIDER_EYES = new ShaderPackProperties.Blend(true,"SRC_ALPHA","ONE","ZERO","ONE");

    private PackProgramFallbacks() {}

    private static Map<String,String> parents() {
        Map<String,String> table = new LinkedHashMap<>();
        for (String name : List.of("shadow_solid","shadow_cutout","shadow_water","shadow_entities","shadow_block")) table.put(name,"shadow");
        table.put("shadow_lightning","shadow_entities");
        gbuffers(table,"basic","line","textured","skybasic");
        gbuffers(table,"textured","textured_lit","skytextured","clouds","beaconbeam","armor_glint","spidereyes");
        gbuffers(table,"textured_lit","terrain","item","entities","particles","hand","weather");
        gbuffers(table,"terrain","terrain_solid","terrain_cutout","terrain_cutout_mip","damagedblock","block","water");
        gbuffers(table,"block","block_translucent");
        gbuffers(table,"entities","entities_translucent","lightning","entities_glowing");
        gbuffers(table,"particles","particles_translucent");
        gbuffers(table,"hand","hand_water");
        table.put("dh_water","dh_terrain");
        table.put("dh_generic","dh_terrain");
        return Map.copyOf(table);
    }
    private static void gbuffers(Map<String,String> table, String parent, String... children) {
        for (String child : children) table.put("gbuffers_"+child,"gbuffers_"+parent);
    }

    public static Optional<String> parent(String program) { return Optional.ofNullable(PARENTS.get(program)); }

    /** The program itself, then each fallback in order. */
    public static List<String> chain(String program) {
        List<String> chain = new ArrayList<>();
        for (String link = program; link != null; link = PARENTS.get(link)) chain.add(link);
        return List.copyOf(chain);
    }

    /** First program of the chain the pack provides; bare names, without a dimension folder. */
    public static Optional<String> resolve(String program, Collection<String> available) {
        return chain(program).stream().filter(available::contains).findFirst();
    }

    /** Every program this table knows, including the chain roots. */
    public static Set<String> known() {
        Set<String> known = new TreeSet<>(PARENTS.keySet());
        known.addAll(PARENTS.values());
        return known;
    }

    /** Resolution of every known program; one absent from the result has no stand-in in this pack. */
    public static Map<String,String> resolveAll(Collection<String> available) {
        Map<String,String> resolved = new LinkedHashMap<>();
        for (String program : known()) resolve(program,available).ifPresent(found -> resolved.put(program,found));
        return resolved;
    }

    /** Blend an Iris program applies unless the pack sets blend.* for it. */
    public static Optional<ShaderPackProperties.Blend> defaultBlend(String program) {
        if (program.equals("shadow") || program.startsWith("shadow_")) return Optional.of(BLEND_OFF);
        if (program.equals("gbuffers_spidereyes")) return Optional.of(SPIDER_EYES);
        return Optional.empty();
    }
}
