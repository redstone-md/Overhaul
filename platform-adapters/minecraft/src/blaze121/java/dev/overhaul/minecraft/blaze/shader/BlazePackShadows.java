package dev.overhaul.minecraft.blaze.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.minecraft.blaze.BlazeMesh;
import dev.overhaul.minecraft.blaze.BlazeOptimization;
import dev.overhaul.minecraft.blaze.BlazePackScene;
import dev.overhaul.minecraft.blaze.BlazeRenderer;
import dev.overhaul.minecraft.blaze.BlazeSectionBatch;
import dev.overhaul.minecraft.blaze.BlazeSectionBounds;
import dev.overhaul.minecraft.blaze.BlazeSectionBuffers;
import dev.overhaul.minecraft.blaze.BlazeSectionGrid;
import dev.overhaul.minecraft.blaze.BlazeVisibleSections;
import dev.overhaul.shader.pack.PackShadowSettings;
import dev.overhaul.vulkan.PackGraphRuntime;
import java.util.Arrays;
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
    private static final RenderType[] LAYERS = {RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout(), RenderType.translucent()};
    private static final int[] LAYER_IDS = new int[LAYERS.length];
    private static final long CASTING;
    static {
        long casting = 0;
        for (int layer = 0; layer < LAYERS.length; layer++) casting |= 1L << (LAYER_IDS[layer] = LAYERS[layer].getChunkLayerId());
        CASTING = casting;
    }
    /**
     * Kept casters stand while the camera moves less than DRIFT blocks and the light turns less than about half a degree:
     * the frustum is widened by MARGIN, which covers that drift, a pack's two-block shadow snap and the turn at 200 blocks.
     */
    private static final float MARGIN = 10, DRIFT = 4, TURN = 0.99996f;
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Matrix4f view = new Matrix4f(), projection = new Matrix4f(), modelView = new Matrix4f(), combined = new Matrix4f();
    private SectionRenderDispatcher.RenderSection[] indexed = new SectionRenderDispatcher.RenderSection[0];
    private int[] origins = new int[0], casters = new int[0];
    /** Per section, the layer mask its meshes were read for, or 0 while one of them has yet to upload. */
    private long[] seen = new long[0];
    /** Per section, its casting layers and the change count they were read at; read again only after some section changed. */
    private long[] contents = new long[0], contentsAt = new long[0];
    private BlazeMesh[] meshes = new BlazeMesh[0];
    private int casterCount;
    /** Per layer, the casters that have a mesh in it, so each pass walks only meshes it draws. */
    private final BlazeMesh[][] layerMeshes = new BlazeMesh[LAYERS.length][0];
    private final int[][] layerCasters = new int[LAYERS.length][0];
    private final int[] layerCounts = new int[LAYERS.length];
    /** Per section, whether this frame's light frustum holds it without the margin; read only while {@link #tight}. */
    private boolean[] inside = new boolean[0];
    private boolean tight;
    private double keptX, keptY, keptZ;
    private float lightX, lightY, lightZ;
    private long keptAt = -1;
    private boolean kept, incomplete;
    private int seenMoves = -1;
    private int width, height, depth;

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
        tighten(position.x, position.y, position.z);
        light.turnOnLightLayer();
        scene.beginShadows(minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId(), RenderSystem.getShaderTexture(2), settings.resolution());
        runtime.prepareShadows(renderer.packCommands());
        cast(0, "shadow_solid", position.x, position.y, position.z);
        cast(1, "shadow_cutout", position.x, position.y, position.z);
        cast(2, "shadow_cutout", position.x, position.y, position.z);
        runtime.snapshotOpaqueShadow(renderer.packCommands());
        cast(3, "shadow_water", position.x, position.y, position.z);
        runtime.finishShadows(renderer.packCommands(), frame, uniforms);
    }

    /**
     * The bounds test runs over a flat copy of the origins: reaching each section object for them cost most of the frame.
     * A caster's layer meshes are looked up again only when its compiled state changed, so the passes read flat arrays.
     */
    private void collect(ViewArea sections, double x, double y, double z) {
        if (sections == null) {
            casterCount = 0;
            Arrays.fill(layerCounts, 0);
            kept = false;
            return;
        }
        long now = BlazeVisibleSections.changes();
        if (index(sections.sections)) kept = false;
        if (still(now, x, y, z)) return;
        casterCount = 0;
        incomplete = false;
        // Without the grid mixin, or with a grid that does not cover the array, sections are walked as columns of one.
        if (sections instanceof BlazeSectionGrid grid) {
            width = grid.overhaul$gridWidth();
            height = grid.overhaul$gridHeight();
            depth = grid.overhaul$gridDepth();
        }
        if (!(sections instanceof BlazeSectionGrid) || width * height * depth != indexed.length) {
            width = indexed.length;
            height = depth = 1;
        }
        // The Iris field-of-view matrix has an unusual m33, so planes taken from it would not bound what it draws.
        boolean bounded = settings.fov() == null;
        if (bounded) frustum.set(projection.mul(view, combined));
        float multiplier = settings.renderMultiplier();
        boolean reuse = BlazeOptimization.PACK_SHADOW_REUSE.on();
        float margin = reuse ? MARGIN : 0;
        double limit = multiplier > 0 ? settings.distance() * multiplier + 16 + margin : Double.MAX_VALUE;
        int[] origins = this.origins;
        // Columns first: most fall outside the light's frustum whole, and one fully inside needs no test per section.
        for (int column = 0; column < width * depth; column++) {
            int base = column / width * height * width + column % width, at = base * 3;
            float minX = (float) (origins[at] - x), minZ = (float) (origins[at + 2] - z), bottom = (float) (origins[at + 1] - y);
            if (Math.abs(minX + 8) > limit || Math.abs(minZ + 8) > limit) continue;
            int side = bounded ? frustum.intersectAab(minX - margin, bottom - margin, minZ - margin, minX + 16 + margin, bottom + height * 16 + margin, minZ + 16 + margin) : FrustumIntersection.INSIDE;
            if (side != FrustumIntersection.INSIDE && side != FrustumIntersection.INTERSECT) continue;
            for (int layer = 0; layer < height; layer++) collect(base + layer * width, minX, minZ, (float) (origins[(base + layer * width) * 3 + 1] - y),
                    limit, margin, bounded && side != FrustumIntersection.INSIDE, now);
        }
        split();
        kept = reuse && !incomplete;
        keptAt = now;
        keptX = x;
        keptY = y;
        keptZ = z;
        lightX = view.m02();
        lightY = view.m12();
        lightZ = view.m22();
    }

    /** True when the casters kept from an earlier frame still cover this one. */
    private boolean still(long now, double x, double y, double z) {
        if (!kept || now != keptAt || Math.abs(x - keptX) > DRIFT || Math.abs(y - keptY) > DRIFT || Math.abs(z - keptZ) > DRIFT) return false;
        float turn = view.m02() * lightX + view.m12() * lightY + view.m22() * lightZ;
        float length = view.m02() * view.m02() + view.m12() * view.m12() + view.m22() * view.m22();
        return turn > TURN * (float) Math.sqrt(length * (lightX * lightX + lightY * lightY + lightZ * lightZ));
    }

    private void collect(int index, float minX, float minZ, float minY, double limit, float margin, boolean test, long now) {
        if (Math.abs(minY + 8) > limit) return;
        if (test && !frustum.testAab(minX - margin, minY - margin, minZ - margin, minX + 16 + margin, minY + 16 + margin, minZ + 16 + margin)) return;
        if (contentsAt[index] != now) {
            contentsAt[index] = now;
            contents[index] = ((BlazeSectionBuffers) indexed[index]).overhaul$contents() & CASTING;
        }
        long layers = contents[index];
        if (layers == 0) return;
        if (layers != seen[index]) remember(index, layers);
        casters[casterCount++] = index;
    }

    /**
     * Kept casters were picked through a frustum widened by the margin. Once the frame is bound by the GPU, drawing
     * them all costs more than this test, so each frame keeps only those inside its own frustum and distance.
     */
    private void tighten(double x, double y, double z) {
        tight = BlazeOptimization.PACK_SHADOW_REUSE.on() && settings.fov() == null && casterCount != 0;
        if (!tight) return;
        frustum.set(projection.mul(view, combined));
        float multiplier = settings.renderMultiplier();
        double limit = multiplier > 0 ? settings.distance() * multiplier + 16 : Double.MAX_VALUE;
        if (inside.length != indexed.length) inside = new boolean[indexed.length];
        int[] origins = this.origins;
        for (int at = 0; at < casterCount; at++) {
            int index = casters[at];
            float minX = (float) (origins[index * 3] - x), minY = (float) (origins[index * 3 + 1] - y), minZ = (float) (origins[index * 3 + 2] - z);
            inside[index] = Math.abs(minX + 8) <= limit && Math.abs(minY + 8) <= limit && Math.abs(minZ + 8) <= limit
                    && frustum.testAab(minX, minY, minZ, minX + 16, minY + 16, minZ + 16);
        }
    }

    private void split() {
        for (int layer = 0; layer < LAYERS.length; layer++) {
            if (layerMeshes[layer].length < casterCount) {
                layerMeshes[layer] = new BlazeMesh[casters.length];
                layerCasters[layer] = new int[casters.length];
            }
            BlazeMesh[] into = layerMeshes[layer];
            int[] owners = layerCasters[layer];
            int count = 0;
            for (int at = 0; at < casterCount; at++) {
                int index = casters[at];
                BlazeMesh mesh = meshes[index * LAYERS.length + layer];
                if (mesh == null) continue;
                into[count] = mesh;
                owners[count++] = index;
            }
            Arrays.fill(into, count, Math.max(count, Math.min(layerCounts[layer], into.length)), null);
            layerCounts[layer] = count;
        }
    }

    /** A buffer keeps its mesh until it closes, so the meshes stand until the section's layers change. */
    private void remember(int index, long contents) {
        var section = (BlazeSectionBuffers) indexed[index];
        boolean complete = true;
        for (int layer = 0; layer < LAYERS.length; layer++) {
            BlazeMesh mesh = (contents & 1L << LAYER_IDS[layer]) == 0 ? null : section.overhaul$mesh(LAYER_IDS[layer]);
            if (mesh == null && (contents & 1L << LAYER_IDS[layer]) != 0) complete = false;
            meshes[index * LAYERS.length + layer] = mesh;
        }
        seen[index] = complete ? contents : 0;
        if (!complete) incomplete = true;
    }

    /** True when the sections were indexed again, which drops kept casters. */
    private boolean index(SectionRenderDispatcher.RenderSection[] all) {
        if (all == indexed && seenMoves == BlazeSectionBounds.originMoves()) return false;
        // A section moved to a new origin is rebuilt through an empty state, so only a new array clears these.
        if (all != indexed) {
            contents = new long[all.length];
            contentsAt = new long[all.length];
            Arrays.fill(contentsAt, -1);
            seen = new long[all.length];
            meshes = new BlazeMesh[all.length * LAYERS.length];
        }
        indexed = all;
        seenMoves = BlazeSectionBounds.originMoves();
        if (origins.length != all.length * 3) origins = new int[all.length * 3];
        if (casters.length != all.length) casters = new int[all.length];
        for (int index = 0; index < all.length; index++) {
            var origin = all[index].getOrigin();
            origins[index * 3] = origin.getX();
            origins[index * 3 + 1] = origin.getY();
            origins[index * 3 + 2] = origin.getZ();
        }
        return true;
    }

    private void cast(int layer, String program, double x, double y, double z) {
        scene.shadowLayer(program);
        BlazeSectionBatch batch = scene.shadowSections(view, projection);
        int[] origins = this.origins, owners = layerCasters[layer];
        BlazeMesh[] meshes = layerMeshes[layer];
        for (int at = 0, count = layerCounts[layer]; at < count; at++) {
            int index = owners[at];
            if (tight && !inside[index]) continue;
            BlazeMesh mesh = meshes[at];
            float offsetX = (float) (origins[index * 3] - x), offsetY = (float) (origins[index * 3 + 1] - y), offsetZ = (float) (origins[index * 3 + 2] - z);
            if (batch != null) {
                batch.add(mesh, offsetX, offsetY, offsetZ);
            } else {
                view.translate(offsetX, offsetY, offsetZ, modelView);
                scene.castShadow(mesh, modelView, projection);
            }
        }
        if (batch != null) batch.end();
    }
}
