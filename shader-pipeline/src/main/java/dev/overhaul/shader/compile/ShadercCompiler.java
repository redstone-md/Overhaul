package dev.overhaul.shader.compile;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** Owns shaderc resources. Serialization makes close/compile races deterministic. */
public final class ShadercCompiler implements AutoCloseable {
    private long compiler;
    private long options;

    public ShadercCompiler() {
        compiler = shaderc_compiler_initialize();
        if (compiler == 0) throw new IllegalStateException("shaderc compiler initialization failed");
        options = shaderc_compile_options_initialize();
        if (options == 0) {
            shaderc_compiler_release(compiler);
            compiler = 0;
            throw new IllegalStateException("shaderc options initialization failed");
        }
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_1);
        shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
        shaderc_compile_options_set_warnings_as_errors(options);
    }

    public synchronized SpirvBinary compile(String name, String source, ShaderStage stage) {
        if (compiler == 0) throw new IllegalStateException("Compiler is closed");
        Objects.requireNonNull(name, "name"); Objects.requireNonNull(source, "source");
        Objects.requireNonNull(stage, "stage");
        long result = shaderc_compile_into_spv(compiler, source, stage.shadercKind(), name, "main", options);
        if (result == 0) throw new ShaderCompilationException(name + ": shaderc returned no result");
        try {
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                throw new ShaderCompilationException(name + ": " + shaderc_result_get_error_message(result));
            }
            ByteBuffer bytes = shaderc_result_get_bytes(result);
            if (bytes == null || bytes.remaining() == 0) {
                throw new ShaderCompilationException(name + ": shaderc returned empty SPIR-V");
            }
            return new SpirvBinary(bytes, stage);
        } finally {
            shaderc_result_release(result);
        }
    }

    /** Runs the real GLSL preprocessor before compatibility declaration analysis. */
    public synchronized String preprocess(String name, String source, ShaderStage stage,
                                          Map<String,String> definitions, IncludeResolver resolver) {
        if (compiler == 0) throw new IllegalStateException("Compiler is closed");
        Objects.requireNonNull(name); Objects.requireNonNull(source); Objects.requireNonNull(stage);
        rejectProfileMacros(source);
        long preprocessing = shaderc_compile_options_initialize();
        if (preprocessing == 0) throw new IllegalStateException("shaderc preprocessing initialization failed");
        // Build the include resolver after identifying the original language version.
        var version = java.util.regex.Pattern.compile("(?m)^\\s*#version\\s+(\\d+)[^\\r\\n]*").matcher(source);
        String languageVersion = version.find() ? version.group(1) : "110";
        IncludeResolver versionedResolver = resolver == null ? null : (requested,requester,relative) -> {
            var include = resolver.resolve(requested,requester,relative);
            rejectProfileMacros(include.text());
            return new ResolvedInclude(include.name(),include.text().replaceAll("\\b__VERSION__\\b",languageVersion));
        };
        try (var includes = new ShaderIncludes(versionedResolver)) {
            // shaderc's SPIR-V frontend refuses legacy #version even for preprocessing.
            // Upgrade only the parsing version while preserving the pack's __VERSION__ value.
            String prepared = source.replaceAll("\\b__VERSION__\\b",languageVersion)
                    .replaceAll("(?m)^\\s*#version[^\\r\\n]*", "#version 450");
            if (!prepared.contains("#version")) prepared = "#version 450\n"+prepared;
            definitions.forEach((macro,value) -> {
                if (!macro.matches("[A-Za-z_]\\w*") || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("Invalid macro definition: " + macro);
                }
                shaderc_compile_options_add_macro_definition(preprocessing,macro,value);
            });
            shaderc_compile_options_set_include_callbacks(preprocessing,includes.resolve,includes.release,0);
            long result = shaderc_compile_into_preprocessed_text(compiler,prepared,stage.shadercKind(),name,"main",preprocessing);
            if (result == 0) throw new ShaderCompilationException(name+": preprocessor returned no result");
            try {
                if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                    throw new ShaderCompilationException(name+": "+shaderc_result_get_error_message(result));
                }
                ByteBuffer bytes = shaderc_result_get_bytes(result);
                if (bytes == null || bytes.remaining() > 4_194_304) throw new ShaderCompilationException(name+": preprocessed source exceeds size limit");
                return StandardCharsets.UTF_8.decode(bytes.duplicate()).toString();
            } finally { shaderc_result_release(result); }
        } finally { shaderc_compile_options_release(preprocessing); }
    }

    @FunctionalInterface public interface IncludeResolver {
        ResolvedInclude resolve(String requested, String requester, boolean relative) throws Exception;
    }
    public record ResolvedInclude(String name, String text) {
        public ResolvedInclude { Objects.requireNonNull(name); Objects.requireNonNull(text); }
    }

    private static void rejectProfileMacros(String source) {
        // Preprocessing uses a modern shaderc frontend solely to expand tokens.
        // These frontend-specific macros would otherwise select the wrong branch
        // after temporarily upgrading a legacy #version directive.
        if (java.util.regex.Pattern.compile("\\b(GL_core_profile|GL_compatibility_profile|GL_es_profile|GL_SPIRV|VULKAN)\\b").matcher(source).find()) {
            throw new ShaderCompilationException("Profile/SPIR-V environment macro requires an explicit compatibility implementation");
        }
    }

    @Override public synchronized void close() {
        if (options != 0) { shaderc_compile_options_release(options); options = 0; }
        if (compiler != 0) { shaderc_compiler_release(compiler); compiler = 0; }
    }
}
