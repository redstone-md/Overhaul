package dev.overhaul.shader.pack;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** Resolves one complete program directory; dimension selection never merges independently authored worlds. */
public final class ShaderPackDimensions {
    private ShaderPackDimensions() {}

    static String select(ShaderPackSource source, List<String> paths, String dimension) throws IOException {
        String identifier = canonicalIdentifier(dimension);
        if (paths.contains("dimension.properties")) {
            return mappings(source.read("dimension.properties"), identifier);
        }
        String legacy = switch (identifier) {
            case "minecraft:overworld" -> "world0/";
            case "minecraft:the_nether" -> "world-1/";
            case "minecraft:the_end" -> "world1/";
            default -> "";
        };
        // Iris uses root programs for a dimension without its own folder. An existing folder still
        // replaces root even when empty, so a pack can leave a dimension unshaded.
        return paths.contains(legacy) ? legacy : "";
    }

    private static String mappings(String text, String dimension) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(text));
        Map<String,String> folders = new TreeMap<>();
        String fallback = null;
        for (String key : new java.util.TreeSet<>(properties.stringPropertyNames())) {
            if (!key.startsWith("dimension.")) throw invalid("Unknown dimension mapping: " + key);
            String folder = key.substring("dimension.".length());
            if (!folder.equals(ShaderPackSource.normalize(folder))) throw invalid("Noncanonical dimension folder: " + folder);
            String value = properties.getProperty(key).trim();
            if (value.isEmpty()) throw invalid("Dimension mapping has no dimensions: " + key);
            for (String token : value.split("\\s+")) {
                if (token.equals("*")) {
                    if (fallback != null && !fallback.equals(folder)) throw invalid("Multiple wildcard dimension folders");
                    fallback = folder;
                } else {
                    String identifier = canonicalIdentifier(token);
                    String previous = folders.putIfAbsent(identifier, folder);
                    if (previous != null && !previous.equals(folder)) throw invalid("Dimension mapped to multiple folders: " + identifier);
                }
            }
        }
        String selected = folders.getOrDefault(dimension, fallback);
        return selected == null ? "" : selected + "/";
    }

    private static String canonicalIdentifier(String value) {
        if (value == null) return "overhaul:unmapped";
        String identifier = value.indexOf(':') < 0 ? "minecraft:" + value : value;
        if (!identifier.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw invalid("Invalid dimension identifier: " + value);
        return identifier;
    }

    static String legacyIdentifier(Integer dimension) {
        if (dimension == null) return null;
        return switch (dimension) {
            case -1 -> "minecraft:the_nether";
            case 0 -> "minecraft:overworld";
            case 1 -> "minecraft:the_end";
            default -> "overhaul:legacy_" + (dimension < 0 ? "minus_" + -(long)dimension : dimension);
        };
    }

    private static UnsupportedShaderException invalid(String message) { return new UnsupportedShaderException(message); }
}
