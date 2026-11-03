package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.shaders.Program;

/** Source ownership travels with the actual game stage object, never its global OpenGL cache. */
public interface BlazeStageAccess {
    record Stage(BlazePrograms owner,Program.Type type,String name,String source){}
    Stage overhaul$stage();
    void overhaul$stage(Stage stage);
}
