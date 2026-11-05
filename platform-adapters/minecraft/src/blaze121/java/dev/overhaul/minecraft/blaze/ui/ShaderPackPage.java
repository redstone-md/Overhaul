package dev.overhaul.minecraft.blaze.ui;

import dev.overhaul.minecraft.blaze.shader.BlazePackRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lists the packs in {@code shaderpacks/} and runs the chosen one on the next frame, so the world behind the page shows
 * the result. A pack's own options open as a second view and apply together, since each apply compiles the pack again.
 */
final class ShaderPackPage extends SettingsPage {
    private static final Logger LOG = LoggerFactory.getLogger(ShaderPackPage.class);
    private static final String SAVED = "saved.";
    private final Minecraft minecraft;
    private final Path folder;
    private Properties file;
    private String error = "";
    private Map<String, Boolean> defaults;
    private final Map<String, Boolean> pending = new LinkedHashMap<>();
    private boolean optionsView, dirty;

    ShaderPackPage(Minecraft minecraft, Runnable refresh) {
        super(refresh);
        this.minecraft = minecraft;
        this.folder = minecraft.gameDirectory.toPath().resolve(BlazePackRequest.FOLDER);
        this.file = read();
    }

    @Override
    String title() {
        return "Shader packs";
    }

    @Override
    boolean preview() {
        return true;
    }

    @Override
    List<SettingsRow> rows() {
        return optionsView ? optionRows() : packRows();
    }

    private List<SettingsRow> packRows() {
        List<SettingsRow> rows = new ArrayList<>();
        List<Path> packs = packs();
        rows.add(new SettingsRow.Header("Installed, " + packs.size()));
        rows.add(guarded(new SettingsRow.Action(Component.literal("Off"), "No shader pack. The world draws with the game's own shaders", () -> select(null), () -> "")
                .selectedWhen(() -> current().isEmpty())));
        for (Path pack : packs) {
            String name = pack.getFileName().toString();
            String kind = Files.isDirectory(pack) ? "Folder" : "Zip archive";
            rows.add(guarded(new SettingsRow.Action(Component.literal(name), kind + " in shaderpacks. Selecting it compiles the pack and runs it on the next frame",
                    () -> select(name), () -> "").selectedWhen(() -> name.equals(current()))));
        }
        rows.add(new SettingsRow.Header("Pack"));
        rows.add(guarded(new SettingsRow.Action(Component.literal("Pack options"), "The selected pack's switches. Changes apply together", this::openOptions, () -> "›")
                .enabledWhen(() -> !current().isEmpty())));
        rows.add(new SettingsRow.Action(Component.literal("Reload pack"), "Compiles the selected pack again, after its files changed on disk", BlazePackRequest::reload, () -> "")
                .enabledWhen(() -> !current().isEmpty()));
        rows.add(new SettingsRow.Action(Component.literal("Open shader pack folder"), "Put zip archives or folders here, or drop them on this screen", this::openFolder, () -> ""));
        return rows;
    }

    private List<SettingsRow> optionRows() {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(new SettingsRow.Action(Component.literal("Back"), "Return to the pack list", () -> { optionsView = false; refresh.run(); }, () -> "‹"));
        rows.add(new SettingsRow.Action(Component.literal("Apply"), "Writes the options and compiles the pack again", this::applyOptions,
                () -> dirty ? "Unsaved" : "").enabledWhen(() -> dirty));
        rows.add(new SettingsRow.Action(Component.literal("Reset to defaults"), "Every option back to the pack's default. Press Apply to run it",
                () -> { pending.clear(); dirty = true; }, () -> ""));
        if (!error.isEmpty()) {
            rows.add(new SettingsRow.Header("Options cannot be read"));
            rows.add(new SettingsRow.Action(Component.literal(error), error, () -> { }, () -> "").enabledWhen(() -> false));
            return rows;
        }
        rows.add(new SettingsRow.Header(current() + ", " + defaults.size() + " options"));
        for (var entry : defaults.entrySet()) {
            String name = entry.getKey();
            boolean fallback = entry.getValue();
            rows.add(new SettingsRow.Toggle(Component.literal(name), "Pack option " + name + ". Default " + (fallback ? "on" : "off"),
                    () -> pending.getOrDefault(name, fallback), value -> { pending.put(name, value); dirty = true; }));
        }
        return rows;
    }

