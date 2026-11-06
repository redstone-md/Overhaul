package dev.overhaul.minecraft.blaze.shader;

import dev.overhaul.shader.pack.PackOptionDiscovery;
import dev.overhaul.shader.pack.ShaderPackSource;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;

/**
 * The settings screen's side of the shader pack request: it writes the same file {@link BlazePackFrame} reads, then
 * asks the frame to read it again, so a pack switches on the next frame without a resource reload.
 */
public final class BlazePackRequest {
    public static final String FOLDER = "shaderpacks";
    private static volatile int generation;
    private static volatile String status = "Off";

    private BlazePackRequest() {
    }

    public static Properties load(Minecraft minecraft) {
        return BlazePackConfiguration.read(minecraft);
    }

    public static void save(Minecraft minecraft, Properties properties) throws IOException {
        Path path = BlazePackConfiguration.path(minecraft);
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path)) {
            properties.store(writer, "Overhaul shader pack: pack, options (NAME=true|false), defines (NAME=VALUE)");
        }
        reload();
    }

    /** Compiles the pack again on the next frame, also after its files changed on disk. */
    public static void reload() {
        generation++;
    }

    static int generation() {
        return generation;
    }

    static void report(String text) {
        status = text;
    }

    /** Off, Active with the pack's name, or Failed with the reason; set by the frame that runs the pack. */
    public static String status() {
        return status;
    }

    /** True while a pack is installed and drawing, so camera effects can go where packs expect them. */
    public static boolean running() {
        return status.startsWith("Active");
    }

    /** {@code -Doverhaul.shaderPack} wins over the file. */
    public static boolean launchOverride() {
        return System.getProperty("overhaul.shaderPack") != null;
    }

    /** The pack's boolean options and their defaults, sorted by name. */
    public static Map<String, Boolean> options(Path pack) throws IOException {
        try (ShaderPackSource source = ShaderPackSource.open(pack)) {
            return new TreeMap<>(PackOptionDiscovery.discover(source, source.paths()).booleans());
        }
    }
}
