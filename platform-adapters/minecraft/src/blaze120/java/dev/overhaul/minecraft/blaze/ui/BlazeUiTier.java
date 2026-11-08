package dev.overhaul.minecraft.blaze.ui;

import dev.overhaul.minecraft.blaze.mixin.OptionsSubScreen120Access;
import java.nio.file.Path;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** What the settings screen needs from the game where 1.20 and 1.21 differ: the video screen, folders, options. */
final class BlazeUiTier {
    private BlazeUiTier() {}

    static boolean isVideo(Screen screen) {
        return screen instanceof VideoSettingsScreen;
    }

    static Screen parent(Screen video) {
        return ((OptionsSubScreen120Access) video).overhaul$lastScreen();
    }

    static Screen vanilla(Screen parent, Minecraft minecraft) {
        return new VideoSettingsScreen(parent, minecraft.options);
    }

    static void openFolder(Path folder) {
        Util.getPlatform().openFile(folder.toFile());
    }

    /** The menu blur option, which only 1.21 has. */
    static OptionInstance<Integer> menuBlur(Options options) {
        return null;
    }

    /** Creates a default world and joins it; 1.20 shows failures on its own screens. */
    static void createWorld(Minecraft minecraft, String name, LevelSettings settings, WorldOptions options, Screen back) {
        minecraft.createWorldOpenFlows().createFreshLevel(name, settings, options, WorldPresets::createNormalWorldDimensions);
    }

    /** Leaves the singleplayer world and waits until its server has saved and stopped, as the pause menu does. */
    static void leaveWorld(Minecraft minecraft) {
        if (minecraft.level != null) minecraft.level.disconnect();
        minecraft.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
    }
}
