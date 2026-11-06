package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.platform.GlStateManager;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiHeightFogMixMode;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiFogRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBoxGroupShading;
import com.seibel.distanthorizons.common.render.openGl.generic.GlGenericObjectVertexContainer;
import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GlQuadIndexBuffer;
import com.seibel.distanthorizons.core.config.Config.Client.Advanced.Debugging;
import com.seibel.distanthorizons.core.config.Config.Client.Advanced.Graphics.Experimental;
import com.seibel.distanthorizons.core.config.Config.Client.Advanced.Graphics.NoiseTexture;
import com.seibel.distanthorizons.core.config.Config.Client.Advanced.Graphics.Quality;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.render.renderer.RenderableBoxGroup;
import com.seibel.distanthorizons.core.util.RenderUtil;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IDhGenericObjectVertexBufferContainer.EState;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.client.Minecraft;

/** Reads one Distant Horizons frame out of the mod's own objects, the only class besides the mixins that links against them. */
public final class BlazeDhFrame {
    private static final int FLAG_NOISE = 1, FLAG_DITHER = 2, FLAG_WHITE = 4;
    private static final int FOG_ON = 1, FOG_HEIGHT = 2, FOG_CAMERA = 4, FOG_UP = 8, FOG_DOWN = 16, FOG_SPHERICAL = 32;

    /** The state DH's terrain pass sets for itself, then every uploaded LOD of the pass. */
    public static void drawTerrain(BlazeRenderer renderer, RenderParams params, boolean opaque, SortedArraySet<LodBufferContainer> containers) {
        GlStateManager._enableCull();
        if (opaque) {
            GlStateManager._disableBlend();
        } else {
            GlStateManager._enableBlend();
            GlStateManager._enableDepthTest();
            GlStateManager._blendEquation(32774);
            GlStateManager._blendFuncSeparate(770, 771, 1, 771);
        }
        GlStateManager._colorMask(true, true, true, true);
        if (containers == null) return;
        if (packDraws(renderer)) {
            drawThroughPack(renderer, params, opaque, containers);
            return;
        }
        BlazeDh dh = renderer.dh();
        dh.begin(terrainSettings(params));
        for (int index = 0; index < containers.size(); index++) {
            LodBufferContainer container = containers.get(index);
            if (!container.buffersUploaded) continue;
            float x = (float) (container.minCornerBlockPos.getX() - params.exactCameraPosition.x);
            float y = (float) (container.minCornerBlockPos.getY() - params.exactCameraPosition.y);
            float z = (float) (container.minCornerBlockPos.getZ() - params.exactCameraPosition.z);
            for (IVertexBufferWrapper wrapper : opaque ? container.vboOpaqueWrappers : container.vboTransparentWrappers) {
                if (wrapper != null) draw(dh, (GLVertexBuffer) wrapper, x, y, z);
            }
        }
        dh.end();
    }

    /** A shader pack with DH programs draws the LODs into its own gbuffers, which leaves DH's composite nothing to add. */
    private static boolean packDraws(BlazeRenderer renderer) {
        BlazePackScene scene = renderer.packScene();
        return scene != null && scene.drawsDistant();
    }

    private static void drawThroughPack(BlazeRenderer renderer, RenderParams params, boolean opaque, SortedArraySet<LodBufferContainer> containers) {
        BlazePackDistant.planes(RenderUtil.getNearClipPlaneInBlocks(), RenderUtil.getFarClipPlaneDistanceInBlocks(), Quality.lodChunkRenderDistanceRadius.get() * 16);
        BlazePackScene scene = renderer.packScene();
        if (!opaque && scene.holdDistant(() -> drawThroughPack(renderer, params, false, containers))) return;
        BlazeSectionBatch batch = scene.distant(opaque);
        if (batch == null) return;
        if (opaque) renderer.gpuMark("beforeDistant");
        var buffers = BlazeGlBoundary.buffers();
        lastIndexName = 0;
        lastIndexBuffer = null;
        try {
            for (int index = 0; index < containers.size(); index++) {
                LodBufferContainer container = containers.get(index);
                if (!container.buffersUploaded) continue;
                float x = (float) (container.minCornerBlockPos.getX() - params.exactCameraPosition.x);
                float y = (float) (container.minCornerBlockPos.getY() - params.exactCameraPosition.y);
                float z = (float) (container.minCornerBlockPos.getZ() - params.exactCameraPosition.z);
                for (IVertexBufferWrapper wrapper : opaque ? container.vboOpaqueWrappers : container.vboTransparentWrappers) {
                    if (wrapper != null) add(renderer, buffers, batch, (GLVertexBuffer) wrapper, x, y, z);
                }
            }
        } finally {
            scene.distantDone(batch, opaque);
            if (opaque) renderer.gpuMark("distant");
        }
    }

    /** DH shares one quad index buffer between containers: looked up once per pass instead of once per container. */
    private static int lastIndexName, lastIndexGeneration;
    private static dev.overhaul.vulkan.GpuBuffer lastIndexBuffer;

