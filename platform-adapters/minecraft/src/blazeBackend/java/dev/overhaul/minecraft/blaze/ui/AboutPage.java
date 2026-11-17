package dev.overhaul.minecraft.blaze.ui;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Manifest;
import net.minecraft.SharedConstants;
import net.minecraft.Util;

/** What is installed and where the project lives. */
final class AboutPage extends SettingsPage {
    private static final String SITE = "https://redstone.md", SOURCE = "https://github.com/redstone-md/Overhaul", AUTHOR = "w_zrd";
    private static final String VERSION = version();

    AboutPage(Runnable refresh) {
        super("about", SettingsIcon.INFO, refresh);
    }

    @Override
    List<SettingsRow> rows() {
        List<SettingsRow> rows = new ArrayList<>();
        rows.add(new SettingsRow.Header(text("section.mod")));
        rows.add(new SettingsRow.Info(text("version.name"), text("version.desc"), () -> VERSION));
        rows.add(new SettingsRow.Info(text("game.name"), text("game.desc"), () -> SharedConstants.getCurrentVersion().getName()));
        rows.add(new SettingsRow.Info(text("author.name"), text("author.desc"), () -> AUTHOR));
        rows.add(new SettingsRow.Header(text("section.links")));
        rows.add(link("site", SettingsIcon.WORLD, SITE, "redstone.md"));
        rows.add(link("source", SettingsIcon.GIT, SOURCE, "GitHub"));
        rows.add(link("bug", SettingsIcon.BUG, SOURCE + "/issues", text("bug.open")));
        return rows;
    }

    private SettingsRow link(String name, SettingsIcon icon, String address, String caption) {
        return new SettingsRow.Action(text(name + ".name"), text(name + ".desc"), icon, () -> caption, () -> Util.getPlatform().openUri(address));
    }

    /** The version the jar was built as; a run from classes has no manifest of its own and shows as a development build. */
    private static String version() {
        String file = AboutPage.class.getName().replace('.', '/') + ".class";
        URL located = AboutPage.class.getClassLoader().getResource(file);
        if (located != null) {
            String address = located.toString();
            String root = address.substring(0, address.length() - file.length());
            try (InputStream stream = new URL(root + "META-INF/MANIFEST.MF").openStream()) {
                String version = new Manifest(stream).getMainAttributes().getValue("Implementation-Version");
                if (version != null && !version.isBlank()) return version;
            } catch (IOException | RuntimeException ignored) {
                // Falls through to the package's own answer.
            }
        }
        Package owner = AboutPage.class.getPackage();
        String version = owner == null ? null : owner.getImplementationVersion();
        return version == null || version.isBlank() ? "dev" : version;
    }

    @Override
    String readout() {
        return VERSION;
    }
}