    private SettingsRow guarded(SettingsRow row) {
        return row.note(() -> BlazePackRequest.launchOverride() ? "The launch argument -Doverhaul.shaderPack overrides this choice" : null);
    }

    private List<Path> packs() {
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.filter(p -> Files.isDirectory(p) || p.getFileName().toString().toLowerCase().endsWith(".zip"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase())).toList();
        } catch (IOException failure) {
            LOG.warn("Cannot list {}", folder, failure);
            return List.of();
        }
    }

    /** The selected pack's file name, or empty for none. */
    private String current() {
        String pack = file.getProperty("pack", "").trim();
        if (pack.isEmpty()) return "";
        Path name = Path.of(pack).getFileName();
        return name == null ? "" : name.toString();
    }

    /** Options belong to one pack, so the outgoing pack's are kept under its name and the incoming pack's restored. */
    private void select(String name) {
        String previous = current();
        if (!previous.isEmpty()) file.setProperty(SAVED + previous, file.getProperty("options", ""));
        if (name == null) {
            file.setProperty("pack", "");
            file.setProperty("options", "");
        } else {
            file.setProperty("pack", BlazePackRequest.FOLDER + "/" + name);
            file.setProperty("options", file.getProperty(SAVED + name, ""));
        }
        write();
    }

    private void openOptions() {
        defaults = Map.of();
        error = "";
        pending.clear();
        dirty = false;
        try {
            defaults = BlazePackRequest.options(folder.resolve(current()));
            for (String token : file.getProperty("options", "").split(",")) {
                int separator = token.indexOf('=');
                if (separator > 0) pending.put(token.substring(0, separator).trim(), Boolean.parseBoolean(token.substring(separator + 1).trim()));
            }
        } catch (IOException | RuntimeException failure) {
            error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        }
        optionsView = true;
        refresh.run();
    }

    /** Only values that differ from the pack's defaults are written, so a pack update can move its defaults. */
    private void applyOptions() {
        StringBuilder options = new StringBuilder();
        pending.forEach((name, value) -> {
            if (defaults.containsKey(name) && defaults.get(name) != value.booleanValue()) {
                if (!options.isEmpty()) options.append(',');
                options.append(name).append('=').append(value);
            }
        });
        file.setProperty("options", options.toString());
        write();
        dirty = false;
    }

    private void openFolder() {
        try {
            Files.createDirectories(folder);
        } catch (IOException failure) {
            LOG.warn("Cannot create {}", folder, failure);
        }
        Util.getPlatform().openPath(folder);
    }

    @Override
    void filesDropped(List<Path> files) {
        for (Path source : files) {
            String name = source.getFileName().toString();
            if (!Files.isDirectory(source) && !name.toLowerCase().endsWith(".zip")) continue;
            try {
                Files.createDirectories(folder);
                copy(source, folder.resolve(name));
            } catch (IOException failure) {
                LOG.warn("Cannot copy {} into {}", source, folder, failure);
            }
        }
        refresh.run();
    }

    private static void copy(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            Files.copy(source, target);
            return;
        }
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
    }

    private Properties read() {
        try {
            return BlazePackRequest.load(minecraft);
        } catch (RuntimeException failure) {
            LOG.warn("Shader pack configuration is unreadable; starting from an empty one", failure);
            return new Properties();
        }
    }

    private void write() {
        try {
            BlazePackRequest.save(minecraft, file);
        } catch (IOException failure) {
            LOG.warn("Cannot write the shader pack configuration", failure);
        }
    }

    @Override
    String status() {
        if (minecraft.level == null) return "Open a world to see the pack run";
        return BlazePackRequest.status();
    }

    @Override
    boolean statusIsError() {
        return minecraft.level != null && BlazePackRequest.status().startsWith("Failed");
    }
}
