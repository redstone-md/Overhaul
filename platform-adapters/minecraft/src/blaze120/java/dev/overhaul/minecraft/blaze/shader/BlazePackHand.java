package dev.overhaul.minecraft.blaze.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.overhaul.minecraft.blaze.mixin.GameRendererHand120Access;
import dev.overhaul.shader.pack.PackBuiltinMacros;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import org.joml.Matrix4f;

/**
 * Draws the first-person hand inside the pack's frame, the way Iris does, so the pack's hand programs light it and its
 * composites see it. The depth is squeezed around the middle of the range, where packs look for the hand
 * (MC_HAND_DEPTH), so the hand never sinks into the terrain it is held against.
 */
public final class BlazePackHand {
    private static final float DEPTH = Float.parseFloat(PackBuiltinMacros.HAND_DEPTH);
    private static boolean drawn;

    private BlazePackHand() {}

    /** True once per frame in which the pack drew the hand, so the game's own hand pass leaves it out. */
    public static boolean takeDrawn() {
        boolean was = drawn;
        drawn = false;
        return was;
    }

    /** A hand the pack drew in a frame whose hand pass never ran must not hide the next frame's hand. */
    public static void frameStarts() {
        drawn = false;
    }

    static boolean wanted(Minecraft minecraft, GameRenderer game) {
        boolean sleeping = minecraft.getCameraEntity() instanceof LivingEntity living && living.isSleeping();
        return ((GameRendererHand120Access) game).overhaul$rendersHand() && !game.isPanoramicMode()
                && minecraft.options.getCameraType().isFirstPerson() && !sleeping && !minecraft.options.hideGui
                && minecraft.gameMode != null && minecraft.gameMode.getPlayerMode() != GameType.SPECTATOR && minecraft.player != null;
    }

    /** Mirrors GameRenderer.renderItemInHand, without the screen overlays, which stay on top of the pack's image. */
    static void draw(Minecraft minecraft, GameRenderer game, Camera camera, float partial, Matrix4f viewRotation) {
        GameRendererHand120Access access = (GameRendererHand120Access) game;
        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        Matrix4f projection = new Matrix4f().scaling(1, 1, DEPTH).mul(game.getProjectionMatrix(access.overhaul$fov(camera, partial, false)));
        RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
        PoseStack pose = new PoseStack();
        pose.mulPoseMatrix(viewRotation.invert(new Matrix4f()));
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.mulPoseMatrix(viewRotation);
        RenderSystem.applyModelViewMatrix();
        try {
            access.overhaul$bobHurt(pose, partial);
            if (minecraft.options.bobView().get()) access.overhaul$bobView(pose, partial);
            game.lightTexture().turnOnLightLayer();
            game.itemInHandRenderer.renderHandsWithItems(partial, pose, minecraft.renderBuffers().bufferSource(), minecraft.player,
                    minecraft.getEntityRenderDispatcher().getPackedLightCoords(minecraft.player, partial));
            game.lightTexture().turnOffLightLayer();
            drawn = true;
        } finally {
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
        }
    }
}
