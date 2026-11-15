package dev.overhaul.minecraft.blaze.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.overhaul.minecraft.blaze.BlazeOptimization;
import dev.overhaul.minecraft.blaze.BlazePackScene;
import dev.overhaul.minecraft.blaze.BlazeShadowAnimation;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws the level's entities from the pack's light camera, as Iris does, so mobs, players and dropped items cast
 * shadows, and block entities such as chests and signs with them. The game's renderers run unchanged with the light's
 * matrices set; the scene sends their draws to the shadow map. Ground shadow blobs and hitboxes are left out, since
 * they are no part of the entity's shape.
 */
final class BlazePackEntityShadows {
    private static final Logger LOG = LoggerFactory.getLogger(BlazePackEntityShadows.class);
    /** Room around a caster for shadow filtering, view bobbing and a pack's soft shadow spread when testing its reach. */
    private static final float REACH_PAD = 4;
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final BlazeShadowReach reach = new BlazeShadowReach();
    private boolean reaching;
    private final Matrix4f combined = new Matrix4f();
    private BlazeShadowReplay replay;
    private boolean failed;

    /**
     * Casts every entity and block entity within {@code limit} blocks of the camera, tested against the light's frustum
     * when it has one and, with {@link BlazeOptimization#ENTITY_SHADOW_REACH}, against where its shadow can fall in
     * {@code player}'s view.
     */
    void cast(Minecraft minecraft, BlazePackScene scene, Camera camera, float partial, Matrix4f view, Matrix4f projection, Matrix4fc player,
              dev.overhaul.shader.pack.PackShadowSettings casters, boolean bounded, double limit) {
        if (!casters.entities() && !casters.player() && !casters.blockEntities()) return;
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        // The game points the dispatcher at its camera only once it draws entities, after the first shadow pass.
        if (failed || minecraft.level == null || dispatcher.camera == null) return;
        if (bounded) frustum.set(projection.mul(view, combined));
        reaching = bounded && BlazeOptimization.ENTITY_SHADOW_REACH.on() && reach.aim(player, view, projection);
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
                if (!casters.entities() && !(casters.player() && entity instanceof net.minecraft.world.entity.player.Player)) continue;
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
                if (bounded && !casts((float) (box.minX - x), (float) (box.minY - y), (float) (box.minZ - z),
                        (float) (box.maxX - x), (float) (box.maxY - y), (float) (box.maxZ - z))) continue;
                float yaw = Mth.lerp(partial, entity.yRotO, entity.getYRot());
                // In first person only this pass animates the player, so its pose may be paced.
                if (entity == minecraft.player && minecraft.options.getCameraType().isFirstPerson()) {
                    localPlayer(dispatcher, entity, ex, ey, ez, yaw, partial, buffers);
                    continue;
                }
                dispatcher.render(entity, ex, ey, ez, yaw, partial, pose, buffers, dispatcher.getPackedLightCoords(entity, partial));
            }
            if (casters.blockEntities()) blockEntities(minecraft, partial, pose, buffers, x, y, z, bounded, limit);
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

    /**
     * Draws the local player in first person: on a paced frame the last recorded geometry when there is one, else the
     * renderers, recording what they draw while {@link BlazeShadowReplay} is on.
     */
    private void localPlayer(EntityRenderDispatcher dispatcher, Entity entity, double ex, double ey, double ez, float yaw, float partial,
                             MultiBufferSource.BufferSource buffers) {
        if (replay == null) replay = new BlazeShadowReplay(buffers);
        boolean paced = BlazeShadowAnimation.beginLocalPlayer();
        try {
            if (paced && replay.replay(ex, ey, ez)) return;
            if (paced || !replay.on()) {
                dispatcher.render(entity, ex, ey, ez, yaw, partial, new PoseStack(), buffers, dispatcher.getPackedLightCoords(entity, partial));
                return;
            }
            boolean drawn = false;
            MultiBufferSource recording = replay.record(ex, ey, ez);
            try {
                dispatcher.render(entity, ex, ey, ez, yaw, partial, new PoseStack(), recording, dispatcher.getPackedLightCoords(entity, partial));
                drawn = true;
            } catch (RuntimeException failure) {
                LOG.warn("The local player's shadow failed to record", failure);
            } finally {
                replay.recorded(drawn);
            }
        } finally {
            if (paced) BlazeShadowAnimation.end();
        }
    }

    /** Whether a camera-relative box lies in the light's frustum and, while reaching, its shadow can land in view. */
    private boolean casts(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return frustum.testAab(minX, minY, minZ, maxX, maxY, maxZ) && (!reaching
                || reach.reaches(minX - REACH_PAD, minY - REACH_PAD, minZ - REACH_PAD, maxX + REACH_PAD, maxY + REACH_PAD, maxZ + REACH_PAD));
    }

    /** The loaded chunks around the camera hold every block entity that could cast; each draws as the game's pass does. */
    private void blockEntities(Minecraft minecraft, float partial, PoseStack pose, MultiBufferSource buffers,
                               double x, double y, double z, boolean bounded, double limit) {
        BlockEntityRenderDispatcher dispatcher = minecraft.getBlockEntityRenderDispatcher();
        if (dispatcher.camera == null) return;
        int reach = (int) Math.ceil(limit / 16) + 1, centreX = Mth.floor(x) >> 4, centreZ = Mth.floor(z) >> 4;
        var chunks = minecraft.level.getChunkSource();
        for (int cx = centreX - reach; cx <= centreX + reach; cx++) for (int cz = centreZ - reach; cz <= centreZ + reach; cz++) {
            LevelChunk chunk = chunks.getChunkNow(cx, cz);
            if (chunk == null) continue;
            for (BlockEntity entity : chunk.getBlockEntities().values()) {
                var renderer = dispatcher.getRenderer(entity);
                if (renderer == null || entity.isRemoved()) continue;
                var at = entity.getBlockPos();
                double bx = at.getX() - x, by = at.getY() - y, bz = at.getZ() - z;
                if (Math.abs(bx) > limit || Math.abs(by) > limit || Math.abs(bz) > limit) continue;
                if (bounded && !renderer.shouldRenderOffScreen(entity) && !casts((float) bx - 1, (float) by - 1, (float) bz - 1,
                        (float) bx + 2, (float) by + 2, (float) bz + 2)) continue;
                pose.pushPose();
                pose.translate(bx, by, bz);
                dispatcher.render(entity, partial, pose, buffers);
                pose.popPose();
            }
        }
    }
}
