package dev.overhaul.minecraft.blaze.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.shader.compat.UniformBlock;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackCustomUniforms;
import dev.overhaul.shader.pack.PackRenderStages;
import dev.overhaul.vulkan.PackGraphRuntime;
import org.joml.Matrix4f;

/** One frame's camera in pack coordinates. A name nothing here owns fails loudly instead of reading as zero. */
final class BlazePackUniforms implements PackGraphRuntime.Uniforms, PackCustomUniforms.Source {
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    private static final float[] ORTHOGRAPHIC = {2, 0, 0, 0, 0, 2, 0, 0, 0, 0, -2, 0, -1, -1, -1, 1};
    private static final float[] ORTHOGRAPHIC_INVERSE = {.5f, 0, 0, 0, 0, .5f, 0, 0, 0, 0, -.5f, 0, .5f, .5f, -.5f, 1};
    private static final float[] NORMAL_IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    private static final float[] LIGHTMAP_MATRIX = {1f / 256, 0, 0, 0, 0, 1f / 256, 0, 0, 0, 0, 1, 0, 8f / 256, 8f / 256, 0, 1};
    private final long started = System.nanoTime();
    private long previousTime = started;
    private final float[] projection = new float[16], projectionInverse = new float[16], view = new float[16], viewInverse = new float[16];
    private final float[] previousProjection = new float[16], previousView = new float[16];
    private final float[] modelViewProjection = new float[16], normal = new float[9];
    private final float[] fog = new float[4];
    private final Matrix4f matrix = new Matrix4f();
    private final BlazePackEnvironment environment = new BlazePackEnvironment();
    private final BlazePackCelestial celestial = new BlazePackCelestial();
    private final BlazePackPlayer player = new BlazePackPlayer();
    private final BlazePackPresence presence = new BlazePackPresence();
    private PackCustomUniforms custom;
    private int width, height, frame;
    private float frameTime, counter, near, far;
    private double x, y, z, previousX, previousY, previousZ;
    private boolean initialized;

    void capture(BlazePackCapture capture, CompiledShaderPack pack) {
        width = capture.width();
        height = capture.height();
        custom = pack.customUniforms();
        environment.capture(capture);
        celestial.capture(capture, pack.shadow());
        long now = System.nanoTime();
        frameTime = (float) ((now - previousTime) * 1e-9);
        previousTime = now;
        counter = (float) (((now - started) * 1e-9) % 3600);
        capture.projection().get(projection);
        capture.view().get(view);
        matrix.set(capture.projection()).invert().get(projectionInverse);
        matrix.set(capture.view()).invert().get(viewInverse);
        matrix.set(capture.projection()).mul(capture.view()).get(modelViewProjection);
        for (int column = 0; column < 3; column++) {
            for (int row = 0; row < 3; row++) normal[column * 3 + row] = viewInverse[row * 4 + column];
        }
        // The game's projection is GL clip space here, so near comes back from its depth terms.
        near = projection[14] / (projection[10] - 1);
        if (!Float.isFinite(near) || near <= 0) throw new UnsupportedShaderException("Unsupported shader-pack camera near plane");
        far = capture.minecraft().gameRenderer.getRenderDistance();
        var position = capture.camera().getPosition();
        x = position.x;
        y = position.y;
        z = position.z;
        System.arraycopy(RenderSystem.getShaderFogColor(), 0, fog, 0, 4);
        if (!initialized) {
            System.arraycopy(projection, 0, previousProjection, 0, 16);
            System.arraycopy(view, 0, previousView, 0, 16);
            previousX = x;
            previousY = y;
            previousZ = z;
        }
        player.capture(capture, frameTime, pack.smoothing(), pack.ids(), environment, near, capture.minecraft().gameRenderer.getDepthFar());
        presence.capture(capture);
        custom.update(frameTime, this);
    }

    float[] fog() {
        return fog;
    }

    org.joml.Matrix4f shadowView(org.joml.Matrix4f into) {
        return celestial.shadowView(into);
    }

    org.joml.Matrix4f shadowProjection(org.joml.Matrix4f into) {
        return celestial.shadowProjection(into);
    }

    @Override
    public double read(String name, int component) {
        return switch (name) {
            case "viewWidth" -> width;
            case "viewHeight" -> height;
            case "aspectRatio" -> (double) width / height;
            case "frameCounter" -> frame;
            case "frameTime" -> frameTime;
            case "frameTimeCounter" -> counter;
            case "near" -> near;
            case "far" -> far;
            case "cameraPosition" -> component == 0 ? x : component == 1 ? y : z;
            case "previousCameraPosition" -> component == 0 ? previousX : component == 1 ? previousY : previousZ;
            case "fogColor" -> fog[component];
            case "cameraPositionInt" -> Math.floor(component == 0 ? x : component == 1 ? y : z);
            case "cameraPositionFract" -> fraction(component == 0 ? x : component == 1 ? y : z);
            case "previousCameraPositionInt" -> Math.floor(component == 0 ? previousX : component == 1 ? previousY : previousZ);
            case "previousCameraPositionFract" -> fraction(component == 0 ? previousX : component == 1 ? previousY : previousZ);
            default -> {
                double value = celestial.read(name, component);
                if (Double.isNaN(value)) value = environment.read(name, component);
                if (Double.isNaN(value)) value = presence.read(name, component);
                yield Double.isNaN(value) ? player.read(name, component) : value;
            }
        };
    }

