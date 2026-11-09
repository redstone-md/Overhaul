package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.compile.SpirvBinary;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles AMD's FidelityFX Super Resolution 2.2 GLSL passes, shipped under {@code overhaul/fsr2/}, and reads which
 * resource each binding of a pass names from the preprocessed text, so the host never keeps its own copy of the SDK's
 * binding tables.
 */
final class BlazeFsr2Shaders {
    private static final String ROOT = "/overhaul/fsr2/";
    private static final Pattern DECLARATION = Pattern.compile(
            "layout\\s*\\(\\s*set\\s*=\\s*(\\d+)\\s*,\\s*binding\\s*=\\s*(\\d+)[^)]*\\)\\s*(?:\\w+\\s+)*?uniform\\s+(\\w+)\\s*(\\w*)");

    enum Kind { SAMPLER, SAMPLED, STORAGE, UNIFORM }

    /** One binding of set 1; a uniform's name is its block's, such as {@code cbFSR2}. */
    record Binding(int binding, Kind kind, String name) {}

    /** A compiled pass, its set 1 bindings in binding order, and its thread group. */
    record Pass(String name, SpirvBinary spirv, List<Binding> bindings, int groupWidth, int groupHeight) {
        int uniforms() {
            int count = 0;
            for (Binding binding : bindings) if (binding.kind == Kind.UNIFORM) count++;
            return count;
        }
    }

    enum Stage {
        LUMINANCE("compute_luminance_pyramid", 256, 1, false),
        RECONSTRUCT("reconstruct_previous_depth", 8, 8, false),
        DEPTH_CLIP("depth_clip", 8, 8, false),
        LOCK("lock", 8, 8, false),
        ACCUMULATE("accumulate", 8, 8, false),
        ACCUMULATE_SHARPEN("accumulate", 8, 8, true),
        RCAS("rcas", 64, 1, false);

        final String file;
        final int groupWidth, groupHeight;
        final boolean sharpen;

        Stage(String file, int groupWidth, int groupHeight, boolean sharpen) {
            this.file = "ffx_fsr2_" + file + "_pass.glsl";
            this.groupWidth = groupWidth;
            this.groupHeight = groupHeight;
            this.sharpen = sharpen;
        }
    }

    private BlazeFsr2Shaders() {}

    static Pass compile(ShadercCompiler compiler, Stage stage) {
        String text = compiler.preprocess(stage.file, read(stage.file), ShaderStage.COMPUTE, definitions(stage.sharpen),
                (requested, requester, relative) -> new ShadercCompiler.ResolvedInclude(requested, read(requested)));
        return new Pass(stage.name(), compiler.compile(stage.file, text, ShaderStage.COMPUTE), bindings(stage, text), stage.groupWidth, stage.groupHeight);
    }

    /** The SDK's Vulkan options for 32-bit maths: low resolution motion vectors, GL-style (non-inverted) depth. */
    private static Map<String, String> definitions(boolean sharpen) {
        Map<String, String> definitions = new LinkedHashMap<>();
        definitions.put("FFX_GLSL", "1");
        definitions.put("FFX_GPU", "1");
        definitions.put("FFX_HALF", "0");
        definitions.put("FFX_FSR2_OPTION_UPSAMPLE_SAMPLERS_USE_DATA_HALF", "0");
        definitions.put("FFX_FSR2_OPTION_ACCUMULATE_SAMPLERS_USE_DATA_HALF", "0");
        definitions.put("FFX_FSR2_OPTION_REPROJECT_SAMPLERS_USE_DATA_HALF", "1");
        definitions.put("FFX_FSR2_OPTION_POSTPROCESSLOCKSTATUS_SAMPLERS_USE_DATA_HALF", "0");
        definitions.put("FFX_FSR2_OPTION_UPSAMPLE_USE_LANCZOS_TYPE", "2");
        definitions.put("FFX_FSR2_OPTION_REPROJECT_USE_LANCZOS_TYPE", "0");
        definitions.put("FFX_FSR2_OPTION_LOW_RESOLUTION_MOTION_VECTORS", "1");
        definitions.put("FFX_FSR2_OPTION_INVERTED_DEPTH", "0");
        definitions.put("FFX_FSR2_OPTION_JITTERED_MOTION_VECTORS", "0");
        definitions.put("FFX_FSR2_OPTION_HDR_COLOR_INPUT", "0");
        definitions.put("FFX_FSR2_OPTION_APPLY_SHARPENING", sharpen ? "1" : "0");
        return definitions;
    }

    private static List<Binding> bindings(Stage stage, String text) {
        Map<Integer, Binding> found = new LinkedHashMap<>();
        Matcher matcher = DECLARATION.matcher(text);
        while (matcher.find()) {
            int set = Integer.parseInt(matcher.group(1)), binding = Integer.parseInt(matcher.group(2));
            String type = matcher.group(3), name = matcher.group(4);
            if (set == 0) {
                if (!type.equals("sampler") || binding > 1) throw new IllegalStateException(stage.file + ": unexpected set 0 binding " + binding);
                continue;
            }
            if (set != 1) throw new IllegalStateException(stage.file + ": unexpected descriptor set " + set);
            Binding parsed = type.endsWith("_t") ? new Binding(binding, Kind.UNIFORM, type.substring(0, type.length() - 2))
                    : new Binding(binding, kind(stage, type), name);
            Binding previous = found.putIfAbsent(binding, parsed);
            if (previous != null && !previous.equals(parsed)) throw new IllegalStateException(stage.file + ": binding " + binding + " declared twice");
        }
        List<Binding> ordered = new ArrayList<>(found.values());
        ordered.sort(Comparator.comparingInt(Binding::binding));
        return List.copyOf(ordered);
    }

    private static Kind kind(Stage stage, String type) {
        return switch (type) {
            case "texture2D", "utexture2D" -> Kind.SAMPLED;
            case "image2D", "uimage2D" -> Kind.STORAGE;
            default -> throw new IllegalStateException(stage.file + ": unsupported resource type " + type);
        };
    }

    private static String read(String file) {
        try (InputStream in = BlazeFsr2Shaders.class.getResourceAsStream(ROOT + file)) {
            if (in == null) throw new IllegalStateException("Missing FSR2 shader source " + file);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
