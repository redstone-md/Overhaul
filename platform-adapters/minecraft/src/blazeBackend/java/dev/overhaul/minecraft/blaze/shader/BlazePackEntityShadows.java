package dev.overhaul.minecraft.blaze.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.overhaul.minecraft.blaze.BlazePackScene;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws the level's entities from the pack's light camera, as Iris does, so mobs, players and dropped items cast
 * shadows. The game's renderers run unchanged with the light's matrices set; the scene sends their draws to the
 * shadow map. Ground shadow blobs and hitboxes are left out, since they are no part of the entity's shape.
 */
final class BlazePackEntityShadows {
    private static final Logger LOG = LoggerFactory.getLogger(BlazePackEntityShadows.class);
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Matrix4f combined = new Matrix4f();
    private boolean failed;

    /** Casts every entity within {@code limit} blocks of the camera, tested against the light's frustum when it has one. */
    void cast(Minecraft minecraft, BlazePackScene scene, Camera camera, float partial, Matrix4f view, Matrix4f projection, boolean bounded, double limit) {
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        // The game points the dispatcher at its camera only once it draws entities, after the first shadow pass.
        if (failed || minecraft.level == null || dispatcher.camera == null) return;
        if (bounded) frustum.set(projection.mul(view, combined));
        var position = camera.getPosition();
        double x = position.x, y = position.y, z = position.z;
        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        boolean hitboxes = dispatcher.shouldRenderHitBoxes();
        RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
        BlazePackGame.modelView(view);
        dispatcher.setRenderShadow(false);
        dispatcher.setRenderHitBoxes(false);
        scene.castEntities(true);
        var buffers = minecraft.renderBuffers().bufferSource();
        PoseStack pose = new PoseStack();
        try {
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity.isRemoved() || !entity.shouldRender(x, y, z)) continue;
                // As the game does: an entity that has not ticked yet has no previous position to blend from.
                if (entity.tickCount == 0) {
                    entity.xOld = entity.getX();
                    entity.yOld = entity.getY();
                    entity.zOld = entity.getZ();
                }
                double ex = Mth.lerp(partial, entity.xOld, entity.getX()) - x, ey = Mth.lerp(partial, entity.yOld, entity.getY()) - y,
                        ez = Mth.lerp(partial, entity.zOld, entity.getZ()) - z;
                if (Math.abs(ex) > limit || Math.abs(ey) > limit || Math.abs(ez) > limit) continue;
                var box = entity.getBoundingBox();
                if (bounded && !frustum.testAab((float) (box.minX - x), (float) (box.minY - y), (float) (box.minZ - z),
                        (float) (box.maxX - x), (float) (box.maxY - y), (float) (box.maxZ - z))) continue;
                dispatcher.render(entity, ex, ey, ez, Mth.lerp(partial, entity.yRotO, entity.getYRot()), partial, pose, buffers,
                        dispatcher.getPackedLightCoords(entity, partial));
            }
        } catch (RuntimeException failure) {
            failed = true;
            LOG.warn("Entities stop casting pack shadows", failure);
        } finally {
            buffers.endBatch();
            scene.castEntities(false);
            dispatcher.setRenderShadow(true);
            dispatcher.setRenderHitBoxes(hitboxes);
            BlazePackGame.restoreModelView();
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
        }
    }
}