    @Override
    public void fill(CompiledShaderPack.Program program, UniformBlock values) {
        for (String name : values.bindings().uniforms().keySet()) {
            if (custom.fill(name, values)) continue;
            switch (name) {
                case "viewWidth" -> values.setFloat(name, width);
                case "viewHeight", "overhaulViewportHeight" -> values.setFloat(name, height);
                case "aspectRatio" -> values.setFloat(name, (float) width / height);
                case "frameCounter" -> values.setInt(name, frame);
                case "frameTime" -> values.setFloat(name, frameTime);
                case "frameTimeCounter" -> values.setFloat(name, counter);
                case "near" -> values.setFloat(name, near);
                case "far" -> values.setFloat(name, far);
                case "cameraPosition" -> values.setVector(name, (float) x, (float) y, (float) z);
                case "previousCameraPosition" -> values.setVector(name, (float) previousX, (float) previousY, (float) previousZ);
                case "fogColor" -> values.setVector(name, fog[0], fog[1], fog[2]);
                case "cameraPositionInt" -> values.setIntVector(name, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
                case "cameraPositionFract" -> values.setVector(name, (float) fraction(x), (float) fraction(y), (float) fraction(z));
                case "previousCameraPositionInt" -> values.setIntVector(name, (int) Math.floor(previousX), (int) Math.floor(previousY), (int) Math.floor(previousZ));
                case "previousCameraPositionFract" -> values.setVector(name, (float) fraction(previousX), (float) fraction(previousY), (float) fraction(previousZ));
                case "overhaulFogColor" -> values.setVector(name, fog[0], fog[1], fog[2], fog[3]);
                case "gbufferProjection" -> values.setMatrix(name, 4, projection);
                case "gbufferProjectionInverse" -> values.setMatrix(name, 4, projectionInverse);
                case "gbufferModelView" -> values.setMatrix(name, 4, view);
                case "gbufferModelViewInverse" -> values.setMatrix(name, 4, viewInverse);
                case "gbufferPreviousProjection" -> values.setMatrix(name, 4, previousProjection);
                case "gbufferPreviousModelView" -> values.setMatrix(name, 4, previousView);
                case "overhaulModelViewProjection" -> values.setMatrix(name, 4, program.scene() ? modelViewProjection : ORTHOGRAPHIC);
                case "overhaulProjection" -> values.setMatrix(name, 4, program.scene() ? projection : ORTHOGRAPHIC);
                case "overhaulProjectionInverse" -> values.setMatrix(name, 4, program.scene() ? projectionInverse : ORTHOGRAPHIC_INVERSE);
                case "overhaulModelView" -> values.setMatrix(name, 4, program.scene() ? view : IDENTITY);
                case "overhaulModelViewInverse" -> values.setMatrix(name, 4, program.scene() ? viewInverse : IDENTITY);
                case "overhaulTextureMatrix0" -> values.setMatrix(name, 4, IDENTITY);
                case "overhaulTextureMatrix1" -> values.setMatrix(name, 4, program.scene() ? LIGHTMAP_MATRIX : IDENTITY);
                case "overhaulNormalMatrix" -> values.setMatrix(name, 3, program.scene() ? normal : NORMAL_IDENTITY);
                case "alphaTestRef" -> values.setFloat(name, program.state().alphaTest().map(test -> test.reference()).orElse(0f));
                case "renderStage" -> values.setInt(name, PackRenderStages.of(program.name()));
                default -> {
                    if (!celestial.fill(name, values) && !environment.fill(name, values) && !presence.fill(name, values) && !player.fill(name, values)) {
                        throw new UnsupportedShaderException("Unbound shader-pack uniform: " + name + " in " + program.name());
                    }
                }
            }
        }
    }

    void complete() {
        System.arraycopy(projection, 0, previousProjection, 0, 16);
        System.arraycopy(view, 0, previousView, 0, 16);
        previousX = x;
        previousY = y;
        previousZ = z;
        frame = (frame + 1) % 720720;
        initialized = true;
    }

    void resetHistory() {
        initialized = false;
        player.reset();
    }

    private static double fraction(double value) {
        return value - Math.floor(value);
    }
}
