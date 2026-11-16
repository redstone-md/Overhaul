package dev.overhaul.minecraft.blaze;

import net.minecraft.client.multiplayer.ClientLevel;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Holds the client's time of day at the number written in the file named by -Doverhaul.dev.dayTimeFile, so
 * unattended picture checks can look at dusk and night without typing a command. Does nothing without it.
 */
final class BlazeDevClock {
    private static final String FILE = System.getProperty("overhaul.dev.dayTimeFile");
    private static long held = -1;
    private static int frames;

    private BlazeDevClock() {
    }

    static void hold(ClientLevel world) {
        if (FILE == null) return;
        if (frames++ % 120 == 0) {
            try {
                held = Long.parseLong(Files.readString(Path.of(FILE)).trim());
            } catch (Exception absent) {
                held = -1;
            }
        }
        if (held >= 0) world.setDayTime(held);
    }
}
