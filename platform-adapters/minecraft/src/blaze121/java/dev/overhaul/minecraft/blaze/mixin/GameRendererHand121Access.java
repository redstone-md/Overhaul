package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** What the first-person hand needs from the game renderer when a shader pack draws it inside the world frame. */
@Mixin(GameRenderer.class)
public interface GameRendererHand121Access {
    @Accessor("renderHand") boolean overhaul$rendersHand();
    @Invoker("getFov") double overhaul$fov(Camera camera, float partial, boolean useSetting);
    @Invoker("bobHurt") void overhaul$bobHurt(PoseStack pose, float partial);
    @Invoker("bobView") void overhaul$bobView(PoseStack pose, float partial);
}
