package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.blaze.BlazeProgramCompiler;
import dev.overhaul.shader.compat.FragmentAlphaTest;
import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.compat.UniformBlock;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackRenderTargets;
import dev.overhaul.shader.pack.PackShadowSettings;
import dev.overhaul.shader.pack.ShaderPackProgram;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.NativeGraphicsPipeline;
import dev.overhaul.vulkan.PackGraphRuntime;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * One pack program as the game's draws use it. Uniforms the frame decides are filled once per phase; the matrices a
 * game draw decides overwrite their slots in the same bytes, so a draw costs a few matrix products and no block copy.
 */
final class BlazePackDraw implements BlazeProgramShape {
    private record GameSampler(String name, String semantic) {}
    final CompiledShaderPack.Program program;
    final BlazeProgramCompiler.Program code;
    private final BlazePackScene scene;
    private final UniformBlock values;
    private final ByteBuffer bytes;
    private final Map<String, Object> samplers = new HashMap<>();
    private final List<GameSampler> gameSamplers = new ArrayList<>();
    private final int modelView, modelViewInverse, modelViewProjection, normalMatrix, projection, projectionInverse, textureMatrix, alphaCutoff;
    private final Matrix4f model = new Matrix4f(), perspective = new Matrix4f(), written = new Matrix4f(), scratch = new Matrix4f();
    private final Matrix3f normal = new Matrix3f();
    private final boolean shadow, sectioned;
    private BlazeProgramCompiler.Program source;
    private int sourceModelView, sourceProjection, sourceChunkOffset, sourceTextureMatrix;
    private boolean projectionWritten;
    private BlazeTarget target;
    private int phase = -1;

    BlazePackDraw(BlazePackScene scene, CompiledShaderPack.Program program) {
        this(scene, program, program.code(), false);
    }

    /**
     * @param compiled the code this draw runs; the program still names it, so its targets, textures and uniforms resolve
     * @param sectioned the code reads each terrain section's offset per instance, so one draw covers a whole layer
     */
    BlazePackDraw(BlazePackScene scene, CompiledShaderPack.Program program, ShaderPackProgram compiled, boolean sectioned) {
        this.scene = scene;
        this.program = program;
        this.sectioned = sectioned;
        shadow = program.stage() == CompiledShaderPack.Stage.SHADOW;
        code = new BlazeProgramCompiler.Program(compiled.vertex(), compiled.fragment(), compiled.descriptors());
        ProgramBindings bindings = code.bindings();
        values = new UniformBlock(bindings);
        bytes = ByteBuffer.allocateDirect(Math.max(16, bindings.uniformBytes())).order(ByteOrder.nativeOrder());
        modelView = offset(bindings, "overhaulModelView");
        modelViewInverse = offset(bindings, "overhaulModelViewInverse");
        modelViewProjection = offset(bindings, "overhaulModelViewProjection");
        normalMatrix = offset(bindings, "overhaulNormalMatrix");
        projection = offset(bindings, "overhaulProjection");
        projectionInverse = offset(bindings, "overhaulProjectionInverse");
        textureMatrix = offset(bindings, "overhaulTextureMatrix0");
        alphaCutoff = offset(bindings, FragmentAlphaTest.CUTOFF);
    }

    /** Takes what the frame decided: swaps change targets and samples between the opaque and translucent phases. */
    void prepare(int current, PackGraphRuntime runtime, PackGraphRuntime.Uniforms uniforms) {
        if (phase == current) return;
        uniforms.fill(program, values);
        values.copyTo(bytes.clear());
        projectionWritten = false;
        samplers.clear();
        gameSamplers.clear();
        for (String name : code.bindings().samplers().keySet()) sample(name, runtime);
        List<GpuImageView> outputs = shadow ? runtime.shadowTargets(program) : runtime.sceneTargets(program);
        if (outputs.size() != program.code().framebuffer().colorTargets().size()) throw new UnsupportedShaderException("Program outputs do not match its targets: " + program.name());
        target = scene.target(outputs, shadow ? runtime.shadowDepth(0) : scene.depthFor(program));
        phase = current;
    }

