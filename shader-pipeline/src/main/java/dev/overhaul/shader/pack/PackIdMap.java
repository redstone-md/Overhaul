package dev.overhaul.shader.pack;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

/**
 * Numeric ids a pack assigns in block.properties and item.properties, the values mc_Entity and heldItemId
 * read. A block entry may name states, as in {@code minecraft:wheat:age=6,7}: it then matches only states whose
 * properties take one of the listed values, and wins over a plain entry for the same block.
 */
public final class PackIdMap {
    public static final int UNKNOWN = -1;
    private static final String BLOCKS = "block.properties", ITEMS = "item.properties";
    private static final PackIdMap EMPTY = new PackIdMap(Map.of(),Map.of());
    private final Map<String,List<StateRule>> blocks;
    private final Map<String,Integer> items;

    /** {@code conditions} maps a property to the values it may take; an empty map matches every state. */
    private record StateRule(Map<String,Set<String>> conditions, int id) {
        boolean matches(Function<String,String> property) {
            for (var condition : conditions.entrySet()) {
                String value = property.apply(condition.getKey());
                if (value == null || !condition.getValue().contains(value)) return false;
            }
            return true;
        }
    }

    private PackIdMap(Map<String,List<StateRule>> blocks, Map<String,Integer> items) { this.blocks = blocks; this.items = items; }

    public static PackIdMap empty() { return EMPTY; }

    /** A file that cannot be evaluated costs its ids, not the pack: every id then reads as unknown. */
    static PackIdMap load(ShaderPackSource pack, Collection<String> paths, Map<String,String> defines, List<PackDiagnostic> warnings) throws IOException {
        Map<String,List<StateRule>> blocks = new HashMap<>();
        Map<String,Integer> items = new HashMap<>();
        Properties blockFile = read(pack,paths,BLOCKS,defines,warnings), itemFile = read(pack,paths,ITEMS,defines,warnings);
        entries(blockFile,"block.",BLOCKS,warnings,(name,id) -> rule(blocks,name,id));
        entries(itemFile,"item.",ITEMS,warnings,(name,id) -> items.merge(canonical(name),id,Math::min));
        // Properties has no line order, so rules sort by specificity then id: the result must not vary per run.
        Map<String,List<StateRule>> sorted = new HashMap<>();
        for (var entry : blocks.entrySet()) {
            List<StateRule> rules = new ArrayList<>(entry.getValue());
            rules.sort((left,right) -> left.conditions().isEmpty() != right.conditions().isEmpty()
                    ? (left.conditions().isEmpty() ? 1 : -1) : Integer.compare(left.id(),right.id()));
            sorted.put(entry.getKey(),List.copyOf(rules));
        }
        return new PackIdMap(Map.copyOf(sorted),Map.copyOf(items));
    }

    private static Properties read(ShaderPackSource pack, Collection<String> paths, String file, Map<String,String> defines, List<PackDiagnostic> warnings) throws IOException {
        Properties properties = new Properties();
        if (!paths.contains(file)) return properties;
        String text = pack.read(file);
        try {
            properties.load(new StringReader(PackPropertiesPreprocessor.process(text,defines)));
        } catch (IOException | IllegalArgumentException failure) {
            warnings.add(PackDiagnostic.degraded(PackDiagnostic.Category.PROPERTY,file,"Ignored, every id reads as unknown: "+failure.getMessage()));
            properties.clear();
        }
        return properties;
    }

    private interface Entry { void accept(String name, int id); }

    private static void entries(Properties properties, String prefix, String file, List<PackDiagnostic> warnings, Entry sink) {
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(prefix)) continue;
            int id;
            try { id = Integer.parseInt(key.substring(prefix.length())); }
            catch (NumberFormatException invalid) { warnings.add(PackDiagnostic.degraded(PackDiagnostic.Category.PROPERTY,file+"."+key,"Ignored: the id is not a number")); continue; }
            for (String name : properties.getProperty(key).trim().split("\\s+")) if (!name.isEmpty()) sink.accept(name,id);
        }
    }

    /** Splits {@code [namespace:]path[:property=value[,value]]...} into the block and its state conditions. */
    private static void rule(Map<String,List<StateRule>> blocks, String entry, int id) {
        List<String> name = new ArrayList<>();
        Map<String,Set<String>> conditions = new HashMap<>();
        for (String part : entry.split(":")) {
            int equals = part.indexOf('=');
            if (equals < 0) name.add(part);
            else conditions.put(part.substring(0,equals),Set.copyOf(List.of(part.substring(equals+1).split(","))));
        }
        if (name.isEmpty() || name.size() > 2) return;
        String block = name.size() == 1 ? "minecraft:"+name.get(0) : name.get(0)+":"+name.get(1);
        blocks.computeIfAbsent(block,ignored -> new ArrayList<>()).add(new StateRule(Map.copyOf(conditions),id));
    }

    private static String canonical(String name) { return name.indexOf(':') < 0 ? "minecraft:"+name : name; }

    /** {@code property} returns a state's value for a property name, or null when the block has no such property. */
    public int block(String identifier, Function<String,String> property) {
        List<StateRule> rules = blocks.get(identifier);
        if (rules == null) return UNKNOWN;
        for (StateRule rule : rules) if (rule.matches(property)) return rule.id();
        return UNKNOWN;
    }
    public boolean hasBlocks() { return !blocks.isEmpty(); }
    public int item(String identifier) { return items.getOrDefault(identifier,UNKNOWN); }
}
