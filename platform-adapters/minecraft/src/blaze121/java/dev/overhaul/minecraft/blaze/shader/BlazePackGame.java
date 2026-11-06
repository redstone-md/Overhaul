package dev.overhaul.minecraft.blaze.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4fc;
import dev.overhaul.minecraft.blaze.BlazeLoader121;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.biome.Biome;

/** The few game calls the shared pack frame makes that changed between game versions. */
final class BlazePackGame {
    private BlazePackGame() {}

    /** How far an effect with a fade, such as darkness, has faded in. */
    static float blend(MobEffectInstance effect, LivingEntity living, float partial) {
        return effect.getBlendFactor(living, partial);
    }

    static float downfall(Biome biome) {
        return BlazeLoader121.downfall(biome);
    }

    /** Puts the game's model-view matrix at {@code view} until {@link #restoreModelView}. */
    static void modelView(Matrix4fc view) {
        RenderSystem.getModelViewStack().pushMatrix().set(view);
        RenderSystem.applyModelViewMatrix();
    }

    static void restoreModelView() {
        RenderSystem.getModelViewStack().popMatrix();
        RenderSystem.applyModelViewMatrix();
    }
}