    BlazeTarget target() {
        return target;
    }

    BlazeShaderSnapshot snapshot(BlazeShaderSnapshot game, float cutoff) {
        place(game);
        if (alphaCutoff >= 0) bytes.putFloat(alphaCutoff, cutoff);
        return bound(game.samplers());
    }

    /** A light-camera draw: no game shader is involved, so the caller decides the matrices and the textures. */
    BlazeShaderSnapshot cast(Matrix4fc modelMatrix, Matrix4fc light, Map<String, Object> textures) {
        model.set(modelMatrix);
        perspective.set(light);
        writeMatrices();
        if (textureMatrix >= 0) scratch.identity().get(textureMatrix, bytes);
        return bound(textures);
    }

    private BlazeShaderSnapshot bound(Map<String, Object> textures) {
        for (int index = 0; index < gameSamplers.size(); index++) {
            GameSampler sampler = gameSamplers.get(index);
            Object value = textures.get(sampler.semantic());
            samplers.put(sampler.name(), value == null ? scene.white() : value);
        }
        return new BlazeShaderSnapshot(code, bytes.clear().limit(Math.max(16, code.bindings().uniformBytes())), samplers);
    }

    private void sample(String name, PackGraphRuntime runtime) {
        if (runtime.packImageBinding(code.bindings(), name)) {
            boolean storage = code.bindings().storageImage(name);
            samplers.put(name, new BlazeTextures.Sampled(runtime.packImage(code.bindings(), name), storage ? 0 : runtime.packImageSampler(name), true));
            return;
        }
        var game = runtime.gameTexture(program, name);
        if (game != null) {
            samplers.put(name, new BlazeTextures.Sampled(game.view(), game.sampler()));
            return;
        }
        GpuImageView custom = runtime.customTexture(program, name);
        if (custom != null) {
            samplers.put(name, new BlazeTextures.Sampled(custom, runtime.customSamplerHandle(program, name)));
            return;
        }
        int shadowDepth = PackShadowSettings.depthIndex(name, code.bindings().samplers().keySet());
        if (shadowDepth >= 0) {
            boolean comparison = code.bindings().comparisonSampler(name);
            samplers.put(name, new BlazeTextures.Sampled(runtime.shadowDepthSample(shadowDepth, comparison), runtime.shadowSamplerHandle(shadowDepth, comparison)));
            return;
        }
        int shadowColor = PackShadowSettings.colorIndex(name);
        if (shadowColor >= 0) {
            samplers.put(name, new BlazeTextures.Sampled(runtime.shadowColor(shadowColor), runtime.shadowColorSamplerHandle(shadowColor)));
            return;
        }
        int color = PackRenderTargets.colorIndex(name);
        if (color >= 0) {
            samplers.put(name, new BlazeTextures.Sampled(runtime.color(color), runtime.samplerHandle()));
            return;
        }
        int depth = PackRenderTargets.depthIndex(name);
        if (depth >= 0) {
            samplers.put(name, new BlazeTextures.Sampled(runtime.sampledDepth(depth), runtime.depthSamplerHandle()));
            return;
        }
        switch (name) {
            case "texture", "tex", "gtexture" -> gameSamplers.add(new GameSampler(name, "Sampler0"));
            case "lightmap" -> gameSamplers.add(new GameSampler(name, "Sampler2"));
            case "overlay" -> gameSamplers.add(new GameSampler(name, "Sampler1"));
            case "normals" -> samplers.put(name, scene.flatNormal());
            case "specular" -> samplers.put(name, scene.noSpecular());
            case "dhDepthTex", "dhDepthTex0", "dhDepthTex1" -> {
                GpuImageView lods = scene.distantDepth(name.equals("dhDepthTex1") ? 1 : 0);
                if (lods == null) throw new UnsupportedShaderException("LOD depth without Distant Horizons terrain: " + name + " in " + program.name());
                samplers.put(name, new BlazeTextures.Sampled(lods, runtime.depthSamplerHandle()));
            }
            default -> throw new UnsupportedShaderException("Unbound scene pack sampler: " + name + " in " + program.name());
        }
    }

