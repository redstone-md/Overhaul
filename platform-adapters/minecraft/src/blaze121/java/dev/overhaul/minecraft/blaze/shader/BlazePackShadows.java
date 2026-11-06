package dev.overhaul.minecraft.blaze.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.minecraft.blaze.BlazePackScene;
import dev.overhaul.minecraft.blaze.BlazeRenderer;
import dev.overhaul.minecraft.blaze.BlazeSectionBounds;
import dev.overhaul.minecraft.blaze.BlazeVertexBufferAccess;
import dev.overhaul.shader.pack.PackShadowSettings;
import dev.overhaul.vulkan.PackGraphRuntime;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

/**
 * Draws terrain from the pack's light camera into its shadow map, before the frame's gbuffers read that map. The game's
 * own visibility list follows the player's camera, so casters come from every built section the light can see. A
 * section the game has never built casts nothing until it does; entities do not cast yet.
 */
final class BlazePackShadows {
    private final BlazeRenderer renderer;
    private final BlazePackScene scene;
    private final PackGraphRuntime runtime;
    private final PackShadowSettings settings;
    private final List<SectionRenderDispatcher.RenderSection> casters = new ArrayList<>();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Matrix4f view = new Matrix4f(), projection = new Matrix4f(), modelView = new Matrix4f(), combined = new Matrix4f();
    private SectionRenderDispatcher.RenderSection[] indexed = new SectionRenderDispatcher.RenderSection[0];
    private int[] origins = new int[0];
    private int seenMoves = -1;

    BlazePackShadows(BlazeRenderer renderer, BlazePackScene scene, PackGraphRuntime runtime, PackShadowSettings settings) {
        this.renderer = renderer;
        this.scene = scene;
        this.runtime = runtime;
        this.settings = settings;
    }

    void render(Minecraft minecraft, ViewArea sections, BlazePackUniforms uniforms, Camera camera, LightTexture light, PackGraphRuntime.Frame frame) {
        uniforms.shadowView(view);
        uniforms.shadowProjection(projection);
        var position = camera.getPosition();
        collect(sections, position.x, position.y, position.z);
        light.turnOnLightLayer();
        scene.beginShadows(minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId(), RenderSystem.getShaderTexture(2), settings.resolution());
        runtime.prepareShadows(renderer.packCommands());
        cast(RenderType.solid(), "shadow_solid", position.x, position.y, position.z);
        cast(RenderType.cutoutMipped(), "shadow_cutout", position.x, position.y, position.z);
        cast(RenderType.cutout(), "shadow_cutout", position.x, position.y, position.z);
        runtime.snapshotOpaqueShadow(renderer.packCommands());
        cast(RenderType.translucent(), "shadow_water", position.x, position.y, position.z);
        runtime.finishShadows(renderer.packCommands(), frame, uniforms);
    }

    /** The bounds test runs over a flat copy of the origins: reaching each section object for them cost most of the frame. */
    private void collect(ViewArea sections, double x, double y, double z) {
        casters.clear();
        if (sections == null) return;
        index(sections.sections);
        // The Iris field-of-view matrix has an unusual m33, so planes taken from it would not bound what it draws.
        boolean bounded = settings.fov() == null;
        if (bounded) frustum.set(projection.mul(view, combined));
        float multiplier = settings.renderMultiplier();
        double limit = multiplier > 0 ? settings.distance() * multiplier + 16 : Double.MAX_VALUE;
        int[] origins = this.origins;
        for (int index = 0, at = 0; index < indexed.length; index++, at += 3) {
            float minX = (float) (origins[at] - x), minY = (float) (origins[at + 1] - y), minZ = (float) (origins[at + 2] - z);
            if (Math.abs(minX + 8) > limit || Math.abs(minY + 8) > limit || Math.abs(minZ + 8) > limit) continue;
            if (bounded && !frustum.testAab(minX, minY, minZ, minX + 16, minY + 16, minZ + 16)) continue;
            var section = indexed[index];
            if (section.getCompiled() != SectionRenderDispatcher.CompiledSection.UNCOMPILED) casters.add(section);
        }
    }

    private void index(SectionRenderDispatcher.RenderSection[] all) {
        if (all == indexed && seenMoves == BlazeSectionBounds.originMoves()) return;
        indexed = all;
        seenMoves = BlazeSectionBounds.originMoves();
        if (origins.length != all.length * 3) origins = new int[all.length * 3];
        for (int index = 0; index < all.length; index++) {
            var origin = all[index].getOrigin();
            origins[index * 3] = origin.getX();
            origins[index * 3 + 1] = origin.getY();
            origins[index * 3 + 2] = origin.getZ();
        }
    }

    private void cast(RenderType layer, String program, double x, double y, double z) {
        scene.shadowLayer(program);
        for (int index = 0; index < casters.size(); index++) {
            var section = casters.get(index);
            if (section.getCompiled().isEmpty(layer)) continue;
            var origin = section.getOrigin();
            view.translate((float) (origin.getX() - x), (float) (origin.getY() - y), (float) (origin.getZ() - z), modelView);
            scene.castShadow(((BlazeVertexBufferAccess) section.getBuffer(layer)).overhaul$existingMesh(), modelView, projection);
        }
    }
}
