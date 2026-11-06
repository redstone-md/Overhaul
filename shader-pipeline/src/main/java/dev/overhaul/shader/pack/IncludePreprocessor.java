package dev.overhaul.shader.pack;

import java.io.IOException;
import java.util.Map;
import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.compile.ShaderCompilationException;

/** Convenience API over the authoritative macro-aware pack preprocessor. */
public final class IncludePreprocessor {
    private final ShaderPackSource source;

    public IncludePreprocessor(ShaderPackSource source) { this.source = java.util.Objects.requireNonNull(source); }

    public ExpandedSource expand(String entry) throws IOException {
        ShaderStage stage = entry.endsWith(".fsh") ? ShaderStage.FRAGMENT : entry.endsWith(".csh") ? ShaderStage.COMPUTE : entry.endsWith(".gsh") ? ShaderStage.GEOMETRY : ShaderStage.VERTEX;
        try (var compiler = new ShadercCompiler()) {
            var processed = new PackPreprocessor(source,compiler,Map.of()).processWithSources(entry,stage);
            return new ExpandedSource(processed.text(),processed.sourceIds());
        } catch (ShaderCompilationException failure) { throw new IOException(failure.getMessage(),failure); }
    }

    public record ExpandedSource(String text, Map<String, Integer> sourceIds) {}
}