    /** The game draws sections relative to a chunk offset the pack's fixed-function matrices have to include. */
    private void place(BlazeShaderSnapshot game) {
        if (game.program() != source) {
            source = game.program();
            ProgramBindings bindings = source.bindings();
            sourceModelView = offset(bindings, "ModelViewMat");
            sourceProjection = offset(bindings, "ProjMat");
            sourceChunkOffset = offset(bindings, "ChunkOffset");
            sourceTextureMatrix = offset(bindings, "TextureMat");
        }
        ByteBuffer input = game.uniforms();
        if (sourceModelView >= 0) model.set(sourceModelView, input); else model.identity();
        if (sourceChunkOffset >= 0) model.translate(input.getFloat(sourceChunkOffset), input.getFloat(sourceChunkOffset + 4), input.getFloat(sourceChunkOffset + 8));
        if (sourceProjection >= 0) perspective.set(sourceProjection, input); else perspective.identity();
        writeMatrices();
        if (textureMatrix >= 0) {
            if (sourceTextureMatrix >= 0) scratch.set(sourceTextureMatrix, input); else scratch.identity();
            scratch.get(textureMatrix, bytes);
        }
    }

    private void writeMatrices() {
        if (modelView >= 0) model.get(modelView, bytes);
        if (modelViewInverse >= 0) model.invert(scratch).get(modelViewInverse, bytes);
        if (modelViewProjection >= 0) perspective.mul(model, scratch).get(modelViewProjection, bytes);
        if (normalMatrix >= 0) writeNormal();
        if (!projectionWritten || !written.equals(perspective)) {
            written.set(perspective);
            projectionWritten = true;
            if (projection >= 0) perspective.get(projection, bytes);
            if (projectionInverse >= 0) perspective.invert(scratch).get(projectionInverse, bytes);
        }
    }

    private void writeNormal() {
        model.normal(normal);
        for (int column = 0; column < 3; column++) for (int row = 0; row < 3; row++) {
            bytes.putFloat(normalMatrix + column * 16 + row * 4, normal.get(column, row));
        }
    }

    private static int offset(ProgramBindings bindings, String name) {
        var uniform = bindings.uniforms().get(name);
        return uniform == null ? -1 : uniform.byteOffset();
    }

    @Override
    public List<NativeGraphicsPipeline.Color> colors(NativeGraphicsPipeline.Color inherited) {
        List<NativeGraphicsPipeline.Color> colors = new ArrayList<>();
        var blends = program.state().blends();
        for (int output : program.code().framebuffer().colorTargets()) {
            boolean explicit = blends.containsKey("*") || blends.containsKey("colortex" + output);
            var chosen = output < 0 ? inherited : shadow ? PackGraphRuntime.shadowColorState(program.state(), output) : explicit ? PackGraphRuntime.colorState(program.state(), output) : inherited;
            colors.add(new NativeGraphicsPipeline.Color(chosen.blend(), chosen.sourceRgb(), chosen.destinationRgb(), chosen.operationRgb(),
                    chosen.sourceAlpha(), chosen.destinationAlpha(), chosen.operationAlpha(), output < 0 ? 0 : inherited.writeMask()));
        }
        return List.copyOf(colors);
    }

    @Override
    public List<NativeGraphicsPipeline.Binding> bindings(BlazeMeshState.Layout layout) {
        return sectioned ? BlazePackVertexInputs.sectionBindings(layout) : BlazePackVertexInputs.bindings(layout);
    }

    @Override
    public List<NativeGraphicsPipeline.Attribute> attributes(BlazeMeshState.Layout layout) {
        return sectioned ? BlazePackVertexInputs.sectionAttributes(code.bindings(), layout) : BlazePackVertexInputs.attributes(code.bindings(), layout);
    }

    @Override
    public long vertexConstants() {
        return scene.vertexConstants();
    }
}
