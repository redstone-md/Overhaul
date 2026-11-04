package dev.overhaul.shader.pack;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic discovery; dimension folders replace, rather than merge with, root programs.
 * {@code diagnostics} block the pack, while {@code warnings} describe what it runs without.
 */
public record ShaderPackCatalog(List<Program> programs, ShaderPackProperties properties, List<String> diagnostics, List<PackDiagnostic> warnings, PackIdMap ids) {
    public ShaderPackCatalog { programs = List.copyOf(programs); diagnostics = List.copyOf(diagnostics); warnings = List.copyOf(warnings); }
    public ShaderPackCatalog(List<Program> programs, ShaderPackProperties properties, List<String> diagnostics) {
        this(programs,properties,diagnostics,List.of(),PackIdMap.empty());
    }

    public static ShaderPackCatalog discover(ShaderPackSource pack, Integer dimension, Map<String,Boolean> options) throws IOException {
        return discoverForDimension(pack,ShaderPackDimensions.legacyIdentifier(dimension),options);
    }

    public static ShaderPackCatalog discoverForDimension(ShaderPackSource pack, String dimension, Map<String,Boolean> options) throws IOException {
        return discoverForDimension(pack,dimension,options,Map.of());
    }

    /** The macros join discovered and selected options when shaders.properties conditionals are evaluated. */
    public static ShaderPackCatalog discoverForDimension(ShaderPackSource pack, String dimension, Map<String,Boolean> options, Map<String,String> macros) throws IOException {
        List<String> paths = pack.paths();
        String prefix = ShaderPackDimensions.select(pack,paths,dimension);
        PackOptionDiscovery.Result discovered = PackOptionDiscovery.discover(pack,paths);
        List<String> diagnostics = new ArrayList<>();
        List<PackDiagnostic> warnings = new ArrayList<>(discovered.diagnostics());
        ShaderPackProperties properties = properties(pack,paths,discovered.defines(options,macros),diagnostics);
        for (String key : properties.unsupportedProperties()) diagnostics.add("Unsupported pack property: "+key);
        for (String key : properties.ignoredProperties()) warnings.add(PackDiagnostic.degraded(PackDiagnostic.Category.PROPERTY,key,"Set by the pack but not honored; default behavior applies"));
        Map<String,Boolean> merged = discovered.merged(options);
        Set<String> names = new TreeSet<>();
        for (String path : paths) {
            if (!path.startsWith(prefix)) continue;
            String relative = path.substring(prefix.length());
            if (relative.contains("/")) continue;
            if (relative.endsWith(".vsh") || relative.endsWith(".fsh")) names.add(path.substring(0,path.length()-4));
            else if (relative.endsWith(".csh")) warnings.add(PackDiagnostic.degraded(PackDiagnostic.Category.PROGRAM,path,"Compute program is not run; features that depend on it stay off"));
            else if (relative.matches(".*\\.(gsh|tcs|tes)$")) diagnostics.add("Unsupported shader stage: "+path);
        }
        List<Program> programs = new ArrayList<>();
        for (String name : names) {
            if (!paths.contains(name+".vsh") || !paths.contains(name+".fsh")) {
                diagnostics.add("Incomplete vertex/fragment program: "+name); continue;
            }
            try { programs.add(new Program(name,name+".vsh",name+".fsh",properties.program(name,merged))); }
            catch (UnsupportedShaderException failure) { diagnostics.add(name+": "+failure.getMessage()); }
        }
        PackIdMap ids = PackIdMap.load(pack,paths,discovered.defines(options,macros),warnings);
        return new ShaderPackCatalog(programs,properties,diagnostics,warnings,ids);
    }

    /** A properties file that cannot be evaluated is reported like any other blocking pack feature. */
    private static ShaderPackProperties properties(ShaderPackSource pack, List<String> paths, Map<String,String> defines, List<String> diagnostics) throws IOException {
        if (!paths.contains("shaders.properties")) return ShaderPackProperties.parse("");
        String text = pack.read("shaders.properties");
        try { return ShaderPackProperties.parse(text,defines); }
        catch (IOException | IllegalArgumentException failure) {
            diagnostics.add("Invalid shaders.properties: "+failure.getMessage());
            return ShaderPackProperties.parse("");
        }
    }

    public record Program(String name, String vertexPath, String fragmentPath, ShaderPackProperties.ProgramState state) {}
}
