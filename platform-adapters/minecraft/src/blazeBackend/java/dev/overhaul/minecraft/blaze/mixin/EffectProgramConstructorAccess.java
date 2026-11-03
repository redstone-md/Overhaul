package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.shaders.EffectProgram;
import com.mojang.blaze3d.shaders.Program;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(EffectProgram.class)
public interface EffectProgramConstructorAccess {
    @Invoker("<init>") static EffectProgram overhaul$create(Program.Type type,int id,String name){throw new AssertionError();}
}
