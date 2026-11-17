package dev.overhaul.minecraft.blaze.ui;

import dev.overhaul.minecraft.blaze.shader.BlazePackRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lists the packs in {@code shaderpacks/}; Apply compiles the chosen one and runs it on the next frame, so the world
 * beside the page shows the result. The running pack's own options open as a second view and apply together, since
 * each apply compiles the pack again.
 */
final class ShaderPackPage extends SettingsPage {
    private static final Logger LOG = LoggerFactory.getLogger(ShaderPackPage.class);
    private static final String SAVED = "saved.";
    private final Minecraft minecraft;
    private final SettingsDraft draft;
    private final Path folder;
    private final SettingsDraft.Value<String> pack;
    private Properties file;
    private String error = "";
    private Map<String, Boolean> defaults = Map.of();
    /** Options committed by an Apply and not yet written, with the pack they belong to. */
    private final Map<String, Boolean> pending = new LinkedHashMap<>();
    private String pendingPack = "";
    private boolean optionsView;

    ShaderPackPage(Minecraft minecraft, SettingsDraft draft, Runnable refresh) {
        super("packs", SettingsIcon.CUBE, refresh);
        this.minecraft = minecraft;
        this.draft = draft;
        this.folder = minecraft.gameDirectory.toPath().resolve(BlazePackRequest.FOLDER);
        this.file = read();
        this.pack = draft.value("shaderPack", this::current, this::select);
        draft.onApplied(this::writeOptions);
    }

    @Override
    boolean preview() {
        return true;
    }

    @Override
    List<SettingsRow> rows() {
        if (optionsView && !pack.get().equals(current())) optionsView = false;
        return optionsView ? optionRows() : packRows();
    }

    private List<SettingsRow> packRows() {
        List<SettingsRow> rows = new ArrayList<>();
        List<Path> packs = packs();
        rows.add(new SettingsRow.Header(text("section.installed"), () -> String.valueOf(packs.size())));
        rows.add(choice("", text("off.name"), text("off.desc")));
        for (Path entry : packs) {
            String name = entry.getFileName().toString();
            rows.add(choice(name, name, text(Files.isDirectory(entry) ? "folder.desc" : "zip.desc")));
        }
        rows.add(new SettingsRow.Header(text("section.pack")));
        rows.add(new SettingsRow.Action(text("options.name"), text("options.desc"), SettingsIcon.NEXT, () -> text("options.open"), this::openOptions)
                .enabledWhen(() -> !current().isEmpty() && !pack.changed()).note(() -> pack.changed() ? text("options.apply_first") : null));
        rows.add(new SettingsRow.Action(text("reload.name"), text("reload.desc"), SettingsIcon.RELOAD, () -> text("reload.run"), BlazePackRequest::reload)
                .enabledWhen(() -> !current().isEmpty()));
        rows.add(new SettingsRow.Action(text("folder.name"), text("folder.open.desc"), SettingsIcon.FOLDER, () -> text("folder.open"), this::openFolder));
        return rows;
    }

    private SettingsRow choice(String name, String label, String description) {
        return new SettingsRow.Action(label, description, null, () -> name.equals(pack.get()) ? text("chosen") : text("choose"), () -> pack.set(name))
                .selectedWhen(() -> name.equals(pack.get())).changedWhen(() -> pack.changed() && name.equals(pack.get()))
                .enabledWhen(() -> !BlazePackRequest.launchOverride())
                .note(() -> BlazePackRequest.launchOverride() ? OverhaulLang.get("overhaul.ui.launch_override", "-Doverhaul.shaderPack") : null);
    }

