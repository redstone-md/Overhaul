package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.shaders.Program;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Program.class)
public interface ProgramConstructorAccess {
    @Invoker("<init>") static Program overhaul$create(Program.Type type,int id,String name){throw new AssertionError();}
}
