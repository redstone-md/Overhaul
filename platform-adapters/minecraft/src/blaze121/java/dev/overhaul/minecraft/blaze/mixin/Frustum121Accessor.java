package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Frustum.class)
interface Frustum121Accessor {
    @Accessor("intersection") FrustumIntersection overhaul$intersection();
    @Accessor("camX") double overhaul$camX();
    @Accessor("camY") double overhaul$camY();
    @Accessor("camZ") double overhaul$camZ();
}
