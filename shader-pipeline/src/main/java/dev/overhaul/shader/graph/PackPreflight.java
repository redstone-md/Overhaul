package dev.overhaul.shader.graph;

import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackCustomUniforms;
import dev.overhaul.shader.pack.PackDiagnostic;
import dev.overhaul.shader.pack.PackDiagnostic.Category;
import dev.overhaul.shader.pack.PackInterfaceCatalog;
import dev.overhaul.shader.pack.PackPreprocessor;
import dev.overhaul.shader.pack.PackProgramFallbacks;
import dev.overhaul.shader.pack.PackRenderTargets;
import dev.overhaul.shader.pack.PackShadowSettings;
import dev.overhaul.shader.pack.PackTextures;
import dev.overhaul.shader.pack.ShaderPackCatalog;
import dev.overhaul.shader.pack.ShaderPackSource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything standing between a pack and {@link CompiledShaderPack}, gathered in one pass. Compilation
 * stops at the first failure; this keeps going, so one report lists every blocker. It compiles and
 * decodes as much as a real generation does and creates no GPU state. An unblocked report predicts
 * only the checks made here: nothing has drawn, and {@link PackInterfaceCatalog} tracks adapter state.
 */
public final class PackPreflight {
    /** {@code plan} covers the programs that compiled; a failed program is a blocker and is absent from it. */
    public record Report(List<CompiledShaderPack.Program> programs, PackPassPlan plan, List<PackDiagnostic> diagnostics) {
        public Report { programs = List.copyOf(programs); diagnostics = List.copyOf(diagnostics); }
        public boolean blocked() { return diagnostics.stream().anyMatch(PackDiagnostic::blocking); }
    }
    private record Builders(PackPreprocessor preprocessor, PackRenderTargets.Builder targets, PackShadowSettings.Builder shadow, PackTextures.Builder textures) {}

    private PackPreflight() {}

    /** Only a pack that cannot be read during discovery throws; every later failure becomes a diagnostic. */
    public static Report analyze(ShaderPackSource source, String dimension, Map<String,Boolean> options,
                                 Map<String,String> macros, ShadercCompiler compiler) throws IOException {
        ShaderPackCatalog catalog = ShaderPackCatalog.discoverForDimension(source,dimension,options,macros);
        List<PackDiagnostic> diagnostics = new ArrayList<>(catalog.warnings());
        for (String blocker : catalog.diagnostics()) diagnostics.add(catalogDiagnostic(blocker));
        if (catalog.programs().size() > CompiledShaderPack.MAX_PROGRAMS) {
            diagnostics.add(PackDiagnostic.blocking(Category.PROGRAM,"pack","Exceeds the "+CompiledShaderPack.MAX_PROGRAMS+"-program generation bound"));
        }
        List<CompiledShaderPack.Program> programs = new ArrayList<>();
        List<PackPassPlan.Input> inputs = new ArrayList<>();
        Builders builders = builders(source,compiler,macros,options,catalog,diagnostics);
        Set<String> customUniforms = customUniforms(catalog,diagnostics);
        if (builders != null) {
            compilePrograms(catalog,builders,compiler,programs,inputs,diagnostics,customUniforms);
            finish(source,builders,programs,diagnostics);
        }
        reportFallbacks(catalog,diagnostics);
        PackPassPlan plan = PackPassPlan.plan(inputs,catalog.properties());
        diagnostics.addAll(plan.diagnostics());
        return new Report(programs,plan,diagnostics);
    }

    private static Builders builders(ShaderPackSource source, ShadercCompiler compiler, Map<String,String> macros, Map<String,Boolean> options,
                                     ShaderPackCatalog catalog, List<PackDiagnostic> diagnostics) {
        try {
            return new Builders(new PackPreprocessor(source,compiler,macros,options),new PackRenderTargets.Builder(catalog.properties()),
                    new PackShadowSettings.Builder(catalog.properties()),new PackTextures.Builder(catalog.properties()));
        } catch (IOException | RuntimeException failure) {
            diagnostics.add(PackDiagnostic.blocking(Category.PROPERTY,"shaders.properties",message(failure)));
            return null;
        }
    }

    private static Set<String> customUniforms(ShaderPackCatalog catalog, List<PackDiagnostic> diagnostics) {
        try {
            return PackCustomUniforms.parse(catalog.properties().values()).uniformTypes().keySet();
        } catch (RuntimeException failure) {
            diagnostics.add(PackDiagnostic.blocking(Category.UNIFORM,"shaders.properties",message(failure)));
            return Set.of();
        }
    }

