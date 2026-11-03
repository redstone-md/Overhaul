package dev.overhaul.minecraft.backendmixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RenderType.class)
public interface RenderTypeFactoryAccess {
    @Invoker("create")
    static RenderType overhaul$create(String name, RenderSetup state) { throw new AssertionError("Mixin invoker"); }
}