    private List<SettingsRow> optionRows() {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(new SettingsRow.Action(text("back.name"), text("back.desc"), SettingsIcon.PREV, () -> OverhaulLang.get("overhaul.ui.back"), () -> {
            optionsView = false;
            refresh.run();
        }));
        if (!error.isEmpty()) {
            rows.add(new SettingsRow.Header(text("options.unreadable")));
            rows.add(new SettingsRow.Info(error, error, () -> ""));
            return rows;
        }
        String name = current();
        Map<String, Boolean> written = written();
        rows.add(new SettingsRow.Action(text("defaults.name"), text("defaults.desc"), SettingsIcon.RELOAD, () -> OverhaulLang.get("overhaul.ui.reset"),
                () -> defaults.forEach((option, fallback) -> option(name, option, fallback, written).set(fallback))));
        rows.add(new SettingsRow.Header(name, () -> String.valueOf(defaults.size())));
        defaults.forEach((option, fallback) -> rows.add(new SettingsRow.Toggle(option,
                text("option.desc", option, OverhaulLang.get(fallback ? "overhaul.ui.on" : "overhaul.ui.off")), option(name, option, fallback, written))));
        return rows;
    }

    private SettingsDraft.Value<Boolean> option(String name, String option, boolean fallback, Map<String, Boolean> written) {
        return draft.value("shaderPack." + name + "." + option, () -> written.getOrDefault(option, fallback), value -> {
            pendingPack = name;
            pending.put(option, value);
        });
    }

    /** The options the file holds for the running pack: only those that differ from the pack's defaults. */
    private Map<String, Boolean> written() {
        Map<String, Boolean> values = new LinkedHashMap<>();
        for (String token : file.getProperty("options", "").split(",")) {
            int separator = token.indexOf('=');
            if (separator > 0) values.put(token.substring(0, separator).trim(), Boolean.parseBoolean(token.substring(separator + 1).trim()));
        }
        return values;
    }

    private List<Path> packs() {
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.filter(entry -> Files.isDirectory(entry) || entry.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .sorted(Comparator.comparing(entry -> entry.getFileName().toString().toLowerCase(Locale.ROOT))).toList();
        } catch (IOException failure) {
            LOG.warn("Cannot list {}", folder, failure);
            return List.of();
        }
    }

    /** The file name of the pack the configuration names, or empty for none. */
    private String current() {
        String path = file.getProperty("pack", "").trim();
        if (path.isEmpty()) return "";
        Path name = Path.of(path).getFileName();
        return name == null ? "" : name.toString();
    }

    /** Options belong to one pack, so the outgoing pack's are kept under its name and the incoming pack's restored. */
    private void select(String name) {
        String previous = current();
        if (!previous.isEmpty()) file.setProperty(SAVED + previous, file.getProperty("options", ""));
        if (name.isEmpty()) {
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
        try {
            defaults = BlazePackRequest.options(folder.resolve(current()));
        } catch (IOException | RuntimeException failure) {
            error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        }
        optionsView = true;
        refresh.run();
    }

    /** Only values that differ from the pack's defaults are written, so a pack update can move its defaults. */
    private void writeOptions() {
        if (pending.isEmpty()) return;
        if (pendingPack.equals(current())) {
            Map<String, Boolean> values = written();
            values.putAll(pending);
            StringBuilder options = new StringBuilder();
            values.forEach((name, value) -> {
                if (defaults.containsKey(name) && defaults.get(name) != value.booleanValue()) {
                    if (!options.isEmpty()) options.append(',');
                    options.append(name).append('=').append(value);
                }
            });
            file.setProperty("options", options.toString());
            write();
        }
        pending.clear();
    }

    private void openFolder() {
        try {
            Files.createDirectories(folder);
        } catch (IOException failure) {
            LOG.warn("Cannot create {}", folder, failure);
        }
        BlazeUiTier.openFolder(folder);
    }

    @Override
    void filesDropped(List<Path> files) {
        for (Path source : files) {
            String name = source.getFileName().toString();
            if (!Files.isDirectory(source) && !name.toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
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
    String readout() {
        String name = pack.get();
        return name.isEmpty() ? OverhaulLang.get("overhaul.ui.off") : name;
    }

    @Override
    String status() {
        if (minecraft.level == null) return text("status.menu");
        return BlazePackRequest.status();
    }

    @Override
    boolean statusIsError() {
        return minecraft.level != null && BlazePackRequest.status().startsWith("Failed");
    }
}