    private static void compilePrograms(ShaderPackCatalog catalog, Builders builders, ShadercCompiler compiler, List<CompiledShaderPack.Program> programs,
                                        List<PackPassPlan.Input> inputs, List<PackDiagnostic> diagnostics, Set<String> customUniforms) {
        long characters = 0;
        for (ShaderPackCatalog.Program discovered : catalog.programs()) {
            String name = discovered.name();
            boolean enabled = discovered.state().enabled();
            if (!enabled || bare(name).startsWith("dh_")) { // The plan reports both.
                inputs.add(new PackPassPlan.Input(name,enabled,List.of(),Set.of(),Map.of()));
                continue;
            }
            try {
                CompiledShaderPack.Sources sources = CompiledShaderPack.preprocess(discovered,builders.preprocessor());
                characters += (long)sources.vertex().length()+sources.fragment().length();
                CompiledShaderPack.Program program = CompiledShaderPack.compileProgram(discovered,sources,compiler,builders.targets(),builders.shadow(),builders.textures());
                programs.add(program);
                inputs.add(PackPassPlan.Input.of(program));
                diagnostics.addAll(PackInterfaceCatalog.check(name,program.code().descriptors(),program.scene(),PackTextures.customSamplers(catalog.properties(),program.stage()),customUniforms));
            } catch (IOException | RuntimeException failure) {
                diagnostics.add(PackDiagnostic.blocking(Category.PROGRAM,name,message(failure)));
            }
        }
        if (characters > CompiledShaderPack.MAX_SOURCE_CHARACTERS) {
            diagnostics.add(PackDiagnostic.blocking(Category.PROGRAM,"pack","Expanded sources exceed the 64 million character generation bound"));
        }
    }

    /** The checks a generation makes over all programs together. */
    private static void finish(ShaderPackSource source, Builders builders, List<CompiledShaderPack.Program> programs, List<PackDiagnostic> diagnostics) {
        boolean failed = diagnostics.stream().anyMatch(PackDiagnostic::blocking);
        if (programs.isEmpty() && !failed) diagnostics.add(PackDiagnostic.blocking(Category.PROGRAM,"pack","No enabled executable programs"));
        try { builders.targets().build(); }
        catch (RuntimeException failure) { diagnostics.add(PackDiagnostic.blocking(Category.FORMAT,"colortex",message(failure))); }
        try { builders.shadow().build(); }
        catch (RuntimeException failure) { diagnostics.add(PackDiagnostic.blocking(Category.FORMAT,"shadow",message(failure))); }
        try { builders.textures().load(source,programs); }
        catch (IOException | RuntimeException failure) { diagnostics.add(PackDiagnostic.blocking(Category.SAMPLER,"textures",message(failure))); }
    }

    /** Reports the table the adapter's scene and shadow selection resolve through. */
    private static void reportFallbacks(ShaderPackCatalog catalog, List<PackDiagnostic> diagnostics) {
        Set<String> available = new TreeSet<>();
        for (ShaderPackCatalog.Program program : catalog.programs()) if (program.state().enabled()) available.add(bare(program.name()));
        Set<String> uncovered = new TreeSet<>();
        for (String program : PackProgramFallbacks.known()) {
            if (available.contains(program)) continue;
            var standIn = PackProgramFallbacks.resolve(program,available);
            if (standIn.isPresent()) diagnostics.add(PackDiagnostic.info(Category.PROGRAM,program,"Not provided; the fallback table resolves it to "+standIn.get()));
            else uncovered.add(program);
        }
        if (!uncovered.isEmpty()) diagnostics.add(PackDiagnostic.info(Category.PROGRAM,"fallbacks","No stand-in in this pack for: "+uncovered));
    }

    /** The catalog words its blockers as text for its compile-time callers. */
    private static PackDiagnostic catalogDiagnostic(String text) {
        Category category = text.startsWith("Unsupported shader stage") ? Category.STAGE
                : text.startsWith("Unsupported pack property") || text.startsWith("Invalid shaders.properties") ? Category.PROPERTY : Category.PROGRAM;
        return PackDiagnostic.blocking(category,"catalog",text);
    }

    private static String bare(String name) { return name.substring(name.lastIndexOf('/')+1); }
    private static String message(Exception failure) { return failure.getMessage() == null ? failure.toString() : failure.getMessage(); }
}
