package dev.overhaul.minecraft.blaze;

/** Client-instance ownership, available before Minecraft's Window field is assigned. */
public interface MinecraftBlazeAccess {
    BlazeRenderer overhaul$blazeRenderer();
    boolean overhaul$blazeWasSelected();
    void overhaul$installBlazeRenderer(BlazeRenderer renderer);
    void overhaul$removeBlazeRenderer(BlazeRenderer renderer);
}
