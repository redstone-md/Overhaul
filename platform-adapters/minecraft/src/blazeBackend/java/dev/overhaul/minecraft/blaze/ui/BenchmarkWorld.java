package dev.overhaul.minecraft.blaze.ui;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.slf4j.Logger;

/**
 * The benchmark's own world: made from the main menu with a fixed seed, so every machine measures the same land, and
 * deleted again when the run ends. A marker file written before the game fills it is what allows a leftover from a
 * crashed run to be removed;
 * a world of the same name without the marker belongs to the player and is never touched.
 */
final class BenchmarkWorld {
    static final String NAME = "Overhaul benchmark";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MARKER = "overhaul-benchmark";
    private static final long SEED = 8_412_975_031L;
    private static final long NOON = 6000;
    private static Screen back;
    private static Consumer<BenchmarkResult> finished;
    private static boolean running;

    private BenchmarkWorld() {}

    /** Creates and joins the world; returns why it cannot, or null once it is on its way. */
    static String start(Minecraft minecraft, Screen back, Consumer<BenchmarkResult> finished) {
        if (minecraft.level != null) return "Leave the world first; the benchmark runs from the main menu";
        if (!clear(minecraft)) return "A world named \"" + NAME + "\" is in the way; rename it to run the benchmark";
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings(NAME, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        // Marked before the game writes into it, so a crash part way through still leaves a world this class may delete.
        try {
            Path folder = Files.createDirectories(minecraft.getLevelSource().getBaseDir().resolve(NAME));
            Files.writeString(folder.resolve(MARKER), "Made by the Overhaul benchmark; deleted when it ends\n");
        } catch (IOException e) {
            LOGGER.warn("Overhaul benchmark could not prepare its world", e);
            return "Could not create the test world: " + e.getMessage();
        }
        BenchmarkWorld.back = back;
        BenchmarkWorld.finished = finished;
        running = false;
        BlazeUiTier.createWorld(minecraft, NAME, settings, new WorldOptions(SEED, false, false), back);
        return null;
    }

    /** Swaps the empty screen the game shows on joining the benchmark world for the run itself. */
    static Screen joined(Minecraft minecraft, Screen requested) {
        if (back == null || running || requested != null || minecraft.player == null) return requested;
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null || !NAME.equals(server.getWorldData().getLevelName())) return requested;
        running = true;
        server.execute(() -> server.overworld().setDayTime(NOON));
        return new BenchmarkScreen(finished, () -> leave(minecraft));
    }

    /** Leaves the world on the next tick, outside any frame, then deletes it and shows the screen the run began from. */
    private static void leave(Minecraft minecraft) {
        Screen to = back;
        back = null;
        finished = null;
        running = false;
        minecraft.tell(() -> {
            BlazeUiTier.leaveWorld(minecraft);
            minecraft.setScreen(to);
            if (!clear(minecraft)) LOGGER.warn("Overhaul benchmark could not delete its world \"{}\"", NAME);
        });
    }

    /** True when no world of the name is left; only a world carrying the marker is deleted. */
    private static boolean clear(Minecraft minecraft) {
        LevelStorageSource source = minecraft.getLevelSource();
        if (!source.levelExists(NAME)) return true;
        Path folder = source.getBaseDir().resolve(NAME);
        if (!Files.isRegularFile(folder.resolve(MARKER))) return false;
        try (LevelStorageSource.LevelStorageAccess access = source.createAccess(NAME)) {
            access.deleteLevel();
            return true;
        } catch (IOException e) {
            LOGGER.warn("Overhaul benchmark could not delete \"{}\"", folder, e);
            return false;
        }
    }
}
