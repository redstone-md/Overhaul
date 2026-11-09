package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the upscaler swap the main target the world draws into for one frame. */
@Mixin(Minecraft.class)
public interface MinecraftMainTarget121Access {
    @Mutable @Accessor("mainRenderTarget") void overhaul$mainTarget(RenderTarget target);
}
