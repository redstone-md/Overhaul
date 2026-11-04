package dev.overhaul.shader.pack;

import java.io.IOException;
import java.io.StringReader;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Numeric ids a pack assigns in block.properties and item.properties, the values mc_Entity and heldItemId
 * read. Lookups use a plain name; a state-qualified entry (minecraft:wheat:age=7) is stored but never matches one.
 */
public final class PackIdMap {
    public static final int UNKNOWN = -1;
    private static final String BLOCKS = "block.properties", ITEMS = "item.properties";
    private static final PackIdMap EMPTY = new PackIdMap(Map.of(),Map.of());
    private final Map<String,Integer> blocks, items;

    private PackIdMap(Map<String,Integer> blocks, Map<String,Integer> items) { this.blocks = blocks; this.items = items; }

    public static PackIdMap empty() { return EMPTY; }

    /** A file that cannot be evaluated costs its ids, not the pack: every id then reads as unknown. */
    static PackIdMap load(ShaderPackSource pack, Collection<String> paths, Map<String,String> defines, List<PackDiagnostic> warnings) throws IOException {
        return new PackIdMap(read(pack,paths,BLOCKS,"block.",defines,warnings),read(pack,paths,ITEMS,"item.",defines,warnings));
    }
    private static Map<String,Integer> read(ShaderPackSource pack, Collection<String> paths, String file, String prefix, Map<String,String> defines, List<PackDiagnostic> warnings) throws IOException {
        if (!paths.contains(file)) return Map.of();
        String text = pack.read(file);
        try {
            Properties properties = new Properties();
            properties.load(new StringReader(PackPropertiesPreprocessor.process(text,defines)));
            return parse(properties,prefix,file,warnings);
        } catch (IOException | IllegalArgumentException failure) {
            warnings.add(PackDiagnostic.degraded(PackDiagnostic.Category.PROPERTY,file,"Ignored, every id reads as unknown: "+failure.getMessage()));
            return Map.of();
        }
    }
    private static Map<String,Integer> parse(Properties properties, String prefix, String file, List<PackDiagnostic> warnings) {
        Map<String,Integer> ids = new HashMap<>();
        // Properties has no line order, so the lowest id decides a name listed twice: the result must not vary per run.
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(prefix)) continue;
            int id;
            try { id = Integer.parseInt(key.substring(prefix.length())); }
            catch (NumberFormatException invalid) { warnings.add(PackDiagnostic.degraded(PackDiagnostic.Category.PROPERTY,file+"."+key,"Ignored: the id is not a number")); continue; }
            for (String name : properties.getProperty(key).trim().split("\\s+")) if (!name.isEmpty()) ids.merge(canonical(name),id,Math::min);
        }
        return Map.copyOf(ids);
    }
    private static String canonical(String name) { return name.indexOf(':') < 0 ? "minecraft:"+name : name; }

    public int block(String identifier) { return blocks.getOrDefault(identifier,UNKNOWN); }
    public int item(String identifier) { return items.getOrDefault(identifier,UNKNOWN); }
}