    private static void add(BlazeRenderer renderer, BlazeGlBuffers buffers, BlazeSectionBatch batch, GLVertexBuffer vertices, float x, float y, float z) {
        GlQuadIndexBuffer indices = vertices.getQuadIBO();
        if (indices == null) return;
        // The locks only make the name and count a consistent pair; the draw records after they are released either way.
        long vertexState = consistent(vertices.renderStampLock, () -> pair(vertices.getId(), vertices.getVertexCount()));
        int vertexName = (int) (vertexState >>> 32), count = (int) vertexState;
        int indexName = (int) consistent(indices.renderStampLock, () -> indices.getId());
        if (count == 0 || vertexName == 0 || indexName == 0) return;
        // DH grows the shared buffer in place, so a new generation means a cached device buffer may be retired.
        int generation = buffers.generation();
        var vertexBuffer = vertices instanceof BlazeDhDeviceSlot.Holder holder
                ? holder.overhaul$deviceSlot().resolve(renderer, buffers, vertexName, generation)
                : buffers.device(renderer, vertexName);
        if (indexName != lastIndexName || generation != lastIndexGeneration || lastIndexBuffer == null) {
            lastIndexBuffer = buffers.device(renderer, indexName);
            lastIndexName = indexName;
            lastIndexGeneration = generation;
        }
        var indexBuffer = lastIndexBuffer;
        if (vertexBuffer != null && indexBuffer != null) batch.add(BlazePackDistant.MESH, vertexBuffer, indexBuffer, (int) (count * 1.5), x, y, z);
    }

    private static long pair(int high, int low) {
        return (long) high << 32 | low & 0xFFFFFFFFL;
    }

    /**
     * Reads under an optimistic stamp, which writes nothing to the lock; only a read that raced an upload takes the
     * read lock. Taking it for every buffer cost a compare-and-set and a waiter check on each of thousands of locks.
     */
    private static long consistent(java.util.concurrent.locks.StampedLock lock, java.util.function.LongSupplier read) {
        long stamp = lock.tryOptimisticRead();
        if (stamp != 0) {
            long value = read.getAsLong();
            if (lock.validate(stamp)) return value;
        }
        stamp = lock.readLock();
        try {
            return read.getAsLong();
        } finally {
            lock.unlockRead(stamp);
        }
    }

    private static void draw(BlazeDh dh, GLVertexBuffer vertices, float x, float y, float z) {
        GlQuadIndexBuffer indices = vertices.getQuadIBO();
        if (indices == null) return;
        long vertexStamp = vertices.renderStampLock.readLock(), indexStamp = indices.renderStampLock.readLock();
        try {
            int count = vertices.getVertexCount();
            if (count != 0 && vertices.getId() != 0 && indices.getId() != 0) dh.draw(vertices.getId(), indices.getId(), (int) (count * 1.5), x, y, z);
        } finally {
            vertices.renderStampLock.unlock(vertexStamp);
            indices.renderStampLock.unlock(indexStamp);
        }
    }

    private static BlazeDh.TerrainSettings terrainSettings(RenderParams params) {
        int curveRatio = Experimental.earthCurveRatio.get();
        float earthRadius = curveRatio >= -1 && curveRatio <= 1 ? 0 : 6371000.0F / curveRatio;
        float clip = RenderUtil.getNearClipPlaneInBlocks();
        if (!Debugging.lodOnlyMode.get()) clip += 16;
        int flags = (NoiseTexture.enableNoiseTexture.get() ? FLAG_NOISE : 0) | (Quality.ditherDhFade.get() ? FLAG_DITHER : 0) | (Debugging.enableWhiteWorld.get() ? FLAG_WHITE : 0);
        return new BlazeDh.TerrainSettings(columns(params.dhMvmProjMatrix), 0.01F, earthRadius, params.worldYOffset, clip,
                NoiseTexture.noiseSteps.get(), NoiseTexture.noiseIntensity.get(), NoiseTexture.noiseDropoff.get(), flags);
    }

    public static void captureFog(BlazeRenderer renderer, RenderParams params, DhApiFogRenderParam fog) {
        EDhApiHeightFogMixMode mode = fog.getHeightFogMixingMode();
        boolean spherical = mode == EDhApiHeightFogMixMode.SPHERICAL;
        var direction = fog.getHeightFogDirection();
        int flags = FOG_ON | (spherical ? FOG_SPHERICAL : 0) | (!spherical && mode != EDhApiHeightFogMixMode.CYLINDRICAL ? FOG_HEIGHT : 0)
                | (direction.basedOnCamera ? FOG_CAMERA : 0) | (direction.fogAppliesUp ? FOG_UP : 0) | (direction.fogAppliesDown ? FOG_DOWN : 0);
        Color color = fog.getFogColor();
        var level = Minecraft.getInstance().level;
        renderer.dh().fog(new BlazeDh.FogSettings(color.getRGBComponents(null), 1.0F / (Quality.lodChunkRenderDistanceRadius.get() * 16),
                1.0F / (level == null ? 384 : level.getHeight()), fog.getFarFogFalloff().value, fog.getFarFogStartPercent(),
                fog.getFarFogEndPercent() - fog.getFarFogStartPercent(), fog.getFarFogMinThickness(), fog.getFarFogMaxThickness() - fog.getFarFogMinThickness(),
                fog.getFarFogDensity(), fog.getHeightFogStartPercent(), fog.getHeightFogEndPercent() - fog.getHeightFogStartPercent(),
                fog.getHeightFogFalloff().value, mode.value, fog.getHeightFogBaseHeight(), (float) params.exactCameraPosition.y, flags));
    }

