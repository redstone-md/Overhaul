package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.blaze.BlazeProgramCompiler;
import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.ImageFormat;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import static org.lwjgl.vulkan.VK10.*;

/**
 * DH's temporal anti-aliasing over its LOD colour: the frame is blended with the last one reprojected through the
 * previous camera, into one of two history images, then sharpened back into the LOD colour. Like DH it keeps the
 * colour's alpha, which the composite reads as coverage.
 */
final class BlazeDhTaa implements AutoCloseable {
    private static final int TAA_BYTES = 224, SHARPEN_BYTES = 16;
    private static final float CAS_AMOUNT = 0.3F;

    /** DH's taa.frag, with its GL depth range fixed in. */
    private static final String TAA_FRAGMENT = """
            #version 450
            layout(set=0,binding=0,std140) uniform Taa { mat4 uDhProjectionInverse; mat4 uDhModelViewInverse; mat4 uDhPrevProjMvm; vec4 uCameraOffset; vec2 uViewSize; };
            layout(set=0,binding=1) uniform sampler2D uCurrentColorSampler;
            layout(set=0,binding=2) uniform sampler2D uCurrentDepthSampler;
            layout(set=0,binding=3) uniform sampler2D uHistoryColorSampler;
            layout(location=0) in vec2 texCoord;
            layout(location=0) out vec4 fragColor;
            const vec2 NEIGHBOURS[8] = vec2[8](vec2(-1.0, -1.0), vec2(0.0, -1.0), vec2(1.0, -1.0), vec2(-1.0, 0.0),
                    vec2(1.0, 0.0), vec2(-1.0, 1.0), vec2(0.0, 1.0), vec2(1.0, 1.0));
            vec2 reproject(float depth) {
                vec4 view = uDhProjectionInverse * vec4(vec3(texCoord, depth) * 2.0 - 1.0, 1.0);
                view = uDhModelViewInverse * (view / view.w);
                vec4 previous = uDhPrevProjMvm * (view + vec4(uCameraOffset.xyz, 0.0));
                return previous.xy / previous.w * 0.5 + 0.5;
            }
            vec3 neighbourClamp(vec3 current, vec3 history, vec2 texel) {
                vec3 low = current, high = current;
                for (int i = 0; i < 8; i++) {
                    vec3 color = textureLod(uCurrentColorSampler, texCoord + NEIGHBOURS[i] * texel, 0.0).rgb;
                    low = min(low, color);
                    high = max(high, color);
                }
                return clamp(history, low, high);
            }
            void main() {
                vec4 current = textureLod(uCurrentColorSampler, texCoord, 0.0);
                vec2 previous = reproject(textureLod(uCurrentDepthSampler, texCoord, 0.0).r);
                vec3 history = textureLod(uHistoryColorSampler, previous, 0.0).rgb;
                // No history yet; like DH this also skips perfectly black history.
                if (history == vec3(0.0)) { fragColor = current; return; }
                float blend = 0.0;
                if (previous.x > 0.0 && previous.x < 1.0 && previous.y > 0.0 && previous.y < 1.0)
                    blend = exp(-length((texCoord - previous) * uViewSize)) * 0.6 + 0.3;
                fragColor = vec4(mix(current.rgb, neighbourClamp(current.rgb, history, 1.0 / uViewSize), blend), current.a);
            }
            """;

    /** DH's contrast adaptive sharpen; the alpha is the anti-aliased colour's, not DH's blended 1. */
    private static final String SHARPEN_FRAGMENT = """
            #version 450
            layout(set=0,binding=0,std140) uniform Sharpen { vec2 uViewSize; float uCasAmount; };
            layout(set=0,binding=1) uniform sampler2D uCurrentColorSampler;
            layout(location=0) in vec2 texCoord;
            layout(location=0) out vec4 fragColor;
            vec3 at(vec2 offset) { return texture(uCurrentColorSampler, texCoord + offset / uViewSize).rgb; }
            void main() {
                vec4 center = texture(uCurrentColorSampler, texCoord);
                vec3 a = at(vec2(-1, -1)), b = at(vec2(0, -1)), c = at(vec2(1, -1)), d = at(vec2(-1, 0)), e = center.rgb;
                vec3 f = at(vec2(1, 0)), g = at(vec2(-1, 1)), h = at(vec2(0, 1)), i = at(vec2(1, 1));
                vec3 low = min(min(min(d, e), min(f, b)), h) + min(min(min(a, g), c), i);
                vec3 high = max(max(max(d, e), max(f, b)), h) + max(max(max(a, g), c), i);
                vec3 amp = inversesqrt(clamp(min(low, 2.0 - high) / high, 0.0, 1.0));
                vec3 weight = -1.0 / (amp * (8.0 - 3.0 * uCasAmount));
                fragColor = vec4(clamp(((b + d + f + h) * weight + e) / (1.0 + 4.0 * weight), 0.0, 1.0), center.a);
            }
            """;

    private final BlazeRenderer owner;
    private final BlazeTerrainPass.Pipelines pipelines;
    private final BlazeProgramCompiler.Program taa, sharpen;
    private final GpuImage[] images = new GpuImage[2];
    private final GpuImageView[] views = new GpuImageView[2];
    private final BlazeTarget[] targets = new BlazeTarget[2];
    private BlazeTarget dhTarget;
    private GpuImageView dhColor;
    private int history;
    private final Matrix4f previousProjMvm = new Matrix4f();
    private double previousX, previousY, previousZ;
    private boolean hasPrevious;

