package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.platform.GlStateManager;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiHeightFogMixMode;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiFogRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GlQuadIndexBuffer;
import com.seibel.distanthorizons.core.config.Config.Client.Advanced.Debugging;
import com.seibel.distanthorizons.core.config.Config.Client.Advanced.Graphics.Experimental;
import com.seibel.distanthorizons.core.config.Config.Client.Advanced.Graphics.NoiseTexture;
import com.seibel.distanthorizons.core.config.Config.Client.Advanced.Graphics.Quality;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.RenderUtil;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import java.awt.Color;
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

    /** The original copy leaves the frame's framebuffer bound, and everything after it draws there. */
    public static void composite(BlazeRenderer renderer, RenderParams params, int colorTexture, int depthTexture) {
        GlStateManager._glBindFramebuffer(36160, Minecraft.getInstance().getMainRenderTarget().frameBufferId);
        float far = RenderUtil.getFarClipPlaneDistanceInBlocks();
        renderer.dh().composite(colorTexture, depthTexture, columns(params.dhInverseMvmProjectionMatrix), far * 0.5F, far * 0.9F);
    }

    /** GL's column order of DH's row-major matrix. */
    private static float[] columns(DhApiMat4f m) {
        return new float[]{m.m00, m.m10, m.m20, m.m30, m.m01, m.m11, m.m21, m.m31, m.m02, m.m12, m.m22, m.m32, m.m03, m.m13, m.m23, m.m33};
    }

    private BlazeDhFrame() {}
}
