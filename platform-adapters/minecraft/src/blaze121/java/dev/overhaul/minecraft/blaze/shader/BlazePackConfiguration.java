package dev.overhaul.minecraft.blaze.shader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import net.minecraft.client.Minecraft;

/**
 * Which shader pack to run: {@code config/overhaul-shaderpack.properties} (keys pack, options, defines), and the
 * {@code overhaul.shaderPack*} system properties over it. The world is compared by identity, so a new level reinstalls.
 */
record BlazePackConfiguration(Object world, Object dimension, Path pack, String options, String defines) {
    private static final String FILE = "overhaul-shaderpack.properties";

    static BlazePackConfiguration capture(Minecraft minecraft, Properties file) {
        var world = minecraft.level;
        String requested = value("overhaul.shaderPack", file, "pack");
        Path pack = requested.isEmpty() ? null : minecraft.gameDirectory.toPath().resolve(requested).normalize();
        return new BlazePackConfiguration(world, world == null ? null : world.dimension(), pack,
                value("overhaul.shaderPackOptions", file, "options"), value("overhaul.shaderPackDefines", file, "defines"));
    }

    /** A missing file means no pack; an unreadable one is the user's error and is reported as such. */
    static Properties read(Minecraft minecraft) {
        Properties properties = new Properties();
        Path path = minecraft.gameDirectory.toPath().resolve("config").resolve(FILE);
        if (!Files.isRegularFile(path)) return properties;
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read " + path, failure);
        }
        return properties;
    }

    boolean configured() {
        return pack != null;
    }

    Map<String, Boolean> selectedOptions() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (var entry : pairs(options).entrySet()) {
            if (!entry.getValue().equals("true") && !entry.getValue().equals("false")) {
                throw new IllegalArgumentException("Shader-pack options require true/false values");
            }
            result.put(entry.getKey(), Boolean.valueOf(entry.getValue()));
        }
        return result;
    }

    Map<String, String> definitions() {
        return pairs(defines);
    }

    private static String value(String property, Properties file, String key) {
        String explicit = System.getProperty(property);
        return (explicit != null ? explicit : file.getProperty(key, "")).trim();
    }

    private static Map<String, String> pairs(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        if (text.isBlank()) return result;
        for (String token : text.split(",", -1)) {
            int separator = token.indexOf('=');
            if (separator < 1) throw new IllegalArgumentException("Shader-pack configuration requires NAME=VALUE entries");
            String name = token.substring(0, separator).trim(), value = token.substring(separator + 1).trim();
            if (!name.matches("[A-Za-z_]\\w*") || value.isEmpty() || result.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("Invalid or duplicate shader-pack option: " + name);
            }
        }
        return result;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BlazePackConfiguration key && world == key.world && Objects.equals(dimension, key.dimension)
                && Objects.equals(pack, key.pack) && options.equals(key.options) && defines.equals(key.defines);
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(world) + Objects.hash(dimension, pack, options, defines);
    }
}
