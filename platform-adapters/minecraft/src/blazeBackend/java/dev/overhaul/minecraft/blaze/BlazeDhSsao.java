package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.blaze.BlazeProgramCompiler;
import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.ImageFormat;
import dev.overhaul.vulkan.NativeGraphicsPipeline;
import java.util.List;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;

/**
 * DH's screen-space ambient occlusion over the LODs drawn so far: occlusion into an image of its own, then a
 * depth-aware blur of it that darkens the LOD colour and keeps its alpha.
 */
final class BlazeDhSsao implements AutoCloseable {
    private static final int OCCLUSION_BYTES = 128, APPLY_BYTES = 16;
    private static final int WRITE_RGBA = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    static final NativeGraphicsPipeline.Color REPLACE =new NativeGraphicsPipeline.Color(false, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD,
            VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD, WRITE_RGBA);
    /** DH's {@code glBlendFuncSeparate(ZERO, SRC_ALPHA, ZERO, ONE)}. */
    private static final NativeGraphicsPipeline.Color DARKEN = new NativeGraphicsPipeline.Color(true, VK_BLEND_FACTOR_ZERO, VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_OP_ADD,
            VK_BLEND_FACTOR_ZERO, VK_BLEND_FACTOR_ONE, VK_BLEND_OP_ADD, WRITE_RGBA);
    static final BlazeTerrainPass.Draw FULLSCREEN =(command, layout, multiDraw) -> vkCmdDraw(command, 3, 1, 0, 0);

    private final BlazeRenderer owner;
    private final BlazeTerrainPass.Pipelines pipelines;
    private final BlazeProgramCompiler.Program occlusion, apply;
    private GpuImage image;
    private GpuImageView view;
    private BlazeTarget occlusionTarget, applyTarget;
    private GpuImageView applyColor;

    /** @param fullscreen a program whose vertex stage covers the screen with one triangle */
    BlazeDhSsao(BlazeRenderer owner, ShadercCompiler compiler, BlazeProgramCompiler.Program fullscreen, BlazeTerrainPass.Pipelines pipelines) {
        this.owner = owner;
        this.pipelines = pipelines;
        occlusion = new BlazeProgramCompiler.Program(fullscreen.vertex(), compiler.compile("dh_ssao.fsh", BlazeDhShaders.SSAO_FRAGMENT, ShaderStage.FRAGMENT),
                new ProgramBindings(Map.of(), OCCLUSION_BYTES, Map.of("uDhDepthTexture", 1), Map.of(), Map.of(), Map.of()));
        apply = new BlazeProgramCompiler.Program(fullscreen.vertex(), compiler.compile("dh_ssao_apply.fsh", BlazeDhShaders.SSAO_APPLY_FRAGMENT, ShaderStage.FRAGMENT),
                new ProgramBindings(Map.of(), APPLY_BYTES, Map.of("uSourceColorTexture", 1, "uSourceDepthTexture", 2), Map.of(), Map.of(), Map.of()));
    }

    /**
     * @param projection DH's projection in GL's column order, and {@code inverse} its inverse
     * @param near DH's near clip plane in blocks, and {@code far} its far one
     */
    void render(int colorTexture, int depthTexture, float[] projection, float[] inverse, float near, float far) {
        var textures = owner.textures();
        BlazeTextures.Sampled color = textures.resolve(colorTexture), depth = textures.resolve(depthTexture);
        if (color.view() == null || depth.view() == null) return;
        int width = color.view().image().width(0), height = color.view().image().height(0);
        BlazeTarget target = occlusionTarget(width, height);
        var first = new BlazeTerrainPass(owner, target, raster(target, REPLACE), (bytes, w, h) -> {
            for (float value : projection) bytes.putFloat(value);
            for (float value : inverse) bytes.putFloat(value);
        }, occlusion, pipelines, name -> depth, "uDhDepthTexture");
        first.add(FULLSCREEN);
        owner.enqueue(first);
        // The depth is sampled, so the LOD colour is drawn to alone.
        var occluded = new BlazeTextures.Sampled(view, depth.sampler());
        BlazeTarget dhColor = applyTarget(color.view());
        var second = new BlazeTerrainPass(owner, dhColor, raster(dhColor, DARKEN), (bytes, w, h) -> bytes.putFloat(width).putFloat(height).putFloat(near).putFloat(far),
                apply, pipelines, name -> name.equals("uSourceColorTexture") ? occluded : depth, "uSourceDepthTexture");
        second.add(FULLSCREEN);
        owner.enqueue(second);
    }

    private BlazeTarget occlusionTarget(int width, int height) {
        if (occlusionTarget != null && occlusionTarget.width() == width && occlusionTarget.height() == height) return occlusionTarget;
        if (occlusionTarget != null) {
            occlusionTarget.close();
            owner.retire(view);
            owner.retire(image);
            occlusionTarget = null;
        }
        image = owner.allocator().createImage(ImageFormat.R16_SFLOAT, width, height, 1, 1, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
        view = image.view(0, 1, 0, 1);
        occlusionTarget = owner.packTarget(List.of(view), null);
        return occlusionTarget;
    }

    private BlazeTarget applyTarget(GpuImageView color) {
        if (applyTarget != null && applyColor == color) return applyTarget;
        if (applyTarget != null) applyTarget.close();
        applyTarget = owner.packTarget(List.of(color), null);
        applyColor = color;
        return applyTarget;
    }

    /** A fullscreen pass over the whole target with no depth test. */
    static BlazeDrawState.Snapshot raster(BlazeTarget target, NativeGraphicsPipeline.Color color) {
        return new BlazeDrawState.Snapshot(new NativeGraphicsPipeline.Depth(false, false, VK_COMPARE_OP_ALWAYS, 0, 0), color,
                NativeGraphicsPipeline.ColorLogic.disabled(), VK_CULL_MODE_NONE, VK_POLYGON_MODE_FILL, 1, 0, 0, target.width(), target.height(), false, 0, 0, 0, 0);
    }

    /** The renderer has waited for the device. */
    @Override public void close() {
        if (applyTarget != null) { applyTarget.close(); applyTarget = null; }
        if (occlusionTarget != null) { occlusionTarget.close(); occlusionTarget = null; }
        if (view != null) { view.close(); view = null; }
        if (image != null) { image.close(); image = null; }
    }
}