    /** @param fullscreen a program whose vertex stage covers the screen with one triangle */
    BlazeDhTaa(BlazeRenderer owner, ShadercCompiler compiler, BlazeProgramCompiler.Program fullscreen, BlazeTerrainPass.Pipelines pipelines) {
        this.owner = owner;
        this.pipelines = pipelines;
        taa = new BlazeProgramCompiler.Program(fullscreen.vertex(), compiler.compile("dh_taa.fsh", TAA_FRAGMENT, ShaderStage.FRAGMENT),
                new ProgramBindings(Map.of(), TAA_BYTES, Map.of("uCurrentColorSampler", 1, "uCurrentDepthSampler", 2, "uHistoryColorSampler", 3), Map.of(), Map.of(), Map.of()));
        sharpen = new BlazeProgramCompiler.Program(fullscreen.vertex(), compiler.compile("dh_taa_sharpen.fsh", SHARPEN_FRAGMENT, ShaderStage.FRAGMENT),
                new ProgramBindings(Map.of(), SHARPEN_BYTES, Map.of("uCurrentColorSampler", 1), Map.of(), Map.of(), Map.of()));
    }

    /**
     * @param projection DH's projection and {@code modelView} its model view, both in GL's column order
     * @param x the exact camera position, with {@code y} and {@code z}
     */
    void render(int colorTexture, int depthTexture, float[] projection, float[] modelView, double x, double y, double z) {
        var textures = owner.textures();
        BlazeTextures.Sampled color = textures.resolve(colorTexture), depth = textures.resolve(depthTexture);
        if (color.view() == null || depth.view() == null) return;
        int width = color.view().image().width(0), height = color.view().image().height(0);
        resize(width, height);
        Matrix4f projectionMatrix = new Matrix4f().set(projection), modelViewMatrix = new Matrix4f().set(modelView);
        float[] projectionInverse = new Matrix4f(projectionMatrix).invert().get(new float[16]);
        float[] modelViewInverse = new Matrix4f(modelViewMatrix).invert().get(new float[16]);
        float[] previous = (hasPrevious ? previousProjMvm : new Matrix4f(projectionMatrix).mul(modelViewMatrix)).get(new float[16]);
        float offsetX = hasPrevious ? (float) (x - previousX) : 0, offsetY = hasPrevious ? (float) (y - previousY) : 0, offsetZ = hasPrevious ? (float) (z - previousZ) : 0;

        int output = 1 - history;
        var historySampled = new BlazeTextures.Sampled(views[history], color.sampler());
        var blend = new BlazeTerrainPass(owner, targets[output], BlazeDhSsao.raster(targets[output], BlazeDhSsao.REPLACE), (bytes, w, h) -> {
            for (float value : projectionInverse) bytes.putFloat(value);
            for (float value : modelViewInverse) bytes.putFloat(value);
            for (float value : previous) bytes.putFloat(value);
            bytes.putFloat(offsetX).putFloat(offsetY).putFloat(offsetZ).putFloat(0).putFloat(width).putFloat(height);
        }, taa, pipelines, name -> switch (name) {
            case "uCurrentColorSampler" -> color;
            case "uCurrentDepthSampler" -> depth;
            default -> historySampled;
        }, "uCurrentColorSampler");
        blend.add(BlazeDhSsao.FULLSCREEN);
        owner.enqueue(blend);

        // The colour was read above, so the sharpened result can go straight back into it.
        var blended = new BlazeTextures.Sampled(views[output], color.sampler());
        BlazeTarget back = dhTarget(color.view());
        var sharpened = new BlazeTerrainPass(owner, back, BlazeDhSsao.raster(back, BlazeDhSsao.REPLACE),
                (bytes, w, h) -> bytes.putFloat(width).putFloat(height).putFloat(CAS_AMOUNT), sharpen, pipelines, name -> blended, "uCurrentColorSampler");
        sharpened.add(BlazeDhSsao.FULLSCREEN);
        owner.enqueue(sharpened);

        history = output;
        previousProjMvm.set(projectionMatrix).mul(modelViewMatrix);
        previousX = x;
        previousY = y;
        previousZ = z;
        hasPrevious = true;
    }

    /** New history starts black, which the blend reads as none. */
    private void resize(int width, int height) {
        if (targets[0] != null && targets[0].width() == width && targets[0].height() == height) return;
        for (int i = 0; i < 2; i++) {
            if (targets[i] != null) {
                targets[i].close();
                owner.retire(views[i]);
                owner.retire(images[i]);
            }
            images[i] = owner.allocator().createImage(ImageFormat.RGBA16_UNORM, width, height, 1, 1,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            owner.commands().clearColor(images[i], 0, 0, 0, 0, 0, 0);
            views[i] = images[i].view(0, 1, 0, 1);
            targets[i] = owner.packTarget(List.of(views[i]), null);
        }
        history = 0;
    }

    private BlazeTarget dhTarget(GpuImageView color) {
        if (dhTarget != null && dhColor == color) return dhTarget;
        if (dhTarget != null) dhTarget.close();
        dhTarget = owner.packTarget(List.of(color), null);
        dhColor = color;
        return dhTarget;
    }

    /** The renderer has waited for the device. */
    @Override public void close() {
        if (dhTarget != null) { dhTarget.close(); dhTarget = null; }
        for (int i = 0; i < 2; i++) {
            if (targets[i] != null) { targets[i].close(); targets[i] = null; }
            if (views[i] != null) { views[i].close(); views[i] = null; }
            if (images[i] != null) { images[i].close(); images[i] = null; }
        }
    }
}
