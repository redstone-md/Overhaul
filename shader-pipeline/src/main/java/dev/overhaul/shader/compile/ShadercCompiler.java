package dev.overhaul.shader.compile;

import java.nio.ByteBuffer;
import java.util.Objects;
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

    @Override public synchronized void close() {
        if (options != 0) { shaderc_compile_options_release(options); options = 0; }
        if (compiler != 0) { shaderc_compiler_release(compiler); compiler = 0; }
    }
}
