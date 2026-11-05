package dev.overhaul.minecraft.blaze.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.minecraft.blaze.BlazePackScene;
import dev.overhaul.minecraft.blaze.BlazeRenderer;
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
        runtime.prepareShadows(renderer.commands());
        cast(RenderType.solid(), "shadow_solid", position.x, position.y, position.z);
        cast(RenderType.cutoutMipped(), "shadow_cutout", position.x, position.y, position.z);
        cast(RenderType.cutout(), "shadow_cutout", position.x, position.y, position.z);
        runtime.snapshotOpaqueShadow(renderer.commands());
        cast(RenderType.translucent(), "shadow_water", position.x, position.y, position.z);
        runtime.finishShadows(renderer.commands(), frame, uniforms);
    }

    private void collect(ViewArea sections, double x, double y, double z) {
        casters.clear();
        if (sections == null) return;
        // The Iris field-of-view matrix has an unusual m33, so planes taken from it would not bound what it draws.
        boolean bounded = settings.fov() == null;
        if (bounded) frustum.set(projection.mul(view, combined));
        float multiplier = settings.renderMultiplier();
        double limit = multiplier > 0 ? settings.distance() * multiplier + 16 : Double.MAX_VALUE;
        for (var section : sections.sections) {
            if (section.getCompiled() == SectionRenderDispatcher.CompiledSection.UNCOMPILED) continue;
            var origin = section.getOrigin();
            float minX = (float) (origin.getX() - x), minY = (float) (origin.getY() - y), minZ = (float) (origin.getZ() - z);
            if (Math.abs(minX + 8) > limit || Math.abs(minY + 8) > limit || Math.abs(minZ + 8) > limit) continue;
            if (bounded && !frustum.testAab(minX, minY, minZ, minX + 16, minY + 16, minZ + 16)) continue;
            casters.add(section);
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
