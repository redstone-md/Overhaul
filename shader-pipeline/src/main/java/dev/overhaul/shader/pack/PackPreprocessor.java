package dev.overhaul.shader.pack;

import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Uses shaderc's real macro/conditional evaluation, including conditional includes. */
public final class PackPreprocessor {
    private static final Pattern COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private final ShaderPackSource pack;
    private final ShadercCompiler compiler;
    private final Map<String,String> definitions;
    private final PackBooleanOptions options;

    public PackPreprocessor(ShaderPackSource pack, ShadercCompiler compiler, Map<String,String> definitions) {
        this(pack,compiler,definitions,Map.of());
    }

    public PackPreprocessor(ShaderPackSource pack, ShadercCompiler compiler, Map<String,String> definitions,
                            Map<String,Boolean> selectedOptions) {
        this.pack = java.util.Objects.requireNonNull(pack); this.compiler = java.util.Objects.requireNonNull(compiler);
        this.definitions = Map.copyOf(definitions);
        this.options = new PackBooleanOptions(selectedOptions);
        for (String name : selectedOptions.keySet()) if (definitions.containsKey(name)) {
            throw new IllegalArgumentException("A shader-pack boolean option cannot also be an external macro: " + name);
        }
    }

    public String process(String entry, ShaderStage stage) throws IOException {
        return processWithSources(entry,stage).text();
    }

    public ProcessedSource processWithSources(String entry, ShaderStage stage) throws IOException {
        Map<String,String> metadata = new LinkedHashMap<>();
        Map<String,Integer> sourceIds = new LinkedHashMap<>();
        String name = ShaderPackSource.normalize(entry);
        sourceIds.put(name,0);
        String text = preserveMetadata(options.apply(pack.read(name)),metadata);
        String result = compiler.preprocess(name,text,stage,definitions,(requested,requester,relative) -> {
            String include = requested;
            if (relative && !include.startsWith("/")) {
                Path parent = Path.of(requester).getParent();
                if (parent != null) include = parent+"/"+include;
            }
            include = ShaderPackSource.normalize(include);
            sourceIds.computeIfAbsent(include,ignored -> sourceIds.size());
            return new ShadercCompiler.ResolvedInclude(include,preserveMetadata(options.apply(pack.read(include)),metadata));
        });
        // shaderc may emit quoted file #line extensions; compiler diagnostics already
        // retain those paths, while compatibility output uses a fresh source name.
        result = result.replaceAll("(?m)^\\s*#line[^\\r\\n]*", "")
                .replaceAll("(?m)^\\s*#extension\\s+GL_GOOGLE_(?:include_directive|cpp_style_line_directive)[^\\r\\n]*", "");
        for (var entryMetadata : metadata.entrySet()) result = result.replace(entryMetadata.getKey(),entryMetadata.getValue());
        return new ProcessedSource(result,Map.copyOf(sourceIds));
    }

    private static String preserveMetadata(String source, Map<String,String> metadata) {
        if (source.contains("OVERHAUL_PACK_METADATA_")) throw new UnsupportedShaderException("Reserved preprocessing identifier in pack");
        var comments = COMMENT.matcher(source); StringBuffer result = new StringBuffer();
        while (comments.find()) {
            String comment = comments.group();
            if (comment.contains("DRAWBUFFERS") || comment.contains("RENDERTARGETS") || PackRenderTargets.hasDirective(comment) || PackShadowSettings.hasDirective(comment)) {
                String token = "OVERHAUL_PACK_METADATA_"+metadata.size()+"_END";
                metadata.put(token,comment);
                comments.appendReplacement(result,"\n"+token+"\n");
            } else comments.appendReplacement(result,java.util.regex.Matcher.quoteReplacement(comment));
        }
        comments.appendTail(result);
        return result.toString();
    }
    public record ProcessedSource(String text, Map<String,Integer> sourceIds) {}
}