    /** What DH's generic pass does around its draws. A group runs in the pass matching its SSAO flag, so each is seen once a frame. */
    public static void drawBoxes(BlazeRenderer renderer, RenderParams params, Collection<RenderableBoxGroup> groups, boolean ssao, int vertices, int indices) {
        GlStateManager._enableCull();
        GlStateManager._enableBlend();
        GlStateManager._blendEquation(32774);
        GlStateManager._blendFuncSeparate(770, 771, 1, 771);
        List<BlazeDh.BoxGroup> draws = new ArrayList<>();
        for (RenderableBoxGroup group : groups) {
            if (group == null || group.ssaoEnabled != ssao) continue;
            group.preRender(params);
            if (!group.active) continue;
            group.tryUpdateInstancedDataAsync();
            if (group.vertexBufferContainer.getState() != EState.RENDER) continue;
            GlGenericObjectVertexContainer boxes = (GlGenericObjectVertexContainer) group.vertexBufferContainer;
            if (boxes.uploadedBoxCount > 0) draws.add(boxGroup(group, boxes, params.exactCameraPosition));
            group.postRender(params);
        }
        renderer.dh().drawBoxes(columns(params.dhMvmProjMatrix), vertices, indices, draws);
    }

    private static BlazeDh.BoxGroup boxGroup(RenderableBoxGroup group, GlGenericObjectVertexContainer boxes, DhVec3d camera) {
        DhApiVec3d origin = group.getOriginBlockPos();
        double[] from = {origin.x, origin.y, origin.z}, to = {camera.x, camera.y, camera.z};
        int[] chunk = new int[3];
        float[] sub = new float[3];
        for (int axis = 0; axis < 3; axis++) {
            double fromChunk = Math.floor(from[axis] / 16), toChunk = Math.floor(to[axis] / 16);
            chunk[axis] = (int) (fromChunk - toChunk);
            sub[axis] = (float) ((from[axis] - fromChunk * 16) - (to[axis] - toChunk * 16));
        }
        DhApiRenderableBoxGroupShading shading = group.shading != null ? group.shading : DhApiRenderableBoxGroupShading.getUnshaded();
        return new BlazeDh.BoxGroup(boxes.color, boxes.scale, boxes.chunkPos, boxes.subChunkPos, boxes.uploadedBoxCount, chunk, sub, group.getSkyLight(), group.getBlockLight(),
                new float[]{shading.north, shading.south, shading.east, shading.west, shading.top, shading.bottom});
    }

    /** The original copy leaves the frame's framebuffer bound, and everything after it draws there. */
    public static void composite(BlazeRenderer renderer, RenderParams params, int colorTexture, int depthTexture) {
        GlStateManager._glBindFramebuffer(36160, Minecraft.getInstance().getMainRenderTarget().frameBufferId);
        if (packDraws(renderer)) return;
        float far = RenderUtil.getFarClipPlaneDistanceInBlocks();
        renderer.dh().composite(colorTexture, depthTexture, columns(params.dhInverseMvmProjectionMatrix), far * 0.5F, far * 0.9F);
    }

    /** DH's own distances: the band starts past the clip plane its LODs are cut at, so what fades in is always there. */
    public static void vanillaFade(BlazeRenderer renderer, RenderParams params) {
        if (Debugging.lodOnlyMode.get() || packDraws(renderer)) return;
        float near = RenderUtil.getNearClipPlaneInBlocks() + 16;
        renderer.dh().vanillaFade(Minecraft.getInstance().getMainRenderTarget().getDepthTextureId(), columns(params.dhInverseMvmProjectionMatrix),
                columns(params.mcInverseMvmProjectionMatrix), near * 1.5F, near * 1.9F, params.clientLevelWrapper.getMaxHeight());
    }

    /** GL's column order of DH's row-major matrix. */
    private static float[] columns(DhApiMat4f m) {
        return new float[]{m.m00, m.m10, m.m20, m.m30, m.m01, m.m11, m.m21, m.m31, m.m02, m.m12, m.m22, m.m32, m.m03, m.m13, m.m23, m.m33};
    }

    private BlazeDhFrame() {}
}
