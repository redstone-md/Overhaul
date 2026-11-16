package dev.overhaul.minecraft.blaze;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs the commands written in the file named by -Doverhaul.dev.commandFile on the world this client hosts, one per
 * line, each time the file's text changes, so unattended picture checks can move the player into a cave or over
 * water without typing. Does nothing without it, or on a world another machine hosts.
 */
final class BlazeDevCommands {
    private static final String FILE = System.getProperty("overhaul.dev.commandFile");
    private static String ran = "";
    private static int frames;

    private BlazeDevCommands() {
    }

    static void run(Minecraft client) {
        if (FILE == null || frames++ % 120 != 0) return;
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) return;
        String text;
        try {
            text = Files.readString(Path.of(FILE)).trim();
        } catch (Exception absent) {
            return;
        }
        if (text.equals(ran)) return;
        ran = text;
        List<String> commands = text.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
        server.execute(() -> {
            for (String command : commands) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        });
    }
}
